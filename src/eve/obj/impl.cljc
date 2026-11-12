(ns eve.obj.impl
  "eve.obj's types and their block format, on every host through ISlabIO
   (eve.obj is the API). A namespace of its own: sci's deftype (bb, nbb)
   calls clojure.core's get from the namespace it is in, which eve.obj
   replaces with its own."
  (:refer-clojure :exclude [aget aset alength unchecked-byte unchecked-int
                            unsigned-bit-shift-right long bytes])
  (:require
   [eve.host :refer [aget aset alength unchecked-byte unchecked-int
                     unsigned-bit-shift-right long bytes]]
   [eve.core :as e]
   [eve.deftype :as edt]
   [eve.deftype-proto.data :as d]
   [eve.encoding :as enc]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.serialize :as ser]
   [eve.array :as arr]))

;;=============================================================================
;; Shared Schema — defines the shape of an object (like V8 hidden classes)
;;=============================================================================

(def ^:private type-sizes
  "Byte sizes for each supported type"
  {:int8    1
   :uint8   1
   :int16   2
   :uint16  2
   :int32   4
   :uint32  4
   :float32 4
   :float64 8
   :obj     4   ;; offset (int32) to another eve-obj
   :array   4}) ;; offset (int32) to an eve-array

(def ^:private type-alignments
  "Alignment requirements for each type"
  {:int8    1
   :uint8   1
   :int16   2
   :uint16  2
   :int32   4
   :uint32  4
   :float32 4
   :float64 8
   :obj     4
   :array   4})

(defn- align-offset
  "Align offset to the given alignment boundary"
  [offset alignment]
  (let [rem (mod offset alignment)]
    (if (zero? rem)
      offset
      (+ offset (- alignment rem)))))

(defn- compute-layout
  "Compute field offsets from a schema map.
   Returns {:fields {field-key {:type t :offset o :size s}} :total-size n}"
  [schema]
  (let [;; Sort fields by alignment (descending) for optimal packing, ties by
        ;; name: the same layout on every host whatever its map order
        sorted-fields (sort-by (fn [[k v]] [(- (clojure.core/get type-alignments v 4)) (str k)])
                               schema)]
    (loop [fields (seq sorted-fields)
           offset 0
           layout {}]
      (if-let [[field-key field-type] (first fields)]
        (let [size (clojure.core/get type-sizes field-type 4)
              alignment (clojure.core/get type-alignments field-type 4)
              aligned-offset (align-offset offset alignment)]
          (recur (next fields)
                 (+ aligned-offset size)
                 (clojure.core/assoc layout field-key {:type field-type
                                                       :offset aligned-offset
                                                       :size size})))
        {:fields layout
         :order (mapv first sorted-fields)
         :total-size (align-offset offset 4)}))))  ;; Final alignment to 4-byte boundary

(defn create-schema
  "Create a schema from a map of {field-key type-keyword}.
   Schema is immutable and can be reused for many objects.

   Example: (create-schema {:key :int32 :left :int32 :right :int32})"
  [field-map]
  (let [layout (compute-layout field-map)]
    {:field-map field-map
     :layout (:fields layout)
     :size (:total-size layout)
     :field-keys (:order layout)}))

;;-----------------------------------------------------------------------------
;; Shared schema encoding/decoding for slab serialization
;;-----------------------------------------------------------------------------

(defn- obj-type-kw->code [kw]
  (case kw :int8 0 :uint8 1 :int16 2 :uint16 3 :int32 4 :uint32 5
            :float32 6 :float64 7 :obj 8 :array 9
            (throw (enc/illegal-argument
                    (str "Unknown Obj field type: " kw)))))

(defn- obj-code->type-kw [code]
  (case (int code) 0 :int8 1 :uint8 2 :int16 3 :uint16 4 :int32 5 :uint32
                   6 :float32 7 :float64 8 :obj 9 :array))

