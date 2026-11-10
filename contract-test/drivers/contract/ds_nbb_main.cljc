(ns contract.ds-nbb-main
  "nbb entry point for the DataScript behave smoke (successor lane
   proof 3a): init the process slab, then run contract.ds-behave —
   load datascript.core, create-conn, transact, query, entity,
   update — printing one EDN row per aspect."
  (:require [eve.process-slab :as ps]
            [contract.ds-behave :as ds-behave]))

(defn -main [& args]
  (-> (ps/init! :force true)
      (.then (fn [_] (apply ds-behave/-main args)))))
