(ns eve.crypto
  "eve's crypto feature (adapters/README.md) in a browser: the digests, cipher and Ed25519's verification are
   eve.net.portable-crypto's (WebCrypto is promise-only, and eve's callers are synchronous), the big-integer and
   edwards25519 rows and Ed25519's public key and signature JavaScript's BigInt (eve.crypto-bigint, shared with the
   Node adapter: under Scittle a signature took 252 ms portable, 7.8 ms over BigInt, the same 64 bytes);
   random bytes are the process feature's. Bytes are vectors of 0..255 in and out."
  (:require [eve.crypto-bigint :as bi]
            [eve.net.portable-crypto :as p]
            [eve.process :as process]))

(defn sha256 "the 32-byte digest" [bs] (p/sha256 bs))
(defn hmac-sha256 "RFC 2104 over SHA-256" [key msg] (p/hmac-sha256 key msg))
(defn aes256-ctr "data XOR the AES-256-CTR keystream from the 16-byte iv under a 32-byte key" [key iv data] (p/aes256-ctr key iv data))
(defn xxh3-128 "the 16-byte XXH3-128 digest, big-endian hi then lo" [bs] (p/xxh3-128 bs))
(defn random-bytes "n bytes of the operating system's entropy" [n] (process/random-bytes n))
(def ^:private ED-ROWS (bi/ed25519-rows p/sha512))
(defn ed25519-public-key "the 32-byte public key of a 32-byte secret seed (BigInt)" [seed] ((:public-key ED-ROWS) seed))
(defn ed25519-sign "the 64-byte signature of the message under a 32-byte secret seed (BigInt)" [seed msg]
  ((:sign ED-ROWS) seed msg))
(defn ed25519-verify "is sig a valid signature of the message under the 32-byte public key" [pub msg sig] (p/ed25519-verify pub msg sig))
(defn sha384 "the 48-byte SHA-384 digest (portable)" [bs] (p/sha384 bs))
(defn modpow "base^exp mod n over big-endian bytes (BigInt)" [base exp n] (bi/modpow base exp n))
(defn modmul "a·b mod n over big-endian bytes (BigInt)" [a b n] (bi/modmul a b n))
(defn modinv "a^-1 mod n over big-endian bytes, or nil (BigInt)" [a n] (bi/modinv a n))
(defn ed25519-scalarmult "the encoding of [s]P (BigInt)" [s p] (bi/ed25519-scalarmult s p))
(defn ed25519-mul-add "the encoding of [a]P + [b]Q (BigInt)" [a p b q] (bi/ed25519-mul-add a p b q))
(defn x25519 "X25519 (RFC 7748, BigInt)" [k u] (bi/x25519 k u))
(defn sha512 "the 64-byte SHA-512 digest (portable)" [bs] (p/sha512 bs))
(defn aes256-gcm-seal "AES-256-GCM (portable)" [key iv aad pt] (p/aes256-gcm-seal key iv aad pt))
(defn aes256-gcm-open "the plaintext of AES-256-GCM ciphertext ‖ tag, or nil (portable)" [key iv aad sealed] (p/aes256-gcm-open key iv aad sealed))

(defn ed25519-map-to-point
  "the 32-byte encoding of clear_cofactor(map_to_curve(u0) + map_to_curve(u1)) on edwards25519 (RFC 9380 G.2.1–G.2.2),
   u0 and u1 32 little-endian bytes (bit 255 dropped) — eve.net.portable-crypto's map over this adapter's modpow"
  [u0 u1]
  (p/ed25519-map-to-point u0 u1 modpow))
