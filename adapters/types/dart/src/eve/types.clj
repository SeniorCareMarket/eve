(ns eve.types
  "eve's types on ClojureDart — the macro-time half, this .clj file.
   ClojureDart evaluates macros on the JVM: eve's macro namespaces are
   compiled by JVM Clojure, whose loader finds this file (a .clj), and
   the entry points below run there. Its sibling types.cljd is the same
   adapter for ClojureDart's own loader (which finds only .cljd/.cljc):
   host-loaded by the compiler and compiled to Dart, where these
   generators exist so the shared sources link — they are called only
   at macro time. The two files are one adapter in the two languages
   of its two loaders; keep their entry points in step.
   This file realizes each eve type as a cljd deftype whose
   protocol impls are cljd.core's — ClojureDart's protocols carry
   ClojureScript's names, so the shared ClojureScript translation
   (eve.deftype-proto.vocab/translate-protos :cljs) is this host's too,
   with its cljs.core symbols re-rooted to cljd.core. The slab-backed
   types reach eve memory through ISlabIO exactly as on the JVM, bb,
   glojure and CLR (eve.deftype-proto.sio's generators, this host's
   hooks); there is no module slab here.
   The entry points eve's macros call (every eve.types has them):
     host-type, plain-type, slab-type, eve-type, extend-eve-type,
     existing-def, rename-fields, reader-of, print-form, vector-types."
  {:clj-kondo/config {:linters {:unresolved-var {:exclude [eve.types/cljd-fields eve.types/dart-define-form eve.types/dart-impls->forms eve.types/dart-merge-inext eve.types/dart-object-groups eve.types/dart-print-body eve.types/dart-print-form eve.types/dart-print-rename eve.types/dart-rehome-methods eve.types/dart-syms eve.types/dart-translate-protos]}}}}
  (:require [clojure.walk :as walk]
            [eve.deftype-proto :as proto]
            [eve.deftype-proto.vocab :as v :refer [impls->forms]]
            [eve.deftype-proto.sio :as sio]))

;;-----------------------------------------------------------------------------
;; Translation: JVM names in, cljd.core names out
;;-----------------------------------------------------------------------------

