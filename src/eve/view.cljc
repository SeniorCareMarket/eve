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
   (JVM java.lang.ref.Cleaner, jolt weak references, JS
   FinalizationRegistry) — read-end B. Read-end C holds no pin: each access
   pins briefly and the read ends at the domain's next commit; a host with no
   GC hook reads in C. Inside `$` reads stay the region's (pinned until `$` exits).

   An eve-deftype's view is its generated flat sister over a field source
   that reads the pinned block: it answers the type's protocols, and the
   fields its methods read come back as views of the same read."
  (:require [eve.deftype-proto.data :as d]
            [eve.atom :as ea]
            [eve.flat.chunk :as chunk]
            [eve.fast-proto :as fp]
            [eve.flat.source :as source]
            [eve.flat.sister :as sister]
            [eve.flat.freeze :as freeze]
            [eve.read-region :as rr]
            [eve.deftype-proto.macros :as eve]
            ;; a reader-registering type is defined below: the registry must load first (a ClojureScript
            ;; bundle orders by requires; eve.deftype-guard-test's reader-registration-load-order)
            [eve.type-readers]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.core :as e]
            [eve.gc :as gc]))

;; ---------------------------------------------------------------------------
;; the read's cell: holds the pin's release; collected with the last view
;; ---------------------------------------------------------------------------

;; gc: the GC hook's token (eve.gc/token), held by the cell alone, so the read
;; ends when the cell is collected
(deftype Cell [release open? acc gc])

;; The host's GC hook (feature gc: eve.gc, one adapter per host capability,
;; adapters/gc): what ends a read whose last view the program dropped (read
;; hygiene: a pin left open blocks every process sharing the domain from
;; reclaiming). With none, reads are C (no pin of their own) — no pin is ever
;; left open.

(defn sweep!
  "Release every read whose cell the GC has collected (where the host's hook
   is swept by eve; a no-op elsewhere). eve calls it at every read."
  []
  (gc/sweep!))

(defn available?
  "True on a host whose GC can end a read (read-end B is possible there;
   views are built on every host — C needs no GC)."
  []
  (gc/available?))

(defn- env-setting
  "A setting from the environment (NAME) or a JVM system property (prop),
   else nil."
  [name prop]
  #?(:clj (or (System/getenv name) (System/getProperty prop))
     :cljs (when (and (exists? js/process) (.-env js/process))
             (aget (.-env js/process) name))
     :default nil))

(defonce ^:private read-end
  ;; where a read outside `$` ends (EVE_READ_END / eve.read-end):
  ;;   :gc      (B) when the host GC collects its last view
  ;;   :commit  (C) at the next commit in its domain (any atom, any
  ;;            process): it holds no pin; used after that, it throws
  ;; B needs the host's GC hook; a host with none reads in C, which needs
  ;; no GC (each access pins briefly and checks the domain epoch)
  (atom (if (or (not (gc/available?)) (= "commit" (env-setting "EVE_READ_END" "eve.read-end"))) :commit :gc)))

(defn set-read-end!
  "Where a read outside `$` ends: :gc (B) or :commit (C). B needs the host's
   GC hook; on a host with none the read stays in C."
  [mode]
  (reset! read-end (if (and (= :gc mode) (not (gc/available?))) :commit mode)))

(defn read-end-mode [] @read-end)

(defn- release-once
  "The read's release, run once. Built in its own scope: what the GC hook
   keeps (the release) must not reach the cell, and an interpreter's closure
   (glojure's) holds its whole enclosing scope, not only what it names."
  [open? release]
  (fn [] (when @open? (vreset! open? false) (release))))

(defn- cell-for
  "The cell of a read (ctx: the atom's read api — :epoch, :hold, :enter,
   :exit). Inside `$`: a pin of its own, released at the region's exit (V10).
   Outside, read-end :gc (B): a pin of its own, released when the host GC
   collects the cell (V9). Outside, read-end :commit (C): no pin — each
   access pins briefly and the read ends at the domain's next commit.
   Answers [cell, release-now] (release-now: let the read go at once, for a
   read that answered no view)."
  [ctx]
  (let [open? (volatile! true)]
    (if (and (= :commit @read-end) (not (rr/active?)))
      [(Cell. (fn []) open? (select-keys ctx [:enter :exit :epoch]) nil) (fn [])]
      (let [once (release-once open? ((:hold ctx)))
            region? (rr/active?)
            ;; the GC hook's token, held by the cell alone: collected with it
            c (Cell. once open? nil (when-not region? (gc/token once)))]
        (when region? (rr/on-exit! once))
        [c once]))))

