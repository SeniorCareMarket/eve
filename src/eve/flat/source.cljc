(ns eve.flat.source
  "The field source a generated flat sister reads through (FORMAT-V1 §6,
   lane H5): one record node, or one row of a batch, of a packed chunk.
   eve.flat.chunk provides the two sources; the sister classes
   eve-deftype generates call this protocol from their method bodies, the
   same place the persistent kind calls eve.slab-io on its block.

   A leaf namespace on purpose: the chunk, the sister generator
   (eve.deftype-proto.sio) and eve.flat.sister all depend on it, and it
   depends on nothing.")

(defprotocol IFieldSource
  "What a sister reads its fields from, in place of a slab block."
  (-field [src kw]
    "The value of field kw: a scalar, a plain value, a flat handle, or a
     nested type's sister — the shape the persistent kind's field read of
     the same field answers.")
  (-field-map [src]
    "An array-map of every field keyword to its value, declaration order.")
  (-source-bytes [src]
    "The chunk bytes this source reads."))

(defprotocol ISister
  "A generated flat sister: the packed kind of an eve-deftype."
  (-sister-source [v] "The IFieldSource this sister reads through.")
  (-sister-type-key [v] "The \"ns/Type\" key of the type this is a sister of."))
