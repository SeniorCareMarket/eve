(ns eve.deftype.rb-tree-test
  "One test file for every host: the tree is one eve-deftype definition."
  ;; swap! on an eve atom: eve.host's (clojerl's own reaches its own atoms only)
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.core :as e]
   #?(:cljs [cljs.test :refer-macros [deftest testing is]]
      :default [clojure.test :refer [deftest testing is]])
   [eve.atom :as atom]
   [eve.deftype.rb-tree :as rb :refer [rb-insert rb-member?
                                                    rb-find rb-seq rb-count
                                                    rb-height rb-black-height
                                                    rb-valid? BLACK rb-delete
                                                    sorted-set member?]]))

;;-----------------------------------------------------------------------------
;; Helpers
;;-----------------------------------------------------------------------------

(defn build-tree
  "Insert a sequence of values into an empty tree."
  [vals]
  (reduce (fn [tree v] (rb-insert tree v)) nil vals))

;;-----------------------------------------------------------------------------
;; Basic construction
;;-----------------------------------------------------------------------------

(deftest empty-tree-test
  (testing "Empty tree is nil"
    (is (nil? nil) "nil represents empty tree")
    (is (= 0 (rb-count nil)))
    (is (rb-valid? nil))
    (is (empty? (rb-seq nil)))))

(deftest single-insert-test
  (testing "Insert one element"
    (let [t (rb-insert nil 42)]
      (is (some? t))
      (is (= BLACK (e/get t :color)) "root must be black")
      (is (nil? (e/get t :left)))
      (is (nil? (e/get t :right)))
      (is (= 42 (e/get t :value)))
      (is (= 1 (rb-count t)))
      (is (rb-valid? t)))))

(deftest two-insert-test
  (testing "Insert two elements"
    (let [t (-> nil
                (as-> t (rb-insert t 10))
                (as-> t (rb-insert t 20)))]
      (is (= 2 (rb-count t)))
      (is (rb-valid? t))
      (is (= [10 20] (vec (rb-seq t)))))))

(deftest three-insert-test
  (testing "Insert three elements — triggers first balance"
    (let [t (build-tree [10 20 30])]
      (is (= 3 (rb-count t)))
      (is (rb-valid? t))
      (is (= [10 20 30] (vec (rb-seq t)))))))

;;-----------------------------------------------------------------------------
;; Membership & find
;;-----------------------------------------------------------------------------

(deftest member-test
  (testing "Membership queries"
    (let [t (build-tree [5 3 8 1 4 7 9])]
      (is (rb-member? t 5))
      (is (rb-member? t 1))
      (is (rb-member? t 9))
      (is (not (rb-member? t 0)))
      (is (not (rb-member? t 6)))
      (is (not (rb-member? t 100))))))

(deftest find-test
  (testing "Find returns the value or nil"
    (let [t (build-tree [10 20 30])]
      (is (= 10 (rb-find t 10)))
      (is (= 30 (rb-find t 30)))
      (is (nil? (rb-find t 15))))))

;;-----------------------------------------------------------------------------
;; Ordering & sequence
;;-----------------------------------------------------------------------------

(deftest sorted-seq-test
  (testing "In-order traversal is sorted regardless of insertion order"
    (let [vals [7 3 18 10 22 8 11 26 2 6 13]
          t (build-tree vals)]
      (is (= (sort vals) (vec (rb-seq t)))))))

(deftest duplicate-insert-test
  (testing "Inserting duplicates doesn't change the tree"
    (let [t1 (build-tree [1 2 3 4 5])
          t2 (rb-insert t1 3)]
      (is (= 5 (rb-count t2)) "count unchanged after duplicate insert")
      (is (= [1 2 3 4 5] (vec (rb-seq t2)))))))

;;-----------------------------------------------------------------------------
;; Invariant checks
;;-----------------------------------------------------------------------------

