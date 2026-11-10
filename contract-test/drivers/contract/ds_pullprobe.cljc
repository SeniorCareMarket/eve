(ns contract.ds-pullprobe
  (:require [eve.host] [eve.atom] [datascript.core :as d]))
(def schema
  {:name   {:db/unique :db.unique/identity}
   :aka    {:db/cardinality :db.cardinality/many}
   :child  {:db/cardinality :db.cardinality/many
            :db/valueType :db.type/ref}
   :father {:db/valueType :db.type/ref}
   :part   {:db/valueType :db.type/ref
            :db/isComponent true
            :db/cardinality :db.cardinality/many}})
(def datoms
  (map #(apply d/datom %)
    [[1 :name "Petr"] [1 :aka "Devil"] [1 :aka "Tupen"]
     [2 :name "David"] [3 :name "Thomas"]
     [1 :child 2] [1 :child 3] [2 :father 1] [3 :father 1]
     [10 :name "Part A"] [11 :name "Part A.A"] [10 :part 11]]))
(defn -main [& _]
  (let [db (d/init-db datoms schema)]
    (println {:eavt (mapv (juxt :e :a :v) (d/datoms db :eavt))})
    (println {:avet (mapv (juxt :e :a :v) (d/datoms db :avet))})
    (println {:pull-aka (d/pull db [:name :aka] 1)})
    (println {:pull-child (d/pull db [:name {:child [:name]}] 1)})
    (println {:pull-part (d/pull db [:name {:part [:name]}] 10)})
    (println {:pull-rev (d/pull db [:name :_father] 1)}))
  (let [db2 (d/db-with (d/empty-db schema)
              [{:db/id 1 :name "Petr" :aka ["Devil" "Tupen"] :child [2 3]}
               {:db/id 2 :name "David"} {:db/id 3 :name "Thomas"}])]
    (println {:tx-avet (mapv (juxt :e :a :v) (d/datoms db2 :avet))})
    (println {:tx-pull-aka (d/pull db2 [:name :aka] 1)})
    (println {:tx-pull-child (d/pull db2 [:name {:child [:name]}] 1)}))
  (System/exit 0))
