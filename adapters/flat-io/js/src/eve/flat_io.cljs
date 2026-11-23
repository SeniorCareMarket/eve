;; adapters/flat-io/js/src/eve/flat_io.cljs — eve.flat-io, the ClojureScript fast root (lane U2b of the flat lanes, 2026-11-17).
;; The contract: doc/flat/FLAT-IO.md. The reference semantics: chunk.cljc's byte layer (its :cljs arms) as it stood at
;; 3b656fea20, which flat-io/portable carries; every answer here is that answer (adapters/flat-io/test). The fast parts:
;; one DataView per chunk, kept in the chunk; the platform's TextDecoder; tuples over typed arrays.
(ns eve.flat-io
  "The byte-level seam under eve's flat layer — ClojureScript fast root (doc/flat/FLAT-IO.md)."
  (:refer-clojure :exclude [chunk]))

;; ---------------------------------------------------------------------------
;; chunks — a Uint8Array and one DataView over exactly its bytes
;; ---------------------------------------------------------------------------

(deftype Chunk [^js/Uint8Array b ^js/DataView dv])

(defn chunk
  "The chunk over Uint8Array bs (a chunk answers itself)."
  [bs]
  (if (instance? Chunk bs)
    bs
    (Chunk. bs (js/DataView. (.-buffer bs) (.-byteOffset bs) (.-byteLength bs)))))

(defn chunk-bytes
  "The Uint8Array of chunk c."
  [^Chunk c] (.-b c))

(defn chunk-len
  "Chunk c's length in bytes."
  [^Chunk c] (.-length (.-b c)))

(defn- host-bytes
  "A byte argument as its Uint8Array: a Uint8Array as it is, a chunk's bytes."
  [x] (if (instance? Chunk x) (.-b ^Chunk x) x))

;; ---------------------------------------------------------------------------
;; reads — little-endian, absolute offsets. An i64 is a js/BigInt (exact).
;; ---------------------------------------------------------------------------

(defn u8 [^Chunk c i] (aget (.-b c) i))
(defn u16 [^Chunk c i] (.getUint16 (.-dv c) i true))
(defn u32 [^Chunk c i] (.getUint32 (.-dv c) i true))
(defn i32 [^Chunk c i] (.getInt32 (.-dv c) i true))
(defn i64 [^Chunk c i] (.getBigInt64 (.-dv c) i true))
(defn f32 [^Chunk c i] (.getFloat32 (.-dv c) i true))
(defn f64 [^Chunk c i] (.getFloat64 (.-dv c) i true))