;;=============================================================================
;; The object block (0x1E), on every host through ISlabIO
;;
;;   [0x1E:u8][pad:u8][schema-len:u16][schema bytes][field data @ 4+schema-len]
;;
;; Schema bytes: [n:u8] then per field [name-len:u8][name UTF-8][type code:u8],
;; zero-padded so the field data starts 8-byte aligned (an i32 field's atomics
;; and an f64's words sit on their own alignment).
;;=============================================================================

(defn- encode-obj-schema
  "The schema bytes of field-map, padded so 4 + their length is a multiple of 8."
  [field-map]
  (let [entries (mapv (fn [[k t]] [(enc/utf8-bytes (name k)) (obj-type-kw->code t)]) field-map)
        len (+ 1 (reduce + 0 (map (fn [[^bytes nb _]] (+ 2 (alength nb))) entries)))
        ^bytes buf (enc/make-bytes (- (align-offset (+ 4 len) 8) 4))]
    (aset buf 0 (unchecked-byte (count entries)))
    (loop [pos 1 es (seq entries)]
      (when es
        (let [[^bytes nb code] (first es)
              nlen (alength nb)]
          (aset buf pos (unchecked-byte nlen))
          (enc/arraycopy nb 0 buf (inc pos) nlen)
          (aset buf (+ pos 1 nlen) (unchecked-byte code))
          (recur (+ pos 2 nlen) (next es)))))
    buf))

(defn- decode-obj-schema
  "The field map the schema bytes bs encode."
  [^bytes bs]
  (let [u8 (fn [i] (bit-and (long (aget bs i)) 0xFF))
        n (u8 0)]
    (loop [i 0 pos 1 fm {}]
      (if (< i n)
        (let [nlen (u8 pos)]
          (recur (inc i) (+ pos 2 nlen)
                 (clojure.core/assoc fm (keyword (enc/utf8-string bs (inc pos) nlen))
                                     (obj-code->type-kw (u8 (+ pos 1 nlen))))))
        fm))))

(defn- read-field
  "The value of a field of type t at fld-off of the block at slab-off."
  [sio slab-off fld-off t]
  (case t
    :int8    (let [b (slab/read-u8 sio slab-off fld-off)]
               (if (> b 127) (- b 256) b))
    :uint8   (slab/read-u8 sio slab-off fld-off)
    :int16   (let [v (slab/read-u16 sio slab-off fld-off)]
               (if (> v 32767) (- v 65536) v))
    :uint16  (slab/read-u16 sio slab-off fld-off)
    (:int32 :obj :array) (slab/read-i32 sio slab-off fld-off)
    :uint32  (let [x (slab/read-i32 sio slab-off fld-off)]
               (if (neg? x) (+ x 4294967296) x))
    :float32 (enc/read-f32-le (slab/read-bytes sio slab-off fld-off 4) 0)
    :float64 (enc/read-f64-le (slab/read-bytes sio slab-off fld-off 8) 0)))

(defn write-field!
  "Write v as a field of type t at fld-off of the block at slab-off."
  [sio slab-off fld-off t v]
  (case t
    (:int8 :uint8)   (slab/write-u8! sio slab-off fld-off (bit-and (int v) 0xFF))
    (:int16 :uint16) (slab/write-u16! sio slab-off fld-off (bit-and (int v) 0xFFFF))
    (:int32 :uint32 :obj :array) (slab/write-i32! sio slab-off fld-off (unchecked-int (long v)))
    :float32 (slab/write-bytes! sio slab-off fld-off (enc/f32-le-bytes v))
    :float64 (slab/write-bytes! sio slab-off fld-off (enc/f64-le-bytes v))))

(defprotocol IEveObj
  "An eve object's parts, for the fns over it (bb's types have no field access
   from outside their body)."
  (-obj-parts [o] "[schema sio slab-off data-off]"))

(defn field-value [o k not-found]
  (let [[schema sio slab-off data-off] (-obj-parts o)]
    (if-let [info (clojure.core/get (:layout schema) k)]
      (read-field sio slab-off (+ data-off (:offset info)) (:type info))
      not-found)))

(declare obj= obj-hash)