(deftest invariants-small-test
  (testing "RB invariants hold for small trees"
    (doseq [n (range 1 8)]
      (let [t (build-tree (range n))]
        (is (rb-valid? t) (str "valid for " n " elements"))
        (is (= n (rb-count t)))))))

(deftest invariants-medium-test
  (testing "RB invariants hold for 100 sequential inserts"
    (let [t (build-tree (range 100))]
      (is (rb-valid? t))
      (is (= 100 (rb-count t)))
      (is (= (vec (range 100)) (vec (rb-seq t)))))))

(deftest invariants-reverse-test
  (testing "RB invariants hold for reverse-order inserts"
    (let [t (build-tree (reverse (range 50)))]
      (is (rb-valid? t))
      (is (= 50 (rb-count t)))
      (is (= (vec (range 50)) (vec (rb-seq t)))))))

;;-----------------------------------------------------------------------------
;; Height bounds
;;-----------------------------------------------------------------------------

(deftest height-bound-test
  (testing "Height is bounded by 2*log2(n+1) for a valid RB tree"
    (let [n 100
          t (build-tree (range n))
          h (rb-height t)
          ;; 2·ceil(log2(n+1)): the least k with 2^k >= n+1, in integers
          ;; (the host's Math is Math/log or Math/Log)
          max-h (* 2 (loop [k 0 p 1] (if (>= p (inc n)) k (recur (inc k) (* 2 p)))))]
      (is (<= h max-h)
          (str "height " h " should be <= " max-h " for " n " elements")))))

(deftest black-height-test
  (testing "Black-height is consistent across all paths"
    (let [t (build-tree (range 50))
          bh (rb-black-height t)]
      (is (pos? bh) "black-height should be positive for non-empty tree")
      (is (not= -1 bh) "black-height should be uniform"))))

;;-----------------------------------------------------------------------------
;; Fressian field values (non-numeric)
;;-----------------------------------------------------------------------------

(deftest string-values-test
  (testing "RB tree with string values"
    (let [words ["fig" "date" "apple" "cherry" "banana" "elderberry"]
          t (build-tree words)]
      (is (rb-valid? t))
      (is (= (sort words) (vec (rb-seq t))))
      (is (rb-member? t "cherry"))
      (is (not (rb-member? t "grape"))))))

(deftest keyword-values-test
  (testing "RB tree with keyword values"
    (let [ks [:z :a :m :f :b :q]
          t (build-tree ks)]
      (is (rb-valid? t))
      (is (= (sort ks) (vec (rb-seq t))))
      (is (= 6 (rb-count t))))))

;;-----------------------------------------------------------------------------
;; Print representation
;;-----------------------------------------------------------------------------

(deftest print-test
  (testing "RBNode prints readable representation"
    (let [t (rb-insert nil 42)]
      (is (string? (pr-str t))))))

;;-----------------------------------------------------------------------------
;; SortedSet (user-facing wrapper) tests
;;-----------------------------------------------------------------------------

(deftest sorted-set-empty-test
  (testing "Empty sorted set"
    (let [s (sorted-set)]
      (is (= 0 (e/count s)))
      (is (nil? (e/seq s))))))

(deftest sorted-set-construction-test
  (testing "Create sorted set from values"
    (let [s (sorted-set 3 1 4 1 5 9 2 6)]
      (is (= 7 (e/count s)) "duplicates are removed")
      (is (= [1 2 3 4 5 6 9] (vec (e/seq s))) "sorted order"))))

(deftest sorted-set-conj-test
  (testing "conj adds elements"
    (let [s (-> (sorted-set) (e/conj 10) (e/conj 5) (e/conj 15))]
      (is (= 3 (e/count s)))
      (is (= [5 10 15] (vec (e/seq s)))))))

(deftest sorted-set-lookup-test
  (testing "Lookup and membership"
    (let [s (sorted-set 10 20 30)]
      (is (= 10 (e/get s 10)))
      (is (= 20 (e/invoke s 20)))
      (is (nil? (e/get s 99)))
      (is (= :nope (e/get s 99 :nope)))
      (is (member? s 10))
      (is (not (member? s 99))))))

(deftest sorted-set-strings-test
  (testing "Sorted set with string values"
    (let [s (sorted-set "fig" "date" "apple" "cherry" "banana")]
      (is (= ["apple" "banana" "cherry" "date" "fig"] (vec (e/seq s))))
      (is (member? s "cherry"))
      (is (not (member? s "grape"))))))

(deftest sorted-set-keywords-test
  (testing "Sorted set with keyword values"
    (let [s (sorted-set :z :a :m :f :b :q)]
      (is (= (sort [:z :a :m :f :b :q]) (vec (e/seq s))))
      (is (= 6 (e/count s))))))

(deftest sorted-set-meta-test
  (testing "with-meta / meta"
    (let [s (e/with-meta (sorted-set 1 2 3) {:doc "test"})]
      (is (= {:doc "test"} (e/meta s))))))

(deftest sorted-set-equality-test
  (testing "Two sorted sets with same values are equal"
    (let [s1 (sorted-set 3 1 2)
          s2 (sorted-set 1 2 3)]
      (is (= s1 s2)))))

(deftest sorted-set-print-test
  (testing "SortedSet prints readable representation"
    (let [s (sorted-set 3 1 2)]
      (is (string? (pr-str s))))))

;;-----------------------------------------------------------------------------
;; A sorted set lives in an atom like any Eve value
;;-----------------------------------------------------------------------------

(deftest sorted-set-in-an-atom-test
  (testing "built inside swap!, read back from the atom"
    (let [a (atom/atom {:id ::sorted-set-in-an-atom} nil)]
      (swap! a (fn [_] {:s (sorted-set 30 10 20)}))
      (swap! a update :s e/conj 5)
      (let [s (:s @a)]
        (is (= [5 10 20 30] (vec (e/seq s))))
        (is (= 4 (e/count s)))
        (is (member? s 20))))))

;;-----------------------------------------------------------------------------
;; Deletion — never exercised before 2026-10-16, when it threw on the JVM too
;;-----------------------------------------------------------------------------

(deftest rb-delete-test
  (testing "deleting every value, in an order unlike the insertion order"
    (let [ins  (map #(mod (* % 37) 101) (range 101))
          dels (map #(mod (* % 53) 101) (range 101))
          t0   (build-tree ins)]
      (is (= (range 101) (rb-seq t0)))
      (loop [t t0 [d & ds] dels gone #{}]
        (when d
          (let [t2 (rb-delete t d)
                gone (conj gone d)]
            (is (rb-valid? t2) (str "valid after deleting " d))
            (is (= (remove gone (range 101)) (or (rb-seq t2) ()))
                (str "the rest after deleting " d))
            (recur t2 ds gone))))))
  (testing "deleting an absent value answers the same tree"
    (let [t (build-tree [30 10 20])]
      (is (identical? t (rb-delete t 99)))))
  (testing "a black leaf beside a black sibling (the case that threw)"
    (is (= [20 30] (vec (rb-seq (rb-delete (build-tree [30 10 20]) 10)))))))

(deftest sorted-set-disj-test
  (testing "disj removes; the set stays sorted"
    (let [s (sorted-set 5 3 8 1 9 2)
          s2 (e/disj (e/disj s 8) 1)]
      (is (= [2 3 5 9] (vec (e/seq s2))))
      (is (= 4 (e/count s2)))
      (is (not (member? s2 8)))
      (is (= [1 2 3 5 8 9] (vec (e/seq s))) "the original is unchanged")))
  (testing "disj inside swap!, read back from the atom"
    (let [a (atom/atom {:id ::sorted-set-disj} nil)]
      (swap! a (fn [_] {:s (sorted-set 30 10 20)}))
      (swap! a update :s e/disj 10)
      (is (= [20 30] (vec (e/seq (:s @a))))))))
