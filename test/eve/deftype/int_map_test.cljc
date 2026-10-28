(ns eve.deftype.int-map-test
  "One test file for every host: the int-map is one eve-deftype definition,
   read through eve.core's vocabulary (clojure.core's on the JVM,
   ClojureScript and nbb)."
  ;; swap! on an eve atom: eve.host's (clojerl's own reaches its own atoms only)
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.core :as e]
   #?(:cljs [cljs.test :refer-macros [deftest testing is]]
      :default [clojure.test :refer [deftest testing is]])
   [eve.atom :as atom]
   [eve.deftype.int-map :as im]))

;;-----------------------------------------------------------------------------
;; Helpers
;;-----------------------------------------------------------------------------

(defn make-map
  "An empty int-map."
  []
  (im/int-map))

;;-----------------------------------------------------------------------------
;; Basic construction
;;-----------------------------------------------------------------------------

(deftest empty-map-test
  (testing "Empty int-map"
    (let [m (make-map)]
      (is (= 0 (e/count m)))
      (is (nil? (e/seq m)))
      (is (nil? (e/get m 0)))
      (is (= :nope (e/get m 42 :nope))))))

(deftest single-assoc-test
  (testing "Assoc one key-value pair"
    (let [m (e/assoc (make-map) 42 :hello)]
      (is (= 1 (e/count m)))
      (is (= :hello (e/get m 42)))
      (is (nil? (e/get m 0)))
      (is (= :hello (e/invoke m 42))))))

(deftest multiple-assoc-test
  (testing "Assoc several keys"
    (let [m (-> (make-map)
                (e/assoc 1 :a)
                (e/assoc 2 :b)
                (e/assoc 3 :c))]
      (is (= 3 (e/count m)))
      (is (= :a (e/get m 1)))
      (is (= :b (e/get m 2)))
      (is (= :c (e/get m 3)))
      (is (nil? (e/get m 4))))))

;;-----------------------------------------------------------------------------
;; Overwrite
;;-----------------------------------------------------------------------------

(deftest overwrite-test
  (testing "Assoc overwrites existing key"
    (let [m (-> (make-map)
                (e/assoc 1 :old)
                (e/assoc 1 :new))]
      (is (= 1 (e/count m)))
      (is (= :new (e/get m 1))))))

;;-----------------------------------------------------------------------------
;; Dissoc
;;-----------------------------------------------------------------------------

(deftest dissoc-test
  (testing "Dissoc removes key"
    (let [m (-> (make-map)
                (e/assoc 1 :a)
                (e/assoc 2 :b)
                (e/assoc 3 :c)
                (e/dissoc 2))]
      (is (= 2 (e/count m)))
      (is (= :a (e/get m 1)))
      (is (nil? (e/get m 2)))
      (is (= :c (e/get m 3)))))

  (testing "Dissoc non-existent key is no-op"
    (let [m (e/assoc (make-map) 1 :a)]
      (is (= 1 (e/count (e/dissoc m 999)))))))

(deftest dissoc-to-empty-test
  (testing "Dissoc all keys leaves empty map"
    (let [m (-> (make-map)
                (e/assoc 1 :a)
                (e/dissoc 1))]
      (is (= 0 (e/count m)))
      (is (nil? (e/seq m))))))

;;-----------------------------------------------------------------------------
;; Seq / iteration
;;-----------------------------------------------------------------------------

(deftest seq-test
  (testing "Seq returns MapEntry pairs"
    (let [m (-> (make-map)
                (e/assoc 3 :c)
                (e/assoc 1 :a)
                (e/assoc 2 :b))
          s (e/seq m)]
      (is (= 3 (count s)))
      ;; PATRICIA trie orders by bit-pattern, check all entries present
      (let [entries (set (map (fn [e] [(key e) (val e)]) s))]
        (is (contains? entries [1 :a]))
        (is (contains? entries [2 :b]))
        (is (contains? entries [3 :c]))))))

;;-----------------------------------------------------------------------------
;; Negative keys
;;-----------------------------------------------------------------------------

