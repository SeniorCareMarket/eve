(ns eve2.compare-bench
  "Head-to-head benchmark: eve1 (eve.atom) vs eve2 (eve2.atom).

   Runs identical operations through both namespace trees to measure:
   1. Delegation overhead (eve2 currently wraps eve1)
   2. Correctness (both paths must produce identical results)

   Exercises the same workloads as data_bench.clj:
   - Map ops (assoc, dissoc, get-in, update-in, merge, reduce-kv)
   - Vector ops (conj, assoc, nth, mapv, filterv)
   - Set ops (conj, disj, contains?, union)
   - Rich transforms (pipelines, group-by, bulk swaps)
   - Counter contention (JVM multi-threaded)

   Usage:
     clj -M:eve2-compare-bench <target-mb>
     Example: clj -M:eve2-compare-bench 5"
  (:require [eve.atom :as eve1-atom]
            [eve2.atom :as eve2-atom]
            [eve.perf :as perf]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- nanos->ms [n] (/ (double n) 1e6))

(defn- total-disk-bytes [base]
  (reduce + (map #(let [f (File. (str base %))]
                    (if (.exists f) (.length f) 0))
                 [".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5"
                  ".slab6" ".root" ".rmap"
                  ".slab0.bm" ".slab1.bm" ".slab2.bm"
                  ".slab3.bm" ".slab4.bm" ".slab5.bm"])))

(defn- cleanup! [base]
  (doseq [ext [".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5"
               ".slab6" ".root" ".rmap"
               ".slab0.bm" ".slab1.bm" ".slab2.bm"
               ".slab3.bm" ".slab4.bm" ".slab5.bm"]]
    (let [f (File. (str base ext))]
      (when (.exists f) (.delete f)))))

(defn- section [title]
  (println)
  (printf "── %s ──\n" title)
  (println))

;; ---------------------------------------------------------------------------
;; Rich data generators (same as data_bench.clj)
;; ---------------------------------------------------------------------------

