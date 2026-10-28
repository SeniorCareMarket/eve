(ns census-vs.probe2
  (:require [eve.atom :as atom]))
(let [a (atom/atom 0)]
  (swap! a inc)
  (swap! a inc)
  (println :vs-heap-read @a))
