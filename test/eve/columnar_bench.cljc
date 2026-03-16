(ns eve.columnar-bench
  "Benchmarks comparing Eve atom + columnar data vs stock Clojure atom + vectors.

   Each benchmark does a realistic multi-step workload inside a single swap! —
   enough computation that atom overhead is amortized and you see the actual
   data-structure performance difference.

   Eve side:  mmap-backed persistent atom with EveArray/Dataset/Tensor
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
   #?@(:cljs [[eve.shared-atom :as sa]]
       :clj  [[eve.atom :as eve-atom]
              [eve.deftype-proto.alloc :as alloc]])))

;;=============================================================================
;; Timing infrastructure
;;=============================================================================

(defn- now-ms []
  #?(:cljs (js/Date.now)
     :clj  (System/currentTimeMillis)))

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
  (dotimes [_ warmup] (f))
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

(defn- random-doubles [n scale]
  (vec (repeatedly n #(* (rand) scale))))

(defn- random-ints [n max-val]
  (vec (repeatedly n #(int (* (rand) max-val)))))

;;=============================================================================
;; Eve atom constructor (platform-specific)
;;=============================================================================

(def ^:private bench-counter
  #?(:cljs (cljs.core/atom 0)
     :clj  (clojure.core/atom 0)))

(defn- make-eve-atom [initial-val]
  #?(:cljs (sa/atom initial-val)
     :clj  (let [n (swap! bench-counter inc)
                 path (str "/tmp/eve-bench-" n "/")]
             (.mkdirs (java.io.File. path))
             (eve-atom/atom {:persistent path} initial-val))))

;;=============================================================================
;; 1. COLUMN STATISTICS — compute 5 aggregates in one swap
;;    sum + mean + min + max + count-above-threshold
;;=============================================================================

(defn bench-column-stats! [n]
  (println (str "\n=== Column Statistics (n=" n ") ==="))
  (println "    Work per swap: sum + mean + min + max + gt-mask + emap(*2)")
  (let [data   (random-doubles n 1000.0)
        thresh 500.0
        dbl-fn (fn [x] (* x 2.0))
        eve-a  (make-eve-atom {:col (arr/eve-array :float64 data)})
        stock-a (atom {:col data})]
    (print-comparison
      (str "6-op summary (" n " elems)")
      (bench "eve-stats"
        #(swap! eve-a (fn [s]
           (let [c (:col s)
                 mask (func/gt c thresh)]
             (assoc s :result
               {:sum    (func/sum c)
                :mean   (func/mean c)
                :min    (func/min-val c)
                :max    (func/max-val c)
                :mask   mask
                :scaled (func/emap dbl-fn c)} )))))
      (bench "stock-stats"
        #(swap! stock-a (fn [s]
           (let [c (:col s)]
             (assoc s :result
               {:sum    (reduce + 0.0 c)
                :mean   (/ (reduce + 0.0 c) (count c))
                :min    (reduce min c)
                :max    (reduce max c)
                :mask   (mapv (fn [x] (if (> x thresh) 1 0)) c)
                :scaled (mapv dbl-fn c)}))))))))

;;=============================================================================
;; 2. MULTI-COLUMN TRANSFORM — element-wise arithmetic pipeline
;;    revenue = price * qty, margin = revenue - cost, then sum(margin)
;;=============================================================================

(defn bench-column-pipeline! [n]
  (println (str "\n=== Multi-Column Transform (n=" n ") ==="))
  (println "    Work per swap: revenue=price*qty, margin=revenue-cost, sum(margin)")
  (let [prices (random-doubles n 100.0)
        qtys   (random-doubles n 50.0)
        costs  (random-doubles n 2000.0)
        eve-a  (make-eve-atom {:price (arr/eve-array :float64 prices)
                               :qty   (arr/eve-array :float64 qtys)
                               :cost  (arr/eve-array :float64 costs)})
        stock-a (atom {:price prices :qty qtys :cost costs})]
    (print-comparison
      (str "price*qty - cost -> sum (" n " rows)")
      (bench "eve-transform"
        #(swap! eve-a (fn [s]
           (let [revenue (func/mul (:price s) (:qty s))
                 margin  (func/sub revenue (:cost s))]
             (assoc s :result (func/sum margin))))))
      (bench "stock-transform"
        #(swap! stock-a (fn [s]
           (let [p (:price s) q (:qty s) c (:cost s)
                 margin (mapv (fn [pi qi ci] (- (* pi qi) ci)) p q c)]
             (assoc s :result (reduce + 0.0 margin)))))))))

;;=============================================================================
;; 3. DATASET ANALYTICS — filter -> sort -> top-N -> aggregate
;;    "Find top 100 most expensive items with qty > 10, compute avg price"
;;=============================================================================

