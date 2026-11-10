(ns flagship.jank-entry
  ;; Roster entry ticket — JANK seat: DataScript load + transact +
  ;; query on jank against the release tree (guard supply landed).
  ;; Heap conn first, then a persistent (mmap) domain round: the
  ;; flagship worker needs the persistent domain, so the ticket
  ;; proves both. Prints one :jank-entry map; exit 0 only if every
  ;; row is exact.
  (:require [datascript.core :as d]
            [datascript.conn :as conn]
            [eve.atom :as atom]))

(def base-tx (mapv (fn [i] {:db/id i :name (str "base-" i) :age (+ 20 i)})
                   (range 1 11)))

(defn -main [& args]
  (let [dir (first args)
        c (conn/create-conn)
        _ (conn/transact! c base-tx)
        heap-count (count (d/datoms @c :eavt))
        heap-q (d/q '[:find ?n :where [?e :name ?n] [?e :age 25]] @c)
        _ (println :jank-entry {:heap-count heap-count
                                :heap-q34 heap-q})
        dom (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! dom "contract/ds-flagship" nil)
        _ (when (nil? @a) (swap! a (fn [_] (d/empty-db))))
        _ (swap! a (fn [db] (d/db-with db base-tx)))
        mmap-count (count (seq (d/datoms @a :eavt)))
        mmap-q (d/q '[:find ?n :where [?e :name ?n] [?e :age 25]] @a)]
    (println :jank-entry {:mmap-count mmap-count :mmap-q34 mmap-q})
    (println :jank-entry-ok
             (and (= 20 heap-count) (= #{["base-5"]} heap-q)
                  (= 20 mmap-count) (= #{["base-5"]} mmap-q)))))
