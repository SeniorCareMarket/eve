(ns contract.ds-loadprobe
  "Load datascript.db/core standalone and print the full cause chain
   (successor lane: D5 verification probe)."
  (:require [eve.host] [eve.atom]))
(defn -main [& _]
  (try (require 'datascript.core)
       (println {:load :ok :filtereddb-var (some? (resolve 'datascript.db/FilteredDB))})
    (catch Throwable e
      (loop [c e depth 0]
        (when c (println {:cause depth :type (str (type c)) :msg (.getMessage c)})
          (recur (.getCause c) (inc depth))))))
  (System/exit 0))
