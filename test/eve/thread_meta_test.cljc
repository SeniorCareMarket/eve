(ns eve.thread-meta-test
  "The meta-atom's state (eve.thread.meta) as pure functions of its value: every move a swap of the
   meta-atom applies, run here over a plain map — the same code eve.thread.domain runs inside the swap. Rows: the
   descriptor's transitions only (a property over random interleavings of several runtimes' submits, claims,
   completions, cancels, blocks, deaths and sweeps, from a seeded generator so every host draws the same sequence),
   adoption by restart type and lease, :after and scopes, the registry, bring-up in dependency order, election by
   lease, and the upgrade protocol's state."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.thread.descriptor :as d]
            [eve.thread.meta :as mt]
            [eve.thread.task :as task]))

(def t0 1000)

(defn- rt [rid] {:rid rid :ttl 100 :caps #{:x}})

(defn- base
  "Runtimes rids joined at t0, pool :g registered, each runtime a member with threads \"a\" and \"b\"."
  [& rids]
  (reduce (fn [m rid] (-> m (mt/join (rt rid) t0) (mt/heartbeat rid ["a" "b"] t0) (mt/join-pool :g rid ["a" "b"])))
          (mt/register-pool {} {:name :g :kind :domain :retry :never})
          rids))

(defn- run-any [_] true)

;; ---------------------------------------------------------------------------
;; A seeded generator: the same draws on every host (a host's rand differs)
;; ---------------------------------------------------------------------------

(defn- lcg [s] (mod (+ (* s 1103515245) 12345) 2147483648))

(defn- draws
  "n draws in [0, k) from seed."
  [seed n k]
  (loop [s seed i 0 out []]
    (if (= i n) out (let [s (lcg s)] (recur s (inc i) (conj out (mod (quot s 65536) k)))))))

;; ---------------------------------------------------------------------------
;; The descriptor table, under random interleavings
;; ---------------------------------------------------------------------------

(defn- step-op
  "One random move by runtime r (of rids) at time t: [m' note]."
  [m op r t]
  (let [ids (vec (sort (keys (:descs m))))
        pick (fn [k] (when (seq ids) (nth ids (mod k (count ids)))))]
    (case (mod op 8)
      0 (let [[m ids] (mt/submit m :g [{:task 'w :args [op]}] r {:retry (if (even? op) :restart :never)} t)] [m [:submit ids]])
      1 (let [[m xs] (mt/claim m :g r "a" 2 #{:x} run-any t)] [m [:claim (map :id xs)]])
      2 (let [id (pick op)
              dd (get-in m [:descs id])]
          ;; the owner completes with its own token, or a stale one
          [(first (mt/complete m id (if (odd? (quot op 8)) (:claim dd) -1) (even? (quot op 16)) op t)) [:complete id]])
      3 [(first (mt/cancel m (pick op) t)) [:cancel]]
      4 [(mt/block m (pick op) "x") [:block]]
      5 [(mt/unblock m (pick op)) [:unblock]]
      6 [(mt/heartbeat m r ["a" "b"] t) [:beat r]]
      7 (let [[m _] (mt/adopt-expired m t)] [m [:sweep]]))))

(deftest descriptor-moves-are-the-tables-only
  (doseq [seed [1 7 42 2026]]
    (let [rids ["r1" "r2" "r3"]
          ops (draws seed 600 10000)]
      (loop [m (base "r1" "r2" "r3") t t0 [op & more] ops seen {} bad []]
        (if (nil? op)
          (do (is (empty? bad) (str "seed " seed ": illegal moves " (take 3 bad)))
              (is (pos? (count (filter (fn [x] (= :done (:state x))) (vals (:descs m)))))
                  (str "seed " seed ": some work was done")))
          (let [r (nth rids (mod (quot op 7) 3))
                ;; r3 stops beating after a while: its threads' leases expire and its work is adopted
                t (+ t (mod op 13))
                [m2 _] (if (and (= r "r3") (> t (+ t0 1500)) (= 6 (mod op 8))) [m nil] (step-op m op r t))
                moves (keep (fn [[id x]]
                              (let [was (get seen id)
                                    now (:state x)]
                                (when (and (not= was now)
                                           (not (d/legal? was now))
                                           ;; a cancel of a running descriptor marks it, the state stays
                                           true)
                                  [id was now])))
                            (:descs m2))]
            (recur m2 t more (into seen (map (fn [[id x]] [id (:state x)])) (:descs m2)) (into bad moves))))))))

(deftest settled-stays-settled
  (let [[m [id]] (mt/submit (base "r1") :g [{:task 'w}] "r1" {} t0)
        [m [x]] (mt/claim m :g "r1" "a" 1 #{} run-any t0)
        [m ok] (mt/complete m id (:claim x) true 42 t0)]
    (is ok)
    (is (= :done (get-in m [:descs id :state])))
    (is (= [m false] (mt/complete m id (:claim x) false :late t0)) "a second completion changes nothing")
    (is (= [m false] (mt/cancel m id t0)) "nor a cancel")
    (is (= m (first (mt/adopt-expired m (+ t0 10000)))) "nor a sweep")))

;; ---------------------------------------------------------------------------
;; Claims, leases and adoption
;; ---------------------------------------------------------------------------

(deftest claim-is-first-in-first-out-and-batched
  (let [[m ids] (mt/submit (base "r1") :g (mapv (fn [i] {:task 'w :args [i]}) (range 5)) "r1" {} t0)
        [m xs] (mt/claim m :g "r1" "a" 2 #{} run-any t0)
        [m ys] (mt/claim m :g "r1" "b" 2 #{} run-any t0)]
    (is (= (subvec ids 0 2) (mapv :id xs)))
    (is (= (subvec ids 2 4) (mapv :id ys)))
    (is (= [(nth ids 4)] (get-in m [:q :g])))
    (is (every? (fn [x] (and (= :running (:state x)) (= 1 (:attempts x)) (= 1 (:claim x)))) (concat xs ys)))
    (is (= "r1#a" (:owner (first xs))))))

(deftest a-claim-takes-only-what-the-claimer-can-run
  (let [[m [i1 i2 i3]] (mt/submit (base "r1") :g [{:task 'a} {:task 'b} {:task 'a}] "r1" {} t0)
        [m [i4]] (mt/submit m :g [{:task 'a}] "r1" {:needs #{:gpu}} t0)
        [m xs] (mt/claim m :g "r1" "a" 10 #{:x} (fn [w] (= 'a (:task w))) t0)]
    (is (= [i1 i3] (mapv :id xs)) "task b is not registered here; i4 needs a cap r1 lacks")
    (is (= [i2 i4] (get-in m [:q :g])))
    (let [[_ ys] (mt/claim m :g "r1" "a" 10 #{:gpu} (fn [_] true) t0)]
      (is (= [i2 i4] (mapv :id ys))))))

(deftest leases-at-the-grain-of-the-thread
  (let [[m [id1 id2]] (mt/submit (base "r1" "r2") :g [{:task 'w} {:task 'w}] "r1" {:retry :restart} t0)
        [m _] (mt/claim m :g "r2" "a" 1 #{} run-any t0)
        [m _] (mt/claim m :g "r2" "b" 1 #{} run-any t0)
        ;; r2's runtime and thread b keep beating; thread a does not (it died)
        m (mt/heartbeat m "r2" ["b"] (+ t0 90))
        t (+ t0 150)
        m (mt/heartbeat m "r2" ["b"] t)]
    (is (mt/runtime-alive? m "r2" t))
    (is (not (mt/owner-alive? m (get-in m [:descs id1]) t)) "thread a's lease expired in a live runtime")
    (is (mt/owner-alive? m (get-in m [:descs id2]) t))
    (let [[m adopted] (mt/adopt-expired m t)]
      (is (= [id1] adopted) "only the dead thread's descriptor is adopted (a runtime-grain lease would miss it: F-5)")
      (is (= :queued (get-in m [:descs id1 :state])))
      (is (= [id1] (get-in m [:q :g])) "re-queued at the back")
      (is (= [] (second (mt/claim m :g "r1" "a" 1 #{} run-any t)))
          "r1 has not beaten since t0: its own lease expired, it claims nothing until it beats again")
      (let [m (mt/heartbeat m "r1" ["a"] t)
            [m [x]] (mt/claim m :g "r1" "a" 1 #{} run-any t)]
        (is (= 2 (:attempts x)) "the restart is attempt 2")
        (is (= [m false] (mt/complete m id1 1 true :late t)) "the dead attempt's late completion is ignored")
        (is (second (mt/complete m id1 2 true :ok t)))))))

(deftest blocked-work-moves-only-as-a-restart-after-its-lease
  (let [[m [r]] (mt/submit (base "r1" "r2") :g [{:task 'w}] "r1" {:retry :restart} t0)
        [m [n]] (mt/submit m :g [{:task 'w}] "r1" {:retry :never} t0)
        [m _] (mt/claim m :g "r2" "a" 2 #{} run-any t0)
        m (-> m (mt/block r "elsewhere") (mt/block n "elsewhere"))]
    (is (= :blocked (get-in m [:descs r :state])))
    (testing "while its owner's lease holds, nothing moves it"
      (is (= [] (second (mt/adopt-expired m (+ t0 50)))))
      (let [[_ xs] (mt/claim m :g "r1" "a" 5 #{} run-any (+ t0 50))] (is (empty? xs))))
    (testing "once it expires: :restart re-queued from the start, :never failed :owner-died"
      (let [[m adopted] (mt/adopt-expired m (+ t0 200))]
        (is (= (sort [r n]) (sort (set adopted))))
        (is (= :queued (get-in m [:descs r :state])))
        (is (nil? (get-in m [:descs r :blocked-on])))
        (is (= :failed (get-in m [:descs n :state])))
        (is (= {:owner-died "r2#a"} (get-in m [:descs n :error :data])))))))

(deftest a-slow-runtime-stretches-its-lease
  (let [m (base "r1")]
    (is (= 100 (get-in m [:runtimes "r1" :ttl])))
    (is (= 400 (get-in (mt/stretch-ttl m "r1" 400) [:runtimes "r1" :ttl])) "8 x a slow beat, say")
    (is (= 100 (get-in (mt/stretch-ttl m "r1" 50) [:runtimes "r1" :ttl])) "never lowered")
    (is (= 400 (mt/fleet-ttl (mt/stretch-ttl (base "r1" "r2") "r1" 400)))
        "the fleet's horizon is its slowest member's: every member renews at that cadence")
    (testing "the sweep is read first: due only when it would change something"
      (is (false? (mt/sweep-due? m t0 1000)))
      (is (true? (mt/sweep-due? m (+ t0 150) 1000)) "r1's lease expired: a sweep would take it out")
      (is (false? (mt/sweep-due? (mt/stretch-ttl m "r1" 400) (+ t0 150) 1000)) "not with its lease stretched"))))

(deftest a-runtime-that-leaves-or-expires
  (let [m (base "r1" "r2")
        [m [id]] (mt/submit m :g [{:task 'w}] "r1" {:retry :restart} t0)
        [m _] (mt/claim m :g "r2" "a" 1 #{} run-any t0)]
    (testing "leave: its descriptor and memberships go, its work adoptable at once"
      (let [m2 (mt/leave m "r2")]
        (is (not (mt/joined? m2 "r2")))
        (is (nil? (get-in m2 [:pools :g :members "r2"])))
        (is (= [id] (second (mt/adopt-expired m2 t0))))))
    (testing "expiry: a sweep takes out runtimes whose own lease expired"
      (let [m2 (mt/heartbeat m "r1" ["a" "b"] (+ t0 150))
            [m2 gone] (mt/expire-runtimes m2 (+ t0 150))]
        (is (= ["r2"] gone))
        (is (mt/joined? m2 "r1"))))))

(deftest after-and-scopes
  (let [[m [a b]] (mt/submit (base "r1") :g [{:task 'w} {:task 'w}] "r1" {} t0)
        [m [c]] (mt/submit m :g [{:task 'w}] "r1" {:after [a b]} t0)
        [m xs] (mt/claim m :g "r1" "a" 5 #{} run-any t0)]
    (is (= [a b] (mapv :id xs)) ":after holds c back until both inputs are done")
    (let [[m _] (mt/complete m a 1 true 1 t0)
          [m ys] (mt/claim m :g "r1" "a" 5 #{} run-any t0)]
      (is (empty? ys))
      (let [[m _] (mt/complete m b 1 true 2 t0)
            [_ zs] (mt/claim m :g "r1" "a" 5 #{} run-any t0)]
        (is (= [c] (mapv :id zs))))
      (testing "an input that failed: the dependent can never run, it is cancelled"
        (let [[m _] (mt/complete m b 1 false {:message "no"} t0)
              [m zs] (mt/claim m :g "r1" "a" 5 #{} run-any t0)]
          (is (empty? zs))
          (is (= :cancelled (get-in m [:descs c :state])))))))
  (testing "a failed element cancels its scope's queued descriptors, in the same move"
    (let [[m [x y z]] (mt/submit (base "r1") :g [{:task 'w} {:task 'w} {:task 'w}] "r1" {:scope "s1"} t0)
          [m [w]] (mt/submit m :g [{:task 'w}] "r1" {:scope "s2"} t0)
          [m _] (mt/claim m :g "r1" "a" 1 #{} run-any t0)
          [m _] (mt/complete m x 1 false {:message "boom"} t0)]
      (is (= [:failed :cancelled :cancelled :queued] (mapv (fn [i] (get-in m [:descs i :state])) [x y z w]))))))

(deftest pools-in-the-registry
  (let [m (base "r1" "r2")
        reg (mt/registry m)]
    (is (= ["r1" "r2"] (sort (keys (:runtimes reg)))))
    (is (= {"r1" {:threads 2 :tids ["a" "b"]} "r2" {:threads 2 :tids ["a" "b"]}} (get-in reg [:pools :g :members])))
    (is (nil? (get-in reg [:runtimes "r1" :thread-hb])) "leases are not the registry")
    (testing "the first registration stands"
      (is (= :never (get-in (mt/register-pool m {:name :g :retry :restart}) [:pools :g :retry]))))
    (testing "shutdown: refused submissions; shutdown-now: queued work cancelled"
      (let [[m ids] (mt/submit m :g [{:task 'w} {:task 'w}] "r1" {} t0)
            m2 (mt/shutdown-pool m :g)]
        (is (= [m2 nil] (mt/submit m2 :g [{:task 'w}] "r1" {} t0)))
        (is (= :shutdown (mt/refusal m2 :g)))
        (let [[m3 cancelled] (mt/shutdown-pool-now m :g t0)]
          (is (= ids cancelled))
          (is (mt/pool-idle? m3 :g)))))))

;; ---------------------------------------------------------------------------
;; Bring-up
;; ---------------------------------------------------------------------------

(def desired
  {:rt/a {:kind :runtime :for "r1"}
   :rt/b {:kind :runtime :for "r2"}
   :pool/g {:kind :pool :after #{:rt/a :rt/b}}
   :svc/x {:kind :svc :after #{:pool/g}}
   :svc/y {:kind :svc :after #{:pool/g :rt/a}}})

(defn- index-of [v x] (first (keep-indexed (fn [i y] (when (= x y) i)) v)))

(defn- walk
  "Claim and walk one component as rid; [m cid]."
  [m rid t]
  (let [[m cid] (mt/claim-component m rid "rt" (fn [_] true) t)]
    (if cid
      [(-> m (mt/advance-component cid (mt/owner-of rid "rt") :starting {})
           (mt/advance-component cid (mt/owner-of rid "rt") :running {}))
       cid]
      [m nil])))

(deftest bring-up-in-dependency-order
  (let [m (mt/desire (base "r1" "r2") desired)]
    (is (every? (fn [c] (= :desired (:state c))) (vals (:components m))) "one move puts the whole set in")
    (testing "a component is claimable only once every dependency runs, and only by the runtime it is for"
      (let [[_ cid] (mt/claim-component m "r1" "rt" (fn [c] (= :pool (:kind c))) t0)] (is (nil? cid)))
      (let [[_ cid] (mt/claim-component m "r1" "rt" (fn [_] true) t0)] (is (= :rt/a cid))))
    (let [m (loop [m m n 0]
              (let [[m c1] (walk m "r2" t0)
                    [m c2] (walk m "r1" t0)]
                (if (or c1 c2 (< n 1)) (recur m (inc n)) m)))
          order (mt/bring-up-order m)]
      (is (mt/brought-up? m))
      (is (= 5 (count order)))
      (doseq [[cid c] desired dep (:after c)]
        (is (< (index-of order dep) (index-of order cid)) (str dep " before " cid)))
      (testing "the start is in the value: each component's moves stamped with the clock"
        (is (every? (fn [c] (< (get-in c [:at :desired]) (get-in c [:at :claimed]) (get-in c [:at :starting])
                               (get-in c [:at :running])))
                    (vals (:components m))))))
    (testing "a cycle or an unknown dependency is refused: nothing swapped in"
      (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (mt/desire {} {:a {:after #{:b}} :b {:after #{:a}}})))
      (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (mt/desire {} {:a {:after #{:nope}}}))))
    (testing "a claimer that dies: its component is desired again"
      (let [[m cid] (mt/claim-component m "r2" "rt" (fn [_] true) t0)
            [m adopted] (mt/adopt-components m (+ t0 200))]
        (is (= [cid] adopted))
        (is (= :desired (get-in m [:components cid :state])))))
    (testing "only the owner may move a component, and only along the table"
      (let [[m cid] (mt/claim-component m "r1" "rt" (fn [_] true) t0)]
        (is (= m (mt/advance-component m cid (mt/owner-of "r2" "rt") :starting {})))
        (is (= m (mt/advance-component m cid (mt/owner-of "r1" "rt") :running {})) "claimed → running skips :starting")))))

;; ---------------------------------------------------------------------------
;; Election, transports and the upgrade protocol
;; ---------------------------------------------------------------------------

(deftest election-by-lease
  (let [[m won1] (mt/acquire {} :leader "r1" 100 t0)
        [m won2] (mt/acquire m :leader "r2" 100 (+ t0 50))]
    (is (and won1 (not won2)))
    (is (= "r1" (mt/holder m :leader (+ t0 50))))
    (let [[m renewed] (mt/acquire m :leader "r1" 100 (+ t0 90))]
      (is renewed)
      (is (= 1 (get-in m [:leases :leader :term])))
      (is (= [m false] (mt/acquire m :leader "r2" 100 (+ t0 150))) "renewed: still r1's")
      (let [[m won] (mt/acquire m :leader "r2" 100 (+ t0 200))]
        (is won "after expiry anyone takes it")
        (is (= 2 (get-in m [:leases :leader :term])))
        (is (= "r2" (mt/holder m :leader (+ t0 200))))
        (is (nil? (mt/holder (mt/release m :leader "r2") :leader (+ t0 200))))
        (is (= m (mt/release m :leader "r1")) "only its holder releases it")))))

(deftest transports-and-the-upgrade
  (let [h {:machine "m1" :boot "b1"}
        m (-> {}
              (mt/join {:rid "net-1" :ttl 100 :host h :transports [{:rung :net :endpoint "e"}]} t0)
              (mt/join {:rid "disk-1" :ttl 100 :host h :transports [{:rung :disk :domain "/d"} {:rung :net :endpoint "e"}]} t0)
              (mt/join {:rid "far" :ttl 100 :host {:machine "m2" :boot "b9"}
                        :transports [{:rung :disk :domain "/d"}]} t0))]
    (is (= [{:rid "disk-1" :transport {:rung :disk :domain "/d"}}] (mt/co-hosted m "net-1"))
        "co-hosted: the same machine and boot, with a disk or window transport (an equal path elsewhere is not)")
    (is (= {:rung :net :endpoint "e"} (mt/shared-home m ["net-1" "disk-1"])) "a pool's home is the lowest rung all share")
    (is (= {:rung :disk :domain "/d"} (mt/shared-home m ["disk-1" "far"])))
    (let [d (mt/hello {} "net-1" "n-42")]
      (is (not (mt/hello-acked? d "net-1" "n-42")))
      (is (= [d false] (mt/ack-hello d "net-1" "disk-1" "n-other")) "a nonce that is not the advertised one proves nothing")
      (let [[d ok] (mt/ack-hello d "net-1" "disk-1" "n-42")]
        (is ok)
        (is (mt/hello-acked? d "net-1" "n-42"))))
    (testing "a pool's home moves by drain"
      (let [m (mt/register-pool {} {:name :p})
            m (mt/drain-pool m :p {:rung :disk :domain "/d"})]
        (is (= :draining (mt/refusal m :p)))
        (is (= {:rung :disk :domain "/d"} (get-in m [:pools :p :next-home])))))))

;; ---------------------------------------------------------------------------
;; What runtimes of different hosts compare: a task's form hash
;; ---------------------------------------------------------------------------

(deftest a-tasks-form-hash-is-the-same-on-every-host
  ;; the JVM's value (2026-10-19): a map and a set in the form print in one order on every host
  (is (= -2073570738
         (task/form-hash '([origin i ms] (proc/sleep-ms! ms)
                           {:origin origin :i i :ran (:rid (:dh dom/*running*)) :s #{:b :a "x" 3}}))))
  ;; a reader's own expansions: @h is (<the host's core>/deref h), #(...) a fn* whose params each reader names its way
  (is (= (task/form-hash '([h k] (deref h) (fn* [a b] (+ a b))))
         (task/form-hash '([h k] @h #(+ %1 %2)))
         -650136401)))
