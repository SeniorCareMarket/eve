(ns census-vs.probe-main
  (:require [eve.atom :as atom]))
(let [dir "/home/hatch/workspace/census-vs/domain"
      dom (atom/persistent-atom-domain dir {:journal? true :journal-batch 10})
      a   (atom/persistent-atom {:id :eve/main :persistent dir} 0)]
  (swap! a inc)
  (swap! a inc)
  (println :vs-read @a)
  (atom/sync! a)
  (println :vs-sync-ok))
