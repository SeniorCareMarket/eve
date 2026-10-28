(ns eve-media.work
  "The jobs every host runs the same way — the index and the duplicate grouping — and what every job does with its
   result: the descriptor carries it, and the job swaps it into the file's library entry. Portable cljc."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.thread.fold :as tf]
            [eve-media.domain :as domain]
            [eve-media.phash :as ph]
            [eve-media.state :as st]))

(defn record!
  "Job kind's result v for the file at path, swapped into the library; v."
  [path kind v]
  (domain/swap-atom! (st/library) assoc-in [:files path :results kind] v)
  v)

(defn index!
  "The index job: the library's results folded into eve-media/index — totals by media kind and status, each file's
   integrated loudness. The duplicate groups are the grouping job's (on the coordinator's own queue, after this)."
  [{:keys [generation]}]
  (let [files (:files (domain/read-atom (st/library)))
        loud (into {} (keep (fn [[_ e]] (when-let [l (get-in e [:results :loudness :lufs])] [(:id e) l])) files))
        totals {:files (count files)
                :by-kind (frequencies (map (comp :kind val) files))
                :by-status (frequencies (map (comp :status val) files))
                :bytes (reduce + 0 (keep (comp :size val) files))}]
    (domain/swap-atom! (st/index) (fn [v] (assoc v :generation generation :totals totals :loudness loud)))
    totals))

(defn group!
  "The duplicate grouping: pHash pairs within the threshold found by a fold over the pairs (eve.thread.fold — chunks on
   this runtime's pools), connected into groups of file ids, swapped into eve-media/index."
  [{:keys [generation]}]
  (let [files (:files (domain/read-atom (st/library)))
        hashes (into {} (keep (fn [[_ e]] (when-let [h (get-in e [:results :phash :phash])] [(:id e) h])) files))
        ids (vec (sort (keys hashes)))
        pairs (vec (for [i (range (count ids)) j (range (inc i) (count ids))] [(nth ids i) (nth ids j)]))
        edges (tf/fold (fn ([] []) ([a b] (into a b)))
                       (fn [acc [a b]] (if (<= (ph/distance (get hashes a) (get hashes b)) ph/threshold)
                                         (conj acc [a b]) acc))
                       pairs)
        groups (ph/groups-from-edges ids edges)]
    (domain/swap-atom! (st/index) (fn [v] (assoc v :duplicates groups :hashed (count ids) :grouped-generation generation)))
    {:groups groups :hashed (count ids)}))
