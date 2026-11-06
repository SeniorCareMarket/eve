(deftype LRU [^clojure.lang.Associative key-value gen-key key-gen gen limit]
  clojure.lang.ILookup
  (valAt [_ k]           (.valAt key-value k))
  (valAt [_ k not-found] (.valAt key-value k not-found))
  clojure.lang.Associative
  (containsKey [_ k] (.containsKey key-value k))
  (entryAt [_ k]     (.entryAt key-value k))
  (assoc [this k v]  (LRU. (.assoc key-value k v) gen-key key-gen gen limit))
  clojure.lang.IPersistentMap)
(def l (LRU. {:a 1 :b 2} {} {} 0 10))
(println :get (get l :a) :get2 (get l :b) :get-nf (get l :zz 42))
(println :contains (contains? l :a) :find (find l :b))
(println :assoc (get (assoc l :c 3) :c))
(doseq [[label thunk] {:count #(count l) :seq #(seq l) :empty #(empty l)
                       :cons #(cons [:x 9] l) :without #(dissoc l :a)
                       :keys #(keys l) :eq #(= l l) :kw-invoke #(l :a) :map? #(map? l)}]
  (println label (try (thunk) (catch Throwable t (str "THREW " (ex-message t))))))