(deftest negative-keys-test
  (testing "Negative integer keys"
    (let [m (-> (make-map)
                (e/assoc -1 :neg)
                (e/assoc 0 :zero)
                (e/assoc 1 :pos))]
      (is (= 3 (e/count m)))
      (is (= :neg (e/get m -1)))
      (is (= :zero (e/get m 0)))
      (is (= :pos (e/get m 1)))))

  (testing "Dissoc negative key"
    (let [m (-> (make-map)
                (e/assoc -5 :a)
                (e/assoc 5 :b)
                (e/dissoc -5))]
      (is (= 1 (e/count m)))
      (is (nil? (e/get m -5)))
      (is (= :b (e/get m 5))))))

;;-----------------------------------------------------------------------------
;; Larger maps
;;-----------------------------------------------------------------------------

(deftest bulk-insert-test
  (testing "Insert 100 keys"
    (let [m (reduce (fn [m i] (e/assoc m i (* i i)))
                    (make-map)
                    (range 100))]
      (is (= 100 (e/count m)))
      (doseq [i (range 100)]
        (is (= (* i i) (e/get m i)) (str "key " i))))))

(deftest bulk-insert-200-test
  (testing "Insert 200 keys including negatives"
    (let [m (reduce (fn [m i] (e/assoc m i (str "v" i)))
                    (make-map)
                    (range -100 100))]
      (is (= 200 (e/count m)))
      (doseq [i (range -100 100)]
        (is (= (str "v" i) (e/get m i)) (str "key " i))))))

;;-----------------------------------------------------------------------------
;; contains? / IAssociative
;;-----------------------------------------------------------------------------

(deftest contains-test
  (testing "contains? works"
    (let [m (-> (make-map) (e/assoc 1 :a) (e/assoc 2 :b))]
      (is (e/contains? m 1))
      (is (e/contains? m 2))
      (is (not (e/contains? m 3))))))

;;-----------------------------------------------------------------------------
;; IFn invocation
;;-----------------------------------------------------------------------------

(deftest ifn-test
  (testing "Map as function"
    (let [m (e/assoc (make-map) 42 :answer)]
      (is (= :answer (e/invoke m 42)))
      (is (nil? (e/invoke m 99)))
      (is (= :default (e/invoke m 99 :default))))))

;;-----------------------------------------------------------------------------
;; reduce / kv-reduce
;;-----------------------------------------------------------------------------

(deftest reduce-test
  (testing "reduce over entries"
    (let [m (-> (make-map) (e/assoc 1 10) (e/assoc 2 20) (e/assoc 3 30))
          sum (e/reduce (fn [acc [k v]] (+ acc v)) 0 m)]
      (is (= 60 sum)))))

(deftest kv-reduce-test
  (testing "kv-reduce"
    (let [m (-> (make-map) (e/assoc 1 10) (e/assoc 2 20) (e/assoc 3 30))
          sum (e/reduce-kv (fn [acc k v] (+ acc k v)) 0 m)]
      (is (= 66 sum)))))

;;-----------------------------------------------------------------------------
;; conj
;;-----------------------------------------------------------------------------

(deftest conj-test
  (testing "conj with vector pair"
    (let [m (e/conj (make-map) [42 :val])]
      (is (= 1 (e/count m)))
      (is (= :val (e/get m 42)))))

  (testing "conj with map"
    (let [base (e/assoc (make-map) 1 :a)
          m (e/conj base {2 :b 3 :c})]
      (is (= 3 (e/count m))))))

;;-----------------------------------------------------------------------------
;; merge
;;-----------------------------------------------------------------------------

(deftest merge-test
  (testing "Merge two int-maps"
    (let [m1 (-> (im/int-map) (e/assoc 1 :a) (e/assoc 2 :b))
          m2 (-> (im/int-map) (e/assoc 2 :B) (e/assoc 3 :c))
          merged (im/merge m1 m2)]
      (is (= 3 (e/count merged)))
      (is (= :a (e/get merged 1)))
      (is (= :B (e/get merged 2))) ;; right wins
      (is (= :c (e/get merged 3))))))

;;-----------------------------------------------------------------------------
;; merge-with
;;-----------------------------------------------------------------------------

