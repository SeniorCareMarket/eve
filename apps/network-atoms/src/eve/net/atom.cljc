(ns eve.net.atom
  "Unit 4.4 (eve/doc/network-atoms/IMPL-PLAN.md §6.4, §6.0): the network atom in portable cljc — the protocol of
   VS's src/vs/net/remote_atom.vs, so a VS client and an EVE client share one atom (WIRE.md §2, §7):

   - the value is a map; its ROOT BLOB is a VSNP snapshot of the top-level entries (eve.net.snapshot): a scalar
     entry inline as its canonical trunk bytes, a collection entry as a 256-byte reference (v3) to its trunk
     image in the store — the image is the entry value's eve.codec/value->eve-bytes, addressed by its SHA-256 —
     and the ROOT KEY is the blob's SHA-256; the entries are ordered by their key's XXH3-128, so every host
     writes the same blob for a value;
   - deref: the broker's deref (its lease held); when its root key is the one the handle last read or wrote,
     nothing more. Else the snapshot's entries are diffed against the handle's, and only the changed entries'
     images the handle lacks are fetched by key (eve.net.blobs: presigned GETs, each checked against its key),
     each fetched image CHECKED CANONICAL before its value is trusted (it must decode and re-encode to
     itself — the trunk form's counterpart of MD-2's rule) and decoded;
   - swap!: f's value imaged against the handle's record of the value it replaces: an entry whose value is
     unchanged keeps its image key (the key IS the entry's digest — the comparison costs an equality, not an
     imaging), a changed entry is imaged whole, and only the images counted for the first time — the only
     ones the store may lack — are HEADed and the missing PUT, beside the snapshot, and a GRFT V3 graft (this
     swap's own: PUT with no HEAD) naming them; then the CAS (old key, new key, blob, count, graft, retired
     keys: the old root and the images the new value no longer reaches); a 409 backs off — 10 ms × 2^attempt,
     capped at 2 s, × (0.5 + a draw from the seeded PRNG) — and retries, at most 50 attempts; a 403 throws
     once, the handle usable after. NAMED LIMIT: a plain Clojure value records no reads, so a winner's graft
     never spares the backoff here (VS's kernel tracks a read Bloom: P7).

   CONSOLIDATION (trunk audit entries 1 + 4 + 7, Nov 14 2026): the handle is a host type (NetAtom) above
   Eve's atom contract — deref / swap! / reset! / compare-and-set! — with the step runner (eve.net.run) as
   its transport; the map-only value, the broker CAS protocol, the leases and the graft stay net's own.
   Storage folded onto the trunk in the same change: entry images are Eve's canonical bytes
   (eve.codec/value->eve-bytes, SHA-256 addressed — eve.digest's scheme), and eve.net.codec's record
   machinery (encode-rec / decode-rec / rec-delta) no longer images this atom's values; the codec remains
   the VS-kernel WIRE codec at the app boundary (pages, the sealer). WATCHES: none yet — a named
   dependency on the trunk watch-surface build (eve.atom's lane: notify on every successful swap, flat
   delivery through the deref funnel); swap!'s success point below is the notify site when it lands. This
   namespace does not re-implement watches.

   The wire and store exchanges are STEPS (eve.net.run), run over the handle's platform — by default the host's own
   (eve.net.platform: a Clojure-like host plugs in its http adapter, adapters/http, and gets the rest from eve). Every
   operation is synchronous on every host: deref and swap! answer the value, never a promise. Config: eve.net.wire's {:endpoint :domain-id :atom-id :api-key}, plus :gak to encrypt (eve.net.blobs)."
  (:refer-clojure :exclude [deref swap! reset! compare-and-set!])
  (:require [eve.net.run :as r]
            [eve.net.wire :as wire]
            [eve.net.blobs :as blobs]
            [eve.net.snapshot :as snap]
            [eve.net.graft :as graft]
            [eve.net.platform :as platform]
            [eve.net.bytes :as b]
            [eve.read-region :as rr]
            [eve.crypto :as prim]
            [eve.host]
            [eve.type-readers]
            [eve.deftype-proto.macros :as eve]))

(def MAX-RETRIES 50)
(def BASE-JITTER-MS 10)
(def MAX-JITTER-MS 2000)
(def LEASE-RENEW-AFTER-MS 40000)

(declare deref swap! reset! compare-and-set!)

(eve/host-deftype NetAtom [cfg platform state]
                  clojure.lang.IDeref
                  (deref [this] (deref this))
                  clojure.lang.IAtom
                  (swap [this f] (swap! this (fn [v] (f v))))
                  (swap [this f a] (swap! this (fn [v] (f v a))))
                  (swap [this f a b] (swap! this (fn [v] (f v a b))))
                  (swap [this f a b more] (swap! this (fn [v] (apply f v a b more))))
                  (compareAndSet [this old new] (compare-and-set! this old new))
                  (reset [this new] (reset! this new)))

(defn remote-atom
  "a handle on the network atom cfg names, its effects run over the host's platform (eve.net.platform: its http and
   process adapters) or the platform given. The handle is a NetAtom: it answers the host atom interfaces
   (IDeref / IAtom) and the fns of this namespace (deref, swap!, reset!, compare-and-set!, cached, renew!,
   release!). Its state: the value and root key it last read or wrote, its lease, the snapshot entries it
   holds (each collection entry's image key is that entry's digest) and the images it counted."
  ([cfg] (remote-atom cfg (platform/platform)))
  ([cfg platform]
   (NetAtom. cfg platform
             (atom {:value nil :root-key nil :epoch 0 :lease-id nil :lease-at 0 :blocks {}
                    :entries nil
                    :rng (r/rng (or (:seed platform) 1))}))))

(defn- run [ra step] (r/run (.-platform ra) step))
(defn- now [ra] ((or (:now-ms (.-platform ra)) (fn [] 0))))
(defn- st [ra] (clojure.core/deref (.-state ra)))
(defn- st! [ra f & args] (apply clojure.core/swap! (.-state ra) f args))

;; ── the snapshot (VSNP) of a map, and back: eve.net.snapshot's, which every client of the atom shares ─────────────

(def snapshot "{:blob :key :blocks} of a map (eve.net.snapshot/snapshot)" snap/snapshot)

(defn- collection? [v] (or (map? v) (vector? v) (set? v) (seq? v)))

;; ── the fetch walk's seen-set: key folds, not keys (merkle plan §6/§8.2, Phase 7) ────────────────────────────────
;; The walk only ever asks membership of `reached`, but keyed by the 64-char hex block keys the set retained
;; ~118 B/key at 100k keys (measured, merkle-phase7 probe 1). This set keeps a 52-bit fold of each key — its
;; first 13 hex chars, a number exact as a JVM long and as a JS double, so every host folds a key to the same
;; value — in an open-addressed host array. Measured (probes 1/1b, the plan §8.2 interning row): 12.8 B/entry at
;; load factor 0.625 with membership flat against the 0.5 table; the add path grows at 0.6 into twice the
;; count, so at rest the table holds 13–16 B/entry. A fold collision can only make the walk skip a subtree
;; whose blocks it then cannot decode — loud, never a wrong value (fetched blocks are checked against their
;; keys); at 100k keys the chance per walk is ~1e-6. No false negatives: a key's fold enters at the level that
;; first names it. The array is the state, threaded by the walk like the set it replaces — it lives exactly as
;; long as the walk (or the step's answer), never longer.

;; The seen-set names no host (adapters/README.md, "eve is portable"; eve/scripts/check-host-leak): the array is eve.host's
;; long-array / aget / aset / alength, which each adapter expands to its core's, and the fold reads the hex digits
;; through eve.net.bytes/hex-value — 13 digits, 52 bits, exact as a long and as a double. (merkle-phase7 wrote
;; the same six forms as reader conditionals, conditionals 0 -> 6 over eve/.host-leak's baseline; M0, 2026-10-25.)
(defn- key-fold
  "the first 13 hex chars of a block key, as a number (52 bits)"
  [k]
  (reduce (fn [acc c] (+ (* acc 16) (b/hex-value c))) 0 (subs k 0 13)))

(defn- seen-cap [seen] (eve.host/alength (:slots seen)))

(defn- seen-at
  "slot i's fold, 0 when empty — a fresh long-array holds nil, not 0, on ClojureScript (its long-array fills with its
   init value, nil when none is given): read as 0, or a probe of an empty set never ends (measured: a page's first
   read of a network atom with blocks hung in seen-slot)"
  [slots i]
  (or (eve.host/aget slots i) 0))

(defn- seen-put! [slots i v] (eve.host/aset slots i (eve.host/long v)))

(defn- seen-slot
  "the slot of fold f in slots — the fold's own, or the empty slot it would take"
  [slots cap f]
  (loop [i (int (mod f cap))]
    (let [v (seen-at slots i)]
      (if (or (zero? v) (= v f)) i (recur (int (mod (inc i) cap)))))))

(defn empty-reached
  "a fresh seen-set for a fetch walk (fetch-images-step starts from one)"
  []
  {:slots (eve.host/long-array 1024) :n 0})

(defn reached?
  "is block key k in a fetch walk's seen-set (the :reached a step answers)?"
  [seen k]
  (let [slots (:slots seen)
        f (inc (key-fold k))]
    (= f (seen-at slots (seen-slot slots (seen-cap seen) f)))))

(defn- seen-grow
  "seen rehashed into twice its count of slots (the load factor back to ~0.5)"
  [seen]
  (let [old (:slots seen)
        ocap (seen-cap seen)
        ncap (max 1024 (* 2 (:n seen)))
        slots (eve.host/long-array ncap)]
    (loop [i 0]
      (when (< i ocap)
        (let [v (seen-at old i)]
          (when-not (zero? v) (seen-put! slots (seen-slot slots ncap v) v)))
        (recur (inc i))))
    (assoc seen :slots slots)))

(defn see-all
  "a fetch walk's seen-set with every key of ks folded in"
  [seen ks]
  (reduce (fn [seen k]
            (let [seen (if (< 0.6 (/ (inc (:n seen)) (seen-cap seen))) (seen-grow seen) seen)
                  slots (:slots seen)
                  f (inc (key-fold k))
                  i (seen-slot slots (seen-cap seen) f)]
              (if (zero? (seen-at slots i))
                (do (seen-put! slots i f) (update seen :n inc))
                seen)))
          seen ks))

;; ── reading: the images a snapshot names, fetched by key ─────────────────────────────────────────────────────────

(defn- fetch-images-step
  "a step answering cache with every image of keys fetched (one presign batch and GETs; each checked against its
   key by eve.net.blobs). A trunk image is a leaf: no level walk, no child keys."
  [cfg keys cache]
  (let [want (vec (distinct (remove (fn [k] (contains? cache k)) keys)))]
    (if (empty? want)
      (r/done cache)
      (r/then (blobs/fetch-blocks cfg want) (fn [got] (r/done (merge cache got)))))))

(defn- decode-image
  "a fetched trunk image, checked before its value is trusted: the fetch checked its address (SHA-256 = the
   key, eve.net.blobs); here the image must decode and re-encode to itself — the canonical form's check, the
   trunk bytes' counterpart of the kernel codec's CANON rule (MD-2): an image in any other form is refused"
  [ik img]
  (let [v (snap/trunk-value img)]
    (when (not= img (snap/trunk-bytes v))
      (throw (ex-info "eve.net.atom: a fetched image is not in canonical form" {:type :non-canonical :key ik})))
    v))

