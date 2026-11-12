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
  (:refer-clojure :exclude [to-array
                            aget aset unchecked-add-int unchecked-multiply-int
                            unchecked-inc-int long double volatile! vswap! transient
                            persistent! conj! doubles])
  (:require
   [eve.host :refer [aget aset unchecked-add-int unchecked-multiply-int unchecked-inc-int
                     long double volatile! vswap! transient persistent! conj! doubles]]
   [eve.core :as e]
   [eve.array :as arr]
   [eve.deftype-proto.data :as d]
   [eve.encoding :as enc]
   [eve.deftype-proto.serialize :as ser]
   [eve.deftype :refer [eve-deftype]]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype.slab-runtime :as slab-rt]))

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
                       data ;; serialized: backing EveArray
                       shape ;; serialized: vector of ints
                       strides] ;; serialized: vector of ints

             ITensorAccess
             (-data [_] data)
             (-shape [_] shape)
             (-strides [_] strides)
             (-elem-offset [_] elem-offset)

             d/IPlain
             (-plain [_]
               (let [sh (vec shape)
                     st (vec strides)
                     eo (eve.host/long elem-offset)
                     backing (d/eve->clj data)]
                 (letfn [(build [dim base]
                           (if (= dim (e/count sh))
                             (e/nth backing base)
                             (let [stride (e/nth st dim)
                                   n (e/nth sh dim)]
                               (mapv (fn [i] (build (inc dim) (+ base (* i stride))))
                                     (range n)))))]
                   (if (empty? sh)
                     (e/nth backing eo)
                     (build 0 eo)))))

             clojure.lang.Counted
             (count [_]
               (reduce * 1 shape))

             clojure.lang.IHashEq
             (hasheq [this]
               (hash [shape (vec (take 100 (e/seq this)))]))

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
                    (every? true? (map = (e/seq this) (e/seq other)))))
             (hashCode [this] (.hasheq this))
             (toString [_]
               (str "#eve/tensor " (pr-str shape)
                    " " (arr/subtype->type-kw (arr/array-subtype-code data)))))

;;-----------------------------------------------------------------------------
;; Internal helpers
;;-----------------------------------------------------------------------------

(defn- compute-c-strides
  "Compute C-order (row-major) strides from shape."
  [shape]
  (let [rank (e/count shape)]
    (if (zero? rank)
      []
      (loop [i (dec rank)
             stride 1
             result (transient [])]
        (if (< i 0)
          (vec (reverse (persistent! result)))
          (recur (dec i)
                 (* stride (e/nth shape i))
                 (conj! result stride)))))))

(defn- multi-idx->flat
  "Convert multi-dimensional index to flat index."
  [strides elem-offset indices]
  (loop [i 0 flat elem-offset]
    (if (< i (e/count indices))
      (recur (inc i) (+ flat (* (e/nth strides i) (e/nth indices i))))
      flat)))

(defn- flat-idx->multi
  "Convert a linear iteration index to multi-dimensional index,
   then to flat data index via strides."
  [shape strides elem-offset flat-iter-idx]
  (let [rank (e/count shape)]
    (loop [r (dec rank)
           remaining flat-iter-idx
           flat elem-offset]
      (if (< r 0)
        flat
        (let [dim (e/nth shape r)
              idx (rem remaining dim)]
          (recur (dec r)
                 (quot remaining dim)
                 (+ flat (* (e/nth strides r) idx))))))))

(defn- flat-get
  "Get element at a flat iteration index."
  [t flat-iter-idx]
  (let [sh (-shape t)
        st (-strides t)
        eo (-elem-offset t)
        data-idx (flat-idx->multi sh st eo flat-iter-idx)]
    (e/nth (-data t) data-idx)))

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

(defn- default-sio
  "The slab I/O a constructor given none allocates in: the running swap!'s
   domain; outside a swap/domain context it throws (:no-domain-context)."
  []
  (alloc/current-sio))

(defn- ->ndbuffer-in
  "->NDBuffer allocating in t's own slab domain (JVM, bb) / the module slab
   (CLJS, whose constructor takes the sio and allocates there). Shape ops use
   this so derived tensors stay in the same domain."
  [t elem-offset data shape strides]
  (->NDBuffer (d/-eve-sio t) elem-offset data shape strides))

