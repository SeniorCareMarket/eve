(ns ants-lpy-main
  "Basilisp ant host: opens the shared world, waits for the ready
   mark, places its ants, runs their behave loops round-robin
   until the deadline. Launched per the STM lane's basilisp
   pattern: basilisp run -p <reversed roots> -c
   (require 'ants-lpy-main) (ants-lpy-main/run \"<cfg-path>\").
   Time and sleep come from eve.process (the python adapter
   supplies real now-ms/sleep-ms!)."
  (:require [ants]
            [eve.process :as proc]
            [eve.stm :as stm]
            [clojure.edn :as edn]))

(defn run [cfg-path]
  (try
    (let [cfg (edn/read-string (slurp cfg-path))
          _ (ants/await-built! (:root cfg))
          w (ants/open-world (:root cfg) (:dim cfg))]
      (ants/await-ready! w)
      (let [ids (ants/place-ants! w (:ants cfg))
            deadline (+ (proc/now-ms) (* 1000 (:seconds cfg)))
            calls (atom 0)
            cycles (atom 0)
            errors (atom [])]
        (println "LPY-ANT-HOST-READY tag=" (:tag w) "ants=" ids)
        (flush)
        (loop []
          (when (< (proc/now-ms) deadline)
            (doseq [id ids]
              (try (ants/behave-once! w id)
                   (swap! calls inc)
                   (catch python/Exception t
                     (when (empty? @errors)
                       (println "LPY-FIRST-ERROR" id (str (type t)) t)
                       (flush))
                     (swap! errors conj (str id " " t)))))
            (swap! cycles inc)
            (when (zero? (mod @cycles 10))
              (println "LPY-BEAT cycles=" @cycles "calls=" @calls
                       "errors=" (count @errors))
              (flush))
            (ants/sleep! 30)
            (recur)))
        (println "LPY-ANT-HOST-DONE tag=" (:tag w)
                 "behave-calls=" @calls
                 "stats=" (stm/stats)
                 "errors=" @errors)
        (flush)))
    (catch python/Exception t
      (println "LPY-HOST-DIED" (str (type t)) t)
      (flush)))
  nil)
