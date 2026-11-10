(ns eve.sorted
  "eve's sorted map and sorted set (EVM-11a): Clojure's sorted-map / sorted-map-by / sorted-set / sorted-set-by,
   subseq and rsubseq, rank and nth, and editable transients (VSv2's sorted collections are editable), as values in
   eve memory — one definition for every eve host.

   ONE CANONICAL TREE. A sorted collection's tree is a treap whose priorities are a pure function of each key: the
   Murmur3 of its key bytes (eve.hamt-util/portable-hash-bytes over eve.codec/encode-key, which every host computes
   alike — the HAMT's own hash), ties broken by those bytes (eve.deftype-proto.data/compare-bytes). A treap with
   fixed priorities has exactly one shape for a set of keys, so the tree is a function of what it holds, never of
   the order its keys arrived or left in, and the same on every host: content addressing and the byte fixtures can
   name it. Expected depth O(log n), every operation path-copying (insert by split, delete by join), each node
   counting its subtree so rank and nth are O(log n).

   The comparator: nil for compare; a fn otherwise (a boolean predicate such as > is read as Clojure reads one).
   It is a value with no eve bytes, so the collection holds it by reference (FORMAT-DECISIONS.md §9): readable in
   this process, refused by a shared domain. Nodes allocate in the running swap!'s domain, else this process's own.

   Use eve.core's vocabulary on them (e/assoc, e/get, e/conj, e/seq, e/rseq, e/transient, e/assoc!, ...), and this
   namespace's subseq, rsubseq, rank, nth-entry, find, dissoc! and disj!."
  (:refer-clojure :exclude [sorted-map sorted-map-by sorted-set sorted-set-by subseq rsubseq find
                            dissoc! disj! aclone bytes deftype])
  (:require
   [eve.deftype :refer [deftype eve-deftype]]
   [eve.core :as e]
   [eve.host :refer [aclone bytes]]
   [eve.codec :as codec]
   [eve.encoding :as enc]
   [eve.hamt-util :as hu]
   [eve.platform :as p]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.alloc :as alloc]
   [eve.slab-io :as slab]
   ;; the writers a collection stored in a field (metadata, a key, a value)
   ;; is written through
   [eve.map] [eve.set] [eve.vec] [eve.list]))

;;-----------------------------------------------------------------------------
;; The node: [prio size left right entry], the entry [k v] its own block — a
;; path copy writes one 32-byte node and shares the entry, so a big key or
;; value is never copied (EVM-13's row, R-6: the kernel's write is flat in the
;; value's size, an inline value's is not)
;;-----------------------------------------------------------------------------

(defprotocol ISortedNode
  "A node's fields, the way the tree reads them on every host; nil answers as
   the empty tree."
  (-prio [n])
  (-size [n])
  (-sl [n])
  (-sr [n])
  (-entry [n]))

(extend-protocol ISortedNode
  nil
  (-prio [_] nil)
  (-size [_] 0)
  (-sl [_] nil)
  (-sr [_] nil)
  (-entry [_] nil))

(defprotocol ISortedEntry
  (-ek [e])
  (-ev [e]))

(extend-protocol ISortedEntry
  nil
  (-ek [_] nil)
  (-ev [_] nil))

(eve-deftype SortedEntry [k v]
             ISortedEntry
             (-ek [_] k)
             (-ev [_] v))

(eve-deftype SortedNode [^:int32 prio ^:int32 size ^:SortedNode left ^:SortedNode right ^:SortedEntry entry]
             ISortedNode
             (-prio [_] prio)
             (-size [_] size)
             (-sl [_] left)
             (-sr [_] right)
             (-entry [_] entry))

(defn- -sk [n] (-ek (-entry n)))
(defn- -sv [n] (-ev (-entry n)))

(defn- node [p l r e] (->SortedNode p (+ 1 (-size l) (-size r)) l r e))

(defn- with-children [n l r] (node (-prio n) l r (-entry n)))

;;-----------------------------------------------------------------------------
;; Order: the comparator, and the priority that fixes the shape
;;-----------------------------------------------------------------------------

(defn- default-compare
  "Clojure's compare, nil before everything spelled out: clojerl's compare is the
   BEAM's term order (an atom, nil, after every number), while its own sorted
   collections put nil first, as Clojure's do."
  [a b]
  (cond (nil? a) (if (nil? b) 0 -1)
        (nil? b) 1
        :else (compare a b)))

(defn- cmp-of
  "The comparison of a collection's comparator field: default-compare for nil; a
   fn's own, a boolean answer read as Clojure's comparator reads it."
  [f]
  (if (nil? f)
    default-compare
    (fn [a b]
      (let [r (f a b)]
        (if (boolean? r)
          (cond r -1 (f b a) 1 :else 0)
          r)))))

(defn- key-bytes [k] (aclone ^bytes (codec/encode-key k)))

(defn- prio-of
  "A key's priority: the Murmur3 of its key bytes, the same on every host."
  [k]
  (hu/portable-hash-bytes (codec/encode-key k)))

(defn- above?
  "True when priority p of key k sits above node n: the larger priority, a tie
   to the smaller key bytes."
  [p k n]
  (let [q (-prio n)]
    (or (> p q)
        (and (== p q) (neg? (d/compare-bytes (key-bytes k) (key-bytes (-sk n))))))))

;;-----------------------------------------------------------------------------
;; The tree
;;-----------------------------------------------------------------------------

(defn- find-node [t c k]
  (loop [n t]
    (when (some? n)
      (let [r (c k (-sk n))]
        (cond (neg? r) (recur (-sl n))
              (pos? r) (recur (-sr n))
              :else n)))))

(defn- split
  "[keys below k, keys above k] of t, k absent from t."
  [t c k]
  (if (nil? t)
    [nil nil]
    (if (neg? (c k (-sk t)))
      (let [[l r] (split (-sl t) c k)] [l (with-children t r (-sr t))])
      (let [[l r] (split (-sr t) c k)] [(with-children t (-sl t) l) r]))))

(defn- insert-new [t c p k v]
  (cond
    (nil? t) (node p nil nil (->SortedEntry k v))
    (above? p k t) (let [[l r] (split t c k)] (node p l r (->SortedEntry k v)))
    (neg? (c k (-sk t))) (with-children t (insert-new (-sl t) c p k v) (-sr t))
    :else (with-children t (-sl t) (insert-new (-sr t) c p k v))))

(defn- replace-value
  "t with the node whose key compares equal to k holding v (its stored key
   kept, as Clojure keeps it): the same shape."
  [t c k v]
  (let [r (c k (-sk t))]
    (cond (neg? r) (with-children t (replace-value (-sl t) c k v) (-sr t))
          (pos? r) (with-children t (-sl t) (replace-value (-sr t) c k v))
          :else (->SortedNode (-prio t) (-size t) (-sl t) (-sr t) (->SortedEntry (-sk t) v)))))

