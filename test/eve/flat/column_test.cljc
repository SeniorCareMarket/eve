(ns eve.flat.column-test
  "Lane C2 (IMPL-PLAN §3.3): columns and batches, FORMAT-V1.md §5.

   C2.1 column-roundtrip            C2.4 rows-and-columns-same-bytes
   C2.2 column-ops-equal-host       C2.5 column-zero-copy-handoff
   C2.3 column-encodings-point-read

   Layout ground truth where §5 is silent is lane C1's chunk writer
   (write-column!/write-batch! in eve.flat.chunk): chunk-absolute
   offsets of the chunk's one width, children before parents, a struct
   column is [21][offset of its BATCH node]. The chunk-level pieces
   (symbol table, scalar nodes, shapes) come from
   eve.flat.column-scaffold — SCAFFOLDING that C1's chunk.cljc
   replaces; the code under test is eve.flat.column alone."
  (:require #?(:cljs ; the test-framework require; its referred macros are this file's test surface
               ;; some of this spec's referred vars are exercised only on the other reader pass or mirror the host-face declaration; the refer list is shared by both passes
               ;; the test-framework require (cljs.test on this pass); its referred macros are this file's test surface
               #_{:clj-kondo/ignore [:unused-namespace :unused-referred-var]}   [cljs.test :refer [deftest testing is]]
               :clj  [clojure.test :refer [deftest testing is]])
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.column :as column]
            #?(:clj [eve.flat.column-scaffold :as scaffold]))
  #?(:clj (:import #_{:clj-kondo/ignore [:unused-import]} ; Arrays is used below; the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use [java.util Arrays Random]
                   [java.nio DoubleBuffer IntBuffer ReadOnlyBufferException])))

