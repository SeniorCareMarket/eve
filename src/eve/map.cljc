(ns eve.map
  "Eve persistent HAMT map — unified CLJ/CLJS implementation.

   Single set of HAMT algorithms using ISlabIO protocol for memory access.
   On CLJS: CljsSlabIO delegates to module-level DataView functions.
   On CLJ:  JvmSlabCtx reads/writes via IMemRegion-backed mmap files.

   Node data lives in slab-class-specific SharedArrayBuffers (CLJS) or
   mmap files (JVM). Node offsets are slab-qualified: [class:3 | block:29].
   Both platforms use portable-hash-bytes (Murmur3 over serialized key bytes)
   for identical trie navigation.

   Uses eve/deftype macro: CLJ protocol names, auto-translated on CLJS."
  (:refer-clojure :exclude [hash-map
                            aget alength unsigned-bit-shift-right volatile! vswap! transient
                            persistent! conj! long bytes])
  (:require
   [eve.host :refer [field aget alength unsigned-bit-shift-right volatile! vswap! transient
                     persistent! conj! long bytes]]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.hamt-batch :as batch]
   [eve.hamt-util :as hu :refer [portable-hash-bytes popcount32
                                 bitpos node-bit has-bit? get-index]]
   [eve.encoding :as enc]
   [eve.core :as ecore]
   [eve.platform :as p]
   [eve.deftype-proto.macros :as eve]
   [eve.vec :as evec]
   [eve.codec :as codec]
   [eve.digest :as dg]))

;;=============================================================================
;; Shared Constants
;;=============================================================================

(def ^:const SHIFT_STEP 5)
(def ^:const MASK 0x1f)

(def ^:const NODE_TYPE_BITMAP 1)
(def ^:const NODE_TYPE_COLLISION 3)

;; Node header: type:u8 + flags:u8 + kv_total_size:u16 + data_bitmap:u32 + node_bitmap:u32 = 12
(def ^:const NODE_HEADER_SIZE 12)
(def ^:const COLLISION_HEADER_SIZE 8)

;; EveHashMap header: type-id:u8 + pad:3 + count:i32 + root-off:i32 = 12
(def EveHashMap-type-id 0xED) ;; also emitted by eve/deftype

;; The not-found sentinel for hamt-get. A keyword literal is not one object on
;; every host — two `::absent` literals are separate Keyword instances in
;; compiled CLJS, so `identical?` was false and contains? answered true for
;; every key — so the sentinel is one object, identical by construction.
(def ^:private absent (volatile! :eve.map/absent))
(def ^:const SABMAPROOT_CNT_OFFSET 4)
(def ^:const SABMAPROOT_ROOT_OFF_OFFSET 8)

(def ^:const NIL_OFFSET alloc/NIL_OFFSET)

;;=============================================================================
;; Platform-specific helpers
;;=============================================================================

(defn- serialize-key-bytes
  "Serialize a key to bytes (platform-specific)."
  [k]
  (codec/encode-key k))

;; --- A5: the lookup's [kb kh] memo for interned keys ---
;; A HAMT lookup pays key serialize + Murmur hash before its first
;; node read. kb and kh are pure functions of the key, and a keyword
;; is one object per name on every host, so an identity-keyed memo
;; is exact: a hit returns the very bytes and hash the uncached path
;; computes; a miss falls through to it. Bounded and emptied whole
;; past the cap, the codec kw-encode-cache pattern (enc/cache-put!).
;; Keyword-only: an equal-but-not-identical key type would only ever
;; miss, so the memo never fills with entries that cannot hit.
(def ^:private key-memo (enc/new-cache))
(def ^:private ^:const KEY-MEMO-MAX 4096)

(defn- key-bytes+hash
  "The [kb kh] a HAMT lookup needs for k."
  [k]
  (if (keyword? k)
    (or (enc/cache-get key-memo k)
        (let [kb (serialize-key-bytes k)
              pair [kb (portable-hash-bytes kb)]]
          (enc/cache-put! key-memo k pair KEY-MEMO-MAX)
          pair))
    (let [kb (serialize-key-bytes k)]
      [kb (portable-hash-bytes kb)])))

(defn- serialize-val-bytes
  "Serialize a value to bytes (platform-specific).
   sio is explicit on CLJ (ignored on CLJS)."
  [sio v]
  (codec/encode-val sio v))

(defn- deserialize-value-bytes
  "Deserialize value bytes (platform-specific). Takes an explicit sio on CLJ."
  [val-bytes sio]
  (codec/decode sio val-bytes))

(defn- default-sio
  "The slab I/O a constructor given none allocates in: the running swap!'s
   domain; outside a swap/domain context it throws (:no-domain-context)."
  []
  (alloc/current-sio))

(defn- bytes-equal? [a b]
  (enc/bytes= a b))

(defn- bytes-length
  "Get byte array length."
   [^bytes ba]
  (alength ba))

;;=============================================================================
;; Computed node layout helpers
;;=============================================================================

(defn- hashes-start-off
  "Byte offset within node where hash array starts (after children)."
   ^long [^long node-bm]
  (+ NODE_HEADER_SIZE (* 4 (popcount32 node-bm))))

(defn- kv-data-start-off
  "Byte offset within node where KV data starts."
   ^long [^long data-bm ^long node-bm]
  (+ NODE_HEADER_SIZE (* 4 (popcount32 node-bm)) (* 4 (popcount32 data-bm))))

;;=============================================================================
;; KV read/write helpers (via ISlabIO)
;;=============================================================================

(defn- skip-kv-at
  "Skip a KV pair at pos within node, returning offset after it."
   [sio ^long node-off ^long pos]
  (let [key-len (alloc/node-i32 sio node-off pos)
        val-off (+ pos 4 key-len)
        val-len (alloc/node-i32 sio node-off val-off)]
    (+ val-off 4 val-len)))

;; The read path's scan to the di-th KV pair: a primitive fn (a loop in a let binding compiles to a closure, allocated
;; and boxed per lookup; jvm-heap-grade step 3).
(defn- kv-pos
  "The offset of the di-th KV pair in the node at node-off, its KV data starting at kvs."
   [sio ^long node-off ^long kvs ^long di]
  (let [a (alloc/node-heap-array sio node-off)]
    (if (nil? a)
      (loop [i 0 p kvs]
        (if (== i di) p
            (recur (inc i) (skip-kv-at sio node-off p))))
      ;; the node's heap bytes, resolved once: [key-len:i32][key][val-len:i32][val] per pair
      (let [base (alloc/node-base sio node-off)]
        (loop [i 0 p kvs]
          (if (== i di) p
              (let [vo (+ p 4 (alloc/bytes-i32 a (+ base p)))]
                (recur (inc i) (+ vo 4 (alloc/bytes-i32 a (+ base vo)))))))))))

(defn- calc-kv-size
  "Calculate total bytes for a KV pair."
   [kb vb]
  (+ 4 (bytes-length kb) 4 (bytes-length vb)))

(defn- key-bytes-match?
  "Check if key bytes at pos match kb."
  [sio ^long node-off ^long pos kb]
  (let [stored-len (alloc/node-i32 sio node-off pos)]
    (when (== stored-len (bytes-length kb))
      (alloc/node-bytes-equal? sio node-off (+ pos 4) kb))))

(defn- write-kv!
  "Write a KV pair at pos within node. Returns offset after written data."
  [sio node-off pos kb vb]
  (let [klen (bytes-length kb)
        vlen (bytes-length vb)]
    (slab/write-i32! sio node-off pos klen)
    (when (pos? klen)
      (slab/write-bytes! sio node-off (+ pos 4) kb))
    (let [val-off (+ pos 4 klen)]
      (slab/write-i32! sio node-off val-off vlen)
      (when (pos? vlen)
        (slab/write-bytes! sio node-off (+ val-off 4) vb))
      (+ val-off 4 vlen))))

(defn- read-kv-bytes-at
  "Read key and value byte arrays at pos. Returns [kb vb next-pos]."
  [sio ^long node-off ^long pos]
  (let [klen (alloc/node-i32 sio node-off pos)
        kb (slab/read-bytes sio node-off (+ pos 4) klen)
        voff (+ pos 4 klen)
        vlen (alloc/node-i32 sio node-off voff)
        vb (slab/read-bytes sio node-off (+ voff 4) vlen)]
    [kb vb (+ voff 4 vlen)]))

;;=============================================================================
;; Node kv-total-size helpers
;;=============================================================================

;; The header's kv-total cache is a u16: a node whose kv bytes pass 65,535 (a
;; few big values) caches 0, "unknown", and readers scan — as vs.eve-disk
;; writes it. A truncated total sized every copy of the node wrong (2026-10-18:
;; 6 values of 12,000 characters, then a replace, read 1.7 GB past the slab).
(defn- kv-total-cache [n] (if (< n 65536) n 0))

(defn- node-kv-total-size
  "Read the cached kv-total-size from header. If 0, compute by scanning."
   [sio ^long node-off ^long data-bm ^long node-bm]
  (let [cached (slab/read-u16 sio node-off 2)]
    (if (pos? cached)
      cached
      (let [dc (popcount32 data-bm)
            kv-off (kv-data-start-off data-bm node-bm)]
        (loop [i 0 pos kv-off]
          (if (>= i dc)
            (- pos kv-off)
            (recur (inc i) (skip-kv-at sio node-off pos))))))))

(defn- node-byte-size
  "Compute total byte size of a bitmap node."
   [sio ^long node-off ^long data-bm ^long node-bm]
  (+ NODE_HEADER_SIZE
     (* 4 (popcount32 node-bm))
     (* 4 (popcount32 data-bm))
     (node-kv-total-size sio node-off data-bm node-bm)))

;;=============================================================================
;; Node construction (via ISlabIO)
;;=============================================================================

(defn- make-single-entry-node!
  "Create bitmap node with exactly 1 data entry and 0 children."
  [sio data-bm kh kb vb]
  (let [kv-size (calc-kv-size kb vb)
        node-off (slab/alloc! sio (+ NODE_HEADER_SIZE 4 kv-size))]
    (slab/write-u8! sio node-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8! sio node-off 1 0)
    (slab/write-u16! sio node-off 2 (kv-total-cache kv-size))
    (slab/write-i32! sio node-off 4 data-bm)
    (slab/write-i32! sio node-off 8 0)
    (slab/write-i32! sio node-off NODE_HEADER_SIZE kh)
    (write-kv! sio node-off (+ NODE_HEADER_SIZE 4) kb vb)
    node-off))

