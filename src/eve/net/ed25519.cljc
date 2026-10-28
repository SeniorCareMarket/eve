(ns eve.net.ed25519
  "Ed25519 (RFC 8032 §5.1, PureEdDSA) in portable cljc — the broker's EdDSA JWTs and federation attestations
   (eve/doc/network-atoms/ADAPTERS.md §4.1, §4.6), the same code on every host that reads Clojure.
   It is TweetNaCl's construction, as the kernel's (VS's src/vs/kernel/wasm/ed25519.vs): a field element of GF(2^255
   − 19) is 16 limbs of radix 2^16, a point is [X Y Z T] in extended coordinates, scalars are reduced mod L over 64
   digits of radix 2^8. Every limb stays below 2^18 in magnitude between multiplies, so every product sum stays below
   2^47: a host whose numbers are doubles (ClojureScript) computes the same bits as one whose numbers are longs. The
   constants (d, 2d, sqrt(−1), the base point, L) were derived from their definitions with Python integers and match
   TweetNaCl's tables limb for limb.

   verify is the kernel's (ed25519.vs), in order, every refusal false: the key's y (bit 255 dropped) must be < p; S must
   be < L (no malleability); the key must decode to a point, and x = 0 with the sign bit set does not; k = SHA-512(R ‖ A
   ‖ M) mod L; valid iff encode([S]B + [k](−A)) = R byte for byte (the cofactorless check §5.1.7 permits). One refusal
   more than the kernel: a key of small order (SMALL-ORDER), which the platforms do not agree on. verify reads
   only public inputs. public-key and sign keep TweetNaCl's structure — the conditional swap and the final subtraction
   are arithmetic selects, not branches on a secret — but a host's arithmetic and allocation are not constant time: this
   is the reference a native adapter is held to, not a hardened signer.

   Bytes are vectors of 0..255 in and out: a seed and a public key 32 bytes, a signature 64."
  (:require [eve.net.bytes :as b]
            [eve.net.sha512 :as sha]
            [eve.net.u64 :as u]))

;; ── the field GF(2^255 − 19): 16 limbs of radix 2^16, value Σ limb_i · 2^(16 i)

(def gf0 [0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0])
(def gf1 [1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0])
(def D [0x78a3 0x1359 0x4dca 0x75eb 0xd8ab 0x4141 0x0a4d 0x0070 0xe898 0x7779 0x4079 0x8cc7 0xfe73 0x2b6f 0x6cee 0x5203])
(def D2 [0xf159 0x26b2 0x9b94 0xebd6 0xb156 0x8283 0x149a 0x00e0 0xd130 0xeef3 0x80f2 0x198e 0xfce7 0x56df 0xd9dc 0x2406])
(def SQRTM1 [0xa0b0 0x4a0e 0x1b27 0xc4ee 0xe478 0xad2f 0x1806 0x2f43 0xd7a7 0x3dfb 0x0099 0x2b4d 0xdf0b 0x4fc1 0x2480 0x2b83])
(def BX [0xd51a 0x8f25 0x2d60 0xc956 0xa7b2 0x9525 0xc760 0x692c 0xdc5c 0xfdd6 0xe231 0xc0a4 0x53fe 0xcd6e 0x36d3 0x2169])
(def BY [0x6658 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666 0x6666])
;; p's limbs, for the final subtraction
(def P [0xffed 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0xffff 0x7fff])
;; L = 2^252 + 27742317777372353535851937790883648493, little-endian bytes
(def L [0xed 0xd3 0xf5 0x5c 0x1a 0x63 0x12 0x58 0xd6 0x9c 0xf7 0xa2 0xde 0xf9 0xde 0x14
                  0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0x10])

(defn- fdiv "floor(x / d), d > 0 — `mod` floors on every host, so this is the same on all" [x d] (quot (- x (mod x d)) d))

(defn- down "n−1, n−2, … 0 (not a range with a negative step: eve.net.u64/steps)" [n] (map (fn [k] (- n 1 k)) (range n)))

(defn car
  "one carry pass (TweetNaCl's car25519): each limb into [0, 2^16), the top carry folded into limb 0 times 38
   (2^256 ≡ 38)"
  [o]
  (loop [i 0 c 1 out []]
    (if (< i 16)
      (let [v (+ (nth o i) c 65535)]
        (recur (inc i) (fdiv v 65536) (conj out (mod v 65536))))
      (assoc out 0 (+ (nth out 0) (* 38 (- c 1)))))))

(defn fadd [a b] (mapv + a b))
(defn fsub [a b] (mapv - a b))

