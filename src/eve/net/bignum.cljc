(ns eve.net.bignum
  "Unsigned big integers in portable cljc — the fallback behind the crypto feature's modpow / modmul / modinv (the
   aegis's clear-evaluation primitives, aegis/docs/simple-spike/REPORT.md): RSA-2048 blind signatures (RFC 9474) on a host whose adapter has no native
   big integer. A number is a vector of 16-bit limbs, little-endian, with no trailing zero limb (zero is []); every
   intermediate stays below 2^53, so a host whose numbers are doubles agrees with one whose numbers are longs
   (the convention of eve.net.sha512 and eve.net.ed25519).

   Modular multiplication and exponentiation are Montgomery's over an ODD modulus (an RSA modulus is); the inverse is
   HAC 14.64's binary inversion for an odd modulus, and for an EVEN modulus with an odd argument (e' mod phi(n), the
   partially-blind draft's DeriveKeyPair) the one-division trick: t = phi^-1 mod e', k = (t·phi − 1) / e',
   e'^-1 = phi − k. Division is shift-and-subtract (used once per inverse over an even modulus, never in modpow).

   The surface, over bytes big-endian (vectors of 0..255) as the crypto feature takes them:
     (modpow base exp n)   base^exp mod n            (modmul a b n)   a·b mod n
     (modinv a n)          a^-1 mod n, or nil when gcd(a, n) ≠ 1
     (mod* a n)            a mod n
   and over limbs for callers that stay in this representation: from-bytes-be / to-bytes-be, cmp, add, sub, mul,
   shr1, bit-length, odd?*. Costs are measured per host in aegis/docs/simple-spike/REPORT.md. Transients are eve.host's
   (a host without them — clojerl — supplies its own), as eve.net.bytes takes them."
  (:refer-clojure :exclude [transient persistent! conj! assoc!])
  (:require [eve.host :refer [transient persistent! conj! assoc!]]))

(def ^:private BASE 65536)

;; ── limbs

(defn norm "drop trailing zero limbs (a fresh vector: a subvector is not editable)" [v]
  (loop [v (if (vector? v) (into [] v) (vec v))] (if (and (seq v) (zero? (peek v))) (recur (pop v)) v)))

(defn from-bytes-be "the number of big-endian bytes" [bs]
  (let [bs (vec bs) n (count bs)]
    (norm (vec (for [i (range 0 n 2)]
                 (let [hi-i (- n 1 i) lo-i (- n 2 i)]
                   (+ (nth bs hi-i) (* 256 (if (neg? lo-i) 0 (nth bs lo-i))))))))))

(defn to-bytes-be "the number as len big-endian bytes (len must hold it)" [v len]
  (let [bs (vec (for [i (range len)]
                  (let [byte-i (- len 1 i) limb (quot byte-i 2)]
                    (if (< limb (count v))
                      (let [l (nth v limb)] (if (even? byte-i) (mod l 256) (quot l 256)))
                      0))))]
    (when (> (count v) (quot (+ len 1) 2))
      (throw (ex-info "bignum/to-bytes-be: the number does not fit" {:limbs (count v) :len len})))
    bs))

(defn from-int "a non-negative integer below 2^53" [n]
  (norm (loop [n n acc []] (if (zero? n) acc (recur (quot n BASE) (conj acc (mod n BASE)))))))

(defn to-int "the integer of a number below 2^53" [v] (reduce (fn [acc l] (+ (* acc BASE) l)) 0 (reverse (vec v))))

(defn cmp "−1, 0 or 1" [a b]
  (let [la (count a) lb (count b)]
    (cond (< la lb) -1 (> la lb) 1
          :else (loop [i (dec la)]
                  (if (neg? i) 0
                      (let [x (nth a i) y (nth b i)]
                        (cond (< x y) -1 (> x y) 1 :else (recur (dec i)))))))))

(defn add [a b]
  (let [n (max (count a) (count b))]
    (loop [i 0 carry 0 acc (transient [])]
      (if (< i n)
        (let [t (+ (if (< i (count a)) (nth a i) 0) (if (< i (count b)) (nth b i) 0) carry)]
          (recur (inc i) (quot t BASE) (conj! acc (mod t BASE))))
        (norm (persistent! (if (pos? carry) (conj! acc carry) acc)))))))

(defn sub "a − b for a ≥ b" [a b]
  (loop [i 0 borrow 0 acc (transient [])]
    (if (< i (count a))
      (let [t (- (nth a i) (if (< i (count b)) (nth b i) 0) borrow)]
        (if (neg? t)
          (recur (inc i) 1 (conj! acc (+ t BASE)))
          (recur (inc i) 0 (conj! acc t))))
      (do (when (pos? borrow) (throw (ex-info "bignum/sub: a < b" {})))
          (norm (persistent! acc))))))

