(ns eve.list
  "Eve persistent list — unified CLJ/CLJS implementation.

   Singly-linked persistent (immutable) list using ISlabIO for memory access.
   Node layout: [next-off:i32 @ 0][val-len:u32 @ 4][val-bytes... @ 8]
   Header layout: [type-id:u8 | pad:3 | cnt:i32 | head-off:i32] = 12 bytes

   Uses eve3/deftype macro: CLJ protocol names, sio threaded everywhere."
  (:require
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-read-i32
            -sio-read-bytes -sio-write-u8! -sio-write-i32!
            -sio-write-bytes! -sio-alloc! -sio-free!
            NIL_OFFSET]]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   #?@(:clj  [[eve.deftype-proto.eve3-deftype :as eve3]
              [eve.mem :as mem :refer [eve-bytes->value value+sio->eve-bytes
                                       register-jvm-collection-writer!]]]))
  #?(:cljs (:require-macros [eve.deftype-proto.eve3-deftype :as eve3])))

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

(defn- serialize-element-bytes [v]
  #?(:cljs (ser/serialize-element v)
     :clj  (value+sio->eve-bytes v)))

(defn- deserialize-element-bytes [val-bytes]
  #?(:cljs (ser/deserialize-element {} val-bytes)
     :clj  (eve-bytes->value val-bytes)))

(defn- bytes-length [ba]
  #?(:cljs (.-length ba)
     :clj  (alength ^bytes ba)))

;;=============================================================================
;; Node operations (via ISlabIO)
;;=============================================================================

(defn- alloc-list-node!
  "Allocate and write a list node. Returns slab offset."
  [sio next-off val-bytes]
  (let [vlen (bytes-length val-bytes)
        node-off (-sio-alloc! sio (+ NODE_VDATA_OFFSET vlen))]
    (-sio-write-i32! sio node-off NODE_NEXT_OFFSET next-off)
    (-sio-write-i32! sio node-off NODE_VLEN_OFFSET vlen)
    (when (pos? vlen)
      (-sio-write-bytes! sio node-off NODE_VDATA_OFFSET val-bytes))
    node-off))

(defn- read-node-value
  "Read and deserialize the value from a list node."
  [sio node-off]
  (let [vlen (-sio-read-i32 sio node-off NODE_VLEN_OFFSET)
        vbs  (-sio-read-bytes sio node-off NODE_VDATA_OFFSET vlen)]
    (deserialize-element-bytes vbs)))

(defn- read-node-next
  "Read the next pointer from a list node."
  [sio node-off]
  (-sio-read-i32 sio node-off NODE_NEXT_OFFSET))

;;=============================================================================
;; Header read/write
;;=============================================================================

(defn- write-list-header!
  "Allocate and write a list header. Returns slab offset."
  [sio cnt head-off]
  (let [hdr (-sio-alloc! sio LIST_HEADER_SIZE)]
    (-sio-write-u8!  sio hdr 0 SabList-type-id)
    (-sio-write-i32! sio hdr LIST_CNT_OFFSET cnt)
    (-sio-write-i32! sio hdr LIST_HEAD_OFFSET head-off)
    hdr))

(defn- read-list-header
  "Read cnt and head-off from header. Returns [cnt head-off]."
  [sio hdr-off]
  [(-sio-read-i32 sio hdr-off LIST_CNT_OFFSET)
   (-sio-read-i32 sio hdr-off LIST_HEAD_OFFSET)])

;;=============================================================================
;; EveList deftype — unified via eve3-deftype macro
;;
;; Fields: cnt (int32 @ offset 4), head-off (int32 @ offset 8)
;; Both platforms: deftype EveList [sio__ offset__]
;;=============================================================================

(declare make-eve3-list)

