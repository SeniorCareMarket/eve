(ns census-vs.probe3
  (:require [eve.atom :as atom]))
(let [dir "/home/hatch/workspace/census-vs/domain3"
      dom (atom/persistent-atom-domain dir {})
      a   (atom/persistent-atom {:id :eve/main :persistent dir} 0)]
  (swap! a inc)
  (swap! a inc)
  (println :vs-disk-read @a)
  (atom/sync! a)
  (println :vs-disk-sync-ok))
