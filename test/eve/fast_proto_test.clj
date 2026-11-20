(ns eve.fast-proto-test
  "eve.fast-proto/fast-satisfies? answers what satisfies? answers (2026-11-20 ET): an inline implementation, a miss,
   nil, an extend after a remembered miss (the memory must not outlive the protocol's :impls), an extend to a
   superclass and to Object."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.fast-proto :as fp]))

(defprotocol IProbe (-probe [x]))
(defprotocol IOther (-other [x]))

(deftype Inline [] IProbe (-probe [_] :inline))
(deftype Later [])
(defrecord Rec [a])

(deftest agrees-with-satisfies
  (doseq [x [(Inline.) (Later.) (->Rec 1) nil 1 :k "s" [1] {:a 1} #{1} '(1) (Object.)]]
    (is (= (satisfies? IProbe x) (fp/fast-satisfies? IProbe x)) (str "IProbe " (pr-str (class x))))
    (is (= (satisfies? IOther x) (fp/fast-satisfies? IOther x)) (str "IOther " (pr-str (class x))))))

(deftest an-extend-after-a-remembered-miss-is-seen
  (testing "a miss, asked twice: the second answer is the remembered one"
    (is (false? (fp/fast-satisfies? IOther (Later.))))
    (is (false? (fp/fast-satisfies? IOther (Later.)))))
  (testing "extended now: the protocol's :impls is replaced and the memory with it"
    (extend-type Later IOther (-other [_] :later))
    (is (true? (fp/fast-satisfies? IOther (Later.))))
    (is (= (satisfies? IOther (Later.)) (fp/fast-satisfies? IOther (Later.))))))

(deftest an-extend-to-a-superclass-is-seen
  (is (false? (fp/fast-satisfies? IOther (java.util.ArrayList.))))
  (extend-type java.util.AbstractList IOther (-other [_] :list))
  (is (true? (fp/fast-satisfies? IOther (java.util.ArrayList.))))
  (is (true? (fp/fast-satisfies? IOther (java.util.LinkedList.)))))

(deftest nil-follows-satisfies
  (is (false? (fp/fast-satisfies? IProbe nil)))
  (extend-type nil IProbe (-probe [_] :nil))
  (is (true? (fp/fast-satisfies? IProbe nil))))
