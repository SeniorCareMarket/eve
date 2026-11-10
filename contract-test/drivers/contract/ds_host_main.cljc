(ns contract.ds-host-main
  "Host entry point for the DataScript drivers on seats whose completion
   must load before user namespaces (the eve.runner.<host>-main pattern):
   eve.host-completion first, then the driver."
  (:require [eve.host-completion]
            [contract.ds-behave :as ds-behave]
            [contract.ds-contend :as ds-contend]))

(defn -main [& [driver & args]]
  (case driver
    "behave" (apply ds-behave/-main args)
    "ds-contend" (apply ds-contend/-main args)
    (println {:fatal "usage: contract.ds-host-main behave|ds-contend"})))
