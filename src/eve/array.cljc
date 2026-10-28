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
  (:refer-clojure :exclude [aget aset areduce amap
                            alength byte-array short-array int-array float-array
                            double-array unchecked-byte unchecked-short unchecked-int
                            unsigned-bit-shift-right long double transient persistent! conj!
                            bytes shorts ints floats doubles locking])
  (:require
   [eve.host :refer [alength byte-array short-array int-array float-array double-array
                     unchecked-byte unchecked-short unchecked-int unsigned-bit-shift-right
                     long double transient persistent! conj! bytes shorts ints floats
                     doubles locking defdelay]]
   [clojure.string :as str]
   [eve.core :as e]
   [eve.deftype-proto.data :as d]
   [eve.encoding :as enc]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.serialize :as ser]
   [eve.codec :as codec]
   [eve.mem :as mem]
   [eve.deftype-proto.macros :as eve]
   [eve.deftype :as edt]))

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
    (throw (enc/illegal-argument
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
      (1 3) (slab/read-u8 sio offset fld-off)
      2     (let [b (slab/read-u8 sio offset fld-off)]
              (if (> b 127) (- b 256) b))
      4     (let [u (slab/read-u16 sio offset fld-off)]
              (if (> u 32767) (- u 65536) u))
      5     (slab/read-u16 sio offset fld-off)
      6     (slab/read-i32 sio offset fld-off)
      7     (let [x (slab/read-i32 sio offset fld-off)]
              (if (neg? x) (+ x 4294967296) x))
      8     (enc/read-f32-le (slab/read-bytes sio offset fld-off 4) 0)
      9     (enc/read-f64-le (slab/read-bytes sio offset fld-off 8) 0))))

(defn- write-element!
  "Write element n to an EveArray block through ISlabIO."
  [sio offset n subtype val]
  (let [es (subtype->elem-shift subtype)
        elem-size (bit-shift-left 1 es)
        fld-off (+ HEADER_SIZE (* n elem-size))]
    (case (int subtype)
      (1 2 3) (slab/write-u8! sio offset fld-off (bit-and (int val) 0xFF))
      (4 5)   (slab/write-u16! sio offset fld-off (bit-and (int val) 0xFFFF))
      ;; a :uint32 above 2^31-1 is its two's-complement word
      (6 7)   (slab/write-i32! sio offset fld-off
                               (let [x (long val)] (if (> x 2147483647) (- x 4294967296) x)))
      8       (slab/write-bytes! sio offset fld-off (enc/f32-le-bytes val))
      9       (slab/write-bytes! sio offset fld-off (enc/f64-le-bytes val)))))

(defn- elem-value
  "An integer element's value from its bits: x is the masked byte / u16 of a
   sub-word element, the i32 word of a 4-byte one."
  [subtype x]
  (case (int subtype)
    2 (if (> x 127) (- x 256) x)
    4 (if (> x 32767) (- x 65536) x)
    7 (if (neg? x) (+ x 4294967296) x)
    x))

(defn- update-element!
  "Atomically replace integer element n with (f old) by a CAS loop on the i32
   word that holds it (a sub-word element is masked in place). Answers old."
  [sio offset n subtype f]
  (let [es (subtype->elem-size subtype)
        fld (+ HEADER_SIZE (* n es))]
    (if (== es 4)
      (loop []
        (let [w (slab/read-i32 sio offset fld)
              old (elem-value subtype w)
              nw (unchecked-int (long (f old)))]
          (if (== w (slab/cas-i32! sio offset fld w nw)) old (recur))))
      (let [wfld (bit-and fld (bit-not 3))
            shift (* 8 (bit-and fld 3))
            mask (if (== es 1) 0xFF 0xFFFF)
            smask (bit-shift-left mask shift)]
        (loop []
          (let [w (slab/read-i32 sio offset wfld)
                old (elem-value subtype (bit-and (unsigned-bit-shift-right w shift) mask))
                nw (unchecked-int (bit-or (bit-and w (bit-not smask))
                                          (bit-shift-left (bit-and (long (f old)) mask) shift)))]
            (if (== w (slab/cas-i32! sio offset wfld w nw)) old (recur))))))))