(defn bench-dataset-analytics! [n]
  (println (str "\n=== Dataset Analytics (n=" n ", 3 columns) ==="))
  (println "    Work per swap: build dataset -> filter(qty>10) -> sort(price desc) -> head(100) -> mean(price)")
  (let [prices (random-doubles n 1000.0)
        qtys   (random-ints n 100)
        ids    (vec (range n))
        gt10   (fn [x] (> x 10))
        eve-a  (make-eve-atom {:price (arr/eve-array :float64 prices)
                               :qty   (arr/eve-array :int32 qtys)
                               :id    (arr/eve-array :int32 ids)})
        stock-a (atom {:price prices :qty qtys :id ids})]
    (print-comparison
      (str "filter->sort->head(100)->mean (" n " rows)")
      (bench "eve-analytics"
        #(swap! eve-a (fn [s]
           (let [ds1 (ds/dataset {:price (:price s) :qty (:qty s) :id (:id s)})
                 ds2 (ds/filter-rows ds1 :qty gt10)
                 ds3 (ds/sort-by-column ds2 :price :desc)
                 ds4 (ds/head ds3 (min 100 (ds/row-count ds3)))]
             (assoc s :result (func/mean (ds/column ds4 :price)))))))
      (bench "stock-analytics"
        #(swap! stock-a (fn [s]
           (let [p (:price s) q (:qty s)
                 ;; filter: keep indices where qty > 10
                 keep-idx (vec (keep-indexed (fn [i v] (when (gt10 v) i)) q))
                 filt-p   (mapv (fn [i] (nth p i)) keep-idx)
                 ;; sort filtered prices descending
                 sorted-p (vec (sort (fn [a b] (compare b a)) filt-p))
                 ;; head 100
                 top      (subvec sorted-p 0 (min 100 (count sorted-p)))]
             (assoc s :result (/ (reduce + 0.0 top) (count top))))))))))

;;=============================================================================
;; 4. ARGOPS CHAIN — argsort + gather + argfilter + stats on result
;;    "Sort column, gather top half, filter >median, count"
;;=============================================================================

(defn bench-argops-chain! [n]
  (println (str "\n=== Argops Chain (n=" n ") ==="))
  (println "    Work per swap: argsort -> gather(top half) -> argfilter(>median) -> count")
  (let [data (random-ints n 100000)
        eve-a   (make-eve-atom {:col (arr/eve-array :int32 data)})
        stock-a (atom {:col data})]
    (print-comparison
      (str "sort->gather->filter->count (" n " elems)")
      (bench "eve-argchain"
        #(swap! eve-a (fn [s]
           (let [c   (:col s)
                 idx (argops/argsort c :asc)
                 ;; take top half (largest values)
                 half-start (quot (count idx) 2)
                 top-idx (argops/take-indices idx
                           (arr/eve-array :int32
                             (vec (range half-start (count idx)))))
                 top-vals (argops/take-indices c top-idx)
                 ;; filter those > 75000
                 above (argops/argfilter top-vals (fn [x] (> x 75000)))]
             (assoc s :result (count above))))))
      (bench "stock-argchain"
        #(swap! stock-a (fn [s]
           (let [c (:col s)
                 sorted-idx (vec (map first (sort-by second (map-indexed vector c))))
                 half-start (quot (count sorted-idx) 2)
                 top-idx (subvec sorted-idx half-start)
                 top-vals (mapv (fn [i] (nth c i)) top-idx)
                 above (filterv (fn [x] (> x 75000)) top-vals)]
             (assoc s :result (count above)))))))))

;;=============================================================================
;; 5. TENSOR PIPELINE — emap + transpose + reduce in one swap
;;    "Double all elements, transpose, flatten, sum"
;;=============================================================================

(defn- sqrt-int [n]
  (int #?(:cljs (js/Math.sqrt n)
          :clj  (Math/sqrt (double n)))))

(defn bench-tensor-pipeline! [n]
  (let [side  (sqrt-int n)
        total (* side side)]
    (println (str "\n=== Tensor Pipeline (" side "x" side " = " total " elems) ==="))
    (println "    Work per swap: emap(*2) -> transpose -> to-array -> sum")
    (let [data (random-doubles total 100.0)
          double-fn (fn [x] (* x 2.0))
          eve-a (make-eve-atom {:data (arr/eve-array :float64 data) :side side})
          stock-a (atom {:t (vec (map vec (partition side data)))})]
      (print-comparison
        (str "emap->transpose->flatten->sum " side "x" side)
        (bench "eve-tensor"
          #(swap! eve-a (fn [s]
             (let [t  (tensor/from-array (:data s) [(:side s) (:side s)])
                   t2 (tensor/emap double-fn t)
                   t3 (tensor/transpose t2)
                   flat (tensor/to-array t3)]
               (assoc s :result (tensor/ereduce + 0.0
                                  (tensor/from-array flat [(count flat)])))))))
        (bench "stock-tensor"
          #(swap! stock-a (fn [s]
             (let [t  (:t s)
                   t2 (mapv (fn [row] (mapv double-fn row)) t)
                   t3 (apply mapv vector t2)
                   flat (vec (apply concat t3))
                   total (reduce + 0.0 flat)]
               (assoc s :result total)))))))))

