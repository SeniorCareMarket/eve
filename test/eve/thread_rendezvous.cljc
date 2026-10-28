(ns eve.thread-rendezvous
  "The two-process rendezvous (the proof): processes attached to one disk domain share ONE registry and ONE
   pool through the meta-atom of a rendezvous atom; each claims the other's queued work; when one is killed (kill -9,
   by the launcher: eve/scripts/thread-rendezvous) the survivor adopts what it held once its leases expire — a :restart
   descriptor run again from the start, a :never one failed :owner-died, a blocked one moved only as a restart — and the
   rendezvous atom's value and seal (eve.atom/seal: its root pointer, its name's hash, its value's blocks byte for byte)
   are what they were before any coordination.

     <host's runner> -m eve.thread-rendezvous setup  <base>            the domain and the rendezvous atom; its seal
     <host's runner> -m eve.thread-rendezvous member <base> <rid> <role>   role a (the survivor) or b (killed)
     <host's runner> -m eve.thread-rendezvous verify <base>            the rendezvous atom's seal, from a fresh process

   Each step prints EDN lines {:step ...} the launcher reads. The members' own barriers live in a third atom of the
   domain (harness/state): neither the rendezvous atom nor its meta-atom carries the harness."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread.cell :as c]
            [eve.thread.domain :as dom])
  #?(:lpy (:import os)))

(def ttl 1500)

(def app-value
  {:app "eve.thread rendezvous" :xs (vec (range 200))
   :m (into {} (map (fn [i] [(keyword (str "k" i)) {:i i :s (str "v" i)}]) (range 100)))})

(defn- say [m] (println (pr-str m)))

(defn- exit! [code]
  #?(:cljs (js/process.exit code) :clje (erlang/halt code) :cljr (Environment/Exit code)
     :lpy (os/_exit code) :glj (os.Exit code) :default (System/exit code)))

