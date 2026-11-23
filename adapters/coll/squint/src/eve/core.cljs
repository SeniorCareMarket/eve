;; clj-kondo: redefined-var ignored for this whole file — eve.core is one namespace per host face: this file is squint's runtime face, eve/core.cljc beside it the SCI face, and the other adapters/coll/* files the other hosts' faces. The defs intentionally shadow the referred cljs.core names — providing the internals squint's core lacks is the file's job — so redefined-var is the layout, not a defect.
(ns ^{:clj-kondo/ignore [:redefined-var]} eve.core
  "EVE's collection vocabulary (adapters/README.md, coll) on squint
   (ClojureScript-syntax compiled to ES modules): cljs.core's, as on
   ClojureScript, and the internals of cljs.core squint's core lacks —
   the chunked seq protocols, ASeq, pr-writer, pr-str*, caching-hash —
   with ClojureScript's names and arities, so a type written for
   ClojureScript runs here. Pattern: adapters/coll/nbb, whose host core
   lacks the same set (measured for squint in adapters/types/squint:
   squint's core has no chunked seqs).

   This .cljs is the RUNTIME face; eve/core.cljc beside it is the SCI
   face (the vars and protocols a .cljc consumer's macro graph resolves
   when squint loads this namespace in SCI — squint takes a namespace's
   SCI vars from its .cljc only). The split is by a measured line
   (0.16.213): squint's SCI holds its core's plain fns but NONE of its
   protocol vars or protocol objects, so the protocol-fn aliases below
   (-count, -nth, ...) exist only here, and the plain-fn aliases are
   duplicated in the .cljc so SCI loads see them. IEquiv is the same
   story one level up: the var below aliases squint core's own
   protocol object (what squint's = dispatches through), while the
   .cljc carries a defprotocol of the same name purely so SCI-side
   deftype expansion can resolve eve.core/IEquiv — eve.types/squint
   routes IEquiv impls to that name."
  (:refer-clojure :exclude [count nth get seq first next rest conj into empty
                            contains? disj subvec meta with-meta reduce
                            sequential? counted? hash-ordered-coll hash-unordered-coll
                            assoc dissoc pop peek rseq reduce-kv transient persistent!
                            conj! assoc! pop! -write pr-str*
                            -count -nth -conj -with-meta -assoc-n -conj! -assoc-n! array
                            unchecked-byte gensym make-array array-seq read-string seq?
                            bit-xor halt-when sequence])
  (:require ["squint-cljs/src/squint/edn.js" :as edn]))

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
(defn rseq
  "cljs.core/rseq: squint core's rseq handles only its own vectors
   and sorted collections and throws on Eve's sorted types and
   vectors, which are IReversible through eve.core's protocol
   object. Dispatch through the protocol when the value carries a
   -rseq impl; squint's own values keep squint's rseq."
  [x]
  (if (and (some? x) (some? (aget x IReversible__rseq)))
    ((aget x IReversible__rseq) x)
    (cljs.core/rseq x)))
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
;; squint core has no IPending (no promise machinery of its own), so
;; eve.core's is a real protocol here, not an alias: eve.thread's
;; Future/Promise implement it, and realized? dispatch on this seat
;; goes through this protocol's -realized?.
(defprotocol IPending (-realized? [x]))
;; squint core has no IRef either, and neither does ClojureScript
;; itself (its validators are an Atom field behind the plain
;; set-validator!/get-validator fns — no protocol object exists to
;; alias), so eve.core's is a real protocol here too: eve.atom's
;; types carry their clojure.lang.IRef sections onto it through the
;; proto map's split (the watch methods land on IWatchable, above),
;; and -set-validator!/-get-validator dispatch through this
;; protocol's fns. The semantics are eve.atom's trunk watch core,
;; as on every host.
(defprotocol IRef (-set-validator! [x f]) (-get-validator [x])
             (-get-watches [x]))
(defn pr-writer [x w _opts] (-write w (pr-str x)))
(defn pr-str* [x] (pr-str x))

;; ClojureScript's protocol fns under eve.core's names, which a namespace
;; written for every host refers from here — and which eve.types/squint
;; routes a type's own protocol-fn calls to (a body's (.assoc this k v)
;; becomes (eve.core/-assoc this k v)), because squint's SCI core holds
;; no protocol fns for its load path to resolve. Runtime only: the SCI
;; face carries the same names as its defprotocol stubs' fns.
;; Where squint core exports the dispatch fn, the def aliases it;
;; where it does not (its generic fns dispatch inline — nth checks the
;; IIndexed prop itself), the defn wraps the generic fn of the same
;; meaning, which dispatches through the same protocol props.
(def -count cljs.core/-count)
(def -conj cljs.core/-conj)
(def -conj! cljs.core/-conj!)
(def -assoc cljs.core/-assoc)
(def -assoc! cljs.core/-assoc!)
(def -dissoc cljs.core/-dissoc)
(def -dissoc! cljs.core/-dissoc!)
(def -disjoin cljs.core/-disjoin)
(def -disjoin! cljs.core/-disjoin!)
(def -empty cljs.core/-empty)
(def -equiv cljs.core/-equiv)
(def -lookup cljs.core/-lookup)
(def -kv-reduce cljs.core/-kv-reduce)
(def -deref cljs.core/-deref)
(def -swap! cljs.core/-swap!)
(def -reset! cljs.core/-reset!)
(def -as-transient cljs.core/-as-transient)
(def -persistent! cljs.core/-persistent!)
(def -seq cljs.core/-seq)
(def -contains-key? cljs.core/-contains-key?)
(def -add-watch cljs.core/-add-watch)
(def -remove-watch cljs.core/-remove-watch)
(def -notify-watches cljs.core/-notify-watches)
(def -pop! pop!)
(defn -nth ([o n] (nth o n)) ([o n not-found] (nth o n not-found)))
(defn -meta [o] (meta o))
(defn -with-meta [o m] (with-meta o m))
(defn -hash [o] (cljs.core/hash o))
(defn -peek [o] (peek o))
(defn -pop [o] (pop o))
(defn -rseq [o] (rseq o))
(defn -reduce ([o f] (reduce f o)) ([o f init] (reduce f init o)))
(defn -next [o] (next o))
(defn -key [o] (cljs.core/key o))
(defn -val [o] (cljs.core/val o))
(defn -compare [o other] (cljs.core/compare o other))
(defn -first [o] (first o))
(defn -rest [o] (rest o))
(defn -more [o] (let [r (rest o)] (if (nil? r) () r)))
(defn -cons [o v] (cons v o))
(defn -assoc-n [o n v] (assoc o n v))
(defn -assoc-n! [o n v] (assoc! o n v))
(def -invoke invoke)
(def -pr-writer pr-writer)

;; Core fns cljs.core has and squint core lacks, which Eve code names
;; bare (hamt-util's unchecked-byte is the measured one: squint compiles
;; the unknown name to a free variable and the module dies at load).
;; The staged gate transform routes such bare heads to eve.core's
;; (bb.edn test:squint); these are the runtime meanings. The SCI face
;; (.cljc) aliases SCI core's own, which has all but the two JS-shaped
;; ones — those carry small defns there too.
(defn unchecked-byte
  "cljs.core/unchecked-byte: the low 8 bits of v as a signed byte."
  [v]
  (let [b (bit-and v 255)]
    (if (>= b 128) (- b 256) b)))

(def ^:private gensym-counter (atom 0))
(defn gensym
  "cljs.core/gensym: a fresh symbol, G__n by default."
  ([] (gensym "G__"))
  ([prefix] (symbol (str prefix (swap! gensym-counter inc)))))

(defn make-array
  "cljs.core/make-array, the one-dimension shape Eve calls: a JS array
   of length n, nil-filled (a raw JS Array reads holes as undefined,
   which Eve's nil checks would miss)."
  [n]
  (let [a (js/Array. n)]
    (.fill a nil)
    a))

(defn array-seq
  "cljs.core/array-seq: a seq over a JS array's elements. A copy (vec)
   rather than cljs's live view — Eve's call sites snapshot (vec,
   doseq, reduce over the result)."
  [arr]
  (seq (vec arr)))

(defn read-string
  "cljs.core/read-string over squint's EDN reader (its read_string is
   (opts, s) / (s), the same arities). Eve's call sites read data
   (numbers, EDN), which is the reader's whole grammar."
  ([s] (edn/read-string s))
  ([opts s] (edn/read-string opts s)))
(def array cljs.core/array)

(defn bit-xor
  "cljs.core/bit-xor as a value: squint core has the bit operations
   only as operators (a call head compiles to ^), never as fn
   objects, so code passing bit-xor as an argument (a flipped-tag
   probe's update) meets a free variable. The call in the body is
   the operator; this defn is the fn object."
  [x y]
  (cljs.core/bit-xor x y))

(defn sequence
  "clojure.core/sequence (the transducer arity), which squint core
   does not carry: a lazy seq of coll through xf, realized one
   input at a time into a small buffer — the shape cljs.core's own
   sequence takes, minus its chunking."
  ([coll] (seq coll))
  ([xf coll]
   (let [rf (xf (fn ([] []) ([acc] acc) ([acc x] (conj acc x))))]
     (letfn [(go [s buf]
               (lazy-seq
                (cond
                  (seq buf) (cons (first buf) (go s (rest buf)))
                  s (let [b (rf [] (first s))]
                      (if (reduced? b)
                        (seq @b)
                        (go (next s) b)))
                  :else (seq (rf [])))))]
       (go (seq coll) []))))
  ([xf coll & colls]
   (sequence xf (apply map list coll colls))))

(defn halt-when
  "clojure.core/halt-when, which squint core does not carry: a
   transducer that halts the reduction when (pred input) is truthy,
   answering the input — or (retf result input) when retf is given —
   as the reduced value."
  ([pred] (halt-when pred nil))
  ([pred retf]
   (fn [rf]
     (fn
       ([] (rf))
       ([result] (rf result))
       ([result input]
        (if (pred input)
          (reduced (if retf (retf result input) input))
          (rf result input)))))))

(defn seq?
  "Clojure's seq?: true only for an actual seq. Squint core's seq?
   is (x != null && x[Symbol.iterator]) — true for strings, arrays,
   maps and sets — which sends Eve's seq?-first dispatch into
   unbounded recursion (eve.thread.task/canonical on a form's
   symbols: a squint symbol is a string, a string is 'seq?', its
   chars are strings, a 1-char string maps over itself). True here:
   squint lists (type tag 4), conses and lazy seqs (tag 6), and Eve
   seq types (the ISeq protocol mark). A plain array is a vector
   here, not a seq — the same branch Clojure-side dispatch takes
   first anyway."
  [x]
  (boolean
   (and (some? x)
        (or (some? (aget x (js/Symbol.for "squint.core/ISeq")))
            (let [t (aget x (js/Symbol.for "squint.core/type"))]
              (or (== t 4) (== t 6)))))))

;; squint core's own protocol objects under eve.core's names (the SCI
;; face of these vars is a defprotocol stub in eve/core.cljc). Each
;; object travels with its method symbols: a deftype impl squint emits
;; against eve.core/<P> sets prototype[eve.core.<P>__<method>], so the
;; method-symbol vars must be squint core's own symbols too, or the
;; impl lands on an undefined property and dispatch silently misses.
(def IEquiv cljs.core/IEquiv)
(def IEquiv__equiv cljs.core/IEquiv__equiv)
(def IHash cljs.core/IHash)
(def IHash__hash cljs.core/IHash__hash)
(def IPrintWithWriter cljs.core/IPrintWithWriter)
(def IPrintWithWriter__pr_writer cljs.core/IPrintWithWriter__pr_writer)
(def ILookup cljs.core/ILookup)
(def ILookup__lookup cljs.core/ILookup__lookup)
(def IKVReduce cljs.core/IKVReduce)
(def IKVReduce__kv_reduce cljs.core/IKVReduce__kv_reduce)
(def IAssociative cljs.core/IAssociative)
(def IAssociative__assoc cljs.core/IAssociative__assoc)
(def IAssociative__contains_key_QMARK_ cljs.core/IAssociative__contains_key_QMARK_)
(def ICollection cljs.core/ICollection)
(def ICollection__conj cljs.core/ICollection__conj)
(def ICounted cljs.core/ICounted)
(def ICounted__count cljs.core/ICounted__count)
(def IDeref cljs.core/IDeref)
(def IDeref__deref cljs.core/IDeref__deref)
(def IEditableCollection cljs.core/IEditableCollection)
(def IEditableCollection__as_transient cljs.core/IEditableCollection__as_transient)
(def IEmptyableCollection cljs.core/IEmptyableCollection)
(def IEmptyableCollection__empty cljs.core/IEmptyableCollection__empty)
(def IIndexed cljs.core/IIndexed)
(def IIndexed__nth cljs.core/IIndexed__nth)
(def IMap cljs.core/IMap)
(def IMap__dissoc cljs.core/IMap__dissoc)
(def IMeta cljs.core/IMeta)
(def IMeta__meta cljs.core/IMeta__meta)
(def IReset cljs.core/IReset)
(def IReset__reset_BANG_ cljs.core/IReset__reset_BANG_)
(def ISeqable cljs.core/ISeqable)
(def ISeqable__seq cljs.core/ISeqable__seq)
(def ISet cljs.core/ISet)
(def ISet__disjoin cljs.core/ISet__disjoin)
(def IStack cljs.core/IStack)
(def IStack__peek cljs.core/IStack__peek)
(def IStack__pop cljs.core/IStack__pop)
(def ISwap cljs.core/ISwap)
(def ISwap__swap_BANG_ cljs.core/ISwap__swap_BANG_)
(def ITransientAssociative cljs.core/ITransientAssociative)
(def ITransientAssociative__assoc_BANG_ cljs.core/ITransientAssociative__assoc_BANG_)
(def ITransientCollection cljs.core/ITransientCollection)
(def ITransientCollection__conj_BANG_ cljs.core/ITransientCollection__conj_BANG_)
(def ITransientCollection__persistent_BANG_ cljs.core/ITransientCollection__persistent_BANG_)
(def ITransientMap cljs.core/ITransientMap)
(def ITransientMap__dissoc_BANG_ cljs.core/ITransientMap__dissoc_BANG_)
(def ITransientSet cljs.core/ITransientSet)
(def ITransientSet__disjoin_BANG_ cljs.core/ITransientSet__disjoin_BANG_)
(def ITransientVector cljs.core/ITransientVector)
(def ITransientVector__pop_BANG_ cljs.core/ITransientVector__pop_BANG_)
(def IVector cljs.core/IVector)
(def IWatchable cljs.core/IWatchable)
(def IWatchable__add_watch cljs.core/IWatchable__add_watch)
(def IWatchable__notify_watches cljs.core/IWatchable__notify_watches)
(def IWatchable__remove_watch cljs.core/IWatchable__remove_watch)
(def IWithMeta cljs.core/IWithMeta)
(def IWithMeta__with_meta cljs.core/IWithMeta__with_meta)

;; Protocols squint core does not export at all: defined here in
;; squint core's own shape (a {__sym} object and Symbol/for method
;; symbols under squint.core's names). Nothing in squint core
;; dispatches through these (its reduce walks seqs, its seqs are its
;; own class), so an impl landing here is inert beyond registration —
;; the same service the bare name would have had on a host whose core
;; exports them, and the references resolve instead of throwing.
(def IReduce #js {:__sym (js/Symbol.for "squint.core/IReduce")})
(def IReduce__reduce (js/Symbol.for "squint.core/-reduce"))
(def IFn #js {:__sym (js/Symbol.for "squint.core/IFn")})
(def IFn__invoke (js/Symbol.for "squint.core/-invoke"))
(def ISeq #js {:__sym (js/Symbol.for "squint.core/ISeq")})
(def ISeq__first (js/Symbol.for "squint.core/-first"))
(def ISeq__rest (js/Symbol.for "squint.core/-rest"))
(def ISeq__more (js/Symbol.for "squint.core/-more"))
(def ISeq__cons (js/Symbol.for "squint.core/-cons"))
(def ISequential #js {:__sym (js/Symbol.for "squint.core/ISequential")})
(def IReversible #js {:__sym (js/Symbol.for "squint.core/IReversible")})
(def IReversible__rseq (js/Symbol.for "squint.core/-rseq"))
;; squint core exports the ITransientVector object and its -pop!
;; symbol but no -assoc-n! symbol; the string is squint core's
;; naming convention for it. Same for IVector's -assoc-n.
(def ITransientVector__assoc_n_BANG_ (js/Symbol.for "squint.core/-assoc-n!"))
(def IVector__assoc_n (js/Symbol.for "squint.core/-assoc-n"))
(def INext #js {:__sym (js/Symbol.for "squint.core/INext")})
(def INext__next (js/Symbol.for "squint.core/-next"))
(def IMapEntry #js {:__sym (js/Symbol.for "squint.core/IMapEntry")})
(def IMapEntry__key (js/Symbol.for "squint.core/-key"))
(def IMapEntry__val (js/Symbol.for "squint.core/-val"))
(def IComparable #js {:__sym (js/Symbol.for "squint.core/IComparable")})
(def IComparable__compare (js/Symbol.for "squint.core/-compare"))

(defn array-chunk
  "A chunk of host array arr's n values: a vector (squint has no chunked seqs)."
  [arr n]
  (vec arr))
