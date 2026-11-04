(ns eve.deftype.type-id-test
  "An eve-deftype's type-id byte and name hash come from its name, so every
   process that shares eve memory derives the same ones, whatever it loaded
   and in whatever order (eve.deftype.registry). This file asserts them as
   literals and runs on every host. Apple and Peach share a type-id under
   eve's hash; the name hash in each block tells them apart when they are
   read back."
  ;; swap! on an eve atom: eve.host's (clojerl's own reaches its own atoms only)
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.deftype #?@(:cljs [:refer-macros [eve-deftype]] :default [:refer [eve-deftype]])]
   #?(:cljs [cljs.test :refer-macros [deftest testing is]]
      :default [clojure.test :refer [deftest testing is]])
   [eve.atom :as atom]
   [eve.deftype.array :as arr]))

(defprotocol IFruit
  (fruit [this]))

(eve-deftype Apple [^:int32 n label]
             IFruit
             (fruit [_] [:apple n label]))

(eve-deftype Peach [^:int32 n label]
             IFruit
             (fruit [_] [:peach n label]))

(deftest the-same-ids-on-every-host
  (testing "the type-id byte: 0x40 + (eve's hash of \"eve.deftype.type-id-test/Apple\") mod 173"
    (is (= 0x4E Apple-type-id))
    (is (= 0x4E Peach-type-id))))

(deftest types-that-share-a-type-id
  (testing "read back through an eve array"
    (let [a (arr/make-array 4)]
      (arr/aset a 0 (->Apple 1 "one"))
      (arr/aset a 1 (->Peach 2 "two"))
      (arr/aset a 2 (->Apple 3 "three"))
      (is (= [[:apple 1 "one"] [:peach 2 "two"] [:apple 3 "three"]]
             (mapv #(fruit (arr/aget a %)) [0 1 2])))))
  (testing "read back from an atom"
    (let [a (atom/atom {:id ::types-that-share-a-type-id} nil)]
      (swap! a (fn [_] {:apple (->Apple 4 "four") :peach (->Peach 5 "five")}))
      (is (= [:apple 4 "four"] (fruit (:apple @a))))
      (is (= [:peach 5 "five"] (fruit (:peach @a))))
      (swap! a (fn [m] (assoc m :apple (:peach m) :peach (:apple m))))
      (is (= [:peach 5 "five"] (fruit (:apple @a))))
      (is (= [:apple 4 "four"] (fruit (:peach @a)))))))
