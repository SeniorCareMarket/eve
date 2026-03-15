(ns eve.tensor
  "N-dimensional views over EveArrays. Shape/strides for zero-copy reshaping,
   transposing, and slicing.

   Built with eve-deftype — lives inside Eve atoms, backed by slab memory.

   Construction (inside swap!):
     (from-array arr [3 4])              ;; wrap 12-elem EveArray as 3×4 matrix
     (zeros :float64 [3 4])             ;; allocate + fill
     (ones :int32 [2 3 4])              ;; allocate + fill

   Shape ops (zero-copy — same backing EveArray):
     (reshape t [4 3])                   ;; new shape, same data
     (transpose t)                       ;; reverse strides
     (slice-axis t 0 1)                  ;; select row 1 → rank-1 view

   Element access:
     (mget t 1 2)                        ;; get element at [1,2]
     (mset! t 1 2 42.0)                  ;; set element at [1,2]"
  (:refer-clojure :exclude [to-array])
  (:require
   [eve.array :as arr]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   #?@(:cljs [[eve.deftype-proto.alloc :as alloc]
              [eve.deftype.slab-runtime :as slab-rt]]
       :clj  [[eve.deftype :refer [eve-deftype]]
              [eve.deftype.slab-runtime :as slab-rt]]))
  #?(:cljs (:require-macros [eve.deftype :refer [eve-deftype]])))

;; Forward declarations
(declare flat-get)

;;-----------------------------------------------------------------------------
;; Protocol for accessing NDBuffer internals from standalone functions
;;-----------------------------------------------------------------------------

(defprotocol ITensorAccess
  (-data [t] "Return backing EveArray.")
  (-shape [t] "Return shape vector.")
  (-strides [t] "Return strides vector.")
  (-elem-offset [t] "Return element offset."))

;;-----------------------------------------------------------------------------
;; NDBuffer type — eve-deftype backed by slab memory
;;-----------------------------------------------------------------------------

(eve-deftype NDBuffer [^:int32 elem-offset
                       data     ;; serialized: backing EveArray
                       shape    ;; serialized: vector of ints
                       strides] ;; serialized: vector of ints

  ITensorAccess
  (-data [_] data)
  (-shape [_] shape)
  (-strides [_] strides)
  (-elem-offset [_] elem-offset)

  #?@(:cljs
      [Object
       (toString [_]
         (str "#eve/tensor " (pr-str shape)
              " " (arr/subtype->type-kw (arr/array-subtype-code data))))

       ICounted
       (-count [_]
         (reduce * 1 shape))

       IHash
       (-hash [this]
         (hash [shape (vec (take 100 (seq this)))]))

       IEquiv
       (-equiv [this other]
         (and (instance? NDBuffer other)
              (= shape (-shape other))
              (every? true? (map = (seq this) (seq other)))))

       ISeqable
       (-seq [this]
         (let [n (reduce * 1 shape)]
           (when (pos? n)
             (map (fn [flat-idx] (flat-get this flat-idx)) (range n)))))

       IPrintWithWriter
       (-pr-writer [this writer _opts]
         (-write writer (str this)))]

      :clj
      [clojure.lang.Counted
       (count [_]
         (reduce * 1 shape))

       clojure.lang.IHashEq
       (hasheq [this]
         (hash [shape (vec (take 100 (seq this)))]))

       clojure.lang.Seqable
       (seq [this]
         (let [n (reduce * 1 shape)]
           (when (pos? n)
             (map (fn [flat-idx] (flat-get this flat-idx)) (range n)))))

       java.lang.Iterable
       (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

       java.lang.Object
       (equals [this other]
         (and (instance? NDBuffer other)
              (= shape (-shape other))
              (every? true? (map = (seq this) (seq other)))))
       (hashCode [this] (.hasheq this))
       (toString [_]
         (str "#eve/tensor " (pr-str shape)
              " " (arr/subtype->type-kw (arr/array-subtype-code data))))]))

;;-----------------------------------------------------------------------------
;; Internal helpers
;;-----------------------------------------------------------------------------

