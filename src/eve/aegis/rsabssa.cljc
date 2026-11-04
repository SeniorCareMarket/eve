(ns eve.aegis.rsabssa
  "RSA blind signatures in portable cljc (aegis's clear evaluation): RFC 9474's RSABSSA and the
   partially-blind extension of draft-irtf-cfrg-partially-blind-rsa-02 (RSAPBSSA), both over SHA-384, MGF1-SHA384 and
   a 48-byte salt (the RSABSSA-SHA384-PSS variants; sLen 0 for the PSSZERO ones), held to the RFC's four vectors and
   the draft's four by eve/test/eve/aegis/rsabssa_test.cljc. Every modular operation goes through an `arith` map
   {:modpow :modmul :modinv} — the crypto feature's (eve.crypto, a host's native big integers) by default, or
   eve.net.portable-crypto's portable rows — so one code path is measured on both.

   Keys: pk {:n :e}, sk {:n :e :d} (:p :q when the partially-blind DeriveKeyPair needs phi), every value big-endian
   bytes (vectors of 0..255); n's length is the modulus length. Messages, salts and signatures are bytes.

   THE PROTOCOL (RFC 9474 §4; the draft adds `info`):
     (prepare-identity msg) / (prepare-randomize msg prefix32)    input_msg
     (blind pk input-msg {:salt :r [:info]})  → {:blinded-msg :inv}  the client blinds the PSS encoding under r
     (blind-sign sk blinded-msg [info])        → blind-sig           the issuer signs with d (d' for info)
     (finalize pk input-msg blind-sig inv [info]) → sig             the client unblinds and verifies, or throws
     (verify pk input-msg sig [info])          → boolean             any checker, with the issuer's public key
   The partially-blind rows take `info` (public metadata): the message is prefixed \"msg\" ‖ len(info) ‖ info and
   the exponent is DerivePublicKey's e' (§4.6: HKDF-SHA384 over n and info). The salt and r are INPUTS (fresh random
   bytes from the caller: a compiled program has no entropy source); r must be in [1, n) and coprime to n.

   EMSA-PSS is RFC 8017 §9.1 with emBits = bit_len(n) − 1 (the RSASSA-PSS convention the vectors follow)."
  (:require [eve.crypto :as crypto]
            [eve.net.bignum :as bn]
            [eve.net.bytes :as b]))

(def HLEN 48)
(def SALT-LEN 48)

(defn adapter-arith "the crypto feature's big-integer rows (a function: clojerl compiles a def's value as a literal)" []
  {:modpow crypto/modpow :modmul crypto/modmul :modinv crypto/modinv})
(defn portable-arith "eve.net.bignum's rows" [] {:modpow bn/modpow :modmul bn/modmul :modinv bn/modinv})

(defn- refuse [msg data] (throw (ex-info (str "rsabssa: " msg) data)))

(defn sha384 [bs] (crypto/sha384 (vec bs)))

;; ── HMAC / HKDF over SHA-384 (RFC 2104, RFC 5869; block size 128)

(defn hmac-sha384 [key msg]
  (let [key (vec key)
        key (if (> (count key) 128) (sha384 key) key)
        key (into key (repeat (- 128 (count key)) 0))
        ipad (mapv (fn [k] (bit-xor k 0x36)) key)
        opad (mapv (fn [k] (bit-xor k 0x5c)) key)]
    (sha384 (into opad (sha384 (into ipad (vec msg)))))))

(defn hkdf-sha384 "RFC 5869 extract-and-expand: L bytes" [ikm salt info L]
  (let [prk (hmac-sha384 salt ikm)
        n (quot (+ L HLEN -1) HLEN)]
    (loop [i 1 t [] okm []]
      (if (> i n) (subvec okm 0 L)
          (let [t (hmac-sha384 prk (-> (vec t) (into (vec info)) (conj i)))]
            (recur (inc i) t (into okm t)))))))

;; ── EMSA-PSS (RFC 8017 §9.1) with SHA-384 and MGF1-SHA384