(defn- make-two-entry-node!
  "Create bitmap node with exactly 2 data entries and 0 children."
  [sio data-bm kh1 kb1 vb1 kh2 kb2 vb2]
  (let [kv-size (+ (calc-kv-size kb1 vb1) (calc-kv-size kb2 vb2))
        node-off (slab/alloc! sio (+ NODE_HEADER_SIZE 8 kv-size))]
    (slab/write-u8! sio node-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8! sio node-off 1 0)
    (slab/write-u16! sio node-off 2 (kv-total-cache kv-size))
    (slab/write-i32! sio node-off 4 data-bm)
    (slab/write-i32! sio node-off 8 0)
    (slab/write-i32! sio node-off NODE_HEADER_SIZE kh1)
    (slab/write-i32! sio node-off (+ NODE_HEADER_SIZE 4) kh2)
    (let [next-pos (write-kv! sio node-off (+ NODE_HEADER_SIZE 8) kb1 vb1)]
      (write-kv! sio node-off next-pos kb2 vb2))
    node-off))

(defn- make-single-child-node!
  "Create bitmap node with 0 data entries and 1 child."
  [sio ^long node-bm ^long child-off]
  (let [node-off (slab/alloc! sio (+ NODE_HEADER_SIZE 4))]
    (slab/write-u8! sio node-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8! sio node-off 1 0)
    (slab/write-u16! sio node-off 2 0)
    (slab/write-i32! sio node-off 4 0)
    (slab/write-i32! sio node-off 8 node-bm)
    (slab/write-i32! sio node-off NODE_HEADER_SIZE child-off)
    node-off))

(defn- make-child-and-entry-node!
  "Create bitmap node with 1 data entry and 1 child."
  [sio data-bm node-bm child-off kh kb vb]
  (let [kv-size (calc-kv-size kb vb)
        node-off (slab/alloc! sio (+ NODE_HEADER_SIZE 4 4 kv-size))]
    (slab/write-u8! sio node-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8! sio node-off 1 0)
    (slab/write-u16! sio node-off 2 (kv-total-cache kv-size))
    (slab/write-i32! sio node-off 4 data-bm)
    (slab/write-i32! sio node-off 8 node-bm)
    (slab/write-i32! sio node-off NODE_HEADER_SIZE child-off)
    (slab/write-i32! sio node-off (+ NODE_HEADER_SIZE 4) kh)
    (write-kv! sio node-off (+ NODE_HEADER_SIZE 8) kb vb)
    node-off))

(defn- make-collision-node!
  "Create collision node from entries: [[kh kb vb] ...]."
  [sio ^long kh entries]
  (let [cnt (count entries)
        kv-size (reduce (fn [acc [_ kb vb]] (+ acc (calc-kv-size kb vb))) 0 entries)
        node-off (slab/alloc! sio (+ COLLISION_HEADER_SIZE kv-size))]
    (slab/write-u8! sio node-off 0 NODE_TYPE_COLLISION)
    (slab/write-u8! sio node-off 1 cnt)
    (slab/write-u16! sio node-off 2 0)
    (slab/write-i32! sio node-off 4 kh)
    (reduce (fn [pos [_ kb vb]]
              (write-kv! sio node-off pos kb vb))
            COLLISION_HEADER_SIZE entries)
    node-off))

;;=============================================================================
;; Bulk-copy node helpers (path-copy for persistent updates)
;;=============================================================================

(defn- copy-node-patch-child!
  "Bulk-copy a bitmap node and patch one child pointer. Returns new offset."
  [sio src-off data-bm node-bm child-idx new-child-off]
  (let [nsize (node-byte-size sio src-off data-bm node-bm)
        dst-off (slab/alloc! sio nsize)]
    (slab/copy-block! sio dst-off 0 src-off 0 nsize)
    (slab/write-i32! sio dst-off (+ NODE_HEADER_SIZE (* child-idx 4)) new-child-off)
    dst-off))

(defn- kv-pos-and-size
  "Find byte offset and size of data-idx'th KV entry."
  [sio node-off data-bm node-bm data-idx]
  (let [kv-off (kv-data-start-off data-bm node-bm)]
    (loop [i 0 pos kv-off]
      (if (== i data-idx)
        (let [next (skip-kv-at sio node-off pos)]
          [pos (- next pos)])
        (recur (inc i) (skip-kv-at sio node-off pos))))))

(defn- copy-node-replace-kv!
  "Bulk-copy a bitmap node and replace one KV entry."
  [sio src-off data-bm node-bm data-idx kh kb vb]
  (let [[kv-pos old-kv-size] (kv-pos-and-size sio src-off data-bm node-bm data-idx)
        new-kv-size (calc-kv-size kb vb)
        size-diff (- new-kv-size old-kv-size)
        old-nsize (node-byte-size sio src-off data-bm node-bm)
        new-nsize (+ old-nsize size-diff)
        dc (popcount32 data-bm)
        cc (popcount32 node-bm)]
    (if (zero? size-diff)
      ;; Same size — bulk copy, overwrite KV + hash
      (let [dst-off (slab/alloc! sio new-nsize)]
        (slab/copy-block! sio dst-off 0 src-off 0 old-nsize)
        (slab/write-i32! sio dst-off kv-pos (bytes-length kb))
        (slab/write-bytes! sio dst-off (+ kv-pos 4) kb)
        (slab/write-i32! sio dst-off (+ kv-pos 4 (bytes-length kb)) (bytes-length vb))
        (slab/write-bytes! sio dst-off (+ kv-pos 4 (bytes-length kb) 4) vb)
        (slab/write-i32! sio dst-off (+ (hashes-start-off node-bm) (* data-idx 4)) kh)
        dst-off)
      ;; Different size — rebuild with splice
      (let [dst-off (slab/alloc! sio new-nsize)
            kv-total (+ (node-kv-total-size sio src-off data-bm node-bm) size-diff)
            children-end (+ NODE_HEADER_SIZE (* 4 cc))]
        (slab/copy-block! sio dst-off 0 src-off 0 children-end)
        (slab/write-u16! sio dst-off 2 (kv-total-cache kv-total))
        ;; Copy hashes, patch one
        (let [h-off (hashes-start-off node-bm)]
          (dotimes [i dc]
            (slab/write-i32! sio dst-off (+ h-off (* i 4))
                             (if (== i data-idx)
                               kh
                               (slab/read-i32 sio src-off (+ h-off (* i 4)))))))
        ;; Copy KV entries, replacing at data-idx
        (let [src-kv-off (kv-data-start-off data-bm node-bm)]
          (loop [i 0 sp src-kv-off dp src-kv-off]
            (when (< i dc)
              (if (== i data-idx)
                (let [next-dp (write-kv! sio dst-off dp kb vb)
                      next-sp (skip-kv-at sio src-off sp)]
                  (recur (inc i) next-sp next-dp))
                (let [entry-size (- (skip-kv-at sio src-off sp) sp)]
                  (slab/copy-block! sio dst-off dp src-off sp entry-size)
                  (recur (inc i) (+ sp entry-size) (+ dp entry-size)))))))
        dst-off))))

(defn- copy-node-add-kv!
  "Copy a bitmap node, inserting a new KV at data-idx."
  [sio src-off src-data-bm new-data-bm node-bm
   data-idx kh kb vb]
  (let [new-kv-size (calc-kv-size kb vb)
        old-kv-total (node-kv-total-size sio src-off src-data-bm node-bm)
        new-kv-total (+ old-kv-total new-kv-size)
        cc (popcount32 node-bm)
        new-dc (popcount32 new-data-bm)
        old-dc (popcount32 src-data-bm)
        nsize (+ NODE_HEADER_SIZE (* 4 cc) (* 4 new-dc) new-kv-total)
        dst-off (slab/alloc! sio nsize)]
    (slab/write-u8! sio dst-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8! sio dst-off 1 0)
    (slab/write-u16! sio dst-off 2 (kv-total-cache new-kv-total))
    (slab/write-i32! sio dst-off 4 new-data-bm)
    (slab/write-i32! sio dst-off 8 node-bm)
    ;; Copy children
    (when (pos? cc)
      (slab/copy-block! sio dst-off NODE_HEADER_SIZE src-off NODE_HEADER_SIZE (* 4 cc)))
    ;; Build hash array with insertion
    (let [h-off (hashes-start-off node-bm)]
      (loop [si 0 di 0]
        (when (< di new-dc)
          (if (== di data-idx)
            (do (slab/write-i32! sio dst-off (+ h-off (* di 4)) kh)
                (recur si (inc di)))
            (do (slab/write-i32! sio dst-off (+ h-off (* di 4))
                                 (slab/read-i32 sio src-off (+ h-off (* si 4))))
                (recur (inc si) (inc di)))))))
    ;; Build KV data with insertion
    (let [src-kv-off (kv-data-start-off src-data-bm node-bm)
          dst-kv-off (kv-data-start-off new-data-bm node-bm)]
      (loop [si 0 di 0 sp src-kv-off dp dst-kv-off]
        (when (< di (inc old-dc))
          (if (== di data-idx)
            (let [next-dp (write-kv! sio dst-off dp kb vb)]
              (recur si (inc di) sp next-dp))
            (let [entry-size (- (skip-kv-at sio src-off sp) sp)]
              (slab/copy-block! sio dst-off dp src-off sp entry-size)
              (recur (inc si) (inc di) (+ sp entry-size) (+ dp entry-size)))))))
    dst-off))

(defn- copy-node-remove-kv-add-child!
  "Copy a bitmap node, removing a KV entry and inserting a child pointer."
  [sio src-off src-data-bm src-node-bm
   new-data-bm new-node-bm remove-idx
   new-child-idx new-child-off]
  (let [old-kv-total (node-kv-total-size sio src-off src-data-bm src-node-bm)
        [_ removed-size] (kv-pos-and-size sio src-off src-data-bm src-node-bm remove-idx)
        new-kv-total (- old-kv-total removed-size)
        new-cc (popcount32 new-node-bm)
        new-dc (popcount32 new-data-bm)
        old-cc (popcount32 src-node-bm)
        old-dc (popcount32 src-data-bm)
        nsize (+ NODE_HEADER_SIZE (* 4 new-cc) (* 4 new-dc) new-kv-total)
        dst-off (slab/alloc! sio nsize)]
    (slab/write-u8! sio dst-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8! sio dst-off 1 0)
    (slab/write-u16! sio dst-off 2 (kv-total-cache new-kv-total))
    (slab/write-i32! sio dst-off 4 new-data-bm)
    (slab/write-i32! sio dst-off 8 new-node-bm)
    ;; Build children array with insertion
    (let [src-c-off NODE_HEADER_SIZE]
      (loop [si 0 di 0]
        (when (< di new-cc)
          (if (== di new-child-idx)
            (do (slab/write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di 4)) new-child-off)
                (recur si (inc di)))
            (do (slab/write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di 4))
                                 (slab/read-i32 sio src-off (+ src-c-off (* si 4))))
                (recur (inc si) (inc di)))))))
    ;; Build hash array, skipping removed entry
    (let [src-h-off (hashes-start-off src-node-bm)
          dst-h-off (hashes-start-off new-node-bm)]
      (loop [si 0 di 0]
        (when (< di new-dc)
          (if (== si remove-idx)
            (recur (inc si) di)
            (do (slab/write-i32! sio dst-off (+ dst-h-off (* di 4))
                                 (slab/read-i32 sio src-off (+ src-h-off (* si 4))))
                (recur (inc si) (inc di)))))))
    ;; Build KV data, skipping removed entry
    (let [src-kv-off (kv-data-start-off src-data-bm src-node-bm)
          dst-kv-off (kv-data-start-off new-data-bm new-node-bm)]
      (loop [si 0 sp src-kv-off dp dst-kv-off]
        (when (< si old-dc)
          (if (== si remove-idx)
            (recur (inc si) (skip-kv-at sio src-off sp) dp)
            (let [entry-size (- (skip-kv-at sio src-off sp) sp)]
              (slab/copy-block! sio dst-off dp src-off sp entry-size)
              (recur (inc si) (+ sp entry-size) (+ dp entry-size)))))))
    dst-off))

