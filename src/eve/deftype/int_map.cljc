(ns eve.deftype.int-map
  "An adaptation of Okasaki and Gill's \"Fast Mergeable Integer Maps\",
   ported from clojure.data.int-map to eve memory — one definition for every
   eve host. A 16-way branching PATRICIA trie with 32-bit integer keys: its
   nodes are eve-deftypes, a branch holding its children's offsets, and a
   child read back through eve's type registry (the block's type-id byte names
   which of the three node types it is), so a node reads only the children a
   lookup walks.

   The user-facing map answers eve.core's vocabulary on every host (and
   clojure.core's where the host's types carry its collection interfaces:
   the JVM, ClojureScript, nbb, ...):
     (int-map)              ;; empty
     (int-map 1 :a 2 :b)    ;; from keys and values
     (e/assoc m 42 :x) (e/dissoc m 42) (e/get m 42) (e/count m) (e/seq m)
     (merge a b) (merge-with f a b) (update m k f) (range m lo hi)

   Nodes allocate in the running swap!'s domain, else this process's own; an
   int-map is an eve value (it lives in an atom like any other)."
  (:refer-clojure :exclude [merge merge-with update range deftype])
  (:require
   [eve.deftype :refer [deftype eve-deftype]]
   [eve.core :as e]
   [eve.host]
   [eve.encoding :as enc]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]))

