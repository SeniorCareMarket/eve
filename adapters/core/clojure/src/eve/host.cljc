(ns eve.host
  "eve's host interface (adapters/README.md, 'eve is portable';
   doc/unification/HOST-CONTRACT.md §10) for every host whose core is
   Clojure's: the JVM, babashka, ClojureCLR, jolt, nbb, ClojureScript (whose
   compiler reads clojure.core/x as cljs.core/x) and basilisp (whose core
   eve.host-completion makes Clojure's first).

   Every name is a macro expanding to clojure.core's own call, so a type hint
   on an argument reaches the host's compiler as written and nothing is added
   at run time. Being macros, they are called, never passed as values."
  (:refer-clojure :exclude [aget aset alength aclone byte-array short-array int-array
                            long-array float-array double-array object-array into-array
                            to-array unchecked-byte unchecked-short unchecked-int
                            unchecked-long unchecked-add-int unchecked-multiply-int
                            unchecked-inc-int unchecked-dec-int unchecked-add
                            unchecked-multiply unchecked-inc unsigned-bit-shift-right
                            long double class volatile! volatile? vswap! locking
                            transient persistent! conj! assoc! dissoc! disj! pop!
                            bytes shorts ints longs floats doubles objects booleans
                            swap! reset! compare-and-set!])
  (:require [eve.host-completion]))

(def names
  '[aget aset alength aclone byte-array short-array int-array long-array float-array
    double-array object-array into-array to-array unchecked-byte unchecked-short
    unchecked-int unchecked-long unchecked-add-int unchecked-multiply-int
    unchecked-inc-int unchecked-dec-int unchecked-add unchecked-multiply unchecked-inc
    unsigned-bit-shift-right long double class volatile! volatile? vswap! locking
    transient persistent! conj! assoc! dissoc! disj! pop! bytes shorts ints longs floats
    doubles objects booleans const-bytes const-objects defdelay swap! reset!
    compare-and-set! token])

(defn expand
  "clojure.core's call of name n on args. (token) is a fresh cell, identical?
   only to itself; const-bytes and const-objects the core's arrays;
   (defdelay name & body) (defonce name (delay body)); vswap! one expansion,
   over vreset! and deref, so (vswap! v conj! x) holds where a core's vswap!
   is a function."
  [n args]
  (let [c (fn [s] (symbol "clojure.core" (name s)))]
    (case n
      token (list (c 'volatile!) nil)
      const-bytes (cons (c 'byte-array) args)
      const-objects (cons (c 'object-array) args)
      defdelay (let [[nm & body] args] (list (c 'defonce) nm (list* (c 'delay) body)))
      vswap! (let [[v f & more] args sym (gensym "vol")]
               (list 'let [sym v] (list (c 'vreset!) sym (list* f (list (c 'deref) sym) more))))
      (cons (c n) args))))

(defmacro ^:private defcore []
  (cons 'do (map (fn [n] (list 'defmacro n '[& args] (list 'eve.host/expand (list 'quote n) 'args)))
                 names)))

(defcore)

(defmacro extend-default
  "(extend-protocol proto nil impls... <root> impls...): proto for every
   value, nil included. The root type is Object, ClojureScript's default
   for a ClojureScript compile (its &env carries :ns; nbb reads this
   file's :cljs)."
  [proto & impls]
  (let [root (if (:ns &env) 'default #?(:cljs 'default :default 'Object))]
    `(extend-protocol ~proto nil ~@impls ~root ~@impls)))

(defmacro field
  "(field x f): the value of field f of x, a type's instance — (.-f x) on every host whose analyzer reads a field
   so (the JVM's, ClojureScript's, babashka's, ...); on jank the completion's read by name (jank's (.-f x) is C++
   member access on a static type: a dynamic value has none). The one name eve's shared code reads a field through.
   The expansion carries the call form's metadata: a type tag written on the call — (aget ^ints (field ctx xs) i) —
   is how the host's compiler picks the primitive overload of the call the field read sits inside, and an
   expansion built fresh drops it (measured 2026-11-05: the slab node readers' aget fell to its generic path,
   ~70x on direct map get in the assembled 0.9.3 tree)."
  [x f]
  (with-meta
    #?(:jank `(eve.host-completion/get-field ~x '~f)
       :default (list (symbol (str ".-" (name f))) x))
    (meta &form)))
