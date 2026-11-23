(ns eve.deftype-proto.vocab
  "eve's type language, the same for every host: a type's body is protocol
   impls written with ClojureScript's names (ICounted/-count, ...) or the
   JVM's (clojure.lang.Counted/count, ...), parsed here into
   [{:protocol :methods}]; the table translations between the two
   (eve.deftype-proto.proto-map); field renames and classes; and the ISlabIO
   reads and writes an eve/deftype type's bodies get. A host realizes a type
   from these in its adapter (eve.types, eve/adapters/types/)."
  (:refer-clojure :exclude [into-array unchecked-inc-int long objects])
  (:require ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.host :refer [field into-array unchecked-inc-int long objects]]
   [clojure.string :as str]
   [eve.deftype-proto.proto-map :as pm]))

;;=============================================================================
;; A body's impls
;;=============================================================================

(defn parse-protocol-impls
  "Parse body into [{:protocol sym :methods [{:name sym :args vec :body list}]}]"
  [body]
  (loop [remaining body
         current-proto nil
         result []]
    (if (empty? remaining)
      (if current-proto (conj result current-proto) result)
      (let [form (first remaining)]
        (if (and (symbol? form) (not (list? form)))
          (recur (rest remaining)
                 {:protocol form :methods []}
                 (if current-proto (conj result current-proto) result))
          (if (and (seq? form) current-proto)
            (recur (rest remaining)
                   (update current-proto :methods conj
                           {:name (first form)
                            :args (second form)
                            :body (rest (rest form))})
                   result)
            (recur (rest remaining) current-proto result)))))))

