;; eve.net.ed25519 held to RFC 8032 §7.1 and to the kernel's refusals — the vectors are test/aegis/ed25519_test.vs's,
;; copied by script (provenance: test/aegis/fixtures/vectors/rfc8032_7_1.json; OpenSSL reproduces every key and
;; signature), so the portable code and the kernel answer the same table.
(ns eve.net.ed25519-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.net.bytes :as b]
            [eve.net.ed25519 :as ed]
            [eve.net.sha512 :as sha512]))

(def RFC8032-VECTORS
  [["1" "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    ""
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"]
   ["2" "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"
    "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
    "72"
    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"]
   ["3" "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7"
    "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"
    "af82"
    "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"]
   ["1024" "f5e5767cf153319517630f226876b86c8160cc583bc013744c6bf255f5cc0ee5"
    "278117fc144c72340f67d0f2316e8386ceffbf2b2428c9c51fef7c597f1d426e"
    "08b8b2b733424243760fe426a4b54908632110a66c2f6591eabd3345e3e4eb98fa6e264bf09efe12ee50f8f54e9f77b1e355f6c50544e23fb1433ddf73be84d879de7c0046dc4996d9e773f4bc9efe5738829adb26c81b37c93a1b270b20329d658675fc6ea534e0810a4432826bf58c941efb65d57a338bbd2e26640f89ffbc1a858efcb8550ee3a5e1998bd177e93a7363c344fe6b199ee5d02e82d522c4feba15452f80288a821a579116ec6dad2b3b310da903401aa62100ab5d1a36553e06203b33890cc9b832f79ef80560ccb9a39ce767967ed628c6ad573cb116dbefefd75499da96bd68a8a97b928a8bbc103b6621fcde2beca1231d206be6cd9ec7aff6f6c94fcd7204ed3455c68c83f4a41da4af2b74ef5c53f1d8ac70bdcb7ed185ce81bd84359d44254d95629e9855a94a7c1958d1f8ada5d0532ed8a5aa3fb2d17ba70eb6248e594e1a2297acbbb39d502f1a8c6eb6f1ce22b3de1a1f40cc24554119a831a9aad6079cad88425de6bde1a9187ebb6092cf67bf2b13fd65f27088d78b7e883c8759d2c4f5c65adb7553878ad575f9fad878e80a0c9ba63bcbcc2732e69485bbc9c90bfbd62481d9089beccf80cfe2df16a2cf65bd92dd597b0707e0917af48bbb75fed413d238f5555a7a569d80c3414a8d0859dc65a46128bab27af87a71314f318c782b23ebfe808b82b0ce26401d2e22f04d83d1255dc51addd3b75a2b1ae0784504df543af8969be3ea7082ff7fc9888c144da2af58429ec96031dbcad3dad9af0dcbaaaf268cb8fcffead94f3c7ca495e056a9b47acdb751fb73e666c6c655ade8297297d07ad1ba5e43f1bca32301651339e22904cc8c42f58c30c04aafdb038dda0847dd988dcda6f3bfd15c4b4c4525004aa06eeff8ca61783aacec57fb3d1f92b0fe2fd1a85f6724517b65e614ad6808d6f6ee34dff7310fdc82aebfd904b01e1dc54b2927094b2db68d6f903b68401adebf5a7e08d78ff4ef5d63653a65040cf9bfd4aca7984a74d37145986780fc0b16ac451649de6188a7dbdf191f64b5fc5e2ab47b57f7f7276cd419c17a3ca8e1b939ae49e488acba6b965610b5480109c8b17b80e1b7b750dfc7598d5d5011fd2dcc5600a32ef5b52a1ecc820e308aa342721aac0943bf6686b64b2579376504ccc493d97e6aed3fb0f9cd71a43dd497f01f17c0e2cb3797aa2a2f256656168e6c496afc5fb93246f6b1116398a346f1a641f3b041e989f7914f90cc2c7fff357876e506b50d334ba77c225bc307ba537152f3f1610e4eafe595f6d9d90d11faa933a15ef1369546868a7f3a45a96768d40fd9d03412c091c6315cf4fde7cb68606937380db2eaaa707b4c4185c32eddcdd306705e4dc1ffc872eeee475a64dfac86aba41c0618983f8741c5ef68d3a101e8a3b8cac60c905c15fc910840b94c00a0b9d0"
    "0aab4c900501b3e24d7cdf4663326a3a87df5e4843b2cbdb67cbf6e460fec350aa5371b1508f9f4528ecea23c436d94b5e8fcd4f681e30a6ac00a9704a188a03"]
   ["SHA(abc)" "833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42"
    "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf"
    "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
    "dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704"]])
;; S >= L: TEST 1's R with S + L (OpenSSL refuses it too)
(def SIG-S-PLUS-L "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901554c8c7872aa064e049dbb3013fbf29380d25bf5f0595bbe24655141438e7a101b")
;; the identity public key (0, 1): R = TEST 2's public key [a']B, S = a' mod L (a' its clamped scalar),
;; so [S]B = R + [k]·identity for ANY message — the canonical key verifies; y = p + 1 (≡ 1) and x = 0 with
;; the sign bit set name the same point non-canonically and must be refused (RFC 8032 §5.1.3)
(def SIG-IDENTITY "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660cc799d106d5927970e5989f5671131fa27e6c6b3b7f821c5e259a24b02e502e01")
(def PUB-IDENTITY "0100000000000000000000000000000000000000000000000000000000000000")
(def PUB-Y-P-PLUS-1 "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")
(def PUB-X0-SIGN "0100000000000000000000000000000000000000000000000000000000000080")
;; off-curve: y = 2, the least y >= 2 with (y^2 - 1)/(d y^2 + 1) a non-square mod p (Euler's criterion)
(def PUB-OFF-CURVE "0200000000000000000000000000000000000000000000000000000000000000")

;; small-order keys [the key, a message, a signature (R = [r]B, S = r) the cofactorless equation accepts under it] —
;; the eight multiples of the order-8 point, made with Python integers (RFC 8032 §6's arithmetic); and a mixed-order key
;; [a]B + T8 with a signature the equation accepts
(def SMALL-ORDER-CASES
  [["0100000000000000000000000000000000000000000000000000000000000000" "6d30"
    "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"]
   ["26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05" "6d35"
    "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"]
   ["0000000000000000000000000000000000000000000000000000000000000000" "6d30"
    "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"]
   ["c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a" "6d3130"
    "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"]
   ["ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f" "6d31"
    "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"]
   ["c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa" "6d34"
    "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"]
   ["0000000000000000000000000000000000000000000000000000000000000080" "6d34"
    "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"]
   ["26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85" "6d3134"
    "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0813930000000000000000000000000000000000000000000000000000000000000"]])
(def MIXED-ORDER-CASE ["8ea6b58beb2ecc0801fbd6c82c6dafee292cd29e8b6698bbb94c566b594d8ba9" "7833" "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a0812abda3de3c7ead8eb9c1cee8664befe56bcc7dd06761a1e39c274f1320f93b04"])

(defn- flip "hex text with byte i XORed by mask" [h i mask]
  (let [bs (b/unhex h)] (b/hex (assoc bs i (bit-xor (nth bs i) mask)))))

(defn- verify? [pub msg sig] (ed/verify (b/unhex pub) (b/unhex msg) (b/unhex sig)))

(deftest sha-512
  ;; FIPS 180-4's examples (one block, two blocks) and the empty message
  (is (= "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"
         (b/hex (sha512/sha512 []))))
  (is (= "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
         (b/hex (sha512/sha512 (b/utf8 "abc")))))
  (is (= "8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909"
         (b/hex (sha512/sha512 (b/utf8 "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu")))))
  (testing "every padding boundary: 111, 112 and 128 bytes"
    (doseq [[n h] [[111 "fa9121c7b32b9e01733d034cfc78cbf67f926c7ed83e82200ef86818196921760b4beff48404df811b953828274461673c68d04e297b0eb7b2b4d60fc6b566a2"] [112 "c01d080efd492776a1c43bd23dd99d0a2e626d481e16782e75d54c2503b5dc32bd05f0f1ba33e568b88fd2d970929b719ecbb152f58f130a407c8830604b70ca"] [128 "b73d1929aa615934e61a871596b3f3b33359f42b8175602e89f7e06e5f658a243667807ed300314b95cacdd579f3e33abdfbe351909519a846d465c59582f321"]]]
      (is (= h (b/hex (sha512/sha512 (vec (repeat n 97))))) (str n " bytes")))))

(deftest rfc-8032-7-1
  (is (= ["1" "2" "3" "1024" "SHA(abc)"] (mapv first RFC8032-VECTORS)))
  (doseq [[nm seed pub msg sig] RFC8032-VECTORS]
    (is (= pub (b/hex (ed/public-key (b/unhex seed)))) (str "TEST " nm " public key"))
    (is (= sig (b/hex (ed/sign (b/unhex seed) (b/unhex msg)))) (str "TEST " nm " signature"))
    (is (true? (verify? pub msg sig)) (str "TEST " nm " verify"))))

(deftest rejections
  (let [[_ _ pub1 msg1 sig1] (nth RFC8032-VECTORS 0)
        [_ _ pub3 msg3 sig3] (nth RFC8032-VECTORS 2)]
    (testing "a flipped bit of the message, of R, of S"
      (is (false? (verify? pub3 (flip msg3 0 1) sig3)))
      (is (false? (verify? pub3 msg3 (flip sig3 0 1))))
      (is (false? (verify? pub3 msg3 (flip sig3 40 1)))))
    (testing "S >= L: TEST 1's R with S + L is refused (the same point; the scalar is not canonical)"
      (is (false? (verify? pub1 msg1 SIG-S-PLUS-L))))
    (testing "a small-order key is refused — the identity, whose signature verifies for any message under the kernel's
              rule, and the other seven, each with a signature the cofactorless equation accepts"
      (is (false? (verify? PUB-IDENTITY "" SIG-IDENTITY)))
      (is (false? (verify? PUB-IDENTITY "deadbeef" SIG-IDENTITY)))
      (doseq [[pub msg sig] SMALL-ORDER-CASES]
        (is (false? (verify? pub msg sig)) pub)))
    (testing "a mixed-order key ([a]B + T8) verifies a signature the cofactorless equation accepts: every platform
              measured agrees"
      (let [[pub msg sig] MIXED-ORDER-CASE] (is (true? (verify? pub msg sig)))))
    (testing "y >= p: the identity spelled y = p + 1 is refused"
      (is (false? (verify? PUB-Y-P-PLUS-1 "" SIG-IDENTITY))))
    (testing "x = 0 with the sign bit set is refused"
      (is (false? (verify? PUB-X0-SIGN "" SIG-IDENTITY))))
    (testing "an off-curve public key is refused"
      (is (false? (verify? PUB-OFF-CURVE "" SIG-IDENTITY)))
      (is (false? (verify? PUB-OFF-CURVE msg1 sig1))))
    (testing "a public key that is not 32 bytes or a signature that is not 64 verifies false"
      (is (false? (verify? (subs pub1 0 62) msg1 sig1)))
      (is (false? (verify? pub1 msg1 (subs sig1 0 126)))))
    (testing "a seed that is not 32 bytes refuses by name"
      ;; the message's start: glojure's ex-message appends its stack to the message
      (is (= "ed25519: a seed is 32 bytes, not 1"
             (let [m (try (ed/public-key [0]) nil (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) e (ex-message e)))]
               (subs m 0 (min (count m) 34))))))))
