(ns eve.dataset.functional
  "Element-wise operations on EveArrays. Works on standalone arrays AND
   dataset columns (columns ARE EveArrays).

   Arithmetic ops return new EveArrays. Aggregations return scalars.
   Comparison ops return :uint8 mask arrays (0/1)."
  (:require
   [eve.array :as arr]
   #?(:clj [eve.deftype-proto.data :as d])))

;;-----------------------------------------------------------------------------
;; Internal helpers
;;-----------------------------------------------------------------------------

(defn- result-type
  "Determine the output type for a binary op between two arrays or array+scalar.
   Promotes to :float64 if either operand is floating-point."
  [a b]
  (let [ta (if (number? a) nil (arr/subtype->type-kw (arr/array-subtype-code a)))
        tb (if (number? b) nil (arr/subtype->type-kw (arr/array-subtype-code b)))]
    (cond
      (or (= ta :float64) (= tb :float64)) :float64
      (or (= ta :float32) (= tb :float32)) :float64
      (or (nil? ta) (nil? tb))              (or ta tb :int32)
      :else                                 ta)))

(defn- arr-count [x]
  (if (number? x) ##Inf (count x)))

(defn- arr-get [x i]
  (if (number? x) x (nth x i)))

;;-----------------------------------------------------------------------------
;; Arithmetic (return new EveArray)
;;-----------------------------------------------------------------------------

(defn add
  "Element-wise addition. Either arg may be a scalar."
  [a b]
  #?(:clj
     (let [da (when-not (number? a) (d/-as-double-array a))
           db (when-not (number? b) (d/-as-double-array b))]
       (if (and (or da (number? a)) (or db (number? b)))
         ;; Fast path: double[] aget loops
         (let [n  (int (min (arr-count a) (arr-count b)))
               ^doubles out (double-array n)]
           (cond
             (and da db)
             (let [^doubles x da ^doubles y db]
               (dotimes [i n] (aset out i (+ (aget x i) (aget y i)))))
             da
             (let [^doubles x da bv (double b)]
               (dotimes [i n] (aset out i (+ (aget x i) bv))))
             :else
             (let [av (double a) ^doubles y db]
               (dotimes [i n] (aset out i (+ av (aget y i))))))
           (arr/from-double-array out))
         ;; Fallback
         (let [n (int (min (arr-count a) (arr-count b)))
               out (arr/eve-array (result-type a b) n)]
           (dotimes [i n] (arr/aset! out i (+ (arr-get a i) (arr-get b i))))
           out)))
     :cljs
     (let [n (min (arr-count a) (arr-count b))
           out (arr/eve-array (result-type a b) n)]
       (dotimes [i n]
         (arr/aset! out i (+ (arr-get a i) (arr-get b i))))
       out)))

(defn sub
  "Element-wise subtraction."
  [a b]
  #?(:clj
     (let [da (when-not (number? a) (d/-as-double-array a))
           db (when-not (number? b) (d/-as-double-array b))]
       (if (and (or da (number? a)) (or db (number? b)))
         (let [n  (int (min (arr-count a) (arr-count b)))
               ^doubles out (double-array n)]
           (cond
             (and da db)
             (let [^doubles x da ^doubles y db]
               (dotimes [i n] (aset out i (- (aget x i) (aget y i)))))
             da
             (let [^doubles x da bv (double b)]
               (dotimes [i n] (aset out i (- (aget x i) bv))))
             :else
             (let [av (double a) ^doubles y db]
               (dotimes [i n] (aset out i (- av (aget y i))))))
           (arr/from-double-array out))
         (let [n (int (min (arr-count a) (arr-count b)))
               out (arr/eve-array (result-type a b) n)]
           (dotimes [i n] (arr/aset! out i (- (arr-get a i) (arr-get b i))))
           out)))
     :cljs
     (let [n (min (arr-count a) (arr-count b))
           out (arr/eve-array (result-type a b) n)]
       (dotimes [i n]
         (arr/aset! out i (- (arr-get a i) (arr-get b i))))
       out)))

(defn mul
  "Element-wise multiplication. Either arg may be a scalar."
  [a b]
  #?(:clj
     (let [da (when-not (number? a) (d/-as-double-array a))
           db (when-not (number? b) (d/-as-double-array b))]
       (if (and (or da (number? a)) (or db (number? b)))
         (let [n  (int (min (arr-count a) (arr-count b)))
               ^doubles out (double-array n)]
           (cond
             (and da db)
             (let [^doubles x da ^doubles y db]
               (dotimes [i n] (aset out i (* (aget x i) (aget y i)))))
             da
             (let [^doubles x da bv (double b)]
               (dotimes [i n] (aset out i (* (aget x i) bv))))
             :else
             (let [av (double a) ^doubles y db]
               (dotimes [i n] (aset out i (* av (aget y i))))))
           (arr/from-double-array out))
         (let [n (int (min (arr-count a) (arr-count b)))
               out (arr/eve-array (result-type a b) n)]
           (dotimes [i n] (arr/aset! out i (* (arr-get a i) (arr-get b i))))
           out)))
     :cljs
     (let [n (min (arr-count a) (arr-count b))
           out (arr/eve-array (result-type a b) n)]
       (dotimes [i n]
         (arr/aset! out i (* (arr-get a i) (arr-get b i))))
       out)))

(defn div
  "Element-wise division."
  [a b]
  #?(:clj
     (let [da (when-not (number? a) (d/-as-double-array a))
           db (when-not (number? b) (d/-as-double-array b))]
       (if (and (or da (number? a)) (or db (number? b)))
         (let [n  (int (min (arr-count a) (arr-count b)))
               ^doubles out (double-array n)]
           (cond
             (and da db)
             (let [^doubles x da ^doubles y db]
               (dotimes [i n] (aset out i (/ (aget x i) (aget y i)))))
             da
             (let [^doubles x da bv (double b)]
               (dotimes [i n] (aset out i (/ (aget x i) bv))))
             :else
             (let [av (double a) ^doubles y db]
               (dotimes [i n] (aset out i (/ av (aget y i))))))
           (arr/from-double-array out))
         (let [n (int (min (arr-count a) (arr-count b)))
               out (arr/eve-array :float64 n)]
           (dotimes [i n] (arr/aset! out i (/ (double (arr-get a i)) (double (arr-get b i)))))
           out)))
     :cljs
     (let [n (min (arr-count a) (arr-count b))
           out (arr/eve-array :float64 n)]
       (dotimes [i n]
         (arr/aset! out i (/ (double (arr-get a i)) (double (arr-get b i)))))
       out)))

