(ns eve.tally
  "THE COUNT — how many use a block, kept by the writer on the block's descriptor row, with deferred release.

   Decided: 2026-10-20, \"you have to tally it\" (VS's docs/reclaim/atom-domains.md §4.1); 2026-10-21
   and 2026-10-22, every block has a descriptor row — eve's 28-byte row, the count a u32 at +8, the flags beside the
   coalescer's lock at +20 (doc/unification/FORMAT-DECISIONS.md §11). This namespace is the one implementation:
   eve's shared code, read as-is by every host and by ValueScript's kernel (\"What I want is for the vs2wat to be
   able to read the eve-cljc impls as is, written in cljc, in the kernel\", 2026-10-18).

   WHAT A COUNT IS. A block's count is its number of holders: an atom's slot (it holds the atom's current value) and
   every other block that references it. 0 means unused; all ones (CPERM) is held for ever; CDEAD is a block a flush
   has claimed. The flags: PUB (a holder took it, so its own references are held: a block at 0 with PUB is parked,
   waiting for the flush), QUEUED (its one entry is on the zero queue), LEAF (held as a leaf: its bytes are never
   read as references), TAKEN (taken back while its entry was queued: that entry's epoch is older than the block's
   next zero). Bits 3-6 (LOGGED, LOGHELD, KEPT, SIDE) are ValueScript's runtime domain's; eve never sets them. A
   fresh block's words are 0: the free that came before reset them. Every change is one atomic on one word of the
   block's own row.

   THE OPERATIONS.
     hold: a holder gains a block. Counted: +1 (a parked 0 is TAKEN BACK to 1). Uncounted: it becomes counted at 1
       and every block it references is held in turn — storing a fresh tree counts its references once; storing a
       block another holder has costs one increment. Nothing reads a counted block's references again.
     drop: a holder lets a block go. k -> k-1; 1 -> 0 parks it on the zero queue at the current epoch. Not descended.
     flush: an entry whose epoch no pinned reader can still see is taken off the queue; a block whose count is STILL
       0 is claimed (0 -> CDEAD, one CAS: a hold racing in is refused), its references read, the block freed, and
       only then its references dropped — a child reaching 0 is parked at the epoch then (a reader may have reached
       it through another holder that let go meanwhile). With no reader pinned the flush runs again over what it
       parked, so a dropped tree is freed whole.
     a write: the slot holds the new root before its CAS; a won CAS drops the old root, a lost one the new.
     store: a reference written in place into a counted block (an eve-deftype's set! or cas!) — hold new, then drop
       old; an uncounted block holds nothing yet (its publish counts what it holds then).

   WHAT A BLOCK REFERENCES (one level, by the kind its holder names — a HAMT node or a vector node does not say what
   it is, so the kind travels with the reference and with the queue entry): a collection root header (map, set,
   vector, list) also references its digest-cache side block when one is stamped (eve.digest, layout B-prime: the
   pointer lives in the POINTER column of the header's descriptor — coalesc/DESC_DIGEST_PTR — and is validated on every read here); a map header its root node, a HAMT node
   its children and the slab pointers among its values, a set header its root, a set node its children (a set's
   elements are flat: FORMAT-DECISIONS.md §3), a vector header its trie root and its tail, a trie node the live slots
   its count and shift make live (a slot past them holds stale bytes: never read), a vector element's block the
   pointer it may be, a list header its head, a list node its next and the pointer its value may be. A slab pointer
   is [0xEE 0xDB tag offset:i32]: tags 0x10-0x13 a map, set, vector or list header; 0x1C-0x1E an array or obj block
   (one block); 0x1F an eve value array (its pointer slots and block slots, eve.deftype.array — written once, while
   it is new); 0x40-0xEC an eve-deftype instance. A value block [0xEE 0xDB 0x19 block len] is one block. A scalar
   root block, an array, an obj and a value block are leaves. An eve-deftype instance (DEFTYPE) references what its
   type's fields name — a ^:MyEveType field another instance, a serialized field its [len][EVE bytes] block — as
   eve.deftype.macros registers them for every eve-deftype it expands (eve.deftype-proto.serialize/type-refs); a
   reference field written in place (set!, cas!) keeps its counts by the store rule (store!). An instance whose type
   is not loaded in this process, and any block of a kind not read here (OPAQUE), is held as a leaf and never freed
   here — leak over use-after-free, the kernel's rule (tally.vs, the flush's gate): at 0 its count goes back to 0.

   RULE 12. Nothing in a block: the count and the flags are the row's, out of band. Nothing zeroed on reuse: a free
   resets the freed block's row (its management record), never its bytes. The zero queue is the domain state's own
   queue, as eve's retire queue was: each process's holds what it parked, freed behind the pins every process
   publishes; the rows are in the domain's files, so every process sees one count."
  (:refer-clojure :exclude [unsigned-bit-shift-right volatile! vswap!])
  (:require [eve.host :refer [unsigned-bit-shift-right volatile! vswap!]]
            [eve.slab-io :as slab]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]
            [eve.hamt-util :refer [popcount32]]
            [eve.int32 :as i32]))

;; ---------------------------------------------------------------------------
;; The descriptor's two words (its COUNT and FLAGS column entries: the handle
;; [[count-region count-off] [flags-region flags-off]] from alloc/desc-of)
;; ---------------------------------------------------------------------------

(def ^:const CPERM -1)      ;; the count all ones: held for ever
(def ^:const CDEAD -2)      ;; claimed by a flush (being freed): a hold of it is refused
(def ^:const CMAX -3)       ;; the last count a hold raises by one; the next hold makes it permanent
(def ^:const F_PUB 2)       ;; bit 1: a holder took it — its references are held
(def ^:const F_QUEUED 4)    ;; bit 2: its entry is on the zero queue
(def ^:const F_LEAF 128)    ;; bit 7: held as a leaf
(def ^:const F_TAKEN 256)   ;; bit 8: taken back while its entry was queued

(defn- cnt [row] (slab/region-load-i32 (nth (nth row 0) 0) (nth (nth row 0) 1)))
(defn- ccas [row old new] (slab/region-cas-i32! (nth (nth row 0) 0) (nth (nth row 0) 1) old new))
(defn- flags [row] (slab/region-load-i32 (nth (nth row 1) 0) (nth (nth row 1) 1)))
(defn- fcas [row old new] (slab/region-cas-i32! (nth (nth row 1) 0) (nth (nth row 1) 1) old new))

(defn- set-flag!
  "f set on the row's flags (the word's other bits, the coalescer's lock among them, kept): true when it was set
   already, false when this call set it."
  [row f]
  (loop []
    (let [t (flags row)]
      (cond (not (zero? (bit-and t f))) true
            (== t (fcas row t (bit-or t f))) false
            :else (recur)))))

(defn- clear-flags!
  "fs cleared on the row's flags, every other bit kept."
  [row fs]
  (loop []
    (let [t (flags row)]
      (when-not (== t (fcas row t (bit-and t (bit-not fs))))
        (recur)))))

;; a block's offset: NIL (-1), an atom slot's CLAIMED sentinel (-2) and every other offset of class 7 name no block
(defn- live? [off] (< (alloc/decode-class-idx off) alloc/SENTINEL_CLASS_IDX))

;; ---------------------------------------------------------------------------
;; Kinds and references
;; ---------------------------------------------------------------------------

(defn root-kind
  "The kind of the root value block at off (an atom slot's value), from its header's type-id byte."
  [sio off]
  (case (int (slab/read-u8 sio off 0))
    0xED :map
    0xEE :set
    0x12 :vec
    0x13 :list
    0x01 :leaf      ;; a scalar root block
    0x1D :leaf      ;; an array
    0x1E :leaf      ;; an obj
    0x1F :varray    ;; an eve value array (eve.deftype.array)
    (let [t (int (slab/read-u8 sio off 0))]
      (if (and (>= t 0x40) (<= t 0xEC)) :deftype :opaque))))

(defn- tag-kind
  "The kind of the block a slab pointer with this tag names, or nil for a tag that names no block."
  [tag]
  (cond (== tag 0x10) :map
        (== tag 0x11) :set
        (== tag 0x12) :vec
        (== tag 0x13) :list
        (and (>= tag 0x1C) (<= tag 0x1E)) :leaf
        (== tag 0x1F) :varray
        (and (>= tag 0x40) (<= tag 0xEC)) :deftype
        :else nil))

(defn- value-ref
  "[offset kind] when the len value bytes at pos of block blk are a slab pointer or a value block, else nil."
  [sio blk pos len]
  (when (and (or (== len 7) (== len 11))
             (== 0xEE (slab/read-u8 sio blk pos))
             (== 0xDB (slab/read-u8 sio blk (+ pos 1))))
    (let [tag (slab/read-u8 sio blk (+ pos 2))
          k (if (== len 11)
              (when (== tag 0x19) :leaf)
              (tag-kind tag))]
      (when k [(slab/read-i32 sio blk (+ pos 3)) k]))))

(defn- varray-refs
  "An eve value array (eve.deftype.array: [0x1F][pad 3][n][kind:u8 x n][tag:u8 x n][pad to 4][payload:i32 x n],
   written once while new): a pointer slot (kind 2) its tagged value, a block slot (kind 4) its [len][EVE bytes]
   block."
  [sio off]
  (let [n (slab/read-i32 sio off 4)
        base (bit-and (+ 8 (* 2 n) 3) (bit-not 3))]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [kind (slab/read-u8 sio off (+ 8 i))
              p (slab/read-i32 sio off (+ base (* 4 i)))
              r (cond (== kind 2) (when-let [k (tag-kind (slab/read-u8 sio off (+ 8 n i)))] [p k])
                      (== kind 4) [p :vval]
                      :else nil)]
          (recur (inc i) (if (and r (live? p)) (conj acc r) acc)))))))

