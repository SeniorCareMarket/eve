(ns eve.deftype-test
  "Tests for eve/deftype macro: field reads, writes, protocols, Fressian."
  (:require-macros [eve.alpha :refer [extend-type]])
  (:require
   [cljs.test :refer [deftest testing is]]
   [eve.alpha :as eve]
   [eve.shared-atom :as atom]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.alloc :as alloc]
   ;; Required so collection serializers are registered for serialized field tests
   [eve.map]
   [eve.vec]
   [eve.set]))

;;-----------------------------------------------------------------------------
;; Define test types using eve/deftype
;;-----------------------------------------------------------------------------

(eve/deftype Counter [^:mutable ^:int32 count]
  ICounted
  (-count [this] count))

(eve/deftype Point [^:int32 x ^:int32 y]
  ILookup
  (-lookup [this k]
    (case k :x x :y y nil))
  (-lookup [this k not-found]
    (case k :x x :y y not-found)))

(eve/deftype LabeledPoint [^:int32 x ^:int32 y label]
  ILookup
  (-lookup [this k]
    (case k :x x :y y :label label nil))
  (-lookup [this k not-found]
    (case k :x x :y y :label label not-found)))

(eve/deftype AtomicCounter [^:volatile-mutable ^:int32 value]
  IDeref
  (-deref [this] value))

;;-----------------------------------------------------------------------------
;; Tests
;;-----------------------------------------------------------------------------

(deftest basic-counter-test
  (testing "Counter with mutable int32 field"
    (let [a (eve/atom ::basic-counter-test {})
          result (swap! a (fn [_]
                           (let [c (->Counter 0)]
                             (is (some? c) "Counter should be created")
                             (is (= 0 (count c)) "Initial count should be 0")
                             (is (satisfies? d/ISabpType c) "Should satisfy ISabpType")
                             :done)))]
      (is (= :done result)))))

(deftest counter-mutation-test
  (testing "Counter set! on mutable field"
    (let [a (eve/atom ::counter-mutation-test {})
          result (swap! a (fn [_]
                           (let [c (->Counter 42)]
                             (is (= 42 (count c)))
                             ;; Mutate via slab allocator
                             (let [base (alloc/resolve-dv! (.-offset__ c))]
                               (.setInt32 alloc/resolved-dv (+ base 4) 99 true))
                             (is (= 99 (count c)))
                             :done)))]
      (is (= :done result)))))

(deftest point-immutable-test
  (testing "Point with immutable int32 fields"
    (let [a (eve/atom ::point-immutable-test {})
          result (swap! a (fn [_]
                           (let [p (->Point 10 20)]
                             (is (= 10 (:x p)))
                             (is (= 20 (:y p)))
                             (is (nil? (:z p)))
                             (is (= :default (:z p :default)))
                             :done)))]
      (is (= :done result)))))

(deftest labeled-point-fressian-field-test
  (testing "LabeledPoint with int32 + fressian fields"
    (let [a (eve/atom ::labeled-point-fressian-field-test {})
          result (swap! a (fn [_]
                           (let [p (->LabeledPoint 5 10 "hello")]
                             (is (= 5 (:x p)))
                             (is (= 10 (:y p)))
                             (is (= "hello" (:label p)))
                             :done)))]
      (is (= :done result)))))

(deftest labeled-point-complex-fressian-test
  (testing "Fressian field with complex data"
    (let [a (eve/atom ::labeled-point-complex-fressian-test {})
          result (swap! a (fn [_]
                           (let [p (->LabeledPoint 1 2 {:nested [1 2 3] :key "val"})]
                             (is (= 1 (:x p)))
                             (is (= {:nested [1 2 3] :key "val"} (:label p)))
                             :done)))]
      (is (= :done result)))))

(deftest atomic-counter-volatile-test
  (testing "AtomicCounter with volatile-mutable int32"
    (let [a (eve/atom ::atomic-counter-volatile-test {})
          result (swap! a (fn [_]
                           (let [c (->AtomicCounter 100)]
                             (is (= 100 @c))
                             ;; Write via Atomics.store on slab
                             (let [base (alloc/resolve-dv! (.-offset__ c))
                                   sab (.-buffer alloc/resolved-dv)
                                   idx (unsigned-bit-shift-right (+ base 4) 2)]
                               (js/Atomics.store (js/Int32Array. sab) idx 200))
                             (is (= 200 @c))
                             :done)))]
      (is (= :done result)))))

(deftest cas-test
  (testing "CAS on volatile-mutable int32 field"
    (let [a (eve/atom ::cas-test {})
          result (swap! a (fn [_]
                           (let [c (->AtomicCounter 42)]
                             (is (= 42 @c))
                             (let [base (alloc/resolve-dv! (.-offset__ c))
                                   sab (.-buffer alloc/resolved-dv)
                                   idx (unsigned-bit-shift-right (+ base 4) 2)
                                   i32 (js/Int32Array. sab)]
                               ;; Successful CAS
                               (is (== 42 (js/Atomics.compareExchange i32 idx 42 43)))
                               (is (= 43 @c))
                               ;; Failed CAS (expected doesn't match)
                               (is (== 43 (js/Atomics.compareExchange i32 idx 42 99)))
                               (is (= 43 @c)))
                             :done)))]
      (is (= :done result)))))

(deftest type-id-check-test
  (testing "Type-id byte is written at offset 0"
    (let [a (eve/atom ::type-id-check-test {})
          result (swap! a (fn [_]
                           (let [c (->Counter 0)
                                 base (alloc/resolve-dv! (.-offset__ c))
                                 type-id (.getUint8 alloc/resolved-dv base)]
                             (is (number? type-id))
                             (is (>= type-id 64) "User type IDs start at 64")
                             :done)))]
      (is (= :done result)))))

(deftest identity-equality-test
  (testing "Identity-based equality"
    (let [a (eve/atom ::identity-equality-test {})
          result (swap! a (fn [_]
                           (let [c1 (->Counter 0)
                                 c2 (->Counter 0)
                                 c1-alias (Counter. (.-offset__ c1))]
                             ;; Different instances at different offsets are not equal
                             (is (not= c1 c2))
                             ;; Same offset = equal
                             (is (= c1 c1-alias))
                             :done)))]
      (is (= :done result)))))

(deftest print-test
  (testing "IPrintWithWriter"
    (let [a (eve/atom ::print-test {})
          result (swap! a (fn [_]
                           (let [c (->Counter 42)
                                 s (pr-str c)]
                             (is (string? s))
                             (is (re-find #"Counter" s))
                             :done)))]
      (is (= :done result)))))
