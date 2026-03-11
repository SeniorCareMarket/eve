(ns eve.profiled-stress
  "Profiled single-writer swap latency test for the JVM HAMT hot path.
   Runs N swaps with eve.perf enabled, then prints a timing breakdown.

   Usage: clj -M:native-profiled-stress <base-path> [n-swaps]
   Example: clj -M:native-profiled-stress /tmp/eve-10m 200"
  (:require [eve.atom :as atom]
            [eve.perf :as perf])
  (:import [java.io File]))

(defn- total-disk-bytes [base]
  (reduce + (map #(let [f (File. (str base %))]
                    (if (.exists f) (.length f) 0))
                 [".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5"
                  ".slab6" ".root" ".rmap"
                  ".slab0.bm" ".slab1.bm" ".slab2.bm"
                  ".slab3.bm" ".slab4.bm" ".slab5.bm"])))

(defn- nanos->ms [n] (/ (double n) 1e6))

(defn -main [& args]
  (when (< (count args) 1)
    (println "Usage: clj -M:native-profiled-stress <base-path> [n-swaps]")
    (System/exit 1))
  (let [base-path (first args)
        n-swaps   (if (>= (count args) 2)
                    (parse-long (second args))
                    200)]
    (when-not (.exists (File. (str base-path ".root")))
      (println (str "Error: No atom found at " base-path))
      (println "Run native-build-atom first to create the atom.")
      (System/exit 1))

    (let [disk-bytes (total-disk-bytes base-path)
          disk-mb    (/ (double disk-bytes) (* 1024 1024))]
      (println)
      (println "native-eve: Profiled JVM Swap Analysis")
      (println "====================================================")
      (printf  "  Atom:    %s\n" base-path)
      (printf  "  On disk: %.1f MB\n" disk-mb)
      (printf  "  Swaps:   %d\n" n-swaps)
      (println "====================================================")
      (println)

      (let [d         (atom/persistent-atom-domain base-path)
            a         (atom/atom {:id :eve/main :persistent base-path} nil)
            key-count (count @a)]

        ;; ── Phase 1: Warmup (unprofiled) ──
        (println "Phase 1: Warmup (10 swaps, unprofiled)")
        (dotimes [i 10]
          (swap! a assoc (keyword (str "k" (mod i key-count))) {:warmup true :i i}))
        (println "  Done.")
        (println)

        ;; ── Phase 2: Profiled existing-key updates ──
        (println (str "Phase 2: Profiled " n-swaps " existing-key updates"))
        (perf/enable!)
        (let [lats (long-array n-swaps)]
          (dotimes [i n-swaps]
            (let [k (keyword (str "k" (mod i key-count)))
                  t (System/nanoTime)]
              (swap! a assoc k {:updated true :iter i})
              (aset lats i (- (System/nanoTime) t))))
          (let [sorted (sort (mapv #(/ (double %) 1e6) (seq lats)))
                cnt    (count sorted)]
            (println)
            (println "  Swap Latency Distribution:")
            (printf  "    p50:     %6.2f ms\n" (nth sorted (quot cnt 2)))
            (printf  "    p95:     %6.2f ms\n" (nth sorted (int (* cnt 0.95))))
            (printf  "    p99:     %6.2f ms\n" (nth sorted (int (* cnt 0.99))))
            (printf  "    min/max: %6.2f / %.2f ms\n" (first sorted) (last sorted))))

        (println)
        (println "  Time Breakdown (all threads merged):")
        (perf/report)

        ;; ── Phase 3: Profiled multi-threaded ──
        (perf/enable!)  ;; reset
        (let [n-threads 4
              ops-per   50]
          (printf "Phase 3: Profiled %d threads × %d swaps (contention)\n" n-threads ops-per)
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
                expect  (* n-threads ops-per)]
            (println)
            (printf  "  Wall:     %,.0f ms\n" wall-ms)
            (printf  "  Counter:  %d (expected %d) %s\n"
                     final expect (if (= final expect) "CORRECT" "MISMATCH"))
            (println)
            (println "  Time Breakdown (contended, all threads merged):")
            (perf/report)))

        (println "====================================================")
        (perf/disable!)
        (atom/close-atom-domain! d)))))
