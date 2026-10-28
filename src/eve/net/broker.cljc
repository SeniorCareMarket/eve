(ns eve.net.broker
  "A PASSIVE broker: WIRE.md §2–§3's routes, served the way production's broker serves them. It compares root keys,
   stores root blobs and leases, and never reads a value — it holds no block, decodes nothing, and every blob goes
   DIRECT to an object store it presigns for (never a blob route here). eve.net.gateway is the other kind: a member
   serving its own domain's atoms.

     (def b (broker {:api-key k :domain-id \"d1\"
                     :presign (eve.net.gateway/hmac-presigner {:url \"http://store/blobs/\" :secret s :now-ms f})}))
     ((:handle b) {:method \"POST\" :url \"…/v1/domains/d1/atoms/app%2Fstate/deref\" :headers … :body \"…\"})

   Routes (POST, the bearer :api-key; every answer carries CORS, and OPTIONS is a 204 preflight):
     …/atoms/<id>/create      the row made at initial_root_key (epoch 0) when absent; answers its epoch
     …/atoms/<id>/deref       the row and a reader lease {lease_id lease_ttl} (404 when absent)
     …/atoms/<id>/cas-root    old_key compared with the row's root_key, atomically (a row store's -cas!); an unknown
                              atom is created at old_key first. 200 the new epoch · 409 the winner's root, skeleton,
                              freelist and graft · 403 when :validate refuses
     …/release-reader         the lease dropped
     …/renew-lease            a live lease's expiry moved to now + lease_ttl; 404 when it is unknown or expired
     …/presign                URLs into the store by :presign (fn [key mode ttl] url); 503 without one

   Leases expire lease_ttl seconds (:lease-ttl, 60) after they were taken or renewed (:now-ms is the clock). The
   retired_keys of a CAS are queued at its new epoch and handed to :on-retire (fn [{:atom-id :epoch :keys}]) once no
   live lease on that atom is below that epoch — the backend's collector attaches there; without one the broker
   deletes nothing. (:validate req row) answers nil or {:reason :detail}, a 403 (a claim gate attaches there).

   The rows sit behind IAtomRows: in memory (mem-rows, the default, in the broker's :state under :atoms) or in one eve
   disk atom (disk-rows), so a local broker survives a restart.

   Threat model. Writer: whoever holds :api-key, refused further only by :validate. What it sees: root keys, root and
   skeleton blobs (ciphertext when clients encrypt), timing; never a block. What it refuses: a stale old_key (409),
   what :validate names (403). It deletes nothing itself."
  (:require [clojure.string :as str]
            [eve.atom :as ea]
            [eve.deftype-proto.data :as ed]
            [eve.host :as host]
            [eve.net.json :as json]
            [eve.process :as process]))

;; ---------------------------------------------------------------------------
;; The row store
;; ---------------------------------------------------------------------------

(defprotocol IAtomRows
  "The broker's rows and reader leases. Each operation is one statement of a SQL store: -row a SELECT, -create! an INSERT, -cas! one UPDATE … WHERE root_key IS
   old-key (and the retired keys' INSERTs in its transaction), the lease operations one statement each. Times are the
   broker's clock (ms); a store converts to its own units."
  (-row [rs aid] "the row of atom id aid, or nil")
  (-create! [rs aid initial-key] "the row of aid, made {:root_key initial-key :epoch 0} when absent")
  (-cas! [rs aid old-key fields]
    "atomically: when the row's root_key equals old-key (an absent row is one at old-key, epoch 0), the row becomes
     fields (:root_key the new key, the root fields, :retired the retired keys) at epoch + 1 → [true new-row]; else
     [false row]")
  (-lease! [rs aid epoch expires-at] "a reader lease on aid at epoch until expires-at; answers its id (unique across restarts)")
  (-release! [rs lid] "the lease dropped, whether or not it existed")
  (-renew! [rs lid now expires-at] "a live lease (expires-at >= now) moved to expires-at → true; else false")
  (-lease-floor [rs now] "{aid lowest-epoch} over the live leases; expired leases dropped"))

(defn- swap-out!
  "f: state → [state' out], applied by compare-and-set! (f may run more than once); answers the committed out"
  [a f]
  (loop []
    (let [old @a
          [new out] (f old)]
      (if (compare-and-set! a old new) out (recur)))))

(defn- row-step
  "[rows' out] of -cas! over the map rows"
  [rows aid old-key fields]
  (let [row (or (get rows aid) {:root_key old-key :epoch 0})]
    (if (= old-key (:root_key row))
      (let [n (assoc fields :epoch (inc (:epoch row)))] [(assoc rows aid n) [true n]])
      [rows [false row]])))

(defn- lease-ops
  "the lease half of IAtomRows over the map at key k of the atom st: {lid {:atom :epoch :expires-at}}, ids
   \"lease-<n>\" from st's :seq (in memory, the leases die with the counter; a durable store mints ids unique across
   restarts)"
  [st k]
  {:lease! (fn [aid epoch expires-at]
             (swap-out! st (fn [s] (let [n (inc (or (:seq s) 0))
                                         lid (str "lease-" n)]
                                     [(-> s (assoc :seq n) (assoc-in [k lid] {:atom aid :epoch epoch :expires-at expires-at}))
                                      lid]))))
   :release! (fn [lid] (swap! st update k dissoc lid) nil)
   :renew! (fn [lid now expires-at]
             (swap-out! st (fn [s] (let [s (update s k (fn [m] (into {} (filter (fn [[_ l]] (>= (:expires-at l) now))) m)))]
                                     (if (get-in s [k lid])
                                       [(assoc-in s [k lid :expires-at] expires-at) true]
                                       [s false])))))
   :floor (fn [now]
            (swap-out! st (fn [s] (let [live (into {} (filter (fn [[_ l]] (>= (:expires-at l) now))) (get s k))]
                                    [(assoc s k live)
                                     (reduce (fn [m l] (update m (:atom l) (fn [e] (if e (min e (:epoch l)) (:epoch l)))))
                                             {} (vals live))]))))})

(defn mem-rows
  "rows in memory: the map at key k (default :atoms) of the atom st, the leases under :leases"
  ([st] (mem-rows st :atoms))
  ([st k]
   (let [ls (lease-ops st :leases)]
    (reify IAtomRows
     (-row [_ aid] (get-in @st [k aid]))
     (-create! [_ aid initial-key]
       (swap-out! st (fn [s] (if-let [row (get-in s [k aid])]
                               [s row]
                               (let [row {:root_key initial-key :epoch 0}] [(assoc-in s [k aid] row) row])))))
     (-cas! [_ aid old-key fields]
       (swap-out! st (fn [s] (let [[rows out] (row-step (get s k) aid old-key fields)] [(assoc s k rows) out]))))
     (-lease! [_ aid epoch expires-at] ((:lease! ls) aid epoch expires-at))
     (-release! [_ lid] ((:release! ls) lid))
     (-renew! [_ lid now expires-at] ((:renew! ls) lid now expires-at))
     (-lease-floor [_ now] ((:floor ls) now))))))

(defn disk-rows
  "rows as one map in the eve atom named nm (default \"eve.net.broker/rows\") of domain (an eve disk domain): every
   change one eve swap. The leases stay in memory (a restart outlives every TTL)"
  ([domain] (disk-rows domain "eve.net.broker/rows"))
  ([domain nm]
   (let [a (ea/lookup-or-create-mmap-atom! domain nm {})
         rows (fn [cur] (or (ed/eve->clj cur) {}))
         change! (fn [g]
                   (let [out (host/volatile! nil)]
                     (host/swap! a (fn [cur] (let [[m o] (g (rows cur))] (vreset! out o) m)))
                     @out))
         ls (lease-ops (atom {}) :leases)]
     (reify IAtomRows
       (-row [_ aid] (get (ea/read-pinned a rows) aid))
       (-create! [_ aid initial-key]
         (change! (fn [m] (if-let [row (get m aid)]
                            [m row]
                            (let [row {:root_key initial-key :epoch 0}] [(assoc m aid row) row])))))
       (-cas! [_ aid old-key fields] (change! (fn [m] (row-step m aid old-key fields))))
       (-lease! [_ aid epoch expires-at] ((:lease! ls) aid epoch expires-at))
       (-release! [_ lid] ((:release! ls) lid))
       (-renew! [_ lid now expires-at] ((:renew! ls) lid now expires-at))
       (-lease-floor [_ now] ((:floor ls) now))))))

;; ---------------------------------------------------------------------------
;; Leases and retired keys
;; ---------------------------------------------------------------------------

(def ^:private RELEASED-MAX "released lease ids kept (a log for tests and stats)" 1000)

(defn- tick!
  "with :on-retire: expired leases dropped, and each retired batch no live lease needs any more handed to it. Without
   one the broker queues nothing and sweeps nothing (a SQL store's retired keys and leases are the backend's alarm's)"
  [b]
  (when-let [h (:on-retire b)]
    (let [low (-lease-floor (:rows b) ((:now-ms b)))
          ready? (fn [{:keys [atom-id epoch]}] (let [e (get low atom-id)] (or (nil? e) (>= e epoch))))
          ready (swap-out! (:state b) (fn [s] (let [q (:retiring s)]
                                                [(assoc s :retiring (vec (remove ready? q))) (filterv ready? q)])))]
      (doseq [r ready] (h r)))))

;; ---------------------------------------------------------------------------
;; The handler
;; ---------------------------------------------------------------------------

(def CORS
  "the headers every answer carries, so a page on any origin reaches the broker"
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "POST, OPTIONS"
   "access-control-allow-headers" "authorization, content-type"
   "access-control-max-age" "600"})

(defn- reply [status m] {:status status :headers (assoc CORS "content-type" "application/json") :body (json/write-str m)})

(defn- put-some [m k v] (if (nil? v) m (assoc m k v)))

(defn- put-row
  "m with the row's root blob, skeleton, freelist, count and graft (those it has)"
  [m row ks]
  (reduce (fn [m k] (put-some m (name k) (get row k))) m ks))

(def ^:private ROOT-FIELDS [:root_blob :skeleton_blob :skeleton_sha :freelist_sha :freelist_blob :root_count :graft_sha])

(defn- split-on
  "the pieces of s between occurrences of the one-character string c"
  [s c]
  (loop [s s out []]
    (if-let [i (str/index-of s c)]
      (recur (subs s (inc i)) (conj out (subs s 0 i)))
      (conj out s))))

(defn route
  "[domain-id atom-id-or-nil op] of a URL (full or a path), or nil when it is not a broker route"
  [url]
  (when-let [i (str/index-of url "/v1/domains/")]
    (let [parts (split-on (first (split-on (subs url (+ i 12)) "?")) "/")]
      (cond (and (= 4 (count parts)) (= "atoms" (nth parts 1))) [(nth parts 0) (nth parts 2) (nth parts 3)]
            (= 2 (count parts)) [(nth parts 0) nil (nth parts 1)]
            :else nil))))

(defn- deref-route [b aid]
  (if-let [row (-row (:rows b) aid)]
    (let [ttl (:lease-ttl b)
          lid (-lease! (:rows b) aid (:epoch row) (+ ((:now-ms b)) (* 1000 ttl)))]
      (reply 200 (put-row {"root_key" (:root_key row) "epoch" (:epoch row) "lease_id" lid "lease_ttl" ttl} row ROOT-FIELDS)))
    (reply 404 {"status" "not-found"})))

(defn- conflict [b row]
  (swap! (:state b) update :conflicts inc)
  (reply 409 (put-row {"status" "conflict" "current_key" (:root_key row) "current_epoch" (:epoch row)}
                      row (remove #{:root_count} ROOT-FIELDS))))

(defn- cas-route
  "production's order: an unknown atom made at old_key, the compare (409), :validate (403), then the atomic -cas!"
  [b aid req http-req]
  (let [old-key (get req "old_key")
        row (or (-row (:rows b) aid) (-create! (:rows b) aid old-key))]
    (if (not= old-key (:root_key row))
      (conflict b row)
      (if-let [why (when-let [v (:validate b)] (v (assoc req :http http-req) row))]
        (reply 403 {"status" "denied" "reason" (:reason why) "detail" (:detail why)})
        (let [retired (vec (get req "retired_keys"))
              [won? row] (-cas! (:rows b) aid old-key
                                (merge {:root_key (get req "new_key") :retired retired}
                                       (into {} (map (fn [k] [k (get req (name k))])) ROOT-FIELDS)))]
          (if won?
            (do (when-let [c (:on-commit b)] (c {:atom-id aid :epoch (:epoch row) :row row}))
                (when (and (seq retired) (:on-retire b))
                  (swap! (:state b) update :retiring conj {:atom-id aid :epoch (:epoch row) :keys retired})
                  (tick! b))
                (reply 200 {"status" "ok" "epoch" (:epoch row)}))
            (conflict b row)))))))

(defn- presign-route [b req]
  (if-let [p (:presign b)]
    (let [ks (take 100 (get req "keys"))
          mode (get req "mode")
          ttl (or (get req "ttl") 300)]
      (reply 200 {"urls" (into {} (map (fn [k] [k (p k mode ttl)])) ks) "ttl" ttl}))
    (reply 503 {"error" "Domain bucket not provisioned"})))

(defn- bearer?
  "the default :authorize: the request's bearer is :api-key"
  [b {:keys [headers]}]
  (= (str "Bearer " (:api-key b)) (or (get headers "Authorization") (get headers "authorization"))))

(defn- handle [b {:keys [method url body] :as http-req}]
  (let [[did aid op] (route url)
        req (or (json/read-str-or-nil (or body "")) {})]
    (swap! (:state b) update :requests inc)
    (tick! b)
    (cond
      (= "OPTIONS" method) {:status 204 :headers CORS :body ""}
      (nil? op) (reply 404 {"error" "Not found"})
      (not (if-let [a (:authorize b)] (a http-req) (bearer? b http-req))) (reply 401 {"error" "Unauthorized"})
      (and (:domain-id b) (not= did (:domain-id b))) (reply 404 {"error" "no such domain"})
      (and aid (= op "create")) (reply 200 {"status" "ok" "epoch" (:epoch (-create! (:rows b) aid (get req "initial_root_key")))})
      (and aid (= op "deref")) (deref-route b aid)
      (and aid (= op "cas-root")) (cas-route b aid req http-req)
      (= op "presign") (presign-route b req)
      (= op "release-reader")
      (let [lid (get req "lease_id")]
          (-release! (:rows b) lid)
          (swap! (:state b) (fn [s] (let [r (conj (:released s) lid)]
                                      (assoc s :released (if (> (count r) RELEASED-MAX) (subvec r (quot RELEASED-MAX 2)) r)))))
          (tick! b)
          (reply 200 {"status" "ok"}))
      (= op "renew-lease")
      (let [lid (get req "lease_id")
            ttl (:lease-ttl b)
            now ((:now-ms b))
            ok? (-renew! (:rows b) lid now (+ now (* 1000 ttl)))]
        (if ok? (reply 200 {"status" "ok" "lease_ttl" ttl}) (reply 404 {"status" "not-found"})))
      :else (reply 404 {"error" "Not found"}))))

(defn broker
  "A passive broker: {:handle (fn [req] resp) :state :rows}. opts: :api-key, :domain-id (when given, the only one it
   answers), :presign (fn [key mode ttl] url), :rows (an IAtomRows; default mem-rows over :state), :lease-ttl (s, 60),
   :now-ms (the clock), :validate (fn [req row] nil-or-{:reason :detail}; req the JSON body, the HTTP request under
   :http), :on-retire (fn [{:atom-id :epoch :keys}]), :on-commit (fn [{:atom-id :epoch :row}]), :authorize (fn
   [http-req] bool; default the bearer :api-key). :state holds {:atoms (the in-memory rows) :leases {id {:atom :epoch
   :expires-at}} (mem-rows') :released :retiring :seq :conflicts :requests}."
  [opts]
  (let [st (atom {:atoms {} :leases {} :released [] :retiring [] :seq 0 :conflicts 0 :requests 0})
        b (merge {:lease-ttl 60 :now-ms process/now-ms} opts {:state st})
        b (assoc b :rows (or (:rows opts) (mem-rows st)))]
    (assoc b :handle (fn [req] (handle b req)))))