(defn mul [a b]
  (if (or (empty? a) (empty? b)) []
      (let [la (count a) lb (count b)
            r (loop [i 0 r (vec (repeat (+ la lb) 0))]
                (if (< i la)
                  (let [ai (nth a i)
                        r (loop [j 0 carry 0 r (transient r)]
                            (if (< j lb)
                              (let [t (+ (nth r (+ i j)) (* ai (nth b j)) carry)]
                                (recur (inc j) (quot t BASE) (assoc! r (+ i j) (mod t BASE))))
                              (persistent! (assoc! r (+ i lb) (+ (nth r (+ i lb)) carry)))))]
                    (recur (inc i) r))
                  r))]
        (norm r))))

(defn shr1 "⌊a / 2⌋" [a]
  (norm (vec (map-indexed (fn [i l] (+ (quot l 2) (* 32768 (mod (if (< (inc i) (count a)) (nth a (inc i)) 0) 2)))) a))))

(defn shl1 "2a" [a] (add a a))

(defn odd?* [a] (and (seq a) (odd? (nth a 0))))

(defn bit-length [a]
  (if (empty? a) 0
      (let [top (peek a)]
        (+ (* 16 (dec (count a)))
           (loop [t top n 0] (if (zero? t) n (recur (quot t 2) (inc n))))))))

(defn- bit-at "bit i of a" [a i]
  (let [limb (quot i 16)]
    (if (< limb (count a)) (mod (quot (nth a limb) (nth [1 2 4 8 16 32 64 128 256 512 1024 2048 4096 8192 16384 32768] (mod i 16))) 2) 0)))

;; ── division (shift-and-subtract; not on the modpow path)

(defn divmod "[⌊a / b⌋, a mod b], b ≠ 0" [a b]
  (when (empty? b) (throw (ex-info "bignum/divmod: division by zero" {})))
  (if (neg? (cmp a b)) [[] a]
      (let [bits (bit-length a)]
        (loop [i (dec bits) q [] r []]
          (if (neg? i) [(norm q) r]
              (let [r (add (shl1 r) (if (= 1 (bit-at a i)) [1] []))
                    bit? (not (neg? (cmp r b)))
                    r (if bit? (sub r b) r)
                    limb (quot i 16)
                    q (if bit? (let [q (if (< limb (count q)) q (into q (repeat (- (inc limb) (count q)) 0)))]
                                 (update q limb + (nth [1 2 4 8 16 32 64 128 256 512 1024 2048 4096 8192 16384 32768] (mod i 16))))
                          q)]
                (recur (dec i) q r)))))))

(defn mod-limbs "a mod n" [a n] (if (neg? (cmp a n)) a (second (divmod a n))))

;; ── Montgomery arithmetic over an odd modulus n of k limbs

(defn- inv16 "x^-1 mod 2^16 for odd x (Newton over 2-adics)" [x]
  (loop [y x i 0]
    (if (= i 5) y
        (recur (mod (* y (mod (- 2 (mod (* x y) BASE)) BASE)) BASE) (inc i)))))

(defn mont-ctx "the Montgomery context of an odd modulus (bytes big-endian or limbs)" [n]
  (let [n (if (and (seq n) (> (count n) 0) (> (reduce max 0 n) 255)) (vec n) (from-bytes-be n))]
    (when-not (odd?* n) (throw (ex-info "bignum/mont-ctx: the modulus must be odd" {})))
    (let [k (count n)
          n0 (mod (- BASE (inv16 (nth n 0))) BASE)
          ;; R^2 mod n by 32k doublings
          r2 (loop [x [1] i 0]
               (if (= i (* 32 k)) x
                   (let [x (shl1 x)] (recur (if (neg? (cmp x n)) x (sub x n)) (inc i)))))]
      {:n n :k k :n0 n0 :r2 r2})))

(defn- redc "T·R^-1 mod n for T < n·R" [{:keys [n k n0]} t]
  ;; the working vector is built fresh (into [] …), never a transient of the input: a host whose transient shares
  ;; the input's tail (glojure 0.7.22, measured 2026-10-23, aegis/docs/simple-spike/REPORT.md §3a) would write through to the caller's value
  (let [t (loop [t (into [] (concat t (repeat (- (inc (* 2 k)) (count t)) 0))) i 0]
            (if (= i k) t
                (let [m (mod (* (nth t i) n0) BASE)
                      t (loop [j 0 carry 0 t (transient t)]
                          (if (< j k)
                            (let [x (+ (nth t (+ i j)) (* m (nth n j)) carry)]
                              (recur (inc j) (quot x BASE) (assoc! t (+ i j) (mod x BASE))))
                            ;; propagate the carry
                            (loop [p (+ i k) carry carry t t]
                              (if (zero? carry) (persistent! t)
                                  (let [x (+ (nth t p) carry)]
                                    (recur (inc p) (quot x BASE) (assoc! t p (mod x BASE))))))))]
                  (recur t (inc i)))))
        r (norm (into [] (subvec t k)))]
    (if (neg? (cmp r n)) r (sub r n))))

