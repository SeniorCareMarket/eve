(ns eve.flat.chunk-test
  "Lane C1 — the 64 KiB packed chunk (IMPL-PLAN §3.3, requirements C1.1–C1.7).
   Board rows B6 (chunk bytes) and B7 (chunk reads) are benched in
   eve.flat.chunk-bench, not here."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [eve.flat.chunk :as chunk])
  (:import [java.util Arrays Date UUID]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- hex->bytes ^bytes [^String s]
  (let [n (/ (count s) 2) out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte (Integer/parseInt (subs s (* 2 i) (+ (* 2 i) 2)) 16))))
    out))

(defn- bytes->hex ^String [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xFF)) bs)))

(defn- fixture-rows []
  (edn/read-string (slurp "test/eve/fixtures/flat-bytes.edn")))

(defn- packed-bytes ^bytes [v] (chunk/chunk-bytes (chunk/pack v)))

(defn- same-bytes? [^bytes a ^bytes b] (Arrays/equals a b))

;; ---------------------------------------------------------------------------
;; C1.1 — golden bytes
;; ---------------------------------------------------------------------------

(deftest golden-bytes
  (doseq [{:keys [note value bytes]} (fixture-rows)]
    (testing note
      (let [expected (hex->bytes bytes)
            actual (packed-bytes value)]
        (is (same-bytes? expected actual)
            (str "encode: " (bytes->hex actual) " != fixture " bytes))
        (is (= value (chunk/unpack (chunk/open expected)))
            "decode: fixture bytes do not read back to the fixture value")))))

;; ---------------------------------------------------------------------------
;; C1.2 — round trip, corpora shapes and generated values
;; ---------------------------------------------------------------------------

