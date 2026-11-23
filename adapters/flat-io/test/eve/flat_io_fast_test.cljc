(ns eve.flat-io-fast-test
  "Lane U2b's contract test for eve.flat-io (doc/flat/FLAT-IO.md): every contract function of whichever root is on
   the classpath — flat-io/jvm on the JVM, flat-io/js on Node — against a reference written here from the
   contract's text (little-endian reads assembled byte by byte, unsigned LEB128, eve's key-byte order built as bytes,
   the writer as a byte model with the reference's growth), on random inputs from a seeded generator and the edge
   rows (WIDE and narrow offsets, varints at 1 / 2 / 3 / … bytes, UTF-8 of 1–4-byte characters, i64 past 2^53,
   NaN / -0.0 floats, key-cmp across the 255 / 256-byte boundary). key-cmp is also held to eve.codec/encode-key's
   bytes under eve.deftype-proto.data/compare-bytes, the order a KMAP's keys are written in. To be folded into U2a's
   conformance test (adapters/flat-io/test) at collation."
  (:require [eve.flat-io :as fio]
            [eve.codec :as codec]
            [eve.deftype-proto.data :as d]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]]) [clojure.string]))

;; ---------------------------------------------------------------------------
;; host bytes and a generator that answers the same on every host
;; ---------------------------------------------------------------------------

(defn- ba [n] #?(:clj (byte-array (int n)) :cljs (js/Uint8Array. n)))
(defn- ba-len [b] #?(:clj (alength ^bytes b) :cljs (.-length b)))
(defn- ba-get [b i] #?(:clj (bit-and (long (aget ^bytes b (int i))) 0xFF) :cljs (aget b i)))
(defn- ba-set! [b i v] #?(:clj (aset ^bytes b (int i) (unchecked-byte v)) :cljs (aset b i (bit-and v 0xFF))))
(defn- ba-of [xs] (let [b (ba (count xs))] (dotimes [i (count xs)] (ba-set! b i (nth xs i))) b))
(defn- ba-vec [b] (mapv #(ba-get b %) (range (ba-len b))))

(defn- rng
  "Park–Miller minimal standard: every product is below 2^47, exact on a long
   and on a double, so the sequence is the same on every host."
  [seed] (atom (long seed)))
(defn- next! [r] (swap! r (fn [x] (mod (* x 48271) 2147483647))))
(defn- rnd [r n] (mod (next! r) n))

(defn- random-bytes [r n] (let [b (ba n)] (dotimes [i n] (ba-set! b i (rnd r 256))) b))

;; ---------------------------------------------------------------------------
;; the reference — the contract's text, byte by byte
;; ---------------------------------------------------------------------------

(defn- r-u8 [b i] (ba-get b i))
(defn- r-u16 [b i] (+ (r-u8 b i) (* 256 (r-u8 b (inc i)))))
(defn- r-u32 [b i] (+ (r-u16 b i) (* 65536 (r-u16 b (+ i 2)))))
(defn- r-i32 [b i] (let [u (r-u32 b i)] (if (>= u 2147483648) (- u 4294967296) u)))
(defn- r-i16 [b i] (let [u (r-u16 b i)] (if (>= u 32768) (- u 65536) u)))
(defn- r-i8 [b i] (let [u (r-u8 b i)] (if (>= u 128) (- u 256) u)))

#?(:clj
   (defn- r-i64 ^long [b i]
     (.longValue ^BigInteger (reduce (fn [^BigInteger acc k]
                                       (.or acc (.shiftLeft (BigInteger/valueOf (r-u8 b (+ i k))) (int (* 8 k)))))
                                     BigInteger/ZERO (range 8))))
   :cljs
   (defn- r-i64 [b i]
     (.asIntN js/BigInt 64
              (reduce (fn [acc k] (+ acc (* (js/BigInt (r-u8 b (+ i k))) (js/BigInt (.pow js/Math 256 k)))))
                      (js/BigInt 0) (range 8)))))

#?(:cljs
   (defn- copy-at [b i n]
     (let [out (js/Uint8Array. n)] (dotimes [k n] (aset out k (aget b (+ i k)))) out)))

(defn- r-f64 [b i]
  #?(:clj (Double/longBitsToDouble (r-i64 b i))
     :cljs (aget (js/Float64Array. (.-buffer (copy-at b i 8))) 0)))
(defn- r-f32 [b i]
  #?(:clj (double (Float/intBitsToFloat (unchecked-int (r-u32 b i))))
     :cljs (aget (js/Float32Array. (.-buffer (copy-at b i 4))) 0)))

(defn- r-varint
  "[value next], unsigned LEB128: the sum of 7-bit groups — exact (a BigInteger
   on the JVM, kept to its low 64 bits as a long holds them; on JavaScript the
   values tested stay below 2^53)."
  [b i]
  (loop [i i k 0 v #?(:clj BigInteger/ZERO :cljs 0)]
    (let [x (r-u8 b i)
          v #?(:clj (.or ^BigInteger v (.shiftLeft (BigInteger/valueOf (bit-and x 127)) (int (* 7 k))))
               :cljs (+ v (* (bit-and x 127) (.pow js/Math 128 k))))]
      (if (< x 128)
        [#?(:clj (.longValue ^BigInteger v) :cljs v) (inc i)]
        (recur (inc i) (inc k) v)))))

(defn- leb128
  "The unsigned LEB128 bytes of a non-negative v."
  [v]
  #?(:clj (loop [v (biginteger v) out []]
            (if (neg? (.compareTo v (BigInteger/valueOf 128)))
              (conj out (.intValue ^BigInteger v))
              (recur (.shiftRight ^BigInteger v 7) (conj out (+ 128 (.intValue (.and ^BigInteger v (BigInteger/valueOf 127))))))))
     :cljs (loop [v v out []]
             (if (< v 128) (conj out v)
                 (recur (js/Math.floor (/ v 128)) (conj out (+ 128 (mod v 128))))))))

