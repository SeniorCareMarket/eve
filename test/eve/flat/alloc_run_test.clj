(ns eve.flat.alloc-run-test
  "Z2 — the allocator run claim (IMPL-PLAN §3.3, lane Z2).
   The contract is Wave 0's (alloc/alloc-run!, W0.4): [sio class-idx n]
   answers a vector of the n blocks' slab-qualified offsets in ascending
   order, or nil when a run-claiming slab I/O has no free run of n
   (nothing claimed). JvmSlabCtx claims by masked word CAS
   (ctx-alloc-run!); a logging sio delegates and logs every block.
   These tests are the lane's requirements, written before the code:
   Z2.1 run-is-consecutive-and-allocated · Z2.2 run-wastes-nothing ·
   Z2.3 no-run-claims-nothing · Z2.4 run-claim-contention ·
   Z2.5 run-claim-across-growth · Z2.6 run-claim-encrypted."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.keyring :as kr]
            [eve.mem :as mem]
            [eve.slab-io :as slab])
  (:import [eve.deftype_proto.alloc JvmSlabCtx]
           [java.util.concurrent CountDownLatch]))

;; ---------------------------------------------------------------------------
;; Helpers: a heap domain's class geometry, read from its own header words
;; (the same words the allocator itself keeps).
;; ---------------------------------------------------------------------------

(defn- ctx-of [sio] (slab/domain-ctx sio))

(defn- class-region [^JvmSlabCtx ctx ci] (aget ^objects (.regions ctx) ci))

(defn- total-blocks [sio ci]
  (mem/-load-i32 (class-region (ctx-of sio) ci) d/SLAB_HDR_TOTAL_BLOCKS))

(defn- free-count [sio ci]
  (mem/-load-i32 (class-region (ctx-of sio) ci) d/SLAB_HDR_FREE_COUNT))

(defn- heap-sio
  "A fresh heap domain with small classes (capacities are region bytes)."
  [caps]
  (alloc/heap-domain-sio! {:capacities caps}))

(defn- off [ci bi] (alloc/encode-slab-offset ci bi))

