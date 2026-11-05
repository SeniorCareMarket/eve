(ns eve.set
  "Eve persistent HAMT set — unified CLJ/CLJS implementation.

   Uses ISlabIO protocol for all memory access.
   Sets store values only (no key-hash arrays like maps).
   Collision nodes use type 2 (distinct from map collision type 3).

   Uses eve/deftype macro: CLJ protocol names, auto-translated on CLJS."
  (:refer-clojure :exclude [hash-set
                            aget alength unchecked-int unsigned-bit-shift-right volatile!
                            vswap! transient persistent! conj! long bytes])
  (:require
   [eve.host :refer [aget alength unchecked-int unsigned-bit-shift-right volatile! vswap!
                     transient persistent! conj! long bytes]]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc
    :refer [NIL_OFFSET]]
   [eve.deftype-proto.data :as d]
   [eve.encoding :as enc]
   [eve.core :as ecore]
   [eve.deftype-proto.serialize :as ser]
   [eve.hamt-util :as hu :refer [portable-hash-bytes popcount32
                                  bitpos node-bit has-bit? get-index]]
   [eve.platform :as p]
   [eve.deftype-proto.macros :as eve]
   [eve.codec :as codec]
   [eve.digest :as dg]))

;;=============================================================================
;; Shared Constants
;;=============================================================================

(def ^:const SHIFT_STEP 5)
(def ^:const MASK 0x1f)

(def ^:const NODE_TYPE_BITMAP 1)
(def ^:const NODE_TYPE_COLLISION 2) ;; Sets use 2, maps use 3

;; Node header: type:u8 + flags:u8 + pad:u16 + data_bitmap:u32 + node_bitmap:u32 = 12
(def ^:const NODE_HEADER_SIZE 12)
(def ^:const COLLISION_HEADER_SIZE 12)

;; EveHashSet header: type-id:u8 + flags:u8 + pad:u16 + count:i32 + root-off:i32 = 12
(def EveHashSet-type-id 0xEE)
(def ^:const SABSETROOT_CNT_OFFSET 4)
(def ^:const SABSETROOT_ROOT_OFF_OFFSET 8)
(def ^:const SET_FLAG_PORTABLE_HASH 0x01)

;;=============================================================================
;; Platform-specific helpers
;;=============================================================================

(defn- serialize-val-bytes
  "A set's elements are keys: the set hashes and compares their bytes, so a
   nested collection is encoded flat (FORMAT-DECISIONS.md §3), never as a
   pointer. sio is unused on either host; kept for the call sites."
  [_sio v]
  (codec/encode-key v))

(defn- deserialize-val-bytes
  [val-bytes sio]
  (codec/decode sio val-bytes))

(defn- default-sio
  "The slab I/O a constructor given none allocates in: the running swap!'s
   domain, else this process's own (eve.deftype-proto.alloc/current-sio)."
  []
  (alloc/current-sio))

(defn- bytes-equal? [a b]
  (enc/bytes= a b))

(defn- bytes-length [ba]
  (alength ^bytes ba))

;;=============================================================================
;; Node I/O helpers
;;=============================================================================

(defn- children-start ^long [] NODE_HEADER_SIZE)

(defn- values-start-off
  "Byte offset where inline values begin."
  [node-bm]
  (+ NODE_HEADER_SIZE (* 4 (popcount32 node-bm))))

(defn- read-value-at
  "Read a value [len:u32][bytes...] at pos within node. Returns [vb next-pos]."
  [sio node-off pos]
  (let [vlen (slab/read-i32 sio node-off pos)
        vb   (slab/read-bytes sio node-off (+ pos 4) vlen)]
    [vb (+ pos 4 vlen)]))

(defn- skip-value-at
  "Skip a value entry, returning offset after it."
  [sio node-off pos]
  (let [vlen (slab/read-i32 sio node-off pos)]
    (+ pos 4 vlen)))

(defn- write-value!
  "Write a value at pos within node. Returns offset after written data."
  [sio node-off pos vb]
  (let [vlen (bytes-length vb)]
    (slab/write-i32! sio node-off pos vlen)
    (when (pos? vlen)
      (slab/write-bytes! sio node-off (+ pos 4) vb))
    (+ pos 4 vlen)))

(defn- value-matches?
  "Check if value bytes at pos match vb."
  [sio node-off pos vb]
  (let [stored-len (slab/read-i32 sio node-off pos)]
    (when (== stored-len (bytes-length vb))
      (slab/bytes-equal-at? sio node-off (+ pos 4) vb))))

;;=============================================================================
;; Node construction
;;=============================================================================

(defn- make-single-value-node! [sio data-bm vb]
  (let [vlen (bytes-length vb)
        nsize (+ NODE_HEADER_SIZE (+ 4 vlen))
        node-off (slab/alloc! sio nsize)]
    (slab/write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8!  sio node-off 1 0)
    (slab/write-u16! sio node-off 2 0)
    (slab/write-i32! sio node-off 4 data-bm)
    (slab/write-i32! sio node-off 8 0)
    (write-value! sio node-off NODE_HEADER_SIZE vb)
    node-off))

(defn- make-two-value-node! [sio data-bm vb1 vb2]
  (let [vlen1 (bytes-length vb1)
        vlen2 (bytes-length vb2)
        nsize (+ NODE_HEADER_SIZE (+ 4 vlen1) (+ 4 vlen2))
        node-off (slab/alloc! sio nsize)]
    (slab/write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8!  sio node-off 1 0)
    (slab/write-u16! sio node-off 2 0)
    (slab/write-i32! sio node-off 4 data-bm)
    (slab/write-i32! sio node-off 8 0)
    (let [next-pos (write-value! sio node-off NODE_HEADER_SIZE vb1)]
      (write-value! sio node-off next-pos vb2))
    node-off))

(defn- make-single-child-node! [sio node-bm child-off]
  (let [node-off (slab/alloc! sio (+ NODE_HEADER_SIZE 4))]
    (slab/write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8!  sio node-off 1 0)
    (slab/write-u16! sio node-off 2 0)
    (slab/write-i32! sio node-off 4 0)
    (slab/write-i32! sio node-off 8 node-bm)
    (slab/write-i32! sio node-off NODE_HEADER_SIZE child-off)
    node-off))

