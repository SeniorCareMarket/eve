(ns wrap.lib)
(deftype LRU [^clojure.lang.Associative key-value gen-key key-gen gen limit]
  clojure.lang.ILookup
  (valAt [_ k]           (.valAt key-value k))
  (valAt [_ k not-found] (.valAt key-value k not-found))
  clojure.lang.Associative
  (containsKey [_ k] (.containsKey key-value k))
  (entryAt [_ k]     (.entryAt key-value k))
  (assoc [this k v]  (LRU. (assoc key-value k v) gen-key key-gen gen limit)))
(defn check [] (get (assoc (LRU. {} {} {} 0 10) :a 1) :a))
(defn type-ns [] (str (type (LRU. {} {} {} 0 10))))
