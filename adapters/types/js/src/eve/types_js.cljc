(ns eve.types-js
  "eve's types as ClojureScript types — the realization every ClojureScript
   host shares (shadow-cljs's compiler, nbb; each one's eve.types passes its
   twist): an instance holds its cached fields and its block's offset in the
   module slab (offset__), its fields read through the slab's DataView."
  (:refer-clojure :exclude [aset])
  (:require [eve.host :refer [aset]]
            [eve.deftype-proto :as proto]
            [eve.deftype-proto.vocab :as v]))

;;-----------------------------------------------------------------------------
;; Code Generation Helpers (Slab Resolve Pattern)
;;-----------------------------------------------------------------------------
;;
;; In the slab allocator, field access works via:
;;   1. resolve-dv! resolves a slab-qualified offset → sets resolved-dv + resolved-base
;;   2. Field reads use (.getXxx resolved-dv (+ resolved-base field-offset) ...)
;;   3. For volatile fields, we get the i32-view for the slab class
;;
;; The method body is wrapped with:
;;   (let [slab-base__ (eve.process-slab/resolve-dv! offset__)
;;         slab-dv__   eve.process-slab/resolved-dv
;;         ... field bindings ...]
;;     body)

(defn- ks-kind
  "The field kind eve.process-slab's keystream accessors take."
  [type-class type-hint]
  (if (= :primitive type-class) type-hint :int32))

(declare raw-read-fn-for-type raw-write-fn-for-type)

(defn- read-fn-for-type
  "Return the read expression for a field type: the slab's DataView, or under
   the open domain's keystream (eve.process-slab/domain-ks, an encrypted
   domain) its decrypting reader. Uses slab-dv__, slab-base__ and slab-off__
   (the block's slab offset) bound at method entry."
  [{:keys [type-hint type-class mutability] :as spec} sab-expr offset-expr field-offset]
  (list 'if '(nil? eve.process-slab/domain-ks)
        (raw-read-fn-for-type spec sab-expr offset-expr field-offset)
        (if (= :volatile-mutable mutability)
          (list 'eve.process-slab/ks-atomic-load 'slab-off__ field-offset)
          (list 'eve.process-slab/ks-read (ks-kind type-class type-hint) 'slab-off__ field-offset))))

(defn- write-fn-for-type
  "Return the write expression for a field type (read-fn-for-type's two ways).
   Uses slab-dv__, slab-base__ and slab-off__ bound at method/constructor entry."
  [{:keys [type-hint type-class mutability] :as spec} sab-expr offset-expr field-offset val-expr]
  (list 'if '(nil? eve.process-slab/domain-ks)
        (raw-write-fn-for-type spec sab-expr offset-expr field-offset val-expr)
        (if (= :volatile-mutable mutability)
          (list 'eve.process-slab/ks-atomic-store! 'slab-off__ field-offset val-expr)
          (list 'eve.process-slab/ks-write! (ks-kind type-class type-hint) 'slab-off__ field-offset val-expr))))

