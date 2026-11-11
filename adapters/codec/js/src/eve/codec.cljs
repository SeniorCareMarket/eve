(ns eve.codec
  "eve's codec on ClojureScript: values to EVE bytes and back in JavaScript
   typed arrays (Uint8Array, DataView, TextEncoder) — fast paths for
   primitives: a keyword cache (keywords are interned, so their bytes are
   kept), reusable scratch buffers for fixed-size types (a subarray view of a
   pre-allocated DataView, no allocation per call), and two of them (A, B) so
   a key and its value can be encoded at once.
   The interface every eve.codec has is at the end (adapters/README.md);
   eve.deftype-proto.serialize is what the codecs share."
  (:require [eve.hamt-util :as hu]
            [eve.numeric :as num]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser
             :refer [FAST_TAG_CHAR FAST_TAG_DATE FAST_TAG_EVE_ARRAY FAST_TAG_FALSE
                     FAST_TAG_FLAT_MAP FAST_TAG_FLAT_SET FAST_TAG_FLAT_VEC FAST_TAG_FLOAT64
                     FAST_TAG_INT32 FAST_TAG_INT64 FAST_TAG_KEYWORD_LONG FAST_TAG_KEYWORD_NS_LONG
                     FAST_TAG_KEYWORD_NS_SHORT FAST_TAG_KEYWORD_SHORT FAST_TAG_RECORD FAST_TAG_SAB_LIST
                     FAST_TAG_SAB_MAP FAST_TAG_STRING_LONG FAST_TAG_STRING_SHORT FAST_TAG_SYMBOL_NS_SHORT
                     FAST_TAG_SYMBOL_SHORT FAST_TAG_TRUE FAST_TAG_TYPED_ARRAY FAST_TAG_UUID
                     TYPED_ARRAY_BIGINT64 TYPED_ARRAY_BIGUINT64 TYPED_ARRAY_FLOAT32 TYPED_ARRAY_FLOAT64
                     TYPED_ARRAY_INT16 TYPED_ARRAY_INT32 TYPED_ARRAY_INT8 TYPED_ARRAY_UINT16
                     TYPED_ARRAY_UINT32 TYPED_ARRAY_UINT8 TYPED_ARRAY_UINT8_CLAMPED convert-to-eve
                     get-type-constructor sab-tag->fast-tag*]]))

;;-----------------------------------------------------------------------------
;; Pointer encoding — the 7-byte EVE pointer [0xEE 0xDB tag off:i32LE]
;;-----------------------------------------------------------------------------

;; One reusable 7-byte scratch buffer (the returned view is consumed — copied
;; into a node — before the next call).
(def ^:private sab-ptr-buf (js/Uint8Array. 7))
(def ^:private sab-ptr-dv (js/DataView. (.-buffer sab-ptr-buf)))

(defn encode-sab-pointer
  "Encode a slab pointer: [0xEE 0xDB tag off0 off1 off2 off3]: a reusable
   scratch view, valid until the next call."
  [tag offset]
  (aset sab-ptr-buf 0 d/DIRECT_MAGIC_0)
  (aset sab-ptr-buf 1 d/DIRECT_MAGIC_1)
  (aset sab-ptr-buf 2 tag)
  (.setInt32 sab-ptr-dv 3 offset true)
  sab-ptr-buf)

(def ^:private ^:mutable encoding-sio
  "The slab I/O of the innermost encode-value / encode-val call running (an
   isolate runs one encode at a time; a nested one restores the outer's on
   return), nil outside one: the nested writers carry no sio."
  nil)

(defn encode-fresh-pointer
  "The 7-byte pointer to an eve value just built from host data, to be held
   here only (nothing to report, 9.31): the tag from -sab-tag, the offset from
   -direct-serialize."
  [eve-inst]
  (let [offset (d/-direct-serialize eve-inst)
        tag-kw (d/-sab-tag eve-inst)
        fast-tag (get @sab-tag->fast-tag* tag-kw)]
    (when-not fast-tag
      (throw (ex-info "encode-eve-pointer: no pointer tag registered for -sab-tag"
                      {:sab-tag tag-kw})))
    (encode-sab-pointer fast-tag offset)))

