(ns eve.dataset
  "Columnar dataset: named columns (EveArrays) with atom-native storage.

   Built with eve-deftype — lives inside Eve atoms, backed by slab memory.

   Construction:
     (dataset {:price (arr/eve-array :float64 [10.5 20.3])
               :qty   (arr/eve-array :int32 [100 200])})
   Inside swap!, pass the atom's domain sio so the dataset allocates in the
   atom's slab (JVM):
     (swap! ea (fn [_] (dataset (eve.atom/atom-sio ea) {...})))

   Column access (zero-copy — reads from slab):
     (column ds :price)          ;; → EveArray
     (column-names ds)           ;; → [:price :qty]
     (row-count ds)              ;; → 2
     (dtypes ds)                 ;; → {:price :float64 :qty :int32}

   Structural ops (new Dataset, structural sharing on unchanged columns):
     (select-columns ds [:price])
     (add-column ds :total some-array)
     (drop-column ds :qty)
     (rename-columns ds {:price :cost})"
  (:require
   [eve.core :as e]
   [eve.array :as arr]
   [eve.dataset.argops :as argops]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.codec :as codec]
   [eve.encoding :as enc]
   [eve.platform :as p]
   [eve.deftype :refer [eve-deftype]]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype.slab-runtime :as slab-rt]))

;;-----------------------------------------------------------------------------
;; Protocol for accessing Dataset internals from standalone functions
;;-----------------------------------------------------------------------------

(defprotocol IDatasetAccess
  (-column-names [ds] "Return vector of column name keywords in order.")
  (-col-map [ds] "Return map of keyword → EveArray.")
  (-row-count [ds] "Return number of rows."))

;;-----------------------------------------------------------------------------
;; Dataset type — eve-deftype backed by slab memory
;;-----------------------------------------------------------------------------

