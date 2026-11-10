(ns ants-jolt-main-spike
  "SPIKE variant of the Jolt ant host: identical loop, but the
   first error per ant also prints a stack trace and the ant's
   cell state (cached loc, the cell there, and where a snapshot
   actually finds the ant) — diagnosis for the intermittent
   nil-Number behave failure (spike-m1 run 1 + m1b)."
  (:require [ants]
            [eve.stm :as stm]
            [clojure.edn :as edn]))

(defn -main [& _args]
  (let [cfg (edn/read-string (slurp "ants-config.edn"))
        w (ants/open-world (:root cfg) (:dim cfg))]
    (ants/await-ready! w)
    (let [ids (ants/place-ants! w (:ants cfg))
          deadline (+ (System/currentTimeMillis) (* 1000 (:seconds cfg)))
          calls (atom 0)
          errors (atom [])
          traced (atom #{})]
      (println "JOLT-ANT-HOST-READY tag=" (:tag w) "ants=" ids)
      (flush)
      (loop []
        (when (< (System/currentTimeMillis) deadline)
          (doseq [id ids]
            (try (ants/behave-once! w id)
                 (swap! calls inc)
                 (catch Throwable t
                   (swap! errors conj (str id " " t))
                   (when-not (contains? @traced id)
                     (swap! traced conj id)
                     (println "SPIKE-TRACE ant=" id "cached-loc=" (get @(:locs w) id))
                     (try (let [cells (ants/snapshot w)
                                at (first (filter (fn [i] (contains? (:ants (nth cells i)) id))
                                                  (range (count cells))))]
                            (println "SPIKE-TRACE snapshot-finds-ant-at=" at
                                     "cached-cell=" (nth cells (get @(:locs w) id))))
                          (catch Throwable t2 (println "SPIKE-TRACE dump failed" t2)))
                     (.printStackTrace t)
                     (flush)))))
          (ants/sleep! 30)
          (recur)))
      (println "JOLT-ANT-HOST-DONE tag=" (:tag w)
               "behave-calls=" @calls
               "stats=" (stm/stats)
               "errors=" @errors)
      (flush)))
  (System/exit 0))
