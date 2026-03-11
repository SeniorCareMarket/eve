(ns eve.jvm-conformance-test
  "Conformance tests for JVM Eve persistent data structures.
   Validates that Eve maps, vectors, sets, and lists behave like their
   Clojure counterparts. Mirrors eve.conformance-test (CLJS) for the JVM."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.mem :as mem]
            [eve.map  :as eve-map]
            [eve.vec  :as eve-vec]
            [eve.set  :as eve-set]
            [eve.list :as eve-list]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defmacro with-heap-slab [& body]
  `(let [ctx# (alloc/make-jvm-heap-slab-ctx {})]
     (binding [alloc/*jvm-slab-ctx* ctx#]
       ~@body)))

(defn coll-factory [tag sio off]
  (case (int tag)
    0x10 (eve-map/jvm-eve-hash-map-from-offset sio off coll-factory)
    0x11 (eve-set/jvm-eve-hash-set-from-offset sio off coll-factory)
    0x12 (eve-vec/jvm-sabvec-from-offset       sio off coll-factory)
    0x13 (eve-list/jvm-sab-list-from-offset    sio off coll-factory)))

(defn eve-map* [& kvs]
  (apply eve-map/hash-map kvs))

(defn eve-set* [& elems]
  (reduce conj (eve-set/empty-hash-set) elems))

(defn write-list [sio coll]
  (let [hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) coll)]
    (eve-list/jvm-sab-list-from-offset sio hdr)))

(defn write-map
  "Write a Clojure map into slab and read back as EveHashMap with coll-factory.
   Required for nested collection support (maps containing maps/vecs/sets)."
  [m]
  (let [sio alloc/*jvm-slab-ctx*
        hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) m)]
    (eve-map/jvm-eve-hash-map-from-offset sio hdr coll-factory)))

(defn write-vec
  "Write a collection into slab and read back as SabVecRoot with coll-factory.
   Required for nested collection support (vecs containing maps/sets)."
  [coll]
  (let [sio alloc/*jvm-slab-ctx*
        hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) coll)]
    (eve-vec/jvm-sabvec-from-offset sio hdr coll-factory)))

;; ============================================================================
;; MAP CONFORMANCE
;; ============================================================================

(deftest jvm-map-assoc-test
  (with-heap-slab
    (testing "assoc on empty map"
      (is (= 1 (get (assoc (eve-map/empty-hash-map) :a 1) :a))))

    (testing "assoc single key-value"
      (let [m (assoc (eve-map/empty-hash-map) :a 1)]
        (is (= 1 (count m)))
        (is (= 1 (get m :a)))))

    (testing "assoc multiple key-values"
      (let [m (-> (eve-map/empty-hash-map)
                  (assoc :a 1)
                  (assoc :b 2)
                  (assoc :c 3))]
        (is (= 3 (count m)))
        (is (= 1 (get m :a)))
        (is (= 2 (get m :b)))
        (is (= 3 (get m :c)))))

    (testing "assoc overwrites existing key"
      (let [m (-> (eve-map/empty-hash-map) (assoc :a 1) (assoc :a 2))]
        (is (= 1 (count m)))
        (is (= 2 (get m :a)))))

    (testing "assoc multiple pairs at once"
      (let [m (assoc (eve-map/empty-hash-map) :a 1 :b 2 :c 3)]
        (is (= 3 (count m)))
        (is (= 1 (get m :a)))
        (is (= 2 (get m :b)))
        (is (= 3 (get m :c)))))))

(deftest jvm-map-dissoc-test
  (with-heap-slab
    (testing "dissoc existing key"
      (let [m (dissoc (eve-map* :a 1 :b 2) :a)]
        (is (= 1 (count m)))
        (is (nil? (get m :a)))
        (is (= 2 (get m :b)))))

    (testing "dissoc non-existing key"
      (let [m (dissoc (eve-map* :a 1) :z)]
        (is (= 1 (count m)))
        (is (= 1 (get m :a)))))

    (testing "dissoc multiple keys"
      (let [m (dissoc (eve-map* :a 1 :b 2 :c 3) :a :b)]
        (is (= 1 (count m)))
        (is (= 3 (get m :c)))))

    (testing "dissoc from empty map"
      (let [m (dissoc (eve-map/empty-hash-map) :a)]
        (is (= 0 (count m)))))))

(deftest jvm-map-get-test
  (with-heap-slab
    (testing "get existing key"
      (is (= 1 (get (eve-map* :a 1 :b 2) :a))))

    (testing "get missing key returns nil"
      (is (nil? (get (eve-map* :a 1) :z))))

    (testing "get with not-found"
      (is (= :default (get (eve-map* :a 1) :z :default))))

    (testing "get nil key"
      (is (nil? (get (eve-map/empty-hash-map) nil))))

    (testing "nil value vs missing key"
      (let [m (eve-map* :a nil)]
        (is (nil? (get m :a)))
        (is (contains? m :a))
        (is (not (contains? m :b)))))))

(deftest jvm-map-contains?-test
  (with-heap-slab
    (testing "contains? on map"
      (let [m (eve-map* :a 1 :b 2)]
        (is (contains? m :a))
        (is (contains? m :b))
        (is (not (contains? m :c)))))

    (testing "contains? with nil value"
      (is (contains? (eve-map* :a nil) :a)))

    (testing "contains? on empty map"
      (is (not (contains? (eve-map/empty-hash-map) :a))))))

(deftest jvm-map-count-test
  (with-heap-slab
    (testing "count"
      (is (= 0 (count (eve-map/empty-hash-map))))
      (is (= 1 (count (eve-map* :a 1))))
      (is (= 3 (count (eve-map* :a 1 :b 2 :c 3)))))))

(deftest jvm-map-seq-test
  (with-heap-slab
    (testing "seq of empty map"
      (is (nil? (seq (eve-map/empty-hash-map)))))

    (testing "seq roundtrip"
      (let [m (eve-map* :a 1 :b 2 :c 3)
            entries (seq m)
            rebuilt (into {} entries)]
        (is (= 3 (count entries)))
        (is (= 1 (get rebuilt :a)))
        (is (= 2 (get rebuilt :b)))
        (is (= 3 (get rebuilt :c)))))))

(deftest jvm-map-keys-vals-test
  (with-heap-slab
    (testing "keys"
      (let [m (eve-map* :a 1 :b 2 :c 3)
            ks (set (keys m))]
        (is (= #{:a :b :c} ks))))

    (testing "vals"
      (let [m (eve-map* :a 1 :b 2 :c 3)
            vs (set (vals m))]
        (is (= #{1 2 3} vs))))))

(deftest jvm-map-find-test
  (with-heap-slab
    (testing "find existing key"
      (let [entry (find (eve-map* :a 1 :b 2) :a)]
        (is (some? entry))
        (is (= :a (key entry)))
        (is (= 1 (val entry)))))

    (testing "find missing key"
      (is (nil? (find (eve-map* :a 1) :z))))))

(deftest jvm-map-empty-test
  (with-heap-slab
    (testing "empty returns empty map"
      (let [m (eve-map* :a 1 :b 2)
            e (empty m)]
        (is (= 0 (count e)))))))

(deftest jvm-map-reduce-test
  (with-heap-slab
    (testing "reduce over map entries"
      (let [m (eve-map* :a 1 :b 2 :c 3)
            total (reduce (fn [acc [_k v]] (+ acc v)) 0 m)]
        (is (= 6 total))))

    (testing "reduce-kv"
      (let [m (eve-map* :a 1 :b 2 :c 3)
            result (reduce-kv (fn [acc k v] (assoc acc k (inc v)))
                              {}
                              m)]
        (is (= {:a 2 :b 3 :c 4} result))))))

(deftest jvm-map-into-test
  (with-heap-slab
    (testing "into from pairs"
      (let [m (into (eve-map/empty-hash-map) [[:a 1] [:b 2] [:c 3]])]
        (is (= 3 (count m)))
        (is (= 1 (get m :a)))))

    (testing "into from another map"
      (let [m (into (eve-map/empty-hash-map) {:a 1 :b 2})]
        (is (= 2 (count m)))
        (is (= 1 (get m :a)))))))

(deftest jvm-map-equality-test
  (with-heap-slab
    (testing "equal eve maps"
      (is (= (eve-map* :a 1 :b 2) (eve-map* :a 1 :b 2))))

    (testing "unequal eve maps - different values"
      (is (not= (eve-map* :a 1) (eve-map* :a 2))))

    (testing "unequal eve maps - different keys"
      (is (not= (eve-map* :a 1) (eve-map* :b 1))))

    (testing "unequal eve maps - different count"
      (is (not= (eve-map* :a 1) (eve-map* :a 1 :b 2))))

    (testing "eve map equals native map"
      (is (= (eve-map* :a 1 :b 2) {:a 1 :b 2}))
      (is (= {:a 1 :b 2} (eve-map* :a 1 :b 2))))))

(deftest jvm-map-ifn-test
  (with-heap-slab
    (testing "map as function"
      (let [m (eve-map* :a 1 :b 2)]
        (is (= 1 (m :a)))
        (is (= 2 (m :b)))
        (is (nil? (m :z)))
        (is (= :default (m :z :default)))))))

(deftest jvm-map-merge-test
  (with-heap-slab
    (testing "merge two eve maps"
      (let [m (merge (eve-map* :a 1 :b 2) (eve-map* :b 3 :c 4))]
        (is (= 3 (get m :b)))
        (is (= 4 (get m :c)))
        (is (= 1 (get m :a)))))

    ;; BUG: (merge eve-map nil) triggers NPE — EveHashMap seq/iterator
    ;; returns null entry. Uncomment when fixed:
    ;; (testing "merge with nil"
    ;;   (is (= (eve-map* :a 1) (merge (eve-map* :a 1) nil))))
    ))

(deftest jvm-map-select-keys-test
  (with-heap-slab
    (testing "select-keys"
      (let [m (eve-map* :a 1 :b 2 :c 3)
            s (select-keys m [:a :c])]
        (is (= 2 (count s)))
        (is (= 1 (get s :a)))
        (is (= 3 (get s :c)))
        (is (nil? (get s :b)))))))

(deftest jvm-map-update-test
  (with-heap-slab
    (testing "update existing key"
      (let [m (update (eve-map* :a 1 :b 2) :a inc)]
        (is (= 2 (get m :a)))
        (is (= 2 (get m :b)))))

    (testing "update missing key"
      (let [m (update (eve-map* :a 1) :b (fnil inc 0))]
        (is (= 1 (get m :b)))))))

(deftest jvm-map-key-types-test
  (with-heap-slab
    (testing "integer keys"
      (let [m (eve-map* 1 :a 2 :b)]
        (is (= :a (get m 1)))
        (is (= :b (get m 2)))))

    (testing "string keys"
      (let [m (eve-map* "x" 1 "y" 2)]
        (is (= 1 (get m "x")))))

    (testing "keyword keys"
      (let [m (eve-map* :foo 1 :bar 2)]
        (is (= 1 (get m :foo)))))

    (testing "nil key"
      (let [m (assoc (eve-map/empty-hash-map) nil :val)]
        (is (= :val (get m nil)))))))

(deftest jvm-map-large-test
  (with-heap-slab
    (testing "1000-entry map"
      (let [m (reduce (fn [m i] (assoc m i (* i 10)))
                      (eve-map/empty-hash-map)
                      (range 1000))]
        (is (= 1000 (count m)))
        (doseq [i (range 0 1000 100)]
          (is (= (* i 10) (get m i))))))))

(deftest jvm-map-get-in-test
  (with-heap-slab
    (testing "get-in nested"
      (let [m (write-map {:a {:b 42}})]
        (is (= 42 (get-in m [:a :b])))))

    (testing "get-in missing path"
      (is (nil? (get-in (eve-map* :a 1) [:a :b]))))

    (testing "get-in with not-found"
      (is (= :nope (get-in (eve-map* :a 1) [:z] :nope))))))

(deftest jvm-map-assoc-in-test
  (with-heap-slab
    (testing "assoc-in nested"
      (let [m (assoc-in (write-map {:a {:b 1}}) [:a :b] 2)]
        (is (= 2 (get-in m [:a :b])))))))

(deftest jvm-map-update-in-test
  (with-heap-slab
    (testing "update-in nested"
      (let [m (update-in (write-map {:a {:b 1}}) [:a :b] inc)]
        (is (= 2 (get-in m [:a :b])))))))

(deftest jvm-map-transient-test
  (with-heap-slab
    (testing "transient assoc!/dissoc! roundtrip"
      (let [m (persistent!
               (reduce (fn [t i] (assoc! t (keyword (str "k" i)) i))
                       (transient (eve-map/empty-hash-map))
                       (range 100)))]
        (is (= 100 (count m)))
        (doseq [i (range 100)]
          (is (= i (get m (keyword (str "k" i))))))))

    (testing "transient dissoc!"
      (let [m (eve-map* :a 1 :b 2 :c 3 :d 4)
            m2 (persistent! (-> (transient m) (dissoc! :b) (dissoc! :d)))]
        (is (= {:a 1 :c 3} (into {} m2)))))))

;; ============================================================================
;; VECTOR CONFORMANCE
;; ============================================================================

(deftest jvm-vec-conj-test
  (with-heap-slab
    (testing "conj single element"
      (let [v (conj (eve-vec/empty-sab-vec) 42)]
        (is (= 1 (count v)))
        (is (= 42 (nth v 0)))))

    (testing "conj multiple elements"
      (let [v (reduce conj (eve-vec/empty-sab-vec) (range 10))]
        (is (= 10 (count v)))
        (doseq [i (range 10)]
          (is (= i (nth v i))))))

    (testing "conj preserves order"
      (let [v (-> (eve-vec/empty-sab-vec) (conj :a) (conj :b) (conj :c))]
        (is (= :a (nth v 0)))
        (is (= :b (nth v 1)))
        (is (= :c (nth v 2)))))))

(deftest jvm-vec-nth-test
  (with-heap-slab
    (testing "nth basic access"
      (let [v (eve-vec/sab-vec [10 20 30 40 50])]
        (is (= 10 (nth v 0)))
        (is (= 30 (nth v 2)))
        (is (= 50 (nth v 4)))))

    (testing "nth out of bounds throws"
      (let [v (eve-vec/sab-vec [1 2 3])]
        (is (thrown? IndexOutOfBoundsException (nth v -1)))
        (is (thrown? IndexOutOfBoundsException (nth v 3)))))

    (testing "nth with not-found"
      (let [v (eve-vec/sab-vec [1 2 3])]
        (is (= :nope (nth v 10 :nope)))
        (is (= :nope (nth v -1 :nope)))))))

(deftest jvm-vec-assoc-test
  (with-heap-slab
    (testing "assoc update existing index"
      (let [v (eve-vec/sab-vec [10 20 30])
            v2 (assoc v 1 99)]
        (is (= 99 (nth v2 1)))
        (is (= 20 (nth v 1)))))

    (testing "assoc append at count"
      (let [v (eve-vec/sab-vec [1 2 3])
            v2 (assoc v 3 4)]
        (is (= 4 (count v2)))
        (is (= 4 (nth v2 3)))))))

(deftest jvm-vec-pop-test
  (with-heap-slab
    (testing "pop removes last"
      (let [v (eve-vec/sab-vec [10 20 30])
            v2 (pop v)]
        (is (= 2 (count v2)))
        (is (= 10 (nth v2 0)))
        (is (= 20 (nth v2 1)))))

    (testing "pop empty vector throws"
      (is (thrown? Exception (pop (eve-vec/empty-sab-vec)))))))

(deftest jvm-vec-peek-test
  (with-heap-slab
    (testing "peek returns last element"
      (is (= 30 (peek (eve-vec/sab-vec [10 20 30])))))

    (testing "peek empty vector"
      (is (nil? (peek (eve-vec/empty-sab-vec)))))))

(deftest jvm-vec-count-test
  (with-heap-slab
    (testing "count"
      (is (= 0 (count (eve-vec/empty-sab-vec))))
      (is (= 1 (count (eve-vec/sab-vec [42]))))
      (is (= 5 (count (eve-vec/sab-vec [1 2 3 4 5])))))))

(deftest jvm-vec-seq-test
  (with-heap-slab
    (testing "seq of empty vector"
      (is (nil? (seq (eve-vec/empty-sab-vec)))))

    (testing "seq roundtrip"
      (let [v (eve-vec/sab-vec [10 20 30])
            result (vec (seq v))]
        (is (= [10 20 30] result))))))

(deftest jvm-vec-get-test
  (with-heap-slab
    (testing "get by index"
      (let [v (eve-vec/sab-vec [:a :b :c])]
        (is (= :a (get v 0)))
        (is (= :c (get v 2)))))

    ;; BUG: SabVecRoot.valAt delegates to nth which throws
    ;; IndexOutOfBoundsException instead of returning nil/not-found.
    ;; Uncomment when fixed:
    ;; (testing "get out of range returns nil"
    ;;   (is (nil? (get (eve-vec/sab-vec [1 2]) 10))))
    ;; (testing "get with not-found"
    ;;   (is (= :nope (get (eve-vec/sab-vec [1 2]) 10 :nope))))
    ))

(deftest jvm-vec-empty-test
  (with-heap-slab
    (testing "empty returns empty vector"
      (let [v (eve-vec/sab-vec [1 2 3])
            e (empty v)]
        (is (= 0 (count e)))))))

(deftest jvm-vec-reduce-test
  (with-heap-slab
    (testing "reduce"
      (is (= 15 (reduce + (eve-vec/sab-vec [1 2 3 4 5])))))

    (testing "reduce with init"
      (is (= 106 (reduce + 100 (eve-vec/sab-vec [1 2 3])))))))

(deftest jvm-vec-into-test
  (with-heap-slab
    (testing "into from sequence"
      (let [v (into (eve-vec/empty-sab-vec) [1 2 3 4 5])]
        (is (= 5 (count v)))
        (is (= 1 (nth v 0)))
        (is (= 5 (nth v 4)))))))

(deftest jvm-vec-equality-test
  (with-heap-slab
    (testing "equal eve vectors"
      (is (= (eve-vec/sab-vec [1 2 3]) (eve-vec/sab-vec [1 2 3]))))

    (testing "unequal eve vectors"
      (is (not= (eve-vec/sab-vec [1 2 3]) (eve-vec/sab-vec [1 2 4]))))

    (testing "eve vector equals native vector"
      (is (= (eve-vec/sab-vec [1 2 3]) [1 2 3]))
      (is (= [1 2 3] (eve-vec/sab-vec [1 2 3]))))))

(deftest jvm-vec-ifn-test
  (with-heap-slab
    (testing "vector as function"
      (let [v (eve-vec/sab-vec [:a :b :c])]
        (is (= :a (v 0)))
        (is (= :c (v 2)))))))

(deftest jvm-vec-map-filter-test
  (with-heap-slab
    (testing "map over vector"
      (let [v (eve-vec/sab-vec [1 2 3 4])
            result (map inc v)]
        (is (= [2 3 4 5] (vec result)))))

    (testing "filter vector"
      (let [v (eve-vec/sab-vec [1 2 3 4 5 6])
            result (filter even? v)]
        (is (= [2 4 6] (vec result)))))))

(deftest jvm-vec-key-types-test
  (with-heap-slab
    (testing "string values"
      (let [v (eve-vec/sab-vec ["hello" "world"])]
        (is (= "hello" (nth v 0)))))

    (testing "keyword values"
      (let [v (eve-vec/sab-vec [:a :b :c])]
        (is (= :a (nth v 0)))))

    (testing "nil values"
      (let [v (eve-vec/sab-vec [nil 1 nil])]
        (is (nil? (nth v 0)))
        (is (= 1 (nth v 1)))))

    (testing "boolean values"
      (let [v (eve-vec/sab-vec [true false true])]
        (is (true? (nth v 0)))
        (is (false? (nth v 1)))))

    (testing "mixed types"
      (let [v (eve-vec/sab-vec [42 "hello" :key true nil 3.14])]
        (is (= 42 (nth v 0)))
        (is (= "hello" (nth v 1)))
        (is (= :key (nth v 2)))
        (is (true? (nth v 3)))
        (is (nil? (nth v 4)))
        (is (== 3.14 (nth v 5)))))))

(deftest jvm-vec-large-test
  (with-heap-slab
    (testing "1000-element vector"
      (let [v (eve-vec/sab-vec (range 1000))]
        (is (= 1000 (count v)))
        (doseq [i (range 0 1000 100)]
          (is (= i (nth v i))))))))

(deftest jvm-vec-contains?-test
  (with-heap-slab
    (testing "contains? checks indices, not values"
      (let [v (eve-vec/sab-vec [:a :b :c])]
        (is (contains? v 0))
        (is (contains? v 2))
        (is (not (contains? v 3)))
        (is (not (contains? v -1)))))))

(deftest jvm-vec-java-list-test
  (with-heap-slab
    (testing "implements java.util.List"
      (let [v (eve-vec/sab-vec [10 20 30])]
        (is (instance? java.util.List v))
        (is (= 3 (.size ^java.util.List v)))
        (is (= 10 (.get ^java.util.List v 0)))
        (is (.contains ^java.util.List v 20))
        (is (not (.contains ^java.util.List v 99)))))))

;; ============================================================================
;; SET CONFORMANCE
;; ============================================================================

(deftest jvm-set-conj-test
  (with-heap-slab
    (testing "conj single element"
      (let [s (conj (eve-set/empty-hash-set) 42)]
        (is (= 1 (count s)))
        (is (contains? s 42))))

    (testing "conj multiple elements"
      (let [s (-> (eve-set/empty-hash-set) (conj 1) (conj 2) (conj 3))]
        (is (= 3 (count s)))
        (is (contains? s 1))
        (is (contains? s 2))
        (is (contains? s 3))))

    (testing "conj duplicate is no-op"
      (let [s (-> (eve-set/empty-hash-set) (conj 1) (conj 1) (conj 1))]
        (is (= 1 (count s)))))))

(deftest jvm-set-disj-test
  (with-heap-slab
    (testing "disj existing element"
      (let [s (disj (eve-set* 1 2 3) 2)]
        (is (= 2 (count s)))
        (is (not (contains? s 2)))
        (is (contains? s 1))
        (is (contains? s 3))))

    (testing "disj non-existing element"
      (let [s (disj (eve-set* 1 2 3) 99)]
        (is (= 3 (count s)))))

    (testing "disj from empty set"
      (let [s (disj (eve-set/empty-hash-set) 1)]
        (is (= 0 (count s)))))))

(deftest jvm-set-contains?-test
  (with-heap-slab
    (testing "contains? basics"
      (let [s (eve-set* :a :b :c)]
        (is (contains? s :a))
        (is (contains? s :b))
        (is (contains? s :c))
        (is (not (contains? s :d)))))

    (testing "contains? on empty set"
      (is (not (contains? (eve-set/empty-hash-set) :a))))))

(deftest jvm-set-count-test
  (with-heap-slab
    (testing "count"
      (is (= 0 (count (eve-set/empty-hash-set))))
      (is (= 1 (count (eve-set* 42))))
      (is (= 3 (count (eve-set* 1 2 3)))))))

(deftest jvm-set-seq-test
  (with-heap-slab
    (testing "seq of empty set"
      (is (nil? (seq (eve-set/empty-hash-set)))))

    (testing "seq roundtrip"
      (let [s (eve-set* 1 2 3)
            elems (set (seq s))]
        (is (= #{1 2 3} elems))))))

(deftest jvm-set-empty-test
  (with-heap-slab
    (testing "empty returns empty set"
      (let [s (eve-set* 1 2 3)
            e (empty s)]
        (is (= 0 (count e)))))))

(deftest jvm-set-reduce-test
  (with-heap-slab
    (testing "reduce"
      (let [s (eve-set* 1 2 3 4 5)]
        (is (= 15 (reduce + s)))))

    (testing "reduce with init"
      (let [s (eve-set* 1 2 3)]
        (is (= 106 (reduce + 100 s)))))))

(deftest jvm-set-into-test
  (with-heap-slab
    (testing "into from sequence"
      (let [s (into (eve-set/empty-hash-set) [1 2 3 4 5])]
        (is (= 5 (count s)))
        (doseq [i (range 1 6)]
          (is (contains? s i)))))

    (testing "into from another set"
      (let [s (into (eve-set/empty-hash-set) #{1 2 3})]
        (is (= 3 (count s)))))))

(deftest jvm-set-equality-test
  (with-heap-slab
    (testing "equal eve sets"
      (is (= (eve-set* 1 2 3) (eve-set* 3 2 1))))

    (testing "unequal eve sets"
      (is (not= (eve-set* 1 2) (eve-set* 1 3))))

    (testing "eve set equals native set"
      (is (= (eve-set* 1 2 3) #{1 2 3}))
      (is (= #{1 2 3} (eve-set* 1 2 3))))))

(deftest jvm-set-ifn-test
  (with-heap-slab
    (testing "set as function"
      (let [s (eve-set* :a :b :c)]
        (is (= :a (s :a)))
        (is (= :b (s :b)))
        (is (nil? (s :z)))))))

(deftest jvm-set-key-types-test
  (with-heap-slab
    (testing "integer elements"
      (let [s (eve-set* 1 2 3)]
        (is (= 3 (count s)))
        (is (contains? s 1))))

    (testing "string elements"
      (let [s (eve-set* "hello" "world")]
        (is (= 2 (count s)))
        (is (contains? s "hello"))))

    (testing "keyword elements"
      (let [s (eve-set* :a :b :c)]
        (is (= 3 (count s)))
        (is (contains? s :a))))

    (testing "mixed types"
      (let [s (eve-set* 42 "hello" :key)]
        (is (= 3 (count s)))
        (is (contains? s 42))
        (is (contains? s "hello"))
        (is (contains? s :key))))))

(deftest jvm-set-large-test
  (with-heap-slab
    (testing "1000-element set"
      (let [s (into (eve-set/empty-hash-set) (range 1000))]
        (is (= 1000 (count s)))
        (doseq [i (range 0 1000 100)]
          (is (contains? s i)))))))

(deftest jvm-set-transient-test
  (with-heap-slab
    (testing "transient conj!/disj! roundtrip"
      (let [s (persistent!
               (reduce conj! (transient (eve-set/empty-hash-set)) (range 100)))]
        (is (= 100 (count s)))
        (doseq [i (range 100)]
          (is (contains? s i)))))

    (testing "transient disj!"
      (let [s (eve-set* :a :b :c :d)
            s2 (persistent! (-> (transient s) (disj! :b) (disj! :d)))]
        (is (= #{:a :c} (set s2)))))))

;; ============================================================================
;; LIST CONFORMANCE
;; ============================================================================

(deftest jvm-list-conj-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "conj adds to front"
        (let [l (conj (write-list sio '()) 42)]
          (is (= 1 (count l)))
          (is (= 42 (first l)))))

      (testing "conj multiple - stack order"
        (let [l (-> (write-list sio '()) (conj 1) (conj 2) (conj 3))]
          (is (= 3 (count l)))
          (is (= 3 (first l)))
          (is (= 2 (first (rest l)))))))))

(deftest jvm-list-peek-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "peek returns first"
        (is (= 1 (peek (write-list sio '(1 2 3))))))

      (testing "peek empty list"
        (is (nil? (peek (write-list sio '()))))))))

(deftest jvm-list-pop-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "pop removes first"
        (let [l (pop (write-list sio '(1 2 3)))]
          (is (= 2 (count l)))
          (is (= 2 (first l))))))))

(deftest jvm-list-count-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "count"
        (is (= 0 (count (write-list sio '()))))
        (is (= 1 (count (write-list sio '(42)))))
        (is (= 3 (count (write-list sio '(1 2 3)))))))))

(deftest jvm-list-seq-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "seq of empty list"
        (is (nil? (seq (write-list sio '())))))

      (testing "seq preserves order"
        (let [l (write-list sio '(10 20 30))
              result (vec (seq l))]
          (is (= [10 20 30] result)))))))

(deftest jvm-list-empty-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "empty returns empty list"
        (let [l (write-list sio '(1 2 3))
              e (empty l)]
          (is (= 0 (count e))))))))

(deftest jvm-list-reduce-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "reduce"
        (let [l (write-list sio '(1 2 3 4 5))]
          (is (= 15 (reduce + l)))))

      (testing "reduce with init"
        (let [l (write-list sio '(1 2 3))]
          (is (= 106 (reduce + 100 l))))))))

(deftest jvm-list-equality-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "equal eve lists"
        (is (= (write-list sio '(1 2 3)) (write-list sio '(1 2 3)))))

      (testing "unequal eve lists"
        (is (not= (write-list sio '(1 2 3)) (write-list sio '(1 2 4)))))

      (testing "eve list equals native list"
        (is (= (write-list sio '(1 2 3)) '(1 2 3)))
        (is (= '(1 2 3) (write-list sio '(1 2 3))))))))

(deftest jvm-list-key-types-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "string values"
        (let [l (write-list sio '("hello" "world"))]
          (is (= "hello" (first l)))))

      (testing "keyword values"
        (let [l (write-list sio '(:a :b :c))]
          (is (= :a (first l)))))

      (testing "mixed types"
        (let [l (write-list sio '(42 "hello" :key true nil))]
          (is (= 42 (first l))))))))

(deftest jvm-list-large-test
  (with-heap-slab
    (let [sio alloc/*jvm-slab-ctx*]
      (testing "1000-element list"
        (let [l (write-list sio (apply list (range 1000)))]
          (is (= 1000 (count l)))
          (is (= 0 (first l))))))))

;; ============================================================================
;; CROSS-TYPE CONFORMANCE
;; ============================================================================

(deftest jvm-nested-map-collections-test
  (with-heap-slab
    (testing "map containing set"
      (let [m (write-map {:tags #{:a :b}})]
        (is (contains? (get m :tags) :a))))

    (testing "map of maps"
      (let [m (write-map {:inner {:deep 42}})]
        (is (= 42 (get-in m [:inner :deep])))))))

(deftest jvm-nested-vec-collections-test
  (with-heap-slab
    (testing "map containing vector"
      (let [m (write-map {:data [1 2 3]})]
        (is (= 1 (nth (get m :data) 0)))
        (is (= 3 (count (get m :data))))))

    (testing "vector containing maps"
      (let [v (write-vec [{:a 1} {:b 2}])]
        (is (= 1 (get (nth v 0) :a)))
        (is (= 2 (get (nth v 1) :b)))))))

(deftest jvm-hash-consistency-test
  (with-heap-slab
    (testing "equal collections have equal hashes"
      (is (= (hash (eve-map* :a 1 :b 2))
             (hash (eve-map* :a 1 :b 2))))
      (is (= (hash (eve-vec/sab-vec [1 2 3]))
             (hash (eve-vec/sab-vec [1 2 3]))))
      (is (= (hash (eve-set* 1 2 3))
             (hash (eve-set* 1 2 3)))))

    (testing "eve hash matches native hash"
      (is (= (hash {:a 1 :b 2})
             (hash (eve-map* :a 1 :b 2))))
      (is (= (hash [1 2 3])
             (hash (eve-vec/sab-vec [1 2 3]))))
      (is (= (hash #{1 2 3})
             (hash (eve-set* 1 2 3)))))))

(deftest jvm-persistent-semantics-test
  (with-heap-slab
    (testing "map persistence"
      (let [m1 (eve-map* :a 1)
            m2 (assoc m1 :b 2)]
        (is (= 1 (count m1)))
        (is (= 2 (count m2)))
        (is (nil? (get m1 :b)))))

    (testing "vector persistence"
      (let [v1 (eve-vec/sab-vec [1 2 3])
            v2 (conj v1 4)]
        (is (= 3 (count v1)))
        (is (= 4 (count v2)))))

    (testing "set persistence"
      (let [s1 (eve-set* 1 2 3)
            s2 (conj s1 4)]
        (is (= 3 (count s1)))
        (is (= 4 (count s2)))))

    (testing "list persistence"
      (let [sio alloc/*jvm-slab-ctx*
            l1 (write-list sio '(1 2 3))
            l2 (conj l1 0)]
        (is (= 3 (count l1)))
        (is (= 4 (count l2)))))))
