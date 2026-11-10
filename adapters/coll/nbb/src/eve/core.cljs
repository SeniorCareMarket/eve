(ns eve.core
  "EVE's collection vocabulary (adapters/README.md, coll) on nbb
   (ClojureScript on SCI): cljs.core's, as on ClojureScript, and the
   internals of cljs.core nbb lacks — the chunked seq protocols, ASeq,
   pr-writer, pr-str*, caching-hash — with ClojureScript's names and arities,
   so a type written for ClojureScript runs here."
  (:refer-clojure :exclude [count nth get seq first next rest conj into empty
                            contains? disj subvec meta with-meta reduce
                            sequential? counted? hash-ordered-coll hash-unordered-coll
                            assoc dissoc pop peek rseq reduce-kv transient persistent!
                            conj! assoc! pop! -write pr-str*
                            -count -nth -conj -with-meta -assoc-n -conj! -assoc-n! array]))

(def count cljs.core/count)
(def nth cljs.core/nth)
(def get cljs.core/get)
(def seq cljs.core/seq)
(def first cljs.core/first)
(def next cljs.core/next)
(def rest cljs.core/rest)
(def conj cljs.core/conj)
(def into cljs.core/into)
(def empty cljs.core/empty)
(def contains? cljs.core/contains?)
(def disj cljs.core/disj)
(def subvec cljs.core/subvec)
(def meta cljs.core/meta)
(def with-meta cljs.core/with-meta)
(defn invoke
  ([f] (f)) ([f a] (f a)) ([f a b] (f a b)) ([f a b c] (f a b c)))
(def reduce cljs.core/reduce)
(def assoc cljs.core/assoc)
(def dissoc cljs.core/dissoc)
(def pop cljs.core/pop)
(def peek cljs.core/peek)
(def rseq cljs.core/rseq)
(def reduce-kv cljs.core/reduce-kv)
(def transient cljs.core/transient)
(def persistent! cljs.core/persistent!)
(def conj! cljs.core/conj!)
(def assoc! cljs.core/assoc!)
(def pop! cljs.core/pop!)
(defn equiv [x y] (cljs.core/= x y))
(def sequential? cljs.core/sequential?)
(def counted? cljs.core/counted?)
(def hash-ordered-coll cljs.core/hash-ordered-coll)
(def hash-unordered-coll cljs.core/hash-unordered-coll)
(def -write cljs.core/-write)

(defprotocol IChunkedSeq (-chunked-first [coll]) (-chunked-rest [coll]))
(defprotocol IChunkedNext (-chunked-next [coll]))
(defprotocol ASeq)
;; F-NBB-DEREFTIMEOUT: cljs.core's IDerefWithTimeout, which nbb's core
;; lacks; a vanilla library's :cljs reify names it (DataScript's
;; transact future). Defined here as eve.core's, like the chunked
;; protocols above; eve.types interns it into the core namespace and
;; remaps deftype bodies to it.
(defprotocol IDerefWithTimeout (-deref-with-timeout [ref timeout-ms timeout-val]))
(defn pr-writer [x w _opts] (-write w (pr-str x)))
(defn pr-str* [x] (pr-str x))
(defmacro caching-hash [coll hash-fn hash-key]
  `(let [h# ~hash-key]
     (if-not (nil? h#)
       h#
       (let [h# (~hash-fn ~coll)]
         (set! ~hash-key h#)
         h#))))

;; ClojureScript's protocol fns under eve.core's names, which a namespace
;; written for every host refers from here
(def -count cljs.core/-count)
(def -nth cljs.core/-nth)
(def -conj cljs.core/-conj)
(def -with-meta cljs.core/-with-meta)
(def -assoc-n cljs.core/-assoc-n)
(def -conj! cljs.core/-conj!)
(def -assoc-n! cljs.core/-assoc-n!)
(def array cljs.core/array)

(defn array-chunk
  "A chunk of host array arr's n values: a vector (nbb has no chunked seqs)."
  [arr n]
  (vec arr))
