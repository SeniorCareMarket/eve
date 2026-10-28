(ns eve.digest-cache-test
  "Merkle Phase 3, eve half (layout B′): a collection root's hash and
   content digest are computed once and then served from the root's
   digest cache (a write-once side block named by the root's descriptor
   row — eve.digest). These tests use only public behaviour: they fail
   on the tree without the cache (every hash re-walks) and pass with it.
   The fold counters wrap eve.platform's two fold entry points — the
   same counting method Phase 3's befores used.

   Every big root is built in its OWN fresh heap domain: the platform
   default domain's coalescing region is 64 KB (coalesc/
   INITIAL_DATA_SIZE), which a few thousand-entry roots exhaust between
   them — measured on the untouched tree (a bare 5000-entry map does
   not fit it at all). Per-root domains also make the equality checks
   cross-domain: a digest must name content, not a domain."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.platform :as p]
            [eve.digest :as dg]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]
            [eve.map :as m]
            [eve.set :as s]
            [eve.vec :as v]
            [eve.list :as l]))

(defn- fresh-sio [] (alloc/heap-domain-sio! {}))

(defn- counting-folds
  "Runs (f) with eve.platform's folds counted; returns [result counts]."
  [f]
  (let [ordered (atom 0)
        unordered (atom 0)
        o p/hash-ordered
        u p/hash-unordered]
    (with-redefs [p/hash-ordered (fn [c] (swap! ordered inc) (o c))
                  p/hash-unordered (fn [c] (swap! unordered inc) (u c))]
      [(f) {:ordered @ordered :unordered @unordered}])))

(defn- big-map [n]
  (m/into-hash-map (fresh-sio) (map (fn [i] [(keyword (str "k" i)) i]) (range n))))

(defn- big-set [n]
  (apply s/hash-set (fresh-sio) (range n)))

(deftest a-roots-hash-is-computed-once
  (testing "map: calls 2..5 are served, and the value is the host's"
    (let [em (big-map 2000)
          hm (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 2000)))
          [hs counts] (counting-folds (fn [] (mapv (fn [_] (hash em)) (range 5))))]
      (is (apply = hs))
      (is (= (first hs) (hash hm)) "the cached word is the resident hash, verbatim")
      (is (= 1 (:unordered counts)) "one fold for five hashes")))
  (testing "vec: one ordered fold for five hashes"
    (let [ev (v/eve-vec (fresh-sio) (range 2000))
          [hs counts] (counting-folds (fn [] (mapv (fn [_] (hash ev)) (range 5))))]
      (is (apply = hs))
      (is (= (first hs) (hash (vec (range 2000)))))
      (is (= 1 (:ordered counts)))))
  (testing "set: one unordered fold; list: one ordered fold"
    (let [es (big-set 2000)
          [_ counts] (counting-folds (fn [] (mapv (fn [_] (hash es)) (range 5))))]
      (is (= 1 (:unordered counts))))
    (let [el (l/eve-list (fresh-sio) (range 500))
          [_ counts] (counting-folds (fn [] (mapv (fn [_] (hash el)) (range 5))))]
      (is (= 1 (:ordered counts))))))

(deftest equality-stays-exact-with-the-cache
  (testing "two independently built equal maps, one differing in a leaf"
    (let [a (big-map 2000)
          b (big-map 2000)
          c (assoc (big-map 2000) :k1999 -1)]
      (is (= a b))
      (is (= a b) "the second comparison is digest-served and still true")
      (is (not= a c))
      (is (not= a c) "and still false the second time")))
  (testing "vec and set analogues"
    (let [a (v/eve-vec (fresh-sio) (range 2000)) b (v/eve-vec (fresh-sio) (range 2000))
          c (assoc (v/eve-vec (fresh-sio) (range 2000)) 1999 -1)]
      (is (= a b)) (is (not= a c)))
    (let [a (big-set 2000) b (big-set 2000)
          c (conj (big-set 2000) 2000)]
      (is (= a b)) (is (not= a c))))
  (testing "cross-partition equality still comes from the walk"
    (is (= (v/eve-vec [1 2 3]) (l/eve-list [1 2 3])))
    (is (= (l/eve-list [1 2 3]) (v/eve-vec [1 2 3]))))
  (testing "a derived root never inherits the source's cache"
    (let [a (big-map 500)
          _ (hash a)
          b (assoc a :new 1)]
      (is (= (hash b) (hash (assoc (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 500))) :new 1))))
      (is (not= a b))
      (is (= (hash a) (hash (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 500))))))
      "the source's cached hash did not move")))

(deftest a-served-digest-equals-the-computed-one
  (testing "root-digest across the latch boundary: computed, then served"
    (let [em (big-map 200)
          sio (d/-eve-sio em)
          hdr (d/-root-header-off em)
          d1 (dg/root-digest sio hdr em)
          d2 (dg/root-digest sio hdr em)]
      (is (some? d1))
      (is (= d1 d2) "the latched read-back is the computed digest, word for word"))))

(deftest by-reference-content-never-decides
  (testing "maps holding a fn (held by reference: no canonical bytes)"
    (let [f (fn [x] x)
          a (m/hash-map :f f)
          b (m/hash-map :f f)
          c (m/hash-map :f (fn [x] x))]
      (is (= a b) "the same fn: equal, by the walk")
      (is (not= a c) "a different fn: not equal, by the walk")
      (is (= (hash a) (hash b))))))

(deftest small-and-empty-roots-are-unharmed
  (is (= (m/empty-hash-map) (m/empty-hash-map)))
  (is (= (hash (m/empty-hash-map)) (hash {})))
  (is (= (v/empty-vec) (v/empty-vec)))
  (is (= (hash (v/empty-vec)) (hash [])))
  (is (= (s/empty-hash-set) (s/empty-hash-set)))
  (is (= (hash (s/empty-hash-set)) (hash #{})))
  (is (= (m/hash-map :a 1) (m/hash-map :a 1)))
  (is (not= (m/hash-map :a 1) (m/hash-map :a 2))))