(defn encode-eve-pointer
  "Encode an existing slab-backed Eve value as its 7-byte pointer, and report
   it stored again (9.31: the swap walks its new value; a transient's scope
   stops freeing within itself) — to the encode call running, else the running
   domain (inside a swap, the attempt's log)."
  [eve-inst]
  (let [sio (or encoding-sio (ser/default-sio))
        vs  (d/-eve-sio eve-inst)]
    (when (and vs (not (ser/same-domain? vs sio)))
      ;; its offset means nothing in sio's domain: written, it would read
      ;; back as whatever sits there (register D8 — the JVM codec's
      ;; value+sio->eve-bytes IEveRoot arm refuses the same case)
      (throw (ex-info (str "An Eve value allocated in a different slab domain cannot be "
                           "put in this one; build it inside swap! (or with the atom's "
                           "domain sio, eve.atom/atom-sio)")
                      {:value-type (str (type eve-inst))})))
    (ser/stored-piece! sio))
  (encode-fresh-pointer eve-inst))

;; Direct map encoder: builds SAB map and returns pointer bytes directly,
;; skipping EveHashMap allocation + protocol dispatch.
;; Set by sab_map.cljs at load time.
(def ^:private ^:mutable direct-map-encoder nil)

(defn set-direct-map-encoder!
  "Set the direct map encoder function. Called by sab_map.cljs at load.
   encoder: (fn [cljs-map] -> Uint8Array) — builds SAB map and returns pointer bytes"
  [encoder]
  (set! direct-map-encoder encoder))

;; Typed array encoder: allocates SAB block, copies bytes, returns 7-byte pointer.
;; Set by sab_map.cljs at load time (needs access to alloc-bytes!).
(def ^:private ^:mutable typed-array-encoder nil)

(defn set-typed-array-encoder!
  "Set the typed array encoder function. Called by sab_map.cljs at load.
   encoder: (fn [typed-array] -> Uint8Array) — allocates SAB and returns pointer bytes"
  [encoder]
  (set! typed-array-encoder encoder))

;; Typed array resolver: resolves a slab-qualified offset to a SAB buffer and byte offset.
;; Set by alloc.cljc at init time (needs access to slab resolution).
;; (fn [slab-offset] -> {:sab ArrayBuffer, :base int}) or nil
(def ^:private ^:mutable typed-array-resolver nil)

(defn set-typed-array-resolver!
  "Set the typed array resolver function. Called by alloc.cljc at init.
   resolver: (fn [slab-qualified-offset] -> {:sab ArrayBuffer :base int}) or nil"
  [resolver]
  (set! typed-array-resolver resolver))

;; Record registries (two-part for encode/decode):
;;   record-tag-by-ctor: constructor-fn → tag-str  (for encoding: look up tag by type)
;;   record-ctor-by-tag: tag-str → map->fn         (for decoding: reconstruct from tag)
(defonce ^:private record-tag-by-ctor (js/Map.))
(defonce ^:private record-ctor-by-tag (js/Map.))

(defn register-record-type!
  "Register a record type for SAB serialization roundtrip.
   record-ctor: the record constructor (e.g., MyRecord)
   tag-str: unique string tag (e.g., \"my.ns/MyRecord\")
   map-fn: (fn [field-map] -> record) — typically map->MyRecord"
  [record-ctor tag-str map-fn]
  (.set record-tag-by-ctor record-ctor tag-str)
  (.set record-ctor-by-tag tag-str map-fn))

(defn- encode-record
  "Encode a CLJS record as a FAST_TAG_RECORD pointer.
   Stores the record's fields + :eve/record-tag in a SabMap.
   Returns nil if the record type is not registered."
  [elem]
  (let [tag-str (.get record-tag-by-ctor (type elem))]
    (when tag-str
      (let [tagged-map (assoc (reduce-kv assoc {} elem) :eve/record-tag tag-str)
            sab-m (convert-to-eve nil tagged-map)]
        (when sab-m
          ;; Get the SAB_MAP encoding, then change the tag byte to RECORD
          (let [bytes (encode-fresh-pointer sab-m)]
            (aset bytes 2 FAST_TAG_RECORD)
            bytes))))))

;;-----------------------------------------------------------------------------
;; Shared resources
;;-----------------------------------------------------------------------------

(def ^:private fast-encoder (js/TextEncoder.))
(def ^:private fast-decoder (js/TextDecoder.))

(defn- decode-text
  "Decode UTF-8 bytes to string. Copies SAB-backed views first since
   TextDecoder.decode rejects SharedArrayBuffer views."
  [u8-view]
  (.decode fast-decoder
    (if (instance? js/SharedArrayBuffer (.-buffer u8-view))
      (js/Uint8Array. u8-view)
      u8-view)))

;; Keyword serialization cache: keywords are interned in CLJS (identical? :foo :foo),
;; so JS Map lookup by reference is O(1). Avoids TextEncoder + allocation per call.
(def ^:private keyword-cache (js/Map.))
(def ^:const ^:private KEYWORD_CACHE_MAX 2048)

;; Keyword deserialization cache: avoids repeated TextDecoder + keyword() during
;; iteration. Keyed by SAB data offset (stable for immutable HAMT nodes).
;; On cache hit, returns the keyword directly — no TextDecoder, no allocation.
(def ^:private kw-deser-cache (js/Map.))
(def ^:const ^:private KW_DESER_CACHE_MAX 16384)

;; Reusable scratch buffers for fixed-size numeric types.
;; Two buffers allow key and value to be serialized simultaneously.
;; Max fixed-size is 19 bytes (UUID), so 32 bytes is generous.
(def ^:private scratch-a-buf (js/ArrayBuffer. 32))
(def ^:private scratch-a-dv (js/DataView. scratch-a-buf))
(def ^:private scratch-a-u8 (js/Uint8Array. scratch-a-buf))
;; Pre-allocated subarray views — eliminates Uint8Array view allocation per call
(def ^:private scratch-a-3 (.subarray scratch-a-u8 0 3))   ;; boolean
(def ^:private scratch-a-7 (.subarray scratch-a-u8 0 7))   ;; int32
(def ^:private scratch-a-11 (.subarray scratch-a-u8 0 11))  ;; int64/float64/date

(def ^:private scratch-b-buf (js/ArrayBuffer. 32))
(def ^:private scratch-b-dv (js/DataView. scratch-b-buf))
(def ^:private scratch-b-u8 (js/Uint8Array. scratch-b-buf))
(def ^:private scratch-b-3 (.subarray scratch-b-u8 0 3))
(def ^:private scratch-b-7 (.subarray scratch-b-u8 0 7))
(def ^:private scratch-b-11 (.subarray scratch-b-u8 0 11))

;;-----------------------------------------------------------------------------
;; Internal: serialize typed array
;;-----------------------------------------------------------------------------

(defn typed-array-subtype
  "Return the subtype code for a JS typed array, or nil if not a typed array."
  [elem]
  (cond
    ;; Uint8ClampedArray must be checked before Uint8Array (it's a subclass)
    (instance? js/Uint8ClampedArray elem)  TYPED_ARRAY_UINT8_CLAMPED
    (instance? js/Uint8Array elem)        TYPED_ARRAY_UINT8
    (instance? js/Int8Array elem)          TYPED_ARRAY_INT8
    (instance? js/Int16Array elem)         TYPED_ARRAY_INT16
    (instance? js/Uint16Array elem)        TYPED_ARRAY_UINT16
    (instance? js/Int32Array elem)         TYPED_ARRAY_INT32
    (instance? js/Uint32Array elem)        TYPED_ARRAY_UINT32
    (instance? js/Float32Array elem)       TYPED_ARRAY_FLOAT32
    (instance? js/Float64Array elem)       TYPED_ARRAY_FLOAT64
    ;; BigInt typed arrays may not be available in all environments
    (and (exists? js/BigInt64Array)  (instance? js/BigInt64Array elem))  TYPED_ARRAY_BIGINT64
    (and (exists? js/BigUint64Array) (instance? js/BigUint64Array elem)) TYPED_ARRAY_BIGUINT64
    :else nil))

(defn- serialize-typed-array
  "Serialize a JS typed array. Uses the SAB-backed encoder (node-backed, 7-byte
   pointer) when available, falling back to inline blob for small arrays.
   Node-backed storage keeps HAMT nodes small — same pattern as maps/vecs/sets."
  [elem]
  (when (some? (typed-array-subtype elem))
    (if typed-array-encoder
      ;; SAB-backed: allocate block, copy bytes, return 7-byte pointer
      (typed-array-encoder elem)
      ;; Fallback for pre-init: inline blob (only used during bootstrap)
      (let [subtype  (typed-array-subtype elem)
            byte-view (js/Uint8Array. (.-buffer elem) (.-byteOffset elem) (.-byteLength elem))
            byte-len  (.-byteLength elem)
            buf       (js/Uint8Array. (+ 8 byte-len))
            dv        (js/DataView. (.-buffer buf))]
        (aset buf 0 d/DIRECT_MAGIC_0)
        (aset buf 1 d/DIRECT_MAGIC_1)
        (aset buf 2 FAST_TAG_TYPED_ARRAY)
        (aset buf 3 subtype)
        (.setUint32 dv 4 byte-len true)
        (.set buf byte-view 8)
        buf))))

;;-----------------------------------------------------------------------------
;; Internal: serialize keyword (cached)
;;-----------------------------------------------------------------------------

(defn- serialize-keyword-impl [elem]
  ;; Check cache first
  (or (.get keyword-cache elem)
  ;; Cache miss — serialize and cache
  (let [result
        (if (namespace elem)
          ;; Namespaced keyword
          (let [ns-str (namespace elem)
                name-str (name elem)
                ns-enc (.encode fast-encoder ns-str)
                name-enc (.encode fast-encoder name-str)
                ns-len (.-length ns-enc)
                name-len (.-length name-enc)]
            (if (and (<= ns-len 255) (<= name-len 255))
              (let [buf (js/Uint8Array. (+ 5 ns-len name-len))]
                (aset buf 0 d/DIRECT_MAGIC_0)
                (aset buf 1 d/DIRECT_MAGIC_1)
                (aset buf 2 FAST_TAG_KEYWORD_NS_SHORT)
                (aset buf 3 ns-len)
                (.set buf ns-enc 4)
                (aset buf (+ 4 ns-len) name-len)
                (.set buf name-enc (+ 5 ns-len))
                buf)
              (let [buf (js/Uint8Array. (+ 11 ns-len name-len))
                    dv (js/DataView. (.-buffer buf))]
                (aset buf 0 d/DIRECT_MAGIC_0)
                (aset buf 1 d/DIRECT_MAGIC_1)
                (aset buf 2 FAST_TAG_KEYWORD_NS_LONG)
                (.setUint32 dv 3 ns-len true)
                (.set buf ns-enc 7)
                (.setUint32 dv (+ 7 ns-len) name-len true)
                (.set buf name-enc (+ 11 ns-len))
                buf)))
          ;; Simple keyword
          (let [s (name elem)
                encoded (.encode fast-encoder s)
                len (.-length encoded)]
            (if (<= len 255)
              (let [buf (js/Uint8Array. (+ 4 len))]
                (aset buf 0 d/DIRECT_MAGIC_0)
                (aset buf 1 d/DIRECT_MAGIC_1)
                (aset buf 2 FAST_TAG_KEYWORD_SHORT)
                (aset buf 3 len)
                (.set buf encoded 4)
                buf)
              (let [buf (js/Uint8Array. (+ 7 len))
                    dv (js/DataView. (.-buffer buf))]
                (aset buf 0 d/DIRECT_MAGIC_0)
                (aset buf 1 d/DIRECT_MAGIC_1)
                (aset buf 2 FAST_TAG_KEYWORD_LONG)
                (.setUint32 dv 3 len true)
                (.set buf encoded 7)
                buf))))]
    ;; Cache the result (evict if cache is too large)
    (when (>= (.-size keyword-cache) KEYWORD_CACHE_MAX)
      (.clear keyword-cache))
    (.set keyword-cache elem result)
    result)))

;;-----------------------------------------------------------------------------
;; Characters — a distinct scalar on every host (FORMAT-DECISIONS.md §4)
;;-----------------------------------------------------------------------------

(deftype EveChar [code]
  ;; ClojureScript has no char type: a one-character string is a string.
  ;; The format keeps char (tag 0x14) and string apart on every host, so a
  ;; CLJS value that means a char is a type of its own, denoted in text by
  ;; the tagged literal #char "a" (the JVM reads that literal as a
  ;; java.lang.Character). `code` is the UTF-16 code unit the format
  ;; carries. Equality is char-to-char only — never to the one-character
  ;; string of the same code — and the hash is eve's HAMT hash of the
  ;; char's canonical key bytes, so a char key hashes here exactly as a
  ;; Character key hashes on the JVM.
  IEquiv
  (-equiv [this other]
    (and (instance? EveChar other) (== code (.-code other))))

  IHash
  (-hash [this]
    (hu/portable-hash-bytes
     (js/Uint8Array. #js [d/DIRECT_MAGIC_0 d/DIRECT_MAGIC_1 FAST_TAG_CHAR
                          (bit-and code 0xFF)
                          (bit-and (bit-shift-right code 8) 0xFF)])))

  IPrintWithWriter
  (-pr-writer [this writer opts]
    (-write writer (str "#char " (pr-str (js/String.fromCharCode code))))))

(defn eve-char
  "The char value the #char tagged literal's payload denotes: s is a
   one-character string, as in #char \"a\". The reader function for the
   'char tag in a readers map. On the JVM the same literal reads as a
   java.lang.Character."
  [s]
  (->EveChar (.charCodeAt s 0)))

(defn eve-char?
  "Is x a char value (an EveChar), as opposed to a one-character string?"
  [x]
  (instance? EveChar x))

(defn- serialize-char
  "An EveChar's EVE bytes [0xEE][0xDB][0x14][utf16:u16LE], nil for any
   other value. A fresh buffer, like the UUID arm's: a key's bytes and a
   value's are alive at the same time, so no scratch buffer is shared."
  [elem]
  (when (instance? EveChar elem)
    (let [buf (js/Uint8Array. 5)
          code (.-code elem)]
      (aset buf 0 d/DIRECT_MAGIC_0)
      (aset buf 1 d/DIRECT_MAGIC_1)
      (aset buf 2 FAST_TAG_CHAR)
      (aset buf 3 (bit-and code 0xFF))
      (aset buf 4 (bit-and (bit-shift-right code 8) 0xFF))
      buf)))

;;-----------------------------------------------------------------------------
;; Internal: serialize to scratch buffer (fixed-size primitives)
;;-----------------------------------------------------------------------------

