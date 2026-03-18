(ns eve.array
  "Typed arrays backed by slab memory (ISlabIO).

   Create arrays with the unified constructor:
     (eve-array :int32 10)          ;; 10 zero-filled int32 elements
     (eve-array :float64 10 0.0)    ;; 10 float64 filled with 0.0
     (eve-array :uint8 [1 2 3])     ;; from collection

   Supported types: :int8 :uint8 :int16 :uint16 :int32 :uint32 :float32 :float64

   Integer types (:int8 through :uint32) support full Atomics API (CLJS):
     aget, aset!, cas!, add!, sub!, band!, bor!, bxor!
   Float types (:float32 :float64) support non-atomic aget/aset! only.
   wait!/notify! are :int32 only (CLJS).

   SIMD-accelerated bulk operations (afill-simd!, asum-simd, etc.) are
   available for :int32 arrays when atomicity is not required (CLJS)."
  (:refer-clojure :exclude [aget aset areduce amap])
  (:require
   [clojure.string :as str]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-write-u8! -sio-read-u16 -sio-write-u16!
            -sio-read-i32 -sio-write-i32! -sio-read-bytes -sio-write-bytes!
            -sio-alloc! -sio-free!]]
   [eve.deftype-proto.serialize :as ser]
   [eve.mem :as mem]
   #?@(:cljs [[eve.atom :as atom]
              [eve.wasm-mem :as wasm]
              [eve.deftype-proto.wasm :as proto-wasm]]
       :clj  [[eve.deftype-proto.macros :as eve]]))
  #?(:cljs (:require-macros [eve.deftype-proto.macros :as eve])))

;; Forward declarations
(declare eve-array aget aset!)

;;-----------------------------------------------------------------------------
;; Shared Constants
;;-----------------------------------------------------------------------------

(def ^:const HEADER_SIZE 8) ;; [subtype:u8][pad:3][count:u32LE]

;;-----------------------------------------------------------------------------
;; Shared type metadata lookup
;;-----------------------------------------------------------------------------

(defn subtype->elem-shift
  "Subtype code → log2(bytes-per-element)."
  [code]
  (case (int code)
    (1 2 3) 0   ;; u8, i8, u8-clamped: 1 byte
    (4 5)   1   ;; i16, u16: 2 bytes
    (6 7 8) 2   ;; i32, u32, f32: 4 bytes
    9       3)) ;; f64: 8 bytes

(defn subtype->elem-size
  "Subtype code → bytes-per-element."
  [code]
  (bit-shift-left 1 (subtype->elem-shift code)))

(defn subtype->atomic?
  "True if the subtype supports Atomics (integer types only, not Uint8ClampedArray)."
  [code]
  (and (<= (int code) 7) (not= (int code) 3)))

(defn type-kw->subtype
  "Type keyword → serializer subtype code."
  [kw]
  (case kw
    :uint8         ser/TYPED_ARRAY_UINT8
    :int8          ser/TYPED_ARRAY_INT8
    :uint8-clamped ser/TYPED_ARRAY_UINT8_CLAMPED
    :int16         ser/TYPED_ARRAY_INT16
    :uint16        ser/TYPED_ARRAY_UINT16
    :int32         ser/TYPED_ARRAY_INT32
    :uint32        ser/TYPED_ARRAY_UINT32
    :float32       ser/TYPED_ARRAY_FLOAT32
    :float64       ser/TYPED_ARRAY_FLOAT64
    (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
            (str "Unknown eve-array type: " kw
                 ". Supported: :int8 :uint8 :uint8-clamped :int16 :uint16 :int32 :uint32 :float32 :float64")))))

(defn subtype->type-kw
  "Subtype code → type keyword (for printing)."
  [code]
  (case (int code)
    1 :uint8
    2 :int8
    3 :uint8-clamped
    4 :int16
    5 :uint16
    6 :int32
    7 :uint32
    8 :float32
    9 :float64))

;;-----------------------------------------------------------------------------
;; Portable accessors (declared here, defined after all types)
;;-----------------------------------------------------------------------------
(declare array-subtype-code)

;;=============================================================================
;; Shared implementation — slab-backed via ISlabIO (eve/deftype)
;;=============================================================================

(def ^:const EveArray-type-id 0x1D)

#?(:cljs
(defn- make-typed-view
  "Create a JS typed array view over the entire buffer for a given subtype."
  [buf subtype-code]
  (case subtype-code
    0x01 (js/Uint8Array. buf)
    0x02 (js/Int8Array. buf)
    0x03 (js/Uint8ClampedArray. buf)
    0x04 (js/Int16Array. buf)
    0x05 (js/Uint16Array. buf)
    0x06 (js/Int32Array. buf)
    0x07 (js/Uint32Array. buf)
    0x08 (js/Float32Array. buf)
    0x09 (js/Float64Array. buf))))

;;-----------------------------------------------------------------------------
;; Element read/write through ISlabIO
;;-----------------------------------------------------------------------------

(defn- read-element
  "Read element n from an EveArray block through ISlabIO."
  [sio offset n subtype]
  (let [es (subtype->elem-shift subtype)
        elem-size (bit-shift-left 1 es)
        fld-off (+ HEADER_SIZE (* n elem-size))]
    (case (int subtype)
      (1 3) (-sio-read-u8 sio offset fld-off)
      2     (let [b (-sio-read-u8 sio offset fld-off)]
              (if (> b 127) (- b 256) b))
      4     (let [raw (-sio-read-bytes sio offset fld-off 2)
                  #?@(:cljs [dv (js/DataView. (.-buffer raw) (.-byteOffset raw) 2)]
                      :clj  [bb (doto (java.nio.ByteBuffer/wrap raw)
                                  (.order java.nio.ByteOrder/LITTLE_ENDIAN))])]
              #?(:cljs (.getInt16 dv 0 true)
                 :clj  (long (.getShort bb))))
      5     (-sio-read-u16 sio offset fld-off)
      6     (-sio-read-i32 sio offset fld-off)
      7     #?(:cljs (unsigned-bit-shift-right (-sio-read-i32 sio offset fld-off) 0)
           :clj  (bit-and (long (-sio-read-i32 sio offset fld-off)) 0xFFFFFFFF))
      8     (let [raw (-sio-read-bytes sio offset fld-off 4)
                  #?@(:cljs [dv (js/DataView. (.-buffer raw) (.-byteOffset raw) 4)]
                      :clj  [bb (doto (java.nio.ByteBuffer/wrap raw)
                                  (.order java.nio.ByteOrder/LITTLE_ENDIAN))])]
              #?(:cljs (.getFloat32 dv 0 true)
                 :clj  (double (.getFloat bb))))
      9     (let [raw (-sio-read-bytes sio offset fld-off 8)
                  #?@(:cljs [dv (js/DataView. (.-buffer raw) (.-byteOffset raw) 8)]
                      :clj  [bb (doto (java.nio.ByteBuffer/wrap raw)
                                  (.order java.nio.ByteOrder/LITTLE_ENDIAN))])]
              #?(:cljs (.getFloat64 dv 0 true)
                 :clj  (.getDouble bb))))))

(defn- write-element!
  "Write element n to an EveArray block through ISlabIO."
  [sio offset n subtype val]
  (let [es (subtype->elem-shift subtype)
        elem-size (bit-shift-left 1 es)
        fld-off (+ HEADER_SIZE (* n elem-size))]
    (case (int subtype)
      (1 2 3) (-sio-write-u8! sio offset fld-off (bit-and (int val) 0xFF))
      (4 5)   (-sio-write-u16! sio offset fld-off (bit-and (int val) 0xFFFF))
      (6 7)   (-sio-write-i32! sio offset fld-off (int val))
      8       (let [#?@(:cljs [buf (js/Uint8Array. 4)
                               dv (js/DataView. (.-buffer buf))]
                       :clj  [buf (byte-array 4)
                               bb (doto (java.nio.ByteBuffer/wrap buf)
                                    (.order java.nio.ByteOrder/LITTLE_ENDIAN))])]
                #?(:cljs (.setFloat32 dv 0 val true)
                   :clj  (.putFloat bb (float val)))
                (-sio-write-bytes! sio offset fld-off buf))
      9       (let [#?@(:cljs [buf (js/Uint8Array. 8)
                               dv (js/DataView. (.-buffer buf))]
                       :clj  [buf (byte-array 8)
                               bb (doto (java.nio.ByteBuffer/wrap buf)
                                    (.order java.nio.ByteOrder/LITTLE_ENDIAN))])]
                #?(:cljs (.setFloat64 dv 0 val true)
                   :clj  (.putDouble bb (double val)))
                (-sio-write-bytes! sio offset fld-off buf)))))

