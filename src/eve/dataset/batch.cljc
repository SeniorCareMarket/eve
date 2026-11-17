(ns eve.dataset.batch
  "Dataset <-> batch: the bridge between Eve datasets and the packed
   form's columnar batches (lane H6 of the flat-Eve spike).

   A Dataset (eve.dataset) holds named EveArray columns in a slab
   domain. A batch (eve.flat.column, FORMAT-V1 §5) is the same data
   as bytes outside every domain: one segment per column, then the
   BATCH node naming them. This namespace converts between the two:

     (dataset->batch ds)        Dataset -> descriptor
     (values->batch cols opts)  {name values} -> descriptor
     (batch->values desc)       descriptor -> {name [values ...]}
     (batch->dataset desc)      descriptor -> Dataset
     (open-batch desc)          descriptor -> eve.flat.column batch

   A descriptor is {:bytes :node-off :ctx :names :dtypes :n :wide?}:
   the bytes and the offset of their BATCH node, the read ctx
   eve.flat.column/open-batch consumes, and the column metadata the
   bytes alone do not carry (names in order, per-column dtype). The
   conversion is a copy per column: each column's values are read once
   and encoded once into its segment, in the dense kind of the
   column's own dtype for Dataset columns — no row-wise transpose, no
   intermediate row maps.

   Column types beyond the EveArray subtypes travel at the value
   level (values->batch): strings (kind 19), keywords and symbols
   (symbol kinds 17/18 over a batch-level dictionary), booleans
   (kind 16), nulls (the validity bitmap; an all-null column is kind
   16 with every row absent), int64 (kind 10 when the dtype is
   stated), and instants (kind 10 holding epoch millis; the dtype
   :instant in the descriptor is what batch->values inverts). A column
   of anything else — maps, sets, ratios, big numbers — is refused by
   name: it needs chunk nodes, not a column.

   The batch-level dictionary is built per batch from that batch's own
   keyword/symbol values, so a descriptor is self-contained; Columns'
   flagged limitation stands untouched — symbol codes past 65,536 in
   one chunk (kind 18's u16 codes) are not guarded here either.

   JVM in this wave: eve.flat.column's reader/writer throws on
   ClojureScript until lane H4, and so does this bridge through it."
  (:require [eve.array :as arr]
            [eve.dataset :as ds]
            [eve.deftype-proto.data :as d]
            [eve.flat.column :as col])
  #?(:clj (:import [java.time Instant])))

;; ---------------------------------------------------------------------------
;; dtypes and kinds
;; ---------------------------------------------------------------------------

(def ^:private dtype->kind
  "The dense column kind (FORMAT-V1 §5) of a stated dtype: Eve's typed
   array subtypes 1-9 in order, then int64 and uint64."
  {:uint8 1 :int8 2 :uint8-clamped 3 :int16 4 :uint16 5
   :int32 6 :uint32 7 :float32 8 :float64 9 :int64 10 :uint64 11})

(defn- plain-integer? [x]
  #?(:clj (and (integer? x) (not (instance? clojure.lang.BigInt x))
               (not (instance? java.math.BigInteger x))
               (not (instance? java.math.BigDecimal x)))
     :cljs (and (number? x) (== x (Math/floor x)))))

