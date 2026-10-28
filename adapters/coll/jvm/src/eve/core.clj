(ns eve.core
  "EVE's collection vocabulary (adapters/README.md, coll) on a host whose
   types implement its own collection interfaces and whose core is Clojure's
   with clojure.lang.RT — the JVM, ClojureCLR, jolt: clojure.core's calls,
   inlined through RT as clojure.core inlines them, so (count x) and
   (e/count x) are the same call, and -nth is nth. ClojureScript's protocol
   fns (-count, -nth, -conj!, ...), which a type written with ClojureScript's
   names calls, are clojure.core's too: the type implements the JVM's
   interfaces (eve.types)."
  (:refer-clojure :exclude [count nth get seq first next rest conj into empty
                            contains? disj subvec meta with-meta reduce
                            sequential? counted? hash-ordered-coll hash-unordered-coll
                            assoc dissoc pop peek rseq reduce-kv transient persistent!
                            conj! assoc! pop!
                            object-array unchecked-int unchecked-add-int
                            unchecked-multiply-int dissoc! disj! long])
  (:require [eve.host :refer [object-array unchecked-int unchecked-add-int
                              unchecked-multiply-int dissoc! disj! long]]
            [eve.encoding :as enc]))

(declare seq-hash-ordered)

(defn count
  {:inline (fn [x] `(. clojure.lang.RT (count ~x)))}
  [coll] (clojure.core/count coll))
(defn nth
  {:inline (fn [c i & nf] `(. clojure.lang.RT (nth ~c ~i ~@nf)))
   :inline-arities #{2 3}}
  ([coll n] (clojure.core/nth coll n))
  ([coll n not-found] (clojure.core/nth coll n not-found)))
(defn get
  {:inline (fn [m k & nf] `(. clojure.lang.RT (get ~m ~k ~@nf)))
   :inline-arities #{2 3}}
  ([o k] (clojure.core/get o k))
  ([o k not-found] (clojure.core/get o k not-found)))
;; seq, into and the chunk fns are Clojure's, written out: a type's seq is
;; its own (a chunked seq, an IReduceInit), an editable collection fills
;; through its transient, a type's chunked seq answers its own chunks. Where
;; clojure.core's are Clojure's (the JVM, ClojureCLR) these are the same
;; calls; jolt's seq is a native op that wraps a type's seq in jolt's own
;; cells and its into folds a type with persistent conj (measured).
(defn seq [coll]
  (if (and (instance? clojure.lang.IType coll) (instance? clojure.lang.Seqable coll))
    (.seq ^clojure.lang.Seqable coll)
    (clojure.core/seq coll)))
(def first clojure.core/first)
(def next clojure.core/next)
(def rest clojure.core/rest)
(def conj clojure.core/conj)
(defn into
  ([] []) ([to] to)
  ([to from]
   (if (instance? clojure.lang.IEditableCollection to)
     (let [r (clojure.core/persistent!
              (clojure.core/reduce clojure.core/conj! (clojure.core/transient to) from))]
       (if-let [m (clojure.core/meta to)] (clojure.core/with-meta r m) r))
     (clojure.core/into to from)))
  ([to xform from]
   (if (instance? clojure.lang.IEditableCollection to)
     (let [r (clojure.core/persistent!
              (clojure.core/transduce xform clojure.core/conj! (clojure.core/transient to) from))]
       (if-let [m (clojure.core/meta to)] (clojure.core/with-meta r m) r))
     (clojure.core/into to xform from))))
(def empty clojure.core/empty)
(def contains? clojure.core/contains?)
(def disj clojure.core/disj)
(def subvec clojure.core/subvec)
(def meta clojure.core/meta)
(def with-meta clojure.core/with-meta)
(defn invoke
  ([f] (f)) ([f a] (f a)) ([f a b] (f a b)) ([f a b c] (f a b c)))
(def reduce clojure.core/reduce)
(def assoc clojure.core/assoc)
(def dissoc clojure.core/dissoc)
(def pop clojure.core/pop)
(def peek clojure.core/peek)
(def rseq clojure.core/rseq)
(def reduce-kv clojure.core/reduce-kv)
(def transient clojure.core/transient)
(def persistent! clojure.core/persistent!)
(def conj! clojure.core/conj!)
(def assoc! clojure.core/assoc!)
(def pop! clojure.core/pop!)
(defn equiv [x y] (clojure.core/= x y))
(def sequential? clojure.core/sequential?)
(def counted? clojure.core/counted?)
(defn hash-ordered-coll [coll] (seq-hash-ordered coll))
(defn hash-unordered-coll [coll] (clojure.core/hash-unordered-coll coll))
(defn array [& xs] (object-array xs))

;; ClojureScript's protocol fns, answered by the host's interfaces (an EVE
;; type written with ClojureScript's names implements those)
(defn -count
  {:inline (fn [x] `(. clojure.lang.RT (count ~x)))}
  [coll] (clojure.core/count coll))
(defn -nth
  {:inline (fn [c i & nf] `(. clojure.lang.RT (nth ~c ~i ~@nf)))
   :inline-arities #{2 3}}
  ([coll n] (clojure.core/nth coll n))
  ([coll n not-found] (clojure.core/nth coll n not-found)))
(defn -lookup
  {:inline (fn [m k & nf] `(. clojure.lang.RT (get ~m ~k ~@nf)))
   :inline-arities #{2 3}}
  ([o k] (clojure.core/get o k))
  ([o k not-found] (clojure.core/get o k not-found)))
(defn -seq [o] (clojure.core/seq o))
(defn -conj [coll o] (clojure.core/conj coll o))
(defn -empty [coll] (clojure.core/empty coll))
(defn -equiv [o other] (clojure.core/= o other))
(defn -hash [o] (clojure.core/hash o))
(defn -meta [o] (clojure.core/meta o))
(defn -with-meta [o m] (clojure.core/with-meta o m))
(defn -invoke
  ([f] (f)) ([f a] (f a)) ([f a b] (f a b)) ([f a b c] (f a b c))
  ([f a b c d & more] (apply f a b c d more)))
(defn -reduce ([coll f] (clojure.core/reduce f coll))
  ([coll f start] (clojure.core/reduce f start coll)))
(defn -kv-reduce [coll f init] (clojure.core/reduce-kv f init coll))
(defn -contains-key? [coll k] (clojure.core/contains? coll k))
(defn -assoc [coll k v] (clojure.core/assoc coll k v))
(defn -dissoc [coll k] (dissoc coll k))
(defn -disjoin [coll v] (clojure.core/disj coll v))
(defn -peek [coll] (clojure.core/peek coll))
(defn -pop [coll] (clojure.core/pop coll))
(defn -assoc-n
  "The vector's own assocN (a type's assoc calls this: assoc would call the
   type's assoc back)."
  [coll n v]
  (.assocN ^clojure.lang.IPersistentVector coll (int n) v))
(defn -first [coll] (clojure.core/first coll))
(defn -rest [coll] (clojure.core/rest coll))
(defn -next [coll] (clojure.core/next coll))
(defn -rseq [coll] (clojure.core/rseq coll))
(defn -compare [x y] (compare x y))
(defn -as-transient [coll] (clojure.core/transient coll))
(defn -conj! [tcoll v] (clojure.core/conj! tcoll v))
(defn -persistent! [tcoll] (clojure.core/persistent! tcoll))
(defn -assoc! [tcoll k v] (clojure.core/assoc! tcoll k v))
(defn -dissoc! [tcoll k] (dissoc! tcoll k))
(defn -assoc-n!
  "The transient vector's own assocN (as -assoc-n)."
  [tcoll n v]
  (.assocN ^clojure.lang.ITransientVector tcoll (int n) v))
(defn -pop! [tcoll] (clojure.core/pop! tcoll))
(defn -disjoin! [tcoll v] (disj! tcoll v))
(defn -chunked-first [coll]
  (if (instance? clojure.lang.IType coll) (.chunkedFirst ^clojure.lang.IChunkedSeq coll) (chunk-first coll)))
(defn -chunked-rest [coll]
  (if (instance? clojure.lang.IType coll) (.chunkedMore ^clojure.lang.IChunkedSeq coll) (chunk-rest coll)))
(defn -chunked-next [coll]
  (if (instance? clojure.lang.IType coll) (.chunkedNext ^clojure.lang.IChunkedSeq coll) (chunk-next coll)))
(defn -key [e] (key e))
(defn -val [e] (val e))
(defn -deref [o] (deref o))

;; printing: print-method and the host's writer (eve.encoding's)
(defn -write [w s] (enc/write! w s))
(defn -pr-writer [o w _opts] (print-method o w))
(defn pr-writer [x w _opts] (print-method x w))
(defn pr-str* [x] (pr-str x))

(defmacro caching-hash [coll hash-fn hash-key]
  `(let [h# ~hash-key]
     (if-not (nil? h#)
       h#
       (let [h# (~hash-fn ~coll)]
         (set! ~hash-key h#)
         h#))))

(defn seq-hash-ordered
  "hash-ordered-coll by walking coll's seq (Murmur3's ordered hash): the
   host's needs an Iterable, which a type written with ClojureScript's names
   is not."
  [coll]
  (loop [n 0 h (int 1) s (seq coll)]
    (if s
      (recur (inc n)
             (unchecked-add-int (unchecked-multiply-int (int 31) h)
                                (unchecked-int (hash (first s))))
             (next s))
      (mix-collection-hash h n))))

;; a chunk of host array arr's n values (a chunked seq's chunk-first): the
;; host's own chunk — the JVM's and ClojureCLR's an ArrayChunk; jolt seals a
;; chunk buffer into a vector, the chunk its core reads
(defn array-chunk [arr n]
  (let [b (clojure.core/chunk-buffer n)]
    (dotimes [i n] (clojure.core/chunk-append b (clojure.core/aget ^objects arr i)))
    (clojure.core/chunk b)))
