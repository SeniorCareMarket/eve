(ns eve.vec
  "Eve persistent vector — unified CLJ/CLJS implementation.

   Bit-partitioned trie with tail optimization (Bagwell/Hickey vector).
   Uses ISlabIO protocol for all memory access, sio threaded everywhere.

   Uses eve/deftype macro: CLJ protocol names, auto-translated on CLJS."
  (:refer-clojure :exclude [alength unsigned-bit-shift-right volatile! vswap! transient
                            persistent! conj! bytes])
  (:require
   [eve.host :refer [alength unsigned-bit-shift-right volatile! vswap! transient persistent!
                     conj! bytes]]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc
    :refer [NIL_OFFSET]]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.encoding :as enc]
   [eve.platform :as p]
   [eve.core :as ecore]
   [eve.rseq :as ers]
   [eve.deftype-proto.macros :as eve]
   [eve.codec :as codec]
   [eve.digest :as dg]))

;;=============================================================================
;; Shared Constants
;;=============================================================================

(def ^:const NODE_SIZE 32)
(def ^:const SHIFT_STEP 5)
(def ^:const MASK 0x1f)

(def EveVector-type-id 0x12)
(def SabVecRoot-type-id EveVector-type-id) ;; backward compat

(def ^:const SABVECROOT_CNT_OFFSET 4)
(def ^:const SABVECROOT_SHIFT_OFFSET 8)
(def ^:const SABVECROOT_ROOT_OFFSET 12)
(def ^:const SABVECROOT_TAIL_OFFSET 16)
(def ^:const SABVECROOT_TAIL_LEN_OFFSET 20)
(def ^:const SABVECROOT_HEADER_SIZE 24)

;;=============================================================================
;; Platform-specific helpers
;;=============================================================================

(defn- serialize-element-bytes [sio v]
  (codec/encode-value sio v))

(defn- deserialize-element-bytes
  [val-bytes sio]
  (codec/decode sio val-bytes))

(defn- default-sio
  "The slab I/O a constructor given none allocates in: the running swap!'s
   domain, else this process's own (eve.deftype-proto.alloc/current-sio)."
  []
  (alloc/current-sio))

(defn- bytes-length [ba]
  (alength ^bytes ba))

;;=============================================================================
;; Node operations (via ISlabIO)
;;=============================================================================

(defn- alloc-node! [sio]
  (let [node-off (slab/alloc! sio (* NODE_SIZE 4))]
    (dotimes [i NODE_SIZE]
      (slab/write-i32! sio node-off (* i 4) NIL_OFFSET))
    node-off))

(defn node-get [sio node-off i]
  (slab/read-i32 sio node-off (* i 4)))

(defn- node-set! [sio node-off i val]
  (slab/write-i32! sio node-off (* i 4) val)
  node-off)

(defn- clone-node! [sio src-off]
  (let [byte-size (* NODE_SIZE 4)
        new-off (slab/alloc! sio byte-size)]
    (slab/copy-block! sio new-off 0 src-off 0 byte-size)
    new-off))

;;=============================================================================
;; Value block operations — [len:u32][bytes...]
;;=============================================================================

(defn- make-value-block! [sio val-bytes]
  (let [val-len (bytes-length val-bytes)
        total-size (+ 4 val-len)
        slab-off (slab/alloc! sio total-size)]
    (slab/write-i32! sio slab-off 0 val-len)
    (when (pos? val-len)
      (slab/write-bytes! sio slab-off 4 val-bytes))
    slab-off))

(defn- read-value-block [sio val-off]
  (when (not= val-off NIL_OFFSET)
    (let [val-len (slab/read-i32 sio val-off 0)
          val-bs  (slab/read-bytes sio val-off 4 val-len)]
      (deserialize-element-bytes val-bs sio))))

;;=============================================================================
;; Trie algorithms (unified via ISlabIO)
;;=============================================================================

(defn- tail-offset-calc [cnt]
  (if (< cnt NODE_SIZE)
    0
    (bit-shift-left (unsigned-bit-shift-right (dec cnt) SHIFT_STEP) SHIFT_STEP)))

(defn nth-impl [sio cnt shift root tail n]
  (let [toff (tail-offset-calc cnt)]
    (if (>= n toff)
      (read-value-block sio (node-get sio tail (- n toff)))
      (let [val-off (loop [node-off root sh shift]
                      (let [idx (bit-and (unsigned-bit-shift-right n sh) MASK)]
                        (if (zero? sh)
                          (node-get sio node-off idx)
                          (recur (node-get sio node-off idx) (- sh SHIFT_STEP)))))]
        (read-value-block sio val-off)))))

(defn- new-path [sio shift leaf-offset]
  (if (zero? shift)
    leaf-offset
    (let [node-off (alloc-node! sio)]
      (node-set! sio node-off 0 (new-path sio (- shift SHIFT_STEP) leaf-offset))
      node-off)))

