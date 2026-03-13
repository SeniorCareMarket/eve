(ns eve2.deftype
  "Unified CLJC deftype macro for Eve data structures.

   Users write one deftype form with CLJS-style protocol names.
   The macro expands differently per platform:

   CLJS → slab-backed deftype with resolve-dv!/field-read pattern
   CLJ  → standard deftype with JVM interfaces from proto-map

   Example:
     (eve2/deftype EvePoint [^:int32 x ^:int32 y]
       ICounted
       (-count [_] 2)
       ILookup
       (-lookup [_ k] (case k :x x :y y nil))
       (-lookup [_ k nf] (case k :x x :y y nf)))"
  (:require [eve2.proto-map :as pm]
            [eve2.deftype.registry :as reg]))

;;=============================================================================
;; Code Generation — Shared Helpers
;;=============================================================================

(defn- read-fn-for-type
  "DataView read expression for a field. Uses slab-dv__ + slab-base__."
  [{:keys [type-hint type-class mutability]} offset-expr field-offset]
  (let [volatile? (= :volatile-mutable mutability)
        abs-offset (list '+ offset-expr field-offset)]
    (if volatile?
      (list 'js/Atomics.load 'slab-i32__
            (list 'unsigned-bit-shift-right abs-offset 2))
      (case type-class
        :primitive
        (case type-hint
          :int8    (list '.getInt8    'slab-dv__ abs-offset)
          :uint8   (list '.getUint8   'slab-dv__ abs-offset)
          :int16   (list '.getInt16   'slab-dv__ abs-offset true)
          :uint16  (list '.getUint16  'slab-dv__ abs-offset true)
          :int32   (list '.getInt32   'slab-dv__ abs-offset true)
          :uint32  (list '.getUint32  'slab-dv__ abs-offset true)
          :float32 (list '.getFloat32 'slab-dv__ abs-offset true)
          :float64 (list '.getFloat64 'slab-dv__ abs-offset true))
        :eve-type
        (list '.getInt32 'slab-dv__ abs-offset true)
        ;; :serialized — default int32
        (list '.getInt32 'slab-dv__ abs-offset true)))))

(defn- write-fn-for-type
  "DataView write expression for a field."
  [{:keys [type-hint type-class mutability]} offset-expr field-offset val-expr]
  (let [volatile? (= :volatile-mutable mutability)
        abs-offset (list '+ offset-expr field-offset)]
    (if volatile?
      (list 'js/Atomics.store 'slab-i32__
            (list 'unsigned-bit-shift-right abs-offset 2) val-expr)
      (case type-class
        :primitive
        (case type-hint
          :int8    (list '.setInt8    'slab-dv__ abs-offset val-expr)
          :uint8   (list '.setUint8   'slab-dv__ abs-offset val-expr)
          :int16   (list '.setInt16   'slab-dv__ abs-offset val-expr true)
          :uint16  (list '.setUint16  'slab-dv__ abs-offset val-expr true)
          :int32   (list '.setInt32   'slab-dv__ abs-offset val-expr true)
          :uint32  (list '.setUint32  'slab-dv__ abs-offset val-expr true)
          :float32 (list '.setFloat32 'slab-dv__ abs-offset val-expr true)
          :float64 (list '.setFloat64 'slab-dv__ abs-offset val-expr true))
        :eve-type
        (list '.setInt32 'slab-dv__ abs-offset val-expr true)
        ;; :serialized
        (list '.setInt32 'slab-dv__ abs-offset val-expr true)))))

(defn- field-bindings
  "Generate let-bindings for reading all fields from slab."
  [fields]
  (vec (mapcat (fn [f]
                 [(symbol (:name f))
                  (read-fn-for-type f 'slab-base__ (:offset f))])
               fields)))

(defn- has-volatile-fields? [fields]
  (some #(= :volatile-mutable (:mutability %)) fields))

;;=============================================================================
;; set!/cas! Rewriting
;;=============================================================================

(defn- rewrite-set!-forms
  "Rewrite (set! field val) and (cas! field old new) to slab ops."
  [form field-map]
  (cond
    ;; set! on a field
    (and (seq? form)
         (= 'set! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          val-expr (rewrite-set!-forms (nth form 2) field-map)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (write-fn-for-type field-spec 'slab-base__ (:offset field-spec) val-expr))

    ;; cas! on a field
    (and (seq? form)
         (= 'cas! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          expected-expr (rewrite-set!-forms (nth form 2) field-map)
          new-expr (rewrite-set!-forms (nth form 3) field-map)
          abs-offset (list '+ 'slab-base__ (:offset field-spec))]
      (when (not= :volatile-mutable (:mutability field-spec))
        (throw (ex-info "cas! requires ^:volatile-mutable field" {:field (:name field-spec)})))
      (list '==
            expected-expr
            (list 'js/Atomics.compareExchange
                  'slab-i32__
                  (list 'unsigned-bit-shift-right abs-offset 2)
                  expected-expr new-expr)))

    ;; Recurse into forms
    (seq? form)
    (with-meta (apply list (map #(rewrite-set!-forms % field-map) form))
               (meta form))
    (vector? form)
    (with-meta (mapv #(rewrite-set!-forms % field-map) form)
               (meta form))
    (map? form)
    (with-meta (into {} (map (fn [[k v]]
                               [(rewrite-set!-forms k field-map)
                                (rewrite-set!-forms v field-map)])
                             form))
               (meta form))
    :else form))

;;=============================================================================
;; Protocol Parsing
;;=============================================================================

(defn- parse-protocol-impls
  "Parse body into [{:protocol sym :methods [{:name sym :args vec :body list}]}]"
  [body]
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

(defn- user-provides-protocol? [parsed-protos proto-sym]
  (some #(= proto-sym (:protocol %)) parsed-protos))

;;=============================================================================
;; CLJS Code Generation
;;=============================================================================

(defn- cljs-transform-method-body
  "Wrap method body in slab resolve + field reads + set!/cas! rewriting."
  [body fields field-map offset-expr has-volatile?]
  (let [field-read-bindings (field-bindings fields)
        rewritten (map #(rewrite-set!-forms % field-map) body)
        resolve-bindings
        (vec (concat
              ['slab-base__ (list 'eve.deftype-proto.alloc/resolve-dv! offset-expr)
               'slab-dv__ 'eve.deftype-proto.alloc/resolved-dv]
              (when has-volatile?
                ['slab-i32__ (list ':i32
                                   (list 'eve.deftype-proto.wasm/get-slab-instance
                                         (list 'eve.deftype-proto.alloc/decode-class-idx
                                               offset-expr)))])
              field-read-bindings))]
    (if (empty? resolve-bindings)
      rewritten
      (list (apply list 'let resolve-bindings (vec rewritten))))))

(defn- cljs-transform-protos
  "Transform parsed protocol impls for CLJS slab expansion."
  [parsed-protos fields field-map has-volatile?]
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
          parsed-protos))

(defn- cljs-boilerplate
  "Generate default IHash/IEquiv/IPrintWithWriter for CLJS."
  [type-name parsed-protos off-sym]
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
            (list '-write 'writer (str "#eve2/" (name type-name) " "))
            (list '-write 'writer (str "{:offset " off-sym "}")))])))

(defn- emit-cljs
  "Emit CLJS deftype expansion."
  [type-name fields parsed-protos type-id total-size]
  (let [field-map (into {} (map (fn [f] [(:name f) f]) fields))
        has-volatile? (has-volatile-fields? fields)
        off-sym 'offset__
        transformed (cljs-transform-protos parsed-protos fields field-map has-volatile?)
        boilerplate (cljs-boilerplate type-name parsed-protos off-sym)
        ctor-name (symbol (str "->" (name type-name)))
        ctor-args (mapv (fn [f] (symbol (:name f))) fields)
        off-ctor-sym (gensym "offset_")
        field-writes (map (fn [f]
                            (write-fn-for-type
                             (assoc f :mutability (if (= :volatile-mutable (:mutability f))
                                                    :volatile-mutable :mutable))
                             'slab-base__ (:offset f) (symbol (:name f))))
                          fields)]
    `(do
       (~'deftype ~type-name [~off-sym]
         ~@boilerplate
         ~@transformed)

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

       ~type-name)))

;;=============================================================================
;; CLJ Code Generation
;;=============================================================================

(defn- clj-translate-method
  "Translate a CLJS method spec to its JVM equivalent using proto-map.
   Returns nil if the method should be skipped (e.g., CLJS-only like INext)."
  [method-spec proto-sym]
  (let [{:keys [name]} method-spec
        mapping (pm/lookup proto-sym)]
    (if-not mapping
      ;; No mapping — pass through verbatim (user-defined protocol)
      method-spec
      (let [methods-map (:methods mapping)
            method-info (get methods-map name)]
        (when method-info
          (if-let [jvm-name (:jvm-name method-info)]
            (assoc method-spec :name jvm-name)
            nil))))))

(defn- clj-translate-protocol
  "Translate a parsed protocol to its JVM form."
  [{:keys [protocol methods]}]
  (let [mapping (pm/lookup protocol)]
    (if-not mapping
      {:protocol protocol :methods methods}
      (if (:marker? mapping)
        {:protocol (:iface mapping) :methods [] :marker? true}
        (when-let [iface (:iface mapping)]
          (let [translated (keep #(clj-translate-method % protocol) methods)]
            {:protocol iface :methods (vec translated)}))))))

(defn- clj-emit-protocols
  "Emit JVM protocol/interface implementations.
   Each method body is auto-wrapped in (binding [*jvm-slab-ctx* sio] ...)
   so downstream functions that read the dynamic var work correctly."
  [parsed-protos]
  (let [translated (keep clj-translate-protocol parsed-protos)
        by-iface (group-by :protocol translated)]
    (mapcat (fn [[iface impls]]
              (let [all-methods (mapcat :methods impls)
                    marker? (some :marker? impls)
                    ;; Normalize java.lang.Object → Object for deftype
                    iface (if (= iface 'java.lang.Object) 'Object iface)]
                (if marker?
                  [iface]
                  (cons iface
                        (map (fn [{:keys [name args body]}]
                               (list name args
                                     (list 'clojure.core/binding
                                           ['eve.deftype-proto.alloc/*jvm-slab-ctx* 'sio]
                                           (cons 'do body))))
                             all-methods)))))
            by-iface)))

(defn- has-equiv-impl?
  "Check if parsed protos include an equiv method (via IEquiv mapping or direct)."
  [parsed-protos]
  (or (user-provides-protocol? parsed-protos 'IEquiv)
      (some (fn [{:keys [protocol methods]}]
              (and (= protocol 'clojure.lang.IPersistentCollection)
                   (some #(= (:name %) 'equiv) methods)))
            parsed-protos)))

(defn- user-provides-object?
  "Check if user provides Object or java.lang.Object implementations."
  [parsed-protos]
  (some #(contains? #{'Object 'java.lang.Object} (:protocol %)) parsed-protos))

(defn- clj-boilerplate
  "Generate JVM boilerplate."
  [type-name parsed-protos]
  (concat
   (when-not (or (user-provides-protocol? parsed-protos 'IHash)
                 (some #(= 'clojure.lang.IHashEq (:protocol %)) parsed-protos))
     ['clojure.lang.IHashEq
      (list 'hasheq ['this] (list 'System/identityHashCode 'this))])
   (when-not (user-provides-object? parsed-protos)
     ['Object
      (list 'hashCode ['this] (list '.hasheq 'this))
      (list 'equals ['this 'other]
            (if (has-equiv-impl? parsed-protos)
              (list 'cond
                    (list 'identical? 'this 'other) true
                    :else (list '.equiv 'this 'other))
              (list 'and (list 'instance? type-name 'other)
                    (list '= (list '.hasheq 'this) (list '.hasheq 'other)))))
      (list 'toString ['this] (str "#eve2/" (name type-name)))])))

(defn- emit-clj
  "Emit CLJ deftype expansion.
   CLJ deftype fields: [declared-fields... ^long offset__ sio _meta]
   offset__ stores the slab header offset (equivalent to CLJS offset__)."
  [type-name fields parsed-protos type-id _total-size]
  (let [jvm-fields (mapv (fn [f]
                           (let [sym (symbol (:name f))
                                 hint (case (:type-hint f)
                                        (:int32 :uint32) 'long
                                        :float64 'double
                                        nil)]
                             (if hint
                               (with-meta sym {:tag hint})
                               sym)))
                         fields)
        ;; Add offset__ (slab header offset) + sio + _meta
        all-fields (-> jvm-fields
                       (conj (with-meta 'offset__ {:tag 'long}))
                       (conj 'sio)
                       (conj '_meta))
        translated-protos (clj-emit-protocols parsed-protos)
        boilerplate (clj-boilerplate type-name parsed-protos)
        ctor-name (symbol (str "->" (name type-name)))]
    `(do
       (~'deftype ~type-name ~all-fields
         ~@boilerplate
         ~@translated-protos)

       (~'defn ~ctor-name [~@(mapv (fn [f] (symbol (:name f))) fields)]
         (new ~type-name ~@(mapv (fn [f] (symbol (:name f))) fields)
              0 eve.deftype-proto.alloc/*jvm-slab-ctx* nil))

       (~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)

       ~type-name)))

;;=============================================================================
;; Main Macro
;;=============================================================================

(defmacro eve2-deftype
  "Define a unified CLJC Eve type.

   Fields support type hints for optimized storage:
     ^:int32, ^:uint32, ^:float32, ^:float64 — primitives
     ^:MyType — reference to another eve2-deftype (stored as offset on CLJS)

   Mutability:
     (default) — immutable after construction
     ^:mutable — mutable via set!
     ^:volatile-mutable — atomic via set!/cas! (CLJS only, Atomics)

   Protocol implementations use CLJS-style names (ICounted, -count, etc.).
   On JVM, these are translated to JVM interfaces automatically."
  [type-name fields & body]
  (let [parsed-fields (mapv reg/parse-field fields)
        {:keys [fields total-size]} (reg/compute-layout parsed-fields)
        type-id (or (:type-id (clojure.core/meta type-name))
                    (reg/next-type-id!))
        type-key (str (ns-name *ns*) "/" (name type-name))
        _ (reg/register-type! (name type-name)
                              {:type-id type-id
                               :total-size total-size
                               :fields fields
                               :type-key type-key})
        parsed-protos (parse-protocol-impls body)]
    (if (:ns &env)
      ;; CLJS compilation
      (emit-cljs type-name fields parsed-protos type-id total-size)
      ;; CLJ compilation
      (emit-clj type-name fields parsed-protos type-id total-size))))
