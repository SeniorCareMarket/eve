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
;; ClojureScript has no IRef protocol (its validators are an Atom
;; field behind plain fns), and nbb's core is no exception (measured:
;; cljs.core/IRef does not resolve on this seat), so eve.core's IRef
;; is the home for the validator half of clojure.lang.IRef, as with
;; the chunked protocols above: eve.atom's types carry their IRef
;; sections onto it through the proto map's split (the watch
;; methods land on IWatchable).
(defprotocol IRef (-set-validator! [ref f]) (-get-validator [ref])
  (-get-watches [ref]))
;; ClojureScript's value protocols — IEquiv, IHash, IComparable,
;; IPrintWithWriter — exist inside nbb's compiled core (its =, hash,
;; compare and pr dispatch on them) but are not exposed as vars, so a
;; host deftype naming one in its spec (eve.numeric's EveBigInt /
;; EveRatio / EveBigDec) dies at analysis: "Protocol not found:
;; IEquiv". As with IDerefWithTimeout above, eve.core's are the home:
;; eve.types interns them into the core namespace sci keeps, so the
;; bare names resolve for deftype expansion. On this seat the methods
;; a type registers here are not what ClojureScript's own dispatch
;; calls — eve.numeric's equality, hash, compare and print route
;; through its own fns (n/equiv, the jhash family, tagged forms), the
;; disposition its extend-type note already records for this seat;
;; these protocols are the registration surface, with ClojureScript's
;; method names and arities.
(defprotocol IEquiv (-equiv [x y]))
(defprotocol IHash (-hash [x]))
(defprotocol IComparable (-compare [x other]))
(defprotocol IPrintWithWriter (-pr-writer [x writer opts]))
;; The interns live here, not in eve.types: eve.numeric — an early
;; load in every chain, well before eve.types — names these four in
;; its host deftype specs, and the names must resolve by then.
;; eve.types carries the same interns (resolve-guarded) as the
;; backstop for any chain that reaches it first.
;; The guards name the core namespace qualified: a bare (resolve
;; 'IEquiv) here would find eve.core's own protocol, defined above,
;; and skip the intern it guards.
(when-not (resolve 'clojure.core/IEquiv)
  (intern 'clojure.core 'IEquiv IEquiv)
  (intern 'clojure.core '-equiv -equiv))
(when-not (resolve 'clojure.core/IHash)
  (intern 'clojure.core 'IHash IHash)
  (intern 'clojure.core '-hash -hash))
(when-not (resolve 'clojure.core/IComparable)
  (intern 'clojure.core 'IComparable IComparable)
  (intern 'clojure.core '-compare -compare))
(when-not (resolve 'clojure.core/IPrintWithWriter)
  (intern 'clojure.core 'IPrintWithWriter IPrintWithWriter)
  (intern 'clojure.core '-pr-writer -pr-writer))

;; This block lived in eve.types until the ds load chain convicted
;; its ordering: eve.type-readers — which requires eve.encoding, and
;; eve.encoding requires eve.core — registers its tag parsers at
;; load, before eve.types is reached on that chain. eve.core is the
;; earliest seat-completion home every Eve chain passes through.
;; F-NBB-EDNREADER: nbb's bundled cljs.reader exports only read-string
;; — no register-tag-parser!, no *data-readers* — so a vanilla
;; ClojureScript library registering its EDN tag readers at load
;; (DataScript's core, for datascript/Datom and datascript/DB) fails
;; to analyse on this seat: "Unable to resolve symbol:
;; edn/register-tag-parser!". The seat's completion: cljs.reader is
;; required here, as Eve loads (the goog pattern below), and the
;; missing registry is interned into it — an atom-backed
;; *data-readers*, a register-tag-parser! that records into it, and a
;; read-string that wraps nbb's own, merging the registered tags into
;; its :readers (nbb's read-string honours a :readers opts map —
;; probed, fix/probes/probe3.cljs), so a registered tag genuinely
;; dispatches on read. Guarded by resolve: a future nbb supplying
;; the registry itself is never shadowed.
(require 'cljs.reader)

(def ^:private nbb-data-readers (atom {}))

(when-not (resolve 'cljs.reader/register-tag-parser!)
  (intern 'cljs.reader '*data-readers* nbb-data-readers)
  (intern 'cljs.reader 'register-tag-parser!
          (fn [tag f] (swap! nbb-data-readers assoc tag f) nil))
  (let [orig-read-string @(resolve 'cljs.reader/read-string)]
    (intern 'cljs.reader 'read-string
            (fn
              ([s] (orig-read-string {:readers @nbb-data-readers} s))
              ([opts s] (orig-read-string
                         (update opts :readers (fn [r] (merge @nbb-data-readers r)))
                         s))))))
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
;; written for every host refers from here. nbb's core exposes no
;; protocol-fn vars (measured on 1.3.204: of cljs.core's dash vars only
;; -deref, -reset!, -swap! and -write resolve — there is no cljs.core/-count
;; to take). -count, -nth, -conj and -assoc-n are the protocol fns of
;; the collection protocols defined at the end of this file (ICounted,
;; IIndexed, ICollection, IVector — on ClojureScript these vars ARE
;; those protocol fns); they cannot also be carried as bridging defns
;; here, because SCI builds a multi-arity protocol method as a
;; multifn and defprotocol over an existing plain -nth dies
;; ("IMultiFn.-add-method ... type function", measured). The three
;; names no protocol below claims stay bridging defns over the public
;; fns, which here ARE the dispatch.
(defn -with-meta [coll m] (cljs.core/with-meta coll m))
(defn -conj! [coll x] (cljs.core/conj! coll x))
(defn -assoc-n! [coll n v] (cljs.core/assoc! coll n v))
(def array cljs.core/array)

(defn array-chunk
  "A chunk of host array arr's n values: a vector (nbb has no chunked seqs)."
  [arr n]
  (vec arr))

;; The rest of ClojureScript's collection protocols, for the same
;; reason as the value four above: nbb's compiled core dispatches on
;; them but does not expose them as vars, so a host deftype naming
;; one in its spec (eve.flat.chunk's flat handles name ICounted,
;; ILookup, IAssociative, ISeqable, ISequential, IList, IVector,
;; IMap, ISet, IStack) dies at analysis: "Protocol not found:
;; ICounted". eve.core's are the home, with ClojureScript's method
;; names and arities; interned into the core namespace at load, as
;; the value four are. A type registering a method here answers
;; Eve's own dispatch through these protocol fns; ClojureScript's
;; compiled dispatch (count, nth, get on a flat handle) does not see
;; SCI-registered methods — the seat disposition eve.numeric's note
;; records for the value protocols, measured the same way.
(defprotocol ICollection (-conj [coll v]))
(defprotocol IEmptyableCollection (-empty [coll]))
(defprotocol ICounted (-count [coll]))
(defprotocol IIndexed (-nth [coll i] [coll i not-found]))
(defprotocol ILookup (-lookup [coll k] [coll k not-found]))
(defprotocol IAssociative (-assoc [coll k v]) (-contains-key? [coll k]))
(defprotocol ISeqable (-seq [coll]))
(defprotocol ISequential)
(defprotocol IList)
(defprotocol IVector (-assoc-n [coll i v]))
(defprotocol IMap (-dissoc [coll k]))
(defprotocol ISet (-disjoin [coll v]))
(defprotocol IStack (-peek [coll]) (-pop [coll]))
(defprotocol ICloneable (-clone [v]))
(defprotocol IKVReduce (-kv-reduce [coll f init]))
(defprotocol IReduce (-reduce [coll f] [coll f start]))
(defprotocol IReversible (-rseq [coll]))
;; the chunk protocols are likewise absent from this seat's core
;; (measured: IChunk/IChunkedSeq/IChunkedNext and their method vars
;; do not resolve); Eve's types adapter already reroutes the seq
;; pair to eve.core for interpreted types — same treatment here, so
;; host deftypes (eve.db.ds's bset Chunk/Iter) can name them. Core
;; seq fns dispatch chunking through the compiled protocols, so an
;; implementer of these is traversed unchunked: correct, unchunked.
(defprotocol IChunk (-drop-first [coll]))
(defprotocol IChunkedSeq (-chunked-first [coll]) (-chunked-rest [coll]))
(defprotocol IChunkedNext (-chunked-next [coll]))

;; seq-iter is likewise absent from this seat's core (measured: does
;; not resolve). cljs.core's returns its SeqIter: the hasNext/next
;; Iterator over the seq that IIterable -iterator consumers
;; (iter-reduce, TransformerIterator) call .hasNext/.next on. A plain
;; JS object carrying the two fns over a seq cell is that contract.
(defn seq-iter [coll]
  (let [s (atom (seq coll))]
    (js-obj "hasNext" (fn [] (boolean @s))
            "next" (fn [] (if @s
                            (let [v (first @s)] (swap! s next) v)
                            (throw (js/Error. "No such element: seq-iter")))))))
(when-not (resolve 'clojure.core/seq-iter)
  (intern 'clojure.core 'seq-iter seq-iter))
(doseq [[p ms] '{ICollection [-conj], IEmptyableCollection [-empty]
                 ICounted [-count], IIndexed [-nth]
                 ILookup [-lookup], IAssociative [-assoc -contains-key?]
                 ISeqable [-seq], ISequential [], IList []
                 IVector [-assoc-n], IMap [-dissoc], ISet [-disjoin]
                 IStack [-peek -pop], ICloneable [-clone]
                 IKVReduce [-kv-reduce], IReduce [-reduce]
                 IReversible [-rseq]
                 IChunk [-drop-first], IChunkedSeq [-chunked-first -chunked-rest]
                 IChunkedNext [-chunked-next]}]
  (when-not (resolve (symbol "clojure.core" (name p)))
    (intern 'clojure.core p @(resolve (symbol "eve.core" (name p))))
    (doseq [m ms]
      (intern 'clojure.core m @(resolve (symbol "eve.core" (name m)))))))

;; instance? on this seat: SCI discriminates on the type object, and
;; an eve type's flat sister is a distinct SCI type from its main
;; type, so (instance? Leaf flat-leaf) answers false although the
;; sister IS a Leaf — both carry the main type's key
;; (eve.deftype-proto.data/type-key-of). The js types adapter
;; registers each eve type object under its key on this seat
;; (register-type-object!); when the plain check fails and the class
;; is a registered eve type, answer by type key. Every other class
;; keeps cljs.core/instance?'s answer.
(def ^:private instance?-fns
  (delay [(resolve 'eve.deftype-proto.data/type-object-key)
          (resolve 'eve.deftype-proto.data/type-key-of)]))

(def ^:private orig-instance? instance?)

(defn eve-instance?
  [c x]
  (or (orig-instance? c x)
      (boolean
        (when (some? c)
          (let [[obj-key key-of] @instance?-fns]
            (when (and obj-key key-of)
              (when-let [k (obj-key c)]
                (= k (key-of x)))))))))

(intern 'clojure.core 'instance? eve-instance?)

;; compare on this seat: cljs.core/compare dispatches the compiled
;; IComparable, which interpreted types cannot join — Eve's own
;; flat/view types implement this namespace's IComparable, so
;; (compare flat-map plain-map) throws "Cannot compare" although
;; the values are equal and hash equal. Bridge: when either side
;; satisfies eve.core/IComparable, dispatch -compare on that side
;; (negated when it is the right side); everything else keeps
;; cljs.core/compare's answer.
(def ^:private orig-compare compare)

(defn eve-compare
  [x y]
  (cond (identical? x y) 0
        (satisfies? IComparable x) (-compare x y)
        (satisfies? IComparable y) (- (-compare y x))
        :else (orig-compare x y)))

(intern 'clojure.core 'compare eve-compare)
