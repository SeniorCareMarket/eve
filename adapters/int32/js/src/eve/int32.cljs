(ns eve.int32
  "32-bit integer arithmetic as eve's hashes take it (Murmur3, FNV-1a, a
   HAMT's bitmaps): signed results, the same on every host. Over
   JavaScript's: its bit operations are 32-bit, and Math.imul multiplies two
   such integers."
  (:refer-clojure :exclude [imul]))

(defn i32
  "x's low 32 bits, as a signed integer."
  [x]
  (bit-or x 0))

(defn imul
  "a × b mod 2^32, signed."
  [a b]
  (js/Math.imul a b))

(defn rotl
  "x's 32-bit pattern rotated left by r bits (0 < r < 32), signed."
  [x r]
  (bit-or (unsigned-bit-shift-right x (- 32 r)) (bit-shift-left x r)))

(defn ushr
  "x's 32-bit pattern shifted right by n bits, zeros in: non-negative."
  [x n]
  (unsigned-bit-shift-right x n))

(defn popcount
  "The number of bits set in x's 32-bit pattern."
  [x]
  (let [x (- x (bit-and (unsigned-bit-shift-right x 1) 0x55555555))
        x (+ (bit-and x 0x33333333) (bit-and (unsigned-bit-shift-right x 2) 0x33333333))
        x (bit-and (+ x (unsigned-bit-shift-right x 4)) 0x0f0f0f0f)]
    (unsigned-bit-shift-right (js/Math.imul x 0x01010101) 24)))
