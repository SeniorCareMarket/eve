(ns eve.alloc-growth-race-test
  "Regression for the allocator growth race (lane-m/alloc-growth-race).

   Two defects, one family — allocator state published without
   revalidation while other threads read it lock-free:

   D2 (crash): try-alloc read its (bitmap-region, total) snapshot
   region-first, total-third, unchecked; a remap publishing between
   the reads paired the OLD bitmap with the NEW total and the scan
   loaded past the buffer — IndexOutOfBoundsException in
   eve.mem/imr-bitmap-find-free (DS11 E7 live catch: Index 276 out of
   bounds for length 253). The scan now clamps total-bits to what the
   region in hand actually holds, and try-alloc certifies its
   snapshot by reading total-blocks on both sides of the region read.

   D1 (runaway growth): a thread whose try-alloc returned nil against
   a stale, pre-growth snapshot entered grow! after a peer had
   already grown the class; grow! compared header vs cached totals,
   found them equal, and CAS-doubled AGAIN — once per waiting
   thread — running classes away geometrically (headers at 2^30 with
   ~200k real allocations; grow! then crashed on the (int new-total)
   overflow). grow! now revalidates the header free-count at current
   geometry before it may lead a doubling."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as atom]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem]
            [eve.region :as region]
            [eve.slab-io :as slab]))

(deftest find-free-clamps-to-the-region-in-hand
  (testing "a torn (region, total) pair reports full instead of throwing"
    (let [r (region/make-heap-region 64)] ;; 16 words = 512 bits
      (dotimes [i 16] (mem/-store-i32! r (* i 4) -1))
      (is (= -1 (mem/imr-bitmap-find-free r 0 512 0)) "full at the true geometry")
      (is (= -1 (mem/imr-bitmap-find-free r 0 4096 0)) "torn total: doubled geometry")
      (is (= -1 (mem/imr-bitmap-find-free r 0 4096 480)) "torn total, late cursor")
      (is (= -1 (mem/imr-bitmap-find-free-bulk r 0 4096 0)) "bulk path clamps too")
      (mem/-store-i32! r (* 3 4) 0)
      (is (= 96 (mem/imr-bitmap-find-free r 0 4096 0)) "a clear bit inside the region is still found"))))

(deftest concurrent-growth-stays-bounded
  (testing "an alloc storm over tiny classes: no failures, no runaway geometry"
    (let [dir    (.getPath (java.io.File. (System/getProperty "java.io.tmpdir")
                                          (str "alloc-growth-race-test-" (System/nanoTime))))
          domain (atom/persistent-atom-domain
                   dir
                   :capacities {0 2048 1 4096 2 8192 3 16384 4 32768 5 65536}
                   :journal? false)
          a      (atom/lookup-or-create-mmap-atom! domain "storm/anchor" nil)
          sio    (atom/atom-sio a)
          ctx    (slab/domain-ctx sio)
          sizes  [16 24 32 32 48 64 64 96 128 128 192 256 384 512 768 1024]
          block-sizes [32 64 128 256 512 1024]
          class-of (fn [s] (first (filter #(<= s (nth block-sizes %)) (range 6))))
          threads 8
          per     20000
          failures (clojure.core/atom [])
          counts   (clojure.core/atom (vec (repeat 6 0)))
          workers  (mapv (fn [k]
                           (Thread.
                             (fn []
                               (let [rnd (java.util.Random. (+ 7 k))]
                                 (dotimes [_ per]
                                   (let [s (nth sizes (.nextInt rnd (count sizes)))]
                                     (try
                                       (slab/alloc! sio s)
                                       (swap! counts update (class-of s) inc)
                                       (catch Throwable th (swap! failures conj th)))))))))
                         (range threads))]
      (doseq [w workers] (.start w))
      (doseq [w workers] (.join w))
      (is (empty? @failures)
          (str "storm failures: " (pr-str (map str (take 3 @failures)))))
      (doseq [ci (range 6)]
        (let [need  (nth @counts ci)
              total (mem/-load-i32 (aget ^objects (.-regions ctx) ci) d/SLAB_HDR_TOTAL_BLOCKS)
              bound (* 4 (loop [p 64] (if (>= p (max 64 need)) p (recur (* 2 p)))))]
          (is (<= total bound)
              (str "class " ci ": total " total " vs bound " bound
                   " (allocated " need ")")))))))
