(ns eve.aegis.h2c
  "Hashing to edwards25519 in portable cljc: RFC 9380's suite edwards25519_XMD:SHA-512_ELL2_RO_ (§8.5) — expand_message_xmd
   over SHA-512 (§5.3.1, the crypto feature's sha512 row), hash_to_field with L = 48 and two elements (§5.2), the straight-line Elligator 2 maps of
   Appendix G.2.1 (curve25519) and G.2.2 (the rational map to edwards25519), the two points added, the cofactor
   cleared by multiplication by 8 (§7, h_eff = 8) — the maps eve.net.ell2's, and hash-to-point's whole map through the
   crypto feature's ed25519-map-to-point row. Held to the RFC's Appendix J.5.1 and K.3 vectors by
   eve/test/eve/aegis/h2c_test.cljc. The field elements and points are eve.net.ed25519's (16-bit limbs, points
   [X Y Z T]); the exponentiations (the inversions, the square-root power) and the reduction of hash_to_field's bytes go
   through the crypto feature's big-integer rows (eve.crypto modpow / modmul: the host's native integers) — on
   ValueScript, where a program frees only what an atom's write replaces, one hash-to-curve over the limb arithmetic
   alone touched ~0.75 GB of slab (the ring probe, eve/test/eve/runner/vs_ring_probe.vs).
   aegis's CLSAG (eve.aegis.clsag) takes its per-scope key-image base from here.

     (expand-message-xmd msg dst len)   uniform bytes
     (hash-to-field msg dst count)      field elements (limbs)
     (hash-to-curve msg dst)            a point [X Y Z T] in the prime-order subgroup
     (hash-to-point msg dst)            its 32-byte encoding"
  (:require [eve.crypto :as crypto]
            [eve.net.bignum :as bn]
            [eve.net.bytes :as b]
            [eve.net.ed25519 :as ed]
            [eve.net.ell2 :as ell2]))

;; ── expand_message_xmd (SHA-512: b_in_bytes 64, s_in_bytes 128)

(defn expand-message-xmd [msg dst len]
  (let [msg (vec msg)
        dst (vec dst)
        ell (quot (+ len 63) 64)]
    (when (or (> ell 255) (> len 65535) (> (count dst) 255))
      (throw (ex-info "h2c: expand_message_xmd abort"
                      {:len len :dst (count dst)})))
    (let [dst' (conj dst (count dst))
          msg' (-> (vec (repeat 128 0))
                   (into msg)
                   (conj (quot len 256) (mod len 256))
                   (conj 0)
                   (into dst'))
          b0 (vec (crypto/sha512 msg'))
          b1 (vec (crypto/sha512 (-> b0 (conj 1) (into dst'))))]
      (loop [i 2
             prev b1
             out b1]
        (if (> i ell)
          (subvec out 0 len)
          (let [bi (vec (crypto/sha512 (-> (mapv bit-xor b0 prev)
                                           (conj i)
                                           (into dst'))))]
            (recur (inc i) bi (into out bi))))))))

;; ── the field and the map: eve.net.ell2's (moved there so every crypto adapter can map with its own modpow); here
;; through the crypto feature's modpow, as before

(def P-BE ell2/P-BE)
(def P-2-BE ell2/P-2-BE)
(def P58-BE ell2/P58-BE)
(def C2 ell2/C2)
(def C1-ED ell2/C1-ED)

(def ^:private P-LIMBS "p as a bignum" (bn/norm ed/P))

(defn- limbs16 "16 limbs of a bignum below p" [v] (into (vec v) (repeat (- 16 (count v)) 0)))

(defn os2ip-mod-p "the field element of big-endian bytes, reduced mod p" [bs]
  (ell2/be->fe (crypto/modmul (vec bs) [1] P-BE)))

(defn os2ip-mod-p-limbs "the same over eve.net.bignum's limbs (h2c_test's reference)" [bs]
  (limbs16 (bn/mod-limbs (bn/from-bytes-be bs) P-LIMBS)))

(defn hash-to-field "count elements of GF(p) (§5.2: L = 48)" [msg dst count]
  (let [u (expand-message-xmd msg dst (* 48 count))]
    (mapv (fn [i] (os2ip-mod-p (subvec u (* 48 i) (* 48 (inc i))))) (range count))))

(defn- fpow-bits "a^e for e the little-endian bytes of the exponent" [a e]
  (loop [i (dec (* 8 (count e))) acc ed/gf1]
    (if (neg? i) acc
        (let [acc (ed/fsq acc)
              acc (if (= 1 (ed/bit e i)) (ed/fmul acc a) acc)]
          (recur (dec i) acc)))))

(defn- sqrt-ed "√a for a square a, p ≡ 5 mod 8: a^((p+3)/8), times √−1 when that misses" [a]
  (let [r (ed/fmul a (ell2/fpow2523 crypto/modpow a))]
    (if (ell2/fe= (ed/fsq r) a) r (ed/fmul r ed/SQRTM1))))

(defn c2-computed "c2 = 2^((p+3)/8): (p+3)/8 = 2^252 − 2, as 32 little-endian bytes" []
  (fpow-bits (ell2/fe-of-int 2) (vec (rseq (bn/to-bytes-be (bn/sub (bn/from-bytes-be (into [16] (repeat 31 0))) [2]) 32)))))
(defn c1-ed-computed "c1 (edwards) = sqrt(−486664) with sgn0 = 0" []
  (let [r (sqrt-ed (ell2/fneg (ell2/fe-of-int 486664)))] (if (= 1 (ell2/sgn0 r)) (ell2/fneg r) r)))

(defn map-to-curve "Q = map_to_curve(u), a point [X Y Z T]" [u] (ell2/map-to-curve crypto/modpow u))

(defn point->affine "[x y] (limbs) of a point" [p] (ell2/point->affine crypto/modpow p))

(defn clear-cofactor "8·P: three doublings" [p] (ell2/clear-cofactor p))

(defn hash-to-curve "the suite's hash_to_curve: clear_cofactor(Q0 + Q1)" [msg dst]
  (let [[u0 u1] (hash-to-field msg dst 2)]
    (clear-cofactor (ed/padd (map-to-curve u0) (map-to-curve u1)))))

(defn hash-to-point
  "the 32-byte encoding of (hash-to-curve msg dst) — eve.net.ed25519/pack's bytes (y, x's parity in bit 255):
   hash_to_field's two elements as 32 little-endian bytes each, then the crypto feature's ed25519-map-to-point row (the
   maps, the sum, the cofactor and the encoding in the host's own code where its adapter has it: ed25519.wasm on
   ValueScript, where the limb map was ~80 ms a hash and filled the slab of a program that signed dozens of times —
   measured on ValueScript)"
  [msg dst]
  (let [u (expand-message-xmd msg dst 96)
        le (fn [i] (vec (rseq (vec (crypto/modmul (subvec u (* 48 i) (* 48 (inc i))) [1] P-BE)))))]
    (vec (crypto/ed25519-map-to-point (le 0) (le 1)))))

(defn fe-hex "the big-endian hex of a field element (as RFC 9380's vectors print one)" [a]
  (b/hex (vec (rseq (ed/pack25519 a)))))
