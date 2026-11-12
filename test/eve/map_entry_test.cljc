(ns eve.map-entry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.map-entry :as me]
            [eve.core :as e]
            [eve.codec :as codec]
            [eve.deftype-proto.data :as d]))

(defn- in-a-domain-of-its-own
  "Construction law (2026-11-11): Eve data is constructed in a domain
   context, never through a process-heap fallback (alloc/current-sio throws
   outside one). Each test here builds in an explicit heap domain of its
   own (rrb-vector-test's fixture), dropped with the test."
  [f]
  (binding [d/*domain-sio* (alloc/heap-domain-sio!
                                         {:capacities {0 (* 64 1024 1024)
                                                       1 (* 16 1024 1024)
                                                       2 (* 16 1024 1024)
                                                       3 (* 192 1024 1024)
                                                       4 (* 16 1024 1024)
                                                       5 (* 16 1024 1024)}})]
                (f)))

(use-fixtures :each in-a-domain-of-its-own)


(deftest map-entry-contract
  (doseq [[k v] [[:k 42] [nil nil] [[1 2] {:a 3}]]]
    (let [entry (me/map-entry k v) pair [k v]]
      (is (map-entry? entry))
      (is (= k (key entry)))
      (is (= v (val entry)))
      (is (vector? entry))
      (is (= 2 (count entry)))
      (is (= [k :replacement] (assoc entry 1 :replacement)))
      (is (= [k v :x] (assoc entry 2 :x)))
      (is (= v (peek entry)))
      (is (= [k] (pop entry)))
      (is (= k (nth entry 0)))
      (is (= v (nth entry 1)))
      (is (= :missing (nth entry 2 :missing)))
      (is (= :missing (nth entry -1 :missing)))
      (is (= pair (vec (seq entry))))
      (is (= entry pair))
      (is (= pair entry))
      (is (= (hash pair) (hash entry)))
      (is (= {k v} (conj {} entry)))
      (is (= [k v :x] (conj entry :x)))
      (is (= [] (empty entry)))
      (is (= k (e/nth entry 0)))
      (is (= :missing (e/nth entry 2 :missing))))))

(deftest map-entry-memory-roundtrip
  (let [entry (me/map-entry :key 42)
        sio (d/-eve-sio entry)
        readback (codec/decode sio (codec/encode-value sio entry))]
    (is (map-entry? readback))
    (is (= :key (key readback)))
    (is (= 42 (val readback)))
    (is (= [:key 42] readback))
    (is (= (hash [:key 42]) (hash readback)))))
