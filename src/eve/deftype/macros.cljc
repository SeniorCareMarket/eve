(ns eve.deftype.macros
  "The expansion of eve.deftype's macros, as functions of the macro's &env —
   one implementation for every host that expands them; what a host realizes
   its own way (its types, eve memory's field reads) is its eve.types
   (eve/adapters/types/).

   eve-deftype builds entirely on the slab allocator infrastructure of
   eve.deftype-proto: types use [offset__] on CLJS and [^long slab-off__ sio__]
   on the JVM.

   Added on top of eve-slab-deftype:
   - :serialized field class (arbitrary Clojure values, auto-serialized to slab blocks)
   - Auto-generated IDirectSerialize, ISabStorable (atom-storable)
   - Auto-generated ISabpType (type registry key)

   Field metadata:
     ^:int32, ^:uint32, ^:float32, ^:float64 — primitive slab fields
     ^:MyEveType — reference to another eve-deftype/slab-deftype (stored as offset)
     (no hint) — serialized, any Clojure value

   Mutability:
     (default) — immutable, set at construction
     ^:mutable — mutable via set! (single-worker)
     ^:volatile-mutable — atomic via set!/cas! (cross-worker)"
  (:require [eve.host :refer [field]]
            [eve.deftype.registry :as reg]
            [eve.deftype-proto :as proto]
            [eve.deftype-proto.emission :as emission]
            [eve.deftype-proto.type-io :as type-io]
            [eve.deftype-proto.vocab :as v]
            [eve.types :as t]))

;;-----------------------------------------------------------------------------
;; deftype: a host type, written once
;;-----------------------------------------------------------------------------

;; The type-io machinery (type-io-spec / reader-form) lives in the proto
;; layer, eve.deftype-proto.type-io (plan §8, slice R0): proto's
;; host-deftype consumes it there too. This namespace's macro keeps
;; calling it unchanged — same code, new home.

;;-----------------------------------------------------------------------------
;; eve-deftype macro
;;-----------------------------------------------------------------------------

(defn eve-deftype*
  "The expansion of eve.deftype/deftype (env: the macro's &env).

   A type that lives in eve memory: (->T field ...) allocates its block in the
   current eve domain (the running swap!'s, else the process's own) and writes
   its fields there, so an instance can sit in an atom, shared across
   processes and hosts. Written once, with ClojureScript's protocol names
   (ICounted/-count, ...) or the JVM's (clojure.lang.Counted/count, ...).

   Fields:
     ^:int32, ^:uint32, ^:float32, ^:float64 — primitives in eve memory
     ^:MyEveType — a reference to another eve-deftype, read when a method
                   reads it
     (no hint)   — any eve value, in eve's encoding
     ^:host, or ^:mutable with no slab type — on the instance only, in the
                   process's own memory (a cache, a transient's edit token);
                   nil when an instance is read back from eve memory
   An immutable field (not a ^:MyEveType reference) is read when the instance
   is reached and kept on it, so (field x field) reads it on every host, as a
   deftype's field.

   Mutability:
     (default) — immutable, set at construction
     ^:mutable with a slab type — mutable via set! (single-worker)
     ^:volatile-mutable — atomic via set!/cas! (cross-worker)

   Example:
     (deftype Counter [^:mutable ^:int32 count label]
       ICounted
       (-count [this] count))"
  [env type-name fields body]
  (let [io (type-io/type-io-spec type-name fields body)
        body (:body io)
        reader (type-io/reader-form type-name fields io)
        ;; the field names the host reserves, renamed (the JVM's __hash)
        [fields body] (t/rename-fields env fields body)
        ;; Parse fields using registry (supports :serialized and :host)
        parsed-fields (mapv reg/parse-field fields)
        ;; Compute layout using registry (4-byte header, 4-byte aligned)
        {:keys [fields total-size]} (reg/compute-layout parsed-fields)
        slab-fields (filterv (complement v/host-local?) fields)
        live-fields (filterv v/live? fields)
        inst-fields (filterv #(or (v/cached? %) (v/host-local? %)) fields)
        type-tag (str "eve/" (name type-name))
        type-key (reg/qualified-type-key env type-name)
        ;; type-id byte and name hash, both from the name: the same in every
        ;; process (eve.deftype.registry)
        [type-id name-hash] (reg/claim-type-id! type-key)
        ;; header bytes 1-3
        name-hash-bytes [(bit-and name-hash 0xFF)
                         (bit-and (bit-shift-right name-hash 8) 0xFF)
                         (bit-and (bit-shift-right name-hash 16) 0xFF)]
        sab-tag-kw (keyword "eve-deftype" (name type-name))

        ;; Register in compile-time registry
        _ (reg/register-type! (name type-name)
                              {:type-id type-id
                               :name-hash name-hash
                               :type-tag type-tag
                               :total-size total-size
                               :fields slab-fields
                               :type-key type-key
                               ;; an eve-deftype's persisted form is its
                               ;; field values: certifiably portable
                               ;; as an eve.record field value.
                               :record/portable? true})

        ;; the fields set!/cas! rewrite to eve memory (a host-local field's
        ;; set! is the instance's own)
        field-map (into {} (map (fn [f] [(:name f) f]) slab-fields))
        ;; Parse user protocol impls -- the type's author's, then the dual-type field lookup
        ;; every type answers (emission/default-lookup-forms: an ILookup over the slab fields,
        ;; unless the body answers lookups itself), appended as the author would write it
        body (concat body (emission/default-lookup-forms (proto/parse-protocol-impls body)
                                                         (map (comp symbol :name) slab-fields)))
        parsed-protos (proto/parse-protocol-impls body)
        has-volatile? (proto/has-volatile-fields? slab-fields)
        serialized-fields (filter #(= :serialized (:type-class %)) slab-fields)
        ctor-name (symbol (str "->" (name type-name)))
        ctor-args (mapv (fn [f] (symbol (:name f))) fields)
        new-name (symbol (str (name type-name) "__new"))
        load-name (symbol (str (name type-name) "__from-slab"))
        ;; an instance's own fields, as the constructor has them, and as an
        ;; instance read from eve memory has them (host-local ones nil)
        inst-vals (mapv #(symbol (:name %)) inst-fields)
        provides? (fn [protos & ps] (some #(proto/user-provides-protocol? protos %) ps))
        ;; the flat sister (lane H5): the field descriptors its
        ;; registration carries — the slab fields as data, in declaration
        ;; order (^:host fields are not among them: they are not written,
        ;; FORMAT-V1 §6), projected by the shared proto-layer emission
        ;; (eve.deftype-proto.emission, plan §4 M3) from THIS registry's
        ;; layout — the projection is shared, the layout stays this
        ;; engine's
        sister-fields (emission/sister-fields slab-fields)
        flat-name (symbol (str (name type-name) "__Flat"))
        sister-name (symbol (str (name type-name) "__sister"))
        ;; the sister's generation, where the host realizes one (its
        ;; eve.types has flat-type); a host that does not registers the
        ;; layout alone, and the packed reader answers the generic form
        flat-forms (emission/flat-forms
                     {:env env :type-name type-name :inst-fields inst-fields
                      :live-fields live-fields :slab-fields slab-fields
                      :field-map field-map :parsed-protos parsed-protos
                      :type-key type-key :provides? provides?
                      :flat-name flat-name :sister-name sister-name})
        ;; the generated predicate: both forms answer to the type's
        ;; name. Library authors wrap it under their own predicate
        ;; name as they choose (eve.dataset/dataset? wraps Dataset?).
        ;; the forms' classes, where the host's instance? reads a class (the JVM's; a
        ;; ClojureScript compile, whose macros run here too): each form one instance? test
        pred-form (emission/pred-form type-name type-key "eve-deftype"
                                      (when #?(:bb false :jank false :glj false :clj true :default false)
                                        (cond-> [type-name] flat-forms (conj flat-name))))]

    (apply list 'do
          (t/eve-type {:env env :type-name type-name :inst-fields inst-fields :live-fields live-fields
                       :slab-fields slab-fields :field-map field-map :parsed-protos parsed-protos
                       :type-key type-key :provides? provides? :has-volatile? has-volatile?
                       :serialized-fields serialized-fields :ctor-name ctor-name :ctor-args ctor-args
                       :new-name new-name :load-name load-name :inst-vals inst-vals
                       :total-size total-size :type-id type-id :name-hash name-hash
                       :name-hash-bytes name-hash-bytes :sab-tag-kw sab-tag-kw})
          (reg/registration-form env type-name
            {:type-id type-id :name-hash name-hash :type-key type-key
             :storage :slab :total-size total-size :fields slab-fields
             :record/portable? true})
          ;; the fields that point at other blocks, so eve.retire walks a stored value of this type as it walks
          ;; its own collections (a cons cell's tail is the value it replaced, and stays) — a tree's nodes too
          (list 'eve.deftype-proto.serialize/register-deftype-layout! type-id name-hash
                (vec (keep (fn [f] (case (:type-class f)
                                     :eve-type [(:offset f) :ref]
                                     :serialized [(:offset f) :value]
                                     :primitive (when (:pointer? f) [(:offset f) :ref])
                                     nil))
                           slab-fields))
                total-size)
          ;; the fields that hold a reference, registered for eve.tally (serialize/type-refs): what the count
          ;; holds and drops for an instance — a ^:MyEveType field another instance's offset, a serialized field
          ;; the offset of its [len][EVE bytes] block
          (list 'eve.deftype-proto.serialize/register-type-refs! type-id name-hash
                (vec (keep (fn [f]
                             (when-let [k (v/ref-kind f)] [(:offset f) k]))
                           slab-fields)))
          reader
          ;; the predicate is declared here, where it always stood, and defined last: its instance? arms name
          ;; the flat sister's class, which the forms below define
          (list 'declare (symbol (str (name type-name) "?")))
          ;; the flat sister (lane H5): its generation, where the host
          ;; realizes one, and its registration — the type key, the slab
          ;; fields as data, and the generated factory — so a packed
          ;; value of this type reads as its sister, and a slab instance
          ;; packs by it (eve.flat.sister). Appended after the release's
          ;; reader registration, the expansion's final act (reconciled
          ;; with release/eve-0.9.3.2: every release emission keeps its
          ;; relative order; the sister rides the same tail)
          (concat (when flat-forms [flat-forms])
                  [(emission/register-sister-form type-key sister-fields
                         (when flat-forms sister-name))]
                  ;; the generated freeze/thaw methods
                  ;; (eve.flat.sister/IFreezeThaw): the pair is
                  ;; TOTAL on both kinds — -freeze on the
                  ;; persistent kind freezes it, on the sister
                  ;; it freezes the flat value into a domain;
                  ;; -thaw on the sister thaws it, on the
                  ;; persistent kind it is the materialization —
                  ;; so the dispatcher never calls a method a
                  ;; kind does not have. Both pairs are built by
                  ;; the shared proto-layer emission (plan §4 M3).
                  (emission/freeze-thaw-forms type-name
                                               (when flat-forms flat-name)
                                               env)
                  [pred-form]))))

;;-----------------------------------------------------------------------------
;; eve-extend-type macro
;;-----------------------------------------------------------------------------

(defn eve-extend-type*
  "The expansion of eve.deftype/eve-extend-type (env: the macro's &env).

   Extend protocols to an existing eve-deftype. Field names from the type's
   declaration are available as local bindings in method bodies. set! and cas!
   on declared fields are rewritten to slab operations."
  [env type-name body]
  (let [type-str (name type-name)
        type-reg (reg/lookup-type type-str)
        _ (when-not type-reg
            (throw (ex-info (str "eve-extend-type: unknown type " type-str
                                 ". Must be defined with eve-deftype first.")
                            {:type type-str})))
        fields (:fields type-reg)
        field-map (into {} (map (fn [f] [(:name f) f]) fields))
        has-volatile? (proto/has-volatile-fields? fields)
        parsed-protos (proto/parse-protocol-impls body)]
    (t/extend-eve-type {:env env :type-name type-name :fields fields :field-map field-map
                        :parsed-protos parsed-protos :has-volatile? has-volatile?})))

(defn extend-host-vectors*
  "(extend-protocol proto T impls... ...) over the host's own vector types
   (eve.types/vector-types), each with the same method bodies."
  [env proto impls]
  `(extend-protocol ~proto ~@(mapcat (fn [t] (cons t impls)) (t/vector-types env))))
