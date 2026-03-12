(ns eve2.vec
  "Eve2 persistent vector — unified CLJ/CLJS implementation.

   Bit-partitioned trie with tail optimization (Bagwell/Hickey vector).
   Uses ISlabIO protocol for all memory access.

   Uses eve2/deftype macro: one deftype form, CLJS protocol names auto-mapped."
  (:require
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-read-i32
            -sio-read-bytes -sio-write-u8! -sio-write-i32!
            -sio-write-bytes! -sio-alloc! -sio-free!
            -sio-copy-block! NIL_OFFSET]]
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

(def ^:const NODE_SIZE 32)
(def ^:const SHIFT_STEP 5)
(def ^:const MASK 0x1f)

(def ^:const SabVecRoot-type-id 0x12)

(def ^:const SABVECROOT_CNT_OFFSET 4)
(def ^:const SABVECROOT_SHIFT_OFFSET 8)
(def ^:const SABVECROOT_ROOT_OFFSET 12)
(def ^:const SABVECROOT_TAIL_OFFSET 16)
(def ^:const SABVECROOT_TAIL_LEN_OFFSET 20)
(def ^:const SABVECROOT_HEADER_SIZE 24)

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

(defn- alloc-node!
  "Allocate a NODE_SIZE-slot trie node, initializing all slots to NIL_OFFSET."
  [sio]
  (let [node-off (-sio-alloc! sio (* NODE_SIZE 4))]
    (dotimes [i NODE_SIZE]
      (-sio-write-i32! sio node-off (* i 4) NIL_OFFSET))
    node-off))

(defn- node-get
  "Get the i-th slot from a node."
  [sio node-off i]
  (-sio-read-i32 sio node-off (* i 4)))

(defn- node-set!
  "Set the i-th slot in a node."
  [sio node-off i val]
  (-sio-write-i32! sio node-off (* i 4) val)
  node-off)

(defn- clone-node!
  "Allocate a new node and copy contents from source."
  [sio src-off]
  (let [byte-size (* NODE_SIZE 4)
        new-off (-sio-alloc! sio byte-size)]
    (-sio-copy-block! sio new-off 0 src-off 0 byte-size)
    new-off))

;;=============================================================================
;; Value block operations
;; Layout: [len:u32][bytes...]
;;=============================================================================

(defn- make-value-block!
  "Allocate and write a serialized value. Returns slab offset."
  [sio val-bytes]
  (let [val-len (bytes-length val-bytes)
        total-size (+ 4 val-len)
        slab-off (-sio-alloc! sio total-size)]
    (-sio-write-i32! sio slab-off 0 val-len)
    (when (pos? val-len)
      (-sio-write-bytes! sio slab-off 4 val-bytes))
    slab-off))

(defn- read-value-block
  "Read and deserialize a value from a value block offset."
  [sio val-off]
  (when (not= val-off NIL_OFFSET)
    (let [val-len (-sio-read-i32 sio val-off 0)
          val-bs  (-sio-read-bytes sio val-off 4 val-len)]
      (deserialize-element-bytes val-bs))))

;;=============================================================================
;; Trie algorithms (unified via ISlabIO)
;;=============================================================================

(defn- tail-offset-calc
  "Calculate the index where the tail starts."
  [cnt]
  (if (< cnt NODE_SIZE)
    0
    (bit-shift-left (unsigned-bit-shift-right (dec cnt) SHIFT_STEP) SHIFT_STEP)))

(defn- nth-impl
  "Get element at index n from the trie + tail."
  [sio cnt shift root tail n]
  (let [toff (tail-offset-calc cnt)]
    (if (>= n toff)
      ;; Element is in tail
      (read-value-block sio (node-get sio tail (- n toff)))
      ;; Element is in trie
      (let [val-off (loop [node-off root sh shift]
                      (let [idx (bit-and (unsigned-bit-shift-right n sh) MASK)]
                        (if (zero? sh)
                          (node-get sio node-off idx)
                          (recur (node-get sio node-off idx) (- sh SHIFT_STEP)))))]
        (read-value-block sio val-off)))))

