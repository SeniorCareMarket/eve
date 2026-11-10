(ns eve.stm
  "eve.stm — software transactional memory in Eve, on Eve atoms,
  with no host STM beneath: stock Clojure STM semantics
  (clojure.lang.LockingTransaction / RT / Ref) over a per-ref
  construction in which every dynamic is replaced by an Eve
  primitive. The user page is doc/stm.md.

  THE CONSTRUCTION

  Each ref's cell is the SOLE atom of its own Eve atom domain
  (ea/make-atom-domain), so the cell's contention domain is the
  ref itself; a ref IS a one-atom domain.

  The cell: {:txid :val :touched :failures}. txid is in
  commit-point units — one committed tx advances its refs' txids
  by exactly 1.

  A transaction attempt carries a read point p (a value of the
  commit-point clock) and reads each ref as of p from its
  versioned history — point -> value entries capped at
  :max-history (default 10) — folded with the attempt's own
  writes. A read is never a value staged at a later point: the
  multi-ref snapshot defect this prevents is the JAGGED
  transaction, a combination of ref values that never existed at
  one commit point. (A single atom's snapshot cannot tear; the
  defect lives in the layer above the atoms.)

  Commit: lock each written/ensured ref in creation order (a CAS
  claim on the ref's own lock atom, carrying the attempt),
  validate (ensured and altered refs' txids must equal the values
  the attempt read — commute writes are not validated and fold at
  install), claim the next commit point from the commit-point
  clock (an Eve atom of one long: CAS-increment), install each
  written ref with ONE CAS on its own cell atom (the value, its
  history entry, and its pending send queue ride the same CAS),
  run validators, release agents. The point is claimed before
  the first install and held until the last, so no reader sees
  part of a commit.

  THERE IS NO SINGLE ATOMIC SWAP ANYWHERE IN THE CONSTRUCTION:
  the atomic unit is one CAS per ref, which is also the unit
  contention can collide on.

  Retry: ordinary retries are unbounded and never counted. A
  retry whose cause is a basis fault (a cell whose txid/failures
  lie beyond the commit-point clock — only a write that bypassed
  this layer can produce one) is counted per run-in-transaction
  invocation and bounded: at the bound (10,000, stock
  LockingTransaction RETRY_LIMIT parity) the runner throws
  IllegalStateException (\"Transaction failed after reaching retry
  limit\") instead of spinning.

  WATCHES AND VALIDATORS live in the ref record itself: a watch
  map and a validator fn in the ref's own state atom, run by
  commit, exactly as Atom's watches/validators run on every
  reset. Eve atoms have neither — the ref layer carries its own.

  The SINGLE-WRITER CONTRACT: a ref's cell is written ONLY
  through this namespace — a winning commit's install CAS, the
  sends this namespace releases at commit, and make-ref at
  creation.
  A body writing a cell atom directly is outside the contract:
  state that needs uncoordinated writers is a plain Eve atom, not
  a ref. An out-of-band write can move a cell where no legal
  commit sequence can put it; the basis-fault bound above is the
  runner's answer.

  INTERRUPTION is cooperative, as it must be at the Eve level:
  the runner checks the running thread's interrupt state once per
  attempt and abandons with InterruptedException. On the JVM seat
  that is the thread's own flag.

  AGENTS: Eve has no agent machinery in its standalone tree, so
  sends are a SEAM here, not an assumption: dispatch! holds a
  message in the attempt record inside a transaction and releases
  it through the installed send dispatcher exactly once, after the
  winning commit; outside a transaction it dispatches immediately.
  An Eve agent layer, when built, installs its executor as the
  dispatcher.

  CONTENTION DOMAINS ARE TIERED: a ref's Eve domain is its
  contention domain at the memory tier; a disk-tier atom's
  contention domain is a process; a network-tier atom's is a
  whole server — in a network organization each ref is its own
  server with its own atom broker, and cross-broker commit is
  protocol design, not a substrate primitive.

  MEMORY LAW: all transactional state lives in atoms — cells in
  Eve atoms (one per ref domain), the clock in one atom, attempt
  records and ref state in per-scope host atoms. No journals, no
  side tables outliving their scope, no counters on headers.

  DISK-TIER SEATING: the construction above is the memory tier —
  its clock is one host atom and its locks live in refs' host
  state atoms, so one process's transactions are the whole world.
  seat-clock! + make-ref's :seat opt seat the SAME mechanism at
  the disk tier: the clock in a shared persistent Eve atom, each
  ref's cell in its own persistent domain, and its commit lock in
  the cell's Eve meta-atom (eve.atom/meta-atom) — the seating
  that scopes for two processes to transact against the same
  refs. Nothing else changes: lock order, validation, claim,
  install and retry are the same code paths, dispatched on where
  the state lives.

  HISTORY ENCODING (0.9.3.1): this release stores the versioned
  history in the OLD encoding. An accepted flat encoding exists
  in the development record but is NOT in this release, pending
  the operator's ruling; the two encodings do not read each
  other, and worlds seated under one must be re-created under the
  other — there is no in-place migration.

  The construction is written against deref / CAS / own-domain
  atoms only, so the port surface per target is the seat's atom +
  domain affordances."
  (:refer-clojure :exclude [ref alter commute ref-set ensure sync dosync
                            ref-history-count ref-min-history ref-max-history])
  (:require [eve.atom :as ea]
            ;; eve.process exists only in the adapter Eve tree; the
            ;; seats that need its clock (below) are exactly the ones
            ;; that tree serves
            #?@(:lpy [[eve.process :as proc]]
                :cljr [[eve.process :as proc]]
                :clje [[eve.process :as proc]]
                :jank [[eve.process :as proc]])))

;; ---------------------------------------------------------------------------
;; the retry signal: ONE shared exception instance, thrown by a failed
;; validation, caught only by run-in-transaction (by its ex-data mark).
;; User code catching ExceptionInfo/Throwable around a dosync body can
;; swallow it -- the JVM's RetryEx has exactly the same exposure.

(defonce ^:private retry-signals (atom {}))

(defn- retry-ex
  "The retry signal for a cause: one cached instance per cause (the
   shared-instance economy of the single signal, kept), its ex-data
   carrying :eve.stm/retry-cause so the runner can count retries BY
   CAUSE (Agent 3's measurement, Q-STM-WAIT): :body-wait,
   :validator-bound, :validation, :history-trim, :install-cas,
   :unspecified. (No :writer-lock: lock! waits for the writer lock
   without a bound and never retries.)"
  ([] (retry-ex :unspecified))
  ([cause]
   (or (get @retry-signals cause)
       (let [e (ex-info "eve.stm: transaction retry"
                        {:eve.stm/retry true :eve.stm/retry-cause cause})]
         (swap! retry-signals assoc cause e)
         e))))

(defn- signal-of
  "The STM signal an exception carries, or nil. On most seats a
   signal reaches the runner as itself. On the CLR seat, .NET wraps
   anything thrown inside a comparison callback (Array.Sort) into
   System.InvalidOperationException (\"Failed to compare two
   elements in the array\"), with the original as InnerException —
   a retry signal thrown through such a sort arrived wrapped and
   the runner missed it (F-ANTS-5, ants lane smoke-cljr4 trace).
   One level: .NET wraps once. Other seats: identity."
  [e]
  #?(:cljr (if (or (:eve.stm/retry (ex-data e))
                   (:eve.stm/basis-fault (ex-data e))
                   (:eve.stm/interrupted (ex-data e)))
             e
             (let [inner (some-> e .InnerException)]
               (when (and inner
                          (or (:eve.stm/retry (ex-data inner))
                              (:eve.stm/basis-fault (ex-data inner))
                              (:eve.stm/interrupted (ex-data inner))))
                 inner)))
     :default e))

