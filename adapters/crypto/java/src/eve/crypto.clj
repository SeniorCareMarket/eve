(ns eve.crypto
  "eve's crypto feature (adapters/README.md) over the JVM's java.security and javax.crypto — the JVM and babashka:
   SHA-256 (MessageDigest), HMAC-SHA256 (Mac), AES-256-CTR (Cipher \"AES/CTR/NoPadding\": the whole 16-byte counter
   incremented big-endian, as SP 800-38A F.5.5 and eve.net.aes), Ed25519 signing and verifying (Signature \"Ed25519\",
   JDK 15+, the raw keys wrapped in their fixed DER prefixes; verify first refuses what eve.net.ed25519/well-formed?
   refuses, so a non-canonical encoding the JDK accepts cannot change the answer). Bytes are vectors of 0..255 in and
   out, as eve.net holds them; eve/test/eve/net/crypto_test.cljc holds this adapter to the portable one on the
   standards' vectors. The JDK has no XXH3, and no way from a raw seed to its public key: those are
   eve.net.portable-crypto's; random bytes are the process feature's (SecureRandom)."
  (:require [eve.net.bigops :as bo]
            [eve.net.edwards :as edw]
            [eve.net.portable-crypto :as p]
            [eve.net.ed25519 :as ed]
            [eve.process :as process])
  (:import [java.security MessageDigest KeyFactory Signature]
           [java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec]
           [javax.crypto Cipher Mac]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec IvParameterSpec]))

(defn- arr ^bytes [bs] (byte-array (map unchecked-byte bs)))
(defn- vec* [^bytes a] (mapv (fn [b] (bit-and b 255)) a))

(defn sha256 "the 32-byte digest" [bs] (vec* (.digest (MessageDigest/getInstance "SHA-256") (arr bs))))

(defn hmac-sha256 "RFC 2104 over SHA-256" [key msg]
  (let [k (arr key)
        m (Mac/getInstance "HmacSHA256")]
    ;; a zero-length key: the JCE refuses an empty SecretKeySpec, and HMAC pads it to 64 zero bytes anyway
    (.init m (SecretKeySpec. (if (zero? (alength k)) (byte-array 64) k) "HmacSHA256"))
    (vec* (.doFinal m (arr msg)))))

(defn aes256-ctr "data XOR the AES-256-CTR keystream from the 16-byte iv under a 32-byte key" [key iv data]
  (let [c (Cipher/getInstance "AES/CTR/NoPadding")]
    (.init c Cipher/ENCRYPT_MODE (SecretKeySpec. (arr key) "AES") (IvParameterSpec. (arr iv)))
    (vec* (.doFinal c (arr data)))))

(defn xxh3-128 "the 16-byte XXH3-128 digest, big-endian hi then lo (portable: the JDK has none)" [bs] (p/xxh3-128 bs))
(defn random-bytes "n bytes of the operating system's entropy" [n] (process/random-bytes n))

;; RFC 8410's DER for a raw Ed25519 key: PrivateKeyInfo and SubjectPublicKeyInfo, the 32 bytes appended
(def ^:private PKCS8-PREFIX [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x04 0x22 0x04 0x20])
(def ^:private SPKI-PREFIX [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00])

(declare sha512 ED)

(defn ed25519-public-key
  "the 32-byte public key of a 32-byte secret seed (RFC 8032 §5.1.5): the JCA derives none from a seed, so the clamped
   SHA-512 half (MessageDigest) times the base point over BigInteger (eve.net.edwards)"
  [seed]
  (let [seed (vec seed)]
    (when-not (= 32 (count seed)) (throw (ex-info "eve.crypto/ed25519-public-key: the seed is 32 bytes" {:count (count seed)})))
    (let [h (sha512 seed)
          s (-> (subvec h 0 32) (update 0 (fn [b] (- b (mod b 8)))) (update 31 (fn [b] (+ 64 (mod b 64)))))]
      ((:scalarmult ED) s (:base ED)))))

(defn ed25519-sign "the 64-byte signature of the message under a 32-byte secret seed" [seed msg]
  (let [seed (vec seed)]
    (when-not (= 32 (count seed))
      (throw (ex-info (str "ed25519: a seed is 32 bytes, not " (count seed)) {:count (count seed)})))
    (let [k (.generatePrivate (KeyFactory/getInstance "Ed25519") (PKCS8EncodedKeySpec. (arr (into PKCS8-PREFIX seed))))
          s (Signature/getInstance "Ed25519")]
      (.initSign s k)
      (.update s (arr msg))
      (vec* (.sign s)))))

(defn ed25519-verify "is sig a valid signature of the message under the 32-byte public key" [pub msg sig]
  (boolean
   (and (ed/well-formed? pub sig)
        (try
          (let [k (.generatePublic (KeyFactory/getInstance "Ed25519") (X509EncodedKeySpec. (arr (into SPKI-PREFIX pub))))
                s (Signature/getInstance "Ed25519")]
            (.initVerify s k)
            (.update s (arr msg))
            (.verify s (arr sig)))
          ;; a key that decodes to no point
          (catch Exception _ false)))))

