(ns eve.set
  "Eve persistent HAMT set — unified CLJ/CLJS implementation.

   Uses ISlabIO protocol for all memory access.
   Sets store values only (no key-hash arrays like maps).
   Collision nodes use type 2 (distinct from map collision type 3).

   Uses eve/deftype macro: CLJ protocol names, auto-translated on CLJS."
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
                                  bitpos has-bit? get-index]]
   [eve.platform :as p]
   #?@(:clj  [[eve.deftype-proto.macros :as eve]
              [eve.mem :as mem :refer [eve-bytes->value value+sio->eve-bytes
                                       register-jvm-collection-writer!]]]))
  #?(:cljs (:require-macros [eve.deftype-proto.macros :as eve])))

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

(defn- serialize-val-bytes [v]
  #?(:cljs (ser/serialize-element v)
     :clj  (value+sio->eve-bytes v)))

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
                            existing-vh (portable-hash-bytes existing-vb)
                            existing-bit (bitpos existing-vh sub-shift)
                            new-bit (bitpos vh sub-shift)]
                        (let [[sub _] (hamt-conj sio NIL_OFFSET existing-vh existing-vb sub-shift)
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
;; HAMT node freeing helpers (via ISlabIO)
;;=============================================================================

(declare free-hamt-node!)

(defn- free-hamt-node!
  "Recursively free a HAMT node and all its children via ISlabIO."
  [sio slab-off]
  (when (not= slab-off NIL_OFFSET)
    (let [node-type (-sio-read-u8 sio slab-off 0)]
      (case (int node-type)
        ;; Bitmap node — free children first
        1 (let [node-bm (-sio-read-i32 sio slab-off 8)
                child-count (popcount32 node-bm)]
            (dotimes [i child-count]
              (let [child-off (-sio-read-i32 sio slab-off (+ NODE_HEADER_SIZE (* i 4)))]
                (free-hamt-node! sio child-off)))
            (-sio-free! sio slab-off))
        ;; Collision node — no children
        2 (-sio-free! sio slab-off)
        ;; Unknown
        (-sio-free! sio slab-off)))))

;;=============================================================================
;; Disposal & Retirement
;;=============================================================================

(defn dispose!
  "Dispose an EveHashSet, freeing its entire HAMT tree and header block.
   Call this when the set is no longer needed to reclaim slab memory.

   WARNING: After disposal, the set must not be used."
  [hash-set]
  (let [sio (#?(:cljs (.-sio__ ^js hash-set) :clj (.sio__ hash-set)))
        header-off (#?(:cljs (.-offset__ ^js hash-set) :clj (.offset__ hash-set)))
        root-off (-sio-read-i32 sio header-off SABSETROOT_ROOT_OFF_OFFSET)]
    (when (not= root-off NIL_OFFSET)
      (free-hamt-node! sio root-off))
    (when (not= header-off NIL_OFFSET)
      (-sio-free! sio header-off))))

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
        (-sio-free! sio old-off)
        (let [old-type (-sio-read-u8 sio old-off 0)]
          (when (== old-type NODE_TYPE_BITMAP)
            (let [bit-pos (bit-and (unsigned-bit-shift-right vh sh) MASK)
                  old-node-bm (-sio-read-i32 sio old-off 8)
                  new-type (when (not= new-off NIL_OFFSET) (-sio-read-u8 sio new-off 0))
                  new-node-bm (when (and new-type (== new-type NODE_TYPE_BITMAP))
                                (-sio-read-i32 sio new-off 8))
                  old-bit (bit-shift-left 1 bit-pos)]
              (when (and (not (zero? (bit-and old-node-bm old-bit)))
                         new-node-bm
                         (not (zero? (bit-and new-node-bm old-bit))))
                (let [old-child-idx (popcount32 (bit-and old-node-bm (dec old-bit)))
                      new-child-idx (popcount32 (bit-and new-node-bm (dec old-bit)))
                      old-child (-sio-read-i32 sio old-off (+ NODE_HEADER_SIZE (* old-child-idx 4)))
                      new-child (-sio-read-i32 sio new-off (+ NODE_HEADER_SIZE (* new-child-idx 4)))]
                  (recur old-child new-child (+ sh SHIFT_STEP)))))))))))