(defn- kv-refs
  "acc with the slab pointers among the n key/value pairs from pos of block off ([klen][key][vlen][value] each)."
  [sio off pos n acc]
  (loop [i 0 pos pos acc acc]
    (if (>= i n)
      acc
      (let [klen (slab/read-i32 sio off pos)
            vpos (+ pos 4 klen)
            vlen (slab/read-i32 sio off vpos)
            r (value-ref sio off (+ vpos 4) vlen)]
        (recur (inc i) (+ vpos 4 vlen) (if r (conj acc r) acc))))))

(defn- children
  "acc with the n child offsets from byte 12 of node off, each of kind k. A child slot may be NIL with its bit still
   set (eve's own walkers skip it: map.cljc collect-tree-diff-offsets, set.cljc collect-hamt-offsets) — MEASURED
   2026-10-22, eve.thread's meta-atom: a bitmap node with one child bit and the child -1."
  [sio off n k acc]
  (loop [i 0 acc acc]
    (if (>= i n)
      acc
      (let [c (slab/read-i32 sio off (+ 12 (* 4 i)))]
        (recur (inc i) (if (live? c) (conj acc [c k]) acc))))))

(defn- hamt-refs
  "A map's HAMT node: a bitmap node's children and its values' pointers; a collision node's values' pointers."
  [sio off]
  (let [t (slab/read-u8 sio off 0)]
    (cond
      (== t 1) (let [dc (popcount32 (slab/read-i32 sio off 4))
                     nc (popcount32 (slab/read-i32 sio off 8))]
                 (kv-refs sio off (+ 12 (* 4 nc) (* 4 dc)) dc (children sio off nc :hamt [])))
      (== t 3) (kv-refs sio off 8 (slab/read-u8 sio off 1) [])
      :else [])))

(defn- shamt-refs
  "A set's HAMT node: a bitmap node's children (its elements are flat)."
  [sio off]
  (if (== 1 (slab/read-u8 sio off 0))
    (children sio off (popcount32 (slab/read-i32 sio off 8)) :shamt [])
    []))

(defn- type-layout
  "The reference fields [[field-offset kind] …] of the eve-deftype instance at off (its header: type-id, then the
   name hash's three bytes), or nil when its type is not loaded in this process."
  [sio off]
  (ser/type-refs (slab/read-u8 sio off 0)
                 (bit-or (slab/read-u8 sio off 1)
                         (bit-shift-left (slab/read-u8 sio off 2) 8)
                         (bit-shift-left (slab/read-u8 sio off 3) 16))))

(defn- readable?
  "True when this process reads what a block of kind at off references (an eve-deftype's type loaded here; never
   an opaque block)."
  [sio off kind]
  (case kind
    :opaque false
    :deftype (some? (type-layout sio off))
    true))

