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
  (:require [eve.deftype-proto :as proto]
            [eve.deftype-proto.vocab :as v]
            [eve.deftype-proto.macros.registry :as reg]
            [eve.deftype-proto.emission :as emission]
            [eve.deftype-proto.type-io :as type-io]
            [eve.deftype.registry :as dt-reg]
            [eve.types :as t]
            ;; the expansions this namespace's macros emit reference
            ;; these at the defining namespace's load: host-deftype's
            ;; reader registration (eve.type-readers/register!) and
            ;; deftype's sister registration + freeze/thaw protocol
            ;; (eve.flat.sister). Requiring them here loads them
            ;; before any expansion compiles — on the MACRO side.
            ;; It does NOT order a ClojureScript bundle: a macro
            ;; namespace is not a node in the cljs runtime graph, so
            ;; this require orders nothing there (measured Nov 16
            ;; 2026: the cohost-media page bundle ran the list/map/
            ;; set/vec sister registrations before eve.flat.sister's
            ;; namespace object existed, and the page died at load).
            ;; The runtime guarantee is the DEFINING namespace's own
            ;; requires — [eve.flat.sister] in every sister-registering
            ;; namespace, [eve.type-readers] in every reader-
            ;; registering one; eve.deftype-guard-test enforces both.
            ;; Neither namespace requires this one back
            ;; (type-readers: encoding + registry; sister: data,
            ;; serialize, encoding, slab-runtime, slab-io, source,
            ;; chunk — chunk/codec pull no collections), so the
            ;; layering holds: proto macros → flat registry, never
            ;; the reverse at load.
            [eve.type-readers]
            [eve.flat.sister]))

