(ns flagship.jank-entry-db
  ;; Partial jank entry proof: datascript.db only (load + transact
  ;; + datoms). datascript.core/query are blocked on jank by
  ;; F-JANK-READER-LIBSPEC (see STATUS); conn rides core, so the
  ;; conn/query rows wait on that finding's owners.
  (:require [datascript.db :as db]))

(def base-tx (mapv (fn [i] {:db/id i :name (str "base-" i) :age (+ 20 i)})
                   (range 1 11)))

(defn -main [& args]
  (let [d0 (db/empty-db)
        d1 (db/db-with d0 base-tx)
        n (count (db/datoms d1 :eavt))
        e5 (db/entity d1 5)]
    (println :jank-entry-db {:datoms n
                             :e5-name (:name e5)
                             :e5-age (:age e5)})
    (println :jank-entry-db-ok (and (= 20 n) (= "base-5" (:name e5))
                                     (= 25 (:age e5))))))
