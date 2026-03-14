(ns eve.vec
  "Eve persistent vector — unified CLJ/CLJS implementation.

   Bit-partitioned trie with tail optimization (Bagwell/Hickey vector).
   Uses ISlabIO protocol for all memory access, sio threaded everywhere.

   Uses eve/deftype macro: CLJ protocol names, auto-translated on CLJS."
  (:require
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-read-i32
            -sio-read-bytes -sio-write-u8! -sio-write-i32!
            -sio-write-bytes! -sio-alloc! -sio-free!
            -sio-copy-block! NIL_OFFSET]]
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

(def ^:const NODE_SIZE 32)
(def ^:const SHIFT_STEP 5)
(def ^:const MASK 0x1f)

;; CLJ needs the def for macro resolve; CLJS gets it from eve/deftype expansion
#?(:clj (def EveVector-type-id 0x12)
   :cljs (declare EveVector-type-id))
(def SabVecRoot-type-id #?(:clj EveVector-type-id :cljs 0x12)) ;; backward compat

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

;;=============================================================================
;; Node operations (via ISlabIO)
;;=============================================================================

(defn- alloc-node! [sio]
  (let [node-off (-sio-alloc! sio (* NODE_SIZE 4))]
    (dotimes [i NODE_SIZE]
      (-sio-write-i32! sio node-off (* i 4) NIL_OFFSET))
    node-off))

(defn- node-get [sio node-off i]
  (-sio-read-i32 sio node-off (* i 4)))

(defn- node-set! [sio node-off i val]
  (-sio-write-i32! sio node-off (* i 4) val)
  node-off)

(defn- clone-node! [sio src-off]
  (let [byte-size (* NODE_SIZE 4)
        new-off (-sio-alloc! sio byte-size)]
    (-sio-copy-block! sio new-off 0 src-off 0 byte-size)
    new-off))

;;=============================================================================
;; Value block operations — [len:u32][bytes...]
;;=============================================================================

(defn- make-value-block! [sio val-bytes]
  (let [val-len (bytes-length val-bytes)
        total-size (+ 4 val-len)
        slab-off (-sio-alloc! sio total-size)]
    (-sio-write-i32! sio slab-off 0 val-len)
    (when (pos? val-len)
      (-sio-write-bytes! sio slab-off 4 val-bytes))
    slab-off))

(defn- read-value-block [sio val-off]
  (when (not= val-off NIL_OFFSET)
    (let [val-len (-sio-read-i32 sio val-off 0)
          val-bs  (-sio-read-bytes sio val-off 4 val-len)]
      (deserialize-element-bytes val-bs))))

;;=============================================================================
;; Trie algorithms (unified via ISlabIO)
;;=============================================================================

(defn- tail-offset-calc [cnt]
  (if (< cnt NODE_SIZE)
    0
    (bit-shift-left (unsigned-bit-shift-right (dec cnt) SHIFT_STEP) SHIFT_STEP)))

(defn- nth-impl [sio cnt shift root tail n]
  (let [toff (tail-offset-calc cnt)]
    (if (>= n toff)
      (read-value-block sio (node-get sio tail (- n toff)))
      (let [val-off (loop [node-off root sh shift]
                      (let [idx (bit-and (unsigned-bit-shift-right n sh) MASK)]
                        (if (zero? sh)
                          (node-get sio node-off idx)
                          (recur (node-get sio node-off idx) (- sh SHIFT_STEP)))))]
        (read-value-block sio val-off)))))

(defn- new-path [sio shift leaf-offset]
  (if (zero? shift)
    leaf-offset
    (let [node-off (alloc-node! sio)]
      (node-set! sio node-off 0 (new-path sio (- shift SHIFT_STEP) leaf-offset))
      node-off)))

(defn- push-tail [sio shift parent-off tail-off cnt]
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

(defn- do-assoc [sio shift node-off n val-off]
  (let [new-node-off (clone-node! sio node-off)
        idx (bit-and (unsigned-bit-shift-right n shift) MASK)]
    (if (zero? shift)
      (do (node-set! sio new-node-off idx val-off)
          new-node-off)
      (let [child-off (node-get sio node-off idx)
            new-child-off (do-assoc sio (- shift SHIFT_STEP) child-off n val-off)]
        (node-set! sio new-node-off idx new-child-off)
        new-node-off))))

(defn- pop-tail [sio shift node-off cnt]
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

(defn- write-vec-header! [sio cnt shift root tail tail-len]
  (let [hdr-off (-sio-alloc! sio SABVECROOT_HEADER_SIZE)]
    (-sio-write-u8!  sio hdr-off 0 SabVecRoot-type-id)
    (-sio-write-i32! sio hdr-off SABVECROOT_CNT_OFFSET cnt)
    (-sio-write-i32! sio hdr-off SABVECROOT_SHIFT_OFFSET shift)
    (-sio-write-i32! sio hdr-off SABVECROOT_ROOT_OFFSET root)
    (-sio-write-i32! sio hdr-off SABVECROOT_TAIL_OFFSET tail)
    (-sio-write-i32! sio hdr-off SABVECROOT_TAIL_LEN_OFFSET tail-len)
    hdr-off))

(defn- read-vec-header [sio header-off]
  [(-sio-read-i32 sio header-off SABVECROOT_CNT_OFFSET)
   (-sio-read-i32 sio header-off SABVECROOT_SHIFT_OFFSET)
   (-sio-read-i32 sio header-off SABVECROOT_ROOT_OFFSET)
   (-sio-read-i32 sio header-off SABVECROOT_TAIL_OFFSET)
   (-sio-read-i32 sio header-off SABVECROOT_TAIL_LEN_OFFSET)])

;;=============================================================================
;; Constructors (forward declare — actual impls after deftype for CLJ compat)
;;=============================================================================

(declare make-vec-impl)

;;=============================================================================
;; Conj / AssocN / Pop implementations
;;=============================================================================

(defn- vec-conj-impl [sio cnt shift root tail tail-len v]
  (let [val-bytes (serialize-element-bytes v)
        val-off (make-value-block! sio val-bytes)]
    (if (< tail-len NODE_SIZE)
      (let [new-tail (clone-node! sio tail)]
        (node-set! sio new-tail tail-len val-off)
        (make-vec-impl sio (inc cnt) shift root new-tail (inc tail-len)))
      (let [old-tail tail
            new-tail (alloc-node! sio)
            _ (node-set! sio new-tail 0 val-off)]
        (if (> (unsigned-bit-shift-right cnt SHIFT_STEP)
               (bit-shift-left 1 shift))
          (let [new-root-off (alloc-node! sio)
                _ (node-set! sio new-root-off 0 root)
                new-shift (+ shift SHIFT_STEP)
                _ (node-set! sio new-root-off 1 (new-path sio shift old-tail))]
            (make-vec-impl sio (inc cnt) new-shift new-root-off new-tail 1))
          (let [new-root (if (== root NIL_OFFSET)
                           (let [nr (alloc-node! sio)]
                             (node-set! sio nr 0 old-tail)
                             nr)
                           (push-tail sio shift root old-tail cnt))]
            (make-vec-impl sio (inc cnt) shift new-root new-tail 1)))))))

(defn- vec-assoc-n-impl [sio cnt shift root tail tail-len n v]
  (let [val-bytes (serialize-element-bytes v)
        val-off (make-value-block! sio val-bytes)
        toff (tail-offset-calc cnt)]
    (if (>= n toff)
      (let [new-tail (clone-node! sio tail)]
        (node-set! sio new-tail (- n toff) val-off)
        (make-vec-impl sio cnt shift root new-tail tail-len))
      (let [new-root (do-assoc sio shift root n val-off)]
        (make-vec-impl sio cnt shift new-root tail tail-len)))))

(defn- vec-pop-impl [sio cnt shift root tail tail-len]
  (cond
    (== cnt 1)
    (let [new-tail (alloc-node! sio)]
      (make-vec-impl sio 0 SHIFT_STEP NIL_OFFSET new-tail 0))

    (> tail-len 1)
    (let [new-tail (alloc-node! sio)]
      (dotimes [i (dec tail-len)]
        (node-set! sio new-tail i (node-get sio tail i)))
      (make-vec-impl sio (dec cnt) shift root new-tail (dec tail-len)))

    :else
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
      (make-vec-impl sio new-cnt new-shift new-root new-tail-off new-tl))))

;;=============================================================================
;; Disposal helpers — recursive freeing for vector trees
;;=============================================================================

(defn- free-block!
  "Free a single allocation block by its slab-qualified offset."
  [sio slab-off]
  (when (not= slab-off NIL_OFFSET)
    (-sio-free! sio slab-off)))

(defn- free-leaf-node!
  "Free a leaf node and all its value blocks."
  [sio node-slab-off]
  (when (not= node-slab-off NIL_OFFSET)
    (dotimes [i NODE_SIZE]
      (let [val-off (node-get sio node-slab-off i)]
        (when (not= val-off NIL_OFFSET)
          (free-block! sio val-off))))
    (free-block! sio node-slab-off)))

(defn- free-trie-node!
  "Recursively free a trie node and all its descendants."
  [sio node-slab-off shift-val]
  (when (not= node-slab-off NIL_OFFSET)
    (if (zero? shift-val)
      (free-leaf-node! sio node-slab-off)
      (do
        (dotimes [i NODE_SIZE]
          (let [child-off (node-get sio node-slab-off i)]
            (when (not= child-off NIL_OFFSET)
              (free-trie-node! sio child-off (- shift-val SHIFT_STEP)))))
        (free-block! sio node-slab-off)))))

;;=============================================================================
;; EveVector deftype — unified via eve/deftype macro.
;; Fields are read from slab header via ISlabIO on each method call.
;;=============================================================================

(eve/deftype ^{:type-id 0x12} EveVector
  [^:int32 cnt ^:int32 shift ^:int32 root ^:int32 tail ^:int32 tail-len]

  clojure.lang.Sequential

  clojure.lang.Counted
  (count [_] #?(:cljs cnt :clj (int cnt)))

  clojure.lang.Seqable
  (seq [_]
    (when (pos? cnt)
      (letfn [(vec-seq [i]
                (when (< i cnt)
                  (lazy-seq
                   (cons (nth-impl sio__ cnt shift root tail i)
                         (vec-seq (inc i))))))]
        (vec-seq 0))))

  clojure.lang.IMeta
  (meta [_] nil)

  clojure.lang.IObj
  (withMeta [this m] this)

  clojure.lang.ILookup
  (valAt [_ k]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      nil))
  (valAt [_ k not-found]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      not-found))

  clojure.lang.Indexed
  (nth [_ n]
    (if (or (neg? n) (>= n cnt))
      (p/throw-index-out-of-bounds (str "Index out of bounds: " n))
      (nth-impl sio__ cnt shift root tail n)))
  (nth [this n not-found]
    (if (or (neg? n) (>= n cnt))
      not-found
      #?(:cljs (-nth this n) :clj (.nth this n))))

  clojure.lang.IPersistentCollection
  (cons [_ val]
    (vec-conj-impl sio__ cnt shift root tail tail-len val))
  (empty [_]
    (let [new-tail (alloc-node! sio__)]
      (make-vec-impl sio__ 0 SHIFT_STEP NIL_OFFSET new-tail 0)))
  (equiv [_ other]
    (cond
      (not (sequential? other)) false
      (not= cnt (count other)) false
      :else
      (loop [i 0]
        (if (>= i cnt)
          true
          (if (= (nth-impl sio__ cnt shift root tail i) (nth other i))
            (recur (inc i))
            false)))))

  clojure.lang.IHashEq
  (hasheq [this] (p/hash-ordered this))

  clojure.lang.IPersistentStack
  (peek [_]
    (when (pos? cnt)
      (nth-impl sio__ cnt shift root tail (dec cnt))))
  (pop [_]
    (if (zero? cnt)
      (p/throw! "Can't pop empty vector")
      (vec-pop-impl sio__ cnt shift root tail tail-len)))

  clojure.lang.IPersistentVector
  (assocN [this n val]
    (cond
      (== n cnt) (#?(:cljs -conj :clj .cons) this val)
      (or (neg? n) (> n cnt))
      (p/throw-index-out-of-bounds (str "Index " n " out of bounds [0," cnt "]"))
      :else
      (vec-assoc-n-impl sio__ cnt shift root tail tail-len n val)))
  #?@(:clj
      [(length [_] (int cnt))
       (entryAt [this i] (when (.containsKey this i) (clojure.lang.MapEntry/create i (.nth this i))))])

  clojure.lang.Associative
  (assoc [this k v]
    (if (integer? k)
      #?(:cljs (-assoc-n this k v)
         :clj  (.assocN this k v))
      (p/throw! "Vector's key for assoc must be a number.")))
  (containsKey [_ k]
    (and (integer? k) (>= k 0) (< k cnt)))

  clojure.lang.IFn
  (invoke [this k] (nth-impl sio__ cnt shift root tail k))
  (invoke [this k not-found]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      not-found))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [i 0 acc init]
      (if (or (>= i cnt) (reduced? acc))
        (unreduced acc)
        (recur (inc i) (f acc (nth-impl sio__ cnt shift root tail i))))))

  ;; CLJS-only: 1-arity reduce (no CLJ equivalent)
  #?@(:cljs [IReduce
             (-reduce [_ f]
               (case (int cnt)
                 0 (f)
                 1 (nth-impl sio__ cnt shift root tail 0)
                 (loop [i 1 acc (nth-impl sio__ cnt shift root tail 0)]
                   (if (>= i cnt)
                     acc
                     (let [val (f acc (nth-impl sio__ cnt shift root tail i))]
                       (if (reduced? val) @val (recur (inc i) val)))))))])

  d/IDirectSerialize
  (d/-direct-serialize [this] offset__)

  d/ISabStorable
  (d/-sab-tag [_] :eve-vec)
  (d/-sab-encode [this _] #?(:cljs (ser/encode-eve-pointer this) :clj offset__))
  (d/-sab-dispose [_ _] nil)

  d/IsEve
  (d/-eve? [_] true)

  d/IEveRoot
  (d/-root-header-off [this] offset__)

  #?@(:cljs [IPrintWithWriter
             (-pr-writer [this writer _opts]
               (-write writer "#eve/vec [")
               (loop [i 0]
                 (when (< i cnt)
                   (when (pos? i) (-write writer " "))
                   (-write writer (pr-str (nth-impl sio__ cnt shift root tail i)))
                   (recur (inc i))))
               (-write writer "]"))])

  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  java.util.List
  (size [_] (int cnt))
  (isEmpty [_] (zero? cnt))
  (get [this ^int i] (.nth this i))
  (indexOf [this o]
    (int (loop [i 0]
      (cond
        (== i cnt) -1
        (.equals ^Object (.nth this i) o) i
        :else (recur (inc i))))))
  (lastIndexOf [this o]
    (int (loop [i (dec cnt)]
      (cond
        (< i 0) -1
        (.equals ^Object (.nth this (int i)) o) i
        :else (recur (dec i))))))
  (toArray [this] (clojure.lang.RT/seqToArray (.seq this)))
  (^boolean contains [this o] (not= -1 (.indexOf this o)))
  (^boolean containsAll [this ^java.util.Collection c]
    (boolean (every? #(.contains this %) c)))
  (^java.util.List subList [this ^int from ^int to]
    (java.util.Collections/unmodifiableList
      (java.util.ArrayList. ^java.util.Collection (subvec (vec this) from to))))
  (^java.util.ListIterator listIterator [this] (.listIterator (java.util.ArrayList. this) 0))
  (^java.util.ListIterator listIterator [this ^int i] (.listIterator (java.util.ArrayList. this) i))
  ;; Unsupported mutators (immutable)
  (set [_ _ _] (p/throw-unsupported))
  (add [_ _ _] (p/throw-unsupported))
  (^boolean add [_ _] (p/throw-unsupported))
  (^boolean remove [_ _] (p/throw-unsupported))
  (addAll [_ _] (p/throw-unsupported))
  (addAll [_ _ _] (p/throw-unsupported))
  (removeAll [_ _] (p/throw-unsupported))
  (retainAll [_ _] (p/throw-unsupported))
  (clear [_] (p/throw-unsupported))
  (^Object remove [_ ^int _] (p/throw-unsupported))

  java.lang.Object
  (toString [this] #?(:clj (pr-str this)))
  (equals [this other]
    #?(:clj (cond
              (identical? this other) true
              (not (instance? clojure.lang.IPersistentVector other)) false
              :else
              (let [ov ^clojure.lang.IPersistentVector other]
                (and (== cnt (.count ov))
                     (loop [i 0]
                       (if (== i cnt) true
                         (if (.equals ^Object (.nth this i) (.nth ov i))
                           (recur (inc i)) false))))))))
  (hashCode [this] #?(:clj (p/hash-ordered this))))

;;=============================================================================
;; Constructor (after deftype so CLJ can resolve EveVector class)
;;=============================================================================

(defn- make-vec-impl
  "Internal constructor: allocate header, create EveVector."
  [sio cnt shift root tail tail-len]
  (let [hdr (write-vec-header! sio cnt shift root tail tail-len)]
    (EveVector. sio hdr)))

(defn vec-from-header
  "Reconstruct an EveVector from an existing header offset."
  [sio header-off]
  (EveVector. sio header-off))

(defn empty-vec
  "Create an empty Eve vector.
   0-arity: uses platform default sio.  1-arity: explicit sio."
  ([] (empty-vec #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*)))
  ([sio]
   (let [tail (alloc-node! sio)]
     (make-vec-impl sio 0 SHIFT_STEP NIL_OFFSET tail 0))))

(defn eve-vec
  "Create an Eve vector from a collection."
  ([coll] (eve-vec #?(:cljs (alloc/->CljsSlabIO) :clj alloc/*jvm-slab-ctx*) coll))
  ([sio coll]
   (reduce conj (empty-vec sio) coll)))

;;=============================================================================
;; Disposal
;;=============================================================================

(defn dispose!
  "Dispose an EveVector, freeing its entire trie tree and tail.
   Call this when the vector is no longer needed to reclaim slab memory.

   WARNING: After disposal, the vector must not be used."
  [eve-vec]
  (let [sio        (#?(:cljs .-sio__ :clj .sio__) #?(:cljs ^js eve-vec :clj eve-vec))
        header-off (#?(:cljs .-offset__ :clj .offset__) #?(:cljs ^js eve-vec :clj eve-vec))
        root-off   (-sio-read-i32 sio header-off SABVECROOT_ROOT_OFFSET)
        tail-off   (-sio-read-i32 sio header-off SABVECROOT_TAIL_OFFSET)
        shift-val  (-sio-read-i32 sio header-off SABVECROOT_SHIFT_OFFSET)]
    ;; Free the trie
    (when (not= root-off NIL_OFFSET)
      (free-trie-node! sio root-off shift-val))
    ;; Free the tail (leaf node with value blocks)
    (when (not= tail-off NIL_OFFSET)
      (free-leaf-node! sio tail-off))
    ;; Free the header block
    (when (and header-off (not= header-off NIL_OFFSET))
      (-sio-free! sio header-off))))

(defn retire-replaced-trie-path!
  "After an atom swap that replaced old-root with new-root, retire the old
   path nodes that are no longer referenced by the new trie.

   Walks both tries following the index bits for the modified index. At each
   level where old-node != new-node, the old node is freed.

   Only retires trie internal/leaf nodes — shared subtrees and value blocks
   are untouched."
  ([sio old-root new-root shift-val idx]
   (retire-replaced-trie-path! sio old-root new-root shift-val idx :free))
  ([sio old-root new-root shift-val idx mode]
   (when (and (not= old-root NIL_OFFSET) (not= old-root new-root))
     (loop [old-off old-root
            new-off new-root
            sh shift-val]
       (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
         ;; Free this old trie node
         (-sio-free! sio old-off)
         ;; Continue down the trie path
         (when (and (pos? sh) (>= idx 0))
           (let [child-idx (bit-and (unsigned-bit-shift-right idx sh) MASK)
                 old-child (node-get sio old-off child-idx)
                 new-child (node-get sio new-off child-idx)]
             (recur old-child new-child (- sh SHIFT_STEP)))))))))

;;=============================================================================
;; ISabRetirable
;;=============================================================================

(extend-type EveVector
  d/ISabRetirable
  (-sab-retire-diff! [this new-value _slab-env mode]
    (let [sio       (#?(:cljs .-sio__ :clj .sio__) this)
          hdr       (#?(:cljs .-offset__ :clj .offset__) this)
          old-root  (-sio-read-i32 sio hdr SABVECROOT_ROOT_OFFSET)
          old-shift (-sio-read-i32 sio hdr SABVECROOT_SHIFT_OFFSET)]
      (if (instance? EveVector new-value)
        ;; Both are EveVector — diff trie paths
        (let [new-hdr      (#?(:cljs .-offset__ :clj .offset__) new-value)
              new-root-off (-sio-read-i32 sio new-hdr SABVECROOT_ROOT_OFFSET)]
          (retire-replaced-trie-path! sio old-root new-root-off old-shift -1 mode))
        ;; Different type — dispose entire old trie
        (dispose! this)))))

;;=============================================================================
;; Backward-compat aliases
;;=============================================================================

(def empty-sab-vec empty-vec)
(def sab-vec eve-vec)

;;=============================================================================
;; Registration — via register-eve-type! macro
;;=============================================================================

;; CLJ collection writer (too custom for macro generation)
#?(:clj
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
                 tail-off (let [t-off (alloc-node! sio)]
                            (dorun (map-indexed
                                     (fn [i v-off]
                                       (-sio-write-i32! sio t-off (* i 4) v-off))
                                     (subvec val-offs toff cnt)))
                            t-off)
                 trie-val-offs (subvec val-offs 0 toff)
                 [root sft]
                 (if (empty? trie-val-offs)
                   [NIL_OFFSET SHIFT_STEP]
                   (let [leaf-nodes
                         (mapv (fn [chunk]
                                 (let [node-off (alloc-node! sio)]
                                   (dorun (map-indexed
                                            (fn [i v-off]
                                              (-sio-write-i32! sio node-off (* i 4) v-off))
                                            chunk))
                                   node-off))
                               (partition-all NODE_SIZE trie-val-offs))]
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
             (write-vec-header! sio cnt sft root tail-off tail-len)))))))

;; Type constructor + disposer + builder registrations
(eve/register-eve-type!
  {:fast-tag    ser/FAST_TAG_SAB_VEC
   :type-id     EveVector-type-id
   :from-header vec-from-header
   :dispose     dispose!
   :builder-pred vector?
   :builder-ctor eve-vec
   :print-fn    #?(:clj (fn [] (defmethod print-method EveVector [v ^java.io.Writer w]
                                  (#'clojure.core/print-sequential "[" #'clojure.core/pr-on " " "]" (seq v) w)))
                   :cljs nil)})

;; Backward-compat JVM aliases
#?(:clj
   (do
     (defn jvm-write-vec!
       "Serialize a Clojure vector to slab. Returns header offset.
        Backward-compat alias for the registered :vec writer."
       [sio serialize-val coll]
       (mem/jvm-write-collection! :vec sio coll))

     (defn jvm-sabvec-from-offset
       "Reconstruct an EveVector from a header offset.
        Backward-compat alias. coll-factory arg is ignored (registry-based)."
       ([sio header-off] (vec-from-header sio header-off))
       ([sio header-off _coll-factory] (vec-from-header sio header-off)))

     (def SabVecRoot EveVector)))

;; No-op pool stub — pool system removed, kept for backward compat
#?(:cljs (defn reset-pools! [] nil))
