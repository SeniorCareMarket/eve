(ns eve.perf-audit
  "Micro-profiler: isolate time spent in each layer of columnar operations.
   Tests individual operations WITHOUT atom overhead to find pure compute bottlenecks."
  (:require
   [eve.array :as arr]
   [eve.dataset.functional :as func]
   [eve.dataset.argops :as argops]))

;;=============================================================================
;; Timing infrastructure
;;=============================================================================

(defn- now-us []
  #?(:cljs (* (js/Date.now) 1000)  ;; µs from ms
     :clj  (/ (System/nanoTime) 1000.0)))

(defn- bench-us
  "Run f, return elapsed microseconds. Warmup 3x, time 5x, return median."
  [f]
  (dotimes [_ 3] (f))
  (let [times (loop [i 0 acc []]
                (if (>= i 5)
                  acc
                  (let [t0 (now-us)
                        _ (f)
                        elapsed (- (now-us) t0)]
                    (recur (inc i) (conj acc elapsed)))))
        sorted (sort times)]
    (nth sorted 2)))  ;; median

(defn- fmt [us]
  #?(:cljs (.toFixed (js/Number. (/ us 1000.0)) 2)
     :clj  (format "%.2f" (/ us 1000.0))))

(defn- report [label us]
  (println (str "  " label ": " (fmt us) " ms")))

;;=============================================================================
;; Data generation (no slab allocation — pure Clojure vectors)
;;=============================================================================