(defn- compute-c-strides
  "Compute C-order (row-major) strides from shape."
  [shape]
  (let [rank (count shape)]
    (if (zero? rank)
      []
      (loop [i (dec rank)
             stride 1
             result (transient [])]
        (if (< i 0)
          (vec (reverse (persistent! result)))
          (recur (dec i)
                 (* stride (nth shape i))
                 (conj! result stride)))))))

(defn- multi-idx->flat
  "Convert multi-dimensional index to flat index."
  [strides elem-offset indices]
  (loop [i 0 flat elem-offset]
    (if (< i (count indices))
      (recur (inc i) (+ flat (* (nth strides i) (nth indices i))))
      flat)))

(defn- flat-idx->multi
  "Convert a linear iteration index to multi-dimensional index,
   then to flat data index via strides."
  [shape strides elem-offset flat-iter-idx]
  (let [rank (count shape)]
    (loop [r (dec rank)
           remaining flat-iter-idx
           flat elem-offset]
      (if (< r 0)
        flat
        (let [dim (nth shape r)
              idx (rem remaining dim)]
          (recur (dec r)
                 (quot remaining dim)
                 (+ flat (* (nth strides r) idx))))))))

(defn- flat-get
  "Get element at a flat iteration index."
  [t flat-iter-idx]
  (let [sh (-shape t)
        st (-strides t)
        eo (-elem-offset t)
        data-idx (flat-idx->multi sh st eo flat-iter-idx)]
    (nth (-data t) data-idx)))

;;-----------------------------------------------------------------------------
;; Predicates
;;-----------------------------------------------------------------------------

(defn tensor?
  "True if x is an NDBuffer (tensor)."
  [x]
  (instance? NDBuffer x))

;;-----------------------------------------------------------------------------
;; Construction
;;-----------------------------------------------------------------------------

(defn from-array
  "Wrap an EveArray as an NDBuffer with given shape.
   The product of shape dimensions must equal array length."
  [arr shape]
  (let [n (reduce * 1 shape)]
    (when (not= n (count arr))
      (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
              (str "Shape " shape " requires " n " elements but array has " (count arr)))))
    (->NDBuffer 0 arr (vec shape) (compute-c-strides shape))))

(defn zeros
  "Create a zero-filled tensor."
  [type-kw shape]
  (let [n (reduce * 1 shape)]
    (from-array (arr/eve-array type-kw n) shape)))

(defn ones
  "Create a tensor filled with 1."
  [type-kw shape]
  (let [n (reduce * 1 shape)
        a (arr/eve-array type-kw n 1)]
    (from-array a shape)))

;;-----------------------------------------------------------------------------
;; Accessors
;;-----------------------------------------------------------------------------

(defn shape
  "Return the shape vector."
  [t]
  (-shape t))

(defn dtype
  "Return the element type keyword."
  [t]
  (arr/subtype->type-kw (arr/array-subtype-code (-data t))))

(defn rank
  "Return the number of dimensions."
  [t]
  (count (-shape t)))

(defn contiguous?
  "True if the tensor has C-order (row-major) strides with no gaps."
  [t]
  (= (-strides t) (compute-c-strides (-shape t))))

;;-----------------------------------------------------------------------------
;; Element access
;;-----------------------------------------------------------------------------

(defn mget
  "Get element at multi-dimensional index."
  [t & indices]
  (let [flat (multi-idx->flat (-strides t) (-elem-offset t) indices)]
    (nth (-data t) flat)))

(defn mset!
  "Set element at multi-dimensional index. Mutates the backing array."
  [t & args]
  (let [indices (butlast args)
        val (last args)
        flat (multi-idx->flat (-strides t) (-elem-offset t) indices)]
    (arr/aset! (-data t) flat val)
    val))

;;-----------------------------------------------------------------------------
;; Shape operations (zero-copy — same backing EveArray)
;;-----------------------------------------------------------------------------