(defn- make-child-and-value-node! [sio data-bm node-bm child-off vb]
  (let [vlen (bytes-length vb)
        nsize (+ NODE_HEADER_SIZE 4 (+ 4 vlen))
        node-off (slab/alloc! sio nsize)]
    (slab/write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (slab/write-u8!  sio node-off 1 0)
    (slab/write-u16! sio node-off 2 0)
    (slab/write-i32! sio node-off 4 data-bm)
    (slab/write-i32! sio node-off 8 node-bm)
    (slab/write-i32! sio node-off NODE_HEADER_SIZE child-off)
    (write-value! sio node-off (+ NODE_HEADER_SIZE 4) vb)
    node-off))

(defn- make-collision-node! [sio vh entries]
  (let [cnt (count entries)
        vals-size (reduce (fn [acc [vb]] (+ acc 4 (bytes-length vb))) 0 entries)
        node-off (slab/alloc! sio (+ COLLISION_HEADER_SIZE vals-size))]
    (slab/write-u8!  sio node-off 0 NODE_TYPE_COLLISION)
    (slab/write-u8!  sio node-off 1 cnt)
    (slab/write-u16! sio node-off 2 0)
    (slab/write-i32! sio node-off 4 vh)
    (slab/write-i32! sio node-off 8 0)
    (reduce (fn [pos [vb]]
              (write-value! sio node-off pos vb))
            COLLISION_HEADER_SIZE entries)
    node-off))

;;=============================================================================
;; Node copy helpers
;;=============================================================================

(defn- node-values-size [sio node-off data-bm node-bm]
  (let [dc (popcount32 data-bm)
        v-off (values-start-off node-bm)]
    (loop [i 0 pos v-off]
      (if (>= i dc)
        (- pos v-off)
        (recur (inc i) (skip-value-at sio node-off pos))))))

(defn- node-byte-size [sio node-off data-bm node-bm]
  (+ NODE_HEADER_SIZE
     (* 4 (popcount32 node-bm))
     (node-values-size sio node-off data-bm node-bm)))

(defn- copy-node-patch-child! [sio src-off data-bm node-bm child-idx new-child-off]
  (let [nsize (node-byte-size sio src-off data-bm node-bm)
        dst-off (slab/alloc! sio nsize)]
    (slab/copy-block! sio dst-off 0 src-off 0 nsize)
    (slab/write-i32! sio dst-off (+ NODE_HEADER_SIZE (* child-idx 4)) new-child-off)
    dst-off))

;;=============================================================================
;; HAMT Find (unified)
;;=============================================================================

(defn- hamt-find [sio root-off v vh vb]
  (loop [off root-off shift 0]
    (if (== off NIL_OFFSET)
      false
      (let [nt (slab/read-u8 sio off 0)]
        (case (int nt)
          1 (let [dbm (slab/read-i32 sio off 4)
                  nbm (slab/read-i32 sio off 8)
                  bit (node-bit dbm nbm vh shift)]
              (cond
                (has-bit? nbm bit)
                (recur (slab/read-i32 sio off
                         (+ NODE_HEADER_SIZE (* (get-index nbm bit) 4)))
                       (+ shift SHIFT_STEP))
                (has-bit? dbm bit)
                (let [di (get-index dbm bit)
                      vs (values-start-off nbm)
                      pos (loop [i 0 p vs]
                            (if (== i di) p
                              (recur (inc i) (skip-value-at sio off p))))]
                  (boolean (value-matches? sio off pos vb)))
                :else false))
          2 (let [ch (slab/read-i32 sio off 4)]
              (if (not= ch vh)
                false
                (let [cc (slab/read-u8 sio off 1)]
                  (loop [i 0 pos COLLISION_HEADER_SIZE]
                    (if (>= i cc)
                      false
                      (if (value-matches? sio off pos vb)
                        true
                        (recur (inc i) (skip-value-at sio off pos))))))))
          false)))))

;;=============================================================================
;; HAMT Conj (unified) — returns [new-root added?]
;;=============================================================================

(defn hamt-conj [sio root-off vh vb shift]
  (if (== root-off NIL_OFFSET)
    [(make-single-value-node! sio (bitpos vh shift) vb) true]
    (let [nt (slab/read-u8 sio root-off 0)]
      (case (int nt)
        1 (let [data-bm (slab/read-i32 sio root-off 4)
                node-bm (slab/read-i32 sio root-off 8)
                bit     (node-bit data-bm node-bm vh shift)]
            (cond
              (has-bit? node-bm bit)
              (let [child-idx (get-index node-bm bit)
                    child-off (slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* child-idx 4)))
                    [new-child added?] (hamt-conj sio child-off vh vb (+ shift SHIFT_STEP))]
                (if (== new-child child-off)
                  [root-off false]
                  [(copy-node-patch-child! sio root-off data-bm node-bm child-idx new-child)
                   added?]))

              (has-bit? data-bm bit)
              (let [di (get-index data-bm bit)
                    vs (values-start-off node-bm)
                    pos (loop [i 0 p vs]
                          (if (== i di) p
                            (recur (inc i) (skip-value-at sio root-off p))))]
                (if (value-matches? sio root-off pos vb)
                  [root-off false]
                  (let [[existing-vb _] (read-value-at sio root-off pos)
                        existing-vh (portable-hash-bytes existing-vb)]
                    (if (>= shift 30)
                      (let [coll (make-collision-node! sio vh [[existing-vb] [vb]])
                            new-data-bm (bit-xor data-bm bit)
                            new-node-bm (bit-or node-bm bit)
                            new-child-idx (get-index new-node-bm bit)]
                        (let [old-dc (popcount32 data-bm)
                              new-dc (popcount32 new-data-bm)
                              new-cc (popcount32 new-node-bm)
                              old-cc (popcount32 node-bm)
                              val-list (let [v-off (values-start-off node-bm)]
                                         (loop [i 0 p v-off acc []]
                                           (if (>= i old-dc) acc
                                             (let [[ev np] (read-value-at sio root-off p)]
                                               (recur (inc i) np
                                                      (if (== i di) acc (conj acc ev)))))))
                              vals-size (reduce (fn [a ev] (+ a 4 (bytes-length ev))) 0 val-list)
                              nsize (+ NODE_HEADER_SIZE (* 4 new-cc) vals-size)
                              dst-off (slab/alloc! sio nsize)]
                          (slab/write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
                          (slab/write-u8!  sio dst-off 1 0)
                          (slab/write-u16! sio dst-off 2 0)
                          (slab/write-i32! sio dst-off 4 new-data-bm)
                          (slab/write-i32! sio dst-off 8 new-node-bm)
                          (loop [si 0 di2 0]
                            (when (< di2 new-cc)
                              (if (== di2 new-child-idx)
                                (do (slab/write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di2 4)) coll)
                                    (recur si (inc di2)))
                                (do (slab/write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di2 4))
                                      (slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* si 4))))
                                    (recur (inc si) (inc di2))))))
                          (reduce (fn [p ev] (write-value! sio dst-off p ev))
                                  (values-start-off new-node-bm) val-list)
                          [dst-off true]))
                      (let [sub-shift (+ shift SHIFT_STEP)
                            existing-vh (portable-hash-bytes existing-vb)
                            existing-bit (bitpos existing-vh sub-shift)
                            new-bit (bitpos vh sub-shift)]
                        (let [[sub _] (hamt-conj sio NIL_OFFSET existing-vh existing-vb sub-shift)
                              [final-sub _] (hamt-conj sio sub vh vb sub-shift)
                              ;; sub (a single-value node made here, never published) is copied into final-sub,
                              ;; never referenced by it: freed now — it was left allocated by every conj that
                              ;; split a slot (2026-10-20 ET: a fresh 200-element set kept 52 such nodes)
                              _ (slab/free! sio sub)
                              new-data-bm (bit-xor data-bm bit)
                              new-node-bm (bit-or node-bm bit)
                              new-child-idx (get-index new-node-bm bit)]
                          (let [old-dc (popcount32 data-bm)
                                new-dc (popcount32 new-data-bm)
                                new-cc (popcount32 new-node-bm)
                                old-cc (popcount32 node-bm)
                                val-list (let [v-off (values-start-off node-bm)]
                                           (loop [i 0 p v-off acc []]
                                             (if (>= i old-dc) acc
                                               (let [[ev np] (read-value-at sio root-off p)]
                                                 (recur (inc i) np
                                                        (if (== i di) acc (conj acc ev)))))))
                                vals-size (reduce (fn [a ev] (+ a 4 (bytes-length ev))) 0 val-list)
                                nsize (+ NODE_HEADER_SIZE (* 4 new-cc) vals-size)
                                dst-off (slab/alloc! sio nsize)]
                            (slab/write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
                            (slab/write-u8!  sio dst-off 1 0)
                            (slab/write-u16! sio dst-off 2 0)
                            (slab/write-i32! sio dst-off 4 new-data-bm)
                            (slab/write-i32! sio dst-off 8 new-node-bm)
                            (loop [si 0 di2 0]
                              (when (< di2 new-cc)
                                (if (== di2 new-child-idx)
                                  (do (slab/write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di2 4)) final-sub)
                                      (recur si (inc di2)))
                                  (do (slab/write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di2 4))
                                        (slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* si 4))))
                                      (recur (inc si) (inc di2))))))
                            (reduce (fn [p ev] (write-value! sio dst-off p ev))
                                    (values-start-off new-node-bm) val-list)
                            [dst-off true])))))))

              :else
              (let [di (get-index data-bm bit)
                    new-data-bm (bit-or data-bm bit)
                    old-dc (popcount32 data-bm)
                    new-dc (popcount32 new-data-bm)
                    cc (popcount32 node-bm)
                    val-list (let [v-off (values-start-off node-bm)]
                               (loop [i 0 p v-off acc []]
                                 (if (>= i old-dc) acc
                                   (let [[ev np] (read-value-at sio root-off p)]
                                     (recur (inc i) np (conj acc ev))))))
                    new-val-list (vec (concat (subvec val-list 0 di) [vb] (subvec val-list di)))
                    vals-size (reduce (fn [a ev] (+ a 4 (bytes-length ev))) 0 new-val-list)
                    nsize (+ NODE_HEADER_SIZE (* 4 cc) vals-size)
                    dst-off (slab/alloc! sio nsize)]
                (slab/write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
                (slab/write-u8!  sio dst-off 1 0)
                (slab/write-u16! sio dst-off 2 0)
                (slab/write-i32! sio dst-off 4 new-data-bm)
                (slab/write-i32! sio dst-off 8 node-bm)
                (when (pos? cc)
                  (slab/copy-block! sio dst-off NODE_HEADER_SIZE root-off NODE_HEADER_SIZE (* 4 cc)))
                (reduce (fn [p ev] (write-value! sio dst-off p ev))
                        (values-start-off node-bm) new-val-list)
                [dst-off true])))

        2 (let [node-hash (slab/read-i32 sio root-off 4)
                cc (slab/read-u8 sio root-off 1)]
            (if (== vh node-hash)
              (let [entries (loop [i 0 pos COLLISION_HEADER_SIZE acc []]
                              (if (>= i cc) acc
                                (let [[ev np] (read-value-at sio root-off pos)]
                                  (recur (inc i) np (conj acc ev)))))]
                (if (some #(bytes-equal? % vb) entries)
                  [root-off false]
                  [(make-collision-node! sio vh (mapv (fn [ev] [ev]) (conj entries vb))) true]))
              (if (>= shift 30)
                (let [entries (loop [i 0 pos COLLISION_HEADER_SIZE acc []]
                                (if (>= i cc) acc
                                  (let [[ev np] (read-value-at sio root-off pos)]
                                    (recur (inc i) np (conj acc ev)))))]
                  [(make-collision-node! sio node-hash (mapv (fn [ev] [ev]) (conj entries vb))) true])
                (let [bit1 (bitpos node-hash shift)
                      bit2 (bitpos vh shift)]
                  (if (== bit1 bit2)
                    (let [[new-child _] (hamt-conj sio root-off vh vb (+ shift SHIFT_STEP))]
                      [(make-single-child-node! sio bit1 new-child) true])
                    [(make-child-and-value-node! sio bit2 bit1 root-off vb) true])))))

        [root-off false]))))

