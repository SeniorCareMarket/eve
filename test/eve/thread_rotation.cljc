(ns eve.thread-rotation
  "The multi-runtime rotation (the EVE-THREAD plan's S3, on lane ET-2's domain cell): processes of any of eve's hosts
   attach to one disk domain's rendezvous atom and serve ONE domain pool; the coordinator role passes along a turn
   order kept in the domain (a harness atom), and each coordinator in turn submits the same work to the pool — collatz
   lengths of 1..n in descriptors of c — and waits on it, the pool spanning every member. Every turn's sum must be the
   one computed alone; the receipts say which member's threads ran each descriptor.

     <host's runner> -m eve.thread-rotation member <base> <rid> <order> <n> <c>    order: the rids, comma-separated

   Each step prints EDN lines {:step ...} the launcher (eve/scripts/thread-rotation.clj) reads."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.string :as str]
            [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-host :as th]
            [eve.thread.domain :as dom])
  #?(:lpy (:import os)))

(def ttl 3000)

(defn- say [m] (println (pr-str m)))

(defn- exit! [code]
  #?(:cljs (js/process.exit code) :clje (erlang/halt code) :cljr (Environment/Exit code)
     :lpy (os/_exit code) :glj (os.Exit code) :default (System/exit code)))

(defn collatz-len
  "The number of steps from x to 1."
  [x]
  (loop [x x k 0] (if (== x 1) k (recur (if (even? x) (quot x 2) (inc (* 3 x))) (inc k)))))

(t/deftask chunk-sum [lo hi]
  {:lo lo :sum (reduce + 0 (map collatz-len (range lo hi))) :ran (:rid (:dh dom/*running*))})

(defn- wait-for [ms what f]
  (let [deadline (+ (proc/now-ms) ms)]
    (loop []
      (or (f)
          (if (> (proc/now-ms) deadline)
            (throw (ex-info (str "rotation: timed out waiting for " what) {}))
            (do (proc/sleep-ms! 20) (recur)))))))

(defn- coordinate! [g rid n c]
  (let [t0 (proc/now-ms)
        fs (mapv (fn [lo] (t/submit g 'eve.thread-rotation/chunk-sum [lo (min (inc n) (+ lo c))] {:retry :restart}))
                 (range 1 (inc n) c))
        vs (mapv deref fs)
        wall (- (proc/now-ms) t0)]
    {:coordinator rid :driving (select-keys (th/driving) [:runtime :block :wake]) :n n :c c
     :descriptors (count fs) :wall-ms wall
     :ok (= (reduce + 0 (map collatz-len (range 1 (inc n)))) (reduce + 0 (map :sum vs)))
     :ran-by (frequencies (map :ran vs))
     :attempts (frequencies (map (fn [f] (:attempts (t/descriptor-of f))) fs))}))

(defn member [base rid order n c]
  (let [order (str/split order #",")
        d (ea/persistent-atom-domain base)
        a (ea/lookup-or-create-mmap-atom! d "rotation/app" {:app "eve.thread rotation"})
        h (ea/lookup-or-create-mmap-atom! d "rotation/state" {})
        dh (dom/attach! a {:rid rid :ttl ttl :requires '[eve.thread-rotation]})
        g (dom/pool dh :rot {:threads 2 :retry :restart})]
    (swap! h update :ready (fnil conj #{}) rid)
    (wait-for 600000 "every member" (fn [] (every? (set (:ready @h)) order)))
    (say {:step :joined :rid rid :t (proc/now-ms) :runtimes (vec (sort (keys (:runtimes (dom/registry dh)))))})
    ;; the coordinator role, turn by turn: the turn's member takes the :coordinator lease (an election any member
    ;; could win; the turn order decides who asks), runs the work, records its reading, and passes the turn on
    (loop []
      (let [at (or (:at @h) 0)]
        (when (< at (count order))
          (if (= rid (nth order at))
            (do (wait-for 60000 "the coordinator lease" (fn [] (dom/elect! dh :coordinator)))
                (let [reading (coordinate! g rid n c)]
                  (say (assoc reading :step :turn :at at))
                  (dom/resign! dh :coordinator)
                  (swap! h (fn [v] (-> v (update :readings (fnil conj []) reading) (assoc :at (inc at)))))))
            (proc/sleep-ms! 20))
          (recur))))
    ;; every member keeps serving until the last turn's work is done; then each leaves
    (swap! h update :done (fnil conj #{}) rid)
    (wait-for 600000 "every member done" (fn [] (every? (set (:done @h)) order)))
    (let [rs (:readings @h)]
      (say {:step :member-done :rid rid :turns (count rs) :coordinators (mapv :coordinator rs)
            :all-ok (every? :ok rs) :ran-by (apply merge-with + (map :ran-by rs))
            :value-same (= {:app "eve.thread rotation"} @a)}))
    (dom/detach! dh)))

(defn -main [& [cmd base rid order n c]]
  (let [[ok? e] (t/attempt (fn []
                             (case cmd
                               "member" (member base rid order (read-string (str n)) (read-string (str c))))))]
    (when-not ok? (say {:step :error :cmd cmd :rid rid :error e}))
    (t/shutdown-default-pools!)
    (exit! (if ok? 0 1))))

;; basilisp's run -n loads the namespace with *main-ns* naming it (eve's basilisp runner does the same)
#?(:lpy (when (= *main-ns* 'eve.thread-rotation) (apply -main *command-line-args*)))