(defn- tree-assoc [t c k v]
  (if-let [n (find-node t c k)]
    (if (identical? v (-sv n)) t (replace-value t c k v))
    (insert-new t c (prio-of k) k v)))

(defn- tree-conj [t c k]
  (if (some? (find-node t c k)) t (insert-new t c (prio-of k) k nil)))

(defn- join
  "The tree of a's keys and b's, every key of a below every key of b."
  [a b]
  (cond
    (nil? a) b
    (nil? b) a
    (above? (-prio a) (-sk a) b) (with-children a (-sl a) (join (-sr a) b))
    :else (with-children b (join a (-sl b)) (-sr b))))

(defn- remove-key [t c k]
  (let [r (c k (-sk t))]
    (cond (neg? r) (with-children t (remove-key (-sl t) c k) (-sr t))
          (pos? r) (with-children t (-sl t) (remove-key (-sr t) c k))
          :else (join (-sl t) (-sr t)))))

(defn- tree-dissoc [t c k]
  (if (some? (find-node t c k)) (remove-key t c k) t))

;; In-order traversal over an explicit stack (a list of nodes): ascending, the
;; stack holds the path's nodes whose left subtree the walk is in

(defn- push-left [stack n] (if (nil? n) stack (recur (conj stack n) (-sl n))))
(defn- push-right [stack n] (if (nil? n) stack (recur (conj stack n) (-sr n))))

