(ns eve.encoding
  "eve's encoding primitives (HOST-CONTRACT §3-4): what eve's codec and types
   take from the host where hosts' APIs differ — UTF-8, IEEE-754 bits, the
   host's numbers, instants, uuids, exceptions, map entries, small caches —
   over the JVM's java.lang and java.util (the JVM, babashka, jolt — whose
   interop answers these classes)."
  (:refer-clojure :exclude [ratio? aget aset byte-array unchecked-byte unsigned-bit-shift-right
                            long class double bytes])
  (:require [eve.host :refer [aget aset byte-array unchecked-byte unsigned-bit-shift-right
                              long class double bytes]]))

(defn throw-index-out-of-bounds
  "Throw an index-out-of-bounds error."
  [msg]
  (throw (IndexOutOfBoundsException. ^String msg)))

(defn throw-unsupported
  "Throw an unsupported-operation error."
  []
  (throw (UnsupportedOperationException.)))

(defn unsupported
  "The host's unsupported-operation exception, with message msg."
  [msg]
  (UnsupportedOperationException. ^String msg))

(defn map-entry
  "A map entry [k v] (what seq on a map answers), on every host."
  [k v]
  (clojure.lang.MapEntry/create k v))

(defn arraycopy
  "Copy len elements of array src from soff to dst at doff."
  [src soff dst doff len]
  (System/arraycopy src (int soff) dst (int doff) (int len)))

(defn copy-of-range
  "A new byte array of b's bytes [from, to)."
  ^bytes [^bytes b from to]
  (let [n (- to from) out (byte-array n)]
    (arraycopy b from out 0 n)
    out))

(defn utf8-bytes
  "The UTF-8 bytes of string s."
  ^bytes [s]
  (.getBytes ^String s "UTF-8"))

(defn utf8-string
  "The string len UTF-8 bytes of b encode, from off."
  [^bytes b off len]
  (String. b (int off) (int len) "UTF-8"))

(defn long-bits->double
  "The double whose IEEE-754 bits are x."
  ^double [^long x]
  (Double/longBitsToDouble x))

(defn double->long-bits
  "The IEEE-754 bits of the double d, as a long (NaN payloads kept)."
  ^long [^double d]
  (Double/doubleToRawLongBits d))

(defn fixed-integer?
  "An integer of a fixed-width host type — the ones eve's format carries as
   INT32 or INT64. A BigInt is not one: the format has no tag for it."
  [v]
  (or (instance? Long v) (instance? Integer v) (instance? Short v) (instance? Byte v)))

(defn floating?
  "A floating-point number (FLOAT64)."
  [v]
  (or (instance? Double v) (instance? Float v)))

;; The numbers past a fixed width (FORMAT-DECISIONS.md §8): what they are on
;; this host, and their parts as eve.codec writes them (it lays out the bytes)

(defn big-integer?
  "An integer of an arbitrary-precision type (clojure.lang.BigInt,
   java.math.BigInteger): eve.codec writes it by its magnitude — INT32 or INT64
   when it fits, else BIGINT."
  [v]
  (or (instance? clojure.lang.BigInt v) (instance? java.math.BigInteger v)))

