(ns eve.aegis.merkle-test
  "eve.aegis.merkle: the field encoding and the record leaf, and the sidecar's leaf and membership / non-membership /
   assoc / dissoc openings (the depth-20 frontier left with ExtCID v3: an append is read from the atom's two states,
   eve.aegis.claims). Every fixture is SYNTHETIC (made up here; no production data)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.aegis.merkle :as m]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

(def ^:private zero32 (vec (repeat 32 0)))

(defn- flip "a copy of a byte vector with its first byte changed" [bs] (update (vec bs) 0 (fn [x] (mod (inc x) 256))))

;; ---------------------------------------------------------------------------------------------------------------
;; fields

(deftest field-of-and-chain
  (testing "scalars"
    (is (= zero32 (m/field-of nil)))
    (is (= zero32 (m/field-of false)))
    (is (= (conj (vec (repeat 31 0)) 1) (m/field-of true)))
    (is (= (conj (vec (repeat 31 0)) 1) (m/field-of 1)))
    (is (= (into (vec (repeat 29 0)) [1 2 3]) (m/field-of 66051)))
    (is (= (crypto/sha256 (b/utf8 "abc")) (m/field-of "abc")))
    (is (= (m/field-of "abc") (m/field-of :abc)))
    (is (= 32 (count (m/field-of "é☃")))))
  (testing "chain"
    (is (= zero32 (m/chain [])))
    (let [f (m/field-of "x")]
      (is (= (crypto/sha256 (into zero32 f)) (m/chain [f])))
      (is (= (crypto/sha256 (into (crypto/sha256 (into zero32 f)) f)) (m/chain [f f])))))
  (testing "a vector is the chain over its elements' fields, order-bound"
    (is (= (m/chain [(m/field-of "a") (m/field-of "b")]) (m/field-of ["a" "b"])))
    (is (not= (m/field-of ["a" "b"]) (m/field-of ["b" "a"])))
    (is (= zero32 (m/field-of []))))
  (testing "no encoding"
    (is (thrown? #?(:cljs js/Error :default Exception) (m/field-of -1)))
    (is (thrown? #?(:cljs js/Error :default Exception) (m/field-of 1.5)))
    (is (thrown? #?(:cljs js/Error :default Exception) (m/field-of {:a 1})))
    (is (thrown? #?(:cljs js/Error :default Exception) (m/field-of [[1]])))))

(deftest record-leaf-test
  (let [ks [:id :author :ts :attachment-cid]
        rec {:id "m1" :author "alice" :ts 5}]
    (testing "an absent optional key contributes nil, keyword- and string-keyed alike"
      (is (= (m/chain [(m/field-of "m1") (m/field-of "alice") (m/field-of 5) zero32]) (m/record-leaf ks rec)))
      (is (= (m/record-leaf ks rec) (m/record-leaf ks {"id" "m1" "author" "alice" "ts" 5})))
      (is (= (m/record-leaf ks rec) (m/record-leaf ks (assoc rec :attachment-cid nil)))))
    (testing "a present value, and every key, are bound"
      (is (not= (m/record-leaf ks rec) (m/record-leaf ks (assoc rec :attachment-cid "cid1"))))
      (is (not= (m/record-leaf ks rec) (m/record-leaf ks (assoc rec :ts 6))))
      (is (not= (m/record-leaf ks rec) (m/record-leaf ks (assoc rec :author "bob")))))
    (testing "record-get"
      (is (= "alice" (m/record-get rec :author)))
      (is (= "alice" (m/record-get {"author" "alice"} :author)))
      (is (nil? (m/record-get rec :nope)))
      (is (false? (m/record-get {:flag false} :flag))))))

;; ---------------------------------------------------------------------------------------------------------------
;; sidecar

(def ^:private seven
  "SYNTHETIC 7-key map: string and keyword keys, scalar and vector values."
  {"apple" "red" "banana" 12 :cherry true "date" nil :elder ["a" "b"] "fig" :green "grape" 0})

(def ^:private eight (assoc seven "honey" 7))

(deftest sidecar-membership
  (let [root (m/sidecar-root seven)]
    (is (= 32 (count root)))
    (doseq [[k v] seven
            :let [o (m/membership-opening seven k)]]
      (is (some? o))
      (is (= root (:root o)))
      (is (= (m/field-of v) (:value-field o)))
      (is (m/verify-membership root k (:value-field o) (:index o) (:siblings o)) (str k))
      (is (m/verify-membership root (b/utf8 (name k)) (:value-field o) (:index o) (:siblings o)))
      (testing "the opening's leaf is sidecar-leaf: SHA-256(0x00 ‖ u32-be(len kb) ‖ kb ‖ value-field)"
        (let [kb (b/utf8 (name k)) n (count kb)]
          (is (= (m/sidecar-leaf k (m/field-of v))
                 (crypto/sha256 (into (into [0 0 0 (quot n 256) (mod n 256)] kb) (m/field-of v)))))))
      (testing "a wrong value-field fails"
        (is (not (m/verify-membership root k (m/field-of "other") (:index o) (:siblings o)))))
      (testing "a wrong index or sibling fails"
        (is (not (m/verify-membership root k (:value-field o) (mod (inc (:index o)) 8) (:siblings o))))
        (is (not (m/verify-membership root k (:value-field o) (:index o) (update (vec (:siblings o)) 0 flip)))))
      (testing "another key fails"
        (is (not (m/verify-membership root "zzz" (:value-field o) (:index o) (:siblings o))))))
    (is (nil? (m/membership-opening seven "absent")))
    (testing "sorted by key bytes, whichever type the key"
      (is (= [0 1 2 3 4 5 6] (mapv (fn [k] (:index (m/membership-opening seven k)))
                                   ["apple" "banana" "cherry" "date" "elder" "fig" "grape"]))))
    (testing "the root binds every value and key"
      (is (not= root (m/sidecar-root (assoc seven "fig" :blue))))
      (is (not= root (m/sidecar-root (dissoc seven "fig"))))
      (is (= root (m/sidecar-root (assoc seven "apple" "red")))))
    (testing "two keys with the same bytes are refused"
      (is (thrown? #?(:cljs js/Error :default Exception)
                   (m/sidecar-root {"a" 1 :a 2}))))))

(deftest sidecar-empty-and-tiny
  (let [e (m/sidecar-root {})
        one (m/sidecar-root {"k" 1})]
    (is (= 32 (count e)))
    (is (not= e one))
    (is (= e zero32) "one empty leaf: the padded tree's root is the empty leaf itself")
    (is (= e (m/sidecar-root nil)))
    (let [o (m/non-membership-opening {} "x")]
      (is (m/verify-non-membership e "x" o))
      (is (nil? (:lower o)))
      (is (:empty (:upper o))))
    (let [o (m/non-membership-opening {"k" 1} "z")]
      (is (nil? (:upper o)) "one entry fills a one-leaf tree: nothing beyond it, no padded leaf")
      (is (m/verify-non-membership one "z" o))
      (is (not (m/verify-non-membership e "z" o))))))

(deftest sidecar-non-membership
  (doseq [[label mp] [["seven (one padded leaf)" seven] ["eight (a full tree)" eight]]]
    (testing label
      (let [root (m/sidecar-root mp)]
        (doseq [[what k] [["below the smallest" "aaa"] ["between banana and cherry" "blueberry"]
                          ["between apple and banana (keyword)" :b] ["between grape and the end" "zzz"]
                          ["a prefix of a key" "app"] ["extends a key" "applesauce"]]]
          (let [o (m/non-membership-opening mp k)]
            (is (some? o) what)
            (is (m/verify-non-membership root k o) what)
            (testing (str what ": another key's opening, or another root, fails")
              (is (not (m/verify-non-membership root "fig" o)))
              (is (not (m/verify-non-membership (m/sidecar-root seven) "fig" o)))
              (is (not (m/verify-non-membership (flip root) k o))))))
        (testing "above the largest"
          (let [o (m/non-membership-opening mp "zzz")]
            (is (= (count mp) (if (:upper o) (:index (:upper o)) (inc (:index (:lower o)))))))
          (is (= (nil? (:upper (m/non-membership-opening mp "zzz"))) (= 8 (count mp)))))
        (testing "a present key has no opening, and borrowed openings fail"
          (is (nil? (m/non-membership-opening mp "banana")))
          (is (not (m/verify-non-membership root "banana" (m/non-membership-opening mp "blueberry"))))
          (is (not (m/verify-non-membership root "cherry" (m/non-membership-opening mp "blueberry")))))
        (testing "neighbours that are not consecutive fail"
          (let [mo (fn [k] (select-keys (m/membership-opening mp k) [:key :value-field :index :siblings]))
                gap {:key (b/utf8 "banana") :root root :lower (mo "apple") :upper (mo "cherry")}]
            (is (not (m/verify-non-membership root "banana" gap)))
            (is (not (m/verify-non-membership root "b" gap)) "apple < b < cherry, but banana lies between")
            (is (m/verify-non-membership root "b" (assoc gap :upper (mo "banana"))))))
        (testing "neighbours out of order, an empty lower, no neighbour at all"
          (let [o (m/non-membership-opening mp "blueberry")]
            (is (not (m/verify-non-membership root "blueberry" (assoc o :lower (:upper o) :upper (:lower o)))))
            (is (not (m/verify-non-membership root "blueberry" (assoc o :lower {:empty true :index 1 :siblings (:siblings (:lower o))}))))
            (is (not (m/verify-non-membership root "blueberry" (assoc o :lower nil :upper nil))))
            (testing "a missing lower claims the key is below everything"
              (is (not (m/verify-non-membership root "blueberry" (assoc o :lower nil)))))))
        (testing "an upper dropped where entries lie beyond"
          (let [o (m/non-membership-opening mp "aaa")]
            (is (not (m/verify-non-membership root "aaa" (assoc o :upper nil))))))))))

(deftest sidecar-key-assoc
  (let [old seven
        new (assoc seven "cat" 7)
        w (m/key-assoc-witness old new "cat")
        vf (m/field-of 7)
        ro (m/sidecar-root old) rn (m/sidecar-root new)]
    (is (some? w))
    (is (= ro (:old-root w)))
    (is (= rn (:new-root w)))
    (is (m/verify-key-assoc ro rn "cat" vf w))
    (testing "assoc onto the end and the front"
      (doseq [k ["zzz" "aaa"]]
        (is (m/verify-key-assoc ro (m/sidecar-root (assoc old k 1)) k (m/field-of 1)
                                (m/key-assoc-witness old (assoc old k 1) k)))))
    (testing "a wrong value fails"
      (is (not (m/verify-key-assoc ro rn "cat" (m/field-of 8) w)))
      (let [new8 (assoc seven "cat" 8)]
        (is (not (m/verify-key-assoc ro (m/sidecar-root new8) "cat" vf (m/key-assoc-witness old new8 "cat"))))))
    (testing "an existing key fails"
      (is (nil? (m/key-assoc-witness old (assoc old "banana" 1) "banana")))
      (let [new2 (assoc old "banana" 99)
            forged (assoc w :membership (m/membership-opening new2 "banana"))]
        (is (not (m/verify-key-assoc ro (m/sidecar-root new2) "banana" (m/field-of 99) forged)))
        (is (not (m/verify-key-assoc ro (m/sidecar-root new2) "banana" (m/field-of 99)
                                     (assoc forged :new-root (m/sidecar-root new2)))))))
    (testing "a witness for another key, or other roots, fails"
      (is (not (m/verify-key-assoc ro rn "banana" vf w)))
      (is (not (m/verify-key-assoc rn ro "cat" vf w)))
      (is (not (m/verify-key-assoc ro rn "cat" vf (assoc w :new-root (flip rn))))))
    (testing "the membership at another index fails"
      (let [w2 (assoc-in w [:membership :index] (inc (:index (:membership w))))]
        (is (not (m/verify-key-assoc ro rn "cat" vf w2)))))))

(deftest sidecar-key-dissoc
  (let [old (assoc seven "cat" 7)
        new seven
        w (m/key-dissoc-witness old new "cat")
        ro (m/sidecar-root old) rn (m/sidecar-root new)]
    (is (some? w))
    (is (m/verify-key-dissoc ro rn "cat" w))
    (testing "dissoc of the first, the last, and down to empty"
      (doseq [k ["apple" "grape"]]
        (is (m/verify-key-dissoc ro (m/sidecar-root (dissoc old k)) k (m/key-dissoc-witness old (dissoc old k) k))))
      (is (m/verify-key-dissoc (m/sidecar-root {"k" 1}) (m/sidecar-root {}) "k"
                               (m/key-dissoc-witness {"k" 1} {} "k"))))
    (testing "a key still present, an absent key, the wrong key, wrong roots"
      (is (nil? (m/key-dissoc-witness old old "cat")))
      (is (nil? (m/key-dissoc-witness old new "nope")))
      (is (not (m/verify-key-dissoc ro rn "banana" w)))
      (is (not (m/verify-key-dissoc rn ro "cat" w)))
      (is (not (m/verify-key-dissoc ro (flip rn) "cat" w))))))
