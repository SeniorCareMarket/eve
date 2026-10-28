(ns eve.vec-retire-test
  "The vector retire walk reads only what a header makes live, and its retire set is
   exactly the old vector's blocks the new one does not own: never a block the new
   vector holds, never a leak, never a block twice — across every trie-level boundary
   and the ops that move a node between trie and tail by reference (conj pushes the
   full tail as the trie's last leaf; pop promotes the last leaf to the tail). Found by
   lane EVE-THREAD's S3 (a pool queue shrinking from 80 entries crashed the walk
   reading through a NIL child: offset -1 decodes as slab class 7)."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [swap!]]
            [eve.core :as e]
            [eve.vec :as v]
            [eve.map :as em]
            [eve.set :as es]
            [eve.atom :as a]
            [eve.deftype.int-map :as im]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]))

(defn- live
  "Every block the vector owns, from its header alone."
  [x]
  (set (v/live-offsets (d/-eve-sio x) (d/-root-header-off x))))

(defn- build
  "A vector of xs in sio. Each check builds in a fresh heap domain: a vector built
   outside an atom leaves its builder's intermediate blocks (nothing swaps, so nothing
   retires them), and a few hundred builds would fill one domain."
  [sio xs]
  (e/into (v/empty-vec sio) xs))

(defn- fresh [] (alloc/heap-domain-sio! {}))

(defn- check
  "The retire set of old replaced by new, against both headers' live sets; then both
   freed — new's own blocks through the reverse retire set, old through dispose! — so
   a wrong set shows as a double free."
  [label old new]
  (let [r (v/collect-retire-diff-offsets old new)
        rs (set r)
        l0 (live old)
        l1 (live new)]
    (is (= (count r) (count rs)) (str label ": no block retired twice"))
    (is (empty? (remove l0 r)) (str label ": only blocks the old vector owns"))
    (is (empty? (filter l1 r)) (str label ": never a block the new vector owns"))
    (is (empty? (remove rs (remove l1 l0))) (str label ": every block the old owns and the new does not"))
    (let [sio (d/-eve-sio old)
          back (v/collect-retire-diff-offsets new old)]
      (is (= (set back) (set (remove l0 l1))) (str label ": the reverse set is new's own blocks"))
      (doseq [off back] (alloc/-sio-free! sio off))
      (v/dispose! old))))

;; every trie-level boundary: the tail alone, one level, two levels, three
(def ^:private sizes [0 1 2 31 32 33 63 64 65 96 97 1023 1024 1025 1056 1057])

(deftest conj-pop-assoc-across-boundaries
  (doseq [n sizes]
    (let [mk (fn [] (build (fresh) (range n)))
          old (mk)]
      (let [new (e/conj old :x)]
        (is (= (conj (vec (range n)) :x) (vec (e/seq new))) (str "conj @" n ": contents"))
        (check (str "conj @" n) old new))
      (when (pos? n)
        (let [old (mk) new (e/pop old)]
          ;; the pop once dropped the whole trie at 1025 (pop-tail read one index past the leaf)
          (is (= (vec (range (dec n))) (vec (e/seq new))) (str "pop @" n ": contents"))
          (check (str "pop @" n) old new))
        (let [old (mk) new (e/assoc old 0 :y)]
          (is (= (assoc (vec (range n)) 0 :y) (vec (e/seq new))) (str "assoc first @" n ": contents"))
          (check (str "assoc first @" n) old new))
        (let [old (mk) new (e/assoc old (dec n) :y)]
          (is (= (assoc (vec (range n)) (dec n) :y) (vec (e/seq new))) (str "assoc last @" n ": contents"))
          (check (str "assoc last @" n) old new))))))

(deftest rebuilt-vectors-of-every-depth
  (let [bounds [0 1 32 33 64 65 1024 1025 1057]]
    (doseq [n bounds m bounds]
      (let [old (build (fresh) (range n))]
        (check (str "rebuild " n " -> " m) old (build (d/-eve-sio old) (range m)))))))

(deftest swap-shrinks-a-nested-vector-across-levels
  (testing "S3's crash: a map's vector shrinking past a trie level inside one swap"
    (let [x (a/atom ::queue {:q (vec (range 80))})]
      (swap! x assoc :q (vec (range 20)))
      (is (= (vec (range 20)) (vec (:q @x))))
      (swap! x assoc :q (vec (range 1100)))
      (swap! x assoc :q (vec (range 40)))
      (is (= (vec (range 40)) (vec (:q @x))))
      (swap! x update :q (fn [q] (vec (remove even? q))))
      (is (= (vec (filter odd? (range 40))) (vec (:q @x)))))))

;; ---------------------------------------------------------------------------
;; What a write leaves allocated (2026-10-20 ET)
;; ---------------------------------------------------------------------------

(defn- block
  "An offset as its [class block] — the same for its unsigned form (the allocator's) and its sign-extended one (read
   back from a block, class 4 and up)."
  [off]
  [(alloc/decode-class-idx off) (alloc/decode-block-idx off)])

(deftest a-fresh-map-or-set-written-keeps-only-its-tree
  ;; the HAMT writers insert one entry at a time, each a path copy: what a step superseded was made by the writer and
  ;; never published, and is freed — a fresh 200-entry map left 337 of the 391 blocks it allocated unreachable, a set
  ;; 389 of 443 (each conj that split a slot left its single-value node too), and a committed swap retires only the old
  ;; value's tree, so every such write lost them for good
  (doseq [n [1 2 33 200 1000]]
    (let [sio (fresh)
          lsio (alloc/logging-sio sio)
          m (into {} (map (fn [i] [(str "k" i) i])) (range n))
          hdr ((ser/get-coll-writer :map) lsio nil m)
          tree (em/collect-retire-diff-offsets (em/hash-map-from-header sio hdr) nil)]
      (is (= (set (map block tree)) (set (map block (alloc/logged-allocs lsio)))) (str "a map of " n ": its blocks only"))
      (is (= m (into {} (em/hash-map-from-header sio hdr))) (str "a map of " n ": its entries")))
    (let [sio (fresh)
          lsio (alloc/logging-sio sio)
          xs (set (map (fn [i] (str "x" i)) (range n)))
          hdr ((ser/get-coll-writer :set) lsio nil xs)
          tree (es/collect-retire-diff-offsets (es/hash-set-from-header sio hdr) nil)]
      (is (= (set (map block tree)) (set (map block (alloc/logged-allocs lsio)))) (str "a set of " n ": its blocks only"))
      (is (= xs (set (e/seq (es/hash-set-from-header sio hdr)))) (str "a set of " n ": its members")))))

(deftest an-int32-field-holds-an-offset-past-2-31
  ;; a slab-qualified offset is [class:3 | block:29], past 2^31 from class 4 up: an int-map's children are int32 fields,
  ;; and the field writer's checked cast threw ("integer overflow") once a heap domain's small classes filled and its
  ;; nodes spilled upward (30 rebuilds of a 200-key int-map in one atom)
  (let [sio (alloc/heap-domain-sio! {:capacities {0 4096 1 4096 2 4096 3 4096}})
        m (binding [d/*domain-sio* sio] (reduce (fn [m k] (e/assoc m k (* k k))) (im/int-map) (range 300)))]
    (is (= (into {} (map (fn [k] [k (* k k)])) (range 300)) (into {} (map (fn [k] [k (e/get m k)])) (range 300)))
        "every key reads back, its nodes in the classes past 2^31")))
