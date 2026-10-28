(ns lockprobe-main
  (:require [eve.region :as region]
            [eve.mem :as mem]
            [vs.mmap :as vmm]))

(let [[mode path secs] *command-line-args*
      r (region/open-mmap-region path 8192)]
  (case mode
    "hold"
    (do (mem/-store-i32! r 16 7)
        (mem/-with-file-lock r 16
          (fn []
            (println "VS-HOLDING")
            ;; hold on wall-clock time: the raw wait-i32 arm's timeout unit is
            ;; not seconds (measured: a 25 timeout returned in <1 s)
            (let [until (+ (System/currentTimeMillis) (* 1000 (parse-long (or secs "10"))))]
              (loop [] (when (< (System/currentTimeMillis) until) (recur))))
            (println "VS-RELEASING"))))
    "try"
    (let [got (vmm/flock! r 16 4)]
      (println "VS-TRY" got)
      (when got (vmm/funlock! r 16 4)))
    "sync"
    (do (mem/-store-i32! r 0 4242)
        (mem/sync-region! r)
        (mem/sync-region! r 0 4)
        (println "VS-SYNC-OK" (mem/-load-i32 r 0)))
    (println "unknown mode" mode)))
