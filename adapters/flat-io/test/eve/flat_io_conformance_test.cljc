(ns eve.flat-io-conformance-test
  "The flat-io feature's conformance test (doc/flat/FLAT-IO.md; adapters/README.md, flat-io): every root of
   eve.flat-io answers it alike — flat-io/portable on every host, and each fast root (flat-io/jvm, flat-io/js,
   flat-io/vs) in its place. Every contract function against fixed byte strings: the golden chunks of
   test/eve/fixtures/flat-bytes.edn read at every offset against a reference written here from the contract's
   text (little-endian, unsigned LEB128), and FORMAT-V1's edge rows — WIDE and narrow offsets, varints of 1 / 2 / 3
   bytes, utf-8 of 1- to 4-byte characters, i64 past 2^53, NaN and -0.0, i32 sign, key-cmp across the 255 / 256-byte
   length boundary (held to eve.codec/encode-key's bytes, the order it names), the writer's every call at both
   offset widths, and tuples of every dense kind. Its answers are the portable root's; a fast root answers the same.

   Run (its root, adapters/flat-io/test, is on no host's default classpath):
     JVM   clojure -M:flat-io-test
     bb    bb test:flat-io
     Node  clojure -M:node-test:flat-io-node -m shadow.cljs.devtools.cli compile flat-io-test
           && node target/flat-io-test/main.js

   Where the contract leaves a value's host type to the host (an i64 is a long on the JVM-class hosts, a js/BigInt
   on ClojureScript; an f32 element of a tuple a Float on the JVM), the test compares the printed decimal or the
   double. One answer differs by host by design and is pinned per host: a u64 tuple element past 2^63 reads as its
   two's complement long on the JVM-class hosts (as eve.flat.column reads kind 11), exactly as a js/BigInt on
   ClojureScript."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.flat-io :as fio]
            [eve.encoding :as enc]
            [eve.codec :as codec]
            [eve.deftype-proto.data :as d]
            #?(:cljs [cljs.reader :as edn] :default [clojure.edn :as edn])))

;; ---------------------------------------------------------------------------
;; byte strings and the reference reader (the contract's text, over a vector of 0..255)
;; ---------------------------------------------------------------------------

(defn- hb
  "Host bytes of bv (a vector of 0..255)."
  [bv]
  (enc/bytes-of bv))

(defn- ch "A chunk over bv." [bv] (fio/chunk (hb bv)))

(defn- hex->bv [s]
  (mapv (fn [i] #?(:cljs (js/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16)
                   :default (Long/parseLong (subs s (* 2 i) (+ 2 (* 2 i))) 16)))
        (range (quot (count s) 2))))

(defn- bv-of
  "The bytes of chunk c as a vector of 0..255, read through fio/u8."
  [c]
  (mapv (fn [i] (fio/u8 c i)) (range (fio/chunk-len c))))

(defn- host-bv
  "Host byte array bs as a vector of 0..255 (through a chunk over it)."
  [bs]
  (bv-of (fio/chunk bs)))

(defn- ref-u16 [bv i] (+ (nth bv i) (* 256 (nth bv (inc i)))))
(defn- ref-u32 [bv i] (+ (ref-u16 bv i) (* 65536 (ref-u16 bv (+ i 2)))))
(defn- ref-i32 [bv i] (let [u (ref-u32 bv i)] (if (>= u 2147483648) (- u 4294967296) u)))
(defn- ref-i64-str
  "The i64 at i as a decimal string, exact on every host."
  [bv i]
  (let [lo (ref-u32 bv i) hi (ref-u32 bv (+ i 4))]
    #?(:cljs (let [v (+ (js/BigInt lo) (* (js/BigInt hi) (js/BigInt 4294967296)))]
               (str (if (>= hi 2147483648) (- v (js/BigInt "18446744073709551616")) v)))
       :default (let [v (+' lo (*' hi 4294967296))]
                  (str (if (>= hi 2147483648) (-' v 18446744073709551616N) v))))))
(defn- ref-varint
  "[value next] of the unsigned LEB128 at i, or nil when it runs past the end."
  [bv i]
  (loop [i i mul 1 v 0]
    (when (< i (count bv))
      (let [x (nth bv i)]
        (if (< x 128)
          [(+ v (* x mul)) (inc i)]
          (recur (inc i) (* mul 128) (+ v (* (- x 128) mul))))))))

(defn- signum [x] (cond (neg? x) -1 (pos? x) 1 :else 0))

(defn- negative-zero?
  "x is -0.0. (Not (neg? (/ 1.0 x)): a boxed division by zero throws on the JVM-class hosts — bb boxes every number.)"
  [x]
  (and (zero? x) #?(:cljs (neg? (/ 1 x)) :default (neg? (Math/copySign 1.0 (double x))))))

(defn- read-fixtures []
  (let [path "test/eve/fixtures/flat-bytes.edn"]
    (edn/read-string #?(:cljs (.readFileSync (js/require "fs") path "utf8")
                        :default (slurp path)))))

;; ---------------------------------------------------------------------------
;; chunks
;; ---------------------------------------------------------------------------

(deftest chunks
  (testing "chunk / chunk-bytes / chunk-len: the bytes come back as they went in"
    (doseq [bv [[] [0] [1 2 3] (vec (range 256))]]
      (let [c (ch bv)]
        (is (= (count bv) (fio/chunk-len c)))
        (is (= bv (host-bv (fio/chunk-bytes c))))
        (is (= bv (bv-of c)))))))

;; ---------------------------------------------------------------------------
;; reads: the golden chunks at every offset, against the reference
;; ---------------------------------------------------------------------------

(deftest golden-chunks-every-offset
  (let [rows (read-fixtures)]
    (is (= 3 (count rows)) "the golden chunks of test/eve/fixtures/flat-bytes.edn")
    (doseq [{:keys [note bytes]} rows]
      (let [bv (hex->bv bytes) c (ch bv) n (count bv)]
        (testing (str "golden chunk: " note)
          (is (= n (fio/chunk-len c)))
          (is (= [0x45 0x56 0x53 0x31] (mapv (fn [i] (fio/u8 c i)) (range 4))) "the magic, EVS1")
          (is (= n (fio/u32 c 8)) "the header's length is the chunk's")
          (is (= bv (mapv (fn [i] (fio/u8 c i)) (range n))) "u8 at every offset")
          (is (= (mapv (fn [i] (ref-u16 bv i)) (range (dec n)))
                 (mapv (fn [i] (fio/u16 c i)) (range (dec n)))) "u16 at every offset")
          (is (= (mapv (fn [i] (ref-u32 bv i)) (range (- n 3)))
                 (mapv (fn [i] (fio/u32 c i)) (range (- n 3)))) "u32 at every offset")
          (is (= (mapv (fn [i] (ref-i32 bv i)) (range (- n 3)))
                 (mapv (fn [i] (fio/i32 c i)) (range (- n 3)))) "i32 at every offset")
          (is (= (mapv (fn [i] (ref-i64-str bv i)) (range (- n 7)))
                 (mapv (fn [i] (str (fio/i64 c i))) (range (- n 7)))) "i64 at every offset (as decimal)")
          (let [is* (filterv (fn [i] (some? (ref-varint bv i))) (range n))]
            (is (= (mapv (fn [i] (ref-varint bv i)) is*) (mapv (fn [i] (fio/varint c i)) is*)) "varint at every offset")
            (is (= (mapv (fn [i] (first (ref-varint bv i))) is*) (mapv (fn [i] (fio/varint-v c i)) is*)) "varint-v")
            (is (= (mapv (fn [i] (second (ref-varint bv i))) is*) (mapv (fn [i] (fio/varint-next c i)) is*)) "varint-next")))))))

;; ---------------------------------------------------------------------------
;; reads: FORMAT-V1's edge rows
;; ---------------------------------------------------------------------------

(deftest offsets-narrow-and-wide
  (let [c (ch [0x34 0x12 0xFF 0xFF 0x00 0x00 0x01 0x00 0xFF 0xFF 0xFF 0xFF 0x78 0x56 0x34 0x12])]
    (testing "a narrow (u16) offset and a WIDE (u32) one, at even and odd positions"
      (is (= 0x1234 (fio/u16 c 0)))
      (is (= 65535 (fio/u16 c 2)))
      (is (= 0xFF12 (fio/u16 c 1)))
      (is (= 65536 (fio/u32 c 4)) "the first offset a u16 cannot hold")
      (is (= 4294967295 (fio/u32 c 8)) "u32's top, unsigned")
      (is (= 0x12345678 (fio/u32 c 12)))
      (is (= 0x00FFFF12 (fio/u32 c 1))))
    (testing "i32 keeps the sign"
      (is (= -1 (fio/i32 c 8)))
      (is (= 65536 (fio/i32 c 4)))
      (is (= -2147483648 (fio/i32 (ch [0 0 0 0x80]) 0)))
      (is (= 2147483647 (fio/i32 (ch [0xFF 0xFF 0xFF 0x7F]) 0))))))

(deftest varints
  (testing "1, 2 and 3 bytes, at the class edges"
    (doseq [[bv v] [[[0x00] 0] [[0x01] 1] [[0x7F] 127]
                    [[0x80 0x01] 128] [[0xAC 0x02] 300] [[0xFF 0x7F] 16383]
                    [[0x80 0x80 0x01] 16384] [[0xFF 0xFF 0x7F] 2097151]
                    [[0x80 0x80 0x80 0x01] 2097152]]]
      (let [c (ch (into [0x99] (conj bv 0x55)))]
        (is (= [v (inc (count bv))] (fio/varint c 1)) (str "varint " v))
        (is (= v (fio/varint-v c 1)))
        (is (= (inc (count bv)) (fio/varint-next c 1)))
        (is (= (count bv) (fio/varint-len v)) (str "varint-len " v))))))

(deftest utf8-of-every-width
  (testing "utf-8 of 1-, 2-, 3- and 4-byte characters, at an offset"
    (let [s "aé日😀z" bs (enc/utf8-bytes s) bv (host-bv bs)
          c (ch (into [0xEE 0xDB] bv))]
      (is (= [0x61 0xC3 0xA9 0xE6 0x97 0xA5 0xF0 0x9F 0x98 0x80 0x7A] bv))
      (is (= s (fio/utf8 c 2 (count bv))))
      (is (= "a" (fio/utf8 c 2 1)))
      (is (= "é" (fio/utf8 c 3 2)))
      (is (= "日" (fio/utf8 c 5 3)))
      (is (= "😀" (fio/utf8 c 8 4)))
      (is (= "" (fio/utf8 c 2 0))))))

(deftest i64-past-2-53
  (testing "exact on every host (a long on the JVM-class hosts, a js/BigInt on ClojureScript)"
    (doseq [[bv s] [[[0x01 0 0 0 0 0 0x20 0] "9007199254740993"]
                    [[0 0 0 0 0 0 0x20 0] "9007199254740992"]
                    [[0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xDF 0xFF] "-9007199254740993"]
                    [[0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0x7F] "9223372036854775807"]
                    [[0 0 0 0 0 0 0 0x80] "-9223372036854775808"]
                    [[0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF] "-1"]
                    [[0x2A 0 0 0 0 0 0 0] "42"]]]
      (is (= s (str (fio/i64 (ch (into [0x11] bv)) 1))) s))))

(deftest floats-nan-and-negative-zero
  (testing "f64"
    (is (== 1.5 (fio/f64 (ch [0 0 0 0 0 0 0xF8 0x3F]) 0)))
    (let [z (fio/f64 (ch [0 0 0 0 0 0 0 0x80]) 0)]
      (is (negative-zero? z) "-0.0 keeps its sign"))
    (let [n (fio/f64 (ch [0 0 0 0 0 0 0xF8 0x7F]) 0)]
      (is (not (== n n)) "NaN"))
    (is (== 0.1 (fio/f64 (ch [0x9A 0x99 0x99 0x99 0x99 0x99 0xB9 0x3F]) 0)))
    (is (== ##Inf (fio/f64 (ch [0 0 0 0 0 0 0xF0 0x7F]) 0))))
  (testing "f32, widened exactly"
    (is (== 1.5 (fio/f32 (ch [0 0 0xC0 0x3F]) 0)))
    (is (== -2.25 (fio/f32 (ch [0 0 0x10 0xC0]) 0)))
    (is (== 0.10000000149011612 (fio/f32 (ch [0xCD 0xCC 0xCC 0x3D]) 0)) "0.1f, exactly")
    (let [z (fio/f32 (ch [0 0 0 0x80]) 0)]
      (is (negative-zero? z) "-0.0 keeps its sign"))
    (let [n (fio/f32 (ch [0 0 0xC0 0x7F]) 0)]
      (is (not (== n n)) "NaN"))))

;; ---------------------------------------------------------------------------
;; in place
;; ---------------------------------------------------------------------------

(deftest bytes-equal-and-compare
  (let [c (ch [9 9 1 2 3 0x80 0x7F 9])]
    (testing "bytes="
      (is (true? (fio/bytes= c 2 3 (hb [1 2 3]) 0)))
      (is (true? (fio/bytes= c 2 3 (hb [7 1 2 3]) 1)))
      (is (false? (fio/bytes= c 2 3 (hb [1 2 4]) 0)))
      (is (true? (fio/bytes= c 2 0 (hb []) 0)) "zero bytes are equal")
      (is (true? (fio/bytes= c 5 2 (hb [0x80 0x7F]) 0)) "the high bit compares as the byte it is"))
    (testing "bytes-cmp: unsigned, lexicographic, a shorter prefix first"
      (is (= 0 (fio/bytes-cmp c 2 3 (hb [1 2 3]) 0 3)))
      (is (= -1 (fio/bytes-cmp c 2 3 (hb [1 2 4]) 0 3)))
      (is (= 1 (fio/bytes-cmp c 2 3 (hb [1 2 2]) 0 3)))
      (is (= -1 (fio/bytes-cmp c 2 2 (hb [1 2 3]) 0 3)) "a prefix sorts first")
      (is (= 1 (fio/bytes-cmp c 2 3 (hb [1 2]) 0 2)))
      (is (= 1 (fio/bytes-cmp c 5 1 (hb [0x7F]) 0 1)) "0x80 > 0x7F, unsigned")
      (is (= -1 (fio/bytes-cmp c 6 1 (hb [0x80]) 0 1)))
      (is (= 0 (fio/bytes-cmp c 0 0 (hb []) 0 0)))
      (is (= 0 (fio/bytes-cmp c 3 2 (hb [5 2 3 5]) 1 2)) "from boff"))))

(defn- rep* [s n] (apply str (repeat n s)))

(deftest key-cmp-in-key-byte-order
  (let [cases [["abc" "abc"] ["abc" "abd"] ["abd" "abc"] ["ab" "abc"] ["abc" "ab"]
               ["b" "ab"] ["ab" "b"] ["é" "z"] ["z" "é"] ["" ""] ["" "a"] ["a" ""]
               ["Station-5" "Station-12"] ["Station-12" "Station-5"] ["日本" "日本"] ["😀" "日"]
               ;; the 255 / 256-byte boundary: the length class decides first
               [(rep* "a" 255) (rep* "a" 256)] [(rep* "a" 256) (rep* "a" 255)]
               [(rep* "z" 255) (rep* "a" 256)] [(rep* "a" 256) (rep* "z" 255)]
               [(rep* "a" 256) (rep* "a" 256)] [(rep* "a" 256) (str (rep* "a" 255) "b")]
               [(str (rep* "é" 127) "a") (rep* "é" 128)] [(rep* "é" 128) (str (rep* "é" 127) "a")]
               ;; past the boundary the length is four little-endian bytes, compared as bytes
               [(rep* "a" 511) (rep* "a" 512)] [(rep* "a" 512) (rep* "a" 511)]
               [(rep* "a" 300) (rep* "a" 600)] [(rep* "b" 256) (rep* "a" 257)]]]
    (doseq [[stored k] cases]
      (let [sb (enc/utf8-bytes stored) sbv (host-bv sb) n (count sbv)
            c (ch (into [0x05 0xFF] sbv))
            want (signum (d/compare-bytes (codec/encode-key stored) (codec/encode-key k)))]
        (is (= want (fio/key-cmp c 2 n k))
            (str "key-cmp " (count stored) " chars vs " (count k) " chars: " (subs stored 0 (min 12 (count stored)))
                 " / " (subs k 0 (min 12 (count k)))))))
    (testing "the answers the order implies, spelled out"
      (let [kc (fn [stored k] (let [bv (host-bv (enc/utf8-bytes stored))] (fio/key-cmp (ch bv) 0 (count bv) k)))]
        (is (= 0 (kc "abc" "abc")))
        (is (= -1 (kc "abc" "abd")))
        (is (= -1 (kc "b" "ab")) "a shorter key sorts first: the length byte precedes the bytes")
        (is (= 1 (kc "é" "z")) "é is two bytes")
        (is (= -1 (kc (rep* "z" 255) (rep* "a" 256))) "255 bytes is the short class, below every long one")
        (is (= 1 (kc (rep* "a" 256) (rep* "z" 255))))
        (is (= 1 (kc (rep* "a" 511) (rep* "a" 512))) "511 = FF 01 00 00 sorts after 512 = 00 02 00 00")))))

;; ---------------------------------------------------------------------------
;; the writer
;; ---------------------------------------------------------------------------

(defn- written [w] (bv-of (fio/buf-chunk w (fio/buf-pos w))))

(def ^:private big-i64
  "2^53 + 1, as the host's i64 (the writer takes a long, or on ClojureScript a BigInt)."
  #?(:cljs (js/BigInt "9007199254740993") :default 9007199254740993))

(deftest the-writer
  (testing "every call, at offset width 2"
    (let [w (fio/buf 2)]
      (is (= 0 (fio/buf-pos w)))
      (fio/buf-u8 w 0xAB)
      (fio/buf-u16 w 0xBEEF)
      (fio/buf-u32 w 0xDEADBEEF)
      (fio/buf-off w 0x1234)
      (fio/buf-varint w 300)
      (is (= 11 (fio/buf-pos w)))
      (fio/buf-align w 8)
      (is (= 16 (fio/buf-pos w)))
      (fio/buf-i64 w -2)
      (fio/buf-f64 w -0.0)
      (fio/buf-bytes w (hb [1 2 3 4 5]) 1 3)
      (is (= [0xAB 0xEF 0xBE 0xEF 0xBE 0xAD 0xDE 0x34 0x12 0xAC 0x02 0 0 0 0 0
              0xFE 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF
              0 0 0 0 0 0 0 0x80
              2 3 4]
             (written w)))
      (testing "patch, seek, and the position"
        (fio/buf-patch-u32 w 1 0x01020304)
        (is (= 35 (fio/buf-pos w)) "a patch moves nothing")
        (fio/buf-seek w 7)
        (fio/buf-off w 0x5678)
        (is (= 9 (fio/buf-pos w)))
        (fio/buf-seek w 35)
        (fio/buf-u8 w 0x77)
        (is (= [0xAB 0x04 0x03 0x02 0x01 0xAD 0xDE 0x78 0x56]
               (subvec (written w) 0 9)))
        (is (= 0x77 (peek (written w)))))
      (testing "buf-raw holds what is written; buf-chunk is a chunk of exactly n"
        (let [raw (host-bv (fio/buf-raw w))]
          (is (>= (count raw) 36))
          (is (= (written w) (subvec raw 0 36))))
        (is (= 5 (fio/chunk-len (fio/buf-chunk w 5))))
        (is (= [0xAB 0x04 0x03 0x02 0x01] (bv-of (fio/buf-chunk w 5)))))))
  (testing "offset width 4"
    (let [w (fio/buf 4)]
      (fio/buf-off w 0x1234)
      (fio/buf-off w 65536)
      (is (= [0x34 0x12 0 0 0 0 1 0] (written w)))))
  (testing "i64 past 2^53, f64's edges"
    (let [w (fio/buf 2)]
      (fio/buf-i64 w big-i64)
      (fio/buf-f64 w 1.5)
      (fio/buf-f64 w ##NaN)
      (let [bv (written w)]
        (is (= [0x01 0 0 0 0 0 0x20 0] (subvec bv 0 8)))
        (is (= [0 0 0 0 0 0 0xF8 0x3F] (subvec bv 8 16)))
        (is (= "9007199254740993" (str (fio/i64 (fio/buf-chunk w 24) 0))) "it reads back")
        (let [n (fio/f64 (fio/buf-chunk w 24) 16)] (is (not (== n n)) "NaN reads back NaN")))))
  (testing "varints of 1, 2 and 3 bytes, written as varint reads them"
    (let [w (fio/buf 2)]
      (doseq [v [0 127 128 16383 16384 2097151]] (fio/buf-varint w v))
      (is (= [0x00 0x7F 0x80 0x01 0xFF 0x7F 0x80 0x80 0x01 0xFF 0xFF 0x7F] (written w)))))
  (testing "growth past the first buffer keeps every byte"
    (let [w (fio/buf 4)]
      (dotimes [i 5000] (fio/buf-u8 w (rem i 251)))
      (fio/buf-bytes w (hb (vec (range 200))) 0 200)
      (is (= 5200 (fio/buf-pos w)))
      (is (= (into (mapv (fn [i] (rem i 251)) (range 5000)) (range 200)) (written w))))))

;; ---------------------------------------------------------------------------
;; tuples and columns
;; ---------------------------------------------------------------------------

(defn- elems "A tuple's elements, i64 / u64 as decimals and f32 as doubles." [t kind]
  (mapv (fn [x] (case (int kind) (10 11) (str x) 8 (double x) x)) t))

(deftest tuples-of-every-dense-kind
  (let [rows [[1 [0x00 0x7F 0x80 0xFF] [0 127 128 255]]
              [2 [0x00 0x7F 0x80 0xFF] [0 127 -128 -1]]
              [4 [0x00 0x00 0xFF 0x7F 0x00 0x80 0xFF 0xFF] [0 32767 -32768 -1]]
              [5 [0x00 0x00 0xFF 0x7F 0x00 0x80 0xFF 0xFF] [0 32767 32768 65535]]
              [6 [0 0 0 0 0xFF 0xFF 0xFF 0x7F 0 0 0 0x80 0xFF 0xFF 0xFF 0xFF] [0 2147483647 -2147483648 -1]]
              [7 [0 0 0 0 0xFF 0xFF 0xFF 0x7F 0 0 0 0x80 0xFF 0xFF 0xFF 0xFF] [0 2147483647 2147483648 4294967295]]
              [8 [0 0 0xC0 0x3F 0 0 0x10 0xC0 0xCD 0xCC 0xCC 0x3D] [1.5 -2.25 0.10000000149011612]]
              [9 [0 0 0 0 0 0 0xF8 0x3F 0x9A 0x99 0x99 0x99 0x99 0x99 0xB9 0x3F] [1.5 0.1]]
              [10 [0x01 0 0 0 0 0 0x20 0 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF] ["9007199254740993" "-1"]]
              [11 [0x01 0 0 0 0 0 0x20 0 0x2A 0 0 0 0 0 0 0] ["9007199254740993" "42"]]]]
    (doseq [[kind bv want] rows]
      (let [size (quot (count bv) (count want))
            c (ch (into [0xAA 0xBB 0xCC 0xDD 0xEE 0xFF 0x11 0x22] bv))
            t (fio/tuple c 8 (count want) kind)
            k (fio/col c 8 (count want) kind)]
        (testing (str "kind " kind ", " size "-byte elements")
          (is (= want (elems t kind)) "the elements")
          (is (= want (elems k kind)) "col reads the same")
          (is (= (count want) (count t)))
          (is (vector? t) "the host's vector")
          (is (= want (elems (seq t) kind)) "seq")
          (is (= (peek want) (first (elems [(nth t (dec (count want)))] kind))) "nth")
          (is (= want (elems (reduce conj [] t) kind)) "reduce"))))
    (testing "u64 past 2^63: the host's answer (two's complement on the JVM-class hosts, exact on ClojureScript)"
      (is (= #?(:cljs ["18446744073709551615"] :default ["-1"])
             (elems (fio/tuple (ch [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF]) 0 1 11) 11))))
    (testing "a tuple compares, hashes and prints as the host's vector of its elements"
      (let [t (fio/tuple (ch [1 0 0 0 2 0 0 0 3 0 0 0]) 0 3 6)]
        (is (= [1 2 3] t))
        (is (= (hash [1 2 3]) (hash t)))
        (is (= "[1 2 3]" (pr-str t)))
        (is (= 6 (reduce + t)))
        (is (= [1 2 3 4] (conj t 4)) "a write answers the host's own vector")
        (is (= [9 2 3] (assoc t 0 9)))))
    (testing "an empty tuple"
      (is (= [] (fio/tuple (ch []) 0 0 6))))))
