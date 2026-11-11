;; Run from eve/: clojure -J-Xmx1g -M bench/hamt_into.clj
;; Median of seven rounds; disposal is deliberately OUTSIDE the timer.
(require '[eve.core :as e] '[eve.set :as s] '[eve.map :as m]
         '[eve.codec :as codec] '[eve.hamt-util :as hu])
(defn bench [label n f cleanup]
  (dotimes [_ 50] (cleanup (f)))
  (let [times (sort (for [_ (range 7)]
                      (let [out (object-array n)
                            t (System/nanoTime)]
                        (dotimes [i n] (aset out i (f)))
                        (let [ms (/ (- (System/nanoTime) t) (* 1e6 n))]
                          (doseq [v out] (cleanup v))
                          ms))))]
    (println label (nth times 3) "ms" (vec times))))
(doseq [size [100 10000]]
  (let [xs (mapv #(vector (str "N" %)) (range size))
        pairs (mapv #(vector % "value") xs)
        n (if (= size 100) 100 3)]
    (let [es (e/into (s/empty-hash-set) xs)
          em (e/into (m/empty-hash-map) pairs)]
      (assert (= size (e/count es) (e/count em)))
      (assert (e/contains? es (last xs)))
      (assert (= "value" (e/get em (last xs))))
      (s/dispose! es) (m/dispose! em)
      (println "CORRECT" size))
    (bench (str "host-set-" size) n #(into #{} xs) identity)
    (bench (str "eve-set-" size) n #(e/into (s/empty-hash-set) xs) s/dispose!)
    (bench (str "host-map-" size) n #(into {} pairs) identity)
    (bench (str "eve-map-" size) n #(e/into (m/empty-hash-map) pairs) m/dispose!)
    (bench (str "encode+hash-floor-" size) n
           #(doseq [x xs] (hu/portable-hash-bytes (codec/encode-key x))) identity)))

;; Revisit 100 after the 10k workloads have exercised the same code paths.
;; These are the steady-state 100-element numbers, not the startup samples.
(let [xs (mapv #(vector (str "N" %)) (range 100))
      pairs (mapv #(vector % "value") xs)]
  (bench "hot-host-set-100" 1000 #(into #{} xs) identity)
  (bench "hot-eve-set-100" 1000 #(e/into (s/empty-hash-set) xs) s/dispose!)
  (bench "hot-host-map-100" 1000 #(into {} pairs) identity)
  (bench "hot-eve-map-100" 1000 #(e/into (m/empty-hash-map) pairs) m/dispose!)
  (bench "hot-encode+hash-floor-100" 1000
         #(doseq [x xs] (hu/portable-hash-bytes (codec/encode-key x))) identity))
