(ns eve.net.snapshot-test
  "eve.net.snapshot — the VSNP root blob every client of a network atom writes and reads (WIRE.md §7): a value round-trips
   through snapshot / value-of, a long outside int32 among its entries (tag 7: EM6's fourth shape, {:big 4294967296},
   which no writer could store before), and two values equal as maps write one blob."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.net.bytes :as b]
            [eve.net.snapshot :as snap]))

(defn- round-trip [m]
  (let [{:keys [blob blocks]} (snap/snapshot m)]
    (snap/value-of blob (fn [k] (or (get blocks k) (throw (ex-info "no block" {:key k})))))))

(deftest longs-outside-int32
  (testing "the fourth shape"
    (is (= {:big 4294967296} (round-trip {:big 4294967296}))))
  (testing "both edges of int32, and past them both ways (within 2^53: every host holds them exactly)"
    (let [m {:a 2147483647 :b -2147483648 :c 2147483648 :d -2147483649 :e 9007199254740991 :f -9007199254740991}]
      (is (= m (round-trip m)))))
  (testing "tag 7, its eight bytes little-endian two's complement"
    (is (= [0xEE 0xDB 7 0 0 0 0 1 0 0 0] (vec (snap/serialize-scalar 4294967296 nil))))
    (is (= [0xEE 0xDB 7 0xFF 0xFF 0xFF 0x7F 0xFF 0xFF 0xFF 0xFF] (vec (snap/serialize-scalar -2147483649 nil))))
    (is (= 4294967296 (snap/unserialize [0xEE 0xDB 7 0 0 0 0 1 0 0 0])))))

(deftest a-value-round-trips
  (let [m {:n 1 :s "str" :k :kw :t true :f false :z nil :d 2.5 :v [{:x nil}] :l '(1 2) :vv [[1]] :set #{:a}}]
    (is (= m (round-trip m)))))

(deftest one-blob-for-one-value
  (is (= (:key (snap/snapshot {:a 1 :b [2] :c 3})) (:key (snap/snapshot (into {} [[:c 3] [:b [2]] [:a 1]])))))
  (is (= (b/hex (:blob (snap/snapshot {}))) "504e535600000000") "the empty map: the magic and a zero count"))
