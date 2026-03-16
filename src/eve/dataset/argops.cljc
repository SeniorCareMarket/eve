(ns eve.dataset.argops
  "Index-space operations on EveArrays. Return EveArray :int32 of indices."
  (:require
   [eve.array :as arr]
   #?(:clj [eve.deftype-proto.data :as d])))

;;-----------------------------------------------------------------------------
;; Bulk extraction helper (JVM only)
;;-----------------------------------------------------------------------------

#?(:clj
   (defn- bulk-doubles
     "Extract all elements as a double[] — fast path via IBulkAccess,
      fallback via nth."
     ^doubles [col]
     (or (d/-as-double-array col)
         (let [n (count col)
               ^doubles out (double-array n)]
           (dotimes [i n] (aset out i (double (nth col i))))
           out))))

#?(:clj
   (defn- bulk-ints
     "Extract all elements as an int[] — fast path via IBulkAccess,
      fallback via nth."
     ^ints [col]
     (or (d/-as-int-array col)
         (let [n (count col)
               ^ints out (int-array n)]
           (dotimes [i n] (aset out i (int (nth col i))))
           out))))

(defn argsort
  "Return :int32 array of indices that would sort col.
   direction is :asc (default) or :desc."
  ([col] (argsort col :asc))
  ([col direction]
   #?(:clj
      (let [n    (count col)
            ^ints ia (d/-as-int-array col)
            ^doubles da (when-not ia (d/-as-double-array col))]
        (if (or ia da)
          ;; Fast path: sort Integer[] indices with typed-array comparisons
          (let [^"[Ljava.lang.Integer;" idx-arr (make-array Integer n)
                _ (dotimes [i n] (aset idx-arr i (Integer/valueOf (int i))))
                cmp (cond
                      ia (if (= direction :desc)
                           (reify java.util.Comparator
                             (compare [_ a b] (Integer/compare (aget ia (.intValue ^Integer b))
                                                                (aget ia (.intValue ^Integer a)))))
                           (reify java.util.Comparator
                             (compare [_ a b] (Integer/compare (aget ia (.intValue ^Integer a))
                                                                (aget ia (.intValue ^Integer b))))))
                      :else (if (= direction :desc)
                              (reify java.util.Comparator
                                (compare [_ a b] (Double/compare (aget da (.intValue ^Integer b))
                                                                  (aget da (.intValue ^Integer a)))))
                              (reify java.util.Comparator
                                (compare [_ a b] (Double/compare (aget da (.intValue ^Integer a))
                                                                  (aget da (.intValue ^Integer b)))))))]
            (java.util.Arrays/sort idx-arr cmp)
            (let [^ints out (int-array n)]
              (dotimes [i n] (aset out i (.intValue ^Integer (aget idx-arr i))))
              (arr/from-int-array out)))
          ;; Fallback: extract to vec, sort with Clojure sort
          (let [idxs (vec (range n))
                cfn  (if (= direction :desc)
                       (fn [a b] (compare (nth col b) (nth col a)))
                       (fn [a b] (compare (nth col a) (nth col b))))
                sorted (sort cfn idxs)
                ^ints out (int-array n)]
            (dotimes [i n]
              (aset out i (int (clojure.core/nth sorted i))))
            (arr/from-int-array out))))
      :cljs
      (let [n    (count col)
            idxs (vec (range n))
            cmp  (if (= direction :desc)
                   (fn [a b] (compare (nth col b) (nth col a)))
                   (fn [a b] (compare (nth col a) (nth col b))))
            sorted (sort cmp idxs)
            out  (arr/eve-array :int32 n)]
        (dotimes [i n]
          (arr/aset! out i (int (clojure.core/nth sorted i))))
        out))))

