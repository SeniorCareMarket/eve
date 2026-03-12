(ns eve2.stress-test
  "Stress tests and benchmarks for eve2 data structures.
   Exercises map, vec, set, list at scale to verify correctness
   and measure performance of the eve2-deftype-based implementations."
  (:require
   [cljs.test :refer-macros [deftest testing is]]
   [eve.deftype-proto.data :as d]
   [eve2.vec :as v2]
   [eve2.list :as l2]
   [eve2.map :as m2]
   [eve2.set :as s2]))

;;-----------------------------------------------------------------------------
;; Timing helper
;;-----------------------------------------------------------------------------

(defn- now-ms [] (.now js/performance))

(defn- bench [label f]
  (let [t0 (now-ms)
        result (f)
        elapsed (- (now-ms) t0)]
    (println (str "  [bench] " label ": " (.toFixed elapsed 1) "ms"))
    result))

(defn- with-parent-atom [f]
  (let [prev d/*parent-atom*]
    (set! d/*parent-atom* true)
    (try (f)
         (finally (set! d/*parent-atom* prev)))))

;;-----------------------------------------------------------------------------
;; VEC stress tests
;;-----------------------------------------------------------------------------

(deftest eve2-vec-2000-elements-test
  (with-parent-atom
    (fn []
      (testing "eve2-vec with 2000 elements"
        (let [n 2000
              v (bench "vec build 2000"
                  #(v2/eve2-vec (range n)))]
          (is (= n (count v)) "Count should be 2000")
          (is (= 0 (nth v 0)))
          (is (= 1024 (nth v 1024)))
          (is (= 1999 (nth v 1999)))
          (bench "vec reduce 2000"
            #(is (= (reduce + (range n)) (reduce + 0 v)))))))))

(deftest eve2-vec-5000-elements-test
  (with-parent-atom
    (fn []
      (testing "eve2-vec with 5000 elements"
        (let [n 5000
              v (bench "vec build 5000"
                  #(v2/eve2-vec (range n)))]
          (is (= n (count v)))
          (is (= 0 (nth v 0)))
          (is (= 2500 (nth v 2500)))
          (is (= 4999 (nth v 4999))))))))

(deftest eve2-vec-assoc-stress-test
  (with-parent-atom
    (fn []
      (testing "eve2-vec many assoc operations"
        (let [n 2000
              v (v2/eve2-vec (range n))]
          (bench "vec assoc 200 updates"
            #(let [v2 (reduce (fn [acc i]
                                (assoc acc i (* i 100)))
                              v (range 0 n 10))]
               (is (= n (count v2)))
               (doseq [i (range 0 n 10)]
                 (is (= (* i 100) (nth v2 i)))))))))))

(deftest eve2-vec-pop-stress-test
  (with-parent-atom
    (fn []
      (testing "eve2-vec popping from 2000 down to 0"
        (let [n 2000
              v (v2/eve2-vec (range n))]
          (bench "vec pop 2000"
            #(loop [current v
                    expected-count n]
               (when (> expected-count 0)
                 (is (= expected-count (count current)))
                 (recur (pop current) (dec expected-count))))))))))

;;-----------------------------------------------------------------------------
;; MAP stress tests
;;-----------------------------------------------------------------------------

(deftest eve2-map-2000-entries-test
  (with-parent-atom
    (fn []
      (testing "eve2 hash-map with 2000 entries"
        (let [n 2000
              m (bench "map build 2000"
                  #(reduce (fn [m i] (assoc m i (* i 10)))
                           (m2/empty-hash-map)
                           (range n)))]
          (is (= n (count m)))
          (is (= 0 (get m 0)))
          (is (= 10000 (get m 1000)))
          (is (= 19990 (get m 1999))))))))

(deftest eve2-map-5000-entries-test
  (with-parent-atom
    (fn []
      (testing "eve2 hash-map with 5000 entries"
        (let [n 5000
              m (bench "map build 5000"
                  #(reduce (fn [m i] (assoc m i (str "val-" i)))
                           (m2/empty-hash-map)
                           (range n)))]
          (is (= n (count m)))
          (is (= "val-0" (get m 0)))
          (is (= "val-2500" (get m 2500)))
          (is (= "val-4999" (get m 4999))))))))

(deftest eve2-map-dissoc-stress-test
  (with-parent-atom
    (fn []
      (testing "eve2 hash-map dissoc stress"
        (let [n 2000
              m (reduce (fn [m i] (assoc m i i))
                        (m2/empty-hash-map)
                        (range n))]
          (bench "map dissoc 667 entries"
            #(let [m2 (reduce (fn [m i] (dissoc m i))
                              m
                              (filter (fn [x] (= 0 (mod x 3))) (range n)))]
               (is (< (count m2) n))
               (doseq [i (filter (fn [x] (not= 0 (mod x 3))) (range n))]
                 (is (= i (get m2 i)))))))))))

(deftest eve2-map-reduce-5000-test
  (with-parent-atom
    (fn []
      (testing "eve2 hash-map reduce 5000 entries"
        (let [n 5000
              m (reduce (fn [m i] (assoc m i (* i 2)))
                        (m2/empty-hash-map)
                        (range n))]
          (bench "map reduce 5000"
            #(is (= (reduce + (map (fn [x] (* x 2)) (range n)))
                     (reduce (fn [acc [k v]] (+ acc v)) 0 m)))))))))

;;-----------------------------------------------------------------------------
;; SET stress tests
;;-----------------------------------------------------------------------------

(deftest eve2-set-2000-elements-test
  (with-parent-atom
    (fn []
      (testing "eve2 hash-set with 2000 elements"
        (let [n 2000
              s (bench "set build 2000"
                  #(into (s2/empty-hash-set) (range n)))]
          (is (= n (count s)))
          (doseq [i (range n)]
            (is (contains? s i)))
          (is (not (contains? s n))))))))

(deftest eve2-set-5000-elements-test
  (with-parent-atom
    (fn []
      (testing "eve2 hash-set with 5000 elements"
        (let [n 5000
              s (bench "set build 5000"
                  #(into (s2/empty-hash-set) (range n)))]
          (is (= n (count s)))
          (is (contains? s 0))
          (is (contains? s 2500))
          (is (contains? s 4999)))))))

(deftest eve2-set-disj-stress-test
  (with-parent-atom
    (fn []
      (testing "eve2 hash-set disj stress"
        (let [n 2000
              s (into (s2/empty-hash-set) (range n))]
          (bench "set disj 1000 even elements"
            #(let [s2 (reduce disj s (filter even? (range n)))]
               (is (= (/ n 2) (count s2)))
               (doseq [i (filter odd? (range n))]
                 (is (contains? s2 i))))))))))

(deftest eve2-set-reduce-5000-test
  (with-parent-atom
    (fn []
      (testing "eve2 hash-set reduce 5000 elements"
        (let [n 5000
              s (into (s2/empty-hash-set) (range n))]
          (bench "set reduce 5000"
            #(is (= (reduce + (range n)) (reduce + 0 s)))))))))

;;-----------------------------------------------------------------------------
;; LIST stress tests
;;-----------------------------------------------------------------------------

(deftest eve2-list-2000-elements-test
  (with-parent-atom
    (fn []
      (testing "eve2-list with 2000 elements"
        (let [n 2000
              l (bench "list build 2000"
                  #(l2/eve2-list (range n)))]
          (is (= n (count l)))
          (is (= 0 (first l)))
          (is (= (vec (range n)) (vec l))))))))

(deftest eve2-list-conj-stress-test
  (with-parent-atom
    (fn []
      (testing "eve2-list conj 3000 elements"
        (let [n 3000]
          (bench "list conj 3000"
            #(let [l (reduce conj (l2/empty-list) (range n))]
               (is (= n (count l)))
               (is (= (dec n) (first l))))))))))

(deftest eve2-list-pop-stress-test
  (with-parent-atom
    (fn []
      (testing "eve2-list pop 2000 down to 0"
        (let [n 2000
              l (l2/eve2-list (range n))]
          (bench "list pop 2000"
            #(loop [current l
                    expected-count n]
               (when (> expected-count 0)
                 (is (= expected-count (count current)))
                 (recur (pop current) (dec expected-count))))))))))
