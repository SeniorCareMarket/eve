(ns eve.deftype-proto.sio
  "A type's realization on every host whose types reach eve memory through
   ISlabIO — an instance holds sio__ and its block's offset: the JVM,
   babashka, ClojureCLR, basilisp, jolt and clojerl. Written once; a host's
   adapter (eve.types, eve/adapters/types/) passes its own steps as hooks:

     :normalize    (fn [parsed] {:impls :print}) — an eve-deftype body's impls
                   before its fields are read into them (ClojureScript's names
                   as the JVM's), and its print fn (or nil)
     :define       (fn [type-name fields impls forms print] [form ...]) — an
                   eve-deftype's deftype and print-method: impls parsed, forms
                   the same impls as a deftype body
     :build        (fn [type-name fields new-name args] form) — how an
                   eve-deftype instance is built
     :factory-via-var? — the deftype's own factory is kept as T__new before
                   ->T becomes the constructor (sci builds (T. x) through ->T)
     :slab-prepare (fn [parsed type-name] parsed) — an eve/deftype body's
                   impls before its fields are read into them
     :object-boilerplate? — whether an eve/deftype type gets IHashEq and
                   Object by default
     :slab-define  (fn [type-name fields forms] [form ...]) — an eve/deftype
                   type's deftype, forms its body"
  (:require [eve.deftype-proto :as proto]
            [eve.deftype-proto.vocab :as v]))

;;-----------------------------------------------------------------------------
;; Field reads and writes through ISlabIO
;;-----------------------------------------------------------------------------

(defn- jvm-emit-field-read
  "Emit a JVM field read expression."
  [field-spec slab-off-expr]
  (case (:type-class field-spec)
    :primitive
    (proto/jvm-read-fn-for-type field-spec slab-off-expr (:offset field-spec))

    ;; a reference to another eve type reads as that instance (nil for the -1
    ;; sentinel), as on CLJS — so one method body serves both hosts
    :eve-type
    (list 'let ['off__eve (proto/jvm-read-fn-for-type field-spec slab-off-expr (:offset field-spec))]
          (list 'when (list 'not= -1 'off__eve)
                ;; an instance read from eve memory (its cached fields read)
                (list (symbol (str (name (:type-hint field-spec)) "__from-slab"))
                      'sio__ 'off__eve)))

    :serialized
    (list 'eve.deftype.slab-runtime/slab-read-serialized
          'sio__ slab-off-expr (:offset field-spec))))

(defn- jvm-field-bindings
  "Generate let-bindings for reading all fields on JVM."
  [fields slab-off-expr]
  (vec (mapcat (fn [f]
                 [(symbol (:name f))
                  (jvm-emit-field-read f slab-off-expr)])
               fields)))

(defn- jvm-emit-field-write
  "Emit a JVM field write expression."
  [field-spec slab-off-expr val-expr]
  (case (:type-class field-spec)
    :primitive
    (proto/jvm-write-fn-for-type
     (assoc field-spec :mutability
            (if (= :volatile-mutable (:mutability field-spec))
              :volatile-mutable :mutable))
     slab-off-expr (:offset field-spec) val-expr)

    ;; an instance (or nil) is written as its header offset (or -1), as on CLJS
    :eve-type
    (proto/jvm-write-fn-for-type
     (assoc field-spec :mutability
            (if (= :volatile-mutable (:mutability field-spec))
              :volatile-mutable :mutable))
     slab-off-expr (:offset field-spec)
     (list 'let ['v__eve val-expr]
           (list 'if (list 'nil? 'v__eve) -1
                 (list 'eve.deftype-proto.data/-root-header-off 'v__eve))))

    :serialized
    (list 'eve.deftype.slab-runtime/slab-write-serialized!
          'sio__ slab-off-expr (:offset field-spec) val-expr)))

(defn- jvm-rewrite-set!-forms
  "Rewrite (set! field val) to JVM ISlabIO write operations."
  [form field-map slab-off-expr]
  (cond
    (and (seq? form)
         (= 'set! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          val-expr (jvm-rewrite-set!-forms (nth form 2) field-map slab-off-expr)
          write (jvm-emit-field-write field-spec slab-off-expr val-expr)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (if-let [kind (v/ref-kind field-spec)]
        ;; a reference field written in place: the store rule (eve.tally/store!) — a counted instance holds the
        ;; new reference, then lets the old one go
        (list 'let ['old__ref (list 'eve.slab-io/read-i32 'sio__ slab-off-expr (:offset field-spec))
                    'ret__ref write]
              (list 'eve.deftype.slab-runtime/slab-stored! 'sio__ slab-off-expr (:offset field-spec) 'old__ref kind)
              'ret__ref)
        write))

    (and (seq? form)
         (= 'cas! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))]
      (when (not= :volatile-mutable (:mutability field-spec))
        (throw (ex-info "cas! requires ^:volatile-mutable field" {:field (:name field-spec)})))
      (when (= :serialized (:type-class field-spec))
        (throw (ex-info "cas! not supported on serialized fields" {:field (:name field-spec)})))
      (when (not= :int32 (:type-hint field-spec))
        (throw (ex-info "cas! only supported on :int32 fields on JVM" {:field (:name field-spec)})))
      ;; Returns true iff the exchange succeeded (old value == expected).
      (let [expected-expr (jvm-rewrite-set!-forms (nth form 2) field-map slab-off-expr)
            new-expr (jvm-rewrite-set!-forms (nth form 3) field-map slab-off-expr)]
        (if-let [kind (v/ref-kind field-spec)]
          ;; an ^:int32 ^:ref field's cas!: when it lands, the store rule (eve.tally/store!) — expected out, new in
          (list 'let ['exp__ref (list 'unchecked-int expected-expr)
                      'new__ref (list 'unchecked-int new-expr)
                      'ok__ref (list '== 'exp__ref
                                     (list 'eve.slab-io/cas-i32! 'sio__ slab-off-expr (:offset field-spec)
                                           'exp__ref 'new__ref))]
                (list 'when 'ok__ref
                      (list 'eve.deftype.slab-runtime/ref-stored! 'sio__ slab-off-expr 'exp__ref 'new__ref kind))
                'ok__ref)
          ;; (the field's 32 bits, as its write stores them: unchecked)
          (list '==
                (list 'unchecked-int expected-expr)
                (list 'eve.slab-io/cas-i32!
                      'sio__ slab-off-expr (:offset field-spec)
                      (list 'unchecked-int expected-expr) (list 'unchecked-int new-expr))))))

    (seq? form)
    (with-meta (apply list (map #(jvm-rewrite-set!-forms % field-map slab-off-expr) form))
      (meta form))
    (vector? form)
    (with-meta (mapv #(jvm-rewrite-set!-forms % field-map slab-off-expr) form)
      (meta form))
    (map? form)
    (with-meta (into {} (map (fn [[k v]]
                               [(jvm-rewrite-set!-forms k field-map slab-off-expr)
                                (jvm-rewrite-set!-forms v field-map slab-off-expr)])
                             form))
      (meta form))
    :else form))

(defn- jvm-transform-method-body
  "Transform method body for JVM deftype."
  [body fields field-map]
  (let [slab-off-sym 'slab-off__
        field-read-bindings (jvm-field-bindings fields slab-off-sym)
        rewritten (map #(jvm-rewrite-set!-forms % field-map slab-off-sym) body)]
    (if (empty? field-read-bindings)
      rewritten
      (list (apply list 'let field-read-bindings (vec rewritten))))))

(defn print-method-form
  "(defmethod print-method T ...) through print-fn, a (fn [this writer opts])."
  [type-name print-fn]
  (when print-fn
    (let [x (gensym "x") w (gensym "w")]
      `(do
         (defmethod print-method ~type-name [~x ~w] (~print-fn ~x ~w nil))
         ;; Only emit the optional host surface when present. This is
         ;; macro-time capability detection, not a Java MultiFn assumption.
         ~@(when #?(:bb false :default (resolve 'print-dup))
             [(list 'defmethod 'print-dup type-name [x w]
                    (list print-fn x w nil))])))))

;;-----------------------------------------------------------------------------
;; eve-deftype
;;-----------------------------------------------------------------------------

(defn eve-type
  "An eve-deftype on a host whose types reach eve memory through ISlabIO
   (eve.deftype.macros/eve-deftype* gives ctx, the host's adapter hooks)."
  [{:keys [type-name inst-fields live-fields slab-fields field-map parsed-protos type-key
           provides? ctor-name ctor-args new-name load-name inst-vals total-size type-id
           name-hash name-hash-bytes]}
   {:keys [normalize define build factory-via-var?]}]
  (let [slab-off-field (with-meta 'slab-off__ {:tag 'long})
        inst-syms (mapv #(v/instance-field-sym % {:unsynchronized-mutable true}) inst-fields)

        ;; the body's impls as the host's deftype takes them before the
        ;; fields are read into them (ClojureScript's names as the JVM's)
        {:keys [impls print]} (normalize parsed-protos)

        transformed-protos
        (mapcat (fn [{:keys [protocol methods]}]
                  (cons protocol
                        (map (fn [{:keys [name args body]}]
                               (let [tbody (jvm-transform-method-body
                                            body live-fields field-map)]
                                 (list name args (cons 'do tbody))))
                             methods)))
                impls)

        boilerplate
        (concat
         ;; ISabpType
         ['eve.deftype-proto.data/ISabpType
          (list 'eve.deftype-proto.data/-sabp-type-key ['_] type-key)]

         ;; IEveRoot — atom root support (JVM), unless the type provides its own.
         ;; The header offset is only meaningful in the allocating slab
         ;; domain; swap! rejects foreign-domain values loudly
         ;; (see eve.atom/jvm-resolve-new-ptr).
         (when-not (provides? impls 'eve.deftype-proto.data/IEveRoot 'd/IEveRoot)
           ['eve.deftype-proto.data/IEveRoot
            (list 'eve.deftype-proto.data/-root-header-off ['_] 'slab-off__)])

         ;; IEveSio — the domain the value reads through
         (when-not (provides? impls 'eve.deftype-proto.data/IEveSio 'd/IEveSio)
           ['eve.deftype-proto.data/IEveSio
            (list 'eve.deftype-proto.data/-eve-sio ['_] 'sio__)])

         ;; IHashEq
         (when-not (provides? impls 'clojure.lang.IHashEq 'IHash)
           ['clojure.lang.IHashEq
            ;; (hash long) is Murmur3's hashLong; bb has no Murmur3 class
            (list 'hasheq ['_]
                  (list 'clojure.core/hash 'slab-off__))])

         ;; Object
         (when-not (provides? impls 'java.lang.Object 'Object)
           ['java.lang.Object
            (list 'equals ['_ 'other]
                  (list 'and
                        (list 'instance? type-name 'other)
                        (list '== 'slab-off__
                              (list 'eve.host/field
                                    (with-meta 'other {:tag type-name}) 'slab-off__))))
            (list 'hashCode ['this] (list '.hasheq 'this))
            (list 'toString ['_]
                  (list 'str (str "#eve/" (name type-name) " {:offset ")
                        'slab-off__ "}"))]))

        field-writes
        (map (fn [f]
               (jvm-emit-field-write f 'slab-off__ (symbol (:name f))))
             slab-fields)

        ;; how an instance is built (the host's build hook)
        all-syms (into (vec inst-syms) [slab-off-field 'sio__])
        build-form (fn [args] (build type-name all-syms new-name args))
        body (concat boilerplate transformed-protos)]

    `(do
       ;; methods may build new instances: the constructor, declared first
       (~'declare ~ctor-name ~load-name ~@(when factory-via-var? [new-name]))
       ;; the host's deftype: its cached and host-local fields,
       ;; [slab-off__ sio__], and its print-method
       ~@(define type-name all-syms (proto/parse-protocol-impls body) body print)
       ~@(when factory-via-var? [`(~'def ~new-name ~ctor-name)])

       ;; An instance read from eve memory: its cached fields read once
       (~'defn ~load-name [~'sio__ ~'slab-off__]
               ~(build-form (concat (map (fn [f]
                                           (when (v/cached? f)
                                             (jvm-emit-field-read f 'slab-off__)))
                                         inst-fields)
                                    ['slab-off__ 'sio__])))

       ;; Constructor — the same fields as on CLJS. Inside a swap!
       ;; (a domain bound) it allocates in that domain. With NO
       ;; domain bound it takes the flat path: the type's generated
       ;; flat sister is constructed from the slab-field values
       ;; instead (John, Nov 14 2026 — instantiating an eve type
       ;; outside a transaction produces its flat variant; the
       ;; no-domain throw survives only for direct current-sio
       ;; callers with no flat form). The arity with a leading sio
       ;; allocates in that one.
       (~'defn ~ctor-name
               ([~@ctor-args]
                (if eve.deftype-proto.data/*domain-sio*
                  (~ctor-name eve.deftype-proto.data/*domain-sio* ~@ctor-args)
                  (eve.flat.sister/construct-flat
                   ~type-key
                   [~@(map (fn [f] (symbol (:name f))) slab-fields)])))
               ([~'sio__ ~@ctor-args]
                (let [~'slab-off__ (eve.slab-io/alloc! ~'sio__ ~total-size)]
            ;; Header: type-id byte, name hash
                  (eve.slab-io/write-u8! ~'sio__ ~'slab-off__ 0 ~type-id)
                  ~@(map-indexed (fn [i b] `(eve.slab-io/write-u8! ~'sio__ ~'slab-off__ ~(inc i) ~b))
                                 name-hash-bytes)
            ;; Write initial field values
                  ~@field-writes
            ;; Return instance
                  ~(build-form (concat inst-vals ['slab-off__ 'sio__])))))

       ;; Register JVM header constructor for atom deref (jvm-read-root-value)
       ;; and nested deserialization (eve-bytes->value). Registered under the
       ;; type-id for both the pointer-tag and header-type-id lookups; the
       ;; name hash picks it among types that share the type-id. A named
       ;; fn: one a top-level form makes is interpreted on clojerl.
       (~'defn ~(symbol (str (name type-name) "__from-header")) [sio# header-off#]
               (~load-name sio# header-off#))
       (eve.deftype-proto.serialize/register-deftype-constructor!
        ~type-id ~name-hash ~(symbol (str (name type-name) "__from-header")))

       ;; Offset accessor
       (~'defn ~(symbol (str (name type-name) "-offset")) [~(with-meta 'inst {:tag type-name})]
               (eve.host/field ~'inst ~'slab-off__))

       ;; Type-id constant
       (~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)

       ;; Nil sentinel
       (~'def ~(symbol (str (name type-name) "-nil")) -1)

       ~type-name)))

;;-----------------------------------------------------------------------------
;; the generated flat sister (lane H5)
;;-----------------------------------------------------------------------------

;; a sister method body's transform (its field reads from the source, its
;; field writes a throw) is the shared vocabulary's: eve.deftype-proto.vocab
;; flat-method-body, which every host's sister generator calls

(defn flat-type
  "An eve-deftype's generated flat sister on a host whose types reach
   eve memory through ISlabIO (eve.deftype.macros/eve-deftype* gives
   ctx, the host's adapter hooks) — the second kind the same expansion
   generates (FORMAT-V1 §6, lane H5).

   A host deftype like the persistent kind's, with the same shape: the
   cached fields as its own fields (read once, when the factory builds
   it from a field source — as Type__from-slab reads them from the
   block), the host-local fields nil, and [src__ sister-hash__] where
   the persistent kind has [slab-off__ sio__]. The user's protocol
   bodies are expanded against it, their live fields read from the
   source. Its factory, Type__sister, is (fn [field-source] → sister):
   the packed reader calls it for the type's records and batch rows."
  [{:keys [type-name inst-fields live-fields field-map parsed-protos type-key
           provides? flat-name sister-name]}
   {:keys [normalize define build]}]
  (let [inst-syms (mapv #(v/instance-field-sym % {:unsynchronized-mutable true}) inst-fields)
        {:keys [impls]} (normalize parsed-protos)

        ;; The shared normalization synthesizes an Object group
        ;; for every type: a toString of (pr-str this) when the
        ;; type has a print form. On the persistent kind that
        ;; toString dispatches through a registered print
        ;; method; no print method is registered for a sister,
        ;; so there it recurses without bound — and its mere
        ;; presence suppressed this kind's whole Object
        ;; boilerplate (the gate below was protocol-level),
        ;; leaving sisters with identity equals and no
        ;; hashCode. Drop the synthesized toString for this
        ;; kind (a user-written Object method, any other body,
        ;; stays), and drop the group if it empties.
        impls (mapv (fn [{:keys [protocol] :as group}]
                      (if (contains? #{'Object 'java.lang.Object} protocol)
                        (update group :methods
                                (fn [ms] (vec (remove (fn [m]
                                                        (and (= 'toString (:name m))
                                                             (= '[(pr-str this)] (:body m))))
                                                      ms))))
                        group))
                    impls)
        impls (vec (remove (fn [{:keys [protocol methods]}]
                             (and (contains? #{'Object 'java.lang.Object} protocol)
                                  (empty? methods)))
                           impls))
        obj-have (into #{}
                       (comp (filter (fn [g] (contains? #{'Object 'java.lang.Object}
                                                        (:protocol g))))
                             (mapcat :methods)
                             (map :name))
                       impls)

        transformed-protos
        (mapcat (fn [{:keys [protocol methods]}]
                  (cons protocol
                        (map (fn [{:keys [name args body]}]
                               (let [tbody (v/flat-method-body
                                            body live-fields field-map)]
                                 (list name args (cons 'do tbody))))
                             methods)))
                impls)

        field-map-expr (list 'eve.flat.source/-field-map 'src__)
        boilerplate
        (concat
         ;; ISabpType — the sister carries the type's key, as the
         ;; persistent kind does
         (when-not (provides? impls 'eve.deftype-proto.data/ISabpType 'd/ISabpType)
           ['eve.deftype-proto.data/ISabpType
            (list 'eve.deftype-proto.data/-sabp-type-key ['_] type-key)])

         ;; ISister — what the packed machinery knows a sister by
         ['eve.flat.source/ISister
          (list 'eve.flat.source/-sister-source ['_] 'src__)
          (list 'eve.flat.source/-sister-type-key ['_] type-key)]

         ;; IFlatHandle — a sister is a flat handle: it stands on
         ;; the chunk's bytes, and re-packs as its type's typed rep
         ['eve.flat.chunk/IFlatHandle
          (list 'eve.flat.chunk/-rep ['this]
                (list 'eve.flat.sister/instance->typed-rep 'this))
          (list 'eve.flat.chunk/-chunk-bytes ['_]
                (list 'eve.flat.source/-source-bytes 'src__))]

         ;; IHashEq — by type key and fields (the flat side of fork
         ;; F2's (a): a flat copy is = its twin; the slab kind's
         ;; identity comparison is untouched)
         (when-not (provides? impls 'clojure.lang.IHashEq 'IHash)
           ['clojure.lang.IHashEq
            (list 'hasheq ['this]
                  (list 'or 'sister-hash__
                        (list 'set! 'sister-hash__
                              (list 'int (list 'clojure.core/hash
                                               (list 'vector type-key field-map-expr))))))])

         ;; Object — per method, not per protocol: the impls
         ;; may still carry user-written Object methods, and
         ;; each boilerplate method is emitted only when the
         ;; impls do not already provide that method.
         (when (not-every? obj-have ['equals 'hashCode 'toString])
           (into ['java.lang.Object]
                 (concat
                  (when-not (contains? obj-have 'equals)
                    [(list 'equals ['this 'other]
                           (list 'and
                                 (list 'instance? flat-name 'other)
                                 (list '= field-map-expr
                                       (list 'eve.flat.source/-field-map
                                             (list 'eve.flat.source/-sister-source
                                                   (with-meta 'other {:tag flat-name}))))))])
                  (when-not (contains? obj-have 'hashCode)
                    [(list 'hashCode ['this] (list '.hasheq 'this))])
                  (when-not (contains? obj-have 'toString)
                    [(list 'toString ['this]
                           (list 'str "#eve.flat/" (name type-name) " "
                                 (list 'pr-str field-map-expr)))])))))

        all-syms (into (vec inst-syms)
                       ['src__ (with-meta 'sister-hash__ {:unsynchronized-mutable true})])
        build-form (fn [args] (build flat-name all-syms sister-name args))
        body (concat boilerplate transformed-protos)]

    `(do
       ;; methods may build sisters: the factory, declared first
       (~'declare ~sister-name)
       ;; the host's deftype: its cached and host-local fields, [src__
       ;; sister-hash__], the user's protocols expanded against it
       ~@(define flat-name all-syms (proto/parse-protocol-impls body) body nil)

       ;; the factory the packed reader calls: the cached fields read
       ;; from the source once, the host-local fields nil — as
       ;; Type__from-slab builds an instance from a block. A field
       ;; carrying a :derive fn (a proto ^:cached field's derivation,
       ;; plan §3(b)(i)) is rebuilt by that fn from the source —
       ;; cached values are not packed, so the source cannot answer
       ;; them by keyword
       (~'defn ~sister-name [~'src__]
               ~(build-form (concat (map (fn [f]
                                           (cond (:derive f)
                                                 (list (:derive f) 'src__)
                                                 (v/cached? f)
                                                 (list 'eve.flat.source/-field
                                                       'src__ (keyword (:name f)))
                                                 :else nil))
                                         inst-fields)
                                    ['src__ nil])))

       ~flat-name)))

;;-----------------------------------------------------------------------------
;; deftype (a plain type) and eve-extend-type
;;-----------------------------------------------------------------------------

(defn eve-renames
  "An eve-deftype's fields and body with the field names the JVM reserves
   renamed (__hash): [fields body]."
  [fields body]
  (let [renames (select-keys v/jvm-field-renames fields)]
    [(mapv (fn [f] (with-meta (get renames f f) (meta f))) fields)
     (v/rename-syms body renames)]))

(defn plain-renames
  "fields and parsed impls of a plain type (eve.deftype/deftype) with the
   field names the JVM reserves renamed (__hash), extra renamed in the
   methods' arguments and bodies, and ClojureScript's ^:mutable as
   ^:unsynchronized-mutable: [fields parsed]."
  [fields parsed extra]
  (let [renames (select-keys v/jvm-field-renames fields)
        extra (select-keys extra fields)
        fields (mapv (fn [f] (v/host-field (with-meta (get (merge renames extra) f f) (meta f))))
                     fields)
        parsed (v/rename-syms parsed renames)
        parsed (if (seq extra)
                 (map (fn [impl]
                        (update impl :methods
                                (fn [ms] (mapv #(-> %
                                                    (update :args v/rename-syms extra)
                                                    (update :body v/rename-syms extra))
                                               ms))))
                      parsed)
                 parsed)]
    [fields parsed]))

(defn extend-eve-type
  "eve-extend-type on a host whose types reach eve memory through ISlabIO."
  [{:keys [type-name fields field-map parsed-protos]}]
  (let [transformed-protos
        (mapcat (fn [{:keys [protocol methods]}]
                  (cons protocol
                        (map (fn [{:keys [name args body]}]
                               (let [this-sym (first args)
                                     tbody (proto/jvm-transform-extend-method-body
                                            body fields field-map
                                            this-sym type-name)]
                                 (list name args (cons 'do tbody))))
                             methods)))
                parsed-protos)]
    `(~'extend-type ~type-name ~@transformed-protos)))

;;-----------------------------------------------------------------------------
;; eve/deftype (eve.deftype-proto.macros)
;;-----------------------------------------------------------------------------

(defn- clj-boilerplate
  "Generate JVM boilerplate: IHashEq and Object when object? (a host's
   :object-boilerplate? hook — bb's sci rejects IHashEq on a non-map type and
   Object equals/hashCode everywhere; map-mode types supply their own
   hasheq), and the type key."
  [type-name parsed-protos type-key object?]
  (concat
   (when-not (or (not object?) (v/user-provides-protocol? parsed-protos 'clojure.lang.IHashEq))
     ['clojure.lang.IHashEq
      (list 'hasheq ['this] (list 'System/identityHashCode 'this))])
   (when-not (or (not object?) (v/user-provides-object? parsed-protos))
     ['Object
      (list 'hashCode ['this] (list '.hasheq 'this))
      (list 'equals ['this 'other]
            (if (v/has-equiv-impl? parsed-protos)
              (list 'cond
                    (list 'identical? 'this 'other) true
                    :else (list '.equiv 'this 'other))
              (list 'and (list 'instance? type-name 'other)
                    (list '= (list '.hasheq 'this) (list '.hasheq 'other)))))
      (list 'toString ['this] (str "#eve/" (name type-name)))])
   ;; ISabpType — every Eve type carries its qualified name
   ['eve.deftype-proto.data/ISabpType
    (list '-sabp-type-key ['_] type-key)]))

(defn slab-type
  "An eve/deftype type on a host whose types reach eve memory through
   ISlabIO. Fields: [sio__ ^long offset__]: sio__ is the ISlabIO context,
   offset__ the slab header offset."
  [{:keys [type-name fields parsed-protos type-id emit-type-id-def? type-key]}
   {:keys [slab-prepare object-boilerplate? slab-define]}]
  (let [field-map (into {} (map (fn [f] [(:name f) f]) fields))
        parsed-protos (slab-prepare parsed-protos type-name)
        ;; an IPrintWithWriter body the host's prepare left as written is the
        ;; type's print-method (it names no field: it runs outside the type)
        print-impl (some #(when (= 'IPrintWithWriter (v/strip-cljs-core (:protocol %))) %) parsed-protos)
        print-fn (when print-impl
                   (let [{:keys [args body]} (first (:methods print-impl))]
                     (list* 'fn args body)))
        parsed-protos (remove #(identical? % print-impl) parsed-protos)
        translated (v/clj-emit-protocols parsed-protos fields field-map)
        boilerplate (clj-boilerplate type-name parsed-protos type-key object-boilerplate?)
        cached-fields (mapv (fn [f]
                              (let [sym (symbol (:name f))
                                    ;; Apply type hints for JVM performance
                                    sym (case (:type-hint f)
                                          (:int32 :uint32 :int16 :uint16 :int8 :uint8)
                                          (with-meta sym {:tag 'long})
                                          sym)]
                                (if (= :mutable (:mutability f))
                                  (with-meta sym (assoc (meta sym) :unsynchronized-mutable true))
                                  sym)))
                            (filter :cached? fields))]
    `(do
       ~@(slab-define type-name (into [(quote sio__) (with-meta 'offset__ {:tag 'long})] cached-fields)
                      (concat boilerplate translated))

       ~@(when print-fn [(print-method-form type-name print-fn)])

       ~@(when emit-type-id-def?
           [`(~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)])

       (~'def ~(symbol (str (name type-name) "-type-key")) ~type-key)

       ~type-name)))