(defn- gen-doubles [n]
  (vec (repeatedly n #(* (rand) 100.0))))

(defn- gen-ints [n max-val]
  (vec (repeatedly n #(int (* (rand) max-val)))))

;;=============================================================================
;; Layer 1: Raw typed array alloc + fill — baseline cost
;;=============================================================================

(defn audit-alloc! [n]
  (println (str "\n=== ALLOC COST (n=" n ") ==="))

  ;; eve-array from count (zero-fill)
  (report "eve-array :float64 zero-fill"
    (bench-us #(arr/eve-array :float64 n)))

  ;; eve-array from collection
  (let [v (gen-doubles n)]
    (report "eve-array :float64 from-vec"
      (bench-us #(arr/eve-array :float64 v))))

  ;; eve-array :int32 from collection
  (let [v (gen-ints n 10000)]
    (report "eve-array :int32 from-vec"
      (bench-us #(arr/eve-array :int32 v))))

  ;; get-typed-view cost (should be ~0)
  #?(:cljs
     (let [a (arr/eve-array :float64 (gen-doubles n))]
       (report "get-typed-view"
         (bench-us #(arr/get-typed-view a))))
     :clj nil)

  ;; Compare: JS Float64Array alloc
  #?(:cljs
     (do
       (report "JS Float64Array(n)"
         (bench-us #(js/Float64Array. n)))
       (let [src (js/Float64Array. n)]
         (report "JS Float64Array.from(src)"
           (bench-us #(js/Float64Array.from src)))))
     :clj
     (do
       (report "JVM double-array(n)"
         (bench-us #(double-array n)))
       (let [v (gen-doubles n)]
         (report "JVM double-array + fill"
           (bench-us #(let [^doubles a (double-array n)]
                        (dotimes [i n] (aset a i (double (nth v i))))
                        a)))))))

;;=============================================================================
;; Layer 2: Tight-loop compute — aget/aset vs nth
;;=============================================================================

(defn audit-loop! [n]
  (println (str "\n=== TIGHT LOOP COST (n=" n ") ==="))

  ;; Sum via get-typed-view + aget (current CLJS fast path)
  (let [a (arr/eve-array :float64 (gen-doubles n))]
    (report "sum via func/sum"
      (bench-us #(func/sum a))))

  ;; Sum via reduce (protocol dispatch per element)
  (let [a (arr/eve-array :float64 (gen-doubles n))]
    (report "sum via clojure reduce"
      (bench-us #(reduce + 0.0 a))))

  ;; Sum via raw JS/JVM typed array (no Eve overhead)
  #?(:cljs
     (let [tv (js/Float64Array. n)]
       (dotimes [i n] (clojure.core/aset tv i (* (rand) 100.0)))
       (report "sum via raw JS Float64Array aget loop"
         (bench-us #(loop [i 0 acc 0.0]
                      (if (< i n)
                        (recur (inc i) (+ acc (clojure.core/aget tv i)))
                        acc)))))
     :clj
     (let [^doubles da (double-array n)]
       (dotimes [i n] (aset da i (* (rand) 100.0)))
       (report "sum via raw double[] aget loop"
         (bench-us #(loop [i (int 0) acc 0.0]
                      (if (< i n)
                        (recur (inc i) (+ acc (aget da i)))
                        acc))))))

  ;; Compare: stock vector sum
  (let [v (gen-doubles n)]
    (report "sum via (reduce + 0.0 clj-vec)"
      (bench-us #(reduce + 0.0 v)))))

;;=============================================================================
;; Layer 3: Binary ops — the full pipeline
;;=============================================================================

(defn audit-binops! [n]
  (println (str "\n=== BINARY OP COST (n=" n ") ==="))

  (let [a (arr/eve-array :float64 (gen-doubles n))
        b (arr/eve-array :float64 (gen-doubles n))]

    (report "func/mul(a,b)"
      (bench-us #(func/mul a b)))

    (report "func/add(a,b)"
      (bench-us #(func/add a b)))

    (report "func/sub(a,b)"
      (bench-us #(func/sub a b))))

  ;; Stock comparison: mapv * on two vectors
  (let [va (gen-doubles n)
        vb (gen-doubles n)]
    (report "stock mapv * two vecs"
      (bench-us #(mapv * va vb)))))

;;=============================================================================
;; Layer 4: Argops
;;=============================================================================

(defn audit-argops! [n]
  (println (str "\n=== ARGOPS COST (n=" n ") ==="))

  (let [a (arr/eve-array :float64 (gen-doubles n))
        pred (fn [x] (> x 50.0))]

    (report "argfilter(a, >50)"
      (bench-us #(argops/argfilter a pred)))

    (report "argsort(a, :desc)"
      (bench-us #(argops/argsort a :desc)))

    (report "take-indices(a, small-idx)"
      (let [idx (arr/eve-array :int32 (vec (range (min 100 n))))]
        (bench-us #(argops/take-indices a idx)))))

  ;; Stock comparison
  (let [v (gen-doubles n)
        pred (fn [x] (> x 50.0))]
    (report "stock filterv pred"
      (bench-us #(filterv pred v)))
    (report "stock sort desc"
      (bench-us #(vec (sort (fn [a b] (compare b a)) v))))))

;;=============================================================================
;; Layer 5: Column Arithmetic pipeline (the benchmark workload)
;;=============================================================================

(defn audit-pipeline! [n]
  (println (str "\n=== FULL PIPELINE (n=" n ") ==="))

  (let [prices (arr/eve-array :float64 (gen-doubles n))
        qtys   (arr/eve-array :float64 (gen-doubles n))
        costs  (arr/eve-array :float64 (gen-doubles n))]

    (report "revenue=mul(p,q) + margin=sub(rev,c) + sum(margin)"
      (bench-us #(let [revenue (func/mul prices qtys)
                       margin  (func/sub revenue costs)]
                   (func/sum margin)))))

  ;; Stock comparison
  (let [p (gen-doubles n)
        q (gen-doubles n)
        c (gen-doubles n)]
    (report "stock: mapv pipeline + reduce"
      (bench-us #(let [margin (mapv (fn [pi qi ci] (- (* pi qi) ci)) p q c)]
                   (reduce + 0.0 margin))))))

;;=============================================================================
;; Runner
;;=============================================================================

(defn run-audit! []
  (println "==============================================================")
  (println (str "  Performance Audit — "
               #?(:cljs "Node.js (CLJS)" :clj "JVM (Clojure)")))
  (println "  Isolating per-layer costs, no atom overhead")
  (println "==============================================================")

  (doseq [n [1000 10000 100000]]
    (audit-alloc! n)
    (audit-loop! n)
    (audit-binops! n)
    (audit-argops! n)
    (audit-pipeline! n))

  (println "\n==============================================================")
  (println "  Audit complete.")
  (println "=============================================================="))

#?(:clj
   (defn -main [& _]
     (run-audit!)))
