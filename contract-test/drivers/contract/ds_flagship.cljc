(ns contract.ds-flagship
  "Flagship scenario for the external-library contract test (Phase 2):
   ONE DataScript database living in ONE shared disk Eve atom,
   transacted IN PARALLEL by multiple host processes (JVM + Babashka).

   Modes (argv after the mode word):
     init     <dir>            create the disk domain + the atom holding
                               an empty DB; transact the base set inside
                               a swap; print datom count + seal
     worker   <dir> <w>        round 1: transact worker w's disjoint
                               entity batch (10 swap!s of 10 entities)
     worker2  <dir> <w>        round 2: every worker transacts a DISTINCT
                               attribute onto the SAME 20 shared entities
     readback <dir>            join fresh; deref; print the datom count,
                               the fixed query battery, the seal, the DB
                               hash, and the locally computed expected
                               datom count for the identical workload

   Workload (identical for every host; all adds of distinct [e a] pairs,
   so the final datom count is order-independent):
     base:    entities 1..10, {:name \"base-<i>\", :age 20+i}   20 datoms
     round 1: worker w in {0,1,2,3}: entities 1000+w*1000+i for
              i in 0..99, {:name \"w<w>-<i>\", :age i}        200 datoms each
     round 2: entities 5001..5020; worker w adds {:mark/w<w> true}
                                                                20 datoms each
   Expected final: 20 + 4*200 + 4*20 = 900 datoms, 830 entities."
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [clojure.string]
   [datascript.core :as d]))

(defmacro attempt
  "Run body; on any throw, answer the string ':throws <err>' (reader-
   conditional catch class, the contend.cljc convention)."
  [& body]
  `(try ~@body
     (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t#
       (str :throws " " t#))))

(def ^:private atom-name "contract/ds-flagship")
(def ^:private workers 4)
(def ^:private per-worker 100)

(defn- base-tx []
  (mapv (fn [i] {:db/id i :name (str "base-" i) :age (+ 20 i)})
        (range 1 11)))

(defn- worker-tx [w i]
  {:db/id (+ 1000 (* w 1000) i) :name (str "w" w "-" i) :age i})

(defn- worker-batch [w b]
  (mapv (fn [i] (worker-tx w i)) (range (* b 10) (* (inc b) 10))))

(defn- mark-tx [w]
  (mapv (fn [e] {:db/id e (keyword (str "mark/w" w)) true})
        (range 5001 5021)))

(defn- open-atom [dir]
  (let [domain (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! domain atom-name nil)]
    ;; the DB is constructed inside a swap so it allocates in the atom's
    ;; own domain (Eve's domain-construction contract, PORTING-DS D1)
    (when (nil? @a)
      (swap! a (fn [_] (d/empty-db))))
    a))

(defn- datom-count [db]
  (attempt (e/count (d/datoms db :eavt))))

(defn- expected-local []
  ;; the identical workload built in-process, no atom: the ground-truth
  ;; datom count the shared DB must reach
  (let [db0 (d/db-with (d/empty-db) (base-tx))
        db1 (reduce (fn [db w]
                      (reduce (fn [db b] (d/db-with db (worker-batch w b)))
                              db (range 10)))
                    db0 (range workers))
        db2 (reduce (fn [db w] (d/db-with db (mark-tx w))) db1 (range workers))]
    (e/count (d/datoms db2 :eavt))))

(defn -main [& [mode dir w]]
  (try
    (case mode
      "init"
      (let [a (open-atom dir)]
        (swap! a (fn [db] (d/db-with db (base-tx))))
        (println (pr-str {:mode :init
                          :datoms (datom-count @a)
                          :seal (atom/seal a)})))

      "worker"
      (let [a (open-atom dir)
            w (parse-long w)]
        (doseq [b (range 10)]
          (swap! a (fn [db] (d/db-with db (worker-batch w b)))))
        (println (pr-str {:mode :worker :w w :swaps 10
                          :datoms-seen (datom-count @a)})))

      "worker2"
      (let [a (open-atom dir)
            w (parse-long w)]
        (swap! a (fn [db] (d/db-with db (mark-tx w))))
        (println (pr-str {:mode :worker2 :w w :swaps 1
                          :datoms-seen (datom-count @a)})))

      "readback"
      (let [a (open-atom dir)
            db @a
            names (attempt (d/q '[:find [?n ...] :where [?e :name ?n]] db))
            w2-names (attempt (when (sequential? names)
                                (sort (filter (fn [n] (and (string? n)
                                                           (clojure.string/starts-with? n "w2-")))
                                              names))))]
        (println (pr-str {:mode :readback
                          :datoms (datom-count db)
                          :host-count (attempt (count (d/datoms db :eavt)))
                          :expected-local (attempt (expected-local))
                          :q-age34 (attempt (count (d/q '[:find [?e ...]
                                                          :where [?e :age 34]] db)))
                          :q-base-names (attempt (mapv (fn [e] (:name (d/entity db e)))
                                                       (range 1 11)))
                          :q-names-count (attempt (count names))
                          :q-w2-count (attempt (count w2-names))
                          :q-w2-first (attempt (first w2-names))
                          :q-w2-last (attempt (last w2-names))
                          :q-mark-w0 (attempt (count (d/q '[:find [?e ...]
                                                            :where [?e :mark/w0 true]] db)))
                          :q-mark-w3 (attempt (count (d/q '[:find [?e ...]
                                                            :where [?e :mark/w3 true]] db)))
                          :seal (atom/seal a)
                          :db-hash (attempt (hash db))}))))
    (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t
      (println (pr-str {:mode (keyword (or mode "none")) :fatal (str t)})))))
