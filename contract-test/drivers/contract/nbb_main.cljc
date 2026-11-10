(ns contract.nbb-main
  "nbb entry point for the contract-test drivers. The CLJS runner's setup
   (eve.runner.nbb-main's `run`): the process slab allocator is initialized
   before any value is constructed.

     nbb --classpath <eve :nbb roots>:<lib>:<drivers> -m contract.nbb-main behave"
  (:require [eve.process-slab :as ps]
            [contract.behave :as behave]
            [contract.contend :as contend]
            [contract.pss-suite :as pss-suite]))

(defn -main [& [driver & args]]
  (-> (ps/init! :force true)
      (.then (fn [_]
               (case driver
                 "behave" (apply behave/-main args)
                 "contend" (apply contend/-main args)
                 "pss-suite" (apply pss-suite/-main args)
                 (println {:fatal "usage: contract.nbb-main behave|contend ..."}))))))