;; ── the handle's record of the value: its snapshot entries, imaged per entry ─────────────────────────────────────
;; The handle keeps, beside the value, what the next swap needs to touch only what changed:
;;   :entries {map key → {:kb :vb :sk :img :val}} — the snapshot's entry records: the key's and value's serial
;;            forms (:kb / :vb, the sort key XXH3-128(kb) as :sk), a collection entry's trunk image key (:img,
;;            nil for a scalar entry) and the entry's value itself (:val);
;;   :blocks  {image key → image} of every counted image: an image is in the value exactly while some entry
;;            names it (an image shared by equal entries is counted once per entry naming it).
;; A swap counts the new entries' images over the old ones': the images counted for the first time are exactly
;; the ones the store may lack (the only ones HEADed), and the ones left uncounted exactly the images the new
;; value no longer reaches (the retired keys). An unchanged entry is never re-imaged: its value compares equal
;; and its image key — the SHA-256 of its canonical bytes — is its digest.

(defn- image-of-entry
  "the entry record of k → v, against the held entries: an unchanged value keeps its record (no imaging, no
   hashing — equality is the digest comparison's front door); a changed or new collection is imaged whole and
   addressed by its SHA-256; a scalar is its trunk bytes inline"
  [held k v]
  (let [h (get held k)]
    (if (and h (= v (:val h)))
      h
      (let [kb (snap/trunk-bytes k)
            sk (b/hex (prim/xxh3-128 kb))]
        (if (collection? v)
          (let [img (snap/trunk-bytes v) ik (b/hex (prim/sha256 img))]
            (if (and h (= ik (:img h)))
              h
              {:kb kb :vb (snap/ref-slot ik) :sk sk :img ik :img-bytes img :val v}))
          {:kb kb :vb (snap/trunk-bytes v) :sk sk :img nil :val v})))))

