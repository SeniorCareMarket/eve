(ns eve.thread-domain-test
  "eve.thread's domain cell : pools, the registry, bring-up, election and the upgrade protocol kept in the
   meta-atom of a rendezvous atom on disk (eve.thread.domain). Each row opens a fresh domain and attaches two runtimes
   of this process to one rendezvous atom (their tickers and pump threads are this host's threads) — the same moves
   two processes make (the two-process rendezvous: test-e2e/eve/thread_rendezvous.cljc). Every row ends with the
   rendezvous atom's seal (eve.atom/seal: its root pointer, name hash and value's blocks, byte for byte) equal to the
   seal it had before any coordination. Rows read only the thread contract's declarations ((driving)), never a host's
   name; a host with no threads (thread/none) runs the rows that need none."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-host :as h]
            [eve.thread.cell :as c]
            [eve.thread.descriptor :as d]
            [eve.thread.domain :as dom]
            [eve.thread.meta :as mt]))

;; ---------------------------------------------------------------------------
;; Tasks: what crosses to any member by name
;; ---------------------------------------------------------------------------

(t/deftask sq [x] (* x x))

(t/deftask nap [ms v] (proc/sleep-ms! ms) v)

(t/deftask boom [x] (throw (ex-info "boom" {:x x})))

(t/deftask where [] (t/pool-name (t/current-pool)))

