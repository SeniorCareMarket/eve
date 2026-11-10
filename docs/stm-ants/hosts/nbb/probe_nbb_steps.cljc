(ns probe-nbb-steps
  "Discriminator: which step of the disk-tier seating hangs on the
   nbb seat? Prints after every step."
  (:require [eve.process-slab :as ps]
            [eve.atom :as ea]
            [eve.stm :as stm]))

(defn- run [root]
  (println "STEP init done") (flush)
  (let [cd (ea/persistent-atom-domain (str root "/clock") :durable? false)]
    (println "STEP clock domain open") (flush)
    (let [clock (ea/lookup-or-create-mmap-atom! cd "eve.stm/clock" 0)
          procs (ea/lookup-or-create-mmap-atom! cd "ants/procs" 0)]
      (println "STEP clock atoms, clock=" @clock) (flush)
      (stm/seat-clock! clock)
      (let [tag (swap! procs inc)]
        (stm/seat-process-tag! tag)
        (println "STEP tag claimed:" tag) (flush)
        (let [r (stm/make-ref 0 {:seat {:domain-path (str root "/cell-0000")
                                        :name "ants/cell"
                                        :durable? false}})]
          (println "STEP seated ref made") (flush)
          (stm/run-in-transaction (fn [] (stm/alter* r inc [])))
          (println "STEP one commit done, value=" (stm/deref-ref r)
                   "clock=" @clock) (flush)))))
  (js/process.exit 0))

(defn -main [& _args]
  (-> (ps/init! :force true)
      (.then (fn [_] (run (first _args))))
      (.catch (fn [e] (println "PROBE-NBB-STEPS ERROR:" e) (js/process.exit 1)))))
