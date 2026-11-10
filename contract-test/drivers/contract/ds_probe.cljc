(ns contract.ds-probe
  "Diagnostic probe for the DataScript port: runs one query shape at a
   time inside a StackOverflowError catch and prints the top frames, so
   the repeating unit of an infinite recursion is visible."
  (:require [eve.host]
            [eve.atom]
            [datascript.core :as d]))

(defn -main [& _]
  (let [conn (d/create-conn)]
    (d/transact! conn [{:db/id 1 :name "Ivan" :age 34}
                       {:db/id 2 :name "Petr" :age 22}
                       {:db/id 3 :name "Oleg" :age 34}])
    (let [db @conn
          run (fn [label q]
                (try
                  (println {:probe label :value (vec (sort (d/q q db)))})
                  (catch StackOverflowError e
                    (println {:probe label :result :overflow
                              :frames (vec (map (fn [f] (str (.getClassName f) "." (.getMethodName f)))
                                                (take 24 (.getStackTrace e))))}))
                  (catch Throwable t
                    (println {:probe label :threw (str (type t) " " (.getMessage t))}))))]
      (run :names '[:find [?n ...] :where [?e :name ?n]])
      (run :age34 '[:find [?n ...] :where [?e :age 34] [?e :name ?n]])
      (run :age-any '[:find [?a ...] :where [?e :age ?a]])
      ;; :join-two uses the SET find spec: the earlier tuple spec
      ;; [:find [?n ?a] ...] returns one [name age] tuple, and this
      ;; probe's (sort ...) then compared its String and Number
      ;; elements — a ClassCastException stock Clojure throws
      ;; identically ((sort ["Ivan" 34])). That was "DS3": a probe
      ;; artifact, not a port defect (see STATUS, DS3 CLOSED).
      (run :join-two '[:find ?n ?a :where [?e :name ?n] [?e :age ?a]])))
  (println {:probe :done}))
