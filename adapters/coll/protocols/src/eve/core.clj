(ns eve.core
  "EVE's collection vocabulary (adapters/README.md, coll) on babashka, whose
   deftype takes protocols, Object and IPersistentMap only: a type cannot
   implement the host's collection interfaces, so the vocabulary dispatches
   through the protocols below (ClojureScript's names and arities), which
   the type's own body implements — eve.types maps ICounted/-count (or
   clojure.lang.Counted/count) onto this namespace's ICounted/-count.
   Anything else (Clojure data, nil) falls through to clojure.core, and
   (e/seq x) of an EVE value is a Clojure seq clojure.core can walk."
  (:refer-clojure :exclude [count nth get seq first next rest conj into empty
                            contains? disj subvec meta with-meta reduce
                            sequential? counted? hash-ordered-coll hash-unordered-coll
                            assoc dissoc pop peek rseq reduce-kv transient persistent!
                            conj! assoc! pop! array
                            object-array unchecked-int unchecked-add-int
                            unchecked-multiply-int unsigned-bit-shift-right dissoc! disj!
                            long])
  (:require [eve.host :refer [object-array unchecked-int unchecked-add-int
                              unchecked-multiply-int unsigned-bit-shift-right dissoc! disj!
                              long]]
            [eve.encoding :as enc]))

(declare seq-hash-ordered seq-hash-unordered)

;; The protocols a babashka EVE type implements — ClojureScript's names
;; and arities (eve.deftype-proto.proto-map is the translation).
(defprotocol ICounted (-count [coll]))
(defprotocol IIndexed (-nth [coll n] [coll n not-found]))
(defprotocol ILookup (-lookup [o k] [o k not-found]))
(defprotocol ISeqable (-seq [o]))
(defprotocol ICollection (-conj [coll o]))
(defprotocol IEmptyableCollection (-empty [coll]))
(defprotocol IEquiv (-equiv [o other]))
(defprotocol IHash (-hash [o]))
(defprotocol IMeta (-meta [o]))
(defprotocol IWithMeta (-with-meta [o meta]))
(defprotocol IFn (-invoke [f] [f a] [f a b] [f a b c]))
(defprotocol IReduce (-reduce [coll f] [coll f start]))
(defprotocol IKVReduce (-kv-reduce [coll f init]))
(defprotocol IAssociative (-contains-key? [coll k]) (-assoc [coll k v]))
(defprotocol IMap (-dissoc [coll k]))
(defprotocol ISet (-disjoin [coll v]))
(defprotocol IStack (-peek [coll]) (-pop [coll]))
(defprotocol IVector (-assoc-n [coll n val]))
(defprotocol ISeq (-first [coll]) (-rest [coll]))
(defprotocol INext (-next [coll]))
(defprotocol ISequential)
(defprotocol ASeq)
(defprotocol IReversible (-rseq [coll]))
(defprotocol IComparable (-compare [x y]))
(defprotocol IEditableCollection (-as-transient [coll]))
(defprotocol ITransientCollection (-conj! [tcoll val]) (-persistent! [tcoll]))
(defprotocol ITransientAssociative (-assoc! [tcoll key val]))
(defprotocol ITransientMap (-dissoc! [tcoll key]))
(defprotocol ITransientVector (-assoc-n! [tcoll n val]) (-pop! [tcoll]))
(defprotocol ITransientSet (-disjoin! [tcoll v]))
(defprotocol IChunkedSeq (-chunked-first [coll]) (-chunked-rest [coll]))
(defprotocol IChunkedNext (-chunked-next [coll]))
(defprotocol IMapEntry (-key [coll]) (-val [coll]))
(defprotocol IDeref (-deref [o]))
(defprotocol IPrintWithWriter (-pr-writer [o writer opts]))

;; seq before any arm that calls it, and no (declare seq): on ValueScript a namespace's declare of a name clojure.core
;; also has breaks clojure.core's seq passed as a value in every other namespace of the program (EM4's seed,
;; vs/test-xfail/eve-migration/probes/declare_value_ref.vs). It needs only the protocols above.
(defn- host-seq
  "An EVE seq (a type with ISeq) as a lazy Clojure seq, element by
   element; anything else through clojure.core/seq."
  [s]
  (lazy-seq
   (cond (nil? s) nil
         ;; satisfies? is true of every Object here (ISeq has an Object
         ;; fallback), so a host seq is told apart by clojure.core/seq?
         ;; and passed through, never wrapped again.
         (clojure.core/seq? s) s
         (satisfies? ISeq s) (clojure.core/cons (-first s) (host-seq (-next s)))
         :else (clojure.core/seq s))))

