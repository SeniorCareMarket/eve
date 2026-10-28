(ns eve.lustre-bench
  "Benchmark swap! performance for mmap atoms at 10MB and 100MB scale.
   Compares standard mmap vs Lustre (fcntl) mode on local filesystem.

   Run via: node target/eve-test/all.js lustre-bench"
  (:require [eve.atom :as atom]
            [eve.mem :as mem]))

(def ^:private fs (js/require "fs"))

(defn- cleanup-domain! [base-path]
  (doseq [ext [".root" ".rmap"
               ".slab0" ".slab0.bm"
               ".slab1" ".slab1.bm"
               ".slab2" ".slab2.bm"
               ".slab3" ".slab3.bm"
               ".slab4" ".slab4.bm"
               ".slab5" ".slab5.bm"
               ".slab6"]]
    (try (.unlinkSync fs (str base-path ext)) (catch :default _))))

(defn- now-ms [] (js/Date.now))

(defn- build-map
  "Build a Clojure map with n entries, each value is a short string."
  [n]
  (loop [i 0 m {}]
    (if (>= i n)
      m
      (recur (inc i) (assoc m (keyword (str "k" i)) (str "value-" i))))))

(defn- estimate-map-size
  "Rough estimate of serialized map size in bytes.
   Each entry: ~20 byte key + ~15 byte value + HAMT overhead ≈ 60-80 bytes/entry."
  [n]
  (* n 70))

(defn- run-bench!
  "Run a swap! benchmark: build initial map, then do N swap! calls (assoc a key).
   Returns {:init-ms, :swap-times-ms, :avg-swap-ms, :total-swap-ms, :n-entries, :n-swaps}."
  [label base-path lustre? n-entries n-swaps]
  (let [_          (println (str "\n--- " label " ---"))
        _          (println (str "  entries: " n-entries
                                 "  swaps: " n-swaps
                                 "  lustre?: " lustre?
                                 "  est-size: " (str (/ (estimate-map-size n-entries) 1048576) "MB")))
        ;; Build initial map
        t0         (now-ms)
        init-map   (build-map n-entries)
        t-build    (- (now-ms) t0)
        _          (println (str "  map built in " t-build "ms"))
        ;; Create domain + atom
        t1         (now-ms)
        domain     (atom/persistent-atom-domain base-path :lustre? lustre?)
        a          (atom/persistent-atom {:id :eve/bench :persistent base-path :lustre? lustre?} init-map)
        t-init     (- (now-ms) t1)
        _          (println (str "  atom init in " t-init "ms (initial swap! with full map)"))
        ;; Run N swap! calls — each assoc's a single key
        swap-times (loop [i 0 times []]
                     (if (>= i n-swaps)
                       times
                       (let [ts (now-ms)]
                         (swap! a assoc (keyword (str "bench-" i)) i)
                         (recur (inc i) (conj times (- (now-ms) ts))))))
        total-swap (reduce + swap-times)
        avg-swap   (/ total-swap (count swap-times))]
    (atom/close-atom-domain! domain)
    (cleanup-domain! base-path)
    (println (str "  swap! x" n-swaps ": total=" total-swap "ms  avg=" (.toFixed avg-swap 2) "ms"))
    {:label label
     :lustre? lustre?
     :n-entries n-entries
     :n-swaps n-swaps
     :build-map-ms t-build
     :init-ms t-init
     :swap-times-ms swap-times
     :total-swap-ms total-swap
     :avg-swap-ms avg-swap}))

(defn run-all-benches! []
  ;; ~10MB: ~140k entries × 70 bytes ≈ 9.8MB
  ;; ~100MB: ~1.4M entries × 70 bytes ≈ 98MB — may be too large for slab capacity
  ;; Use conservative sizes: 50k (~3.5MB) and 200k (~14MB) to stay within slab limits,
  ;; then scale up if they work.

  (let [ts       (js/Date.now)
        results  []
        ;; Small warmup
        _        (println "\n=== Lustre Swap! Benchmark ===")
        _        (println (str "Timestamp: " (.toISOString (js/Date.))))

        ;; --- 10MB scale ---
        ;; ~150k entries ≈ 10.5MB serialized
        r1 (run-bench! "mmap-std-10MB"
                       (str "/tmp/eve-bench-std10-" ts)
                       false 150000 20)
        r2 (run-bench! "mmap-lustre-10MB"
                       (str "/tmp/eve-bench-lus10-" ts)
                       true 150000 20)

        ;; --- 100MB scale ---
        ;; ~1.4M entries ≈ 98MB serialized
        r3 (run-bench! "mmap-std-100MB"
                       (str "/tmp/eve-bench-std100-" ts)
                       false 1400000 10)
        r4 (run-bench! "mmap-lustre-100MB"
                       (str "/tmp/eve-bench-lus100-" ts)
                       true 1400000 10)]

    (println "\n=== Summary ===")
    (doseq [r [r1 r2 r3 r4]]
      (println (str "  " (:label r)
                    ": init=" (:init-ms r) "ms"
                    "  avg-swap=" (.toFixed (:avg-swap-ms r) 2) "ms"
                    "  total-swap=" (:total-swap-ms r) "ms")))

    ;; Show overhead ratio
    (println "\n=== Lustre Overhead ===")
    (when (pos? (:avg-swap-ms r1))
      (println (str "  10MB:  lustre/std = "
                    (.toFixed (/ (:avg-swap-ms r2) (:avg-swap-ms r1)) 2) "x")))
    (when (pos? (:avg-swap-ms r3))
      (println (str "  100MB: lustre/std = "
                    (.toFixed (/ (:avg-swap-ms r4) (:avg-swap-ms r3)) 2) "x")))

    (println "\nDone.")))
