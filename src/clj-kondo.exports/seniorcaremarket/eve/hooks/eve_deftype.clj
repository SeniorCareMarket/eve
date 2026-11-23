(ns hooks.eve-deftype
  "clj-kondo analyze-call hook for Eve's deftype family
   (eve.deftype/deftype, eve.alpha/deftype,
   eve.deftype-proto.macros/deftype and /host-deftype).

   Why a hook and not :lint-as: Eve deftype is written once with
   CLJ protocol names and realized per host by the macro — on a
   ClojureScript host the JVM names are translated through
   eve.deftype-proto.proto-map's tables, and a body written with
   CLJS names is translated the other way on the JVM. No single
   :lint-as target models both passes. The hook rewrites the call
   to a real clojure.core/deftype per pass:

   - fields exactly as written (meta stripped to plain tokens), so
     the ->Type constructor's arity — which kondo derives from the
     field count — stays as written;
   - an options map ({:print ...}), which deftype grammar has no
     place for, re-homed in a let binding just the field/residence
     names it references (its value may reference the fields);
   - protocol groups translated on each pass through the macro's
     own tables (transcribed below from
     src/eve/deftype_proto/proto_map.cljc; keep them in sync): on
     :cljs, JVM heads through jvm->cljs (split interfaces fan out
     by per-method proto; a method whose CLJS name is nil drops,
     as the macro drops it); on :clj, CLJS heads through
     cljs->jvm (IPrintWithWriter drops — JVM printing is a
     print-method, not a deftype group; its body is analyzed on
     the :cljs pass);
   - declares for the vars the macro generates that no
     deftype model knows exactly: <TypeName>? (the predicate —
     kondo's deftype generates no such var), <TypeName>-type-id
     (the (def TypeName-type-id ...) the macro emits when the
     namespace does not define it first), and ->TypeName (the
     constructor — a two-arity defn, [fields] and [sio__ fields],
     where kondo's model derives only [fields] and would
     false-flag the sio arity at every call site; the declare
     makes its arity unknown, and carries a metadata ignore for
     :redundant-declare, the one linter that objects to declaring
     a var kondo's model does generate).

   The two residence locals, sio__ and offset__, are NOT modeled
   here: Eve deftype stores them ahead of the written fields and
   method bodies reference them as locals. They are covered by the
   named :unresolved-symbol exclusion in .clj-kondo/config.edn —
   binding them in a synthetic let instead was measured to cost
   an unused-binding warning on every method that does not use
   both, and prepending them as fields falsifies the ->Type
   arity. See the config header.

   Traps, measured in scratch (hooktest2/3):
   (1) protocol groups are flat siblings in the deftype form.
       A raw vector among the deftype node's children is rejected
       by kondo's printer with \"Key must be integer\", and a
       group wrapped in a list node is read as a method form and
       collapses the analysis — groups are carried as plain
       vectors of nodes and mapcat-flattened into the form;
   (2) kondo's cache does not key on hook code — clear
       .clj-kondo/.cache after editing this file."
  (:require [clj-kondo.hooks-api :as api]))

(def jvm->cljs
  ;; proto -> {:proto cljs-proto-or-nil :clj-only? bool
  ;;           :methods {jvm-name {:name cljs-name-or-nil
  ;;                               :proto per-method-cljs-proto}}}
  '{clojure.lang.Counted {:proto ICounted :methods {count {:name -count}}}
    clojure.lang.ILookup {:proto ILookup :methods {valAt {:name -lookup}}}
    clojure.lang.Associative {:proto IAssociative
                              :methods {containsKey {:name -contains-key?}
                                        assoc {:name -assoc}}}
    clojure.lang.IPersistentMap {:proto IMap :methods {without {:name -dissoc}}}
    clojure.lang.IPersistentCollection {:proto nil
                                        :methods {cons {:name -conj :proto ICollection}
                                                  empty {:name -empty :proto IEmptyableCollection}
                                                  equiv {:name -equiv :proto IEquiv}}}
    clojure.lang.IHashEq {:proto IHash :methods {hasheq {:name -hash}}}
    clojure.lang.Seqable {:proto ISeqable :methods {seq {:name -seq}}}
    clojure.lang.IMeta {:proto IMeta :methods {meta {:name -meta}}}
    clojure.lang.IObj {:proto IWithMeta :methods {withMeta {:name -with-meta}}}
    clojure.lang.IFn {:proto IFn :methods {invoke {:name -invoke}}}
    clojure.lang.IReduce {:proto IReduce :methods {reduce {:name -reduce}}}
    clojure.lang.IReduceInit {:proto IReduce :methods {reduce {:name -reduce}}}
    clojure.lang.IKVReduce {:proto IKVReduce :methods {kvreduce {:name -kv-reduce}}}
    clojure.lang.Indexed {:proto IIndexed :methods {nth {:name -nth}}}
    clojure.lang.IPersistentStack {:proto IStack :methods {peek {:name -peek} pop {:name -pop}}}
    clojure.lang.IPersistentVector {:proto IVector :methods {assocN {:name -assoc-n}}}
    clojure.lang.Reversible {:proto IReversible :methods {rseq {:name -rseq}}}
    clojure.lang.IDeref {:proto IDeref :methods {deref {:name -deref}}}
    clojure.lang.IPending {:proto IPending :methods {isRealized {:name -realized?}}}
    clojure.lang.IRef {:proto nil
                       :methods {addWatch {:name -add-watch :proto IWatchable}
                                 removeWatch {:name -remove-watch :proto IWatchable}
                                 setValidator {:name -set-validator! :proto eve.core/IRef}
                                 getValidator {:name -get-validator :proto eve.core/IRef}
                                 getWatches {:name -get-watches :proto eve.core/IRef}}}
    clojure.lang.IAtom {:proto nil
                        :methods {swap {:name -swap! :proto ISwap}
                                  reset {:name -reset! :proto IReset}
                                  compareAndSet {:name nil}}}
    clojure.lang.IAtom2 {:proto nil
                         :methods {swapVals {:name nil} resetVals {:name nil}}}
    clojure.lang.IEditableCollection {:proto IEditableCollection
                                      :methods {asTransient {:name -as-transient}}}
    clojure.lang.ITransientCollection {:proto ITransientCollection
                                       :methods {conj {:name -conj!}
                                                 persistent {:name -persistent!}}}
    clojure.lang.ITransientAssociative {:proto nil
                                        :methods {assoc {:name -assoc! :proto ITransientAssociative}
                                                  conj {:name -conj! :proto ITransientCollection}
                                                  persistent {:name -persistent! :proto ITransientCollection}}}
    clojure.lang.ITransientMap {:proto nil
                                :methods {without {:name -dissoc! :proto ITransientMap}
                                          assoc {:name -assoc! :proto ITransientAssociative}
                                          conj {:name -conj! :proto ITransientCollection}
                                          persistent {:name -persistent! :proto ITransientCollection}
                                          count {:name -count :proto ICounted}}}
    clojure.lang.ITransientVector {:proto nil
                                   :methods {assocN {:name -assoc-n! :proto ITransientVector}
                                             pop {:name -pop! :proto ITransientVector}
                                             assoc {:name -assoc! :proto ITransientAssociative}
                                             conj {:name -conj! :proto ITransientCollection}
                                             persistent {:name -persistent! :proto ITransientCollection}}}
    clojure.lang.ITransientSet {:proto nil
                                :methods {disjoin {:name -disjoin! :proto ITransientSet}
                                          conj {:name -conj! :proto ITransientCollection}
                                          persistent {:name -persistent! :proto ITransientCollection}
                                          count {:name -count :proto ICounted}
                                          get {:name nil}
                                          contains {:name nil}}}
    clojure.lang.IPersistentSet {:proto ISet
                                 :methods {disjoin {:name -disjoin}
                                           get {:name nil}
                                           contains {:name nil}}}
    clojure.lang.Sorted {:proto ISorted
                         :methods {comparator {:name nil}
                                   entryKey {:name nil}
                                   seqFrom {:name -sorted-seq-from}}}
    clojure.lang.ISeq {:proto ISeq
                       :methods {first {:name -first}
                                 more {:name -rest}
                                 next {:name -next :proto INext}}}
    clojure.lang.IMapEntry {:proto IMapEntry :methods {key {:name -key} val {:name -val}}}
    clojure.lang.Sequential {:proto ISequential :methods {}}
    clojure.lang.MapEquivalence {:proto nil :clj-only? true :methods {}}
    clojure.lang.IPersistentList {:proto nil :clj-only? true :methods {}}
    java.lang.Iterable {:proto nil :clj-only? true :methods {iterator {:name nil}}}
    java.lang.Object {:proto nil :clj-only? true
                      :methods {toString {:name nil} equals {:name nil} hashCode {:name nil}}}
    Object {:proto nil :clj-only? true
            :methods {toString {:name nil} equals {:name nil} hashCode {:name nil}}}
    java.lang.Comparable {:proto IComparable :methods {compareTo {:name -compare}}}
    java.util.Set {:proto nil :clj-only? true :methods {}}
    java.util.Map {:proto nil :clj-only? true :methods {}}
    java.util.Collection {:proto nil :clj-only? true :methods {}}
    java.util.List {:proto nil :clj-only? true
                    :methods {size {:name nil} isEmpty {:name nil} get {:name nil}
                              indexOf {:name nil} lastIndexOf {:name nil}
                              toArray {:name nil} contains {:name nil}
                              containsAll {:name nil} subList {:name nil}
                              listIterator {:name nil} set {:name nil}
                              add {:name nil} remove {:name nil} addAll {:name nil}
                              removeAll {:name nil} retainAll {:name nil}
                              clear {:name nil}}}})

(def cljs->jvm
  ;; cljs protocol -> {cljs-method [jvm-interface jvm-method]}, an
  ;; arity map {n [iface name]} where arities split interfaces,
  ;; or :marker/:print/:drop (proto_map's own dispositions).
  '{ICounted {-count [clojure.lang.Counted count]}
    IIndexed {-nth [clojure.lang.Indexed nth]}
    ILookup {-lookup [clojure.lang.ILookup valAt]}
    ISeqable {-seq [clojure.lang.Seqable seq]}
    ICollection {-conj [clojure.lang.IPersistentCollection cons]}
    IEmptyableCollection {-empty [clojure.lang.IPersistentCollection empty]}
    IEquiv {-equiv [clojure.lang.IPersistentCollection equiv]}
    IHash {-hash [clojure.lang.IHashEq hasheq]}
    IMeta {-meta [clojure.lang.IMeta meta]}
    IWithMeta {-with-meta [clojure.lang.IObj withMeta]}
    IFn {-invoke [clojure.lang.IFn invoke]}
    IReduce {-reduce {2 [clojure.lang.IReduce reduce]
                      3 [clojure.lang.IReduceInit reduce]}}
    IKVReduce {-kv-reduce [clojure.lang.IKVReduce kvreduce]}
    IAssociative {-assoc [clojure.lang.Associative assoc]
                  -contains-key? [clojure.lang.Associative containsKey]}
    IMap {-dissoc [clojure.lang.IPersistentMap without]}
    ISet {-disjoin [clojure.lang.IPersistentSet disjoin]}
    IStack {-peek [clojure.lang.IPersistentStack peek]
            -pop [clojure.lang.IPersistentStack pop]}
    IVector {-assoc-n [clojure.lang.IPersistentVector assocN]}
    ISeq {-first [clojure.lang.ISeq first]
          -rest [clojure.lang.ISeq more]}
    INext {-next [clojure.lang.ISeq next]}
    IReversible {-rseq [clojure.lang.Reversible rseq]}
    IComparable {-compare [java.lang.Comparable compareTo]}
    IEditableCollection {-as-transient [clojure.lang.IEditableCollection asTransient]}
    ITransientCollection {-conj! [clojure.lang.ITransientCollection conj]
                          -persistent! [clojure.lang.ITransientCollection persistent]}
    ITransientAssociative {-assoc! [clojure.lang.ITransientAssociative assoc]}
    ITransientMap {-dissoc! [clojure.lang.ITransientMap without]}
    ITransientVector {-assoc-n! [clojure.lang.ITransientVector assocN]
                      -pop! [clojure.lang.ITransientVector pop]}
    ITransientSet {-disjoin! [clojure.lang.ITransientSet disjoin]}
    IChunkedSeq {-chunked-first [clojure.lang.IChunkedSeq chunkedFirst]
                 -chunked-rest [clojure.lang.IChunkedSeq chunkedMore]}
    IChunkedNext {-chunked-next [clojure.lang.IChunkedSeq chunkedNext]}
    IMapEntry {-key [clojure.lang.IMapEntry key]
               -val [clojure.lang.IMapEntry val]}
    IDeref {-deref [clojure.lang.IDeref deref]}
    IPending {-realized? [clojure.lang.IPending isRealized]}
    IRef {-set-validator! [clojure.lang.IRef setValidator]
          -get-validator [clojure.lang.IRef getValidator]
          -get-watches [clojure.lang.IRef getWatches]}
    INamed {-name [clojure.lang.Named getName]
            -namespace [clojure.lang.Named getNamespace]}
    IIterable {-iterator [java.lang.Iterable iterator]}
    ISequential :marker
    ASeq :marker-nil
    IPrintWithWriter :print
    ICloneable :drop})

(defn- groups
  "Split a deftype body (after name/fields/opts) into protocol
   groups: each group is a plain vector [head-token &
   method-list-nodes]. Groups become list NODEs only at the end —
   a raw vector among the deftype node's children is what
   kondo's printer rejects with \"Key must be integer\"."
  [body]
  (loop [xs (seq body), cur nil, acc []]
    (if (nil? xs)
      (cond-> acc cur (conj cur))
      (let [x (first xs)]
        (if (api/token-node? x)
          (recur (next xs) [x] (cond-> acc cur (conj cur)))
          (recur (next xs) (conj cur x) acc))))))

(defn- rename-method [new-name m]
  (api/list-node (into [(api/token-node new-name)] (rest (:children m)))))

(defn- translate-group-cljs
  "One protocol group on the :cljs pass -> a seq of groups (each
   a plain vector of nodes): a split interface fans out; a
   dropped one is empty."
  [[head-node & method-nodes]]
  (let [head (api/sexpr head-node)
        t (get jvm->cljs head)]
    (cond
      ;; A namespaced head (eve.core/IRef, eve.foo/IProto) or a
      ;; non-dotted head (a CLJS protocol written directly, an Eve
      ;; protocol) passes through as written.
      (or (not (symbol? head)) (namespace head) (not (re-find #"\." (str head))))
      [(into [head-node] method-nodes)]

      ;; A JVM class with no entry in Eve's table has no CLJS home
      ;; the macro can realize; the macro drops it, and so do we.
      (nil? t) []

      (:clj-only? t) []

      (:proto t)
      [(into [(api/token-node (:proto t))]
             (keep (fn [m]
                     (let [mt (get (:methods t) (api/sexpr (first (:children m))))]
                       (when (:name mt) (rename-method (:name mt) m))))
                   method-nodes))]

      ;; Split interface: fan methods out by their per-method proto.
      :else
      (let [by-proto (reduce (fn [acc m]
                               (let [mt (get (:methods t) (api/sexpr (first (:children m))))]
                                 (if (:name mt)
                                   (update acc (:proto mt) (fnil conj [])
                                           (rename-method (:name mt) m))
                                   acc)))
                             {} method-nodes)]
        (map (fn [[p ms]] (into [(api/token-node p)] ms)) by-proto)))))

(defn- translate-group-clj
  "One protocol group on the :clj pass -> a seq of groups. JVM
   heads and Eve protocols pass through; a CLJS head is
   translated back through cljs->jvm, fanning out where one CLJS
   protocol's methods live on several JVM interfaces."
  [[head-node & method-nodes]]
  (let [head (api/sexpr head-node)
        t (when (and (symbol? head) (not (namespace head))) (get cljs->jvm head))]
    (cond
      (nil? t) [(into [head-node] method-nodes)]
      (= :print t) []
      (= :drop t) []
      (= :marker t) [[(api/token-node 'clojure.lang.Sequential)]]
      (= :marker-nil t) []
      :else
      (let [by-iface (reduce (fn [acc m]
                               (let [entry (get t (api/sexpr (first (:children m))))
                                     entry (if (map? entry)
                                             (get entry (count (:children (second (:children m)))))
                                             entry)]
                                 (if entry
                                   (update acc (first entry) (fnil conj [])
                                           (rename-method (second entry) m))
                                   acc)))
                             {} method-nodes)]
        (map (fn [[iface ms]] (into [(api/token-node iface)] ms)) by-iface)))))

(defn- referenced-names
  "The subset of `names` that appear as symbols anywhere in the
   node's sexpr."
  [node names]
  (let [syms (into #{} (filter symbol?) (tree-seq coll? seq (api/sexpr node)))]
    (filter syms names)))

(defn deftype [{:keys [node lang]}]
  (let [children (:children node)
        [_ type-name fields & body] children
        body (vec body)
        opts (when (and (seq body) (api/map-node? (first body))) (first body))
        specs (if opts (rest body) body)
        field-names (map api/sexpr (:children fields))
        new-fields (api/vector-node
                    (map (fn [f] (if (= :meta (api/tag f))
                                   (api/token-node (api/sexpr f))
                                   f))
                         (:children fields)))
        ;; Protocol groups are FLAT in a deftype form — head token
        ;; and method lists are siblings, never a sublist — so the
        ;; translated groups are mapcat-flattened back to that
        ;; shape. (Wrapping a group in a list node makes kondo read
        ;; it as a method form and the whole spec collapses.)
        ;; mapcat over the groups yields the group vectors;
        ;; a second mapcat flattens them to the flat node seq —
        ;; a plain vector left among the children would be
        ;; coerced by api/list-node into a vector NODE (the
        ;; "Key must be integer" death).
        new-specs (mapcat (fn [g]
                            (mapcat identity
                                    ((if (= lang :cljs)
                                       translate-group-cljs
                                       translate-group-clj) g)))
                          (groups specs))
        deftype-node (api/list-node
                      (into [(api/token-node 'clojure.core/deftype) type-name new-fields]
                            new-specs))
        ;; The macro's ->Type constructor is a defn of two
        ;; arities, [fields] and [sio__ fields] (sio.cljc's
        ;; emission); kondo's deftype model derives only [fields]
        ;; and would false-flag the sio arity at every call site.
        ;; A declare makes the var's arity unknown instead — the
        ;; exact-arity alternative (a synthetic defn) costs a
        ;; redefined-var warning per type. The declare reads as
        ;; redundant to kondo (its model does generate ->Type), so
        ;; it carries a targeted ignore for exactly that linter.
        ctor-node (with-meta
                    (api/list-node
                     [(api/token-node 'declare)
                      (api/token-node (symbol (str "->" (api/sexpr type-name))))])
                    {:clj-kondo/ignore [:redundant-declare]})
        pred-node (api/list-node
                   [(api/token-node 'declare)
                    (api/token-node (symbol (str (api/sexpr type-name) "?")))])
        ;; The macro also emits (def TypeName-type-id ...) in the
        ;; namespace (deftype-proto.macros, the derived type-id)
        ;; when the namespace does not already define it; consumers
        ;; name it (ds11-fixture's Box-type-id is read by
        ;; eve.user-ns-root-test). Kondo's deftype model generates
        ;; no such var, so declare it like the predicate.
        type-id-node (api/list-node
                      [(api/token-node 'declare)
                       (api/token-node (symbol (str (api/sexpr type-name) "-type-id")))])
        ;; The macro also emits (defn TypeName-offset [inst] ...)
        ;; — the field-offset accessor eve.sorted and the flat
        ;; machinery call (deftype-proto.macros, the sio emission).
        offset-node (api/list-node
                     [(api/token-node 'declare)
                      (api/token-node (symbol (str (api/sexpr type-name) "-offset")))])
        opts-node (when opts
                    (let [binds (referenced-names opts (into ['sio__ 'offset__] field-names))]
                      (api/list-node
                       [(api/token-node 'let)
                        (api/vector-node (mapcat (fn [f] [(api/token-node f) (api/token-node 'nil)])
                                                 binds))
                        opts])))]
    {:node (api/list-node
            (into [(api/token-node 'do) deftype-node ctor-node pred-node type-id-node]
                  (when opts-node [opts-node])))}))
