(ns ^{:doc
  "A B-tree based persistent sorted set. Supports transients, custom comparators, fast iteration, efficient slices (iterator over a part of the set) and reverse slices. Almost a drop-in replacement for [[clojure.core/sorted-set]], the only difference being this one can’t store nil.

   EVE PORT of Tonsky's persistent-sorted-set 0.2.3 (CLJS form) — see
   PROVENANCE.md for the upstream source and PORTING.md for every delta.
   The B-tree algorithm, path encoding and public API are vanilla."
  :author "Nikita Prokopov"}
  me.tonsky.persistent-sorted-set
  (:refer-clojure :exclude [iter conj disj sorted-set sorted-set-by])
  (:require
    [clojure.string :as str]
    [eve.core :as e]
    [eve.deftype :refer [eve-deftype deftype]]
    [eve.deftype-proto.data :as d]
    [me.tonsky.persistent-sorted-set.arrays :as arrays]
    ;; PORT DELTA (F-CLJE-HOST-REFERS): as in arrays — clojerl core
    ;; also lacks the transient family; eve.host supplies it with
    ;; persistent-identity semantics.
    #?(:clje [eve.host :refer [unsigned-bit-shift-right transient persistent! conj! long]])))

; B+ tree
; -------

; Leaf:     keys[]     :: array of values

; Node:     pointers[] :: links to children nodes
;           keys[]     :: max value for whole subtree
;                         node.keys[i] == max(node.pointers[i].keys)
; All arrays are 16..32 elements, inclusive

; BTSet:    root       :: Node or Leaf
;           shift      :: depth - 1
;           cnt        :: size of a set, integer, rolling
;           comparator :: comparator used for ordering
;           meta       :: clojure meta map
; (vanilla also caches _hash; the port computes hasheq on demand — PORTING.md E2)

; Path: conceptually a vector of indexes from root to leaf value, but encoded in a single number.
;       E.g. we have path [7 30 11] representing root.pointers[7].pointers[30].keys[11].
;       In our case level-shift is 5, meaning each index will take 5 bits:
;       (7 << 10) | (30 << 5) | (11 << 0) = 8139
;         00111       11110       01011

; Iter:     set       :: Set this iterator belongs to
;           left      :: Current path
;           right     :: Right bound path (exclusive)
;           keys      :: Cached ref for keys array for a leaf
;           idx       :: Cached idx in keys array
; Keys and idx are cached for fast iteration inside a leaf"

(def ^:const bits-per-level
  "tunable param"
  5)

(def ^:const max-len
  (bit-shift-left 1 bits-per-level)) ;; 32

(def ^:const min-len
  ;; PORT DELTA (F-CLJE-DIV): was (/ max-len 2) — clojerl's / is
  ;; Erlang float division ((/ 32 2) is the float 16.0 there), and
  ;; the float poisoned avg-len's compile-eval (bit-and on a float:
  ;; :badarith). The division is exact by construction (max-len is
  ;; 2^bits-per-level), and quot is the exact-integer spelling every
  ;; seat agrees on: 16, an integer, everywhere.
  (quot max-len 2)) ;; 16

(def ^:private ^:const avg-len
  (arrays/half (+ max-len min-len))) ;; 24

(def ^:const bit-mask
  (- max-len 1)) ;; 0b011111 = 5 bit

(def ^:const empty-path 0)

;; PORTING.md N1: vanilla's path layer carried a JS-32-bit fallback (doubles
;; past 2^31, the `factors` table). Eve hosts have 64-bit (or bigger) integers;
;; the port uses plain long bit-ops at every level.
(defn- path-get [path level]
  (-> path
    (unsigned-bit-shift-right (* level bits-per-level))
    (bit-and bit-mask)))

(defn- path-set [path level idx]
  (let [old   (path-get path level)
        minus (bit-shift-left old (* level bits-per-level))
        plus  (bit-shift-left idx (* level bits-per-level))]
    (-> path
      (- minus)
      (+ plus))))

(defn- path-inc [path]
  (inc path))

(defn- path-dec [path]
  (dec path))

(defn- path-cmp [path1 path2]
  (- path1 path2))

(defn- path-lt [path1 path2]
  (< path1 path2))

(defn- path-lte [path1 path2]
  (<= path1 path2))

(defn- path-eq [path1 path2]
  (== path1 path2))

(defn- path-same-leaf [path1 path2]
  (==
    (unsigned-bit-shift-right path1 bits-per-level)
    (unsigned-bit-shift-right path2 bits-per-level)))

(defn- path-str [path]
  (loop [res ()
         path path]
    (if (not= path 0)
      (recur (clojure.core/conj res (mod path max-len)) (quot path max-len))
      (vec res))))

(defn- binary-search-l [cmp arr r k]
  (loop [l 0
         r (long r)]
    (if (<= l r)
      (let [m  (arrays/half (+ l r))
            mk (arrays/aget arr m)]
        (if (neg? (cmp mk k))
          (recur (inc m) r)
          (recur l (dec m))))
      l)))

(defn- binary-search-r [cmp arr r k]
  (loop [l 0
         r (long r)]
    (if (<= l r)
      (let [m  (arrays/half (+ l r))
            mk (arrays/aget arr m)]
        (if (pos? (cmp mk k))
          (recur l (dec m))
          (recur (inc m) r)))
      l)))

