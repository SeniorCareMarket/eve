(ns eve.columnar-bench
  "Benchmarks comparing Eve atom + columnar data structures vs stock Clojure
   atom + plain vectors/maps.

   All operations are performed inside atom swap! calls to reflect real-world
   usage patterns and ensure proper memory management (the swap/deref formalism
   prevents SAB memory leaks on CLJS by retiring old values).

   Eve side:  shared-atom (CLJS) or standard atom (JVM) with EveArray/Dataset/Tensor
   Stock side: standard atom with plain Clojure vectors/maps

   Run via:
     Node:  node target/eve-test/all.js columnar-bench
     JVM:   clojure -M:jvm-test -m eve.columnar-bench"
  (:require
   [eve.array :as arr]
   [eve.dataset :as ds]
   [eve.dataset.functional :as func]
   [eve.dataset.argops :as argops]
   [eve.tensor :as tensor]
   #?(:cljs [eve.shared-atom :as sa])))

;;=============================================================================
;; Timing infrastructure
;;=============================================================================

(defn- now-ms []
  #?(:cljs (js/Date.now)
     :clj  (System/currentTimeMillis)))

(defn- sqrt-int [n]
  (int #?(:cljs (js/Math.sqrt n)
          :clj  (Math/sqrt (double n)))))

(defn- fmt1 [x]
  #?(:cljs (.toFixed (js/Number. x) 1)
     :clj  (clojure.core/format "%.1f" (double x))))

(defn- fmt2 [x]
  #?(:cljs (.toFixed (js/Number. x) 2)
     :clj  (clojure.core/format "%.2f" (double x))))

(defn- bench
  "Run f repeatedly, return {:mean-ms :min-ms :max-ms :runs}.
   Warmup runs are discarded."
  [label f & {:keys [warmup runs] :or {warmup 3 runs 7}}]
  ;; warmup
  (dotimes [_ warmup] (f))
  ;; timed runs
  (let [times (loop [i 0 acc []]
                (if (>= i runs)
                  acc
                  (let [t0 (now-ms)
                        _ (f)
                        elapsed (- (now-ms) t0)]
                    (recur (inc i) (conj acc elapsed)))))
        sorted (sort times)
        mean (/ (double (reduce + 0 times)) (count times))
        mn (first sorted)
        mx (last sorted)]
    {:label label :mean-ms mean :min-ms mn :max-ms mx :runs runs :times sorted}))