(defn- array-domain-sio
  "sio for an NDBuffer wrapping arr: the array's own domain when it is a
   slab-backed Eve value that carries one, else the current one (a
   ClojureScript instance carries none: its domain is the module slab)."
  [arr]
  (or (d/-eve-sio arr) (alloc/current-sio)))

(defn from-array
  "Wrap an EveArray as an NDBuffer with given shape.
   The product of shape dimensions must equal array length.

   ([arr shape]) uses the array's own slab domain when it is slab-backed
   (JVM), else a fresh heap domain. ([sio arr shape]) allocates in sio's
   domain — pass the atom's domain sio (eve.atom/atom-sio) when the tensor
   will be stored in an atom."
  ([arr shape] (from-array (array-domain-sio arr) arr shape))
  ([sio arr shape]
   (let [n (reduce * 1 shape)]
     (when (not= n (e/count arr))
       (throw (enc/illegal-argument
               (str "Shape " shape " requires " n " elements but array has " (e/count arr)))))
     (->NDBuffer sio 0 arr (vec shape) (compute-c-strides shape)))))

(defn zeros
  "Create a zero-filled tensor.
   ([type-kw shape]) uses a fresh heap domain (JVM). ([sio type-kw shape])
   allocates in sio's domain — use the atom's domain sio for atom storage."
  ([type-kw shape] (zeros (default-sio) type-kw shape))
  ([sio type-kw shape]
   (let [n (reduce * 1 shape)]
     (from-array sio (arr/eve-array type-kw n) shape))))

(defn ones
  "Create a tensor filled with 1.
   ([type-kw shape]) uses a fresh heap domain (JVM). ([sio type-kw shape])
   allocates in sio's domain — use the atom's domain sio for atom storage."
  ([type-kw shape] (ones (default-sio) type-kw shape))
  ([sio type-kw shape]
   (let [n (reduce * 1 shape)
         a (arr/eve-array type-kw n 1)]
     (from-array sio a shape))))

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
  (e/count (-shape t)))

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
    (e/nth (-data t) flat)))

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
    (throw (enc/illegal-argument
            "Cannot reshape non-contiguous tensor")))
  (let [old-n (reduce * 1 (-shape t))
        new-n (reduce * 1 new-shape)]
    (when (not= old-n new-n)
      (throw (enc/illegal-argument
              (str "Cannot reshape " (-shape t) " to " new-shape
                   ": element count mismatch"))))
    (->ndbuffer-in t (-elem-offset t) (-data t) (vec new-shape) (compute-c-strides new-shape))))

