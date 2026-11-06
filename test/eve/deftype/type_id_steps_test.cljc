(ns eve.deftype.type-id-steps-test
  "The derivation of an eve-deftype's type-id (eve.deftype.registry/type-id-of),
   one step per assertion, for the two names eve.deftype.type-id-test asserts:

     key bytes  (eve.codec/encode-key \"eve.deftype.type-id-test/Apple\")
     digest     (eve.hamt-util/xxh3-128-bytes key-bytes)
     fold       (eve.hamt-util/fold-hash-int digest)          a signed int32
     type-id    0x40 + (mod fold 173)                          floored mod
     name-hash  (bit-and fold 0xFFFFFF)

   The type-id is a cross-host contract (operator ruling 2026-11-05: the same
   type definition receives the same ID on every host), so every literal here is
   the same on every host; a host that derives another type-id fails at the first
   step that leaves the contract, which names the producer to fix. The literals
   are babashka 1.13.229's (whose eve.xxh3-kat-test is 4 / 83 green) and agree
   with the type-id byte 0x4E that eve.deftype.type-id-test asserts on every host.
   Apple's fold is negative, so its type-id also depends on `mod` being floored:
   (mod -1244416666 173) is 14, where a truncating remainder gives -159."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eve.codec :as codec]
            [eve.hamt-util :as hu]
            [eve.deftype.registry :as r]))

(defn- hex [b]
  (str/join (map #(let [x (bit-and % 0xFF)]
                    (str (nth "0123456789abcdef" (quot x 16)) (nth "0123456789abcdef" (mod x 16))))
                 b)))

(def ^:private rows
  [{:k "eve.deftype.type-id-test/Apple"
    :key-bytes "eedb051e6576652e646566747970652e747970652d69642d746573742f4170706c65"
    :digest "f2ecec3c2541cc2792c13e40f0bfab3d"
    :fold -1244416666
    :type-id 0x4E
    :name-hash 0xD3B566}
   {:k "eve.deftype.type-id-test/Peach"
    :key-bytes "eedb051e6576652e646566747970652e747970652d69642d746573742f5065616368"
    :digest "e715f336ed905b8f6609ae5a4222d29a"
    :fold 783209593
    :type-id 0x4E
    :name-hash 0xAED479}])

(deftest each-step-of-the-type-id
  (doseq [{:keys [k key-bytes digest fold type-id name-hash]} rows]
    (testing k
      (let [kb (codec/encode-key k)
            d (hu/xxh3-128-bytes kb)]
        (is (= key-bytes (hex kb)) "1 key bytes: eve.codec/encode-key")
        (is (= digest (hex (hu/xxh3-128-bytes (codec/encode-key k)))) "2 digest: eve.hamt-util/xxh3-128-bytes")
        (is (= digest (hex d)) "2 digest, over the same key bytes")
        (is (= fold (hu/fold-hash-int d)) "3 fold: eve.hamt-util/fold-hash-int")
        (is (= fold (r/string-hash k)) "3 fold: eve.deftype.registry/string-hash")
        (is (= type-id (r/type-id-of k)) "4 type-id: 0x40 + (mod fold 173)")
        (is (= name-hash (r/name-hash-of k)) "5 name-hash: (bit-and fold 0xFFFFFF)")))))

(deftest mod-is-floored
  (testing "the type-id's mod of a negative int32 fold (Apple's), and its neighbours"
    (is (= 14 (mod -1244416666 173)))
    (is (= -159 (rem -1244416666 173)))
    (is (= 172 (mod -1 173)))
    (is (= 0 (mod -173 173)))
    (is (= 14 (mod 783209593 173)))))
