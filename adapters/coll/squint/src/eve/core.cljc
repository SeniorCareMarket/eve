(ns eve.core
  "eve.core's SCI face on squint: what squint's SCI sees when a .cljc
   consumer's macro graph loads eve.core. Squint takes a namespace's
   SCI vars from its .cljc only (the pair trap — eve.host's pair died
   of it), so the vocabulary a .cljc body can name at load time lives
   here: the caching-hash macro (coll/jvm's, which coll/cljs's eve.core
   pulls in by :require-macros; body as coll/nbb's: read the cached
   hash, on a miss compute and store it), the plain-fn aliases, and the
   protocols defined in this namespace.

   The line between this file and eve/core.cljs is measured on squint
   0.16.213 (Bucket F): SCI resolves its core's plain fns (count,
   hash-ordered-coll, -write, pr-str, array) but NO protocol vars
   (cljs.core/-count and kin) and NO protocol objects (cljs.core/
   IEquiv) — those aliases live only in the .cljs, the runtime face,
   which is also what squint compiles eve.core's .mjs from. The
   plain-fn aliases are therefore duplicated here verbatim. eve.platform
   is the measured consumer that forced this file's existence: its
   bodies call eve.core/hash-ordered-coll, and loads through it failed
   'Unable to resolve symbol: eve.core/hash-ordered-coll' (42 of the
   gate compile's round-3 errors).

   IEquiv, IHash, IPrintWithWriter, ILookup and IKVReduce below are
   defprotocol stubs for
   SCI only: a namespace squint SCI-evaluates as macro graph (eve.codec
   is the measured one — its EveChar deftype names all three) resolves
   the protocols a deftype names, and squint's SCI registry holds no
   core protocols ('Protocol not found: IEquiv', thrown from
   eve.deftype-proto.alloc's load of eve.codec, the core collection
   types' compiles). Such a namespace refers the names from eve.core;
   in SCI they are these stubs, at runtime squint core's own protocol
   objects (the .cljs defs) — the two faces never meet, macro-time
   registration on one side, = / hash / print dispatch on the other.
   eve.types/squint likewise routes eve.deftype IEquiv impls to
   eve.core/IEquiv."
  (:refer-clojure :exclude [count nth get seq first next rest conj into empty
                            contains? disj subvec meta with-meta reduce
                            sequential? counted? hash-ordered-coll hash-unordered-coll
                            assoc dissoc pop peek rseq reduce-kv transient persistent!
                            conj! assoc! pop! -write pr-str* array
                            unchecked-byte gensym read-string make-array array-seq lazy-cat seq?
                            bit-xor halt-when sequence]))



(defmacro caching-hash [coll hash-fn hash-key]
  `(let [h# ~hash-key]
     (if-not (nil? h#)
       h#
       (let [h# (~hash-fn ~coll)]
         (set! ~hash-key h#)
         h#))))

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
(def array cljs.core/array)

;; The core-gap set (see the .cljs face): fns cljs.core has and squint
;; core lacks, which Eve code names bare; the staged gate transform
;; routes those heads to eve.core's. SCI core has the plain fns, so
;; here they are aliases; the runtime meanings live in the .cljs.
;; lazy-cat is a macro: it lives only here, as every macro does.
(def unchecked-byte cljs.core/unchecked-byte)
(def gensym cljs.core/gensym)
(def read-string cljs.core/read-string)
;; SCI-side seq? is SCI's own (correct on SCI forms); the runtime
;; face in core.cljs carries the Clojure-semantics definition the
;; staged sources are routed to (squint core's seq? is true for any
;; JS-iterable — strings included).
(def seq? cljs.core/seq?)
(def bit-xor cljs.core/bit-xor)
(def halt-when cljs.core/halt-when)
(def sequence cljs.core/sequence)
(def make-array cljs.core/make-array)
(def array-seq cljs.core/array-seq)
(defmacro lazy-cat
  "clojure.core/lazy-cat: each coll is realized only when the seq
   reaches it."
  [& colls]
  (if (seq colls)
    `(lazy-seq (concat (lazy-seq ~(first colls)) (lazy-cat ~@(rest colls))))
    `(lazy-seq ())))

(defprotocol IChunkedSeq (-chunked-first [coll]) (-chunked-rest [coll]))
(defprotocol IChunkedNext (-chunked-next [coll]))
(defprotocol ASeq)
(defprotocol IEquiv (-equiv [x y]))
(defprotocol IHash (-hash [x]))
(defprotocol IPrintWithWriter (-pr-writer [x writer opts]))
(defprotocol ILookup (-lookup [o k] [o k not-found]))
(defprotocol IKVReduce (-kv-reduce [o f init]))

;; The rest of the protocol universe eve's type machinery names in a
;; deftype's impl spine (eve.deftype-proto's cljs translation), stubbed
;; for SCI on the same terms as the five above: squint's SCI registry
;; resolves none of them under the bare name, so eve.types/squint
;; routes every one to eve.core's, and these stubs are what SCI-side
;; deftype expansion registers against. Method names and arities are
;; eve.deftype-proto.proto-map's cljs side. At runtime the .cljs face
;; holds squint core's own protocol objects (or, for the ones squint
;; core does not export — IReduce, IFn, ISeq, ISequential, IReversible
;; — symbol objects in squint core's own shape).
(defprotocol IAssociative (-assoc [o k v]) (-contains-key? [o k]))
(defprotocol ICollection (-conj [o v]))
(defprotocol ICounted (-count [o]))
(defprotocol IDeref (-deref [o]))
(defprotocol IEditableCollection (-as-transient [o]))
(defprotocol IEmptyableCollection (-empty [o]))
(defprotocol IFn (-invoke [o a] [o a b]))
(defprotocol IIndexed (-nth [o n] [o n not-found]))
(defprotocol IMap (-dissoc [o k]))
(defprotocol IMeta (-meta [o]))
(defprotocol IReduce (-reduce [o f] [o f init]))
(defprotocol IReset (-reset! [o v]))
(defprotocol IReversible (-rseq [o]))
(defprotocol ISeq (-first [o]) (-rest [o]) (-more [o]) (-cons [o v]))
(defprotocol ISeqable (-seq [o]))
(defprotocol ISequential)
(defprotocol IList)
(defprotocol ISet (-disjoin [o v]))
(defprotocol IStack (-peek [o]) (-pop [o]))
(defprotocol ISwap (-swap! [o f] [o f a] [o f a b] [o f a b xs]))
(defprotocol ITransientAssociative (-assoc! [o k v]))
(defprotocol ITransientCollection (-conj! [o v]) (-persistent! [o]))
(defprotocol ITransientMap (-dissoc! [o k]))
(defprotocol ITransientSet (-disjoin! [o v]))
(defprotocol ITransientVector (-assoc-n! [o n v]) (-pop! [o]))
(defprotocol IVector (-assoc-n [o n v]))
(defprotocol IWatchable (-add-watch [o k f]) (-remove-watch [o k])
  (-notify-watches [o old new]))
(defprotocol IWithMeta (-with-meta [o m]))
(defprotocol INext (-next [o]))
(defprotocol IMapEntry (-key [o]) (-val [o]))
(defprotocol IComparable (-compare [o other]))
(defprotocol IPending (-realized? [x]))
;; ClojureScript has no IRef protocol (its validators are an Atom
;; field behind plain fns), so eve.core's IRef is the JS family's
;; home for the validator half of clojure.lang.IRef — the proto map
;; splits the JVM name here (watches go to IWatchable). The runtime
;; face defprotocols the same protocol for real.
(defprotocol IRef (-set-validator! [o f]) (-get-validator [o])
  (-get-watches [o]))
(defn pr-writer [x w _opts] (-write w (pr-str x)))
(defn pr-str* [x] (pr-str x))

(defn array-chunk
  "A chunk of host array arr's n values: a vector (squint has no chunked seqs)."
  [arr n]
  (vec arr))