;; ── the aegis clear-evaluation rows (aegis/docs/simple-spike/REPORT.md): SHA-384 (MessageDigest) and the big-integer and
;; edwards25519 rows over java.math.BigInteger through eve.net.bigops / eve.net.edwards (one OPS map), held to
;; eve.net.portable-crypto's rows by eve/test/eve/net/crypto_fast_path_test.cljc
(defn- big ^java.math.BigInteger [bs] (java.math.BigInteger. 1 (arr bs)))
(defn- unbig "the big-endian len bytes of a non-negative BigInteger" [^java.math.BigInteger x len]
  (let [v (vec* (.toByteArray x))
        v (if (> (count v) len) (subvec v (- (count v) len)) v)]
    (into (vec (repeat (- len (count v)) 0)) v)))
(def ^:private OPS
  (bo/complete
   {:int (fn [n] (java.math.BigInteger/valueOf (long n)))
    :from-be big :to-be unbig
    :add (fn [^java.math.BigInteger a ^java.math.BigInteger b] (.add a b))
    :sub (fn [^java.math.BigInteger a ^java.math.BigInteger b] (.subtract a b))
    :mul (fn [^java.math.BigInteger a ^java.math.BigInteger b] (.multiply a b))
    :quot (fn [^java.math.BigInteger a ^java.math.BigInteger b] (.divide a b))
    :mod (fn [^java.math.BigInteger a ^java.math.BigInteger m] (.mod a m))
    :modpow (fn [^java.math.BigInteger b ^java.math.BigInteger e ^java.math.BigInteger m] (.modPow b e m))
    :modinv (fn [^java.math.BigInteger a ^java.math.BigInteger m] (try (.modInverse a m) (catch ArithmeticException _ nil)))
    :even? (fn [^java.math.BigInteger a] (not (.testBit a 0)))
    :zero? (fn [^java.math.BigInteger a] (zero? (.signum a)))
    :eq (fn [^java.math.BigInteger a ^java.math.BigInteger b] (= 0 (.compareTo a b)))}))
(def ^:private ED (edw/make OPS))
(defn sha384 "the 48-byte SHA-384 digest" [bs] (vec* (.digest (MessageDigest/getInstance "SHA-384") (arr bs))))
(defn sha512 "the 64-byte SHA-512 digest" [bs] (vec* (.digest (MessageDigest/getInstance "SHA-512") (arr bs))))
(defn aes256-gcm-seal "AES-256-GCM (javax.crypto, a 96-bit iv, a 128-bit tag): ciphertext ‖ tag" [key iv aad pt]
  (let [c (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init c Cipher/ENCRYPT_MODE (SecretKeySpec. (arr key) "AES") (GCMParameterSpec. 128 (arr iv)))
    (.updateAAD c (arr aad))
    (vec* (.doFinal c (arr pt)))))
(defn aes256-gcm-open "the plaintext of AES-256-GCM ciphertext ‖ tag, or nil when the tag does not verify" [key iv aad sealed]
  (when (>= (count sealed) 16)
    (try (let [c (Cipher/getInstance "AES/GCM/NoPadding")]
           (.init c Cipher/DECRYPT_MODE (SecretKeySpec. (arr key) "AES") (GCMParameterSpec. 128 (arr iv)))
           (.updateAAD c (arr aad))
           (vec* (.doFinal c (arr sealed))))
         ;; AEADBadTagException (babashka exposes GCM but not that class): a tag that does not verify
         (catch Exception _ nil))))
(defn modpow "base^exp mod n over big-endian bytes; the result is as long as n" [base exp n] (bo/modpow-bytes OPS base exp n))
(defn modmul "a·b mod n over big-endian bytes; the result is as long as n" [a b n] (bo/modmul-bytes OPS a b n))
(defn modinv "a^-1 mod n over big-endian bytes, or nil when gcd(a, n) ≠ 1" [a n] (bo/modinv-bytes OPS a n))
(defn ed25519-scalarmult "the encoding of [s]P: s 32 little-endian bytes, P a 32-byte encoding" [s p] ((:scalarmult ED) s p))
(defn ed25519-mul-add "the encoding of [a]P + [b]Q" [a p b q] ((:mul-add ED) a p b q))
(defn x25519 "X25519 (RFC 7748): the 32-byte u-coordinate of [k]u, k and u 32 little-endian bytes" [k u] ((:x25519 ED) k u))

(defn ed25519-map-to-point
  "the 32-byte encoding of clear_cofactor(map_to_curve(u0) + map_to_curve(u1)) on edwards25519 (RFC 9380 G.2.1–G.2.2),
   u0 and u1 32 little-endian bytes (bit 255 dropped) — eve.net.portable-crypto's map over this adapter's modpow"
  [u0 u1]
  (p/ed25519-map-to-point u0 u1 modpow))
