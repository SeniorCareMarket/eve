(ns probe-nbb-clock2
  "Discriminator 6: the clock atom's deref before/after a second
   domain opens (make-ref's cell domain) and after a claim."
  (:require [eve.process-slab :as ps]
            [eve.atom :as ea]
            [eve.stm :as stm]))

(defn- show [label clock]
  (println label "val:" (pr-str @clock)
           "type:" (pr-str (type @clock))
           "number?" (number? @clock)) (flush))

(defn- run [root]
  (let [cd (ea/persistent-atom-domain (str root "/clock") :durable? false)
        clock (ea/lookup-or-create-mmap-atom! cd "eve.stm/clock" 0)
        procs (ea/lookup-or-create-mmap-atom! cd "ants/procs" 0)]
    (show "D after create" clock)
    (stm/seat-clock! clock)
    (stm/seat-process-tag! (swap! procs inc))
    (show "D after procs swap" clock)
    (let [r (stm/make-ref 0 {:seat {:domain-path (str root "/cell-0000")
                                    :name "ants/cell"
                                    :durable? false}})]
      (show "D after make-ref" clock)
      (println "D claim returns:" (pr-str (swap! clock inc))) (flush)
      (show "D after claim" clock)
      (println "D cell:" (pr-str @(:cell r))) (flush)))
  (js/process.exit 0))

(defn -main [& _args]
  (-> (ps/init! :force true)
      (.then (fn [_] (run (first _args))))
      (.catch (fn [e] (println "PROBE-NBB-CLOCK2 ERROR:" e) (js/process.exit 1)))))
