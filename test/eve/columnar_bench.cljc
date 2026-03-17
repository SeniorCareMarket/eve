(ns eve.columnar-bench
  "Comprehensive benchmarks: Eve atom + columnar data vs stock Clojure atom + vectors.

   Each benchmark does a realistic multi-step workload inside a single swap!.
   Runs in two modes:
     :mmap       — mmap-backed persistent atoms (cross-process capable)
     :in-memory  — SAB-backed (CLJS) / heap-backed (JVM) atoms (in-process only)
   Runs at 2 scales (10K, 100K) to show scaling behavior.

   Run via:
     Node:  node target/eve-test/all.js columnar-bench
     JVM:   clojure -M:columnar-bench"
  (:refer-clojure :exclude [atom])
  (:require
   [eve.array :as arr]
   [eve.dataset :as ds]
   [eve.dataset.functional :as func]
   [eve.dataset.argops :as argops]
   [eve.tensor :as tensor]
   [eve.atom :as e]
   #?(:clj [clojure.java.io :as io])))

;;=============================================================================
;; Platform + timing infrastructure
;;=============================================================================

(def ^:private platform
  #?(:cljs "node" :clj "jvm"))

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
  "Run f repeatedly, return {:mean-ms :min-ms :max-ms}.
   3 warmup runs discarded, 7 timed runs."
  [f]
  (dotimes [_ 3] (f))
  (let [times (loop [i 0 acc []]
                (if (>= i 7)
                  acc
                  (let [t0 (now-ms)
                        _ (f)
                        elapsed (- (now-ms) t0)]
                    (recur (inc i) (conj acc elapsed)))))
        sorted (sort times)
        ;; Drop highest and lowest, average the middle 5
        trimmed (subvec (vec sorted) 1 6)
        mean (/ (double (reduce + 0 trimmed)) (count trimmed))]
    {:mean-ms mean :min-ms (first sorted) :max-ms (last sorted)}))

;;=============================================================================
;; Atom helpers — create, close, cleanup
;;=============================================================================

(def ^:private bench-counter (clojure.core/atom 0))

(defn- bench-path
  "Generate a unique temp path for a persistent atom."
  [label]
  (let [n (clojure.core/swap! bench-counter inc)]
    (str "/tmp/eve-bench-" label "-" n)))

(def ^:private domain-extensions
  "File extensions created by a persistent atom domain."
  [".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5" ".root" ".rmap"])

(defn- cleanup-persistent-files!
  "Remove all domain files for a given base-path."
  [base-path]
  #?(:cljs (let [fs (js/require "fs")]
             (doseq [ext domain-extensions]
               (let [p (str base-path ext)]
                 (try (.unlinkSync fs p) (catch :default _)))))
     :clj  (doseq [ext domain-extensions]
             (let [f (io/file (str base-path ext))]
               (when (.exists f) (.delete f))))))

(defn- with-eve-atom
  "Create an Eve atom (mmap or in-memory), seed it, run (f atom), close & cleanup.
   mode is :mmap or :in-memory."
  [mode label seed-fn f]
  (if (= mode :mmap)
    ;; mmap-backed persistent atom
    (let [path (bench-path label)
          _    (cleanup-persistent-files! path)
          a    (e/atom {:id (keyword "eve.columnar-bench" label)
                        :persistent path}
                       nil)]
      (reset! a (seed-fn))
      (try
        (f a)
        (finally
          (e/close! a)
          (cleanup-persistent-files! path))))
    ;; in-memory atom (SAB on CLJS, heap on JVM)
    (let [a (e/atom {:id (keyword "eve.columnar-bench" (str label "-mem"))} nil)]
      (reset! a (seed-fn))
      (try
        (f a)
        (finally
          (e/close! a))))))

;;=============================================================================
;; Data generation
;;=============================================================================