(defn- lookup-exact [cmp arr key]
  (let [arr-l (arrays/alength arr)
        idx   (binary-search-l cmp arr (dec arr-l) key)]
    (if (and (< idx arr-l)
             (== 0 (cmp (arrays/aget arr idx) key)))
      idx
      -1)))

(defn- lookup-range [cmp arr key]
  (let [arr-l (arrays/alength arr)
        idx   (binary-search-l cmp arr (dec arr-l) key)]
    (if (== idx arr-l)
      -1
      idx)))

;; Array operations
;; PORTING.md S2: vanilla builds arrays by allocating then copying into them
;; for effect. The port's arrays are persistent vectors, so the construction
;; helpers below build the same final array functionally.

(defn- cut-n-splice [arr cut-from cut-to splice-from splice-to xs]
  ;; PORT DELTA (tally write fix): the vanilla (vec (concat (subvec ...)
  ;; xs (subvec ...))) rebuild decoded and re-wrapped every carried
  ;; element's value block — the dominant fresh-block mint on the
  ;; DataScript write path. arrays/splice assembles the same array at
  ;; the block level; only xs are wrapped.
  (arrays/splice arr cut-from cut-to splice-from splice-to xs))

(defn- splice [arr splice-from splice-to xs]
  (cut-n-splice arr 0 (arrays/alength arr) splice-from splice-to xs))

(defn- insert [arr idx xs]
  (cut-n-splice arr 0 (arrays/alength arr) idx idx xs))

(defn- merge-n-split [a1 a2]
  (let [total-l (+ (arrays/alength a1) (arrays/alength a2))
        r1-l    (arrays/half total-l)
        ;; PORT DELTA (tally write fix): block-level concat + slices —
        ;; the (into (vec a1) a2) rebuild re-wrapped every element of
        ;; both halves; the halves now share a1's and a2's blocks.
        both    (arrays/aconcat a1 a2)]
    (arrays/array (arrays/aslice both 0 r1-l)
                  (arrays/aslice both r1-l))))