(deftest merge-with-test
  (testing "Merge-with uses conflict resolution fn"
    (let [m1 (-> (im/int-map) (e/assoc 1 10) (e/assoc 2 20))
          m2 (-> (im/int-map) (e/assoc 2 200) (e/assoc 3 300))
          merged (im/merge-with + m1 m2)]
      (is (= 3 (e/count merged)))
      (is (= 10 (e/get merged 1)))
      (is (= 220 (e/get merged 2))) ;; 20 + 200
      (is (= 300 (e/get merged 3))))))

;;-----------------------------------------------------------------------------
;; update
;;-----------------------------------------------------------------------------

(deftest update-test
  (testing "Update existing key"
    (let [m (-> (make-map) (e/assoc 1 10))]
      (is (= 11 (e/get (im/update m 1 inc) 1)))))

  (testing "Update missing key invokes f with nil"
    (let [m (im/update (make-map) 1 (fnil inc 0))]
      (is (= 1 (e/get m 1))))))

;;-----------------------------------------------------------------------------
;; range
;;-----------------------------------------------------------------------------

(deftest range-test
  (testing "Range filters to [min, max]"
    (let [m (reduce (fn [m i] (e/assoc m i i))
                    (make-map)
                    (range 10))
          r (im/range m 3 7)]
      (is (= 5 (e/count r)))
      (doseq [i (range 3 8)]
        (is (= i (e/get r i)) (str "key " i)))
      (is (nil? (e/get r 2)))
      (is (nil? (e/get r 8))))))

;;-----------------------------------------------------------------------------
;; Equality
;;-----------------------------------------------------------------------------

(deftest equality-test
  (testing "Two maps with same entries are equal"
    (let [m1 (-> (make-map) (e/assoc 1 :a) (e/assoc 2 :b))
          m2 (-> (make-map) (e/assoc 2 :b) (e/assoc 1 :a))]
      (is (= m1 m2)))))

;;-----------------------------------------------------------------------------
;; with-meta
;;-----------------------------------------------------------------------------

(deftest meta-test
  (testing "with-meta / meta"
    (let [m (e/with-meta (make-map) {:doc "test"})]
      (is (= {:doc "test"} (e/meta m))))))

;;-----------------------------------------------------------------------------
;; into
;;-----------------------------------------------------------------------------

(deftest into-test
  (testing "into regular map from int-map"
    (let [m (-> (make-map) (e/assoc 1 :a) (e/assoc 2 :b))
          h (into {} (e/seq m))]
      (is (= {1 :a 2 :b} h))))

  (testing "into int-map from pairs"
    (let [m (reduce (fn [m [k v]] (e/assoc m k v))
                    (make-map)
                    [[1 :a] [2 :b] [3 :c]])]
      (is (= 3 (e/count m))))))

;;-----------------------------------------------------------------------------
;; An int-map lives in an atom like any eve value
;;-----------------------------------------------------------------------------

(deftest int-map-in-an-atom-test
  (testing "built inside swap!, read back from the atom"
    (let [a (atom/atom {:id ::int-map-in-an-atom} nil)]
      (swap! a (fn [_] {:m (im/int-map 3 :c 1 :a -2 :neg)}))
      (swap! a update :m e/assoc 2 :b)
      (swap! a update :m e/dissoc 3)
      (let [m (:m @a)]
        (is (= 3 (e/count m)))
        (is (= :neg (e/get m -2)))
        (is (= :b (e/get m 2)))
        (is (nil? (e/get m 3)))
        (is (= {-2 :neg 1 :a 2 :b} (into {} (e/seq m))))))))

(deftest hash-test
         (testing
           "the hash of a map with the same entries"
           (is (= (hash {}) (hash (make-map))))
           (is (= (hash {1 :a}) (hash (-> (make-map) (e/assoc 1 :a)))))
           (is (= (hash {1 :a 2 :b -7 "seven" 40000 [1 2]})
                  (hash (-> (make-map)
                            (e/assoc 1 :a)
                            (e/assoc 2 :b)
                            (e/assoc -7 "seven")
                            (e/assoc 40000 [1 2]))))))
         (testing
           "equal maps hash alike, and are found by each other in a host set"
           (let [m1 (-> (make-map) (e/assoc 1 :a) (e/assoc 2 :b))
                 m2 (-> (make-map) (e/assoc 2 :b) (e/assoc 1 :a))]
             (is (= (hash m1) (hash m2)))
             (is (contains? #{m1} m2)))))
