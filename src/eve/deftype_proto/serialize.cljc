(ns eve.deftype-proto.serialize
  "Shared serialization for EVE data structures.

   The codec itself — values to EVE bytes and back — is the host's, in
   eve.codec (eve/adapters/codec/): JavaScript typed arrays on ClojureScript,
   byte arrays on every other host. This namespace is what they share: the
   tags, the registries a type registers its constructors and writers in, the
   slab's default I/O."
  (:require
   [eve.deftype-proto.data :as d]
   [eve.process :as proc]))

;;-----------------------------------------------------------------------------
;; Fast-path type tags (byte after magic prefix 0xEE 0xDB)  — shared
;;-----------------------------------------------------------------------------

(def ^:const FAST_TAG_FALSE 0x01)
(def ^:const FAST_TAG_TRUE 0x02)
(def ^:const FAST_TAG_INT32 0x03)
(def ^:const FAST_TAG_FLOAT64 0x04)
(def ^:const FAST_TAG_STRING_SHORT 0x05)
(def ^:const FAST_TAG_STRING_LONG 0x06)
(def ^:const FAST_TAG_KEYWORD_SHORT 0x07)
(def ^:const FAST_TAG_KEYWORD_LONG 0x08)
(def ^:const FAST_TAG_KEYWORD_NS_SHORT 0x09)
(def ^:const FAST_TAG_KEYWORD_NS_LONG 0x0A)
(def ^:const FAST_TAG_UUID 0x0B)
(def ^:const FAST_TAG_SYMBOL_SHORT 0x0C)
(def ^:const FAST_TAG_SYMBOL_NS_SHORT 0x0D)
(def ^:const FAST_TAG_DATE 0x0E)
(def ^:const FAST_TAG_INT64 0x0F)
;; A character: [0xEE][0xDB][0x14][utf16:u16LE] — a Character on the JVM and
;; bb; ClojureScript has no character type, it reads a one-character string
(def ^:const FAST_TAG_CHAR 0x14)

;; The numbers past a fixed width (FORMAT-DECISIONS.md §8). An integer is
;; written by its magnitude — INT32, then INT64, then BIGINT — so (= 5 5N) is
;; one key; a ratio in lowest terms with a positive denominator; a decimal with
;; its trailing zeros stripped down to scale 0, so (= 1.5M 1.50M) is one key.
;; A big integer's bytes are two's complement, big-endian, minimal.
;; BIGINT: [0xEE][0xDB][0x16][len:u32LE][bytes]
;; RATIO:  [0xEE][0xDB][0x17][len:u32LE][numerator][len:u32LE][denominator]
;; BIGDEC: [0xEE][0xDB][0x18][scale:i32LE][len:u32LE][unscaled]
(def ^:const FAST_TAG_BIGINT 0x16)
(def ^:const FAST_TAG_RATIO 0x17)
(def ^:const FAST_TAG_BIGDEC 0x18)

;; A value eve has no bytes for, by reference (FORMAT-DECISIONS.md §9):
;; [0xEE][0xDB][0x15][realm:u32LE][handle:u32LE] — the handle names the value in
;; this process's reference table (below), the realm is this process's. Never
;; written to a shared domain.
(def ^:const FAST_TAG_REF 0x15)

;; A map entry's value longer than VALUE_INLINE_MAX bytes, out of line
;; (FORMAT-DECISIONS.md §10): [0xEE][0xDB][0x19][block:i32LE][len:u32LE], the
;; block a scalar block [0x01][EVE bytes]
(def ^:const FAST_TAG_VALUE_BLOCK 0x19)
(def ^:const VALUE_INLINE_MAX 64)

;; SAB pointer tags — nested collections stored as SAB data structures.
;; Each encodes a reference to an existing SAB type instance in shared memory.
;; Format: [0xEE][0xDB][tag:u8][instance-offset:i32] = 7 bytes
(def ^:const FAST_TAG_SAB_MAP 0x10)
(def ^:const FAST_TAG_SAB_SET 0x11)
(def ^:const FAST_TAG_SAB_VEC 0x12)
(def ^:const FAST_TAG_SAB_LIST 0x13)

