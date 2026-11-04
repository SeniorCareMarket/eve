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

;; --- Mappings held per deref ---

(defn- mapping-count
  "Lines of /proc/self/maps (one per mapping) on Linux; nil elsewhere."
  []
  (try (count (.split (.readFileSync (js/require "fs") "/proc/self/maps" "utf8") "\n"))
       (catch :default _ nil)))

(deftest test-deref-does-not-leak-mappings
  (testing "a deref reads the slab headers through the mappings it already holds
            (refresh-mmap-slabs! opened a 64-byte peek per slab class per call,
            ~12 mappings per deref, until vm.max_map_count)"
    (let [b (str test-base "-maps")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:x 1})
          _ @a
          m0 (mapping-count)
          _ (dotimes [_ 500] @a)
          m1 (mapping-count)
          _ (dotimes [_ 1500] @a)
          m2 (mapping-count)]
      (when m0
        ;; A per-deref leak grows in proportion (the old code: ~6000, then
        ;; ~18000); the second batch shows whether what remains is one-time.
        (let [g1 (- m1 m0) g2 (- m2 m1)]
          (println "    mappings: +" g1 "over the first 500 derefs, +" g2 "over the next 1500")
          (is (< (+ g1 g2) 1000) (str "mappings grew by " (+ g1 g2) " over 2000 derefs"))))
      (atom/close-atom-domain! d))))
