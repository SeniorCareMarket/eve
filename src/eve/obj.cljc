(ns eve.obj
  "Simple typed objects and object-arrays backed by SharedArrayBuffer (CLJS)
   or slab memory (JVM).

   Two representations optimized for different use cases:

   1. eve-obj (AoS) - Single object with named typed fields
      Good for: individual records, tree nodes, whole-object operations

   2. eve-obj-array (SoA) - Collection of objects stored column-wise (CLJS only)
      Good for: batch processing, SIMD operations, cache-efficient iteration

   Both provide full Atomics API and compose with eve-array (CLJS).
   JVM provides read/write via ISlabIO."
  (:refer-clojure :exclude [get assoc! get-in])
  (:require
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.serialize :as ser]
   #?@(:cljs [[eve.shared-atom :as atom]
              [eve.array :as arr]
              [eve.wasm-mem :as wasm]]
       :clj  [[eve.mem :as mem]])))

;; Forward declarations
#?(:cljs (declare get ObjArrayRow get-in))

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
  (let [;; Sort fields by alignment (descending) for optimal packing
        sorted-fields (sort-by (fn [[_k v]] (- (clojure.core/get type-alignments v 4)))
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
     :field-keys (vec (keys (:fields layout)))}))

;;-----------------------------------------------------------------------------
;; Shared schema encoding/decoding for slab serialization
;;-----------------------------------------------------------------------------

(defn- obj-type-kw->code [kw]
  (case kw :int8 0 :uint8 1 :int16 2 :uint16 3 :int32 4 :uint32 5
            :float32 6 :float64 7 :obj 8 :array 9
            (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
                    (str "Unknown Obj field type: " kw)))))

(defn- obj-code->type-kw [code]
  (case (int code) 0 :int8 1 :uint8 2 :int16 3 :uint16 4 :int32 5 :uint32
                   6 :float32 7 :float64 8 :obj 9 :array))

;;=============================================================================
;; CLJS implementation — SharedArrayBuffer + Atomics
;;=============================================================================