(defn- push-tail [sio shift parent-off tail-off cnt]
  (let [idx (bit-and (unsigned-bit-shift-right (dec cnt) shift) MASK)
        new-parent-off (if (== parent-off NIL_OFFSET)
                         (alloc-node! sio)
                         (clone-node! sio parent-off))]
    (if (== shift SHIFT_STEP)
      (do (node-set! sio new-parent-off idx tail-off)
          new-parent-off)
      (let [child-off (node-get sio new-parent-off idx)
            new-child-off (if (== child-off NIL_OFFSET)
                            (new-path sio (- shift SHIFT_STEP) tail-off)
                            (push-tail sio (- shift SHIFT_STEP) child-off tail-off cnt))]
        (node-set! sio new-parent-off idx new-child-off)
        new-parent-off))))

(defn- do-assoc [sio shift node-off n val-off]
  (let [new-node-off (clone-node! sio node-off)
        idx (bit-and (unsigned-bit-shift-right n shift) MASK)]
    (if (zero? shift)
      (do (node-set! sio new-node-off idx val-off)
          new-node-off)
      (let [child-off (node-get sio node-off idx)
            new-child-off (do-assoc sio (- shift SHIFT_STEP) child-off n val-off)]
        (node-set! sio new-node-off idx new-child-off)
        new-node-off))))

(defn- pop-tail
  "The trie with its last leaf removed, for a vector of cnt elements (the count
   before the pop) whose tail holds one: the leaf holds element cnt-2, the new
   last (Clojure's PersistentVector.popTail, subidx ((cnt - 2) >>> level)). The
   index once read (dec cnt), one past it: the pop wrote NIL into a slot that was
   not live, left the leaf in the trie as well as in the tail, and at cnt 1025 the
   index wrapped to 0 and the pop dropped the whole trie."
  [sio shift node-off cnt]
  (let [idx (bit-and (unsigned-bit-shift-right (- cnt 2) shift) MASK)]
    (cond
      (> shift SHIFT_STEP)
      (let [child-off (node-get sio node-off idx)
            new-child (pop-tail sio (- shift SHIFT_STEP) child-off cnt)]
        (if (and (== new-child NIL_OFFSET) (zero? idx))
          NIL_OFFSET
          (let [new-node-off (clone-node! sio node-off)]
            (node-set! sio new-node-off idx new-child)
            new-node-off)))

      (zero? idx)
      NIL_OFFSET

      :else
      (let [new-node-off (clone-node! sio node-off)]
        (node-set! sio new-node-off idx NIL_OFFSET)
        new-node-off))))

;;=============================================================================
;; Header read/write
;;=============================================================================

(defn write-vec-header! [sio cnt shift root tail tail-len]
  (let [hdr-off (slab/alloc! sio SABVECROOT_HEADER_SIZE)]
    (slab/write-u8!  sio hdr-off 0 SabVecRoot-type-id)
    (slab/write-i32! sio hdr-off SABVECROOT_CNT_OFFSET cnt)
    (slab/write-i32! sio hdr-off SABVECROOT_SHIFT_OFFSET shift)
    (slab/write-i32! sio hdr-off SABVECROOT_ROOT_OFFSET root)
    (slab/write-i32! sio hdr-off SABVECROOT_TAIL_OFFSET tail)
    (slab/write-i32! sio hdr-off SABVECROOT_TAIL_LEN_OFFSET tail-len)
    hdr-off))

(defn read-vec-header [sio header-off]
  [(slab/read-i32 sio header-off SABVECROOT_CNT_OFFSET)
   (slab/read-i32 sio header-off SABVECROOT_SHIFT_OFFSET)
   (slab/read-i32 sio header-off SABVECROOT_ROOT_OFFSET)
   (slab/read-i32 sio header-off SABVECROOT_TAIL_OFFSET)
   (slab/read-i32 sio header-off SABVECROOT_TAIL_LEN_OFFSET)])

;;=============================================================================
;; Constructors (forward declare — actual impls after deftype for CLJ compat)
;;=============================================================================

(declare make-vec-impl)
(declare ->transient-vec)
(declare eve-vector?)

;;=============================================================================
;; Conj / AssocN / Pop implementations (used by EveVector deftype — not bb)
;;=============================================================================