(defn mont-mul "a·b·R^-1 mod n for a, b < n" [ctx a b] (redc ctx (mul a b)))

(defn modmul-limbs "a·b mod n for a, b < n (ctx = mont-ctx n)" [ctx a b]
  (mont-mul ctx (mont-mul ctx a b) (:r2 ctx)))

(defn modpow-limbs "base^exp mod n for base < n (ctx = mont-ctx n)" [ctx base exp]
  (let [one-m (redc ctx (:r2 ctx))                 ; R mod n
        base-m (mont-mul ctx base (:r2 ctx))      ; base·R mod n
        bits (bit-length exp)]
    (loop [i (dec bits) acc one-m]
      (if (neg? i) (redc ctx acc)
          (let [acc (mont-mul ctx acc acc)
                acc (if (= 1 (bit-at exp i)) (mont-mul ctx acc base-m) acc)]
            (recur (dec i) acc))))))

;; ── inverses

(defn- half-mod "x/2 mod p for odd p" [x p]
  (if (odd?* x) (shr1 (add x p)) (shr1 x)))

(defn- sub-mod "(x − y) mod p" [x y p]
  (if (neg? (cmp x y)) (sub (add x p) y) (sub x y)))

(defn modinv-odd "a^-1 mod p for odd p and gcd(a, p) = 1, else nil (HAC 14.64)" [a p]
  (let [a (mod-limbs a p)]
    (if (empty? a) nil
        (loop [u a v p x1 [1] x2 []]
          (cond (= u [1]) x1
                (= v [1]) x2
                (odd?* u) (if (odd?* v)
                            (if (neg? (cmp u v))
                              (recur u (sub v u) x1 (sub-mod x2 x1 p))
                              (recur (sub u v) v (sub-mod x1 x2 p) x2))
                            (recur u (shr1 v) x1 (half-mod x2 p)))
                :else (recur (shr1 u) v (half-mod x1 p) x2))))))

(defn modinv-limbs "a^-1 mod n, or nil: n odd (HAC 14.64), or n even with a odd (one division)" [a n]
  (cond (odd?* n) (modinv-odd a n)
        (odd?* a) (let [t (modinv-odd n a)]          ; t·n ≡ 1 mod a
                    (when t
                      (let [[k _] (divmod (sub (mul t n) [1]) a)   ; k·a = t·n − 1 ⇒ k·a ≡ −1 mod n
                            k (mod-limbs k n)]
                        (if (empty? k) nil (sub n k)))))
        :else nil))

;; ── the byte surface

(defn- bytes-len [n] (count n))

(defn modpow "base^exp mod n over big-endian bytes; the result is as long as n" [base exp n]
  (let [ctx (mont-ctx n)
        b (mod-limbs (from-bytes-be base) (:n ctx))]
    (to-bytes-be (modpow-limbs ctx b (from-bytes-be exp)) (bytes-len n))))

(defn modmul "a·b mod n over big-endian bytes; the result is as long as n" [a b n]
  (let [ctx (mont-ctx n)]
    (to-bytes-be (modmul-limbs ctx (mod-limbs (from-bytes-be a) (:n ctx)) (mod-limbs (from-bytes-be b) (:n ctx)))
                 (bytes-len n))))

(defn modinv "a^-1 mod n over big-endian bytes, or nil when none exists; the result is as long as n" [a n]
  (let [nl (from-bytes-be n)]
    (some-> (modinv-limbs (mod-limbs (from-bytes-be a) nl) nl) (to-bytes-be (bytes-len n)))))

(defn mod* "a mod n over big-endian bytes; the result is as long as n" [a n]
  (to-bytes-be (mod-limbs (from-bytes-be a) (from-bytes-be n)) (bytes-len n)))

;; ── a key's arithmetic: the Montgomery context of n computed once (R^2 mod n is 32k doublings — the dominant cost of
;; a single modmul at 2048 bits), shared by every call under the same modulus; a local value, never module state

(defn cached-arith
  "{:modpow :modmul :modinv} over big-endian bytes for calls that share the modulus n (any other modulus falls back to
   the uncached rows)"
  [n]
  (let [n (vec n) ctx (mont-ctx n) nl (:n ctx) len (count n)]
    {:modpow (fn [base exp n'] (if (= (vec n') n)
                                 (to-bytes-be (modpow-limbs ctx (mod-limbs (from-bytes-be base) nl) (from-bytes-be exp)) len)
                                 (modpow base exp n')))
     :modmul (fn [a b n'] (if (= (vec n') n)
                            (to-bytes-be (modmul-limbs ctx (mod-limbs (from-bytes-be a) nl) (mod-limbs (from-bytes-be b) nl)) len)
                            (modmul a b n')))
     :modinv modinv}))
