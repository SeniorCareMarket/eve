(ns eve.array-legacy-read-test
  "Reading an old 0x1C array block does not rewrite it (DIVERGENCE-CENSUS
   §8, D6). An old block carries its subtype at byte 0, where the 0x1D
   header carries the type-id; the reader used to convert the header in
   place — a read that writes, into a block that may sit on a page its
   reader does not own. The instance now takes its subtype from byte 0
   and the block's bytes are the reader's to leave alone."
  (:require #?(:cljs [cljs.test :refer [deftest testing is]]
               :clj  [clojure.test :refer [deftest testing is]])
            [eve.core :as e]
            [eve.array :as arr]
            [eve.codec :as codec]
            [eve.slab-io :as slab]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]))

(deftest reading-an-old-array-block-does-not-rewrite-it
  (testing "an old block, read through its 0x1C pointer, keeps its bytes"
    (let [sio (alloc/heap-domain-sio! {})
          off (slab/alloc! sio 20)]
      ;; an old block by hand: [subtype:u8 @0][pad @1-3][count:u32 @4][data @8+]
      (slab/write-u8!  sio off 0 6) ; subtype 6 = :int32
      (slab/write-i32! sio off 4 3)
      (slab/write-i32! sio off 8 10)
      (slab/write-i32! sio off 12 20)
      (slab/write-i32! sio off 16 30)
      (let [before (vec (slab/read-bytes sio off 0 20))
            a      (codec/decode sio (codec/encode-sab-pointer ser/FAST_TAG_EVE_ARRAY off))]
        (is (= 3 (e/count a)) "the old block's count")
        (is (= [10 20 30] [(arr/aget a 0) (arr/aget a 1) (arr/aget a 2)])
            "the old block's elements, read by its own subtype")
        (is (= before (vec (slab/read-bytes sio off 0 20)))
            "the block's bytes are unchanged by the read")
        (let [a2 (codec/decode sio (codec/encode-sab-pointer ser/FAST_TAG_EVE_ARRAY off))]
          (is (= [10 20 30] [(arr/aget a2 0) (arr/aget a2 1) (arr/aget a2 2)])
              "a second read answers the same array"))))))
