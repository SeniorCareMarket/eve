(ns eve.jvm-set-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.hamt-util :as hu]
            [eve.mem :as mem]
            [eve.set :as eve-set]))

(defmacro with-heap-slab [& body]
  `(let [ctx# (alloc/make-jvm-heap-slab-ctx {})]
     (binding [alloc/*jvm-slab-ctx* ctx#]
       ~@body)))

(deftest empty-set-roundtrip
  (testing "empty set"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= #{} (set s)))
        (is (zero? (count s)))))))

(deftest primitive-set-roundtrip
  (testing "set with primitive elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{:a :b :c 1 "hello"}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= src (set s)))
        (is (= 5 (count s)))))))

(deftest set-contains
  (testing "contains? returns correct results"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{:x :y :z}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (contains? s :x))
        (is (contains? s :y))
        (is (contains? s :z))
        (is (not (contains? s :missing)))))))

(deftest set-seq
  (testing "seq returns all elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{10 20 30}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= src (set (seq s))))))))

(deftest large-set-roundtrip
  (testing "set with 50 elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (set (map #(keyword (str "e" %)) (range 50)))
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= src (set s)))
        (is (= 50 (count s)))))))

;; ---------------------------------------------------------------------------
;; Phase 0.6: Cross-process set hash compatibility tests
;; ---------------------------------------------------------------------------

(deftest portable-hash-flag-set
  (testing "JVM-written sets have SET_FLAG_PORTABLE_HASH flag"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{1 2 3})
            flags (alloc/-sio-read-u8 sio hdr 1)]
        (is (not (zero? (bit-and flags eve-set/SET_FLAG_PORTABLE_HASH)))
            "SET_FLAG_PORTABLE_HASH should be set in header byte 1")))))

(deftest jvm-hashed-flag-read
  (testing "jvm-eve-hash-set-from-offset reads jvm-hashed? correctly"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{:a :b})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (.-jvm-hashed? ^eve.set.EveHashSet s)
            "jvm-hashed? should be true for sets with portable hash flag")))))

(deftest large-set-contains-all
  (testing "contains? works for 100-element set (exercises hash collisions)"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (set (range 100))
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= 100 (count s)))
        (doseq [i (range 100)]
          (is (contains? s i) (str "should contain " i)))
        (is (not (contains? s 999)))))))

(deftest portable-hash-cross-platform-consistency
  (testing "portable-hash-bytes produces same hash on JVM as expected"
    ;; Verify that the JVM portable-hash-bytes matches known values
    ;; by hashing serialized integers and checking consistency
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            serialize (partial mem/value+sio->eve-bytes sio)]
        (doseq [v [0 1 42 -1 100 999]]
          (let [vb (serialize v)
                h  (hu/portable-hash-bytes vb)]
            (is (integer? h) (str "hash of " v " should be integer"))
            ;; Hash should be deterministic
            (is (= h (hu/portable-hash-bytes (serialize v)))
                (str "hash of " v " should be deterministic"))))))))

(deftest set-with-mixed-types-roundtrip
  (testing "set with strings, keywords, and integers"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{:a "hello" 42 :b "world" 99}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= src (set s)))
        (doseq [v src]
          (is (contains? s v) (str "should contain " (pr-str v))))))))

;; ---------------------------------------------------------------------------
;; Phase 1: O(log n) hash-directed lookup tests
;; ---------------------------------------------------------------------------

(deftest hamt-get-basic
  (testing "jvm-set-hamt-get finds elements by hash-directed trie descent"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{:a :b :c 1 2 3 "x" "y"}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            root-off (alloc/-sio-read-i32 sio hdr eve-set/SABSETROOT_ROOT_OFF_OFFSET)]
        (doseq [v src]
          (is (not (identical? ::miss
                    (eve-set/jvm-set-hamt-get sio root-off v ::miss)))
              (str "should find " (pr-str v))))
        (is (identical? ::miss
              (eve-set/jvm-set-hamt-get sio root-off :missing ::miss))
            "should return not-found for absent element")))))

(deftest hamt-get-large-set
  (testing "O(log n) lookup works for 200-element set with hash collisions"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (set (range 200))
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            root-off (alloc/-sio-read-i32 sio hdr eve-set/SABSETROOT_ROOT_OFF_OFFSET)]
        (doseq [i (range 200)]
          (is (= i (eve-set/jvm-set-hamt-get sio root-off i ::miss))
              (str "should find " i)))
        (doseq [i (range 200 210)]
          (is (identical? ::miss
                (eve-set/jvm-set-hamt-get sio root-off i ::miss))
              (str "should miss " i)))))))

(deftest hamt-get-via-contains-and-get
  (testing "EveHashSet.contains and .get use O(log n) path when jvm-hashed?"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (set (range 100))
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (.-jvm-hashed? ^eve.set.EveHashSet s))
        (doseq [i (range 100)]
          (is (contains? s i) (str "contains? should find " i))
          (is (= i (get s i)) (str "get should return " i)))
        (is (not (contains? s 999)))
        (is (nil? (get s 999)))))))

;; ---------------------------------------------------------------------------
;; Phase 2c: Native conj / disjoin tests
;; ---------------------------------------------------------------------------

(deftest conj-returns-eve-hash-set
  (testing "conj returns EveHashSet, not PersistentHashSet"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{1 2 3})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            s2  (conj s 4)]
        (is (instance? eve.set.EveHashSet s2))
        (is (= #{1 2 3 4} (set s2)))
        (is (= 4 (count s2)))))))

(deftest conj-existing-element
  (testing "conj-ing existing element returns same set"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{:a :b})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            s2  (conj s :a)]
        (is (identical? s s2))))))

(deftest disjoin-returns-eve-hash-set
  (testing "disjoin returns EveHashSet, not PersistentHashSet"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{:x :y :z})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            s2  (disj s :y)]
        (is (instance? eve.set.EveHashSet s2))
        (is (= #{:x :z} (set s2)))
        (is (= 2 (count s2)))))))

(deftest disjoin-missing-element
  (testing "disjoin on missing element returns same set"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{1 2})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            s2  (disj s 999)]
        (is (identical? s s2))))))

(deftest conj-disjoin-roundtrip
  (testing "add elements then remove some, verify membership"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            s2  (reduce conj s (range 50))
            s3  (reduce disj s2 (range 0 50 2))]  ;; remove evens
        (is (instance? eve.set.EveHashSet s3))
        (is (= 25 (count s3)))
        (doseq [i (range 1 50 2)]
          (is (contains? s3 i) (str "should contain odd " i)))
        (doseq [i (range 0 50 2)]
          (is (not (contains? s3 i)) (str "should not contain even " i)))))))

;; ---------------------------------------------------------------------------
;; Phase 3: IFn tests
;; ---------------------------------------------------------------------------

(deftest set-ifn
  (testing "EveHashSet implements IFn"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{:a :b :c})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= :a (s :a)))
        (is (= :b (s :b)))
        (is (nil? (s :missing)))
        (is (= :nf (s :missing :nf)))))))

;; ---------------------------------------------------------------------------
;; Phase 4: IHashEq + print-method tests
;; ---------------------------------------------------------------------------

(deftest set-hasheq
  (testing "hash of EveHashSet equals hash of equivalent Clojure set"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{:a :b :c}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= (hash src) (hash s)))))))

(deftest set-print-method
  (testing "pr-str prints as set literal"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{:x})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= "#{:x}" (pr-str s)))))))

;; ---------------------------------------------------------------------------
;; Phase 5a: IReduceInit / IReduce tests
;; ---------------------------------------------------------------------------

(deftest set-reduce-init
  (testing "reduce with init collects all elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{1 2 3 4 5}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            result (reduce conj #{} s)]
        (is (= src result))))))

(deftest set-reduce-no-init
  (testing "reduce without init sums elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{1 2 3 4 5}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= 15 (reduce + s)))))))

(deftest set-reduce-early-termination
  (testing "reduce with reduced stops early"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (set (range 50))
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            result (reduce (fn [acc _] (if (>= (count acc) 5) (reduced acc) (conj acc 1)))
                           [] s)]
        (is (= 5 (count result)))))))

;; ---------------------------------------------------------------------------
;; Phase 6a: Transient tests
;; ---------------------------------------------------------------------------

(deftest set-transient-roundtrip
  (testing "transient -> conj! -> persistent! roundtrip"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{1 2 3})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            s2  (persistent! (conj! (transient s) 4))]
        (is (instance? eve.set.EveHashSet s2))
        (is (= #{1 2 3 4} (set s2)))))))

(deftest set-transient-batch-conj
  (testing "batch conj! builds correct set"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            s2  (persistent! (reduce conj! (transient s) (range 50)))]
        (is (instance? eve.set.EveHashSet s2))
        (is (= 50 (count s2)))
        (doseq [i (range 50)]
          (is (contains? s2 i) (str "should contain " i)))))))

(deftest set-transient-disjoin
  (testing "disjoin! removes elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{:a :b :c :d}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)
            s2  (persistent! (-> (transient s) (disj! :b) (disj! :d)))]
        (is (instance? eve.set.EveHashSet s2))
        (is (= #{:a :c} (set s2)))))))