(defn- raw-read-fn-for-type
  "Return the read expression for a field type.
   Uses slab-dv__ and slab-base__ bound at method entry."
  [{:keys [type-hint type-class mutability]} _sab-expr offset-expr field-offset]
  (let [volatile? (= :volatile-mutable mutability)
        ;; In slab mode, offset-expr is slab-base__ (the resolved byte offset)
        abs-offset (list '+ offset-expr field-offset)]
    (if volatile?
      ;; Volatile fields use Atomics on the slab's i32-view
      (list 'js/Atomics.load
            'slab-i32__
            (list 'unsigned-bit-shift-right abs-offset 2))
      ;; Non-volatile: direct DataView read from slab-dv__
      (case type-class
        :primitive
        (case type-hint
          :int8    (list '.getInt8 'slab-dv__ abs-offset)
          :uint8   (list '.getUint8 'slab-dv__ abs-offset)
          :int16   (list '.getInt16 'slab-dv__ abs-offset true)
          :uint16  (list '.getUint16 'slab-dv__ abs-offset true)
          :int32   (list '.getInt32 'slab-dv__ abs-offset true)
          :uint32  (list '.getUint32 'slab-dv__ abs-offset true)
          :float32 (list '.getFloat32 'slab-dv__ abs-offset true)
          :float64 (list '.getFloat64 'slab-dv__ abs-offset true))
        :eve-type
        (list '.getInt32 'slab-dv__ abs-offset true)
        ;; Default to int32
        (list '.getInt32 'slab-dv__ abs-offset true)))))

(defn- raw-write-fn-for-type
  "Return the write expression for a field type.
   Uses slab-dv__ and slab-base__ bound at method/constructor entry."
  [{:keys [type-hint type-class mutability]} _sab-expr offset-expr field-offset val-expr]
  (let [volatile? (= :volatile-mutable mutability)
        abs-offset (list '+ offset-expr field-offset)]
    (if volatile?
      ;; Volatile writes use Atomics on the slab's i32-view
      (list 'js/Atomics.store
            'slab-i32__
            (list 'unsigned-bit-shift-right abs-offset 2)
            val-expr)
      ;; Non-volatile: direct DataView write
      (case type-class
        :primitive
        (case type-hint
          :int8    (list '.setInt8 'slab-dv__ abs-offset val-expr)
          :uint8   (list '.setUint8 'slab-dv__ abs-offset val-expr)
          :int16   (list '.setInt16 'slab-dv__ abs-offset val-expr true)
          :uint16  (list '.setUint16 'slab-dv__ abs-offset val-expr true)
          :int32   (list '.setInt32 'slab-dv__ abs-offset val-expr true)
          :uint32  (list '.setUint32 'slab-dv__ abs-offset val-expr true)
          :float32 (list '.setFloat32 'slab-dv__ abs-offset val-expr true)
          :float64 (list '.setFloat64 'slab-dv__ abs-offset val-expr true))
        :eve-type
        (list '.setInt32 'slab-dv__ abs-offset val-expr true)
        ;; Default to int32
        (list '.setInt32 'slab-dv__ abs-offset val-expr true)))))


;;-----------------------------------------------------------------------------
;; Field reads and writes through the module slab's DataView
;;-----------------------------------------------------------------------------

(defn- cljs-emit-field-read
  "Emit a field read expression for CLJS. Uses slab-dv__/slab-base__ for
   primitive/eve-type fields, slab-runtime for serialized fields.
   For eve-type fields, wraps the raw offset in a constructor call."
  [field-spec offset-expr]
  (case (:type-class field-spec)
    :primitive
    ;; read-fn-for-type above (DataView reads from slab-dv__/slab-base__)
    (read-fn-for-type field-spec nil 'slab-base__ (:offset field-spec))

    :eve-type
    ;; Read raw offset, then wrap in constructor (nil when -1 sentinel)
    (let [raw-read (read-fn-for-type field-spec nil 'slab-base__ (:offset field-spec))
          type-ctor (symbol (str (name (:type-hint field-spec)) "__from-slab"))]
      (list 'let ['off__eve raw-read]
            (list 'when (list 'not= -1 'off__eve)
                  (list type-ctor 'off__eve))))

    :serialized
    ;; Serialized fields use the runtime helper (resolves separate slab block)
    (list 'eve.deftype.slab-runtime/slab-read-serialized
          '(eve.process-slab/cljs-sio) offset-expr (:offset field-spec))))

(defn- cljs-emit-field-write
  "Emit a field write expression for CLJS constructor/set! context."
  [field-spec offset-expr val-expr]
  (case (:type-class field-spec)
    :primitive
    ;; write-fn-for-type above
    (write-fn-for-type
     (assoc field-spec :mutability
            (if (= :volatile-mutable (:mutability field-spec))
              :volatile-mutable :mutable))
     nil 'slab-base__ (:offset field-spec) val-expr)

    :eve-type
    ;; Extract offset from instance (or -1 for nil) then write
    (let [offset-val (list 'if (list 'nil? val-expr)
                           -1
                           (list '.-offset__ val-expr))]
      (write-fn-for-type
       (assoc field-spec :mutability
              (if (= :volatile-mutable (:mutability field-spec))
                :volatile-mutable :mutable))
       nil 'slab-base__ (:offset field-spec) offset-val))

    :serialized
    ;; Serialized fields use the runtime helper
    ;; through the running swap!'s sio, so a lost CAS frees the block
    (list 'eve.deftype.slab-runtime/slab-write-serialized!
          '(eve.deftype-proto.alloc/current-sio) offset-expr (:offset field-spec) val-expr)))

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
          val-expr (cljs-rewrite-set!-forms (nth form 2) field-map offset-expr)
          write (cljs-emit-field-write field-spec offset-expr val-expr)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (if-let [kind (v/ref-kind field-spec)]
        ;; a reference field written in place: the store rule (eve.tally/store!), as on every host
        (list 'let ['sio__ref '(eve.deftype-proto.alloc/current-sio)
                    'old__ref (list 'eve.slab-io/read-i32 'sio__ref offset-expr (:offset field-spec))
                    'ret__ref write]
              (list 'eve.deftype.slab-runtime/slab-stored! 'sio__ref offset-expr (:offset field-spec) 'old__ref kind)
              'ret__ref)
        write))

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
            abs-offset (list '+ 'slab-base__ (:offset field-spec))
            cas (list '==
                      expected-expr
                      (list 'if '(nil? eve.process-slab/domain-ks)
                            (list 'js/Atomics.compareExchange
                                  'slab-i32__
                                  (list 'unsigned-bit-shift-right abs-offset 2)
                                  expected-expr new-expr)
                            (list 'eve.process-slab/ks-cas-i32! 'slab-off__ (:offset field-spec) expected-expr new-expr)))]
        (if-let [kind (v/ref-kind field-spec)]
          ;; a reference field's cas!: when it lands, the store rule (eve.tally/store!) — expected out, new in
          (list 'let ['exp__ref expected-expr 'new__ref new-expr
                      'ok__ref (list '==
                                     'exp__ref
                                     (list 'if '(nil? eve.process-slab/domain-ks)
                                           (list 'js/Atomics.compareExchange
                                                 'slab-i32__
                                                 (list 'unsigned-bit-shift-right abs-offset 2)
                                                 'exp__ref 'new__ref)
                                           (list 'eve.process-slab/ks-cas-i32! 'slab-off__ (:offset field-spec)
                                                 'exp__ref 'new__ref)))]
                (list 'when 'ok__ref
                      (list 'eve.deftype.slab-runtime/ref-stored! '(eve.deftype-proto.alloc/current-sio) offset-expr
                            'exp__ref 'new__ref kind))
                'ok__ref)
          cas)))

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
              ['slab-off__ offset-expr
               'slab-base__ (list 'eve.process-slab/resolve-dv! 'slab-off__)
               'slab-dv__ 'eve.process-slab/resolved-dv]
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
;; eve-deftype, deftype, eve-extend-type
;;-----------------------------------------------------------------------------

(defn eve-type
  "An eve-deftype as a ClojureScript type over the module slab
   (eve.deftype.macros/eve-deftype* gives ctx). twist: the host's
   {:translate (fn [impls] impls) :declare-type? bool}."
  [{:keys [type-name inst-fields live-fields slab-fields field-map parsed-protos type-key
           provides? has-volatile? serialized-fields ctor-name ctor-args new-name load-name
           inst-vals total-size type-id name-hash name-hash-bytes sab-tag-kw]}
   {:keys [translate declare-type?]}]
  (let [off-sym 'offset__
        ;; one body for every host: JVM names translate to CLJS here
        parsed-protos (translate (v/translate-protos (v/cljs-own-calls parsed-protos) :cljs))
        inst-syms (mapv #(v/instance-field-sym % {:mutable true}) inst-fields)

        ;; Transform user protocol method bodies: the live fields are read
        ;; from eve memory; cached and host-local ones are the instance's
        transformed-protos
        (mapcat (fn [{:keys [protocol methods]}]
                  (cons protocol
                        (map (fn [{:keys [name args body]}]
                               (let [this-sym (first args)
                                     tbody (if (empty? live-fields)
                                             body
                                             (cljs-transform-method-body
                                              body live-fields field-map
                                              (list '.-offset__ this-sym)
                                              has-volatile?))]
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

         ;; IEveRoot — the header offset, as the JVM/bb emission
         ;; (eve.deftype-proto.sio) gives it, unless the type provides its own
         (when-not (provides? parsed-protos 'eve.deftype-proto.data/IEveRoot 'd/IEveRoot)
           ['eve.deftype-proto.data/IEveRoot
            (list 'eve.deftype-proto.data/-root-header-off ['_] off-sym)])

         ;; IEveSio — the domain the instance reads through: on CLJS every
         ;; eve-deftype lives in the module slab (the open mmap domain once
         ;; one is open), whose slab I/O is eve.process-slab/cljs-sio — the
         ;; same token the domain's atoms hold, so eve.atom's foreign-domain
         ;; check sees this domain (the JVM/bb emission answers its sio__)
         (when-not (provides? parsed-protos 'eve.deftype-proto.data/IEveSio 'd/IEveSio)
           ['eve.deftype-proto.data/IEveSio
            (list 'eve.deftype-proto.data/-eve-sio ['_] '(eve.process-slab/cljs-sio))])

         ;; ISabStorable — for atom serialization/disposal (skipped if user provides)
         (when-not (provides? parsed-protos 'eve.deftype-proto.data/ISabStorable)
           ['eve.deftype-proto.data/ISabStorable
            (list 'eve.deftype-proto.data/-sab-tag ['_] sab-tag-kw)
            (list 'eve.deftype-proto.data/-sab-encode ['_ '_env]
                  (list 'eve.codec/encode-sab-pointer
                        type-id off-sym))
            (list 'eve.deftype-proto.data/-sab-dispose ['_ '_env]
                  (cons 'do
                        (concat
                         ;; Free serialized field data blocks
                         (map (fn [f]
                                (list 'eve.deftype.slab-runtime/slab-free-serialized!
                                      '(eve.process-slab/cljs-sio) off-sym (:offset f)))
                              serialized-fields)
                         ;; Free the instance block itself
                         [(list 'eve.process-slab/free! off-sym)])))])

         ;; IHash
         (when-not (provides? parsed-protos 'IHash)
           ['IHash (list '-hash ['_] (list 'hash off-sym))])

         ;; IEquiv
         (when-not (provides? parsed-protos 'IEquiv)
           ['IEquiv
            (list '-equiv ['_ 'other]
                  (list 'and
                        (list 'instance? type-name 'other)
                        (list '== off-sym (list '.-offset__ 'other))))])

         ;; IPrintWithWriter — the type's toString when it has one
         (when-not (provides? parsed-protos 'IPrintWithWriter)
           ['IPrintWithWriter
            (if (some (fn [{:keys [protocol methods]}]
                        (and (= 'Object protocol) (some #(= 'toString (:name %)) methods)))
                      parsed-protos)
              (list '-pr-writer ['this 'writer '_opts]
                    (list '-write 'writer (list 'str 'this)))
              (list '-pr-writer ['_ 'writer '_opts]
                    (list '-write 'writer (str "#eve/" (name type-name) " "))
                    (list '-write 'writer (str "{:offset " off-sym "}"))))]))

        ;; Constructor field writes
        off-ctor-sym (gensym "offset_")
        field-writes
        (mapcat (fn [f]
                  (case (:type-class f)
                    :primitive
                    ;; Write primitive via slab DataView
                    [(write-fn-for-type
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
                      [(write-fn-for-type
                        (assoc f :mutability
                               (if (= :volatile-mutable (:mutability f))
                                 :volatile-mutable :mutable))
                        nil 'slab-base__ (:offset f) offset-val)])

                    :serialized
                    ;; Write serialized via slab-runtime
                    [(list 'eve.deftype.slab-runtime/slab-write-serialized!
                           '(eve.deftype-proto.alloc/current-sio) off-ctor-sym (:offset f)
                           (symbol (:name f)))]))
                slab-fields)
        load-off (gensym "offset_")]

    `(do
       ;; methods may build new instances: the constructor, declared first;
       ;; and they may name the type-id (sci, nbb's interpreter, resolves a
       ;; form's symbols when it reads the form)
       (~'declare ~ctor-name ~new-name ~load-name)
       ;; and the type's own name: ClojureScript's deftype resolves it
       ;; before defining it, so a name cljs.core also has (VectorNode,
       ;; RSeq, Range) would build cljs.core's type with ->T
       ~@(when declare-type? [`(~'declare ~type-name)])
       (~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)
       ;; The instance: its cached and host-local fields, and its block's offset
       (~'deftype ~type-name [~@inst-syms ~off-sym]
                  ~@boilerplate
                  ~@transformed-protos)

       ;; (T__new field ... offset) builds an instance: the deftype's own
       ;; factory, kept before ->T becomes the constructor below (sci
       ;; builds a (T. x) through the ->T var, so instances are never made
       ;; with T.)
       (~'def ~new-name ~ctor-name)

       ;; An instance read from eve memory: its cached fields read once
       (~'defn ~load-name [~load-off]
               (let [~'slab-off__ ~load-off
                     ~'slab-base__ (eve.process-slab/resolve-dv! ~load-off)
                     ~'slab-dv__ eve.process-slab/resolved-dv]
                 (~new-name ~@(map (fn [f]
                                     (when (v/cached? f)
                                       (cljs-emit-field-read f load-off)))
                                   inst-fields)
                            ~load-off)))

       ;; Constructor: allocate, write fields. It allocates through the
       ;; running swap!'s sio (which logs the attempt's allocations, freed
       ;; when its CAS loses), else this process's (alloc/current-sio), as
       ;; on the JVM; on CLJS every sio is the module slab, which is the
       ;; open mmap domain once one is open, so the fields are written there.
       ;; A nil sio is the current one (a CLJS instance carries none; its
       ;; d/-eve-sio answers the module slab's cljs-sio).
       (~'defn ~ctor-name
               ([~@ctor-args]
                (~ctor-name (eve.deftype-proto.alloc/current-sio) ~@ctor-args))
               ([~'sio__ ~@ctor-args]
                (let [~off-ctor-sym (eve.deftype-proto.alloc/-sio-alloc!
                                     (or ~'sio__ (eve.deftype-proto.alloc/current-sio))
                                     ~total-size)
                      ~'slab-off__ ~off-ctor-sym
                      ~'slab-base__ (eve.process-slab/resolve-dv! ~off-ctor-sym)
                      ~'slab-dv__ eve.process-slab/resolved-dv
                      ~@(when has-volatile?
                          ['slab-i32__ (list 'js/Int32Array.
                                             (list '.-buffer 'slab-dv__))])]
           ;; The block is as its last owner left it (eve never zeroes
           ;; memory): the header and every field are written here.
           ;; Header: type-id byte, name hash
                  (if (nil? eve.process-slab/domain-ks)
                    (do (.setUint8 ~'slab-dv__ ~'slab-base__ ~type-id)
                        ~@(map-indexed (fn [i b] `(.setUint8 ~'slab-dv__ (+ ~'slab-base__ ~(inc i)) ~b))
                                       name-hash-bytes))
                    (do (eve.process-slab/write-u8! ~off-ctor-sym 0 ~type-id)
                        ~@(map-indexed (fn [i b] `(eve.process-slab/write-u8! ~off-ctor-sym ~(inc i) ~b))
                                       name-hash-bytes)))
           ;; Write field values
                  ~@field-writes
           ;; Return instance
                  (~new-name ~@inst-vals ~off-ctor-sym))))

       ;; Offset accessor
       (~'defn ~(symbol (str (name type-name) "-offset")) [inst#]
               (.-offset__ inst#))

       ;; Nil sentinel
       (~'def ~(symbol (str (name type-name) "-nil")) -1)

       ;; Register SAB fast-tag for atom serialization
       (eve.deftype-proto.serialize/register-sab-fast-tag!
        ~sab-tag-kw ~type-id)

       ;; Register constructor for deserialization
       (eve.deftype-proto.serialize/register-deftype-constructor!
        ~type-id ~name-hash
        (fn [_sio# header-off#]
          (~load-name header-off#)))

       ~type-name)))

(defn- factory-ctor-calls
  "impls with each method body's own-type construction (T. field ...)
   rewritten to the factory call (->T field ...). On SCI (nbb), a
   (T. ...) inside a method body can resolve the type's name to a
   non-constructor \u2014 \"[object Object] is not a constructor\" when a
   seq method builds the next node (F-NBB-SEQWALK; the sorted-set
   Iter's -next) \u2014 where ->T, a plain fn the deftype defines,
   constructs on every host."
  [impls type-name]
  (let [ctor    (symbol (str (name type-name) "."))
        factory (symbol (str "->" (name type-name)))
        rw      (fn rw [form]
                  (cond
                    (and (seq? form) (= 'quote (first form))) form
                    (and (seq? form) (= ctor (first form)))
                    (apply list factory (map rw (rest form)))
                    (seq? form) (with-meta (apply list (map rw form)) (meta form))
                    (vector? form) (with-meta (mapv rw form) (meta form))
                    (map? form) (with-meta (into {} (map (fn [[k v]] [(rw k) (rw v)])) form) (meta form))
                    (set? form) (into #{} (map rw form))
                    :else form))]
    (mapv (fn [impl]
            (update impl :methods
                    (fn [ms] (mapv (fn [m] (update m :body #(map rw %))) ms))))
          impls)))

(defn plain-type
  "A plain type (eve.deftype/deftype) as cljs.core's deftype: JVM names
   translated (twist's :translate after). A twist's :factory-ctor-calls
   rewrites own-type constructions in method bodies to ->T factory
   calls (see factory-ctor-calls)."
  [type-name fields parsed {:keys [translate] :as twist}]
  (let [impls (translate (v/translate-protos (v/cljs-own-calls parsed) :cljs))
        impls (if (:factory-ctor-calls twist)
                (factory-ctor-calls impls type-name)
                impls)
        ;; the JVM's mutable field marks are ClojureScript's ^:mutable
        fields (mapv (fn [f]
                       (let [m (meta f)]
                         (if (or (:volatile-mutable m) (:unsynchronized-mutable m))
                           (vary-meta f #(-> % (dissoc :volatile-mutable :unsynchronized-mutable)
                                             (assoc :mutable true)))
                           f)))
                     fields)]
    `(cljs.core/deftype ~type-name ~fields ~@(v/impls->forms impls))))

(defn extend-eve-type
  "eve-extend-type on ClojureScript."
  [{:keys [type-name fields field-map parsed-protos has-volatile?]}]
  (let [parsed-protos (v/translate-protos parsed-protos :cljs)
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
                parsed-protos)]
    `(~'extend-type ~type-name ~@transformed-protos)))

;;-----------------------------------------------------------------------------
;; A ClojureScript compiler (its macros run in its own Clojure)
;;-----------------------------------------------------------------------------

;; cljs.core has every protocol eve's types name; a type's name is declared
;; before its deftype (ClojureScript's deftype resolves it first, so a name
;; cljs.core also has — VectorNode, RSeq, Range — would build cljs.core's
;; type with ->T)
(def compiler-twist {:translate identity :declare-type? true})

(defn compiler-existing-def
  "{:value v} when the namespace a ClojureScript compiler is compiling already
   defines sym (the analyzer's defs: Clojure's resolve cannot see them), else
   nil."
  [env sym]
  (when-let [compiler-env (some-> (find-ns 'cljs.env) (ns-resolve '*compiler*) deref)]
    (when-let [d (get-in @compiler-env [:cljs.analyzer/namespaces (-> env :ns :name) :defs sym])]
      {:value (get-in d [:init :val])})))

;;-----------------------------------------------------------------------------
;; eve/deftype (eve.deftype-proto.macros)
;;-----------------------------------------------------------------------------

(defn- cljs-emit-protocols
  "Emit CLJS protocol implementations from CLJ-style parsed protos."
  [parsed-protos fields field-map]
  ;; a JVM-named java.lang.Object's toString stays on Object (equals and
  ;; hashCode are IEquiv's and IHash's: the type's own or the boilerplate)
  (let [translated (concat (mapcat v/cljs-translate-protocol parsed-protos)
                           (filter #(= 'Object (:protocol %)) (v/cljs-object-impls parsed-protos)))
        by-proto (group-by :protocol translated)]
    (mapcat (fn [[proto impls]]
              (let [all-methods (mapcat :methods impls)
                    marker? (some :marker? impls)]
                (if marker?
                  [proto]
                  (cons proto
                        (map (fn [{:keys [name args body]}]
                               (let [tbody (v/transform-method-body body fields field-map)]
                                 (list name args (cons 'do tbody))))
                             all-methods)))))
            by-proto)))

(defn- cljs-boilerplate
  "Generate default CLJS boilerplate protocols."
  [type-name parsed-protos type-key]
  (concat
   (when-not (v/user-provides-protocol? parsed-protos 'clojure.lang.IHashEq)
     ['IHash (list '-hash ['_] (list 'hash (list '.-offset__ '_)))])
   (when-not (v/user-provides-protocol? parsed-protos 'clojure.lang.IPersistentCollection)
     ['IEquiv
      (list '-equiv ['_ 'other]
            (list 'and
                  (list 'instance? type-name 'other)
                  (list '== (list '.-offset__ '_) (list '.-offset__ 'other))))])
   (when-not (or (v/user-provides-protocol? parsed-protos 'java.lang.Object)
                 (v/user-provides-protocol? parsed-protos 'IPrintWithWriter))
     ['IPrintWithWriter
      (list '-pr-writer ['this 'writer '_opts]
            (list '-write 'writer (str "#eve/" (name type-name) " "))
            (list '-write 'writer (str "{:offset " (list '.-offset__ 'this) "}")))])
   ;; IEveRoot — the header offset, as the JVM/bb emission
   ;; (eve.deftype-proto.sio) gives it, unless the type provides its own
   (when-not (or (v/user-provides-protocol? parsed-protos 'eve.deftype-proto.data/IEveRoot)
                 (v/user-provides-protocol? parsed-protos 'd/IEveRoot))
     ['eve.deftype-proto.data/IEveRoot
      (list '-root-header-off ['_] (list '.-offset__ '_))])
   ;; IEveSio — the instance's own sio__, as the JVM/bb emission answers it
   (when-not (or (v/user-provides-protocol? parsed-protos 'eve.deftype-proto.data/IEveSio)
                 (v/user-provides-protocol? parsed-protos 'd/IEveSio))
     ['eve.deftype-proto.data/IEveSio
      (list '-eve-sio ['_] (list '.-sio__ '_))])
   ;; ISabpType — every Eve type carries its qualified name
   ['eve.deftype-proto.data/ISabpType
    (list '-sabp-type-key ['_] type-key)]))

(defn slab-type
  "An eve/deftype type as a ClojureScript type."
  [{:keys [type-name fields parsed-protos type-id emit-type-id-def? type-key]}]
  (let [parsed-protos (v/cljs-own-calls parsed-protos)
        field-map (into {} (map (fn [f] [(:name f) f]) fields))
        translated (cljs-emit-protocols parsed-protos fields field-map)
        boilerplate (cljs-boilerplate type-name parsed-protos type-key)
        cached-fields (mapv (fn [f]
                              (let [sym (symbol (:name f))]
                                (if (= :mutable (:mutability f))
                                  (with-meta sym {:mutable true})
                                  sym)))
                            (filter :cached? fields))]
    `(do
       (~'deftype ~type-name [~'sio__ ~'offset__ ~@cached-fields]
                  ~@boilerplate
                  ~@translated)

       ~@(when emit-type-id-def?
           [`(~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)])

       (~'def ~(symbol (str (name type-name) "-type-key")) ~type-key)

       ~type-name)))

;;-----------------------------------------------------------------------------
;; A registered eve collection's printer
;;-----------------------------------------------------------------------------

(defn print-form
  "IPrintWithWriter for a registered eve collection of shape spec
   ({:open :close} for a sequential one, :map for a map), writing it as
   ClojureScript prints its own collections."
  [type-name spec]
  (let [x (gensym "x") w (gensym "w") s (gensym "s") first? (gensym "first")]
    (if (= :map spec)
      (let [k (gensym "k") v (gensym "v")]
        `(~'extend-type ~type-name ~'IPrintWithWriter
          (~'-pr-writer [~x ~w ~'_]
           (~'-write ~w "{")
           (loop [~s (seq ~x) ~first? true]
             (when ~s
               (let [[~k ~v] (first ~s)]
                 (when-not ~first? (~'-write ~w ", "))
                 (~'-write ~w (pr-str ~k))
                 (~'-write ~w " ")
                 (~'-write ~w (pr-str ~v))
                 (recur (next ~s) false))))
           (~'-write ~w "}"))))
      `(~'extend-type ~type-name ~'IPrintWithWriter
        (~'-pr-writer [~x ~w ~'_]
         (~'-write ~w ~(:open spec))
         (loop [~s (seq ~x) ~first? true]
           (when ~s
             (when-not ~first? (~'-write ~w " "))
             (~'-write ~w (pr-str (first ~s)))
             (recur (next ~s) false)))
         (~'-write ~w ~(:close spec)))))))

(defn vector-types
  "ClojureScript's own vector types: its persistent vector and its subvec."
  []
  '[cljs.core/PersistentVector cljs.core/Subvec])
