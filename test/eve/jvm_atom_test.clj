(ns eve.jvm-atom-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as atom]))

(def ^:private base (str "/tmp/eve-p7-jvm-atom-" (System/currentTimeMillis)))

;; =========================================================================
;; Persistent (mmap-backed) atom tests — via persistent-atom
;; =========================================================================

(deftest test-fresh-atom-nil
  (let [b (str base "-nil")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} nil)]
    (is (nil? @a))
    (atom/close-atom-domain! d)))

(deftest test-initial-val
  (let [b (str base "-iv")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} {:x 1})]
    (is (= 1 (:x @a)))
    (atom/close-atom-domain! d)))

(deftest test-swap-bang
  (let [b (str base "-sw")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} {:count 0})]
    (swap! a update :count inc)
    (is (= 1 (:count @a)))
    (atom/close-atom-domain! d)))

(deftest test-reset-bang
  (let [b (str base "-rs")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} {:n 0})]
    (reset! a {:n 42})
    (is (= 42 (:n @a)))
    (atom/close-atom-domain! d)))

(deftest test-nil-value
  (let [b (str base "-nl")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} {:x 1})]
    (reset! a nil)
    (is (nil? @a))
    (atom/close-atom-domain! d)))

(deftest test-join-atom
  (let [base2 (str base "-join")
        d1    (atom/persistent-atom-domain base2)
        a     (atom/persistent-atom {:id :eve/main :persistent base2} {:count 5})]
    (atom/close-atom-domain! d1)
    (let [d2 (atom/persistent-atom-domain base2)
          b  (atom/persistent-atom {:id :eve/main :persistent base2} nil)]
      (is (= 5 (:count @b)))
      (atom/close-atom-domain! d2))))

(deftest test-multiple-swaps
  (let [b (str base "-multi")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} {:count 0})]
    (dotimes [_ 10] (swap! a update :count inc))
    (is (= 10 (:count @a)))
    (atom/close-atom-domain! d)))

;; =========================================================================
;; atom/atom API dispatch tests
;; =========================================================================

(deftest test-atom-fn-persistent-dispatch
  (testing "atom with :persistent delegates to persistent-atom"
    (let [b (str base "-atom-fn")
          d (atom/persistent-atom-domain b)
          a (atom/atom {:id :eve/via-atom :persistent b} {:v 99})]
      (is (= 99 (:v @a)))
      (swap! a assoc :v 100)
      (is (= 100 (:v @a)))
      (atom/close-atom-domain! d))))

;; =========================================================================
;; Heap-backed (non-persistent) atom tests — via atom/atom
;; =========================================================================

;; --- API forms: keyword shorthand, config map, anonymous ---

(deftest test-heap-keyword-shorthand
  (testing "atom/atom with qualified keyword creates heap-backed atom"
    (let [a (atom/atom :test.heap/keyword-form {:x 1})]
      (is (= {:x 1} @a))
      (swap! a assoc :x 2)
      (is (= {:x 2} @a)))))

(deftest test-heap-config-map
  (testing "atom/atom with config map (no :persistent) creates heap-backed atom"
    (let [a (atom/atom {:id :test.heap/config-form} {:y 10})]
      (is (= {:y 10} @a))
      (swap! a update :y + 5)
      (is (= {:y 15} @a)))))

(deftest test-heap-anonymous
  (testing "atom/atom with just a value creates anonymous heap-backed atom"
    (let [a (atom/atom {:count 0})]
      (is (= {:count 0} @a))
      (swap! a update :count inc)
      (is (= {:count 1} @a)))))

(deftest test-heap-multiple-anonymous
  (testing "multiple anonymous atoms are independent"
    (let [a (atom/atom {:n 1})
          b (atom/atom {:n 2})]
      (swap! a update :n + 10)
      (is (= {:n 11} @a))
      (is (= {:n 2} @b)))))

;; --- Basic operations: swap!, reset!, deref ---

(deftest test-heap-swap-arities
  (testing "swap! with various arities"
    (let [a (atom/atom :test.heap/swap-arity {:count 0})]
      ;; 1-arg fn
      (swap! a update :count inc)
      (is (= 1 (:count @a)))
      ;; 2-arg fn
      (swap! a assoc :name "test")
      (is (= "test" (:name @a)))
      ;; 3-arg fn (update-in uses 2 extra args via apply)
      (swap! a update-in [:count] + 10)
      (is (= 11 (:count @a))))))

(deftest test-heap-reset
  (testing "reset! replaces value entirely"
    (let [a (atom/atom :test.heap/reset {:a 1 :b 2})]
      (reset! a {:c 3})
      (is (= {:c 3} @a)))))

