(ns eve.deftype-proto.proto-map
  "CLJ → CLJS protocol mapping table.

   Maps JVM interface/class symbols to CLJS protocol + method translations.
   Users write CLJ-style names (clojure.lang.Counted, count). On CLJS, the
   macro translates to CLJS equivalents (ICounted, -count)."
  (:require [eve.host]))

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

    clojure.lang.IReduce
    {:cljs-proto IReduce
     :methods {reduce {:cljs-name -reduce :arities [[_ f]]}}}

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

    clojure.lang.Reversible
    {:cljs-proto IReversible
     :methods {rseq {:cljs-name -rseq :arities [[_]]}}}

    ;; --- references ---

    clojure.lang.IDeref
    {:cljs-proto IDeref
     :methods {deref {:cljs-name -deref :arities [[_]]}}}

    ;; (compare-and-set! on ClojureScript is its own Atom's; eve's atom has
    ;; eve.atom/compare-and-set!*)
    clojure.lang.IAtom
    {:cljs-proto nil
     :split? true
     :methods {swap          {:cljs-name -swap!  :cljs-proto ISwap  :arities [[_ f] [_ f a] [_ f a b] [_ f a b xs]]}
               reset         {:cljs-name -reset! :cljs-proto IReset :arities [[_ v]]}
               compareAndSet {:cljs-name nil :arities [[_ o n]]}}}

    ;; --- transients: a JVM transient interface carries the methods it
    ;; inherits, which split across ClojureScript's transient protocols ---

    clojure.lang.IEditableCollection
    {:cljs-proto IEditableCollection
     :methods {asTransient {:cljs-name -as-transient :arities [[_]]}}}

    clojure.lang.ITransientCollection
    {:cljs-proto ITransientCollection
     :methods {conj       {:cljs-name -conj!       :arities [[_ v]]}
               persistent {:cljs-name -persistent! :arities [[_]]}}}

    clojure.lang.ITransientAssociative
    {:cljs-proto nil
     :split? true
     :methods {assoc      {:cljs-name -assoc!      :cljs-proto ITransientAssociative :arities [[_ k v]]}
               conj       {:cljs-name -conj!       :cljs-proto ITransientCollection  :arities [[_ v]]}
               persistent {:cljs-name -persistent! :cljs-proto ITransientCollection  :arities [[_]]}}}

    clojure.lang.ITransientMap
    {:cljs-proto nil
     :split? true
     :methods {without    {:cljs-name -dissoc!     :cljs-proto ITransientMap         :arities [[_ k]]}
               assoc      {:cljs-name -assoc!      :cljs-proto ITransientAssociative :arities [[_ k v]]}
               conj       {:cljs-name -conj!       :cljs-proto ITransientCollection  :arities [[_ v]]}
               persistent {:cljs-name -persistent! :cljs-proto ITransientCollection  :arities [[_]]}
               count      {:cljs-name -count       :cljs-proto ICounted              :arities [[_]]}}}

    clojure.lang.ITransientVector
    {:cljs-proto nil
     :split? true
     :methods {assocN     {:cljs-name -assoc-n!    :cljs-proto ITransientVector      :arities [[_ n v]]}
               pop        {:cljs-name -pop!        :cljs-proto ITransientVector      :arities [[_]]}
               assoc      {:cljs-name -assoc!      :cljs-proto ITransientAssociative :arities [[_ k v]]}
               conj       {:cljs-name -conj!       :cljs-proto ITransientCollection  :arities [[_ v]]}
               persistent {:cljs-name -persistent! :cljs-proto ITransientCollection  :arities [[_]]}}}

    clojure.lang.ITransientSet
    {:cljs-proto nil
     :split? true
     :methods {disjoin    {:cljs-name -disjoin!    :cljs-proto ITransientSet         :arities [[_ v]]}
               conj       {:cljs-name -conj!       :cljs-proto ITransientCollection  :arities [[_ v]]}
               persistent {:cljs-name -persistent! :cljs-proto ITransientCollection  :arities [[_]]}
               count      {:cljs-name -count       :cljs-proto ICounted              :arities [[_]]}
               ;; (get and contains: a transient set reaches them through ILookup)
               get        {:cljs-name nil :arities [[_ v]]}
               contains   {:cljs-name nil :arities [[_ v]]}}}

    clojure.lang.IPersistentSet
    {:cljs-proto ISet
     ;; CLJS's ISet has -disjoin alone: get and contains? reach a set through
     ;; ILookup there, so the JVM's get and contains are dropped on CLJS
     :methods {disjoin  {:cljs-name -disjoin :arities [[_ v]]}
               get      {:cljs-name nil      :arities [[_ v]]}
               contains {:cljs-name nil      :arities [[_ v]]}}}

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
               hashCode {:cljs-name nil :arities [[_]]}}}

    java.util.Set
    {:cljs-proto nil
     :clj-only? true
     :methods {}}

    java.util.Map
    {:cljs-proto nil
     :clj-only? true
     :methods {}}

    java.util.Collection
    {:cljs-proto nil
     :clj-only? true
     :methods {}}

    java.util.List
    {:cljs-proto nil
     :clj-only? true
     :methods {size          {:cljs-name nil :arities [[_]]}
               isEmpty       {:cljs-name nil :arities [[_]]}
               get           {:cljs-name nil :arities [[_ i]]}
               indexOf       {:cljs-name nil :arities [[_ o]]}
               lastIndexOf   {:cljs-name nil :arities [[_ o]]}
               toArray       {:cljs-name nil :arities [[_]]}
               contains      {:cljs-name nil :arities [[_ o]]}
               containsAll   {:cljs-name nil :arities [[_ c]]}
               subList       {:cljs-name nil :arities [[_ from to]]}
               listIterator  {:cljs-name nil :arities [[_] [_ i]]}
               set           {:cljs-name nil :arities [[_ _ _]]}
               add           {:cljs-name nil :arities [[_ _] [_ _ _]]}
               remove        {:cljs-name nil :arities [[_ _]]}
               addAll        {:cljs-name nil :arities [[_ _] [_ _ _]]}
               removeAll     {:cljs-name nil :arities [[_ _]]}
               retainAll     {:cljs-name nil :arities [[_ _]]}
               clear         {:cljs-name nil :arities [[_]]}}}})

