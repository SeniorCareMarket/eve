(ns eve.core
  "EVE's collection vocabulary (adapters/README.md, coll) on ClojureScript:
   its types implement cljs.core's protocols (eve.types), so the vocabulary
   is cljs.core's, (count x) and (e/count x) are the same call, and a type
   written with ClojureScript's protocol names calls cljs.core's protocol
   fns directly."
  (:refer-clojure :exclude [count nth get seq first next rest conj into empty
                            contains? disj subvec meta with-meta reduce
                            sequential? counted? hash-ordered-coll hash-unordered-coll
                            assoc dissoc pop peek rseq reduce-kv transient persistent!
                            conj! assoc! pop! -write pr-str*
                            -count -nth -conj -with-meta -assoc-n -conj! -assoc-n! -chunked-rest -chunked-next array
                            caching-hash])
  ;; caching-hash: the macro of eve.core's Clojure side (coll/jvm)
  (:require-macros [eve.core]))

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

;; printing: the writer is IWriter (cljs.core's pr-writer is private)
(defn pr-writer [x w _opts] (cljs.core/-write w (cljs.core/pr-str x)))
(def pr-str* cljs.core/pr-str*)

;; ClojureScript's protocol fns under eve.core's names, which a namespace
;; written for every host refers from here
(def -count cljs.core/-count)
(def -nth cljs.core/-nth)
(def -conj cljs.core/-conj)
(def -with-meta cljs.core/-with-meta)
(def -assoc-n cljs.core/-assoc-n)
(def -conj! cljs.core/-conj!)
(def -assoc-n! cljs.core/-assoc-n!)
(def -chunked-rest cljs.core/-chunked-rest)
(def -chunked-next cljs.core/-chunked-next)
(def array cljs.core/array)

(defn array-chunk
  "A chunk of host array arr's n values (a chunked seq's chunk-first)."
  [arr n]
  (cljs.core/array-chunk arr 0 n))
