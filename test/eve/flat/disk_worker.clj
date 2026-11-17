(ns eve.flat.disk-worker
  "H1.1's second process (lane H1, IMPL-PLAN §3.4): a child JVM that joins
   the mmap domain at BASE (created by the parent test), waits for the GO
   file, then claims runs and single blocks from the domain's slab I/O on
   a fixed per-seed script, and writes every slab offset it was handed to
   OUT as EDN. The parent compares the two processes' sets: no block
   twice, and the domain's free counts exact against the claimed total."
  (:require [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d])
  (:import [java.io File]
           [java.util Random]))

(defn- wait-for-go [^String path]
  (let [f (File. path)]
    (loop [i 0]
      (when-not (.exists f)
        (when (> i 600) (throw (ex-info "disk-worker: go file never appeared" {:path path})))
        (Thread/sleep 50)
        (recur (inc i))))))

(def ^:private run-script
  "Run lengths claimed per round, per class in rotation. No frees during
   the race, so a class fills monotonically and runs stay findable until
   the class nears its total."
  [37 5 64 1 13 128 3 21 8 55 2 34])

(defn -main [& [base out go seed]]
  (let [sio (alloc/mmap-domain-sio! base {:create? false})
        rnd (Random. (Long/parseLong seed))
        claimed (java.util.ArrayList.)]
    (wait-for-go go)
    ;; 10 rounds: in each, one run per script entry (class rotating
    ;; 0,1,2,0,1,2...) and one single block of a rotating class.
    (dotimes [round 10]
      (doseq [[i n] (map-indexed vector run-script)]
        (let [ci (mod (+ i round) 3)
              offs (alloc/alloc-run! sio ci n)]
          (when-not offs
            (throw (ex-info "disk-worker: run claim answered nil inside capacity"
                            {:class ci :n n :round round})))
          (.addAll claimed offs)))
      (let [ci (mod round 3)]
        (.add claimed (alloc/-sio-alloc! sio (nth alloc/CLASS_BLOCK_SIZES ci))))
      ;; a breath of jitter so the two processes interleave unevenly
      (Thread/sleep (.nextInt rnd 3)))
    (spit out (pr-str (vec claimed)))
    (System/exit 0)))
