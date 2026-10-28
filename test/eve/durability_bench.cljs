(ns eve.durability-bench
  "Durability spike bench, Node host (2026-10-27) — the CLJS mirror of
   bench/eve/durability_bench.clj. One source, compiled per tree:
   variant A = pristine main, B = the durable-commit fix (default
   :durable? true), C = :durable? false + explicit sync! every K swaps.
   eve.atom/sync! is referenced only on the C path (and the spike tree's
   cinit-free design never needs it elsewhere), so the base tree compiles
   this file with an undeclared-var warning and never calls it.

   Modes (argv after the script):
     single  <variant>
     cinit   <dir>
     cworker <dir> <variant> <n> <sync-every> <elapsed-out>
     cread   <dir>
   Bench roots come from BENCH_DIR."
  (:require [eve.atom :as atom]
            [clojure.string :as str]))

(def ^:private fs (js/require "fs"))

(defn- env [k] (aget (.-env js/process) k))

(defn- now-ms []
  (/ (js/Number (.bigint (.-hrtime js/process))) 1e6))

(defn- delete-recursively! [path]
  (.rmSync fs path #js {:recursive true :force true}))

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
    (atom/sync! a)))

;; ---------------------------------------------------------------------------
;; single-host

(defn- bench-size [variant size warm n]
  (let [dir (str (env "BENCH_DIR") "/single-" variant "-" (name size))
        _ (delete-recursively! dir)
        dom (open-domain dir variant)
        init (case size :counter 0 (apply str (repeat (case size :kb 1024 65536) "a")))
        a (atom/persistent-atom {:id :eve/main :persistent dir} init)
        vs (when (not= size :counter)
             [init (apply str (repeat (case size :kb 1024 65536) "b"))])
        idx (volatile! 0)
        step (case size
               :counter (fn [] (swap! a inc))
               (fn [] (swap! a (fn [_] (nth vs (mod (vswap! idx inc) 2))))))
        run (fn [m sync-every]
              (dotimes [i m]
                (step)
                (maybe-sync! a variant (inc i) sync-every)))]
    (run warm 0)
    (let [t0 (now-ms)]
      (run n 10)
      (let [ms (- (now-ms) t0)
            us (/ (* ms 1000.0) n)]
        (println (pr-str {:mode :single :variant variant :size size :iters n
                          :ms ms :us-per-op us :ops-sec (/ 1e6 us)}))
        (flush)))
    (atom/close-atom-domain! dom)))

(defn- single [variant]
  (bench-size variant :counter 50 (parse-long (or (env "BENCH_N_COUNTER") "3000")))
  (bench-size variant :kb 20 (parse-long (or (env "BENCH_N_KB") "1000")))
  (bench-size variant :kb64 5 (parse-long (or (env "BENCH_N_KB64") "300"))))

;; ---------------------------------------------------------------------------
;; contention

(defn- cinit [dir]
  (delete-recursively! dir)
  (let [dom (atom/persistent-atom-domain dir)
        _a (atom/persistent-atom {:id :eve/main :persistent dir} 0)]
    (atom/close-atom-domain! dom)
    (println :init-done)))

(defn- cworker [dir variant n sync-every out]
  (let [dom (open-domain dir variant)
        a (atom/persistent-atom {:id :eve/main :persistent dir} nil)
        go (str dir ".go")]
    @a ;; join + warm before the start signal
    (.writeFileSync fs (str dir ".ready-" (.-pid js/process)) "")
    (letfn [(poll []
              (if (.existsSync fs go)
                (do (let [t0 (now-ms)]
                      (dotimes [i n]
                        (swap! a inc)
                        (maybe-sync! a variant (inc i) sync-every))
                      (.writeFileSync fs out (str (- (now-ms) t0))))
                    (atom/close-atom-domain! dom)
                    (.exit js/process 0))
                (js/setTimeout poll 20)))]
      (poll))))

(defn- cread [dir]
  (let [dom (atom/persistent-atom-domain dir)
        a (atom/persistent-atom {:id :eve/main :persistent dir} nil)]
    (println :value @a)
    (atom/close-atom-domain! dom)))

(defn main [& _]
  (let [[mode & args] (drop 2 (.-argv js/process))]
    (case mode
      "single" (single (first args))
      "cinit" (cinit (first args))
      "cworker" (let [[dir variant n sync-every out] args]
                  (cworker dir variant (parse-long n) (parse-long sync-every) out))
      "cread" (cread (first args)))))

;; node-script :main is called with no args; argv carries the mode.