;;=============================================================================
;; HAMT Disj (unified)
;;=============================================================================

(defn- hamt-disj [sio root-off vh vb shift]
  (if (== root-off NIL_OFFSET)
    [root-off false]
    (let [nt (slab/read-u8 sio root-off 0)]
      (case (int nt)
        1 (let [data-bm (slab/read-i32 sio root-off 4)
                node-bm (slab/read-i32 sio root-off 8)
                bit     (node-bit data-bm node-bm vh shift)]
            (cond
              (has-bit? node-bm bit)
              (let [child-idx (get-index node-bm bit)
                    child-off (slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* child-idx 4)))
                    [new-child removed?] (hamt-disj sio child-off vh vb (+ shift SHIFT_STEP))]
                (if-not removed?
                  [root-off false]
                  (if (== new-child NIL_OFFSET)
                    [(copy-node-patch-child! sio root-off data-bm node-bm child-idx NIL_OFFSET) true]
                    [(copy-node-patch-child! sio root-off data-bm node-bm child-idx new-child) true])))

              (has-bit? data-bm bit)
              (let [di (get-index data-bm bit)
                    vs (values-start-off node-bm)
                    pos (loop [i 0 p vs]
                          (if (== i di) p
                            (recur (inc i) (skip-value-at sio root-off p))))]
                (if (value-matches? sio root-off pos vb)
                  (let [new-data-bm (bit-xor data-bm bit)
                        new-dc (popcount32 new-data-bm)
                        cc (popcount32 node-bm)]
                    (if (and (zero? new-dc) (zero? cc))
                      [NIL_OFFSET true]
                      (let [old-dc (popcount32 data-bm)
                            val-list (let [v-off (values-start-off node-bm)]
                                       (loop [i 0 p v-off acc []]
                                         (if (>= i old-dc) acc
                                           (let [[ev np] (read-value-at sio root-off p)]
                                             (recur (inc i) np
                                                    (if (== i di) acc (conj acc ev)))))))
                            vals-size (reduce (fn [a ev] (+ a 4 (bytes-length ev))) 0 val-list)
                            nsize (+ NODE_HEADER_SIZE (* 4 cc) vals-size)
                            dst-off (slab/alloc! sio nsize)]
                        (slab/write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
                        (slab/write-u8!  sio dst-off 1 0)
                        (slab/write-u16! sio dst-off 2 0)
                        (slab/write-i32! sio dst-off 4 new-data-bm)
                        (slab/write-i32! sio dst-off 8 node-bm)
                        (when (pos? cc)
                          (slab/copy-block! sio dst-off NODE_HEADER_SIZE root-off NODE_HEADER_SIZE (* 4 cc)))
                        (reduce (fn [p ev] (write-value! sio dst-off p ev))
                                (values-start-off node-bm) val-list)
                        [dst-off true])))
                  [root-off false]))

              :else [root-off false]))

        2 (let [node-hash (slab/read-i32 sio root-off 4)
                cc (slab/read-u8 sio root-off 1)]
            (if (not= vh node-hash)
              [root-off false]
              (let [entries (loop [i 0 pos COLLISION_HEADER_SIZE acc []]
                              (if (>= i cc) acc
                                (let [[ev np] (read-value-at sio root-off pos)]
                                  (recur (inc i) np (conj acc ev)))))]
                (if-let [idx (some (fn [i] (when (bytes-equal? (nth entries i) vb) i))
                               (range cc))]
                  (if (== cc 1)
                    [NIL_OFFSET true]
                    (let [remaining (vec (concat (subvec entries 0 idx) (subvec entries (inc idx))))]
                      [(make-collision-node! sio vh (mapv (fn [ev] [ev]) remaining)) true]))
                  [root-off false]))))

        [root-off false]))))

