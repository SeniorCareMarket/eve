(ns eve.value-buffer-test
  "EVM-11e (EM5): eve.deftype.array as a runtime's arrays' home — fixed captures and argument vectors as eve
   arrays, a growing accumulator as eve.deftype.value-buffer (sarr.vs's operations)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype.array :as arr]
            [eve.deftype.value-buffer :as vb]
            ;; a collection in a slot is written through its writer
            [eve.map] [eve.set] [eve.vec] [eve.list]))

(defn- caught [f] (try (f) nil (catch #?(:cljs :default :jank jank.runtime.object_ref :default Exception) ex (or (:type (ex-data ex)) :thrown))))

(deftest a-closure-s-captures
  (let [f (fn [x] (inc x))
        caps (arr/make-array 4)]
    (arr/aset caps 0 f)
    (arr/aset caps 1 41)
    (arr/aset caps 2 "s")
    (arr/aset caps 3 {:k [1 2]})
    (is (identical? f (arr/aget caps 0)) "a captured fn reads back by identity (the reference tag)")
    (is (= 42 ((arr/aget caps 0) (arr/aget caps 1))))
    (is (= "s" (arr/aget caps 2)))
    (is (= 4 (arr/alength caps)))))

(deftest an-accumulator-grows-in-place-then-by-doubling
  (let [b (reduce vb/push! (vb/buffer 4) (range 100))]
    (is (= 100 (vb/buffer-count b)))
    (is (<= 100 (vb/buffer-capacity b)))
    (is (= 128 (vb/buffer-capacity b)) "4 → 8 → … → 128")
    (is (= (range 100) (map #(vb/buffer-nth b %) (range 100))))
    (is (= (range 100) (arr/array-seq (vb/->array b))))
    (is (= 100 (arr/alength (vb/->array b))) "->array is exact")
    (testing "pop!, then a push! overwrites the slot"
      (is (= 99 (vb/pop! b)))
      (is (= 99 (vb/buffer-count b)))
      (vb/push! b :new)
      (is (= :new (vb/buffer-nth b 99))))
    (testing "set!"
      (vb/buffer-set! b 0 :zero)
      (is (= :zero (vb/buffer-nth b 0))))
    (testing "out of range, and empty"
      (is (= :index (caught #(vb/buffer-nth b 100))))
      (is (= :empty (caught #(vb/pop! (vb/buffer))))))
    (testing "values of every kind"
      (let [f (fn [] :f)
            b2 (reduce vb/push! (vb/buffer 1) [nil true 7 2.5 "x" :k f [1 2]])]
        (is (= [nil true 7 2.5 "x" :k] (map #(vb/buffer-nth b2 %) (range 6))))
        (is (identical? f (vb/buffer-nth b2 6)))
        (is (= [1 2] (vec (vb/buffer-nth b2 7))))))))

#?(:clj
   (deftest only-its-owner-writes-it
     (let [b (vb/buffer)]
       (vb/push! b 1)
       (is (= :not-owner @(future (caught #(vb/push! b 2)))))
       (is (= 1 (vb/buffer-nth b 0)) "a read from another thread is a read"))))