(defn seq
  "x's seq. For an EVE value it is a Clojure seq (its own seq walked
   lazily), so clojure.core's sequence functions take it."
  [o]
  (let [s (-seq o)]
    (if (or (nil? s) (clojure.core/seq? s)) s (host-seq s))))
;; Clojure data and nil: clojure.core's answer. An EVE value without the
;; protocol reaches the Object arm too; where clojure.core cannot see it
;; (count, nth) the arm goes through the value's seq.
(extend-protocol ICounted
  nil (-count [_] 0)
  Object (-count [c] (try (clojure.core/count c)
                          (catch UnsupportedOperationException _
                            (clojure.core/count (seq c))))))
(extend-protocol IIndexed
  nil (-nth ([_ _] nil) ([_ _ nf] nf))
  Object (-nth ([c n] (try (clojure.core/nth c n)
                           (catch UnsupportedOperationException _
                             (clojure.core/nth (seq c) n))))
           ([c n nf] (try (clojure.core/nth c n nf)
                          (catch UnsupportedOperationException _
                            (clojure.core/nth (seq c) n nf))))))
(extend-protocol ILookup
  nil (-lookup ([_ _] nil) ([_ _ nf] nf))
  Object (-lookup ([o k] (clojure.core/get o k)) ([o k nf] (clojure.core/get o k nf))))
(extend-protocol ISeqable
  nil (-seq [_] nil)
  Object (-seq [o] (clojure.core/seq o)))
(extend-protocol ICollection
  nil (-conj [_ o] (list o))
  Object (-conj [c o] (clojure.core/conj c o)))
(extend-protocol IEmptyableCollection
  nil (-empty [_] nil)
  Object (-empty [c] (clojure.core/empty c)))
(extend-protocol IEquiv
  nil (-equiv [_ other] (nil? other))
  Object (-equiv [o other] (= o other)))
;; A host collection hashes its elements through -hash, in Clojure's shapes (a map the unordered hash of its entries,
;; each entry the ordered hash of [k v]; a set unordered; a vector, list or seq ordered), so an EVE value inside one
;; answers its own: the host's hash does not see it (ValueScript before the flip hashes the type's representation —
;; an eve map holding a map hashed wrong, EM4's in-program differential). A record's hash is not its map's: the host's.
(extend-protocol IHash
  nil (-hash [_] 0)
  Object (-hash [o] (cond (record? o) (hash o)
                          (or (map? o) (set? o)) (seq-hash-unordered o)
                          (clojure.core/sequential? o) (seq-hash-ordered o)
                          :else (hash o))))
(extend-protocol IMeta
  nil (-meta [_] nil)
  Object (-meta [o] (clojure.core/meta o)))
(extend-protocol IWithMeta
  Object (-with-meta [o m] (clojure.core/with-meta o m)))
(extend-protocol ISet
  nil (-disjoin [_ _] nil)
  Object (-disjoin [s v] (clojure.core/disj s v)))
(extend-protocol IFn
  Object
  (-invoke ([f] (f)) ([f a] (f a)) ([f a b] (f a b)) ([f a b c] (f a b c))))
(extend-protocol IReduce
  nil
  (-reduce ([_ f] (f)) ([_ _ start] start))
  Object
  ;; through seq: a type with ISeqable and no IReduce reduces too
  (-reduce ([coll f] (clojure.core/reduce f (seq coll)))
    ([coll f start] (clojure.core/reduce f start (seq coll)))))
(extend-protocol IKVReduce
  nil (-kv-reduce [_ _ init] init)
  Object (-kv-reduce [c f init] (clojure.core/reduce-kv f init c)))
(extend-protocol IAssociative
  nil (-contains-key? [_ _] false) (-assoc [_ k v] {k v})
  Object (-contains-key? [c k] (clojure.core/contains? c k))
  (-assoc [c k v] (clojure.core/assoc c k v)))
(extend-protocol IMap
  nil (-dissoc [_ _] nil)
  Object (-dissoc [c k] (clojure.core/dissoc c k)))
(extend-protocol IStack
  nil (-peek [_] nil) (-pop [_] nil)
  Object (-peek [c] (clojure.core/peek c)) (-pop [c] (clojure.core/pop c)))
(extend-protocol IVector
  Object (-assoc-n [c n v] (clojure.core/assoc c n v)))
(extend-protocol ISeq
  nil (-first [_] nil) (-rest [_] ())
  Object (-first [c] (clojure.core/first c)) (-rest [c] (clojure.core/rest c)))
