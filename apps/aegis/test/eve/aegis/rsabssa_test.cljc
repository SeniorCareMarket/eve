(ns eve.aegis.rsabssa-test
  "eve.aegis.rsabssa held to RFC 9474 Appendix A (four variants) and draft-irtf-cfrg-partially-blind-rsa-02 Appendix B
   (four vectors), on BOTH arithmetic paths: the crypto feature's (the host's native big integers where its adapter
   has them) and eve.net.bignum's portable rows. r is recovered from the RFC's inv (r = inv^-1 mod n); the draft gives r."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.aegis.rsabssa :as rsa]
            [eve.aegis.rsa-vectors :as vectors]
            [eve.net.bignum :as bn]
            [eve.net.bytes :as b]))

(defn- hx [s] (b/unhex s))

(defn- check-rfc9474
  "sign? false: the client's rows only (blind, finalize, verify), the issuer's blind_sig taken from the vector — the
   portable path's issuer row is measured, not run here (33.5 s on the JVM)"
  [arith label vectors sign?]
  (doseq [{:keys [variant] :as v} vectors]
    (testing (str label " " variant)
      (let [n (hx (:n v)) e (hx (:e v)) d (hx (:d v))
            arith (if (= arith :portable) (assoc (bn/cached-arith n) :label :portable) arith)
            pk {:n n :e e} sk {:n n :e e :d d}
            msg (hx (:prepared_msg v))
            salt (hx (:salt v))
            inv (hx (:inv v))
            r ((:modinv arith) inv n)
            {:keys [blinded-msg inv encoded-msg]} (rsa/blind pk msg {:salt salt :r r :arith arith})]
        (is (= (hx (:prepared_msg v)) (if (seq (:msg_prefix v)) (rsa/prepare-randomize (hx (:msg v)) (hx (:msg_prefix v))) (rsa/prepare-identity (hx (:msg v))))))
        (is (= (hx (:encoded_msg v)) encoded-msg) "encoded_msg")
        (is (= (hx (:inv v)) inv) "inv")
        (is (= (hx (:blinded_msg v)) blinded-msg) "blinded_msg")
        (let [bs (if sign? (rsa/blind-sign sk blinded-msg nil arith) (hx (:blind_sig v)))]
          (is (= (hx (:blind_sig v)) bs) "blind_sig")
          (let [sig (rsa/finalize pk msg bs inv nil arith (count salt))]
            (is (= (hx (:sig v)) sig) "sig")
            (is (rsa/verify pk msg sig nil arith (count salt)))
            (is (not (rsa/verify pk (conj (vec msg) 1) sig nil arith (count salt))) "another message")
            (is (not (rsa/verify pk msg (assoc (vec sig) 7 (bit-xor 1 (nth sig 7))) nil arith (count salt))) "a flipped byte")))))))

(defn- check-pbrsa [arith label vectors sign?]
  (doseq [v vectors]
    (testing (str label " pbrsa vector " (:vector v))
      (let [n (hx (:n v)) e (hx (:e v)) d (hx (:d v))
            arith (if (= arith :portable) (assoc (bn/cached-arith n) :label :portable) arith)
            pk {:n n :e e} sk {:n n :e e :d d :p (hx (:p v)) :q (hx (:q v))}
            msg (hx (:msg v)) info (hx (:info v))
            {e' :e} (rsa/derive-public-key pk info)]
        (is (= (hx (:eprime v)) e') "eprime")
        (let [{:keys [blinded-msg inv]} (rsa/blind pk msg {:salt (hx (:salt v)) :r (hx (:r v)) :info info :arith arith})]
          (is (= (hx (:blind_msg v)) blinded-msg) "blind_msg")
          (let [bs (if sign? (rsa/blind-sign sk blinded-msg info arith) (hx (:blind_sig v)))]
            (is (= (hx (:blind_sig v)) bs) "blind_sig")
            (let [sig (rsa/finalize pk msg bs inv info arith 48)]
              (is (= (hx (:sig v)) sig) "sig")
              (is (rsa/verify pk msg sig info arith 48))
              (is (not (rsa/verify pk msg sig (conj (vec info) 1) arith 48)) "another scope's metadata")
              (is (not (rsa/verify pk msg sig nil arith 48)) "the plain key"))))))))

(deftest rfc9474-vectors-adapter (check-rfc9474 (rsa/adapter-arith) "adapter" (:rfc9474 vectors/vectors) true))
(deftest pbrsa-vectors-adapter (check-pbrsa (rsa/adapter-arith) "adapter" (:pbrsa vectors/vectors) true))
;; the portable rows: one vector per family, the client's side (the issuer's row is the measured one)
(deftest rfc9474-vector-portable-client (check-rfc9474 :portable "portable" [(nth (:rfc9474 vectors/vectors) 2)] false))
(deftest pbrsa-vector-portable-client (check-pbrsa :portable "portable" [(first (:pbrsa vectors/vectors))] false))

(deftest hkdf-and-hmac
  ;; RFC 4231 test case 2 for HMAC-SHA-384
  (is (= "af45d2e376484031617f78d2b58a6b1b9c7ef464f5a01b47e42ec3736322445e8e2240ca5e69e2c78b3239ecfab21649"
         (b/hex (rsa/hmac-sha384 (b/utf8 "Jefe") (b/utf8 "what do ya want for nothing?"))))))
