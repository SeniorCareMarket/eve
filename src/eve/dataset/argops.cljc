(ns eve.dataset.argops
  "Index-space operations on EveArrays. Return EveArray :int32 of indices."
  (:refer-clojure :exclude [aget aset alength int-array double-array unchecked-inc-int
                            unchecked-dec-int unsigned-bit-shift-right double transient
                            persistent! conj! long ints doubles])
  (:require
   [eve.host :refer [aget aset alength int-array double-array unchecked-inc-int
                     unchecked-dec-int unsigned-bit-shift-right double transient persistent!
                     conj! long ints doubles]]
   [eve.core :as e]
   [eve.array :as arr]
   [eve.deftype-proto.data :as d]))

;;-----------------------------------------------------------------------------
;; Bulk extraction: the host array of the elements (IBulkAccess, else nth)
;;-----------------------------------------------------------------------------

(defn- bulk-doubles
  "Extract all elements as a double[] — fast path via IBulkAccess,
   fallback via nth."
   [col]
  (or (d/-as-double-array col)
      (let [n (e/count col)
            out (double-array n)]
        (dotimes [i n] (aset out i (double (e/nth col i))))
        out)))

(defn- bulk-ints
  "Extract all elements as an int[] — fast path via IBulkAccess,
   fallback via nth."
   [col]
  (or (d/-as-int-array col)
      (let [n (e/count col)
            out (int-array n)]
        (dotimes [i n] (aset out i (int (e/nth col i))))
        out)))

