(ns eve.net.crypto-fast-path-test
  "The crypto feature's fast-path rows (the aegis clear-evaluation rows: sha384, modpow / modmul / modinv,
   ed25519-scalarmult / ed25519-mul-add) held to eve.net.portable-crypto's on whatever host runs this — a native row
   and the portable one must answer the same bytes: SHA-384 on the NIST vectors, the big-integer rows on a 512-bit
   modulus and on the RFC 9474 A.3 key's public exponent, the group rows on six keys and scalars against
   eve.net.ed25519, x25519 on RFC 7748's vectors, sha512 on NIST's, aes256-gcm on NIST SP 800-38D's test cases 13–16,
   ed25519-public-key against the portable derivation. Prints the host's cost of one ed25519-mul-add on each path."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.crypto :as c]
            [eve.net.bytes :as b]
            [eve.net.ed25519 :as ed]
            [eve.net.portable-crypto :as p]
            [eve.net.sha512 :as sha]
            [eve.process :as process]))

(deftest sha384-rows
  (is (= "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7" (b/hex (c/sha384 (b/utf8 "abc")))))
  (is (= (p/sha384 []) (c/sha384 [])))
  (is (= (p/sha384 (vec (range 200))) (c/sha384 (vec (range 200))))))

(defn- bytes-of [label n] (vec (take n (cycle (sha/sha512 (b/utf8 label))))))

(deftest big-integer-rows
  (let [n (assoc (bytes-of "modulus" 64) 63 0xc3)   ; odd
        a (bytes-of "a" 64) e (bytes-of "e" 64) one (into (vec (repeat 63 0)) [1])]
    (is (= (p/modpow a e n) (c/modpow a e n)) "modpow")
    (is (= (p/modmul a e n) (c/modmul a e n)) "modmul")
    (is (= (p/modmul a one n) (c/modmul a one n)) "modmul by one")
    (let [inv (c/modinv a n)]
      (is (= (p/modinv a n) inv) "modinv")
      (when inv (is (= one (c/modmul a inv n)) "a · a^-1 = 1")))
    (is (nil? (c/modinv (assoc (vec (repeat 64 0)) 63 0) n)) "no inverse of 0")
    (testing "a 2048-bit modulus, the public exponent"
      (let [n (b/unhex "aec4d69addc70b990ea66a5e70603b6fee27aafebd08f2d94cbe1250c556e047a928d635c3f45ee9b66d1bc628a03bac9b7c3f416fe20dabea8f3d7b4bbf7f963be335d2328d67e6c13ee4a8f955e05a3283720d3e1f139c38e43e0338ad058a9495c53377fc35be64d208f89b4aa721bf7f7d3fef837be2a80e0f8adf0bcd1eec5bb040443a2b2792fdca522a7472aed74f31a1ebe1eebc1f408660a0543dfe2a850f106a617ec6685573702eaaa21a5640a5dcaf9b74e397fa3af18a2f1b7c03ba91a6336158de420d63188ee143866ee415735d155b7c2d854d795b7bc236cffd71542df34234221a0413e142d8c61355cc44d45bda94204974557ac2704cd8b593f035a5724b1adf442e78c542cd4414fce6f1298182fb6d8e53cef1adfd2e90e1e4deec52999bdc6c29144e8d52a125232c8c6d75c706ea3cc06841c7bda33568c63a6c03817f722b50fcf898237d788a4400869e44d90a3020923dc646388abcc914315215fcd1bae11b1c751fd52443aac8f601087d8d42737c18a3fa11ecd4131ecae017ae0a14acfc4ef85b83c19fed33cfd1cd629da2c4c09e222b398e18d822f77bb378dea3cb360b605e5aa58b20edc29d000a66bd177c682a17e7eb12a63ef7c2e4183e0d898f3d6bf567ba8ae84f84f1d23bf8b8e261c3729e2fa6d07b832e07cddd1d14f55325c6f924267957121902dc19b3b32948bdead5")
            e [1 0 1] m (bytes-of "m" 256)]
        (is (= (p/modpow m e n) (c/modpow m e n)))))))

