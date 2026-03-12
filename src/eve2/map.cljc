(ns eve2.map
  "Eve2 persistent HAMT map — unified CLJ/CLJS implementation.

   Single set of HAMT algorithms using ISlabIO protocol for memory access.
   On CLJS: CljsSlabIO delegates to module-level DataView functions.
   On CLJ:  JvmSlabCtx reads/writes via IMemRegion-backed mmap files.

   Node data lives in slab-class-specific SharedArrayBuffers (CLJS) or
   mmap files (JVM). Node offsets are slab-qualified: [class:3 | block:29].
   Both platforms use portable-hash-bytes (Murmur3 over serialized key bytes)
   for identical trie navigation."
  (:refer-clojure :exclude [hash-map])
  (:require
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-read-u16 -sio-read-i32
            -sio-read-bytes -sio-write-u8! -sio-write-u16!
            -sio-write-i32! -sio-write-bytes! -sio-alloc!
            -sio-free! -sio-copy-block!]]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.hamt-util :as hu :refer [portable-hash-bytes popcount32
                                  mask-hash bitpos has-bit? get-index]]
   #?@(:cljs [[eve2.alloc :as eve-alloc]]
       :clj  [[eve2.deftype :as eve2]
              [eve.mem :as mem :refer [eve-bytes->value value->eve-bytes
                                        value+sio->eve-bytes
                                        register-jvm-collection-writer!]]
              [eve.perf :as perf]]))
  #?(:cljs (:require-macros [eve2.deftype :as eve2])))

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
(def EveHashMap-type-id 0xED) ;; also emitted by eve2-deftype
(def ^:const SABMAPROOT_CNT_OFFSET 4)
(def ^:const SABMAPROOT_ROOT_OFF_OFFSET 8)

(def ^:const NIL_OFFSET alloc/NIL_OFFSET)

;;=============================================================================
;; Platform-specific helpers
;;=============================================================================

(defn- serialize-key-bytes
  "Serialize a key to bytes (platform-specific)."
  [k]
  #?(:cljs (ser/serialize-key k)
     :clj  (value->eve-bytes k)))

(defn- serialize-val-bytes
  "Serialize a value to bytes (platform-specific)."
  [v]
  #?(:cljs (ser/serialize-val v)
     :clj  (value+sio->eve-bytes v)))

(defn- deserialize-value-bytes
  "Deserialize value bytes (platform-specific)."
  [val-bytes]
  #?(:cljs (ser/deserialize-element {} val-bytes)
     :clj  (eve-bytes->value val-bytes)))

(defn- bytes-equal?
  "Compare two byte arrays for equality."
  [a b]
  #?(:cljs (and (== (.-length a) (.-length b))
                (loop [i 0]
                  (if (>= i (.-length a)) true
                    (if (not= (aget a i) (aget b i)) false (recur (inc i))))))
     :clj  (java.util.Arrays/equals ^bytes a ^bytes b)))

(defn- bytes-length
  "Get byte array length."
  ^long [ba]
  #?(:cljs (.-length ba)
     :clj  (alength ^bytes ba)))

