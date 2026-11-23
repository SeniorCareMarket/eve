(ns eve.jvm-slab-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]))

(deftest heap-slab-alloc-free
  (testing "alloc and free a block in a heap slab"
    (let [ctx (alloc/make-jvm-heap-slab-ctx {})]
      (do
        (let [off (alloc/-sio-alloc! ctx 32)]
          (is (not= off alloc/NIL_OFFSET))
          (is (true? (alloc/-sio-free! ctx off))))))))

(deftest heap-slab-read-write
  (testing "write and read back bytes in a heap slab"
    (let [ctx (alloc/make-jvm-heap-slab-ctx {})]
      (do
        (let [off (alloc/-sio-alloc! ctx 64)]
          (alloc/-sio-write-i32! ctx off 0 42)
          (is (= 42 (alloc/-sio-read-i32 ctx off 0))))))))

(deftest heap-slab-double-free
  (testing "double-free returns false"
    (let [ctx (alloc/make-jvm-heap-slab-ctx {})]
      (do
        (let [off (alloc/-sio-alloc! ctx 32)]
          (is (true?  (alloc/-sio-free! ctx off)) "first free succeeds")
          (is (false? (alloc/-sio-free! ctx off)) "double-free returns false"))))))

(deftest heap-slab-multiple-allocs
  (testing "multiple allocs return distinct offsets"
    (let [ctx (alloc/make-jvm-heap-slab-ctx {})]
      (do
        (let [o1 (alloc/-sio-alloc! ctx 32)
              o2 (alloc/-sio-alloc! ctx 32)
              o3 (alloc/-sio-alloc! ctx 32)]
          (is (not= o1 o2))
          (is (not= o2 o3))
          (is (not= o1 o3))
          (alloc/-sio-free! ctx o1)
          (alloc/-sio-free! ctx o2)
          (alloc/-sio-free! ctx o3))))))

(deftest node-i32-matches-sio-read
  (testing "node-i32 equals -sio-read-i32 for aligned and unaligned field offsets, including a logging wrapper"
    (let [ctx (alloc/make-jvm-heap-slab-ctx {})
          off (alloc/-sio-alloc! ctx 64)
          wrap (alloc/logging-sio ctx)
          vs [0 1 -1 42 -42 127 -128 255 256 0x7FFFFFFF -0x80000000 0x12345678 -0x01020304]]
      (doseq [fo [0 1 2 3 7 13]
              v vs]
        (alloc/-sio-write-i32! ctx off fo v)
        (let [want (alloc/-sio-read-i32 ctx off fo)
              label (str "fo=" fo " v=" v)]
          (is (= want (alloc/node-i32 ctx off fo)) (str "ctx " label))
          (is (= want (alloc/node-i32 wrap off fo)) (str "wrap " label)))))))
