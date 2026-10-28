(ns eve.host-completion
  "jolt's completion of its core to Clojure's, loaded first by eve.host.

   swap!, reset! and compare-and-set! cast their atom to jolt's own
   (clojure.lang.Atom), so any other IAtom — an eve atom — throws \"cannot be
   cast to class clojure.lang.IAtom\" though it is one (measured, jolt
   v0.8.22). Clojure's call the atom's IAtom methods; for an atom that is not
   jolt's, so do these. into and the chunked-seq fns likewise reach a type's
   own (below). (Calls compiled after this, in any namespace. seq is a native
   op a re-rooted var does not reach: eve.core's seq, adapters/coll/jvm, is
   Clojure's.)")

(alter-var-root #'clojure.core/swap!
  (fn [core]
    (fn ([a f] (if (instance? clojure.lang.Atom a) (core a f) (.swap a f)))
      ([a f x] (if (instance? clojure.lang.Atom a) (core a f x) (.swap a f x)))
      ([a f x y] (if (instance? clojure.lang.Atom a) (core a f x y) (.swap a f x y)))
      ([a f x y & more]
       (if (instance? clojure.lang.Atom a) (apply core a f x y more) (.swap a f x y more))))))

(alter-var-root #'clojure.core/reset!
  (fn [core] (fn [a v] (if (instance? clojure.lang.Atom a) (core a v) (.reset a v)))))

(alter-var-root #'clojure.core/compare-and-set!
  (fn [core] (fn [a o n] (if (instance? clojure.lang.Atom a) (core a o n) (.compareAndSet a o n)))))

;; into takes the transient path for jolt's own collections only and folds
;; any other with persistent conj (host/chez/seq.ss jolt-into), though an eve
;; vector is an IEditableCollection: a large into allocates a path per
;; element. Clojure's goes through any IEditableCollection's transient: so
;; does this one for a type's (deftype's) — jolt's own are jolt's business.
(alter-var-root #'clojure.core/into
  (fn [core]
    (fn ([] []) ([to] to)
      ([to from]
       (if (and (instance? clojure.lang.IType to) (instance? clojure.lang.IEditableCollection to))
         (let [r (clojure.core/persistent!
                  (clojure.core/reduce clojure.core/conj! (clojure.core/transient to) from))]
           (if-let [m (clojure.core/meta to)] (clojure.core/with-meta r m) r))
         (core to from)))
      ([to xform from]
       (if (and (instance? clojure.lang.IType to) (instance? clojure.lang.IEditableCollection to))
         (let [r (clojure.core/persistent!
                  (clojure.core/transduce xform clojure.core/conj! (clojure.core/transient to) from))]
           (if-let [m (clojure.core/meta to)] (clojure.core/with-meta r m) r))
         (core to xform from))))))

;; jolt's chunk-first of a type's chunked seq is its first element and its
;; chunk-rest a lazy (rest s), which the seq's own rest calls back (unbounded
;; recursion). Clojure's answer an IChunkedSeq's own methods: so do these for
;; a type's (jolt's own chunked seqs, which its core walks, have no such
;; methods to call — measured: PersistentVector$ChunkedSeq).
(alter-var-root #'clojure.core/chunk-first
  (fn [core] (fn [s] (if (and (instance? clojure.lang.IType s) (instance? clojure.lang.IChunkedSeq s)) (.chunkedFirst s) (core s)))))
(alter-var-root #'clojure.core/chunk-rest
  (fn [core] (fn [s] (if (and (instance? clojure.lang.IType s) (instance? clojure.lang.IChunkedSeq s)) (.chunkedMore s) (core s)))))
(alter-var-root #'clojure.core/chunk-next
  (fn [core] (fn [s] (if (and (instance? clojure.lang.IType s) (instance? clojure.lang.IChunkedSeq s)) (.chunkedNext s) (core s)))))

;; jolt's replace has no transducer arity — (replace smap) throws "Wrong number
;; of args (1)" (measured, jolt f79e64d8) — where Clojure's
;; (replace smap) is (map #(if-let [e (find smap %)] (val e) %)).
(alter-var-root #'clojure.core/replace
  (fn [core]
    (fn ([smap] (clojure.core/map (fn [x] (if-let [e (clojure.core/find smap x)] (clojure.core/val e) x))))
      ([smap coll] (core smap coll)))))
