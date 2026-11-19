(ns eve.view
  "Views: what a read of an eve atom answers outside `$` (the value model's
   V9, docs/ds-in-eve/EVE-FIX-PLAN.md §0 and X10). A view is a window onto
   the version the read pinned — no copy. Lookups (get, keyword invoke,
   get-in, nth, find, contains?, count, a deftype's field reads and protocol
   methods) read that version in place: a scalar answers itself, a subtree
   answers a child view of the same read. Consuming operations (seq and all
   built on it, =, hash, print, assoc/conj/dissoc, storing into an atom) pack
   that subtree once into flat data and act on the flat copy.

   The read's pin is held by its cell, which every view of the read
   references; it is released when the host GC collects the read's last view
   (JVM java.lang.ref.Cleaner, JS FinalizationRegistry). A host with neither
   has no views: its reads deliver flat (eve.flat.freeze/deliver-flat), as
   before. Inside `$` reads stay the region's (pinned until `$` exits).

   An eve-deftype's view is its generated flat sister over a field source
   that reads the pinned block: it answers the type's protocols, and the
   fields its methods read come back as views of the same read."
  (:require [eve.deftype-proto.data :as d]
            [eve.atom :as ea]
            [eve.flat.chunk :as chunk]
            [eve.flat.source :as source]
            [eve.flat.sister :as sister]
            [eve.flat.freeze :as freeze]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; the read's cell: holds the pin's release; collected with the last view
;; ---------------------------------------------------------------------------

(deftype Cell [release])

(defonce ^:private gc-hook
  #?(:clj (try (let [c (java.lang.ref.Cleaner/create)]
                 (fn [cell release] (.register ^java.lang.ref.Cleaner c cell ^Runnable release)))
               (catch Throwable _ nil))
     :cljs (when (exists? js/FinalizationRegistry)
             (let [r (js/FinalizationRegistry. (fn [release] (release)))]
               (fn [cell release] (.register r cell release))))
     :default nil))

(defn available?
  "True on a host whose GC can end a read (views are built there)."
  []
  (some? gc-hook))

(defn- cell-for
  "A cell whose collection runs release (exactly once)."
  [release]
  (let [done (volatile! false)
        once (fn [] (when-not @done (vreset! done true) (release)))
        c (Cell. once)]
    (gc-hook c once)
    c))