(defn integer->twos
  "Integer n's minimal two's-complement bytes, big-endian, as a vector of 0..255
   — by arithmetic (floor mod and exact quotient), which every host of this
   adapter has: jolt's BigInt answers no BigInteger.toByteArray (2026-10-19)."
  [n]
  (loop [x n acc ()]
    (let [b (clojure.core/long (mod x 256))
          x (quot (-' x b) 256)]
      (if (or (and (zero? x) (< b 128)) (and (= -1 x) (>= b 128)))
        (vec (cons b acc))
        (recur x (cons b acc))))))

(defn twos->integer
  "The integer whose minimal two's-complement big-endian bytes are bs (0..255
   each): a BigInt where it needs one (by arithmetic, as integer->twos)."
  [bs]
  (let [u (reduce (fn [acc b] (+ (* acc 256) b)) 0N bs)]
    (bigint (if (and (seq bs) (>= (first bs) 128))
              (- u (reduce * 1N (repeat (count bs) 256)))
              u))))

(defn ratio? "A ratio (RATIO)." [v] (instance? clojure.lang.Ratio v))

(defn ratio-parts
  "[numerator denominator] of a ratio: lowest terms, the denominator positive."
  [r]
  [(numerator r) (denominator r)])

(defn make-ratio "The ratio n/d (n, d integers in lowest terms, d > 1)." [n d] (/ (bigint n) (bigint d)))

(defn bigdec? "An arbitrary-precision decimal (BIGDEC)." [v] (instance? java.math.BigDecimal v))

(defn bigdec-parts
  "[unscaled scale] of decimal d in its canonical form: trailing zeros stripped
   down to scale 0 (so (= 1.5M 1.50M) has one form), never a negative scale."
  [d]
  (let [z (.stripTrailingZeros ^java.math.BigDecimal d)
        z (if (neg? (.scale z)) (.setScale z 0) z)]
    [(.unscaledValue z) (.scale z)]))

(defn make-bigdec
  "The decimal unscaled × 10^-scale."
  [unscaled scale]
  ;; not BigDecimal(BigInteger, int): jolt's ignores the scale (it built 15M for 15 x 10^-1, 2026-10-19)
  (.movePointLeft ^java.math.BigDecimal (bigdec unscaled) (clojure.core/int scale)))

(defn date?
  "The host's instant type, the one eve's DATE tag reads back as."
  [v]
  (instance? java.util.Date v))

(defn date-ms
  "Milliseconds since the Unix epoch of a date."
  ^long [d]
  (.getTime ^java.util.Date d))

(defn ms->date
  "The host's instant at ms milliseconds since the Unix epoch (UTC)."
  [ms]
  (java.util.Date. (long ms)))

(defn uuid->bytes!
  "Write uuid u's 16 bytes, big-endian (network order), into b at off."
  [u ^bytes b off]
  (let [^java.util.UUID u u]
    (loop [i 0 msb (.getMostSignificantBits u) lsb (.getLeastSignificantBits u)]
      (when (< i 8)
        (aset b (+ off (- 7 i)) (unchecked-byte msb))
        (aset b (+ off (- 15 i)) (unchecked-byte lsb))
        (recur (inc i) (unsigned-bit-shift-right msb 8) (unsigned-bit-shift-right lsb 8))))))

(defn bytes->uuid
  "The uuid whose 16 big-endian bytes are at off in b."
  [^bytes b off]
  (let [word (fn [o] (loop [i 0 x 0]
                       (if (< i 8)
                         (recur (inc i) (bit-or (bit-shift-left x 8) (bit-and (long (aget b (+ o i))) 0xFF)))
                         x)))]
    (java.util.UUID. (word off) (word (+ off 8)))))

(defn utf16-units
  "The UTF-16 code units of string s, as integers (what a JVM or
   JavaScript string holds; a clojerl string is UTF-8, so a code point
   above U+FFFF is two units there, a surrogate pair)."
  [s]
  (mapv int s))

(defn host-char?
  "A character of the host's own char type. basilisp and clojerl have none: a
   one-character string answers char? there, and is a string, as in
   ClojureScript."
  [v]
  (char? v))

(defn host-array?
  "A host array (a Java or CLR array; on clojerl one of eve.host's)."
  [v]
  (.isArray (class v)))

(defn new-cache
  "A thread-safe map for small caches (cache-get / cache-put!).
  A synchronized HashMap, not a ConcurrentHashMap: this leaf is analyzed
  by babashka (named in the ns docstring), and bb 1.3.190's analyzer cannot
  resolve java.util.concurrent.ConcurrentHashMap (nor java.util.Hashtable).
  Every operation the cache's callers use (get / put / size / clear, never
  iteration) is individually synchronized on the wrapper, so the cache
  stays safe under concurrent encoding; the size→clear→put sequence was
  never atomic and remains a benign memo race (a lost entry re-encodes)."
  []
  (java.util.Collections/synchronizedMap (java.util.HashMap. 256)))

(defn cache-get [c k]
  (.get ^java.util.Map c k))

(defn cache-put!
  "Put k → v in cache c, emptying it first when it holds more than max."
  [c k v max]
  (let [^java.util.Map c c]
    (when (> (.size c) (long max)) (.clear c))
    (.put c k v))
  v)

(defn bytes=
  "Two byte arrays equal, byte for byte."
  [a b]
  (java.util.Arrays/equals ^bytes a ^bytes b))

(defn illegal-argument
  "The host's illegal-argument exception, with message msg."
  [msg]
  (IllegalArgumentException. ^String msg))

(defn index-out-of-bounds
  "The host's index-out-of-bounds exception, with message msg."
  [msg]
  (IndexOutOfBoundsException. ^String msg))

(defn- le-buffer ^java.nio.ByteBuffer [^bytes b]
  (doto (java.nio.ByteBuffer/wrap b) (.order java.nio.ByteOrder/LITTLE_ENDIAN)))

(defn read-f32-le
  "The float32 at byte off of b (little-endian), as a double."
  [^bytes b off]
  (double (.getFloat (le-buffer b) (int off))))

(defn read-f64-le
  "The float64 at byte off of b (little-endian)."
  [^bytes b off]
  (.getDouble (le-buffer b) (int off)))

(defn f32-le-bytes
  "The 4 little-endian bytes of v as a float32."
  [v]
  (let [b (clojure.core/byte-array 4)] (.putFloat (le-buffer b) 0 (float v)) b))

