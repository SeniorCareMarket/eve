(ns eve.deftype
  "eve-deftype: High-level type definition macro built on eve-slab-deftype.

   This macro builds ENTIRELY on top of the slab allocator infrastructure
   from eve.deftype-proto. Types use [offset__] on CLJS and [^long slab-off__ sio__]
   on JVM — the exact same storage pattern as eve-slab-deftype.

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
  (:require [eve.deftype.registry :as reg]
            [eve.deftype-proto :as proto]))

;;-----------------------------------------------------------------------------
;; CLJS Code Generation Helpers
;;-----------------------------------------------------------------------------

(defn- cljs-emit-field-read
  "Emit a field read expression for CLJS. Uses slab-dv__/slab-base__ for
   primitive/eve-type fields, slab-runtime for serialized fields.
   For eve-type fields, wraps the raw offset in a constructor call."
  [field-spec offset-expr]
  (case (:type-class field-spec)
    :primitive
    ;; Delegate to proto's read-fn-for-type (DataView reads from slab-dv__/slab-base__)
    (proto/read-fn-for-type field-spec nil 'slab-base__ (:offset field-spec))

    :eve-type
    ;; Read raw offset, then wrap in constructor (nil when -1 sentinel)
    (let [raw-read (proto/read-fn-for-type field-spec nil 'slab-base__ (:offset field-spec))
          type-ctor (symbol (str (name (:type-hint field-spec)) "."))]
      (list 'let ['off__eve raw-read]
            (list 'when (list 'not= -1 'off__eve)
                  (list type-ctor 'off__eve))))

    :serialized
    ;; Serialized fields use the runtime helper (resolves separate slab block)
    (list 'eve.deftype.slab-runtime/slab-read-serialized
          offset-expr (:offset field-spec))))

(defn- cljs-emit-field-write
  "Emit a field write expression for CLJS constructor/set! context."
  [field-spec offset-expr val-expr]
  (case (:type-class field-spec)
    :primitive
    ;; Delegate to proto's write-fn-for-type
    (proto/write-fn-for-type
     (assoc field-spec :mutability
            (if (= :volatile-mutable (:mutability field-spec))
              :volatile-mutable :mutable))
     nil 'slab-base__ (:offset field-spec) val-expr)

    :eve-type
    ;; Extract offset from instance (or -1 for nil) then write
    (let [offset-val (list 'if (list 'nil? val-expr)
                           -1
                           (list '.-offset__ val-expr))]
      (proto/write-fn-for-type
       (assoc field-spec :mutability
              (if (= :volatile-mutable (:mutability field-spec))
                :volatile-mutable :mutable))
       nil 'slab-base__ (:offset field-spec) offset-val))

    :serialized
    ;; Serialized fields use the runtime helper
    (list 'eve.deftype.slab-runtime/slab-write-serialized!
          offset-expr (:offset field-spec) val-expr)))

(defn- cljs-field-bindings
  "Generate let-binding pairs for all declared fields (CLJS).
   Non-serialized fields use slab-dv__/slab-base__ locals.
   Serialized fields use slab-runtime (re-resolves internally)."
  [fields offset-expr]
  (vec (mapcat (fn [f]
                 [(symbol (:name f))
                  (cljs-emit-field-read f offset-expr)])
               fields)))

(defn- cljs-rewrite-set!-forms
  "Walk a form, rewriting (set! field-sym expr) to slab write operations."
  [form field-map offset-expr]
  (cond
    ;; set! on a field
    (and (seq? form)
         (= 'set! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          val-expr (cljs-rewrite-set!-forms (nth form 2) field-map offset-expr)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (cljs-emit-field-write field-spec offset-expr val-expr))

    ;; cas! on a field (primitive/eve-type only, same as proto)
    (and (seq? form)
         (= 'cas! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))]
      (when (not= :volatile-mutable (:mutability field-spec))
        (throw (ex-info "cas! requires ^:volatile-mutable field" {:field (:name field-spec)})))
      (when (= :serialized (:type-class field-spec))
        (throw (ex-info "cas! not supported on serialized fields" {:field (:name field-spec)})))
      ;; Delegate to proto's cas! rewrite pattern
      (let [expected-expr (cljs-rewrite-set!-forms (nth form 2) field-map offset-expr)
            new-expr (cljs-rewrite-set!-forms (nth form 3) field-map offset-expr)
            abs-offset (list '+ 'slab-base__ (:offset field-spec))]
        (list '==
              expected-expr
              (list 'js/Atomics.compareExchange
                    'slab-i32__
                    (list 'unsigned-bit-shift-right abs-offset 2)
                    expected-expr new-expr))))

    ;; Recurse into collections
    (seq? form)
    (with-meta (apply list (map #(cljs-rewrite-set!-forms % field-map offset-expr) form))
               (meta form))
    (vector? form)
    (with-meta (mapv #(cljs-rewrite-set!-forms % field-map offset-expr) form)
               (meta form))
    (map? form)
    (with-meta (into {} (map (fn [[k v]]
                               [(cljs-rewrite-set!-forms k field-map offset-expr)
                                (cljs-rewrite-set!-forms v field-map offset-expr)])
                             form))
               (meta form))
    :else form))

(defn- cljs-transform-method-body
  "Transform method body with slab resolve + field bindings + set!/cas! rewriting.
   Same resolve-dv! pattern as eve-slab-deftype but extended for serialized fields."
  [body fields field-map offset-expr has-volatile?]
  (let [;; Build resolve bindings: resolve-dv! once, then read cached dv/base
        resolve-bindings
        (vec (concat
              ['slab-base__ (list 'eve.deftype-proto.alloc/resolve-dv! offset-expr)
               'slab-dv__ 'eve.deftype-proto.alloc/resolved-dv]
              (when has-volatile?
                ['slab-i32__ (list 'js/Int32Array.
                                   (list '.-buffer 'slab-dv__))])
              ;; Field read bindings (primitive fields use slab-dv__/slab-base__,
              ;; serialized fields use slab-runtime which re-resolves internally)
              (cljs-field-bindings fields offset-expr)))

        ;; Rewrite set!/cas! in body
        rewritten (map #(cljs-rewrite-set!-forms % field-map offset-expr) body)]
    (list (apply list 'let resolve-bindings (vec rewritten)))))

;;-----------------------------------------------------------------------------
;; JVM Code Generation Helpers
;;-----------------------------------------------------------------------------

(defn- jvm-emit-field-read
  "Emit a JVM field read expression."
  [field-spec slab-off-expr]
  (case (:type-class field-spec)
    (:primitive :eve-type)
    (proto/jvm-read-fn-for-type field-spec slab-off-expr (:offset field-spec))

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
    (:primitive :eve-type)
    (proto/jvm-write-fn-for-type
     (assoc field-spec :mutability
            (if (= :volatile-mutable (:mutability field-spec))
              :volatile-mutable :mutable))
     slab-off-expr (:offset field-spec) val-expr)

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
          val-expr (jvm-rewrite-set!-forms (nth form 2) field-map slab-off-expr)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (jvm-emit-field-write field-spec slab-off-expr val-expr))

    (and (seq? form)
         (= 'cas! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (throw (ex-info "cas! in eve-deftype not yet supported on JVM"
                    {:field (name (second form))}))

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

;;-----------------------------------------------------------------------------
;; Shared Helpers
;;-----------------------------------------------------------------------------

(defn- has-serialized-fields? [fields]
  (some #(= :serialized (:type-class %)) fields))

;;-----------------------------------------------------------------------------
;; eve-deftype macro
;;-----------------------------------------------------------------------------

(defmacro eve-deftype
  "Define a slab-backed type with high-level features.

   Builds entirely on top of eve-slab-deftype's slab allocator. Types use
   the same [offset__] / [^long slab-off__ sio__] storage pattern and the
   same slab allocation API.

   Additional features vs eve-slab-deftype:
   - :serialized fields (arbitrary Clojure values)
   - Auto-generated IDirectSerialize / ISabStorable / ISabpType
   - Constructor requires *parent-atom* binding (atom-aware)

   Example:
     (eve-deftype Counter [^:mutable ^:int32 count label]
       ICounted
       (-count [this] count))"
  [type-name fields & body]
  (let [;; Parse fields using registry (supports :serialized)
        parsed-fields (mapv reg/parse-field fields)
        ;; Compute layout using registry (4-byte header, 4-byte aligned)
        {:keys [fields total-size]} (reg/compute-layout parsed-fields)
        ;; Assign type-id from registry (starts at 64)
        type-id (reg/next-type-id!)
        type-tag (str "eve/" (name type-name))
        type-key (str (name (ns-name *ns*)) "/" (name type-name))
        sab-tag-kw (keyword "eve-deftype" (name type-name))

        ;; Register in compile-time registry
        _ (reg/register-type! (name type-name)
                              {:type-id type-id
                               :type-tag type-tag
                               :total-size total-size
                               :fields fields
                               :type-key type-key})

        ;; Build field-map for rewriting
        field-map (into {} (map (fn [f] [(:name f) f]) fields))
        ;; Parse user protocol impls
        parsed-protos (proto/parse-protocol-impls body)
        has-volatile? (proto/has-volatile-fields? fields)
        has-serialized? (has-serialized-fields? fields)
        serialized-fields (filter #(= :serialized (:type-class %)) fields)
        ctor-name (symbol (str "->" (name type-name)))
        ctor-args (mapv (fn [f] (symbol (:name f))) fields)]

    (if (:ns &env)
      ;; =================================================================
      ;; CLJS PATH — slab allocation (same pattern as eve-slab-deftype)
      ;; =================================================================
      (let [off-sym 'offset__

            ;; Transform user protocol method bodies
            transformed-protos
            (mapcat (fn [{:keys [protocol methods]}]
                      (cons protocol
                            (map (fn [{:keys [name args body]}]
                                   (let [this-sym (first args)
                                         tbody (cljs-transform-method-body
                                                body fields field-map
                                                (list '.-offset__ this-sym)
                                                has-volatile?)]
                                     (list name args (cons 'do tbody))))
                                 methods)))
                    parsed-protos)

            ;; Auto-generated boilerplate protocols
            boilerplate
            (concat
             ;; ISabpType — always generated
             ['eve.deftype-proto.data/ISabpType
              (list 'eve.deftype-proto.data/-sabp-type-key ['_] type-key)]

             ;; IDirectSerialize — for atom storage
             ['eve.deftype-proto.data/IDirectSerialize
              (list 'eve.deftype-proto.data/-direct-serialize ['_] off-sym)]

             ;; ISabStorable — for atom serialization/disposal (skipped if user provides)
             (when-not (proto/user-provides-protocol? parsed-protos 'eve.deftype-proto.data/ISabStorable)
               ['eve.deftype-proto.data/ISabStorable
                (list 'eve.deftype-proto.data/-sab-tag ['_] sab-tag-kw)
                (list 'eve.deftype-proto.data/-sab-encode ['_ '_env]
                      (list 'eve.deftype-proto.serialize/encode-sab-pointer
                            type-id off-sym))
                (list 'eve.deftype-proto.data/-sab-dispose ['_ '_env]
                      (cons 'do
                            (concat
                             ;; Free serialized field data blocks
                             (map (fn [f]
                                    (list 'eve.deftype.slab-runtime/slab-free-serialized!
                                          off-sym (:offset f)))
                                  serialized-fields)
                             ;; Free the instance block itself
                             [(list 'eve.deftype-proto.alloc/free! off-sym)])))])

             ;; IHash
             (when-not (proto/user-provides-protocol? parsed-protos 'IHash)
               ['IHash (list '-hash ['_] (list 'hash off-sym))])

             ;; IEquiv
             (when-not (proto/user-provides-protocol? parsed-protos 'IEquiv)
               ['IEquiv
                (list '-equiv ['_ 'other]
                      (list 'and
                            (list 'instance? type-name 'other)
                            (list '== off-sym (list '.-offset__ 'other))))])

             ;; IPrintWithWriter
             (when-not (proto/user-provides-protocol? parsed-protos 'IPrintWithWriter)
               ['IPrintWithWriter
                (list '-pr-writer ['_ 'writer '_opts]
                      (list '-write 'writer (str "#eve/" (name type-name) " "))
                      (list '-write 'writer (str "{:offset " off-sym "}")))]))

            ;; Constructor field writes
            off-ctor-sym (gensym "offset_")
            field-writes
            (mapcat (fn [f]
                      (case (:type-class f)
                        :primitive
                        ;; Write primitive via slab DataView
                        [(proto/write-fn-for-type
                          (assoc f :mutability
                                 (if (= :volatile-mutable (:mutability f))
                                   :volatile-mutable :mutable))
                          nil 'slab-base__ (:offset f) (symbol (:name f)))]

                        :eve-type
                        ;; Write eve-type reference: extract offset from instance, or -1 for nil
                        (let [fsym (symbol (:name f))
                              offset-val (list 'if (list 'nil? fsym)
                                               -1
                                               (list '.-offset__ fsym))]
                          [(proto/write-fn-for-type
                            (assoc f :mutability
                                   (if (= :volatile-mutable (:mutability f))
                                     :volatile-mutable :mutable))
                            nil 'slab-base__ (:offset f) offset-val)])

                        :serialized
                        ;; Write serialized via slab-runtime
                        [(list 'eve.deftype.slab-runtime/slab-write-serialized!
                               off-ctor-sym (:offset f) (symbol (:name f)))]))
                    fields)]

        `(do
           ;; Type definition — slab-backed, stores [offset__]
           (~'deftype ~type-name [~off-sym]
             ~@boilerplate
             ~@transformed-protos)

           ;; Constructor: allocate from slab, write fields
           (~'defn ~ctor-name [~@ctor-args]
             (let [~off-ctor-sym (eve.deftype-proto.alloc/alloc-offset ~total-size)
                   ~'slab-base__ (eve.deftype-proto.alloc/resolve-dv! ~off-ctor-sym)
                   ~'slab-dv__ eve.deftype-proto.alloc/resolved-dv
                   ~@(when has-volatile?
                       ['slab-i32__ (list 'js/Int32Array.
                                         (list '.-buffer 'slab-dv__))])
                   ~'slab-u8__ (eve.deftype-proto.alloc/resolve-u8! ~off-ctor-sym)]
               ;; Zero-fill
               (dotimes [i# ~total-size]
                 (aset eve.deftype-proto.alloc/resolved-u8
                       (+ ~'slab-u8__ i#) 0))
               ;; Re-resolve after zero-fill
               (eve.deftype-proto.alloc/resolve-dv! ~off-ctor-sym)
               (let [~'slab-dv__ eve.deftype-proto.alloc/resolved-dv
                     ~'slab-base__ eve.deftype-proto.alloc/resolved-base]
                 ;; Write type-id byte
                 (.setUint8 ~'slab-dv__ ~'slab-base__ ~type-id)
                 ;; Write field values
                 ~@field-writes)
               ;; Return instance
               (~(symbol (str (name type-name) ".")) ~off-ctor-sym)))

           ;; Offset accessor
           (~'defn ~(symbol (str (name type-name) "-offset")) [inst#]
             (.-offset__ inst#))

           ;; Type-id constant
           (~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)

           ;; Nil sentinel
           (~'def ~(symbol (str (name type-name) "-nil")) -1)

           ;; Register SAB fast-tag for atom serialization
           (eve.deftype-proto.serialize/register-sab-fast-tag!
            ~sab-tag-kw ~type-id)

           ;; Register constructor for deserialization
           (eve.deftype-proto.serialize/register-sab-type-constructor!
            ~type-id ~type-id
            (fn [_sab# header-off#]
              (~(symbol (str (name type-name) ".")) header-off#)))

           ~type-name))

      ;; =================================================================
      ;; JVM PATH — ISlabIO-based field access (same as eve-slab-deftype JVM)
      ;; =================================================================
      (let [slab-off-field (with-meta 'slab-off__ {:tag 'long})

            transformed-protos
            (mapcat (fn [{:keys [protocol methods]}]
                      (cons protocol
                            (map (fn [{:keys [name args body]}]
                                   (let [tbody (jvm-transform-method-body
                                                body fields field-map)]
                                     (list name args (cons 'do tbody))))
                                 methods)))
                    parsed-protos)

            boilerplate
            (concat
             ;; ISabpType
             ['eve.deftype-proto.data/ISabpType
              (list 'eve.deftype-proto.data/-sabp-type-key ['_] type-key)]

             ;; IHashEq
             (when-not (proto/user-provides-protocol? parsed-protos 'clojure.lang.IHashEq)
               ['clojure.lang.IHashEq
                (list 'hasheq ['_]
                      (list 'clojure.lang.Murmur3/hashLong 'slab-off__))])

             ;; Object
             (when-not (proto/user-provides-protocol? parsed-protos 'java.lang.Object)
               ['java.lang.Object
                (list 'equals ['_ 'other]
                      (list 'and
                            (list 'instance? type-name 'other)
                            (list '== 'slab-off__
                                  (list '.-slab-off__
                                        (with-meta 'other {:tag type-name})))))
                (list 'hashCode ['this] (list '.hasheq 'this))
                (list 'toString ['_]
                      (list 'str (str "#eve/" (name type-name) " {:offset ")
                            'slab-off__ "}"))]))

            field-writes
            (map (fn [f]
                   (jvm-emit-field-write f 'slab-off__ (symbol (:name f))))
                 fields)]

        `(do
           ;; JVM deftype: [^long slab-off__ sio__]
           (~'deftype ~type-name [~slab-off-field ~'sio__]
             ~@boilerplate
             ~@transformed-protos)

           ;; Constructor
           (~'defn ~ctor-name [~@ctor-args]
             (let [~'sio__ eve.deftype-proto.alloc/*jvm-slab-ctx*]
               (~'when-not ~'sio__
                 (~'throw (IllegalStateException.
                            (~'str "Cannot construct " ~(str type-name)
                                   " — *jvm-slab-ctx* not bound"))))
               (let [~'slab-off__ (eve.deftype-proto.alloc/-sio-alloc! ~'sio__ ~total-size)]
                 ;; Write type-id byte
                 (eve.deftype-proto.alloc/-sio-write-u8! ~'sio__ ~'slab-off__ 0 ~type-id)
                 ;; Write initial field values
                 ~@field-writes
                 ;; Return instance
                 (~(symbol (str (name type-name) ".")) ~'slab-off__ ~'sio__))))

           ;; Offset accessor
           (~'defn ~(symbol (str (name type-name) "-offset")) [inst#]
             (~'.-slab-off__ inst#))

           ;; Type-id constant
           (~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)

           ;; Nil sentinel
           (~'def ~(symbol (str (name type-name) "-nil")) -1)

           ~type-name)))))

;;-----------------------------------------------------------------------------
;; eve-extend-type macro
;;-----------------------------------------------------------------------------

(defmacro eve-extend-type
  "Extend protocols to an existing eve-deftype. Field names from the type's
   declaration are available as local bindings in method bodies. set! and cas!
   on declared fields are rewritten to slab operations."
  [type-name & body]
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

    (if (:ns &env)
      ;; CLJS PATH
      (let [transformed-protos
            (mapcat (fn [{:keys [protocol methods]}]
                      (cons protocol
                            (map (fn [{:keys [name args body]}]
                                   (let [this-sym (first args)
                                         tbody (cljs-transform-method-body
                                                body fields field-map
                                                (list '.-offset__ this-sym)
                                                has-volatile?)]
                                     (list name args (cons 'do tbody))))
                                 methods)))
                    parsed-protos)]
        `(~'extend-type ~type-name ~@transformed-protos))

      ;; JVM PATH
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
        `(~'extend-type ~type-name ~@transformed-protos)))))