;; PORT DELTA (F-CLJE-TAG): eq-arr's return tag was ^boolean — on
;; clojerl a callee's declared tag is fetched from its var at every
;; call site, and this one is stored as a clojerl.Var, which the
;; analyzer refuses ("Invalid tag, expected Type, Symbol or String
;; got: clojerl.Var" at the check-n-splice call). The tag is a hint
;; only; dropping it is behavior-neutral on every seat (the same
;; remedy Eve's clje lane applied across its own surface).
(defn- eq-arr [cmp a1 a1-from a1-to a2 a2-from a2-to]
  (let [len (- a1-to a1-from)]
    (and
      (== len (- a2-to a2-from))
      (loop [i 0]
        (cond
          (== i len)
          true

          (not (== 0 (cmp
                       (arrays/aget a1 (+ i a1-from))
                       (arrays/aget a2 (+ i a2-from)))))
          false

          :else
          (recur (inc i)))))))

(defn- check-n-splice [cmp arr from to new-arr]
  (if (eq-arr cmp arr from to new-arr 0 (arrays/alength new-arr))
    arr
    (splice arr from to new-arr)))

(defn- return-array
  "Drop non-nil references and return array of arguments"
  ([a1]
    (arrays/array a1))
  ([a1 a2]
    (if a1
      (if a2
        (arrays/array a1 a2)
        (arrays/array a1))
      (arrays/array a2)))
  ([a1 a2 a3]
    (if a1
      (if a2
        (if a3
          (arrays/array a1 a2 a3)
          (arrays/array a1 a2))
        (if a3
          (arrays/array a1 a3)
          (arrays/array a1)))
      (if a2
        (if a3
          (arrays/array a2 a3)
          (arrays/array a2))
        (arrays/array a3)))))

;;

(defprotocol INode
  (node-lim-key       [_])
  (node-len           [_])
  (node-merge         [_ next])
  (node-merge-n-split [_ next])
  (node-lookup        [_ cmp key])
  (node-conj          [_ cmp key])
  (node-disj          [_ cmp key root? left right]))

(defn- rotate [node root? left right]
  (cond
    ;; root never merges
    root?
    (return-array node)

    ;; enough keys, nothing to merge
    (> (node-len node) min-len)
    (return-array left node right)

    ;; left and this can be merged to one
    (and left (<= (node-len left) min-len))
    (return-array (node-merge left node) right)

    ;; right and this can be merged to one
    (and right (<= (node-len right) min-len))
    (return-array left (node-merge node right))

    ;; left has fewer nodes, redestribute with it
    (and left (or (nil? right)
                  (< (node-len left) (node-len right))))
    (let [nodes (node-merge-n-split left node)]
      (return-array (arrays/aget nodes 0) (arrays/aget nodes 1) right))

    ;; right has fewer nodes, redestribute with it
    :else
    (let [nodes (node-merge-n-split node right)]
      (return-array left (arrays/aget nodes 0) (arrays/aget nodes 1)))))

(eve-deftype Node [keys pointers]
  INode
  (node-lim-key [_]
    (arrays/alast keys))

  (node-len [_]
    (arrays/alength keys))

  (node-merge [_ next]
    (->Node (arrays/aconcat keys (.-keys next))
            (arrays/aconcat pointers (.-pointers next))))

  (node-merge-n-split [_ next]
    (let [ks (merge-n-split keys     (.-keys next))
          ps (merge-n-split pointers (.-pointers next))]
      (return-array (->Node (arrays/aget ks 0) (arrays/aget ps 0))
                    (->Node (arrays/aget ks 1) (arrays/aget ps 1)))))

  (node-lookup [_ cmp key]
    (let [idx (lookup-range cmp keys key)]
      (when-not (== -1 idx)
        (node-lookup (arrays/aget pointers idx) cmp key))))

  (node-conj [_ cmp key]
    (let [idx   (binary-search-l cmp keys (- (arrays/alength keys) 2) key)
          nodes (node-conj (arrays/aget pointers idx) cmp key)]
      (when nodes
        (let [new-keys     (check-n-splice cmp keys     idx (inc idx) (arrays/amap node-lim-key nodes))
              new-pointers (splice             pointers idx (inc idx) nodes)]
          (if (<= (arrays/alength new-pointers) max-len)
            ;; ok as is
            (arrays/array (->Node new-keys new-pointers))
            ;; gotta split it up
            (let [middle  (arrays/half (arrays/alength new-pointers))]
              (arrays/array
                (->Node (arrays/aslice new-keys     0 middle)
                        (arrays/aslice new-pointers 0 middle))
                (->Node (arrays/aslice new-keys     middle)
                        (arrays/aslice new-pointers middle)))))))))

  (node-disj [_ cmp key root? left right]
    (let [idx (lookup-range cmp keys key)]
      (when-not (== -1 idx) ;; short-circuit, key not here
        (let [child       (arrays/aget pointers idx)
              left-child  (when (>= (dec idx) 0)
                            (arrays/aget pointers (dec idx)))
              right-child (when (< (inc idx) (arrays/alength pointers))
                            (arrays/aget pointers (inc idx)))
              disjned     (node-disj child cmp key false left-child right-child)]
          (when disjned     ;; short-circuit, key not here
            (let [left-idx     (if left-child  (dec idx) idx)
                  right-idx    (if right-child (+ 2 idx) (+ 1 idx))
                  new-keys     (check-n-splice cmp keys     left-idx right-idx (arrays/amap node-lim-key disjned))
                  new-pointers (splice             pointers left-idx right-idx disjned)]
              (rotate (->Node new-keys new-pointers) root? left right))))))))

(eve-deftype Leaf [keys]
  INode
  (node-lim-key [_]
    (arrays/alast keys))

  (node-len [_]
    (arrays/alength keys))

  (node-merge [_ next]
    (->Leaf (arrays/aconcat keys (.-keys next))))

  (node-merge-n-split [_ next]
    (let [ks (merge-n-split keys (.-keys next))]
      (return-array (->Leaf (arrays/aget ks 0))
                    (->Leaf (arrays/aget ks 1)))))

  (node-lookup [_ cmp key]
    (let [idx (lookup-exact cmp keys key)]
      (when-not (== -1 idx)
        (arrays/aget keys idx))))

  (node-conj [_ cmp key]
    (let [idx    (binary-search-l cmp keys (dec (arrays/alength keys)) key)
          keys-l (arrays/alength keys)]
      (cond
        ;; element already here
        (and (< idx keys-l)
             (== 0 (cmp key (arrays/aget keys idx))))
          nil

        ;; splitting
        (== keys-l max-len)
          (let [middle (arrays/half (inc keys-l))]
            (if (> idx middle)
              ;; new key goes to the second half
              (arrays/array
                (->Leaf (arrays/aslice keys 0 middle))
                (->Leaf (cut-n-splice keys middle keys-l idx idx (arrays/array key))))
              ;; new key goes to the first half
              (arrays/array
                (->Leaf (cut-n-splice keys 0 middle idx idx (arrays/array key)))
                (->Leaf (arrays/aslice keys middle keys-l)))))

        ;; ok as is
        :else
          (arrays/array (->Leaf (splice keys idx idx (arrays/array key)))))))

  (node-disj [_ cmp key root? left right]
    (let [idx (lookup-exact cmp keys key)]
      (when-not (== -1 idx) ;; key is here
        (let [new-keys (splice keys idx (inc idx) (arrays/array))]
          (rotate (->Leaf new-keys) root? left right))))))

;; BTSet

(declare conj disj btset-iter slice rslice seek)

(defn- print-btset [this]
  (str "#{" (str/join " " (map pr-str (e/seq this))) "}"))

(defonce comparator-registry
  ;; PORTING.md E3 (0.3.1 upgrade): comparators that must cross Eve's codec
  ;; (a set living in a shared atom) are stored as registered NAMES
  ;; (keywords) and resolved here at every use site. A keyword is a slab
  ;; value on every host; the fn it names is per-process, like Eve's own
  ;; type/protocol registries. Unregistered keywords resolve to nil and
  ;; fail loudly at the first comparison.
  (atom {}))

(defn register-comparator!
  "Register a comparator fn under a keyword name so sets using it can be
   stored in Eve atoms and read back on any host. Returns the keyword."
  [k f]
  (swap! comparator-registry assoc k f)
  k)

(defn- cmp-of
  "The set's comparator. PORTING.md E1: the default comparator is stored as
   nil — a host fn is not a slab value — and unwrapped to clojure.core/compare
   at every use site. Custom comparators are stored as-is (heap use);
   keyword comparators resolve through comparator-registry (E3)."
  [set]
  (let [c (.-comparator set)]
    (cond (nil? c)     compare
          (keyword? c) (get @comparator-registry c)
          :else        c)))

(eve-deftype BTSet [root ^:int32 shift cnt comparator meta]
  clojure.lang.IMeta
  (meta [_] meta)

  clojure.lang.IObj
  (withMeta [_ m] (->BTSet root shift cnt comparator m))

  clojure.lang.IPersistentCollection
  (cons [this key] (conj this key (cmp-of this)))
  (empty [_] (->BTSet (->Leaf (arrays/array)) 0 0 comparator meta))
  (equiv [this other]
    ;; PORTING.md V2: gate and traversal in Eve's vocabulary, following Eve's
    ;; own user type (eve.deftype.rb-tree's SortedSet): the host predicate
    ;; covers host values, (instance? BTSet other) covers this type on seats
    ;; whose core predicates are blind to Eve types (bb's SCI: (set? btset)
    ;; and (satisfies? IPersistentSet btset) are both false there), and the
    ;; walk goes through e/seq + e/contains?, never core seq/contains?.
    (and
      (or (set? other) (instance? BTSet other))
      (== cnt (e/count other))
      (every? #(e/contains? this %) (e/seq other))))

  clojure.lang.Counted
  (count [_] cnt)

  ;; PORTING.md T4 (revised by the upstream suite): vanilla's transients are
  ;; identity shims — as-transient returns this, conj!/disjoin! are the
  ;; persistent ops, persistent! is the identity. The port implements them
  ;; with exactly those semantics. (Upstream's one :clj-only test,
  ;; iter-over-transient, demands the JAVA form's edit-tracked transients
  ;; that throw on mutation during iteration; the CLJS form never runs it.)
  clojure.lang.IEditableCollection
  (asTransient [this] this)

  clojure.lang.ITransientCollection
  (conj [this key] (conj this key (cmp-of this)))
  (persistent [this] this)

  ;; No ITransientSet group: on the JVM its methods collide with
  ;; IPersistentSet's on this one class — contains/get share signatures
  ;; (duplicate-method ClassFormatError) and disjoin shares name+params
  ;; with an incompatible return type (IPersistentSet vs ITransientSet),
  ;; which is exactly why stock Clojure's sets answer `transient` with a
  ;; separate transient object. Vanilla's identity transient can therefore
  ;; be carried on the JVM seat only as far as ITransientCollection
  ;; (conj!/persistent! work on the set itself); disjoin! casts to
  ;; ITransientSet and refuses — a host class-model law, recorded in
  ;; PORTING.md T4, not a port choice.

  ;; the JVM form implements IReduce/IReduceInit; upstream's test-reduces
  ;; calls .reduce directly on :clj. Delegates to the seq, like cljs reduce.
  clojure.lang.IReduce
  (reduce [this f]
    (if-some [s (e/seq this)]
      (reduce f (first s) (next s))
      (f)))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (reduce f init (e/seq this)))

  clojure.lang.Seqable
  (seq [this] (btset-iter this))

  ;; PORTING.md T7: vanilla's JVM form implements clojure.lang.Sorted,
  ;; and DataScript's find-datom calls (.comparator ^Sorted set) on the
  ;; :clj branch. comparator() hands back a java.util.Comparator over
  ;; the set's (possibly registered-keyword) comparator; the seqFrom
  clojure.lang.Sorted
  (comparator [this]
    ;; a Comparator that is also IFn: DataScript calls it as a fn,
    ;; (cmp to datom). clojure.core/comparator over the less-than
    ;; predicate of the set's compare fn returns exactly that dual
    ;; object (Comparator + IFn, normalized to -1/0/1).
    (let [cmp (cmp-of this)]
      (clojure.core/comparator (fn [a b] (neg? (cmp a b))))))
  (entryKey [_ k] k)
  (seqFrom [this k ascending]
    (if ascending
      (some-> (btset-iter this) (seek k (cmp-of this)) seq)
      (when (pos? cnt)
        (let [mx (first (e/rseq (btset-iter this)))]
          (rslice this k mx (cmp-of this))))))

  clojure.lang.Reversible
  (rseq [this] (e/rseq (btset-iter this)))

  clojure.lang.ILookup
  (valAt [_ k]
    (node-lookup root (cmp-of _) k))
  (valAt [_ k not-found]
    (or (node-lookup root (cmp-of _) k) not-found))

  clojure.lang.IFn
  (invoke [this k] (e/get this k))
  (invoke [this k not-found] (e/get this k not-found))

  clojure.lang.IPersistentSet
  (disjoin [this key] (disj this key (cmp-of this)))
  (contains [_ k] (some? (node-lookup root (cmp-of _) k)))
  (get [_ k] (node-lookup root (cmp-of _) k))

  clojure.lang.IHashEq
  (hasheq [this]
    ;; PORTING.md E2 (amended by the multi-host lane): vanilla caches
    ;; cljs's hash-unordered-coll in a mutable _hash field. The port
    ;; computes it on demand via eve.core's hash-unordered-coll — the
    ;; stock Murmur3 unordered hash (sum + count mix). The first cut
    ;; used a plain sum of element hashes: equal sets hashed equal to
    ;; EACH OTHER but not to stock's number, which DataScript's
    ;; hash-fdb mixes with (hash-unordered-coll datoms) — upstream
    ;; test-filter-db caught the mismatch.
    ;; V1: (e/seq this), not core seq — core seq does not route to Eve types
    ;; on every seat (bb's SCI throws "Don't know how to create ISeq").
    ;; The empty set's seq is nil; hash-unordered-coll of nil NPEs on
    ;; the JVM seat's Murmur3, so the empty case hashes the empty seq.
    (if (zero? cnt)
      (e/hash-unordered-coll [])
      (e/hash-unordered-coll (e/seq this))))

  ;; what a deref of an atom holding it answers: a Clojure sorted set
  d/IPlain
  (-plain [this] (into (clojure.core/sorted-set) (map d/eve->clj) (e/seq this)))

  IPrintWithWriter
  (-pr-writer [this w _] (e/-write w (print-btset this)))

  java.lang.Object
  (toString [this] (print-btset this))
  (equals [this other] (.equiv this other))
  (hashCode [this] (.hasheq this)))

(defn- keys-for [set path]
  (loop [level (.-shift set)
         node  (.-root set)]
    (if (pos? level)
      (recur
        (dec level)
        (arrays/aget (.-pointers node) (path-get path level)))
      (.-keys node))))

(defn- alter-btset [set root shift cnt]
  (->BTSet root shift cnt (.-comparator set) (.-meta set)))


;; iteration

(defn- -next-path [node path level]
  (let [idx (path-get path level)]
    (if (pos? level)
      ;; inner node
      (let [sub-path (-next-path (arrays/aget (.-pointers node) idx) path (dec level))]
        (if (nil? sub-path)
          ;; nested node overflow
          (if (< (inc idx) (arrays/alength (.-pointers node)))
            ;; advance current node idx, reset subsequent indexes
            (path-set empty-path level (inc idx))
            ;; current node overflow
            nil)
          ;; keep current idx
          (path-set sub-path level idx)))
      ;; leaf
      (if (< (inc idx) (arrays/alength (.-keys node)))
        ;; advance leaf idx
        (path-set empty-path 0 (inc idx))
        ;; leaf overflow
        nil))))

(defn- -rpath
  "Returns rightmost path possible starting from node and going deeper"
  [node path level]
  (loop [node  node
         path  path
         level level]
    (if (pos? level)
      ;; inner node
      (recur
        (arrays/alast (.-pointers node))
        (path-set path level (dec (arrays/alength (.-pointers node))))
        (dec level))
      ;; leaf
      (path-set path 0 (dec (arrays/alength (.-keys node)))))))

(defn- next-path
  "Returns path representing next item after `path` in natural traversal order.
   Will overflow at leaf if at the end of the tree"
  [set path]
  (if (neg? path)
    empty-path
    (or
      (-next-path (.-root set) path (.-shift set))
      (path-inc (-rpath (.-root set) empty-path (.-shift set))))))

(defn- -prev-path [node path level]
  (let [idx (path-get path level)]
    (cond
      ;; leaf overflow
      (and (== 0 level) (== 0 idx))
      nil

      ;; leaf
      (== 0 level)
      (path-set empty-path 0 (dec idx))

      ;; branch that was overflow before
      (>= idx (node-len node))
      (-rpath node path level)

      :else
      (let [path' (-prev-path (arrays/aget (.-pointers node) idx) path (dec level))]
        (cond
          ;; no sub-overflow, keep current idx
          (some? path')
          (path-set path' level idx)

          ;; nested overflow + this node overflow
          (== 0 idx)
          nil

          ;; nested overflow, advance current idx, reset subsequent indexes
          :else
          (let [path' (-rpath (arrays/aget (.-pointers node) (dec idx)) path (dec level))]
            (path-set path' level (dec idx))))))))

(defn- prev-path
  "Returns path representing previous item before `path` in natural traversal order.
   Will overflow at leaf if at beginning of tree"
  [set path]
  (if (> (path-get path (inc (.-shift set))) 0) ;; overflow
    (-rpath (.-root set) path (.-shift set))
    (or
      (-prev-path (.-root set) path (.-shift set))
      (path-dec empty-path))))

(declare iter riter)

(defn- btset-iter
  "Iterator that represents the whole set"
  [set]
  (when (pos? (node-len (.-root set)))
    (let [left  empty-path
          rpath (-rpath (.-root set) empty-path (.-shift set))
          right (next-path set rpath)]
      (iter set left right))))

(defprotocol IIter
  (-copy [this left right]))

(defprotocol ISeek
  (-seek
    [this key]
    [this key comparator]))

(declare -seek* -rseek*)

;; PORTING.md T3: vanilla's Chunk/IChunkedSeq (a performance device) is not
;; ported; seqs here are element-wise. Semantics unchanged.
(deftype Iter [set left right keys idx]
  IIter
  (-copy [_ l r]
    (Iter. set l r (keys-for set l) (path-get l 0)))

  clojure.lang.Seqable
  (seq [this] (when keys this))

  ;; PORTING.md T6: vanilla's JVM form is Iterable; without this group
  ;; clojure.lang.RT/iter falls back to iter(seq x) with seq x = x and
  ;; eduction/iterator consumers recurse forever (DataScript DS2).
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  clojure.lang.Sequential

  clojure.lang.ISeq
  (first [_]
    (when keys
      (arrays/aget keys idx)))

  (next [this]
    (when keys
      (if (< (inc idx) (arrays/alength keys))
        ;; can use cached array to move forward
        (let [left' (path-inc left)]
          (when (path-lt left' right)
            (Iter. set left' right keys (inc idx))))
        (let [left' (next-path set left)]
          (when (path-lt left' right)
            (-copy this left' right))))))

  (more [this]
    (or (next this) ()))

  (cons [this x]
    (clojure.core/cons x this))

  (count [this]
    ;; exact count of a bounded slice is est-count's job; ISeq count walks
    (loop [s (seq this) n 0]
      (if s (recur (next s) (inc n)) n)))

  (empty [_] ())

  (equiv [this other]
    (and (or (sequential? other) (instance? Iter other))
         (= (vec (seq this)) (vec (seq other)))))

  clojure.lang.IReduce
  (reduce [this f]
    (if (nil? keys)
      (f)
      (let [first (first this)]
        (if-some [next (next this)]
          (reduce f first next)
          first))))

  (reduce [this f start]
    (loop [left left
           keys keys
           idx  idx
           acc  start]
      (if (nil? keys)
        acc
        (let [new-acc (f acc (arrays/aget keys idx))]
          (cond
            (reduced? new-acc)
            @new-acc

            (< (inc idx) (arrays/alength keys)) ;; can use cached array to move forward
            (let [left' (path-inc left)]
              (if (path-lt left' right)
                (recur left' keys (inc idx) new-acc)
                new-acc))

            :else
            (let [left' (next-path set left)]
              (if (path-lt left' right)
                (recur left' (keys-for set left') (path-get left' 0) new-acc)
                new-acc)))))))

  clojure.lang.Reversible
  (rseq [this]
    (when keys
      (riter set (prev-path set left) (prev-path set right))))

  ISeek
  (-seek [this key]
    (-seek this key (cmp-of set)))

  (-seek [this key cmp]
    (cond
      (nil? key)
      (throw (ex-info "seek can't be called with a nil key!" {:key key}))

      (nat-int? (cmp (arrays/aget keys idx) key))
      this

      :else
      (when-some [left' (-seek* set key cmp)]
        (Iter. set left' right (keys-for set left') (path-get left' 0)))))

  clojure.lang.IHashEq
  (hasheq [this]
    ;; F-LPY-ITER-HASH (pinned by Mac DISPATCH #57): Iter/ReverseIter
    ;; carried equiv but no hash group. The JVM seat silently falls back
    ;; to identity hashCode; basilisp (Python) makes an __eq__-bearing
    ;; class without __hash__ unhashable, so DataScript's query engine
    ;; died hashing a Relation whose :tuples is a slice Iter
    ;; (TypeError: unhashable type: 'Iter'). Ordered seq hash,
    ;; consistent with equiv (which compares (vec (seq this))): equal
    ;; Iters hash equal. Twins BTSet's IHashEq group (unordered there,
    ;; set semantics; ordered here, seq semantics). V1: (e/seq this),
    ;; not core seq (see BTSet's note); the exhausted case hashes the
    ;; empty seq (hash-ordered-coll of nil NPEs on the JVM seat).
    (if (nil? keys)
      (e/hash-ordered-coll [])
      (e/hash-ordered-coll (e/seq this))))

  java.lang.Object
  (toString [this] (pr-str this))
  (hashCode [this] (.hasheq this)))

(defn iter [set left right]
  (Iter. set left right (keys-for set left) (path-get left 0)))

;; reverse iteration

(deftype ReverseIter [set left right keys idx]
  IIter
  (-copy [_ l r]
    (ReverseIter. set l r (keys-for set r) (path-get r 0)))

  clojure.lang.Seqable
  (seq [this] (when keys this))

  ;; PORTING.md T6 (same as Iter): Iterable for RT/iter consumers.
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  clojure.lang.Sequential

  clojure.lang.ISeq
  (first [_]
    (when keys
      (arrays/aget keys idx)))

  (next [this]
    (when keys
      (if (> idx 0)
        ;; can use cached array to advance
        (let [right' (path-dec right)]
          (when (path-lt left right')
            (ReverseIter. set left right' keys (dec idx))))
        (let [right' (prev-path set right)]
          (when (path-lt left right')
            (-copy this left right'))))))

  (more [this]
    (or (next this) ()))

  (cons [this x]
    (clojure.core/cons x this))

  (count [this]
    (loop [s (seq this) n 0]
      (if s (recur (next s) (inc n)) n)))

  (empty [_] ())

  (equiv [this other]
    (and (or (sequential? other) (instance? ReverseIter other))
         (= (vec (seq this)) (vec (seq other)))))

  clojure.lang.Reversible
  (rseq [this]
    (when keys
      (iter set (next-path set left) (next-path set right))))

  ISeek
  (-seek [this key]
    (-seek this key (cmp-of set)))

  (-seek [this key cmp]
    (cond
      (nil? key)
      (throw (ex-info "seek can't be called with a nil key!" {:key key}))

      (nat-int? (cmp key (arrays/aget keys idx)))
      this

      :else
      (let [right' (prev-path set (-rseek* set key cmp))]
        (when (and
                (nat-int? right')
                (path-lte left right')
                (path-lt  right' right))
          (ReverseIter. set left right' (keys-for set right') (path-get right' 0))))))

  clojure.lang.IHashEq
  (hasheq [this]
    ;; F-LPY-ITER-HASH (pinned by Mac DISPATCH #57): Iter/ReverseIter
    ;; carried equiv but no hash group. The JVM seat silently falls back
    ;; to identity hashCode; basilisp (Python) makes an __eq__-bearing
    ;; class without __hash__ unhashable, so DataScript's query engine
    ;; died hashing a Relation whose :tuples is a slice Iter
    ;; (TypeError: unhashable type: 'Iter'). Ordered seq hash,
    ;; consistent with equiv (which compares (vec (seq this))): equal
    ;; Iters hash equal. Twins BTSet's IHashEq group (unordered there,
    ;; set semantics; ordered here, seq semantics). V1: (e/seq this),
    ;; not core seq (see BTSet's note); the exhausted case hashes the
    ;; empty seq (hash-ordered-coll of nil NPEs on the JVM seat).
    (if (nil? keys)
      (e/hash-ordered-coll [])
      (e/hash-ordered-coll (e/seq this))))

  java.lang.Object
  (toString [this] (pr-str this))
  (hashCode [this] (.hasheq this)))

(defn riter [set left right]
  (ReverseIter. set left right (keys-for set right) (path-get right 0)))

;; distance

(defn- -distance [node left right level]
  (let [idx-l (path-get left level)
        idx-r (path-get right level)]
    (if (pos? level)
      ;; inner node
      (if (== idx-l idx-r)
        (-distance (arrays/aget (.-pointers node) idx-l) left right (dec level))
        (loop [level level
               res   (- idx-r idx-l)]
          (if (== 0 level)
            res
            (recur (dec level) (* res avg-len)))))
      (- idx-r idx-l))))

(defn- distance [set path-l path-r]
  (cond
    (path-eq path-l path-r)
    0

    (path-eq (path-inc path-l) path-r)
    1

    (path-eq (next-path set path-l) path-r)
    1

    :else
    (-distance (.-root set) path-l path-r (.-shift set))))

(defn est-count [iter]
  (distance (.-set iter) (.-left iter) (.-right iter)))


;; Slicing

(defn- -seek*
  "Returns path to first element >= key,
   or -1 if all elements in a set < key"
  [set key comparator]
  (if (nil? key)
    empty-path
    (loop [node  (.-root set)
           path  empty-path
           level (.-shift set)]
      (let [keys-l (node-len node)]
        (if (== 0 level)
          (let [keys (.-keys node)
                idx  (binary-search-l comparator keys (dec keys-l) key)]
            (if (== keys-l idx)
              nil
              (path-set path 0 idx)))
          (let [keys (.-keys node)
                idx  (binary-search-l comparator keys (- keys-l 2) key)]
            (recur
              (arrays/aget (.-pointers node) idx)
              (path-set path level idx)
              (dec level))))))))

(defn- -rseek*
  "Returns path to the first element that is > key.
   If all elements in a set are <= key, returns `(-rpath set) + 1`.
   It’s a virtual path that is bigger than any path in a tree"
  [set key comparator]
  (if (nil? key)
    (path-inc (-rpath (.-root set) empty-path (.-shift set)))
    (loop [node  (.-root set)
           path  empty-path
           level (.-shift set)]
      (let [keys-l (node-len node)]
        (if (== 0 level)
          (let [keys (.-keys node)
                idx  (binary-search-r comparator keys (dec keys-l) key)
                res  (path-set path 0 idx)]
            res)
          (let [keys (.-keys node)
                idx  (binary-search-r comparator keys (- keys-l 2) key)
                res  (path-set path level idx)]
            (recur
              (arrays/aget (.-pointers node) idx)
              res
              (dec level))))))))

(defn- -slice [set key-from key-to comparator]
  (when-some [path (-seek* set key-from comparator)]
    (let [till-path (-rseek* set key-to comparator)]
      (when (path-lt path till-path)
        (Iter. set path till-path (keys-for set path) (path-get path 0))))))

(defn- arr-map-inplace [f arr]
  (mapv f arr))


(defn- arr-partition-approx
  "Splits `arr` into arrays of size between min-len and max-len,
   trying to stick to (min+max)/2"
  [min-len max-len arr]
  (let [chunk-len avg-len
        len       (arrays/alength arr)]
    (when (pos? len)
      (loop [pos 0
             acc []]
        (let [rest (- len pos)]
          (cond
            (<= rest max-len)
              (clojure.core/conj acc (arrays/aslice arr pos))
            (>= rest (+ chunk-len min-len))
              (recur (+ pos chunk-len)
                     (clojure.core/conj acc (arrays/aslice arr pos (+ pos chunk-len))))
            :else
              (let [piece-len (arrays/half rest)]
                (recur (+ pos piece-len)
                       (clojure.core/conj acc (arrays/aslice arr pos (+ pos piece-len)))))))))))


(defn- sorted-arr-distinct? [arr cmp]
  (let [al (arrays/alength arr)]
    (if (<= al 1)
      true
      (loop [i 1
             p (arrays/aget arr 0)]
        (if (>= i al)
          true
          (let [e (arrays/aget arr i)]
            (if (== 0 (cmp e p))
              false
              (recur (inc i) e))))))))


(defn- sorted-arr-distinct
  "Filter out repetitive values in a sorted array.
   Optimized for no-duplicates case"
  [arr cmp]
  (if (sorted-arr-distinct? arr cmp)
    arr
    (let [al (arrays/alength arr)]
      (loop [acc (transient [(arrays/aget arr 0)])
             i   1
             p   (arrays/aget arr 0)]
        (if (>= i al)
          (vec (persistent! acc))
          (let [e (arrays/aget arr i)]
            (if (== 0 (cmp e p))
              (recur acc (inc i) e)
              (recur (conj! acc e) (inc i) e))))))))


;; Public interface

(defn conj
  "Analogue to [[clojure.core/conj]] but with comparator that overrides the one stored in set."
  [set key cmp]
  (let [roots (node-conj (.-root set) cmp key)]
    (cond
      ;; tree not changed
      (nil? roots)
        set

      ;; keeping single root
      (== (arrays/alength roots) 1)
        (alter-btset set
          (arrays/aget roots 0)
          (.-shift set)
          (inc (.-cnt set)))

      ;; introducing new root
      :else
        (alter-btset set
          (->Node (arrays/amap node-lim-key roots) roots)
          (inc (.-shift set))
          (inc (.-cnt set))))))


(defn disj
  "Analogue to [[clojure.core/disj]] with comparator that overrides the one stored in set."
  [set key cmp]
  (let [new-roots (node-disj (.-root set) cmp key true nil nil)]
    (if (nil? new-roots) ;; nothing changed, key wasn't in the set
      set
      (let [new-root (arrays/aget new-roots 0)]
        (if (and (instance? Node new-root)
                 (== 1 (arrays/alength (.-pointers new-root))))

          ;; root has one child, make him new root
          (alter-btset set
            (arrays/aget (.-pointers new-root) 0)
            (dec (.-shift set))
            (dec (.-cnt set)))

          ;; keeping root level
          (alter-btset set
            new-root
            (.-shift set)
            (dec (.-cnt set))))))))


(defn slice
  "An iterator for part of the set with provided boundaries.
   `(slice set from to)` returns iterator for all Xs where from <= X <= to.
   Optionally pass in comparator that will override the one that set uses. Supports efficient [[clojure.core/rseq]]."
  ([set key-from key-to]
    (-slice set key-from key-to (cmp-of set)))
  ([set key-from key-to comparator]
    (-slice set key-from key-to comparator)))


(defn rslice
  "A reverse iterator for part of the set with provided boundaries.
   `(rslice set from to)` returns backwards iterator for all Xs where from <= X <= to.
   Optionally pass in comparator that will override the one that set uses. Supports efficient [[clojure.core/rseq]]."
  ([set key]
    (some-> (-slice set key key (cmp-of set)) rseq))
  ([set key-from key-to]
    (some-> (-slice set key-to key-from (cmp-of set)) rseq))
  ([set key-from key-to comparator]
    (some-> (-slice set key-to key-from comparator) rseq)))


(defn seek
  "An efficient way to seek to a specific key in a seq (either returned by [[clojure.core.seq]] or a slice.)
   `(seek (seq set) to)` returns iterator for all Xs where to <= X.
   Optionally pass in comparator that will override the one that set uses."
  ([seq to]
   (-seek seq to))
  ([seq to cmp]
   (-seek seq to cmp)))


(defn- normalize-cmp
  "PORTING.md E1: the default comparator is stored as nil."
  [cmp]
  (when-not (identical? cmp compare) cmp))


(defn from-sorted-array
  "Fast path to create a set if you already have a sorted array of elements on your hands."
  ([cmp arr]
   (from-sorted-array cmp arr (arrays/alength arr) {}))
  ([cmp arr _len]
   (from-sorted-array cmp arr _len {}))
  ([cmp arr _len _opts]
   ;; 0.3.1 arities. _len is advisory (the array's own length is read);
   ;; _opts (storage settings in the full 0.3.x line) has no Eve analogue
   ;; and is accepted-and-ignored, as the 0.3.1 CLJS form ignores it.
   (let [cmp    (normalize-cmp cmp)
        leaves (->> arr
                   (arr-partition-approx min-len max-len)
                   (arr-map-inplace #(->Leaf %)))]
    (loop [current-level leaves
           shift 0]
      (case (count current-level)
        0 (->BTSet (->Leaf (arrays/array)) 0 0 cmp nil)
        1 (->BTSet (first current-level) shift (arrays/alength arr) cmp nil)
        (recur
          (->> current-level
            (arr-partition-approx min-len max-len)
            (arr-map-inplace #(->Node (arrays/amap node-lim-key %) %)))
          (inc shift)))))))


(defn from-sequential
  "Create a set with custom comparator and a collection of keys. Useful when you don’t want to call [[clojure.core/apply]] on [[sorted-set-by]]."
  [cmp seq]
  (let [arr (-> (arrays/into-array seq) (arrays/asort cmp) (sorted-arr-distinct cmp))]
    (from-sorted-array cmp arr)))


(defn sorted-set-by
  ([cmp] (->BTSet (->Leaf (arrays/array)) 0 0 (normalize-cmp cmp) nil))
  ([cmp & keys] (from-sequential cmp keys)))


(defn sorted-set
  ([] (sorted-set-by compare))
  ([& keys] (from-sequential compare keys)))


(defn sorted-set*
  "Create a set with custom comparator, metadata and settings.
   0.3.1 addition. opts: :cmp (fn, or a registered comparator keyword —
   see register-comparator!), :meta. Other 0.3.x opts (storage settings)
   have no Eve analogue and are ignored."
  [opts]
  (->BTSet (->Leaf (arrays/array)) 0 0 (normalize-cmp (or (:cmp opts) compare)) (:meta opts)))


(defn settings
  "The set's construction settings. 0.3.1 addition; the map shape is
   vanilla's CLJS answer."
  [_set]
  {:branching-factor max-len
   :ref-type        :strong})
