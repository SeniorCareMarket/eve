(ns eve.list
  "Eve persistent list — unified CLJ/CLJS implementation.

   Singly-linked persistent (immutable) list using ISlabIO for memory access.
   Node layout: [next-off:i32 @ 0][val-len:u32 @ 4][val-bytes... @ 8]
   Header layout: [type-id:u8 | pad:3 | cnt:i32 | head-off:i32] = 12 bytes

   Uses eve/deftype macro: CLJ protocol names, sio threaded everywhere."
  (:require
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-read-i32
            -sio-read-bytes -sio-write-u8! -sio-write-i32!
            -sio-write-bytes! -sio-alloc! -sio-free!
            NIL_OFFSET]]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.platform :as p]
   #?@(:clj  [[eve.deftype-proto.macros :as eve]
              [eve.mem :as mem :refer [eve-bytes->value value+sio->eve-bytes
                                       register-jvm-collection-writer!]]]))
  #?(:cljs (:require-macros [eve.deftype-proto.macros :as eve])))

;;=============================================================================
;; Shared Constants
;;=============================================================================

#?(:clj (def EveList-type-id 0x13)
   :cljs (declare EveList-type-id))
(def SabList-type-id #?(:clj EveList-type-id :cljs 0x13)) ;; backward compat
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
;; EveList deftype — unified via eve/deftype macro
;;
;; Fields: cnt (int32 @ offset 4), head-off (int32 @ offset 8)
;; Both platforms: deftype EveList [sio__ offset__]
;;=============================================================================

(declare make-list)

(eve/deftype EveList [^:int32 cnt ^:int32 head-off]

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
      (p/throw! "Can't pop empty list")
      (let [next-off (read-node-next sio__ head-off)]
        (make-list sio__ (dec cnt) next-off))))

  clojure.lang.IPersistentCollection
  (cons [_ v]
    (let [vb (serialize-element-bytes v)
          node-off (alloc-list-node! sio__ head-off vb)]
      (make-list sio__ (inc cnt) node-off)))
  (empty [_]
    (make-list sio__ 0 NIL_OFFSET))
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
  (hasheq [this] (p/hash-ordered this))

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
  (d/-sab-encode [this _] #?(:cljs (ser/encode-eve-pointer this) :clj offset__))
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
    (let [sio (#?(:cljs .-sio__ :clj .sio__) eve-list)
          hdr (#?(:cljs .-offset__ :clj .offset__) eve-list)
          hd  (when hdr (-sio-read-i32 sio hdr LIST_HEAD_OFFSET))]
      (when (and hd (not= hd NIL_OFFSET))
        (free-list-chain! sio hd))
      (when (and hdr (not= hdr NIL_OFFSET))
        (-sio-free! sio hdr)))))

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
           (-sio-free! sio node-off)
           (recur next-off)))))))

;; ISabRetirable
(extend-type EveList
  d/ISabRetirable
  (-sab-retire-diff! [this new-value _slab-env mode]
    (let [sio      (#?(:cljs .-sio__ :clj .sio__) this)
          hdr      (#?(:cljs .-offset__ :clj .offset__) this)
          old-head (-sio-read-i32 sio hdr LIST_HEAD_OFFSET)
          new-head (when (instance? EveList new-value)
                     (-sio-read-i32 sio (#?(:cljs .-offset__ :clj .offset__) new-value) LIST_HEAD_OFFSET))]
      (if new-head
        (retire-replaced-chain! sio old-head new-head mode)
        (dispose! this)))))

;;=============================================================================
;; Constructors
;;=============================================================================

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
   0-arity: uses platform default sio.  1-arity: explicit sio."
  ([] (empty-list #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*)))
  ([sio] (make-list sio 0 NIL_OFFSET)))

(defn eve-list
  "Create an Eve list from a collection (reversed, as cons-list)."
  ([coll] (eve-list #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*) coll))
  ([sio coll]
   (reduce conj (empty-list sio) (reverse coll))))

;; Backward-compat aliases
(def empty-sab-list empty-list)
(def sab-list eve-list)

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

     ;; Backward-compat JVM aliases
     (defn jvm-write-list!
       "Serialize a Clojure list/seq to slab. Returns header offset.
        Backward-compat alias for the registered :list writer."
       [sio serialize-val coll]
       (mem/jvm-write-collection! :list sio coll))

     (defn jvm-sab-list-from-offset
       "Reconstruct an EveList from a header offset.
        Backward-compat alias. coll-factory arg is ignored (registry-based)."
       ([sio header-off] (list-from-header sio header-off))
       ([sio header-off _coll-factory] (list-from-header sio header-off)))

     (def SabList EveList)))

;; Type constructor + disposer + builder registrations
(eve/register-eve-type!
  {:fast-tag    ser/FAST_TAG_SAB_LIST
   :type-id     EveList-type-id
   :from-header list-from-header
   :dispose     dispose!
   :builder-pred seq?
   :builder-ctor eve-list
   :print-fn    #?(:clj (fn [] (defmethod print-method EveList [lst ^java.io.Writer w]
                                  (#'clojure.core/print-sequential "(" #'clojure.core/pr-on " " ")" (seq lst) w)))
                   :cljs nil)})

;; No-op pool stub — pool system removed, kept for backward compat
#?(:cljs (defn reset-pools! [] nil))
