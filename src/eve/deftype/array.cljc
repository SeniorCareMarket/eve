(ns eve.deftype.array
  "Arrays of eve values, in eve memory: what an eve-deftype holds where a type
   on the host holds an array (an Object[], a JS array). A node of a
   persistent tree is the usual case — its children, its elements, its range
   table.

   (make-array n) allocates the array in the current eve domain (the running
   swap!'s, else this process's own), as an eve-deftype's constructor does.
   aget, aset, alength, aclone, array-copy, array-seq and array-chunk take an
   eve array or a host array, so code written against ClojureScript's array
   functions reads the same; an eve array is seqable by clojure.core on every
   host, as a host array is.

   Block layout (type-id 0x1F), n slots:
     [0x1F:u8][pad:3][n:i32][kind:u8 x n][tag:u8 x n][pad to 4][payload:i32 x n]
   A slot holds one eve value, in eve's own encoding (the serializer's):
     kind 0  nil
     kind 1  an int32, the payload
     kind 2  a pointer to an eve value (another array, an eve-deftype
             instance, an eve map/vector/set): its tag and offset
     kind 3  a value whose whole encoding is its tag (false, true)
     kind 4  any other value: the payload is a block holding its encoding
   A node's 32 or 33 slots fit a 256-byte block.
   An eve array is written once, while it is new — by the code that made it,
   before anything else can see it — and read after that. Copies (aclone,
   array-copy) share the values a slot points at, as a host array's copy
   shares its elements."
  (:refer-clojure :exclude [make-array aget aset alength aclone array-seq
                            byte-array object-array unchecked-byte unchecked-int long
                            volatile! vswap! bytes objects])
  (:require [eve.host :refer [byte-array object-array unchecked-byte unchecked-int long
                              volatile! vswap! bytes objects]]
            [eve.core :as e]
            [eve.deftype :as edt]
            [eve.slab-io :as slab]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.encoding :as enc]
            [eve.platform :as p]
            ;; a slot holds any eve value: Clojure data stored in one becomes
            ;; eve's map, vector, set or list (their writers register here)
            [eve.map] [eve.vec] [eve.set] [eve.list]
            [eve.codec :as codec]
            [eve.deftype-proto.macros :as pmac]))

(def ^:const ValueArray-type-id 0x1F)
(def ^:const HEADER 8)

(def ^:const KIND_NIL 0)
(def ^:const KIND_INT32 1)
(def ^:const KIND_POINTER 2)
(def ^:const KIND_TAG 3)
(def ^:const KIND_BLOCK 4)

(defn- payload-base
  "Where an n-slot array's payloads start (4-aligned, after the kind and tag
   bytes)."
  [n]
  (bit-and (+ HEADER (* 2 n) 3) (bit-not 3)))

(defn- block-size [n] (+ (payload-base n) (* 4 n)))

;;-----------------------------------------------------------------------------
;; A slot: one eve value, in eve's encoding
;;-----------------------------------------------------------------------------

(defn- encode
  "v's eve encoding (CLJS: the serializer's scratch view, consumed at once)."
  [sio v]
  (codec/encode-value sio v))

(defn- decode [sio bs]
  (codec/decode sio bs))

(defn- byte-len [bs]
  (eve.host/alength ^bytes bs))

(defn- magic? [bs]
  (and (>= (byte-len bs) 3)
       (== d/DIRECT_MAGIC_0 (bit-and (eve.host/aget ^bytes bs 0) 0xFF))
       (== d/DIRECT_MAGIC_1 (bit-and (eve.host/aget ^bytes bs 1) 0xFF))))

(defn- byte-at [bs i]
  (bit-and (eve.host/aget ^bytes bs i) 0xFF))

(defn- i32-at [bs i]
  (let [v (bit-or (byte-at bs i)
                  (bit-shift-left (byte-at bs (+ i 1)) 8)
                  (bit-shift-left (byte-at bs (+ i 2)) 16)
                  (bit-shift-left (byte-at bs (+ i 3)) 24))]
    (p/int32 v)))