(defn- new-path
  "Create a new path from root to leaf at given shift level."
  [sio shift leaf-offset]
  (if (zero? shift)
    leaf-offset
    (let [node-off (alloc-node! sio)]
      (node-set! sio node-off 0 (new-path sio (- shift SHIFT_STEP) leaf-offset))
      node-off)))

(defn- push-tail
  "Push a full tail into the trie, returning new root offset."
  [sio shift parent-off tail-off cnt]
  (let [idx (bit-and (unsigned-bit-shift-right (dec cnt) shift) MASK)
        new-parent-off (if (== parent-off NIL_OFFSET)
                         (alloc-node! sio)
                         (clone-node! sio parent-off))]
    (if (== shift SHIFT_STEP)
      (do (node-set! sio new-parent-off idx tail-off)
          new-parent-off)
      (let [child-off (node-get sio new-parent-off idx)
            new-child-off (if (== child-off NIL_OFFSET)
                            (new-path sio (- shift SHIFT_STEP) tail-off)
                            (push-tail sio (- shift SHIFT_STEP) child-off tail-off cnt))]
        (node-set! sio new-parent-off idx new-child-off)
        new-parent-off))))

(defn- do-assoc
  "Recursively update trie at index n with value offset."
  [sio shift node-off n val-off]
  (let [new-node-off (clone-node! sio node-off)
        idx (bit-and (unsigned-bit-shift-right n shift) MASK)]
    (if (zero? shift)
      (do (node-set! sio new-node-off idx val-off)
          new-node-off)
      (let [child-off (node-get sio node-off idx)
            new-child-off (do-assoc sio (- shift SHIFT_STEP) child-off n val-off)]
        (node-set! sio new-node-off idx new-child-off)
        new-node-off))))

(defn- pop-tail
  "Remove the rightmost leaf from the trie."
  [sio shift node-off cnt]
  (let [idx (bit-and (unsigned-bit-shift-right (dec cnt) shift) MASK)]
    (cond
      (> shift SHIFT_STEP)
      (let [child-off (node-get sio node-off idx)
            new-child (pop-tail sio (- shift SHIFT_STEP) child-off cnt)]
        (if (and (== new-child NIL_OFFSET) (zero? idx))
          NIL_OFFSET
          (let [new-node-off (clone-node! sio node-off)]
            (node-set! sio new-node-off idx new-child)
            new-node-off)))

      (zero? idx)
      NIL_OFFSET

      :else
      (let [new-node-off (clone-node! sio node-off)]
        (node-set! sio new-node-off idx NIL_OFFSET)
        new-node-off))))

;;=============================================================================
;; Header read/write
;;=============================================================================

(defn- write-vec-header!
  "Allocate and write a SabVecRoot header. Returns slab offset."
  [sio cnt shift root tail tail-len]
  (let [hdr-off (-sio-alloc! sio SABVECROOT_HEADER_SIZE)]
    (-sio-write-u8!  sio hdr-off 0 SabVecRoot-type-id)
    (-sio-write-i32! sio hdr-off SABVECROOT_CNT_OFFSET cnt)
    (-sio-write-i32! sio hdr-off SABVECROOT_SHIFT_OFFSET shift)
    (-sio-write-i32! sio hdr-off SABVECROOT_ROOT_OFFSET root)
    (-sio-write-i32! sio hdr-off SABVECROOT_TAIL_OFFSET tail)
    (-sio-write-i32! sio hdr-off SABVECROOT_TAIL_LEN_OFFSET tail-len)
    hdr-off))

