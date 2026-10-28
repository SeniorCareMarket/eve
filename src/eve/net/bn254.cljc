(ns eve.net.bn254
  "The scalar field of BN254 (Fr, p = 0x30644e72e131a029b85045b68181585d2833e84879b9709143e1f593f0000001) in portable
   cljc, for aegis's Poseidon2 (eve.net.poseidon2) — the kernel's field_bn254.vs, moved into eve's crypto feature.
   An element is 16 limbs of radix 2^16, least significant first,
   each in [0, 2^16): every product is below 2^32 and every sum this code forms below 2^34, so a host whose numbers are
   doubles (ClojureScript) computes the same bits as one whose numbers are longs, the eve.net.ed25519 rule. Arithmetic
   splits with quot and mod, never a bit operation (ClojureScript's are 32-bit).

   Multiplication is Montgomery's (CIOS, R = 2^256): `mul` takes and answers the Montgomery form a·R mod p, as the
   kernel's montmul does, so Poseidon2 runs in that form end to end. `to-mont` brings any value below 2^256 in (one
   multiply by R^2, which also reduces it), `from-mont` takes one out. Bytes are vectors of 0..255."
  (:require [eve.host :refer [long-array aget aset longs long]])
  (:refer-clojure :exclude [long-array aget aset longs long]))

(def ^:private W 65536)
(def ^:private N 16)

(def ^:private HEX (zipmap ["0" "1" "2" "3" "4" "5" "6" "7" "8" "9" "a" "b" "c" "d" "e" "f"] (range 16)))

(defn from-hex
  "the limbs of a hex text (at most 64 lowercase digits, no 0x), exactly: four digits a limb (one-character strings,
   not chars: clojerl holds no char)"
  [h]
  (let [h (str (apply str (repeat (- 64 (count h)) "0")) h)]
    (mapv (fn [k]
            (let [e (- 64 (* 4 k))]
              (reduce (fn [acc i] (+ (* acc 16) (get HEX (subs h i (inc i))))) 0 (range (- e 4) e))))
          (range N))))

(def P (from-hex "30644e72e131a029b85045b68181585d2833e84879b9709143e1f593f0000001"))
(def ^:private R2 (from-hex "0216d0b17f4e44a58c49833d53bb808553fe3ab1e35c59e31bb8e645ae216da7"))
(def ^:private UNIT (vec (cons 1 (repeat 15 0))))
(def zero (vec (repeat N 0)))

(defn- geq-p? "is the 17-limb value [t0..t15, top] at least p" [t top]
  (if (pos? top)
    true
    (loop [i (dec N)]
      (cond (< i 0) true
            (> (nth t i) (nth P i)) true
            (< (nth t i) (nth P i)) false
            :else (recur (dec i))))))

(defn- sub-p "t − p for t ≥ p (17 limbs: [t0..t15] and top)" [t top]
  (loop [i 0 b 0 out []]
    (if (< i N)
      (let [x (- (nth t i) (nth P i) b)]
        (if (neg? x) (recur (inc i) 1 (conj out (+ x W))) (recur (inc i) 0 (conj out x))))
      [out (- top b)])))

(defn- reduce-once* "a 17-limb value below k·p brought below p" [t top]
  (loop [t t top top]
    (if (geq-p? t top) (let [[t top] (sub-p t top)] (recur t top)) t)))

(defn mul
  "Montgomery multiplication: a·b·R^-1 mod p (CIOS over 16-bit words, -p^-1 mod 2^16 = 0xFFFF). a and b below 2^256;
   the answer below p"
  [a b]
  (let [t (long-array 18)]
    (dotimes [i N]
      (let [bi (nth b i)]
        (loop [j 0 c 0]
          (if (< j N)
            (let [x (+ (aget ^longs t j) (* (nth a j) bi) c)]
              (aset ^longs t j (long (mod x W)))
              (recur (inc j) (quot x W)))
            (let [x (+ (aget ^longs t N) c)]
              (aset ^longs t N (long (mod x W)))
              (aset ^longs t (inc N) (long (+ (aget ^longs t (inc N)) (quot x W)))))))
        (let [m (mod (* (aget ^longs t 0) 65535) W)]
          (loop [j 1 c (quot (+ (aget ^longs t 0) (* m (nth P 0))) W)]
            (if (< j N)
              (let [x (+ (aget ^longs t j) (* m (nth P j)) c)]
                (aset ^longs t (dec j) (long (mod x W)))
                (recur (inc j) (quot x W)))
              (let [x (+ (aget ^longs t N) c)]
                (aset ^longs t (dec N) (long (mod x W)))
                (aset ^longs t N (long (+ (aget ^longs t (inc N)) (quot x W))))
                (aset ^longs t (inc N) (long 0))))))))
    (reduce-once* (mapv (fn [i] (aget ^longs t i)) (range N)) (aget ^longs t N))))

(defn add "a + b mod p, both below p" [a b]
  (loop [i 0 c 0 out []]
    (if (< i N)
      (let [x (+ (nth a i) (nth b i) c)] (recur (inc i) (quot x W) (conj out (mod x W))))
      (reduce-once* out c))))

(defn to-mont "the Montgomery form of a value below 2^256 (limbs), reduced mod p" [a] (mul a R2))
(defn from-mont "the canonical value of a Montgomery form" [a] (mul a UNIT))

(defn from-bytes-le "the limbs of up to 32 little-endian bytes" [bs]
  (let [bs (vec bs) b (fn [i] (if (< i (count bs)) (nth bs i) 0))]
    (mapv (fn [k] (+ (b (* 2 k)) (* 256 (b (inc (* 2 k)))))) (range N))))

(defn from-bytes-be "the limbs of up to 32 big-endian bytes (the last byte least significant)" [bs]
  (from-bytes-le (reverse (vec bs))))

(defn to-bytes-le "the 32 little-endian bytes of a canonical value" [a]
  (vec (mapcat (fn [x] [(mod x 256) (quot x 256)]) a)))

(defn to-bytes-be "the 32 big-endian bytes of a canonical value" [a] (vec (reverse (to-bytes-le a))))

(defn from-long "the limbs of a non-negative integer below 2^53" [x]
  (loop [i 0 x x out []] (if (< i N) (recur (inc i) (quot x W) (conj out (mod x W))) out)))
