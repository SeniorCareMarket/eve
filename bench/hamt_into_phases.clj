;; JVM-only phase breakdown of the selected builder (100 string-vector keys).
;; Run from eve/: clojure -J-Xmx1g -M bench/hamt_into_phases.clj
(require '[eve.set :as s] '[eve.core :as e] '[eve.codec :as codec]
         '[eve.hamt-util :as hu] '[eve.hamt-batch :as batch]
         '[eve.deftype-proto.alloc :as alloc])
(defn measure [label f cleanup]
  (dotimes [_ 100] (cleanup (f)))
  (let [times (sort (for [_ (range 7)]
                      (let [out (object-array 1000) t (System/nanoTime)]
                        (dotimes [i 1000] (aset out i (f)))
                        (let [ms (/ (- (System/nanoTime) t) 1e9)]
                          (doseq [v out] (cleanup v)) ms))))]
    (println label (nth times 3) "ms/build" (vec times))))
(def xs (mapv #(vector (str "N" %)) (range 100)))
(def entries (mapv (fn [x] (let [b (codec/encode-key x)] [(hu/portable-hash-bytes b) b])) xs))
(def unique (batch/unique-entries entries))
(def build-set (ns-resolve 'eve.set 'build-batch-set))
(def sio (alloc/current-sio))
(let [nodes (atom 0)]
  (batch/build unique 0 (fn [_db _nb _data _children] (swap! nodes inc) 0) (fn [_] (swap! nodes inc) 0))
  (println "FINAL-NODES" @nodes))
(measure "encode+hash" #(mapv (fn [x] (let [b (codec/encode-key x)] [(hu/portable-hash-bytes b) b])) xs) identity)
(measure "dedup-only" #(batch/unique-entries entries) identity)
(measure "plan-only-preencoded-unique" #(batch/build unique 0 (fn [_db _nb _data _children] 0) (fn [_] 0)) identity)
(measure "build-scoped-preencoded" #(let [scope (alloc/scope-sio sio)
                                         result (build-set scope entries)]
                                     (alloc/stop-logging! scope) result) s/dispose!)
(measure "into-total" #(e/into (s/empty-hash-set) xs) s/dispose!)
