(ns eve.net.atom
  "Unit 4.4 (eve/doc/network-atoms/IMPL-PLAN.md §6.4, §6.0): the network atom in portable cljc — the protocol of
   vs/src/vs/net/remote_atom.vs, so a VS client and an EVE client share one atom (WIRE.md §2, §7):

   - the value is a map; its ROOT BLOB is a VSNP snapshot of the top-level entries (eve.net.snapshot: a scalar in the
     kernel's serial form, a collection as the 256-byte reference by content to its key) and the ROOT KEY is the
     blob's SHA-256; the entries are ordered by their key's XXH3-128, so every host writes the same blob for a value;
   - deref: the broker's deref (its lease held); when its root key is the one the handle last read or wrote, nothing
     more. Else the snapshot's entries are diffed against the handle's, and only the changed entries' blocks the handle
     lacks are fetched by key, level by level (eve.net.blobs: presigned GETs, each checked against its key), each
     fetched tree CHECKED CANONICAL before its keys are trusted (eve.net.codec decode-rec, MD-2's rule: a tree in any
     other form is refused) and decoded; every block fetched or uploaded is kept while the value reaches it (its key is
     its content);
   - swap!: f's value encoded against the handle's record of the value it replaces (eve.net.codec encode-rec, MD-4):
     a nested collection unchanged at its reference is cut by its known key — neither re-encoded nor HEADed — and only
     the changed paths of the top-level map's blocks (every client rebuilds the same ones, A10) and of the nested
     collections are imaged; the new tree's references are counted over the old one's, so the blocks reached for the
     first time — the only ones the store may lack — are HEADed and the missing PUT, beside the snapshot, and a GRFT V3
     graft (this swap's own: PUT with no HEAD) naming them; then the CAS (old key, new key, blob, count, graft, retired
     keys: the old root and the old blocks left unreferenced, which are exactly the ones the new value no longer
     reaches, walked only where the two trees do not share a key); a 409 backs off — 10 ms × 2^attempt, capped at 2 s,
     × (0.5 + a draw from the seeded PRNG) — and retries, at most 50 attempts; a 403 throws once, the handle usable
     after. NAMED LIMIT: a plain Clojure value records no reads, so a winner's graft never spares the backoff here (VS's
     kernel tracks a read Bloom: P7).

   The wire and store exchanges are STEPS (eve.net.run), run over the handle's platform — by default the host's own
   (eve.net.platform: a Clojure-like host plugs in its http adapter, adapters/http, and gets the rest from eve). Every
   operation is synchronous on every host: deref and swap! answer the value, never a promise. Config: eve.net.wire's {:endpoint :domain-id :atom-id :api-key}, plus :gak to encrypt (eve.net.blobs)."
  (:refer-clojure :exclude [deref swap! reset!])
  (:require [eve.net.run :as r]
            [eve.net.wire :as wire]
            [eve.net.blobs :as blobs]
            [eve.net.codec :as codec]
            [eve.net.snapshot :as snap]
            [eve.net.graft :as graft]
            [eve.net.platform :as platform]
            [eve.net.bytes :as b]
            [eve.read-region :as rr]
            [eve.crypto :as prim]
            [eve.host]))

(def MAX-RETRIES 50)
(def BASE-JITTER-MS 10)
(def MAX-JITTER-MS 2000)
(def LEASE-RENEW-AFTER-MS 40000)

(defn remote-atom
  "a handle on the network atom cfg names, its effects run over the host's platform (eve.net.platform: its http and
   process adapters) or the platform given"
  ([cfg] (remote-atom cfg (platform/platform)))
  ([cfg platform]
   {:cfg cfg :platform platform
    ;; (eve.net.codec's memos: a value's next encode and decode do only what changed)
    :memo (atom {}) :decoded (atom {})
    :state (atom {:value nil :root-key nil :epoch 0 :lease-id nil :lease-at 0 :blocks {}
                  :rng (r/rng (or (:seed platform) 1))})}))

(defn- run [ra step] (r/run (:platform ra) step))
(defn- now [ra] ((or (:now-ms (:platform ra)) (fn [] 0))))
(defn- st [ra] (clojure.core/deref (:state ra)))
(defn- st! [ra f & args] (apply clojure.core/swap! (:state ra) f args))

;; ── the snapshot (VSNP) of a map, and back: eve.net.snapshot's, which every client of the atom shares ─────────────

