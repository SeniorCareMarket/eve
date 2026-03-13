(ns eve.jvm-fuzz-test
  "Property-based fuzz tests for JVM Eve persistent data structures.
   Inspired by ztellman/collection-check (MIT License).

   Performs random sequences of operations on both an Eve collection and a
   reference Clojure collection in parallel, then asserts equivalence."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [eve.deftype-proto.alloc :as alloc]
            [eve.mem :as mem]
            [eve.map  :as eve-map]
            [eve.vec  :as eve-vec]
            [eve.set  :as eve-set]
            [eve.list :as eve-list]))

;; ============================================================================
;; Config
;; ============================================================================

(def ^:private num-tests
  "Number of random test iterations per property."
  100)

;; ============================================================================
;; Helpers
;; ============================================================================

(defmacro with-heap-slab [& body]
  `(let [ctx# (alloc/make-jvm-heap-slab-ctx {})]
     (binding [alloc/*jvm-slab-ctx* ctx#]
       ~@body)))

(defn- check-property
  "Run a test.check property and report failures via clojure.test."
  [prop-name prop]
  (let [result (tc/quick-check num-tests prop)]
    (is (:pass? result)
        (str "Property '" prop-name "' failed after "
             (:num-tests result) " tests.\n"
             "Shrunk: " (pr-str (get-in result [:shrunk :smallest]))))))

;; ============================================================================
;; Map operations generator
;; ============================================================================

(def ^:private gen-map-key
  (gen/one-of [(gen/choose -20 20)
               (gen/elements [:a :b :c :d :e :f :g :h :i :j])]))

(def ^:private gen-map-val
  (gen/one-of [(gen/choose -100 100)
               gen/string-alphanumeric
               (gen/elements [:x :y :z nil true false])]))

(def ^:private gen-map-op
  (gen/one-of
   [(gen/fmap (fn [[k v]] [:assoc k v]) (gen/tuple gen-map-key gen-map-val))
    (gen/fmap (fn [k] [:dissoc k]) gen-map-key)
    (gen/fmap (fn [k] [:get k]) gen-map-key)]))

(defn- apply-map-op
  [[eve-m ref-m] [op & args]]
  (case op
    :assoc (let [[k v] args]
             [(assoc eve-m k v) (assoc ref-m k v)])
    :dissoc (let [[k] args]
              [(dissoc eve-m k) (dissoc ref-m k)])
    :get (let [[k] args]
           (is (= (get eve-m k) (get ref-m k))
               (str "get mismatch for key " k))
           [eve-m ref-m])))

;; ============================================================================
;; Vector operations generator
;; ============================================================================

(def ^:private gen-vec-val
  (gen/one-of [(gen/choose -100 100)
               (gen/elements [:a :b :c nil true false])]))

(def ^:private gen-vec-op
  (gen/one-of
   [(gen/fmap (fn [v] [:conj v]) gen-vec-val)
    (gen/return [:pop])
    (gen/fmap (fn [[i v]] [:assoc i v])
              (gen/tuple (gen/choose 0 50) gen-vec-val))]))

(defn- apply-vec-op
  [[eve-v ref-v] [op & args]]
  (case op
    :conj (let [[v] args]
            [(conj eve-v v) (conj ref-v v)])
    :pop (if (pos? (count eve-v))
           [(pop eve-v) (pop ref-v)]
           [eve-v ref-v])
    :assoc (let [[i v] args
                 cnt (count eve-v)]
             (if (and (pos? cnt) (<= 0 i) (< i cnt))
               [(assoc eve-v i v) (assoc ref-v i v)]
               [eve-v ref-v]))))

;; ============================================================================
;; Set operations generator
;; ============================================================================

(def ^:private gen-set-elem
  (gen/one-of [(gen/choose -20 20)
               (gen/elements [:a :b :c :d :e :f :g :h :i :j])]))

(def ^:private gen-set-op
  (gen/one-of
   [(gen/fmap (fn [e] [:conj e]) gen-set-elem)
    (gen/fmap (fn [e] [:disj e]) gen-set-elem)
    (gen/fmap (fn [e] [:contains? e]) gen-set-elem)]))

(defn- apply-set-op
  [[eve-s ref-s] [op & args]]
  (case op
    :conj (let [[e] args]
            [(conj eve-s e) (conj ref-s e)])
    :disj (let [[e] args]
            [(disj eve-s e) (disj ref-s e)])
    :contains? (let [[e] args]
                 (is (= (contains? eve-s e) (contains? ref-s e))
                     (str "contains? mismatch for " e))
                 [eve-s ref-s])))

;; ============================================================================
;; Map fuzz tests
;; ============================================================================

(deftest fuzz-jvm-map-ops-test
  (testing "Random map operations maintain equivalence with reference"
    (with-heap-slab
      (check-property
       "jvm-map-ops"
       (prop/for-all [ops (gen/vector gen-map-op 1 50)]
         (with-heap-slab
           (let [[eve-m ref-m] (reduce apply-map-op
                                       [(eve-map/empty-hash-map) {}]
                                       ops)]
             (and (= (count eve-m) (count ref-m))
                  (= eve-m ref-m)
                  (= ref-m eve-m)
                  (= (hash eve-m) (hash ref-m))
                  (every? (fn [[k v]]
                            (= v (get eve-m k)))
                          ref-m)))))))))

(deftest fuzz-jvm-map-seq-test
  (testing "Map seq produces all entries"
    (with-heap-slab
      (check-property
       "jvm-map-seq"
       (prop/for-all [kvs (gen/vector (gen/tuple gen-map-key gen-map-val) 0 30)]
         (with-heap-slab
           (let [ref-m (into {} kvs)
                 eve-m (into (eve-map/empty-hash-map) kvs)]
             (= (set (seq eve-m)) (set (seq ref-m))))))))))

(deftest fuzz-jvm-map-assoc-dissoc-roundtrip-test
  (testing "assoc then dissoc returns to original"
    (with-heap-slab
      (check-property
       "jvm-map-assoc-dissoc-roundtrip"
       (prop/for-all [k gen-map-key
                      v gen-map-val]
         (with-heap-slab
           (let [m (eve-map/empty-hash-map)
                 m2 (assoc m k v)
                 m3 (dissoc m2 k)]
             (and (= v (get m2 k))
                  (nil? (get m3 k))
                  (= 0 (count m3))))))))))

;; ============================================================================
;; Vector fuzz tests
;; ============================================================================

(deftest fuzz-jvm-vec-ops-test
  (testing "Random vector operations maintain equivalence with reference"
    (with-heap-slab
      (check-property
       "jvm-vec-ops"
       (prop/for-all [ops (gen/vector gen-vec-op 1 50)]
         (with-heap-slab
           (let [[eve-v ref-v] (reduce apply-vec-op
                                       [(eve-vec/empty-sab-vec) []]
                                       ops)]
             (and (= (count eve-v) (count ref-v))
                  (= eve-v ref-v)
                  (= ref-v eve-v)
                  (= (hash eve-v) (hash ref-v))
                  (every? (fn [i]
                            (= (nth eve-v i) (nth ref-v i)))
                          (range (count ref-v)))))))))))

(deftest fuzz-jvm-vec-conj-seq-test
  (testing "conj sequence matches reference"
    (with-heap-slab
      (check-property
       "jvm-vec-conj-seq"
       (prop/for-all [elems (gen/vector gen-vec-val 0 50)]
         (with-heap-slab
           (let [eve-v (into (eve-vec/empty-sab-vec) elems)
                 ref-v (vec elems)]
             (and (= (count eve-v) (count ref-v))
                  (= (vec (seq eve-v)) ref-v)))))))))

(deftest fuzz-jvm-vec-assoc-test
  (testing "Random assocs maintain equivalence"
    (with-heap-slab
      (check-property
       "jvm-vec-assoc"
       (prop/for-all [elems (gen/vector gen-vec-val 1 30)
                      updates (gen/vector
                               (gen/tuple (gen/choose 0 29) gen-vec-val)
                               1 10)]
         (with-heap-slab
           (let [n (count elems)
                 valid-updates (filter (fn [[i _]] (< i n)) updates)
                 eve-v (reduce (fn [v [i val']] (assoc v i val'))
                               (eve-vec/sab-vec elems)
                               valid-updates)
                 ref-v (reduce (fn [v [i val']] (assoc v i val'))
                               (vec elems)
                               valid-updates)]
             (= eve-v ref-v))))))))

;; ============================================================================
;; Set fuzz tests
;; ============================================================================

(deftest fuzz-jvm-set-ops-test
  (testing "Random set operations maintain equivalence with reference"
    (with-heap-slab
      (check-property
       "jvm-set-ops"
       (prop/for-all [ops (gen/vector gen-set-op 1 50)]
         (with-heap-slab
           (let [[eve-s ref-s] (reduce apply-set-op
                                       [(eve-set/empty-hash-set) #{}]
                                       ops)]
             (and (= (count eve-s) (count ref-s))
                  (= eve-s ref-s)
                  (= ref-s eve-s)
                  (= (hash eve-s) (hash ref-s))))))))))

(deftest fuzz-jvm-set-conj-disj-roundtrip-test
  (testing "conj then disj returns to original"
    (with-heap-slab
      (check-property
       "jvm-set-conj-disj-roundtrip"
       (prop/for-all [e gen-set-elem]
         (with-heap-slab
           (let [s (eve-set/empty-hash-set)
                 s2 (conj s e)
                 s3 (disj s2 e)]
             (and (contains? s2 e)
                  (not (contains? s3 e))
                  (= 0 (count s3))))))))))

(deftest fuzz-jvm-set-idempotent-conj-test
  (testing "conj same element is idempotent"
    (with-heap-slab
      (check-property
       "jvm-set-idempotent-conj"
       (prop/for-all [e gen-set-elem]
         (with-heap-slab
           (let [s (conj (eve-set/empty-hash-set) e)
                 s2 (conj s e)]
             (and (= 1 (count s))
                  (= 1 (count s2))
                  (= s s2)))))))))