(defn- img-counts "the {image key → count} an entries map names" [entries]
  (frequencies (keep :img (vals entries))))

(defn- recount
  "{:fresh :gone} — the image keys counted for the first time, and the ones no longer counted, between the
   held entries and the new entries"
  [held entries]
  (let [old (img-counts held) new (img-counts entries)]
    {:fresh (vec (filter (fn [ik] (zero? (get old ik 0))) (keys new)))
     :gone (vec (filter (fn [ik] (zero? (get new ik 0))) (keys old)))}))

(defn- sync-step
  "a step answering the handle's state for the value the broker's deref dr names, from the state s the handle
   holds: the snapshot's entries diffed against the handle's; the changed entries' images fetched (only the
   ones the handle lacks), each checked canonical before its value is trusted and decoded; the value and its
   entries updated by the changed entries alone; the images counted over the broker's entries and dropped
   over the handle's"
  [cfg dr s]
  (let [blob (:root-blob dr)
        raw (if (and (seq blob) (<= 8 (count blob))) (snap/unframe blob) [])
        held (or (:entries s) {})
        held-by-kb (into {} (map (fn [[k e]] [(:kb e) k]) held))
        parsed (mapv (fn [[kb vb]] {:kb kb :vb vb :k (snap/trunk-value kb)}) raw)
        changed (filterv (fn [{:keys [kb vb]}]
                           (let [hk (held-by-kb kb) h (and hk (get held hk))]
                             (not (and h (= vb (:vb h))))))
                         parsed)
        now-kbs (set (map :kb parsed))
        removed (filterv (fn [[_ e]] (not (contains? now-kbs (:kb e)))) held)
        ref-keys (keep (fn [{:keys [vb]}] (let [x (snap/unserialize vb)] (when (snap/ref? x) (second x)))) changed)]
    (r/then (fetch-images-step cfg ref-keys (:blocks s))
            (fn [cache]
              (let [decode (fn [{:keys [kb vb k]}]
                             (let [x (snap/unserialize vb)
                                   sk (b/hex (prim/xxh3-128 kb))]
                               (if (snap/ref? x)
                                 (let [ik (second x)
                                       img (or (get cache ik) (throw (ex-info "eve.net.atom: an image not fetched" {:key ik})))]
                                   [k {:kb kb :vb vb :sk sk :img ik :val (decode-image ik img)}])
                                 [k {:kb kb :vb vb :sk sk :img nil :val x}])))
                    fresh-entries (into {} (map decode changed))
                    gone-ks (set (map first removed))
                    entries (into {} (concat (remove (fn [[k _]] (or (gone-ks k) (contains? fresh-entries k))) held)
                                             fresh-entries))
                    value (into {} (map (fn [[k e]] [k (:val e)])) entries)
                    counts (img-counts entries)
                    blocks (into {} (filter (fn [[ik _]] (pos? (get counts ik 0))))
                                 (merge (:blocks s) cache))]
                (r/done {:value value :entries entries :blocks blocks}))))))