(edt/deftype Obj [schema sio slab-off data-off mta]
  clojure.lang.IMeta
  (meta [_] mta)

  clojure.lang.IObj
  (withMeta [_ m] (Obj. schema sio slab-off data-off m))

  clojure.lang.Counted
  (count [_] (count (:field-keys schema)))

  clojure.lang.ILookup
  (valAt [this k] (field-value this k nil))
  (valAt [this k not-found] (field-value this k not-found))

  clojure.lang.IFn
  (invoke [this k] (field-value this k nil))
  (invoke [this k not-found] (field-value this k not-found))

  clojure.lang.Seqable
  (seq [this]
    (map (fn [k] (enc/map-entry k (field-value this k nil))) (:field-keys schema)))

  clojure.lang.IHashEq
  (hasheq [this] (obj-hash this))

  ;; cons and empty too: basilisp's types refuse a partial interface
  clojure.lang.IPersistentCollection
  (cons [_ _] (enc/throw-unsupported))
  (empty [_] nil)
  (equiv [this other] (obj= this other))

  d/IPlain
  (-plain [this]
    (into {} (map (fn [k] [k (d/eve->clj (field-value this k nil))])) (:field-keys schema)))

  IEveObj
  (-obj-parts [_] [schema sio slab-off data-off])

  ;; its block, in its domain: an atom's value built in that domain (inside
  ;; swap!) holds the object by pointer
  d/IEveRoot
  (-root-header-off [_] slab-off)

  d/IEveSio
  (-eve-sio [_] sio)

  IPrintWithWriter
  (-pr-writer [this w _opts]
    (e/-write w (str "#eve/obj " (e/pr-str* (into {} (e/seq this))))))

  java.lang.Object
  (toString [this] (str "#eve/obj " (e/pr-str* (into {} (e/seq this)))))
  (equals [this other] (obj= this other))
  (hashCode [this] (obj-hash this)))

(defn- obj-hash [o]
  (reduce (fn [h k] (unchecked-int (+ (* 31 h) (hash [k (field-value o k nil)]))))
          1 (:field-keys (first (-obj-parts o)))))

(defn- obj= [o other]
  (cond
    (identical? o other) true
    (not (satisfies? IEveObj other)) false
    :else (let [ks (:field-keys (first (-obj-parts o)))]
            (and (= ks (:field-keys (first (-obj-parts other))))
                 (every? (fn [k] (= (field-value o k nil) (field-value other k nil))) ks)))))

(defn obj-from-offset
  "The object at the 0x1E block slab-off in sio."
  [sio slab-off]
  (let [slen (slab/read-u16 sio slab-off 2)
        schema (create-schema (decode-obj-schema (slab/read-bytes sio slab-off 4 slen)))]
    (->Obj schema sio slab-off (+ 4 slen) nil)))

(defn ->schema [schema-or-field-map]
  (if (and (map? schema-or-field-map) (not (:layout schema-or-field-map)))
    (create-schema schema-or-field-map)
    schema-or-field-map))

(defn write-obj-block!
  "A 0x1E block in sio holding an object of schema (or a field map) with
   values (fields not given are 0); answers its slab offset."
  [sio schema-or-field-map values]
  (let [schema (->schema schema-or-field-map)
        sbytes (encode-obj-schema (:field-map schema))
        slen (alength ^bytes sbytes)
        slab-off (slab/alloc! sio (+ 4 slen (:size schema)))
        data-off (+ 4 slen)]
    (slab/write-u8! sio slab-off 0 ser/EVE_OBJ_SLAB_TYPE_ID)
    (slab/write-u8! sio slab-off 1 0)
    (slab/write-u16! sio slab-off 2 slen)
    (slab/write-bytes! sio slab-off 4 sbytes)
    (doseq [[k info] (:layout schema)]
      (write-field! sio slab-off (+ data-off (:offset info)) (:type info) (clojure.core/get values k 0)))
    slab-off))

(defn read-obj-block
  "The 0x1E block at slab-off as {:schema field-map :values {field value}}."
  [sio slab-off]
  (let [o (obj-from-offset sio slab-off)
        schema (first (-obj-parts o))]
    {:schema (:field-map schema)
     :values (into {} (map (fn [k] [k (field-value o k nil)])) (:field-keys schema))}))

(defn obj
  "A new typed object of a schema (or a field map) with initial values; fields
   not given are 0. It lives in the running swap!'s domain; outside a context
   (alloc/current-sio) throws."
  ([schema-or-field-map]
   (obj schema-or-field-map {}))
  ([schema-or-field-map init-values]
   (let [schema (->schema schema-or-field-map)
         sio (alloc/current-sio)
         slab-off (write-obj-block! sio schema init-values)]
     (->Obj schema sio slab-off (+ 4 (slab/read-u16 sio slab-off 2)) nil))))

;;=============================================================================
;; Object Array (SoA style): one eve-array column per field
;;=============================================================================

