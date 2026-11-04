(ns eve.net.u64
  "Unsigned 64-bit arithmetic as PAIRS of unsigned 32-bit numbers [hi lo], the same on every host that reads Clojure
   (eve/doc/network-atoms/IMPL-PLAN.md §6.0, §9 A11): no reader conditional, no `long`-only operation, no BigInt. Every
   intermediate stays below 2^53 (a 32 × 32 product is taken in 16-bit pieces), so a host whose numbers are doubles (CLJS)
   computes the same bits as one whose numbers are longs (the JVM, bb). `u32` normalises a host's bitwise result — a
   signed 32-bit one on JS, a 64-bit one on the JVM — with `mod`, which floors on every host.

   A host may supply faster arithmetic through its platform map; these are the defaults every override is checked
   against.")

(def ^:const TWO32 4294967296)
(def ^:const TWO16 65536)

;; 2^0 … 2^32 as integers (a double from Math/pow would make the JVM's bit operations refuse it)
(def POW2 (vec (take 33 (iterate (fn [x] (* 2 x)) 1))))

(defn steps
  "from, from+step, … below to, as a seq — what (range from to step) answers, without it: clojerl 0.9's reduce over a
   stepped range whose span is shorter than its step yields nothing ((reduce conj [] (range 160 161 32)) is [] there,
   [160] elsewhere), and every map, mapv and reduce over one goes through that reduce"
  [from to step]
  (take-while (fn [i] (< i to)) (iterate (fn [i] (+ i step)) from)))

(defn u32 "x as an unsigned 32-bit number" [x] (mod x TWO32))

(defn of
  "[hi lo] of two u32 words"
  [hi lo]
  [(u32 hi) (u32 lo)])

(def ZERO [0 0])

(defn from-int "a non-negative integer below 2^53 as [hi lo]" [n] [(quot n TWO32) (mod n TWO32)])

(defn xor [[ah al] [bh bl]] [(u32 (bit-xor ah bh)) (u32 (bit-xor al bl))])
(defn or* [[ah al] [bh bl]] [(u32 (bit-or ah bh)) (u32 (bit-or al bl))])
(defn and* [[ah al] [bh bl]] [(u32 (bit-and ah bh)) (u32 (bit-and al bl))])
(defn not* [[h l]] [(- (dec TWO32) h) (- (dec TWO32) l)])

(defn add
  "a + b mod 2^64"
  [[ah al] [bh bl]]
  (let [l (+ al bl)]
    [(u32 (+ ah bh (if (>= l TWO32) 1 0))) (u32 l)]))

(defn neg "−a mod 2^64" [a] (add (not* a) [0 1]))

(defn sub "a − b mod 2^64" [a b] (add a (neg b)))

(defn- shl32 "x · 2^n mod 2^32 for x < 2^32, 0 ≤ n < 32" [x n] (* (mod x (nth POW2 (- 32 n))) (nth POW2 n)))

(defn shr
  "a >> n (logical), 0 ≤ n < 64"
  [[h l] n]
  (cond (zero? n) [h l]
        (< n 32) [(quot h (nth POW2 n))
                  (u32 (+ (quot l (nth POW2 n)) (shl32 h (- 32 n))))]
        :else [0 (quot h (nth POW2 (- n 32)))]))

(defn shl
  "a << n mod 2^64, 0 ≤ n < 64"
  [[h l] n]
  (cond (zero? n) [h l]
        (< n 32) [(u32 (+ (shl32 h n) (quot l (nth POW2 (- 32 n))))) (shl32 l n)]
        :else [(shl32 l (- n 32)) 0]))

(defn rotl32
  "the 32-bit rotation left of a u32 by n (0 < n < 32)"
  [x n]
  (u32 (+ (shl32 x n) (quot x (nth POW2 (- 32 n))))))

(defn mul32
  "the full product of two u32s as [hi lo] — in 16-bit pieces, every partial product below 2^32"
  [a b]
  (let [a1 (quot a TWO16) a0 (mod a TWO16)
        b1 (quot b TWO16) b0 (mod b TWO16)
        p00 (* a0 b0) p01 (* a0 b1) p10 (* a1 b0) p11 (* a1 b1)
        mid (+ p01 p10)                                  ;; < 2^33
        lo (+ p00 (* (mod mid TWO16) TWO16))             ;; < 2^33
        hi (+ p11 (quot mid TWO16) (quot lo TWO32))]
    [(u32 hi) (u32 lo)]))

(defn mul
  "a · b mod 2^64"
  [[ah al] [bh bl]]
  (let [[h l] (mul32 al bl)
        x (+ h (second (mul32 ah bl)) (second (mul32 al bh)))]
    [(u32 x) l]))

(defn mul128
  "the full 128-bit product of a and b as [hi64 lo64] — xxh3's mul64to128"
  [[ah al] [bh bl]]
  (let [[ll-h ll-l] (mul32 al bl)
        [hl-h hl-l] (mul32 ah bl)
        [lh-h lh-l] (mul32 al bh)
        [hh-h hh-l] (mul32 ah bh)
        ;; column sums, low to high, each below 2^35: no precision is lost
        c1 (+ ll-h hl-l lh-l)
        c2 (+ hl-h lh-h hh-l (quot c1 TWO32))
        c3 (+ hh-h (quot c2 TWO32))]
    [[(u32 c3) (u32 c2)] [(u32 c1) ll-l]]))

(defn from-le
  "[hi lo] of the 8 little-endian bytes of `bs` (a sequence of 0..255) at offset i"
  [bs i]
  (let [b (fn [k] (nth bs (+ i k)))]
    [(+ (b 4) (* 256 (b 5)) (* 65536 (b 6)) (* 16777216 (b 7)))
     (+ (b 0) (* 256 (b 1)) (* 65536 (b 2)) (* 16777216 (b 3)))]))

(defn u32-le "the u32 of the 4 little-endian bytes at offset i" [bs i]
  (+ (nth bs i) (* 256 (nth bs (inc i))) (* 65536 (nth bs (+ i 2))) (* 16777216 (nth bs (+ i 3)))))

(defn be-bytes "the 8 big-endian bytes of [hi lo]" [[h l]]
  (let [w (fn [x] [(quot x 16777216) (mod (quot x 65536) 256) (mod (quot x 256) 256) (mod x 256)])]
    (into (w h) (w l))))

(defn bswap32 "a u32's bytes reversed" [x]
  (+ (* (mod x 256) 16777216) (* (mod (quot x 256) 256) 65536) (* (mod (quot x 65536) 256) 256) (quot x 16777216)))

(defn bswap64 "[hi lo]'s 8 bytes reversed" [[h l]] [(bswap32 l) (bswap32 h)])

(defn hex32 "8 lower-case hex digits of a u32" [x]
  (apply str (map (fn [i] (nth "0123456789abcdef" (mod (quot x (nth POW2 (* 4 (- 7 i)))) 16))) (range 8))))

(defn hex "16 lower-case hex digits of [hi lo]" [[h l]] (str (hex32 h) (hex32 l)))