(defn fmul
  "a · b: limb k of the product is Σ_{i+j=k} a_i b_j + 38 Σ_{i+j=k+16} a_i b_j, then two carry passes"
  [a b]
  (car (car (mapv (fn [k]
                    (+ (loop [i 0 s 0] (if (<= i k) (recur (inc i) (+ s (* (nth a i) (nth b (- k i))))) s))
                       (* 38 (loop [i (inc k) s 0] (if (< i 16) (recur (inc i) (+ s (* (nth a i) (nth b (- (+ k 16) i))))) s)))))
                  (range 16)))))

(defn fsq [a] (fmul a a))

(defn inv "a^(p−2)" [a]
  (reduce (fn [c i] (let [c (fsq c)] (if (or (= i 2) (= i 4)) c (fmul c a)))) a (down 254)))

(defn pow2523 "a^((p−5)/8)" [a]
  (reduce (fn [c i] (let [c (fsq c)] (if (= i 1) c (fmul c a)))) a (down 251)))

(defn select
  "x where b is 0, y where b is 1 — limb by limb, arithmetic"
  [x y b]
  (mapv (fn [xi yi] (+ xi (* b (- yi xi)))) x y))

(defn pack25519
  "the 32 little-endian bytes of the canonical value (in [0, p))"
  [n]
  (let [minus-p (fn [t _]
                  ;; t − p with borrows (the top limb unmasked); its sign says whether t < p, and t is kept then
                  (let [[m b] (loop [i 0 borrow 0 out []]
                                (let [mi (- (nth t i) (nth P i) borrow)
                                      bo (- (fdiv mi 65536))]
                                  (if (< i 15)
                                    (recur (inc i) bo (conj out (mod mi 65536)))
                                    [(conj out mi) bo])))]
                    (select m t b)))
        t (reduce minus-p (car (car (car n))) [0 1])]
    (vec (mapcat (fn [x] [(mod x 256) (fdiv x 256)]) t))))

(defn unpack25519
  "the field element of 32 little-endian bytes, bit 255 dropped"
  [bs]
  (let [o (mapv (fn [i] (+ (nth bs (* 2 i)) (* 256 (nth bs (inc (* 2 i)))))) (range 16))]
    (assoc o 15 (mod (nth o 15) 32768))))

(defn parity [a] (mod (nth (pack25519 a) 0) 2))

;; ── the group: points [X Y Z T], x = X/Z, y = Y/Z, xy = T/Z, on −x² + y² = 1 + d x² y²

(defn padd
  "P + Q — the complete a = −1 formula (add-2008-hwcd-3), doubling included"
  [[p0 p1 p2 p3] [q0 q1 q2 q3]]
  (let [a (fmul (fsub p1 p0) (fsub q1 q0))
        b (fmul (fadd p0 p1) (fadd q0 q1))
        c (fmul (fmul p3 q3) D2)
        d (let [d (fmul p2 q2)] (fadd d d))
        e (fsub b a) f (fsub d c) g (fadd d c) h (fadd b a)]
    [(fmul e f) (fmul h g) (fmul g f) (fmul e h)]))

(defn bit "bit i of a little-endian scalar" [s i]
  (mod (quot (nth s (quot i 8)) (nth u/POW2 (mod i 8))) 2))

(defn scalarmult
  "[s]q: a swap, an add and a double per bit, from bit 255 down"
  [q s]
  (loop [is (down 256) p [gf0 gf1 gf1 gf0] q q]
    (if-let [i (first is)]
      (let [b (bit s i)
            p1 (mapv (fn [x y] (select x y b)) p q)
            q1 (mapv (fn [x y] (select y x b)) p q)
            q2 (padd q1 p1)
            p2 (padd p1 p1)]
        (recur (rest is) (mapv (fn [x y] (select x y b)) p2 q2) (mapv (fn [x y] (select y x b)) p2 q2)))
      p)))

(defn scalarbase "[s]B" [s] (scalarmult [BX BY gf1 (fmul BX BY)] s))

(defn pack "the 32-byte encoding of a point: y, with x's parity in bit 255" [[x y z _]]
  (let [zi (inv z)
        r (pack25519 (fmul y zi))]
    (assoc r 31 (+ (nth r 31) (* 128 (parity (fmul x zi)))))))

(defn unpack-neg
  "−A for the encoding of A, or nil when it decodes to no point (RFC 8032 §5.1.3 steps 2–4)"
  [p]
  (let [y (unpack25519 p)
        num (fsub (fsq y) gf1)
        den (fadd gf1 (fmul (fsq y) D))
        den2 (fsq den)
        den6 (fmul (fsq den2) den2)
        t (pow2523 (fmul (fmul den6 num) den))
        x (fmul (fmul (fmul (fmul t num) den) den) den)
        x (if (= (pack25519 (fmul (fsq x) den)) (pack25519 num)) x (fmul x SQRTM1))
        sign (quot (nth p 31) 128)]
    (when (and (= (pack25519 (fmul (fsq x) den)) (pack25519 num))
               (not (and (= 1 sign) (= (pack25519 x) (pack25519 gf0)))))
      (let [x (if (= (parity x) sign) (fsub gf0 x) x)]
        [x y gf1 (fmul x y)]))))