;;-----------------------------------------------------------------------------
;; Quicksort of an index array by a value array (primitive comparisons where the host has them)
;; (the scans' loop locals are a and b, not the partition loop's i and j they start from: a host whose codegen
;; cannot shadow an outer loop's local in an inner loop — doc/hosts/jank.md, upstream 24)
;;-----------------------------------------------------------------------------

(defn- qsort-doubles-asc!
  "In-place quicksort of int[] indices by double[] values, ascending."
  [^ints idx ^doubles vals ^long lo ^long hi]
  (when (< lo hi)
    (let [pivot (aget vals (aget idx (int (+ lo (unsigned-bit-shift-right (- hi lo) 1)))))]
      (loop [i (int lo) j (int hi)]
        (let [i (int (loop [a i] (if (and (<= a j) (< (aget vals (aget idx a)) pivot)) (recur (unchecked-inc-int a)) a)))
              j (int (loop [b j] (if (and (<= i b) (> (aget vals (aget idx b)) pivot)) (recur (unchecked-dec-int b)) b)))]
          (if (<= i j)
            (let [tmp (aget idx i)]
              (aset idx i (aget idx j))
              (aset idx j tmp)
              (recur (unchecked-inc-int i) (unchecked-dec-int j)))
            (do (when (< lo j) (qsort-doubles-asc! idx vals lo j))
                (when (< i hi) (qsort-doubles-asc! idx vals i hi)))))))))

(defn- qsort-doubles-desc!
  "In-place quicksort of int[] indices by double[] values, descending."
  [^ints idx ^doubles vals ^long lo ^long hi]
  (when (< lo hi)
    (let [pivot (aget vals (aget idx (int (+ lo (unsigned-bit-shift-right (- hi lo) 1)))))]
      (loop [i (int lo) j (int hi)]
        (let [i (int (loop [a i] (if (and (<= a j) (> (aget vals (aget idx a)) pivot)) (recur (unchecked-inc-int a)) a)))
              j (int (loop [b j] (if (and (<= i b) (< (aget vals (aget idx b)) pivot)) (recur (unchecked-dec-int b)) b)))]
          (if (<= i j)
            (let [tmp (aget idx i)]
              (aset idx i (aget idx j))
              (aset idx j tmp)
              (recur (unchecked-inc-int i) (unchecked-dec-int j)))
            (do (when (< lo j) (qsort-doubles-desc! idx vals lo j))
                (when (< i hi) (qsort-doubles-desc! idx vals i hi)))))))))

(defn- qsort-ints-asc!
  "In-place quicksort of int[] indices by int[] values, ascending."
  [^ints idx ^ints vals ^long lo ^long hi]
  (when (< lo hi)
    (let [pivot (aget vals (aget idx (int (+ lo (unsigned-bit-shift-right (- hi lo) 1)))))]
      (loop [i (int lo) j (int hi)]
        (let [i (int (loop [a i] (if (and (<= a j) (< (aget vals (aget idx a)) pivot)) (recur (unchecked-inc-int a)) a)))
              j (int (loop [b j] (if (and (<= i b) (> (aget vals (aget idx b)) pivot)) (recur (unchecked-dec-int b)) b)))]
          (if (<= i j)
            (let [tmp (aget idx i)]
              (aset idx i (aget idx j))
              (aset idx j tmp)
              (recur (unchecked-inc-int i) (unchecked-dec-int j)))
            (do (when (< lo j) (qsort-ints-asc! idx vals lo j))
                (when (< i hi) (qsort-ints-asc! idx vals i hi)))))))))

(defn- qsort-ints-desc!
  "In-place quicksort of int[] indices by int[] values, descending."
  [^ints idx ^ints vals ^long lo ^long hi]
  (when (< lo hi)
    (let [pivot (aget vals (aget idx (int (+ lo (unsigned-bit-shift-right (- hi lo) 1)))))]
      (loop [i (int lo) j (int hi)]
        (let [i (int (loop [a i] (if (and (<= a j) (> (aget vals (aget idx a)) pivot)) (recur (unchecked-inc-int a)) a)))
              j (int (loop [b j] (if (and (<= i b) (< (aget vals (aget idx b)) pivot)) (recur (unchecked-dec-int b)) b)))]
          (if (<= i j)
            (let [tmp (aget idx i)]
              (aset idx i (aget idx j))
              (aset idx j tmp)
              (recur (unchecked-inc-int i) (unchecked-dec-int j)))
            (do (when (< lo j) (qsort-ints-desc! idx vals lo j))
                (when (< i hi) (qsort-ints-desc! idx vals i hi)))))))))

(defn argsort
  "Return :int32 array of indices that would sort col.
   direction is :asc (default) or :desc."
  ([col] (argsort col :asc))
  ([col direction]
   (let [n (e/count col)]
     (if (< n 2)
       (arr/from-int-array (int-array (range n)))
       (let [ia (d/-as-int-array col)
             da (when-not ia (d/-as-double-array col))]
         (if (or ia da)
           ;; Fast path: quicksort on primitive int[] indices
           (let [idx (int-array n)
                 _ (dotimes [i n] (aset idx i (int i)))]
             (cond
               ia (if (= direction :desc)
                    (qsort-ints-desc! idx ia 0 (dec n))
                    (qsort-ints-asc! idx ia 0 (dec n)))
               :else (if (= direction :desc)
                       (qsort-doubles-desc! idx da 0 (dec n))
                       (qsort-doubles-asc! idx da 0 (dec n))))
             (arr/from-int-array idx))
           ;; Fallback: extract to vec, sort with Clojure sort
           (let [idxs (vec (range n))
                 cfn  (if (= direction :desc)
                        (fn [a b] (compare (e/nth col b) (e/nth col a)))
                        (fn [a b] (compare (e/nth col a) (e/nth col b))))
                 sorted (sort cfn idxs)
                 out (int-array n)]
             (dotimes [i n]
               (aset out i (int (clojure.core/nth sorted i))))
             (arr/from-int-array out))))))))

(defn argfilter
  "Return :int32 array of indices where (pred elem) is truthy."
  [col pred]
  (let [n (e/count col)
        da (d/-as-double-array col)
        ia (when-not da (d/-as-int-array col))
        elem (cond da (fn [i] (aget da i))
                   ia (fn [i] (aget ia i))
                   :else (fn [i] (e/nth col i)))
        hits (loop [i 0 acc (transient [])]
               (if (< i n)
                 (recur (inc i) (if (pred (elem i)) (conj! acc i) acc))
                 (persistent! acc)))
        m (count hits)
        out (int-array m)]
    (dotimes [i m] (aset out i (int (nth hits i))))
    (arr/from-int-array out)))

(defn argmin
  "Return index of minimum value (scalar)."
  [col]
  (if-let [da (d/-as-double-array col)]
    (when (pos? (alength da))
      (loop [i 1 best 0 best-val (aget da 0)]
        (if (< i (alength da))
          (let [v (aget da i)]
            (if (< v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
          best)))
    (let [n (e/count col)]
      (when (pos? n)
        (loop [i 1 best 0 best-val (e/nth col 0)]
          (if (< i n)
            (let [v (e/nth col i)]
              (if (< v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
            best))))))

(defn argmax
  "Return index of maximum value (scalar)."
  [col]
  (if-let [da (d/-as-double-array col)]
    (when (pos? (alength da))
      (loop [i 1 best 0 best-val (aget da 0)]
        (if (< i (alength da))
          (let [v (aget da i)]
            (if (> v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
          best)))
    (let [n (e/count col)]
      (when (pos? n)
        (loop [i 1 best 0 best-val (e/nth col 0)]
          (if (< i n)
            (let [v (e/nth col i)]
              (if (> v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
            best))))))

(defn arggroup
  "Return map of {value → :int32 EveArray of indices}."
  [col]
  (let [n (e/count col)
        groups (loop [i 0 g (transient {})]
                 (if (< i n)
                   (let [v (e/nth col i)]
                     (recur (inc i) (assoc! g v (conj (get g v []) i))))
                   (persistent! g)))]
    (into {}
          (map (fn [[k idxs]]
                 (let [m (count idxs)
                       out (int-array m)]
                   (dotimes [i m] (aset out i (int (nth idxs i))))
                   [k (arr/from-int-array out)])))
          groups)))

(defn take-indices
  "Gather: return new array with elements at given indices.
   idx-arr is an :int32 EveArray of indices."
  [col idx-arr]
  (let [idx (or (d/-as-int-array idx-arr)
                      (bulk-ints idx-arr))
        n   (alength idx)]
    (if-let [da (d/-as-double-array col)]
      (let [out (double-array n)]
        (dotimes [i n]
          (aset out i (aget da (aget idx i))))
        (arr/from-double-array out))
      (if-let [ia (d/-as-int-array col)]
        (let [out (int-array n)]
          (dotimes [i n]
            (aset out i (aget ia (aget idx i))))
          (arr/from-int-array out))
        (let [type-kw (arr/subtype->type-kw (arr/array-subtype-code col))
              out (arr/eve-array type-kw n)]
          (dotimes [i n]
            (arr/aset! out i (e/nth col (int (e/nth idx-arr i)))))
          out)))))
