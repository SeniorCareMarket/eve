(ns eve2.deftype.registry
  "Compile-time type registry for eve2/deftype.")

(defonce registry (atom {}))
(defonce next-id (atom 64))

(defn next-type-id! []
  (let [id @next-id]
    (swap! next-id inc)
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
        mutability (cond
                     (:volatile-mutable m) :volatile-mutable
                     (:mutable m)          :mutable
                     :else                 :immutable)
        hint-keys (disj (set (keys m))
                        :mutable :volatile-mutable :tag
                        :file :line :column :end-line :end-column :source)
        type-hint (first hint-keys)
        type-class (cond
                     (nil? type-hint)            :serialized
                     (primitive-type? type-hint)  :primitive
                     :else                        :eve-type)]
    {:name       (name field-sym)
     :sym        field-sym
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
      (let [f (first fields)
            sz (if (= :eve-type (:type-class f))
                 4
                 (field-size (:type-hint f)))
            alignment (if (= :eve-type (:type-class f))
                        4
                        (field-alignment (:type-hint f)))
            aligned-offset (align-offset offset alignment)]
        (recur (rest fields)
               (+ aligned-offset sz)
               (conj result (assoc f :offset aligned-offset :size sz)))))))