;;-----------------------------------------------------------------------------
;; Precondition helpers
;;-----------------------------------------------------------------------------

(defn- require-atomic! [arr op]
  (when-not (.-atomic?__ arr)
    (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
            (str op " requires an integer-typed array (not supported on :float32/:float64)")))))

(defn- require-int32! [arr op]
  (when (not= (.-subtype__ arr) ser/TYPED_ARRAY_INT32)
    (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
            (str op " only supported on :int32 arrays")))))

;;-----------------------------------------------------------------------------
;; Typed-view freshness helper (CLJS only)
;;
;; When the coalesc allocator (class 6) grows, it opens a new mmap region with
;; a new ArrayBuffer.  EveArray instances created before the growth still hold
;; a cached typed-view over the OLD buffer, which may be detached/stale.
;;
;; resolve-live-tv checks: for regular slab classes (0-5) the buffer is fixed,
;; so the cached view is always valid.  For overflow class (6) it re-resolves
;; from the current slab instance.
;;-----------------------------------------------------------------------------

#?(:cljs
(defn- resolve-live-tv
  "Return a valid typed view for the given EveArray fields.
   For regular slab classes the cached view is returned directly.
   For overflow/coalesc class, resolves fresh from the current slab instance."
  [cached-tv offset subtype-code]
  (let [class-idx (alloc/decode-class-idx offset)]
    (if (== class-idx alloc/OVERFLOW_CLASS_IDX)
      (let [inst (proto-wasm/get-slab-instance class-idx)
            buf (.-buffer ^js (:u8 inst))]
        (make-typed-view buf subtype-code))
      cached-tv))))

;;-----------------------------------------------------------------------------
;; EveArray deftype (via eve/deftype — fields: [sio__ offset__])
;;
;; Block layout (type-id 0x1D):
;;   [type-id:u8 @0][subtype:u8 @1][pad:u16 @2-3][cnt:i32 @4-7][data @8+]
;;
;; The macro auto-reads cnt from offset 4 in method bodies.
;; Subtype is in header byte 1, read manually as needed.
;;-----------------------------------------------------------------------------

(eve/deftype EveArray [^:int32 cnt
                      ^:cached subtype__
                      ^:cached elem-shift__
                      ^:cached atomic?__
                      ^:cached ^:mutable hash-cache__
                      #?@(:cljs [^:cached typed-view__
                                 ^:cached data-byte-offset__])]

  clojure.lang.Counted
  (count [_] #?(:cljs cnt :clj (int cnt)))

  clojure.lang.Indexed
  (nth [this n]
    (if (and (>= n 0) (< n cnt))
      #?(:cljs (let [tv (resolve-live-tv typed-view__ offset__ subtype__)
                     idx (+ (unsigned-bit-shift-right data-byte-offset__ elem-shift__) n)]
                 (if atomic?__
                   (js/Atomics.load tv idx)
                   (clojure.core/aget tv idx)))
         :clj  (read-element sio__ offset__ n subtype__))
      (throw (#?(:cljs js/Error. :clj IndexOutOfBoundsException.)
              (str "Index out of bounds: " n " for length " cnt)))))
  (nth [this n not-found]
    (if (and (>= n 0) (< n cnt))
      #?(:cljs (let [tv (resolve-live-tv typed-view__ offset__ subtype__)
                     idx (+ (unsigned-bit-shift-right data-byte-offset__ elem-shift__) n)]
                 (if atomic?__
                   (js/Atomics.load tv idx)
                   (clojure.core/aget tv idx)))
         :clj  (read-element sio__ offset__ n subtype__))
      not-found))

  clojure.lang.ILookup
  (valAt [this k] (nth this #?(:cljs k :clj (int k)) nil))
  (valAt [this k not-found] (nth this #?(:cljs k :clj (int k)) not-found))

  clojure.lang.IFn
  (invoke [this k] (nth this #?(:cljs k :clj (int k))))
  (invoke [this k not-found] (nth this #?(:cljs k :clj (int k)) not-found))

  clojure.lang.Seqable
  (seq [this]
    (when (pos? cnt)
      #?(:cljs (let [tv (resolve-live-tv typed-view__ offset__ subtype__)
                     es elem-shift__ dbo data-byte-offset__ a? atomic?__]
                 (map (fn [i]
                        (let [idx (+ (unsigned-bit-shift-right dbo es) i)]
                          (if a? (js/Atomics.load tv idx) (clojure.core/aget tv idx))))
                      (range cnt)))
         :clj  (let [sub subtype__]
                 (letfn [(arr-seq [i]
                           (when (< i cnt)
                             (lazy-seq (cons (read-element sio__ offset__ i sub) (arr-seq (inc i))))))]
                   (arr-seq 0))))))

  clojure.lang.IMeta
  (meta [_] nil)

  clojure.lang.IObj
  (withMeta [this m] this)

  clojure.lang.IReduce
  (reduce [this f]
    (if (zero? cnt)
      (f)
      #?(:cljs (let [tv (resolve-live-tv typed-view__ offset__ subtype__)
                      es elem-shift__ dbo data-byte-offset__ a? atomic?__
                      base-idx (unsigned-bit-shift-right dbo es)]
                 (loop [i 1
                        acc (if a? (js/Atomics.load tv base-idx) (clojure.core/aget tv base-idx))]
                   (if (< i cnt)
                     (let [idx (+ base-idx i)
                           result (f acc (if a? (js/Atomics.load tv idx) (clojure.core/aget tv idx)))]
                       (if (reduced? result) @result (recur (inc i) result)))
                     acc)))
         :clj  (let [sub subtype__]
                 (loop [i 1 acc (read-element sio__ offset__ 0 sub)]
                   (if (< i cnt)
                     (let [result (f acc (read-element sio__ offset__ i sub))]
                       (if (reduced? result) @result (recur (inc i) result)))
                     acc))))))

  clojure.lang.IReduceInit
  (reduce [this f start]
    #?(:cljs (let [tv (resolve-live-tv typed-view__ offset__ subtype__)
                    es elem-shift__ dbo data-byte-offset__ a? atomic?__
                    base-idx (unsigned-bit-shift-right dbo es)]
               (loop [i 0 acc start]
                 (if (< i cnt)
                   (let [idx (+ base-idx i)
                         result (f acc (if a? (js/Atomics.load tv idx) (clojure.core/aget tv idx)))]
                     (if (reduced? result) @result (recur (inc i) result)))
                   acc)))
       :clj  (let [sub subtype__]
               (loop [i 0 acc start]
                 (if (< i cnt)
                   (let [result (f acc (read-element sio__ offset__ i sub))]
                     (if (reduced? result) @result (recur (inc i) result)))
                   acc)))))

  clojure.lang.IHashEq
  (hasheq [this]
    (if hash-cache__
      hash-cache__
      (let [sub subtype__
            h #?(:cljs (let [tv (resolve-live-tv typed-view__ offset__ subtype__)
                              es elem-shift__ dbo data-byte-offset__ a? atomic?__
                              base-idx (unsigned-bit-shift-right dbo es)]
                         (loop [i 0 h (+ 1 (* 31 sub))]
                           (if (< i cnt)
                             (let [idx (+ base-idx i)
                                   v (if a? (js/Atomics.load tv idx) (clojure.core/aget tv idx))]
                               (recur (inc i) (+ (* 31 h) (hash v))))
                             h)))
                  :clj  (loop [i 0 h (unchecked-int (+ 1 (* 31 sub)))]
                          (if (< i cnt)
                            (recur (inc i) (unchecked-int (+ (* 31 h) (clojure.lang.Util/hasheq (read-element sio__ offset__ i sub)))))
                            h)))]
        (set! hash-cache__ h)
        h)))

  clojure.lang.IPersistentCollection
  (equiv [this other]
    (cond
      (identical? this other) true
      (not (instance? EveArray other)) false
      (not= subtype__ (.-subtype__ ^EveArray other)) false
      (not= cnt (count other)) false
      :else (loop [i 0]
              (if (< i cnt)
                (if (= (nth this i) (nth other i))
                  (recur (inc i))
                  false)
                true))))

  #?@(:cljs [IPrintWithWriter
             (-pr-writer [this writer opts]
                         (-write writer (str "#eve/array " (subtype->type-kw subtype__) " ["))
                         (loop [i 0]
                           (when (< i (min 20 cnt))
                             (when (pos? i) (-write writer " "))
                             (-write writer (str (nth this i)))
                             (recur (inc i))))
                         (when (> cnt 20)
                           (-write writer " ..."))
                         (-write writer "]"))])

  d/IDirectSerialize
  (-direct-serialize [_] offset__)

  d/ISabStorable
  (-sab-tag [_] :eve/array)
  (-sab-encode [_this _s-atom-env]
    #?(:cljs (ser/encode-sab-pointer ser/FAST_TAG_EVE_ARRAY offset__)
       :clj  offset__))
  (-sab-dispose [_ _s-atom-env]
    #?(:cljs (when (and (some? offset__) (not= offset__ alloc/NIL_OFFSET))
               (-sio-free! sio__ offset__))
       :clj  nil))

  d/IsEve
  (-eve? [_] true)

  d/IEveRoot
  (-root-header-off [_] offset__)

  ;; --- CLJ-only interfaces ---
  #?@(:clj
      [java.lang.Iterable
       (iterator [this]
                 (clojure.lang.SeqIterator. (.seq this)))

       java.lang.Object
       (toString [this]
                 (str "#eve/array " (subtype->type-kw subtype__)
                      " " (vec (seq this))))
       (equals [this other]
               (cond
                 (identical? this other) true
                 (not (instance? EveArray other)) false
                 :else (and (== cnt (count other))
                            (== subtype__ (.-subtype__ ^EveArray other))
                            (every? true? (map = (seq this) (seq other))))))
       (hashCode [this] (.hasheq this))

       eve.deftype-proto.data/IBulkAccess
       (-as-double-array [_]
         (when (== subtype__ 9)
           (let [raw (-sio-read-bytes sio__ offset__ 8 (* cnt 8))
                 bb  (doto (java.nio.ByteBuffer/wrap raw)
                       (.order java.nio.ByteOrder/LITTLE_ENDIAN))
                 out (double-array cnt)]
             (.get (.asDoubleBuffer bb) out)
             out)))
       (-as-int-array [_]
         (when (== subtype__ 6)
           (let [raw (-sio-read-bytes sio__ offset__ 8 (* cnt 4))
                 bb  (doto (java.nio.ByteBuffer/wrap raw)
                       (.order java.nio.ByteOrder/LITTLE_ENDIAN))
                 out (int-array cnt)]
             (.get (.asIntBuffer bb) out)
             out)))]))

