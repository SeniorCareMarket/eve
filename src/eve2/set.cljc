(ns eve2.set
  "Eve2 persistent HAMT set — unified CLJ/CLJS implementation.

   Uses ISlabIO protocol for all memory access.
   Sets store values only (no key-hash arrays like maps).
   Collision nodes use type 2 (distinct from map collision type 3).

   Uses eve2/deftype macro: one deftype form, CLJS protocol names auto-mapped."
  (:refer-clojure :exclude [hash-set])
  (:require
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-read-u16 -sio-read-i32
            -sio-read-bytes -sio-write-u8! -sio-write-u16!
            -sio-write-i32! -sio-write-bytes! -sio-alloc!
            -sio-free! -sio-copy-block! NIL_OFFSET]]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.hamt-util :as hu :refer [portable-hash-bytes popcount32
                                  mask-hash bitpos has-bit? get-index]]
   #?@(:cljs [[eve.deftype-proto.alloc :as eve-alloc]]
       :clj  [[eve2.deftype :as eve2]
              [eve.mem :as mem :refer [eve-bytes->value value->eve-bytes
                                       value+sio->eve-bytes
                                       register-jvm-collection-writer!]]]))
  #?(:cljs (:require-macros [eve2.deftype :as eve2])))

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
(def ^:const EveHashSet-type-id 0xEE)
(def ^:const SABSETROOT_CNT_OFFSET 4)
(def ^:const SABSETROOT_ROOT_OFF_OFFSET 8)
(def ^:const SET_FLAG_PORTABLE_HASH 0x01)

;;=============================================================================
;; Platform-specific helpers
;;=============================================================================

(defn- serialize-val-bytes [v]
  #?(:cljs (ser/serialize-element v)
     :clj  (value->eve-bytes v)))

(defn- deserialize-val-bytes [val-bytes]
  #?(:cljs (ser/deserialize-element {} val-bytes)
     :clj  (eve-bytes->value val-bytes)))

(defn- bytes-equal? [a b]
  #?(:cljs (and (== (.-length a) (.-length b))
                (loop [i 0]
                  (if (>= i (.-length a)) true
                    (if (not= (aget a i) (aget b i)) false (recur (inc i))))))
     :clj  (java.util.Arrays/equals ^bytes a ^bytes b)))

(defn- bytes-length [ba]
  #?(:cljs (.-length ba)
     :clj  (alength ^bytes ba)))

