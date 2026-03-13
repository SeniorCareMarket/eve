(ns eve.jvm-bench-test
  "Cross-generation benchmark: eve1, eve2, eve3 on JVM.
   Measures build, lookup, update, reduce, and iteration operations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.mem :as mem]
            ;; eve1
            [eve.map  :as m1]
            [eve.vec  :as v1]
            [eve.set  :as s1]
            [eve.list :as l1]
            ;; eve2
            [eve2.map  :as m2]
            [eve2.vec  :as v2]
            [eve2.set  :as s2]
            [eve2.list :as l2]
            ;; eve3
            [eve3.map  :as m3]
            [eve3.vec  :as v3]
            [eve3.set  :as s3]
            [eve3.list :as l3]))

(defmacro with-heap-slab [& body]
  `(let [ctx# (alloc/make-jvm-heap-slab-ctx
                {:capacities {0 (* 32 1024 1024)
                              1 (* 32 1024 1024)
                              2 (* 16 1024 1024)
                              3 (* 8 1024 1024)
                              4 (* 4 1024 1024)
                              5 (* 2 1024 1024)}})]
     (binding [alloc/*jvm-slab-ctx* ctx#]
       ~@body)))

(def ^:private N 1000)

(def ^:private results (atom []))

(defn- bench [gen op label f]
  ;; Warm up
  (f)
  ;; Run 3 iterations, take median
  (let [times (mapv (fn [_]
                      (let [t0 (System/nanoTime)
                            _ (f)
                            t1 (System/nanoTime)]
                        (/ (- t1 t0) 1e6)))
                    (range 3))
        median (nth (sort times) 1)]
    (swap! results conj {:gen gen :op op :label label :median-ms median :times times})
    median))

;;-----------------------------------------------------------------------------
;; Vec
;;-----------------------------------------------------------------------------

(deftest bench-vec-jvm
  (testing "Vector benchmarks (JVM)"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*]
        ;; Build
        (bench "eve1" "vec-build" (str "build " N)
          #(let [hdr (v1/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) (range N))]
             (v1/jvm-sabvec-from-offset sio hdr)))
        (bench "eve2" "vec-build" (str "build " N)
          #(reduce conj (v2/empty-vec) (range N)))
        (bench "eve3" "vec-build" (str "build " N)
          #(reduce conj (v3/empty-vec sio) (range N)))

        ;; Lookup
        (let [v1v (let [hdr (v1/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) (range N))]
                    (v1/jvm-sabvec-from-offset sio hdr))
              v2v (reduce conj (v2/empty-vec) (range N))
              v3v (reduce conj (v3/empty-vec sio) (range N))
              indices (vec (repeatedly 10000 #(rand-int N)))]
          (bench "eve1" "vec-nth" (str "10k nth on " N)
            #(doseq [i indices] (nth v1v i)))
          (bench "eve2" "vec-nth" (str "10k nth on " N)
            #(doseq [i indices] (nth v2v i)))
          (bench "eve3" "vec-nth" (str "10k nth on " N)
            #(doseq [i indices] (nth v3v i))))

        ;; Reduce
        (let [v1v (let [hdr (v1/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) (range N))]
                    (v1/jvm-sabvec-from-offset sio hdr))
              v2v (reduce conj (v2/empty-vec) (range N))
              v3v (reduce conj (v3/empty-vec sio) (range N))]
          (bench "eve1" "vec-reduce" (str "reduce " N)
            #(reduce + 0 v1v))
          (bench "eve2" "vec-reduce" (str "reduce " N)
            #(reduce + 0 v2v))
          (bench "eve3" "vec-reduce" (str "reduce " N)
            #(reduce + 0 v3v)))))
    (is true)))

;;-----------------------------------------------------------------------------
;; Map — each sub-benchmark gets fresh slab to avoid OOM
;;-----------------------------------------------------------------------------

(deftest bench-map-jvm
  (testing "Map benchmarks (JVM)"
    ;; Build
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src-map (into {} (map (fn [i] [i (* i 10)]) (range N)))]
        (bench "eve1" "map-build" (str "build " N)
          #(let [hdr (m1/jvm-write-map! (partial mem/value+sio->eve-bytes sio) src-map)]
             (m1/jvm-eve-hash-map-from-offset hdr)))))
    (with-heap-slab
      (bench "eve2" "map-build" (str "build " N)
        #(reduce (fn [m i] (assoc m i (* i 10)))
                 (m2/empty-hash-map)
                 (range N))))
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*]
        (bench "eve3" "map-build" (str "build " N)
          #(reduce (fn [m i] (assoc m i (* i 10)))
                   (m3/empty-hash-map sio)
                   (range N)))))

    ;; Lookup
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src-map (into {} (map (fn [i] [i (* i 10)]) (range N)))
            m1m (let [hdr (m1/jvm-write-map! (partial mem/value+sio->eve-bytes sio) src-map)]
                  (m1/jvm-eve-hash-map-from-offset hdr))
            m2m (reduce (fn [m i] (assoc m i (* i 10)))
                        (m2/empty-hash-map)
                        (range N))
            m3m (reduce (fn [m i] (assoc m i (* i 10)))
                        (m3/empty-hash-map sio)
                        (range N))
            keys-to-look-up (vec (repeatedly 10000 #(rand-int N)))]
        (bench "eve1" "map-get" (str "10k get on " N)
          #(doseq [k keys-to-look-up] (get m1m k)))
        (bench "eve2" "map-get" (str "10k get on " N)
          #(doseq [k keys-to-look-up] (get m2m k)))
        (bench "eve3" "map-get" (str "10k get on " N)
          #(doseq [k keys-to-look-up] (get m3m k)))))

    ;; Reduce
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src-map (into {} (map (fn [i] [i (* i 10)]) (range N)))
            m1m (let [hdr (m1/jvm-write-map! (partial mem/value+sio->eve-bytes sio) src-map)]
                  (m1/jvm-eve-hash-map-from-offset hdr))
            m2m (reduce (fn [m i] (assoc m i (* i 10)))
                        (m2/empty-hash-map)
                        (range N))
            m3m (reduce (fn [m i] (assoc m i (* i 10)))
                        (m3/empty-hash-map sio)
                        (range N))]
        (bench "eve1" "map-reduce" (str "reduce " N)
          #(reduce (fn [acc [k v]] (+ acc v)) 0 m1m))
        (bench "eve2" "map-reduce" (str "reduce " N)
          #(reduce (fn [acc [k v]] (+ acc v)) 0 m2m))
        (bench "eve3" "map-reduce" (str "reduce " N)
          #(reduce (fn [acc [k v]] (+ acc v)) 0 m3m))))
    (is true)))

;;-----------------------------------------------------------------------------
;; Set — each sub-benchmark gets fresh slab to avoid OOM
;;-----------------------------------------------------------------------------

(deftest bench-set-jvm
  (testing "Set benchmarks (JVM)"
    ;; Build
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*]
        (bench "eve1" "set-build" (str "build " N)
          #(let [hdr (s1/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) (range N))]
             (s1/jvm-eve-hash-set-from-offset sio hdr)))))
    (with-heap-slab
      (bench "eve2" "set-build" (str "build " N)
        #(into (s2/empty-hash-set) (range N))))
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*]
        (bench "eve3" "set-build" (str "build " N)
          #(into (s3/empty-hash-set sio) (range N)))))

    ;; Lookup
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            s1s (let [hdr (s1/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) (range N))]
                  (s1/jvm-eve-hash-set-from-offset sio hdr))
            s2s (into (s2/empty-hash-set) (range N))
            s3s (into (s3/empty-hash-set sio) (range N))
            vals-to-check (vec (repeatedly 10000 #(rand-int N)))]
        (bench "eve1" "set-contains" (str "10k contains? on " N)
          #(doseq [v vals-to-check] (contains? s1s v)))
        (bench "eve2" "set-contains" (str "10k contains? on " N)
          #(doseq [v vals-to-check] (contains? s2s v)))
        (bench "eve3" "set-contains" (str "10k contains? on " N)
          #(doseq [v vals-to-check] (contains? s3s v)))))

    ;; Reduce
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            s1s (let [hdr (s1/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) (range N))]
                  (s1/jvm-eve-hash-set-from-offset sio hdr))
            s2s (into (s2/empty-hash-set) (range N))
            s3s (into (s3/empty-hash-set sio) (range N))]
        (bench "eve1" "set-reduce" (str "reduce " N)
          #(reduce + 0 s1s))
        (bench "eve2" "set-reduce" (str "reduce " N)
          #(reduce + 0 s2s))
        (bench "eve3" "set-reduce" (str "reduce " N)
          #(reduce + 0 s3s))))
    (is true)))

;;-----------------------------------------------------------------------------
;; List
;;-----------------------------------------------------------------------------

(deftest bench-list-jvm
  (testing "List benchmarks (JVM)"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*]
        ;; Build
        (bench "eve1" "list-conj" (str "conj " N)
          #(let [hdr (l1/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) (range N))]
             (l1/jvm-sab-list-from-offset sio hdr)))
        (bench "eve2" "list-conj" (str "conj " N)
          #(reduce conj (l2/empty-list) (range N)))
        (bench "eve3" "list-conj" (str "conj " N)
          #(reduce conj (l3/empty-list sio) (range N)))

        ;; Pop
        (let [l1l (let [hdr (l1/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) (range N))]
                    (l1/jvm-sab-list-from-offset sio hdr))
              l2l (reduce conj (l2/empty-list) (range N))
              l3l (reduce conj (l3/empty-list sio) (range N))]
          (bench "eve1" "list-pop" (str "pop " N)
            #(loop [l l1l c N]
               (when (pos? c) (recur (pop l) (dec c)))))
          (bench "eve2" "list-pop" (str "pop " N)
            #(loop [l l2l c N]
               (when (pos? c) (recur (pop l) (dec c)))))
          (bench "eve3" "list-pop" (str "pop " N)
            #(loop [l l3l c N]
               (when (pos? c) (recur (pop l) (dec c))))))))
    (is true)))

;;-----------------------------------------------------------------------------
;; Summary (printed via :once fixture to ensure it runs after all tests)
;;-----------------------------------------------------------------------------

(defn- print-summary []
  (println "\n╔════════════════════════════════════════════════════════════════╗")
  (println "║            EVE Cross-Generation Benchmark (JVM)              ║")
  (println (str "║            N=" N "                                            ║"))
  (println "╠═══════════════════╦═══════════╦═══════════╦═══════════╦══════╣")
  (println "║ Operation         ║   eve1    ║   eve2    ║   eve3    ║ best ║")
  (println "╠═══════════════════╬═══════════╬═══════════╬═══════════╬══════╣")

  (let [by-op (group-by :op @results)]
    (doseq [op ["vec-build" "vec-nth" "vec-reduce"
                 "map-build" "map-get" "map-reduce"
                 "set-build" "set-contains" "set-reduce"
                 "list-conj" "list-pop"]]
      (let [entries (get by-op op)
            get-ms (fn [gen] (:median-ms (first (filter #(= gen (:gen %)) entries))))
            e1 (get-ms "eve1")
            e2 (get-ms "eve2")
            e3 (get-ms "eve3")
            all (remove nil? [e1 e2 e3])
            min-ms (when (seq all) (apply min all))
            best (cond
                   (and e1 (= e1 min-ms)) "eve1"
                   (and e2 (= e2 min-ms)) "eve2"
                   (and e3 (= e3 min-ms)) "eve3"
                   :else "?")
            fmt (fn [ms] (if ms (format "%6.1fms" ms) "  N/A  "))]
        (println (str "║ " (subs (str op "                   ") 0 17)
                      " ║ " (fmt e1)
                      " ║ " (fmt e2)
                      " ║ " (fmt e3)
                      " ║ " (subs (str best "    ") 0 4) " ║")))))

  (println "╚═══════════════════╩═══════════╩═══════════╩═══════════╩══════╝"))

(use-fixtures :once
  (fn [run-tests]
    (run-tests)
    (print-summary)))
