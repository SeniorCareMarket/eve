(ns eve.deftype-proto.eve3-deftype
  "Unified CLJC deftype macro for Eve data structures.

   Users write CLJ-style protocol names (clojure.lang.Counted, count).
   On CLJS, the macro translates to CLJS equivalents (ICounted, -count).
   On CLJ, names pass through verbatim.

   Both platforms use ISlabIO for field access.
   Deftype fields are always [sio__ ^long offset__] on both platforms.

   Example:
     (eve3/eve3-deftype EvePoint [^:int32 x ^:int32 y]
       clojure.lang.Counted
       (count [_] 2))"
  (:require [eve.deftype-proto.proto-map :as pm]
            [eve.deftype-proto.eve3-deftype.registry :as reg]
            [eve.deftype-proto.data :as d]))

;;=============================================================================
;; Field Read/Write via ISlabIO
;;=============================================================================

(defn- sio-read-expr
  "ISlabIO read expression for a field."
  [{:keys [type-hint type-class]} offset-sym field-offset]
  (let [read-fn (case type-class
                  :primitive
                  (case type-hint
                    (:int8 :uint8)     'eve.deftype-proto.alloc/-sio-read-u8
                    (:int16 :uint16)   'eve.deftype-proto.alloc/-sio-read-u16
                    (:int32 :uint32 :float32 :float64)
                    'eve.deftype-proto.alloc/-sio-read-i32)
                  ;; :eve-type, :serialized — stored as int32
                  'eve.deftype-proto.alloc/-sio-read-i32)]
    (list read-fn 'sio__ offset-sym field-offset)))

(defn- sio-write-expr
  "ISlabIO write expression for a field."
  [{:keys [type-hint type-class]} offset-sym field-offset val-expr]
  (let [write-fn (case type-class
                   :primitive
                   (case type-hint
                     (:int8 :uint8)     'eve.deftype-proto.alloc/-sio-write-u8!
                     (:int16 :uint16)   'eve.deftype-proto.alloc/-sio-write-u16!
                     (:int32 :uint32 :float32 :float64)
                     'eve.deftype-proto.alloc/-sio-write-i32!)
                   ;; :eve-type, :serialized
                   'eve.deftype-proto.alloc/-sio-write-i32!)]
    (list write-fn 'sio__ offset-sym field-offset val-expr)))

(defn- field-bindings
  "Generate let-bindings that read all fields from slab via ISlabIO."
  [fields]
  (vec (mapcat (fn [f]
                 [(symbol (:name f))
                  (sio-read-expr f 'offset__ (:offset f))])
               fields)))

;;=============================================================================
;; set!/cas! Rewriting (ISlabIO-based)
;;=============================================================================

(defn- rewrite-set!-forms
  "Rewrite (set! field val) to ISlabIO write calls."
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
      (sio-write-expr field-spec 'offset__ (:offset field-spec) val-expr))

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
;; Shared: Transform method bodies (field reads + set! rewriting)
;;=============================================================================

(defn- transform-method-body
  "Wrap method body with field-read let-bindings and set! rewriting."
  [body fields field-map]
  (let [field-read-bindings (field-bindings fields)
        rewritten (map #(rewrite-set!-forms % field-map) body)]
    (if (empty? field-read-bindings)
      rewritten
      (list (apply list 'let field-read-bindings (vec rewritten))))))

;;=============================================================================
;; CLJS Code Generation
;;=============================================================================

(defn- cljs-translate-method
  "Translate a CLJ method to its CLJS equivalent using proto-map."
  [method-spec iface-sym]
  (let [{:keys [name]} method-spec
        mapping (pm/lookup iface-sym)]
    (if-not mapping
      ;; No mapping — pass through verbatim
      method-spec
      (let [methods-map (:methods mapping)
            method-info (get methods-map name)]
        (when method-info
          (if-let [cljs-name (:cljs-name method-info)]
            (assoc method-spec
                   :name cljs-name
                   :cljs-proto-override (or (:cljs-proto-override method-info)
                                            (:cljs-proto method-info)))
            ;; nil cljs-name means CLJ-only method, skip
            nil))))))

(defn- cljs-translate-protocol
  "Translate a parsed protocol to its CLJS form.
   Returns a seq of {:protocol :methods} entries (may be >1 for split protocols)."
  [{:keys [protocol methods]}]
  (let [mapping (pm/lookup protocol)]
    (if-not mapping
      ;; No mapping — pass through verbatim
      [{:protocol protocol :methods methods}]
      (if (:clj-only? mapping)
        ;; CLJ-only: skip entirely on CLJS
        []
        (if (:marker? mapping)
          [{:protocol (:cljs-proto mapping) :methods [] :marker? true}]
          (let [translated (keep #(cljs-translate-method % protocol) methods)]
            (if (:split? mapping)
              ;; Split: group methods by their cljs-proto-override
              (let [by-proto (group-by (fn [m]
                                         (or (:cljs-proto-override m)
                                             (:cljs-proto mapping)))
                                       translated)]
                (mapv (fn [[proto ms]]
                        {:protocol proto
                         :methods (mapv #(dissoc % :cljs-proto-override) ms)})
                      by-proto))
              ;; Normal: methods with cljs-proto-override go to separate protos
              (let [{overridden true normal false}
                    (group-by #(boolean (:cljs-proto-override %)) translated)
                    result (if (seq normal)
                             [{:protocol (:cljs-proto mapping)
                               :methods (mapv #(dissoc % :cljs-proto-override) normal)}]
                             [])]
                (into result
                      (map (fn [m]
                             {:protocol (:cljs-proto-override m)
                              :methods [(dissoc m :cljs-proto-override)]})
                           overridden))))))))))

(defn- cljs-emit-protocols
  "Emit CLJS protocol implementations from CLJ-style parsed protos."
  [parsed-protos fields field-map]
  (let [translated (mapcat cljs-translate-protocol parsed-protos)
        by-proto (group-by :protocol translated)]
    (mapcat (fn [[proto impls]]
              (let [all-methods (mapcat :methods impls)
                    marker? (some :marker? impls)]
                (if marker?
                  [proto]
                  (cons proto
                        (map (fn [{:keys [name args body]}]
                               (let [tbody (transform-method-body body fields field-map)]
                                 (list name args (cons 'do tbody))))
                             all-methods)))))
            by-proto)))

(defn- cljs-boilerplate
  "Generate default CLJS boilerplate protocols."
  [type-name parsed-protos type-key]
  (concat
   (when-not (user-provides-protocol? parsed-protos 'clojure.lang.IHashEq)
     ['IHash (list '-hash ['_] (list 'hash (list '.-offset__ '_)))])
   (when-not (user-provides-protocol? parsed-protos 'clojure.lang.IPersistentCollection)
     ['IEquiv
      (list '-equiv ['_ 'other]
            (list 'and
                  (list 'instance? type-name 'other)
                  (list '== (list '.-offset__ '_) (list '.-offset__ 'other))))])
   (when-not (user-provides-protocol? parsed-protos 'java.lang.Object)
     ['IPrintWithWriter
      (list '-pr-writer ['this 'writer '_opts]
            (list '-write 'writer (str "#eve/" (name type-name) " "))
            (list '-write 'writer (str "{:offset " (list '.-offset__ 'this) "}")))])
   ;; ISabpType — every eve3 type carries its qualified name
   ['eve.deftype-proto.data/ISabpType
    (list '-sabp-type-key ['_] type-key)]))

(defn- emit-cljs
  "Emit CLJS deftype expansion."
  [type-name fields parsed-protos type-id total-size emit-type-id-def? type-key]
  (let [field-map (into {} (map (fn [f] [(:name f) f]) fields))
        translated (cljs-emit-protocols parsed-protos fields field-map)
        boilerplate (cljs-boilerplate type-name parsed-protos type-key)]
    `(do
       (~'deftype ~type-name [~'sio__ ~'offset__]
         ~@boilerplate
         ~@translated)

       ~@(when emit-type-id-def?
           [`(~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)])

       (~'def ~(symbol (str (name type-name) "-type-key")) ~type-key)

       ~type-name)))

;;=============================================================================
;; CLJ Code Generation
;;=============================================================================

(defn- clj-emit-protocols
  "Emit CLJ protocol/interface implementations.
   CLJ names pass through verbatim — no translation needed.
   Method bodies get field-read let-bindings."
  [parsed-protos fields field-map]
  (let [grouped (group-by :protocol parsed-protos)]
    (mapcat (fn [[proto impls]]
              (let [all-methods (mapcat :methods impls)
                    marker? (some (fn [imp] (empty? (:methods imp))) impls)
                    ;; Normalize java.lang.Object → Object for deftype
                    proto (if (= proto 'java.lang.Object) 'Object proto)]
                (if (and marker? (empty? all-methods))
                  [proto]
                  (cons proto
                        (map (fn [{:keys [name args body]}]
                               (let [tbody (transform-method-body body fields field-map)]
                                 (list name args (cons 'do tbody))))
                             all-methods)))))
            grouped)))

(defn- has-equiv-impl? [parsed-protos]
  (some (fn [{:keys [protocol methods]}]
          (and (= protocol 'clojure.lang.IPersistentCollection)
               (some #(= (:name %) 'equiv) methods)))
        parsed-protos))

(defn- user-provides-object? [parsed-protos]
  (some #(contains? #{'Object 'java.lang.Object} (:protocol %)) parsed-protos))

(defn- clj-boilerplate
  "Generate JVM boilerplate."
  [type-name parsed-protos type-key]
  (concat
   (when-not (user-provides-protocol? parsed-protos 'clojure.lang.IHashEq)
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
      (list 'toString ['this] (str "#eve/" (name type-name)))])
   ;; ISabpType — every eve3 type carries its qualified name
   ['eve.deftype-proto.data/ISabpType
    (list '-sabp-type-key ['_] type-key)]))

(defn- emit-clj
  "Emit CLJ deftype expansion.
   Fields: [sio__ ^long offset__]
   sio__ is the ISlabIO context, offset__ is the slab header offset."
  [type-name fields parsed-protos type-id _total-size emit-type-id-def? type-key]
  (let [field-map (into {} (map (fn [f] [(:name f) f]) fields))
        translated (clj-emit-protocols parsed-protos fields field-map)
        boilerplate (clj-boilerplate type-name parsed-protos type-key)]
    `(do
       (~'deftype ~type-name [~'sio__ ~(with-meta 'offset__ {:tag 'long})]
         ~@boilerplate
         ~@translated)

       ~@(when emit-type-id-def?
           [`(~'def ~(symbol (str (name type-name) "-type-id")) ~type-id)])

       (~'def ~(symbol (str (name type-name) "-type-key")) ~type-key)

       ~type-name)))

;;=============================================================================
;; Main Macro
;;=============================================================================

(defmacro eve3-deftype
  "Define a unified CLJC Eve type.

   Fields support type hints: ^:int32, ^:uint32, ^:float32, ^:float64.
   Protocol implementations use CLJ-style names (clojure.lang.Counted, count).
   On CLJS, these are translated to CLJS equivalents automatically.

   Both platforms store [sio__ offset__] — ISlabIO context + slab offset.
   Field reads emit ISlabIO protocol calls on both platforms.

   Type-id resolution (in priority order):
     1. ^{:type-id 0xNN} metadata on type-name (explicit override)
     2. (def TypeName-type-id ...) in same namespace (convention lookup)
     3. Auto-assigned from incrementing counter (for user types)"
  [type-name fields & body]
  (let [parsed-fields (mapv reg/parse-field fields)
        {:keys [fields total-size]} (reg/compute-layout parsed-fields)
        resolved-var (resolve (symbol (str (name type-name) "-type-id")))
        type-id (or (:type-id (clojure.core/meta type-name))
                    (when resolved-var (deref resolved-var))
                    (reg/next-type-id!))
        ;; Only emit (def TypeName-type-id ...) if not already defined
        emit-type-id-def? (nil? resolved-var)
        type-key (str (ns-name *ns*) "/" (name type-name))
        _ (reg/register-type! (name type-name)
                              {:type-id type-id
                               :total-size total-size
                               :fields fields
                               :type-key type-key})
        parsed-protos (parse-protocol-impls body)]
    (if (:ns &env)
      ;; CLJS compilation — use CLJS namespace from &env
      (emit-cljs type-name fields parsed-protos type-id total-size emit-type-id-def? type-key)
      ;; CLJ compilation
      (emit-clj type-name fields parsed-protos type-id total-size emit-type-id-def? type-key))))
