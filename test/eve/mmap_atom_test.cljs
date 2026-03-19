(ns eve.mmap-atom-test
  "Tests for the B2 mmap-backed persistent atom (CLJS Node.js only).
   Requires: native addon loaded, mmap slab path working (Phase 2 + 3).
   Run via: node target/thread-test/all.js mmap-atom"
  (:require [cljs.test :refer [deftest testing is]]
            [eve.atom :as atom]
            [eve.atom :as sa]))

(def ^:private test-base (str "/tmp/eve-p5-atom-test-" (js/Date.now)))

;; --- Direct persistent-atom API tests (unchanged) ---

(deftest test-persistent-atom-nil
  (testing "fresh atom has nil value"
    (let [d (atom/persistent-atom-domain test-base)
          a (atom/persistent-atom {:id :eve/main :persistent test-base} nil)]
      (is (nil? @a))
      (atom/close-atom-domain! d))))

(deftest test-persistent-atom-initial-val
  (testing "persistent-atom with initial value"
    (let [b (str test-base "-iv")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:x 1})]
      (is (= 1 (:x @a)))
      (atom/close-atom-domain! d))))

(deftest test-nil-value
  (testing "swap! to nil is supported"
    (let [b (str test-base "-nl")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:x 1})]
      (reset! a nil)
      (is (nil? @a))
      (atom/close-atom-domain! d))))

(deftest test-join-atom
  (testing "join-atom reads value written by persistent-atom"
    (let [base (str test-base "-join")
          d1   (atom/persistent-atom-domain base)
          a    (atom/persistent-atom {:id :eve/main :persistent base} {:count 5})]
      (atom/close-atom-domain! d1)
      (let [d2 (atom/persistent-atom-domain base)
            b  (atom/persistent-atom {:id :eve/main :persistent base} nil)]
        (is (= 5 (:count @b)))
        (atom/close-atom-domain! d2)))))

;; --- t/atom :persistent path tests ---

(deftest test-t-atom-swap
  (testing "t/atom with :persistent path — swap! works"
    (let [b (str test-base "-ta-sw")
          a (sa/atom {:id :eve/main :persistent b} {:count 0})]
      (swap! a update :count inc)
      (is (= 1 (:count @a)))
      (swap! a assoc :y 99)
      (is (= 99 (:y @a)))
      (atom/close-atom-domain! (atom/persistent-atom-domain b)))))

(deftest test-t-atom-reset
  (testing "t/atom with :persistent path — reset! works"
    (let [b (str test-base "-ta-rs")
          a (sa/atom {:id :eve/main :persistent b} {:n 0})]
      (reset! a {:n 42})
      (is (= 42 (:n @a)))
      (atom/close-atom-domain! (atom/persistent-atom-domain b)))))
