(ns alloc-growth-race.storm2
  "Storm v2: direct alloc! loop + a witness thread that cross-checks
   imr-bitmap-find-free against the class-0 header free-count, and a
   full failure census (every throwable, counted by type)."
  (:require [eve.atom :as atom]
            [eve.slab-io :as slab]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem]))

(def ^:private sizes [16 24 32 32 48 64 64 96 128 128 192 256 384 512 768 1024])

(defn -main [& [dir threads max-allocs]]
  (let [dir     (or dir "/home/john/j/alloc-growth-race-run/s2")
        threads (parse-long (or threads "12"))
        max-a   (parse-long (or max-allocs "1500000"))
        domain  (atom/persistent-atom-domain
                  dir
                  :capacities {0 2048 1 4096 2 8192 3 16384 4 32768 5 65536}
                  :journal? false)
        a       (atom/lookup-or-create-mmap-atom! domain "storm/anchor" nil)
        sio     (atom/atom-sio a)
        ctx     (slab/domain-ctx sio)
        regions (.-regions ctx)
        bm-rgns (.-bitmap-regions ctx)
        bm-offs (.-bitmap-offsets ctx)
        totals  (.-total-blocks ctx)
        per     (quot max-a threads)
        latch   (java.util.concurrent.CountDownLatch. 1)
        failures (clojure.core/atom [])
        running (clojure.core/atom true)
        witness-events (clojure.core/atom [])
        witness (Thread.
                  (fn []
                    (while @running
                      (try
                        (let [rgn    (aget ^objects regions 0)
                              bm     (aget ^objects bm-rgns 0)
                              bm-off (aget ^ints bm-offs 0)
                              total  (aget ^ints totals 0)
                              htotal (mem/-load-i32 rgn d/SLAB_HDR_TOTAL_BLOCKS)
                              hfree  (mem/-load-i32 rgn d/SLAB_HDR_FREE_COUNT)
                              cursor (mem/-load-i32 rgn d/SLAB_HDR_ALLOC_CURSOR)
                              found  (mem/imr-bitmap-find-free bm bm-off total (mod cursor (max 1 total)))]
                          (when (and (= found -1) (> hfree 100))
                            (swap! witness-events conj
                                   {:ctx-total total :hdr-total htotal :hdr-free hfree
                                    :cursor cursor :bm-len (mem/-byte-length bm)
                                    :w0 (mem/-load-i32 bm bm-off)
                                    :wc (mem/-load-i32 bm (+ bm-off (* 4 (quot (mod cursor (max 1 total)) 32))))})))
                        (catch Throwable _))
                      (Thread/sleep 25))))
        workers (mapv (fn [k]
                        (Thread.
                          (fn []
                            (.await latch)
                            (let [rnd (java.util.Random. (+ 99 k))]
                              (loop [i 0]
                                (when (< i per)
                                  (try
                                    (slab/alloc! sio (nth sizes (.nextInt rnd (count sizes))))
                                    (catch Throwable th (swap! failures conj th)))
                                  (recur (inc i))))))))
                      (range threads))]
    (.start witness)
    (doseq [w workers] (.start w))
    (.countDown latch)
    (doseq [w workers] (.join w))
    (reset! running false)
    (.join witness 2000)
    (println "FAILURE-CENSUS" (pr-str (frequencies (map #(str (type %) ": " (.getMessage %)) @failures))))
    (when-let [th (first @failures)]
      (doseq [el (take 10 (.getStackTrace th))] (println "  AT" (str el))))
    (println "WITNESS-BLIND-EVENTS" (count @witness-events))
    (doseq [e (take 6 @witness-events)] (println "  EV" (pr-str e)))
    (println "FINAL-CLASS0" {:ctx-total (aget ^ints totals 0)
                             :hdr-total (mem/-load-i32 (aget ^objects regions 0) d/SLAB_HDR_TOTAL_BLOCKS)
                             :hdr-free (mem/-load-i32 (aget ^objects regions 0) d/SLAB_HDR_FREE_COUNT)})
    (System/exit (if (empty? @failures) 0 1))))