(defn- node-seq [stack asc?]
  (lazy-seq
   (when (seq stack)
     (let [n (first stack)
           more (rest stack)]
       (cons n (node-seq (if asc? (push-left more (-sr n)) (push-right more (-sl n))) asc?))))))

(defn- nodes [t asc?] (node-seq (if asc? (push-left () t) (push-right () t)) asc?))

(defn- nodes-from
  "The nodes from k on, ascending (keys >= k) or descending (keys <= k)."
  [t c k asc?]
  (node-seq (loop [n t stack ()]
              (if (nil? n)
                stack
                (let [r (c k (-sk n))]
                  (cond (zero? r) (conj stack n)
                        asc? (if (neg? r) (recur (-sl n) (conj stack n)) (recur (-sr n) stack))
                        :else (if (pos? r) (recur (-sr n) (conj stack n)) (recur (-sl n) stack))))))
            asc?))

(defn- tree-rank [t c k]
  (loop [n t acc 0]
    (if (nil? n)
      -1
      (let [r (c k (-sk n))]
        (cond (neg? r) (recur (-sl n) acc)
              (pos? r) (recur (-sr n) (+ acc 1 (-size (-sl n))))
              :else (+ acc (-size (-sl n))))))))

(defn- tree-nth [t i]
  (when (or (neg? i) (>= i (-size t)))
    (throw (enc/index-out-of-bounds (str "eve.sorted: index " i " of " (-size t)))))
  (loop [n t i i]
    (let [ls (-size (-sl n))]
      (cond (< i ls) (recur (-sl n) i)
            (== i ls) n
            :else (recur (-sr n) (- i ls 1))))))

(defn- tree-shape [n]
  (when (some? n) [(-sk n) (tree-shape (-sl n)) (tree-shape (-sr n))]))

;;-----------------------------------------------------------------------------
;; The walk: the blocks one tree holds that another does not
;;-----------------------------------------------------------------------------
;; One canonical tree: a node sits at its key's place, so a block of the old
;; tree is in the new one only at that key there. Two subtrees over the same
;; key interval whose tops are the same key have children over the same
;; intervals: walked pairwise, the walk reads only the changed path. Below a
;; split or a join (an inserted key above, a removed one), each old node is
;; looked for by its key under the new subtree over its interval — where the
;; same block is found, the subtree is shared.

(def ^:private NODE_ENTRY_OFFSET 20) ;; SortedNode [type:4][prio:4][size:4][left:4][right:4][entry:4]
(def ^:private ENTRY_K_OFFSET 4)   ;; SortedEntry [type:4][k:4][v:4]
(def ^:private ENTRY_V_OFFSET 8)
(def ^:private CMP_OFFSET 8)       ;; EveSortedMap / EveSortedSet [type:4][root:4][cmp:4][mta:4]
(def ^:private MTA_OFFSET 12)

(defn- same-block?
  "An offset as the allocator answered it and as a field read it back (sign-
   extended from class 4 up) name one block alike."
  [a b]
  (== (bit-and a 0xFFFFFFFF) (bit-and b 0xFFFFFFFF)))

(defn- sio-of [x] (or (d/-eve-sio x) (alloc/current-sio)))

(defn- entry-block
  "The entry block of node n, read raw (an entry read as an instance decodes its key and value)."
  [n]
  (slab/read-i32 (sio-of n) (SortedNode-offset n) NODE_ENTRY_OFFSET))

(defn- gone
  "acc, [blocks values], with old node o's block, and its entry's blocks (the
   entry, its key's, its value's) unless m — the new tree's node at o's key, or
   nil — shares the entry; values gains [old new] for each key and value block
   (new: m's, -1 when none), for a caller that diffs a collection value."
  [[blocks values] o m]
  (let [blocks (conj blocks (SortedNode-offset o))
        sio (sio-of o)
        e (entry-block o)
        me (if m (entry-block m) -1)]
    (if (and (not= -1 me) (same-block? e me))
      [blocks values]
      (let [k (slab/read-i32 sio e ENTRY_K_OFFSET)
            v (slab/read-i32 sio e ENTRY_V_OFFSET)
            mk (if (not= -1 me) (slab/read-i32 (sio-of m) me ENTRY_K_OFFSET) -1)
            mv (if (not= -1 me) (slab/read-i32 (sio-of m) me ENTRY_V_OFFSET) -1)]
        [(cond-> (conj blocks e) (not= -1 k) (conj k) (not= -1 v) (conj v))
         (cond-> values (not= -1 k) (conj [k mk]) (not= -1 v) (conj [v mv]))]))))

