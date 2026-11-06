(ns eve.equiv-self-test
  "An eve collection equals itself — and an independently built equal one —
   on every seat. Regression for the jank seat's blind gate (2026-11-06,
   the 0.9.3 release blocker): EveVector's equiv opens with
   (sequential? other), clojure.core's — a host class test an eve instance
   (an opaque_box to jank) could never pass, so (= v v) was false on jank
   while (= v [1 2 3]) was true. The seat now routes sequential? for an eve
   type through its ISequential marker (adapters/completion/jank +
   adapters/types/jank), as it already routed count/conj/assoc."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.vec :as v]
            [eve.map :as m]
            [eve.set :as s]
            [eve.list :as l]))

(deftest eve-collections-equal-themselves
  (let [sio (alloc/heap-domain-sio! {})
        sio2 (alloc/heap-domain-sio! {})
        ev (v/eve-vec sio [1 2 3])
        ev2 (v/eve-vec sio2 [1 2 3])
        em (m/into-hash-map sio [[:a 1] [:b 2]])
        em2 (m/into-hash-map sio2 [[:a 1] [:b 2]])
        es (s/hash-set sio 1 2 3)
        es2 (s/hash-set sio2 1 2 3)
        el (l/eve-list sio [1 2 3])]
    (testing "vector: self, independent, host — and not an unequal vector"
      (is (sequential? ev))
      (is (= ev ev))
      (is (= ev ev2))
      (is (= ev [1 2 3]))
      (is (= [1 2 3] ev))
      (is (not= ev [1 2 4]))
      (is (not= ev [1 2]))
      (is (= (hash ev) (hash [1 2 3]))))
    (testing "map and set: rows unmoved"
      (is (= em em)) (is (= em em2)) (is (= (hash em) (hash em2)))
      (is (= es es)) (is (= es es2)) (is (= (hash es) (hash #{1 2 3}))))
    (testing "list: self (its equiv walks the same gate)"
      (is (= el el)))
    (testing "by-reference content: the digest verdict abstains, the fallback's gate decides"
      ;; NOTE: the set twin of this row ((= (s/hash-set sio f) (s/hash-set sio2 f))) is the
      ;; named sibling in the fix lane's STATUS: set-equiv?'s fallback gates on (set? other)
      ;; identically, but re-rooting clojure.core/set? breaks jank's own core codegen, so the
      ;; seat cannot answer it the same way. It is not asserted here.
      (let [f (fn [] 1)
            mb1 (m/into-hash-map sio [[:f f]])
            mb2 (m/into-hash-map sio2 [[:f f]])]
        (is (map? em))
        (is (= mb1 mb2))))))