;; Record tag — stores a CLJS record as a SabMap with :eve/record-tag metadata.
;; Format: [0xEE][0xDB][0x1A][sab-map-offset:i32] = 7 bytes
(def ^:const FAST_TAG_RECORD 0x1A)

;; Typed array tag — stores a JS typed array as a flat byte blob.
;; Format: [0xEE][0xDB][0x1B][subtype:u8][byte-length:u32][raw bytes...] = 8 + N bytes
(def ^:const FAST_TAG_TYPED_ARRAY 0x1B)

;; EveArray tag — SAB pointer to a live eve-array (zero-copy wrapper).
;; Format: [0xEE][0xDB][0x1C][block-offset:i32] = 7 bytes.
(def ^:const FAST_TAG_EVE_ARRAY 0x1C)

;; Flat collection tags — self-contained binary encoding for cross-process
;; maps/sets/vecs. No SAB pointer indirection; the same bytes on every host
;; (eve/doc/unification/FORMAT-DECISIONS.md §2).
;; FLAT_MAP: [0xEE][0xDB][0xED][count:i32LE]([k-len:i32LE][k-bytes][v-len:i32LE][v-bytes])* count
;; FLAT_SET: [0xEE][0xDB][0xEE][count:i32LE]([e-len:i32LE][e-bytes])* count
;; FLAT_VEC: [0xEE][0xDB][0xEF][count:i32LE]([e-len:i32LE][e-bytes])* count
;; Map entries are written in ascending order of their key bytes and set
;; elements in ascending order of their bytes (d/compare-bytes); a vector
;; keeps its order.
(def ^:const FAST_TAG_FLAT_MAP 0xED)
(def ^:const FAST_TAG_FLAT_SET 0xEE)
(def ^:const FAST_TAG_FLAT_VEC 0xEF)

;; Scalar block type-id — used for primitive values stored at atom root.
;; Layout: [0x01][EVE-serialized-bytes...]
(def ^:const SCALAR_BLOCK_TYPE_ID 0x01)

;; EveArray slab block type-id — used when an EveArray is stored as atom root.
;; Layout: [0x1D][subtype:u8][pad:u16][count:u32][element-bytes...]
(def ^:const EVE_ARRAY_SLAB_TYPE_ID 0x1D)

;; Obj slab block type-id — used when an Obj (typed object) is stored as atom root.
;; Layout: [0x1E][pad:u8][schema-len:u16][schema-bytes...][field-data aligned to 4...]
(def ^:const EVE_OBJ_SLAB_TYPE_ID 0x1E)

;; Typed array subtype codes
(def ^:const TYPED_ARRAY_UINT8         0x01)
(def ^:const TYPED_ARRAY_INT8          0x02)
(def ^:const TYPED_ARRAY_UINT8_CLAMPED 0x03)
(def ^:const TYPED_ARRAY_INT16         0x04)
(def ^:const TYPED_ARRAY_UINT16        0x05)
(def ^:const TYPED_ARRAY_INT32         0x06)
(def ^:const TYPED_ARRAY_UINT32        0x07)
(def ^:const TYPED_ARRAY_FLOAT32       0x08)
(def ^:const TYPED_ARRAY_FLOAT64       0x09)
(def ^:const TYPED_ARRAY_BIGINT64      0x0A)
(def ^:const TYPED_ARRAY_BIGUINT64     0x0B)

;;-----------------------------------------------------------------------------
;; Type registries — one portable registry on every host.
;;
;; Every constructor takes the slab I/O it reads through: (fn [sio header-off]).
;; On CLJS the sio is the module-slab token (alloc/cljs-sio); on the JVM and bb
;; it is the JvmSlabCtx of the value's domain. The registries are Clojure atoms:
;; thread-safe on the JVM, plain on CLJS and bb, and loadable by bb (the
;; ConcurrentHashMap/js.Map/atom triplet this replaces was the bb load failure
;; of 2026-10 — eve/doc/unification/SEAM-MAP.md L-58).
;;-----------------------------------------------------------------------------

