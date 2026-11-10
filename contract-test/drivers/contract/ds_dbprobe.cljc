(ns contract.ds-dbprobe
  (:require [eve.host] [eve.atom] [datascript.core :as d]))
(defn -main [& _]
  (let [db (d/db-with (d/empty-db {:aka {:db/cardinality :db.cardinality/many}})
             [{:db/id 1 :name "Ivan" :aka ["IV" "Terrible"]}
              {:db/id 2 :name "Petr" :age 37 :huh? false}])]
    (println {:count (count db) :keys (set (keys db)) :map? (map? db)
              :seqable-eavt (seqable? (:eavt db))
              :find-datom (d/find-datom db :eavt 1 :name)}))
  (System/exit 0))
