(ns eve.descriptor-table-test
  "Every block has a descriptor (doc/unification/FORMAT-DECISIONS.md §11): per-field descriptor COLUMNS on every
   class, the same in eve and in ValueScript's kernel. A bitmap class's descriptor i is block i's: its COUNT and
   FLAGS entries sit 4 bytes from their neighbours' in their columns, and a free resets the freed block's count
   and flags before its bit clears. Class 6's lock is bit 0 of LOCK_OWNER, so a flag beside it leaves it
   takeable. An mmap domain carries each bitmap class's columns in .slabN.desc-count / .desc-flags files."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as a]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.coalesc :as coalesc]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem]
            [eve.process :as proc]
            [eve.region :as region]
            [eve.slab-io :as slab]
            #?(:cljs ["os" :as os])))

(deftest every-bitmap-block-has-a-descriptor
  (let [sio (alloc/heap-domain-sio! {})]
    (doseq [size [24 60 120 250 500 1000]]                        ;; classes 0-5
      (let [b0 (slab/alloc! sio size)
            b1 (slab/alloc! sio size)
            [[cr0 co0] [fr0 fo0]] (alloc/desc-of sio b0)
            [[cr1 co1] [fr1 fo1]] (alloc/desc-of sio b1)]
        (testing (str size " bytes")
          (is (some? cr0) "a bitmap-class block has a descriptor")
          (is (= (* 4 (- (alloc/decode-block-idx b1) (alloc/decode-block-idx b0))) (- co1 co0))
              "descriptor i is block i's: count entries sit 4 bytes apart in the COUNT column")
          (is (= (- co1 co0) (- fo1 fo0)) "the FLAGS column marches with the COUNT column")
          (is (zero? (mem/-load-i32 cr0 co0)) "a fresh block's count is 0")
          (is (zero? (mem/-load-i32 fr0 fo0)) "a fresh block's flags are clear"))
        (testing "the free resets the descriptor"
          (mem/-store-i32! cr0 co0 5)
          (mem/-store-i32! fr0 fo0 2)
          (is (true? (slab/free! sio b0)))
          (is (zero? (mem/-load-i32 cr0 co0)))
          (is (zero? (mem/-load-i32 fr0 fo0))))
        (testing "the descriptors are beside the blocks, never in them: a block's bytes survive a descriptor write"
          (slab/write-i32! sio b1 0 1234567)
          (mem/-store-i32! cr1 co1 7)
          (is (= 1234567 (slab/read-i32 sio b1 0)))
          (slab/free! sio b1))))))

(deftest a-flag-beside-the-lock-does-not-block-it
  (let [data-size (* 64 1024)
        layout    (coalesc/coalesc-layout data-size coalesc/MAX_DESCRIPTORS)
        r         (region/make-heap-region (:total-bytes layout))]
    (coalesc/init-coalesc-region! r data-size coalesc/MAX_DESCRIPTORS)
    (let [dt (mem/-load-i32 r d/SLAB_HDR_BITMAP_OFFSET)
          ;; descriptor 0's FLAGS entry: the FLAGS column starts 16 bytes-per-descriptor into the columns
          flags0 (+ dt (* 16 coalesc/MAX_DESCRIPTORS))]
      ;; descriptor 0 is the one FREE extent: a flag (bit 1) beside its lock bit
      (mem/-store-i32! r flags0 2)
      (is (some? (coalesc/try-coalesc-alloc! r 2000)) "the lock is bit 0: a flag beside it leaves it takeable")
      (is (zero? (mem/-load-i32 r flags0)) "the hand-out clears the flags and releases the lock"))))

(defn- fresh-base []
  (str #?(:cljs (.tmpdir os) :lpy "/tmp" :clje "/tmp" :jank "/tmp" :default (System/getProperty "java.io.tmpdir"))
       "/eve-descriptor-table-" (rand-int 1000000000) "/d"))

(deftest an-mmap-domain-carries-desc-files
  (let [base (fresh-base)
        dom  (a/persistent-atom-domain base)
        x    (a/lookup-or-create-mmap-atom! dom "t/x" {:a 1})]
    (is (= {:a 1} @x))
    (doseq [i (range 6)]
      (is (proc/file-exists? (d/slab-desc-count-path base i)) (str ".slab" i ".desc-count"))
      (is (proc/file-exists? (d/slab-desc-flags-path base i)) (str ".slab" i ".desc-flags")))
    (a/close-atom-domain! dom)))