(declare encode-eve-pointer)

;; pointer tag (0x10–0x13, 0x1C, eve-deftype type-ids) → (fn [sio header-off])
(defonce type-constructors (atom {}))

;; header type-id byte (0xED map, 0xEE set, 0x12 vec, 0x13 list, …) → (fn [sio header-off])
;; Byte 0 of a slab block header; the atom root is read through this registry.
(defonce header-constructors (atom {}))

;; header type-id byte → (fn [sio header-off]) that frees every block the root value owns
(defonce header-disposers (atom {}))

;; The domain a slab I/O writes, under any wrapper: alloc's sio-root,
;; registered by alloc, so the codec below it can compare domains
(defonce ^:private sio-root-fn (atom identity))

(defn register-sio-root! [f] (reset! sio-root-fn f))

;; (fn [sio]): a write through sio stored an existing eve value by pointer — registered by alloc, which stops a
;; transient's scope freeing within itself (the piece may be held at another place now; the commit counts it,
;). nil until alloc registers it: clojerl compiles a def's value as a literal, and an atom
;; holding a fn a top-level form made is not one.
(defonce ^:private stored-piece-fn (atom nil))

(defn register-stored-piece! [f] (reset! stored-piece-fn f))

(defn stored-piece!
  "A write through sio stored an existing eve value by pointer."
  [sio]
  (when-let [f @stored-piece-fn] (f sio)))

(defn same-domain?
  "True when slab I/Os a and b write the same domain."
  [a b]
  (let [root @sio-root-fn]
    (identical? (root a) (root b))))

;; (fn [sio] → true when sio's domain is shared beyond this process: files
;; another process maps), registered by alloc; a reference (FAST_TAG_REF) is
;; never written there (FORMAT-DECISIONS.md §9)
;; (a named fn: clojerl compiles a def's value as a literal, and an anonymous fn
;; inside an atom is not one — eve.atom did not load on clojerl; HOST-CONTRACT §10)
(defn- never-shared [_] false)
(defonce ^:private shared-domain-fn (atom never-shared))

(defn register-shared-domain-fn! [f] (reset! shared-domain-fn f))

(defn shared-domain?
  "True when sio writes a domain another process reads."
  [sio]
  (boolean (and (some? sio) (@shared-domain-fn sio))))

;; True while a codec writes into a shared domain: its key encoder, which takes
;; no slab I/O, refuses a reference at any depth through it
(def ^:dynamic *shared-write* false)