(defn- print-comparison [label eve-result stock-result]
  (let [speedup (if (pos? (:mean-ms eve-result))
                  (/ (:mean-ms stock-result) (:mean-ms eve-result))
                  ##Inf)]
    (println)
    (println (str "  " label))
    (println (str "    Eve:   " (fmt1 (:mean-ms eve-result)) "ms"
                  "  (min=" (:min-ms eve-result) " max=" (:max-ms eve-result) ")"))
    (println (str "    Stock: " (fmt1 (:mean-ms stock-result)) "ms"
                  "  (min=" (:min-ms stock-result) " max=" (:max-ms stock-result) ")"))
    (println (str "    Speedup: " (fmt2 (double speedup)) "x"))
    speedup))

;;=============================================================================
;; Data generation helpers
;;=============================================================================

(defn- random-doubles
  "Generate n random doubles in [0, scale)."
  [n scale]
  (vec (repeatedly n #(* (rand) scale))))

(defn- random-ints
  "Generate n random ints in [0, max-val)."
  [n max-val]
  (vec (repeatedly n #(int (* (rand) max-val)))))

;;=============================================================================
;; Eve atom constructor (platform-specific)
;;=============================================================================

(defn- make-eve-atom
  "Create an Eve atom. CLJS: SAB-backed shared-atom. JVM: standard atom."
  [initial-val]
  #?(:cljs (sa/atom initial-val)
     :clj  (atom initial-val)))

;;=============================================================================
;; 1. ARRAY ARITHMETIC — element-wise add, mul, sum
;;=============================================================================

(defn bench-array-arithmetic! [n]
  (println (str "\n=== Array Arithmetic (n=" n ") ==="))
  (let [data-a (random-doubles n 1000.0)
        data-b (random-doubles n 1000.0)
        eve-a  (make-eve-atom {:a (arr/eve-array :float64 data-a)
                               :b (arr/eve-array :float64 data-b)})
        stock-a (atom {:a data-a :b data-b})]

    (print-comparison
      (str "element-wise add (" n " elems)")
      (bench "eve-add"
        #(swap! eve-a (fn [s] (assoc s :result (func/add (:a s) (:b s))))))
      (bench "stock-add"
        #(swap! stock-a (fn [s] (assoc s :result (mapv + (:a s) (:b s)))))))

    (print-comparison
      (str "element-wise mul (" n " elems)")
      (bench "eve-mul"
        #(swap! eve-a (fn [s] (assoc s :result (func/mul (:a s) (:b s))))))
      (bench "stock-mul"
        #(swap! stock-a (fn [s] (assoc s :result (mapv * (:a s) (:b s)))))))

    (print-comparison
      (str "sum reduction (" n " elems)")
      (bench "eve-sum"
        #(swap! eve-a (fn [s] (assoc s :result (func/sum (:a s))))))
      (bench "stock-sum"
        #(swap! stock-a (fn [s] (assoc s :result (reduce + 0.0 (:a s)))))))

    (print-comparison
      (str "mean (" n " elems)")
      (bench "eve-mean"
        #(swap! eve-a (fn [s] (assoc s :result (func/mean (:a s))))))
      (bench "stock-mean"
        #(swap! stock-a (fn [s]
           (let [a (:a s)]
             (assoc s :result (/ (reduce + 0.0 a) (count a))))))))

    (print-comparison
      (str "min-val (" n " elems)")
      (bench "eve-min"
        #(swap! eve-a (fn [s] (assoc s :result (func/min-val (:a s))))))
      (bench "stock-min"
        #(swap! stock-a (fn [s] (assoc s :result (reduce min (:a s)))))))

    (print-comparison
      (str "max-val (" n " elems)")
      (bench "eve-max"
        #(swap! eve-a (fn [s] (assoc s :result (func/max-val (:a s))))))
      (bench "stock-max"
        #(swap! stock-a (fn [s] (assoc s :result (reduce max (:a s)))))))))

;;=============================================================================
;; 2. COMPARISON / MASK ops
;;=============================================================================

(defn bench-comparison-ops! [n]
  (println (str "\n=== Comparison Ops (n=" n ") ==="))
  (let [data (random-doubles n 100.0)
        eve-a   (make-eve-atom {:col (arr/eve-array :float64 data)})
        stock-a (atom {:col data})
        threshold 50.0]

    (print-comparison
      (str "gt mask (" n " elems)")
      (bench "eve-gt"
        #(swap! eve-a (fn [s] (assoc s :result (func/gt (:col s) threshold)))))
      (bench "stock-gt"
        #(swap! stock-a (fn [s]
           (let [gt-fn (fn [x] (if (> x threshold) 1 0))]
             (assoc s :result (mapv gt-fn (:col s))))))))

    (let [double-fn (fn [x] (* x 2.0))]
      (print-comparison
        (str "emap (*2) (" n " elems)")
        (bench "eve-emap"
          #(swap! eve-a (fn [s] (assoc s :result (func/emap double-fn (:col s))))))
        (bench "stock-mapv"
          #(swap! stock-a (fn [s] (assoc s :result (mapv double-fn (:col s))))))))))

;;=============================================================================
;; 3. ARGOPS — argsort, argfilter, take-indices
;;=============================================================================

(defn bench-argops! [n]
  (println (str "\n=== Argops (n=" n ") ==="))
  (let [data (random-ints n 10000)
        eve-a   (make-eve-atom {:col (arr/eve-array :int32 data)})
        stock-a (atom {:col data})]

    (print-comparison
      (str "argsort ascending (" n " elems)")
      (bench "eve-argsort"
        #(swap! eve-a (fn [s] (assoc s :result (argops/argsort (:col s) :asc)))))
      (bench "stock-argsort"
        #(swap! stock-a (fn [s]
           (assoc s :result
             (vec (map first (sort-by second (map-indexed vector (:col s))))))))))

    (let [gt5k (fn [x] (> x 5000))]
      (print-comparison
        (str "argfilter (>5000) (" n " elems)")
        (bench "eve-argfilter"
          #(swap! eve-a (fn [s] (assoc s :result (argops/argfilter (:col s) gt5k)))))
        (bench "stock-argfilter"
          #(swap! stock-a (fn [s]
             (assoc s :result
               (vec (keep-indexed (fn [i v] (when (gt5k v) i)) (:col s)))))))))

    (print-comparison
      (str "argmin (" n " elems)")
      (bench "eve-argmin"
        #(swap! eve-a (fn [s] (assoc s :result (argops/argmin (:col s))))))
      (bench "stock-argmin"
        #(swap! stock-a (fn [s]
           (assoc s :result
             (first (reduce-kv (fn [[bi bv] i v] (if (< v bv) [i v] [bi bv]))
                      [0 (nth (:col s) 0)]
                      (:col s))))))))

    (let [idx-data (random-ints (quot n 2) n)
          eve-idx (arr/eve-array :int32 idx-data)]
      (print-comparison
        (str "take-indices (" (quot n 2) " from " n ")")
        (bench "eve-gather"
          #(swap! eve-a (fn [s] (assoc s :result (argops/take-indices (:col s) eve-idx)))))
        (bench "stock-gather"
          #(swap! stock-a (fn [s]
             (let [col (:col s)]
               (assoc s :result (mapv (fn [i] (nth col i)) idx-data))))))))))

;;=============================================================================
;; 4. DATASET — filter-rows, sort-by-column, head/tail/slice
;;=============================================================================

(defn bench-dataset-ops! [n]
  (println (str "\n=== Dataset Ops (n=" n ", 4 columns) ==="))
  (let [prices (random-doubles n 1000.0)
        qtys (random-ints n 1000)
        ids (vec (range n))
        flags (random-ints n 2)
        ;; Store raw columns in atoms; construct Dataset inside swap!
        eve-a (make-eve-atom {:price (arr/eve-array :float64 prices)
                              :qty   (arr/eve-array :int32 qtys)
                              :id    (arr/eve-array :int32 ids)
                              :flag  (arr/eve-array :int32 flags)})
        col-keys [:price :qty :id :flag]
        stock-a (atom {:price prices :qty qtys :id ids :flag flags})]

    (let [gt500 (fn [x] (> x 500.0))]
      (print-comparison
        (str "filter-rows (price > 500) (" n " rows)")
        (bench "eve-filter"
          #(swap! eve-a (fn [s]
             (let [ds (ds/dataset (select-keys s col-keys))]
               (assoc s :result (ds/filter-rows ds :price gt500))))))
        (bench "stock-filter"
          #(swap! stock-a (fn [s]
             (let [keep-idx (vec (keep-indexed (fn [i v] (when (gt500 v) i)) (:price s)))]
               (assoc s :result
                 (into {} (map (fn [k] [k (mapv (fn [i] (nth (get s k) i)) keep-idx)]) col-keys)))))))))

    (print-comparison
      (str "sort-by-column :price asc (" n " rows)")
      (bench "eve-sort"
        #(swap! eve-a (fn [s]
           (let [ds (ds/dataset (select-keys s col-keys))]
             (assoc s :result (ds/sort-by-column ds :price :asc))))))
      (bench "stock-sort"
        #(swap! stock-a (fn [s]
           (let [idx (vec (map first (sort-by second (map-indexed vector (:price s)))))]
             (assoc s :result
               (into {} (map (fn [k] [k (mapv (fn [i] (nth (get s k) i)) idx)]) col-keys))))))))

    (let [h (min 100 n)]
      (print-comparison
        (str "head " h " (" n " rows)")
        (bench "eve-head"
          #(swap! eve-a (fn [s]
             (let [ds (ds/dataset (select-keys s col-keys))]
               (assoc s :result (ds/head ds h))))))
        (bench "stock-head"
          #(swap! stock-a (fn [s]
             (assoc s :result
               (into {} (map (fn [k] [k (subvec (get s k) 0 h)]) col-keys))))))))

    (let [start (quot n 4)
          end   (quot (* 3 n) 4)]
      (print-comparison
        (str "slice [" start "," end ") (" n " rows)")
        (bench "eve-slice"
          #(swap! eve-a (fn [s]
             (let [ds (ds/dataset (select-keys s col-keys))]
               (assoc s :result (ds/slice ds start end))))))
        (bench "stock-slice"
          #(swap! stock-a (fn [s]
             (assoc s :result
               (into {} (map (fn [k] [k (subvec (get s k) start end)]) col-keys))))))))))

;;=============================================================================
;; 5. TENSOR — emap, ereduce, transpose+materialize, random mget
;;=============================================================================

(defn bench-tensor-ops! [n]
  (let [side (sqrt-int n)
        total (* side side)]
    (println (str "\n=== Tensor Ops (" side "x" side " = " total " elems) ==="))
    (let [data (random-doubles total 100.0)
          ;; Store raw backing array in atom; construct tensor view inside swap!
          eve-a (make-eve-atom {:data (arr/eve-array :float64 data) :side side})
          stock-a (atom {:t (vec (map vec (partition side data)))})
          double-fn (fn [x] (* x 2.0))]

      (print-comparison
        (str "emap (*2) " side "x" side)
        (bench "eve-emap"
          #(swap! eve-a (fn [s]
             (let [t (tensor/from-array (:data s) [(:side s) (:side s)])]
               (assoc s :result (tensor/emap double-fn t))))))
        (bench "stock-emap"
          #(swap! stock-a (fn [s]
             (assoc s :result (mapv (fn [row] (mapv double-fn row)) (:t s)))))))

      (print-comparison
        (str "ereduce (sum) " side "x" side)
        (bench "eve-ereduce"
          #(swap! eve-a (fn [s]
             (let [t (tensor/from-array (:data s) [(:side s) (:side s)])]
               (assoc s :result (tensor/ereduce + 0.0 t))))))
        (bench "stock-reduce"
          #(swap! stock-a (fn [s]
             (assoc s :result
               (reduce (fn [acc row] (reduce + acc row)) 0.0 (:t s)))))))

      (print-comparison
        (str "transpose + to-array " side "x" side)
        (bench "eve-transpose"
          #(swap! eve-a (fn [s]
             (let [t (tensor/from-array (:data s) [(:side s) (:side s)])]
               (assoc s :result (tensor/to-array (tensor/transpose t)))))))
        (bench "stock-transpose"
          #(swap! stock-a (fn [s]
             (assoc s :result (vec (apply concat (apply map vector (:t s)))))))))

      (let [indices (vec (repeatedly 1000 #(vector (rand-int side) (rand-int side))))]
        (print-comparison
          (str "1000 random mget " side "x" side)
          (bench "eve-mget"
            #(swap! eve-a (fn [s]
               (let [t (tensor/from-array (:data s) [(:side s) (:side s)])]
                 (doseq [[r c] indices] (tensor/mget t r c))
                 s))))
          (bench "stock-get-in"
            #(swap! stock-a (fn [s]
               (let [t (:t s)]
                 (doseq [[r c] indices] (get-in t [r c]))
                 s)))))))))

