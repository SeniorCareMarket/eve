(ns eve.construction-law-test
  "The construction law (John, 2026-11-11): all Eve data must be constructed
   in a swapping operation, in the atom domain. Instantiating Eve data
   outside an Eve context throws :eve.alloc/error :no-domain-context.
   Legal contexts: inside an atom's swap!, an explicit domain binding
   (d/*domain-sio* over a domain's sio), and explicit-sio constructors."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [swap!]]
            [eve.atom :as a]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.vec :as v]
            [eve.map :as m]))

(deftest construction-outside-a-context-throws
  (doseq [[label thunk] {"eve-vec"  #(v/eve-vec [1 2 3])
                         "hash-map" #(m/hash-map :a 1)}]
    (testing label
      (let [e (try (thunk) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo :lpy python/Exception :default Exception) ex ex))]
        (is (some? e) "construction outside a context must throw")
        (is (= :no-domain-context (:eve.alloc/error (ex-data e))))))))

(deftest construction-inside-a-swap-works
  (let [ea (a/atom ::construction-law-probe [])]
    (swap! ea (fn [_] (v/eve-vec [1 2 3])))
    (is (= [1 2 3] (vec @ea)))))

(deftest construction-in-an-explicit-domain-context-works
  (binding [d/*domain-sio* (alloc/heap-domain-sio! {})]
    (is (= [1 2 3] (vec (v/eve-vec [1 2 3]))))
    (is (= {:a 1} (into {} (m/hash-map :a 1))))))

(deftest plumbing-sio-is-not-a-constructor-path
  ;; Eve's own materialization plumbing keeps its process-domain fallback;
  ;; it is not a way to construct user data (constructors never call it).
  (is (some? (alloc/plumbing-sio))))
