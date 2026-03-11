(ns eve.jvm-list-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.mem :as mem]
            [eve.list :as eve-list]))

(defmacro with-heap-slab [& body]
  `(let [ctx# (alloc/make-jvm-heap-slab-ctx {})]
     (binding [alloc/*jvm-slab-ctx* ctx#]
       ~@body)))

(deftest empty-list-roundtrip
  (testing "empty list"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) '())
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (zero? (count l)))
        (is (nil? (seq l)))))))

(deftest single-element-list
  (testing "list with one element"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) '(42))
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= 1 (count l)))
        (is (= 42 (first l)))))))

(deftest primitive-list-roundtrip
  (testing "list with mixed primitives"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src '(1 "hello" :kw true nil)
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= src (seq l)))
        (is (= 5 (count l)))))))

(deftest list-peek
  (testing "peek returns first element"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src '(:a :b :c)
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= :a (peek l)))))))

(deftest list-seq-order
  (testing "seq preserves insertion order"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src '(10 20 30 40 50)
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= src (seq l)))))))

(deftest large-list-roundtrip
  (testing "list with 50 elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (apply list (range 50))
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= src (seq l)))
        (is (= 50 (count l)))))))

;; ---------------------------------------------------------------------------
;; Phase 5a: IReduceInit / IReduce tests
;; ---------------------------------------------------------------------------

(deftest list-reduce-init
  (testing "reduce with init collects all elements in order"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src '(10 20 30 40 50)
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)
            result (reduce conj [] l)]
        (is (= [10 20 30 40 50] result))))))

(deftest list-reduce-no-init
  (testing "reduce without init sums elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src '(1 2 3 4 5)
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= 15 (reduce + l)))))))

(deftest list-reduce-early-termination
  (testing "reduce with reduced stops early"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (apply list (range 50))
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)
            result (reduce (fn [acc x] (if (>= (count acc) 5) (reduced acc) (conj acc x)))
                           [] l)]
        (is (= 5 (count result)))))))

(deftest list-reduce-empty
  (testing "reduce on empty list"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) '())
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= 0 (reduce + 0 l)))
        (is (= 0 (reduce + l)))))))

;; ---------------------------------------------------------------------------
;; Phase 6b: ISeq tests
;; ---------------------------------------------------------------------------

(deftest list-first
  (testing "first returns head element"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) '(:a :b :c))
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= :a (first l)))))))

(deftest list-next
  (testing "next returns rest of list"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) '(1 2 3))
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (instance? eve.list.SabList (next l)))
        (is (= '(2 3) (seq (next l))))
        (is (= '(3) (seq (next (next l)))))
        (is (nil? (next (next (next l)))))))))

(deftest list-rest
  (testing "rest returns rest or empty list"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-list/jvm-write-list! sio (partial mem/value+sio->eve-bytes sio) '(42))
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (instance? eve.list.SabList (rest l)))
        (is (zero? (count (rest l))))))))
