(ns eve.sorted-coll-test
  "EVM-11a (EM5): eve's sorted map and set (eve.sorted) answer Clojure's PersistentTreeMap / PersistentTreeSet —
   on the JVM against clojure.core's own as the oracle — and VSv2's editable transients, rank / nth, and ONE
   canonical tree: the tree is a function of its keys alone, never of the order they arrived or left in, and the
   same on every host (the shape literal below is asserted on each). The assertions VS's own suite holds for its
   sorted collections (VS's test/vs/gap_coll_sorted_semantics_test.vs, gap_coll_c6_sorted_transients_test.vs) are
   carried here as written."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [eve.core :as e]
            [eve.sorted :as s]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))

(defn- in-a-domain-of-its-own
  "What a computation outside an atom drops is not reclaimed in eve memory (eve frees what a swap! replaces), so on
   the JVM and bb each test builds in a heap domain of its own, dropped with the test (rrb_vector_test's fixture).
   ClojureScript's module slab grows on demand."
  [f]
  #?(:cljs (f)
     :default
     (binding [d/*domain-sio* (alloc/heap-domain-sio! {:capacities {0 (* 32 1024 1024) 1 (* 16 1024 1024)
                                                                    2 (* 8 1024 1024) 3 (* 8 1024 1024)
                                                                    4 (* 8 1024 1024) 5 (* 8 1024 1024)}})]
       (f))))

(use-fixtures :each in-a-domain-of-its-own)

(def shuffled [17 3 42 8 25 1 33 12 29 5 38 21 9 46 14 30 2 40 19 27 6 35 11 23 44 15 31 4 37 20])

(defn- ks [c] (map key (e/seq c)))
(defn- vs [c] (map val (e/seq c)))
(defn- els [c] (vec (e/seq c)))

(deftest default-sorted-map-orders-and-answers
  (let [m (reduce (fn [m k] (e/assoc m k (* 2 k))) (s/sorted-map) shuffled)]
    (is (= (sort shuffled) (ks m)))
    (is (= (map #(* 2 %) (sort shuffled)) (vs m)))
    (is (= (count shuffled) (e/count m)))
    (is (= 34 (e/get m 17)))
    (is (= nil (e/get m 18)))
    (is (= :nf (e/get m 18 :nf)))
    (is (true? (e/contains? m 46)))
    (is (false? (e/contains? m 47)))
    (is (= [8 16] (vec (s/find m 8))))
    (is (nil? (s/find m 7)))
    (is (= (reverse (sort shuffled)) (map key (e/rseq m))))
    (is (= [[1 2] [2 4]] (map vec (take 2 (e/seq m)))))))

(deftest default-sorted-map-dissoc-and-replace
  (let [m (reduce (fn [m k] (e/assoc m k k)) (s/sorted-map) shuffled)
        m2 (reduce e/dissoc m [17 3 42 99])
        m3 (e/assoc m 17 :seventeen)]
    (is (= (count shuffled) (e/count m)) "the source is untouched")
    (is (= (- (count shuffled) 3) (e/count m2)))
    (is (= (remove #{17 3 42} (sort shuffled)) (ks m2)))
    (is (nil? (e/get m2 17)))
    (is (= :seventeen (e/get m3 17)))
    (is (= (sort shuffled) (ks m3)))
    (is (= (count shuffled) (e/count m3)))
    (is (nil? (e/seq (reduce e/dissoc m shuffled))))
    (is (= 0 (e/count (reduce e/dissoc m shuffled))))))

(deftest default-sorted-set-conj-disj
  (let [st (reduce e/conj (s/sorted-set) shuffled)
        st2 (e/disj (e/disj (e/disj st 17) 3) 99)]
    (is (= (sort shuffled) (els st)))
    (is (= (reverse (sort shuffled)) (vec (e/rseq st))))
    (is (true? (e/contains? st 8)))
    (is (= 8 (e/invoke st 8)))
    (is (nil? (e/invoke st 7)))
    (is (= 8 (e/get st 8)))
    (is (= (- (count shuffled) 2) (e/count st2)))
    (is (= (remove #{17 3} (sort shuffled)) (els st2)))
    (is (= (sort shuffled) (els (e/conj st 17))) "conj of a member is the set")
    (is (= (vec (sort (conj shuffled 100))) (els (e/conj st 100))))))

(deftest subseq-and-rsubseq
  ;; 0, 3, ... 99 as (map #(* 3 %) (range 34)): clojerl counts (range 0 100 3) as 33 and reduces 0..96
  (let [m (reduce (fn [m k] (e/assoc m k k)) (s/sorted-map) (map #(* 3 %) (range 34)))
        st (reduce e/conj (s/sorted-set) (map #(* 3 %) (range 34)))]
    (is (= [30 33 36] (map key (take 3 (s/subseq m >= 30)))))
    (is (= [33 36 39] (map key (take 3 (s/subseq m > 30)))))
    (is (= [33 36] (take 2 (map key (s/subseq m >= 31)))))
    (is (= [0 3 6] (map key (s/subseq m <= 6))))
    (is (= [0 3] (map key (s/subseq m < 6))))
    (is (= [3 6 9] (map key (s/subseq m > 0 <= 9))))
    (is (= [30 27 24] (map key (take 3 (s/rsubseq m <= 30)))))
    (is (= [27 24 21] (map key (take 3 (s/rsubseq m < 30)))))
    (is (= [30 27] (take 2 (map key (s/rsubseq m <= 31)))))
    (is (= [99 96] (map key (s/rsubseq m >= 96))))
    (is (= [12 9 6] (map key (s/rsubseq m > 3 < 15))))
    (is (empty? (s/subseq m > 99)))
    (is (empty? (s/rsubseq m < 0)))
    (is (= [96 99] (s/subseq st >= 96)))
    (is (= [9 6 3 0] (s/rsubseq st <= 9)))))

(deftest comparator-sorted-map-and-set-order
  (let [m (e/assoc (s/sorted-map-by > 3 "3" 1 "1" 4 "4" 1 "1" 5 "5" 9 "9" 2 "2" 6 "6") 1 "1")
        st (s/sorted-set-by (fn [a b] (compare b a)) 3 1 4 1 5 9 2 6)
        by-len (s/sorted-map-by (fn [a b] (compare (count a) (count b))) "ccc" 3 "a" 1 "bb" 2)]
    (is (= [9 6 5 4 3 2 1] (ks m)))
    (is (= ["9" "6" "5" "4" "3" "2" "1"] (vs m)))
    (is (= [1 2 3 4 5 6 9] (map key (e/rseq m))))
    (is (= [9 6 5 4 3 2 1] (els st)))
    (is (= ["a" "bb" "ccc"] (ks by-len)))
    (is (= 2 (e/get by-len "zz")))
    (is (= ["a" "bb" "ccc"] (ks (e/assoc by-len "yy" 22))) "a comparator-equal key replaces the value, the stored key stays")
    (is (= [["a" 1] ["bb" 22] ["ccc" 3]] (map vec (e/seq (e/assoc by-len "yy" 22)))))
    (is (= ["a" "ccc"] (ks (e/dissoc by-len "xx"))))
    (is (= [4 3 2] (map key (take 3 (s/subseq m >= 4)))) "subseq in the comparator's own order")))

(deftest metadata-and-empty-keep-the-comparator
  (let [m (e/with-meta (s/sorted-map-by > 1 :a 3 :c 2 :b) {:tag :m})
        m2 (e/assoc m 5 :e)
        st (e/with-meta (s/sorted-set 3 1 2) {:tag :s})
        st2 (e/conj st 0)]
    (is (= [3 2 1] (ks m)))
    (is (= {:tag :m} (e/meta m)))
    (is (= [5 3 2 1] (ks m2)))
    (is (= {:tag :m} (e/meta m2)) "a write carries the metadata")
    (is (= {:tag :s} (e/meta st2)))
    (is (= [0 1 2 3] (els st2)))
    (is (= [3 2 1] (ks (e/with-meta m {:tag :n}))) "with-meta keeps the comparator")
    (is (= {:tag :n} (e/meta (e/with-meta m {:tag :n}))))
    (is (nil? (e/seq (e/empty m))))
    (is (nil? (e/seq (e/empty st))))
    (is (= [2 1 0] (els (e/conj (e/conj (e/conj (e/empty (s/sorted-set-by > 1)) 0) 1) 2))))))

(defn- caught [f] (try (f) nil (catch #?(:cljs :default :default Exception) _ :thrown)))

(deftest transients-round-trip-order-and-contents
  (let [t (reduce (fn [t k] (e/assoc! t k (str k))) (e/transient (s/sorted-map)) shuffled)
        p (e/persistent! t)
        t3 (s/dissoc! (e/assoc! (e/transient p) 100 "100") 17)
        p2 (e/persistent! t3)
        ts (reduce e/conj! (e/transient (s/sorted-set-by >)) shuffled)
        ps (e/persistent! ts)]
    (is (= (sort shuffled) (ks p)))
    (is (= "17" (e/get p 17)))
    (is (= (count shuffled) (e/count p)))
    (is (= (concat (remove #{17} (sort shuffled)) [100]) (ks p2)))
    (is (= (count shuffled) (e/count p)) "the source of a transient is untouched")
    (is (= (sort shuffled) (ks p)))
    (is (= (reverse (sort shuffled)) (els ps)))
    (is (true? (e/contains? ps 42)))
    (is (= [3 1] (els (e/persistent! (s/disj! (e/conj! (e/transient (s/sorted-set-by > 3 2)) 1) 2)))))
    (testing "reads through a live transient answer as the collection's"
      (let [t (e/assoc! (e/transient (s/sorted-map 1 :a)) 2 :b)]
        (is (= :b (e/get t 2)))
        (is (= 2 (e/count t)))
        (is (= :a (e/get t 1)))))
    (testing "a transient used after persistent! throws"
      (let [t (e/transient (s/sorted-map))
            _ (e/persistent! t)]
        (is (= :thrown (caught #(e/assoc! t 1 1))))
        (is (= :thrown (caught #(e/persistent! t))))))
    (testing "the comparator rides through the transient"
      (let [len-cmp (fn [a b] (compare (count a) (count b)))
            src (s/sorted-map-by len-cmp "a" 1)
            p (e/persistent! (e/assoc! (e/transient src) "ccc" 3))]
        (is (= ["a" "ccc"] (ks p)))
        (is (= 1 (e/get p "q")) "comparator-equal lookup on the persisted result")
        (is (= 1 (e/count src)) "the source is untouched")))))

(deftest mixed-keys
  (let [m (s/sorted-map 3 :c 1 :a 2.5 :x 2 :b)
        st (s/sorted-set "pear" "apple" "fig")
        n (s/sorted-map nil :nil 1 :one)
        k (s/sorted-map :b 2 :a 1 :c 3)]
    (is (= [1 2 2.5 3] (ks m)))
    (is (= :x (e/get m 2.5)))
    (is (= [1 2 3] (ks (e/dissoc m 2.5))))
    (is (= [1 2 2.5 3 10] (ks (e/assoc m 10 :t))))
    (is (= ["apple" "fig" "pear"] (els st)))
    (is (= ["fig" "pear"] (els (e/disj st "apple"))))
    (is (= [nil 1] (ks n)))
    (is (= :nil (e/get n nil)))
    (is (true? (e/contains? n nil)))
    (is (= [:a :b :c] (ks k)))
    (is (= [:a :b :c :d] (ks (e/assoc k :d 4))))
    (is (= [:a :c] (ks (e/dissoc k :b))))))

(deftest rank-and-nth
  (let [m (reduce (fn [m k] (e/assoc m k (- k))) (s/sorted-map) shuffled)
        st (reduce e/conj (s/sorted-set-by >) shuffled)
        sorted (vec (sort shuffled))]
    (is (= (range (count sorted)) (map #(s/rank m %) sorted)))
    (is (= -1 (s/rank m 7)))
    (is (= sorted (map #(key (s/nth-entry m %)) (range (count sorted)))))
    (is (= [-1 -2] (map #(val (s/nth-entry m %)) [0 1])))
    (is (= (reverse sorted) (map #(s/nth-entry st %) (range (count sorted)))))
    (is (= 0 (s/rank st 46)))
    (is (= :thrown (caught #(s/nth-entry m 30))))
    (is (= :thrown (caught #(s/nth-entry m -1))))))

(def range-16-shape
  "the canonical tree of (range 16): each key's priority the Murmur3 of its INT32 key bytes, the larger above, ties
   to the smaller bytes — computed from that definition alone by an independent Python model (2026-10-18), equal to
   the JVM's tree, and asserted on every host"
  '[6 [1 [0 nil nil] [2 nil [3 nil [5 [4 nil nil] nil]]]]
    [10 [7 nil [8 nil [9 nil nil]]] [11 nil [15 [12 nil [13 nil [14 nil nil]]] nil]]]])

(defn- lcg-seq
  "n pseudo-random ints in [0, bound) from seed, the same on every host"
  [seed n bound]
  (take n (rest (map #(mod (quot % 65536) bound) (iterate #(mod (+ (* % 1103515245) 12345) 2147483648) seed)))))

(deftest a-larger-map-stays-ordered-through-churn
  (let [order (map #(mod (* % 7919) 1009) (range 600))
        m (reduce (fn [m k] (e/assoc m k (- k))) (s/sorted-map) order)
        gone (filter #(zero? (mod % 3)) (distinct order))
        m2 (reduce e/dissoc m gone)
        rest-keys (sort (remove (set gone) (distinct order)))]
    (is (= (sort (distinct order)) (ks m)))
    (is (= rest-keys (ks m2)))
    (is (= (count rest-keys) (e/count m2)))
    (is (every? #(= (- %) (e/get m2 %)) rest-keys))
    (is (every? #(nil? (e/get m2 %)) gone))
    (is (= (reverse rest-keys) (map key (e/rseq m2))))
    (is (= (take 5 (drop 100 rest-keys)) (map key (take 5 (s/subseq m2 >= (nth rest-keys 100))))))))

(deftest one-canonical-tree
  (testing "the same keys reached by different histories are the same tree"
    (let [keys (range 40)
          a (reduce e/conj (s/sorted-set) keys)
          b (reduce e/conj (s/sorted-set) (reverse keys))
          c (reduce e/conj (s/sorted-set) (lcg-seq 7 200 40))
          d (reduce e/disj (reduce e/conj (s/sorted-set) (range 80)) (range 40 80))
          t (e/persistent! (reduce e/conj! (e/transient (s/sorted-set)) (lcg-seq 99 400 40)))]
      (is (= 40 (e/count c)) "the pseudo-random history reaches every key")
      (is (= (s/shape a) (s/shape b)))
      (is (= (s/shape a) (s/shape c)))
      (is (= (s/shape a) (s/shape d)) "inserted then deleted: the tree of what is left")
      (is (= (s/shape a) (s/shape t)) "a transient build is the persistent build's tree")))
  (testing "a map's tree is its key set's: the values do not move it"
    (is (= (s/shape (reduce e/conj (s/sorted-set) (range 20)))
           (s/shape (reduce (fn [m k] (e/assoc m k (str "v" k))) (s/sorted-map) (reverse (range 20)))))))
  (testing "the tree of (range 16), as a literal: the same on every host"
    (is (= range-16-shape (s/shape (reduce e/conj (s/sorted-set) (range 16)))))))

(deftest equal-to-clojure-s-own
  (let [m (s/sorted-map 2 :b 1 :a)
        st (s/sorted-set 3 1 2)]
    (is (e/equiv m {1 :a 2 :b}))
    (is (e/equiv st #{1 2 3}))
    (is (not (e/equiv m {1 :a})))
    (is (= (hash m) (hash (s/sorted-map 1 :a 2 :b))) "a map's hash is its contents', not its history")
    ;; a host map's own hash where it is Clojure's (Murmur3 over the entries): basilisp hashes its maps by
    ;; immutables.Map's own formula (measured 2026-10-18), which no eve map follows — eve.map's neither
    #?(:lpy nil :default (is (= (hash {1 :a 2 :b}) (hash m)) "a map's hash"))
    (is (= (hash #{1 2 3}) (hash st)) "a set's hash")
    (testing "equal either way round to the host's own map and set (C1: Clojure's sorted map is = to its hash
              map), in the host's vocabulary: clojure.core's =, or eve.core's equiv on bb, where an eve type
              answers eve.core's protocols (an eve-deftype is no sci map: rb-tree's set is the same there)"
      (let [eq #?(:bb e/equiv :default =)]
        (is (eq m {1 :a 2 :b}))
        (is (eq {1 :a 2 :b} m))
        (is (eq {} (s/sorted-map)))
        (is (not (eq {1 :a} m)))
        (is (eq st #{1 2 3}))
        (is (eq #{1 2 3} st))
        ;; clojerl's = of its set with another set checks only containment ((= #{1 2} #{1 2 3}) is true there)
        #?(:clje nil :default (is (not (eq #{1 2} st))))))))

#?(:clj
   (deftest against-clojure-core
     (testing "random operation sequences answer as clojure.core's sorted map and set"
       (doseq [seed [1 2 3 4 5]]
         (let [ops (map vector (lcg-seq seed 500 3) (lcg-seq (+ 100 seed) 500 200))
               [em cm] (reduce (fn [[em cm] [op k]]
                                 (case (int op)
                                   0 [(e/assoc em k (* 3 k)) (assoc cm k (* 3 k))]
                                   1 [(e/dissoc em k) (dissoc cm k)]
                                   2 [(e/assoc em k k) (assoc cm k k)]))
                               [(s/sorted-map) (clojure.core/sorted-map)] ops)]
           (is (= (seq cm) (seq (map (fn [en] [(key en) (val en)]) (e/seq em)))) (str "seed " seed))
           (is (= (map first (rseq cm)) (map key (e/rseq em))))
           (is (= (map first (clojure.core/subseq cm >= 50 < 120)) (map key (s/subseq em >= 50 < 120))))
           (is (= (map first (clojure.core/rsubseq cm > 20 <= 150)) (map key (s/rsubseq em > 20 <= 150))))
           (is (= (count cm) (e/count em))))))))