;;=============================================================================
;; HAMT Find (unified)
;;=============================================================================

(defn- hamt-get-words
  "Find the key in the HAMT at root-off, walking with the per-word
   node readers: every node field a separate node read (plain loads
   on published nodes since item 9b). This is the path for a sio
   with no JvmSlabCtx under its wrappers, and the fallback for any
   single node a view cannot resolve. kbkh is [kb kh shift0] — the
   precomputed key bytes, their hash, and the starting shift,
   packed because a fn taking primitives supports at most 4 args; a
   missing shift0 is 0 (hamt-get passes key-bytes+hash's [kb kh])."
  [sio ^long root-off kbkh not-found]
    (let [kb (nth kbkh 0)
          kh (long (nth kbkh 1))]
    (loop [off root-off shift (long (nth kbkh 2 0))]
      (if (== off NIL_OFFSET)
        not-found
        (let [nt (alloc/node-u8 sio off 0)]
          (case (int nt)
            ;; Bitmap node
            1 (let [dbm (alloc/node-i32 sio off 4)
                    nbm (alloc/node-i32 sio off 8)
                    bit (node-bit dbm nbm kh shift)]
                (cond
                  (has-bit? nbm bit)
                  (recur (alloc/node-i32 sio off
                                        (+ NODE_HEADER_SIZE (* (get-index nbm bit) 4)))
                         (+ shift SHIFT_STEP))

                  (has-bit? dbm bit)
                  (let [di (get-index dbm bit)
                        hs (hashes-start-off nbm)
                        sh (alloc/node-i32 sio off (+ hs (* di 4)))]
                    (if (not (== sh kh))
                      not-found
                      (let [kvs (kv-data-start-off dbm nbm)
                            pos (kv-pos sio off kvs di)
                            kl (alloc/node-i32 sio off pos)]
                        (if (and (== kl (bytes-length kb))
                                 (alloc/node-bytes-equal? sio off (+ pos 4) kb))
                          (let [vo (+ pos 4 kl)
                                vl (alloc/node-i32 sio off vo)
                                vb (alloc/node-bytes sio off (+ vo 4) vl)]
                            (deserialize-value-bytes vb sio))
                          not-found))))

                  :else not-found))

            ;; Collision node
            3 (let [ch (alloc/node-i32 sio off 4)]
                (if (not (== ch kh))
                  not-found
                  (let [cc (alloc/node-u8 sio off 1)]
                    (loop [i 0 pos COLLISION_HEADER_SIZE]
                      (if (>= i cc)
                        not-found
                        (let [kl (alloc/node-i32 sio off pos)]
                          (if (and (== kl (bytes-length kb))
                                   (alloc/node-bytes-equal? sio off (+ pos 4) kb))
                            (let [vo (+ pos 4 kl)
                                  vl (alloc/node-i32 sio off vo)
                                  vb (alloc/node-bytes sio off (+ vo 4) vl)]
                              (deserialize-value-bytes vb sio))
                            (let [vo (+ pos 4 kl)]
                              (recur (inc i) (+ vo 4 (alloc/node-i32 sio off vo)))))))))))

            ;; Unknown
            not-found))))))

;; The kv walk of hamt-get-view, a named fn: a loop bound in a let is compiled
;; as a closure returning a boxed position (a fresh fn object and a Long per
;; node, measured — fix-for-release E3). Absolute offsets keep it at four
;; args (a fn taking primitives supports at most 4): start is base + the
;; node's kv-data offset, the result is the absolute offset of entry di.
(defn- kv-pos-parts
  ^long [arr rgn ^long start ^long di]
  (loop [i 0 p start]
    (if (== i di)
      p
      (let [vo (+ p 4 (alloc/parts-i32 arr rgn 0 p))]
        (recur (inc i) (+ vo 4 (alloc/parts-i32 arr rgn 0 vo)))))))

(defn- hamt-get-view
  "Find the key in the HAMT at root-off, resolving each node once
   (alloc/node-view) and reading its words through the view. A node
   the view cannot resolve hands off to hamt-get-words at the same
   position and shift. kbkh is [kb kh shift0] (see hamt-get-words)."
  [sio ^long root-off kbkh not-found]
  (let [kb (nth kbkh 0)
        kh (long (nth kbkh 1))]
  (loop [off root-off shift (long (nth kbkh 2 0))]
    (if (== off NIL_OFFSET)
      not-found
      (let [view (alloc/node-view sio off)]
        (if (nil? view)
          (hamt-get-words sio off [kb kh shift] not-found)
          (let [arr (nth view 0)
                rgn (nth view 1)
                base (long (nth view 2))
                nt (alloc/parts-u8 arr rgn base 0)]
            (case (int nt)
              ;; Bitmap node
              1 (let [dbm (alloc/parts-i32 arr rgn base 4)
                      nbm (alloc/parts-i32 arr rgn base 8)
                      bit (node-bit dbm nbm kh shift)]
                  (cond
                    (has-bit? nbm bit)
                    (recur (alloc/parts-i32 arr rgn base
                                          (+ NODE_HEADER_SIZE (* (get-index nbm bit) 4)))
                           (+ shift SHIFT_STEP))

                    (has-bit? dbm bit)
                    (let [di (get-index dbm bit)
                          hs (hashes-start-off nbm)
                          sh (alloc/parts-i32 arr rgn base (+ hs (* di 4)))]
                      (if (not (== sh kh))
                        not-found
                        (let [kvs (kv-data-start-off dbm nbm)
                              pos (- (kv-pos-parts arr rgn (+ base kvs) di) base)
                              kl (alloc/parts-i32 arr rgn base pos)]
                          (if (and (== kl (bytes-length kb))
                                   (alloc/parts-bytes-equal? arr rgn (+ base pos 4) kb))
                            (let [vo (+ pos 4 kl)
                                  vl (alloc/parts-i32 arr rgn base vo)
                                  vb (alloc/parts-bytes arr rgn (+ base vo 4) vl)]
                              (deserialize-value-bytes vb sio))
                            not-found))))

                    :else not-found))

              ;; Collision node
              3 (let [ch (alloc/parts-i32 arr rgn base 4)]
                  (if (not (== ch kh))
                    not-found
                    (let [cc (alloc/parts-u8 arr rgn base 1)]
                      (loop [i 0 pos COLLISION_HEADER_SIZE]
                        (if (>= i cc)
                          not-found
                          (let [kl (alloc/parts-i32 arr rgn base pos)]
                            (if (and (== kl (bytes-length kb))
                                     (alloc/parts-bytes-equal? arr rgn (+ base pos 4) kb))
                              (let [vo (+ pos 4 kl)
                                    vl (alloc/parts-i32 arr rgn base vo)
                                    vb (alloc/parts-bytes arr rgn (+ base vo 4) vl)]
                                (deserialize-value-bytes vb sio))
                              (let [vo (+ pos 4 kl)]
                                (recur (inc i) (+ vo 4 (alloc/parts-i32 arr rgn base vo)))))))))))

              ;; Unknown
              not-found))))))))

(defn- hamt-get
  "Find key k in HAMT rooted at root-off. Returns value or not-found.
   The descent resolves each node once and reads its words through
   the resolved view (alloc/node-view) when the sio is a JvmSlabCtx
   under its wrappers — the per-word readers re-pay the wrapper
   walk, the region lookup, and the offset arithmetic on every
   word (tier-repair round 2, adder A4). Any other sio, and any
   node a view cannot resolve, walks per-word (hamt-get-words).

   History: a fused per-node-view variant (one bulk read per node
   into a fresh byte array, walked locally) was built and measured
   in round 1 and REVERTED the same night: engagement was total
   (599/599 nodes viewed) but it priced ~2x the per-word path -- a
   bulk region read costs ~0.61us, about two plain word loads, and
   the view pays it twice per node (header, then whole node) plus
   the array copies. That shape wanted a zero-copy region view;
   the resolved view above is it (no copies — the node's words are
   read in place through the one resolution). The round-1 finding
   is banked in the sweep STATUS (adder A4)."
  [sio ^long root-off k not-found]
  (let [kbkh (key-bytes+hash k)]
    ;; the incumbent descent's first test: an empty map's root is
    ;; NIL_OFFSET, which is not a node — never hand it to node-view
    ;; (its class bits decode to 7, off the regions array)
    (if (== root-off NIL_OFFSET)
      not-found
      ;; kbkh is [kb kh]: both descents start at shift 0 when it has no third
      ;; element, so it is passed as it is (no per-lookup vector)
      (if (alloc/node-viewable? sio)
        (hamt-get-view sio root-off kbkh not-found)
        (hamt-get-words sio root-off kbkh not-found)))))

;;=============================================================================
;; HAMT Assoc (unified)
;;=============================================================================

(defn hamt-get-bytes
  "Find the entry whose key bytes are kb (hash kh) in the HAMT rooted at
   root-off. Returns the value bytes, or nil when the key is absent. The
   lookup eve.atom's retirement makes: the new tree's value at a retired
   entry's key, without deserialising."
  [sio ^long root-off kh kb]
  (loop [off root-off shift 0]
    (if (== off NIL_OFFSET)
      nil
      (let [nt (alloc/node-u8 sio off 0)]
        (case (int nt)
          1 (let [dbm (alloc/node-i32 sio off 4)
                  nbm (alloc/node-i32 sio off 8)
                  bit (node-bit dbm nbm kh shift)]
              (cond
                (has-bit? nbm bit)
                (recur (alloc/node-i32 sio off
                                      (+ NODE_HEADER_SIZE (* (get-index nbm bit) 4)))
                       (+ shift SHIFT_STEP))

                (has-bit? dbm bit)
                (let [di (get-index dbm bit)
                      hs (hashes-start-off nbm)
                      sh (alloc/node-i32 sio off (+ hs (* di 4)))]
                  (when (== sh kh)
                    (let [kvs (kv-data-start-off dbm nbm)
                          pos (kv-pos sio off kvs di)
                          kl (alloc/node-i32 sio off pos)]
                      (when (and (== kl (bytes-length kb))
                                 (alloc/node-bytes-equal? sio off (+ pos 4) kb))
                        (let [vo (+ pos 4 kl)
                              vl (alloc/node-i32 sio off vo)]
                          (alloc/node-bytes sio off (+ vo 4) vl))))))

                :else nil))

          3 (let [ch (alloc/node-i32 sio off 4)]
              (when (== ch kh)
                (let [cc (alloc/node-u8 sio off 1)]
                  (loop [i 0 pos COLLISION_HEADER_SIZE]
                    (when (< i cc)
                      (let [kl (alloc/node-i32 sio off pos)
                            vo (+ pos 4 kl)
                            vl (alloc/node-i32 sio off vo)]
                        (if (and (== kl (bytes-length kb))
                                 (alloc/node-bytes-equal? sio off (+ pos 4) kb))
                          (alloc/node-bytes sio off (+ vo 4) vl)
                          (recur (inc i) (+ vo 4 vl)))))))))

          nil)))))

(defn hamt-assoc
  "Assoc key/value into HAMT. Returns [new-root added?]."
  [sio root-off kh kb vb shift]
  (if (== root-off NIL_OFFSET)
    [(make-single-entry-node! sio (bitpos kh shift) kh kb vb) true]

    (let [nt (slab/read-u8 sio root-off 0)]
      (case (int nt)
        ;; Bitmap node
        1 (let [data-bm (slab/read-i32 sio root-off 4)
                node-bm (slab/read-i32 sio root-off 8)
                bit (node-bit data-bm node-bm kh shift)]
            (cond
              ;; Child node — descend
              (has-bit? node-bm bit)
              (let [child-idx (get-index node-bm bit)
                    child-off (slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* child-idx 4)))
                    [new-child added?] (hamt-assoc sio child-off kh kb vb (+ shift SHIFT_STEP))]
                (if (== new-child child-off)
                  [root-off false]
                  [(copy-node-patch-child! sio root-off data-bm node-bm child-idx new-child)
                   added?]))

              ;; Inline data at this position
              (has-bit? data-bm bit)
              (let [data-idx (get-index data-bm bit)
                    existing-kh (slab/read-i32 sio root-off
                                               (+ (hashes-start-off node-bm) (* data-idx 4)))
                    kvs (kv-data-start-off data-bm node-bm)
                    pos (loop [i 0 p kvs]
                          (if (== i data-idx) p
                              (recur (inc i) (skip-kv-at sio root-off p))))]
                (if (key-bytes-match? sio root-off pos kb)
                  ;; Same key — replace value
                  (let [kl (slab/read-i32 sio root-off pos)
                        val-off (+ pos 4 kl)
                        vl (slab/read-i32 sio root-off val-off)]
                    (if (and (== vl (bytes-length vb))
                             (slab/bytes-equal-at? sio root-off (+ val-off 4) vb))
                      [root-off false]
                      [(copy-node-replace-kv! sio root-off data-bm node-bm data-idx kh kb vb)
                       false]))
                  ;; Different key — push down
                  (let [kl (slab/read-i32 sio root-off pos)
                        existing-kb (slab/read-bytes sio root-off (+ pos 4) kl)
                        voff (+ pos 4 kl)
                        vl (slab/read-i32 sio root-off voff)
                        existing-vb (slab/read-bytes sio root-off (+ voff 4) vl)]
                    (if (or (== existing-kh kh) (>= shift 30))
                      ;; Collision
                      (let [coll (make-collision-node! sio kh
                                                       [[existing-kh existing-kb existing-vb] [kh kb vb]])
                            new-data-bm (bit-xor data-bm bit)
                            new-node-bm (bit-or node-bm bit)
                            new-child-idx (get-index new-node-bm bit)]
                        [(copy-node-remove-kv-add-child!
                          sio root-off data-bm node-bm new-data-bm new-node-bm
                          data-idx new-child-idx coll)
                         true])
                      ;; Push down to sub-node
                      (let [sub-shift (+ shift SHIFT_STEP)
                            existing-bit (bitpos existing-kh sub-shift)
                            new-bit (bitpos kh sub-shift)]
                        (if (== existing-bit new-bit)
                          (let [[sub _] (hamt-assoc sio NIL_OFFSET existing-kh existing-kb existing-vb sub-shift)
                                [final-sub _] (hamt-assoc sio sub kh kb vb sub-shift)
                                ;; sub (a single-entry node made here, never published) is copied into final-sub,
                                ;; never referenced by it: freed now (eve.set's conj left such nodes allocated)
                                _ (slab/free! sio sub)
                                new-data-bm (bit-xor data-bm bit)
                                new-node-bm (bit-or node-bm bit)
                                new-child-idx (get-index new-node-bm bit)]
                            [(copy-node-remove-kv-add-child!
                              sio root-off data-bm node-bm new-data-bm new-node-bm
                              data-idx new-child-idx final-sub)
                             true])
                          (let [sub-data-bm (bit-or existing-bit new-bit)
                                sub (if (< (unsigned-bit-shift-right existing-bit 0)
                                           (unsigned-bit-shift-right new-bit 0))
                                      (make-two-entry-node! sio sub-data-bm
                                                            existing-kh existing-kb existing-vb kh kb vb)
                                      (make-two-entry-node! sio sub-data-bm
                                                            kh kb vb existing-kh existing-kb existing-vb))
                                new-data-bm (bit-xor data-bm bit)
                                new-node-bm (bit-or node-bm bit)
                                new-child-idx (get-index new-node-bm bit)]
                            [(copy-node-remove-kv-add-child!
                              sio root-off data-bm node-bm new-data-bm new-node-bm
                              data-idx new-child-idx sub)
                             true])))))))

              ;; Empty position — add to data_bitmap
              :else
              (let [data-idx (get-index data-bm bit)
                    new-data-bm (bit-or data-bm bit)]
                [(copy-node-add-kv! sio root-off data-bm new-data-bm node-bm
                                    data-idx kh kb vb)
                 true])))

        ;; Collision node
        3 (let [node-hash (slab/read-i32 sio root-off 4)
                cnt (slab/read-u8 sio root-off 1)]
            (if (== kh node-hash)
              ;; Same hash — add/replace in collision
              (loop [i 0 pos COLLISION_HEADER_SIZE entries []]
                (if (>= i cnt)
                  [(make-collision-node! sio kh (conj entries [kh kb vb])) true]
                  (let [[ekb evb next-pos] (read-kv-bytes-at sio root-off pos)]
                    (if (bytes-equal? ekb kb)
                      ;; Key matches — check value
                      (if (bytes-equal? evb vb)
                        [root-off false]
                        ;; Replace value, collect remaining
                        (let [remaining (loop [j (inc i) p next-pos acc []]
                                          (if (>= j cnt) acc
                                              (let [[rk rv np] (read-kv-bytes-at sio root-off p)]
                                                (recur (inc j) np (conj acc [node-hash rk rv])))))]
                          [(make-collision-node! sio kh (into (conj entries [kh kb vb]) remaining))
                           false]))
                      ;; Continue
                      (recur (inc i) next-pos (conj entries [node-hash ekb evb]))))))
              ;; Different hash — split. Shift 30 still tells the two apart
              ;; (bits 30-31, FORMAT-DECISIONS §6): a node made higher up
              ;; for two keys of one hash is reached there by a key that
              ;; agrees with them on bits 0-29 only, and splits like at any
              ;; shift above (census D1: it was kept with the node's hash,
              ;; where no lookup finds it). Past 30 no bit is left, and a key
              ;; that reaches a node there has the node's hash.
              (if (> shift 30)
                (let [entries (loop [i 0 pos COLLISION_HEADER_SIZE acc []]
                                (if (>= i cnt) acc
                                    (let [[ek ev np] (read-kv-bytes-at sio root-off pos)]
                                      (recur (inc i) np (conj acc [node-hash ek ev])))))]
                  [(make-collision-node! sio node-hash (conj entries [kh kb vb])) true])
                (let [bit1 (bitpos node-hash shift)
                      bit2 (bitpos kh shift)]
                  (if (== bit1 bit2)
                    (let [[new-child _] (hamt-assoc sio root-off kh kb vb (+ shift SHIFT_STEP))]
                      [(make-single-child-node! sio bit1 new-child) true])
                    [(make-child-and-entry-node! sio bit2 bit1 root-off kh kb vb) true])))))

        ;; Unknown
        [root-off false]))))