(def roundtrip-values
  [nil true false 0 1 -1 42 2147483647 -2147483648 9223372036854775807
   1.5 -0.0 3.141592653589793 1/3 1.5M 123456789012345678901234567890N
   "" "hello" "héllo 🌍" :a :ns/kw 'sym 'ns/sym \c
   #uuid "123e4567-e89b-12d3-a456-426614174000"
   ;; Eve's instant on the JVM is java.util.Date (eve.encoding/ms->date)
   (Date. 1700000000000)
   (byte-array [1 2 3]) (byte-array 0)
   [] [1 2 3] [1.0 2.0] [:a :b :a] [nil nil] [[1 [2 [3]]]]
   {} {:a 1} {:a 1 :b "two" :c [1 2] :d {:e #{1 2 3}}}
   {1 2 3 4} {"k" :v} {[1 2] :nested-key}
   #{} #{1 2 3} #{:a :b} #{[1] [2]}
   () (list 1 2 3) (list {:a 1} {:a 2})
   [{:id 1 :name "user-1" :active true :score 10.5 :dept :eng}
    {:id 2 :name "user-2" :active false :score 3.25 :dept :ops}]
   {:deep {:deeper [{:x #{1 2}} {:y (list :a :b)}]}}
   (with-meta {:a 1} {:m :meta})
   (with-meta [1 2] {:m 2})])

(defn- bytes= [^bytes a ^bytes b] (Arrays/equals a b))
(def ^:private byte-array-class (Class/forName "[B"))
(defn- bytes-val? [x] (instance? byte-array-class x))

(defn- value=
  "Round-trip equality: byte arrays by content; maps and sets unordered
   (a packed map's iteration order is canonical key order, not the
   original's); sequentials in order; everything else by =."
  [a b]
  (cond (and (bytes-val? a) (bytes-val? b)) (bytes= a b)
        (and (map? a) (map? b))
        (and (= (count a) (count b))
             (every? (fn [[k v]] (and (contains? b k) (value= v (get b k)))) a))
        (and (set? a) (set? b))
        (and (= (count a) (count b))
             (every? (fn [x] (boolean (some (fn [y] (value= x y)) b))) a))
        (and (sequential? a) (sequential? b))
        (and (= (count a) (count b))
             (every? true? (map value= a b)))
        :else (= a b)))

(deftest chunk-roundtrip-values
  (doseq [v roundtrip-values]
    (let [rt (chunk/unpack (chunk/pack v))]
      (is (value= v rt) (str "round trip changed: " (pr-str v) " -> " (pr-str rt)))
      (when (instance? clojure.lang.IObj v)
        (is (= (meta v) (meta rt)) (str "metadata lost on " (pr-str v)))))))

(def gen-scalar
  (gen/one-of [gen/small-integer (gen/large-integer* {}) gen/boolean gen/char-ascii
               gen/string-alphanumeric gen/keyword gen/symbol
               (gen/double* {:infinite? false :NaN? false})
               (gen/fmap (fn [x] (byte-array (map unchecked-byte x)))
                         (gen/vector gen/byte 0 8))]))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 6)
                  (gen/fmap (fn [xs] (apply list xs)) (gen/vector inner 0 6))
                  (gen/fmap (fn [xs] (into #{} xs)) (gen/vector inner 0 6))
                  (gen/map (gen/one-of [gen/keyword gen/symbol gen/string-alphanumeric
                                        gen/small-integer])
                           inner {:max-elements 6})]))
   gen-scalar))

(defspec chunk-roundtrip 200
  (prop/for-all [v gen-value]
                (value= v (chunk/unpack (chunk/pack v)))))

;; ---------------------------------------------------------------------------
;; C1.3 — fill policy
;; ---------------------------------------------------------------------------

(deftest chunk-fill-policy
  (testing "small values share a chunk"
    (let [chunks (chunk/pack-all (vec (range 100)))]
      (is (= 1 (count chunks)))
      (is (= (vec (range 100)) (vec (mapcat chunk/unpack chunks))))))
  (testing "a chunk seals when the next row would pass 65,535 bytes"
    (let [rows (mapv (fn [i] {:id i :payload (str (apply str (repeat 190 "x")) "-" i)}) (range 2000))
          chunks (chunk/pack-all rows)]
      (is (< 1 (count chunks)))
      (doseq [^bytes c chunks]
        (is (<= (alength c) 65535) "a non-wide chunk passed the seal limit"))
      (is (= rows (vec (mapcat chunk/unpack chunks))))))
  (testing "an oversize value makes one wide chunk"
    (let [big (apply str (repeat 200000 "y"))
          bs (packed-bytes [big])]
      (is (> (alength bs) 65535))
      (is (= [big] (chunk/unpack (chunk/open bs)))))))

;; ---------------------------------------------------------------------------
;; C1.4 — protocols in place, no whole-chunk decode
;; ---------------------------------------------------------------------------

(deftest chunk-protocols-in-place
  (let [rows [{:id 1 :name "a" :score 1.5} {:id 2 :name "b" :score 2.5}
              {:id 3 :name "c" :score 3.5}]
        batch (chunk/pack rows)
        m (chunk/pack {:a [10 20 30] :b {:c 42} :s #{7 8}})
        before (chunk/full-decodes batch)]
    (testing "the batch reads as a vector of row maps"
      (is (= 3 (count batch)))
      (is (= {:id 2 :name "b" :score 2.5} (nth batch 1)))
      (is (= rows (vec batch)))
      (is (= (map :id rows) (map :id batch)))
      (is (= 6 (reduce + (map :id batch)))))
    (testing "lookup, nth, count, seq over a map root"
      (is (= 42 (get-in m [:b :c])))
      (is (= [10 20 30] (get m :a)))
      (is (= 20 (nth (get m :a) 1)))
      (is (= 3 (count m)))
      (is (= #{:a :b :s} (set (keys m))))
      (is (= #{7 8} (get m :s))))
    (testing "hash and print agree with the plain value"
      (is (= (hash rows) (hash batch)))
      (is (= (pr-str rows) (pr-str batch)))
      (is (= (pr-str {:a [10 20 30] :b {:c 42} :s #{7 8}}) (pr-str m))))
    (is (= before (chunk/full-decodes batch))
        "a protocol operation ran the whole-chunk decoder")))

;; ---------------------------------------------------------------------------
;; C1.5 — assoc / conj append; sealed bytes untouched
;; ---------------------------------------------------------------------------

(deftest chunk-update-appends
  (let [v (chunk/pack [1 2 3])
        v-bytes (chunk/chunk-bytes v)
        snapshot (Arrays/copyOf v-bytes (alength v-bytes))
        v2 (conj v 4)]
    (is (= [1 2 3 4] v2))
    (is (= [1 2 3] v) "the old flat changed")
    (is (same-bytes? snapshot v-bytes) "the old chunk's bytes were mutated"))
  (let [m (chunk/pack {:a 1 :b 2})
        m-bytes (chunk/chunk-bytes m)
        snapshot (Arrays/copyOf m-bytes (alength m-bytes))
        m2 (assoc m :b 20)
        m3 (assoc m :c 3)]
    (is (= {:a 1 :b 20} m2))
    (is (= {:a 1 :b 2 :c 3} m3))
    (is (= {:a 1 :b 2} m) "the old flat changed")
    (is (same-bytes? snapshot m-bytes) "the old chunk's bytes were mutated")))

;; ---------------------------------------------------------------------------
;; C1.6 — an unknown type round-trips as a generic record, bytes unchanged
;; ---------------------------------------------------------------------------

(defrecord C1Unregistered [alpha beta gamma])

(deftest chunk-unknown-type
  (let [v (->C1Unregistered 1 "two" :three)
        bs (packed-bytes [v])
        opened (chunk/open bs)
        rec (nth opened 0)]
    (is (chunk/generic-record? rec) "the reader has this type and should not")
    (is (= 1 (:alpha rec)))
    (is (= "two" (:beta rec)))
    (is (= :three (:gamma rec)))
    (is (= "eve.flat.chunk_test/C1Unregistered" (chunk/record-tag rec)))
    (is (same-bytes? bs (packed-bytes opened))
        "re-packing the generic record changed the bytes")))

;; ---------------------------------------------------------------------------
;; C1.7 — one equality domain: packed = plain, both ways; hash agrees
;; ---------------------------------------------------------------------------

(deftest chunk-equality-domain
  (doseq [v [{:a 1 :b [1 2 3] :c #{:x :y}}
             [1 "two" :three {:four 4}]
             [{:id 1 :s 1.5} {:id 2 :s 2.5}]
             {:a 0.0}
             #{1 2 [3]}]]
    (let [s (chunk/pack v)]
      (is (= v s) "plain = flat failed")
      (is (= s v) "flat = plain failed")
      (is (= (hash v) (hash s)) "hash disagrees with the plain value")
      (is (= s (chunk/pack (chunk/unpack s))) "flat != its own round trip")))
  (testing "the float edges behave as the digest does (A10)"
    ;; Flat-driven comparisons follow Eve's digest rule: NaN equals
    ;; NaN, 0.0 differs from -0.0. Plain-driven comparisons are computed
    ;; by clojure.lang itself over decoded Doubles and CANNOT be moved
    ;; from the flat side — measured: vanilla (= {:a ##NaN}
    ;; {:a ##NaN}) is false and (= {:a 0.0} {:a -0.0}) is true, which
    ;; is exactly A10's recorded disagreement between Clojure's = and
    ;; Eve's digest equality. The edges are therefore asserted in the
    ;; directions the flat side drives, plus by digest directly.
    (is (not= (chunk/pack {:a 0.0}) (chunk/pack {:a -0.0})))
    (is (= (chunk/pack {:a ##NaN}) (chunk/pack {:a ##NaN})))
    (is (not= (chunk/pack {:a 0.0}) {:a -0.0}))
    (is (= (chunk/pack {:a ##NaN}) {:a ##NaN}))
    (is (= (chunk/content-digest (chunk/pack {:a ##NaN}))
           (chunk/eve-digest {:a ##NaN})))
    (is (not= (chunk/content-digest (chunk/pack {:a 0.0}))
              (chunk/eve-digest {:a -0.0}))))
  (testing "the packed digest is Eve's digest of the plain value"
    (doseq [v [{:a 1 :b [1 2 3]} [1 "two" :three] #{1 2 3}]]
      (is (= (chunk/content-digest (chunk/pack v)) (chunk/eve-digest v))))))

;; ---------------------------------------------------------------------------
;; flat lanes U2a: lookups from 16 keys are a binary search in key-byte order
;; (FORMAT-V1 §4) — the answers are the plain map's, for every key layout
;; ---------------------------------------------------------------------------

(defrecord WideRec [a b])

(deftest lookups-search-answers-the-plain-map
  (let [maps {:strings-407 (into {} (for [i (range 407)] [(str "Station-" i) [(- i) i]]))
              :keywords-40 (into {} (for [i (range 40)] [(keyword (str "k" i)) i]))
              :ns-keywords-40 (into {} (for [i (range 40)] [(keyword (str "ns" (rem i 3)) (str "k" i)) i]))
              :symbols-30 (into {} (for [i (range 30)] [(symbol (str "s" i)) i]))
              :mixed-shape-40 (into {} (concat (for [i (range 15)] [(keyword (str "k" i)) i])
                                               (for [i (range 15)] [(str "s" i) i])
                                               (for [i (range 10)] [(symbol (str "y" i)) i])))
              :long-strings-20 (into {} (for [i (range 20)] [(str (apply str (repeat (+ 120 i) "é")) i) i]))
              :boundary-strings-20 (into {} (for [i (range 250 270)] [(apply str (repeat i "a")) i]))
              :ints-100 (into {} (for [i (range 100)] [i (str "v" i)]))
              :ints-negative-50 (into {} (for [i (range -25 25)] [(* i 1000003) i]))
              :ints-wide-30 (into {} (for [i (range 30)] [(+ 5000000000 (* i 7)) i]))
              :mixed-scalars-30 (into {} (concat (for [i (range 10)] [i i]) (for [i (range 10)] [(str "x" i) i])
                                                 (for [i (range 10)] [(keyword (str "z" i)) i])))
              :chars-26 (into {} (for [i (range 26)] [(char (+ 97 i)) i]))
              :bigints-20 (into {} (for [i (range 20)] [(+ 100000000000000000000N i) i]))
              :nil-key-20 (into {nil :nil} (for [i (range 20)] [i i]))
              :coll-keys-20 (into {[1 2] :v #{3} :s} (for [i (range 20)] [i i]))
              :record-20 (into (->WideRec 1 2) (for [i (range 20)] [(keyword (str "f" i)) i]))}
        probes (concat [nil 0 1 5 5N (int 5) 99 100 -1 -25000075 5000000000 5000000007 \a \z \A
                        100000000000000000005N "x3" "Station-5" "Station-406" "Station-407" "" "s3"
                        :k3 :k39 :k40 :ns0/k0 :ns1/k1 :ns1/k0 's3 'y9 'y10 [1 2] #{3} :f3 :a :b
                        (apply str (repeat 255 "a")) (apply str (repeat 256 "a")) (apply str (repeat 257 "a"))
                        true false]
                       (for [i (range 0 407 13)] (str "Station-" i)))]
    (doseq [[label m] maps]
      (let [f (chunk/pack m)]
        (testing (str label)
          (is (= (count m) (count f)))
          (is (every? (fn [k] (= (get m k ::absent) (get f k ::absent))) (keys m)) "every key, found")
          (is (every? (fn [k] (= (get m k ::absent) (get f k ::absent))) probes) "present and absent probes")
          (is (every? (fn [k] (= (contains? m k) (contains? f k))) probes) "contains?"))))
    (testing "a batch row's fields (row-lookup)"
      (let [rows (vec (for [i (range 5)] (into {} (for [j (range 20)] [(keyword (str "c" j)) (+ i j)]))))
            f (chunk/pack rows)]
        (is (= rows (mapv (fn [i] (into {} (map (fn [j] (let [k (keyword (str "c" j))] [k (get (nth f i) k)])))
                                        (range 20)))
                          (range 5))))
        (is (nil? (get (nth f 0) :c20)))))))
