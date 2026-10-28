(ns eve.host
  "eve's host interface on cherry (adapters/README.md, 'eve is portable'): cherry
   compiles ClojureScript onto its own ES module of cljs.core, but it reads a
   core-qualified symbol by its bare name, so the Clojure adapter's macros
   (clojure.core/long beside a namespace's referred long) expand into
   themselves. Here every name is a function calling cherry's core module
   through an alias cherry resolves as written; the three that take forms —
   locking, defdelay, extend-default — are this namespace's macros
   (host.cljc). The names JavaScript's core lacks are its typed arrays (a
   byte array an Int8Array, as the JVM's bytes are signed), the identity
   coercions, and class as the value's constructor."
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
  (:require ["cherry-cljs/cljs.core.js" :as core])
  (:require-macros [eve.host]))

(defn aget
  ([a i] (core/aget a i))
  ([a i & is] (apply core/aget a i is)))
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
(defn float-array ([s] (typed js/Float32Array s nil)) ([n init] (typed js/Float32Array n init)))
(defn int-array ([s] (core/int-array s)) ([n init] (core/int-array n init)))
(defn long-array ([s] (core/long-array s)) ([n init] (core/long-array n init)))
(defn double-array ([s] (core/double-array s)) ([n init] (core/double-array n init)))
(defn object-array ([s] (core/object-array s)) ([n init] (core/object-array n init)))
(defn into-array ([s] (core/into-array s)) ([t s] (core/into-array t s)))
(defn to-array [s] (core/to-array s))

(defn unchecked-byte [x] (core/unchecked-byte x))
(defn unchecked-short [x] (core/unchecked-short x))
(defn unchecked-int [x] (core/unchecked-int x))
(defn unchecked-long [x] (core/unchecked-long x))
(defn unchecked-add-int [a b] (core/unchecked-add-int a b))
(defn unchecked-multiply-int [a b] (core/unchecked-multiply-int a b))
(defn unchecked-inc-int [a] (core/unchecked-inc-int a))
(defn unchecked-dec-int [a] (core/unchecked-dec-int a))
(defn unchecked-add [a b] (core/unchecked-add a b))
(defn unchecked-multiply [a b] (core/unchecked-multiply a b))
(defn unchecked-inc [a] (core/unchecked-inc a))
(defn unsigned-bit-shift-right [x n] (core/unsigned-bit-shift-right x n))
(defn long [x] (core/long x))
(defn double [x] (core/double x))
(defn class [x] (core/type x))

(defn volatile! [x] (core/volatile! x))
(defn volatile? [x] (core/volatile? x))
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
