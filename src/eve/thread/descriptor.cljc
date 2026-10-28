(ns eve.thread.descriptor
  "The thread descriptor (EVE-THREAD plan P§5.4): a unit of work as data — never a stack. Every pool is threads
   pulling descriptors out of a cell; a descriptor's state moves only along the plan's transitions, and where the work
   may move to another thread is a property of the state it is in:

     from               to                      by                    legal reassignment
     —                  :queued                 submit                —
     :queued            :running                claim                 yes, freely: queued work is data
     :queued            :cancelled              cancel                —
     :running           :blocked                the task waits        no: a blocked task holds a stack
     :blocked           :running                what it waits settles —
     :running/:blocked  :done/:failed           complete (the owner,  —
                                                its claim token)
     :running/:blocked  :queued (attempts + 1)  adopt, :retry :restart yes, as a restart: the work runs again from
                                                (the owner died)      the start; the dead stack is gone, never moved
     :running/:blocked  :failed {:owner-died}   adopt, :retry :never  no: an honest failure

   OTP's restart types read onto descriptors: :restart is transient, :never temporary (the default, P§10 Q5). A
   running or blocked descriptor is never cancelled out from under its owner: a cancel marks it :cancel-requested and
   the future answers cancelled, as a JVM future's cancel(true) does for a task that ignores interrupts.

   Pure data and pure functions: the same descriptor is what a local cell keeps in a host atom (ET-1) and what a
   domain cell keeps in the disk atom's eve.thread/pool.<name> (ET-2).")

(def states #{:queued :running :blocked :done :failed :cancelled})

(def settled-states #{:done :failed :cancelled})

(def transitions
  "[from to] → the operation that may make the move (P§5.4)."
  {[nil :queued] :submit
   [:queued :running] :claim
   [:queued :cancelled] :cancel
   [:running :blocked] :block
   [:blocked :running] :unblock
   [:running :done] :complete
   [:blocked :done] :complete
   [:running :failed] :complete
   [:blocked :failed] :complete
   [:running :queued] :adopt
   [:blocked :queued] :adopt})

(defn legal?
  "May a descriptor move from state from to state to?"
  [from to]
  (contains? transitions [from to]))

(defn settled? [d] (contains? settled-states (:state d)))

(defn reassignment
  "Where the descriptor's work may go from the state it is in: :free (queued: any thread may claim it), :restart (running
   or blocked: only by adoption once its owner is dead, the work run again from the start), or nil (settled)."
  [d]
  (case (:state d)
    :queued :free
    (:running :blocked) :restart
    nil))

(defn descriptor
  "A new descriptor in :queued (a field with no value is absent: :owner, :claim, :value, :error, :ran-on, :claimed,
   :done, :blocked-on, :cancel-requested, :scope, :after, :needs, :priority read nil). work: what runs (a closure in a local cell whose threads share a heap; data — a task
   name or a form, materialized arguments, conveyed values — wherever it may cross one). opts: :pool (the pool's
   name), :origin (the submitting thread), :retry (:never, the default, or :restart), :scope, :after, :needs,
   :priority, :submitted (ms)."
  [id work opts]
  (cond-> {:id id :state :queued :work work :retry (or (:retry opts) :never) :attempts 0}
    (:pool opts) (assoc :pool (:pool opts))
    (:origin opts) (assoc :origin (:origin opts))
    (:scope opts) (assoc :scope (:scope opts))
    (seq (:after opts)) (assoc :after (:after opts))
    (seq (:needs opts)) (assoc :needs (:needs opts))
    (:priority opts) (assoc :priority (:priority opts))
    (:submitted opts) (assoc :submitted (:submitted opts))))

(defn step
  "d moved to state to, kvs merged in; an illegal move throws (ex-data :eve.thread/illegal [from to])."
  [d to kvs]
  (let [from (:state d)]
    (if (legal? from to)
      (merge (assoc d :state to) kvs)
      (throw (ex-info (str "eve.thread: illegal descriptor transition " from " -> " to " (" (:id d) ")")
                      {:eve.thread/illegal [from to] :id (:id d)})))))

(defn claim
  "d claimed by owner (a thread's address) with claim token — by default the attempt's number, unique per claim (an
   adopted descriptor's next claim is the next attempt, so a dead owner's late completion never matches): :queued →
   :running, attempts + 1; nil when d is not queued (another thread took it, or it was cancelled)."
  ([d owner now] (claim d owner (inc (:attempts d)) now))
  ([d owner token now]
  ;; (every future makes this move and complete's: assoc'd directly — the state tested here is the table's row)
  (when (= :queued (:state d))
    (assoc d :state :running :owner owner :claim token :attempts (inc (:attempts d)) :claimed now :ran-on owner))))

(defn block
  "The owner's task waits on blocked-on (an id): :running → :blocked. Unchanged when d is not running."
  [d blocked-on]
  (if (= :running (:state d)) (step d :blocked {:blocked-on blocked-on}) d))

(defn unblock
  "What the task waited on settled: :blocked → :running. Unchanged when d is not blocked."
  [d]
  (if (= :blocked (:state d)) (step d :running {:blocked-on nil}) d))

(defn complete
  "The owner's result: :done with value v (ok? true) or :failed with error data v. Accepted only while token is still
   the descriptor's claim — a completion from an owner whose descriptor was adopted (restarted elsewhere) is ignored:
   d is answered unchanged."
  [d token ok? v now]
  (if (and (= token (:claim d)) (or (= :running (:state d)) (= :blocked (:state d))))
    (if ok?
      (assoc d :state :done :value v :done now :blocked-on nil)
      (assoc d :state :failed :error v :done now :blocked-on nil))
    d))

(defn cancel
  "A cancel: a queued descriptor is :cancelled (it never runs); a running or blocked one is marked :cancel-requested
   (its owner's stack is never taken from it); a settled one is unchanged."
  [d now]
  (case (:state d)
    :queued (step d :cancelled {:done now})
    (:running :blocked) (assoc d :cancel-requested true)
    d))

(defn adopt
  "d adopted from dead-owner, whose thread has died: with :retry :restart back to :queued (no owner, no claim — the
   next claim makes attempt + 1), with :retry :never :failed with {:owner-died dead-owner}. Unchanged unless d is
   running or blocked with dead-owner its owner."
  [d dead-owner now]
  (if (and (contains? #{:running :blocked} (:state d)) (= dead-owner (:owner d)))
    (if (= :restart (:retry d))
      (step d :queued {:owner nil :claim nil :blocked-on nil})
      (step d :failed {:error {:class "eve.thread/owner-died"
                               :message (str "eve.thread: the thread running " (:id d) " died")
                               :data {:owner-died dead-owner}}
                       :done now :blocked-on nil}))
    d))

(defn cancelled?
  "Does the future of d answer cancelled: d cancelled while queued, or a cancel requested while it ran."
  [d]
  (or (= :cancelled (:state d)) (true? (:cancel-requested d))))

(defn done?
  "Is d's future done (Java's isDone): settled, or cancelled while it ran."
  [d]
  (or (settled? d) (true? (:cancel-requested d))))