;;=============================================================================
;; 6. END-TO-END: dataset pipeline (construct -> filter -> sort -> aggregate)
;;=============================================================================

(defn bench-pipeline! [n]
  (println (str "\n=== End-to-End Pipeline (n=" n ") ==="))
  (let [prices (random-doubles n 1000.0)
        qtys   (random-ints n 500)
        gt200  (fn [x] (> x 200.0))
        eve-a  (make-eve-atom {:prices (arr/eve-array :float64 prices)
                               :qtys   (arr/eve-array :int32 qtys)})
        stock-a (atom {:prices prices :qtys qtys})]

    (print-comparison
      (str "construct -> filter -> sort -> sum (" n " rows)")
      (bench "eve-pipeline"
        #(swap! eve-a (fn [s]
           (let [ds1 (ds/dataset {:price (:prices s) :qty (:qtys s)})
                 ds2 (ds/filter-rows ds1 :price gt200)
                 ds3 (ds/sort-by-column ds2 :price :desc)]
             (assoc s :result (func/sum (ds/column ds3 :price)))))))
      (bench "stock-pipeline"
        #(swap! stock-a (fn [s]
           (let [p (:prices s)
                 keep-idx (vec (keep-indexed (fn [i v] (when (gt200 v) i)) p))
                 filtered-prices (mapv (fn [i] (nth p i)) keep-idx)
                 sort-fn (fn [pair] (- (second pair)))
                 sort-idx (vec (map first (sort-by sort-fn
                                                    (map-indexed vector filtered-prices))))
                 sorted-prices (mapv (fn [i] (nth filtered-prices i)) sort-idx)]
             (assoc s :result (reduce + 0.0 sorted-prices)))))))))

