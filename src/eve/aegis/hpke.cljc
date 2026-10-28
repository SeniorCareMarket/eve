(ns eve.aegis.hpke
  "HPKE (RFC 9180) base mode for aegis's read regions (the read-regions spike, aegis/docs/read-regions-spike/REPORT.md):
   the suite DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM — kem 0x0020, kdf 0x0001, aead 0x0002 — over eve's
   crypto contract, in portable cljc. Held to the CFRG's published HPKE vectors (the base-mode entry of this suite in
   cfrg/draft-irtf-cfrg-hpke test-vectors.json) and AES-256-GCM to NIST SP 800-38D's test cases 13–16, by
   eve/test/eve/aegis/hpke_test.cljc.

   AES-256-GCM is the crypto contract's aes256-gcm-seal / aes256-gcm-open rows: a host's native GCM (javax.crypto,
   node:crypto), else eve.net.gcm (SP 800-38D over the host's AES-256-CTR, GHASH in portable code).

     (gcm-seal key iv aad pt)      ciphertext ‖ 16-byte tag
     (gcm-open key iv aad sealed)  the plaintext, or nil when the tag does not verify
     (derive-key-pair ikm)         {:sk :pk} (RFC 9180 §7.1.3, X25519)
     (public-key sk)               X25519(sk, 9)
     (seal pk-r info aad pt ikm-e) {:enc :ct}: single-shot base mode (§6.1), the ephemeral key derived from ikm-e — 32
                                   fresh random bytes from the caller (a compiled program has no entropy source)
     (open sk-r enc info aad ct)   the plaintext, or nil (a bad tag, or a small-order enc: the all-zero DH is refused)
     (key-schedule shared-secret info)  {:key :base-nonce :exporter-secret ...} (§5.1, mode_base)
   Bytes are vectors of 0..255 throughout; nothing here keeps module state."
  (:require [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

;; ── AES-256-GCM: the crypto contract's rows (a host's native GCM, else eve.net.gcm over its AES-256-CTR)

(defn gcm-seal "AES-256-GCM: ciphertext ‖ 16-byte tag" [key iv aad pt]
  (when-not (= 32 (count key)) (throw (ex-info "hpke/gcm: the key is 32 bytes" {:count (count key)})))
  (when-not (= 12 (count iv)) (throw (ex-info "hpke/gcm: the iv is 12 bytes" {:count (count iv)})))
  (vec (crypto/aes256-gcm-seal (vec key) (vec iv) (vec aad) (vec pt))))

(defn gcm-open "the plaintext of ciphertext ‖ tag, or nil when the tag does not verify" [key iv aad sealed]
  (when-let [pt (crypto/aes256-gcm-open (vec key) (vec iv) (vec aad) (vec sealed))] (vec pt)))

(defn- xor-bytes [a b] (mapv bit-xor a b))
(defn- u64-be [n] (mapv (fn [k] (mod (quot n (reduce * (repeat (- 7 k) 256))) 256)) (range 8)))

;; ── HKDF-SHA256 (RFC 5869), labeled as RFC 9180 §4 asks

(defn- extract [salt ikm] (crypto/hmac-sha256 (vec salt) (vec ikm)))

(defn- expand [prk info l]
  (loop [i 1 t [] okm []]
    (if (>= (count okm) l) (subvec okm 0 l)
        (let [t (crypto/hmac-sha256 (vec prk) (-> (vec t) (into (vec info)) (conj i)))]
          (recur (inc i) t (into okm t))))))

(def ^:private HPKE-V1 (b/utf8 "HPKE-v1"))
(defn- i2osp2 [n] [(quot n 256) (mod n 256)])

(defn- labeled-extract [suite salt label ikm]
  (extract salt (-> HPKE-V1 (into suite) (into (b/utf8 label)) (into (vec ikm)))))

(defn- labeled-expand [suite prk label info l]
  (expand prk (-> (i2osp2 l) (into HPKE-V1) (into suite) (into (b/utf8 label)) (into (vec info))) l))

(def KEM-ID 0x0020)
(def KDF-ID 0x0001)
(def AEAD-ID 0x0002)
(def ^:private SUITE-KEM (into (b/utf8 "KEM") (i2osp2 KEM-ID)))
(def ^:private SUITE (-> (b/utf8 "HPKE") (into (i2osp2 KEM-ID)) (into (i2osp2 KDF-ID)) (into (i2osp2 AEAD-ID))))
(def ^:private NK 32)
(def ^:private NN 12)
(def ^:private NH 32)

;; ── DHKEM(X25519, HKDF-SHA256) (RFC 9180 §4.1, §7.1)

(def ^:private BASE-U (into [9] (repeat 31 0)))

(defn public-key "X25519(sk, 9)" [sk] (vec (crypto/x25519 (vec sk) BASE-U)))

(defn derive-key-pair "RFC 9180 §7.1.3 for X25519: sk = LabeledExpand(LabeledExtract(\"\", \"dkp_prk\", ikm), \"sk\", \"\", 32)" [ikm]
  (let [prk (labeled-extract SUITE-KEM [] "dkp_prk" ikm)
        sk (labeled-expand SUITE-KEM prk "sk" [] 32)]
    {:sk sk :pk (public-key sk)}))

(defn- dh "X25519(sk, pk), refusing the all-zero output of a small-order pk (RFC 9180 §7.1.4)" [sk pk]
  (let [z (vec (crypto/x25519 (vec sk) (vec pk)))]
    (when (some pos? z) z)))

(defn- extract-and-expand [dh-out kem-context]
  (let [prk (labeled-extract SUITE-KEM [] "eae_prk" dh-out)]
    (labeled-expand SUITE-KEM prk "shared_secret" kem-context 32)))

(defn encap "{:shared-secret :enc} for the recipient's public key, the ephemeral key derived from ikm-e" [pk-r ikm-e]
  (let [{sk-e :sk pk-e :pk} (derive-key-pair ikm-e)
        z (or (dh sk-e pk-r) (throw (ex-info "hpke: the recipient's key is of small order" {})))]
    {:shared-secret (extract-and-expand z (into pk-e (vec pk-r))) :enc pk-e}))

(defn decap "the shared secret for enc under the recipient's secret key, or nil (a small-order enc)" [enc sk-r]
  (when-let [z (and (= 32 (count enc)) (dh sk-r enc))]
    (extract-and-expand z (into (vec enc) (public-key sk-r)))))

;; ── the key schedule (RFC 9180 §5.1, mode_base: the default psk and psk_id, both empty)

(defn key-schedule [shared-secret info]
  (let [psk-id-hash (labeled-extract SUITE [] "psk_id_hash" [])
        info-hash (labeled-extract SUITE [] "info_hash" info)
        ctx (-> [0] (into psk-id-hash) (into info-hash))
        secret (labeled-extract SUITE shared-secret "secret" [])]
    {:key-schedule-context ctx :secret secret
     :key (labeled-expand SUITE secret "key" ctx NK)
     :base-nonce (labeled-expand SUITE secret "base_nonce" ctx NN)
     :exporter-secret (labeled-expand SUITE secret "exp" ctx NH)}))

(defn nonce "base_nonce ⊕ I2OSP(seq, Nn)" [base-nonce seq-n]
  (xor-bytes base-nonce (into (vec (repeat 4 0)) (u64-be seq-n))))

;; ── single-shot base mode (§6.1): one message per context, seq 0

(defn seal "{:enc :ct}: pt sealed to the recipient's X25519 public key under info and aad" [pk-r info aad pt ikm-e]
  (let [{:keys [shared-secret enc]} (encap (vec pk-r) (vec ikm-e))
        {:keys [key base-nonce]} (key-schedule shared-secret (vec info))]
    {:enc enc :ct (gcm-seal key (nonce base-nonce 0) (vec aad) (vec pt))}))

(defn open "the plaintext, or nil" [sk-r enc info aad ct]
  (when-let [ss (decap (vec enc) (vec sk-r))]
    (let [{:keys [key base-nonce]} (key-schedule ss (vec info))]
      (gcm-open key (nonce base-nonce 0) (vec aad) (vec ct)))))