(defn- random-doubles [n scale]
  (vec (repeatedly n #(* (rand) scale))))

(defn- random-ints [n max-val]
  (vec (repeatedly n #(int (* (rand) max-val)))))

;;=============================================================================
;; Results collection
;;=============================================================================

(def ^:private results (clojure.core/atom []))

(defn- record! [category n mode eve-result stock-result]
  (let [speedup (if (pos? (:mean-ms eve-result))
                  (/ (:mean-ms stock-result) (:mean-ms eve-result))
                  ##Inf)]
    (swap! results conj
      {:category category
       :n n
       :mode mode
       :platform platform
       :eve-ms (:mean-ms eve-result)
       :stock-ms (:mean-ms stock-result)
       :speedup speedup
       :eve-min (:min-ms eve-result)
       :eve-max (:max-ms eve-result)
       :stock-min (:min-ms stock-result)
       :stock-max (:max-ms stock-result)})
    ;; Print as we go
    (println (str "  " category " [" (name mode) "] (n=" n ")"))
    (println (str "    Eve:   " (fmt1 (:mean-ms eve-result)) "ms"
                  "  [" (:min-ms eve-result) "-" (:max-ms eve-result) "]"))
    (println (str "    Stock: " (fmt1 (:mean-ms stock-result)) "ms"
                  "  [" (:min-ms stock-result) "-" (:max-ms stock-result) "]"))
    (println (str "    Eve/Stock: " (fmt2 (double speedup)) "x"))
    (println)))

;;=============================================================================
;; 1. COLUMN ARITHMETIC — element-wise math pipeline
;;    revenue = price * qty, margin = revenue - cost, total = sum(margin)
;;=============================================================================

(defn- bench-column-arithmetic! [n mode]
  (let [prices (random-doubles n 100.0)
        qtys   (random-doubles n 50.0)
        costs  (random-doubles n 2000.0)
        stock-a (clojure.core/atom {:price prices :qty qtys :cost costs})]
    (record! "Column Arithmetic" n mode
      (with-eve-atom mode (str "col-arith-" n)
        (fn [] {:price (arr/eve-array :float64 prices)
                :qty   (arr/eve-array :float64 qtys)
                :cost  (arr/eve-array :float64 costs)})
        (fn [eve-a]
          (bench
            #(swap! eve-a (fn [s]
               (let [revenue (func/mul (:price s) (:qty s))
                     margin  (func/sub revenue (:cost s))]
                 (assoc s :result (func/sum margin))))))))
      (bench
        #(swap! stock-a (fn [s]
           (let [p (:price s) q (:qty s) c (:cost s)
                 margin (mapv (fn [pi qi ci] (- (* pi qi) ci)) p q c)]
             (assoc s :result (reduce + 0.0 margin)))))))))

;;=============================================================================
;; 2. FILTER + AGGREGATE — filter rows, then compute multiple stats
;;    filter(price > 50) → sum + mean + min + max on filtered
;;=============================================================================

(defn- bench-filter-aggregate! [n mode]
  (let [prices (random-doubles n 100.0)
        gt50   (fn [x] (> x 50.0))
        stock-a (clojure.core/atom {:price prices})]
    (record! "Filter + Aggregate" n mode
      (with-eve-atom mode (str "filt-agg-" n)
        (fn [] {:price (arr/eve-array :float64 prices)})
        (fn [eve-a]
          (bench
            #(swap! eve-a (fn [s]
               (let [c    (:price s)
                     idx  (argops/argfilter c gt50)
                     filt (argops/take-indices c idx)]
                 (assoc s :result
                   {:sum  (func/sum filt)
                    :mean (func/mean filt)
                    :min  (func/min-val filt)
                    :max  (func/max-val filt)})))))))
      (bench
        #(swap! stock-a (fn [s]
           (let [c    (:price s)
                 filt (filterv gt50 c)]
             (assoc s :result
               {:sum  (reduce + 0.0 filt)
                :mean (/ (reduce + 0.0 filt) (count filt))
                :min  (reduce min filt)
                :max  (reduce max filt)}))))))))

;;=============================================================================
;; 3. SORT + TOP-N — argsort, take top 100, aggregate
;;    sort(price desc) → top 100 → mean
;;=============================================================================

(defn- bench-sort-topn! [n mode]
  (let [prices (random-doubles n 1000.0)
        stock-a (clojure.core/atom {:price prices})]
    (record! "Sort + Top-N" n mode
      (with-eve-atom mode (str "sort-topn-" n)
        (fn [] {:price (arr/eve-array :float64 prices)})
        (fn [eve-a]
          (bench
            #(swap! eve-a (fn [s]
               (let [c   (:price s)
                     idx (argops/argsort c :desc)
                     top (argops/take-indices idx (arr/eve-array :int32 (vec (range (min 100 (count idx))))))
                     top-vals (argops/take-indices c top)]
                 (assoc s :result (func/mean top-vals))))))))
      (bench
        #(swap! stock-a (fn [s]
           (let [c (:price s)
                 sorted (vec (sort (fn [a b] (compare b a)) c))
                 top (subvec sorted 0 (min 100 (count sorted)))]
             (assoc s :result (/ (reduce + 0.0 top) (count top))))))))))

