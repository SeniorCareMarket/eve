(ns eve.int32
  "32-bit integer arithmetic as eve's hashes take it (Murmur3, FNV-1a, a
   HAMT's bitmaps): signed results, the same on every host. Over the JVM's
   (and babashka's and jolt's): longs, which unchecked-int narrows to their
   low 32 bits.")

(defn i32
  "x's low 32 bits, as a signed integer."
  [x]
  (long (unchecked-int x)))

(defn imul
  "a × b mod 2^32, signed."
  [a b]
  (long (unchecked-multiply-int (unchecked-int a) (unchecked-int b))))

(defn rotl
  "x's 32-bit pattern rotated left by r bits (0 < r < 32), signed."
  [x r]
  (let [x (bit-and x 0xFFFFFFFF)]
    (long (unchecked-int (bit-or (bit-shift-left x r) (unsigned-bit-shift-right x (- 32 r)))))))

(defn ushr
  "x's 32-bit pattern shifted right by n bits, zeros in: non-negative."
  [x n]
  (unsigned-bit-shift-right (bit-and x 0xFFFFFFFF) n))

(defn popcount
  "The number of bits set in x's 32-bit pattern."
  [x]
  (Long/bitCount (bit-and x 0xFFFFFFFF)))