(defn- tail-offset
  "How many elements a vector of cnt elements keeps in its trie (the rest are its tail's)."
  [cnt]
  (if (< cnt 32) 0 (bit-shift-left (unsigned-bit-shift-right (dec cnt) 5) 5)))

(defn- live-slots
  "How many slots of the trie node at shift sh covering the elements from base are live in a trie of toff."
  [toff base sh]
  (let [n (- toff base)]
    (if (<= n 0)
      0
      (let [w (bit-shift-left 1 sh)]
        (min 32 (quot (+ n (dec w)) w))))))

(defn- slot-refs
  "acc with the live slots 0..n-1 of vector node off, each [slot (kind-of i)] (a NIL slot is a nil element)."
  [sio off n kind-of acc]
  (loop [i 0 acc acc]
    (if (>= i n)
      acc
      (let [c (slab/read-i32 sio off (* 4 i))]
        (recur (inc i) (if (live? c) (conj acc [c (kind-of i)]) acc))))))

(defn- cache+
  "acc with block off's digest-cache side block (eve.digest: the pointer in
   the POINTER column of off's descriptor — the desc-of handle's third pair),
   when the word names a live, allocated block. The side block is held as a
   leaf: it references nothing.
   The word is only ever 0, -1 (no cache) or a stamped offset; a stale word
   (its block freed and the offset reused) fails the allocated? check or the
   reader's own validation (eve.digest re-checks magic + back-pointer)."
  [sio off acc]
  (if-let [row (alloc/desc-of sio off)]
    (let [p (slab/region-load-i32 (nth (nth row 2) 0) (nth (nth row 2) 1))]
      (if (and (not (zero? p)) (not (== p alloc/NIL_OFFSET)) (live? p) (alloc/allocated? sio p))
        (conj acc [p :leaf])
        acc))
    acc))