(defn- release-lease! [ra lease-id]
  (when lease-id (run ra (wire/release-reader (.-cfg ra) lease-id))))

(defn- load!
  "the broker's deref (its lease held), then the handle's entries brought to the value it names — nothing fetched
   or decoded when it is the value the handle last read or wrote (the same root key: the snapshot names the
   value whole)"
  [ra]
  (let [dr (run ra (wire/deref-atom (.-cfg ra)))
        s (st ra)
        prev (:lease-id s)]
    ;; inside a `$` the lease it replaces is let go when the region exits, with the others the region holds
    (when (and prev (not= prev (:lease-id dr)))
      (when-not (rr/on-exit! (fn [] (release-lease! ra prev)))
        (release-lease! ra prev)))
    (st! ra assoc :lease-id (:lease-id dr) :lease-at (now ra) :epoch (:epoch dr))
    (if (and (:entries s) (= (:root-key dr) (:root-key s)))
      {:dr dr :value (:value s)}
      (let [t (run ra (sync-step (.-cfg ra) dr s))]
        (st! ra merge t {:root-key (:root-key dr)})
        {:dr dr :value (:value t)}))))

;; collation (ET-3 merge): value-step is ET-3's public step (a gateway reads a writer's new value
;; through it); the MD-4 spine resolution of the section above dropped it — restored verbatim.
(defn value-step
  "the value a root blob names: its snapshot's entries, every image they reach fetched (cache first; presigned
   GETs, each checked against its key), each checked canonical and decoded — a step answering {:value :cache
   :reached} (:reached a seen-set of the images the root reaches — key folds, not the keys; test a key with
   reached? — a gateway reads a writer's new value this way: eve.net.gateway)"
  [cfg blob cache]
  (let [entries (if (and (seq blob) (<= 8 (count blob))) (snap/unframe blob) [])
        parsed (map (fn [[kb vb]] [(snap/trunk-value kb) (snap/unserialize vb)]) entries)
        roots (keep (fn [[_ v]] (when (snap/ref? v) (second v))) parsed)]
    (r/then (fetch-images-step cfg roots cache)
            (fn [cache]
              (let [value (into {} (map (fn [[k v]] [k (if (snap/ref? v) (decode-image (second v) (or (get cache (second v)) (throw (ex-info "eve.net.atom: an image not fetched" {:key (second v)})))) v)]) parsed))]
                (r/done {:value value :cache cache :reached (see-all (empty-reached) roots)}))))))

