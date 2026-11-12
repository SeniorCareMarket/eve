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
            [eve.logical-protocol :as logical]
            [eve.record-context :as record-context]
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

(def ^:private bb-completion-interfaces
  "Interfaces sci's deftype will not carry, answered instead by a protocol
   on eve.host-completion. clojure.lang.IPending is refused (measured on
   bb 1.13.226: 'deftype currently only support protocol implementations'),
   and clojure.core/realized? otherwise reaches only a host IPending; the
   completion re-roots realized? onto this protocol."
  '{clojure.lang.IPending {:protocol eve.host-completion/IPending
                           :methods {isRealized -realized?}}})

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
   agree with them. Self-calls in bodies go through eve.core.
   opts :slab? true marks a slab-backed type (eve-deftype, eve/deftype):
   IPersistentMap is a collection-kind head for it (see map-signature?)."
  ([parsed-protos type-name] (bb-translate-protos parsed-protos type-name nil))
  ([parsed-protos type-name {:keys [slab?]}]
  (let [object? #(contains? '#{Object java.lang.Object} (:protocol %))
        rewrite (fn [m] (update m :body (fn [body] (map #(rewrite-self-calls % (first (:args m))) body))))
        objects (mapcat :methods (filter object? parsed-protos))
        translated (->> (remove object? parsed-protos)
                        (mapcat (fn [impl]
                                  (cond
                                    (contains? sci-native-interfaces (:protocol impl))
                                    [(update impl :methods sci-swap-arities)]

                                    (contains? bb-completion-interfaces (:protocol impl))
                                    (let [{:keys [protocol methods]} (bb-completion-interfaces (:protocol impl))]
                                      [(assoc impl :protocol protocol
                                              :methods (mapv #(update % :name (fn [n] (get methods n n)))
                                                             (:methods impl)))])

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
                       hash-code)
        ;; A type carrying the full map signature (ILookup +
        ;; Associative + Counted) is a map on the JVM whether or not
        ;; it names IPersistentMap: deftype there answers
        ;; clojure.core's get/assoc/count through those interfaces.
        ;; On this seat only sci's map mode gives a type that
        ;; visibility, so such a type's map groups are ALSO kept as
        ;; written (with the IPersistentMap marker), beside their
        ;; eve.core translations — datascript's DB is the measured
        ;; case (its spec names no IPersistentMap, and library code
        ;; reaches it through clojure.core's fns, which are blind to
        ;; eve.core protocols).
        ;; The exclusion that keeps this honest: a type that names
        ;; a host collection-KIND head (IPersistentVector,
        ;; IPersistentSet, IPersistentList, IList,
        ;; IPersistentStack, IPersistentQueue, Sequential) is one of
        ;; the seat's own collection types — EveVector carries the
        ;; signature too, and as a SciMap its host hash/iteration
        ;; walk fields instead of contents (measured: (hash
        ;; (eve-vec [1 2 3])) threw "Method not implemented:
        ;; iterator" in map mode, and is the eve hash as a SciType).
        ;; Kind-headed types keep the plain translation only.
        ;; IPersistentMap is in that exclusion for a slab-backed type
        ;; only (opts :slab?, from this seat's :define hook):
        ;; PersistentIntMap and EveSortedMap name it and are the
        ;; seat's own map collections, defined through :define. As
        ;; SciMaps their host hash walked an iterator they do not
        ;; carry ("Method not implemented: iterator", int-map
        ;; hash-test), and an instance is a babashka.impl.SciMap, so
        ;; retire-diff's (instance? PersistentIntMap new) is false and
        ;; the walk counts every block of old as reclaimed, the ones
        ;; new still holds too (measured: (e/assoc (im/int-map 1 1 2 2)
        ;; 3 3) diffed 6 of 6 blocks, not 2; reclaim-test 266 fails).
        ;; A process-local defrecord-like type
        ;; (eve.deftype/deftype: ILookup+Associative+Counted plus
        ;; IPersistentMap, no vector/set/list/stack head) still gets
        ;; SciMap mode so clojure.core/assoc and dissoc reach it
        ;; (eve.db.ds def-eve-record; naming IPersistentMap used to
        ;; force SciType and ClassCast on assoc).
        ;; An eve.record record (eve.record/defrecord: slab-backed, naming the
        ;; full map signature with IPersistentMap, marked by its own
        ;; eve.record/IRecordData) is NOT one of the seat's map collections
        ;; the exclusion above protects: it is a nominal record whose
        ;; fields clojure.core's get and keyword lookup must reach, as a
        ;; JVM record's do. Without SciMap mode those fns are blind to the
        ;; eve.core translations (measured on bb 1.13.226: (:x r) and
        ;; (get r :x) answered nil on a 2-field record while eve.core/get
        ;; answered the value, and (instance? clojure.lang.ILookup r) was
        ;; false), so a record keeps its map groups as written, like a
        ;; process-local record-like type does.
        map-signature? (let [ps (set (map :protocol parsed-protos))
                             record? (contains? ps 'eve.record/IRecordData)]
                         (and (contains? ps 'clojure.lang.ILookup)
                              (contains? ps 'clojure.lang.Associative)
                              (contains? ps 'clojure.lang.Counted)
                              (not (and slab? (not record?) (contains? ps 'clojure.lang.IPersistentMap)))
                              (not-any? ps '#{clojure.lang.IPersistentVector
                                              clojure.lang.IPersistentSet
                                              clojure.lang.IPersistentList
                                              clojure.lang.IList
                                              clojure.lang.IPersistentStack
                                              clojure.lang.IPersistentQueue
                                              clojure.lang.Sequential})))
        map-kept (when map-signature?
                   (let [kept (filterv (fn [impl]
                                         (contains? '#{clojure.lang.ILookup
                                                       clojure.lang.Associative
                                                       clojure.lang.IPersistentCollection
                                                       clojure.lang.IPersistentMap
                                                       clojure.lang.Counted
                                                       clojure.lang.Seqable
                                                       java.lang.Iterable}
                                                    (:protocol impl)))
                                       parsed-protos)
                         ;; SCI's map hash and host collection consumers call
                         ;; iterator directly. A JVM map-shaped declaration can
                         ;; omit Iterable, but SciMap cannot: derive it from seq.
                         kept (if (some #(= 'java.lang.Iterable (:protocol %)) kept)
                                kept
                                (conj kept
                                      {:protocol 'java.lang.Iterable
                                       :methods [{:name 'iterator :args '[this]
                                                  :body '[(let [state (atom (seq this))]
                                                            (reify java.util.Iterator
                                                              (hasNext [_] (boolean @state))
                                                              (next [_]
                                                                (if-let [s @state]
                                                                  (let [v (first s)]
                                                                    (swap! state next)
                                                                    v)
                                                                  (throw (java.util.NoSuchElementException.))))))]}]}))]
                     (if (some #(= 'clojure.lang.IPersistentMap (:protocol %)) kept)
                       kept
                       (into [{:protocol 'clojure.lang.IPersistentMap :methods []}] kept))))]
    (mapv (fn [impl] (update impl :methods #(mapv rewrite %)))
          (into (conj translated
                      {:protocol 'Object :methods (vec (concat to-string equals hash-code))})
                (concat map-kept
                        ;; Every type this adapter defines is an Eve type:
                        ;; mark it with eve.deftype-proto.data/IsEve, as
                        ;; Eve's own collection types mark themselves in
                        ;; their sources. The mark is what lets Eve's own
                        ;; traversal (eve->clj) tell a presented deftype
                        ;; from a plain collection on this seat — sci's
                        ;; predicates cannot: a map-signature type IS a
                        ;; host map here, and an unmarked one is descended
                        ;; into and rebuilt as plain data by a deref
                        ;; (F-BB-DATOM-CMP: a DataScript DB in an atom,
                        ;; its index sets materialized with their
                        ;; registered comparators lost). A type that
                        ;; already carries the mark keeps its own impl.
                        (when-not (some #(= "IsEve" (name (:protocol %))) translated)
                          [{:protocol 'eve.deftype-proto.data/IsEve
                            :methods [{:name '-eve? :args '[_] :body '[true]}]}])))))))

;; Map entries: an eve-deftype heading clojure.lang.IMapEntry (eve.map-entry's
;; MapEntry) is PRESENTED as a clojure.lang.MapEntry on this seat. sci's
;; deftype cannot carry IMapEntry, IPersistentVector, Counted or Indexed, and
;; clojure.core's count and nth are compiled past their vars (measured on bb
;; 1.13.226: a re-rooted count/nth is not reached), so a SciType entry answered
;; map-entry? false, key/val/conj/count with a ClassCastException or
;; "count not supported on this type: SciType", and (empty entry) nil. The
;; type's constructor and its loader (an entry read back from eve memory) build
;; the Eve value as before, then hand the program a host MapEntry of its key and
;; val: a map entry to every clojure.core fn. The Eve value stays behind it
;; (presented-backing, a weak identity table) for eve's own reach: the codec
;; writes it (eve.deftype-proto.serialize/presented-backing) and
;; eve.deftype-proto.data/-eve-sio answers its domain.
(def ^:private map-entry-types
  "The qualified names of the eve-deftypes presented as host map entries
   (recorded at :define, read at :build, both at macroexpansion)."
  (atom #{}))

(defn- qualified-type [type-name] (symbol (str *ns*) (name type-name)))

(def ^:private backings
  "System/identityHashCode of a presented entry → [(WeakReference entry) eve-value]."
  (java.util.concurrent.ConcurrentHashMap.))

(def ^:private backing-puts (atom 0))

(defn- sweep-backings!
  "Drops the rows whose presented entry is gone."
  []
  (doseq [k (vec (.keySet backings))]
    (when-let [row (.get backings k)]
      (when (nil? (.get ^java.lang.ref.WeakReference (first row)))
        (.remove backings k row)))))

(defn presented-backing
  "The Eve value the host map entry x presents, or nil (x a host value of its own)."
  [x]
  (when (instance? clojure.lang.MapEntry x)
    (when-let [row (.get backings (System/identityHashCode x))]
      (when (identical? x (.get ^java.lang.ref.WeakReference (first row)))
        (second row)))))

(def ^:private presentation-installed (atom false))

(defn- install-presentation!
  "Once, at the first presented entry (eve.types loads before the
   serializer and eve's data protocols, which it must not require): the
   codec's backing lookup, and -eve-sio on a presented entry."
  []
  (when (compare-and-set! presentation-installed false true)
    (reset! @(resolve 'eve.deftype-proto.serialize/presentation-backing) presented-backing)
    (let [eve-sio @(resolve 'eve.deftype-proto.data/-eve-sio)]
      (extend clojure.lang.MapEntry
        @(resolve 'eve.deftype-proto.data/IEveSio)
        {:-eve-sio (fn [e] (some-> (presented-backing e) eve-sio))}))
    ;; clojure.core/empty of a host MapEntry is nil (AMapEntry); the Eve
    ;; entry's is its own (eve.map-entry: [], as a vector's). A presented
    ;; entry answers the Eve entry's; every other value the seat's own.
    (let [host-empty @#'clojure.core/empty]
      (alter-var-root #'clojure.core/empty
                      (fn [_] (fn [coll]
                                (if-let [e (presented-backing coll)]
                                  (eve.core/-empty e)
                                  (host-empty coll))))))))

(defn present-map-entry
  "The Eve map entry e as a clojure.lang.MapEntry of its key and val, e kept behind it."
  [e]
  (install-presentation!)
  (let [x (clojure.lang.MapEntry. (eve.core/-nth e 0) (eve.core/-nth e 1))]
    (.put backings (System/identityHashCode x) [(java.lang.ref.WeakReference. x) e])
    (when (zero? (mod (swap! backing-puts inc) 4096))
      (sweep-backings!))
    x))

(def ^:private map-mode-record-types
  "The qualified names of the eve.record records kept in SciMap mode (recorded
   at :define, read at :build, both at macroexpansion)."
  (atom #{}))

(defn- record-map-mode?
  "True for an eve.record record's impls: the full map signature, marked by
   eve.record/IRecordData, and no host collection-kind head. The same
   condition as the record carve-out of map-signature? in bb-translate-protos."
  [impls]
  (let [ps (set (map :protocol impls))]
    (and (contains? ps 'eve.record/IRecordData)
         (contains? ps 'clojure.lang.ILookup)
         (contains? ps 'clojure.lang.Associative)
         (contains? ps 'clojure.lang.Counted)
         (not-any? ps '#{clojure.lang.IPersistentVector clojure.lang.IPersistentSet
                         clojure.lang.IPersistentList clojure.lang.IList
                         clojure.lang.IPersistentStack clojure.lang.IPersistentQueue
                         clojure.lang.Sequential}))))

(defn- define [type-name fields forms]
  [`(clojure.core/deftype ~type-name ~fields ~@forms)])

(defn- hooks
  "This host's steps for eve.deftype-proto.sio (a function: clojerl compiles a def's
   value as a literal, which a map of functions is not)."
  []
  {:normalize (fn [parsed] {:impls parsed})
   ;; the interfaces go onto eve.core's protocols; an IPrintWithWriter body
   ;; is the print-method
   :define (fn [type-name fields impls _forms _print]
             (when (some #(= 'clojure.lang.IMapEntry (:protocol %)) impls)
               (swap! map-entry-types conj (qualified-type type-name)))
             (when (record-map-mode? impls)
               (swap! map-mode-record-types conj (qualified-type type-name)))
             (let [bb-impls (bb-translate-protos impls type-name {:slab? true})]
               (concat [`(clojure.core/deftype ~type-name ~fields ~@(impls->forms bb-impls))]
                       (when (some #(= 'eve.core/IPrintWithWriter (:protocol %)) bb-impls)
                         [(sio/print-method-form type-name 'eve.core/-pr-writer)]))))
   ;; sci builds (T. x) through the ->T var: the deftype's own factory, kept
   ;; as T__new before ->T becomes the constructor, builds instances
   ;; a map-entry type's instances are presented as host map entries
   :build (fn [type-name _fields new-name args]
            (cond
              (contains? @map-entry-types (qualified-type type-name))
              (list 'eve.types/present-map-entry (list* new-name args))
              ;; a record in map mode carries its type as a stamp on every
              ;; instance (see map-signature? in bb-translate-protos)
              (contains? @map-mode-record-types (qualified-type type-name))
              (list 'eve.host-completion/stamp-map-mode-type (list* new-name args) type-name)
              :else (list* new-name args)))
   :factory-via-var? true
   ;; a map type keeps the map interfaces; another's go onto eve.core's protocols
   :slab-prepare (fn [parsed type-name]
                   (if (v/user-provides-protocol? parsed 'clojure.lang.IPersistentMap)
                     (bb-filter-protos parsed)
                     (bb-translate-protos parsed type-name {:slab? true})))
   ;; sci rejects IHashEq on a non-map type and Object equals/hashCode
   :object-boilerplate? false
   :slab-define define})

(defn host-type
  "A plain type written with the JVM's names (host-deftype)."
  [_env type-name fields parsed]
  `(clojure.core/deftype ~type-name ~fields ~@(impls->forms (bb-translate-protos parsed type-name))))

(defn plain-type
  "A plain type (eve.deftype/deftype) on eve.core's protocols."
  [_env type-name fields parsed]
  (logical/install!)
  (let [[fields parsed] (sio/plain-renames fields parsed nil)
        impls (bb-translate-protos parsed type-name)
        printed? (some #(= 'eve.core/IPrintWithWriter (:protocol %)) impls)
        map-mode? (some #(= 'clojure.lang.IPersistentMap (:protocol %)) impls)
        factory (symbol (str "->" (name type-name)))]
    `(do (clojure.core/deftype ~type-name ~fields ~@(impls->forms impls))
         ~@(when printed? [(sio/print-method-form type-name 'eve.core/-pr-writer)])
         ~@(when map-mode?
             [`(eve.host-completion/present-map-mode-type! (var ~factory) ~type-name)])
         ~type-name)))

(defn slab-type "An eve/deftype type (eve.deftype-proto.macros/deftype)." [m] (sio/slab-type m (hooks)))
(defn eve-type "An eve-deftype (eve.deftype.macros/eve-deftype*)." [ctx]
  (logical/install!)
  (sio/eve-type ctx (hooks)))
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
  (if host-value
    `(fn [sio# header-off#]
       (if eve.record-context/*slab-values*
         (~from-header sio# header-off#)
         (~host-value sio# header-off#)))
    from-header))

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
