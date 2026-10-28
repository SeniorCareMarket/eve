(ns eve.crypto
  "eve's crypto feature (adapters/README.md) over Node's node:crypto — ClojureScript on Node and nbb: SHA-256
   (createHash), HMAC-SHA256 (createHmac), AES-256-CTR (createCipheriv \"aes-256-ctr\": OpenSSL's, the whole 16-byte
   counter incremented big-endian, as SP 800-38A F.5.5 and eve.net.aes), Ed25519 (sign / verify over KeyObjects made
   from the raw keys in their fixed DER prefixes; verify first refuses what eve.net.ed25519/well-formed? refuses), all
   synchronous. Node has no XXH3: that one
   is eve.net.portable-crypto's; random bytes are the process feature's. Bytes are vectors of 0..255 in and out;
   eve/test/eve/net/crypto_test.cljc holds this adapter to the portable code."
  (:require ["crypto" :as nc]
            [eve.crypto-bigint :as bi]
            [eve.net.portable-crypto :as p]
            [eve.net.ed25519 :as ed]
            [eve.process :as process]))

(defn- buf [bs] (js/Buffer.from (into-array bs)))
(defn- v [b] (vec (js/Array.from b)))

(defn sha256 "the 32-byte digest" [bs] (v (.digest (.update (.createHash nc "sha256") (buf bs)))))

(defn hmac-sha256 "RFC 2104 over SHA-256" [key msg]
  (v (.digest (.update (.createHmac nc "sha256" (buf key)) (buf msg)))))

(defn aes256-ctr "data XOR the AES-256-CTR keystream from the 16-byte iv under a 32-byte key" [key iv data]
  (let [c (.createCipheriv nc "aes-256-ctr" (buf key) (buf iv))]
    (v (js/Buffer.concat #js [(.update c (buf data)) (.final c)]))))

(defn xxh3-128 "the 16-byte XXH3-128 digest, big-endian hi then lo (portable: Node has none)" [bs] (p/xxh3-128 bs))
(defn random-bytes "n bytes of the operating system's entropy" [n] (process/random-bytes n))

;; RFC 8410's DER for a raw Ed25519 key: PrivateKeyInfo and SubjectPublicKeyInfo, the 32 bytes appended
(def ^:private PKCS8-PREFIX [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x04 0x22 0x04 0x20])
(def ^:private SPKI-PREFIX [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00])

(defn- private-key [seed]
  (let [seed (vec seed)]
    (when-not (= 32 (count seed))
      (throw (ex-info (str "ed25519: a seed is 32 bytes, not " (count seed)) {:count (count seed)})))
    (.createPrivateKey nc #js {:key (buf (into PKCS8-PREFIX seed)) :format "der" :type "pkcs8"})))

(defn ed25519-public-key "the 32-byte public key of a 32-byte secret seed" [seed]
  (subvec (v (.export (.createPublicKey nc (private-key seed)) #js {:format "der" :type "spki"})) 12))

(defn ed25519-sign "the 64-byte signature of the message under a 32-byte secret seed" [seed msg]
  (v (.sign nc nil (buf msg) (private-key seed))))

(defn ed25519-verify "is sig a valid signature of the message under the 32-byte public key" [pub msg sig]
  (boolean
   (and (ed/well-formed? pub sig)
        (try
          (.verify nc nil (buf msg) (.createPublicKey nc #js {:key (buf (into SPKI-PREFIX pub)) :format "der" :type "spki"})
                   (buf sig))
          ;; a key that decodes to no point
          (catch :default _ false)))))

;; ── the aegis clear-evaluation rows: SHA-384 (createHash); the big-integer and edwards25519
;; rows are eve.crypto-bigint's (adapters/crypto/js-bigint: BigInt, shared with the browser adapter)
(defn sha384 "the 48-byte SHA-384 digest" [bs] (v (.digest (.update (.createHash nc "sha384") (buf bs)))))
(defn modpow "base^exp mod n over big-endian bytes (BigInt)" [base exp n] (bi/modpow base exp n))
(defn modmul "a·b mod n over big-endian bytes (BigInt)" [a b n] (bi/modmul a b n))
(defn modinv "a^-1 mod n over big-endian bytes, or nil (BigInt)" [a n] (bi/modinv a n))
(defn ed25519-scalarmult "the encoding of [s]P (BigInt)" [s p] (bi/ed25519-scalarmult s p))
(defn ed25519-mul-add "the encoding of [a]P + [b]Q (BigInt)" [a p b q] (bi/ed25519-mul-add a p b q))
(defn x25519 "X25519 (RFC 7748, BigInt)" [k u] (bi/x25519 k u))
(defn sha512 "the 64-byte SHA-512 digest" [bs] (v (.digest (.update (.createHash nc "sha512") (buf bs)))))
(defn aes256-gcm-seal "AES-256-GCM (createCipheriv \"aes-256-gcm\", a 96-bit iv, a 128-bit tag): ciphertext ‖ tag" [key iv aad pt]
  (let [c (.createCipheriv nc "aes-256-gcm" (buf key) (buf iv))]
    (.setAAD c (buf aad))
    (v (js/Buffer.concat #js [(.update c (buf pt)) (.final c) (.getAuthTag c)]))))
(defn aes256-gcm-open "the plaintext of AES-256-GCM ciphertext ‖ tag, or nil when the tag does not verify" [key iv aad sealed]
  (let [s (vec sealed) n (count s)]
    (when (>= n 16)
      (try (let [d (.createDecipheriv nc "aes-256-gcm" (buf key) (buf iv))]
             (.setAAD d (buf aad))
             (.setAuthTag d (buf (subvec s (- n 16))))
             (v (js/Buffer.concat #js [(.update d (buf (subvec s 0 (- n 16)))) (.final d)])))
           (catch :default _ nil)))))

(defn ed25519-map-to-point
  "the 32-byte encoding of clear_cofactor(map_to_curve(u0) + map_to_curve(u1)) on edwards25519 (RFC 9380 G.2.1–G.2.2),
   u0 and u1 32 little-endian bytes (bit 255 dropped) — eve.net.portable-crypto's map over this adapter's modpow"
  [u0 u1]
  (p/ed25519-map-to-point u0 u1 modpow))
