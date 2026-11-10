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
  (:refer-clojure :exclude [alength bytes])
  (:require
   [eve.host :refer [alength bytes]]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc]
   [eve.codec :as codec]
   [eve.tally :as tally]))

(defn slab-read-serialized
  "Read a serialized field from a slab-backed type instance.
   sio: ISlabIO instance.
   slab-off: slab offset of the type instance.
   field-offset: byte offset of the field slot."
  [sio slab-off field-offset]
  (let [ser-off (slab/read-i32 sio slab-off field-offset)]
    (when (not= ser-off -1)
      (let [data-len (slab/read-i32 sio ser-off 0)
            raw      (slab/read-bytes sio ser-off 4 data-len)]
        (codec/decode sio raw)))))

(defn slab-write-serialized!
  "Write a serialized field.
   sio: ISlabIO instance.
   slab-off: slab offset of the type instance.
   field-offset: byte offset of the field slot.
   val: the Clojure value to serialize and store.
   Returns the slab-qualified offset of the serialized data block."
  [sio slab-off field-offset val]
  (if (nil? val)
    ;; Write -1 sentinel
    (do (slab/write-i32! sio slab-off field-offset -1)
        -1)
    ;; Serialize, allocate block, write bytes, store offset
    (let [encoded (codec/encode-value sio val)
          byte-len      (alength ^bytes encoded)
          ser-off       (slab/alloc! sio (+ 4 byte-len))]
      ;; Write length + bytes into the serialized data block
      (slab/write-i32! sio ser-off 0 byte-len)
      (slab/write-bytes! sio ser-off 4 encoded)
      ;; Write the block offset into the parent type's field slot
      (slab/write-i32! sio slab-off field-offset ser-off)
      ser-off)))

(defn slab-free-serialized!
  "Free a serialized field's data block."
  [sio slab-off field-offset]
  (let [ser-off (slab/read-i32 sio slab-off field-offset)]
    (when (not= ser-off -1)
      (slab/free! sio ser-off))))

(defn ref-stored!
  "Reference field of instance inst went from old to new (offsets; -1 nil) in place: the store rule
   (eve.tally/store!) — a counted instance holds new, then lets old go; kind is what the field references."
  [sio inst old new kind]
  (tally/store! sio inst old new kind))

(defn slab-stored!
  "Reference field field-offset of instance inst was written in place over old: ref-stored! with the slot's new
   offset."
  [sio inst field-offset old kind]
  (ref-stored! sio inst old (slab/read-i32 sio inst field-offset) kind))