(deftest test-heap-nil-lifecycle
  (testing "atom supports nil → value → nil transitions"
    (let [a (atom/atom :test.heap/nil-lifecycle nil)]
      (is (nil? @a))
      (reset! a {:x 1})
      (is (= {:x 1} @a))
      (reset! a nil)
      (is (nil? @a)))))

;; --- Value types ---

(deftest test-heap-map-value
  (testing "heap atom stores maps"
    (let [a (atom/atom :test.heap/map-val {:a 1 :b "hello" :c true})]
      (is (= 1 (:a @a)))
      (is (= "hello" (:b @a)))
      (is (true? (:c @a))))))

(deftest test-heap-vector-value
  (testing "heap atom stores vectors"
    (let [a (atom/atom :test.heap/vec-val [1 2 3])]
      (is (= [1 2 3] @a))
      (swap! a conj 4)
      (is (= [1 2 3 4] @a)))))

(deftest test-heap-set-value
  (testing "heap atom stores sets"
    (let [a (atom/atom :test.heap/set-val #{:a :b :c})]
      (is (= #{:a :b :c} @a))
      (swap! a conj :d)
      (is (contains? @a :d)))))

(deftest test-heap-list-value
  (testing "heap atom stores lists"
    (let [a (atom/atom :test.heap/list-val '(1 2 3))]
      (is (= '(1 2 3) @a)))))

(deftest test-heap-nested-collections
  (testing "heap atom stores nested collections"
    (let [v {:users [{:name "alice" :roles #{:admin}}
                     {:name "bob"   :roles #{:user}}]
             :meta  {:version 1}}
          a (atom/atom :test.heap/nested v)]
      (is (= "alice" (get-in @a [:users 0 :name])))
      (is (contains? (get-in @a [:users 0 :roles]) :admin))
      (swap! a update-in [:meta :version] inc)
      (is (= 2 (get-in @a [:meta :version]))))))

(deftest test-heap-scalar-values
  (testing "heap atom stores scalars (string, number, keyword)"
    (let [a (atom/atom :test.heap/scalar-str "hello")]
      (is (= "hello" @a))
      (reset! a 42)
      (is (= 42 @a))
      (reset! a :done)
      (is (= :done @a)))))

;; --- Multiple swaps / stress ---

(deftest test-heap-many-swaps
  (testing "100 sequential swaps produce correct result"
    (let [a (atom/atom :test.heap/many-swaps {:count 0})]
      (dotimes [_ 100] (swap! a update :count inc))
      (is (= 100 (:count @a))))))

(deftest test-heap-growing-map
  (testing "atom handles growing maps (many keys)"
    (let [a (atom/atom :test.heap/growing-map {})]
      (dotimes [i 50]
        (swap! a assoc (keyword (str "k" i)) i))
      (is (= 50 (count @a)))
      (is (= 0 (:k0 @a)))
      (is (= 49 (:k49 @a))))))

;; --- Concurrent access (multi-threaded) ---

(deftest test-heap-concurrent-swaps
  (testing "concurrent swap! from multiple threads converges correctly"
    (let [a       (atom/atom :test.heap/concurrent {:count 0})
          n       8
          per     50
          latch   (java.util.concurrent.CountDownLatch. n)
          threads (mapv (fn [_]
                          (Thread.
                            (fn []
                              (dotimes [_ per]
                                (swap! a update :count inc))
                              (.countDown latch))))
                        (range n))]
      (doseq [t threads] (.start t))
      (.await latch 30 java.util.concurrent.TimeUnit/SECONDS)
      (is (= (* n per) (:count @a))))))

;; --- Same-id atoms share domain ---

(deftest test-heap-same-id-returns-same-atom
  (testing "atom/atom with same keyword returns atom pointing to same slot"
    (let [a (atom/atom :test.heap/shared-id {:count 0})
          _ (swap! a update :count inc)
          b (atom/atom :test.heap/shared-id {:count 0})]
      ;; b should see the current value, not re-initialize
      (is (= 1 (:count @b))))))

;; --- Persistent via atom/atom with :persistent true ---

(deftest test-atom-fn-persistent-true
  (testing "atom with :persistent true uses default ./eve/ domain"
    ;; Use timestamp-based id so the test is idempotent across runs
    (let [id (keyword "test.heap" (str "ptrue-" (System/nanoTime)))
          a  (atom/atom {:id id :persistent true} {:v 1})]
      (is (= 1 (:v @a)))
      (swap! a assoc :v 2)
      (is (= 2 (:v @a))))))

(deftest test-atom-fn-persistent-path
  (testing "atom with :persistent path creates mmap-backed atom"
    (let [b (str base "-atom-path")
          d (atom/persistent-atom-domain b)
          a (atom/atom {:id :test.heap/ppath :persistent b} {:w 10})]
      (is (= 10 (:w @a)))
      (swap! a update :w * 2)
      (is (= 20 (:w @a)))
      (atom/close-atom-domain! d))))