(defn- get-sio
  "Get the ISlabIO context for the current platform."
  []
  #?(:cljs eve-alloc/cljs-sio
     :clj  alloc/*jvm-slab-ctx*))

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
  ^long [sio ^long node-off ^long pos]
  (let [key-len (-sio-read-i32 sio node-off pos)
        val-off (+ pos 4 key-len)
        val-len (-sio-read-i32 sio node-off val-off)]
    (+ val-off 4 val-len)))

(defn- calc-kv-size
  "Calculate total bytes for a KV pair."
  ^long [kb vb]
  (+ 4 (bytes-length kb) 4 (bytes-length vb)))

(defn- key-bytes-match?
  "Check if key bytes at pos match kb."
  [sio ^long node-off ^long pos kb]
  (let [stored-len (-sio-read-i32 sio node-off pos)]
    (when (== stored-len (bytes-length kb))
      (let [stored-bytes (-sio-read-bytes sio node-off (+ pos 4) stored-len)]
        (bytes-equal? stored-bytes kb)))))

(defn- write-kv!
  "Write a KV pair at pos within node. Returns offset after written data."
  [sio node-off pos kb vb]
  (let [klen (bytes-length kb)
        vlen (bytes-length vb)]
    (-sio-write-i32! sio node-off pos klen)
    (when (pos? klen)
      (-sio-write-bytes! sio node-off (+ pos 4) kb))
    (let [val-off (+ pos 4 klen)]
      (-sio-write-i32! sio node-off val-off vlen)
      (when (pos? vlen)
        (-sio-write-bytes! sio node-off (+ val-off 4) vb))
      (+ val-off 4 vlen))))

(defn- read-kv-bytes-at
  "Read key and value byte arrays at pos. Returns [kb vb next-pos]."
  [sio ^long node-off ^long pos]
  (let [klen (-sio-read-i32 sio node-off pos)
        kb   (-sio-read-bytes sio node-off (+ pos 4) klen)
        voff (+ pos 4 klen)
        vlen (-sio-read-i32 sio node-off voff)
        vb   (-sio-read-bytes sio node-off (+ voff 4) vlen)]
    [kb vb (+ voff 4 vlen)]))

;;=============================================================================
;; Node kv-total-size helpers
;;=============================================================================

(defn- node-kv-total-size
  "Read the cached kv-total-size from header. If 0, compute by scanning."
  ^long [sio ^long node-off ^long data-bm ^long node-bm]
  (let [cached (-sio-read-u16 sio node-off 2)]
    (if (pos? cached)
      cached
      (let [dc     (popcount32 data-bm)
            kv-off (kv-data-start-off data-bm node-bm)]
        (loop [i 0 pos kv-off]
          (if (>= i dc)
            (- pos kv-off)
            (recur (inc i) (skip-kv-at sio node-off pos))))))))

(defn- node-byte-size
  "Compute total byte size of a bitmap node."
  ^long [sio ^long node-off ^long data-bm ^long node-bm]
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
  (let [kv-size  (calc-kv-size kb vb)
        node-off (-sio-alloc! sio (+ NODE_HEADER_SIZE 4 kv-size))]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio node-off 1 0)
    (-sio-write-u16! sio node-off 2 kv-size)
    (-sio-write-i32! sio node-off 4 data-bm)
    (-sio-write-i32! sio node-off 8 0)
    (-sio-write-i32! sio node-off NODE_HEADER_SIZE kh)
    (write-kv! sio node-off (+ NODE_HEADER_SIZE 4) kb vb)
    node-off))

(defn- make-two-entry-node!
  "Create bitmap node with exactly 2 data entries and 0 children."
  [sio data-bm kh1 kb1 vb1 kh2 kb2 vb2]
  (let [kv-size  (+ (calc-kv-size kb1 vb1) (calc-kv-size kb2 vb2))
        node-off (-sio-alloc! sio (+ NODE_HEADER_SIZE 8 kv-size))]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio node-off 1 0)
    (-sio-write-u16! sio node-off 2 kv-size)
    (-sio-write-i32! sio node-off 4 data-bm)
    (-sio-write-i32! sio node-off 8 0)
    (-sio-write-i32! sio node-off NODE_HEADER_SIZE kh1)
    (-sio-write-i32! sio node-off (+ NODE_HEADER_SIZE 4) kh2)
    (let [next-pos (write-kv! sio node-off (+ NODE_HEADER_SIZE 8) kb1 vb1)]
      (write-kv! sio node-off next-pos kb2 vb2))
    node-off))

(defn- make-single-child-node!
  "Create bitmap node with 0 data entries and 1 child."
  [sio ^long node-bm ^long child-off]
  (let [node-off (-sio-alloc! sio (+ NODE_HEADER_SIZE 4))]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio node-off 1 0)
    (-sio-write-u16! sio node-off 2 0)
    (-sio-write-i32! sio node-off 4 0)
    (-sio-write-i32! sio node-off 8 node-bm)
    (-sio-write-i32! sio node-off NODE_HEADER_SIZE child-off)
    node-off))

(defn- make-child-and-entry-node!
  "Create bitmap node with 1 data entry and 1 child."
  [sio data-bm node-bm child-off kh kb vb]
  (let [kv-size  (calc-kv-size kb vb)
        node-off (-sio-alloc! sio (+ NODE_HEADER_SIZE 4 4 kv-size))]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio node-off 1 0)
    (-sio-write-u16! sio node-off 2 kv-size)
    (-sio-write-i32! sio node-off 4 data-bm)
    (-sio-write-i32! sio node-off 8 node-bm)
    (-sio-write-i32! sio node-off NODE_HEADER_SIZE child-off)
    (-sio-write-i32! sio node-off (+ NODE_HEADER_SIZE 4) kh)
    (write-kv! sio node-off (+ NODE_HEADER_SIZE 8) kb vb)
    node-off))

(defn- make-collision-node!
  "Create collision node from entries: [[kh kb vb] ...]."
  [sio ^long kh entries]
  (let [cnt (count entries)
        kv-size (reduce (fn [acc [_ kb vb]] (+ acc (calc-kv-size kb vb))) 0 entries)
        node-off (-sio-alloc! sio (+ COLLISION_HEADER_SIZE kv-size))]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_COLLISION)
    (-sio-write-u8!  sio node-off 1 cnt)
    (-sio-write-u16! sio node-off 2 0)
    (-sio-write-i32! sio node-off 4 kh)
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
        dst-off (-sio-alloc! sio nsize)]
    (-sio-copy-block! sio dst-off 0 src-off 0 nsize)
    (-sio-write-i32! sio dst-off (+ NODE_HEADER_SIZE (* child-idx 4)) new-child-off)
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
        size-diff   (- new-kv-size old-kv-size)
        old-nsize   (node-byte-size sio src-off data-bm node-bm)
        new-nsize   (+ old-nsize size-diff)
        dc          (popcount32 data-bm)
        cc          (popcount32 node-bm)]
    (if (zero? size-diff)
      ;; Same size — bulk copy, overwrite KV + hash
      (let [dst-off (-sio-alloc! sio new-nsize)]
        (-sio-copy-block! sio dst-off 0 src-off 0 old-nsize)
        (-sio-write-i32!   sio dst-off kv-pos (bytes-length kb))
        (-sio-write-bytes! sio dst-off (+ kv-pos 4) kb)
        (-sio-write-i32!   sio dst-off (+ kv-pos 4 (bytes-length kb)) (bytes-length vb))
        (-sio-write-bytes! sio dst-off (+ kv-pos 4 (bytes-length kb) 4) vb)
        (-sio-write-i32!   sio dst-off (+ (hashes-start-off node-bm) (* data-idx 4)) kh)
        dst-off)
      ;; Different size — rebuild with splice
      (let [dst-off    (-sio-alloc! sio new-nsize)
            kv-total   (+ (node-kv-total-size sio src-off data-bm node-bm) size-diff)
            children-end (+ NODE_HEADER_SIZE (* 4 cc))]
        (-sio-copy-block! sio dst-off 0 src-off 0 children-end)
        (-sio-write-u16! sio dst-off 2 kv-total)
        ;; Copy hashes, patch one
        (let [h-off (hashes-start-off node-bm)]
          (dotimes [i dc]
            (-sio-write-i32! sio dst-off (+ h-off (* i 4))
              (if (== i data-idx)
                kh
                (-sio-read-i32 sio src-off (+ h-off (* i 4)))))))
        ;; Copy KV entries, replacing at data-idx
        (let [src-kv-off (kv-data-start-off data-bm node-bm)]
          (loop [i 0 sp src-kv-off dp src-kv-off]
            (when (< i dc)
              (if (== i data-idx)
                (let [next-dp (write-kv! sio dst-off dp kb vb)
                      next-sp (skip-kv-at sio src-off sp)]
                  (recur (inc i) next-sp next-dp))
                (let [entry-size (- (skip-kv-at sio src-off sp) sp)]
                  (-sio-copy-block! sio dst-off dp src-off sp entry-size)
                  (recur (inc i) (+ sp entry-size) (+ dp entry-size)))))))
        dst-off))))

(defn- copy-node-add-kv!
  "Copy a bitmap node, inserting a new KV at data-idx."
  [sio src-off src-data-bm new-data-bm node-bm
   data-idx kh kb vb]
  (let [new-kv-size  (calc-kv-size kb vb)
        old-kv-total (node-kv-total-size sio src-off src-data-bm node-bm)
        new-kv-total (+ old-kv-total new-kv-size)
        cc      (popcount32 node-bm)
        new-dc  (popcount32 new-data-bm)
        old-dc  (popcount32 src-data-bm)
        nsize   (+ NODE_HEADER_SIZE (* 4 cc) (* 4 new-dc) new-kv-total)
        dst-off (-sio-alloc! sio nsize)]
    (-sio-write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio dst-off 1 0)
    (-sio-write-u16! sio dst-off 2 new-kv-total)
    (-sio-write-i32! sio dst-off 4 new-data-bm)
    (-sio-write-i32! sio dst-off 8 node-bm)
    ;; Copy children
    (when (pos? cc)
      (-sio-copy-block! sio dst-off NODE_HEADER_SIZE src-off NODE_HEADER_SIZE (* 4 cc)))
    ;; Build hash array with insertion
    (let [h-off (hashes-start-off node-bm)]
      (loop [si 0 di 0]
        (when (< di new-dc)
          (if (== di data-idx)
            (do (-sio-write-i32! sio dst-off (+ h-off (* di 4)) kh)
                (recur si (inc di)))
            (do (-sio-write-i32! sio dst-off (+ h-off (* di 4))
                  (-sio-read-i32 sio src-off (+ h-off (* si 4))))
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
              (-sio-copy-block! sio dst-off dp src-off sp entry-size)
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
        new-cc  (popcount32 new-node-bm)
        new-dc  (popcount32 new-data-bm)
        old-cc  (popcount32 src-node-bm)
        old-dc  (popcount32 src-data-bm)
        nsize   (+ NODE_HEADER_SIZE (* 4 new-cc) (* 4 new-dc) new-kv-total)
        dst-off (-sio-alloc! sio nsize)]
    (-sio-write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio dst-off 1 0)
    (-sio-write-u16! sio dst-off 2 new-kv-total)
    (-sio-write-i32! sio dst-off 4 new-data-bm)
    (-sio-write-i32! sio dst-off 8 new-node-bm)
    ;; Build children array with insertion
    (let [src-c-off NODE_HEADER_SIZE]
      (loop [si 0 di 0]
        (when (< di new-cc)
          (if (== di new-child-idx)
            (do (-sio-write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di 4)) new-child-off)
                (recur si (inc di)))
            (do (-sio-write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di 4))
                  (-sio-read-i32 sio src-off (+ src-c-off (* si 4))))
                (recur (inc si) (inc di)))))))
    ;; Build hash array, skipping removed entry
    (let [src-h-off (hashes-start-off src-node-bm)
          dst-h-off (hashes-start-off new-node-bm)]
      (loop [si 0 di 0]
        (when (< di new-dc)
          (if (== si remove-idx)
            (recur (inc si) di)
            (do (-sio-write-i32! sio dst-off (+ dst-h-off (* di 4))
                  (-sio-read-i32 sio src-off (+ src-h-off (* si 4))))
                (recur (inc si) (inc di)))))))
    ;; Build KV data, skipping removed entry
    (let [src-kv-off (kv-data-start-off src-data-bm src-node-bm)
          dst-kv-off (kv-data-start-off new-data-bm new-node-bm)]
      (loop [si 0 sp src-kv-off dp dst-kv-off]
        (when (< si old-dc)
          (if (== si remove-idx)
            (recur (inc si) (skip-kv-at sio src-off sp) dp)
            (let [entry-size (- (skip-kv-at sio src-off sp) sp)]
              (-sio-copy-block! sio dst-off dp src-off sp entry-size)
              (recur (inc si) (+ sp entry-size) (+ dp entry-size)))))))
    dst-off))

;;=============================================================================
;; HAMT Find (unified)
;;=============================================================================

(defn- hamt-get
  "Find key k in HAMT rooted at root-off. Returns value or not-found."
  [sio ^long root-off k not-found]
  (let [kb (serialize-key-bytes k)
        kh (portable-hash-bytes kb)]
    (loop [off root-off shift 0]
      (if (== off NIL_OFFSET)
        not-found
        (let [nt (-sio-read-u8 sio off 0)]
          (case (int nt)
            ;; Bitmap node
            1 (let [dbm (-sio-read-i32 sio off 4)
                    nbm (-sio-read-i32 sio off 8)
                    bit (bitpos kh shift)]
                (cond
                  (has-bit? nbm bit)
                  (recur (-sio-read-i32 sio off
                           (+ NODE_HEADER_SIZE (* (get-index nbm bit) 4)))
                         (+ shift SHIFT_STEP))

                  (has-bit? dbm bit)
                  (let [di (get-index dbm bit)
                        hs (hashes-start-off nbm)
                        sh (-sio-read-i32 sio off (+ hs (* di 4)))]
                    (if (not= sh kh)
                      not-found
                      (let [kvs (kv-data-start-off dbm nbm)
                            pos (loop [i 0 p kvs]
                                  (if (== i di) p
                                    (recur (inc i) (skip-kv-at sio off p))))
                            kl (-sio-read-i32 sio off pos)
                            eks (-sio-read-bytes sio off (+ pos 4) kl)]
                        (if (bytes-equal? eks kb)
                          (let [vo (+ pos 4 kl)
                                vl (-sio-read-i32 sio off vo)
                                vb (-sio-read-bytes sio off (+ vo 4) vl)]
                            (deserialize-value-bytes vb))
                          not-found))))

                  :else not-found))

            ;; Collision node
            3 (let [ch (-sio-read-i32 sio off 4)]
                (if (not= ch kh)
                  not-found
                  (let [cc (-sio-read-u8 sio off 1)]
                    (loop [i 0 pos COLLISION_HEADER_SIZE]
                      (if (>= i cc)
                        not-found
                        (let [kl  (-sio-read-i32 sio off pos)
                              eks (-sio-read-bytes sio off (+ pos 4) kl)]
                          (if (bytes-equal? eks kb)
                            (let [vo (+ pos 4 kl)
                                  vl (-sio-read-i32 sio off vo)
                                  vb (-sio-read-bytes sio off (+ vo 4) vl)]
                              (deserialize-value-bytes vb))
                            (let [vo (+ pos 4 kl)]
                              (recur (inc i) (+ vo 4 (-sio-read-i32 sio off vo)))))))))))

            ;; Unknown
            not-found))))))

;;=============================================================================
;; HAMT Assoc (unified)
;;=============================================================================

(defn- hamt-assoc
  "Assoc key/value into HAMT. Returns [new-root added?]."
  [sio root-off kh kb vb shift]
  (if (== root-off NIL_OFFSET)
    [(make-single-entry-node! sio (bitpos kh shift) kh kb vb) true]

    (let [nt (-sio-read-u8 sio root-off 0)]
      (case (int nt)
        ;; Bitmap node
        1 (let [data-bm (-sio-read-i32 sio root-off 4)
                node-bm (-sio-read-i32 sio root-off 8)
                bit     (bitpos kh shift)]
            (cond
              ;; Child node — descend
              (has-bit? node-bm bit)
              (let [child-idx (get-index node-bm bit)
                    child-off (-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* child-idx 4)))
                    [new-child added?] (hamt-assoc sio child-off kh kb vb (+ shift SHIFT_STEP))]
                (if (== new-child child-off)
                  [root-off false]
                  [(copy-node-patch-child! sio root-off data-bm node-bm child-idx new-child)
                   added?]))

              ;; Inline data at this position
              (has-bit? data-bm bit)
              (let [data-idx (get-index data-bm bit)
                    existing-kh (-sio-read-i32 sio root-off
                                  (+ (hashes-start-off node-bm) (* data-idx 4)))
                    kvs (kv-data-start-off data-bm node-bm)
                    pos (loop [i 0 p kvs]
                          (if (== i data-idx) p
                            (recur (inc i) (skip-kv-at sio root-off p))))]
                (if (key-bytes-match? sio root-off pos kb)
                  ;; Same key — replace value
                  (let [kl (-sio-read-i32 sio root-off pos)
                        val-off (+ pos 4 kl)
                        vl (-sio-read-i32 sio root-off val-off)
                        old-vb (-sio-read-bytes sio root-off (+ val-off 4) vl)]
                    (if (bytes-equal? old-vb vb)
                      [root-off false]
                      [(copy-node-replace-kv! sio root-off data-bm node-bm data-idx kh kb vb)
                       false]))
                  ;; Different key — push down
                  (let [kl (-sio-read-i32 sio root-off pos)
                        existing-kb (-sio-read-bytes sio root-off (+ pos 4) kl)
                        voff (+ pos 4 kl)
                        vl (-sio-read-i32 sio root-off voff)
                        existing-vb (-sio-read-bytes sio root-off (+ voff 4) vl)]
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
              (let [data-idx    (get-index data-bm bit)
                    new-data-bm (bit-or data-bm bit)]
                [(copy-node-add-kv! sio root-off data-bm new-data-bm node-bm
                                    data-idx kh kb vb)
                 true])))

        ;; Collision node
        3 (let [node-hash (-sio-read-i32 sio root-off 4)
                cnt       (-sio-read-u8 sio root-off 1)]
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
              ;; Different hash — split
              (if (>= shift 30)
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
    (let [nt (-sio-read-u8 sio root-off 0)]
      (case (int nt)
        ;; Bitmap node
        1 (let [data-bm (-sio-read-i32 sio root-off 4)
                node-bm (-sio-read-i32 sio root-off 8)
                bit     (bitpos kh shift)]
            (cond
              ;; Child — descend
              (has-bit? node-bm bit)
              (let [child-idx (get-index node-bm bit)
                    child-off (-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* child-idx 4)))
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
                            children (mapv #(-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* % 4))) (range cc))
                            h-off (hashes-start-off node-bm)
                            hashes (vec (keep-indexed
                                          (fn [i _] (when (not= i data-idx)
                                                      (-sio-read-i32 sio root-off (+ h-off (* i 4)))))
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
                              new-off (-sio-alloc! sio nsize)]
                          (-sio-write-u8!  sio new-off 0 NODE_TYPE_BITMAP)
                          (-sio-write-u8!  sio new-off 1 0)
                          (-sio-write-u16! sio new-off 2 kvs-size)
                          (-sio-write-i32! sio new-off 4 new-data-bm)
                          (-sio-write-i32! sio new-off 8 node-bm)
                          (dotimes [i cc]
                            (-sio-write-i32! sio new-off (+ NODE_HEADER_SIZE (* i 4)) (nth children i)))
                          (let [new-h-off (hashes-start-off node-bm)]
                            (dotimes [i dc]
                              (-sio-write-i32! sio new-off (+ new-h-off (* i 4)) (nth hashes i))))
                          (reduce (fn [p [ek ev]] (write-kv! sio new-off p ek ev))
                                  (kv-data-start-off new-data-bm node-bm) kvs-list)
                          [new-off true]))))
                  ;; Key doesn't match
                  [root-off false]))

              :else [root-off false]))

        ;; Collision node
        3 (let [node-hash (-sio-read-i32 sio root-off 4)
                cnt       (-sio-read-u8 sio root-off 1)]
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

(defn- hamt-kv-reduce
  "Walk HAMT tree, calling (f acc k v) at each entry. Supports reduced?."
  [sio ^long root-off f init]
  (if (== root-off NIL_OFFSET)
    init
    (let [nt (-sio-read-u8 sio root-off 0)]
      (case (int nt)
        ;; Bitmap node
        1 (let [data-bm  (-sio-read-i32 sio root-off 4)
                node-bm  (-sio-read-i32 sio root-off 8)
                dc       (popcount32 data-bm)
                cc       (popcount32 node-bm)
                kv-off   (kv-data-start-off data-bm node-bm)
                ;; Process inline entries
                acc (loop [i 0 pos kv-off acc init]
                      (if (or (>= i dc) (reduced? acc))
                        acc
                        (let [kl  (-sio-read-i32 sio root-off pos)
                              kb  (-sio-read-bytes sio root-off (+ pos 4) kl)
                              vo  (+ pos 4 kl)
                              vl  (-sio-read-i32 sio root-off vo)
                              vb  (-sio-read-bytes sio root-off (+ vo 4) vl)
                              k   (deserialize-value-bytes kb)
                              v   (deserialize-value-bytes vb)]
                          (recur (inc i) (+ vo 4 vl) (f acc k v)))))]
            ;; Process child nodes
            (if (reduced? acc)
              acc
              (loop [i 0 acc acc]
                (if (or (>= i cc) (reduced? acc))
                  acc
                  (let [child-off (-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* i 4)))
                        new-acc (hamt-kv-reduce sio child-off f acc)]
                    (recur (inc i) new-acc))))))

        ;; Collision node
        3 (let [cnt (-sio-read-u8 sio root-off 1)]
            (loop [i 0 pos COLLISION_HEADER_SIZE acc init]
              (if (or (>= i cnt) (reduced? acc))
                acc
                (let [kl (-sio-read-i32 sio root-off pos)
                      kb (-sio-read-bytes sio root-off (+ pos 4) kl)
                      vo (+ pos 4 kl)
                      vl (-sio-read-i32 sio root-off vo)
                      vb (-sio-read-bytes sio root-off (+ vo 4) vl)
                      k  (deserialize-value-bytes kb)
                      v  (deserialize-value-bytes vb)]
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
          #?(:cljs (MapEntry. k v nil)
             :clj  (clojure.lang.MapEntry/create k v))))
      nil)
    (seq (persistent! @entries))))

;;=============================================================================
;; Map header read/write
;;=============================================================================

(defn- write-map-header!
  "Allocate and write a 12-byte EveHashMap header. Returns slab offset."
  ^long [sio ^long cnt ^long root-off]
  (let [off (-sio-alloc! sio 12)]
    (-sio-write-u8!  sio off 0 EveHashMap-type-id)
    (-sio-write-u8!  sio off 1 1) ;; flags
    (-sio-write-u16! sio off 2 0)
    (-sio-write-i32! sio off SABMAPROOT_CNT_OFFSET cnt)
    (-sio-write-i32! sio off SABMAPROOT_ROOT_OFF_OFFSET root-off)
    off))

(defn- read-map-header
  "Read cnt and root-off from an EveHashMap header. Returns [cnt root-off]."
  [sio ^long header-off]
  [(-sio-read-i32 sio header-off SABMAPROOT_CNT_OFFSET)
   (-sio-read-i32 sio header-off SABMAPROOT_ROOT_OFF_OFFSET)])

;;=============================================================================
;; EveHashMap deftype — unified via eve2-deftype macro
;;
;; Fields: cnt (int32 @ offset 4), root-off (int32 @ offset 8)
;; CLJS: deftype EveHashMap [offset__] — fields read from slab
;; CLJ:  deftype EveHashMap [cnt root-off offset__ sio _meta]
;;=============================================================================

(declare make-eve2-hash-map)

(eve2/eve2-deftype ^{:type-id 0xED} EveHashMap [^:int32 cnt ^:int32 root-off]
  ;; --- Shared protocols (CLJS names, macro translates for JVM) ---
  ICounted
  (-count [_] #?(:cljs cnt :clj (int cnt)))

  ISeqable
  (-seq [_]
    (let [sio (get-sio)]
      (when (pos? cnt)
        #?(:clj (binding [alloc/*jvm-slab-ctx* sio]
                  (hamt-seq sio root-off))
           :cljs (hamt-seq sio root-off)))))

  IMeta
  (-meta [_] #?(:cljs nil :clj _meta))

  IWithMeta
  (-with-meta [this m]
    #?(:cljs this
       :clj (EveHashMap. cnt root-off offset__ sio m)))

  ILookup
  (-lookup [this k] (-lookup this k nil))
  (-lookup [_ k not-found]
    (let [sio (get-sio)]
      #?(:clj (binding [alloc/*jvm-slab-ctx* sio]
                (hamt-get sio root-off k not-found))
         :cljs (hamt-get sio root-off k not-found))))

  IAssociative
  (-contains-key? [_ k]
    (let [sio (get-sio)]
      #?(:clj (binding [alloc/*jvm-slab-ctx* sio]
                (not (identical? (hamt-get sio root-off k ::absent) ::absent)))
         :cljs (not (identical? (hamt-get sio root-off k ::absent) ::absent)))))
  (-assoc [this k v]
    (let [sio (get-sio)
          kb  (serialize-key-bytes k)
          vb  (serialize-val-bytes v)
          kh  (portable-hash-bytes kb)]
      #?(:clj (binding [alloc/*jvm-slab-ctx* sio]
                (let [[new-root added?] (hamt-assoc sio root-off kh kb vb 0)]
                  (if (== new-root root-off)
                    this
                    (make-eve2-hash-map sio (if added? (inc cnt) cnt) new-root))))
         :cljs (let [[new-root added?] (hamt-assoc sio root-off kh kb vb 0)]
                 (if (== new-root root-off)
                   this
                   (make-eve2-hash-map sio (if added? (inc cnt) cnt) new-root))))))

  IMap
  (-dissoc [this k]
    (let [sio (get-sio)
          kb  (serialize-key-bytes k)
          kh  (portable-hash-bytes kb)]
      #?(:clj (binding [alloc/*jvm-slab-ctx* sio]
                (let [[new-root removed?] (hamt-dissoc sio root-off kh kb 0)]
                  (if-not removed?
                    this
                    (if (== new-root NIL_OFFSET)
                      (make-eve2-hash-map sio 0 NIL_OFFSET)
                      (make-eve2-hash-map sio (dec cnt) new-root)))))
         :cljs (let [[new-root removed?] (hamt-dissoc sio root-off kh kb 0)]
                 (if-not removed?
                   this
                   (if (== new-root NIL_OFFSET)
                     (make-eve2-hash-map sio 0 NIL_OFFSET)
                     (make-eve2-hash-map sio (dec cnt) new-root)))))))

  ICollection
  (-conj [this entry]
    #?(:cljs (if (vector? entry)
               (-assoc this (nth entry 0) (nth entry 1))
               (if (satisfies? IMapEntry entry)
                 (-assoc this (key entry) (val entry))
                 (reduce -conj this entry)))
       :clj (if (map? entry)
               (reduce-kv (fn [m k v] (.assoc ^EveHashMap m k v)) this entry)
               (let [[k v] (if (vector? entry) entry [(key entry) (val entry)])]
                 (.assoc this k v)))))

  IEmptyableCollection
  (-empty [_]
    (let [sio (get-sio)]
      #?(:clj (binding [alloc/*jvm-slab-ctx* sio]
                (make-eve2-hash-map sio 0 NIL_OFFSET))
         :cljs (make-eve2-hash-map sio 0 NIL_OFFSET))))

  IReduce
  (-reduce [this f]
    #?(:cljs (if (zero? cnt)
               (f)
               (let [result (hamt-kv-reduce (get-sio) root-off
                              (fn [acc k v]
                                (if (nil? acc)
                                  (MapEntry. k v nil)
                                  (f acc (MapEntry. k v nil))))
                              nil)]
                 (if (reduced? result) @result result)))
       :clj (let [s (.seq this)]
               (if s (reduce f s) (f)))))
  (-reduce [_ f init]
    (let [sio (get-sio)]
      #?(:clj (binding [alloc/*jvm-slab-ctx* sio]
                (hamt-kv-reduce sio root-off
                  (fn [acc k v] (f acc (clojure.lang.MapEntry/create k v)))
                  init))
         :cljs (let [result (hamt-kv-reduce sio root-off
                              (fn [acc k v] (f acc (MapEntry. k v nil)))
                              init)]
                 (if (reduced? result) @result result)))))

  IKVReduce
  (-kv-reduce [_ f init]
    (let [sio (get-sio)]
      #?(:clj (binding [alloc/*jvm-slab-ctx* sio]
                (hamt-kv-reduce sio root-off f init))
         :cljs (let [result (hamt-kv-reduce sio root-off f init)]
                 (if (reduced? result) @result result)))))

  IEquiv
  (-equiv [this other]
    #?(:cljs (and (map? other)
                  (== cnt (count other))
                  (every? (fn [[k v]]
                            (let [found (hamt-get (get-sio) root-off k ::absent)]
                              (and (not (identical? found ::absent)) (= v found))))
                          other))
       :clj (clojure.lang.APersistentMap/mapEquals this other)))

  IHash
  (-hash [this]
    #?(:cljs (hash-unordered-coll this)
       :clj (clojure.lang.Murmur3/hashUnordered this)))

  IFn
  (-invoke [this k] (-lookup this k nil))
  (-invoke [this k nf] (-lookup this k nf))

  IPrintWithWriter
  (-pr-writer [this writer _opts]
    #?(:cljs (do
               (-write writer "{")
               (let [s (seq this)]
                 (when s
                   (loop [[[k v] & more] s first? true]
                     (when-not first? (-write writer ", "))
                     (-write writer (pr-str k))
                     (-write writer " ")
                     (-write writer (pr-str v))
                     (when more (recur more false)))))
               (-write writer "}"))
       :clj nil))  ;; CLJ uses Object/toString below

  d/IDirectSerialize
  (-direct-serialize [this]
    #?(:cljs (ser/encode-sab-pointer ser/FAST_TAG_SAB_MAP (.-offset__ this))
       :clj offset__))

  d/ISabStorable
  (-sab-tag [_] :eve-hash-map)
  (-sab-encode [this _]
    (d/-direct-serialize this))
  (-sab-dispose [_ _] nil)

  d/IsEve
  (-eve? [_] true)

  d/IEveRoot
  (-root-header-off [this]
    #?(:cljs (.-offset__ this)
       :clj offset__))

  ;; --- CLJ-only interfaces (no CLJS equivalent) ---
  #?@(:clj
      [clojure.lang.MapEquivalence

       clojure.lang.IPersistentMap
       (entryAt [_ k]
         (binding [alloc/*jvm-slab-ctx* sio]
           (let [v (hamt-get sio root-off k ::absent)]
             (when-not (identical? v ::absent)
               (clojure.lang.MapEntry/create k v)))))
       (assocEx [this k v]
         (if (.containsKey this k)
           (throw (RuntimeException. (str "Key already present: " k)))
           (.assoc this k v)))

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

       java.lang.Object
       (toString [this] (pr-str this))
       (equals [this other]
         (clojure.lang.APersistentMap/mapEquals this other))
       (hashCode [this]
         (clojure.lang.APersistentMap/mapHash this))]))

;;=============================================================================
;; Constructors
;;=============================================================================

(defn- make-eve2-hash-map
  "Internal constructor: allocate header, create EveHashMap."
  [sio ^long cnt ^long root-off]
  (let [hdr (write-map-header! sio cnt root-off)]
    #?(:cljs (EveHashMap. hdr)
       :clj  (EveHashMap. cnt root-off hdr sio nil))))

(defn eve2-hash-map-from-header
  "Reconstruct an EveHashMap from an existing header offset."
  ([sio ^long header-off]
   (let [[cnt root-off] (read-map-header sio header-off)]
     #?(:cljs (EveHashMap. header-off)
        :clj  (EveHashMap. cnt root-off header-off sio nil)))))

(defn empty-hash-map
  "Create an empty Eve2 hash map."
  []
  (let [sio (get-sio)]
    (make-eve2-hash-map sio 0 NIL_OFFSET)))

(defn hash-map
  "Create an Eve2 hash map from key-value pairs."
  [& kvs]
  (reduce (fn [m [k v]] (assoc m k v))
          (empty-hash-map)
          (partition 2 kvs)))

(defn into-hash-map
  "Create an Eve2 hash map from a collection of [k v] entries."
  [coll]
  (reduce (fn [m [k v]] (assoc m k v))
          (empty-hash-map)
          coll))

;;=============================================================================
;; Registration
;;=============================================================================

#?(:clj
   (do
     ;; Register collection writer so mem/value+sio->eve-bytes routes maps here
     (register-jvm-collection-writer! :map
       (fn [_sio _serialize-elem m]
         ;; Build HAMT from Clojure map, return header offset
         (let [sio alloc/*jvm-slab-ctx*
               entries (map (fn [[k v]]
                              (let [kb (value->eve-bytes k)
                                    kh (portable-hash-bytes kb)
                                    vb (value+sio->eve-bytes v)]
                                [kh kb vb]))
                            m)]
           (if (empty? entries)
             (write-map-header! sio 0 NIL_OFFSET)
             ;; Build HAMT from entries
             (let [root-off (reduce (fn [root [kh kb vb]]
                                      (let [[new-root _] (hamt-assoc sio root kh kb vb 0)]
                                        new-root))
                                    NIL_OFFSET entries)]
               (write-map-header! sio (count m) root-off))))))

     ;; Register type constructor for SAB pointer tag 0x10
     (ser/register-jvm-type-constructor! 0x10
       (fn [header-off]
         (eve2-hash-map-from-header alloc/*jvm-slab-ctx* header-off)))

     (defmethod print-method EveHashMap [m ^java.io.Writer w]
       (#'clojure.core/print-map m print-method w))))
