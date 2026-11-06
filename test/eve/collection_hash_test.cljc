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
