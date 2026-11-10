(ns contract.ds-order
  (:require [eve.host] [eve.atom] [datascript.core :as d]
            [me.tonsky.persistent-sorted-set :as set]))
(defn -main [& _]
  (let [db (d/db-with (d/empty-db)
             [{:db/id 1 :name "Ivan" :age 17 :sex :male :aka "IV"}
              {:db/id 2 :name "Petr" :age 37}])]
    (println {:datoms-eavt (mapv (juxt :e :a :v) (d/datoms db :eavt))})
    (println {:raw-eavt-seq (mapv (juxt :e :a :v) (:eavt db))})
    (println {:raw-aevt-seq (mapv (juxt :e :a :v) (:aevt db))}))
  (let [s (set/sorted-set-by :datascript.db/cmp-eavt
            (d/datom 1 :name "Ivan") (d/datom 1 :age 17) (d/datom 1 :sex :male))]
    (println {:btset-seq (mapv (juxt :e :a :v) s)}))
  (let [db2 (d/init-db [(d/datom 1 :name "Ivan") (d/datom 1 :age 17) (d/datom 1 :sex :male)])]
    (println {:initdb-eavt (mapv (juxt :e :a :v) (d/datoms db2 :eavt))}))
  (System/exit 0))
