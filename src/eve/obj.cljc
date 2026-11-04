(ns eve.obj
  "Typed objects and object-arrays in slab memory, on every host.

   Two representations optimized for different use cases:

   1. eve-obj (AoS) - Single object with named typed fields, one 0x1E block
      Good for: individual records, tree nodes, whole-object operations

   2. eve-obj-array (SoA) - Collection of objects stored column-wise, one
      eve-array per field
      Good for: batch processing, cache-efficient iteration

   Both reach memory through ISlabIO; int32/uint32 fields (and int32 columns)
   carry the atomics (cas!, add!, sub!, exchange!). The types and the block
   format are eve.obj.impl's."
  (:refer-clojure :exclude [get assoc! get-in unchecked-int long])
  (:require
   [eve.host :refer [unchecked-int long]]
   [eve.encoding :as enc]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc]
   [eve.array :as arr]
   [eve.obj.impl :as impl]))

(def create-schema impl/create-schema)
(def obj impl/obj)
(def obj-from-offset impl/obj-from-offset)
(def obj-array impl/obj-array)

;;=============================================================================
;; Single Object - Mutation & Atomics
;;=============================================================================

(defn- field-info [o k]
  (or (clojure.core/get (:layout (first (impl/-obj-parts o))) k)
      (throw (enc/illegal-argument (str "Unknown field: " k)))))

(defn get
  "Get field value from object."
  [o k]
  (impl/field-value o k nil))

(defn assoc!
  "Set field value in object. Returns v."
  [o k v]
  (let [[_ sio slab-off data-off] (impl/-obj-parts o)
        info (field-info o k)]
    (impl/write-field! sio slab-off (+ data-off (:offset info)) (:type info) v)
    v))

(defn- update-i32-field!
  "Atomically replace an i32-word field (types allowed) with (f old) by a CAS
   loop on its word. Answers old (a :uint32 unsigned)."
  [o k types op f]
  (let [[_ sio slab-off data-off] (impl/-obj-parts o)
        info (field-info o k)
        type (:type info)
        fld (+ data-off (:offset info))]
    (when-not (contains? types type)
      (throw (enc/illegal-argument (str op " not supported on " type " fields"))))
    (loop []
      (let [w (slab/read-i32 sio slab-off fld)
            old (if (and (= type :uint32) (neg? w)) (+ w 4294967296) w)
            nw (unchecked-int (long (f old)))]
        (if (== w (slab/cas-i32! sio slab-off fld w nw)) old (recur))))))

(defn cas!
  "Compare-and-swap on an int32/uint32/obj/array field. Returns true if successful."
  [o k expected new-val]
  (== expected (update-i32-field! o k #{:int32 :uint32 :obj :array} "cas!"
                                  (fn [old] (if (== old expected) new-val old)))))

(defn add!
  "Atomic add on an int32/uint32 field. Returns the old value."
  [o k delta]
  (update-i32-field! o k #{:int32 :uint32} "add!" (fn [old] (+ old delta))))

(defn sub!
  "Atomic subtract on an int32/uint32 field. Returns the old value."
  [o k delta]
  (update-i32-field! o k #{:int32 :uint32} "sub!" (fn [old] (- old delta))))

(defn exchange!
  "Atomic exchange on an int32/uint32/obj/array field. Returns the old value."
  [o k new-val]
  (update-i32-field! o k #{:int32 :uint32 :obj :array} "exchange!" (fn [_] new-val)))

;;=============================================================================
;; Object Array - Field Access
;;=============================================================================

(defn- column! [arr k]
  (or (impl/column-of arr k) (throw (enc/illegal-argument (str "Unknown field: " k)))))

(defn get-in
  "Get field value at [idx field-key] from object array."
  ([arr path] (get-in arr path nil))
  ([arr [idx k] not-found]
   (impl/row-value arr idx k not-found)))

(defn assoc-in!
  "Set field value at [idx field-key] in object array."
  [arr [idx k] v]
  (arr/aset! (column! arr k) idx v)
  v)

(defn cas-in! [arr [idx k] expected new-val]
  (arr/cas! (column! arr k) idx expected new-val))

(defn add-in! [arr [idx k] delta]
  (arr/add! (column! arr k) idx delta))

(defn sub-in! [arr [idx k] delta]
  (arr/sub! (column! arr k) idx delta))

(defn exchange-in! [arr [idx k] new-val]
  (arr/exchange! (column! arr k) idx new-val))

;;=============================================================================
;; Object Array - Column Access
;;=============================================================================

(defn column [arr k] (impl/column-of arr k))

(defn column-reduce [arr k init f]
  (arr/areduce (column! arr k) init f))

(defn column-map! [arr k f]
  (arr/amap! (column! arr k) f))

;;=============================================================================
;; Low-level access
;;=============================================================================

(defn get-schema [obj-or-arr]
  (cond (satisfies? impl/IEveObj obj-or-arr) (first (impl/-obj-parts obj-or-arr))
        (satisfies? impl/IEveObjArray obj-or-arr) (first (impl/-obj-array-parts obj-or-arr))
        :else nil))

(defn get-offset
  "The object's block (its slab-qualified offset): what an :obj field holds."
  [o]
  (nth (impl/-obj-parts o) 2))

;;=============================================================================
;; GC / Lifecycle
;;=============================================================================

(defn retire!
  "Free the object's block. Call when nothing reads it any more."
  [o]
  (let [[_ sio slab-off] (impl/-obj-parts o)]
    (slab/free! sio slab-off)))