;;=============================================================================
;; 4. DATASET PIPELINE — multi-column realistic analytics
;;    build dataset → derive(revenue) → filter(revenue > 500)
;;    → sort(revenue desc) → head(200) → sum + mean
;;=============================================================================

(defn- bench-dataset-pipeline! [n mode]
  (let [prices (random-doubles n 100.0)
        qtys   (random-doubles n 20.0)
        cats   (random-ints n 10)
        ids    (vec (range n))
        gt500  (fn [x] (> x 500.0))
        stock-a (clojure.core/atom {:price prices :qty qtys :cat cats :id ids})]
    (record! "Dataset Pipeline" n mode
      (with-eve-atom mode (str "ds-pipe-" n)
        (fn [] {:price (arr/eve-array :float64 prices)
                :qty   (arr/eve-array :float64 qtys)
                :cat   (arr/eve-array :int32 cats)
                :id    (arr/eve-array :int32 ids)})
        (fn [eve-a]
          (bench
            #(swap! eve-a (fn [s]
               (let [ds1  (ds/dataset {:price (:price s) :qty (:qty s)
                                       :cat (:cat s) :id (:id s)})
                     rev  (func/mul (:price s) (:qty s))
                     ds2  (ds/add-column ds1 :revenue rev)
                     ds3  (ds/filter-rows ds2 :revenue gt500)
                     ds4  (ds/sort-by-column ds3 :revenue :desc)
                     take-n (min 200 (ds/row-count ds4))
                     ds5  (ds/head ds4 take-n)]
                 (assoc s :result
                   {:rev-sum    (func/sum (ds/column ds5 :revenue))
                    :price-mean (func/mean (ds/column ds5 :price))})))))))
      (bench
        #(swap! stock-a (fn [s]
           (let [p (:price s) q (:qty s)
                 rev (mapv * p q)
                 keep-idx (vec (keep-indexed (fn [i v] (when (gt500 v) i)) rev))
                 filt-p   (mapv (fn [i] (nth p i)) keep-idx)
                 filt-rev (mapv (fn [i] (nth rev i)) keep-idx)
                 sort-idx (vec (map first
                               (sort-by (fn [[_ v]] (- v))
                                 (map-indexed vector filt-rev))))
                 sorted-p   (mapv (fn [i] (nth filt-p i)) sort-idx)
                 sorted-rev (mapv (fn [i] (nth filt-rev i)) sort-idx)
                 take-n (min 200 (count sorted-rev))
                 top-rev (subvec sorted-rev 0 take-n)
                 top-p   (subvec sorted-p 0 take-n)]
             (assoc s :result
               {:rev-sum    (reduce + 0.0 top-rev)
                :price-mean (/ (reduce + 0.0 top-p) (count top-p))}))))))))

;;=============================================================================
;; 5. TENSOR TRANSFORM — emap → transpose → flatten → reduce
;;    "Scale a matrix, transpose, sum all elements"
;;=============================================================================

(defn- sqrt-int [n]
  (int #?(:cljs (js/Math.sqrt n)
          :clj  (Math/sqrt (double n)))))

(defn- bench-tensor-pipeline! [n mode]
  (let [side  (sqrt-int n)
        total (* side side)
        data  (random-doubles total 100.0)
        double-fn (fn [x] (* x 2.0))
        stock-a (clojure.core/atom {:t (vec (map vec (partition side data)))})]
    (record! "Tensor Pipeline" total mode
      (with-eve-atom mode (str "tensor-" total)
        (fn [] {:data (arr/eve-array :float64 data) :side side})
        (fn [eve-a]
          (bench
            #(swap! eve-a (fn [s]
               (let [t  (tensor/from-array (:data s) [(:side s) (:side s)])
                     t2 (tensor/emap double-fn t)
                     t3 (tensor/transpose t2)
                     flat (tensor/to-array t3)]
                 (assoc s :result (tensor/ereduce + 0.0
                                    (tensor/from-array flat [(count flat)])))))))))
      (bench
        #(swap! stock-a (fn [s]
           (let [t  (:t s)
                 t2 (mapv (fn [row] (mapv double-fn row)) t)
                 t3 (apply mapv vector t2)
                 flat (vec (apply concat t3))]
             (assoc s :result (reduce + 0.0 flat)))))))))

;;=============================================================================
;; Summary table
;;=============================================================================