(eve3/eve3-deftype EveList [^:int32 cnt ^:int32 head-off]

  clojure.lang.Sequential

  clojure.lang.Counted
  (count [_] #?(:cljs cnt :clj (int cnt)))

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
      (throw (#?(:cljs js/Error. :clj IllegalStateException.)
              "Can't pop empty list"))
      (let [next-off (read-node-next sio__ head-off)]
        (make-eve3-list sio__ (dec cnt) next-off))))

  clojure.lang.IPersistentCollection
  (cons [_ v]
    (let [vb (serialize-element-bytes v)
          node-off (alloc-list-node! sio__ head-off vb)]
      (make-eve3-list sio__ (inc cnt) node-off)))
  (empty [_]
    (make-eve3-list sio__ 0 NIL_OFFSET))
  (equiv [_ other]
    (cond
      (not (sequential? other)) false
      (not= cnt (count other)) false
      :else
      (loop [off head-off i 0 os (clojure.core/seq other)]
        (if (or (>= i cnt) (nil? os))
          true
          (let [v (read-node-value sio__ off)]
            (if (= v (first os))
              (recur (read-node-next sio__ off) (inc i) (clojure.core/next os))
              false))))))

  clojure.lang.IHashEq
  (hasheq [this]
    #?(:cljs (hash-ordered-coll this)
       :clj (clojure.lang.Murmur3/hashOrdered this)))

  clojure.lang.ISeq
  (first [_]
    (when (pos? cnt)
      (read-node-value sio__ head-off)))
  (more [_]
    (if (<= cnt 1)
      (make-eve3-list sio__ 0 NIL_OFFSET)
      (let [next-off (read-node-next sio__ head-off)]
        (make-eve3-list sio__ (dec cnt) next-off))))
  (next [_]
    (when (> cnt 1)
      (let [next-off (read-node-next sio__ head-off)]
        (make-eve3-list sio__ (dec cnt) next-off))))

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
        #?(:cljs (if (reduced? acc) @acc acc)
           :clj  (unreduced acc))
        (let [v (read-node-value sio__ off)
              nxt (read-node-next sio__ off)]
          (recur nxt (inc i) (f acc v))))))

  ;; CLJS-only: 1-arity reduce (no CLJ equivalent)
  #?@(:cljs [IReduce
             (-reduce [_ f]
               (if (zero? cnt)
                 (f)
                 (loop [off (read-node-next sio__ head-off) i 1
                        acc (read-node-value sio__ head-off)]
                   (if (or (== off NIL_OFFSET) (>= i cnt))
                     acc
                     (let [v (read-node-value sio__ off)
                           result (f acc v)]
                       (if (reduced? result) @result
                         (recur (read-node-next sio__ off) (inc i) result)))))))])

  d/IDirectSerialize
  (d/-direct-serialize [this] offset__)

  d/ISabStorable
  (d/-sab-tag [_] :eve-list)
  (d/-sab-encode [this _] (d/-direct-serialize this))
  (d/-sab-dispose [_ _] nil)

  d/IsEve
  (d/-eve? [_] true)

  d/IEveRoot
  (d/-root-header-off [this] offset__)

  #?@(:cljs [IPrintWithWriter
             (-pr-writer [this writer _opts]
               (-write writer "(")
               (loop [off head-off i 0]
                 (when (and (< i cnt) (not= off NIL_OFFSET))
                   (when (pos? i) (-write writer " "))
                   (-write writer (pr-str (read-node-value sio__ off)))
                   (recur (read-node-next sio__ off) (inc i))))
               (-write writer ")"))])

  clojure.lang.IPersistentList

  java.lang.Iterable
  (iterator [this] #?(:clj (clojure.lang.SeqIterator. (.seq this))))

  java.lang.Object
  (toString [this] #?(:clj (pr-str this)))
  (equals [this other]
    #?(:clj (cond
              (identical? this other) true
              (not (sequential? other)) false
              :else (.equiv this other))))
  (hashCode [this]
    #?(:clj (clojure.lang.Murmur3/hashOrdered this))))

;;=============================================================================
;; Disposal & retirement (CLJS only)
;;=============================================================================

#?(:cljs
   (do
     (defn- free-list-chain!
       "Free all nodes in a list linked-list chain via ISlabIO."
       [sio head-off]
       (loop [node-off head-off]
         (when (not= node-off NIL_OFFSET)
           (let [next-off (read-node-next sio node-off)]
             (-sio-free! sio node-off)
             (recur next-off)))))

     (defn dispose!
       "Dispose an EveList, freeing all nodes and header.
        Call this when the list is no longer needed to reclaim slab memory.

        WARNING: After disposal, the list must not be used."
       [eve-list]
       (when (instance? EveList eve-list)
         (let [sio (.-sio__ eve-list)
               hdr (.-offset__ eve-list)
               hd  (when hdr (-sio-read-i32 sio hdr LIST_HEAD_OFFSET))]
           (when (and hd (not= hd NIL_OFFSET))
             (free-list-chain! sio hd))
           (when (and hdr (not= hdr NIL_OFFSET))
             (-sio-free! sio hdr)))))

     (defn retire-replaced-chain!
       "After an atom swap that replaced a list, retire old nodes not shared
        by the new list.

        Walks the old chain, freeing nodes until we hit a node shared with
        the new chain (or NIL).

        mode is accepted for API compat but currently ignored (always frees)."
       ([sio old-head new-head]
        (retire-replaced-chain! sio old-head new-head :free))
       ([sio old-head new-head mode]
        (when (and (not= old-head NIL_OFFSET) (not= old-head new-head))
          (loop [node-off old-head]
            (when (and (not= node-off NIL_OFFSET) (not= node-off new-head))
              (let [next-off (read-node-next sio node-off)]
                (-sio-free! sio node-off)
                (recur next-off)))))))

     ;; Extend EveList with ISabRetirable
     (extend-type EveList
       d/ISabRetirable
       (-sab-retire-diff! [this new-value _slab-env mode]
         (let [sio      (.-sio__ this)
               hdr      (.-offset__ this)
               old-head (-sio-read-i32 sio hdr LIST_HEAD_OFFSET)
               new-head (when (instance? EveList new-value)
                          (-sio-read-i32 sio (.-offset__ new-value) LIST_HEAD_OFFSET))]
           (if new-head
             ;; Both are EveList — retire replaced chain nodes
             (retire-replaced-chain! sio old-head new-head mode)
             ;; Different type — dispose entire old list
             (dispose! this)))))))

