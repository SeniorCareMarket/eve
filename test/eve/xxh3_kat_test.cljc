(ns eve.xxh3-kat-test
  "Known-answer tests for eve's hash: XXH3-128 in eve.hamt-util.

   The expected digests are the reference XXH3-128 (seed 0, default
   secret) of deterministic content — byte i of a length-L input is
   (i*31 + L*7 + 13) mod 256 — generated with the python xxhash library
   and cross-checked against ValueScript's kernel's xxh3_128 header
   KATs (empty input -> 99aa06d3..., abc -> 06b05ab6...). Every length path of
   the algorithm is covered, including the >240 striped long path and
   multi-block inputs. This suite also gates the host adapter slot: a
   native impl may be registered (eve.hamt-util/register-xxh3-128-impl!)
   only when this suite passes with it in place, on that host."
  (:refer-clojure :exclude [aget alength byte-array unchecked-byte bytes])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [field aget alength byte-array unchecked-byte bytes]]
            [clojure.string :as str]
            [eve.encoding :as enc]
            [eve.hamt-util :as hu]))

(defn- content-bytes [len]
  (let [arr (enc/make-bytes len)]
    (dotimes [i len]
      (aset ^bytes arr i (unchecked-byte (mod (+ (* i 31) (* len 7) 13) 256))))
    arr))

(defn- hex [b]
  #?(:cljs (str/join (map (fn [i] (let [h (.toString (aget b i) 16)]
                                    (if (< (aget b i) 16) (str "0" h) h)))
                          (range (field b length))))
     :default (str/join (map #(let [x (bit-and % 0xFF)]
                                (str (nth "0123456789abcdef" (quot x 16))
                                     (nth "0123456789abcdef" (mod x 16))))
                             b))))

(defn- blen [bs] (alength ^bytes bs))