(defn- pad-right [s n]
  (let [s (str s)]
    (if (>= (count s) n) s
      (str s (apply str (repeat (- n (count s)) " "))))))

(defn- pad-left [s n]
  (let [s (str s)]
    (if (>= (count s) n) s
      (str (apply str (repeat (- n (count s)) " ")) s))))

(defn- format-n [n]
  (cond
    (>= n 1000000) (str (quot n 1000000) "M")
    (>= n 1000) (str (quot n 1000) "K")
    :else (str n)))

(defn- print-summary-table [mode-kw]
  (let [rs (filterv #(= (:mode %) mode-kw) @results)
        mode-label (name mode-kw)
        hdr (str "| " (pad-right "Benchmark" 22)
                 " | " (pad-left "N" 5)
                 " | " (pad-left "Eve (ms)" 10)
                 " | " (pad-left "Stock (ms)" 10)
                 " | " (pad-left "Speedup" 8) " |")
        sep (str "|" (apply str (repeat 24 "-"))
                 "|" (apply str (repeat 7 "-"))
                 "|" (apply str (repeat 12 "-"))
                 "|" (apply str (repeat 12 "-"))
                 "|" (apply str (repeat 10 "-")) "|")]
    (println)
    (println (str "## Eve Columnar Benchmark Results (" platform ", " mode-label ")"))
    (println)
    (println hdr)
    (println sep)
    (doseq [{:keys [category n eve-ms stock-ms speedup]} rs]
      (println (str "| " (pad-right category 22)
                   " | " (pad-left (format-n n) 5)
                   " | " (pad-left (fmt1 eve-ms) 10)
                   " | " (pad-left (fmt1 stock-ms) 10)
                   " | " (pad-left (str (fmt2 speedup) "x") 8) " |")))
    (println)))

;;=============================================================================
;; Runner — runs a given tier at a given mode
;;=============================================================================

(defn- safe-bench!
  "Run a single benchmark fn, catching and reporting errors."
  [bench-fn n mode]
  (try
    (bench-fn n mode)
    (catch #?(:cljs :default :clj Exception) e
      (println (str "  [SKIP] " #?(:cljs (.-message e) :clj (.getMessage e))))
      (println))))

(defn- run-tier! [n mode]
  (safe-bench! bench-column-arithmetic! n mode)
  (safe-bench! bench-filter-aggregate! n mode)
  (safe-bench! bench-sort-topn! n mode)
  ;; Dataset Pipeline and Tensor Pipeline require nested Eve types in swap!
  ;; which currently have serialization issues with corrupted EveArray counts
  ;; after SAB/mmap round-trip in repeated swap! iterations.
  ;; Run them only in standalone (non-atom) mode when that's supported.
  (safe-bench! bench-dataset-pipeline! n mode)
  (safe-bench! bench-tensor-pipeline! n mode))

(defn- safe-run-tier!
  "Run a tier, catching and reporting any unexpected errors."
  [n mode]
  (try
    (run-tier! n mode)
    (catch #?(:cljs :default :clj Exception) e
      (println (str "  [ERROR] Tier n=" n " mode=" (name mode) " failed: "
                    #?(:cljs (.-message e) :clj (.getMessage e))))
      (println))))

(defn run-all! []
  (reset! results [])
  (println "==============================================================")
  (println (str "  Eve Columnar Benchmarks — " platform))
  (println "  Each swap! does a multi-step pipeline of real work")
  (println "  Modes: in-memory (SAB/heap) + mmap (persistent)")
  (println "  7 timed runs (trimmed mean of middle 5)")
  (println "==============================================================")
  (println)

  ;; --- MMAP mode (run first — has best slab isolation) ---
  (println "=== MMAP (persistent) ===")
  (println)
  (println "--- 10K ---")
  (safe-run-tier! 10000 :mmap)
  (println "--- 100K ---")
  (safe-run-tier! 100000 :mmap)

  (print-summary-table :mmap)

  ;; --- In-memory mode (SAB-backed on CLJS, heap on JVM) ---
  (println "=== IN-MEMORY (SAB/heap) ===")
  (println)
  (println "--- 10K ---")
  (safe-run-tier! 10000 :in-memory)
  (println "--- 100K ---")
  (safe-run-tier! 100000 :in-memory)

  (print-summary-table :in-memory)

  (println "==============================================================")
  (println "  Benchmarks complete.")
  (println "=============================================================="))

;; JVM entry point
#?(:clj
   (defn -main [& _args]
     (run-all!)))
