(ns eve.contention-bench
  "Cross-process contention benchmark: JVM + Node + bb.

   Usage:
     clj -M:contention-bench <base-path>
     Example: clj -M:contention-bench /tmp/eve-contention"
  (:require [eve.atom :as atom]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private bench-worker
  (str (System/getProperty "user.dir") "/target/eve-test/bench-worker.js"))

(def ^:private bb-bench-worker
  (str (System/getProperty "user.dir") "/bench/bb_bench_worker.clj"))

(defn- spawn-proc! [cmd & args]
  (let [pb   (doto (ProcessBuilder. ^java.util.List (into cmd args))
               (.redirectErrorStream false))
        proc (.start pb)
        out  (future (slurp (.getInputStream proc)))
        err  (future (slurp (.getErrorStream proc)))
        exit (.waitFor proc)]
    {:exit exit :out @out :err @err}))

(defn- spawn-edn! [spawn-fn & args]
  (let [r (apply spawn-fn args)]
    (when-not (zero? (:exit r))
      (throw (ex-info "Worker failed" {:args args :err (:err r)})))
    (edn/read-string (.trim (:out r)))))

(defn- spawn-node! [& args] (apply spawn-proc! ["node"] args))
(defn- spawn-bb!   [& args] (apply spawn-proc! ["bb" "-f" bb-bench-worker] args))
(defn- spawn-node-edn! [& args] (apply spawn-edn! spawn-node! args))
(defn- spawn-bb-edn!   [& args] (apply spawn-edn! spawn-bb! args))

(defn- nanos->ms [n] (/ (double n) 1e6))

(defn- cleanup! [base]
  (doseq [ext [".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5"
               ".slab6" ".root" ".rmap"
               ".slab0.bm" ".slab1.bm" ".slab2.bm"
               ".slab3.bm" ".slab4.bm" ".slab5.bm"]]
    (let [f (File. (str base ext))]
      (when (.exists f) (.delete f)))))

(defn- total-disk-bytes [base]
  (reduce + (map #(let [f (File. (str base %))]
                    (if (.exists f) (.length f) 0))
                 [".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5"
                  ".slab6" ".root" ".rmap"
                  ".slab0.bm" ".slab1.bm" ".slab2.bm"
                  ".slab3.bm" ".slab4.bm" ".slab5.bm"])))

;; ---------------------------------------------------------------------------
;; Rich data generator
;; ---------------------------------------------------------------------------