;;=============================================================================
;; HAMT Dissoc (unified)
;;=============================================================================

(defn- hamt-dissoc
  "Remove key from HAMT. Returns [new-root removed?]."
  [sio root-off kh kb shift]
  (if (== root-off NIL_OFFSET)
    [root-off false]
    (let [nt (slab/read-u8 sio root-off 0)]
      (case (int nt)
        ;; Bitmap node
        1 (let [data-bm (slab/read-i32 sio root-off 4)
                node-bm (slab/read-i32 sio root-off 8)
                bit (node-bit data-bm node-bm kh shift)]
            (cond
              ;; Child — descend
              (has-bit? node-bm bit)
              (let [child-idx (get-index node-bm bit)
                    child-off (slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* child-idx 4)))
                    [new-child removed?] (hamt-dissoc sio child-off kh kb (+ shift SHIFT_STEP))]
                (if-not removed?
                  [root-off false]
                  (if (== new-child NIL_OFFSET)
                    ;; Child empty — remove from node_bitmap
                    ;; TODO: compact node if only 1 entry remains
                    [(copy-node-patch-child! sio root-off data-bm node-bm child-idx NIL_OFFSET)
                     true]
                    [(copy-node-patch-child! sio root-off data-bm node-bm child-idx new-child)
                     true])))

              ;; Inline data
              (has-bit? data-bm bit)
              (let [data-idx (get-index data-bm bit)
                    kvs (kv-data-start-off data-bm node-bm)
                    pos (loop [i 0 p kvs]
                          (if (== i data-idx) p
                              (recur (inc i) (skip-kv-at sio root-off p))))]
                (if (key-bytes-match? sio root-off pos kb)
                  ;; Found — remove it
                  (let [new-data-bm (bit-xor data-bm bit)
                        dc (popcount32 new-data-bm)
                        cc (popcount32 node-bm)]
                    (if (and (zero? dc) (zero? cc))
                      [NIL_OFFSET true]
                      ;; Rebuild without this entry
                      ;; Read all children, hashes, kvs except removed
                      (let [old-dc (popcount32 data-bm)
                            children (mapv #(slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* % 4))) (range cc))
                            h-off (hashes-start-off node-bm)
                            hashes (vec (keep-indexed
                                         (fn [i _] (when (not= i data-idx)
                                                     (slab/read-i32 sio root-off (+ h-off (* i 4)))))
                                         (range old-dc)))
                            kvs-list (let [kv-off (kv-data-start-off data-bm node-bm)]
                                       (loop [i 0 pos kv-off acc []]
                                         (if (>= i old-dc) acc
                                             (let [[ek ev np] (read-kv-bytes-at sio root-off pos)]
                                               (recur (inc i) np
                                                      (if (== i data-idx) acc (conj acc [ek ev])))))))]
                        ;; Write new node
                        (let [kvs-size (reduce (fn [a [kb vb]] (+ a (calc-kv-size kb vb))) 0 kvs-list)
                              nsize (+ NODE_HEADER_SIZE (* 4 cc) (* 4 dc) kvs-size)
                              new-off (slab/alloc! sio nsize)]
                          (slab/write-u8! sio new-off 0 NODE_TYPE_BITMAP)
                          (slab/write-u8! sio new-off 1 0)
                          (slab/write-u16! sio new-off 2 (kv-total-cache kvs-size))
                          (slab/write-i32! sio new-off 4 new-data-bm)
                          (slab/write-i32! sio new-off 8 node-bm)
                          (dotimes [i cc]
                            (slab/write-i32! sio new-off (+ NODE_HEADER_SIZE (* i 4)) (nth children i)))
                          (let [new-h-off (hashes-start-off node-bm)]
                            (dotimes [i dc]
                              (slab/write-i32! sio new-off (+ new-h-off (* i 4)) (nth hashes i))))
                          (reduce (fn [p [ek ev]] (write-kv! sio new-off p ek ev))
                                  (kv-data-start-off new-data-bm node-bm) kvs-list)
                          [new-off true]))))
                  ;; Key doesn't match
                  [root-off false]))

              :else [root-off false]))

        ;; Collision node
        3 (let [node-hash (slab/read-i32 sio root-off 4)
                cnt (slab/read-u8 sio root-off 1)]
            (if (not= kh node-hash)
              [root-off false]
              (let [entries (loop [i 0 pos COLLISION_HEADER_SIZE acc []]
                              (if (>= i cnt) acc
                                  (let [[ek ev np] (read-kv-bytes-at sio root-off pos)]
                                    (recur (inc i) np (conj acc [ek ev])))))]
                (if-let [idx (some (fn [i] (when (bytes-equal? (first (nth entries i)) kb) i))
                                   (range cnt))]
                  (if (== cnt 1)
                    [NIL_OFFSET true]
                    (let [remaining (vec (concat (subvec entries 0 idx)
                                                 (subvec entries (inc idx))))
                          new-entries (mapv (fn [[ek ev]] [node-hash ek ev]) remaining)]
                      [(make-collision-node! sio kh new-entries) true]))
                  [root-off false]))))

        ;; Unknown
        [root-off false]))))

