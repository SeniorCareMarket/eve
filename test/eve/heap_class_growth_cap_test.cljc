(ns eve.heap-class-growth-cap-test
  "Regression: a heap bitmap class grew by unbounded doubling. eve.db.ds's
   upstream test-rule-performance-on-larger-datasets (one 50k-entity
   db-with) doubled class 0 (32 B blocks) to 33554432 blocks; the next
   doubling asked make-heap-region for 2.9 GB and the JVM's int cast threw
   ArithmeticException: integer overflow (region.clj:757) — on that and on
   every later allocation that reached the full class, so the whole rest of
   a one-process suite errored. A heap class now stops growing before its
   one-buffer layout passes d/MAX_HEAP_CLASS_BYTES (i32 offsets, one JVM
   byte array); the allocator spills to the next class instead."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.data :as d]
            #?@(:bb [] :clj [[eve.deftype-proto.alloc :as alloc]])))

(defn- layout-bytes [blk n] (:total-bytes (d/slab-layout blk (* n blk))))

(deftest growth-doubles-below-the-cap
  (is (= 2048 (d/heap-class-growth-total 32 1024 0)))
  (is (= 1500 (d/heap-class-growth-total 32 1024 1500)) "max-blocks caps the doubling")
  (is (nil? (d/heap-class-growth-total 32 1024 1024)) "at max-blocks: no growth")
  (is (= 33554432 (d/heap-class-growth-total 32 16777216 0))
      "the last doubling eve.db.ds's 50k-entity db-with took (1.4 GB layout)"))

(deftest growth-past-the-cap-is-refused
  (testing "the doubling that overflowed: 32 B blocks, 2^25 -> 2^26"
    (is (> (layout-bytes 32 67108864) d/MAX_HEAP_CLASS_BYTES))
    (is (nil? (d/heap-class-growth-total 32 33554432 0))))
  (testing "every class refuses before its layout passes the cap"
    (doseq [blk [32 64 128 256 512 1024]]
      (let [last-ok (loop [n 1024]
                      (if-let [n' (d/heap-class-growth-total blk n 0)] (recur n') n))]
        (is (<= (layout-bytes blk last-ok) d/MAX_HEAP_CLASS_BYTES) (str "class of " blk " B"))
        (is (> (layout-bytes blk (* 2 last-ok)) d/MAX_HEAP_CLASS_BYTES) (str "class of " blk " B"))))))

#?(:bb nil
   :clj
   (deftest grow-heap-class-refuses-instead-of-overflowing
     (testing "a heap ctx whose class 0 holds 2^25 blocks: grow refuses, no ArithmeticException"
       (let [ctx    (alloc/make-jvm-heap-slab-ctx {:capacities {0 1024}})
             ^ints totals (.-total_blocks ^eve.deftype_proto.alloc.JvmSlabCtx ctx)
             before (aget totals 0)]
         (try
           (aset totals 0 (int 33554432))
           (is (not (#'alloc/grow-heap-class! ctx 0)))
           (finally (aset totals 0 (int before))))
         (is (true? (#'alloc/grow-heap-class! ctx 0)) "below the cap a heap class still grows")
         (is (= (* 2 before) (aget totals 0)))))))
