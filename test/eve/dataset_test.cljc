(ns eve.dataset-test
  (:require
   #?(:cljs [cljs.test :refer [deftest testing is are]]
      :clj  [clojure.test :refer [deftest testing is are]])
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

;;=============================================================================
;; Dataset construction and accessors
;;=============================================================================

(deftest column-construction-test
  (testing "EveArray construction from values"
    (let [a (e/eve-array :int32 [10 20 30])]
      (is (= 3 (count a)))
      (is (= 10 (nth a 0)))
      (is (= 20 (nth a 1)))
      (is (= 30 (nth a 2))))
    (let [a (e/eve-array :float64 [1.5 2.5 3.5])]
      (is (= 3 (count a)))
      (is (approx= 1.5 (nth a 0) 0.001))
      (is (approx= 2.5 (nth a 1) 0.001)))))

#?(:cljs
   (deftest dataset-construction-test
     (testing "Dataset from column map (inside atom)"
       (let [ea (make-eve-atom nil)
             ds1 (swap! ea (fn [_]
                             (e/dataset {:price (e/eve-array :float64 [10.5 20.3 30.1])
                                          :qty   (e/eve-array :int32 [100 200 300])})))]
         (is (e/dataset? ds1))
         (is (= 3 (e/ds-row-count ds1)))
         (is (= 2 (count (e/ds-column-names ds1))))
         (is (some #{:price} (e/ds-column-names ds1)))
         (is (some #{:qty} (e/ds-column-names ds1)))))))

#?(:cljs
   (deftest column-access-test
     (testing "Column access returns EveArray"
       (let [ea (make-eve-atom nil)
             ds1 (swap! ea (fn [_]
                             (e/dataset {:price (e/eve-array :float64 [10.5 20.3])
                                          :qty   (e/eve-array :int32 [100 200])})))]
         (is (some? (e/ds-column ds1 :price)))
         (is (= 2 (count (e/ds-column ds1 :qty))))
         (is (approx= 10.5 (nth (e/ds-column ds1 :price) 0) 0.001))))))

#?(:cljs
   (deftest dtypes-test
     (testing "dtypes returns type map"
       (let [ea (make-eve-atom nil)
             ds1 (swap! ea (fn [_]
                             (e/dataset {:price (e/eve-array :float64 [1.0 2.0])
                                          :qty   (e/eve-array :int32 [1 2])})))]
         (is (= :float64 (:price (e/ds-dtypes ds1))))
         (is (= :int32 (:qty (e/ds-dtypes ds1))))))))

#?(:cljs
   (deftest row-count-test
     (testing "row-count"
       (let [ea (make-eve-atom nil)]
         (is (= 5 (e/ds-row-count
                     (swap! ea (fn [_]
                                 (e/dataset {:a (e/eve-array :int32 [1 2 3 4 5])}))))))))))

;;=============================================================================
;; Structural operations
;;=============================================================================

#?(:cljs
   (deftest select-columns-test
     (testing "select-columns"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:a (e/eve-array :int32 [1 2])
                                                    :b (e/eve-array :int32 [3 4])
                                                    :c (e/eve-array :int32 [5 6])})]
                               (e/ds-select-columns ds1 [:a :c]))))]
         (is (= [:a :c] (e/ds-column-names ds2)))
         (is (= 2 (e/ds-row-count ds2)))
         (is (nil? (e/ds-column ds2 :b)))))))

#?(:cljs
   (deftest add-column-test
     (testing "add-column"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:a (e/eve-array :int32 [1 2])})]
                               (e/ds-add-column ds1 :b (e/eve-array :int32 [3 4])))))]
         (is (= [:a :b] (e/ds-column-names ds2)))
         (is (= 3 (nth (e/ds-column ds2 :b) 0)))))))

#?(:cljs
   (deftest drop-column-test
     (testing "drop-column"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:a (e/eve-array :int32 [1 2])
                                                    :b (e/eve-array :int32 [3 4])})]
                               (e/ds-drop-column ds1 :b))))]
         (is (= [:a] (e/ds-column-names ds2)))
         (is (nil? (e/ds-column ds2 :b)))))))