(defn- user-record [i]
  {:id i :name (str "user-" i)
   :email (str "user-" i "@example.com")
   :active? (even? i)
   :role (nth [:admin :editor :viewer :guest] (mod i 4))
   :profile {:bio (str "Bio for user " i)
             :location {:city (nth ["NYC" "SF" "LA" "CHI" "SEA"] (mod i 5))
                        :state (nth ["NY" "CA" "CA" "IL" "WA"] (mod i 5))
                        :zip (str (+ 10000 (mod i 90000)))}
             :prefs {:theme (if (even? i) :dark :light) :lang :en
                     :font-size (+ 10 (mod i 10)) :notify? true}}
   :scores (vec (for [j (range 10)] (+ 50 (mod (* i (inc j)) 50))))
   :tags #{:verified :active (keyword (str "tier-" (mod i 5)))}
   :history (list :signup :confirmed :purchase :review)
   :matrix [[(mod i 100) (* i 2) (* i 3)]
            [(+ i 10) (+ i 20) (+ i 30)]
            [(+ i 100) (+ i 200) (+ i 300)]]
   :metadata {:created (str "2025-01-" (inc (mod i 28)))
              :version (inc (mod i 10))
              :flags #{:exportable :searchable}}})

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (when (< (count args) 1)
    (println "Usage: clj -M:contention-bench <base-path>")
    (System/exit 1))
  (let [base-path (first args)]
    (cleanup! base-path)

    (println)
    (println "╔══════════════════════════════════════════════════════════════╗")
    (println "║  Cross-Process Contention Benchmark: JVM + Node + bb       ║")
    (println "╚══════════════════════════════════════════════════════════════╝")

    ;; Build a moderately rich dataset
    (let [n-users 200
          users   (into {} (map (fn [i] [(keyword (str "u" i)) (user-record i)])
                                (range n-users)))
          dataset {:users users :counter 0 :version 1}
          d       (atom/persistent-atom-domain base-path)
          eve-a   (atom/atom {:id :eve/main :persistent base-path} dataset)
          disk-mb (/ (double (total-disk-bytes base-path)) (* 1024 1024))]

      (printf "\n  Dataset: %d users, %.1f MB on disk\n\n" n-users disk-mb)
      (flush)

      ;; Verify round-trip
      (assert (= (:counter @eve-a) 0) "Round-trip failed")
      (assert (= (count (:users @eve-a)) n-users) "User count mismatch")

      ;; ── Phase 1: Individual swap latencies per platform ──
      (println "── Phase 1: Individual Swap Latencies ──")
      (println)

      ;; JVM swap latencies
      (let [n 50
            lats (long-array n)]
        (dotimes [i n]
          (let [t0 (System/nanoTime)]
            (swap! eve-a update :counter inc)
            (aset lats i (- (System/nanoTime) t0))))
        (let [sorted (sort (map #(/ (double %) 1e6) (seq lats)))
              cnt    (count sorted)
              p50    (nth sorted (quot cnt 2))
              p99    (nth sorted (int (* cnt 0.99)))]
          (printf "  JVM:  %d swaps, p50=%.2f ms, p99=%.2f ms\n" n p50 p99)))

      ;; Node swap latencies
      (when (.exists (File. bench-worker))
        (let [r (spawn-node-edn! bench-worker "bench-swap-latencies"
                                 base-path "50" "node-lat")]
          (printf "  Node: %d swaps, p50=%.2f ms, p99=%.2f ms\n"
                  (:ops r) (:p50-ms r) (:p99-ms r))))

      ;; bb swap latencies
      (let [r (spawn-bb-edn! "bench-swap-latencies" base-path "50" "bb-lat")]
        (printf "  bb:   %d swaps, p50=%.2f ms, p99=%.2f ms\n"
                (:ops r) (:p50-ms r) (:p99-ms r)))
      (flush)

      ;; ── Phase 2: Cross-process contention (JVM + Node + bb) ──
      (println)
      (println "── Phase 2: Cross-Process Contention (JVM + Node + bb) ──")
      (println)

      (when (.exists (File. bench-worker))
        (swap! eve-a assoc :counter 0)
        (let [ops-per 50
              n-jvm   2
              n-node  2
              n-bb    2
              t0      (System/nanoTime)
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
              ;; bb processes
              bb-futures
              (mapv (fn [_]
                      (future
                        (spawn-bb-edn! "bench-contend"
                                       base-path (str ops-per))))
                    (range n-bb))
              _            (run! deref jvm-futures)
              node-results (mapv deref node-futures)
              bb-results   (mapv deref bb-futures)
              wall-ms      (nanos->ms (- (System/nanoTime) t0))
              final-count  (:counter @eve-a)
              expected     (* ops-per (+ n-jvm n-node n-bb))
              correct?     (= final-count expected)]
          (printf "  %d JVM threads + %d Node processes + %d bb processes × %d ops each\n"
                  n-jvm n-node n-bb ops-per)
          (printf "  Wall time:  %.0f ms\n" wall-ms)
          (printf "  Throughput: %.0f ops/s\n" (/ (* expected 1000.0) wall-ms))
          (printf "  Counter:    %d (expected %d) %s\n"
                  final-count expected
                  (if correct? "CORRECT" "MISMATCH"))
          (flush)))

      ;; ── Phase 3: Cross-process rich data writes ──
      (println)
      (println "── Phase 3: Cross-Process Rich Data Writes (JVM + Node + bb) ──")
      (println)

      (when (.exists (File. bench-worker))
        (let [n-writes 20
              t0       (System/nanoTime)
              ;; JVM writes
              jvm-f    (future
                         (dotimes [i n-writes]
                           (let [k (keyword (str "jvm-rich-" i))]
                             (swap! eve-a assoc k (user-record (+ 10000 i))))))
              ;; Node writes
              node-f   (future
                         (spawn-node-edn! bench-worker "bench-write-rich"
                                          base-path (str n-writes) "node-rich"))
              ;; bb writes
              bb-f     (future
                         (spawn-bb-edn! "bench-write-rich"
                                        base-path (str n-writes) "bb-rich"))
              _        @jvm-f
              node-r   @node-f
              bb-r     @bb-f
              wall-ms  (nanos->ms (- (System/nanoTime) t0))]
          (printf "  JVM wrote %d + Node wrote %d + bb wrote %d rich records\n"
                  n-writes n-writes n-writes)
          (printf "  Wall time: %.0f ms  (Node: %.1f ms, bb: %.1f ms)\n"
                  wall-ms (:elapsed-ms node-r) (:elapsed-ms bb-r))
          (printf "  Combined throughput: %.0f records/s\n"
                  (/ (* 3 n-writes 1000.0) wall-ms))
          (flush)))

      (println)
      (printf "  Final disk size: %.1f MB\n"
              (/ (double (total-disk-bytes base-path)) (* 1024 1024)))
      (println)
      (atom/close-atom-domain! d))))