(defn transpose
  "Transpose: reverse axis order (zero-copy).
   With explicit perm vector, permute axes in that order."
  ([t]
   (let [sh (-shape t)
         st (-strides t)]
     (->ndbuffer-in t (-elem-offset t) (-data t) (vec (reverse sh)) (vec (reverse st)))))
  ([t perm]
   (let [sh (-shape t)
         st (-strides t)]
     (->ndbuffer-in t (-elem-offset t) (-data t)
                   (vec (map #(e/nth sh %) perm))
                   (vec (map #(e/nth st %) perm))))))

(defn slice-axis
  "Select a single index along an axis, reducing rank by 1.
   E.g., (slice-axis matrix 0 2) selects row 2 → returns a 1D tensor."
  [t axis idx]
  (let [sh (-shape t)
        st (-strides t)
        new-offset (+ (-elem-offset t) (* (e/nth st axis) idx))
        new-shape (vec (concat (subvec sh 0 axis)
                               (subvec sh (inc axis))))
        new-strides (vec (concat (subvec st 0 axis)
                                 (subvec st (inc axis))))]
    (->ndbuffer-in t new-offset (-data t) new-shape new-strides)))







(defn- strided-read
  "Read element at flat iteration index using stride translation.
   Uses direct typed-view access (no nth protocol dispatch)."
  [data-arr shape strides elem-offset flat-iter-idx]
  (e/nth data-arr (flat-idx->multi shape strides elem-offset flat-iter-idx)))



;;-----------------------------------------------------------------------------
;; Bulk operations
;;-----------------------------------------------------------------------------

(defn emap
  "Element-wise operation, returning a new contiguous tensor.
   f takes elements from one or more tensors."
  ([f t]
   (let [sh (-shape t)
         n (reduce * 1 sh)
         type-kw (dtype t)]
     (from-array
      (if-let [src (d/-as-double-array (-data t))]
        (let [eo (int (-elem-offset t))
              dst (eve.host/double-array n)]
          (if (contiguous? t)
            (dotimes [i n] (aset dst i (double (f (aget src (+ eo i))))))
            (let [st (-strides t)]
              (if (== 2 (e/count sh))
                (let [rows (int (e/nth sh 0)) cols (int (e/nth sh 1))
                      rs   (int (e/nth st 0))  cs   (int (e/nth st 1))]
                  (loop [r (int 0) out-i (int 0)]
                    (when (< r rows)
                      (let [row-start (unchecked-add-int eo (unchecked-multiply-int r rs))]
                        (loop [c (int 0)]
                          (when (< c cols)
                            (aset dst (unchecked-add-int out-i c)
                                  (double (f (aget src (unchecked-add-int row-start (unchecked-multiply-int c cs))))))
                            (recur (unchecked-inc-int c)))))
                      (recur (unchecked-inc-int r) (unchecked-add-int out-i cols)))))
                (dotimes [i n]
                  (aset dst i (double (f (aget src (int (flat-idx->multi sh st eo i))))))))))
          (arr/from-double-array dst))
        (let [out-arr (arr/eve-array type-kw n)]
          (dotimes [i n]
            (arr/aset! out-arr i (f (flat-get t i))))
          out-arr))
      sh)))
  ([f t1 t2]
   (let [sh (-shape t1)
         n (reduce * 1 sh)
         type-kw (dtype t1)]
     (from-array
      (let [s1 (d/-as-double-array (-data t1))
            s2 (d/-as-double-array (-data t2))]
        (if (and s1 s2)
          (let [dst (eve.host/double-array n)
                eo1 (int (-elem-offset t1)) eo2 (int (-elem-offset t2))]
            (if (and (contiguous? t1) (contiguous? t2))
              (dotimes [i n]
                (aset dst i (double (f (aget s1 (+ eo1 i)) (aget s2 (+ eo2 i))))))
              (let [st1 (-strides t1) st2 (-strides t2) sh2 (-shape t2)]
                (if (and (== 2 (e/count sh)) (== 2 (e/count sh2)))
                  ;; 2D fast path
                  (let [rows (int (e/nth sh 0)) cols (int (e/nth sh 1))
                        rs1  (int (e/nth st1 0)) cs1  (int (e/nth st1 1))
                        rs2  (int (e/nth st2 0)) cs2  (int (e/nth st2 1))]
                    (loop [r (int 0) out-i (int 0)]
                      (when (< r rows)
                        (let [base1 (unchecked-add-int eo1 (unchecked-multiply-int r rs1))
                              base2 (unchecked-add-int eo2 (unchecked-multiply-int r rs2))]
                          (loop [c (int 0)]
                            (when (< c cols)
                              (aset dst (unchecked-add-int out-i c)
                                    (double (f (aget s1 (unchecked-add-int base1 (unchecked-multiply-int c cs1)))
                                               (aget s2 (unchecked-add-int base2 (unchecked-multiply-int c cs2))))))
                              (recur (unchecked-inc-int c)))))
                        (recur (unchecked-inc-int r) (unchecked-add-int out-i cols)))))
                  (dotimes [i n]
                    (aset dst i (double (f (aget s1 (int (flat-idx->multi sh st1 eo1 i)))
                                           (aget s2 (int (flat-idx->multi sh2 st2 eo2 i))))))))))
            (arr/from-double-array dst))
          (let [out-arr (arr/eve-array type-kw n)]
            (dotimes [i n]
              (arr/aset! out-arr i (f (flat-get t1 i) (flat-get t2 i))))
            out-arr)))
      sh))))

(defn ereduce
  "Reduce all elements of the tensor."
  [f init t]
  (let [sh (-shape t)
        n (reduce * 1 sh)]
    (if-let [da (d/-as-double-array (-data t))]
      (let [eo (int (-elem-offset t))]
        (if (contiguous? t)
          (loop [i 0 acc init]
            (if (< i n) (recur (inc i) (f acc (aget da (+ eo i)))) acc))
          (let [st (-strides t)]
            (if (== 2 (e/count sh))
              ;; 2D fast path
              (let [rows (int (e/nth sh 0)) cols (int (e/nth sh 1))
                    rs   (int (e/nth st 0))  cs   (int (e/nth st 1))
                    acc-v (volatile! init)]
                (loop [r (int 0)]
                  (when (< r rows)
                    (let [row-start (unchecked-add-int eo (unchecked-multiply-int r rs))]
                      (loop [c (int 0)]
                        (when (< c cols)
                          (vswap! acc-v f (aget da (unchecked-add-int row-start (unchecked-multiply-int c cs))))
                          (recur (unchecked-inc-int c)))))
                    (recur (unchecked-inc-int r))))
                @acc-v)
              (loop [i 0 acc init]
                (if (< i n)
                  (recur (inc i) (f acc (aget da (int (flat-idx->multi sh st eo i)))))
                  acc))))))
      (loop [i 0 acc init]
        (if (< i n)
          (recur (inc i) (f acc (flat-get t i)))
          acc)))))

;;-----------------------------------------------------------------------------
;; Materialization
;;-----------------------------------------------------------------------------

(defn to-array
  "Flatten tensor to a new contiguous EveArray."
  [t]
  (let [sh (-shape t)
        n (reduce * 1 sh)
        type-kw (dtype t)]
    (if-let [src (d/-as-double-array (-data t))]
      (let [eo (int (-elem-offset t))
            dst (eve.host/double-array n)]
        (if (contiguous? t)
          (dotimes [i n] (aset dst i (aget src (+ eo i))))
          (let [st (-strides t)]
            (if (== 2 (e/count sh))
              ;; 2D fast path: nested primitive loops (no rem/quot)
              (let [rows (int (e/nth sh 0)) cols (int (e/nth sh 1))
                    rs   (int (e/nth st 0))  cs   (int (e/nth st 1))]
                (loop [r (int 0) out-i (int 0)]
                  (when (< r rows)
                    (let [row-start (unchecked-add-int eo (unchecked-multiply-int r rs))]
                      (loop [c (int 0)]
                        (when (< c cols)
                          (aset dst (unchecked-add-int out-i c)
                                (aget src (unchecked-add-int row-start (unchecked-multiply-int c cs))))
                          (recur (unchecked-inc-int c)))))
                    (recur (unchecked-inc-int r) (unchecked-add-int out-i cols)))))
              ;; General ND path
              (dotimes [i n]
                (aset dst i (aget src (int (flat-idx->multi sh st eo i))))))))
        (arr/from-double-array dst))
      (let [out (arr/eve-array type-kw n)]
        (dotimes [i n]
          (arr/aset! out i (flat-get t i)))
        out))))

(defn to-dataset
  "Convert a 2D tensor to a Dataset with given column names.
   Requires exactly rank-2."
  [t col-names]
  (let [sh (-shape t)]
    (when (not= 2 (e/count sh))
      (throw (enc/illegal-argument
              "to-dataset requires a 2D tensor")))
    (let [nrows (first sh)
          ncols (second sh)
          _ (when (not= ncols (e/count col-names))
              (throw (enc/illegal-argument
                      (str "Expected " ncols " column names, got " (e/count col-names)))))
          type-kw (dtype t)
          cols (into {} (map (fn [c]
                               (let [col-arr (arr/eve-array type-kw nrows)]
                                 (dotimes [r nrows]
                                   (arr/aset! col-arr r (apply mget t [r c])))
                                 [(e/nth col-names c) col-arr]))
                             (range ncols)))]
      ;; Return the column map — caller can wrap with (eve.dataset/dataset ...)
      cols)))