(defn f64-le-bytes
  "The 8 little-endian bytes of v as a float64."
  [v]
  (let [b (clojure.core/byte-array 8)] (.putDouble (le-buffer b) 0 (double v)) b))

(defn make-bytes
  "n zeroed bytes (the host's byte array, as eve's codec holds bytes)."
  [n]
  (clojure.core/byte-array (int n)))

(defn bytes-of
  "The bytes xs (each 0..255, or its signed byte), as a byte array."
  [xs]
  (clojure.core/byte-array (map (fn [x] (unchecked-byte x)) xs)))

(defn bytes-copy
  "A copy of byte array b, in b's own representation (what the host's region
   and codec read: a read's bytes, changed, handed on as read)."
  [b]
  (clojure.core/aclone ^bytes b))

(defn map-hash-code
  "The host's object hash of a map m (java.util.Map's hashCode: the sum of
   each entry's key hash xor value hash)."
  [m]
  (clojure.lang.APersistentMap/mapHash m))

(defn write!
  "Write s (str of it) to the host's writer w (java.io.Writer): what a printer writes to."
  [w s]
  (.write ^java.io.Writer w (str s)))

(defn f64s-le
  "The n float64s little-endian in b from byte off, as the host's double array."
  [^bytes b off n]
  (let [out (double-array n)]
    (.get (.asDoubleBuffer (.position (le-buffer b) (int off))) out)
    out))

(defn i32s-le
  "The n int32s little-endian in b from byte off, as the host's int array."
  [^bytes b off n]
  (let [out (int-array n)]
    (.get (.asIntBuffer (.position (le-buffer b) (int off))) out)
    out))

(defn f64s->bytes-le
  "The little-endian bytes of the doubles in da (the host's double array), 8 each."
  [^doubles da]
  (let [b (byte-array (* 8 (alength da)))]
    (.put (.asDoubleBuffer (le-buffer b)) da)
    b))

(defn i32s->bytes-le
  "The little-endian bytes of the ints in ia (the host's int array), 4 each."
  [^ints ia]
  (let [b (byte-array (* 4 (alength ia)))]
    (.put (.asIntBuffer (le-buffer b)) ia)
    b))

(defn array-subtype+bytes
  "[subtype little-endian-bytes] of a host typed array — eve.array's subtype
   codes: int8 2, int16 4, int32 6, float32 8, float64 9 — nil for any other
   value."
  [a]
  (cond
    (instance? (class (clojure.core/int-array 0)) a) [6 (i32s->bytes-le a)]
    (instance? (class (clojure.core/double-array 0)) a) [9 (f64s->bytes-le a)]
    (instance? (class (clojure.core/float-array 0)) a)
    (let [fa ^floats a
          bb (le-buffer (byte-array (* 4 (clojure.core/alength fa))))]
      (dotimes [i (clojure.core/alength fa)] (.putFloat bb (clojure.core/aget fa i)))
      [8 (.array bb)])
    (instance? (class (clojure.core/short-array 0)) a)
    (let [sa ^shorts a
          bb (le-buffer (byte-array (* 2 (clojure.core/alength sa))))]
      (dotimes [i (clojure.core/alength sa)] (.putShort bb (clojure.core/aget sa i)))
      [4 (.array bb)])
    (instance? (class (clojure.core/byte-array 0)) a) [2 a]
    :else nil))

;; The host's own typed arrays, which a fresh eve array is on a host that has
;; them (eve.array's heap arrays): the JVM's primitive arrays

(defn typed-array
  "n zeroed elements of eve.array subtype code as the host's typed array (nil
   where the host has none): byte[] for the 8-bit subtypes, short[] for the
   16-bit, int[] for the 32-bit integers, float[], double[]."
  [subtype n]
  (case (int subtype)
    (1 2 3) (clojure.core/byte-array (int n))
    (4 5)   (clojure.core/short-array (int n))
    (6 7)   (clojure.core/int-array (int n))
    8       (clojure.core/float-array (int n))
    9       (clojure.core/double-array (int n))))

(defn typed-aget
  "Element i of typed array a of subtype code, as eve.array reads it (an
   unsigned subtype's value from its two's-complement bits)."
  [a subtype i]
  (case (int subtype)
    (1 3) (bit-and (clojure.core/long (clojure.core/aget ^bytes a (int i))) 0xFF)
    2     (clojure.core/long (clojure.core/aget ^bytes a (int i)))
    4     (clojure.core/long (clojure.core/aget ^shorts a (int i)))
    5     (bit-and (clojure.core/long (clojure.core/aget ^shorts a (int i))) 0xFFFF)
    6     (clojure.core/long (clojure.core/aget ^ints a (int i)))
    7     (bit-and (clojure.core/long (clojure.core/aget ^ints a (int i))) 0xFFFFFFFF)
    8     (clojure.core/double (clojure.core/aget ^floats a (int i)))
    9     (clojure.core/aget ^doubles a (int i))))

