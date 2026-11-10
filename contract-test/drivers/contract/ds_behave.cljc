(ns contract.ds-behave
  "First-light driver for the DataScript port (lib-ds/): load
   datascript.core, create a conn, transact, query, and print one EDN
   row per aspect. The JVM run is the semantic reference; later host
   runs compare against it row by row, like the PSS behave driver."
  (:require [eve.host]
            [eve.atom]
            [datascript.core :as d]))

(defn -main [& _]
  (println {:aspect :load :value :ok})
  (let [conn (d/create-conn)]
    (println {:aspect :create-conn :value :ok})
    (d/transact! conn [{:db/id 1 :name "Ivan" :age 34}
                       {:db/id 2 :name "Petr" :age 22}
                       {:db/id 3 :name "Oleg" :age 34}])
    (let [db @conn]
      (println {:aspect :transact :value :ok})
      (println {:aspect :datom-count :value (count (d/datoms db :eavt))})
      (println {:aspect :query-names
                :value (vec (sort (d/q '[:find [?n ...]
                                         :where [?e :name ?n]] db)))})
      (println {:aspect :query-age34
                :value (vec (sort (d/q '[:find [?n ...]
                                         :where [?e :age 34]
                                         [?e :name ?n]] db)))})
      (println {:aspect :entity :value (:name (d/entity db 2))})
      (d/transact! conn [[:db/add 2 :age 23]])
      (println {:aspect :after-update-age
                :value (:age (d/entity @conn 2))})))
  (println {:aspect :done :value :ok}))