#?(:cljs
   (deftest rename-columns-test
     (testing "rename-columns"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:price (e/eve-array :float64 [10.5 20.3])
                                                    :qty   (e/eve-array :int32 [100 200])})]
                               (e/ds-rename-columns ds1 {:price :cost}))))]
         (is (some #{:cost} (e/ds-column-names ds2)))
         (is (not (some #{:price} (e/ds-column-names ds2))))
         (is (approx= 10.5 (nth (e/ds-column ds2 :cost) 0) 0.001))))))

;;=============================================================================
;; Row operations
;;=============================================================================

#?(:cljs
   (deftest filter-rows-test
     (testing "filter-rows with predicate"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:price (e/eve-array :float64 [10.5 20.3 5.0 30.1])
                                                    :qty   (e/eve-array :int32 [100 200 50 300])})]
                               (e/ds-filter-rows ds1 :price #(> % 15.0)))))]
         (is (= 2 (e/ds-row-count ds2)))
         (is (approx= 20.3 (nth (e/ds-column ds2 :price) 0) 0.001))
         (is (approx= 30.1 (nth (e/ds-column ds2 :price) 1) 0.001))))))

#?(:cljs
   (deftest sort-by-column-test
     (testing "sort ascending"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:v (e/eve-array :int32 [30 10 20])})]
                               (e/ds-sort-by-column ds1 :v :asc))))]
         (is (= 10 (nth (e/ds-column ds2 :v) 0)))
         (is (= 20 (nth (e/ds-column ds2 :v) 1)))
         (is (= 30 (nth (e/ds-column ds2 :v) 2)))))
     (testing "sort descending"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:v (e/eve-array :int32 [30 10 20])})]
                               (e/ds-sort-by-column ds1 :v :desc))))]
         (is (= 30 (nth (e/ds-column ds2 :v) 0)))
         (is (= 20 (nth (e/ds-column ds2 :v) 1)))
         (is (= 10 (nth (e/ds-column ds2 :v) 2)))))))

#?(:cljs
   (deftest head-tail-slice-test
     (testing "head"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:v (e/eve-array :int32 [1 2 3 4 5])})]
                               (e/ds-head ds1 3))))]
         (is (= 3 (e/ds-row-count ds2)))
         (is (= 1 (nth (e/ds-column ds2 :v) 0)))
         (is (= 3 (nth (e/ds-column ds2 :v) 2)))))
     (testing "tail"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:v (e/eve-array :int32 [1 2 3 4 5])})]
                               (e/ds-tail ds1 2))))]
         (is (= 2 (e/ds-row-count ds2)))
         (is (= 4 (nth (e/ds-column ds2 :v) 0)))
         (is (= 5 (nth (e/ds-column ds2 :v) 1)))))
     (testing "slice"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:v (e/eve-array :int32 [10 20 30 40 50])})]
                               (e/ds-slice ds1 1 4))))]
         (is (= 3 (e/ds-row-count ds2)))
         (is (= 20 (nth (e/ds-column ds2 :v) 0)))
         (is (= 40 (nth (e/ds-column ds2 :v) 2)))))))

;;=============================================================================
;; Functional operations (on EveArray columns — no atom needed)
;;=============================================================================

(deftest sum-mean-test
  (testing "sum and mean"
    (let [col (e/eve-array :float64 [10.0 20.0 30.0])]
      (is (approx= 60.0 (e/col-sum col) 0.001))
      (is (approx= 20.0 (e/col-mean col) 0.001)))))

(deftest min-max-val-test
  (testing "min-val and max-val"
    (let [col (e/eve-array :int32 [30 10 20 50 5])]
      (is (= 5 (e/col-min-val col)))
      (is (= 50 (e/col-max-val col))))))

(deftest arithmetic-ops-test
  (testing "add, mul, sub, div (array x array)"
    (let [a (e/eve-array :int32 [10 20 30])
          b (e/eve-array :int32 [1 2 3])
          s (e/col-add a b)
          d (e/col-sub a b)
          m (e/col-mul a b)]
      (is (= 11 (nth s 0)))
      (is (= 22 (nth s 1)))
      (is (= 9 (nth d 0)))
      (is (= 10 (nth m 0)))
      (is (= 90 (nth m 2)))))
  (testing "array x scalar"
    (let [a (e/eve-array :int32 [10 20 30])
          m (e/col-mul a 2)]
      (is (= 20 (nth m 0)))
      (is (= 60 (nth m 2)))))
  (testing "div returns float64"
    (let [a (e/eve-array :int32 [10 20])
          b (e/eve-array :int32 [3 4])
          r (e/col-div a b)]
      (is (approx= 3.333 (nth r 0) 0.01))
      (is (approx= 5.0 (nth r 1) 0.001)))))

