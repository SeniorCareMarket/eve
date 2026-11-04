(ns eve.net.bigops
  "The crypto feature's big-integer rows over a host's own integers — written once, host-free, over the OPS map an
   adapter builds from its native type (eve.net.edwards takes the same map). An adapter supplies what its platform
   has natively and `complete` fills the rest: a square-and-multiply `:modpow` and an extended-Euclid `:modinv` over
   the map's own multiply, divide and mod.

   OPS (every value of the host's integer type T unless said otherwise):
     (:int ops n)          T of a small non-negative number      (:from-be ops bytes)   T of big-endian bytes
     (:to-be ops x len)    len big-endian bytes (0..255) of x     (:add ops a b) (:sub ops a b) (:mul ops a b)
     (:quot ops a b)       ⌊a / b⌋ for a, b ≥ 0                   (:mod ops a m)  a mod m in [0, m), a may be negative
     (:even? ops a) (:zero? ops a) (:eq ops a b)
     optional: (:modpow ops b e m)  (:modinv ops a m) -> T or nil when gcd ≠ 1
   The byte rows (what eve.crypto exposes): (modpow-bytes ops base exp n), (modmul-bytes ops a b n),
   (modinv-bytes ops a n) — big-endian byte vectors in, a vector as long as n out (nil for no inverse).")

(defn modpow-by "b^e mod m by square-and-multiply, from the top bit" [ops b e m]
  (let [{:keys [int mul mod quot even? zero?]} ops
        two (int 2)
        b (mod b m)]
    (loop [e e acc (int 1) base b]
      (if (zero? e) acc
          (recur (quot e two)
                 (if (even? e) acc (mod (mul acc base) m))
                 (mod (mul base base) m))))))

(defn modinv-by "a^-1 mod m by the extended Euclid, or nil" [ops a m]
  (let [{:keys [int sub mul mod quot zero? eq]} ops
        one (int 1)]
    (loop [r0 m r1 (mod a m) t0 (int 0) t1 one]
      (if (zero? r1)
        (when (eq r0 one) (mod t0 m))
        (let [q (quot r0 r1)]
          (recur r1 (sub r0 (mul q r1)) t1 (sub t0 (mul q t1))))))))

(defn complete "ops with :modpow and :modinv filled in where the adapter gave none" [ops]
  (cond-> ops
    (nil? (:modpow ops)) (assoc :modpow (fn [b e m] (modpow-by ops b e m)))
    (nil? (:modinv ops)) (assoc :modinv (fn [a m] (modinv-by ops a m)))))

(defn modpow-bytes [ops base exp n]
  (let [{:keys [from-be to-be modpow]} ops n' (from-be n)]
    (to-be (modpow (from-be base) (from-be exp) n') (count n))))

(defn modmul-bytes [ops a b n]
  (let [{:keys [from-be to-be mul mod]} ops n' (from-be n)]
    (to-be (mod (mul (mod (from-be a) n') (mod (from-be b) n')) n') (count n))))

(defn modinv-bytes [ops a n]
  (let [{:keys [from-be to-be modinv]} ops]
    (some-> (modinv (from-be a) (from-be n)) (to-be (count n)))))

;; byte conversions by arithmetic, for a host whose integers have no byte constructor worth the interop
(defn from-be-by "T of big-endian bytes, by multiply-add" [ops bs]
  (let [{:keys [int add mul]} ops b256 (int 256)]
    (reduce (fn [acc b] (add (mul acc b256) (int b))) (int 0) bs)))

(defn to-be-by "len big-endian bytes of x ≥ 0, by divide-mod; small-int is the host's T -> number for a value below 256" [ops small-int x len]
  (let [{:keys [int mod quot]} ops b256 (int 256)]
    (loop [i (dec len) x x out (vec (repeat len 0))]
      (if (neg? i) out
          (recur (dec i) (quot x b256) (assoc out i (small-int (mod x b256))))))))
