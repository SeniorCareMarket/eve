(ns eve.durability-workload-verify
  "Phase-3 verifier: opens a COPY of a killed cell domain with the
  journal on (recovery installs the newest journaled epoch), reads
  every atom whole, and writes an EDN summary for the python checker:
  counter value, per-class record counts, the :v of every record for
  the small classes, and for the 100 MB class the record count, the
  sum of :v, and the :v of the rids named in a rids file (the rids
  the op logs touched). Records that are not {:v <number> ...} maps
  are counted and sampled as :anomalies rather than crashing the
  read — a corrupt recovered state is a verdict, not a harness
  failure."
  (:require [eve.atom :as atom]
            [clojure.string :as str]))

(def classes
  {:s1k   :rec-1k
   :s100k :rec-100k
   :s1m   :rec-1m
   :s100m :rec-100m})

(defn- anomaly? [[_ v]]
  (or (not (map? v)) (not (number? (:v v)))))

(defn- class-summary [recs rids full?]
  (let [anoms (filter anomaly? recs)]
    (cond-> {:count    (count recs)
             :n-anoms  (count anoms)
             :anoms    (into [] (map (fn [[k v]] [(str k) (pr-str v)])) (take 5 anoms))
             :sum      (reduce + 0 (keep :v (vals recs)))}
      full? (assoc :vs (into {} (map (fn [[k v]] [(str k) (:v v)])) recs))
      (not full?) (assoc :vs (into {} (for [r rids
                                            :let [rec (or (get recs r)
                                                          (get recs (keyword r)))]]
                                        [r (:v rec)]))))))

(defn -main [& [dir out rids-file]]
  (let [rids (when rids-file
               (into #{} (remove str/blank? (str/split-lines (slurp rids-file)))))
        dom  (atom/persistent-atom-domain dir {:journal? true
                                               :journal-batch 1000000})
        c    (atom/persistent-atom {:id :counter :persistent dir} 0)
        res  {:counter @c
              :classes
              (into {}
                    (for [[cls aid] classes]
                      (let [a    (atom/persistent-atom {:id aid :persistent dir} {:records {}})
                            recs (:records @a)]
                        [cls (class-summary recs rids (not= cls :s100m))])))}]
    (spit out (pr-str res))
    (println :verify-done :counter (:counter res)
             :counts (into {} (map (fn [[k v]] [k (:count v)])) (:classes res))
             :anoms (into {} (map (fn [[k v]] [k (:n-anoms v)])) (:classes res)))
    (System/exit 0)))
