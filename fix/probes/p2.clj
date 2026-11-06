;; P2: + IPersistentMap listed, no new methods
(deftype LRU [^clojure.lang.Associative key-value gen-key key-gen gen limit]
  clojure.lang.ILookup
  (valAt [_ k]           (.valAt key-value k))
  (valAt [_ k not-found] (.valAt key-value k not-found))
  clojure.lang.Associative
  (containsKey [_ k] (.containsKey key-value k))
  (entryAt [_ k]     (.entryAt key-value k))
  (assoc [this k v]  this)
  clojure.lang.IPersistentMap)
(println :p2-ok)