(defn- refs
  "The [offset kind] pairs block off of kind references, one level."
  [sio off kind]
  (let [k (if (vector? kind) (nth kind 0) kind)]
    (case k
      :map   (let [r (slab/read-i32 sio off 8)] (cache+ sio off (if (live? r) [[r :hamt]] [])))
      :hamt  (hamt-refs sio off)
      :set   (let [r (slab/read-i32 sio off 8)] (cache+ sio off (if (live? r) [[r :shamt]] [])))
      :shamt (shamt-refs sio off)
      :vec   (let [cnt  (slab/read-i32 sio off 4)
                   sh   (slab/read-i32 sio off 8)
                   root (slab/read-i32 sio off 12)
                   tail (slab/read-i32 sio off 16)
                   tl   (slab/read-i32 sio off 20)
                   toff (tail-offset cnt)]
               (cache+ sio off
                       (cond-> []
                         (and (pos? toff) (live? root)) (conj [root [:vnode sh 0 toff]])
                         (live? tail) (conj [tail [:vleaf tl]]))))
      ;; a trie node at shift sh covering the elements from base: a leaf's (sh 0) live slots are element blocks
      :vnode (let [[_ sh base toff] kind
                   w (bit-shift-left 1 sh)]
               (slot-refs sio off (live-slots toff base sh)
                          (if (zero? sh) (fn [_] :vval) (fn [i] [:vnode (- sh 5) (+ base (* i w)) toff]))
                          []))
      :vleaf (slot-refs sio off (nth kind 1) (fn [_] :vval) [])
      :vval  (let [r (value-ref sio off 4 (slab/read-i32 sio off 0))] (if r [r] []))
      :list  (let [h (slab/read-i32 sio off 8)] (cache+ sio off (if (live? h) [[h :lnode]] [])))
      :lnode (let [nx (slab/read-i32 sio off 0)
                   r (value-ref sio off 8 (slab/read-i32 sio off 4))]
               (cond-> [] (live? nx) (conj [nx :lnode]) r (conj r)))
      :varray (varray-refs sio off)
      :deftype (into [] (keep (fn [[foff fk]]
                                (let [c (slab/read-i32 sio off foff)] (when (live? c) [c fk]))))
                     (type-layout sio off))
      [])))

