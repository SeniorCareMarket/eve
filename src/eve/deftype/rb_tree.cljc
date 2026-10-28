(ns eve.deftype.rb-tree
  "Okasaki-style persistent red-black tree in Eve slab memory — one definition
   for every Eve host: RBNode and SortedSet are eve-deftypes written with the
   JVM's interface names, which eve-deftype translates per host.
   Each RBNode lives in SAB memory — color is a primitive int32,
   left/right are eve-type self-references, value is serialized.
   All fields are immutable; insert produces new nodes (path-copying).

   User-facing API (eve.core's vocabulary, which is clojure.core's on the JVM,
   ClojureScript and nbb, where (conj my-set 42) etc. work as well):
     (sorted-set)               ;; empty sorted set
     (sorted-set 3 1 4 1 5)    ;; from values (deduped, sorted)
     (e/conj my-set 42)         ;; insert
     (e/contains? my-set 42)    ;; membership (or member?)
     (e/seq my-set)             ;; sorted traversal
     (e/count my-set)           ;; element count

   Nodes allocate in the running swap!'s domain, else this process's own —
   users never pass a slab or domain."
  (:refer-clojure :exclude [sorted-set])
  (:require
   [eve.deftype :refer [eve-deftype]]
   [eve.core :as e]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]))

;;-----------------------------------------------------------------------------
;; Constants
;;-----------------------------------------------------------------------------

(def BLACK 0)
(def RED 1)

;;-----------------------------------------------------------------------------
;; RBNode — a single node in the red-black tree
;;
;;   Fields:
;;     color : int32 (0=BLACK, 1=RED)
;;     left  : RBNode (eve-type self-ref, nil when absent)
;;     right : RBNode (eve-type self-ref, nil when absent)
;;     value : any (serialized)
;;
;;   Layout in SAB:
;;     [type-id:1][pad:3][color:4][left-offset:4][right-offset:4][value-offset:4]
;;     total = 20 bytes
;;-----------------------------------------------------------------------------

(defprotocol IRBNode
  "A node's fields, the way the tree reads them on every host (bb's RBNode is
   a deftype over protocols: no ILookup there for (:left n)). nil answers nil,
   as (:left nil) does."
  (-color [n])
  (-left [n])
  (-right [n])
  (-value [n]))

(extend-protocol IRBNode
  nil
  (-color [_] nil)
  (-left [_] nil)
  (-right [_] nil)
  (-value [_] nil))

(eve-deftype RBNode [^:int32 color ^:RBNode left ^:RBNode right value]
  IRBNode
  (-color [_] color)
  (-left [_] left)
  (-right [_] right)
  (-value [_] value)

  clojure.lang.ILookup
  (valAt [this k]
    (case k :color color :left left :right right :value value nil))
  (valAt [this k not-found]
    (case k :color color :left left :right right :value value not-found)))

;;-----------------------------------------------------------------------------
;; The walk: the blocks one tree holds that another does not
;;-----------------------------------------------------------------------------
;; A value is in a set once, so a block of the old tree is in the new one only
;; as the new tree's node of that value. Two subtrees over the same value
;; interval whose tops hold the same value have children over the same
;; intervals: walked pairwise, the walk reads only the changed path. Where a
;; rotation put another value on top, each old node is looked for by its
;; value under the new subtree over its interval; where the same block is
;; found, the subtree is shared.

(def ^:private VALUE_OFFSET 16)  ;; RBNode [type:4][color:4][left:4][right:4][value:4]
(def ^:private SET_MTA_OFFSET 8) ;; SortedSet [type:4][root:4][mta:4]

(defn- same-block?
  "An offset as the allocator answered it and as a field read it back (sign-
   extended from class 4 up) name one block alike."
  [a b]
  (== (bit-and a 0xFFFFFFFF) (bit-and b 0xFFFFFFFF)))

(defn- sio-of [x] (or (d/-eve-sio x) (alloc/current-sio)))

(defn- find-node [t x]
  (when (some? t)
    (let [cmp (compare x (-value t))]
      (cond (neg? cmp) (recur (-left t) x)
            (pos? cmp) (recur (-right t) x)
            :else t))))

