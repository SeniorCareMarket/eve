(ns eve.deftype.runtime
  (:require
   [eve.atom :as atom]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.serialize :as ser]
   [eve.deftype-proto.wasm :as proto-wasm]))

;; Runtime helpers for eve-deftype instances.
;; The macro emits calls to these functions with compile-time-known
;; offsets and types baked in as arguments.
;;
;; SLAB SYSTEM: eve-offset is now a slab-qualified offset (class:3|block:29).
;; All DataView access goes through the slab allocator's resolve-dv!/resolve-u8!.
;; The eve-env parameter is kept for backward compat but ignored.

;;-----------------------------------------------------------------------------
;; Direct allocation with descriptor tracking for GC
;;-----------------------------------------------------------------------------

(defn- direct-alloc
  "Allocate `size` bytes from the slab allocator.
   Returns {:offset slab-qualified-offset :descriptor-idx -1}."
  [eve-env size]
  (let [slab-off (alloc/alloc-offset size)]
    {:offset slab-off :descriptor-idx -1}))

;;-----------------------------------------------------------------------------
;; Instance allocation
;;-----------------------------------------------------------------------------

(defn alloc-instance
  "Allocate a contiguous region in the slab for a new eve-deftype instance.
   Writes the type-id byte at offset 0 and zero-fills the rest.
   Returns the slab-qualified offset of the allocated instance."
  [eve-env type-id total-size]
  (let [slab-off (alloc/alloc-offset total-size)
        base (alloc/resolve-u8! slab-off)
        dv alloc/resolved-dv
        u8 alloc/resolved-u8]
    ;; write type-id byte at position 0
    (.setUint8 dv base type-id)
    ;; zero-fill bytes 1..total-size-1
    (loop [i 1]
      (when (< i total-size)
        (aset u8 (+ base i) 0)
        (recur (inc i))))
    slab-off))

;;-----------------------------------------------------------------------------
;; Serialized field encode/decode (fast-path, no Fressian)
;;-----------------------------------------------------------------------------

(defn serialize-value
  "Encode a Clojure value to bytes using fast-path serialization."
  [obj]
  (ser/serialize-element obj))

(defn deserialize-value
  "Decode bytes to a Clojure value using fast-path deserialization."
  [eve-env byte-array]
  (when byte-array
    (ser/deserialize-element eve-env byte-array)))

;;-----------------------------------------------------------------------------
;; Primitive field reads
;;-----------------------------------------------------------------------------

(defn read-int32
  "Read an int32 field at (slab-off + field-offset) via slab DataView."
  [_eve-env slab-off field-offset]
  (alloc/read-i32 slab-off field-offset))

(defn read-int32-volatile
  "Read a volatile int32 field via Atomics.load."
  [_eve-env slab-off field-offset]
  ;; Atomics.load on the slab's Int32Array
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))]
    (js/Atomics.load (js/Int32Array. sab)
                     (unsigned-bit-shift-right (+ base field-offset) 2))))

(defn read-uint32
  "Read a uint32 field."
  [_eve-env slab-off field-offset]
  (let [base (alloc/resolve-dv! slab-off)]
    (unsigned-bit-shift-right (.getUint32 alloc/resolved-dv (+ base field-offset) true) 0)))

(defn read-uint32-volatile
  "Read a volatile uint32 field via Atomics.load."
  [_eve-env slab-off field-offset]
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))]
    (js/Atomics.load (js/Int32Array. sab)
                     (unsigned-bit-shift-right (+ base field-offset) 2))))

(defn read-float32
  "Read a float32 field."
  [_eve-env slab-off field-offset]
  (let [base (alloc/resolve-dv! slab-off)]
    (.getFloat32 alloc/resolved-dv (+ base field-offset) true)))

(defn read-float64
  "Read a float64 field."
  [_eve-env slab-off field-offset]
  (let [base (alloc/resolve-dv! slab-off)]
    (.getFloat64 alloc/resolved-dv (+ base field-offset) true)))

;;-----------------------------------------------------------------------------
;; Primitive field writes
;;-----------------------------------------------------------------------------

(defn write-int32!
  "Write an int32 field. Returns the written value."
  [_eve-env slab-off field-offset val]
  (alloc/write-i32! slab-off field-offset val)
  val)