(def snapshot "{:blob :key :blocks} of a map (eve.net.snapshot/snapshot)" snap/snapshot)

;; collation (EM6 merge): the swap machinery below (snap-entries and the pruned swap) calls these
;; atom-local helpers; the EM6 extraction moved its own copies into eve.net.snapshot, whose serialize
;; is serialize-scalar only - the five helpers from main are restored verbatim here.
(defn- f64-bits [d] (let [bs (codec/f64-le d)] [(b/read-u32le bs 0) (b/read-u32le bs 4)]))

(defn- collection? [v] (or (map? v) (vector? v) (set? v) (seq? v)))

(defn- serialize
  "[the serial form of v, the blocks a collection value needs]"
  [v]
  (if (collection? v)
    (let [[bd blocks] (codec/band-of v)] [(snap/ref-slot (b/hex bd)) blocks])
    [(snap/serialize-scalar v f64-bits) {}]))

(defn- unserialize
  "the scalar a serial form names, or [::ref key] for a reference by content — eve.net.snapshot's reading, the one its
   writer (serialize-scalar) is held to (a copy kept here lacked tag 7, a long past int32: every network write of one
   failed at its reader)"
  [bs]
  (let [x (snap/unserialize bs)]
    (if (snap/ref? x) [::ref (second x)] x)))

(defn- ref? [x] (and (vector? x) (= ::ref (first x))))

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

