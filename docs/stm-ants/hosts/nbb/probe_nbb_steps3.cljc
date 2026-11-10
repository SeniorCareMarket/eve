(ns probe-nbb-steps3
  "Discriminator 4: empty-body transaction (runner + claim only)
   vs a deref-body transaction (adds touch/read-point)."
  (:require [eve.process-slab :as ps]
            [eve.atom :as ea]
            [eve.stm :as stm]))

(defn- run [root]
  (let [cd (ea/persistent-atom-domain (str root "/clock") :durable? false)
        clock (ea/lookup-or-create-mmap-atom! cd "eve.stm/clock" 0)
        procs (ea/lookup-or-create-mmap-atom! cd "ants/procs" 0)]
    (stm/seat-clock! clock)
    (stm/seat-process-tag! (swap! procs inc))
    (let [r (stm/make-ref 0 {:seat {:domain-path (str root "/cell-0000")
                                    :name "ants/cell"
                                    :durable? false}})]
      (println "T ref made") (flush)
      (let [v (stm/run-in-transaction (fn [] 42))]
        (println "T empty tx done:" v "clock=" @clock) (flush))
      (let [v (stm/run-in-transaction (fn [] (stm/deref-ref r)))]
        (println "T deref tx done:" v "clock=" @clock) (flush))))
  (js/process.exit 0))

(defn -main [& _args]
  (-> (ps/init! :force true)
      (.then (fn [_] (run (first _args))))
      (.catch (fn [e] (println "PROBE-NBB-STEPS3 ERROR:" e) (js/process.exit 1)))))