;;=============================================================================
;; 6. FULL ANALYTICS PIPELINE
;;    "Build 4-column dataset, compute derived column, filter, sort, slice,
;;     then aggregate two columns"
;;=============================================================================

(defn bench-full-pipeline! [n]
  (println (str "\n=== Full Analytics Pipeline (n=" n ") ==="))
  (println "    Work per swap: build ds -> add revenue col -> filter(revenue>500)")
  (println "                   -> sort(revenue desc) -> slice[0,200) -> sum(revenue) + mean(price)")
  (let [prices (random-doubles n 100.0)
        qtys   (random-doubles n 20.0)
        cats   (random-ints n 10)
        ids    (vec (range n))
        gt500  (fn [x] (> x 500.0))
        eve-a  (make-eve-atom {:price (arr/eve-array :float64 prices)
                               :qty   (arr/eve-array :float64 qtys)
                               :cat   (arr/eve-array :int32 cats)
                               :id    (arr/eve-array :int32 ids)})
        stock-a (atom {:price prices :qty qtys :cat cats :id ids})]
    (print-comparison
      (str "build->derive->filter->sort->slice->agg (" n " rows)")
      (bench "eve-full"
        #(swap! eve-a (fn [s]
           (let [ds1  (ds/dataset {:price (:price s) :qty (:qty s)
                                   :cat (:cat s) :id (:id s)})
                 ;; derived column
                 rev  (func/mul (:price s) (:qty s))
                 ds2  (ds/add-column ds1 :revenue rev)
                 ;; filter
                 ds3  (ds/filter-rows ds2 :revenue gt500)
                 ;; sort
                 ds4  (ds/sort-by-column ds3 :revenue :desc)
                 ;; slice top 200
                 take-n (min 200 (ds/row-count ds4))
                 ds5  (ds/head ds4 take-n)
                 ;; aggregate
                 rev-sum    (func/sum (ds/column ds5 :revenue))
                 price-mean (func/mean (ds/column ds5 :price))]
             (assoc s :result {:rev-sum rev-sum :price-mean price-mean})))))
      (bench "stock-full"
        #(swap! stock-a (fn [s]
           (let [p (:price s) q (:qty s) c (:cat s) id (:id s)
                 ;; derived column
                 rev (mapv * p q)
                 ;; filter indices where revenue > 500
                 keep-idx (vec (keep-indexed (fn [i v] (when (gt500 v) i)) rev))
                 filt-p   (mapv (fn [i] (nth p i)) keep-idx)
                 filt-rev (mapv (fn [i] (nth rev i)) keep-idx)
                 ;; sort by revenue descending
                 sort-idx (vec (map first
                               (sort-by (fn [[_ v]] (- v))
                                 (map-indexed vector filt-rev))))
                 sorted-p   (mapv (fn [i] (nth filt-p i)) sort-idx)
                 sorted-rev (mapv (fn [i] (nth filt-rev i)) sort-idx)
                 ;; head 200
                 take-n (min 200 (count sorted-rev))
                 top-rev (subvec sorted-rev 0 take-n)
                 top-p   (subvec sorted-p 0 take-n)
                 ;; aggregate
                 rev-sum    (reduce + 0.0 top-rev)
                 price-mean (/ (reduce + 0.0 top-p) (count top-p))]
             (assoc s :result {:rev-sum rev-sum :price-mean price-mean}))))))))

;;=============================================================================
;; Runner
;;=============================================================================

(defn run-all! []
  (println "==============================================================")
  (println "  Eve Columnar Benchmarks — Realistic Workloads")
  (println "  Each swap! does a multi-step pipeline of real work")
  (println "  3 warmup + 7 timed runs, reporting mean/min/max")
  (println "==============================================================")

  ;; 10K — moderate scale, atom overhead visible but work dominates
  (bench-column-stats! 10000)
  (bench-column-pipeline! 10000)
  (bench-dataset-analytics! 10000)
  (bench-argops-chain! 10000)
  (bench-tensor-pipeline! 10000) ;; 100x100
  (bench-full-pipeline! 10000)

  ;; 100K — work clearly dominates atom overhead
  (bench-column-stats! 100000)
  (bench-column-pipeline! 100000)
  (bench-dataset-analytics! 100000)
  (bench-argops-chain! 100000)
  (bench-tensor-pipeline! 100000) ;; 316x316
  (bench-full-pipeline! 100000)

  ;; 1M — big data territory
  (bench-column-stats! 1000000)
  (bench-column-pipeline! 1000000)
  (bench-argops-chain! 1000000)
  (bench-tensor-pipeline! 1000000) ;; 1000x1000

  (println)
  (println "==============================================================")
  (println "  Benchmarks complete.")
  (println "=============================================================="))

;; JVM entry point
#?(:clj
   (defn -main [& _args]
     (run-all!)))
