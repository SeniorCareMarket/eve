(ns eve.types
  "eve's types on ValueScript. A VS deftype takes protocols and Java blocks, but VS's core reads a deftype instance as
   its own typed map or struct block: clojure.core's count, seq, get, deref, invoke and = never reach the type's
   interface methods (measured on the landed builder, 2026-10-19: count and seq refuse, get and invoke answer nil, =
   is identity; hash reads a registered hashCode, str a registered toString). That is babashka's capability, so a type
   is a deftype whose interface impls are translated onto eve.core's protocols (adapters/coll/protocols, ClojureScript's
   names), as types/bb translates them — with no sci map mode: a VS map type is translated too.

   What VS adds to babashka's translation: a type body's own calls of clojure.core's collection vocabulary (count,
   assoc, conj, reduce-kv, ...) go through eve.core, where an eve value answers by its protocol and any other value
   falls through to clojure.core — so a body written once (eve.map's cons reduces assoc over this) reaches its own
   type on VS. A name a method binds is left alone.

   The entry points eve's macros call (every eve.types has them):
     host-type, plain-type, slab-type, eve-type, extend-eve-type,
     existing-def, rename-fields, reader-of, print-form, vector-types."
  (:require ;; what a translated type implements: required here, as types/bb does, so every namespace a type macro
            ;; expands in has it loaded
            [eve.core]
            ;; a translated type carries a Java block (Object's toString and hashCode), which VS registers through
            ;; vs.interop/register-class! as the deftype loads; the launcher composes vs.interop only for a program
            ;; file that names a class, so a library's type needs it loaded first (measured: eve.map alone,
            ;; RUN-TRIP vs.interop/register-class!)
            [vs.interop]
            [eve.deftype-proto.vocab :as v :refer [impls->forms interface-sym? strip-cljs-core
                                                 cljs-protocol? cljs-translate-protocol]]
            [eve.deftype-proto.sio :as sio]))

(def ^:private eve-core-protocols
  "The protocols eve.core defines (adapters/coll/protocols; ClojureScript's names)."
  '#{ICounted IIndexed ILookup ISeqable ICollection IEmptyableCollection IEquiv
     IHash IMeta IWithMeta IFn IReduce IKVReduce IAssociative IMap ISet IStack
     IVector ISeq INext ISequential ASeq IReversible IComparable
     IEditableCollection ITransientCollection ITransientAssociative ITransientMap
     ITransientVector ITransientSet IChunkedSeq IChunkedNext IMapEntry IDeref
     IPrintWithWriter})

(def ^:private self-call->eve-core
  "A method a body calls on its own instance, (.m this ...), and the eve.core protocol fn that answers it (the type has
   no such method once translated)."
  '{seq eve.core/-seq count eve.core/-count nth eve.core/-nth
    valAt eve.core/-lookup equiv eve.core/-equiv hasheq eve.core/-hash
    cons eve.core/-conj empty eve.core/-empty invoke eve.core/-invoke
    meta eve.core/-meta withMeta eve.core/-with-meta disjoin eve.core/-disjoin
    reduce eve.core/-reduce kvreduce eve.core/-kv-reduce
    containsKey eve.core/-contains-key? assoc eve.core/-assoc without eve.core/-dissoc
    peek eve.core/-peek pop eve.core/-pop assocN eve.core/-assoc-n})

(def ^:private core->eve-core
  "clojure.core's collection vocabulary a body may call on an eve value, and eve.core's name for it (eve.core answers
   any other value through clojure.core)."
  '#{count nth get seq first next rest conj into empty contains? disj meta with-meta reduce
     assoc dissoc pop peek rseq reduce-kv transient persistent! conj! assoc! pop! sequential? counted?})

(defn- bind-names
  "the names a binding form binds (a symbol, a vector or map destructuring)"
  [b]
  (cond (symbol? b) [(name b)]
        (vector? b) (mapcat bind-names b)
        (map? b) (mapcat (fn [[k x]] (cond (= :keys k) (map name x)
                                           (= :as k) [(name x)]
                                           (keyword? k) []
                                           :else (bind-names k)))
                         b)
        :else []))