(defn- allocated-range
  "The set of allocated block indices of class ci, by the allocator's own
   allocated? over every block of the class."
  [sio ci]
  (into #{} (filter #(alloc/allocated? sio (off ci %))) (range (total-blocks sio ci))))

(defn- fill-class!
  "Allocate single blocks of class ci until the class is full; the vector of
   their slab offsets, in the order handed out."
  [sio ci]
  (let [size (d/slab-size ci)]
    (loop [out []]
      (if (zero? (free-count sio ci))
        out
        (recur (conj out (slab/alloc! sio size)))))))

;; ---------------------------------------------------------------------------
;; Z2.1 — a run is n consecutive blocks, all marked allocated
;; ---------------------------------------------------------------------------

(deftest run-is-consecutive-and-allocated
  (let [sio   (heap-sio {0 8192 1 8192 2 65536 3 8192 4 8192 5 8192})
        ci    2
        n     50
        fc0   (free-count sio ci)
        offs  (alloc/alloc-run! sio ci n)]
    (is (some? offs) "a fresh class answers a run")
    (is (= n (count offs)) "the answer is the run's n offsets")
    (let [s (alloc/decode-block-idx (first offs))]
      (is (= (mapv #(off ci %) (range s (+ s n))) offs)
          "the offsets are the consecutive blocks of the class, ascending")
      (is (every? #(alloc/allocated? sio %) offs)
          "every block of the run is marked allocated")
      (is (= n (- fc0 (free-count sio ci))) "the free count fell by exactly n")
      (testing "single allocation never hands out a block of the run"
        (let [singles (repeatedly 50 #(slab/alloc! sio (d/slab-size ci)))]
          (is (empty? (filter #(<= s (alloc/decode-block-idx %) (dec (+ s n)))
                              singles))))))))

;; ---------------------------------------------------------------------------
;; Z2.2 — a run of any n at any cursor wastes no block: whole words by one
;; CAS, partial words masked at both ends; bits outside the run untouched
;; and the free count exact.
;; ---------------------------------------------------------------------------

(deftest run-wastes-nothing
  (let [sio (heap-sio {0 16384 1 8192 2 8192 3 8192 4 8192 5 8192})
        ci  0
        _   (is (> (total-blocks sio ci) 128) "the class holds the pattern")
        offs (fill-class! sio ci)
        by-idx (into {} (map (fn [o] [(alloc/decode-block-idx o) o]) offs))
        ;; the pattern: one run of 40 at the unaligned start 37 (words 1–2,
        ;; head and tail both partial), plus isolated free singles at 5, 90,
        ;; 100 whose neighbours stay allocated
        run-start 37
        n         40
        freed     (into (set (range run-start (+ run-start n))) [5 90 100])]
    (doseq [bi freed] (slab/free! sio (by-idx bi)))
    (let [fc0    (free-count sio ci)
          before (allocated-range sio ci)
          offs   (alloc/alloc-run! sio ci n)]
      (is (some? offs) "the one run of n is found")
      (is (= run-start (alloc/decode-block-idx (first offs)))
          "the claim starts exactly at the run, not at a word boundary")
      (is (= n (- fc0 (free-count sio ci))) "the free count fell by exactly n")
      (is (= (into before (range run-start (+ run-start n)))
             (allocated-range sio ci))
          "no bit outside the run changed: the isolated singles stay free, every other block stays as it was"))))

;; ---------------------------------------------------------------------------
;; Z2.3 — no free run: answers none and claims nothing
;; ---------------------------------------------------------------------------

(deftest no-run-claims-nothing
  (let [sio (heap-sio {0 16384 1 8192 2 8192 3 8192 4 8192 5 8192})
        ci  0
        _   (is (> (total-blocks sio ci) 128) "the class holds the pattern")
        offs (fill-class! sio ci)
        by-idx (into {} (map (fn [o] [(alloc/decode-block-idx o) o]) offs))]
    ;; free every other block of the first 100: 50 free blocks, no two adjacent
    (doseq [bi (range 0 100 2)] (slab/free! sio (by-idx bi)))
    (let [fc0    (free-count sio ci)
          before (allocated-range sio ci)]
      (is (>= fc0 4) "free blocks exist — the class is fragmented, not full")
      (is (nil? (alloc/alloc-run! sio ci 4)) "no run of 4: the answer is none")
      (is (= fc0 (free-count sio ci)) "and nothing was claimed doing it")
      (is (= before (allocated-range sio ci)) "the allocated set is untouched"))))

;; ---------------------------------------------------------------------------
;; Z2.4 — N threads claiming runs and single blocks in one domain: no block
;; handed out twice, free counts exact
;; ---------------------------------------------------------------------------

(deftest run-claim-contention
  (let [sio     (heap-sio {0 8192 1 8192 2 8192 3 1048576 4 8192 5 8192})
        ci      3
        size    (d/slab-size ci)
        total   (total-blocks sio ci)
        threads 8
        per     25
        gate    (CountDownLatch. 1)
        handed  (clojure.core/atom [])
        workers (mapv (fn [_]
                        (future
                          (.await gate)
                          (dotimes [i per]
                            (if (even? i)
                              (when-let [offs (alloc/alloc-run! sio ci (inc (mod i 8)))]
                                (swap! handed into offs))
                              (swap! handed conj (slab/alloc! sio size))))))
                      (range threads))]
    (.countDown gate)
    (doseq [w workers] @w)
    (let [hs @handed]
      (is (= (count hs) (count (distinct hs))) "no block was handed out twice")
      (is (= (- total (free-count sio ci)) (count hs))
          "the free count is exact: total − free = blocks handed out")
      (is (every? #(alloc/allocated? sio %) hs) "every handed block is allocated"))))

;; ---------------------------------------------------------------------------
;; Z2.5 — correct across growth of a heap class
;; ---------------------------------------------------------------------------

(deftest run-claim-across-growth
  (let [sio (heap-sio {0 8192 1 4096 2 8192 3 8192 4 8192 5 8192})
        ci  1
        t0  (total-blocks sio ci)
        n   (* 4 t0)
        offs (alloc/alloc-run! sio ci n)]
    (is (> (total-blocks sio ci) t0) "the class grew to hold the run")
    (is (some? offs) "a run larger than the whole class is claimed after growth")
    (is (= n (count offs)) "the answer is the grown run's n offsets")
    (let [s (alloc/decode-block-idx (first offs))]
      (is (= (mapv #(off ci %) (range s (+ s n))) offs)
          "the grown run is consecutive")
      (is (every? #(alloc/allocated? sio %) offs)
          "every block of the grown run is marked allocated")
      (is (= n (- (total-blocks sio ci) (free-count sio ci)))
          "the free count is exact across the growth"))
    (testing "the class still claims and allocates singly after growing"
      (is (some? (alloc/alloc-run! sio ci 8)))
      (is (some? (slab/alloc! sio (d/slab-size ci)))))))

;; ---------------------------------------------------------------------------
;; Z2.6 — works in an encrypted domain. eve.keyring's EncryptedSlabIO is
;; not this lane's file and implements no run claim, so alloc-run! serves
;; it with the per-block default (W0.4) — the claim answers, the blocks
;; are the domain's, and writes through the encrypting sio land under
;; its keystream. The fast claim through a wrapper awaits a one-method
;; ISlabRunAlloc delegation in eve.keyring (noted in Z2-REPORT).
;; ---------------------------------------------------------------------------

(deftest run-claim-encrypted
  (let [inner (heap-sio {0 8192 1 8192 2 65536 3 8192 4 8192 5 8192})
        gak   (byte-array (map unchecked-byte (range 32)))
        salt  (byte-array (map unchecked-byte (range 16)))
        sio   (kr/encrypted-sio inner gak salt)
        ci    2
        n     24
        offs  (alloc/alloc-run! sio ci n)]
    (is (some? offs) "a claim answers through the encrypting sio")
    (is (= n (count offs)) "the answer is n blocks")
    (is (every? #(alloc/allocated? sio %) offs)
        "the claimed blocks are allocated in the domain")
    (testing "a claimed block writes and reads through the keystream"
      (let [first (first offs)]
        (alloc/-sio-write-i32! sio first 0 123456789)
        (is (= 123456789 (alloc/-sio-read-i32 sio first 0)))
        (is (not= 123456789 (alloc/-sio-read-i32 inner first 0))
            "the bytes at rest are not the plaintext")))))