;; ---------------------------------------------------------------------------
;; hold / drop
;; ---------------------------------------------------------------------------

(defn- hold-one!
  "One holder gains block off: :new when this hold made it counted (its references are the caller's to hold),
   :held, :norow (the block has no row: its references are held on every hold, and it is never freed here — a
   leak, never a use-after-free), or nil (a dangling reference: the block is free, or a flush has claimed it)."
  [sio off leaf?]
  (if-let [row (alloc/desc-of sio off)]
    (loop []
      (let [t (flags row)]
        (if-not (zero? (bit-and t F_PUB))
          (let [c (cnt row)]
            (cond
              (== c CPERM) :held
              (== c CDEAD) nil
              :else (do
                      ;; a parked 0 taken back: TAKEN goes on FIRST while its entry is queued, so a flush that sees
                      ;; it at 0 again (dropped since) queues the entry again at the epoch then
                      (when (and (zero? c) (not (zero? (bit-and t F_QUEUED))))
                        (set-flag! row F_TAKEN))
                      (if (== c (ccas row c (if (== c CMAX) CPERM (i32/i32 (inc c)))))
                        :held
                        (recur)))))
          (when (alloc/allocated? sio off)
            ;; publish: whoever's CAS takes PUB holds the references; the count follows
            (let [nt (if leaf? (bit-or t F_PUB F_LEAF) (bit-or t F_PUB))]
              (if (== t (fcas row t nt))
                (do (slab/region-add-i32! (nth (nth row 0) 0) (nth (nth row 0) 1) 1)
                    :new)
                (recur)))))))
    :norow))

(defn hold!
  "A holder gains the block at off of kind, and through each block that hold makes counted, everything it
   references (a worklist: no recursion depth bound on a long list or a deep tree)."
  [sio off kind]
  (when (live? off)
    (loop [work [[off kind]]]
      (when (seq work)
        (let [[o k] (peek work)
              work (pop work)
              leaf? (not (readable? sio o k))
              r (hold-one! sio o leaf?)]
          (recur (if (and (not leaf?) (or (= r :new) (= r :norow)))
                   (into work (refs sio o k))
                   work)))))))

(defn hold-cache!
  "A digest-cache side block was just stamped onto the root header at hdr
   (eve.digest): when hdr is already counted (a holder took it), the root
   holds its cache from now — one hold here. When hdr is not counted yet,
   nothing is held: the publish walk that counts hdr reads its references
   then, and the stamped cache is among them (refs). A stamp racing that
   walk can in the worst interleaving hold the cache twice or not at all —
   a bounded 48-byte leak, never a use-after-free: a drop of an unheld
   block is a no-op, and a doubly-held cache simply stays."
  [sio hdr cache-off]
  (when-let [row (alloc/desc-of sio hdr)]
    (when (not (zero? (bit-and (flags row) F_PUB)))
      (hold! sio cache-off :leaf))))

(defn- drop-one!
  "One holder lets block off of kind go: :parked when its count reached 0 here (queued by park! unless its earlier
   entry still is), :dropped, or nil (a hold never taken: an uncounted block, a second drop of one hold)."
  [sio off kind park!]
  (when-let [row (alloc/desc-of sio off)]
    (when-not (zero? (bit-and (flags row) F_PUB))
      (loop []
        (let [c (cnt row)]
          (cond
            (== c CPERM) :dropped
            (or (zero? c) (== c CDEAD)) nil
            (not (== c (ccas row c (i32/i32 (dec c))))) (recur)
            (not (== c 1)) :dropped
            :else (do (when-not (set-flag! row F_QUEUED)
                        (park! off kind))
                      :parked)))))))

(defn drop!
  "A holder lets the block at off of kind go. park! queues a block that reached 0: (park! off kind)."
  [sio off kind park!]
  (when (live? off)
    (drop-one! sio off kind park!)))

(defonce ^:private parkers
  ;; [domain-root park!] pairs, matched by identity: a domain root is a host slab context, an object no host's
  ;; map may be asked to hash (clojerl's maps hash their keys through IHash, which a slab context does not
  ;; implement) — and identity is the registry's meaning anyway: one park! per domain object.
  (atom []))