;; The seen-set names no host (eve/CLAUDE.md "eve is portable"; eve/scripts/check-host-leak): the array is eve.host's
;; long-array / aget / aset / alength, which each adapter expands to its core's, and the fold reads the hex digits
;; through eve.net.bytes/hex-value — 13 digits, 52 bits, exact as a long and as a double. (merkle-phase7 wrote
;; the same six forms as reader conditionals, conditionals 0 -> 6 over eve/.host-leak's baseline; M0, 2026-10-25.)
(defn- key-fold
  "the first 13 hex chars of a block key, as a number (52 bits)"
  [k]
  (reduce (fn [acc c] (+ (* acc 16) (b/hex-value c))) 0 (subs k 0 13)))

(defn- seen-cap [seen] (eve.host/alength (:slots seen)))

(defn- seen-at [slots i] (eve.host/aget slots i))

(defn- seen-put! [slots i v] (eve.host/aset slots i (eve.host/long v)))

(defn- seen-slot
  "the slot of fold f in slots — the fold's own, or the empty slot it would take"
  [slots cap f]
  (loop [i (int (mod f cap))]
    (let [v (seen-at slots i)]
      (if (or (zero? v) (= v f)) i (recur (int (mod (inc i) cap)))))))

(defn empty-reached
  "a fresh seen-set for a fetch walk (fetch-tree-step starts from one)"
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

;; ── reading: the tree a snapshot names, fetched level by level ───────────────────────────────────────────────────

(defn- fetch-tree-step
  "a step answering [cache reached] — cache with every block reachable from roots (keys) fetched (one presign batch and
   GETs per level), and a seen-set (key folds — test with reached?) of every block reachable from roots (each one
   the store holds: it was fetched from there now, or was before and is named by a root the broker answered)"
  [cfg roots cache]
  (letfn [(level [frontier cache reached]
            (let [frontier (vec (distinct (remove (fn [k] (reached? reached k)) frontier)))
                  reached (see-all reached frontier)
                  want (filterv (fn [k] (not (contains? cache k))) frontier)
                  ;; (a block held already is walked here too: what it names is reached as well)
                  held (keep (fn [k] (get cache k)) frontier)]
              (if (empty? frontier)
                (r/done [cache reached])
                (r/then (if (empty? want) (r/done {}) (blobs/fetch-blocks cfg want))
                        (fn [got]
                          (level (concat (mapcat codec/child-keys (vals got)) (mapcat codec/child-keys held))
                                 (merge cache got) reached))))))]
    (level roots cache (empty-reached))))

;; ── the handle's tree: the value's record, its snapshot's entries, the references its blocks hold (MD-4) ────────
;; The handle keeps, beside the value, what the next swap needs to touch only what changed:
;;   :rec    the top-level map's record (eve.net.codec encode-rec: every entry's band, every node's key) — the blocks
;;           every client rebuilds the same (A10), the keys a graft names;
;;   :top    its root key (nil for the empty map, which uploads no block);
;;   :snap   the snapshot's entries: :of {key {:kb :vb :sk}} (the serial forms, the sort key XXH3-128(kb) hex), :idx
;;           {kb key}, :sorted {sk [kb vb]};
;;   :refs   {block key → the references to it}: the parents in the tree reached from :top that name it (each parent
;;           once), :top itself one — a block is in the value exactly while it is counted;
;;   :blocks {key image} of every counted block: every block uploaded or fetched, merged; a block no longer counted
;;           leaves it (MD-4 C1).
;; A swap counts the new tree's references before it drops the old one's, so a block both reach never reaches zero:
;; the blocks reached for the first time are exactly the ones the store may lack (the only ones HEADed), and the ones
;; left at zero exactly the old blocks the new value no longer reaches (the retired keys) — whatever the sharing
;; between subtrees, and walking each tree only where the other does not share a key (MD-4 C2).

(def ^:private EMPTY-SNAP {:of {} :idx {} :sorted (sorted-map)})

(defn- image-of [images k]
  (or (images k) (throw (ex-info (str "eve.net.atom: no image for the counted block " (subs k 0 16) "…") {:key k}))))

(defn- reach
  "[refs after one reference to each of ks, the keys counted for the first time]: a block counted for the first time
   counts one reference to each block its image names (images: a function of a key to its image)"
  [refs ks images]
  (loop [refs refs stack (vec ks) fresh []]
    (if (empty? stack)
      [refs fresh]
      (let [k (peek stack) stack (pop stack) n (get refs k 0)]
        (if (pos? n)
          (recur (assoc refs k (inc n)) stack fresh)
          (recur (assoc refs k 1) (into stack (codec/child-keys (image-of images k))) (conj fresh k)))))))

(defn- drop-refs
  "[refs after dropping one reference to each of ks, the keys no longer counted]: a block left at zero drops its
   references to the blocks its image names"
  [refs ks images]
  (loop [refs refs stack (vec ks) gone []]
    (if (empty? stack)
      [refs gone]
      (let [k (peek stack) stack (pop stack) n (get refs k 0)]
        (cond (> n 1) (recur (assoc refs k (dec n)) stack gone)
              (= n 1) (recur (dissoc refs k) (into stack (codec/child-keys (image-of images k))) (conj gone k))
              :else (recur refs stack gone))))))

(defn- recount
  "{:refs :fresh :gone} — refs moved from the tree under old-top to the tree under new-top (either nil: none)"
  [refs old-top new-top images]
  (let [[refs fresh] (if new-top (reach refs [new-top] images) [refs []])
        [refs gone] (if old-top (drop-refs refs [old-top] images) [refs []])]
    {:refs refs :fresh fresh :gone gone}))

(defn- entry-forms
  "[the snapshot's serial form of key k, of its value v]: a collection by reference to its band, from the record"
  [rec k v]
  [(first (serialize k)) (if (collection? v) (snap/ref-slot (b/hex (codec/rec-band-of rec k))) (first (serialize v)))])

(defn- snap-entries
  "the snapshot entries of the map m whose record is rec: the ones rec's delta names rebuilt over sn (all of them when rec
   has none: a full encode), the rest kept"
  [m rec sn]
  (let [delta (codec/rec-delta rec)
        sn (if delta sn EMPTY-SNAP)
        put (fn [sn k]
              (let [[kb vb] (entry-forms rec k (get m k))
                    sk (or (get-in sn [:of k :sk]) (b/hex (prim/xxh3-128 kb)))]
                (-> sn (assoc-in [:of k] {:kb kb :vb vb :sk sk}) (assoc-in [:idx kb] k) (update :sorted assoc sk [kb vb]))))
        del (fn [sn k]
              (let [{:keys [kb sk]} (get-in sn [:of k])]
                (-> sn (update :of dissoc k) (update :idx dissoc kb) (update :sorted dissoc sk))))
        sn (if delta
             (reduce del (reduce put sn (:set delta)) (:gone delta))
             (reduce put sn (keys m)))]
    sn))

(defn- snap-of
  "{:blob :key :snap} for the map m whose record is rec: its snapshot entries (snap-entries), the blob framed in
   XXH3-128 order"
  [m rec sn]
  (let [sn (snap-entries m rec sn)
        blob (snap/frame (vals (:sorted sn)))]
    {:blob blob :key (b/hex (prim/sha256 blob)) :snap sn}))

(defn- key-encodable? "has the codec an image for k as a map key" [k] (or (string? k) (keyword? k) (symbol? k) (number? k)))

