(ns eve.aegis.h2c-test
  "eve.aegis.h2c held to RFC 9380: Appendix K.3 (expand_message_xmd over SHA-512, ten rows) and Appendix J.5.1
   (edwards25519_XMD:SHA-512_ELL2_RO_, five rows: u0 u1, Q0 Q1 before the cofactor is cleared, P after)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.aegis.h2c :as h2c]
            [eve.aegis.h2c-vectors :as v]
            [eve.net.bignum :as bn]
            [eve.net.bytes :as b]))

(def XMD-DST (b/utf8 "QUUX-V01-CS02-with-expander-SHA512-256"))
(def RO-DST (b/utf8 "QUUX-V01-CS02-with-edwards25519_XMD:SHA-512_ELL2_RO_"))

(defn- hex-len "a length as the vectors print it: 0x-prefixed hex, else decimal digits (no parse-long: clojerl has none)" [s]
  (if (= "0x" (subs s 0 2))
    (reduce (fn [acc c] (+ (* 16 acc) (b/hex-value c))) 0 (subs s 2))
    (reduce (fn [acc c] (+ (* 10 acc) (b/hex-value c))) 0 s)))

(deftest expand-message-xmd-sha512
  (doseq [{:keys [msg len_in_bytes uniform_bytes]} v/xmd]
    (testing (str "msg " (subs msg 0 (min 12 (count msg))) " len " len_in_bytes)
      (is (= uniform_bytes (b/hex (h2c/expand-message-xmd (b/utf8 msg) XMD-DST (hex-len len_in_bytes))))))))

(defn- affine-hex [p] (let [[x y] (h2c/point->affine p)] [(h2c/fe-hex x) (h2c/fe-hex y)]))

(deftest edwards25519-ell2-ro
  (doseq [{:keys [msg P_x P_y u0 u1 Q0_x Q0_y Q1_x Q1_y]} v/ro]
    (testing (str "msg " (subs msg 0 (min 12 (count msg))))
      (let [[e0 e1] (h2c/hash-to-field (b/utf8 msg) RO-DST 2)]
        (is (= u0 (h2c/fe-hex e0)) "u[0]")
        (is (= u1 (h2c/fe-hex e1)) "u[1]")
        (is (= [Q0_x Q0_y] (affine-hex (h2c/map-to-curve e0))) "Q0")
        (is (= [Q1_x Q1_y] (affine-hex (h2c/map-to-curve e1))) "Q1")
        (is (= [P_x P_y] (affine-hex (h2c/hash-to-curve (b/utf8 msg) RO-DST))) "P")))))

(defn- encoding-of "the RFC 8032 encoding of the affine point (x, y), each 64 big-endian hex digits" [x y]
  (let [yl (vec (rseq (vec (b/unhex y))))]
    (assoc yl 31 (+ (nth yl 31) (* 128 (mod (peek (vec (b/unhex x))) 2))))))

(deftest hash-to-point-encodes-P
  ;; hash-to-point maps through the crypto feature's ed25519-map-to-point row: each host's own (ed25519.wasm on
  ;; ValueScript), held here to the RFC's P
  (doseq [{:keys [msg P_x P_y]} v/ro]
    (is (= (encoding-of P_x P_y) (vec (h2c/hash-to-point (b/utf8 msg) RO-DST)))
        (str "the encoding of P, msg " (subs msg 0 (min 12 (count msg)))))))

(deftest encoding-round-trip
  (let [p (h2c/hash-to-point (b/utf8 "aegis scope") (b/utf8 "aegis-clsag-scope-v1"))]
    (is (= 32 (count p)))
    (is (not= p (h2c/hash-to-point (b/utf8 "another scope") (b/utf8 "aegis-clsag-scope-v1"))))))

(deftest map-constants
  (testing "the maps' literal constants equal their computations"
    (is (= h2c/C2 (vec (h2c/c2-computed))) "c2 = 2^((p+3)/8)")
    (is (= h2c/C1-ED (vec (h2c/c1-ed-computed))) "c1 = sqrt(-486664), sgn0 0")))

(deftest the-crypto-rows-constants-and-reduction
  (testing "p, p − 2 and (p − 5)/8 as big-endian bytes equal their computations"
    (let [p (bn/from-bytes-be h2c/P-BE)]
      (is (= h2c/P-BE (bn/to-bytes-be (bn/sub (bn/from-bytes-be (into [0x80] (repeat 31 0))) [19]) 32)) "2^255 − 19")
      (is (= h2c/P-2-BE (bn/to-bytes-be (bn/sub p [2]) 32)))
      (is (= h2c/P58-BE (bn/to-bytes-be (bn/sub (bn/from-bytes-be (into [0x10] (repeat 31 0))) [3]) 32)) "(p − 5)/8 = 2^252 − 3")))
  (testing "hash_to_field's reduction through the crypto rows equals the limb reduction"
    (doseq [bs [(vec (repeat 48 0xff)) (vec (range 48)) (into [0x7f] (repeat 47 0xee)) (vec (repeat 48 0))]]
      (is (= (h2c/os2ip-mod-p bs) (h2c/os2ip-mod-p-limbs bs))))))
