(ns eve.net.crypto-test
  "G4 (eve/doc/network-atoms/IMPL-PLAN.md §6.4, T-4.9): eve.net.crypto held to the standards VS's test/vs/blob_enc_test.vs
   holds vs.net.blob-crypto to (FIPS-197 C.3, SP 800-38A F.5.5, RFC 4231 TC1, RFC 5869 A.1), and to one per-block
   key, IV and ciphertext computed by an independent implementation (Python's cryptography: HKDF and AES-CTR) for
   blob_enc_test's own ctx — so a blob either implementation encrypts, the other decrypts."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.net.bytes :as b]
            [eve.crypto :as prim]
            [eve.net.aes :as aes]
            [eve.net.crypto :as c]
            [eve.net.portable-crypto :as pc]
            [eve.net.sha256 :as sha]))

(deftest the-standards
  (is (= "8ea2b7ca516745bfeafc49904b496089" (b/hex (aes/aes256-encrypt-block (vec (range 32)) (b/unhex "00112233445566778899aabbccddeeff")))))
  (let [key (b/unhex "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        iv (b/unhex "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        pt (b/unhex "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")]
    (is (= "601ec313775789a5b7a7f504bbf3d228f443e3ca4d62b59aca84e990cacaf5c5" (b/hex (prim/aes256-ctr key iv pt))))
    (is (= pt (prim/aes256-ctr key iv (prim/aes256-ctr key iv pt))))
    (is (= 5 (count (prim/aes256-ctr key iv [1 2 3 4 5])))))
  (is (= "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7" (b/hex (c/hmac-sha256 (vec (repeat 20 11)) (b/utf8 "Hi There")))))
  (is (= "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
         (b/hex (c/hkdf-sha256 (vec (repeat 22 11)) (b/unhex "000102030405060708090a0b0c") (b/unhex "f0f1f2f3f4f5f6f7f8f9") 42)))))

(def ^:private gak (vec (range 100 132)))
(def ^:private nonce-a (vec (range 1 17)))
(def ^:private nonce-b (vec (range 17 33)))
(def ^:private plain (mapv (fn [i] (mod (* i 7) 256)) (range 200)))

(deftest interop-with-an-independent-implementation
  (let [k (c/derive-block-key gak nonce-a 5 9)]
    (is (= "1330b2af6de01500412d17c2ac8b15471b4a0cb585056666c992123a28364fc8" (b/hex k)))
    (is (= "44b6072006411d06ee123e98a8921e45" (b/hex (c/blob-iv k))))
    (is (= (str "9c648f3f6e369418acf5304fa409569038735ce27be59aa4fb1add434af00bc2c0fee638bd9d90ec60a64a05f95493e74a07894c844c9dd832e99d74d266f4a12a64fc18ef7dc62fcd944e6b48e95829472a5887f0cfaf1bb2f2a669009b5ed98d7ffbf87d78c1ccf747c279c17ad8aab6c1c0877980429a6e2bd9f4d6061ee7ac44863355fa4842a425892490fd317d7db5a997f800a23fa93314b27acc52b52e8bdbdc1ad5c6a570a9466b8d0ee2bae18e9f77739c3f2a82df33719317fcc9968487ddba5dee19")
           (b/hex (c/encrypt-blob {:gak gak :nonce nonce-a :page-id 5 :slot-idx 9} plain))))))

(deftest per-block-and-authenticated
  (let [k1 (c/derive-block-key gak nonce-a 5 9)]
    (is (= 4 (count (distinct [k1 (c/derive-block-key gak nonce-b 5 9) (c/derive-block-key gak nonce-a 6 9) (c/derive-block-key gak nonce-a 5 10)]))))
    (is (nil? (c/derive-block-key gak (vec (repeat 16 0)) 5 9)) "an all-zero nonce: shredded, no key"))
  (let [ctx {:gak gak :nonce nonce-a :page-id 3 :slot-idx 1}
        ca (c/encrypt-blob ctx plain)
        addr (b/hex (sha/sha256 plain))]
    (is (not= ca plain))
    (is (not= ca (c/encrypt-blob {:gak gak :nonce nonce-b :page-id 3 :slot-idx 2} plain)) "the same plaintext, another block")
    (is (= plain (c/decrypt-blob ctx ca addr)))
    (is (= :authentication (try (c/decrypt-blob ctx (update ca 7 (fn [x] (bit-xor x 1))) addr) nil
                                (catch #?(:cljs :default :lpy python/Exception :default Exception) e (:type (ex-data e))))))))

(defn- portable-hmac [key msg]
  (let [k (vec key) k (if (> (count k) 64) (vec (sha/sha256 k)) k) k (into k (repeat (- 64 (count k)) 0))]
    (vec (sha/sha256 (into (mapv (fn [x] (bit-xor x 0x5c)) k) (sha/sha256 (into (mapv (fn [x] (bit-xor x 0x36)) k) msg)))))))

(deftest the-host-adapter-against-the-portable-code
  (testing "eve.crypto (this host's crypto adapter) answers what eve.net.sha256 / eve.net.aes answer (§6.0: an override
            cannot change a byte)"
    (doseq [n [0 1 55 56 63 64 65 200 1000]]
      (let [data (mapv (fn [i] (mod (+ (* i 31) n) 256)) (range n))
            key (mapv (fn [i] (mod (+ (* i 7) n) 256)) (range 32))
            iv (mapv (fn [i] (mod (* i 13) 256)) (range 16))]
        (is (= (vec (sha/sha256 data)) (prim/sha256 data)) (str "sha256, " n " bytes"))
        (is (= (portable-hmac key data) (prim/hmac-sha256 key data)) (str "hmac, " n " bytes"))
        (is (= (portable-hmac data key) (prim/hmac-sha256 data key)) (str "hmac with a " n "-byte key"))
        (is (= (aes/aes256-ctr key iv data) (prim/aes256-ctr key iv data)) (str "aes-ctr, " n " bytes"))
        (is (= (pc/xxh3-128 data) (prim/xxh3-128 data)) (str "xxh3-128, " n " bytes"))))))

(deftest random-bytes-are-the-operating-systems
  (let [a (prim/random-bytes 64)
        b (prim/random-bytes 64)
        big (prim/random-bytes 70000)]
    (is (= 64 (count a)))
    (is (every? (fn [x] (and (integer? x) (<= 0 x 255))) a) "a vector of 0..255")
    (is (not= a b) "two draws differ")
    (is (= 70000 (count big)) "past one getRandomValues call's 65,536-byte limit")
    (is (< 250 (count (distinct big))) "spread over the byte values")))

;; RFC 8032 §7.1 TEST 2 and 3 [seed public-key message signature], and the refusals, from test/aegis/ed25519_test.vs
;; (eve/test/eve/net/ed25519_test.cljc holds the portable code to all five vectors)
(def ^:private ED-VECTORS
  [["4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"
    "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
    "72"
    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"]
   ["c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7"
    "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"
    "af82"
    "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"]])
(def ^:private ED-PUB-1 "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
(def ^:private ED-SIG-S-PLUS-L "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901554c8c7872aa064e049dbb3013fbf29380d25bf5f0595bbe24655141438e7a101b")
(def ^:private ED-SIG-IDENTITY "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660cc799d106d5927970e5989f5671131fa27e6c6b3b7f821c5e259a24b02e502e01")

(deftest ed25519-through-the-adapter
  (let [verify? (fn [pub msg sig] (prim/ed25519-verify (b/unhex pub) (b/unhex msg) (b/unhex sig)))]
    (testing "eve.crypto (this host's crypto adapter) answers RFC 8032's keys and signatures"
      (doseq [[seed pub msg sig] ED-VECTORS]
        (is (= pub (b/hex (prim/ed25519-public-key (b/unhex seed)))))
        (is (= sig (b/hex (prim/ed25519-sign (b/unhex seed) (b/unhex msg)))))
        (is (true? (verify? pub msg sig)))))
    (testing "and refuses what eve.net.ed25519 refuses"
      (let [[_ pub msg sig] (second ED-VECTORS)]
        (is (false? (verify? pub "af83" sig)) "another message")
        (is (false? (verify? pub msg (str "7" (subs sig 1)))) "another R")
        (is (false? (verify? (subs pub 0 62) msg sig)) "a 31-byte key")
        (is (false? (verify? pub msg (subs sig 0 126))) "a 63-byte signature"))
      (is (false? (verify? ED-PUB-1 "" ED-SIG-S-PLUS-L)) "S >= L")
      (is (false? (verify? "0100000000000000000000000000000000000000000000000000000000000000" "" ED-SIG-IDENTITY))
          "the identity key: small order")
      (is (false? (verify? "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05" "6d35" "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"))
          "a small-order key of order 8, with a signature the cofactorless equation accepts")
      (is (true? (verify? "8ea6b58beb2ecc0801fbd6c82c6dafee292cd29e8b6698bbb94c566b594d8ba9" "7833" "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0812abda3de3c7ead8eb9c1cee8664befe56bcc7dd06761a1e39c274f1320f93b04"))
          "a mixed-order key, with a signature the cofactorless equation accepts")
      (is (false? (verify? "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f" "" ED-SIG-IDENTITY)) "y >= p")
      (is (false? (verify? "0100000000000000000000000000000000000000000000000000000000000080" "" ED-SIG-IDENTITY))
          "x = 0 with the sign bit set")
      (is (false? (verify? "0200000000000000000000000000000000000000000000000000000000000000" "" ED-SIG-IDENTITY))
          "a key off the curve"))
    ;; the adapter alone, no portable call: on basilisp one portable scalar multiplication takes minutes (§3)
    (testing "a random seed: what the adapter signs, it verifies, and only for that message"
      (let [seed (prim/random-bytes 32)
            msg (prim/random-bytes 100)
            pub (prim/ed25519-public-key seed)
            sig (prim/ed25519-sign seed msg)]
        (is (= 64 (count sig)))
        (is (true? (prim/ed25519-verify pub msg sig)))
        (is (false? (prim/ed25519-verify pub (assoc msg 0 (bit-xor (nth msg 0) 1)) sig)))))))
