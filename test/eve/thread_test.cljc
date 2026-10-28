(ns eve.thread-test
  "eve.thread's battery : S1's identity rows (EVE-THREAD plan P§6.1), promoted — equal on every host — and
   a row for each pool kind, the executor protocol, the future type, conveyance and refusal, the descriptor's state
   machine and adoption. Every row reads eve.thread, the thread contract and the capabilities the adapter declares
   ((driving), (ships-closures?)) or the form a pool declares in its descriptor (:local or :routed) — never a host's
   name. The pools' isolate threads load this namespace (:requires): its tasks are what crosses a heap by name."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-host :as h]
            [eve.thread.cell :as cell]
            [eve.thread.descriptor :as d]
            [eve.thread.pool :as pool]))

;; ---------------------------------------------------------------------------
;; Workloads: registered tasks and a conveyed var (what an isolate runs by name)
;; ---------------------------------------------------------------------------

(t/defconveyed *scale* 1)

(def ^:dynamic *plain* 1)

(t/deftask collatz-len [n]
  (loop [n n c 0] (if (= n 1) c (recur (if (even? n) (quot n 2) (inc (* 3 n))) (inc c)))))

(t/deftask scaled [x] (* *scale* x))

(t/deftask boom [x] (throw (ex-info "boom" {:x x})))

(t/deftask where [] (t/pool-name (t/current-pool)))

