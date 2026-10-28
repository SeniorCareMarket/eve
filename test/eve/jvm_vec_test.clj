(ns eve.jvm-vec-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.codec :as codec]
            [eve.map  :as eve-map]
            [eve.vec  :as eve-vec]
            [eve.set  :as eve-set]
            [eve.list :as eve-list]))

(defmacro with-heap-slab [& body]
  `(let [~'sio (alloc/make-jvm-heap-slab-ctx {})]
     ~@body))

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
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= [] (vec v)))
        (is (zero? (count v)))))))

(deftest single-element-vec
  (testing "vector with one element"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [42])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= [42] (vec v)))
        (is (= 1 (count v)))
        (is (= 42 (nth v 0)))))))

(deftest primitive-vec-roundtrip
  (testing "vector with mixed primitives"
    (with-heap-slab
      (let [            src [1 "hello" :kw true nil]
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= src (vec v)))
        (is (= 5 (count v)))
        (is (= 1     (nth v 0)))
        (is (= "hello" (nth v 1)))
        (is (= :kw   (nth v 2)))))))

(deftest vec-nth
  (testing "nth access by index"
    (with-heap-slab
      (let [            src [:a :b :c :d :e]
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (doseq [i (range (count src))]
          (is (= (nth src i) (nth v i))))))))

(deftest vec-reduce
  (testing "reduce visits all elements in order"
    (with-heap-slab
      (let [            src [10 20 30 40 50]
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            result (reduce conj [] v)]
        (is (= src result))))))

(deftest large-vec-roundtrip
  (testing "vector with 50 elements"
    (with-heap-slab
      (let [            src (vec (range 50))
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= src (vec v)))
        (is (= 50 (count v)))))))

(deftest nested-vec-with-map-elements
  (testing "vector containing maps — exercises value+sio->eve-bytes nested path"
    (with-heap-slab
      (let [            src [{:a 1} {:b 2} {:c 3}]
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr coll-factory)]
        (is (= src (vec v)))))))

;; ---------------------------------------------------------------------------
;; Phase 2b: Native conj / assocN / pop tests
;; ---------------------------------------------------------------------------

(deftest conj-returns-sabvecroot
  (testing "conj returns SabVecRoot, not PersistentVector"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [1 2 3])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (conj v 4)]
        (is (instance? eve.vec.EveVector v2))
        (is (= [1 2 3 4] (vec v2)))
        (is (= 4 (count v2)))))))

(deftest conj-past-node-size
  (testing "conj past NODE_SIZE triggers tail push + tree growth"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (reduce conj v (range 100))]
        (is (instance? eve.vec.EveVector v2))
        (is (= 100 (count v2)))
        (doseq [i (range 100)]
          (is (= i (nth v2 i)) (str "nth " i)))))))

(deftest assocn-returns-sabvecroot
  (testing "assocN returns SabVecRoot"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [10 20 30])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (assoc v 1 99)]
        (is (instance? eve.vec.EveVector v2))
        (is (= [10 99 30] (vec v2)))))))

(deftest assocn-append
  (testing "assocN at count acts as conj"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [1 2])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (assoc v 2 3)]
        (is (= [1 2 3] (vec v2)))))))

(deftest pop-returns-sabvecroot
  (testing "pop returns SabVecRoot"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [1 2 3])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (pop v)]
        (is (instance? eve.vec.EveVector v2))
        (is (= [1 2] (vec v2)))
        (is (= 2 (count v2)))))))

(deftest pop-to-empty
  (testing "pop to single element then to empty"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [42])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            v2  (pop v)]
        (is (zero? (count v2)))
        (is (= [] (vec v2)))))))

(deftest conj-pop-roundtrip
  (testing "build with conj, shrink with pop"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [])
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
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [10 20 30])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= 10 (v 0)))
        (is (= 20 (v 1)))
        (is (= 30 (v 2)))
        (is (= :nf (v 5 :nf)))))))

;; ---------------------------------------------------------------------------
;; Phase 4: IHashEq + print-method tests
;; ---------------------------------------------------------------------------

(deftest vec-hasheq
  (testing "hash of SabVecRoot equals hash of equivalent Clojure vector"
    (with-heap-slab
      (let [            src [1 2 3 4 5]
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= (hash src) (hash v)))))))

(deftest vec-print-method
  (testing "pr-str prints as vector literal"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [1 2 3])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= "[1 2 3]" (pr-str v)))))))

;; ---------------------------------------------------------------------------
;; Phase 5a: IReduceInit / IReduce tests
;; ---------------------------------------------------------------------------

(deftest vec-reduce-init
  (testing "reduce with init collects all elements in order"
    (with-heap-slab
      (let [            src [10 20 30 40 50]
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            result (reduce conj [] v)]
        (is (= src result))))))

(deftest vec-reduce-no-init
  (testing "reduce without init sums elements"
    (with-heap-slab
      (let [            src [1 2 3 4 5]
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= 15 (reduce + v)))))))

(deftest vec-reduce-early-termination
  (testing "reduce with reduced stops early"
    (with-heap-slab
      (let [            src (vec (range 100))
            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) src)
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)
            result (reduce (fn [acc x] (if (>= acc 10) (reduced acc) (+ acc x)))
                           0 v)]
        (is (= 10 result))))))

(deftest vec-reduce-empty
  (testing "reduce on empty vec"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (= 0 (reduce + 0 v)))
        (is (= 0 (reduce + v)))))))

;; ---------------------------------------------------------------------------
;; Phase 6c: java.util.List tests
;; ---------------------------------------------------------------------------

(deftest vec-is-java-list
  (testing "SabVecRoot implements java.util.List"
    (with-heap-slab
      (let [            hdr (eve-vec/jvm-write-vec! sio (partial codec/value+sio->eve-bytes sio) [10 20 30])
            v   (eve-vec/jvm-sabvec-from-offset sio hdr)]
        (is (instance? java.util.List v))
        (is (= 3 (.size ^java.util.List v)))
        (is (= 10 (.get ^java.util.List v 0)))
        (is (= 30 (.get ^java.util.List v 2)))
        (is (.contains ^java.util.List v 20))
        (is (not (.contains ^java.util.List v 99)))
        (is (= 1 (.indexOf ^java.util.List v 20)))
        (is (= -1 (.indexOf ^java.util.List v 99)))))))

;; ---------------------------------------------------------------------------
;; Fixed 2026-10-16 (found porting eve to ClojureCLR): each threw before
;; ---------------------------------------------------------------------------

(deftest vec-nil-element-equals-and-index-of
  (testing "Object.equals and List.indexOf take a nil element (NullPointerException before)"
    (let [v (into (eve-vec/empty-vec) [1 nil 3])]
      (is (.equals ^Object v [1 nil 3]))
      (is (not (.equals ^Object v [1 2 3])))
      (is (= 1 (.indexOf ^java.util.List v nil)))
      (is (= 2 (.indexOf ^java.util.List v 3)))
      (is (= 1 (.lastIndexOf ^java.util.List v nil))))))

(deftest vec-rseq
  (testing "rseq walks the vector backwards (AbstractMethodError before)"
    (let [v (into (eve-vec/empty-vec) (range 40))]
      (is (= (reverse (range 40)) (rseq v)))
      (is (nil? (rseq (eve-vec/empty-vec)))))))

(deftest vec-transient
  (testing "transient, conj!, assoc!, pop!, persistent! (ClassCastException before)"
    (let [v (into (eve-vec/empty-vec) [1 2 3])
          p (-> (transient v) (conj! 4) (assoc! 0 :a) (pop!) (conj! 5) persistent!)]
      (is (= [:a 2 3 5] p))
      (is (= [1 2 3] v)))))

(deftest set-dispose
  (testing "dispose! frees a set's tree and header (it called the set's slab I/O as a fn before)"
    (with-heap-slab
      (let [s (eve-set/hash-set sio 1 2 3)]
        (is (= :freed (do (eve-set/dispose! s) :freed)))))))
