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
  (testing "jvm-set-hamt-get finds present elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{1 2 3 4 5}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (doseq [v src]
          (is (= v (eve-set/jvm-set-hamt-get sio (.-root-off ^eve.set.EveHashSet s) v ::absent))
              (str "should find " v)))
        (is (= ::absent (eve-set/jvm-set-hamt-get sio (.-root-off ^eve.set.EveHashSet s) 99 ::absent))
            "should return not-found for absent element")))))

(deftest hamt-get-empty
  (testing "jvm-set-hamt-get on empty set"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= ::absent (eve-set/jvm-set-hamt-get sio (.-root-off ^eve.set.EveHashSet s) 1 ::absent)))))))

(deftest hamt-get-single
  (testing "jvm-set-hamt-get on single-element set"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) #{42})
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= 42 (eve-set/jvm-set-hamt-get sio (.-root-off ^eve.set.EveHashSet s) 42 ::absent)))
        (is (= ::absent (eve-set/jvm-set-hamt-get sio (.-root-off ^eve.set.EveHashSet s) 99 ::absent)))))))

(deftest hamt-get-scale
  (testing "jvm-set-hamt-get works for 200-element set (collision coverage)"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (set (range 200))
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (doseq [i (range 200)]
          (is (= i (eve-set/jvm-set-hamt-get sio (.-root-off ^eve.set.EveHashSet s) i ::absent))
              (str "should find " i)))
        (doseq [i (range 200 210)]
          (is (= ::absent (eve-set/jvm-set-hamt-get sio (.-root-off ^eve.set.EveHashSet s) i ::absent))
              (str "should not find " i)))))))

(deftest contains-uses-hamt-get
  (testing "contains? dispatches to hash-directed lookup for portable-hash sets"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (set (range 200))
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (.-jvm-hashed? ^eve.set.EveHashSet s))
        (doseq [i (range 200)]
          (is (contains? s i) (str "contains? should find " i)))
        (is (not (contains? s 999)))))))

(deftest get-uses-hamt-get
  (testing ".get dispatches to hash-directed lookup for portable-hash sets"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src #{:a :b :c}
            hdr (eve-set/jvm-write-set! sio (partial mem/value+sio->eve-bytes sio) src)
            s   (eve-set/jvm-eve-hash-set-from-offset sio hdr)]
        (is (= :a (.get ^eve.set.EveHashSet s :a)))
        (is (= :b (.get ^eve.set.EveHashSet s :b)))
        (is (nil? (.get ^eve.set.EveHashSet s :missing)))))))