(defn- bound-names
  "Every symbol a form binds anywhere (fn and method params, let / loop / letfn bindings, destructuring), as names —
   written in the forms VS's macro evaluator runs (no volatile, no clojure.string)."
  [form]
  (set (mapcat (fn [f]
                 (when (seq? f)
                   (let [h (first f)]
                     (cond
                       (contains? '#{let let* loop loop* binding} h)
                       (when (vector? (second f)) (mapcat bind-names (take-nth 2 (second f))))
                       (contains? '#{fn fn*} h)
                       (mapcat (fn [x] (cond (vector? x) (bind-names x)
                                             (and (seq? x) (vector? (first x))) (bind-names (first x))
                                             (symbol? x) [(name x)]
                                             :else []))
                               (rest f))
                       (= 'letfn h)
                       (mapcat (fn [spec] (when (seq? spec) (cons (name (first spec)) (mapcat bind-names (filter vector? spec)))))
                               (second f))
                       :else nil))))
               (tree-seq coll? seq form))))

(defn- rewrite-body
  "(.m this args...) → (eve.core/-m' this args...); (f args...) for f in core->eve-core, unqualified or
   clojure.core's, and bound by no form of the method → (eve.core/f args...)."
  [form this-sym bound]
  (let [rw #(rewrite-body % this-sym bound)
        head (when (seq? form) (first form))
        self (when (and (symbol? head) (= this-sym (second form)))
               (let [n (name head)]
                 (when (and (> (count n) 1) (= "." (subs n 0 1)) (not (= "-" (subs n 1 2))))
                   (self-call->eve-core (symbol (subs n 1))))))
        core (when (and (symbol? head) (contains? core->eve-core (symbol (name head)))
                        (contains? #{nil "clojure.core"} (namespace head))
                        (not (contains? bound (name head))))
               (symbol "eve.core" (name head)))]
    (cond
      self (apply list self this-sym (map rw (nnext form)))
      core (with-meta (apply list core (map rw (rest form))) (meta form))
      (and (seq? form) (= 'quote head)) form
      (seq? form) (with-meta (apply list (map rw form)) (meta form))
      (vector? form) (with-meta (mapv rw form) (meta form))
      (map? form) (with-meta (into {} (map (fn [[k x]] [(rw k) (rw x)]) form)) (meta form))
      :else form)))

(defn- rewrite-method [{:keys [args body] :as m}]
  (let [bound (bound-names (cons args body))]
    (assoc m :body (map #(rewrite-body % (first args) bound) body))))

(defn translate-protos
  "A type's impls ([{:protocol :methods}], the JVM's or ClojureScript's names) as a VS deftype takes them: interfaces
   translated onto eve.core's protocols (through the ClojureScript table; ones without an eve.core protocol dropped),
   the type's own protocols as written, and Object's toString and hashCode — hashCode the type's -hash where it has
   one, so VS's hash (which reads a registered hashCode) agrees with it. Bodies call eve.core (rewrite-body)."
  [parsed-protos type-name]
  (let [object? #(contains? '#{Object java.lang.Object} (:protocol %))
        objects (mapcat :methods (filter object? parsed-protos))
        translated (->> (remove object? parsed-protos)
                        (mapcat (fn [impl]
                                  (cond
                                    (interface-sym? (:protocol impl))
                                    (keep (fn [{:keys [protocol] :as t}]
                                            (when (contains? eve-core-protocols protocol)
                                              (assoc t :protocol (symbol "eve.core" (name protocol)))))
                                          (cljs-translate-protocol impl))
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
        hash-code (or (when (has? 'eve.core/IHash)
                        [{:name 'hashCode :args '[this] :body '[(eve.core/-hash this)]}])
                      (get by-name 'hashCode))]
    (mapv (fn [impl] (update impl :methods #(mapv rewrite-method %)))
          (conj translated {:protocol 'Object :methods (vec (concat to-string hash-code))}))))

(defn- printed? [impls] (some #(= 'eve.core/IPrintWithWriter (:protocol %)) impls))

(defn- define [type-name fields forms]
  [`(~'deftype ~type-name ~fields ~@forms)])

(defn- hooks
  "This host's steps for eve.deftype-proto.sio."
  []
  {:normalize (fn [parsed] {:impls parsed})
   ;; the interfaces go onto eve.core's protocols; an IPrintWithWriter body is the print-method
   :define (fn [type-name fields impls _forms _print]
             (let [vs-impls (translate-protos impls type-name)]
               (concat [`(~'deftype ~type-name ~fields ~@(impls->forms vs-impls))]
                       (when (printed? vs-impls)
                         [(sio/print-method-form type-name 'eve.core/-pr-writer)]))))
   :build (fn [type-name _fields _new-name args] (list* 'new type-name args))
   :factory-via-var? false
   :slab-prepare (fn [parsed type-name] (translate-protos parsed type-name))
   ;; IHashEq and Object equals/hashCode would be Java blocks VS's = never reads; the translation gives hashCode
   :object-boilerplate? false
   :slab-define define})

(defn host-type
  "A plain type written with the JVM's names (host-deftype)."
  [_env type-name fields parsed]
  `(~'deftype ~type-name ~fields ~@(impls->forms (translate-protos parsed type-name))))

(defn plain-type
  "A plain type (eve.deftype/deftype) on eve.core's protocols."
  [_env type-name fields parsed]
  (let [[fields parsed] (sio/plain-renames fields parsed nil)
        impls (translate-protos parsed type-name)]
    `(do (clojure.core/deftype ~type-name ~fields ~@(impls->forms impls))
         ~@(when (printed? impls) [(sio/print-method-form type-name 'eve.core/-pr-writer)])
         ~type-name)))

(defn slab-type "An eve/deftype type (eve.deftype-proto.macros/deftype)." [m] (sio/slab-type m (hooks)))
(defn eve-type "An eve-deftype (eve.deftype.macros/eve-deftype*)." [ctx] (sio/eve-type ctx (hooks)))
(defn extend-eve-type "eve-extend-type." [ctx] (sio/extend-eve-type ctx))

(defn existing-def
  "{:value v} when the namespace being compiled already defines sym, else nil."
  [_env sym]
  (when-let [v (resolve sym)] {:value @v}))

(defn rename-fields
  "An eve-deftype's fields and body with the field names the JVM reserves renamed (__hash), as on the JVM and
   babashka: [fields body]."
  [_env fields body]
  (sio/eve-renames fields body))

(defn reader-of
  "What a registered eve collection reads as on VS: the host's own collection (host-value) where one is given —
   VS's core reads VS's own collections, not an eve type's protocols — else its eve type (from-header)."
  [from-header host-value]
  (or host-value from-header))

(defn print-form
  "The printer of a registered eve collection: none — VS prints what it reads (a host value) by its own printer, and
   an eve type's IPrintWithWriter body is its print-method."
  [_env _type-name _spec]
  nil)

(defn vector-types
  "VS's own vector types: IPersistentVector's implementors."
  [_env]
  '[clojure.lang.IPersistentVector])
