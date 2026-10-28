(ns eve.net.edwards
  "edwards25519 group arithmetic over a host's own big integers — the fast path of the crypto feature's
   `ed25519-scalarmult` and `ed25519-mul-add` rows (the ring signature, eve.aegis.clsag: two scalar
   multiplications per ring member). Written once, host-free, over an OPS map an adapter builds from its native
   integer type (java.math.BigInteger, JavaScript's BigInt, Python's int, .NET's BigInteger, Erlang's integers, VS's
   BigInt tower, Chez's bignums); the portable fallback is eve.net.ed25519's 16-bit-limb arithmetic.

   OPS, every value of the host's integer type T unless said otherwise:
     (:int ops n)        T of a small non-negative number       (:from-be ops bytes)   T of big-endian bytes
     (:to-be ops x len)  len big-endian bytes (0..255) of x      (:add ops a b) (:sub ops a b) (:mul ops a b)
     (:mod ops a m)      a mod m in [0, m) for a possibly negative   (:modpow ops b e m)  b^e mod m
     (:even? ops a)      (:zero? ops a)      (:eq ops a b)
   (make ops) -> {:scalarmult (fn [scalar32-le point32]) :mul-add (fn [a P b Q]) :x25519 (fn [k u])}: scalars are 32 little-endian bytes
   (any value: the walk takes 256 bits), points their RFC 8032 encodings; each answers a 32-byte encoding, or nil
   when an input is not a point (no exception: shared code catches nothing host-specific). Variable-time (a fast path, not a constant-time one: aegis/docs/simple-spike/REPORT.md §7.2).

   The curve: p = 2^255 − 19, −x² + y² = 1 + d x² y², extended coordinates [X Y Z T] with the complete a = −1 addition
   (add-2008-hwcd-3, as eve.net.ed25519/padd); decoding per RFC 8032 §5.1.3 with sqrt by (p+3)/8 and √−1.

   (ed25519-rows ops ed sha512) -> {:public-key (fn [seed]) :sign (fn [seed msg])}: RFC 8032 §5.1.5–6 over (make ops)
   and the host's SHA-512 row — what an adapter whose platform has no Ed25519 of its own answers (.NET's)."
  (:require [eve.net.ed25519 :as ed]))

(def ^:private P-BE "p = 2^255 − 19, big-endian (pack25519 would reduce it to 0)" (-> [0x7f] (into (repeat 30 0xff)) (conj 0xed)))
(def ^:private D-BE (vec (rseq (ed/pack25519 ed/D))))
(def ^:private SQRTM1-BE (vec (rseq (ed/pack25519 ed/SQRTM1))))
(def ^:private BX-BE (vec (rseq (ed/pack25519 ed/BX))))
(def ^:private BY-BE (vec (rseq (ed/pack25519 ed/BY))))
(def ^:private E-SQRT-BE "(p − 5) / 8 = 2^252 − 3, big-endian: the exponent of u·v^7 in RFC 8032 §5.1.3" (-> [0x0f] (into (repeat 30 0xff)) (conj 0xfd)))