(deftest group-rows
  (let [seeds (mapv (fn [i] (subvec (sha/sha512 (b/utf8 (str "s" i))) 0 32)) (range 6))
        pks (mapv ed/public-key seeds)
        scalars (mapv (fn [i] (ed/mod-l (sha/sha512 (b/utf8 (str "k" i))))) (range 6))
        base (ed/pack (ed/scalarbase (into [1] (repeat 31 0))))]
    (doseq [i (range 6)]
      (is (= (p/ed25519-scalarmult (nth scalars i) (nth pks i)) (c/ed25519-scalarmult (nth scalars i) (nth pks i))) "scalarmult")
      (is (= (ed/pack (ed/scalarbase (nth scalars i))) (c/ed25519-scalarmult (nth scalars i) base)) "scalarmult of the base point")
      (is (= (p/ed25519-mul-add (nth scalars i) (nth pks i) (nth scalars (mod (inc i) 6)) (nth pks (mod (+ i 2) 6)))
             (c/ed25519-mul-add (nth scalars i) (nth pks i) (nth scalars (mod (inc i) 6)) (nth pks (mod (+ i 2) 6)))) "mul-add"))
    (is (nil? (c/ed25519-scalarmult (nth scalars 0) (vec (repeat 32 255)))) "not a point: nil")
    (is (nil? (p/ed25519-scalarmult (nth scalars 0) (vec (repeat 32 255)))) "not a point: nil, portable too")
    (is (nil? (c/ed25519-mul-add (nth scalars 0) (nth pks 0) (nth scalars 1) [1 2 3])) "a short encoding: nil")
    (let [t0 (process/now-ms)] (dotimes [_ 5] (c/ed25519-mul-add (nth scalars 0) (nth pks 0) (nth scalars 1) (nth pks 1)))
      (println (str "ed25519-mul-add: adapter " (/ (- (process/now-ms) t0) 5.0) " ms")))
    (let [t0 (process/now-ms)] (p/ed25519-mul-add (nth scalars 0) (nth pks 0) (nth scalars 1) (nth pks 1))
      (println (str "ed25519-mul-add: portable " (- (process/now-ms) t0) " ms")))))

