(ns eve.guard-test
  "PROTOTYPE (jvm-heap-grade step 2): the automatic read guard's contract."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.guard :as g]
            [eve.mem :as mem]
            [eve.deftype-proto.data :as d]))

(defn- slot-ptr [a]
  (mem/-load-i32 (:root-r (ea/-domain-state a)) (d/atom-slot-offset (ea/-atom-slot-idx a) d/ATOM_SLOT_PTR_OFFSET)))

(def src (into {} (map (fn [i] [(keyword (str "k" i)) {:id i :name (str "n" i) :tags [i (inc i)]}]) (range 50))))

(deftest guard-reads-in-place
  (let [a (e/atom src)]
    (testing "deref inside the extent answers the lazy root at the atom's own pointer"
      (is (g/with-guard (instance? eve.guard.LazyMap @a)))
      (is (= (slot-ptr a) (g/with-guard (d/-root-header-off @a))))
      (is (g/with-guard (identical? (d/-eve-sio @a) (ea/atom-sio a)))))
    (testing "scalar and subtree access"
      (is (= "n3" (g/with-guard (get-in @a [:k3 :name]))))
      (is (= 4 (g/with-guard (nth (:tags (get @a :k3)) 1))))
      (is (= 50 (g/with-guard (count @a))))
      (is (g/with-guard (= (get @a :k3) {:id 3 :name "n3" :tags [3 4]}))))
    (testing "materialize is the explicit materialization point"
      (is (= (get src :k3) (g/with-guard (g/materialize (get @a :k3))))))
    (testing "outside any extent, deref is unchanged (plain data)"
      (is (= src @a)))))

(deftest guard-validity
  (let [a (e/atom src)]
    (testing "use after the extent closes throws"
      (let [x (g/with-guard (get @a :k3))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"after its guard extent closed" (get x :id))))
      (let [r (g/with-guard @a)]
        (is (thrown? clojure.lang.ExceptionInfo (count r)))))
    (testing "a nested extent's values die with it; the outer's live on"
      (is (= [5 :closed]
             (g/with-guard
               (let [x (get @a :k5) y (g/with-guard (get @a :k6))]
                 [(:id x) (try (:id y) (catch clojure.lang.ExceptionInfo _ :closed))])))))
    (testing "read-only"
      (is (thrown? UnsupportedOperationException (g/with-guard (assoc @a :x 1)))))
    (testing "pins released at close"
      (g/with-guard (get @a :k1))
      (is (empty? @(:pins (ea/-domain-state a)))))))

(deftest same-domain-and-foreign-stores
  (let [a (e/atom src) b (e/atom {}) c (e/atom ::guard-test-other {})]
    (testing "a same-domain store through the guard keeps the source pointer"
      (let [p (e/$ (d/-root-header-off (get @a :k7)))]
        (g/with-guard (swap! b (fn [_] (get @a :k7))))
        (is (= p (slot-ptr b)))
        (is (= (get src :k7) @b))))
    (testing "a foreign store throws by default and copies under :copy"
      (is (thrown? clojure.lang.ExceptionInfo (g/with-guard (swap! c (fn [_] (get @a :k7))))))
      (ea/set-foreign-policy! c :copy)
      (g/with-guard (swap! c (fn [_] (get @a :k7))))
      (is (= (get src :k7) @c)))))