(defn retry-ex? [e]
  (boolean (and (map? (ex-data (signal-of e))) (:eve.stm/retry (ex-data (signal-of e))))))

;; ---------------------------------------------------------------------------
;; the basis-fault signal: the retry signal's sibling, thrown instead
;; of it when a failure carries the out-of-band signature (a cell
;; standing beyond the commit-point clock -- see beyond-clock?). The
;; runner counts these per transaction invocation and, at
;; basis-fault-limit, faults the transaction (IllegalStateException)
;; rather than retrying.

(defonce ^:private basis-fault-signal (atom nil))

(defn- basis-fault-ex []
  (or @basis-fault-signal
      (let [e (ex-info "eve.stm: transaction basis fault (cell moved outside the STM)"
                       {:eve.stm/basis-fault true})]
        (reset! basis-fault-signal e)
        e)))

(defn basis-fault-ex? [e]
  (boolean (and (map? (ex-data (signal-of e))) (:eve.stm/basis-fault (ex-data (signal-of e))))))

;; ---------------------------------------------------------------------------
;; host seam: the few things this layer needs from the seat — a wall
;; clock, a sleep, an interrupt flag, and the seat's exception shapes.
;; The clock uses eve.atom's own idiom (#?(:cljs (js/Date.now)
;; :default (System/currentTimeMillis))) on the seats that have a
;; System, and Eve's portable adapter clock (eve.process/now-ms) on
;; the seats that do not (Basilisp, ClojureCLR, clojerl — the reason
;; eve.process exists). The exception
;; constructors are reader arms in this one file (not per-host
;; forks): stock's classes where the seat has them — the JVM and the
;; JVM-surface dialects through their :clj/:jank arms — and an
;; ex-info carrying the same fact where it does not (the CLJS
;; family), so the retry/fault logic never has to name a class its
;; seat cannot see. The 12-host matrix pass added these arms; the
;; JVM forms are byte-identical to what ran before it.

(defn- now-ms []
  #?(:cljs (js/Date.now)
     ;; the seats with no java.lang.System take Eve's portable clock
     ;; (eve.process/now-ms — the adapter clock eve.atom uses there)
     :lpy (proc/now-ms)
     :cljr (proc/now-ms)
     :clje (proc/now-ms)
     :default (System/currentTimeMillis)))

