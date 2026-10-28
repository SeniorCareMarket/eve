(ns eve.net.seen-set-test
  "The fetch walk's seen-set (eve.net.atom: empty-reached / see-all / reached?) — key folds in an
   open-addressed array, the merkle plan §8.2 interning row's shape (Phase 7). Membership is exact
   for every key folded in (no false negatives at any size, growth included); a key never folded
   in is reported only when its 52-bit fold collides with a folded key's — the documented trade,
   pinned by the last test."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.net.atom :as na]))

(defn- key-of
  "a 64-char hex block key whose first 13 hex chars are i, zero-padded — the fold
   (the first 13 chars) is i itself, so distinct i have distinct folds"
  [i]
  (let [h #?(:clj (Long/toString i 16) :cljs (.toString i 16))
        p (str (apply str (repeat (- 13 (count h)) "0")) h)]
    (str p (apply str (repeat 51 "0")))))

(defn- slots-of [seen] #?(:clj (alength (:slots seen)) :cljs (.-length (:slots seen))))

(deftest seen-set-membership
  (testing "an empty seen-set reaches nothing"
    (is (not (na/reached? (na/empty-reached) (key-of 1)))))
  (testing "slots that hold nil read as empty (ClojureScript's long-array fills with nil): a probe ends, a fold enters"
    (let [blank {:slots (object-array 1024) :n 0}]
      (is (not (na/reached? blank (key-of 1))))
      (is (na/reached? (na/see-all blank [(key-of 1) (key-of 2)]) (key-of 2)))))
  (testing "every folded key is reached, across growth (1024 slots -> tens of thousands)"
    (let [seen (na/see-all (na/empty-reached) (map key-of (range 20000)))]
      (is (= 20000 (:n seen)))
      (is (every? (fn [i] (na/reached? seen (key-of i))) (range 20000)))
      (testing "the §8.2 interning bar: the table retains <= 16 B/entry at rest"
        (is (<= (* 8 (slots-of seen)) (* 16 20000))
            (str "slots=" (slots-of seen) " for 20000 entries")))
      (testing "keys never folded in are not reached (their folds differ by construction)"
        (is (not-any? (fn [i] (na/reached? seen (key-of i))) (range 20000 40000))))
      (testing "folding the same keys again changes nothing"
        (let [again (na/see-all seen (map key-of (range 20000)))]
          (is (= 20000 (:n again)))
          (is (every? (fn [i] (na/reached? again (key-of i))) (range 20000))))))))

(deftest seen-set-fold-collision-is-the-documented-behaviour
  (testing "two keys sharing their first 13 hex chars share a fold: the second reads as reached.
            In a fetch walk that skips a subtree whose blocks then fail to decode — loud, never
            a wrong value (fetched blocks are checked against their keys)."
    (let [k1 (str "0123456789abc" (apply str (repeat 51 "0")))
          k2 (str "0123456789abc" (apply str (repeat 51 "f")))
          seen (na/see-all (na/empty-reached) [k1])]
      (is (na/reached? seen k1))
      (is (na/reached? seen k2) "the fold cannot tell k2 from k1 — the priced trade"))))
