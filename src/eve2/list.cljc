(ns eve2.list
  "Eve2 persistent list — unified CLJ/CLJS implementation.

   Singly-linked persistent (immutable) list using ISlabIO for memory access.
   Node layout: [next-off:i32 @ 0][val-len:u32 @ 4][val-bytes... @ 8]
   Header layout: [type-id:u8 | pad:3 | cnt:i32 | head-off:i32] = 12 bytes

   Uses eve2/deftype macro: one deftype form, CLJS protocol names auto-mapped."
  (:require
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-read-i32
            -sio-read-bytes -sio-write-u8! -sio-write-i32!
            -sio-write-bytes! -sio-alloc! -sio-free!
            NIL_OFFSET]]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   #?@(:cljs [[eve2.alloc :as eve-alloc]]
       :clj  [[eve2.deftype :as eve2]
              [eve.mem :as mem :refer [eve-bytes->value value+sio->eve-bytes
                                       register-jvm-collection-writer!]]]))
  #?(:cljs (:require-macros [eve2.deftype :as eve2])))

;;=============================================================================
;; Shared Constants
;;=============================================================================

(def ^:const SabList-type-id 0x13)
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

(defn- get-sio []
  #?(:cljs eve-alloc/cljs-sio
     :clj  alloc/*jvm-slab-ctx*))

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
;; EveList deftype — unified via eve2-deftype macro
;;
;; Fields: cnt (int32 @ offset 4), head-off (int32 @ offset 8)
;; CLJS: deftype EveList [offset__] — fields read from slab via DataView
;; CLJ:  deftype EveList [cnt head-off offset__ sio _meta] — regular fields
;;=============================================================================

(declare make-eve2-list)

(eve2/eve2-deftype ^{:type-id 0x13} EveList [^:int32 cnt ^:int32 head-off]
  ;; --- Shared protocols (CLJS names, macro translates for JVM) ---
  ;; CLJ method bodies are auto-wrapped in (binding [*jvm-slab-ctx* sio] ...) by the macro.
  ISequential

  ICounted
  (-count [_] #?(:cljs cnt :clj (int cnt)))

  ISeqable
  (-seq [this] (when (pos? cnt) this))

  IMeta
  (-meta [_] #?(:cljs nil :clj _meta))

  IWithMeta
  (-with-meta [this m]
    #?(:cljs this
       :clj (EveList. cnt head-off offset__ sio m)))

  IStack
  (-peek [_]
    (when (pos? cnt)
      (read-node-value (get-sio) head-off)))
  (-pop [_]
    (if (zero? cnt)
      (throw (#?(:cljs js/Error. :clj IllegalStateException.)
              "Can't pop empty list"))
      (let [sio (get-sio)
            next-off (read-node-next sio head-off)]
        (make-eve2-list sio (dec cnt) next-off))))

  ICollection
  (-conj [_ v]
    (let [sio (get-sio)
          vb (serialize-element-bytes v)
          node-off (alloc-list-node! sio head-off vb)]
      (make-eve2-list sio (inc cnt) node-off)))

  IEmptyableCollection
  (-empty [_]
    (make-eve2-list (get-sio) 0 NIL_OFFSET))

  ISeq
  (-first [_]
    (when (pos? cnt)
      (read-node-value (get-sio) head-off)))
  (-rest [_]
    (if (<= cnt 1)
      (make-eve2-list (get-sio) 0 NIL_OFFSET)
      (let [sio (get-sio)
            next-off (read-node-next sio head-off)]
        (make-eve2-list sio (dec cnt) next-off))))

  INext
  (-next [_]
    (when (> cnt 1)
      (let [sio (get-sio)
            next-off (read-node-next sio head-off)]
        (make-eve2-list sio (dec cnt) next-off))))

  IReduce
  (-reduce [_ f]
    (if (zero? cnt)
      (f)
      (let [sio (get-sio)]
        (loop [off head-off i 0 acc nil]
          (if (or (== off NIL_OFFSET) (>= i cnt))
            (if (reduced? acc) @acc acc)
            (let [v (read-node-value sio off)
                  next (read-node-next sio off)
                  new-acc (if (zero? i) v (f acc v))]
              (if (reduced? new-acc)
                @new-acc
                (recur next (inc i) new-acc))))))))
  (-reduce [_ f init]
    (let [sio (get-sio)]
      (loop [off head-off i 0 acc init]
        (if (or (== off NIL_OFFSET) (>= i cnt) (reduced? acc))
          (#?(:cljs (fn [x] (if (reduced? x) @x x)) :clj unreduced) acc)
          (let [v (read-node-value sio off)
                next (read-node-next sio off)]
            (recur next (inc i) (f acc v)))))))

  IEquiv
  (-equiv [_ other]
    (cond
      (not (sequential? other)) false
      (not= cnt (count other)) false
      :else
      (let [sio (get-sio)]
        (loop [off head-off i 0 os (seq other)]
          (if (or (>= i cnt) (nil? os))
            true
            (let [v (read-node-value sio off)]
              (if (= v (first os))
                (recur (read-node-next sio off) (inc i) (next os))
                false)))))))

  IHash
  (-hash [this]
    #?(:cljs (hash-ordered-coll this)
       :clj (clojure.lang.Murmur3/hashOrdered this)))

  IFn
  (-invoke [_ n]
    (if (and (integer? n) (>= n 0) (< n cnt))
      (let [sio (get-sio)]
        (loop [off head-off i 0]
          (if (== i n)
            (read-node-value sio off)
            (recur (read-node-next sio off) (inc i)))))
      nil))

  #?@(:cljs [IPrintWithWriter
             (-pr-writer [_ writer _opts]
               (let [sio (get-sio)]
                 (-write writer "(")
                 (loop [off head-off i 0]
                   (when (and (< i (min cnt 10)) (not= off NIL_OFFSET))
                     (when (pos? i) (-write writer " "))
                     (-write writer (pr-str (read-node-value sio off)))
                     (recur (read-node-next sio off) (inc i))))
                 (when (> cnt 10)
                   (-write writer " ..."))
                 (-write writer ")")))])

  d/IDirectSerialize
  (-direct-serialize [this]
    #?(:cljs (ser/encode-sab-pointer ser/FAST_TAG_SAB_LIST (.-offset__ this))
       :clj offset__))

  d/ISabStorable
  (-sab-tag [_] :eve-list)
  (-sab-encode [this _] (d/-direct-serialize this))
  (-sab-dispose [_ _] nil)

  d/IsEve
  (-eve? [_] true)

  d/IEveRoot
  (-root-header-off [this]
    #?(:cljs (.-offset__ this)
       :clj offset__))

  ;; --- CLJ-only interfaces (no CLJS equivalent) ---
  #?@(:clj
      [clojure.lang.ISeq
       ;; next is CLJ-only (INext has no JVM mapping)
       (next [_]
         (when (> cnt 1)
           (let [next-off (read-node-next sio head-off)]
             (make-eve2-list sio (dec cnt) next-off))))

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
         (clojure.lang.Murmur3/hashOrdered this))]))

;;=============================================================================
;; Constructors
;;=============================================================================

(defn- make-eve2-list
  "Internal constructor."
  [sio cnt head-off]
  (let [hdr (write-list-header! sio cnt head-off)]
    #?(:cljs (EveList. hdr)
       :clj  (EveList. cnt head-off hdr sio nil))))

(defn eve2-list-from-header
  "Reconstruct an EveList from a header offset."
  [sio header-off]
  (let [[cnt head-off] (read-list-header sio header-off)]
    #?(:cljs (EveList. header-off)
       :clj  (EveList. cnt head-off header-off sio nil))))

(defn empty-list
  "Create an empty Eve2 list."
  []
  (let [sio (get-sio)]
    (make-eve2-list sio 0 NIL_OFFSET)))

(defn eve2-list
  "Create an Eve2 list from a collection (reversed, as cons-list)."
  [coll]
  (reduce conj (empty-list) (reverse coll)))

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
             ;; Build chain from end to front
             (let [head-off (reduce (fn [next-off elem]
                                      (let [^bytes vb (serialize-val elem)]
                                        (alloc-list-node! sio next-off vb)))
                                    NIL_OFFSET
                                    (rseq elems))]
               (write-list-header! sio cnt head-off))))))

     (ser/register-jvm-type-constructor! SabList-type-id
       (fn [header-off]
         (eve2-list-from-header alloc/*jvm-slab-ctx* header-off)))

     (defmethod print-method EveList [lst ^java.io.Writer w]
       (#'clojure.core/print-sequential "(" #'clojure.core/pr-on " " ")" (seq lst) w))))
