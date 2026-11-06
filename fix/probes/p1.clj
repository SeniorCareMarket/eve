;; P1: the vanilla LRU shape, verbatim interface list (datascript.lru :clj branch)
(deftype LRU [^clojure.lang.Associative key-value gen-key key-gen gen limit]
  clojure.lang.ILookup
  (valAt [_ k]           (.valAt key-value k))
  (valAt [_ k not-found] (.valAt key-value k not-found))
  clojure.lang.Associative
  (containsKey [_ k] (.containsKey key-value k))
  (entryAt [_ k]     (.entryAt key-value k))
  (assoc [this k v]  this))
(println :p1-ok)
