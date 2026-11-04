(ns eve.thread-fold-rendezvous
  "fold and =>> on a group of processes : two processes — any two hosts — attached to one disk domain serve
   ONE domain pool through the meta-atom of a rendezvous atom, and each in turn folds over it. A turn's first chunks are
   held by a gate until four run at once, which takes both members' two threads: each process's fold runs chunks in
   the other process. Each fold, =>> and measured fold equals its sequential reduction; each member measures its
   meta-atom swaps at its first fold and publishes them (:measured :swap-us, P§5.4), the folder sizing its chunks from
   the slowest member's; the rendezvous atom's seal is unchanged.

     <host's runner> -m eve.thread-fold-rendezvous setup  <base>          the domain and the rendezvous atom; its seal
     <host's runner> -m eve.thread-fold-rendezvous member <base> <rid>    rid a folds first, then b
     <host's runner> -m eve.thread-fold-rendezvous verify <base>          the rendezvous atom's seal, from a fresh process

   Launched by eve/scripts/thread-fold-rendezvous.clj; each step prints EDN lines {:step ...}. The members' barriers
   and the gate live in a third atom of the domain (harness/state)."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread.domain :as dom]
            [eve.thread.fold :as fold])
  #?(:lpy (:import os)))

(def ttl 1500)

(def app-value
  {:app "eve.thread fold rendezvous" :xs (vec (range 200))
   :m (into {} (map (fn [i] [(keyword (str "k" i)) {:i i :s (str "v" i)}]) (range 100)))})

(defn- say [m] (println (pr-str m)))

(defn- exit! [code]
  #?(:cljs (js/process.exit code) :clje (erlang/halt code) :cljr (Environment/Exit code)
     :lpy (os/_exit code) :default (System/exit code)))

(defn- wait-for [ms what f]
  (let [deadline (+ (proc/now-ms) ms)]
    (loop []
      (or (f)
          (if (> (proc/now-ms) deadline)
            (throw (ex-info (str "fold rendezvous: timed out waiting for " what) {}))
            (do (proc/sleep-ms! 20) (recur)))))))

