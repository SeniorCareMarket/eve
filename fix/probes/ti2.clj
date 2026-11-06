(deftype T [x] ILookup (valAt [_ k] 1) (valAt [_ k nf] nf))
(println :ok)