(defn- get-sio []
  #?(:cljs eve-alloc/cljs-sio
     :clj  alloc/*jvm-slab-ctx*))

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
  (let [vlen (-sio-read-i32 sio node-off pos)
        vb   (-sio-read-bytes sio node-off (+ pos 4) vlen)]
    [vb (+ pos 4 vlen)]))

(defn- skip-value-at
  "Skip a value entry, returning offset after it."
  [sio node-off pos]
  (let [vlen (-sio-read-i32 sio node-off pos)]
    (+ pos 4 vlen)))

(defn- write-value!
  "Write a value at pos within node. Returns offset after written data."
  [sio node-off pos vb]
  (let [vlen (bytes-length vb)]
    (-sio-write-i32! sio node-off pos vlen)
    (when (pos? vlen)
      (-sio-write-bytes! sio node-off (+ pos 4) vb))
    (+ pos 4 vlen)))

(defn- value-matches?
  "Check if value bytes at pos match vb."
  [sio node-off pos vb]
  (let [stored-len (-sio-read-i32 sio node-off pos)]
    (when (== stored-len (bytes-length vb))
      (let [stored-bytes (-sio-read-bytes sio node-off (+ pos 4) stored-len)]
        (bytes-equal? stored-bytes vb)))))

;;=============================================================================
;; Node construction
;;=============================================================================

(defn- make-single-value-node! [sio data-bm vb]
  (let [vlen (bytes-length vb)
        nsize (+ NODE_HEADER_SIZE (+ 4 vlen))
        node-off (-sio-alloc! sio nsize)]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio node-off 1 0)
    (-sio-write-u16! sio node-off 2 0)
    (-sio-write-i32! sio node-off 4 data-bm)
    (-sio-write-i32! sio node-off 8 0)
    (write-value! sio node-off NODE_HEADER_SIZE vb)
    node-off))

(defn- make-two-value-node! [sio data-bm vb1 vb2]
  (let [vlen1 (bytes-length vb1)
        vlen2 (bytes-length vb2)
        nsize (+ NODE_HEADER_SIZE (+ 4 vlen1) (+ 4 vlen2))
        node-off (-sio-alloc! sio nsize)]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio node-off 1 0)
    (-sio-write-u16! sio node-off 2 0)
    (-sio-write-i32! sio node-off 4 data-bm)
    (-sio-write-i32! sio node-off 8 0)
    (let [next-pos (write-value! sio node-off NODE_HEADER_SIZE vb1)]
      (write-value! sio node-off next-pos vb2))
    node-off))

(defn- make-single-child-node! [sio node-bm child-off]
  (let [node-off (-sio-alloc! sio (+ NODE_HEADER_SIZE 4))]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio node-off 1 0)
    (-sio-write-u16! sio node-off 2 0)
    (-sio-write-i32! sio node-off 4 0)
    (-sio-write-i32! sio node-off 8 node-bm)
    (-sio-write-i32! sio node-off NODE_HEADER_SIZE child-off)
    node-off))

(defn- make-child-and-value-node! [sio data-bm node-bm child-off vb]
  (let [vlen (bytes-length vb)
        nsize (+ NODE_HEADER_SIZE 4 (+ 4 vlen))
        node-off (-sio-alloc! sio nsize)]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_BITMAP)
    (-sio-write-u8!  sio node-off 1 0)
    (-sio-write-u16! sio node-off 2 0)
    (-sio-write-i32! sio node-off 4 data-bm)
    (-sio-write-i32! sio node-off 8 node-bm)
    (-sio-write-i32! sio node-off NODE_HEADER_SIZE child-off)
    (write-value! sio node-off (+ NODE_HEADER_SIZE 4) vb)
    node-off))

(defn- make-collision-node! [sio vh entries]
  (let [cnt (count entries)
        vals-size (reduce (fn [acc [vb]] (+ acc 4 (bytes-length vb))) 0 entries)
        node-off (-sio-alloc! sio (+ COLLISION_HEADER_SIZE vals-size))]
    (-sio-write-u8!  sio node-off 0 NODE_TYPE_COLLISION)
    (-sio-write-u8!  sio node-off 1 cnt)
    (-sio-write-u16! sio node-off 2 0)
    (-sio-write-i32! sio node-off 4 vh)
    (-sio-write-i32! sio node-off 8 0)
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
        dst-off (-sio-alloc! sio nsize)]
    (-sio-copy-block! sio dst-off 0 src-off 0 nsize)
    (-sio-write-i32! sio dst-off (+ NODE_HEADER_SIZE (* child-idx 4)) new-child-off)
    dst-off))

;;=============================================================================
;; HAMT Find (unified)
;;=============================================================================

(defn- hamt-find [sio root-off v vh vb]
  (loop [off root-off shift 0]
    (if (== off NIL_OFFSET)
      false
      (let [nt (-sio-read-u8 sio off 0)]
        (case (int nt)
          1 (let [dbm (-sio-read-i32 sio off 4)
                  nbm (-sio-read-i32 sio off 8)
                  bit (bitpos vh shift)]
              (cond
                (has-bit? nbm bit)
                (recur (-sio-read-i32 sio off
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
          2 (let [ch (-sio-read-i32 sio off 4)]
              (if (not= ch vh)
                false
                (let [cc (-sio-read-u8 sio off 1)]
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

(defn- hamt-conj [sio root-off vh vb shift]
  (if (== root-off NIL_OFFSET)
    [(make-single-value-node! sio (bitpos vh shift) vb) true]
    (let [nt (-sio-read-u8 sio root-off 0)]
      (case (int nt)
        1 (let [data-bm (-sio-read-i32 sio root-off 4)
                node-bm (-sio-read-i32 sio root-off 8)
                bit     (bitpos vh shift)]
            (cond
              (has-bit? node-bm bit)
              (let [child-idx (get-index node-bm bit)
                    child-off (-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* child-idx 4)))
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
                        existing-vh vh]
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
                              dst-off (-sio-alloc! sio nsize)]
                          (-sio-write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
                          (-sio-write-u8!  sio dst-off 1 0)
                          (-sio-write-u16! sio dst-off 2 0)
                          (-sio-write-i32! sio dst-off 4 new-data-bm)
                          (-sio-write-i32! sio dst-off 8 new-node-bm)
                          (loop [si 0 di2 0]
                            (when (< di2 new-cc)
                              (if (== di2 new-child-idx)
                                (do (-sio-write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di2 4)) coll)
                                    (recur si (inc di2)))
                                (do (-sio-write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di2 4))
                                      (-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* si 4))))
                                    (recur (inc si) (inc di2))))))
                          (reduce (fn [p ev] (write-value! sio dst-off p ev))
                                  (values-start-off new-node-bm) val-list)
                          [dst-off true]))
                      (let [sub-shift (+ shift SHIFT_STEP)
                            existing-bit (bitpos vh sub-shift)
                            new-bit (bitpos vh sub-shift)]
                        (let [[sub _] (hamt-conj sio NIL_OFFSET vh existing-vb sub-shift)
                              [final-sub _] (hamt-conj sio sub vh vb sub-shift)
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
                                dst-off (-sio-alloc! sio nsize)]
                            (-sio-write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
                            (-sio-write-u8!  sio dst-off 1 0)
                            (-sio-write-u16! sio dst-off 2 0)
                            (-sio-write-i32! sio dst-off 4 new-data-bm)
                            (-sio-write-i32! sio dst-off 8 new-node-bm)
                            (loop [si 0 di2 0]
                              (when (< di2 new-cc)
                                (if (== di2 new-child-idx)
                                  (do (-sio-write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di2 4)) final-sub)
                                      (recur si (inc di2)))
                                  (do (-sio-write-i32! sio dst-off (+ NODE_HEADER_SIZE (* di2 4))
                                        (-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* si 4))))
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
                    dst-off (-sio-alloc! sio nsize)]
                (-sio-write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
                (-sio-write-u8!  sio dst-off 1 0)
                (-sio-write-u16! sio dst-off 2 0)
                (-sio-write-i32! sio dst-off 4 new-data-bm)
                (-sio-write-i32! sio dst-off 8 node-bm)
                (when (pos? cc)
                  (-sio-copy-block! sio dst-off NODE_HEADER_SIZE root-off NODE_HEADER_SIZE (* 4 cc)))
                (reduce (fn [p ev] (write-value! sio dst-off p ev))
                        (values-start-off node-bm) new-val-list)
                [dst-off true])))

        2 (let [node-hash (-sio-read-i32 sio root-off 4)
                cc (-sio-read-u8 sio root-off 1)]
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
    (let [nt (-sio-read-u8 sio root-off 0)]
      (case (int nt)
        1 (let [data-bm (-sio-read-i32 sio root-off 4)
                node-bm (-sio-read-i32 sio root-off 8)
                bit     (bitpos vh shift)]
            (cond
              (has-bit? node-bm bit)
              (let [child-idx (get-index node-bm bit)
                    child-off (-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* child-idx 4)))
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
                            dst-off (-sio-alloc! sio nsize)]
                        (-sio-write-u8!  sio dst-off 0 NODE_TYPE_BITMAP)
                        (-sio-write-u8!  sio dst-off 1 0)
                        (-sio-write-u16! sio dst-off 2 0)
                        (-sio-write-i32! sio dst-off 4 new-data-bm)
                        (-sio-write-i32! sio dst-off 8 node-bm)
                        (when (pos? cc)
                          (-sio-copy-block! sio dst-off NODE_HEADER_SIZE root-off NODE_HEADER_SIZE (* 4 cc)))
                        (reduce (fn [p ev] (write-value! sio dst-off p ev))
                                (values-start-off node-bm) val-list)
                        [dst-off true])))
                  [root-off false]))

              :else [root-off false]))

        2 (let [node-hash (-sio-read-i32 sio root-off 4)
                cc (-sio-read-u8 sio root-off 1)]
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

(defn- hamt-val-reduce [sio root-off f init]
  (if (== root-off NIL_OFFSET)
    init
    (let [nt (-sio-read-u8 sio root-off 0)]
      (case (int nt)
        1 (let [data-bm (-sio-read-i32 sio root-off 4)
                node-bm (-sio-read-i32 sio root-off 8)
                dc      (popcount32 data-bm)
                cc      (popcount32 node-bm)
                vs      (values-start-off node-bm)
                acc (loop [i 0 pos vs acc init]
                      (if (or (>= i dc) (reduced? acc))
                        acc
                        (let [vlen (-sio-read-i32 sio root-off pos)
                              vbs  (-sio-read-bytes sio root-off (+ pos 4) vlen)
                              v    (deserialize-val-bytes vbs)]
                          (recur (inc i) (+ pos 4 vlen) (f acc v)))))]
            (if (reduced? acc)
              acc
              (loop [i 0 acc acc]
                (if (or (>= i cc) (reduced? acc))
                  acc
                  (let [child-off (-sio-read-i32 sio root-off (+ NODE_HEADER_SIZE (* i 4)))]
                    (recur (inc i) (hamt-val-reduce sio child-off f acc)))))))

        2 (let [cc (-sio-read-u8 sio root-off 1)]
            (loop [i 0 pos COLLISION_HEADER_SIZE acc init]
              (if (or (>= i cc) (reduced? acc))
                acc
                (let [vlen (-sio-read-i32 sio root-off pos)
                      vbs  (-sio-read-bytes sio root-off (+ pos 4) vlen)
                      v    (deserialize-val-bytes vbs)]
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

(defn- write-set-header! [sio cnt root-off]
  (let [off (-sio-alloc! sio 12)]
    (-sio-write-u8!  sio off 0 EveHashSet-type-id)
    (-sio-write-u8!  sio off 1 SET_FLAG_PORTABLE_HASH)
    (-sio-write-u16! sio off 2 0)
    (-sio-write-i32! sio off SABSETROOT_CNT_OFFSET cnt)
    (-sio-write-i32! sio off SABSETROOT_ROOT_OFF_OFFSET root-off)
    off))

(defn- read-set-header [sio header-off]
  [(-sio-read-i32 sio header-off SABSETROOT_CNT_OFFSET)
   (-sio-read-i32 sio header-off SABSETROOT_ROOT_OFF_OFFSET)])

;;=============================================================================
;; EveHashSet deftype — unified via eve2-deftype macro
;;
;; Fields: cnt (int32 @ offset 4), root-off (int32 @ offset 8)
;; CLJS: deftype EveHashSet [offset__] — fields read from slab
;; CLJ:  deftype EveHashSet [cnt root-off offset__ sio _meta]
;;=============================================================================

(declare make-eve2-hash-set)

(eve2/eve2-deftype ^{:type-id 0xEE} EveHashSet [^:int32 cnt ^:int32 root-off]
  ;; --- Shared mapped protocols ---
  ICounted
  (-count [_] #?(:cljs cnt :clj (int cnt)))

  ISeqable
  (-seq [_]
    (when (pos? cnt)
      #?(:cljs (hamt-seq eve-alloc/cljs-sio root-off)
         :clj (binding [alloc/*jvm-slab-ctx* sio]
                 (hamt-seq sio root-off)))))

  ;; --- Platform-specific protocols ---
  #?@(:cljs
      [IMeta
       (-meta [_] nil)

       IWithMeta
       (-with-meta [this _] this)

       ILookup
       (-lookup [this v] (-lookup this v nil))
       (-lookup [_ v not-found]
         (let [sio eve-alloc/cljs-sio
               vb (serialize-val-bytes v)
               vh (portable-hash-bytes vb)]
           (if (hamt-find sio root-off v vh vb)
             v
             not-found)))

       ISet
       (-disjoin [this v]
         (let [sio eve-alloc/cljs-sio
               vb (serialize-val-bytes v)
               vh (portable-hash-bytes vb)
               [new-root removed?] (hamt-disj sio root-off vh vb 0)]
           (if-not removed?
             this
             (make-eve2-hash-set sio (dec cnt) new-root))))

       ICollection
       (-conj [this v]
         (let [sio eve-alloc/cljs-sio
               vb (serialize-val-bytes v)
               vh (portable-hash-bytes vb)
               [new-root added?] (hamt-conj sio root-off vh vb 0)]
           (if-not added?
             this
             (make-eve2-hash-set sio (inc cnt) new-root))))

       IEmptyableCollection
       (-empty [_]
         (make-eve2-hash-set eve-alloc/cljs-sio 0 NIL_OFFSET))

       IReduce
       (-reduce [_ f]
         (let [result (hamt-val-reduce eve-alloc/cljs-sio root-off
                        (fn [acc v]
                          (if (nil? acc) v (f acc v)))
                        nil)]
           (if (reduced? result) @result result)))
       (-reduce [_ f init]
         (let [result (hamt-val-reduce eve-alloc/cljs-sio root-off f init)]
           (if (reduced? result) @result result)))

       IEquiv
       (-equiv [this other]
         (and (set? other)
              (== cnt (count other))
              (every? (fn [v]
                        (let [vb (serialize-val-bytes v)
                              vh (portable-hash-bytes vb)]
                          (hamt-find eve-alloc/cljs-sio root-off v vh vb)))
                      other)))

       IHash
       (-hash [this]
         (hash-unordered-coll this))

       IFn
       (-invoke [this v] (-lookup this v nil))
       (-invoke [this v nf] (-lookup this v nf))

       IPrintWithWriter
       (-pr-writer [this writer _opts]
         (-write writer "#{")
         (let [s (seq this)]
           (when s
             (loop [[v & more] s first? true]
               (when-not first? (-write writer " "))
               (-write writer (pr-str v))
               (when more (recur more false)))))
         (-write writer "}"))

       d/IDirectSerialize
       (-direct-serialize [this]
         (ser/encode-sab-pointer ser/FAST_TAG_SAB_SET (.-offset__ this)))

       d/ISabStorable
       (-sab-tag [_] :hash-set)
       (-sab-encode [this _] (d/-direct-serialize this))
       (-sab-dispose [_ _] nil)

       d/IsEve
       (-eve? [_] true)

       d/IEveRoot
       (-root-header-off [this] (.-offset__ this))]

      :clj
      [clojure.lang.IMeta
       (meta [_] _meta)

       clojure.lang.IObj
       (withMeta [_ new-meta]
         (EveHashSet. cnt root-off offset__ sio new-meta))

       clojure.lang.IPersistentSet
       (disjoin [this v]
         (binding [alloc/*jvm-slab-ctx* sio]
           (let [^bytes vb (serialize-val-bytes v)
                 vh (portable-hash-bytes vb)
                 [new-root removed?] (hamt-disj sio root-off vh vb 0)]
             (if-not removed?
               this
               (make-eve2-hash-set sio (dec cnt) new-root)))))
       (contains [_ v]
         (binding [alloc/*jvm-slab-ctx* sio]
           (let [^bytes vb (serialize-val-bytes v)
                 vh (portable-hash-bytes vb)]
             (hamt-find sio root-off v vh vb))))
       (get [_ v]
         (binding [alloc/*jvm-slab-ctx* sio]
           (let [^bytes vb (serialize-val-bytes v)
                 vh (portable-hash-bytes vb)]
             (when (hamt-find sio root-off v vh vb) v))))

       clojure.lang.Seqable
       (seq [_]
         (binding [alloc/*jvm-slab-ctx* sio]
           (when (pos? cnt)
             (hamt-seq sio root-off))))

       clojure.lang.Counted
       (count [_] (int cnt))

       clojure.lang.IPersistentCollection
       (empty [_]
         (binding [alloc/*jvm-slab-ctx* sio]
           (make-eve2-hash-set sio 0 NIL_OFFSET)))
       (cons [this v]
         (binding [alloc/*jvm-slab-ctx* sio]
           (let [^bytes vb (serialize-val-bytes v)
                 vh (portable-hash-bytes vb)
                 [new-root added?] (hamt-conj sio root-off vh vb 0)]
             (if-not added?
               this
               (make-eve2-hash-set sio (inc cnt) new-root)))))
       (equiv [this other]
         (cond
           (not (instance? java.util.Set other)) false
           (not= cnt (.size ^java.util.Set other)) false
           :else (every? #(.contains ^java.util.Set other %) (.seq this))))

       clojure.lang.IReduceInit
       (reduce [_ f init]
         (binding [alloc/*jvm-slab-ctx* sio]
           (hamt-val-reduce sio root-off f init)))

       clojure.lang.IReduce
       (reduce [this f]
         (let [s (.seq this)]
           (if s (reduce f s) (f))))

       clojure.lang.IFn
       (invoke [this v] (.get this v))
       (invoke [this v nf]
         (binding [alloc/*jvm-slab-ctx* sio]
           (let [^bytes vb (serialize-val-bytes v)
                 vh (portable-hash-bytes vb)]
             (if (hamt-find sio root-off v vh vb) v nf))))

       java.lang.Iterable
       (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

       clojure.lang.IHashEq
       (hasheq [this]
         (clojure.lang.Murmur3/hashUnordered this))

       d/IEveRoot
       (-root-header-off [_] offset__)

       java.lang.Object
       (toString [this] (pr-str this))
       (equals [this other]
         (cond
           (identical? this other) true
           (not (instance? java.util.Set other)) false
           :else
           (and (== cnt (.size ^java.util.Set other))
                (every? #(.contains ^java.util.Set other %) (.seq this)))))
       (hashCode [this]
         (reduce + 0 (map hash (.seq this))))]))

;;=============================================================================
;; Constructors
;;=============================================================================

(defn- make-eve2-hash-set
  "Internal constructor."
  [sio cnt root-off]
  (let [hdr (write-set-header! sio cnt root-off)]
    #?(:cljs (EveHashSet. hdr)
       :clj  (EveHashSet. cnt root-off hdr sio nil))))

(defn eve2-hash-set-from-header
  "Reconstruct an EveHashSet from a header offset."
  [sio header-off]
  (let [[cnt root-off] (read-set-header sio header-off)]
    #?(:cljs (EveHashSet. header-off)
       :clj  (EveHashSet. cnt root-off header-off sio nil))))

(defn empty-hash-set
  "Create an empty Eve2 hash set."
  []
  (let [sio (get-sio)]
    (make-eve2-hash-set sio 0 NIL_OFFSET)))

(defn hash-set
  "Create an Eve2 hash set from values."
  [& vals]
  (reduce conj (empty-hash-set) vals))

;;=============================================================================
;; Registration
;;=============================================================================

#?(:clj
   (do
     (register-jvm-collection-writer! :set
       (fn [sio serialize-val coll]
         (let [entries (mapv (fn [v]
                               (let [^bytes vb (value->eve-bytes v)
                                     vh (portable-hash-bytes vb)]
                                 [vh vb]))
                             coll)]
           (if (empty? entries)
             (write-set-header! sio 0 NIL_OFFSET)
             (let [root-off (reduce (fn [root [vh vb]]
                                      (let [[new-root _] (hamt-conj sio root vh vb 0)]
                                        new-root))
                                    NIL_OFFSET entries)]
               (write-set-header! sio (count coll) root-off))))))

     (ser/register-jvm-type-constructor! EveHashSet-type-id
       (fn [header-off]
         (eve2-hash-set-from-header alloc/*jvm-slab-ctx* header-off)))

     (defmethod print-method EveHashSet [s ^java.io.Writer w]
       (#'clojure.core/print-sequential "#{" #'clojure.core/pr-on " " "}" (seq s) w))))