(defn release!
  "releases the held lease, if any"
  [ra]
  (release-lease! ra (:lease-id (st ra)))
  (st! ra assoc :lease-id nil :lease-at 0)
  nil)

(defn deref
  "the atom's current value (a map; {} when empty), freshly read from the broker; the lease is held — inside a `$`
   (eve.alpha/$), until the region exits, when every lease its derefs took is released"
  [ra]
  (let [v (:value (load! ra))]
    (rr/hold! ra (fn []) (fn [] (release! ra)))
    v))

(defn cached "the value of the last deref / swap!, no network" [ra] (:value (st ra)))

(defn renew!
  "renews the held lease when older than LEASE-RENEW-AFTER-MS (or force?): :renewed, :fresh, :none, or :lost (the broker
   does not know it — production's Worker has no renew route: the lease is released, best-effort)"
  ([ra] (renew! ra false))
  ([ra force?]
   (let [{:keys [lease-id lease-at]} (st ra)
         t (now ra)]
     (cond
       (nil? lease-id) :none
       (and (not force?) (< (- t lease-at) LEASE-RENEW-AFTER-MS)) :fresh
       :else (if (= "ok" (:status (run ra (wire/renew-lease (.-cfg ra) lease-id))))
               (do (st! ra assoc :lease-at t) :renewed)
               (do (release! ra) :lost))))))

;; ── writing ───────────────────────────────────────────────────────────────────────────────────────────────────────