;; ---------------------------------------------------------------------------
;; consuming: the subtree packed once, flat (under the read's pin)
;; ---------------------------------------------------------------------------

(defprotocol IView
  (-raw [v] "the persistent value this view reads")
  (-flat [v] "this view's subtree as flat data (packed once)"))

(defn- consume [raw fl]
  (or @fl (vreset! fl (freeze/deliver-flat raw))))

(declare wrap)

(defn view?
  "x is a view (a window onto a pinned version, outside `$`)."
  [x]
  (satisfies? IView x))

(defn flat
  "x's flat form: a view's subtree packed; anything else as is."
  [x]
  (if (view? x) (-flat x) x))

;; ---------------------------------------------------------------------------
;; a deftype's view: its sister over a field source reading the pinned block
;; ---------------------------------------------------------------------------

(deftype SlabSource [raw desc cell fl]
  source/IFieldSource
  (-field [_ kw] (wrap (get (sister/field-values desc raw) kw) cell))
  (-field-map [_]
    (into (array-map) (map (fn [[k x]] [k (wrap x cell)])) (sister/field-values desc raw)))
  (-source-bytes [_] (chunk/bytes-of (consume raw fl)))
  IView
  (-raw [_] raw)
  (-flat [_] (consume raw fl)))

(defn- deftype-view [raw cell]
  (let [desc (sister/descriptor (d/-sabp-type-key raw))
        factory (:factory desc)]
    (if factory
      (factory (SlabSource. raw desc cell (volatile! nil)))
      (freeze/deliver-flat raw))))

;; ---------------------------------------------------------------------------
;; collection views
;; ---------------------------------------------------------------------------

#?(:clj
   (do
     (deftype ViewMap [raw cell fl]
       IView
       (-raw [_] raw)
       (-flat [_] (consume raw fl))
       chunk/IFlatHandle
       (-rep [_] (chunk/to-rep raw))
       (-chunk-bytes [_] (chunk/bytes-of (consume raw fl)))
       clojure.lang.ILookup
       (valAt [_ k] (wrap (get raw k) cell))
       (valAt [_ k nf] (if (contains? raw k) (wrap (get raw k) cell) nf))
       clojure.lang.IFn
       (invoke [this k] (.valAt this k))
       (invoke [this k nf] (.valAt this k nf))
       (applyTo [this args] (clojure.lang.AFn/applyToHelper this args))
       clojure.lang.IPersistentMap
       (assoc [_ k v] (assoc (consume raw fl) k v))
       (assocEx [_ k v] (.assocEx ^clojure.lang.IPersistentMap (consume raw fl) k v))
       (without [_ k] (dissoc (consume raw fl) k))
       (count [_] (count raw))
       (cons [_ o] (conj (consume raw fl) o))
       (empty [_] {})
       (equiv [_ o] (= (consume raw fl) (flat o)))
       (seq [_] (seq (consume raw fl)))
       (containsKey [_ k] (contains? raw k))
       (entryAt [this k] (when (contains? raw k) (clojure.lang.MapEntry. k (.valAt this k))))
       ;; Clojure's map equality takes another IPersistentMap only with this
       ;; marker
       clojure.lang.MapEquivalence
       clojure.lang.IHashEq
       (hasheq [_] (hash (consume raw fl)))
       clojure.lang.IKVReduce
       (kvreduce [_ f init] (reduce-kv f init (consume raw fl)))
       clojure.lang.IReduceInit
       (reduce [_ f init] (reduce f init (consume raw fl)))
       clojure.lang.IMeta
       (meta [_] nil)
       clojure.lang.IObj
       (withMeta [_ m] (with-meta (consume raw fl) m))
       java.lang.Iterable
       (iterator [_] (.iterator ^Iterable (consume raw fl)))
       java.util.Map
       (size [_] (count raw))
       (isEmpty [_] (zero? (count raw)))
       (containsValue [_ v] (.containsValue ^java.util.Map (consume raw fl) v))
       (get [this k] (.valAt this k))
       (keySet [_] (.keySet ^java.util.Map (consume raw fl)))
       (values [_] (.values ^java.util.Map (consume raw fl)))
       (entrySet [_] (.entrySet ^java.util.Map (consume raw fl)))
       (put [_ _ _] (throw (UnsupportedOperationException.)))
       (remove [_ _] (throw (UnsupportedOperationException.)))
       (putAll [_ _] (throw (UnsupportedOperationException.)))
       (clear [_] (throw (UnsupportedOperationException.)))
       Object
       (equals [_ o] (= (consume raw fl) (flat o)))
       (hashCode [_] (.hashCode ^Object (consume raw fl)))
       (toString [_] (str (consume raw fl))))

     (deftype ViewVec [raw cell fl]
       IView
       (-raw [_] raw)
       (-flat [_] (consume raw fl))
       chunk/IFlatHandle
       (-rep [_] (chunk/to-rep raw))
       (-chunk-bytes [_] (chunk/bytes-of (consume raw fl)))
       clojure.lang.Indexed
       (nth [_ i] (wrap (nth raw i) cell))
       (nth [_ i nf] (if (and (integer? i) (<= 0 i) (< i (count raw))) (wrap (nth raw i) cell) nf))
       clojure.lang.ILookup
       (valAt [this k] (.valAt this k nil))
       (valAt [this k nf] (if (integer? k) (.nth this (int k) nf) nf))
       clojure.lang.IFn
       (invoke [this i] (.nth this (int i)))
       (applyTo [this args] (clojure.lang.AFn/applyToHelper this args))
       clojure.lang.IPersistentVector
       (length [_] (count raw))
       (assocN [_ i v] (assoc (consume raw fl) i v))
       (cons [_ o] (conj (consume raw fl) o))
       (count [_] (count raw))
       (empty [_] [])
       (equiv [_ o] (= (consume raw fl) (flat o)))
       (seq [_] (seq (consume raw fl)))
       (assoc [_ k v] (assoc (consume raw fl) k v))
       (containsKey [_ k] (and (integer? k) (<= 0 k) (< k (count raw))))
       (entryAt [this k] (when (.containsKey this k) (clojure.lang.MapEntry. k (.nth this (int k)))))
       (peek [_] (peek (consume raw fl)))
       (pop [_] (pop (consume raw fl)))
       (rseq [_] (rseq (consume raw fl)))
       clojure.lang.Sequential
       clojure.lang.IHashEq
       (hasheq [_] (hash (consume raw fl)))
       clojure.lang.IReduceInit
       (reduce [_ f init] (reduce f init (consume raw fl)))
       clojure.lang.IMeta
       (meta [_] nil)
       clojure.lang.IObj
       (withMeta [_ m] (with-meta (consume raw fl) m))
       java.lang.Iterable
       (iterator [_] (.iterator ^Iterable (consume raw fl)))
       Object
       (equals [_ o] (= (consume raw fl) (flat o)))
       (hashCode [_] (.hashCode ^Object (consume raw fl)))
       (toString [_] (str (consume raw fl))))

     (deftype ViewSet [raw cell fl]
       IView
       (-raw [_] raw)
       (-flat [_] (consume raw fl))
       chunk/IFlatHandle
       (-rep [_] (chunk/to-rep raw))
       (-chunk-bytes [_] (chunk/bytes-of (consume raw fl)))
       clojure.lang.IPersistentSet
       (disjoin [_ k] (disj (consume raw fl) k))
       (contains [_ k] (contains? raw k))
       (get [_ k] (when (contains? raw k) (wrap (get raw k) cell)))
       (count [_] (count raw))
       (cons [_ o] (conj (consume raw fl) o))
       (empty [_] #{})
       (equiv [_ o] (= (consume raw fl) (flat o)))
       (seq [_] (seq (consume raw fl)))
       clojure.lang.IFn
       (invoke [this k] (.get this k))
       (applyTo [this args] (clojure.lang.AFn/applyToHelper this args))
       clojure.lang.IHashEq
       (hasheq [_] (hash (consume raw fl)))
       clojure.lang.IMeta
       (meta [_] nil)
       clojure.lang.IObj
       (withMeta [_ m] (with-meta (consume raw fl) m))
       java.util.Set
       (size [_] (count raw))
       (isEmpty [_] (zero? (count raw)))
       (iterator [_] (.iterator ^Iterable (consume raw fl)))
       (^objects toArray [_] (.toArray ^java.util.Collection (consume raw fl)))
       (^objects toArray [_ ^objects a] (.toArray ^java.util.Collection (consume raw fl) a))
       (containsAll [_ c] (every? #(contains? raw %) c))
       (add [_ _] (throw (UnsupportedOperationException.)))
       (remove [_ _] (throw (UnsupportedOperationException.)))
       (addAll [_ _] (throw (UnsupportedOperationException.)))
       (retainAll [_ _] (throw (UnsupportedOperationException.)))
       (removeAll [_ _] (throw (UnsupportedOperationException.)))
       (clear [_] (throw (UnsupportedOperationException.)))
       Object
       (equals [_ o] (= (consume raw fl) (flat o)))
       (hashCode [_] (.hashCode ^Object (consume raw fl)))
       (toString [_] (str (consume raw fl))))

     (defmethod print-method ViewMap [v ^java.io.Writer w] (print-method (-flat v) w))
     (defmethod print-method ViewVec [v ^java.io.Writer w] (print-method (-flat v) w))
     (defmethod print-method ViewSet [v ^java.io.Writer w] (print-method (-flat v) w)))

   :cljs
   (do
     (deftype ViewMap [raw cell fl]
       IView
       (-raw [_] raw)
       (-flat [_] (consume raw fl))
       chunk/IFlatHandle
       (-rep [_] (chunk/to-rep raw))
       (-chunk-bytes [_] (chunk/bytes-of (consume raw fl)))
       ILookup
       (-lookup [_ k] (wrap (get raw k) cell))
       (-lookup [_ k nf] (if (contains? raw k) (wrap (get raw k) cell) nf))
       IFn
       (-invoke [this k] (-lookup this k))
       (-invoke [this k nf] (-lookup this k nf))
       ICounted
       (-count [_] (count raw))
       IMap
       (-dissoc [_ k] (dissoc (consume raw fl) k))
       IAssociative
       (-contains-key? [_ k] (contains? raw k))
       (-assoc [_ k v] (assoc (consume raw fl) k v))
       IFind
       (-find [this k] (when (contains? raw k) (MapEntry. k (-lookup this k) nil)))
       ICollection
       (-conj [_ o] (conj (consume raw fl) o))
       IEmptyableCollection
       (-empty [_] {})
       ISeqable
       (-seq [_] (seq (consume raw fl)))
       IEquiv
       (-equiv [_ o] (= (consume raw fl) (flat o)))
       IHash
       (-hash [_] (hash (consume raw fl)))
       IKVReduce
       (-kv-reduce [_ f init] (reduce-kv f init (consume raw fl)))
       IReduce
       (-reduce [_ f] (reduce f (consume raw fl)))
       (-reduce [_ f init] (reduce f init (consume raw fl)))
       IIterable
       (-iterator [_] (-iterator (consume raw fl)))
       IMeta
       (-meta [_] nil)
       IWithMeta
       (-with-meta [_ m] (with-meta (consume raw fl) m))
       IPrintWithWriter
       (-pr-writer [_ w opts] (-pr-writer (consume raw fl) w opts)))

     (deftype ViewVec [raw cell fl]
       IView
       (-raw [_] raw)
       (-flat [_] (consume raw fl))
       chunk/IFlatHandle
       (-rep [_] (chunk/to-rep raw))
       (-chunk-bytes [_] (chunk/bytes-of (consume raw fl)))
       IIndexed
       (-nth [_ i] (wrap (nth raw i) cell))
       (-nth [_ i nf] (if (and (integer? i) (<= 0 i) (< i (count raw))) (wrap (nth raw i) cell) nf))
       ILookup
       (-lookup [this k] (-lookup this k nil))
       (-lookup [this k nf] (if (integer? k) (-nth this k nf) nf))
       IFn
       (-invoke [this i] (-nth this i))
       ICounted
       (-count [_] (count raw))
       IVector
       (-assoc-n [_ i v] (assoc (consume raw fl) i v))
       IAssociative
       (-contains-key? [_ k] (and (integer? k) (<= 0 k) (< k (count raw))))
       (-assoc [_ k v] (assoc (consume raw fl) k v))
       ISequential
       ICollection
       (-conj [_ o] (conj (consume raw fl) o))
       IEmptyableCollection
       (-empty [_] [])
       IStack
       (-peek [_] (peek (consume raw fl)))
       (-pop [_] (pop (consume raw fl)))
       ISeqable
       (-seq [_] (seq (consume raw fl)))
       IEquiv
       (-equiv [_ o] (= (consume raw fl) (flat o)))
       IHash
       (-hash [_] (hash (consume raw fl)))
       IReduce
       (-reduce [_ f] (reduce f (consume raw fl)))
       (-reduce [_ f init] (reduce f init (consume raw fl)))
       IIterable
       (-iterator [_] (-iterator (consume raw fl)))
       IMeta
       (-meta [_] nil)
       IWithMeta
       (-with-meta [_ m] (with-meta (consume raw fl) m))
       IPrintWithWriter
       (-pr-writer [_ w opts] (-pr-writer (consume raw fl) w opts)))

     (deftype ViewSet [raw cell fl]
       IView
       (-raw [_] raw)
       (-flat [_] (consume raw fl))
       chunk/IFlatHandle
       (-rep [_] (chunk/to-rep raw))
       (-chunk-bytes [_] (chunk/bytes-of (consume raw fl)))
       ISet
       (-disjoin [_ k] (disj (consume raw fl) k))
       ILookup
       (-lookup [_ k] (when (contains? raw k) (wrap (get raw k) cell)))
       (-lookup [_ k nf] (if (contains? raw k) (wrap (get raw k) cell) nf))
       IFn
       (-invoke [this k] (-lookup this k))
       ICounted
       (-count [_] (count raw))
       ICollection
       (-conj [_ o] (conj (consume raw fl) o))
       IEmptyableCollection
       (-empty [_] #{})
       ISeqable
       (-seq [_] (seq (consume raw fl)))
       IEquiv
       (-equiv [_ o] (= (consume raw fl) (flat o)))
       IHash
       (-hash [_] (hash (consume raw fl)))
       IIterable
       (-iterator [_] (-iterator (consume raw fl)))
       IMeta
       (-meta [_] nil)
       IWithMeta
       (-with-meta [_ m] (with-meta (consume raw fl) m))
       IPrintWithWriter
       (-pr-writer [_ w opts] (-pr-writer (consume raw fl) w opts)))))

;; ---------------------------------------------------------------------------
;; wrap: a value read under cell's pin, as what a read answers
;; ---------------------------------------------------------------------------

(defn- scalar? [x]
  (or (nil? x) (string? x) (number? x) (keyword? x) (symbol? x) (boolean? x) (char? x)))

(defn- slab? [x]
  (and (satisfies? d/IEveRoot x) (satisfies? d/IEveSio x) (some? (d/-eve-sio x))))

(defn- value-array? [x]
  (and (satisfies? d/ISabpType x) (= "eve/ValueArray" (d/-sabp-type-key x))))

(defn wrap
  "x, read under cell's pin, as a read answers it: a scalar as itself; an
   eve-deftype or record as its sister over the pinned block; an eve map,
   vector, value array or set as a view; any other persistent value (a stored
   flat value, a typed array, a dataset, a list...) delivered flat; anything
   else as is."
  [x cell]
  (cond
    (scalar? x) x
    (not (slab? x)) x
    (or (record? x) (sister/own-type-instance? x)) (deftype-view x cell)
    #?@(:clj [(map? x) (ViewMap. x cell (volatile! nil))
              (or (vector? x) (value-array? x)) (ViewVec. x cell (volatile! nil))
              (set? x) (ViewSet. x cell (volatile! nil))]
        :cljs [(map? x) (ViewMap. x cell (volatile! nil))
               (or (vector? x) (value-array? x)) (ViewVec. x cell (volatile! nil))
               (set? x) (ViewSet. x cell (volatile! nil))])
    :else (freeze/deliver-flat x)))

(defn- view-of
  "The read's answer for raw (read under a pin whose release is release):
   a view holding the pin until the GC collects it, or — a scalar, or a
   value delivered flat — the value, the pin released at once."
  [raw release]
  (let [cell (cell-for release)
        v (wrap raw cell)]
    (when-not (or (view? v) (satisfies? source/ISister v))
      ((.-release ^Cell cell)))
    v))

;; eve->clj looks through a view to the persistent value it reads (still
;; pinned: the view is reachable for the call)
(d/register-unwrap! (fn [x] (if (view? x) (-raw x) x)))

;; the atom's reads outside `$` answer views where the host's GC can end them
(when (available?)
  (ea/register-view-read! view-of))