;; ── scalars mod L

(defn mod-l
  "the 32 little-endian bytes of x mod L, for x 64 signed digits of radix 2^8 (TweetNaCl's modL)"
  [x]
  (let [x (reduce (fn [x i]
                    (let [xi (nth x i)
                          [x carry j] (loop [j (- i 32) carry 0 x x]
                                        (if (< j (- i 12))
                                          (let [v (+ (nth x j) carry (- (* 16 xi (nth L (- j (- i 32))))))
                                                c (fdiv (+ v 128) 256)]
                                            (recur (inc j) c (assoc x j (- v (* c 256)))))
                                          [x carry j]))]
                      (-> x (assoc j (+ (nth x j) carry)) (assoc i 0))))
                  (vec x) (map (fn [k] (- 63 k)) (range 32)))
        [x carry] (loop [j 0 carry 0 x x]
                    (if (< j 32)
                      (let [v (+ (nth x j) carry (- (* (fdiv (nth x 31) 16) (nth L j))))]
                        (recur (inc j) (fdiv v 256) (assoc x j (mod v 256))))
                      [x carry]))
        x (reduce (fn [x j] (assoc x j (- (nth x j) (* carry (nth L j))))) x (range 32))]
    (loop [i 0 x x out []]
      (if (< i 32)
        (let [xi (nth x i)]
          (recur (inc i) (assoc x (inc i) (+ (nth x (inc i)) (fdiv xi 256))) (conj out (mod xi 256))))
        out))))

(defn below-l? "is the 32-byte little-endian scalar < L" [s]
  (loop [i 31]
    (cond (neg? i) false
          (< (nth s i) (nth L i)) true
          (> (nth s i) (nth L i)) false
          :else (recur (dec i)))))

;; ── the operations

(defn seed-hash
  "SHA-512 of the seed, its first half clamped (RFC 8032 §5.1.5): [scalar prefix]"
  [seed]
  (let [seed (vec seed)]
    (when-not (= 32 (count seed))
      (throw (ex-info (str "ed25519: a seed is 32 bytes, not " (count seed)) {:count (count seed)})))
    (let [d (sha/sha512 seed)
          s (-> (subvec d 0 32)
                (assoc 0 (- (nth d 0) (mod (nth d 0) 8)))
                (assoc 31 (+ 64 (mod (nth d 31) 64))))]
      [s (subvec d 32 64)])))

(defn public-key
  "the 32-byte public key of a 32-byte secret seed (RFC 8032 §5.1.5)"
  [seed]
  (pack (scalarbase (first (seed-hash seed)))))

(defn sign
  "the 64-byte signature R ‖ S of the message under a 32-byte secret seed (RFC 8032 §5.1.6)"
  [seed msg]
  (let [[s prefix] (seed-hash seed)
        msg (vec msg)
        a (pack (scalarbase s))
        r (mod-l (sha/sha512 (into prefix msg)))
        rr (pack (scalarbase r))
        k (mod-l (sha/sha512 (-> rr (into a) (into msg))))
        ;; S = r + k·s mod L, as 64 digits
        x (reduce (fn [x i]
                    (reduce (fn [x j] (assoc x (+ i j) (+ (nth x (+ i j)) (* (nth k i) (nth s j))))) x (range 32)))
                  (into r (repeat 32 0)) (range 32))]
    (into rr (mod-l x))))

