(ns eve.net.broker-disk-test
  "eve.net.broker past what needs no eve.atom (eve.net.broker-test): real presigning (eve.net.gateway/hmac-presigner)
   and rows in an eve disk atom (eve.net.broker-disk) that outlive the broker."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [eve.atom :as ea]
            [eve.net.broker :as broker]
            [eve.net.broker-disk :as disk]
            [eve.net.gateway :as gw]
            [eve.net.json :as json]
            [eve.net.run :as r]
            [eve.net.wire :as wire]
            [eve.thread.rung :as rung]))

(def ^:private cfg {:endpoint "https://broker.example" :domain-id "d1" :atom-id "a1" :api-key "k"})

(defn- clocked
  "[broker clock]: a broker on a clock the test moves"
  [opts]
  (let [clock (atom 0)]
    [(broker/broker (merge {:api-key "k" :domain-id "d1" :now-ms (fn [] @clock)} opts)) clock]))

(defn- run [b step] (r/run {:request (:handle b) :sleep (fn [_] nil) :now-ms (fn [] 0) :seed 7} step))

(defn- post! [b path body]
  (let [resp ((:handle b) {:method "POST" :url (str "https://broker.example/v1/domains/d1" path)
                           :headers {"Authorization" "Bearer k"} :body (json/write-str body)})]
    [(:status resp) (json/read-str-or-nil (:body resp))]))

(deftest presigning-is-real
  (let [p (gw/hmac-presigner {:url "http://store.test/blobs/" :secret "s" :now-ms (fn [] 0)})
        [b _] (clocked {:presign p})
        u (get (run b (wire/presign cfg :write ["ab"])) "ab")
        q (second (str/split u #"\?"))
        m (into {} (map (fn [kv] (str/split kv #"="))) (str/split q #"&"))
        exp (json/read-str-or-nil (get m "exp"))]
    (is (str/starts-with? u "http://store.test/blobs/ab?"))
    (is (gw/hmac-ok? "s" "ab" "write" exp (get m "sig") 0) "the store accepts its signature")
    (is (not (gw/hmac-ok? "s" "ab" "read" exp (get m "sig") 0)) "for its mode only")
    (is (= 503 (first (post! (first (clocked {})) "/presign" {"keys" ["ab"] "mode" "read"}))) "no store: 503")))

(deftest rows-in-a-disk-atom-outlive-the-broker
  (let [path (rung/place {:name (str "broker-" (rand-int 1000000000)) :placement :window :window-root "/dev/shm/eve-broker-test"})
        d (ea/persistent-atom-domain path)]
    (try
      (let [b1 (broker/broker {:api-key "k" :rows (disk/disk-rows d)})]
        (run b1 (wire/create cfg))
        (is (= {:status "ok" :epoch 1} (run b1 (wire/cas-root cfg {:old-key nil :new-key "aa" :root-blob [1 2]}))))
        (is (= "conflict" (:status (run b1 (wire/cas-root cfg {:old-key "zz" :new-key "bb"}))))))
      (let [b2 (broker/broker {:api-key "k" :rows (disk/disk-rows d)})
            v (run b2 (wire/deref-atom cfg))]
        (is (= ["aa" [1 2] 1] [(:root-key v) (:root-blob v) (:epoch v)]) "a second broker over the rows reads the first's")
        (is (= {:status "ok" :epoch 2} (run b2 (wire/cas-root cfg {:old-key "aa" :new-key "bb"})))))
      (finally (ea/close-atom-domain! d)))))