;;=============================================================================
;; HAMT Seq / Reduce (unified)
;;=============================================================================

(defn hamt-kv-reduce
  "Walk HAMT tree, calling (f acc k v) at each entry. Supports reduced?."
  [sio ^long root-off f init]
  (if (== root-off NIL_OFFSET)
    init
    (let [nt (slab/read-u8 sio root-off 0)]
      (case (int nt)
        ;; Bitmap node
        1 (let [data-bm (slab/read-i32 sio root-off 4)
                node-bm (slab/read-i32 sio root-off 8)
                dc (popcount32 data-bm)
                cc (popcount32 node-bm)
                kv-off (kv-data-start-off data-bm node-bm)
                ;; Process inline entries
                acc (loop [i 0 pos kv-off acc init]
                      (if (or (>= i dc) (reduced? acc))
                        acc
                        (let [kl (slab/read-i32 sio root-off pos)
                              kb (slab/read-bytes sio root-off (+ pos 4) kl)
                              vo (+ pos 4 kl)
                              vl (slab/read-i32 sio root-off vo)
                              vb (slab/read-bytes sio root-off (+ vo 4) vl)
                              k (deserialize-value-bytes kb sio)
                              v (deserialize-value-bytes vb sio)]
                          (recur (inc i) (+ vo 4 vl) (f acc k v)))))]
            ;; Process child nodes
            (if (reduced? acc)
              acc
              (loop [i 0 acc acc]
                (if (or (>= i cc) (reduced? acc))
                  acc
                  (let [child-off (slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* i 4)))
                        new-acc (hamt-kv-reduce sio child-off f acc)]
                    (recur (inc i) new-acc))))))

        ;; Collision node
        3 (let [cnt (slab/read-u8 sio root-off 1)]
            (loop [i 0 pos COLLISION_HEADER_SIZE acc init]
              (if (or (>= i cnt) (reduced? acc))
                acc
                (let [kl (slab/read-i32 sio root-off pos)
                      kb (slab/read-bytes sio root-off (+ pos 4) kl)
                      vo (+ pos 4 kl)
                      vl (slab/read-i32 sio root-off vo)
                      vb (slab/read-bytes sio root-off (+ vo 4) vl)
                      k (deserialize-value-bytes kb sio)
                      v (deserialize-value-bytes vb sio)]
                  (recur (inc i) (+ vo 4 vl) (f acc k v))))))

        ;; Unknown
        init))))

(defn- hamt-seq
  "Build a lazy seq of [k v] MapEntry pairs from HAMT."
  [sio ^long root-off]
  (let [entries (volatile! (transient []))]
    (hamt-kv-reduce sio root-off
                    (fn [_ k v]
                      (vswap! entries conj!
                              (enc/map-entry k v)))
                    nil)
    (seq (persistent! @entries))))

;;=============================================================================
;; Map header read/write
;;=============================================================================

(defn write-map-header!
  "Allocate and write a 12-byte EveHashMap header. Returns slab offset."
   [sio ^long cnt ^long root-off]
  (let [off (slab/alloc! sio 12)]
    (slab/write-u8! sio off 0 EveHashMap-type-id)
    (slab/write-u8! sio off 1 1) ;; flags
    (slab/write-u16! sio off 2 0)
    (slab/write-i32! sio off SABMAPROOT_CNT_OFFSET cnt)
    (slab/write-i32! sio off SABMAPROOT_ROOT_OFF_OFFSET root-off)
    off))

(defn read-map-header
  "Read cnt and root-off from an EveHashMap header. Returns [cnt root-off]."
  [sio ^long header-off]
  [(slab/read-i32 sio header-off SABMAPROOT_CNT_OFFSET)
   (slab/read-i32 sio header-off SABMAPROOT_ROOT_OFF_OFFSET)])

;;=============================================================================
;; EveHashMap deftype — unified via eve/deftype macro
;;
;; Fields: cnt (int32 @ offset 4), root-off (int32 @ offset 8)
;; Both platforms: deftype EveHashMap [sio__ offset__]
;;=============================================================================

(declare make-hash-map)
(declare ->transient-map)
(declare eve-hash-map?)

;; A map's equality where the host's own map equality does not reach an eve
;; map (ClojureScript, clojerl): the same entries, by value.
(defn- map-equiv? [sio root-off cnt other]
  (and (map? other)
       (== cnt (count other))
       (every? (fn [[k v]]
                 (let [found (hamt-get sio root-off k absent)]
                   (and (not (identical? found absent)) (= v found))))
               other)))

(defn- root-digest-verdict
  "Merkle Phase 4 (§2b) digest seam for equiv — ported from canonical eve
   (commit 366e9b2); wired to
   the Phase 3 eve cache (eve.digest, layout B-prime) 2026-10-25. Answers
   :equal or :unequal for two EveHashMap roots whose content digests
   decide, and :walk for everything else (a root of any other type, a
   root whose content has no digest — anything held by reference — any
   doubt). The verdict itself carries the partition gate — it must answer
   :walk unless BOTH roots are EveHashMap roots with comparable digests —
   because this type's equiv delegates its structural check to
   map-equiv?. equiv falls back to map-equiv? on :walk."
  [a b]
  (if (eve-hash-map? b)
    (dg/digest-verdict (d/-eve-sio a) (d/-root-header-off a) a
                       (d/-eve-sio b) (d/-root-header-off b) b)
    :walk))