(defn varint-v
  "The value of the unsigned LEB128 varint at i, accumulated arithmetically
   (exact below 2^53, as the reference's)."
  [^Chunk c i]
  (let [b (.-b c) x (aget b i)]
    (if (< x 128)
      x
      (loop [i (inc i) mul 128 v (bit-and x 127)]
        (let [x (aget b i)]
          (if (< x 128)
            (+ v (* x mul))
            (recur (inc i) (* mul 128) (+ v (* (bit-and x 127) mul)))))))))

(defn varint-next
  "The offset after the varint at i."
  [^Chunk c i]
  (let [b (.-b c)]
    (loop [i i] (if (< (aget b i) 128) (inc i) (recur (inc i))))))

(defn varint
  "[value next] — the unsigned LEB128 varint at i."
  [^Chunk c i]
  (let [b (.-b c)]
    (loop [i i mul 1 v 0]
      (let [x (aget b i)]
        (if (< x 128)
          [(+ v (* (bit-and x 127) mul)) (inc i)]
          (recur (inc i) (* mul 128) (+ v (* (bit-and x 127) mul))))))))

(defn varint-len
  "The byte count of v as a varint (a negative v answers 1, as the reference's)."
  [v]
  (loop [v v n 1] (if (< v 128) n (recur (quot v 128) (inc n)))))

;; ignoreBOM: true — a leading EF BB BF is the string's U+FEFF, as the JVM's
;; String(byte[], off, n, UTF_8) keeps it; TextDecoder's default drops it.
;; fatal: false — malformed input decodes to U+FFFD (the WHATWG decoder; the
;; conformance fixture holds it to the JVM's answers).
(def ^:private utf8-decoder (js/TextDecoder. "utf-8" #js {:fatal false :ignoreBOM true}))
;; Short all-ASCII runs (map keys, symbol names) skip the decoder: measured on Node 22 for a "Station-N" decode
;; (best of 15 x 200k), TextDecoder 80-84 ns, String.fromCharCode called with the bytes as arguments 18-19 ns,
;; fromCharCode.apply over a fresh Array 46-47 ns; over a subarray, or a += loop, no better than the decoder.
(def ^:private from-char-code (.-fromCharCode js/String))

(defn- ascii? [b off end]
  (loop [i off] (if (< i end) (if (< (aget b i) 128) (recur (inc i)) false) true)))

(defn- ascii-string
  "The string of n (1-32) ASCII bytes at off of b."
  [b off n]
  (case n
    1 (.fromCharCode js/String (aget b off))
    2 (.fromCharCode js/String (aget b off) (aget b (+ off 1)))
    3 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)))
    4 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)))
    5 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)))
    6 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)))
    7 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)))
    8 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)))
    9 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)) (aget b (+ off 8)))
    10 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)) (aget b (+ off 8)) (aget b (+ off 9)))
    11 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)) (aget b (+ off 8)) (aget b (+ off 9)) (aget b (+ off 10)))
    12 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)) (aget b (+ off 8)) (aget b (+ off 9)) (aget b (+ off 10)) (aget b (+ off 11)))
    13 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)) (aget b (+ off 8)) (aget b (+ off 9)) (aget b (+ off 10)) (aget b (+ off 11)) (aget b (+ off 12)))
    14 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)) (aget b (+ off 8)) (aget b (+ off 9)) (aget b (+ off 10)) (aget b (+ off 11)) (aget b (+ off 12)) (aget b (+ off 13)))
    15 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)) (aget b (+ off 8)) (aget b (+ off 9)) (aget b (+ off 10)) (aget b (+ off 11)) (aget b (+ off 12)) (aget b (+ off 13)) (aget b (+ off 14)))
    16 (.fromCharCode js/String (aget b off) (aget b (+ off 1)) (aget b (+ off 2)) (aget b (+ off 3)) (aget b (+ off 4)) (aget b (+ off 5)) (aget b (+ off 6)) (aget b (+ off 7)) (aget b (+ off 8)) (aget b (+ off 9)) (aget b (+ off 10)) (aget b (+ off 11)) (aget b (+ off 12)) (aget b (+ off 13)) (aget b (+ off 14)) (aget b (+ off 15)))
    (let [a (make-array n)]
      (dotimes [i n] (aset a i (aget b (+ off i))))
      (.apply from-char-code nil a))))

(defn utf8
  "The string of the n UTF-8 bytes at off: a run of up to 32 ASCII bytes spelled
   with String.fromCharCode (the same string), anything else through the
   TextDecoder."
  [^Chunk c off n]
  (let [b (.-b c) end (+ off n)]
    (cond
      (zero? n) ""
      (and (<= n 32) (ascii? b off end)) (ascii-string b off n)
      :else (.decode utf8-decoder (.subarray b off end)))))

;; ---------------------------------------------------------------------------
;; in place — compares with no copy of the stored bytes
;; ---------------------------------------------------------------------------

