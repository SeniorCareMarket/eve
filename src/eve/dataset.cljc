(ns eve.dataset
  "Columnar dataset: named columns (EveArrays) with atom-native storage.

   Construction:
     (dataset {:price (arr/eve-array :float64 [10.5 20.3])
               :qty   (arr/eve-array :int32 [100 200])})

   Column access (zero-copy — returns the EveArray directly):
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
   [eve.dataset.argops :as argops]))

;;-----------------------------------------------------------------------------
;; Dataset type
;;-----------------------------------------------------------------------------

(deftype Dataset [col-map    ;; {keyword → EveArray} — ordered (array-map or linked)
                  col-order  ;; [keyword ...] — column name order
                  nrows      ;; int — number of rows
                  #?@(:cljs [^:mutable __hash
                             ^IPersistentMap _meta]
                      :clj  [^:unsynchronized-mutable __hash
                             _meta])]

  #?@(:cljs
      [Object
       (toString [this]
         (str "#eve/dataset {" (count col-order) " cols × " nrows " rows}"))

       IMeta
       (-meta [_] _meta)
       IWithMeta
       (-with-meta [_ new-meta]
         (Dataset. col-map col-order nrows __hash new-meta))

       ICounted
       (-count [_] nrows)

       ILookup
       (-lookup [_ k] (get col-map k))
       (-lookup [_ k not-found] (get col-map k not-found))

       IFn
       (-invoke [_ k] (get col-map k))
       (-invoke [_ k not-found] (get col-map k not-found))

       ISeqable
       (-seq [_]
         (map (fn [k] #?(:cljs (MapEntry. k (get col-map k) nil)
                         :clj  (clojure.lang.MapEntry/create k (get col-map k))))
              col-order))

       IHash
       (-hash [this]
         (if __hash
           __hash
           (let [h (hash [col-order nrows (mapv #(hash (get col-map %)) col-order)])]
             (set! __hash h)
             h)))

       IEquiv
       (-equiv [this other]
         (and (instance? Dataset other)
              (= nrows (.-nrows ^Dataset other))
              (= col-order (.-col-order ^Dataset other))
              (every? true? (map #(= (get col-map %)
                                     (get (.-col-map ^Dataset other) %))
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
       (hasheq [this]
         (if __hash
           __hash
           (let [h (hash [col-order nrows (mapv #(hash (get col-map %)) col-order)])]
             (set! __hash h)
             h)))

       java.lang.Iterable
       (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

       java.lang.Object
       (equals [this other]
         (and (instance? Dataset other)
              (= nrows (.-nrows ^Dataset other))
              (= col-order (.-col-order ^Dataset other))
              (every? true? (map #(= (get col-map %)
                                     (get (.-col-map ^Dataset other) %))
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
   All columns must have the same length."
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
    (Dataset. (into {} entries) col-order n nil nil)))

;;-----------------------------------------------------------------------------
;; Core accessors
;;-----------------------------------------------------------------------------

(defn column
  "Get a column by name. Returns the EveArray directly (zero-copy)."
  [^Dataset ds k]
  (get (.-col-map ds) k))

(defn column-names
  "Return vector of column name keywords in order."
  [^Dataset ds]
  (.-col-order ds))

(defn row-count
  "Return the number of rows."
  [^Dataset ds]
  (.-nrows ds))

(defn dtypes
  "Return {col-name → type-keyword} for all columns."
  [^Dataset ds]
  (into {} (map (fn [k]
                  (let [col (column ds k)]
                    [k (arr/subtype->type-kw
                        #?(:cljs (.-subtype-code col)
                           :clj  (.-subtype-code col)))]))
                (column-names ds))))

;;-----------------------------------------------------------------------------
;; Structural ops (new Dataset, structural sharing on unchanged columns)
;;-----------------------------------------------------------------------------

(defn select-columns
  "Return new dataset with only the specified columns."
  [^Dataset ds col-keys]
  (let [cm (.-col-map ds)
        new-map (into {} (map (fn [k] [k (get cm k)])) col-keys)]
    (Dataset. new-map (vec col-keys) (.-nrows ds) nil nil)))

(defn add-column
  "Return new dataset with an additional column."
  [^Dataset ds k col]
  (when (not= (count col) (.-nrows ds))
    (throw (#?(:cljs js/Error. :clj IllegalArgumentException.)
            (str "Column length " (count col) " does not match dataset row count " (.-nrows ds)))))
  (let [new-map (assoc (.-col-map ds) k col)
        new-order (if (some #{k} (.-col-order ds))
                    (.-col-order ds)
                    (conj (.-col-order ds) k))]
    (Dataset. new-map new-order (.-nrows ds) nil nil)))

(defn drop-column
  "Return new dataset without the specified column."
  [^Dataset ds k]
  (let [new-map (dissoc (.-col-map ds) k)
        new-order (vec (remove #{k} (.-col-order ds)))]
    (Dataset. new-map new-order (.-nrows ds) nil nil)))

(defn rename-columns
  "Return new dataset with columns renamed per rename-map {old-key → new-key}."
  [^Dataset ds rename-map]
  (let [new-order (mapv (fn [k] (get rename-map k k)) (.-col-order ds))
        new-map (into {} (map (fn [k]
                                [(get rename-map k k) (get (.-col-map ds) k)])
                              (.-col-order ds)))]
    (Dataset. new-map new-order (.-nrows ds) nil nil)))

;;-----------------------------------------------------------------------------
;; Row operations (new Dataset with new EveArray blocks)
;;-----------------------------------------------------------------------------

(defn reindex
  "Return new dataset with rows reordered by an :int32 index array."
  [^Dataset ds idx-arr]
  (let [new-map (into {} (map (fn [k]
                                [k (argops/take-indices (column ds k) idx-arr)])
                              (.-col-order ds)))]
    (Dataset. new-map (.-col-order ds) (count idx-arr) nil nil)))

(defn filter-rows
  "Return new dataset with only rows where (pred (nth col i)) is truthy."
  [^Dataset ds col-key pred]
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
  [^Dataset ds n]
  (let [n (min n (.-nrows ds))
        idx (arr/eve-array :int32 (vec (range n)))]
    (reindex ds idx)))

(defn tail
  "Return last n rows."
  [^Dataset ds n]
  (let [total (.-nrows ds)
        n (min n total)
        start (- total n)
        idx (arr/eve-array :int32 (vec (range start total)))]
    (reindex ds idx)))

(defn slice
  "Return rows [start, end). Zero-copy if possible, otherwise copies."
  [^Dataset ds start end]
  (let [end (min end (.-nrows ds))
        n (- end start)
        idx (arr/eve-array :int32 (vec (range start end)))]
    (reindex ds idx)))
