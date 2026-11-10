(ns alloc-growth-race.repro
  "Allocator growth-race reproduction driver (lane-m/alloc-growth-race).

   T threads (or, launched as P processes, P x T) hammer ONE persistent
   domain with allocation-burst swaps on per-thread atoms, over a domain
   created with tiny class capacities so the bitmap classes double
   constantly. The DS11 live catch was: IndexOutOfBoundsException in
   eve.mem/imr-bitmap-find-free via eve.region/bb-load-i32, when one
   allocator scanned a bitmap region with a total published by a
   concurrent remap (grow!/refresh!) — a torn (bitmap-region, total)
   pair in ctx-alloc!/try-alloc.

   Usage (JVM): clojure -M:test -m alloc-growth-race.repro <dir> <threads> <swaps> [burst]
   Any throwable is printed with its top frames; exit 1 on failure."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.atom :as atom]))

(defn run-storm! [dir threads swaps burst]
  (let [domain (atom/persistent-atom-domain
                 dir
                 :capacities {0 8192 1 16384 2 32768 3 65536 4 131072 5 262144}
                 :journal? false)
        atoms  (mapv (fn [k]
                       (let [a (atom/lookup-or-create-mmap-atom! domain (str "repro/vec-" k) nil)]
                         (when (nil? @a) (swap! a (fn [_] [])))
                         a))
                     (range threads))
        latch  (java.util.concurrent.CountDownLatch. 1)
        failure (clojure.core/atom nil)
        workers (mapv (fn [k]
                        (Thread.
                          (fn []
                            (.await latch)
                            (loop [i 0]
                              (when (and (< i swaps) (nil? @failure))
                                (try
                                  (swap! (nth atoms k)
                                         (fn [v]
                                           (into v (map (fn [j] {:p k :i i :j j
                                                                 :s (str "value-" k "-" i "-" j)}))
                                                 (range burst))))
                                  (catch Throwable th
                                    (reset! failure th)))
                                (recur (inc i)))))))
                      (range threads))]
    (doseq [w workers] (.start w))
    (.countDown latch)
    (doseq [w workers] (.join w))
    @failure))

(defn -main [& [dir threads swaps burst]]
  (let [th (run-storm! (or dir "/home/john/j/alloc-growth-race-run/d1")
                 (parse-long (or threads "8"))
                 (parse-long (or swaps "2000"))
                 (parse-long (or burst "8")))]
    (if th
      (do (println "REPRO-FAIL" (str th))
          (doseq [el (take 16 (.getStackTrace th))]
            (println "  AT" (str el)))
          (System/exit 1))
      (do (println "REPRO-CLEAN")
          (System/exit 0)))))