(defn write-int32-volatile!
  "Write a volatile int32 field via Atomics.store. Returns the written value."
  [_eve-env slab-off field-offset val]
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))]
    (js/Atomics.store (js/Int32Array. sab)
                      (unsigned-bit-shift-right (+ base field-offset) 2)
                      val)
    val))

(defn write-uint32!
  "Write a uint32 field. Returns the written value."
  [_eve-env slab-off field-offset val]
  (let [base (alloc/resolve-dv! slab-off)]
    (.setUint32 alloc/resolved-dv (+ base field-offset) val true))
  val)

(defn write-uint32-volatile!
  "Write a volatile uint32 field via Atomics.store."
  [_eve-env slab-off field-offset val]
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))]
    (js/Atomics.store (js/Int32Array. sab)
                      (unsigned-bit-shift-right (+ base field-offset) 2)
                      val)
    val))

(defn write-float32!
  "Write a float32 field."
  [_eve-env slab-off field-offset val]
  (let [base (alloc/resolve-dv! slab-off)]
    (.setFloat32 alloc/resolved-dv (+ base field-offset) val true))
  val)

(defn write-float64!
  "Write a float64 field."
  [_eve-env slab-off field-offset val]
  (let [base (alloc/resolve-dv! slab-off)]
    (.setFloat64 alloc/resolved-dv (+ base field-offset) val true))
  val)

;;-----------------------------------------------------------------------------
;; Primitive CAS
;;-----------------------------------------------------------------------------

(defn cas-int32!
  "CAS on a volatile int32 field. Returns true if successful."
  [_eve-env slab-off field-offset expected new-val]
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))
        idx (unsigned-bit-shift-right (+ base field-offset) 2)]
    (== expected
        (js/Atomics.compareExchange (js/Int32Array. sab)
                                    idx expected new-val))))

;;-----------------------------------------------------------------------------
;; Eve-type field reads/writes
;;-----------------------------------------------------------------------------

(defn read-eve-type
  "Read an eve-type field: load the int32 offset, wrap in constructor.
   Returns nil if offset is -1 (nil sentinel)."
  [eve-env slab-off field-offset constructor]
  (let [target-offset (alloc/read-i32 slab-off field-offset)]
    (when (not= target-offset -1)
      (constructor eve-env target-offset))))

(defn read-eve-type-volatile
  "Read a volatile eve-type field via Atomics.load."
  [eve-env slab-off field-offset constructor]
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))
        target-offset (js/Atomics.load (js/Int32Array. sab)
                                       (unsigned-bit-shift-right (+ base field-offset) 2))]
    (when (not= target-offset -1)
      (constructor eve-env target-offset))))

(defn write-eve-type!
  "Write an eve-type field: type-tag check + store offset.
   Returns the written value."
  [_eve-env slab-off field-offset expected-type-id ^js val]
  (if (nil? val)
    (do (alloc/write-i32! slab-off field-offset -1)
        nil)
    (let [target-off (.-eve-offset val)
          actual-id (alloc/read-u8 target-off 0)]
      (when (not= actual-id expected-type-id)
        (throw (js/Error. (str "eve set!: expected type-id " expected-type-id
                               ", got " actual-id " at offset " target-off))))
      (alloc/write-i32! slab-off field-offset target-off)
      val)))

(defn write-eve-type-volatile!
  "Write a volatile eve-type field via Atomics.store."
  [_eve-env slab-off field-offset expected-type-id ^js val]
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))]
    (if (nil? val)
      (do (js/Atomics.store (js/Int32Array. sab)
                            (unsigned-bit-shift-right (+ base field-offset) 2)
                            -1)
          nil)
      (let [target-off (.-eve-offset val)
            actual-id (alloc/read-u8 target-off 0)]
        (when (not= actual-id expected-type-id)
          (throw (js/Error. (str "eve set!: expected type-id " expected-type-id
                                 ", got " actual-id " at offset " target-off))))
        (js/Atomics.store (js/Int32Array. sab)
                          (unsigned-bit-shift-right (+ base field-offset) 2)
                          target-off)
        val))))

