(ns eve.reclaim-test
  "What a write leaves allocated (2026-10-21 ET: transients reclaim).
   An int-map, a sorted collection and the red-black tree are walked by their own structure, and the walk between two
   versions is exact: the blocks the old one holds that the new one does not, never one the new holds. One write
   allocates only what its new version keeps (int-map's leaf split and branch-above, Okasaki's balance, Kahrs' delete
   and the vector pop that drops a trie level made and dropped nodes). A transient frees what each of its writes
   superseded among the blocks it made, so a build keeps its tree, and the value it began from; a constructor begins
   from no tree and keeps its tree only. Each checked against the allocation log (eve.deftype-proto.alloc's logging
   sio), on every host."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.core :as e]
            [eve.map :as em]
            [eve.set :as es]
            [eve.vec :as v]
            [eve.list :as el]
            [eve.deftype.int-map :as im]
            [eve.deftype.rb-tree :as rb]
            [eve.sorted :as so]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))

(defn- block
  "An offset as its [class block] — the same for its unsigned form (the allocator's) and its sign-extended one (read
   back from a block, class 4 and up)."
  [off]
  [(alloc/decode-class-idx off) (alloc/decode-block-idx off)])

(defn- blocks [offs] (set (map block offs)))

(defn- fresh [] (alloc/heap-domain-sio! {}))

(defn- scrambled
  "xs in an order of their own, the same on every host (a red-black tree's shape depends on the order it is built in)."
  [xs]
  (sort-by (fn [x] (mod (* x 7919) 1009)) xs))

(defn- as-map [x] (into {} (map (fn [kv] [(key kv) (val kv)])) (e/seq x)))
(defn- as-set [x] (set (e/seq x)))
(defn- as-vec [x] (vec (e/seq x)))