(defn- sleep-ms [ms]
  #?(:jank (proc/sleep-ms! ms)
     ;; jank has no Thread shim and claims :clj in reader
     ;; conditionals, so its arm must precede :clj (Eve's own process
     ;; completion sleeps on this seat — adapters/process/jank, the
     ;; same proc/sleep-ms! eve.atom calls there)
     :clj (Thread/sleep ms)
     :lpy (proc/sleep-ms! ms)
     ;; seats without a sleep spin against the wall-clock deadline
     ;; instead (await-unlocked! bounds the wait by now-ms either way)
     :default nil))

(defn- illegal-state [msg]
  #?(:lpy (IllegalStateException. msg)
     :jank (ex-info msg {:eve.stm/illegal-state true})
     :clj (IllegalStateException. msg)
     :cljr (System.InvalidOperationException. msg)
     :cljs (js/Error. msg)
     :default (ex-info msg {:eve.stm/illegal-state true})))

;; ---------------------------------------------------------------------------
;; attempt statistics (probes read these; the runner maintains them)

(defonce ^:private stats* (atom {:attempts 0 :retries 0 :commits 0 :retry-causes {}}))

(defn stats [] @stats*)

(defn reset-stats! [] (reset! stats* {:attempts 0 :retries 0 :commits 0 :retry-causes {}}) nil)

;; ---------------------------------------------------------------------------
;; the commit-point clock: one atom holding the last commit point.
;; It carries no ref's data; it is the ordering stock keeps as
;; LockingTransaction's global lastPoint, and the only state refs of
;; one program share.

(defonce ^:private commit-point (atom 0))

;; ---------------------------------------------------------------------------
;; disk-tier seating of the clock: when a program seats the clock in
;; an Eve atom -- a persistent atom every process taking part opens --
;; claims and reads go through that atom and the points are one
;; global order across processes. Unseated, the host atom above is
;; the clock, exactly as before.

(defonce ^:private clock-cell (atom nil))

(defn seat-clock!
  "Seat the commit-point clock in the Eve atom `a` (which must hold
   a long: the last claimed commit point). Returns `a`."
  [a]
  (reset! clock-cell a)
  a)

(defn- clock-now []
  (if-let [c @clock-cell] @c @commit-point))

(defn- claim-point! []
  (if-let [c @clock-cell] (swap! c inc) (swap! commit-point inc)))

(def basis-fault-limit
  "How many impossible-progress (basis-fault) failures one
   run-in-transaction invocation tolerates before faulting the
   transaction with an IllegalStateException. The number is stock's:
   LockingTransaction's RETRY_LIMIT is 10000. The SCOPE is
   deliberately narrower than a global attempt cap: only basis
   faults count toward it. Contention retries are not counted, are
   not reset by basis faults, and stay unbounded."
  10000)

(defn- beyond-clock?
  "True when a cell's point stands beyond the commit-point clock.
   This namespace's commit path cannot produce that state: commit!
   claims the next point from the clock and install! writes only
   claimed points, and the clock never decreases. A cell beyond the
   clock was moved by a writer outside the STM — the single-writer
   contract's violation signature."
  [cell]
  (> (:point cell) (clock-now)))

;; ---------------------------------------------------------------------------
;; cells: a ref's committed state {:value :point :version :history},
;; history a newest-first vector of {:point :value}. The cell atom is
;; the sole atom of a fresh Eve domain minted for this ref alone
;; (eve.atom/atom-domain), seated by name via
;; lookup-or-create-mmap-atom! — the same seat cohost's Eve hosting
;; uses. Domain geometry is Eve's current one; its cost is measured
;; in the lane report (STATUS 4 of the cohost lane; this lane's
;; probe_domains): ~6.8 MB eager heap and ~10^2 ms per domain on
;; the JVM — the binding constraint on ref-heavy programs, named in
;; RESEARCH.md, not hidden here.

(defonce ^:private cell-seq (atom 0))

(defn- new-cell
  "Mint a ref's cell atom. Unseated: the sole atom of a fresh heap
   domain, named from this process's sequence. Seated (seat =
   {:domain-path :name}): the named atom of the persistent domain
   at that path -- created by whichever process asks first, opened
   by the rest (lookup-or-create is cross-process safe)."
  ([x] (new-cell x nil))
  ([x seat]
   (if seat
     (ea/lookup-or-create-mmap-atom!
      (ea/persistent-atom-domain (:domain-path seat)
                                 :durable? (:durable? seat true))
      (:name seat)
      {:value x :point 0 :version 0 :history []})
     (let [d (ea/atom-domain {})
           n (swap! cell-seq inc)]
       (ea/lookup-or-create-mmap-atom! d (str "eve.stm/ref" n)
                                       {:value x :point 0 :version 0 :history []})))))