(defn reshape
  "Return a new tensor with different shape, same data.
   Requires contiguous layout. Product of new shape must equal product of old."
  [t new-shape]
  (when-not (contiguous? t)
    (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
            "Cannot reshape non-contiguous tensor")))
  (let [old-n (reduce * 1 (-shape t))
        new-n (reduce * 1 new-shape)]
    (when (not= old-n new-n)
      (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
              (str "Cannot reshape " (-shape t) " to " new-shape
                   ": element count mismatch"))))
    (->NDBuffer (-elem-offset t) (-data t) (vec new-shape) (compute-c-strides new-shape))))

(defn transpose
  "Transpose: reverse axis order (zero-copy).
   With explicit perm vector, permute axes in that order."
  ([t]
   (let [sh (-shape t)
         st (-strides t)]
     (->NDBuffer (-elem-offset t) (-data t) (vec (reverse sh)) (vec (reverse st)))))
  ([t perm]
   (let [sh (-shape t)
         st (-strides t)]
     (->NDBuffer (-elem-offset t) (-data t)
                 (vec (map #(nth sh %) perm))
                 (vec (map #(nth st %) perm))))))

(defn slice-axis
  "Select a single index along an axis, reducing rank by 1.
   E.g., (slice-axis matrix 0 2) selects row 2 → returns a 1D tensor."
  [t axis idx]
  (let [sh (-shape t)
        st (-strides t)
        new-offset (+ (-elem-offset t) (* (nth st axis) idx))
        new-shape (vec (concat (subvec sh 0 axis)
                               (subvec sh (inc axis))))
        new-strides (vec (concat (subvec st 0 axis)
                                  (subvec st (inc axis))))]
    (->NDBuffer new-offset (-data t) new-shape new-strides)))

;;-----------------------------------------------------------------------------
;; Bulk operations
;;-----------------------------------------------------------------------------

(defn emap
  "Element-wise operation, returning a new contiguous tensor.
   f takes elements from one or more tensors."
  ([f t]
   (let [n (reduce * 1 (-shape t))
         type-kw (dtype t)
         out-arr (arr/eve-array type-kw n)]
     (dotimes [i n]
       (arr/aset! out-arr i (f (flat-get t i))))
     (from-array out-arr (-shape t))))
  ([f t1 t2]
   (let [n (reduce * 1 (-shape t1))
         type-kw (dtype t1)
         out-arr (arr/eve-array type-kw n)]
     (dotimes [i n]
       (arr/aset! out-arr i (f (flat-get t1 i) (flat-get t2 i))))
     (from-array out-arr (-shape t1)))))

(defn ereduce
  "Reduce all elements of the tensor."
  [f init t]
  (let [n (reduce * 1 (-shape t))]
    (loop [i 0 acc init]
      (if (< i n)
        (recur (inc i) (f acc (flat-get t i)))
        acc))))

;;-----------------------------------------------------------------------------
;; Materialization
;;-----------------------------------------------------------------------------

(defn to-array
  "Flatten tensor to a new contiguous EveArray."
  [t]
  (let [n (reduce * 1 (-shape t))
        type-kw (dtype t)
        out (arr/eve-array type-kw n)]
    (dotimes [i n]
      (arr/aset! out i (flat-get t i)))
    out))

(defn to-dataset
  "Convert a 2D tensor to a Dataset with given column names.
   Requires exactly rank-2."
  [t col-names]
  (let [sh (-shape t)]
    (when (not= 2 (count sh))
      (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
              "to-dataset requires a 2D tensor")))
    (let [nrows (first sh)
          ncols (second sh)
          _ (when (not= ncols (count col-names))
              (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
                      (str "Expected " ncols " column names, got " (count col-names)))))
          type-kw (dtype t)
          cols (into {} (map (fn [c]
                               (let [col-arr (arr/eve-array type-kw nrows)]
                                 (dotimes [r nrows]
                                   (arr/aset! col-arr r (apply mget t [r c])))
                                 [(nth col-names c) col-arr]))
                             (range ncols)))]
      ;; Return the column map — caller can wrap with (eve.dataset/dataset ...)
      cols)))
