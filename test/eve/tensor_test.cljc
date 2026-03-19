(ns eve.tensor-test
  (:require
   #?(:cljs [cljs.test :refer [deftest testing is]]
      :clj  [clojure.test :refer [deftest testing is]])
   [eve.alpha :as e]))

(defn- approx= [expected actual tolerance]
  (< #?(:cljs (abs (- expected actual))
        :clj  (Math/abs (double (- expected actual))))
     tolerance))

#?(:cljs
   (defn- make-eve-atom
     "Create an Eve shared atom for testing."
     [initial-val]
     (e/atom initial-val)))

;; Helper: run tensor ops inside an atom swap!
#?(:cljs
   (defn- in-atom [f]
     (let [ea (make-eve-atom nil)]
       (swap! ea (fn [_] (f))))))

;;=============================================================================
;; Construction
;;=============================================================================

#?(:cljs
   (deftest from-array-test
     (testing "from-array + shape/strides"
       (let [t (in-atom #(e/tensor-from-array
                           (e/eve-array :float64 [1.0 2.0 3.0 4.0 5.0 6.0])
                           [2 3]))]
         (is (e/tensor? t))
         (is (= [2 3] (e/tensor-shape t)))
         (is (= :float64 (e/tensor-dtype t)))
         (is (= 2 (e/tensor-rank t)))))))

#?(:cljs
   (deftest zeros-test
     (testing "zeros constructor"
       (let [t (in-atom #(e/tensor-zeros :float64 [3 4]))]
         (is (= [3 4] (e/tensor-shape t)))
         (is (= 12 (count t)))
         (is (= 0.0 (e/tensor-mget t 0 0)))
         (is (= 0.0 (e/tensor-mget t 2 3)))))))

#?(:cljs
   (deftest ones-test
     (testing "ones constructor"
       (let [t (in-atom #(e/tensor-ones :int32 [2 3]))]
         (is (= [2 3] (e/tensor-shape t)))
         (is (= 1 (e/tensor-mget t 0 0)))
         (is (= 1 (e/tensor-mget t 1 2)))))))

;;=============================================================================
;; Element access
;;=============================================================================

#?(:cljs
   (deftest mget-mset-test
     (testing "mget / mset!"
       (let [t (in-atom #(e/tensor-zeros :float64 [3 4]))]
         ;; mset! inside a swap to mutate the backing array
         (in-atom (fn [] (e/tensor-mset! t 1 2 42.0) nil))
         (is (approx= 42.0 (e/tensor-mget t 1 2) 0.001))
         (is (= 0.0 (e/tensor-mget t 0 0)))))))

;;=============================================================================
;; Shape operations
;;=============================================================================

#?(:cljs
   (deftest reshape-test
     (testing "reshape (zero-copy: same backing array)"
       (let [t2 (in-atom #(let [a (e/eve-array :int32 [1 2 3 4 5 6])
                                 t1 (e/tensor-from-array a [2 3])]
                             (e/tensor-reshape t1 [3 2])))]
         (is (= [3 2] (e/tensor-shape t2)))
         (is (= 1 (e/tensor-mget t2 0 0)))
         (is (= 6 (e/tensor-mget t2 2 1)))))))

#?(:cljs
   (deftest transpose-2d-test
     (testing "transpose 2D (zero-copy)"
       (let [tt (in-atom #(let [a (e/eve-array :int32 [1 2 3 4 5 6])
                                 t (e/tensor-from-array a [2 3])]
                             (e/tensor-transpose t)))]
         ;; tt = [[1 4] [2 5] [3 6]]
         (is (= [3 2] (e/tensor-shape tt)))
         (is (= 1 (e/tensor-mget tt 0 0)))
         (is (= 4 (e/tensor-mget tt 0 1)))
         (is (= 2 (e/tensor-mget tt 1 0)))
         (is (= 5 (e/tensor-mget tt 1 1)))
         (is (= 3 (e/tensor-mget tt 2 0)))
         (is (= 6 (e/tensor-mget tt 2 1)))))))

#?(:cljs
   (deftest transpose-perm-test
     (testing "transpose with axis permutation"
       (let [tp (in-atom #(let [a (e/eve-array :int32 (vec (range 24)))
                                 t (e/tensor-from-array a [2 3 4])]
                             (e/tensor-transpose t [2 0 1])))]
         (is (= [4 2 3] (e/tensor-shape tp)))
         (is (= 0 (e/tensor-mget tp 0 0 0)))
         (is (= 6 (e/tensor-mget tp 2 0 1)))))))