(defn- lock-cell-for
  "The seated ref's commit lock atom: the cell's Eve meta-atom --
   the atom of the cell's domain registered as eve.meta/<name>,
   which is eve.atom/meta-atom's residence. Opened through
   lookup-or-create (meta-atom's own construction) so this file
   also loads on Eve trees that predate the meta-atom fn; where
   meta-atom exists, (ea/meta-atom cell) returns this same atom."
  [domain name]
  (ea/lookup-or-create-mmap-atom! domain (str "eve.meta/" name) nil))

(defn- cell-deref [r] @(:cell r))

(defn- cell-cas!
  "Value-equality CAS on the ref's Eve cell atom. NOTE the substrate
   fact: on the JVM seat MmapAtom implements clojure.lang.IAtom's
   swap/reset but NOT compareAndSet (the bb form has it; the :clj
   form omits it — eve/atom.cljc, MmapAtom deftype), so a portable
   value-CAS is an Eve-side BUILD row (RESEARCH.md §a). The spike
   composes it from the atom's own arbitration instead: swap! runs
   f under the domain's per-atom arbitration, so a swap whose f
   returns the CURRENT value unchanged when it no longer equals
   old installs nothing semantically — and the returned value
   tells us whether our new value won. Sound because installs are
   additionally serialized by the ref's commit lock (commit! holds
   it across validate+install): the only writers who can interleave
   are other lock-holders, and there are none mid-install. An
   out-of-band writer can still interleave — the single-writer
   contract's outside; worst case it is observed as a lost CAS and
   the install escalates to retry, as designed."
  [r old new]
  (let [res (swap! (:cell r) (fn [cur] (if (= cur old) new cur)))]
    (= res new)))

(defn cell-residence
  "Where a ref's cell lives: :eve-own-domain, always, in this
   namespace — the construction has no shared-domain fallback."
  [r]
  :eve-own-domain)

;; ---------------------------------------------------------------------------
;; refs

(defonce ^:private ref-seq (atom 0))

(defn ref-value? [x] (and (map? x) (= ::ref (:eve.stm/kind x))))

(defn make-ref
  "clojure.lang.Ref's constructor, this namespace's shape. opts:
   :meta, :validator, :min-history, :max-history, and :seat
   {:domain-path :name :durable?} -- the disk-tier seating: the
   cell lives in the persistent domain at :domain-path under
   :name, and the ref's commit lock lives in the cell's Eve
   meta-atom. :durable? (default true) is the domain's own open
   opt: false gives the group-commit shape -- commits publish
   without a per-swap force and eve.atom/sync! is the caller's
   barrier (process-crash survival comes from the mapped pages
   either way; the force is for power loss). The cell — and
   its Eve domain — are minted immediately; creation is not
   transactional, as stock's is not."
  ([x] (make-ref x {}))
  ([x opts]
   (let [cell (new-cell x (:seat opts))]
     {:eve.stm/kind ::ref
      :seq (swap! ref-seq inc)
      :cell cell
      ;; the disk-tier commit lock: the cell's Eve meta-atom, when
      ;; seated; nil (the host state atom's :lock serves) when not
      :lock-cell (when (:seat opts)
                   (lock-cell-for (ea/persistent-atom-domain
                                   (:domain-path (:seat opts))
                                   :durable? (:durable? (:seat opts) true))
                                  (:name (:seat opts))))
      :state (atom {:validator (:validator opts)
                    :watches {}
                    :meta (:meta opts)
                    :min-history (or (:min-history opts) 0)
                    :max-history (or (:max-history opts) 10)
                    :lock nil})})))

(defn- value-at
  "The cell entry an attempt with read point rp sees: [point value],
   the newest entry with point <= rp — or ::too-short when the
   ref's retained history no longer reaches rp (the attempt must
   retry with a fresh point; stock faults the same way)."
  [cell rp]
  (if (<= (:point cell) rp)
    [(:point cell) (:value cell)]
    (or (first (map (fn [e] [(:point e) (:value e)])
                    (filter (fn [e] (<= (:point e) rp)) (:history cell))))
        ::too-short)))

;; ---------------------------------------------------------------------------
;; the attempt record: a per-scope local atom, dynamically bound.
;; {:id :rp (atom nil-or-long) :touched (atom {seq entry})
;;  :held-sends (atom [])}
;; entry: {:ref :base-point :base-value :ensured :actions []}

(def ^:dynamic *tx*
  "The running attempt's record on this thread, or nil."
  nil)

(defn in-transaction? [] (some? *tx*))

(defonce ^:private attempt-seq (atom 0))

;; ---------------------------------------------------------------------------
;; the process tag (disk-tier seating): when set, attempt ids are
;; [tag n] -- globally unique across the processes sharing a world,
;; so a seated lock's holder id never equals another process's
;; attempt id. Unset, ids are plain per-process longs, as before.

(defonce ^:private proc-tag (atom nil))

(defn seat-process-tag!
  "Give this process its attempt-id tag (claimed by the caller from
   a counter every process shares -- the demo claims it from an Eve
   atom in the clock domain). Returns the tag."
  [t]
  (reset! proc-tag t)
  t)

(defn- new-attempt-id []
  (let [n (swap! attempt-seq inc)]
    (if-let [t @proc-tag]
      [t n]
      n)))

(defn- read-point!
  "The attempt's read point, fixed lazily at its first ref touch."
  [att]
  (or @(:rp att)
      (let [p (clock-now)]
        (reset! (:rp att) p)
        p)))

