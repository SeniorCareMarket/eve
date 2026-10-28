(ns eve.deftype.type-id-test
  "An eve-deftype's type-id byte and name hash come from its name, so every
   process that shares eve memory derives the same ones, whatever it loaded
   and in whatever order (eve.deftype.registry). This file asserts them as
   literals and runs on every host. Grape and Apricot share a type-id; the
   name hash in each block tells them apart when they are read back."
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

(eve-deftype Grape [^:int32 n label]
             IFruit
             (fruit [_] [:grape n label]))

(eve-deftype Apricot [^:int32 n label]
             IFruit
             (fruit [_] [:apricot n label]))

(deftest the-same-ids-on-every-host
  (testing "the type-id byte: 0x40 + (hash \"eve.deftype.type-id-test/Grape\") mod 173"
    (is (= 0x50 Grape-type-id))
    (is (= 0x50 Apricot-type-id))))

(deftest types-that-share-a-type-id
  (testing "read back through an eve array"
    (let [a (arr/make-array 4)]
      (arr/aset a 0 (->Grape 1 "one"))
      (arr/aset a 1 (->Apricot 2 "two"))
      (arr/aset a 2 (->Grape 3 "three"))
      (is (= [[:grape 1 "one"] [:apricot 2 "two"] [:grape 3 "three"]]
             (mapv #(fruit (arr/aget a %)) [0 1 2])))))
  (testing "read back from an atom"
    (let [a (atom/atom {:id ::types-that-share-a-type-id} nil)]
      (swap! a (fn [_] {:g (->Grape 4 "four") :a (->Apricot 5 "five")}))
      (is (= [:grape 4 "four"] (fruit (:g @a))))
      (is (= [:apricot 5 "five"] (fruit (:a @a))))
      (swap! a (fn [m] (assoc m :g (:a m) :a (:g m))))
      (is (= [:apricot 5 "five"] (fruit (:g @a))))
      (is (= [:grape 4 "four"] (fruit (:a @a)))))))