(defn register-parker!
  "Domain sio's park!: where a store into one of its blocks queues a block that reached 0 (eve.atom registers its
   domain's zero queue)."
  [sio park!]
  (let [root (alloc/domain-root sio)]
    (swap! parkers (fn [ps] (conj (filterv (fn [[r _]] (not (identical? r root))) ps) [root park!]))))
  nil)

(defn unregister-parker!
  "Domain sio's park! forgotten (the domain closed)."
  [sio]
  (let [root (alloc/domain-root sio)]
    (swap! parkers (fn [ps] (filterv (fn [[r _]] (not (identical? r root))) ps))))
  nil)

(defn- parker
  "Domain sio's park!, or one that queues nothing (no domain registered: a block reaching 0 there stays parked — a
   leak, never a use-after-free)."
  [sio]
  (let [root (alloc/domain-root sio)]
    (or (some (fn [[r p]] (when (identical? r root) p)) @parkers)
        (fn [_ _] nil))))

(defn store!
  "A reference written in place into block c (an eve-deftype's reference field, by set! or cas!): old out, new in
   (offsets; -1 nil), of kind. A counted c holds new, then lets old go; an uncounted c holds nothing yet (its publish
   counts what it holds then), nor does a c held as a leaf (its references were never counted)."
  [sio c old new kind]
  (when-not (== old new)
    (when-let [row (alloc/desc-of sio c)]
      (let [t (flags row)]
        (when (and (not (zero? (bit-and t F_PUB))) (zero? (bit-and t F_LEAF)))
          (hold! sio new kind)
          (drop! sio old kind (parker sio)))))))

(defn hold-root!
  "An atom slot's hold of root value ptr, taken BEFORE the CAS that publishes it: a write that won the slot after
   this one cannot drop ptr before it is counted. A lost CAS takes it back with drop-root!."
  [sio ptr]
  (when (live? ptr) (hold! sio ptr (root-kind sio ptr))))

(defn drop-root!
  "An atom slot lets root value ptr go: the old value after a won CAS, the attempt's own new value after a lost one."
  [sio ptr park!]
  (when (live? ptr) (drop! sio ptr (root-kind sio ptr) park!)))

(defn free-unheld!
  "A lost attempt's allocations freed: every block of offs no holder took (still uncounted). Answers how many."
  [sio offs]
  (reduce (fn [n off]
            (let [row (alloc/desc-of sio off)]
              (if (and row (not (zero? (bit-and (flags row) F_PUB))))
                n
                (if (slab/free! sio off) (inc n) n))))
          0 offs))

(defn count-of
  "How many hold the block at off: -1 uncounted, 0 parked (or claimed by a flush), k, or 2147483647 (held for ever,
   or a count past 2^31 - 1)."
  [sio off]
  (if-let [row (alloc/desc-of sio off)]
    (if (zero? (bit-and (flags row) F_PUB))
      -1
      (let [c (cnt row)]
        (cond (== c CDEAD) 0 (neg? c) 2147483647 :else c)))
    -1))

;; ---------------------------------------------------------------------------
;; The flush
;; ---------------------------------------------------------------------------

(defn- free-claimed!
  "Block off, claimed at 0 (its flags t as read before the claim, row its row): its references read, the block freed,
   then its references dropped. A block held as a leaf has none read. A block whose references this process cannot
   read (opaque, an eve-deftype not loaded here) and that was not held as a leaf is never freed here: its claimed
   count goes back to 0, uncounted. Answers 1 freed, 0 not."
  [sio off kind row t park!]
  (if (or (= kind :opaque) (and (zero? (bit-and t F_LEAF)) (not (readable? sio off kind))))
    (do (slab/region-store-i32! (nth (nth row 0) 0) (nth (nth row 0) 1) 0) 0)
    (let [rs (if (zero? (bit-and t F_LEAF)) (refs sio off kind) [])   ;; read BEFORE the free hands the block back
          freed? (slab/free! sio off)]
      (doseq [[o k] rs] (drop-one! sio o k park!))
      (if freed? 1 0))))