(deftest comparison-ops-test
  (testing "gt, lt, eq return uint8 mask"
    (let [col (e/eve-array :int32 [10 20 30 40 50])
          mask-gt (e/col-gt col 25)
          mask-lt (e/col-lt col 25)
          mask-eq (e/col-eq col 30)]
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
;; Argops (on EveArray columns — no atom needed)
;;=============================================================================

(deftest argsort-test
  (testing "argsort ascending"
    (let [col (e/eve-array :int32 [30 10 20])
          idx (e/argsort col :asc)]
      (is (= 1 (nth idx 0)))  ;; 10 is at original index 1
      (is (= 2 (nth idx 1)))  ;; 20 is at original index 2
      (is (= 0 (nth idx 2)))));; 30 is at original index 0
  (testing "argsort descending"
    (let [col (e/eve-array :int32 [30 10 20])
          idx (e/argsort col :desc)]
      (is (= 0 (nth idx 0)))
      (is (= 2 (nth idx 1)))
      (is (= 1 (nth idx 2))))))

(deftest argfilter-test
  (testing "argfilter"
    (let [col (e/eve-array :int32 [10 20 30 40 50])
          idx (e/argfilter col #(> % 25))]
      (is (= 3 (count idx)))
      (is (= 2 (nth idx 0)))
      (is (= 3 (nth idx 1)))
      (is (= 4 (nth idx 2))))))

(deftest argmin-argmax-test
  (testing "argmin and argmax"
    (let [col (e/eve-array :int32 [30 10 50 20])]
      (is (= 1 (e/argmin col)))
      (is (= 2 (e/argmax col))))))

(deftest take-indices-reindex-test
  (testing "take-indices"
    (let [col (e/eve-array :int32 [10 20 30 40 50])
          idx (e/eve-array :int32 [4 2 0])
          result (e/take-indices col idx)]
      (is (= 3 (count result)))
      (is (= 50 (nth result 0)))
      (is (= 30 (nth result 1)))
      (is (= 10 (nth result 2)))))
  #?(:cljs
     (testing "reindex dataset"
       (let [ea (make-eve-atom nil)
             ds2 (swap! ea (fn [_]
                             (let [ds1 (e/dataset {:v (e/eve-array :int32 [10 20 30])})
                                   idx (e/eve-array :int32 [2 0 1])]
                               (e/ds-reindex ds1 idx))))]
         (is (= 30 (nth (e/ds-column ds2 :v) 0)))
         (is (= 10 (nth (e/ds-column ds2 :v) 1)))
         (is (= 20 (nth (e/ds-column ds2 :v) 2)))))))

;;=============================================================================
;; Multiple dtypes in one dataset
;;=============================================================================

#?(:cljs
   (deftest multi-dtype-test
     (testing "dataset with multiple dtypes"
       (let [ea (make-eve-atom nil)
             ds1 (swap! ea (fn [_]
                             (e/dataset {:id    (e/eve-array :int32 [1 2 3])
                                          :value (e/eve-array :float64 [1.1 2.2 3.3])
                                          :flag  (e/eve-array :uint8 [1 0 1])})))]
         (is (= :int32 (:id (e/ds-dtypes ds1))))
         (is (= :float64 (:value (e/ds-dtypes ds1))))
         (is (= :uint8 (:flag (e/ds-dtypes ds1))))
         (is (= 3 (e/ds-row-count ds1)))))))

;;=============================================================================
;; ILookup / IFn
;;=============================================================================

#?(:cljs
   (deftest dataset-lookup-test
     (testing "Dataset implements ILookup"
       (let [ea (make-eve-atom nil)
             ds1 (swap! ea (fn [_]
                             (e/dataset {:a (e/eve-array :int32 [1 2 3])})))]
         (is (some? (:a ds1)))
         (is (some? (ds1 :a)))
         (is (nil? (:missing ds1)))))))

;;=============================================================================
;; Emap
;;=============================================================================

(deftest emap-test
  (testing "emap on single column"
    (let [col (e/eve-array :int32 [10 20 30])
          result (e/col-emap #(* % 2) col)]
      (is (= 3 (count result)))
      (is (= 20 (nth result 0)))
      (is (= 60 (nth result 2)))))
  (testing "emap2 on two columns"
    (let [a (e/eve-array :int32 [1 2 3])
          b (e/eve-array :int32 [10 20 30])
          result (e/col-emap2 + a b)]
      (is (= 11 (nth result 0)))
      (is (= 33 (nth result 2))))))
