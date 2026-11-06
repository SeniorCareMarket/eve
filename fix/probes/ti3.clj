(deftype T [x] Associative (containsKey [_ k] true) (entryAt [_ k] nil) (assoc [_ k v] nil))
(println :ok)