(def ^:private kat-vectors
  ;; length -> reference xxh3-128 hexdigest of (content-bytes length)
{
   0 "99aa06d3014798d86001c324468d497f"
   1 "f5950428e527e5ba324714f62fca15ce"
   2 "5e3be73c88c477cc669a7b3c708e113e"
   3 "8e0dd895b5950e835a0c50c062204a6b"
   4 "98641bea51911284ae1e40aa8c2955b1"
   5 "092cee97801e713ff860a039c9d403f1"
   6 "2b935e435380cbac756b466daf3f1e28"
   7 "bfba79bf86769fe708210e7a8069ffc8"
   8 "ed9fa6095cede5dfddd2152e275cd041"
   9 "d0a83a4c2eafe1c8748f7e0112e08078"
   10 "e30d033ded7ae30a867581560f44f97d"
   11 "0ca4e24ce768b26aecd12cb7c130a244"
   12 "f558955139ec5181447e76c46a952225"
   13 "7ad9065d0a206d7380a9b1c7836f36c2"
   14 "eec2f721fa6b30905b01970ea7bf7bc8"
   15 "14e46ee51c7eb6c62c32954f5ff9fd27"
   16 "90ef8977caee100b86f2ddc109de9c30"
   17 "077ed7a0589e3c70e26ca2f5f974da3f"
   18 "4c23d87704d24706cb33127a6fdc4b44"
   19 "be4a938eb4c87c055a9802826662e69e"
   20 "d524d38a6ba9007eee380d5e43c74f07"
   24 "3eca8a0867988f91cc3351f02f3ca69f"
   25 "888167ab637086b956d548962656c673"
   31 "0ab2029dcbbc7077c30df3e7602efb66"
   32 "0cf350a8ed989c67bbcd1b2bb32b2b57"
   33 "cfe5872844631472ef93da221c3a88b2"
   47 "c999a74c5008267717e4c8c872bd5a02"
   48 "17ddbb7ba337da5035697e6f81a07994"
   49 "ec4d15f8faa6757895ab49652229634a"
   63 "1ec305f0eb44a9aef395f6e06ef84eba"
   64 "c3d7aaacd5229b8101262cb79c21ea27"
   65 "e396c11bdca1b9ded8406adc791b3dee"
   79 "8b456ea2e77dfd55a45713f62df2792c"
   80 "6a286c11b907f48258bcb7f18fde979a"
   81 "beaf03e4a1dc3983976ad046cb305d9c"
   95 "36a3da47550eb2f849d08f8d98ef5168"
   96 "b4d3f975cbb599c85035d310042232b2"
   97 "c4c23fb4a621e069bdfaeda0fd28db2d"
   111 "b9a539e3e57ea9287297e466b339eb13"
   112 "3a4189ab9f42f58909c62c4801a87015"
   113 "ae9ab822b2f13cfa4c120a40b221f508"
   127 "a2a7d5c2bfb9c13d663a30b245de343c"
   128 "67dc7f54ea76c3278450a61e24163bcf"
   129 "5a3dfd3d1f39956b69d046cf9fdf2bfb"
   130 "4a390a1a6765a22a8adf2bc6fdea45f4"
   143 "cbf9c8584ba34ed75fc46669bbee7d35"
   144 "6178107e03bd4957e1f988898c405d29"
   159 "bc173ffe69abf2b6cc06650259f1fab6"
   160 "123f36e39cc99398c5b467ccefef1e52"
   161 "6eea0b317e4a88d8c6d5eb08c07ef900"
   175 "dcc0dc34325f379681d80f956828d18c"
   176 "da801e21b7a8d8031b8db598a86cc69a"
   191 "80f603385ed41978a14ea01229329745"
   192 "a43582ca457df18829e84a87bfffe6c8"
   193 "941b4ef636758cee856082f141660dc0"
   207 "21950553ef432b58210ccccaeab95ff5"
   208 "a645e4dd139107a1d91119fa280ddb29"
   223 "8972a5e3bd6ab1c9a771fcf584a37896"
   224 "8c81222e79cb4f7abba9b0a0a39e8bf9"
   225 "435b841a1100b2726890772732c75483"
   239 "09fa1c0e064b916ab921928847a1e3f4"
   240 "2c5267e5b9556f21a5a17bc69da38eeb"
   241 "c0867363bf33d923e1cb919e69b83c0b"
   242 "295b6bf091fe9df2a3e61febd075a19a"
   255 "7e96d70bc161c1784aee63bb5cc8b641"
   256 "16c7cd9b58d3beef92c71de7bf0cee05"
   257 "dab7127368dd1de31b04731f2554e2ee"
   300 "110e286fefe6f98d499a99b0452a79e0"
   500 "306ad057cb7038b729f2a2d586595c26"
   1000 "aa16cc9732e19d203d13acaf2ba23a1c"
   1023 "1c0d1d471a8c2970f53545ec6ef2a3c6"
   1024 "eca300e7908ddd983fbee32a6395581a"
   1025 "1345657091d3e00db50dbbf5b061a73a"
   1100 "b065e385bc58bd7d55e56dd5035c600f"
   2048 "a0b59570119e44623ab046cf99b68016"
   4097 "424a460f0d1c7d890e92aba79a329317"
   10000 "75c5ae6fc03de47a33ca71bf1a9f3681"})

(deftest xxh3-128-kat-test
  (doseq [[len expected] (sort-by key kat-vectors)]
    (is (= expected (hex (hu/xxh3-128-bytes (content-bytes len))))
        (str "xxh3-128 mismatch at length " len))))

(deftest xxh3-128-header-kat-test
  (testing "the kernel header KATs"
    (is (= "99aa06d3014798d86001c324468d497f"
           (hex (hu/xxh3-128-bytes (content-bytes 0)))))
    (is (= "06b05ab6733a618578af5f94892f3950"
           (hex (hu/xxh3-128-bytes (enc/utf8-bytes "abc")))))))

(deftest key-hash-bytes-test
  (is (= 16 (blen (hu/key-hash-bytes (content-bytes 5)))))
  (is (= (hex (hu/xxh3-128-bytes (content-bytes 42)))
         (hex (hu/key-hash-bytes (content-bytes 42))))))

(deftest fold-hash-int-test
  (testing "the int32 fold is deterministic and int-sized"
    (let [hb (hu/xxh3-128-bytes (content-bytes 7))
          f (hu/fold-hash-int hb)]
      (is (= f (hu/fold-hash-int hb)))
      (is (<= -2147483648 f 2147483647))))
  )