(extend-protocol INext
  nil (-next [_] nil)
  Object (-next [c] (clojure.core/next c)))
(extend-protocol IReversible
  Object (-rseq [c] (clojure.core/rseq c)))
(extend-protocol IComparable
  Object (-compare [x y] (compare x y)))
(extend-protocol IEditableCollection
  Object (-as-transient [c] (clojure.core/transient c)))
(extend-protocol ITransientCollection
  Object (-conj! [t v] (clojure.core/conj! t v)) (-persistent! [t] (clojure.core/persistent! t)))
(extend-protocol ITransientAssociative
  Object (-assoc! [t k v] (clojure.core/assoc! t k v)))
(extend-protocol ITransientMap
  Object (-dissoc! [t k] (dissoc! t k)))
(extend-protocol ITransientVector
  Object (-assoc-n! [t n v] (clojure.core/assoc! t n v)) (-pop! [t] (clojure.core/pop! t)))
(extend-protocol ITransientSet
  Object (-disjoin! [t v] (disj! t v)))
(extend-protocol IChunkedSeq
  Object (-chunked-first [s] (chunk-first s)) (-chunked-rest [s] (chunk-rest s)))
(extend-protocol IChunkedNext
  Object (-chunked-next [s] (chunk-next s)))
(extend-protocol IMapEntry
  Object (-key [e] (key e)) (-val [e] (val e)))
(extend-protocol IDeref
  Object (-deref [o] (deref o)))
(extend-protocol IPrintWithWriter
  nil (-pr-writer [o w _] (print-method o w))
  Object (-pr-writer [o w _] (print-method o w)))

(defn first [coll] (clojure.core/first (seq coll)))
(defn next [coll] (clojure.core/next (seq coll)))
(defn rest [coll] (clojure.core/rest (seq coll)))
(defn count [coll] (-count coll))
(defn nth ([coll n] (-nth coll n)) ([coll n not-found] (-nth coll n not-found)))
(defn get ([o k] (-lookup o k)) ([o k not-found] (-lookup o k not-found)))
(defn conj ([] []) ([coll] coll) ([coll x] (-conj coll x))
  ([coll x & xs] (clojure.core/reduce -conj (-conj coll x) xs)))
(defn empty [coll] (-empty coll))
(defn contains? [coll k]
  (not (identical? ::absent (-lookup coll k ::absent))))
(defn disj [s v] (-disjoin s v))
(defn meta [o] (-meta o))
(defn with-meta [o m] (-with-meta o m))
(defn invoke
  ([f] (-invoke f)) ([f a] (-invoke f a)) ([f a b] (-invoke f a b))
  ([f a b c] (-invoke f a b c)))
(defn reduce ([f coll] (-reduce coll f)) ([f init coll] (-reduce coll f init)))
(defn- host-coll?
  "A host collection (or nil): coll? — IPersistentCollection's instances, and on glojure not a type (a record is one
   there; its completion's coll? says no for a type)."
  [x]
  (or (nil? x) (coll? x)))
(defn- transient-of
  "An EVE type's transient, or nil when it has none (the Object fallback
   hands it to clojure.core/transient, which refuses it)."
  [to]
  (try (-as-transient to) (catch Exception _ nil)))
(defn into
  "clojure.core's into for a host collection; for an EVE type,
   ClojureScript's: through a transient when it has one, keeping to's
   meta, else -conj by -conj."
  ([] []) ([to] to)
  ([to from]
   (if (host-coll? to)
     (clojure.core/into to from)
     (if-some [t (transient-of to)]
       (let [r (-persistent! (reduce -conj! t from)) m (meta to)]
         (if (some? m) (with-meta r m) r))
       (reduce -conj to from))))
  ([to xform from]
   (if (host-coll? to)
     (clojure.core/into to xform from)
     (if-some [t (transient-of to)]
       (let [r (-persistent! (transduce xform (completing -conj!) t (seq from))) m (meta to)]
         (if (some? m) (with-meta r m) r))
       (transduce xform (completing -conj) to (seq from))))))
(defn assoc [coll k v] (-assoc coll k v))
(defn dissoc [coll k] (-dissoc coll k))
(defn pop [coll] (-pop coll))
(defn peek [coll] (-peek coll))
(defn rseq [coll] (seq (-rseq coll)))
(defn reduce-kv [f init coll] (-kv-reduce coll f init))
(defn transient [coll] (-as-transient coll))
(defn persistent! [tcoll] (-persistent! tcoll))
(defn conj! [tcoll x] (-conj! tcoll x))
(defn assoc! [tcoll k v] (-assoc! tcoll k v))
(defn pop! [tcoll] (-pop! tcoll))
(defn equiv
  "x and y equal: clojure.core's = cannot see an EVE value on bb (it asks
   the Clojure collection), so each side's -equiv answers."
  [x y]
  (or (-equiv x y) (-equiv y x)))