(defn retire-tree-diff!
  "Full tree diff: walk old and new HAMT trees in parallel, freeing all
   old nodes that differ from the new tree."
  [sio old-root new-root]
  (when (and (not= old-root NIL_OFFSET) (not= old-root new-root))
    (letfn [(walk [old-off new-off]
              (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
                (-sio-free! sio old-off)
                (let [old-type (-sio-read-u8 sio old-off 0)]
                  (when (== old-type NODE_TYPE_BITMAP)
                    (let [old-node-bm (-sio-read-i32 sio old-off 8)
                          new-type (when (not= new-off NIL_OFFSET) (-sio-read-u8 sio new-off 0))
                          new-node-bm (when (and new-type (== new-type NODE_TYPE_BITMAP))
                                        (-sio-read-i32 sio new-off 8))]
                      (loop [remaining old-node-bm
                             old-idx 0]
                        (when (not (zero? remaining))
                          (let [bit (bit-and remaining (- remaining))
                                old-child (-sio-read-i32 sio old-off (+ NODE_HEADER_SIZE (* old-idx 4)))
                                new-child (if (and new-node-bm (not (zero? (bit-and new-node-bm bit))))
                                            (let [new-idx (popcount32 (bit-and new-node-bm (dec bit)))]
                                              (-sio-read-i32 sio new-off (+ NODE_HEADER_SIZE (* new-idx 4))))
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

(eve/deftype EveHashSet [^:int32 cnt ^:int32 root-off]
  clojure.lang.Counted
  (count [_] #?(:cljs cnt :clj (int cnt)))

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
          vb (serialize-val-bytes v)
          vh (portable-hash-bytes vb)]
      (if (hamt-find sio root-off v vh vb) v nil)))
  (valAt [_ v not-found]
    (let [sio sio__
          vb (serialize-val-bytes v)
          vh (portable-hash-bytes vb)]
      (if (hamt-find sio root-off v vh vb)
        v
        not-found)))

  clojure.lang.IPersistentSet
  (disjoin [this v]
    (let [sio sio__
          vb (serialize-val-bytes v)
          vh (portable-hash-bytes vb)
          [new-root removed?] (hamt-disj sio root-off vh vb 0)]
      (if-not removed?
        this
        (make-hash-set sio (dec cnt) new-root))))
  ;; get is CLJ-only; on CLJS, ILookup/valAt handles (get set v)
  #?@(:clj
      [(get [_ v]
         (let [sio sio__
               vb (serialize-val-bytes v)
               vh (portable-hash-bytes vb)]
           (when (hamt-find sio root-off v vh vb) v)))])

  clojure.lang.IPersistentCollection
  (cons [this v]
    (let [sio sio__
          vb (serialize-val-bytes v)
          vh (portable-hash-bytes vb)
          [new-root added?] (hamt-conj sio root-off vh vb 0)]
      (if-not added?
        this
        (make-hash-set sio (inc cnt) new-root))))
  (empty [_]
    (make-hash-set sio__ 0 NIL_OFFSET))
  (equiv [this other]
    #?(:cljs (and (set? other)
                  (== cnt (count other))
                  (every? (fn [v]
                            (let [vb (serialize-val-bytes v)
                                  vh (portable-hash-bytes vb)]
                              (hamt-find sio__ root-off v vh vb)))
                          other))
       :clj (cond
               (not (instance? java.util.Set other)) false
               (not= cnt (.size ^java.util.Set other)) false
               :else (every? #(.contains ^java.util.Set other %) (.seq this)))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (unreduced (hamt-val-reduce sio__ root-off f init)))

  clojure.lang.IHashEq
  (hasheq [this] (p/hash-unordered this))

  clojure.lang.IFn
  (invoke [this v]
    (let [vb (serialize-val-bytes v)
          vh (portable-hash-bytes vb)]
      (when (hamt-find sio__ root-off v vh vb) v)))
  (invoke [this v nf]
    (let [vb (serialize-val-bytes v)
          vh (portable-hash-bytes vb)]
      (if (hamt-find sio__ root-off v vh vb) v nf)))

  #?@(:cljs [IPrintWithWriter
             (-pr-writer [this writer _opts]
               (do
                 (-write writer "#{")
                 (let [s (seq this)]
                   (when s
                     (loop [[v & more] s first? true]
                       (when-not first? (-write writer " "))
                       (-write writer (pr-str v))
                       (when more (recur more false)))))
                 (-write writer "}")))])

  d/IDirectSerialize
  (-direct-serialize [this] offset__)

  d/ISabStorable
  (-sab-tag [_] :hash-set)
  (-sab-encode [this _] #?(:cljs (ser/encode-eve-pointer this) :clj offset__))
  (-sab-dispose [_ _] nil)

  d/IsEve
  (-eve? [_] true)

  d/IEveRoot
  (-root-header-off [this] offset__)

  ;; java.lang.Object in main body so macro sees it and skips default IPrintWithWriter.
  ;; Proto map marks it :clj-only? so CLJS translation strips it automatically.
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
    (reduce + 0 (map hash (.seq this))))

  ;; --- CLJ-only interfaces (no CLJS equivalent) ---
  #?@(:clj
      [clojure.lang.IPersistentSet
       ;; contains has no CLJS protocol equivalent
       (contains [_ v]
         (let [^bytes vb (serialize-val-bytes v)
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
       (clear [_] (throw (UnsupportedOperationException.)))]))

;;=============================================================================
;; CLJS-only: 2-arity IReduce (reduce without init)
;;=============================================================================

#?(:cljs
   (extend-type EveHashSet
     IReduce
     (-reduce
       ([coll f]
        (let [s (seq coll)]
          (if s
            (reduce f (first s) (rest s))
            (f))))
       ([coll f start]
        (let [sio (.-sio__ coll)
              root-off (-sio-read-i32 sio (.-offset__ coll) SABSETROOT_ROOT_OFF_OFFSET)
              result (hamt-val-reduce sio root-off f start)]
          (if (reduced? result) @result result))))))

;;=============================================================================
;; ISabRetirable
;;=============================================================================

(extend-type EveHashSet
  d/ISabRetirable
  (-sab-retire-diff! [this new-value _slab-env mode]
    (let [sio (#?(:cljs .-sio__ :clj .sio__) this)
          old-root (-sio-read-i32 sio (#?(:cljs .-offset__ :clj .offset__) this) SABSETROOT_ROOT_OFF_OFFSET)]
      (if (instance? EveHashSet new-value)
        (let [new-root-off (-sio-read-i32 sio (#?(:cljs .-offset__ :clj .offset__) new-value) SABSETROOT_ROOT_OFF_OFFSET)]
          (retire-tree-diff! sio old-root new-root-off))
        (when (not= old-root NIL_OFFSET)
          (free-hamt-node! sio old-root)))
      ;; Free the header block
      (when (not= (#?(:cljs .-offset__ :clj .offset__) this) NIL_OFFSET)
        (-sio-free! sio (#?(:cljs .-offset__ :clj .offset__) this))))))

;;=============================================================================
;; Constructors
;;=============================================================================

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
   0-arity: uses platform default sio.  1-arity: explicit sio."
  ([]  (empty-hash-set #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*)))
  ([sio] (make-hash-set sio 0 NIL_OFFSET)))

(defn hash-set
  "Create an Eve hash set from values.
   If first arg satisfies ISlabIO, uses it as sio.
   Otherwise uses platform default sio and all args as values."
  [& args]
  (let [default-sio #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*)
        [sio vals] (if (and (seq args) (satisfies? ISlabIO (first args)))
                     [(first args) (rest args)]
                     [default-sio args])]
    (reduce conj (empty-hash-set sio) vals)))

;;=============================================================================
;; Registration
;;=============================================================================

#?(:clj
   (do
     (register-jvm-collection-writer! :set
       (fn [sio serialize-val coll]
         (let [entries (mapv (fn [v]
                               (let [^bytes vb (value+sio->eve-bytes v)
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

     ;; Backward-compat JVM aliases
     (defn jvm-write-set!
       "Serialize a Clojure set to slab. Returns header offset.
        Backward-compat alias for the registered :set writer."
       [sio serialize-val coll]
       (mem/jvm-write-collection! :set sio coll))

     (defn jvm-eve-hash-set-from-offset
       "Reconstruct an EveHashSet from a header offset.
        Backward-compat alias. coll-factory arg is ignored (registry-based)."
       ([sio header-off] (hash-set-from-header sio header-off))
       ([sio header-off _coll-factory] (hash-set-from-header sio header-off)))))

(defn- into-hash-set
  "Build an EveHashSet from a Clojure set."
  ([coll] (into-hash-set #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*) coll))
  ([sio coll] (reduce conj (empty-hash-set sio) coll)))

;; Type constructor + disposer + builder registrations
(eve/register-eve-type!
  {:fast-tag    ser/FAST_TAG_SAB_SET
   :type-id     EveHashSet-type-id
   :from-header hash-set-from-header
   :dispose     dispose!
   :builder-pred set?
   :builder-ctor into-hash-set
   :print-fn    #?(:clj (fn [] (defmethod print-method EveHashSet [s ^java.io.Writer w]
                                  (#'clojure.core/print-sequential "#{" #'clojure.core/pr-on " " "}" (seq s) w)))
                   :cljs nil)})


;; No-op pool stub — pool system removed, kept for backward compat
#?(:cljs (defn reset-pools! [] nil))
