(ns ants-jolt-main
  "The ants demo's Jolt ant host: opens the shared world, waits for
   the driver's ready mark, places its ants, and runs their behave
   loops (round-robin in this process) until the deadline. Config
   is read from ants-config.edn in the working directory (the
   runner writes it): {:root :dim :ants :seconds}."
  (:require [ants]
            [eve.stm :as stm]
            [clojure.edn :as edn]))

(defn -main [& _args]
  (let [cfg (edn/read-string (slurp "ants-config.edn"))
        _ (ants/await-built! (:root cfg))
        w (ants/open-world (:root cfg) (:dim cfg))]
    (ants/await-ready! w)
    (let [ids (ants/place-ants! w (:ants cfg))
          deadline (+ (System/currentTimeMillis) (* 1000 (:seconds cfg)))
          calls (atom 0)
          errors (atom [])]
      (println "JOLT-ANT-HOST-READY tag=" (:tag w) "ants=" ids)
      (flush)
      (loop []
        (when (< (System/currentTimeMillis) deadline)
          (doseq [id ids]
            (try (ants/behave-once! w id)
                 (swap! calls inc)
                 (catch Throwable t
                   (swap! errors conj (str id " " t)))))
          (ants/sleep! 30)
          (recur)))
      (println "JOLT-ANT-HOST-DONE tag=" (:tag w)
               "behave-calls=" @calls
               "stats=" (stm/stats)
               "errors=" @errors)
      (flush)))
  (System/exit 0))
