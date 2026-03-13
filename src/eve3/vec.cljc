(ns eve3.vec
  "Eve3 persistent vector — unified CLJ/CLJS implementation.

   Bit-partitioned trie with tail optimization (Bagwell/Hickey vector).
   Uses ISlabIO protocol for all memory access, sio threaded everywhere.

   Uses eve3/deftype macro: CLJ protocol names, auto-translated on CLJS."
  (:require
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO -sio-read-u8 -sio-read-i32
            -sio-read-bytes -sio-write-u8! -sio-write-i32!
            -sio-write-bytes! -sio-alloc! -sio-free!
            -sio-copy-block! NIL_OFFSET]]
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

(declare make-eve3-vec)
(declare make-eve3-vec-impl)

;;=============================================================================
;; Conj / AssocN / Pop implementations
;;=============================================================================

(defn- vec-conj-impl [sio cnt shift root tail tail-len v]
  (let [val-bytes (serialize-element-bytes v)
        val-off (make-value-block! sio val-bytes)]
    (if (< tail-len NODE_SIZE)
      (let [new-tail (clone-node! sio tail)]
        (node-set! sio new-tail tail-len val-off)
        (make-eve3-vec-impl sio (inc cnt) shift root new-tail (inc tail-len)))
      (let [old-tail tail
            new-tail (alloc-node! sio)
            _ (node-set! sio new-tail 0 val-off)]
        (if (> (unsigned-bit-shift-right cnt SHIFT_STEP)
               (bit-shift-left 1 shift))
          (let [new-root-off (alloc-node! sio)
                _ (node-set! sio new-root-off 0 root)
                new-shift (+ shift SHIFT_STEP)
                _ (node-set! sio new-root-off 1 (new-path sio shift old-tail))]
            (make-eve3-vec-impl sio (inc cnt) new-shift new-root-off new-tail 1))
          (let [new-root (if (== root NIL_OFFSET)
                           (let [nr (alloc-node! sio)]
                             (node-set! sio nr 0 old-tail)
                             nr)
                           (push-tail sio shift root old-tail cnt))]
            (make-eve3-vec-impl sio (inc cnt) shift new-root new-tail 1)))))))

(defn- vec-assoc-n-impl [sio cnt shift root tail tail-len n v]
  (let [val-bytes (serialize-element-bytes v)
        val-off (make-value-block! sio val-bytes)
        toff (tail-offset-calc cnt)]
    (if (>= n toff)
      (let [new-tail (clone-node! sio tail)]
        (node-set! sio new-tail (- n toff) val-off)
        (make-eve3-vec-impl sio cnt shift root new-tail tail-len))
      (let [new-root (do-assoc sio shift root n val-off)]
        (make-eve3-vec-impl sio cnt shift new-root tail tail-len)))))

(defn- vec-pop-impl [sio cnt shift root tail tail-len]
  (cond
    (== cnt 1)
    (let [new-tail (alloc-node! sio)]
      (make-eve3-vec-impl sio 0 SHIFT_STEP NIL_OFFSET new-tail 0))

    (> tail-len 1)
    (let [new-tail (alloc-node! sio)]
      (dotimes [i (dec tail-len)]
        (node-set! sio new-tail i (node-get sio tail i)))
      (make-eve3-vec-impl sio (dec cnt) shift root new-tail (dec tail-len)))

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
      (make-eve3-vec-impl sio new-cnt new-shift new-root new-tail-off new-tl))))

;;=============================================================================
;; EveVector deftype — plain deftype with cached fields for performance.
;; Fields are read from slab header once at construction time, not on every access.
;;=============================================================================

