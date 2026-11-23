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

(defn expand-const-bytes
  "Runtime builder behind the const-bytes macro. The plain expansion
   (clojure.core/byte-array values) is unavailable on seats whose core
   is SCI's (nbb/scittle: SCI's clojure.core has no byte-array — the
   F3 load death at eve/hamt_util.cljc's xxh3-secret def), so const
   byte blocks are built here instead: the core's byte-array where
   the seat's core has one, a js/Uint8Array over the same values
   where it does not. Readers go through host aget + masking
   (hamt_util's ubyte), so the two representations are
   indistinguishable to every caller. On let-go neither the :clj nor
   the :cljs arm is read (its reader feature is :lg), so it carries
   its own arm: without it the body reads empty, const-bytes returns
   nil there, and everything built on it — hamt-util's xxh3 secret,
   eve.codec's BYTES-NIL/TRUE/FALSE and small-int cache — is nil
   with it (measured on :lg, eve-letgo jobs 47/48)."
  [values]
  ;; :default, not :clj — ClojureCLR's reader takes :cljr and basilisp's
  ;; :lpy, so a :clj/:cljs-only body read as EMPTY there and returned nil:
  ;; every const-bytes constant (the codec's BYTES-NIL/TRUE/FALSE and
  ;; small-int cache) was nil on both, and a persistent atom whose root
  ;; became a small integer died writing it (CLR: NullReferenceException at
  ;; alength in value->scalar-block!; basilisp: TypeError len(None)) —
  ;; fix-for-release F4, ants d8-m6 cljr and lpy members, 2026-11-09.
  #?(:cljs    (js/Uint8Array. (clojure.core/into-array values))
     :lg      (clojure.core/byte-array values)
     :default (clojure.core/byte-array values)))

;; tagged bytes on the JVM: byte-array expands to this call, so the tag is what types every (let [b (byte-array n)])
;; local in eve's shared code — untagged, each aset/aget on such a local went through clojure.lang.Reflector (69 sites
;; in eve.codec alone, on every value encoded; measured 2026-11-20 ET)
(defn #?(:clj ^{:tag "[B"} js-byte-array :default js-byte-array)
  "Signed byte storage: a size or a sequence, optionally with an init (a
   fill byte or a sequence). The runtime half of the byte-array routing in
   expand: a js/Int8Array on JavaScript seats (the compiled seat's runtime
   copy lives in adapters/core/cljs-compiler's eve/host.cljs, beside
   expand-const-bytes), the core's byte-array on the JVM."
  ([x]
   #?(:cljs (js/Int8Array. (if (number? x) x (clojure.core/into-array x)))
      :default (clojure.core/byte-array x)))
  ([size init]
   #?(:cljs (let [a (js/Int8Array. size)]
              (if (number? init)
                (.fill a init)
                (.set a (clojure.core/into-array (take size init))))
              a)
      ;; basilisp: its completion's byte-array takes the init itself (a
      ;; java.util.Arrays call here does not compile there: eve.alpha did
      ;; not load on basilisp, measured 2026-11-19)
      :lpy (clojure.core/byte-array size init)
      ;; let-go compiles :default and has no java.util.Arrays (eve.host did
      ;; not load on lg, measured 2026-11-22): the fill as plain asets
      :lg (let [n (int size)
                a (clojure.core/byte-array n)]
            (if (number? init)
              (let [b (clojure.core/byte init)]
                (dotimes [i n] (clojure.core/aset a i b)))
              (doseq [i (range (min n (count init)))]
                (clojure.core/aset a i (clojure.core/byte (nth init i)))))
            a)
      :default (let [a (clojure.core/byte-array (int size))]
                 (if (number? init)
                   (java.util.Arrays/fill a (clojure.core/byte init))
                   (doseq [i (range (min (int size) (count init)))]
                     (clojure.core/aset a i (clojure.core/byte (nth init i)))))
                 a))))

#?(:jolt
   (defn- jolt-ushr
     "jolt: (unsigned-bit-shift-right x k) with k a literal or a ^:const number in 0..62, as quot by 2^k when x is a
      non-negative integer — the same value (x >>> k is floor(x / 2^k) for x >= 0). jolt's unsigned-bit-shift-right is
      never open-coded: its helper masks with the 2^64-1 bignum and wraps to 64 bits on every call (~48 ns, against
      ~8 ns for the fixnum quot; decode-class-idx/node-at take it per slab read). Any other x (negative, not an
      integer) and any other k take clojure.core's call unchanged."
     [[x k :as args] core-call]
     (let [kv (cond (integer? k) k
                    (symbol? k) (let [v (resolve k)] (when (and (var? v) (:const (meta v))) (let [x @v] (when (integer? x) x)))))]
       (if (and (= 2 (count args)) kv (<= 0 kv 62))
         (let [xs (gensym "x")]
           (list 'let [xs x]
                 (list 'if (list 'clojure.core/and (list 'clojure.core/int? xs) (list 'clojure.core/>= xs 0))
                       (list 'clojure.core/quot xs (bit-shift-left 1 kv))
                       (list 'clojure.core/unsigned-bit-shift-right xs kv))))
         core-call))))

(defn expand
  "clojure.core's call of name n on args. (token) is a fresh cell, identical?
   only to itself; const-bytes is built by expand-const-bytes (some
   seats' cores have no byte-array) and const-objects is the core's
   array; (defdelay name & body) (defonce name (delay body)); vswap! one expansion,
   over vreset! and deref, so (vswap! v conj! x) holds where a core's vswap!
   is a function."
  [n args]
  (let [c (fn [s] (symbol "clojure.core" (name s)))]
    (case n
      token (list (c 'volatile!) nil)
      const-bytes (list 'eve.host/expand-const-bytes (first args))
      const-objects (cons (c 'object-array) args)
      defdelay (let [[nm & body] args] (list (c 'defonce) nm (list* (c 'delay) body)))
      vswap! (let [[v f & more] args sym (gensym "vol")]
               (list 'let [sym v] (list (c 'vreset!) sym (list* f (list (c 'deref) sym) more))))
      ;; SCI's JavaScript core has no byte-array (nbb/scittle), and neither
      ;; does ClojureScript's. Keep the memory facility in the host adapter,
      ;; not in applications. The routing is unconditional on purpose: this
      ;; file is read by the compiling host's reader, whose features are not
      ;; the target's, so a reader conditional here decides by the wrong
      ;; seat (measured on the cohost-media page: the compiled bundle called
      ;; cljs.core/byte-array, which does not exist, and every snapshot read
      ;; died on it).
      unsigned-bit-shift-right
      #?(:jolt (jolt-ushr args (cons (c n) args))
         :default (cons (c n) args))
      (if (= n 'byte-array)
        (cons 'eve.host/js-byte-array args)
        (cons (c n) args)))))

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
    `(clojure.core/extend-protocol ~proto nil ~@impls ~root ~@impls)))

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
