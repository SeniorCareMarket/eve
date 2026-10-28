(ns eve.net.sha512
  "SHA-512 (FIPS 180-4) in portable cljc over 64-bit words held as [hi lo] pairs of u32s (eve.net.u64) — the hash
   inside Ed25519 (RFC 8032 §5.1: the secret scalar and nonce prefix, the nonce, the challenge), the same code on every
   host that reads Clojure (IMPL-PLAN.md §6.0, §9 A11): every intermediate stays below 2^53, so a host whose numbers
   are doubles agrees with one whose numbers are longs. The constants were derived from their definitions (the first
   64 bits of the fractional parts of the cube roots of the first 80 primes, the square roots of the first 8) with
   Python integers, not copied.

   Input: a byte sequence (`nth` / `count` read 0..255). Output: 64 bytes as a vector of 0..255."
  (:require [eve.net.u64 :as u]))

(def ^:private K
  [[0x428a2f98 0xd728ae22] [0x71374491 0x23ef65cd] [0xb5c0fbcf 0xec4d3b2f] [0xe9b5dba5 0x8189dbbc]
   [0x3956c25b 0xf348b538] [0x59f111f1 0xb605d019] [0x923f82a4 0xaf194f9b] [0xab1c5ed5 0xda6d8118]
   [0xd807aa98 0xa3030242] [0x12835b01 0x45706fbe] [0x243185be 0x4ee4b28c] [0x550c7dc3 0xd5ffb4e2]
   [0x72be5d74 0xf27b896f] [0x80deb1fe 0x3b1696b1] [0x9bdc06a7 0x25c71235] [0xc19bf174 0xcf692694]
   [0xe49b69c1 0x9ef14ad2] [0xefbe4786 0x384f25e3] [0x0fc19dc6 0x8b8cd5b5] [0x240ca1cc 0x77ac9c65]
   [0x2de92c6f 0x592b0275] [0x4a7484aa 0x6ea6e483] [0x5cb0a9dc 0xbd41fbd4] [0x76f988da 0x831153b5]
   [0x983e5152 0xee66dfab] [0xa831c66d 0x2db43210] [0xb00327c8 0x98fb213f] [0xbf597fc7 0xbeef0ee4]
   [0xc6e00bf3 0x3da88fc2] [0xd5a79147 0x930aa725] [0x06ca6351 0xe003826f] [0x14292967 0x0a0e6e70]
   [0x27b70a85 0x46d22ffc] [0x2e1b2138 0x5c26c926] [0x4d2c6dfc 0x5ac42aed] [0x53380d13 0x9d95b3df]
   [0x650a7354 0x8baf63de] [0x766a0abb 0x3c77b2a8] [0x81c2c92e 0x47edaee6] [0x92722c85 0x1482353b]
   [0xa2bfe8a1 0x4cf10364] [0xa81a664b 0xbc423001] [0xc24b8b70 0xd0f89791] [0xc76c51a3 0x0654be30]
   [0xd192e819 0xd6ef5218] [0xd6990624 0x5565a910] [0xf40e3585 0x5771202a] [0x106aa070 0x32bbd1b8]
   [0x19a4c116 0xb8d2d0c8] [0x1e376c08 0x5141ab53] [0x2748774c 0xdf8eeb99] [0x34b0bcb5 0xe19b48a8]
   [0x391c0cb3 0xc5c95a63] [0x4ed8aa4a 0xe3418acb] [0x5b9cca4f 0x7763e373] [0x682e6ff3 0xd6b2b8a3]
   [0x748f82ee 0x5defb2fc] [0x78a5636f 0x43172f60] [0x84c87814 0xa1f0ab72] [0x8cc70208 0x1a6439ec]
   [0x90befffa 0x23631e28] [0xa4506ceb 0xde82bde9] [0xbef9a3f7 0xb2c67915] [0xc67178f2 0xe372532b]
   [0xca273ece 0xea26619c] [0xd186b8c7 0x21c0c207] [0xeada7dd6 0xcde0eb1e] [0xf57d4f7f 0xee6ed178]
   [0x06f067aa 0x72176fba] [0x0a637dc5 0xa2c898a6] [0x113f9804 0xbef90dae] [0x1b710b35 0x131c471b]
   [0x28db77f5 0x23047d84] [0x32caab7b 0x40c72493] [0x3c9ebe0a 0x15c9bebc] [0x431d67c4 0x9c100d4c]
   [0x4cc5d4be 0xcb3e42b6] [0x597f299c 0xfc657e2a] [0x5fcb6fab 0x3ad6faec] [0x6c44198c 0x4a475817]])