(def read-lock-wait-ms
  "How long a read performed WHILE HOLDING COMMIT LOCKS waits on a
   ref whose commit lock another attempt holds before giving its
   attempt up with the plain retry signal (contention: never counted,
   unbounded). The bound applies ONLY under commit locks (Agent 3's
   Q-STM-WAIT ruling): body-phase reads hold no commit locks, so
   their waits cannot form a cycle and are UNBOUNDED, as stock's
   read lock is. Under locks the bound turns a wait that cannot end
   — two committers whose validators each read a ref the other holds
   locked — into a retry. A commit holds its locks far shorter than
   this (defect F1)."
  100)

(def ^:private read-lock-spins
  ;; checks of a held lock before the wait backs off to 1 ms sleeps:
  ;; a fast commit section passes inside the spin; an Eve-cell one
  ;; (milliseconds) is waited out without starving the committer of
  ;; the CPU it needs to finish (measured in cohost, defect F1)
  1000)

(defn- lock-holder
  "The attempt id holding r's commit lock, or nil: the ref's Eve
   meta-atom when the ref is seated at the disk tier, else its host
   state atom (stock's residence)."
  [r]
  (if-let [lc (:lock-cell r)]
    @lc
    (:lock @(:state r))))

(defn- held-by-other
  "The id of the attempt holding r's commit lock when that is not
   att (att nil: any holder), else nil."
  [att r]
  (let [l (lock-holder r)]
    (when (and (some? l) (not= l (:id att))) l)))

(defn- await-unlocked!
  "Stock's read lock (Ref.lock.readLock, taken by
   LockingTransaction.doGet and by Ref.currentVal): do not read r's
   cell while another attempt holds r's commit lock. commit! holds its
   locks from before it claims its commit point until after its last
   install, so a read that passes this wait AFTER its read point was
   fixed sees every install of every commit whose point it can read.
   Without it (defect F1), an attempt whose read point equals a
   commit's point read that commit's installed refs new and its
   not-yet-installed refs old: a torn snapshot, which a read-only
   transaction returns (read-only refs are not validated — stock).
   The wait is UNBOUNDED in body phase and outside a transaction
   (att nil), as Ref.currentVal's read lock is: body-phase reads
   hold no commit locks, so they cannot form a cycle (Agent 3,
   Q-STM-WAIT). Only a read performed while the attempt HOLDS its
   commit locks (commit phase: the :locks-held flag commit! sets)
   is bounded by read-lock-wait-ms and gives up with the retry
   signal, cause :validator-bound. The wait spins read-lock-spins
   checks, then backs off in 1 ms sleeps."
  [att r]
  (when (held-by-other att r)
    (let [locks-held (boolean (and att @(:locks-held att)))
          deadline (when locks-held (+ (now-ms) read-lock-wait-ms))]
      (loop [n 0]
        (when (held-by-other att r)
          (when (and deadline (> (now-ms) deadline))
            (throw (retry-ex :validator-bound)))
          (when (>= n read-lock-spins)
            (sleep-ms 1))
          (recur (inc n)))))))

(defn- touch!
  "Enroll ref r in the attempt: read its value as of the attempt's
   read point and record the base it stands at. The read point is
   fixed FIRST; then the read waits out another attempt's commit
   lock on r (await-unlocked!, defect F1)."
  [att r]
  (let [t @(:touched att)]
    (or (get t (:seq r))
        (let [rp (read-point! att)
              _ (await-unlocked! att r)
              cell (cell-deref r)
              va (value-at cell rp)]
          (when (= ::too-short va)
            ;; ::too-short against a cell within the clock is the
            ;; legal history-trim case: plain retry. Against a cell
            ;; beyond the clock the fresh point can never be
            ;; served: a basis fault.
            (throw (if (beyond-clock? cell) (basis-fault-ex) (retry-ex :history-trim))))
          (let [e {:ref r :base-point (first va) :base-value (second va)
                   :ensured false :actions []}]
            (swap! (:touched att) assoc (:seq r) e)
            e)))))

(defn- view-of
  "The attempt's current value of an entry: its base value (as of
   the read point) folded with its own actions, in order."
  [e]
  (reduce (fn [v [op a b]]
            (case op
              :set a
              :commute (apply a v b)))
          (:base-value e)
          (:actions e)))

(defn- record-action! [att r action]
  (touch! att r)
  (swap! (:touched att) update (:seq r)
         (fn [e] (update e :actions conj action)))
  (view-of (get @(:touched att) (:seq r))))

(defn- require-tx! []
  (when-not *tx*
    (throw (illegal-state "No transaction running")))
  *tx*)

;; ---------------------------------------------------------------------------
;; ref operations

(defn deref-ref
  "deref of a ref: inside a transaction, the attempt's view (the
   value as of its read point, folded with its own writes);
   outside, the latest committed value, read once no commit holds
   the ref's lock (Ref.currentVal's read lock: a reader that has
   seen one ref of a commit cannot then see another ref of that
   commit older — defect F1, outside a transaction)."
  [r]
  (if *tx*
    (view-of (touch! *tx* r))
    (do (await-unlocked! nil r)
        (:value (cell-deref r)))))

(defn ref-set*
  "ref-set: the attempt's value of r becomes v; the attempt's view of it."
  [r v]
  (record-action! (require-tx!) r [:set v]))

