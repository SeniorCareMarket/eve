(ns eve.perf-suite
  "eve's performance suite: the same workloads on every host, written once over eve's public API (no host named).

   Each workload runs in a doubling loop — 1, 2, 4 … operations — until one round has taken the time budget (default
   1000 ms), and that round is reported as one EDN line:

     {:perf :atom-swap-counter :ops 65536 :ms 1012 :ns-per-op 15441}

   so a fast host and a slow one both finish in about twice the budget per workload, and both report the same work's
   cost. The sizes are fixed (a 1,000-key map, a 200-key sorted set …): every host measures the same thing. A workload a
   host cannot run (no disk domains in a browser page) reports {:perf k :error msg}.

   Each host's runner calls run! (eve.runner.*-main perf; the JVM and ClojureScript entries likewise);
   scripts/perf-all.clj runs them all and collects the lines.
   doc/hosts/ reads the results."
  (:refer-clojure :exclude [swap! reset! run!])
  (:require [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.core :as ec]
            [eve.deftype.int-map :as im]
            [eve.host :refer [swap! reset!]]
            [eve.process :as proc]
            [eve.sorted :as sorted]
            [eve.thread :as t]
            [eve.thread-host :as h]))

(def default-budget-ms 1000)

;; the thread workload's task: a name crosses to an isolate (a worker, a BEAM process), a closure does not
(t/deftask sq [x] (* x x))

(defn measure
  "op run in doubling rounds until one round takes budget-ms: {:ops n :ms t :ns-per-op x} of that round (one op first,
   untimed: a lazy load or a first compile is not the workload). With max-ops, no more than max-ops operations in all:
   the last round that fits is reported, :capped max-ops when it is under the budget."
  ([op budget-ms] (measure op budget-ms nil))
  ([op budget-ms max-ops]
   (op)
   (loop [n 1 done 1]
     (let [t0 (proc/now-ms)
           _ (dotimes [_ n] (op))
           dt (- (proc/now-ms) t0)
           done (+ done n)
           line {:ops n :ms dt :ns-per-op (quot (* 1000000 dt) n)}]
       (cond (or (>= dt budget-ms) (>= n 100000000)) line
             (and max-ops (> (+ done (* 2 n)) max-ops)) (assoc line :capped max-ops)
             :else (recur (* 2 n) done))))))

(defn- keys-1k [] (into {} (map (fn [k] [k k]) (range 1000))))

(defn workloads
  "[name setup op opts]: setup answers what op takes; dir is where a disk domain may be made; opts :max-ops caps a
   workload's operations. The two build workloads build a 200-element structure inside one swap! through the
   collection's constructor, a transient that begins from no tree: each version a write supersedes is freed, and the
   swap retires the structure it replaces by the tree's own walk, so a build leaves its tree only and the loop runs
   on its time budget like every other workload (RELEASE-CHECKLIST 9.25)."
  [dir]
  (let [i (atom 0)
        next-i (fn [] (clojure.core/swap! i inc))]
    [[:atom-swap-counter
      (fn [] (e/atom ::counter {:n 0}))
      (fn [a] (swap! a update :n inc))]
     [:atom-deref-small
      (fn [] (e/atom ::small {:n 0 :name "eve"}))
      (fn [a] @a)]
     [:atom-swap-map-1k
      (fn [] (let [a (e/atom ::map-1k {})] (reset! a (keys-1k)) a))
      (fn [a] (let [k (mod (next-i) 1000)] (swap! a assoc k (- k))))]
     [:atom-deref-map-1k
      (fn [] (let [a (e/atom ::deref-1k {})] (reset! a (keys-1k)) a))
      (fn [a] @a)]
     [:atom-$-get-map-1k
      (fn [] (let [a (e/atom ::get-1k {})] (reset! a (keys-1k)) a))
      (fn [a] (ea/read-pinned a (fn [v] (ec/get v (mod (next-i) 1000)))))]
     [:atom-reset-vec-1k
      (fn [] (e/atom ::vec-1k {}))
      (fn [a] (reset! a {:v (vec (range 1000))}))]
     [:sorted-set-build-200
      (fn [] (e/atom ::sorted {}))
      (fn [a] (swap! a (fn [_] {:s (apply sorted/sorted-set (range 200))})))]
     [:int-map-build-200
      (fn [] (e/atom ::int-map {}))
      (fn [a] (swap! a (fn [_] {:m (apply im/int-map (interleave (range 200) (range 200)))})))]
     [:disk-swap-counter
      (fn [] (let [path (str dir "/db-" (proc/now-ms))]
               (proc/ensure-parent-dir! path)
               {:path path :a (e/atom {:id :perf/hits :persistent path} {:n 0})}))
      (fn [{:keys [a]}] (swap! a update :n inc))]
     [:thread-submit-64
      (fn [] (or (t/current-pool) (t/default-pool :compute)))
      (fn [p] (reduce + 0 (map deref (mapv (fn [x] (t/submit p 'eve.perf-suite/sq [x])) (range 64)))))]]))

(defn- close! [state]
  (when (and (map? state) (:path state))
    (ea/close-atom-domain! (ea/persistent-atom-domain (:path state)))))

(defn run!
  "Every workload (or the ones named), each line printed as it ends; the lines. opts: :budget-ms, :dir (where a disk
   domain is made, default target/perf-suite), :only (a set of workload names)."
  ([] (run! {}))
  ([{:keys [budget-ms dir only]}]
   (let [budget (or budget-ms default-budget-ms)
         dir (or dir "target/perf-suite")]
     (vec
      (for [[k setup op opts] (workloads dir)
            :when (or (nil? only) (contains? only k))]
        (let [[ok? v] (h/attempt (fn []
                                   (let [state (setup)]
                                     (try (merge {:perf k} (measure (fn [] (op state)) budget (:max-ops opts)))
                                          (finally (close! state))))))
              line (if ok? v {:perf k :error (str (or (ex-message v) v))})]
          (println (pr-str line))
          line))))))
