(ns eve.net.broker-test
  "eve.net.broker, the passive broker, past what eve.net.wire-test drives through eve.net.stand-in: leases that expire,
   renew-lease, retired keys handed to :on-retire once no lease needs them, :validate's 403, the 409's skeleton, real
   presigning (eve.net.gateway/hmac-presigner), and rows in an eve disk atom that outlive the broker."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eve.atom :as ea]
            [eve.net.broker :as broker]
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

(deftest leases-expire-and-renew
  (let [[b clock] (clocked {})]
    (run b (wire/create cfg))
    (let [l (:lease-id (run b (wire/deref-atom cfg)))]
      (is (= 60 (:lease-ttl (run b (wire/deref-atom cfg)))))
      (testing "a renew inside the lease's 60 s moves its expiry: alive 100 s after it was taken"
        (reset! clock 50000)
        (is (= {:status "ok" :lease-ttl 60} (run b (wire/renew-lease cfg l))))
        (reset! clock 100000)
        (is (= {:status "ok" :lease-ttl 60} (run b (wire/renew-lease cfg l)))))
      (testing "past its ttl a lease is gone: renew is not-found and the broker holds no lease"
        (reset! clock 161000)
        (is (= {:status "not-found"} (run b (wire/renew-lease cfg l))))
        (is (empty? (:leases @(:state b)))))
      (is (= {:status "not-found"} (run b (wire/renew-lease cfg "lease-none")))))))

(deftest retired-keys-wait-for-the-leases-below-them
  (let [got (atom [])
        [b clock] (clocked {:on-retire (fn [x] (swap! got conj x))})]
    (run b (wire/create cfg))
    (run b (wire/cas-root cfg {:old-key nil :new-key "aa" :root-blob [1]}))
    (let [l (:lease-id (run b (wire/deref-atom cfg)))]
      (is (= {:status "ok" :epoch 2} (run b (wire/cas-root cfg {:old-key "aa" :new-key "bb" :root-blob [2] :retired-keys ["k1"]}))))
      (is (= [] @got) "a lease at epoch 1 still reads k1")
      (run b (wire/release-reader cfg l))
      (is (= [{:atom-id "a1" :epoch 2 :keys ["k1"]}] @got) "released: k1 handed to :on-retire once"))
    (testing "an expired lease frees what it held too"
      (let [_ (run b (wire/deref-atom cfg))]
        (run b (wire/cas-root cfg {:old-key "bb" :new-key "cc" :root-blob [3] :retired-keys ["k2"]}))
        (is (= 1 (count @got)))
        (reset! clock 61000)
        (run b (wire/create cfg))
        (is (= {:atom-id "a1" :epoch 3 :keys ["k2"]} (last @got)))))
    (testing "with no lease, a CAS's retired keys go at once"
      (run b (wire/cas-root cfg {:old-key "cc" :new-key "dd" :root-blob [4] :retired-keys ["k3"]}))
      (is (= ["k3"] (:keys (last @got))))
      (is (empty? (:retiring @(:state b)))))))

(deftest validate-refuses-with-a-403
  (let [seen (atom nil)
        [b _] (clocked {:validate (fn [req row] (reset! seen row)
                                    (when (= "secret" (get req "new_key")) {:reason "claim-refused" :detail "no claim"}))})]
    (run b (wire/create cfg))
    (is (= {:status "denied" :reason "claim-refused" :detail "no claim" :by nil}
           (run b (wire/cas-root cfg {:old-key nil :new-key "secret"}))))
    (is (= {:root_key nil :epoch 0} @seen) ":validate sees the row the CAS would replace")
    (is (nil? (:root_key (broker/-row (:rows b) "a1"))) "nothing written")
    (is (= {:status "ok" :epoch 1} (run b (wire/cas-root cfg {:old-key nil :new-key "open"}))))))

(deftest a-conflict-carries-the-winner
  (let [[b _] (clocked {})]
    (is (= 200 (first (post! b "/atoms/a1/cas-root" {"old_key" nil "new_key" "aa" "root_blob" "AQ==" "skeleton_blob" "Ag=="
                                                     "skeleton_sha" "s1" "freelist_sha" "f1" "graft_sha" "g1"}))))
    (is (= [409 {"status" "conflict" "current_key" "aa" "current_epoch" 1 "root_blob" "AQ==" "skeleton_blob" "Ag=="
                 "skeleton_sha" "s1" "freelist_sha" "f1" "graft_sha" "g1"}]
           (post! b "/atoms/a1/cas-root" {"old_key" "stale" "new_key" "bb"})))
    (is (= "Ag==" (get (second (post! b "/atoms/a1/deref" {})) "skeleton_blob")) "deref answers the skeleton")
    (testing "an unknown atom is created at old_key, then CASed"
      (is (= [200 {"status" "ok" "epoch" 1}] (post! b "/atoms/a2/cas-root" {"old_key" "x" "new_key" "y"}))))
    (is (= 404 (:status ((:handle b) {:method "POST" :url "https://broker.example/v1/domains/d2/atoms/a1/deref"
                                    :headers {"Authorization" "Bearer k"} :body "{}"})))
        "another domain is not found")))

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
      (let [b1 (broker/broker {:api-key "k" :rows (broker/disk-rows d)})]
        (run b1 (wire/create cfg))
        (is (= {:status "ok" :epoch 1} (run b1 (wire/cas-root cfg {:old-key nil :new-key "aa" :root-blob [1 2]}))))
        (is (= "conflict" (:status (run b1 (wire/cas-root cfg {:old-key "zz" :new-key "bb"}))))))
      (let [b2 (broker/broker {:api-key "k" :rows (broker/disk-rows d)})
            v (run b2 (wire/deref-atom cfg))]
        (is (= ["aa" [1 2] 1] [(:root-key v) (:root-blob v) (:epoch v)]) "a second broker over the rows reads the first's")
        (is (= {:status "ok" :epoch 2} (run b2 (wire/cas-root cfg {:old-key "aa" :new-key "bb"})))))
      (finally (ea/close-atom-domain! d)))))

(deftest a-page-reaches-it-cors
  (let [[b _] (clocked {})
        pre ((:handle b) {:method "OPTIONS" :url "https://broker.example/v1/domains/d1/atoms/a1/deref" :headers {} :body ""})
        h (:headers pre)]
    (is (= 204 (:status pre)) "a preflight needs no credential")
    (is (= "*" (get h "access-control-allow-origin")))
    (is (str/includes? (get h "access-control-allow-methods") "POST"))
    (is (every? (fn [x] (str/includes? (get h "access-control-allow-headers") x)) ["authorization" "content-type"]))
    (is (= "*" (get-in ((:handle b) {:method "POST" :url "https://broker.example/v1/domains/d1/atoms/a1/deref"
                                     :headers {} :body "{}"})
                       [:headers "access-control-allow-origin"]))
        "every answer carries it, a 401 too")))
