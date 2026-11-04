(ns eve.map-last-level-test
  "Keys whose hashes meet at the HAMT's last level (shift 30, bits 30-31 —
   FORMAT-DECISIONS.md §6). A collision node holds keys of one hash; a key of
   another hash that reaches it splits it, at shift 30 as at any shift above,
   because bits 30-31 still tell the two apart (DIVERGENCE-CENSUS §8, D1).

   3870166 and 8239693 have the same hash; 4140288's differs from it only in
   bit 30. The first two make a collision node under the root, the third walks
   the five levels below it to shift 30."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.map :as em]
            [eve.core :as e]
            [eve.codec :as codec]
            [eve.hamt-util :as hu]
            [eve.deftype-proto.alloc :as alloc]))

(def ^:private a 3870166)
(def ^:private b 8239693)
(def ^:private c 4140288)

(defn- key-hash [k] (hu/portable-hash-bytes (codec/encode-key k)))

(defn- abc-map []
  (-> (em/hash-map (alloc/heap-domain-sio! {}))
      (e/assoc a :a) (e/assoc b :b) (e/assoc c :c)))

(deftest the-keys-meet-at-the-last-level
  (testing "the reproduction's premise, on this host"
    (is (= (key-hash a) (key-hash b)) "a and b collide")
    (is (not= (key-hash a) (key-hash c)))
    (is (= (bit-and (key-hash a) 0x3fffffff) (bit-and (key-hash c) 0x3fffffff))
        "c agrees with a on bits 0-29")))

(deftest a-key-that-reaches-a-collision-node-at-the-last-level-is-kept
  (let [m (abc-map)]
    (is (= 3 (e/count m)))
    (is (= :a (e/get m a)))
    (is (= :b (e/get m b)))
    (is (= :c (e/get m c)) "the third key is found")
    (testing "re-assoc replaces, it does not add"
      (let [m2 (e/assoc m c :c2)]
        (is (= 3 (e/count m2)))
        (is (= :c2 (e/get m2 c)))
        (is (= :a (e/get m2 a)))))
    (testing "dissoc removes it"
      (let [m3 (e/dissoc m c)]
        (is (= 2 (e/count m3)))
        (is (nil? (e/get m3 c)))
        (is (= :b (e/get m3 b)))))
    (testing "each of the collided pair, removed, leaves the other two"
      (let [ma (e/dissoc m a)]
        (is (= 2 (e/count ma)))
        (is (= [nil :b :c] [(e/get ma a) (e/get ma b) (e/get ma c)])))
      (let [mb (e/dissoc m b)]
        (is (= 2 (e/count mb)))
        (is (= [:a nil :c] [(e/get mb a) (e/get mb b) (e/get mb c)]))))
    (testing "the keys in another order"
      (doseq [ks [[c a b] [a c b] [b c a]]]
        (let [mk (reduce (fn [acc k] (e/assoc acc k k)) (em/hash-map (alloc/heap-domain-sio! {})) ks)]
          (is (= 3 (e/count mk)) (str "order " ks))
          (is (= ks (mapv #(e/get mk %) ks)) (str "order " ks)))))))