(defn alter*
  "alter: apply f to the attempt's view of r, record the result as a
   set. A ref altered in an attempt is validated at commit."
  [r f args]
  (let [att (require-tx!)
        e (touch! att r)
        v (apply f (view-of e) args)]
    (swap! (:touched att) update (:seq r)
           (fn [e] (update e :actions conj [:set v])))
    v))

(defn commute*
  "commute: record f to fold over the CURRENT committed value at
   commit time, in call order; the attempt's view applies it now,
   over its view. A commute-only ref is not validated at commit."
  [r f args]
  (record-action! (require-tx!) r [:commute f args]))

(defn ensure*
  "ensure (Ref.touch): enroll r in the attempt's validated set, so
   a concurrent commit to it retries this attempt — the write-skew
   guard."
  [r]
  (let [att (require-tx!)
        e (touch! att r)]
    (swap! (:touched att) assoc-in [(:seq r) :ensured] true)
    (view-of e)))

(defn ref-history-count [r] (count (:history (cell-deref r))))
(defn ref-min-history [r] (:min-history @(:state r)))
(defn ref-max-history [r] (:max-history @(:state r)))
(defn set-min-history! [r n] (swap! (:state r) assoc :min-history n) r)
(defn set-max-history! [r n] (swap! (:state r) assoc :max-history n) r)

;; watches and validators: the ref's own state atom (non-transactional,
;; as stock's are)

(defn add-watch* [r k f] (swap! (:state r) assoc-in [:watches k] f) r)
(defn remove-watch* [r k] (swap! (:state r) update :watches dissoc k) r)
(defn get-watches [r] (:watches @(:state r)))
(defn set-validator* [r f]
  (when f
    (let [v (deref-ref r)]
      (when-not (f v)
        (throw (illegal-state "Invalid reference state")))))
  (swap! (:state r) assoc :validator f)
  nil)
(defn get-validator [r] (:validator @(:state r)))

(defn meta-of [r] (:meta @(:state r)))

(defn with-meta-ref [r m]
  (assoc r :state (atom (assoc @(:state r) :meta m))))

(defn alter-meta-ref [r f args]
  (let [s (swap! (:state r) (fn [s] (assoc s :meta (apply f (:meta s) args))))]
    (:meta s)))

;; ---------------------------------------------------------------------------
;; commit: per-ref locks + per-ref validation + per-ref CAS installs.
;; This section is where cross-domain atomicity would have to live
;; if it existed; it does not, here or in stock Clojure — the
;; composition below IS the commit semantics (RESEARCH.md §c).

