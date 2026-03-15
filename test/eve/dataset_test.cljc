(ns eve.dataset-test
  (:require
   #?(:cljs [cljs.test :refer [deftest testing is are]]
      :clj  [clojure.test :refer [deftest testing is are]])
   [eve.array :as arr]
   [eve.dataset :as ds]
   [eve.dataset.functional :as func]
   [eve.dataset.argops :as argops]))

(defn- approx= [expected actual tolerance]
  (< #?(:cljs (abs (- expected actual))
        :clj  (Math/abs (double (- expected actual))))
     tolerance))

;;=============================================================================
;; Dataset construction and accessors
;;=============================================================================

(deftest column-construction-test
  (testing "EveArray construction from values"
    (let [a (arr/eve-array :int32 [10 20 30])]
      (is (= 3 (count a)))
      (is (= 10 (nth a 0)))
      (is (= 20 (nth a 1)))
      (is (= 30 (nth a 2))))
    (let [a (arr/eve-array :float64 [1.5 2.5 3.5])]
      (is (= 3 (count a)))
      (is (approx= 1.5 (nth a 0) 0.001))
      (is (approx= 2.5 (nth a 1) 0.001)))))

(deftest dataset-construction-test
  (testing "Dataset from column map"
    (let [ds1 (ds/dataset {:price (arr/eve-array :float64 [10.5 20.3 30.1])
                           :qty   (arr/eve-array :int32 [100 200 300])})]
      (is (ds/dataset? ds1))
      (is (= 3 (ds/row-count ds1)))
      (is (= 2 (count (ds/column-names ds1))))
      (is (some #{:price} (ds/column-names ds1)))
      (is (some #{:qty} (ds/column-names ds1))))))

(deftest column-access-test
  (testing "Column access returns EveArray identity"
    (let [price-col (arr/eve-array :float64 [10.5 20.3])
          ds1 (ds/dataset {:price price-col
                           :qty   (arr/eve-array :int32 [100 200])})]
      (is (identical? price-col (ds/column ds1 :price)))
      (is (= 2 (count (ds/column ds1 :qty)))))))

(deftest dtypes-test
  (testing "dtypes returns type map"
    (let [ds1 (ds/dataset {:price (arr/eve-array :float64 [1.0 2.0])
                           :qty   (arr/eve-array :int32 [1 2])})]
      (is (= :float64 (:price (ds/dtypes ds1))))
      (is (= :int32 (:qty (ds/dtypes ds1)))))))

(deftest row-count-test
  (testing "row-count"
    (is (= 5 (ds/row-count
               (ds/dataset {:a (arr/eve-array :int32 [1 2 3 4 5])}))))))

;;=============================================================================
;; Structural operations
;;=============================================================================

(deftest select-columns-test
  (testing "select-columns"
    (let [ds1 (ds/dataset {:a (arr/eve-array :int32 [1 2])
                           :b (arr/eve-array :int32 [3 4])
                           :c (arr/eve-array :int32 [5 6])})
          ds2 (ds/select-columns ds1 [:a :c])]
      (is (= [:a :c] (ds/column-names ds2)))
      (is (= 2 (ds/row-count ds2)))
      (is (nil? (ds/column ds2 :b))))))

(deftest add-column-test
  (testing "add-column"
    (let [ds1 (ds/dataset {:a (arr/eve-array :int32 [1 2])})
          ds2 (ds/add-column ds1 :b (arr/eve-array :int32 [3 4]))]
      (is (= [:a :b] (ds/column-names ds2)))
      (is (= 3 (nth (ds/column ds2 :b) 0))))))

(deftest drop-column-test
  (testing "drop-column"
    (let [ds1 (ds/dataset {:a (arr/eve-array :int32 [1 2])
                           :b (arr/eve-array :int32 [3 4])})
          ds2 (ds/drop-column ds1 :b)]
      (is (= [:a] (ds/column-names ds2)))
      (is (nil? (ds/column ds2 :b))))))

(deftest rename-columns-test
  (testing "rename-columns"
    (let [ds1 (ds/dataset {:price (arr/eve-array :float64 [10.5 20.3])
                           :qty   (arr/eve-array :int32 [100 200])})
          ds2 (ds/rename-columns ds1 {:price :cost})]
      (is (some #{:cost} (ds/column-names ds2)))
      (is (not (some #{:price} (ds/column-names ds2))))
      (is (approx= 10.5 (nth (ds/column ds2 :cost) 0) 0.001)))))

;;=============================================================================
;; Row operations
;;=============================================================================

(deftest filter-rows-test
  (testing "filter-rows with predicate"
    (let [ds1 (ds/dataset {:price (arr/eve-array :float64 [10.5 20.3 5.0 30.1])
                           :qty   (arr/eve-array :int32 [100 200 50 300])})
          ds2 (ds/filter-rows ds1 :price #(> % 15.0))]
      (is (= 2 (ds/row-count ds2)))
      (is (approx= 20.3 (nth (ds/column ds2 :price) 0) 0.001))
      (is (approx= 30.1 (nth (ds/column ds2 :price) 1) 0.001)))))

(deftest sort-by-column-test
  (testing "sort ascending"
    (let [ds1 (ds/dataset {:v (arr/eve-array :int32 [30 10 20])})
          ds2 (ds/sort-by-column ds1 :v :asc)]
      (is (= 10 (nth (ds/column ds2 :v) 0)))
      (is (= 20 (nth (ds/column ds2 :v) 1)))
      (is (= 30 (nth (ds/column ds2 :v) 2)))))
  (testing "sort descending"
    (let [ds1 (ds/dataset {:v (arr/eve-array :int32 [30 10 20])})
          ds2 (ds/sort-by-column ds1 :v :desc)]
      (is (= 30 (nth (ds/column ds2 :v) 0)))
      (is (= 20 (nth (ds/column ds2 :v) 1)))
      (is (= 10 (nth (ds/column ds2 :v) 2))))))

(deftest head-tail-slice-test
  (testing "head"
    (let [ds1 (ds/dataset {:v (arr/eve-array :int32 [1 2 3 4 5])})
          ds2 (ds/head ds1 3)]
      (is (= 3 (ds/row-count ds2)))
      (is (= 1 (nth (ds/column ds2 :v) 0)))
      (is (= 3 (nth (ds/column ds2 :v) 2)))))
  (testing "tail"
    (let [ds1 (ds/dataset {:v (arr/eve-array :int32 [1 2 3 4 5])})
          ds2 (ds/tail ds1 2)]
      (is (= 2 (ds/row-count ds2)))
      (is (= 4 (nth (ds/column ds2 :v) 0)))
      (is (= 5 (nth (ds/column ds2 :v) 1)))))
  (testing "slice"
    (let [ds1 (ds/dataset {:v (arr/eve-array :int32 [10 20 30 40 50])})
          ds2 (ds/slice ds1 1 4)]
      (is (= 3 (ds/row-count ds2)))
      (is (= 20 (nth (ds/column ds2 :v) 0)))
      (is (= 40 (nth (ds/column ds2 :v) 2))))))

;;=============================================================================
;; Functional operations (on EveArray columns)
;;=============================================================================

(deftest sum-mean-test
  (testing "sum and mean"
    (let [col (arr/eve-array :float64 [10.0 20.0 30.0])]
      (is (approx= 60.0 (func/sum col) 0.001))
      (is (approx= 20.0 (func/mean col) 0.001)))))

(deftest min-max-val-test
  (testing "min-val and max-val"
    (let [col (arr/eve-array :int32 [30 10 20 50 5])]
      (is (= 5 (func/min-val col)))
      (is (= 50 (func/max-val col))))))

(deftest arithmetic-ops-test
  (testing "add, mul, sub, div (array x array)"
    (let [a (arr/eve-array :int32 [10 20 30])
          b (arr/eve-array :int32 [1 2 3])
          s (func/add a b)
          d (func/sub a b)
          m (func/mul a b)]
      (is (= 11 (nth s 0)))
      (is (= 22 (nth s 1)))
      (is (= 9 (nth d 0)))
      (is (= 10 (nth m 0)))
      (is (= 90 (nth m 2)))))
  (testing "array x scalar"
    (let [a (arr/eve-array :int32 [10 20 30])
          m (func/mul a 2)]
      (is (= 20 (nth m 0)))
      (is (= 60 (nth m 2)))))
  (testing "div returns float64"
    (let [a (arr/eve-array :int32 [10 20])
          b (arr/eve-array :int32 [3 4])
          r (func/div a b)]
      (is (approx= 3.333 (nth r 0) 0.01))
      (is (approx= 5.0 (nth r 1) 0.001)))))

(deftest comparison-ops-test
  (testing "gt, lt, eq return uint8 mask"
    (let [col (arr/eve-array :int32 [10 20 30 40 50])
          mask-gt (func/gt col 25)
          mask-lt (func/lt col 25)
          mask-eq (func/eq col 30)]
      (is (= 0 (nth mask-gt 0)))  ;; 10 > 25? no
      (is (= 0 (nth mask-gt 1)))  ;; 20 > 25? no
      (is (= 1 (nth mask-gt 2)))  ;; 30 > 25? yes
      (is (= 1 (nth mask-gt 3)))  ;; 40 > 25? yes
      (is (= 1 (nth mask-lt 0)))  ;; 10 < 25? yes
      (is (= 0 (nth mask-lt 2)))  ;; 30 < 25? no
      (is (= 0 (nth mask-eq 0)))  ;; 10 == 30? no
      (is (= 1 (nth mask-eq 2)))  ;; 30 == 30? yes
      )))

;;=============================================================================
;; Argops
;;=============================================================================

(deftest argsort-test
  (testing "argsort ascending"
    (let [col (arr/eve-array :int32 [30 10 20])
          idx (argops/argsort col :asc)]
      (is (= 1 (nth idx 0)))  ;; 10 is at original index 1
      (is (= 2 (nth idx 1)))  ;; 20 is at original index 2
      (is (= 0 (nth idx 2)))));; 30 is at original index 0
  (testing "argsort descending"
    (let [col (arr/eve-array :int32 [30 10 20])
          idx (argops/argsort col :desc)]
      (is (= 0 (nth idx 0)))
      (is (= 2 (nth idx 1)))
      (is (= 1 (nth idx 2))))))

(deftest argfilter-test
  (testing "argfilter"
    (let [col (arr/eve-array :int32 [10 20 30 40 50])
          idx (argops/argfilter col #(> % 25))]
      (is (= 3 (count idx)))
      (is (= 2 (nth idx 0)))
      (is (= 3 (nth idx 1)))
      (is (= 4 (nth idx 2))))))

(deftest argmin-argmax-test
  (testing "argmin and argmax"
    (let [col (arr/eve-array :int32 [30 10 50 20])]
      (is (= 1 (argops/argmin col)))
      (is (= 2 (argops/argmax col))))))

(deftest take-indices-reindex-test
  (testing "take-indices"
    (let [col (arr/eve-array :int32 [10 20 30 40 50])
          idx (arr/eve-array :int32 [4 2 0])
          result (argops/take-indices col idx)]
      (is (= 3 (count result)))
      (is (= 50 (nth result 0)))
      (is (= 30 (nth result 1)))
      (is (= 10 (nth result 2)))))
  (testing "reindex dataset"
    (let [ds1 (ds/dataset {:v (arr/eve-array :int32 [10 20 30])})
          idx (arr/eve-array :int32 [2 0 1])
          ds2 (ds/reindex ds1 idx)]
      (is (= 30 (nth (ds/column ds2 :v) 0)))
      (is (= 10 (nth (ds/column ds2 :v) 1)))
      (is (= 20 (nth (ds/column ds2 :v) 2))))))

;;=============================================================================
;; Multiple dtypes in one dataset
;;=============================================================================

(deftest multi-dtype-test
  (testing "dataset with multiple dtypes"
    (let [ds1 (ds/dataset {:id    (arr/eve-array :int32 [1 2 3])
                           :value (arr/eve-array :float64 [1.1 2.2 3.3])
                           :flag  (arr/eve-array :uint8 [1 0 1])})]
      (is (= :int32 (:id (ds/dtypes ds1))))
      (is (= :float64 (:value (ds/dtypes ds1))))
      (is (= :uint8 (:flag (ds/dtypes ds1))))
      (is (= 3 (ds/row-count ds1))))))

;;=============================================================================
;; ILookup / IFn
;;=============================================================================

(deftest dataset-lookup-test
  (testing "Dataset implements ILookup"
    (let [col (arr/eve-array :int32 [1 2 3])
          ds1 (ds/dataset {:a col})]
      (is (identical? col (:a ds1)))
      (is (identical? col (ds1 :a)))
      (is (nil? (:missing ds1))))))

;;=============================================================================
;; Emap
;;=============================================================================

(deftest emap-test
  (testing "emap on single column"
    (let [col (arr/eve-array :int32 [10 20 30])
          result (func/emap #(* % 2) col)]
      (is (= 3 (count result)))
      (is (= 20 (nth result 0)))
      (is (= 60 (nth result 2)))))
  (testing "emap2 on two columns"
    (let [a (arr/eve-array :int32 [1 2 3])
          b (arr/eve-array :int32 [10 20 30])
          result (func/emap2 + a b)]
      (is (= 11 (nth result 0)))
      (is (= 33 (nth result 2))))))