;;=============================================================================
;; HAMT Reduce / Seq (unified)
;;=============================================================================

(defn hamt-val-reduce [sio root-off f init]
  (if (== root-off NIL_OFFSET)
    init
    (let [nt (slab/read-u8 sio root-off 0)]
      (case (int nt)
        1 (let [data-bm (slab/read-i32 sio root-off 4)
                node-bm (slab/read-i32 sio root-off 8)
                dc      (popcount32 data-bm)
                cc      (popcount32 node-bm)
                vs      (values-start-off node-bm)
                acc (loop [i 0 pos vs acc init]
                      (if (or (>= i dc) (reduced? acc))
                        acc
                        (let [vlen (slab/read-i32 sio root-off pos)
                              vbs  (slab/read-bytes sio root-off (+ pos 4) vlen)
                              v    (deserialize-val-bytes vbs sio)]
                          (recur (inc i) (+ pos 4 vlen) (f acc v)))))]
            (if (reduced? acc)
              acc
              (loop [i 0 acc acc]
                (if (or (>= i cc) (reduced? acc))
                  acc
                  (let [child-off (slab/read-i32 sio root-off (+ NODE_HEADER_SIZE (* i 4)))]
                    (recur (inc i) (hamt-val-reduce sio child-off f acc)))))))

        2 (let [cc (slab/read-u8 sio root-off 1)]
            (loop [i 0 pos COLLISION_HEADER_SIZE acc init]
              (if (or (>= i cc) (reduced? acc))
                acc
                (let [vlen (slab/read-i32 sio root-off pos)
                      vbs  (slab/read-bytes sio root-off (+ pos 4) vlen)
                      v    (deserialize-val-bytes vbs sio)]
                  (recur (inc i) (+ pos 4 vlen) (f acc v))))))

        init))))

