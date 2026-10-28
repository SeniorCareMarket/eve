(ns eve.thread.domain
  "eve.thread's domain cell : pools, the runtime registry, bring-up, election and the upgrade protocol, all
   kept in the META-ATOM of a rendezvous atom (eve.atom/meta-atom, 2026-10-19). Its value is
   eve.thread.meta's schema and every move on it is one of eve.thread.meta's pure functions, applied by one swap of
   the meta-atom — so the rendezvous atom itself (the application's state) is never written by coordination, and any
   process attached to its domain takes part with no coordinator: it submits, claims, completes, cancels and adopts
   descriptors, and joins, heartbeats and leaves, by swapping.

     (def dh (attach! a {:ttl 3000}))            this runtime joins a's meta-atom (its registry)
     (def g (pool dh :g {:threads 2}))           pool :g registered there; two of this runtime's threads pull from it
     @(t/submit g 'my.ns/task [1 2])             a descriptor any member may claim — this process's or another's
     (registry dh)                               the one registry every attached process reads
     (detach! dh)

   The rendezvous atom is on whichever rung its developer opened it on (eve.thread.rung): an in-process
   eve atom (rung 0: the threads of one heap), a domain on disk (rung 1) or in the window (rung 2: tmpfs, by
   configuration), or a network atom at a broker or a gateway (rung 3: eve.thread.net) — the same moves over each, and
   a handle keeps the rung it was opened on. Work crosses heaps, so it is data: a registered task's name with its
   form's hash, or a form where the pool allows forms ({:forms true}) — eve.thread.task's data-work. An idle thread
   waits on a doorbell where its host's region has a futex (a submission, a completion rings it), else polls, its
   polls spaced by what a read costs on its rung; a thread waiting on many descriptors waits by a driver (collect:
   :in-order, :sweep, :waiters — P§5.7).

   Liveness is a lease at the grain of the work. Each attached runtime with threads runs one ticker thread: it starts
   the runtime's pump threads (so their exit notices reach it), and every ttl/4 it renews in one swap the runtime's
   lease and the lease of each pump thread still alive, and sweeps — adopting every descriptor and component whose
   owner's lease expired (a killed process's: all of them, once its ttl passes; a dead thread's: at once, its lease
   dropped on its exit notice), forgetting settled descriptors nobody will read, taking out runtimes whose own lease
   expired. A runtime with no threads (thread/none) renews on its own swaps, and its waits run the work."
  (:refer-clojure :exclude [swap! reset! volatile! vswap!])
  (:require [eve.host :refer [swap! reset! volatile! vswap!]]
            [eve.deftype-proto.data :as ed]
            [eve.process :as proc]
            [eve.thread-host :as h]
            [eve.thread.cell :as c]
            [eve.thread.descriptor :as d]
            [eve.thread.fold :as fold]
            [eve.thread.future :as fut]
            [eve.thread.meta :as mt]
            [eve.thread.pool :as ep]
            [eve.thread.rung :as rung]
            [eve.thread.task :as task]))

(defn now [] (proc/now-ms))

(defn- threads? [] (not= 0 (:max-threads (h/driving))))

(defn- threaded?
  "Does this runtime run threads of its own for handle dh (a ticker, pumps)? Not on a host with none, nor where attach!
   was told :threads false (a rung only this heap can reach — an in-process stand-in broker — on a host whose threads
   are isolates)."
  [dh]
  (and (threads?) (not (:threadless dh))))

;; ---------------------------------------------------------------------------
;; Handles: this runtime's attachment to one rendezvous atom
;; ---------------------------------------------------------------------------

;; key → handle: the threads of a heap find their runtime's handle here; an isolate (whose heap is its own) opens the
;; domain by its path and makes its own
(defonce handles (clojure.core/atom {}))

(defn- cell-of
  "The cell of a rendezvous atom's rung r: the rung itself, its meta-atom's (the coordination's home) and that one's
   meta-atom's (the turns)."
  [r]
  (let [mr (rung/-meta-rung r {:clock 0})]
    {:rung r :meta mr :turn (rung/-meta-rung mr {})}))

(defn- make-handle [r b]
  {:key (:key b) :base (:base b) :name (:name b) :rid (:rid b) :ttl (:ttl b)
   :caps (set (:caps b)) :requires (vec (:requires b)) :threadless (boolean (:threadless b))
   :host (:host b) :pid (:pid b) :transports (vec (:transports b))
   ;; the rung this runtime reaches the rendezvous atom by: the one it was opened on, chosen by its developer
   :cell (cell-of r)
   :st (clojure.core/atom {:forget [] :pools {} :ticker nil :beat 0})})

(defn- mcell "The rung of the handle's meta-atom." [dh] (:meta (:cell dh)))
(defn- tcell "The rung of the meta-atom's meta-atom (turns)." [dh] (:turn (:cell dh)))
(defn via
  "The rung this runtime reaches its rendezvous atom by — the one the handle was opened on: :heap :disk :window :net."
  [dh]
  (rung/-kind (:rung (:cell dh))))

(defn- boot-of
  "The handle as data: what a thread of this runtime is started with."
  [dh]
  {:key (:key dh) :base (:base dh) :name (:name dh) :rid (:rid dh) :ttl (:ttl dh) :caps (vec (:caps dh))
   :requires (:requires dh) :host (:host dh) :pid (:pid dh) :transports (:transports dh)
   :locate (rung/-locate (:rung (:cell dh)))})

(defn- resolve-handle
  "The handle a thread of this runtime was started with: the heap's own, or — an isolate — its rung re-opened from its
   location (a domain by its path, a network atom by its endpoint)."
  [b]
  (or (get @handles (:key b))
      (let [dh (make-handle (rung/open (:locate b)) b)]
        (clojure.core/swap! handles assoc (:key b) dh)
        dh)))

;; ---------------------------------------------------------------------------
;; Turns: a writer that keeps losing the meta-atom's races is let through
;; ---------------------------------------------------------------------------
;; eve's swap is optimistic: an attempt that lost its CAS is made again from the new value. Among writers of one speed
;; that is fair; a writer whose attempt outlasts the others' commit interval (a host whose swaps cost tens or hundreds
;; of ms, beside one whose swaps cost µs) loses every race while the others are busy — measured: a basilisp member
;; beside a JVM member could not renew its lease for 3.3 s while the JVM's threads ran work, and was taken out; in one
;; basilisp process, a runtime's write of its whole descriptor starved beside another runtime's beats. Such a writer
;; takes a TURN: it announces itself in the meta-atom's own meta-atom (the pattern again: the meta-atom's coordination
;; beside it, as plain data), and every other writer waits before its next swap while a turn stands, so the starved
;; writer's next attempt meets no new commit. Turns are served in the order announced; a turn ends when its swap
;; commits, or at its :until (a writer that died holding one). A ticker's renewal waits only until its runtime's lease
;; is half spent (a renewal is never late for a turn), and a renewal made while a turn stands stretches the lease to
;; 8 x that writer's attempt, so that the next one waits the turn out.

(def ^:private starved-after
  "A swap is starved at the start of its third attempt once it has failed for this many ms (a fast host's attempt is
   µs: two failures never cost it this)."
  500)

(def ^:dynamic *turn-deadline*
  "When bound (a ticker's renewal: see Turns), the time past which this writer waits on no turn."
  nil)

(defn- turn-ahead?
  "Does turn [k x] stand at t ahead of this writer's — announced before mine ([token turn], or nil: every standing turn
   is ahead)?"
  [[k x] mine t]
  (and (> (:until x) t)
       (or (nil? mine) (neg? (compare [(:at x) k] [(:at (second mine)) (first mine)])))))

(def ^:dynamic *waited*
  "When bound (a beat), a host atom the ms this writer waited on turns are added to: a beat's cost, which its lease
   is a multiple of, is its own swaps', not its waits (a lease that grew with its waits would let it wait longer)."
  nil)

(defn- await-turn! [dh mine]
  (let [t0 (now)]
    (loop []
      (let [t (now)]
        (when (and (or (nil? *turn-deadline*) (< t *turn-deadline*))
                   (rung/-read (tcell dh) (fn [m] (boolean (some (fn [e] (turn-ahead? e mine t)) m)))))
          (proc/sleep-ms! 1)
          (recur))))
    (when *waited* (clojure.core/swap! *waited* + (- (now) t0)))))

