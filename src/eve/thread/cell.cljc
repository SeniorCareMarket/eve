(ns eve.thread.cell
  "eve.thread's engine over the thread feature (HOST-CONTRACT §11): descriptors kept in cells, the threads that pull
   them, and the helping wait (EVE-THREAD plan P§5.0-§5.4, rung 0 of P§5.5's ladder).

   Every pool is threads pulling thread descriptors out of a cell; what differs between pools is the cell's rung. Rung
   0 — the threads of one runtime — has two forms here, chosen by what the host declares, never by its name:

     local   the pool's threads can reach a host atom of the submitter's (they share a heap — or, on the BEAM, the
             atom lives in ETS, which every process reads): each descriptor is a host atom of its own, the cell's
             queue an index of candidates, and an idle thread pulls (claims with one compare-and-set on the
             descriptor) when a submission wakes it. Work is a closure and the binding frame.
     routed  the pool's threads are isolates (ships-closures? false: Node's and a browser's workers): the submitter
             is the descriptor's home and routes it to a member at once (P§5.4: push only as a routing hint, the
             member's mailbox its queue); the work is data (a task name or a form, values), the result comes back
             by message. Pulling across isolates needs a cell in shared memory — the domain cell of lane ET-2.

   Either way a descriptor moves only along eve.thread.descriptor's transitions; a running stack never moves: a
   descriptor whose owner thread died (its exit notice, C2 of ET-0) is adopted — re-queued as a restart or failed as
   :owner-died, by its :retry. A thread waiting on a future first runs the very descriptor it waits for when that is
   still queued (any thread of the heap), then — a pool member, waiting without a bound — other queued work of its
   pool; otherwise it registers as the descriptor's waiter and blocks in receive!, the descriptor marked :blocked if
   the waiter is running one. Every wake is consumed by the thread it was sent to (a registered waiter leaves only by
   its wake or by deregistering), so no eve.thread message is left in a mailbox; a message that is not eve.thread's
   is kept and handed back, in order, when the thread's outermost wait ends."
  (:require [eve.deftype :as edt]
            [eve.process :as proc]
            [eve.thread-host :as h]
            [eve.thread.descriptor :as d]
            [eve.thread.task :as task]))

(defn now [] (proc/now-ms))

(defonce counter (atom 0))

(defn next-id
  "A process-unique id: this thread's address and a counter (each isolate counts on its own; its address differs)."
  [tag]
  (str tag (h/self) "/" (swap! counter inc)))

(defn cas!
  "Change atom a by f — f answers the new value, or nil for no change — retried until it holds; [old new] when it
   changed, nil when f answered nil."
  [a f]
  (loop []
    (let [old @a
          new (f old)]
      (cond (nil? new) nil
            (compare-and-set! a old new) [old new]
            :else (recur)))))

;; ---------------------------------------------------------------------------
;; Hooks eve.thread installs (its *pool* var lives there)
;; ---------------------------------------------------------------------------

(defonce hooks (atom {}))

(defn install-pool-var!
  "eve.thread's *pool*: current answers its value here, bind runs (bind pool thunk) with it bound."
  [current bind]
  (swap! hooks assoc :current current :bind bind)
  nil)

(defn current-pool [] (when-let [f (:current @hooks)] (f)))

(defn- with-pool* [pool f] (if-let [b (:bind @hooks)] (b pool f) (f)))

;; ---------------------------------------------------------------------------
;; Pools, as the engine sees them (eve.thread.pool makes them)
;; ---------------------------------------------------------------------------

(defprotocol IPoolEngine
  (-pool-name [p])
  (-cell [p] "the pool's cell")
  (-help [p] ":any — any thread waiting on a queued descriptor may run it; :members — only the pool's threads (FIFO)")
  (-threadless? [p] "no thread of its own (a host without threads): a waiter runs the work itself, bounded wait or not")
  (-on-exit! [p notice] "a thread of the pool ended: its exit notice, at the thread that started it")
  (-on-member-update! [p m] "routed: a membership message"))

;; name → pool; address → the pool its thread serves. Process-wide where threads share a heap; per isolate otherwise.
(defonce pools (atom {}))
(defonce thread-pools (atom {}))

(defn register-pool! [p] (swap! pools assoc (-pool-name p) p) p)
(defn unregister-pool! [p] (swap! pools dissoc (-pool-name p)) nil)
(defn find-pool [nm] (get @pools nm))
(defn register-threads! [addrs nm] (swap! thread-pools into (map (fn [a] [a nm])) addrs) nil)
(defn unregister-thread! [addr] (swap! thread-pools dissoc addr) nil)

;; ---------------------------------------------------------------------------
;; A thread's own state in eve.thread (bound by its outermost eve.thread loop)
;; ---------------------------------------------------------------------------

(def ^:dynamic *loop* nil)

(defn- loop-state
  "member: the pool this thread serves (a pump), or nil."
  [member]
  {:st (atom {:woken #{} :foreign [] :running [] :stop false}) :member member})

(defn member-pool [] (:member *loop*))

(defn- st [] (:st *loop*))

(defn- foreign! [m] (swap! (st) update :foreign conj m) nil)

(defn stop-requested? [] (:stop @(st)))

(declare handle! await-local! await-any-local!)

(defn- finish-loop!
  "The outermost loop ends. If it kept messages that were not eve.thread's, what is still in the mailbox is handled
   (eve.thread's own) or kept too, then every kept message is handed back to this thread's own queue in the order it
   came. If it kept none there is nothing to reorder: the mailbox is left as it is (eve.thread's own messages in it are
   handled by the thread's next eve.thread loop) — a receive! saved per wait."
  []
  (when (seq (:foreign @(st)))
    (loop []
      (let [m (h/receive! 0)]
        (when-not (= m :eve.thread/timeout) (handle! m) (recur))))
    (let [ms (:foreign @(st))]
      (swap! (st) assoc :foreign [])
      (doseq [m ms] (h/send! (h/self) m)))))

(defn call-in-loop
  "f inside this thread's eve.thread loop state — the outermost call makes one (member: the pool this thread pumps
   for) and hands back, when f ends, the messages that were not eve.thread's."
  [member f]
  (if *loop*
    (f)
    (binding [*loop* (loop-state member)]
      (let [[ok? v] (h/attempt f)]
        (finish-loop!)
        (if ok? v (throw v))))))

;; ---------------------------------------------------------------------------
;; Descriptor entries: a host atom each
;; ---------------------------------------------------------------------------

(defn entry
  "A descriptor's entry: a host atom holding it. Made empty and then set — a clojerl atom's term carries its initial
   value (clojerl.Atom.erl), so an atom made with the descriptor would copy it, work and frame, wherever the entry
   itself is copied (the queue's ETS, a message)."
  [dd]
  (let [a (atom nil)] (reset! a dd) a))

(defn wake!
  "The registered waiters of a descriptor that settled (or was cancelled): one wake each — this thread's own recorded
   in its loop state at once, no message to itself."
  [id waiters]
  (let [me (when *loop* (h/self))]
    (doseq [w waiters]
      (if (and me (= w me))
        (swap! (:st *loop*) update :woken conj id)
        (h/send! w {:eve.thread/op :wake :id id})))))

(defn- running-push! [e token] (when *loop* (swap! (st) update :running conj [e token])))
(defn- running-pop! [] (when *loop* (swap! (st) update :running (fn [v] (if (seq v) (pop v) v)))))
(defn- running-top [] (when *loop* (peek (:running @(st)))))

(defn running-entries
  "The [entry token] pairs this thread is running, outermost first (what adoption takes when it dies)."
  [ls]
  (:running @(:st ls)))

(defn settle!
  "The owner's outcome of the entry it claimed with token: :done with v, or :failed with v as error data; its waiters
   woken; true. A stale token (the descriptor was adopted, or already settled) changes nothing: nil."
  [e token ok? v]
  (when-let [[old _] (cas! e (fn [dd]
                               (let [n (d/complete dd token ok? v (now))]
                                 (when-not (identical? n dd) (assoc n :waiters #{})))))]
    (wake! (:id old) (:waiters old))
    true))

;; The descriptors this isolate routed to a member and is the home of, by id (see route!). Defined here, before its
;; first use: on clojerl a declared var counts as bound, so a later defonce would leave it unset.
(defonce routed (atom {}))

;; ---------------------------------------------------------------------------
;; Remote entries: a descriptor kept outside this heap (a domain cell's, in a meta-atom: eve.thread.domain)
;; ---------------------------------------------------------------------------

(defprotocol IRemoteEntry
  "An entry whose descriptor lives in a cell outside this heap: deref reads it there, and it waits and cancels by its
   cell's own means (the wait, cancel and await-any below hand such an entry to it)."
  (-await-remote [e ms] "the descriptor once done (descriptor/done?), or ::timeout after ms (< 0: for ever)")
  (-cancel-remote! [e] "cancel it there: true when that changed it"))

(defn remote? [e] (satisfies? IRemoteEntry e))

(edt/deftype RemoteEntry [read-fn await-fn cancel-fn]
  clojure.lang.IDeref
  (deref [_] (read-fn))
  IRemoteEntry
  (-await-remote [_ ms] (await-fn ms))
  (-cancel-remote! [_] (cancel-fn)))

(defn remote-entry
  "A remote entry over its cell's three means: (read) the descriptor, (wait ms) as -await-remote, (cancel)."
  [read wait cancel]
  (->RemoteEntry read wait cancel))

(defn- cancel-local!
  [e]
  (if-let [[old _] (cas! e (fn [dd]
                             (when-not (d/done? dd)
                               (assoc (d/cancel dd (now)) :waiters #{}))))]
    (do (wake! (:id old) (:waiters old))
        (when (contains? @routed (:id old))
          (swap! routed dissoc (:id old))
          (when-let [o (:owner old)] (h/send! o {:eve.thread/op :unschedule :id (:id old)})))
        true)
    false))

(defn cancel!
  "Cancel the entry's descriptor (descriptor/cancel); true when that changed it (it was not done or already
   cancelled), its waiters woken. A descriptor routed from here is forgotten here, and its owner told (a timer drops
   it; any other member ignores the hint). A remote entry's cell cancels it."
  [e]
  (if (remote? e)
    (-cancel-remote! e)
    (cancel-local! e)))

(defn claim!
  "This thread claims the entry (queued → running); the claim's token, or nil when it was not queued."
  [e]
  (when-let [[_ dd] (cas! e (fn [dd] (d/claim dd (h/self) (now))))]
    (:claim dd)))

(defn run-claimed!
  "Run the descriptor this thread claimed with token, inside its pool (*pool* bound to it), and settle it."
  [e token]
  (let [dd @e
        pool (find-pool (:pool dd))
        ls *loop*
        [ok? v] (h/attempt (fn [] (task/run-work (:work dd)
                                                 (fn [f] (binding [*loop* ls] (with-pool* pool f))))))]
    (settle! e token ok? (if ok? v (h/error-data v)))))

(defn- claim-and-run!
  "Claim e and run it here; true when this thread ran it."
  [e]
  (when-let [token (claim! e)]
    (running-push! e token)
    (let [[ok? v] (h/attempt (fn [] (run-claimed! e token)))]
      (running-pop!)
      (if ok? true (throw v)))))

;; ---------------------------------------------------------------------------
;; Cells
;; ---------------------------------------------------------------------------

(defprotocol ICell
  (-submit! [c e] "a new entry (queued) into the cell; true when an idle thread was woken for it")
  (-claim-next! [c] "claim and run the next queued descriptor here; true when one ran")
  (-claimable? [c] "may a claim find work (a hint)")
  (-idle! [c addr] "addr waits for work")
  (-unidle! [c addr] "addr no longer waits; true when it was still registered (no wake is on its way)")
  (-wake-all! [c] "every idle thread woken (shutdown)")
  (-drain! [c] "every queued entry taken out (shutdown-now!): the entries"))

(defn- push-q [q e] (-> q (assoc-in [:q (:t q)] e) (update :t inc)))

(defn- pop-q!
  "The first entry of queue atom qa, taken out; nil when empty."
  [qa]
  (loop []
    (let [{:keys [h t q] :as old} @qa]
      (when (< h t)
        (if (compare-and-set! qa old (assoc old :h (inc h) :q (dissoc q h)))
          (get q h)
          (recur))))))

(defrecord LocalCell [queue idle])

;; (protocols by extend-type, fields read by key: basilisp's defrecord dispatches an inline method whose name starts
;; with - to an attribute without the dash — measured: 'Pool' object has no attribute 'pool_name')
(extend-type LocalCell
  ICell
  (-submit! [c e]
    (swap! (:queue c) push-q e)
    (some? (when-let [[old _] (cas! (:idle c) (fn [s] (when (seq s) (pop s))))]
             (h/send! (peek old) {:eve.thread/op :work})
             true)))
  (-claim-next! [c]
    (loop []
      (when-let [e (pop-q! (:queue c))]
        (if (claim-and-run! e) true (recur)))))
  (-claimable? [c] (let [{:keys [h t]} @(:queue c)] (< h t)))
  (-idle! [c addr] (swap! (:idle c) (fn [s] (if (some #(= addr %) s) s (conj s addr)))) nil)
  (-unidle! [c addr]
    (some? (cas! (:idle c) (fn [s] (when (some #(= addr %) s) (vec (remove #(= addr %) s)))))))
  (-wake-all! [c]
    (when-let [[old _] (cas! (:idle c) (fn [s] (when (seq s) [])))]
      (doseq [a old] (h/send! a {:eve.thread/op :work}))))
  (-drain! [c]
    (loop [out []]
      (if-let [e (pop-q! (:queue c))] (recur (conj out e)) out))))

(defn local-cell [] (->LocalCell (atom {:h 0 :t 0 :q {}}) (atom [])))

(defn claim-next-held!
  "Claim the next queued entry of local cell c for this thread and hold it (on this thread's running stack) without
   running it: the entry, or nil. What a thread that is about to die holds (the adoption tests)."
  [c]
  (loop []
    (when-let [e (pop-q! (:queue c))]
      (if-let [token (claim! e)]
        (do (running-push! e token) e)
        (recur)))))

(defn enqueue!
  "Put entry e (queued) into the cell of its pool and wake an idle thread."
  [e]
  (when-let [p (find-pool (:pool @e))] (-submit! (-cell p) e)))

;; ---------------------------------------------------------------------------
;; Routed descriptors (isolates): this isolate is their home
;; ---------------------------------------------------------------------------

(defn route!
  "Send entry e's descriptor (queued, its work data) to member to, which runs it: claimed on to's behalf (:running,
   owner to) and kept here until its result comes back. pool-desc: the pool as data, so to binds it (empty: to has
   it). A routed descriptor lives in its home isolate, whose one thread is the only one that touches it — read and
   reset here, no compare-and-set — and it is kept only once the send has gone (a refused send leaves nothing)."
  [e to pool-desc]
  (when-let [dd (d/claim @e to (now))]
    (reset! e dd)
    (let [m {:eve.thread/op :task :id (:id dd) :token (:claim dd) :home (h/self) :work (:work dd)}]
      (h/send! to (if (contains? pool-desc :members) (assoc m :pool pool-desc) m)))
    (swap! routed assoc (:id dd) e))
  nil)

(defn routed-owned-by
  "The routed entries this isolate is home of whose owner is addr."
  [addr]
  (filter (fn [e] (= addr (:owner @e))) (vals @routed)))

(defn- accept-result!
  "A member's result for a descriptor routed from here: settled with the token it was routed with (or cancelled, when
   the member ended it so: a periodic run at its pool's shutdown)."
  [{:keys [id token ok value error cancelled]}]
  (when-let [e (get @routed id)]
    (swap! routed dissoc id)
    (if cancelled
      (cancel! e)
      ;; home-local (see route!): read and reset
      (let [dd @e
            n (d/complete dd token ok (if ok value error) (now))]
        (when-not (identical? n dd)
          (reset! e (assoc n :waiters #{}))
          (wake! id (:waiters dd)))))))

(defn- result-message [id token ok? v]
  (let [m {:eve.thread/op :result :id id :token token :ok ok?}]
    (if ok? (assoc m :value v) (assoc m :error (h/error-data v)))))

(defn- reply! [home m]
  (let [[ok? _] (h/attempt (fn [] (h/send! home m)))]
    (when-not ok?
      ;; the value or the error's data is not data: the failure travels instead
      (h/send! home (assoc (dissoc m :value) :ok false
                           :error {:class "eve.thread/host-object"
                                   :message (str "eve.thread: the task's " (if (:ok m) "value" "error data")
                                                 " cannot cross a heap: a host object")
                                   :data nil})))))

(defn- call-thunk [f] (f))

(defn- run-routed-task!
  "A descriptor routed to this thread: run it inside its pool — the one the message names the first time a home routes
   here, else this thread's own (a member runs only its pool's work) — its result sent home. (No binding frame
   crosses to an isolate, so this loop's state stays bound as it is.)"
  [{:keys [id token home work pool]}]
  (let [own (member-pool)
        view (if pool
               (or (find-pool (:name pool)) (when-let [mk (:pool-view @hooks)] (mk pool)))
               own)
        ;; a member's own pool is bound for its whole loop (pump-routed!): bound again only for another
        [ok? v] (h/attempt (fn [] (task/run-work work (if (= view own) call-thunk (fn [f] (with-pool* view f))))))]
    (reply! home (result-message id token ok? v))))

;; ---------------------------------------------------------------------------
;; Messages
;; ---------------------------------------------------------------------------

(defn- exit-notice! [m]
  (if-let [p (find-pool (get @thread-pools (:eve.thread/exit m)))]
    (-on-exit! p m)
    (foreign! m)))

(defn handle!
  "One message received inside an eve.thread loop: eve.thread's own acted on, any other kept for the thread."
  [m]
  (cond
    (and (map? m) (contains? m :eve.thread/op))
    (case (:eve.thread/op m)
      :wake (swap! (st) update :woken conj (:id m))
      (:work :unschedule) nil
      :task (run-routed-task! m)
      :result (accept-result! m)
      (:pool :dead) (when-let [p (find-pool (:name m))] (-on-member-update! p m))
      (foreign! m))
    (and (map? m) (contains? m :eve.thread/exit)) (exit-notice! m)
    (and (= m :eve.thread/stop) (member-pool)) (swap! (st) assoc :stop true)
    :else (foreign! m)))

;; ---------------------------------------------------------------------------
;; Waiting: the helping wait
;; ---------------------------------------------------------------------------

(defn- register-waiter! [e addr]
  (some? (cas! e (fn [dd] (when-not (d/done? dd) (update dd :waiters (fnil conj #{}) addr))))))

(defn- deregister-waiter! [e addr]
  (some? (cas! e (fn [dd] (when (contains? (:waiters dd) addr) (update dd :waiters disj addr))))))

(defn- take-woken! [id]
  (some? (cas! (st) (fn [s] (when (contains? (:woken s) id) (update s :woken disj id))))))

(defn wait-wake!
  "Messages handled until the wake for id comes (:woken) or deadline (ms, nil: none) passes (:timeout)."
  [id deadline]
  (loop []
    (if (take-woken! id)
      :woken
      (let [left (when deadline (- deadline (now)))]
        (if (and left (<= left 0))
          :timeout
          (let [m (h/receive! (if left left -1))]
            (if (= m :eve.thread/timeout)
              (recur)
              (do (handle! m) (recur)))))))))

(defn- block-running! [id]
  (when-let [[e _] (running-top)] (cas! e (fn [dd] (when (= :running (:state dd)) (d/block dd id))))))

(defn- unblock-running! []
  (when-let [[e _] (running-top)] (cas! e (fn [dd] (when (= :blocked (:state dd)) (d/unblock dd))))))

(defn- may-help?
  "May this thread run the queued descriptor dd it waits for? Not before it is due (a scheduled one-shot); not in a
   bounded wait (the work could outlast it) unless the pool has no thread to run it; not a FIFO pool's work unless this
   thread is that pool's."
  [dd timed?]
  (let [p (find-pool (:pool dd))
        threadless? (and (some? p) (-threadless? p))]
    (and (or (nil? (:not-before dd)) (>= (now) (:not-before dd)))
         (or (not timed?) threadless?)
         (or threadless? (nil? p) (= :any (-help p)) (= p (member-pool))))))

(defn- not-yet-due
  "ms until a queued scheduled descriptor of a pool with no thread is due (its waiter runs it then), else nil."
  [dd]
  (when-let [nb (:not-before dd)]
    (let [p (find-pool (:pool dd))
          left (- nb (now))]
      (when (and (pos? left) (some? p) (-threadless? p)) left))))

(defn await!
  "Wait for entry e's descriptor to be done (descriptor/done?): ms < 0 for ever, else at most ms; the descriptor, or
   ::timeout. Helps on the way (see the namespace doc): on a pool with no thread (a host without threads) the waiter runs
   the work itself even when the wait is bounded — a current-thread executor. A remote entry waits by its cell's means."
  [e ms]
  (if (remote? e)
    (-await-remote e ms)
    (await-local! e ms)))

(defn- await-local! [e ms]
  (call-in-loop
   nil
   (fn []
     (let [timed? (not (neg? ms))
           deadline (when timed? (+ (now) ms))
           me (h/self)]
       (loop []
         (let [dd @e]
           (cond
             (d/done? dd) dd
             (and (= :queued (:state dd)) (may-help? dd timed?) (claim-and-run! e)) (recur)
             (and (= :queued (:state dd)) (not-yet-due dd))
             (let [wait (not-yet-due dd)
                   left (when deadline (- deadline (now)))]
               (if (and left (<= left 0))
                 ::timeout
                 (let [m (h/receive! (if left (min left wait) wait))]
                   (when-not (= m :eve.thread/timeout) (handle! m))
                   (recur))))
             (and (not timed?) (member-pool) (-claim-next! (-cell (member-pool)))) (recur)
             ;; routed from here: this isolate's one thread is its only possible waiter, and its result can only
             ;; arrive as a message this loop handles — re-read after each, no registration, no wake
             (contains? @routed (:id dd))
             (let [left (when deadline (- deadline (now)))]
               (if (and left (<= left 0))
                 ::timeout
                 (let [m (h/receive! (if left left -1))]
                   (when-not (= m :eve.thread/timeout) (handle! m))
                   (recur))))
             (register-waiter! e me)
             (do (block-running! (:id dd))
                 (let [r (wait-wake! (:id dd) deadline)]
                   (cond
                     (= r :woken) (do (unblock-running!) (recur))
                     (deregister-waiter! e me) (do (unblock-running!) ::timeout)
                     :else (do (wait-wake! (:id dd) nil) (unblock-running!) (recur)))))
             :else (recur))))))))

(defn- await-any-remote!
  "await-any! over entries some of which are remote: each read in turn, the reads spaced 1 ms growing to 16 ms, this
   thread's messages handled between them."
  [es]
  (call-in-loop
   nil
   (fn []
     (loop [b 1]
       (if-let [e (first (filter (fn [e] (d/done? @e)) es))]
         e
         (let [m (h/receive! b)]
           (when-not (= m :eve.thread/timeout) (handle! m))
           (recur (min 16 (* 2 b)))))))))

(defn await-any!
  "Wait until one of entries es is done; that entry. Untimed: it runs a queued one it may help with first; each
   registration is consumed by its wake or undone. Entries of which any is remote are read in turn instead."
  [es]
  (if (some remote? es)
    (await-any-remote! es)
    (await-any-local! es)))

(defn- await-any-local!
  [es]
  (call-in-loop
   nil
   (fn []
     (let [me (h/self)]
       (loop []
         (if-let [e (first (filter (fn [e] (d/done? @e)) es))]
           e
           (if-let [q (first (filter (fn [e] (let [dd @e] (and (= :queued (:state dd)) (may-help? dd false)))) es))]
             (do (claim-and-run! q) (recur))
           (let [reg (vec (filter (fn [e] (register-waiter! e me)) es))]
             (if (< (count reg) (count es))
               ;; one finished while registering: undo the rest, consuming the wakes already on their way
               (do (doseq [e reg] (when-not (deregister-waiter! e me) (wait-wake! (:id @e) nil))) (recur))
               (let [ids (mapv (fn [e] (:id @e)) reg)
                     woken-one (fn [] (loop [xs ids] (when (seq xs) (if (take-woken! (first xs)) (first xs) (recur (rest xs))))))
                     hit (loop []
                           (or (woken-one)
                               (let [m (h/receive! -1)] (handle! m) (recur))))]
                 (doseq [e reg]
                   (when-not (= hit (:id @e))
                     (when-not (deregister-waiter! e me) (wait-wake! (:id @e) nil))))
                 (recur)))))))))))

;; ---------------------------------------------------------------------------
;; Adoption: the descriptors a dead thread owned
;; ---------------------------------------------------------------------------

(defn adopt!
  "Adopt entry e from dead-addr, its owner: re-queued (and put back into its pool's cell, or re-routed by route-again
   for a routed one) with :retry :restart, failed with :owner-died with :retry :never; its waiters woken when it
   failed."
  [e dead-addr route-again]
  (when-let [[old new] (cas! e (fn [dd]
                                 (let [n (d/adopt dd dead-addr (now))]
                                   (when-not (identical? n dd)
                                     (if (d/settled? n) (assoc n :waiters #{}) n)))))]
    (if (= :queued (:state new))
      (if route-again (route-again e) (enqueue! e))
      (wake! (:id old) (:waiters old)))))

;; ---------------------------------------------------------------------------
;; Pumps: the loops pool threads run
;; ---------------------------------------------------------------------------

(defn pump-local!
  "A local pool's thread: claim and run until the pool is done with it. live? answers whether to go on (false once the
   pool is shut down and nothing is queued, or stopped); keep-alive-ms (nil: for ever) and retire? (called on its
   expiry: may this thread end, true taking it off the pool's count) make a cached pool shrink. on-running answers
   this thread's loop state to the pool (so the pool can adopt what it runs if it dies)."
  [pool me {:keys [live? keep-alive-ms retire? on-running]}]
  (call-in-loop
   pool
   (fn []
     (when on-running (on-running *loop*))
     (let [c (-cell pool)]
       (loop []
         (cond
           (stop-requested?) :stopped
           (-claim-next! c) (recur)
           (not (live?)) :done
           :else
           (do (-idle! c me)
               (if (or (-claimable? c) (not (live?)))
                 (do (-unidle! c me) (recur))
                 (let [m (h/receive! (if keep-alive-ms keep-alive-ms -1))]
                   (cond
                     (= m :eve.thread/timeout)
                     (if (and (-unidle! c me) (retire?)) :retired (recur))
                     (= m :eve.thread/stop) (do (-unidle! c me) :stopped)
                     :else (do (handle! m) (recur))))))))))))

(defn pump-routed!
  "A routed pool's thread (an isolate): run what is routed to it until asked to stop, then what is already waiting —
   its pool bound as *pool* throughout."
  [pool]
  (call-in-loop
   pool
   (fn []
     (with-pool*
      pool
      (fn []
        (loop []
          (if (stop-requested?)
            (loop [] (let [m (h/receive! 0)] (when-not (= m :eve.thread/timeout) (handle! m) (recur))))
            (let [m (h/receive! -1)]
              (if (= m :eve.thread/stop)
                (swap! (st) assoc :stop true)
                (handle! m))
              (recur))))
        :stopped)))))
