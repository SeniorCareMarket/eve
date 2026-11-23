(ns eve.sorted-migration-test
  "The migration check for the sorted-collections trunk consolidation
   (trunk audit entry 3, Nov 14 2026): the semantic deftests of the retired
   eve.deftype.rb-tree suite, ported onto eve.sorted's treap. Every assertion
   here is one the red-black suite held for its SortedSet, re-expressed
   through the generic sorted-set surface (sorted-set / conj / disj / get /
   contains? / seq) that all of rb-tree's real consumers already used.
   The rb-internal deftests — red-black invariants (rb-valid?), height and
   black-height bounds, RBNode field/print shape — retired with the trunk:
   they tested the deleted implementation, not the sorted-set contract.
   The treap's own shape property (one canonical tree) is covered by
   eve.sorted-coll-test."
  ;; swap! on an eve atom: eve.host's (clojerl's own reaches its own atoms only)
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]
   [eve.host :refer [swap!]]
   [eve.core :as e]
   #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
      :default [clojure.test :refer [deftest testing is use-fixtures]])
   [eve.atom :as atom]
   [eve.sorted :as so]))

(defn- in-a-domain-of-its-own
  "Construction law (2026-11-11): Eve data is constructed in a domain
   context, never through a process-heap fallback (alloc/current-sio throws
   outside one). Each test here builds in an explicit heap domain of its
   own (rrb-vector-test's fixture), dropped with the test."
  [f]
  (binding [d/*domain-sio* (alloc/heap-domain-sio!
                            {:capacities {0 (* 64 1024 1024)
                                          1 (* 16 1024 1024)
                                          2 (* 16 1024 1024)
                                          3 (* 192 1024 1024)
                                          4 (* 16 1024 1024)
                                          5 (* 16 1024 1024)}})]
    (f)))

(use-fixtures :each in-a-domain-of-its-own)

;;-----------------------------------------------------------------------------
;; Helpers
;;-----------------------------------------------------------------------------

(defn build-set
  "Conj a sequence of values into an empty sorted set, in the given order."
  [vals]
  (reduce (fn [s v] (e/conj s v)) (so/sorted-set) vals))

;;-----------------------------------------------------------------------------
;; Basic construction (rb suite: empty-tree / single / two / three insert)
;;-----------------------------------------------------------------------------

(deftest empty-set-test
  (testing "Empty sorted set"
    (let [s (so/sorted-set)]
      (is (= 0 (e/count s)))
      (is (nil? (e/seq s)))
      (is (= s (so/sorted-set))))))

(deftest single-insert-test
  (testing "Insert one element"
    (let [s (build-set [42])]
      (is (= 1 (e/count s)))
      (is (= [42] (vec (e/seq s))))
      (is (e/contains? s 42)))))

(deftest two-insert-test
  (testing "Insert two elements"
    (let [s (build-set [10 20])]
      (is (= 2 (e/count s)))
      (is (= [10 20] (vec (e/seq s)))))))

(deftest three-insert-test
  (testing "Insert three elements"
    (let [s (build-set [10 20 30])]
      (is (= 3 (e/count s)))
      (is (= [10 20 30] (vec (e/seq s)))))))

;;-----------------------------------------------------------------------------
;; Membership & find (rb suite: member-test / find-test)
;;-----------------------------------------------------------------------------

(deftest member-test
  (testing "Membership queries"
    (let [s (build-set [5 3 8 1 4 7 9])]
      (is (e/contains? s 5))
      (is (e/contains? s 1))
      (is (e/contains? s 9))
      (is (not (e/contains? s 0)))
      (is (not (e/contains? s 6)))
      (is (not (e/contains? s 100))))))

(deftest find-test
  (testing "Find returns the value or nil"
    (let [s (build-set [10 20 30])]
      (is (= 10 (e/get s 10)))
      (is (= 30 (e/get s 30)))
      (is (nil? (e/get s 15))))))

;;-----------------------------------------------------------------------------
;; Ordering & sequence (rb suite: sorted-seq / duplicate-insert)
;;-----------------------------------------------------------------------------

(deftest sorted-seq-test
  (testing "In-order traversal is sorted regardless of insertion order"
    (let [vals [7 3 18 10 22 8 11 26 2 6 13]
          s (build-set vals)]
      (is (= (sort vals) (vec (e/seq s)))))))

(deftest duplicate-insert-test
  (testing "Inserting duplicates doesn't change the set"
    (let [s1 (build-set [1 2 3 4 5])
          s2 (e/conj s1 3)]
      (is (= 5 (e/count s2)) "count unchanged after duplicate insert")
      (is (= [1 2 3 4 5] (vec (e/seq s2)))))))

;;-----------------------------------------------------------------------------
;; Contents at scale (rb suite: invariants-small / medium / reverse,
;; minus the rb-valid? assertions that tested the deleted tree's shape)
;;-----------------------------------------------------------------------------

(deftest contents-small-test
  (testing "Contents hold for small sets"
    (doseq [n (range 1 8)]
      (let [s (build-set (range n))]
        (is (= n (e/count s)) (str "count for " n " elements"))
        (is (= (vec (range n)) (vec (e/seq s))))))))

(deftest contents-medium-test
  (testing "Contents hold for 100 sequential inserts"
    (let [s (build-set (range 100))]
      (is (= 100 (e/count s)))
      (is (= (vec (range 100)) (vec (e/seq s)))))))

(deftest contents-reverse-test
  (testing "Contents hold for reverse-order inserts"
    (let [s (build-set (reverse (range 50)))]
      (is (= 50 (e/count s)))
      (is (= (vec (range 50)) (vec (e/seq s)))))))

;;-----------------------------------------------------------------------------
;; Non-numeric values (rb suite: string-values / keyword-values)
;;-----------------------------------------------------------------------------

(deftest string-values-test
  (testing "Sorted set with string values"
    (let [words ["fig" "date" "apple" "cherry" "banana" "elderberry"]
          s (build-set words)]
      (is (= (sort words) (vec (e/seq s))))
      (is (e/contains? s "cherry"))
      (is (not (e/contains? s "grape"))))))

(deftest keyword-values-test
  (testing "Sorted set with keyword values"
    (let [ks [:z :a :m :f :b :q]
          s (build-set ks)]
      (is (= (sort ks) (vec (e/seq s))))
      (is (= 6 (e/count s))))))

;;-----------------------------------------------------------------------------
;; SortedSet wrapper tests (rb suite's user-facing section, verbatim shape)
;;-----------------------------------------------------------------------------

(deftest sorted-set-empty-test
  (testing "Empty sorted set"
    (let [s (so/sorted-set)]
      (is (= 0 (e/count s)))
      (is (nil? (e/seq s))))))

(deftest sorted-set-construction-test
  (testing "Create sorted set from values"
    (let [s (so/sorted-set 3 1 4 1 5 9 2 6)]
      (is (= 7 (e/count s)) "duplicates are removed")
      (is (= [1 2 3 4 5 6 9] (vec (e/seq s))) "sorted order"))))

(deftest sorted-set-conj-test
  (testing "conj adds elements"
    (let [s (-> (so/sorted-set) (e/conj 10) (e/conj 5) (e/conj 15))]
      (is (= 3 (e/count s)))
      (is (= [5 10 15] (vec (e/seq s)))))))

(deftest sorted-set-lookup-test
  (testing "Lookup and membership"
    (let [s (so/sorted-set 10 20 30)]
      (is (= 10 (e/get s 10)))
      (is (= 20 (e/invoke s 20)))
      (is (nil? (e/get s 99)))
      (is (= :nope (e/get s 99 :nope)))
      (is (e/contains? s 10))
      (is (not (e/contains? s 99))))))

(deftest sorted-set-strings-test
  (testing "Sorted set with string values"
    (let [s (so/sorted-set "fig" "date" "apple" "cherry" "banana")]
      (is (= ["apple" "banana" "cherry" "date" "fig"] (vec (e/seq s))))
      (is (e/contains? s "cherry"))
      (is (not (e/contains? s "grape"))))))

(deftest sorted-set-keywords-test
  (testing "Sorted set with keyword values"
    (let [s (so/sorted-set :z :a :m :f :b :q)]
      (is (= (sort [:z :a :m :f :b :q]) (vec (e/seq s))))
      (is (= 6 (e/count s))))))

(deftest sorted-set-meta-test
  (testing "with-meta / meta"
    (let [s (e/with-meta (so/sorted-set 1 2 3) {:doc "test"})]
      (is (= {:doc "test"} (e/meta s))))))

(deftest sorted-set-equality-test
  (testing "Two sorted sets with same values are equal"
    (let [s1 (so/sorted-set 3 1 2)
          s2 (so/sorted-set 1 2 3)]
      (is (= s1 s2)))))

(deftest sorted-set-print-test
  (testing "SortedSet prints readable representation"
    (let [s (so/sorted-set 3 1 2)]
      (is (string? (pr-str s))))))

;;-----------------------------------------------------------------------------
;; A sorted set lives in an atom like any Eve value
;;-----------------------------------------------------------------------------

(deftest sorted-set-in-an-atom-test
  (testing "built inside swap!, read back from the atom"
    (let [a (atom/atom {:id ::sorted-set-in-an-atom} nil)]
      (swap! a (fn [_] {:s (so/sorted-set 30 10 20)}))
      (swap! a update :s e/conj 5)
      (let [s (:s @a)]
        (is (= [5 10 20 30] (vec (e/seq s))))
        (is (= 4 (e/count s)))
        (is (e/contains? s 20))))))

;;-----------------------------------------------------------------------------
;; Deletion (rb suite: rb-delete-test, at the set surface)
;;-----------------------------------------------------------------------------

(deftest delete-every-value-test
  (testing "deleting every value, in an order unlike the insertion order"
    (let [ins  (map #(mod (* % 37) 101) (range 101))
          dels (map #(mod (* % 53) 101) (range 101))
          s0   (build-set ins)]
      (is (= (range 101) (e/seq s0)))
      (loop [s s0 [d & ds] dels gone #{}]
        (when d
          (let [s2 (e/disj s d)
                gone (conj gone d)]
            (is (= (remove gone (range 101)) (or (e/seq s2) ()))
                (str "the rest after deleting " d))
            (recur s2 ds gone))))))
  (testing "deleting an absent value answers an equal set"
    (let [s (build-set [30 10 20])]
      (is (= s (e/disj s 99)))))
  (testing "deleting a leaf value"
    (is (= [20 30] (vec (e/seq (e/disj (build-set [30 10 20]) 10)))))))

(deftest sorted-set-disj-test
  (testing "disj removes; the set stays sorted"
    (let [s (so/sorted-set 5 3 8 1 9 2)
          s2 (e/disj (e/disj s 8) 1)]
      (is (= [2 3 5 9] (vec (e/seq s2))))
      (is (= 4 (e/count s2)))
      (is (not (e/contains? s2 8)))
      (is (= [1 2 3 5 8 9] (vec (e/seq s))) "the original is unchanged")))
  (testing "disj inside swap!, read back from the atom"
    (let [a (atom/atom {:id ::sorted-set-disj} nil)]
      (swap! a (fn [_] {:s (so/sorted-set 30 10 20)}))
      (swap! a update :s e/disj 10)
      (is (= [20 30] (vec (e/seq (:s @a))))))))
