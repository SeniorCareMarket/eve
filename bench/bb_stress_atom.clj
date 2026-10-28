#!/usr/bin/env bb
;; bench/bb_stress_atom.clj — Babashka mmap atom stress bench
;;
;; Usage:  bb -f bench/bb_stress_atom.clj <base-path>
;; Example: bb -f bench/bb_stress_atom.clj /tmp/eve-std-10m
;;
;; Requires: atom built first via `clj -M:native-build-atom <path> <mb>`

(require '[eve.atom :as atom])

(when (< (count *command-line-args*) 1)
  (println "Usage: bb -f bench/bb_stress_atom.clj <base-path>")
  (println "Example: bb -f bench/bb_stress_atom.clj /tmp/eve-std-10m")
  (System/exit 1))

(def base-path (first *command-line-args*))

(defn nanos->ms [n] (/ (double n) 1e6))

(defn total-disk-bytes [base]
  (reduce + (map #(let [f (java.io.File. (str base %))]
                    (if (.exists f) (.length f) 0))
                 [".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5"
                  ".slab6" ".root" ".rmap"
                  ".slab0.bm" ".slab1.bm" ".slab2.bm"
                  ".slab3.bm" ".slab4.bm" ".slab5.bm"])))

(defn percentile [sorted-vec p]
  (nth sorted-vec (min (int (* (count sorted-vec) p))
                       (dec (count sorted-vec)))))

(defn section [title]
  (println)
  (printf "-- %s --\n" title)
  (println))

;; Preflight
(when-not (.exists (java.io.File. (str base-path ".root")))
  (println (str "Error: No atom found at " base-path))
  (println "Run native-build-atom first.")
  (System/exit 1))

(let [disk-bytes (total-disk-bytes base-path)
      disk-mb    (/ (double disk-bytes) (* 1024 1024))]
  (println)
  (println "native-eve: Babashka Stress Bench")
  (println "====================================================")
  (printf  "  Atom:    %s\n" base-path)
  (printf  "  On disk: %.1f MB\n" disk-mb)
  (println "====================================================")

  ;; ── Phase 1: Cold Open ──
  (section "Phase 1: Cold Open (bb joins atom from disk)")
  (let [t0       (System/nanoTime)
        d        (atom/persistent-atom-domain base-path)
        a        (atom/lookup-or-create-mmap-atom! d "eve/main" nil)
        open-ms  (nanos->ms (- (System/nanoTime) t0))
        t1       (System/nanoTime)
        val      @a
        deref-ms (nanos->ms (- (System/nanoTime) t1))
        key-count (count val)]
    (printf "  join-atom:   %6.1f ms (open mmap files)\n" open-ms)
    (printf "  first deref: %6.1f ms (%,d keys, %.1f MB)\n"
            deref-ms key-count disk-mb)

    ;; ── Phase 2: Single-Writer Swap Latency ──
    (section "Phase 2: bb Single-Writer Swap Latency")
    ;; Warmup: 20 swaps
    (dotimes [i 20]
      (swap! a assoc (keyword (str "k" (mod i key-count))) {:warmup true :i i}))
    (let [n    200
          lats (long-array n)]
      (dotimes [i n]
        (let [k (keyword (str "k" (mod i key-count)))
              t (System/nanoTime)]
          (swap! a assoc k {:updated true :iter i})
          (aset lats i (- (System/nanoTime) t))))
      (let [sorted (vec (sort (map #(/ (double %) 1e6) (seq lats))))
            cnt    (count sorted)]
        (printf "  %d swaps (update existing keys in %,d-key map)\n" n key-count)
        (printf "    p50:     %6.2f ms\n" (percentile sorted 0.50))
        (printf "    p95:     %6.2f ms\n" (percentile sorted 0.95))
        (printf "    p99:     %6.2f ms\n" (percentile sorted 0.99))
        (printf "    min/max: %6.2f / %.2f ms\n" (first sorted) (last sorted))))

    ;; ── Phase 3: Deref Throughput ──
    (section "Phase 3: Deref Throughput (slab-backed, no materialization)")
    (let [n  500
          t0 (System/nanoTime)]
      (dotimes [_ n] @a)
      (let [elapsed-ms (nanos->ms (- (System/nanoTime) t0))]
        (printf "  %d derefs: %.1f ms (%.2f ms/deref)\n" n elapsed-ms (/ elapsed-ms n))
        (printf "  Throughput: %,.0f derefs/s\n" (/ (* n 1000.0) elapsed-ms))))

    ;; ── Phase 4: Multi-Threaded Contention (bb threads) ──
    (section "Phase 4: 4 bb Threads (counter contention)")
    (swap! a assoc :counter 0)
    (let [ops-per-thread 50
          n-threads      4
          t0             (System/nanoTime)
          futures        (mapv (fn [_]
                                (future
                                  (dotimes [_ ops-per-thread]
                                    (swap! a update :counter inc))))
                              (range n-threads))
          _              (run! deref futures)
          wall-ms        (nanos->ms (- (System/nanoTime) t0))
          final-count    (:counter @a)
          expected       (* ops-per-thread n-threads)
          correct?       (= final-count expected)]
      (printf "  Writers:    %d bb threads\n" n-threads)
      (printf "  Ops/thread: %d\n" ops-per-thread)
      (printf "  Wall time:  %,.0f ms\n" wall-ms)
      (printf "  Throughput: %,.0f ops/s (aggregate)\n"
              (/ (* expected 1000.0) wall-ms))
      (printf "  Counter:    %d (expected %d) %s\n"
              final-count expected
              (if correct? "CORRECT" "MISMATCH"))
      (when-not correct?
        (println "  *** CONTENTION BUG DETECTED ***")))

    ;; ── Summary ──
    (println)
    (println "====================================================")
    (println "  Babashka Stress Bench Complete")
    (println "====================================================")
    (println)
    (atom/close-atom-domain! d)))