(defn- known-of
  "a function of a value to the record decoded with exactly it"
  [pairs]
  (let [m (into {} (map (fn [[v rec]] [v [v rec]]) pairs))]
    (fn [x] (let [[o rec] (get m x)] (when (identical? o x) rec)))))

(defn- sync-step
  "a step answering the handle's state for the value the broker's deref dr names, from the state s the handle holds:
   the snapshot's entries diffed against the handle's; the changed entries' trees fetched (only blocks the handle
   lacks), each checked canonical before its keys are trusted and decoded (codec/decode-rec: MD-4 C3 — a tree in any
   other form is refused, ex-info :type :non-canonical); the value and its record updated by the changed entries alone;
   the references counted over the broker's tree and dropped over the handle's"
  [cfg dr s]
  (let [blob (:root-blob dr)
        entries (if (and (seq blob) (<= 8 (count blob))) (snap/unframe blob) [])
        sn (if (:rec s) (or (:snap s) EMPTY-SNAP) EMPTY-SNAP)
        ;; the entries whose value is not the one the handle holds, and the keys the broker's value no longer has
        changed (vec (keep (fn [[kb vb]]
                             (let [k (get (:idx sn) kb)]
                               (when-not (and (some? k) (= vb (get-in sn [:of k :vb])))
                                 [(if (some? k) k (unserialize kb)) (unserialize vb)])))
                           entries))
        held (count (filter (fn [[kb _]] (contains? (:idx sn) kb)) entries))
        removed (if (< held (count (:of sn)))
               (let [now (set (map first entries))] (vec (remove (fn [k] (contains? now (get-in sn [:of k :kb]))) (keys (:of sn)))))
               [])
        roots (keep (fn [[_ v]] (when (ref? v) (second v))) changed)]
    (r/then (fetch-tree-step cfg roots (:blocks s))
            (fn [[cache _reached]]
              (let [get-block (fn [k] (or (get cache k) (throw (ex-info "eve.net.atom: a block not fetched" {:key k}))))
                    decoded (mapv (fn [[k v]] [k (if (ref? v) (codec/decode-rec (b/unhex (second v)) get-block) [v nil])]) changed)
                    base (if (:rec s) (or (:value s) {}) {})
                    value (reduce (fn [m [k [v _]]] (assoc m k v)) (apply dissoc base removed) decoded)]
                (if-not (every? key-encodable? (keys value))
                  ;; a top-level key the codec has no image for (a VS writer's collection or nil key): read, never cut — the
                  ;; handle keeps no tree, and a swap encodes in full (and refuses it, as it always has)
                  (r/done {:value value :rec nil :top nil :refs {} :blocks cache :snap nil})
                  (let [[rec built] (codec/encode-rec value (:rec s) (known-of (keep (fn [[_ [v r]]] (when r [v r])) decoded)))
                        top (when (seq value) (codec/rec-key rec))
                        images (fn [k] (or (get built k) (get cache k)))
                        {:keys [refs fresh gone]} (recount (or (:refs s) {}) (:top s) top images)
                        blocks (apply dissoc (merge cache (select-keys built fresh)) gone)]
                    (r/done {:value value :rec rec :top top :refs refs :blocks blocks :snap (snap-entries value rec sn)}))))))))

(defn- release-lease! [ra lease-id]
  (when lease-id (run ra (wire/release-reader (:cfg ra) lease-id))))

(defn- load!
  "the broker's deref (its lease held), then the handle's tree brought to the value it names — nothing fetched or
   decoded when it is the value the handle last read or wrote (the same root key: the snapshot names the value whole)"
  [ra]
  (let [dr (run ra (wire/deref-atom (:cfg ra)))
        s (st ra)
        prev (:lease-id s)]
    ;; inside a `$` the lease it replaces is let go when the region exits, with the others the region holds
    (when (and prev (not= prev (:lease-id dr)))
      (when-not (rr/on-exit! (fn [] (release-lease! ra prev)))
        (release-lease! ra prev)))
    (st! ra assoc :lease-id (:lease-id dr) :lease-at (now ra) :epoch (:epoch dr))
    (if (and (:rec s) (= (:root-key dr) (:root-key s)))
      {:dr dr :value (:value s)}
      (let [t (run ra (sync-step (:cfg ra) dr s))]
        (st! ra merge t {:root-key (:root-key dr)})
        {:dr dr :value (:value t)}))))