(eve/deftype EveHashMap [^:int32 cnt ^:int32 root-off]
  ;; --- Unified protocols (CLJ names) ---

  clojure.lang.Counted
  (count [_] (int cnt))

  clojure.lang.Seqable
  (seq [_]
    (when (pos? cnt)
      (hamt-seq sio__ root-off)))

  clojure.lang.IMeta
  (meta [_] nil)

  clojure.lang.IObj
  (withMeta [this m] this)

  clojure.lang.ILookup
  (valAt [_ k] (hamt-get sio__ root-off k nil))
  (valAt [_ k not-found]
    (hamt-get sio__ root-off k not-found))

  clojure.lang.Associative
  (containsKey [_ k]
    (not (identical? (hamt-get sio__ root-off k absent) absent)))
  (assoc [this k v]
    (let [sio sio__
          kb (serialize-key-bytes k)
          vb (serialize-val-bytes sio v)
          kh (portable-hash-bytes kb)
          [new-root added?] (hamt-assoc sio root-off kh kb vb 0)]
      (if (== new-root root-off)
        this
        (make-hash-map sio (if added? (inc cnt) cnt) new-root))))

  clojure.lang.IPersistentMap
  (without [this k]
    (let [sio sio__
          kb (serialize-key-bytes k)
          kh (portable-hash-bytes kb)
          [new-root removed?] (hamt-dissoc sio root-off kh kb 0)]
      (if-not removed?
        this
        (if (== new-root NIL_OFFSET)
          (make-hash-map sio 0 NIL_OFFSET)
          (make-hash-map sio (dec cnt) new-root)))))
  ;; (the JVM's alone: dropped on ClojureScript)
  (entryAt [_ k]
    (let [v (hamt-get sio__ root-off k absent)]
      (when-not (identical? v absent)
        (clojure.lang.MapEntry/create k v))))
  (assocEx [this k v]
    (if (.containsKey this k)
      (throw (RuntimeException. (str "Key already present: " k)))
      (.assoc this k v)))

  clojure.lang.IPersistentCollection
  ;; Clojure's conj onto a map: a map's entries, a [k v] pair, a seq of
  ;; entries, or a map entry
  (cons [this entry]
    (cond
      (map? entry) (reduce-kv (fn [m k v] (assoc m k v)) this entry)
      ;; an Eve vector is a [k v] pair as a host vector is (on jank vector? is blind to it: identity audit
      ;; B-VEC-ENTRY — it fell to the sequential arm, its key and value conj'd as two entries)
      (or (vector? entry) (evec/eve-vector? entry)) (.assoc this (ecore/nth entry 0) (ecore/nth entry 1))
      (sequential? entry) (reduce conj this entry)
      :else (.assoc this (key entry) (val entry))))
  (empty [_]
    (make-hash-map sio__ 0 NIL_OFFSET))
  (equiv [this other]
    (let [verdict (root-digest-verdict this other)]
      (cond
        (= verdict :equal) true
        (= verdict :unequal) false
        :else (map-equiv? sio__ root-off cnt other))))

  clojure.lang.IReduce
  (reduce [_ f]
    (if (zero? cnt)
      (f)
      (let [acc (volatile! ::none)]
        (hamt-kv-reduce sio__ root-off
                        (fn [_ k v]
                          (let [e (enc/map-entry k v)]
                            (if (identical? ::none @acc)
                              (vreset! acc e)
                              (vreset! acc (f @acc e)))))
                        nil)
        (unreduced @acc))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (let [sio sio__]
      (unreduced
       (hamt-kv-reduce sio root-off
                       (fn [acc k v] (f acc (enc/map-entry k v)))
                       init))))

  clojure.lang.IKVReduce
  (kvreduce [_ f init]
    (let [result (hamt-kv-reduce sio__ root-off f init)]
      (unreduced result)))

  clojure.lang.IHashEq
  (hasheq [this] (dg/root-hash sio__ offset__ (fn [] (p/hash-unordered this))))

  clojure.lang.IFn
  (invoke [this k] (hamt-get sio__ root-off k nil))
  (invoke [this k nf] (hamt-get sio__ root-off k nf))

  

  d/IDirectSerialize
  (-direct-serialize [this] offset__)

  d/ISabStorable
  (-sab-tag [_] :eve-map)
  (-sab-encode [this _] (codec/encode-eve-pointer this))
  (-sab-dispose [_ _] nil)

  d/IsEve
  (-eve? [_] true)

  d/IEveRoot
  (-root-header-off [this] offset__)

  d/IEveSio
  (-eve-sio [_] sio__)

  ;; what a deref of an atom holding it answers: a plain Clojure map.
  ;; eve->clj's (map? v) branch cannot see this type on every host — on
  ;; jank the core predicates are false for Eve types — so the type
  ;; carries its own conversion, as the int-map and the sorted types do.
  d/IPlain
  (-plain [this] (into {} (map (fn [kv] [(d/eve->clj (key kv)) (d/eve->clj (val kv))])) (ecore/seq this)))

  ;; --- the JVM's (dropped on ClojureScript, but IEditableCollection; on the
  ;; CLR java.util.Map is dropped and Iterable is IEnumerable) ---
  clojure.lang.MapEquivalence

  java.lang.Iterable
  (iterator [this]
            (clojure.lang.SeqIterator. (.seq this)))

  java.util.Map
  (size [_] (int cnt))
  (isEmpty [_] (zero? cnt))
  (containsValue [_ _v] (throw (UnsupportedOperationException.)))
  (get [this k] (.valAt this k))
  (put [_ _ _] (throw (UnsupportedOperationException.)))
  (remove [_ _] (throw (UnsupportedOperationException.)))
  (putAll [_ _] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.)))
  (keySet [this] (set (keys this)))
  (values [this] (vals this))
  (entrySet [this] (set (.seq this)))

  clojure.lang.IEditableCollection
  (asTransient [this]
    (eve.map/->transient-map this))

  java.lang.Object
  (toString [this] (pr-str this))
  (equals [this other]
          (clojure.lang.APersistentMap/mapEquals this other))
  (hashCode [this]
            (enc/map-hash-code this))) ;; end eve/deftype

;;=============================================================================
;; Constructors
;;=============================================================================

(defn eve-hash-map?
  "True for a slab-backed Eve hash map of this host (SCI does not resolve a
   deftype through a namespace alias, so callers test through this fn)."
  [x]
  ;; by its type key, which every eve type carries: a sci map-mode instance is
  ;; not instance? of its own type name (measured on bb 2026-10-15)
  (and (satisfies? d/ISabpType x) (= EveHashMap-type-key (d/-sabp-type-key x))))

