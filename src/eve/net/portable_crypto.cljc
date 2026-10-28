(ns eve.net.portable-crypto
  "eve's crypto in portable cljc (eve/doc/network-atoms/ADAPTERS.md §4.1): SHA-256 (eve.net.sha256), HMAC-SHA256
   (RFC 2104 over it), AES-256-CTR (eve.net.aes), XXH3-128 (eve.net.xxh3), Ed25519 (eve.net.ed25519). The reference every native crypto adapter
   is held to byte for byte (eve/test/eve/net/crypto_test.cljc), the whole of the portable adapter, and what a native
   adapter delegates to for a primitive its platform lacks. Bytes are vectors of 0..255 in and out. Random bytes are
   not here: entropy is the operating system's (eve.process/random-bytes)."
  (:require [eve.net.bignum :as bn]
            [eve.net.sha512 :as sha512]
            [eve.net.sha256 :as sha]
            [eve.net.aes :as aes]
            [eve.net.gcm :as gcm]
            [eve.net.xxh3 :as xxh3]
            [eve.net.ed25519 :as ed]
            [eve.net.ell2 :as ell2]))

(defn sha256 "the 32-byte digest" [bs] (vec (sha/sha256 (vec bs))))

(defn hmac-sha256
  "RFC 2104 over SHA-256"
  [key msg]
  (let [k (vec key)
        k (if (> (count k) 64) (sha256 k) k)
        k (into k (repeat (- 64 (count k)) 0))]
    (sha256 (into (mapv (fn [x] (bit-xor x 0x5c)) k)
                  (sha256 (into (mapv (fn [x] (bit-xor x 0x36)) k) msg))))))

(defn aes256-ctr "data XOR the AES-256-CTR keystream from the 16-byte iv under a 32-byte key" [key iv data]
  (aes/aes256-ctr key iv data))

(defn xxh3-128 "the 16-byte XXH3-128 digest, big-endian hi then lo (as the kernel writes it)" [bs]
  (xxh3/xxh3-128 bs))

(defn ed25519-public-key "the 32-byte public key of a 32-byte secret seed (RFC 8032 §5.1.5)" [seed] (ed/public-key seed))
(defn ed25519-sign "the 64-byte signature of the message under a 32-byte secret seed (RFC 8032 §5.1.6)" [seed msg]
  (ed/sign seed msg))
(defn ed25519-verify "is sig a valid signature of the message under the 32-byte public key (RFC 8032 §5.1.7)" [pub msg sig]
  (ed/verify pub msg sig))

;; ── the aegis clear-evaluation rows (design: aegis/docs/simple-spike/REPORT.md): SHA-384 and modular big-integer arithmetic over
;; big-endian bytes, the portable reference every adapter's native row is held to

(defn sha384 "the 48-byte SHA-384 digest" [bs] (sha512/sha384 (vec bs)))
(defn modpow "base^exp mod n over big-endian bytes; the result is as long as n" [base exp n] (bn/modpow (vec base) (vec exp) (vec n)))
(defn modmul "a·b mod n over big-endian bytes; the result is as long as n" [a b n] (bn/modmul (vec a) (vec b) (vec n)))
(defn modinv "a^-1 mod n over big-endian bytes, or nil when gcd(a, n) ≠ 1" [a n] (bn/modinv (vec a) (vec n)))
(defn ed25519-scalarmult "the encoding of [s]P: s 32 little-endian bytes, P a 32-byte encoding" [s p] (ed/scalarmult-packed s p))
(defn ed25519-mul-add "the encoding of [a]P + [b]Q" [a p b q] (ed/mul-add a p b q))
(defn x25519 "X25519 (RFC 7748): the 32-byte u-coordinate of [k]u, k and u 32 little-endian bytes" [k u] (ed/x25519 (vec k) (vec u)))
(defn sha512 "the 64-byte SHA-512 digest" [bs] (sha512/sha512 (vec bs)))
(defn ed25519-map-to-point
  "the 32-byte encoding of clear_cofactor(map_to_curve(u0) + map_to_curve(u1)) on edwards25519 (RFC 9380 G.2.1–G.2.2:
   a _RO_ suite's steps after hash_to_field), u0 and u1 32 little-endian bytes (bit 255 dropped) — eve.net.ell2's map,
   its powers through `modpow` (an adapter passes its own big-integer row; this namespace's when none is given)"
  ([u0 u1] (ell2/map-to-point u0 u1 modpow))
  ([u0 u1 modpow] (ell2/map-to-point u0 u1 modpow)))
(defn aes256-gcm-seal "AES-256-GCM (SP 800-38D, 96-bit iv): ciphertext ‖ 16-byte tag" [key iv aad pt] (gcm/seal aes/aes256-ctr key iv aad pt))
(defn aes256-gcm-open "the plaintext of AES-256-GCM ciphertext ‖ tag, or nil when the tag does not verify" [key iv aad sealed]
  (gcm/open aes/aes256-ctr key iv aad sealed))
