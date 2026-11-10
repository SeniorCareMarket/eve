(ns probe-nbb-cas
  "Discriminator 2: the commit's composed-CAS primitives on the nbb
   seat. stm's cell-cas!/eve-value-cas! are swap! compositions whose
   success test is (= returned new). Print what swap! actually
   returns for the lock atom and the cell atom on this seat."
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
                                    :durable? false}})
          lc (:lock-cell r)
          cell (:cell r)]
      (println "P lock initial:" (pr-str @lc)) (flush)
      (let [res (swap! lc (fn [cur] (if (= cur nil) [1 1] cur)))]
        (println "P lock swap result:" (pr-str res)
                 "equal-to-new?" (= res [1 1])
                 "lock now:" (pr-str @lc)) (flush))
      (let [res2 (swap! lc (fn [cur] (if (= cur [1 1]) nil cur)))]
        (println "P lock release result:" (pr-str res2)
                 "lock now:" (pr-str @lc)) (flush))
      (let [cur @cell
            new-cell (assoc cur :value 1 :point 1 :version 1)
            res (swap! cell (fn [c] (if (= c cur) new-cell c)))]
        (println "P cell cas result-equal?" (= res new-cell)
                 "cell now:" (pr-str @cell)) (flush))
      (println "P deref-ref:" (stm/deref-ref r)) (flush)))
  (js/process.exit 0))

(defn -main [& _args]
  (-> (ps/init! :force true)
      (.then (fn [_] (run (first _args))))
      (.catch (fn [e] (println "PROBE-NBB-CAS ERROR:" e) (js/process.exit 1)))))
