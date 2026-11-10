(ns probe-nbb-steps2
  "Discriminator 3: bisect the first-commit hang on nbb — a
   read-only transaction (touch + claim, no installs), then a
   writing transaction (full commit path)."
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
      (println "S ref made") (flush)
      (let [v (stm/run-in-transaction (fn [] (stm/deref-ref r)))]
        (println "S read-only tx done:" v "clock=" @clock) (flush))
      (stm/run-in-transaction (fn [] (stm/alter* r inc [])))
      (println "S write tx done, value=" (stm/deref-ref r)
               "clock=" @clock "stats=" (stm/stats)) (flush)))
  (js/process.exit 0))

(defn -main [& _args]
  (-> (ps/init! :force true)
      (.then (fn [_] (run (first _args))))
      (.catch (fn [e] (println "PROBE-NBB-STEPS2 ERROR:" e) (js/process.exit 1)))))
