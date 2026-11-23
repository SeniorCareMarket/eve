(ns eve.types
  "eve's types on the JVM, and on jolt (whose deftype takes the JVM's
   interfaces): a type is a deftype with the JVM's interface names, as eve's
   type language writes them; ClojureScript's names translate onto them
   (eve.deftype-proto.vocab/jvm-translate-protos).

   A JVM also runs ClojureScript compilers, whose macros expand in it: an
   expansion for a ClojureScript compile (its &env has :ns) is a ClojureScript
   type (eve.types-js), and the rest the JVM's.
   The entry points eve's macros call (every eve.types has them):
     host-type, plain-type, slab-type, eve-type, extend-eve-type,
     existing-def, rename-fields, reader-of, print-form, vector-types."
  (:require [eve.deftype-proto.vocab :as v]
            [eve.deftype-proto.sio :as sio]
            [eve.types-js :as js]))

(defn- cljs?
  "True for an expansion a ClojureScript compiler running in this JVM asked
   for (its &env carries :ns)."
  [env]
  (some? (:ns env)))

(defn- define [type-name fields forms]
  [`(clojure.core/deftype ~type-name ~fields ~@forms)])

(defn- hooks
  "This host's steps for eve.deftype-proto.sio (a function: clojerl compiles a def's
   value as a literal, which a map of functions is not)."
  []
  {:normalize v/jvm-translate-protos
   :define (fn [type-name fields _impls forms print]
             (concat (define type-name fields forms)
                     (when print [(sio/print-method-form type-name print)])))
   :build (fn [type-name _fields _new-name args] (list* 'new type-name args))
   :factory-via-var? false
   :slab-prepare (fn [parsed _type-name] parsed)
   :object-boilerplate? true
   :slab-define define})

(declare plain-type)

(defn host-type
  "A plain type (host-deftype). Its realization is plain-type's,
   shared: the vocab translation, the derived methods, the reserved
   field renames, and — the R0 extension's point (plan §6, G3) — an
   IPrintWithWriter body realized as the type's print-method, where
   this entry point previously passed impls through verbatim and
   could not derive one. That verbatim shape is what stopped Lane
   B's print-carrying callers (RSeq, MmapAtom, the Obj trio,
   HeapEveArray). host-deftype's contract is the CLJ/JVM API (John,
   Nov 14 2026: core types standardize on it); the shared
   realization underneath is this leg's business, as it already was
   for plain-type. On this host the two entry points differ only in
   which macro asked."
  [env type-name fields parsed]
  (plain-type env type-name fields parsed))

(defn plain-type
  "A plain type (eve.deftype/deftype): ClojureScript's names translated, the
   JVM's reserved field names renamed, an IPrintWithWriter body its
   print-method."
  [env type-name fields parsed]
  (if (cljs? env)
    (js/plain-type type-name fields parsed js/compiler-twist)
    (let [[fields parsed] (sio/plain-renames fields parsed nil)
          {:keys [impls print]} (v/jvm-translate-protos parsed)]
      `(do (clojure.core/deftype ~type-name ~fields ~@(impls->forms impls))
           ~@(when print [(sio/print-method-form type-name print)])
           ~type-name))))

(defn slab-type
  "An eve/deftype type (eve.deftype-proto.macros/deftype)."
  [m]
  (if (cljs? (:env m)) (js/slab-type m) (sio/slab-type m (hooks))))

(defn eve-type
  "An eve-deftype (eve.deftype.macros/eve-deftype*)."
  [ctx]
  (if (cljs? (:env ctx)) (js/eve-type ctx js/compiler-twist) (sio/eve-type ctx (hooks))))

(defn flat-type
  "An eve-deftype's generated flat sister (eve.deftype.macros/eve-deftype*,
   lane H5): a ClojureScript compile's from eve.types-js/flat-type (flat
   lanes U4a), the JVM's from the shared eve.deftype-proto.sio/flat-type."
  [ctx]
  (if (cljs? (:env ctx)) (js/flat-type ctx) (sio/flat-type ctx (hooks))))

(defn extend-eve-type
  "eve-extend-type."
  [ctx]
  (if (cljs? (:env ctx)) (js/extend-eve-type ctx) (sio/extend-eve-type ctx)))

(defn existing-def
  "{:value v} when the namespace being compiled already defines sym, else nil."
  [env sym]
  (if (cljs? env)
    (js/compiler-existing-def env sym)
    (when-let [v (resolve sym)] {:value @v})))

(defn rename-fields
  "An eve-deftype's fields and body with the field names the JVM reserves
   renamed (__hash; none for a ClojureScript compile): [fields body]."
  [env fields body]
  (if (cljs? env) [fields body] (sio/eve-renames fields body)))

(defn reader-of
  "What a registered eve collection reads as on this host: its eve type
   (from-header; host-value is for a host whose types cannot stand in for its
   collections)."
  [from-header _host-value]
  from-header)

(defn print-form
  "The printer of a registered eve collection of shape spec ({:open :close}
   for a sequential one, :map for a map): print-method through clojure.core's
   own printers, as Clojure prints its collections — where this Clojure has
   them (jolt prints by its own and has not); a ClojureScript compile's is
   ClojureScript's (eve.types-js)."
  [env type-name spec]
  (cond
    (cljs? env) (js/print-form type-name spec)
    (= :map spec)
    (when (resolve 'clojure.core/print-map)
      (let [m (gensym "m") w (gensym "w")]
        `(defmethod print-method ~type-name [~m ~w]
           ((var clojure.core/print-map) ~m print-method ~w))))
    :else
    (when (resolve 'clojure.core/print-sequential)
      (let [v (gensym "v") w (gensym "w")]
        `(defmethod print-method ~type-name [~v ~w]
           ((var clojure.core/print-sequential) ~(:open spec) (var clojure.core/pr-on) " "
                                                ~(:close spec) (seq ~v) ~w))))))

(defn vector-types
  "The host's own vector types, which a protocol extended to the host's
   vectors names: IPersistentVector's implementors (its subvec among them);
   a ClojureScript compile's are ClojureScript's (eve.types-js)."
  [env]
  (if (cljs? env) (js/vector-types) '[clojure.lang.IPersistentVector]))