(defn- logged
  "[(f lsio) the blocks it allocated and did not free], f run with lsio (a logging sio over sio) as the allocating
   domain."
  [sio f]
  (let [lsio (alloc/logging-sio sio)
        x (binding [d/*domain-sio* lsio] (f lsio))]
    [x (blocks (alloc/logged-allocs lsio))]))

;; functions, not defs of values: clojerl's def takes literal values only
(defn- walks [k]
  (case k
    :int-map im/collect-retire-diff-offsets
    :sorted so/collect-retire-diff-offsets
    :rb rb/collect-retire-diff-offsets))

(defn- check-walk
  "The walk of old against new, against both versions' own blocks (each walked against nothing)."
  [label walk old new]
  (let [r (walk old new)
        rs (blocks r)
        l0 (blocks (walk old nil))
        l1 (blocks (walk new nil))]
    (is (= (count r) (count rs)) (str label ": no block twice"))
    (is (empty? (remove l0 rs)) (str label ": only blocks the old version holds"))
    (is (empty? (filter l1 rs)) (str label ": never a block the new version holds"))
    (is (= rs (set (remove l1 l0))) (str label ": every block the old holds and the new does not"))))

(defn- check-op
  "One write f on x: the walk of x against (f x) exact, and (f x) allocating only the blocks it holds that x does not."
  [label walk sio x f]
  (let [[x' allocs] (logged sio (fn [_] (f x)))]
    (check-walk label walk x x')
    (is (= allocs (blocks (walk x' x))) (str label ": allocates only what it keeps"))))

(defn- int-keys []
  (concat [0 1 2 3 5 299 300 1000 100000 -1 -500 -7 2147483647 -2147483647] (range 0 300 37)))

(deftest int-map-walk-and-writes
  (let [sio (fresh)
        walk (walks :int-map)
        base (binding [d/*domain-sio* sio] (apply im/int-map (interleave (range 0 400 3) (range 0 400 3))))
        neg (binding [d/*domain-sio* sio] (e/assoc base -5 -5))]
    (doseq [k (int-keys)]
      (check-op (str "assoc " k) walk sio base #(e/assoc % k :x))
      (check-op (str "dissoc " k) walk sio base #(e/dissoc % k)))
    (check-op "update" walk sio base #(im/update % 3 inc))
    (check-op "dissoc -5 (the sign split goes)" walk sio neg #(e/dissoc % -5))
    (check-op "the first key" walk sio (binding [d/*domain-sio* sio] (im/int-map)) #(e/assoc % 7 7))
    (check-op "a leaf split" walk sio (binding [d/*domain-sio* sio] (im/int-map 7 7)) #(e/assoc % 9 9))
    (check-walk "merge" walk base (binding [d/*domain-sio* sio] (im/merge base (im/int-map 1 :a 2 :b 100000 :c))))
    (check-walk "range" walk base (binding [d/*domain-sio* sio] (im/range base 10 200)))
    (check-walk "rebuilt" walk base (binding [d/*domain-sio* sio] (apply im/int-map (range 100))))))

(deftest sorted-walk-and-writes
  (let [sio (fresh)
        walk (walks :sorted)
        bs (binding [d/*domain-sio* sio] (apply so/sorted-set (range 0 300 3)))
        bm (binding [d/*domain-sio* sio] (apply so/sorted-map (interleave (range 0 300 3) (range 100))))]
    (doseq [k (concat [-1 1 2 4 150 151 299 300 1000] (range 0 300 37))]
      (check-op (str "conj " k) walk sio bs #(e/conj % k))
      (check-op (str "disj " k) walk sio bs #(e/disj % k))
      (check-op (str "assoc " k) walk sio bm #(e/assoc % k :v))
      (check-op (str "dissoc " k) walk sio bm #(e/dissoc % k)))
    (check-op "assoc an existing key" walk sio bm #(e/assoc % 3 1))
    (check-op "by a comparator" walk sio (binding [d/*domain-sio* sio] (so/sorted-set-by > 1 2 3)) #(e/conj % 9))))

(deftest rb-tree-walk-and-writes
  ;; Okasaki's balance rebuilt the red pair an insert made a level down, a red root was blackened by a copy, and
  ;; Kahrs' delete made and dropped nodes along its path: each insert or delete left them allocated
  (let [sio (fresh)
        walk (walks :rb)
        base (binding [d/*domain-sio* sio] (apply rb/sorted-set (scrambled (range 0 300 3))))]
    (doseq [k (concat [-1 1 2 4 150 151 299 300 1000] (range 0 300 7) (range 0 300 9))]
      (check-op (str "conj " k) walk sio base #(e/conj % k))
      (check-op (str "disj " k) walk sio base #(e/disj % k)))
    (is (rb/rb-valid? (rb/-rb-root (binding [d/*domain-sio* sio] (reduce e/disj base (range 0 300 6))))))))

(deftest a-vector-pop-across-a-trie-level-keeps-only-its-vector
  ;; a pop that drops a trie level read the one child of the root it had just cloned and left the clone allocated
  (doseq [n [1 2 31 32 33 64 65 1024 1025 1056 1057 1058]]
    (let [sio (fresh)
          x (e/into (v/empty-vec sio) (range n))
          [x' allocs] (logged sio (fn [lsio] (e/pop (v/vec-from-header lsio (d/-root-header-off x)))))]
      (is (= (vec (range (dec n))) (vec (e/seq x'))) (str "pop @" n ": contents"))
      (is (= allocs (blocks (v/collect-retire-diff-offsets x' x))) (str "pop @" n ": allocates only what it keeps")))))

(deftest a-list-walk-aligns-the-chains
  ;; the walk stepped the old chain to the new list's head cell, which a conj never reaches: every old cell was retired
  ;; while the new list held it (a list kept in an atom read back other values' bytes once its blocks were reused)
  (let [sio (fresh)
        walk el/collect-retire-diff-offsets
        mk (fn [xs] (e/into (el/empty-list sio) xs))
        base (mk (range 20))]
    ;; where an eve list reads back as one (not babashka's: a list there is the host's own)
    (when (el/eve-list? base)
      (check-walk "conj" walk base (e/conj base :x))
      (check-walk "conj twice" walk base (e/conj (e/conj base :x) :y))
      (check-walk "pop" walk base (e/pop base))
      (check-walk "pop twice" walk base (e/pop (e/pop base)))
      (check-walk "pop then conj" walk base (e/conj (e/pop base) :x))
      (check-walk "rebuilt" walk base (mk (range 30)))
      (check-walk "emptied" walk base (mk []))
      (is (= 1 (count (walk base (e/conj base :x)))) "a conj retires the old header only"))))

;; ---------------------------------------------------------------------------
;; Transients and constructors
;; ---------------------------------------------------------------------------

(defn- tree-of
  "Every block x holds, by its own walk against nothing (a vector: what its header makes live)."
  [x]
  (blocks (cond
            (satisfies? im/IIntMap x) (im/collect-retire-diff-offsets x nil)
            (so/sorted-coll? x) (so/collect-retire-diff-offsets x nil)
            (satisfies? rb/IRBSet x) (rb/collect-retire-diff-offsets x nil)
            (em/eve-hash-map? x) (em/collect-retire-diff-offsets x nil)
            (es/eve-hash-set? x) (es/collect-retire-diff-offsets x nil)
            :else (v/live-offsets (d/-eve-sio x) (d/-root-header-off x)))))

(defn- check-build
  "A transient built by (build (transient origin)), origin made in the log: the log holds the result's tree and the
   origin's blocks, nothing more (each version a write superseded freed)."
  [label mk-origin build plain expected]
  (let [[[origin x] allocs] (logged (fresh) (fn [lsio]
                                              (let [o (mk-origin lsio)]
                                                [o (e/persistent! (build (e/transient o)))])))]
    (is (= expected (plain x)) (str label ": contents"))
    (is (= allocs (into (tree-of x) (tree-of origin))) (str label ": the tree and the value it began from, only"))))

(deftest a-transient-build-keeps-its-tree
  (let [squares (into {} (map (fn [k] [k (* k k)])) (range 200))]
    (check-build "int-map assoc!" (fn [_] (im/int-map))
                 #(reduce (fn [t k] (e/assoc! t k (* k k))) % (range 200)) as-map squares)
    (check-build "int-map assoc! over its own keys" (fn [_] (im/int-map))
                 #(reduce (fn [t k] (e/assoc! t (mod k 200) (* k k))) % (range 400)) as-map
                 (into {} (map (fn [k] [k (* (+ k 200) (+ k 200))])) (range 200)))
    (check-build "sorted-map assoc!" (fn [_] (so/sorted-map))
                 #(reduce (fn [t k] (e/assoc! t k (* k k))) % (range 200)) as-map squares)
    (check-build "sorted-map dissoc!" (fn [_] (so/sorted-map))
                 #(reduce so/dissoc! (reduce (fn [t k] (e/assoc! t k (* k k))) % (range 200)) (range 0 200 2)) as-map
                 (into {} (filter (fn [[k _]] (odd? k))) squares))
    (check-build "sorted-set conj!" (fn [_] (so/sorted-set))
                 #(reduce e/conj! % (range 200)) as-set (set (range 200)))
    (check-build "sorted-set disj!" (fn [_] (so/sorted-set))
                 #(reduce so/disj! (reduce e/conj! % (range 300)) (range 0 300 3)) as-set
                 (set (remove (fn [k] (zero? (mod k 3))) (range 300))))
    (check-build "hash map assoc!" em/empty-hash-map
                 #(reduce (fn [t k] (e/assoc! t k (* k k))) % (range 200)) as-map squares)
    (check-build "hash set conj!" es/empty-hash-set
                 #(reduce e/conj! % (range 200)) as-set (set (range 200)))
    (check-build "vector conj!" v/empty-vec
                 #(reduce e/conj! % (range 2000)) as-vec (vec (range 2000)))
    (check-build "vector conj! then pop!" v/empty-vec
                 #(reduce (fn [t _] (e/pop! t)) (reduce e/conj! % (range 1100)) (range 1000)) as-vec (vec (range 100)))))

(deftest a-transient-from-a-held-value-frees-only-its-own
  ;; the value a transient began from is the caller's: every block of it stays, whatever the writes replace
  (doseq [[label mk build plain] [["int-map" #(apply im/int-map (interleave (range 100) (range 100)))
                                   #(reduce (fn [t k] (e/assoc! t k (- k))) % (range 50 150)) as-map]
                                  ["sorted-set" #(apply so/sorted-set (range 100))
                                   #(reduce so/disj! (reduce e/conj! % (range 50 150)) (range 0 100 2)) as-set]]]
    (let [sio (fresh)
          origin (binding [d/*domain-sio* sio] (mk))
          before (plain origin)
          [x allocs] (logged sio (fn [_] (e/persistent! (build (e/transient origin)))))]
      (is (= before (plain origin)) (str label ": the value it began from is untouched"))
      (is (= allocs (blocks ((if (so/sorted-coll? x) so/collect-retire-diff-offsets im/collect-retire-diff-offsets) x origin)))
          (str label ": what it allocated and kept is exactly what the result holds that the origin does not")))))

(deftest a-constructor-keeps-only-its-tree
  ;; each constructor reduced persistent ops over an empty value: every version but the last stayed allocated
  (doseq [[label mk] [["int-map" #(apply im/int-map (interleave (range 200) (range 200)))]
                      ["int-map of one key" #(im/int-map 1 2)]
                      ["sorted-set" #(apply so/sorted-set (range 200))]
                      ["sorted-map" #(apply so/sorted-map (interleave (range 200) (range 200)))]
                      ["red-black sorted-set" #(apply rb/sorted-set (scrambled (range 200)))]]]
    (let [[x allocs] (logged (fresh) (fn [_] (mk)))]
      (is (= allocs (tree-of x)) (str label ": its tree, nothing more")))))