(defn- take-turn!
  "Announce (or renew) this writer's turn: it lasts four of its attempts (avg ms each), served from when it was first
   taken."
  [dh mine avg]
  (let [token (or (first mine) (str (:rid dh) "#" (rand-int 2147483647)))
        t (now)
        x {:rid (:rid dh) :at (or (:at (second mine)) t) :avg (max avg 25)
           :until (+ t (* 4 (max avg 25)))}]
    (rung/-swap! (tcell dh) (fn [m] (assoc (into {} (filter (fn [[_ y]] (> (:until y) t))) m) token x)))
    [token x]))

(defn- end-turn! [dh mine] (when mine (rung/-swap! (tcell dh) (fn [m] (dissoc m (first mine))))))

(defn- turn-floor
  "The lease a renewal needs so that its next wait can outlast a standing turn: 8 x that writer's attempt (the multiple
   a lease is of a beat), the longest of them; 0 when none stands."
  [dh]
  (let [t (now)]
    (rung/-read (tcell dh) (fn [m] (reduce max 0 (keep (fn [[_ x]] (when (> (:until x) t) (* 8 (or (:avg x) 0)))) m))))))

(defn swap-meta!
  "One swap of the meta-atom: f takes its value and answers [new-value answer]; the answer of the attempt that
   committed, materialized under the swap's pin (eve values do not outlive it). A starved swap (see Turns) commits
   nothing, takes a turn and is made again."
  [dh f]
  (let [out (volatile! nil)
        attempts (volatile! 0)
        swap-once! (fn [t0]
                     (rung/-swap! (mcell dh)
                            (fn [v]
                              (if (and (>= (vswap! attempts inc) 3) (> (- (now) t0) starved-after))
                                ;; the same value: this attempt publishes nothing
                                (do (vreset! out ::starved) v)
                                (let [[n a] (f (or v {}))] (vreset! out (ed/eve->clj a)) n)))))]
    (loop [mine nil]
      (await-turn! dh mine)
      (vreset! attempts 0)
      (let [t0 (now)
            [ok? e] (h/attempt (fn [] (swap-once! t0)))]
        (cond
          (not ok?) (do (end-turn! dh mine) (throw e))
          (= ::starved @out) (recur (take-turn! dh mine (quot (- (now) t0) (max 1 (dec @attempts)))))
          :else (do (end-turn! dh mine) @out))))))

(defn- read-timed
  "(rung/-read the meta-atom f), its cost kept (a running mean, ms) — what this runtime's idle polls are spaced by."
  [dh f]
  (let [t0 (now)
        r (rung/-read (mcell dh) f)
        ms (- (now) t0)]
    (clojure.core/swap! (:st dh) (fn [st] (-> st (update :read-ms (fn [x] (if x (quot (+ (* 4 x) ms) 5) ms)))
                                                (update :reads (fnil inc 0)))))
    r))

(defn read-meta
  "The meta-atom's value at path (materialized), read under a pin — never more of it than the path."
  [dh path]
  (read-timed dh (fn [v] (ed/eve->clj (get-in v path)))))

