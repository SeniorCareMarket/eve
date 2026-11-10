(ns contract.ds-contend
  "Flagship driver: the whole DataScript database in ONE shared disk
   Eve atom, transacted in parallel by multiple host processes.

   Modes (argv after the mode word):
     init     <dir>          create the disk domain + the atom holding
                             an empty DataScript DB; print seal
     worker   <dir> <w>      join the domain; swap! this worker's
                             shared batch + private batch in, batch by
                             batch (each swap re-runs d/with on the
                             current DB value — Eve atom semantics);
                             print swaps done and local datom count
     readback <dir>          join fresh; deref; print datom count,
                             query answers, seal, DB hash

   Workload (identical for every host): shared entities 1..20
   (:name \"shared-i\", :age i) written by EVERY worker (idempotent —
   same ids, same values); private entities per worker w:
   ids 10000+w*1000+i for i in 0..149 (:name \"w<w>-<i>\",
   :age (mod i 97)). With W workers the expected final DB has
   40 + W*300 datoms and 20 + W*150 named entities."
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [datascript.core :as d]))

(defmacro attempt
  [& body]
  `(try ~@body
     (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t#
       (str :throws " " t#))))

(def ^:private atom-name "contract/ds-db")
(def ^:private schema {:name {:db/unique :db.unique/identity}})
(def ^:private shared-ids (range 1 21))
(def ^:private priv-count 150)
(def ^:private batch-size 25)

(defn- shared-tx []
  (mapv (fn [i] {:db/id i :name (str "shared-" i) :age i}) shared-ids))

(defn- priv-tx [w]
  (mapv (fn [i] {:db/id (+ 10000 (* w 1000) i)
                 :name (str "w" w "-" i)
                 :age (mod i 97)})
        (range priv-count)))

(defn- open-atom [dir]
  (let [domain (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! domain atom-name nil)]
    ;; the DB is constructed inside a swap so it allocates in the
    ;; atom's own domain (Eve's domain-construction contract)
    (when (nil? @a)
      (swap! a (fn [_] (d/empty-db schema))))
    a))

(defn- transact-batches [a tx]
  (reduce (fn [n batch]
            (swap! a (fn [db] (:db-after (d/with db batch))))
            (inc n))
          0 (partition-all batch-size tx)))

(defn -main [& [mode dir w]]
  (try
    (case mode
      "init"
      (let [a (open-atom dir)]
        (println (pr-str {:mode :init
                          :datoms (count (d/datoms @a :eavt))
                          :seal (atom/seal a)})))

      "worker"
      (let [a (open-atom dir)
            w (parse-long w)
            n1 (transact-batches a (shared-tx))
            n2 (transact-batches a (priv-tx w))]
        (println (pr-str {:mode :worker :w w
                          :swaps (+ n1 n2)
                          :datoms (attempt (count (d/datoms @a :eavt)))})))

      "readback"
      (let [a (open-atom dir)
            db @a
            ages (d/q '[:find ?a :where [_ :age ?a]] db)]
        (println (pr-str {:mode :readback
                          :datoms (attempt (count (d/datoms db :eavt)))
                          :named (attempt (count (d/q '[:find ?e :where [?e :name ?n]] db)))
                          :age-sum (attempt (reduce + (map first ages)))
                          :age34 (attempt (vec (sort (d/q '[:find [?n ...]
                                                            :where [?e :age 34]
                                                            [?e :name ?n]] db))))
                          :seal (atom/seal a)
                          :hash (attempt (hash db))}))))
    (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t
      (println (pr-str {:mode (keyword (or mode "none")) :fatal (str t)})))))
