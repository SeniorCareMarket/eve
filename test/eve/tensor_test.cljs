(ns eve.tensor-test
  (:require
   [cljs.test :refer [deftest testing is]]
   [eve.array :as arr]
   [eve.tensor :as tensor]))

;;=============================================================================
;; Construction
;;=============================================================================

(deftest from-array-test
  (testing "from-array + shape/strides"
    (let [a (arr/eve-array :float64 [1.0 2.0 3.0 4.0 5.0 6.0])
          t (tensor/from-array a [2 3])]
      (is (tensor/tensor? t))
      (is (= [2 3] (tensor/shape t)))
      (is (= :float64 (tensor/dtype t)))
      (is (= 2 (tensor/rank t))))))

(deftest zeros-test
  (testing "zeros constructor"
    (let [t (tensor/zeros :float64 [3 4])]
      (is (= [3 4] (tensor/shape t)))
      (is (= 12 (count t)))
      (is (= 0.0 (tensor/mget t 0 0)))
      (is (= 0.0 (tensor/mget t 2 3))))))

(deftest ones-test
  (testing "ones constructor"
    (let [t (tensor/ones :int32 [2 3])]
      (is (= [2 3] (tensor/shape t)))
      (is (= 1 (tensor/mget t 0 0)))
      (is (= 1 (tensor/mget t 1 2))))))

;;=============================================================================
;; Element access
;;=============================================================================

(deftest mget-mset-test
  (testing "mget / mset!"
    (let [t (tensor/zeros :float64 [3 4])]
      (tensor/mset! t 1 2 42.0)
      (is (< (abs (- 42.0 (tensor/mget t 1 2))) 0.001))
      (is (= 0.0 (tensor/mget t 0 0))))))

;;=============================================================================
;; Shape operations
;;=============================================================================

(deftest reshape-test
  (testing "reshape (zero-copy: same backing array)"
    (let [a (arr/eve-array :int32 [1 2 3 4 5 6])
          t1 (tensor/from-array a [2 3])
          t2 (tensor/reshape t1 [3 2])]
      (is (= [3 2] (tensor/shape t2)))
      ;; Same backing data — element 0 is still 1
      (is (= 1 (tensor/mget t2 0 0)))
      (is (= 6 (tensor/mget t2 2 1))))))

(deftest transpose-2d-test
  (testing "transpose 2D (zero-copy)"
    (let [a (arr/eve-array :int32 [1 2 3 4 5 6])
          t (tensor/from-array a [2 3])
          ;; t = [[1 2 3] [4 5 6]]
          tt (tensor/transpose t)]
      ;; tt = [[1 4] [2 5] [3 6]]
      (is (= [3 2] (tensor/shape tt)))
      (is (= 1 (tensor/mget tt 0 0)))
      (is (= 4 (tensor/mget tt 0 1)))
      (is (= 2 (tensor/mget tt 1 0)))
      (is (= 5 (tensor/mget tt 1 1)))
      (is (= 3 (tensor/mget tt 2 0)))
      (is (= 6 (tensor/mget tt 2 1))))))

(deftest transpose-perm-test
  (testing "transpose with axis permutation"
    (let [a (arr/eve-array :int32 (vec (range 24)))
          t (tensor/from-array a [2 3 4])
          ;; permute [2 0 1]: new shape = [4 2 3]
          tp (tensor/transpose t [2 0 1])]
      (is (= [4 2 3] (tensor/shape tp)))
      ;; Element [i,j,k] in original → [k,i,j] in permuted
      ;; Original [0,0,0] = 0, permuted [0,0,0] should also be 0
      (is (= 0 (tensor/mget tp 0 0 0)))
      ;; Original [0,1,2] = 0*12 + 1*4 + 2 = 6
      ;; In permuted: [2,0,1] → original[0,1,2] = 6
      (is (= 6 (tensor/mget tp 2 0 1))))))

(deftest slice-axis-test
  (testing "slice (dimension reduction)"
    (let [a (arr/eve-array :int32 [1 2 3 4 5 6])
          t (tensor/from-array a [2 3])
          ;; t = [[1 2 3] [4 5 6]]
          row1 (tensor/slice-axis t 0 1)]
      ;; row1 = [4 5 6]
      (is (= [3] (tensor/shape row1)))
      (is (= 1 (tensor/rank row1)))
      (is (= 4 (tensor/mget row1 0)))
      (is (= 5 (tensor/mget row1 1)))
      (is (= 6 (tensor/mget row1 2))))))