;; RFC 7748 §5.2 and §6.1 (vectors copied from the RFC's text, rfc-editor.org/rfc/rfc7748.txt)
(def ^:private X25519-VECTORS
  [["a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
    "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
    "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"]
   ["4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"
    "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"
    "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"]])

(defn- iterate-x25519 "RFC 7748 §5.2's iteration: k = u = 9, then (k, u) <- (X25519(k, u), k), n times" [f n]
  (loop [i 0 k (into [9] (repeat 31 0)) u (into [9] (repeat 31 0))]
    (if (= i n) k (recur (inc i) (f k u) k))))

(deftest x25519-rows
  (testing "RFC 7748 §5.2, adapter and portable"
    (doseq [[k u out] X25519-VECTORS]
      (is (= out (b/hex (c/x25519 (b/unhex k) (b/unhex u)))))
      (is (= out (b/hex (p/x25519 (b/unhex k) (b/unhex u)))))))
  (testing "RFC 7748 §5.2, the iteration: 1 (both paths) and 1,000 (the adapter's row)"
    (is (= "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079" (b/hex (iterate-x25519 c/x25519 1))))
    (is (= "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079" (b/hex (iterate-x25519 p/x25519 1))))
    (is (= "684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51" (b/hex (iterate-x25519 c/x25519 1000)))))
  (testing "RFC 7748 §6.1, the Diffie-Hellman exchange"
    (let [nine (into [9] (repeat 31 0))
          a (b/unhex "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
          bk (b/unhex "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
          ka (c/x25519 a nine) kb (c/x25519 bk nine)]
      (is (= "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a" (b/hex ka)))
      (is (= "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f" (b/hex kb)))
      (is (= "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742" (b/hex (c/x25519 a kb)) (b/hex (c/x25519 bk ka))))
      (is (= (b/hex (p/x25519 a kb)) (b/hex (c/x25519 a kb))))))
  (testing "a point of small order gives the all-zero output (the caller refuses it)"
    (is (= (vec (repeat 32 0)) (c/x25519 (b/unhex "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4") (vec (repeat 32 0)))))
    (is (= (vec (repeat 32 0)) (p/x25519 (b/unhex "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4") (into [1] (repeat 31 0))))))
  (let [k (b/unhex "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        u (b/unhex "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        t0 (process/now-ms) _ (c/x25519 k u) t1 (process/now-ms) _ (p/x25519 k u) t2 (process/now-ms)]
    (println "x25519: adapter" (- t1 t0) "ms, portable" (- t2 t1) "ms")))

(deftest map-to-point-row
  (doseq [i (range 4)]
    (let [u0 (subvec (sha/sha512 (b/utf8 (str "map-u0-" i))) 0 32)
          u1 (subvec (sha/sha512 (b/utf8 (str "map-u1-" i))) 0 32)]
      (is (= (p/ed25519-map-to-point u0 u1) (vec (c/ed25519-map-to-point u0 u1))) (str "map-to-point " i))))
  (let [z (vec (repeat 32 0)) one (into [1] (repeat 31 0))]
    (is (= (p/ed25519-map-to-point z one) (vec (c/ed25519-map-to-point z one))) "u0 = 0, u1 = 1")))

(deftest sha512-and-public-key-rows
  (is (= "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
         (b/hex (c/sha512 (b/utf8 "abc")))) "SHA-512, FIPS 180-2's \"abc\"")
  (is (= (p/sha512 (mapv (fn [i] (mod i 256)) (range 300))) (vec (c/sha512 (mapv (fn [i] (mod i 256)) (range 300))))))
  (doseq [i (range 4)]
    (let [seed (subvec (sha/sha512 (b/utf8 (str "pk-seed-" i))) 0 32)]
      (is (= (p/ed25519-public-key seed) (vec (c/ed25519-public-key seed))) (str "public key " i)))))

;; NIST SP 800-38D test cases 13-16 (AES-256, 96-bit IV): McGrew & Viega's GCM submission, as
;; test/aegis/fixtures/vectors/gcm_13_16.json vendors them with provenance
(def ^:private GCM-VECTORS
  [["0000000000000000000000000000000000000000000000000000000000000000" "000000000000000000000000" "" ""
    "530f8afbc74536b9a963b4f1c4cb738b"]
   ["0000000000000000000000000000000000000000000000000000000000000000" "000000000000000000000000" "" "00000000000000000000000000000000"
    "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919"]
   ["feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308" "cafebabefacedbaddecaf888" ""
    "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255"
    "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015adb094dac5d93471bdec1a502270e3cc6c"]
   ["feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308" "cafebabefacedbaddecaf888" "feedfacedeadbeeffeedfacedeadbeefabaddad2"
    "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
    "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f66276fc6ece0f4e1768cddf8853bb2d551b"]])

(defn- unhex* [s] (if (= "" s) [] (b/unhex s)))

(deftest aes256-gcm-rows
  (doseq [[k iv aad pt sealed] GCM-VECTORS]
    (let [[k iv aad pt] (map unhex* [k iv aad pt])
          out (vec (c/aes256-gcm-seal k iv aad pt))]
      (is (= sealed (b/hex out)) "adapter seal")
      (is (= sealed (b/hex (p/aes256-gcm-seal k iv aad pt))) "portable seal")
      (is (= pt (vec (c/aes256-gcm-open k iv aad out))) "adapter open")
      (is (nil? (c/aes256-gcm-open k iv aad (update out (dec (count out)) bit-xor 1))) "a flipped tag bit")
      (is (nil? (c/aes256-gcm-open k iv (conj aad 1) out)) "another aad")))
  (let [k (vec (range 32)) iv (vec (range 12)) pt (vec (take 1000 (cycle (range 256)))) aad (vec (range 7))
        t0 (process/now-ms) s (c/aes256-gcm-seal k iv aad pt) t1 (process/now-ms) s2 (p/aes256-gcm-seal k iv aad pt) t2 (process/now-ms)]
    (is (= (vec s) (vec s2)) "1,000 bytes: adapter and portable agree")
    (println "aes256-gcm 1000 B: adapter" (- t1 t0) "ms, portable" (- t2 t1) "ms")))
