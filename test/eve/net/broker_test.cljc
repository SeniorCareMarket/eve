(ns eve.net.broker-test
  "eve.net.broker, the passive broker, past what eve.net.wire-test drives through eve.net.stand-in: leases that expire,
   renew-lease, retired keys handed to :on-retire once no lease needs them, :validate's 403, the 409's skeleton. No
   eve.atom: these rows run on ValueScript too (bb test:vs); real presigning and rows in a disk atom are
   eve.net.broker-disk-test's."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.net.broker :as broker]
            [eve.net.json :as json]
            [eve.net.run :as r]
            [eve.net.wire :as wire]))

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
