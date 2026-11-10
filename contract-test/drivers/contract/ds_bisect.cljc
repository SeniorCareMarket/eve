(ns contract.ds-bisect
  (:require [eve.host] [eve.atom]
            [datascript.core :as d] [datascript.db :as db]
            [me.tonsky.persistent-sorted-set :as pss]))
(defn attempt [label f]
  (let [fut (future (try {:ok (f)} (catch Throwable t {:threw (str (type t) " " (.getMessage t))})) )
        res (deref fut 15000 ::timeout)]
    (println {:case label :res res})
    (future-cancel fut)))
(defn -main [& _]
  (let [conn (d/create-conn)]
    (d/transact! conn [{:db/id 1 :name "Ivan" :age 34}
                       {:db/id 2 :name "Petr" :age 22}
                       {:db/id 3 :name "Oleg" :age 34}])
    (let [dbv @conn aevt (:aevt dbv)
          from (db/datom db/e0 :age nil db/tx0)
          to   (db/datom db/emax :age nil db/txmax)
          sl   (pss/slice aevt from to)
          pred (db/vpred 34)
          vals (fn [s] (mapv (fn [x] (.-v x)) s))]
      (attempt :slice-only #(vals (take 10 sl)))
      (attempt :filter-lazy #(vals (take 10 (filter (fn [x] (pred (.-v x))) sl))))
      (attempt :eduction-map #(vals (take 10 (eduction (map identity) sl))))
      (attempt :eduction-filter #(vals (take 10 (eduction (filter (fn [x] (pred (.-v x)))) sl))))
      (attempt :into-vec-slice #(vals (into [] sl)))
      (attempt :reduce-slice #(reduce (fn [a x] (conj a (.-v x))) [] sl))
      (println {:done true}))
  (System/exit 0)))