(defn- read-vec-header
  "Read SabVecRoot fields from a header block. Returns [cnt shift root tail tail-len]."
  [sio header-off]
  [(-sio-read-i32 sio header-off SABVECROOT_CNT_OFFSET)
   (-sio-read-i32 sio header-off SABVECROOT_SHIFT_OFFSET)
   (-sio-read-i32 sio header-off SABVECROOT_ROOT_OFFSET)
   (-sio-read-i32 sio header-off SABVECROOT_TAIL_OFFSET)
   (-sio-read-i32 sio header-off SABVECROOT_TAIL_LEN_OFFSET)])

;;=============================================================================
;; Constructors (forward declare)
;;=============================================================================

(declare make-eve2-vec)

(defn- make-eve2-vec-impl
  "Internal: create EveVector from fields, allocating header."
  [sio cnt shift root tail tail-len]
  (let [hdr (write-vec-header! sio cnt shift root tail tail-len)]
    (make-eve2-vec sio cnt shift root tail tail-len hdr)))

;;=============================================================================
;; Conj / AssocN / Pop implementations (unified)
;;=============================================================================

(defn- vec-conj-impl
  "Add element to end of vector. Returns new EveVector."
  [sio cnt shift root tail tail-len v]
  (let [val-bytes (serialize-element-bytes v)
        val-off (make-value-block! sio val-bytes)]
    (if (< tail-len NODE_SIZE)
      ;; Room in tail
      (let [new-tail (clone-node! sio tail)]
        (node-set! sio new-tail tail-len val-off)
        (make-eve2-vec-impl sio (inc cnt) shift root new-tail (inc tail-len)))
      ;; Tail is full, push into trie
      (let [old-tail tail
            new-tail (alloc-node! sio)
            _ (node-set! sio new-tail 0 val-off)]
        (if (> (unsigned-bit-shift-right cnt SHIFT_STEP)
               (bit-shift-left 1 shift))
          ;; Trie needs to grow
          (let [new-root-off (alloc-node! sio)
                _ (node-set! sio new-root-off 0 root)
                new-shift (+ shift SHIFT_STEP)
                _ (node-set! sio new-root-off 1 (new-path sio shift old-tail))]
            (make-eve2-vec-impl sio (inc cnt) new-shift new-root-off new-tail 1))
          ;; Room in trie
          (let [new-root (if (== root NIL_OFFSET)
                           (let [nr (alloc-node! sio)]
                             (node-set! sio nr 0 old-tail)
                             nr)
                           (push-tail sio shift root old-tail cnt))]
            (make-eve2-vec-impl sio (inc cnt) shift new-root new-tail 1)))))))

(defn- vec-assoc-n-impl
  "Update element at index n."
  [sio cnt shift root tail tail-len n v]
  (let [val-bytes (serialize-element-bytes v)
        val-off (make-value-block! sio val-bytes)
        toff (tail-offset-calc cnt)]
    (if (>= n toff)
      ;; Update in tail
      (let [new-tail (clone-node! sio tail)]
        (node-set! sio new-tail (- n toff) val-off)
        (make-eve2-vec-impl sio cnt shift root new-tail tail-len))
      ;; Update in trie
      (let [new-root (do-assoc sio shift root n val-off)]
        (make-eve2-vec-impl sio cnt shift new-root tail tail-len)))))