#?(:cljs
   (deftest slice-axis-test
     (testing "slice (dimension reduction)"
       (let [row1 (in-atom #(let [a (e/eve-array :int32 [1 2 3 4 5 6])
                                   t (e/tensor-from-array a [2 3])]
                               (e/tensor-slice-axis t 0 1)))]
         ;; row1 = [4 5 6]
         (is (= [3] (e/tensor-shape row1)))
         (is (= 1 (e/tensor-rank row1)))
         (is (= 4 (e/tensor-mget row1 0)))
         (is (= 5 (e/tensor-mget row1 1)))
         (is (= 6 (e/tensor-mget row1 2)))))))

#?(:cljs
   (deftest contiguous-test
     (testing "contiguous? check"
       (let [results (in-atom #(let [a (e/eve-array :int32 [1 2 3 4 5 6])
                                      t (e/tensor-from-array a [2 3])]
                                  [(e/tensor-contiguous? t)
                                   (e/tensor-contiguous? (e/tensor-transpose t))]))]
         (is (true? (first results)))
         (is (false? (second results)))))))

;;=============================================================================
;; Bulk operations
;;=============================================================================

#?(:cljs
   (deftest emap-test
     (testing "emap (element-wise unary)"
       (let [t2 (in-atom (fn [] (let [a (e/eve-array :int32 [1 2 3 4])
                                       t1 (e/tensor-from-array a [2 2])]
                                   (e/tensor-emap (fn [x] (* x 10)) t1))))]
         (is (= [2 2] (e/tensor-shape t2)))
         (is (= 10 (e/tensor-mget t2 0 0)))
         (is (= 40 (e/tensor-mget t2 1 1)))))
     (testing "emap binary"
       (let [t3 (in-atom #(let [a (e/eve-array :int32 [1 2 3 4])
                                 b (e/eve-array :int32 [10 20 30 40])
                                 t1 (e/tensor-from-array a [2 2])
                                 t2 (e/tensor-from-array b [2 2])]
                             (e/tensor-emap + t1 t2)))]
         (is (= 11 (e/tensor-mget t3 0 0)))
         (is (= 44 (e/tensor-mget t3 1 1)))))))

#?(:cljs
   (deftest ereduce-test
     (testing "ereduce (full reduction)"
       (let [total (in-atom #(let [a (e/eve-array :int32 [1 2 3 4])
                                    t (e/tensor-from-array a [2 2])]
                                (e/tensor-ereduce + 0 t)))]
         (is (= 10 total))))))

;;=============================================================================
;; Materialization
;;=============================================================================

#?(:cljs
   (deftest to-array-test
     (testing "to-array materialization"
       (let [flat (in-atom #(let [a (e/eve-array :int32 [1 2 3 4 5 6])
                                   t (e/tensor-from-array a [2 3])
                                   tt (e/tensor-transpose t)]
                               (e/tensor-to-array tt)))]
         (is (= 6 (count flat)))
         (is (= 1 (nth flat 0)))
         (is (= 4 (nth flat 1)))
         (is (= 2 (nth flat 2)))
         (is (= 5 (nth flat 3)))
         (is (= 3 (nth flat 4)))
         (is (= 6 (nth flat 5)))))))

#?(:cljs
   (deftest to-dataset-test
     (testing "to-dataset from 2D tensor"
       (let [cols (in-atom #(let [a (e/eve-array :int32 [1 2 3 4 5 6])
                                   t (e/tensor-from-array a [3 2])]
                               (e/tensor-to-dataset t [:a :b])))]
         (is (= 2 (count cols)))
         (is (= 3 (count (:a cols))))
         (is (= 1 (nth (:a cols) 0)))
         (is (= 3 (nth (:a cols) 1)))
         (is (= 5 (nth (:a cols) 2)))
         (is (= 2 (nth (:b cols) 0)))
         (is (= 4 (nth (:b cols) 1)))
         (is (= 6 (nth (:b cols) 2)))))))

;;=============================================================================
;; Non-contiguous operations
;;=============================================================================

#?(:cljs
   (deftest non-contiguous-select-test
     (testing "non-contiguous select via slice + emap"
       (let [col1 (in-atom #(let [a (e/eve-array :int32 [1 2 3 4 5 6])
                                   t (e/tensor-from-array a [2 3])]
                               (e/tensor-slice-axis t 1 1)))]
         (is (= [2] (e/tensor-shape col1)))
         (is (= 2 (e/tensor-mget col1 0)))
         (is (= 5 (e/tensor-mget col1 1)))))))

;;=============================================================================
;; Scalar broadcast in emap
;;=============================================================================

#?(:cljs
   (deftest scalar-broadcast-emap-test
     (testing "scalar broadcast in emap"
       (let [doubled (in-atom (fn [] (let [a (e/eve-array :int32 [1 2 3 4])
                                            t (e/tensor-from-array a [2 2])]
                                        (e/tensor-emap (fn [x] (* 2 x)) t))))]
         (is (= 2 (e/tensor-mget doubled 0 0)))
         (is (= 8 (e/tensor-mget doubled 1 1)))))))
