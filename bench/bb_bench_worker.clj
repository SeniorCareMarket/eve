#!/usr/bin/env bb
;; bench/bb_bench_worker.clj — Babashka benchmark worker for cross-process benchmarks.
;;
;; Usage:
;;   bb -f bench/bb_bench_worker.clj bench-contend   <base> <count>
;;   bb -f bench/bb_bench_worker.clj bench-write     <base> <count> <prefix>
;;   bb -f bench/bb_bench_worker.clj bench-read      <base> <count>
;;   bb -f bench/bb_bench_worker.clj bench-write-rich <base> <count> <prefix>
;;   bb -f bench/bb_bench_worker.clj bench-swap-latencies <base> <count> <prefix>
;;
;; All actions print a single EDN map to stdout with timing results.

(require '[eve.atom :as atom])

(defn- now-ns [] (System/nanoTime))
(defn- ns->ms [n] (/ (double n) 1e6))

(let [args *command-line-args*
      action (first args)
      base   (second args)]
  (when (nil? action)
    (binding [*out* *err*]
      (println "Usage: bb -f bench/bb_bench_worker.clj <action> <base> [args...]"))
    (System/exit 2))

  (let [d (atom/persistent-atom-domain base)
        a (atom/lookup-or-create-mmap-atom! d "eve/main" nil)]
    (case action
      ;; Time N derefs, report throughput
      "bench-read"
      (let [n  (Integer/parseInt (nth args 2))
            _  @a ;; warmup
            t0 (now-ns)]
        (dotimes [_ n] @a)
        (let [elapsed (ns->ms (- (now-ns) t0))]
          (atom/close-atom-domain! d)
          (println (pr-str {:elapsed-ms elapsed :ops n
                            :ops-per-s (/ (* n 1000) elapsed)}))))

      ;; Time N assoc swaps with unique keys
      "bench-write"
      (let [n      (Integer/parseInt (nth args 2))
            prefix (or (nth args 3 nil) "bb-bw")
            t0     (now-ns)]
        (dotimes [i n]
          (let [k (keyword (str prefix "-" i))]
            (swap! a assoc k i)))
        (let [elapsed (ns->ms (- (now-ns) t0))]
          (atom/close-atom-domain! d)
          (println (pr-str {:elapsed-ms elapsed :ops n
                            :ops-per-s (/ (* n 1000) elapsed)}))))

      ;; Time N counter increments (contention benchmark)
      "bench-contend"
      (let [n  (Integer/parseInt (nth args 2))
            t0 (now-ns)]
        (dotimes [_ n]
          (swap! a update :counter (fnil inc 0)))
        (let [elapsed (ns->ms (- (now-ns) t0))]
          (atom/close-atom-domain! d)
          (println (pr-str {:elapsed-ms elapsed :ops n
                            :ops-per-s (/ (* n 1000) elapsed)}))))

      ;; Write rich nested values for benchmarking
      "bench-write-rich"
      (let [n      (Integer/parseInt (nth args 2))
            prefix (or (nth args 3 nil) "bb-br")
            t0     (now-ns)]
        (dotimes [i n]
          (let [k (keyword (str prefix "-" i))
                v {:id i
                   :writer prefix
                   :deep {:a {:b {:c {:d (str prefix "-" i "-deep")}}}}
                   :items (vec (range 40))
                   :matrix [[i (* i 2) (* i 3)]
                             [(+ i 10) (+ i 20) (+ i 30)]
                             [(+ i 100) (+ i 200) (+ i 300)]]
                   :tags #{:alpha :beta :gamma :delta}
                   :history (list :created :validated :committed)
                   :payload (apply str (repeat 200 (str (char (+ 65 (mod i 26))))))}]
            (swap! a assoc k v)))
        (let [elapsed (ns->ms (- (now-ns) t0))]
          (atom/close-atom-domain! d)
          (println (pr-str {:elapsed-ms elapsed :ops n
                            :ops-per-s (/ (* n 1000) elapsed)}))))

      ;; Latency recording: N swaps, return each swap duration
      "bench-swap-latencies"
      (let [n      (Integer/parseInt (nth args 2))
            prefix (or (nth args 3 nil) "bb-bl")
            lats   (java.util.ArrayList.)]
        (dotimes [i n]
          (let [t0 (now-ns)
                k  (keyword (str prefix "-" i))]
            (swap! a assoc k i)
            (.add lats (ns->ms (- (now-ns) t0)))))
        (let [sorted (sort (seq lats))
              cnt    (count sorted)
              p50    (nth sorted (quot cnt 2))
              p95    (nth sorted (int (* cnt 0.95)))
              p99    (nth sorted (int (* cnt 0.99)))
              mn     (first sorted)
              mx     (last sorted)]
          (atom/close-atom-domain! d)
          (println (pr-str {:ops n :min-ms mn :p50-ms p50
                            :p95-ms p95 :p99-ms p99 :max-ms mx}))))

      ;; Unknown action
      (do (binding [*out* *err*]
            (println "Unknown action:" action))
          (atom/close-atom-domain! d)
          (System/exit 2)))))