(defn- gone
  "acc, [blocks values], with old node o's block and its value's; values gains
   [old new] value blocks (new: the value block of m, the new tree's node of
   o's value, -1 when none), for a caller that diffs a collection value."
  [[blocks values] o m]
  (let [off (RBNode-offset o)
        v (slab/read-i32 (sio-of o) off VALUE_OFFSET)
        mv (if m (slab/read-i32 (sio-of m) (RBNode-offset m) VALUE_OFFSET) -1)]
    (if (== -1 v)
      [(conj blocks off) values]
      [(conj blocks off v) (conj values [v mv])])))

(declare diff-under)

(defn- diff-at
  "acc with the blocks of old subtree o that new subtree n does not hold, o and
   n over the same value interval."
  [acc o n]
  (cond
    (nil? o) acc
    (and (some? n) (same-block? (RBNode-offset o) (RBNode-offset n))) acc
    (and (some? n) (zero? (compare (-value o) (-value n))))
    (-> acc (gone o n) (diff-at (-left o) (-left n)) (diff-at (-right o) (-right n)))
    :else (diff-under acc o n)))

(defn- diff-under
  "acc with the blocks of old subtree o that the new tree does not hold, n the
   new subtree over o's value interval or one containing it."
  [acc o n]
  (if (nil? o)
    acc
    (let [m (find-node n (-value o))]
      (if (and (some? m) (same-block? (RBNode-offset o) (RBNode-offset m)))
        acc
        (-> acc (gone o m) (diff-under (-left o) n) (diff-under (-right o) n))))))

(defn- tree-diff
  "The blocks of old tree o that new tree n does not hold."
  [o n]
  (first (diff-at [[] []] o n)))

(defn- scoped
  "(f), a new tree, allocating in a scope of its own; the blocks the scope made
   that the new tree does not hold freed. Okasaki's balance rebuilds the red
   pair an insert made a level down, a red root is blackened by a copy, and
   Kahrs' delete makes and drops nodes along its path: never published, and
   nothing else could ever free them (RELEASE-CHECKLIST 9.25, 2026-10-21 ET)."
  [old f]
  (let [sio (alloc/scope-sio (alloc/current-sio))
        new (binding [d/*domain-sio* sio] (f))
        kept (set (map #(bit-and % 0xFFFFFFFF) (tree-diff new old)))]
    (alloc/free-owned! sio (remove #(contains? kept (bit-and % 0xFFFFFFFF)) (alloc/logged-allocs sio)))
    (alloc/stop-logging! sio)
    new))

;;-----------------------------------------------------------------------------
;; Core operations (no pattern matching — just cond chains)
;;-----------------------------------------------------------------------------

(defn- balance
  "Okasaki balance: given components of a node, check for the 4 red-red
   violation cases and restructure if needed. Always returns a new node."
  [color left val right]
  (if (== color BLACK)
    (cond
      ;; Case 1: B(R(R(a,x,b),y,c),z,d)
      (and (some? left) (== RED (-color left))
           (some? (-left left)) (== RED (-color (-left left))))
      (let [ll (-left left)]
        (->RBNode RED
                  (->RBNode BLACK (-left ll) (-right ll) (-value ll))
                  (->RBNode BLACK (-right left) right val)
                  (-value left)))

      ;; Case 2: B(R(a,x,R(b,y,c)),z,d)
      (and (some? left) (== RED (-color left))
           (some? (-right left)) (== RED (-color (-right left))))
      (let [lr (-right left)]
        (->RBNode RED
                  (->RBNode BLACK (-left left) (-left lr) (-value left))
                  (->RBNode BLACK (-right lr) right val)
                  (-value lr)))

      ;; Case 3: B(a,x,R(R(b,y,c),z,d))
      (and (some? right) (== RED (-color right))
           (some? (-left right)) (== RED (-color (-left right))))
      (let [rl (-left right)]
        (->RBNode RED
                  (->RBNode BLACK left (-left rl) val)
                  (->RBNode BLACK (-right rl) (-right right) (-value right))
                  (-value rl)))

      ;; Case 4: B(a,x,R(b,y,R(c,z,d)))
      (and (some? right) (== RED (-color right))
           (some? (-right right)) (== RED (-color (-right right))))
      (let [rr (-right right)]
        (->RBNode RED
                  (->RBNode BLACK left (-left right) val)
                  (->RBNode BLACK (-left rr) (-right rr) (-value rr))
                  (-value right)))

      ;; No violation — pass through
      :else
      (->RBNode color left right val))

    ;; Red node — no balancing
    (->RBNode color left right val)))

(defn- ins
  "Insert helper. Recurses down the tree, creating new nodes on the path.
   Uses `compare` for ordering so any Comparable value works."
  [tree x]
  (if (nil? tree)
    ;; New leaf is always red
    (->RBNode RED nil nil x)
    (let [c (-color tree)
          l (-left tree)
          v (-value tree)
          r (-right tree)
          cmp (compare x v)]
      (cond
        (neg? cmp) (balance c (ins l x) v r)
        (pos? cmp) (balance c l v (ins r x))
        ;; Already present — return as-is
        :else tree))))

(declare rb-member?)

(defn rb-insert
  "Insert value x into red-black tree rooted at `tree`.
   Returns a new root node (always black). `tree` may be nil (empty)."
  [tree x]
  (if (rb-member? tree x)
    tree
    (scoped tree
            (fn []
              (let [node (ins tree x)]
                ;; Blacken the root (Okasaki invariant)
                (if (== BLACK (-color node))
                  node
                  (->RBNode BLACK (-left node) (-right node) (-value node))))))))

(defn rb-member?
  "Returns true if value x is in the tree."
  [tree x]
  (if (nil? tree)
    false
    (let [cmp (compare x (-value tree))]
      (cond
        (neg? cmp) (recur (-left tree) x)
        (pos? cmp) (recur (-right tree) x)
        :else true))))

(defn rb-find
  "Returns the stored value equal to x, or nil if not found."
  [tree x]
  (if (nil? tree)
    nil
    (let [cmp (compare x (-value tree))]
      (cond
        (neg? cmp) (recur (-left tree) x)
        (pos? cmp) (recur (-right tree) x)
        :else (-value tree)))))

(defn rb-seq
  "Returns a lazy in-order sequence of all values in the tree."
  [tree]
  (when (some? tree)
    (lazy-cat (rb-seq (-left tree))
              [(-value tree)]
              (rb-seq (-right tree)))))

(defn rb-count
  "Returns the number of elements in the tree."
  [tree]
  (if (nil? tree)
    0
    (+ 1 (rb-count (-left tree)) (rb-count (-right tree)))))

(defn rb-height
  "Returns the height of the tree (longest path from root to leaf)."
  [tree]
  (if (nil? tree)
    0
    (inc (max (rb-height (-left tree))
              (rb-height (-right tree))))))

;;-----------------------------------------------------------------------------
;; Deletion — Kahrs' persistent red-black deletion ("Red-black trees with
;; types", J. Functional Programming 11(4), 2001: the delete that ships with
;; Okasaki-style trees). Red and black only — every node it builds is an
;; ordinary RBNode, nothing phantom is ever stored in the slab.
;;
;; T below takes a node in Okasaki's reading, (color left value right);
;; RBNode's fields are [color left right value].
;;-----------------------------------------------------------------------------

(defn- T [c l v r] (->RBNode c l r v))
(defn- red? [n] (and (some? n) (== RED (-color n))))
(defn- black? [n] (and (some? n) (== BLACK (-color n))))

(defn- violated! [where]
  (throw (ex-info (str "rb-tree: red-black invariant violated in " where) {})))

(defn- kbalance
  "Kahrs' balance: a black node over a and b, restructured when it holds a
   red-red pair (the result is then red)."
  [a x b]
  (cond
    (and (red? a) (red? b))
    (T RED (T BLACK (-left a) (-value a) (-right a)) x (T BLACK (-left b) (-value b) (-right b)))

    (and (red? a) (red? (-left a)))
    (let [aa (-left a)]
      (T RED (T BLACK (-left aa) (-value aa) (-right aa)) (-value a) (T BLACK (-right a) x b)))

    (and (red? a) (red? (-right a)))
    (let [ab (-right a)]
      (T RED (T BLACK (-left a) (-value a) (-left ab)) (-value ab) (T BLACK (-right ab) x b)))

    (and (red? b) (red? (-right b)))
    (let [bb (-right b)]
      (T RED (T BLACK a x (-left b)) (-value b) (T BLACK (-left bb) (-value bb) (-right bb))))

    (and (red? b) (red? (-left b)))
    (let [ba (-left b)]
      (T RED (T BLACK a x (-left ba)) (-value ba) (T BLACK (-right ba) (-value b) (-right b))))

    :else (T BLACK a x b)))

(defn- sub1
  "A black node made red (its subtree's black height one less)."
  [n]
  (if (black? n)
    (T RED (-left n) (-value n) (-right n))
    (violated! "sub1")))

(defn- balleft
  "l is one black short of r: rebalance (color left value right) = (_ l x r)."
  [l x r]
  (cond
    (red? l) (T RED (T BLACK (-left l) (-value l) (-right l)) x r)
    (black? r) (kbalance l x (T RED (-left r) (-value r) (-right r)))
    (and (red? r) (black? (-left r)))
    (let [rl (-left r)]
      (T RED (T BLACK l x (-left rl)) (-value rl)
         (kbalance (-right rl) (-value r) (sub1 (-right r)))))
    :else (violated! "balleft")))

(defn- balright
  "r is one black short of l: the mirror of balleft."
  [l x r]
  (cond
    (red? r) (T RED l x (T BLACK (-left r) (-value r) (-right r)))
    (black? l) (kbalance (T RED (-left l) (-value l) (-right l)) x r)
    (and (red? l) (black? (-right l)))
    (let [lr (-right l)]
      (T RED (kbalance (sub1 (-left l)) (-value l) (-left lr)) (-value lr)
         (T BLACK (-right lr) x r)))
    :else (violated! "balright")))

(defn- app
  "The two subtrees of a removed node, joined."
  [a b]
  (cond
    (nil? a) b
    (nil? b) a

    (and (red? a) (red? b))
    (let [bc (app (-right a) (-left b))]
      (if (red? bc)
        (T RED (T RED (-left a) (-value a) (-left bc)) (-value bc)
           (T RED (-right bc) (-value b) (-right b)))
        (T RED (-left a) (-value a) (T RED bc (-value b) (-right b)))))

    (and (black? a) (black? b))
    (let [bc (app (-right a) (-left b))]
      (if (red? bc)
        (T RED (T BLACK (-left a) (-value a) (-left bc)) (-value bc)
           (T BLACK (-right bc) (-value b) (-right b)))
        (balleft (-left a) (-value a) (T BLACK bc (-value b) (-right b)))))

    (red? b) (T RED (app a (-left b)) (-value b) (-right b))
    :else (T RED (-left a) (-value a) (app (-right a) b))))

(defn- del
  [t x]
  (when (some? t)
    (let [y (-value t)
          c (compare x y)]
      (cond
        (neg? c) (let [a (-left t)]
                   (if (black? a)
                     (balleft (del a x) y (-right t))
                     (T RED (del a x) y (-right t))))
        (pos? c) (let [b (-right t)]
                   (if (black? b)
                     (balright (-left t) y (del b x))
                     (T RED (-left t) y (del b x))))
        :else (app (-left t) (-right t))))))

(defn rb-delete
  "Remove value x from red-black tree rooted at `tree`: a new tree, path-copied
   (the same tree when x is absent)."
  [tree x]
  (if (rb-member? tree x)
    (scoped tree
            (fn []
              (when-let [r (del tree x)]
                (T BLACK (-left r) (-value r) (-right r)))))
    tree))

(defn rb-black-height
  "Returns the black-height (number of black nodes on any path to a leaf).
   Returns -1 if the tree violates the black-height invariant."
  [tree]
  (if (nil? tree)
    1 ;; nil counts as black
    (let [lh (rb-black-height (-left tree))
          rh (rb-black-height (-right tree))]
      (if (or (== -1 lh) (== -1 rh) (not= lh rh))
        -1
        (+ lh (if (== BLACK (-color tree)) 1 0))))))

(defn rb-valid?
  "Checks all red-black tree invariants:
   1. Root is black
   2. Red nodes have only black children
   3. Every path has equal black-height
   Returns true if all invariants hold."
  [tree]
  (if (nil? tree)
    true
    (let [;; Invariant 1: root is black
          root-black? (== BLACK (-color tree))
          ;; Invariant 3: uniform black-height
          bh (rb-black-height tree)]
      (and root-black?
           (not= -1 bh)
           ;; Invariant 2: no red-red violations (checked recursively)
           (letfn [(no-red-red? [node]
                     (if (nil? node)
                       true
                       (let [c (-color node)
                             l (-left node)
                             r (-right node)]
                         (if (== RED c)
                           (and (or (nil? l) (== BLACK (-color l)))
                                (or (nil? r) (== BLACK (-color r)))
                                (no-red-red? l)
                                (no-red-red? r))
                           (and (no-red-red? l)
                                (no-red-red? r))))))]
             (no-red-red? tree))))))

;;-----------------------------------------------------------------------------
;; SortedSet — the user-facing set: an eve-deftype over the tree's root, so a
;; sorted set lives in an atom like any Eve value. Metadata is a serialized field.
;;-----------------------------------------------------------------------------

(defn- print-sorted-set [vals]
  (str "#eve/SortedSet#{" (apply str (interpose " " (map pr-str vals))) "}"))

(defprotocol IRBSet
  (-rb-root [s] "the tree's root node, nil when empty"))

(declare retire-diff)

(eve-deftype SortedSet [^:RBNode root mta]
  IRBSet
  (-rb-root [_] root)

  d/IRetireDiff
  (-retire-diff [this new] (retire-diff this new))

  clojure.lang.IMeta
  (meta [_] mta)

  clojure.lang.IObj
  (withMeta [_ m] (->SortedSet root m))

  clojure.lang.Counted
  (count [_] (rb-count root))

  clojure.lang.IPersistentCollection
  (cons [_ v] (->SortedSet (rb-insert root v) mta))
  (empty [_] (->SortedSet nil nil))
  (equiv [this other]
    (and (or (coll? other) (instance? SortedSet other))
         (= (e/count this) (e/count other))
         (every? #(rb-member? root %) (e/seq other))))

  clojure.lang.Seqable
  (seq [_] (rb-seq root))

  clojure.lang.ILookup
  (valAt [_ v] (rb-find root v))
  (valAt [_ v not-found]
    (let [result (rb-find root v)]
      (if (some? result) result not-found)))

  clojure.lang.IFn
  (invoke [_ v] (rb-find root v))
  (invoke [_ v not-found]
    (let [result (rb-find root v)]
      (if (some? result) result not-found)))

  clojure.lang.IPersistentSet
  (disjoin [_ v] (->SortedSet (rb-delete root v) mta))
  (contains [_ v] (rb-member? root v))
  (get [_ v] (rb-find root v))

  clojure.lang.IHashEq
  (hasheq [_]
    (reduce (fn [acc v] (+ acc (hash v))) 0 (rb-seq root)))

  ;; what a deref of an atom holding it answers: a Clojure sorted set
  d/IPlain
  (-plain [_] (into (clojure.core/sorted-set) (map d/eve->clj) (rb-seq root)))

  ;; through the instance (e/seq this): on a host whose printer is a
  ;; print-method, this body runs outside the type, where the fields are not
  IPrintWithWriter
  (-pr-writer [this w _] (e/-write w (print-sorted-set (e/seq this))))

  java.lang.Object
  (toString [_] (print-sorted-set (rb-seq root)))
  (equals [this other] (.equiv this other))
  (hashCode [this] (.hasheq this)))

;;-----------------------------------------------------------------------------
;; Public API
;;-----------------------------------------------------------------------------

(defn retire-diff
  "[blocks values]: the blocks sorted set old holds that new does not (new nil,
   or no SortedSet: every block of old's) — its header and metadata block, its
   nodes and their values — and the [old new] value blocks among them."
  [old new]
  (let [new (when (instance? SortedSet new) new)
        hdr (SortedSet-offset old)]
    (if (and new (same-block? hdr (SortedSet-offset new)))
      [[] []]
      (let [mta (slab/read-i32 (sio-of old) hdr SET_MTA_OFFSET)
            own? (and (not= -1 mta)
                      (not (and new (same-block? mta (slab/read-i32 (sio-of new) (SortedSet-offset new) SET_MTA_OFFSET)))))]
        (diff-at [(cond-> [hdr] own? (conj mta)) []] (-rb-root old) (when new (-rb-root new)))))))

(defn collect-retire-diff-offsets
  "The blocks sorted set old holds that new does not (new nil: all of old's)."
  [old new]
  (first (retire-diff old new)))

(defn sorted-set
  "An Eve sorted set (a red-black tree in slab memory). Built in a scope of its
   own: each version the next supersedes is freed (a reduce of conjs left
   them all allocated)."
  ([]
   (->SortedSet nil nil))
  ([& vals]
   (let [sio (alloc/scope-sio (alloc/current-sio))
         s (reduce (fn [s v]
                     (let [s' (binding [d/*domain-sio* sio] (e/conj s v))]
                       (alloc/free-owned! sio (collect-retire-diff-offsets s s'))
                       s'))
                   (binding [d/*domain-sio* sio] (sorted-set))
                   vals)]
     (alloc/stop-logging! sio)
     s)))

(defn member?
  "Returns true if value v is in the sorted set (or any collection)."
  [s v]
  (e/contains? s v))
