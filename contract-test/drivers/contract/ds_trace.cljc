(ns contract.ds-trace
  (:require [eve.host] [eve.atom] [datascript.core :as d]))
(defn -main [& _]
  (let [conn (d/create-conn)]
    (d/transact! conn [{:db/id 1 :name "Ivan" :age 34}
                       {:db/id 2 :name "Petr" :age 22}
                       {:db/id 3 :name "Oleg" :age 34}])
    (try
      (println {:join-two (d/q '[:find [?n ?a] :where [?e :name ?n] [?e :age ?a]] @conn)})
      (catch Throwable t
        (println {:threw (str (type t) " " (.getMessage t))})
        (doseq [f (take 30 (.getStackTrace t))] (println {:frame (str f)}))))
  (System/exit 0)))
