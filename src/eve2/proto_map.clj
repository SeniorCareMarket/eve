(ns eve2.proto-map
  "CLJS → JVM protocol mapping table.

   Maps CLJS protocol symbols to JVM interface + method translations.
   Users write CLJS-style names (ICounted, -count). On CLJ, the macro
   translates to JVM interfaces (clojure.lang.Counted, count).")

(def cljs->jvm
  '{ICounted
    {:iface   clojure.lang.Counted
     :methods {-count {:jvm-name count :arities [[_]]}}}

    ILookup
    {:iface   clojure.lang.ILookup
     :methods {-lookup {:jvm-name valAt :arities [[_ k] [_ k nf]]}}}

    IAssociative
    {:iface   clojure.lang.Associative
     :methods {-contains-key? {:jvm-name containsKey :arities [[_ k]]}
               -assoc         {:jvm-name assoc       :arities [[_ k v]]}}}

    IMap
    {:iface   clojure.lang.IPersistentMap
     :methods {-dissoc {:jvm-name without :arities [[_ k]]}}}

    ICollection
    {:iface   clojure.lang.IPersistentCollection
     :methods {-conj  {:jvm-name cons  :arities [[_ v]]}
               -empty {:jvm-name empty :arities [[_]]}}}

    IEquiv
    {:iface   clojure.lang.IPersistentCollection
     :methods {-equiv {:jvm-name equiv :arities [[_ other]]}}
     :transform :equiv-to-equals}

    IHash
    {:iface   clojure.lang.IHashEq
     :methods {-hash {:jvm-name hasheq :arities [[_]]}}}

    ISeqable
    {:iface   clojure.lang.Seqable
     :methods {-seq {:jvm-name seq :arities [[_]]}}}

    IMeta
    {:iface   clojure.lang.IMeta
     :methods {-meta {:jvm-name meta :arities [[_]]}}}

    IWithMeta
    {:iface   clojure.lang.IObj
     :methods {-with-meta {:jvm-name withMeta :arities [[_ m]]}}}

    IFn
    {:iface   clojure.lang.IFn
     :methods {-invoke {:jvm-name invoke :arities [[_ a] [_ a b]]}}}

    IReduce
    {:iface   clojure.lang.IReduceInit
     :methods {-reduce {:jvm-name reduce :arities [[_ f init]]}}}

    IKVReduce
    {:iface   clojure.lang.IKVReduce
     :methods {-kv-reduce {:jvm-name kvreduce :arities [[_ f init]]}}}

    IIndexed
    {:iface   clojure.lang.Indexed
     :methods {-nth {:jvm-name nth :arities [[_ n] [_ n nf]]}}}

    IStack
    {:iface   clojure.lang.IPersistentStack
     :methods {-peek {:jvm-name peek :arities [[_]]}
               -pop  {:jvm-name pop  :arities [[_]]}}}

    ISeq
    {:iface   clojure.lang.ISeq
     :methods {-first {:jvm-name first :arities [[_]]}
               -rest  {:jvm-name more  :arities [[_]]}}}

    INext
    {:iface   nil
     :methods {-next {:jvm-name nil :arities [[_]]}}}

    ISequential
    {:iface   clojure.lang.Sequential
     :marker? true
     :methods {}}

    IIterable
    {:iface   java.lang.Iterable
     :methods {-iterator {:jvm-name iterator :arities [[_]]}}}

    IPrintWithWriter
    {:iface   Object
     :methods {-pr-writer {:jvm-name toString :arities [[_]]
                           :transform :pr-to-string}}}})

(defn lookup [proto-sym]
  (get cljs->jvm proto-sym))

(defn mapped? [proto-sym]
  (contains? cljs->jvm proto-sym))