(defn bytes=
  "Are the n bytes at off of chunk c equal to the bytes of bs (a Uint8Array)
   from boff? False when bs holds fewer than n bytes from boff (the portable
   root's answer)."
  [^Chunk c off n bs boff]
  (let [b (.-b c) ^js/Uint8Array bs (host-bytes bs)]
    (and (<= (+ boff n) (.-length bs))
         (loop [i 0]
           (if (< i n)
             (if (== (aget b (+ off i)) (aget bs (+ boff i))) (recur (inc i)) false)
             true)))))

(defn bytes-cmp
  "-1 / 0 / 1: the n bytes at off of c against the bn bytes of bs from boff,
   unsigned lexicographic, a shorter one first on a common prefix."
  [^Chunk c off n bs boff bn]
  (let [b (.-b c) ^js/Uint8Array bs (host-bytes bs) m (min n bn)]
    (loop [i 0]
      (if (< i m)
        (let [x (aget b (+ off i)) y (aget bs (+ boff i))]
          (if (== x y) (recur (inc i)) (if (< x y) -1 1)))
        (cond (< n bn) -1 (> n bn) 1 :else 0)))))

(defn- utf8-len
  "The byte count of TextEncoder's encoding of k: a lone surrogate encodes as
   U+FFFD (three bytes) — what eve.codec/encode-key (TextEncoder) answers."
  [^string k]
  (let [m (.-length k)]
    (loop [i 0 n 0]
      (if (< i m)
        (let [ch (.charCodeAt k i)]
          (cond
            (< ch 0x80) (recur (inc i) (inc n))
            (< ch 0x800) (recur (inc i) (+ n 2))
            (and (>= ch 0xD800) (<= ch 0xDBFF) (< (inc i) m)
                 (let [lo (.charCodeAt k (inc i))] (and (>= lo 0xDC00) (<= lo 0xDFFF))))
            (recur (+ i 2) (+ n 4))
            :else (recur (inc i) (+ n 3))))
        n))))

(defn- cmp-bytes
  "-1 / 0 / 1: the cnt stored bytes at j against e0 e1 e2 e3 (the first cnt)."
  [b j cnt e0 e1 e2 e3]
  (let [s (aget b j)]
    (if (not (== s e0)) (if (< s e0) -1 1)
        (if (< cnt 2) 0
            (let [s (aget b (+ j 1))]
              (if (not (== s e1)) (if (< s e1) -1 1)
                  (if (< cnt 3) 0
                      (let [s (aget b (+ j 2))]
                        (if (not (== s e2)) (if (< s e2) -1 1)
                            (if (< cnt 4) 0
                                (let [s (aget b (+ j 3))]
                                  (if (not (== s e3)) (if (< s e3) -1 1) 0))))))))))))

(defn- key-cmp-general
  "key-cmp's whole rule: k's UTF-8 length first, then the class and length
   bytes, then the bytes encoded on the fly."
  [^Chunk c off n ^string k]
  (let [b (.-b c)
        m (utf8-len k)
        cs (if (< n 256) 5 6)
        ck (if (< m 256) 5 6)]
    (cond
      (not (== cs ck)) (if (< cs ck) -1 1)
      (and (== cs 5) (not (== n m))) (if (< n m) -1 1)
      (and (== cs 6) (not (== n m)))
      (loop [d 1]
        (let [x (bit-and (js/Math.floor (/ n d)) 0xFF)
              y (bit-and (js/Math.floor (/ m d)) 0xFF)]
          (if (== x y) (recur (* d 256)) (if (< x y) -1 1))))
      :else
      (let [km (.-length k)]
        (loop [i 0 j off]
          (if (< i km)
            (let [ch (.charCodeAt k i)]
              (if (< ch 0x80)
                (let [s (aget b j)]
                  (if (== s ch) (recur (inc i) (inc j)) (if (< s ch) -1 1)))
                (let [lo (if (< (inc i) km) (.charCodeAt k (inc i)) 0)
                      pair? (and (>= ch 0xD800) (<= ch 0xDBFF) (>= lo 0xDC00) (<= lo 0xDFFF))
                      ;; a lone surrogate is U+FFFD, as TextEncoder writes it
                      cp (cond pair? (+ 0x10000 (* (- ch 0xD800) 1024) (- lo 0xDC00))
                               (and (>= ch 0xD800) (<= ch 0xDFFF)) 0xFFFD
                               :else ch)
                      r (cond
                          (< cp 0x800)
                          (cmp-bytes b j 2 (bit-or 0xC0 (bit-shift-right cp 6)) (bit-or 0x80 (bit-and cp 0x3F)) 0 0)
                          (< cp 0x10000)
                          (cmp-bytes b j 3 (bit-or 0xE0 (bit-shift-right cp 12))
                                     (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                                     (bit-or 0x80 (bit-and cp 0x3F)) 0)
                          :else
                          (cmp-bytes b j 4 (bit-or 0xF0 (bit-shift-right cp 18))
                                     (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F))
                                     (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                                     (bit-or 0x80 (bit-and cp 0x3F))))]
                  (if (zero? r)
                    (recur (if pair? (+ i 2) (inc i))
                           (+ j (cond (< cp 0x800) 2 (< cp 0x10000) 3 :else 4)))
                    r))))
            0))))))

(defn- ascii-cmp
  "k (of exactly n chars) against the n stored bytes at off, when every char of
   k is ASCII: -1 / 0 / 1 by the first differing byte; 2 when k has a char past
   ASCII (the caller then takes the whole rule)."
  [^js/Uint8Array b off n ^string k]
  (loop [i 0 r 0]
    (if (< i n)
      (let [ch (.charCodeAt k i)]
        (if (>= ch 0x80)
          2
          (if (zero? r)
            (let [s (aget b (+ off i))]
              (recur (inc i) (cond (== s ch) 0 (< s ch) -1 :else 1)))
            (recur (inc i) r))))
      r)))

(defn key-cmp
  "-1 / 0 / 1: the string key stored as the n UTF-8 bytes at [off, off+n) of c
   against host string k, in eve's key-byte order — the order of
   eve.codec/encode-key's bytes under unsigned lexicographic compare: a string
   below 256 UTF-8 bytes is [0x05][len u8][bytes], from 256 [0x06][len u32 LE]
   [bytes]. So: length class, then the length's encoded bytes (one byte; or
   four little-endian bytes compared lexicographically — low byte first, which
   is not numeric order past 256), then the bytes. k is encoded on the fly,
   never copied. A binary search's common cases answer early: k's UTF-16 length
   km bounds its UTF-8 length m (km <= m <= 3 km), so a short stored key
   (n < 256) is below a k of more than n code units and above one of fewer than
   n/3; and a k of n ASCII chars is compared byte for byte in one pass."
  [^Chunk c off n ^string k]
  (let [km (.-length k)]
    (cond
      (and (< n 256) (> km n)) -1
      (and (< n 256) (< (* 3 km) n)) 1
      (== km n) (let [r (ascii-cmp (.-b c) off n k)] (if (== r 2) (key-cmp-general c off n k) r))
      :else (key-cmp-general c off n k))))

;; ---------------------------------------------------------------------------
;; the writer — a growable Uint8Array + DataView. Growth is the reference's
;; exactly (start at 1024 bytes; a write past the end doubles, or grows to what
;; a block needs if more), so buf-raw answers the same array length.
;; ---------------------------------------------------------------------------

;; Spelled as chunk.cljc's reference spells it (no goog/typeOf, no js*). This root is for compiled
;; ClojureScript (Node, the browser): nbb's SCI deftype takes no core protocol (measured: "Protocol not
;; found: ICounted") and reads no deftype field from outside, so nbb takes flat-io/portable.
(defn- bigint? [x] (identical? "[object BigInt]" (.call ^js (.-toString (.-prototype js/Object)) x)))
(defn- bi [x] (if (bigint? x) x (js/BigInt x)))

(deftype FlatBuf [^:mutable ^js/Uint8Array b ^:mutable ^js/DataView dv ^:mutable p width]
  Object
  (grow [_ need]
    (when (< (.-length b) need)
      (let [nb (js/Uint8Array. (max (* 2 (.-length b)) need))]
        (.set nb b)
        (set! b nb)
        (set! dv (js/DataView. (.-buffer nb))))))
  (raw [_] b)
  (pos [_] p)
  (seek [_ np] (set! p np) nil)
  (u8 [this v]
    (when (>= p (.-length b)) (.grow this (* 2 (.-length b))))
    (aset b p (bit-and v 0xFF))
    (set! p (inc p))
    nil)
  (u16 [this v]
    (.grow this (+ p 2))
    (.setUint16 dv p (bit-and v 0xFFFF) true)
    (set! p (+ p 2))
    nil)
  (u32 [this v]
    (.grow this (+ p 4))
    (.setUint32 dv p (bit-and v 0xFFFFFFFF) true)
    (set! p (+ p 4))
    nil)
  (i64 [this v]
    (.grow this (+ p 8))
    (.setBigInt64 dv p (bi v) true)
    (set! p (+ p 8))
    nil)
  (f32 [this v]
    (.grow this (+ p 4))
    (.setFloat32 dv p v true)
    (set! p (+ p 4))
    nil)
  (f64 [this v]
    (.grow this (+ p 8))
    (.setFloat64 dv p v true)
    (set! p (+ p 8))
    nil)
  (varint [this v]
    (loop [v v]
      (if (< v 128)
        (.u8 this v)
        (do (.u8 this (bit-or (bit-and v 127) 128))
            (recur (quot v 128))))))
  (putBytes [this src off n]
    (.grow this (+ p n))
    (.set b (.subarray ^js/Uint8Array (host-bytes src) off (+ off n)) p)
    (set! p (+ p n))
    nil)
  (off [this v] (if (== width 2) (.u16 this v) (.u32 this v)))
  (patchU32 [_ at v] (.setUint32 dv at (bit-and v 0xFFFFFFFF) true) nil)
  (align [this a]
    (let [r (rem p a)]
      (when-not (zero? r)
        (let [e (+ p (- a r))]
          (.grow this e)
          ;; explicit zeros: a seek back can leave old bytes past p
          (.fill b 0 p e)
          (set! p e))))
    nil)
  (chunkOf [_ n]
    (let [out (js/Uint8Array. n)]
      (.set out (.subarray b 0 (min n (.-length b))))
      out)))

(defn buf
  "A growable writer; width 2 or 4, the chunk's offset width."
  [width]
  (let [b (js/Uint8Array. 1024)]
    (FlatBuf. b (js/DataView. (.-buffer b)) 0 width)))

(defn buf-pos [^FlatBuf w] (.pos w))
(defn buf-seek [^FlatBuf w p] (.seek w p))
(defn buf-u8 [^FlatBuf w v] (.u8 w v))
(defn buf-u16 [^FlatBuf w v] (.u16 w v))
(defn buf-u32 [^FlatBuf w v] (.u32 w v))
(defn buf-i64 [^FlatBuf w v] (.i64 w v))
(defn buf-f32
  "Not in the contract's list (FLAT-IO.md): v as a little-endian f32 — what
   chunk.cljc's cljs Buf .f32 writes for column kind 8."
  [^FlatBuf w v] (.f32 w v))
(defn buf-f64 [^FlatBuf w v] (.f64 w v))
(defn buf-varint [^FlatBuf w v] (.varint w v))
(defn buf-bytes [^FlatBuf w src off n] (.putBytes w src off n))
(defn buf-off [^FlatBuf w v] (.off w v))
(defn buf-patch-u32 [^FlatBuf w at v] (.patchU32 w at v))
(defn buf-align [^FlatBuf w a] (.align w a))
(defn buf-raw [^FlatBuf w] (.raw w))
(defn buf-chunk
  "The chunk of the writer's first n bytes: a copy of exactly n (zero-padded
   past the bytes written)."
  [^FlatBuf w n] (chunk (.chunkOf w n)))

