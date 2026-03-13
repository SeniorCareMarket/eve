(ns eve.bench-test
  "Cross-generation benchmark: eve1, eve2, eve3 on CLJS (Node.js).
   Measures build, lookup, update, reduce, and iteration operations
   across all three generations of Eve data structures."
  (:require
   [cljs.test :refer-macros [deftest testing is]]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.alloc :as alloc]

   ;; eve1
   [eve.vec :as v1]
   [eve.map :as m1]
   [eve.set :as s1]
   [eve.list :as l1]

   ;; eve2
   [eve2.vec :as v2]
   [eve2.map :as m2]
   [eve2.set :as s2]
   [eve2.list :as l2]

   ;; eve3
   [eve3.alloc :as eve3-alloc]
   [eve3.vec :as v3]
   [eve3.map :as m3]
   [eve3.set :as s3]
   [eve3.list :as l3]))

;;-----------------------------------------------------------------------------
;; Timing
;;-----------------------------------------------------------------------------

(defn- now-ms [] (.now js/performance))

(def ^:private results (atom []))

(defn- bench [gen op label f]
  (let [;; Warm up
        _ (f)
        ;; Run 3 iterations
        times (mapv (fn [_]
                      (let [t0 (now-ms)
                            _ (f)
                            t1 (now-ms)]
                        (- t1 t0)))
                    (range 3))
        median (nth (sort times) 1)]
    (swap! results conj {:gen gen :op op :label label :median-ms median :times times})
    median))

