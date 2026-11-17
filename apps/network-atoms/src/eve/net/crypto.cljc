(ns eve.net.crypto
  "G4 (eve/doc/network-atoms/IMPL-PLAN.md §6.4): blob encryption with a per-block key, in portable cljc — the contract
   of VS's src/vs/net/blob_crypto.vs (VSv1's crypto.js encryptBlob / decryptBlob, the kernel's enc.vs / aes.vs):

     block key = HKDF-SHA256(ikm = the 32-byte GAK, salt = the block's 16-byte nonce,
                             info = page-id u16 BE ‖ slot-idx u32 BE, L = 32)   — an all-zero nonce (a crypto-shredded
                                                                                   block) derives no key: nil
     IV        = SHA-256(block key)[0..16)
     cipher    = AES-256-CTR from the IV, the 16-byte counter incremented big-endian per block (SP 800-38A F.5.5);
                 encrypting and decrypting are the one transform.

   CTR does not authenticate: a blob is addressed by the SHA-256 of its plaintext, so `decrypt-blob` takes that address
   and throws when the recovered plaintext does not hash to it. Bytes are vectors of 0..255 (eve.net.bytes). The
   primitives — SHA-256, HMAC-SHA256, AES-256-CTR — are the host's crypto feature (eve.crypto: a native adapter where
   the host has one, adapters/crypto/portable's cljc elsewhere); HKDF and the per-block key are written here."
  (:require [eve.crypto :as prim]
            [eve.net.bytes :as b]))

;; ── HMAC-SHA256 (the host's) and HKDF-SHA256 (RFC 5869) ──────────────────────────────────────────────────────────

(defn hmac-sha256 "RFC 2104 over SHA-256 (the host's crypto feature)" [key msg] (prim/hmac-sha256 (vec key) (vec msg)))

(defn hkdf-sha256
  "n bytes of RFC 5869 extract-then-expand; an empty salt is 32 zero bytes"
  [ikm salt info n]
  (let [prk (hmac-sha256 (if (empty? salt) (vec (repeat 32 0)) salt) ikm)]
    (loop [t [] out [] ctr 1]
      (if (>= (count out) n)
        (subvec out 0 n)
        (let [t2 (hmac-sha256 prk (conj (into (vec t) info) ctr))] (recur t2 (into out t2) (inc ctr)))))))

;; ── the per-block key, the IV, blobs ────────────────────────────────────────────────────────────────────────────

(defn derive-block-key
  "the 32-byte key of one block, nil for an all-zero nonce (a crypto-shredded block)"
  [gak nonce page-id slot-idx]
  (when-not (every? zero? nonce)
    (hkdf-sha256 (vec gak) (vec nonce)
                 [(mod (quot page-id 256) 256) (mod page-id 256)
                  (mod (quot slot-idx 16777216) 256) (mod (quot slot-idx 65536) 256) (mod (quot slot-idx 256) 256) (mod slot-idx 256)]
                 32)))

(defn blob-iv "SHA-256(block key)[0..16)" [block-key] (subvec (vec (prim/sha256 (vec block-key))) 0 16))

(defn crypt-blob
  "the AES-256-CTR transform of a blob under the block key its ctx {:gak :nonce :page-id :slot-idx} names: encrypts a
   plaintext, decrypts a ciphertext; throws for a shredded block"
  [{:keys [gak nonce page-id slot-idx]} data]
  (let [k (derive-block-key gak nonce (or page-id 0) (or slot-idx 0))]
    (when (nil? k) (throw (ex-info "crypto: the block's nonce is all zero (crypto-shredded): no key" {:type :shredded})))
    (prim/aes256-ctr k (blob-iv k) data)))

(defn encrypt-blob [ctx plain] (crypt-blob ctx plain))

(defn decrypt-blob
  "the plaintext of a ciphertext; with sha (the 64-hex address of the plaintext) the recovered bytes must hash to it"
  ([ctx cipher] (crypt-blob ctx cipher))
  ([ctx cipher sha-hex]
   (let [plain (crypt-blob ctx cipher)]
     (if (= sha-hex (b/hex (prim/sha256 plain)))
       plain
       (throw (ex-info (str "blob authentication failed: the recovered bytes do not hash to " sha-hex)
                       {:type :authentication :sha sha-hex}))))))