(defn- instant? [x]
  #?(:clj (instance? Instant x) :cljs (instance? js/Date x)))

(defn- infer-dtype
  "The dtype of a column of plain values, from its present (non-nil)
   values; ::unsupported when no column kind holds them."
  [values]
  (let [present (filterv some? values)]
    (cond
      (empty? present) :null
      (every? instant? present) :instant
      (every? keyword? present) :keyword
      (every? symbol? present) :symbol
      (every? string? present) :string
      (every? boolean? present) :boolean
      (every? plain-integer? present) :int64
      #?@(:clj [(every? (fn [v] (instance? Float v)) present) :float32])
      (every? number? present) :float64
      :else ::unsupported)))

(defn- build-symtab
  "The batch's symbol dictionary over its keyword/symbol column values:
   {:syms [v ...] :code {v code}}, codes by first appearance, columns in
   name order, values in row order."
  [names col-map]
  (reduce (fn [acc k]
            (reduce (fn [acc v]
                      (if (and (or (keyword? v) (symbol? v))
                               (not (contains? (:code acc) v)))
                        (let [c (count (:syms acc))]
                          (-> acc
                              (update :syms conj v)
                              (assoc-in [:code v] c)))
                        acc))
                    acc
                    (get col-map k)))
          {:syms [] :code {}}
          names))

(defn- prepare-column
  "One column for the encoder: {:values :field-opts :dtype}. Stated
   dtypes force their kind; an inferred int64 leaves the choice to
   Columns' planner (narrowed / i32 / i64 are all value-exact)."
  [k values dtype code]
  (let [values (vec values)
        stated? (some? dtype)
        dtype (or dtype (infer-dtype values))]
    (when (= ::unsupported dtype)
      (throw (ex-info "eve.dataset.batch: a column no column kind holds"
                      {:eve.dataset.batch/check :unsupported-column
                       :column k
                       :sample (first (filterv some? values))})))
    (case dtype
      (:keyword :symbol)
      {:dtype dtype :values values
       :field-opts {:sym-code (fn [v] (get code v))}}

      :instant
      {:dtype dtype
       :values (mapv (fn [v] (when (some? v)
                                #?(:clj (.toEpochMilli ^Instant v)
                                   :cljs (.getTime v))))
                     values)
       :field-opts {:kind 10}}

      :null
      {:dtype dtype :values values :field-opts {:kind 16}}

      (:string :boolean)
      {:dtype dtype :values values :field-opts {}}

      ;; a stated dtype with a dense kind forces it; an inferred one
      ;; (int64 only — float kinds are the planner's own answer) plans
      {:dtype dtype :values values
       :field-opts (if (and stated? (dtype->kind dtype))
                     {:kind (dtype->kind dtype)}
                     {})})))

;; ---------------------------------------------------------------------------
;; encoding: values -> a batch descriptor
;; ---------------------------------------------------------------------------

(defn values->batch
  "Encode {name [values ...]} as one batch. Options: :names (the
   column order; default the map's key order), :dtypes ({name dtype}
   for the columns whose dtype is stated — a Dataset's, or a caller's
   contract; the rest are inferred from their values).

   Answers the descriptor {:bytes :node-off :ctx :names :dtypes :n
   :wide?}. The ctx is self-contained: the batch's own symbol
   dictionary and its one shape (index 0, the column order)."
  ([col-map] (values->batch col-map {}))
  ([col-map {:keys [names dtypes]}]
   (let [names (vec (or names (keys col-map)))
         n (count (get col-map (first names)))]
     (doseq [k names]
       (when-not (== n (count (get col-map k)))
         (throw (ex-info "eve.dataset.batch: columns of unequal length"
                         {:eve.dataset.batch/check :ragged-columns
                          :column k :expected n
                          :actual (count (get col-map k))}))))
     (let [{:keys [syms code]} (build-symtab names col-map)
           prepared (mapv (fn [k]
                            (prepare-column k (get col-map k)
                                            (get dtypes k) code))
                          names)
           cols (mapv :values prepared)
           field-opts (zipmap names (mapv :field-opts prepared))
           encode (fn [wide?]
                    (col/encode-batch names cols
                                      {:shape 0 :wide? wide?
                                       :field-opts field-opts}))
           [enc wide?] (try [(encode false) false]
                            (catch #?(:clj clojure.lang.ExceptionInfo
                                      :cljs :default) ex
                              (if (= :offset-overflow
                                     (:eve.flat.column/check (ex-data ex)))
                                [(encode true) true]
                                (throw ex))))
           bytes (:bytes enc)
           ctx {:bytes bytes
                :sym (fn [c] (nth syms c))
                :code code
                :shape (fn [i] (when (== 0 i) names))
                :wide? wide?}]
       {:bytes bytes
        :node-off (:node-off enc)
        :ctx ctx
        :names names
        :dtypes (zipmap names (mapv :dtype prepared))
        :n n
        :wide? wide?}))))

(defn dataset->batch
  "A Dataset as a batch descriptor: each column's plain values read
   once (eve->clj over the EveArray) and encoded once, in the dense
   kind of the column's own dtype. The Dataset is not modified; the
   batch shares no storage with it."
  [dataset]
  (let [names (ds/column-names dataset)
        col-map (into {}
                      (map (fn [k]
                             [k (vec (d/eve->clj (ds/column dataset k)))]))
                      names)]
    (values->batch col-map {:names names :dtypes (ds/dtypes dataset)})))

;; ---------------------------------------------------------------------------
;; reading back
;; ---------------------------------------------------------------------------

(defn open-batch
  "The descriptor's batch, opened with Columns' reader: a handle of
   column handles over the descriptor's bytes, read in place."
  [{:keys [bytes node-off ctx]}]
  (col/open-batch bytes node-off ctx))

(defn batch->values
  "The descriptor's columns as plain values, {name [values ...]} —
   the exact inverse of values->batch: symbol columns answer their
   keywords/symbols through the descriptor's dictionary, :instant
   columns answer Instants, nulls answer nil."
  [{:keys [dtypes names] :as desc}]
  (let [batch (open-batch desc)]
    (into {}
          (map-indexed
           (fn [i k]
             [k (let [vals (col/col-values (nth (:cols batch) i))]
                  (if (= :instant (get dtypes k))
                    (mapv (fn [v]
                            (when (some? v)
                              #?(:clj (Instant/ofEpochMilli (long v))
                                 :cljs (js/Date. (long v)))))
                          vals)
                    vals))]))
          names)))

(defn batch->dataset
  "The descriptor as a Dataset, equal to the one dataset->batch was
   given: one EveArray per column in the descriptor's dtype, built by
   eve.dataset/dataset — so it allocates where that allocates (inside
   a swap!, the atom's domain; outside, the process domain), exactly
   as a hand-built Dataset does."
  [{:keys [dtypes names] :as desc}]
  (let [vals (batch->values desc)]
    (ds/dataset
     (into {}
           (map (fn [k] [k (arr/eve-array (get dtypes k) (get vals k))]))
           names))))
