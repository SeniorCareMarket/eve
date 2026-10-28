(ns eve.types
  "eve's types on babashka: sci's deftype takes protocols, Object and
   IPersistentMap — nothing else (the babashka book, 'Differences with
   Clojure'; measured on bb 1.13.226). So a type that is not a map is a
   deftype whose interfaces are translated onto eve.core's protocols, and a
   map type keeps the map interfaces sci accepts.
   The entry points eve's macros call (every eve.types has them):
     host-type, plain-type, slab-type, eve-type, extend-eve-type,
     existing-def, rename-fields, reader-of, print-form, vector-types."
  (:require [clojure.string :as str]
            [eve.core]
            [eve.deftype-proto.vocab :as v :refer [impls->forms interface-sym? strip-cljs-core
                                                 cljs-protocol? rename-syms parse-protocol-impls
                                                 cljs-translate-protocol]]
            [eve.deftype-proto.sio :as sio]))

;; Babashka: a non-map type is a plain deftype over eve.core's protocols
;; babashka's deftype takes protocols, Object and IPersistentMap — nothing else
;; (the babashka book, "Differences with Clojure"; measured on bb 1.13.226).
;; So on bb a type that is not a map is a deftype whose interface impls are
;; translated, as for ClojureScript, onto eve.core's protocols (ICounted/-count,
;; IIndexed/-nth, ...): eve.core/count and friends answer through them there,
;; and clojure.core's own count/nth do not see the type (bb's limit). Fields,
;; T., instance?, =, hash and str are bb's own. A map type keeps the map
;; interfaces (bb-filter-protos), as EveHashMap does.

;; What sci's deftype rejects even in map mode (a deftype listing
;; clojure.lang.IPersistentMap): these entries are dropped from the expansion
;; on bb, so the JVM map type loads there. Non-map eve types (sci rejects
;; IPersistentVector, IPersistentSet, ISeq) are deftypes over protocols,
;; translated onto eve.core's protocols (bb-translate-protos).
(def ^:private bb-rejected-protocols
  '#{clojure.lang.IReduceInit clojure.lang.IReduce clojure.lang.IEditableCollection
     clojure.lang.ITransientMap clojure.lang.ITransientCollection
     java.util.List java.util.Set java.util.Collection})

