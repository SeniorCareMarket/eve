(ns eve.encoding
  "eve's encoding primitives (HOST-CONTRACT §3-4) over ValueScript's: UTF-8 and IEEE-754 bits through a cell of the
   program's own memory (a window from mmap/reserve!: the string arms write and read UTF-8 there, the f32/f64 and i64
   arms reinterpret bits there), its numbers, exceptions as ex-info, map entries, caches as atoms.
   A spike adapter: one thread (the cell is not shared safely), no instants, strings up to the cell's size."
  (:refer-clojure :exclude [aget aset alength byte-array unchecked-byte unsigned-bit-shift-right long double bytes])
  (:require [eve.host :refer [aget aset alength byte-array unchecked-byte unsigned-bit-shift-right long double
                              bytes]]))

(def ^:private CELL_BYTES 1000000)
(def ^:private -cell (atom nil))
(defn- cell []
  (or (deref -cell) (reset! -cell (mmap/reserve! CELL_BYTES))))

(defn throw-index-out-of-bounds [msg] (throw (ex-info msg {:eve/index-out-of-bounds true})))
(defn throw-unsupported [] (throw (ex-info "Unsupported operation" {:eve/unsupported true})))
(defn unsupported [msg] (ex-info msg {:eve/unsupported true}))
(defn map-entry [k v] (first {k v}))

(defn arraycopy [src soff dst doff len]
  (loop [i 0] (when (< i len) (aset dst (+ doff i) (aget src (+ soff i))) (recur (inc i)))))

(defn copy-of-range [b from to]
  (let [n (- to from) out (byte-array n)]
    (arraycopy b from out 0 n)
    out))

(defn utf8-bytes [s]
  (let [c (cell) n (mmap/bytes! c s) out (byte-array n)]
    (loop [i 0] (when (< i n) (aset out i (unchecked-byte (mmap/u8 (+ c i)))) (recur (inc i))))
    out))

(defn utf8-string [b off len]
  (let [c (cell)]
    (loop [i 0] (when (< i len) (mmap/u8! (+ c i) (bit-and (aget b (+ off i)) 0xFF)) (recur (inc i))))
    (mmap/bytes c len)))

(defn long-bits->double [x]
  (let [c (cell)] (mmap/i64! c x) (mmap/f64 c)))

(defn double->long-bits [d]
  (let [c (cell)] (mmap/f64! c d) (mmap/i64 c)))

(defn fixed-integer? [v] (int? v))
(defn floating? [v] (double? v))

;; The numbers past a fixed width (FORMAT-DECISIONS.md §8), over VS's own tower (VS's docs/semantics.md: Clojure's
;; BigInt, Ratio and BigDecimal in a user namespace): what they are here, and their parts as eve.codec writes them.
;; VS has no java.math interop (a BigDecimal answers no .scale), so a decimal's parts are read from its printed form.

(defn big-integer?
  "An integer of an arbitrary-precision type (VS's clojure.lang.BigInt): eve.codec writes it by its magnitude."
  [v]
  (and (integer? v) (not (int? v))))

(defn integer->twos
  "Integer n's minimal two's-complement bytes, big-endian, as a vector of 0..255."
  [n]
  (loop [x (bigint n) acc ()]
    (let [b (long (mod x 256))
          r (quot (- x b) 256)]
      (if (or (and (zero? r) (< b 128)) (and (= r -1) (>= b 128)))
        (vec (cons b acc))
        (recur r (cons b acc))))))

(defn twos->integer
  "The integer whose minimal two's-complement big-endian bytes are bs (0..255 each): a BigInt."
  [bs]
  (let [u (reduce (fn [acc b] (+' (*' acc 256) b)) (bigint 0) bs)]
    (bigint (if (and (seq bs) (>= (first bs) 128))
              (-' u (reduce (fn [acc _] (*' acc 256)) (bigint 1) bs))
              u))))

(defn ratio? "A ratio (RATIO)." [v] (clojure.core/ratio? v))

(defn ratio-parts
  "[numerator denominator] of a ratio: lowest terms, the denominator positive."
  [r]
  [(numerator r) (denominator r)])

(defn make-ratio "The ratio n/d (n, d integers in lowest terms, d > 1)." [n d] (/ (bigint n) (bigint d)))

