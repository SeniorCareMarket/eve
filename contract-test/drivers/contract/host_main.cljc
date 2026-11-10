(ns contract.host-main
  "Generic host entry point for the contract-test drivers on seats whose
   completion must load before user namespaces (the eve.runner.<host>-main
   pattern): eve.host-completion first, then the driver.

     <host> -m contract.host-main behave
     <host> -m contract.host-main contend <mode> <dir> [w]"
  (:require [eve.host-completion]
            [contract.behave :as behave]
            [contract.contend :as contend]
            [contract.pss-suite :as pss-suite]))

(defn -main [& [driver & args]]
  (case driver
    "behave" (apply behave/-main args)
    "contend" (apply contend/-main args)
    "pss-suite" (apply pss-suite/-main args)
    (println {:fatal "usage: contract.host-main behave|contend ..."})))

;; basilisp run -n loads the namespace with *main-ns* naming it but does not
;; call -main; self-invoke, the eve.runner.lpy-main pattern.
#?(:lpy (when (= *main-ns* 'contract.host-main)
          (apply -main *command-line-args*)))