(deftype EveVector [sio__ offset__
                    #?@(:clj [^int cnt ^int shift])
                    #?@(:cljs [cnt shift])
                    root tail
                    #?@(:clj [^int tail-len])
                    #?@(:cljs [tail-len])]

  #?@(:cljs [ISequential])
  #?(:clj clojure.lang.Sequential)

  #?(:cljs ICounted :clj clojure.lang.Counted)
  (#?(:cljs -count :clj count) [_] #?(:cljs cnt :clj (int cnt)))

  #?(:cljs ISeqable :clj clojure.lang.Seqable)
  (#?(:cljs -seq :clj seq) [_]
    (when (pos? cnt)
      #?(:cljs
         ((fn iter [i]
            (lazy-seq
             (when (< i cnt)
               (cons (nth-impl sio__ cnt shift root tail i)
                     (iter (inc i))))))
          0)
         :clj
         (letfn [(vec-seq [i]
                   (when (< i cnt)
                     (lazy-seq
                      (clojure.core/cons
                       (nth-impl sio__ cnt shift root tail i)
                       (vec-seq (inc i))))))]
           (vec-seq 0)))))

  #?(:cljs IMeta :clj clojure.lang.IMeta)
  (#?(:cljs -meta :clj meta) [_] nil)

  #?(:cljs IWithMeta :clj clojure.lang.IObj)
  (#?(:cljs -with-meta :clj withMeta) [this m] this)

  #?(:cljs ILookup :clj clojure.lang.ILookup)
  (#?(:cljs -lookup :clj valAt) [_ k]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      nil))
  (#?(:cljs -lookup :clj valAt) [_ k not-found]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      not-found))

  #?(:cljs IIndexed :clj clojure.lang.Indexed)
  (#?(:cljs -nth :clj nth) [_ n]
    (if (or (neg? n) (>= n cnt))
      (throw (#?(:cljs js/Error. :clj IndexOutOfBoundsException.)
              (str "Index out of bounds: " n)))
      (nth-impl sio__ cnt shift root tail n)))
  (#?(:cljs -nth :clj nth) [this n not-found]
    (if (or (neg? n) (>= n cnt))
      not-found
      #?(:cljs (-nth this n) :clj (.nth this n))))

  #?(:cljs ICollection :clj clojure.lang.IPersistentCollection)
  (#?(:cljs -conj :clj cons) [_ val]
    (vec-conj-impl sio__ cnt shift root tail tail-len val))
  (#?(:cljs -empty :clj empty) [_]
    (let [new-tail (alloc-node! sio__)]
      (make-eve3-vec-impl sio__ 0 SHIFT_STEP NIL_OFFSET new-tail 0)))
  (#?(:cljs -equiv :clj equiv) [_ other]
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

  #?(:cljs IHash :clj clojure.lang.IHashEq)
  (#?(:cljs -hash :clj hasheq) [this]
    #?(:cljs (hash-ordered-coll this)
       :clj (clojure.lang.Murmur3/hashOrdered this)))

  #?(:cljs IStack :clj clojure.lang.IPersistentStack)
  (#?(:cljs -peek :clj peek) [_]
    (when (pos? cnt)
      (nth-impl sio__ cnt shift root tail (dec cnt))))
  (#?(:cljs -pop :clj pop) [_]
    (if (zero? cnt)
      (throw (#?(:cljs js/Error. :clj IllegalStateException.)
              "Can't pop empty vector"))
      (vec-pop-impl sio__ cnt shift root tail tail-len)))

  #?(:cljs IVector :clj clojure.lang.IPersistentVector)
  (#?(:cljs -assoc-n :clj assocN) [this n val]
    (cond
      (== n cnt) (#?(:cljs -conj :clj .cons) this val)
      (or (neg? n) (> n cnt))
      (throw (#?(:cljs js/Error. :clj IndexOutOfBoundsException.)
              (str "Index " n " out of bounds [0," cnt "]")))
      :else
      (vec-assoc-n-impl sio__ cnt shift root tail tail-len n val)))

  #?(:cljs IAssociative :clj clojure.lang.Associative)
  (#?(:cljs -assoc :clj assoc) [this k v]
    (if (integer? k)
      #?(:cljs (-assoc-n this k v)
         :clj  (.assocN this k v))
      (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
              "Vector's key for assoc must be a number."))))
  (#?(:cljs -contains-key? :clj containsKey) [_ k]
    (and (integer? k) (>= k 0) (< k cnt)))

  #?(:cljs IFn :clj clojure.lang.IFn)
  (#?(:cljs -invoke :clj invoke) [this k] (nth-impl sio__ cnt shift root tail k))
  (#?(:cljs -invoke :clj invoke) [this k not-found]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      not-found))

  #?@(:cljs [IReduce
             (-reduce [_ f]
               (case (int cnt)
                 0 (f)
                 1 (nth-impl sio__ cnt shift root tail 0)
                 (loop [i 1 acc (nth-impl sio__ cnt shift root tail 0)]
                   (if (>= i cnt)
                     acc
                     (let [val (f acc (nth-impl sio__ cnt shift root tail i))]
                       (if (reduced? val) @val (recur (inc i) val)))))))
             (-reduce [_ f init]
               (loop [i 0 acc init]
                 (if (or (>= i cnt) (reduced? acc))
                   (if (reduced? acc) @acc acc)
                   (recur (inc i) (f acc (nth-impl sio__ cnt shift root tail i))))))])

  #?@(:clj [clojure.lang.IReduceInit
             (reduce [_ f init]
               (loop [i 0 acc init]
                 (if (or (>= i cnt) (reduced? acc))
                   (unreduced acc)
                   (recur (inc i) (f acc (nth-impl sio__ cnt shift root tail i))))))])

  d/IDirectSerialize
  (d/-direct-serialize [this]
    #?(:cljs (ser/encode-sab-pointer ser/FAST_TAG_SAB_VEC offset__)
       :clj offset__))

  d/ISabStorable
  (d/-sab-tag [_] :eve-vec)
  (d/-sab-encode [this _] (d/-direct-serialize this))
  (d/-sab-dispose [_ _] nil)

  d/IsEve
  (d/-eve? [_] true)

  d/IEveRoot
  (d/-root-header-off [this] offset__)

  #?@(:cljs [IPrintWithWriter
             (-pr-writer [this writer _opts]
               (-write writer "#eve3/vec [")
               (loop [i 0]
                 (when (< i cnt)
                   (when (pos? i) (-write writer " "))
                   (-write writer (pr-str (nth-impl sio__ cnt shift root tail i)))
                   (recur (inc i))))
               (-write writer "]"))])

  ;; --- CLJ-only interfaces ---
  #?@(:clj
      [clojure.lang.IPersistentVector
       (length [_] (int cnt))
       (entryAt [this i] (when (.containsKey this i) (clojure.lang.MapEntry/create i (.nth this i))))

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
                  (loop [i 0]
                    (if (== i cnt) true
                      (if (.equals ^Object (.nth this i) (.nth ov i))
                        (recur (inc i)) false)))))))
       (hashCode [this] (clojure.lang.Murmur3/hashOrdered this))]))