;;=============================================================================
;; Runner
;;=============================================================================

(defn run-all! []
  (println "==============================================================")
  (println "  Eve Columnar Benchmarks -- Eve Atom vs Stock Atom")
  (println "  Each operation: 3 warmup + 7 timed runs, reporting mean/min/max")
  (println "  All operations performed inside atom swap! calls")
  (println "==============================================================")

  ;; Small scale (1K)
  (bench-array-arithmetic! 1000)
  (bench-comparison-ops! 1000)
  (bench-argops! 1000)
  (bench-dataset-ops! 1000)
  (bench-tensor-ops! 1024)    ;; 32x32
  (bench-pipeline! 1000)

  ;; Medium scale (100K)
  (bench-array-arithmetic! 100000)
  (bench-comparison-ops! 100000)
  (bench-argops! 100000)
  (bench-dataset-ops! 100000)
  (bench-tensor-ops! 100489)  ;; 317x317
  (bench-pipeline! 100000)

  ;; NOTE: SAB memory (~256MB) limits max scale; cumulative allocations across
  ;; warmup + timed runs exhaust it at >100K. Two tiers suffice to show crossover.

  (println)
  (println "==============================================================")
  (println "  Benchmarks complete.")
  (println "=============================================================="))

;; JVM entry point
#?(:clj
   (defn -main [& _args]
     (run-all!)))