(defn- write-slot!
  "Store v in slot i of the n-slot array at off."
  [sio off n i v]
  (let [pb (payload-base n)
        put! (fn [kind tag payload]
               (slab/write-u8! sio off (+ HEADER i) kind)
               (slab/write-u8! sio off (+ HEADER n i) tag)
               (slab/write-i32! sio off (+ pb (* 4 i)) payload))]
    (if (nil? v)
      (put! KIND_NIL 0 0)
      (let [bs  (encode sio v)
            len (byte-len bs)]
        (cond
          ;; [EE DB tag i32]: an int32, or a pointer — a tag the registry
          ;; builds a value for (a short keyword or string is 7 bytes too)
          (and (== len 7) (magic? bs)
               (or (== (byte-at bs 2) ser/FAST_TAG_INT32)
                   (some? (ser/get-type-constructor (byte-at bs 2)))))
          (let [tag (byte-at bs 2)]
            (put! (if (== tag ser/FAST_TAG_INT32) KIND_INT32 KIND_POINTER) tag (i32-at bs 3)))

          (and (== len 3) (magic? bs))
          (put! KIND_TAG (byte-at bs 2) 0)

          :else
          (let [blk (slab/alloc! sio (+ 4 len))]
            (slab/write-i32! sio blk 0 len)
            (slab/write-bytes! sio blk 4 bs)
            (put! KIND_BLOCK 0 blk)))))))

(defn- read-slot
  "The value in slot i of the n-slot array at off."
  [sio off n i]
  (let [kind (slab/read-u8 sio off (+ HEADER i))]
    (if (== kind KIND_NIL)
      nil
      (let [payload (slab/read-i32 sio off (+ (payload-base n) (* 4 i)))]
        (cond
          (== kind KIND_INT32) (long payload)

          (== kind KIND_BLOCK)
          (let [len (slab/read-i32 sio payload 0)]
            (decode sio (slab/read-bytes sio payload 4 len)))

          :else
          (let [tag (slab/read-u8 sio off (+ HEADER n i))]
            (if (== kind KIND_TAG)
              (decode sio (enc/bytes-of [d/DIRECT_MAGIC_0 d/DIRECT_MAGIC_1 tag]))
              (if-let [ctor (ser/get-type-constructor tag)]
                (ctor sio payload)
                (throw (ex-info "eve array: no constructor for a pointer tag"
                                {:tag tag}))))))))))

(defn- copy-bytes!
  "Copy nbytes from (src-off + from) to (dst-off + to), read whole before the
   write, so ranges of one array may overlap."
  [src-sio src-off from dst-sio dst-off to nbytes]
  (when (pos? nbytes)
    (slab/write-bytes! dst-sio dst-off to (slab/read-bytes src-sio src-off from nbytes))))

(defn- copy-slots!
  "Copy len slots from (src-off, i) to (dst-off, j), as they are: the copy
   points at the same values. A run of slots is three runs of bytes — kinds,
   tags, payloads — so it is three copies, not three per slot."
  [src-sio src-off src-n i dst-sio dst-off dst-n j len]
  (copy-bytes! src-sio src-off (+ HEADER i) dst-sio dst-off (+ HEADER j) len)
  (copy-bytes! src-sio src-off (+ HEADER src-n i) dst-sio dst-off (+ HEADER dst-n j) len)
  (copy-bytes! src-sio src-off (+ (payload-base src-n) (* 4 i))
               dst-sio dst-off (+ (payload-base dst-n) (* 4 j)) (* 4 len)))

;;-----------------------------------------------------------------------------
;; The array: a handle on its block
;;-----------------------------------------------------------------------------

(defprotocol IValueArray
  (-va-sio [a])
  (-va-off [a])
  (-va-count [a]))