(defn- serialize-numeric-a
  "Serialize fixed-size primitive to scratch buffer A using pre-allocated views."
  [elem]
  (cond
    (nil? elem)
    (js/Uint8Array. 0)

    (boolean? elem)
    (do (aset scratch-a-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-a-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-a-u8 2 (if elem FAST_TAG_TRUE FAST_TAG_FALSE))
        scratch-a-3)

    (and (number? elem) (js/Number.isInteger elem)
         (>= elem -2147483648) (<= elem 2147483647))
    (do (aset scratch-a-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-a-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-a-u8 2 FAST_TAG_INT32)
        (.setInt32 scratch-a-dv 3 elem true)
        scratch-a-7)

    (and (number? elem) (js/Number.isInteger elem) (js/Number.isSafeInteger elem))
    (do (aset scratch-a-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-a-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-a-u8 2 FAST_TAG_INT64)
        (.setBigInt64 scratch-a-dv 3 (js/BigInt elem) true)
        scratch-a-11)

    (number? elem)
    (do (aset scratch-a-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-a-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-a-u8 2 FAST_TAG_FLOAT64)
        (.setFloat64 scratch-a-dv 3 elem true)
        scratch-a-11)

    (inst? elem)
    (do (aset scratch-a-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-a-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-a-u8 2 FAST_TAG_DATE)
        (.setFloat64 scratch-a-dv 3 (.getTime elem) true)
        scratch-a-11)

    :else nil))

(defn- serialize-numeric-b
  "Serialize fixed-size primitive to scratch buffer B using pre-allocated views."
  [elem]
  (cond
    (nil? elem)
    (js/Uint8Array. 0)

    (boolean? elem)
    (do (aset scratch-b-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-b-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-b-u8 2 (if elem FAST_TAG_TRUE FAST_TAG_FALSE))
        scratch-b-3)

    (and (number? elem) (js/Number.isInteger elem)
         (>= elem -2147483648) (<= elem 2147483647))
    (do (aset scratch-b-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-b-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-b-u8 2 FAST_TAG_INT32)
        (.setInt32 scratch-b-dv 3 elem true)
        scratch-b-7)

    (and (number? elem) (js/Number.isInteger elem) (js/Number.isSafeInteger elem))
    (do (aset scratch-b-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-b-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-b-u8 2 FAST_TAG_INT64)
        (.setBigInt64 scratch-b-dv 3 (js/BigInt elem) true)
        scratch-b-11)

    (number? elem)
    (do (aset scratch-b-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-b-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-b-u8 2 FAST_TAG_FLOAT64)
        (.setFloat64 scratch-b-dv 3 elem true)
        scratch-b-11)

    (inst? elem)
    (do (aset scratch-b-u8 0 d/DIRECT_MAGIC_0)
        (aset scratch-b-u8 1 d/DIRECT_MAGIC_1)
        (aset scratch-b-u8 2 FAST_TAG_DATE)
        (.setFloat64 scratch-b-dv 3 (.getTime elem) true)
        scratch-b-11)

    :else nil))

;;-----------------------------------------------------------------------------
;; Public: serialize-element with two scratch buffers
;;-----------------------------------------------------------------------------

(declare serialize-flat-collection)

;;-----------------------------------------------------------------------------
;; The numbers past a fixed width (FORMAT-DECISIONS.md §8): JavaScript's BigInt
;; (wrapped by eve.numeric so it prints and equals as in Clojure), written by its
;; magnitude (INT32, INT64, else BIGINT); Ratio and BigDecimal are Eve types.
;; References (§9): a value with no eve bytes, by its handle in this process's
;; table, never into a shared domain.
;;-----------------------------------------------------------------------------

(defn- js-bigint? [x] (and (some? x) (identical? (.-constructor x) js/BigInt)))

(def ^:private BI-0 (js/BigInt 0))
(def ^:private BI-1 (js/BigInt 1))
(def ^:private BI-128 (js/BigInt 128))
(def ^:private BI-256 (js/BigInt 256))
(def ^:private BI-I32-MIN (js/BigInt -2147483648))
(def ^:private BI-I32-MAX (js/BigInt 2147483647))
(def ^:private BI-I64-MIN (js/BigInt "-9223372036854775808"))
(def ^:private BI-I64-MAX (js/BigInt "9223372036854775807"))

(defn- bi-pow256 [k] (loop [i 0 p BI-1] (if (< i k) (recur (inc i) (* p BI-256)) p)))

(defn- bigint->twos
  "BigInt n's minimal two's-complement bytes, big-endian."
  [n]
  (let [k (loop [k 1 lim BI-128]
            (if (and (< n lim) (>= n (- lim)))
              k
              (recur (inc k) (* lim BI-256))))
        out (js/Uint8Array. k)]
    ;; BigInt / truncates: the low byte is u - (u / 256) * 256
    (loop [i (dec k) u (if (< n BI-0) (+ n (bi-pow256 k)) n)]
      (when (>= i 0)
        (let [hi (/ u BI-256)]
          (aset out i (js/Number (- u (* hi BI-256))))
          (recur (dec i) hi))))
    out))

(defn- serialize-bigint
  "A BigInt by its magnitude: INT32, INT64, else BIGINT
   [0xEE][0xDB][0x16][len:u32LE][two's complement, big-endian, minimal]."
  [x]
  (cond
    (and (>= x BI-I32-MIN) (<= x BI-I32-MAX))
    (let [buf (js/Uint8Array. 7)]
      (aset buf 0 d/DIRECT_MAGIC_0) (aset buf 1 d/DIRECT_MAGIC_1) (aset buf 2 FAST_TAG_INT32)
      (.setInt32 (js/DataView. (.-buffer buf)) 3 (js/Number x) true)
      buf)

    (and (>= x BI-I64-MIN) (<= x BI-I64-MAX))
    (let [buf (js/Uint8Array. 11)]
      (aset buf 0 d/DIRECT_MAGIC_0) (aset buf 1 d/DIRECT_MAGIC_1) (aset buf 2 FAST_TAG_INT64)
      (.setBigInt64 (js/DataView. (.-buffer buf)) 3 x true)
      buf)

    :else
    (let [tw (bigint->twos x)
          buf (js/Uint8Array. (+ 7 (.-length tw)))]
      (aset buf 0 d/DIRECT_MAGIC_0) (aset buf 1 d/DIRECT_MAGIC_1) (aset buf 2 ser/FAST_TAG_BIGINT)
      (.setUint32 (js/DataView. (.-buffer buf)) 3 (.-length tw) true)
      (.set buf tw 7)
      buf)))


(defn- as-js-bigint
  "A JS BigInt for an eve.numeric bigint, a raw JS BigInt, or an integer."
  [x]
  (cond
    (js-bigint? x) x
    (num/bigint? x) (js/BigInt (num/str-number x))
    (number? x) (js/BigInt (js/Math.trunc x))
    :else (js/BigInt (str x))))

(defn- serialize-ratio
  "RATIO [0xEE][0xDB][0x17][len:u32LE][numerator][len:u32LE][denominator]."
  [r]
  (let [nb (bigint->twos (as-js-bigint (num/numerator r)))
        db (bigint->twos (as-js-bigint (num/denominator r)))
        buf (js/Uint8Array. (+ 11 (.-length nb) (.-length db)))
        dv (js/DataView. (.-buffer buf))]
    (aset buf 0 d/DIRECT_MAGIC_0) (aset buf 1 d/DIRECT_MAGIC_1) (aset buf 2 ser/FAST_TAG_RATIO)
    (.setUint32 dv 3 (.-length nb) true)
    (.set buf nb 7)
    (.setUint32 dv (+ 7 (.-length nb)) (.-length db) true)
    (.set buf db (+ 11 (.-length nb)))
    buf))

(defn- serialize-bigdec
  "BIGDEC [0xEE][0xDB][0x18][scale:i32LE][len:u32LE][unscaled], canonical
   (trailing zeros stripped, a negative scale raised to 0)."
  [d]
  (let [u (as-js-bigint (num/unscaled-value d))
        s (num/scale d)
        ;; strip trailing zeros, then raise a negative scale to 0
        ten (js/BigInt 10)
        [u s] (loop [u u s s]
                (if (and (not= u (js/BigInt 0)) (zero? (- u (* (/ u ten) ten))))
                  (recur (/ u ten) (dec s))
                  [u s]))
        [u s] (if (neg? s)
                (loop [u u k s]
                  (if (neg? k) (recur (* u ten) (inc k)) [u 0]))
                [u s])
        ub (bigint->twos u)
        buf (js/Uint8Array. (+ 11 (.-length ub)))
        dv (js/DataView. (.-buffer buf))]
    (aset buf 0 d/DIRECT_MAGIC_0) (aset buf 1 d/DIRECT_MAGIC_1) (aset buf 2 ser/FAST_TAG_BIGDEC)
    (.setInt32 dv 3 s true)
    (.setUint32 dv 7 (.-length ub) true)
    (.set buf ub 11)
    buf))

(defn- twos->bigint
  "The BigInt at off of u8: [len:u32LE][two's complement, big-endian]."
  [^js u8 off]
  (let [n (.getUint32 (js/DataView. (.-buffer u8) (.-byteOffset u8) (.-byteLength u8)) off true)
        u (loop [i 0 acc BI-0]
            (if (< i n)
              (recur (inc i) (+ (* acc BI-256) (js/BigInt (aget u8 (+ off 4 i)))))
              acc))]
    (if (and (pos? n) (>= (aget u8 (+ off 4)) 128))
      (- u (bi-pow256 n))
      u)))

(defn- serialize-reference
  "[0xEE][0xDB][0x15][realm:u32LE][handle:u32LE]: v by its handle in this
   process's reference table — refused while the module slab is an mmap
   domain's files (one domain per process here, ser/default-sio's)."
  [v]
  (when (ser/shared-domain? (ser/default-sio)) (ser/refuse-reference! v))
  (let [h (ser/ref->handle! v)
        buf (js/Uint8Array. 11)
        dv (js/DataView. (.-buffer buf))]
    (aset buf 0 d/DIRECT_MAGIC_0) (aset buf 1 d/DIRECT_MAGIC_1) (aset buf 2 ser/FAST_TAG_REF)
    (.setUint32 dv 3 (ser/ref-realm) true)
    (.setUint32 dv 7 h true)
    buf))

