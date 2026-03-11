(ns eve.jvm-map-test
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

(deftest empty-map-roundtrip
  (testing "empty map"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) {})
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)]
        (is (= {} (into {} m)))
        (is (zero? (count m)))))))

(deftest primitive-kv-roundtrip
  (testing "map with primitive keys and values"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src {:a 1 :b "hello" :c true :d nil}
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)]
        (is (= src (into {} m)))
        (is (= 4 (count m)))
        (is (= 1 (get m :a)))
        (is (= "hello" (get m :b)))))))

(deftest keyword-lookup
  (testing "get returns correct value or default"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) {:x 42})
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)]
        (is (= 42 (get m :x)))
        (is (nil? (get m :missing)))
        (is (= :default (get m :missing :default)))))))

(deftest map-reduce
  (testing "kvreduce visits all entries"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src {:a 1 :b 2 :c 3}
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)
            result (reduce-kv (fn [acc k v] (assoc acc k v)) {} m)]
        (is (= src result))))))

(deftest large-map-roundtrip
  (testing "map with 100 entries"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 100)))
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)]
        (is (= src (into {} m)))))))

(deftest map-seq
  (testing "seq returns all key-value pairs"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src {:p 10 :q 20}
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)]
        (is (= (set src) (set (seq m))))))))

(deftest nested-map-with-vec-value
  (testing "map value is a vector — exercises value+sio->eve-bytes nested path"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src {:nums [1 2 3] :tags [:a :b]}
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr coll-factory)]
        (is (= src (into {} m)))))))

(deftest nested-map-with-map-value
  (testing "map value is itself a map — exercises recursive slab allocation"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src {:outer {:inner 42}}
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr coll-factory)]
        (is (= src (into {} m)))))))

;; ---------------------------------------------------------------------------
;; Phase 2a: Native dissoc tests
;; ---------------------------------------------------------------------------

(deftest dissoc-returns-eve-hash-map
  (testing "dissoc returns EveHashMap, not PersistentHashMap"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src {:a 1 :b 2 :c 3}
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)
            m2  (dissoc m :b)]
        (is (instance? eve.map.EveHashMap m2))
        (is (= {:a 1 :c 3} (into {} m2)))
        (is (= 2 (count m2)))))))

(deftest dissoc-missing-key
  (testing "dissoc on missing key returns same map"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) {:x 1})
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)
            m2  (dissoc m :missing)]
        (is (identical? m m2))))))

(deftest dissoc-all-keys
  (testing "dissoc-ing all keys produces empty map"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) {:a 1 :b 2})
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)
            m2  (-> m (dissoc :a) (dissoc :b))]
        (is (instance? eve.map.EveHashMap m2))
        (is (zero? (count m2)))
        (is (= {} (into {} m2)))))))

(deftest dissoc-roundtrip-large
  (testing "assoc N keys, dissoc some, verify remaining"
    (with-heap-slab
      (let [sio  alloc/*jvm-slab-ctx*
            src  (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 50)))
            hdr  (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m    (eve-map/jvm-eve-hash-map-from-offset sio hdr)
            ks   (take 20 (keys src))
            m2   (reduce dissoc m ks)
            exp  (apply dissoc src ks)]
        (is (instance? eve.map.EveHashMap m2))
        (is (= (count exp) (count m2)))
        (is (= exp (into {} m2)))))))

(deftest assoc-collision-native
  (testing "assoc into collision node stays as EveHashMap"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            ;; Build a large map that likely has collision nodes
            src (into {} (map (fn [i] [(keyword (str "collision-" i)) i]) (range 200)))
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)
            m2  (assoc m :new-key 999)]
        (is (instance? eve.map.EveHashMap m2))
        (is (= 999 (get m2 :new-key)))
        (is (= 201 (count m2)))))))

;; ---------------------------------------------------------------------------
;; Phase 3: IFn tests
;; ---------------------------------------------------------------------------

(deftest map-ifn
  (testing "EveHashMap implements IFn"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) {:a 1 :b 2})
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)]
        (is (= 1 (m :a)))
        (is (= 2 (m :b)))
        (is (nil? (m :c)))
        (is (= :default (m :c :default)))))))

;; ---------------------------------------------------------------------------
;; Phase 4: IHashEq + print-method tests
;; ---------------------------------------------------------------------------

(deftest map-hasheq
  (testing "hash of EveHashMap equals hash of equivalent Clojure map"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            src {:a 1 :b 2 :c 3}
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) src)
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)]
        (is (= (hash src) (hash m)))))))

(deftest map-print-method
  (testing "pr-str prints as map literal"
    (with-heap-slab
      (let [sio alloc/*jvm-slab-ctx*
            hdr (eve-map/jvm-write-map! sio (partial mem/value+sio->eve-bytes sio) {:a 1})
            m   (eve-map/jvm-eve-hash-map-from-offset sio hdr)]
        (is (= "{:a 1}" (pr-str m)))))))