(declare diff-under)

(defn- diff-at
  "acc with the blocks of old subtree o that new subtree n does not hold, o and
   n over the same key interval."
  [acc o n c]
  (cond
    (nil? o) acc
    (and (some? n) (same-block? (SortedNode-offset o) (SortedNode-offset n))) acc
    ;; the same key: a path copy shares the entry (no key decoded), a new value's entry holds the same key
    (and (some? n) (or (same-block? (entry-block o) (entry-block n)) (zero? (c (-sk o) (-sk n)))))
    (-> acc (gone o n) (diff-at (-sl o) (-sl n) c) (diff-at (-sr o) (-sr n) c))
    :else (diff-under acc o n c)))

(defn- diff-under
  "acc with the blocks of old subtree o that the new tree does not hold, n the
   new subtree over o's key interval or one containing it."
  [acc o n c]
  (if (nil? o)
    acc
    (let [m (find-node n c (-sk o))]
      (if (and (some? m) (same-block? (SortedNode-offset o) (SortedNode-offset m)))
        acc
        (-> acc (gone o m) (diff-under (-sl o) n c) (diff-under (-sr o) n c))))))

(defn- tree-diff
  "The blocks of old tree o that new tree n does not hold, c their comparison."
  [o n c]
  (first (diff-at [[] []] o n c)))

;;-----------------------------------------------------------------------------
;; What both collections answer
;;-----------------------------------------------------------------------------

(defprotocol ISortedColl
  (-root [sc] "the tree's root node, nil when empty")
  (-cmp [sc] "the comparator field: nil for compare")
  (-map? [sc] "true for a map (entries), false for a set (keys)"))

(defprotocol ISortedTransient
  (-tdissoc! [t k] "a transient map without k")
  (-tdisj! [t k] "a transient set without k"))

(defn- entry-of [sc n] (if (-map? sc) (enc/map-entry (-sk n) (-sv n)) (-sk n)))

(defn- entry-key [sc e] (if (-map? sc) (key e) e))