(defn- decode-tagged-number-or-ref
  "The BIGINT / RATIO / BIGDEC / REF value at off of u8 (its tag at off+2), or
   ::none for another tag."
  [^js u8 off tag]
  (cond
    (== tag ser/FAST_TAG_BIGINT) (num/bigint (twos->bigint u8 (+ off 3)))
    (== tag ser/FAST_TAG_RATIO)
    (let [n (twos->bigint u8 (+ off 3))
          nlen (.getUint32 (js/DataView. (.-buffer u8) (.-byteOffset u8) (.-byteLength u8)) (+ off 3) true)
          d (twos->bigint u8 (+ off 3 4 nlen))]
      (num/ratio n d))
    (== tag ser/FAST_TAG_BIGDEC)
    (let [scale (.getInt32 (js/DataView. (.-buffer u8) (.-byteOffset u8) (.-byteLength u8)) (+ off 3) true)
          u (twos->bigint u8 (+ off 7))]
      (num/bigdec u scale))
    (== tag ser/FAST_TAG_REF)
    (let [dv (js/DataView. (.-buffer u8) (.-byteOffset u8) (.-byteLength u8))]
      (ser/handle->ref (.getUint32 dv (+ off 3) true) (.getUint32 dv (+ off 7) true)))
    :else ::none))

(declare deserialize-element)

(defn- decode-value-block
  "A value out of line (FORMAT-DECISIONS.md §10): its block's bytes, decoded."
  [sio ^js u8 off]
  (let [dv (js/DataView. (.-buffer u8) (.-byteOffset u8) (.-byteLength u8))]
    (deserialize-element sio (ser/read-value-block sio (.getInt32 dv (+ off 3) true) (.getUint32 dv (+ off 7) true)))))

(defn- check-symbol-len!
  "The symbol tags 0x0C/0x0D carry a one-byte length: a name or namespace past
   255 UTF-8 bytes has no encoding (FORMAT-DECISIONS.md §4). Refuse it rather
   than write len mod 256 (seam map L-40)."
  [elem n]
  (when (> n 255)
    (throw (js/Error. (str "eve: symbol segment longer than 255 bytes has no encoding: " elem))))
  n)

(defn- serialize-value-a
  "Serialize an element as a VALUE using scratch buffer A: scalars inline,
   collections as slab pointers (an eve type's own pointer, a CLJS map through
   direct-map-encoder, anything else through convert-to-eve)."
  [elem]
  ;; Keywords: use cache (most common key type)
  (if (keyword? elem)
    (serialize-keyword-impl elem)
    ;; Fixed-size primitives: use scratch A (pre-allocated views)
    (or (serialize-numeric-a elem)
        (serialize-char elem)
        ;; Variable-size types
        (cond
          ;; SAB types — already in shared memory, encode as pointer
          (satisfies? d/ISabStorable elem)
          (d/-sab-encode elem nil)

          ;; Records — must check before map? (records satisfy map?)
          (satisfies? IRecord elem)
          (or (encode-record elem)
              (js/Uint8Array. 0))

          ;; CLJS maps — direct encode (skip builder registry + EveHashMap allocation)
          (and direct-map-encoder (map? elem))
          (direct-map-encoder elem)

          ;; UUID
          (uuid? elem)
          (let [buf (js/Uint8Array. 19)
                uuid-str (.toLowerCase (str elem))]
            (aset buf 0 d/DIRECT_MAGIC_0)
            (aset buf 1 d/DIRECT_MAGIC_1)
            (aset buf 2 FAST_TAG_UUID)
            (let [hex-positions [0 2 4 6 9 11 14 16 19 21 24 26 28 30 32 34]]
              (dotimes [i 16]
                (let [hex-idx (aget (into-array hex-positions) i)
                      byte-val (js/parseInt (.substring uuid-str hex-idx (+ hex-idx 2)) 16)]
                  (aset buf (+ 3 i) byte-val))))
            buf)

          ;; Symbol (namespaced)
          (and (symbol? elem) (namespace elem))
          (let [ns-str (namespace elem)
                name-str (name elem)
                ns-enc (.encode fast-encoder ns-str)
                name-enc (.encode fast-encoder name-str)
                ns-len (check-symbol-len! elem (.-length ns-enc))
                name-len (check-symbol-len! elem (.-length name-enc))
                buf (js/Uint8Array. (+ 5 ns-len name-len))]
            (aset buf 0 d/DIRECT_MAGIC_0)
            (aset buf 1 d/DIRECT_MAGIC_1)
            (aset buf 2 FAST_TAG_SYMBOL_NS_SHORT)
            (aset buf 3 ns-len)
            (.set buf ns-enc 4)
            (aset buf (+ 4 ns-len) name-len)
            (.set buf name-enc (+ 5 ns-len))
            buf)

          ;; Symbol (simple)
          (symbol? elem)
          (let [s (name elem)
                encoded (.encode fast-encoder s)
                len (check-symbol-len! elem (.-length encoded))
                buf (js/Uint8Array. (+ 4 len))]
            (aset buf 0 d/DIRECT_MAGIC_0)
            (aset buf 1 d/DIRECT_MAGIC_1)
            (aset buf 2 FAST_TAG_SYMBOL_SHORT)
            (aset buf 3 len)
            (.set buf encoded 4)
            buf)

          ;; String
          (string? elem)
          (let [encoded (.encode fast-encoder elem)
                len (.-length encoded)]
            (if (<= len 255)
              (let [buf (js/Uint8Array. (+ 4 len))]
                (aset buf 0 d/DIRECT_MAGIC_0)
                (aset buf 1 d/DIRECT_MAGIC_1)
                (aset buf 2 FAST_TAG_STRING_SHORT)
                (aset buf 3 len)
                (.set buf encoded 4)
                buf)
              (let [buf (js/Uint8Array. (+ 7 len))
                    dv (js/DataView. (.-buffer buf))]
                (aset buf 0 d/DIRECT_MAGIC_0)
                (aset buf 1 d/DIRECT_MAGIC_1)
                (aset buf 2 FAST_TAG_STRING_LONG)
                (.setUint32 dv 3 len true)
                (.set buf encoded 7)
                buf)))

          ;; a BigInt, by its magnitude (§8); Ratio / BigDecimal (§8)
          (or (js-bigint? elem) (num/bigint? elem))
          (serialize-bigint (as-js-bigint elem))

          (num/ratio? elem)
          (serialize-ratio elem)

          (num/decimal? elem)
          (serialize-bigdec elem)

          ;; JS typed arrays — serialize as flat byte blob
          (some? (typed-array-subtype elem))
          (serialize-typed-array elem)

          ;; Eve types — already in shared memory, encode as SAB pointer
          (satisfies? d/IDirectSerialize elem)
          (encode-eve-pointer elem)

          ;; Other CLJS collections (sets, vecs, lists) — auto-convert via builder
          :else
          (if-let [sab-inst (convert-to-eve nil elem)]
            (encode-fresh-pointer sab-inst)
            ;; no eve bytes: by reference, in this process only (§9)
            (serialize-reference elem))))))

(defn serialize-key
  "Serialize an element as a KEY using scratch buffer A. A key's bytes are what
   the HAMT hashes and compares, so they are a pure function of the key's
   value: scalars inline; a collection — CLJS or eve, map, set, vector, list —
   flat and in canonical order (FORMAT-DECISIONS.md §2–§3), never a pointer.
   Records keep their record encoding. Use for map keys and set elements."
  [elem]
  (if (keyword? elem)
    (serialize-keyword-impl elem)
    (or (serialize-numeric-a elem)
        (if (and (or (map? elem) (set? elem) (sequential? elem))
                 (not (satisfies? IRecord elem)))
          (serialize-flat-collection elem)
          (serialize-value-a elem)))))

