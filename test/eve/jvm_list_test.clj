(ns eve.jvm-list-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.codec :as codec]
            [eve.deftype-proto.data :as d]
            [eve.list :as eve-list]))

(defmacro with-heap-slab [& body]
  `(let [~'sio (alloc/make-jvm-heap-slab-ctx {})]
     ~@body))

(deftest empty-list-roundtrip
  (testing "empty list"
    (with-heap-slab
      (let [hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) '())
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (zero? (count l)))
        (is (nil? (seq l)))))))

(deftest single-element-list
  (testing "list with one element"
    (with-heap-slab
      (let [hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) '(42))
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= 1 (count l)))
        (is (= 42 (first l)))))))

(deftest primitive-list-roundtrip
  (testing "list with mixed primitives"
    (with-heap-slab
      (let [src '(1 "hello" :kw true nil)
            hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= src (seq l)))
        (is (= 5 (count l)))))))

(deftest list-peek
  (testing "peek returns first element"
    (with-heap-slab
      (let [src '(:a :b :c)
            hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= :a (peek l)))))))

(deftest list-seq-order
  (testing "seq preserves insertion order"
    (with-heap-slab
      (let [src '(10 20 30 40 50)
            hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= src (seq l)))))))

(deftest large-list-roundtrip
  (testing "list with 50 elements"
    (with-heap-slab
      (let [src (apply list (range 50))
            hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= src (seq l)))
        (is (= 50 (count l)))))))

;; ---------------------------------------------------------------------------
;; Phase 5a: IReduceInit / IReduce tests
;; ---------------------------------------------------------------------------

(deftest list-reduce-init
  (testing "reduce with init collects all elements in order"
    (with-heap-slab
      (let [src '(10 20 30 40 50)
            hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)
            result (reduce conj [] l)]
        (is (= [10 20 30 40 50] result))))))

(deftest list-reduce-no-init
  (testing "reduce without init sums elements"
    (with-heap-slab
      (let [src '(1 2 3 4 5)
            hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= 15 (reduce + l)))))))

(deftest list-reduce-early-termination
  (testing "reduce with reduced stops early"
    (with-heap-slab
      (let [src (apply list (range 50))
            hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) src)
            l   (eve-list/jvm-sab-list-from-offset sio hdr)
            result (reduce (fn [acc x] (if (>= (count acc) 5) (reduced acc) (conj acc x)))
                           [] l)]
        (is (= 5 (count result)))))))

(deftest list-reduce-empty
  (testing "reduce on empty list"
    (with-heap-slab
      (let [hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) '())
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (zero? (reduce + 0 l)))
        (is (zero? (reduce + l)))))))

;; ---------------------------------------------------------------------------
;; Phase 6b: ISeq tests
;; ---------------------------------------------------------------------------

(deftest list-first
  (testing "first returns head element"
    (with-heap-slab
      (let [hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) '(:a :b :c))
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        (is (= :a (first l)))))))

(deftest list-next
  (testing "next returns rest of list"
    (with-heap-slab
      (let [hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) '(1 2 3))
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        ;; L1 disposition (2026-11-12, see doc/flat/L1-STATUS.md): this
        ;; deftest used to assert (instance? eve.list.EveList (next l)).
        ;; An EveList value is only (sio, header-offset) — its cnt/head
        ;; exist solely as bytes in a slab header block — so next could
        ;; answer an EveList only by minting a fresh header per step:
        ;; the representation the assertion pinned WAS the L1 defect
        ;; (197 domain blocks leaked over 4 walks of a 50-list, re-derived
        ;; at 7402ef9). next/more now walk lazily and allocate nothing;
        ;; the ISeq contract (values, order, termination) is asserted by
        ;; the seq assertions below, and EveList-ness of a tail is
        ;; asserted on pop — the update whose result is a first-class
        ;; EveList tail and which rightly pays for its header.
        (is (instance? eve.list.EveList (pop l)))
        (is (= '(2 3) (seq (pop l))))
        (is (= '(2 3) (seq (next l))))
        (is (= '(3) (seq (next (next l)))))
        (is (nil? (next (next (next l)))))))))

(deftest list-rest
  (testing "rest returns rest or empty list"
    (with-heap-slab
      (let [hdr (eve-list/jvm-write-list! sio (partial codec/value+sio->eve-bytes sio) '(42))
            l   (eve-list/jvm-sab-list-from-offset sio hdr)]
        ;; Same L1 disposition as list-next: (rest l) walks lazily and
        ;; allocates nothing, so it is a seq, not an EveList; the EveList
        ;; tail of a one-element list is (pop l), the empty EveList.
        (is (instance? eve.list.EveList (pop l)))
        (is (zero? (count (pop l))))
        (is (zero? (count (rest l))))))))

(deftest plain-data-keeps-lists
  (testing "eve->clj (an atom's value as plain data) keeps a list a list, an
            eve list or one nested in other data"
    (with-heap-slab
      (let [l (eve-list/eve-list sio '(1 2 3))]
        (is (list? (d/eve->clj l)))
        (is (= '(1 2 3) (d/eve->clj l)))
        (is (list? (:l (d/eve->clj {:l '(1 (2))}))))
        (is (list? (second (d/eve->clj '(1 (2))))))))))