(defn- i2osp4 [n] [(mod (quot n 16777216) 256) (mod (quot n 65536) 256) (mod (quot n 256) 256) (mod n 256)])

(defn mgf1 [seed len]
  (loop [c 0 t []]
    (if (>= (count t) len) (subvec t 0 len)
        (recur (inc c) (into t (sha384 (into (vec seed) (i2osp4 c))))))))

(defn- bit-len "the bit length of big-endian bytes" [bs] (bn/bit-length (bn/from-bytes-be bs)))

(defn- pow2 [k] (nth [1 2 4 8 16 32 64 128 256] k))

(defn emsa-pss-encode "EM of emBits bits for the message under the salt (sLen = (count salt))" [msg em-bits salt]
  (let [m-hash (sha384 msg)
        s-len (count salt)
        em-len (quot (+ em-bits 7) 8)
        _ (when (< em-len (+ HLEN s-len 2)) (refuse "encoding error" {:em-len em-len}))
        m' (-> (vec (repeat 8 0)) (into m-hash) (into salt))
        h (sha384 m')
        ps (vec (repeat (- em-len s-len HLEN 2) 0))
        db (-> ps (conj 1) (into salt))
        db-mask (mgf1 h (- em-len HLEN 1))
        masked (mapv bit-xor db db-mask)
        clear (- (* 8 em-len) em-bits)
        masked (assoc masked 0 (mod (nth masked 0) (pow2 (- 8 clear))))]
    (-> masked (into h) (conj 0xbc))))

(defn emsa-pss-verify "RFC 8017 §9.1.2: is EM a PSS encoding of the message with an sLen-byte salt" [msg em em-bits s-len]
  (let [m-hash (sha384 msg)
        em-len (quot (+ em-bits 7) 8)
        em (vec em)]
    (and (= (count em) em-len)
         (>= em-len (+ HLEN s-len 2))
         (= 0xbc (peek em))
         (let [masked (subvec em 0 (- em-len HLEN 1))
               h (subvec em (- em-len HLEN 1) (- em-len 1))
               clear (- (* 8 em-len) em-bits)]
           (and (zero? (quot (nth masked 0) (pow2 (- 8 clear))))
                (let [db-mask (mgf1 h (- em-len HLEN 1))
                      db (mapv bit-xor masked db-mask)
                      db (assoc db 0 (mod (nth db 0) (pow2 (- 8 clear))))
                      ps-len (- em-len HLEN s-len 2)]
                  (and (every? zero? (subvec db 0 ps-len))
                       (= 1 (nth db ps-len))
                       (let [salt (subvec db (inc ps-len))
                             m' (-> (vec (repeat 8 0)) (into m-hash) (into salt))]
                         (= h (sha384 m'))))))))))

;; ── the partially-blind draft's key derivation (§4.6, §4.7)

(defn derive-public-key "(n, e') for the public metadata (draft §4.6)" [{:keys [n]} info]
  (let [modulus-len (count n)
        lambda-len (quot modulus-len 2)
        ikm (-> (b/utf8 "key") (into (vec info)) (conj 0))
        expanded (vec (hkdf-sha384 ikm n (b/utf8 "PBRSA") (+ lambda-len 16)))
        expanded (-> expanded
                     (assoc 0 (mod (nth expanded 0) 64))
                     (update (dec lambda-len) (fn [x] (if (odd? x) x (inc x)))))]
    {:n n :e (subvec expanded 0 lambda-len)}))

(defn derive-key-pair "(sk', pk') for the public metadata (draft §4.7): d' = e'^-1 mod phi, phi = (p−1)(q−1)" [{:keys [n p q] :as sk} info arith]
  (let [{e' :e} (derive-public-key sk info)
        one [1]
        phi (bn/mul (bn/sub (bn/from-bytes-be p) one) (bn/sub (bn/from-bytes-be q) one))
        phi-bytes (bn/to-bytes-be phi (count n))
        d' ((:modinv arith) e' phi-bytes)]
    (when-not d' (refuse "key derivation: e' has no inverse mod phi" {}))
    {:sk (assoc sk :e e' :d d') :pk {:n n :e e'}}))

;; ── the protocol

(defn prepare-identity [msg] (vec msg))
(defn prepare-randomize "RFC 9474 §4.1: 32 random bytes before the message" [msg prefix32]
  (when-not (= 32 (count prefix32)) (refuse "the prefix is 32 bytes" {:count (count prefix32)}))
  (into (vec prefix32) (vec msg)))

(defn- msg-prime "the draft's §4.2 step 1: \"msg\" ‖ len(info) as 4 bytes ‖ info ‖ msg" [msg info]
  (-> (b/utf8 "msg") (into (i2osp4 (count info))) (into (vec info)) (into (vec msg))))

(defn- effective "the (pk, message) a row actually signs: the metadata-derived exponent and prefixed message when info is given"
  [pk msg info]
  (if (nil? info) [pk (vec msg)] [(derive-public-key pk info) (msg-prime msg info)]))

(defn blind
  "the blinded message and the inverse of the blind (RFC 9474 §4.2; the draft's §4.2 with :info). opts: :salt (48 bytes,
   or [] for PSSZERO), :r (random bytes, 1 ≤ r < n, coprime to n), :info (public metadata, partially-blind),
   :arith (default adapter-arith)"
  [pk msg {:keys [salt r info arith] :or {arith (adapter-arith)}}]
  (let [[pk' msg'] (effective pk msg info)
        {:keys [n e]} pk'
        em (emsa-pss-encode msg' (dec (bit-len n)) salt)
        _ (when (nil? ((:modinv arith) em n)) (refuse "invalid input: the encoded message is not coprime with n" {}))
        inv ((:modinv arith) r n)
        _ (when (nil? inv) (refuse "blinding error: r has no inverse mod n" {}))
        x ((:modpow arith) r e n)
        z ((:modmul arith) em x n)]
    {:blinded-msg z :inv inv :encoded-msg em}))

(defn blind-sign
  "the blind signature (RFC 9474 §4.3): blinded^d mod n, checked against the public key; with info, under the
   metadata-derived key (draft §4.3)"
  ([sk blinded-msg] (blind-sign sk blinded-msg nil (adapter-arith)))
  ([sk blinded-msg info arith]
   (let [sk' (if (nil? info) sk (:sk (derive-key-pair sk info arith)))
         {:keys [n d e]} sk'
         _ (when-not (= (count blinded-msg) (count n)) (refuse "unexpected input size" {:count (count blinded-msg)}))
         s ((:modpow arith) blinded-msg d n)
         m' ((:modpow arith) s e n)]
     (when-not (= (vec m') (vec blinded-msg)) (refuse "signing failure" {}))
     s)))

(defn verify
  "RSASSA-PSS-VERIFY of the (prepared) message under the issuer's public key (RFC 9474 §4.5); with info, under the
   metadata-derived exponent over the prefixed message (draft §4.5)"
  ([pk msg sig] (verify pk msg sig nil (adapter-arith) SALT-LEN))
  ([pk msg sig info arith s-len]
   (let [[pk' msg'] (effective pk msg info)
         {:keys [n e]} pk']
     (and (= (count sig) (count n))
          (let [m ((:modpow arith) sig e n)]
            (emsa-pss-verify msg' m (dec (bit-len n)) s-len))))))

(defn finalize
  "the signature (RFC 9474 §4.4): the blind signature unblinded with inv and verified, else an ex-info"
  ([pk msg blind-sig inv] (finalize pk msg blind-sig inv nil (adapter-arith) SALT-LEN))
  ([pk msg blind-sig inv info arith s-len]
   (let [{:keys [n]} pk
         _ (when-not (= (count blind-sig) (count n)) (refuse "unexpected input size" {:count (count blind-sig)}))
         sig ((:modmul arith) blind-sig inv n)]
     (when-not (verify pk msg sig info arith s-len) (refuse "invalid signature" {}))
     sig)))