(defn- harness []
  (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain (:base (:dh dom/*running*))) "harness/state" {}))

(t/deftask plus ([] 0) ([a b] (+ a b)))

(t/deftask by-runtime ([] {}) ([a b] (merge-with + a b)))

(t/deftask gate-tally
  [acc [turn i]]
  ;; a chunk's first element waits at the gate until four chunks of this turn run at once (both members' two threads)
  (when (empty? acc)
    (let [h (harness)]
      (swap! h update-in [:gate turn] (fnil inc 0))
      (wait-for 600000 "four chunks at the gate" (fn [] (>= (get-in @h [:gate turn]) 4)))))
  (update acc (:rid (:dh dom/*running*)) (fnil inc 0)))

(t/defconveyed *spins* 50)

(t/deftask spin [x] (loop [i *spins* a x] (if (zero? i) a (recur (dec i) (mod (+ (* a 31) i) 1000003)))))

(t/deftask spin-plus [acc x] (+ acc (spin x)))

(defn- elements-of-ms
  "How many elements of *spins* steps take about ms here (at least 64): the measured fold's input outlasts the probe."
  [ms]
  (loop [k 64]
    (let [t0 (proc/now-ms) _ (reduce spin-plus 0 (range k)) dt (- (proc/now-ms) t0)]
      (if (or (>= dt 20) (>= k 1000000)) (max 64 (min 200000 (quot (* k ms) (max 1 dt)))) (recur (* 4 k))))))

(t/deftask sq [x] (* x x))
(t/deftask big? [x] (> x 50))
(t/deftask twice [x] [x x])
(t/deftask odd-x? [x] (odd? x))
(t/deftask tens-of [x] (quot x 10))

(defn- open [base]
  (let [d (ea/persistent-atom-domain base)]
    [(ea/lookup-or-create-mmap-atom! d "app/state" nil) (ea/lookup-or-create-mmap-atom! d "harness/state" {})]))

(defn setup [base]
  (let [d (ea/persistent-atom-domain base)
        a (ea/lookup-or-create-mmap-atom! d "app/state" app-value)
        h (ea/lookup-or-create-mmap-atom! d "harness/state" {})]
    (swap! h assoc :seal0 (ea/seal a))
    (say {:step :setup :seal (ea/seal a) :value-ok (= app-value @a)})))

(defn verify [base]
  (let [[a h] (open base)]
    (say {:step :verify :seal (ea/seal a) :seal0 (:seal0 @h) :same (= (ea/seal a) (:seal0 @h))
          :value-same (= app-value @a)})))

(def seam-input
  (vec (concat (mapcat (fn [i] (repeat (inc (mod i 3)) (+ 1 (* 2 i)))) (range 20)) [60] (range 61 100 2))))

(defn- turn [dh g rid]
  (let [;; the gated fold: its chunks claimed by both processes' threads
        by (fold/fold-in g 10 by-runtime gate-tally (mapv (fn [i] [rid i]) (range 80)))
        xs (vec (range 600))
        fold-ok (= (reduce + xs) (fold/fold-in g 25 plus plus xs))
        par (t/with-pool g (binding [fold/*chunk* 7]
                             (vec (t/=>> seam-input (map sq) (filter big?) (mapcat twice) (dedupe) (take-while odd-x?)
                                         (partition-by tens-of)))))
        seq-ref (vec (->> seam-input (map sq) (filter big?) (mapcat twice) (dedupe) (take-while odd-x?)
                          (partition-by tens-of)))
        plans (clojure.core/atom [])
        ys (vec (range (elements-of-ms 40)))
        measured (t/with-pool g (binding [fold/*on-plan* (fn [x] (clojure.core/swap! plans conj x))]
                                  (fold/fold plus spin-plus ys)))
        plan (first @plans)]
    {:step :fold :rid rid :ran-by by :count-ok (= 80 (reduce + (vals by)))
     :fold-ok fold-ok :=>>-ok (= seq-ref par) :measured-ok (= (reduce spin-plus 0 ys) measured)
     :plan (select-keys plan [:n :swap-us :swaps :carry-ns :probe :chunks :here])
     ;; (the rule's answer either way: chunks of n, or — no chunk worth its swaps — reduced here)
     :plan-sized (and (some? (:swap-us plan)) (= (fold/chunk-size (merge plan (:probe plan))) (:n plan)))
     :published (into {} (map (fn [[r d]] [r (get-in d [:measured :swap-us])])) (:runtimes (dom/registry dh)))}))

(defn member [base rid]
  (let [[a h] (open base)
        dh (dom/attach! a {:rid rid :ttl ttl :requires '[eve.thread-fold-rendezvous]})
        g (dom/pool dh :g {:threads 2})]
    (swap! h update :ready (fnil conj #{}) rid)
    (wait-for 600000 "both members" (fn [] (= 2 (count (:ready @h)))))
    (say {:step :joined :rid rid :t (proc/now-ms)})
    (doseq [who ["a" "b"]]
      (if (= rid who)
        (do (say (turn dh g rid))
            (swap! h update :turned (fnil conj #{}) rid))
        (wait-for 900000 (str who "'s turn") (fn [] (contains? (:turned @h) who)))))
    (say {:step :done :rid rid :seal-same (= (ea/seal a) (:seal0 @h)) :value-same (= app-value @a)})
    (swap! h update :finished (fnil conj #{}) rid)
    ;; (both stay until both are done: a member's threads serve the other's turn)
    (wait-for 600000 "both members done" (fn [] (= 2 (count (:finished @h)))))
    (dom/detach! dh)))

(defn -main [& [cmd base rid]]
  (let [[ok? e] (t/attempt (fn []
                             (case cmd
                               "setup" (setup base)
                               "verify" (verify base)
                               "member" (member base rid))))]
    (when-not ok? (say {:step :error :cmd cmd :rid rid :error e}))
    (t/shutdown-default-pools!)
    (exit! (if ok? 0 1))))

;; basilisp's run -n loads the namespace with *main-ns* naming it (eve's basilisp runner does the same)
#?(:lpy (when (= *main-ns* 'eve.thread-fold-rendezvous) (apply -main *command-line-args*)))