(defn- hamt-seq [sio root-off]
  (let [entries (volatile! (transient []))]
    (hamt-val-reduce sio root-off
      (fn [_ v] (vswap! entries conj! v))
      nil)
    (seq (persistent! @entries))))

;;=============================================================================
;; Header read/write
;;=============================================================================

(defn write-set-header! [sio cnt root-off]
  (let [off (slab/alloc! sio 12)]
    (slab/write-u8!  sio off 0 EveHashSet-type-id)
    (slab/write-u8!  sio off 1 SET_FLAG_PORTABLE_HASH)
    (slab/write-u16! sio off 2 0)
    (slab/write-i32! sio off SABSETROOT_CNT_OFFSET cnt)
    (slab/write-i32! sio off SABSETROOT_ROOT_OFF_OFFSET root-off)
    off))

(defn read-set-header [sio header-off]
  [(slab/read-i32 sio header-off SABSETROOT_CNT_OFFSET)
   (slab/read-i32 sio header-off SABSETROOT_ROOT_OFF_OFFSET)])

;;=============================================================================
;; HAMT node freeing helpers (via ISlabIO)
;;=============================================================================

(declare free-hamt-node!)

(defn- free-hamt-node!
  "Recursively free a HAMT node and all its children via ISlabIO."
  [sio slab-off]
  (when (not= slab-off NIL_OFFSET)
    (let [node-type (slab/read-u8 sio slab-off 0)]
      (case (int node-type)
        ;; Bitmap node — free children first
        1 (let [node-bm (slab/read-i32 sio slab-off 8)
                child-count (popcount32 node-bm)]
            (dotimes [i child-count]
              (let [child-off (slab/read-i32 sio slab-off (+ NODE_HEADER_SIZE (* i 4)))]
                (free-hamt-node! sio child-off)))
            (slab/free! sio slab-off))
        ;; Collision node — no children
        2 (slab/free! sio slab-off)
        ;; Unknown
        (slab/free! sio slab-off)))))

;;=============================================================================
;; Disposal & Retirement
;;=============================================================================

(defn dispose!
  "Dispose an EveHashSet, freeing its entire HAMT tree and header block.
   Call this when the set is no longer needed to reclaim slab memory.

   WARNING: After disposal, the set must not be used."
  [hash-set]
  (let [sio (d/-eve-sio hash-set)
        header-off (d/-root-header-off hash-set)
        root-off (slab/read-i32 sio header-off SABSETROOT_ROOT_OFF_OFFSET)]
    (when (not= root-off NIL_OFFSET)
      (free-hamt-node! sio root-off))
    (when (not= header-off NIL_OFFSET)
      (slab/free! sio header-off))))

(defn retire-replaced-path!
  "After an atom swap that replaced old-root with new-root, free the old
   path nodes that are no longer referenced by the new tree.
   vh: the hash of the value that was modified"
  [sio old-root new-root vh]
  (when (and (not= old-root NIL_OFFSET) (not= old-root new-root))
    (loop [old-off old-root
           new-off new-root
           sh 0]
      (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
        (slab/free! sio old-off)
        (let [old-type (slab/read-u8 sio old-off 0)]
          (when (== old-type NODE_TYPE_BITMAP)
            (let [bit-pos (bit-and (unsigned-bit-shift-right vh sh) MASK)
                  old-node-bm (slab/read-i32 sio old-off 8)
                  new-type (when (not= new-off NIL_OFFSET) (slab/read-u8 sio new-off 0))
                  new-node-bm (when (and new-type (== new-type NODE_TYPE_BITMAP))
                                (slab/read-i32 sio new-off 8))
                  old-bit (bit-shift-left 1 bit-pos)]
              (when (and (not (zero? (bit-and old-node-bm old-bit)))
                         new-node-bm
                         (not (zero? (bit-and new-node-bm old-bit))))
                (let [old-child-idx (popcount32 (bit-and old-node-bm (dec old-bit)))
                      new-child-idx (popcount32 (bit-and new-node-bm (dec old-bit)))
                      old-child (slab/read-i32 sio old-off (+ NODE_HEADER_SIZE (* old-child-idx 4)))
                      new-child (slab/read-i32 sio new-off (+ NODE_HEADER_SIZE (* new-child-idx 4)))]
                  (recur old-child new-child (+ sh SHIFT_STEP)))))))))))

(defn retire-tree-diff!
  "Full tree diff: walk old and new HAMT trees in parallel, freeing all
   old nodes that differ from the new tree."
  [sio old-root new-root]
  (when (and (not= old-root NIL_OFFSET) (not= old-root new-root))
    (letfn [(walk [old-off new-off]
              (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
                (slab/free! sio old-off)
                (let [old-type (slab/read-u8 sio old-off 0)]
                  (when (== old-type NODE_TYPE_BITMAP)
                    (let [old-node-bm (slab/read-i32 sio old-off 8)
                          new-type (when (not= new-off NIL_OFFSET) (slab/read-u8 sio new-off 0))
                          new-node-bm (when (and new-type (== new-type NODE_TYPE_BITMAP))
                                        (slab/read-i32 sio new-off 8))]
                      (loop [remaining (bit-and old-node-bm 0xFFFFFFFF) ;; unsigned: bit 31 as a negative long never reaches zero
                             old-idx 0]
                        (when (not (zero? remaining))
                          (let [bit (bit-and remaining (- remaining))
                                old-child (slab/read-i32 sio old-off (+ NODE_HEADER_SIZE (* old-idx 4)))
                                new-child (if (and new-node-bm (not (zero? (bit-and new-node-bm bit))))
                                            (let [new-idx (popcount32 (bit-and new-node-bm (dec bit)))]
                                              (slab/read-i32 sio new-off (+ NODE_HEADER_SIZE (* new-idx 4))))
                                            NIL_OFFSET)]
                            (walk old-child new-child)
                            (recur (bit-and remaining (dec remaining)) (inc old-idx))))))))))]
      (walk old-root new-root))))

