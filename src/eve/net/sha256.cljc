(ns eve.net.sha256
  "SHA-256 (FIPS 180-4) in portable cljc over u32 words — the content address of every VSv2 block, value blob, snapshot
   and graft (eve/doc/network-atoms/WIRE.md §7), the same code on the JVM, bb, CLJS and any Clojure-reading host
   (IMPL-PLAN.md §6.0, §9 A11). Words are normalised with `mod` (eve.net.u64/u32), so a host whose bitwise operators
   answer signed 32-bit numbers (JS) and one whose answer 64-bit longs (the JVM) agree. A host overrides it through its
   platform map with its native digest (`MessageDigest`, `crypto.createHash`); this is what every override is checked
   against.

   Input: a byte sequence (`nth` / `count` read 0..255). Output: 32 bytes as a vector of 0..255, or 64 hex digits."
  (:require [eve.net.u64 :as u]))

(def ^:private K
  [0x428a2f98 0x71374491 0xb5c0fbcf 0xe9b5dba5 0x3956c25b 0x59f111f1 0x923f82a4 0xab1c5ed5
   0xd807aa98 0x12835b01 0x243185be 0x550c7dc3 0x72be5d74 0x80deb1fe 0x9bdc06a7 0xc19bf174
   0xe49b69c1 0xefbe4786 0x0fc19dc6 0x240ca1cc 0x2de92c6f 0x4a7484aa 0x5cb0a9dc 0x76f988da
   0x983e5152 0xa831c66d 0xb00327c8 0xbf597fc7 0xc6e00bf3 0xd5a79147 0x06ca6351 0x14292967
   0x27b70a85 0x2e1b2138 0x4d2c6dfc 0x53380d13 0x650a7354 0x766a0abb 0x81c2c92e 0x92722c85
   0xa2bfe8a1 0xa81a664b 0xc24b8b70 0xc76c51a3 0xd192e819 0xd6990624 0xf40e3585 0x106aa070
   0x19a4c116 0x1e376c08 0x2748774c 0x34b0bcb5 0x391c0cb3 0x4ed8aa4a 0x5b9cca4f 0x682e6ff3
   0x748f82ee 0x78a5636f 0x84c87814 0x8cc70208 0x90befffa 0xa4506ceb 0xbef9a3f7 0xc67178f2])

(def ^:private H0
  [0x6a09e667 0xbb67ae85 0x3c6ef372 0xa54ff53a 0x510e527f 0x9b05688c 0x1f83d9ab 0x5be0cd19])

(defn- rotr [x n] (u/rotl32 x (- 32 n)))
(defn- shr [x n] (quot x (nth u/POW2 n)))
(defn- add [& xs] (u/u32 (reduce + 0 xs)))
(defn- xor3 [a b c] (u/u32 (bit-xor a (bit-xor b c))))

(defn- block-words
  "the 16 big-endian words of the 64-byte block at offset o of the padded message"
  [m o]
  (mapv (fn [i] (let [j (+ o (* 4 i))]
                  (+ (* 16777216 (nth m j)) (* 65536 (nth m (+ j 1))) (* 256 (nth m (+ j 2))) (nth m (+ j 3)))))
        (range 16)))

(defn- schedule [w16]
  (loop [w w16]
    (if (= 64 (count w))
      w
      (let [i (count w)
            a (nth w (- i 15)) b (nth w (- i 2))
            s0 (xor3 (rotr a 7) (rotr a 18) (shr a 3))
            s1 (xor3 (rotr b 17) (rotr b 19) (shr b 10))]
        (recur (conj w (add (nth w (- i 16)) s0 (nth w (- i 7)) s1)))))))

(defn- compress [h w]
  (let [[a b c d e f g hh]
        (reduce (fn [[a b c d e f g hh] i]
                  (let [s1 (xor3 (rotr e 6) (rotr e 11) (rotr e 25))
                        ch (u/u32 (bit-xor (bit-and e f) (bit-and (- 4294967295 e) g)))
                        t1 (add hh s1 ch (nth K i) (nth w i))
                        s0 (xor3 (rotr a 2) (rotr a 13) (rotr a 22))
                        mj (u/u32 (bit-xor (bit-and a b) (bit-xor (bit-and a c) (bit-and b c))))
                        t2 (add s0 mj)]
                    [(add t1 t2) a b c (add d t1) e f g]))
                h (range 64))]
    (mapv add h [a b c d e f g hh])))

(defn- pad [bs]
  (let [n (count bs)
        bits (* 8 n)
        z (mod (- 55 n) 64)]
    (-> (vec bs) (conj 0x80) (into (repeat z 0))
        (into [0 0 0 (quot bits 4294967296)
               (quot (mod bits 4294967296) 16777216) (mod (quot bits 65536) 256) (mod (quot bits 256) 256) (mod bits 256)]))))

(defn sha256
  "the 32-byte digest of the bytes, as a vector of 0..255"
  [bs]
  (let [m (pad bs)
        h (reduce (fn [h o] (compress h (schedule (block-words m o)))) H0 (u/steps 0 (count m) 64))]
    (vec (mapcat (fn [x] [(quot x 16777216) (mod (quot x 65536) 256) (mod (quot x 256) 256) (mod x 256)]) h))))

(defn sha256-hex "the digest as 64 lower-case hex digits" [bs]
  (apply str (map (fn [b] (str (nth "0123456789abcdef" (quot b 16)) (nth "0123456789abcdef" (mod b 16)))) (sha256 bs))))
