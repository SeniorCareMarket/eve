(ns eve.thread.pool
  "eve.thread's pools (EVE-THREAD plan P§2.2, P§5.2): threads pulling thread descriptors out of a rung-0 cell
   (eve.thread.cell), and the executor protocol over them.

     fixed-pool n      n threads
     single-pool       one thread: first in, first out, one at a time (only its own thread helps a waiter)
     cached-pool       grows on demand up to :max, an idle thread ends after :keep-alive-ms, a warm minimum (:core)
                       stays — by default from the adapter's :spawn-ms (a thread that is slow to start is kept)
     per-task-pool     a thread of its own for every task (:kind :virtual where the host has virtual threads;
                       BEAM processes are already that): a cached pool with no keep-alive — kept alive where a
                       thread is expensive to start (:spawn-ms), as P§2.2 says
     scheduled-pool n  one timer thread waiting in receive! with the next deadline, the due work run by n threads

   A pool's descriptor (pool-descriptor) is data — its name, kind, rung, threads and state — the shape of an entry of
   eve.thread/meta's :pools in a domain (P§5.4); the pool registry is these descriptors by name (registry).

   Where the threads are isolates (the routed form of rung 0) a cached or per-task pool keeps its warm minimum fixed —
   growth there needs a cell in shared memory (lane ET-2) — and a scheduled pool's timer runs its work itself.

   Threads are started by the thread that creates the pool, or — for a pool that grows — by its door thread, so every
   exit notice reaches a thread that expects it (the door also adopts what a dead thread held). A thread that died
   holding descriptors (its exit notice says so) is replaced in a fixed pool and its descriptors adopted: re-queued as
   restarts (:retry :restart) or failed with :owner-died (:retry :never, the default)."
  (:require [eve.thread-host :as h]
            [eve.thread.cell :as c]
            [eve.thread.descriptor :as d]
            [eve.thread.future :as fut]
            [eve.thread.task :as task]))

(defprotocol IExecutor
  (-submit [p f args opts] "a future of (apply f args) on the pool")
  (-shutdown! [p] "no new submissions; queued work still runs, then the threads end")
  (-shutdown-now! [p] "no new submissions; queued work cancelled (its descriptor ids answered); threads end after what they run")
  (-await-termination [p ms] "wait up to ms for the pool's threads to end; true when they all have")
  (-shutdown? [p])
  (-terminated? [p]))