;; a value no map holds, identical? only to itself (a keyword is not
;; identical? to itself across ClojureScript call sites); made on first use
;; (clojerl's def takes literal values only)
(eve.host/defdelay ^:private not-found (eve.host/token))

;;-----------------------------------------------------------------------------
;; The node protocol, and each node type's fields (read through protocols on
;; every host: bb's eve types answer no ILookup)
;;-----------------------------------------------------------------------------

(defprotocol IIntMapNode
  (-im-count [node] "Element count")
  (-im-assoc [node k epoch merge-fn v] "Insert or update k→v")
  (-im-dissoc [node k epoch] "Remove k; nil when nothing remains")
  (-im-get [node k default-val] "The value at k, default-val if absent")
  (-im-update [node k epoch f] "Update the value at k with (f old-val)")
  (-im-merge [node other epoch f] "Merge with another node")
  (-im-reduce [node f init] "Reduce over [k v] entries")
  (-im-kvreduce [node f init] "Reduce with (f acc k v)")
  (-im-seq [node] "A seq of map entries")
  (-im-seq-reverse [node] "A reversed seq of map entries")
  (-im-range [node min-k max-k] "The node filtered to [min-k, max-k]"))

(defprotocol ILeaf
  (-leaf-key [leaf])
  (-leaf-val [leaf]))

(defprotocol IBranch
  (-prefix [branch])
  (-bit-offset [branch])
  (-epoch [branch])
  (-child-off [branch i] "The offset of child i (-1: none)"))

(defprotocol IBinary
  (-neg-off [bb] "The offset of the child for negative keys (-1: none)")
  (-pos-off [bb] "The offset of the child for non-negative keys (-1: none)"))

(defprotocol IIntMap
  (-root [m] "The root node, or nil")
  (-map-epoch [m])
  (-map-meta [m]))

;;-----------------------------------------------------------------------------
;; Bitwise helpers (32-bit keys)
;;-----------------------------------------------------------------------------

(defn- lowest-bit
  "Isolate the lowest set bit."
  [n]
  (bit-and n (- n)))

(defn- highest-bit
  "The highest set bit of n (n non-negative), at or above estimate."
  [n estimate]
  (loop [x (bit-and n (bit-not (dec estimate)))]
    (let [m (lowest-bit x)]
      (if (== x m)
        m
        (recur (- x m))))))

(defn- bit-log2
  "Log base 2 of a power of 2 (0 for 0 and 1)."
  [n]
  (loop [n n i 0]
    (if (<= n 1) i (recur (bit-shift-right n 1) (inc i)))))

(defn- branching-offset
  "The 4-bit-aligned offset where keys a and b (of one sign) diverge."
  [a b]
  (bit-and (bit-log2 (highest-bit (bit-xor a b) 1)) (bit-not 0x3)))

(defn- invert-fn
  "f with its arguments swapped: (f a b) -> (f b a)."
  [f]
  (fn [a b] (f b a)))

(defn default-merge
  "Default merge function — last value wins."
  [_ x] x)

;;-----------------------------------------------------------------------------
;; Nodes in eve memory, and reading one back
;;-----------------------------------------------------------------------------

(declare leaf-assoc leaf-update
         branch-count branch-get branch-assoc branch-dissoc branch-update branch-merge
         branch-reduce branch-kvreduce branch-seq branch-seq-reverse branch-range
         bin-count bin-get bin-assoc bin-dissoc bin-update bin-merge bin-reduce
         bin-kvreduce bin-seq bin-seq-reverse bin-range)

;; Layout: [type-id:1][name-hash:3][key:4][value-offset:4]
(eve-deftype IntMapLeaf [^:int32 key value]
             ILeaf
             (-leaf-key [_] key)
             (-leaf-val [_] value)

             IIntMapNode
             (-im-count [_] 1)
             (-im-get [_ k default-val] (if (== k key) value default-val))
             (-im-assoc [this k epoch merge-fn v] (leaf-assoc this k epoch merge-fn v))
             (-im-dissoc [this k _epoch] (if (== k key) nil this))
             (-im-update [this k epoch f] (leaf-update this k epoch f))
             (-im-merge [_ other epoch f] (-im-assoc other key epoch (invert-fn f) value))
             (-im-reduce [_ f init] (f init (enc/map-entry key value)))
             (-im-kvreduce [_ f init] (f init key value))
             (-im-seq [_] (list (enc/map-entry key value)))
             (-im-seq-reverse [_] (list (enc/map-entry key value)))
             (-im-range [this min-k max-k] (when (and (<= min-k key) (<= key max-k)) this)))

;; Layout: [type-id:1][name-hash:3][prefix:4][bit-offset:4][epoch:4][c0:4]...[c15:4]
;; (a child is a node's offset, -1 for none: ^:eve/pointer, so the retire walk follows it)
(eve-deftype IntMapBranch
             [^:int32 prefix ^:int32 bit-offset ^:int32 epoch
              ^:int32 ^:eve/pointer c0 ^:int32 ^:eve/pointer c1 ^:int32 ^:eve/pointer c2 ^:int32 ^:eve/pointer c3
              ^:int32 ^:eve/pointer c4 ^:int32 ^:eve/pointer c5 ^:int32 ^:eve/pointer c6 ^:int32 ^:eve/pointer c7
              ^:int32 ^:eve/pointer c8 ^:int32 ^:eve/pointer c9 ^:int32 ^:eve/pointer c10 ^:int32 ^:eve/pointer c11
              ^:int32 ^:eve/pointer c12 ^:int32 ^:eve/pointer c13 ^:int32 ^:eve/pointer c14
              ^:int32 ^:eve/pointer c15]
             IBranch
             (-prefix [_] prefix)
             (-bit-offset [_] bit-offset)
             (-epoch [_] epoch)
             (-child-off [_ i]
                         (case (int i)
                           0 c0 1 c1 2 c2 3 c3 4 c4 5 c5 6 c6 7 c7
                           8 c8 9 c9 10 c10 11 c11 12 c12 13 c13 14 c14 15 c15))

             IIntMapNode
             (-im-count [this] (branch-count this))
             (-im-get [this k default-val] (branch-get this k default-val))
             (-im-assoc [this k epoch merge-fn v] (branch-assoc this k epoch merge-fn v))
             (-im-dissoc [this k epoch] (branch-dissoc this k epoch))
             (-im-update [this k epoch f] (branch-update this k epoch f))
             (-im-merge [this other epoch f] (branch-merge this other epoch f))
             (-im-reduce [this f init] (branch-reduce this f init))
             (-im-kvreduce [this f init] (branch-kvreduce this f init))
             (-im-seq [this] (branch-seq this))
             (-im-seq-reverse [this] (branch-seq-reverse this))
             (-im-range [this min-k max-k] (branch-range this min-k max-k)))

;; The top-level split for negative / non-negative keys.
;; Layout: [type-id:1][name-hash:3][neg-off:4][pos-off:4]
(eve-deftype IntMapBinaryBranch [^:int32 ^:eve/pointer neg-off ^:int32 ^:eve/pointer pos-off]
             IBinary
             (-neg-off [_] neg-off)
             (-pos-off [_] pos-off)

             IIntMapNode
             (-im-count [this] (bin-count this))
             (-im-get [this k default-val] (bin-get this k default-val))
             (-im-assoc [this k epoch merge-fn v] (bin-assoc this k epoch merge-fn v))
             (-im-dissoc [this k epoch] (bin-dissoc this k epoch))
             (-im-update [this k epoch f] (bin-update this k epoch f))
             (-im-merge [this other epoch f] (bin-merge this other epoch f))
             (-im-reduce [this f init] (bin-reduce this f init))
             (-im-kvreduce [this f init] (bin-kvreduce this f init))
             (-im-seq [this] (bin-seq this))
             (-im-seq-reverse [this] (bin-seq-reverse this))
             (-im-range [this min-k max-k] (bin-range this min-k max-k)))

(defn- sio-of
  "The slab I/O a node reads its children through: its own (a node read from
   an atom's domain reads that domain), else the current one."
  [node]
  (or (d/-eve-sio node) (alloc/current-sio)))

(defn- off-of
  "A node's offset (its type's T-offset), or -1 for nil."
  [node]
  (cond
    (nil? node) -1
    (instance? IntMapLeaf node) (IntMapLeaf-offset node)
    (instance? IntMapBranch node) (IntMapBranch-offset node)
    :else (IntMapBinaryBranch-offset node)))

(defn- load-node
  "The node at offset off of sio, or nil for -1: read back as the type eve's
   registry names by the block's type-id byte (and name hash)."
  [sio off]
  (when-not (== -1 off)
    ((ser/get-header-constructor (slab/read-u8 sio off 0)) sio off)))

;;-----------------------------------------------------------------------------
;; Branch children
;;-----------------------------------------------------------------------------

(defn- branch-index-of
  "The child index (0-15) for key k within a branch."
  [branch k]
  (bit-and (bit-shift-right k (-bit-offset branch)) 0xf))

(defn- branch-child
  "Child i of a branch, or nil."
  [branch i]
  (load-node (sio-of branch) (-child-off branch i)))

(defn- children
  "A branch's 16 child offsets, as a vector."
  [branch]
  (mapv #(-child-off branch %) (clojure.core/range 16)))

(def ^:private no-children (vec (repeat 16 -1)))

(defn- make-branch
  "A new branch over a vector of 16 child offsets."
  [prefix bit-offset epoch cs]
  (apply ->IntMapBranch prefix bit-offset epoch cs))

(defn- branch-of-two
  "A branch at bit offset boff over two nodes whose keys diverge there (a and b
   the keys that place them), built at once: building it by assoc into an
   empty branch made and dropped two branches every time a leaf split or a
   branch went in above another, and nothing freed them
   (2026-10-21 ET)."
  [prefix boff epoch a a-node b b-node]
  (make-branch prefix boff epoch
               (-> no-children
                   (assoc (bit-and (bit-shift-right a boff) 0xf) (off-of a-node))
                   (assoc (bit-and (bit-shift-right b boff) 0xf) (off-of b-node)))))

(defn- children-left
  "A branch of children cs (16 offsets), or its only child, or nil."
  [sio prefix bit-offset epoch cs]
  (let [live (filterv #(not= -1 %) cs)]
    (case (count live)
      0 nil
      1 (load-node sio (first live))
      (make-branch prefix bit-offset epoch cs))))

;;-----------------------------------------------------------------------------
;; IntMapLeaf
;;-----------------------------------------------------------------------------

(defn- leaf-assoc [leaf k epoch merge-fn v]
  (let [nk (-leaf-key leaf)]
    (cond
      (== k nk)
      (->IntMapLeaf k (if (nil? merge-fn) v (merge-fn (-leaf-val leaf) v)))

      ;; different signs: a binary branch
      (and (neg? nk) (>= k 0))
      (->IntMapBinaryBranch (off-of leaf) (off-of (->IntMapLeaf k v)))

      (and (neg? k) (>= nk 0))
      (->IntMapBinaryBranch (off-of (->IntMapLeaf k v)) (off-of leaf))

      ;; the leaf itself goes into the branch (it was copied, its value written again)
      :else
      (branch-of-two k (branching-offset k nk) epoch nk leaf k (->IntMapLeaf k v)))))

(defn- leaf-update [leaf k epoch f]
  (if (== k (-leaf-key leaf))
    (->IntMapLeaf k (f (-leaf-val leaf)))
    (-im-assoc leaf k epoch nil (f nil))))

;;-----------------------------------------------------------------------------
;; IntMapBranch
;;-----------------------------------------------------------------------------

(defn- branch-count [branch]
  (loop [i 0 cnt 0]
    (if (== i 16)
      cnt
      (let [child (branch-child branch i)]
        (recur (inc i) (if child (+ cnt (-im-count child)) cnt))))))

(defn- branch-get [branch k default-val]
  (let [child (branch-child branch (branch-index-of branch k))]
    (if (nil? child)
      default-val
      (-im-get child k default-val))))

(defn- branch-with
  "branch's children with child idx replaced by the node (f child), child
   being nil where the slot is empty; branch itself when (f child) is child."
  [branch idx epoch f]
  (let [child (branch-child branch idx)
        child' (f child)]
    (if (and (some? child) (identical? child child'))
      branch
      (make-branch (-prefix branch) (-bit-offset branch) epoch
                   (assoc (children branch) idx (off-of child'))))))

(defn- branch-assoc [branch k epoch merge-fn v]
  (let [prefix (-prefix branch)
        boff (-bit-offset branch)]
    (cond
      ;; different signs: a binary branch
      (and (neg? prefix) (>= k 0))
      (->IntMapBinaryBranch (off-of branch) (off-of (->IntMapLeaf k v)))

      (and (neg? k) (>= prefix 0))
      (->IntMapBinaryBranch (off-of (->IntMapLeaf k v)) (off-of branch))

      :else
      (let [offset' (branching-offset k prefix)]
        (if (> offset' boff)
          ;; a new branch above this one
          (branch-of-two k offset' epoch prefix branch k (->IntMapLeaf k v))
          ;; at or below this level
          (branch-with branch (branch-index-of branch k) epoch
                       (fn [child]
                         (if (nil? child)
                           (->IntMapLeaf k v)
                           (-im-assoc child k epoch merge-fn v)))))))))

(defn- branch-dissoc [branch k epoch]
  (let [idx (branch-index-of branch k)
        child (branch-child branch idx)]
    (if (nil? child)
      branch
      (let [child' (-im-dissoc child k epoch)]
        (if (identical? child child')
          branch
          (children-left (sio-of branch) (-prefix branch) (-bit-offset branch) epoch
                         (assoc (children branch) idx (off-of child'))))))))

(defn- branch-update [branch k epoch f]
  (let [prefix (-prefix branch)
        boff (-bit-offset branch)]
    (cond
      (and (neg? prefix) (>= k 0))
      (->IntMapBinaryBranch (off-of branch) (off-of (->IntMapLeaf k (f nil))))

      (and (neg? k) (>= prefix 0))
      (->IntMapBinaryBranch (off-of (->IntMapLeaf k (f nil))) (off-of branch))

      :else
      (let [offset' (branching-offset k prefix)]
        (if (> offset' boff)
          (branch-of-two k offset' epoch prefix branch k (->IntMapLeaf k (f nil)))
          (branch-with branch (branch-index-of branch k) epoch
                       (fn [child]
                         (if (nil? child)
                           (->IntMapLeaf k (f nil))
                           (-im-update child k epoch f)))))))))

(defn- branch-merge [branch other epoch f]
  (if (instance? IntMapBranch other)
    (let [other-prefix (-prefix other)
          other-boff (-bit-offset other)
          prefix (-prefix branch)
          boff (-bit-offset branch)]
      (cond
        ;; sign mismatch
        (and (neg? other-prefix) (>= prefix 0))
        (->IntMapBinaryBranch (off-of other) (off-of branch))

        (and (neg? prefix) (>= other-prefix 0))
        (->IntMapBinaryBranch (off-of branch) (off-of other))

        :else
        (let [offset' (branching-offset prefix other-prefix)]
          (cond
            ;; a higher branch over both
            (and (> offset' boff) (> offset' other-boff))
            (branch-of-two prefix offset' epoch prefix branch other-prefix other)

            ;; this one holds the other
            (> boff other-boff)
            (branch-with branch (branch-index-of branch other-prefix) epoch
                         (fn [child] (if child (-im-merge child other epoch f) other)))

            ;; the other holds this one
            (< boff other-boff)
            (-im-merge other branch epoch (invert-fn (or f default-merge)))

            ;; the same level: children pairwise
            :else
            (make-branch prefix boff epoch
                         (mapv (fn [i]
                                 (let [n (branch-child branch i)
                                       n' (branch-child other i)]
                                   (off-of (cond
                                             (nil? n) n'
                                             (nil? n') n
                                             :else (-im-merge n n' epoch f)))))
                               (clojure.core/range 16)))))))
    ;; a leaf or a binary branch: flip and merge
    (-im-merge other branch epoch (invert-fn (or f default-merge)))))

(defn- branch-reduce [branch f init]
  (loop [i 0 acc init]
    (if (or (== i 16) (reduced? acc))
      acc
      (let [child (branch-child branch i)]
        (recur (inc i) (if child (-im-reduce child f acc) acc))))))

(defn- branch-kvreduce [branch f init]
  (loop [i 0 acc init]
    (if (or (== i 16) (reduced? acc))
      acc
      (let [child (branch-child branch i)]
        (recur (inc i) (if child (-im-kvreduce child f acc) acc))))))

(defn- branch-seq [branch]
  (mapcat (fn [i] (when-let [child (branch-child branch i)] (-im-seq child)))
          (clojure.core/range 0 16)))

(defn- branch-seq-reverse [branch]
  (mapcat (fn [i] (when-let [child (branch-child branch i)] (-im-seq-reverse child)))
          (clojure.core/range 15 -1 -1)))

(defn- branch-range [branch min-k max-k]
  (let [prefix (-prefix branch)
        boff (-bit-offset branch)
        ;; this node's keyspace
        node-mask (if (< boff 28)
                    (dec (bit-shift-left 1 (+ boff 4)))
                    0x7fffffff)
        node-min (bit-and prefix (bit-not node-mask))
        node-max (bit-or prefix node-mask)]
    (cond
      ;; fully contained
      (and (<= min-k node-min) (<= node-max max-k))
      branch

      ;; no overlap
      (or (> min-k node-max) (< max-k node-min))
      nil

      ;; partial overlap: filter the children
      :else
      (let [sio (sio-of branch)
            min-i (if (<= min-k node-min) -1 (branch-index-of branch (max node-min min-k)))
            max-i (if (>= max-k node-max) 16 (branch-index-of branch (min node-max max-k)))]
        (children-left sio prefix boff (-epoch branch)
                       (mapv (fn [i]
                               (let [off (-child-off branch i)]
                                 (cond
                                   (== off -1) -1
                                   (or (< i min-i) (> i max-i)) -1      ;; outside the range
                                   (and (< min-i i) (< i max-i)) off    ;; fully inside
                                   :else (off-of (-im-range (load-node sio off) min-k max-k)))))
                             (clojure.core/range 16)))))))

;;-----------------------------------------------------------------------------
;; IntMapBinaryBranch
;;-----------------------------------------------------------------------------

(defn- neg-child [bb] (load-node (sio-of bb) (-neg-off bb)))
(defn- pos-child [bb] (load-node (sio-of bb) (-pos-off bb)))

(defn- bin-count [bb]
  (let [a (neg-child bb) b (pos-child bb)]
    (+ (if a (-im-count a) 0) (if b (-im-count b) 0))))

(defn- bin-get [bb k default-val]
  (let [child (if (neg? k) (neg-child bb) (pos-child bb))]
    (if child (-im-get child k default-val) default-val)))

(defn- bin-with
  "bb with the child for keys of k's sign replaced by (f child) (child nil
   where there is none); bb itself when (f child) is child."
  [bb k f]
  (if (neg? k)
    (let [a (neg-child bb) a' (f a)]
      (if (and a (identical? a a'))
        bb
        (->IntMapBinaryBranch (off-of a') (-pos-off bb))))
    (let [b (pos-child bb) b' (f b)]
      (if (and b (identical? b b'))
        bb
        (->IntMapBinaryBranch (-neg-off bb) (off-of b'))))))

(defn- bin-assoc [bb k epoch merge-fn v]
  (bin-with bb k (fn [child]
                   (if child (-im-assoc child k epoch merge-fn v) (->IntMapLeaf k v)))))

(defn- bin-update [bb k epoch f]
  (bin-with bb k (fn [child]
                   (if child (-im-update child k epoch f) (->IntMapLeaf k (f nil))))))

(defn- bin-dissoc [bb k epoch]
  (if (neg? k)
    (let [a (neg-child bb)
          a' (when a (-im-dissoc a k epoch))]
      (cond
        (nil? a') (pos-child bb)
        (identical? a a') bb
        :else (->IntMapBinaryBranch (off-of a') (-pos-off bb))))
    (let [b (pos-child bb)
          b' (when b (-im-dissoc b k epoch))]
      (cond
        (nil? b') (neg-child bb)
        (identical? b b') bb
        :else (->IntMapBinaryBranch (-neg-off bb) (off-of b'))))))

(defn- bin-merge [bb other epoch f]
  (cond
    (instance? IntMapBinaryBranch other)
    (let [a (neg-child bb) a2 (neg-child other)
          b (pos-child bb) b2 (pos-child other)]
      (->IntMapBinaryBranch
       (off-of (cond (nil? a) a2 (nil? a2) a :else (-im-merge a a2 epoch f)))
       (off-of (cond (nil? b) b2 (nil? b2) b :else (-im-merge b b2 epoch f)))))

    (instance? IntMapBranch other)
    (if (neg? (-prefix other))
      (let [a (neg-child bb)]
        (->IntMapBinaryBranch (off-of (if a (-im-merge a other epoch f) other)) (-pos-off bb)))
      (let [b (pos-child bb)]
        (->IntMapBinaryBranch (-neg-off bb) (off-of (if b (-im-merge b other epoch f) other)))))

    ;; a leaf: delegate
    :else
    (-im-merge other bb epoch (invert-fn (or f default-merge)))))

(defn- bin-reduce [bb f init]
  (let [a (neg-child bb)
        acc (if a (-im-reduce a f init) init)]
    (if (reduced? acc)
      acc
      (let [b (pos-child bb)]
        (if b (-im-reduce b f acc) acc)))))

(defn- bin-kvreduce [bb f init]
  (let [a (neg-child bb)
        acc (if a (-im-kvreduce a f init) init)]
    (if (reduced? acc)
      acc
      (let [b (pos-child bb)]
        (if b (-im-kvreduce b f acc) acc)))))

(defn- bin-seq [bb]
  (let [a (neg-child bb) b (pos-child bb)]
    (concat (when a (-im-seq a)) (when b (-im-seq b)))))

(defn- bin-seq-reverse [bb]
  (let [a (neg-child bb) b (pos-child bb)]
    (concat (when b (-im-seq-reverse b)) (when a (-im-seq-reverse a)))))

(defn- bin-range [bb min-k max-k]
  (if (> max-k 0)
    (if (>= min-k 0)
      ;; non-negative keys only
      (let [b (pos-child bb)]
        (when b (-im-range b min-k max-k)))
      ;; both
      (let [a (neg-child bb)
            b (pos-child bb)
            a' (when a (-im-range a min-k max-k))
            b' (when b (-im-range b min-k max-k))]
        (cond
          (and (nil? a') (nil? b')) nil
          (nil? a') b'
          (nil? b') a'
          :else (->IntMapBinaryBranch (off-of a') (off-of b')))))
    ;; negative keys only
    (let [a (neg-child bb)]
      (when a (-im-range a min-k max-k)))))

;;-----------------------------------------------------------------------------
;; PersistentIntMap — the map, an eve value
;;-----------------------------------------------------------------------------

(declare im-lookup im-assoc im-dissoc im-conj im-equiv im-hash print-int-map ->TransientIntMap retire-diff)

(eve-deftype PersistentIntMap [^:int32 ^:eve/pointer root-off ^:int32 epoch mta]
             IIntMap
             (-root [this] (load-node (sio-of this) root-off))
             (-map-epoch [_] epoch)
             (-map-meta [_] mta)

             clojure.lang.IMeta
             (meta [_] mta)

             clojure.lang.IObj
             (withMeta [_ m] (->PersistentIntMap root-off epoch m))

             clojure.lang.Counted
             (count [this] (let [root (-root this)] (if root (-im-count root) 0)))

             clojure.lang.ILookup
             (valAt [this k] (im-lookup this k nil))
             (valAt [this k not-found] (im-lookup this k not-found))

             clojure.lang.Associative
             (containsKey [this k] (not (identical? @not-found (im-lookup this k @not-found))))
             (assoc [this k v] (im-assoc this k v))

             clojure.lang.IPersistentMap
             (without [this k] (im-dissoc this k))
             (entryAt [this k]
                      (let [v (im-lookup this k @not-found)]
                        (when-not (identical? v @not-found) (enc/map-entry k v))))
             (assocEx [this k v] (im-assoc this k v))

             clojure.lang.IPersistentCollection
             (cons [this o] (im-conj this o))
             (empty [_] (->PersistentIntMap -1 0 nil))
             (equiv [this other] (im-equiv this other))

             clojure.lang.Seqable
             (seq [this] (let [root (-root this)] (when root (clojure.core/seq (-im-seq root)))))

             clojure.lang.Reversible
             (rseq [this] (let [root (-root this)] (when root (clojure.core/seq (-im-seq-reverse root)))))

             clojure.lang.IFn
             (invoke [this k] (im-lookup this k nil))
             (invoke [this k not-found] (im-lookup this k not-found))

             clojure.lang.IKVReduce
             (kvreduce [this f init]
                       (let [root (-root this)
                             result (if root (-im-kvreduce root f init) init)]
                         (if (reduced? result) @result result)))

             clojure.lang.IReduceInit
             (reduce [this f init]
                     (let [root (-root this)
                           result (if root (-im-reduce root f init) init)]
                       (if (reduced? result) @result result)))

             clojure.lang.IHashEq
             (hasheq [this] (im-hash this))

             clojure.lang.IEditableCollection
             (asTransient [this] (->TransientIntMap this (alloc/scope-sio (alloc/current-sio)) true))

             d/IRetireDiff
             (-retire-diff [this new] (retire-diff this new))

  ;; what a deref of an atom holding it answers: a Clojure map
             d/IPlain
             (-plain [this] (into {} (map (fn [kv] [(key kv) (d/eve->clj (val kv))])) (e/seq this)))

  ;; through the instance: on a host whose printer is a print-method, this
  ;; body runs outside the type, where the fields are not
             IPrintWithWriter
             (-pr-writer [this w _] (e/-write w (print-int-map this)))

             java.lang.Object
             (toString [this] (print-int-map this))
             (equals [this other] (im-equiv this other))
             (hashCode [this] (im-hash this)))

(defn- im-lookup [m k not-found]
  (let [root (-root m)]
    (if root (-im-get root (int k) not-found) not-found)))

(defn- im-assoc [m k v]
  (let [k (int k)
        epoch' (inc (-map-epoch m))
        root (-root m)
        root' (if root (-im-assoc root k epoch' default-merge v) (->IntMapLeaf k v))]
    (->PersistentIntMap (off-of root') epoch' (-map-meta m))))

(defn- im-dissoc [m k]
  (let [k (int k)
        epoch' (inc (-map-epoch m))
        root (-root m)
        root' (when root (-im-dissoc root k epoch'))]
    (->PersistentIntMap (off-of root') epoch' (-map-meta m))))

(declare chain)

(defn- im-conj [m o]
  (if (map? o)
    (chain (fn [m kv] (im-assoc m (key kv) (val kv))) (constantly m) (e/seq o))
    (im-assoc m (e/nth o 0) (e/nth o 1))))

(defn- im-equiv [m other]
  (and (or (map? other) (instance? PersistentIntMap other))
       (== (e/count m) (e/count other))
       (every? (fn [kv] (= (val kv) (e/get other (key kv) @not-found)))
               (e/seq m))))

(defn- im-hash
  "The hash of a map with the same entries: the host's hash of a host map
   holding them, what = with such a map needs. Not enc/map-hash-code, the
   host's object hash of a map: on the JVM's adapter that is
   APersistentMap/mapHash, which takes a map, not its entries (census D3).
   Not hash-unordered-coll over the entries either: basilisk's map hash is
   not that."
  [m]
  (hash (into {} (map (fn [kv] [(key kv) (val kv)])) (e/seq m))))

(defn- print-int-map [m]
  (str "#eve/IntMap" (pr-str (into {} (map (fn [kv] [(key kv) (val kv)])) (e/seq m)))))

;;-----------------------------------------------------------------------------
;; The walk: the blocks one version holds that another does not
;;-----------------------------------------------------------------------------
;; A PATRICIA trie has one shape for its keys: a leaf sits at its key's place,
;; a branch at its prefix's and bit offset's, the binary branch at the root.
;; So a block of the old tree is in the new one only at its own place there:
;; the walk descends the new tree by the old node's key bits to that place,
;; and where it finds the same block the subtree is shared; anywhere else the
;; old node is the old tree's alone, and its children are looked for the same
;; way — what changed, times the depth.

(def ^:private LEAF_VALUE_OFFSET 8)  ;; IntMapLeaf [type:4][key:4][value:4]
(def ^:private MAP_MTA_OFFSET 12)    ;; PersistentIntMap [type:4][root-off:4][epoch:4][mta:4]

(defn- same-block?
  "An offset as the allocator answered it and as a field read it back (sign-
   extended from class 4 up) name one block alike."
  [a b]
  (== (bit-and a 0xFFFFFFFF) (bit-and b 0xFFFFFFFF)))

(defn- same-region?
  "True when m is a branch over the same keys as branch o: the same bit offset, the same prefix above it."
  [o m]
  (and (instance? IntMapBranch m)
       (== (-bit-offset m) (-bit-offset o))
       (let [boff (-bit-offset o)
             mask (if (< boff 28) (bit-not (dec (bit-shift-left 1 (+ boff 4)))) 0)]
         (== (bit-and (-prefix m) mask) (bit-and (-prefix o) mask)))))

(defn- place-in
  "The node tree n holds at old node o's place, reached through the branches
   above o's level by o's key (a leaf's key, a branch's prefix)."
  [n o]
  (let [leaf? (instance? IntMapLeaf o)
        bin? (instance? IntMapBinaryBranch o)
        k (cond leaf? (-leaf-key o) bin? 0 :else (-prefix o))
        b (if (or leaf? bin?) -1 (-bit-offset o))]
    (loop [m n]
      (cond
        (nil? m) nil
        (instance? IntMapBinaryBranch m) (if bin? m (recur (if (neg? k) (neg-child m) (pos-child m))))
        bin? nil
        (instance? IntMapLeaf m) m
        (> (-bit-offset m) b) (recur (branch-child m (branch-index-of m k)))
        :else m))))

(defn- node-diff
  "acc, [blocks values], with the blocks of old node o's subtree that tree n
   does not hold — each node, and a leaf's value block — and in values the
   [old new] value blocks of each such leaf (new: the value block of n's leaf
   at its key, -1 when there is none), for a caller that diffs a value that
   is a collection."
  [acc o n]
  (if (nil? o)
    acc
    (let [m (place-in n o)
          off (off-of o)]
      (if (and (some? m) (same-block? (off-of m) off))
        acc
        (let [[blocks values] acc
              acc [(conj blocks off) values]]
          (cond
            (instance? IntMapLeaf o)
            (let [v (slab/read-i32 (sio-of o) off LEAF_VALUE_OFFSET)
                  nv (if (and (instance? IntMapLeaf m) (== (-leaf-key m) (-leaf-key o)))
                       (slab/read-i32 (sio-of m) (off-of m) LEAF_VALUE_OFFSET)
                       -1)]
              (if (or (== -1 v) (same-block? v nv))
                acc
                [(conj (first acc) v) (conj values [v nv])]))

            ;; a child the new tree holds at the same slot of the same region is shared: compared by offset, no lookup
            (instance? IntMapBranch o)
            (let [paired? (same-region? o m)]
              (reduce (fn [acc i]
                        (let [c (-child-off o i)]
                          (if (or (== -1 c) (and paired? (same-block? c (-child-off m i))))
                            acc
                            (node-diff acc (branch-child o i) n))))
                      acc (clojure.core/range 16)))

            :else
            (let [paired? (instance? IntMapBinaryBranch m)
                  a (-neg-off o)
                  b (-pos-off o)]
              (cond-> acc
                (not (or (== -1 a) (and paired? (same-block? a (-neg-off m))))) (node-diff (neg-child o) n)
                (not (or (== -1 b) (and paired? (same-block? b (-pos-off m))))) (node-diff (pos-child o) n)))))))))

(defn retire-diff
  "[blocks values]: the blocks int-map old holds that new does not (new nil, or
   no int-map: every block of old's) — its header and metadata block, its
   nodes and leaf values — and the [old new] value blocks of the leaves among
   them (see node-diff)."
  [old new]
  (let [new (when (instance? PersistentIntMap new) new)
        sio (sio-of old)
        hdr (PersistentIntMap-offset old)]
    (if (and new (same-block? hdr (PersistentIntMap-offset new)))
      [[] []]
      (let [mta (slab/read-i32 sio hdr MAP_MTA_OFFSET)
            new-mta (if new (slab/read-i32 (sio-of new) (PersistentIntMap-offset new) MAP_MTA_OFFSET) -1)
            blocks (cond-> [hdr] (and (not= -1 mta) (not (same-block? mta new-mta))) (conj mta))]
        (node-diff [blocks []] (-root old) (when new (-root new)))))))

(defn collect-retire-diff-offsets
  "The blocks int-map old holds that new does not (new nil: all of old's)."
  [old new]
  (first (retire-diff old new)))

;;-----------------------------------------------------------------------------
;; Transients: each op makes the next persistent version in the transient's
;; own allocation log (a scope sio over the domain it began in), and frees
;; what that version superseded in the one before it — the walk between two
;; versions only the transient holds, and only blocks it made: never one of
;; the map it began from (
;; 2026-10-21 ET). persistent! answers the map and ends the transient. A value
;; an op replaces that is a collection stays: a value read out of the
;; transient may still be in use.
;;-----------------------------------------------------------------------------

(defn- live! [live?]
  (when-not live?
    (throw (ex-info "Transient used after persistent! call" {}))))

(defn- edit
  "(f m) allocating in sio, and the blocks of m it superseded that sio made
   freed."
  [sio m f]
  (let [m' (binding [d/*domain-sio* sio] (f m))]
    (alloc/free-owned! sio (collect-retire-diff-offsets m m'))
    m'))

(defn- chain
  "(reduce f (init) xs), each step's version superseding the one before, run
   in a scope of its own: each version the next supersedes is freed, of the
   blocks the scope made (init's too, when init makes them) — a reduce of
   persistent ops inside one call left every intermediate version allocated."
  [f init xs]
  (let [sio (alloc/scope-sio (alloc/current-sio))
        r (reduce (fn [m x] (edit sio m #(f % x)))
                  (binding [d/*domain-sio* sio] (init))
                  xs)]
    (alloc/stop-logging! sio)
    r))

(deftype TransientIntMap [^:mutable m sio ^:mutable live?]
  clojure.lang.Counted
  (count [_] (live! live?) (e/count m))

  clojure.lang.ILookup
  (valAt [_ k] (live! live?) (im-lookup m k nil))
  (valAt [_ k not-found] (live! live?) (im-lookup m k not-found))

  clojure.lang.ITransientMap
  (assoc [this k v] (live! live?) (set! m (edit sio m #(im-assoc % k v))) this)
  (without [this k] (live! live?) (set! m (edit sio m #(im-dissoc % k))) this)
  (conj [this o] (live! live?) (set! m (edit sio m #(im-conj % o))) this)
  (persistent [_] (live! live?) (set! live? false) (alloc/stop-logging! sio) m))

;;-----------------------------------------------------------------------------
;; Public API
;;-----------------------------------------------------------------------------

(defn int-map
  "An integer map: empty, or of alternating keys and values. Its nodes
   allocate in the running swap!'s domain, else this process's own."
  ([]
   (->PersistentIntMap -1 0 nil))
  ([k v]
   (chain (fn [m [k v]] (im-assoc m k v)) int-map [[k v]]))
  ([k v & kvs]
   (chain (fn [m [k v]] (im-assoc m k v)) int-map (cons [k v] (partition 2 kvs)))))

(defn merge-with
  "Merge int-maps, f resolving values at the same key."
  ([f a b]
   (let [root-a (-root a)
         root-b (-root b)
         epoch' (inc (max (-map-epoch a) (-map-epoch b)))]
     (cond
       (nil? root-a) b
       (nil? root-b) a
       :else (->PersistentIntMap (off-of (-im-merge root-a root-b epoch' f)) epoch' nil))))
  ([f a b & more]
   (chain #(merge-with f %1 %2) (constantly a) (cons b more))))

(defn merge
  "Merge int-maps; the last value wins at the same key."
  ([a b] (merge-with default-merge a b))
  ([a b & more] (apply merge-with default-merge a b more)))

(defn update
  "The int-map with the value at key k (f old-value args...)."
  ([m k f]
   (let [k (int k)
         epoch' (inc (-map-epoch m))
         root (-root m)
         root' (if root (-im-update root k epoch' f) (->IntMapLeaf k (f nil)))]
     (->PersistentIntMap (off-of root') epoch' (-map-meta m))))
  ([m k f & args]
   (update m k #(apply f % args))))

(defn range
  "The int-map's entries with keys in [min-k, max-k]."
  [m min-k max-k]
  (let [root (-root m)
        root' (when root (-im-range root (int min-k) (int max-k)))]
    (->PersistentIntMap (off-of root') (-map-epoch m) (-map-meta m))))
