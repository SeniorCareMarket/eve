(ns eve.codec
  "eve's codec on every host that holds bytes in a byte array (eve.host's
   byte-array — the JVM, babashka, ClojureCLR, basilisp, jolt, clojerl):
   values to EVE bytes and back through eve.host and eve.encoding.
   The interface every eve.codec has is at the end (adapters/README.md);
   eve.deftype-proto.serialize is what the codecs share."
  (:refer-clojure :exclude [aget aset alength byte-array int-array long-array object-array
                            into-array unchecked-byte unchecked-int unchecked-long
                            unchecked-inc-int unsigned-bit-shift-right long double class
                            locking transient persistent! conj! assoc! bytes objects
                            const-bytes const-objects])
  (:require
   [eve.host :refer [aget aset alength byte-array int-array long-array object-array
                     into-array unchecked-byte unchecked-int unchecked-long
                     unchecked-inc-int unsigned-bit-shift-right long double class locking
                     transient persistent! conj! assoc! bytes objects const-bytes
                     const-objects]]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.encoding :as enc]))

(defn encode-sab-pointer
  "Encode a slab pointer: [0xEE 0xDB tag off0 off1 off2 off3], a fresh byte
   array."
  ^bytes [tag offset]
  (let [b (byte-array 7)
        off (long offset)]
    (aset b 0 (unchecked-byte 0xEE))
    (aset b 1 (unchecked-byte 0xDB))
    (aset b 2 (unchecked-byte tag))
    (aset b 3 (unchecked-byte (bit-and off 0xFF)))
    (aset b 4 (unchecked-byte (bit-and (unsigned-bit-shift-right off 8) 0xFF)))
    (aset b 5 (unchecked-byte (bit-and (unsigned-bit-shift-right off 16) 0xFF)))
    (aset b 6 (unchecked-byte (bit-and (unsigned-bit-shift-right off 24) 0xFF)))
    b))

(defn encode-eve-pointer
  "Encode a slab-backed Eve instance as its 7-byte pointer: the tag from
   -sab-tag, the offset from -direct-serialize."
  [eve-inst]
  (let [offset (d/-direct-serialize eve-inst)
        tag-kw (d/-sab-tag eve-inst)
        fast-tag (get @ser/sab-tag->fast-tag* tag-kw)]
    (when-not fast-tag
      (throw (ex-info "encode-eve-pointer: no pointer tag registered for -sab-tag"
                      {:sab-tag tag-kw})))
    (encode-sab-pointer fast-tag offset)))


;; EVE binary format — magic prefix bytes written into serialized byte arrays.
(def ^:private ^:const MAGIC-0 (unchecked-byte 0xEE))
(def ^:private ^:const MAGIC-1 (unchecked-byte 0xDB))
;; Byte-typed write-side constants for value->eve-bytes
(def ^:private ^:const TAG-FALSE          (unchecked-byte 0x01))
(def ^:private ^:const TAG-TRUE           (unchecked-byte 0x02))
(def ^:private ^:const TAG-INT32          (unchecked-byte 0x03))
(def ^:private ^:const TAG-FLOAT64        (unchecked-byte 0x04))
(def ^:private ^:const TAG-STRING-SHORT   (unchecked-byte 0x05))
(def ^:private ^:const TAG-STRING-LONG    (unchecked-byte 0x06))
(def ^:private ^:const TAG-KEYWORD-SHORT  (unchecked-byte 0x07))
(def ^:private ^:const TAG-KEYWORD-LONG   (unchecked-byte 0x08))
(def ^:private ^:const TAG-KW-NS-SHORT    (unchecked-byte 0x09))
(def ^:private ^:const TAG-KW-NS-LONG     (unchecked-byte 0x0A))
(def ^:private ^:const TAG-UUID           (unchecked-byte 0x0B))
(def ^:private ^:const TAG-SYMBOL-SHORT   (unchecked-byte 0x0C))
(def ^:private ^:const TAG-SYM-NS-SHORT   (unchecked-byte 0x0D))
(def ^:private ^:const TAG-DATE           (unchecked-byte 0x0E))
(def ^:private ^:const TAG-INT64          (unchecked-byte 0x0F))
(def ^:private ^:const TAG-CHAR           (unchecked-byte 0x14))
(def ^:private ^:const TAG-REF            (unchecked-byte 0x15))
(def ^:private ^:const TAG-BIGINT         (unchecked-byte 0x16))
(def ^:private ^:const TAG-RATIO          (unchecked-byte 0x17))
(def ^:private ^:const TAG-BIGDEC         (unchecked-byte 0x18))
;; Flat collection tags — cross-process binary encoding (no SAB pointers)
(def ^:private ^:const TAG-FLAT-MAP       (unchecked-byte 0xED))
(def ^:private ^:const TAG-FLAT-SET       (unchecked-byte 0xEE))
(def ^:private ^:const TAG-FLAT-VEC       (unchecked-byte 0xEF))


