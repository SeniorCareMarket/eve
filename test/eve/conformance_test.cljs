(ns ^{:isolated true} eve.conformance-test
  "Conformance tests for Eve persistent data structures.
   Validates that Eve maps, vectors, sets, and lists behave like their
   Clojure counterparts. Inspired by jank-lang/clojure-test-suite (MPL-2.0)
   and ClojureScript core_test.cljs (EPL-1.0)."
  (:require
   [cljs.test :refer-macros [deftest testing is are]]
   [eve.alpha :as e]
   [eve.map :as em]
   [eve.vec :as ev]
   [eve.set :as es]
   [eve.list :as el]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn eve-map
  "Create an Eve hash-map from key-value pairs."
  [& kvs]
  (apply em/hash-map kvs))

(defn eve-set
  "Create an Eve set from elements."
  [& elems]
  (into (es/empty-hash-set) elems))

(defn eve-list
  "Create an Eve list from elements (preserves order)."
  [& elems]
  (el/sab-list elems))

(defmacro with-vec
  "Run body inside an atom swap to provide the required atomic context
   for Eve vector allocation. Returns the last expression's value."
  [test-id & body]
  `(let [a# (e/atom ~test-id {})
         result# (swap! a# (fn [~'_] ~@body))]
     result#))

;; ============================================================================
;; MAP CONFORMANCE
;; ============================================================================

(deftest map-assoc-test
  (testing "assoc on empty map"
    (is (= 1 (get (assoc (em/empty-hash-map) :a 1) :a))))

  (testing "assoc single key-value"
    (let [m (assoc (em/empty-hash-map) :a 1)]
      (is (= 1 (count m)))
      (is (= 1 (get m :a)))))

  (testing "assoc multiple key-values"
    (let [m (-> (em/empty-hash-map)
                (assoc :a 1)
                (assoc :b 2)
                (assoc :c 3))]
      (is (= 3 (count m)))
      (is (= 1 (get m :a)))
      (is (= 2 (get m :b)))
      (is (= 3 (get m :c)))))

  (testing "assoc overwrites existing key"
    (let [m (-> (em/empty-hash-map) (assoc :a 1) (assoc :a 2))]
      (is (= 1 (count m)))
      (is (= 2 (get m :a)))))

  (testing "assoc multiple pairs at once"
    (let [m (assoc (em/empty-hash-map) :a 1 :b 2 :c 3)]
      (is (= 3 (count m)))
      (is (= 1 (get m :a)))
      (is (= 2 (get m :b)))
      (is (= 3 (get m :c))))))

(deftest map-dissoc-test
  (testing "dissoc existing key"
    (let [m (dissoc (eve-map :a 1 :b 2) :a)]
      (is (= 1 (count m)))
      (is (nil? (get m :a)))
      (is (= 2 (get m :b)))))

  (testing "dissoc non-existing key"
    (let [m (dissoc (eve-map :a 1) :z)]
      (is (= 1 (count m)))
      (is (= 1 (get m :a)))))

  (testing "dissoc multiple keys"
    (let [m (dissoc (eve-map :a 1 :b 2 :c 3) :a :b)]
      (is (= 1 (count m)))
      (is (= 3 (get m :c)))))

  (testing "dissoc from empty map"
    (let [m (dissoc (em/empty-hash-map) :a)]
      (is (= 0 (count m))))))

(deftest map-get-test
  (testing "get existing key"
    (is (= 1 (get (eve-map :a 1 :b 2) :a))))

  (testing "get missing key returns nil"
    (is (nil? (get (eve-map :a 1) :z))))

  (testing "get with not-found"
    (is (= :default (get (eve-map :a 1) :z :default))))

  (testing "get nil key"
    (is (nil? (get (em/empty-hash-map) nil))))

  (testing "nil value vs missing key"
    (let [m (eve-map :a nil)]
      (is (nil? (get m :a)))
      (is (contains? m :a))
      (is (not (contains? m :b))))))

(deftest map-contains?-test
  (testing "contains? on map"
    (let [m (eve-map :a 1 :b 2)]
      (is (contains? m :a))
      (is (contains? m :b))
      (is (not (contains? m :c)))))

  (testing "contains? with nil value"
    (is (contains? (eve-map :a nil) :a)))

  (testing "contains? on empty map"
    (is (not (contains? (em/empty-hash-map) :a)))))

(deftest map-count-test
  (testing "count"
    (is (= 0 (count (em/empty-hash-map))))
    (is (= 1 (count (eve-map :a 1))))
    (is (= 3 (count (eve-map :a 1 :b 2 :c 3))))))

(deftest map-seq-test
  (testing "seq of empty map"
    (is (nil? (seq (em/empty-hash-map)))))

  (testing "seq roundtrip"
    (let [m (eve-map :a 1 :b 2 :c 3)
          entries (seq m)
          rebuilt (into {} entries)]
      (is (= 3 (count entries)))
      (is (= 1 (get rebuilt :a)))
      (is (= 2 (get rebuilt :b)))
      (is (= 3 (get rebuilt :c))))))

(deftest map-keys-vals-test
  (testing "keys"
    (let [m (eve-map :a 1 :b 2 :c 3)
          ks (set (keys m))]
      (is (= #{:a :b :c} ks))))

  (testing "vals"
    (let [m (eve-map :a 1 :b 2 :c 3)
          vs (set (vals m))]
      (is (= #{1 2 3} vs)))))

(deftest map-find-test
  (testing "find existing key"
    (let [entry (find (eve-map :a 1 :b 2) :a)]
      (is (some? entry))
      (is (= :a (key entry)))
      (is (= 1 (val entry)))))

  (testing "find missing key"
    (is (nil? (find (eve-map :a 1) :z)))))

(deftest map-empty-test
  (testing "empty returns empty map"
    (let [m (eve-map :a 1 :b 2)
          e (empty m)]
      (is (= 0 (count e))))))

(deftest map-reduce-test
  (testing "reduce over map entries"
    (let [m (eve-map :a 1 :b 2 :c 3)
          total (reduce (fn [acc [_k v]] (+ acc v)) 0 m)]
      (is (= 6 total))))

  (testing "reduce-kv"
    (let [m (eve-map :a 1 :b 2 :c 3)
          result (reduce-kv (fn [acc k v] (assoc acc k (inc v)))
                            {}
                            m)]
      (is (= {:a 2 :b 3 :c 4} result)))))

(deftest map-into-test
  (testing "into from pairs"
    (let [m (into (em/empty-hash-map) [[:a 1] [:b 2] [:c 3]])]
      (is (= 3 (count m)))
      (is (= 1 (get m :a)))))

  (testing "into from another map"
    (let [m (into (em/empty-hash-map) {:a 1 :b 2})]
      (is (= 2 (count m)))
      (is (= 1 (get m :a))))))

(deftest map-equality-test
  (testing "equal eve maps"
    (is (= (eve-map :a 1 :b 2) (eve-map :a 1 :b 2))))

  (testing "unequal eve maps - different values"
    (is (not= (eve-map :a 1) (eve-map :a 2))))

  (testing "unequal eve maps - different keys"
    (is (not= (eve-map :a 1) (eve-map :b 1))))

  (testing "unequal eve maps - different count"
    (is (not= (eve-map :a 1) (eve-map :a 1 :b 2))))

  (testing "eve map equals native map"
    (is (= (eve-map :a 1 :b 2) {:a 1 :b 2}))
    (is (= {:a 1 :b 2} (eve-map :a 1 :b 2)))))

(deftest map-ifn-test
  (testing "map as function"
    (let [m (eve-map :a 1 :b 2)]
      (is (= 1 (m :a)))
      (is (= 2 (m :b)))
      (is (nil? (m :z)))
      (is (= :default (m :z :default))))))

(deftest map-merge-test
  (testing "merge two eve maps"
    (let [m (merge (eve-map :a 1 :b 2) (eve-map :b 3 :c 4))]
      (is (= 3 (get m :b)))
      (is (= 4 (get m :c)))
      (is (= 1 (get m :a)))))

  (testing "merge with nil"
    (is (= (eve-map :a 1) (merge (eve-map :a 1) nil)))
    (is (= (eve-map :a 1) (merge nil (eve-map :a 1))))))

(deftest map-select-keys-test
  (testing "select-keys"
    (let [m (eve-map :a 1 :b 2 :c 3)
          s (select-keys m [:a :c])]
      (is (= 2 (count s)))
      (is (= 1 (get s :a)))
      (is (= 3 (get s :c)))
      (is (nil? (get s :b))))))

(deftest map-update-test
  (testing "update existing key"
    (let [m (update (eve-map :a 1 :b 2) :a inc)]
      (is (= 2 (get m :a)))
      (is (= 2 (get m :b)))))

  (testing "update missing key"
    (let [m (update (eve-map :a 1) :b (fnil inc 0))]
      (is (= 1 (get m :b))))))

(deftest map-get-in-test
  (testing "get-in nested"
    (let [m (eve-map :a (eve-map :b 42))]
      (is (= 42 (get-in m [:a :b])))))

  (testing "get-in missing path"
    (is (nil? (get-in (eve-map :a 1) [:a :b]))))

  (testing "get-in with not-found"
    (is (= :nope (get-in (eve-map :a 1) [:z] :nope)))))

(deftest map-assoc-in-test
  (testing "assoc-in nested"
    (let [m (assoc-in (eve-map :a (eve-map :b 1)) [:a :b] 2)]
      (is (= 2 (get-in m [:a :b]))))))

(deftest map-update-in-test
  (testing "update-in nested"
    (let [m (update-in (eve-map :a (eve-map :b 1)) [:a :b] inc)]
      (is (= 2 (get-in m [:a :b]))))))

(deftest map-key-types-test
  (testing "integer keys"
    (let [m (eve-map 1 :a 2 :b)]
      (is (= :a (get m 1)))
      (is (= :b (get m 2)))))

  (testing "string keys"
    (let [m (eve-map "x" 1 "y" 2)]
      (is (= 1 (get m "x")))))

  (testing "keyword keys"
    (let [m (eve-map :foo 1 :bar 2)]
      (is (= 1 (get m :foo)))))

  (testing "nil key"
    (let [m (assoc (em/empty-hash-map) nil :val)]
      (is (= :val (get m nil))))))

(deftest map-large-test
  (testing "1000-entry map"
    (let [m (reduce (fn [m i] (assoc m i (* i 10)))
                    (em/empty-hash-map)
                    (range 1000))]
      (is (= 1000 (count m)))
      (doseq [i (range 0 1000 100)]
        (is (= (* i 10) (get m i)))))))

;; ============================================================================
;; VECTOR CONFORMANCE
;; All vector tests run inside an atom swap to provide atomic context.
;; ============================================================================

(deftest vec-conj-test
  (let [a (e/atom ::vec-conj {})
        result (swap! a (fn [_]
                          (testing "conj single element"
                            (let [v (conj (ev/empty-sab-vec) 42)]
                              (is (= 1 (count v)))
                              (is (= 42 (nth v 0)))))
                          (testing "conj multiple elements"
                            (let [v (reduce conj (ev/empty-sab-vec) (range 10))]
                              (is (= 10 (count v)))
                              (doseq [i (range 10)]
                                (is (= i (nth v i))))))
                          (testing "conj preserves order"
                            (let [v (-> (ev/empty-sab-vec) (conj :a) (conj :b) (conj :c))]
                              (is (= :a (nth v 0)))
                              (is (= :b (nth v 1)))
                              (is (= :c (nth v 2)))))
                          :done))]
    (is (= :done result))))

(deftest vec-nth-test
  (let [a (e/atom ::vec-nth {})
        result (swap! a (fn [_]
                          (testing "nth basic access"
                            (let [v (ev/sab-vec [10 20 30 40 50])]
                              (is (= 10 (nth v 0)))
                              (is (= 30 (nth v 2)))
                              (is (= 50 (nth v 4)))))
                          (testing "nth out of bounds throws"
                            (let [v (ev/sab-vec [1 2 3])]
                              (is (thrown? js/Error (nth v -1)))
                              (is (thrown? js/Error (nth v 3)))))
                          (testing "nth with not-found"
                            (let [v (ev/sab-vec [1 2 3])]
                              (is (= :nope (nth v 10 :nope)))
                              (is (= :nope (nth v -1 :nope)))))
                          :done))]
    (is (= :done result))))

(deftest vec-assoc-test
  (let [a (e/atom ::vec-assoc {})
        result (swap! a (fn [_]
                          (testing "assoc update existing index"
                            (let [v (ev/sab-vec [10 20 30])
                                  v2 (assoc v 1 99)]
                              (is (= 99 (nth v2 1)))
                              (is (= 20 (nth v 1)))))
                          (testing "assoc append at count"
                            (let [v (ev/sab-vec [1 2 3])
                                  v2 (assoc v 3 4)]
                              (is (= 4 (count v2)))
                              (is (= 4 (nth v2 3)))))
                          :done))]
    (is (= :done result))))

(deftest vec-pop-test
  (let [a (e/atom ::vec-pop {})
        result (swap! a (fn [_]
                          (testing "pop removes last"
                            (let [v (ev/sab-vec [10 20 30])
                                  v2 (pop v)]
                              (is (= 2 (count v2)))
                              (is (= 10 (nth v2 0)))
                              (is (= 20 (nth v2 1)))))
                          (testing "pop empty vector throws"
                            (is (thrown? js/Error (pop (ev/empty-sab-vec)))))
                          :done))]
    (is (= :done result))))

(deftest vec-peek-test
  (let [a (e/atom ::vec-peek {})
        result (swap! a (fn [_]
                          (testing "peek returns last element"
                            (is (= 30 (peek (ev/sab-vec [10 20 30])))))
                          (testing "peek empty vector"
                            (is (nil? (peek (ev/empty-sab-vec)))))
                          :done))]
    (is (= :done result))))

(deftest vec-count-test
  (let [a (e/atom ::vec-count {})
        result (swap! a (fn [_]
                          (testing "count"
                            (is (= 0 (count (ev/empty-sab-vec))))
                            (is (= 1 (count (ev/sab-vec [42]))))
                            (is (= 5 (count (ev/sab-vec [1 2 3 4 5])))))
                          :done))]
    (is (= :done result))))

(deftest vec-seq-test
  (let [a (e/atom ::vec-seq {})
        result (swap! a (fn [_]
                          (testing "seq of empty vector"
                            (is (nil? (seq (ev/empty-sab-vec)))))
                          (testing "seq roundtrip"
                            (let [v (ev/sab-vec [10 20 30])
                                  result (vec (seq v))]
                              (is (= [10 20 30] result))))
                          :done))]
    (is (= :done result))))

(deftest vec-get-test
  (let [a (e/atom ::vec-get {})
        result (swap! a (fn [_]
                          (testing "get by index"
                            (let [v (ev/sab-vec [:a :b :c])]
                              (is (= :a (get v 0)))
                              (is (= :c (get v 2)))))
                          (testing "get out of range returns nil"
                            (is (nil? (get (ev/sab-vec [1 2]) 10))))
                          (testing "get with not-found"
                            (is (= :nope (get (ev/sab-vec [1 2]) 10 :nope))))
                          :done))]
    (is (= :done result))))

(deftest vec-contains?-test
  (let [a (e/atom ::vec-contains {})
        result (swap! a (fn [_]
                          (testing "contains? checks indices, not values"
                            (let [v (ev/sab-vec [:a :b :c])]
                              (is (contains? v 0))
                              (is (contains? v 2))
                              (is (not (contains? v 3)))
                              (is (not (contains? v -1)))))
                          :done))]
    (is (= :done result))))

(deftest vec-empty-test
  (let [a (e/atom ::vec-empty {})
        result (swap! a (fn [_]
                          (testing "empty returns empty vector"
                            (let [v (ev/sab-vec [1 2 3])
                                  e (empty v)]
                              (is (= 0 (count e)))))
                          :done))]
    (is (= :done result))))

(deftest vec-reduce-test
  (let [a (e/atom ::vec-reduce {})
        result (swap! a (fn [_]
                          (testing "reduce"
                            (is (= 15 (reduce + (ev/sab-vec [1 2 3 4 5])))))
                          (testing "reduce with init"
                            (is (= 106 (reduce + 100 (ev/sab-vec [1 2 3])))))
                          :done))]
    (is (= :done result))))

(deftest vec-into-test
  (let [a (e/atom ::vec-into {})
        result (swap! a (fn [_]
                          (testing "into from sequence"
                            (let [v (into (ev/empty-sab-vec) [1 2 3 4 5])]
                              (is (= 5 (count v)))
                              (is (= 1 (nth v 0)))
                              (is (= 5 (nth v 4)))))
                          :done))]
    (is (= :done result))))

(deftest vec-equality-test
  (let [a (e/atom ::vec-eq {})
        result (swap! a (fn [_]
                          (testing "equal eve vectors"
                            (is (= (ev/sab-vec [1 2 3]) (ev/sab-vec [1 2 3]))))
                          (testing "unequal eve vectors"
                            (is (not= (ev/sab-vec [1 2 3]) (ev/sab-vec [1 2 4]))))
                          (testing "eve vector equals native vector"
                            (is (= (ev/sab-vec [1 2 3]) [1 2 3]))
                            (is (= [1 2 3] (ev/sab-vec [1 2 3]))))
                          :done))]
    (is (= :done result))))

(deftest vec-ifn-test
  (let [a (e/atom ::vec-ifn {})
        result (swap! a (fn [_]
                          (testing "vector as function"
                            (let [v (ev/sab-vec [:a :b :c])]
                              (is (= :a (v 0)))
                              (is (= :c (v 2)))))
                          :done))]
    (is (= :done result))))

(deftest vec-map-filter-test
  (let [a (e/atom ::vec-map-filter {})
        result (swap! a (fn [_]
                          (testing "map over vector"
                            (let [v (ev/sab-vec [1 2 3 4])
                                  result (map inc v)]
                              (is (= [2 3 4 5] (vec result)))))
                          (testing "filter vector"
                            (let [v (ev/sab-vec [1 2 3 4 5 6])
                                  result (filter even? v)]
                              (is (= [2 4 6] (vec result)))))
                          :done))]
    (is (= :done result))))

(deftest vec-key-types-test
  (let [a (e/atom ::vec-types {})
        result (swap! a (fn [_]
                          (testing "string values"
                            (let [v (ev/sab-vec ["hello" "world"])]
                              (is (= "hello" (nth v 0)))))
                          (testing "keyword values"
                            (let [v (ev/sab-vec [:a :b :c])]
                              (is (= :a (nth v 0)))))
                          (testing "nil values"
                            (let [v (ev/sab-vec [nil 1 nil])]
                              (is (nil? (nth v 0)))
                              (is (= 1 (nth v 1)))))
                          (testing "boolean values"
                            (let [v (ev/sab-vec [true false true])]
                              (is (true? (nth v 0)))
                              (is (false? (nth v 1)))))
                          (testing "mixed types"
                            (let [v (ev/sab-vec [42 "hello" :key true nil 3.14])]
                              (is (= 42 (nth v 0)))
                              (is (= "hello" (nth v 1)))
                              (is (= :key (nth v 2)))
                              (is (true? (nth v 3)))
                              (is (nil? (nth v 4)))
                              (is (== 3.14 (nth v 5)))))
                          :done))]
    (is (= :done result))))

(deftest vec-large-test
  (let [a (e/atom ::vec-large {})
        result (swap! a (fn [_]
                          (testing "1000-element vector"
                            (let [v (ev/sab-vec (range 1000))]
                              (is (= 1000 (count v)))
                              (doseq [i (range 0 1000 100)]
                                (is (= i (nth v i))))))
                          :done))]
    (is (= :done result))))

;; ============================================================================
;; SET CONFORMANCE
;; ============================================================================

(deftest set-conj-test
  (testing "conj single element"
    (let [s (conj (es/empty-hash-set) 42)]
      (is (= 1 (count s)))
      (is (contains? s 42))))

  (testing "conj multiple elements"
    (let [s (-> (es/empty-hash-set) (conj 1) (conj 2) (conj 3))]
      (is (= 3 (count s)))
      (is (contains? s 1))
      (is (contains? s 2))
      (is (contains? s 3))))

  (testing "conj duplicate is no-op"
    (let [s (-> (es/empty-hash-set) (conj 1) (conj 1) (conj 1))]
      (is (= 1 (count s))))))

(deftest set-disj-test
  (testing "disj existing element"
    (let [s (disj (eve-set 1 2 3) 2)]
      (is (= 2 (count s)))
      (is (not (contains? s 2)))
      (is (contains? s 1))
      (is (contains? s 3))))

  (testing "disj non-existing element"
    (let [s (disj (eve-set 1 2 3) 99)]
      (is (= 3 (count s)))))

  (testing "disj from empty set"
    (let [s (disj (es/empty-hash-set) 1)]
      (is (= 0 (count s))))))

(deftest set-contains?-test
  (testing "contains? basics"
    (let [s (eve-set :a :b :c)]
      (is (contains? s :a))
      (is (contains? s :b))
      (is (contains? s :c))
      (is (not (contains? s :d)))))

  (testing "contains? on empty set"
    (is (not (contains? (es/empty-hash-set) :a)))))

(deftest set-count-test
  (testing "count"
    (is (= 0 (count (es/empty-hash-set))))
    (is (= 1 (count (eve-set 42))))
    (is (= 3 (count (eve-set 1 2 3))))))

(deftest set-seq-test
  (testing "seq of empty set"
    (is (nil? (seq (es/empty-hash-set)))))

  (testing "seq roundtrip"
    (let [s (eve-set 1 2 3)
          elems (set (seq s))]
      (is (= #{1 2 3} elems)))))

(deftest set-empty-test
  (testing "empty returns empty set"
    (let [s (eve-set 1 2 3)
          e (empty s)]
      (is (= 0 (count e))))))

(deftest set-reduce-test
  (testing "reduce"
    (let [s (eve-set 1 2 3 4 5)]
      (is (= 15 (reduce + s)))))

  (testing "reduce with init"
    (let [s (eve-set 1 2 3)]
      (is (= 106 (reduce + 100 s))))))

(deftest set-into-test
  (testing "into from sequence"
    (let [s (into (es/empty-hash-set) [1 2 3 4 5])]
      (is (= 5 (count s)))
      (doseq [i (range 1 6)]
        (is (contains? s i)))))

  (testing "into from another set"
    (let [s (into (es/empty-hash-set) #{1 2 3})]
      (is (= 3 (count s))))))

(deftest set-equality-test
  (testing "equal eve sets"
    (is (= (eve-set 1 2 3) (eve-set 3 2 1))))

  (testing "unequal eve sets"
    (is (not= (eve-set 1 2) (eve-set 1 3))))

  (testing "eve set equals native set"
    (is (= (eve-set 1 2 3) #{1 2 3}))
    (is (= #{1 2 3} (eve-set 1 2 3)))))

(deftest set-ifn-test
  (testing "set as function"
    (let [s (eve-set :a :b :c)]
      (is (= :a (s :a)))
      (is (= :b (s :b)))
      (is (nil? (s :z))))))

(deftest set-key-types-test
  (testing "integer elements"
    (let [s (eve-set 1 2 3)]
      (is (= 3 (count s)))
      (is (contains? s 1))))

  (testing "string elements"
    (let [s (eve-set "hello" "world")]
      (is (= 2 (count s)))
      (is (contains? s "hello"))))

  (testing "keyword elements"
    (let [s (eve-set :a :b :c)]
      (is (= 3 (count s)))
      (is (contains? s :a))))

  (testing "mixed types"
    (let [s (eve-set 42 "hello" :key)]
      (is (= 3 (count s)))
      (is (contains? s 42))
      (is (contains? s "hello"))
      (is (contains? s :key)))))

(deftest set-large-test
  (testing "1000-element set"
    (let [s (into (es/empty-hash-set) (range 1000))]
      (is (= 1000 (count s)))
      (doseq [i (range 0 1000 100)]
        (is (contains? s i))))))

;; ============================================================================
;; LIST CONFORMANCE
;; ============================================================================

(deftest list-conj-test
  (testing "conj adds to front"
    (let [l (conj (el/empty-sab-list) 42)]
      (is (= 1 (count l)))
      (is (= 42 (first l)))))

  (testing "conj multiple - stack order"
    (let [l (-> (el/empty-sab-list) (conj 1) (conj 2) (conj 3))]
      (is (= 3 (count l)))
      (is (= 3 (first l)))
      (is (= 2 (first (rest l)))))))

(deftest list-peek-test
  (testing "peek returns first"
    (is (= 1 (peek (eve-list 1 2 3)))))

  (testing "peek empty list"
    (is (nil? (peek (el/empty-sab-list))))))

(deftest list-pop-test
  (testing "pop removes first"
    (let [l (pop (eve-list 1 2 3))]
      (is (= 2 (count l)))
      (is (= 2 (first l))))))

(deftest list-count-test
  (testing "count"
    (is (= 0 (count (el/empty-sab-list))))
    (is (= 1 (count (eve-list 42))))
    (is (= 3 (count (eve-list 1 2 3))))))

(deftest list-seq-test
  (testing "seq of empty list"
    (is (nil? (seq (el/empty-sab-list)))))

  (testing "seq preserves order"
    (let [l (eve-list 10 20 30)
          result (vec (seq l))]
      (is (= [10 20 30] result)))))

(deftest list-empty-test
  (testing "empty returns empty list"
    (let [l (eve-list 1 2 3)
          e (empty l)]
      (is (= 0 (count e))))))

(deftest list-reduce-test
  (testing "reduce"
    (let [l (eve-list 1 2 3 4 5)]
      (is (= 15 (reduce + l)))))

  (testing "reduce with init"
    (let [l (eve-list 1 2 3)]
      (is (= 106 (reduce + 100 l))))))

(deftest list-equality-test
  (testing "equal eve lists"
    (is (= (eve-list 1 2 3) (eve-list 1 2 3))))

  (testing "unequal eve lists"
    (is (not= (eve-list 1 2 3) (eve-list 1 2 4))))

  (testing "eve list equals native list"
    (is (= (eve-list 1 2 3) '(1 2 3)))
    (is (= '(1 2 3) (eve-list 1 2 3)))))

(deftest list-key-types-test
  (testing "string values"
    (let [l (eve-list "hello" "world")]
      (is (= "hello" (first l)))))

  (testing "keyword values"
    (let [l (eve-list :a :b :c)]
      (is (= :a (first l)))))

  (testing "mixed types"
    (let [l (eve-list 42 "hello" :key true nil)]
      (is (= 42 (first l))))))

(deftest list-large-test
  (testing "1000-element list"
    (let [l (el/sab-list (range 1000))]
      (is (= 1000 (count l)))
      (is (= 0 (first l))))))

;; ============================================================================
;; CROSS-TYPE CONFORMANCE
;; ============================================================================

(deftest nested-map-collections-test
  (testing "map containing set"
    (let [m (eve-map :tags (eve-set :a :b))]
      (is (contains? (get m :tags) :a))))

  (testing "map of maps"
    (let [m (eve-map :inner (eve-map :deep 42))]
      (is (= 42 (get-in m [:inner :deep]))))))

(deftest nested-vec-collections-test
  (let [a (e/atom ::nested-vec {})
        result (swap! a (fn [_]
                          (testing "map containing vector"
                            (let [m (eve-map :data (ev/sab-vec [1 2 3]))]
                              (is (= 1 (nth (get m :data) 0)))
                              (is (= 3 (count (get m :data))))))
                          (testing "vector containing maps"
                            (let [v (ev/sab-vec [(eve-map :a 1) (eve-map :b 2)])]
                              (is (= 1 (get (nth v 0) :a)))
                              (is (= 2 (get (nth v 1) :b)))))
                          :done))]
    (is (= :done result))))

(deftest hash-consistency-test
  (let [a (e/atom ::hash-consistency {})
        result (swap! a (fn [_]
                          (testing "equal collections have equal hashes"
                            (is (= (hash (eve-map :a 1 :b 2))
                                   (hash (eve-map :a 1 :b 2))))
                            (is (= (hash (ev/sab-vec [1 2 3]))
                                   (hash (ev/sab-vec [1 2 3]))))
                            (is (= (hash (eve-set 1 2 3))
                                   (hash (eve-set 1 2 3))))
                            (is (= (hash (eve-list 1 2 3))
                                   (hash (eve-list 1 2 3)))))
                          :done))]
    (is (= :done result))))

(deftest persistent-semantics-test
  (let [a (e/atom ::persistence {})
        result (swap! a (fn [_]
                          (testing "map persistence"
                            (let [m1 (eve-map :a 1)
                                  m2 (assoc m1 :b 2)]
                              (is (= 1 (count m1)))
                              (is (= 2 (count m2)))
                              (is (nil? (get m1 :b)))))
                          (testing "vector persistence"
                            (let [v1 (ev/sab-vec [1 2 3])
                                  v2 (conj v1 4)]
                              (is (= 3 (count v1)))
                              (is (= 4 (count v2)))))
                          (testing "set persistence"
                            (let [s1 (eve-set 1 2 3)
                                  s2 (conj s1 4)]
                              (is (= 3 (count s1)))
                              (is (= 4 (count s2)))))
                          (testing "list persistence"
                            (let [l1 (eve-list 1 2 3)
                                  l2 (conj l1 0)]
                              (is (= 3 (count l1)))
                              (is (= 4 (count l2)))))
                          :done))]
    (is (= :done result))))