;;=============================================================================
;; Constructors
;;=============================================================================

(defn- make-eve3-list
  "Internal constructor: allocate header, create EveList."
  [sio cnt head-off]
  (let [hdr (write-list-header! sio cnt head-off)]
    (EveList. sio hdr)))

(defn eve3-list-from-header
  "Reconstruct an EveList from an existing header offset."
  [sio header-off]
  (EveList. sio header-off))

(defn empty-list
  "Create an empty Eve list.
   0-arity: uses platform default sio.  1-arity: explicit sio."
  ([] (empty-list #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*)))
  ([sio] (make-eve3-list sio 0 NIL_OFFSET)))

(defn eve3-list
  "Create an Eve list from a collection (reversed, as cons-list)."
  ([coll] (eve3-list #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*) coll))
  ([sio coll]
   (reduce conj (empty-list sio) (reverse coll))))

;; Backward-compat aliases
(def empty-sab-list empty-list)
(def sab-list eve3-list)

;;=============================================================================
;; Registration
;;=============================================================================

#?(:clj
   (do
     (register-jvm-collection-writer! :list
       (fn [sio serialize-val coll]
         (let [elems (vec coll)
               cnt (count elems)]
           (if (zero? cnt)
             (write-list-header! sio 0 NIL_OFFSET)
             (let [head-off (reduce (fn [next-off elem]
                                      (let [^bytes vb (serialize-val elem)]
                                        (alloc-list-node! sio next-off vb)))
                                    NIL_OFFSET
                                    (rseq elems))]
               (write-list-header! sio cnt head-off))))))

     ;; Register by pointer tag (0x13) and header type-id (0x13) — same value
     (ser/register-jvm-type-constructor! ser/FAST_TAG_SAB_LIST EveList-type-id
       (fn [header-off]
         (eve3-list-from-header alloc/*jvm-slab-ctx* header-off)))

     (defmethod print-method EveList [lst ^java.io.Writer w]
       (#'clojure.core/print-sequential "(" #'clojure.core/pr-on " " ")" (seq lst) w))

     ;; Backward-compat JVM aliases
     (defn jvm-write-list!
       "Serialize a Clojure list/seq to slab. Returns header offset.
        Backward-compat alias for the registered :list writer."
       [sio serialize-val coll]
       (mem/jvm-write-collection! :list sio coll))

     (defn jvm-sab-list-from-offset
       "Reconstruct an EveList from a header offset.
        Backward-compat alias. coll-factory arg is ignored (registry-based)."
       ([sio header-off] (eve3-list-from-header sio header-off))
       ([sio header-off _coll-factory] (eve3-list-from-header sio header-off)))

     (def SabList EveList)))

;; CLJS registrations
#?(:cljs
   (do
     ;; Register SAB type constructor for deserialization (tag 0x13 = list)
     (ser/register-sab-type-constructor!
       ser/FAST_TAG_SAB_LIST
       EveList-type-id
       (fn [_sab header-off]
         (eve3-list-from-header (alloc/->CljsSlabIO) header-off)))

     ;; Register disposer for list root values
     (ser/register-header-disposer! EveList-type-id
       (fn [slab-off] (dispose! (eve3-list-from-header (alloc/->CljsSlabIO) slab-off))))

     ;; Register CLJS list/seq → EveList builder for convert-to-sab (mmap atoms)
     (ser/register-cljs-to-sab-builder!
       seq?
       (fn [s] (eve3-list (alloc/->CljsSlabIO) s)))))

;; No-op pool stub — pool system removed, kept for backward compat
#?(:cljs (defn reset-pools! [] nil))