(def ^:private bb-rejected-object-methods '#{equals hashCode})

(def ^:private bb-map-protocols
  "A rejected interface a map type answers through eve.core's protocol
   instead: its transient (eve.core/transient reaches it; dropped, an eve map
   had none on bb — eve.core's fallback handed it to clojure.core/transient,
   which refused it)."
  '{clojure.lang.IEditableCollection [eve.core/IEditableCollection {asTransient -as-transient}]})

(defn- bb-filter-protos
  "The protocol impls sci's deftype accepts: drops the rejected interfaces and
   Object's equals/hashCode (not allowed on a sci type), and puts those
   bb-map-protocols names onto eve.core's protocols."
  [parsed-protos]
  (keep (fn [{:keys [protocol methods] :as impl}]
          (cond
            (contains? bb-map-protocols protocol)
            (let [[p names] (bb-map-protocols protocol)]
              (assoc impl :protocol p :methods (mapv #(update % :name names) methods)))
            (contains? bb-rejected-protocols protocol) nil
            (contains? #{'Object 'java.lang.Object} protocol)
            (let [ms (remove #(contains? bb-rejected-object-methods (:name %)) methods)]
              (when (seq ms) (assoc impl :methods (vec ms))))
            :else impl))
        parsed-protos))

(def ^:private eve-core-protocols
  "The protocols eve.core defines on bb (ClojureScript's names)."
  '#{ICounted IIndexed ILookup ISeqable ICollection IEmptyableCollection IEquiv
     IHash IMeta IWithMeta IFn IReduce IKVReduce IAssociative IMap ISet IStack
     IVector ISeq INext ISequential ASeq IReversible IComparable
     IEditableCollection ITransientCollection ITransientAssociative ITransientMap
     ITransientVector ITransientSet IChunkedSeq IChunkedNext IMapEntry IDeref
     IPrintWithWriter})

(def ^:private self-call->eve-core
  "A method a body calls on its own instance, (.m this ...), and the eve.core
   protocol fn that answers it on bb (where the type has no such method)."
  '{seq eve.core/-seq count eve.core/-count nth eve.core/-nth
    valAt eve.core/-lookup equiv eve.core/-equiv hasheq eve.core/-hash
    cons eve.core/-conj empty eve.core/-empty invoke eve.core/-invoke
    meta eve.core/-meta withMeta eve.core/-with-meta disjoin eve.core/-disjoin
    reduce eve.core/-reduce kvreduce eve.core/-kv-reduce
    containsKey eve.core/-contains-key? assoc eve.core/-assoc without eve.core/-dissoc
    peek eve.core/-peek pop eve.core/-pop assocN eve.core/-assoc-n})

(defn- rewrite-self-calls
  "(.m this args...) → (eve.core/-m' this args...) for the methods above."
  [form this-sym]
  (let [m (when (and (seq? form) (symbol? (first form)) (= this-sym (second form)))
            (let [n (name (first form))]
              (when (and (str/starts-with? n ".") (not (str/starts-with? n ".-")))
                (self-call->eve-core (symbol (subs n 1))))))]
    (cond
      m (apply list m this-sym (map #(rewrite-self-calls % this-sym) (nnext form)))
      (seq? form) (with-meta (apply list (map #(rewrite-self-calls % this-sym) form)) (meta form))
      (vector? form) (with-meta (mapv #(rewrite-self-calls % this-sym) form) (meta form))
      (map? form) (with-meta (into {} (map (fn [[k v]] [(rewrite-self-calls k this-sym)
                                                        (rewrite-self-calls v this-sym)])
                                           form))
                    (meta form))
      :else form)))

(def ^:private sci-native-interfaces
  "The JVM interfaces sci's deftype carries itself (deref, swap!, reset! and
   compare-and-set! reach them): kept as written."
  '#{clojure.lang.IDeref clojure.lang.IAtom})

(defn- sci-swap-arities
  "IAtom's swap as sci calls it: it spreads swap!'s extra arguments flat over
   the method's arity (measured: (swap! a f x y z) reaches [_ f a b xs] with
   xs = z), so the JVM's [this f a b xs] (xs: the rest) becomes one arity per
   count of extra arguments, xs their list."
  [methods]
  (mapcat (fn [{:keys [name args body] :as m}]
            (if (and (= 'swap name) (= 5 (count args)))
              (let [[this f a b xs] args]
                (for [k (range 1 5)]
                  (let [extra (vec (repeatedly k #(gensym "x")))]
                    {:name 'swap :args (into [this f a b] extra)
                     :body [(list* 'clojure.core/let [xs (list* 'clojure.core/list extra)] body)]})))
              [m]))
          methods))

(defn bb-translate-protos
  "A non-map type's impls ([{:protocol :methods}], JVM names) as a bb deftype
   takes them: interfaces translated onto eve.core's protocols (through the
   ClojureScript table; ones without an eve.core protocol dropped), the type's
   own protocols as written, and Object's toString/equals/hashCode — equals
   and hashCode the type's equiv and hasheq where it has them, so = and hash
   agree with them. Self-calls in bodies go through eve.core."
  [parsed-protos type-name]
  (let [object? #(contains? '#{Object java.lang.Object} (:protocol %))
        rewrite (fn [m] (update m :body (fn [body] (map #(rewrite-self-calls % (first (:args m))) body))))
        objects (mapcat :methods (filter object? parsed-protos))
        translated (->> (remove object? parsed-protos)
                        (mapcat (fn [impl]
                                  (cond
                                    (contains? sci-native-interfaces (:protocol impl))
                                    [(update impl :methods sci-swap-arities)]

                                    (interface-sym? (:protocol impl))
                                    (keep (fn [{:keys [protocol] :as t}]
                                            (when (contains? eve-core-protocols protocol)
                                              (assoc t :protocol (symbol "eve.core" (name protocol)))))
                                          (cljs-translate-protocol impl))
                                    ;; a ClojureScript protocol: eve.core's of that name
                                    (cljs-protocol? (:protocol impl))
                                    (let [p (strip-cljs-core (:protocol impl))]
                                      (when (contains? eve-core-protocols p)
                                        [(assoc impl :protocol (symbol "eve.core" (name p)))]))
                                    :else [impl])))
                        (group-by :protocol)
                        (mapv (fn [[p impls]] {:protocol p :methods (vec (mapcat :methods impls))})))
        has? (fn [p] (some #(= p (:protocol %)) translated))
        by-name (group-by :name objects)
        to-string (or (get by-name 'toString)
                      [{:name 'toString :args '[_] :body [(str "#eve/" (name type-name))]}])
        ;; equals and hashCode are the type's equiv and hasheq where it has
        ;; them (a JVM-named body's own equals is the JVM's interop)
        equals (or (when (has? 'eve.core/IEquiv)
                     [{:name 'equals :args '[this other] :body '[(eve.core/-equiv this other)]}])
                   (get by-name 'equals))
        hash-code (or (when (has? 'eve.core/IHash)
                        [{:name 'hashCode :args '[this] :body '[(eve.core/-hash this)]}])
                      (get by-name 'hashCode))
        hash-code (map (fn [m] (update m :body (fn [b] [(list 'unchecked-int (cons 'do b))])))
                       hash-code)]
    (mapv (fn [impl] (update impl :methods #(mapv rewrite %)))
          (conj translated
                {:protocol 'Object :methods (vec (concat to-string equals hash-code))}))))

(defn- define [type-name fields forms]
  [`(~'deftype ~type-name ~fields ~@forms)])

(defn- hooks
  "This host's steps for eve.deftype-proto.sio (a function: clojerl compiles a def's
   value as a literal, which a map of functions is not)."
  []
  {:normalize (fn [parsed] {:impls parsed})
   ;; the interfaces go onto eve.core's protocols; an IPrintWithWriter body
   ;; is the print-method
   :define (fn [type-name fields impls _forms _print]
             (let [bb-impls (bb-translate-protos impls type-name)]
               (concat [`(~'deftype ~type-name ~fields ~@(impls->forms bb-impls))]
                       (when (some #(= 'eve.core/IPrintWithWriter (:protocol %)) bb-impls)
                         [(sio/print-method-form type-name 'eve.core/-pr-writer)]))))
   ;; sci builds (T. x) through the ->T var: the deftype's own factory, kept
   ;; as T__new before ->T becomes the constructor, builds instances
   :build (fn [_type-name _fields new-name args] (list* new-name args))
   :factory-via-var? true
   ;; a map type keeps the map interfaces; another's go onto eve.core's protocols
   :slab-prepare (fn [parsed type-name]
                   (if (v/user-provides-protocol? parsed 'clojure.lang.IPersistentMap)
                     (bb-filter-protos parsed)
                     (bb-translate-protos parsed type-name)))
   ;; sci rejects IHashEq on a non-map type and Object equals/hashCode
   :object-boilerplate? false
   :slab-define define})

(defn host-type
  "A plain type written with the JVM's names (host-deftype)."
  [_env type-name fields parsed]
  `(~'deftype ~type-name ~fields ~@(impls->forms (bb-translate-protos parsed type-name))))

(defn plain-type
  "A plain type (eve.deftype/deftype) on eve.core's protocols."
  [_env type-name fields parsed]
  (let [[fields parsed] (sio/plain-renames fields parsed nil)
        impls (bb-translate-protos parsed type-name)
        printed? (some #(= 'eve.core/IPrintWithWriter (:protocol %)) impls)]
    `(do (clojure.core/deftype ~type-name ~fields ~@(impls->forms impls))
         ~@(when printed? [(sio/print-method-form type-name 'eve.core/-pr-writer)])
         ~type-name)))

(defn slab-type "An eve/deftype type (eve.deftype-proto.macros/deftype)." [m] (sio/slab-type m (hooks)))
(defn eve-type "An eve-deftype (eve.deftype.macros/eve-deftype*)." [ctx] (sio/eve-type ctx (hooks)))
(defn extend-eve-type "eve-extend-type." [ctx] (sio/extend-eve-type ctx))

(defn existing-def
  "{:value v} when the namespace being compiled already defines sym, else nil."
  [_env sym]
  (when-let [v (resolve sym)] {:value @v}))

(defn rename-fields
  "An eve-deftype's fields and body with the field names the JVM reserves
   renamed (__hash): [fields body]."
  [_env fields body]
  (sio/eve-renames fields body))

(defn reader-of
  "What a registered eve collection reads as on babashka: the host's own
   collection (host-value) where one is given — sci's types answer eve.core's
   protocols, not clojure.core's, so what bb reads is the Clojure collection
   clojure.core takes; else its eve type (from-header)."
  [from-header host-value]
  (or host-value from-header))

(defn print-form
  "The printer of a registered eve collection: none — babashka (a value read from eve memory is a Clojure
   collection there) prints it by
   its own."
  [_env _type-name _spec]
  nil)

(defn vector-types
  "babashka's own vector types: IPersistentVector's implementors."
  [_env]
  '[clojure.lang.IPersistentVector])
