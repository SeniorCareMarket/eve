(ns eve.flat.chunk-node-test
  "Lane X1 (doc/flat/IMPL-PLAN.md §3.3): the packed chunk's bytes on a
   second host. The golden fixtures encode and decode on Node (X1.1); a
   value packed on the JVM and on Node is the same bytes (X1.2, the probe
   that settles A14 for the format); i64 columns are exact through
   BigInt64Array and -0.0 / integral doubles are kept in f64 columns
   (X1.3); a chunk viewed through typed arrays starts 8-aligned (X1.4).

   Run (from the repo root, as in eve.eve-bytes-fixture-test):

     clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-x1-test
     node target/flat-x1-test/main.js

   The JVM reference bytes in `jvm-reference` were produced by lane C1's
   JVM writer (eve.flat.chunk/pack + chunk-bytes) at the C1 commit
   pinned in doc/flat/X1-STATUS.md; a byte-identity claim is only as
   good as that pin."
  (:require [cljs.test :refer [deftest is testing]]
            [cljs.reader :as edn]
            [eve.flat.chunk :as chunk] [clojure.string]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(def fixture-path "test/eve/fixtures/flat-bytes.edn")

(defn- read-fixtures []
  (if (.existsSync (js/require "fs") fixture-path)
    (edn/read-string
     {:readers {'uuid uuid
                'inst #_{:splint/disable [lint/fn-wrapper]} (fn [s] (js/Date. s))
                'char first}}
     (.readFileSync (js/require "fs") fixture-path "utf8"))
    []))

(defn- u8->hex [^js/Uint8Array u8]
  (clojure.string/join
   (map
    (fn [i] (let [h (.toString (aget u8 i) 16)] (if (< (aget u8 i) 16) (str "0" h) h)))
    (range (.-length u8)))))

(defn- hex->u8 [s]
  (let [n (quot (count s) 2)
        u8 (js/Uint8Array. n)]
    (dotimes [i n]
      (aset u8 i (js/parseInt (subs s (* 2 i) (+ (* 2 i) 2)) 16)))
    u8))

(defn- pack->u8
  "The chunk bytes of (chunk/pack v) — a Uint8Array in a fresh buffer."
  [v]
  (chunk/chunk-bytes (chunk/pack v)))

(defn- realize
  "An opened flat handle as plain ClojureScript data, by a protocol
   walk over the handle (count/seq/get in place, no whole-chunk decode)."
  [h]
  (cond
    (nil? h) nil
    (map? h) (into {} (map (fn [e] [(realize (key e)) (realize (val e))])) h)
    (set? h) (into #{} (map realize) h)
    (vector? h) (mapv realize h)
    (sequential? h) (doall (map realize h))
    :else h))

;; ---------------------------------------------------------------------------
;; X1.2's JVM reference corpus. Entries are added by running lane C1's JVM
;; writer over the same logical values; :value is this host's construction
;; of the value, :jvm-bytes the JVM's packing of it.
;; ---------------------------------------------------------------------------

(def neg-zero (/ -1 js/Infinity))

(def numerics-rows
  "A batch whose :id column needs i64 (values past 2^53 and the i64
   minimum) and whose :w column is f64 with the two double edges."
  [{:id (js/BigInt "9007199254740993") :w neg-zero}
   {:id (js/BigInt "-9223372036854775808") :w 3.0}
   {:id (js/BigInt "42") :w 1.5}])

(def jvm-reference
  [{:note "batch-numerics" :value numerics-rows
    ;; generated from the merged JVM writer (eve.flat.chunk/pack +
    ;; chunk-bytes) over the JVM mirror of numerics-rows (longs +
    ;; doubles, -0.0 literal), C1 pin 9ea620b, merged tree at 1263ee5;
    ;; 120 bytes. Generator: /tmp script recorded in X1-STATUS.md.
    :jvm-bytes "4556533101000000780000007000000002000100200000002d0000000000000000000400070007026964070177000004000200010009000000000000000000800000000000000840000000000000f83f0a00000000000000010000000000200000000000000000802a000000000000002600033500500000"}])

;; ---------------------------------------------------------------------------
;; X1.1 — the golden fixtures encode and decode on Node
;; ---------------------------------------------------------------------------

(deftest golden-bytes
  (let [rows (read-fixtures)]
    (is (seq rows)
        "fixture rows present (test/eve/fixtures/flat-bytes.edn, lane C1)")
    (doseq [{:keys [note value bytes]} rows]
      (testing (str "fixture row: " note)
        (is (= bytes (u8->hex (pack->u8 value)))
            "encode: the value packs to the fixture bytes on Node")
        (is (= value (realize (chunk/open (hex->u8 bytes))))
            "decode: the fixture bytes open to the value on Node")))))

;; ---------------------------------------------------------------------------
;; X1.2 — one value packed on the JVM and on Node is the same bytes
;; ---------------------------------------------------------------------------

(deftest jvm-node-same-bytes
  ;; the fixture bytes are JVM-authored (hand-computed from FORMAT-V1,
  ;; then writer-produced and checked byte by byte — see the fixture
  ;; file's header): identity over them is the claim over C1's corpus.
  (doseq [{:keys [note value bytes]} (read-fixtures)]
    (testing (str "fixture identity: " note)
      (is (= bytes (u8->hex (pack->u8 value)))
          "the Node packing is the JVM's bytes")))
  ;; the X1 reference corpus: values chosen for cross-host determinism
  ;; (symbol-table ties, batch columns, typed columns, bytes, big ints).
  (doseq [{:keys [note value jvm-bytes]} jvm-reference]
    (testing (str "jvm reference: " note)
      (is (= jvm-bytes (u8->hex (pack->u8 value)))
          "the Node packing is the JVM writer's bytes at the pinned C1 commit"))))

;; ---------------------------------------------------------------------------
;; X1.3 — i64 exact through BigInt64Array; -0.0 and integral doubles kept
;; ---------------------------------------------------------------------------

(defn- assert-numeric-edges [handle]
  (let [ids (chunk/column-view handle :id)
        ws (chunk/column-view handle :w)]
    (is (instance? js/BigInt64Array ids)
        "the :id column is a dense i64 column (kind 10)")
    (is (= (js/BigInt "9007199254740993") (aget ids 0))
        "an i64 past 2^53 is exact through BigInt64Array")
    (is (= (js/BigInt "-9223372036854775808") (aget ids 1))
        "the i64 minimum is exact")
    (is (= (js/BigInt "42") (aget ids 2)))
    (is (instance? js/Float64Array ws)
        "the :w column stays a dense f64 column (kind 9), not narrowed to ints")
    (is (js/Object.is (aget ws 0) neg-zero)
        "-0.0 keeps its sign in the typed column")
    (is (== 3.0 (aget ws 1)) "an integral double is kept as a double")
    (is (== 1.5 (aget ws 2)))))

(deftest node-numeric-edges
  (testing "a batch packed and opened on Node"
    (assert-numeric-edges (chunk/open (pack->u8 numerics-rows))))
  (testing "a standalone i64 column (a vector of big integers)"
    (let [view (chunk/column-view
                (chunk/open (pack->u8 [(js/BigInt "9007199254740993")
                                       (js/BigInt "5")])))]
      (is (instance? js/BigInt64Array view))
      (is (= (js/BigInt "9007199254740993") (aget view 0)))
      (is (= (js/BigInt "5") (aget view 1)))))
  (testing "the JVM's packing of the same batch, opened on Node"
    (if-let [ref (first (filter #(= "batch-numerics" (:note %)) jvm-reference))]
      (assert-numeric-edges (chunk/open (hex->u8 (:jvm-bytes ref))))
      (is false "jvm-reference has no batch-numerics entry yet (see X1-STATUS.md)"))))

;; ---------------------------------------------------------------------------
;; X1.4 — a chunk viewed through typed arrays starts 8-aligned
;; ---------------------------------------------------------------------------

(deftest node-alignment
  (let [u8 (pack->u8 numerics-rows)
        handle (chunk/open u8)
        ids (chunk/column-view handle :id)
        ws (chunk/column-view handle :w)]
    (is (zero? (mod (.-byteOffset u8) 8))
        "the chunk itself starts 8-aligned in its buffer")
    (is (zero? (mod (.-byteOffset ids) 8))
        "the i64 column's view starts 8-aligned")
    (is (zero? (mod (.-byteOffset ws) 8))
        "the f64 column's view starts 8-aligned")))
