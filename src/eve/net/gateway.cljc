(ns eve.net.gateway
  "A GATEWAY (C3): eve.net's broker routes — WIRE.md §2's, as eve.net.wire speaks them (test support's
   eve.net.stand-in, test/eve/net/stand_in.cljc, models production's) — served over the atoms of an eve domain. Any process attached to a disk domain can serve its
   atoms this way, and runtimes on other machines reach them as network atoms (eve.net.atom): one atom, its disk
   writers and its network writers.

     (def g (gateway {:domain d :endpoint \"http://host:port\" :domain-id \"d1\" :api-key k
                      :presign (hmac-presigner {:url \"http://store/blobs/\" :secret s}) :platform p :deny f}))
     ((:handle g) {:method \"POST\" :url \"…/v1/domains/d1/atoms/app%2Fstate/deref\" :headers … :body \"…\"})

   Small JSON only. Every blob goes DIRECT to an object store this gateway presigns for, as any broker presigns for
   an S3-compatible store (never a blob route here): :presign answers a URL into the store for (key mode ttl) —
   hmac-presigner below for a store that checks HMAC signatures (eve.net.store, the store role, is one, and
   capstone/eve-media/src/eve_media/serve.cljc's store another). The gateway reaches the store itself as any client does — through its own presign and the
   platform's http (eve.net.run's steps, eve.net.blobs), never by a side door.

   Routes (POST, the bearer :api-key; an atom id is the atom's registered name with each / written %2F):
     …/atoms/<id>/create       the atom made holding {} when absent
     …/atoms/<id>/deref        the value now as a VSNP snapshot (eve.net.atom/snapshot): root_key root_blob epoch
                               lease_id lease_ttl root_count — the blocks it references put in the store first
     …/atoms/<id>/cas-root     the value the root blob names (its blocks fetched from the store, each checked against
                               its key) committed by ONE swap of the eve atom whose attempt compares the value there to
                               the value old_key names — a network writer's swap is atomic against every disk writer's
                               (eve's swap); 409 when they differ; 403 when :deny refuses the move
     …/presign                 URLs into the store, by :presign
     …/release-reader, …/renew-lease   ok: a value read here is materialized at once (no pin outlives a request),
                               and this gateway asks the store to delete nothing

   A key is the SHA-256 of a value's snapshot: content, not a version — two values have one key exactly when they are
   one value, so comparing keys compares values (an A-B-A is the same value; no update is lost). The gateway keeps,
   per atom, the value it last served or committed with its key and blob, and compares a value read from the atom with
   it: a value it has not seen is never taken to have a writer's old_key (that writer derefs again, as after any
   conflict). (deny name old-value new-value) answers a reason to refuse a network write, or nil."
  (:refer-clojure :exclude [swap! volatile!])
  (:require [clojure.string :as str]
            [eve.host :refer [swap! volatile!]]
            [eve.atom :as ea]
            [eve.crypto :as prim]
            [eve.deftype-proto.data :as ed]
            [eve.net.atom :as na]
            [eve.net.blobs :as blobs]
            [eve.net.bytes :as b]
            [eve.net.codec :as codec]
            [eve.net.json :as json]
            [eve.net.run :as r]))

;; ---------------------------------------------------------------------------
;; Atom ids
;; ---------------------------------------------------------------------------

(defn atom-id "an atom's registered name as eve.net's atom id: one path segment, each / written %2F" [nm]
  (str/replace nm "/" "%2F"))

(defn atom-name "the registered name an atom id names" [aid]
  (str/replace (str/replace aid "%2F" "/") "%2f" "/"))

;; ---------------------------------------------------------------------------
;; The dev presigner: HMAC-SHA-256 over key, mode and expiry (a store checks it: hmac-ok?)
;; ---------------------------------------------------------------------------

(defn- sig [secret k mode exp]
  (b/hex (prim/hmac-sha256 (b/utf8 secret) (b/utf8 (str k "|" mode "|" exp)))))

(defn hmac-presigner
  "A presigner for a store at url that checks HMAC-SHA-256 signatures under secret (a stand-in for an S3 store's
   SigV4: the store holds the secret, the client only the URL). now-ms: the clock (the expiry is now + ttl s)."
  [{:keys [url secret now-ms]}]
  (fn [k mode ttl]
    (let [exp (+ (quot (now-ms) 1000) (or ttl 300))]
      (str url k "?mode=" mode "&exp=" exp "&sig=" (sig secret k mode exp)))))

(defn hmac-ok?
  "Does a presigned request carry a valid signature for key k and the method's mode, unexpired at now-s?"
  [secret k mode exp signature now-s]
  (and (string? signature) (some? exp) (> exp now-s) (= signature (sig secret k mode exp))))

;; ---------------------------------------------------------------------------
;; The handler
;; ---------------------------------------------------------------------------

(defn- reply [status m] {:status status :headers {"content-type" "application/json"} :body (json/write-str m)})

(defn- split-on
  "the pieces of s between occurrences of the one-character string c"
  [s c]
  (loop [s s out []]
    (if-let [i (str/index-of s c)]
      (recur (subs s (inc i)) (conj out (subs s 0 i)))
      (conj out s))))

(defn- route
  "[domain-id atom-id-or-nil op] of a URL (full or a path), or nil when it is not a broker route"
  [url]
  (when-let [i (str/index-of url "/v1/domains/")]
    (let [path (first (split-on (subs url (+ i 12)) "?"))
          parts (split-on path "/")]
      (cond (and (= 4 (count parts)) (= "atoms" (nth parts 1))) [(nth parts 0) (nth parts 2) (nth parts 3)]
            (= 2 (count parts)) [(nth parts 0) nil (nth parts 1)]
            :else nil))))

(defn- self-platform
  "The platform the gateway reaches the store through: its own routes answered in this heap (its presign), every
   other request by the platform it was given."
  [g]
  (let [p (:platform g)
        own? (fn [req] (str/starts-with? (:url req) (:endpoint g)))
        one (fn [req] (if (own? req) ((:handle g) req) ((:request p) req)))]
    (assoc p :request one
           :request-all (fn [reqs]
                          (let [outs (filterv (fn [q] (not (own? q))) reqs)
                                answers (if (and (seq outs) (:request-all p)) (vec ((:request-all p) outs)) (mapv one outs))
                                by (zipmap (range) answers)]
                            (loop [qs reqs i 0 acc []]
                              (if (empty? qs)
                                acc
                                (if (own? (first qs))
                                  (recur (rest qs) i (conj acc ((:handle g) (first qs))))
                                  (recur (rest qs) (inc i) (conj acc (get by i)))))))))))

(defn- cfg
  "The wire's config for the gateway's own store I/O — with :gak when its clients encrypt their blobs (eve.net.blobs):
   the gateway, a member holding the domain, reads and writes the same blobs they do."
  [g]
  (select-keys g [:endpoint :domain-id :api-key :gak]))

(defn- run [g step] (r/run (:sp g) step))

(defn- atom-of
  "The eve atom an atom id names (made holding {} when create? and absent; nil when absent otherwise)."
  [g aid create?]
  (let [nm (atom-name aid)]
    (or (get-in @(:st g) [:atoms aid :a])
        (let [a (if create?
                  (ea/lookup-or-create-mmap-atom! (:domain g) nm {})
                  (ea/lookup-or-create-mmap-atom! (:domain g) nm nil))]
          (clojure.core/swap! (:st g) assoc-in [:atoms aid :a] a)
          a))))

(def ^:private BLOCKS-MAX "blocks kept by key (content-addressed: a kept block is never stale)" 100000)

(defn- keep-blocks!
  "Blocks (key → bytes) kept for the next decode — emptied past BLOCKS-MAX."
  [g blocks]
  (clojure.core/swap! (:blocks g) (fn [m] (merge (if (> (count m) BLOCKS-MAX) {} m) blocks))))

(defn- store!
  "The blocks the store does not hold yet (by this gateway's knowledge: it deletes nothing) put there."
  [g blocks]
  (let [known @(:known g)
        missing (into {} (remove (fn [[k _]] (contains? known k))) blocks)]
    (when (seq missing)
      (run g (blobs/upload-blocks (cfg g) missing))
      (clojure.core/swap! (:known g) into (keys missing))
      (keep-blocks! g missing))))

(defn- seen
  "The view of value v this gateway serves: {:value :key :blob :epoch} — the one it last saw when v is that one, else v's
   snapshot made now (its referenced blocks put in the store) under the next epoch."
  [g aid v]
  (let [c (get-in @(:st g) [:atoms aid :seen])]
    (if (and c (= v (:value c)))
      c
      (let [{:keys [blob key blocks]} (binding [codec/*memo* (:memo g)] (na/snapshot (or v {})))
            _ (store! g (assoc blocks key blob))
            c {:value v :key key :blob blob :epoch (inc (or (:epoch c) 0))}]
        (clojure.core/swap! (:st g) assoc-in [:atoms aid :seen] c)
        c))))

(defn- deref-route [g aid]
  (if-let [a (atom-of g aid false)]
    (let [ver (ea/version a)
          c0 (get-in @(:st g) [:atoms aid :seen])
          ;; (the version read before the value: equal versions are one value — eve.atom/version)
          c (if (and c0 (= ver (:version c0)))
              c0
              (let [c (seen g aid (ea/read-pinned a ed/eve->clj))]
                (clojure.core/swap! (:st g) update-in [:atoms aid :seen]
                                    (fn [x] (if (and x (= (:value x) (:value c))) (assoc x :version ver) x)))
                c))
          v (:value c)
          n (:lease (clojure.core/swap! (:st g) update :lease (fnil inc 0)))]
      (reply 200 (cond-> {"root_key" (when (some? v) (:key c)) "epoch" (:epoch c) "lease_id" (str "g-" n)
                          "lease_ttl" 60 "root_count" (count v)}
                   (some? v) (assoc "root_blob" (b/to-b64 (:blob c))))))
    (reply 404 {"status" "not-found"})))

(defn- patch
  "What to swap in for value new where the atom holds cur (an eve value) whose plain form is old: cur with only what
   differs replaced, map by map — eve keeps every subtree that did not change (a whole new value would be written and
   the whole old one retired, on every network write)."
  [cur old new]
  (cond
    (= old new) cur
    (and (map? old) (map? new) (map? cur))
    (let [cur (reduce (fn [c k] (if (contains? new k) c (dissoc c k))) cur (keys old))]
      (reduce (fn [c [k nv]]
                (let [ov (get old k ::none)]
                  (cond (= ov nv) c
                        (= ov ::none) (assoc c k nv)
                        :else (assoc c k (patch (get c k) ov nv)))))
              cur new))
    :else new))

(defn- cas-route [g aid req]
  (let [a (atom-of g aid false)
        blob (some-> (get req "root_blob") b/from-b64)
        old-key (get req "old_key")
        new-key (get req "new_key")]
    (cond
      (nil? a) (reply 404 {"status" "not-found"})
      (not (and blob (= new-key (b/hex (prim/sha256 blob)))))
      (reply 400 {"status" "error" "error" "the root blob does not hash to new_key"})
      :else
      (let [{:keys [value cache]} (binding [codec/*decoded* (:decoded g)]
                                    (run g (na/value-step (cfg g) blob @(:blocks g))))
            ;; (the cache it answers holds the one it was given: kept whole, or emptied past BLOCKS-MAX)
            _ (clojure.core/reset! (:blocks g) (if (> (count cache) BLOCKS-MAX) {} cache))
            nm (atom-name aid)
            out (volatile! nil)]
        (swap! a
               (fn [cur]
                 (let [cv (ed/eve->clj cur)
                       c (get-in @(:st g) [:atoms aid :seen])
                       cur-key (when (some? cv) (when (and c (= cv (:value c))) (:key c)))
                       why (when-let [d (:deny g)] (d nm cv value))]
                   (cond
                     ;; (a value this gateway has not seen is not taken to be old_key's: the writer derefs again)
                     (not (if (nil? cv) (nil? old-key) (and (some? cur-key) (= cur-key old-key))))
                     (do (vreset! out [:conflict c]) cur)
                     why (do (vreset! out [:denied why]) cur)
                     :else (do (vreset! out [:ok]) (patch cur cv value))))))
        (let [[k x] @out]
          (case k
            :ok (let [c (get-in @(:st g) [:atoms aid :seen])
                      e (inc (or (:epoch c) 0))]
                  (clojure.core/swap! (:st g) assoc-in [:atoms aid :seen] {:value value :key new-key :blob blob :epoch e})
                  (clojure.core/swap! (:known g) conj new-key)
                  (clojure.core/swap! (:st g) update :commits (fnil inc 0))
                  (reply 200 {"status" "ok" "epoch" e}))
            :denied (reply 403 {"status" "denied" "reason" "gateway-denied" "detail" (str x) "by" "eve.net.gateway"})
            (do (clojure.core/swap! (:st g) update :conflicts (fnil inc 0))
                (reply 409 {"status" "conflict" "current_epoch" (or (:epoch x) 0)}))))))))

(defn- handle [g {:keys [url headers body]}]
  (let [[did aid op] (route url)
        _ (clojure.core/swap! (:st g) (fn [st] (cond-> (update st :requests (fnil inc 0))
                                                 aid (update-in [:by-atom (atom-name aid)] (fnil inc 0)))))
        req (or (json/read-str-or-nil (or body "")) {})
        auth (or (get headers "Authorization") (get headers "authorization"))]
    (cond
      (nil? op) (reply 404 {"error" "Not found"})
      (not= (str "Bearer " (:api-key g)) auth) (reply 401 {"error" "Unauthorized"})
      (not= did (:domain-id g)) (reply 404 {"error" "no such domain"})
      (and aid (= op "create")) (do (atom-of g aid true) (reply 200 {"status" "ok" "epoch" 0}))
      (and aid (= op "deref")) (deref-route g aid)
      (and aid (= op "cas-root")) (cas-route g aid req)
      (= op "presign") (let [ks (take 100 (get req "keys"))
                             mode (get req "mode")
                             ttl (get req "ttl")]
                         (reply 200 {"urls" (into {} (map (fn [k] [k ((:presign g) k mode ttl)])) ks) "ttl" ttl}))
      (= op "release-reader") (reply 200 {"status" "ok"})
      (= op "renew-lease") (reply 200 {"status" "ok" "lease_ttl" 60})
      :else (reply 404 {"error" "Not found"}))))

(defn gateway
  "A gateway over domain (an eve atom domain): {:handle (fn [req] resp) :st :known ...}. opts: :endpoint (its own base
   URL, as clients reach it), :domain-id, :api-key, :presign (fn [key mode ttl] url — into the store), :platform (the
   http it reaches the store by: eve.net.platform's map), :deny (fn [name old new] reason-or-nil), :gak (the domain's
   group key, when its clients encrypt blobs).

   Threat model. Writer: whoever holds :api-key — the bearer every request carries; a network
   write lands only by a compare of content keys, so it is atomic against every disk writer and a replayed CAS commits
   only onto the very value it was made from. What the gateway sees: everything — it is a member that holds the domain
   (a gateway is for a member's own domain, not a passive broker); the store sees ciphertext under :gak. What it
   refuses: whatever :deny names (eve.thread's: a hello over the network, the upgrade's proof). Blobs never pass
   through it; it never deletes one (no retired key is reclaimed: content-addressed blocks a store may keep)."
  [domain opts]
  (let [g (merge opts {:domain domain :st (atom {:atoms {} :requests 0 :commits 0 :conflicts 0 :lease 0})
                       :known (atom #{}) :memo (atom {}) :decoded (atom {}) :blocks (atom {})})
        ;; (an atom, not a volatile: a gateway's handler runs on whatever thread serves a request — on the BEAM a
        ;; volatile is its process's own, an atom every process reads)
        hp (clojure.core/atom nil)
        g (assoc g :handle (fn [req] (handle @hp req)))
        g (assoc g :sp (self-platform g))]
    (clojure.core/reset! hp g)
    g))

(defn stats "what the gateway has served: {:requests :commits :conflicts :by-atom {name requests}}" [g]
  (select-keys @(:st g) [:requests :commits :conflicts :by-atom]))