(do
(defn- vec-conj-impl [sio cnt shift root tail tail-len v]
  (let [val-bytes (serialize-element-bytes sio v)
        val-off (make-value-block! sio val-bytes)]
    (if (< tail-len NODE_SIZE)
      (let [new-tail (clone-node! sio tail)]
        (node-set! sio new-tail tail-len val-off)
        (make-vec-impl sio (inc cnt) shift root new-tail (inc tail-len)))
      (let [old-tail tail
            new-tail (alloc-node! sio)
            _ (node-set! sio new-tail 0 val-off)]
        (if (> (unsigned-bit-shift-right cnt SHIFT_STEP)
               (bit-shift-left 1 shift))
          (let [new-root-off (alloc-node! sio)
                _ (node-set! sio new-root-off 0 root)
                new-shift (+ shift SHIFT_STEP)
                _ (node-set! sio new-root-off 1 (new-path sio shift old-tail))]
            (make-vec-impl sio (inc cnt) new-shift new-root-off new-tail 1))
          (let [new-root (if (== root NIL_OFFSET)
                           (let [nr (alloc-node! sio)]
                             (node-set! sio nr 0 old-tail)
                             nr)
                           (push-tail sio shift root old-tail cnt))]
            (make-vec-impl sio (inc cnt) shift new-root new-tail 1)))))))

(defn- vec-assoc-n-impl [sio cnt shift root tail tail-len n v]
  (let [val-bytes (serialize-element-bytes sio v)
        val-off (make-value-block! sio val-bytes)
        toff (tail-offset-calc cnt)]
    (if (>= n toff)
      (let [new-tail (clone-node! sio tail)]
        (node-set! sio new-tail (- n toff) val-off)
        (make-vec-impl sio cnt shift root new-tail tail-len))
      (let [new-root (do-assoc sio shift root n val-off)]
        (make-vec-impl sio cnt shift new-root tail tail-len)))))

(defn- vec-pop-impl [sio cnt shift root tail tail-len]
  (cond
    (== cnt 1)
    (let [new-tail (alloc-node! sio)]
      (make-vec-impl sio 0 SHIFT_STEP NIL_OFFSET new-tail 0))

    (> tail-len 1)
    (let [new-tail (alloc-node! sio)]
      (dotimes [i (dec tail-len)]
        (node-set! sio new-tail i (node-get sio tail i)))
      (make-vec-impl sio (dec cnt) shift root new-tail (dec tail-len)))

    :else
    (let [new-cnt (dec cnt)
          new-tail-off (loop [node-off root sh shift]
                         (let [idx (bit-and (unsigned-bit-shift-right (dec new-cnt) sh) MASK)]
                           (if (zero? sh)
                             node-off
                             (recur (node-get sio node-off idx) (- sh SHIFT_STEP)))))
          new-root (pop-tail sio shift root cnt)
          new-toff (tail-offset-calc new-cnt)
          ;; a level goes when the root has one live child — read from the count,
          ;; never from a slot past the live ones (Clojure tests array[1] == null)
          [new-root new-shift]
          (cond
            (== new-root NIL_OFFSET)
            [NIL_OFFSET SHIFT_STEP]

            (and (> shift SHIFT_STEP)
                 (<= new-toff (bit-shift-left 1 shift)))
            (let [child (node-get sio new-root 0)]
              ;; the root pop-tail just cloned, dropped for its one child: never published, freed now (it was
              ;; left allocated, 2026-10-21 ET)
              (slab/free! sio new-root)
              [child (- shift SHIFT_STEP)])

            :else
            [new-root shift])

          new-tl (- new-cnt new-toff)]
      (make-vec-impl sio new-cnt new-shift new-root new-tail-off new-tl)))) ;; end vec-pop-impl
)

;;=============================================================================
;; Liveness — what a vector's header says is current
;;=============================================================================
;; A walker reads only the slots its vector's header makes live: cnt, shift and
;; tail-len name every one (the trie holds elements [0, toff), the tail the rest).
;; A slot past them holds whatever bytes its block last held — eve forgets memory,
;; it does not scrub it — so it is never read, never tested for NIL, never
;; followed. A NIL in a live leaf or tail slot is a nil element, not a pointer.

(defn- live-slots
  "How many slots of the trie node at shift sh, covering the elements from base,
   hold live entries of a trie of toff elements."
  [toff base sh]
  (let [n (- toff base)]
    (if (<= n 0)
      0
      (let [w (bit-shift-left 1 sh)]
        (min NODE_SIZE (quot (+ n (dec w)) w))))))

(defn- vec-shape
  "[cnt shift root tail tail-len toff]: the header at header-off, and the number of
   elements its trie holds."
  [sio header-off]
  (let [cnt (slab/read-i32 sio header-off SABVECROOT_CNT_OFFSET)]
    [cnt
     (slab/read-i32 sio header-off SABVECROOT_SHIFT_OFFSET)
     (slab/read-i32 sio header-off SABVECROOT_ROOT_OFFSET)
     (slab/read-i32 sio header-off SABVECROOT_TAIL_OFFSET)
     (slab/read-i32 sio header-off SABVECROOT_TAIL_LEN_OFFSET)
     (tail-offset-calc cnt)]))

(defn- collect-trie-live!
  "The trie node at shift sh covering the elements from base, its live children,
   and at the leaves their (non-nil) values, into result."
  [sio node-off sh base toff result]
  (vswap! result conj! node-off)
  (let [w (bit-shift-left 1 sh)]
    (dotimes [i (live-slots toff base sh)]
      (let [c (node-get sio node-off i)]
        (if (zero? sh)
          (when (not= c NIL_OFFSET) (vswap! result conj! c))
          (collect-trie-live! sio c (- sh SHIFT_STEP) (+ base (* i w)) toff result))))))