(t/deftask nested [x] (+ x @(t/submit (t/current-pool) 'eve.thread-domain-test/sq [x])))

;; Held work: a task that runs until the test releases it (a gate atom in the rendezvous atom's domain — neither the
;; rendezvous atom nor its meta-atom), so a runtime's death finds it running on every host however slow, and a task
;; that waits on another descriptor (it is :blocked meanwhile) until that one settles.

(defn- gate []
  (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain (:base (:dh dom/*running*))) "test/gate" {}))

(t/deftask hold [k v] (let [g (gate)] (loop [] (if (get @g k) v (do (proc/sleep-ms! 20) (recur))))))

(t/deftask wait-on [id] (:state (c/await! (dom/entry (:dh dom/*running*) id) -1)))

(defn- threads? [] (not= 0 (:max-threads (h/driving))))

(def ttl 600)

(def opts {:ttl ttl :requires '[eve.thread-domain-test]})

(defn- fresh-base []
  ;; (the same directory on every host this suite runs on)
  (str "/tmp/eve-thread-domain-" (rand-int 1000000000) "/d"))

(def app-value {:app "eve" :xs [1 2 3] :nested {:k #{:a :b} :n 42}})

(defn- rendezvous
  "A fresh domain's rendezvous atom holding app-value, and its seal before any coordination."
  []
  (let [dom (ea/persistent-atom-domain (fresh-base))
        a (ea/lookup-or-create-mmap-atom! dom "app/state" app-value)]
    [a (ea/seal a)]))

(defn- untouched [a seal0]
  (is (= seal0 (ea/seal a)) "the rendezvous atom's seal: as it was before any coordination")
  (is (= app-value @a)))

(defn- close! [dom-of-a]
  (ea/close-atom-domain! (ea/persistent-atom-domain (:base-path (ea/-domain-state dom-of-a)))))

(defn- until [ms f]
  (let [deadline (+ (proc/now-ms) ms)]
    (loop [] (or (f) (if (> (proc/now-ms) deadline) nil (do (proc/sleep-ms! 10) (recur)))))))

;; ---------------------------------------------------------------------------
;; The pairing
;; ---------------------------------------------------------------------------

(deftest the-meta-atom-is-a-second-atom-of-the-domain
  (let [[a seal0] (rendezvous)
        ma (ea/meta-atom a {:n 0})]
    (is (= "app/state" (ea/registered-name a)))
    (is (= "eve.meta/app/state" (ea/registered-name ma)) "registered under a reserved name made from the atom's own")
    (is (= (ea/registered-name ma) (ea/registered-name (ea/meta-atom a))) "the same atom whoever asks")
    (dotimes [_ 50] (swap! ma update :n inc))
    (is (= {:n 50} @ma))
    (untouched a seal0)
    (testing "its seal moves with every swap; the main atom's does not"
      (let [s1 (ea/seal ma)]
        (swap! ma update :n inc)
        (is (not= (:ptr s1) (:ptr (ea/seal ma))))))
    (close! a)))

;; ---------------------------------------------------------------------------
;; One registry, one pool, two runtimes
;; ---------------------------------------------------------------------------

(deftest two-runtimes-one-registry-one-pool
  (let [[a seal0] (rendezvous)
        local (t/fixed-pool 1 {:name ::local})
        r1 (dom/attach! a (assoc opts :rid "r1" :caps #{:x}))
        r2 (dom/attach! a (assoc opts :rid "r2"))
        n (if (threads?) 2 0)
        g1 (dom/pool r1 :g {:threads n :retry :restart})
        g2 (dom/pool r2 :g {:threads n})]
    (testing "the registry is the meta-atom's: both runtimes read the same one"
      (let [reg1 (dom/registry r1)
            reg2 (dom/registry r2)]
        ;; (two reads, one through each runtime: equal at a moment no lease was stretched between them)
        (is (until 30000 (fn [] (= (dom/registry r1) (dom/registry r2)))))
        (is (= ["r1" "r2"] (sort (keys (:runtimes reg1)))))
        (is (= [:x] (sort (get-in reg1 [:runtimes "r1" :caps]))))
        (is (= :restart (get-in reg1 [:pools :g :retry])) "the first registration's descriptor stands")
        (is (= :fixed (get-in reg1 [:runtimes "r1" :local-pools ::local :kind]))
            "a runtime's own pools are in its entry: the registry carries them")
        (when (threads?)
          (is (= {"r1" 2 "r2" 2} (into {} (map (fn [[k v]] [k (:threads v)])) (get-in reg1 [:pools :g :members])))))))
    (testing "a runtime's own registry caches the pool it claimed"
      (is (= 1 (:rung (t/pool-descriptor g1))))
      (is (= :domain (:form (t/pool-descriptor g2))))
      (is (= "r2" (:rid (t/pool-descriptor g2)))))
    (testing "futures: any member's thread runs a descriptor; its value written into the descriptor"
      (let [fs (mapv (fn [i] (t/submit g1 'eve.thread-domain-test/nap [30 i])) (range 12))]
        (is (= (vec (range 12)) (mapv deref fs)))
        (is (every? (fn [f] (= :done (:state (t/descriptor-of f)))) fs))))
    (when (threads?)
      (testing "r2 claims queued work r1 submitted: four held at once take both runtimes' threads (held by a gate, not
                a clock: on every host however slow)"
        (let [gate (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain (:base-path (ea/-domain-state a)))
                                                   "test/gate" {})
              fs (mapv (fn [i] (t/submit g1 'eve.thread-domain-test/hold [:two i])) (range 4))]
          (is (until 120000 (fn [] (every? (fn [f] (= :running (:state (t/descriptor-of f)))) fs))))
          (is (= ["r1" "r2"] (sort (set (map (fn [f] (:orid (t/descriptor-of f))) fs)))))
          (swap! gate assoc :two true)
          (is (= [0 1 2 3] (mapv deref fs))))))
    (testing "pmap, nested futures and with-pool over the domain pool"
      (is (= [0 1 4 9 16 25 36 49] (vec (t/with-pool g2 (t/pmap sq (range 8))))))
      (is (= 12 @(t/submit g2 'eve.thread-domain-test/nested [3])))
      (is (= :g @(t/submit g1 'eve.thread-domain-test/where []))))
    (testing "an error travels out of band, as data"
      (let [[ok? e] (t/attempt (fn [] @(t/submit g2 boom [7])))]
        (is (false? ok?))
        (is (= {:x 7} (get-in e [:data :data])))))
    (testing "a closure cannot cross: refused at submission"
      (let [[ok? e] (t/attempt (fn [] (t/submit g1 (fn [] 1))))]
        (is (false? ok?))
        (is (= :closure (get-in e [:data :eve.thread/refused])))))
    (testing "settled descriptors leave the meta-atom once their futures have read them"
      @(t/submit g1 'eve.thread-domain-test/sq [5])
      ;; (the lingering ones named: id, state, origin, task)
      (let [lingering (fn [] (vec (keep (fn [[id x]] (when (d/settled? x) [id (:state x) (:origin x) (get-in x [:work :task])]))
                                        (dom/read-meta r1 [:descs]))))]
        ;; each runtime's next swap takes them out — this thread's beat, or its ticker's when the ticker took the list
        (until 30000 (fn [] (dom/beat! r1) (dom/beat! r2) (empty? (lingering))))
        (is (= [] (lingering)))))
    (dom/detach! r2)
    (is (= ["r1"] (sort (keys (:runtimes (dom/registry r1))))) "a runtime that leaves is out of the registry")
    (dom/detach! r1)
    (t/shutdown! local)
    (untouched a seal0)
    (close! a)))

(deftest cancel-and-shutdown
  (let [[a seal0] (rendezvous)
        r1 (dom/attach! a (assoc opts :rid "r1"))
        idle (dom/pool r1 :idle)
        f (t/submit idle 'eve.thread-domain-test/sq [3])]
    ;; (bound first: a failing is evaluates its form again on some hosts)
    (let [cancelled? (t/future-cancel f)] (is (true? cancelled?) "a queued descriptor is cancelled"))
    (is (t/future-cancelled? f))
    (is (= :cancelled (:state (t/descriptor-of f))))
    (let [again? (t/future-cancel f)] (is (false? again?) "once"))
    (let [[ok? e] (t/attempt (fn [] @f))]
      (is (false? ok?))
      (is (some? (get-in e [:data :eve.thread/cancelled]))))
    (let [fs (mapv (fn [i] (t/submit idle 'eve.thread-domain-test/sq [i])) (range 3))]
      (is (= (mapv (fn [x] (:id (t/descriptor-of x))) fs) (t/shutdown-now! idle)) "shutdown-now!: the queued ids")
      (is (= [true true true] (mapv t/future-cancelled? fs)))
      (is (t/shutdown? idle))
      (is (t/await-termination idle 1000))
      (let [[ok? e] (t/attempt (fn [] (t/submit idle 'eve.thread-domain-test/sq [1])))]
        (is (false? ok?))
        (is (= :stopped (get-in e [:data :eve.thread/refused])))))
    (dom/detach! r1)
    (untouched a seal0)
    (close! a)))

;; ---------------------------------------------------------------------------
;; Leases and adoption: a runtime that dies
;; ---------------------------------------------------------------------------

(deftest a-dead-runtimes-work-is-adopted-on-lease-expiry
  (if-not (threads?)
    (is (zero? (:max-threads (h/driving))) "no threads: no runtime's pump to lose")
    (let [[a seal0] (rendezvous)
          gate (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain (:base-path (ea/-domain-state a))) "test/gate" {})
          r1 (dom/attach! a (assoc opts :rid "r1"))
          r2 (dom/attach! a (assoc opts :rid "r2"))
          _ (dom/pool r2 :g {:threads 3})
          ;; submitted by r1 (the survivor: a dead runtime's own futures die with it), run by r2's threads
          g (dom/pool r1 :g)
          inner (t/submit (dom/pool r1 :idle) 'eve.thread-domain-test/sq [2])
          restart (t/submit g 'eve.thread-domain-test/hold [:go :restarted] {:retry :restart})
          never (t/submit g 'eve.thread-domain-test/hold [:go :never] {:retry :never})
          blocked (t/submit g 'eve.thread-domain-test/wait-on [(:id (t/descriptor-of inner))] {:retry :restart})
          state (fn [f] (select-keys (t/descriptor-of f) [:state :orid :attempts]))]
      (is (until 120000 (fn [] (= [{:state :running :orid "r2" :attempts 1} {:state :running :orid "r2" :attempts 1}
                                   {:state :blocked :orid "r2" :attempts 1}]
                                  (mapv state [restart never blocked]))))
          "r2's threads hold all three; the one waiting on another descriptor is :blocked")
      (testing "while r2's leases hold, nothing moves its work"
        (is (= [] (dom/adopt! r1))))
      (dom/halt! r2)
      (dom/pool r1 :g {:threads 2})
      (is (until 120000 (fn [] (and (= :failed (:state (t/descriptor-of never)))
                                    (= "r1" (:orid (t/descriptor-of restart)) (:orid (t/descriptor-of blocked))))))
          "once r2's leases expire: adopted, and r1 claims the restarts")
      (swap! gate assoc :go true)
      (t/future-cancel inner)
      (let [v-restart @restart
            [ok? e] (t/attempt (fn [] @never))]
        (testing ":retry :restart — re-queued after the lease expired, run again from the start elsewhere"
          (is (= :restarted v-restart))
          (is (= {:state :done :orid "r1" :attempts 2} (state restart))))
        (testing ":retry :never — failed :owner-died"
          (is (false? ok?))
          (is (= "r2" (first (clojure.string/split (str (get-in e [:data :data :owner-died])) #"#"))) (pr-str e)))
        (testing "blocked work moved only as a restart: attempt 2, on r1"
          (is (= :cancelled @blocked) "its second attempt waited on the same descriptor, until it was cancelled")
          (is (= {:state :done :orid "r1" :attempts 2} (state blocked)))))
      (testing "the dead runtime is taken out of the registry"
        (is (until 10000 (fn [] (dom/beat! r1) (= ["r1"] (sort (keys (:runtimes (dom/registry r1)))))))))
      (dom/detach! r1)
      (untouched a seal0)
      (close! a))))

;; ---------------------------------------------------------------------------
;; Bring-up
;; ---------------------------------------------------------------------------

(deftest bring-up-is-a-desired-set-claimed-in-dependency-order
  (let [[a seal0] (rendezvous)
        r1 (dom/attach! a (assoc opts :rid "r1"))
        r2 (dom/attach! a (assoc opts :rid "r2"))
        n (if (threads?) 1 0)
        ;; bring-up's own starters (:runtime, :pool) and an application's (:svc)
        starters (assoc (dom/starters)
                        :svc (fn [dh c] @(t/submit (dom/pool dh :g) 'eve.thread-domain-test/sq [(get-in c [:spec :x])])))
        desired {:rt/r1 {:kind :runtime :for "r1"}
                 :rt/r2 {:kind :runtime :for "r2"}
                 :pool/g {:kind :pool :after #{:rt/r1} :for "r1" :spec {:name :g :threads n}}
                 :pool/g-r2 {:kind :pool :after #{:rt/r2 :pool/g} :for "r2" :spec {:name :g :threads n}}
                 :svc/sq {:kind :svc :after #{:pool/g-r2} :spec {:x 9}}}]
    (dom/desire! r1 desired)
    (is (= (set (keys desired)) (set (keys (dom/read-meta r1 [:components])))) "the whole set, in one swap")
    (let [mine1 (dom/start! r1 starters 200)
          mine2 (dom/start! r2 starters 5000)
          mine1b (dom/start! r1 starters 5000)
          comps (dom/read-meta r1 [:components])
          order (mt/bring-up-order {:components comps})]
      (is (= [:rt/r1 :pool/g] mine1) "r1 brings up what is for it until r2's part is needed")
      (is (= [:rt/r2 :pool/g-r2 :svc/sq] mine2))
      (is (= [] mine1b))
      (is (mt/brought-up? {:components comps}))
      (is (= [:rt/r1 :pool/g :rt/r2 :pool/g-r2 :svc/sq] order) "the start, observable in the meta-atom's value")
      (is (= 81 (get-in comps [:svc/sq :result])))
      (is (= :g (get-in comps [:pool/g-r2 :result])))
      (when (threads?)
        (is (= {"r1" n "r2" n} (into {} (map (fn [[k v]] [k (:threads v)])) (dom/read-meta r1 [:pools :g :members])))
            "the pool the bring-up started: both runtimes' parts"))
      (is (= "r2#rt" (get-in comps [:svc/sq :owner]))))
    (testing "a cycle is refused, nothing swapped in"
      (let [[ok? _] (t/attempt (fn [] (dom/desire! r1 {:x {:after #{:y}} :y {:after #{:x}}})))]
        (is (false? ok?))
        (is (nil? (dom/read-meta r1 [:components :x])))))
    (dom/detach! r2)
    (dom/detach! r1)
    (untouched a seal0)
    (close! a)))

;; ---------------------------------------------------------------------------
;; Election by lease, and the upgrade protocol
;; ---------------------------------------------------------------------------

(deftest election-by-lease
  (let [[a seal0] (rendezvous)
        r1 (dom/attach! a (assoc opts :rid "r1"))
        r2 (dom/attach! a (assoc opts :rid "r2"))]
    (is (true? (dom/elect! r1 :leader)))
    (is (false? (dom/elect! r2 :leader)))
    (is (= "r1" (dom/leader r1)))
    (when (threads?)
      (testing "held: renewed by the holder's ticker past its ttl"
        (proc/sleep-ms! (* 2 ttl))
        (is (= "r1" (dom/leader r2)))
        (is (false? (dom/elect! r2 :leader))))
      (testing "the holder dies: after its ttl anyone takes it, at a new term"
        (dom/halt! r1)
        ;; (its lease, as the fleet's slowest member stretched it: a slow host's is longer than ttl)
        (is (until 60000 (fn [] (dom/elect! r2 :leader))))
        (is (= "r2" (dom/leader r1)))
        (is (= 2 (dom/read-meta r2 [:leases :leader :term])))))
    (dom/resign! r2 :leader)
    (when-not (threads?) (dom/resign! r1 :leader))
    (is (nil? (dom/leader r2)))
    (dom/detach! r2)
    (when-not (threads?) (dom/detach! r1))
    (untouched a seal0)
    (close! a)))

(deftest the-upgrade-from-the-network-rung-to-the-disk
  ;; the network rung's stand-in: a second rendezvous atom of the same domain (its meta-atom the network atom's — the
  ;; network cell is ET-3's; and a ClojureScript process holds one mmap domain at a time)
  (let [[disk-a disk-seal] (rendezvous)
        disk-path (:base-path (ea/-domain-state disk-a))
        net-a (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain disk-path) "net/state" app-value)
        net-seal (ea/seal net-a)
        here {:machine "m1" :boot "b1"}
        ;; r2: attached to both rungs, advertising the disk domain; r1: joined on the network rung only
        r2-net (dom/attach! net-a (assoc opts :rid "r2" :host here :transports [{:rung :disk :domain disk-path}
                                                                                {:rung :net :endpoint "e"}]))
        r2-disk (dom/attach! disk-a (assoc opts :rid "r2" :host here))
        r1-net (dom/attach! net-a (assoc opts :rid "r1" :host here :transports [{:rung :net :endpoint "e"}]))
        open (fn [tr] (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain (:domain tr)) "app/state" nil))]
    (is (= [{:rid "r2" :transport {:rung :disk :domain disk-path}}] (dom/co-hosted r1-net)))
    (let [h (dom/hello! r1-net open)]
      (is (some? (:nonce h)))
      (is (= (:nonce h) (dom/read-meta r1-net [:runtimes "r1" :hello-nonce])) "the same nonce on the network rung")
      (is (= [] (dom/ack-hellos! r2-disk r2-disk))
          "only a nonce the network rung advertises is acknowledged: r2 reads r1's descriptor there")
      (is (= ["r1"] (dom/ack-hellos! r2-disk r2-net)))
      (let [r1-disk (dom/await-ack! h 2000)]
        (is (some? r1-disk) "acknowledged: r1 now pulls from pools homed on the disk")
        (is (= ["r1" "r2"] (sort (keys (:runtimes (dom/registry r2-disk))))))
        (is (= 16 @(t/submit (dom/pool r1-disk :local {:threads (if (threads?) 1 0)}) 'eve.thread-domain-test/sq [4])))
        (dom/detach! r1-disk)))
    (testing "a pool's home moves by drain: the old home refuses new work, naming the next"
      (let [p (dom/pool r2-net :moving)]
        (dom/migrate-pool! r2-net :moving {:rung :disk :domain disk-path})
        (let [[ok? e] (t/attempt (fn [] (t/submit p 'eve.thread-domain-test/sq [1])))]
          (is (false? ok?))
          (is (= :draining (get-in e [:data :eve.thread/refused])))
          (is (= {:rung :disk :domain disk-path} (get-in e [:data :next-home]))))))
    (doseq [dh [r1-net r2-disk r2-net]] (dom/detach! dh))
    (untouched net-a net-seal)
    (untouched disk-a disk-seal)
    (close! disk-a)))
