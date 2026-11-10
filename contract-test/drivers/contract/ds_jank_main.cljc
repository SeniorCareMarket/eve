(ns contract.ds-jank-main
  "jank entry point for the DataScript drivers (the contract.jank-main
   pattern): eve.host-completion first, exit via eve.process/exit!."
  (:require [eve.host-completion]
            [eve.process :as proc]
            [contract.ds-behave :as ds-behave]
            [contract.ds-contend :as ds-contend]))

(defn -main [& [driver & args]]
  (case driver
    "behave" (apply ds-behave/-main args)
    "ds-contend" (apply ds-contend/-main args)
    (println {:fatal "usage: contract.ds-jank-main behave|ds-contend"}))
  (proc/exit! 0))
