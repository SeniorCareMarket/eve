(ns ^:no-doc datascript.impl.entity
  ;; PORT DELTA (F-NBB-MACROS round): vanilla excludes get as well,
  ;; for its JS-Map method name; the interpreted seat's deftype
  ;; (nbb/SCI) expands through the core get in this namespace and
  ;; fails on the exclusion ("Unable to resolve symbol: get" at the
  ;; deftype form), where compiled ClojureScript qualifies it. The
  ;; JS-Map get is a prototype string now (see the block below), so
  ;; nothing here names a var get
  (:refer-clojure :exclude [keys])
  (:require
    ;; PORT DELTA (F-JANK-READER-LIBSPEC): as in datascript.core —
    ;; the #?-inside-libspec form is rejected by jank's loader;
    ;; spliced #?@ with one plain libspec per feature instead.
    ;; :lpy (basilisp) reads clojure.core like :clj.
    #?@(:cljs [[cljs.core :as c]]
        :clj  [[clojure.core :as c]] :cljr [[clojure.core :as c]]
        :lpy  [[clojure.core :as c]])
    ;; PORT DELTA (F-CLJE-HOST-REFERS): see datascript.util
    #?(:clje [eve.host :refer [volatile!]])
    [datascript.db :as db]))

(declare entity ->Entity equiv-entity lookup-entity touch hash-entity)

(defn- entid [db eid]
  (when (or (number? eid)
          (sequential? eid)
          (keyword? eid))
    (db/entid db eid)))

(defn entity [db eid]
  {:pre [(db/db? db)]}
  (when-let [e (entid db eid)]
    (when (db/numeric-eid-exists? db e)
      (->Entity db e (volatile! false) (volatile! {})))))

