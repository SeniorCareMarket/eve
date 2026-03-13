(ns eve3.list
  "Eve3 persistent list — unified CLJ/CLJS implementation.

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
   #?@(:cljs [[eve3.alloc :as eve-alloc]]
       :clj  [[eve3.deftype :as eve3]
              [eve.mem :as mem :refer [eve-bytes->value value+sio->eve-bytes
                                       register-jvm-collection-writer!]]]))
  #?(:cljs (:require-macros [eve3.deftype :as eve3])))

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

(deftype EveList [sio__ offset__
                  #?@(:clj [^int cnt]) #?@(:cljs [cnt])
                  head-off]

  #?@(:cljs [ISequential])
  #?(:clj clojure.lang.Sequential)

  #?(:cljs ICounted :clj clojure.lang.Counted)
  (#?(:cljs -count :clj count) [_] #?(:cljs cnt :clj (int cnt)))

  #?(:cljs ISeqable :clj clojure.lang.Seqable)
  (#?(:cljs -seq :clj seq) [this] (when (pos? cnt) this))

  #?(:cljs IMeta :clj clojure.lang.IMeta)
  (#?(:cljs -meta :clj meta) [_] nil)

  #?(:cljs IWithMeta :clj clojure.lang.IObj)
  (#?(:cljs -with-meta :clj withMeta) [this m] this)

  #?(:cljs IStack :clj clojure.lang.IPersistentStack)
  (#?(:cljs -peek :clj peek) [_]
    (when (pos? cnt)
      (read-node-value sio__ head-off)))
  (#?(:cljs -pop :clj pop) [_]
    (if (zero? cnt)
      (throw (#?(:cljs js/Error. :clj IllegalStateException.)
              "Can't pop empty list"))
      (let [next-off (read-node-next sio__ head-off)]
        (make-eve3-list sio__ (dec cnt) next-off))))

  #?(:cljs ICollection :clj clojure.lang.IPersistentCollection)
  (#?(:cljs -conj :clj cons) [_ v]
    (let [vb (serialize-element-bytes v)
          node-off (alloc-list-node! sio__ head-off vb)]
      (make-eve3-list sio__ (inc cnt) node-off)))
  (#?(:cljs -empty :clj empty) [_]
    (make-eve3-list sio__ 0 NIL_OFFSET))
  (#?(:cljs -equiv :clj equiv) [_ other]
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

  #?(:cljs IHash :clj clojure.lang.IHashEq)
  (#?(:cljs -hash :clj hasheq) [this]
    #?(:cljs (hash-ordered-coll this)
       :clj (clojure.lang.Murmur3/hashOrdered this)))

  #?(:cljs ISeq :clj clojure.lang.ISeq)
  (#?(:cljs -first :clj first) [_]
    (when (pos? cnt)
      (read-node-value sio__ head-off)))
  (#?(:cljs -rest :clj more) [_]
    (if (<= cnt 1)
      (make-eve3-list sio__ 0 NIL_OFFSET)
      (let [next-off (read-node-next sio__ head-off)]
        (make-eve3-list sio__ (dec cnt) next-off))))
  #?@(:clj
      [(next [_]
         (when (> cnt 1)
           (let [next-off (read-node-next sio__ head-off)]
             (make-eve3-list sio__ (dec cnt) next-off))))])

  #?@(:cljs [INext
             (-next [_]
               (when (> cnt 1)
                 (let [next-off (read-node-next sio__ head-off)]
                   (make-eve3-list sio__ (dec cnt) next-off))))])

  #?(:cljs IFn :clj clojure.lang.IFn)
  (#?(:cljs -invoke :clj invoke) [_ n]
    (if (and (integer? n) (>= n 0) (< n cnt))
      (loop [off head-off i 0]
        (if (== i n)
          (read-node-value sio__ off)
          (recur (read-node-next sio__ off) (inc i))))
      nil))

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
                         (recur (read-node-next sio__ off) (inc i) result)))))))
             (-reduce [_ f init]
               (loop [off head-off i 0 acc init]
                 (if (or (== off NIL_OFFSET) (>= i cnt) (reduced? acc))
                   (if (reduced? acc) @acc acc)
                   (let [v (read-node-value sio__ off)
                         nxt (read-node-next sio__ off)]
                     (recur nxt (inc i) (f acc v))))))])

  #?@(:clj [clojure.lang.IReduceInit
             (reduce [_ f init]
               (loop [off head-off i 0 acc init]
                 (if (or (== off NIL_OFFSET) (>= i cnt) (reduced? acc))
                   (unreduced acc)
                   (let [v (read-node-value sio__ off)
                         nxt (read-node-next sio__ off)]
                     (recur nxt (inc i) (f acc v))))))])

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

  ;; --- CLJ-only interfaces ---
  #?@(:clj
      [clojure.lang.IPersistentList

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

(defn- make-eve3-list
  "Internal constructor."
  [sio cnt head-off]
  (let [hdr (write-list-header! sio cnt head-off)]
    (EveList. sio hdr cnt head-off)))

(defn eve3-list-from-header
  "Reconstruct an EveList from a header offset."
  [sio header-off]
  (let [[cnt head-off] (read-list-header sio header-off)]
    (EveList. sio header-off cnt head-off)))

(defn empty-list
  "Create an empty Eve3 list."
  [sio]
  (make-eve3-list sio 0 NIL_OFFSET))

(defn eve3-list
  "Create an Eve3 list from a collection (reversed, as cons-list)."
  [sio coll]
  (reduce conj (empty-list sio) (reverse coll)))

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

     (ser/register-jvm-type-constructor! SabList-type-id
       (fn [header-off]
         (eve3-list-from-header alloc/*jvm-slab-ctx* header-off)))

     (defmethod print-method EveList [lst ^java.io.Writer w]
       (#'clojure.core/print-sequential "(" #'clojure.core/pr-on " " ")" (seq lst) w))))
