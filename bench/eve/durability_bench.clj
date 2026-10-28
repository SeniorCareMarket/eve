(ns eve.durability-bench
  "Durability spike bench (2026-10-27). One script, run against two trees:
   variant A = pristine main (the current version), B = the durable-commit
   fix (default :durable? true), C = the fix with :durable? false + an
   explicit sync! every K swaps (group commit). A-tree runs never touch
   sync! (it is resolved at runtime) and never pass :durable? — the same
   file compiles and runs on both trees.

   Modes:
     single  <variant>                      counter / ~1KB / ~64KB swap! loops
     cinit   <dir>                          create the contention domain, atom = 0
     cworker <dir> <variant> <n> <sync-every> <elapsed-out>
                                            join, wait for <dir>.go, n inc swaps,
                                            write own loop elapsed ms to <elapsed-out>
     cread   <dir>                          print the contention atom's value

   Bench roots come from BENCH_DIR (a scratch dir under ~/workspace)."
  (:require [eve.atom :as atom]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private sync!-fn (delay (resolve 'eve.atom/sync!)))

(defn- delete-recursively! [^java.io.File f]
  (when (.exists f)
    (doseq [c (reverse (file-seq f))] (.delete ^java.io.File c))))

(defn- open-domain [dir variant]
  (atom/persistent-atom-domain dir (case variant
                                     "C" {:durable? false}
                                     "J1" {:journal? true :journal-batch 1}
                                     "J10" {:journal? true :journal-batch 10}
                                     "J100" {:journal? true :journal-batch 100}
                                     "J1000" {:journal? true :journal-batch 1000}
                                     {})))

(defn- maybe-sync! [a variant i sync-every]
  (when (and (= variant "C") (pos? sync-every) (zero? (mod i sync-every)))
    (when-let [s @sync!-fn] (s a))))

;; ---------------------------------------------------------------------------
;; single-host

(defn- swap-loop! [a variant n sync-every step]
  (dotimes [i n]
    (step)
    (maybe-sync! a variant (inc i) sync-every)))

(defn- time-loop! [a variant n sync-every step]
  (let [t0 (System/nanoTime)]
    (swap-loop! a variant n sync-every step)
    (/ (double (- (System/nanoTime) t0)) 1e6)))

(defn- bench-size [variant size warm n]
  (let [dir (str (System/getenv "BENCH_DIR") "/single-" variant "-" (name size))
        _ (delete-recursively! (io/file dir))
        dom (open-domain dir variant)
        init (case size :counter 0 (apply str (repeat (case size :kb 1024 65536) "a")))
        a (atom/persistent-atom {:id :eve/main :persistent dir} init)
        vs (when (not= size :counter)
             [init (apply str (repeat (case size :kb 1024 65536) "b"))])
        idx (volatile! 0)
        step (case size
               :counter (fn [] (swap! a inc))
               (fn [] (swap! a (fn [_] (nth vs (mod (vswap! idx inc) 2))))))]
    (let [timing (when (str/starts-with? variant "J") (clojure.core/atom []))
          ;; find-var, not a static reference: this same file runs on
          ;; the pristine tree (variant A), whose eve.atom has no
          ;; *flush-timing*.
          tv (when timing (find-var 'eve.atom/*flush-timing*))
          ms (with-bindings (if tv {tv timing} {})
               (swap-loop! a variant warm 0 step)
               (time-loop! a variant n 10 step))
          us (/ (* ms 1000.0) n)]
      (println (pr-str {:mode :single :variant variant :size size :iters n
                        :ms ms :us-per-op us :ops-sec (/ 1e6 us)}))
      (when (and timing (seq @timing))
        (let [ts @timing]
          (println (pr-str {:mode :flush-latency :variant variant :size size
                            :flushes (count ts)
                            :mean-ms (/ (reduce + ts) (count ts))
                            :max-ms (reduce max ts)}))))
      (flush))
    (atom/close-atom-domain! dom)))

(defn- single [variant]
  (bench-size variant :counter 300 (parse-long (or (System/getenv "BENCH_N_COUNTER") "3000")))
  (bench-size variant :kb 100 (parse-long (or (System/getenv "BENCH_N_KB") "1000")))
  (bench-size variant :kb64 30 (parse-long (or (System/getenv "BENCH_N_KB64") "300"))))

;; ---------------------------------------------------------------------------
;; contention

(defn- cinit [dir]
  (delete-recursively! (io/file dir))
  (let [dom (atom/persistent-atom-domain dir)
        a (atom/persistent-atom {:id :eve/main :persistent dir} 0)]
    (when-let [s @sync!-fn] (s a))
    (atom/close-atom-domain! dom)
    (println :init-done)))

(defn- cworker [dir variant n sync-every out]
  (let [dom (open-domain dir variant)
        a (atom/persistent-atom {:id :eve/main :persistent dir} nil)
        go (io/file (str dir ".go"))]
    ;; join and warm the domain before the start signal, so the timed
    ;; window is the swap loop, not process startup
    @a
    (spit (str dir ".ready-" (.pid (java.lang.ProcessHandle/current))) "")
    (loop [waited 0]
      (when (and (not (.exists go)) (< waited 120000))
        (Thread/sleep 20)
        (recur (+ waited 20))))
    (let [timing (when (str/starts-with? variant "J") (clojure.core/atom []))
          tv (when timing (find-var 'eve.atom/*flush-timing*))
          t0 (System/nanoTime)]
      (with-bindings (if tv {tv timing} {})
        (dotimes [i n]
          (swap! a inc)
          (maybe-sync! a variant (inc i) sync-every)))
      (spit out (str (/ (double (- (System/nanoTime) t0)) 1e6)))
      (when (and timing (seq @timing))
        (let [ts @timing]
          (spit (str out ".lat")
                (pr-str {:variant variant :flushes (count ts)
                         :mean-ms (/ (reduce + ts) (count ts))
                         :max-ms (reduce max ts)})))))
    (atom/close-atom-domain! dom)))

(defn- cread [dir]
  (let [dom (atom/persistent-atom-domain dir)
        a (atom/persistent-atom {:id :eve/main :persistent dir} nil)]
    (println :value @a)
    (atom/close-atom-domain! dom)))

(defn -main [mode & args]
  (case mode
    "single" (single (first args))
    "cinit" (cinit (first args))
    "cworker" (let [[dir variant n sync-every out] args]
                (cworker dir variant (parse-long n) (parse-long sync-every) out))
    "cread" (cread (first args)))
  (System/exit 0))
