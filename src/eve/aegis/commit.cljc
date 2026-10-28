(ns eve.aegis.commit
  "G.2 content hiding on eve's crypto contract (aegis's clear evaluation, aegis/docs/simple-spike/REPORT.md): the room key, the sealed
   record and a HIDING content commitment. src/aegis/chat/content_encrypt.vs's header names the weakness this fixes:
   its commitment is Poseidon2(plaintext, iv), binding but not hiding, so whoever holds a record can confirm a guessed
   plaintext (a dictionary over \"yes\" / \"no\"). Here the commitment mixes the room key in, so only a member can
   confirm a guess, and the broker, which never sees the key, learns nothing from it.

     (room-key secret atom-id)       HKDF-SHA256(ikm = the 32-byte room secret, salt, info = the atom id), 32 bytes
     (seal key iv aad plaintext)     {:ciphertext :tag :commitment}: AES-256-CTR under the encryption subkey, then
                                     HMAC-SHA256 over aad ‖ iv ‖ ciphertext (16-byte tag) under the MAC subkey —
                                     encrypt-then-MAC from the contract's AES-CTR and HMAC (no GCM needed)
     (open key iv aad ciphertext tag) the plaintext bytes, or nil when the tag does not verify
     (commitment key iv plaintext)   HMAC-SHA256(key, label ‖ iv ‖ plaintext): the record field the leaf binds
   The iv (16 bytes) and the room secret are INPUTS: fresh random bytes from the caller."
  (:require [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

(def ^:private KDF-SALT (b/utf8 "aegis.chat.room-key.v3"))
(def ^:private COMMIT-LABEL (b/utf8 "aegis.chat.commit.v3"))

(defn hkdf-sha256 "RFC 5869 over the contract's HMAC-SHA256: L bytes" [ikm salt info L]
  (let [prk (crypto/hmac-sha256 (vec salt) (vec ikm))
        n (quot (+ L 31) 32)]
    (loop [i 1 t [] okm []]
      (if (> i n) (subvec okm 0 L)
          (let [t (crypto/hmac-sha256 prk (-> (vec t) (into (vec info)) (conj i)))]
            (recur (inc i) t (into okm t)))))))

(defn room-key [secret atom-id] (hkdf-sha256 (vec secret) KDF-SALT (b/utf8 (str atom-id)) 32))

(defn- subkey [key label] (hkdf-sha256 key (b/utf8 label) [] 32))

(defn commitment [key iv plaintext]
  (crypto/hmac-sha256 (vec key) (-> (vec COMMIT-LABEL) (into (vec iv)) (into (vec plaintext)))))

(defn seal [key iv aad plaintext]
  (when-not (= 16 (count iv)) (throw (ex-info "commit/seal: the iv is 16 bytes" {:count (count iv)})))
  (let [ct (crypto/aes256-ctr (subkey key "enc") (vec iv) (vec plaintext))
        tag (subvec (crypto/hmac-sha256 (subkey key "mac") (-> (vec aad) (into (vec iv)) (into ct))) 0 16)]
    {:ciphertext ct :tag tag :commitment (commitment key iv plaintext)}))

(defn open [key iv aad ciphertext tag]
  (let [tag' (subvec (crypto/hmac-sha256 (subkey key "mac") (-> (vec aad) (into (vec iv)) (into (vec ciphertext)))) 0 16)]
    (when (= (vec tag) tag')
      (crypto/aes256-ctr (subkey key "enc") (vec iv) (vec ciphertext)))))