(defn cas-eve-type!
  "CAS on a volatile eve-type field. expected and new-val are eve-type instances.
   Returns true if successful."
  [_eve-env slab-off field-offset expected-type-id ^js expected-instance ^js new-instance]
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))
        idx (unsigned-bit-shift-right (+ base field-offset) 2)
        expected-off (if (nil? expected-instance) -1 (.-eve-offset expected-instance))
        new-off (if (nil? new-instance) -1 (.-eve-offset new-instance))]
    ;; type-check the new value
    (when (and (some? new-instance) (not= (alloc/read-u8 new-off 0) expected-type-id))
      (throw (js/Error. (str "eve cas!: expected type-id " expected-type-id))))
    (== expected-off
        (js/Atomics.compareExchange (js/Int32Array. sab) idx expected-off new-off))))

;;-----------------------------------------------------------------------------
;; Serialized field reads/writes
;;-----------------------------------------------------------------------------

(defn read-serialized
  "Read a serialized field: load offset, read length-prefixed bytes, decode."
  [eve-env slab-off field-offset]
  (let [data-slab-off (alloc/read-i32 slab-off field-offset)]
    (when (not= data-slab-off -1)
      (let [data-len (alloc/read-i32 data-slab-off 0)
            data-bytes (alloc/read-bytes data-slab-off 4 data-len)]
        (deserialize-value eve-env data-bytes)))))

(defn read-serialized-volatile
  "Read a volatile serialized field via Atomics.load for the offset."
  [eve-env slab-off field-offset]
  ;; For volatile, we need atomic load of the pointer
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))
        data-slab-off (js/Atomics.load (js/Int32Array. sab)
                                       (unsigned-bit-shift-right (+ base field-offset) 2))]
    (when (not= data-slab-off -1)
      (let [data-len (alloc/read-i32 data-slab-off 0)
            data-bytes (alloc/read-bytes data-slab-off 4 data-len)]
        (deserialize-value eve-env data-bytes)))))

(defn write-serialized!
  "Write a serialized field: encode val, alloc block, store offset.
   Returns {:value val :descriptor-idx -1} for GC tracking, or nil."
  [_eve-env slab-off field-offset val]
  (if (nil? val)
    (do (alloc/write-i32! slab-off field-offset -1)
        nil)
    (let [encoded-bytes (serialize-value val)
          byte-len (alength encoded-bytes)
          data-slab-off (alloc/alloc-offset (+ 4 byte-len))]
      ;; Write length + data into the allocated block
      (alloc/write-i32! data-slab-off 0 byte-len)
      (alloc/write-bytes! data-slab-off 4 encoded-bytes)
      ;; Store pointer to data block in the field slot
      (alloc/write-i32! slab-off field-offset data-slab-off)
      {:value val :descriptor-idx -1})))

(defn write-serialized-volatile!
  "Write a volatile serialized field.
   Returns {:value val :descriptor-idx -1} for GC tracking, or nil."
  [_eve-env slab-off field-offset val]
  (let [base (alloc/slab-offset->byte-offset slab-off)
        class-idx (alloc/decode-class-idx slab-off)
        inst (proto-wasm/get-slab-instance class-idx)
        sab (.-buffer ^js (:u8 inst))]
    (if (nil? val)
      (do (js/Atomics.store (js/Int32Array. sab)
                            (unsigned-bit-shift-right (+ base field-offset) 2)
                            -1)
          nil)
      (let [encoded-bytes (serialize-value val)
            byte-len (alength encoded-bytes)
            data-slab-off (alloc/alloc-offset (+ 4 byte-len))]
        ;; Write length + data into the allocated block
        (alloc/write-i32! data-slab-off 0 byte-len)
        (alloc/write-bytes! data-slab-off 4 encoded-bytes)
        ;; Store pointer via Atomics
        (js/Atomics.store (js/Int32Array. sab)
                          (unsigned-bit-shift-right (+ base field-offset) 2)
                          data-slab-off)
        {:value val :descriptor-idx -1}))))

;;-----------------------------------------------------------------------------
;; Type check
;;-----------------------------------------------------------------------------

(defn eve-type-id
  "Read the type-id byte at the given slab-qualified offset."
  [_eve-env slab-off]
  (alloc/read-u8 slab-off 0))