(eve-deftype Dataset [^:int32 nrows
                      col-order  ;; serialized: vector of keyword column names
                      col-map]   ;; serialized: map of keyword → EveArray

  IDatasetAccess
  (-column-names [_] col-order)
  (-col-map [_] col-map)
  (-row-count [_] nrows)

  d/IPlain
  (-plain [_]
    (into {} (map (fn [k] [k (d/eve->clj (get col-map k))])) col-order))

  clojure.lang.Counted
  (count [_] nrows)

  clojure.lang.ILookup
  (valAt [_ k] (get col-map k))
  (valAt [_ k not-found] (get col-map k not-found))

  clojure.lang.IFn
  (invoke [_ k] (get col-map k))
  (invoke [_ k not-found] (get col-map k not-found))

  clojure.lang.Seqable
  (seq [_]
    (map (fn [k] (enc/map-entry k (get col-map k))) col-order))

  clojure.lang.IHashEq
  (hasheq [_]
    (hash [col-order nrows (mapv #(hash (get col-map %)) col-order)]))

  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

  java.lang.Object
  (equals [_ other]
    (and (instance? Dataset other)
         (= nrows (-row-count other))
         (= col-order (-column-names other))
         (every? true? (map #(= (get col-map %) (get (-col-map other) %))
                            col-order))))
  (hashCode [this] (.hasheq this))
  (toString [_]
    (str "#eve/dataset {" (e/count col-order) " cols × " nrows " rows}")))

;;-----------------------------------------------------------------------------
;; Predicates
;;-----------------------------------------------------------------------------

(defn dataset?
  "True if x is a Dataset."
  [x]
  (instance? Dataset x))

;;-----------------------------------------------------------------------------
;; Domain helpers — explicit slab contexts (no ambient state)
;;-----------------------------------------------------------------------------

(defn- default-sio
  "The slab I/O a constructor given none allocates in: the running swap!'s
   domain; outside a swap/domain context it throws (:no-domain-context)."
  []
  (alloc/current-sio))

(defn- ->dataset-in
  "->Dataset allocating in ds's own slab domain (JVM, bb) / the module slab
   (CLJS, whose constructor takes the sio and allocates there). Structural ops
   use this so derived datasets stay in the same domain."
  [ds nrows col-order col-map]
  (->Dataset (d/-eve-sio ds) nrows col-order col-map))

;;-----------------------------------------------------------------------------
;; Construction
;;-----------------------------------------------------------------------------

(defn dataset
  "Create a dataset from a map of {keyword → EveArray}.
   All columns must have the same length.

   ([col-map]) allocates in a fresh heap slab domain (JVM; no ambient state)
   — the dataset is self-contained and fully usable for reads and structural
   ops, but it can NOT be stored in an atom: atom roots must live in the
   atom's own slab domain.
   ([sio col-map]) allocates in sio's domain. When the dataset will be stored
   in an atom, pass that atom's domain sio (eve.atom/atom-sio):
     (swap! ea (fn [_] (dataset (eve.atom/atom-sio ea) {...})))"
  ([col-map] (dataset (default-sio) col-map))
  ([sio col-map]
   (let [entries (e/seq col-map)
         _ (when (empty? entries)
             (throw (enc/illegal-argument
                     "Dataset requires at least one column")))
         col-order (vec (map first entries))
         lengths (map #(e/count (second %)) entries)
         n (first lengths)]
     (when-not (every? #(= n %) lengths)
       (throw (enc/illegal-argument
               (str "All columns must have same length. Got: "
                    (zipmap col-order lengths)))))
     (->Dataset sio n col-order (into {} entries)))))

;;-----------------------------------------------------------------------------
;; Core accessors
;;-----------------------------------------------------------------------------

;;-----------------------------------------------------------------------------
;; Core accessors
;;-----------------------------------------------------------------------------

(defn column
  "Get a column by name. Returns the EveArray (deserialized from slab)."
  [ds k]
  (get (-col-map ds) k))

(defn column-names
  "Return vector of column name keywords in order."
  [ds]
  (-column-names ds))

(defn row-count
  "Return the number of rows."
  [ds]
  (-row-count ds))

(defn dtypes
  "Return {col-name → type-keyword} for all columns."
  [ds]
  (let [cm (-col-map ds)]
    (into {} (map (fn [k]
                    (let [col (get cm k)]
                      [k (arr/subtype->type-kw
                          (arr/array-subtype-code col))]))
                  (column-names ds)))))

;;-----------------------------------------------------------------------------
;; Structural ops (new Dataset, structural sharing on unchanged columns)
;;-----------------------------------------------------------------------------

(defn select-columns
  "Return new dataset with only the specified columns."
  [ds col-keys]
  (let [cm (-col-map ds)
        new-map (into {} (map (fn [k] [k (get cm k)])) col-keys)]
    (->dataset-in ds (-row-count ds) (vec col-keys) new-map)))

(defn add-column
  "Return new dataset with an additional column."
  [ds k col]
  (when (not= (e/count col) (-row-count ds))
    (throw (enc/illegal-argument
            (str "Column length " (e/count col) " does not match dataset row count " (-row-count ds)))))
  (let [new-map (assoc (-col-map ds) k col)
        old-order (-column-names ds)
        new-order (if (some #{k} old-order)
                    old-order
                    (conj old-order k))]
    (->dataset-in ds (-row-count ds) new-order new-map)))

(defn drop-column
  "Return new dataset without the specified column."
  [ds k]
  (let [new-map (dissoc (-col-map ds) k)
        new-order (vec (remove #{k} (-column-names ds)))]
    (->dataset-in ds (-row-count ds) new-order new-map)))

(defn rename-columns
  "Return new dataset with columns renamed per rename-map {old-key → new-key}."
  [ds rename-map]
  (let [cm (-col-map ds)
        old-order (-column-names ds)
        new-order (mapv (fn [k] (get rename-map k k)) old-order)
        new-map (into {} (map (fn [k]
                                [(get rename-map k k) (get cm k)])
                              old-order))]
    (->dataset-in ds (-row-count ds) new-order new-map)))

;;-----------------------------------------------------------------------------
;; Row operations (new Dataset with new EveArray blocks)
;;-----------------------------------------------------------------------------

(defn reindex
  "Return new dataset with rows reordered by an :int32 index array."
  [ds idx-arr]
  (let [cm (-col-map ds)
        new-map (into {} (map (fn [k]
                                [k (argops/take-indices (get cm k) idx-arr)])
                              (-column-names ds)))]
    (->dataset-in ds (e/count idx-arr) (-column-names ds) new-map)))

(defn filter-rows
  "Return new dataset with only rows where (pred (nth col i)) is truthy."
  [ds col-key pred]
  (let [idx (argops/argfilter (column ds col-key) pred)]
    (reindex ds idx)))

(defn sort-by-column
  "Return new dataset sorted by the given column.
   direction is :asc (default) or :desc."
  ([ds col-key] (sort-by-column ds col-key :asc))
  ([ds col-key direction]
   (let [idx (argops/argsort (column ds col-key) direction)]
     (reindex ds idx))))

(defn head
  "Return first n rows."
  [ds n]
  (let [n (min n (-row-count ds))
        idx (arr/eve-array :int32 (vec (range n)))]
    (reindex ds idx)))

(defn tail
  "Return last n rows."
  [ds n]
  (let [total (-row-count ds)
        n (min n total)
        start (- total n)
        idx (arr/eve-array :int32 (vec (range start total)))]
    (reindex ds idx)))

(defn slice
  "Return rows [start, end). Zero-copy if possible, otherwise copies."
  [ds start end]
  (let [end (min end (-row-count ds))
        idx (arr/eve-array :int32 (vec (range start end)))]
    (reindex ds idx)))