(defn- va-seq [sio off n start]
  (when (< start n)
    (map #(read-slot sio off n %) (range start n))))

(declare ->value-array)

;; An eve array, one type on every host (eve's type language: each host's
;; eve.types realizes it). Its print body names no field: on some hosts it is
;; the type's print-method.
(declare va=)

(edt/deftype ValueArray [sio off n]
  IValueArray
  (-va-sio [_] sio) (-va-off [_] off) (-va-count [_] n)
  d/IEveRoot (-root-header-off [_] off)
  d/IEveSio (-eve-sio [_] sio)
  d/ISabpType (-sabp-type-key [_] "eve/ValueArray")
  d/IDirectSerialize (-direct-serialize [_] off)
  d/ISabStorable
  (-sab-tag [_] :eve/value-array)
  (-sab-encode [_ _] (codec/encode-sab-pointer ValueArray-type-id off))
  (-sab-dispose [_ _] nil)

  clojure.lang.Counted
  (count [_] n)

  clojure.lang.Indexed
  (nth [_ i] (if (and (<= 0 i) (< i n))
               (read-slot sio off n i)
               (throw (enc/index-out-of-bounds (str "No item " i " in eve array of " n)))))
  (nth [_ i nf] (if (and (<= 0 i) (< i n)) (read-slot sio off n i) nf))

  clojure.lang.Seqable
  (seq [_] (va-seq sio off n 0))

  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  ;; cons and empty too: basilisp's types refuse a partial interface
  clojure.lang.IPersistentCollection
  (cons [_ _] (enc/throw-unsupported))
  (empty [_] nil)
  (equiv [this other] (va= this other))

  clojure.lang.IHashEq
  (hasheq [_] (hash off))

  IPrintWithWriter
  (-pr-writer [this w _] (e/-write w (str "#eve/array " (vec (va-seq (-va-sio this) (-va-off this) (-va-count this) 0)))))

  java.lang.Object
  (equals [this other] (va= this other))
  (hashCode [_] (hash off))
  (toString [this] (str "#eve/array " (vec (va-seq (-va-sio this) (-va-off this) (-va-count this) 0)))))

(defn- va=
  "The same eve array: the same block."
  [a b]
  (and (satisfies? IValueArray b) (== (-va-off a) (-va-off b))))

(ser/register-sab-fast-tag! :eve/value-array ValueArray-type-id)

(defn- ->value-array [sio off n] (->ValueArray sio off n))

;; A pointer to an array (a slot, a field, the atom root) reads back as one (a
;; named fn: one a top-level form makes is interpreted on clojerl)
(defn- value-array-from-header [sio off] (->value-array sio off (slab/read-i32 sio off 4)))
(ser/register-type-constructor! ValueArray-type-id ValueArray-type-id value-array-from-header)

(defn value-array?
  "True for an eve array."
  [x]
  (instance? ValueArray x))

;;-----------------------------------------------------------------------------
;; The array functions — an eve array, or a host array
;;-----------------------------------------------------------------------------

(defn make-array
  "A new eve array of n slots (each nil), in the current eve domain."
  ([n]
   (let [sio (alloc/current-sio)
         off (slab/alloc! sio (block-size n))]
     (slab/write-u8! sio off 0 ValueArray-type-id)
     (slab/write-i32! sio off 4 n)
     ;; every kind byte nil (a block is handed back as it was left): one
     ;; write of n KIND_NIL (0) bytes
     (when (pos? n)
       (slab/write-bytes! sio off HEADER (enc/make-bytes n)))
     (->value-array sio off n)))
  ([_type n] (make-array n)))

(defn aget
  "The value at index i."
  [a i]
  (if (value-array? a)
    (read-slot (-va-sio a) (-va-off a) (-va-count a) i)
    (eve.host/aget ^objects a (int i))))

(defn aset
  "Store v at index i. An eve array is written while it is new: by the code
   that made it, before anything else sees it."
  [a i v]
  (if (value-array? a)
    (do (write-slot! (-va-sio a) (-va-off a) (-va-count a) i v) v)
    (eve.host/aset ^objects a (int i) v)))

(defn alength
  "The number of slots."
  [a]
  (if (value-array? a)
    (-va-count a)
    (eve.host/alength ^objects a)))

(defn aclone
  "A new array with a's values (an eve array: a new one in the current domain,
   its slots pointing at the same values)."
  [a]
  (if (value-array? a)
    (let [n   (-va-count a)
          ret (make-array n)]
      (copy-slots! (-va-sio a) (-va-off a) n 0 (-va-sio ret) (-va-off ret) n 0 n)
      ret)
    (eve.host/aclone ^objects a)))

(defn array-copy
  "Copy len values from (from, i) to (to, j) — ClojureScript's array-copy."
  [from i to j len]
  (if (and (value-array? from) (value-array? to))
    (copy-slots! (-va-sio from) (-va-off from) (-va-count from) i
                 (-va-sio to) (-va-off to) (-va-count to) j len)
    (dotimes [k len]
      (aset to (+ j k) (aget from (+ i k)))))
  to)

(defn array-seq
  "A seq of the array's values from index start."
  ([a] (array-seq a 0))
  ([a start]
   (if (value-array? a)
     (va-seq (-va-sio a) (-va-off a) (-va-count a) start)
     (seq (drop start a)))))

(defn array-chunk
  "A chunk of the array's values from off to end (a host chunk: the values
   read out of eve memory)."
  ([a] (array-chunk a 0 (alength a)))
  ([a off] (array-chunk a off (alength a)))
  ([a off end]
   (let [n   (- end off)
         arr (object-array n)]
     (dotimes [k n]
       (eve.host/aset ^objects arr k (aget a (+ off k))))
     (e/array-chunk arr n))))
