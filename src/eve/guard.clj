(ns eve.guard
  "PROTOTYPE (jvm-heap-grade step 2) — the automatic read guard.

   A guard extent is the dynamic extent of a crossing (`with-guard` / `guard*`). Inside it, an eve atom's deref
   (eve.atom/mmap-deref) pins the atom's domain once for the extent and answers its root as a LAZY value — the
   slab-backed value read-root-value builds, wrapped in a validity proxy (LazyMap / LazyColl) — instead of
   materializing the whole root (plain-deref's d/eve->clj):
     - scalar access navigates the slab in place and decodes only the leaf (the wrapped value's own hamt-get / nth);
     - subtree access answers a child lazy value, chained to the same cell;
     - `materialize` (d/eve->clj, through IPlain) is the one explicit materialization point;
     - the proxies keep IEveRoot / IEveSio, so a same-domain swap! stores the source's own pointer (no copy).
   Validity is a chain of cells: an extent's cell links to its enclosing extent's cell; a lazy value is valid while
   every cell on its chain is open. Every access checks it; a use after the extent closes throws.

   The pins an extent takes are released when the extent that took them closes (the receiving side's lifecycle),
   last taken first released. A nested extent reuses a pin an enclosing extent already holds.

   No journals, scans, counters or arenas: a cell is one volatile, holds are a vector in one atom per extent.

   Interception gaps (prototype): assoc/dissoc/conj on a proxy throw (read-only); printing shows the deftype;
   map? is true for LazyMap, vector?/set? are false for LazyColl; a JVM host only (no cljs / bb / basilisp branch)."
  (:require [eve.deftype-proto.data :as d]))

;; ---------------------------------------------------------------------------
;; Cells and extents
;; ---------------------------------------------------------------------------

(deftype Cell [open? parent]) ;; open?: a volatile boolean; parent: the enclosing extent's Cell or nil

(defn valid? [^Cell c]
  (loop [^Cell c c]
    (cond (nil? c) true
          (not @(.-open? c)) false
          :else (recur (.-parent c)))))

(defn check! [c]
  (when-not (valid? c)
    (throw (ex-info "eve.guard: lazy eve value used after its guard extent closed (materialize it inside the extent)"
                    {:eve.guard/closed true}))))

(def ^:dynamic *extent*
  "This thread's innermost guard extent: {:cell Cell :held (atom [[k release] ...]) :parent extent :domain ds}."
  nil)

(def ^:dynamic *proxy-same-domain?*
  "true (default): a deref of the extent's own receiving domain is proxied like any other. false: it comes back raw
   (the slab value itself) — the domain-aware arm, measured against the proxy."
  true)

(defn active? [] (some? *extent*))

(defn current-cell [] (some-> *extent* :cell))

(defn- held-anywhere? [ext k]
  (loop [e ext]
    (cond (nil? e) false
          (some (fn [[k' _]] (identical? k k')) @(:held e)) true
          :else (recur (:parent e)))))

(defn hold!
  "Inside an extent: (acquire) the first time k (by identity) is held on this extent chain; (release) when the extent
   that acquired it closes. Answers true; outside an extent nil."
  [k acquire release]
  (when-let [ext *extent*]
    (when-not (held-anywhere? ext k)
      (acquire)
      (swap! (:held ext) conj [k release]))
    true))

(defn guard*
  "(f) inside a guard extent. receiving-domain (optional) names the domain state whose lifecycle owns the extent
   (eve.atom/-domain-state of the consuming side); a deref of an atom of that domain is a same-domain read.
   f's result is returned AS IS: lazy values in it are dead once this returns — materialize what must escape."
  ([f] (guard* nil f))
  ([receiving-domain f]
   (let [parent *extent*
         ext {:cell (Cell. (volatile! true) (some-> parent :cell))
              :held (atom [])
              :parent parent
              :domain (or receiving-domain (some-> parent :domain))}]
     (try
       (binding [*extent* ext] (f))
       (finally
         (vreset! (.-open? ^Cell (:cell ext)) false)
         (doseq [[_ release] (rseq @(:held ext))] (release)))))))

(defmacro with-guard
  "A guard extent around body (guard*)."
  [& body]
  `(guard* (fn [] ~@body)))

;; ---------------------------------------------------------------------------
;; The lazy proxies
;; ---------------------------------------------------------------------------

(declare wrap)

(defn- read-only! [op]
  (throw (UnsupportedOperationException.
          (str "eve.guard: " op " on a guarded lazy value — it is read-only; materialize it or swap! the atom"))))

(defprotocol IGuarded
  (-unwrap [this] "The slab-backed value under the proxy (checked)."))

(deftype LazyMap [^clojure.lang.IPersistentMap v cell]
  IGuarded
  (-unwrap [_] (check! cell) v)
  clojure.lang.ILookup
  (valAt [_ k] (check! cell) (wrap (.valAt v k) cell))
  (valAt [_ k nf] (check! cell) (wrap (.valAt v k nf) cell))
  clojure.lang.IFn
  (invoke [this k] (.valAt this k))
  (invoke [this k nf] (.valAt this k nf))
  clojure.lang.Associative
  (containsKey [_ k] (check! cell) (.containsKey v k))
  (entryAt [_ k] (check! cell)
    (when-let [e (.entryAt v k)] (clojure.lang.MapEntry. (key e) (wrap (val e) cell))))
  (assoc [_ _ _] (read-only! "assoc"))
  clojure.lang.IPersistentMap
  (assocEx [_ _ _] (read-only! "assocEx"))
  (without [_ _] (read-only! "dissoc"))
  clojure.lang.IPersistentCollection
  (count [_] (check! cell) (.count v))
  (cons [_ _] (read-only! "conj"))
  (empty [_] (read-only! "empty"))
  (equiv [_ o] (check! cell) (.equiv v (if (instance? eve.guard.IGuarded o) (-unwrap o) o)))
  clojure.lang.Counted
  clojure.lang.Seqable
  (seq [_] (check! cell)
    (when-let [s (seq v)]
      (map (fn [e] (check! cell) (clojure.lang.MapEntry. (key e) (wrap (val e) cell))) s)))
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))
  clojure.lang.IKVReduce
  (kvreduce [_ f init] (check! cell) (reduce-kv (fn [acc k x] (f acc k (wrap x cell))) init v))
  clojure.lang.IHashEq
  (hasheq [_] (check! cell) (hash v))
  Object
  (hashCode [_] (check! cell) (.hashCode ^Object v))
  (equals [this o] (.equiv this o))
  d/IPlain
  (-plain [_] (check! cell) (d/eve->clj v))
  d/IEveRoot
  (-root-header-off [_] (check! cell) (d/-root-header-off v))
  d/IEveSio
  (-eve-sio [_] (check! cell) (d/-eve-sio v)))

(deftype LazyColl [v cell] ;; an eve vector, set or list
  IGuarded
  (-unwrap [_] (check! cell) v)
  clojure.lang.ILookup
  (valAt [_ k] (check! cell) (wrap (get v k) cell))
  (valAt [_ k nf] (check! cell) (wrap (get v k nf) cell))
  clojure.lang.IFn
  (invoke [this k] (.valAt this k))
  clojure.lang.Indexed
  (nth [_ i] (check! cell) (wrap (nth v i) cell))
  (nth [_ i nf] (check! cell) (wrap (nth v i nf) cell))
  clojure.lang.IPersistentCollection
  (count [_] (check! cell) (count v))
  (cons [_ _] (read-only! "conj"))
  (empty [_] (read-only! "empty"))
  (equiv [_ o] (check! cell) (= v (if (instance? eve.guard.IGuarded o) (-unwrap o) o)))
  clojure.lang.Counted
  clojure.lang.Seqable
  (seq [_] (check! cell) (when-let [s (seq v)] (map (fn [x] (check! cell) (wrap x cell)) s)))
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))
  clojure.lang.IReduceInit
  (reduce [_ f init] (check! cell) (reduce (fn [acc x] (f acc (wrap x cell))) init v))
  clojure.lang.IHashEq
  (hasheq [_] (check! cell) (hash v))
  Object
  (hashCode [_] (check! cell) (.hashCode ^Object v))
  (equals [this o] (.equiv this o))
  d/IPlain
  (-plain [_] (check! cell) (d/eve->clj v))
  d/IEveRoot
  (-root-header-off [_] (check! cell) (d/-root-header-off v))
  d/IEveSio
  (-eve-sio [_] (check! cell) (d/-eve-sio v)))

(defn wrap
  "x as the extent's lazy value: an eve collection proxied under cell; a scalar (already decoded: heap-owned) as is."
  [x cell]
  (cond
    (or (nil? x) (number? x) (string? x) (keyword? x) (boolean? x)) x
    (instance? eve.deftype_proto.data.IEveRoot x)
    (if (instance? clojure.lang.IPersistentMap x) (LazyMap. x cell) (LazyColl. x cell))
    :else x))

(defn unwrap
  "The slab-backed value under a proxy (checked); anything else as is."
  [x]
  (if (instance? eve.guard.IGuarded x) (-unwrap x) x))

(defn materialize
  "The one explicit materialization point: x as plain data (d/eve->clj), checked."
  [x]
  (d/eve->clj x))