;; The executor protocol's public names are fns over its methods: eve.thread's api table takes these as values, and a
;; protocol fn taken as a value before a later extension (eve.thread.domain's pools) does not see it on the JVM.
(defn shutdown! "no new submissions; queued work still runs, then the threads end" [p] (-shutdown! p))
(defn shutdown-now! "no new submissions; queued work cancelled (its descriptor ids answered)" [p] (-shutdown-now! p))
(defn await-termination "wait up to ms for the pool's threads to end; true when they all have" [p ms] (-await-termination p ms))
(defn shutdown? [p] (-shutdown? p))
(defn terminated? [p] (-terminated? p))

(defprotocol IScheduled
  (-schedule [p f args at period mode] "a future of f run at at (ms), then every period by mode :rate or :delay"))

(def ^:private local-entry-name 'eve.thread.pool/local-entry)
(def ^:private routed-entry-name 'eve.thread.pool/routed-entry)

(defonce pool-counter (atom 0))

(defn- pool-name [opts kind]
  (or (:name opts) (keyword "eve.thread" (str (name kind) "-" (swap! pool-counter inc)))))

(defn- max-threads [] (:max-threads (h/driving)))

(defn- cap [n] (let [m (max-threads)] (if (nil? m) n (min n m))))

(defn- routed? [n] (and (pos? n) (not (h/ships-closures?))))

(defn- task-namespaces
  "The namespaces that registered a task or a conveyed var here: what an isolate must load to run what crosses to it."
  []
  (distinct (keep (fn [sym] (when (namespace sym) (symbol (namespace sym))))
                  (concat (keys @task/tasks) (keys @task/conveyed)))))

(defn- thread-opts [p]
  (let [o (:opts p)]
    {:name (str "eve-" (name (:name p))) :kind (:kind o)
     :requires (vec (distinct (concat ['eve.thread.pool] (:requires o) (task-namespaces))))}))

;; ---------------------------------------------------------------------------
;; The pool
;; ---------------------------------------------------------------------------

(declare on-exit! member-update! start-threads! terminate-if-done!)

(defrecord Pool [name kind mode size cell state opts])

;; (protocols by extend-type: see eve.thread.cell's LocalCell)
(extend-type Pool
  c/IPoolEngine
  (-pool-name [p] (:name p))
  (-cell [p] (:cell p))
  (-help [p] (if (= :single (:kind p)) :members :any))
  (-threadless? [p] (:threadless @(:state p)))
  (-on-exit! [p notice] (on-exit! p notice))
  (-on-member-update! [p m] (member-update! p m)))

(defrecord RoutedCell [])

(extend-type RoutedCell
  c/ICell
  (-submit! [_ _] false)
  (-claim-next! [_] nil)
  (-claimable? [_] false)
  (-idle! [_ _] nil)
  (-unidle! [_ _] false)
  (-wake-all! [_] nil)
  (-drain! [_] []))

(defprotocol IPoolDescriptor
  (-pool-descriptor [p] "the pool as data"))

(defn pool-descriptor
  "The pool as data (P§5.4's pool descriptor): what a member isolate binds as *pool*; a domain pool's is its entry of
   the meta-atom's :pools, as this runtime last read it (eve.thread.domain)."
  [p]
  (-pool-descriptor p))

(defn- local-pool-descriptor
  [p]
  (let [s @(:state p)]
    {:name (:name p) :kind (:kind p) :rung 0 :form (:mode p) :size (:size p) :state (:state s)
     :threads (vec (:threads s)) :members (vec (:members s)) :forms (boolean (:forms (:opts p)))
     :retry (or (:retry (:opts p)) :never)
     :core (:core (:opts p)) :max (:max (:opts p)) :keep-alive-ms (:keep-alive-ms (:opts p))}))

(extend-type Pool
  IPoolDescriptor
  (-pool-descriptor [p] (local-pool-descriptor p)))

(defn registry
  "The pool registry of this runtime (an isolate's own, where threads are isolates): pool name → its descriptor. For a
   pool whose home is a meta-atom (eve.thread.domain) this is a cache of what this runtime claimed there — the
   meta-atom's :pools is the authority."
  []
  (into {} (map (fn [[k p]] [k (pool-descriptor p)])) @c/pools))

(defn find-pool [nm] (c/find-pool nm))

(defn- new-pool [kind n opts]
  (let [n' (cap n)
        mode (if (routed? n') :routed :local)
        p (->Pool (pool-name opts kind) kind mode n
                  (if (= mode :routed) (->RoutedCell) (c/local-cell))
                  (atom {:state :running :threads #{} :live 0 :pending-grow 0 :loops {} :pending-notices #{}
                         :members [] :rr 0 :threadless (zero? n') :door nil :timer nil :timer-done false})
                  opts)]
    (c/register-pool! p)
    [p n']))

;; ---------------------------------------------------------------------------
;; Threads: start, join, leave, die
;; ---------------------------------------------------------------------------

(defn- start-threads!
  "Started by this thread: n threads running entry (the pool's own unless given), their exit notices to come here."
  ([p n] (start-threads! p n nil))
  ([p n entry]
   (if (pos? n)
     (let [routed (= :routed (:mode p))
           e (or entry (if routed routed-entry-name local-entry-name))
           _ (when-not routed (swap! (:state p) update :live + n))
           addrs (h/start-threads! n e {:pool (if routed (pool-descriptor p) (:name p))} (thread-opts p))]
       (swap! (:state p) update :pending-notices into addrs)
       (c/register-threads! addrs (:name p))
       (when routed
         (swap! (:state p) update :members into addrs)
         (let [desc (pool-descriptor p)]
           (doseq [a (:members desc)] (h/send! a {:eve.thread/op :pool :name (:name p) :desc desc}))
           (swap! (:state p) update :told into (:members desc))))
       addrs)
     [])))

(defn joined!
  "A local pool's thread starts serving it (its loop state, so the pool can adopt what it holds if it dies)."
  [p me ls]
  (swap! (:state p) (fn [s] (-> s (update :threads conj me) (assoc-in [:loops me] ls))))
  nil)

(defn- terminate-if-done! [p]
  (swap! (:state p) (fn [s] (if (and (contains? #{:shutdown :stopped} (:state s)) (<= (:live s) 0)
                                     (or (nil? (:timer s)) (:timer-done s)))
                              (assoc s :state :terminated)
                              s))))

(defn- left!
  "A local pool's thread no longer serves it; counted? false when its count was already taken (a cached thread that
   retired)."
  [p me counted?]
  (swap! (:state p) (fn [s] (cond-> (-> s (update :threads disj me) (update :loops dissoc me))
                              counted? (update :live dec))))
  (terminate-if-done! p)
  nil)

(defn- live? [p]
  (let [s @(:state p)]
    (case (:state s)
      :running true
      :shutdown (or (c/-claimable? (:cell p)) (and (some? (:timer s)) (not (:timer-done s))))
      false)))

(defn- retire!
  "A cached thread's keep-alive expired: it may end while the pool keeps its warm minimum."
  [p]
  (let [core (or (:core (:opts p)) 0)]
    (some? (c/cas! (:state p) (fn [s] (when (> (:live s) core) (update s :live dec)))))))

(defn run-local-pump!
  "The loop a local pool's thread runs (local-entry's body; a wrapper entry may run it too)."
  [p]
  (let [me (h/self)
        r (c/pump-local! p me {:live? (fn [] (live? p))
                               :keep-alive-ms (:keep-alive-ms (:opts p))
                               :retire? (fn [] (retire! p))
                               :on-running (fn [ls] (joined! p me ls))})]
    (left! p me (not= r :retired))
    r))

(defn local-entry
  "The entry of a local pool's thread: boot {:pool name}."
  [boot]
  (run-local-pump! (find-pool (:pool boot))))

(defn- routed-view
  "This isolate's view of a routed pool (from its descriptor): its members, this isolate the home of what it routes."
  [desc]
  (let [p (->Pool (:name desc) (:kind desc) :routed (:size desc) (->RoutedCell)
                  (atom {:state :running :threads #{} :live 0 :pending-grow 0 :loops {} :pending-notices #{}
                         :members (vec (:members desc)) :rr 0 :threadless false :door nil :timer nil
                         :timer-done false :view true})
                  {:forms (:forms desc) :retry (:retry desc)})]
    (c/register-pool! p)
    p))

(swap! c/hooks assoc :pool-view routed-view)

(defn routed-entry
  "The entry of a routed pool's thread (an isolate): boot {:pool descriptor}."
  [boot]
  (let [p (routed-view (:pool boot))]
    (c/pump-routed! p)))

(defn- next-member [p]
  (let [s (swap! (:state p) update :rr inc)
        ms (:members s)]
    (when (empty? ms)
      (throw (ex-info (str "eve.thread: pool " (:name p) " has no thread to run on") {:eve.thread/refused :no-threads})))
    (nth ms (mod (:rr s) (count ms)))))

(defn- desc-for!
  "The pool as a routed task message carries it to member to: the whole descriptor the first time this isolate routes
   to to (a member binds it as *pool*), nothing after that — each pair of threads is first in, first out, so to has it
   by then, and a member runs only its own pool's work. (A whole descriptor costs the Node adapter's send ~4× a
   name: 188 against 43 µs on nbb, measured.)"
  [p to]
  (if (contains? (:told @(:state p)) to)
    {}
    (do (swap! (:state p) update :told (fnil conj #{}) to)
        (pool-descriptor p))))

(defn- route-next! [p e]
  (let [to (next-member p)] (c/route! e to (desc-for! p to))))

(defn- reroute-fn [p] (fn [e] (route-next! p e)))

(defn- member-update!
  "A routed pool's membership message, at a member isolate: the new members, or a member that ended (what this isolate
   routed to it adopted)."
  [p m]
  (case (:eve.thread/op m)
    :pool (swap! (:state p) assoc :members (vec (:members (:desc m))))
    :dead (do (swap! (:state p) update :members (fn [ms] (vec (remove #(= (:addr m) %) ms))))
              (doseq [e (c/routed-owned-by (:addr m))] (c/adopt! e (:addr m) (reroute-fn p))))
    nil))

(defn- broadcast! [p m] (doseq [a (:members @(:state p))] (h/send! a m)))

(defn- on-exit!
  "A thread of p ended — its exit notice, here at the thread that started it. Normal: it did its own leaving. Died
   (any other reason): its leaving done here, a fixed pool's place refilled while the pool runs, and every descriptor
   it held adopted."
  [p {addr :eve.thread/exit reason :reason}]
  (swap! (:state p) update :pending-notices disj addr)
  (c/unregister-thread! addr)
  (let [died? (not= :normal reason)
        s @(:state p)]
    (if (= :routed (:mode p))
      (do (swap! (:state p) update :members (fn [ms] (vec (remove #(= addr %) ms))))
          (when died?
            (when (and (= :running (:state s)) (contains? #{:fixed :single :cached :per-task} (:kind p)))
              (start-threads! p 1))
            (broadcast! p {:eve.thread/op :dead :name (:name p) :addr addr})
            (doseq [e (c/routed-owned-by addr)] (c/adopt! e addr (reroute-fn p))))
          (when (and (not= :running (:state @(:state p))) (empty? (:members @(:state p))))
            (swap! (:state p) assoc :state :terminated)))
      (cond
        (= addr (:door s)) nil
        (= addr (:timer s)) (when died? (swap! (:state p) assoc :timer-done true) (terminate-if-done! p))
        died?
        (let [ls (get-in s [:loops addr])]
          (left! p addr true)
          (when (and (= :running (:state s)) (contains? #{:fixed :single :scheduled} (:kind p)))
            (start-threads! p 1))
          (doseq [[e _] (when ls (c/running-entries ls))] (c/adopt! e addr nil)))))))

;; ---------------------------------------------------------------------------
;; The door: a growing pool's starter
;; ---------------------------------------------------------------------------

(defn door-entry
  "A cached or per-task pool's door thread: starts the pool's threads (its warm minimum, then one per :grow), so their
   exit notices come here; ends once the pool is shut down and every thread it started has ended."
  [boot]
  (let [p (find-pool (:pool boot))]
    (c/call-in-loop
     nil
     (fn []
       (start-threads! p (or (:core (:opts p)) 0))
       (loop [stopping false]
         (if (and stopping (empty? (disj (:pending-notices @(:state p)) (h/self))))
           :stopped
           (let [m (h/receive! -1)]
             (cond
               (= m :eve.thread/stop) (do (c/-wake-all! (:cell p)) (recur true))
               (and (map? m) (= :grow (:eve.thread/op m)))
               (do (swap! (:state p) update :pending-grow dec)
                   (when (= :running (:state @(:state p))) (start-threads! p 1))
                   (recur stopping))
               :else (do (c/handle! m) (recur stopping))))))))))

(defn- grow!
  "A submission no idle thread took: one more thread, through the door, while under :max."
  [p]
  (let [mx (:max (:opts p))]
    (when (c/cas! (:state p) (fn [s] (when (and (= :running (:state s))
                                                (or (nil? mx) (< (+ (:live s) (:pending-grow s)) mx)))
                                       (update s :pending-grow inc))))
      (h/send! (:door @(:state p)) {:eve.thread/op :grow}))))

;; ---------------------------------------------------------------------------
;; Submitting
;; ---------------------------------------------------------------------------

(defn- refuse-if-shut! [p]
  (when-not (= :running (:state @(:state p)))
    (throw (ex-info (str "eve.thread: pool " (:name p) " is shut down: submission refused")
                    {:eve.thread/refused :shutdown :pool (:name p)}))))

(defn- new-entry [p work opts]
  (c/entry (d/descriptor (c/next-id "") work {:pool (:name p) :origin (h/self) :submitted (c/now)
                                              :retry (or (:retry opts) (:retry (:opts p)))})))

(defn- work-of [p f args]
  (if (= :routed (:mode p))
    (task/data-work f args (:forms (:opts p)) (:eval (h/driving)))
    (task/closure-work f args)))

(defn- submit* [p f args opts]
  (refuse-if-shut! p)
  (let [e (new-entry p (work-of p f args) opts)]
    (if (= :routed (:mode p))
      (route-next! p e)
      (when (and (not (c/-submit! (:cell p) e)) (:door @(:state p)))
        (grow! p)))
    (fut/future-of e)))

(defn submit
  "A future of (apply f args) on pool p (f: a fn, a tfn, or a registered task's name). opts: :retry."
  ([p f] (-submit p f [] nil))
  ([p f args] (-submit p f args nil))
  ([p f args opts] (-submit p f args opts)))

(defn- wait-terminated [p ms]
  (c/call-in-loop
   nil
   (fn []
     (let [deadline (when-not (neg? ms) (+ (c/now) ms))
           me (h/self)]
       (loop []
         (let [s @(:state p)]
           (cond
             (and (= :terminated (:state s)) (empty? (:pending-notices s))) true
             (and (= :terminated (:state s)) (not= me (:creator s))) true
             (and deadline (>= (c/now) deadline)) (= :terminated (:state s))
             :else (let [m (h/receive! (if deadline (max 1 (min 10 (- deadline (c/now)))) 10))]
                     (when-not (= m :eve.thread/timeout) (c/handle! m))
                     (recur)))))))))

(extend-type Pool
  IExecutor
  (-submit [p f args opts] (submit* p f args opts))
  (-shutdown! [p]
    (let [[old _] (c/cas! (:state p) (fn [s] (when (= :running (:state s)) (assoc s :state :shutdown))))]
      (when old
        (if (= :routed (:mode p))
          (do (doseq [a (:members old)] (h/send! a :eve.thread/stop))
              (when (empty? (:members old)) (swap! (:state p) assoc :state :terminated)))
          (do (when-let [door (:door old)] (h/send! door :eve.thread/stop))
              (when-let [timer (:timer old)] (h/send! timer {:eve.thread/op :shutdown}))
              (c/-wake-all! (:cell p))
              (terminate-if-done! p))))
      nil))
  (-shutdown-now! [p]
    (let [[old _] (c/cas! (:state p) (fn [s] (when (contains? #{:running :shutdown} (:state s))
                                               (assoc s :state :stopped))))]
      (if-not old
        []
        (let [drained (c/-drain! (:cell p))
              ids (vec (keep (fn [e] (when (c/cancel! e) (:id @e))) drained))]
          (if (= :routed (:mode p))
            (do (doseq [a (:members old)] (h/send! a :eve.thread/stop))
                (when (empty? (:members old)) (swap! (:state p) assoc :state :terminated)))
            (do (when-let [door (:door old)] (h/send! door :eve.thread/stop))
                (when-let [timer (:timer old)] (h/send! timer {:eve.thread/op :shutdown-now}))
                (c/-wake-all! (:cell p))
                (terminate-if-done! p)))
          ids))))
  (-await-termination [p ms] (wait-terminated p ms))
  (-shutdown? [p] (not= :running (:state @(:state p))))
  (-terminated? [p] (= :terminated (:state @(:state p)))))

(defn invoke-all
  "Each fn of fs on p; their futures, once every one is done."
  [p fs]
  (let [futs (mapv (fn [f] (submit p f)) fs)]
    (doseq [f futs] (c/await! (fut/-entry f) -1))
    futs))

(defn invoke-any
  "Each fn of fs on p; the value of the first to succeed (the others then cancelled), or the last failure thrown."
  [p fs]
  (let [futs (mapv (fn [f] (submit p f)) fs)]
    (loop [left futs]
      (let [e (c/await-any! (mapv fut/-entry left))
            f (first (filter (fn [f] (identical? e (fut/-entry f))) left))
            dd @e]
        (if (= :done (:state dd))
          (do (doseq [g futs] (fut/future-cancel g)) (:value dd))
          (let [more (vec (remove (fn [g] (identical? g f)) left))]
            (if (empty? more) (fut/outcome dd) (recur more))))))))

;; ---------------------------------------------------------------------------
;; The constructors
;; ---------------------------------------------------------------------------

(defn- creator! [p] (swap! (:state p) assoc :creator (h/self)) p)

(defn fixed-pool
  "n threads. opts: :name, :kind (:platform / :virtual where the host has both), :requires (the namespaces an isolate's
   thread loads: the tasks it runs), :forms (true: forms may cross to the threads, P§10 Q4), :retry (the default
   restart type, :never), :entry (the symbol the pool's first threads run — a wrapper of local-entry)."
  ([n] (fixed-pool n {}))
  ([n opts]
   (let [[p n'] (new-pool :fixed n opts)]
     (creator! p)
     (start-threads! p n' (:entry opts))
     p)))

(defn single-pool
  "One thread: first in, first out, one task at a time."
  ([] (single-pool {}))
  ([opts]
   (let [[p n'] (new-pool :single 1 opts)]
     (creator! p)
     (start-threads! p n' (:entry opts))
     p)))

(defn- warm-minimum
  "The threads a growing pool keeps: :core if given; else none where a thread starts in a few ms, and the host's
   parallelism (bounded by :max) where a start costs more (P§2.2's Piscina row: a Node worker's 79.8 ms)."
  [opts]
  (or (:core opts)
      (let [ms (:spawn-ms (h/driving))]
        (if (and ms (> ms 5)) (min (or (:max opts) (h/parallelism)) (h/parallelism)) 0))))

(defn- growing-pool [kind opts]
  (let [routed-n (max 1 (or (:core opts) (h/parallelism)))
        core (warm-minimum opts)
        opts (assoc opts :core core)
        [p n'] (new-pool kind (max 1 core) opts)]
    (creator! p)
    (cond
      (zero? (cap 1)) (swap! (:state p) assoc :threadless true)
      ;; isolates: no growth without a shared cell (lane ET-2) — the :core asked for, else the host's parallelism, fixed
      (= :routed (:mode p)) (do (swap! (:state p) assoc :routed-size routed-n)
                                (start-threads! p routed-n))
      :else (let [[door] (h/start-threads! 1 'eve.thread.pool/door-entry {:pool (:name p)}
                                           (assoc (thread-opts p) :kind nil))]
              (swap! (:state p) (fn [s] (-> s (assoc :door door) (update :pending-notices conj door))))
              (c/register-threads! [door] (:name p))))
    p))

(defn cached-pool
  "Grows on demand up to :max (nil: no bound), an idle thread ending after :keep-alive-ms (60,000 by default), the
   warm minimum :core staying (warm-minimum)."
  ([] (cached-pool {}))
  ([opts] (growing-pool :cached (merge {:keep-alive-ms 60000} opts))))

(defn per-task-pool
  "A thread of its own for each task (virtual threads where the host has them, BEAM processes): a thread ends as soon
   as nothing waits for it, unless threads are expensive to start here (:spawn-ms), where it lives :keep-alive-ms."
  ([] (per-task-pool {}))
  ([opts]
   (let [slow? (let [ms (:spawn-ms (h/driving))] (and ms (> ms 5)))]
     (growing-pool :per-task (merge {:kind :virtual :keep-alive-ms (if slow? 60000 0)} opts)))))

;; ---------------------------------------------------------------------------
;; The scheduled pool: one timer thread, receive! with the next deadline
;; ---------------------------------------------------------------------------

(defn- insert-due [pending item] (vec (sort-by (juxt :at :seq) (conj pending item))))

(defn- next-at [i]
  (if (= :rate (:mode i)) (max (c/now) (+ (:at i) (:period i))) (+ (c/now) (:period i))))

(defn- fire-local!
  "A local scheduled item is due: a one-shot's entry put into the cell; a periodic run submitted as a run descriptor
   that tells the timer when it ends. True when a periodic run went out."
  [p item]
  (let [e (:entry item)]
    (if (= :once (:mode item))
      (do (when (= :queued (:state @e)) (c/enqueue! e)) false)
      (when-not (d/done? @e)
        (let [timer (h/self)
              call (:fn (:work item))
              id (:id item)
              run (fn []
                    (if (d/done? @e)
                      ;; cancelled while this run waited in the pool's queue: a cancelled periodic runs no more (the
                      ;; JVM's ScheduledFuture; a run already started finishes)
                      (do (h/send! timer {:eve.thread/op :ran :id id :ok true}) nil)
                      (let [[ok? v] (h/attempt call)]
                        (h/send! timer {:eve.thread/op :ran :id id :ok ok? :error (when-not ok? (h/error-data v))})
                        (if ok? v (throw v)))))]
          (c/-submit! (:cell p) (new-entry p {:fn run :frame (:frame (:work item))} {}))
          true)))))

(defn- tell-home! [item m]
  (h/send! (:home item) (merge {:eve.thread/op :result :id (:id item) :token (:token item)} m)))

(defn- fire-routed!
  "A routed scheduled item is due, at the timer isolate: run here; a one-shot's result, or a periodic run's failure,
   sent to its home. True when a periodic item goes on."
  [item]
  (let [[ok? v] (h/attempt (fn [] (task/run-work (:work item) (fn [f] (f)))))]
    (cond
      (= :once (:mode item)) (do (tell-home! item (if ok? {:ok true :value v} {:ok false :error (h/error-data v)}))
                                 false)
      ok? true
      :else (do (tell-home! item {:ok false :error (h/error-data v)}) false))))

(defn- end-periodic!
  "A periodic item ends with the pool's shutdown: its future cancelled (at its home, for a routed one)."
  [local? i]
  (if local?
    (c/cancel! (:entry i))
    (tell-home! i {:ok false :cancelled true :error {:class "eve.thread/cancelled" :message "shut down" :data nil}})))

(defn timer-entry
  "A scheduled pool's timer thread: boot {:pool name} (local) or {:pool descriptor} (routed). Waits in receive! with the
   next deadline as its timeout; what is due goes to the pool's threads (local) or runs here (routed)."
  [boot]
  (let [local? (keyword? (:pool boot))
        p (if local? (find-pool (:pool boot)) (routed-view (:pool boot)))]
    (c/call-in-loop
     p
     (fn []
       (loop [pending [] inflight {} n 0 stopping nil]
         (let [t (c/now)
               due (vec (take-while (fn [i] (<= (:at i) t)) pending))]
           (cond
             (seq due)
             (let [later (vec (drop (count due) pending))
                   fired (mapv (fn [i] [i (if local? (fire-local! p i) (fire-routed! i))]) due)
                   inflight (into inflight (keep (fn [[i r]] (when (and local? r) [(:id i) i]))) fired)
                   again (keep (fn [[i r]] (when (and (not local?) r) (assoc i :at (next-at i)))) fired)]
               (recur (reduce insert-due later again) inflight n stopping))
             (and stopping (or (= stopping :now) (not-any? (fn [i] (= :once (:mode i))) pending)))
             (do (when local? (swap! (:state p) assoc :timer-done true) (c/-wake-all! (:cell p)) (terminate-if-done! p))
                 :stopped)
             :else
             (let [m (h/receive! (if (seq pending) (max 1 (- (:at (first pending)) (c/now))) -1))
                   op (when (map? m) (:eve.thread/op m))]
               (cond
                 (= m :eve.thread/timeout) (recur pending inflight n stopping)
                 (= op :schedule)
                 (if stopping
                   (recur pending inflight n stopping)
                   (recur (insert-due pending (assoc (:item m) :seq n)) inflight (inc n) stopping))
                 (= op :unschedule)
                 (recur (vec (remove (fn [i] (= (:id m) (:id i))) pending)) (dissoc inflight (:id m)) n stopping)
                 (= op :ran)
                 (let [i (get inflight (:id m))
                       inflight (dissoc inflight (:id m))]
                   (cond
                     (nil? i) (recur pending inflight n stopping)
                     (not (:ok m)) (do (c/settle! (:entry i) (:token i) false (:error m))
                                       (recur pending inflight n stopping))
                     (or stopping (d/done? @(:entry i))) (recur pending inflight n stopping)
                     :else (recur (insert-due pending (assoc i :at (next-at i) :seq n)) inflight (inc n) stopping)))
                 (or (= op :shutdown) (= m :eve.thread/stop))
                 (let [[once periodic] ((juxt filter remove) (fn [i] (= :once (:mode i))) pending)]
                   (doseq [i (concat periodic (vals inflight))] (end-periodic! local? i))
                   (recur (vec once) {} n :drain))
                 (= op :shutdown-now)
                 (do (doseq [i (concat pending (vals inflight))]
                       (if local? (c/cancel! (:entry i)) (end-periodic! false i)))
                     (recur [] {} n :now))
                 :else (do (c/handle! m) (recur pending inflight n stopping)))))))))))

(defn- schedule* [p f args at period mode]
  (refuse-if-shut! p)
  (when (and (:threadless @(:state p)) (not= :once mode))
    (throw (ex-info "eve.thread: a periodic schedule needs a thread: this host has none" {:eve.thread/refused :no-threads})))
  (let [timer (if (= :routed (:mode p)) (first (:members @(:state p))) (:timer @(:state p)))
        e (new-entry p (work-of p f args) {})
        id (:id @e)
        token (c/next-id "c:")]
    (if (= :routed (:mode p))
      (do (c/cas! e (fn [dd] (d/claim dd timer token (c/now))))
          (swap! c/routed assoc id e)
          (h/send! timer {:eve.thread/op :schedule
                          :item {:id id :at at :mode mode :period period :work (:work @e) :home (h/self) :token token}}))
      (if (= :once mode)
        (do (swap! e assoc :not-before at)
            (when timer (h/send! timer {:eve.thread/op :schedule :item {:id id :at at :mode :once :entry e}})))
        (do (c/cas! e (fn [dd] (d/claim dd timer token (c/now))))
            (h/send! timer {:eve.thread/op :schedule
                            :item {:id id :at at :mode mode :period period :entry e :token token :work (:work @e)}}))))
    (fut/future-of e)))

(extend-type Pool
  IScheduled
  (-schedule [p f args at period mode] (schedule* p f args at period mode)))

(defn schedule!
  "A future of (apply f args) run once, delay-ms from now, on scheduled pool p."
  ([p f delay-ms] (schedule! p f [] delay-ms))
  ([p f args delay-ms] (-schedule p f args (+ (c/now) delay-ms) nil :once)))

(defn schedule-at-fixed-rate!
  "(apply f args) run first after initial-ms, then every period-ms from the previous run's start (a late run starts at
   once, never two at a time); the future completes only by cancel, a failure, or the pool's shutdown."
  ([p f initial-ms period-ms] (schedule-at-fixed-rate! p f [] initial-ms period-ms))
  ([p f args initial-ms period-ms] (-schedule p f args (+ (c/now) initial-ms) period-ms :rate)))

(defn schedule-with-fixed-delay!
  "(apply f args) run first after initial-ms, then delay-ms after each run ends."
  ([p f initial-ms delay-ms] (schedule-with-fixed-delay! p f [] initial-ms delay-ms))
  ([p f args initial-ms delay-ms] (-schedule p f args (+ (c/now) initial-ms) delay-ms :delay)))

(defn scheduled-pool
  "n threads for due work and one timer thread (its receive! timed to the next deadline). Where threads are isolates
   the timer runs the work itself (n is 1). After shutdown! delayed one-shots still run and periodic ones are cancelled
   (the JVM's defaults); shutdown-now! cancels both."
  ([n] (scheduled-pool n {}))
  ([n opts]
   (let [[p n'] (new-pool :scheduled n opts)]
     (creator! p)
     (cond
       (zero? n') (swap! (:state p) assoc :threadless true)
       (= :routed (:mode p)) (start-threads! p 1 'eve.thread.pool/timer-entry)
       :else (do (start-threads! p n' (:entry opts))
                 (let [[timer] (h/start-threads! 1 'eve.thread.pool/timer-entry {:pool (:name p)}
                                                 (assoc (thread-opts p) :kind nil))]
                   (swap! (:state p) (fn [s] (-> s (assoc :timer timer) (update :pending-notices conj timer))))
                   (c/register-threads! [timer] (:name p)))))
     p)))

;; ---------------------------------------------------------------------------
;; The default pools (core.async's by workload, P§3): :io per task, :compute fixed at the host's parallelism
;; ---------------------------------------------------------------------------

(defonce defaults (atom {}))

(defn default-pool
  "The default pool for a workload: :io (future: per-task) or :compute (pmap, pcalls: a fixed pool of the host's
   parallelism), made on first use."
  [kind]
  (or (get @defaults kind)
      (let [p (case kind
                :io (per-task-pool {:name :eve.thread/io})
                :compute (fixed-pool (h/parallelism) {:name :eve.thread/compute}))]
        (if (c/cas! defaults (fn [m] (when-not (contains? m kind) (assoc m kind p))))
          p
          (do (shutdown! p) (get @defaults kind))))))

(defn shutdown-default-pools!
  "Shut the default pools down (as shutdown-agents does for Clojure's): an isolate's workers keep its process alive."
  []
  (let [[old _] (c/cas! defaults (fn [m] (when (seq m) {})))]
    (doseq [p (vals old)] (shutdown! p) (await-termination p 10000))
    nil))

(defn pool-for
  "The pool work goes to: *pool* when bound, else the default pool for kind."
  [kind]
  (or (c/current-pool) (default-pool kind)))

;; ---------------------------------------------------------------------------
;; pmap, pcalls: semi-lazy, unchunked, the pool's size + 2 ahead
;; ---------------------------------------------------------------------------

(defn lookahead
  "How many calls pmap keeps running ahead of its consumer: the pool's size, or the host's parallelism for a pool that
   grows — plus 2 (clojure.core/pmap's availableProcessors + 2)."
  [p]
  (+ 2 (if (contains? #{:cached :per-task} (:kind p)) (h/parallelism) (:size p))))

(defn- unchunked-map
  "(map f s), realized one element at a time whatever s's chunking (the host's chunk size is 32 on the JVM and 64 on
   the BEAM: a chunked map would submit that many ahead, P§2.1)."
  [f s]
  (lazy-seq (when-let [s (seq s)] (cons (f (first s)) (unchunked-map f (rest s))))))

(defn- arg-vectors
  "The argument vectors of a map over colls, one element at a time."
  [colls]
  (lazy-seq
   (let [ss (mapv seq colls)]
     (when (every? some? ss)
       (cons (mapv first ss) (arg-vectors (mapv rest ss)))))))

(defn- submit-through!
  "Submit the calls of pmap-calls' cursor st until index upto is submitted or the calls end; each call realized and
   submitted once, the cursor holding what is submitted (by index) and the calls not yet realized."
  [p st upto]
  (loop []
    (let [{:keys [futs more]} @st]
      (when (and (<= (count futs) upto) (some? more))
        (if-let [s (seq more)]
          (let [[f args] (first s)]
            (swap! st assoc :futs (conj futs (submit p f args)) :more (rest s)))
          (swap! st assoc :more nil))
        (recur)))))

(defn pmap-calls
  "The values of each [f args] of calls on pool p, semi-lazily: realizing value i submits through call i + (lookahead
   p). The calls are walked once, by a cursor of pmap's own, and each is submitted once, whatever the host's lazy seqs
   do: clojerl's do not memoize — a lazy seq walked again through another reference runs its body again (measured:
   drop 3, seq, drop 3, seq, take 4 realized 12 elements where the JVM realizes 4) — so neither the answer seq nor
   the input is trusted to remember. (One consumer, as pmap's answer is used.)"
  [p calls]
  (let [n (lookahead p)
        st (atom {:futs [] :more calls})
        step (fn step [i]
               (lazy-seq
                (submit-through! p st (+ i n))
                (when-let [f (get (:futs @st) i)]
                  (cons (deref f) (step (inc i))))))]
    (step 0)))

(defn pmap*
  "clojure.core/pmap on pool p (read by the caller, at the call)."
  [p f colls]
  (pmap-calls p (unchunked-map (fn [args] [f args]) (arg-vectors colls))))

(defn pcalls*
  "Each fn (a fn, a tfn or a task name) on pool p; their values, semi-lazily."
  [p fns]
  (pmap-calls p (unchunked-map (fn [g] [g []]) fns)))

;; ---------------------------------------------------------------------------
;; What eve.thread's public names are
;; ---------------------------------------------------------------------------

(defn future-call*
  "f (a fn, a tfn or a task name) on *pool*, else the :io pool; a future."
  [f]
  (submit (pool-for :io) f [] nil))

(defn pmap-default
  "clojure.core/pmap over *pool* (read here, at the call), else the :compute pool: unchunked, (lookahead p) ahead."
  [f coll & colls]
  (pmap* (pool-for :compute) f (cons coll colls)))

(defn pcalls-default
  "Each fn on *pool*, else the :compute pool; their values, semi-lazily."
  [& fns]
  (pcalls* (pool-for :compute) fns))

(defn pool-name-of [p] (:name p))

(defn attempt
  "[true value] or [false {:class :message :data}]: the catch-all shared code cannot spell."
  [f]
  (let [[ok? v] (h/attempt f)] (if ok? [true v] [false (h/error-data v)])))
