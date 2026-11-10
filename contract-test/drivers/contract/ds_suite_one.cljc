(ns contract.ds-suite-one
  "Runs ONE upstream suite namespace (arg) in this process and prints
   its summary. Fresh process = fresh default domain (F14 isolation)."
  (:require [eve.host] [eve.atom] [clojure.test :as t]))
(defn -main [& [ns-name]]
  (let [ns-sym (symbol ns-name)]
    (try (require ns-sym)
         (println {:suite-ns ns-sym :result (t/run-tests ns-sym)})
      (catch Throwable e
        (println {:suite-ns ns-sym :threw (str (type e) " " (.getMessage e))}))))
  (System/exit 0))
