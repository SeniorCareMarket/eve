(ns eve.region-segment-test
  "Segmented mmap regions (eve.region, JVM varhandle adapter): a
   file-backed region is a table of MappedByteBuffer windows, so a
   slab file is no longer walled at 2^31 bytes by FileChannel.map's
   single-mapping limit.  These tests use 4096-byte segments so every
   boundary shape is exercised on a small file: aligned and unaligned
   accesses straddling a boundary, bulk reads/writes crossing several
   segments, ranged sync across a boundary, growth re-mapping only
   the delta (:reuse-from reuses unchanged windows), the Lustre
   variant, :segment-bytes validation, and heap regions (a single
   segment) unchanged.  Design: docs/eve-region-segmentation/REPORT.md."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.mem :as m]
            [eve.region :as r])
  (:import [java.io File]
           [java.util Arrays]))

(defn- tmp-path []
  (let [f (File/createTempFile "eve-seg-test" ".bin")]
    (.delete f)
    (.getAbsolutePath f)))

(deftest heap-region-is-one-segment
  (let [h (r/make-heap-region 1024)]
    (m/-store-i32! h 0 1234567)
    (is (= 1234567 (m/-load-i32 h 0)))
    (m/-store-i64! h 8 9876543210)
    (is (= 9876543210 (m/-load-i64 h 8)))
    (is (= 1234567 (m/-cas-i32! h 0 1234567 -5)))
    (is (= -5 (m/-load-i32 h 0)))
    (is (some? (m/-heap-bytes h)))))

(deftest segmented-mmap-ops
  (let [r1 (r/open-mmap-region (tmp-path) (* 4096 5) {:segment-bytes 4096})]
    (is (= 5 (count (.segments r1))))
    (testing "aligned i32 in every segment"
      (doseq [off [0 4092 4096 8192 12284]]
        (m/-store-i32! r1 off (+ off 7))
        (is (= (+ off 7) (m/-load-i32 r1 off)))))
    (testing "CAS and add on an aligned offset by a boundary"
      (is (= 12291 (m/-cas-i32! r1 12284 12291 42)))
      (is (= 42 (m/-add-i32! r1 12284 8)))
      (is (= 50 (m/-load-i32 r1 12284))))
    (testing "unaligned i32 straddling a segment boundary (4094..4097)"
      (m/-store-i32! r1 4094 0x0A0B0C0D)
      (is (= 0x0A0B0C0D (m/-load-i32 r1 4094)))
      (is (= 0x0A0B0C0D (m/-plain-load-i32 r1 4094))))
    (testing "unaligned i64 straddling a segment boundary (8190..8197)"
      (m/-store-i64! r1 8190 0x1122334455667788)
      (is (= 0x1122334455667788 (m/-load-i64 r1 8190))))
    (testing "bulk write/read crossing segments (13000, 7000 bytes)"
      (let [src (byte-array (map (fn [i] (unchecked-byte (mod i 251))) (range 7000)))]
        (m/-write-bytes! r1 13000 src)
        (is (Arrays/equals src (m/-read-bytes r1 13000 7000)))))
    (testing "sync whole and ranged across a boundary"
      (m/-sync! r1)
      (m/-sync-range! r1 4000 300))))

(deftest growth-remap-reuses-windows
  (let [p  (tmp-path)
        r1 (r/open-mmap-region p (* 4096 5) {:segment-bytes 4096})]
    (m/-store-i32! r1 4094 0x0A0B0C0D)
    (let [r2 (r/open-mmap-region p (* 4096 8) {:segment-bytes 4096 :reuse-from r1})]
      (is (= 8 (count (.segments r2))))
      (testing "unchanged full windows are the same buffers (only the delta mapped)"
        (doseq [k [0 1 2 3]]
          (is (identical? (aget (.segments r1) k) (aget (.segments r2) k)))))
      (is (= 0x0A0B0C0D (m/-load-i32 r2 4094)) "data survives the growth re-map")
      (m/-store-i32! r2 30000 777)
      (is (= 777 (m/-load-i32 r2 30000)) "the delta windows are writable"))))

(deftest lustre-segmented
  (let [l (r/open-mmap-region (tmp-path) (* 4096 3) {:segment-bytes 4096 :lustre? true})]
    (is (r/lustre-region? l))
    (m/-store-i32! l 4096 111)
    (is (= 111 (m/-load-i32 l 4096)))
    (is (= 111 (m/-cas-i32! l 4096 111 222)))
    (is (= 222 (m/-add-i32! l 4096 3)))
    (m/-store-i32! l 4094 0x0A0B0C0D)
    (is (= 0x0A0B0C0D (m/-load-i32 l 4094)) "lustre unaligned straddle")
    (m/-store-i64! l 8190 0x1122334455667788)
    (is (= 0x1122334455667788 (m/-load-i64 l 8190)) "lustre i64 straddle")
    (let [src (byte-array (map (fn [i] (unchecked-byte (mod i 251))) (range 5000)))]
      (m/-write-bytes! l 5000 src)
      (is (Arrays/equals src (m/-read-bytes l 5000 5000)) "lustre bulk straddle"))
    (m/-sync! l)
    (m/-sync-range! l 4000 300)))

(deftest segment-bytes-validation
  (doseq [bad [1000 4095 (bit-shift-left 1 31) 0 -4096]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"segment-bytes"
                          (r/open-mmap-region (tmp-path) 4096 {:segment-bytes bad}))
        (str "rejects " bad)))
  (testing "a window larger than the file is one segment; the default is one segment here"
    (is (= 1 (count (.segments (r/open-mmap-region (tmp-path) 4096 {:segment-bytes 1048576})))))
    (is (= 1 (count (.segments (r/open-mmap-region (tmp-path) 4096)))))))