;;-----------------------------------------------------------------------------
;; Allocation (through ISlabIO — backing-store agnostic)
;;-----------------------------------------------------------------------------

(defn- alloc-eve-region
  "Allocate a slab block for n elements of the given subtype.
   Writes the 8-byte header [type-id:u8][subtype:u8][pad:u16][count:i32].
   Data starts at offset 8 (no alignment padding — DataView handles unaligned).
   Returns the slab-qualified offset."
  [sio subtype n]
  (let [es (subtype->elem-shift subtype)
        elem-size (bit-shift-left 1 es)
        total-size (+ HEADER_SIZE (* n elem-size))
        slab-off (-sio-alloc! sio total-size)]
    (-sio-write-u8!  sio slab-off 0 EveArray-type-id)
    (-sio-write-u8!  sio slab-off 1 subtype)
    (-sio-write-u16! sio slab-off 2 0)
    (-sio-write-i32! sio slab-off 4 n)
    slab-off))

;;-----------------------------------------------------------------------------
;; Cached-field constructor helper
;;-----------------------------------------------------------------------------

#?(:cljs
(defn- resolve-typed-view
  "Create a JS typed array view over the entire backing buffer for a given subtype."
  [sio offset subtype-code]
  (let [class-idx (alloc/decode-class-idx offset)
        inst (proto-wasm/get-slab-instance class-idx)
        buf (.-buffer ^js (:u8 inst))]
    (make-typed-view buf subtype-code))))

#?(:cljs
(defn- resolve-data-byte-offset
  "Compute the absolute byte offset of the data region for a slab-backed array."
  [offset]
  (+ (alloc/slab-offset->byte-offset offset) HEADER_SIZE)))

(defn- make-eve-array-instance
  "Construct an EveArray with all cached fields computed from slab header.
   Reads subtype from slab once, derives elem-shift, atomic?, and (CLJS) typed-view."
  [sio slab-off]
  (let [subtype (-sio-read-u8 sio slab-off 1)
        elem-shift (subtype->elem-shift subtype)
        atomic? (subtype->atomic? subtype)]
    #?(:cljs (let [typed-view (resolve-typed-view sio slab-off subtype)
                   data-byte-offset (resolve-data-byte-offset slab-off)]
               (EveArray. sio slab-off subtype elem-shift atomic? nil typed-view data-byte-offset))
       :clj  (EveArray. sio slab-off subtype elem-shift atomic? nil))))

;;-----------------------------------------------------------------------------
;; Internal constructors
;;-----------------------------------------------------------------------------

(defn- make-eve-array [sio type-kw n init-val]
  (let [subtype (type-kw->subtype type-kw)
        slab-off (alloc-eve-region sio subtype n)
        fill-val (or init-val 0)]
    #?(:cljs
       ;; Fast path: use typed-view .fill() instead of element-by-element writes
       (let [arr (make-eve-array-instance sio slab-off)
             tv  (.-typed-view__ ^EveArray arr)
             es  (.-elem-shift__ ^EveArray arr)
             dbo (.-data-byte-offset__ ^EveArray arr)
             base (unsigned-bit-shift-right dbo es)]
         (.fill tv fill-val base (+ base n))
         arr)
       :clj
       (do
         (dotimes [i n]
           (write-element! sio slab-off i subtype fill-val))
         (make-eve-array-instance sio slab-off)))))

(defn- make-eve-array-from [sio type-kw coll]
  (let [v (vec coll)
        n (count v)]
    (if (zero? n)
      (make-eve-array sio type-kw 0 nil)
      (let [subtype (type-kw->subtype type-kw)
            slab-off (alloc-eve-region sio subtype n)]
        #?(:cljs
           ;; Fast path: bulk-set via typed view instead of element-by-element writes
           (let [arr (make-eve-array-instance sio slab-off)
                 tv  (.-typed-view__ ^EveArray arr)
                 es  (.-elem-shift__ ^EveArray arr)
                 dbo (.-data-byte-offset__ ^EveArray arr)
                 base (unsigned-bit-shift-right dbo es)]
             (dotimes [i n]
               (clojure.core/aset tv (+ base i) (nth v i)))
             arr)
           :clj
           (do
             (dotimes [i n]
               (write-element! sio slab-off i subtype (nth v i)))
             (make-eve-array-instance sio slab-off)))))))

