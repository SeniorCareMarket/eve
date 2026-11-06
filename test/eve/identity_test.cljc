(ns eve.identity-test
  "Lane M's identity audit (2026-11-06, docs/cohost/debug-seam/audit-identity on muse/cohost-reports): an Eve value's
   identity is answered in Eve's vocabulary on every host. Its §C rows as assertions — stock Clojure questions asked
   of Eve values answer as they do of the host's own collections; two sets with a by-reference member compare equal,
   and a hash set and a sorted set of the same members, both ways (B-SET, B-SET-X); an Eve vector is a [k v] entry to
   a map, hash and sorted (B-VEC-ENTRY); compare reaches a type's own comparison (B-COMPARE)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [field]]
            [eve.deftype #?@(:cljs [:refer-macros [eve-deftype]] :default [:refer [eve-deftype]])]
            [eve.core :as e]
            [eve.map :as m]
            [eve.set :as s]
            [eve.vec :as v]
            [eve.list :as l]
            [eve.sorted :as so]))

;; The stock predicates and stock compare are the host's own on babashka, whose Eve values are sci types (the audit's
;; §E bb column): there they are not Eve's to answer. Every other row runs on every host.
#?(:bb nil :default
(deftest stock-predicates-answer-for-eve-values
  (let [ev (v/eve-vec [1 2 3]) em (m/hash-map :a 1) es (s/hash-set 1 2) el (l/eve-list [1 2])]
    (testing "the kind"
      (is (vector? ev)) (is (map? em)) (is (set? es)) (is (list? el)) (is (seq? el)))
    (testing "the family"
      (is (coll? ev)) (is (coll? em)) (is (coll? es)) (is (coll? el))
      (is (sequential? ev)) (is (sequential? el))
      (is (associative? em)) (is (associative? ev))
      (is (counted? ev)) (is (counted? em)) (is (counted? es))
      (is (reversible? ev)))
    (testing "and not what it is not"
      (is (not (map? ev))) (is (not (vector? em))) (is (not (seq? ev))) (is (not (list? ev))) (is (not (set? em)))))))

(defn- by-ref [x] (+ x 1))

(deftest sets-compare-in-eves-vocabulary
  (testing "two Eve sets holding a by-reference member (the digest abstains): equal"
    (is (= (s/hash-set by-ref 1) (s/hash-set by-ref 1)))
    (is (not= (s/hash-set by-ref 1) (s/hash-set by-ref 2))))
  (testing "a hash set and a sorted set of the same members, both ways"
    (is (= (s/hash-set 1 2 3) (so/sorted-set 1 2 3)))
    (is (= (so/sorted-set 1 2 3) (s/hash-set 1 2 3)))
    (is (not= (so/sorted-set 1 2) (s/hash-set 1 2 3)))))

(deftest an-eve-vector-is-a-map-entry
  (testing "conj of an Eve [k v] onto a hash map: one entry, k to v"
    (let [r (e/conj (m/hash-map :a 1) (v/eve-vec [:k 2]))]
      (is (= 2 (e/count r)))
      (is (= 2 (e/get r :k)))))
  (testing "and onto a sorted map"
    (let [r (e/conj (so/sorted-map :a 1) (v/eve-vec [:k 2]))]
      (is (= 2 (e/count r)))
      (is (= 2 (e/get r :k))))))

#?(:bb nil :default
(eve-deftype Rank [^:int32 n]
             IComparable
             (-compare [_ o] (compare n (field o n)))))

#?(:bb nil :default
(deftest compare-reaches-the-types-own
  (is (neg? (compare (->Rank 1) (->Rank 2))))
  (is (zero? (compare (->Rank 2) (->Rank 2))))
  (is (= [1 2 3] (mapv (fn [r] (field r n)) (sort [(->Rank 3) (->Rank 1) (->Rank 2)]))))))

;; A field read written as stock Clojure — (.-f x) on an untyped parameter, as an external .cljc structure writes it
;; (the audit's B-RRB class) — reads the Eve object's field on every host (on jank the completion defers it to its
;; field read).
(eve-deftype Tagged [label n])

(defn- label-of [t] (.-label t))
(defn- n-of [t] (.-n t))

(deftest a-stock-field-read-reads-the-eve-field
  (let [t (->Tagged "eve" 7)]
    (is (= "eve" (label-of t)))
    (is (= 7 (n-of t)))
    (is (= [["eve" 7]] (mapv (fn [x] [(.-label x) (.-n x)]) [t])))))