(deftest contiguous-test
  (testing "contiguous? check"
    (let [a (arr/eve-array :int32 [1 2 3 4 5 6])
          t (tensor/from-array a [2 3])]
      (is (true? (tensor/contiguous? t)))
      ;; Transpose breaks contiguity
      (is (false? (tensor/contiguous? (tensor/transpose t)))))))

;;=============================================================================
;; Bulk operations
;;=============================================================================

(deftest emap-test
  (testing "emap (element-wise binary op)"
    (let [a (arr/eve-array :int32 [1 2 3 4])
          t1 (tensor/from-array a [2 2])
          t2 (tensor/emap #(* % 10) t1)]
      (is (= [2 2] (tensor/shape t2)))
      (is (= 10 (tensor/mget t2 0 0)))
      (is (= 40 (tensor/mget t2 1 1)))))
  (testing "emap binary"
    (let [a (arr/eve-array :int32 [1 2 3 4])
          b (arr/eve-array :int32 [10 20 30 40])
          t1 (tensor/from-array a [2 2])
          t2 (tensor/from-array b [2 2])
          t3 (tensor/emap + t1 t2)]
      (is (= 11 (tensor/mget t3 0 0)))
      (is (= 44 (tensor/mget t3 1 1))))))

(deftest ereduce-test
  (testing "ereduce (full reduction)"
    (let [a (arr/eve-array :int32 [1 2 3 4])
          t (tensor/from-array a [2 2])
          total (tensor/ereduce + 0 t)]
      (is (= 10 total)))))

;;=============================================================================
;; Materialization
;;=============================================================================

(deftest to-array-test
  (testing "to-array materialization"
    (let [a (arr/eve-array :int32 [1 2 3 4 5 6])
          t (tensor/from-array a [2 3])
          ;; Transpose then flatten
          tt (tensor/transpose t)
          ;; tt = [[1 4] [2 5] [3 6]]
          flat (tensor/to-array tt)]
      (is (= 6 (count flat)))
      (is (= 1 (nth flat 0)))
      (is (= 4 (nth flat 1)))
      (is (= 2 (nth flat 2)))
      (is (= 5 (nth flat 3)))
      (is (= 3 (nth flat 4)))
      (is (= 6 (nth flat 5))))))

(deftest to-dataset-test
  (testing "to-dataset from 2D tensor"
    (let [a (arr/eve-array :int32 [1 2 3 4 5 6])
          t (tensor/from-array a [3 2])
          ;; t = [[1 2] [3 4] [5 6]]
          cols (tensor/to-dataset t [:a :b])]
      (is (= 2 (count cols)))
      (is (= 3 (count (:a cols))))
      (is (= 1 (nth (:a cols) 0)))
      (is (= 3 (nth (:a cols) 1)))
      (is (= 5 (nth (:a cols) 2)))
      (is (= 2 (nth (:b cols) 0)))
      (is (= 4 (nth (:b cols) 1)))
      (is (= 6 (nth (:b cols) 2))))))

;;=============================================================================
;; Non-contiguous operations
;;=============================================================================

(deftest non-contiguous-select-test
  (testing "non-contiguous select via slice + emap"
    (let [a (arr/eve-array :int32 [1 2 3 4 5 6])
          t (tensor/from-array a [2 3])
          ;; t = [[1 2 3] [4 5 6]]
          ;; Select column 1: [2 5]
          col1 (tensor/slice-axis t 1 1)]
      (is (= [2] (tensor/shape col1)))
      (is (= 2 (tensor/mget col1 0)))
      (is (= 5 (tensor/mget col1 1))))))

;;=============================================================================
;; Scalar broadcast in emap
;;=============================================================================

(deftest scalar-broadcast-emap-test
  (testing "scalar broadcast in emap"
    (let [a (arr/eve-array :int32 [1 2 3 4])
          t (tensor/from-array a [2 2])
          doubled (tensor/emap #(* 2 %) t)]
      (is (= 2 (tensor/mget doubled 0 0)))
      (is (= 8 (tensor/mget doubled 1 1))))))