(defmacro host-deftype
  "A plain (not slab-backed) type, written with the CLJ/JVM API —
   the standard for core types across all CLJC Eve (John, Nov 14
   2026): clojure.lang.* protocol heads and method names, the CLJ
   field spellings (^:volatile-mutable, ^:unsynchronized-mutable).
   Per-host name realization stays in the host's eve.types adapter
   and eve.deftype-proto.vocab, where it already lives; this macro
   adds no name language of its own.

   The type's print implementation — an IPrintWithWriter section in
   the body (the print spelling Eve's type bodies carry), or the
   type-io declaration's :print — is realized as the type's
   print-method by the host's adapter (plan §6, G3 — the R0
   extension; a verbatim realization cannot derive it, which is
   what stopped Lane B's print-carrying callers).

   An options map may precede the protocol impls — the type-io
   declaration ({:tag :read :print}, eve.deftype-proto.type-io): a
   tagged reader is registered for the type, and a field-map printer
   is generated unless the body supplies IPrintWithWriter. This is
   the same machinery eve.deftype's macros consume (plan §8, R0)."
  [type-name fields & body]
  (let [io (type-io/type-io-spec type-name fields body)]
    (list 'do
          (t/host-type &env type-name fields (v/parse-protocol-impls (:body io)))
          (type-io/reader-form type-name fields io)
          (dt-reg/registration-form &env type-name {:storage :process-local}))))

;;=============================================================================
;; Main Macro
;;=============================================================================

(defn- residence-protocol?
  "The persistent kind's slab-residence protocols (IEveRoot/IEveSio):
   a flat sister is not slab-resident and never carries them — the
   posture eve-deftype's sisters take (eve-type generates them for
   the persistent kind alone; a proto body supplies them by hand
   because slab-type does not)."
  [protocol-sym]
  (contains? #{"IEveRoot" "IEveSio"} (name protocol-sym)))

(defn- sister-eligible?
  "Whether a proto type's body (residence groups already set aside)
   can be expanded against a field source (plan §4, slice M2): no
   method body may speak of the residence — the bare sio__/offset__
   locals or (.-sio__ this)/(.-offset__ this) — reach a written
   field by instance access ((.-f this): on a sister the written
   fields are source values, not instance fields), or name the
   sister's own generated locals (src__, sister-hash__). A body
   that does registers layout-only (nil factory) until its content
   route (plan §4, M5) — the five collections are the standing
   example: EveVector's methods read sio__ throughout."
  [parsed-protos written-fields]
  (let [bad (into #{'sio__ 'offset__ 'src__ 'sister-hash__
                    '.-sio__ '.-offset__}
                  (map (fn [f] (symbol (str ".-" (:name f)))) written-fields))
        syms (fn syms [form]
               (cond (symbol? form) [form]
                     (sequential? form) (mapcat syms form)
                     (map? form) (mapcat syms (concat (keys form) (vals form)))
                     :else []))]
    (not-any? bad
              (mapcat (fn [{:keys [methods]}]
                        (mapcat (fn [{:keys [body]}] (mapcat syms body)) methods))
                      parsed-protos))))

(defn- ctor-field-write
  "The generated ->T's write of one written field into a fresh block
   (plan §4, slice M4): the same forms the persistent kind's own
   constructor emits (eve.deftype-proto.sio's jvm-emit-field-write) —
   a primitive by the proto write emitter, a serialized field by the
   slab runtime, a reference field as its header offset or -1 for
   nil. The block offset local is off__, the I/O local sio__, as in
   the generated constructor that splices these."
  [f val-sym]
  (case (:type-class f)
    :primitive (proto/jvm-write-fn-for-type f 'off__ (:offset f) val-sym)
    :serialized (list 'eve.deftype.slab-runtime/slab-write-serialized!
                      'sio__ 'off__ (:offset f) val-sym)
    :eve-type (list 'eve.slab-io/write-i32! 'sio__ 'off__ (:offset f)
                    (list 'let ['v__m4 val-sym]
                          (list 'if (list 'nil? 'v__m4) -1
                                (list 'eve.deftype-proto.data/-root-header-off 'v__m4))))
    (proto/jvm-write-fn-for-type f 'off__ (:offset f) val-sym)))

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
        process (for user types)

   The expansion also emits the flat wiring eve-deftype's types have
   (plan §4, slices M1+M2; the forms are built by the shared
   eve.deftype-proto.emission helpers both macros consume, M3): the
   generated <TypeName>? predicate, the
   sister descriptor registration (eve.flat.sister/register-sister!),
   the IFreezeThaw freeze/thaw methods, and — where the host
   realizes one and the body is sister-eligible (see
   sister-eligible?) — the generated flat sister class itself, from
   the shared eve.types/flat-type generator eve-deftype's expansion
   calls. The descriptor's fields come from this macro's own
   registry layout; ^:cached fields are not among them (they are
   not written to the slab) — on the sister they ride as instance
   fields, rebuilt by their :derive fn from the packed source
   where one is declared (plan §3(b)(i)), else nil."
  [type-name fields & body]
  (let [parsed-fields (mapv reg/parse-field fields)
        {:keys [fields total-size]} (reg/compute-layout parsed-fields)
        type-id-sym (symbol (str (name type-name) "-type-id"))
        ;; an existing (def TypeName-type-id ...) in the namespace being
        ;; compiled (the host's eve.types reads it its way)
        existing (t/existing-def &env type-id-sym)
        type-key (dt-reg/qualified-type-key &env type-name)
        type-id (or (:value existing) (reg/derived-type-id! type-key))
        ;; Only emit (def TypeName-type-id ...) if not already defined
        emit-type-id-def? (nil? existing)
        ;; the registration contents eve-deftype's types carry and this
        ;; macro's lacked (plan §1 row 2, §5 R5): the name hash (the
        ;; header's bytes 1-3, derived from the type key as every
        ;; engine derives it) and the type tag
        name-hash (dt-reg/name-hash-of type-key)
        type-tag (str "eve/" (name type-name))
        _ (reg/register-type! (name type-name)
                              {:type-id type-id
                               :name-hash name-hash
                               :type-tag type-tag
                               :total-size total-size
                               :fields fields
                               :type-key type-key
                               :record/portable? true})
        parsed-protos (v/parse-protocol-impls body)
        ;; the flat sister's field descriptors (plan §4, slice M1):
        ;; the type's slab fields as data, built from THIS registry's
        ;; layout — proto arithmetic (natural sizes, per-field
        ;; alignment), never eve.deftype.registry's (plan §3(a)).
        ;; ^:cached fields are not among them: they occupy no slab
        ;; space and are not written (register-sister!'s contract —
        ;; the fields that are not written are not in the descriptor).
        ;; The projection is the shared proto-layer emission
        ;; (eve.deftype-proto.emission, plan §4 M3); the field list it
        ;; projects is this registry's.
        sister-fields (emission/sister-fields
                       (filterv #(not= :cached (:type-class %)) fields))
        ;; the flat sister class (plan §4, slice M2): the ctx the
        ;; shared generator consumes — eve.types/flat-type, the same
        ;; realization eve-deftype's expansion resolves — assembled
        ;; from THIS registry's layout, as M1's descriptor was:
        ;; inst-fields are the ^:cached fields (they ride the sister
        ;; as its own fields), live fields are the written
        ;; (non-cached) fields, and the walk plan the generator's
        ;; methods read by is the descriptor's sister-fields
        ;; themselves — no parallel layout derivation. The residence
        ;; groups are set aside for the sister (residence-protocol?);
        ;; a body that still speaks of the residence is not
        ;; sister-eligible and registers layout-only, as every proto
        ;; type did at M1
        written-fields (filterv #(not (:cached? %)) fields)
        cached-fields (filterv :cached? fields)
        sister-protos (vec (remove #(residence-protocol? (:protocol %)) parsed-protos))
        flat-name (symbol (str (name type-name) "__Flat"))
        sister-name (symbol (str (name type-name) "__sister"))
        field-oriented? (sister-eligible? sister-protos written-fields)
        flat-forms (when field-oriented?
                     (emission/flat-forms
                      {:env &env :type-name type-name
                       :inst-fields cached-fields
                       :live-fields written-fields
                       :slab-fields written-fields
                       :field-map (into {} (map (fn [f] [(:name f) f]) written-fields))
                       :parsed-protos sister-protos
                       :type-key type-key
                       :provides? (fn [protos & ps]
                                    (some #(v/user-provides-protocol? protos %) ps))
                       :flat-name flat-name
                       :sister-name sister-name}))
        ;; ^:cached derivations (plan §3(b)(i)): {field-keyword
        ;; fn-symbol} — the descriptor's record of how a sister's
        ;; factory rebuilds the cached values from the packed
        ;; source; the factory's generated calls carry the same fns
        cached-derivations (into {}
                                 (keep (fn [f] (when (:derive f)
                                                 [(keyword (:name f)) (:derive f)])))
                                 cached-fields)
        ;; the construction + header wiring (plan §4, slice M4),
        ;; emitted under the same gate as the sister class
        ;; (field-oriented bodies): ->T, the value constructor with
        ;; the construction law's two arities (no domain bound →
        ;; the flat sister; a domain bound or a leading sio → the
        ;; slab instance), and T__from-header, the constructor atom
        ;; roots and nested reads dispatch to, registered under
        ;; [type-id name-hash] as eve-deftype's are. The five
        ;; collections fail the gate — their bodies speak of the
        ;; residence — and keep their hand-written constructors and
        ;; register-eve-type! registrations untouched.
        ctor-name (symbol (str "->" (name type-name)))
        from-header-name (symbol (str (name type-name) "__from-header"))
        class-ctor (symbol (str (name type-name) "."))
        name-hash-bytes [(bit-and name-hash 0xFF)
                         (bit-and (bit-shift-right name-hash 8) 0xFF)
                         (bit-and (bit-shift-right name-hash 16) 0xFF)]
        written-syms (mapv (fn [f] (symbol (:name f))) written-fields)
        cached-syms (mapv (fn [f] (symbol (:name f))) cached-fields)
        m4-forms (when field-oriented?
                   [(list 'defn ctor-name
                          (str "Construct a " (name type-name) ": with a domain bound "
                               "(inside a swap!, or eve.deftype-proto.data/*domain-sio* over "
                               "a domain) the value is built in that domain; with none, its "
                               "flat sister is constructed instead — the construction law. "
                               "The leading-sio arity builds in that sio. Arguments are the "
                               "written fields, then the ^:cached fields, in declaration "
                               "order; on the flat route the cached arguments are not packed "
                               "(the sister rebuilds them by derivation, or starts them nil). "
                               "Generated by eve.deftype-proto.macros/deftype (plan §4, M4).")
                          (list (vec (into written-syms cached-syms))
                                (list 'if 'eve.deftype-proto.data/*domain-sio*
                                      (apply list ctor-name 'eve.deftype-proto.data/*domain-sio*
                                             (into written-syms cached-syms))
                                      (list 'eve.flat.sister/construct-flat
                                            type-key (vec written-syms))))
                          (list (vec (cons 'sio__ (into written-syms cached-syms)))
                                (apply list 'let ['off__ (list 'eve.slab-io/alloc! 'sio__ total-size)]
                                       (concat
                                        [(list 'eve.slab-io/write-u8! 'sio__ 'off__ 0 type-id)]
                                        (map-indexed (fn [i b]
                                                       (list 'eve.slab-io/write-u8! 'sio__ 'off__ (inc i) b))
                                                     name-hash-bytes)
                                        (map (fn [f] (ctor-field-write f (symbol (:name f))))
                                             written-fields)
                                        [(apply list class-ctor 'sio__ 'off__ cached-syms)]))))
                    (list 'defn from-header-name
                          (str "A " (name type-name) " read from the header block at "
                               "header-off__ of sio__ — the from-header constructor atom "
                               "roots and nested reads dispatch to. A ^:cached field with "
                               "a derivation is rebuilt from the block's field values, as "
                               "a sister rebuilds it from the packed source; the rest "
                               "start nil. Generated by eve.deftype-proto.macros/deftype "
                               "(plan §4, M4).")
                          ['sio__ 'header-off__]
                          (if (empty? cached-fields)
                            (list class-ctor 'sio__ 'header-off__)
                            (list 'let ['inst__ (apply list class-ctor 'sio__ 'header-off__
                                                       (repeat (count cached-fields) nil))
                                        'vals__ (list 'eve.flat.sister/field-values
                                                      (list 'eve.flat.sister/descriptor type-key)
                                                      'inst__)]
                                  (apply list class-ctor 'sio__ 'header-off__
                                         (map (fn [f]
                                                (when (:derive f)
                                                  (list
                                                   (:derive f)
                                                   (list
                                                    'reify
                                                    'eve.flat.source/IFieldSource
                                                    (list '-field ['_ 'kw] (list 'get 'vals__ 'kw))
                                                    (list '-field-map ['_] 'vals__)
                                                    (list '-source-bytes ['_] nil)))))
                                              cached-fields)))))
                    (list 'eve.deftype-proto.serialize/register-deftype-constructor!
                          type-id name-hash from-header-name)])]
    (apply list 'do
           (concat
            (when field-oriented? [(list 'declare ctor-name)])
            [(t/slab-type {:env &env :type-name type-name :fields fields :parsed-protos parsed-protos
                           :type-id type-id :total-size total-size
                           :emit-type-id-def? emit-type-id-def? :type-key type-key})
             (dt-reg/registration-form &env type-name
                                       {:type-id type-id :name-hash name-hash :type-tag type-tag
                                        :total-size total-size :type-key type-key :storage :slab :fields fields
                                        :record/portable? true})
        ;; the generated predicate: both forms answer to the type's
        ;; name (the shared pred-form, plan §2.3 + §4 M3)
             (emission/pred-form type-name type-key "eve.deftype-proto.macros/deftype")]
       ;; the sister's generation, where the host realizes one and
       ;; the body is sister-eligible (plan §4, M2) — before the
       ;; registration, whose factory is the generated Type__sister
            (when flat-forms [flat-forms])
            [;; the flat sister's registration: the descriptor, its
        ;; generated factory (nil on a host that realizes no sister
        ;; class, or for a body that is not sister-eligible — the
        ;; layout-only posture: packed values read as generic
        ;; records by their layout)
             (emission/register-sister-form type-key sister-fields
                                            (when flat-forms sister-name)
                                            {:generated-by :proto-deftype
                                             :cached-derivations cached-derivations})]
       ;; the generated freeze/thaw method pairs (eve.flat.sister/
       ;; IFreezeThaw), built by the shared proto-layer emission
       ;; (plan §4, M3) — the pair total on the persistent kind, and
       ;; the sister kind's own pair when a sister was realized
            (emission/freeze-thaw-forms type-name (when flat-forms flat-name))
       ;; the construction + header wiring (plan §4, M4): the
       ;; value constructor and the from-header registration
            m4-forms))))

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
