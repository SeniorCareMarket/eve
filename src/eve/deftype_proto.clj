(ns eve.deftype-proto
  "eve-slab-deftype: Slab-backed types with fast-path serialization.

   This is the structural layer for the multi-SAB slab allocator that provides:
   - Memory management (allocation from slab allocator)
   - Typed field access via slab resolve pattern
   - Full Atomics API for volatile-mutable fields

   Used to build lock-free persistent data structures like eve-vec, eve-map.

   Key difference from eve-sab-deftype:
   - No single SAB; offsets are slab-qualified (3-bit class + 29-bit block)
   - Field access resolves the correct DataView per-method via resolve-dv!
   - No eve-env / *global-atom-instance* dependency
   - Deftype has only offset__ field (no sab__)

   Field types:
     ^:int8, ^:uint8, ^:int16, ^:uint16     - small integers
     ^:int32, ^:uint32                       - 32-bit integers (Atomics-capable)
     ^:float32, ^:float64                    - floating point
     ^:SomeType                              - reference to another slab-deftype (offset)

   Mutability:
     (default)          - immutable after construction
     ^:mutable          - mutable via set! (single worker)
     ^:volatile-mutable - atomic via set!/cas! (multi-worker safe)")

;;-----------------------------------------------------------------------------
;; Type Registry (compile-time)
;;-----------------------------------------------------------------------------

(defonce ^:private type-registry (atom {}))
(defonce ^:private next-type-id (atom 1))

(defn- register-type! [type-name type-info]
  (swap! type-registry assoc type-name type-info))

(defn- lookup-type [type-name]
  (get @type-registry type-name))

(defn- next-type-id! []
  (let [id @next-type-id]
    (swap! next-type-id inc)
    id))

;;-----------------------------------------------------------------------------
;; Field Parsing
;;-----------------------------------------------------------------------------

(def ^:private primitive-types
  #{:int8 :uint8 :int16 :uint16 :int32 :uint32 :float32 :float64})

(def ^:private type-sizes
  {:int8 1, :uint8 1, :int16 2, :uint16 2,
   :int32 4, :uint32 4, :float32 4, :float64 8,
   :ref 4}) ;; references are int32 offsets

(def ^:private type-alignments
  {:int8 1, :uint8 1, :int16 2, :uint16 2,
   :int32 4, :uint32 4, :float32 4, :float64 8,
   :ref 4})

(defn parse-field
  "Parse a field symbol with metadata into a field spec."
  [field-sym]
  (let [m (meta field-sym)
        field-name (name field-sym)
        ;; Check for primitive type hints
        type-hint (first (filter primitive-types (keys m)))
        ;; Check for eve-type reference (capitalized keyword like :TreeNode)
        eve-type-hint (first (filter #(and (keyword? %)
                                           (Character/isUpperCase (first (name %))))
                                     (keys m)))
        ;; Determine mutability
        mutability (cond
                     (:volatile-mutable m) :volatile-mutable
                     (:mutable m) :mutable
                     :else :immutable)]
    {:name field-name
     :type-hint (or type-hint eve-type-hint :int32) ;; default to int32
     :type-class (cond
                   type-hint :primitive
                   eve-type-hint :eve-type
                   :else :primitive) ;; default to primitive
     :mutability mutability}))

(defn align-offset [offset alignment]
  (let [rem (mod offset alignment)]
    (if (zero? rem) offset (+ offset (- alignment rem)))))

(defn compute-layout
  "Compute field offsets. Returns {:fields [...] :total-size n}."
  [parsed-fields]
  ;; First byte reserved for type-id
  (loop [fields parsed-fields
         offset 1 ;; Start after type-id byte
         result []]
    (if (empty? fields)
      {:fields result
       :total-size (align-offset offset 4)} ;; Final 4-byte alignment
      (let [f (first fields)
            type-class (:type-class f)
            size (if (= :eve-type type-class)
                   4 ;; eve-type refs are int32 offsets
                   (type-sizes (:type-hint f) 4))
            alignment (if (= :eve-type type-class)
                        4
                        (type-alignments (:type-hint f) 4))
            aligned (align-offset offset alignment)]
        (recur (rest fields)
               (+ aligned size)
               (conj result (assoc f :offset aligned :size size)))))))

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
;;   (let [slab-base__ (eve.deftype-proto.alloc/resolve-dv! offset__)
;;         slab-dv__   eve.deftype-proto.alloc/resolved-dv
;;         ... field bindings ...]
;;     body)

(defn read-fn-for-type
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

(defn write-fn-for-type
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

(defn field-bindings
  "Generate let-bindings for reading all fields."
  [fields _sab-expr offset-expr]
  (vec (mapcat (fn [f]
                 [(symbol (:name f))
                  (read-fn-for-type f nil offset-expr (:offset f))])
               fields)))

(defn has-volatile-fields? [fields]
  (some #(= :volatile-mutable (:mutability %)) fields))

(defn rewrite-set!-forms
  "Rewrite (set! field val) to slab write operations."
  [form field-map _sab-expr offset-expr]
  (cond
    ;; set! on a field
    (and (seq? form)
         (= 'set! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          val-expr (rewrite-set!-forms (nth form 2) field-map nil offset-expr)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (write-fn-for-type field-spec nil offset-expr (:offset field-spec) val-expr))

    ;; cas! on a field
    (and (seq? form)
         (= 'cas! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          expected-expr (rewrite-set!-forms (nth form 2) field-map nil offset-expr)
          new-expr (rewrite-set!-forms (nth form 3) field-map nil offset-expr)
          abs-offset (list '+ offset-expr (:offset field-spec))]
      (when (not= :volatile-mutable (:mutability field-spec))
        (throw (ex-info "cas! requires ^:volatile-mutable field" {:field (:name field-spec)})))
      ;; Use slab-i32__ for Atomics.compareExchange
      (list '==
            expected-expr
            (list 'js/Atomics.compareExchange
                  'slab-i32__
                  (list 'unsigned-bit-shift-right abs-offset 2)
                  expected-expr new-expr)))

    ;; Recurse
    (seq? form)
    (with-meta (apply list (map #(rewrite-set!-forms % field-map nil offset-expr) form))
               (meta form))
    (vector? form)
    (with-meta (mapv #(rewrite-set!-forms % field-map nil offset-expr) form)
               (meta form))
    (map? form)
    (with-meta (into {} (map (fn [[k v]]
                               [(rewrite-set!-forms k field-map nil offset-expr)
                                (rewrite-set!-forms v field-map nil offset-expr)])
                             form))
               (meta form))
    :else form))

(defn transform-method-body
  "Transform method body with slab resolve + field bindings + set!/cas! rewriting.
   Wraps body in:
     (let [slab-base__ (eve-alloc/resolve-dv! offset__)
           slab-dv__ eve-alloc/resolved-dv
           slab-i32__ (...) ;; only if volatile fields
           field1 (.getXxx slab-dv__ (+ slab-base__ field1-offset))
           ...]
       body)"
  [body fields field-map _sab-expr offset-expr has-volatile?]
  (let [field-read-bindings (field-bindings fields nil 'slab-base__)
        rewritten (map #(rewrite-set!-forms % field-map nil 'slab-base__) body)
        ;; Build resolve bindings: resolve-dv! once, then read cached dv/base
        resolve-bindings
        (vec (concat
              ;; Resolve the slab-qualified offset → sets resolved-dv + resolved-base
              ['slab-base__ (list 'eve.deftype-proto.alloc/resolve-dv! offset-expr)
               'slab-dv__ 'eve.deftype-proto.alloc/resolved-dv]
              ;; If any volatile fields, also get the i32-view for this slab class
              (when has-volatile?
                ['slab-i32__ (list ':i32
                                   (list 'eve.deftype-proto.wasm/get-slab-instance
                                         (list 'eve.deftype-proto.alloc/decode-class-idx
                                               offset-expr)))])
              field-read-bindings))
        all-bindings resolve-bindings]
    (if (empty? all-bindings)
      rewritten
      (list (apply list 'let all-bindings (vec rewritten))))))

(defn parse-protocol-impls [body]
  (loop [remaining body
         current-proto nil
         result []]
    (if (empty? remaining)
      (if current-proto (conj result current-proto) result)
      (let [form (first remaining)]
        (if (and (symbol? form) (not (list? form)))
          (recur (rest remaining)
                 {:protocol form :methods []}
                 (if current-proto (conj result current-proto) result))
          (if (and (seq? form) current-proto)
            (recur (rest remaining)
                   (update current-proto :methods conj
                           {:name (first form)
                            :args (second form)
                            :body (rest (rest form))})
                   result)
            (recur (rest remaining) current-proto result)))))))

(defn user-provides-protocol? [parsed-protos proto-sym]
  (some #(= proto-sym (:protocol %)) parsed-protos))

;;-----------------------------------------------------------------------------
;; JVM Code Generation Helpers (ISlabIO Pattern)
;;-----------------------------------------------------------------------------
;;
;; On JVM, slab field access uses the ISlabIO protocol:
;;   (-sio-read-i32 sio slab-offset field-off)
;;   (-sio-write-i32! sio slab-offset field-off val)
;; The deftype stores [^long slab-off__ sio__] instead of [offset__].
;; ISlabIO methods handle class/block resolution internally.

(defn jvm-read-fn-for-type
  "Return the JVM read expression for a field type.
   sio__ and slab-off__ are bound from deftype fields."
  [{:keys [type-hint type-class]} slab-off-expr field-offset]
  (case type-class
    :primitive
    (case type-hint
      :int8    (list 'let ['b__jvm (list 'eve.deftype-proto.alloc/-sio-read-u8
                                         'sio__ slab-off-expr field-offset)]
                 (list 'if (list '> 'b__jvm 127) (list '- 'b__jvm 256) 'b__jvm))
      :uint8   (list 'eve.deftype-proto.alloc/-sio-read-u8 'sio__ slab-off-expr field-offset)
      :int16   (list 'let ['v__jvm (list 'eve.deftype-proto.alloc/-sio-read-u16
                                         'sio__ slab-off-expr field-offset)]
                 (list 'if (list '> 'v__jvm 32767) (list '- 'v__jvm 65536) 'v__jvm))
      :uint16  (list 'eve.deftype-proto.alloc/-sio-read-u16 'sio__ slab-off-expr field-offset)
      :int32   (list 'eve.deftype-proto.alloc/-sio-read-i32 'sio__ slab-off-expr field-offset)
      :uint32  (list 'bit-and
                     (list 'long (list 'eve.deftype-proto.alloc/-sio-read-i32
                                       'sio__ slab-off-expr field-offset))
                     0xFFFFFFFF)
      :float32 (list 'Float/intBitsToFloat
                     (list 'int (list 'eve.deftype-proto.alloc/-sio-read-i32
                                      'sio__ slab-off-expr field-offset)))
      :float64 (list 'let ['lo__jvm (list 'bit-and
                                          (list 'long (list 'eve.deftype-proto.alloc/-sio-read-i32
                                                            'sio__ slab-off-expr field-offset))
                                          0xFFFFFFFF)
                            'hi__jvm (list 'bit-and
                                          (list 'long (list 'eve.deftype-proto.alloc/-sio-read-i32
                                                            'sio__ slab-off-expr (+ field-offset 4)))
                                          0xFFFFFFFF)]
                 (list 'Double/longBitsToDouble
                       (list 'bit-or (list 'bit-shift-left 'hi__jvm 32) 'lo__jvm))))
    :eve-type
    (list 'eve.deftype-proto.alloc/-sio-read-i32 'sio__ slab-off-expr field-offset)
    ;; default
    (list 'eve.deftype-proto.alloc/-sio-read-i32 'sio__ slab-off-expr field-offset)))

(defn jvm-write-fn-for-type
  "Return the JVM write expression for a field type."
  [{:keys [type-hint type-class]} slab-off-expr field-offset val-expr]
  (case type-class
    :primitive
    (case type-hint
      (:int8 :uint8)
      (list 'eve.deftype-proto.alloc/-sio-write-u8! 'sio__ slab-off-expr field-offset
            (list 'int val-expr))
      (:int16 :uint16)
      (list 'eve.deftype-proto.alloc/-sio-write-u16! 'sio__ slab-off-expr field-offset
            (list 'int val-expr))
      (:int32 :uint32)
      (list 'eve.deftype-proto.alloc/-sio-write-i32! 'sio__ slab-off-expr field-offset
            (list 'int val-expr))
      :float32
      (list 'eve.deftype-proto.alloc/-sio-write-i32! 'sio__ slab-off-expr field-offset
            (list 'Float/floatToRawIntBits (list 'float val-expr)))
      :float64
      (list 'let ['bits__jvm (list 'Double/doubleToRawLongBits (list 'double val-expr))]
        (list 'do
          (list 'eve.deftype-proto.alloc/-sio-write-i32! 'sio__ slab-off-expr field-offset
                (list 'unchecked-int (list 'bit-and 'bits__jvm 0xFFFFFFFF)))
          (list 'eve.deftype-proto.alloc/-sio-write-i32! 'sio__ slab-off-expr (+ field-offset 4)
                (list 'unchecked-int (list 'unsigned-bit-shift-right 'bits__jvm 32))))))
    :eve-type
    (list 'eve.deftype-proto.alloc/-sio-write-i32! 'sio__ slab-off-expr field-offset
          (list 'int val-expr))
    ;; default
    (list 'eve.deftype-proto.alloc/-sio-write-i32! 'sio__ slab-off-expr field-offset
          (list 'int val-expr))))

(defn jvm-field-bindings
  "Generate let-bindings for reading all fields on JVM."
  [fields slab-off-expr]
  (vec (mapcat (fn [f]
                 [(symbol (:name f))
                  (jvm-read-fn-for-type f slab-off-expr (:offset f))])
               fields)))

(defn jvm-rewrite-set!-forms
  "Rewrite (set! field val) to JVM ISlabIO write operations."
  [form field-map slab-off-expr]
  (cond
    ;; set! on a field
    (and (seq? form)
         (= 'set! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          val-expr (jvm-rewrite-set!-forms (nth form 2) field-map slab-off-expr)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (jvm-write-fn-for-type field-spec slab-off-expr (:offset field-spec) val-expr))

    ;; cas! — not yet supported on JVM
    (and (seq? form)
         (= 'cas! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (throw (ex-info "cas! in eve-slab-deftype not yet supported on JVM (needs -sio-cas-i32! on ISlabIO)"
                    {:field (name (second form))}))

    ;; Recurse
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

(defn jvm-transform-method-body
  "Transform method body for JVM deftype. slab-off__ and sio__ are deftype fields,
   accessible directly in method bodies."
  [body fields field-map]
  (let [slab-off-sym 'slab-off__
        field-read-bindings (jvm-field-bindings fields slab-off-sym)
        rewritten (map #(jvm-rewrite-set!-forms % field-map slab-off-sym) body)]
    (if (empty? field-read-bindings)
      rewritten
      (list (apply list 'let field-read-bindings (vec rewritten))))))

(defn jvm-transform-extend-method-body
  "Transform method body for JVM extend-type. Binds slab-off__ and sio__
   from the this reference since extend-type methods don't have field access."
  [body fields field-map this-sym type-name]
  (let [slab-off-sym 'slab-off__
        field-read-bindings (jvm-field-bindings fields slab-off-sym)
        rewritten (map #(jvm-rewrite-set!-forms % field-map slab-off-sym) body)
        resolve-bindings ['slab-off__ (list '.-slab-off__
                                            (with-meta this-sym {:tag type-name}))
                          'sio__      (list '.-sio__
                                            (with-meta this-sym {:tag type-name}))]
        all-bindings (vec (concat resolve-bindings field-read-bindings))]
    (list (apply list 'let all-bindings (vec rewritten)))))

;;-----------------------------------------------------------------------------
;; eve-slab-deftype macro
;;-----------------------------------------------------------------------------

(defmacro eve-slab-deftype
  "Define a EVE type with fast-path serialization.

   Fields support type hints for optimized storage:
     ^:int32, ^:uint32, ^:float32, ^:float64 — primitives
     ^:MyType — reference to another eve-slab-deftype (stored as offset)

   Mutability:
     (default) — immutable after construction
     ^:mutable — mutable via set!
     ^:volatile-mutable — atomic via set!/cas!

   The generated type:
     - Allocates from the slab allocator (no eve-env threading)
     - CLJS: uses slab resolve pattern (resolve-dv! per method), stores offset__
     - JVM: uses ISlabIO protocol methods, stores [^long slab-off__ sio__]

   Example:
     (eve-slab-deftype TreeNode [^:volatile-mutable ^:int32 key
                                 ^:volatile-mutable ^:TreeNode left
                                 ^:volatile-mutable ^:TreeNode right]
       ITreeNode
       (-get-key [this] key)
       (-set-key! [this v] (set! key v))
       (-cas-left! [this old new] (cas! left old new)))"
  [type-name fields & body]
  (let [;; === SHARED: Parse fields, compute layout, register type ===
        parsed-fields (mapv parse-field fields)
        {:keys [fields total-size]} (compute-layout parsed-fields)
        type-id (next-type-id!)
        type-key (str (ns-name *ns*) "/" (name type-name))

        _ (register-type! (name type-name)
                          {:type-id type-id
                           :total-size total-size
                           :fields fields
                           :type-key type-key})

        field-map (into {} (map (fn [f] [(:name f) f]) fields))
        parsed-protos (parse-protocol-impls body)
        has-volatile? (has-volatile-fields? fields)
        ctor-name (symbol (str "->" (name type-name)))
        ctor-args (mapv (fn [f] (symbol (:name f))) fields)]

    (if (:ns &env)
      ;; =================================================================
      ;; CLJS PATH (existing, unchanged)
      ;; =================================================================
      (let [off-sym 'offset__

            transformed-protos
            (mapcat (fn [{:keys [protocol methods]}]
                      (cons protocol
                            (map (fn [{:keys [name args body]}]
                                   (let [this-sym (first args)
                                         tbody (transform-method-body
                                                body fields field-map
                                                nil
                                                (list '.-offset__ this-sym)
                                                has-volatile?)]
                                     (list name args (cons 'do tbody))))
                                 methods)))
                    parsed-protos)

            boilerplate
            (concat
             (when-not (user-provides-protocol? parsed-protos 'IHash)
               ['IHash (list '-hash ['_] (list 'hash off-sym))])
             (when-not (user-provides-protocol? parsed-protos 'IEquiv)
               ['IEquiv
                (list '-equiv ['_ 'other]
                      (list 'and
                            (list 'instance? type-name 'other)
                            (list '== off-sym (list '.-offset__ 'other))))])
             (when-not (user-provides-protocol? parsed-protos 'IPrintWithWriter)
               ['IPrintWithWriter
                (list '-pr-writer ['this 'writer '_opts]
                      (list '-write 'writer (str "#slab/" (name type-name) " "))
                      (list '-write 'writer (str "{:offset " off-sym "}")))]))

            off-ctor-sym (gensym "offset_")
            field-writes (map (fn [f]
                                (write-fn-for-type
                                 (assoc f :mutability (if (= :volatile-mutable (:mutability f))
                                                        :volatile-mutable :mutable))
                                 nil 'slab-base__ (:offset f) (symbol (:name f))))
                              fields)]
        `(do
           (~'deftype ~type-name [~off-sym]
             ~@boilerplate
             ~@transformed-protos)

           (~'defn ~ctor-name [~@ctor-args]
             (~'when-not eve.deftype-proto.data/*parent-atom*
               (~'throw (~'js/Error. (~'str "Cannot construct " ~(str type-name)
                                           " outside a transaction — *parent-atom* not bound"))))
             (let [~off-ctor-sym (eve.deftype-proto.alloc/alloc-offset (+ ~total-size 4))
                   ~'slab-base__ (eve.deftype-proto.alloc/resolve-dv! ~off-ctor-sym)
                   ~'slab-dv__ eve.deftype-proto.alloc/resolved-dv
                   ~@(when has-volatile?
                       ['slab-i32__ (list ':i32
                                          (list 'eve.deftype-proto.wasm/get-slab-instance
                                                (list 'eve.deftype-proto.alloc/decode-class-idx
                                                      off-ctor-sym)))])
                   ~'slab-u8__ (eve.deftype-proto.alloc/resolve-u8! ~off-ctor-sym)]
               (dotimes [i# ~total-size]
                 (aset eve.deftype-proto.alloc/resolved-u8
                       (+ ~'slab-u8__ i#) 0))
               (eve.deftype-proto.alloc/resolve-dv! ~off-ctor-sym)
               (let [~'slab-dv__ eve.deftype-proto.alloc/resolved-dv
                     ~'slab-base__ eve.deftype-proto.alloc/resolved-base]
                 (.setUint8 ~'slab-dv__ ~'slab-base__ ~type-id)
                 ~@field-writes)
               (~(symbol (str (name type-name) ".")) ~off-ctor-sym)))

           (~'defn ~(symbol (str (name type-name) "-offset")) [inst#]
             (.-offset__ inst#))

           (~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)
           (~'def ~(symbol (str (name type-name) "-nil")) -1)

           ~type-name))

      ;; =================================================================
      ;; JVM PATH (new — ISlabIO-based field access)
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
             (when-not (user-provides-protocol? parsed-protos 'clojure.lang.IHashEq)
               ['clojure.lang.IHashEq
                (list 'hasheq ['_]
                      (list 'clojure.lang.Murmur3/hashLong 'slab-off__))])
             (when-not (user-provides-protocol? parsed-protos 'java.lang.Object)
               ['java.lang.Object
                (list 'equals ['_ 'other]
                      (list 'and
                            (list 'instance? type-name 'other)
                            (list '== 'slab-off__
                                  (list '.-slab-off__
                                        (with-meta 'other {:tag type-name})))))
                (list 'hashCode ['this] (list '.hasheq 'this))
                (list 'toString ['_]
                      (list 'str (str "#slab/" (name type-name) " {:offset ")
                            'slab-off__ "}"))]))

            field-writes
            (map (fn [f]
                   (jvm-write-fn-for-type
                    (assoc f :mutability (if (= :volatile-mutable (:mutability f))
                                           :volatile-mutable :mutable))
                    'slab-off__ (:offset f) (symbol (:name f))))
                 fields)]
        `(do
           ;; JVM deftype: [^long slab-off__ sio__]
           (~'deftype ~type-name [~slab-off-field ~'sio__]
             ~@boilerplate
             ~@transformed-protos)

           ;; Constructor — uses *jvm-slab-ctx* for sio
           (~'defn ~ctor-name [~@ctor-args]
             (let [~'sio__ eve.deftype-proto.alloc/*jvm-slab-ctx*]
               (~'when-not ~'sio__
                 (~'throw (IllegalStateException.
                            (~'str "Cannot construct " ~(str type-name)
                                   " — *jvm-slab-ctx* not bound"))))
               (let [~'slab-off__ (eve.deftype-proto.alloc/-sio-alloc! ~'sio__ ~(+ total-size 4))]
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

           ;; Nil sentinel constant
           (~'def ~(symbol (str (name type-name) "-nil")) -1)

           ~type-name)))))

(defmacro eve-slab-extend-type
  "Extend protocols to an existing eve-slab-deftype."
  [type-name & body]
  (let [type-reg (lookup-type (name type-name))
        _ (when-not type-reg
            (throw (ex-info (str "Unknown type: " type-name) {:type type-name})))
        fields (:fields type-reg)
        field-map (into {} (map (fn [f] [(:name f) f]) fields))
        has-volatile? (has-volatile-fields? fields)
        parsed-protos (parse-protocol-impls body)]

    (if (:ns &env)
      ;; CLJS PATH (existing, unchanged)
      (let [transformed-protos
            (mapcat (fn [{:keys [protocol methods]}]
                      (cons protocol
                            (map (fn [{:keys [name args body]}]
                                   (let [this-sym (first args)
                                         tbody (transform-method-body
                                                body fields field-map
                                                nil
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
                                         tbody (jvm-transform-extend-method-body
                                                body fields field-map
                                                this-sym type-name)]
                                     (list name args (cons 'do tbody))))
                                 methods)))
                    parsed-protos)]
        `(~'extend-type ~type-name ~@transformed-protos)))))
