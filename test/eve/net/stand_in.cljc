(ns eve.net.stand-in
  "An in-process production broker and object store for eve.net's tests: the routes and shapes of
   vs/test/vs/net_wire_fixtures.vs `production-broker` (production's coordinator.cljs / worker.cljs, WIRE.md §2) and a
   store behind its presigned URLs. (stand-in opts) → {:request f :state :store :log}; opts :api-key, :deny (fn [req]
   truthy refuses the CAS with a 403), :tamper {key bytes} (served in place of the stored bytes), :before-cas (fn
   [state-atom], run before each CAS: a racer)."
  (:require [clojure.string :as str]
            [eve.net.bytes :as b]
            [eve.net.json :as json]))

(defn- reply [status m] {:status status :headers {"content-type" "application/json"} :body (json/write-str m)})

(defn- atom-id-of [url]
  (let [i (str/index-of url "/atoms/")]
    (when i (let [rest (subs url (+ i 7)) j (str/index-of rest "/")] (if j (subs rest 0 j) rest)))))

(defn- put-some [m k v] (if (nil? v) m (assoc m k v)))

(def STORE "https://b.t3.storage.dev/blobs/")

(defn stand-in
  "{:request f :state atom :store atom :log atom}: the production broker's routes and a store behind its URLs"
  [opts]
  (let [state (atom {:atoms {} :leases {} :released [] :seq 0 :conflicts 0})
        store (atom {})
        log (atom [])
        good (str "Bearer " (get opts :api-key "k"))
        f (fn [{:keys [method url headers body]}]
            (if (str/starts-with? url STORE)
              (let [k (first (str/split (subs url (count STORE)) #"\?"))]
                (swap! log conj [method k])
                (case method
                  "PUT" (do (swap! store assoc k (b/from-b64 body)) {:status 200 :headers {} :body ""})
                  "HEAD" {:status (if (contains? @store k) 200 404) :headers {} :body ""}
                  (if-let [o (get @store k)]
                    {:status 200 :headers {} :body (b/to-b64 (or (get (:tamper opts) k) o))}
                    {:status 404 :headers {} :body ""})))
              (let [req (or (json/read-str-or-nil (or body "")) {})
                    aid (atom-id-of url)
                    ends? (fn [s] (str/ends-with? url s))]
                (swap! log conj [method url req])
                (cond
                  (not= good (get headers "Authorization")) (reply 401 {"error" "Unauthorized"})
                  (ends? "/create")
                  (if-let [row (get-in @state [:atoms aid])]
                    (reply 200 {"status" "ok" "epoch" (:epoch row)})
                    (do (swap! state assoc-in [:atoms aid] {:root_key nil :epoch 0}) (reply 200 {"status" "ok" "epoch" 0})))
                  (ends? "/deref")
                  (if-let [row (get-in @state [:atoms aid])]
                    (let [n (:seq (swap! state update :seq inc))
                          lid (str "lease-" n)]
                      (swap! state assoc-in [:leases lid] aid)
                      (reply 200 (-> {"root_key" (:root_key row) "epoch" (:epoch row) "lease_id" lid "lease_ttl" 60}
                                     (put-some "root_blob" (:root_blob row)) (put-some "root_count" (:root_count row)))))
                    (reply 404 {"status" "not-found"}))
                  (ends? "/cas-root")
                  (let [_ (when (:before-cas opts) ((:before-cas opts) state))
                        row (get-in @state [:atoms aid])]
                    (cond
                      (and (:deny opts) ((:deny opts) req))
                      (reply 403 {"status" "denied" "reason" "spec-write-denied" "detail" "Write denied by domain spec"})
                      (= (get req "old_key") (:root_key row))
                      (let [e (inc (:epoch row))]
                        (swap! state assoc-in [:atoms aid] {:root_key (get req "new_key") :epoch e :root_blob (get req "root_blob")
                                                            :root_count (get req "root_count") :graft_sha (get req "graft_sha")
                                                            :retired (get req "retired_keys")})
                        (reply 200 {"status" "ok" "epoch" e}))
                      :else
                      (do (swap! state update :conflicts inc)
                          (reply 409 (-> {"status" "conflict" "current_key" (:root_key row) "current_epoch" (:epoch row)}
                                         (put-some "root_blob" (:root_blob row)) (put-some "graft_sha" (:graft_sha row)))))))
                  (ends? "/release-reader")
                  (do (swap! state (fn [m] (-> m (update :released conj (get req "lease_id")) (update :leases dissoc (get req "lease_id")))))
                      (reply 200 {"status" "ok"}))
                  (ends? "/renew-lease") (reply 404 {"error" "Not found"})       ;; production's Worker has no such route
                  (ends? "/presign")
                  (let [ks (take 100 (get req "keys"))]
                    (reply 200 {"urls" (into {} (map (fn [h] [h (str STORE h "?X-Amz-Signature=deadbeef&mode=" (get req "mode"))]) ks))
                                "ttl" (get req "ttl")}))
                  :else (reply 404 {"error" "Not found"})))))]
    {:request f :state state :store store :log log}))