(defn lookup [iface-sym]
  (get jvm->cljs iface-sym))


;;-----------------------------------------------------------------------------
;; CLJS → JVM: a type written with ClojureScript's protocol names, on the JVM
;;-----------------------------------------------------------------------------

(def cljs->jvm
  "ClojureScript protocol → {method → [JVM interface, JVM method]}, for a type
   whose body is written with ClojureScript's names. A method keyed by arity
   ({2 [...] 3 [...]}, `this` counted) maps its arities to different
   interfaces. :marker names the JVM marker interface (nil: none); :print is
   printing (print-method on the JVM); :drop has no JVM counterpart."
  '{ICounted              {-count [clojure.lang.Counted count]}
    IIndexed              {-nth [clojure.lang.Indexed nth]}
    ILookup               {-lookup [clojure.lang.ILookup valAt]}
    ISeqable              {-seq [clojure.lang.Seqable seq]}
    ICollection           {-conj [clojure.lang.IPersistentCollection cons]}
    IEmptyableCollection  {-empty [clojure.lang.IPersistentCollection empty]}
    IEquiv                {-equiv [clojure.lang.IPersistentCollection equiv]}
    IHash                 {-hash [clojure.lang.IHashEq hasheq]}
    IMeta                 {-meta [clojure.lang.IMeta meta]}
    IWithMeta             {-with-meta [clojure.lang.IObj withMeta]}
    IFn                   {-invoke [clojure.lang.IFn invoke]}
    IReduce               {-reduce {2 [clojure.lang.IReduce reduce]
                                    3 [clojure.lang.IReduceInit reduce]}}
    IKVReduce             {-kv-reduce [clojure.lang.IKVReduce kvreduce]}
    IAssociative          {-assoc [clojure.lang.Associative assoc]
                           -contains-key? [clojure.lang.Associative containsKey]}
    IMap                  {-dissoc [clojure.lang.IPersistentMap without]}
    ISet                  {-disjoin [clojure.lang.IPersistentSet disjoin]}
    IStack                {-peek [clojure.lang.IPersistentStack peek]
                           -pop [clojure.lang.IPersistentStack pop]}
    IVector               {-assoc-n [clojure.lang.IPersistentVector assocN]}
    ISeq                  {-first [clojure.lang.ISeq first]
                           -rest [clojure.lang.ISeq more]}
    INext                 {-next [clojure.lang.ISeq next]}
    IReversible           {-rseq [clojure.lang.Reversible rseq]}
    IComparable           {-compare [java.lang.Comparable compareTo]}
    IEditableCollection   {-as-transient [clojure.lang.IEditableCollection asTransient]}
    ITransientCollection  {-conj! [clojure.lang.ITransientCollection conj]
                           -persistent! [clojure.lang.ITransientCollection persistent]}
    ITransientAssociative {-assoc! [clojure.lang.ITransientAssociative assoc]}
    ITransientMap         {-dissoc! [clojure.lang.ITransientMap without]}
    ITransientVector      {-assoc-n! [clojure.lang.ITransientVector assocN]
                           -pop! [clojure.lang.ITransientVector pop]}
    ITransientSet         {-disjoin! [clojure.lang.ITransientSet disjoin]}
    IChunkedSeq           {-chunked-first [clojure.lang.IChunkedSeq chunkedFirst]
                           -chunked-rest [clojure.lang.IChunkedSeq chunkedMore]}
    IChunkedNext          {-chunked-next [clojure.lang.IChunkedSeq chunkedNext]}
    IMapEntry             {-key [clojure.lang.IMapEntry key]
                           -val [clojure.lang.IMapEntry val]}
    IDeref                {-deref [clojure.lang.IDeref deref]}
    IPending              {-realized? [clojure.lang.IPending isRealized]}
    INamed                {-name [clojure.lang.Named getName]
                           -namespace [clojure.lang.Named getNamespace]}
    IIterable             {-iterator [java.lang.Iterable iterator]}
    ISequential           :marker
    ASeq                  :marker
    IPrintWithWriter      :print
    ICloneable            :drop})

(def cljs-marker->jvm
  '{ISequential clojure.lang.Sequential
    ASeq        nil})
