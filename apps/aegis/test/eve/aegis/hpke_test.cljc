(ns eve.aegis.hpke-test
  "eve.aegis.hpke held to published vectors: AES-256-GCM to NIST SP 800-38D's test cases 13–16 (McGrew & Viega's GCM
   submission, the cases test/aegis/fixtures/vectors/gcm_13_16.json vendors with provenance), and HPKE to the CFRG's
   base-mode vector for DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM (mode 0, kem 32, kdf 1, aead 2) in
   https://raw.githubusercontent.com/cfrg/draft-irtf-cfrg-hpke/master/test-vectors.json (retrieved 2026-10-23):
   DeriveKeyPair, Encap / Decap's shared secret, the key schedule, and the first three sealed messages."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.aegis.hpke :as hpke]
            [eve.net.bytes :as b]))

(def ^:private GCM
  [{:case 13 :K "0000000000000000000000000000000000000000000000000000000000000000" :P "" :A ""
    :IV "000000000000000000000000" :C "" :T "530f8afbc74536b9a963b4f1c4cb738b"}
   {:case 14 :K "0000000000000000000000000000000000000000000000000000000000000000" :P "00000000000000000000000000000000" :A ""
    :IV "000000000000000000000000" :C "cea7403d4d606b6e074ec5d3baf39d18" :T "d0d1c8a799996bf0265b98b5d48ab919"}
   {:case 15 :K "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"
    :P "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255"
    :A "" :IV "cafebabefacedbaddecaf888"
    :C "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad"
    :T "b094dac5d93471bdec1a502270e3cc6c"}
   {:case 16 :K "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"
    :P "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
    :A "feedfacedeadbeeffeedfacedeadbeefabaddad2" :IV "cafebabefacedbaddecaf888"
    :C "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662"
    :T "76fc6ece0f4e1768cddf8853bb2d551b"}])

(defn- u [s] (if (= "" s) [] (b/unhex s)))

(deftest aes-256-gcm-nist-13-16
  (doseq [{:keys [case K P A IV C T]} GCM]
    (testing (str "case " case)
      (let [sealed (hpke/gcm-seal (u K) (u IV) (u A) (u P))]
        (is (= (str C T) (b/hex sealed)))
        (is (= (u P) (hpke/gcm-open (u K) (u IV) (u A) sealed)))
        (is (nil? (hpke/gcm-open (u K) (u IV) (u A) (update sealed (dec (count sealed)) bit-xor 1))) "a flipped tag bit")
        (when (seq P) (is (nil? (hpke/gcm-open (u K) (u IV) (u A) (update sealed 0 bit-xor 1))) "a flipped ciphertext bit"))
        (is (nil? (hpke/gcm-open (u K) (u IV) (conj (u A) 0) sealed)) "another aad")))))