(defn- with-parent-atom [f]
  (let [prev d/*parent-atom*]
    (set! d/*parent-atom* true)
    (try (f)
         (finally (set! d/*parent-atom* prev)))))

(def ^:private sio eve3-alloc/cljs-sio)

;;-----------------------------------------------------------------------------
;; Vec benchmarks
;;-----------------------------------------------------------------------------

(def ^:private N 5000)

(deftest bench-vec-test
  (with-parent-atom
    (fn []
      (testing "Vector benchmarks"
        ;; Build
        (bench "eve1" "vec-build" (str "build " N)
          #(v1/sab-vec (range N)))
        (bench "eve2" "vec-build" (str "build " N)
          #(v2/eve2-vec (range N)))
        (bench "eve3" "vec-build" (str "build " N)
          #(v3/eve3-vec sio (range N)))

        ;; Random lookup
        (let [v1v (v1/sab-vec (range N))
              v2v (v2/eve2-vec (range N))
              v3v (v3/eve3-vec sio (range N))
              indices (vec (repeatedly 10000 #(rand-int N)))]
          (bench "eve1" "vec-nth" (str "10k nth on " N)
            #(doseq [i indices] (nth v1v i)))
          (bench "eve2" "vec-nth" (str "10k nth on " N)
            #(doseq [i indices] (nth v2v i)))
          (bench "eve3" "vec-nth" (str "10k nth on " N)
            #(doseq [i indices] (nth v3v i))))

        ;; Assoc
        (let [v1v (v1/sab-vec (range N))
              v2v (v2/eve2-vec (range N))
              v3v (v3/eve3-vec sio (range N))
              indices (vec (repeatedly 1000 #(rand-int N)))]
          (bench "eve1" "vec-assoc" (str "1k assoc on " N)
            #(reduce (fn [v i] (assoc v i -1)) v1v indices))
          (bench "eve2" "vec-assoc" (str "1k assoc on " N)
            #(reduce (fn [v i] (assoc v i -1)) v2v indices))
          (bench "eve3" "vec-assoc" (str "1k assoc on " N)
            #(reduce (fn [v i] (assoc v i -1)) v3v indices)))

        ;; Reduce
        (let [v1v (v1/sab-vec (range N))
              v2v (v2/eve2-vec (range N))
              v3v (v3/eve3-vec sio (range N))]
          (bench "eve1" "vec-reduce" (str "reduce " N)
            #(reduce + 0 v1v))
          (bench "eve2" "vec-reduce" (str "reduce " N)
            #(reduce + 0 v2v))
          (bench "eve3" "vec-reduce" (str "reduce " N)
            #(reduce + 0 v3v))))

      (is true))))

;;-----------------------------------------------------------------------------
;; Map benchmarks
;;-----------------------------------------------------------------------------

(deftest bench-map-test
  (with-parent-atom
    (fn []
      (testing "Map benchmarks"
        ;; Build
        (bench "eve1" "map-build" (str "build " N)
          #(reduce (fn [m i] (assoc m i (* i 10)))
                   (m1/empty-hash-map)
                   (range N)))
        (bench "eve2" "map-build" (str "build " N)
          #(reduce (fn [m i] (assoc m i (* i 10)))
                   (m2/empty-hash-map)
                   (range N)))
        (bench "eve3" "map-build" (str "build " N)
          #(reduce (fn [m i] (assoc m i (* i 10)))
                   (m3/empty-hash-map sio)
                   (range N)))

        ;; Lookup
        (let [m1m (reduce (fn [m i] (assoc m i (* i 10)))
                          (m1/empty-hash-map)
                          (range N))
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
            #(doseq [k keys-to-look-up] (get m3m k))))

        ;; Reduce
        (let [m1m (reduce (fn [m i] (assoc m i (* i 10)))
                          (m1/empty-hash-map)
                          (range N))
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

      (is true))))

;;-----------------------------------------------------------------------------
;; Set benchmarks
;;-----------------------------------------------------------------------------

(deftest bench-set-test
  (with-parent-atom
    (fn []
      (testing "Set benchmarks"
        ;; Build
        (bench "eve1" "set-build" (str "build " N)
          #(into (s1/empty-hash-set) (range N)))
        (bench "eve2" "set-build" (str "build " N)
          #(into (s2/empty-hash-set) (range N)))
        (bench "eve3" "set-build" (str "build " N)
          #(into (s3/empty-hash-set sio) (range N)))

        ;; Lookup
        (let [s1s (into (s1/empty-hash-set) (range N))
              s2s (into (s2/empty-hash-set) (range N))
              s3s (into (s3/empty-hash-set sio) (range N))
              vals-to-check (vec (repeatedly 10000 #(rand-int N)))]
          (bench "eve1" "set-contains" (str "10k contains? on " N)
            #(doseq [v vals-to-check] (contains? s1s v)))
          (bench "eve2" "set-contains" (str "10k contains? on " N)
            #(doseq [v vals-to-check] (contains? s2s v)))
          (bench "eve3" "set-contains" (str "10k contains? on " N)
            #(doseq [v vals-to-check] (contains? s3s v))))

        ;; Reduce
        (let [s1s (into (s1/empty-hash-set) (range N))
              s2s (into (s2/empty-hash-set) (range N))
              s3s (into (s3/empty-hash-set sio) (range N))]
          (bench "eve1" "set-reduce" (str "reduce " N)
            #(reduce + 0 s1s))
          (bench "eve2" "set-reduce" (str "reduce " N)
            #(reduce + 0 s2s))
          (bench "eve3" "set-reduce" (str "reduce " N)
            #(reduce + 0 s3s))))

      (is true))))

;;-----------------------------------------------------------------------------
;; List benchmarks
;;-----------------------------------------------------------------------------

(deftest bench-list-test
  (with-parent-atom
    (fn []
      (testing "List benchmarks"
        ;; Build via conj
        (bench "eve1" "list-conj" (str "conj " N)
          #(reduce conj (l1/empty-sab-list) (range N)))
        (bench "eve2" "list-conj" (str "conj " N)
          #(reduce conj (l2/empty-list) (range N)))
        (bench "eve3" "list-conj" (str "conj " N)
          #(reduce conj (l3/empty-list sio) (range N)))

        ;; Pop
        (let [l1l (reduce conj (l1/empty-sab-list) (range N))
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
               (when (pos? c) (recur (pop l) (dec c)))))))

      (is true))))

;;-----------------------------------------------------------------------------
;; Summary printer
;;-----------------------------------------------------------------------------

(deftest bench-summary-test
  (testing "Benchmark Summary"
    (println "\n╔════════════════════════════════════════════════════════════════╗")
    (println "║            EVE Cross-Generation Benchmark (CLJS/Node)        ║")
    (println (str "║            N=" N "                                            ║"))
    (println "╠═══════════════════╦═══════════╦═══════════╦═══════════╦══════╣")
    (println "║ Operation         ║   eve1    ║   eve2    ║   eve3    ║ best ║")
    (println "╠═══════════════════╬═══════════╬═══════════╬═══════════╬══════╣")

    (let [by-op (group-by :op @results)]
      (doseq [op ["vec-build" "vec-nth" "vec-assoc" "vec-reduce"
                   "map-build" "map-get" "map-reduce"
                   "set-build" "set-contains" "set-reduce"
                   "list-conj" "list-pop"]]
        (let [entries (get by-op op)
              get-ms (fn [gen] (:median-ms (first (filter #(= gen (:gen %)) entries))))
              e1 (get-ms "eve1")
              e2 (get-ms "eve2")
              e3 (get-ms "eve3")
              best (cond
                     (and e1 e2 e3 (<= e1 e2 e3)) "eve1"
                     (and e1 e2 e3 (<= e1 e3 e2)) "eve1"
                     (and e1 e2 e3 (<= e2 e1 e3)) "eve2"
                     (and e1 e2 e3 (<= e2 e3 e1)) "eve2"
                     (and e1 e2 e3 (<= e3 e1 e2)) "eve3"
                     (and e1 e2 e3 (<= e3 e2 e1)) "eve3"
                     :else "?")
              fmt (fn [ms] (if ms (str (.toFixed ms 1) "ms") "  N/A  "))]
          (println (str "║ " (subs (str op "                   ") 0 17)
                        " ║ " (subs (str "   " (fmt e1) "   ") 0 9)
                        " ║ " (subs (str "   " (fmt e2) "   ") 0 9)
                        " ║ " (subs (str "   " (fmt e3) "   ") 0 9)
                        " ║ " (subs (str best "    ") 0 4) " ║")))))

    (println "╚═══════════════════╩═══════════╩═══════════╩═══════════╩══════╝")
    (is true)))