(defn typed-aset!
  "Write v as element i of typed array a of subtype code (an unsigned value as
   its two's-complement bits: a :uint8 200 is the byte -56). Answers v."
  [a subtype i v]
  (case (int subtype)
    (1 2 3) (clojure.core/aset ^bytes a (int i) (clojure.core/unchecked-byte (clojure.core/long v)))
    (4 5)   (clojure.core/aset ^shorts a (int i) (clojure.core/unchecked-short (clojure.core/long v)))
    (6 7)   (clojure.core/aset ^ints a (int i) (clojure.core/unchecked-int (clojure.core/long v)))
    8       (clojure.core/aset ^floats a (int i) (float (clojure.core/double v)))
    9       (clojure.core/aset ^doubles a (int i) (clojure.core/double v)))
  v)

(defn code-points->string
  "The string of a sequence of Unicode code points (one past U+FFFF as its UTF-16 surrogate pair, as a JVM string
   holds it) — the inverse of utf16-units, where a host's characters differ in width."
  [cps]
  (let [sb (StringBuilder.)]
    (doseq [cp cps] (.appendCodePoint sb (int cp)))
    (.toString sb)))

;; ── the byte codec slot (eve.net.bytes, defect register #50) ──────────────────────────────────────────────
;; This host's native codecs, plugged where eve.net.bytes dispatches to them (java.util.Base64 is Java 8;
;; nothing here is newer). The contract is eve.net.bytes' portable semantics, which the fixtures
;; (eve.net.bytes-codec-test) hold these to byte for byte, and java.util.Base64's decoders disagree with it
;; in two places, each handled here: they treat '=' as the end wherever it appears and refuse junk, where the
;; contract SKIPS any character outside the alphabet (so the text is cleaned to the alphabet first — on
;; cleaned text the basic decoder matches, its MIME sibling does not), and they refuse a tail of one digit,
;; which the contract decodes as one byte of four times the digit (so that digit is decoded here). Hex is a
;; pair loop over java.lang.Character digits: no newer class is named, so every host this adapter serves
;; (the JVM, babashka, jolt) loads it.

(def ^:private b64-digit-value
  (into {} (map-indexed (fn [i c] [c i]) "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")))

(def ^:private hex-chars "0123456789abcdef")

(defn- byte-array->ints
  "a byte array as a vector of 0..255"
  [^bytes arr]
  (mapv (fn [b] (bit-and (clojure.core/long b) 0xFF)) arr))

(defn bytes->b64
  "standard base64 (padded) of a byte sequence — java.util.Base64"
  [bs]
  (.encodeToString (java.util.Base64/getEncoder)
                   (clojure.core/byte-array (map (fn [x] (clojure.core/unchecked-byte x)) bs))))

(defn b64->bytes
  "the bytes of base64 text, eve.net.bytes' contract over java.util.Base64 (see the section note)"
  [s]
  (let [clean (.replaceAll ^String s "[^A-Za-z0-9+/]" "")
        n (count clean)
        r (mod n 4)
        body (if (= r 1) (subs clean 0 (dec n)) clean)
        out (byte-array->ints (.decode (java.util.Base64/getDecoder) body))]
    (if (= r 1)
      (conj out (* 4 (get b64-digit-value (nth clean (dec n)))))
      out)))

(defn bytes->hex
  "lower-case hex of a byte sequence — a StringBuilder loop"
  [bs]
  (let [sb (StringBuilder. (int (* 2 (count bs))))]
    (doseq [b bs]
      (.appendCodePoint sb (int (nth hex-chars (quot b 16))))
      (.appendCodePoint sb (int (nth hex-chars (mod b 16)))))
    (.toString sb)))

(defn hex->bytes
  "the bytes of hex text (even length), eve.net.bytes' contract: throws on odd length and on a non-digit"
  [s]
  (let [n (count s)]
    (when (odd? n) (throw (ex-info "hex text of odd length" {:len n})))
    (let [m (quot n 2)
          out (clojure.core/byte-array (int m))]
      (loop [i 0]
        (if (< i m)
          (let [j (* 2 i)
                hc (nth s j)
                lc (nth s (inc j))
                hi (if (< (int hc) 128) (Character/digit hc 16) -1)
                lo (if (< (int lc) 128) (Character/digit lc 16) -1)]
            (when (or (neg? hi) (neg? lo))
              (let [bad (if (neg? hi) hc lc)]
                (throw (ex-info (str "not a hex digit: " bad) {:char bad}))))
            (clojure.core/aset out (int i) (clojure.core/unchecked-byte (+ (* 16 hi) lo)))
            (recur (inc i)))
          (byte-array->ints out))))))
