(ns alloc-growth-race.probe1
  "Sequential growth probe: single thread allocs class-0 blocks one at a
   time and prints header geometry every 16 allocs — watching for a
   spurious doubling (total grows while free blocks remain)."
  (:require [eve.atom :as atom]
            [eve.slab-io :as slab]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem]
            [eve.region :as region]))

(defn -main [& [dir n]]
  (let [dir (or dir "/home/john/j/alloc-growth-race-run/p1")
        n   (parse-long (or n "600"))
        domain (atom/persistent-atom-domain dir :capacities {0 2048} :journal? false)
        a   (atom/lookup-or-create-mmap-atom! domain "probe/anchor" nil)
        sio (atom/atom-sio a)
        ctx (slab/domain-ctx sio)
        rgn (aget ^objects (.-regions ctx) 0)]
    (println "FIELDS" (pr-str (keys (bean ctx))))
    (loop [i 0]
      (if (>= i n)
        (println "PROBE-DONE" i)
        (do
          (when (zero? (mod i 16))
            (println "AT" i
                     "total" (mem/-load-i32 rgn d/SLAB_HDR_TOTAL_BLOCKS)
                     "free" (mem/-load-i32 rgn d/SLAB_HDR_FREE_COUNT)
                     "cursor" (mem/-load-i32 rgn d/SLAB_HDR_ALLOC_CURSOR)
                     "ctx-total" (aget ^ints (.-total-blocks ctx) 0)))
          (slab/alloc! sio 32)
          (recur (inc i)))))))