(defn- dart-syms
  "form with every cljs.core/* and clojure.core/* symbol re-rooted to
   cljd.core/* (the two sources of foreign core names in a translated
   expansion: vocab's own-call rewrite, and sio's boilerplate)."
  [form]
  (walk/postwalk (fn [x]
                   (if (and (symbol? x) (contains? #{"cljs.core" "clojure.core"} (namespace x)))
                     (symbol "cljd.core" (name x))
                     x))
                 form))

(defn- dart-print-body
  "A -pr-writer method's [args body] as IPrint's -print takes them:
   [this sink] — IPrint has no opts parameter, so the opts argument a
   body may still name is bound to nil around the body (ClojureDart's
   printer threads no print options; eve.core's pr-writer ignores
   them on this host too)."
  [args body]
  (if (= 3 (count args))
    [(vec (take 2 args)) (list (list* 'let [(nth args 2) nil] body))]
    [args body]))

(defn- dart-print-rename
  "IPrintWithWriter/-pr-writer [this writer opts] as ClojureDart's print
   protocol takes it: IPrint/-print [this sink]. (Bodies the generators
   extract their print from never reach here; a print group left inline
   — a plain or host type — is renamed.)"
  [impls]
  (mapv (fn [{:keys [protocol methods] :as impl}]
          (if (= "IPrintWithWriter" (name protocol))
            {:protocol 'cljd.core/IPrint
             :methods (mapv (fn [{:keys [args body]}]
                              (let [[args body] (dart-print-body args body)]
                                {:name '-print :args args :body body}))
                            methods)}
            impl))
        impls))

(defn- dart-print-form
  "PRINT-FN, a (fn [this writer opts] body...), as an extend-type of
   ClojureDart's IPrint on TYPE-NAME: -print [this sink], its opts
   parameter bound to nil (dart-print-body)."
  [type-name print-fn]
  (let [[args body] (dart-print-body (second print-fn) (nnext print-fn))]
    (list 'extend-type type-name 'cljd.core/IPrint
          (list* '-print args body))))

(defn- dart-merge-inext
  "INext folded into ISeq: ClojureScript splits -next into its own
   protocol, ClojureDart's ISeq declares -next itself. An INext entry's
   methods join the ISeq entry (created when the body has no ISeq)."
  [impls]
  (let [inext (some #(when (= "INext" (name (:protocol %))) %) impls)]
    (if-not inext
      impls
      (let [rest-impls (remove #(identical? % inext) impls)
            iseq (some #(when (= "ISeq" (name (:protocol %))) %) rest-impls)]
        (if iseq
          (mapv (fn [impl]
                  (if (identical? impl iseq)
                    (update impl :methods into (:methods inext))
                    impl))
                rest-impls)
          (into [{:protocol 'cljd.core/ISeq :methods (:methods inext)}] rest-impls))))))

(defn- dart-rehome-methods
  "Methods ClojureScript puts on one protocol and ClojureDart on
   another, moved to the entry that declares them here — a method its
   entry's protocol does not declare is emitted by ClojureDart's
   deftype as a stray member, not an implementation. The table is the
   measured divergence between the two cores' protocol declarations:
   IAssociative's -contains-key? is ILookup's in ClojureDart."
  [impls]
  (let [moves {["IAssociative" "-contains-key?"] "ILookup"}
        move-one (fn [impls [from method] to]
                   (let [src (some #(when (= from (name (:protocol %))) %) impls)
                         m (some #(when (= method (name (:name %))) %) (:methods src))]
                     (if-not m
                       impls
                       (let [impls (mapv (fn [impl]
                                           (if (identical? impl src)
                                             (update impl :methods
                                                     (fn [ms] (vec (remove #(identical? % m) ms))))
                                             impl))
                                         impls)
                             dst (some #(when (= to (name (:protocol %))) %) impls)]
                         (if dst
                           (mapv (fn [impl]
                                   (if (identical? impl dst)
                                     (update impl :methods conj m)
                                     impl))
                                 impls)
                           (into [{:protocol (symbol "cljd.core" to) :methods [m]}] impls))))))]
    (reduce-kv (fn [acc km to] (move-one acc km to)) impls moves)))

(defn dart-translate-protos
  "Protocol impls written with JVM names ([{:protocol :methods}]) as a
   cljd deftype takes them: through the ClojureScript table, Object's
   toString kept on Object and its equals re-homed to IEquiv (vocab's
   cljs-object-impls, inside translate-protos), hosts' own protocols and
   eve's passing through unchanged."
  [parsed]
  (dart-rehome-methods (dart-merge-inext (dart-print-rename (dart-syms (v/translate-protos (v/cljs-own-calls parsed) :cljs))))))

(defn- cljd-fields
  "deftype field syms with cljd's mutability meta: a field the expansion
   marked :unsynchronized-mutable (the JVM's word) or :mutable is
   ^:mutable here; JVM type tags go (Dart's ints need none)."
  [field-syms]
  (mapv (fn [s]
          (let [m (meta s)]
            (with-meta (symbol (name s))
              (when (or (:unsynchronized-mutable m) (:mutable m) (:volatile-mutable m))
                {:mutable true}))))
        field-syms))

(defn- dart-object-groups
  "A parsed impl group's disposition in a cljd deftype: sio's JVM
   boilerplate groups (IHashEq, Object) become cljd's IHash, IEquiv and
   an Object toString; eve's own protocols and the translated user
   groups pass through."
  [{:keys [protocol methods] :as impl}]
  (case protocol
    clojure.lang.IHashEq
    [{:protocol 'IHash
      :methods (mapv (fn [m] (assoc m :name '-hash)) methods)}]

    ;; a bare Object group (vocab's cljs-object-impls output: toString,
    ;; its equals already re-homed to IEquiv) passes through as written
    Object
    [impl]

    java.lang.Object
    (let [by-name (group-by :name methods)]
      (concat
       (when (seq (get by-name 'equals))
         [{:protocol 'IEquiv
           :methods (mapv (fn [m] (assoc m :name '-equiv)) (get by-name 'equals))}])
       ;; hashCode goes: IHash (from IHashEq, or the type's own) is the hash
       (when (seq (get by-name 'toString))
         [{:protocol 'Object :methods (vec (get by-name 'toString))}])))

    [impl]))

(defn- dart-impls->forms
  "impls->forms with this host's method names: unqualified. The JVM's
   and ClojureScript's deftype take a qualified method name
   (eve.deftype-proto.data/-sabp-type-key, as the boilerplate and some
   bodies write it) and strip it to the name; ClojureDart's emits the
   symbol as written, which is no Dart name at all."
  [impls]
  (impls->forms
   (mapv (fn [impl]
           (update impl :methods
                   (fn [ms] (mapv (fn [m] (update m :name #(symbol (name %)))) ms))))
         impls)))

(defn- dart-define-form
  "The cljd deftype for TYPE-NAME with FIELDS and parsed IMPLS (boilerplate
   included, JVM-named groups among them): one deftype form."
  [type-name fields impls]
  (let [impls (mapcat dart-object-groups impls)]
    `(deftype ~type-name ~(cljd-fields fields)
       ~@(dart-impls->forms (dart-syms impls)))))

;;-----------------------------------------------------------------------------
;; Hooks for eve.deftype-proto.sio's generators
;;-----------------------------------------------------------------------------

(defn- hooks
  "This host's steps for eve.deftype-proto.sio (a function, as on the
   other hosts: a map of fns is not a literal every compiler takes)."
  []
  {:normalize (fn [parsed]
                ;; the print group is the type's printer, not an impl:
                ;; extracted here, emitted by :define as IPrint (the
                ;; JVM extraction in vocab/jvm-translate-protos, this
                ;; host's protocol in its place)
                (let [print-impl (some #(when (= "IPrintWithWriter" (name (:protocol %))) %) parsed)
                      print-fn (when print-impl
                                 (let [{:keys [args body]} (first (:methods print-impl))]
                                   (list* 'fn args body)))
                      rest-parsed (remove #(identical? % print-impl) parsed)]
                  {:impls (dart-translate-protos rest-parsed) :print print-fn}))
   ;; the deftype: sio's body (its boilerplate + the translated impls,
   ;; field reads already ISlabIO calls) grouped and placed; the print
   ;; fn as an IPrint extension after it
   :define (fn [type-name fields _parsed body print]
             (let [def-form (dart-define-form type-name fields (proto/parse-protocol-impls body))]
               (if print
                 [def-form (dart-print-form type-name print)]
                 [def-form])))
   ;; instances are built by the deftype's own positional factory
   :build (fn [type-name _fields _new-name args]
            (list* (symbol (str "->" (name type-name))) args))
   :factory-via-var? false
   ;; an eve/deftype (the [sio__ offset__] macro) prepares like an
   ;; eve-deftype: its impls translate onto cljd.core's protocols
   :slab-prepare (fn [parsed _type-name] (dart-translate-protos parsed))
   ;; cljd's deftype takes no JVM boilerplate: identity is its own
   ;; equals/hash, ISabpType (in slab-type below) its only addition
   :object-boilerplate? false
   :slab-define (fn [type-name fields impls]
                  (list (dart-define-form type-name fields (proto/parse-protocol-impls impls))))})

;;-----------------------------------------------------------------------------
;; The entry points
;;-----------------------------------------------------------------------------

(defn host-type
  "A plain type written with the JVM's names (host-deftype)."
  [_env type-name fields parsed]
  `(deftype ~type-name ~(cljd-fields fields)
     ~@(dart-impls->forms (dart-translate-protos parsed))))

(defn plain-type
  "A plain type (eve.deftype/deftype), written with ClojureScript's
   protocol names or the JVM's: translated the same way, realized as a
   cljd deftype."
  [_env type-name fields parsed]
  `(deftype ~type-name ~(cljd-fields fields)
     ~@(dart-impls->forms (dart-translate-protos parsed))))

(defn slab-type
  "An eve/deftype type (eve.deftype-proto.macros/deftype): fields
   [sio__ ^long offset__] and the type's cached fields, its impls
   translated and their bodies' field reads made ISlabIO calls by
   vocab/clj-emit-protocols, as sio/slab-type composes them — with this
   host's boilerplate (ISabpType; identity equals/hash are cljd's own)
   and define."
  [{:keys [type-name fields parsed-protos type-id emit-type-id-def? type-key]}]
  (let [field-map (into {} (map (fn [f] [(:name f) f]) fields))
        ;; the print group is the type's printer (it names no field: it
        ;; runs outside the type) — extracted, emitted as IPrint below
        print-impl (some #(when (= "IPrintWithWriter" (name (:protocol %))) %) parsed-protos)
        print-fn (when print-impl
                   (let [{:keys [args body]} (first (:methods print-impl))]
                     (list* 'fn args body)))
        parsed-protos (dart-translate-protos (remove #(identical? % print-impl) parsed-protos))
        translated (v/clj-emit-protocols parsed-protos fields field-map)
        boilerplate ['eve.deftype-proto.data/ISabpType
                     (list 'eve.deftype-proto.data/-sabp-type-key ['_] type-key)]
        cached-fields (mapv (fn [f]
                              (let [sym (symbol (:name f))]
                                (if (= :mutable (:mutability f))
                                  (with-meta sym {:mutable true})
                                  sym)))
                            (filter :cached? fields))]
    `(do
       ~(dart-define-form type-name
                          (into ['sio__ 'offset__] cached-fields)
                          (proto/parse-protocol-impls (concat boilerplate translated)))
       ~@(when print-fn [(dart-print-form type-name print-fn)])
       ~@(when emit-type-id-def?
           [`(def ~(symbol (str (name type-name) "-type-id")) ~type-id)])
       (def ~(symbol (str (name type-name) "-type-key")) ~type-key)
       ~type-name)))

(defn eve-type
  "An eve-deftype (eve.deftype.macros/eve-deftype*)."
  [ctx]
  (sio/eve-type ctx (hooks)))

(defn extend-eve-type
  "eve-extend-type: sio's ISlabIO body transform, the impls translated
   onto cljd.core's protocols first."
  [ctx]
  (sio/extend-eve-type (update ctx :parsed-protos dart-translate-protos)))

(defn existing-def
  "{:value v} when the namespace being compiled already defines sym,
   else nil (the type-id is then derived from the type's qualified
   name, the same on every host: eve.deftype-proto.macros.registry).
   A cljd build's earlier defs are Dart-side, not JVM vars, so this
   answers from the JVM mirror when the compiler keeps one and from
   the derivation otherwise."
  [env sym]
  (or (when-let [v (resolve sym)] {:value @v})
      (when-let [ns-name (get-in env [:ns :name])]
        (when-let [n (find-ns (symbol (str ns-name)))]
          (when-let [v (ns-resolve n sym)] {:value @v})))))

(defn rename-fields
  "An eve-deftype's fields and body, unrenamed: the JVM's reserved
   field names (eve.deftype-proto.sio/eve-renames) are not Dart's."
  [_env fields body]
  [fields body])

(defn reader-of
  "What a registered eve collection reads as on ClojureDart: its eve
   type (from-header) — a cljd deftype answers cljd.core's protocols,
   so no host value stands in for it."
  [from-header _host-value]
  from-header)

(defn print-form
  "The printer of a registered eve collection: none — the type's own
   IPrintWithWriter (kept in its impls) prints it."
  [_env _type-name _spec]
  nil)

(defn vector-types
  "ClojureDart's own vector types: its persistent vector and, subvec
   being a type of its own there, its subvec."
  [_env]
  '[PersistentVector Subvec])

(defn field
  "A field read in an expansion: eve.host/field, this host's member read."
  [x f]
  (list 'eve.host/field x f))