(defn flush!
  "One flush of zero queue q (an atom of a vector of [{:off :kind :epoch} …]).

   Entries stand in epoch order — every park stamps the epoch at park time
   and the restore below preserves the order — so the flush examines only
   the epoch-eligible PREFIX of the queue: the entries stamped below
   safe-epoch. When the head entry is already at or above safe-epoch
   (safe-epoch non-nil) no entry can pass the epoch test, and the flush
   answers 0 without draining the queue at all. (An entry stranded behind
   a disordered head simply waits for a later flush; a flush only ever
   delays a free.)

   Each prefix entry is looked at once, on its block's own two words:
   - no longer QUEUED, or claimed by another flush: it leaves (freed by another path, or served);
   - taken back and held: QUEUED and TAKEN go — a drop that zeroed it meanwhile saw QUEUED and queued nothing, so it
     is parked again here;
   - TAKEN (taken back and dropped again since the entry's epoch): queued again at the epoch now;
   - still 0, its epoch below safe-epoch (the oldest pinned reader's; nil: none pinned): claimed and freed;
   - else it stays.

   The restore writes the queue back as stays ++ unexamined suffix ++
   parks: stays carry the smallest stamps, the suffix keeps the order it
   had, and every park — this scan's, or a concurrent conj's — carries
   the epoch now, the largest stamp there is, so the queue stands in
   epoch order again for the next flush.

   (ep-fn) is the domain's epoch now, (safe-fn) its safe epoch, sampled
   once at entry: a pin registered after the sample carries an epoch at
   or above the sampled safe epoch (a pin is the root epoch its reader
   observed, and the root epoch never goes back), so sampling before the
   drain is never less conservative than sampling after it. With no
   reader pinned (safe-epoch nil) the whole queue is eligible, and the
   flush runs again over the blocks it parked, so a dropped tree is
   freed whole. Answers the blocks freed."
  [sio q ep-fn safe-fn]
  (let [safe (safe-fn)
        head (first @q)]
    (if (and (some? safe) (some? head) (not (< (:epoch head) safe)))
      0
      (loop [rounds 0 freed 0]
        (let [entries (loop [] (let [v @q] (if (compare-and-set! q v []) v (recur))))
              ;; the epoch-eligible prefix: the entries stamped below
              ;; safe (the whole queue when safe is nil)
              k (if (nil? safe)
                  (count entries)
                  (loop [i 0]
                    (if (and (< i (count entries))
                             (< (:epoch (nth entries i)) safe))
                      (recur (inc i))
                      i)))
              prefix (subvec entries 0 k)
              suffix (subvec entries k)
              parked (volatile! 0)
          park! (fn [o k] (vswap! parked inc) (swap! q conj {:off o :kind k :epoch (ep-fn)}))
          stay (volatile! [])
          n (reduce
             (fn [n {:keys [off kind epoch] :as e}]
               (let [row (alloc/desc-of sio off)
                     t (if row (flags row) 0)
                     c (if row (cnt row) CDEAD)]
                 (cond
                   (or (zero? (bit-and t F_QUEUED)) (== c CDEAD)) n
                   (not (zero? c))
                   (do (clear-flags! row (bit-or F_QUEUED F_TAKEN))
                       (when (and (zero? (cnt row)) (not (set-flag! row F_QUEUED)))
                         (park! off kind))
                       n)
                   (not (zero? (bit-and t F_TAKEN)))
                   (do (clear-flags! row F_TAKEN) (park! off kind) n)
                   (not (or (nil? safe) (< epoch safe)))
                   (do (vswap! stay conj e) n)
                   ;; THE STILL-ZERO CHECK, one CAS: 0 -> CDEAD claims it (a hold racing in is refused)
                   (not (zero? (ccas row 0 CDEAD)))
                   (do (vswap! stay conj e) n)
                   :else
                   (do (clear-flags! row (bit-or F_QUEUED F_PUB))
                       (+ n (free-claimed! sio off kind row t park!))))))
             0 prefix)]
          ;; restore in epoch order: stays, then the unexamined
          ;; suffix, then whatever this scan (park!) or a concurrent
          ;; conj put on q since the drain — all stamped at the
          ;; epoch now, behind everything else
          (swap! q (fn [parks] (into [] (concat @stay suffix parks))))
          (if (and (nil? safe) (pos? @parked) (< rounds 4096))
            (recur (inc rounds) (+ freed n))
            (+ freed n)))))))