(defn- ended! [^Cell cell]
  (vreset! (.-open? cell) false)
  (throw (ex-info "eve view: used after its read ended (its $ exited, or — read-end :commit — its domain committed since)"
                  {:type :eve/read-ended})))

(defn- in
  "(f), which reads the persistent value under cell, while the read is open:
   refused once it ended. read-end :commit (C): the read holds no pin of its
   own — each access pins this thread at the domain's current epoch, checks
   no commit has happened since the read (the domain epoch is unchanged), and
   lets go."
  [^Cell cell f]
  (if-let [acc (.-acc cell)]
    (let [now ((:enter acc))]
      (try
        (when (or (not @(.-open? cell)) (not= now (:epoch acc)))
          (ended! cell))
        (f)
        (finally ((:exit acc)))))
    (do (when-not @(.-open? cell) (ended! cell))
        (f))))

;; ---------------------------------------------------------------------------
;; consuming: the subtree packed once, flat (under the read's pin)
;; ---------------------------------------------------------------------------

(defprotocol IView
  (-raw [v] "the persistent value this view reads")
  (-flat [v] "this view's subtree as flat data (packed once)"))

(defn- consume [raw fl cell]
  (or @fl (vreset! fl (in cell (fn [] (freeze/deliver-flat raw))))))

(declare wrap)

(defn view?
  "x is a view (a window onto a pinned version, outside `$`)."
  [x]
  (fp/fast-satisfies? IView x))

(defn flat
  "x's flat form: a view's subtree packed; anything else as is."
  [x]
  (if (view? x) (-flat x) x))

;; ---------------------------------------------------------------------------
;; a deftype's view: its sister over a field source reading the pinned block
;; ---------------------------------------------------------------------------

(deftype SlabSource [raw desc cell fl]
  source/IFieldSource
  (-field [_ kw] (wrap (get (in cell (fn [] (sister/field-values desc raw))) kw) cell))
  (-field-map [_]
    (into (array-map) (map (fn [[k x]] [k (wrap x cell)])) (in cell (fn [] (sister/field-values desc raw)))))
  (-source-bytes [_] (chunk/bytes-of (consume raw fl cell)))
  IView
  (-raw [_] raw)
  (-flat [_] (consume raw fl cell)))

(defn- sister-desc
  "x's sister descriptor when it has a flat sister to view it through (an
   eve-deftype's, a record's), else nil. Keyed on the sister, not on
   record?: a host's record? can answer true for eve's own map (glojure)."
  [x]
  (when (satisfies? d/ISabpType x)
    (let [desc (sister/descriptor (d/-sabp-type-key x))]
      (when (:factory desc) desc))))

(defn- deftype-view [raw desc cell]
  ((:factory desc) (SlabSource. raw desc cell (volatile! nil))))

;; ---------------------------------------------------------------------------
;; collection views
;; ---------------------------------------------------------------------------

