(ns eve.net.aes
  "AES-256 (FIPS-197) and AES-256-CTR (SP 800-38A F.5.5) in portable cljc — the portable crypto adapter's cipher
   (adapters/crypto/portable) and the reference the native adapters are tested against. No host is named: the S-box
   is built by the generator walk over GF(2^8), every operation integer arithmetic on bytes (vectors of 0..255)."
  (:refer-clojure :exclude [transient persistent! conj!])
  (:require [eve.host :refer [transient persistent! conj!]]))

;; ── AES-256 (FIPS-197) ─────────────────────────────────────────────────────────────────────────────────────────

(defn- rotl8 [x n] (mod (+ (* x (nth [1 2 4 8 16 32 64 128] n)) (quot x (nth [1 2 4 8 16 32 64 128 256] (- 8 n)))) 256))
(defn- xtime [x] (bit-xor (mod (* 2 x) 256) (if (>= x 128) 27 0)))

(def ^:private SBOX
  ;; p runs through the powers of 3, q through their inverses
  (loop [p 1 q 1 sb (assoc (vec (repeat 256 0)) 0 99)]
    (let [p2 (bit-xor (bit-xor p (mod (* 2 p) 256)) (if (>= p 128) 27 0))
          q1 (bit-xor q (mod (* 2 q) 256))
          q2 (bit-xor q1 (mod (* 4 q1) 256))
          q3 (bit-xor q2 (mod (* 16 q2) 256))
          q4 (bit-xor q3 (if (>= q3 128) 9 0))
          x (bit-xor q4 (rotl8 q4 1) (rotl8 q4 2) (rotl8 q4 3) (rotl8 q4 4))
          sb2 (assoc sb p2 (bit-xor x 99))]
      (if (= p2 1) sb2 (recur p2 q4 sb2)))))

(def ^:private RCON [1 2 4 8 16 32 64])

(defn- expand-key
  "the 15 round keys (16 bytes each) of a 32-byte key"
  [key]
  (let [key (vec key)
        ws (loop [i 8 w (mapv (fn [j] (subvec key (* 4 j) (+ (* 4 j) 4))) (range 8))]
             (if (< i 60)
               (let [prev (nth w (dec i))
                     t (cond (zero? (mod i 8)) (let [sub (mapv (fn [x] (nth SBOX x)) [(nth prev 1) (nth prev 2) (nth prev 3) (nth prev 0)])]
                                                 (assoc sub 0 (bit-xor (nth sub 0) (nth RCON (dec (quot i 8))))))
                             (= 4 (mod i 8)) (mapv (fn [x] (nth SBOX x)) prev)
                             :else prev)]
                 (recur (inc i) (conj w (mapv bit-xor (nth w (- i 8)) t))))
               w))]
    (mapv (fn [r] (vec (mapcat identity (subvec ws (* 4 r) (+ (* 4 r) 4))))) (range 15))))

(def ^:private SHIFT (mapv (fn [i] (let [r (mod i 4) c (quot i 4)] (+ r (* 4 (mod (+ c r) 4))))) (range 16)))

(defn- mix-col [a0 a1 a2 a3]
  (let [x0 (xtime a0) x1 (xtime a1) x2 (xtime a2) x3 (xtime a3)]
    [(bit-xor x0 x1 a1 a2 a3) (bit-xor a0 x1 x2 a2 a3) (bit-xor a0 a1 x2 x3 a3) (bit-xor x0 a0 a1 a2 x3)]))

(defn- sub-shift [st] (mapv (fn [i] (nth SBOX (nth st (nth SHIFT i)))) (range 16)))
(defn- mix [st] (vec (mapcat (fn [c] (mix-col (nth st (* 4 c)) (nth st (+ (* 4 c) 1)) (nth st (+ (* 4 c) 2)) (nth st (+ (* 4 c) 3)))) (range 4))))
(defn- ark [st rk] (mapv bit-xor st rk))

(defn- encrypt-with [rks block]
  (let [st (loop [r 1 st (ark (vec block) (nth rks 0))]
             (if (< r 14) (recur (inc r) (ark (mix (sub-shift st)) (nth rks r))) st))]
    (ark (sub-shift st) (nth rks 14))))

(defn aes256-encrypt-block "one 16-byte block under a 32-byte key" [key block] (encrypt-with (expand-key key) block))

(defn- ctr-inc [ctr]
  (loop [i 15 c ctr]
    (if (neg? i) c (let [x (mod (inc (nth c i)) 256) c2 (assoc c i x)] (if (zero? x) (recur (dec i) c2) c2)))))

(defn aes256-ctr
  "data XOR the AES-256-CTR keystream from the 16-byte iv under a 32-byte key: the cipher in both directions; a partial
   last block is cut, not padded"
  [key iv data]
  (let [rks (expand-key key)
        data (vec data)
        n (count data)]
    (loop [i 0 ctr (vec iv) out (transient [])]
      (if (< i n)
        (let [ks (encrypt-with rks ctr)]
          (recur (+ i 16) (ctr-inc ctr)
                 (reduce (fn [o j] (conj! o (bit-xor (nth data (+ i j)) (nth ks j)))) out (range (min 16 (- n i))))))
        (persistent! out)))))