(defn bigdec? "An arbitrary-precision decimal (BIGDEC)." [v] (decimal? v))

(defn- pow10 [k] (loop [i 0 p (bigint 1)] (if (< i k) (recur (inc i) (*' p 10)) p)))

(defn bigdec-parts
  "[unscaled scale] of decimal d in its canonical form: trailing zeros stripped down to scale 0 (so (= 1.5M 1.50M) has
   one form), never a negative scale — read from d's printed digits ([-]int[.frac][E[+-]exp])."
  [d]
  (let [s (str d)
        minus? (clojure.string/starts-with? s "-")
        s (if (or minus? (clojure.string/starts-with? s "+")) (subs s 1) s)
        e-at (or (clojure.string/index-of s "E") (clojure.string/index-of s "e"))
        mant (if e-at (subs s 0 e-at) s)
        ex (if e-at (parse-long (clojure.string/replace (subs s (inc e-at)) "+" "")) 0)
        dot (clojure.string/index-of mant ".")
        digits (if dot (str (subs mant 0 dot) (subs mant (inc dot))) mant)
        frac (if dot (- (count mant) dot 1) 0)
        u (bigint (reduce (fn [acc c] (+' (*' acc 10) (- (int c) 48))) (bigint 0) digits))]
    (loop [u (if minus? (-' u) u) sc (- frac ex)]
      (cond (zero? u) [(bigint 0) 0]
            (neg? sc) (recur (*' u 10) (inc sc))
            (and (pos? sc) (zero? (mod u 10))) (recur (quot u 10) (dec sc))
            :else [u sc]))))

(defn make-bigdec
  "The decimal unscaled x 10^-scale."
  [unscaled scale]
  (/ (bigdec unscaled) (bigdec (pow10 scale))))
(defn date? "VS's instant (a java.util.Date, as #inst reads): the one eve's DATE tag reads back as." [v] (inst? v))
(defn date-ms [d] (inst-ms d))
(defn ms->date
  "The instant at ms: VS's java.util.Date, the map its shim makes and #inst reads (VS's src/vs/core.vs's instant rows;
   vs.eve-disk builds it the same way)."
  [ms]
  {:vs-type "java.util.Date" :millis (long ms)})

(defn- nibble [c]
  (let [i (int c)] (cond (<= 48 i 57) (- i 48) (<= 97 i 102) (- i 87) :else (- i 55))))

(defn uuid->bytes! [u b off]
  (let [h (clojure.string/replace (str u) "-" "")]
    (dotimes [i 16]
      (aset b (+ off i) (unchecked-byte (+ (* 16 (nibble (nth h (* 2 i)))) (nibble (nth h (inc (* 2 i))))))))))

(defn bytes->uuid [b off]
  (let [hex "0123456789abcdef"
        h (apply str (map (fn [i] (let [x (bit-and (aget b (+ off i)) 0xFF)]
                                    (str (nth hex (quot x 16)) (nth hex (mod x 16)))))
                          (range 16)))]
    (uuid (str (subs h 0 8) "-" (subs h 8 12) "-" (subs h 12 16) "-" (subs h 16 20) "-" (subs h 20 32)))))

;; utf16-units as EM6's subset adapter wrote it (its docstring: what eve.net's codec and snapshot
;; read through eve.net.bytes): a code point past U+FFFF answers as its UTF-16 surrogate pair, as
;; every other host's row answers. The spike's one-liner here answered the code points themselves,
;; which diverges from the other rows on astral characters and would split the wire's bytes by host.
(defn utf16-units
  "The UTF-16 code units of string s, as integers: a code point past U+FFFF as its surrogate pair."
  [s]
  (vec (mapcat (fn [c] (let [cp (int c)]
                         (if (> cp 0xFFFF)
                           (let [v (- cp 0x10000)] [(+ 0xD800 (quot v 1024)) (+ 0xDC00 (mod v 1024))])
                           [cp])))
               s)))
(defn host-char? [v] (char? v))
(defn host-array? [v] false)

(defn new-cache [] (atom {}))
(defn cache-get [c k] (get @c k))
(defn cache-put! [c k v max]
  (swap! c (fn [m] (assoc (if (> (count m) max) {} m) k v)))
  v)

(defn bytes= [a b]
  (and (== (alength a) (alength b))
       (loop [i 0]
         (if (>= i (alength a)) true (if (not= (aget a i) (aget b i)) false (recur (inc i)))))))

(defn illegal-argument [msg] (ex-info msg {:eve/illegal-argument true}))
(defn index-out-of-bounds [msg] (ex-info msg {:eve/index-out-of-bounds true}))

(defn- bytes->cell [b off n]
  (let [c (cell)]
    (loop [i 0] (when (< i n) (mmap/u8! (+ c i) (bit-and (aget b (+ off i)) 0xFF)) (recur (inc i))))
    c))
(defn- cell->bytes [n]
  (let [c (cell) out (byte-array n)]
    (loop [i 0] (when (< i n) (aset out i (unchecked-byte (mmap/u8 (+ c i)))) (recur (inc i))))
    out))

(defn read-f32-le [b off] (mmap/f32 (bytes->cell b off 4)))
(defn read-f64-le [b off] (mmap/f64 (bytes->cell b off 8)))
(defn f32-le-bytes [v] (mmap/f32! (cell) v) (cell->bytes 4))
(defn f64-le-bytes [v] (mmap/f64! (cell) v) (cell->bytes 8))
(defn make-bytes [n] (byte-array n))
(defn bytes-of [xs] (byte-array (map (fn [x] (unchecked-byte x)) xs)))
(defn bytes-copy
  "A copy of byte array b, in b's own representation (what the codec and region read: a read's bytes, changed, handed
   on as read)."
  [b]
  (copy-of-range b 0 (alength b)))
(defn map-hash-code [m] (hash-unordered-coll m))

(defn write!
  "Write s (str of it) to the printer's writer w."
  [w s]
  (.write w (str s)))

;; eve.array's host views: VS has no typed array of its own (host-array? is false), so a fresh eve array is a slab
;; block (typed-array nil, as adapters/README.md's encoding row has it) and the little-endian runs are plain arrays.
(defn typed-array [subtype n] nil)
(defn typed-aget [a subtype i] (throw-unsupported))
(defn typed-aset! [a subtype i v] (throw-unsupported))
(defn array-subtype+bytes [a] nil)
(defn f64s-le [b off n]
  (let [out (double-array n)] (dotimes [i n] (aset out i (read-f64-le b (+ off (* 8 i))))) out))
(defn i32s-le [b off n]
  (let [out (int-array n)]
    (dotimes [i n]
      (let [o (+ off (* 4 i))
            u (bit-or (bit-and (aget b o) 0xFF) (bit-shift-left (bit-and (aget b (+ o 1)) 0xFF) 8)
                      (bit-shift-left (bit-and (aget b (+ o 2)) 0xFF) 16) (bit-shift-left (bit-and (aget b (+ o 3)) 0xFF) 24))]
        (aset out i (if (>= u 2147483648) (- u 4294967296) u))))
    out))
(defn f64s->bytes-le [da]
  (let [n (alength da) b (byte-array (* 8 n))]
    (dotimes [i n] (arraycopy (f64-le-bytes (aget da i)) 0 b (* 8 i) 8))
    b))
(defn i32s->bytes-le [ia]
  (let [n (alength ia) b (byte-array (* 4 n))]
    (dotimes [i n]
      (let [v (aget ia i)]
        (dotimes [j 4] (aset b (+ (* 4 i) j) (unchecked-byte (bit-and (bit-shift-right v (* 8 j)) 0xFF))))))
    b))
(defn code-points->string
  "The string of a sequence of Unicode code points."
  [cps]
  (apply str (map (fn [cp] (str (char cp))) cps)))

;; THE CODEC SLOTS (eve.net.bytes: bytes->b64, b64->bytes, bytes->hex, hex->bytes — a function here, or nil): VS has
;; no native codec for them, so each is nil and eve.net.bytes runs its portable reference, as the BEAM, CLR, Go and JS
;; adapters declare. Absent (main's codec-slot change fa41a1c98c added the slots to every adapter but this one), a VS
;; program's (if e/hex->bytes …) tripped at run time on the unresolved name: `run: form 571 eve.encoding/hex->bytes` in
;; seven net tests and three merkle-equality rows (M0, 2026-10-25, MEASURED).
(def bytes->b64 nil)
(def b64->bytes nil)
(def bytes->hex nil)
(def hex->bytes nil)
