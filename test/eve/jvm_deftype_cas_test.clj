(ns eve.jvm-deftype-cas-test
  "eve/deftype's cas! on the JVM (seam map L-34): a ^:volatile-mutable ^:int32
   field compare-and-swapped through ISlabIO (-sio-cas-i32!). The macro emits
   (== expected witness), so cas! answers true iff the field held `expected`
   and now holds `new`. Nothing exercised this on the JVM before."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.alpha :as eve]
            [eve.deftype-proto.alloc :as alloc]))

(defprotocol ICasCounter
  (cas-value! [this expected new])
  (value-of [this]))

(eve/deftype CasCounter [^:volatile-mutable ^:int32 value]
  ICasCounter
  (cas-value! [this expected new] (cas! value expected new))
  (value-of [this] value))

(deftest jvm-deftype-cas!
  (let [sio (alloc/default-sio)
        c   (->CasCounter sio 100)]
    (testing "a cas! whose expected value does not match fails and leaves the field"
      (is (false? (cas-value! c 999 5)))
      (is (= 100 (value-of c))))
    (testing "a cas! whose expected value matches succeeds and installs the new value"
      (is (true? (cas-value! c 100 5)))
      (is (= 5 (value-of c))))
    (testing "the witness is the field, not a cached copy: a second identical cas! fails"
      (is (false? (cas-value! c 100 7)))
      (is (= 5 (value-of c))))
    (testing "contended cas! from several threads: exactly one winner per round"
      (let [rounds 200
            wins   (atom 0)]
        (dotimes [r rounds]
          (let [cur (value-of c)
                ts  (doall (for [_ (range 4)]
                             (future (cas-value! c cur (inc cur)))))]
            (swap! wins + (count (filter true? (map deref ts))))))
        (is (= rounds @wins) "one of the four threads wins each round")
        (is (= (+ 5 rounds) (value-of c)))))))
