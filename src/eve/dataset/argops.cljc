(ns eve.dataset.argops
  "Index-space operations on EveArrays. Return EveArray :int32 of indices."
  (:require
   [eve.array :as arr]))

(defn argsort
  "Return :int32 array of indices that would sort col.
   direction is :asc (default) or :desc."
  ([col] (argsort col :asc))
  ([col direction]
   (let [n    (count col)
         idxs (vec (range n))
         cmp  (if (= direction :desc)
                (fn [a b] (compare (nth col b) (nth col a)))
                (fn [a b] (compare (nth col a) (nth col b))))
         sorted (sort cmp idxs)
         out  (arr/eve-array :int32 n)]
     (dotimes [i n]
       (arr/aset! out i (int (clojure.core/nth sorted i))))
     out)))

(defn argfilter
  "Return :int32 array of indices where (pred elem) is truthy."
  [col pred]
  (let [n (count col)
        ;; Collect matching indices
        matches #?(:cljs (let [buf #js []]
                           (dotimes [i n]
                             (when (pred (nth col i))
                               (.push buf i)))
                           buf)
                   :clj  (let [buf (java.util.ArrayList.)]
                           (dotimes [i n]
                             (when (pred (nth col i))
                               (.add buf (int i))))
                           buf))
        m #?(:cljs (.-length matches)
             :clj  (.size matches))
        out (arr/eve-array :int32 m)]
    (dotimes [i m]
      (arr/aset! out i (int #?(:cljs (clojure.core/aget matches i)
                               :clj  (.get matches i)))))
    out))

(defn argmin
  "Return index of minimum value (scalar)."
  [col]
  (let [n (count col)]
    (when (pos? n)
      (loop [i 1 best 0 best-val (nth col 0)]
        (if (< i n)
          (let [v (nth col i)]
            (if (< v best-val)
              (recur (inc i) i v)
              (recur (inc i) best best-val)))
          best)))))

(defn argmax
  "Return index of maximum value (scalar)."
  [col]
  (let [n (count col)]
    (when (pos? n)
      (loop [i 1 best 0 best-val (nth col 0)]
        (if (< i n)
          (let [v (nth col i)]
            (if (> v best-val)
              (recur (inc i) i v)
              (recur (inc i) best best-val)))
          best)))))

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
  (let [n (count idx-arr)
        type-kw (arr/subtype->type-kw (.-subtype-code col))
        out (arr/eve-array type-kw n)]
    (dotimes [i n]
      (arr/aset! out i (nth col (int (nth idx-arr i)))))
    out))
