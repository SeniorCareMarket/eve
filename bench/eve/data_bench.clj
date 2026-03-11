(ns eve.data-bench
  "Comprehensive data-transformation benchmark: CLJ atoms vs Eve persistent atoms.

   Exercises the bread-and-butter Clojure workloads — maps in vectors in maps,
   rich scalars, splitting / merging / shuffling / reducing — and times each
   operation individually, in bulk, and under cross-process contention.

   Usage:
     clj -M:native-data-bench <base-path> [target-mb]
     Example: clj -M:native-data-bench /tmp/eve-bench 5

   Phases:
     1. Build identical rich datasets in CLJ atom and Eve atom
     2. 24 data-transformation benchmarks on each
     3. Cross-process contention (JVM + Node concurrent swaps)
     4. Summary table"
  (:require [eve.atom :as atom]
            [eve.perf :as perf]
            [eve.deftype-proto.data :as d]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private bench-worker
  (str (System/getProperty "user.dir") "/target/eve-test/bench-worker.js"))

(defn- spawn-node! [& args]
  (let [pb   (doto (ProcessBuilder. ^java.util.List (into ["node"] args))
               (.redirectErrorStream false))
        proc (.start pb)
        out  (future (slurp (.getInputStream proc)))
        err  (future (slurp (.getErrorStream proc)))
        exit (.waitFor proc)]
    {:exit exit :out @out :err @err}))

(defn- spawn-node-edn! [& args]
  (let [r (apply spawn-node! args)]
    (when-not (zero? (:exit r))
      (throw (ex-info "Node worker failed" {:args args :err (:err r)})))
    (edn/read-string (.trim (:out r)))))

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
;; Rich data generators
;; ---------------------------------------------------------------------------

(defn- user-record
  "A rich heterogeneous record: nested maps, vectors, sets, lists, strings,
   keywords, integers, booleans."
  [i]
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
  "Run f n-iters times, return elapsed-ms. Stores result in results atom.
   Logs per-op timing for the first 5 ops and every 10th op after that."
  [label n-iters f]
  (printf "  [start] %s (%d ops)\n" label n-iters)
  (flush)
  (let [lats (long-array n-iters)
        t0   (System/nanoTime)]
    (dotimes [i n-iters]
      (let [t-op (System/nanoTime)]
        (f)
        (let [op-ns (- (System/nanoTime) t-op)]
          (aset lats i op-ns)
          (when (or (< i 5) (zero? (mod i 10)))
            (printf "    op %d: %.1f ms\n" i (/ (double op-ns) 1e6))
            (flush)))))
    (let [elapsed-ms (nanos->ms (- (System/nanoTime) t0))
          ms-per-op  (/ elapsed-ms (max 1 n-iters))
          sorted     (sort (map #(/ (double %) 1e6) (seq lats)))
          cnt        (count sorted)
          p50        (nth sorted (quot cnt 2))
          p99        (nth sorted (int (* cnt 0.99)))]
      (swap! results conj {:label label :elapsed-ms elapsed-ms
                           :ops n-iters :ms-per-op ms-per-op
                           :p50-ms p50 :p99-ms p99})
      (printf "  %-45s %8.1f ms  (%d ops, %.3f ms/op, p50=%.1f p99=%.1f)\n"
              label elapsed-ms n-iters ms-per-op p50 p99)
      (flush)
      elapsed-ms)))

(defn- bench-pair
  "Run the same operation on both CLJ and Eve atoms, print side by side."
  [label n-iters clj-fn eve-fn]
  (let [clj-ms (bench (str label " [CLJ]") n-iters clj-fn)
        eve-ms (bench (str label " [EVE]") n-iters eve-fn)
        ratio  (if (pos? clj-ms) (/ eve-ms clj-ms) 0.0)]
    (printf "  %-45s ratio: %.2fx\n" "" ratio)
    (flush)))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (when (< (count args) 1)
    (println "Usage: clj -M:native-data-bench <base-path> [target-mb]")
    (System/exit 1))
  (let [base-path    (first args)
        target-mb    (if (second args) (Double/parseDouble (second args)) 6.0)
        ;; 500 users + 1000 orders ≈ 5.9 MB on disk
        scale        (/ target-mb 6.0)
        n-users      (max 10 (int (* 500 scale)))
        n-orders     (max 20 (int (* 1000 scale)))]
    (cleanup! base-path)

    (println)
    (println "╔══════════════════════════════════════════════════════════════╗")
    (println "║   Eve Data Transformation Benchmark: CLJ atom vs Eve atom  ║")
    (println "╚══════════════════════════════════════════════════════════════╝")
    (println)

    ;; ── Phase 1: Build datasets ──
    (section "Phase 1: Build identical datasets")

    (let [users  (into {} (map (fn [i] [(keyword (str "u" i)) (user-record i)])
                               (range n-users)))
          orders (into {} (map (fn [i] [(keyword (str "o" i)) (order-record i)])
                               (range n-orders)))
          dataset {:users users :orders orders :counter 0 :version 1}]

      (printf "  Users:  %d records\n" n-users)
      (printf "  Orders: %d records\n" n-orders)
      (printf "  Top-level keys in dataset: %d\n" (count dataset))

      ;; CLJ atom
      (let [clj-a   (clojure.core/atom dataset)
            ;; Eve atom
            d       (atom/persistent-atom-domain base-path)
            eve-a   (atom/atom {:id :eve/main :persistent base-path} dataset)
            disk-mb (/ (double (total-disk-bytes base-path)) (* 1024 1024))]

        (printf "  Eve on-disk: %.1f MB\n" disk-mb)

        ;; Verify round-trip
        (let [eve-val @eve-a]
          (assert (= (:counter eve-val) 0) "Eve round-trip failed")
          (assert (= (count (:users eve-val)) n-users) "Eve user count mismatch"))

        ;; ══════════════════════════════════════════════════════════════
        ;; DIAGNOSTIC: IEveRoot check + profiled swap
        ;; ══════════════════════════════════════════════════════════════
        (section "DIAGNOSTIC: IEveRoot + profiled swap")

        (let [eve-val @eve-a
              users-val (:users eve-val)]
          (printf "  Root value type: %s\n" (type eve-val))
          (printf "  IEveRoot? root:  %s\n" (satisfies? d/IEveRoot eve-val))
          (printf "  IEveRoot? users: %s\n" (satisfies? d/IEveRoot users-val))
          (flush))

        (perf/enable!)
        (dotimes [i 5]
          (let [t0 (System/nanoTime)]
            (swap! eve-a assoc-in [:users :new-user] (user-record 9999))
            (let [ms (/ (- (System/nanoTime) t0) 1e6)]
              (printf "  swap %d: %.1f ms\n" i ms)
              (flush))))
        (println "  Perf breakdown (5 swaps):")
        (perf/report)
        (perf/disable!)
        (printf "  Disk after 5 swaps: %.1f MB\n"
                (/ (double (total-disk-bytes base-path)) (* 1024 1024)))
        (flush)

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 2: Individual operation benchmarks (24 benchmarks)
        ;; ══════════════════════════════════════════════════════════════

        ;; ── MAP OPERATIONS ──
        (section "Phase 2a: Map Operations")

        ;; 1. assoc — add new key to nested map
        (bench-pair "map/assoc-new-key" 200
          #(swap! clj-a assoc-in [:users :new-user] (user-record 9999))
          #(swap! eve-a assoc-in [:users :new-user] (user-record 9999)))

        ;; 2. assoc — update existing key
        (bench-pair "map/update-existing-key" 200
          #(swap! clj-a assoc-in [:users :u0 :name] "updated")
          #(swap! eve-a assoc-in [:users :u0 :name] "updated"))

        ;; 3. dissoc — remove key from map
        (bench-pair "map/dissoc-key" 200
          #(swap! clj-a update :users dissoc :u1)
          #(swap! eve-a update :users dissoc :u1))

        ;; 4. get-in — deep nested lookup
        (bench-pair "map/get-in-deep" 1000
          #(get-in @clj-a [:users :u50 :profile :location :city])
          #(get-in @eve-a [:users :u50 :profile :location :city]))

        ;; 5. update-in — increment nested counter
        (bench-pair "map/update-in-nested" 200
          #(swap! clj-a update-in [:users :u10 :profile :prefs :font-size] inc)
          #(swap! eve-a update-in [:users :u10 :profile :prefs :font-size] inc))

        ;; 6. merge — merge two maps
        (bench-pair "map/merge-maps" 100
          #(swap! clj-a update :users merge {:extra1 {:id -1} :extra2 {:id -2}})
          #(swap! eve-a update :users merge {:extra1 {:id -1} :extra2 {:id -2}}))

        ;; 7. select-keys — extract subset
        (bench-pair "map/select-keys" 500
          #(select-keys (:users @clj-a) [:u0 :u1 :u2 :u3 :u4])
          #(select-keys (:users @eve-a) [:u0 :u1 :u2 :u3 :u4]))

        ;; 8. reduce-kv — sum all user IDs
        (bench-pair "map/reduce-kv-sum" 50
          #(reduce-kv (fn [acc _k v] (+ acc (:id v))) 0 (:users @clj-a))
          #(reduce-kv (fn [acc _k v] (+ acc (:id v))) 0 (:users @eve-a)))

        ;; ── VECTOR OPERATIONS ──
        (section "Phase 2b: Vector Operations")

        ;; 9. conj — append to vector
        (bench-pair "vec/conj-append" 200
          #(swap! clj-a update-in [:users :u0 :scores] conj 99)
          #(swap! eve-a update-in [:users :u0 :scores] conj 99))

        ;; 10. assoc — update vector element by index
        (bench-pair "vec/assoc-at-index" 200
          #(swap! clj-a assoc-in [:users :u0 :scores 0] 100)
          #(swap! eve-a assoc-in [:users :u0 :scores 0] 100))

        ;; 11. nth — random access
        (bench-pair "vec/nth-random-access" 1000
          #(nth (:scores (get-in @clj-a [:users :u25])) 5)
          #(nth (:scores (get-in @eve-a [:users :u25])) 5))

        ;; 12. mapv — transform entire vector
        (bench-pair "vec/mapv-transform" 200
          #(swap! clj-a update-in [:users :u0 :scores] (fn [v] (mapv inc v)))
          #(swap! eve-a update-in [:users :u0 :scores] (fn [v] (mapv inc v))))

        ;; 13. filterv — filter vector elements
        (bench-pair "vec/filterv-select" 200
          #(filterv (fn [x] (> x 60)) (:scores (get-in @clj-a [:users :u10])))
          #(filterv (fn [x] (> x 60)) (:scores (get-in @eve-a [:users :u10]))))

        ;; 14. into [] — rebuild vector from seq
        (bench-pair "vec/into-rebuild" 200
          #(into [] (map inc) (:scores (get-in @clj-a [:users :u5])))
          #(into [] (map inc) (:scores (get-in @eve-a [:users :u5]))))

        ;; ── SET OPERATIONS ──
        (section "Phase 2c: Set Operations")

        ;; 15. conj — add to set
        (bench-pair "set/conj-add" 200
          #(swap! clj-a update-in [:users :u0 :tags] conj :new-tag)
          #(swap! eve-a update-in [:users :u0 :tags] conj :new-tag))

        ;; 16. disj — remove from set
        (bench-pair "set/disj-remove" 200
          #(swap! clj-a update-in [:users :u0 :tags] disj :verified)
          #(swap! eve-a update-in [:users :u0 :tags] disj :verified))

        ;; 17. contains? — set membership
        (bench-pair "set/contains?" 1000
          #(contains? (:tags (get-in @clj-a [:users :u0])) :active)
          #(contains? (:tags (get-in @eve-a [:users :u0])) :active))

        ;; 18. set union (via into)
        (bench-pair "set/union-via-into" 200
          #(swap! clj-a update-in [:users :u0 :tags] into #{:x :y :z})
          #(swap! eve-a update-in [:users :u0 :tags] into #{:x :y :z}))

        ;; ── RICH DATA TRANSFORMATIONS ──
        (section "Phase 2d: Rich Data Transformations")

        ;; 19. Pipeline: filter + transform + aggregate orders
        (bench-pair "xform/filter-map-reduce-orders" 30
          #(let [ords (vals (:orders @clj-a))]
             (->> ords
                  (filter (fn [o] (= :shipped (:status o))))
                  (map :total)
                  (reduce +)))
          #(let [ords (vals (:orders @eve-a))]
             (->> ords
                  (filter (fn [o] (= :shipped (:status o))))
                  (map :total)
                  (reduce +))))

        ;; 20. Group-by transformation
        (bench-pair "xform/group-by-status" 30
          #(group-by :status (vals (:orders @clj-a)))
          #(group-by :status (vals (:orders @eve-a))))

        ;; 21. Nested restructure: flatten user profiles into vector
        (bench-pair "xform/flatten-profiles" 30
          #(mapv (fn [[_k v]] {:name (:name v) :city (get-in v [:profile :location :city])})
                 (:users @clj-a))
          #(mapv (fn [[_k v]] {:name (:name v) :city (get-in v [:profile :location :city])})
                 (:users @eve-a)))

        ;; 22. Bulk swap — update 50 records in single swap
        (bench-pair "xform/bulk-swap-50-records" 20
          #(swap! clj-a update :users
                  (fn [users]
                    (reduce (fn [m i]
                              (update-in m [(keyword (str "u" i)) :profile :prefs :font-size] (fnil inc 0)))
                            users (range 50))))
          #(swap! eve-a update :users
                  (fn [users]
                    (reduce (fn [m i]
                              (update-in m [(keyword (str "u" i)) :profile :prefs :font-size] (fnil inc 0)))
                            users (range 50)))))

        ;; 23. Build new structure from existing: users → sorted-by-score leaderboard
        (bench-pair "xform/leaderboard-build" 20
          #(->> (:users @clj-a) vals
                (map (fn [u] {:name (:name u) :score (reduce + (:scores u))}))
                (sort-by :score >)
                (take 20)
                vec)
          #(->> (:users @eve-a) vals
                (map (fn [u] {:name (:name u) :score (reduce + (:scores u))}))
                (sort-by :score >)
                (take 20)
                vec))

        ;; 24. Swap with full rewrite of nested structure
        (bench-pair "xform/rewrite-nested-matrix" 100
          #(swap! clj-a update-in [:users :u0 :matrix]
                  (fn [mx] (mapv (fn [row] (mapv (fn [x] (mod (+ x 1) 1000)) row)) mx)))
          #(swap! eve-a update-in [:users :u0 :matrix]
                  (fn [mx] (mapv (fn [row] (mapv (fn [x] (mod (+ x 1) 1000)) row)) mx))))

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 3: Bulk operation benchmarks
        ;; ══════════════════════════════════════════════════════════════
        (section "Phase 3: Bulk Operations")

        ;; 25. Rapid-fire counter increments
        (let [n 500]
          (bench-pair (str "bulk/counter-inc-" n "x") n
            #(swap! clj-a update :counter inc)
            #(swap! eve-a update :counter inc)))

        ;; 26. Sequential assoc of new records
        (let [n 100]
          (bench-pair (str "bulk/assoc-" n "-new-orders") n
            #(swap! clj-a assoc-in [:orders (keyword (str "new-" (rand-int 100000)))]
                    (order-record (rand-int 100000)))
            #(swap! eve-a assoc-in [:orders (keyword (str "new-" (rand-int 100000)))]
                    (order-record (rand-int 100000)))))

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 4: JVM multi-threaded contention
        ;; ══════════════════════════════════════════════════════════════
        (section "Phase 4: JVM Multi-Threaded Contention")

        (let [n-threads   4
              ops-per     100
              ;; Reset counters
              _           (swap! clj-a assoc :counter 0)
              _           (swap! eve-a assoc :counter 0)
              ;; CLJ contention
              t0-clj      (System/nanoTime)
              clj-futures (mapv (fn [_] (future (dotimes [_ ops-per]
                                                  (swap! clj-a update :counter inc))))
                                (range n-threads))
              _           (run! deref clj-futures)
              clj-ms      (nanos->ms (- (System/nanoTime) t0-clj))
              clj-count   (:counter @clj-a)
              ;; Eve contention
              t0-eve      (System/nanoTime)
              eve-futures (mapv (fn [_] (future (dotimes [_ ops-per]
                                                  (swap! eve-a update :counter inc))))
                                (range n-threads))
              _           (run! deref eve-futures)
              eve-ms      (nanos->ms (- (System/nanoTime) t0-eve))
              eve-count   (:counter @eve-a)
              expected    (* n-threads ops-per)]
          (printf "  CLJ: %d threads × %d ops = %d (expected %d) in %.0f ms (%.0f ops/s) %s\n"
                  n-threads ops-per clj-count expected clj-ms
                  (/ (* expected 1000.0) clj-ms)
                  (if (= clj-count expected) "CORRECT" "MISMATCH"))
          (printf "  EVE: %d threads × %d ops = %d (expected %d) in %.0f ms (%.0f ops/s) %s\n"
                  n-threads ops-per eve-count expected eve-ms
                  (/ (* expected 1000.0) eve-ms)
                  (if (= eve-count expected) "CORRECT" "MISMATCH"))
          (printf "  Ratio: %.2fx\n" (if (pos? clj-ms) (/ eve-ms clj-ms) 0.0)))

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 5: Cross-process contention (JVM + Node)
        ;; ══════════════════════════════════════════════════════════════
        (section "Phase 5: Cross-Process Contention (JVM + Node)")

        (if (.exists (File. bench-worker))
          (do
            (swap! eve-a assoc :counter 0)
            (let [ops-per      50
                  n-jvm        2
                  n-node       2
                  t0           (System/nanoTime)
                  ;; JVM threads
                  jvm-futures
                  (mapv (fn [_]
                          (future
                            (dotimes [_ ops-per]
                              (swap! eve-a update :counter inc))))
                        (range n-jvm))
                  ;; Node processes
                  node-futures
                  (mapv (fn [_]
                          (future
                            (spawn-node-edn! bench-worker "bench-contend"
                                             base-path (str ops-per))))
                        (range n-node))
                  _            (run! deref jvm-futures)
                  node-results (mapv deref node-futures)
                  wall-ms      (nanos->ms (- (System/nanoTime) t0))
                  final-count  (:counter @eve-a)
                  expected     (* ops-per (+ n-jvm n-node))
                  correct?     (= final-count expected)]
              (printf "  %d JVM threads + %d Node processes × %d ops each\n"
                      n-jvm n-node ops-per)
              (printf "  Wall time:  %.0f ms\n" wall-ms)
              (printf "  Throughput: %.0f ops/s\n" (/ (* expected 1000.0) wall-ms))
              (printf "  Counter:    %d (expected %d) %s\n"
                      final-count expected
                      (if correct? "CORRECT" "MISMATCH"))))
          (println "  (skipped — bench-worker.js not found, compile with shadow-cljs)"))

        ;; ══════════════════════════════════════════════════════════════
        ;; Phase 6: Cross-process rich data writes (JVM + Node)
        ;; ══════════════════════════════════════════════════════════════
        (section "Phase 6: Cross-Process Rich Data Writes")

        (if (.exists (File. bench-worker))
          (let [n-writes  20
                t0        (System/nanoTime)
                ;; JVM writes rich records
                jvm-f     (future
                            (dotimes [i n-writes]
                              (let [k (keyword (str "jvm-rich-" i))]
                                (swap! eve-a assoc k (user-record (+ 10000 i))))))
                ;; Node writes rich records concurrently
                node-f    (future
                            (spawn-node-edn! bench-worker "bench-write-rich"
                                             base-path (str n-writes) "node-rich"))
                _         @jvm-f
                node-r    @node-f
                wall-ms   (nanos->ms (- (System/nanoTime) t0))]
            (printf "  JVM wrote %d rich records + Node wrote %d rich records\n"
                    n-writes n-writes)
            (printf "  Wall time: %.0f ms  (Node: %.1f ms)\n" wall-ms (:elapsed-ms node-r))
            (printf "  Combined throughput: %.0f records/s\n"
                    (/ (* 2 n-writes 1000.0) wall-ms)))
          (println "  (skipped — bench-worker.js not found)"))

        ;; ══════════════════════════════════════════════════════════════
        ;; Summary
        ;; ══════════════════════════════════════════════════════════════
        (println)
        (println "╔══════════════════════════════════════════════════════════════╗")
        (println "║                     Results Summary                        ║")
        (println "╠══════════════════════════════════════════════════════════════╣")
        (printf  "║  Benchmarks run: %-42d║\n" (count @results))
        (printf  "║  Eve on disk:    %-42s║\n"
                (format "%.1f MB" (/ (double (total-disk-bytes base-path)) (* 1024 1024))))
        (println "╠══════════════════════════════════════════════════════════════╣")
        ;; Print CLJ vs EVE pairs
        (doseq [[clj-r eve-r] (->> @results
                                   (partition-all 2)
                                   (filter #(and (= 2 (count %))
                                                 (.contains (:label (first %)) "[CLJ]")
                                                 (.contains (:label (second %)) "[EVE]"))))]
          (let [label (-> (:label clj-r) (str/replace " [CLJ]" ""))
                ratio (if (pos? (:elapsed-ms clj-r))
                        (/ (:elapsed-ms eve-r) (:elapsed-ms clj-r))
                        0.0)]
            (printf "║  %-38s CLJ:%6.1f  EVE:%6.1f  %5.2fx ║\n"
                    label (:elapsed-ms clj-r) (:elapsed-ms eve-r) ratio)))
        (println "╚══════════════════════════════════════════════════════════════╝")
        (println)

        (atom/close-atom-domain! d)))))