;;=============================================================================
;; Constructor (after deftype so CLJ can resolve EveVector class)
;;=============================================================================

(defn- make-eve3-vec-impl [sio cnt shift root tail tail-len]
  (let [hdr (write-vec-header! sio cnt shift root tail tail-len)]
    (EveVector. sio hdr cnt shift root tail tail-len)))

(defn- make-eve3-vec [sio hdr]
  (let [[cnt shift root tail tail-len] (read-vec-header sio hdr)]
    (EveVector. sio hdr cnt shift root tail tail-len)))

(defn eve3-vec-from-header [sio header-off]
  (make-eve3-vec sio header-off))

(defn empty-vec [sio]
  (let [tail (alloc-node! sio)]
    (make-eve3-vec-impl sio 0 SHIFT_STEP NIL_OFFSET tail 0)))

(defn eve3-vec [sio coll]
  (reduce conj (empty-vec sio) coll))

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
               (write-vec-header! sio cnt sft root tail-off tail-len))))))

     (ser/register-jvm-type-constructor! SabVecRoot-type-id
       (fn [header-off]
         (eve3-vec-from-header alloc/*jvm-slab-ctx* header-off)))

     (defmethod print-method EveVector [v ^java.io.Writer w]
       (#'clojure.core/print-sequential "[" #'clojure.core/pr-on " " "]" (seq v) w))))