(defn user-provides-protocol? [parsed-protos proto-sym]
  (some #(= proto-sym (:protocol %)) parsed-protos))

(defn has-equiv-impl? [parsed-protos]
  (some (fn [{:keys [protocol methods]}]
          (and (= protocol 'clojure.lang.IPersistentCollection)
               (some #(= (:name %) 'equiv) methods)))
        parsed-protos))

(defn user-provides-object? [parsed-protos]
  (some #(contains? #{'Object 'java.lang.Object} (:protocol %)) parsed-protos))

(defn impls->forms
  "[{:protocol :methods}] as the flat body of a deftype or reify."
  [impls]
  (mapcat (fn [{:keys [protocol methods]}]
            (cons protocol (map (fn [{:keys [name args body]}] (list* name args body)) methods)))
          impls))

(defn interface-sym?
  "True for a JVM interface name (an impl the host's type system carries), as
   opposed to a protocol."
  [sym]
  (let [s (str sym)]
    (or (= s "Object")
        (str/starts-with? s "clojure.lang.")
        (str/starts-with? s "java."))))

(defn strip-cljs-core
  "cljs.core/ICounted → ICounted (a body may name ClojureScript's protocols
   qualified)."
  [sym]
  (if (and (symbol? sym) (= "cljs.core" (namespace sym))) (symbol (name sym)) sym))

(defn cljs-protocol?
  "True for a ClojureScript core protocol name (ICounted, IIndexed, ...)."
  [sym]
  (and (symbol? sym) (contains? pm/cljs->jvm (strip-cljs-core sym))))

;;=============================================================================
;; ClojureScript's names from the JVM's (the proto map)
;;=============================================================================

(defn cljs-translate-method
  "Translate a CLJ method to its CLJS equivalent using proto-map."
  [method-spec iface-sym]
  (let [{:keys [name]} method-spec
        mapping (pm/lookup iface-sym)]
    (if-not mapping
      ;; No mapping — pass through verbatim
      method-spec
      (let [methods-map (:methods mapping)
            method-info (get methods-map name)]
        (when method-info
          (if-let [cljs-name (:cljs-name method-info)]
            (assoc method-spec
                   :name cljs-name
                   :cljs-proto-override (or (:cljs-proto-override method-info)
                                            (:cljs-proto method-info)))
            ;; nil cljs-name means CLJ-only method, skip
            nil))))))

(defn cljs-translate-protocol
  "Translate a parsed protocol to its CLJS form.
   Returns a seq of {:protocol :methods} entries (may be >1 for split protocols)."
  [{:keys [protocol methods]}]
  (let [mapping (pm/lookup protocol)]
    (if-not mapping
      ;; No mapping — pass through verbatim
      [{:protocol protocol :methods methods}]
      (if (:clj-only? mapping)
        ;; CLJ-only: skip entirely on CLJS
        []
        (if (:marker? mapping)
          [{:protocol (:cljs-proto mapping) :methods [] :marker? true}]
          (let [translated (keep #(cljs-translate-method % protocol) methods)]
            (if (:split? mapping)
              ;; Split: group methods by their cljs-proto-override
              (let [by-proto (group-by (fn [m]
                                         (or (:cljs-proto-override m)
                                             (:cljs-proto mapping)))
                                       translated)]
                (mapv (fn [[proto ms]]
                        {:protocol proto
                         :methods (mapv #(dissoc % :cljs-proto-override) ms)})
                      by-proto))
              ;; Normal: methods with cljs-proto-override go to separate protos
              (let [{overridden true normal false}
                    (group-by #(boolean (:cljs-proto-override %)) translated)
                    result (if (seq normal)
                             [{:protocol (:cljs-proto mapping)
                               :methods (mapv #(dissoc % :cljs-proto-override) normal)}]
                             [])]
                (into result
                      (map (fn [m]
                             {:protocol (:cljs-proto-override m)
                              :methods [(dissoc m :cljs-proto-override)]})
                           overridden))))))))))

(defn cljs-object-impls
  "java.lang.Object's methods on CLJS, for the user-facing layer: toString
   stays on Object, equals is IEquiv's -equiv unless the type answers equiv
   another way (IPersistentCollection's equiv, its own IEquiv), and hashCode
   goes (IHashEq is IHash there)."
  [parsed-protos]
  ;; java.lang.Object is a JVM-named body's; a bare Object block is the
  ;; same body with the unqualified name and gets the same disposition
  ;; (its group is dropped by the proto map's Object entry, here its
  ;; toString and equals are re-homed as below)
  (let [object? #(contains? '#{java.lang.Object Object} (:protocol %))
        ms (mapcat :methods (filter object? parsed-protos))
        equiv? (some (fn [{:keys [protocol methods]}]
                       (or (= 'IEquiv protocol)
                           (and (= 'clojure.lang.IPersistentCollection protocol)
                                (some #(= 'equiv (:name %)) methods))))
                     parsed-protos)
        to-string (filter #(= 'toString (:name %)) ms)
        equals (when-not equiv? (filter #(= 'equals (:name %)) ms))]
    (concat (when (seq to-string) [{:protocol 'Object :methods (vec to-string)}])
            (when (seq equals)
              [{:protocol 'IEquiv :methods (mapv #(assoc % :name '-equiv) equals)}]))))

(defn- cljs-protocol-fn
  "The ClojureScript protocol fn JVM interface iface's method m becomes
   (cljs.core/-nth for clojure.lang.Indexed's nth), or nil."
  [iface m]
  (when-let [n (:cljs-name (get (:methods (pm/lookup iface)) m))]
    (symbol "cljs.core" (name n))))

(defn cljs-own-calls
  "parsed-protos with each body's call of one of its own type's JVM methods
   by name, (.nth this n), the protocol fn that method becomes on
   ClojureScript, (cljs.core/-nth this n): a body calls its type's own
   methods the same way on every host. A name two of the type's interfaces
   make different protocol fns of (assocN: -assoc-n and -assoc-n!) is left
   as written."
  [parsed-protos]
  (let [fns (reduce (fn [acc {:keys [protocol methods]}]
                      (reduce (fn [acc {m :name}]
                                (if-let [f (cljs-protocol-fn protocol m)]
                                  (update acc m (fnil conj #{}) f)
                                  acc))
                              acc methods))
                    {} parsed-protos)
        renames (into {} (keep (fn [[m fs]] (when (= 1 (count fs)) [m (first fs)]))) fns)
        call (fn [form]
               (when (and (seq? form) (symbol? (first form)))
                 (let [n (name (first form))]
                   (when (and (str/starts-with? n ".") (not (str/starts-with? n ".-")))
                     (get renames (symbol (subs n 1)))))))
        rw (fn rw [form]
             (cond
               (and (seq? form) (= 'quote (first form))) form
               (call form) (apply list (call form) (map rw (rest form)))
               (seq? form) (with-meta (apply list (map rw form)) (meta form))
               (vector? form) (with-meta (mapv rw form) (meta form))
               (map? form) (with-meta (into {} (map (fn [[k v]] [(rw k) (rw v)])) form) (meta form))
               :else form))]
    (mapv (fn [p] (update p :methods (fn [ms] (mapv (fn [m] (update m :body #(map rw %))) ms))))
          parsed-protos)))

(defn translate-protos
  "Protocol impls written with JVM names — [{:protocol :methods}] as
   parse-protocol-impls answers them — as the impls of host target. :cljs goes
   through the proto map (a name it does not know, a CLJS protocol or the
   user's own, passes through unchanged), java.lang.Object as
   cljs-object-impls says; :clj answers them as given. One entry per protocol,
   :marker? for a protocol with no methods. eve.deftype's user-facing deftype
   translates through this, so one body serves every host."
  [parsed-protos target]
  (if (= target :cljs)
    (->> (concat (mapcat cljs-translate-protocol parsed-protos)
                 (cljs-object-impls parsed-protos))
         (group-by :protocol)
         (mapv (fn [[p impls]]
                 {:protocol p
                  :methods (vec (mapcat :methods impls))
                  :marker? (boolean (some :marker? impls))})))
    parsed-protos))

(def jvm-supers
  "A JVM collection interface → the interfaces it extends (transitively)."
  '{clojure.lang.IPersistentCollection #{clojure.lang.Seqable}
    clojure.lang.Indexed               #{clojure.lang.Counted}
    clojure.lang.Associative           #{clojure.lang.IPersistentCollection clojure.lang.ILookup
                                         clojure.lang.Seqable}
    clojure.lang.IPersistentStack      #{clojure.lang.IPersistentCollection clojure.lang.Seqable}
    clojure.lang.IPersistentVector     #{clojure.lang.Associative clojure.lang.Sequential
                                         clojure.lang.IPersistentStack clojure.lang.Reversible
                                         clojure.lang.Indexed clojure.lang.Counted
                                         clojure.lang.IPersistentCollection clojure.lang.ILookup
                                         clojure.lang.Seqable}
    clojure.lang.IPersistentMap        #{clojure.lang.Associative clojure.lang.Counted
                                         clojure.lang.IPersistentCollection clojure.lang.ILookup
                                         clojure.lang.Seqable}
    clojure.lang.IPersistentSet        #{clojure.lang.IPersistentCollection clojure.lang.Counted
                                         clojure.lang.Seqable}
    clojure.lang.ISeq                  #{clojure.lang.IPersistentCollection clojure.lang.Seqable}
    clojure.lang.IChunkedSeq           #{clojure.lang.ISeq clojure.lang.Sequential
                                         clojure.lang.IPersistentCollection clojure.lang.Seqable}
    clojure.lang.IObj                  #{clojure.lang.IMeta}
    clojure.lang.IReduce               #{clojure.lang.IReduceInit}
    clojure.lang.ITransientAssociative #{clojure.lang.ITransientCollection clojure.lang.ILookup}
    clojure.lang.ITransientVector      #{clojure.lang.ITransientAssociative clojure.lang.Indexed
                                         clojure.lang.ITransientCollection clojure.lang.ILookup
                                         clojure.lang.Counted}
    clojure.lang.ITransientMap         #{clojure.lang.ITransientAssociative clojure.lang.Counted
                                         clojure.lang.ITransientCollection clojure.lang.ILookup}
    clojure.lang.ITransientSet         #{clojure.lang.ITransientCollection clojure.lang.Counted}
    clojure.lang.IMapEntry             #{java.util.Map$Entry}
    clojure.lang.IFn                   #{java.util.concurrent.Callable java.lang.Runnable}})

(defn jvm-method-for
  "[interface jvm-name] for a ClojureScript protocol's method, or nil."
  [proto {:keys [name args]}]
  (let [entry (get-in pm/cljs->jvm [proto name])]
    (cond (vector? entry) entry
          (map? entry) (get entry (count args)))))

(defn jvm-derived-methods
  "The methods of the implemented interfaces the body does not give, derived
   from the ones it does: [[name args body-form] ...]. has? [name arity]."
  [ifaces has?]
  (let [i? #(contains? ifaces %)
        coll? (some i? '[clojure.lang.IPersistentCollection])
        absent :eve.deftype-proto.macros/absent
        want (fn [nm arity form-fn] (when-not (has? [nm arity]) [(form-fn)]))]
    (concat
     (when (and (i? 'clojure.lang.Seqable) (i? 'clojure.lang.ISeq))
       (want 'seq 1 #(list 'seq '[this] 'this)))
     (when (or (i? 'clojure.lang.Counted) coll?)
       (want 'count 1 #(list 'count '[this]
                             '(loop [s (.seq this) i (int 0)]
                                (if s
                                  (recur (.next ^clojure.lang.ISeq s) (unchecked-inc-int i))
                                  i)))))
     (when coll?
       (concat
        (want 'empty 1 #(list 'empty '[_] 'clojure.lang.PersistentList/EMPTY))
        (want 'equiv 2 #(list 'equiv '[this other] '(identical? this other)))
        (when-not (i? 'clojure.lang.ISeq)
          (want 'cons 2 #(list 'cons '[_ _] '(throw (UnsupportedOperationException.)))))))
     (when (i? 'clojure.lang.ISeq)
       (concat
        (want 'cons 2 #(list 'cons '[this o] '(clojure.lang.Cons. o this)))
        (want 'next 1 #(list 'next '[this] '(.seq ^clojure.lang.ISeq (.more this))))
        (want 'more 1 #(list 'more '[this] '(or (.next this) clojure.lang.PersistentList/EMPTY)))))
     (when (i? 'clojure.lang.IChunkedSeq)
       (concat
        (want 'chunkedNext 1 #(list 'chunkedNext '[this]
                                    '(.seq ^clojure.lang.ISeq (.chunkedMore this))))
        (want 'chunkedMore 1 #(list 'chunkedMore '[this]
                                    '(or (.chunkedNext this) clojure.lang.PersistentList/EMPTY)))))
     (when (i? 'clojure.lang.ILookup)
       (concat
        (when (has? ['valAt 3])
          (want 'valAt 2 #(list 'valAt '[this k] '(.valAt this k nil))))
        (when (and (not (has? ['valAt 3])) (i? 'clojure.lang.Indexed))
          (concat
           (want 'valAt 2 #(list 'valAt '[this k] '(.valAt this k nil)))
           [(list 'valAt '[this k nf]
                  '(if (integer? k) (.nth this (int k) nf) nf))]))))
     (when (i? 'clojure.lang.Indexed)
       (want 'nth 3 #(list 'nth '[this i nf]
                           '(if (and (<= 0 i) (< i (.count this))) (.nth this i) nf))))
     (when (i? 'clojure.lang.Associative)
       (concat
        (want 'containsKey 2
              #(list 'containsKey '[this k]
                     (if (i? 'clojure.lang.IPersistentVector)
                       '(and (integer? k) (< -1 (long k) (.count this)))
                       (list 'not (list 'identical? absent (list '.valAt 'this 'k absent))))))
        (want 'entryAt 2
              #(list 'entryAt '[this k]
                     (list 'let ['v (list '.valAt 'this 'k absent)]
                           (list 'when-not (list 'identical? 'v absent)
                                 '(clojure.lang.MapEntry/create k v)))))
        (when (i? 'clojure.lang.IPersistentVector)
          (want 'assoc 3 #(list 'assoc '[this k v]
                                '(if (integer? k)
                                   (.assocN this (int k) v)
                                   (throw (IllegalArgumentException. "Key must be integer"))))))))
     (when (i? 'clojure.lang.IPersistentVector)
       (want 'length 1 #(list 'length '[this] '(.count this))))
     (when (i? 'clojure.lang.IFn)
       (concat
        (want 'applyTo 2 #(list 'applyTo '[this args] '(clojure.lang.AFn/applyToHelper this args)))
        (when (has? ['invoke 1])
          (concat (want 'call 1 #(list 'call '[this] '(.invoke this)))
                  (want 'run 1 #(list 'run '[this] '(do (.invoke this) nil)))))))
     (when (i? 'java.util.Map$Entry)
       (concat
        (want 'getKey 1 #(list 'getKey '[this] '(.key this)))
        (want 'getValue 1 #(list 'getValue '[this] '(.val this)))
        (want 'setValue 2 #(list 'setValue '[_ _] '(throw (UnsupportedOperationException.)))))))))

(defn jvm-translate-protos
  "A body written with ClojureScript protocol names (JVM names pass through)
   as a JVM deftype's impls. Answers {:impls [{:protocol :methods}] :print
   (fn form, from IPrintWithWriter's -pr-writer, or nil)}."
  [parsed-protos]
  (let [object? #(contains? '#{Object java.lang.Object} (:protocol %))
        {objects true others false} (group-by (comp boolean object?) parsed-protos)
        print (some (fn [{:keys [protocol methods]}]
                      (when (= 'IPrintWithWriter (strip-cljs-core protocol))
                        (let [{:keys [args body]} (first methods)]
                          (list* 'fn args body))))
                    others)
        entries
        (mapcat
         (fn [{:keys [protocol methods] :as _impl}]
           (let [p (strip-cljs-core protocol)
                 spec (get pm/cljs->jvm p)]
             (cond
               (nil? spec) [{:iface protocol :methods methods}]
               (= :marker spec) (when-let [m (get pm/cljs-marker->jvm p)]
                                  [{:iface m :methods []}])
               (#{:print :drop} spec) []
               :else
               (for [m methods
                     :let [[iface jname] (or (jvm-method-for p m)
                                             (throw (ex-info (str "No JVM method for " p "/"
                                                                  (:name m) " with "
                                                                  (count (:args m)) " args")
                                                             {:protocol p :method (:name m)})))]]
                 {:iface iface :methods [(assoc m :name jname)]}))))
         others)
        by-iface (->> entries
                      (group-by :iface)
                      (mapv (fn [[iface es]] {:protocol iface :methods (vec (mapcat :methods es))})))
        declared (set (map :protocol by-iface))
        ifaces (into declared (mapcat #(get jvm-supers %) declared))
        present (set (for [{:keys [methods]} by-iface m methods] [(:name m) (count (:args m))]))
        derived (for [[nm args & body] (jvm-derived-methods ifaces present)]
                  {:name nm :args args :body body})
        object-methods (vec (mapcat :methods objects))
        obj-has? (set (map :name object-methods))
        object-methods (cond-> object-methods
                         (and (not (obj-has? 'equals)) (present ['equiv 2]))
                         (conj {:name 'equals :args '[this other] :body '[(.equiv this other)]})
                         (and (not (obj-has? 'hashCode)) (present ['hasheq 1]))
                         (conj {:name 'hashCode :args '[this] :body '[(.hasheq this)]})
                         (and (not (obj-has? 'toString)) print)
                         (conj {:name 'toString :args '[this] :body '[(pr-str this)]}))
        ;; derived methods go under an interface entry (deftype pools them)
        iface-idx (first (keep-indexed (fn [i {:keys [protocol]}]
                                         (when (interface-sym? protocol) i))
                                       by-iface))
        impls (cond-> by-iface
                (and (seq derived) iface-idx)
                (update iface-idx update :methods into derived)
                (seq object-methods)
                (conj {:protocol 'Object :methods object-methods}))]
    {:impls impls :print print}))

(def jvm-method->core
  "(.m x args...) of a JVM method → the clojure.core call it stands for."
  '{valAt clojure.core/get, nth clojure.core/nth, count clojure.core/count, seq clojure.core/seq
    containsKey clojure.core/contains?, assoc clojure.core/assoc, assocN clojure.core/assoc
    equiv clojure.core/=, equals clojure.core/=, hasheq clojure.core/hash, hashCode clojure.core/hash
    cons clojure.core/conj, without clojure.core/dissoc, entryAt clojure.core/find, more clojure.core/rest
    next clojure.core/next, first clojure.core/first, size clojure.core/count, length clojure.core/count
    empty clojure.core/empty, peek clojure.core/peek, pop clojure.core/pop, meta clojure.core/meta
    withMeta clojure.core/with-meta, deref clojure.core/deref, key clojure.core/key, val clojure.core/val
    persistent clojure.core/persistent!, asTransient clojure.core/transient, disjoin clojure.core/disj
    toString clojure.core/str, rseq clojure.core/rseq})

;;=============================================================================
;; Fields: renames, classes, and an eve/deftype body's reads through ISlabIO
;;=============================================================================

(defn rename-syms
  "form with symbols renamed per renames (a field rename, applied to the
   bodies that read the field)."
  [form renames]
  (cond
    (symbol? form) (get renames form form)
    (seq? form) (with-meta (apply list (map #(rename-syms % renames) form)) (meta form))
    (vector? form) (with-meta (mapv #(rename-syms % renames) form) (meta form))
    (map? form) (with-meta (into {} (map (fn [[k v]] [(rename-syms k renames)
                                                      (rename-syms v renames)])
                                         form))
                  (meta form))
    :else form))

(defn host-field
  "A field symbol as the JVM or bb deftype takes it: ClojureScript's
   ^:mutable is ^:unsynchronized-mutable there."
  [sym]
  (let [m (meta sym)]
    (if (:mutable m)
      (vary-meta sym #(-> % (dissoc :mutable) (assoc :unsynchronized-mutable true)))
      sym)))

(def jvm-field-renames
  "Field names the JVM reserves for types (deftype rejects them): the name a
   field takes there. ClojureScript's types cache their hash in __hash."
  '{__hash hash__ __meta meta__ __hasheq hasheq__ __extmap extmap__})

(defn host-local? [f] (= :host (:type-class f)))

(defn ref-kind
  "The kind of what a field references, for eve.tally: :deftype for a ^:MyEveType field or an ^:int32 field
   marked as holding an instance's offset (^:ref, or ^:eve/pointer — the two spellings eve's types carry),
   :vval for a serialized one (the offset of its [len][EVE bytes] block), nil for a field that references
   nothing."
  [f]
  (case (:type-class f)
    :eve-type :deftype
    :serialized :vval
    :primitive (when (or (:ref? f) (:pointer? f)) :deftype)
    nil))

(defn cached?
  "An immutable field in eve memory, read when the instance is reached and
   kept on it (so (field x f) reads it on every host). A ^:Type reference is read
   when a method reads it instead: a node linked to nodes of its own type
   would read the whole structure."
  [f]
  (and (not (host-local? f))
       (= :immutable (:mutability f))
       (not= :eve-type (:type-class f))))

(defn live?
  "A field a method reads from eve memory each time: a mutable one, or a
   ^:Type reference."
  [f]
  (and (not (host-local? f)) (not (cached? f))))

(defn instance-field-sym
  "The instance's own field for a cached or host-local field, as the host's
   deftype takes it: a mutable host-local one with mutable-meta (the host's
   mark for a mutable field)."
  [f mutable-meta]
  (let [s (symbol (:name f))]
    (if (and (host-local? f) (= :mutable (:mutability f)))
      (with-meta s mutable-meta)
      s)))

(defn sio-read-expr
  "ISlabIO read expression for a field — the VALUE read of the
   field's declared type, agreeing with
   eve.deftype-proto/jvm-read-fn-for-type (and so with the flat
   descriptor's reads, which are defined to answer as it does):
   the signed small ints sign-extend, :uint32 masks to the
   unsigned long, the floats convert from their stored bits.
   (This generator previously emitted the raw storage read for
   every width: a body reading its own ^:int16 field got the
   unsigned value, its ^:float64 field the low word.)"
  [{:keys [type-hint type-class mutability]} offset-sym field-offset]
  (case type-class
    :primitive
    (case type-hint
      :int8    (list 'let ['b__jvm (list 'eve.slab-io/read-u8 'sio__ offset-sym field-offset)]
                     (list 'if (list '> 'b__jvm 127) (list '- 'b__jvm 256) 'b__jvm))
      :uint8   (list 'eve.slab-io/read-u8 'sio__ offset-sym field-offset)
      :int16   (list 'let ['v__jvm (list 'eve.slab-io/read-u16 'sio__ offset-sym field-offset)]
                     (list 'if (list '> 'v__jvm 32767) (list '- 'v__jvm 65536) 'v__jvm))
      :uint16  (list 'eve.slab-io/read-u16 'sio__ offset-sym field-offset)
      ;; an immutable int32 field is written once, before its instance is published: the hot read
      ;; path's plain node read (jvm-heap-grade step 3). A mutable one keeps the atomic read.
      :int32   (list (if (#{:mutable :volatile-mutable} mutability)
                       'eve.slab-io/read-i32
                       'eve.deftype-proto.alloc/node-i32)
                     'sio__ offset-sym field-offset)
      :uint32  (list 'bit-and
                     (list 'long (list 'eve.slab-io/read-i32 'sio__ offset-sym field-offset))
                     0xFFFFFFFF)
      :float32 (list 'Float/intBitsToFloat
                     (list 'int (list 'eve.slab-io/read-i32 'sio__ offset-sym field-offset)))
      :float64 (list 'let ['lo__jvm (list 'bit-and
                                          (list 'long (list 'eve.slab-io/read-i32 'sio__ offset-sym field-offset))
                                          0xFFFFFFFF)
                           'hi__jvm (list 'bit-and
                                          (list 'long (list 'eve.slab-io/read-i32 'sio__ offset-sym (+ field-offset 4)))
                                          0xFFFFFFFF)]
                     (list 'Double/longBitsToDouble
                           (list 'bit-or (list 'bit-shift-left 'hi__jvm 32) 'lo__jvm)))
      ;; an unrecognized primitive hint reads as stored int32
      (list 'eve.slab-io/read-i32 'sio__ offset-sym field-offset))
    ;; :eve-type, :serialized — stored as int32
    (list 'eve.slab-io/read-i32 'sio__ offset-sym field-offset)))

(defn sio-write-expr
  "ISlabIO write expression for a field — the VALUE write of the
   field's declared type, agreeing with
   eve.deftype-proto/jvm-write-fn-for-type (the write the
   generated ctor performs): the small ints write through an
   (int …) coercion, :int32/:uint32 and the stored-offset kinds
   write their unchecked bits, :float32 writes its raw int bits,
   :float64 writes both words of its raw long bits.
   (This generator previously emitted the raw val-expr for every
   width: a body setting its own ^:float32 or ^:float64 field
   handed the raw Double to write-i32!, which threw
   \"bit operation not supported for: class java.lang.Double\"
   at runtime — measured by the write-widths probe, Nov 15 2026;
   the int arms' raw pass happened to land the same bits.)"
  [{:keys [type-hint type-class]} offset-sym field-offset val-expr]
  (case type-class
    :primitive
    (case type-hint
      (:int8 :uint8)
      (list 'eve.slab-io/write-u8! 'sio__ offset-sym field-offset
            (list 'int val-expr))
      (:int16 :uint16)
      (list 'eve.slab-io/write-u16! 'sio__ offset-sym field-offset
            (list 'int val-expr))
      ;; its 32 bits, not a checked cast (jvm-write-fn-for-type's
      ;; note: a slab-qualified offset is past 2^31 from class 4 up)
      (:int32 :uint32)
      (list 'eve.slab-io/write-i32! 'sio__ offset-sym field-offset
            (list 'unchecked-int val-expr))
      :float32
      (list 'eve.slab-io/write-i32! 'sio__ offset-sym field-offset
            (list 'Float/floatToRawIntBits (list 'float val-expr)))
      :float64
      (list 'let ['bits__jvm (list 'Double/doubleToRawLongBits (list 'double val-expr))]
            (list 'do
                  (list 'eve.slab-io/write-i32! 'sio__ offset-sym field-offset
                        (list 'unchecked-int (list 'bit-and 'bits__jvm 0xFFFFFFFF)))
                  (list 'eve.slab-io/write-i32! 'sio__ offset-sym (+ field-offset 4)
                        (list 'unchecked-int (list 'unsigned-bit-shift-right 'bits__jvm 32)))))
      ;; an unrecognized primitive hint writes as stored int32
      (list 'eve.slab-io/write-i32! 'sio__ offset-sym field-offset
            (list 'int val-expr)))
    ;; :eve-type, :serialized — stored as int32; a slab-qualified
    ;; offset is unsigned 32-bit, so its bits, not a checked cast
    (list 'eve.slab-io/write-i32! 'sio__ offset-sym field-offset
          (list 'unchecked-int val-expr))))

(defn field-bindings
  "Generate let-bindings that read all fields from slab via ISlabIO.
   Cached fields are skipped — they are direct type fields, not slab-backed."
  [fields]
  (vec (mapcat (fn [f]
                 (when-not (:cached? f)
                   [(symbol (:name f))
                    (sio-read-expr f 'offset__ (:offset f))]))
               fields)))

(defn rewrite-set!-forms
  "Rewrite (set! field val) to ISlabIO write calls."
  [form field-map]
  (cond
    ;; set! on a field
    (and (seq? form)
         (= 'set! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          val-expr (rewrite-set!-forms (nth form 2) field-map)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (if (:cached? field-spec)
        ;; Cached fields use normal set! (they're real JVM/JS fields)
        form
        (sio-write-expr field-spec 'offset__ (:offset field-spec) val-expr)))

    ;; Recurse into forms
    (seq? form)
    (with-meta (apply list (map #(rewrite-set!-forms % field-map) form))
      (meta form))
    (vector? form)
    (with-meta (mapv #(rewrite-set!-forms % field-map) form)
      (meta form))
    (map? form)
    (with-meta (into {} (map (fn [[k v]]
                               [(rewrite-set!-forms k field-map)
                                (rewrite-set!-forms v field-map)])
                             form))
      (meta form))
    :else form))

(defn transform-method-body
  "Wrap method body with field-read let-bindings and set! rewriting."
  [body fields field-map]
  (let [field-read-bindings (field-bindings fields)
        rewritten (map #(rewrite-set!-forms % field-map) body)]
    (if (empty? field-read-bindings)
      rewritten
      (list (apply list 'let field-read-bindings (vec rewritten))))))

;;=============================================================================
;; A flat sister's bodies (FORMAT-V1 §6, lane H5): a field read is the row's
;;=============================================================================

(defn flat-rewrite-set!-forms
  "Rewrite (set! field val) and (cas! field ...) on a slab field of a
   sister method body: a flat value is immutable, so the write is a
   throw, as a flat view refuses every mutator. Writes to anything
   else (a host-local field, a local) pass through, as in the
   persistent kind."
  [form field-map]
  (cond
    (and (seq? form)
         (contains? #{'set! 'cas!} (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (list 'throw
          (list 'ex-info
                (str "a flat sister is immutable: " (first form)
                     " on a field of a flat value")
                {:field (name (second form))}))

    (seq? form)
    (with-meta (apply list (map #(flat-rewrite-set!-forms % field-map) form))
      (meta form))
    (vector? form)
    (with-meta (mapv #(flat-rewrite-set!-forms % field-map) form)
      (meta form))
    (map? form)
    (with-meta (into {} (map (fn [[k v]]
                               [(flat-rewrite-set!-forms k field-map)
                                (flat-rewrite-set!-forms v field-map)])
                             form))
      (meta form))
    :else form))

(defn flat-method-body
  "A method body of a type's generated flat sister, on every host that
   realizes one: the live fields the body names bound as locals at the
   method's entry, each read from the sister's field source
   (eve.flat.source/-field on src__, the row's column[row] — FORMAT-V1
   §6) instead of from a slab block; a set!/cas! on a field a throw.
   Only the fields the body names are read (flat lanes U4a): a field
   read is one column read at the row, not one per field of the type
   — a body naming one of four fields reads one. The names are found
   over the body's forms, so the set is a superset of what the body
   reads (a local shadowing a field still binds it, harmlessly); the
   persistent kind binds every live field, and no body can tell the
   difference."
  [body live-fields field-map]
  (let [named (into #{} (comp (filter symbol?) (map name)) (tree-seq coll? seq body))
        field-read-bindings
        (vec (mapcat (fn [f]
                       (when (contains? named (name (:name f)))
                         [(symbol (:name f))
                          (list 'eve.flat.source/-field 'src__ (keyword (:name f)))]))
                     live-fields))
        rewritten (map #(flat-rewrite-set!-forms % field-map) body)]
    (if (empty? field-read-bindings)
      rewritten
      (list (apply list 'let field-read-bindings (vec rewritten))))))

(defn clj-emit-protocols
  "Emit CLJ protocol/interface implementations.
   CLJ names pass through verbatim — no translation needed.
   Method bodies get field-read let-bindings."
  [parsed-protos fields field-map]
  (let [grouped (group-by :protocol parsed-protos)]
    (mapcat (fn [[proto impls]]
              (let [all-methods (mapcat :methods impls)
                    marker? (some (fn [imp] (empty? (:methods imp))) impls)
                    ;; Normalize java.lang.Object → Object for deftype
                    proto (if (= proto 'java.lang.Object) 'Object proto)]
                (if (and marker? (empty? all-methods))
                  [proto]
                  (cons proto
                        (map (fn [{:keys [name args body]}]
                               (let [tbody (transform-method-body body fields field-map)]
                                 (list name args (cons 'do tbody))))
                             all-methods)))))
            grouped)))
