(deftype OnlyLookup [m] clojure.lang.ILookup (valAt [_ k] (get m k)) (valAt [_ k nf] (get m k nf)))
(println :ilookup-alone-ok)