;;-----------------------------------------------------------------------------
;; Unified constructor
;;-----------------------------------------------------------------------------

(defn- default-sio []
  #?(:cljs (alloc/->CljsSlabIO)
     :clj  alloc/*jvm-slab-ctx*))

(defn eve-array
  "Create a typed array backed by slab memory.

     (eve-array :int32 10)          ;; 10 zero-filled int32 elements
     (eve-array :float64 10 0.0)    ;; 10 float64 filled with 0.0
     (eve-array :uint8 [1 2 3])     ;; from collection

   Supported types: :int8 :uint8 :int16 :uint16 :int32 :uint32 :float32 :float64"
  ([type-kw size-or-coll]
   (let [sio (default-sio)]
     (if (number? size-or-coll)
       (make-eve-array sio type-kw (int size-or-coll) nil)
       (make-eve-array-from sio type-kw size-or-coll))))
  ([type-kw n init-val]
   (make-eve-array (default-sio) type-kw (int n) init-val)))

(defn eve-array-uninit
  "Allocate a typed array without zero-filling the data region.
   Use when the caller will immediately overwrite all elements.
   On CLJS, SAB memory is zero-initialized by spec, so this is safe even
   if only partially written."
  [type-kw n]
  (let [sio (default-sio)
        subtype (type-kw->subtype type-kw)
        slab-off (alloc-eve-region sio subtype n)]
    (make-eve-array-instance sio slab-off)))

;;-----------------------------------------------------------------------------
;; Backward-compatible aliases for Int32Array
;;-----------------------------------------------------------------------------

(defn int32-array
  "Create an int32 array. Alias for (eve-array :int32 ...)."
  ([n] (eve-array :int32 n))
  ([n init-val] (eve-array :int32 n init-val)))

(defn int32-array-from
  "Create an int32 array from a collection. Alias for (eve-array :int32 coll)."
  [coll]
  (eve-array :int32 coll))

;;-----------------------------------------------------------------------------
;; Element access (through ISlabIO — backing-store agnostic)
;;-----------------------------------------------------------------------------

(defn aget
  "Read element at index. Uses cached typed-view on CLJS for direct access."
  [^EveArray arr idx]
  (let [cnt (count arr)]
    (when (or (< idx 0) (>= idx cnt))
      (throw (#?(:cljs js/Error. :clj IndexOutOfBoundsException.)
              (str "Index out of bounds: " idx))))
    #?(:cljs (let [tv (resolve-live-tv (.-typed-view__ arr) (.-offset__ arr) (.-subtype__ arr))
                    base-idx (+ (unsigned-bit-shift-right (.-data-byte-offset__ arr) (.-elem-shift__ arr)) idx)]
               (if (.-atomic?__ arr)
                 (js/Atomics.load tv base-idx)
                 (clojure.core/aget tv base-idx)))
       :clj  (read-element (.-sio__ arr) (.-offset__ arr) idx (.-subtype__ arr)))))

(defn aset!
  "Write element at index. Uses cached typed-view on CLJS for direct access. Returns the value written."
  [^EveArray arr idx val]
  (let [cnt (count arr)]
    (when (or (< idx 0) (>= idx cnt))
      (throw (#?(:cljs js/Error. :clj IndexOutOfBoundsException.)
              (str "Index out of bounds: " idx))))
    #?(:cljs (let [tv (resolve-live-tv (.-typed-view__ arr) (.-offset__ arr) (.-subtype__ arr))
                    base-idx (+ (unsigned-bit-shift-right (.-data-byte-offset__ arr) (.-elem-shift__ arr)) idx)]
               (if (.-atomic?__ arr)
                 (js/Atomics.store tv base-idx val)
                 (clojure.core/aset tv base-idx val)))
       :clj  (write-element! (.-sio__ arr) (.-offset__ arr) idx (.-subtype__ arr) val))
    val))

#?(:cljs
(do ;; Begin CLJS-only: atomic ops, wait/notify

;;-----------------------------------------------------------------------------
;; Atomic operations (integer types, through IMemRegion)
;;
;; For int32: direct IMemRegion i32 ops (aligned 4-byte access).
;; For sub-word types (u8, i8, u16, i16, u32): CAS on the containing i32
;; word with bit masking to update only the target bytes.
;;-----------------------------------------------------------------------------

(defn- resolve-element-region
  "Resolve IMemRegion + absolute byte offset for element n."
  [arr idx subtype]
  (let [offset (.-offset__ arr)
        es (subtype->elem-shift subtype)
        elem-size (bit-shift-left 1 es)
        fld-off (+ HEADER_SIZE (* idx elem-size))]
    (alloc/resolve-slab-mem-region offset fld-off)))

(defn- sub-word-mask
  "Return [byte-shift bit-mask] for a sub-word element within its containing i32.
   byte-off is the absolute byte offset of the element."
  [byte-off elem-size]
  (let [byte-in-word (bit-and byte-off 3)   ;; position within the i32 word
        shift (* byte-in-word 8)
        mask (case elem-size
               1 0xFF
               2 0xFFFF)]
    [shift mask]))

(defn- sub-word-cas!
  "CAS loop on an i32 word to atomically update a sub-word element.
   Returns the old sub-word value."
  [region byte-off elem-size expected new-val]
  (let [word-off (bit-and byte-off (bit-not 3))  ;; align to i32 boundary
        [shift mask] (sub-word-mask byte-off elem-size)
        shifted-mask (bit-shift-left mask shift)
        exp-masked (bit-and expected mask)]
    (loop []
      (let [old-word (mem/-load-i32 region word-off)
            old-val (bit-and (unsigned-bit-shift-right old-word shift) mask)]
        (if-not (== old-val exp-masked)
          old-val  ;; expected doesn't match — CAS fails, return witness
          (let [new-word (bit-or (bit-and old-word (bit-not shifted-mask))
                                 (bit-shift-left (bit-and new-val mask) shift))
                prev (mem/-cas-i32! region word-off old-word new-word)]
            (if (== old-word prev)
              old-val  ;; success
              (recur))))))))

(defn- sub-word-exchange!
  "CAS loop on an i32 word to atomically exchange a sub-word element.
   Returns the old sub-word value."
  [region byte-off elem-size new-val]
  (let [word-off (bit-and byte-off (bit-not 3))
        [shift mask] (sub-word-mask byte-off elem-size)
        shifted-mask (bit-shift-left mask shift)]
    (loop []
      (let [old-word (mem/-load-i32 region word-off)
            old-val (bit-and (unsigned-bit-shift-right old-word shift) mask)
            new-word (bit-or (bit-and old-word (bit-not shifted-mask))
                             (bit-shift-left (bit-and new-val mask) shift))
            prev (mem/-cas-i32! region word-off old-word new-word)]
        (if (== old-word prev)
          old-val
          (recur))))))

(defn- sub-word-add!
  "CAS loop on an i32 word to atomically add to a sub-word element.
   Returns the old sub-word value."
  [region byte-off elem-size delta mask-bits]
  (let [word-off (bit-and byte-off (bit-not 3))
        [shift mask] (sub-word-mask byte-off elem-size)
        shifted-mask (bit-shift-left mask shift)]
    (loop []
      (let [old-word (mem/-load-i32 region word-off)
            old-val (bit-and (unsigned-bit-shift-right old-word shift) mask)
            new-val (bit-and (+ old-val delta) mask)
            new-word (bit-or (bit-and old-word (bit-not shifted-mask))
                             (bit-shift-left new-val shift))
            prev (mem/-cas-i32! region word-off old-word new-word)]
        (if (== old-word prev)
          old-val
          (recur))))))

(defn- elem-size-for-subtype [subtype]
  (bit-shift-left 1 (subtype->elem-shift subtype)))

(defn- bounds-check! [arr idx op]
  (let [cnt (count arr)]
    (when (or (< idx 0) (>= idx cnt))
      (throw (#?(:cljs js/Error. :clj IndexOutOfBoundsException.)
              (str op ": index out of bounds: " idx " for length " cnt))))))

(defn cas!
  "Compare-and-swap at index. Returns true if successful.
   Integer types only."
  [^EveArray arr idx expected new-val]
  (require-atomic! arr "cas!")
  (bounds-check! arr idx "cas!")
  (let [subtype (.-subtype__ arr)
        [region byte-off] (resolve-element-region arr idx subtype)
        es (elem-size-for-subtype subtype)]
    (if (== es 4)
      (== expected (mem/-cas-i32! region byte-off expected new-val))
      (== (bit-and expected (if (== es 1) 0xFF 0xFFFF))
          (sub-word-cas! region byte-off es expected new-val)))))

(defn exchange!
  "Atomically replace value at index, returning the old value.
   Integer types only."
  [^EveArray arr idx new-val]
  (require-atomic! arr "exchange!")
  (bounds-check! arr idx "exchange!")
  (let [subtype (.-subtype__ arr)
        [region byte-off] (resolve-element-region arr idx subtype)
        es (elem-size-for-subtype subtype)]
    (if (== es 4)
      (mem/-exchange-i32! region byte-off new-val)
      (sub-word-exchange! region byte-off es new-val))))

(defn add!
  "Atomically add to value at index, returning the old value.
   Integer types only."
  [^EveArray arr idx delta]
  (require-atomic! arr "add!")
  (bounds-check! arr idx "add!")
  (let [subtype (.-subtype__ arr)
        [region byte-off] (resolve-element-region arr idx subtype)
        es (elem-size-for-subtype subtype)]
    (if (== es 4)
      (mem/-add-i32! region byte-off delta)
      (sub-word-add! region byte-off es delta (if (== es 1) 0xFF 0xFFFF)))))

(defn sub!
  "Atomically subtract from value at index, returning the old value.
   Integer types only."
  [^EveArray arr idx delta]
  (require-atomic! arr "sub!")
  (bounds-check! arr idx "sub!")
  (let [subtype (.-subtype__ arr)
        [region byte-off] (resolve-element-region arr idx subtype)
        es (elem-size-for-subtype subtype)]
    (if (== es 4)
      (mem/-sub-i32! region byte-off delta)
      (sub-word-add! region byte-off es (- delta) (if (== es 1) 0xFF 0xFFFF)))))

(defn- atomic-bitop-cas!
  "CAS-loop for bitwise atomic ops through IMemRegion."
  [region byte-off f arg]
  (loop []
    (let [old (mem/-load-i32 region byte-off)
          new-val (f old arg)
          prev (mem/-cas-i32! region byte-off old new-val)]
      (if (== old prev)
        old
        (recur)))))

(defn band!
  "Atomically bitwise-AND value at index, returning the old value.
   :int32 arrays only."
  [^EveArray arr idx mask]
  (require-int32! arr "band!")
  (bounds-check! arr idx "band!")
  (let [[region byte-off] (resolve-element-region arr idx ser/TYPED_ARRAY_INT32)]
    (atomic-bitop-cas! region byte-off bit-and mask)))

(defn bor!
  "Atomically bitwise-OR value at index, returning the old value.
   :int32 arrays only."
  [^EveArray arr idx mask]
  (require-int32! arr "bor!")
  (bounds-check! arr idx "bor!")
  (let [[region byte-off] (resolve-element-region arr idx ser/TYPED_ARRAY_INT32)]
    (atomic-bitop-cas! region byte-off bit-or mask)))

(defn bxor!
  "Atomically bitwise-XOR value at index, returning the old value.
   :int32 arrays only."
  [^EveArray arr idx mask]
  (require-int32! arr "bxor!")
  (bounds-check! arr idx "bxor!")
  (let [[region byte-off] (resolve-element-region arr idx ser/TYPED_ARRAY_INT32)]
    (atomic-bitop-cas! region byte-off bit-xor mask)))

;;-----------------------------------------------------------------------------
;; Wait/Notify (:int32 only, through IMemRegion)
;;-----------------------------------------------------------------------------

(defn wait!
  "Block until the value at index is not equal to `expected`, or until timeout.
   Returns :ok, :not-equal, or :timed-out.
   :int32 arrays only."
  ([^EveArray arr idx expected]
   (wait! arr idx expected ##Inf))
  ([^EveArray arr idx expected timeout-ms]
   (require-int32! arr "wait!")
   (bounds-check! arr idx "wait!")
   (let [[region byte-off] (resolve-element-region arr idx (.-subtype__ arr))]
     (mem/-wait-i32! region byte-off expected timeout-ms))))

(defn wait-async
  "Async version of wait!. Returns a promise that resolves to :ok, :not-equal, or :timed-out.
   :int32 arrays only.
   Note: falls back to IMemRegion wait which may not support async on all backends."
  ([^EveArray arr idx expected]
   (wait-async arr idx expected ##Inf))
  ([^EveArray arr idx expected timeout-ms]
   (require-int32! arr "wait-async")
   (bounds-check! arr idx "wait-async")
   (let [[region byte-off] (resolve-element-region arr idx (.-subtype__ arr))]
     (js/Promise.resolve (mem/-wait-i32! region byte-off expected timeout-ms)))))

(defn notify!
  "Wake up waiting agents on the value at index.
   count defaults to ##Inf (wake all waiters).
   Returns the number of agents woken.
   :int32 arrays only."
  ([^EveArray arr idx]
   (notify! arr idx ##Inf))
  ([^EveArray arr idx n]
   (require-int32! arr "notify!")
   (bounds-check! arr idx "notify!")
   (let [[region byte-off] (resolve-element-region arr idx (.-subtype__ arr))]
     (mem/-notify-i32! region byte-off n))))

)) ;; end CLJS-only: atomic ops, wait/notify

;;-----------------------------------------------------------------------------
;; Functional operations
;;-----------------------------------------------------------------------------

(defn areduce
  "Reduce over array elements with index.
   f is (fn [acc idx val] ...).
   Returns the final accumulated value."
  [^EveArray arr init f]
  (let [len (count arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (let [result (f acc i (aget arr i))]
          (if (reduced? result)
            @result
            (recur (inc i) result)))
        acc))))

(defn amap
  "Map f over array indices, returning a new array of the same type.
   f is (fn [idx current-val] ...) and must return a value of the right type."
  [^EveArray arr f]
  (let [len (count arr)
        type-kw (subtype->type-kw (.-subtype__ arr))
        result (eve-array type-kw len)]
    (dotimes [i len]
      (aset! result i (f i (aget arr i))))
    result))

(defn amap!
  "Map f over array indices in-place, mutating the array.
   f is (fn [idx current-val] ...).
   Returns the array."
  [^EveArray arr f]
  (let [len (count arr)]
    (dotimes [i len]
      (aset! arr i (f i (aget arr i))))
    arr))

(defn afill!
  "Fill array with value, optionally in range [start, end).
   Returns the array."
  ([^EveArray arr val]
   (afill! arr val 0 (count arr)))
  ([^EveArray arr val start]
   (afill! arr val start (count arr)))
  ([^EveArray arr val start end]
   (let [len (count arr)
         end (min end len)]
     (loop [i start]
       (when (< i end)
         (aset! arr i val)
         (recur (inc i))))
     arr)))

(defn acopy!
  "Copy elements from src to dest array.
   Both arrays must be the same type.
   src-start, dest-start default to 0.
   length defaults to min of remaining space.
   Returns dest array."
  ([dest src]
   (acopy! dest 0 src 0 (min (count dest) (count src))))
  ([dest dest-start src src-start len]
   (dotimes [i len]
     (aset! dest (+ dest-start i) (aget src (+ src-start i))))
   dest))

(defn array-type
  "Get the type keyword for this array (:int32, :float64, etc.)."
  [^EveArray arr]
  (subtype->type-kw (.-subtype__ ^EveArray arr)))

(defn retire!
  "Mark this array's slab block as retired for GC.
   Call when replacing this array with a new version."
  [^EveArray arr]
  (let [slab-off (.-offset__ arr)]
    (when (and (some? slab-off) (not= slab-off alloc/NIL_OFFSET))
      (-sio-free! (.-sio__ arr) slab-off))))

#?(:cljs
(do ;; Begin CLJS-only: low-level access, SIMD, typed array conversion, serialization

;;-----------------------------------------------------------------------------
;; Low-level access (resolve dynamically from sio__/offset__)
;;-----------------------------------------------------------------------------

(defn get-sab
  "Get the underlying buffer (SAB or mmap-backed ArrayBuffer).
   Resolves dynamically from the slab offset."
  [^EveArray arr]
  (let [class-idx (alloc/decode-class-idx (.-offset__ arr))
        inst (proto-wasm/get-slab-instance class-idx)]
    (.-buffer ^js (:u8 inst))))

(defn get-offset
  "Get the absolute byte offset of the data region in the backing buffer."
  [^EveArray arr]
  (+ (alloc/slab-offset->byte-offset (.-offset__ arr)) HEADER_SIZE))

(defn get-typed-view
  "Get a raw JS typed array view of this array's data region.
   Always resolves from the current slab instance to handle coalesc buffer growth.
   Useful for bulk operations. Handle with care."
  [^EveArray arr]
  (let [offset (.-offset__ arr)
        class-idx (alloc/decode-class-idx offset)
        inst (proto-wasm/get-slab-instance class-idx)
        buf (.-buffer ^js (:u8 inst))
        tv (make-typed-view buf (.-subtype__ arr))
        es (.-elem-shift__ arr)
        dbo (.-data-byte-offset__ arr)
        cnt (count arr)
        base (unsigned-bit-shift-right dbo es)]
    (.subarray tv base (+ base cnt))))

(defn get-int32-view
  "Get a raw Int32Array view of the data region.
   Only valid for :int32 arrays."
  [^EveArray arr]
  (let [cnt (count arr)
        buf (get-sab arr)
        data-off (.-data-byte-offset__ arr)]
    (js/Int32Array. buf data-off cnt)))

(defn get-descriptor-idx
  "Get the block descriptor index for this array.
   Deprecated: returns -1 (descriptors are no longer tracked per-array)."
  [^EveArray _arr]
  -1)

;;-----------------------------------------------------------------------------
;; SIMD-Accelerated Operations (:int32 only)
;;
;; These operations use WASM SIMD for bulk processing (4 elements at a time).
;; They do NOT use Atomics and should only be used when:
;; - The array is not yet shared with other threads
;; - The array is read-only in the current context
;; - You have other synchronization in place
;;-----------------------------------------------------------------------------

(defn- array-data-byte-offset
  "Get the absolute byte offset of element `start` in an i32 array's backing buffer."
  [^EveArray arr start]
  (+ (alloc/slab-offset->byte-offset (.-offset__ arr)) HEADER_SIZE (* start 4)))

(defn afill-simd!
  "Fill :int32 array with value using SIMD (4 elements at a time).
   WARNING: Does not use Atomics. Only use before sharing with other threads."
  ([^EveArray arr val]
   (afill-simd! arr val 0 (count arr)))
  ([^EveArray arr val start end]
   (require-int32! arr "afill-simd!")
   (let [byte-start (array-data-byte-offset arr start)
         count (- end start)]
     (when (and (wasm/ready?) (pos? count))
       (wasm/simd-fill-i32! byte-start count val))
     arr)))

(defn acopy-simd!
  "Copy elements between :int32 arrays using SIMD (4 elements at a time).
   WARNING: Does not use Atomics. Only use before sharing with other threads."
  ([dest src]
   (acopy-simd! dest 0 src 0 (min (count dest) (count src))))
  ([dest dest-start src src-start len]
   (require-int32! dest "acopy-simd!")
   (require-int32! src "acopy-simd!")
   (when (and (wasm/ready?) (pos? len))
     (let [dest-byte-offset (array-data-byte-offset dest dest-start)
           src-byte-offset (array-data-byte-offset src src-start)]
       (wasm/simd-copy-i32! dest-byte-offset src-byte-offset len)))
   dest))

(defn asum-simd
  "Sum all elements in :int32 array using SIMD.
   Safe to use on shared arrays (read-only)."
  ([^EveArray arr]
   (asum-simd arr 0 (count arr)))
  ([^EveArray arr start end]
   (require-int32! arr "asum-simd")
   (let [byte-start (array-data-byte-offset arr start)
         count (- end start)]
     (if (and (wasm/ready?) (pos? count))
       (wasm/simd-sum-i32 byte-start count)
       0))))

(defn amin-simd
  "Find minimum value in :int32 array using SIMD.
   Safe to use on shared arrays (read-only).
   Returns INT32_MAX for empty arrays."
  ([^EveArray arr]
   (amin-simd arr 0 (count arr)))
  ([^EveArray arr start end]
   (require-int32! arr "amin-simd")
   (let [byte-start (array-data-byte-offset arr start)
         count (- end start)]
     (if (and (wasm/ready?) (pos? count))
       (wasm/simd-min-i32 byte-start count)
       2147483647))))

(defn amax-simd
  "Find maximum value in :int32 array using SIMD.
   Safe to use on shared arrays (read-only).
   Returns INT32_MIN for empty arrays."
  ([^EveArray arr]
   (amax-simd arr 0 (count arr)))
  ([^EveArray arr start end]
   (require-int32! arr "amax-simd")
   (let [byte-start (array-data-byte-offset arr start)
         count (- end start)]
     (if (and (wasm/ready?) (pos? count))
       (wasm/simd-max-i32 byte-start count)
       -2147483648))))

(defn aequal-simd?
  "Compare two :int32 arrays for equality using SIMD.
   Safe to use on shared arrays (read-only)."
  [^EveArray arr1 ^EveArray arr2]
  (require-int32! arr1 "aequal-simd?")
  (require-int32! arr2 "aequal-simd?")
  (let [len1 (count arr1)
        len2 (count arr2)]
    (if (not= len1 len2)
      false
      (if (and (wasm/ready?) (pos? len1))
        (wasm/simd-eq-i32? (array-data-byte-offset arr1 0) (array-data-byte-offset arr2 0) len1)
        (zero? len1)))))

;;-----------------------------------------------------------------------------
;; Native typed array → EveArray conversion
;;-----------------------------------------------------------------------------

(defn from-typed-array
  "Create an EveArray from a native JS typed array.
   Copies the data into slab memory through ISlabIO.
   Supports all standard typed array types including Uint8ClampedArray."
  [elem]
  (let [sio (alloc/->CljsSlabIO)
        subtype (ser/typed-array-subtype elem)
        n (.-length elem)
        slab-off (alloc-eve-region sio subtype n)
        ;; Bulk copy element bytes into the data region
        byte-view (js/Uint8Array. (.-buffer elem) (.-byteOffset elem) (.-byteLength elem))]
    (-sio-write-bytes! sio slab-off HEADER_SIZE byte-view)
    (make-eve-array-instance sio slab-off)))

;;-----------------------------------------------------------------------------
;; Typed array encoder registration (called by serializer for native typed arrays)
;;-----------------------------------------------------------------------------

(ser/set-typed-array-encoder!
  (fn [elem]
    (let [subtype (ser/typed-array-subtype elem)]
      (if (and subtype (<= subtype 0x0B))
        ;; All typed arrays → allocate SAB block, return TYPED_ARRAY pointer.
        ;; This preserves the typed array identity on deref (vs converting to EveArray).
        ;;
        ;; Block format (16-byte header for SIMD alignment):
        ;;   [subtype:u8][reserved:7 bytes][byte-len:u32LE][reserved:4 bytes][data at +16]
        ;; Data starts at offset+16 for 16-byte alignment (enables SIMD).
        ;; We over-allocate by up to 15 bytes to ensure the header starts at
        ;; a 16-byte aligned position within the allocated block.
        (let [byte-view (js/Uint8Array. (.-buffer elem) (.-byteOffset elem) (.-byteLength elem))
              byte-len (.-byteLength elem)
              header-size 16
              ;; Over-allocate by 15 to guarantee 16-byte alignment headroom:
              ;; worst case raw_offset % 16 == 1 → shift 15 bytes to align.
              alloc-size (+ header-size byte-len 15)
              ;; Allocate from slab (available when parent-atom or global is set)
              has-slab? (or (some? d/*parent-atom*) (some? atom/*global-atom-instance*))
              alloc-result (when has-slab?
                             (let [r (alloc/alloc alloc-size)]
                               (when-not (:error r) r)))]
          (if alloc-result
            ;; Slab-backed: write block at aligned position, return 7-byte pointer
            (let [slab-off (:offset alloc-result)
                  base (alloc/resolve-dv! slab-off)
                  dv alloc/resolved-dv
                  ;; Align header to 16-byte boundary
                  aligned-offset (bit-and (+ base 15) (bit-not 15))
                  class-idx (alloc/decode-class-idx slab-off)
                  inst (proto-wasm/get-slab-instance class-idx)
                  u8 (:u8 inst)]
              ;; Write header: [subtype:u8][reserved:7][byte-len:u32LE][reserved:4]
              (.setUint8 dv aligned-offset subtype)
              ;; reserved bytes 1-7 are implicitly zero from allocation
              (.setUint32 dv (+ aligned-offset 8) byte-len true)
              ;; reserved bytes 12-15 are implicitly zero
              ;; Write data at offset+16
              (.set u8 byte-view (+ aligned-offset 16))
              ;; Return 7-byte pointer with slab-qualified offset
              (ser/encode-sab-pointer ser/FAST_TAG_TYPED_ARRAY slab-off))
            ;; Fallback for pre-init: inline blob (old 8-byte format for compatibility)
            (let [buf (js/Uint8Array. (+ 8 byte-len))
                  dv (js/DataView. (.-buffer buf))]
              (cljs.core/aset buf 0 d/DIRECT_MAGIC_0)
              (cljs.core/aset buf 1 d/DIRECT_MAGIC_1)
              (cljs.core/aset buf 2 ser/FAST_TAG_TYPED_ARRAY)
              (cljs.core/aset buf 3 subtype)
              (.setUint32 dv 4 byte-len true)
              (.set buf byte-view 8)
              buf)))
        ;; Unsupported type
        (js/Uint8Array. 0)))))

;;-----------------------------------------------------------------------------
;; Slab-backed atom root registration (mmap-atom)
;;
;; Allows EveArray to be stored as an atom root value via the slab allocator.
;; Block layout (type-id 0x1D):
;;   [0x1D : u8][subtype : u8][pad : u16][count : i32][element-bytes...]
;; Max array: (1024 - 8) / elem-size elements (capped by slab block size).
;;-----------------------------------------------------------------------------

(defn- eve-array-from-header
  "Reconstruct an EveArray from an existing 0x1D header offset."
  [sio header-off]
  (make-eve-array-instance sio header-off))

(defn- dispose-eve-array!
  "Free an EveArray's slab block."
  [arr]
  (let [off (.-offset__ arr)]
    (when (and (some? off) (not= off alloc/NIL_OFFSET))
      (alloc/free! off))))

;; Builder: EveArray → slab block (type-id 0x1D)
(ser/register-cljs-to-sab-builder!
  (fn [v] (instance? EveArray v))
  (fn [^EveArray arr]
    (let [sio (.-sio__ arr)
          offset (.-offset__ arr)
          subtype (.-subtype__ arr)
          n (count arr)
          es (.-elem-shift__ arr)
          byte-len (bit-shift-left n es)
          blk-off (alloc/alloc-offset (+ HEADER_SIZE byte-len))]
      (alloc/write-u8!  blk-off 0 ser/EVE_ARRAY_SLAB_TYPE_ID)
      (alloc/write-u8!  blk-off 1 subtype)
      (alloc/write-u16! blk-off 2 0)
      (alloc/write-i32! blk-off 4 n)
      (when (pos? byte-len)
        (let [src-bytes (-sio-read-bytes sio offset HEADER_SIZE byte-len)]
          (alloc/write-bytes! blk-off 8 src-bytes)))
      (reify d/IEveRoot
        (-root-header-off [_] blk-off)))))

;; Header constructor: slab block (0x1D) → EveArray
(ser/register-header-constructor!
  ser/EVE_ARRAY_SLAB_TYPE_ID
  (fn [_sab blk-off]
    (make-eve-array-instance (alloc/->CljsSlabIO) blk-off)))

;; Disposer: free the single slab block (element data is embedded, no sub-blocks)
(ser/register-header-disposer!
  ser/EVE_ARRAY_SLAB_TYPE_ID
  (fn [blk-off] (alloc/free! blk-off)))

;;-----------------------------------------------------------------------------
;; Constructor registration for SAB pointer deserialization (FAST_TAG 0x1C)
;;
;; Old SAB blocks have [subtype:u8 @0][pad @1-3][count:u32 @4-7][data @8+].
;; Convert header in-place to 0x1D format: [type-id @0][subtype @1][pad @2-3].
;;-----------------------------------------------------------------------------

(ser/register-sab-type-constructor!
  ser/FAST_TAG_EVE_ARRAY
  (fn [_sab slab-off]
    ;; Handle both old and new block formats:
    ;; Old (0x1C): [subtype:u8 @0][pad @1-3][count:u32 @4-7][data @8+]
    ;; New (0x1D): [type-id:u8 @0][subtype:u8 @1][pad:u16 @2-3][count:i32 @4-7][data @8]
    ;; Subtype codes are 1-9; type-id 0x1D (29) is outside that range.
    (let [byte0 (alloc/read-u8 slab-off 0)]
      (when (<= byte0 9)
        ;; Old format: convert header in-place
        (alloc/write-u8! slab-off 0 EveArray-type-id)
        (alloc/write-u8! slab-off 1 byte0)))
    (make-eve-array-instance (alloc/->CljsSlabIO) slab-off)))

)) ;; end CLJS-only: low-level access, SIMD, typed array, serialization

;;=============================================================================
;; ISabRetirable — lets atom swap retirement free old EveArray slab blocks
;;=============================================================================

#?(:bb nil
   :default
(extend-type EveArray
  d/ISabRetirable
  (-sab-retire-diff! [this new-value _slab-env mode]
    (let [old-off (#?(:cljs .-offset__ :clj .offset__) this)]
      ;; If the new value is an EveArray at the same offset, no-op (shared block)
      (when-not (and (instance? EveArray new-value)
                     (== old-off (#?(:cljs .-offset__ :clj .offset__) new-value)))
        ;; Free the old block
        (when (and (some? old-off) (not= old-off alloc/NIL_OFFSET))
          (-sio-free! (#?(:cljs .-sio__ :clj .sio__) this) old-off)))))))

;;=============================================================================
;; JVM-only: print-method, JvmHeapEveArray, from-* helpers, JVM registrations
;;=============================================================================

#?(:clj
   (do

     ;; print-method for the unified EveArray on JVM
     (defmethod print-method EveArray [^EveArray a ^java.io.Writer w]
       (.write w (str "#eve/array " (subtype->type-kw (.-subtype__ a)) " "))
       (print-method (vec (seq a)) w))

     (defn eve-array-from-offset
       "Construct an EveArray from a slab-qualified offset of a 0x1D block."
       [sio slab-off]
       (make-eve-array-instance sio slab-off))

;;-----------------------------------------------------------------------------
;; JVM heap-backed EveArray (no slab required)
;;-----------------------------------------------------------------------------

     (deftype JvmHeapEveArray [^long cnt
                                ^long subtype-code
                                backing         ;; Java array (int[], double[], etc.)
                                ^:unsynchronized-mutable _hash_val]

       clojure.lang.Counted
       (count [_] (int cnt))

       clojure.lang.Indexed
       (nth [this i]
         (if (and (>= i 0) (< i cnt))
           (case (int subtype-code)
             (1 3) (bit-and (long (clojure.core/aget ^bytes backing i)) 0xFF)
             2     (long (clojure.core/aget ^bytes backing i))
             4     (long (clojure.core/aget ^shorts backing i))
             5     (bit-and (long (clojure.core/aget ^shorts backing i)) 0xFFFF)
             6     (long (clojure.core/aget ^ints backing i))
             7     (bit-and (long (clojure.core/aget ^ints backing i)) 0xFFFFFFFF)
             8     (double (clojure.core/aget ^floats backing i))
             9     (clojure.core/aget ^doubles backing i))
           (throw (IndexOutOfBoundsException. (str "Index " i " out of bounds for length " cnt)))))
       (nth [this i not-found]
         (if (and (>= i 0) (< i cnt))
           (.nth this i)
           not-found))

       clojure.lang.ILookup
       (valAt [this k] (.nth this (int k)))
       (valAt [this k not-found] (.nth this (int k) not-found))

       clojure.lang.Seqable
       (seq [this]
         (when (pos? cnt)
           (letfn [(arr-seq [i]
                     (when (< i cnt)
                       (lazy-seq (cons (.nth this i) (arr-seq (inc i))))))]
             (arr-seq 0))))

       clojure.lang.IReduce
       (reduce [this f]
         (if (zero? cnt)
           (f)
           (loop [i 1 acc (.nth this 0)]
             (if (or (>= i cnt) (reduced? acc))
               (unreduced acc)
               (recur (inc i) (f acc (.nth this i)))))))

       clojure.lang.IReduceInit
       (reduce [this f init]
         (loop [i 0 acc init]
           (if (or (>= i cnt) (reduced? acc))
             (unreduced acc)
             (recur (inc i) (f acc (.nth this i))))))

       clojure.lang.IFn
       (invoke [this i] (.nth this (int i)))
       (invoke [this i not-found] (.nth this (int i) not-found))

       java.lang.Iterable
       (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

       clojure.lang.IHashEq
       (hasheq [this]
         (if _hash_val
           _hash_val
           (let [h (loop [i 0 h (int (+ 1 (* 31 subtype-code)))]
                     (if (< i cnt)
                       (recur (inc i) (unchecked-int (+ (* 31 h) (clojure.lang.Util/hasheq (.nth this i)))))
                       h))]
             (set! _hash_val h)
             h)))

       java.lang.Object
       (toString [this]
         (str "#eve/array " (subtype->type-kw subtype-code) " " (vec (seq this))))
       (equals [this other]
         (cond
           (identical? this other) true
           (not (or (instance? JvmHeapEveArray other)
                    (instance? EveArray other))) false
           :else (and (== cnt (count other))
                      (== subtype-code (.-subtype-code ^JvmHeapEveArray other))
                      (every? true? (map = (seq this) (seq other))))))
       (hashCode [this] (.hasheq this))

       eve.deftype-proto.data/IBackingArray
       (-backing-array [_] backing)

       eve.deftype-proto.data/IBulkAccess
       (-as-double-array [_]
         (when (== subtype-code 9) backing))
       (-as-int-array [_]
         (when (== subtype-code 6) backing)))

     (defmethod print-method JvmHeapEveArray [^JvmHeapEveArray a ^java.io.Writer w]
       (.write w (str "#eve/array " (subtype->type-kw (.-subtype-code a)) " "))
       (print-method (vec (seq a)) w))

     (defn- make-jvm-backing-array
       "Create a Java array of the right type for subtype-code."
       [subtype-code ^long n]
       (case (int subtype-code)
         (1 2 3) (byte-array n)
         (4 5)   (short-array n)
         (6 7)   (int-array n)
         8       (float-array n)
         9       (double-array n)))

     (defn- jvm-heap-aset!
       "Write a value into a JvmHeapEveArray's backing array."
       [^JvmHeapEveArray arr ^long idx val]
       (let [backing (.-backing arr)
             sc (.-subtype-code arr)]
         (case (int sc)
           (1 2 3) (clojure.core/aset ^bytes backing (int idx) (byte (int val)))
           (4 5)   (clojure.core/aset ^shorts backing (int idx) (short (int val)))
           (6 7)   (clojure.core/aset ^ints backing (int idx) (int val))
           8       (clojure.core/aset ^floats backing (int idx) (float (double val)))
           9       (clojure.core/aset ^doubles backing (int idx) (double val)))
         val))

     (defn eve-array
       "Create a heap-backed typed array on JVM.
        (eve-array :int32 10)          ;; 10 zero-filled
        (eve-array :float64 10 0.0)    ;; 10 filled with 0.0
        (eve-array :uint8 [1 2 3])     ;; from collection"
       ([type-kw size-or-coll]
        (if (number? size-or-coll)
          (eve-array type-kw (int size-or-coll) nil)
          (let [sc (type-kw->subtype type-kw)
                coll (vec size-or-coll)
                n (count coll)
                backing (make-jvm-backing-array sc n)
                arr (JvmHeapEveArray. n sc backing nil)]
            (dotimes [i n]
              (jvm-heap-aset! arr i (nth coll i)))
            arr)))
       ([type-kw n init-val]
        (let [sc (type-kw->subtype type-kw)
              backing (make-jvm-backing-array sc (int n))
              arr (JvmHeapEveArray. (long n) sc backing nil)]
          (when init-val
            (dotimes [i n]
              (jvm-heap-aset! arr i init-val)))
          arr)))

     (defn aset!
       "Write element at index in a heap-backed EveArray."
       [arr idx val]
       (jvm-heap-aset! arr idx val))

     (defn from-double-array
       "Wrap a double[] directly as a JvmHeapEveArray. Zero-copy."
       ^JvmHeapEveArray [^doubles arr]
       (JvmHeapEveArray. (alength arr) 9 arr nil))

     (defn from-int-array
       "Wrap an int[] directly as a JvmHeapEveArray. Zero-copy."
       ^JvmHeapEveArray [^ints arr]
       (JvmHeapEveArray. (alength arr) 6 arr nil))

     (defn from-byte-array
       "Wrap a byte[] directly as a JvmHeapEveArray with :uint8 subtype. Zero-copy."
       ^JvmHeapEveArray [^bytes arr]
       (JvmHeapEveArray. (alength arr) 1 arr nil))

     ;; Register JVM type constructor for EveArray slab pointer tags.
     ;; 0x1D = EVE_ARRAY_SLAB_TYPE_ID — inline slab block pointer (from value+sio->eve-bytes)
     ;; 0x1C = FAST_TAG_EVE_ARRAY — SAB-style pointer tag (from CLJS, cross-compat)
     (ser/register-jvm-type-constructor!
       ser/EVE_ARRAY_SLAB_TYPE_ID  ;; 0x1D
       (fn [header-off]
         (eve-array-from-offset alloc/*jvm-slab-ctx* header-off)))

     (ser/register-jvm-type-constructor!
       ser/FAST_TAG_EVE_ARRAY  ;; 0x1C
       (fn [header-off]
         (eve-array-from-offset alloc/*jvm-slab-ctx* header-off)))

     )) ;; end #?(:clj (do ...))

;;-----------------------------------------------------------------------------
;; Portable accessors (after all types are defined)
;;-----------------------------------------------------------------------------

(defn array-subtype-code
  "Return the subtype code of an EveArray (portable across CLJS and JVM).
   Works for both slab-backed EveArray and heap-backed JvmHeapEveArray."
  [arr]
  #?(:cljs (.-subtype__ ^EveArray arr)
     :clj  (if (instance? EveArray arr)
              (.-subtype__ ^EveArray arr)
              (.-subtype-code arr))))
