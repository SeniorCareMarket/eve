(ns ants-cljr-main-spike
  "The ants demo's ClojureCLR ant host: opens the shared world,
   waits for the driver's ready mark, places its ants, and runs
   their behave loops (round-robin in this process) until the
   deadline. Config is read from ants-config.edn in the working
   directory (the runner writes it): {:root :dim :ants :seconds}.
   Mirrors the Jolt host main; the clock and sleep go through
   eve.process, the portable adapter (the CLR seat has no
   System/currentTimeMillis)."
  (:require [ants]
            [eve.process :as proc]
            [eve.stm :as stm]
            [clojure.edn :as edn]))

(defn -main [& _args]
  (let [cfg (edn/read-string (slurp "ants-config.edn"))
        _ (ants/await-built! (:root cfg))
        w (ants/open-world (:root cfg) (:dim cfg))]
    (ants/await-ready! w)
    (let [ids (ants/place-ants! w (:ants cfg))
          deadline (+ (proc/now-ms) (* 1000 (:seconds cfg)))
          calls (atom 0)
          errors (atom [])
          traced (atom #{})]
      (println "CLJR-ANT-HOST-READY tag=" (:tag w) "ants=" ids)
      (flush)
      (loop []
        (when (< (proc/now-ms) deadline)
          (doseq [id ids]
            (try (ants/behave-once! w id)
                 (swap! calls inc)
                 (catch System.Exception t
                   (swap! errors conj (str id " " t))
                   (when-not (contains? @traced id)
                     (swap! traced conj id)
                     (println "SPIKE-TRACE ant=" id)
                     (println (.ToString t))
                     (flush)))))
          (ants/sleep! 30)
          (recur)))
      (println "CLJR-ANT-HOST-DONE tag=" (:tag w)
               "behave-calls=" @calls
               "stats=" (stm/stats)
               "errors=" @errors)
      (flush)))
  (System.Environment/Exit 0))
