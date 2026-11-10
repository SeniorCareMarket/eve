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

;; A library written for the JVM names a primitive in a type tag by the
;; class of clojure.core's cast fn — (assoc meta :tag clojure.core$long)
;; is the idiom, its value on the JVM being that class (datascript's
;; db.cljc patch-tag). jolt's analyzer resolves a dotted symbol to a
;; clojure.core var named by the symbol — java.lang.Boolean is one — and
;; a $-bearing class name sits outside its class grammar, so the symbol
;; is unresolved and the library's analysis dies on it (F-JOLT-TAG,
;; measured, jolt v0.8.23). The classes exist: (class clojure.core/long)
;; is clojure.core$long. Intern them under their names, as jolt itself
;; interns the java.lang ones, and the symbol resolves to the very class
;; the JVM reads.
(doseq [f '[long double boolean int short byte char float]]
  (let [cname (clojure.core/symbol (clojure.core/str "clojure.core$" (clojure.core/name f)))]
    (when-not (clojure.core/find-var (clojure.core/symbol "clojure.core" (clojure.core/str cname)))
      (clojure.core/intern 'clojure.core cname
        (clojure.core/class @(clojure.core/find-var (clojure.core/symbol "clojure.core" (clojure.core/name f))))))))

;; A JVM library's portability macro can emit the JVM's raw deftype*
;; special form — a qualified name, a class name, fields, :implements
;; [clojure.lang.* ...], then flat method defs in JVM method names
;; (extend-clj's deftype-atom, under datascript's Conn). jolt reads :clj
;; but has no deftype*: its analyzer stops at the symbol. The form is
;; translatable here — jolt's deftype takes the same interfaces as spec
;; heads and registers methods under the names written — by grouping
;; the flat defs under their interfaces again. The atom surface then
;; already works: the swap!/reset!/compare-and-set! roots above call a
;; non-jolt atom's own .swap/.reset/.compareAndSet, which are the very
;; methods such a type defines.
;;
;; One JVM shape needs more than grouping: deftype there grows extra
;; constructors, and the extend family builds through the short one —
;; (new Conn atom nil {}), the user fields plus __meta and __extmap.
;; jolt's new takes every field, so a type whose field tail is
;; extend's [__validator __watches __meta __extmap __hash __hasheq] is
;; built here with just the short constructor's slots: __meta and
;; __extmap stay fields, __validator and __watches move into __extmap
;; under their own keys (method bodies are rewritten to match), and
;; __hash/__hasheq — named in no method body of that family — go.
(defmacro jvm-deftype*
  [qname classname fields & body]
  (let [opts    (loop [b (seq body) m {}]
                  (if (keyword? (first b))
                    (recur (nnext b) (assoc m (first b) (second b)))
                    m))
        methods (drop (* 2 (count opts)) body)
        fname   (fn [f] (symbol (name f)))
        extend-tail? (and (<= 6 (count fields))
                          (= '[__validator __watches __meta __extmap __hash __hasheq]
                             (mapv fname (take-last 6 fields))))
        fields  (if extend-tail?
                  (let [user  (drop-last 6 fields)
                        meta-f (nth fields (- (count fields) 4))
                        ext-f  (nth fields (- (count fields) 3))
                        bare   (fn [f] (with-meta (symbol (name f)) (meta f)))]
                    (vec (concat (map bare user)
                                 [(bare meta-f) (vary-meta (bare ext-f) assoc :volatile-mutable true)])))
                  fields)
        packed  (when extend-tail? '#{__validator __watches})
        pack    (fn pack [form]
                  (cond
                    (and (seq? form) (= 'set! (first form)) (contains? packed (second form)))
                    (list 'set! '__extmap
                          (list 'clojure.core/assoc '__extmap (keyword (second form)) (pack (nth form 2))))
                    (and (symbol? form) (contains? packed form))
                    (if (= '__watches form)
                      (list 'clojure.core/get '__extmap :__watches {})
                      (list 'clojure.core/get '__extmap (keyword form)))
                    (seq? form)    (apply list (map pack form))
                    (vector? form) (mapv pack form)
                    (map? form)    (into {} (map (fn [[k v]] [(pack k) (pack v)])) form)
                    (set? form)    (into #{} (map pack) form)
                    :else form))
        methods (if extend-tail?
                  (map (fn [mdef] (cons (first mdef) (cons (second mdef) (map pack (drop 2 mdef))))) methods)
                  methods)
        ifaces  (:implements opts)
        static  {'clojure.lang.IMeta          #{'meta}
                 'clojure.lang.IReference     #{'alterMeta 'resetMeta}
                 'clojure.lang.IDeref         #{'deref}
                 'clojure.lang.IRef           #{'setValidator 'getValidator 'addWatch 'removeWatch 'getWatches}
                 'clojure.lang.IAtom          #{'swap 'compareAndSet 'reset}
                 'clojure.lang.IAtom2         #{'swapVals 'resetVals}
                 'clojure.lang.ILookup        #{'valAt}
                 'clojure.lang.IKeywordLookup #{'getLookupThunk}}
        names-of (fn [iface]
                   (or (get static iface)
                       (try
                         (let [s      (str iface)
                               dotted (fn [t] (let [i (.lastIndexOf t ".")]
                                                  (when (pos? i)
                                                    (symbol (subs t 0 i) (subs t (inc i))))))
                               v      (or (resolve iface)
                                          (when-let [d (dotted s)] (resolve d))
                                          (when-let [d (dotted (.replace s "_" "-"))] (resolve d)))]
                           (when (and v (map? @v))
                             (set (map :name (vals (:sigs @v))))))
                         (catch Throwable _ nil))))
        groups  (reduce (fn [gs mdef]
                          (let [mname (symbol (name (first mdef)))
                                owner (first (filter (fn [i] (contains? (names-of i) mname)) ifaces))]
                            (when-not owner
                              (throw (ex-info (str "deftype*: no interface in :implements declares method " mname)
                                              {:method mname})))
                            (update gs owner (fnil conj []) (cons mname (rest mdef)))))
                        {}
                        methods)
        specs   (mapcat (fn [i] (cons i (get groups i)))
                        (filter (fn [i] (contains? groups i)) ifaces))]
    (list 'do
          (apply list 'deftype (symbol (name qname)) fields specs)
          (symbol (name qname)))))

(alter-meta! (clojure.core/intern 'clojure.core 'deftype* @#'jvm-deftype*)
             clojure.core/assoc :macro true)

;; ---------------------------------------------------------------------------
;; + - * checked (doc/arithmetic-overflow.md)
;; ---------------------------------------------------------------------------
;; The arithmetic contract (the operator's 2026-11-07 decision): checked
;; + - * throw ArithmeticException on i64 overflow on every host. This
;; seat's core promotes instead (measured: eve.overflow-test's six throw
;; assertions fail on the unbound seat; its +' and unchecked rows pass) —
;; jolt's Numbers are its own, not the JVM's. The binding is the JVM's
;; own intrinsic, which this JVM-hosted seat can call: two Longs go
;; through Math/*Exact (the overflow-flag branch, FREE as on the JVM);
;; any other operands keep the seat's core op, whose promoting answer is
;; the contract's for them (a double, a bigint, a ratio). The inlines
;; are dropped first, as glojure's completion drops them: a compiled
;; 2-arity call must reach the checked fn, not the core's expansion.

(def ^:private core+ clojure.core/+)
(def ^:private core- clojure.core/-)
(def ^:private core* clojure.core/*)

(defn checked-add [x y] (if (and (instance? Long x) (instance? Long y)) (Math/addExact x y) (core+ x y)))
(defn checked-sub [x y] (if (and (instance? Long x) (instance? Long y)) (Math/subtractExact x y) (core- x y)))
(defn checked-mul [x y] (if (and (instance? Long x) (instance? Long y)) (Math/multiplyExact x y) (core* x y)))
(defn checked-negate [x] (if (instance? Long x) (Math/negateExact x) (core- x)))
(defn checked-inc [x] (if (instance? Long x) (Math/incrementExact x) (core+ x 1)))
(defn checked-dec [x] (if (instance? Long x) (Math/decrementExact x) (core- x 1)))

;; What the binding reaches on this seat (all measured): the var roots
;; carry var-mediated calls — (apply (var-get #'clojure.core/+) [MAX 1])
;; throws. Compiled direct calls do NOT reach it: this seat's compiler
;; emits + as a native promoting op that consults neither the var root
;; nor :inline metadata (a namespace compiled after this file loads,
;; with both bindings in place, still promotes a direct (+ MAX 1)).
;; The :inline metadata is set anyway — arity-exact (+ - * 2-arity,
;; inc/dec 1-arity, -'s unary call left to the var root) — so the
;; binding is complete at every surface Eve controls; the compiled-call
;; gap is the seat's open finding (doc/arithmetic-overflow.md, the jolt
;; row), with an upstream observation for jolt's compiler: core
;; arithmetic is hard-compiled to promotion, so the stock checked
;; semantics cannot be bound from the completion surface.
(alter-meta! #'clojure.core/+ assoc :inline (fn* [x y] (list 'eve.host-completion/checked-add x y)))
(alter-meta! #'clojure.core/- assoc :inline (fn* [x y] (list 'eve.host-completion/checked-sub x y)))
(alter-meta! #'clojure.core/* assoc :inline (fn* [x y] (list 'eve.host-completion/checked-mul x y)))
(alter-meta! #'clojure.core/inc assoc :inline (fn* [x] (list 'eve.host-completion/checked-inc x)))
(alter-meta! #'clojure.core/dec assoc :inline (fn* [x] (list 'eve.host-completion/checked-dec x)))
(alter-var-root #'clojure.core/+
  (fn [_] (fn ([] 0) ([x] x) ([x y] (checked-add x y)) ([x y & more] (reduce checked-add (checked-add x y) more)))))
(alter-var-root #'clojure.core/-
  (fn [_] (fn ([x] (checked-negate x)) ([x y] (checked-sub x y)) ([x y & more] (reduce checked-sub (checked-sub x y) more)))))
(alter-var-root #'clojure.core/*
  (fn [_] (fn ([] 1) ([x] x) ([x y] (checked-mul x y)) ([x y & more] (reduce checked-mul (checked-mul x y) more)))))
(alter-var-root #'clojure.core/inc (fn [_] checked-inc))
(alter-var-root #'clojure.core/dec (fn [_] checked-dec))
