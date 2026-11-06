(deftype OnlyAssoc [m] clojure.lang.Associative (containsKey [_ k] (contains? m k)) (entryAt [_ k] (find m k)) (assoc [_ k v] (OnlyAssoc. (assoc m k v))))
(println :assoc-alone-ok)