;;=============================================================================
;; EveHashSet deftype — unified via eve/deftype macro
;;
;; Fields: cnt (int32 @ offset 4), root-off (int32 @ offset 8)
;; Both platforms: deftype EveHashSet [sio__ offset__]
;;=============================================================================

(declare make-hash-set)
(declare ->transient-set)
(declare eve-hash-set?)

(defn- set-equiv?
  "The set at root-off (cnt values) equals other: a set of as many values,
   each of them in this one."
  [sio root-off cnt other]
  (and (set? other)
       (== cnt (count other))
       (every? (fn [v]
                 (let [vb (serialize-val-bytes sio v)]
                   (hamt-find sio root-off v (portable-hash-bytes vb) vb)))
               other)))

(defn- root-digest-verdict
  "Merkle Phase 4 (§2b) digest seam for equiv — ported from canonical eve
   (commit 366e9b2); wired to
   the Phase 3 eve cache (eve.digest, layout B-prime) 2026-10-25. Answers
   :equal or :unequal for two EveHashSet roots whose content digests
   decide, and :walk for everything else (a root of any other type, a
   root whose content has no digest — anything held by reference — any
   doubt). The verdict itself carries the partition gate — it must answer
   :walk unless BOTH roots are EveHashSet roots with comparable digests —
   because this type's equiv delegates its structural check to
   set-equiv?. equiv falls back to set-equiv? on :walk."
  [a b]
  (if (eve-hash-set? b)
    (dg/digest-verdict (d/-eve-sio a) (d/-root-header-off a) a
                       (d/-eve-sio b) (d/-root-header-off b) b)
    :walk))