(defn live-offsets
  "Every block the vector at header-off owns, read from its header alone: the
   header, the live trie with its values, the tail with its live values."
  [sio header-off]
  (let [[_ shift root tail tail-len toff] (vec-shape sio header-off)
        result (volatile! (transient [header-off]))]
    (when (pos? toff)
      (collect-trie-live! sio root shift 0 toff result))
    (when (not= tail NIL_OFFSET)
      (vswap! result conj! tail)
      (dotimes [i tail-len]
        (let [v (node-get sio tail i)]
          (when (not= v NIL_OFFSET) (vswap! result conj! v)))))
    (persistent! @result)))

(defn- last-leaf
  "The trie's leaf holding element toff-1, or NIL_OFFSET for an empty trie: where
   conj puts a full tail (the old tail node itself, push-tail)."
  [sio root shift toff]
  (if (pos? toff)
    (loop [node root sh shift]
      (if (zero? sh)
        node
        (recur (node-get sio node (bit-and (unsigned-bit-shift-right (dec toff) sh) MASK))
               (- sh SHIFT_STEP))))
    NIL_OFFSET))

;;=============================================================================
;; EveVector deftype — unified via eve/deftype macro.
;; Fields are read from slab header via ISlabIO on each method call.
;;=============================================================================

(defn- root-digest-verdict
  "Merkle Phase 4 (§2b) digest seam for equiv — ported from canonical eve
   (commit 366e9b2); wired to
   the Phase 3 eve cache (eve.digest, layout B-prime) 2026-10-25. Answers
   :equal or :unequal for two EveVector roots whose content digests
   decide, and :walk for everything else (a root of any other type — an
   EveList included: vec/list equality is the walk's, though both are
   sequential — a root whose content has no digest, any doubt). equiv
   calls it only after its own partition gates (sequential? + count)
   have passed, and falls back to the structural walk on :walk."
  [a b]
  (if (eve-vector? b)
    (dg/digest-verdict (d/-eve-sio a) (d/-root-header-off a) a
                       (d/-eve-sio b) (d/-root-header-off b) b)
    :walk))

