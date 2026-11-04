(ns eve.durability-spike-test
  "Durability spike (2026-10-27): the disk-atom commit ordering and the
   public sync!. These are process-level reopen checks — write, close,
   reopen, read back. They do NOT simulate power loss: kill -9 does not
   simulate power loss for mmap (the kernel keeps the dirty pages), and
   no test here claims otherwise. The per-commit force itself is proven
   by counting msync under strace (doc/durability-spike-report-2026-10-27.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as atom]))

(def ^:private base (str "target/durability-spike-test-" (System/currentTimeMillis)))

(deftest write-sync-close-reopen
  (let [b (str base "-wsr")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b}
                                {:n 41 :s (apply str (repeat 3000 "x"))})]
    (swap! a update :n inc)
    (atom/sync! a)
    (atom/close-atom-domain! d)
    (let [d2 (atom/persistent-atom-domain b)
          a2 (atom/persistent-atom {:id :eve/main :persistent b} nil)
          v @a2]
      (is (= 42 (:n v)))
      (is (= 3000 (count (:s v))))
      (atom/close-atom-domain! d2))))

(deftest durable-commits-read-back-after-reopen
  (testing "no explicit sync!: the commits themselves are forced as they publish"
    (let [b (str base "-durable")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} 0)]
      (dotimes [_ 25] (swap! a inc))
      (atom/close-atom-domain! d)
      (let [d2 (atom/persistent-atom-domain b)
            a2 (atom/persistent-atom {:id :eve/main :persistent b} nil)]
        (is (= 25 @a2))
        (atom/close-atom-domain! d2)))))

(deftest group-commit-shape
  (testing ":durable? false + explicit sync! — one force for many commits"
    (let [b (str base "-group")
          d (atom/persistent-atom-domain b {:durable? false})
          a (atom/persistent-atom {:id :eve/main :persistent b} 0)]
      (dotimes [_ 25] (swap! a inc))
      (is (identical? a (atom/sync! a)) "sync! returns its argument")
      (atom/close-atom-domain! d)
      (let [d2 (atom/persistent-atom-domain b)
            a2 (atom/persistent-atom {:id :eve/main :persistent b} nil)]
        (is (= 25 @a2))
        (atom/close-atom-domain! d2)))))

(deftest sync-on-domain
  (testing "sync! accepts the domain as well as an atom"
    (let [b (str base "-dom")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:v 1})]
      (swap! a assoc :v 2)
      (is (identical? d (atom/sync! d)))
      (atom/close-atom-domain! d)
      (let [d2 (atom/persistent-atom-domain b)
            a2 (atom/persistent-atom {:id :eve/main :persistent b} nil)]
        (is (= 2 (:v @a2)))
        (atom/close-atom-domain! d2)))))