;;-----------------------------------------------------------------------------
;; Aggregations (return scalar)
;;-----------------------------------------------------------------------------

(defn sum
  "Sum all elements."
  [col]
  #?(:clj
     (if-let [^doubles da (d/-as-double-array col)]
       (let [n (alength da)]
         (loop [i 0 acc 0.0]
           (if (< i n) (recur (inc i) (+ acc (aget da i))) acc)))
       (if-let [^ints ia (d/-as-int-array col)]
         (let [n (alength ia)]
           (loop [i 0 acc (long 0)]
             (if (< i n) (recur (inc i) (+ acc (long (aget ia i)))) (double acc))))
         (let [n (count col)]
           (loop [i 0 acc 0.0]
             (if (< i n) (recur (inc i) (+ acc (double (nth col i)))) acc)))))
     :cljs
     (let [n (count col)]
       (loop [i 0 acc 0.0]
         (if (< i n)
           (recur (inc i) (+ acc (double (nth col i))))
           acc)))))

(defn mean
  "Arithmetic mean."
  [col]
  (let [n (count col)]
    (if (zero? n) 0.0 (/ (sum col) n))))

(defn min-val
  "Minimum value."
  [col]
  #?(:clj
     (if-let [^doubles da (d/-as-double-array col)]
       (when (pos? (alength da))
         (loop [i 1 m (aget da 0)]
           (if (< i (alength da))
             (let [v (aget da i)] (recur (inc i) (if (< v m) v m)))
             m)))
       (let [n (count col)]
         (when (pos? n)
           (loop [i 1 m (nth col 0)]
             (if (< i n)
               (let [v (nth col i)] (recur (inc i) (if (< v m) v m)))
               m)))))
     :cljs
     (let [n (count col)]
       (when (pos? n)
         (loop [i 1 m (nth col 0)]
           (if (< i n)
             (let [v (nth col i)] (recur (inc i) (if (< v m) v m)))
             m))))))

