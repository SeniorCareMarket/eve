(ns eve.thread.meta
  "The meta-atom's state: eve.thread's coordination as plain data (2026-10-19, which
   supersedes the plan's \"reserved atoms in the domain's metadata\", P§5.4).

   An atom may have a META-ATOM — a second atom paired with it (eve.atom/meta-atom: registered in the domain's registry
   under a reserved name derived from the atom's own) — holding its coordination state as plain data: queues,
   descriptors, leases. It is read and swapped with the ordinary mechanics, and a coordination swap never touches the
   main atom, so the main atom's value, its root pointer (its version), its registry entry and its readers are
   untouched by bookkeeping, and the two traffic classes do not contend. There is no coordinator process: state lives
   as descriptors with fixed legal-transition tables, every move is one swap any participant may make, and liveness is
   a lease at the grain of the work (a thread's, for the work a thread holds).

   This namespace is that value's schema and every move on it, as pure functions of the value — and of now (ms), which
   the caller passes, so a swap's retries apply one instant. The same functions run inside a swap of the meta-atom
   (eve.thread.domain) and over a plain map (the tests' interleavings). The value:

     {:clock n                            every move ticks it: the order of events, readable without wall clocks
      :seq n                              the descriptor id counter
      :runtimes {rid runtime}             who is attached (join, heartbeat, leave) — P§5.4's runtime descriptor
      :pools {pool pool}                  each pool's descriptor and its members {rid {:threads n}}
      :q {pool [id ...]}                  each pool's queued ids, first in first out (a restart goes to the back)
      :descs {id descriptor}              the thread descriptors (eve.thread.descriptor: the same table as ET-1's)
      :components {cid component}         bring-up: the desired set, each component walked to :running in topo order
      :leases {name {:rid :until :term}}  named leases — :leader is §5.6's election
      :hello {rid {:nonce :acked-by}}}    the upgrade protocol's proofs of co-hosting (P§5.5)

   A descriptor's owner is a thread of a runtime, written \"rid#tid\" with :orid and :otid beside it; the owner is
   alive while its runtime's lease (:hb within :ttl) and its thread's lease (:thread-hb tid within :ttl) both hold —
   \"rt\" is a runtime's thread outside its pools, covered by the runtime's lease. A running or blocked descriptor
   whose owner is not alive is adopted by whoever sweeps next: re-queued as a restart (:retry :restart, attempts + 1)
   or failed :owner-died (:retry :never). Queued work is data and moves by claim; blocked work holds a stack and moves
   only as that restart."
  (:require [eve.thread.descriptor :as d]))

;; ---------------------------------------------------------------------------
;; The clock, and moves with an answer
;; ---------------------------------------------------------------------------

(defn clock [m] (or (:clock m) 0))

(defn tick [m] (assoc m :clock (inc (clock m))))

;; ---------------------------------------------------------------------------
;; Runtimes: join, heartbeat, leave, liveness (P§5.4's runtime descriptor; thread-grain leases)
;; ---------------------------------------------------------------------------

(def default-ttl-ms 3000)

(defn join
  "Runtime rt ({:rid ...} — P§5.4's fields: :kind :host :pid :threads :driving :tasks :caps :transports :ttl) joined:
   its own lease, at least the fleet's (the cadence every member renews at: fleet-ttl), and no thread leases yet. A
   runtime that joins again is the same runtime coming back: what it held was adopted when its lease expired, and it
   starts from nothing."
  [m rt now]
  (let [rid (:rid rt)
        fleet (reduce max 0 (keep :ttl (vals (:runtimes m))))]
    (-> m
        (assoc-in [:runtimes rid] (assoc rt :hb now :thread-hb {} :ttl (max (or (:ttl rt) default-ttl-ms) fleet)
                                         :joined (clock m) :state :joined))
        tick)))

(defn joined? [m rid] (some? (get-in m [:runtimes rid])))

(defn heartbeat
  "Renew runtime rid's lease and the leases of its threads tids; m unchanged when rid is not joined (its lease expired
   and it was swept: it joins again). A lease never moves back: a renewal whose now is older than the one written
   (a thread that read its clock, then waited for its swap) leaves the newer."
  [m rid tids now]
  (if (joined? m rid)
    (let [later (fn [t] (if (and t (> t now)) t now))]
      (reduce (fn [m tid] (update-in m [:runtimes rid :thread-hb tid] later))
              (update-in m [:runtimes rid :hb] later)
              tids))
    m))

(defn stretch-ttl
  "Runtime rid's lease horizon raised to at least ttl, never lowered: a runtime whose own beats are slow — a host whose
   swaps cost tens of ms, losing races to faster hosts' — lengthens its lease to a multiple of what a beat costs it
   (the plan's reading, P§6.3a: a lease horizon is a multiple of the heartbeat jitter measured, not a constant)."
  [m rid ttl]
  (if (and (joined? m rid) (> ttl (or (get-in m [:runtimes rid :ttl]) 0)))
    (assoc-in m [:runtimes rid :ttl] ttl)
    m))

(defn fleet-ttl
  "The longest lease horizon among the joined runtimes: the cadence every member renews at (a member that renews faster
   than the slowest member can make one swap starves it: an optimistic swap whose attempt outlasts the others' commit
   interval never commits)."
  [m]
  (reduce max 0 (keep :ttl (vals (:runtimes m)))))

(defn heartbeat-threads
  "Renew only the leases of rid's threads tids (a thread shows it is alive by claiming; the runtime's own lease is its
   ticker's to renew — a runtime whose ticker died is not kept alive by its threads' claims)."
  [m rid tids now]
  (if (joined? m rid)
    (reduce (fn [m tid] (update-in m [:runtimes rid :thread-hb tid] (fn [t] (if (and t (> t now)) t now)))) m tids)
    m))

(defn thread-up
  "Thread tid of rid has started pulling (its pump loop runs): recorded under [:runtimes rid :up tid] with the clock —
   a pool's start is observable to its last thread."
  [m rid tid]
  (if (joined? m rid) (tick (assoc-in m [:runtimes rid :up tid] (clock m))) m))

(defn drop-threads
  "Threads tids of rid have ended: their leases are gone now (what they held is adoptable at once)."
  [m rid tids]
  (if (joined? m rid)
    (-> m
        (update-in [:runtimes rid :thread-hb] (fn [hb] (reduce dissoc hb tids)))
        (update-in [:runtimes rid :up] (fn [up] (reduce dissoc up tids))))
    m))

(defn- remove-member [m rid]
  (reduce (fn [m pool] (if (get-in m [:pools pool :members rid])
                         (update-in m [:pools pool :members] dissoc rid)
                         m))
          m (keys (:pools m))))

(defn leave
  "Runtime rid leaves: its descriptor and its pool memberships are gone (what it still held is adoptable at once)."
  [m rid]
  (if (joined? m rid)
    (-> m (update :runtimes dissoc rid) (remove-member rid) tick)
    m))

(defn runtime-alive? [m rid now]
  (let [r (get-in m [:runtimes rid])]
    (and (some? r) (< (- now (:hb r)) (:ttl r)))))

(defn owner-of
  "The owner string of thread tid of runtime rid."
  [rid tid]
  (str rid "#" tid))

(defn owner-alive?
  "Is the thread holding descriptor or component x alive: its runtime's lease and its thread's lease hold (thread \"rt\"
   is the runtime's own, outside its pools: its lease is the runtime's)."
  [m x now]
  (let [rid (:orid x)
        tid (:otid x)
        r (get-in m [:runtimes rid])]
    (and (some? r)
         (< (- now (:hb r)) (:ttl r))
         (or (= tid "rt")
             (let [t (get-in r [:thread-hb tid])]
               (and (some? t) (< (- now t) (:ttl r))))))))

(defn expire-runtimes
  "Runtimes whose own lease has expired are taken out (they join again if they come back), each expiry recorded under
   [:expired rid] {:hb its last renewal :at now :clock}; their rids."
  [m now]
  (let [dead (vec (keep (fn [[rid r]] (when-not (< (- now (:hb r)) (:ttl r)) [rid (:hb r)])) (:runtimes m)))]
    [(reduce (fn [m [rid hb]] (-> (leave m rid) (assoc-in [:expired rid] {:hb hb :at now :clock (clock m)}))) m dead)
     (mapv first dead)]))

;; ---------------------------------------------------------------------------
;; Pools: the registry is the meta-atom's; a runtime's own is a cache of what it claimed
;; ---------------------------------------------------------------------------

(defn register-pool
  "Pool descriptor pd (:name :kind :forms :retry ...) registered unless a pool of that name is there already (the
   first registration's descriptor stands); state :running, no members yet."
  [m pd]
  (if (get-in m [:pools (:name pd)])
    m
    (-> m (assoc-in [:pools (:name pd)] (assoc pd :state :running :members {} :registered (clock m))) tick)))

(defn join-pool
  "Runtime rid serves pool with its threads tids."
  [m pool rid tids]
  (if (and (get-in m [:pools pool]) (joined? m rid))
    (-> m
        (assoc-in [:pools pool :members rid] {:threads (count tids) :tids (vec tids)})
        (update-in [:runtimes rid :pools] (fnil conj #{}) pool)
        tick)
    m))

(defn leave-pool [m pool rid]
  (cond-> m
    (get-in m [:pools pool :members rid]) (update-in [:pools pool :members] dissoc rid)
    (joined? m rid) (update-in [:runtimes rid :pools] (fnil disj #{}) pool)))

(defn publish-local-pools
  "Runtime rid's own pools (rung 0: its threads only, eve.thread.pool's) as its descriptor carries them — what it runs
   besides the domain's pools, visible in the one registry."
  [m rid descs]
  (if (joined? m rid) (tick (assoc-in m [:runtimes rid :local-pools] descs)) m))

(defn pool-state [m pool] (get-in m [:pools pool :state]))

(defn registry
  "The registry as one view: every runtime (its descriptor less the leases — renewals and horizon, which move with
   every beat) and every pool with its members."
  [m]
  {:runtimes (into {} (map (fn [[rid r]] [rid (dissoc r :hb :thread-hb :ttl)])) (:runtimes m))
   :pools (into {} (:pools m))})

;; ---------------------------------------------------------------------------
;; Thread descriptors: submit, claim, block, complete, cancel, adopt (eve.thread.descriptor's table)
;; ---------------------------------------------------------------------------

(defn submit
  "Descriptors for works (each work's data) queued on pool, from runtime origin: [m ids], ids nil (and m unchanged)
   when the pool is not running — the reason in (refusal m pool). opts: :retry (else the pool's), :scope, :after
   (ids every one of these waits on), :needs (caps a claimer must have), :priority."
  [m pool works origin opts now]
  (if-not (= :running (pool-state m pool))
    [m nil]
    (let [n0 (or (:seq m) 0)
          ids (mapv (fn [i] (str "d" (+ n0 i))) (range (count works)))
          retry (or (:retry opts) (get-in m [:pools pool :retry]) :never)
          m (reduce (fn [m [id w]]
                      (assoc-in m [:descs id]
                                (assoc (d/descriptor id w (merge (select-keys opts [:scope :after :needs :priority])
                                                                 {:pool pool :origin origin :retry retry :submitted now}))
                                       :seqno (clock m))))
                    m (map vector ids works))]
      [(-> m
           (assoc :seq (+ n0 (count works)))
           (update-in [:q pool] (fn [q] (into (vec q) ids)))
           tick)
       ids])))

(defn refusal
  "Why pool takes no submission: :no-pool, :shutdown, :stopped, :draining; nil when it does."
  [m pool]
  (let [s (pool-state m pool)]
    (cond (nil? s) :no-pool (= :running s) nil :else s)))

(defn- input-states [m dd] (map (fn [a] (get-in m [:descs a :state])) (:after dd)))

(defn- inputs-done? [m dd] (every? (fn [s] (= :done s)) (input-states m dd)))

(defn- inputs-dead?
  "An input of dd failed or was cancelled (or is gone): dd can never run."
  [m dd]
  (some (fn [s] (or (nil? s) (= :failed s) (= :cancelled s))) (input-states m dd)))

(defn claimable?
  "May a thread whose runtime has caps (a set) and runs what (can-run? work) answers claim dd: queued, every :after
   input done, every :needs a cap."
  [m dd caps can-run?]
  (and (= :queued (:state dd))
       (inputs-done? m dd)
       (every? (fn [n] (contains? caps n)) (:needs dd))
       (can-run? (:work dd))))

(defn claim
  "Thread tid of runtime rid claims up to k queued descriptors of pool it may run (claimable?), first in first out:
   each :queued → :running, owned by rid#tid, attempts + 1, the claim token the attempt's number. Ids no longer queued
   leave the queue on the way; a descriptor one of whose inputs failed or was cancelled is cancelled (it can never
   run). A runtime whose own lease has expired claims nothing until it joins again: a process that was paused past
   its lease (and its threads still running) would otherwise claim work that is adopted from it at the next sweep.
   [m claimed-descriptors]."
  [m pool rid tid k caps can-run? now]
  (let [q (if (runtime-alive? m rid now) (vec (get-in m [:q pool])) [])
        owner (owner-of rid tid)]
    (loop [i 0 m m picked [] keep-q []]
      (if (>= i (count q))
        (if (and (empty? picked) (= (count keep-q) (count q)))
          [m []]
          [(tick (assoc-in m [:q pool] keep-q)) picked])
        (let [id (nth q i)
              dd (get-in m [:descs id])]
          (cond
            (or (nil? dd) (not= :queued (:state dd))) (recur (inc i) m picked keep-q)
            (and (seq (:after dd)) (inputs-dead? m dd))
            (recur (inc i) (assoc-in m [:descs id] (d/cancel dd now)) picked keep-q)
            (and (< (count picked) k) (claimable? m dd caps can-run?))
            (let [c (assoc (d/claim dd owner now) :orid rid :otid tid)]
              (recur (inc i) (assoc-in m [:descs id] c) (conj picked c) keep-q))
            :else (recur (inc i) m picked (conj keep-q id))))))))

(defn block
  "The owner's task waits on blocked-on: :running → :blocked (a blocked descriptor holds a stack: it moves only as a
   restart, after its owner's lease expires)."
  [m id blocked-on]
  (let [dd (get-in m [:descs id])]
    (if (and dd (= :running (:state dd))) (tick (assoc-in m [:descs id] (d/block dd blocked-on))) m)))

(defn unblock [m id]
  (let [dd (get-in m [:descs id])]
    (if (and dd (= :blocked (:state dd))) (tick (assoc-in m [:descs id] (d/unblock dd))) m)))

(defn- cancel-queued-of
  "Every queued descriptor of scope cancelled (one swap: P§5.4's scopes); m."
  [m scope now]
  (reduce (fn [m [id dd]]
            (if (and (= scope (:scope dd)) (= :queued (:state dd)))
              (assoc-in m [:descs id] (d/cancel dd now))
              m))
          m (:descs m)))

(defn complete
  "The owner's outcome for descriptor id claimed with token: :done with v (ok?), :failed with error data v — and a failed
   descriptor's scope cancelled. A token that is no longer the descriptor's (it was adopted and restarted) changes
   nothing. [m changed?]."
  [m id token ok? v now]
  (let [dd (get-in m [:descs id])]
    (if (nil? dd)
      [m false]
      (let [n (d/complete dd token ok? v now)]
        ;; (complete answers dd itself, its state unchanged, for a stale token or a settled descriptor)
        (if (= (:state n) (:state dd))
          [m false]
          [(cond-> (tick (assoc-in m [:descs id] n))
             (and (not ok?) (:scope n)) (cancel-queued-of (:scope n) now))
           true])))))

(defn cancel
  "Cancel descriptor id (eve.thread.descriptor/cancel: queued → :cancelled, never runs; running or blocked → marked
   :cancel-requested). [m changed?]."
  [m id now]
  (let [dd (get-in m [:descs id])]
    (if (or (nil? dd) (d/done? dd))
      [m false]
      [(tick (assoc-in m [:descs id] (d/cancel dd now))) true])))

(defn cancel-scope
  "Every queued descriptor of scope cancelled, in one move."
  [m scope now]
  (tick (cancel-queued-of m scope now)))

(defn adopt-expired
  "Every running or blocked descriptor whose owner is not alive (owner-alive?), adopted: re-queued at the back of its
   pool's queue as a restart (:retry :restart), or failed {:owner-died owner} (:retry :never). [m adopted-ids]."
  [m now]
  (let [orphans (vec (keep (fn [[id dd]] (when (and (contains? #{:running :blocked} (:state dd))
                                                    (not (owner-alive? m dd now)))
                                           id))
                           (:descs m)))]
    (if (empty? orphans)
      [m []]
      [(tick (reduce (fn [m id]
                       (let [dd (get-in m [:descs id])
                             n (d/adopt dd (:owner dd) now)
                             n (if (= :queued (:state n)) (dissoc n :orid :otid) n)]
                         (cond-> (assoc-in m [:descs id] n)
                           (= :queued (:state n)) (update-in [:q (:pool n)] (fn [q] (conj (vec q) id))))))
                     m orphans))
       orphans])))

(defn sweep-due?
  "Would a sweep at now change anything — an expired owner holding a descriptor or a component, a runtime whose lease
   expired, a departed runtime's settled descriptor past horizon? (Predicates only: it builds nothing, so it may run
   over a value read under a pin.)"
  [m now horizon]
  (boolean
   (or (some (fn [[_ dd]] (and (contains? #{:running :blocked} (:state dd)) (not (owner-alive? m dd now)))) (:descs m))
       (some (fn [[_ c]] (and (contains? #{:claimed :starting :running} (:state c)) (not (owner-alive? m c now))))
             (:components m))
       (some (fn [[_ r]] (not (< (- now (:hb r)) (:ttl r)))) (:runtimes m))
       (some (fn [[_ dd]] (and (d/settled? dd) (not (joined? m (:origin dd))) (> (- now (or (:done dd) 0)) horizon)))
             (:descs m)))))

(defn forget
  "Settled descriptors ids taken out (their origin has read them)."
  [m ids]
  (reduce (fn [m id]
            (let [dd (get-in m [:descs id])]
              (if (and dd (d/settled? dd)) (update m :descs dissoc id) m)))
          m ids))

(defn forget-orphans
  "Settled descriptors whose origin runtime is not joined, settled more than horizon ms ago, taken out (nobody will
   read them: a runtime whose lease lapsed and that joins again within the horizon still finds its results); their
   ids."
  [m now horizon]
  (let [ids (vec (keep (fn [[id dd]] (when (and (d/settled? dd) (not (joined? m (:origin dd)))
                                                (> (- now (or (:done dd) 0)) horizon))
                                       id))
                       (:descs m)))]
    [(update m :descs (fn [ds] (reduce dissoc ds ids))) ids]))

;; ---------------------------------------------------------------------------
;; Pool shutdown, domain-wide
;; ---------------------------------------------------------------------------

(defn shutdown-pool
  "No new submissions; queued work still runs, then the members' threads end."
  [m pool]
  (if (= :running (pool-state m pool)) (tick (assoc-in m [:pools pool :state] :shutdown)) m))

(defn shutdown-pool-now
  "No new submissions; every queued descriptor cancelled. [m cancelled-ids]."
  [m pool now]
  (if-not (contains? #{:running :shutdown :draining} (pool-state m pool))
    [m []]
    (let [ids (vec (filter (fn [id] (= :queued (get-in m [:descs id :state]))) (get-in m [:q pool])))]
      [(-> (reduce (fn [m id] (assoc-in m [:descs id] (d/cancel (get-in m [:descs id]) now))) m ids)
           (assoc-in [:q pool] [])
           (assoc-in [:pools pool :state] :stopped)
           tick)
       ids])))

(defn pool-idle?
  "No descriptor of pool queued, running or blocked."
  [m pool]
  (not-any? (fn [[_ dd]] (and (= pool (:pool dd)) (contains? #{:queued :running :blocked} (:state dd)))) (:descs m)))

;; ---------------------------------------------------------------------------
;; Bring-up: a desired set swapped in, each component claimed and walked to :running in dependency order
;; ---------------------------------------------------------------------------

(def component-transitions
  "[from to] → the move (a component's legal transitions; the descriptor discipline ET-1's thread descriptors keep)."
  {[nil :desired] :desire
   [:desired :claimed] :claim
   [:claimed :starting] :start
   [:starting :running] :up
   [:claimed :failed] :fail
   [:starting :failed] :fail
   [:running :stopping] :stop
   [:stopping :stopped] :down
   [:claimed :desired] :adopt
   [:starting :desired] :adopt
   [:running :desired] :adopt})

(defn component-legal? [from to] (contains? component-transitions [from to]))

(defn- topo-ranks
  "cid → rank in a dependency order of comps ({cid {:after #{cid ...}}}, known ranks given for components already in
   the value); throws on a cycle or an unknown dependency."
  [comps known]
  (loop [ranks known left (set (keys comps))]
    (if (empty? left)
      ranks
      (let [ready (filter (fn [c] (every? (fn [a] (contains? ranks a)) (:after (get comps c)))) left)]
        (when (empty? ready)
          (throw (ex-info "eve.thread: the desired set has a cycle or an unknown dependency"
                          {:eve.thread/refused :cycle :components (vec left)})))
        (let [r (count ranks)
              ready (sort-by str ready)]
          (recur (into ranks (map-indexed (fn [i c] [c (+ r i)])) ready)
                 (reduce disj left ready)))))))

(defn desire
  "The desired set comps ({cid {:kind ... :after #{cid ...} :for rid-or-nil :spec ...}}) swapped in, in one move: each
   a component descriptor in :desired with its rank in a dependency order (a cycle or an unknown dependency throws:
   nothing is swapped in). A component already there is left as it is."
  [m comps]
  (let [have (into {} (keep (fn [[cid c]] (when (some? (:rank c)) [cid (:rank c)]))) (:components m))
        new (into {} (remove (fn [[cid _]] (contains? have cid))) comps)
        ranks (topo-ranks new have)
        t (clock m)]
    (tick (reduce (fn [m [cid c]]
                    (assoc-in m [:components cid]
                              (assoc c :id cid :after (set (:after c)) :state :desired :rank (get ranks cid)
                                     :attempts 0 :at {:desired t})))
                  m new))))

(defn- component-ready? [m c]
  (every? (fn [a] (= :running (get-in m [:components a :state]))) (:after c)))

(defn claim-component
  "Thread tid of runtime rid claims the first desired component, in rank order, whose every :after is :running, that is
   for it (:for nil or rid) and that eligible? answers true for: :desired → :claimed. [m cid-or-nil]."
  [m rid tid eligible? now]
  (let [cands (sort-by :rank (filter (fn [c] (and (= :desired (:state c)) (component-ready? m c)
                                                  (or (nil? (:for c)) (= rid (:for c))) (eligible? c)))
                                     (vals (:components m))))]
    (if-let [c (first cands)]
      (let [cid (:id c)]
        [(tick (update-in m [:components cid]
                          (fn [c] (-> c (assoc :state :claimed :owner (owner-of rid tid) :orid rid :otid tid
                                               :attempts (inc (:attempts c)) :claimed now)
                                      (assoc-in [:at :claimed] (clock m))))))
         cid])
      [m nil])))

(defn advance-component
  "Component cid moved to state to by its owner (rid#tid), kvs merged: legal moves by the owner only; m unchanged
   otherwise."
  [m cid owner to kvs]
  (let [c (get-in m [:components cid])]
    (if (and c (= owner (:owner c)) (component-legal? (:state c) to))
      (tick (update-in m [:components cid] (fn [c] (-> (merge c kvs) (assoc :state to) (assoc-in [:at to] (clock m))))))
      m)))

(defn adopt-components
  "A claimed, starting or running component whose owner is not alive is desired again (its next claim is attempt + 1).
   [m cids]."
  [m now]
  (let [cids (vec (keep (fn [[cid c]] (when (and (contains? #{:claimed :starting :running} (:state c))
                                                 (not (owner-alive? m c now)))
                                        cid))
                        (:components m)))]
    (if (empty? cids)
      [m []]
      [(tick (reduce (fn [m cid] (update-in m [:components cid]
                                            (fn [c] (-> c (assoc :state :desired) (dissoc :owner :orid :otid)
                                                        (assoc-in [:at :desired] (clock m))))))
                     m cids))
       cids])))

(defn bring-up-order
  "The components in the order they reached :running (by the clock)."
  [m]
  (mapv :id (sort-by (fn [c] (get-in c [:at :running])) (filter (fn [c] (get-in c [:at :running])) (vals (:components m))))))

(defn brought-up? [m] (every? (fn [c] (= :running (:state c))) (vals (:components m))))

;; ---------------------------------------------------------------------------
;; Leases by name: election (P§5.6)
;; ---------------------------------------------------------------------------

(defn acquire
  "Runtime rid takes (or renews) lease lname for ttl ms: when it is absent, expired, or already rid's. [m won?]; a
   change of holder raises the lease's :term."
  [m lname rid ttl now]
  (let [cur (get-in m [:leases lname])]
    (cond
      (and cur (= rid (:rid cur)) (> (:until cur) now))
      [(assoc-in m [:leases lname :until] (+ now ttl)) true]
      (or (nil? cur) (<= (:until cur) now))
      [(tick (assoc-in m [:leases lname] {:rid rid :until (+ now ttl) :since now :term (inc (or (:term cur) 0))})) true]
      :else [m false])))

(defn release
  "Lease lname given up by rid (only by its holder)."
  [m lname rid]
  (if (= rid (get-in m [:leases lname :rid])) (tick (update m :leases dissoc lname)) m))

(defn holder
  "The runtime holding lease lname now, or nil."
  [m lname now]
  (let [cur (get-in m [:leases lname])]
    (when (and cur (> (:until cur) now)) (:rid cur))))

;; ---------------------------------------------------------------------------
;; Transports and the upgrade protocol (P§5.5), as state
;; ---------------------------------------------------------------------------

(def rungs "A transport's rung (P§5.5's ladder): what a pool's home is chosen by." {:disk 1 :window 2 :net 3})

(defn- transport-key
  "What two members must have equal to share a transport: the domain's path (disk, window) or the endpoint (net)."
  [t]
  [(:rung t) (or (:domain t) (:endpoint t))])

(defn shared-home
  "The lowest-rung transport every one of members (rids) advertises, or nil (P§5.5: a pool's home is the lowest rung
   all its members share)."
  [m members]
  (let [ts (map (fn [rid] (set (map transport-key (get-in m [:runtimes rid :transports])))) members)
        common (if (seq ts) (reduce (fn [a b] (set (filter (fn [x] (contains? b x)) a))) ts) #{})]
    (when-let [[rung k] (first (sort-by (fn [[rung _]] (get rungs rung 99)) common))]
      (if (= :net rung) {:rung rung :endpoint k} {:rung rung :domain k}))))

(defn co-hosted
  "The runtimes other than rid that advertise rid's :host (machine and boot) and a :disk or :window transport: the
   candidates rid may prove co-hosting with. [{:rid :transport}]."
  [m rid]
  ;; (compared by their fields: two maps read out of an eve value are not = on every host — compiled ClojureScript)
  (let [host-key (fn [h] (when h [(:machine h) (:boot h)]))
        h (host-key (get-in m [:runtimes rid :host]))]
    (vec (for [[r rt] (:runtimes m)
               :when (and (not= r rid) (some? h) (= h (host-key (:host rt))))
               t (:transports rt)
               :when (contains? #{:disk :window} (:rung t))]
           {:rid r :transport t}))))

(defn advertise
  "Runtime rid's transports and the nonce it is proving co-hosting with (its descriptor on the rung it joined by)."
  [m rid transports nonce]
  (if (joined? m rid)
    (tick (update-in m [:runtimes rid] (fn [r] (cond-> (assoc r :transports (vec transports))
                                                 nonce (assoc :hello-nonce nonce)))))
    m))

(defn hello
  "Step 2 of the upgrade, in the disk domain's meta-atom: runtime rid's nonce."
  [m rid nonce]
  (tick (assoc-in m [:hello rid] {:nonce nonce :at (clock m)})))

(defn ack-hello
  "Step 3: attached runtime acker, having read rid's descriptor on the other rung (its :hello-nonce there is nonce),
   acknowledges rid's hello when it carries the same nonce. [m acked?]."
  [m rid acker nonce]
  (let [h (get-in m [:hello rid])]
    (if (and h (= nonce (:nonce h)) (nil? (:acked-by h)))
      [(tick (assoc-in m [:hello rid] (assoc h :acked-by acker :acked (clock m)))) true]
      [m (and (some? h) (= nonce (:nonce h)) (some? (:acked-by h)))])))

(defn hello-acked? [m rid nonce]
  (let [h (get-in m [:hello rid])] (and h (= nonce (:nonce h)) (some? (:acked-by h)))))

(defn drain-pool
  "A pool's home migrates by drain (P§5.5 step 4): the old home marked :draining with its next home — new submissions
   go there — while what is queued here finishes."
  [m pool next-home]
  (if (= :running (pool-state m pool))
    (tick (update-in m [:pools pool] assoc :state :draining :next-home next-home))
    m))
