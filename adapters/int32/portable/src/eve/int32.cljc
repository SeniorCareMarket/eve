(ns eve.int32
  "32-bit integer arithmetic as eve's hashes take it (Murmur3, FNV-1a, a
   HAMT's bitmaps): signed results, the same on every host. For a host whose
   integers are wider than 32 bits and whose fixed-width casts cannot be
   relied on (ClojureCLR, basilisp, whose unchecked-* do not narrow, clojerl,
   whose integers are unbounded): values are kept in [0, 2^32) through quot,
   mod, * and + — every intermediate under 2^53 — and two-argument bit
   operations on them; no shift past 32 bits, no unsigned-bit-shift-right.")

(def ^:private TWO32 4294967296)
(def ^:private TWO31 2147483648)
(def ^:private pow2
  (loop [i 0 acc [] p 1]
    (if (> i 32) acc (recur (inc i) (conj acc p) (* p 2)))))

(defn- u32 [x] (mod x TWO32))

(defn i32
  "x's low 32 bits, as a signed integer."
  [x]
  (let [u (u32 x)] (if (>= u TWO31) (- u TWO32) u)))

(defn imul
  "a × b mod 2^32, signed."
  [a b]
  (let [a (u32 a) b (u32 b)]
    (i32 (+ (* (mod a 65536) b)
            (* (mod (* (quot a 65536) (mod b 65536)) 65536) 65536)))))

(defn rotl
  "x's 32-bit pattern rotated left by r bits (0 < r < 32), signed."
  [x r]
  (let [x (u32 x)]
    (i32 (+ (mod (* x (nth pow2 r)) TWO32)
            (quot x (nth pow2 (- 32 r)))))))

(defn ushr
  "x's 32-bit pattern shifted right by n bits, zeros in: non-negative."
  [x n]
  (quot (u32 x) (nth pow2 n)))

(defn popcount
  "The number of bits set in x's 32-bit pattern."
  [x]
  (let [x (u32 x)
        x (- x (bit-and (quot x 2) 0x55555555))
        x (+ (bit-and x 0x33333333) (bit-and (quot x 4) 0x33333333))
        x (bit-and (+ x (quot x 16)) 0x0f0f0f0f)]
    (mod (quot (* x 0x01010101) 16777216) 256)))
