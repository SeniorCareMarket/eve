(ns alloc-growth-race.allocstorm
  "Allocator growth-race reproduction, direct shape (lane-m/alloc-growth-race).

   T threads run a tight eve.slab-io/alloc! loop over ONE persistent
   domain created with tiny class capacities (64 blocks per class), so
   every bitmap class doubles constantly while all threads scan. This
   maximizes (bitmap scan x remap publication) overlaps per second —
   the torn (bitmap-region, total-blocks) snapshot in ctx-alloc!/try-alloc
   throws IndexOutOfBoundsException in eve.mem/imr-bitmap-find-free
   via eve.region/bb-load-i32 (the DS11 E7 live-catch signature).

   Usage: clojure -M:test -m alloc-growth-race.allocstorm <dir> <threads> <max-allocs>"
  (:require [eve.atom :as atom]
            [eve.slab-io :as slab]))

(def ^:private sizes
  ;; weighted toward the small classes (bytes)
  [16 24 32 32 48 64 64 96 128 128 192 256 384 512 768 1024])

(defn -main [& [dir threads max-allocs]]
  (let [dir      (or dir "/home/john/j/alloc-growth-race-run/s1")
        threads  (parse-long (or threads "16"))
        max-a    (parse-long (or max-allocs "4000000"))
        domain   (atom/persistent-atom-domain
                   dir
                   :capacities {0 2048 1 4096 2 8192 3 16384 4 32768 5 65536}
                   :journal? false)
        a        (atom/lookup-or-create-mmap-atom! domain "storm/anchor" nil)
        sio      (atom/atom-sio a)
        per      (quot max-a threads)
        latch    (java.util.concurrent.CountDownLatch. 1)
        failure  (clojure.core/atom nil)
        done     (clojure.core/atom 0)
        workers  (mapv (fn [k]
                         (Thread.
                           (fn []
                             (.await latch)
                             (let [rnd (java.util.Random. (+ 1234 k))]
                               (loop [i 0]
                                 (if (and (< i per) (nil? @failure))
                                   (do (try
                                         (slab/alloc! sio (nth sizes (.nextInt rnd (count sizes))))
                                         (catch Throwable th
                                           (reset! failure th)))
                                       (recur (inc i)))
                                   (swap! done inc)))))))
                       (range threads))]
    (doseq [w workers] (.start w))
    (.countDown latch)
    (doseq [w workers] (.join w))
    (if-let [th @failure]
      (do (println "STORM-FAIL" (str th))
          (doseq [el (take 16 (.getStackTrace th))]
            (println "  AT" (str el)))
          (System/exit 1))
      (do (println "STORM-CLEAN" {:threads threads :per per})
          (System/exit 0)))))