(defn serialize-val
  "Serialize element using scratch buffer B.
   Use for values when serializing key+value pairs simultaneously."
  [elem]
  ;; Keywords can also be values (e.g., enum values)
  (if (keyword? elem)
    (serialize-keyword-impl elem)
    ;; Fixed-size primitives: use scratch B (pre-allocated views)
    (or (serialize-numeric-b elem)
        (serialize-char elem)
        ;; Variable-size types - same as serialize-key but uses scratch B
        (cond
          ;; Eve types — already in shared memory, encode as SAB pointer
          ;; Must check before map? since EveHashMap satisfies map?
          (satisfies? d/ISabStorable elem)
          (encode-eve-pointer elem)

          ;; Records — must check before map? (records satisfy map?)
          (satisfies? IRecord elem)
          (or (encode-record elem)
              (js/Uint8Array. 0))

          ;; CLJS maps — direct encode (skip builder registry + EveHashMap allocation)
          (and direct-map-encoder (map? elem))
          (direct-map-encoder elem)

          (uuid? elem)
          (let [buf (js/Uint8Array. 19)
                uuid-str (.toLowerCase (str elem))]
            (aset buf 0 d/DIRECT_MAGIC_0)
            (aset buf 1 d/DIRECT_MAGIC_1)
            (aset buf 2 FAST_TAG_UUID)
            (let [hex-positions [0 2 4 6 9 11 14 16 19 21 24 26 28 30 32 34]]
              (dotimes [i 16]
                (let [hex-idx (aget (into-array hex-positions) i)
                      byte-val (js/parseInt (.substring uuid-str hex-idx (+ hex-idx 2)) 16)]
                  (aset buf (+ 3 i) byte-val))))
            buf)

          (and (symbol? elem) (namespace elem))
          (let [ns-str (namespace elem)
                name-str (name elem)
                ns-enc (.encode fast-encoder ns-str)
                name-enc (.encode fast-encoder name-str)
                ns-len (check-symbol-len! elem (.-length ns-enc))
                name-len (check-symbol-len! elem (.-length name-enc))
                buf (js/Uint8Array. (+ 5 ns-len name-len))]
            (aset buf 0 d/DIRECT_MAGIC_0)
            (aset buf 1 d/DIRECT_MAGIC_1)
            (aset buf 2 FAST_TAG_SYMBOL_NS_SHORT)
            (aset buf 3 ns-len)
            (.set buf ns-enc 4)
            (aset buf (+ 4 ns-len) name-len)
            (.set buf name-enc (+ 5 ns-len))
            buf)

          (symbol? elem)
          (let [s (name elem)
                encoded (.encode fast-encoder s)
                len (check-symbol-len! elem (.-length encoded))
                buf (js/Uint8Array. (+ 4 len))]
            (aset buf 0 d/DIRECT_MAGIC_0)
            (aset buf 1 d/DIRECT_MAGIC_1)
            (aset buf 2 FAST_TAG_SYMBOL_SHORT)
            (aset buf 3 len)
            (.set buf encoded 4)
            buf)

          (string? elem)
          (let [encoded (.encode fast-encoder elem)
                len (.-length encoded)]
            (if (<= len 255)
              (let [buf (js/Uint8Array. (+ 4 len))]
                (aset buf 0 d/DIRECT_MAGIC_0)
                (aset buf 1 d/DIRECT_MAGIC_1)
                (aset buf 2 FAST_TAG_STRING_SHORT)
                (aset buf 3 len)
                (.set buf encoded 4)
                buf)
              (let [buf (js/Uint8Array. (+ 7 len))
                    dv (js/DataView. (.-buffer buf))]
                (aset buf 0 d/DIRECT_MAGIC_0)
                (aset buf 1 d/DIRECT_MAGIC_1)
                (aset buf 2 FAST_TAG_STRING_LONG)
                (.setUint32 dv 3 len true)
                (.set buf encoded 7)
                buf)))

          ;; a BigInt, by its magnitude (§8); Ratio / BigDecimal (§8)
          (or (js-bigint? elem) (num/bigint? elem))
          (serialize-bigint (as-js-bigint elem))

          (num/ratio? elem)
          (serialize-ratio elem)

          (num/decimal? elem)
          (serialize-bigdec elem)

          ;; JS typed arrays — serialize as flat byte blob
          (some? (typed-array-subtype elem))
          (serialize-typed-array elem)

          ;; Eve types — already in shared memory, encode as SAB pointer
          (satisfies? d/IDirectSerialize elem)
          (encode-eve-pointer elem)

          ;; Other CLJS collections (sets, vecs, lists) — auto-convert via builder
          :else
          (if-let [sab-inst (convert-to-eve nil elem)]
            (encode-fresh-pointer sab-inst)
            ;; no eve bytes: by reference, in this process only (§9)
            (serialize-reference elem))))))

(defn serialize-element
  "Serialize an element as a VALUE (single-use, when only one serialize is
   alive at a time): vector and list elements, scalar roots, deftype fields.
   Uses scratch buffer A. For key+value pairs, use serialize-key/serialize-val."
  [elem]
  (serialize-value-a elem))

;;-----------------------------------------------------------------------------
;; Flat binary collection serialization (cross-process safe, no SAB pointers)
;;-----------------------------------------------------------------------------

(declare serialize-flat-element)

(defn- flat-elements-blob
  "[0xEE][0xDB][tag][count:i32LE]([e-len:i32LE][e-bytes])* over already-encoded
   elements, in the order given."
  [tag encoded]
  (let [cnt       (count encoded)
        body-size (reduce (fn [acc eb] (+ acc 4 (.-length eb))) 0 encoded)
        buf       (js/Uint8Array. (+ 7 body-size))
        dv        (js/DataView. (.-buffer buf))]
    (aset buf 0 d/DIRECT_MAGIC_0)
    (aset buf 1 d/DIRECT_MAGIC_1)
    (aset buf 2 tag)
    (.setInt32 dv 3 cnt true)
    (loop [pos 7 items (seq encoded)]
      (when-let [[eb & rest-items] items]
        (let [elen (.-length eb)]
          (.setInt32 dv pos elen true)
          (.set buf eb (+ pos 4))
          (recur (+ pos 4 elen) (seq rest-items)))))
    buf))

(defn serialize-flat-collection
  "Encode a collection as a self-contained flat binary blob — no slab
   pointers, the same bytes on every host (FORMAT-DECISIONS.md §2):
   FLAT_MAP  [0xEE][0xDB][0xED][count:i32LE]([k-len:i32LE][k-bytes][v-len:i32LE][v-bytes])*
   FLAT_SET  [0xEE][0xDB][0xEE][count:i32LE]([e-len:i32LE][e-bytes])*
   FLAT_VEC  [0xEE][0xDB][0xEF][count:i32LE]([e-len:i32LE][e-bytes])*
   A map's entries are written in ascending order of their key bytes and a
   set's elements in ascending order of their bytes (d/compare-bytes), so two
   writers encode equal values to equal bytes; a vector keeps its order.
   Recursively uses serialize-flat-element."
  [coll]
  (cond
    (map? coll)
    (let [encoded (->> (seq coll)
                       (mapv (fn [[k v]] [(serialize-flat-element k) (serialize-flat-element v)]))
                       (sort-by first d/compare-bytes))
          cnt     (count encoded)
          body-size (reduce (fn [acc [kb vb]]
                              (+ acc 4 (.-length kb) 4 (.-length vb)))
                            0 encoded)
          buf (js/Uint8Array. (+ 7 body-size))  ; 3 header + 4 count + body
          dv  (js/DataView. (.-buffer buf))]
      (aset buf 0 d/DIRECT_MAGIC_0)
      (aset buf 1 d/DIRECT_MAGIC_1)
      (aset buf 2 FAST_TAG_FLAT_MAP)
      (.setInt32 dv 3 cnt true)
      (loop [pos 7 pairs (seq encoded)]
        (when-let [[[kb vb] & rest-pairs] pairs]
          (let [klen (.-length kb)
                vlen (.-length vb)]
            (.setInt32 dv pos klen true)
            (.set buf kb (+ pos 4))
            (.setInt32 dv (+ pos 4 klen) vlen true)
            (.set buf vb (+ pos 4 klen 4))
            (recur (+ pos 4 klen 4 vlen) (seq rest-pairs)))))
      buf)

    (set? coll)
    (flat-elements-blob FAST_TAG_FLAT_SET
                        (sort d/compare-bytes (mapv serialize-flat-element (seq coll))))

    :else
    (flat-elements-blob FAST_TAG_FLAT_VEC
                        (mapv serialize-flat-element (seq coll)))))

(defn serialize-flat-element
  "Serialize one element of a flat collection: a nested map, set or sequential
   flat (recursively), anything else as serialize-element does — copied out of
   its scratch buffer, since the enclosing collection encodes many elements
   before it assembles them."
  [elem]
  (if (or (map? elem) (set? elem) (sequential? elem))
    (serialize-flat-collection elem)
    (js/Uint8Array. (serialize-value-a elem))))

;;-----------------------------------------------------------------------------
;; Deserialization
;;-----------------------------------------------------------------------------