(defn- host-utf8 [s] #?(:clj (.getBytes ^String s "UTF-8") :cljs (.encode (js/TextEncoder.) s)))
(defn- units [s] (mapv #(#?(:clj int :cljs identity) (#?(:clj .charAt :cljs .charCodeAt) s %)) (range (count s))))
(defn- from-units [us] #?(:clj (String. (char-array (map char us))) :cljs (clojure.string/join (map #(.fromCharCode js/String %) us))))

#?(:cljs (defn- type-tag [x] (.call (.-toString (.-prototype js/Object)) x)))

(defn- signum [x] (cond (neg? x) -1 (pos? x) 1 :else 0))

(defn- cmp-vec
  "Unsigned lexicographic compare of two vectors of byte values, a shorter one
   first on a common prefix."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (< i n)
        (let [x (nth a i) y (nth b i)] (if (== x y) (recur (inc i)) (if (< x y) -1 1)))
        (signum (- (count a) (count b)))))))

(defn- key-bytes-of
  "Eve's key bytes for a string of UTF-8 bytes u (FLAT-IO.md, key-cmp): [0x05][len u8][bytes] below 256 bytes,
   else [0x06][len u32 LE][bytes]."
  [u]
  (let [n (count u)]
    (if (< n 256)
      (into [5 n] u)
      (into [6 (mod n 256) (mod (quot n 256) 256) (mod (quot n 65536) 256) (quot n 16777216)] u))))

(defn- r-key-cmp [b off n k]
  (cmp-vec (key-bytes-of (mapv #(r-u8 b %) (range off (+ off n))))
           (key-bytes-of (ba-vec (host-utf8 k)))))

(defn- codec-key-cmp
  "The same order as eve.codec writes it: encode-key of the stored string (when its bytes decode and re-encode to
   themselves) against encode-key of k."
  [b off n k]
  (let [s (fio/utf8 (fio/chunk b) off n)]
    (when (= (ba-vec (host-utf8 s)) (mapv #(r-u8 b %) (range off (+ off n))))
      (signum (d/compare-bytes (codec/encode-key s) (codec/encode-key k))))))

;; numbers compared as what they are: NaN = NaN, -0.0 ≠ 0.0, a Float ≠ a Double
(defn- same? [a b]
  #?(:clj (cond (and (instance? Double a) (instance? Double b))
                (== (Double/doubleToRawLongBits a) (Double/doubleToRawLongBits b))
                (and (instance? Float a) (instance? Float b))
                (== (Float/floatToRawIntBits a) (Float/floatToRawIntBits b))
                :else (and (= (class a) (class b)) (= a b)))
     :cljs (if (and (number? a) (number? b))
             (.is js/Object a b)
             (and (= (type-tag a) (type-tag b)) (= a b)))))

;; ---------------------------------------------------------------------------
;; reads
;; ---------------------------------------------------------------------------

(deftest chunk-surface
  (let [b (ba-of [1 2 3 4 5]) c (fio/chunk b)]
    (is (identical? b (fio/chunk-bytes c)))
    (is (= 5 (fio/chunk-len c)))
    #?(:clj (is (identical? b c) "on the JVM a chunk is its byte array"))))

(defn- check-reads-at [b c i]
  (let [n (ba-len b)]
    (is (== (r-u8 b i) (fio/u8 c i)))
    (when (<= (+ i 2) n) (is (== (r-u16 b i) (fio/u16 c i))))
    (when (<= (+ i 4) n)
      (is (== (r-u32 b i) (fio/u32 c i)))
      (is (== (r-i32 b i) (fio/i32 c i)))
      (is (same? (r-f32 b i) (fio/f32 c i)) (str "f32 at " i)))
    (when (<= (+ i 8) n)
      (is (= (r-i64 b i) (fio/i64 c i)) (str "i64 at " i))
      #?(:cljs (is (= "[object BigInt]" (type-tag (fio/i64 c i))) "an i64 is a js/BigInt"))
      (is (same? (r-f64 b i) (fio/f64 c i)) (str "f64 at " i)))))

(deftest reads-random
  (let [r (rng 12345)]
    (dotimes [_ 20]
      (let [n (+ 8 (rnd r 600)) b (random-bytes r n) c (fio/chunk b)]
        (dotimes [i n] (check-reads-at b c i))))))

(defn- put-le! [b i v nbytes]
  ;; v a non-negative integer below 2^(8 nbytes), or (JVM) a long whose bytes are written two's complement
  (dotimes [k nbytes]
    (ba-set! b (+ i k) #?(:clj (bit-and (unsigned-bit-shift-right (long v) (* 8 k)) 0xFF)
                          :cljs (mod (js/Math.floor (/ v (.pow js/Math 256 k))) 256)))))

(deftest reads-edges
  (testing "integer boundaries at aligned and unaligned offsets, and at the chunk's last bytes"
    (doseq [v [0 1 0x7F 0x80 0xFF 0x7FFF 0x8000 0xFFFF 0x7FFFFFFF 0x80000000 0xFFFFFFFF]
            at [0 1 3 7 (- 16 4)]]
      (let [b (ba 16)]
        (put-le! b at v 4)
        (check-reads-at b (fio/chunk b) at))))
  (testing "i64 past 2^53, the extremes, and -1"
    (doseq [bs [[0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0x1F 0x00]      ;; 2^53 - 1
                [0x00 0x00 0x00 0x00 0x00 0x00 0x20 0x00]      ;; 2^53
                [0x01 0x00 0x00 0x00 0x00 0x00 0x20 0x00]      ;; 2^53 + 1
                [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0x7F]      ;; Long/MAX_VALUE
                [0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x80]      ;; Long/MIN_VALUE
                [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF]      ;; -1
                [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xDF 0xFF]]     ;; -(2^53) - 1
            at [0 3]]
      (let [b (ba 12)]
        (dotimes [k 8] (ba-set! b (+ at k) (nth bs k)))
        (check-reads-at b (fio/chunk b) at)))
    (let [b (ba-of [0x01 0x00 0x00 0x00 0x00 0x00 0x20 0x00])]
      (is (= #?(:clj 9007199254740993 :cljs (js/BigInt "9007199254740993")) (fio/i64 (fio/chunk b) 0)))))
  (testing "floats: NaN, -0.0, infinities, denormals"
    (doseq [bs [[0 0 0 0 0 0 0xF8 0x7F] [0 0 0 0 0 0 0 0x80] [0 0 0 0 0 0 0xF0 0x7F] [0 0 0 0 0 0 0xF0 0xFF]
                [1 0 0 0 0 0 0 0] [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xEF 0x7F] [0 0 0 0 0 0 0xF8 0x3F]
                [0 0 0xC0 0x7F 0 0 0 0] [0 0 0 0x80 0 0 0 0] [0 0 0x80 0x7F 0 0 0 0] [1 0 0 0 0 0 0 0]]
            at [0 1]]
      (let [b (ba 10)]
        (dotimes [k 8] (ba-set! b (+ at k) (nth bs k)))
        (check-reads-at b (fio/chunk b) at)))
    (let [c (fio/chunk (ba-of [0 0 0 0 0 0 0 0x80]))]
      (is (same? -0.0 (fio/f64 c 0)))
      (is (#?(:clj Double/isNaN :cljs js/isNaN) (fio/f64 (fio/chunk (ba-of [0 0 0 0 0 0 0xF8 0x7F])) 0))))))

;; ---------------------------------------------------------------------------
;; varints
;; ---------------------------------------------------------------------------

(def ^:private varint-values
  (concat [0 1 127 128 255 256 16383 16384 2097151 2097152 268435455 268435456 2147483647 2147483648
           4294967295 4294967296 34359738367 34359738368 9007199254740991]
          #?(:clj [9007199254740992 Long/MAX_VALUE] :cljs [])))

(deftest varints
  (testing "1, 2, 3, … byte varints, at every boundary, at offsets 0 and 5"
    (doseq [v varint-values at [0 5]]
      (let [enc (leb128 v)
            b (ba (+ at (count enc) 3))]
        (dotimes [k (count enc)] (ba-set! b (+ at k) (nth enc k)))
        (let [c (fio/chunk b) want (r-varint b at)]
          (is (= want [v (+ at (count enc))]) (str "reference reads its own encoding of " v))
          (is (= want (fio/varint c at)) (str "varint " v))
          (is (== (first want) (fio/varint-v c at)))
          (is (== (second want) (fio/varint-next c at)))
          (is (== (count enc) (fio/varint-len v)) (str "varint-len " v))))))
  #?(:clj
     (testing "a ten-byte varint (2^64 - 1) reads as the long -1, as the reference's shift-or does"
       (let [b (ba-of [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0x01])]
         (is (= [-1 10] (fio/varint (fio/chunk b) 0)))
         (is (== -1 (fio/varint-v (fio/chunk b) 0))))))
  (testing "a negative v counts as one byte (the reference's signed compare)"
    (is (== 1 (fio/varint-len -1))))
  (testing "random varints in a random stream"
    (let [r (rng 777)]
      (dotimes [_ 200]
        (let [v (case (rnd r 4) 0 (rnd r 128) 1 (rnd r 16384) 2 (rnd r 2147483647)
                      3 (+ (* (rnd r 2097152) 4294967296) (rnd r 2147483647)))
              enc (leb128 v)
              b (ba (inc (count enc)))]
          (dotimes [k (count enc)] (ba-set! b (inc k) (nth enc k)))
          (is (= [v (inc (count enc))] (fio/varint (fio/chunk b) 1))))))))

;; ---------------------------------------------------------------------------
;; utf8
;; ---------------------------------------------------------------------------

(def ^:private utf8-fixture
  "[bytes, the JDK 21 decoding (String(b, 0, n, UTF_8)) as UTF-16 code units]: the JVM's answer, which flat-io/js
   answers too except on the rows in utf8-js-divergence"
  [[[] []]
   [[65] [65]]
   [[239 187 191 65] [65279 65]]
   [[239 187 191] [65279]]
   [[195 169] [233]]
   [[226 130 172] [8364]]
   [[240 159 152 128] [55357 56832]]
   [[128] [65533]]
   [[191 65] [65533 65]]
   [[192 128] [65533 65533]]
   [[193 191] [65533 65533]]
   [[194] [65533]]
   [[194 65] [65533 65]]
   [[224 128 128] [65533 65533 65533]]
   [[224 160] [65533]]
   [[224 160 65] [65533 65]]
   [[237 160 128] [65533]]
   [[237 191 191] [65533]]
   [[237 159 191] [55295]]
   [[238 128 128] [57344]]
   [[240 128 128 128] [65533 65533 65533 65533]]
   [[240 144 128] [65533]]
   [[240 144 128 65] [65533 65]]
   [[244 143 191 191] [56319 57343]]
   [[244 144 128 128] [65533 65533 65533 65533]]
   [[245 128 128 128] [65533 65533 65533 65533]]
   [[255] [65533]]
   [[254 255] [65533 65533]]
   [[65 195] [65 65533]]
   [[226 130] [65533]]
   [[226 130 172 226] [8364 65533]]
   [[240 159 152] [65533]]
   [[248 136 128 128 128] [65533 65533 65533 65533 65533]]
   [[0 127] [0 127]]
   [[195 169 239 187 191] [233 65279]]
   [[237 160 128 237 176 128] [65533 65533]]
   [[225 128 65 66] [65533 65 66]]])

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :cljs pass (the expected-answers lookup in the divergence deftest); the :clj pass reads the JDK answers directly
(def ^:private utf8-js-divergence
  "Malformed input only: a surrogate code point in three-byte form (ED A0..BF xx). The JDK's decoder replaces the
   three bytes with one U+FFFD; the WHATWG decoder (TextDecoder, every JavaScript host) replaces each byte. Measured
   2026-11-17 (Node 22.23.4, JDK 21.0.12.1); TextDecoder has no option for it."
  {[237 160 128] [65533 65533 65533]
   [237 191 191] [65533 65533 65533]
   [237 160 128 237 176 128] [65533 65533 65533 65533 65533 65533]})

(deftest utf8-decoding
  (testing "the fixture: valid UTF-8 of 1-4-byte characters, a leading U+FEFF kept, malformed input"
    (doseq [[bs want] utf8-fixture]
      (let [want #?(:clj want :cljs (get utf8-js-divergence bs want))
            b (ba (+ 3 (count bs)))]
        (dotimes [k (count bs)] (ba-set! b (+ 3 k) (nth bs k)))
        (is (= want (units (fio/utf8 (fio/chunk b) 3 (count bs)))) (str "utf8 of " bs)))))
  (testing "strings round-trip through their UTF-8 bytes, across the short-ASCII path's bound"
    (doseq [s ["" "a" "Station-123" (clojure.string/join (repeat 32 "x")) (clojure.string/join (repeat 33 "y"))
               "é" "€uro" "😀" "a😀b€c" (clojure.string/join (repeat 300 "z")) (str (from-units [0xFEFF]) "bom")
               (clojure.string/join (repeat 40 "ü"))]]
      (let [u (host-utf8 s) b (ba (+ 2 (ba-len u)))]
        (dotimes [k (ba-len u)] (ba-set! b (+ 2 k) (ba-get u k)))
        (is (= s (fio/utf8 (fio/chunk b) 2 (ba-len u))) (str "utf8 round trip of " (pr-str s)))))))

;; ---------------------------------------------------------------------------
;; in place: bytes=, bytes-cmp, key-cmp
;; ---------------------------------------------------------------------------

(deftest bytes-compares
  (let [r (rng 4242)]
    (dotimes [_ 400]
      (let [n (rnd r 12) bn (rnd r 12)
            c-bytes (random-bytes r 32)
            bs (random-bytes r 32)
            off (rnd r 16) boff (rnd r 16)]
        ;; half the time make the two ranges share a prefix (or be equal)
        (when (even? (rnd r 2))
          (dotimes [k (min n bn (rnd r 13))] (ba-set! bs (+ boff k) (ba-get c-bytes (+ off k)))))
        (let [c (fio/chunk c-bytes)
              sa (mapv #(ba-get c-bytes %) (range off (+ off n)))
              sb (mapv #(ba-get bs %) (range boff (+ boff bn)))]
          (is (= (= sa (mapv #(ba-get bs %) (range boff (+ boff n)))) (fio/bytes= c off n bs boff))
              (str "bytes= " sa " " sb))
          (is (= (cmp-vec sa sb) (fio/bytes-cmp c off n bs boff bn)) (str "bytes-cmp " sa " " sb))))))
  (testing "edges: empty, unsigned order (0x80 above 0x7F), a prefix first"
    (let [c (fio/chunk (ba-of [0x80 0x7F 1 2]))]
      (is (true? (fio/bytes= c 0 0 (ba-of []) 0)))
      (is (zero? (fio/bytes-cmp c 0 0 (ba-of []) 0 0)))
      (is (= 1 (fio/bytes-cmp c 0 1 (ba-of [0x7F]) 0 1)))
      (is (= -1 (fio/bytes-cmp c 1 1 (ba-of [0x80]) 0 1)))
      (is (= -1 (fio/bytes-cmp c 2 1 (ba-of [1 2]) 0 2)))
      (is (= 1 (fio/bytes-cmp c 2 2 (ba-of [1]) 0 1)))
      (is (true? (fio/bytes= c 2 2 (ba-of [9 1 2]) 1)))
      (is (false? (fio/bytes= c 2 2 (ba-of [9 1]) 1)) "bs shorter than n from boff: false, as portable answers")
      (is (false? (fio/bytes= c 2 2 (ba-of [9 1 3]) 1))))))

(defn- stored
  "A chunk with the UTF-8 bytes of string (or byte vector) s at offset 3: [chunk host-bytes off n]."
  [s]
  (let [u (if (string? s) (ba-vec (host-utf8 s)) s)
        b (ba (+ 3 (count u) 2))]
    (dotimes [k (count u)] (ba-set! b (+ 3 k) (nth u k)))
    [(fio/chunk b) b 3 (count u)]))

(defn- check-key-cmp [s k]
  (let [[c b off n] (stored s)
        want (r-key-cmp b off n k)
        got (fio/key-cmp c off n k)]
    (is (= want got) (str "key-cmp " (pr-str s) " vs " (pr-str k)))
    (when-let [cw (codec-key-cmp b off n k)]
      (is (= cw got) (str "key-cmp against encode-key, " (pr-str s) " vs " (pr-str k))))))

(deftest key-cmp-order
  (testing "the length class, the length, the bytes"
    (doseq [[s k] [["" ""] ["a" ""] ["" "a"] ["ab" "abc"] ["abc" "ab"] ["abc" "abd"] ["abd" "abc"]
                   ["b" "aa"] ["Station-10" "Station-9"] ["é" "e"] ["€" "é"] ["😀" "€a"] ["a😀" "a😀"]
                   ["Ω" "Ω"] [(clojure.string/join (repeat 255 "a")) (clojure.string/join (repeat 256 "a"))]
                   [(clojure.string/join (repeat 256 "a")) (clojure.string/join (repeat 255 "a"))]
                   [(clojure.string/join (repeat 256 "a")) (clojure.string/join (repeat 256 "a"))]
                   [(clojure.string/join (repeat 256 "a")) (str (clojure.string/join (repeat 255 "a")) "b")]
                   [(clojure.string/join (repeat 255 "b")) (clojure.string/join (repeat 300 "a"))]]]
      (check-key-cmp s k)))
  (testing "past 256 bytes the u32 length compares little-endian, byte by byte (257 above 512)"
    (let [[c _ off n] (stored (clojure.string/join (repeat 257 "a")))]
      (is (= 1 (fio/key-cmp c off n (clojure.string/join (repeat 512 "a")))))
      (check-key-cmp (clojure.string/join (repeat 257 "a")) (clojure.string/join (repeat 512 "a")))
      (check-key-cmp (clojure.string/join (repeat 512 "q")) (clojure.string/join (repeat 257 "a")))
      (check-key-cmp (clojure.string/join (repeat 300 "a")) (clojure.string/join (repeat 556 "a")))))
  (testing "a host string with lone surrogates encodes as the host's encoder does"
    (doseq [k [(from-units [0xD800]) (from-units [0xDC00]) (from-units [0x61 0xD83D]) (from-units [0xD83D 0x61])
               (from-units [0xDE00 0xD83D]) (from-units [0xD83D 0xDE00])]]
      (doseq [s ["?" "a?" "?a" "�" "a�" "�a" "😀" ""]]
        (check-key-cmp s k))))
  (testing "stored bytes that are not UTF-8 compare as bytes"
    (doseq [u [[0xFF] [0x80 0x80] [0xC3] [0xED 0xA0 0x80]]]
      (doseq [k ["a" "é" "�" (from-units [0xD800])]]
        (check-key-cmp u k))))
  (testing "random keys over a small alphabet of 1-4-byte characters"
    (let [r (rng 99)
          alphabet ["a" "b" "z" "0" "é" "ß" "€" "Ω" "😀" "𝄞"]]
      (dotimes [_ 600]
        (let [mk (fn [] (clojure.string/join (repeatedly (rnd r 6) #(nth alphabet (rnd r (count alphabet))))))
              s (mk)
              k (if (zero? (rnd r 4)) s (mk))]
          (check-key-cmp s k)))))
  (testing "a KMAP-style binary search over 407 keys answers every key"
    (let [ks (sort-by identity (fn [a b] (cmp-vec (key-bytes-of (ba-vec (host-utf8 a))) (key-bytes-of (ba-vec (host-utf8 b)))))
                      (map #(str "Station-" %) (range 407)))
          spans (reduce (fn [acc k] (conj acc [(+ (or (some-> (peek acc) (#(apply + %))) 0)) (ba-len (host-utf8 k))]))
                        [] ks)
          total (apply + (map second spans))
          b (ba total)
          _ (doseq [[[o n] k] (map vector spans ks)]
              (let [u (host-utf8 k)] (dotimes [i n] (ba-set! b (+ o i) (ba-get u i)))))
          c (fio/chunk b)
          find (fn [k] (loop [lo 0 hi (dec (count spans))]
                         (when (<= lo hi)
                           (let [mid (quot (+ lo hi) 2) [o n] (nth spans mid) x (fio/key-cmp c o n k)]
                             (cond (zero? x) mid (neg? x) (recur (inc mid) hi) :else (recur lo (dec mid)))))))]
      (is (= (range 407) (map find ks)))
      (is (nil? (find "Station-407"))))))

;; ---------------------------------------------------------------------------
;; the writer, against a byte model with the reference's growth
;; ---------------------------------------------------------------------------

(def ^:private init-cap #?(:clj 256 :cljs 1024))

(defn- m-put [m bs]
  (let [{:keys [pos cap]} m
        need (+ pos (count bs))
        cap (if (> need cap) (max need (* 2 cap)) cap)]
    (-> m (assoc :cap cap :pos need)
        (update :bytes (fn [v] (reduce (fn [v [k x]] (assoc v (+ pos k) x))
                                       (into v (repeat (max 0 (- need (count v))) 0))
                                       (map-indexed vector bs)))))))

(defn- le-bytes [v nbytes]
  #?(:clj (mapv #(bit-and (unsigned-bit-shift-right (long v) (* 8 %)) 0xFF) (range nbytes))
     :cljs (if (== nbytes 8)
             (let [u (.asUintN js/BigInt 64 (js/BigInt v))]
               (mapv #(js/Number (.asUintN js/BigInt 8 (/ u (js/BigInt (.pow js/Math 256 %))))) (range 8)))
             (mapv #(mod (js/Math.floor (/ (mod v 4294967296) (.pow js/Math 256 %))) 256) (range nbytes)))))

(defn- f64-bytes [x]
  #?(:clj (le-bytes (Double/doubleToRawLongBits x) 8)
     :cljs (vec (js/Array.from (js/Uint8Array. (.-buffer (js/Float64Array. #js [x])))))))
(defn- f32-bytes [x]
  #?(:clj (le-bytes (Float/floatToRawIntBits (float x)) 4)
     :cljs (vec (js/Array.from (js/Uint8Array. (.-buffer (js/Float32Array. #js [x])))))))

(defn- run-op!
  "Apply op to writer w and to model m; answer the new model."
  [w m [op a b]]
  (case op
    :u8 (do (fio/buf-u8 w a) (m-put m [(mod a 256)]))
    :u16 (do (fio/buf-u16 w a) (m-put m (le-bytes a 2)))
    :u32 (do (fio/buf-u32 w a) (m-put m (le-bytes a 4)))
    :i64 (do (fio/buf-i64 w a) (m-put m (le-bytes a 8)))
    :f64 (do (fio/buf-f64 w a) (m-put m (f64-bytes a)))
    :f32 (do (fio/buf-f32 w a) (m-put m (f32-bytes a)))
    :varint (do (fio/buf-varint w a) (m-put m (leb128 a)))
    :off (do (fio/buf-off w a) (m-put m (le-bytes a (:width m))))
    :bytes (let [src (ba-of a)] (fio/buf-bytes w src b (- (count a) b)) (m-put m (subvec a b)))
    :align (do (fio/buf-align w a)
               (let [r (rem (:pos m) a)] (if (zero? r) m (m-put m (vec (repeat (- a r) 0))))))
    :seek (do (fio/buf-seek w a) (assoc m :pos a))
    :patch (do (fio/buf-patch-u32 w a b)
               (update m :bytes (fn [v] (reduce (fn [v [k x]] (assoc v (+ a k) x)) v (map-indexed vector (le-bytes b 4))))))))

(defn- random-op [r m]
  (let [pos (:pos m) hi (count (:bytes m))]
    (case (rnd r 13)
      0 [:u8 (rnd r 256)]
      1 [:u16 (rnd r 65536)]
      2 [:u32 (+ (* (rnd r 2) 2147483648) (rnd r 2147483647))]
      3 [:i64 #?(:clj (- (unchecked-multiply (unchecked-multiply (long (rnd r 2147483647)) (long (rnd r 2147483647))) 4) (rnd r 1000))
                 :cljs (- (* (rnd r 2097152) 4294967296) (rnd r 2147483647)))]
      4 [:f64 (/ (- (rnd r 2000001) 1000000) 7.0)]
      5 [:f32 (/ (rnd r 1000) 4.0)]
      6 [:varint (case (rnd r 3) 0 (rnd r 128) 1 (rnd r 2097152) 2 (* (rnd r 2147483647) 3))]
      7 [:off (rnd r (if (== 2 (:width m)) 65536 2147483647))]
      8 (let [n (rnd r (if (zero? (rnd r 8)) 700 20)) xs (vec (repeatedly n #(rnd r 256)))]
          [:bytes xs (if (pos? n) (rnd r n) 0)])
      9 [:align (nth [1 2 4 8] (rnd r 4))]
      10 (if (pos? hi) [:seek (rnd r (inc hi))] [:u8 1])
      11 (if (>= hi 4) [:patch (rnd r (- hi 3)) (rnd r 2147483647)] [:u8 2])
      12 (if (< pos hi) [:seek hi] [:u8 3]))))

(deftest writer-against-model
  (doseq [seed (range 1 41) width [2 4]]
    (let [r (rng (* seed 31))
          w (fio/buf width)
          m (loop [k 0 m {:bytes [] :pos 0 :cap init-cap :width width}]
              (if (< k 150)
                (recur (inc k) (run-op! w m (random-op r m)))
                m))
          raw (fio/buf-raw w)
          hi (count (:bytes m))]
      (is (== (:pos m) (fio/buf-pos w)) (str "pos, seed " seed))
      (is (== (:cap m) (ba-len raw)) (str "buf-raw's length is the reference's capacity, seed " seed))
      (is (= (:bytes m) (subvec (ba-vec raw) 0 hi)) (str "bytes, seed " seed))
      (is (every? zero? (subvec (ba-vec raw) hi)) "past the written bytes, zeros")
      (let [ch (fio/buf-chunk w hi)]
        (is (= (:bytes m) (ba-vec (fio/chunk-bytes ch))))
        (is (== hi (fio/chunk-len ch))))
      (let [ch (fio/buf-chunk w (+ 5 (ba-len raw)))]
        (is (= (into (ba-vec raw) [0 0 0 0 0]) (ba-vec (fio/chunk-bytes ch))) "buf-chunk past the end zero-pads")))))

(deftest writer-edges
  (testing "the header shape chunk.cljc writes: zeros, a seek back, the magic, patches"
    (let [w (fio/buf 2)]
      (dotimes [_ 72] (fio/buf-u8 w 0))
      (fio/buf-varint w 300)
      (fio/buf-align w 8)
      (is (== 80 (fio/buf-pos w)))
      (fio/buf-seek w 0)
      (fio/buf-bytes w (ba-of [0x45 0x56 0x53 0x31]) 0 4)
      (fio/buf-u8 w 1) (fio/buf-u8 w 0) (fio/buf-u16 w 3) (fio/buf-u32 w 80)
      (fio/buf-patch-u32 w 12 0xDEADBEEF)
      (is (= [0x45 0x56 0x53 0x31 1 0 3 0 80 0 0 0 0xEF 0xBE 0xAD 0xDE 0 0]
             (subvec (ba-vec (fio/buf-raw w)) 0 18)))
      (is (= [0xAC 0x02 0 0 0 0 0 0] (subvec (ba-vec (fio/buf-raw w)) 72 80)))))
  (testing "align writes zeros over old bytes after a seek back"
    (let [w (fio/buf 4)]
      (dotimes [_ 16] (fio/buf-u8 w 0xEE))
      (fio/buf-seek w 3)
      (fio/buf-align w 8)
      (is (== 8 (fio/buf-pos w)))
      (is (= [0xEE 0xEE 0xEE 0 0 0 0 0 0xEE] (subvec (ba-vec (fio/buf-raw w)) 0 9)))))
  (testing "u32 / off keep the low 32 bits; i64 writes negatives two's complement; f64 NaN and -0.0 bits"
    (let [w (fio/buf 4)]
      (fio/buf-u32 w 0xFFFFFFFF) (fio/buf-off w 0x12345678) (fio/buf-i64 w -2)
      (fio/buf-f64 w -0.0)
      (is (= (concat [0xFF 0xFF 0xFF 0xFF 0x78 0x56 0x34 0x12 0xFE] (repeat 7 0xFF) [0 0 0 0 0 0 0 0x80])
             (subvec (ba-vec (fio/buf-raw w)) 0 24))))))

;; ---------------------------------------------------------------------------
;; tuples and columns
;; ---------------------------------------------------------------------------

(def ^:private kind-size {1 1 2 1 3 1 4 2 5 2 6 4 7 4 8 4 9 8 10 8 11 8})

(defn- r-elem
  "Element i of a kind column at off, as flat-io/portable's tuple answers it: integers a long on the JVM (a u64 past
   2^63 its two's complement), a number on JavaScript, where an i64 / u64 is a js/BigInt; f32 a Float on the JVM."
  [b off kind i]
  (let [at (+ off (* i (kind-size kind)))]
    (case kind
      1 (r-u8 b at) 2 (r-i8 b at) 3 (r-u8 b at) 4 (r-i16 b at) 5 (r-u16 b at) 6 (r-i32 b at) 7 (r-u32 b at)
      8 #?(:clj (Float/intBitsToFloat (unchecked-int (r-u32 b at))) :cljs (r-f32 b at))
      9 (r-f64 b at)
      10 (r-i64 b at)
      11 #?(:clj (r-i64 b at) :cljs (.asUintN js/BigInt 64 (r-i64 b at))))))

(defn- finite-fill!
  "Overwrite the float elements of a kind 8 / 9 column with finite values (so = and hash are meaningful: NaN is not =
   to itself in any vector)."
  [r b off n kind]
  (when (#{8 9} kind)
    (dotimes [i n]
      (let [x (/ (- (rnd r 20001) 10000) 8.0)
            bs (if (== kind 8) (f32-bytes x) (f64-bytes x))]
        (dotimes [k (count bs)] (ba-set! b (+ off (* i (count bs)) k) (nth bs k)))))))

#_{:clj-kondo/ignore [:unused-binding]} ; bindings in this form are used on the :cljs pass only; the :clj arm does not reference them (per-pass binding surface)
(defn- hashable?
  "On ClojureScript a js/BigInt element has no cljs hash or compare (the same for a persistent vector holding it), so
   those rows are checked where every element is a number."
  [want]
  #?(:clj true :cljs (every? number? want)))

(defn- check-tuple [t want]
  (is (== (count want) (count t)))
  (is (every? true? (map same? want (seq t))) "seq, element for element")
  (is (every? true? (map-indexed (fn [i x] (same? x (nth t i))) want)) "nth")
  (is (every? true? (map-indexed (fn [i x] (same? x (get t i))) want)) "get")
  (is (= want t) "the vector = the tuple")
  (is (= t want) "the tuple = the vector")
  (when (hashable? want) (is (= (hash want) (hash t)) "hash"))
  (is (= (pr-str want) (pr-str t)) "print")
  (is (= (str want) (str t)) "str")
  (is (= (seq want) (seq t)))
  ;; into, not vec: this line exists to exercise into on the tuple type
  #_{:splint/disable [lint/into-literal]} (is (= want (into [] t)) "into []")
  (is (= want (vec t)))
  (is (= (reduce conj [] want) (reduce conj [] t)) "reduce with init")
  (when (seq want) (is (= (reduce (fn [a x] (if (vector? a) (conj a x) [a x])) want)
                          (reduce (fn [a x] (if (vector? a) (conj a x) [a x])) t)) "reduce without init"))
  (is (= (reduce-kv (fn [a i x] (conj a [i x])) [] want) (reduce-kv (fn [a i x] (conj a [i x])) [] t)) "reduce-kv")
  (when (seq want) (is (= 1 (reduce (fn [_a _] (reduced 1)) 0 t)) "reduced"))
  (is (nil? (get t -1)))
  (is (nil? (get t (count want))))
  (is (= :nf (get t :k :nf)))
  (is (= :nf (nth t (count want) :nf)))
  (is (= (rseq want) (rseq t)) "rseq")
  (is (= (peek want) (peek t)) "peek")
  (is (sequential? t))
  (is (vector? t))
  (is (counted? t))
  (when (hashable? want) (is (zero? (compare t want)) "compare"))
  (is (= [] (empty t)))
  (when (seq want)
    (is (same? (first want) (t 0)) "a tuple is a function of its index")
    (is (= (pop want) (pop t)) "pop")
    (is (vector? (pop t)))
    (is (contains? t 0))
    (is (= (find want 0) (find t 0)))
    (let [w (assoc t 0 :x)]
      (is (= (assoc want 0 :x) w))
      (is (instance? #?(:clj clojure.lang.PersistentVector :cljs PersistentVector) w) "a write answers a persistent vector")))
  (let [w (conj t :y)]
    (is (= (conj want :y) w))
    (is (instance? #?(:clj clojure.lang.PersistentVector :cljs PersistentVector) w)))
  (is (= {:m 1} (meta (with-meta t {:m 1}))))
  (is (= want (with-meta t {:m 1})))
  (when (hashable? want)
    (is (= (hash {want 1}) (hash {t 1})) "as a map key")
    (is (= 1 (get {want 1} t)) "a tuple finds its vector's map entry")))

(deftest tuples-random
  (let [r (rng 2026)]
    (doseq [kind [1 2 3 4 5 6 7 8 9 10 11] _ (range 12)]
      (let [sz (kind-size kind)
            n (rnd r 9)
            ;; aligned to the element size, or (half the time) one byte off it (FORMAT-V1 aligns; a reader
            ;; need not rely on it)
            off (+ (* sz (rnd r 4)) (if (and (> sz 1) (odd? (rnd r 2))) 1 0))
            b (random-bytes r (+ off (* sz n) 3))
            _ (finite-fill! r b off n kind)
            c (fio/chunk b)
            want (mapv #(r-elem b off kind %) (range n))]
        (testing (str "kind " kind " n " n " off " off)
          (check-tuple (fio/tuple c off n kind) want)
          (check-tuple (fio/col c off n kind) want))))))

#_{:clj-kondo/ignore [:unused-binding]} ; bindings in this form are used on the :clj pass only; the :cljs arm does not reference them (per-pass binding surface)
(deftest tuples-edges
  (testing "a chunk over bytes that sit at an odd offset in their buffer"
    #?(:cljs (let [big (js/Uint8Array. 64)]
               (dotimes [i 64] (aset big i (* 3 i)))
               (let [sub (.subarray big 1)
                     c (fio/chunk sub)]
                 (doseq [kind [4 5 6 7 9 10]]
                   (check-tuple (fio/tuple c 8 3 kind) (mapv #(r-elem sub 8 kind %) (range 3)))))))
    #?(:clj (is true)))
  (testing "elements as portable answers them: f32 a Float on the JVM, an i64 a js/BigInt on JavaScript"
    (let [b (ba 24)]
      (doseq [[k x] (map-indexed vector (f32-bytes 0.1))] (ba-set! b k x))
      (let [t (fio/tuple (fio/chunk b) 0 1 8)]
        #?(:clj (is (instance? Float (nth t 0))))
        #?(:clj (is (= "[0.1]" (pr-str t)))))
      (doseq [[k x] (map-indexed vector [0x01 0 0 0 0 0 0x20 0])] (ba-set! b (+ 8 k) x))
      (doseq [[k x] (map-indexed vector [5 0 0 0 0 0 0 0])] (ba-set! b (+ 16 k) x))
      ;; the :cljs arm's do is required: a reader-conditional arm takes one form
      #_{:splint/disable [style/let-do]} (let [t (fio/tuple (fio/chunk b) 8 2 10)]
                                           #?(:clj (is (= [9007199254740993 5] t))
                                              :cljs (do (is (= "[object BigInt]" (type-tag (nth t 0))))
                                                        (is (= "[object BigInt]" (type-tag (nth t 1))))
                                                        (is (= (js/BigInt 5) (nth t 1))))))))
  (testing "u64 past the signed range: its two's complement long on the JVM (as eve.flat.column reads kind 11)"
    (let [b (ba-of [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF])
          t (fio/tuple (fio/chunk b) 0 1 11)]
      #?(:clj (is (= [-1] t))
         :cljs (is (= (js/BigInt "18446744073709551615") (nth t 0))))))
  (testing "NaN elements read and print as NaN"
    (let [b (ba-of (f64-bytes ##NaN))
          t (fio/tuple (fio/chunk b) 0 1 9)]
      (is (same? ##NaN (nth t 0)))
      (is (= (pr-str [##NaN]) (pr-str t)))))
  (testing "an empty tuple is the empty vector"
    (let [t (fio/tuple (fio/chunk (ba 4)) 0 0 6)]
      (is (= [] t)) (is (= t [])) (is (= (hash []) (hash t))) (is (nil? (seq t))) (is (= "[]" (pr-str t)))))
  (testing "an unknown kind is refused"
    (is (thrown? #?(:clj Exception :cljs js/Error) (fio/tuple (fio/chunk (ba 8)) 0 1 12)))))

;; ---------------------------------------------------------------------------
;; the JVM's call shape: primitive signatures, so a hinted call site stays unboxed
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest jvm-primitive-signatures
     (doseq [[f iface] [[fio/u8 clojure.lang.IFn$OLL] [fio/u16 clojure.lang.IFn$OLL] [fio/u32 clojure.lang.IFn$OLL]
                        [fio/i32 clojure.lang.IFn$OLL] [fio/i64 clojure.lang.IFn$OLL] [fio/f32 clojure.lang.IFn$OLD]
                        [fio/f64 clojure.lang.IFn$OLD] [fio/varint-v clojure.lang.IFn$OLL]
                        [fio/varint-next clojure.lang.IFn$OLL] [fio/varint-len clojure.lang.IFn$LL]
                        [fio/key-cmp clojure.lang.IFn$OLLOL] [fio/buf-pos clojure.lang.IFn$OL]
                        [fio/buf-u8 clojure.lang.IFn$OLO] [fio/buf-u32 clojure.lang.IFn$OLO]
                        [fio/buf-f64 clojure.lang.IFn$ODO] [fio/buf-patch-u32 clojure.lang.IFn$OLLO]]]
       (is (instance? iface f) (str f " is " (.getSimpleName ^Class iface))))))
