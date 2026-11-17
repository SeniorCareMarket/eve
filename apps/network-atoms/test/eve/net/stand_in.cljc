(ns eve.net.stand-in
  "eve.net's tests' broker and object store, in one process: eve.net.broker (the passive broker, WIRE.md §2–§3) shaped
   as production's — its renew-lease route answers 404, as production's Worker has none — and a store behind the
   presigned URLs it hands out. (stand-in opts) → {:request f :state :store :log :broker}; opts :api-key, :deny (fn
   [req] truthy refuses the CAS with a 403), :tamper {key bytes} (served in place of the stored bytes), :before-cas (fn
   [state-atom], run before each CAS: a racer), :renew (true: the broker's own renew-lease), and any broker opt."
  (:require [clojure.string :as str]
            [eve.net.broker :as broker]
            [eve.net.bytes :as b]
            [eve.net.json :as json]))

(def STORE "https://store.example/blobs/")

(defn stand-in
  "{:request f :state atom :store atom :log atom :broker b}: the broker's routes and a store behind its URLs"
  [opts]
  (let [deny (:deny opts)
        bk (broker/broker (merge {:api-key "k"
                                  :presign (fn [h mode _] (str STORE h "?X-Amz-Signature=deadbeef&mode=" mode))}
                                 (dissoc opts :deny :tamper :before-cas :renew)
                                 (when deny
                                   {:validate (fn [req _] (when (deny req)
                                                            {:reason "spec-write-denied" :detail "Write denied by domain spec"}))})))
        state (:state bk)
        store (atom {})
        log (atom [])
        f (fn [{:keys [method url body] :as q}]
            (if (str/starts-with? url STORE)
              (let [k (first (str/split (subs url (count STORE)) #"\?"))]
                (swap! log conj [method k])
                (case method
                  "PUT" (do (swap! store assoc k (b/from-b64 body)) {:status 200 :headers {} :body ""})
                  "HEAD" {:status (if (contains? @store k) 200 404) :headers {} :body ""}
                  (if-let [o (get @store k)]
                    {:status 200 :headers {} :body (b/to-b64 (or (get (:tamper opts) k) o))}
                    {:status 404 :headers {} :body ""})))
              (do (swap! log conj [method url (or (json/read-str-or-nil (or body "")) {})])
                  (if (and (str/ends-with? url "/renew-lease") (not (:renew opts)))
                    {:status 404 :headers {"content-type" "application/json"} :body (json/write-str {"error" "Not found"})}
                    (do (when (and (:before-cas opts) (str/ends-with? url "/cas-root")) ((:before-cas opts) state))
                        ((:handle bk) q))))))]
    {:request f :state state :store store :log log :broker bk}))