(defn- entity-attr [db a datoms]
  (if (db/multival? db a)
    (if (db/ref? db a)
      ;; PORT DELTA (F-BB-ENTITY-SET): on the :bb seat an Entity is
      ;; backed by babashka.impl.SciMap, whose hasheq walks an
      ;; iterator the native image does not implement -- (hash e) and
      ;; conj-into-a-set both throw UnsupportedOperationException
      ;; "Method not implemented: iterator", so a multival ref lookup
      ;; died outright. The :bb arm accumulates the same entities
      ;; into a vector in datom order (the set's dedup is vacuous
      ;; here: (e a v) datoms are unique). Type delta on this seat
      ;; only; making Entity itself hashable on bb is stage work for
      ;; the DataScript line, not this fix.
      #?(:bb (reduce #(conj %1 (entity db (:v %2))) [] datoms)
         :default (reduce #(conj %1 (entity db (:v %2))) #{} datoms))
      (reduce #(conj %1 (:v %2)) #{} datoms))
    (if (db/ref? db a)
      (entity db (:v (first datoms)))
      (:v (first datoms)))))

(defn- -lookup-backwards [db eid attr not-found]
  (if-let [datoms (not-empty (db/-search db [nil attr eid]))]
    (if (db/component? db attr)
      (entity db (:e (first datoms)))
      ;; PORT DELTA (F-BB-ENTITY-SET): see entity-attr above.
      #?(:bb (reduce #(conj %1 (entity db (:e %2))) [] datoms)
         :default (reduce #(conj %1 (entity db (:e %2))) #{} datoms)))
    not-found))

#?(:cljs
   (defn- multival->js [val]
     (when val (to-array val))))

#?(:cljs
   (defn- js-seq [e]
     (touch e)
     (for [[a v] @(.-cache e)]
       (if (db/multival? (.-db e) a)
         [a (multival->js v)]
         [a v]))))

#?(:cljs
  (unchecked-set (.-prototype ES6Iterator) cljs.core/ITER_SYMBOL
     (fn []
       (this-as this# this#))))

#?(:cljs
  (unchecked-set (.-prototype ES6EntriesIterator) cljs.core/ITER_SYMBOL
     (fn []
       (this-as this# this#))))

(deftype Entity [db eid touched cache]
  #?@(:cljs
      [Object
       (toString [this]
         (pr-str* this))

       ;; PORT DELTA (F-NBB-MACROS round): vanilla's Object group
       ;; also carries equiv and the JS-Map interface (keys, entries,
       ;; values, has, get, forEach, key_set, entry_set, value_set).
       ;; The interpreted CLJS seat's deftype (nbb/SCI) takes only
       ;; toString on Object — equiv is already IEquiv's below, and
       ;; the JS-Map methods are installed on the prototype after
       ;; this form (see below), which is where a deftype's Object
       ;; extras land in compiled ClojureScript as well
       IEquiv
       (-equiv [this o] (equiv-entity this o))

       IHash
       (-hash [this]
         (hash-entity this))

       ISeqable
       (-seq [this]
         (touch this)
         (seq @cache))

       ICounted
       (-count [this]
         (touch this)
         (count @cache))

       ILookup
       (-lookup [this attr]           (lookup-entity this attr nil))
       (-lookup [this attr not-found] (lookup-entity this attr not-found))

       IAssociative
       (-contains-key? [this k]
         (not= ::nf (lookup-entity this k ::nf)))

       IFn
       (-invoke [this k]
         (lookup-entity this k))
       (-invoke [this k not-found]
         (lookup-entity this k not-found))

       IPrintWithWriter
       (-pr-writer [_ writer opts]
         (-pr-writer (assoc @cache :db/id eid) writer opts))]

      :clj
      [Object
       (toString [e]      (pr-str (assoc @cache :db/id eid)))
       (hashCode [e]      (hash-entity e))
       (equals [e o]      (equiv-entity e o))

       clojure.lang.Seqable
       (seq [e]           (touch e) (seq @cache))

       clojure.lang.Associative
       (equiv [e o]       (equiv-entity e o))
       (containsKey [e k] (not= ::nf (lookup-entity e k ::nf)))
       ;; PORT DELTA (F-CLJE-MAPENTRY): see datascript.db's Datom
       (entryAt [e k]     (some->> (lookup-entity e k)
                            #?(:clje (vector k)
                               :clj  (clojure.lang.MapEntry. k) :cljr (clojure.lang.MapEntry. k)
                               :cljs (clojure.lang.MapEntry. k)
                               :lpy  (clojure.lang.MapEntry. k))))

       (empty [e]         (throw (UnsupportedOperationException.)))
       (assoc [e k v]     (throw (UnsupportedOperationException.)))
       (cons  [e [k v]]   (throw (UnsupportedOperationException.)))
       (count [e]         (touch e) (count @(.-cache e)))

       clojure.lang.ILookup
       (valAt [e k]       (lookup-entity e k))
       (valAt [e k not-found] (lookup-entity e k not-found))

       clojure.lang.IFn
       (invoke [e k]      (lookup-entity e k))
       (invoke [e k not-found] (lookup-entity e k not-found))] :cljr [Object
       (ToString [e]      (pr-str (assoc @cache :db/id eid)))
       (GetHashCode [e]   (hash-entity e))
       (Equals [e o]      (equiv-entity e o))

       clojure.lang.Seqable
       (seq [e]           (touch e) (seq @cache))

       clojure.lang.Associative
       (equiv [e o]       (equiv-entity e o))
       (containsKey [e k] (not= ::nf (lookup-entity e k ::nf)))
       ;; PORT DELTA (F-CLJE-MAPENTRY): see datascript.db's Datom
       (entryAt [e k]     (some->> (lookup-entity e k)
                            #?(:clje (vector k)
                               :clj  (clojure.lang.MapEntry. k) :cljr (clojure.lang.MapEntry. k)
                               :cljs (clojure.lang.MapEntry. k)
                               :lpy  (clojure.lang.MapEntry. k))))

       (empty [e]         (throw (System.NotSupportedException.)))
       (assoc [e k v]     (throw (System.NotSupportedException.)))
       (cons  [e [k v]]   (throw (System.NotSupportedException.)))
       (count [e]         (touch e) (count @(.-cache e)))

       clojure.lang.ILookup
       (valAt [e k]       (lookup-entity e k))
       (valAt [e k not-found] (lookup-entity e k not-found))

       clojure.lang.IFn
       (invoke [e k]      (lookup-entity e k))
       (invoke [e k not-found] (lookup-entity e k not-found))]

      :lpy
      [ ;; PORT DELTA (F-LPY-ENTITY): the splice had :cljs/:clj/:clje
       ;; arms only, and basilisp's reader carries no :clj fallback
       ;; (measured: a no-:lpy splice selects NOTHING on this seat),
       ;; so Entity carried NO protocol implementations on basilisp
       ;; at all — the same silent-bare-Entity state the clje lane
       ;; proved pre-F-CLJE-ENTITY (satisfies? ILookup false, every
       ;; keyword lookup silently the not-found). This arm mirrors
       ;; the :clj arm verbatim: JVM-interface groups in this
       ;; vocabulary are the proven DB path on this seat (eve.deftype
       ;; translates the groups itself; cf. db.cljc's Datom/DB
       ;; bodies, UnsupportedOperationException throws included).
       Object
       (toString [e]      (pr-str (assoc @cache :db/id eid)))
       (hashCode [e]      (hash-entity e))
       (equals [e o]      (equiv-entity e o))

       clojure.lang.Seqable
       (seq [e]           (touch e) (seq @cache))

       clojure.lang.Associative
       (equiv [e o]       (equiv-entity e o))
       (containsKey [e k] (not= ::nf (lookup-entity e k ::nf)))
       (entryAt [e k]     (some->> (lookup-entity e k)
                            #?(:clje (vector k)
                               :clj  (clojure.lang.MapEntry. k)
                               :cljs (clojure.lang.MapEntry. k)
                               :lpy  (clojure.lang.MapEntry. k))))

       (empty [e]         (throw (UnsupportedOperationException.)))
       (assoc [e k v]     (throw (UnsupportedOperationException.)))
       (cons  [e [k v]]   (throw (UnsupportedOperationException.)))
       (count [e]         (touch e) (count @(.-cache e)))

       clojure.lang.ILookup
       (valAt [e k]       (lookup-entity e k))
       (valAt [e k not-found] (lookup-entity e k not-found))

       clojure.lang.IFn
       (invoke [e k]      (lookup-entity e k))
       (invoke [e k not-found] (lookup-entity e k not-found))]

      :clje
      [ ;; PORT DELTA (F-CLJE-ENTITY): the splice had :cljs/:clj arms
       ;; only, so Entity carried NO protocol implementations on
       ;; clojerl at all -- (satisfies? clojerl.ILookup entity) was
       ;; false and every keyword lookup silently returned the
       ;; not-found (stage-47: entity2-name nil while -search worked).
       ;; This arm mirrors the :clj arm in clojerl's own protocol
       ;; vocabulary (the spellings Eve's beam types layer emits);
       ;; assoc/cons/empty throw ex-info where :clj throws
       ;; UnsupportedOperationException.
       clojerl.IStringable
       (str [e] (pr-str (assoc @(.-cache e) :db/id (.-eid e))))

       clojerl.IHash
       (hash [e] (hash-entity e))

       clojerl.IEquiv
       (equiv [e o] (equiv-entity e o))

       clojerl.ISeqable
       (seq [e] (touch e) (seq @(.-cache e)))

       clojerl.ICounted
       (count [e] (touch e) (count @(.-cache e)))

       clojerl.ILookup
       (get [e k] (lookup-entity e k))
       (get [e k not-found] (lookup-entity e k not-found))

       clojerl.IAssociative
       (contains_key [e k] (not= ::nf (lookup-entity e k ::nf)))
       (entry_at [e k] (some->> (lookup-entity e k) (vector k)))
       (assoc [e k v] (throw (ex-info "Entity assoc is not supported" {})))

       clojerl.IColl
       (cons [e [k v]] (throw (ex-info "Entity cons is not supported" {})))
       (empty [e] (throw (ex-info "Entity empty is not supported" {})))

       clojerl.IFn
       (apply [e args]
         (case (count args)
           1 (lookup-entity e (first args))
           2 (lookup-entity e (first args) (second args))
           (throw (ex-info "Entity invoke: wrong number of arguments" {}))))]))

(defn entity? [x] (instance? Entity x))

#?(:cljs
  ;; PORT DELTA (F-NBB-MACROS round): guarded like the JS-Map block
  ;; above — no JS prototype on the interpreted seat
  (when (.-prototype Entity)
    (unchecked-set (.-prototype Entity) cljs.core/ITER_SYMBOL
       (fn []
         (this-as this# (.entries this#))))))

#?(:clj
   (defmethod print-method Entity [e, ^java.io.Writer w]
     (.write w (str e))) :cljr (defmethod print-method Entity [e, ^System.IO.TextWriter w]
     (.Write w (str e))))

(defn- equiv-entity [^Entity this that]
  (and
    (instance? Entity that)
    (identical? (.-db this) (.-db ^Entity that)) ; `=` and `hash` on db is expensive
    (= (.-eid this) (.-eid ^Entity that))))

(defn- hash-entity [^Entity e]
  (db/combine-hashes
    (hash (.-eid e))
    ;; A hash compatible with `identical?`. Consistent with `=`.
    ;; PORT DELTA (F-CLJE-ENTITY): the fn-position select had no
    ;; :clje arm -- the call vanished on clojerl and (.-db e) itself
    ;; became combine-hashes' second argument. erlang/phash2 is the
    ;; seat's identity hash (the spelling Eve's beam types layer
    ;; emits for System/identityHashCode).
    (#?(:clj System/identityHashCode :cljr System.Runtime.CompilerServices.RuntimeHelpers/GetHashCode :cljs goog/getUid :clje erlang/phash2 :lpy python/id) (.-db e))))

(defn- lookup-entity
  ([this attr] (lookup-entity this attr nil))
  ([^Entity this attr not-found]
   (if (= attr :db/id)
     (.-eid this)
     (if (db/reverse-ref? attr)
       (-lookup-backwards (.-db this) (.-eid this) (db/reverse-ref attr) not-found)
       (if-some [v (@(.-cache this) attr)]
         v
         (if @(.-touched this)
           not-found
           (if-some [datoms (not-empty (db/-search (.-db this) [(.-eid this) attr]))]
             (let [value (entity-attr (.-db this) attr datoms)]
               (vreset! (.-cache this) (assoc @(.-cache this) attr value))
               value)
             not-found)))))))

;; PORT DELTA (F-NBB-MACROS round): the JS-Map interface vanilla
;; declares in Entity's deftype Object group, installed on the
;; prototype — where compiled ClojureScript puts a deftype's Object
;; extras. Guarded by the prototype's presence: the interpreted
;; seat's deftype (nbb/SCI) has no JS prototype (.-prototype reads
;; nil there), and Eve's seqs are the iteration path on that seat.
#?(:cljs
  (when (.-prototype Entity)
    (unchecked-set (.-prototype Entity) "equiv"
      (fn [other] (this-as this (equiv-entity this other))))
    (unchecked-set (.-prototype Entity) "keys"
      (fn [] (this-as this (es6-iterator (c/keys this)))))
    (unchecked-set (.-prototype Entity) "entries"
      (fn [] (this-as this (es6-entries-iterator (js-seq this)))))
    (unchecked-set (.-prototype Entity) "values"
      (fn [] (this-as this (es6-iterator (map second (js-seq this))))))
    (unchecked-set (.-prototype Entity) "has"
      (fn [attr] (this-as this (not (nil? (.get this attr))))))
    (unchecked-set (.-prototype Entity) "get"
      (fn [attr]
        (this-as this
          (if (= attr ":db/id")
            (.-eid this)
            (if (db/reverse-ref? attr)
              (-> (-lookup-backwards (.-db this) (.-eid this) (db/reverse-ref attr) nil)
                multival->js)
              (cond-> (lookup-entity this attr)
                (db/multival? (.-db this) attr) multival->js))))))
    (unchecked-set (.-prototype Entity) "forEach"
      (fn [f use-as-this]
        (this-as this
          (doseq [[a v] (js-seq this)]
            (if use-as-this
              (.call f use-as-this v a this)
              (f v a this))))))
    (unchecked-set (.-prototype Entity) "key_set"
      (fn [] (this-as this (to-array (c/keys this)))))
    (unchecked-set (.-prototype Entity) "entry_set"
      (fn [] (this-as this (to-array (map to-array (js-seq this))))))
    (unchecked-set (.-prototype Entity) "value_set"
      (fn [] (this-as this (to-array (map second (js-seq this))))))))

(defn touch-components [db a->v]
  (reduce-kv
    (fn [acc a v]
      (assoc acc a
        (if (db/component? db a)
          (if (db/multival? db a)
            (set (map touch v))
            (touch v))
          v)))
    {} a->v))

(defn- datoms->cache [db datoms]
  (reduce (fn [acc part]
            (let [a (:a (first part))]
              (assoc acc a (entity-attr db a part))))
    {} (partition-by :a datoms)))

(defn touch [^Entity e]
  {:pre [(or (nil? e) (entity? e))]}
  (when (some? e)
    (when-not @(.-touched e)
      (when-let [datoms (not-empty (db/-search (.-db e) [(.-eid e)]))]
        (vreset! (.-cache e) (->> datoms
                               (datoms->cache (.-db e))
                               (touch-components (.-db e))))
        (vreset! (.-touched e) true)))
    e))

#?(:cljs (goog/exportSymbol "datascript.impl.entity.Entity" Entity))
