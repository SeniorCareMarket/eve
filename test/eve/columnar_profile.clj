(ns eve.columnar-profile
  "Micro-benchmarks to profile exactly where time is spent in columnar ops.

   Compares:
   1. Raw double[] loop (what columnar SHOULD cost)
   2. JvmHeapEveArray nth loop (protocol dispatch + case + bounds check per elem)
   3. JvmHeapEveArray direct backing loop (bypass nth, use raw array)
   4. JvmEveArray (slab-backed) nth loop (slab IO + ByteBuffer alloc per elem)
   5. func/sum over EveArray (the actual benchmark hot path)

   Run: clojure -M:columnar-bench -m eve.columnar-profile"
  (:require
   [eve.array :as arr]
   [eve.dataset.functional :as func]
   [eve.dataset.argops :as argops]
   [eve.atom :as eve-atom]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]))

(defn bench [label f & {:keys [warmup runs] :or {warmup 3 runs 7}}]
  (dotimes [_ warmup] (f))
  (let [times (long-array runs)]
    (dotimes [i runs]
      (let [t0 (System/nanoTime)
            _ (f)
            elapsed (- (System/nanoTime) t0)]
        (aset times i elapsed)))
    (let [sorted (sort (vec times))
          mean (/ (double (reduce + 0 sorted)) (count sorted))
          median (nth sorted (quot (count sorted) 2))]
      (printf "  %-45s  mean=%8.2fms  median=%8.2fms  min=%8.2fms%n"
              label
              (/ mean 1e6)
              (/ (double median) 1e6)
              (/ (double (first sorted)) 1e6))
      (flush)
      (/ mean 1e6))))

(defn -main [& _args]
  (let [n 100000
        raw-data (double-array n)]
    ;; Fill with random data
    (dotimes [i n]
      (aset raw-data i (* (Math/random) 1000.0)))
    (let [data-vec (vec (seq raw-data))]

      (println)
      (println "================================================================")
      (println "  Columnar Hot Path Profile — 100K float64 elements")
      (println "================================================================")

      ;; ---------------------------------------------------------------
      ;; LEVEL 1: Raw double[] — the theoretical minimum
      ;; ---------------------------------------------------------------
      (println)
      (println "--- Level 1: Raw double[] (theoretical floor) ---")
      (bench "sum: raw double[] areduce"
        (fn []
          (let [^doubles a raw-data
                n (alength a)]
            (loop [i 0 acc 0.0]
              (if (< i n)
                (recur (inc i) (+ acc (aget a i)))
                acc)))))

      ;; ---------------------------------------------------------------
      ;; LEVEL 2: JvmHeapEveArray via nth (what func/sum actually does)
      ;; ---------------------------------------------------------------
      (println)
      (println "--- Level 2: JvmHeapEveArray via nth (current func/sum path) ---")
      (let [heap-arr (arr/eve-array :float64 data-vec)]
        (bench "sum: heap-eve-array .nth loop"
          (fn []
            (let [n (count heap-arr)]
              (loop [i 0 acc 0.0]
                (if (< i n)
                  (recur (inc i) (+ acc (double (nth heap-arr i))))
                  acc)))))

        (bench "func/sum over heap-eve-array"
          (fn [] (func/sum heap-arr)))

        ;; ---------------------------------------------------------------
        ;; LEVEL 2b: JvmHeapEveArray — bypass nth, use backing array
        ;; ---------------------------------------------------------------
        (println)
        (println "--- Level 2b: JvmHeapEveArray — direct backing array access ---")
        (when (satisfies? d/IBackingArray heap-arr)
          (let [^doubles backing (d/-backing-array heap-arr)]
            (bench "sum: direct backing double[] areduce"
              (fn []
                (let [n (alength backing)]
                  (loop [i 0 acc 0.0]
                    (if (< i n)
                      (recur (inc i) (+ acc (aget backing i)))
                      acc))))))))

      ;; ---------------------------------------------------------------
      ;; LEVEL 3: JvmEveArray (slab-backed) — what mmap atom uses
      ;; ---------------------------------------------------------------
      (println)
      (println "--- Level 3: Slab-backed JvmEveArray via nth (mmap atom path) ---")
      (let [path (str "/tmp/eve-profile-" (System/currentTimeMillis) "/")
            _ (.mkdirs (java.io.File. path))
            a (eve-atom/atom {:persistent path}
                             {:col (arr/eve-array :float64 data-vec)})]
        ;; Read back from atom — col is now a slab-backed JvmEveArray
        (let [slab-arr (:col @a)]
          (println (str "  slab-arr type: " (type slab-arr)))
          (bench "sum: slab-eve-array .nth loop"
            (fn []
              (let [n (count slab-arr)]
                (loop [i 0 acc 0.0]
                  (if (< i n)
                    (recur (inc i) (+ acc (double (nth slab-arr i))))
                    acc)))))

          (bench "func/sum over slab-eve-array"
            (fn [] (func/sum slab-arr))))

        ;; Clean up
        (eve-atom/close! a))

      ;; ---------------------------------------------------------------
      ;; LEVEL 4: Stock Clojure vector reduce — the "stock" comparison
      ;; ---------------------------------------------------------------
      (println)
      (println "--- Level 4: Stock Clojure vector (benchmark comparison) ---")
      (bench "sum: (reduce + 0.0 clj-vec)"
        (fn [] (reduce + 0.0 data-vec)))

      ;; ---------------------------------------------------------------
      ;; LEVEL 5: Argops — argsort comparison
      ;; ---------------------------------------------------------------
      (println)
      (println "--- Level 5: argsort (10K elements) ---")
      (let [n-sort 10000
            sort-data (vec (repeatedly n-sort #(int (* (Math/random) 100000))))
            sort-arr (arr/eve-array :int32 sort-data)]
        (bench "argsort: eve-array :int32 10K"
          (fn [] (argops/argsort sort-arr :asc)))
        (bench "argsort: clj vector 10K"
          (fn [] (vec (sort sort-data)))))

      ;; ---------------------------------------------------------------
      ;; LEVEL 6: Element-wise mul — func/mul path
      ;; ---------------------------------------------------------------
      (println)
      (println "--- Level 6: Element-wise mul (100K) ---")
      (let [a1 (arr/eve-array :float64 data-vec)
            a2 (arr/eve-array :float64 data-vec)]
        (bench "func/mul: two heap-eve-arrays"
          (fn [] (func/mul a1 a2)))
        (bench "raw: double[] element-wise mul"
          (fn []
            (let [^doubles x raw-data
                  ^doubles y raw-data
                  n (alength x)
                  ^doubles out (double-array n)]
              (dotimes [i n]
                (aset out i (* (aget x i) (aget y i))))
              out)))
        (bench "stock: mapv * two vecs"
          (fn [] (mapv * data-vec data-vec))))

      (println)
      (println "================================================================")
      (println "  Profile complete.")
      (println "================================================================")
      (println))))