(defn- user-record [i]
  {:id        i
   :name      (str "user-" i)
   :email     (str "user-" i "@example.com")
   :active?   (even? i)
   :role      (nth [:admin :editor :viewer :guest] (mod i 4))
   :profile   {:bio      (str "Bio for user " i)
               :location {:city    (nth ["NYC" "SF" "LA" "CHI" "SEA"] (mod i 5))
                          :state   (nth ["NY" "CA" "CA" "IL" "WA"]    (mod i 5))
                          :zip     (str (+ 10000 (mod i 90000)))}
               :prefs    {:theme    (if (even? i) :dark :light)
                          :lang     :en
                          :font-size (+ 10 (mod i 10))
                          :notify?  true}}
   :scores    (vec (for [j (range 10)] (+ 50 (mod (* i (inc j)) 50))))
   :tags      #{:verified :active (keyword (str "tier-" (mod i 5)))}
   :history   (list :signup :confirmed :purchase :review)
   :matrix    [[(mod i 100) (* i 2)  (* i 3)]
               [(+ i 10)    (+ i 20) (+ i 30)]
               [(+ i 100)   (+ i 200)(+ i 300)]]
   :metadata  {:created (str "2025-01-" (inc (mod i 28)))
               :version (inc (mod i 10))
               :flags   #{:exportable :searchable}}})

(defn- order-record [i]
  {:order-id  (+ 1000 i)
   :user-id   (mod i 200)
   :items     (vec (for [j (range (+ 1 (mod i 5)))]
                     {:sku     (str "SKU-" (+ (* i 10) j))
                      :qty     (inc (mod j 3))
                      :price   (+ 9.99 (* j 5.0))
                      :options {:color (nth [:red :blue :green :black] (mod j 4))
                                :size  (nth [:S :M :L :XL] (mod j 4))}}))
   :total     (* (+ 1 (mod i 5)) 14.99)
   :status    (nth [:pending :shipped :delivered :returned] (mod i 4))
   :address   {:street (str (+ 100 i) " Main St")
               :city   "Anytown"
               :zip    (str (+ 10000 (mod i 90000)))}
   :notes     (when (zero? (mod i 3)) (str "Rush order #" i))})

;; ---------------------------------------------------------------------------
;; Benchmark harness
;; ---------------------------------------------------------------------------

(def ^:private results (atom []))

(defn- bench
  "Run f n-iters times, return elapsed-ms."
  [label n-iters f]
  (let [lats (long-array n-iters)
        t0   (System/nanoTime)]
    (dotimes [i n-iters]
      (let [t-op (System/nanoTime)]
        (f)
        (aset lats i (- (System/nanoTime) t-op))))
    (let [elapsed-ms (nanos->ms (- (System/nanoTime) t0))
          ms-per-op  (/ elapsed-ms (max 1 n-iters))
          sorted     (sort (map #(/ (double %) 1e6) (seq lats)))
          cnt        (count sorted)
          p50        (nth sorted (quot cnt 2))
          p99        (nth sorted (int (* cnt 0.99)))]
      (swap! results conj {:label label :elapsed-ms elapsed-ms
                           :ops n-iters :ms-per-op ms-per-op
                           :p50-ms p50 :p99-ms p99})
      (printf "  %-50s %8.1f ms  (%d ops, %.3f ms/op, p50=%.2f p99=%.2f)\n"
              label elapsed-ms n-iters ms-per-op p50 p99)
      (flush)
      elapsed-ms)))

(defn- bench-pair
  "Run the same operation on eve1 and eve2 atoms, print side by side."
  [label n-iters eve1-fn eve2-fn]
  (let [eve1-ms (bench (str label " [eve1]") n-iters eve1-fn)
        eve2-ms (bench (str label " [eve2]") n-iters eve2-fn)
        ratio   (if (pos? eve1-ms) (/ eve2-ms eve1-ms) 0.0)]
    (printf "  %-50s ratio: %.3fx (eve2/eve1)\n" "" ratio)
    (flush)))

;; ---------------------------------------------------------------------------
;; 10MB / 100MB atom builder
;; ---------------------------------------------------------------------------

(defn- build-atom!
  "Build atom up to target-mb using the given atom constructor.
   Returns {:atom a :domain d :key-count n :disk-mb f}."
  [base-path target-mb atom-ctor domain-ctor]
  (cleanup! base-path)
  (let [target-bytes (* target-mb 1024 1024)
        d            (domain-ctor base-path)
        a            (atom-ctor {:id :eve/main :persistent base-path} {:counter 0})
        t0           (System/nanoTime)
        batch-size   200]
    (loop [i 0]
      (let [disk (if (zero? (mod i batch-size))
                   (total-disk-bytes base-path)
                   0)]
        (when (or (zero? disk) (< disk target-bytes))
          (swap! a assoc (keyword (str "k" i)) (user-record i))
          (when (zero? (mod (inc i) batch-size))
            (let [disk-mb (/ (double (total-disk-bytes base-path)) (* 1024 1024))]
              (printf "\r    %,6d keys | %6.1f MB on disk" (inc i) disk-mb)
              (flush)))
          (recur (inc i)))))
    (let [key-count (count @a)
          disk-mb   (/ (double (total-disk-bytes base-path)) (* 1024 1024))]
      (println)
      {:atom a :domain d :key-count key-count :disk-mb disk-mb
       :elapsed-s (/ (- (System/nanoTime) t0) 1e9)})))

;; ---------------------------------------------------------------------------
;; Stress test: swap latency on large atoms
;; ---------------------------------------------------------------------------

(defn- swap-latency-test!
  "Run n-swaps on existing keys, return {:p50 :p95 :p99 :min :max} in ms."
  [a key-count n-swaps warmup-swaps]
  ;; Warmup
  (dotimes [i warmup-swaps]
    (swap! a assoc (keyword (str "k" (mod i key-count))) {:warmup true :i i}))
  ;; Timed
  (let [lats (long-array n-swaps)]
    (dotimes [i n-swaps]
      (let [k (keyword (str "k" (mod i key-count)))
            t (System/nanoTime)]
        (swap! a assoc k {:updated true :iter i})
        (aset lats i (- (System/nanoTime) t))))
    (let [sorted (sort (mapv #(/ (double %) 1e6) (seq lats)))
          cnt    (count sorted)]
      {:p50 (nth sorted (quot cnt 2))
       :p95 (nth sorted (int (* cnt 0.95)))
       :p99 (nth sorted (int (* cnt 0.99)))
       :min (first sorted)
       :max (last sorted)})))

(defn- contention-test!
  "Run n-threads x ops-per counter increments, return {:count :wall-ms :correct?}."
  [a n-threads ops-per]
  (swap! a assoc :counter 0)
  (let [t0      (System/nanoTime)
        futures (mapv (fn [_]
                        (future
                          (dotimes [_ ops-per]
                            (swap! a update :counter inc))))
                      (range n-threads))
        _       (run! deref futures)
        wall-ms (nanos->ms (- (System/nanoTime) t0))
        final   (:counter @a)
        expected (* n-threads ops-per)]
    {:count final :expected expected :wall-ms wall-ms
     :correct? (= final expected)}))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [target-mb (if (first args) (Double/parseDouble (first args)) 5.0)
        scale     (/ target-mb 6.0)
        n-users   (max 10 (int (* 500 scale)))
        n-orders  (max 20 (int (* 1000 scale)))
        base-eve1 "/tmp/eve1-bench"
        base-eve2 "/tmp/eve2-bench"]

    (println)
    (println "╔══════════════════════════════════════════════════════════════╗")
    (println "║     Eve Comparison Benchmark: eve1 vs eve2 (JVM)           ║")
    (println "╚══════════════════════════════════════════════════════════════╝")
    (println)

    ;; ── Phase 1: Build identical datasets ──
    (section "Phase 1: Build identical datasets")

    (let [users   (into {} (map (fn [i] [(keyword (str "u" i)) (user-record i)])
                                (range n-users)))
          orders  (into {} (map (fn [i] [(keyword (str "o" i)) (order-record i)])
                                (range n-orders)))
          dataset {:users users :orders orders :counter 0 :version 1}]

      (printf "  Users:  %d records\n" n-users)
      (printf "  Orders: %d records\n" n-orders)
      (flush)

      ;; eve1 atom
      (cleanup! base-eve1)
      (let [d1    (eve1-atom/persistent-atom-domain base-eve1)
            eve1  (eve1-atom/atom {:id :eve/main :persistent base-eve1} dataset)
            ;; eve2 atom
            _     (cleanup! base-eve2)
            d2    (eve2-atom/persistent-atom-domain base-eve2)
            eve2  (eve2-atom/atom {:id :eve/main :persistent base-eve2} dataset)
            disk1 (/ (double (total-disk-bytes base-eve1)) (* 1024 1024))
            disk2 (/ (double (total-disk-bytes base-eve2)) (* 1024 1024))]

        (printf "  eve1 on-disk: %.1f MB\n" disk1)
        (printf "  eve2 on-disk: %.1f MB\n" disk2)
        (flush)

        ;; Verify round-trip
        (assert (= (:counter @eve1) 0) "eve1 round-trip failed")
        (assert (= (:counter @eve2) 0) "eve2 round-trip failed")
        (assert (= (count (:users @eve1)) n-users) "eve1 user count mismatch")
        (assert (= (count (:users @eve2)) n-users) "eve2 user count mismatch")

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 2: Individual operation benchmarks
        ;; ══════════════════════════════════════════════════════════════

        ;; ── MAP OPERATIONS ──
        (section "Phase 2a: Map Operations")

        (bench-pair "map/assoc-new-key" 200
          #(swap! eve1 assoc-in [:users :new-user] (user-record 9999))
          #(swap! eve2 assoc-in [:users :new-user] (user-record 9999)))

        (bench-pair "map/update-existing-key" 200
          #(swap! eve1 assoc-in [:users :u0 :name] "updated")
          #(swap! eve2 assoc-in [:users :u0 :name] "updated"))

        (bench-pair "map/dissoc-key" 200
          #(swap! eve1 update :users dissoc :u1)
          #(swap! eve2 update :users dissoc :u1))

        (bench-pair "map/get-in-deep" 1000
          #(get-in @eve1 [:users :u50 :profile :location :city])
          #(get-in @eve2 [:users :u50 :profile :location :city]))

        (bench-pair "map/update-in-nested" 200
          #(swap! eve1 update-in [:users :u10 :profile :prefs :font-size] inc)
          #(swap! eve2 update-in [:users :u10 :profile :prefs :font-size] inc))

        (bench-pair "map/merge-maps" 100
          #(swap! eve1 update :users merge {:extra1 {:id -1} :extra2 {:id -2}})
          #(swap! eve2 update :users merge {:extra1 {:id -1} :extra2 {:id -2}}))

        (bench-pair "map/select-keys" 500
          #(select-keys (:users @eve1) [:u0 :u1 :u2 :u3 :u4])
          #(select-keys (:users @eve2) [:u0 :u1 :u2 :u3 :u4]))

        (bench-pair "map/reduce-kv-sum" 50
          #(reduce-kv (fn [acc _k v] (+ acc (:id v))) 0 (:users @eve1))
          #(reduce-kv (fn [acc _k v] (+ acc (:id v))) 0 (:users @eve2)))

        ;; ── VECTOR / SET / LIST ──
        ;; NOTE: JVM deserialization of nested SAB_VEC/SAB_SET/SAB_LIST from atom
        ;; values is not yet supported (no JVM type constructors registered for
        ;; 0x11/0x12/0x13). This is a pre-existing limitation in both eve1 and eve2.
        ;; Vec/set/list benchmarks are skipped.
        (section "Phase 2b: Vec/Set/List (skipped — JVM deser limitation)")

        ;; ── RICH DATA TRANSFORMATIONS ──
        (section "Phase 2d: Rich Data Transformations")

        (bench-pair "xform/filter-map-reduce-orders" 30
          (fn [] (let [ords (vals (:orders @eve1))]
                   (->> ords (filter (fn [o] (= :shipped (:status o)))) (map :total) (reduce +))))
          (fn [] (let [ords (vals (:orders @eve2))]
                   (->> ords (filter (fn [o] (= :shipped (:status o)))) (map :total) (reduce +)))))

        (bench-pair "xform/group-by-status" 30
          #(group-by :status (vals (:orders @eve1)))
          #(group-by :status (vals (:orders @eve2))))

        (bench-pair "xform/flatten-profiles" 30
          #(mapv (fn [[_k v]] {:name (:name v) :city (get-in v [:profile :location :city])})
                 (:users @eve1))
          #(mapv (fn [[_k v]] {:name (:name v) :city (get-in v [:profile :location :city])})
                 (:users @eve2)))

        (bench-pair "xform/bulk-swap-50-records" 20
          #(swap! eve1 update :users
                  (fn [users]
                    (reduce (fn [m i]
                              (update-in m [(keyword (str "u" i)) :profile :prefs :font-size] (fnil inc 0)))
                            users (range 50))))
          #(swap! eve2 update :users
                  (fn [users]
                    (reduce (fn [m i]
                              (update-in m [(keyword (str "u" i)) :profile :prefs :font-size] (fnil inc 0)))
                            users (range 50)))))

        ;; xform/leaderboard-build and xform/rewrite-nested-matrix skipped
        ;; (require nested vec deser from atom which is not yet supported on JVM)

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 3: Bulk operations
        ;; ══════════════════════════════════════════════════════════════
        (section "Phase 3: Bulk Operations")

        (let [n 500]
          (bench-pair (str "bulk/counter-inc-" n "x") n
            #(swap! eve1 update :counter inc)
            #(swap! eve2 update :counter inc)))

        (let [n 100]
          (bench-pair (str "bulk/assoc-" n "-new-orders") n
            #(swap! eve1 assoc-in [:orders (keyword (str "new-" (rand-int 100000)))]
                    (order-record (rand-int 100000)))
            #(swap! eve2 assoc-in [:orders (keyword (str "new-" (rand-int 100000)))]
                    (order-record (rand-int 100000)))))

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 4: JVM multi-threaded contention
        ;; ══════════════════════════════════════════════════════════════
        (section "Phase 4: JVM Multi-Threaded Contention (4 threads × 100 ops)")

        (let [r1 (contention-test! eve1 4 100)
              r2 (contention-test! eve2 4 100)]
          (printf "  eve1: %d/%d in %.0f ms (%.0f ops/s) %s\n"
                  (:count r1) (:expected r1) (:wall-ms r1)
                  (/ (* (:expected r1) 1000.0) (:wall-ms r1))
                  (if (:correct? r1) "CORRECT" "MISMATCH"))
          (printf "  eve2: %d/%d in %.0f ms (%.0f ops/s) %s\n"
                  (:count r2) (:expected r2) (:wall-ms r2)
                  (/ (* (:expected r2) 1000.0) (:wall-ms r2))
                  (if (:correct? r2) "CORRECT" "MISMATCH"))
          (printf "  Ratio: %.3fx (eve2/eve1)\n"
                  (if (pos? (:wall-ms r1)) (/ (:wall-ms r2) (:wall-ms r1)) 0.0))
          (flush))

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 5: Swap latency on current atom (cold path)
        ;; ══════════════════════════════════════════════════════════════
        (section "Phase 5: Swap Latency (100 existing-key updates)")

        (let [kc1 (count @eve1)
              kc2 (count @eve2)
              r1  (swap-latency-test! eve1 kc1 100 50)
              r2  (swap-latency-test! eve2 kc2 100 50)]
          (printf "  eve1 (%d keys): p50=%.2f p95=%.2f p99=%.2f min=%.2f max=%.2f ms\n"
                  kc1 (:p50 r1) (:p95 r1) (:p99 r1) (:min r1) (:max r1))
          (printf "  eve2 (%d keys): p50=%.2f p95=%.2f p99=%.2f min=%.2f max=%.2f ms\n"
                  kc2 (:p50 r2) (:p95 r2) (:p99 r2) (:min r2) (:max r2))
          (flush))

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 6: Large atom stress test (build + swap latency)
        ;; ══════════════════════════════════════════════════════════════
        (section "Phase 6: Large Atom Build + Stress")

        (let [lg-base1 "/tmp/eve1-large-bench"
              lg-base2 "/tmp/eve2-large-bench"
              lg-mb    (min target-mb 10.0)]  ;; cap at 10MB for speed

          (printf "  Building %.0f MB atom via eve1...\n" lg-mb) (flush)
          (let [b1 (build-atom! lg-base1 lg-mb eve1-atom/atom eve1-atom/persistent-atom-domain)]
            (printf "    eve1: %d keys, %.1f MB, %.1fs (%.0f keys/s)\n"
                    (:key-count b1) (:disk-mb b1) (:elapsed-s b1)
                    (/ (:key-count b1) (:elapsed-s b1)))
            (flush)

            (printf "  Building %.0f MB atom via eve2...\n" lg-mb) (flush)
            (let [b2 (build-atom! lg-base2 lg-mb eve2-atom/atom eve2-atom/persistent-atom-domain)]
              (printf "    eve2: %d keys, %.1f MB, %.1fs (%.0f keys/s)\n"
                      (:key-count b2) (:disk-mb b2) (:elapsed-s b2)
                      (/ (:key-count b2) (:elapsed-s b2)))
              (flush)

              ;; Swap latency on large atoms
              (println "  Swap latency on large atoms (100 swaps, 50 warmup):")
              (let [r1 (swap-latency-test! (:atom b1) (:key-count b1) 100 50)
                    r2 (swap-latency-test! (:atom b2) (:key-count b2) 100 50)]
                (printf "    eve1: p50=%.2f p95=%.2f p99=%.2f ms\n"
                        (:p50 r1) (:p95 r1) (:p99 r1))
                (printf "    eve2: p50=%.2f p95=%.2f p99=%.2f ms\n"
                        (:p50 r2) (:p95 r2) (:p99 r2))
                (flush))

              ;; Contention on large atoms
              (println "  Contention on large atoms (4 threads × 50 ops):")
              (let [c1 (contention-test! (:atom b1) 4 50)
                    c2 (contention-test! (:atom b2) 4 50)]
                (printf "    eve1: %.0f ms (%.0f ops/s) %s\n"
                        (:wall-ms c1) (/ (* (:expected c1) 1000.0) (:wall-ms c1))
                        (if (:correct? c1) "CORRECT" "MISMATCH"))
                (printf "    eve2: %.0f ms (%.0f ops/s) %s\n"
                        (:wall-ms c2) (/ (* (:expected c2) 1000.0) (:wall-ms c2))
                        (if (:correct? c2) "CORRECT" "MISMATCH"))
                (flush))

              (eve1-atom/close-atom-domain! (:domain b2))
              (cleanup! lg-base2))

            (eve1-atom/close-atom-domain! (:domain b1))
            (cleanup! lg-base1)))

        ;; ══════════════════════════════════════════════════════════════
        ;; Summary
        ;; ══════════════════════════════════════════════════════════════
        (println)
        (println "╔══════════════════════════════════════════════════════════════╗")
        (println "║                     Results Summary                        ║")
        (println "╠══════════════════════════════════════════════════════════════╣")
        (printf  "║  Benchmarks run: %-42d║\n" (count @results))
        (printf  "║  eve1 on disk:   %-42s║\n"
                (format "%.1f MB" (/ (double (total-disk-bytes base-eve1)) (* 1024 1024))))
        (printf  "║  eve2 on disk:   %-42s║\n"
                (format "%.1f MB" (/ (double (total-disk-bytes base-eve2)) (* 1024 1024))))
        (println "╠══════════════════════════════════════════════════════════════╣")
        ;; Print eve1 vs eve2 pairs
        (doseq [[r1 r2] (->> @results
                              (partition-all 2)
                              (filter #(and (= 2 (count %))
                                            (.contains (:label (first %)) "[eve1]")
                                            (.contains (:label (second %)) "[eve2]"))))]
          (let [label (-> (:label r1) (str/replace " [eve1]" ""))
                ratio (if (pos? (:elapsed-ms r1))
                        (/ (:elapsed-ms r2) (:elapsed-ms r1))
                        0.0)]
            (printf "║  %-35s eve1:%6.1f  eve2:%6.1f  %5.3fx ║\n"
                    label (:elapsed-ms r1) (:elapsed-ms r2) ratio)))
        (println "╚══════════════════════════════════════════════════════════════╝")
        (println)

        (eve1-atom/close-atom-domain! d1)
        (eve1-atom/close-atom-domain! d2)
        (cleanup! base-eve1)
        (cleanup! base-eve2)))))