#?(:cljs
   (do

(deftype Obj [schema
              ^js/SharedArrayBuffer sab
              ^number offset
              ^number descriptor-idx ;; block descriptor for GC tracking
              ^:mutable __hash
              ^IPersistentMap _meta]

  Object
  (toString [this]
    (str "#eve/obj " (into {} (map (fn [k] [k (get this k)]) (:field-keys schema)))))

  IMeta
  (-meta [_] _meta)

  IWithMeta
  (-with-meta [_ new-meta]
    (Obj. schema sab offset descriptor-idx __hash new-meta))

  ICounted
  (-count [_] (count (:field-keys schema)))

  ILookup
  (-lookup [this k]
    (-lookup this k nil))
  (-lookup [this k not-found]
    (if-let [field-info (clojure.core/get (:layout schema) k)]
      (let [field-offset (+ offset (:offset field-info))
            field-type (:type field-info)
            dv (js/DataView. sab)]
        (case field-type
          :int8    (.getInt8 dv field-offset)
          :uint8   (.getUint8 dv field-offset)
          :int16   (.getInt16 dv field-offset true)
          :uint16  (.getUint16 dv field-offset true)
          :int32   (js/Atomics.load (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2))
          :uint32  (js/Atomics.load (js/Uint32Array. sab) (unsigned-bit-shift-right field-offset 2))
          :float32 (.getFloat32 dv field-offset true)
          :float64 (.getFloat64 dv field-offset true)
          :obj     (js/Atomics.load (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2))
          :array   (js/Atomics.load (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2))
          not-found))
      not-found))

  IFn
  (-invoke [this k]
    (-lookup this k))
  (-invoke [this k not-found]
    (-lookup this k not-found))

  ISeqable
  (-seq [this]
    (map (fn [k] (MapEntry. k (-lookup this k) nil))
         (:field-keys schema)))

  IHash
  (-hash [this]
    (if __hash
      __hash
      (let [h (reduce (fn [h k]
                        (+ (* 31 h) (hash [k (-lookup this k)])))
                      1
                      (:field-keys schema))]
        (set! __hash h)
        h)))

  IEquiv
  (-equiv [this ^js other]
    (cond
      (identical? this other) true
      (not (instance? Obj other)) false
      (not= (:field-keys schema) (:field-keys (.-schema other))) false
      :else (every? (fn [k] (= (-lookup this k) (-lookup other k)))
                    (:field-keys schema))))

  IPrintWithWriter
  (-pr-writer [this writer opts]
    (-write writer "#eve/obj {")
    (let [ks (:field-keys schema)]
      (loop [i 0]
        (when (< i (count ks))
          (let [k (nth ks i)]
            (when (pos? i) (-write writer ", "))
            (-write writer (pr-str k))
            (-write writer " ")
            (-write writer (pr-str (-lookup this k)))
            (recur (inc i))))))
    (-write writer "}")))

;;=============================================================================
;; Single Object - Mutation & Atomics
;;=============================================================================

(defn get
  "Get field value from object. Uses Atomics.load for int32/uint32."
  [^Obj obj k]
  (-lookup obj k))

(defn assoc!
  "Set field value in object. Uses Atomics.store for int32/uint32."
  [^Obj obj k v]
  (let [schema (.-schema obj)
        sab (.-sab obj)
        base-offset (.-offset obj)]
    (if-let [field-info (clojure.core/get (:layout schema) k)]
      (let [field-offset (+ base-offset (:offset field-info))
            field-type (:type field-info)
            dv (js/DataView. sab)]
        (case field-type
          :int8    (.setInt8 dv field-offset v)
          :uint8   (.setUint8 dv field-offset v)
          :int16   (.setInt16 dv field-offset v true)
          :uint16  (.setUint16 dv field-offset v true)
          :int32   (js/Atomics.store (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2) v)
          :uint32  (js/Atomics.store (js/Uint32Array. sab) (unsigned-bit-shift-right field-offset 2) v)
          :float32 (.setFloat32 dv field-offset v true)
          :float64 (.setFloat64 dv field-offset v true)
          :obj     (js/Atomics.store (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2) v)
          :array   (js/Atomics.store (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2) v))
        v)
      (throw (js/Error. (str "Unknown field: " k))))))

(defn cas!
  "Compare-and-swap on int32/uint32 field. Returns true if successful."
  [^Obj obj k expected new-val]
  (let [schema (.-schema obj)
        sab (.-sab obj)
        base-offset (.-offset obj)]
    (if-let [field-info (clojure.core/get (:layout schema) k)]
      (let [field-offset (+ base-offset (:offset field-info))
            field-type (:type field-info)]
        (case field-type
          (:int32 :obj :array)
          (== expected (js/Atomics.compareExchange (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2) expected new-val))
          :uint32
          (== expected (js/Atomics.compareExchange (js/Uint32Array. sab) (unsigned-bit-shift-right field-offset 2) expected new-val))
          (throw (js/Error. (str "CAS only supported on int32/uint32/obj/array fields, got: " field-type)))))
      (throw (js/Error. (str "Unknown field: " k))))))

(defn add!
  "Atomic add on int32/uint32 field. Returns old value."
  [^Obj obj k delta]
  (let [schema (.-schema obj)
        sab (.-sab obj)
        base-offset (.-offset obj)]
    (if-let [field-info (clojure.core/get (:layout schema) k)]
      (let [field-offset (+ base-offset (:offset field-info))
            field-type (:type field-info)]
        (case field-type
          :int32 (js/Atomics.add (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2) delta)
          :uint32 (js/Atomics.add (js/Uint32Array. sab) (unsigned-bit-shift-right field-offset 2) delta)
          (throw (js/Error. (str "add! only supported on int32/uint32 fields, got: " field-type)))))
      (throw (js/Error. (str "Unknown field: " k))))))

(defn sub!
  "Atomic subtract on int32/uint32 field. Returns old value."
  [^Obj obj k delta]
  (let [schema (.-schema obj)
        sab (.-sab obj)
        base-offset (.-offset obj)]
    (if-let [field-info (clojure.core/get (:layout schema) k)]
      (let [field-offset (+ base-offset (:offset field-info))
            field-type (:type field-info)]
        (case field-type
          :int32 (js/Atomics.sub (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2) delta)
          :uint32 (js/Atomics.sub (js/Uint32Array. sab) (unsigned-bit-shift-right field-offset 2) delta)
          (throw (js/Error. (str "sub! only supported on int32/uint32 fields, got: " field-type)))))
      (throw (js/Error. (str "Unknown field: " k))))))

(defn exchange!
  "Atomic exchange on int32/uint32 field. Returns old value."
  [^Obj obj k new-val]
  (let [schema (.-schema obj)
        sab (.-sab obj)
        base-offset (.-offset obj)]
    (if-let [field-info (clojure.core/get (:layout schema) k)]
      (let [field-offset (+ base-offset (:offset field-info))
            field-type (:type field-info)]
        (case field-type
          (:int32 :obj :array)
          (js/Atomics.exchange (js/Int32Array. sab) (unsigned-bit-shift-right field-offset 2) new-val)
          :uint32
          (js/Atomics.exchange (js/Uint32Array. sab) (unsigned-bit-shift-right field-offset 2) new-val)
          (throw (js/Error. (str "exchange! only supported on int32/uint32/obj/array fields, got: " field-type)))))
      (throw (js/Error. (str "Unknown field: " k))))))

;;=============================================================================
;; Single Object - Constructor
;;=============================================================================

(defn- alloc-obj-region [schema]
  (let [byte-size (:size schema)
        eve-env (if atom/*global-atom-instance*
                  (atom/get-env atom/*global-atom-instance*)
                  (throw (js/Error. "No global atom instance.")))
        alloc-result (atom/alloc eve-env byte-size)]
    (if (:error alloc-result)
      (throw (js/Error. (str "Failed to allocate eve-obj: " (:error alloc-result))))
      {:sab (:sab eve-env)
       :offset (:offset alloc-result)
       :descriptor-idx (:descriptor-idx alloc-result)})))

(defn obj
  "Create a new typed object from a schema and initial values."
  ([schema-or-field-map]
   (obj schema-or-field-map {}))
  ([schema-or-field-map init-values]
   (let [schema (if (map? schema-or-field-map)
                  (if (:layout schema-or-field-map)
                    schema-or-field-map
                    (create-schema schema-or-field-map))
                  schema-or-field-map)
         {:keys [sab offset descriptor-idx]} (alloc-obj-region schema)
         obj-instance (Obj. schema sab offset descriptor-idx nil nil)]
     (doseq [[k v] init-values]
       (assoc! obj-instance k v))
     (doseq [[k field-info] (:layout schema)]
       (when (and (not (contains? init-values k))
                  (#{:int32 :uint32 :obj :array} (:type field-info)))
         (assoc! obj-instance k 0)))
     obj-instance)))

;;=============================================================================
;; Object Array (SoA style) - CLJS only
;;=============================================================================

(deftype ObjArray [schema ^number length columns ^:mutable __hash ^IPersistentMap _meta]
  Object
  (toString [this]
    (str "#eve/obj-array [schema=" (pr-str (:field-map schema)) " length=" length "]"))
  IMeta (-meta [_] _meta)
  IWithMeta (-with-meta [_ new-meta] (ObjArray. schema length columns __hash new-meta))
  ICounted (-count [_] length)
  IIndexed
  (-nth [this idx]
    (if (and (>= idx 0) (< idx length)) (ObjArrayRow. this idx)
      (throw (js/Error. (str "Index out of bounds: " idx)))))
  (-nth [this idx not-found]
    (if (and (>= idx 0) (< idx length)) (ObjArrayRow. this idx) not-found))
  IFn
  (-invoke [this idx] (-nth this idx))
  (-invoke [this idx not-found] (-nth this idx not-found))
  ISeqable
  (-seq [this] (when (pos? length) (map #(-nth this %) (range length))))
  IPrintWithWriter
  (-pr-writer [this writer opts]
    (-write writer (str "#eve/obj-array [schema=" (pr-str (:field-map schema)) " length=" length "]"))))

(deftype ObjArrayRow [^ObjArray parent ^number idx]
  Object
  (toString [this]
    (let [schema (.-schema parent)]
      (str "#eve/obj-array-row " (into {} (map (fn [k] [k (get-in parent [idx k])]) (:field-keys schema))))))
  ILookup
  (-lookup [this k] (get-in parent [idx k]))
  (-lookup [this k not-found] (get-in parent [idx k] not-found))
  IFn
  (-invoke [this k] (-lookup this k))
  (-invoke [this k not-found] (-lookup this k not-found))
  ISeqable
  (-seq [this]
    (let [schema (.-schema parent)]
      (map (fn [k] (MapEntry. k (-lookup this k) nil)) (:field-keys schema))))
  IPrintWithWriter
  (-pr-writer [this writer opts]
    (let [schema (.-schema parent)]
      (-write writer "#eve/obj-array-row {")
      (let [ks (:field-keys schema)]
        (loop [i 0]
          (when (< i (count ks))
            (let [k (nth ks i)]
              (when (pos? i) (-write writer ", "))
              (-write writer (pr-str k))
              (-write writer " ")
              (-write writer (pr-str (-lookup this k)))
              (recur (inc i))))))
      (-write writer "}"))))

;;=============================================================================
;; Object Array - Field Access
;;=============================================================================

(defn get-in
  "Get field value at [idx field-key] from object array."
  ([^ObjArray arr path] (get-in arr path nil))
  ([^ObjArray arr [idx k] not-found]
   (let [columns (.-columns arr)]
     (if-let [col (clojure.core/get columns k)]
       (if (and (>= idx 0) (< idx (.-length arr))) (arr/aget col idx) not-found)
       not-found))))

(defn assoc-in!
  "Set field value at [idx field-key] in object array."
  [^ObjArray arr [idx k] v]
  (let [columns (.-columns arr)]
    (if-let [col (clojure.core/get columns k)]
      (do (arr/aset! col idx v) v)
      (throw (js/Error. (str "Unknown field: " k))))))

(defn cas-in! [^ObjArray arr [idx k] expected new-val]
  (if-let [col (clojure.core/get (.-columns arr) k)]
    (arr/cas! col idx expected new-val)
    (throw (js/Error. (str "Unknown field: " k)))))

(defn add-in! [^ObjArray arr [idx k] delta]
  (if-let [col (clojure.core/get (.-columns arr) k)]
    (arr/add! col idx delta)
    (throw (js/Error. (str "Unknown field: " k)))))

(defn sub-in! [^ObjArray arr [idx k] delta]
  (if-let [col (clojure.core/get (.-columns arr) k)]
    (arr/sub! col idx delta)
    (throw (js/Error. (str "Unknown field: " k)))))

(defn exchange-in! [^ObjArray arr [idx k] new-val]
  (if-let [col (clojure.core/get (.-columns arr) k)]
    (arr/exchange! col idx new-val)
    (throw (js/Error. (str "Unknown field: " k)))))

;;=============================================================================
;; Object Array - Column Access
;;=============================================================================

(defn column [^ObjArray arr k] (clojure.core/get (.-columns arr) k))

(defn column-reduce [^ObjArray arr k init f]
  (if-let [col (column arr k)] (arr/areduce col init f)
    (throw (js/Error. (str "Unknown field: " k)))))

(defn column-map! [^ObjArray arr k f]
  (if-let [col (column arr k)] (arr/amap! col f)
    (throw (js/Error. (str "Unknown field: " k)))))

;;=============================================================================
;; Object Array - Constructor
;;=============================================================================

(defn obj-array
  ([n schema-or-field-map] (obj-array n schema-or-field-map nil))
  ([n schema-or-field-map init-val]
   (let [schema (if (map? schema-or-field-map)
                  (if (:layout schema-or-field-map) schema-or-field-map (create-schema schema-or-field-map))
                  schema-or-field-map)
         columns (into {}
                       (map (fn [[k _]]
                              [k (if init-val (arr/int32-array n init-val) (arr/int32-array n))])
                            (:layout schema)))]
     (ObjArray. schema n columns nil nil))))

;;=============================================================================
;; Low-level access
;;=============================================================================

(defn get-schema [obj-or-arr]
  (cond (instance? Obj obj-or-arr) (.-schema obj-or-arr)
        (instance? ObjArray obj-or-arr) (.-schema obj-or-arr)
        :else nil))

(defn get-sab [^Obj obj] (.-sab obj))
(defn get-offset [^Obj obj] (.-offset obj))
(defn get-descriptor-idx [^Obj obj] (.-descriptor-idx obj))

;;=============================================================================
;; GC / Lifecycle
;;=============================================================================

(defn retire! [^Obj obj]
  (when-let [desc-idx (.-descriptor-idx obj)]
    (let [eve-env (when atom/*global-atom-instance*
                    (atom/get-env atom/*global-atom-instance*))]
      (when eve-env (atom/retire-block! eve-env desc-idx)))))

;;=============================================================================
;; Slab-backed atom root registration
;;=============================================================================

(defn- encode-obj-schema [field-map]
  (let [encoder (js/TextEncoder.)
        fields  (seq field-map)
        nbufs   (mapv (fn [[k _]] (.encode encoder (name k))) fields)
        total   (+ 1 (reduce + 0 (map #(+ 2 (.-length %)) nbufs)))
        buf     (js/Uint8Array. total)
        dv      (js/DataView. (.-buffer buf))]
    (.setUint8 dv 0 (count fields))
    (loop [pos 1, fseq (seq fields), bseq (seq nbufs)]
      (when fseq
        (let [[_ ftype] (first fseq)
              nbuf      (first bseq)
              nlen      (.-length nbuf)]
          (.setUint8 dv pos nlen)
          (.set buf nbuf (inc pos))
          (.setUint8 dv (+ pos 1 nlen) (obj-type-kw->code ftype))
          (recur (+ pos 2 nlen) (next fseq) (next bseq)))))
    buf))

(defn- decode-obj-schema [^js u8 start]
  (let [dv      (js/DataView. (.-buffer u8))
        n       (.getUint8 dv start)
        decoder (js/TextDecoder.)]
    (loop [i 0, pos (inc start), fm {}]
      (if (< i n)
        (let [nlen      (.getUint8 dv pos)
              nm        (.decode decoder (.subarray u8 (inc pos) (+ pos 1 nlen)))
              type-code (.getUint8 dv (+ pos 1 nlen))]
          (recur (inc i) (+ pos 2 nlen)
                 (clojure.core/assoc fm (keyword nm) (obj-code->type-kw type-code))))
        fm))))

(ser/register-cljs-to-sab-builder!
  (fn [v] (instance? Obj v))
  (fn [^Obj o]
    (let [schema     (.-schema o)
          field-map  (:field-map schema)
          data-size  (:size schema)
          schema-buf (encode-obj-schema field-map)
          schema-len (.-length schema-buf)
          blk-off    (alloc/alloc-offset (+ 4 schema-len data-size))]
      (alloc/write-u8!  blk-off 0 ser/EVE_OBJ_SLAB_TYPE_ID)
      (alloc/write-u8!  blk-off 1 0)
      (alloc/write-u16! blk-off 2 schema-len)
      (alloc/write-bytes! blk-off 4 schema-buf)
      (when (pos? data-size)
        (alloc/write-bytes! blk-off (+ 4 schema-len)
                            (js/Uint8Array. (.-sab o) (.-offset o) data-size)))
      (reify d/IEveRoot
        (-root-header-off [_] blk-off)))))

(ser/register-header-constructor!
  ser/EVE_OBJ_SLAB_TYPE_ID
  (fn [_sab blk-off]
    (let [schema-len (alloc/read-u16 blk-off 2)
          class-idx  (alloc/decode-class-idx blk-off)
          u8-view    (proto-wasm/slab-u8-view class-idx)
          byte-base  (alloc/slab-offset->byte-offset blk-off)
          field-map  (decode-obj-schema u8-view (+ byte-base 4))
          schema     (create-schema field-map)
          sab        (.-buffer u8-view)
          data-off   (+ byte-base 4 schema-len)]
      (Obj. schema sab data-off -1 nil nil))))

(ser/register-header-disposer!
  ser/EVE_OBJ_SLAB_TYPE_ID
  (fn [blk-off] (alloc/free! blk-off)))

)) ;; end #?(:cljs (do ...))

;;=============================================================================
;; JVM implementation — slab-backed typed objects via ISlabIO
;;=============================================================================

#?(:clj
   (do

     (deftype JvmObj [schema       ;; {:field-map, :layout, :size, :field-keys}
                      ^long slab-off  ;; slab-qualified offset of 0x1E block
                      ^long data-off  ;; relative offset of field data within block
                      sio           ;; ISlabIO context
                      ^:unsynchronized-mutable _hash_val]

       clojure.lang.Counted
       (count [_] (clojure.core/count (:field-keys schema)))

       clojure.lang.ILookup
       (valAt [this k] (.valAt this k nil))
       (valAt [this k not-found]
         (if-let [{:keys [type offset]} (clojure.core/get (:layout schema) k)]
           (let [fld-off (+ data-off offset)]
             (case type
               :int8    (let [b (alloc/-sio-read-u8 sio slab-off fld-off)]
                          (if (> b 127) (- b 256) b))
               :uint8   (alloc/-sio-read-u8 sio slab-off fld-off)
               :int16   (let [v (alloc/-sio-read-u16 sio slab-off fld-off)]
                          (if (> v 32767) (- v 65536) v))
               :uint16  (alloc/-sio-read-u16 sio slab-off fld-off)
               :int32   (alloc/-sio-read-i32 sio slab-off fld-off)
               :uint32  (bit-and (long (alloc/-sio-read-i32 sio slab-off fld-off)) 0xFFFFFFFF)
               (:obj :array) (alloc/-sio-read-i32 sio slab-off fld-off)
               :float32 (Float/intBitsToFloat (alloc/-sio-read-i32 sio slab-off fld-off))
               :float64 (let [lo (bit-and (long (alloc/-sio-read-i32 sio slab-off fld-off)) 0xFFFFFFFF)
                              hi (bit-and (long (alloc/-sio-read-i32 sio slab-off (+ fld-off 4))) 0xFFFFFFFF)]
                          (Double/longBitsToDouble (bit-or (bit-shift-left hi 32) lo)))))
           not-found))

       clojure.lang.Seqable
       (seq [this]
         (map (fn [k] (clojure.lang.MapEntry/create k (.valAt this k)))
              (:field-keys schema)))

       clojure.lang.IFn
       (invoke [this k] (.valAt this k))
       (invoke [this k not-found] (.valAt this k not-found))

       clojure.lang.IHashEq
       (hasheq [this]
         (if _hash_val
           _hash_val
           (let [h (reduce (fn [h k]
                             (unchecked-int (+ (* 31 h)
                                               (clojure.lang.Util/hasheq
                                                 (clojure.lang.MapEntry/create k (.valAt this k))))))
                           (int 1)
                           (:field-keys schema))]
             (set! _hash_val h)
             h)))

       java.lang.Iterable
       (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

       java.lang.Object
       (toString [this]
         (str "#eve/obj " (into {} (.seq this))))
       (equals [this other]
         (cond
           (identical? this other) true
           (not (instance? JvmObj other)) false
           :else (let [^JvmObj o other]
                   (and (= (:field-keys schema) (:field-keys (.-schema o)))
                        (every? (fn [k] (= (.valAt this k) (.valAt o k)))
                                (:field-keys schema))))))
       (hashCode [this] (.hasheq this)))

     (defmethod print-method JvmObj [^JvmObj o ^java.io.Writer w]
       (.write w (str "#eve/obj " (into {} (.seq o)))))

     (defn jvm-obj-from-offset
       "Construct a JVM JvmObj from a slab-qualified offset of a 0x1E block."
       [sio slab-off]
       (let [slen     (alloc/-sio-read-u16 sio slab-off 2)
             s-raw    (alloc/-sio-read-bytes sio slab-off 4 slen)
             schema-map (let [n  (bit-and (long (aget ^bytes s-raw 0)) 0xFF)]
                          (loop [i 0 pos 1 fm (array-map)]
                            (if (< i n)
                              (let [nlen (bit-and (long (aget ^bytes s-raw pos)) 0xFF)
                                    nm   (String. ^bytes s-raw (int (inc pos)) (int nlen) "UTF-8")
                                    tc   (bit-and (long (aget ^bytes s-raw (+ pos 1 nlen))) 0xFF)]
                                (recur (inc i) (+ pos 2 nlen)
                                       (clojure.core/assoc fm (keyword nm) (obj-code->type-kw tc))))
                              fm)))
             schema   (create-schema schema-map)
             data-off (+ 4 slen)]
         (JvmObj. schema slab-off data-off sio nil)))

     )) ;; end #?(:clj (do ...))
