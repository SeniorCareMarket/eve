(ns eve3.proto-map
  "CLJ → CLJS protocol mapping table.

   Maps JVM interface/class symbols to CLJS protocol + method translations.
   Users write CLJ-style names (clojure.lang.Counted, count). On CLJS, the
   macro translates to CLJS equivalents (ICounted, -count).")

(def jvm->cljs
  '{clojure.lang.Counted
    {:cljs-proto ICounted
     :methods {count {:cljs-name -count :arities [[_]]}}}

    clojure.lang.ILookup
    {:cljs-proto ILookup
     :methods {valAt {:cljs-name -lookup :arities [[_ k] [_ k nf]]}}}

    clojure.lang.Associative
    {:cljs-proto IAssociative
     :methods {containsKey {:cljs-name -contains-key? :arities [[_ k]]}
               assoc       {:cljs-name -assoc         :arities [[_ k v]]}}}

    clojure.lang.IPersistentMap
    {:cljs-proto IMap
     :methods {without {:cljs-name -dissoc :arities [[_ k]]}}}

    clojure.lang.IPersistentCollection
    {:cljs-proto nil  ;; splits across ICollection + IEmptyableCollection + IEquiv
     :split? true
     :methods {cons  {:cljs-name -conj  :cljs-proto ICollection       :arities [[_ v]]}
               empty {:cljs-name -empty :cljs-proto IEmptyableCollection :arities [[_]]}
               equiv {:cljs-name -equiv :cljs-proto IEquiv            :arities [[_ other]]}}}

    clojure.lang.IHashEq
    {:cljs-proto IHash
     :methods {hasheq {:cljs-name -hash :arities [[_]]}}}

    clojure.lang.Seqable
    {:cljs-proto ISeqable
     :methods {seq {:cljs-name -seq :arities [[_]]}}}

    clojure.lang.IMeta
    {:cljs-proto IMeta
     :methods {meta {:cljs-name -meta :arities [[_]]}}}

    clojure.lang.IObj
    {:cljs-proto IWithMeta
     :methods {withMeta {:cljs-name -with-meta :arities [[_ m]]}}}

    clojure.lang.IFn
    {:cljs-proto IFn
     :methods {invoke {:cljs-name -invoke :arities [[_ a] [_ a b]]}}}

    clojure.lang.IReduceInit
    {:cljs-proto IReduce
     :methods {reduce {:cljs-name -reduce :arities [[_ f init]]}}}

    clojure.lang.IKVReduce
    {:cljs-proto IKVReduce
     :methods {kvreduce {:cljs-name -kv-reduce :arities [[_ f init]]}}}

    clojure.lang.Indexed
    {:cljs-proto IIndexed
     :methods {nth {:cljs-name -nth :arities [[_ n] [_ n nf]]}}}

    clojure.lang.IPersistentStack
    {:cljs-proto IStack
     :methods {peek {:cljs-name -peek :arities [[_]]}
               pop  {:cljs-name -pop  :arities [[_]]}}}

    clojure.lang.IPersistentVector
    {:cljs-proto IVector
     :methods {assocN {:cljs-name -assoc-n :arities [[_ n val]]}}}

    clojure.lang.IPersistentSet
    {:cljs-proto ISet
     :methods {disjoin {:cljs-name -disjoin :arities [[_ v]]}
               get     {:cljs-name -get     :arities [[_ v]]}}}

    clojure.lang.ISeq
    {:cljs-proto ISeq
     :methods {first {:cljs-name -first :arities [[_]]}
               more  {:cljs-name -rest  :arities [[_]]}
               ;; next splits to INext on CLJS
               next  {:cljs-name -next  :cljs-proto-override INext :arities [[_]]}}}

    clojure.lang.Sequential
    {:cljs-proto ISequential
     :marker? true
     :methods {}}

    ;; --- CLJ-only interfaces (no CLJS equivalent) ---

    clojure.lang.MapEquivalence
    {:cljs-proto nil
     :marker? true
     :clj-only? true
     :methods {}}

    clojure.lang.IPersistentList
    {:cljs-proto nil
     :marker? true
     :clj-only? true
     :methods {}}

    java.lang.Iterable
    {:cljs-proto nil
     :clj-only? true
     :methods {iterator {:cljs-name nil :arities [[_]]}}}

    java.lang.Object
    {:cljs-proto nil
     :clj-only? true
     :methods {toString {:cljs-name nil :arities [[_]]}
               equals   {:cljs-name nil :arities [[_ other]]}
               hashCode {:cljs-name nil :arities [[_]]}}}})

(defn lookup [iface-sym]
  (get jvm->cljs iface-sym))

(defn mapped? [iface-sym]
  (contains? jvm->cljs iface-sym))
