(ns contract.jank-main
  "jank entry point for the contract-test drivers. eve.host-completion loads
   first — a namespace sees the names the completion interns into clojure.core
   only when it is made after them (the eve.runner.jank-main pattern). Exit
   goes through eve.process/exit!: jank's own end runs no hook and can crash
   under a live timer thread, eating buffered output.

     jank --module-path <eve :jank roots>:<lib>:<drivers> run-main contract.jank-main -- behave
     jank --module-path <eve :jank roots>:<lib>:<drivers> run-main contract.jank-main -- contend <mode> <dir> [w]"
  (:require [eve.host-completion]
            [eve.process :as proc]
            [contract.behave :as behave]
            [contract.contend :as contend]
            [contract.pss-suite :as pss-suite]))

(defn -main [& [driver & args]]
  (case driver
    "behave" (apply behave/-main args)
    "contend" (apply contend/-main args)
    "pss-suite" (apply pss-suite/-main args)
    (println {:fatal "usage: contract.jank-main behave|contend ..."}))
  (proc/exit! 0))
