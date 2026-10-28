(ns eve.deftype-proto.macros.registry
  "Compile-time type registry for eve/deftype macro.
   Handles field parsing, layout computation, and type ID allocation."
  (:require [eve.host]
            [eve.deftype.registry :as dt-reg]))

(defonce registry (atom {}))

;; A user type's id, when it declares none ((def TypeName-type-id ...)): from
;; its namespace-qualified name, as an eve-deftype's is
;; (eve.deftype.registry/type-id-of; FORMAT-DECISIONS §7). A type id is a
;; format fact: every process sharing a disk or network atom derives the same
;; one, whatever it loaded and in whatever order. Two such types in one
;; process with the same id are refused: one declares its own.
(defonce derived-ids (atom {}))   ;; type-id -> the type-key holding it

(defn derived-type-id!
  "The type id of the eve/deftype named type-key (\"my.ns/MyType\")."
  [type-key]
  (let [id (dt-reg/type-id-of type-key)
        holder (get (swap! derived-ids update id #(or % type-key)) id)]
    (when (not= holder type-key)
      (throw (ex-info (str "eve/deftype " type-key " and " holder " derive the same type id " id
                           "; declare one's own (def <Type>-type-id ...)")
                      {:type-key type-key :holder holder :type-id id})))
    id))

(defn register-type! [type-name spec]
  (swap! registry assoc type-name spec))

(defn lookup-type [type-name]
  (get @registry type-name))

(def primitive-types
  #{:int8 :uint8 :int16 :uint16 :int32 :uint32 :float32 :float64})

(defn primitive-type? [t]
  (contains? primitive-types t))

(defn field-size [type-kw]
  (case type-kw
    (:int8 :uint8)     1
    (:int16 :uint16)   2
    (:int32 :uint32)   4
    :float32           4
    :float64           8
    :serialized        4
    4))

(defn field-alignment [type-kw]
  (case type-kw
    (:int8 :uint8)     1
    (:int16 :uint16)   2
    (:int32 :uint32)   4
    :float32           4
    :float64           8
    4))

(defn parse-field [field-sym]
  (let [m (meta field-sym)
        cached?   (:cached m)
        mutability (cond
                     (:volatile-mutable m) :volatile-mutable
                     (:mutable m)          :mutable
                     :else                 :immutable)
        hint-keys (disj (set (keys m))
                        :cached :mutable :volatile-mutable :tag
                        :file :line :column :end-line :end-column :source)
        type-hint (first hint-keys)
        type-class (cond
                     cached?                     :cached
                     (nil? type-hint)            :serialized
                     (primitive-type? type-hint)  :primitive
                     :else                        :eve-type)]
    {:name       (name field-sym)
     :sym        field-sym
     :cached?    (boolean cached?)
     :mutability mutability
     :type-hint  type-hint
     :type-class type-class}))

(defn- align-offset [offset alignment]
  (let [rem (mod offset alignment)]
    (if (zero? rem) offset (+ offset (- alignment rem)))))

(defn compute-layout [parsed-fields]
  (loop [fields parsed-fields
         offset 4
         result []]
    (if (empty? fields)
      {:fields result
       :total-size (align-offset offset 4)}
      (let [f (first fields)]
        (if (:cached? f)
          ;; Cached fields don't consume slab space — no offset, no size
          (recur (rest fields)
                 offset
                 (conj result (assoc f :offset nil :size 0)))
          (let [sz (if (= :eve-type (:type-class f))
                     4
                     (field-size (:type-hint f)))
                alignment (if (= :eve-type (:type-class f))
                            4
                            (field-alignment (:type-hint f)))
                aligned-offset (align-offset offset alignment)]
            (recur (rest fields)
                   (+ aligned-offset sz)
                   (conj result (assoc f :offset aligned-offset :size sz)))))))))