(defn max-val
  "Maximum value."
  [col]
  #?(:clj
     (if-let [^doubles da (d/-as-double-array col)]
       (when (pos? (alength da))
         (loop [i 1 m (aget da 0)]
           (if (< i (alength da))
             (let [v (aget da i)] (recur (inc i) (if (> v m) v m)))
             m)))
       (let [n (count col)]
         (when (pos? n)
           (loop [i 1 m (nth col 0)]
             (if (< i n)
               (let [v (nth col i)] (recur (inc i) (if (> v m) v m)))
               m)))))
     :cljs
     (let [n (count col)]
       (when (pos? n)
         (loop [i 1 m (nth col 0)]
           (if (< i n)
             (let [v (nth col i)] (recur (inc i) (if (> v m) v m)))
             m))))))

;;-----------------------------------------------------------------------------
;; Comparison (return EveArray :uint8 of 0/1)
;;-----------------------------------------------------------------------------

(defn gt
  "Element-wise greater-than. Returns :uint8 mask."
  [a b]
  #?(:clj
     (let [da (when-not (number? a) (d/-as-double-array a))]
       (if (and da (number? b))
         (let [^doubles x da
               n   (alength x)
               bv  (double b)
               ^bytes out (byte-array n)]
           (dotimes [i n]
             (aset out i (byte (if (> (aget x i) bv) 1 0))))
           (arr/from-byte-array out))
         (let [n (int (min (arr-count a) (arr-count b)))
               out (arr/eve-array :uint8 n)]
           (dotimes [i n]
             (arr/aset! out i (if (> (arr-get a i) (arr-get b i)) 1 0)))
           out)))
     :cljs
     (let [n (min (arr-count a) (arr-count b))
           out (arr/eve-array :uint8 n)]
       (dotimes [i n]
         (arr/aset! out i (if (> (arr-get a i) (arr-get b i)) 1 0)))
       out)))

(defn lt
  "Element-wise less-than. Returns :uint8 mask."
  [a b]
  (let [n (min (arr-count a) (arr-count b))
        out (arr/eve-array :uint8 n)]
    (dotimes [i n]
      (arr/aset! out i (if (< (arr-get a i) (arr-get b i)) 1 0)))
    out))

(defn eq
  "Element-wise equality. Returns :uint8 mask."
  [a b]
  (let [n (min (arr-count a) (arr-count b))
        out (arr/eve-array :uint8 n)]
    (dotimes [i n]
      (arr/aset! out i (if (== (arr-get a i) (arr-get b i)) 1 0)))
    out))

;;-----------------------------------------------------------------------------
;; Mapping
;;-----------------------------------------------------------------------------

(defn emap
  "Map f over elements, returning new array of same type.
   f receives (elem)."
  [f col]
  #?(:clj
     (if-let [^doubles da (d/-as-double-array col)]
       (let [n (alength da)
             ^doubles out (double-array n)]
         (dotimes [i n] (aset out i (double (f (aget da i)))))
         (arr/from-double-array out))
       (let [n (count col)
             type-kw (arr/subtype->type-kw (arr/array-subtype-code col))
             out (arr/eve-array type-kw n)]
         (dotimes [i n] (arr/aset! out i (f (nth col i))))
         out))
     :cljs
     (let [n (count col)
           type-kw (arr/subtype->type-kw (arr/array-subtype-code col))
           out (arr/eve-array type-kw n)]
       (dotimes [i n]
         (arr/aset! out i (f (nth col i))))
       out)))

(defn emap2
  "Map f over pairs of elements from two arrays.
   f receives (a b). Output type is promoted."
  [f col1 col2]
  (let [n (min (count col1) (count col2))
        out (arr/eve-array (result-type col1 col2) n)]
    (dotimes [i n]
      (arr/aset! out i (f (nth col1 i) (nth col2 i))))
    out))