(defn- vec-pop-impl
  "Remove last element. Returns new EveVector."
  [sio cnt shift root tail tail-len]
  (cond
    (== cnt 1)
    (let [new-tail (alloc-node! sio)]
      (make-eve2-vec-impl sio 0 SHIFT_STEP NIL_OFFSET new-tail 0))

    (> tail-len 1)
    ;; Shrink tail
    (let [new-tail (alloc-node! sio)]
      (dotimes [i (dec tail-len)]
        (node-set! sio new-tail i (node-get sio tail i)))
      (make-eve2-vec-impl sio (dec cnt) shift root new-tail (dec tail-len)))

    :else
    ;; Pull rightmost leaf from trie as new tail
    (let [new-cnt (dec cnt)
          new-tail-off (loop [node-off root sh shift]
                         (let [idx (bit-and (unsigned-bit-shift-right (dec new-cnt) sh) MASK)]
                           (if (zero? sh)
                             node-off
                             (recur (node-get sio node-off idx) (- sh SHIFT_STEP)))))
          new-root (pop-tail sio shift root cnt)
          [new-root new-shift]
          (cond
            (== new-root NIL_OFFSET)
            [NIL_OFFSET SHIFT_STEP]

            (and (> shift SHIFT_STEP)
                 (== (node-get sio new-root 1) NIL_OFFSET))
            [(node-get sio new-root 0) (- shift SHIFT_STEP)]

            :else
            [new-root shift])

          new-toff (tail-offset-calc new-cnt)
          new-tl (- new-cnt new-toff)]
      (make-eve2-vec-impl sio new-cnt new-shift new-root new-tail-off new-tl))))

;;=============================================================================
;; EveVector deftype — unified via eve2-deftype macro
;;
;; Fields: cnt, shift, root, tail, tail-len (all int32)
;; CLJS: deftype EveVector [offset__] — fields read from slab
;; CLJ:  deftype EveVector [cnt shift root tail tail-len offset__ sio _meta]
;;=============================================================================

