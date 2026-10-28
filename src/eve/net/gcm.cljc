(ns eve.net.gcm
  "AES-256-GCM (NIST SP 800-38D) in portable cljc, over an AES-256-CTR function — the crypto feature's
   `aes256-gcm-seal` / `aes256-gcm-open` rows where a host has no native GCM (eve.net.portable-crypto passes
   eve.net.aes's CTR; an adapter with a native CTR passes its own). For a 96-bit IV: H = E_K(0¹²⁸); J0 = IV ‖ 0³¹1;
   C = CTR(K, inc32(J0), P) (a CTR that counts the whole block up is inc32 for any message under 2³² blocks);
   T = E_K(J0) ⊕ GHASH_H(A ‖ 0* ‖ C ‖ 0* ‖ [len A]₆₄ ‖ [len C]₆₄). GHASH multiplies in GF(2¹²⁸) bit by bit
   (SP 800-38D Algorithm 1): variable-time, and slow per block — a fallback, not a fast path.

     (seal ctr key iv aad pt)      ciphertext ‖ 16-byte tag
     (open ctr key iv aad sealed)  the plaintext, or nil when the tag does not verify
   ctr: (fn [key32 iv16 data]) → data XOR the keystream. Bytes are vectors of 0..255.")

(defn- xor-bytes [a b] (mapv bit-xor a b))

(defn- shift-right-1 "a 16-byte block as a 128-bit big-endian value, shifted right one bit" [v]
  (mapv (fn [j] (+ (quot (nth v j) 2) (if (and (pos? j) (odd? (nth v (dec j)))) 128 0))) (range 16)))

(def ^:private R-BLOCK (into [0xe1] (repeat 15 0)))
(def ^:private ZERO-BLOCK (vec (repeat 16 0)))

(defn- gf-mul "X · Y in GF(2^128), GCM's bit order" [x y]
  (loop [i 0 z ZERO-BLOCK v y]
    (if (= i 128) z
        (let [xi (mod (quot (nth x (quot i 8)) (nth [128 64 32 16 8 4 2 1] (mod i 8))) 2)
              z (if (= 1 xi) (xor-bytes z v) z)
              v (if (odd? (nth v 15)) (xor-bytes (shift-right-1 v) R-BLOCK) (shift-right-1 v))]
          (recur (inc i) z v)))))

(defn- pad16 [bs] (let [r (mod (count bs) 16)] (if (zero? r) (vec bs) (into (vec bs) (repeat (- 16 r) 0)))))

(defn- u64-be [n] (mapv (fn [k] (mod (quot n (reduce * (repeat (- 7 k) 256))) 256)) (range 8)))

(defn- ghash [h data]
  (loop [i 0 y ZERO-BLOCK]
    (if (>= i (count data)) y
        (recur (+ i 16) (gf-mul (xor-bytes y (subvec data i (+ i 16))) h)))))

(defn- tag [ctr key j0 h aad ct]
  (let [s (ghash h (-> (pad16 aad) (into (pad16 ct)) (into (u64-be (* 8 (count aad)))) (into (u64-be (* 8 (count ct))))))]
    (vec (ctr key j0 s))))

(defn- setup [ctr key iv]
  (when-not (= 32 (count key)) (throw (ex-info "gcm: the key is 32 bytes" {:count (count key)})))
  (when-not (= 12 (count iv)) (throw (ex-info "gcm: the iv is 12 bytes" {:count (count iv)})))
  {:h (vec (ctr key ZERO-BLOCK ZERO-BLOCK)) :j0 (into (vec iv) [0 0 0 1]) :ctr1 (into (vec iv) [0 0 0 2])})

(defn seal "AES-256-GCM: ciphertext ‖ 16-byte tag" [ctr key iv aad pt]
  (let [key (vec key) {:keys [h j0 ctr1]} (setup ctr key (vec iv))
        ct (if (empty? pt) [] (vec (ctr key ctr1 (vec pt))))]
    (into ct (tag ctr key j0 h (vec aad) ct))))

(defn open "the plaintext of ciphertext ‖ tag, or nil when the tag does not verify" [ctr key iv aad sealed]
  (let [sealed (vec sealed) key (vec key)]
    (when (>= (count sealed) 16)
      (let [{:keys [h j0 ctr1]} (setup ctr key (vec iv))
            ct (subvec sealed 0 (- (count sealed) 16))]
        (when (= (subvec sealed (- (count sealed) 16)) (tag ctr key j0 h (vec aad) ct))
          (if (empty? ct) [] (vec (ctr key ctr1 ct))))))))