(t/deftask nested [x] (+ x @(t/submit (t/current-pool) 'eve.thread-test/collatz-len [27])))

(t/deftask three [] 3)

(t/deftask sq [x] (* x x))

(t/deftask whoami [] (h/self))

(t/deftask nap [ms v] (proc/sleep-ms! ms) v)

(defonce ticks (atom {}))

(t/deftask tick [k] (get (swap! ticks update k (fnil inc 0)) k))

(t/deftask ticks-of [k] (get @ticks k 0))

;; thunks as tasks (invoke-all, invoke-any): they cross to an isolate by name on every host
(t/deftask four-later [] (nap 20 4))
(t/deftask boom-1 [] (boom 1))
(t/deftask boom-2 [] (boom 2))
(t/deftask slow [] (nap 300 :slow))

(def wait-ms 30000)

(def iso
  "The options every battery pool takes: isolates load this namespace; forms may cross (P§10 Q4's opt-in)."
  {:requires '[eve.thread-test] :forms true})

(defn- threads? [] (not= 0 (:max-threads (h/driving))))

(defn- form-of [p] (:form (t/pool-descriptor p)))

(defn- close! [& ps]
  (doseq [p ps]
    (t/shutdown! p)
    (is (t/await-termination p wait-ms) (str "pool " (t/pool-name p) " terminated"))))

(defn- refused [r] (when (false? (first r)) (get-in (second r) [:data :eve.thread/refused])))

(defn- forms-cross?
  "Can a form reach a battery pool's threads: they share this heap, or they can eval."
  []
  (or (h/ships-closures?) (:eval (h/driving)) (not (threads?))))

(defn- counted
  "A lazy seq of from, from + 1, ... that counts in realized how many elements were realized."
  [realized from]
  (lazy-seq (swap! realized inc) (cons from (counted realized (inc from)))))

;; ---------------------------------------------------------------------------
;; S1's identity rows (P§6.1): equal on every host
;; ---------------------------------------------------------------------------

(def s1-identity
  "The rows of P§6.1's JVM reading, :laziness made host-independent: pmap is unchunked, so realizing its first value
   submits the pool's size + 2 + 1 calls on every host (S1 read the host's chunk size there: 32, or 64 on clojerl)."
  {:future 3 :future-local 21 :nested 7 :nested-pool-of-1 6 :nested-task 112
   :pmap-task [2000 134100 '(0 1 7 2 5)] :pmap-tfn [3 6 9 12] :pmap-2colls [11 22 33] :pcalls [1 4]
   :pvalues [3 12 "ab"] :binding 10 :binding-task [10 20 30] :error [false "eve.thread: task failed: boom" {:x 7}]
   :with-pool [:p :q :p] :laziness 7})

(def form-rows
  "The rows whose work is a form (a future's body, a tfn): they reach an isolate only where it can eval."
  #{:future :future-local :nested :nested-pool-of-1 :pmap-tfn :pmap-2colls :pcalls :pvalues :binding :with-pool})

(defn- s1-rows [p q one]
  (let [k 3
        row (fn [f] (let [[ok? v] (t/attempt f)] (if ok? v [:refused (get-in v [:data :eve.thread/refused])])))]
    (t/with-pool p
      {:future (row (fn [] @(t/future (+ 1 2))))
       :future-local (row (fn [] @(t/future (* k 7))))
       :nested (row (fn [] @(t/future (+ 1 @(t/future (* 2 3))))))
       :nested-pool-of-1 (row (fn [] (t/with-pool one @(t/future (+ 1 @(t/future (+ 2 @(t/future 3))))))))
       :nested-task (row (fn [] @(t/submit p 'eve.thread-test/nested [1])))
       :pmap-task (row (fn [] (let [xs (doall (t/pmap collatz-len (range 1 2001)))]
                                [(count xs) (reduce + xs) (take 5 xs)])))
       :pmap-tfn (row (fn [] (vec (t/pmap (t/tfn [x] (* k x)) [1 2 3 4]))))
       :pmap-2colls (row (fn [] (vec (t/pmap (t/tfn [a b] (+ a b)) [1 2 3] [10 20 30]))))
       :pcalls (row (fn [] (vec (t/pcalls (t/tfn [] 1) (t/tfn [] (+ k 1))))))
       :pvalues (row (fn [] (vec (t/pvalues (+ 1 2) (* 3 4) (str "a" "b")))))
       :binding (row (fn [] (binding [*scale* 10] @(t/future *scale*))))
       :binding-task (row (fn [] (binding [*scale* 10] (vec (t/pmap scaled [1 2 3])))))
       :error (row (fn [] (let [[ok? e] (t/attempt (fn [] @(t/submit p boom [7])))]
                            [ok? (:message e) (-> e :data :data)])))
       :with-pool (row (fn [] [@(t/future (where)) (t/with-pool q @(t/future (where))) @(t/future (where))]))
       :laziness (row (fn [] (let [realized (atom 0)
                                   s (t/pmap collatz-len (counted realized 1))]
                               (first s)
                               @realized)))})))

(deftest s1-identity-rows
  (let [p (t/fixed-pool 4 (assoc iso :name :p))
        q (t/fixed-pool 2 (assoc iso :name :q))
        one (t/fixed-pool 1 (assoc iso :name :one))
        rows (s1-rows p q one)]
    (println "eve.thread S1 identity:" (:runtime (h/driving)) (vec (sort-by (fn [[k _]] (str k)) rows)))
    (doseq [[k want] s1-identity]
      (if (or (forms-cross?) (not (form-rows k)))
        (is (= want (get rows k)) (str "S1 row " k))
        (is (= [:refused :eval] (get rows k)) (str "S1 row " k ": a form, refused where the isolates cannot eval"))))
    (testing "the divergent row: a plain closure runs where the pool's threads share this heap, refused where not"
      (let [k 3
            r (t/with-pool p (t/attempt (fn [] (vec (t/pmap (fn [x] (* k x)) [1 2 3])))))]
        (if (= :local (form-of p))
          (is (= [true [3 6 9]] r))
          (do (is (= :closure (refused r)))
              (is (= "eve.thread: a closure cannot cross a heap boundary — register it (deftask) or write it as a tfn"
                     (:message (second r))))))))
    (close! p q one)))

;; ---------------------------------------------------------------------------
;; The descriptor: its state machine and reassignment (P§5.4)
;; ---------------------------------------------------------------------------

(deftest descriptor-state-machine
  (let [q (d/descriptor "x/1" :work {:pool :p :retry :restart})]
    (testing "a new descriptor is queued: its work may go anywhere"
      (is (= :queued (:state q)))
      (is (= :free (d/reassignment q))))
    (testing "claim: queued -> running, attempts + 1, a token; a second claim finds nothing"
      (let [r (d/claim q :t1 "k1" 10)]
        (is (= [:running :t1 "k1" 1] ((juxt :state :owner :claim :attempts) r)))
        (is (= :restart (d/reassignment r)) "a running descriptor moves only as a restart")
        (is (nil? (d/claim r :t2 "k2" 11)))
        (testing "blocked: it holds a stack; only a restart moves it"
          (let [b (d/block r "x/2")]
            (is (= [:blocked "x/2"] ((juxt :state :blocked-on) b)))
            (is (= :restart (d/reassignment b)))
            (is (= :running (:state (d/unblock b))))))
        (testing "complete: only with the claim's token"
          (is (= r (d/complete r "stale" true 5 12)) "a stale token changes nothing")
          (is (= [:done 5] ((juxt :state :value) (d/complete r "k1" true 5 12))))
          (is (= [:failed {:message "m"}] ((juxt :state :error) (d/complete r "k1" false {:message "m"} 12)))))
        (testing "adopt from a dead owner: :restart re-queues (no owner, no token), :never fails :owner-died"
          (let [a (d/adopt r :t1 20)]
            (is (= [:queued nil nil 1] ((juxt :state :owner :claim :attempts) a)))
            (is (= 2 (:attempts (d/claim a :t2 "k2" 21))) "the restart is attempt 2")
            (is (= r (d/adopt r :t9 20)) "only from its owner"))
          (let [n (d/adopt (assoc r :retry :never) :t1 20)]
            (is (= :failed (:state n)))
            (is (= {:owner-died :t1} (:data (:error n))))))
        (testing "cancel: a running descriptor is marked, never taken from its owner"
          (let [c (d/cancel r 30)]
            (is (= [:running true] ((juxt :state :cancel-requested) c)))
            (is (d/cancelled? c))
            (is (d/done? c))))))
    (testing "cancel of a queued descriptor: :cancelled; settled ones are not reassignable"
      (is (= :cancelled (:state (d/cancel q 1))))
      (is (nil? (d/reassignment (d/cancel q 1)))))
    (testing "every move off the table is refused"
      (doseq [[from to] [[:queued :done] [:done :running] [:cancelled :queued] [:failed :queued] [:queued :blocked]]]
        (let [[ok? e] (t/attempt (fn [] (d/step (assoc q :state from) to {})))]
          (is (false? ok?))
          (is (= [from to] (:eve.thread/illegal (:data e)))))))
    (is (= (set (keys d/transitions))
           #{[nil :queued] [:queued :running] [:queued :cancelled] [:running :blocked] [:blocked :running]
             [:running :done] [:blocked :done] [:running :failed] [:blocked :failed] [:running :queued]
             [:blocked :queued]})
        "P§5.4's table, exactly")))

;; ---------------------------------------------------------------------------
;; The future type (C3)
;; ---------------------------------------------------------------------------

(deftest the-future-is-an-eve-type
  (let [s (t/single-pool iso)]
    (testing "@, a bounded deref, realized?"
      (let [f (t/submit s nap [200 :slept])]
        (if (threads?)
          (do (is (= :not-yet (t/deref f 20 :not-yet)) "(deref f ms v): v when ms pass first")
              (is (false? (t/realized? f))))
          (is (= :slept (t/deref f 20 :not-yet)) "a host without threads: the waiter runs the work, bounded or not"))
        (is (= :slept @f))
        (is (true? (t/realized? f)))
        (is (true? (t/future-done? f)))
        (is (= :slept (t/deref f 20 :not-yet)))))
    (testing "future-cancel: a queued descriptor never runs; a done one cannot be cancelled"
      (let [k (str "cancel-" (proc/now-ms))
            blocker (t/submit s nap [300 :b])
            queued (t/submit s tick [k])]
        ;; a side effect's answer bound before its assertion: basilisp.test's is evaluates a (pred x) form twice
        (let [cancelled (t/future-cancel queued)] (is (true? cancelled)))
        (is (true? (t/future-cancelled? queued)))
        (is (true? (t/future-done? queued)) "a cancelled future is done (Java's isDone)")
        (let [[ok? e] (t/attempt (fn [] @queued))]
          (is (false? ok?))
          (is (contains? (:data e) :eve.thread/cancelled)))
        (is (= :b @blocker))
        (let [cancelled (t/future-cancel blocker)] (is (false? cancelled) "done: nothing to cancel"))
        (if (= :local (form-of s))
          (is (= 0 @(t/submit s ticks-of [k])) "the cancelled task never ran")
          (is (<= 0 @(t/submit s ticks-of [k]) 1)
              "isolates: work routed to a member is its (P§5.4's routing hint); the future answers cancelled"))))
    (testing "the descriptor is the future's state"
      (let [f (t/submit s three)]
        (is (= 3 @f))
        (is (= [:done 3 1] ((juxt :state :value :attempts) (t/descriptor-of f))))))
    (testing "promise and deliver"
      (let [pr (t/promise)]
        (is (false? (t/realized? pr)))
        (is (= :none (t/deref pr 10 :none)))
        (is (= pr (t/deliver pr 42)))
        (let [again (t/deliver pr 43)] (is (nil? again) "delivered once"))
        (is (= 42 @pr))
        (is (true? (t/realized? pr)))))
    (close! s)))

;; ---------------------------------------------------------------------------
;; The pools (C2)
;; ---------------------------------------------------------------------------

(deftest fixed-pool-threads
  (let [p (t/fixed-pool 3 iso)
        d (t/pool-descriptor p)]
    (is (= [:fixed 0 3] ((juxt :kind :rung :size) d)))
    (is (contains? (t/registry) (t/pool-name p)) "in the pool registry, as its descriptor")
    (if (threads?)
      ;; bounded derefs: they do not help, so every answer comes from a pool thread
      (let [who (set (map (fn [f] (t/deref f wait-ms :timeout)) (doall (for [_ (range 30)] (t/submit p whoami)))))
            members (let [d (t/pool-descriptor p)] (set (concat (:threads d) (:members d))))]
        (is (<= 1 (count who) 3) "at most its 3 threads ran the work")
        (is (every? members who) "the pool's own threads"))
      (is (= 3 @(t/submit p three)) "a host without threads: the waiter runs it"))
    (close! p)
    (is (t/terminated? p))))

(deftest single-pool-fifo
  (let [s (t/single-pool iso)
        k (str "fifo-" (proc/now-ms))
        fs (doall (for [_ (range 20)] (t/submit s tick [k])))]
    (is (= (range 1 21) (map deref fs)) "first in, first out, one at a time")
    (is (= 1 (count (distinct (map (fn [f] (:ran-on (t/descriptor-of f))) fs)))) "on one thread")
    (close! s)))

(deftest cached-pool-grows-and-keeps-alive
  (testing "the warm minimum follows the adapter's :spawn-ms"
    (let [c (t/cached-pool iso)
          ms (:spawn-ms (h/driving))]
      (if (> ms 5)
        (is (pos? (:core (t/pool-descriptor c))) "a slow start: threads kept warm")
        (is (zero? (:core (t/pool-descriptor c))) "a cheap start: none kept"))
      (close! c)))
  (let [c (t/cached-pool (assoc iso :core 0 :keep-alive-ms 150 :max 8))
        live (fn [] (count (:threads (t/pool-descriptor c))))]
    (if (and (threads?) (= :local (form-of c)))
      (let [fs (doall (for [_ (range 4)] (t/submit c nap [250 :ok])))
            ;; the most threads it had while the work ran (a thread's start is the host's: slow on a loaded basilisp —
            ;; measured 2026-10-19, under 2 at a fixed 100 ms)
            peak (loop [n 0 peak 0]
                   (if (or (>= peak 2) (> n 300)) peak (do (proc/sleep-ms! 10) (recur (inc n) (max peak (live))))))]
        (is (<= 2 peak 8) "it grew for work no idle thread took")
        (is (= [:ok :ok :ok :ok] (mapv deref fs)))
        (loop [n 0] (when (and (pos? (live)) (< n 100)) (proc/sleep-ms! 20) (recur (inc n))))
        (is (= 0 (live)) "idle threads ended after the keep-alive, down to the warm minimum")
        (is (= :ok @(t/submit c nap [1 :ok])) "and it grows again"))
      (is (= [:ok :ok] (mapv deref [(t/submit c nap [10 :ok]) (t/submit c nap [10 :ok])]))
          "isolates (no growth without a shared cell, ET-2) or no threads: the work still runs"))
    (close! c)))

(deftest per-task-pool-runs-tasks-at-once
  (let [p (t/per-task-pool iso)]
    @(t/submit p three)
    (if (threads?)
      (let [t0 (proc/now-ms)
            fs (doall (for [i (range 4)] (t/submit p nap [500 i])))
            vs (mapv deref fs)
            dt (- (proc/now-ms) t0)]
        (is (= [0 1 2 3] vs))
        (is (< dt 1500) (str "4 naps of 500 ms side by side (in sequence: 2,000): " dt " ms")))
      (is (= [0 1] (mapv deref [(t/submit p nap [1 0]) (t/submit p nap [1 1])]))))
    (close! p)))

(deftest scheduled-pool-timer
  (let [s (t/scheduled-pool 2 iso)]
    (testing "a delayed one-shot"
      (let [t0 (proc/now-ms)
            f (t/schedule! s three 150)]
        (is (false? (t/realized? f)))
        (is (= 3 @f))
        (is (<= 140 (- (proc/now-ms) t0)) "not before its delay")))
    (testing "cancelled before it is due: never runs"
      (let [k (str "sched-cancel-" (proc/now-ms))
            f (t/schedule! s tick [k] 200)]
        (let [cancelled (t/future-cancel f)] (is (true? cancelled)))
        (proc/sleep-ms! 300)
        (is (= 0 @(t/submit s ticks-of [k])))))
    (if (threads?)
      (doseq [[mode sched] [[:rate t/schedule-at-fixed-rate!] [:delay t/schedule-with-fixed-delay!]]]
        (testing (str "periodic, " mode)
          (let [k (str "period-" (name mode) "-" (proc/now-ms))
                f (sched s tick [k] 0 60)]
            (proc/sleep-ms! 400)
            (let [cancelled (t/future-cancel f)] (is (true? cancelled)))
            (let [n @(t/submit s ticks-of [k])]
              (is (<= 2 n 12) (str "ran " n " times in 400 ms at a 60 ms period"))
              (proc/sleep-ms! 200)
              (is (= n @(t/submit s ticks-of [k])) "cancel stopped it"))
            (is (= 3 @(t/submit s three)) "the pool still takes work"))))
      (let [r (t/attempt (fn [] (t/schedule-at-fixed-rate! s three 0 60)))]
        (is (= :no-threads (refused r)) "a host without threads: no periodic work")))
    (close! s)))

;; ---------------------------------------------------------------------------
;; The executor protocol
;; ---------------------------------------------------------------------------

(deftest executor-protocol
  (testing "shutdown!: later submissions refused, queued work still runs, then terminated"
    (let [s (t/single-pool iso)
          a (t/submit s nap [100 :a])
          b (t/submit s nap [10 :b])]
      (t/shutdown! s)
      (is (t/shutdown? s))
      (let [r (t/attempt (fn [] (t/submit s three)))]
        (is (= :shutdown (refused r)) "RejectedExecutionException's place"))
      (is (= [:a :b] [@a @b]))
      (is (t/await-termination s wait-ms))
      (is (t/terminated? s))))
  (testing "shutdown-now!: queued work cancelled, its ids answered"
    (let [s (t/single-pool iso)
          a (t/submit s nap [200 :a])
          qs (doall (for [_ (range 3)] (t/submit s three)))]
      (proc/sleep-ms! 50)
      (let [ids (t/shutdown-now! s)]
        (cond
          (not (threads?))
          (is (= (set (map (fn [f] (:id (t/descriptor-of f))) (cons a qs))) (set ids))
              "a host without threads: nothing had started, so everything is cancelled")
          (= :local (form-of s))
          (do (is (= (set (map (fn [f] (:id (t/descriptor-of f))) qs)) (set ids)))
              (is (every? t/future-cancelled? qs)))
          :else (is (= [] ids) "isolates: routed work is its member's (the routing hint), not queued here")))
      (is (t/await-termination s wait-ms))
      (when (threads?) (is (= :a (t/deref a wait-ms :timeout))))))
  (testing "invoke-all and invoke-any"
    (let [p (t/fixed-pool 3 iso)
          fs (t/invoke-all p [three four-later boom-1])]
      (is (every? t/future-done? fs))
      (is (= [3 4] (mapv deref (take 2 fs))))
      (is (false? (first (t/attempt (fn [] @(nth fs 2))))))
      (is (= 3 (t/invoke-any p [boom-1 three slow])))
      (let [[ok? e] (t/attempt (fn [] (t/invoke-any p [boom-1 boom-2])))]
        (is (false? ok?))
        (is (= "eve.thread: task failed: boom" (:message e))))
      (close! p))))

;; ---------------------------------------------------------------------------
;; Conveyance (C4) and the default pools
;; ---------------------------------------------------------------------------

(deftest conveyance-across-the-pool
  (let [p (t/fixed-pool 2 iso)
        strict (t/fixed-pool 1 (dissoc iso :forms))]
    (testing "a declared conveyed var travels everywhere; the rest of the frame only where the heap is shared"
      (is (= [10 20] (binding [*scale* 10] (vec (t/with-pool p (t/pmap scaled [1 2]))))))
      (when (forms-cross?)
        (is (= (if (= :local (form-of p)) 5 1) (binding [*plain* 5] @(t/with-pool p (t/future *plain*)))))))
    (testing "a plain closure: refused where the work crosses a heap, with the rule's message"
      (let [r (t/attempt (fn [] @(t/submit p (fn [] 1))))]
        (if (= :local (form-of p))
          (is (= [true 1] r))
          (is (= :closure (refused r))))))
    (testing "forms cross only where the pool allows them (P§10 Q4), task names always"
      (is (= 3 @(t/submit strict three)))
      (let [r (t/attempt (fn [] @(t/with-pool strict (t/future (+ 1 2)))))]
        (if (= :local (form-of strict))
          (is (= [true 3] r))
          (is (= :form (refused r))))))
    (testing "an argument that is not a value never crosses a heap"
      (let [r (t/attempt (fn [] @(t/submit p 'eve.thread-test/three-of [(fn [] 1)])))]
        (if (= :local (form-of p))
          (is (false? (first r)) "(no such task: refused at the claim)")
          (is (= :host-object (refused r))))))
    (close! p strict)))

(deftest default-pools
  (is (= 3 @(t/future-call three)) ":io, per task")
  (is (= [1 4 9] (vec (t/pmap sq [1 2 3]))) ":compute, the host's parallelism")
  (is (= [:eve.thread/compute :eve.thread/io] (sort (set (filter #{:eve.thread/io :eve.thread/compute}
                                                                  (keys (t/registry)))))))
  (t/shutdown-default-pools!))

;; ---------------------------------------------------------------------------
;; Helping, blocking, and adoption: where work may move
;; ---------------------------------------------------------------------------

(deftest the-helping-deref-and-blocked-state
  (let [one (t/fixed-pool 1 iso)
        two (t/fixed-pool 2 iso)]
    (testing "a waiting thread runs the queued descriptor it waits for (queued work may go anywhere)"
      (let [busy (t/submit one nap [300 :busy])
            f (t/submit one three)]
        (is (= 3 @f))
        (when (= :local (form-of one))
          (is (= (h/self) (:ran-on (t/descriptor-of f))) "it ran here, on the waiting thread"))
        @busy))
    (when (and (threads?) (= :local (form-of two)))
      (testing "a pool thread waiting on running work is :blocked on it (it holds a stack: never moved)"
        (let [inner (t/submit two nap [400 :inner])
              _ (proc/sleep-ms! 50)
              outer (t/submit two (t/tfn [] @inner))]
          (proc/sleep-ms! 150)
          (let [dd (t/descriptor-of outer)]
            (is (= :blocked (:state dd)))
            (is (= (:id (t/descriptor-of inner)) (:blocked-on dd)))
            (is (= :restart (d/reassignment dd))))
          (is (= :inner @outer))
          (is (= :done (:state (t/descriptor-of outer)))))))
    (close! one two)))

(def death (atom 0))

(defn dying-entry
  "A pool thread that dies holding a descriptor (what adoption is for): it takes the first one it can — from the cell
   (local) or from its mailbox (routed) — and throws without completing it."
  [boot]
  (let [me (h/self)]
    (if (keyword? (:pool boot))
      (let [p (pool/find-pool (:pool boot))
            c (cell/-cell p)]
        (cell/call-in-loop
         p
         (fn []
           (pool/joined! p me cell/*loop*)
           (loop []
             (if (cell/claim-next-held! c)
               (throw (ex-info "this thread dies holding a descriptor" {:who :adoption-test}))
               (do (cell/-idle! c me) (h/receive! 2000) (recur)))))))
      (loop []
        (let [m (h/receive! -1)]
          (if (and (map? m) (= :task (:eve.thread/op m)))
            (throw (ex-info "this isolate dies holding a descriptor" {:who :adoption-test}))
            (recur)))))))

(deftest a-dead-threads-descriptors-are-adopted
  (when (threads?)
    (testing ":retry :restart — re-queued and run again from the start, elsewhere"
      (let [p (t/fixed-pool 1 (assoc iso :entry 'eve.thread-test/dying-entry :retry :restart))
            f (t/submit p three)]
        (is (= 3 (t/deref f wait-ms :timeout)))
        (let [dd (t/descriptor-of f)]
          (is (= 2 (:attempts dd)) "attempt 2: the restart")
          (is (= 1 (let [d (t/pool-descriptor p)] (count (concat (:threads d) (:members d)))))
              "the fixed pool refilled its place"))
        (close! p)))
    (testing ":retry :never — failed, :owner-died"
      (let [p (t/fixed-pool 1 (assoc iso :entry 'eve.thread-test/dying-entry))
            f (t/submit p three)
            [ok? e] (t/attempt (fn [] (t/deref f wait-ms :timeout)))]
        (is (false? ok?))
        (is (contains? (:data (:data e)) :owner-died))
        (is (= :failed (:state (t/descriptor-of f))))
        (is (= 3 @(t/submit p three)) "and the pool goes on")
        (close! p)))))

;; ---------------------------------------------------------------------------
;; Round trips: the thread conformance's bar (100 in under 100 ms), and the work a future costs
;; ---------------------------------------------------------------------------

(defn echo-entry
  "A raw thread for the round-trip row: answers {:reply addr :n n} with n (the adapter alone, no engine)."
  [_boot]
  (loop []
    (let [m (h/receive! -1)]
      (when-not (= m :eve.thread/stop)
        (when (map? m) (h/send! (:reply m) (:n m)))
        (recur)))))

(defn- median-of-5 [batch] (do (batch) (nth (vec (sort (repeatedly 5 batch))) 2)))

(deftest round-trips
  ;; A future's round trip is two messages and the engine's bookkeeping (descriptor, claim, settle, wait). The bar is
  ;; the thread conformance's — 100 round trips in under 100 ms — raised only where this host itself is slow, by one of
  ;; two bounds on the engine's work measured in this row: at most 300 of the host's operations per future (each a
  ;; swap! of a small map: basilisp's cost ~140 µs, measured), or at most 10 of the host's own raw message round trips
  ;; per future (the adapter alone, an echo thread: on nbb a task-sized message is most of a round trip, measured).
  ;; It catches a shape that costs more per future than its work — the spike engine's 7.3 ms on the BEAM, where a
  ;; message costs 6.5 µs — not a host that is slow at everything.
  (let [one (t/fixed-pool 1 iso)
        me (h/self)
        engine (median-of-5 (fn []
                              (let [t0 (proc/now-ms)]
                                (dotimes [_ 100]
                                  ;; a bounded deref does not help: the task crosses to the pool's thread and back
                                  (t/deref (t/submit one three) wait-ms :timeout))
                                (- (proc/now-ms) t0))))
        raw (when (threads?)
              (let [[w] (h/start-threads! 1 'eve.thread-test/echo-entry {} {:requires '[eve.thread-test]})
                    ms (median-of-5 (fn []
                                      (let [t0 (proc/now-ms)]
                                        (dotimes [i 100] (h/send! w {:reply me :n i}) (h/receive! wait-ms))
                                        (- (proc/now-ms) t0))))]
                (h/stop-threads! [w])
                (loop [] (let [m (h/receive! wait-ms)] (when-not (and (map? m) (= w (:eve.thread/exit m))) (recur))))
                ms))
        op-us (let [a (atom {:n 0}) t0 (proc/now-ms)]
                (dotimes [_ 1000] (swap! a update :n inc))
                (- (proc/now-ms) t0))
        bar (max 100 (* 30 op-us) (* 10 (or raw 0)))]
    (println "eve.thread round trips:" (:runtime (h/driving)) (form-of one) "ms per 100 futures (median of 5):" engine
             "· the adapter alone, ms per 100:" raw "· a host operation, µs:" op-us "· bar:" bar)
    (is (<= engine bar)
        (str "100 future round trips in " engine " ms: under the conformance's 100 ms, 300 host operations a future, or "
             "10 of its raw round trips (" bar " ms here)"))
    (close! one)))
