(ns nbb-probe
  (:require [eve.process-slab :as ps]
            [eve.core :as e]
            [me.tonsky.persistent-sorted-set :as pss]))

(defn run [_]
  (let [s (reduce (fn [s x] (e/conj s x)) (pss/sorted-set) (range 20))]
    (println :next (try (pr-str (next (e/seq s))) (catch :default t (str :throws t))))
    (println :rest (try (pr-str (rest (e/seq s))) (catch :default t (str :throws t))))
    (println :e-next (try (pr-str (e/next (e/seq s))) (catch :default t (str :throws t))))
    (println :second (try (second (e/seq s)) (catch :default t (str :throws t))))
    (println :nth1 (try (nth (e/seq s) 1) (catch :default t (str :throws t))))
    (println :take3 (try (pr-str (take 3 (e/seq s))) (catch :default t (str :throws t))))
    (println :sum (try (reduce + (e/seq s)) (catch :default t (str :throws t))))))

(defn -main [& _]
  (.then (ps/init! :force true) run))
