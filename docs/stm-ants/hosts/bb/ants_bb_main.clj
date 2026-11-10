;; ants_bb_main.clj — the ants demo's Babashka ant host.
;; Script main: args = config file path ({:root :dim :ants
;; :seconds}). Opens the shared world, waits for the ready mark,
;; places its ants, runs their behave loops round-robin until the
;; deadline. Run with the flagship bb binary:
;;   bb -cp <eve-bb-roots + stm + ants> -f ants_bb_main.clj <cfg>

(require '[ants]
         '[eve.stm :as stm]
         '[clojure.edn :as edn])

(let [[cfg-path] *command-line-args*
      cfg (edn/read-string (slurp cfg-path))
      _ (ants/await-built! (:root cfg))
      w (ants/open-world (:root cfg) (:dim cfg))]
  (ants/await-ready! w)
  (let [ids (ants/place-ants! w (:ants cfg))
        deadline (+ (System/currentTimeMillis) (* 1000 (:seconds cfg)))
        calls (atom 0)
        errors (atom [])]
    (println "BB-ANT-HOST-READY tag=" (:tag w) "ants=" ids)
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
    (println "BB-ANT-HOST-DONE tag=" (:tag w)
             "behave-calls=" @calls
             "stats=" (stm/stats)
             "errors=" @errors)
    (flush)))

(System/exit 0)