(eve/deftype EveHashSet [^:int32 cnt ^:int32 root-off]
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
  (valAt [_ v]
    (let [sio sio__
          vb (serialize-val-bytes sio v)
          vh (portable-hash-bytes vb)]
      (if (hamt-find sio root-off v vh vb) v nil)))
  (valAt [_ v not-found]
    (let [sio sio__
          vb (serialize-val-bytes sio v)
          vh (portable-hash-bytes vb)]
      (if (hamt-find sio root-off v vh vb)
        v
        not-found)))

  clojure.lang.IPersistentSet
  (disjoin [this v]
    (let [sio sio__
          vb (serialize-val-bytes sio v)
          vh (portable-hash-bytes vb)
          [new-root removed?] (hamt-disj sio root-off vh vb 0)]
      (if-not removed?
        this
        (make-hash-set sio (dec cnt) new-root))))
  ;; (dropped on ClojureScript: (get set v) reaches a set through ILookup there)
  (get [_ v]
    (let [sio sio__
          vb (serialize-val-bytes sio v)
          vh (portable-hash-bytes vb)]
      (when (hamt-find sio root-off v vh vb) v)))

  clojure.lang.IPersistentCollection
  (cons [this v]
    (let [sio sio__
          vb (serialize-val-bytes sio v)
          vh (portable-hash-bytes vb)
          [new-root added?] (hamt-conj sio root-off vh vb 0)]
      (if-not added?
        this
        (make-hash-set sio (inc cnt) new-root))))
  (empty [_]
    (make-hash-set sio__ 0 NIL_OFFSET))
  (equiv [this other]
    (let [verdict (root-digest-verdict this other)]
      (cond
        (= verdict :equal) true
        (= verdict :unequal) false
        :else (set-equiv? sio__ root-off cnt other))))

  clojure.lang.IReduce
  (reduce [this f]
    (let [s (.seq this)]
      (if s
        (reduce f (first s) (rest s))
        (f))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (unreduced (hamt-val-reduce sio__ root-off f init)))

  clojure.lang.IHashEq
  (hasheq [this] (dg/root-hash sio__ offset__ (fn [] (p/hash-unordered this))))

  clojure.lang.IFn
  (invoke [this v]
    (let [vb (serialize-val-bytes sio__ v)
          vh (portable-hash-bytes vb)]
      (when (hamt-find sio__ root-off v vh vb) v)))
  (invoke [this v nf]
    (let [vb (serialize-val-bytes sio__ v)
          vh (portable-hash-bytes vb)]
      (if (hamt-find sio__ root-off v vh vb) v nf)))

  

  d/IDirectSerialize
  (-direct-serialize [this] offset__)

  d/ISabStorable
  (-sab-tag [_] :hash-set)
  (-sab-encode [this _] (codec/encode-eve-pointer this))
  (-sab-dispose [_ _] nil)

  d/IsEve
  (-eve? [_] true)

  d/IEveRoot
  (-root-header-off [this] offset__)

  d/IEveSio
  (-eve-sio [_] sio__)

  ;; what a deref of an atom holding it answers: a plain Clojure set
  ;; (see EveHashMap's IPlain note: eve->clj cannot rely on set? here).
  d/IPlain
  (-plain [this] (into #{} (map d/eve->clj) (ecore/seq this)))

  ;; java.lang.Object in main body so macro sees it and skips default IPrintWithWriter.
  ;; Proto map marks it :clj-only? so CLJS translation strips it automatically.
  java.lang.Object
  (toString [this] (pr-str this))
  (equals [this other]
    (or (identical? this other)
        (set-equiv? sio__ root-off cnt other)))
  (hashCode [this]
    (unchecked-int (reduce + 0 (map hash (.seq this)))))

  ;; --- the JVM's (dropped on ClojureScript, where (contains? s v) is
  ;; ILookup's; on the CLR java.util.Set is dropped and Iterable is
  ;; IEnumerable) ---
  clojure.lang.IPersistentSet
  (contains [_ v]
    (let [^bytes vb (serialize-val-bytes sio__ v)
          vh (portable-hash-bytes vb)]
      (hamt-find sio__ root-off v vh vb)))

  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  java.util.Set
  (size [_] (int cnt))
  (isEmpty [_] (zero? cnt))
  (toArray [this] (clojure.lang.RT/seqToArray (.seq this)))
  ;; contains is already defined via IPersistentSet above
  (^boolean containsAll [this ^java.util.Collection c]
    (boolean (every? #(.contains this %) c)))
  ;; Unsupported mutators (immutable set)
  (add [_ _] (throw (UnsupportedOperationException.)))
  (remove [_ _] (throw (UnsupportedOperationException.)))
  (addAll [_ _] (throw (UnsupportedOperationException.)))
  (retainAll [_ _] (throw (UnsupportedOperationException.)))
  (removeAll [_ _] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.)))

  clojure.lang.IEditableCollection
  (asTransient [this]
    (eve.set/->transient-set this)))

;;=============================================================================
;; Offset Collection (for epoch-based retirement in mmap atoms)
;;=============================================================================

(defn collect-hamt-offsets
  "Recursively collect all slab offsets in a HAMT node tree."
  [sio slab-off result]
  (when (not= slab-off NIL_OFFSET)
    (vswap! result conj! slab-off)
    (let [node-type (slab/read-u8 sio slab-off 0)]
      (when (== node-type NODE_TYPE_BITMAP)
        (let [node-bm (slab/read-i32 sio slab-off 8)
              child-count (popcount32 node-bm)]
          (dotimes [i child-count]
            (let [child-off (slab/read-i32 sio slab-off (+ NODE_HEADER_SIZE (* i 4)))]
              (collect-hamt-offsets sio child-off result))))))))

(defn- collect-hamt-diff-offsets
  "Collect old HAMT nodes that differ from the new tree."
  [sio old-root new-root result]
  (when (and (not= old-root NIL_OFFSET) (not= old-root new-root))
    (letfn [(walk [old-off new-off]
              (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
                (vswap! result conj! old-off)
                (let [old-type (slab/read-u8 sio old-off 0)]
                  (when (== old-type NODE_TYPE_BITMAP)
                    (let [old-node-bm (slab/read-i32 sio old-off 8)
                          new-type (when (not= new-off NIL_OFFSET) (slab/read-u8 sio new-off 0))
                          new-node-bm (when (and new-type (== new-type NODE_TYPE_BITMAP))
                                        (slab/read-i32 sio new-off 8))]
                      (loop [remaining (bit-and old-node-bm 0xFFFFFFFF) ;; unsigned: bit 31 as a negative long never reaches zero
                             old-idx 0]
                        (when (not (zero? remaining))
                          (let [bit (bit-and remaining (- remaining))
                                old-child (slab/read-i32 sio old-off (+ NODE_HEADER_SIZE (* old-idx 4)))
                                new-child (if (and new-node-bm (not (zero? (bit-and new-node-bm bit))))
                                            (let [new-idx (popcount32 (bit-and new-node-bm (dec bit)))]
                                              (slab/read-i32 sio new-off (+ NODE_HEADER_SIZE (* new-idx 4))))
                                            NIL_OFFSET)]
                            (walk old-child new-child)
                            (recur (bit-and remaining (dec remaining)) (inc old-idx))))))))))]
      (walk old-root new-root))))

(defn collect-retire-diff-offsets
  "Collect all slab offsets that would be freed by -sab-retire-diff!.
   Returns a vector of offsets to free when the epoch is safe."
  [old-set new-value]
  (let [sio (d/-eve-sio old-set)
        header-off (d/-root-header-off old-set)
        root-off (slab/read-i32 sio header-off SABSETROOT_ROOT_OFF_OFFSET)
        result (volatile! (transient []))]
    (if (eve-hash-set? new-value)
      (let [new-root-off (slab/read-i32 sio (d/-root-header-off new-value) SABSETROOT_ROOT_OFF_OFFSET)]
        (collect-hamt-diff-offsets sio root-off new-root-off result))
      ;; Different type — collect entire old tree
      (collect-hamt-offsets sio root-off result))
    ;; Always include header
    (when (not= header-off NIL_OFFSET)
      (vswap! result conj! header-off))
    (persistent! @result)))

;;=============================================================================
;; ISabRetirable
;;=============================================================================

(extend-type EveHashSet
  d/ISabRetirable
  (-sab-retire-diff! [this new-value _slab-env mode]
    (let [sio (.-sio__ this)
          old-root (slab/read-i32 sio (.-offset__ this) SABSETROOT_ROOT_OFF_OFFSET)]
      (if (eve-hash-set? new-value)
        (let [new-root-off (slab/read-i32 sio (d/-root-header-off new-value) SABSETROOT_ROOT_OFF_OFFSET)]
          (retire-tree-diff! sio old-root new-root-off))
        (when (not= old-root NIL_OFFSET)
          (free-hamt-node! sio old-root)))
      ;; Free the header block
      (when (not= (.-offset__ this) NIL_OFFSET)
        (slab/free! sio (.-offset__ this))))))

;;=============================================================================
;; Constructors
;;=============================================================================

(do
(defn- make-hash-set
  "Internal constructor."
  [sio cnt root-off]
  (let [hdr (write-set-header! sio cnt root-off)]
    (EveHashSet. sio hdr)))

(defn hash-set-from-header
  "Reconstruct an EveHashSet from a header offset."
  [sio header-off]
  (EveHashSet. sio header-off))

(defn empty-hash-set
  "Create an empty Eve hash set.
   0-arity: uses platform default sio (fresh heap domain on JVM).
   1-arity: explicit sio."
  ([]  (empty-hash-set (default-sio)))
  ([sio] (make-hash-set sio 0 NIL_OFFSET)))

(defn hash-set
  "Create an Eve hash set from values.
   If first arg satisfies ISlabIO, uses it as sio.
   Otherwise uses platform default sio and all args as values."
  [& args]
  (let [sio-or-default (default-sio)
        [sio vals] (if (and (seq args) (satisfies? alloc/ISlabIO (first args)))
                     [(first args) (rest args)]
                     [sio-or-default args])]
    (reduce ecore/conj (empty-hash-set sio) vals)))
)

;;=============================================================================
;; Transients: a persistent collection behind a transient's interface — each
;; op makes the next persistent one (eve.core's ops: on bb the type answers
;; eve.core's protocols), and frees what it superseded in the one before
;;=============================================================================

(defn collect-member-path-diff-offsets
  "The blocks set old-set holds that new-set does not, when new-set is old-set
   with one member written (a conj or a disj of v): a one-member write
   path-copies only the nodes on that member's hash path — a slot split, a
   collapse and a collision node all sit on it — so the two paths are stepped
   together to the first node they share; and the old header. Depth reads,
   where the full walk reads every child slot of each node on the path."
  [old-set new-set v]
  (let [sio (d/-eve-sio old-set)
        vh (portable-hash-bytes (serialize-val-bytes sio v))
        root-of (fn [x] (slab/read-i32 sio (d/-root-header-off x) SABSETROOT_ROOT_OFF_OFFSET))
        ;; the child on vh's path, NIL where vh's slot holds none
        down (fn [off shift]
               (if (and (not= off NIL_OFFSET) (== NODE_TYPE_BITMAP (slab/read-u8 sio off 0)))
                 (let [dbm (slab/read-i32 sio off 4)
                       nbm (slab/read-i32 sio off 8)
                       bit (node-bit dbm nbm vh shift)]
                   (if (has-bit? nbm bit)
                     (slab/read-i32 sio off (+ NODE_HEADER_SIZE (* (get-index nbm bit) 4)))
                     NIL_OFFSET))
                 NIL_OFFSET))]
    (loop [o (root-of old-set) n (root-of new-set) shift 0 out [(d/-root-header-off old-set)]]
      (if (or (== o NIL_OFFSET) (== o n))
        out
        (recur (down o shift) (down n shift) (+ shift SHIFT_STEP) (conj out o))))))

(defn- edit
  "(f s), s read through sio (a transient's allocation log), and the blocks of
   s it superseded that sio made freed — by the walk of its one-member write of
   v: the walk between two versions only the transient holds, never a block of
   the set it began from (
   2026-10-21 ET)."
  [sio s f v]
  (let [s' (f s)]
    ;; an op that answers the same set superseded nothing
    (when-not (== (d/-root-header-off s) (d/-root-header-off s'))
      (alloc/free-owned! sio (collect-member-path-diff-offsets s s' v)))
    s'))

(do
     (eve/host-deftype TransientEveHashSet [^:volatile-mutable s sio]
       clojure.lang.ITransientSet
       (disjoin [this v]
         (set! s (edit sio s #(ecore/disj % v) v))
         this)
       (contains [_ v] (ecore/contains? s v))
       (get [_ v] (ecore/get s v))
       (count [_] (ecore/count s))

       clojure.lang.ITransientCollection
       (conj [this v]
         (set! s (edit sio s #(ecore/conj % v) v))
         this)
       (persistent [_] (alloc/stop-logging! sio) s))

     (defn ->transient-set [s]
       ;; the set read through a scope of its own over its domain: what its ops allocate is logged there
       (let [sio (alloc/scope-sio (d/-eve-sio s))]
         (->TransientEveHashSet (hash-set-from-header sio (d/-root-header-off s)) sio))))

(defn eve-hash-set?
  "True for a slab-backed EveHashSet. On babashka a set read from eve memory is
   the host's own set (eve.types/reader-of), so one read there is not one."
  [x]
  (instance? EveHashSet x))

;;=============================================================================
;; Registration
;;=============================================================================

;; Collection writer: a host collection written straight into the slab, for a
;; codec that writes one directly (eve.codec)
(defn- -write-set-coll!
  "Serialize a Clojure set to slab as an Eve HAMT set. Returns header offset.
   sio is explicit (first parameter)."
  [sio _serialize-elem coll]
  (let [entries (mapv (fn [v]
                        (let [^bytes vb (codec/encode-key v)
                              vh (portable-hash-bytes vb)]
                          [vh vb]))
                      coll)]
    (if (empty? entries)
      (write-set-header! sio 0 NIL_OFFSET)
      (let [root-off (reduce (fn [root [vh vb]]
                               (let [[new-root _] (hamt-conj sio root vh vb 0)
                                     gone (volatile! (transient []))]
                                 ;; the nodes this conj's path copy replaced were made by this writer and never
                                 ;; published: freed now (eve.map's writer says why)
                                 (collect-hamt-diff-offsets sio root new-root gone)
                                 (doseq [off (persistent! @gone)]
                                   (slab/free! sio off))
                                 new-root))
                             NIL_OFFSET entries)]
        (write-set-header! sio (count coll) root-off)))))

;; into-hash-set — the builder register-eve-type! installs for plain sets
(defn- into-hash-set
     "Build an EveHashSet from a Clojure set."
     ([coll] (into-hash-set (default-sio) coll))
     ([sio coll] (reduce ecore/conj (empty-hash-set sio) coll)))

;; Type constructor + disposer + builder + collection writer registrations
(defn- set->host-value
  "A set in eve memory as the host's own set: what a host whose types
   cannot stand in for its collections reads (eve.types/reader-of)."
  [sio header-off]
  (let [[_cnt root-off] (read-set-header sio header-off)]
    (hamt-val-reduce sio root-off (fn [s v] (conj s v)) #{})))

(eve/register-eve-type!
    {:fast-tag     ser/FAST_TAG_SAB_SET
     :type-id      EveHashSet-type-id
     :from-header  hash-set-from-header
     :host-value   set->host-value
     :dispose      dispose!
     :builder-pred set?
     :builder-ctor into-hash-set
     :coll-writer  {:tag :set :fn -write-set-coll!}
     :type         EveHashSet
     :print        {:open "#{" :close "}"}})

;; Backward-compat aliases
(do
     (defn jvm-write-set!
       "Serialize a Clojure set to slab. Returns header offset.
        Backward-compat alias for the registered :set writer."
       [sio serialize-val coll]
       (codec/write-collection! :set sio coll))

     (defn jvm-eve-hash-set-from-offset
       "Reconstruct an EveHashSet from a header offset.
        Backward-compat alias. coll-factory arg is ignored (registry-based)."
       ([sio header-off] (hash-set-from-header sio header-off))
       ([sio header-off _coll-factory] (hash-set-from-header sio header-off))))

;; No-op pool stub — pool system removed, kept for backward compat
(defn reset-pools! [] nil)