(def ^:private H0
  [[0x6a09e667 0xf3bcc908] [0xbb67ae85 0x84caa73b] [0x3c6ef372 0xfe94f82b] [0xa54ff53a 0x5f1d36f1]
   [0x510e527f 0xade682d1] [0x9b05688c 0x2b3e6c1f] [0x1f83d9ab 0xfb41bd6b] [0x5be0cd19 0x137e2179]])

(defn- rotr [x n] (u/or* (u/shr x n) (u/shl x (- 64 n))))
(defn- xor3 [a b c] (u/xor a (u/xor b c)))
(defn- add [& xs] (reduce u/add xs))

(defn- block-words
  "the 16 big-endian words of the 128-byte block at offset o of the padded message"
  [m o]
  (mapv (fn [i] (let [j (+ o (* 8 i))
                      w (fn [k] (+ (* 16777216 (nth m (+ j k))) (* 65536 (nth m (+ j k 1)))
                                   (* 256 (nth m (+ j k 2))) (nth m (+ j k 3))))]
                  [(w 0) (w 4)]))
        (range 16)))

(defn- schedule [w16]
  (loop [w w16]
    (if (= 80 (count w))
      w
      (let [i (count w)
            a (nth w (- i 15)) b (nth w (- i 2))
            s0 (xor3 (rotr a 1) (rotr a 8) (u/shr a 7))
            s1 (xor3 (rotr b 19) (rotr b 61) (u/shr b 6))]
        (recur (conj w (add (nth w (- i 16)) s0 (nth w (- i 7)) s1)))))))

(defn- compress [h w]
  (let [[a b c d e f g hh]
        (reduce (fn [[a b c d e f g hh] i]
                  (let [s1 (xor3 (rotr e 14) (rotr e 18) (rotr e 41))
                        ch (u/xor (u/and* e f) (u/and* (u/not* e) g))
                        t1 (add hh s1 ch (nth K i) (nth w i))
                        s0 (xor3 (rotr a 28) (rotr a 34) (rotr a 39))
                        mj (xor3 (u/and* a b) (u/and* a c) (u/and* b c))
                        t2 (u/add s0 mj)]
                    [(u/add t1 t2) a b c (u/add d t1) e f g]))
                h (range 80))]
    (mapv u/add h [a b c d e f g hh])))

(defn- pad [bs]
  ;; the message, 0x80, zeros to 112 mod 128, then the bit length as 16 big-endian bytes (a length below 2^53 bits:
  ;; the high eight are zero)
  (let [n (count bs)
        z (mod (- 111 n) 128)]
    (-> (vec bs) (conj 0x80) (into (repeat z 0)) (into (repeat 8 0)) (into (u/be-bytes (u/from-int (* 8 n)))))))

(def ^:private H0-384
  ;; FIPS 180-4 §5.3.4: the first 64 bits of the fractional parts of the square roots of the 9th through 16th primes
  [[0xcbbb9d5d 0xc1059ed8] [0x629a292a 0x367cd507] [0x9159015a 0x3070dd17] [0x152fecd8 0xf70e5939]
   [0x67332667 0xffc00b31] [0x8eb44a87 0x68581511] [0xdb0c2e0d 0x64f98fa7] [0x47b5481d 0xbefa4fa4]])

(defn- digest [h0 bs]
  (let [m (pad bs)
        h (reduce (fn [h o] (compress h (schedule (block-words m o)))) h0 (u/steps 0 (count m) 128))]
    (vec (mapcat u/be-bytes h))))

(defn sha512
  "the 64-byte digest of the bytes, as a vector of 0..255"
  [bs]
  (digest H0 bs))

(defn sha384
  "the 48-byte SHA-384 digest of the bytes (FIPS 180-4 §6.5: SHA-512's compression from its own initial value, the
   first six words of the result), as a vector of 0..255 — the hash of RFC 9474's RSABSSA-SHA384 variants"
  [bs]
  (subvec (digest H0-384 bs) 0 48))