#?(:clj
   (do
     ;; ---------------------------------------------------------------------
     ;; helpers
     ;; ---------------------------------------------------------------------

     (defn- roundtrip
       "Encode values as one column (opts), open it over its own bytes, read
        it back whole. Returns the read-back vector."
       ([values] (roundtrip values {}))
       ([values opts]
        (let [seg (column/encode-column values opts)
              c (column/open-column seg 0 (count values) (scaffold/ctx seg {}))]
          (column/col-values c))))

     (defn- records
       "The probe corpora's records shape (doc/flat/probes/corpora.clj,
        seed 7), local so the tests do not load research files."
       [n]
       (let [r (Random. 7)
             cities (mapv #(str "city-" %) (range 50))
             depts [:eng :ops :sales :legal :finance :design :support :research]]
         (mapv (fn [i]
                 {:id i
                  :name (str "user-" i)
                  :active (even? (.nextInt r 10))
                  :score (* 0.5 (.nextInt r 2000))
                  :dept (nth depts (.nextInt r (count depts)))
                  :city (nth cities (.nextInt r (count cities)))
                  :ts (+ 1700000000000 (* 1000 (long i)))})
               (range n))))

     (def ^:private record-ks [:id :name :active :score :dept :city :ts])

     (defn- batch-of
       "Encode rows as one BATCH node and open it. Returns [enc batch]."
       [rows ks]
       (let [symtab (scaffold/build-symtab rows)
             opts {:ks ks :shape 0
                   :sym-code (:code symtab) :nsym (:nsym symtab)}
             enc (column/encode-batch-rows rows opts)
             ctx (scaffold/ctx (:bytes enc) {:symtab symtab :shapes {0 ks}})]
         [enc (column/open-batch (:bytes enc) (:node-off enc) ctx)]))

     ;; ---------------------------------------------------------------------
     ;; C2.1 — column-roundtrip: BATCH and every column kind of §5
     ;; ---------------------------------------------------------------------

     (deftest column-roundtrip
       (testing "dense number kinds 1-11, explicit kind"
         (doseq [[kind values expected]
                 [[1 [0 1 127 128 255 7] [0 1 127 128 255 7]]
                  [2 [-128 -1 0 1 127] [-128 -1 0 1 127]]
                  [3 [0 5 250 255] [0 5 250 255]]
                  [4 [-32768 -1000 0 1000 32767] [-32768 -1000 0 1000 32767]]
                  [5 [0 1000 65535] [0 1000 65535]]
                  [6 [-2147483648 -7 0 42 2147483647] [-2147483648 -7 0 42 2147483647]]
                  [7 [0 42 3000000000 4294967295] [0 42 3000000000 4294967295]]
                  [8 [1.5 -2.25 0.0 100.0] [1.5 -2.25 0.0 100.0]]
                  [9 [1.5 -2.25 0.0 1e300] [1.5 -2.25 0.0 1e300]]
                  [10 [Long/MIN_VALUE -1 0 1 Long/MAX_VALUE]
                   [Long/MIN_VALUE -1 0 1 Long/MAX_VALUE]]
                  [11 [0 1 9223372036854775807] [0 1 9223372036854775807]]]]
           (is (= expected (roundtrip values {:kind kind}))
               (str "kind " kind))))
       (testing "kind 16, bits (odd row count: a partial last byte)"
         (is (= [true false true true false false true]
                (roundtrip [true false true true false false true] {:kind 16}))))
       (testing "kinds 17/18, symbol codes at both widths"
         (let [values [:eng :ops :eng :legal :ops]
               symtab (scaffold/build-symtab values)
               opts {:sym-code (:code symtab) :nsym (:nsym symtab)}
               read-back (fn [o]
                           (let [seg (column/encode-column values o)]
                             (column/col-values
                              (column/open-column seg 0 (count values)
                                                  (scaffold/ctx seg {:symtab symtab})))))]
           (is (= values (read-back (assoc opts :kind 17))))
           (is (= values (read-back (assoc opts :kind 18))))
           (is (= values (read-back opts)) "inferred symbol column")))
       (testing "kind 19, utf-8 (empty, non-ASCII, unique strings stay out of the table)"
         (is (= ["alpha" "" "βγ delta" "user-123"]
                (roundtrip ["alpha" "" "βγ delta" "user-123"] {:kind 19}))))
       (testing "kind 20, any: node offsets resolved through the ctx"
         (let [values [42 "hello" :kw 3.5 true]
               nodes (java.io.ByteArrayOutputStream.)
               offsets (reduce (fn [m v]
                                 (let [off (.size nodes)
                                       ^bytes b (scaffold/scalar-body v)]
                                   (.write nodes b 0 (alength b))
                                   (assoc m v off)))
                               {} values)
               node-bytes (.toByteArray nodes)
               seg (column/encode-column (conj values nil)
                                         {:kind 20 :node-off offsets
                                          :at (alength node-bytes)})
               ba (byte-array (+ (alength node-bytes) (alength seg)))]
           (System/arraycopy node-bytes 0 ba 0 (alength node-bytes))
           (System/arraycopy seg 0 ba (alength node-bytes) (alength seg))
           (let [c (column/open-column ba (alength node-bytes) 6
                                       (scaffold/ctx ba {}))]
             (is (= [42 "hello" :kw 3.5 true nil] (column/col-values c))))))
       (testing "kind 21, struct: [21][offset of a BATCH node]"
         (let [rows [{:a 1 :b "x"} {:a 2 :b "y"} {:a 3 :b "x"}]
               symtab (scaffold/build-symtab rows)
               opts {:ks [:a :b] :shape 0 :sym-code (:code symtab) :nsym (:nsym symtab)}
               inner (column/encode-batch-rows rows opts)
               seg (column/encode-column rows {:kind 21 :batch-off (:node-off inner)
                                               :at (alength (:bytes inner))})
               ba (byte-array (+ (alength (:bytes inner)) (alength seg)))]
           (System/arraycopy (:bytes inner) 0 ba 0 (alength (:bytes inner)))
           (System/arraycopy seg 0 ba (alength (:bytes inner)) (alength seg))
           (let [ctx (scaffold/ctx ba {:symtab symtab :shapes {0 [:a :b]}})
                 c (column/open-column ba (alength (:bytes inner)) 3 ctx)]
             (is (= rows (column/col-values c))))))
       (testing "kind 22, narrowed, at widths 1/2/4 and a negative base"
         (doseq [[base width values]
                 [[536870913 1 (mapv #(+ 536870913 %) [0 5 100 255 7 0])]
                  [1000 2 (mapv #(+ 1000 %) [0 1 4096 65535])]
                  [-100 2 (mapv #(+ -100 %) [0 100 60000])]
                  [0 4 [0 1 4294967295 7]]]]
           (is (= values (roundtrip values {:kind 22 :base base :width width}))
               (str "base " base " width " width))))
       (testing "kind 23, run-end, over an integer and a symbol values column"
         (is (= [1 1 1 2 2 3 3 3 3] (roundtrip [1 1 1 2 2 3 3 3 3] {:kind 23})))
         (let [values [:a :a nil nil :b :b :b]
               symtab (scaffold/build-symtab values)
               seg (column/encode-column values {:kind 23
                                                 :sym-code (:code symtab)
                                                 :nsym (:nsym symtab)})
               c (column/open-column seg 0 (count values)
                                     (scaffold/ctx seg {:symtab symtab}))]
           (is (= values (column/col-values c)) "nil is a run value")))
       (testing "nilable columns: the validity bitmap round-trips"
         (is (= [1 nil 3 nil 5] (roundtrip [1 nil 3 nil 5] {:kind 6})))
         (is (= ["a" nil "c"] (roundtrip ["a" nil "c"] {:kind 19})))
         (let [values [:eng nil :ops]
               symtab (scaffold/build-symtab values)
               seg (column/encode-column values {:sym-code (:code symtab)
                                                 :nsym (:nsym symtab)})]
           (is (= values (column/col-values
                          (column/open-column seg 0 3
                                              (scaffold/ctx seg {:symtab symtab}))))))
         (is (= [10 nil 30] (roundtrip [10 nil 30] {:kind 22 :base 0 :width 1}))))
       (testing "inferred kinds: booleans, ints, doubles"
         (is (= [true false true] (roundtrip [true false true])))
         (is (= [1 2 3] (roundtrip [1 2 3])))
         (is (= [1.5 2.5] (roundtrip [1.5 2.5]))))
       (testing "a batch of records: whole-batch and per-column round-trip"
         (let [rows (records 64)
               [_ bt] (batch-of rows record-ks)]
           (is (= rows (column/batch-values bt)))
           (doseq [[ci k] (map-indexed vector record-ks)]
             (is (= (mapv k rows) (column/col-values (nth (:cols bt) ci)))
                 (str "column " k)))))
       (testing "a WIDE batch: u32 column offsets"
         (let [rows (records 64)
               symtab (scaffold/build-symtab rows)
               opts {:ks record-ks :shape 0 :wide? true
                     :sym-code (:code symtab) :nsym (:nsym symtab)}
               enc (column/encode-batch-rows rows opts)
               ctx (scaffold/ctx (:bytes enc) {:symtab symtab
                                               :shapes {0 record-ks}
                                               :wide? true})]
           (is (= rows (column/batch-values
                        (column/open-batch (:bytes enc) (:node-off enc) ctx)))))))

     ;; ---------------------------------------------------------------------
     ;; C2.2 — column-ops-equal-host
     ;; ---------------------------------------------------------------------

     (deftest column-ops-equal-host
       (let [rows (records 2000)
             [_ bt] (batch-of rows record-ks)
             score-col (nth (:cols bt) 3)
             dept-col (nth (:cols bt) 4)
             r (Random. 99)]
         (testing "a scan computed in place equals the host's fold"
           (is (= (transduce (map :score) + 0.0 rows) (column/scan-sum score-col))))
         (testing "a filter count computed in place equals the host's count"
           (is (= (count (filter #(= :eng (:dept %)) rows))
                  (column/count-eq dept-col :eng)))
           (is (= (vec (keep-indexed (fn [i row] (when (= :eng (:dept row)) i)) rows))
                  (column/indices-eq dept-col :eng))))
         (testing "one cell, read in place, equals the host's get"
           (dotimes [_ 200]
             (let [i (.nextInt r 2000) ci (.nextInt r (count record-ks))
                   k (nth record-ks ci)]
               (is (= (get (nth rows i) k) (column/batch-cell bt ci i))
                   (str "cell " i " " k)))))
         (testing "a row view assembled in place equals the host's row"
           (dotimes [_ 200]
             (let [i (.nextInt r 2000)]
               (is (= (nth rows i) (column/batch-row bt i)) (str "row " i)))))))

     ;; ---------------------------------------------------------------------
     ;; C2.3 — column-encodings-point-read
     ;; ---------------------------------------------------------------------

     (deftest column-encodings-point-read
       (let [r (Random. 5)
             n 30000]
         (testing "narrowed: random indexes read base + load"
           (doseq [[base width pattern]
                   [[536870913 1 (mapv #(mod % 100) (range n))]
                    [7 2 (mapv #(mod (* 13 %) 60000) (range n))]
                    [-50 4 (mapv #(mod (* 7919 %) 100000) (range n))]]]
             (let [values (mapv #(+ base %) pattern)
                   seg (column/encode-column values {:kind 22 :base base :width width})
                   c (column/open-column seg 0 n (scaffold/ctx seg {}))]
               (dotimes [_ 2000]
                 (let [i (.nextInt r n)]
                   (is (= (nth values i) (column/col-ref c i))
                       (str "narrowed base " base " width " width " at " i)))))))
         (testing "run-end: random indexes read by binary search over the ends"
           (let [values (vec (mapcat (fn [e] (repeat (inc (mod e 7)) e)) (range 1 5000)))
                 seg (column/encode-column values {:kind 23})
                 c (column/open-column seg 0 (count values) (scaffold/ctx seg {}))]
             (dotimes [_ 2000]
               (let [i (.nextInt r (count values))]
                 (is (= (nth values i) (column/col-ref c i)) (str "run-end at " i)))))
           (let [values (mapv :dept (sort-by :dept (records 3000)))
                 symtab (scaffold/build-symtab values)
                 seg (column/encode-column values {:kind 23
                                                   :sym-code (:code symtab)
                                                   :nsym (:nsym symtab)})
                 c (column/open-column seg 0 (count values)
                                       (scaffold/ctx seg {:symtab symtab}))]
             (dotimes [_ 1000]
               (let [i (.nextInt r (count values))]
                 (is (= (nth values i) (column/col-ref c i))
                     (str "run-end symbol at " i))))))))

     ;; ---------------------------------------------------------------------
     ;; C2.4 — rows-and-columns-same-bytes
     ;; ---------------------------------------------------------------------

     (deftest rows-and-columns-same-bytes
       (let [rows (records 1000)
             symtab (scaffold/build-symtab rows)
             base-opts {:shape 0 :sym-code (:code symtab) :nsym (:nsym symtab)}
             from-rows (column/encode-batch-rows rows (assoc base-opts :ks record-ks))
             cols (mapv (fn [k] (mapv k rows)) record-ks)
             from-cols (column/encode-batch record-ks cols base-opts)]
         (is (Arrays/equals ^bytes (:bytes from-rows) ^bytes (:bytes from-cols))
             "a vector of maps and a map of vectors pack to the same bytes")
         (is (= (:node-off from-rows) (:node-off from-cols)))
         ;; and the shared bytes are the right value, not the same wrong value
         (let [ctx (scaffold/ctx (:bytes from-cols) {:symtab symtab
                                                     :shapes {0 record-ks}})]
           (is (= rows (column/batch-values
                        (column/open-batch (:bytes from-cols) (:node-off from-cols)
                                           ctx)))))))

     ;; ---------------------------------------------------------------------
     ;; C2.5 — column-zero-copy-handoff
     ;; ---------------------------------------------------------------------

     (deftest column-zero-copy-handoff
       (testing "an f64 column is handed over as a read-only DoubleBuffer over the same array"
         (let [values [1.5 -2.25 3.25 4.5]
               seg (column/encode-column values {:kind 9})
               c (column/open-column seg 0 4 (scaffold/ctx seg {}))
               ^DoubleBuffer view (column/typed-array-view c)]
           (is (.isReadOnly view))
           (is (= 4 (.remaining view)))
           (doseq [i (range 4)] (is (= (nth values i) (.get view (int i)))))
           (testing "no copy: writing the column's own bytes moves the view"
             (let [doff (:data-off c)]
               (aset seg (int doff) (byte 0))
               (aset seg (int (+ doff 1)) (byte 0))
               (aset seg (int (+ doff 6)) (unchecked-byte 0xF0))
               (aset seg (int (+ doff 7)) (unchecked-byte 0x3F))
               (is (= 1.0 (.get view 0)))))
           (is (thrown? ReadOnlyBufferException (.put view 0 9.9)))))
       (testing "an i32 column is handed over as a read-only IntBuffer"
         (let [values [10 -20 30]
               seg (column/encode-column values {:kind 6})
               c (column/open-column seg 0 3 (scaffold/ctx seg {}))
               ^IntBuffer view (column/typed-array-view c)]
           (is (.isReadOnly view))
           (doseq [i (range 3)] (is (= (nth values i) (.get view (int i)))))))
       (testing "a non-dense column refuses the handoff by name"
         (let [seg (column/encode-column ["a" "b"] {:kind 19})
               c (column/open-column seg 0 2 (scaffold/ctx seg {}))]
           (is (thrown? clojure.lang.ExceptionInfo (column/typed-array-view c))))))))
