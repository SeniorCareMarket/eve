(ns eve.crypto-bigint
  "The crypto feature's big-integer and edwards25519 rows over JavaScript's BigInt, shared by the Node adapter
   (crypto/node: ClojureScript on Node and nbb) and the browser's (crypto/browser): eve.net.bigops / eve.net.edwards
   over one OPS map. Written with the operators both the ClojureScript compiler and nbb's SCI apply to BigInts —
   * + - / (truncating) < = and bit-and / bit-shift-right — never zero? odd? mod or quot, whose 0 and Math.floor are
   Numbers. Held to eve.net.portable-crypto's rows by eve/test/eve/net/crypto_fast_path_test.cljc."
  (:require [eve.net.bigops :as bo]
            [eve.net.edwards :as edw]))

(defn- hex-of [bs] (apply str (map (fn [b] (let [h (.toString b 16)] (if (< b 16) (str "0" h) h))) bs)))
(defn- big [bs] (js/BigInt (str "0x0" (hex-of bs))))
(def ^:private ZERO (js/BigInt 0))
(def ^:private ONE (js/BigInt 1))
(defn- unbig "the big-endian len bytes of a non-negative BigInt" [x len]
  (let [h (.toString x 16)
        h (if (odd? (count h)) (str "0" h) h)
        v (vec (map (fn [i] (js/parseInt (subs h i (+ i 2)) 16)) (range 0 (count h) 2)))
        v (if (> (count v) len) (subvec v (- (count v) len)) v)]
    (into (vec (repeat (- len (count v)) 0)) v)))
(defn- bmod "a mod n, non-negative" [a n] (let [r (- a (* n (/ a n)))] (if (< r ZERO) (+ r n) r)))

(def OPS
  (bo/complete
   {:int (fn [n] (js/BigInt n))
    :from-be big :to-be unbig
    :add (fn [a b] (+ a b)) :sub (fn [a b] (- a b)) :mul (fn [a b] (* a b))
    :quot (fn [a b] (/ a b)) :mod bmod
    :even? (fn [a] (= (bit-and a ONE) ZERO)) :zero? (fn [a] (= a ZERO)) :eq (fn [a b] (= a b))}))
(def ^:private ED (edw/make OPS))

(defn modpow "base^exp mod n over big-endian bytes; the result is as long as n" [base exp n] (bo/modpow-bytes OPS base exp n))
(defn modmul "a·b mod n over big-endian bytes; the result is as long as n" [a b n] (bo/modmul-bytes OPS a b n))
(defn modinv "a^-1 mod n over big-endian bytes, or nil when gcd(a, n) ≠ 1" [a n] (bo/modinv-bytes OPS a n))
(defn ed25519-scalarmult "the encoding of [s]P: s 32 little-endian bytes, P a 32-byte encoding" [s p] ((:scalarmult ED) s p))
(defn ed25519-mul-add "the encoding of [a]P + [b]Q" [a p b q] ((:mul-add ED) a p b q))
(defn x25519 "X25519 (RFC 7748): the 32-byte u-coordinate of [k]u, k and u 32 little-endian bytes" [k u] ((:x25519 ED) k u))
(defn ed25519-rows
  "Ed25519's public key and signature (RFC 8032 §5.1.5–6, eve.net.edwards/ed25519-rows) over BigInt and the given SHA-512
   row: {:public-key (fn [seed]) :sign (fn [seed msg])} — for a platform with no Ed25519 of its own (a browser's is
   WebCrypto's, promise-only)"
  [sha512]
  (edw/ed25519-rows OPS ED sha512))