(defn- written? [e] (boolean (some #(= :set (first %)) (:actions e))))

(defn- fold-actions
  "Fold an entry's actions over base: :set replaces, :commute applies."
  [base actions]
  (reduce (fn [v [op a b]]
            (case op
              :set a
              :commute (apply a v b)))
          base
          actions))

(defn- eve-value-cas!
  "Value-equality CAS composed from an Eve atom's own swap!, the
   composition cell-cas! uses: swap! runs f under the domain's
   per-atom arbitration, so an f that returns the current value
   unchanged when it no longer equals old installs nothing -- and
   the returned value tells us whether our new value won. Sound
   for the seated lock for the same reason it is sound for cells:
   the arbitration is the domain's, and it is cross-process."
  [a old new]
  (= new (swap! a (fn [cur] (if (= cur old) new cur)))))

(defn- try-lock!
  "CAS the ref's commit lock from free to this attempt's id.
   Unseated, the lock is a field of the ref's own state atom — host-atom CAS,
   never an Eve publish. Seated, the lock is the ref's Eve
   meta-atom, CASed the same way, so a lock held by another
   PROCESS excludes this one."
  [att r]
  (if-let [lc (:lock-cell r)]
    (or (= (:id att) @lc)
        (eve-value-cas! lc nil (:id att)))
    (let [st (:state r)]
      (loop []
        (let [s @st]
          (cond (nil? (:lock s)) (if (compare-and-set! st s (assoc s :lock (:id att)))
                                   true
                                   (recur))
                (= (:id att) (:lock s)) true
                :else false))))))

(defn- lock!
  "Take r's commit lock for att, waiting until it is free: spin
   read-lock-spins tries, then back off in 1 ms sleeps, as
   await-unlocked! does. UNBOUNDED, never a retry: commit! takes its
   lock set in one global order (ref :seq, creation order), and the
   only other wait made while holding commit locks is the bounded
   validator read (await-unlocked!), so a writer waiting here cannot
   be part of a cycle (Lane M's decision on the operator's word,
   after Agent 3's Q-STM-WAIT rule). Serializing at commit is what
   the commute case wants: never re-run the body for a lock alone.
   A seated lock try is a full Eve swap (the holder's commit
   section may be in another process), so seated tries back off
   1 ms from the first try instead of burning the spin."
  [att r]
  (loop [n 0]
    (when-not (try-lock! att r)
      (when (or (:lock-cell r) (>= n read-lock-spins))
        (sleep-ms 1))
      (recur (inc n)))))

(defn- unlock! [att r]
  (if-let [lc (:lock-cell r)]
    (swap! lc (fn [cur] (if (= cur (:id att)) nil cur)))
    (let [st (:state r)]
      (loop []
        (let [s @st]
          (when (= (:id att) (:lock s))
            (when-not (compare-and-set! st s (assoc s :lock nil))
              (recur)))))))
  nil)

(defn- trim-history
  "Bound a history vector: never more than max-history entries,
   never fewer than min-history (stock's fault window lives between
   the two: a read point older than the oldest retained entry
   retries with a fresh point).

   The kept prefix is rebuilt as a FRESH vector, never a subvec:
   a subvec shares the untrimmed vector's backing storage, so the
   trimmed entries would stay reachable through the cell — on a GC
   host a small bounded over-retention, on a host without a GC a
   slab tree pinned forever per ref. A trim must RELEASE, not
   merely unlist (the cohost lane's reclaim probe caught the
   subvec shape retaining exactly one trimmed version)."
  [h mn mx]
  (let [keep (max mn mx)]
    (if (> (count h) keep)
      (vec (take keep h))
      h)))

(defn- install!
  "Install one written/commuted entry under its lock: CAS the ref's
   cell from the state validation saw to the new cell at commit
   point cp. Returns [ref old-value final-value] for the watches.
   The CAS gives up after 100 failures and escalates to the retry
   signal (or the basis-fault signal against a beyond-clock cell)
   — the runner re-runs the body against a fresh read point."
  [att e cp]
  (let [r (:ref e)]
    (loop [n 0]
      (let [cur (cell-deref r)
            final (if (written? e)
                    (fold-actions (:base-value e) (:actions e))
                    (fold-actions (:value cur) (:actions e)))
            new-cell {:value final
                      :point cp
                      :version (inc (:version cur))
                      :history (trim-history
                                (into [{:point (:point cur) :value (:value cur)}]
                                      (:history cur))
                                (ref-min-history r)
                                (ref-max-history r))}]
        (cond (cell-cas! r cur new-cell) [r (:value cur) final]
              (< n 100) (recur (inc n))
              :else (throw (if (beyond-clock? cur) (basis-fault-ex) (retry-ex :install-cas))))))))

(defn- commit!
  "Validate the attempt against the touched refs' own cells and
   install its writes, one CAS per ref, under the refs' own locks
   (lock! waits for a held lock; it never gives up). Throws the
   retry signal on a validation failure; returns the watch list
   [[ref old new] ...]."
  [att]
  (let [touched @(:touched att)
        entries (sort-by (fn [e] (:seq (:ref e))) (vals touched))
        installs (filter (fn [e] (seq (:actions e))) entries)
        ;; the lock set is installs UNION ensured: stock's ensure
        ;; write-locks the ref for the rest of the transaction, and
        ;; that lock is what closes the validate/install race for a
        ;; ref this attempt does not write. Locking only installs
        ;; left the race open in the cohost lane — invisible over
        ;; nanosecond host cells, a lost skew check over
        ;; millisecond Eve cells.
        lock-set (filter (fn [e] (or (seq (:actions e)) (:ensured e))) entries)
        locked (atom [])]
    ;; the commit phase holds locks from here to the finally: reads
    ;; performed under them (a validator fn that derefs) are the
    ;; ONLY bounded read waits (Q-STM-WAIT)
    (reset! (:locks-held att) true)
    (try
      ;; (1) lock every ref we will install into or ensured, in
      ;; creation order (the one global order; lock! waits)
      (doseq [e lock-set]
        (lock! att (:ref e))
        (swap! locked conj (:ref e)))
      ;; (2) validate: every written or ensured ref must still
      ;; stand at the point the attempt read. Read-only refs and
      ;; commute-only refs are not validated (stock's answers).
      (doseq [e entries]
        (when (or (:ensured e) (written? e))
          (let [cur (cell-deref (:ref e))]
            (when (not= (:base-point e) (:point cur))
              (throw (if (beyond-clock? cur) (basis-fault-ex) (retry-ex :validation)))))))
      ;; (3) validators against final values
      (doseq [e installs]
        (let [validator (:validator @(:state (:ref e)))]
          (when validator
            (let [final (if (written? e)
                          (fold-actions (:base-value e) (:actions e))
                          (fold-actions (:value (cell-deref (:ref e))) (:actions e)))]
              (when-not (validator final)
                (throw (illegal-state "Invalid reference state")))))))
      ;; (4) claim the commit point -- only an attempt with installs
      ;; claims one: a read-only attempt must not advance the shared
      ;; clock (stock's lastPoint moves on committing writers only;
      ;; readers claiming points make the clock a contention hot
      ;; spot, spam the seated clock atom from every snapshot, and
      ;; break cross-process clock reproducibility -- the ants jank
      ;; seat's stage-(a) probe caught exactly that), (5) install
      ;; per ref
      (let [cp (when (seq installs) (claim-point!))]
        (mapv (fn [e] (install! att e cp)) installs))
      (finally
        (doseq [r @locked]
          (unlock! att r))
        (reset! (:locks-held att) false)))))

;; ---------------------------------------------------------------------------
;; the send seam: Eve has no agents; a dispatcher installed by
;; whatever agent/executor layer a program uses receives released
;; sends. Inside a transaction a send is held in the attempt record
;; and released by the successful attempt only, exactly once — a
;; retried attempt's record dies with it, and the re-run's sends are
;; recorded afresh.

(defonce ^:private send-dispatcher (atom nil))

(defn set-send-dispatcher!
  "Install the send dispatcher: a 1-arg fn receiving each released
   send. Until one is installed, released sends are dropped (and an
   in-transaction dispatch! is still held correctly — it dies with
   an aborting attempt and fires on the winning one)."
  [f]
  (reset! send-dispatcher f)
  nil)

(defn dispatch!
  "The STM half of the agent send seam: inside a transaction, hold
   msg for release at commit; outside, dispatch immediately."
  [msg]
  (if *tx*
    (do (swap! (:held-sends *tx*) conj msg) true)
    (do (when-let [d @send-dispatcher] (d msg)) false)))

(defn- release-sends! [att]
  (when-let [d @send-dispatcher]
    (doseq [msg @(:held-sends att)]
      (d msg)))
  nil)

;; ---------------------------------------------------------------------------
;; interruption: the runner's cooperative checkpoint. JVM seat: the
;; thread's own interrupt flag, peeked (never cleared by the check).

(defn- interrupted-now? []
  #?(:jank false
     :clj (.isInterrupted (Thread/currentThread))
     ;; single-threaded seats have no flag to peek: the checkpoint is
     ;; the attempt boundary itself (RESEARCH.md section b)
     :default false))

(defn- interrupted-ex []
  #?(:jank (ex-info "eve.stm: transaction interrupted"
                    {:eve.stm/interrupted true})
     :clj (InterruptedException. "eve.stm: transaction interrupted")
     :lpy (InterruptedException. "eve.stm: transaction interrupted")
     :default (ex-info "eve.stm: transaction interrupted"
                       {:eve.stm/interrupted true})))

(defn- interrupted-ex? [e]
  #?(:jank (boolean (:eve.stm/interrupted (ex-data (signal-of e))))
     :clj (instance? InterruptedException e)
     :lpy (instance? InterruptedException e)
     :default (boolean (:eve.stm/interrupted (ex-data (signal-of e))))))

(defn run-in-transaction
  "clojure.lang.LockingTransaction.runInTransaction, this
   namespace's shape: run f in an attempt; on the retry signal,
   re-run against a fresh read point; on the thread's interrupt
   flag, abandon with InterruptedException (checked once per
   attempt — the cooperative checkpoint). A nested call joins the
   running attempt, as stock's does. Validators run at commit;
   watches fire once per written ref after the winning installs,
   before the attempt's held sends are released.

   Retries are unbounded for contention. Basis faults are counted
   per invocation; at basis-fault-limit the invocation throws an
   IllegalStateException instead of retrying."
  [f]
  (if *tx*
    (f)
    (loop [basis-faults 0]
      (when (interrupted-now?)
        (throw (interrupted-ex)))
      (swap! stats* update :attempts inc)
      (let [att {:id (new-attempt-id)
                 :rp (atom nil)
                 :touched (atom {})
                 :held-sends (atom [])
                 ;; Q-STM-WAIT: true only while commit! holds this
                 ;; attempt's commit locks; gates the read-wait bound
                 :locks-held (atom false)}
            outcome (binding [*tx* att]
                      (try
                        (let [v (f)
                              watches (commit! att)]
                          {:value v :watches watches})
                        (catch #?(:clj Throwable
                                  :cljr System.Exception
                                  :cljs :default
                                  ;; basilisp's catch names a host class
                                  :lpy python/Exception
                                  :default Throwable)
                               e {:error e})))]
        (if-let [e (:error outcome)]
          (cond (basis-fault-ex? e)
                (if (>= basis-faults basis-fault-limit)
                  (throw (illegal-state "Transaction failed after reaching retry limit"))
                  (do (swap! stats* update :retries inc)
                      (recur (inc basis-faults))))
                (retry-ex? e) (do (swap! stats* (fn [s]
                                                  (-> s
                                                      (update :retries inc)
                                                      (update-in [:retry-causes (or (:eve.stm/retry-cause (ex-data (signal-of e))) :unspecified)]
                                                                 (fnil inc 0)))))
                                  (recur basis-faults))
                (interrupted-ex? e) (throw e)
                :else (throw e))
          (do (swap! stats* update :commits inc)
              (doseq [[r old new] (:watches outcome)]
                (doseq [[k wf] (:watches @(:state r))]
                  (wf k r old new)))
              (release-sends! att)
              (:value outcome)))))))

(defmacro dosync
  "dosync, the layer's shape: run body in a transaction."
  [& body]
  `(run-in-transaction (fn [] ~@body)))

(defmacro sync
  "sync: the flags (:release-pending-sends) are not modeled —
   pending sends here are the agent seam's, released at commit;
   named as a distance in RESEARCH.md."
  [_flags & body]
  `(run-in-transaction (fn [] ~@body)))
