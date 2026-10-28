(ns eve.slab-io
  "eve's memory-level seam (adapters/README.md, slab-io; HOST-CONTRACT §12) for
   every host whose slab I/O is a value answering eve's protocols: the JVM,
   babashka, ClojureCLR, jolt, nbb, ClojureScript (whose compiler takes these
   through adapters/slab-io/cljs-compiler), basilisp and clojerl.

   Every name is a macro expanding to the call eve's shared code made before
   the seam — ISlabIO on a domain's slab I/O
   (eve.deftype-proto.alloc/-sio-read-i32 …), IMemRegion on a region
   (eve.mem/-load-i32 …), eve.mem's bitmap helpers — so nothing is added at
   run time. Being macros, they are called, never passed as values. The
   expansions name their namespaces in full: eve.deftype-proto.alloc requires
   this one, so this one cannot require it.

   A slab offset is slab-qualified ([class:3 | block:29]); a field offset is
   a byte offset within its block. A region is an eve.region region; an
   offset in it is a byte offset.")

;; ---------------------------------------------------------------------------
;; A domain's slab I/O
;; ---------------------------------------------------------------------------

(defmacro read-u8
  "The unsigned byte at slab-off + field-off."
  [sio slab-off field-off]
  (list 'eve.deftype-proto.alloc/-sio-read-u8 sio slab-off field-off))

(defmacro read-u16
  "The little-endian u16 at slab-off + field-off."
  [sio slab-off field-off]
  (list 'eve.deftype-proto.alloc/-sio-read-u16 sio slab-off field-off))

(defmacro read-i32
  "The little-endian i32 at slab-off + field-off (any alignment)."
  [sio slab-off field-off]
  (list 'eve.deftype-proto.alloc/-sio-read-i32 sio slab-off field-off))

(defmacro read-bytes
  "The len bytes at slab-off + field-off, as a host byte array (HOST-CONTRACT
   §2.4: valid until the next write to them)."
  [sio slab-off field-off len]
  (list 'eve.deftype-proto.alloc/-sio-read-bytes sio slab-off field-off len))

(defmacro write-u8!
  [sio slab-off field-off v]
  (list 'eve.deftype-proto.alloc/-sio-write-u8! sio slab-off field-off v))

(defmacro write-u16!
  [sio slab-off field-off v]
  (list 'eve.deftype-proto.alloc/-sio-write-u16! sio slab-off field-off v))

(defmacro write-i32!
  [sio slab-off field-off v]
  (list 'eve.deftype-proto.alloc/-sio-write-i32! sio slab-off field-off v))

(defmacro write-bytes!
  "All of byte array src at slab-off + field-off."
  [sio slab-off field-off src]
  (list 'eve.deftype-proto.alloc/-sio-write-bytes! sio slab-off field-off src))

(defmacro cas-i32!
  "Compare-and-exchange on the aligned i32 at slab-off + field-off: the value
   found (success iff it equals expected)."
  [sio slab-off field-off expected desired]
  (list 'eve.deftype-proto.alloc/-sio-cas-i32! sio slab-off field-off expected desired))

(defmacro copy-block!
  "len bytes from src-off + src-field to dst-off + dst-field."
  [sio dst-off dst-field src-off src-field len]
  (list 'eve.deftype-proto.alloc/-sio-copy-block! sio dst-off dst-field src-off src-field len))

(defmacro alloc!
  "A block of at least size-bytes: its slab offset (throws when out of memory)."
  [sio size-bytes]
  (list 'eve.deftype-proto.alloc/-sio-alloc! sio size-bytes))

(defmacro free!
  "The block at slab-off freed: true, or false when it was free already."
  [sio slab-off]
  (list 'eve.deftype-proto.alloc/-sio-free! sio slab-off))

(defmacro mem-region
  "[region byte-off]: the region holding slab-off + field-off and the field's
   offset in it (for the region's own operations, wait and notify)."
  [sio slab-off field-off]
  (list 'eve.deftype-proto.alloc/-sio-mem-region sio slab-off field-off))

;; The fused primitive: the default is the read and the compare the shared
;; code made before it.
(defmacro bytes-equal-at?
  "Do the (alength b) bytes at slab-off + field-off equal byte array b."
  [sio slab-off field-off b]
  (let [bs (with-meta (gensym "b") {:tag 'bytes})]
    (list 'let [bs b]
          (list 'eve.encoding/bytes=
                (list 'eve.deftype-proto.alloc/-sio-read-bytes sio slab-off field-off
                      (list 'eve.host/alength bs))
                bs))))

;; ---------------------------------------------------------------------------
;; A domain: the slab I/O eve's values carry, over the slab context
;; (eve.deftype-proto.alloc's JvmSlabCtx) that holds its regions
;; ---------------------------------------------------------------------------

(defmacro domain
  "The slab I/O of slab context ctx: ctx itself."
  [ctx]
  ctx)

(defmacro domain-ctx
  "The slab context behind a domain's slab I/O: the slab I/O itself."
  [sio]
  sio)

(defmacro regions-changed!
  "ctx's regions were re-mapped (a class grew): nothing to follow."
  [ctx]
  nil)

;; ---------------------------------------------------------------------------
;; A region: the slab allocators' header words and bitmaps
;; ---------------------------------------------------------------------------

(defmacro region-load-i32 [r off] (list 'eve.mem/-load-i32 r off))
(defmacro region-store-i32! [r off v] (list 'eve.mem/-store-i32! r off v))
(defmacro region-cas-i32! [r off expected desired] (list 'eve.mem/-cas-i32! r off expected desired))
(defmacro region-add-i32! [r off delta] (list 'eve.mem/-add-i32! r off delta))
(defmacro region-sub-i32! [r off delta] (list 'eve.mem/-sub-i32! r off delta))
(defmacro region-load-i64 [r off] (list 'eve.mem/-load-i64 r off))
(defmacro region-store-i64! [r off v] (list 'eve.mem/-store-i64! r off v))
(defmacro region-cas-i64! [r off expected desired] (list 'eve.mem/-cas-i64! r off expected desired))

;; The fused primitive: the default is eve.mem's scan, a load per word.
(defmacro bitmap-find-free
  "The first clear bit at or after start-bit of the total-bits bitmap at
   bm-off in r, or -1."
  [r bm-off total-bits start-bit]
  (list 'eve.mem/imr-bitmap-find-free r bm-off total-bits start-bit))

(defmacro bitmap-alloc-cas!
  "bit set 0 -> 1: true, or false when it was set already."
  [r bm-off bit]
  (list 'eve.mem/imr-bitmap-alloc-cas! r bm-off bit))

(defmacro bitmap-free!
  "bit cleared 1 -> 0: true, or false when it was clear already."
  [r bm-off bit]
  (list 'eve.mem/imr-bitmap-free! r bm-off bit))
