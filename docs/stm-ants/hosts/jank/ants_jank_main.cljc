(ns ants-jank-main
  "The ants demo's jank ant host: opens the shared world, waits for
   the driver's ready mark, places its ants, and runs their behave
   loops (round-robin in this process) until the deadline. Config
   is read from ants-config.edn in the working directory (the
   runner writes it): {:root :dim :ants :seconds}.

   Jank seat discipline (the stm-jank-main pattern): eve.host-
   completion loads first (it completes this seat's core), the
   deadline clock is eve.process/now-ms (the host contract's
   clock, no java.lang.System assumption), and the process exits
   through eve.process/exit! — jank's own teardown otherwise
   hangs, a named upstream defect (eve/doc/hosts/jank.md).
   No (flush) anywhere: on this seat clojure.core/flush is an
   unported stub that throws ex-info \"TODO: port flesh\"
   (measured, Nov 8) — println's output lands without it, as
   the STM seat mains already rely on."
  (:require [eve.host-completion]
            [eve.process :as proc]
            [ants]
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
          errors (atom [])]
      (println "JANK-ANT-HOST-READY tag=" (:tag w) "ants=" ids)
      (loop []
        (when (< (proc/now-ms) deadline)
          (doseq [id ids]
            (try (ants/behave-once! w id)
                 (swap! calls inc)
                 (catch Throwable t
                   (swap! errors conj (str id " " t)))))
          (ants/sleep! 30)
          (recur)))
      (println "JANK-ANT-HOST-DONE tag=" (:tag w)
               "behave-calls=" @calls
               "stats=" (stm/stats)
               "errors=" @errors)))
  (proc/exit! 0))
