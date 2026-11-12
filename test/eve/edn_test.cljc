(ns eve.edn-test
  "eve.edn: the one EDN reader data code names. Registered Eve types,
   bare Clojure number tokens and caller readers all read through it,
   on every host, with Clojure's semantics."
  (:refer-clojure :exclude [deftype])
  (:require #?(:cljs [cljs.test :refer-macros [deftest is testing]]
               :default [clojure.test :refer [deftest is testing]])
            [eve.deftype #?@(:cljs [:refer-macros [deftype]] :default [:refer [deftype]])]
            [eve.edn :as edn]
            [eve.numeric :as n]))

(deftype EdnPoint [x y])

(deftest plain-edn-reads-as-clojure
  (is (= {:a [1 2 3] :b #{:x} :c "s" :d nil :e true}
         (edn/read-string "{:a [1 2 3] :b #{:x} :c \"s\" :d nil :e true}")))
  (is (= '(1 2) (edn/read-string "(1 2)")))
  (is (= :kw (edn/read-string ":kw"))))

(deftest registered-type-tags-read
  (testing "a deftype's auto-registered tag, payload a field map"
    (let [v (edn/read-string "#eve.edn-test/EdnPoint {:x 1, :y \"two\"}")]
      ;; a group-less plain deftype has identity equality; the round-trip
      ;; claim is type + fields + print (type-io-test's own idiom).
      (is (instance? EdnPoint v))
      (is (= 1 (.-x v))) (is (= "two" (.-y v)))
      (is (= (pr-str (->EdnPoint 1 "two")) (pr-str v)))))
  (testing "eve.numeric's registered tags"
    (is (n/equiv (edn/read-string "#eve/N \"9007199254740993\"") (n/bigint "9007199254740993")))
    (is (n/equiv (edn/read-string "#eve/ratio \"1/3\"") (n/ratio 1 3)))
    (is (= 4 (n/scale (edn/read-string "#eve/M \"1.2300\""))))))

(deftest bare-number-tokens-read-exactly
  (testing "the eve.db.ds serialize case: exact tokens inside a form"
    (let [v (edn/read-string "{:x 1/3 :y 123N :z 1.2300M}")]
      (is (n/equiv (:x v) (n/ratio 1 3)))
      (is (n/equiv (:y v) (n/bigint "123")))
      (is (= 4 (n/scale (:z v))))))
  (testing "a lone token"
    (is (n/equiv (edn/read-string "9223372036854775808") (n/bigint "9223372036854775808")))
    (is (n/equiv (edn/read-string "1/3") (n/ratio 1 3))))
  (testing "number-like text inside strings is not rewritten"
    (is (= {:s "1/3 and 123N"} (edn/read-string "{:s \"1/3 and 123N\"}")))))

(deftest caller-readers-win-and-extend
  (is (= {:x 42} (edn/read-string {:readers {'example/custom identity}}
                                  "#example/custom {:x 42}")))
  (is (= 7 (edn/read-string {:readers {'example/seven (fn [_] 7)}}
                            "#example/seven 0"))))
