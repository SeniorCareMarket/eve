(ns eve.collection-hash-test
  "An eve collection hashes as the host's value of the same contents (Clojure's Murmur3: the ordered hash of a vector or
   a list, the unordered hash of a set or a map), on every host, nested ones too — maps excepted: an eve map hashes by
   eve's own hash, not the host map's (see the ruling note at the test below). Before 2026-10-19 eve.platform hashed
   an eve value through clojure.core's hash-ordered-coll / hash-unordered-coll, which need an Iterable: on babashka
   every eve type but the map threw (sci.impl.deftype.SciType cannot be cast to java.lang.Iterable), and on ValueScript
   the hash was of VS's own representation of the type. eve.platform now hashes through eve.core. And a map holding a
   map hashed wrong on ValueScript (2026-10-18 ET, EM4's in-program differential): an entry is a host [k v], which
   eve.core's protocols hashed with the host's hash, blind to the eve value in it; they now hash a host collection's
   elements through -hash."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.core :as e]
            [eve.hamt-util :as hu]
            [eve.encoding :as enc]
            [eve.map :as m]
            [eve.set :as s]
            [eve.vec :as v]
            [eve.list :as l]))

;; Ruling (John, 2026-11-05, the hash facade): eve's hash is authoritative inside
;; eve. A host value is re-homed on entry, never wrapped, so an eve map's hash is
;; NOT expected to equal the host map's hash of the same contents — on the JVM
;; the two happen to coincide, on jank they do not, and the host side of that
;; comparison was the erroneous expectation. The map rows below therefore assert
;; the eve-side contract instead: equal eve maps hash equally, whichever eve
;; path built them (constructor, into, re-homed host values), with equiv and
;; hash consistent. The vector/list/set rows keep the host comparison: their
;; hashes are the host's by construction on every host.
(deftest an-eve-collection-hashes-as-the-host-value
  (testing "a vector, a list, a set, a map"
    (is (= (hash [1 2 3]) (hash (v/eve-vec [1 2 3]))))
    (let [el (l/eve-list [1 2 3])]
      (is (= (hash (apply list (e/seq el))) (hash el))))
    (is (= (hash #{1 2 3}) (hash (s/hash-set 1 2 3))))
    (let [a (m/hash-map :a 1 :b 2)
          b (m/into-hash-map [[:a 1] [:b 2]])]
      (is (= a b))
      (is (= (hash a) (hash b)))))
  (testing "empty"
    (is (= (hash []) (hash (v/empty-vec))))
    (is (= (hash #{}) (hash (s/empty-hash-set))))
    (let [a (m/empty-hash-map)
          b (m/into-hash-map [])]
      (is (= a b))
      (is (= (hash a) (hash b)))))
  (testing "nested: the element's own hash"
    (is (= (hash [[1 2] 3]) (hash (v/eve-vec [(v/eve-vec [1 2]) 3]))))
    (is (= (hash #{[:k 1]}) (hash (s/hash-set (v/eve-vec [:k 1])))))
    (let [a (m/hash-map :k1 (m/hash-map :i 10 :s "v10"))
          b (m/into-hash-map [[:k1 {:i 10 :s "v10"}]])]
      (is (= a b) "a map holding a map")
      (is (= (hash a) (hash b)) "a map holding a map"))
    (let [a (m/hash-map :k (v/eve-vec [1 2]) :s (s/hash-set 3))
          b (m/hash-map :k [1 2] :s #{3})]
      (is (= a b) "a map holding a vector and a set")
      (is (= (hash a) (hash b)) "a map holding a vector and a set"))))

(deftest a-map-built-by-its-constructors-holds-its-entries
  (testing "eve.map/hash-map and into-hash-map build through eve.core's assoc"
    (is (= 2 (e/count (m/hash-map :x 1 :y 2))))
    (is (= 1 (e/get (m/hash-map :x 1 :y 2) :x)))
    (is (= 3 (e/count (m/into-hash-map [[:a 1] [:b 2] [:c 3]]))))))

;; This namespace is registered in JVM, bb, nbb and basilisp runners.
(deftest portable-hash-known-answers
  ;; Independently published MurmurHash3_x86_32 seed-zero vectors, not host hash.
  (doseq [[text expected] [["" 0] ["foo" -156908512] ["hello" 613153351]]]
    (is (= expected (hu/portable-hash-bytes (enc/utf8-bytes text))))))

(deftest bulk-into-matches-persistent-writes
  (let [xs (mapv #(vector (str "N" %)) (range 1000))
        xs (into xs (take 20 xs))
        a (e/into (s/empty-hash-set) xs)
        b (reduce e/conj (s/empty-hash-set) xs)
        pairs (mapv vector xs (range (count xs)))
        c (e/into (m/empty-hash-map) pairs)
        d (reduce (fn [out [k v]] (e/assoc out k v)) (m/empty-hash-map) pairs)]
    (is (= 1000 (e/count a) (e/count c)))
    (is (= a b))
    (is (= (hash a) (hash b)))
    (is (= c d))
    (doseq [i [0 19 20 500 999]]
      (is (e/contains? a [(str "N" i)]))
      (is (= (if (< i 20) (+ i 1000) i) (e/get c [(str "N" i)]))))))

(deftest bulk-transient-observation-and-fallback
  (let [t (e/transient (s/empty-hash-set))]
    (e/conj! t ["a"])
    (e/conj! t ["a"])
    (is (= 1 (e/count t)))
    (e/conj! t ["b"])
    #?(:cljs (disj! t ["a"]) :default (e/-disjoin! t ["a"]))
    (is (= #{["b"]} (set (e/seq (e/persistent! t))))))
  (let [t (e/transient (m/empty-hash-map))]
    (e/assoc! t ["a"] 1)
    (e/assoc! t ["a"] 2)
    (is (= 2 (e/get t ["a"])))
    (e/assoc! t ["b"] 3)
    #?(:cljs (dissoc! t ["a"]) :default (e/-dissoc! t ["a"]))
    (is (= 1 (e/count t)))
    (is (= 3 (e/get (e/persistent! t) ["b"]))))
  (let [a (e/conj (s/empty-hash-set) ["old"])
        b (e/into a [["new"]])]
    (is (= 1 (e/count a)))
    (is (= 2 (e/count b))))
  (is (= 0 (e/count (e/into (s/empty-hash-set) []))))
  (is (= 0 (e/count (e/into (m/empty-hash-map) [])))))

(deftest bulk-last-level-natural-collisions
  (let [xs [3870166 8239693 3870166 4140288]
        a (e/into (s/empty-hash-set) xs)
        b (e/into (m/empty-hash-map) (mapv #(vector % %) xs))]
    (doseq [x xs]
      (is (e/contains? a x))
      (is (= x (e/get b x))))
    (is (= 3 (e/count a) (e/count b)))
    (is (= 4 (e/count (e/conj a :extra))))
    (is (= 4 (e/count (e/assoc b :extra 1))))
    (is (= 2 (e/count (e/disj a 3870166))))
    (is (= 2 (e/count (e/dissoc b 3870166))))))

(deftest bulk-large-map-payload-size-is-not-truncated
  (let [value (apply str (repeat 70000 "x"))
        a (e/into (m/empty-hash-map) [["large" value] ["small" "ok"]])]
    (is (= 2 (e/count a)))
    (is (= value (e/get a "large")))
    (is (= "ok" (e/get a "small")))
    (is (= value (e/get (e/assoc a "other" 1) "large")))))
