(ns eve.durability-crash-reader
  "Phase 2 — reopen one materialized crash image in a fresh process
  and print a value descriptor the orchestrator can compare:
    :count <n> :head <first-4-chars> :hash <java-hashCode>
  for strings, or :value <n> for the counter. Opening a journal
  domain runs journal recovery (on a non-live domain), which is
  the mechanism under test. Usage: read <variant> <dir>"
  (:require [eve.atom :as atom]))

(defn- opts [variant]
  (case variant
    "J1" {:journal? true :journal-batch 1}
    "J10" {:journal? true :journal-batch 10}
    {}))

(defn -main [variant dir]
  (let [dom (atom/persistent-atom-domain dir (opts variant))
        a (atom/persistent-atom {:id :eve/main :persistent dir} ::absent)
        v @a]
    (cond
      (= v ::absent) (println :absent true)
      (string? v) (println :count (count v) :head (subs v 0 (min 4 (count v)))
                           :hash (.hashCode ^String v))
      (sequential? v) (println :count (count v)
                               :elem (count (str (first v)))
                               :fills (str (first (first v))
                                           (first (nth v (quot (count v) 2)))
                                           (first (last v))))
      :else (println :value v))
    (flush)
    (System/exit 0)))