;; ---------------------------------------------------------------------------
;; tuples and columns — a view over the chunk that IS the host's vector of its
;; elements. The elements are what chunk.cljc's COL reader (col-at) answers for
;; the kind, as flat-io/portable's tuple answers them: numbers, an i64 / u64 a
;; js/BigInt (the contract's i64). Every element is read through the chunk's DataView:
;; measured on Node 22, a typed-array view costs 15.4-15.8 ns to make and reads
;; no faster than the DataView (0.53 vs 0.55 ns an element over a 1024-element
;; reduce), so a tuple makes none (scratch viewprobe.js, ledger 2026-11-17).
;; ---------------------------------------------------------------------------

(defn- kind-size [kind]
  (case kind
    (1 2 3) 1
    (4 5) 2
    (6 7 8) 4
    (9 10 11) 8
    (throw (ex-info "eve.flat-io: no tuple of this column kind" {:kind kind}))))

(defn- elem
  "Element i (no bounds check, i an integer) of the tuple's column."
  [^Chunk c off kind i]
  (let [dv (.-dv c)]
    (case kind
      1 (.getUint8 dv (+ off i))
      2 (.getInt8 dv (+ off i))
      3 (.getUint8 dv (+ off i))
      4 (.getInt16 dv (+ off (* 2 i)) true)
      5 (.getUint16 dv (+ off (* 2 i)) true)
      6 (.getInt32 dv (+ off (* 4 i)) true)
      7 (.getUint32 dv (+ off (* 4 i)) true)
      8 (.getFloat32 dv (+ off (* 4 i)) true)
      9 (.getFloat64 dv (+ off (* 8 i)) true)
      10 (.getBigInt64 dv (+ off (* 8 i)) true)
      11 (.getBigUint64 dv (+ off (* 8 i)) true))))

(defn- elems
  "The tuple's elements as a JS array."
  [c off n kind]
  (let [a (make-array n)]
    (dotimes [i n] (aset a i (elem c off kind i)))
    a))

(defn- compare-elems [t o n]
  (loop [i 0]
    (if (< i n)
      (let [d (compare (nth t i) (nth o i))]
        (if (zero? d) (recur (inc i)) d))
      0)))

(deftype FlatTuple [^Chunk c off n kind ^:mutable __hash]
  Object
  (toString [coll] (pr-str* coll))
  (equiv [this other] (-equiv this other))

  ICloneable
  (-clone [_] (FlatTuple. c off n kind __hash))

  IMeta
  (-meta [_] nil)
  IWithMeta
  (-with-meta [_ m] (-with-meta (vec (elems c off n kind)) m))

  IStack
  (-peek [_] (when (pos? n) (elem c off kind (dec n))))
  (-pop [_] (-pop (vec (elems c off n kind))))

  ICollection
  (-conj [_ o] (-conj (vec (elems c off n kind)) o))

  IEmptyableCollection
  (-empty [_] [])

  ISequential

  IEquiv
  (-equiv [coll other]
    (cond
      (identical? coll other) true
      (and (vector? other) (counted? other))
      (and (== n (count other))
           (loop [i 0]
             (if (< i n)
               (if (= (elem c off kind i) (nth other i)) (recur (inc i)) false)
               true)))
      (sequential? other)
      (if (and (counted? other) (not (== n (count other))))
        false
        (loop [xs (seq coll) ys (seq other)]
          (cond (nil? xs) (nil? ys)
                (nil? ys) false
                (= (first xs) (first ys)) (recur (next xs) (next ys))
                :else false)))
      :else false))

  IHash
  (-hash [_coll]
    (if (some? __hash)
      __hash
      (let [h (loop [h 1 i 0]
                (if (< i n)
                  (recur (bit-or (+ (imul 31 h) (hash (elem c off kind i))) 0) (inc i))
                  (mix-collection-hash h n)))]
        (set! __hash h)
        h)))

  ISeqable
  (-seq [_] (when (pos? n) (array-seq (elems c off n kind) 0)))

  ICounted
  (-count [_] n)

  IIndexed
  (-nth [_ i]
    (if (and (<= 0 i) (< i n))
      (elem c off kind (bit-or i 0))
      (throw (js/Error. (str "No item " i " in vector of length " n)))))
  (-nth [_ i not-found]
    (if (and (<= 0 i) (< i n))
      (elem c off kind (bit-or i 0))
      not-found))

  ILookup
  (-lookup [coll k] (-lookup coll k nil))
  (-lookup [coll k not-found] (if (number? k) (-nth coll k not-found) not-found))

  IAssociative
  (-assoc [_ k v] (-assoc (vec (elems c off n kind)) k v))
  (-contains-key? [_ k] (if (integer? k) (and (<= 0 k) (< k n)) false))

  IFind
  (-find [_coll k]
    (when (and (integer? k) (<= 0 k) (< k n))
      (MapEntry. k (elem c off kind k) nil)))

  IVector
  (-assoc-n [_ i v] (-assoc-n (vec (elems c off n kind)) i v))

  IReduce
  (-reduce [_ f]
    (if (zero? n)
      (f)
      (loop [i 1 acc (elem c off kind 0)]
        (if (< i n)
          (let [acc (f acc (elem c off kind i))]
            (if (reduced? acc) @acc (recur (inc i) acc)))
          acc))))
  (-reduce [_ f init]
    (loop [i 0 acc init]
      (if (< i n)
        (let [acc (f acc (elem c off kind i))]
          (if (reduced? acc) @acc (recur (inc i) acc)))
        acc)))

  IKVReduce
  (-kv-reduce [_ f init]
    (loop [i 0 acc init]
      (if (< i n)
        (let [acc (f acc i (elem c off kind i))]
          (if (reduced? acc) @acc (recur (inc i) acc)))
        acc)))

  IFn
  (-invoke [coll k] (-nth coll k))
  (-invoke [coll k not-found] (-nth coll k not-found))

  IReversible
  (-rseq [coll] (when (pos? n) (RSeq. coll (dec n) nil)))

  IIterable
  (-iterator [_] (-iterator (vec (elems c off n kind))))

  IComparable
  (-compare [coll other]
    (if (vector? other)
      (let [on (count other)]
        (cond (< n on) -1
              (> n on) 1
              :else (compare-elems coll other n)))
      (throw (js/Error. (str "Cannot compare " coll " to " other)))))

  IPrintWithWriter
  ;; pr-writer is cljs.core's element writer for pr-sequential-writer;
  ;; it is defn- in ClojureScript (verified in the pinned 1.12.x
  ;; core.cljs) and cljs.core offers no public equivalent — this is
  ;; the standard IPrintWithWriter idiom, so the one :private-call
  ;; finding is ignored at the site.
  (-pr-writer [coll writer opts]
    #_{:clj-kondo/ignore [:private-call]}
    (pr-sequential-writer writer pr-writer "[" " " "]" opts coll)))

(defn tuple
  "The n elements of column kind kind (FORMAT-V1 §5: 1 u8 · 2 i8 · 3 u8c ·
   4 i16 · 5 u16 · 6 i32 · 7 u32 · 8 f32 · 9 f64 · 10 i64 · 11 u64) stored at
   data-off of chunk c, as a vector over the bytes: count, nth, get, seq,
   reduce, =, hash and print as the vector of its elements, with no copy. A
   write (assoc, conj, pop, with-meta) answers a persistent vector."
  [c data-off n kind]
  (kind-size kind)
  (FlatTuple. c data-off n kind nil))

(defn col
  "The column of n kind-kind elements at data-off of chunk c — the same view
   as tuple."
  [c data-off n kind]
  (tuple c data-off n kind))
