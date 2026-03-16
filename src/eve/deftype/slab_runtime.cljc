(ns eve.deftype.slab-runtime
  "Cross-platform runtime helpers for eve-deftype serialized fields.

   Serialized fields store arbitrary Clojure values as length-prefixed byte
   blocks in the slab allocator. The field slot holds a 4-byte slab-qualified
   offset pointing to the serialized data block.

   Layout of serialized data block:
     [length:i32LE][serialized-bytes...]

   These functions are called by macro-generated code in eve-deftype.
   They work transparently across SAB (CLJS) and mmap (JVM) backing stores
   because they use the same slab allocator API as eve-slab-deftype."
  (:require
   [eve.deftype-proto.serialize :as ser]
   #?(:cljs [eve.deftype-proto.alloc :as alloc]
      :clj  [eve.deftype-proto.alloc :as alloc])
   #?(:clj [eve.mem :as mem])))

#?(:cljs
   (do
     (defn slab-read-serialized
       "Read a serialized field from a slab-backed type instance.
        parent-offset: slab-qualified offset of the type instance.
        field-offset: byte offset of the field slot within the instance.
        Returns the deserialized Clojure value, or nil if the slot is -1."
       [parent-offset field-offset]
       (let [p-base (alloc/resolve-dv! parent-offset)
             p-dv   alloc/resolved-dv
             ser-off (.getInt32 p-dv (+ p-base field-offset) true)]
         (when (not= ser-off -1)
           (let [ser-base (alloc/resolve-dv! ser-off)
                 ser-dv   alloc/resolved-dv
                 data-len (.getInt32 ser-dv ser-base true)
                 ;; Copy bytes so we don't hold a view into the slab
                 raw      (js/Uint8Array. data-len)]
             (.set raw (js/Uint8Array. (.-buffer ser-dv) (+ ser-base 4) data-len))
             (ser/deserialize-element nil raw)))))

     (defn slab-write-serialized!
       "Write a serialized field to a slab-backed type instance.
        parent-offset: slab-qualified offset of the type instance.
        field-offset: byte offset of the field slot within the instance.
        val: the Clojure value to serialize and store.
        Returns the slab-qualified offset of the serialized data block."
       [parent-offset field-offset val]
       (if (nil? val)
         ;; Write -1 sentinel
         (let [p-base (alloc/resolve-dv! parent-offset)
               p-dv   alloc/resolved-dv]
           (.setInt32 p-dv (+ p-base field-offset) -1 true)
           -1)
         ;; Serialize, allocate block, write bytes, store offset
         (let [encoded  (ser/serialize-element val)
               byte-len (alength encoded)
               ser-off  (alloc/alloc-offset (+ 4 byte-len))
               ser-base (alloc/resolve-dv! ser-off)
               ser-dv   alloc/resolved-dv]
           ;; Write length + bytes into the serialized data block
           (.setInt32 ser-dv ser-base byte-len true)
           (.set (js/Uint8Array. (.-buffer ser-dv) (+ ser-base 4) byte-len)
                 encoded 0)
           ;; Write the block offset into the parent type's field slot
           (let [p-base (alloc/resolve-dv! parent-offset)
                 p-dv   alloc/resolved-dv]
             (.setInt32 p-dv (+ p-base field-offset) ser-off true))
           ser-off)))

     (defn slab-free-serialized!
       "Free a serialized field's data block.
        parent-offset: slab-qualified offset of the type instance.
        field-offset: byte offset of the field slot within the instance."
       [parent-offset field-offset]
       (let [p-base (alloc/resolve-dv! parent-offset)
             p-dv   alloc/resolved-dv
             ser-off (.getInt32 p-dv (+ p-base field-offset) true)]
         (when (not= ser-off -1)
           (alloc/free! ser-off))))))

#?(:clj
   (do
     (defn slab-read-serialized
       "Read a serialized field from a slab-backed type (JVM).
        sio: ISlabIO instance.
        slab-off: slab offset of the type instance.
        field-offset: byte offset of the field slot."
       [sio slab-off field-offset]
       (let [ser-off (alloc/-sio-read-i32 sio slab-off field-offset)]
         (when (not= ser-off -1)
           (let [data-len (alloc/-sio-read-i32 sio ser-off 0)
                 raw      (alloc/-sio-read-bytes sio ser-off 4 data-len)]
             (mem/eve-bytes->value raw)))))

     (defn slab-write-serialized!
       "Write a serialized field (JVM).
        sio: ISlabIO instance.
        slab-off: slab offset of the type instance.
        field-offset: byte offset of the field slot.
        val: the Clojure value to serialize and store.
        Returns the slab-qualified offset of the serialized data block."
       [sio slab-off field-offset val]
       (if (nil? val)
         ;; Write -1 sentinel
         (do (alloc/-sio-write-i32! sio slab-off field-offset -1)
             -1)
         ;; Serialize, allocate block, write bytes, store offset
         (let [^bytes encoded (mem/value+sio->eve-bytes sio val)
               byte-len      (alength encoded)
               ser-off       (alloc/-sio-alloc! sio (+ 4 byte-len))]
           ;; Write length + bytes into the serialized data block
           (alloc/-sio-write-i32! sio ser-off 0 byte-len)
           (alloc/-sio-write-bytes! sio ser-off 4 encoded)
           ;; Write the block offset into the parent type's field slot
           (alloc/-sio-write-i32! sio slab-off field-offset ser-off)
           ser-off)))

     (defn slab-free-serialized!
       "Free a serialized field's data block (JVM)."
       [sio slab-off field-offset]
       (let [ser-off (alloc/-sio-read-i32 sio slab-off field-offset)]
         (when (not= ser-off -1)
           (alloc/-sio-free! sio ser-off))))))