(do
(defn- make-hash-map
  "Internal constructor: allocate header, create EveHashMap."
  [sio ^long cnt ^long root-off]
  (let [hdr (write-map-header! sio cnt root-off)]
    (EveHashMap. sio hdr)))

(defn hash-map-from-header
  "Reconstruct an EveHashMap from an existing header offset."
  ([sio ^long header-off]
   (EveHashMap. sio header-off)))

(defn empty-hash-map
  "Create an empty Eve hash map.
   0-arity: uses platform default sio (fresh heap domain on JVM).
   1-arity: explicit sio."
  ([] (empty-hash-map (default-sio)))
  ([sio] (make-hash-map sio 0 NIL_OFFSET)))

(defn hash-map
  "Create an Eve hash map from key-value pairs.
   If first arg satisfies ISlabIO, uses it as sio and rest as kvs.
   Otherwise uses platform default sio and all args as kvs.
   (hash-map) → empty map
   (hash-map k v ...) → map with pairs
   (hash-map sio k v ...) → map with pairs using explicit sio"
  [& args]
  (let [sio-or-default (default-sio)
        [sio kvs] (if (and (seq args) (satisfies? alloc/ISlabIO (first args)))
                    [(first args) (rest args)]
                    [sio-or-default args])]
    ;; eve.core's assoc: an eve map is a value of eve.core's vocabulary on every host
    ;; (clojure.core's does not reach an eve type on ValueScript)
    (reduce (fn [m [k v]] (ecore/assoc m k v))
            (empty-hash-map sio)
            (partition 2 kvs))))

(defn into-hash-map
  "Create an Eve hash map from a collection of [k v] entries.
   1-arity: uses platform default sio (fresh heap domain on JVM).
   2-arity: explicit sio."
  ([coll] (into-hash-map (default-sio) coll))
  ([sio coll]
   (reduce (fn [m [k v]] (ecore/assoc m k v))
           (empty-hash-map sio)
           coll))))

;;=============================================================================
;; Transients: empty-source builds batch canonical encoded entries and write
;; each final node once. Observation/removal flushes the batch. Nonempty
;; sources and post-flush edits use the persistent path with owned-node reclaim.
;;=============================================================================

(declare collect-retire-diff-offsets)

(defn collect-key-path-diff-offsets
  "The blocks map old-map holds that new-map does not, when new-map is old-map
   with one key written (an assoc or a dissoc of the key whose bytes are kb):
   a one-key write path-copies only the nodes on that key's hash path — a slot
   split, a collapse and a collision node all sit on it — so the two paths are
   stepped together to the first node they share; and the old header. Depth
   reads, where the full walk reads every child slot of each node on the path."
  [old-map new-map kb]
  (let [sio (d/-eve-sio old-map)
        kh (portable-hash-bytes kb)
        root-of (fn [m] (slab/read-i32 sio (d/-root-header-off m) SABMAPROOT_ROOT_OFF_OFFSET))
        ;; the child on kh's path, NIL where kh's slot holds none
        down (fn [off shift]
               (if (and (not= off NIL_OFFSET) (== NODE_TYPE_BITMAP (slab/read-u8 sio off 0)))
                 (let [dbm (slab/read-i32 sio off 4)
                       nbm (slab/read-i32 sio off 8)
                       bit (node-bit dbm nbm kh shift)]
                   (if (has-bit? nbm bit)
                     (slab/read-i32 sio off (+ NODE_HEADER_SIZE (* (get-index nbm bit) 4)))
                     NIL_OFFSET))
                 NIL_OFFSET))]
    (loop [o (root-of old-map) n (root-of new-map) shift 0 out [(d/-root-header-off old-map)]]
      (if (or (== o NIL_OFFSET) (== o n))
        out
        (recur (down o shift) (down n shift) (+ shift SHIFT_STEP) (conj out o))))))

(defn- edit
  "(f m), m read through sio (a transient's allocation log), and the blocks of
   m it superseded that sio made freed — by (walk m m'), the full walk unless
   given one — the walk between two versions only the transient holds, never
   a block of the map it began from (the operator's
   ruling of 2026-10-21 ET). A value an op replaces that is a collection
   stays: a value read out of the transient may be in use."
  ([sio m f] (edit sio m f collect-retire-diff-offsets))
  ([sio m f walk]
   (let [m' (f m)]
     ;; the walk names the old header even when an op answers the same map
     (when-not (== (d/-root-header-off m) (d/-root-header-off m'))
       (alloc/free-owned! sio (walk m m')))
     m')))

(defn- key-walk
  "The walk of a one-key write of k: its hash path."
  [k]
  (let [kb (serialize-key-bytes k)]
    (fn [m m'] (collect-key-path-diff-offsets m m' kb))))


(defn- build-batch-map [sio entries]
  (let [entries (batch/unique-entries entries)
        root (if (empty? entries) NIL_OFFSET
                 (batch/build
                  entries 0
                  (fn [db nb data children]
                    (let [kv-size (reduce (fn [n [_ kb vb]] (+ n (calc-kv-size kb vb))) 0 data)
                          off (slab/alloc! sio (+ NODE_HEADER_SIZE (* 4 (count children))
                                                 (* 4 (count data)) kv-size))]
                      (slab/write-u8! sio off 0 NODE_TYPE_BITMAP)
                      (slab/write-u8! sio off 1 0)
                      (slab/write-u16! sio off 2 (kv-total-cache kv-size))
                      (slab/write-i32! sio off 4 db)
                      (slab/write-i32! sio off 8 nb)
                      (doseq [[i child] (map-indexed vector children)]
                        (slab/write-i32! sio off (+ NODE_HEADER_SIZE (* i 4)) child))
                      (doseq [[i [kh]] (map-indexed vector data)]
                        (slab/write-i32! sio off (+ (hashes-start-off nb) (* i 4)) kh))
                      (reduce (fn [p [_ kb vb]] (write-kv! sio off p kb vb))
                              (kv-data-start-off db nb) data)
                      off))
                  (fn [entries] (make-collision-node! sio (ffirst entries) entries))))]
    (hash-map-from-header sio (write-map-header! sio (count entries) root))))

(defn- batch-map-entry [sio k v]
  (let [[kb kh] (key-bytes+hash k)]
    [kh kb (serialize-val-bytes sio v)]))

(do
     (eve/host-deftype TransientEveHashMap [^:volatile-mutable m sio ^:volatile-mutable pending]
       clojure.lang.ITransientMap
       (assoc [this k v]
         (if pending
           (set! pending (conj! pending (batch-map-entry sio k v)))
           (set! m (edit sio m #(assoc % k v) (key-walk k))))
         this)
       (without [this k]
         (when pending
           (set! m (build-batch-map sio (persistent! pending)))
           (set! pending nil))
         (set! m (edit sio m #(dissoc % k) (key-walk k)))
         this)
       (persistent [_]
         (when pending
           (set! m (build-batch-map sio (persistent! pending)))
           (set! pending nil))
         (alloc/stop-logging! sio) m)
       (conj [this x]
         (if (or (map-entry? x) (and (vector? x) (= 2 (count x))))
           (if pending
             (set! pending (conj! pending (batch-map-entry sio (nth x 0) (nth x 1))))
             (set! m (edit sio m #(assoc % (nth x 0) (nth x 1)) (key-walk (nth x 0)))))
           (do
             (when pending
               (set! m (build-batch-map sio (persistent! pending)))
               (set! pending nil))
             (set! m (edit sio m #(conj % x)))))
         this)
       clojure.lang.Counted
       (count [_]
         (when pending
           (set! m (build-batch-map sio (persistent! pending)))
           (set! pending nil))
         (count m))
       clojure.lang.ILookup
       (valAt [_ k]
         (when pending
           (set! m (build-batch-map sio (persistent! pending)))
           (set! pending nil))
         (get m k))
       (valAt [_ k nf]
         (when pending
           (set! m (build-batch-map sio (persistent! pending)))
           (set! pending nil))
         (get m k nf)))
     (defn ->transient-map [m]
       (let [sio (alloc/scope-sio (d/-eve-sio m))]
         (->TransientEveHashMap (hash-map-from-header sio (d/-root-header-off m)) sio
                                (when (zero? (count m)) (transient []))))))

;;=============================================================================
;; Debug no-ops (API compat) and the direct map encoder
;;=============================================================================

(defn untrack-debug-offset!
  "No-op — pool system has been removed. Kept for atom.cljc API compat."
  [_slab-offset]
  nil)

;; No-op pool stubs — pool system removed, kept for backward compat
(defn reset-pools! [] nil)
(defn drain-pools! [] nil)
(defn enable-pool-track! [] nil)
(defn disable-pool-track! [] nil)

;; The direct map encoder: how a codec that builds a host map into eve memory
;; (ClojureScript's) writes a plain map nested in a value. The codec hands
;; over the running encode's sio — the domain the caller of encode-value
;; named — and the nested map is built THERE, never in whatever domain
;; happens to be ambient: with an explicit sio that differs from the bound
;; one, the ambient choice silently put a second domain inside one encoded
;; value (and under the construction law it throws despite the supplied
;; domain — the law lane's P2 finding). Outside an encode (sio nil) the
;; ambient domain is the only one there is, as before.
(codec/set-direct-map-encoder!
 (fn [sio m]
   (let [sio (or sio (alloc/current-sio))
         eve-m (into-hash-map sio m)]
     ;; built here from host data, held here only: no stored piece to report (9.31)
     (codec/encode-fresh-pointer eve-m))))

;;=============================================================================
;; Free / Retire / Dispose
;;=============================================================================

(defn- free-hamt-node!
  "Recursively free a HAMT node and all its children via ISlabIO."
  [sio slab-off]
  (when (not= slab-off NIL_OFFSET)
    (let [node-type (slab/read-u8 sio slab-off 0)]
      (case (int node-type)
             ;; Bitmap node — read children BEFORE freeing to avoid use-after-free
        1 (let [node-bm (slab/read-i32 sio slab-off 8)
                child-count (popcount32 node-bm)
                children (mapv #(slab/read-i32 sio slab-off (+ NODE_HEADER_SIZE (* % 4)))
                               (range child-count))]
            (slab/free! sio slab-off)
            (doseq [child-off children]
              (free-hamt-node! sio child-off)))
             ;; Collision node — no children
        3 (slab/free! sio slab-off)
             ;; Unknown
        (slab/free! sio slab-off)))))

(defn dispose!
  "Dispose an EveHashMap, freeing its entire HAMT tree and header block."
  [eve-map]
  (let [sio (d/-eve-sio eve-map)
        header-off (d/-root-header-off eve-map)
        root-off (slab/read-i32 sio header-off SABMAPROOT_ROOT_OFF_OFFSET)]
    (when (not= root-off NIL_OFFSET)
      (free-hamt-node! sio root-off))
    (when (not= header-off NIL_OFFSET)
      (slab/free! sio header-off))))

(defn retire-replaced-path!
  "After an atom swap that replaced old-root with new-root, free the old
        path nodes that are no longer referenced by the new tree.
        Walks both trees following the hash bits for key kh."
  [sio old-root new-root kh]
  (when (and (not= old-root NIL_OFFSET) (not= old-root new-root))
    (loop [old-off old-root
           new-off new-root
           sh 0]
      (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
             ;; Read data BEFORE freeing
        (let [old-type (slab/read-u8 sio old-off 0)
              [old-child new-child next-sh]
              (when (== old-type NODE_TYPE_BITMAP)
                (let [old-node-bm (slab/read-i32 sio old-off 8)
                      old-bit (node-bit (slab/read-i32 sio old-off 4) old-node-bm kh sh)
                      new-type (when (not= new-off NIL_OFFSET) (slab/read-u8 sio new-off 0))
                      new-node-bm (when (and new-type (== new-type NODE_TYPE_BITMAP))
                                    (slab/read-i32 sio new-off 8))
                      new-bit (when new-node-bm
                                (node-bit (slab/read-i32 sio new-off 4) new-node-bm kh sh))]
                  (when (and (has-bit? old-node-bm old-bit)
                             new-node-bm
                             (has-bit? new-node-bm new-bit))
                    (let [old-child-idx (get-index old-node-bm old-bit)
                          new-child-idx (get-index new-node-bm new-bit)]
                      [(slab/read-i32 sio old-off (+ NODE_HEADER_SIZE (* old-child-idx 4)))
                       (slab/read-i32 sio new-off (+ NODE_HEADER_SIZE (* new-child-idx 4)))
                       (+ sh SHIFT_STEP)]))))]
               ;; Free this old node
          (slab/free! sio old-off)
               ;; Continue down the hash path
          (when old-child
            (recur old-child new-child next-sh)))))))

(defn retire-tree-diff!
  "Full tree diff: walk old and new HAMT trees in parallel, freeing all
        old nodes that differ from the new tree."
  [sio old-root new-root]
  (when (and (not= old-root NIL_OFFSET) (not= old-root new-root))
    (letfn [(walk [old-off new-off]
              (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
                     ;; Read children BEFORE freeing
                (let [old-type (slab/read-u8 sio old-off 0)
                      children-to-walk
                      (when (== old-type NODE_TYPE_BITMAP)
                        (let [old-node-bm (slab/read-i32 sio old-off 8)
                              new-type (when (not= new-off NIL_OFFSET) (slab/read-u8 sio new-off 0))
                              new-node-bm (when (and new-type (== new-type NODE_TYPE_BITMAP))
                                            (slab/read-i32 sio new-off 8))]
                          (loop [remaining (bit-and old-node-bm 0xFFFFFFFF) ;; unsigned: bit 31 as a negative long never reaches zero
                                 old-idx 0
                                 result (transient [])]
                            (if (zero? remaining)
                              (persistent! result)
                              (let [bit (bit-and remaining (- remaining))
                                    old-child (slab/read-i32 sio old-off (+ NODE_HEADER_SIZE (* old-idx 4)))
                                    new-child (if (and new-node-bm (has-bit? new-node-bm bit))
                                                (let [new-idx (get-index new-node-bm bit)]
                                                  (slab/read-i32 sio new-off (+ NODE_HEADER_SIZE (* new-idx 4))))
                                                NIL_OFFSET)]
                                (recur (bit-and remaining (dec remaining))
                                       (inc old-idx)
                                       (conj! result [old-child new-child])))))))]
                       ;; Free this old node
                  (slab/free! sio old-off)
                       ;; Walk children
                  (doseq [[old-child new-child] children-to-walk]
                    (walk old-child new-child)))))]
      (walk old-root new-root))))

(defn collect-tree-diff-offsets
  "Like retire-tree-diff! but COLLECTS slab-qualified offsets instead of freeing.
        Returns a vector of old-tree node offsets that differ from new-tree."
  [sio old-root new-root]
  (if (or (== old-root NIL_OFFSET) (== old-root new-root))
    []
    (let [result (volatile! (transient []))]
      (letfn [(walk [old-off new-off]
                (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
                  (let [old-type (slab/read-u8 sio old-off 0)
                        children-to-walk
                        (when (== old-type NODE_TYPE_BITMAP)
                          (let [old-node-bm (slab/read-i32 sio old-off 8)
                                new-type (when (not= new-off NIL_OFFSET) (slab/read-u8 sio new-off 0))
                                new-node-bm (when (and new-type (== new-type NODE_TYPE_BITMAP))
                                              (slab/read-i32 sio new-off 8))]
                            ;; the bitmap as an unsigned 32-bit value: a set bit 31 read as a
                            ;; negative long never reaches zero under (bit-and r (dec r))
                            (loop [remaining (bit-and old-node-bm 0xFFFFFFFF)
                                   old-idx 0
                                   pairs (transient [])]
                              (if (zero? remaining)
                                (persistent! pairs)
                                (let [bit (bit-and remaining (- remaining))
                                      old-child (slab/read-i32 sio old-off (+ NODE_HEADER_SIZE (* old-idx 4)))
                                      new-child (if (and new-node-bm (has-bit? new-node-bm bit))
                                                  (let [new-idx (get-index new-node-bm bit)]
                                                    (slab/read-i32 sio new-off (+ NODE_HEADER_SIZE (* new-idx 4))))
                                                  NIL_OFFSET)]
                                  (recur (bit-and remaining (dec remaining))
                                         (inc old-idx)
                                         (conj! pairs [old-child new-child])))))))]
                    (vswap! result conj! old-off)
                    (doseq [[old-child new-child] children-to-walk]
                      (walk old-child new-child)))))]
        (walk old-root new-root))
      (persistent! @result))))

(defn scan-node-value-sab-ptrs
  "Scan a HAMT bitmap node's inline values for SAB pointer tags (0x10-0x13, 0x1C, and an eve-deftype's type-id,
   0x40-0xEC). Returns a vector of [tag nested-offset key-bytes] for each nested Eve collection."
  [sio ^long node-off]
  (let [node-type (slab/read-u8 sio node-off 0)]
    (when (== node-type NODE_TYPE_BITMAP)
      (let [data-bm (slab/read-i32 sio node-off 4)
            node-bm (slab/read-i32 sio node-off 8)
            dc (popcount32 data-bm)
            kv-off (kv-data-start-off data-bm node-bm)]
        (loop [i 0 pos kv-off result (transient [])]
          (if (>= i dc)
            (persistent! result)
            (let [klen (slab/read-i32 sio node-off pos)
                  val-pos (+ pos 4 klen)
                  vlen (slab/read-i32 sio node-off val-pos)
                  val-data-pos (+ val-pos 4)
                  next-pos (+ val-data-pos vlen)
                  result (if (>= vlen 7)
                           (let [m0 (slab/read-u8 sio node-off val-data-pos)
                                 m1 (slab/read-u8 sio node-off (+ val-data-pos 1))]
                             (if (and (== m0 0xEE) (== m1 0xDB))
                               (let [tag (slab/read-u8 sio node-off (+ val-data-pos 2))]
                                 (if (or (and (>= tag 0x10) (<= tag 0x13))
                                         (== tag 0x1C)
                                         ;; an eve-deftype value (an int-map, a sorted collection)
                                         (and (>= tag 0x40) (<= tag 0xEC)))
                                   (let [nested-off (slab/read-i32 sio node-off (+ val-data-pos 3))]
                                     ;; [tag nested-off key-bytes]: the key is what eve.atom's
                                     ;; retirement looks up in the new tree
                                     (conj! result [tag nested-off (slab/read-bytes sio node-off (+ pos 4) klen)]))
                                   result))
                               result))
                           result)]
              (recur (inc i) next-pos result))))))))

(defn collect-all-tree-sab-ptrs
  "Walk entire HAMT tree and collect all SAB pointer offsets from inline values.
   Returns a set of nested-offset values."
  [sio ^long root-off]
  (if (== root-off NIL_OFFSET)
    #{}
    (let [result (volatile! (transient #{}))]
      (letfn [(walk [off]
                (when (not= off NIL_OFFSET)
                  (let [node-type (slab/read-u8 sio off 0)]
                    (when (== node-type NODE_TYPE_BITMAP)
                      (doseq [[_tag nested-off] (scan-node-value-sab-ptrs sio off)]
                        (vswap! result conj! nested-off))
                      (let [node-bm (slab/read-i32 sio off 8)
                            cc (popcount32 node-bm)]
                        (dotimes [i cc]
                          (walk (slab/read-i32 sio off (+ NODE_HEADER_SIZE (* i 4))))))))))]
        (walk root-off))
      (persistent! @result))))

(defn collect-retire-diff-offsets
  "Collect all slab offsets that would be freed by -sab-retire-diff!.
   Includes both HAMT tree nodes and the header block.
   Returns a vector of offsets to free when the epoch is safe."
  [old-map new-value]
  (let [sio (d/-eve-sio old-map)
        header-off (d/-root-header-off old-map)
        root-off (slab/read-i32 sio header-off SABMAPROOT_ROOT_OFF_OFFSET)]
    (if (eve-hash-map? new-value)
      (let [new-header (d/-root-header-off new-value)
            other-root (slab/read-i32 sio new-header SABMAPROOT_ROOT_OFF_OFFSET)
            node-offsets (collect-tree-diff-offsets sio root-off other-root)]
        (if (not= header-off NIL_OFFSET)
          (conj node-offsets header-off)
          node-offsets))
      ;; Not an EveHashMap — collect entire tree for disposal
      (let [tree-offsets (volatile! (transient []))]
        (when (not= root-off NIL_OFFSET)
          (letfn [(collect-all [slab-off]
                    (when (not= slab-off NIL_OFFSET)
                      (let [node-type (slab/read-u8 sio slab-off 0)
                            children (when (== node-type NODE_TYPE_BITMAP)
                                       (let [node-bm (slab/read-i32 sio slab-off 8)
                                             cc (popcount32 node-bm)]
                                         (mapv #(slab/read-i32 sio slab-off (+ NODE_HEADER_SIZE (* % 4)))
                                               (range cc))))]
                        (vswap! tree-offsets conj! slab-off)
                        (doseq [child children]
                          (collect-all child)))))]
            (collect-all root-off)))
        (let [offs (persistent! @tree-offsets)]
          (if (not= header-off NIL_OFFSET)
            (conj offs header-off)
            offs))))))

;;=============================================================================
;; HAMT Validation (a debugging walk of a map's tree)
;;=============================================================================

(def ^:const ^:private HAMT_MAX_DEPTH 32)
(def ^:const ^:private HAMT_MAX_NODES 1000000)

(defn- validate-slab-offset
  "Check if a slab-qualified offset looks valid. Returns nil if valid."
  [slab-off]
  (when (not= slab-off NIL_OFFSET)
    (let [class-idx (alloc/decode-class-idx slab-off)
          block-idx (alloc/decode-block-idx slab-off)]
      (cond
        (or (< class-idx 0) (> class-idx 5))
        (str "invalid class-idx=" class-idx " (expected 0-5)")
        (< block-idx 0)
        (str "negative block-idx=" block-idx)
        (> block-idx 10000000)
        (str "suspiciously large block-idx=" block-idx)
        :else nil))))

(defn- validate-hamt-node
  "Validate a single HAMT node."
  [sio slab-off depth visited errors-acc]
  (cond
    (== slab-off NIL_OFFSET)
    {:valid? true :children [] :visited visited}

    (> depth HAMT_MAX_DEPTH)
    {:valid? false :children [] :visited visited
     :errors (conj errors-acc (str "depth " depth " exceeds max " HAMT_MAX_DEPTH
                                   " at offset " slab-off))}

    (contains? @visited slab-off)
    {:valid? false :children [] :visited visited
     :errors (conj errors-acc (str "cycle detected: offset " slab-off " already visited"))}

    :else
    (let [offset-err (validate-slab-offset slab-off)]
      (if offset-err
        {:valid? false :children [] :visited visited
         :errors (conj errors-acc (str "invalid offset " slab-off ": " offset-err))}
        (let [_ (vswap! visited conj slab-off)
              node-type (slab/read-u8 sio slab-off 0)]
          (case (int node-type)
            1 (let [node-bm (slab/read-i32 sio slab-off 8)
                    child-count (popcount32 node-bm)
                    children (mapv #(slab/read-i32 sio slab-off (+ NODE_HEADER_SIZE (* % 4)))
                                   (range child-count))]
                {:valid? true :children children :visited visited})
            3 {:valid? true :children [] :visited visited}
            {:valid? false :children [] :visited visited
             :errors (conj errors-acc (str "invalid node type " node-type
                                           " at offset " slab-off))}))))))

(defn validate-hamt-tree
  "Walk the HAMT tree from root-off and validate all nodes."
  [sio root-off]
  (let [visited (volatile! #{})
        errors (volatile! [])
        node-count (volatile! 0)
        max-depth (volatile! 0)]
    (loop [queue [[root-off 0]]]
      (if (empty? queue)
        {:valid? (empty? @errors)
         :errors @errors
         :node-count @node-count
         :max-depth @max-depth}
        (let [[off depth] (first queue)
              rest-queue (rest queue)]
          (vswap! max-depth max depth)
          (if (> @node-count HAMT_MAX_NODES)
            {:valid? false
             :errors [(str "exceeded max nodes " HAMT_MAX_NODES)]
             :node-count @node-count
             :max-depth @max-depth}
            (let [result (validate-hamt-node sio off depth visited [])]
              (when-not (:valid? result)
                (doseq [e (:errors result)]
                  (vswap! errors conj e)))
              (when (not= off NIL_OFFSET)
                (vswap! node-count inc))
              (let [children (:children result)
                    new-queue (reduce (fn [q child-off]
                                        (conj q [child-off (inc depth)]))
                                      (vec rest-queue)
                                      children)]
                (recur new-queue)))))))))

(defn validate-from-header-offset
  "Validate HAMT tree given an EveHashMap header slab-qualified offset.
   The sio is explicit (first parameter, as everywhere in this namespace):
   a diagnostic reads the slab its caller names — under the construction
   law there is no ambient domain for it to borrow."
  [sio header-off]
  (let [root-off (slab/read-i32 sio header-off SABMAPROOT_ROOT_OFF_OFFSET)
        result (validate-hamt-tree sio root-off)]
    (assoc result :root-off root-off)))

;;=============================================================================
;; CLJS-only: ISabRetirable
;;=============================================================================

(extend-type EveHashMap
  d/ISabRetirable
  (-sab-retire-diff! [this new-value _slab-env mode]
    (let [sio (field this sio__)
          old-hdr (field this offset__)
          old-root (slab/read-i32 sio old-hdr SABMAPROOT_ROOT_OFF_OFFSET)]
      (if (eve-hash-map? new-value)
        (let [new-root (slab/read-i32 sio (d/-root-header-off new-value) SABMAPROOT_ROOT_OFF_OFFSET)]
          (retire-tree-diff! sio old-root new-root))
        (when (not= old-root NIL_OFFSET)
          (free-hamt-node! sio old-root)))
      ;; Free the header block
      (when (not= old-hdr NIL_OFFSET)
        (slab/free! sio old-hdr)))))

;;=============================================================================
;; Registration
;;=============================================================================

;; Collection writer: a host collection written straight into the slab, for a
;; codec that writes one directly (eve.codec)
(defn- -write-map-coll!
  "Serialize a Clojure map to slab as an Eve HAMT map. Returns header offset.
   sio is explicit (first parameter)."
  [sio _serialize-elem m]
  (let [entries (map (fn [[k v]]
                       (let [kb (codec/encode-key k)
                             kh (portable-hash-bytes kb)
                             vb (codec/encode-val sio v)]
                         [kh kb vb]))
                     m)]
    (if (empty? entries)
      (write-map-header! sio 0 NIL_OFFSET)
      (let [root-off (reduce (fn [root [kh kb vb]]
                               (let [[new-root _] (hamt-assoc sio root kh kb vb 0)]
                                 ;; the nodes this assoc's path copy replaced were made by this writer and never
                                 ;; published: freed now, as a lost attempt frees its own — else a fresh 200-entry
                                 ;; map left 337 of its 391 blocks allocated and unreachable (2026-10-20 ET)
                                 (doseq [off (collect-tree-diff-offsets sio root new-root)]
                                   (slab/free! sio off))
                                 new-root))
                             NIL_OFFSET entries)]
        (write-map-header! sio (count m) root-off)))))

;; Type constructor + disposer + builder + collection writer registrations
(eve/register-eve-type!
    {:fast-tag    ser/FAST_TAG_SAB_MAP
     :type-id     EveHashMap-type-id
     :from-header hash-map-from-header
     :dispose     dispose!
     :builder-pred map?
     :builder-ctor into-hash-map
     :coll-writer {:tag :map :fn -write-map-coll!}
     :type        EveHashMap
     :print       :map})

;; Backward-compat aliases
(do
     (defn jvm-write-map!
       "Serialize a Clojure map to slab. Returns header offset.
        Backward-compat alias for the registered :map writer.
        sio is explicit."
       ([sio m]
        (codec/write-collection! :map sio m))
       ([sio _serialize-val m]
        (codec/write-collection! :map sio m)))

     (defn jvm-eve-hash-map-from-offset
       "Reconstruct an EveHashMap from a header offset.
        Backward-compat alias. coll-factory arg is ignored (registry-based).
        sio is explicit."
       ([sio header-off]
        (hash-map-from-header sio header-off))
       ([sio header-off _coll-factory]
        (hash-map-from-header sio header-off))))