(eve/deftype EveVector
  [^:int32 cnt ^:int32 shift ^:int32 root ^:int32 tail ^:int32 tail-len]

  clojure.lang.Sequential

  clojure.lang.Counted
  (count [_] (int cnt))

  clojure.lang.Seqable
  (seq [_]
    (when (pos? cnt)
      (letfn [(vec-seq [i]
                (when (< i cnt)
                  (lazy-seq
                   (cons (nth-impl sio__ cnt shift root tail i)
                         (vec-seq (inc i))))))]
        (vec-seq 0))))

  clojure.lang.IMeta
  (meta [_] nil)

  clojure.lang.IObj
  (withMeta [this m] this)

  clojure.lang.ILookup
  (valAt [_ k]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      nil))
  (valAt [_ k not-found]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      not-found))

  clojure.lang.Indexed
  (nth [_ n]
    (if (or (neg? n) (>= n cnt))
      (enc/throw-index-out-of-bounds (str "Index out of bounds: " n))
      (nth-impl sio__ cnt shift root tail n)))
  (nth [this n not-found]
    (if (or (neg? n) (>= n cnt))
      not-found
      (.nth this n)))

  clojure.lang.IPersistentCollection
  (cons [_ val]
    (vec-conj-impl sio__ cnt shift root tail tail-len val))
  (empty [_]
    (let [new-tail (alloc-node! sio__)]
      (make-vec-impl sio__ 0 SHIFT_STEP NIL_OFFSET new-tail 0)))
  (equiv [this other]
    (cond
      (not (sequential? other)) false
      (not= cnt (count other)) false
      :else
      (let [verdict (root-digest-verdict this other)]
        (cond
          (= verdict :equal) true
          (= verdict :unequal) false
          :else
          (loop [i 0]
            (if (>= i cnt)
              true
              (if (= (nth-impl sio__ cnt shift root tail i) (nth other i))
                (recur (inc i))
                false)))))))

  clojure.lang.IHashEq
  (hasheq [this] (dg/root-hash sio__ offset__ (fn [] (p/hash-ordered this))))

  clojure.lang.IPersistentStack
  (peek [_]
    (when (pos? cnt)
      (nth-impl sio__ cnt shift root tail (dec cnt))))
  (pop [_]
    (if (zero? cnt)
      (p/throw! "Can't pop empty vector")
      (vec-pop-impl sio__ cnt shift root tail tail-len)))

  clojure.lang.IPersistentVector
  (assocN [this n val]
    (cond
      (== n cnt) (.cons this val)
      (or (neg? n) (> n cnt))
      (enc/throw-index-out-of-bounds (str "Index " n " out of bounds [0," cnt "]"))
      :else
      (vec-assoc-n-impl sio__ cnt shift root tail tail-len n val)))
  ;; (the JVM's alone: ClojureScript's IVector has neither)
  (length [_] (int cnt))
  (entryAt [this i] (when (.containsKey this i) (clojure.lang.MapEntry/create i (.nth this i))))

  ;; rseq: the index-walking reverse seq every host's vectors answer
  clojure.lang.Reversible
  (rseq [this] (when (pos? cnt) (ers/->RSeq this (dec cnt) nil)))

  clojure.lang.IEditableCollection
  (asTransient [this] (eve.vec/->transient-vec this))

  clojure.lang.Associative
  (assoc [this k v]
    (if (integer? k)
      (.assocN this k v)
      (p/throw! "Vector's key for assoc must be a number.")))
  (containsKey [_ k]
    (and (integer? k) (>= k 0) (< k cnt)))

  clojure.lang.IFn
  (invoke [this k] (nth-impl sio__ cnt shift root tail k))
  (invoke [this k not-found]
    (if (and (integer? k) (>= k 0) (< k cnt))
      (nth-impl sio__ cnt shift root tail k)
      not-found))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (loop [i 0 acc init]
      (if (or (>= i cnt) (reduced? acc))
        (unreduced acc)
        (recur (inc i) (f acc (nth-impl sio__ cnt shift root tail i))))))

  clojure.lang.IReduce
  (reduce [_ f]
    (case (int cnt)
      0 (f)
      1 (nth-impl sio__ cnt shift root tail 0)
      (loop [i 1 acc (nth-impl sio__ cnt shift root tail 0)]
        (if (>= i cnt)
          acc
          (let [val (f acc (nth-impl sio__ cnt shift root tail i))]
            (if (reduced? val) @val (recur (inc i) val)))))))

  d/IDirectSerialize
  (d/-direct-serialize [this] offset__)

  d/ISabStorable
  (d/-sab-tag [_] :eve-vec)
  (d/-sab-encode [this _] (codec/encode-eve-pointer this))
  (d/-sab-dispose [_ _] nil)

  d/IsEve
  (d/-eve? [_] true)

  d/IEveRoot
  (d/-root-header-off [this] offset__)

  d/IEveSio
  (d/-eve-sio [_] sio__)

  

  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  java.util.List
  (size [_] (int cnt))
  (isEmpty [_] (zero? cnt))
  (get [this ^int i] (.nth this i))
  (indexOf [this o]
    (int (loop [i 0]
      (cond
        (== i cnt) -1
        (clojure.lang.Util/equiv (.nth this i) o) i
        :else (recur (inc i))))))
  (lastIndexOf [this o]
    (int (loop [i (dec cnt)]
      (cond
        (< i 0) -1
        (clojure.lang.Util/equiv (.nth this (int i)) o) i
        :else (recur (dec i))))))
  (toArray [this] (clojure.lang.RT/seqToArray (.seq this)))
  (^boolean contains [this o] (not= -1 (.indexOf this o)))
  (^boolean containsAll [this ^java.util.Collection c]
    (boolean (every? #(.contains this %) c)))
  (^java.util.List subList [this ^int from ^int to]
    (java.util.Collections/unmodifiableList
      (java.util.ArrayList. ^java.util.Collection (subvec (vec this) from to))))
  (^java.util.ListIterator listIterator [this] (.listIterator (java.util.ArrayList. this) 0))
  (^java.util.ListIterator listIterator [this ^int i] (.listIterator (java.util.ArrayList. this) i))
  ;; Unsupported mutators (immutable)
  (set [_ _ _] (enc/throw-unsupported))
  (add [_ _ _] (enc/throw-unsupported))
  (^boolean add [_ _] (enc/throw-unsupported))
  (^boolean remove [_ _] (enc/throw-unsupported))
  (addAll [_ _] (enc/throw-unsupported))
  (addAll [_ _ _] (enc/throw-unsupported))
  (removeAll [_ _] (enc/throw-unsupported))
  (retainAll [_ _] (enc/throw-unsupported))
  (clear [_] (enc/throw-unsupported))
  (^Object remove [_ ^int _] (enc/throw-unsupported))

  java.lang.Object
  ;; (dropped on ClojureScript: its IEquiv and IHash are the type's)
  (toString [this] (pr-str this))
  (equals [this other]
    (cond
      (identical? this other) true
      (not (instance? clojure.lang.IPersistentVector other)) false
      :else
      (let [ov ^clojure.lang.IPersistentVector other]
        (and (== cnt (.count ov))
             (loop [i 0]
               (if (== i cnt) true
                 (if (clojure.lang.Util/equals (.nth this i) (.nth ov i))
                   (recur (inc i)) false)))))))
  (hashCode [this] (p/hash-ordered this)))

;;=============================================================================
;; Transients (the JVM's and ClojureCLR's): a persistent EveVector behind a
;; transient's interface — each op makes the next persistent vector, and frees
;; what it superseded in the one before
;;=============================================================================

(declare collect-retire-diff-offsets vec-from-header)

(defn- edit
  "(f v), v read through sio (a transient's allocation log), and the blocks of
   v it superseded that sio made freed: the walk between two versions only the
   transient holds — never a block of the vector it began from
   (2026-10-21 ET)."
  [sio v f]
  (let [v' (f v)]
    ;; the walk names the old header even when an op answers the same vector
    (when-not (== (d/-root-header-off v) (d/-root-header-off v'))
      (alloc/free-owned! sio (collect-retire-diff-offsets v v')))
    v'))

(defn- conj!-owned
  "conj! of x onto v (read through sio, the transient's log): into the tail in place when the transient made the
   header and the tail and the tail has room — a block it made is no other version's, so the write is its own
   (Clojure's transient vector;: a path-copying conj! and its walk made a build 5x slower
   than a persistent one) — else the path copy and the walk."
  [sio v x]
  (let [hdr (d/-root-header-off v)
        [cnt _ _ tail tl] (read-vec-header sio hdr)]
    (if (and (< tl NODE_SIZE) (not= tail NIL_OFFSET) (alloc/owns? sio hdr) (alloc/owns? sio tail))
      (let [val-off (make-value-block! sio (serialize-element-bytes sio x))]
        (node-set! sio tail tl val-off)
        (slab/write-i32! sio hdr SABVECROOT_CNT_OFFSET (inc cnt))
        (slab/write-i32! sio hdr SABVECROOT_TAIL_LEN_OFFSET (inc tl))
        (vec-from-header sio hdr))
      (edit sio v #(ecore/conj % x)))))

(do
     (eve/host-deftype TransientEveVector [^:volatile-mutable v sio]
       clojure.lang.ITransientVector
       (assocN [this i x] (set! v (edit sio v #(ecore/assoc % i x))) this)
       (pop [this] (set! v (edit sio v ecore/pop)) this)
       clojure.lang.ITransientAssociative
       (assoc [this k x] (set! v (edit sio v #(ecore/assoc % k x))) this)
       clojure.lang.ITransientCollection
       (conj [this x] (set! v (conj!-owned sio v x)) this)
       (persistent [_] (alloc/stop-logging! sio) v)
       clojure.lang.Counted
       (count [_] (ecore/count v))
       clojure.lang.Indexed
       (nth [_ i] (ecore/nth v i))
       (nth [_ i nf] (ecore/nth v i nf))
       clojure.lang.ILookup
       (valAt [_ k] (ecore/get v k))
       (valAt [_ k nf] (ecore/get v k nf)))

     (defn ->transient-vec [v]
       ;; the vector read through a scope of its own over its domain: what its ops allocate is logged there
       (let [sio (alloc/scope-sio (d/-eve-sio v))]
         (->TransientEveVector (vec-from-header sio (d/-root-header-off v)) sio))))

;;=============================================================================
;; Constructor (after deftype so CLJ can resolve EveVector class)
;;=============================================================================

(do
(defn- make-vec-impl
  "Internal constructor: allocate header, create EveVector."
  [sio cnt shift root tail tail-len]
  (let [hdr (write-vec-header! sio cnt shift root tail tail-len)]
    (EveVector. sio hdr)))

(defn vec-from-header
  "Reconstruct an EveVector from an existing header offset."
  [sio header-off]
  (EveVector. sio header-off))

(defn empty-vec
  "Create an empty Eve vector.
   0-arity: uses platform default sio (fresh heap domain on JVM).
   1-arity: explicit sio."
  ([] (empty-vec (default-sio)))
  ([sio]
   (let [tail (alloc-node! sio)]
     (make-vec-impl sio 0 SHIFT_STEP NIL_OFFSET tail 0))))

(defn eve-vec
  "Create an Eve vector from a collection."
  ([coll] (eve-vec (default-sio) coll))
  ([sio coll]
   (reduce ecore/conj (empty-vec sio) coll)))
)

;;=============================================================================
;; Disposal
;;=============================================================================

(defn dispose!
  "Dispose an EveVector, freeing every block its header says it owns (live-offsets).
   Call this when the vector is no longer needed to reclaim slab memory.

   WARNING: After disposal, the vector must not be used."
  [eve-vec]
  (let [sio (d/-eve-sio eve-vec)]
    (doseq [off (live-offsets sio (d/-root-header-off eve-vec))]
      (slab/free! sio off))))

(defn retire-replaced-trie-path!
  "After an atom swap that replaced old-root with new-root, retire the old
   path nodes that are no longer referenced by the new trie.

   Walks both tries following the index bits for the modified index. At each
   level where old-node != new-node, the old node is freed.

   Only retires trie internal/leaf nodes — shared subtrees and value blocks
   are untouched."
  ([sio old-root new-root shift-val idx]
   (retire-replaced-trie-path! sio old-root new-root shift-val idx :free))
  ([sio old-root new-root shift-val idx mode]
   (when (and (not= old-root NIL_OFFSET) (not= old-root new-root))
     (loop [old-off old-root
            new-off new-root
            sh shift-val]
       (when (and (not= old-off NIL_OFFSET) (not= old-off new-off))
         ;; Free this old trie node
         (slab/free! sio old-off)
         ;; Continue down the trie path
         (when (and (pos? sh) (>= idx 0))
           (let [child-idx (bit-and (unsigned-bit-shift-right idx sh) MASK)
                 old-child (node-get sio old-off child-idx)
                 ;; idx must be live in both tries (the caller's contract); a NIL
                 ;; new node has no slots
                 new-child (if (== new-off NIL_OFFSET) NIL_OFFSET (node-get sio new-off child-idx))]
             (recur old-child new-child (- sh SHIFT_STEP)))))))))

;;=============================================================================
;; Offset Collection (for epoch-based retirement in mmap atoms)
;;=============================================================================

(defn- trie-diff!
  "The old trie's blocks under old-off that the new trie does not hold in the same
   place — both tries of one shift, each node's live slots read from its own trie's
   element count (toff0, toff1). A shared subtree is pruned whole; a node in keep
   (one conj or pop moved between trie and tail) is the new vector's and is skipped
   with everything under it."
  [sio old-off new-off sh base toff0 toff1 keep result]
  (when (and (not= old-off new-off) (not (contains? keep old-off)))
    (vswap! result conj! old-off)
    (let [w  (bit-shift-left 1 sh)
          k1 (if (== new-off NIL_OFFSET) 0 (live-slots toff1 base sh))]
      (dotimes [i (live-slots toff0 base sh)]
        (let [oc (node-get sio old-off i)
              nc (if (< i k1) (node-get sio new-off i) NIL_OFFSET)]
          (if (zero? sh)
            (when (and (not= oc NIL_OFFSET) (not= oc nc))
              (vswap! result conj! oc))
            (trie-diff! sio oc nc (- sh SHIFT_STEP) (+ base (* i w)) toff0 toff1 keep result)))))))

(defn collect-retire-diff-offsets
  "The blocks old-vec owns that new-value does not: what a swap that replaced one
   with the other retires. Every read comes from the two headers' live ranges.
   Same depth: a positional diff that prunes shared subtrees. A changed depth: the
   old vector's live blocks less the new one's. Nodes conj and pop move between
   trie and tail by reference — the old tail as the new trie's last leaf, the old
   last leaf as the new tail — and stay the new vector's."
  [old-vec new-value]
  (let [sio        (d/-eve-sio old-vec)
        header-off (d/-root-header-off old-vec)
        [_ shift0 root0 tail0 tl0 toff0] (vec-shape sio header-off)
        result     (volatile! (transient []))]
    (if (eve-vector? new-value)
      (let [new-hdr (d/-root-header-off new-value)
            [_ shift1 root1 tail1 tl1 toff1] (vec-shape sio new-hdr)
            leaf1   (last-leaf sio root1 shift1 toff1)
            keep    #{tail1 leaf1}]
        (when (pos? toff0)
          (if (== shift0 shift1)
            (trie-diff! sio root0 (if (pos? toff1) root1 NIL_OFFSET) shift0 0 toff0 toff1 keep result)
            (let [live1 (set (live-offsets sio new-hdr))
                  old   (volatile! (transient []))]
              (collect-trie-live! sio root0 shift0 0 toff0 old)
              (doseq [off (persistent! @old)]
                (when-not (contains? live1 off) (vswap! result conj! off))))))
        ;; the old tail, unless it became the new trie's last leaf; its live values
        ;; unless the new tail holds them in place
        (when (and (not= tail0 NIL_OFFSET) (not= tail0 tail1) (not= tail0 leaf1))
          (vswap! result conj! tail0)
          (dotimes [i tl0]
            (let [ov (node-get sio tail0 i)
                  nv (if (and (not= tail1 NIL_OFFSET) (< i tl1)) (node-get sio tail1 i) NIL_OFFSET)]
              (when (and (not= ov NIL_OFFSET) (not= ov nv))
                (vswap! result conj! ov))))))
      ;; no longer a vector: everything the old one owns
      (doseq [off (rest (live-offsets sio header-off))]
        (vswap! result conj! off)))
    ;; Always include header
    (when (and header-off (not= header-off NIL_OFFSET))
      (vswap! result conj! header-off))
    (persistent! @result)))

;;=============================================================================
;; ISabRetirable
;;=============================================================================

(extend-type EveVector
  d/ISabRetirable
  (-sab-retire-diff! [this new-value _slab-env mode]
    (let [sio       (.-sio__ this)
          hdr       (.-offset__ this)
          old-root  (slab/read-i32 sio hdr SABVECROOT_ROOT_OFFSET)
          old-shift (slab/read-i32 sio hdr SABVECROOT_SHIFT_OFFSET)]
      (if (eve-vector? new-value)
        ;; Both are EveVector — diff trie paths
        (let [new-hdr      (d/-root-header-off new-value)
              new-root-off (slab/read-i32 sio new-hdr SABVECROOT_ROOT_OFFSET)]
          (retire-replaced-trie-path! sio old-root new-root-off old-shift -1 mode))
        ;; Different type — dispose entire old trie
        (dispose! this)))))

;;=============================================================================
;; Backward-compat aliases
;;=============================================================================

(do
     (def empty-sab-vec empty-vec)
     (def sab-vec eve-vec))

(defn eve-vector?
  "True for a slab-backed EveVector. On babashka a vector read from eve memory is
   the host's own vector (eve.types/reader-of), so one read there is not one."
  [x]
  (instance? EveVector x))

;;=============================================================================
;; Registration
;;=============================================================================

;; Collection writer: a host collection written straight into the slab, for a
;; codec that writes one directly (eve.codec)
(defn- -write-vec-coll!
  "Serialize a Clojure vector/seq to slab as an Eve persistent vector trie.
   Returns header offset."
  [sio _serialize-val coll]
  (let [elems (vec coll)
        cnt   (count elems)]
    (if (zero? cnt)
      (let [empty-tail (alloc-node! sio)]
        (write-vec-header! sio 0 SHIFT_STEP NIL_OFFSET empty-tail 0))
      (let [val-offs (mapv (fn [elem]
                             (make-value-block! sio (codec/encode-value sio elem)))
                           elems)
            toff     (tail-offset-calc cnt)
            tail-len (- cnt toff)
            tail-off (let [t-off (alloc-node! sio)]
                       (dorun (map-indexed
                                (fn [i v-off]
                                  (slab/write-i32! sio t-off (* i 4) v-off))
                                (subvec val-offs toff cnt)))
                       t-off)
            trie-val-offs (subvec val-offs 0 toff)
            [root sft]
            (if (empty? trie-val-offs)
              [NIL_OFFSET SHIFT_STEP]
              (let [leaf-nodes
                    (mapv (fn [chunk]
                            (let [node-off (alloc-node! sio)]
                              (dorun (map-indexed
                                       (fn [i v-off]
                                         (slab/write-i32! sio node-off (* i 4) v-off))
                                       chunk))
                              node-off))
                          (partition-all NODE_SIZE trie-val-offs))]
                (loop [nodes leaf-nodes sh SHIFT_STEP]
                  (if (<= (count nodes) NODE_SIZE)
                    (let [root-off (alloc-node! sio)]
                      (dorun (map-indexed
                               (fn [i child-off]
                                 (slab/write-i32! sio root-off (* i 4) child-off))
                               nodes))
                      [root-off sh])
                    (let [parent-nodes
                          (mapv (fn [chunk]
                                  (let [node-off (alloc-node! sio)]
                                    (dorun (map-indexed
                                             (fn [i child-off]
                                               (slab/write-i32! sio node-off (* i 4) child-off))
                                             chunk))
                                    node-off))
                                (partition-all NODE_SIZE nodes))]
                      (recur parent-nodes (+ sh SHIFT_STEP)))))))]
        (write-vec-header! sio cnt sft root tail-off tail-len)))))

;; Type constructor + disposer + builder + collection writer registrations
(defn- vec->host-value
  "A vector in eve memory as the host's own vector: what a host whose types
   cannot stand in for its collections reads (eve.types/reader-of)."
  [sio header-off]
  (let [[cnt shift root tail _tail-len] (read-vec-header sio header-off)]
    (loop [i 0 acc (transient [])]
      (if (>= i cnt)
        (persistent! acc)
        (recur (inc i) (conj! acc (nth-impl sio cnt shift root tail i)))))))

(eve/register-eve-type!
    {:fast-tag     ser/FAST_TAG_SAB_VEC
     :type-id      EveVector-type-id
     :from-header  vec-from-header
     :host-value   vec->host-value
     :dispose      dispose!
     :builder-pred vector?
     :builder-ctor eve-vec
     :coll-writer  {:tag :vec :fn -write-vec-coll!}
     :type         EveVector
     :print        {:open "[" :close "]"}})

;; Backward-compat aliases
(do
     (defn jvm-write-vec!
       "Serialize a Clojure vector to slab. Returns header offset.
        Backward-compat alias for the registered :vec writer."
       [sio serialize-val coll]
       (codec/write-collection! :vec sio coll))

     (defn jvm-sabvec-from-offset
       "Reconstruct an EveVector from a header offset.
        Backward-compat alias. coll-factory arg is ignored (registry-based)."
       ([sio header-off] (vec-from-header sio header-off))
       ([sio header-off _coll-factory] (vec-from-header sio header-off)))

     (def SabVecRoot EveVector))

;; No-op pool stub — pool system removed, kept for backward compat
(defn reset-pools! [] nil)