(eve2/eve2-deftype ^{:type-id 0x12} EveVector
  [^:int32 cnt ^:int32 shift ^:int32 root ^:int32 tail ^:int32 tail-len]

  ;; --- Shared mapped protocols ---
  ISequential

  ICounted
  (-count [_] #?(:cljs cnt :clj (int cnt)))

  ISeqable
  (-seq [_]
    (when (pos? cnt)
      (let [sio #?(:cljs eve-alloc/cljs-sio :clj sio)]
        #?(:clj (binding [alloc/*jvm-slab-ctx* sio])
           :cljs nil)
        (let [captured-cnt cnt
              captured-shift shift
              captured-root root
              captured-tail tail]
          #?(:cljs
             ((fn iter [i]
                (lazy-seq
                 (when (< i captured-cnt)
                   (cons (nth-impl sio captured-cnt captured-shift captured-root captured-tail i)
                         (iter (inc i))))))
              0)
             :clj
             (binding [alloc/*jvm-slab-ctx* sio]
               (letfn [(vec-seq [i]
                         (when (< i captured-cnt)
                           (lazy-seq
                            (clojure.core/cons
                             (nth-impl sio captured-cnt captured-shift captured-root captured-tail i)
                             (vec-seq (inc i))))))]
                 (vec-seq 0))))))))

  ;; --- Platform-specific protocols ---
  #?@(:cljs
      [ILookup
       (-lookup [_ k]
         (if (and (integer? k) (>= k 0) (< k cnt))
           (nth-impl eve-alloc/cljs-sio cnt shift root tail k)
           nil))
       (-lookup [_ k not-found]
         (if (and (integer? k) (>= k 0) (< k cnt))
           (nth-impl eve-alloc/cljs-sio cnt shift root tail k)
           not-found))

       IIndexed
       (-nth [_ n]
         (if (or (neg? n) (>= n cnt))
           (throw (js/Error. (str "Index out of bounds: " n)))
           (nth-impl eve-alloc/cljs-sio cnt shift root tail n)))
       (-nth [this n not-found]
         (if (or (neg? n) (>= n cnt))
           not-found
           (-nth this n)))

       ICollection
       (-conj [_ val]
         (vec-conj-impl eve-alloc/cljs-sio cnt shift root tail tail-len val))

       IEmptyableCollection
       (-empty [_]
         (let [sio eve-alloc/cljs-sio
               new-tail (alloc-node! sio)]
           (make-eve2-vec-impl sio 0 SHIFT_STEP NIL_OFFSET new-tail 0)))

       IStack
       (-peek [_]
         (when (pos? cnt)
           (nth-impl eve-alloc/cljs-sio cnt shift root tail (dec cnt))))
       (-pop [_]
         (if (zero? cnt)
           (throw (js/Error. "Can't pop empty vector"))
           (vec-pop-impl eve-alloc/cljs-sio cnt shift root tail tail-len)))

       IVector
       (-assoc-n [this n val]
         (cond
           (== n cnt) (-conj this val)
           (or (neg? n) (> n cnt))
           (throw (js/Error. (str "Index " n " out of bounds [0," cnt "]")))
           :else
           (vec-assoc-n-impl eve-alloc/cljs-sio cnt shift root tail tail-len n val)))

       IAssociative
       (-assoc [this k v]
         (if (integer? k)
           (-assoc-n this k v)
           (throw (js/Error. "Vector's key for assoc must be a number."))))
       (-contains-key? [_ k]
         (and (integer? k) (>= k 0) (< k cnt)))

       IFn
       (-invoke [this k] (-lookup this k nil))
       (-invoke [this k not-found] (-lookup this k not-found))

       IReduce
       (-reduce [_ f]
         (let [sio eve-alloc/cljs-sio]
           (case cnt
             0 (f)
             1 (nth-impl sio cnt shift root tail 0)
             (loop [i 1 acc (nth-impl sio cnt shift root tail 0)]
               (if (>= i cnt)
                 acc
                 (let [acc' (f acc (nth-impl sio cnt shift root tail i))]
                   (if (reduced? acc') @acc' (recur (inc i) acc'))))))))
       (-reduce [_ f init]
         (let [sio eve-alloc/cljs-sio]
           (loop [i 0 acc init]
             (if (or (>= i cnt) (reduced? acc))
               (if (reduced? acc) @acc acc)
               (recur (inc i) (f acc (nth-impl sio cnt shift root tail i)))))))

       IEquiv
       (-equiv [_ other]
         (let [sio eve-alloc/cljs-sio]
           (cond
             (not (sequential? other)) false
             (not= cnt (count other)) false
             :else
             (loop [i 0]
               (if (>= i cnt)
                 true
                 (if (= (nth-impl sio cnt shift root tail i) (nth other i))
                   (recur (inc i))
                   false))))))

       IHash
       (-hash [this]
         (hash-ordered-coll this))

       IPrintWithWriter
       (-pr-writer [_ writer _opts]
         (let [sio eve-alloc/cljs-sio]
           (-write writer "[")
           (dotimes [i (min cnt 10)]
             (when (pos? i) (-write writer " "))
             (-write writer (pr-str (nth-impl sio cnt shift root tail i))))
           (when (> cnt 10)
             (-write writer " ..."))
           (-write writer "]")))

       d/IDirectSerialize
       (-direct-serialize [this]
         (ser/encode-sab-pointer ser/FAST_TAG_SAB_VEC (.-offset__ this)))

       d/ISabStorable
       (-sab-tag [_] :eve-vec)
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
         (EveVector. cnt shift root tail tail-len offset__ sio new-meta))

       clojure.lang.Indexed
       (nth [_ n]
         (when (or (< n 0) (>= n cnt))
           (throw (IndexOutOfBoundsException. (str "Index " n " out of bounds, cnt=" cnt))))
         (binding [alloc/*jvm-slab-ctx* sio]
           (nth-impl sio cnt shift root tail n)))
       (nth [_ n not-found]
         (if (or (< n 0) (>= n cnt))
           not-found
           (binding [alloc/*jvm-slab-ctx* sio]
             (nth-impl sio cnt shift root tail n))))

       clojure.lang.Associative
       (assoc [this k v]
         (if (clojure.lang.Util/isInteger k)
           (.assocN this (int k) v)
           (throw (IllegalArgumentException. "Key must be integer"))))

       clojure.lang.IPersistentVector
       (assocN [this i v]
         (cond
           (== i cnt) (.cons this v)
           (or (< i 0) (>= i cnt)) (throw (IndexOutOfBoundsException. (str "Index " i " out of bounds")))
           :else
           (binding [alloc/*jvm-slab-ctx* sio]
             (vec-assoc-n-impl sio cnt shift root tail tail-len i v))))
       (cons [_ v]
         (binding [alloc/*jvm-slab-ctx* sio]
           (vec-conj-impl sio cnt shift root tail tail-len v)))
       (length [_] (int cnt))
       (empty [_]
         (binding [alloc/*jvm-slab-ctx* sio]
           (let [new-tail (alloc-node! sio)]
             (make-eve2-vec-impl sio 0 SHIFT_STEP NIL_OFFSET new-tail 0))))
       (equiv [this other]
         (cond
           (identical? this other) true
           (instance? clojure.lang.IPersistentVector other)
           (let [ov ^clojure.lang.IPersistentVector other]
             (and (== cnt (.count ov))
                  (binding [alloc/*jvm-slab-ctx* sio]
                    (loop [i 0]
                      (if (== i cnt) true
                        (if (clojure.lang.Util/equiv (.nth this i) (.nth ov i))
                          (recur (inc i)) false))))))
           :else false))
       (containsKey [_ i] (and (>= i 0) (< i cnt)))
       (entryAt [this i] (when (.containsKey this i) (clojure.lang.MapEntry/create i (.nth this i))))
       (valAt [this i] (.nth this (int i)))
       (valAt [this i not-found] (.nth this (int i) not-found))
       (peek [this] (when (pos? cnt) (.nth this (dec cnt))))
       (pop [this]
         (cond
           (zero? cnt) (throw (IllegalStateException. "Can't pop empty vector"))
           :else
           (binding [alloc/*jvm-slab-ctx* sio]
             (vec-pop-impl sio cnt shift root tail tail-len))))

       clojure.lang.Seqable
       (seq [_]
         (when (pos? cnt)
           (binding [alloc/*jvm-slab-ctx* sio]
             (letfn [(vec-seq [i]
                       (when (< i cnt)
                         (lazy-seq
                          (clojure.core/cons (nth-impl sio cnt shift root tail i)
                                (vec-seq (inc i))))))]
               (vec-seq 0)))))

       clojure.lang.Counted
       (count [_] (int cnt))

       clojure.lang.IReduceInit
       (reduce [_ f init]
         (binding [alloc/*jvm-slab-ctx* sio]
           (loop [i 0 acc init]
             (if (or (>= i cnt) (reduced? acc))
               (unreduced acc)
               (recur (inc i) (f acc (nth-impl sio cnt shift root tail i)))))))

       clojure.lang.IReduce
       (reduce [this f]
         (if (zero? cnt)
           (f)
           (binding [alloc/*jvm-slab-ctx* sio]
             (loop [i 1 acc (nth-impl sio cnt shift root tail 0)]
               (if (or (>= i cnt) (reduced? acc))
                 (unreduced acc)
                 (recur (inc i) (f acc (nth-impl sio cnt shift root tail i))))))))

       clojure.lang.IFn
       (invoke [this i] (.nth this (int i)))
       (invoke [this i not-found] (.nth this (int i) not-found))

       java.lang.Iterable
       (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

       java.lang.Object
       (toString [this] (pr-str this))
       (equals [this other]
         (cond
           (identical? this other) true
           (not (instance? clojure.lang.IPersistentVector other)) false
           :else
           (let [ov ^clojure.lang.IPersistentVector other]
             (and (== cnt (.count ov))
                  (binding [alloc/*jvm-slab-ctx* sio]
                    (loop [i 0]
                      (if (== i cnt) true
                        (if (.equals ^Object (.nth this i) (.nth ov i))
                          (recur (inc i)) false))))))))
       (hashCode [this] (clojure.lang.Murmur3/hashOrdered this))

       clojure.lang.IHashEq
       (hasheq [this]
         (clojure.lang.Murmur3/hashOrdered this))

       d/IEveRoot
       (-root-header-off [_] offset__)]))

;;=============================================================================
;; Constructor
;;=============================================================================

(defn- make-eve2-vec
  "Internal constructor."
  [sio cnt shift root tail tail-len hdr]
  #?(:cljs (EveVector. hdr)
     :clj  (EveVector. cnt shift root tail tail-len hdr sio nil)))

(defn eve2-vec-from-header
  "Reconstruct an EveVector from an existing header offset."
  [sio header-off]
  (let [[cnt shift root tail tail-len] (read-vec-header sio header-off)]
    #?(:cljs (EveVector. header-off)
       :clj  (EveVector. cnt shift root tail tail-len header-off sio nil))))

(defn empty-vec
  "Create an empty Eve2 vector."
  []
  (let [sio (get-sio)
        tail (alloc-node! sio)]
    (make-eve2-vec-impl sio 0 SHIFT_STEP NIL_OFFSET tail 0)))

(defn eve2-vec
  "Create an Eve2 vector from a collection."
  [coll]
  (reduce conj (empty-vec) coll))

;;=============================================================================
;; Registration
;;=============================================================================

#?(:clj
   (do
     (register-jvm-collection-writer! :vec
       (fn [sio serialize-val coll]
         (let [elems (vec coll)
               cnt   (count elems)]
           (if (zero? cnt)
             (write-vec-header! sio 0 SHIFT_STEP NIL_OFFSET NIL_OFFSET 0)
             (let [val-offs (mapv (fn [elem]
                                    (make-value-block! sio (serialize-val elem)))
                                  elems)
                   toff     (tail-offset-calc cnt)
                   tail-len (- cnt toff)
                   ;; Build tail node
                   tail-off (let [t-off (alloc-node! sio)]
                              (dorun (map-indexed
                                       (fn [i v-off]
                                         (-sio-write-i32! sio t-off (* i 4) v-off))
                                       (subvec val-offs toff cnt)))
                              t-off)
                   ;; Build trie from trie-portion val-offs
                   trie-val-offs (subvec val-offs 0 toff)
                   [root sft]
                   (if (empty? trie-val-offs)
                     [NIL_OFFSET SHIFT_STEP]
                     ;; Build leaf nodes
                     (let [leaf-nodes
                           (mapv (fn [chunk]
                                   (let [node-off (alloc-node! sio)]
                                     (dorun (map-indexed
                                              (fn [i v-off]
                                                (-sio-write-i32! sio node-off (* i 4) v-off))
                                              chunk))
                                     node-off))
                                 (partition-all NODE_SIZE trie-val-offs))]
                       ;; Build internal levels
                       (loop [nodes leaf-nodes sh SHIFT_STEP]
                         (if (<= (count nodes) NODE_SIZE)
                           (let [root-off (alloc-node! sio)]
                             (dorun (map-indexed
                                      (fn [i child-off]
                                        (-sio-write-i32! sio root-off (* i 4) child-off))
                                      nodes))
                             [root-off sh])
                           (let [parent-nodes
                                 (mapv (fn [chunk]
                                         (let [node-off (alloc-node! sio)]
                                           (dorun (map-indexed
                                                    (fn [i child-off]
                                                      (-sio-write-i32! sio node-off (* i 4) child-off))
                                                    chunk))
                                           node-off))
                                       (partition-all NODE_SIZE nodes))]
                             (recur parent-nodes (+ sh SHIFT_STEP)))))))]
               (write-vec-header! sio cnt sft root tail-off tail-len))))))

     (ser/register-jvm-type-constructor! SabVecRoot-type-id
       (fn [header-off]
         (eve2-vec-from-header alloc/*jvm-slab-ctx* header-off)))

     (defmethod print-method EveVector [v ^java.io.Writer w]
       (#'clojure.core/print-sequential "[" #'clojure.core/pr-on " " "]" (seq v) w))))
