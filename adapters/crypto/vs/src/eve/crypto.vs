(ns eve.crypto
  "eve's crypto feature (adapters/README.md) on ValueScript: SHA-256 is the kernel's (vs.core/rt-crypto-sha256, exact
   over a byte string, no slab), every other primitive eve's portable code (eve.net.portable-crypto). A compiled VS
   program has no entropy source (vs/docs/abi.md), so random-bytes refuses. Bytes are vectors of 0..255 in and out."
  (:require [eve.net.bigops :as bo]
            [eve.net.edwards :as edw]
            [eve.net.portable-crypto :as p]
            [vs.net.bytes :as vb]))

(defn sha256 "the 32-byte digest" [bs] (vb/ints (vs.core/rt-crypto-sha256 (vb/of-ints bs))))
(defn hmac-sha256
  "RFC 2104 over this adapter's sha256 (the kernel's): a key past the 64-byte block hashed first, then
   H((K ⊕ opad) ‖ H((K ⊕ ipad) ‖ msg)) — MEASURED 2026-10-26, the portable HMAC (eve.net.sha256 in cljc) was 95 ms of a
   chat post's client (HKDF's subkeys, aegis/docs/chat-peer/U8c-REPORT.md §6)"
  [key msg]
  (let [k (vec key)
        k (if (> (count k) 64) (sha256 k) k)
        k (into k (repeat (- 64 (count k)) 0))]
    (sha256 (into (mapv (fn [b] (bit-xor b 0x5c)) k)
                  (sha256 (into (mapv (fn [b] (bit-xor b 0x36)) k) (vec msg)))))))
(defn aes256-ctr "data XOR the AES-256-CTR keystream from the 16-byte iv under a 32-byte key" [key iv data]
  (p/aes256-ctr key iv data))
(defn xxh3-128 "the 16-byte XXH3-128 digest, big-endian hi then lo" [bs] (p/xxh3-128 bs))
(defn random-bytes "refused: a compiled VS program has no entropy source" [n]
  (throw (ex-info "eve.crypto/random-bytes: a compiled ValueScript program has no entropy source (vs/docs/abi.md)"
                  {:n n})))
(defn ed25519-public-key "the 32-byte public key of a 32-byte secret seed" [seed] (p/ed25519-public-key seed))
(defn ed25519-sign "the 64-byte signature of the message under a 32-byte secret seed" [seed msg] (p/ed25519-sign seed msg))
(defn ed25519-verify "is sig a valid signature of the message under the 32-byte public key" [pub msg sig]
  (p/ed25519-verify pub msg sig))

;; ── the aegis clear-evaluation rows (aegis/docs/simple-spike/REPORT.md): SHA-384 portable (the kernel has no SHA-384 row), the
;; big-integer and edwards25519 rows over VS's own BigInt tower (+' -' *' quot mod, as adapters/encoding/vs does)
;; through eve.net.bigops / eve.net.edwards
(def ^:private OPS
  (bo/complete
   {:int (fn [n] (bigint n))
    :from-be (fn [bs] (reduce (fn [acc b] (+' (*' acc 256) b)) (bigint 0) bs))
    :to-be (fn [x len] (bo/to-be-by {:int (fn [n] (bigint n)) :mod mod :quot quot} (fn [d] (long d)) x len))
    :add (fn [a b] (+' a b)) :sub (fn [a b] (-' a b)) :mul (fn [a b] (*' a b)) :quot quot :mod mod
    :even? (fn [a] (zero? (mod a 2))) :zero? zero? :eq =}))
(def ^:private ED (edw/make OPS))
(defn sha384 "the 48-byte SHA-384 digest" [bs] (p/sha384 bs))
(defn modpow "base^exp mod n over big-endian bytes; the result is as long as n" [base exp n] (bo/modpow-bytes OPS base exp n))
(defn modmul "a·b mod n over big-endian bytes; the result is as long as n" [a b n] (bo/modmul-bytes OPS a b n))
(defn modinv "a^-1 mod n over big-endian bytes, or nil when gcd(a, n) ≠ 1" [a n] (bo/modinv-bytes OPS a n))
(defn ed25519-scalarmult "the encoding of [s]P: s 32 little-endian bytes, P a 32-byte encoding" [s p] ((:scalarmult ED) s p))
(defn ed25519-mul-add "the encoding of [a]P + [b]Q" [a p b q] ((:mul-add ED) a p b q))
(defn x25519 "X25519 (RFC 7748): the 32-byte u-coordinate of [k]u, k and u 32 little-endian bytes" [k u] ((:x25519 ED) k u))
(defn sha512 "the 64-byte SHA-512 digest (portable)" [bs] (p/sha512 bs))
(defn aes256-gcm-seal "AES-256-GCM (portable)" [key iv aad pt] (p/aes256-gcm-seal key iv aad pt))
(defn aes256-gcm-open "the plaintext of AES-256-GCM ciphertext ‖ tag, or nil (portable)" [key iv aad sealed] (p/aes256-gcm-open key iv aad sealed))

(defn ed25519-map-to-point
  "the 32-byte encoding of clear_cofactor(map_to_curve(u0) + map_to_curve(u1)) on edwards25519 (RFC 9380 G.2.1–G.2.2),
   u0 and u1 32 little-endian bytes (bit 255 dropped) — eve.net.portable-crypto's map over this adapter's modpow"
  [u0 u1]
  (p/ed25519-map-to-point u0 u1 modpow))
