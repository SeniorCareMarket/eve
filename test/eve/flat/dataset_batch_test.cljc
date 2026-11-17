(ns eve.flat.dataset-batch-test
  "Lane H6 — Dataset <-> batch. The three requirement tests of
   doc/flat/IMPL-PLAN.md §3.4:

     H6.1 dataset-batch-roundtrip — a Dataset detaches into a batch as
          one copy per column (each column in the dense kind of its own
          dtype) and reads back an equal Dataset; the Columns lane's
          readers (eve.flat.column) answer over the batch in place.
     H6.2 columnar-pipelines — test/eve/columnar_bench.cljc's five
          pipelines, run over a batch against today's Dataset machinery,
          one session, answers equal under the bench's own tolerance.
     H6.3 dataset-column-types — strings, keywords, booleans, nulls,
          int64 and instants as columns, round-tripped exactly."
  (:require [clojure.test :refer [deftest testing is]]
            [eve.alpha :as e]
            [eve.array :as arr]
            [eve.atom :as sa]
            [eve.dataset :as ds]
            [eve.dataset.batch :as db]
            [eve.dataset.argops :as argops]
            [eve.dataset.functional :as func]
            [eve.flat.column :as col]
            [eve.tensor :as tensor])
  (:import [java.time Instant]
           [java.util Random]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- in-atom
  "Run (f) inside an Eve atom's swap! and answer its value — the domain a
   Dataset and its arrays are constructed in (eve.dataset-test's pattern)."
  [f]
  (let [a (sa/atom nil)]
    (swap! a (fn [_] (f)))))

(defn- plain-column
  "A Dataset column as plain values, element by element."
  [dataset k]
  (let [a (ds/column dataset k)]
    (mapv (fn [i] (e/nth a i)) (range (e/count a)))))

(defn- doubles
  "n deterministic doubles in [0, scale) from r."
  [^Random r n scale]
  (mapv (fn [_] (* (.nextDouble r) scale)) (range n)))

(defn- same-result?
  "columnar_bench.cljc's rule: numbers within a relative 1e-9 (sums run
   in another order), maps key by key, anything else exact."
  [a b]
  (cond
    (and (number? a) (number? b))
    (let [d (Math/abs (double (- a b)))]
      (<= d (* 1e-9 (max 1.0 (Math/abs (double a)) (Math/abs (double b))))))

    (and (map? a) (map? b))
    (and (= (set (keys a)) (set (keys b)))
         (every? (fn [k] (same-result? (get a k) (get b k))) (keys a)))

    :else (= a b)))

;; ---------------------------------------------------------------------------
;; H6.1 — dataset <-> batch roundtrip, a copy per column
;; ---------------------------------------------------------------------------

(deftest dataset-batch-roundtrip
  (testing "a Dataset packs to a batch column by column and reads back equal"
    (let [r (Random. 1234)
          n 500
          price (doubles r n 100.0)
          qty (mapv (fn [_] (long (.nextInt r 1000))) (range n))
          flag (mapv (fn [_] (long (.nextInt r 256))) (range n))
          score (mapv (fn [x] (float (* x 0.5))) price)
          big (mapv (fn [_] (long (.nextInt r 100000))) (range n))
          dataset (in-atom
                   (fn []
                     (ds/dataset {:price (arr/eve-array :float64 price)
                                  :qty (arr/eve-array :int32 qty)
                                  :flag (arr/eve-array :uint8 flag)
                                  :score (arr/eve-array :float32 score)
                                  :big (arr/eve-array :uint32 big)})))
          desc (db/dataset->batch dataset)
          batch (db/open-batch desc)]
      ;; the batch is the dataset's shape: same names, same row count
      (is (= (ds/column-names dataset) (:names desc)))
      (is (= (ds/column-names dataset) (:ks batch)))
      (is (= n (:n batch)))
      (is (= (ds/dtypes dataset) (:dtypes desc)))
      ;; one copy per column, in the dense kind of the column's own dtype:
      ;; float64 -> 9, int32 -> 6, uint8 -> 1, float32 -> 8, uint32 -> 7
      (is (= [9 6 1 8 7] (mapv :kind (:cols batch))))
      ;; the Columns lane's readers answer over the batch in place,
      ;; cell for cell the dataset's own values
      (doseq [i (range (count (:names desc)))]
        (let [k (nth (:names desc) i)]
          (is (= (plain-column dataset k)
                 (col/col-values (nth (:cols batch) i)))
              (str "column " k " reads back through eve.flat.column"))))
      (is (= {:price (first price)} (select-keys (col/batch-row batch 0) [:price]))
          "a row view assembles from the columns in place")
      ;; and the batch inverts to an equal Dataset
      (let [back (in-atom (fn [] (db/batch->dataset desc)))]
        (is (= dataset back))
        (is (= (ds/column-names dataset) (ds/column-names back)))
        (is (= (ds/dtypes dataset) (ds/dtypes back)))
        (is (= n (ds/row-count back)))))))

;; ---------------------------------------------------------------------------
;; H6.3 — strings, keywords, booleans, nulls, int64 and instants as columns
;; ---------------------------------------------------------------------------

(deftest dataset-column-types
  (testing "value-typed columns round-trip exactly through a batch"
    (let [cols {:s ["alpha" "beta" nil "gamma" "alpha"]
                :k [:eng :ops :eng nil :eng]
                :b [true false nil true false]
                :z [nil nil nil nil nil]
                :i [5000000000 -9000000000 42 nil 7]
                :t [(Instant/ofEpochMilli 1700000000000) nil
                    (Instant/ofEpochMilli 0)
                    (Instant/ofEpochMilli -5000)
                    (Instant/ofEpochMilli 1700000000000)]}
          dtypes {:s :string :k :keyword :b :boolean
                  :z :null :i :int64 :t :instant}
          desc (db/values->batch cols {:dtypes dtypes})
          batch (db/open-batch desc)
          kinds (zipmap (:names desc) (map :kind (:cols batch)))]
      (is (= [:s :k :b :z :i :t] (:names desc)))
      (is (= 5 (:n batch)))
      ;; each type took its column kind: utf-8 19, symbol 17/18, bits 16,
      ;; int64 10, instant-as-epoch-milli 10
      (is (= 19 (:s kinds)))
      (is (contains? #{17 18} (:k kinds)))
      (is (= 16 (:b kinds)))
      (is (= 10 (:i kinds)))
      (is (= 10 (:t kinds)))
      ;; the null column reads back as nils, through the column reader
      (is (= [nil nil nil nil nil]
             (col/col-values (nth (:cols batch) (.indexOf (:names desc) :z)))))
      ;; and the whole map inverts exactly, instants as instants
      (is (= cols (db/batch->values desc))))))

;; ---------------------------------------------------------------------------
;; H6.2 — the columnar bench's five pipelines, over a batch vs today's Dataset
;; ---------------------------------------------------------------------------

(defn- batch-col
  "Column handle ci of an opened batch."
  [batch ci]
  (nth (:cols batch) ci))

(defn- run-in-atom
  "One swap! on a fresh Eve atom: (f) builds its arrays/dataset in the
   atom's domain and answers a map holding :result — columnar_bench's
   shape for its Eve arm."
  [f]
  (let [a (sa/atom nil)]
    (:result (swap! a (fn [_] (f))))))

(deftest columnar-pipelines
  (let [r (Random. 42)
        n 2000
        prices (doubles r n 100.0)
        qtys (doubles r n 50.0)
        costs (doubles r n 2000.0)
        big-prices (doubles r n 1000.0)
        pipe-prices (doubles r n 100.0)
        pipe-qtys (doubles r n 20.0)
        cats (mapv (fn [_] (long (.nextInt r 10))) (range n))
        ids (mapv long (range n))
        side 45
        cells (* side side)
        tdata (doubles r cells 100.0)]

    (testing "1. column arithmetic: revenue = price*qty, margin, sum(margin)"
      (let [today (run-in-atom
                   (fn []
                     (let [s {:price (arr/eve-array :float64 prices)
                              :qty (arr/eve-array :float64 qtys)
                              :cost (arr/eve-array :float64 costs)}
                           revenue (func/mul (:price s) (:qty s))
                           margin (func/sub revenue (:cost s))]
                       {:result (func/sum margin)})))
            desc (db/values->batch {:price prices :qty qtys :cost costs}
                                   {:dtypes {:price :float64 :qty :float64
                                             :cost :float64}})
            batch (db/open-batch desc)
            margins (mapv (fn [i]
                            (- (* (col/col-ref (batch-col batch 0) i)
                                  (col/col-ref (batch-col batch 1) i))
                               (col/col-ref (batch-col batch 2) i)))
                          (range n))
            mdesc (db/values->batch {:margin margins} {:dtypes {:margin :float64}})
            over-batch (col/scan-sum (batch-col (db/open-batch mdesc) 0))]
        (is (same-result? today over-batch))))

    (testing "2. filter + aggregate: price > 50, then sum/mean/min/max"
      (let [today (run-in-atom
                   (fn []
                     (let [c (arr/eve-array :float64 prices)
                           idx (argops/argfilter c (fn [x] (> x 50.0)))
                           filt (argops/take-indices c idx)]
                       {:result {:sum (func/sum filt)
                                 :mean (func/mean filt)
                                 :min (func/min-val filt)
                                 :max (func/max-val filt)}})))
            desc (db/values->batch {:price prices} {:dtypes {:price :float64}})
            batch (db/open-batch desc)
            pc (batch-col batch 0)
            kept (filterv (fn [i] (> (col/col-ref pc i) 50.0)) (range n))
            vals (mapv (fn [i] (col/col-ref pc i)) kept)
            over-batch {:sum (reduce + 0.0 vals)
                        :mean (/ (reduce + 0.0 vals) (count vals))
                        :min (reduce min vals)
                        :max (reduce max vals)}]
        (is (same-result? today over-batch))))

    (testing "3. sort + top-n: argsort desc, top 100, mean"
      (let [today (run-in-atom
                   (fn []
                     (let [c (arr/eve-array :float64 big-prices)
                           idx (argops/argsort c :desc)
                           top (argops/take-indices
                                idx (arr/eve-array :int32 (vec (range 100))))
                           top-vals (argops/take-indices c top)]
                       {:result (func/mean top-vals)})))
            desc (db/values->batch {:price big-prices} {:dtypes {:price :float64}})
            batch (db/open-batch desc)
            pc (batch-col batch 0)
            order (vec (sort (fn [a b]
                               (compare (col/col-ref pc b) (col/col-ref pc a)))
                             (range n)))
            top (subvec order 0 100)
            over-batch (/ (reduce + 0.0 (mapv (fn [i] (col/col-ref pc i)) top))
                          100.0)]
        (is (same-result? today over-batch))))

    (testing "4. dataset pipeline: derive, filter, sort, head, sum + mean"
      (let [dataset (in-atom
                     (fn [] (ds/dataset {:price (arr/eve-array :float64 pipe-prices)
                                         :qty (arr/eve-array :float64 pipe-qtys)
                                         :cat (arr/eve-array :int32 cats)
                                         :id (arr/eve-array :int32 ids)})))
            today (run-in-atom
                   (fn []
                     (let [d (ds/dataset {:price (arr/eve-array :float64 pipe-prices)
                                          :qty (arr/eve-array :float64 pipe-qtys)
                                          :cat (arr/eve-array :int32 cats)
                                          :id (arr/eve-array :int32 ids)})
                           rev (func/mul (ds/column d :price)
                                         (ds/column d :qty))
                           d2 (ds/add-column d :revenue rev)
                           d3 (ds/filter-rows d2 :revenue
                                              (fn [x] (> x 500.0)))
                           d4 (ds/sort-by-column d3 :revenue :desc)
                           d5 (ds/head d4 (min 200 (ds/row-count d4)))]
                       {:result {:rev-sum (func/sum (ds/column d5 :revenue))
                                 :price-mean (func/mean (ds/column d5 :price))}})))
            desc (db/dataset->batch dataset)
            batch (db/open-batch desc)
            names (:names desc)
            pc (batch-col batch (.indexOf names :price))
            qc (batch-col batch (.indexOf names :qty))
            rev (mapv (fn [i] (* (col/col-ref pc i) (col/col-ref qc i)))
                      (range n))
            kept (filterv (fn [i] (> (nth rev i) 500.0)) (range n))
            order (vec (sort (fn [a b] (compare (nth rev b) (nth rev a))) kept))
            top (subvec order 0 (min 200 (count order)))
            over-batch {:rev-sum (reduce + 0.0 (mapv (fn [i] (nth rev i)) top))
                        :price-mean (/ (reduce + 0.0 (mapv (fn [i] (col/col-ref pc i)) top))
                                       (count top))}]
        (is (same-result? today over-batch))))

    (testing "5. tensor pipeline: emap x2, transpose, flatten, sum"
      (let [today (run-in-atom
                   (fn []
                     (let [t (tensor/from-array (arr/eve-array :float64 tdata)
                                                [side side])
                           t2 (tensor/emap (fn [x] (* x 2.0)) t)
                           t3 (tensor/transpose t2)
                           flat (tensor/to-array t3)]
                       {:result (tensor/ereduce + 0.0
                                                (tensor/from-array
                                                 flat [(e/count flat)]))})))
            desc (db/values->batch {:data tdata} {:dtypes {:data :float64}})
            batch (db/open-batch desc)
            dc (batch-col batch 0)
            flat (mapv (fn [j]
                         (mapv (fn [i]
                                 (* 2.0 (col/col-ref dc (+ (* i side) j))))
                               (range side)))
                       (range side))
            fdesc (db/values->batch {:x (vec (apply concat flat))}
                                    {:dtypes {:x :float64}})
            over-batch (col/scan-sum (batch-col (db/open-batch fdesc) 0))]
        (is (same-result? today over-batch))))))
