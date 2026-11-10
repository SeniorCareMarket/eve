(ns ants-glj-main
  "Glojure ant host: opens the shared world, waits for the ready
   mark, places its ants, runs their behave loops round-robin
   until the deadline. Launched per the STM lane's glojure
   pattern: cwd = the gljw5b worktree, GLJ_CLASSPATH carries that
   tree's Eve + this lane's stm/ants sources, binary glj-eve -e
   (do (require 'eve.host-completion) (require 'ants-glj-main)
       (ants-glj-main/run \"<cfg-path>\")).
   Glojure's now: System/currentTimeMillis via interop; sleep is
   Thread/sleep (go seat threads are real threads)."
  (:require [ants]
            [eve.stm :as stm]
            [clojure.edn :as edn]))

(defn run [cfg-path]
  (try
    (let [cfg (edn/read-string (slurp cfg-path))
          _ (ants/await-built! (:root cfg))
          w (ants/open-world (:root cfg) (:dim cfg))]
      (ants/await-ready! w)
      (let [ids (ants/place-ants! w (:ants cfg))
            deadline (+ (System/currentTimeMillis) (* 1000 (:seconds cfg)))
            calls (atom 0)
            cycles (atom 0)
            errors (atom [])]
        (println "GLJ-ANT-HOST-READY tag=" (:tag w) "ants=" ids)
        (flush)
        (loop []
          (when (< (System/currentTimeMillis) deadline)
            (doseq [id ids]
              (try (ants/behave-once! w id)
                   (swap! calls inc)
                   (catch Throwable t
                     (when (empty? @errors)
                       (println "GLJ-FIRST-ERROR" id (.getName (class t)) t)
                       (flush))
                     (swap! errors conj (str id " " t)))))
            (swap! cycles inc)
            (when (zero? (mod @cycles 10))
              (println "GLJ-BEAT cycles=" @cycles "calls=" @calls
                       "errors=" (count @errors))
              (flush))
            (ants/sleep! 30)
            (recur)))
        (println "GLJ-ANT-HOST-DONE tag=" (:tag w)
                 "behave-calls=" @calls
                 "stats=" (stm/stats)
                 "errors=" @errors)
        (flush)))
    (catch Throwable t
      (println "GLJ-HOST-DIED" (.getName (class t)) t)
      (flush)
      (System/exit 1)))
  (System/exit 0))
