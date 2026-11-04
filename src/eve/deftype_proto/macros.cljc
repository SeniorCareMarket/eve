(ns eve.deftype-proto.macros
  "Unified CLJC deftype macro for Eve data structures.

   Users write CLJ-style protocol names (clojure.lang.Counted, count): eve's
   type language (eve.deftype-proto.vocab). The host's adapter (eve.types,
   eve/adapters/types/) realizes the type — ClojureScript's protocols on a
   ClojureScript host, the JVM's names as written on the JVM, each other
   host's own types.

   Both platforms use ISlabIO for field access.
   Deftype fields are always [sio__ ^long offset__] on both platforms.

   Example:
     (eve/deftype EvePoint [^:int32 x ^:int32 y]
       clojure.lang.Counted
       (count [_] 2))"
  (:refer-clojure :exclude [deftype])
  (:require [eve.deftype-proto.vocab :as v]
            [eve.deftype-proto.macros.registry :as reg]
            [eve.types :as t]))

(defmacro host-deftype
  "A plain (not slab-backed) type written with the JVM's names, as the host's
   eve.types realizes it (clojure.core/deftype on the JVM; each other host's
   own types, translated)."
  [type-name fields & body]
  (t/host-type &env type-name fields (v/parse-protocol-impls body)))

;;=============================================================================
;; Main Macro
;;=============================================================================

(defmacro deftype
  "Define a unified CLJC Eve type.

   Fields support type hints: ^:int32, ^:uint32, ^:float32, ^:float64.
   Protocol implementations use CLJ-style names (clojure.lang.Counted, count),
   which the host's eve.types realizes (ClojureScript's protocols there,
   ClojureCLR's, basilisp's, clojerl's, ...).

   Both platforms store [sio__ offset__] — ISlabIO context + slab offset.
   Field reads emit ISlabIO protocol calls on both platforms.

   Type-id resolution (in priority order):
     1. (def TypeName-type-id ...) in same namespace (convention lookup)
     2. Derived from the type's namespace-qualified name, as an
        eve-deftype's (eve.deftype.registry/type-id-of), the same in every
        process (for user types)"
  [type-name fields & body]
  (let [parsed-fields (mapv reg/parse-field fields)
        {:keys [fields total-size]} (reg/compute-layout parsed-fields)
        type-id-sym (symbol (str (name type-name) "-type-id"))
        ;; an existing (def TypeName-type-id ...) in the namespace being
        ;; compiled (the host's eve.types reads it its way)
        existing (t/existing-def &env type-id-sym)
        type-key (str (ns-name *ns*) "/" (name type-name))
        type-id (or (:value existing) (reg/derived-type-id! type-key))
        ;; Only emit (def TypeName-type-id ...) if not already defined
        emit-type-id-def? (nil? existing)
        _ (reg/register-type! (name type-name)
                              {:type-id type-id
                               :total-size total-size
                               :fields fields
                               :type-key type-key})
        parsed-protos (v/parse-protocol-impls body)]
    (t/slab-type {:env &env :type-name type-name :fields fields :parsed-protos parsed-protos
                  :type-id type-id :total-size total-size
                  :emit-type-id-def? emit-type-id-def? :type-key type-key})))

;;=============================================================================
;; Registration Macro
;;=============================================================================

(defmacro register-eve-type!
  "Generate the registration boilerplate for an Eve collection type — one
   emission for every host (the CLJS/CLJ fork this replaced is SEAM-MAP L-07).

   Called AFTER deftype and constructor functions are defined.

   Options map keys:
     :fast-tag       — pointer tag constant (e.g. ser/FAST_TAG_SAB_VEC)
     :type-id        — header type-id constant (e.g. EveVector-type-id)
     :from-header    — (fn [sio header-off] → instance) constructor symbol
     :host-value     — (fn [sio header-off] → the host's own collection), for a
                       host whose types cannot stand in for its collections
                       (optional; which the host reads is eve.types/reader-of)
     :dispose        — (fn [instance] → nil) disposal function symbol (optional)
     :builder-pred   — predicate for auto-conversion (e.g. vector?) (optional)
     :builder-ctor   — (fn [sio coll] → instance) constructor for builder (optional)
     :coll-writer    — {:tag :vec, :fn writer-fn-symbol} direct slab writer (optional)
     :type           — the type (the symbol eve/deftype defined)
     :print          — how it prints: {:open \"[\" :close \"]\"} for a sequential
                       type, :map for a map — as the host prints its own
                       collections (eve.types/print-form; optional)
     :print-fn       — symbol of a print helper, run once (optional)

   Registers, in eve.deftype-proto.serialize's one registry: the type
   constructor (by pointer tag and header type-id), the header disposer, the
   builder, the collection writer; then runs print-fn. What it registers are
   named fns (<type-id>--from-header, --dispose, --build): a fn that a
   top-level form makes is interpreted on clojerl (its Core Erlang
   evaluator), a defn's compiled."
  [opts-map]
  (let [opts (if (symbol? opts-map) (eval opts-map) opts-map)
        {:keys [fast-tag type-id from-header host-value dispose builder-pred builder-ctor
                coll-writer type print print-fn]} opts
        named (fn [suffix] (symbol (str (name type-id) "--" suffix)))]
    `(do
       ;; Type constructor: (fn [sio header-off]), what this host reads
       (~'defn ~(named "from-header") [sio# header-off#]
        (~(t/reader-of from-header host-value) sio# header-off#))
       (eve.deftype-proto.serialize/register-type-constructor!
        ~fast-tag ~type-id ~(named "from-header"))

       ;; Header disposer: frees every block the root value owns
       ~@(when dispose
           [`(~'defn ~(named "dispose") [sio# header-off#]
              (~dispose (~from-header sio# header-off#)))
            `(eve.deftype-proto.serialize/register-header-disposer! ~type-id ~(named "dispose"))])

       ;; Builder for auto-conversion of plain collections
       ~@(when (and builder-pred builder-ctor)
           [`(~'defn ~(named "build") [sio# v#] (~builder-ctor sio# v#))
            `(eve.deftype-proto.serialize/register-builder!
              ~builder-pred ~(named "build"))])

       ;; Direct collection writer (the JVM codec's fast path)
       ~@(when coll-writer
           [`(eve.deftype-proto.serialize/register-coll-writer! ~(:tag coll-writer) ~(:fn coll-writer))])

       ;; Printing: the host's printer for the type's shape
       ~@(when-let [f (and print (t/print-form &env type print))]
           [f])
       ~@(when print-fn
           [`(~print-fn)]))))
