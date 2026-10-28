(ns eve.thread-ladder
  "The network rung over real HTTP (C3 across processes), the remote member's side. Three processes, started
   by eve/scripts/thread-ladder.clj: a STORE (eve.thread-ladder-gateway store: blobs behind presigned URLs — an object store's
   part), the GATEWAY member G (eve.thread-ladder-gateway gateway: attached to a disk domain in the window, serving its
   atoms on eve.net's routes) and the REMOTE member R, here, on any host with an http adapter:

     <host's runner> -m eve.thread-ladder remote <endpoint>

   R opens the rendezvous atom as a network atom at G's gateway — its developer's choice of rung — and joins the one
   registry there; G's work runs on R's threads and R's on G's; G's disk writes and R's network writes of one counter
   all count; a hello R tries to write over the network is refused. G and R keep their barriers in a third atom
   (harness/state) — G reaches it by the disk, R over the network. Each step prints an EDN line {:step ...}."
  (:require [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread.domain :as dom]
            [eve.thread.meta :as mt]
            [eve.thread.net :as net]
            [eve.thread.rung :as rung])
  #?(:lpy (:import os)))

(def ttl 4000)

(def app-value
  {:app "eve.thread ladder" :xs (vec (range 100))
   :m (into {} (map (fn [i] [(keyword (str "k" i)) {:i i :s (str "v" i)}]) (range 50)))})

(def domain-id "d1")
(def api-key "ladder-key")

(defn say [m] (println (pr-str m)))

(defn exit! [code]
  #?(:cljs (js/process.exit code) :clje (erlang/halt code) :cljr (Environment/Exit code)
     :lpy (os/_exit code) :default (System/exit code)))

;; what crosses by name, in G's process and R's
(t/deftask who [i]
  (let [dh (:dh dom/*running*)] {:i i :ran (:rid dh) :via (dom/via dh)}))

(defn wait-for [ms what f]
  (let [deadline (+ (proc/now-ms) ms)]
    (loop []
      (or (f)
          (if (> (proc/now-ms) deadline)
            (throw (ex-info (str "ladder: timed out waiting for " what) {}))
            (do (proc/sleep-ms! 25) (recur)))))))

(defn summary
  "{ran-by {via n}} of collected who descriptors, and whether every one is done with its own i."
  [xs]
  {:ok (every? (fn [[i x]] (and (= :done (:state x)) (= i (get-in x [:value :i])))) (map-indexed vector xs))
   :ran (frequencies (map (fn [x] [(get-in x [:value :ran]) (get-in x [:value :via])]) xs))})

(defn run-who!
  "n who descriptors submitted to pool p (of dh) in one swap, collected in order: their summary and the wall ms."
  [dh p n]
  (let [t0 (proc/now-ms)
        ids (dom/submit-all! p (mapv (fn [i] ['eve.thread-ladder/who [i]]) (range n)))
        xs (dom/collect dh ids :in-order)]
    (assoc (summary xs) :ms (- (proc/now-ms) t0))))

(defn remote [endpoint]
  (let [cfg {:endpoint endpoint :domain-id domain-id :api-key api-key}
        h (net/rung cfg "harness/state")
        hv (fn [] (rung/value h))
        hset! (fn [k v] (rung/-swap! h (fn [m] (assoc m k v))))
        t0 (proc/now-ms)
        dh (dom/attach! (net/rung cfg "app/state")
                        {:rid "R" :ttl ttl :requires '[eve.thread-ladder]
                         :transports [{:rung :net :endpoint endpoint :domain-id domain-id}]})
        t-attach (- (proc/now-ms) t0)
        g (dom/pool dh :g {:threads 2})
        g2 (dom/pool dh :g2 {:threads 0})
        counter (net/rung cfg "test/counter")]
    (say {:step :joined :via (dom/via dh) :attach-ms t-attach})
    (hset! :r-up true)
    ;; R submits to :g2, which only G's threads serve; G's submissions to :g run on R's threads meanwhile
    (wait-for 300000 "G up" (fn [] (:g-up (hv))))
    (let [p1 (run-who! dh g2 6)
          [ok? e] (t/attempt (fn [] (dom/swap-meta! dh (fn [v] [(mt/hello v "R" "forged-over-the-network") nil]))))]
      (say {:step :work :result p1 :registry (dom/registry dh) :network-hello {:ok ok? :message (:message e)}}))
    ;; one counter, two rungs at once: G's disk threads and R's network swaps
    (wait-for 600000 "G's counting" (fn [] (:g-counting (hv))))
    (let [t1 (proc/now-ms)]
      (dotimes [_ 20] (rung/-swap! counter (fn [v] (update v :n (fnil inc 0)))))
      (say {:step :counted :n 20 :ms (- (proc/now-ms) t1)}))
    (hset! :r-counted true)
    (wait-for 600000 "G's work" (fn [] (:g-done-work (hv))))
    (dom/detach! dh)
    (say {:step :r-done})
    (hset! :r-done true)))

(defn -main [& [mode & args]]
  (let [[ok? e] (t/attempt (fn [] (case mode "remote" (apply remote args))))]
    (when-not ok? (say {:step :error :mode mode :error e}))
    (exit! (if ok? 0 1))))

;; basilisp's run -n loads the namespace with *main-ns* naming it (eve's basilisp runner does the same)
#?(:lpy (when (= *main-ns* 'eve.thread-ladder) (apply -main *command-line-args*)))