(defprotocol IEveObjArray
  (-obj-array-parts [a] "[schema length columns]"))

(defn column-of
  "The eve-array column of field k, or nil."
  [arr k]
  (clojure.core/get (nth (-obj-array-parts arr) 2) k))

(defn row-value
  "Field k of row idx of an object array, or not-found."
  [arr idx k not-found]
  (if-let [col (column-of arr k)]
    (if (and (>= idx 0) (< idx (nth (-obj-array-parts arr) 1))) (arr/aget col idx) not-found)
    not-found))

(defn- obj-array-str [a]
  (let [[schema length] (-obj-array-parts a)]
    (str "#eve/obj-array [schema=" (e/pr-str* (:field-map schema)) " length=" length "]")))

(edt/deftype ObjArrayRow [parent idx]
  clojure.lang.ILookup
  (valAt [_ k] (row-value parent idx k nil))
  (valAt [_ k not-found] (row-value parent idx k not-found))

  clojure.lang.IFn
  (invoke [_ k] (row-value parent idx k nil))
  (invoke [_ k not-found] (row-value parent idx k not-found))

  clojure.lang.Seqable
  (seq [_]
    (map (fn [k] (enc/map-entry k (row-value parent idx k nil)))
         (:field-keys (first (-obj-array-parts parent)))))

  IPrintWithWriter
  (-pr-writer [this w _opts]
    (e/-write w (str "#eve/obj-array-row " (e/pr-str* (into {} (e/seq this))))))

  java.lang.Object
  (toString [this] (str "#eve/obj-array-row " (e/pr-str* (into {} (e/seq this))))))

(edt/deftype ObjArray [schema length columns mta]
  clojure.lang.IMeta
  (meta [_] mta)

  clojure.lang.IObj
  (withMeta [_ m] (ObjArray. schema length columns m))

  clojure.lang.Counted
  (count [_] length)

  clojure.lang.Indexed
  (nth [this i]
    (if (and (>= i 0) (< i length))
      (ObjArrayRow. this i)
      (throw (enc/index-out-of-bounds (str "Index out of bounds: " i)))))
  (nth [this i not-found]
    (if (and (>= i 0) (< i length)) (ObjArrayRow. this i) not-found))

  clojure.lang.IFn
  (invoke [this i] (e/nth this i))
  (invoke [this i not-found] (e/nth this i not-found))

  clojure.lang.Seqable
  (seq [this] (when (pos? length) (map (fn [i] (ObjArrayRow. this i)) (range length))))

  IEveObjArray
  (-obj-array-parts [_] [schema length columns])

  ;; a print body is the type's print-method on some hosts: no fields in it
  IPrintWithWriter
  (-pr-writer [this w _opts] (e/-write w (obj-array-str this)))

  java.lang.Object
  (toString [this] (obj-array-str this)))

;;=============================================================================
;; Object Array - Constructor
;;=============================================================================

(defn obj-array
  ([n schema-or-field-map] (obj-array n schema-or-field-map nil))
  ([n schema-or-field-map init-val]
   (let [schema (->schema schema-or-field-map)
         columns (into {}
                       (map (fn [[k _]]
                              [k (if init-val (arr/int32-array n init-val) (arr/int32-array n))])
                            (:layout schema)))]
     (->ObjArray schema n columns nil))))

;;=============================================================================
;; Slab-backed atom root registration
;;=============================================================================

;; An object stored into an atom is copied into a block of the atom's domain
(ser/register-builder!
 (fn [v] (satisfies? IEveObj v))
 (fn [sio o]
   (let [[schema src-sio src-off data-off] (-obj-parts o)
         total (+ data-off (:size schema))
         blk-off (slab/alloc! sio total)]
     (slab/write-bytes! sio blk-off 0 (slab/read-bytes src-sio src-off 0 total))
     (reify d/IEveRoot
       (-root-header-off [_] blk-off)))))

;; An Obj at the atom root or nested in a value (0x1E): the one registry
(ser/register-type-constructor!
 ser/EVE_OBJ_SLAB_TYPE_ID ser/EVE_OBJ_SLAB_TYPE_ID
 (fn [sio header-off] (obj-from-offset sio header-off)))

(ser/register-header-disposer!
 ser/EVE_OBJ_SLAB_TYPE_ID
 (fn [sio blk-off] (slab/free! sio blk-off)))