(defn argfilter
  "Return :int32 array of indices where (pred elem) is truthy."
  [col pred]
  #?(:clj
     (let [^doubles da (d/-as-double-array col)]
       (if da
         (let [n   (alength da)
               buf (java.util.ArrayList.)]
           (dotimes [i n]
             (when (pred (aget da i))
               (.add buf (int i))))
           (let [m   (.size buf)
                 ^ints out (int-array m)]
             (dotimes [i m]
               (aset out i (int (.get buf i))))
             (arr/from-int-array out)))
         (let [^ints ia (d/-as-int-array col)]
           (if ia
             (let [n   (alength ia)
                   buf (java.util.ArrayList.)]
               (dotimes [i n]
                 (when (pred (aget ia i))
                   (.add buf (int i))))
               (let [m   (.size buf)
                     ^ints out (int-array m)]
                 (dotimes [i m]
                   (aset out i (int (.get buf i))))
                 (arr/from-int-array out)))
             ;; Fallback
             (let [n (count col)
                   buf (java.util.ArrayList.)]
               (dotimes [i n]
                 (when (pred (nth col i))
                   (.add buf (int i))))
               (let [m (.size buf)
                     ^ints out (int-array m)]
                 (dotimes [i m]
                   (aset out i (int (.get buf i))))
                 (arr/from-int-array out)))))))
     :cljs
     (let [n (count col)
           matches #js []]
       (dotimes [i n]
         (when (pred (nth col i))
           (.push matches i)))
       (let [m (.-length matches)
             out (arr/eve-array :int32 m)]
         (dotimes [i m]
           (arr/aset! out i (int (clojure.core/aget matches i))))
         out))))

(defn argmin
  "Return index of minimum value (scalar)."
  [col]
  #?(:clj
     (if-let [^doubles da (d/-as-double-array col)]
       (when (pos? (alength da))
         (loop [i 1 best 0 best-val (aget da 0)]
           (if (< i (alength da))
             (let [v (aget da i)]
               (if (< v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
             best)))
       (let [n (count col)]
         (when (pos? n)
           (loop [i 1 best 0 best-val (nth col 0)]
             (if (< i n)
               (let [v (nth col i)]
                 (if (< v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
               best)))))
     :cljs
     (let [n (count col)]
       (when (pos? n)
         (loop [i 1 best 0 best-val (nth col 0)]
           (if (< i n)
             (let [v (nth col i)]
               (if (< v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
             best))))))

(defn argmax
  "Return index of maximum value (scalar)."
  [col]
  #?(:clj
     (if-let [^doubles da (d/-as-double-array col)]
       (when (pos? (alength da))
         (loop [i 1 best 0 best-val (aget da 0)]
           (if (< i (alength da))
             (let [v (aget da i)]
               (if (> v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
             best)))
       (let [n (count col)]
         (when (pos? n)
           (loop [i 1 best 0 best-val (nth col 0)]
             (if (< i n)
               (let [v (nth col i)]
                 (if (> v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
               best)))))
     :cljs
     (let [n (count col)]
       (when (pos? n)
         (loop [i 1 best 0 best-val (nth col 0)]
           (if (< i n)
             (let [v (nth col i)]
               (if (> v best-val) (recur (inc i) i v) (recur (inc i) best best-val)))
             best))))))

(defn arggroup
  "Return map of {value → :int32 EveArray of indices}."
  [col]
  (let [n (count col)
        groups (reduce (fn [m i]
                         (let [v (nth col i)]
                           (update m v (fnil conj []) i)))
                       {} (range n))]
    (into {} (map (fn [[k idxs]]
                    [k (arr/eve-array :int32 idxs)])
                  groups))))

(defn take-indices
  "Gather: return new array with elements at given indices.
   idx-arr is an :int32 EveArray of indices."
  [col idx-arr]
  #?(:clj
     (let [^ints idx (or (d/-as-int-array idx-arr)
                         (bulk-ints idx-arr))
           n   (alength idx)]
       (if-let [^doubles da (d/-as-double-array col)]
         (let [^doubles out (double-array n)]
           (dotimes [i n]
             (aset out i (aget da (aget idx i))))
           (arr/from-double-array out))
         (if-let [^ints ia (d/-as-int-array col)]
           (let [^ints out (int-array n)]
             (dotimes [i n]
               (aset out i (aget ia (aget idx i))))
             (arr/from-int-array out))
           (let [type-kw (arr/subtype->type-kw (arr/array-subtype-code col))
                 out (arr/eve-array type-kw n)]
             (dotimes [i n]
               (arr/aset! out i (nth col (int (nth idx-arr i)))))
             out))))
     :cljs
     (let [n (count idx-arr)
           type-kw (arr/subtype->type-kw (arr/array-subtype-code col))
           out (arr/eve-array type-kw n)]
       (dotimes [i n]
         (arr/aset! out i (nth col (int (nth idx-arr i)))))
       out)))