;; --- OBJ-7: Keyword serialization cache ---
;; keyword → bytes, mirroring CLJS kw-ser-cache (a thread-safe host map on
;; the JVM, enc/new-cache). Emptied when it passes KW_CACHE_MAX.

(def ^:private kw-encode-cache (enc/new-cache))

(def ^:private ^:const KW_CACHE_MAX 4096)

;; --- EVE binary format — manual LE byte helpers (no ByteBuffer alloc) ---

(defn- read-u8 ^long [^bytes b ^long i]
  (bit-and (aget b i) 0xFF))

(defn- read-u32-le ^long [^bytes b ^long i]
  (bit-or (bit-and (long (aget b i)) 0xFF)
          (bit-shift-left (bit-and (long (aget b (+ i 1))) 0xFF) 8)
          (bit-shift-left (bit-and (long (aget b (+ i 2))) 0xFF) 16)
          (bit-shift-left (bit-and (long (aget b (+ i 3))) 0xFF) 24)))

(defn- read-i32-le ^long [^bytes b ^long i]
  (let [u (read-u32-le b i)]
    (if (>= u 0x80000000)
      (- u 0x100000000)
      u)))

(defn- read-i64-le ^long [^bytes b ^long i]
  (bit-or (bit-and (long (aget b i)) 0xFF)
          (bit-shift-left (bit-and (long (aget b (+ i 1))) 0xFF) 8)
          (bit-shift-left (bit-and (long (aget b (+ i 2))) 0xFF) 16)
          (bit-shift-left (bit-and (long (aget b (+ i 3))) 0xFF) 24)
          (bit-shift-left (bit-and (long (aget b (+ i 4))) 0xFF) 32)
          (bit-shift-left (bit-and (long (aget b (+ i 5))) 0xFF) 40)
          (bit-shift-left (bit-and (long (aget b (+ i 6))) 0xFF) 48)
          (bit-shift-left (long (aget b (+ i 7))) 56)))

(defn- read-f64-le ^double [^bytes b ^long i]
  (enc/long-bits->double (read-i64-le b i)))

;; --- LE write helpers (eliminates ByteBuffer alloc for fixed-size types) ---

(defn- put-i32-le! [^bytes b ^long off ^long v]
  (aset b off       (unchecked-byte v))
  (aset b (+ off 1) (unchecked-byte (unsigned-bit-shift-right v 8)))
  (aset b (+ off 2) (unchecked-byte (unsigned-bit-shift-right v 16)))
  (aset b (+ off 3) (unchecked-byte (unsigned-bit-shift-right v 24))))

(defn- put-i64-le! [^bytes b ^long off ^long v]
  (aset b off       (unchecked-byte v))
  (aset b (+ off 1) (unchecked-byte (unsigned-bit-shift-right v 8)))
  (aset b (+ off 2) (unchecked-byte (unsigned-bit-shift-right v 16)))
  (aset b (+ off 3) (unchecked-byte (unsigned-bit-shift-right v 24)))
  (aset b (+ off 4) (unchecked-byte (unsigned-bit-shift-right v 32)))
  (aset b (+ off 5) (unchecked-byte (unsigned-bit-shift-right v 40)))
  (aset b (+ off 6) (unchecked-byte (unsigned-bit-shift-right v 48)))
  (aset b (+ off 7) (unchecked-byte (unsigned-bit-shift-right v 56))))

(defn- put-f64-le! [^bytes b ^long off ^double v]
  (put-i64-le! b off (enc/double->long-bits v)))

;; --- Cached constant byte arrays for zero-alloc serialization (never
;; written: eve.host's const arrays, which a top-level def holds on every
;; host) ---

(def ^:private BYTES-NIL     (const-bytes []))
(def ^:private BYTES-FALSE   (const-bytes [MAGIC-0 MAGIC-1 TAG-FALSE]))
(def ^:private BYTES-TRUE    (const-bytes [MAGIC-0 MAGIC-1 TAG-TRUE]))

;; Small integer cache [-128, 127] — mirrors java.lang.Integer cache range.
;; Each entry is a pre-built 7-byte INT32 array.
(def ^:private small-int-cache
  (const-objects
   (map (fn [i]
          (let [b (byte-array 7)]
            (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-INT32)
            (put-i32-le! b 3 (- i 128))
            (const-bytes b)))
        (range 256))))

(defn- read-utf8 [^bytes b ^long off ^long len]
  (enc/utf8-string b off len))

