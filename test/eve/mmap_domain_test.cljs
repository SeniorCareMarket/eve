(ns eve.mmap-domain-test
  "Tests for multi-atom domain API (Phase F).
   Run via: node target/thread-test/all.js mmap-domain"
  (:require [cljs.test :refer [deftest testing is]]
            [eve.atom :as atom]
            [eve.atom :as sa]))

(def ^:private test-base (str "/tmp/eve-domain-test-" (js/Date.now)))

;; --- Direct persistent-atom API tests ---

(deftest test-domain-create-and-deref
  (testing "fresh domain has nil registry"
    (let [d (atom/persistent-atom-domain (str test-base "-cre"))]
      (is (nil? @d))
      (atom/close-atom-domain! d))))

(deftest test-registry-lookup
  (testing "looking up an existing atom returns same slot"
    (let [b (str test-base "-lookup")
          d (atom/persistent-atom-domain b)]
      (let [a (atom/persistent-atom {:id :test.ns/x :persistent b} {:v 42})]
        (is (= 42 (:v @a)))
        ;; Lookup again — should return existing atom, not create new one
        (let [b-atom (atom/persistent-atom {:id :test.ns/x :persistent b} nil)]
          ;; b sees value 42, not nil (initial-val ignored for existing atom)
          (is (= 42 (:v @b-atom)))
          ;; Same slot-idx
          (is (= (.-atom-slot-idx a) (.-atom-slot-idx b-atom)))))
      (atom/close-atom-domain! d))))

(deftest test-domain-join
  (testing "persistent-atom-domain auto-joins existing domain"
    (let [base (str test-base "-djoin")
          d1   (atom/persistent-atom-domain base)]
      ;; Create an atom in d1
      (let [a (atom/persistent-atom {:id :test.ns/val :persistent base} {:count 10})]
        (is (= {:count 10} @a)))
      (atom/close-atom-domain! d1)
      ;; Re-open (auto-joins since files exist)
      (let [d2 (atom/persistent-atom-domain base)]
        ;; Look up same atom
        (let [b (atom/persistent-atom {:id :test.ns/val :persistent base} nil)]
          (is (= {:count 10} @b))
          ;; Swap from d2
          (swap! b update :count + 5)
          ;; Read via new persistent-atom call
          (let [a2 (atom/persistent-atom {:id :test.ns/val :persistent base} nil)]
            (is (= {:count 15} @a2))))
        (atom/close-atom-domain! d2)))))

(deftest test-default-global-domain
  (testing "persistent-atom with keyword shorthand uses default global domain"
    (let [b (str test-base "-global-" (js/Date.now))
          d (atom/persistent-atom-domain b)]
      (binding [atom/*global-persistent-atom-domain* d]
        (let [a (atom/persistent-atom ::counter {:count 0})]
          (swap! a update :count inc)
          (is (= 1 (:count @a)))
          (let [b-atom (atom/persistent-atom ::users {})]
            (swap! b-atom assoc :alice {:role :admin})
            (is (= {:alice {:role :admin}} @b-atom))
            ;; a unaffected
            (is (= 1 (:count @a))))))
      (atom/close-atom-domain! d))))

;; --- t/atom :persistent tests ---

(deftest test-t-atom-multi-atom-independent
  (testing "t/atom with :persistent — two atoms are independent"
    (let [b (str test-base "-ta-multi")
          d (atom/persistent-atom-domain b)]
      (let [a (sa/atom {:id :test.ns/counter :persistent b} {:count 0})
            b-atom (sa/atom {:id :test.ns/users :persistent b} {})]
        (swap! a update :count inc)
        (swap! b-atom assoc :alice {:role :admin})
        (is (= 1 (:count @a)))
        (is (= {:alice {:role :admin}} @b-atom))
        (swap! a update :count inc)
        (is (= 2 (:count @a)))
        (is (= {:alice {:role :admin}} @b-atom)))
      (atom/close-atom-domain! d))))

(deftest test-t-atom-scalar-values
  (testing "t/atom with :persistent — map values work"
    (let [b (str test-base "-ta-scalar")
          d (atom/persistent-atom-domain b)]
      (let [a (sa/atom {:id :test.ns/num :persistent b} {:v 42})]
        (is (= 42 (:v @a)))
        (swap! a update :v inc)
        (is (= 43 (:v @a))))
      (atom/close-atom-domain! d))))

(deftest test-t-atom-nil-value
  (testing "t/atom with :persistent — nil initial value"
    (let [b (str test-base "-ta-nil")
          d (atom/persistent-atom-domain b)]
      (let [a (sa/atom {:id :test.ns/maybe :persistent b} nil)]
        (is (nil? @a))
        (reset! a {:x 1})
        (is (= {:x 1} @a)))
      (atom/close-atom-domain! d))))

(deftest test-t-atom-persistent-true
  (testing "t/atom with :persistent true uses default global domain"
    (let [b (str test-base "-ta-ptrue-" (js/Date.now))
          d (atom/persistent-atom-domain b)]
      (binding [atom/*global-persistent-atom-domain* d]
        (let [a (sa/atom {:id ::counter :persistent true} {:count 0})]
          (swap! a update :count inc)
          (is (= 1 (:count @a)))
          (let [b-atom (sa/atom {:id ::users :persistent true} {})]
            (swap! b-atom assoc :alice {:role :admin})
            (is (= {:alice {:role :admin}} @b-atom))
            (is (= 1 (:count @a))))))
      (atom/close-atom-domain! d))))

(deftest test-t-atom-persistent-path
  (testing "t/atom with :persistent path auto-creates domain"
    (let [b (str test-base "-ta-path")]
      (let [a (sa/atom {:id :test.ns/x :persistent b} {:v 42})]
        (is (= 42 (:v @a)))
        (swap! a update :v inc)
        (is (= 43 (:v @a))))
      (atom/close-atom-domain! (atom/persistent-atom-domain b)))))

(deftest test-t-atom-persistent-path-rejoin
  (testing "t/atom with :persistent path — close and rejoin sees data"
    (let [b (str test-base "-ta-rejoin")]
      (let [a (sa/atom {:id :test.ns/val :persistent b} {:count 10})]
        (is (= {:count 10} @a)))
      (atom/close-atom-domain! (atom/persistent-atom-domain b))
      ;; Re-open
      (let [a2 (sa/atom {:id :test.ns/val :persistent b} nil)]
        (is (= {:count 10} @a2)))
      (atom/close-atom-domain! (atom/persistent-atom-domain b)))))
