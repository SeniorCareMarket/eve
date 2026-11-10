(ns eve.list
  "Eve persistent list — unified CLJ/CLJS implementation.

   Singly-linked persistent (immutable) list using ISlabIO for memory access.
   Node layout: [next-off:i32 @ 0][val-len:u32 @ 4][val-bytes... @ 8]
   Header layout: [type-id:u8 | pad:3 | cnt:i32 | head-off:i32] = 12 bytes

   Uses eve/deftype macro: CLJ protocol names, sio threaded everywhere."
  ;; :clje — callees' ^long return tags resolve in the CALLER's ns on clojerl; take eve.host's `long`.
  (:refer-clojure :exclude [alength volatile! vswap! transient persistent! conj! bytes #?@(:clje [long])])
  (:require
   [eve.host :refer [field alength volatile! vswap! transient persistent! conj! bytes #?@(:clje [long])]]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc
    :refer [NIL_OFFSET]]
   [eve.deftype-proto.data :as d]
   [eve.core :as ecore]
   [eve.deftype-proto.serialize :as ser]
   [eve.platform :as p]
   [eve.deftype-proto.macros :as eve]
   [eve.codec :as codec]
   [eve.digest :as dg]))

;;=============================================================================
;; Shared Constants
;;=============================================================================

(def EveList-type-id 0x13)
(def SabList-type-id EveList-type-id) ;; backward compat
(def ^:const LIST_CNT_OFFSET 4)
(def ^:const LIST_HEAD_OFFSET 8)
(def ^:const LIST_HEADER_SIZE 12)

;; Node layout offsets
(def ^:const NODE_NEXT_OFFSET 0)
(def ^:const NODE_VLEN_OFFSET 4)
(def ^:const NODE_VDATA_OFFSET 8)

;;=============================================================================
;; Platform-specific helpers
;;=============================================================================

(defn- serialize-element-bytes [sio v]
  (codec/encode-value sio v))

(defn- deserialize-element-bytes
  [val-bytes sio]
  (codec/decode sio val-bytes))

(defn- default-sio
  "The slab I/O a constructor given none allocates in: the running swap!'s
   domain, else this process's own (eve.deftype-proto.alloc/current-sio)."
  []
  (alloc/current-sio))

(defn- bytes-length [^bytes ba]
  (alength ba))

;;=============================================================================
;; Node operations (via ISlabIO)
;;=============================================================================

(defn- alloc-list-node!
  "Allocate and write a list node. Returns slab offset."
  [sio next-off val-bytes]
  (let [vlen (bytes-length val-bytes)
        node-off (slab/alloc! sio (+ NODE_VDATA_OFFSET vlen))]
    (slab/write-i32! sio node-off NODE_NEXT_OFFSET next-off)
    (slab/write-i32! sio node-off NODE_VLEN_OFFSET vlen)
    (when (pos? vlen)
      (slab/write-bytes! sio node-off NODE_VDATA_OFFSET val-bytes))
    node-off))

(defn read-node-value
  "Read and deserialize the value from a list node."
  [sio node-off]
  (let [vlen (slab/read-i32 sio node-off NODE_VLEN_OFFSET)
        vbs  (slab/read-bytes sio node-off NODE_VDATA_OFFSET vlen)]
    (deserialize-element-bytes vbs sio)))

(defn read-node-next
  "Read the next pointer from a list node."
  [sio node-off]
  (slab/read-i32 sio node-off NODE_NEXT_OFFSET))

;;=============================================================================
;; Header read/write
;;=============================================================================

(defn write-list-header!
  "Allocate and write a list header. Returns slab offset."
  [sio cnt head-off]
  (let [hdr (slab/alloc! sio LIST_HEADER_SIZE)]
    (slab/write-u8!  sio hdr 0 SabList-type-id)
    (slab/write-i32! sio hdr LIST_CNT_OFFSET cnt)
    (slab/write-i32! sio hdr LIST_HEAD_OFFSET head-off)
    hdr))

(defn read-list-header
  "Read cnt and head-off from header. Returns [cnt head-off]."
  [sio hdr-off]
  [(slab/read-i32 sio hdr-off LIST_CNT_OFFSET)
   (slab/read-i32 sio hdr-off LIST_HEAD_OFFSET)])

;;=============================================================================
;; EveList deftype — unified via eve/deftype macro
;;
;; Fields: cnt (int32 @ offset 4), head-off (int32 @ offset 8)
;; Both platforms: deftype EveList [sio__ offset__]
;;=============================================================================

(declare make-list)
(declare eve-list?)

(defn- root-digest-verdict
  "Merkle Phase 4 (§2b) digest seam for equiv — ported from canonical eve
   (commit 366e9b2). When
   collection roots carry cached digests (Phase 3's eve layout — an open
   fork: lists have no root header block at all today, see Phase 3's
   STATUS), this answers :equal or :unequal for two EveList roots whose
   digests decide, and :walk for everything else (no digest on either
   root, a root of any other type, any doubt). equiv calls it only after
   its own partition gates (sequential? + count) have passed, and falls
   back to the structural walk on :walk. Wired to the Phase 3 eve cache
   (eve.digest, layout B-prime) 2026-10-25: the gate below answers :walk
   unless BOTH roots are EveList roots (an EveVector included in the
   exclusions: list/vec equality is the walk's, though both are
   sequential), and eve.digest answers :walk for any root whose content
   has no digest. (The Phase 4 note above that lists have no root header
   block was wrong: an EveList's offset__ is its header block, with a
   descriptor row like every root's.)"
  [a b]
  (if (eve-list? b)
    (dg/digest-verdict (d/-eve-sio a) (d/-root-header-off a) a
                       (d/-eve-sio b) (d/-root-header-off b) b)
    :walk))

(eve/deftype EveList [^:int32 cnt ^:int32 head-off]

  clojure.lang.Sequential

  clojure.lang.Counted
  (count [_] (int cnt))

  clojure.lang.Seqable
  (seq [this] (when (pos? cnt) this))

  clojure.lang.IMeta
  (meta [_] nil)

  clojure.lang.IObj
  (withMeta [this m] this)

  clojure.lang.IPersistentStack
  (peek [_]
    (when (pos? cnt)
      (read-node-value sio__ head-off)))
  (pop [_]
    (if (zero? cnt)
      (p/throw! "Can't pop empty list")
      (let [next-off (read-node-next sio__ head-off)]
        (make-list sio__ (dec cnt) next-off))))

  clojure.lang.IPersistentCollection
  (cons [_ v]
    (let [vb (serialize-element-bytes sio__ v)
          node-off (alloc-list-node! sio__ head-off vb)]
      (make-list sio__ (inc cnt) node-off)))
  (empty [_]
    (make-list sio__ 0 NIL_OFFSET))
  (equiv [this other]
    (cond
      (not (sequential? other)) false
      (not= cnt (count other)) false
      :else
      (let [verdict (root-digest-verdict this other)]
        (cond
          (= verdict :equal) true
          (= verdict :unequal) false
          :else
          (loop [off head-off i 0 os (clojure.core/seq other)]
            (if (or (>= i cnt) (nil? os))
              true
              (let [v (read-node-value sio__ off)]
                (if (= v (first os))
                  (recur (read-node-next sio__ off) (inc i) (clojure.core/next os))
                  false))))))))

  clojure.lang.IHashEq
  (hasheq [this] (dg/root-hash sio__ offset__ (fn [] (p/hash-ordered this))))

  clojure.lang.ISeq
  (first [_]
    (when (pos? cnt)
      (read-node-value sio__ head-off)))
  (more [_]
    (if (<= cnt 1)
      (make-list sio__ 0 NIL_OFFSET)
      (let [next-off (read-node-next sio__ head-off)]
        (make-list sio__ (dec cnt) next-off))))
  (next [_]
    (when (> cnt 1)
      (let [next-off (read-node-next sio__ head-off)]
        (make-list sio__ (dec cnt) next-off))))

  clojure.lang.IFn
  (invoke [_ n]
    (if (and (integer? n) (>= n 0) (< n cnt))
      (loop [off head-off i 0]
        (if (== i n)
          (read-node-value sio__ off)
          (recur (read-node-next sio__ off) (inc i))))
      nil))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [off head-off i 0 acc init]
      (if (or (== off NIL_OFFSET) (>= i cnt) (reduced? acc))
        (unreduced acc)
        (let [v (read-node-value sio__ off)
              nxt (read-node-next sio__ off)]
          (recur nxt (inc i) (f acc v))))))

  clojure.lang.IReduce
  (reduce [_ f]
    (if (zero? cnt)
      (f)
      (loop [off (read-node-next sio__ head-off) i 1
             acc (read-node-value sio__ head-off)]
        (if (or (== off NIL_OFFSET) (>= i cnt))
          acc
          (let [v (read-node-value sio__ off)
                result (f acc v)]
            (if (reduced? result) @result
              (recur (read-node-next sio__ off) (inc i) result)))))))

  d/IDirectSerialize
  (d/-direct-serialize [this] offset__)

  d/ISabStorable
  (d/-sab-tag [_] :eve-list)
  (d/-sab-encode [this _] (codec/encode-eve-pointer this))
  (d/-sab-dispose [_ _] nil)

  d/IsEve
  (d/-eve? [_] true)

  d/IEveRoot
  (d/-root-header-off [this] offset__)

  d/IEveSio
  (d/-eve-sio [_] sio__)

  ;; what a deref of an atom holding it answers: a plain Clojure list
  ;; (see EveHashMap's IPlain note: eve->clj cannot rely on list? here).
  d/IPlain
  (-plain [this] (apply list (map d/eve->clj (ecore/seq this))))

  clojure.lang.IPersistentList

  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  java.lang.Object
  (toString [this] (pr-str this))
  (equals [this other]
    (cond
      (identical? this other) true
      (not (sequential? other)) false
      :else (.equiv this other)))
  (hashCode [this]
    (clojure.lang.Murmur3/hashOrdered this)))

;;=============================================================================
;; Disposal & retirement
;;=============================================================================

(do
(defn- free-list-chain!
  "Free all nodes in a list linked-list chain via ISlabIO."
  [sio head-off]
  (loop [node-off head-off]
    (when (not= node-off NIL_OFFSET)
      (let [next-off (read-node-next sio node-off)]
        (slab/free! sio node-off)
        (recur next-off)))))

(defn dispose!
  "Dispose an EveList, freeing all nodes and header.
   Call this when the list is no longer needed to reclaim slab memory.

   WARNING: After disposal, the list must not be used."
  [eve-list]
  (when (instance? EveList eve-list)
    (let [sio (field ^EveList eve-list sio__)
          hdr (field ^EveList eve-list offset__)
          hd  (when hdr (slab/read-i32 sio hdr LIST_HEAD_OFFSET))]
      (when (and hd (not= hd NIL_OFFSET))
        (free-list-chain! sio hd))
      (when (and hdr (not= hdr NIL_OFFSET))
        (slab/free! sio hdr)))))

(defn retire-replaced-chain!
  "After an atom swap that replaced a list, retire old nodes not shared
   by the new list."
  ([sio old-head new-head]
   (retire-replaced-chain! sio old-head new-head :free))
  ([sio old-head new-head mode]
   (when (and (not= old-head NIL_OFFSET) (not= old-head new-head))
     (loop [node-off old-head]
       (when (and (not= node-off NIL_OFFSET) (not= node-off new-head))
         (let [next-off (read-node-next sio node-off)]
           (slab/free! sio node-off)
           (recur next-off)))))))

;; ISabRetirable
(extend-type EveList
  d/ISabRetirable
  (-sab-retire-diff! [this new-value _slab-env mode]
    (let [sio      (field this sio__)
          hdr      (field this offset__)
          old-head (slab/read-i32 sio hdr LIST_HEAD_OFFSET)
          new-head (when (instance? EveList new-value)
                     (slab/read-i32 sio (d/-root-header-off new-value) LIST_HEAD_OFFSET))]
      (if new-head
        (retire-replaced-chain! sio old-head new-head mode)
        (dispose! this)))))
)

;; The retire set of one list replaced by another — portable (never reached
;; on bb, where eve-list? is false).
(defn collect-retire-diff-offsets
  "The blocks old-list holds that new-value does not: its header, and the
   cells of its chain before the suffix the new list shares. Two lists share a
   suffix at the same distance from their ends, so the walk aligns the two
   chains by their counts and steps them together to the first cell they
   share (a conj shares the whole old chain, a pop all but its head). It
   walked the old chain to the new list's head cell, which a conj never
   reaches: every old cell was retired while the new list held it, and a list
   kept in an atom read back other values' bytes once its blocks were reused
   (2026-10-21 ET)."
  [old-list new-value]
  (let [sio (d/-eve-sio old-list)
        hdr (d/-root-header-off old-list)
        [old-cnt old-head] (read-list-header sio hdr)
        [new-cnt new-head] (if (eve-list? new-value)
                             (read-list-header sio (d/-root-header-off new-value))
                             [0 NIL_OFFSET])
        skip (fn [off n] (if (pos? n) (recur (read-node-next sio off) (dec n)) off))
        ;; the old cells past the new list's length are its own; then the chains in step
        result (loop [o old-head n (- old-cnt new-cnt) out (transient [])]
                 (if (pos? n)
                   (recur (read-node-next sio o) (dec n) (conj! out o))
                   (loop [o o m (skip new-head (- new-cnt old-cnt)) out out]
                     (if (or (== o NIL_OFFSET) (== o m))
                       out
                       (recur (read-node-next sio o) (read-node-next sio m) (conj! out o))))))]
    (persistent! (cond-> result
                   (and hdr (not= hdr NIL_OFFSET)) (conj! hdr)))))

;;=============================================================================
;; Constructors
;;=============================================================================

(do
(defn- make-list
  "Internal constructor: allocate header, create EveList."
  [sio cnt head-off]
  (let [hdr (write-list-header! sio cnt head-off)]
    (EveList. sio hdr)))

(defn list-from-header
  "Reconstruct an EveList from an existing header offset."
  [sio header-off]
  (EveList. sio header-off))

(defn empty-list
  "Create an empty Eve list.
   0-arity: uses platform default sio (fresh heap domain on JVM).
   1-arity: explicit sio."
  ([] (empty-list (default-sio)))
  ([sio] (make-list sio 0 NIL_OFFSET)))

(defn eve-list
  "Create an Eve list from a collection (reversed, as cons-list)."
  ([coll] (eve-list (default-sio) coll))
  ([sio coll]
   (reduce ecore/conj (empty-list sio) (reverse coll))))

;; Backward-compat aliases
(def empty-sab-list empty-list)
(def sab-list eve-list)
)

(defn eve-list?
  "True for a slab-backed EveList. On babashka a list read from eve memory is
   the host's own list (eve.types/reader-of), so one read there is not one."
  [x]
  (instance? EveList x))

;;=============================================================================
;; Registration
;;=============================================================================

;; Collection writer: a host collection written straight into the slab, for a
;; codec that writes one directly (eve.codec)
(defn- -write-list-coll!
  "Serialize a Clojure list/seq to slab as an Eve linked list. Returns header offset.
   sio is explicit (first parameter)."
  [sio _serialize-elem coll]
  (let [elems (vec coll)
        cnt (count elems)]
    (if (zero? cnt)
      (write-list-header! sio 0 NIL_OFFSET)
      ;; back to front, each node pointing at the one after it — by index: glojure's reduce has no coll-reduce
      ;; method for a vector's rseq (main's tally test, the first to store a list there)
      (let [head-off (loop [i (dec cnt) next-off NIL_OFFSET]
                       (if (neg? i)
                         next-off
                         (recur (dec i) (alloc-list-node! sio next-off (codec/encode-value sio (nth elems i))))))]
        (write-list-header! sio cnt head-off)))))

;; Type constructor + disposer + builder + collection writer registrations
(defn- list->host-value
  "A list in eve memory as the host's own list: what a host whose types
   cannot stand in for its collections reads (eve.types/reader-of)."
  [sio header-off]
  (let [[cnt head-off] (read-list-header sio header-off)]
    (loop [off head-off i 0 acc (transient [])]
      (if (or (>= i cnt) (== off NIL_OFFSET))
        (apply list (persistent! acc))
        (recur (read-node-next sio off)
               (inc i)
               (conj! acc (read-node-value sio off)))))))

(eve/register-eve-type!
    {:fast-tag     ser/FAST_TAG_SAB_LIST
     :type-id      EveList-type-id
     :from-header  list-from-header
     :host-value   list->host-value
     :dispose      dispose!
     :builder-pred seq?
     :builder-ctor eve-list
     :coll-writer  {:tag :list :fn -write-list-coll!}
     :type         EveList
     :print        {:open "(" :close ")"}})

;; Backward-compat aliases
(do
     (defn jvm-write-list!
       "Serialize a Clojure list/seq to slab. Returns header offset.
        Backward-compat alias for the registered :list writer."
       [sio serialize-val coll]
       (codec/write-collection! :list sio coll))

     (defn jvm-sab-list-from-offset
       "Reconstruct an EveList from a header offset.
        Backward-compat alias. coll-factory arg is ignored (registry-based)."
       ([sio header-off] (list-from-header sio header-off))
       ([sio header-off _coll-factory] (list-from-header sio header-off)))

     (def SabList EveList))

;; No-op pool stub — pool system removed, kept for backward compat
(defn reset-pools! [] nil)
