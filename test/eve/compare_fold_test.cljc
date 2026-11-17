(ns eve.compare-fold-test
  "The comparison relations at 2, 3 and 4 arguments, equal and unequal values: the basilisp completion folds = == < >
   <= >= at three arguments as a pairwise chain, and not= must stay (not (= ...)) — never a fold of not= over its own
   result ((not= (not= 1 1) 1) is true). Every host answers clojure.core's values."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host-completion]))

(defn- rel
  "f applied through a call site of each arity (direct call sites, not apply: the folds live at the fixed arities)."
  [f & xs]
  (case (count xs)
    2 (f (nth xs 0) (nth xs 1))
    3 (f (nth xs 0) (nth xs 1) (nth xs 2))
    4 (f (nth xs 0) (nth xs 1) (nth xs 2) (nth xs 3))))

(deftest not=-at-each-arity
  (testing "direct call sites"
    (is (false? (not= 1 1)))
    (is (true?  (not= 1 2)))
    (is (false? (not= 1 1 1)))
    (is (true?  (not= 1 1 2)))
    (is (true?  (not= 1 2 1)))
    (is (true?  (not= 2 1 1)))
    (is (false? (not= 1 1 1 1)))
    (is (true?  (not= 1 1 1 2)))
    (is (true?  (not= 2 1 1 1)))
    (is (false? (not= :a :a :a)))
    (is (true?  (not= :a :a :b))))
  (testing "through a var (the fn value) and apply"
    (is (false? (rel not= 1 1)))
    (is (false? (rel not= 1 1 1)))
    (is (true?  (rel not= 1 1 2)))
    (is (false? (rel not= 1 1 1 1)))
    (is (true?  (rel not= 1 1 1 2)))
    (is (false? (apply not= [1 1 1])))
    (is (true?  (apply not= [1 2 1])))))

(deftest equality-and-order-chains
  (is (true?  (= 1 1 1)))   (is (false? (= 1 1 2)))  (is (false? (= 2 1 1)))
  (is (true?  (= 1 1 1 1))) (is (false? (= 1 1 1 2)))
  (is (true?  (== 1 1 1)))  (is (false? (== 1 1 2)))
  (is (true?  (< 1 2 3)))   (is (false? (< 1 3 2)))  (is (false? (< 1 1 2)))
  (is (true?  (< 1 2 3 4))) (is (false? (< 1 2 4 3)))
  (is (true?  (<= 1 1 2)))  (is (false? (<= 1 2 1)))
  (is (true?  (> 3 2 1)))   (is (false? (> 3 1 2)))
  (is (true?  (>= 2 2 1)))  (is (false? (>= 1 2 2)))
  (is (false? (= 1 2 1)))   (is (false? (< 2 1 3))))
