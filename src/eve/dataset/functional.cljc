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
;; CLJS typed-view helpers
;;-----------------------------------------------------------------------------

#?(:cljs
   (defn- get-view
     "Extract typed array view for an EveArray. Returns nil for non-EveArray."
     [x]
     (when-not (number? x)
       (arr/get-typed-view x))))

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
     (let [va (get-view a)
           vb (get-view b)
           n  (min (arr-count a) (arr-count b))
           out (arr/eve-array (result-type a b) n)
           vo (arr/get-typed-view out)]
       (cond
         (and va vb)
         (dotimes [i n]
           (clojure.core/aset vo i (+ (clojure.core/aget va i) (clojure.core/aget vb i))))
         va
         (let [bv b]
           (dotimes [i n]
             (clojure.core/aset vo i (+ (clojure.core/aget va i) bv))))
         vb
         (let [av a]
           (dotimes [i n]
             (clojure.core/aset vo i (+ av (clojure.core/aget vb i)))))
         :else
         (dotimes [i n]
           (arr/aset! out i (+ (arr-get a i) (arr-get b i)))))
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
     (let [va (get-view a)
           vb (get-view b)
           n  (min (arr-count a) (arr-count b))
           out (arr/eve-array (result-type a b) n)
           vo (arr/get-typed-view out)]
       (cond
         (and va vb)
         (dotimes [i n]
           (clojure.core/aset vo i (- (clojure.core/aget va i) (clojure.core/aget vb i))))
         va
         (let [bv b]
           (dotimes [i n]
             (clojure.core/aset vo i (- (clojure.core/aget va i) bv))))
         vb
         (let [av a]
           (dotimes [i n]
             (clojure.core/aset vo i (- av (clojure.core/aget vb i)))))
         :else
         (dotimes [i n]
           (arr/aset! out i (- (arr-get a i) (arr-get b i)))))
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
     (let [va (get-view a)
           vb (get-view b)
           n  (min (arr-count a) (arr-count b))
           out (arr/eve-array (result-type a b) n)
           vo (arr/get-typed-view out)]
       (cond
         (and va vb)
         (dotimes [i n]
           (clojure.core/aset vo i (* (clojure.core/aget va i) (clojure.core/aget vb i))))
         va
         (let [bv b]
           (dotimes [i n]
             (clojure.core/aset vo i (* (clojure.core/aget va i) bv))))
         vb
         (let [av a]
           (dotimes [i n]
             (clojure.core/aset vo i (* av (clojure.core/aget vb i)))))
         :else
         (dotimes [i n]
           (arr/aset! out i (* (arr-get a i) (arr-get b i)))))
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
     (let [va (get-view a)
           vb (get-view b)
           n  (min (arr-count a) (arr-count b))
           out (arr/eve-array :float64 n)
           vo (arr/get-typed-view out)]
       (cond
         (and va vb)
         (dotimes [i n]
           (clojure.core/aset vo i (/ (clojure.core/aget va i) (clojure.core/aget vb i))))
         va
         (let [bv b]
           (dotimes [i n]
             (clojure.core/aset vo i (/ (clojure.core/aget va i) bv))))
         vb
         (let [av a]
           (dotimes [i n]
             (clojure.core/aset vo i (/ av (clojure.core/aget vb i)))))
         :else
         (dotimes [i n]
           (arr/aset! out i (/ (double (arr-get a i)) (double (arr-get b i))))))
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
     (let [tv (arr/get-typed-view col)
           n (.-length tv)]
       (loop [i 0 acc 0.0]
         (if (< i n)
           (recur (inc i) (+ acc (clojure.core/aget tv i)))
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
     (let [tv (arr/get-typed-view col)
           n (.-length tv)]
       (when (pos? n)
         (loop [i 1 m (clojure.core/aget tv 0)]
           (if (< i n)
             (let [v (clojure.core/aget tv i)] (recur (inc i) (if (< v m) v m)))
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
     (let [tv (arr/get-typed-view col)
           n (.-length tv)]
       (when (pos? n)
         (loop [i 1 m (clojure.core/aget tv 0)]
           (if (< i n)
             (let [v (clojure.core/aget tv i)] (recur (inc i) (if (> v m) v m)))
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
     (let [va (get-view a)
           n  (min (arr-count a) (arr-count b))
           out (arr/eve-array :uint8 n)
           vo (arr/get-typed-view out)]
       (if (and va (number? b))
         (let [bv b]
           (dotimes [i n]
             (clojure.core/aset vo i (if (> (clojure.core/aget va i) bv) 1 0))))
         (let [vb (get-view b)]
           (if (and va vb)
             (dotimes [i n]
               (clojure.core/aset vo i (if (> (clojure.core/aget va i) (clojure.core/aget vb i)) 1 0)))
             (dotimes [i n]
               (arr/aset! out i (if (> (arr-get a i) (arr-get b i)) 1 0))))))
       out)))

(defn lt
  "Element-wise less-than. Returns :uint8 mask."
  [a b]
  #?(:clj
     (let [n (min (arr-count a) (arr-count b))
           out (arr/eve-array :uint8 n)]
       (dotimes [i n]
         (arr/aset! out i (if (< (arr-get a i) (arr-get b i)) 1 0)))
       out)
     :cljs
     (let [va (get-view a)
           vb (get-view b)
           n  (min (arr-count a) (arr-count b))
           out (arr/eve-array :uint8 n)
           vo (arr/get-typed-view out)]
       (if (and va vb)
         (dotimes [i n]
           (clojure.core/aset vo i (if (< (clojure.core/aget va i) (clojure.core/aget vb i)) 1 0)))
         (dotimes [i n]
           (arr/aset! out i (if (< (arr-get a i) (arr-get b i)) 1 0))))
       out)))

(defn eq
  "Element-wise equality. Returns :uint8 mask."
  [a b]
  #?(:clj
     (let [n (min (arr-count a) (arr-count b))
           out (arr/eve-array :uint8 n)]
       (dotimes [i n]
         (arr/aset! out i (if (== (arr-get a i) (arr-get b i)) 1 0)))
       out)
     :cljs
     (let [va (get-view a)
           vb (get-view b)
           n  (min (arr-count a) (arr-count b))
           out (arr/eve-array :uint8 n)
           vo (arr/get-typed-view out)]
       (if (and va vb)
         (dotimes [i n]
           (clojure.core/aset vo i (if (== (clojure.core/aget va i) (clojure.core/aget vb i)) 1 0)))
         (dotimes [i n]
           (arr/aset! out i (if (== (arr-get a i) (arr-get b i)) 1 0))))
       out)))

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
     (let [tv (arr/get-typed-view col)
           n  (.-length tv)
           type-kw (arr/subtype->type-kw (arr/array-subtype-code col))
           out (arr/eve-array type-kw n)
           vo (arr/get-typed-view out)]
       (dotimes [i n]
         (clojure.core/aset vo i (f (clojure.core/aget tv i))))
       out)))

(defn emap2
  "Map f over pairs of elements from two arrays.
   f receives (a b). Output type is promoted."
  [f col1 col2]
  #?(:clj
     (let [n (min (count col1) (count col2))
           out (arr/eve-array (result-type col1 col2) n)]
       (dotimes [i n]
         (arr/aset! out i (f (nth col1 i) (nth col2 i))))
       out)
     :cljs
     (let [tv1 (arr/get-typed-view col1)
           tv2 (arr/get-typed-view col2)
           n   (min (.-length tv1) (.-length tv2))
           out (arr/eve-array (result-type col1 col2) n)
           vo  (arr/get-typed-view out)]
       (dotimes [i n]
         (clojure.core/aset vo i (f (clojure.core/aget tv1 i) (clojure.core/aget tv2 i))))
       out)))
