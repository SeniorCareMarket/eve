(ns contract.one
  (:require [eve.atom :as atom] [datascript.core :as d] [eve.core :as e]))
(defn -main [& [dir]]
  (let [dom (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! dom "contract/ds-cli-update-db" nil)]
    (when (nil? @a) (swap! a (fn [_] (d/empty-db))))
    (swap! a (fn [db] (d/db-with db [{:db/id 1 :name "one" :age 1}])))
    (println (pr-str {:mark :ONE-DONE :datoms (e/count (d/datoms @a :eavt))}))
    (flush)))