(defn poll-cap
  "The longest an idle poll of this runtime waits between reads: 16 ms, or four reads' worth where a read costs more
   (a network rung's read is a request, a fetch and a decode — measured, not a constant)."
  [dh]
  (max 16 (* 4 (or (:read-ms @(:st dh)) 0))))

(defn- ring!
  "Ring the doorbells of words (eve.thread.rung: a descriptor's, a pool's, the completions') — after the move that
   concerns their waiters has committed."
  [dh words]
  (rung/ring! (mcell dh) (remove nil? words)))

(defn- all-ring?
  "Does every runtime of the registry ring with a futex ((driving) :wake :futex)? A futex waiter may then wait long
   (a ring wakes it); else it waits no longer than a poll would (a :poll host's commit wakes no other process). Read
   at most once a second."
  [dh]
  (let [{:keys [at v]} (:all-ring @(:st dh))
        t (now)]
    (if (and at (< (- t at) 1000))
      v
      (let [v (rung/-read (mcell dh) (fn [m] (every? (fn [[_ r]] (= :futex (get-in r [:driving :wake]))) (:runtimes m))))]
        (clojure.core/swap! (:st dh) assoc :all-ring {:at t :v v})
        v))))

(defn- idle-wait!
  "Wait for doorbell word to ring past seen (its count before the read that found nothing to do), or for ms: a futex
   wait where this host's region has one — up to 100 ms when every runtime rings, ms otherwise — then this thread's
   mailbox read; else a poll (this thread's mailbox, ms). Messages that are not eve.thread's are handled."
  ([dh word seen ms] (idle-wait! dh word seen ms 100))
  ([dh word seen ms most]
  (let [r (mcell dh)]
    (if (and (some? seen) (rung/futex? r))
      (do (rung/wait-bell! r word seen (if (all-ring? dh) (max 1 (min 100 most)) ms))
          (let [m (h/receive! 0)] (when-not (= m :eve.thread/timeout) (c/handle! m))))
      (let [m (h/receive! ms)] (when-not (= m :eve.thread/timeout) (c/handle! m)))))))

(defn meta-value "The whole meta-atom value (materialized)." [dh] (read-meta dh []))

(defn- take-forgets!
  "The settled descriptors this runtime's futures have read since its last swap (they leave the meta-atom with it)."
  [dh]
  ;; (by compare-and-set: swap-vals!'s order is not every host's — basilisp's answers [new old])
  (let [st (:st dh)] (loop [] (let [s @st] (if (compare-and-set! st s (assoc s :forget [])) (:forget s) (recur))))))

(defn- swap-forgetting!
  "swap-meta! with f also taking the settled descriptors to forget (take-forgets!): given back when the swap throws."
  [dh f]
  (let [fg (take-forgets! dh)
        [ok? r] (h/attempt (fn [] (swap-meta! dh (fn [v] (f v fg)))))]
    (if ok?
      r
      (do (when (seq fg) (clojure.core/swap! (:st dh) update :forget into fg))
          (throw r)))))

(defn- task-namespaces []
  (distinct (keep (fn [sym] (when (namespace sym) (symbol (namespace sym))))
                  (concat (keys @task/tasks) (keys @task/conveyed)))))

(defn- runtime-descriptor
  "P§5.4's runtime descriptor: what this runtime is, runs and can reach (the handle carries what attach! was told, so
   a runtime that joins again — from any of its threads — is described as it first was)."
  [dh]
  (let [dr (h/driving)
        measured (:measured @(:st dh))]
    (cond-> {:rid (:rid dh) :kind (:runtime dr) :pid (:pid dh) :host (:host dh) :ttl (:ttl dh)
             :driving (select-keys dr [:block :parallel :closures :eval :wake :spawn-ms])
             :tasks (set (keys @task/tasks)) :caps (:caps dh) :transports (vec (:transports dh))}
      ;; (its swap cost, once measured, rejoins with it: fold's chunks are sized by it)
      measured (assoc :measured measured))))

(defn- local-pool-descriptors
  "This runtime's own pools (eve.thread.pool's rung 0, not a domain's) as data: their descriptors less the thread
   addresses (a host's address is not data everywhere), with the count of their threads."
  []
  (into {} (keep (fn [[nm p]]
                   (when (nil? (:dh p))
                     (let [pd (ep/pool-descriptor p)]
                       [nm (-> (select-keys pd [:name :kind :rung :form :size :state :forms :retry :core :max
                                                :keep-alive-ms])
                               (assoc :thread-count (count (:threads pd))))]))))
        @c/pools))

(defn- rejoin
  "v with this runtime joined again when a sweep took it out (its lease expired while it lived): it starts from nothing,
   its pools' memberships restored from what it claimed, its own pools published again."
  [v dh now]
  (if (mt/joined? v (:rid dh))
    v
    (-> (reduce (fn [v [nm tids]] (mt/join-pool v nm (:rid dh) tids))
                (mt/join v (runtime-descriptor dh) now)
                (map (fn [[nm p]] [nm (:tids p)]) (:pools @(:st dh))))
        (mt/publish-local-pools (:rid dh) (local-pool-descriptors)))))

(defn publish!
  "This runtime's own pools published in its registry entry (attach! does it once; call again after making more)."
  [dh]
  (let [descs (local-pool-descriptors)]
    (swap-meta! dh (fn [v] [(mt/publish-local-pools v (:rid dh) descs) nil]))))

(declare start-ticker! beat!)

(defn attach!
  "This runtime attaches to rendezvous atom a (an eve atom: on disk for processes, in-process for the threads of one
   heap): a's meta-atom — made on first use, by exactly one of the processes asking at once — becomes the home of the
   coordination this runtime takes part in, and this runtime joins its registry. Where the host has threads a ticker
   thread keeps its leases (see the namespace doc). opts: :rid (default: the adapter's :runtime and a random number),
   :ttl (the lease horizon, ms; heartbeats every ttl/4), :caps (what it is suited for: a descriptor's :needs), :host
   ({:machine :boot}: co-hosting, P§5.5), :pid, :transports, :requires (the namespaces its isolate threads load: the
   tasks they run), :threads false (no ticker or pumps for this handle: it renews on its own swaps and its waits run
   the work, as on a host with no threads — for a rung only this heap reaches, on a host whose threads are isolates). a
   is an eve atom, or a rung (eve.thread.rung: a placed domain's, a network atom's — eve.thread.net). The handle."
  ([a] (attach! a {}))
  ([a opts]
   (let [r (if (satisfies? rung/IRung a) a (rung/eve-rung a))
         loc (rung/-locate r)
         nm (or (:name loc) (throw (ex-info "eve.thread.domain: the rendezvous atom has no name" {})))
         rid (or (:rid opts) (str (name (or (:runtime (h/driving)) :rt)) "-" (rand-int 1000000000)))
         b {:key [rid nm (rand-int 1000000000)] :base (:domain loc) :name nm :rid rid
            :ttl (or (:ttl opts) mt/default-ttl-ms) :caps (vec (:caps opts))
            :host (:host opts) :pid (:pid opts) :transports (vec (:transports opts))
            :requires (vec (distinct (concat ['eve.thread.domain] (:requires opts) (task-namespaces))))
            :threadless (false? (:threads opts))}
         dh (make-handle r b)
         desc (runtime-descriptor dh)
         pools (local-pool-descriptors)]
     (clojure.core/swap! handles assoc (:key dh) dh)
     ;; one swap, its lease from the clock of the attempt that commits (a slow host's join is a write of seconds) —
     ;; and measured: it is a swap of this rung, so a lease is at least 8 of it from the first beat on (P§6.3a). A
     ;; ticker's floor otherwise grows only after a slow beat has been made, and a lease that lapses before then (a
     ;; network swap of seconds against a lease of a few: clojerl under load, measured) lets another runtime adopt the
     ;; work this runtime's live thread holds.
     (let [t0 (now)
           waited (binding [*waited* (clojure.core/atom 0)]
                    (swap-meta! dh (fn [v] [(mt/publish-local-pools (mt/join v desc (now)) rid pools) nil]))
                    @*waited*)]
       (clojure.core/swap! (:st dh) assoc :floor (* 8 (- (now) t0 waited))))
     (when (threaded? dh) (start-ticker! dh))
     dh)))

;; ---------------------------------------------------------------------------
;; The ticker: leases, sweeps, and the starter of this runtime's pump threads
;; ---------------------------------------------------------------------------

(defn beat!
  "A beat: one small swap renewing this runtime's lease (stretched to at least floor ms), its live threads' (tids) and
   its held named leases, and forgetting what its futures read; then the sweep, read first and swapped only when it has
   something to do — descriptors and components whose owner's lease expired adopted, runtimes whose lease expired taken
   out, a departed runtime's settled descriptors past the horizon forgotten. (A beat that must win a race is kept small:
   a slow host's attempts otherwise lose every race to a fast host's.) Answers what the sweep did, the lease (:ttl)
   and the ms it waited on turns (:waited)."
  ([dh] (beat! dh [] {} 0))
  ([dh tids held floor]
   ;; (a renewal waits on another process's turn only until this runtime's lease is half spent: see Turns)
   (binding [*turn-deadline* (let [{:keys [renewed lease]} @(:st dh)]
                               (if renewed (+ renewed (quot lease 2)) (+ (now) (quot (:ttl dh) 4))))
             *waited* (clojure.core/atom 0)]
   (let [horizon (* 10 (:ttl dh))
         ;; a renewal made while another process's turn stands covers that writer's attempt (see Turns)
         floor (max floor (turn-floor dh))]
     (let [ttl (swap-forgetting! dh (fn [v fg]
                                     ;; (the clock read by each attempt: a swap that waited renews with the time it commits at)
                                     (let [t (now)
                                           v (-> (rejoin v dh t) (mt/heartbeat (:rid dh) tids t) (mt/forget fg))
                                           ;; this runtime's lease: at least floor, and the fleet's (its slowest member's)
                                           v (mt/stretch-ttl v (:rid dh) (max floor (mt/fleet-ttl v)))
                                           own (get-in v [:runtimes (:rid dh) :ttl])
                                           ;; a held lease lasts at least this runtime's own (its renewals come at that cadence)
                                           v (reduce (fn [v [lname ttl]] (first (mt/acquire v lname (:rid dh) (max ttl (or own 0)) t)))
                                                     v held)]
                                       [v (get-in v [:runtimes (:rid dh) :ttl])])))
         _ (clojure.core/swap! (:st dh) assoc :renewed (now) :lease (or ttl (:ttl dh)))]
       (let [sw (if (rung/-read (mcell dh) (fn [v] (mt/sweep-due? v (now) horizon)))
                    (swap-meta! dh (fn [v]
                                     (let [t (now)
                                           [v adopted] (mt/adopt-expired v t)
                                           [v comps] (mt/adopt-components v t)
                                           [v gone] (mt/expire-runtimes v t)
                                           [v orphans] (mt/forget-orphans v t horizon)]
                                       [v {:adopted adopted :components comps :expired gone :forgotten orphans
                                           :pools (vec (distinct (keep (fn [id] (get-in v [:descs id :pool])) adopted)))}])))
                    {:adopted [] :components [] :expired [] :forgotten []})]
         ;; re-queued work's pools and failed work's waiters
         (when (seq (:adopted sw))
           (ring! dh (concat [(rung/completion-word)] (map rung/descriptor-word (:adopted sw))
                             (map rung/pool-word (:pools sw)))))
         (assoc sw :ttl ttl :waited @*waited*)))))))

(defn- threadless-beat!
  "A runtime with no threads of its own beats from its waits, every quarter of its lease — and, as a ticker does, keeps
   its lease at least 8 x the slowest beat it has made (P§6.3a): a runtime whose beat outlasts a fixed lease (a slow
   rung, a slow host) would otherwise lapse between its renewals and, lapsed, claim nothing."
  [dh]
  (let [{:keys [beat lease floor]} @(:st dh)]
    (when (> (now) (+ (or beat 0) (quot (or lease (:ttl dh)) 4)))
      (clojure.core/swap! (:st dh) assoc :beat (now))
      (let [t0 (now)
            r (beat! dh [] {} (or floor 0))]
        (clojure.core/swap! (:st dh) update :floor (fn [f] (max (or f 0) (* 8 (- (now) t0 (:waited r))))))))))

(defn adopt!
  "One sweep now (any runtime may make it; every ticker makes it every beat): the ids of the descriptors adopted —
   those whose owner's lease expired, re-queued as restarts or failed :owner-died by their :retry."
  [dh]
  (:adopted (beat! dh)))

(defn- thread-opts [dh nm]
  {:name nm :requires (:requires dh)})

(defn ticker-entry
  "The ticker thread of a runtime (boot: its handle as data, and :reply — the thread its first beat is reported to)."
  [b]
  (let [dh (resolve-handle b)
        ;; the lease's floor: 8 x the slowest beat this ticker has made, from 8 x its runtime's join (it only grows); the
        ;; cadence: a quarter of the lease as the last beat left it (the fleet's slowest member's, at least)
        floor (clojure.core/atom (max (:ttl dh) (or (:floor b) 0)))
        cadence (clojure.core/atom (max 20 (quot (:ttl dh) 4)))
        beat (fn [tids held]
               (let [t0 (now)
                     [ok? r] (h/attempt (fn [] (beat! dh tids held @floor)))]
                 ;; a beat that failed (a swap past eve's retries) is the next beat's to make; the ticker goes on
                 (clojure.core/swap! floor max (* 8 (- (now) t0 (if ok? (:waited r) 0))))
                 (when (and ok? (:ttl r)) (clojure.core/reset! cadence (max 20 (quot (:ttl r) 4))))
                 ok?))]
    (beat [] {})
    (when-let [r (:reply b)] (h/send! r {:eve.thread/op :ticker-up}))
    (c/call-in-loop
     nil
     (fn []
       (loop [live {} held {} next-beat 0 stopping nil]
         (let [t (now)
               next-beat (if (>= t next-beat)
                           (do (beat (keys live) held) (+ t @cadence))
                           next-beat)]
           (if (and stopping (or (empty? live) (>= t stopping)))
             :stopped
             (let [m (h/receive! (max 1 (- next-beat (now))))
                   op (when (map? m) (:eve.thread/op m))]
               (cond
                 (= m :eve.thread/timeout) (recur live held next-beat stopping)
                 (= m :eve.thread/stop)
                 (do (doseq [a (vals live)] (h/send! a :eve.thread/stop))
                     (recur live {} next-beat (+ (now) (* 2 (:ttl dh)))))
                 (= op :pumps)
                 (let [addrs (h/start-threads! (:n m) 'eve.thread.domain/pump-entry
                                               (assoc b :pool (:pool m) :batch (:batch m))
                                               (thread-opts dh (str "eve-domain-" (name (:pool m)))))
                       tids (mapv str addrs)
                       live (into live (map vector tids addrs))]
                   (beat (keys live) held)
                   (h/send! (:reply m) {:eve.thread/op :pumps-started :pool (:pool m) :tids tids})
                   (recur live held next-beat stopping))
                 (= op :stop-pumps)
                 (do (doseq [tid (:tids m)] (when-let [a (get live tid)] (h/send! a :eve.thread/stop)))
                     (recur live held next-beat stopping))
                 (= op :halt)
                 ;; as if this runtime's process died: no leave, no further renewal — its leases expire
                 (do (doseq [a (vals live)] (h/send! a :eve.thread/stop)) :halted)
                 (= op :hold) (recur live (assoc held (:lease m) (:ttl m)) next-beat stopping)
                 (= op :release) (recur live (dissoc held (:lease m)) next-beat stopping)
                 (and (map? m) (contains? m :eve.thread/exit))
                 (let [tid (str (:eve.thread/exit m))]
                   ;; its lease goes now: what it held, if it died holding anything, is adoptable at once
                   (swap-meta! dh (fn [v] [(mt/drop-threads v (:rid dh) [tid]) nil]))
                   (recur (dissoc live tid) held next-beat stopping))
                 :else (do (c/handle! m) (recur live held next-beat stopping)))))))))))

(declare await-reply! detach!)

(defn- start-ticker!
  "The ticker started, and its first beat waited for: a runtime's lease is renewed from the moment attach! answers (a
   Node worker loads the program for seconds before it runs — longer than a lease)."
  [dh]
  (let [[a] (h/start-threads! 1 'eve.thread.domain/ticker-entry
                              (assoc (boot-of dh) :reply (h/self) :floor (:floor @(:st dh)))
                              (thread-opts dh "eve-domain-ticker"))]
    (clojure.core/swap! (:st dh) assoc :ticker a)
    ;; the lease renewed from here while the ticker starts, at the ticker's own cadence (ttl/4): else another runtime's
    ;; sweep takes this one out — and no oftener: these renewals and the ticker's first one write the same meta-atom
    (let [last (clojure.core/atom (now))
          every (max 100 (quot (:ttl dh) 4))]
      ;; a first beat that never came: this runtime leaves (its ticker stopped) before attach! throws — no handle reaches
      ;; a ticker attach! did not answer, and it would beat for the life of the process
      (let [[ok? r] (h/attempt (fn []
                                 (await-reply! :ticker-up 120000 a
                                               (fn [] (when (>= (- (now) @last) every)
                                                        (clojure.core/reset! last (now))
                                                        (let [t (now)] (swap-meta! dh (fn [v] [(mt/heartbeat v (:rid dh) [] t) nil]))))))))]
        (when-not ok?
          (h/attempt (fn [] (detach! dh)))
          (throw r))))
    a))

(defn- ask-ticker!
  "Send the ticker m (with :reply this thread) and wait for its answer of op reply-op (other messages handled)."
  [dh m reply-op]
  (let [ticker (or (:ticker @(:st dh))
                   (throw (ex-info "eve.thread.domain: this runtime has no threads to run a pool's work"
                                   {:eve.thread/refused :no-threads})))]
    (h/send! ticker (assoc m :reply (h/self)))
    (await-reply! reply-op 120000 ticker nil)))

(defn- await-reply!
  "This thread's next message of op reply-op (other messages handled meanwhile); throws after ms, or at once when the
   ticker (its address, from) ends — with its exit notice's reason. keep (nil: none) runs every 100 ms meanwhile."
  [reply-op ms from keep]
  (c/call-in-loop
   nil
   (fn []
     (let [deadline (+ (now) ms)]
       (loop []
         (let [r (h/receive! (max 1 (min (if keep 100 ms) (- deadline (now)))))]
           (cond
             (and (= r :eve.thread/timeout) (< (now) deadline)) (do (when keep (keep)) (recur))
             (= r :eve.thread/timeout)
             (throw (ex-info "eve.thread.domain: the ticker did not answer" {:awaiting reply-op}))
             (and (map? r) (= reply-op (:eve.thread/op r))) r
             (and (map? r) (contains? r :eve.thread/exit) (= from (:eve.thread/exit r)))
             (throw (ex-info (str "eve.thread.domain: the ticker ended: " (pr-str (:reason r)))
                             {:eve.thread/refused :ticker-ended :reason (:reason r)}))
             :else (do (c/handle! r) (recur)))))))))

;; ---------------------------------------------------------------------------
;; Descriptors in the meta-atom: claim, run, complete, cancel, await
;; ---------------------------------------------------------------------------

(def ^:dynamic *running*
  "The domain descriptor this thread is running: {:dh :id :pool :tid} (a wait marks it :blocked)."
  nil)

(defn- can-run-fn
  "Which work this runtime can run: a task registered here, or a form where this runtime evals."
  []
  (let [tasks @task/tasks
        eval? (:eval (h/driving))]
    (fn [w] (if-let [s (:task w)] (contains? tasks s) (and eval? (some? (:form w)))))))

(defn- unmarked?
  "Is this runtime joined and its thread tid not recorded pulling (:up)? — a runtime taken out while it lived joins
   again with no thread recorded, and a thread that started while it was out recorded nothing."
  [v rid tid]
  (and (some? (get-in v [:runtimes rid])) (nil? (get-in v [:runtimes rid :up tid]))))

(defn- queue-state
  "Whether pool's queue holds an id, the pool's state, and whether thread tid is unmarked — one read, no write."
  [dh nm tid]
  (read-timed dh (fn [v] [(boolean (seq (get-in v [:q nm]))) (ed/eve->clj (get-in v [:pools nm :state]))
                            (unmarked? v (:rid dh) tid)])))

(defn claim!
  "Thread tid of this runtime claims up to k of pool nm's queued descriptors it can run (one swap, made only when the
   queue holds something). {:claimed [descriptor ...] :state pool-state :unmarked (unmarked?)}."
  [dh nm tid k]
  (let [[q? state unmarked] (queue-state dh nm tid)]
    (if-not q?
      {:claimed [] :state state :unmarked unmarked}
      (let [rid (:rid dh)
            caps (:caps dh)
            can-run? (can-run-fn)]
        (swap-forgetting! dh (fn [v fg]
                              (let [t (now)
                                    [v xs] (mt/claim (mt/forget v fg) nm rid tid k caps can-run? t)]
                                ;; a claimer is alive: its thread's lease renewed with the claim (a thread just started may
                                ;; claim before its ticker's first renewal)
                                [(if (seq xs) (mt/heartbeat-threads v rid [tid] t) v)
                                 {:claimed xs :state (mt/pool-state v nm) :unmarked (unmarked? v rid tid)}])))))))

(defn complete!
  "The owner's outcome for descriptor id claimed with token; a value the meta-atom cannot hold (not data) fails the
   descriptor instead. True when it settled it."
  [dh id token ok? v]
  (let [t (now)
        settle (fn [m ok? v] (let [[m ch] (mt/complete m id token ok? v t)] [m [ch (get-in m [:descs id :pool])]]))
        [ok2? r] (h/attempt (fn [] (swap-forgetting! dh (fn [m fg] (settle (mt/forget m fg) ok? v)))))
        [ch pool] (if ok2?
                    r
                    (swap-meta! dh (fn [m] (settle m false
                                         {:class "eve.thread/host-object"
                                          :message (str "eve.thread: the task's " (if ok? "value" "error data")
                                                        " cannot be kept in a meta-atom: not data")
                                          :data nil}))))]
    ;; its waiters, a sweep, and the pool's idle threads (work :after it may now be claimable)
    (when ch (ring! dh [(rung/completion-word) (rung/descriptor-word id) (when pool (rung/pool-word pool))]))
    ch))

(defn- with-pool* [p f] (if-let [b (:bind @c/hooks)] (b p f) (f)))

(defn- call-thunk [f] (f))

(defn run-claimed!
  "Run domain descriptor x, claimed by thread tid of this runtime, here — *running* bound to it — and complete it."
  [dh x tid]
  (let [[ok? v] (binding [*running* {:dh dh :id (:id x) :pool (:pool x) :tid tid}]
                  (h/attempt (fn [] (task/run-work (:work x) call-thunk))))]
    (complete! dh (:id x) (:claim x) ok? (if ok? v (h/error-data v)))))

(defrecord DomainPool [name kind size dh])

(defn- pool-size [dh nm]
  (max 1 (reduce + 0 (map :threads (vals (read-meta dh [:pools nm :members]))))))

(defn- help-one!
  "Claim one queued descriptor of pool nm as thread tid and run it here — *pool* bound to the pool, as a pump binds it;
   true when one ran."
  [dh nm tid]
  (let [{:keys [claimed]} (claim! dh nm tid 1)]
    (when-let [x (first claimed)]
      (with-pool* (->DomainPool nm :domain (pool-size dh nm) dh) (fn [] (run-claimed! dh x tid)))
      true)))

(defn cancel-descriptor! [dh id]
  (let [t (now)
        ch (swap-meta! dh (fn [v] (mt/cancel v id t)))]
    (when ch (ring! dh [(rung/completion-word) (rung/descriptor-word id)]))
    ch))

(defn- lost [id]
  {:id id :state :failed
   :error {:class "eve.thread/lost" :message (str "eve.thread: descriptor " id " is not in the meta-atom") :data nil}})

(defn- entry-descriptor
  "Descriptor id as an entry sees it: read from the meta-atom until settled, then kept — and, for its submitter's own
   future (forget? true), forgotten there with this runtime's next swap. Any other reader of it (a task waiting on it)
   only reads: several may wait on one descriptor, and the first to see it settle must not take it from the others."
  [dh id cache forget?]
  (or @cache
      (let [x (read-meta dh [:descs id])]
        (cond
          (nil? x) (lost id)
          (d/settled? x) (do (clojure.core/reset! cache x)
                             (when forget? (clojure.core/swap! (:st dh) update :forget conj id))
                             x)
          :else x))))

(defn- block-mine! [dh run on]
  (swap-meta! dh (fn [v] [(mt/block v (:id run) on) nil])))

(defn- unblock-mine! [dh run]
  (swap-meta! dh (fn [v] [(mt/unblock v (:id run)) nil])))

(defn- await-descriptor
  "Wait for descriptor id to be done: the descriptor, or :eve.thread.cell/timeout after ms (< 0: for ever). Reads
   spaced 1 ms growing to poll-cap (this thread's messages handled between them) — or, where this host's region has a
   futex, a wait on the descriptor's doorbell, which its completion rings. A thread running a descriptor of this
   domain marks it :blocked while it waits (it holds a stack: it moves only as a restart) and, waiting without a bound,
   helps — claims and runs queued work of its pool. A runtime with no threads runs its pool's work in its waits."
  [dh id cache forget? ms]
  (c/call-in-loop
   nil
   (fn []
     (let [deadline (when-not (neg? ms) (+ (now) ms))
           run (when (= (:key (:dh *running*)) (:key dh)) *running*)
           threadless? (not (threaded? dh))]
       (loop [b 1 blocked? false]
         (let [seen (rung/bell-value (mcell dh) (rung/descriptor-word id))
               x (entry-descriptor dh id cache forget?)]
           (cond
             (d/done? x) (do (when blocked? (unblock-mine! dh run)) x)
             (and deadline (>= (now) deadline)) (do (when blocked? (unblock-mine! dh run)) :eve.thread.cell/timeout)
             :else
             (let [blocked? (or blocked? (when run (block-mine! dh run id) true))]
               (cond
                 (and run (nil? deadline) (help-one! dh (:pool run) (:tid run))) (recur 1 blocked?)
                 (and threadless? (help-one! dh (:pool x) "rt")) (recur 1 blocked?)
                 :else (do (when threadless? (threadless-beat! dh))
                           (idle-wait! dh (rung/descriptor-word id) seen b
                                       (if deadline (- deadline (now)) 100))
                           (recur (min (poll-cap dh) (* 2 b)) blocked?)))))))))))

(defn entry
  "An entry of domain descriptor id (eve.thread.cell's remote entry): its submitter's future's (opts :forget true —
   once it has read the descriptor settled, the meta-atom lets it go), or any other reader's (a task that waits on it)."
  ([dh id] (entry dh id {}))
  ([dh id opts]
   (let [cache (clojure.core/atom nil)
         forget? (boolean (:forget opts))]
     (c/remote-entry (fn [] (entry-descriptor dh id cache forget?))
                     (fn [ms] (await-descriptor dh id cache forget? ms))
                     (fn [] (cancel-descriptor! dh id))))))

;; ---------------------------------------------------------------------------
;; Domain pools
;; ---------------------------------------------------------------------------


(defn- pool-entry-of [dh nm] (read-meta dh [:pools nm]))

(defn- submit*
  [p f args opts]
  (let [dh (:dh p)
        nm (:name p)
        pd (get-in @(:st dh) [:pools nm :desc])
        work (task/data-work f args (boolean (:forms pd)) true)
        t (now)
        [ids why next-home] (swap-forgetting! dh (fn [v fg]
                                                  (let [[v ids] (mt/submit (mt/forget v fg) nm [work] (:rid dh) (or opts {}) t)]
                                                    [v [ids (when-not ids (mt/refusal v nm))
                                                        (get-in v [:pools nm :next-home])]])))]
    (when-not ids
      (throw (ex-info (str "eve.thread: domain pool " nm " takes no submission: " why)
                      (cond-> {:eve.thread/refused why :pool nm} next-home (assoc :next-home next-home)))))
    ;; the pool's idle threads, wherever they wait on its doorbell
    (ring! dh [(rung/pool-word nm)])
    (fut/future-of (entry dh (first ids) {:forget true}))))

(defn- stop-local-pumps! [dh nm]
  (when-let [tids (seq (get-in @(:st dh) [:pools nm :tids]))]
    (when-let [ticker (:ticker @(:st dh))]
      (h/send! ticker {:eve.thread/op :stop-pumps :tids (vec tids)}))))

(defn- wait-until
  ([ms pred] (wait-until ms pred 16))
  ([ms pred cap]
   (c/call-in-loop
    nil
    (fn []
      (let [deadline (when-not (neg? ms) (+ (now) ms))]
        (loop [b 1]
          (cond
            (pred) true
            (and deadline (>= (now) deadline)) false
            :else (let [m (h/receive! b)]
                    (when-not (= m :eve.thread/timeout) (c/handle! m))
                    (recur (min cap (* 2 b)))))))))))

(defn- terminated-in? [v nm]
  (and (not= :running (get-in v [:pools nm :state])) (mt/pool-idle? v nm)))

(extend-type DomainPool
  c/IPoolEngine
  (-pool-name [p] (:name p))
  (-cell [p] p)
  (-help [_] :any)
  (-threadless? [p] (not (threaded? (:dh p))))
  (-on-exit! [_ _] nil)
  (-on-member-update! [_ _] nil)
  ep/IExecutor
  (-submit [p f args opts] (submit* p f args opts))
  (-shutdown! [p]
    (let [dh (:dh p) nm (:name p)]
      (swap-meta! dh (fn [v] [(mt/shutdown-pool v nm) nil]))
      (ring! dh [(rung/pool-word nm)])
      nil))
  (-shutdown-now! [p]
    (let [dh (:dh p) nm (:name p) t (now)]
      (let [ids (swap-meta! dh (fn [v] (mt/shutdown-pool-now v nm t)))]
        (ring! dh (concat [(rung/completion-word) (rung/pool-word nm)] (map rung/descriptor-word ids)))
        (stop-local-pumps! dh nm)
        ids)))
  (-await-termination [p ms]
    (let [dh (:dh p) nm (:name p)]
      (wait-until ms (fn [] (rung/-read (mcell dh) (fn [v] (terminated-in? v nm)))) (poll-cap dh))))
  (-shutdown? [p] (not= :running (read-meta (:dh p) [:pools (:name p) :state])))
  (-terminated? [p] (rung/-read (mcell (:dh p)) (fn [v] (terminated-in? v (:name p)))))
  ep/IPoolDescriptor
  (-pool-descriptor [p]
    ;; the cache: what this runtime claimed of the pool, as it read it (the meta-atom's :pools is the authority)
    (let [{:keys [desc tids]} (get-in @(:st (:dh p)) [:pools (:name p)])]
      (assoc desc :rung (get mt/rungs (via (:dh p)) 0) :via (via (:dh p)) :form :domain :threads (vec tids)
             :domain (:base (:dh p)) :atom (:name (:dh p)) :rid (:rid (:dh p))))))

;; the domain cell as eve.thread.cell's seam: the pool is its own cell
(extend-type DomainPool
  c/ICell
  (-submit! [_ _] false)
  (-claim-next! [p]
    (let [run *running*]
      (help-one! (:dh p) (:name p) (or (:tid run) "rt"))))
  ;; (queue-state takes the thread whose mark it reads: none here — compiled ClojureScript warned of the arity, and the
  ;; JVM would throw it)
  (-claimable? [p] (first (queue-state (:dh p) (:name p) nil)))
  (-idle! [_ _] nil)
  (-unidle! [_ _] false)
  (-wake-all! [_] nil)
  (-drain! [p] (mapv (fn [id] (entry (:dh p) id)) (ep/shutdown-now! p))))

;; The cell's swap cost — what fold and =>> size their chunks by (eve.thread.fold, P§5.7): P§5.4's :measured
;; {:swap-us :carry-ns} in each runtime's descriptor, the slowest member's read by whoever folds over the pool.

(defn- timed-swaps
  "[swaps ms]: (f k) swapped into the meta-atom k = 0, 1, ... until 20 ms or 64 swaps."
  [dh f]
  (let [t0 (now)]
    (loop [k 0]
      (if (or (>= (- (now) t0) 20) (>= k 64))
        [k (- (now) t0)]
        (do (swap-meta! dh (fn [m] [(f m k) nil])) (recur (inc k)))))))

(defn- measure-swap-cost!
  "This runtime's cost of the meta-atom's swaps: µs per swap (:swap-us — real swaps, each writing the count so far into
   this runtime's descriptor) and ns per element a swap writes (:carry-ns — swaps writing fold/carry-probe elements
   there: a descriptor's data travels in it); then both written there as its :measured."
  [dh]
  (let [rid (:rid dh)
        put (fn [m k v] (if (mt/joined? m rid) (mt/tick (assoc-in m [:runtimes rid :measured k] v)) m))
        xs (vec (range fold/carry-probe))
        ;; (one pass untimed: a cold JIT's swaps are not this runtime's cost)
        _ (timed-swaps dh (fn [m k] (put m :swaps k)))
        [k0 ms0] (timed-swaps dh (fn [m k] (put m :swaps k)))
        [k1 ms1] (timed-swaps dh (fn [m k] (put m :carrying (if (even? k) xs (pop xs)))))
        per0 (quot (* 1000000 ms0) k0)
        per1 (quot (* 1000000 ms1) k1)
        cost {:swap-us (max 1 (quot (+ per0 999) 1000)) :carry-ns (max 0 (quot (- per1 per0) fold/carry-probe))}]
    (clojure.core/swap! (:st dh) assoc :measured cost)
    (swap-meta! dh (fn [m] [(if (mt/joined? m rid)
                              (mt/tick (update-in m [:runtimes rid :measured] (fn [x] (merge (dissoc x :carrying) cost))))
                              m)
                            nil]))
    cost))

(defn swap-cost
  "{:swap-us :swaps :carry-ns} of domain pool p: the slowest member's measured costs of the meta-atom's swaps (this
   runtime's own measured first when it has none), and the swaps a descriptor makes here: its submission, its claim,
   its completion."
  [p]
  (let [dh (:dh p)
        rid (:rid dh)
        kept (:measured @(:st dh))
        own (or (read-meta dh [:runtimes rid :measured :swap-us])
                ;; measured before, and gone from the meta-atom (a sweep took this runtime out; a rejoin by another of
                ;; its threads' handles wrote its descriptor without it): published again
                (when kept
                  (swap-meta! dh (fn [m] [(if (mt/joined? m rid) (mt/tick (assoc-in m [:runtimes rid :measured] kept)) m) nil]))
                  (:swap-us kept))
                (:swap-us (measure-swap-cost! dh)))
        ms (keep (fn [rid] (read-meta dh [:runtimes rid :measured]))
                 (distinct (cons (:rid dh) (keys (read-meta dh [:pools (:name p) :members])))))]
    {:swap-us (reduce max own (keep :swap-us ms)) :carry-ns (reduce max 0 (keep :carry-ns ms)) :swaps 3}))

(fold/register-swap-cost! :domain swap-cost)

(defn- home-of
  "Where a pool registered through this runtime lives: the rendezvous atom's location (no credential: a network
   location's key stays in the handle)."
  [dh]
  (let [loc (rung/-locate (:rung (:cell dh)))]
    (-> loc (dissoc :name :api-key :gak) (assoc :atom (:name loc)))))

(defn pool
  "Pool nm of the domain as this runtime sees it: registered in the meta-atom unless it is there (the first
   registration's descriptor stands: opts :forms — forms may cross, P§10 Q4 — and :retry, the default restart type),
   and served by (:threads opts) threads of this runtime (0, the default: this runtime only submits). The pool, which
   eve.thread's executor protocol, futures, pmap and with-pool take; this runtime's registry caches it."
  ([dh nm] (pool dh nm {}))
  ([dh nm opts]
   (swap-meta! dh (fn [v] [(mt/register-pool v {:name nm :kind :domain :forms (boolean (:forms opts))
                                               :retry (or (:retry opts) :never)
                                               :home (home-of dh)})
                           nil]))
   (let [n (or (:threads opts) 0)
         tids (if (pos? n)
                (:tids (ask-ticker! dh {:eve.thread/op :pumps :pool nm :n n :batch (or (:batch opts) 1)}
                                    :pumps-started))
                [])]
     (when (seq tids)
       (swap-meta! dh (fn [v] [(mt/join-pool v nm (:rid dh) tids) nil]))
       ;; started = pulling: every new thread has entered its loop (an isolate loads the program first, for seconds)
       (when-not (wait-until 120000 (fn [] (every? (fn [tid] (some? (read-meta dh [:runtimes (:rid dh) :up tid]))) tids)))
         (throw (ex-info (str "eve.thread.domain: pool " nm "'s threads did not start") {:pool nm :tids tids}))))
     ;; (the read made first: a read keeps its cost in (:st dh), so it may not run inside a swap of it)
     (let [desc (pool-entry-of dh nm)]
       (clojure.core/swap! (:st dh) update-in [:pools nm]
                           (fn [x] {:desc desc :tids (into (vec (:tids x)) tids)})))
     (let [p (->DomainPool nm :domain (pool-size dh nm) dh)]
       (c/register-pool! p)
       p))))

(defn- start-runtime [dh _] (:rid dh))

(defn- start-pool [dh c] (let [spec (:spec c)] (pool dh (:name spec) spec) (:name spec)))

(defn starters
  "The component kinds bring-up starts by itself: :runtime — up once the runtime it is :for claims it (that runtime has
   attached); :pool — this runtime's part of the domain pool its :spec names ({:name :threads :forms :retry :batch}),
   its result the pool's name. (start! takes these merged with an application's own. A fn, not a def: clojerl
   compiles a def's value as a literal, and a fn is not one.)"
  []
  {:runtime start-runtime :pool start-pool})

(defn pump-entry
  "A pump thread of a domain pool (boot: its runtime's handle as data, :pool, :batch): claim, run, complete — idle,
   it waits on the pool's doorbell (a futex, which a submission rings) where its host's region has one, else reads
   spaced 1 ms growing to poll-cap — until stopped, or the pool is shut down and holds nothing for it."
  [b]
  (let [dh (resolve-handle b)
        nm (:pool b)
        tid (str (h/self))
        p (->DomainPool nm :domain (pool-size dh nm) dh)]
    (swap-meta! dh (fn [v] [(mt/thread-up v (:rid dh) tid) nil]))
    (c/call-in-loop
     p
     (fn []
       (with-pool*
         p
         (fn []
           (loop [bo 1]
             ;; a stop that came while a task ran is seen before the next claim
             (let [msg (h/receive! 0)] (when-not (= msg :eve.thread/timeout) (c/handle! msg)))
             (if (c/stop-requested?)
               :stopped
               (let [seen (rung/bell-value (mcell dh) (rung/pool-word nm))
                     {:keys [claimed state unmarked]} (claim! dh nm tid (or (:batch b) 1))]
                 ;; (recorded pulling again after its runtime joined again: a pool's start waits on it)
                 (when unmarked (swap-meta! dh (fn [v] [(mt/thread-up v (:rid dh) tid) nil])))
                 (cond
                   (seq claimed) (do (doseq [x claimed] (run-claimed! dh x tid)) (recur 1))
                   (not= :running state) :done
                   :else (do (idle-wait! dh (rung/pool-word nm) seen bo)
                             (recur (min (poll-cap dh) (* 2 bo))))))))))))))

;; ---------------------------------------------------------------------------
;; Drivers: how a thread waiting on many descriptors waits (P§5.7)
;; ---------------------------------------------------------------------------

(defn choose-driver
  "The strategy P§5.7 reads from the host's declared driving properties and the rung's wake model: :sweep where this
   thread cannot block (:block :never) or where a wait is a poll (no futex doorbell here: a waiter per descriptor would
   be as many readers), :waiters where blocking is cheap (:cheap — virtual threads, BEAM processes) and a doorbell wakes
   each, :in-order otherwise."
  [dh]
  (let [{:keys [block]} (h/driving)]
    (cond (= :never block) :sweep
          (not (rung/futex? (mcell dh))) :sweep
          (= :cheap block) :waiters
          :else :in-order)))

(defn- settled-of
  "Of ids, those settled, as {id descriptor} — one read of the meta-atom (pinned on an eve rung)."
  [dh ids]
  (read-timed dh (fn [v] (into {} (keep (fn [id] (let [x (get-in v [:descs id])]
                                                    (when (or (nil? x) (d/done? x)) [id (ed/eve->clj x)]))))
                               ids))))

(defn- collect-sweep
  [dh ids]
  (c/call-in-loop
   nil
   (fn []
     (let [pool (when-not (threaded? dh) (read-meta dh [:descs (first ids) :pool]))]
       (loop [pending (vec ids) got {} b 1]
         (if (empty? pending)
           got
           (let [seen (rung/bell-value (mcell dh) (rung/completion-word))
                 done (settled-of dh pending)
                 got (merge got (into {} (map (fn [[id x]] [id (or x (lost id))])) done))
                 left (filterv (fn [id] (not (contains? done id))) pending)]
             (cond
               (seq done) (recur left got 1)
               ;; (a runtime with no threads runs its pool's work in its waits, and keeps its lease there)
               (and pool (help-one! dh pool "rt")) (recur left got 1)
               :else (do (when pool (threadless-beat! dh))
                         (idle-wait! dh (rung/completion-word) seen b)
                         (recur left got (min (poll-cap dh) (* 2 b))))))))))))

(defn- settled-now?
  "Is descriptor id done, or gone — read from its state alone (a waiter needs no more of it)."
  [dh id]
  (read-timed dh (fn [v] (let [x (get-in v [:descs id])]
                           (or (nil? x)
                               (d/done? {:state (ed/eve->clj (get x :state))
                                         :cancel-requested (ed/eve->clj (get x :cancel-requested))}))))))

(defn waiter-entry
  "A waiter thread (P§5.7's :waiters): block on one descriptor's doorbell (a poll where the host's region has no futex)
   until it is done, then tell the thread that started this one (boot: the handle as data, :id, :reply). It reads the
   descriptor's state, never its value: the collector reads the values, in one pinned read, and no value is copied
   into a message."
  [b]
  (let [dh (resolve-handle b)
        id (:id b)]
    (c/call-in-loop
     nil
     (fn []
       (loop [bk 1]
         (let [seen (rung/bell-value (mcell dh) (rung/descriptor-word id))]
           (when-not (settled-now? dh id)
             (idle-wait! dh (rung/descriptor-word id) seen bk 100)
             (recur (min (poll-cap dh) (* 2 bk))))))))
    (h/send! (:reply b) {:eve.thread/op :waited :id id})
    :done))

(defn- collect-waiters
  [dh ids]
  (let [{:keys [block closures]} (h/driving)
        kind (if (= :cheap block) :virtual :platform)
        want (set ids)
        n (count want)]
    ;; a thread per descriptor where a thread is an isolate (Node, a browser: :closures false) is an isolate per
    ;; descriptor — ~335 MB each on nbb with eve loaded (twelve took it from 1.4 to 5.4 GB, measured), not returned when
    ;; they end. P§5.7 chooses :waiters only where blocking is cheap; asked for here, it is refused, not started.
    (when (false? closures)
      (throw (ex-info (str "eve.thread.domain: :waiters starts a thread per descriptor, and a thread here is an isolate "
                           "(each loads its own eve) — collect with :sweep or :in-order")
                      {:driver :waiters :descriptors (count ids) :driving (h/driving)})))
    (doseq [id ids]
      (h/start-threads! 1 'eve.thread.domain/waiter-entry (assoc (boot-of dh) :id id :reply (h/self))
                        (assoc (thread-opts dh "eve-domain-waiter") :kind kind)))
    (c/call-in-loop
     nil
     (fn []
       ;; (counted, not compared: clojerl's = of a set with another checks only that the first's members are in the
       ;; second — (= #{} want) is true there, and the collector read before any waiter had signalled, measured)
       (loop [waited #{}]
         (when (< (count waited) n)
           (let [m (h/receive! -1)]
             (cond
               (and (map? m) (= :waited (:eve.thread/op m)))
               (recur (if (contains? want (:id m)) (conj waited (:id m)) waited))
               ;; (each waiter's exit notice comes here, its starter)
               (and (map? m) (contains? m :eve.thread/exit)) (recur waited)
               :else (do (c/handle! m) (recur waited))))))))
    (settled-of dh ids)))

(defn collect
  "The descriptors ids, settled, in their order — waited for by driver (:in-order: each in turn; :sweep: one reader,
   every pending id in one read per pass, passes spaced by the completions doorbell or a poll; :waiters: a waiter
   thread per descriptor, each on its own doorbell; nil: choose-driver's). This runtime's next swap forgets them in the
   meta-atom."
  ([dh ids] (collect dh ids nil))
  ([dh ids driver]
   (let [driver (or driver (choose-driver dh))
         got (case driver
               :in-order (into {} (map (fn [id] [id (await-descriptor dh id (clojure.core/atom nil) false -1)])) ids)
               :sweep (collect-sweep dh ids)
               :waiters (if (threaded? dh) (collect-waiters dh ids) (collect-sweep dh ids)))]
     (clojure.core/swap! (:st dh) update :forget into ids)
     (mapv got ids))))

(defn submit-all!
  "Descriptors for each work of works ([task args] pairs: registered task names) queued on domain pool p in ONE swap;
   their ids."
  [p works]
  (let [dh (:dh p)
        nm (:name p)
        pd (get-in @(:st dh) [:pools nm :desc])
        ws (mapv (fn [[f args]] (task/data-work f args (boolean (:forms pd)) true)) works)
        t (now)
        [ids why] (swap-forgetting! dh (fn [v fg]
                                        (let [[v ids] (mt/submit (mt/forget v fg) nm ws (:rid dh) {} t)]
                                          [v [ids (when-not ids (mt/refusal v nm))]])))]
    (when-not ids
      (throw (ex-info (str "eve.thread: domain pool " nm " takes no submission: " why) {:eve.thread/refused why :pool nm})))
    (ring! dh [(rung/pool-word nm)])
    ids))

(defn reads
  "How many reads of the meta-atom this handle has made (read-timed's count): a driver's cost in reads."
  [dh]
  (or (:reads @(:st dh)) 0))

;; ---------------------------------------------------------------------------
;; The registry: the meta-atom's, one for every attached runtime
;; ---------------------------------------------------------------------------

(defn registry
  "The registry every attached runtime reads: each runtime's descriptor (leases aside) and each pool with its members."
  [dh]
  (rung/-read (mcell dh) (fn [v] (mt/registry (ed/eve->clj (select-keys v [:runtimes :pools]))))))

(defn halt!
  "This runtime stops renewing its leases and its pump threads stop after what they run, without leaving — as if its
   process had died (a test's kill): once its ttl passes, any other runtime's sweep adopts what it held."
  [dh]
  (when-let [t (:ticker @(:st dh))]
    (h/send! t {:eve.thread/op :halt})
    (clojure.core/swap! (:st dh) assoc :ticker nil))
  (clojure.core/swap! handles dissoc (:key dh))
  nil)

(defn detach!
  "This runtime leaves: its pump threads stopped, its ticker ended, its descriptor taken out of the registry (what its
   threads still held is adoptable at once), its domain pools out of its local registry."
  [dh]
  (when-let [t (:ticker @(:st dh))]
    (h/send! t :eve.thread/stop)
    (c/call-in-loop
     nil
     (fn []
       (let [deadline (+ (now) (* 4 (:ttl dh)))]
         (loop []
           (let [m (h/receive! (max 1 (- deadline (now))))]
             (cond
               (= m :eve.thread/timeout) nil
               (and (map? m) (= t (:eve.thread/exit m))) nil
               :else (do (c/handle! m) (recur)))))))))
  (swap-forgetting! dh (fn [v fg] [(mt/leave (mt/forget v fg) (:rid dh)) nil]))
  (doseq [nm (keys (:pools @(:st dh)))]
    (when-let [p (c/find-pool nm)] (when (= (:key (:dh p)) (:key dh)) (c/unregister-pool! p))))
  (clojure.core/swap! handles dissoc (:key dh))
  nil)

;; ---------------------------------------------------------------------------
;; Bring-up: a desired set in one swap, components claimed and walked to :running in dependency order
;; ---------------------------------------------------------------------------

(defn desire!
  "The desired set comps ({cid {:kind k :after #{cid ...} :for rid :spec ...}}) swapped in, in one move — refused
   (thrown, nothing swapped in) when it has a cycle or an unknown dependency."
  [dh comps]
  ;; validated against the value as read first: a throw inside a swap would abandon an attempt's allocations
  (mt/desire {:components (read-meta dh [:components])} comps)
  (swap-meta! dh (fn [v] [(mt/desire v comps) nil])))

(defn start!
  "This runtime's part of a bring-up: it claims, in rank order, each desired component whose dependencies are all
   :running, that is for it (:for nil or its rid) and of a kind starters has a fn for, and walks it — :claimed,
   :starting, then :running with what (starter dh component) answers as its :result, or :failed with the error — until
   nothing desired is left for it or every component is running, or ms pass. The cids it brought up, in order."
  [dh starters ms]
  (let [deadline (+ (now) ms)
        rid (:rid dh)
        owner (mt/owner-of rid "rt")]
    (c/call-in-loop
     nil
     (fn []
       (loop [mine [] b 1]
         (let [t (now)
               cid (swap-meta! dh (fn [v] (mt/claim-component v rid "rt" (fn [c] (contains? starters (:kind c))) t)))]
           (if cid
             (let [c (do (swap-meta! dh (fn [v] [(mt/advance-component v cid owner :starting {}) nil]))
                         (read-meta dh [:components cid]))
                   [ok? r] (h/attempt (fn [] ((get starters (:kind c)) dh c)))]
               (swap-meta! dh (fn [v] [(mt/advance-component v cid owner (if ok? :running :failed)
                                                            (if ok? {:result r} {:error (h/error-data r)}))
                                       nil]))
               (recur (conj mine cid) 1))
             (let [comps (read-meta dh [:components])
                   left (filter (fn [c] (and (contains? #{:desired :claimed :starting} (:state c))
                                             (or (nil? (:for c)) (= rid (:for c)))
                                             (contains? starters (:kind c))))
                                (vals comps))]
               (if (or (empty? left) (>= (now) deadline))
                 mine
                 (let [m (h/receive! b)]
                   (when-not (= m :eve.thread/timeout) (c/handle! m))
                   (recur mine (min 16 (* 2 b)))))))))))))

;; ---------------------------------------------------------------------------
;; Election by lease (P§5.6)
;; ---------------------------------------------------------------------------

(defn elect!
  "This runtime takes lease lname (:leader for P§5.6's election) when it is absent or expired, for ttl ms; true when it
   holds it. Held, the lease is renewed by this runtime's ticker every beat until resign! (or this runtime dies: then
   anyone may take it once ttl passes)."
  ([dh lname] (elect! dh lname (:ttl dh)))
  ([dh lname ttl]
   (let [t (now)
         ;; (at least this runtime's own lease: its ticker renews at that cadence)
         won? (swap-meta! dh (fn [v] (mt/acquire v lname (:rid dh) (max ttl (or (get-in v [:runtimes (:rid dh) :ttl]) 0)) t)))]
     (when (and won? (:ticker @(:st dh)))
       (h/send! (:ticker @(:st dh)) {:eve.thread/op :hold :lease lname :ttl ttl}))
     won?)))

(defn resign!
  "Give up lease lname (only its holder can)."
  [dh lname]
  (when-let [t (:ticker @(:st dh))] (h/send! t {:eve.thread/op :release :lease lname}))
  (swap-meta! dh (fn [v] [(mt/release v lname (:rid dh)) nil])))

(defn leader
  "The runtime holding lease lname now, or nil."
  ([dh] (leader dh :leader))
  ([dh lname] (let [t (now)] (rung/-read (mcell dh) (fn [v] (mt/holder v lname t))))))

;; ---------------------------------------------------------------------------
;; Transports and the upgrade protocol (P§5.5), over two meta-atoms
;; ---------------------------------------------------------------------------

(defn advertise!
  "This runtime's transports, and the nonce it proves co-hosting with, in its descriptor on dh's rung."
  [dh transports nonce]
  (swap-meta! dh (fn [v] [(mt/advertise v (:rid dh) transports nonce) nil])))

(defn co-hosted
  "The runtimes of dh's registry that advertise this runtime's :host and a :disk or :window transport."
  [dh]
  (rung/-read (mcell dh) (fn [v] (ed/eve->clj (mt/co-hosted v (:rid dh))))))

(defn ack-hellos!
  "As a runtime attached to both rungs: every unacknowledged hello in disk-dh's meta-atom whose nonce equals the
   :hello-nonce its runtime advertises on net-dh's rung is acknowledged (P§5.5 step 3: a shared filesystem and memory,
   not an equal hostname). The rids acknowledged."
  [disk-dh net-dh]
  (let [hellos (read-meta disk-dh [:hello])
        rts (read-meta net-dh [:runtimes])]
    (vec (keep (fn [[rid h]]
                 (let [nonce (get-in rts [rid :hello-nonce])]
                   (when (and (nil? (:acked-by h)) (some? nonce) (= nonce (:nonce h))
                              (swap-meta! disk-dh (fn [v] (mt/ack-hello v rid (:rid disk-dh) nonce))))
                     rid)))
               hellos))))

(defn hello!
  "P§5.5's upgrade, steps 1-2, by a runtime joined on net-dh's rung that finds a co-hosted runtime there: it opens the
   advertised domain's rendezvous atom ((open transport) → the eve atom), attaches to it, and writes a fresh nonce as
   its hello there and the same nonce into its descriptor on net-dh's rung. {:disk dh :nonce n}, or nil when no
   co-hosted runtime advertises a disk or window transport."
  [net-dh open]
  (when-let [{:keys [transport]} (first (co-hosted net-dh))]
    (let [nonce (str (:rid net-dh) "/" (rand-int 1000000000) "/" (now))
          disk-dh (attach! (open transport) {:rid (:rid net-dh) :ttl (:ttl net-dh) :caps (:caps net-dh)
                                             :requires (:requires net-dh)})]
      (swap-meta! disk-dh (fn [v] [(mt/hello v (:rid net-dh) nonce) nil]))
      (advertise! net-dh (read-meta net-dh [:runtimes (:rid net-dh) :transports]) nonce)
      {:disk disk-dh :nonce nonce})))

(defn await-ack!
  "Step 3-4 of the upgrade: wait up to ms for an attached runtime's acknowledgement of the hello (ack-hellos!). The
   disk handle when acknowledged — this runtime now pulls from pools homed there — else nil, the disk attachment undone
   (it stays on the network rung: step 5)."
  [{:keys [disk nonce]} ms]
  (if (wait-until ms (fn [] (rung/-read (mcell disk) (fn [v] (mt/hello-acked? v (:rid disk) nonce)))))
    disk
    (do (detach! disk) nil)))

(defn upgrade!
  "The whole upgrade by the runtime on net-dh's rung (hello!, then await-ack! up to ms): the disk handle, or nil."
  [net-dh open ms]
  (when-let [h (hello! net-dh open)] (await-ack! h ms)))

(defn migrate-pool!
  "A pool's home moves by drain (P§5.5 step 4): on the old home (dh) it is marked :draining with next-home — new
   submissions are refused there, naming next-home — while its queued work finishes."
  [dh nm next-home]
  (swap-meta! dh (fn [v] [(mt/drain-pool v nm next-home) nil])))