(defn sequential? [x] (or (clojure.core/sequential? x) (satisfies? ISequential x)))
(defn counted? [x] (or (clojure.core/counted? x) (satisfies? ICounted x)))
(defn hash-ordered-coll [coll] (seq-hash-ordered coll))
(defn hash-unordered-coll [coll] (seq-hash-unordered coll))
(defn array [& xs] (object-array xs))

;; printing: bb's print-method and a java.io.Writer
(defn -write [w s] (enc/write! w s))
(defn pr-writer [x w opts] (-pr-writer x w opts))
(defn pr-str* [x] (pr-str x))

;; a sub-range view of an EVE vector (clojure.core/subvec needs an
;; IPersistentVector, which a bb type is not)
(deftype Subvec [v start end meta]
  ICounted (-count [_] (- end start))
  IIndexed
  (-nth [_ n] (if (and (<= 0 n) (< n (- end start)))
                (-nth v (+ start n))
                (throw (IndexOutOfBoundsException.))))
  (-nth [_ n nf] (if (and (<= 0 n) (< n (- end start))) (-nth v (+ start n)) nf))
  ISeqable (-seq [_] (when (< start end)
                       (clojure.core/map #(-nth v %) (range start end))))
  ISequential
  IEquiv (-equiv [this other] (= (seq this) (seq other)))
  IMeta (-meta [_] meta)
  IWithMeta (-with-meta [_ m] (Subvec. v start end m)))
(defn subvec
  ([v start] (subvec v start (count v)))
  ([v start end]
   (if (vector? v)
     (clojure.core/subvec v start end)
     (do (when (or (neg? start) (< end start) (> end (count v)))
           (throw (IndexOutOfBoundsException.)))
         (Subvec. v start end nil)))))

(defmacro caching-hash [coll hash-fn hash-key]
  `(let [h# ~hash-key]
     (if-not (nil? h#)
       h#
       (let [h# (~hash-fn ~coll)]
         (set! ~hash-key h#)
         h#))))

(defn- mix-collection-hash*
  "Murmur3's mixCollHash in int32 arithmetic (bb has no
   mix-collection-hash)."
  [hash-basis n]
  (let [rotl (fn [x r] (let [u (bit-and x 0xffffffff)]
                         (unchecked-int (bit-or (bit-shift-left u r)
                                                (unsigned-bit-shift-right u (- 32 r))))))
        fold (fn [h r] (unchecked-int (bit-xor h (unsigned-bit-shift-right (bit-and h 0xffffffff) r))))
        k1 (-> (unchecked-int hash-basis)
               (unchecked-multiply-int (unchecked-int 0xcc9e2d51))
               (rotl 15)
               (unchecked-multiply-int (unchecked-int 0x1b873593)))
        h1 (-> (rotl k1 13)
               (unchecked-multiply-int (int 5))
               (unchecked-add-int (unchecked-int 0xe6546b64)))
        h1 (unchecked-int (bit-xor h1 n))]
    (-> (fold h1 16)
        (unchecked-multiply-int (unchecked-int 0x85ebca6b))
        (fold 13)
        (unchecked-multiply-int (unchecked-int 0xc2b2ae35))
        (fold 16))))

(defn seq-hash-ordered
  "hash-ordered-coll by walking coll's seq (Murmur3's ordered hash): the
   host's needs an Iterable, which a sci type is not; each element hashed
   through -hash, so an eve value nested in it answers its own."
  [coll]
  (loop [n 0 h (int 1) s (seq coll)]
    (if s
      (recur (inc n)
             (unchecked-add-int (unchecked-multiply-int (int 31) h)
                                (unchecked-int (-hash (first s))))
             (next s))
      (mix-collection-hash* h n))))

(defn seq-hash-unordered
  "hash-unordered-coll by walking coll's seq (Murmur3's unordered hash: the
   elements' hashes summed, then mixed with the count)."
  [coll]
  (loop [n 0 h (int 0) s (seq coll)]
    (if s
      (recur (inc n) (unchecked-add-int h (unchecked-int (-hash (first s)))) (next s))
      (mix-collection-hash* h n))))

(defn array-chunk
  "A chunk of host array arr's n values: a vector (what bb's seqs read)."
  [arr n]
  (vec arr))