(defn deserialize-element
  "Deserialize bytes back to a CLJS value. sio is the slab I/O the value's
   nested collections are read through (alloc/cljs-sio on CLJS).
   Uses fast-path for primitive types, protocol dispatch for everything else."
  [s-atom-env ^js bytes]
  (let [;; The bytes may arrive as a plain JS array: a host aclone of a
        ;; Uint8Array is one (cljs.core/aclone element-copies into an
        ;; Array), and every arm below needs a real typed array — its
        ;; .buffer for DataView, .subarray for slices. Normalize once,
        ;; here at the entry every decode path shares.
        bytes (if (instance? js/Uint8Array bytes) bytes (js/Uint8Array. bytes))
        len   (.-length bytes)]
    (if (zero? len)
      nil
      ;; Check for fast-path magic prefix
      (if (and (>= len 3)
               (== (aget bytes 0) d/DIRECT_MAGIC_0)
               (== (aget bytes 1) d/DIRECT_MAGIC_1))
        (let [tag (aget bytes 2)]
          (cond
            (== tag FAST_TAG_FALSE) false
            (== tag FAST_TAG_TRUE) true

            (== tag FAST_TAG_INT32)
            (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))]
              (.getInt32 dv 3 true))

            (== tag FAST_TAG_INT64)
            (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))]
              (js/Number (.getBigInt64 dv 3 true)))

            (== tag FAST_TAG_FLOAT64)
            (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))]
              (.getFloat64 dv 3 true))

            (== tag FAST_TAG_STRING_SHORT)
            (let [str-len (aget bytes 3)]
              (decode-text (.subarray bytes 4 (+ 4 str-len))))

            (== tag FAST_TAG_STRING_LONG)
            (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                  str-len (.getUint32 dv 3 true)]
              (decode-text (.subarray bytes 7 (+ 7 str-len))))

            (== tag FAST_TAG_KEYWORD_SHORT)
            (let [str-len (aget bytes 3)]
              (keyword (decode-text (.subarray bytes 4 (+ 4 str-len)))))

            (== tag FAST_TAG_KEYWORD_LONG)
            (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                  str-len (.getUint32 dv 3 true)]
              (keyword (decode-text (.subarray bytes 7 (+ 7 str-len)))))

            (== tag FAST_TAG_KEYWORD_NS_SHORT)
            (let [ns-len (aget bytes 3)
                  ns-str (decode-text (.subarray bytes 4 (+ 4 ns-len)))
                  name-len (aget bytes (+ 4 ns-len))
                  name-str (decode-text (.subarray bytes (+ 5 ns-len) (+ 5 ns-len name-len)))]
              (keyword ns-str name-str))

            (== tag FAST_TAG_KEYWORD_NS_LONG)
            (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                  ns-len (.getUint32 dv 3 true)
                  ns-str (decode-text (.subarray bytes 7 (+ 7 ns-len)))
                  name-len (.getUint32 dv (+ 7 ns-len) true)
                  name-str (decode-text (.subarray bytes (+ 11 ns-len) (+ 11 ns-len name-len)))]
              (keyword ns-str name-str))

            (== tag FAST_TAG_CHAR)
            (->EveChar (bit-or (aget bytes 3) (bit-shift-left (aget bytes 4) 8)))

            (or (== tag ser/FAST_TAG_BIGINT) (== tag ser/FAST_TAG_RATIO)
                (== tag ser/FAST_TAG_BIGDEC) (== tag ser/FAST_TAG_REF))
            (decode-tagged-number-or-ref bytes 0 tag)

            (== tag ser/FAST_TAG_VALUE_BLOCK)
            (decode-value-block s-atom-env bytes 0)

            (== tag FAST_TAG_UUID)
            (let [hex-chars (array)]
              (dotimes [i 16]
                (let [b (aget bytes (+ 3 i))
                      hi (unsigned-bit-shift-right b 4)
                      lo (bit-and b 0xf)]
                  (.push hex-chars (.toString hi 16))
                  (.push hex-chars (.toString lo 16))
                  (when (or (== i 3) (== i 5) (== i 7) (== i 9))
                    (.push hex-chars "-"))))
              (uuid (.join hex-chars "")))

            (== tag FAST_TAG_SYMBOL_SHORT)
            (let [str-len (aget bytes 3)]
              (symbol (decode-text (.subarray bytes 4 (+ 4 str-len)))))

            (== tag FAST_TAG_SYMBOL_NS_SHORT)
            (let [ns-len (aget bytes 3)
                  ns-str (decode-text (.subarray bytes 4 (+ 4 ns-len)))
                  name-len (aget bytes (+ 4 ns-len))
                  name-str (decode-text (.subarray bytes (+ 5 ns-len) (+ 5 ns-len name-len)))]
              (symbol ns-str name-str))

            (== tag FAST_TAG_DATE)
            (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))]
              (js/Date. (.getFloat64 dv 3 true)))

            ;; SAB pointer tags — reconstruct type wrapper from offset
            (and (>= tag FAST_TAG_SAB_MAP) (<= tag FAST_TAG_SAB_LIST))
            (let [ctor (get-type-constructor tag)]
              (when ctor
                (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                      offset (.getInt32 dv 3 true)]
                  (ctor s-atom-env offset))))

            ;; Record tag — reconstruct record from SabMap + :eve/record-tag
            (== tag FAST_TAG_RECORD)
            (let [sab-map-ctor (get-type-constructor FAST_TAG_SAB_MAP)]
              (when sab-map-ctor
                (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                      offset (.getInt32 dv 3 true)
                      sab-m (sab-map-ctor s-atom-env offset)
                      tag-str (get sab-m :eve/record-tag)]
                  (if-let [map-fn (and tag-str (.get record-ctor-by-tag tag-str))]
                    (let [field-map (dissoc (reduce-kv assoc {} sab-m) :eve/record-tag)]
                      (map-fn field-map))
                    sab-m))))

            ;; EveArray pointer — reconstruct via registered constructor
            (== tag FAST_TAG_EVE_ARRAY)
            (let [ctor (get-type-constructor tag)]
              (when ctor
                (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                      block-offset (.getInt32 dv 3 true)]
                  (ctor s-atom-env block-offset))))

            ;; Typed array — SAB pointer format (7 bytes): reconstruct from SAB
            ;; 16-byte header: [subtype:u8][reserved:7][byte-len:u32LE][reserved:4][data at +16]
            ;; Transaction-aware deserialization:
            ;; - In transaction (*parent-atom* bound): return mutable view into SAB
            ;; - Outside transaction: copy bytes to fresh ArrayBuffer (immutable)
            ;; Data is always 16-byte aligned (SIMD-ready), so no alignment check needed.
            (== tag FAST_TAG_TYPED_ARRAY)
            (let [data-len (.-byteLength bytes)]
              (if (== data-len 7)
                ;; SAB pointer format (16-byte header)
                (let [ptr-dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                      slab-offset (.getInt32 ptr-dv 3 true)
                      ;; Try slab resolver first (slab-qualified offsets), then legacy s-atom-env
                      resolved (when typed-array-resolver (typed-array-resolver slab-offset))
                      sab (if resolved
                            (:sab resolved)
                            (when-let [u8 (or (:data-view s-atom-env)
                                              (when-let [^js parent d/*parent-atom*]
                                                (let [env (.-s-atom-env ^js (or (.-parent-atom-domain parent) parent))]
                                                  (:data-view env))))]
                              (.-buffer u8)))
                      raw-base (if resolved (:base resolved) slab-offset)
                      ;; Apply same 16-byte alignment as encoder
                      base-offset (bit-and (+ raw-base 15) (bit-not 15))]
                  (when sab
                    (let [sab-dv (js/DataView. sab)
                          subtype (.getUint8 sab-dv base-offset)
                          byte-len (.getUint32 sab-dv (+ base-offset 8) true)
                          data-start (+ base-offset 16)
                          in-transaction? (some? d/*parent-atom*)]
                      (if in-transaction?
                        ;; In transaction: return mutable view directly into SAB (always aligned)
                        (case subtype
                          0x01 (js/Uint8Array. sab data-start byte-len)
                          0x02 (js/Int8Array. sab data-start byte-len)
                          0x03 (js/Uint8ClampedArray. sab data-start byte-len)
                          0x04 (js/Int16Array. sab data-start (/ byte-len 2))
                          0x05 (js/Uint16Array. sab data-start (/ byte-len 2))
                          0x06 (js/Int32Array. sab data-start (/ byte-len 4))
                          0x07 (js/Uint32Array. sab data-start (/ byte-len 4))
                          0x08 (js/Float32Array. sab data-start (/ byte-len 4))
                          0x09 (js/Float64Array. sab data-start (/ byte-len 8))
                          0x0A (when (exists? js/BigInt64Array) (js/BigInt64Array. sab data-start (/ byte-len 8)))
                          0x0B (when (exists? js/BigUint64Array) (js/BigUint64Array. sab data-start (/ byte-len 8)))
                          nil)
                        ;; Outside transaction: copy bytes to fresh ArrayBuffer
                        (let [sab-u8 (js/Uint8Array. sab)
                              src (.subarray sab-u8 data-start (+ data-start byte-len))
                              dst (js/Uint8Array. byte-len)]
                          (.set dst src)
                          (let [ab (.-buffer dst)]
                            (case subtype
                              0x01 (js/Uint8Array. ab)
                              0x02 (js/Int8Array. ab)
                              0x03 (js/Uint8ClampedArray. ab)
                              0x04 (js/Int16Array. ab)
                              0x05 (js/Uint16Array. ab)
                              0x06 (js/Int32Array. ab)
                              0x07 (js/Uint32Array. ab)
                              0x08 (js/Float32Array. ab)
                              0x09 (js/Float64Array. ab)
                              0x0A (when (exists? js/BigInt64Array) (js/BigInt64Array. ab))
                              0x0B (when (exists? js/BigUint64Array) (js/BigUint64Array. ab))
                              nil)))))))
                ;; Inline format (8+N bytes) — not currently used in deserialize-element path
                nil))

            ;; Flat map — cross-process binary map encoding
            (== tag FAST_TAG_FLAT_MAP)
            (let [dv    (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                  cnt   (.getInt32 dv 3 true)]
              (loop [pos 7 i 0 m (transient {})]
                (if (>= i cnt)
                  (persistent! m)
                  (let [klen (.getInt32 dv pos true)
                        k    (deserialize-element s-atom-env (.subarray bytes (+ pos 4) (+ pos 4 klen)))
                        voff (+ pos 4 klen)
                        vlen (.getInt32 dv voff true)
                        v    (deserialize-element s-atom-env (.subarray bytes (+ voff 4) (+ voff 4 vlen)))]
                    (recur (+ voff 4 vlen) (inc i) (assoc! m k v))))))

            ;; Flat set — cross-process binary set encoding
            (== tag FAST_TAG_FLAT_SET)
            (let [dv    (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                  cnt   (.getInt32 dv 3 true)]
              (loop [pos 7 i 0 s (transient #{})]
                (if (>= i cnt)
                  (persistent! s)
                  (let [elen (.getInt32 dv pos true)
                        elem (deserialize-element s-atom-env (.subarray bytes (+ pos 4) (+ pos 4 elen)))]
                    (recur (+ pos 4 elen) (inc i) (conj! s elem))))))

            ;; Flat vec — cross-process binary vector encoding
            (== tag FAST_TAG_FLAT_VEC)
            (let [dv    (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                  cnt   (.getInt32 dv 3 true)]
              (loop [pos 7 i 0 v (transient [])]
                (if (>= i cnt)
                  (persistent! v)
                  (let [elen (.getInt32 dv pos true)
                        elem (deserialize-element s-atom-env (.subarray bytes (+ pos 4) (+ pos 4 elen)))]
                    (recur (+ pos 4 elen) (inc i) (conj! v elem))))))

            :else
            ;; Unknown tag — check registered constructors (eve-deftype types)
            (let [ctor (get-type-constructor tag)]
              (when ctor
                (let [dv (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
                      offset (.getInt32 dv 3 true)]
                  (ctor s-atom-env offset))))))
        ;; No magic prefix — unsupported legacy format
        nil))))

;;-----------------------------------------------------------------------------
;; Zero-copy deserialization from SAB DataView (Phase 6 atom optimization)
;;-----------------------------------------------------------------------------

(defn deserialize-from-dv
  "Zero-copy deserialization: reads directly from a js/DataView at the given
   offset+length without creating intermediate Uint8Array copies.
   Handles all fast-path types inline — no allocation for numeric types,
   subarray views (not copies) for string/keyword types.
   For SAB pointer types (map/set/vec/list), constructs the wrapper directly
   from the DataView bytes — true O(1) deref."
  [s-atom-env ^js dv data-offset data-len]
  (if (zero? data-len)
    nil
    (if (and (>= data-len 3)
             (== (.getUint8 dv data-offset) d/DIRECT_MAGIC_0)
             (== (.getUint8 dv (+ data-offset 1)) d/DIRECT_MAGIC_1))
      (let [tag (.getUint8 dv (+ data-offset 2))
            off data-offset]
        (cond
          ;; Boolean — no read needed
          (== tag FAST_TAG_FALSE) false
          (== tag FAST_TAG_TRUE) true

          ;; Numeric — single DataView read, zero allocation
          (== tag FAST_TAG_INT32)
          (.getInt32 dv (+ off 3) true)

          (== tag FAST_TAG_INT64)
          (js/Number (.getBigInt64 dv (+ off 3) true))

          (== tag FAST_TAG_FLOAT64)
          (.getFloat64 dv (+ off 3) true)

          ;; String — subarray view (no copy), TextDecoder
          (== tag FAST_TAG_STRING_SHORT)
          (let [str-len (.getUint8 dv (+ off 3))
                u8 (:data-view s-atom-env)]
            (decode-text (.subarray u8 (+ off 4) (+ off 4 str-len))))

          (== tag FAST_TAG_STRING_LONG)
          (let [str-len (.getUint32 dv (+ off 3) true)
                u8 (:data-view s-atom-env)]
            (decode-text (.subarray u8 (+ off 7) (+ off 7 str-len))))

          ;; Keyword — cached by SAB offset (immutable HAMT nodes never change)
          (== tag FAST_TAG_KEYWORD_SHORT)
          (or (.get kw-deser-cache off)
              (let [str-len (.getUint8 dv (+ off 3))
                    u8 (:data-view s-atom-env)
                    kw (keyword (decode-text (.subarray u8 (+ off 4) (+ off 4 str-len))))]
                (when (>= (.-size kw-deser-cache) KW_DESER_CACHE_MAX) (.clear kw-deser-cache))
                (.set kw-deser-cache off kw)
                kw))

          (== tag FAST_TAG_KEYWORD_LONG)
          (or (.get kw-deser-cache off)
              (let [str-len (.getUint32 dv (+ off 3) true)
                    u8 (:data-view s-atom-env)
                    kw (keyword (decode-text (.subarray u8 (+ off 7) (+ off 7 str-len))))]
                (when (>= (.-size kw-deser-cache) KW_DESER_CACHE_MAX) (.clear kw-deser-cache))
                (.set kw-deser-cache off kw)
                kw))

          (== tag FAST_TAG_KEYWORD_NS_SHORT)
          (or (.get kw-deser-cache off)
              (let [ns-len (.getUint8 dv (+ off 3))
                    u8 (:data-view s-atom-env)
                    ns-str (decode-text (.subarray u8 (+ off 4) (+ off 4 ns-len)))
                    name-len (.getUint8 dv (+ off 4 ns-len))
                    name-str (decode-text (.subarray u8 (+ off 5 ns-len) (+ off 5 ns-len name-len)))
                    kw (keyword ns-str name-str)]
                (when (>= (.-size kw-deser-cache) KW_DESER_CACHE_MAX) (.clear kw-deser-cache))
                (.set kw-deser-cache off kw)
                kw))

          (== tag FAST_TAG_KEYWORD_NS_LONG)
          (or (.get kw-deser-cache off)
              (let [ns-len (.getUint32 dv (+ off 3) true)
                    u8 (:data-view s-atom-env)
                    ns-str (decode-text (.subarray u8 (+ off 7) (+ off 7 ns-len)))
                    name-len (.getUint32 dv (+ off 7 ns-len) true)
                    name-str (decode-text (.subarray u8 (+ off 11 ns-len) (+ off 11 ns-len name-len)))
                    kw (keyword ns-str name-str)]
                (when (>= (.-size kw-deser-cache) KW_DESER_CACHE_MAX) (.clear kw-deser-cache))
                (.set kw-deser-cache off kw)
                kw))

          ;; Symbol
          (== tag FAST_TAG_SYMBOL_SHORT)
          (let [str-len (.getUint8 dv (+ off 3))
                u8 (:data-view s-atom-env)]
            (symbol (decode-text (.subarray u8 (+ off 4) (+ off 4 str-len)))))

          (== tag FAST_TAG_SYMBOL_NS_SHORT)
          (let [ns-len (.getUint8 dv (+ off 3))
                u8 (:data-view s-atom-env)
                ns-str (decode-text (.subarray u8 (+ off 4) (+ off 4 ns-len)))
                name-len (.getUint8 dv (+ off 4 ns-len))
                name-str (decode-text (.subarray u8 (+ off 5 ns-len) (+ off 5 ns-len name-len)))]
            (symbol ns-str name-str))

          (or (== tag ser/FAST_TAG_BIGINT) (== tag ser/FAST_TAG_RATIO)
              (== tag ser/FAST_TAG_BIGDEC) (== tag ser/FAST_TAG_REF))
          (decode-tagged-number-or-ref (js/Uint8Array. (.-buffer dv) (+ (.-byteOffset dv) off) data-len) 0 tag)

          (== tag ser/FAST_TAG_VALUE_BLOCK)
          (decode-value-block s-atom-env (js/Uint8Array. (.-buffer dv) (+ (.-byteOffset dv) off) data-len) 0)

          (== tag FAST_TAG_CHAR)
          (let [u8 (:data-view s-atom-env)]
            (->EveChar (bit-or (aget u8 (+ off 3))
                               (bit-shift-left (aget u8 (+ off 4)) 8))))

          ;; UUID — read bytes directly from u8 view
          (== tag FAST_TAG_UUID)
          (let [u8 (:data-view s-atom-env)
                hex-chars (array)]
            (dotimes [i 16]
              (let [b (aget u8 (+ off 3 i))
                    hi (unsigned-bit-shift-right b 4)
                    lo (bit-and b 0xf)]
                (.push hex-chars (.toString hi 16))
                (.push hex-chars (.toString lo 16))
                (when (or (== i 3) (== i 5) (== i 7) (== i 9))
                  (.push hex-chars "-"))))
            (uuid (.join hex-chars "")))

          ;; Date
          (== tag FAST_TAG_DATE)
          (js/Date. (.getFloat64 dv (+ off 3) true))

          ;; SAB pointer tags — direct constructor
          (and (>= tag FAST_TAG_SAB_MAP) (<= tag FAST_TAG_SAB_LIST))
          (let [ctor (get-type-constructor tag)]
            (when ctor
              (let [instance-offset (.getInt32 dv (+ off 3) true)]
                (ctor s-atom-env instance-offset))))

          ;; Record tag
          (== tag FAST_TAG_RECORD)
          (let [sab-map-ctor (get-type-constructor FAST_TAG_SAB_MAP)]
            (when sab-map-ctor
              (let [instance-offset (.getInt32 dv (+ off 3) true)
                    sab-m (sab-map-ctor s-atom-env instance-offset)
                    tag-str (get sab-m :eve/record-tag)]
                (if-let [map-fn (and tag-str (.get record-ctor-by-tag tag-str))]
                  (let [field-map (dissoc (reduce-kv assoc {} sab-m) :eve/record-tag)]
                    (map-fn field-map))
                  sab-m))))

          ;; Typed array — SAB pointer or inline blob.
          ;; Format 1 (SAB pointer, 7 bytes): [magic][magic][tag][sab-offset:i32]
          ;;   At sab-offset (16-byte header): [subtype:u8][reserved:7][byte-len:u32LE][reserved:4][data at +16]
          ;; Format 2 (inline, 8+N bytes): [magic][magic][tag][subtype:u8][byte-len:u32LE][bytes...]
          ;;
          ;; Transaction-aware deserialization:
          ;; - In transaction (*parent-atom* bound): return mutable view into SAB
          ;; - Outside transaction: copy bytes to fresh ArrayBuffer (immutable)
          ;; Data is always 16-byte aligned (SIMD-ready), so no alignment check needed.
          (== tag FAST_TAG_TYPED_ARRAY)
          (if (== data-len 7)
            ;; SAB pointer format (16-byte header)
            (let [sab-offset (.getInt32 dv (+ off 3) true)
                  u8 (:data-view s-atom-env)
                  sab (.-buffer u8)
                  subtype (.getUint8 dv sab-offset)
                  byte-len (.getUint32 dv (+ sab-offset 8) true)  ;; byte-len at offset+8
                  data-start (+ sab-offset 16)  ;; data at offset+16
                  in-transaction? (some? d/*parent-atom*)]
              (if in-transaction?
                ;; In transaction: return mutable view directly into SAB (always aligned)
                (case subtype
                  0x01 (js/Uint8Array. sab data-start byte-len)
                  0x02 (js/Int8Array. sab data-start byte-len)
                  0x03 (js/Uint8ClampedArray. sab data-start byte-len)
                  0x04 (js/Int16Array. sab data-start (/ byte-len 2))
                  0x05 (js/Uint16Array. sab data-start (/ byte-len 2))
                  0x06 (js/Int32Array. sab data-start (/ byte-len 4))
                  0x07 (js/Uint32Array. sab data-start (/ byte-len 4))
                  0x08 (js/Float32Array. sab data-start (/ byte-len 4))
                  0x09 (js/Float64Array. sab data-start (/ byte-len 8))
                  0x0A (when (exists? js/BigInt64Array) (js/BigInt64Array. sab data-start (/ byte-len 8)))
                  0x0B (when (exists? js/BigUint64Array) (js/BigUint64Array. sab data-start (/ byte-len 8)))
                  nil)
                ;; Outside transaction: copy bytes to fresh ArrayBuffer
                (let [src (.subarray u8 data-start (+ data-start byte-len))
                      dst (js/Uint8Array. byte-len)]
                  (.set dst src)
                  (let [ab (.-buffer dst)]
                    (case subtype
                      0x01 (js/Uint8Array. ab)
                      0x02 (js/Int8Array. ab)
                      0x03 (js/Uint8ClampedArray. ab)
                      0x04 (js/Int16Array. ab)
                      0x05 (js/Uint16Array. ab)
                      0x06 (js/Int32Array. ab)
                      0x07 (js/Uint32Array. ab)
                      0x08 (js/Float32Array. ab)
                      0x09 (js/Float64Array. ab)
                      0x0A (when (exists? js/BigInt64Array) (js/BigInt64Array. ab))
                      0x0B (when (exists? js/BigUint64Array) (js/BigUint64Array. ab))
                      dst)))))
            ;; Inline format: [magic][magic][tag][subtype:u8][byte-len:u32LE][bytes...]
            ;; Inline format is always copied (no SAB backing, legacy/bootstrap only)
            (let [subtype (.getUint8 dv (+ off 3))
                  byte-len (.getUint32 dv (+ off 4) true)
                  u8 (:data-view s-atom-env)
                  src (.subarray u8 (+ off 8) (+ off 8 byte-len))
                  dst (js/Uint8Array. byte-len)]
              (.set dst src)
              (let [ab (.-buffer dst)]
                (case subtype
                  0x01 (js/Uint8Array. ab)
                  0x02 (js/Int8Array. ab)
                  0x03 (js/Uint8ClampedArray. ab)
                  0x04 (js/Int16Array. ab)
                  0x05 (js/Uint16Array. ab)
                  0x06 (js/Int32Array. ab)
                  0x07 (js/Uint32Array. ab)
                  0x08 (js/Float32Array. ab)
                  0x09 (js/Float64Array. ab)
                  0x0A (when (exists? js/BigInt64Array) (js/BigInt64Array. ab))
                  0x0B (when (exists? js/BigUint64Array) (js/BigUint64Array. ab))
                  dst))))

          ;; EveArray pointer — live SAB-backed array (zero-copy reconstruct)
          (== tag FAST_TAG_EVE_ARRAY)
          (let [ctor (get-type-constructor tag)]
            (when ctor
              (let [block-offset (.getInt32 dv (+ off 3) true)]
                (ctor s-atom-env block-offset))))

          :else nil))
      ;; No magic prefix — unsupported legacy format
      nil)))

;;-----------------------------------------------------------------------------
;; Disposal helper for memory management
;;-----------------------------------------------------------------------------

(defn dispose-sab-value!
  "Dispose a deserialized SAB value, freeing its SAB memory.
   No-op for primitives and non-SAB values.
   Used by atom swap to clean up old state after CAS."
  [value s-atom-env]
  (when (satisfies? d/ISabStorable value)
    (d/-sab-dispose value s-atom-env)))

;;-----------------------------------------------------------------------------
;; eve.codec's interface — every eve.codec has these (adapters/README.md)
;;-----------------------------------------------------------------------------

(defn encode-value
  "v's EVE bytes as a value: a scalar inline, a collection a slab pointer
   (the collection allocated in the running domain). A scratch view, valid
   until the next encode-value."
  [sio v]
  (let [outer encoding-sio]
    (set! encoding-sio sio)
    (let [bs (serialize-element v)]
      (set! encoding-sio outer)
      bs)))

(defn encode-val
  "encode-value, for the value of a key/value pair while its key's bytes
   (encode-key's) are still in use: its own scratch buffer."
  [sio v]
  (let [outer encoding-sio]
    (set! encoding-sio sio)
    (let [bs (serialize-val v)]
      (set! encoding-sio outer)
      ;; the caller's own bytes, as encode-key's: a number's are a view of scratch buffer B, which the next
      ;; pair's value overwrites while these are still held (a writer that encodes every pair before it writes)
      (if (identical? (.-buffer bs) scratch-b-buf) (.slice bs) bs))))

(defn encode-val-out-of-line
  "encode-val, a value whose bytes pass VALUE_INLINE_MAX written to a block of
   its own: [0xEE 0xDB 0x19][block:i32LE][len:u32LE] (FORMAT-DECISIONS.md §10).
   Not yet what encode-val writes: that switch lands with the retire walk's
   data-value arm, which frees a superseded block (EM2)."
  [sio v]
  (let [bs (serialize-val v)
        n (.-length bs)]
    (if (<= n ser/VALUE_INLINE_MAX)
      bs
      (let [blk (ser/write-value-block! sio bs)
            buf (js/Uint8Array. 11)
            dv (js/DataView. (.-buffer buf))]
        (aset buf 0 d/DIRECT_MAGIC_0) (aset buf 1 d/DIRECT_MAGIC_1) (aset buf 2 ser/FAST_TAG_VALUE_BLOCK)
        (.setInt32 dv 3 blk true)
        (.setUint32 dv 7 n true)
        buf))))

(defn encode-key
  "k's EVE bytes as a key: a pure function of k's value (a collection flat, in
   canonical order — what the HAMT hashes and compares). The bytes are the
   caller's own: a number's serialize-key bytes are a view of scratch buffer A,
   which the encode of the value that follows reuses (serialize-element writes a
   nested vector's or set's numbers there), so they are copied out — else
   (assoc m 1 [1 2]) stored the key as 2, the last number the value wrote."
  [k]
  (let [bs (serialize-key k)]
    (if (identical? (.-buffer bs) scratch-a-buf) (.slice bs) bs)))

(defn decode
  "The value EVE bytes bs hold (a slab pointer read in sio's domain)."
  [sio bs]
  (deserialize-element sio bs))

(defn register-collection-writer!
  "A direct slab writer for a host collection of kind tag (:map :set :vec
   :list :array), (fn [sio encode coll] → header-off). This codec builds a
   host collection through its builder (eve.deftype-proto.serialize's
   convert-to-eve) and keeps the writer for the codec that writes directly."
  [tag writer]
  (ser/register-coll-writer! tag writer))

(defn write-collection!
  "Write host collection coll into sio's slab through the writer registered
   for tag (:map :set :vec :list). Returns its header offset."
  [tag sio coll]
  (if-let [writer (ser/get-coll-writer tag)]
    (writer sio (partial encode-value sio) coll)
    (throw (ex-info (str "write-collection!: no writer for " tag) {:tag tag}))))

(defn value->eve-bytes
  "The flat form of v (the byte-array codec's): this codec has none, so a caller that asks — eve.digest's root digest,
   under eve.thread-host/attempt — gets no digest and walks, as it did when the name was missing here (the shadow
   build's undeclared-var warning, a TypeError at the call). Declared so a host that resolves names when it loads a
   namespace (nbb) can load eve.digest."
  [_v]
  (throw (ex-info "eve.codec: no flat form on this codec (value->eve-bytes)" {:codec :js})))