;; One body per type, in the CLJ API, realized on every host by
;; eve/host-deftype (the host's eve.types adapter maps the interface names) —
;; the way eve's own map, vector and set types are written. Lookups read the
;; pinned version in place; everything else consumes the subtree (packed once).

(eve/host-deftype ViewMap [raw cell fl]
                  IView
                  (-raw [_] raw)
                  (-flat [_] (consume raw fl cell))
                  chunk/IFlatHandle
                  (-rep [_] (in cell (fn [] (chunk/to-rep raw))))
                  (-chunk-bytes [_] (chunk/bytes-of (consume raw fl cell)))
                  chunk/IFlatDigest
                  (-digest [_] (chunk/digest-of (consume raw fl cell)))
                  clojure.lang.Counted
                  (count [_] (in cell (fn [] (count raw))))
                  clojure.lang.Seqable
                  (seq [_] (seq (consume raw fl cell)))
                  clojure.lang.IMeta
                  (meta [_] nil)
                  clojure.lang.IObj
                  (withMeta [_ m] (with-meta (consume raw fl cell) m))
                  clojure.lang.ILookup
                  (valAt [_ k] (wrap (in cell (fn [] (get raw k))) cell))
                  (valAt [_ k nf] (if (in cell (fn [] (contains? raw k))) (wrap (in cell (fn [] (get raw k))) cell) nf))
                  clojure.lang.Associative
                  (containsKey [_ k] (in cell (fn [] (contains? raw k))))
                  (assoc [_ k v] (assoc (consume raw fl cell) k v))
                  clojure.lang.IPersistentMap
                  (without [_ k] (dissoc (consume raw fl cell) k))
                  (entryAt [_ k] (when (in cell (fn [] (contains? raw k)))
                                   (clojure.lang.MapEntry/create k (wrap (in cell (fn [] (get raw k))) cell))))
                  (assocEx [_ k v] (if (in cell (fn [] (contains? raw k)))
                                     (throw (ex-info "Key already present" {:key k}))
                                     (assoc (consume raw fl cell) k v)))
                  clojure.lang.IPersistentCollection
                  (cons [_ o] (conj (consume raw fl cell) o))
                  (empty [_] {})
                  (equiv [_ o] (= (consume raw fl cell) (flat o)))
                  clojure.lang.IReduce
                  (reduce [_ f] (reduce f (consume raw fl cell)))
                  clojure.lang.IReduceInit
                  (reduce [_ f init] (reduce f init (consume raw fl cell)))
                  clojure.lang.IKVReduce
                  (kvreduce [_ f init] (reduce-kv f init (consume raw fl cell)))
                  clojure.lang.IHashEq
                  (hasheq [_] (hash (consume raw fl cell)))
                  clojure.lang.IFn
                  (invoke [_ k] (wrap (in cell (fn [] (get raw k))) cell))
                  (invoke [_ k nf] (if (in cell (fn [] (contains? raw k))) (wrap (in cell (fn [] (get raw k))) cell) nf))
                  clojure.lang.MapEquivalence
                  java.lang.Iterable
                  (iterator [_] (clojure.lang.SeqIterator. (seq (consume raw fl cell))))
                  java.util.Map
                  (size [_] (in cell (fn [] (count raw))))
                  (isEmpty [_] (zero? (in cell (fn [] (count raw)))))
                  (containsValue [_ v] (boolean (some #(= v %) (vals (consume raw fl cell)))))
                  (get [_ k] (wrap (in cell (fn [] (get raw k))) cell))
                  (put [_ _ _] (throw (UnsupportedOperationException.)))
                  (remove [_ _] (throw (UnsupportedOperationException.)))
                  (putAll [_ _] (throw (UnsupportedOperationException.)))
                  (clear [_] (throw (UnsupportedOperationException.)))
                  (keySet [_] (set (keys (consume raw fl cell))))
                  (values [_] (vals (consume raw fl cell)))
                  (entrySet [_] (set (seq (consume raw fl cell))))
                  java.lang.Object
                  (toString [_] (pr-str (consume raw fl cell)))
                  (equals [_ o] (= (consume raw fl cell) (flat o)))
                  (hashCode [_] (hash (consume raw fl cell)))
                  IPrintWithWriter
                  (-pr-writer [this w opts] (e/pr-writer (-flat this) w opts)))

(eve/host-deftype ViewVec [raw cell fl]
                  IView
                  (-raw [_] raw)
                  (-flat [_] (consume raw fl cell))
                  chunk/IFlatHandle
                  (-rep [_] (in cell (fn [] (chunk/to-rep raw))))
                  (-chunk-bytes [_] (chunk/bytes-of (consume raw fl cell)))
                  chunk/IFlatDigest
                  #_{:clj-kondo/ignore [:unresolved-protocol-method]} ; Eve protocol method: the protocol is Eve's own and the implementing type is built by Eve's deftype macros; kondo's cljs analysis does not join the two
                  (-digest [_] (chunk/digest-of (consume raw fl cell)))
                  clojure.lang.Sequential
                  clojure.lang.Counted
                  (count [_] (in cell (fn [] (count raw))))
                  clojure.lang.Seqable
                  (seq [_] (seq (consume raw fl cell)))
                  clojure.lang.IMeta
                  (meta [_] nil)
                  clojure.lang.IObj
                  (withMeta [_ m] (with-meta (consume raw fl cell) m))
                  clojure.lang.ILookup
                  (valAt [_ k] (if (and (integer? k) (<= 0 k) (< k (in cell (fn [] (count raw)))))
                                 (wrap (in cell (fn [] (nth raw k))) cell) nil))
                  (valAt [_ k nf] (if (and (integer? k) (<= 0 k) (< k (in cell (fn [] (count raw)))))
                                    (wrap (in cell (fn [] (nth raw k))) cell) nf))
                  clojure.lang.Indexed
                  (nth [_ i] (wrap (in cell (fn [] (nth raw i))) cell))
                  (nth [_ i nf] (if (and (integer? i) (<= 0 i) (< i (in cell (fn [] (count raw)))))
                                  (wrap (in cell (fn [] (nth raw i))) cell) nf))
                  clojure.lang.IPersistentCollection
                  (cons [_ o] (conj (consume raw fl cell) o))
                  (empty [_] [])
                  (equiv [_ o] (= (consume raw fl cell) (flat o)))
                  clojure.lang.IHashEq
                  (hasheq [_] (hash (consume raw fl cell)))
                  clojure.lang.IPersistentStack
                  (peek [_] (peek (consume raw fl cell)))
                  (pop [_] (pop (consume raw fl cell)))
                  clojure.lang.IPersistentVector
                  (assocN [_ i v] (assoc (consume raw fl cell) i v))
                  (length [_] (in cell (fn [] (count raw))))
                  (entryAt [_ i] (when (and (integer? i) (<= 0 i) (< i (in cell (fn [] (count raw)))))
                                   (clojure.lang.MapEntry/create i (wrap (in cell (fn [] (nth raw i))) cell))))
                  clojure.lang.Reversible
                  (rseq [_] (rseq (consume raw fl cell)))
                  clojure.lang.Associative
                  (assoc [_ k v] (assoc (consume raw fl cell) k v))
                  (containsKey [_ k] (and (integer? k) (<= 0 k) (< k (in cell (fn [] (count raw))))))
                  clojure.lang.IFn
                  (invoke [_ i] (wrap (in cell (fn [] (nth raw i))) cell))
                  clojure.lang.IReduceInit
                  (reduce [_ f init] (reduce f init (consume raw fl cell)))
                  clojure.lang.IReduce
                  (reduce [_ f] (reduce f (consume raw fl cell)))
                  java.lang.Iterable
                  (iterator [_] (clojure.lang.SeqIterator. (seq (consume raw fl cell))))
                  java.lang.Object
                  (toString [_] (pr-str (consume raw fl cell)))
                  (equals [_ o] (= (consume raw fl cell) (flat o)))
                  (hashCode [_] (hash (consume raw fl cell)))
                  IPrintWithWriter
                  (-pr-writer [this w opts] (e/pr-writer (-flat this) w opts)))

(eve/host-deftype ViewSet [raw cell fl]
                  IView
                  (-raw [_] raw)
                  (-flat [_] (consume raw fl cell))
                  chunk/IFlatHandle
                  (-rep [_] (in cell (fn [] (chunk/to-rep raw))))
                  (-chunk-bytes [_] (chunk/bytes-of (consume raw fl cell)))
                  chunk/IFlatDigest
                  (-digest [_] (chunk/digest-of (consume raw fl cell)))
                  clojure.lang.Counted
                  (count [_] (in cell (fn [] (count raw))))
                  clojure.lang.Seqable
                  (seq [_] (seq (consume raw fl cell)))
                  clojure.lang.IMeta
                  (meta [_] nil)
                  clojure.lang.IObj
                  (withMeta [_ m] (with-meta (consume raw fl cell) m))
                  clojure.lang.ILookup
                  (valAt [_ k] (when (in cell (fn [] (contains? raw k))) (wrap (in cell (fn [] (get raw k))) cell)))
                  (valAt [_ k nf] (if (in cell (fn [] (contains? raw k))) (wrap (in cell (fn [] (get raw k))) cell) nf))
                  clojure.lang.IPersistentSet
                  (disjoin [_ k] (disj (consume raw fl cell) k))
                  (contains [_ k] (in cell (fn [] (contains? raw k))))
                  (get [_ k] (when (in cell (fn [] (contains? raw k))) (wrap (in cell (fn [] (get raw k))) cell)))
                  clojure.lang.IPersistentCollection
                  (cons [_ o] (conj (consume raw fl cell) o))
                  (empty [_] #{})
                  (equiv [_ o] (= (consume raw fl cell) (flat o)))
                  clojure.lang.IReduce
                  (reduce [_ f] (reduce f (consume raw fl cell)))
                  clojure.lang.IReduceInit
                  (reduce [_ f init] (reduce f init (consume raw fl cell)))
                  clojure.lang.IHashEq
                  (hasheq [_] (hash (consume raw fl cell)))
                  clojure.lang.IFn
                  (invoke [_ k] (when (in cell (fn [] (contains? raw k))) (wrap (in cell (fn [] (get raw k))) cell)))
                  (invoke [_ k nf] (if (in cell (fn [] (contains? raw k))) (wrap (in cell (fn [] (get raw k))) cell) nf))
                  java.lang.Iterable
                  (iterator [_] (clojure.lang.SeqIterator. (seq (consume raw fl cell))))
                  java.util.Set
                  (size [_] (in cell (fn [] (count raw))))
                  (isEmpty [_] (zero? (in cell (fn [] (count raw)))))
                  (containsAll [_ c] (in cell (fn [] (every? (fn [x] (contains? raw x)) c))))
                  (add [_ _] (throw (UnsupportedOperationException.)))
                  (remove [_ _] (throw (UnsupportedOperationException.)))
                  (addAll [_ _] (throw (UnsupportedOperationException.)))
                  (retainAll [_ _] (throw (UnsupportedOperationException.)))
                  (removeAll [_ _] (throw (UnsupportedOperationException.)))
                  (clear [_] (throw (UnsupportedOperationException.)))
                  java.lang.Object
                  (toString [_] (pr-str (consume raw fl cell)))
                  (equals [_ o] (= (consume raw fl cell) (flat o)))
                  (hashCode [_] (hash (consume raw fl cell)))
                  IPrintWithWriter
                  (-pr-writer [this w opts] (e/pr-writer (-flat this) w opts)))

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
    (sister-desc x) (deftype-view x (sister-desc x) cell)
    (map? x) (ViewMap. x cell (volatile! nil))
    (or (vector? x) (value-array? x)) (ViewVec. x cell (volatile! nil))
    (set? x) (ViewSet. x cell (volatile! nil))
    :else (freeze/deliver-flat x)))

(defn- view-of
  "The read's answer for raw (read under a pin whose release is release):
   a view holding the pin until the GC collects it, or — a scalar, or a
   value delivered flat — the value, the pin released at once."
  [raw ctx]
  (let [[cell release-now] (cell-for ctx)
        v (wrap raw cell)]
    (when-not (or (view? v) (satisfies? source/ISister v))
      (release-now))
    v))

;; eve->clj looks through a view to the persistent value it reads (still
;; pinned: the view is reachable for the call)
(d/register-unwrap! (fn [x] (if (view? x) (-raw x) x)))

;; the atom's reads outside `$` answer views on every host: in B where the
;; host's GC can end them, else in C
(ea/register-view-read! (fn [raw ctx] (sweep!) (view-of raw ctx)))