(defn- write-attempt!
  "attempt!'s write: new-value (f's answer for the value read as dr) imaged per entry, uploaded and CASed"
  [ra dr new-value]
  (let [s (st ra)
        _ (when-not (map? new-value)
            (throw (ex-info (str "eve.net.atom: the atom's value must be a map, not "
                                 (pr-str new-value))
                            {:type :not-a-map :value new-value})))
        held (or (:entries s) {})
        entries (into {} (map (fn [[k v]] [k (image-of-entry held k v)])) new-value)
        {:keys [fresh gone]} (recount held entries)
        blob (snap/frame (map (fn [[_ e]] [(:kb e) (:vb e)]) (sort-by (fn [[_ e]] (:sk e)) entries)))
        key (b/hex (prim/sha256 blob))
        upload (into {} (map (fn [ik] [ik (or (some (fn [[_ e]] (when (= ik (:img e)) (:img-bytes e))) entries)
                                              (get (:blocks s) ik)
                                              (throw (ex-info (str "eve.net.atom: no image for the counted key " (subs ik 0 16) "…") {:key ik})))])
                             fresh))
        retired (vec (sort gone))
        old-key (:root-key dr)
        branch (vec (sort fresh))
        gblob (graft/encode {:old-root (when (and (string? old-key)
                                                  (= 64 (count old-key)))
                                         old-key)
                             :new-root key
                             :epoch (or (:epoch dr) 0)
                             :timestamp-ms (now ra)
                             :branch branch
                             :blobs branch
                             :retired retired})
        gsha (b/hex (prim/sha256 gblob))
        cfg (.-cfg ra)]
    (run ra (blobs/upload-blocks cfg (assoc upload key blob) {gsha gblob}))
    (let [res (run ra
                   (wire/cas-root cfg
                                  {:old-key old-key
                                   :new-key key
                                   :root-blob blob
                                   :root-count (count new-value)
                                   :graft-sha gsha
                                   :retired-keys (into (if (nil? old-key)
                                                         []
                                                         [old-key]) retired)}))]
      (release! ra)
      (if (= "ok" (:status res))
        (do
          (st! ra assoc :epoch (:epoch res) :root-key key :value new-value :entries
               entries :blocks
               (into {} (filter (fn [[ik _]] (pos? (get (img-counts entries) ik 0))))
                     (merge (:blocks (st ra)) upload)))
          {:status "ok" :value new-value})
        res))))

(defn- attempt!
  "one attempt: {:status \"ok\" :value v} · the broker's conflict · its denial. f's value is imaged against the
   handle's record of the value it replaces (only the changed entries imaged, every unchanged entry kept at
   its image key), its images counted over the old entries' (the ones counted for the first time are HEADed
   and the missing PUT, beside the snapshot; the graft, which names this swap's epoch and roots, is PUT with
   no HEAD; the CAS retires the old root and the images left uncounted. The handle's record moves only when
   the CAS lands. A function that answers the very value it was given commits nothing: the attempt is a read
   (eve.thread's starved writer answers its value unchanged, then takes a turn — a no-op CAS would have to
   win the race it lost)"
  [ra f args]
  (let [{:keys [dr value]} (load! ra)
        new-value (apply f value args)]
    (if (identical? new-value value)
      (do
        (release! ra)
        {:status "ok" :value value})
      (write-attempt! ra dr new-value))))

(defn- jitter-ms [ra attempt]
  (let [base (min (* BASE-JITTER-MS (nth (iterate (fn [x] (* 2 x)) 1) (min attempt 20))) MAX-JITTER-MS)
        [d nxt] (r/next-double (:rng (st ra)))]
    (st! ra assoc :rng nxt)
    (* base (+ 0.5 d))))

(defn swap!
  "the new value after (apply f value args), committed on the broker; retries conflicts, throws on a policy deny.
   (The trunk watch build's notify site, when it lands, is this fn's success — see the ns docstring.)"
  [ra f & args]
  (loop [attempt 0]
    (when (>= attempt MAX-RETRIES)
      (throw (ex-info (str "swap! failed after " MAX-RETRIES " retries") {:type :retries-exhausted :attempts attempt})))
    (let [res (attempt! ra f args)
          s (:status res)]
      (cond
        (= s "ok") (:value res)
        (= s "conflict") (do ((:sleep (.-platform ra)) (jitter-ms ra attempt)) (recur (inc attempt)))
        (= s "denied") (throw (ex-info (str "swap! denied: " (or (:reason res) "policy rejected"))
                                       {:type :policy-denied :reason (:reason res) :detail (:detail res) :by (:by res)}))
        :else (throw (ex-info (str "swap!: unexpected CAS result " (pr-str s)) {:result res}))))))

(defn reset! "sets the value" [ra v] (swap! ra (fn [_] v)))

(defn compare-and-set!
  "one CAS attempt, the atom contract's: true when the atom's current value is `old` and the write of `new`
   lands; false when the current value is not `old`, or when the broker's CAS conflicts (another writer
   won). No retries — swap! is the retrying form. The mechanism is net's own: a broker CAS by root key
   under the lease load! takes."
  [ra old new]
  (let [{:keys [dr value]} (load! ra)]
    (cond
      (not= value old) (do (release! ra) false)
      (identical? new value) (do (release! ra) true)
      :else (= "ok" (:status (write-attempt! ra dr new))))))
