(ns eve.dataset
  "Columnar dataset: named columns (EveArrays) with atom-native storage.

   Built with eve-deftype — lives inside Eve atoms, backed by slab memory.

   Construction (inside swap!):
     (dataset {:price (arr/eve-array :float64 [10.5 20.3])
               :qty   (arr/eve-array :int32 [100 200])})

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
   [eve.array :as arr]
   [eve.dataset.argops :as argops]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   #?@(:cljs [[eve.deftype-proto.alloc :as alloc]
              [eve.deftype.slab-runtime :as slab-rt]]
       :clj  [[eve.deftype :refer [eve-deftype]]
              [eve.deftype.slab-runtime :as slab-rt]]))
  #?(:cljs (:require-macros [eve.deftype :refer [eve-deftype]])))

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

  eve.deftype-proto.data/ISabStorable
  (-sab-tag [_] :eve-deftype/Dataset)
  (-sab-encode [_ _env]
    (ser/encode-sab-pointer Dataset-type-id offset__))
  (-sab-dispose [_ _env]
    ;; Dispose each EveArray in col-map
    (let [#?@(:cljs [slab-base__ (alloc/resolve-dv! offset__)
                     slab-dv__ alloc/resolved-dv])
          cm #?(:cljs (slab-rt/slab-read-serialized offset__ 8)
                :clj  (slab-rt/slab-read-serialized sio__ slab-off__ 8))]
      (doseq [[_ arr-val] cm]
        (when (satisfies? d/ISabStorable arr-val)
          (d/-sab-dispose arr-val _env))))
    ;; Free serialized field data blocks
    #?(:cljs (do (slab-rt/slab-free-serialized! offset__ 4)
                 (slab-rt/slab-free-serialized! offset__ 8))
       :clj  (do (slab-rt/slab-free-serialized! sio__ slab-off__ 4)
                  (slab-rt/slab-free-serialized! sio__ slab-off__ 8)))
    ;; Free the instance block itself
    #?(:cljs (alloc/free! offset__)
       :clj  nil))

  #?@(:cljs
      [ICounted
       (-count [_] nrows)

       ILookup
       (-lookup [_ k] (get col-map k))
       (-lookup [_ k not-found] (get col-map k not-found))

       IFn
       (-invoke [_ k] (get col-map k))
       (-invoke [_ k not-found] (get col-map k not-found))

       ISeqable
       (-seq [_]
         (map (fn [k] (MapEntry. k (get col-map k) nil)) col-order))

       IHash
       (-hash [_]
         (hash [col-order nrows (mapv #(hash (get col-map %)) col-order)]))

       IEquiv
       (-equiv [_ other]
         (and (instance? Dataset other)
              (= nrows (-row-count other))
              (= col-order (-column-names other))
              (every? true? (map #(= (get col-map %) (get (-col-map other) %))
                                 col-order))))

       IPrintWithWriter
       (-pr-writer [_ writer _opts]
         (-write writer (str "#eve/dataset {" (count col-order) " cols × " nrows " rows}")))]

      :clj
      [clojure.lang.Counted
       (count [_] nrows)

       clojure.lang.ILookup
       (valAt [_ k] (get col-map k))
       (valAt [_ k not-found] (get col-map k not-found))

       clojure.lang.IFn
       (invoke [_ k] (get col-map k))
       (invoke [_ k not-found] (get col-map k not-found))

       clojure.lang.Seqable
       (seq [_]
         (map (fn [k] (clojure.lang.MapEntry/create k (get col-map k)))
              col-order))

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
         (str "#eve/dataset {" (count col-order) " cols × " nrows " rows}"))]))

;;-----------------------------------------------------------------------------
;; Predicates
;;-----------------------------------------------------------------------------

(defn dataset?
  "True if x is a Dataset."
  [x]
  (instance? Dataset x))

;;-----------------------------------------------------------------------------
;; Construction
;;-----------------------------------------------------------------------------

(defn dataset
  "Create a dataset from a map of {keyword → EveArray}.
   All columns must have the same length. Must be called inside swap!."
  [col-map]
  (let [entries (seq col-map)
        _ (when (empty? entries)
            (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
                    "Dataset requires at least one column")))
        col-order (vec (map first entries))
        lengths (map #(count (second %)) entries)
        n (first lengths)]
    (when-not (every? #(= n %) lengths)
      (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
              (str "All columns must have same length. Got: "
                   (zipmap col-order lengths)))))
    (->Dataset n col-order (into {} entries))))

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
    (->Dataset (-row-count ds) (vec col-keys) new-map)))

(defn add-column
  "Return new dataset with an additional column."
  [ds k col]
  (when (not= (count col) (-row-count ds))
    (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
            (str "Column length " (count col) " does not match dataset row count " (-row-count ds)))))
  (let [new-map (assoc (-col-map ds) k col)
        old-order (-column-names ds)
        new-order (if (some #{k} old-order)
                    old-order
                    (conj old-order k))]
    (->Dataset (-row-count ds) new-order new-map)))

(defn drop-column
  "Return new dataset without the specified column."
  [ds k]
  (let [new-map (dissoc (-col-map ds) k)
        new-order (vec (remove #{k} (-column-names ds)))]
    (->Dataset (-row-count ds) new-order new-map)))

(defn rename-columns
  "Return new dataset with columns renamed per rename-map {old-key → new-key}."
  [ds rename-map]
  (let [cm (-col-map ds)
        old-order (-column-names ds)
        new-order (mapv (fn [k] (get rename-map k k)) old-order)
        new-map (into {} (map (fn [k]
                                [(get rename-map k k) (get cm k)])
                              old-order))]
    (->Dataset (-row-count ds) new-order new-map)))

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
    (->Dataset (count idx-arr) (-column-names ds) new-map)))

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