(defn- read-twos
  "The integer at off — [len:u32LE][two's complement, big-endian] — and the
   position after it."
  [^bytes b off]
  (let [n (read-u32-le b off)]
    [(enc/twos->integer (mapv #(read-u8 b (+ off 4 %)) (range n))) (+ off 4 n)]))

;; --- EVE binary format — deserializer ---

(defn eve-bytes->value
  "Decode an EVE-format byte array into a Clojure value.

   1-arity [b]: primitives only.
     Handles: nil (0-byte array), boolean, long (int32/int64), double (float64),
     String, Keyword, Symbol, java.util.Date, java.util.UUID.
     Throws UnsupportedOperationException for SAB pointer tags (0x10–0x13).

   3-arity [b sio coll-factory]: also handles collection types.
     sio          — ISlabIO context (e.g. JvmSlabCtx from alloc/make-jvm-slab-ctx)
     coll-factory — (fn [tag sio slab-offset] → collection)
                     tag 0x10 → EveHashMap, 0x11 → EveHashSet,
                     0x12 → SabVec,        0x13 → SabList"
  ([^bytes b] (eve-bytes->value b nil nil))
  ([^bytes b sio coll-factory]
   (let [len (alength b)]
     (cond
       (zero? len)
       nil

       (< len 3)
       (throw (ex-info "EVE bytes truncated" {:len len}))

       :else
       (let [tag (bit-and (aget b 2) 0xFF)]
         (condp = tag
          0x01 false   ; TAG-FALSE
          0x02 true    ; TAG-TRUE
          0x03 (read-i32-le b 3)   ; TAG-INT32
          0x0F (read-i64-le b 3)   ; TAG-INT64
          0x04 (read-f64-le b 3)   ; TAG-FLOAT64
          0x0E (enc/ms->date (long (read-f64-le b 3)))  ; TAG-DATE
          0x14 (char (bit-or (read-u8 b 3) (bit-shift-left (read-u8 b 4) 8)))  ; TAG-CHAR

          0x05  ; TAG-STRING-SHORT
          (read-utf8 b 4 (read-u8 b 3))

          0x06  ; TAG-STRING-LONG
          (read-utf8 b 7 (read-u32-le b 3))

          0x07  ; TAG-KEYWORD-SHORT
          (keyword (read-utf8 b 4 (read-u8 b 3)))

          0x08  ; TAG-KEYWORD-LONG
          (keyword (read-utf8 b 7 (read-u32-le b 3)))

          0x09  ; TAG-KW-NS-SHORT
          (let [ns-len  (read-u8 b 3)
                ns-str  (read-utf8 b 4 ns-len)
                name-off (+ 4 ns-len)
                name-len (read-u8 b name-off)
                name-str (read-utf8 b (+ name-off 1) name-len)]
            (keyword ns-str name-str))

          0x0A  ; TAG-KW-NS-LONG
          (let [ns-len  (read-u32-le b 3)
                ns-str  (read-utf8 b 7 ns-len)
                name-off (+ 7 ns-len)
                name-len (read-u32-le b name-off)
                name-str (read-utf8 b (+ name-off 4) name-len)]
            (keyword ns-str name-str))

          0x0C  ; TAG-SYMBOL-SHORT
          (symbol (read-utf8 b 4 (read-u8 b 3)))

          0x0D  ; TAG-SYM-NS-SHORT
          (let [ns-len  (read-u8 b 3)
                ns-str  (read-utf8 b 4 ns-len)
                name-off (+ 4 ns-len)
                name-len (read-u8 b name-off)
                name-str (read-utf8 b (+ name-off 1) name-len)]
            (symbol ns-str name-str))

          0x0B  ; TAG-UUID — 16 raw bytes in big-endian order
          (enc/bytes->uuid b 3)

          ;; the numbers past a fixed width (FORMAT-DECISIONS.md §8): the host's
          ;; own, or its refusal where it has none
          0x16 (first (read-twos b 3))   ; TAG-BIGINT
          0x17 (let [[n o] (read-twos b 3) [d _] (read-twos b o)] (enc/make-ratio n d))   ; TAG-RATIO
          0x18 (enc/make-bigdec (first (read-twos b 7)) (read-i32-le b 3))   ; TAG-BIGDEC

          ;; a reference (§9): the value this process's table names, or a refusal
          0x15 (ser/handle->ref (read-u32-le b 3) (read-u32-le b 7))   ; TAG-REF

          ;; a value out of line (§10): its block's bytes
          0x19 (eve-bytes->value (ser/read-value-block sio (read-i32-le b 3) (read-u32-le b 7)) sio coll-factory)

          ;; SAB pointer types — collection values in slab memory.
          ;; Prefer registry lookup (mirrors CLJS pattern); fall back to
          ;; legacy coll-factory callback for backward compat.
          0x10 (let [ctor (or (ser/get-type-constructor 0x10)
                              (when coll-factory (fn [off] (coll-factory 0x10 sio off))))]
                 (if ctor (ctor sio (read-i32-le b 3))
                   (throw (enc/unsupported "EVE SAB_MAP: no constructor registered."))))
          0x11 (let [ctor (or (ser/get-type-constructor 0x11)
                              (when coll-factory (fn [off] (coll-factory 0x11 sio off))))]
                 (if ctor (ctor sio (read-i32-le b 3))
                   (throw (enc/unsupported "EVE SAB_SET: no constructor registered."))))
          0x12 (let [ctor (or (ser/get-type-constructor 0x12)
                              (when coll-factory (fn [off] (coll-factory 0x12 sio off))))]
                 (if ctor (ctor sio (read-i32-le b 3))
                   (throw (enc/unsupported "EVE SAB_VEC: no constructor registered."))))
          0x13 (let [ctor (or (ser/get-type-constructor 0x13)
                              (when coll-factory (fn [off] (coll-factory 0x13 sio off))))]
                 (if ctor (ctor sio (read-i32-le b 3))
                   (throw (enc/unsupported "EVE SAB_LIST: no constructor registered."))))
          0x1C (let [ctor (or (ser/get-type-constructor 0x1C)
                              (when coll-factory (fn [off] (coll-factory 0x1C sio off))))]
                 (if ctor (ctor sio (read-i32-le b 3))
                   (throw (enc/unsupported "EVE ARRAY (0x1C): no constructor registered."))))
          0x1D (let [ctor (or (ser/get-type-constructor 0x1D)
                              (when coll-factory (fn [off] (coll-factory 0x1D sio off))))]
                 (if ctor (ctor sio (read-i32-le b 3))
                   (throw (enc/unsupported "EVE ARRAY: no constructor registered."))))
          0x1E (let [ctor (or (ser/get-type-constructor 0x1E)
                              (when coll-factory (fn [off] (coll-factory 0x1E sio off))))]
                 (if ctor (ctor sio (read-i32-le b 3))
                   (throw (enc/unsupported "EVE OBJ: no constructor registered."))))

          ;; Flat map — cross-process binary map encoding
          0xED
          (let [cnt (read-i32-le b 3)]
            (loop [pos 7 i 0 m (transient {})]
              (if (>= i cnt)
                (persistent! m)
                (let [klen (read-i32-le b pos)
                      k    (eve-bytes->value (enc/copy-of-range b (+ pos 4) (+ pos 4 klen)))
                      voff (+ pos 4 klen)
                      vlen (read-i32-le b voff)
                      v    (eve-bytes->value (enc/copy-of-range b (+ voff 4) (+ voff 4 vlen)))]
                  (recur (+ voff 4 vlen) (inc i) (assoc! m k v))))))

          ;; Flat set — cross-process binary set encoding
          0xEE
          (let [cnt (read-i32-le b 3)]
            (loop [pos 7 i 0 s (transient #{})]
              (if (>= i cnt)
                (persistent! s)
                (let [elen (read-i32-le b pos)
                      elem (eve-bytes->value (enc/copy-of-range b (+ pos 4) (+ pos 4 elen)))]
                  (recur (+ pos 4 elen) (inc i) (conj! s elem))))))

          ;; Flat vec — cross-process binary vector encoding
          0xEF
          (let [cnt (read-i32-le b 3)]
            (loop [pos 7 i 0 v (transient [])]
              (if (>= i cnt)
                (persistent! v)
                (let [elen (read-i32-le b pos)
                      elem (eve-bytes->value (enc/copy-of-range b (+ pos 4) (+ pos 4 elen)))]
                  (recur (+ pos 4 elen) (inc i) (conj! v elem))))))

          ;; Any other tag — try the registered JVM type constructors
          ;; (eve-deftype type-ids, e.g. Dataset, register here).
          (let [ctor (ser/get-type-constructor tag)]
            (if ctor
              (ctor sio (read-i32-le b 3))
              (throw (ex-info "Unknown EVE type tag" {:tag tag}))))))))))

;; --- EVE binary format — serializer (primitive types only) ---

(defn- bytes-header-tag
  "Create a 3-byte prefix [0xEE 0xDB tag]. Only used for variable-size types
   that need a fresh array. Fixed-size types use cached constants or put-* helpers."
  ^bytes [tag]
  (doto (byte-array 3) (aset 0 MAGIC-0) (aset 1 MAGIC-1) (aset 2 (unchecked-byte tag))))

(declare value->eve-bytes)

;; --- The numbers past a fixed width (§8) and references (§9) ---

(defn- put-twos!
  "Write [len:u32LE][bs] (bs 0..255 each) into b at pos; the position after."
  [^bytes b pos bs]
  (put-i32-le! b pos (count bs))
  (reduce (fn [p x] (aset b p (unchecked-byte x)) (inc p)) (+ pos 4) bs))

(defn- tagged
  "A fresh byte array of n bytes led by [0xEE 0xDB tag]."
  ^bytes [tag n]
  (doto (byte-array n) (aset 0 (unchecked-byte MAGIC-0)) (aset 1 (unchecked-byte MAGIC-1)) (aset 2 (unchecked-byte tag))))

(defn- bigint->eve-bytes
  "BIGINT: [0xEE][0xDB][0x16][len:u32LE][two's complement, big-endian, minimal]"
  ^bytes [n]
  (let [bs (enc/integer->twos n)
        b (tagged TAG-BIGINT (+ 7 (count bs)))]
    (put-twos! b 3 bs)
    b))

(defn- ratio->eve-bytes
  "RATIO: [0xEE][0xDB][0x17][len:u32LE][numerator][len:u32LE][denominator]"
  ^bytes [r]
  (let [[n d] (enc/ratio-parts r)
        nb (enc/integer->twos n)
        db (enc/integer->twos d)
        b (tagged TAG-RATIO (+ 11 (count nb) (count db)))]
    (put-twos! b (put-twos! b 3 nb) db)
    b))

(defn- bigdec->eve-bytes
  "BIGDEC: [0xEE][0xDB][0x18][scale:i32LE][len:u32LE][unscaled], canonical
   (enc/bigdec-parts: trailing zeros stripped down to scale 0)"
  ^bytes [x]
  (let [[u sc] (enc/bigdec-parts x)
        ub (enc/integer->twos u)
        b (tagged TAG-BIGDEC (+ 11 (count ub)))]
    (put-i32-le! b 3 sc)
    (put-twos! b 7 ub)
    b))

(defn- ref->eve-bytes
  "REF: [0xEE][0xDB][0x15][realm:u32LE][handle:u32LE] — refused while the
   running swap!'s domain or the write in progress is a shared one"
  ^bytes [v]
  (when (ser/shared-domain? d/*domain-sio*) (ser/refuse-reference! v))
  (let [h (ser/ref->handle! v)
        b (tagged TAG-REF 11)]
    (put-i32-le! b 3 (ser/ref-realm))
    (put-i32-le! b 7 h)
    b))

(defn- shared-guarded
  "(f), with ser/*shared-write* bound while sio's domain is a shared one: the
   key encoder, which takes no slab I/O, then refuses a reference at any depth"
  [sio f]
  (if (and (not ser/*shared-write*) (ser/shared-domain? sio))
    (binding [ser/*shared-write* true] (f))
    (f)))

(defn- check-symbol-len!
  "The symbol tags 0x0C/0x0D carry a one-byte length: a name or namespace
   past 255 UTF-8 bytes has no encoding (FORMAT-DECISIONS.md §4). Refuse it
   rather than write len mod 256 (seam map L-40)."
  ^long [v n]
  (when (> (long n) 255)
    (throw (ex-info "eve: symbol segment longer than 255 bytes has no encoding"
                    {:symbol v :bytes n})))
  (long n))

(defn- put-flat-elements!
  "Write each encoded element of es into b from pos, led by its length
   ([len:i32LE][bytes]); the position after the last."
  [^bytes b pos es]
  (reduce (fn [pos ^bytes eb]
            (let [n (alength eb)]
              (put-i32-le! b pos n)
              (enc/arraycopy eb 0 b (+ pos 4) n)
              (+ pos 4 n)))
          pos es))

(defn- flat-set->eve-bytes
  "Encode a Clojure set as a FLAT_SET byte[].
   Format: [0xEE][0xDB][0xEE][count:i32LE]([e-len:i32LE][e-bytes])*
   Elements in ascending order of their bytes (d/compare-bytes) — the
   canonical order every host writes (FORMAT-DECISIONS.md §2)."
  ^bytes [s]
  (let [items   (seq s)
        cnt     (count s)
        encoded (sort d/compare-bytes (mapv value->eve-bytes items))
        body    (reduce + (map #(+ 4 (alength ^bytes %)) encoded))
        b       (byte-array (+ 7 body))]
    (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-FLAT-SET)
    (put-i32-le! b 3 cnt)
    (put-flat-elements! b 7 encoded)
    b))

(defn- flat-vec->eve-bytes
  "Encode a Clojure sequential as a FLAT_VEC byte[].
   Format: [0xEE][0xDB][0xEF][count:i32LE]([e-len:i32LE][e-bytes])*"
  ^bytes [coll]
  (let [items   (seq coll)
        count   (count coll)
        encoded (mapv value->eve-bytes items)
        body    (reduce + (map #(+ 4 (alength ^bytes %)) encoded))
        b       (byte-array (+ 7 body))]
    (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-FLAT-VEC)
    (put-i32-le! b 3 count)
    (put-flat-elements! b 7 encoded)
    b))

(defn- flat-map->eve-bytes
  "Encode a Clojure map as a FLAT_MAP byte[].
   Format: [0xEE][0xDB][0xED][count:i32LE]([k-len:i32LE][k-bytes][v-len:i32LE][v-bytes])*
   Entries in ascending order of their key bytes (d/compare-bytes) — the
   canonical order every host writes (FORMAT-DECISIONS.md §2)."
  ^bytes [m]
  (let [count   (count m)
        encoded (sort-by first d/compare-bytes
                         (mapv (fn [[k v]] [(value->eve-bytes k) (value->eve-bytes v)]) m))
        body    (reduce (fn [acc [kb vb]] (+ acc 4 (alength ^bytes kb) 4 (alength ^bytes vb)))
                        0 encoded)
        b       (byte-array (+ 7 body))]
    (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-FLAT-MAP)
    (put-i32-le! b 3 count)
    (put-flat-elements! b 7 (mapcat identity encoded))
    b))

(defn value->eve-bytes
  "Encode a Clojure value to EVE binary format byte[].

   Supports: nil (→ 0 bytes), Boolean, Long/Integer/Short/Byte (→ INT32 or INT64),
   Double/Float (→ FLOAT64), Character, String, Keyword, Symbol, java.util.Date,
   java.util.UUID.

   Maps are flat-encoded as FLAT_MAP (tag 0xED), sets as FLAT_SET (tag 0xEE);
   sequential collections are flat-encoded as FLAT_VEC (tag 0xEF).  Use value+sio->eve-bytes when HAMT
   allocation in a slab context is required.

   Fixed-size types use manual LE byte writes (no ByteBuffer allocation).
   nil, booleans, and small integers [-128,127] return cached arrays (zero alloc)."
  ^bytes [v]
  (cond
    (nil? v)
    BYTES-NIL

    (boolean? v)
    (if v BYTES-TRUE BYTES-FALSE)

    ;; an integer is written by its magnitude (§8): (= 5 5N) is one key
    (enc/big-integer? v)
    (if (<= -9223372036854775808 v 9223372036854775807)
      (value->eve-bytes (long v))
      (bigint->eve-bytes v))

    (enc/fixed-integer? v)
    (let [n (long v)]
      (if (and (>= n -2147483648) (<= n 2147483647))
        ;; INT32: check small-int cache first
        (if (and (>= n -128) (<= n 127))
          (aget ^objects small-int-cache (+ (int n) 128))
          (let [b (byte-array 7)]
            (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-INT32)
            (put-i32-le! b 3 n)
            b))
        ;; INT64: [0xEE][0xDB][0x0F][i64LE:8]
        (let [b (byte-array 11)]
          (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-INT64)
          (put-i64-le! b 3 n)
          b)))

    (enc/floating? v)
    ;; FLOAT64: [0xEE][0xDB][0x04][f64LE:8]
    (let [b (byte-array 11)]
      (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-FLOAT64)
      (put-f64-le! b 3 (double v))
      b)

    (enc/host-char? v)
    ;; CHAR: [0xEE][0xDB][0x14][utf16:u16LE]
    (let [b (byte-array 5)
          c (long (int (char v)))]
      (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-CHAR)
      (aset b 3 (unchecked-byte (bit-and c 0xFF)))
      (aset b 4 (unchecked-byte (bit-shift-right c 8)))
      b)

    (enc/date? v)
    ;; DATE: [0xEE][0xDB][0x0E][f64LE:8]  (milliseconds since epoch)
    (let [b (byte-array 11)]
      (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-DATE)
      (put-f64-le! b 3 (double (enc/date-ms v)))
      b)

    (string? v)
    (let [^bytes utf8 (enc/utf8-bytes v)
          slen (alength utf8)]
      (if (<= slen 255)
        ;; STRING_SHORT: [0xEE][0xDB][0x05][len:u8][utf8]
        (let [b (byte-array (+ 4 slen))]
          (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-STRING-SHORT)
          (aset b 3 (unchecked-byte slen))
          (enc/arraycopy utf8 0 b 4 slen) b)
        ;; STRING_LONG: [0xEE][0xDB][0x06][len:u32LE][utf8]
        (let [b (byte-array (+ 7 slen))]
          (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-STRING-LONG)
          (put-i32-le! b 3 (long slen))
          (enc/arraycopy utf8 0 b 7 slen) b)))

    (keyword? v)
    (or (enc/cache-get kw-encode-cache v)
        (let [ns-str  (namespace v)
              nm-str  (name v)
              result
              (if (nil? ns-str)
                (let [^bytes utf8 (enc/utf8-bytes nm-str)
                      slen (alength utf8)]
                  (if (<= slen 255)
                    (let [b (byte-array (+ 4 slen))]
                      (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-KEYWORD-SHORT)
                      (aset b 3 (unchecked-byte slen))
                      (enc/arraycopy utf8 0 b 4 slen) b)
                    (let [b (byte-array (+ 7 slen))]
                      (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-KEYWORD-LONG)
                      (put-i32-le! b 3 (long slen))
                      (enc/arraycopy utf8 0 b 7 slen) b)))
                (let [^bytes ns-utf8 (enc/utf8-bytes ns-str)
                      ^bytes nm-utf8 (enc/utf8-bytes nm-str)
                      ns-len (alength ns-utf8)
                      nm-len (alength nm-utf8)]
                  (if (and (<= ns-len 255) (<= nm-len 255))
                    (let [b (byte-array (+ 5 ns-len nm-len))]
                      (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-KW-NS-SHORT)
                      (aset b 3 (unchecked-byte ns-len))
                      (enc/arraycopy ns-utf8 0 b 4 ns-len)
                      (aset b (+ 4 ns-len) (unchecked-byte nm-len))
                      (enc/arraycopy nm-utf8 0 b (+ 5 ns-len) nm-len) b)
                    (let [b (byte-array (+ 11 ns-len nm-len))]
                      (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-KW-NS-LONG)
                      (put-i32-le! b 3 (long ns-len))
                      (enc/arraycopy ns-utf8 0 b 7 ns-len)
                      (put-i32-le! b (+ 7 ns-len) (long nm-len))
                      (enc/arraycopy nm-utf8 0 b (+ 11 ns-len) nm-len) b))))]
          (enc/cache-put! kw-encode-cache v result KW_CACHE_MAX)))

    (symbol? v)
    (let [ns-str (namespace v)
          nm-str (name v)]
      (if (nil? ns-str)
        ;; Simple symbol
        (let [^bytes utf8 (enc/utf8-bytes nm-str)
              slen (check-symbol-len! v (alength utf8))
              b    (byte-array (+ 4 slen))]
          (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-SYMBOL-SHORT)
          (aset b 3 (unchecked-byte slen))
          (enc/arraycopy utf8 0 b 4 slen) b)
        ;; Namespaced symbol
        (let [^bytes ns-utf8 (enc/utf8-bytes ns-str)
              ^bytes nm-utf8 (enc/utf8-bytes nm-str)
              ns-len (check-symbol-len! v (alength ns-utf8))
              nm-len (check-symbol-len! v (alength nm-utf8))
              b (byte-array (+ 5 ns-len nm-len))]
          (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-SYM-NS-SHORT)
          (aset b 3 (unchecked-byte ns-len))
          (enc/arraycopy ns-utf8 0 b 4 ns-len)
          (aset b (+ 4 ns-len) (unchecked-byte nm-len))
          (enc/arraycopy nm-utf8 0 b (+ 5 ns-len) nm-len) b)))

    (uuid? v)
    ;; UUID: [0xEE][0xDB][0x0B][16 bytes, big-endian (network order)]
    (let [b (byte-array 19)]
      (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-UUID)
      (enc/uuid->bytes! v b 3)
      b)

    (enc/ratio? v)
    (ratio->eve-bytes v)

    (enc/bigdec? v)
    (bigdec->eve-bytes v)

    (map? v)
    (flat-map->eve-bytes v)

    (set? v)
    (flat-set->eve-bytes v)

    (or (vector? v) (sequential? v) (seq? v))
    (flat-vec->eve-bytes v)

    ;; an Eve collection on a host whose core predicates cannot see Eve
    ;; types (jank: map?/set?/vector? are false for them): it carries its
    ;; own plain conversion (d/IPlain) — encode that, whose flat bytes
    ;; are the same canonical bytes the branches above write on a host
    ;; where the predicates can see the type. Without this branch the
    ;; value falls to the by-reference stub, and a digest over a shared
    ;; root silently has no digest at all.
    (satisfies? d/IPlain v)
    (value->eve-bytes (d/-plain v))

    ;; no eve bytes: by reference, in this process only (§9)
    :else
    (ref->eve-bytes v)))

;; --- Collections into the slab ---

;; Collection writers live in eve.deftype-proto.serialize's one registry
;; (register-coll-writer!).
(declare value+sio->eve-bytes)

(defn register-collection-writer!
  "Register a direct slab writer for a collection kind.
   tag    — one of :map, :set, :vec, :list, :array
   writer — (fn [sio serialize-elem coll] → slab-off)"
  [tag writer]
  (ser/register-coll-writer! tag writer))

(defn write-collection!
  "Serialize a Clojure collection to slab via the registered writer.
   Returns the slab-qualified header offset.
   tag — :map, :set, :vec, or :list."
  [tag sio coll]
  (let [writer (ser/get-coll-writer tag)]
    (if writer
      (writer sio (fn [x] (value+sio->eve-bytes sio x)) coll)
      (throw (ex-info (str "write-collection!: no writer for " tag) {:tag tag})))))

(defn value+sio->eve-bytes
  "Serialize v to EVE bytes, allocating collection structures into sio.
   Maps → SAB_MAP pointer (0x10), sets → SAB_SET (0x11),
   vectors → SAB_VEC (0x12), lists → SAB_LIST (0x13).
   Primitives are encoded inline via value->eve-bytes.
   Collection writers must be registered via register-collection-writer!
   before calling this function with collection values.
   Takes an explicit sio — no ambient context."
  (^bytes [sio v]
  (let [writers @ser/coll-writers]
    (cond
      (or (nil? v) (boolean? v) (integer? v) (float? v) (string? v)
          (keyword? v) (symbol? v) (enc/host-char? v)
          (uuid? v) (enc/date? v) (enc/ratio? v) (enc/bigdec? v))
      (value->eve-bytes v)

      ;; Already slab-backed Eve type — return pointer to existing header.
      ;; The tag is the header's own type-id byte, so nested eve-deftype
      ;; values (Dataset, NDBuffer, …) round-trip through the registry.
      ;; The pointer tag comes from the block's own header type-id, never
      ;; from map?/set?: an eve-deftype that implements IPersistentSet
      ;; (the RB tree's SortedSet) is not an EveHashSet. The HAMT map and
      ;; set headers (0xED, 0xEE) take their pointer tags (0x10, 0x11);
      ;; every other type-id (vector 0x12, list 0x13, user types) is its own.
      (satisfies? d/IEveRoot v)
      (let [vs  (d/-eve-sio v)
            _   (when (and vs (not (ser/same-domain? vs sio)))
                  ;; its offset means nothing in sio's domain: written,
                  ;; it would read back as whatever sits there
                  (throw (ex-info (str "An Eve value allocated in a different slab domain cannot be "
                                       "put in this one; build it inside swap! (or with the atom's "
                                       "domain sio, eve.atom/atom-sio)")
                                  {:value-type (str (type v))})))
            off (d/-root-header-off v)
            h   (ser/read-header-tag sio off)
            tag (case (int h) 0xED 0x10 0xEE 0x11 h)]
        ;; an existing piece stored again: a transient's scope stops freeing within itself (9.31)
        (ser/stored-piece! sio)
        (encode-sab-pointer tag off))

      ;; the element writer is a fn, not (partial value+sio->eve-bytes sio): jank 0.1 cannot compile a declared var
      ;; taken as a value inside a closure of its own defn ("unordered_map::at", measured 2026-11-05 ET; the same
      ;; form outside a closure, or an undeclared var, compiles) — the fn is the same on every host
      (map? v)
      (if-let [write-map! (get writers :map)]
        (encode-sab-pointer 0x10 (shared-guarded sio #(write-map! sio (fn [x] (value+sio->eve-bytes sio x)) v)))
        (throw (ex-info "value+sio->eve-bytes: :map writer not registered" {:value v})))

      (set? v)
      (if-let [write-set! (get writers :set)]
        (encode-sab-pointer 0x11 (shared-guarded sio #(write-set! sio (fn [x] (value+sio->eve-bytes sio x)) v)))
        (throw (ex-info "value+sio->eve-bytes: :set writer not registered" {:value v})))

      (list? v)
      (if-let [write-list! (get writers :list)]
        (encode-sab-pointer 0x13 (shared-guarded sio #(write-list! sio (fn [x] (value+sio->eve-bytes sio x)) v)))
        (throw (ex-info "value+sio->eve-bytes: :list writer not registered" {:value v})))

      (or (vector? v) (sequential? v))
      (if-let [write-vec! (get writers :vec)]
        (encode-sab-pointer 0x12 (shared-guarded sio #(write-vec! sio (fn [x] (value+sio->eve-bytes sio x)) v)))
        (throw (ex-info "value+sio->eve-bytes: :vec writer not registered" {:value v})))

      (satisfies? d/IBackingArray v)
      (if-let [write-arr! (get writers :array)]
        (encode-sab-pointer 0x1D (write-arr! sio (d/-backing-subtype v) (d/-backing-array v)))
        (throw (ex-info "value+sio->eve-bytes: :array writer not registered" {:value v})))

      (enc/host-array? v)
      (if-let [write-arr! (get writers :array)]
        (encode-sab-pointer 0x1D (write-arr! sio nil v))
        (throw (ex-info "value+sio->eve-bytes: :array writer not registered" {:value v})))

      ;; no eve bytes: by reference (§9), never into a shared domain
      :else
      (if (ser/shared-domain? sio)
        (ser/refuse-reference! v)
        (ref->eve-bytes v))))))

;; ---------------------------------------------------------------------------
;; eve.codec's interface — every eve.codec has these (adapters/README.md)
;; ---------------------------------------------------------------------------

(defn encode-value
  "v's EVE bytes as a value: a scalar inline, a collection a slab pointer (a
   host collection written into sio)."
  ^bytes [sio v]
  (value+sio->eve-bytes sio v))

(defn encode-val
  "encode-value, for the value of a key/value pair (each call's bytes are its
   own here)."
  ^bytes [sio v]
  (value+sio->eve-bytes sio v))

(defn encode-val-out-of-line
  "encode-val, a value whose bytes pass VALUE_INLINE_MAX written to a block of
   its own: [0xEE 0xDB 0x19][block:i32LE][len:u32LE] (FORMAT-DECISIONS.md §10).
   Not yet what encode-val writes: that switch lands with the retire walk's
   data-value arm, which frees a superseded block (EM2)."
  ^bytes [sio v]
  (let [^bytes bs (value+sio->eve-bytes sio v)
        n (alength bs)]
    (if (<= n ser/VALUE_INLINE_MAX)
      bs
      (let [b (tagged (unchecked-byte ser/FAST_TAG_VALUE_BLOCK) 11)]
        (put-i32-le! b 3 (ser/write-value-block! sio bs))
        (put-i32-le! b 7 n)
        b))))

(defn encode-key
  "k's EVE bytes as a key: a pure function of k's value (a collection flat, in
   canonical order — what the HAMT hashes and compares)."
  ^bytes [k]
  (value->eve-bytes k))

(defn decode
  "The value EVE bytes bs hold (a slab pointer read in sio's domain)."
  [sio bs]
  (eve-bytes->value bs sio nil))

(defn encode-fresh-pointer
  "encode-eve-pointer, for an eve value just built from host data (this codec
   reports a stored piece where it writes an existing eve value's pointer,
   value+sio->eve-bytes, so the two are one)."
  [eve-inst]
  (encode-eve-pointer eve-inst))

(defn set-direct-map-encoder!
  "Nothing: this codec writes a plain map nested in a value through its
   collection writer (register-collection-writer!), not a direct encoder."
  [_encoder]
  nil)
