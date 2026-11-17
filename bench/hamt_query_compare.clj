;; Requires eve.db.ds on the classpath; see docs/one-eve-work/e5.md.
(require '[eve.db.ds.core :as d]
         '[eve.db.ds.query :as q1]
         '[eve.db.ds.query-v3 :as q3])

(def query '[:find ?n :where [?e :name ?n]])
(def data (d/db-with (d/empty-db)
                     (mapv (fn [i] {:db/id (inc i) :name (str "N" i)})
                           (range 100))))
(def expected (into #{} (map (fn [i] [(str "N" i)])) (range 100)))

(defn result-set [r] (into #{} (map vec) r))

(def r1 (result-set (apply q1/q query [data])))
(def r3 (result-set (apply q3/q query [data])))
(println "Q-RESULT-COUNT" (count r1))
(println "V3-RESULT-COUNT" (count r3))
(println "RESULTS-IDENTICAL" (= r1 r3))
(println "RESULTS-CORRECT" (= expected r1 r3))
(when-not (= expected r1 r3)
  (println "Q-ONLY" (pr-str (clojure.set/difference r1 r3)))
  (println "V3-ONLY" (pr-str (clojure.set/difference r3 r1)))
  (throw (ex-info "query results differ or are incorrect"
                  {:q r1 :v3 r3 :expected expected})))

(defn time-block [label f]
  (dotimes [_ 200] (f))
  (let [samples (vec (for [sample (range 5)]
                       (let [t (System/nanoTime)]
                         (dotimes [_ 100] (f))
                         (let [ms (/ (- (System/nanoTime) t) 1e6)]
                           (println (str label "-MS-100") sample ms)
                           ms))))
        sorted (vec (sort samples))
        median (nth sorted 2)]
    (println (str label "-MEDIAN-MS-PER-QUERY") (/ median 100.0))
    {:samples samples :median-ms-per-query (/ median 100.0)}))

(def q-stats (time-block "Q" #(q1/q query data)))
(def v3-stats (time-block "V3" #(q3/q query data)))
(def ratio (/ (:median-ms-per-query v3-stats)
              (:median-ms-per-query q-stats)))
(println "V3-OVER-Q-RATIO" (double ratio))
(println "V3-GT-2X" (> ratio 2.0))
(println "BENCH-OK")
