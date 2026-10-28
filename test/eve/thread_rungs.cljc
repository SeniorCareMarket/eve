(ns eve.thread-rungs
  "Readings per rung (the brief's §8), run by eve/scripts/thread-rungs.clj on any host:

     <runner> -m eve.thread-rungs swap    <base> <n>               swap! inc on one atom n times: µs a swap
     <runner> -m eve.thread-rungs netswap <endpoint> <n> <size>    the same through a network rung (a gateway's): size
                                          small (a counter) or meta (a busy meta-atom's shape, ~300 blocks)
     <runner> -m eve.thread-rungs worker  <base> <threads>         serve pool :g until harness/state says :stop
     <runner> -m eve.thread-rungs coord   <base> <n> <ms> <drivers...>
                                          n naps of ms on pool :g, collected by each driver in turn: wall ms, reads of
                                          the meta-atom, and a wake's latency

   Each prints EDN lines {:step ...}. The disk and window placements are the same code at two paths (/tmp, /dev/shm)."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-host :as h]
            [eve.thread-ladder :as l]
            [eve.thread.domain :as dom]
            [eve.thread.net :as net]
            [eve.thread.rung :as rung]))

(defn- num-of [s]
  ;; (digits by their place in a string: no host's parse)
  (reduce (fn [n c] (+ (* 10 n) (count (take-while (fn [d] (not= d (str c))) ["0" "1" "2" "3" "4" "5" "6" "7" "8" "9"]))))
          0 (map str (str s))))

(t/deftask nap [ms i] (proc/sleep-ms! ms) i)

(defn swap-run [base n]
  (let [n (num-of n)
        a (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain base) "bench/counter" {:n 0})
        t0 (proc/now-ms)]
    (dotimes [_ n] (swap! a update :n inc))
    (let [ms (- (proc/now-ms) t0)]
      (l/say {:step :swap :n n :ms ms :us (quot (* 1000 ms) n) :final (:n @a)
              :placement (rung/placement-of base) :runtime (:runtime (h/driving))}))))

(defn- meta-like
  "A value shaped like a busy meta-atom's (two runtimes, a pool, 20 settled descriptors): ~300 blocks in eve.net's codec."
  []
  {:clock 0 :seq 20 :n 0
   :runtimes (into {} (map (fn [r] [r {:rid r :kind :jvm :hb 1792456000000 :ttl 3000 :thread-hb {"4" 1792456000000}
                                       :tasks #{'eve.thread-rungs/nap} :caps #{} :pools #{:g}
                                       :driving {:block :thread :wake :futex :parallel true}
                                       :transports [{:rung :window :domain "/dev/shm/eve/x/d"}]}])
                       ["r1" "r2"]))
   :pools {:g {:name :g :kind :domain :state :running :members {"r1" {:threads 2 :tids ["4" "5"]}}}}
   :q {:g []}
   :descs (into {} (map (fn [i] [(str "d" i) {:id (str "d" i) :state :done :pool :g :value i :attempts 1
                                              :work {:task 'eve.thread-rungs/nap :args [1 i] :form-hash 12345}
                                              :owner "r1#4" :orid "r1" :otid "4" :submitted 1792456000000
                                              :claimed 1792456000001 :done 1792456000002}])
                    (range 20)))})

(defn netswap-run [endpoint n size]
  (let [n (num-of n)
        nm (str "bench/" size)
        r (net/rung {:endpoint endpoint :domain-id l/domain-id :api-key l/api-key} nm (eve.net.platform/platform)
                    (if (= "meta" size) (meta-like) {:n 0}))
        ;; (a meta-sized swap changes what a claim does: the clock and one descriptor)
        f (fn [v] (-> v (update :n (fnil inc 0)) (update :clock (fnil inc 0))
                      (assoc-in [:descs (str "d" (mod (or (:n v) 0) 20)) :attempts] (or (:n v) 0))))
        _ (rung/-swap! r f)
        t0 (proc/now-ms)]
    (dotimes [_ n] (rung/-swap! r f))
    (let [ms (- (proc/now-ms) t0)]
      (l/say {:step :netswap :size size :n n :ms ms :us (quot (* 1000 ms) n) :final (:n (rung/value r))
              :runtime (:runtime (h/driving))}))))

(defn- harness [base] (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain base) "harness/state" {}))

(defn worker-run [base threads]
  (let [a (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain base) "app/state" {:app 1})
        dh (dom/attach! a {:rid "W" :ttl 3000 :requires '[eve.thread-rungs]})
        _ (dom/pool dh :g {:threads (num-of threads)})
        hh (harness base)]
    (l/say {:step :worker-up})
    (loop [] (when-not (:stop @hh) (proc/sleep-ms! 100) (recur)))
    (dom/detach! dh)
    (l/say {:step :worker-done})))

(defn coord-run [base n ms & drivers]
  (let [n (num-of n) ms (num-of ms)
        a (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain base) "app/state" {:app 1})
        dh (dom/attach! a {:rid "C" :ttl 3000 :requires '[eve.thread-rungs]})
        p (dom/pool dh :g {:threads 0})
        hh (harness base)]
    (l/wait-for 120000 "the worker's threads" (fn [] (pos? (count (dom/read-meta dh [:pools :g :members "W" :tids])))))
    ;; (a warm-up round: the worker's threads have claimed once)
    (dom/collect dh (dom/submit-all! p (mapv (fn [i] ['eve.thread-rungs/nap [1 i]]) (range 8))) :sweep)
    (doseq [d (map keyword drivers)]
      (let [r0 (dom/reads dh)
            t0 (proc/now-ms)
            ids (dom/submit-all! p (mapv (fn [i] ['eve.thread-rungs/nap [ms i]]) (range n)))
            [ok? xs] (t/attempt (fn [] (dom/collect dh ids d)))
            wall (- (proc/now-ms) t0)]
        (l/say (merge {:step :driver :driver d :n n :nap-ms ms :runtime (:runtime (h/driving)) :wake (:wake (h/driving))
                       :futex (rung/futex? (rung/-meta-rung (rung/eve-rung a) {}))}
                      (if ok?
                        {:wall-ms wall :reads (- (dom/reads dh) r0) :ok (= (vec (range n)) (mapv :value xs))}
                        ;; (a driver this host refuses: its work collected by a sweep, outside the reading)
                        (do (dom/collect dh ids :sweep) {:refused (:message xs)}))))))
    (swap! hh assoc :stop true)
    (dom/detach! dh)))

(defn -main [& [mode & args]]
  (let [[ok? e] (t/attempt (fn [] (case mode
                                    "swap" (apply swap-run args)
                                    "netswap" (apply netswap-run args)
                                    "worker" (apply worker-run args)
                                    "coord" (apply coord-run args))))]
    (when-not ok? (l/say {:step :error :mode mode :error e}))
    (l/exit! (if ok? 0 1))))

;; basilisp's run -n loads the namespace with *main-ns* naming it (eve's basilisp runner does the same)
#?(:lpy (when (= *main-ns* 'eve.thread-rungs) (apply -main *command-line-args*)))