;; collation (ET-3 merge): value-step is ET-3's public step (a gateway reads a writer's new value
;; through it); the MD-4 spine resolution of the section above dropped it — restored verbatim.
(defn value-step
  "the value a root blob names: its snapshot's entries, every block they reach fetched (cache first; presigned GETs,
   each checked against its key) and decoded — a step answering {:value :cache :reached} (:reached a seen-set of
   the blocks the root reaches — key folds, not the keys; test a key with reached? — a gateway reads a writer's
   new value this way: eve.net.gateway)"
  [cfg blob cache]
  (let [entries (if (and (seq blob) (<= 8 (count blob))) (snap/unframe blob) [])
        parsed (map (fn [[kb vb]] [(unserialize kb) (unserialize vb)]) entries)
        roots (keep (fn [[_ v]] (when (ref? v) (second v))) parsed)]
    (r/then (fetch-tree-step cfg roots cache)
            (fn [[cache reached]]
              (let [get-block (fn [k] (or (get cache k) (throw (ex-info "eve.net.atom: a block not fetched" {:key k}))))
                    value (into {} (map (fn [[k v]] [k (if (ref? v) (codec/value-of-band (b/unhex (second v)) get-block) v)]) parsed))]
                (r/done {:value value :cache cache :reached reached}))))))

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
       :else (if (= "ok" (:status (run ra (wire/renew-lease (:cfg ra) lease-id))))
               (do (st! ra assoc :lease-at t) :renewed)
               (do (release! ra) :lost))))))

;; ── writing ───────────────────────────────────────────────────────────────────────────────────────────────────────

(defn- write-attempt!
  "attempt!'s write: new-value (f's answer for the value read as dr) encoded, uploaded and CASed"
  [ra dr new-value]
  (let [s (st ra)
        _ (when-not (map? new-value)
            (throw (ex-info (str "eve.net.atom: the atom's value must be a map, not "
                                 (pr-str new-value))
                            {:type :not-a-map :value new-value})))
        [rec built] (codec/encode-rec new-value (:rec s))
        top (when (seq new-value) (codec/rec-key rec))
        images (fn [k] (or (get built k) (get (:blocks s) k)))
        {:keys [refs fresh gone]} (recount (or (:refs s) {}) (:top s) top images)
        {:keys [blob key snap]} (snap-of new-value rec (:snap s))
        upload (into {} (map (fn [k] [k (image-of images k)]) fresh))
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
        cfg (:cfg ra)]
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
          (st! ra assoc :epoch (:epoch res) :root-key key :value new-value :rec
               rec :top top :refs refs :snap snap :blocks
               (apply dissoc (merge (:blocks (st ra)) upload) gone))
          {:status "ok" :value new-value})
        res))))

(defn- attempt!
  "one attempt: {:status \"ok\" :value v} · the broker's conflict · its denial. f's value is encoded against the
   handle's record of the value it replaces (MD-4 C1: only the changed paths imaged, every unchanged collection kept at
   its reference by its key), its tree's references counted over the old tree's (C2): the blocks counted for the first
   time are HEADed and the missing PUT, beside the snapshot; the graft, which names this swap's epoch and roots, is PUT
   with no HEAD; the CAS retires the old root and the old blocks left uncounted. The handle's tree moves only when the
   CAS lands. A function that answers the very value it was given commits nothing: the attempt is a read (eve.thread's
   starved writer answers its value unchanged, then takes a turn — a no-op CAS would have to win the race it lost)"
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
  "the new value after (apply f value args), committed on the broker; retries conflicts, throws on a policy deny"
  [ra f & args]
  (loop [attempt 0]
    (when (>= attempt MAX-RETRIES)
      (throw (ex-info (str "swap! failed after " MAX-RETRIES " retries") {:type :retries-exhausted :attempts attempt})))
    (let [res (attempt! ra f args)
          s (:status res)]
      (cond
        (= s "ok") (:value res)
        (= s "conflict") (do ((:sleep (:platform ra)) (jitter-ms ra attempt)) (recur (inc attempt)))
        (= s "denied") (throw (ex-info (str "swap! denied: " (or (:reason res) "policy rejected"))
                                       {:type :policy-denied :reason (:reason res) :detail (:detail res) :by (:by res)}))
        :else (throw (ex-info (str "swap!: unexpected CAS result " (pr-str s)) {:result res}))))))

(defn reset! "sets the value" [ra v] (swap! ra (fn [_] v)))