(defn- entries [sc s] (when (seq s) (map #(entry-of sc %) s)))

(defn- print-sorted [sc]
  (if (-map? sc)
    (str "#eve/SortedMap{" (apply str (interpose ", " (map (fn [n] (str (pr-str (-sk n)) " " (pr-str (-sv n))))
                                                           (nodes (-root sc) true)))) "}")
    (str "#eve/SortedSet#{" (apply str (interpose " " (map (fn [n] (pr-str (-sk n))) (nodes (-root sc) true)))) "}")))

(defn- map-equiv [sc other]
  (and (or (map? other) (eve.map/eve-hash-map? other) (satisfies? ISortedColl other))
       (== (-size (-root sc)) (e/count other))
       (every? (fn [n] (= (-sv n) (e/get other (-sk n) ::absent))) (nodes (-root sc) true))))

(defn- set-equiv [sc other]
  ;; eve-set?: the host's set?, an EveHashSet, or this namespace's EveSortedSet (registered below) — never set? alone,
  ;; blind on jank to every Eve set (identity audit B-SET-X: a sorted set and a hash set of the same members unequal)
  (and (or (eve.set/eve-set? other) (satisfies? ISortedColl other))
       (== (-size (-root sc)) (e/count other))
       (every? (fn [x] (some? (find-node (-root sc) (cmp-of (-cmp sc)) x))) (e/seq other))))

(defn- lookup [sc k nf]
  (if-let [n (find-node (-root sc) (cmp-of (-cmp sc)) k)]
    (if (-map? sc) (-sv n) (-sk n))
    nf))

(declare ->EveSortedMap ->EveSortedSet ->TransientSortedMap ->TransientSortedSet retire-diff)

;; A collection built from no tree, through a transient: each version the next
;; supersedes is freed (a reduce of persistent ops left them all allocated)

(defn- build-map [cmp kvs]
  (e/persistent! (reduce (fn [t [k v]] (e/assoc! t k v))
                         (->TransientSortedMap nil cmp true (alloc/scope-sio (alloc/current-sio)))
                         kvs)))

(defn- build-set [cmp ks]
  (e/persistent! (reduce e/conj!
                         (->TransientSortedSet nil cmp true (alloc/scope-sio (alloc/current-sio)))
                         ks)))

(defn- to-plain
  "What a deref of an atom holding sc answers: the same sorted collection, its
   contents plain, in this process's own domain (never the atom's) — on every
   host, which need not have a sorted map of its own (basilisp has none)."
  [sc]
  (let [c (-cmp sc)
        ns (nodes (-root sc) true)]
    (binding [d/*domain-sio* nil]
      (if (-map? sc)
        (build-map c (map (fn [n] [(d/eve->clj (-sk n)) (d/eve->clj (-sv n))]) ns))
        (build-set c (map (fn [n] (d/eve->clj (-sk n))) ns))))))

;;-----------------------------------------------------------------------------
;; The sorted map and the sorted set
;;-----------------------------------------------------------------------------

(eve-deftype EveSortedMap [^:SortedNode root cmp mta]
             ISortedColl
             (-root [_] root)
             (-cmp [_] cmp)
             (-map? [_] true)

             clojure.lang.IMeta
             (meta [_] mta)

             clojure.lang.IObj
             (withMeta [_ m] (->EveSortedMap root cmp m))

             clojure.lang.Counted
             (count [_] (-size root))

             clojure.lang.ILookup
             (valAt [this k] (lookup this k nil))
             (valAt [this k not-found] (lookup this k not-found))

             clojure.lang.Associative
             (containsKey [_ k] (some? (find-node root (cmp-of cmp) k)))
             (entryAt [_ k] (when-let [n (find-node root (cmp-of cmp) k)] (enc/map-entry (-sk n) (-sv n))))
             (assoc [_ k v] (->EveSortedMap (tree-assoc root (cmp-of cmp) k v) cmp mta))

             clojure.lang.IPersistentMap
             (without [_ k] (->EveSortedMap (tree-dissoc root (cmp-of cmp) k) cmp mta))
             (assocEx [_ k v] (->EveSortedMap (tree-assoc root (cmp-of cmp) k v) cmp mta))

             clojure.lang.IPersistentCollection
             (cons [this o]
                   (cond
                     (map? o) (reduce (fn [m en] (e/assoc m (key en) (val en))) this (e/seq o))
                     (or (vector? o) (eve.vec/eve-vector? o)) (e/assoc this (e/nth o 0) (e/nth o 1))
                     :else (e/assoc this (key o) (val o))))
             (empty [_] (->EveSortedMap nil cmp mta))
             (equiv [this other] (map-equiv this other))

             clojure.lang.Seqable
             (seq [this] (entries this (nodes root true)))

             clojure.lang.Reversible
             (rseq [this] (entries this (nodes root false)))

             clojure.lang.IFn
             (invoke [this k] (lookup this k nil))
             (invoke [this k not-found] (lookup this k not-found))

             clojure.lang.IHashEq
             (hasheq [this] (p/hash-unordered (or (entries this (nodes root true)) ())))

             clojure.lang.IEditableCollection
             (asTransient [_] (->TransientSortedMap root cmp true (alloc/scope-sio (alloc/current-sio))))

             d/IRetireDiff
             (-retire-diff [this new] (retire-diff this new))

  ;; the JVM's (each host's vocab drops what it has no interface for): a
  ;; Clojure map is = to it only through java.util.Map and MapEquivalence
             clojure.lang.MapEquivalence

             java.lang.Iterable
             (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

             java.util.Map
             (size [_] (int (-size root)))
             (isEmpty [_] (zero? (-size root)))
             (get [this k] (lookup this k nil))
             (containsValue [this v] (boolean (some #(= v (val %)) (e/seq this))))
             (keySet [this] (set (keys this)))
             (values [this] (vals this))
             (entrySet [this] (set (e/seq this)))
             (put [_ _ _] (enc/throw-unsupported))
             (remove [_ _] (enc/throw-unsupported))
             (putAll [_ _] (enc/throw-unsupported))
             (clear [_] (enc/throw-unsupported))

  ;; what a deref of an atom holding it answers: a Clojure sorted map
             d/IPlain
             (-plain [this] (to-plain this))

             IPrintWithWriter
             (-pr-writer [this w _] (e/-write w (print-sorted this)))

             java.lang.Object
             (toString [this] (print-sorted this))
             (equals [this other] (map-equiv this other))
             (hashCode [this] (p/hash-unordered (or (entries this (nodes root true)) ()))))

(eve-deftype EveSortedSet [^:SortedNode root cmp mta]
             ISortedColl
             (-root [_] root)
             (-cmp [_] cmp)
             (-map? [_] false)

             clojure.lang.IMeta
             (meta [_] mta)

             clojure.lang.IObj
             (withMeta [_ m] (->EveSortedSet root cmp m))

             clojure.lang.Counted
             (count [_] (-size root))

             clojure.lang.IPersistentCollection
             (cons [_ x] (->EveSortedSet (tree-conj root (cmp-of cmp) x) cmp mta))
             (empty [_] (->EveSortedSet nil cmp mta))
             (equiv [this other] (set-equiv this other))

             clojure.lang.Seqable
             (seq [this] (entries this (nodes root true)))

             clojure.lang.Reversible
             (rseq [this] (entries this (nodes root false)))

             clojure.lang.ILookup
             (valAt [this x] (lookup this x nil))
             (valAt [this x not-found] (lookup this x not-found))

             clojure.lang.IFn
             (invoke [this x] (lookup this x nil))
             (invoke [this x not-found] (lookup this x not-found))

             clojure.lang.IPersistentSet
             (disjoin [_ x] (->EveSortedSet (tree-dissoc root (cmp-of cmp) x) cmp mta))
             (contains [_ x] (some? (find-node root (cmp-of cmp) x)))
             (get [this x] (lookup this x nil))

             clojure.lang.IHashEq
             (hasheq [this] (p/hash-unordered (or (entries this (nodes root true)) ())))

             clojure.lang.IEditableCollection
             (asTransient [_] (->TransientSortedSet root cmp true (alloc/scope-sio (alloc/current-sio))))

             d/IRetireDiff
             (-retire-diff [this new] (retire-diff this new))

  ;; the JVM's: a Clojure set is = to it only through java.util.Set
             java.lang.Iterable
             (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

             java.util.Set
             (size [_] (int (-size root)))
             (isEmpty [_] (zero? (-size root)))
             (toArray [this] (clojure.lang.RT/seqToArray (.seq this)))
             (containsAll [this c] (boolean (every? #(some? (find-node root (cmp-of cmp) %)) c)))
             (add [_ _] (enc/throw-unsupported))
             (remove [_ _] (enc/throw-unsupported))
             (addAll [_ _] (enc/throw-unsupported))
             (retainAll [_ _] (enc/throw-unsupported))
             (removeAll [_ _] (enc/throw-unsupported))
             (clear [_] (enc/throw-unsupported))

             d/IPlain
             (-plain [this] (to-plain this))

             IPrintWithWriter
             (-pr-writer [this w _] (e/-write w (print-sorted this)))

             java.lang.Object
             (toString [this] (print-sorted this))
             (equals [this other] (set-equiv this other))
             (hashCode [this] (p/hash-unordered (or (entries this (nodes root true)) ()))))

;;-----------------------------------------------------------------------------
;; What a collection holds that another does not: the walk above, from the
;; headers down
;;-----------------------------------------------------------------------------

(defn- hdr-of [sc] (if (instance? EveSortedMap sc) (EveSortedMap-offset sc) (EveSortedSet-offset sc)))

(defn retire-diff
  "[blocks values]: the blocks sorted collection old holds that new does not
   (new nil, or no sorted collection of old's kind: every block of old's) —
   its header, comparator and metadata blocks, its nodes and entries — and
   the [old new] key and value blocks of the entries among them (see gone)."
  [old new]
  (let [new (when (if (instance? EveSortedMap old) (instance? EveSortedMap new) (instance? EveSortedSet new)) new)
        hdr (hdr-of old)]
    (if (and new (same-block? hdr (hdr-of new)))
      [[] []]
      (let [field (fn [x f] (slab/read-i32 (sio-of x) (hdr-of x) f))
            own (fn [f] (let [b (field old f)]
                          (when-not (or (== -1 b) (and new (same-block? b (field new f)))) b)))
            blocks (cond-> [hdr] (own CMP_OFFSET) (conj (own CMP_OFFSET)) (own MTA_OFFSET) (conj (own MTA_OFFSET)))]
        (diff-at [blocks []] (-root old) (when new (-root new)) (cmp-of (-cmp old)))))))

(defn collect-retire-diff-offsets
  "The blocks sorted collection old holds that new does not (new nil: all of old's)."
  [old new]
  (first (retire-diff old new)))

;;-----------------------------------------------------------------------------
;; Transients: the tree held in this process, its writes path-copying in eve
;; memory, in the transient's own allocation log (a scope sio over the domain
;; it began in); each write frees what it superseded in the tree before it —
;; the walk between two versions only the transient holds, and only blocks it
;; made, never one of the collection it began from
;; (2026-10-21 ET). persistent! answers the
;; collection and ends the transient. A value a write replaces that is a
;; collection stays: a value read out of the transient may still be in use.
;;-----------------------------------------------------------------------------

(defn- live! [live?]
  (when-not live?
    (throw (ex-info "Transient used after persistent! call" {}))))

(defn- edit
  "(f root) allocating in sio, and the blocks of tree root it superseded that
   sio made freed."
  [sio cmp root f]
  (let [root' (binding [d/*domain-sio* sio] (f root))]
    (alloc/free-owned! sio (tree-diff root root' (cmp-of cmp)))
    root'))

(defn- end!
  "The collection (ctor root cmp nil) of a transient's last tree, made where
   the transient allocated; the log ends (the blocks stay, the collection's)."
  [sio ctor root cmp]
  (let [sc (binding [d/*domain-sio* sio] (ctor root cmp nil))]
    (alloc/stop-logging! sio)
    sc))

(deftype TransientSortedMap [^:mutable root cmp ^:mutable live? sio]
  ISortedTransient
  (-tdissoc! [this k] (live! live?) (set! root (edit sio cmp root #(tree-dissoc % (cmp-of cmp) k))) this)
  (-tdisj! [_ _] (throw (ex-info "disj! of a transient sorted map" {})))

  clojure.lang.Counted
  (count [_] (live! live?) (-size root))

  clojure.lang.ILookup
  (valAt [_ k]
    (live! live?)
    (if-let [n (find-node root (cmp-of cmp) k)] (-sv n) nil))
  (valAt [_ k not-found]
    (live! live?)
    (if-let [n (find-node root (cmp-of cmp) k)] (-sv n) not-found))

  clojure.lang.ITransientMap
  (assoc [this k v] (live! live?) (set! root (edit sio cmp root #(tree-assoc % (cmp-of cmp) k v))) this)
  (without [this k] (live! live?) (set! root (edit sio cmp root #(tree-dissoc % (cmp-of cmp) k))) this)
  (conj [this o]
    (live! live?)
    (let [c (cmp-of cmp)
          kvs (cond (map? o) (map (fn [en] [(key en) (val en)]) (e/seq o))
                    (or (vector? o) (eve.vec/eve-vector? o)) [[(e/nth o 0) (e/nth o 1)]]
                    :else [[(key o) (val o)]])]
      (set! root (reduce (fn [r [k v]] (edit sio cmp r #(tree-assoc % c k v))) root kvs)))
    this)
  (persistent [_] (live! live?) (set! live? false) (end! sio ->EveSortedMap root cmp)))

(deftype TransientSortedSet [^:mutable root cmp ^:mutable live? sio]
  ISortedTransient
  (-tdissoc! [_ _] (throw (ex-info "dissoc! of a transient sorted set" {})))
  (-tdisj! [this x] (live! live?) (set! root (edit sio cmp root #(tree-dissoc % (cmp-of cmp) x))) this)

  clojure.lang.Counted
  (count [_] (live! live?) (-size root))

  clojure.lang.ILookup
  (valAt [_ x]
    (live! live?)
    (if-let [n (find-node root (cmp-of cmp) x)] (-sk n) nil))
  (valAt [_ x not-found]
    (live! live?)
    (if-let [n (find-node root (cmp-of cmp) x)] (-sk n) not-found))

  clojure.lang.ITransientSet
  (conj [this x] (live! live?) (set! root (edit sio cmp root #(tree-conj % (cmp-of cmp) x))) this)
  (disjoin [this x] (live! live?) (set! root (edit sio cmp root #(tree-dissoc % (cmp-of cmp) x))) this)
  (persistent [_] (live! live?) (set! live? false) (end! sio ->EveSortedSet root cmp)))

;;-----------------------------------------------------------------------------
;; Public API
;;-----------------------------------------------------------------------------

(defn sorted-map
  "An eve sorted map of keys and values, ordered by compare."
  [& kvs]
  (build-map nil (partition 2 kvs)))

(defn sorted-map-by
  "An eve sorted map ordered by comparator cmp (a fn of two keys answering an
   integer, or a boolean predicate such as >)."
  [cmp & kvs]
  (build-map cmp (partition 2 kvs)))

;; an EveSortedSet is a set to eve.set's set gates (eve.set/eve-set?), on every host
(eve.set/register-set-kind! (fn [x] (instance? EveSortedSet x)))

(defn sorted-set
  "An eve sorted set of the keys, ordered by compare."
  [& ks]
  (build-set nil ks))

(defn sorted-set-by
  "An eve sorted set ordered by comparator cmp."
  [cmp & ks]
  (build-set cmp ks))

(defn find
  "The entry of map sc at key k ([k v], the stored key), or nil."
  [sc k]
  (when-let [n (find-node (-root sc) (cmp-of (-cmp sc)) k)]
    (enc/map-entry (-sk n) (-sv n))))

(defn dissoc! "Transient sorted map t without key k." [t k] (-tdissoc! t k))

(defn disj! "Transient sorted set t without x." [t x] (-tdisj! t x))

(defn- seq-from [sc k asc?] (entries sc (nodes-from (-root sc) (cmp-of (-cmp sc)) k asc?)))

(defn- bound-test [sc test k]
  (let [c (cmp-of (-cmp sc))]
    (fn [en] (test (c (entry-key sc en) k) 0))))

(defn subseq
  "Clojure's subseq: sc's entries (a map's) or keys (a set's) ascending, from
   those (test (cmp key k) 0) admits — test one of < <= > >=, or a start and an
   end test."
  ([sc test k]
   (let [include (bound-test sc test k)]
     (if (contains? #{> >=} test)
       (when-let [[en :as s] (seq-from sc k true)]
         (if (include en) s (next s)))
       (take-while include (entries sc (nodes (-root sc) true))))))
  ([sc start-test start-k end-test end-k]
   (when-let [[en :as s] (seq-from sc start-k true)]
     (take-while (bound-test sc end-test end-k)
                 (if ((bound-test sc start-test start-k) en) s (next s))))))

(defn rsubseq
  "Clojure's rsubseq: subseq's entries, descending."
  ([sc test k]
   (let [include (bound-test sc test k)]
     (if (contains? #{< <=} test)
       (when-let [[en :as s] (seq-from sc k false)]
         (if (include en) s (next s)))
       (take-while include (entries sc (nodes (-root sc) false))))))
  ([sc start-test start-k end-test end-k]
   (when-let [[en :as s] (seq-from sc end-k false)]
     (take-while (bound-test sc start-test start-k)
                 (if ((bound-test sc end-test end-k) en) s (next s))))))

(defn rank
  "The index of key k in sc's order, or -1 when absent."
  [sc k]
  (tree-rank (-root sc) (cmp-of (-cmp sc)) k))

(defn nth-entry
  "sc's i-th entry (a map's [k v]) or key (a set's) in its order; throws out of
   range."
  [sc i]
  (entry-of sc (tree-nth (-root sc) i)))

(defn shape
  "The tree of sc as nested [key left right] (nil for no subtree): what one
   canonical tree means — equal for equal key sets, on every host."
  [sc]
  (tree-shape (-root sc)))

(defn sorted-coll?
  "True for an eve sorted map or set."
  [x]
  (satisfies? ISortedColl x))