(def ^:private X0-SIGN
  ;; the two encodings of a point with x = 0 and the sign bit set — y = 1 and y = p − 1 (x² = 0 means y² = 1) — which
  ;; name no point (RFC 8032 §5.1.3 step 4)
  #{(into [0x01] (concat (repeat 30 0) [0x80])) (into [0xec] (concat (repeat 30 0xff) [0xff]))})

(def ^:private SMALL-ORDER
  ;; the canonical encodings of the eight points of order dividing 8 — the multiples of the order-8 point T8, computed
  ;; with Python integers. A small-order key A has [k]A = O for k ≡ 0 mod its order, so (R = [r]B, S = r) verifies
  ;; under it for one message in eight; the platforms disagree about them (measured: OpenSSL 3.0.13 and 4.0.2 accept
  ;; all eight, Node's OpenSSL 3.5.8 refuses all eight), so eve refuses them on every host. y < p and the x = 0 rule
  ;; already refuse every other encoding of these points.
  (set (map b/unhex
            ["0100000000000000000000000000000000000000000000000000000000000000"  ;; [0]T8, order 1 (the identity)
             "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"  ;; [1]T8, order 8
             "0000000000000000000000000000000000000000000000000000000000000000"  ;; [2]T8, order 4
             "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a"  ;; [3]T8, order 8
             "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"  ;; [4]T8, order 2
             "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa"  ;; [5]T8, order 8
             "0000000000000000000000000000000000000000000000000000000000000080"  ;; [6]T8, order 4
             "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85"  ;; [7]T8, order 8
             ])))

(defn well-formed?
  "the refusals verify makes on the bytes alone, before any curve arithmetic: a 32-byte key and a 64-byte signature,
   the key's y < p, S < L, not x = 0 with the sign bit set, and not a small-order key. A native adapter answers
   (and (well-formed? pub sig) (its platform's verify)), so a platform that accepts what eve refuses cannot change
   verify's answer"
  [pub sig]
  (let [pub (vec pub) sig (vec sig)]
    (and (= 32 (count pub))
         (= 64 (count sig))
         ;; y < p: the dropped-bit encoding survives the canonical round trip
         (= (pack25519 (unpack25519 pub)) (assoc pub 31 (mod (nth pub 31) 128)))
         (below-l? (subvec sig 32 64))
         (not (contains? X0-SIGN pub))
         (not (contains? SMALL-ORDER pub)))))

(defn verify
  "is sig a valid signature of the message under the 32-byte public key (RFC 8032 §5.1.7, cofactorless) — false for
   a key that is not 32 bytes or a signature that is not 64"
  [pub msg sig]
  (let [pub (vec pub) sig (vec sig)]
    (boolean
     (and (well-formed? pub sig)
          (when-let [neg-a (unpack-neg pub)]
            (let [k (mod-l (sha/sha512 (-> (subvec sig 0 32) (into pub) (into (vec msg)))))]
              (= (subvec sig 0 32) (pack (padd (scalarmult neg-a k) (scalarbase (subvec sig 32 64)))))))))))

;; ── the group rows of the crypto feature (eve.aegis.clsag's ring signature): the portable fallback of
;; eve.crypto/ed25519-scalarmult and ed25519-mul-add, over 32-byte encodings and 32-byte little-endian scalars

(defn unpack "the point of a 32-byte encoding, or nil (a y ≥ p is refused, as RFC 8032 §5.1.3 asks)" [p]
  (when (= 32 (count p))
    (let [p (vec p) yb (assoc p 31 (mod (nth p 31) 128))]
      (when (= yb (pack25519 (unpack25519 yb)))
        (when-let [[x y z t] (unpack-neg p)] [(fsub gf0 x) y z (fsub gf0 t)])))))

(defn scalarmult-packed "the encoding of [s]P for P an encoding, or nil when P is no point" [s p]
  (when-let [p (unpack p)] (pack (scalarmult p (vec s)))))

(defn mul-add "the encoding of [a]P + [b]Q for P, Q encodings, or nil when either is no point" [a p b q]
  (let [p (unpack p) q (unpack q)]
    (when (and p q) (pack (padd (scalarmult p (vec a)) (scalarmult q (vec b)))))))

;; ── X25519 (RFC 7748 §5), the Montgomery ladder of TweetNaCl's crypto_scalarmult over the same field

(def ^:private A24 "121665 = (486662 − 2) / 4, as limbs" [0xdb41 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0])

(defn- clamp25519 "decodeScalar25519: the low 3 bits and bit 255 cleared, bit 254 set (arithmetic, not bit ops)" [k]
  (-> (vec k) (assoc 0 (- (nth k 0) (mod (nth k 0) 8))) (assoc 31 (+ 64 (mod (nth k 31) 64)))))

(defn x25519
  "X25519(k, u): the 32-byte little-endian scalar and u-coordinate in (bit 255 of u dropped, a u ≥ p reduced), the
   32-byte u-coordinate of [clamp(k)]·u out. The all-zero output (u of small order) is returned, not refused: the
   caller checks it (RFC 7748 §6.1, RFC 9180 §7.1.4)."
  [k u]
  (let [z (clamp25519 (vec k)) x (unpack25519 (vec u))
        sw (fn [p q r] [(select p q r) (select q p r)])]
    (loop [i 254 a gf1 b x c gf0 d gf1]
      (if (neg? i)
        (pack25519 (fmul a (inv c)))
        (let [r (bit z i)
              [a b] (sw a b r) [c d] (sw c d r)
              e (fadd a c) a (fsub a c) c (fadd b d) b (fsub b d)
              d (fsq e) f (fsq a) a (fmul c a) c (fmul b e)
              e (fadd a c) a (fsub a c) b (fsq a) c (fsub d f)
              a (fmul c A24) a (fadd a d) c (fmul c a) a (fmul d f)
              d (fmul b x) b (fsq e)
              [a b] (sw a b r) [c d] (sw c d r)]
          (recur (dec i) a b c d))))))
