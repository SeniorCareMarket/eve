(ns eve.host
  "eve's host interface on squint (adapters/README.md, 'eve is portable'):
   squint compiles ClojureScript-syntax source to ES modules over its own
   core module (squint-cljs/core.js, a cljs.core-shaped runtime). As on
   cherry (eve's other compile-to-JS host), every name here is a function
   calling the host's core module, never core/clojure's macros — a
   core-qualified expansion would name fns squint's core does not have.

   ONE FILE, unlike cherry's host.cljc + host.cljs pair — measured on
   squint 0.16.213 (Bucket F, 2026-11-12): when squint's SCI loads a
   .cljc macro namespace (eve.deftype-proto's alloc/vocab) that refers
   eve.host vars, it resolves the referred vars from the namespace's
   .cljc only; the .cljs half of a pair is invisible there, and the load
   dies with 'aget does not exist' / 'into-array does not exist' (47 of
   the port's 56 first-compile errors). A single .cljc holding both the
   macros and the functions compiles, SCI-loads and runs (mini-repro:
   pair fails with the production error verbatim; single file passes).

   Squint-shaped differences from cherry's core, measured on squint
   0.16.213: no aget VAR (squint compiles the unshadowed aget FORM to
   direct indexing; shadowed by this namespace's own aget it compiles
   to a call of that defn — eve.host-prim holds the forms unshadowed
   and the wrappers below delegate; see its docstring), no
   byte-array / short-array (typed arrays, as cherry), no
   unchecked-byte / unchecked-short (intN wraps by shifting), no
   unchecked-multiply-int (Math.imul), no volatile? (the class is
   taken from an instance), and no 64-bit unchecked arithmetic
   (squint's numbers are doubles; + * inc dec stand in — the byte-exact
   64-bit lanes on JS hosts live in eve.encoding / eve.codec over
   DataView and BigInt, not in these names)."
  (:refer-clojure :exclude [aget aset alength aclone byte-array short-array int-array
                            long-array float-array double-array object-array into-array
                            to-array unchecked-byte unchecked-short unchecked-int
                            unchecked-long unchecked-add-int unchecked-multiply-int
                            unchecked-inc-int unchecked-dec-int unchecked-add
                            unchecked-multiply unchecked-inc unsigned-bit-shift-right
                            long double class volatile! volatile? vswap!
                            transient persistent! conj! assoc! dissoc! disj! pop!
                            bytes shorts ints longs floats doubles objects booleans
                            swap! reset! compare-and-set!])
  (:require ;; Load-order ballast (Bucket F): eve.core's SCI face carries the
            ;; core protocol stubs (ILookup & friends). A namespace squint
            ;; SCI-loads as macro graph resolves the protocols a deftype
            ;; names from the SCI registry, which holds a stub only once
            ;; eve.core's .cljc has been evaluated in that session. The
            ;; refer forces that evaluation (a plain require does not —
            ;; measured); eve.host is the first require of the namespaces
            ;; whose graphs reach an ILookup-naming deftype (eve.atom and
            ;; kin), so the stubs register before any deftype form in those
            ;; graphs evaluates. eve.core requires nothing: no cycle.
   [eve.core :refer [ILookup IEquiv IHash IPrintWithWriter]]
   ["squint-cljs/core.js" :as core]
   [eve.host-prim :as prim]
   [eve.host-completion]))

;; === macros (the names that take forms rather than values) ===

(defmacro locking
  "JavaScript runs one thread an isolate: the body, as cljs.core's locking."
  [x & body]
  `(do ~x ~@body))

(defmacro defdelay
  "(defonce name (delay body))."
  [nm & body]
  `(defonce ~nm (delay ~@body)))

(defmacro extend-default
  "proto for every value, nil included: extend-type on nil and on default
   (squint has extend-type, not extend-protocol)."
  [proto & impls]
  `(do (extend-type nil ~proto ~@impls)
       (extend-type ~'default ~proto ~@impls)))

(defmacro field
  "(field x f): the value of field f of x, a type's instance: (.-f x) (eve.host's name for a field read; on jank the
   completion's read by name). Squint munges a dashed property name on .-
   access (camelCase) where a #js literal key stays verbatim; eve's field
   names carry no dashes, so the read is as written."
  [x f]
  (list (symbol (str ".-" (name f))) x))

;; === functions ===

(defn aget
  ([a i] (prim/idx a i))
  ([a i & is] (reduce prim/idx a (cons i is))))
(defn aset
  ([a i v] (core/aset a i v))
  ([a i j & more] (apply core/aset a i j more)))
(defn alength [a] (core/alength a))
(defn aclone [a] (core/aclone a))

(defn- typed [ctor size-or-seq init]
  (if (number? size-or-seq)
    (let [a (new ctor size-or-seq)] (when (some? init) (.fill a init)) a)
    (.from ctor (core/into-array size-or-seq))))
(defn byte-array ([s] (typed js/Int8Array s nil)) ([n init] (typed js/Int8Array n init)))
(defn short-array ([s] (typed js/Int16Array s nil)) ([n init] (typed js/Int16Array n init)))
(defn float-array ([s] (core/float-array s)) ([n init] (core/float-array n init)))
(defn int-array ([s] (core/int-array s)) ([n init] (core/int-array n init)))
(defn long-array ([s] (core/long-array s)) ([n init] (core/long-array n init)))
(defn double-array ([s] (core/double-array s)) ([n init] (core/double-array n init)))
(defn object-array ([s] (core/object-array s)) ([n init] (core/object-array n init)))
(defn into-array ([s] (core/into-array s)) ([t s] (core/into-array t s)))
(defn to-array [s] (core/to-array s))

(defn unchecked-byte [x] (bit-shift-right (bit-shift-left x 24) 24))
(defn unchecked-short [x] (bit-shift-right (bit-shift-left x 16) 16))
(defn unchecked-int [x] (core/unchecked-int x))
(defn unchecked-long [x] (core/long x))
(defn unchecked-add-int [a b] (core/unchecked-add-int a b))
(defn unchecked-multiply-int [a b] (js/Math.imul a b))
(defn unchecked-inc-int [a] (core/unchecked-inc-int a))
(defn unchecked-dec-int [a] (core/unchecked-dec-int a))
(defn unchecked-add [a b] (+ a b))
(defn unchecked-multiply [a b] (* a b))
(defn unchecked-inc [a] (inc a))
(defn unsigned-bit-shift-right [x n] (prim/ushr x n))
(defn long [x] (core/long x))
(defn double [x] (core/double x))
(defn class [x] (core/type x))

(defn volatile! [x] (core/volatile! x))
(def ^:private volatile-ctor (.-constructor (core/volatile! nil)))
(defn volatile? [x] (instance? volatile-ctor x))
(defn vswap! [v f & args] (core/vreset! v (apply f (core/deref v) args)))
(defn token [] (core/volatile! nil))

(defn transient [coll] (core/transient coll))
(defn persistent! [t] (core/persistent! t))
(defn conj!
  ([] (core/conj!))
  ([t] (core/conj! t))
  ([t x] (core/conj! t x))
  ([t x & xs] (apply core/conj! t x xs)))
(defn assoc!
  ([t k v] (core/assoc! t k v))
  ([t k v & kvs] (apply core/assoc! t k v kvs)))
(defn dissoc! [t & ks] (apply core/dissoc! t ks))
(defn disj! [t & xs] (apply core/disj! t xs))
(defn pop! [t] (core/pop! t))

(defn bytes [x] x)
(defn shorts [x] x)
(defn ints [x] x)
(defn longs [x] x)
(defn floats [x] x)
(defn doubles [x] x)
(defn objects [x] x)
(defn booleans [x] x)
(defn const-bytes [s] (byte-array s))
(defn const-objects [s] (core/object-array s))

(defn swap! [a f & args] (apply core/swap! a f args))
(defn reset! [a v] (core/reset! a v))
(defn compare-and-set! [a old new] (core/compare-and-set! a old new))