;;-----------------------------------------------------------------------------
;; The reference table — what FAST_TAG_REF's handle names (FORMAT-DECISIONS.md §9)
;;-----------------------------------------------------------------------------
;; A value eve has no bytes for (a fn or closure, a regex, a var, a volatile,
;; an atom, a host object) is written as its handle here. One object is one
;; handle, looked up by identity (under the host's identity hash: a host's
;; object may answer no hash of its own — basilisp's volatile). A value stays
;; for the process's life: eve memory may hold its handle anywhere, and
;; nothing here can know when the last copy goes.

(defonce ^:private refs (atom nil))

(defn- fresh-ref-table []
  ;; the realm: 32 random bits, so another process's handles are refused, not
  ;; misread as this one's. Assembled with bit ops, not multiply-accumulate:
  ;; on jank an arithmetic accumulator that crosses 2^31 wraps to a negative
  ;; i32 (its JIT types the intermediates as int), while the codec's wire
  ;; read of the same bits is unsigned (read-u32-le) — a wrapped realm makes
  ;; handle->ref refuse this process's own references. Bit assembly yields
  ;; the unsigned value 0..2^32-1 on every 64-bit-integer host; JavaScript's
  ;; bit ops answer a signed int32, so a top byte >= 0x80 made the realm
  ;; negative there and every reference this process wrote was refused on
  ;; read (nbb: reference-tag / value-buffer tests failed on half the runs) —
  ;; `>>> 0` gives the unsigned value.
  {:realm (let [r (reduce (fn [acc x] (bit-or (bit-shift-left acc 8) x)) 0 (proc/random-bytes 4))]
            #?(:cljs (unsigned-bit-shift-right r 0) :default r))
   :next 1 :by-handle {} :by-ref {}})

(defn- ref-table [] (or @refs (swap! refs #(or % (fresh-ref-table)))))

(defn ref-realm
  "This process's realm: the u32 every reference it writes carries."
  []
  (:realm (ref-table)))

(defn refuse-reference!
  "Throw: v has no eve bytes, so it would be written by reference, and a
   reference never reaches a shared domain (FORMAT-DECISIONS.md §9, C3)."
  [v]
  (throw (ex-info (str "eve: a value with no eve bytes (a fn, a regex, an atom, a host object, ...) "
                       "is held by reference, and a reference never reaches a shared domain")
                  {:value-type (str (type v))})))

(defn- issued [t ih v]
  (some (fn [[x h]] (when (identical? x v) h)) (get (:by-ref t) ih)))

(defn ref->handle!
  "The handle of v in this process's reference table (u32), issued the first
   time. Throws inside a shared-domain write (*shared-write*)."
  [v]
  (when *shared-write* (refuse-reference! v))
  (ref-table)
  (let [ih (proc/identity-hash v)]
    (issued (swap! refs (fn [t]
                          (if (issued t ih v)
                            t
                            (let [h (:next t)]
                              (-> t
                                  (assoc :next (inc h))
                                  (assoc-in [:by-handle h] v)
                                  (update-in [:by-ref ih] (fnil conj []) [v h]))))))
            ih v)))

(defn handle->ref
  "The value a reference names. Throws for another process's realm, or a
   handle this process never issued."
  [realm h]
  (let [t (ref-table)]
    (when (not= realm (:realm t))
      (throw (ex-info "eve: a reference written by another process names nothing in this one"
                      {:realm realm :this-realm (:realm t) :handle h})))
    (let [by-handle (:by-handle t)]
      (if (contains? by-handle h)
        (get by-handle h)
        (throw (ex-info "eve: a reference handle this process never issued" {:handle h}))))))

;; 0xF0–0xFF as the first byte of a slab block are a runtime's own block kinds
;; (FORMAT-DECISIONS.md §7, §9): process-private, never written to a shared
;; domain. eve assigns none (its type ids end at 0xEC, its headers at 0xEF).
(def ^:const PROCESS_PRIVATE_KIND_MIN 0xF0)

(defn process-private-kind?
  "True for a block kind (header byte 0) a runtime keeps to its own process."
  [b]
  (>= (int b) PROCESS_PRIVATE_KIND_MIN))

;; [[pred (fn [sio coll] → IEveRoot)] …] — plain collections → slab values
(defonce builders (atom []))

;; :map/:set/:vec/:list → (fn [sio serialize-elem coll] → header-off) — the
;; direct HAMT/trie writers a codec may write a host collection with (eve.codec)
(defonce coll-writers (atom {}))

;; (fn [v] → the Eve value presenting as v, or nil): registered by a host
;; whose types cannot stand in for one of its own values and so presents an
;; Eve value AS that host value (babashka: an Eve map entry is a
;; clojure.lang.MapEntry there, eve.types/present-map-entry). A codec writes
;; the Eve value behind a presented one (eve.codec value+sio->eve-bytes).
;; nil on a host that presents nothing.
(defonce presentation-backing (atom nil))

(defn presented-backing
  "The Eve value v presents (a host value standing for an Eve value), or nil."
  [v]
  (when-let [f @presentation-backing] (f v)))

;; (fn [] → ISlabIO): the default slab I/O, registered by alloc.cljc at load
(defonce default-sio-fn (atom nil))

;; (fn [sio slab-off] → header type-id byte): registered by alloc.cljc at load,
;; so eve.mem can read a block's tag without requiring alloc (a cycle).
(defonce header-tag-reader (atom nil))

(defn register-type-constructor!
  "Register a constructor for a pointer tag. 2-arity: by pointer tag only.
   3-arity: also by the header type-id byte at block offset 0."
  ([tag ctor-fn]
   (swap! type-constructors assoc (int tag) ctor-fn))
  ([tag header-type-id ctor-fn]
   (swap! type-constructors assoc (int tag) ctor-fn)
   (swap! header-constructors assoc (int header-type-id) ctor-fn)))

;; eve-deftype type-id → {name-hash (fn [sio header-off])}. Types may share a
;; type-id (eve.deftype.registry); the 24-bit name hash in bytes 1-3 of the
;; block picks one.
(defonce deftype-constructors (atom {}))

;; (fn [sio header-off] → the name hash in bytes 1-3), registered by alloc.cljc
(defonce header-name-hash-reader (atom nil))

(defn register-header-name-hash-reader!
  "Register the (fn [sio header-off] → u24 name hash) reader."
  [f]
  (reset! header-name-hash-reader f))

(defn register-deftype-constructor!
  "Register an eve-deftype's constructor under its type-id and name hash.
   While one type holds the type-id, its constructor is the type-id's;
   once two do, the type-id's constructor reads the block's name hash and
   calls the one it names."
  [type-id name-hash ctor-fn]
  (let [by-hash (get (swap! deftype-constructors assoc-in [(int type-id) (int name-hash)] ctor-fn)
                     (int type-id))]
    (register-type-constructor!
     type-id type-id
     (if (== 1 (count by-hash))
       ctor-fn
       (fn [sio header-off]
         (let [h (@header-name-hash-reader sio header-off)]
           (if-let [ctor (get by-hash (int h))]
             (ctor sio header-off)
             (throw (ex-info "eve-deftype: no type with this type-id and name hash"
                             {:type-id type-id :name-hash h :header-off header-off})))))))))

;; eve-deftype type-id → {name-hash [[field-offset kind] …]}: the fields of a
;; type's block that point at other blocks — :ref another eve-deftype's block,
;; :value a serialized value's — so eve.atom walks a stored value of the type
;; as it walks its own collections; :own-walk for a type that walks itself
;; (d/IRetireDiff: an int-map, a sorted collection). Every eve-deftype
;; registers as it is defined.
(defonce deftype-layouts (atom {}))

(defn register-deftype-layout!
  "Register an eve-deftype's pointing fields, [[field-offset kind] …], under
   its type-id and name hash."
  [type-id name-hash fields]
  (swap! deftype-layouts assoc-in [(int type-id) (int name-hash)] fields))

(defn deftype-layout
  "The pointing fields of the eve-deftype block at header-off of sio (its
   type-id byte type-id), or nil when the block is no eve-deftype's."
  [sio header-off type-id]
  (when-let [by-hash (get @deftype-layouts (int type-id))]
    (if (== 1 (count by-hash))
      (val (first by-hash))
      (get by-hash (int (@header-name-hash-reader sio header-off))))))

;; eve-deftype type-id → {name-hash [[field-offset kind] …]}: the fields of an instance that hold a reference — a
;; ^:MyEveType field (another instance's offset, kind :deftype) and a serialized field (the offset of a block
;; [len:u32][EVE bytes], kind :vval) — so eve.tally holds and drops what an instance references (eve.deftype.macros
;; registers every eve-deftype's at load)
(defonce type-refs-registry (atom {}))

(defn register-type-refs!
  "Register the reference fields of the eve-deftype with type-id and name hash: [[field-offset kind] …]."
  [type-id name-hash refs]
  (swap! type-refs-registry assoc-in [(int type-id) (int name-hash)] refs)
  nil)

(defn type-refs
  "The reference fields of the eve-deftype with type-id and name hash, or nil when no such type is loaded here."
  [type-id name-hash]
  (get-in @type-refs-registry [(int type-id) (int name-hash)]))

(defn register-header-constructor!
  "Register a header constructor without touching the pointer-tag entry."
  [header-type-id ctor-fn]
  (swap! header-constructors assoc (int header-type-id) ctor-fn))

(defn get-type-constructor
  "The constructor for a pointer tag, or nil."
  [tag]
  (get @type-constructors (int tag)))

(defn get-header-constructor
  "The constructor for a header type-id byte, or nil."
  [type-id-byte]
  (get @header-constructors (int type-id-byte)))

(defn register-header-disposer!
  "Register (fn [sio header-off]) that frees every slab block a root value of
   this header type-id owns."
  [header-type-id disposer-fn]
  (swap! header-disposers assoc (int header-type-id) disposer-fn))

(defn get-header-disposer
  "The disposer for a header type-id byte, or nil."
  [type-id-byte]
  (get @header-disposers (int type-id-byte)))

(defn register-default-sio!
  "Register (fn [] → ISlabIO), the slab I/O a converter uses when its caller
   passes none (CLJS: the module slab; JVM/bb: none — callers pass their domain)."
  [f]
  (reset! default-sio-fn f))

(defn default-sio
  "The registered default slab I/O, or nil."
  []
  (when-let [f @default-sio-fn] (f)))

(defn register-builder!
  "Register a builder for converting plain collections to slab values.
   pred: (fn [v] → boolean). builder: (fn [sio v] → IEveRoot instance)."
  [pred builder]
  (swap! builders conj [pred builder]))

(defn convert-to-eve
  "Convert a plain value to its slab-backed Eve type through the registered
   builders, allocating in sio. Returns the instance, or nil when no builder
   matches (scalars)."
  [sio v]
  (let [bs @builders
        n (count bs)]
    (loop [i 0]
      (when (< i n)
        (let [[pred builder] (nth bs i)]
          (if (pred v)
            (builder (or sio (default-sio)) v)
            (recur (inc i))))))))

(defn register-coll-writer!
  "Register a direct slab writer for a plain collection kind.
   tag: :map, :set, :vec or :list. writer: (fn [sio serialize-elem coll] → header-off)."
  [tag writer]
  (swap! coll-writers assoc tag writer))

(defn get-coll-writer
  "The direct writer for a collection kind, or nil."
  [tag]
  (get @coll-writers tag))

;; {:read (fn [sio block len] → the len EVE bytes of a value block)
;;  :write (fn [sio bytes] → a new value block's offset)}, registered by alloc
(defonce ^:private value-block-io (atom nil))

(defn register-value-block-io! [m] (reset! value-block-io m))

(defn read-value-block
  "The EVE bytes an out-of-line value's block holds."
  [sio block len]
  ((:read @value-block-io) sio block len))

(defn write-value-block!
  "A new value block holding EVE bytes bs, in sio's domain: its offset."
  [sio bs]
  ((:write @value-block-io) sio bs))

(defn register-header-tag-reader!
  "Register the (fn [sio slab-off] → header type-id byte) reader."
  [f]
  (reset! header-tag-reader f))

(defn read-header-tag
  "Read the type-id byte at the start of a slab block."
  [sio slab-off]
  (if-let [f @header-tag-reader]
    (int (f sio slab-off))
    (throw (ex-info "read-header-tag: no reader registered" {}))))

;;-----------------------------------------------------------------------------
;; Pointer encoding — the 7-byte EVE pointer [0xEE 0xDB tag off:i32LE]
;;-----------------------------------------------------------------------------

;; ISabStorable tag keyword → pointer tag byte. Extended by eve-deftype types
;; at load time via register-sab-fast-tag!.
(defonce sab-tag->fast-tag*
  (atom {:eve-map   FAST_TAG_SAB_MAP
         :hash-set  FAST_TAG_SAB_SET
         :eve-vec   FAST_TAG_SAB_VEC
         :eve-list  FAST_TAG_SAB_LIST
         :eve/array FAST_TAG_EVE_ARRAY}))

(defn register-sab-fast-tag!
  "Register a -sab-tag keyword → pointer tag byte mapping."
  [tag-kw fast-tag-byte]
  (swap! sab-tag->fast-tag* assoc tag-kw fast-tag-byte))
