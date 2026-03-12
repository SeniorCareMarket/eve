#!/usr/bin/env bb
;; Eve Babashka compatibility smoke tests
;;
;; Run:  bb test:bb
;;   or: bb script/bb_test.clj

(require '[clojure.test :refer [deftest is testing run-tests]])

;; ---------------------------------------------------------------
;; Load all bb-compatible Eve namespaces
;; ---------------------------------------------------------------
(require '[eve.mem :as mem])
(require '[eve.hamt-util :as hu])
(require '[eve.deftype-proto.alloc :as alloc])
(require '[eve.deftype-proto.data :as d])
(require '[eve.deftype-proto.serialize :as ser])
(require '[eve.deftype-proto.coalesc :as coalesc])
(require '[eve.perf :as perf])

;; ---------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------

(deftest namespace-loading-test
  (testing "All core namespaces load in Babashka"
    (is (some? (resolve 'eve.mem/open-mmap-region)))
    (is (some? (resolve 'eve.mem/value->eve-bytes)))
    (is (some? (resolve 'eve.hamt-util/portable-hash-bytes)))
    (is (some? (resolve 'eve.deftype-proto.alloc/encode-slab-offset)))
    (is (some? (resolve 'eve.deftype-proto.data/SLAB_SIZES)))
    (is (some? (resolve 'eve.perf/timed)))))

(deftest mmap-region-test
  (testing "MappedByteBuffer mmap region I/O"
    (let [r (mem/open-mmap-region "/tmp/bb-eve-test-mmap.bin" 4096)]
      ;; i32
      (mem/store-i32! r 0 42)
      (is (= 42 (mem/load-i32 r 0)))

      ;; CAS success
      (is (= 42 (mem/cas-i32! r 0 42 99)))
      (is (= 99 (mem/load-i32 r 0)))

      ;; CAS failure
      (is (= 99 (mem/cas-i32! r 0 42 200)))
      (is (= 99 (mem/load-i32 r 0)))

      ;; add
      (mem/store-i32! r 4 100)
      (is (= 100 (mem/add-i32! r 4 50)))
      (is (= 150 (mem/load-i32 r 4)))

      ;; sub
      (is (= 150 (mem/sub-i32! r 4 30)))
      (is (= 120 (mem/load-i32 r 4)))

      ;; exchange
      (is (= 120 (mem/exchange-i32! r 4 0)))
      (is (= 0   (mem/load-i32 r 4)))

      ;; i64
      (mem/store-i64! r 16 9999999999)
      (is (= 9999999999 (mem/load-i64 r 16)))

      ;; bulk bytes
      (mem/write-bytes! r 100 (byte-array [1 2 3 4 5]))
      (is (= [1 2 3 4 5] (vec (mem/read-bytes r 100 5)))))))

(deftest heap-region-test
  (testing "Heap region I/O"
    (let [r (mem/make-heap-region 1024)]
      (mem/store-i32! r 0 777)
      (is (= 777 (mem/load-i32 r 0)))
      (mem/store-i64! r 8 Long/MAX_VALUE)
      (is (= Long/MAX_VALUE (mem/load-i64 r 8))))))

(deftest serialization-roundtrip-test
  (testing "EVE serialization roundtrip for primitives"
    (doseq [v [nil true false 42 -1 0 127 -128 999999
               3.14 "hello" "" :foo :ns/bar 'my-sym]]
      (let [b  (mem/value->eve-bytes v)
            v' (mem/eve-bytes->value b)]
        (is (= v v') (str "roundtrip failed for " (pr-str v)))))))

(deftest serialization-collections-test
  (testing "EVE flat collection serialization"
    (let [m {:a 1 :b "two" :c true}
          b (mem/value->eve-bytes m)
          m' (mem/eve-bytes->value b)]
      (is (= m m')))

    (let [v [1 2 3 "four"]
          b (mem/value->eve-bytes v)
          v' (mem/eve-bytes->value b)]
      (is (= v v')))

    (let [s #{:x :y :z}
          b (mem/value->eve-bytes s)
          s' (mem/eve-bytes->value b)]
      (is (= s s')))))

(deftest portable-hash-test
  (testing "Murmur3 hash produces consistent results"
    (let [kb1 (mem/value->eve-bytes :hello)
          kb2 (mem/value->eve-bytes :hello)
          h1  (hu/portable-hash-bytes kb1)
          h2  (hu/portable-hash-bytes kb2)]
      (is (= h1 h2) "same key → same hash"))

    (let [kb1 (mem/value->eve-bytes :hello)
          kb2 (mem/value->eve-bytes :world)
          h1  (hu/portable-hash-bytes kb1)
          h2  (hu/portable-hash-bytes kb2)]
      (is (not= h1 h2) "different keys → different hashes"))))

(deftest slab-offset-encoding-test
  (testing "Slab-qualified offset encode/decode roundtrip"
    (doseq [[cls blk] [[0 0] [0 1] [2 42] [5 1000] [6 536870911]]]
      (let [off (alloc/encode-slab-offset cls blk)]
        (is (= cls (alloc/decode-class-idx off))
            (str "class mismatch for " cls "," blk))
        (is (= blk (alloc/decode-block-idx off))
            (str "block mismatch for " cls "," blk))))))

(deftest perf-noop-test
  (testing "Perf stubs are no-ops in Babashka"
    (is (false? (perf/enabled?)))
    (is (= 42 (perf/timed :test 42)))
    (is (nil? (perf/count! :test)))))

;; ---------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
