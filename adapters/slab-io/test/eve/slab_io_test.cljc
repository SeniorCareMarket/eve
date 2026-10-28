(ns eve.slab-io-test
  "The slab-io feature's conformance test (adapters/README.md, slab-io): every
   implementation of eve.slab-io answers it alike — slab-io/protocol on every
   host, slab-io/vs on ValueScript (bb test:vs). A heap domain's slab I/O
   (eve.deftype-proto.alloc), its blocks read and written at every width and
   alignment, the allocator through the seam, the fused primitives, and a
   region's header words and bitmap. Not in any host's default suite: its
   runner adds adapters/slab-io/test to the roots."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.slab-io :as slab]
            [eve.deftype-proto.alloc :as alloc]
            [eve.encoding :as enc]
            [eve.region :as region]))

(def ^:private sio (alloc/make-jvm-heap-slab-ctx))

(deftest block-reads-and-writes
  (let [o (slab/alloc! sio 40)]
    (testing "u8, u16 and i32 at aligned and unaligned offsets, signed i32 kept"
      (slab/write-u8! sio o 1 0xAB)
      (slab/write-u16! sio o 2 0xBEEF)
      (slab/write-i32! sio o 4 123456)
      (slab/write-i32! sio o 9 -7)
      (slab/write-i32! sio o 13 -2147483648)
      (is (= 0xAB (slab/read-u8 sio o 1)))
      (is (= 0xBEEF (slab/read-u16 sio o 2)))
      (is (= 123456 (slab/read-i32 sio o 4)))
      (is (= -7 (slab/read-i32 sio o 9)))
      (is (= -2147483648 (slab/read-i32 sio o 13))))
    (testing "bytes, and comparing them in place"
      (slab/write-bytes! sio o 20 (enc/bytes-of [1 2 3 250]))
      (is (= [1 2 3 -6] (vec (slab/read-bytes sio o 20 4))))
      (is (true? (slab/bytes-equal-at? sio o 20 (enc/bytes-of [1 2 3 250]))))
      (is (true? (slab/bytes-equal-at? sio o 20 (enc/bytes-of [1 2]))))
      (is (false? (slab/bytes-equal-at? sio o 20 (enc/bytes-of [1 2 4])))))
    (testing "cas answers the value found"
      (is (= 123456 (slab/cas-i32! sio o 4 123456 99)))
      (is (= 99 (slab/read-i32 sio o 4)))
      (is (= 99 (slab/cas-i32! sio o 4 5 6)))
      (is (= 99 (slab/read-i32 sio o 4))))
    (testing "a block copy"
      (let [p (slab/alloc! sio 40)]
        (slab/copy-block! sio p 0 o 0 24)
        (is (= 0xBEEF (slab/read-u16 sio p 2)))
        (is (= -7 (slab/read-i32 sio p 9)))
        (is (= [1 2 3 -6] (vec (slab/read-bytes sio p 20 4))))
        (is (true? (slab/free! sio p)))))
    (testing "a free, and a double free refused"
      (is (true? (slab/free! sio o)))
      (is (false? (slab/free! sio o))))))

(deftest the-allocator-through-the-seam
  (testing "2,000 blocks of 100 B: distinct, each holding its own word, each freed once"
    (let [offs (mapv (fn [i] (let [o (slab/alloc! sio 100)] (slab/write-i32! sio o 8 i) o)) (range 2000))]
      (is (= 2000 (count (set offs))))
      (is (= (range 2000) (map (fn [o] (slab/read-i32 sio o 8)) offs)))
      (is (every? true? (map (fn [o] (slab/free! sio o)) offs)))))
  (testing "a block past 1024 B is the coalescing class 6's"
    (let [o (slab/alloc! sio 5000)]
      (is (= alloc/OVERFLOW_CLASS_IDX (alloc/decode-class-idx o)))
      (slab/write-i32! sio o 4996 77)
      (is (= 77 (slab/read-i32 sio o 4996)))
      (is (true? (slab/free! sio o))))))

(deftest a-logging-slab-io
  (testing "an attempt's slab I/O over the domain (alloc/logging-sio): its allocations logged, its reads and writes the domain's"
    (let [lsio (alloc/logging-sio sio)
          a (slab/alloc! lsio 40)
          b (slab/alloc! lsio 200)]
      (slab/write-i32! lsio a 4 31337)
      (is (= 31337 (slab/read-i32 sio a 4)))
      (slab/write-u8! sio b 3 9)
      (is (= 9 (slab/read-u8 lsio b 3)))
      (is (true? (slab/bytes-equal-at? lsio b 3 (enc/bytes-of [9]))))
      ;; the log is a set (a block the attempt frees itself leaves it, 7faf6597f)
      (is (= #{a b} (set (alloc/logged-allocs lsio))))
      (is (identical? (alloc/sio-root lsio) (alloc/sio-root sio)))
      (alloc/stop-logging! lsio)
      (let [c (slab/alloc! lsio 40)]
        (is (= [] (alloc/logged-allocs lsio)) "stopped: the log is dropped, allocations no longer logged")
        (is (every? true? (map (fn [o] (slab/free! lsio o)) [a b c])))))))

(deftest region-words-and-bitmaps
  (let [r (region/make-heap-region 4096)]
    (testing "header words"
      (slab/region-store-i32! r 8 100000)
      (is (= 100000 (slab/region-load-i32 r 8)))
      (is (= 100000 (slab/region-cas-i32! r 8 100000 7)))
      (is (= 7 (slab/region-add-i32! r 8 5)))
      (is (= 12 (slab/region-sub-i32! r 8 2)))
      (is (= 10 (slab/region-load-i32 r 8)))
      (slab/region-store-i64! r 16 123456789012)
      (is (= 123456789012 (slab/region-load-i64 r 16)))
      (is (= 123456789012 (slab/region-cas-i64! r 16 123456789012 5)))
      (is (= 5 (slab/region-load-i64 r 16))))
    (testing "a 70-bit bitmap at byte 64: find, set, refuse a set bit, free"
      (is (= 0 (slab/bitmap-find-free r 64 70 0)))
      (is (true? (slab/bitmap-alloc-cas! r 64 0)))
      (is (false? (slab/bitmap-alloc-cas! r 64 0)))
      (is (= 1 (slab/bitmap-find-free r 64 70 0)))
      (doseq [b (range 1 69)] (slab/bitmap-alloc-cas! r 64 b))
      (is (= 69 (slab/bitmap-find-free r 64 70 3)))
      (is (true? (slab/bitmap-alloc-cas! r 64 69)))
      (is (= -1 (slab/bitmap-find-free r 64 70 0)))
      (is (true? (slab/bitmap-free! r 64 31)))
      (is (false? (slab/bitmap-free! r 64 31)))
      (is (= 31 (slab/bitmap-find-free r 64 70 0)))
      (is (= -1 (slab/bitmap-find-free r 64 70 32))))))
