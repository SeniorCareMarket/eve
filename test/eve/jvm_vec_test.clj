(ns eve.jvm-vec-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.mem :as mem]
            [eve.map  :as eve-map]
            [eve.vec  :as eve-vec]
            [eve.set  :as eve-set]
            [eve.list :as eve-list]))

(defmacro with-heap-slab [& body]
  `(let [ctx# (alloc/make-jvm-heap-slab-ctx {})]
     (binding [alloc/*jvm-slab-ctx* ctx#]
       ~@body)))

;;; Recursive collection factory — dispatches on SAB tag to the correct constructor.
;;; Passed as coll-factory when reading back nested collections.
(defn coll-factory [tag sio off]
  (case (int tag)
    0x10 (eve-map/jvm-eve-hash-map-from-offset sio off coll-factory)
    0x11 (eve-set/jvm-eve-hash-set-from-offset sio off coll-factory)
    0x12 (eve-vec/jvm-sabvec-from-offset       sio off coll-factory)
    0x13 (eve-list/jvm-sab-list-from-offset    sio off coll-factory)))

(deftest empty-vec-roundtrip
  (testing "empty vector"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= [] (vec v)))
        (is (zero? (count v)))))))

(deftest single-element-vec
  (testing "vector with one element"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [42])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= [42] (vec v)))
        (is (= 1 (count v)))
        (is (= 42 (nth v 0)))))))

(deftest primitive-vec-roundtrip
  (testing "vector with mixed primitives"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src [1 "hello" :kw true nil]
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= src (vec v)))
        (is (= 5 (count v)))
        (is (= 1     (nth v 0)))
        (is (= "hello" (nth v 1)))
        (is (= :kw   (nth v 2)))))))

(deftest vec-nth
  (testing "nth access by index"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src [:a :b :c :d :e]
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (doseq [i (range (count src))]
          (is (= (nth src i) (nth v i))))))))

(deftest vec-reduce
  (testing "reduce visits all elements in order"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src [10 20 30 40 50]
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            result (reduce conj [] v)]
        (is (= src result))))))

(deftest large-vec-roundtrip
  (testing "vector with 50 elements"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (vec (range 50))
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= src (vec v)))
        (is (= 50 (count v)))))))

(deftest nested-vec-with-map-elements
  (testing "vector containing maps — exercises value+sio->eve-bytes nested path"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src [{:a 1} {:b 2} {:c 3}]
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr coll-factory)]
        (is (= src (vec v)))))))

;; ---------------------------------------------------------------------------
;; Phase 2b: Native conj / assocN / pop tests
;; ---------------------------------------------------------------------------

(deftest conj-returns-sabvecroot
  (testing "conj returns SabVecRoot, not PersistentVector"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [1 2 3])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (conj v 4)]
        (is (instance? eve.vec.SabVecRoot v2))
        (is (= [1 2 3 4] (vec v2)))
        (is (= 4 (count v2)))))))

(deftest conj-past-node-size
  (testing "conj past NODE_SIZE triggers tail push + tree growth"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (reduce conj v (range 100))]
        (is (instance? eve.vec.SabVecRoot v2))
        (is (= 100 (count v2)))
        (doseq [i (range 100)]
          (is (= i (nth v2 i)) (str "nth " i)))))))

(deftest assocn-returns-sabvecroot
  (testing "assocN returns SabVecRoot"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [10 20 30])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (assoc v 1 99)]
        (is (instance? eve.vec.SabVecRoot v2))
        (is (= [10 99 30] (vec v2)))))))

(deftest assocn-append
  (testing "assocN at count acts as conj"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [1 2])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (assoc v 2 3)]
        (is (= [1 2 3] (vec v2)))))))

(deftest pop-returns-sabvecroot
  (testing "pop returns SabVecRoot"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [1 2 3])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (pop v)]
        (is (instance? eve.vec.SabVecRoot v2))
        (is (= [1 2] (vec v2)))
        (is (= 2 (count v2)))))))

(deftest pop-to-empty
  (testing "pop to single element then to empty"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [42])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (pop v)]
        (is (zero? (count v2)))
        (is (= [] (vec v2)))))))

(deftest conj-pop-roundtrip
  (testing "build with conj, shrink with pop"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (reduce conj v (range 70))
            v3  (nth (iterate pop v2) 30)]
        (is (= 40 (count v3)))
        (is (= (vec (range 40)) (vec v3)))))))

;; ---------------------------------------------------------------------------
;; Phase 3: IFn tests
;; ---------------------------------------------------------------------------

(deftest vec-ifn
  (testing "SabVecRoot implements IFn"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-vec/jvm-write-vec! sio (partial mem/value+sio->eve-bytes sio) [10 20 30])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= 10 (v 0)))
        (is (= 20 (v 1)))
        (is (= 30 (v 2)))
        (is (= :nf (v 5 :nf)))))))
