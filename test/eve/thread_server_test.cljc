(ns eve.thread-server-test
  "eve.thread.server over real HTTP, in one process (a host with the http-server feature: the JVM, babashka): a store
   (eve.net.store), a passive broker (eve.net.broker) presigning for it, and three thread servers attached to one
   domain cell at the broker. A client attaches too, submits nothing of its own threads, and pmaps a registered task
   over the pool: the results equal the sequential ones, and every server ran some (each result names its runtime, as
   eve.thread-ladder's run-who! receipts do; each server's /health counts its jobs)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.http-server :as hs]
            [eve.net.broker :as broker]
            [eve.net.gateway :as gw]
            [eve.net.json :as json]
            [eve.net.platform :as platform]
            [eve.net.store :as es]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread.domain :as dom]
            [eve.thread.net :as net]
            [eve.thread.probe :as probe]
            [eve.thread.server :as server]))

(defn- served
  "[url-base stop] of a handler made from its own base URL (f base) → handler"
  [f]
  (let [hv (atom nil)
        srv (hs/serve! {:port 0 :threads 16} (fn [req] (@hv req)))
        base (str "http://127.0.0.1:" (:port srv))]
    (reset! hv (f base))
    [base (:stop srv)]))

(deftest three-servers-run-one-pmap
  (let [secret "s"
        [store-base stop-store] (served (fn [base] (:handle (es/store {:url (str base "/blobs/") :secret secret :now-ms proc/now-ms}))))
        [ep stop-broker] (served (fn [_] (:handle (broker/broker {:api-key "k" :domain-id "d1"
                                                                  :presign (gw/hmac-presigner {:url (str store-base "/blobs/")
                                                                                               :secret secret :now-ms proc/now-ms})}))))
        cfg {:endpoint ep :domain-id "d1" :api-key "k"}
        servers (mapv (fn [i] (server/serve! (assoc cfg :rid (str "t" i) :pool :work :threads 2 :requires '[eve.thread.probe])))
                      (range 3))
        client (dom/attach! (net/rung cfg "eve.thread/cell") {:rid "client" :ttl 4000 :requires '[eve.thread.probe]})]
    (try
      (let [p (dom/pool client :work {:threads 0})
            xs (vec (range 48))
            got (vec (t/with-pool p (t/pmap probe/who xs)))
            p* (platform/platform)
            health (mapv (fn [s] (json/read-str-or-nil (:body ((:request p*) {:method "GET" :headers {}
                                                                              :url (str "http://127.0.0.1:" (:port s) "/health")}))))
                         servers)]
        (is (= (mapv (fn [x] (* x x)) xs) (mapv :y got)) "the pool's results are the sequential ones, in order")
        (is (= #{"t0" "t1" "t2"} (set (map :ran got))) "every server ran some of them")
        (is (every? (fn [x] (= :net (:via x))) got) "over the network rung")
        (testing "/health names each server and counts what it ran"
          (is (= ["t0" "t1" "t2"] (mapv (fn [h] (get h "rid")) health)))
          (is (= (frequencies (map :ran got)) (into {} (map (fn [h] [(get h "rid") (get h "jobs")])) health)))
          (is (every? (fn [h] (= "work" (get h "pool"))) health))))
      (finally
        (dom/detach! client)
        (doseq [s servers] ((:stop s)))
        (stop-broker)
        (stop-store)))))
