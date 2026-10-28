(ns eve.durability-workload-node
  "Node entry for the phase-3 workload writer (shadow build
  :durability-workload). nbb runs eve.durability-workload directly
  (-m) and needs no entry shim — and cannot resolve *main-cli-fn* —
  hence this separate namespace."
  (:require [eve.durability-workload :as w]))

(defn main [& args] (apply w/-main args))

(set! *main-cli-fn* main)
