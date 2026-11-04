(ns eve.typed-view
  "ClojureScript's own views of an EveArray (nbb's too): its module-slab
   buffer and a JS TypedArray over its elements, wasm-SIMD bulk operations on
   :int32 arrays, and an EveArray from a JS typed array. eve.array is the
   array on every host; these are JavaScript's.
   (Moved from eve.array's ClojureScript arm.)"
  (:require [eve.core :as e]
            [eve.array :as arr]
            [eve.codec :as codec]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.deftype-proto.wasm :as slab-wasm]
            [eve.process-slab :as ps]
            [eve.wasm-mem :as wasm-mem]))

(defn- require-int32! [a op]
  (when (not= (arr/array-subtype-code a) ser/TYPED_ARRAY_INT32)
    (throw (js/Error. (str op " only supported on :int32 arrays")))))

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
    0x09 (js/Float64Array. buf)))

;;-----------------------------------------------------------------------------
;; Low-level access (resolve dynamically from sio__/offset__)
;;-----------------------------------------------------------------------------

(defn get-sab
  "Get the underlying buffer (SAB or mmap-backed ArrayBuffer).
   Resolves dynamically from the slab offset."
  [^EveArray arr]
  (let [class-idx (alloc/decode-class-idx (.-offset__ arr))
        inst (slab-wasm/get-slab-instance class-idx)]
    (.-buffer ^js (:u8 inst))))

(defn get-offset
  "Get the absolute byte offset of the data region in the backing buffer."
  [^EveArray arr]
  (+ (ps/slab-offset->byte-offset (.-offset__ arr)) arr/HEADER_SIZE))

(defn get-typed-view
  "Get a raw JS typed array view of this array's data region.
   Always resolves from the current slab instance to handle coalesc buffer growth.
   Useful for bulk operations. Handle with care."
  [^EveArray arr]
  (let [offset (.-offset__ arr)
        class-idx (alloc/decode-class-idx offset)
        inst (slab-wasm/get-slab-instance class-idx)
        buf (.-buffer ^js (:u8 inst))
        tv (make-typed-view buf (.-subtype__ arr))
        es (.-elem-shift__ arr)
        dbo (get-offset arr)
        cnt (e/count arr)
        base (unsigned-bit-shift-right dbo es)]
    (.subarray tv base (+ base cnt))))

(defn get-int32-view
  "Get a raw Int32Array view of the data region.
   Only valid for :int32 arrays."
  [^EveArray arr]
  (let [cnt (e/count arr)
        buf (get-sab arr)
        data-off (get-offset arr)]
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
  (+ (ps/slab-offset->byte-offset (.-offset__ arr)) arr/HEADER_SIZE (* start 4)))

(defn afill-simd!
  "Fill :int32 array with value using SIMD (4 elements at a time).
   WARNING: Does not use Atomics. Only use before sharing with other threads."
  ([^EveArray arr val]
   (afill-simd! arr val 0 (e/count arr)))
  ([^EveArray arr val start end]
   (require-int32! arr "afill-simd!")
   (let [byte-start (array-data-byte-offset arr start)
         count (- end start)]
     (when (and (wasm-mem/ready?) (pos? count))
       (wasm-mem/simd-fill-i32! byte-start count val))
     arr)))

(defn acopy-simd!
  "Copy elements between :int32 arrays using SIMD (4 elements at a time).
   WARNING: Does not use Atomics. Only use before sharing with other threads."
  ([dest src]
   (acopy-simd! dest 0 src 0 (min (e/count dest) (e/count src))))
  ([dest dest-start src src-start len]
   (require-int32! dest "acopy-simd!")
   (require-int32! src "acopy-simd!")
   (when (and (wasm-mem/ready?) (pos? len))
     (let [dest-byte-offset (array-data-byte-offset dest dest-start)
           src-byte-offset (array-data-byte-offset src src-start)]
       (wasm-mem/simd-copy-i32! dest-byte-offset src-byte-offset len)))
   dest))

(defn asum-simd
  "Sum all elements in :int32 array using SIMD.
   Safe to use on shared arrays (read-only)."
  ([^EveArray arr]
   (asum-simd arr 0 (e/count arr)))
  ([^EveArray arr start end]
   (require-int32! arr "asum-simd")
   (let [byte-start (array-data-byte-offset arr start)
         count (- end start)]
     (if (and (wasm-mem/ready?) (pos? count))
       (wasm-mem/simd-sum-i32 byte-start count)
       0))))

(defn amin-simd
  "Find minimum value in :int32 array using SIMD.
   Safe to use on shared arrays (read-only).
   Returns INT32_MAX for empty arrays."
  ([^EveArray arr]
   (amin-simd arr 0 (e/count arr)))
  ([^EveArray arr start end]
   (require-int32! arr "amin-simd")
   (let [byte-start (array-data-byte-offset arr start)
         count (- end start)]
     (if (and (wasm-mem/ready?) (pos? count))
       (wasm-mem/simd-min-i32 byte-start count)
       2147483647))))

(defn amax-simd
  "Find maximum value in :int32 array using SIMD.
   Safe to use on shared arrays (read-only).
   Returns INT32_MIN for empty arrays."
  ([^EveArray arr]
   (amax-simd arr 0 (e/count arr)))
  ([^EveArray arr start end]
   (require-int32! arr "amax-simd")
   (let [byte-start (array-data-byte-offset arr start)
         count (- end start)]
     (if (and (wasm-mem/ready?) (pos? count))
       (wasm-mem/simd-max-i32 byte-start count)
       -2147483648))))

(defn aequal-simd?
  "Compare two :int32 arrays for equality using SIMD.
   Safe to use on shared arrays (read-only)."
  [^EveArray arr1 ^EveArray arr2]
  (require-int32! arr1 "aequal-simd?")
  (require-int32! arr2 "aequal-simd?")
  (let [len1 (e/count arr1)
        len2 (e/count arr2)]
    (if (not= len1 len2)
      false
      (if (and (wasm-mem/ready?) (pos? len1))
        (wasm-mem/simd-eq-i32? (array-data-byte-offset arr1 0) (array-data-byte-offset arr2 0) len1)
        (zero? len1)))))

;;-----------------------------------------------------------------------------
;; Native typed array → EveArray conversion
;;-----------------------------------------------------------------------------

(defn from-typed-array
  "Create an EveArray from a native JS typed array.
   Copies the data into slab memory through ISlabIO.
   Supports all standard typed array types including Uint8ClampedArray."
  [elem]
  (let [subtype (codec/typed-array-subtype elem)
        a (arr/eve-array-uninit (arr/subtype->type-kw subtype) (.-length elem))
        ;; Bulk copy element bytes into the data region
        byte-view (js/Uint8Array. (.-buffer elem) (.-byteOffset elem) (.-byteLength elem))]
    (alloc/-sio-write-bytes! (d/-eve-sio a) (d/-root-header-off a) arr/HEADER_SIZE byte-view)
    a))
