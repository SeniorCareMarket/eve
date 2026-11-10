(ns contract.ds-load1
  (:require [eve.host] [eve.atom]))
(defn -main [& _]
  (try (require 'datascript.test.serialize) (println {:load :ok})
    (catch Throwable e
      (loop [c e depth 0]
        (when c (println {:cause depth :type (str (type c)) :msg (.getMessage c)})
          (recur (.getCause c) (inc depth))))))
  (System/exit 0))
