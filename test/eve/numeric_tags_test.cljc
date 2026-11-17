(ns eve.numeric-tags-test
  "EVM-11b (EM5): eve's tags for the numbers past a fixed width — BIGINT 0x16, RATIO 0x17, BIGDEC 0x18
   (eve/doc/unification/FORMAT-DECISIONS.md §8). The bytes are eve-bytes.edn's rows; this file holds what such a
   value does in eve memory, on every host that has the number: a round trip inline and as a map's value, the
   one-key rule (= 5 5N is one key, as in Clojure's maps), the canonical decimal (= 1.5M 1.50M is one key), and a
   host without the number refusing its bytes loudly. ClojureScript now reads RATIO and
   BIGDEC as Eve types (eve.numeric), same bytes as the other hosts."
  ;; the codec's ^bytes return hint names eve.host's bytes where it is called (clojerl resolves it there)
  (:refer-clojure :exclude [bytes])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [bytes]]
            [eve.codec :as codec]
            [eve.numeric :as num]
            [eve.core :as e]
            [eve.deftype-proto.alloc :as alloc]
            ;; the collection writers a host map or vector is written through
            [eve.map] [eve.set] [eve.vec] [eve.list]))

(defn- sio [] (alloc/heap-domain-sio! {}))

(defn- hex [b]
  (let [n #?(:cljs (.-length b) :default (count b))]
    (apply str (map (fn [i] (let [x (bit-and (int #?(:cljs (aget b i) :default (nth b i))) 0xFF)]
                              (str (nth "0123456789abcdef" (quot x 16)) (nth "0123456789abcdef" (mod x 16)))))
                    (range n)))))

(defn- key-hex [v] (hex (codec/encode-key v)))

(defn- round-trip
  "v through the value encoder and back, in a fresh heap domain"
  [v]
  (let [s (sio)] (codec/decode s (codec/encode-value s v))))

(defn- in-map
  "v as the value of :k in an eve map, read back"
  [v]
  (let [s (sio)] (e/get (codec/decode s (codec/encode-value s {:k v})) :k)))

(defn- keyed
  "an eve map holding k → :hit, looked up by k2"
  [k k2]
  (let [s (sio)] (e/get (codec/decode s (codec/encode-value s {k :hit})) k2)))

#?(:cljs
   (do
     (deftest js-bigint-round-trips
       (testing "a BigInt past int64 is BIGINT and reads back a BigInt"
         (let [b (js/BigInt "18446744073709551616")]
           (is (= "eedb1609000000010000000000000000" (key-hex b)))
           (is (= b (round-trip b)))
           (is (= b (in-map b)))
           (is (= :hit (keyed b (js/BigInt "18446744073709551616"))))))
       (testing "a BigInt in int32 is written by its magnitude: INT32"
         (is (= (key-hex 5) (key-hex (js/BigInt 5))))))

     (deftest cljs-ratio-and-decimal-round-trip
       (testing "RATIO and BIGDEC bytes decode to Eve types, never a double"
         (let [s (sio)
               b (fn [h] (js/Uint8Array. (clj->js (map #(js/parseInt (subs h (* 2 %) (+ 2 (* 2 %))) 16)
                                                       (range (quot (count h) 2))))))
               half (codec/decode s (b "eedb1701000000010100000002"))
               fifteen-tenths (codec/decode s (b "eedb1801000000010000000f"))]
           (is (num/ratio? half))
           (is (num/equiv half (num/ratio 1 2)))
           (is (num/decimal? fifteen-tenths))
           (is (num/equiv fifteen-tenths (num/bigdec "1.5")))
           (is (= "eedb1701000000010100000002" (key-hex (num/ratio 1 2))))
           (is (= "eedb1801000000010000000f" (key-hex (num/bigdec "1.5"))))
           (is (= "eedb1801000000010000000f" (key-hex (num/bigdec "1.50")))))))))

#?(:cljs nil
   :clje nil
   :default
   (do
     (deftest big-integers-round-trip
       (testing "an integer past int64 is BIGINT and reads back equal"
         (doseq [v [9223372036854775808N -9223372036854775809N 18446744073709551616N
                    -340282366920938463463374607431768211456N]]
           (is (= v (round-trip v)) (pr-str v))
           (is (integer? (round-trip v)))
           (is (= v (in-map v)) (pr-str v))))
       (testing "as a key"
         (is (= :hit (keyed 18446744073709551616N 18446744073709551616N)))))

     (deftest one-key-for-equal-integers
       (testing "(= 5 5N) with one hash, so one key: the integer is written by its magnitude"
         (is (= (key-hex 5) (key-hex 5N)))
         (is (= (key-hex 2147483648) (key-hex 2147483648N)))
         (is (= :hit (keyed 5N 5)))
         (is (= :hit (keyed 5 5N)))))

     (deftest ratios-round-trip
       ;; built with /: clojerl's reader rejects a ratio literal even in a branch it skips
       (doseq [v [(/ 1 2) (/ -3 4) (/ 1 128) (/ 22 7) (/ -1 340282366920938463463374607431768211456)]]
         (is (= v (round-trip v)) (pr-str v))
         (is (ratio? (round-trip v)) (pr-str v))
         (is (= v (in-map v)) (pr-str v)))
       (testing "a ratio is not its double: (= 1/2 0.5) is false, two keys"
         (is (not= (key-hex (/ 1 2)) (key-hex 0.5)))
         (is (nil? (keyed (/ 1 2) 0.5)))))

     (deftest decimals-round-trip
       (doseq [v [1.5M -0.001M 100M 0M 12345678901234567890.5M (bigdec "1E+2") 3.14159265358979323846264338327950288M]]
         (is (= v (round-trip v)) (pr-str v))
         (is (decimal? (round-trip v)) (pr-str v))
         (is (= v (in-map v)) (pr-str v)))
       (testing "(= 1.5M 1.50M) with one hash, so one key: trailing zeros are not written"
         (is (= (key-hex 1.5M) (key-hex 1.50M)))
         (is (= (key-hex 100M) (key-hex (bigdec "1E+2"))))
         (is (= :hit (keyed 1.50M 1.5M))))
       (testing "a decimal is not an integer: (= 1 1M) is false, two keys"
         (is (not= (key-hex 1) (key-hex 1M)))
         (is (nil? (keyed 1M 1)))))))
