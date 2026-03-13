(ns eve.lustre-test
  "Unit tests for LustreMmapRegion (fcntl byte-range locking for cross-node atomics).
   Requires native addon at build/Release/mmap_cas.node (npm run build:addon).
   Tests run on local ext4 — fcntl works locally too, just faster than on Lustre."
  (:require [cljs.test :refer [deftest testing is]]
            [eve.mem :as mem]
            [eve.atom :as atom]))

(def ^:private fs (js/require "fs"))

(defn- tmp-path [label]
  (str "/tmp/eve-lustre-" label "-" (js/Date.now) ".mem"))

(defn- cleanup! [& paths]
  (doseq [p paths]
    (try (.unlinkSync fs p) (catch :default _))))

;; ---------------------------------------------------------------------------
;; LustreMmapRegion — IMemRegion unit tests
;; ---------------------------------------------------------------------------

(deftest test-lustre-open-creates-file
  (let [path (tmp-path "create")]
    (try
      (mem/open-lustre-region path 4096)
      (is (true? (.existsSync fs path)))
      (finally (cleanup! path)))))

(deftest test-lustre-byte-length
  (let [path (tmp-path "bl")]
    (try
      (is (= 4096 (mem/-byte-length (mem/open-lustre-region path 4096))))
      (finally (cleanup! path)))))

(deftest test-lustre-store-load-roundtrip
  (let [path (tmp-path "sl")]
    (try
      (let [r (mem/open-lustre-region path 4096)]
        (mem/-store-i32! r 0 42)
        (is (= 42 (mem/-load-i32 r 0)))
        (mem/-store-i32! r 0 -1)
        (is (= -1 (mem/-load-i32 r 0)))
        (mem/-store-i32! r 4 99)
        (is (= -1 (mem/-load-i32 r 0)))
        (is (= 99 (mem/-load-i32 r 4))))
      (finally (cleanup! path)))))

(deftest test-lustre-cas-success
  (let [path (tmp-path "cas-ok")]
    (try
      (let [r (mem/open-lustre-region path 4096)]
        (mem/-store-i32! r 0 10)
        (let [old (mem/-cas-i32! r 0 10 20)]
          (is (= 10 old) "CAS returns old value on success")
          (is (= 20 (mem/-load-i32 r 0)) "Value updated after successful CAS")))
      (finally (cleanup! path)))))

(deftest test-lustre-cas-failure
  (let [path (tmp-path "cas-fail")]
    (try
      (let [r (mem/open-lustre-region path 4096)]
        (mem/-store-i32! r 0 10)
        (let [old (mem/-cas-i32! r 0 99 20)]
          (is (= 10 old) "CAS returns old value on failure")
          (is (= 10 (mem/-load-i32 r 0)) "Value unchanged after failed CAS")))
      (finally (cleanup! path)))))

(deftest test-lustre-add-sub
  (let [path (tmp-path "addsub")]
    (try
      (let [r (mem/open-lustre-region path 4096)]
        (mem/-store-i32! r 0 100)
        (let [old-add (mem/-add-i32! r 0 25)]
          (is (= 100 old-add) "add returns old value")
          (is (= 125 (mem/-load-i32 r 0))))
        (let [old-sub (mem/-sub-i32! r 0 50)]
          (is (= 125 old-sub) "sub returns old value")
          (is (= 75 (mem/-load-i32 r 0)))))
      (finally (cleanup! path)))))

(deftest test-lustre-exchange
  (let [path (tmp-path "xchg")]
    (try
      (let [r (mem/open-lustre-region path 4096)]
        (mem/-store-i32! r 0 42)
        (let [old (mem/-exchange-i32! r 0 99)]
          (is (= 42 old) "exchange returns old value")
          (is (= 99 (mem/-load-i32 r 0)))))
      (finally (cleanup! path)))))

(deftest test-lustre-i64-ops
  (let [path (tmp-path "i64")]
    (try
      (let [r (mem/open-lustre-region path 4096)]
        ;; store/load
        (mem/-store-i64! r 0 1000000)
        (is (= 1000000 (mem/-load-i64 r 0)))
        ;; cas success
        (let [old (mem/-cas-i64! r 0 1000000 2000000)]
          (is (= 1000000 old))
          (is (= 2000000 (mem/-load-i64 r 0))))
        ;; cas failure
        (let [old (mem/-cas-i64! r 0 999 3000000)]
          (is (= 2000000 old))
          (is (= 2000000 (mem/-load-i64 r 0))))
        ;; add
        (let [old (mem/-add-i64! r 0 500)]
          (is (= 2000000 old))
          (is (= 2000500 (mem/-load-i64 r 0))))
        ;; sub
        (let [old (mem/-sub-i64! r 0 500)]
          (is (= 2000500 old))
          (is (= 2000000 (mem/-load-i64 r 0)))))
      (finally (cleanup! path)))))

(deftest test-lustre-byte-io
  (let [path (tmp-path "bytes")]
    (try
      (let [r (mem/open-lustre-region path 4096)
            src (js/Uint8Array. #js [1 2 3 4 5])]
        (mem/-write-bytes! r 100 src)
        (let [dst (mem/-read-bytes r 100 5)]
          (is (= 5 (.-length dst)))
          (is (= 1 (aget dst 0)))
          (is (= 5 (aget dst 4)))))
      (finally (cleanup! path)))))

(deftest test-lustre-bitmap-alloc
  (testing "imr-bitmap-alloc-cas! and imr-bitmap-free! work through LustreMmapRegion"
    (let [path (tmp-path "bitmap")]
      (try
        (let [r (mem/open-lustre-region path 4096)
              bm-off 64]  ; bitmap starts at byte 64
          ;; Clear bitmap word
          (mem/-store-i32! r bm-off 0)
          ;; Allocate bit 0
          (is (true? (mem/imr-bitmap-alloc-cas! r bm-off 0)))
          ;; Bit 0 already set — should fail
          (is (false? (mem/imr-bitmap-alloc-cas! r bm-off 0)))
          ;; Allocate bit 1
          (is (true? (mem/imr-bitmap-alloc-cas! r bm-off 1)))
          ;; Free bit 0
          (is (true? (mem/imr-bitmap-free! r bm-off 0)))
          ;; Bit 0 free again — can allocate
          (is (true? (mem/imr-bitmap-alloc-cas! r bm-off 0))))
        (finally (cleanup! path))))))

;; ---------------------------------------------------------------------------
;; Lustre mode integration — persistent atom with :lustre? true
;; ---------------------------------------------------------------------------

(deftest test-lustre-persistent-atom
  (testing "persistent atom with :lustre? true creates and operates correctly"
    (let [base-path (str "/tmp/eve-lustre-domain-" (js/Date.now))]
      (try
        (let [domain (atom/persistent-atom-domain base-path :lustre? true)
              a      (atom/lookup-or-create-mmap-atom! domain "test/counter" {:count 0})]
          ;; deref
          (is (= {:count 0} @a))
          ;; swap!
          (swap! a update :count inc)
          (is (= {:count 1} @a))
          ;; another swap
          (swap! a assoc :name "lustre")
          (is (= {:count 1 :name "lustre"} @a)))
        (finally
          ;; Cleanup domain files
          (doseq [ext [".root" ".rmap"
                       ".slab0" ".slab0.bm"
                       ".slab1" ".slab1.bm"
                       ".slab2" ".slab2.bm"
                       ".slab3" ".slab3.bm"
                       ".slab4" ".slab4.bm"
                       ".slab5" ".slab5.bm"
                       ".slab6"]]
            (cleanup! (str base-path ext))))))))