(defn- fill-elements!
  "Write val into elements [0, n): element 0 written, then copied over the
   rest in doubling runs."
  [sio offset subtype n val]
  (when (pos? n)
    (write-element! sio offset 0 subtype val)
    (let [total (* n (subtype->elem-size subtype))]
      (loop [have (subtype->elem-size subtype)]
        (when (< have total)
          (let [len (min have (- total have))]
            (slab/copy-block! sio offset (+ HEADER_SIZE have) offset HEADER_SIZE len)
            (recur (+ have len))))))))

;;-----------------------------------------------------------------------------
;; IEveArray — how array fns reach an array, on every host and representation
;;-----------------------------------------------------------------------------

(defprotocol IEveArray
  "An array's element subtype and element access, answered by the slab-backed
   EveArray and the heap-backed HeapEveArray alike. Array fns reach an
   array through this, d/-eve-sio and d/-root-header-off — never through its
   fields, which bb's EveArray (a reify) does not have."
  (-subtype [arr] "The element subtype code (1-9).")
  (-aget [arr i] "Element i (the caller checked bounds).")
  (-aset! [arr i v] "Write element i (the caller checked bounds).")
  (-update-elem! [arr i f]
    "Atomically replace element i (an integer subtype) with (f old); answers
     old (the caller checked bounds and subtype)."))

;;-----------------------------------------------------------------------------
;; Precondition helpers
;;-----------------------------------------------------------------------------

(defn- require-atomic! [arr op]
  (when-not (subtype->atomic? (-subtype arr))
    (throw (enc/illegal-argument
            (str op " requires an integer-typed array (not supported on :float32/:float64)")))))

(defn- array=
  "Same subtype, same count, equal elements: an EveArray and a heap array
   alike."
  [a b]
  (or (identical? a b)
      (and (satisfies? IEveArray b)
           (== (-subtype a) (-subtype b))
           (== (e/count a) (e/count b))
           (let [n (e/count a)]
             (loop [i 0]
               (cond (== i n) true
                     (= (-aget a i) (-aget b i)) (recur (inc i))
                     :else false))))))

(defn- require-int32! [arr op]
  (when (not= (-subtype arr) ser/TYPED_ARRAY_INT32)
    (throw (enc/illegal-argument
            (str op " only supported on :int32 arrays")))))

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
                      ^:cached ^:mutable hash-cache__]

  clojure.lang.Counted
  (count [_] (int cnt))

  clojure.lang.Indexed
  (nth [this n]
    (if (and (>= n 0) (< n cnt))
      (read-element sio__ offset__ n subtype__)
      (throw (enc/index-out-of-bounds
              (str "Index out of bounds: " n " for length " cnt)))))
  (nth [this n not-found]
    (if (and (>= n 0) (< n cnt))
      (read-element sio__ offset__ n subtype__)
      not-found))

  clojure.lang.ILookup
  (valAt [this k] (e/nth this (int k) nil))
  (valAt [this k not-found] (e/nth this (int k) not-found))

  clojure.lang.IFn
  (invoke [this k] (e/nth this (int k)))
  (invoke [this k not-found] (e/nth this (int k) not-found))

  clojure.lang.Seqable
  (seq [this]
    (when (pos? cnt)
      (let [sub subtype__]
        (letfn [(arr-seq [i]
                  (when (< i cnt)
                    (lazy-seq (cons (read-element sio__ offset__ i sub) (arr-seq (inc i))))))]
          (arr-seq 0)))))

  clojure.lang.IMeta
  (meta [_] nil)

  clojure.lang.IObj
  (withMeta [this m] this)

  clojure.lang.IReduce
  (reduce [this f]
    (if (zero? cnt)
      (f)
      (let [sub subtype__]
        (loop [i 1 acc (read-element sio__ offset__ 0 sub)]
          (if (< i cnt)
            (let [result (f acc (read-element sio__ offset__ i sub))]
              (if (reduced? result) @result (recur (inc i) result)))
            acc)))))

  clojure.lang.IReduceInit
  (reduce [this f start]
    (let [sub subtype__]
      (loop [i 0 acc start]
        (if (< i cnt)
          (let [result (f acc (read-element sio__ offset__ i sub))]
            (if (reduced? result) @result (recur (inc i) result)))
          acc))))

  clojure.lang.IHashEq
  (hasheq [this]
    (if hash-cache__
      hash-cache__
      (let [sub subtype__
            h (loop [i 0 h (unchecked-int (+ 1 (* 31 sub)))]
                (if (< i cnt)
                  (recur (inc i) (unchecked-int (+ (* 31 h) (hash (read-element sio__ offset__ i sub)))))
                  h))]
        (set! hash-cache__ h)
        h)))

  ;; cons and empty too: basilisp's types refuse a partial interface
  clojure.lang.IPersistentCollection
  (cons [_ _] (enc/throw-unsupported))
  (empty [_] nil)
  (equiv [this other] (array= this other))

  ;; (the type's print-method on some hosts: it names no field)
  IPrintWithWriter
  (-pr-writer [this writer _opts]
    (e/-write writer (str "#eve/array " (subtype->type-kw (-subtype this)) " "
                          (e/pr-str* (mapv #(e/nth this %) (range (e/count this)))))))

  d/IDirectSerialize
  (-direct-serialize [_] offset__)

  d/ISabStorable
  (-sab-tag [_] :eve/array)
  (-sab-encode [_this _s-atom-env]
    (codec/encode-sab-pointer ser/FAST_TAG_EVE_ARRAY offset__))
  (-sab-dispose [_ _s-atom-env]
    (when (and (some? offset__) (not= offset__ alloc/NIL_OFFSET))
      (slab/free! sio__ offset__)))

  d/IsEve
  (-eve? [_] true)

  d/IEveRoot
  (-root-header-off [_] offset__)

  d/IEveSio
  (-eve-sio [_] sio__)

  d/IPlain
  (-plain [this]
    ;; through nth, not seq: bb's EveArray carries Indexed only
    (let [n cnt]
      (loop [i 0 acc (transient [])]
        (if (< i n)
          (recur (inc i) (conj! acc (d/eve->clj (e/nth this i))))
          (persistent! acc)))))

  IEveArray
  (-subtype [_] subtype__)
  (-aget [_ i] (read-element sio__ offset__ i subtype__))
  (-aset! [_ i v] (write-element! sio__ offset__ i subtype__ v))
  (-update-elem! [_ i f] (update-element! sio__ offset__ i subtype__ f))

  ;; lets atom swap retirement free an old EveArray's block
  d/ISabRetirable
  (-sab-retire-diff! [_ new-value _slab-env _mode]
    (when-not (and (instance? EveArray new-value)
                   (== offset__ (d/-root-header-off new-value)))
      (when (and (some? offset__) (not= offset__ alloc/NIL_OFFSET))
        (slab/free! sio__ offset__))))

  ;; the elements as the host's own double / int array (eve.encoding's
  ;; reading of the slab's little-endian words): a copy
  d/IBulkAccess
  (-as-double-array [_]
    (when (== subtype__ 9)
      (enc/f64s-le (slab/read-bytes sio__ offset__ HEADER_SIZE (* cnt 8)) 0 cnt)))
  (-as-int-array [_]
    (when (== subtype__ 6)
      (enc/i32s-le (slab/read-bytes sio__ offset__ HEADER_SIZE (* cnt 4)) 0 cnt)))

  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  java.lang.Object
  ;; (dropped on ClojureScript: its IEquiv and IHash are the type's)
  (toString [this] (str "#eve/array " (subtype->type-kw subtype__) " " (vec (e/seq this))))
  (equals [this other] (array= this other))
  (hashCode [this] (.hasheq this)))

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
        slab-off (slab/alloc! sio total-size)]
    (slab/write-u8!  sio slab-off 0 EveArray-type-id)
    (slab/write-u8!  sio slab-off 1 subtype)
    (slab/write-u16! sio slab-off 2 0)
    (slab/write-i32! sio slab-off 4 n)
    slab-off))

;;-----------------------------------------------------------------------------
;; Cached-field constructor helper
;;-----------------------------------------------------------------------------

(defn- make-eve-array-instance
  "Construct an EveArray with all cached fields computed from slab header.
   Reads subtype from slab once, derives elem-shift and atomic?."
  [sio slab-off]
  (let [subtype (slab/read-u8 sio slab-off 1)
        elem-shift (subtype->elem-shift subtype)
        atomic? (subtype->atomic? subtype)]
    (->EveArray sio slab-off subtype elem-shift atomic? nil)))

;;-----------------------------------------------------------------------------
;; Internal constructors
;;-----------------------------------------------------------------------------

(defn- make-eve-array [sio type-kw n init-val]
  (let [subtype (type-kw->subtype type-kw)
        slab-off (alloc-eve-region sio subtype n)
        fill-val (or init-val 0)]
    (fill-elements! sio slab-off subtype n fill-val)
    (make-eve-array-instance sio slab-off)))

(defn- make-eve-array-from [sio type-kw coll]
  (let [v (vec (e/seq coll))
        n (count v)]
    (if (zero? n)
      (make-eve-array sio type-kw 0 nil)
      (let [subtype (type-kw->subtype type-kw)
            slab-off (alloc-eve-region sio subtype n)]
        ;; float64 / int32 elements go in as one write of their bytes
        (case (int subtype)
          9 (slab/write-bytes! sio slab-off HEADER_SIZE (enc/f64s->bytes-le (double-array v)))
          6 (slab/write-bytes! sio slab-off HEADER_SIZE (enc/i32s->bytes-le (int-array v)))
          (dotimes [i n]
            (write-element! sio slab-off i subtype (nth v i))))
        (make-eve-array-instance sio slab-off)))))

;;-----------------------------------------------------------------------------
;; Unified constructor
;;-----------------------------------------------------------------------------

(defn- default-sio
  "The slab I/O a constructor given none allocates in: the running swap!'s
   domain, else this process's own (eve.deftype-proto.alloc/current-sio)."
  []
  (alloc/current-sio))

(declare heap-arrays? heap-array)

(defn eve-array
  "Create a typed array.

     (eve-array :int32 10)          ;; 10 zero-filled int32 elements
     (eve-array :float64 10 0.0)    ;; 10 float64 filled with 0.0
     (eve-array :uint8 [1 2 3])     ;; from collection

   sio-first variants take an explicit ISlabIO:
     (eve-array sio :int32 10)
     (eve-array sio :float64 10 0.0)

   Without an explicit sio the array is the host's own typed array where the
   host has them (a HeapEveArray: the JVM, bb, jolt — eve.encoding's
   typed-array), else it is allocated in this process's slab (default-sio).

   Supported types: :int8 :uint8 :int16 :uint16 :int32 :uint32 :float32 :float64"
  ([type-kw size-or-coll]
   (if (heap-arrays?)
     (heap-array type-kw size-or-coll)
     (eve-array (default-sio) type-kw size-or-coll)))
  ([a b c]
   (if (keyword? a)
     ;; (eve-array type-kw n init-val) — this process's
     (if (heap-arrays?)
       (heap-array a b c)
       (eve-array (default-sio) a b c))
     ;; (eve-array sio type-kw size-or-coll) — explicit sio
     (let [sio a type-kw b size-or-coll c]
       (if (number? size-or-coll)
         (make-eve-array sio type-kw (int size-or-coll) nil)
         (make-eve-array-from sio type-kw size-or-coll)))))
  ([sio type-kw n init-val]
   (make-eve-array sio type-kw (int n) init-val)))

(defn eve-array-uninit
  "Allocate a typed array without zero-filling the data region.
   Use when the caller will immediately overwrite all elements.
   On CLJS, SAB memory is zero-initialized by spec, so this is safe even
   if only partially written.
   sio-first variant takes an explicit ISlabIO."
  ([type-kw n]
   (eve-array-uninit (default-sio) type-kw n))
  ([sio type-kw n]
   (let [subtype (type-kw->subtype type-kw)
         slab-off (alloc-eve-region sio subtype n)]
     (make-eve-array-instance sio slab-off))))

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
  "Read element at index."
  [arr idx]
  (let [cnt (e/count arr)]
    (when (or (< idx 0) (>= idx cnt))
      (throw (enc/index-out-of-bounds
              (str "Index out of bounds: " idx))))
    (-aget arr idx)))

(defn aset!
  "Write element at index. Returns the value written."
  [arr idx val]
  (let [cnt (e/count arr)]
    (when (or (< idx 0) (>= idx cnt))
      (throw (enc/index-out-of-bounds
              (str "Index out of bounds: " idx))))
    (-aset! arr idx val)
    val))

;;-----------------------------------------------------------------------------
;; Atomic operations (integer subtypes, through IEveArray's -update-elem!)
;;
;; A slab-backed array updates by a CAS loop on the i32 word holding the
;; element (a sub-word element masked in place); wait!/notify! go to the
;; IMemRegion the element lives in (slab/mem-region).
;;-----------------------------------------------------------------------------

(defn- bounds-check! [arr idx op]
  (let [cnt (e/count arr)]
    (when (or (< idx 0) (>= idx cnt))
      (throw (enc/index-out-of-bounds
              (str op ": index out of bounds: " idx " for length " cnt))))))

(defn cas!
  "Compare-and-swap at index. Returns true if successful.
   Integer types only."
  [arr idx expected new-val]
  (require-atomic! arr "cas!")
  (bounds-check! arr idx "cas!")
  (== expected (-update-elem! arr idx (fn [old] (if (== old expected) new-val old)))))

(defn exchange!
  "Atomically replace value at index, returning the old value.
   Integer types only."
  [arr idx new-val]
  (require-atomic! arr "exchange!")
  (bounds-check! arr idx "exchange!")
  (-update-elem! arr idx (fn [_] new-val)))

(defn add!
  "Atomically add to value at index, returning the old value.
   Integer types only."
  [arr idx delta]
  (require-atomic! arr "add!")
  (bounds-check! arr idx "add!")
  (-update-elem! arr idx (fn [old] (+ old delta))))

(defn sub!
  "Atomically subtract from value at index, returning the old value.
   Integer types only."
  [arr idx delta]
  (require-atomic! arr "sub!")
  (bounds-check! arr idx "sub!")
  (-update-elem! arr idx (fn [old] (- old delta))))

(defn band!
  "Atomically bitwise-AND value at index, returning the old value.
   :int32 arrays only."
  [arr idx mask]
  (require-int32! arr "band!")
  (bounds-check! arr idx "band!")
  (-update-elem! arr idx (fn [old] (bit-and old mask))))

(defn bor!
  "Atomically bitwise-OR value at index, returning the old value.
   :int32 arrays only."
  [arr idx mask]
  (require-int32! arr "bor!")
  (bounds-check! arr idx "bor!")
  (-update-elem! arr idx (fn [old] (bit-or old mask))))

(defn bxor!
  "Atomically bitwise-XOR value at index, returning the old value.
   :int32 arrays only."
  [arr idx mask]
  (require-int32! arr "bxor!")
  (bounds-check! arr idx "bxor!")
  (-update-elem! arr idx (fn [old] (bit-xor old mask))))

;;-----------------------------------------------------------------------------
;; Wait/Notify (:int32 only, through the element's IMemRegion)
;;-----------------------------------------------------------------------------

(defn- element-region
  "[region byte-off] of an :int32 element of a slab-backed array."
  [arr idx]
  (slab/mem-region (d/-eve-sio arr) (d/-root-header-off arr) (+ HEADER_SIZE (* idx 4))))

(defn wait!
  "Block until the value at index is not equal to `expected`, or until timeout.
   Returns :ok, :not-equal, or :timed-out.
   :int32 arrays only."
  ([arr idx expected]
   (wait! arr idx expected ##Inf))
  ([arr idx expected timeout-ms]
   (require-int32! arr "wait!")
   (bounds-check! arr idx "wait!")
   (let [[region byte-off] (element-region arr idx)]
     (mem/-wait-i32! region byte-off expected timeout-ms))))

(defn notify!
  "Wake up waiting agents on the value at index.
   count defaults to ##Inf (wake all waiters).
   Returns the number of agents woken.
   :int32 arrays only."
  ([arr idx]
   (notify! arr idx ##Inf))
  ([arr idx n]
   (require-int32! arr "notify!")
   (bounds-check! arr idx "notify!")
   (let [[region byte-off] (element-region arr idx)]
     (mem/-notify-i32! region byte-off n))))

;;-----------------------------------------------------------------------------
;; Functional operations
;;-----------------------------------------------------------------------------

(defn areduce
  "Reduce over array elements with index.
   f is (fn [acc idx val] ...).
   Returns the final accumulated value."
  [^EveArray arr init f]
  (let [len (e/count arr)]
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
  (let [len (e/count arr)
        type-kw (subtype->type-kw (-subtype arr))
        result (eve-array type-kw len)]
    (dotimes [i len]
      (aset! result i (f i (aget arr i))))
    result))

(defn amap!
  "Map f over array indices in-place, mutating the array.
   f is (fn [idx current-val] ...).
   Returns the array."
  [^EveArray arr f]
  (let [len (e/count arr)]
    (dotimes [i len]
      (aset! arr i (f i (aget arr i))))
    arr))

(defn afill!
  "Fill array with value, optionally in range [start, end).
   Returns the array."
  ([^EveArray arr val]
   (afill! arr val 0 (e/count arr)))
  ([^EveArray arr val start]
   (afill! arr val start (e/count arr)))
  ([^EveArray arr val start end]
   (let [len (e/count arr)
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
   (acopy! dest 0 src 0 (min (e/count dest) (e/count src))))
  ([dest dest-start src src-start len]
   (dotimes [i len]
     (aset! dest (+ dest-start i) (aget src (+ src-start i))))
   dest))

(defn array-type
  "Get the type keyword for this array (:int32, :float64, etc.)."
  [arr]
  (subtype->type-kw (-subtype arr)))

(defn retire!
  "Mark this array's slab block as retired for GC.
   Call when replacing this array with a new version."
  [arr]
  (let [slab-off (d/-root-header-off arr)]
    (when (and (some? slab-off) (not= slab-off alloc/NIL_OFFSET))
      (slab/free! (d/-eve-sio arr) slab-off))))


;;=============================================================================
;; Offset Collection (for epoch-based retirement in mmap atoms)
;;=============================================================================

(defn collect-retire-diff-offsets
  "Collect slab offsets for an old EveArray. Single block allocation."
  [old-array new-value]
  (let [old-off (d/-root-header-off old-array)]
    (if (and (instance? EveArray new-value)
             (== old-off (d/-root-header-off new-value)))
      [] ;; Same block — no-op
      (if (and (some? old-off) (not= old-off alloc/NIL_OFFSET))
        [old-off]
        []))))

;;=============================================================================
;; An EveArray in an atom, and from a slab pointer (every host)
;;
;; Block layout (type-id 0x1D):
;;   [0x1D : u8][subtype : u8][pad : u16][count : i32][element-bytes...]
;;=============================================================================

(defn eve-array-from-offset
  "The EveArray at the 0x1D block slab-off."
  [sio slab-off]
  (make-eve-array-instance sio slab-off))

;; An EveArray stored into an atom is copied into a block of the atom's domain
;; (a host whose atom holds an eve root by pointer, IEveRoot, never gets here)
(ser/register-builder!
  (fn [v] (instance? EveArray v))
  (fn [sio arr]
    (let [subtype (-subtype arr)
          n (e/count arr)
          byte-len (* n (subtype->elem-size subtype))
          blk-off (slab/alloc! sio (+ HEADER_SIZE byte-len))]
      (slab/write-u8!  sio blk-off 0 ser/EVE_ARRAY_SLAB_TYPE_ID)
      (slab/write-u8!  sio blk-off 1 subtype)
      (slab/write-u16! sio blk-off 2 0)
      (slab/write-i32! sio blk-off 4 n)
      (when (pos? byte-len)
        (slab/write-bytes! sio blk-off HEADER_SIZE
                           (slab/read-bytes (d/-eve-sio arr) (d/-root-header-off arr) HEADER_SIZE byte-len)))
      (reify d/IEveRoot
        (-root-header-off [_] blk-off)))))

;; 0x1D: the pointer tag and the header type-id
(ser/register-type-constructor!
  ser/EVE_ARRAY_SLAB_TYPE_ID ser/EVE_ARRAY_SLAB_TYPE_ID eve-array-from-offset)

;; the block holds its elements: freeing it frees the array
(ser/register-header-disposer!
  ser/EVE_ARRAY_SLAB_TYPE_ID
  (fn [sio blk-off] (slab/free! sio blk-off)))

;; A host typed array in an eve value: an eve array block (eve.encoding reads
;; the host's array as its subtype and little-endian bytes)
(defn write-host-array!
  "Host typed array a written as a 0x1D block in sio; answers its slab offset.
   subtype, when given, is the block's element subtype (a host array's class
   alone cannot say :uint8 for a byte array, or :uint16, :uint32)."
  ([sio a] (write-host-array! sio a nil))
  ([sio a subtype]
   (let [[inferred bs] (or (enc/array-subtype+bytes a)
                           (throw (enc/illegal-argument (str "Not a host typed array: " (type a)))))
         n (quot (alength ^bytes bs) (subtype->elem-size inferred))
         off (alloc-eve-region sio (or subtype inferred) n)]
     (when (pos? n)
       (slab/write-bytes! sio off HEADER_SIZE bs))
     off)))

(codec/register-collection-writer! :array
  (fn [sio subtype a] (write-host-array! sio a subtype)))

(defn array-values
  "The elements of the 0x1D block at slab-off, a vector."
  [sio slab-off]
  (let [a (eve-array-from-offset sio slab-off)]
    (mapv #(-aget a %) (range (e/count a)))))

;; 0x1C (FAST_TAG_EVE_ARRAY), the SAB pointer tag. An old block,
;; [subtype:u8 @0][pad @1-3][count:u32 @4-7][data @8+], has the 0x1D layout
;; but for its first two bytes: the subtype sits where the type-id sits
;; (subtype codes are 1-9; 0x1D is outside them). Reading one does not
;; rewrite it (census D6: a read that writes): the instance takes its
;; subtype from byte 0, and the header is converted only when a write
;; copies the array (the atom store builder writes a fresh 0x1D block).
(defn- eve-array-from-sab-pointer [sio slab-off]
  (let [byte0 (slab/read-u8 sio slab-off 0)]
    (if (<= byte0 9)
      (->EveArray sio slab-off byte0 (subtype->elem-shift byte0)
                  (subtype->atomic? byte0) nil)
      (make-eve-array-instance sio slab-off))))

(ser/register-type-constructor! ser/FAST_TAG_EVE_ARRAY eve-array-from-sab-pointer)

;;=============================================================================
;; HeapEveArray: a fresh eve array on a host with its own typed arrays
;; (eve.encoding's typed-array, typed-aget, typed-aset!: the JVM's primitive
;; arrays on the JVM, bb and jolt). It belongs to no slab domain; storing it in
;; an atom writes its elements into the atom's slab (its IBackingArray). On a
;; host without typed arrays a fresh eve array is slab-backed (eve-array).
;;=============================================================================

(defdelay ^:private heap-arrays
  (some? (enc/typed-array ser/TYPED_ARRAY_INT32 0)))

(defn- heap-arrays?
  "A fresh eve array is the host's own typed array here."
  []
  @heap-arrays)

(defn- array-hash [a]
  (loop [i 0 h (unchecked-int (+ 1 (* 31 (-subtype a))))]
    (if (< i (e/count a))
      (recur (inc i) (unchecked-int (+ (* 31 h) (hash (-aget a i)))))
      h)))

(defn- array-str [a]
  (str "#eve/array " (subtype->type-kw (-subtype a)) " "
       (e/pr-str* (mapv #(-aget a %) (range (e/count a))))))

(edt/deftype HeapEveArray [cnt subtype-code backing]
  clojure.lang.Counted
  (count [_] (int cnt))

  clojure.lang.Indexed
  (nth [_ i]
    (if (and (>= i 0) (< i cnt))
      (enc/typed-aget backing subtype-code i)
      (throw (enc/index-out-of-bounds (str "Index " i " out of bounds for length " cnt)))))
  (nth [_ i not-found]
    (if (and (>= i 0) (< i cnt))
      (enc/typed-aget backing subtype-code i)
      not-found))

  clojure.lang.ILookup
  (valAt [this k] (e/nth this (int k)))
  (valAt [this k not-found] (e/nth this (int k) not-found))

  clojure.lang.Seqable
  (seq [this]
    (when (pos? cnt)
      (letfn [(arr-seq [i]
                (when (< i cnt)
                  (lazy-seq (cons (enc/typed-aget backing subtype-code i) (arr-seq (inc i))))))]
        (arr-seq 0))))

  clojure.lang.IReduce
  (reduce [this f]
    (if (zero? cnt)
      (f)
      (loop [i 1 acc (enc/typed-aget backing subtype-code 0)]
        (if (or (>= i cnt) (reduced? acc))
          (unreduced acc)
          (recur (inc i) (f acc (enc/typed-aget backing subtype-code i)))))))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (loop [i 0 acc init]
      (if (or (>= i cnt) (reduced? acc))
        (unreduced acc)
        (recur (inc i) (f acc (enc/typed-aget backing subtype-code i))))))

  clojure.lang.IFn
  (invoke [this i] (e/nth this (int i)))
  (invoke [this i not-found] (e/nth this (int i) not-found))

  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  clojure.lang.IHashEq
  (hasheq [this] (array-hash this))

  ;; cons and empty too: basilisp's types refuse a partial interface
  clojure.lang.IPersistentCollection
  (cons [_ _] (enc/throw-unsupported))
  (empty [_] nil)
  (equiv [this other] (array= this other))

  ;; (the type's print-method on some hosts: it names no field)
  IPrintWithWriter
  (-pr-writer [this writer _opts] (e/-write writer (array-str this)))

  java.lang.Object
  (toString [this] (array-str this))
  (equals [this other] (array= this other))
  (hashCode [this] (array-hash this))

  IEveArray
  (-subtype [_] subtype-code)
  (-aget [_ i] (enc/typed-aget backing subtype-code i))
  (-aset! [_ i v] (enc/typed-aset! backing subtype-code i v))
  (-update-elem! [this i f]
    (locking this
      (let [old (enc/typed-aget backing subtype-code i)]
        (enc/typed-aset! backing subtype-code i (f old))
        old)))

  d/IBackingArray
  (-backing-array [_] backing)
  (-backing-subtype [_] subtype-code)

  d/IBulkAccess
  (-as-double-array [_]
    (when (== subtype-code 9) backing))
  (-as-int-array [_]
    (when (== subtype-code 6) backing)))

(defn- heap-array
  "A HeapEveArray: (heap-array type-kw n), (heap-array type-kw n init-val),
   (heap-array type-kw coll)."
  ([type-kw size-or-coll]
   (if (number? size-or-coll)
     (heap-array type-kw size-or-coll nil)
     (let [sc (type-kw->subtype type-kw)
           coll (vec (e/seq size-or-coll))
           n (count coll)
           backing (enc/typed-array sc n)]
       (dotimes [i n]
         (enc/typed-aset! backing sc i (nth coll i)))
       (->HeapEveArray n sc backing))))
  ([type-kw n init-val]
   (let [sc (type-kw->subtype type-kw)
         backing (enc/typed-array sc n)]
     (when init-val
       (dotimes [i n]
         (enc/typed-aset! backing sc i init-val)))
     (->HeapEveArray (long n) sc backing))))

;; A host array as an eve array: the array itself (zero-copy) where fresh
;; arrays are the host's own, else its elements written into a slab array in
;; one go (eve.encoding's bytes)
(defn- from-element-bytes [type-kw n bs]
  (let [arr (eve-array-uninit type-kw n)]
    (when (pos? n)
      (slab/write-bytes! (d/-eve-sio arr) (d/-root-header-off arr) HEADER_SIZE bs))
    arr))

(defn from-double-array
  "An eve :float64 array of the host double array's elements: the array
   itself on a host whose fresh arrays are heap arrays, else a slab copy."
  [^doubles arr]
  (if (heap-arrays?)
    (->HeapEveArray (alength arr) 9 arr)
    (from-element-bytes :float64 (alength arr) (enc/f64s->bytes-le arr))))

(defn from-int-array
  "An eve :int32 array of the host int array's elements (as from-double-array)."
  [^ints arr]
  (if (heap-arrays?)
    (->HeapEveArray (alength arr) 6 arr)
    (from-element-bytes :int32 (alength arr) (enc/i32s->bytes-le arr))))

(defn from-byte-array
  "An eve :uint8 array of the host byte array's bytes (as from-double-array)."
  [^bytes arr]
  (if (heap-arrays?)
    (->HeapEveArray (alength arr) 1 arr)
    (from-element-bytes :uint8 (alength arr) arr)))

;;-----------------------------------------------------------------------------
;; Portable accessors (after all types are defined)
;;-----------------------------------------------------------------------------

(defn array-subtype-code
  "Return the subtype code of an EveArray (portable across hosts).
   Works for both slab-backed EveArray and heap-backed HeapEveArray."
  [arr]
  (-subtype arr))