;; the CFRG entry: mode 0, kem_id 32, kdf_id 1, aead_id 2
(def ^:private V
  {:info "4f6465206f6e2061204772656369616e2055726e"
   :ikmE "2cd7c601cefb3d42a62b04b7a9041494c06c7843818e0ce28a8f704ae7ab20f9"
   :ikmR "dac33b0e9db1b59dbbea58d59a14e7b5896e9bdf98fad6891e99d1686492b9ee"
   :skEm "179d4b53b6365c45b600c4163b61d95cbc2f4d9e36f1695558dce265ab8bab11"
   :skRm "497b4502664cfea5d5af0b39934dac72242a74f8480451e1aee7d6a53320333d"
   :pkEm "6c93e09869df3402d7bf231bf540fadd35cd56be14f97178f0954db94b7fc256"
   :pkRm "430f4b9859665145a6b1ba274024487bd66f03a2dd577d7753c68d7d7d00c00c"
   :enc "6c93e09869df3402d7bf231bf540fadd35cd56be14f97178f0954db94b7fc256"
   :shared_secret "3101c54c3a4f87439eaac080699ed9bbcc726ffe44e860c0424ccb7e3e2ead7b"
   :key_schedule_context "004ce5472ecdd5093ba0aecb8f871ff13f1fbc90ee76f0e18ace1a1b7e565bafa306f6ef962c9ee7cea40407b5d60f0f26990472faae3ac44c78366f1cac1ecde1"
   :secret "2058ac9b02c1f52c1aaf08bedbec9198219751a94ef67b7d5f0c8b6e2b54ebfb"
   :key "f50b0609186798729ed0564b36ef2ef8044f1f9d05636874d1f46c819c7a669f"
   :base_nonce "151d9929e2449747889bc923"
   :exporter_secret "86017151bbff6a1940e8abae2ac9e0e7032e33df1eaaecc02ca6259b130d62df"
   :encryptions
   [{:aad "436f756e742d30" :pt "4265617574792069732074727574682c20747275746820626561757479" :nonce "151d9929e2449747889bc923"
     :ct "e5d84cd531cfb583096e7cfa9641bd3079cf3a91cda813c52deb5f512be9931980a41de125a925cdad859d5b7a"}
    {:aad "436f756e742d31" :pt "4265617574792069732074727574682c20747275746820626561757479" :nonce "151d9929e2449747889bc922"
     :ct "2c43aff25343fdbff864506f0818b9d87df84ea01b1a2144d23b4d40c26bf655fdf197fe40297a8aebeed5cc2d"}
    {:aad "436f756e742d32" :pt "4265617574792069732074727574682c20747275746820626561757479" :nonce "151d9929e2449747889bc921"
     :ct "e0a8f2cf92ff61215edbb8c55dc31fe9e2eb42a5685867bb6854211542099f9e940c4b41c192bc390835b1a5f7"}]})

(deftest hpke-base-x25519-sha256-aes256gcm
  (let [{sk-e :sk pk-e :pk} (hpke/derive-key-pair (u (:ikmE V)))
        {sk-r :sk pk-r :pk} (hpke/derive-key-pair (u (:ikmR V)))]
    (testing "DeriveKeyPair"
      (is (= (:skEm V) (b/hex sk-e))) (is (= (:pkEm V) (b/hex pk-e)))
      (is (= (:skRm V) (b/hex sk-r))) (is (= (:pkRm V) (b/hex pk-r))))
    (testing "Encap / Decap"
      (let [{:keys [shared-secret enc]} (hpke/encap pk-r (u (:ikmE V)))]
        (is (= (:enc V) (b/hex enc)))
        (is (= (:shared_secret V) (b/hex shared-secret)))
        (is (= (:shared_secret V) (b/hex (hpke/decap enc sk-r))))))
    (testing "the key schedule"
      (let [ks (hpke/key-schedule (u (:shared_secret V)) (u (:info V)))]
        (is (= (:key_schedule_context V) (b/hex (:key-schedule-context ks))))
        (is (= (:secret V) (b/hex (:secret ks))))
        (is (= (:key V) (b/hex (:key ks))))
        (is (= (:base_nonce V) (b/hex (:base-nonce ks))))
        (is (= (:exporter_secret V) (b/hex (:exporter-secret ks))))))
    (testing "the sealed messages, seq 0, 1, 2"
      (doseq [[i {:keys [aad pt nonce ct]}] (map-indexed vector (:encryptions V))]
        (is (= nonce (b/hex (hpke/nonce (u (:base_nonce V)) i))))
        (is (= ct (b/hex (hpke/gcm-seal (u (:key V)) (u nonce) (u aad) (u pt)))))))
    (testing "single-shot seal / open is the seq-0 message"
      (let [{:keys [enc ct]} (hpke/seal pk-r (u (:info V)) (u "436f756e742d30") (u (:pt (first (:encryptions V)))) (u (:ikmE V)))]
        (is (= (:enc V) (b/hex enc)))
        (is (= (:ct (first (:encryptions V))) (b/hex ct)))
        (is (= (u (:pt (first (:encryptions V)))) (hpke/open sk-r enc (u (:info V)) (u "436f756e742d30") ct)))
        (is (nil? (hpke/open sk-r enc (u "00") (u "436f756e742d30") ct)) "another info")
        (is (nil? (hpke/open sk-e enc (u (:info V)) (u "436f756e742d30") ct)) "another recipient")
        (is (nil? (hpke/open sk-r (vec (repeat 32 0)) (u (:info V)) (u "436f756e742d30") ct)) "a small-order enc")))))
