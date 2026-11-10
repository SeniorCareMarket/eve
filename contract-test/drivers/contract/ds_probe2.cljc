(ns contract.ds-probe2
  "Second bisect for the DataScript port: separates the index-seek path
   (d/datoms with a constant) from the query layer's use of it."
  (:require [eve.host]
            [eve.atom]
            [datascript.core :as d]))

(defn -main [& _]
  (let [conn (d/create-conn)]
    (d/transact! conn [{:db/id 1 :name "Ivan" :age 34}
                       {:db/id 2 :name "Petr" :age 22}
                       {:db/id 3 :name "Oleg" :age 34}])
    (let [db @conn
          run (fn [label thunk]
                (try
                  (println {:probe label :value (thunk)})
                  (catch StackOverflowError _
                    (println {:probe label :result :overflow}))
                  (catch Throwable t
                    (println {:probe label :threw (str (type t) " " (.getMessage t))}))))]
      (run :datoms-avet-all #(vec (map (fn [dt] [(:e dt) (:a dt) (:v dt)]) (d/datoms db :avet))))
      (run :datoms-avet-age34 #(vec (map (fn [dt] [(:e dt) (:v dt)]) (d/datoms db :avet :age 34))))
      (run :q-const-only #(vec (sort (d/q '[:find [?e ...] :where [?e :age 34]] db))))
      (run :q-const-second #(vec (sort (d/q '[:find [?e ...] :where [?e :name ?n] [?e :age 34]] db)))))
    (println {:probe :done})))