(t/deftask work [origin i ms]
  (proc/sleep-ms! ms)
  {:origin origin :i i :ran (:rid (:dh dom/*running*))})

(defn- harness []
  (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain (:base (:dh dom/*running*))) "harness/state" {}))

(t/deftask held
  [k v]
  ;; runs until the harness releases it: a runtime's death finds it running on every host, however slow
  (let [h (harness)] (loop [] (if (get @h k) {:v v :ran (:rid (:dh dom/*running*))} (do (proc/sleep-ms! 20) (recur))))))

(t/deftask waits
  [id]
  ;; waits on descriptor id (one no thread serves): this one is :blocked meanwhile
  {:v (:state (c/await! (dom/entry (:dh dom/*running*) id) -1)) :ran (:rid (:dh dom/*running*))})

(defn- open [base]
  (let [d (ea/persistent-atom-domain base)]
    [(ea/lookup-or-create-mmap-atom! d "app/state" nil) (ea/lookup-or-create-mmap-atom! d "harness/state" {})]))

(defn- wait-for [ms what f]
  (let [deadline (+ (proc/now-ms) ms)]
    (loop []
      (or (f)
          (if (> (proc/now-ms) deadline)
            (throw (ex-info (str "rendezvous: timed out waiting for " what) {}))
            (do (proc/sleep-ms! 20) (recur)))))))

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

(defn- futures-report [fs]
  (mapv (fn [f] (let [x (t/descriptor-of f)]
                  {:id (:id x) :state (:state x) :attempts (:attempts x) :orid (:orid x)
                   :error (get-in x [:error :class]) :owner-died (get-in x [:error :data :owner-died])}))
        fs))

(defn member [base rid role]
  (let [[a h] (open base)
        dh (dom/attach! a {:rid rid :ttl ttl :requires '[eve.thread-rendezvous]})
        g (dom/pool dh :g {:threads 2 :retry :restart})]
    (swap! h update :ready (fnil conj #{}) rid)
    (wait-for 600000 "both members" (fn [] (= 2 (count (:ready @h)))))
    (say {:step :joined :rid rid :t (proc/now-ms)})
    ;; one registry: each writes what it reads; the launcher compares them
    (let [reg (dom/registry dh)]
      (say {:step :registry :rid rid :registry reg})
      (swap! h assoc-in [:registry rid] true))
    (wait-for 600000 "both registries" (fn [] (= 2 (count (:registry @h)))))
    ;; each submits queued work to the one pool; both pull
    (let [fs (mapv (fn [i] (t/submit g 'eve.thread-rendezvous/work [rid i 40])) (range 24))
          vs (mapv deref fs)]
      (say {:step :work :rid rid :ran-by (frequencies (map :ran vs)) :ok (= (range 24) (map :i vs))}))
    (swap! h update :worked (fnil conj #{}) rid)
    (wait-for 600000 "both members' work" (fn [] (= 2 (count (:worked @h)))))
    ;; that share is the members' speeds' (a fast member's threads pull first: a slow host's may pull none). Claims
    ;; across processes are shown under a gate: four held at once take both members' two threads — a's four first,
    ;; then b's — so each member's threads run work the other submitted, on every pair of hosts however unequal
    (doseq [[who k] [["a" :cross-a] ["b" :cross-b]]]
      (if (= rid who)
        (let [fs (mapv (fn [i] (t/submit g 'eve.thread-rendezvous/held [k i])) (range 4))]
          (wait-for 600000 "four held" (fn [] (every? (fn [f] (= :running (:state (t/descriptor-of f)))) fs)))
          (swap! h assoc k true)
          (let [vs (mapv deref fs)]
            (say {:step :cross :rid rid :ran-by (frequencies (map :ran vs)) :ok (= (range 4) (map :v vs))})))
        (wait-for 600000 (str who "'s four held") (fn [] (get @h k)))))
    (case role
      "b"
      ;; b serves pool :held (three threads) and announces once its threads hold what a submitted there
      (let [_ (dom/pool dh :held {:threads 3})]
        (wait-for 600000 "the held work" (fn [] (:held-ids @h)))
        (let [ids (:held-ids @h)]
          (wait-for 600000 "b's threads to hold three descriptors"
                    (fn [] (let [ds (map (fn [id] (dom/read-meta dh [:descs id])) ids)]
                             ;; (the three a submitted first: b's blocked thread may also help with the queued pair)
                             (and (every? (fn [x] (= rid (:orid x))) (take 3 ds))
                                  (= [:running :running :blocked] (mapv :state (take 3 ds)))))))
          (say {:step :holding :rid rid :t (proc/now-ms)
                :states (mapv (fn [id] (select-keys (dom/read-meta dh [:descs id]) [:id :state :orid :attempts])) ids)})
          ;; the launcher kills this process now; it waits here, beating, until it does
          (proc/sleep-ms! 600000)))
      "a"
      ;; a submits to :held (b's), waits for b to hold it, then serves :held itself: queued work it claims, what b
      ;; held it adopts on b's lease expiry
      (let [held (dom/pool dh :held)
            inner (t/submit (dom/pool dh :idle) 'eve.thread-rendezvous/work ["-" 0 0])
            fs [(t/submit held 'eve.thread-rendezvous/held [:release :restart-1] {:retry :restart})
                (t/submit held 'eve.thread-rendezvous/held [:release :never] {:retry :never})
                (t/submit held 'eve.thread-rendezvous/waits [(:id (t/descriptor-of inner))] {:retry :restart})
                ;; (queued behind b's three: a claims them, short, so its threads are free for what it adopts)
                (t/submit held 'eve.thread-rendezvous/work ["a" 1 50] {:retry :restart})
                (t/submit held 'eve.thread-rendezvous/work ["a" 2 50] {:retry :restart})]]
        (swap! h assoc :held-ids (mapv (fn [f] (:id (t/descriptor-of f))) fs))
        (wait-for 300000 "b holding" (fn [] (= 3 (count (filter (fn [f] (= "b" (:orid (t/descriptor-of f)))) (take 3 fs))))))
        (let [t-held (proc/now-ms)
              _ (dom/pool dh :held {:threads 2})
              ;; adoption: the first moment one of b's three is back in :queued, failed, or claimed by a
              t-adopt (wait-for 300000 "adoption"
                                (fn [] (when (some (fn [f] (let [x (t/descriptor-of f)]
                                                             (or (not= "b" (:orid x)) (= :failed (:state x)))))
                                                   (take 3 fs))
                                         (proc/now-ms))))
              ;; all three of b's adopted (a holds the restarts, the :never one failed), then the work is released
              _ (wait-for 300000 "the three adopted"
                          (fn [] (let [[x y z] (map t/descriptor-of (take 3 fs))]
                                   (and (= "a" (:orid x) (:orid z)) (= :failed (:state y))))))
              _ (swap! h assoc :release true)
              _ (t/future-cancel inner)
              outcomes (mapv (fn [f] (let [[ok? v] (t/attempt (fn [] @f))] (if ok? v {:failed (:message v)}))) fs)
              reg (dom/registry dh)]
          (say {:step :survived :rid rid :t-held t-held :t-adopt t-adopt :outcomes outcomes
                :descriptors (futures-report fs) :runtimes (vec (keys (:runtimes reg)))
                :seal (ea/seal a) :seal0 (:seal0 @h) :seal-same (= (ea/seal a) (:seal0 @h))
                :value-same (= app-value @a)})
          (dom/detach! dh))))))

(defn -main [& [cmd base rid role]]
  (let [[ok? e] (t/attempt (fn []
                             (case cmd
                               "setup" (setup base)
                               "verify" (verify base)
                               "member" (member base rid role))))]
    (when-not ok? (say {:step :error :cmd cmd :rid rid :error e}))
    (t/shutdown-default-pools!)
    (exit! (if ok? 0 1))))

;; basilisp's run -n loads the namespace with *main-ns* naming it (eve's basilisp runner does the same)
#?(:lpy (when (= *main-ns* 'eve.thread-rendezvous) (apply -main *command-line-args*)))