(defn make [ops]
  (let [{:keys [int from-be to-be add sub mul mod modpow even? zero? eq]} ops
        p (from-be P-BE) d (from-be D-BE) sqrtm1 (from-be SQRTM1-BE)
        zero (int 0) one (int 1) two (int 2) eight (int 8)
        fmul (fn [a b] (mod (mul a b) p))
        fadd (fn [a b] (mod (add a b) p))
        fsub (fn [a b] (mod (sub a b) p))
        inv (fn [a] (modpow a (sub p two) p))
        e-sqrt (from-be E-SQRT-BE)
        bx (from-be BX-BE) by (from-be BY-BE)
        base [bx by one (fmul bx by)]
        identity [zero one one zero]
        padd (fn [[x1 y1 z1 t1] [x2 y2 z2 t2]]
               (let [a (fmul (fsub y1 x1) (fsub y2 x2))
                     b (fmul (fadd y1 x1) (fadd y2 x2))
                     c (fmul (fmul t1 t2) (fmul two d))
                     dd (fmul (fmul z1 z2) two)
                     e (fsub b a) f (fsub dd c) g (fadd dd c) h (fadd b a)]
                 [(fmul e f) (fmul h g) (fmul g f) (fmul e h)]))
        bit (fn [s i] (clojure.core/mod (quot (nth s (quot i 8)) (nth [1 2 4 8 16 32 64 128] (clojure.core/mod i 8))) 2))
        smul (fn [q s]
               (loop [i 255 acc identity]
                 (if (neg? i) acc
                     (let [acc (padd acc acc)
                           acc (if (= 1 (bit s i)) (padd acc q) acc)]
                       (recur (dec i) acc)))))
        encode (fn [[x y z _]]
                 (let [zi (inv z) x (fmul x zi) y (fmul y zi)
                       yb (vec (rseq (to-be y 32)))]
                   (if (even? x) yb (assoc yb 31 (+ (nth yb 31) 128)))))
        decode (fn [bs]
                 (let [bs (vec bs)]
                   (when (= 32 (count bs))
                     (let [sign (quot (nth bs 31) 128)
                           yb (assoc bs 31 (clojure.core/mod (nth bs 31) 128))
                           y (from-be (vec (rseq yb)))]
                       (when (eq (mod y p) y)                      ; RFC 8032 §5.1.3: y < p
                         (let [y2 (fmul y y)
                               u (fsub y2 one)
                               v (fadd (fmul d y2) one)
                               ;; x = sqrt(u/v): u v^3 (u v^7)^((p−5)/8) is the candidate; times √−1 when x² v ≠ u
                               v3 (fmul (fmul v v) v)
                               v7 (fmul (fmul v3 v3) v)
                               x (fmul (fmul u v3) (modpow (fmul u v7) e-sqrt p))
                               x (if (eq (fmul (fmul x x) v) u) x (fmul x sqrtm1))]
                           (when (and (eq (fmul (fmul x x) v) u)
                                      (not (and (= 1 sign) (zero? x))))
                             (let [x (if (= sign (if (even? x) 0 1)) x (fsub zero x))]
                               [x y one (fmul x y)]))))))))
        a24 (int 121665)
        ;; X25519 (RFC 7748 §5): the Montgomery ladder over the same field, the x-only twin of smul
        x25519 (fn [k u]
                 (let [k (vec k) u (vec u)
                       k (-> k (assoc 0 (- (nth k 0) (clojure.core/mod (nth k 0) 8)))
                             (assoc 31 (+ 64 (clojure.core/mod (nth k 31) 64))))
                       x1 (mod (from-be (vec (rseq (assoc u 31 (clojure.core/mod (nth u 31) 128))))) p)]
                   (loop [t 254 x2 one z2 zero x3 x1 z3 one swap 0]
                     (if (neg? t)
                       (let [[x2 z2] (if (= swap 1) [x3 z3] [x2 z2])]
                         (vec (rseq (to-be (fmul x2 (inv z2)) 32))))
                       (let [kt (bit k t)
                             s (if (= swap kt) 0 1)
                             [x2 x3] (if (= s 1) [x3 x2] [x2 x3])
                             [z2 z3] (if (= s 1) [z3 z2] [z2 z3])
                             a (fadd x2 z2) aa (fmul a a) b (fsub x2 z2) bb (fmul b b) e (fsub aa bb)
                             c (fadd x3 z3) dd (fsub x3 z3) da (fmul dd a) cb (fmul c b)
                             x3 (let [v (fadd da cb)] (fmul v v))
                             z3 (let [v (fsub da cb)] (fmul x1 (fmul v v)))]
                         (recur (dec t) (fmul aa bb) (fmul e (fadd aa (fmul a24 e))) x3 z3 kt))))))]
    {:scalarmult (fn [s q] (when-let [q (decode q)] (encode (smul q (vec s)))))
     :mul-add (fn [a pp b qq] (let [pp (decode pp) qq (decode qq)]
                                (when (and pp qq) (encode (padd (smul pp (vec a)) (smul qq (vec b)))))))
     :x25519 x25519
     :base (encode base)}))

(def ^:private L-BE "the group order L = 2^252 + 27742317777372353535851937790883648493, big-endian"
  [0x10 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0x14 0xde 0xf9 0xde 0xa2 0xf7 0x9c 0xd6 0x58 0x12 0x63 0x1a 0x5c 0xf5 0xd3 0xed])

(defn ed25519-rows
  "Ed25519's public key and signature (RFC 8032 §5.1.5, §5.1.6) over ed = (make ops) and sha512 (bytes → 64 bytes): the
   seed's SHA-512, its clamped low half the secret scalar a, A = [a]B; r = SHA-512(high half ‖ M) mod L, R = [r]B,
   S = r + SHA-512(R ‖ A ‖ M)·a mod L, the signature R ‖ S. Deterministic, as the RFC; variable-time, as make's walk."
  [ops ed sha512]
  (let [{:keys [from-be to-be mul add mod]} ops
        l (from-be L-BE)
        le->int (fn [bs] (from-be (vec (rseq (vec bs)))))
        int->le32 (fn [x] (vec (rseq (vec (to-be x 32)))))
        expand (fn [seed]
                 (let [seed (vec seed)]
                   (when-not (= 32 (count seed)) (throw (ex-info "ed25519: a seed is 32 bytes" {:count (count seed)})))
                   (let [h (vec (sha512 seed))]
                     [(-> (subvec h 0 32) (update 0 (fn [x] (- x (clojure.core/mod x 8)))) (update 31 (fn [x] (+ 64 (clojure.core/mod x 64)))))
                      (subvec h 32 64)])))
        public-key (fn [a] ((:scalarmult ed) a (:base ed)))]
    {:public-key (fn [seed] (public-key (first (expand seed))))
     :sign (fn [seed msg]
             (let [[a prefix] (expand seed)
                   msg (vec msg)
                   pk (public-key a)
                   r (mod (le->int (sha512 (into prefix msg))) l)
                   big-r ((:scalarmult ed) (int->le32 r) (:base ed))
                   k (mod (le->int (sha512 (-> big-r (into pk) (into msg)))) l)]
               (into big-r (int->le32 (mod (add r (mul k (le->int a))) l)))))}))
