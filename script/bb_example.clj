#!/usr/bin/env bb
;; Eve + Babashka example: mmap regions and EVE serialization
;;
;; Run:  bb example:bb
;;   or: bb script/bb_example.clj

(require '[eve.mem :as mem])
(require '[eve.hamt-util :as hu])
(require '[eve.deftype-proto.alloc :as alloc])
(require '[eve.deftype-proto.data :as d])

(println "=== Eve + Babashka Example ===")
(println)

;; ---------------------------------------------------------------
;; 1. Memory-mapped region — file-backed shared memory
;; ---------------------------------------------------------------
(println "1. MappedByteBuffer mmap region")
(let [path "/tmp/bb-eve-example.bin"
      r    (mem/open-mmap-region path 4096)]
  ;; Write a 32-bit integer atomically
  (mem/store-i32! r 0 12345)
  (println "   Wrote i32 12345 at offset 0, read back:" (mem/load-i32 r 0))

  ;; Compare-and-swap
  (let [old (mem/cas-i32! r 0 12345 99999)]
    (println "   CAS 12345→99999 succeeded, witness:" old
             "new value:" (mem/load-i32 r 0)))

  ;; 64-bit operations
  (mem/store-i64! r 8 Long/MAX_VALUE)
  (println "   i64 MAX_VALUE stored and read back:" (mem/load-i64 r 8))

  ;; Bulk byte I/O
  (mem/write-bytes! r 64 (byte-array (map byte (range 10))))
  (println "   Bulk bytes [0..9]:" (vec (mem/read-bytes r 64 10)))
  (println "   File:" path))

(println)

;; ---------------------------------------------------------------
;; 2. Heap region — in-process, non-persistent
;; ---------------------------------------------------------------
(println "2. Heap region (non-persistent, in-process)")
(let [r (mem/make-heap-region 1024)]
  (mem/store-i32! r 0 42)
  (mem/add-i32! r 0 8)
  (println "   42 + 8 =" (mem/load-i32 r 0)))

(println)

;; ---------------------------------------------------------------
;; 3. EVE binary serialization (identical format to JVM + Node.js)
;; ---------------------------------------------------------------
(println "3. EVE binary serialization")
(doseq [v [nil true false 42 -1 3.14 "hello" :foo :ns/bar
           'my-sym {:a 1 :b "two"} [1 2 3] #{:x :y}]]
  (let [b (mem/value->eve-bytes v)
        v' (mem/eve-bytes->value b)]
    (printf "   %-20s → %d bytes → %s%n" (pr-str v) (count b) (pr-str v'))))

(println)

;; ---------------------------------------------------------------
;; 4. Portable Murmur3 hash — identical on bb, JVM, and CLJS
;; ---------------------------------------------------------------
(println "4. Portable Murmur3 hash")
(doseq [k [:hello :world :foo/bar]]
  (let [kb (mem/value->eve-bytes k)
        h  (hu/portable-hash-bytes kb)]
    (printf "   %-12s hash = %d%n" k h)))

(println)

;; ---------------------------------------------------------------
;; 5. Slab offset encoding (shared addressing format)
;; ---------------------------------------------------------------
(println "5. Slab-qualified offset encoding")
(doseq [[cls blk] [[0 0] [2 42] [5 1000]]]
  (let [off (alloc/encode-slab-offset cls blk)]
    (printf "   class=%d block=%-4d → offset=%d (decode: class=%d block=%d)%n"
            cls blk off (alloc/decode-class-idx off) (alloc/decode-block-idx off))))

(println)
(println "=== Done ===")
