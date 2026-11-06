(ns eve.atom
  "Persistent atoms over slab memory — one implementation for every host.

   A domain is a set of slab regions (.slab0–.slab6), a root region (.root:
   header, 256 worker slots, 256 atom slots) and a reader map (.rmap), either
   mmap files shared between processes or in-process memory. The root pointer
   of an atom is a slab-qualified i32 [class:3|block:29]. A swap! allocates the
   new value's blocks, CASes the root pointer, then retires the old value's
   blocks behind the epoch its readers pinned.

   The host enters only through the seams: IMemRegion (eve.mem) for the root
   and reader map, ISlabIO (eve.deftype-proto.alloc) for the slabs, IEveRoot /
   IEveSio (eve.deftype-proto.data) for the values, and the few one-line
   primitives at the top of this file (the clock, the thread id, a sleep, a
   timer, the exit hook, the atom interfaces of the host's core). A host whose
   root region has no atomic read-modify-write (babashka) takes the exclusive
   hold around each swap (ROOT_HOLD/ROOT_INFLIGHT, eve.deftype-proto.data)."
  (:refer-clojure :exclude [atom
                            aget alength long class
                            volatile! vswap! locking transient persistent! conj! bytes])
  (:require
   [eve.host :refer [aget alength long class volatile!
                     vswap! locking transient persistent! conj! bytes token]]
   [eve.encoding :as enc]
   [eve.int32 :as i32]
   [eve.process :as proc]
   [eve.journal-file :as jf]
   [eve.read-region :as rr]
   ;; PROTOTYPE (jvm-heap-grade step 2): the automatic read guard, JVM only
   ;; (bb and glj read :clj but cannot load the JVM-only eve.guard: no guard there, as on cljs)
   #?@(:bb [] :glj [] :clj [[eve.guard :as guard]])
   [eve.deftype-proto.data :as d]
   [eve.slab-io :as slab]
   [eve.deftype-proto.alloc :as alloc]
   ;; the host's process slab (ClojureScript's module slabs register here)
   [eve.process-slab]
   [eve.deftype-proto.serialize :as ser]
   [eve.region :as region]
   [eve.mem :as mem]
   [eve.hamt-util :as hu]
   [eve.map :as eve-map]
   [eve.set :as eve-set]
   [eve.vec :as eve-vec]
   [eve.list :as eve-list]
   [eve.retire :as retire]
   [eve.tally :as tally]
   [eve.core :as ecore]
   [eve.deftype :as edt]
   ;; the array and object types, so their blocks read back in any value
   [eve.array :as arr]
   [eve.obj]
   [eve.deftype-proto.macros]))

;; ---------------------------------------------------------------------------
;; Constants
;; Root pointer format: alloc/encode-slab-offset [class:3 | block:29]
;; ---------------------------------------------------------------------------

(def ^:const ROOT_BYTES d/ROOT_FILE_SIZE_V2) ;; 8320 (V2 multi-atom)
(def ^:const READER_MAP_BYTES 262144)

;; Time-throttled retire flush — skip the 256-slot scan when the last scan
;; was recent and the queue is small. Only ever delays a free.
(def ^:const FLUSH_INTERVAL_MS 50)
(def ^:const FLUSH_QUEUE_THRESHOLD 64)

;; CAS retry backoff — jittered exponential backoff after the third failure.
(def ^:const BACKOFF_CAP_MS 8)

(def ^:const DEFAULT_ATOM_SLOT 1) ;; slot 0 = registry, slot 1 = default atom
(def ^:const CLAIMED_SENTINEL -2) ;; "claimed but no value" slot marker

;; The exclusive hold a joining lock-only participant takes before it has a
;; slot (its value in ROOT_HOLD); cleared by a waiter after HEARTBEAT_TIMEOUT_MS.
(def ^:const HOLD_JOINING (inc d/MAX_WORKERS))

;; ---------------------------------------------------------------------------
;; The host's process primitives — the clock, a thread's identity, sleep, a
;; periodic timer, a hook at exit, files — are eve.process's, an adapter
;; (eve/adapters/process/, adapters/README.md).
;; ---------------------------------------------------------------------------

(defn- cas-backoff!
  "Jittered exponential backoff after a lost CAS. The first three retries are
   immediate; from the fourth, 1..min(2^(n-3), 8) ms."
  [attempt]
  (when (> attempt 3)
    (let [max-ms (min (bit-shift-left 1 (min (- attempt 3) 3)) BACKOFF_CAP_MS)]
      (proc/sleep-ms! (inc (rand-int max-ms))))))

;; ---------------------------------------------------------------------------
;; Worker slots — on the root region
;; ---------------------------------------------------------------------------

(defn- worker-slot-offset [slot-idx field-offset]
  (+ d/ROOT_WORKER_REGISTRY_START (* slot-idx d/WORKER_SLOT_SIZE) field-offset))

(defn- write-heartbeat!
  "Write the clock as a 64-bit millisecond timestamp in two i32 words.
   hi is the quotient by 2^32 (on CLJS `(unsigned-bit-shift-right now 32)` is
   `now >>> 0`: hi was lo and a Node slot never went stale — round 2, N3)."
  [root-r slot-idx]
  (let [now (proc/now-ms)
        hi  (quot now 4294967296)
        lo  (- now (* hi 4294967296))]
    (mem/-store-i32! root-r (worker-slot-offset slot-idx d/OFFSET_WS_HEARTBEAT_LO)
                     (i32/i32 lo))
    (mem/-store-i32! root-r (worker-slot-offset slot-idx d/OFFSET_WS_HEARTBEAT_HI)
                     (i32/i32 hi))))

(defn- heartbeat-stale?
  "True if slot-idx's heartbeat is older than HEARTBEAT_TIMEOUT_MS."
  [root-r slot-idx]
  (let [lo (mem/-load-i32 root-r (worker-slot-offset slot-idx d/OFFSET_WS_HEARTBEAT_LO))
        hi (mem/-load-i32 root-r (worker-slot-offset slot-idx d/OFFSET_WS_HEARTBEAT_HI))
        lo (if (neg? lo) (+ lo 4294967296) lo)
        ts (+ (* hi 4294967296) lo)]
    (> (- (proc/now-ms) ts) d/HEARTBEAT_TIMEOUT_MS)))

(defn- mmap-claim-slot!
  "CAS-scan the worker registry for the first INACTIVE slot; failing that,
   reclaim a stale ACTIVE one. Stores ACTIVE, zeroes CURRENT_EPOCH, returns
   the slot index. Throws when all 256 slots are live."
  [root-r]
  (or
   (loop [i 0]
     (when (< i d/MAX_WORKERS)
       (let [status-off (worker-slot-offset i d/OFFSET_WS_STATUS)
             witness (mem/-cas-i32! root-r status-off
                                    d/WORKER_STATUS_INACTIVE
                                    d/WORKER_STATUS_ACTIVE)]
         (if (== witness d/WORKER_STATUS_INACTIVE)
           (do (mem/-store-i32! root-r (worker-slot-offset i d/OFFSET_WS_CURRENT_EPOCH) 0)
               i)
           (recur (inc i))))))
   (loop [i 0]
     (when (< i d/MAX_WORKERS)
       (let [status (mem/-load-i32 root-r (worker-slot-offset i d/OFFSET_WS_STATUS))]
         (if (and (== status d/WORKER_STATUS_ACTIVE)
                  (heartbeat-stale? root-r i))
           (let [witness (mem/-cas-i32! root-r (worker-slot-offset i d/OFFSET_WS_STATUS)
                                        d/WORKER_STATUS_ACTIVE d/WORKER_STATUS_ACTIVE)]
             (if (== witness d/WORKER_STATUS_ACTIVE)
               (do (mem/-store-i32! root-r (worker-slot-offset i d/OFFSET_WS_CURRENT_EPOCH) 0)
                   i)
               (recur (inc i))))
           (recur (inc i))))))
   (throw (ex-info "mmap-atom: no free worker slot in .root" {}))))

(defn- mmap-release-slot!
  "Clear CURRENT_EPOCH then set STATUS back to INACTIVE."
  [root-r slot-idx]
  (mem/-store-i32! root-r (worker-slot-offset slot-idx d/OFFSET_WS_CURRENT_EPOCH) 0)
  (mem/-store-i32! root-r (worker-slot-offset slot-idx d/OFFSET_WS_STATUS)
                   d/WORKER_STATUS_INACTIVE))

(defn- mmap-pin-epoch!
  "Announce this slot reads at epoch. Before the root pointer is read."
  [root-r slot-idx epoch]
  (mem/-store-i32! root-r (worker-slot-offset slot-idx d/OFFSET_WS_CURRENT_EPOCH) epoch))

(defn- mmap-unpin-epoch!
  "Clear the announcement. After the read, in a finally."
  [root-r slot-idx]
  (mem/-store-i32! root-r (worker-slot-offset slot-idx d/OFFSET_WS_CURRENT_EPOCH) 0))

(defn- mmap-min-safe-epoch
  "The minimum CURRENT_EPOCH among live, pinned slots; nil when none is
   pinned (every retired entry is reclaimable)."
  [root-r]
  (loop [i 0 result nil]
    (if (>= i d/MAX_WORKERS)
      result
      (let [status (mem/-load-i32 root-r (worker-slot-offset i d/OFFSET_WS_STATUS))
            epoch (mem/-load-i32 root-r (worker-slot-offset i d/OFFSET_WS_CURRENT_EPOCH))]
        (if (and (== status d/WORKER_STATUS_ACTIVE)
                 (not (zero? epoch))
                 (not (heartbeat-stale? root-r i)))
          (recur (inc i) (if (nil? result) epoch (min result epoch)))
          (recur (inc i) result))))))

;; ---------------------------------------------------------------------------
;; The exclusive hold — how a host without atomic RMW takes part
;; ---------------------------------------------------------------------------
;; data.cljc names the two root words. A lock-free host counts each write
;; (a swap, a slot claim) into ROOT_INFLIGHT and backs off while ROOT_HOLD is
;; set; a lock-only host (its root region answers false to -atomic-rmw?)
;; takes a byte-range file lock on the hold word, sets ROOT_HOLD, waits for
;; ROOT_INFLIGHT to drain, runs its whole write with plain loads and stores,
;; then clears ROOT_HOLD. Both orders of the Dekker pair are safe: the host
;; that sees the other's word first yields; a host that saw neither cannot
;; exist, because each write is fenced before the other's read.

(defn- hold-holder-stale?
  "A hold whose holder's heartbeat is HEARTBEAT_TIMEOUT_MS old, or a joining
   hold (no slot yet) that a waiter has watched for that long."
  [root-r hold waited-ms]
  (if (== hold HOLD_JOINING)
    (> waited-ms d/HEARTBEAT_TIMEOUT_MS)
    (heartbeat-stale? root-r (dec hold))))

(defn- wait-hold-clear!
  "Spin, then sleep, until ROOT_HOLD is 0; a dead holder's hold is cleared."
  [root-r]
  (let [t0 (proc/now-ms)]
    (loop [i 0]
      (let [hold (mem/-load-i32 root-r d/ROOT_HOLD_OFFSET)]
        (when-not (zero? hold)
          (if (and (> i 1000) (hold-holder-stale? root-r hold (- (proc/now-ms) t0)))
            (mem/-cas-i32! root-r d/ROOT_HOLD_OFFSET hold 0)
            (do (when (> i 100) (proc/sleep-ms! 1))
                (recur (inc i)))))))))

(defn- inflight-corrupt! [root-r op old]
  (throw (ex-info "exclusive-hold protocol: ROOT_INFLIGHT count is corrupt"
                  {:op op :inflight-before old
                   :hold (mem/-load-i32 root-r d/ROOT_HOLD_OFFSET)})))

(defn- enter-inflight!
  "Lock-free host: count this write in, yielding to a held domain. The
   count never goes negative; a negative old value is a corrupt domain and
   is reported, never spun on."
  [root-r]
  (loop []
    (let [old (mem/-add-i32! root-r d/ROOT_INFLIGHT_OFFSET 1)]
      (when (neg? old) (inflight-corrupt! root-r :enter old)))
    (when-not (zero? (mem/-load-i32 root-r d/ROOT_HOLD_OFFSET))
      (mem/-sub-i32! root-r d/ROOT_INFLIGHT_OFFSET 1)
      (wait-hold-clear! root-r)
      (recur))))

(defn- leave-inflight! [root-r]
  (let [old (mem/-sub-i32! root-r d/ROOT_INFLIGHT_OFFSET 1)]
    (when (< old 1) (inflight-corrupt! root-r :leave old))))

(defn- with-exclusive-hold*
  "Lock-only host: run f with the domain held — no other participant writes."
  [root-r hold-value f]
  (mem/-with-file-lock root-r d/ROOT_HOLD_OFFSET
                       (fn []
      ;; the region's store is fenced: the hold is visible before the wait
                         (mem/-store-i32! root-r d/ROOT_HOLD_OFFSET hold-value)
                         (try
                           (loop [i 0]
                             (let [n (mem/-load-i32 root-r d/ROOT_INFLIGHT_OFFSET)]
                               (when (neg? n) (inflight-corrupt! root-r :hold n))
                               (when-not (zero? n)
                                 (when (> i 100) (proc/sleep-ms! 1))
                                 (recur (inc i)))))
                           (f)
                           (finally
                             (mem/-store-i32! root-r d/ROOT_HOLD_OFFSET 0))))))

(defn- with-root-write*
  "Run f as a write to the root region: in flight for a lock-free host,
   under the exclusive hold for a lock-only one."
  [root-r lock-only? hold-value f]
  (if lock-only?
    (with-exclusive-hold* root-r hold-value f)
    (do (enter-inflight! root-r)
        (try (f)
             (finally (leave-inflight! root-r))))))

(defn- with-held-write*
  "A lock-free host's exclusive write, taken under the domain's hold: in flight, it takes
   ROOT_HOLD by CAS, waits until it is the one write in flight — a lock-free write that starts sees the hold and
   backs off; a lock-only participant stores its hold under its file lock and then waits for the in-flight count to
   drain, so it waits for this one — runs f, and gives the hold back when it is still its own."
  [root-r hold-value f]
  (loop []
    (enter-inflight! root-r)
    (if (zero? (mem/-cas-i32! root-r d/ROOT_HOLD_OFFSET 0 hold-value))
      (try
        (loop [i 0]
          (let [n (mem/-load-i32 root-r d/ROOT_INFLIGHT_OFFSET)]
            (when (neg? n) (inflight-corrupt! root-r :hold n))
            (when (> n 1)
              (when (> i 100) (proc/sleep-ms! 1))
              (recur (inc i)))))
        (f)
        (finally
          (mem/-cas-i32! root-r d/ROOT_HOLD_OFFSET hold-value 0)
          (leave-inflight! root-r)))
      (do (leave-inflight! root-r)
          (wait-hold-clear! root-r)
          (recur)))))

(defn- with-domain-write
  "(f exclusive?) as a write to the domain ds: exclusive? when no other participant writes while it runs — a
   lock-only host's every write, a write that asked for the hold (hold?), and a write this thread makes while it
   holds the domain (it runs inside that hold)."
  [{:keys [root-r lock-only? slot-idx holder]} hold? f]
  (let [tid (proc/thread-id)
        held (fn [] (reset! holder tid) (try (f true) (finally (reset! holder nil))))]
    (cond
      (= @holder tid) (f true)
      lock-only? (with-exclusive-hold* root-r (inc slot-idx) held)
      hold? (with-held-write* root-r (inc slot-idx) held)
      :else (with-root-write* root-r false (inc slot-idx) (fn [] (f false))))))

;; ---------------------------------------------------------------------------
;; Domain root initialization
;; ---------------------------------------------------------------------------

(defn init-domain-root!
  "Write the V2 domain root header into root-r (any IMemRegion): epoch,
   worker registry, atom table, the hold words, and the magic last — a
   process joining a domain while its creator writes it reads the root once
   the magic is there (await-domain-root!). Slab allocation is the caller's
   job. Returns root-r."
  [root-r]
  (mem/-store-i32! root-r d/ROOT_ATOM_PTR_OFFSET 0) ;; now: atom slot count
  (mem/-store-i32! root-r d/ROOT_EPOCH_OFFSET 1)
  (mem/-store-i32! root-r d/ROOT_WORKER_REG_OFFSET d/ROOT_WORKER_REGISTRY_START)
  (mem/-store-i32! root-r d/ROOT_ATOM_TABLE_OFFSET d/ATOM_TABLE_START)
  (mem/-store-i32! root-r d/ROOT_ATOM_TABLE_CAPACITY d/MAX_ATOM_SLOTS)
  (mem/-store-i32! root-r d/ROOT_HOLD_OFFSET 0)
  (mem/-store-i32! root-r d/ROOT_INFLIGHT_OFFSET 0)
  (dotimes [slot d/MAX_WORKERS]
    (mem/-store-i32! root-r
                     (+ d/ROOT_WORKER_REGISTRY_START (* slot d/WORKER_SLOT_SIZE))
                     d/WORKER_STATUS_INACTIVE))
  (mem/-store-i32! root-r d/ATOM_TABLE_HEADER_START d/ATOM_TABLE_MAGIC)
  (mem/-store-i32! root-r (+ d/ATOM_TABLE_HEADER_START 4) 0) ;; slot count
  (dotimes [s d/MAX_ATOM_SLOTS]
    (mem/-store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_PTR_OFFSET) alloc/NIL_OFFSET)
    (mem/-store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_HASH_OFFSET) 0))
  ;; the region's store is fenced: everything above is visible before it
  (mem/-store-i32! root-r d/ROOT_MAGIC_OFFSET d/ROOT_MAGIC_V2)
  root-r)

;; ---------------------------------------------------------------------------
;; Domain state — one shape on every host
;; ---------------------------------------------------------------------------
;;   :root-r :rmap-r  IMemRegion       :sio        ISlabIO (the domain's)
;;   :base-path       str | nil        :slot-idx   this participant's worker slot
;;   :lock-only?      no atomic RMW → exclusive hold per write
;;   :heartbeat       the timer handle (nil for an in-process domain)
;;   :retire-q        (atom [{:off o :kind k :epoch e} …]) — the zero queue: blocks whose count reached 0
;;   :flush-ts        (atom ms)        :pins (atom {thread-id [epoch depth]})
;;   :pin-lock        the host's lock  :closed? (atom bool)
;;   :holder          (atom thread-id) the thread of this process holding the domain's exclusive hold, nil for none

(declare park-fn)

(defn- make-domain-state
  [root-r rmap-r sio base-path slot-idx heartbeat]
  (let [ds {:root-r root-r :rmap-r rmap-r :sio sio :base-path base-path
            :slot-idx slot-idx
            :lock-only? (not (mem/-atomic-rmw? root-r))
            :heartbeat heartbeat
            :retire-q (clojure.core/atom [])
            :flush-ts (clojure.core/atom 0)
            :pins (clojure.core/atom {})
            :pin-lock (token)
            :holder (clojure.core/atom nil)
            :closed? (clojure.core/atom false)
            ;; PROTOTYPE (step 2): what swap! does with a slab-backed value of ANOTHER domain — :throw (today's
            ;; behaviour) or :copy (materialize it under the reader's pin and write it here). set-foreign-policy!
            :foreign-policy (clojure.core/atom :throw)}]
    ;; a reference an eve-deftype writes in place into one of this domain's blocks parks what reaches 0 here
    (tally/register-parker! sio (park-fn ds))
    ds))

(defn- park-fn
  "The domain's park!: a block whose count reached 0 queued on the zero queue at the epoch now."
  [{:keys [root-r retire-q]}]
  (fn [off kind]
    (swap! retire-q conj {:off off :kind kind :epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)})))

(defn- flush-zero-queue!
  "Free the zero queue's entries whose epoch no live reader pins
  (eve.tally/flush!). The safe epoch is the retire path's existing
  input — the minimum pinned reader epoch — with ONE added bound
  (RETENTION FLOOR, fix (b), decision 2026-10-27): a journal
  domain's floor epoch, the oldest epoch recovery may still install.
  The journal pins that epoch the way a slow reader pins its read
  epoch: blocks reachable from it are not recycled while its record
  survives. The floor is per-process state set at recovery and
  advanced at every flush (to the previous flush's epoch — the
  FLUSH-ADVANCE rule below, journal-flush!) and at checkpoint, so
  a stale local floor only ever makes this process's flush more
  conservative."
  [{:keys [root-r sio retire-q journal]}]
  (tally/flush! sio retire-q
                (fn [] (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
                (fn [] (let [readers (mmap-min-safe-epoch root-r)
                             floor   (some-> journal :floor deref)]
                         (cond (and readers floor) (min readers floor)
                               floor   floor
                               :else   readers)))))

(defn- claim-slot-as-participant!
  "Claim a worker slot as a write to the root: a lock-only host holds the
   domain with HOLD_JOINING while it claims (it has no slot yet)."
  [root-r]
  (let [lock-only? (not (mem/-atomic-rmw? root-r))]
    (with-root-write* root-r lock-only? HOLD_JOINING
      (fn [] (mmap-claim-slot! root-r)))))

(defn- await-domain-root!
  "A joiner's wait for the domain's creator: the root's magic, written last
   (init-domain-root!). A creator that never finishes is reported once its
   heartbeat timeout has passed, never waited on for ever."
  [root-r base-path]
  (let [t0 (proc/now-ms)]
    (loop [i 0]
      (let [magic (mem/-load-i32 root-r d/ROOT_MAGIC_OFFSET)]
        (cond
          (== magic d/ROOT_MAGIC_V2) nil
          (not (zero? magic))
          (throw (ex-info "mmap-atom: root file has wrong magic (old format?)"
                          {:expected d/ROOT_MAGIC_V2 :actual magic}))
          (> (- (proc/now-ms) t0) d/HEARTBEAT_TIMEOUT_MS)
          (throw (ex-info "mmap-atom: the domain's creator never finished writing its root"
                          {:base-path base-path}))
          :else (do (when (> i 100) (proc/sleep-ms! 1)) (recur (inc i))))))))

;; ---------------------------------------------------------------------------
;; Commit journal (durability spike, variant J)
;;
;; A per-domain append-only file, <base>.journal, beside the .root.
;; Records carry COMMIT METADATA ONLY — never values:
;;   [magic i32][len i32][epoch i32][table-len i32][table bytes][crc32 i32]
;; len counts the bytes after itself (epoch + table-len + table + crc);
;; the crc32 covers the epoch and table bytes. The table is the root
;; region's raw atom-table range (MAX_ATOM_SLOTS x ATOM_SLOT_SIZE — one
;; pointer per atom per epoch), so the newest valid record IS the
;; newest fully journaled epoch: recovery installs its table whole,
;; no folding. A torn record (crash mid-append) fails framing or CRC
;; and is skipped by its length. The journal decides nothing about
;; liveness — the mmap root CAS + epoch bump coordinate running
;; processes exactly as without it; it records which epochs are
;; durable. Appends serialize on the journal file's own lock, held
;; across a flush (append + force), never across the live CAS path.
;; Every host writes the identical byte format (cross-host recovery
;; verified: a Node opener recovers a JVM-written journal; the
;; ClojureCLR and Basilisp seats write records the JVM reader
;; recovers). The file itself — its lock, its record codec, its
;; forces — is the host's adapter, eve.journal-file; everything below
;; is the host-free sequence over it: lock, sweep, append, force.
;; ---------------------------------------------------------------------------

(def ^:dynamic *flush-timing*
  "When bound to an atom, journal-flush! conjs each flush's wall time
  in ms (whole call: lock + slab sweep + append + journal force).
  Bench instrumentation only."
  nil)

(defn- now-ms []
  ;; eve.process/now-ms is the portable wall clock (per-host adapter);
  ;; System/currentTimeMillis exists only on the JVM — the CLR and
  ;; Basilisp seats have no java.lang.System, and journal-note-commit!
  ;; threw there once the batch/window path first ran on them
  ;; (phase 3). The adapter's now-ms is the one clock on every host.
  (proc/now-ms))

(def ^:dynamic *flush-log*
  "When bound to an atom, journal-flush! conjs {:at <wall-ms> :ms
  <duration-ms>} per flush. Bench/workload instrumentation only."
  nil)

(defn- journal-flush!
  "Append one flush record (the live epoch + atom table) and force the
   journal — the commit point of a journal domain. sweep? forces the
   slab set first (J10's deferred sweep; J1 swept before the CAS).
   The sweep is whole-domain: a flush record's table names blocks
   whichever live process wrote them, and the sweep makes them all
   durable before the record can land."
  [ds sweep?]
  (let [journal (:journal ds)
        ;; the epoch the appended record will carry (read again
        ;; inside the lock by jf/journal-record; epochs are
        ;; monotone, so this earlier read never exceeds it)
        floor-epoch (when journal
                      (mem/-load-i32 (:root-r ds) d/ROOT_EPOCH_OFFSET))
        t0 (when (and journal *flush-timing*)
             (now-ms))]
    (when journal
      (jf/with-journal-lock (:file journal)
        (fn []
          (when sweep? (alloc/sync-sio! (:sio ds)))
          (jf/journal-append! (:file journal)
                              (jf/journal-record (:root-r ds)))
          ;; data force: the record bytes and the file size they need
          ;; are durable; timestamps are not metadata recovery reads.
          (jf/journal-force! (:file journal) false))))
    (when journal
      ;; RETENTION FLOOR — FLUSH-ADVANCE (decision 2026-10-27,
      ;; after the sweep reconciliation measured the checkpoint-only
      ;; floor's price): the floor advances at EVERY flush, not only
      ;; at checkpoint. Recovery installs the newest valid record;
      ;; appends are sequential and forced per flush, so a crash can
      ;; tear only the tail append and recovery falls back at most
      ;; one record — protection only needs to cover the two newest
      ;; durable records. So: a first append into a floor-less
      ;; journal pins the floor at that record's epoch (the journal's
      ;; only record is the oldest installable); every later append
      ;; advances the floor to the PREVIOUS append's epoch.
      ;; :last-epoch tracks this process's own appends; records land
      ;; in epoch order under the journal lock, so the record before
      ;; this process's newest append has an epoch >= its own
      ;; previous one — the own-previous epoch never exceeds the
      ;; global second-newest durable record's epoch, and the floor
      ;; stays conservative. (journal-anchor!'s reset of the floor
      ;; to the anchor epoch at checkpoint is a separate mechanism —
      ;; the anchor decides what the journal FILE holds — and is
      ;; unchanged; the max keeps the floor monotone across it.)
      (when (:floor journal)
        (if (nil? @(:floor journal))
          (reset! (:floor journal) floor-epoch)
          (when (and (:last-epoch journal) @(:last-epoch journal))
            (reset! (:floor journal)
                    (max @(:floor journal) @(:last-epoch journal))))))
      (when (:last-epoch journal)
        (reset! (:last-epoch journal) floor-epoch))
      (when (:last-flush journal) (reset! (:last-flush journal) (now-ms)))
      (when *flush-log*
        (swap! *flush-log* conj
               {:at (now-ms)
                :ms (when t0 (- (now-ms) t0))})))
    (when t0
      (swap! *flush-timing* conj (- (now-ms) t0)))))

(defn- journal-note-commit!
  "A journal domain's commit published: J1 (limit 1) flushes now — the
   journal force is the commit point. J10 counts the epoch toward the
   batch; the batch's flush is one sweep + one record + one force.
   With :journal-window-ms set, a commit also flushes when the
   window has elapsed since the last flush (checked on the commit
   path — no timer thread; the count batch remains the backstop)."
  [ds]
  (let [{:keys [limit pending window-ms last-flush] :as journal} (:journal ds)]
    (when journal
      (if (<= limit 1)
        (journal-flush! ds false)
        (let [n (swap! pending inc)]
          (when (or (>= n limit)
                    (and window-ms (>= (- (now-ms) @last-flush) window-ms)))
            (reset! pending 0)
            (journal-flush! ds true)))))))

(defn- domain-live?
  "Any worker slot ACTIVE with a fresh heartbeat: a process holds this
   domain open right now."
  [root-r]
  (loop [i 0]
    (cond (>= i d/MAX_WORKERS) false
          (and (== (mem/-load-i32 root-r (worker-slot-offset i d/OFFSET_WS_STATUS))
                   d/WORKER_STATUS_ACTIVE)
               (not (heartbeat-stale? root-r i))) true
          :else (recur (inc i)))))

(defn- journal-recover!
  "Open-time recovery for a journal domain, run before this process
   claims its worker slot. Live domain: the journal is untouched (a
   joining process reads the live root). Not live: install the newest
   valid record's table + epoch when they differ from the mapped
   root's, force the root file, and compact the journal to that
   record — the torn tail is discarded. A creator truncates the
   journal of a fresh domain."
  [root-r journal create?]
  (when journal
    (jf/with-journal-lock (:file journal)
      (fn []
        (let [h (:file journal)]
          (if create?
            (do (jf/journal-truncate! h) (jf/journal-force! h true))
            (let [{:keys [newest oldest torn?]} (jf/journal-scan h)
                  e-map (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
              ;; RETENTION FLOOR (fix (b)): the oldest epoch
              ;; recovery may still install — the journal's
              ;; oldest surviving record, or the root's own
              ;; epoch when no record survives. Set for live
              ;; joiners too (read-only): their retire flush
              ;; must honor it like anyone's.
              (when (:floor journal)
                (reset! (:floor journal) (or oldest e-map)))
              (when-not (domain-live? root-r)
                (let [install? (and newest (not= (:epoch newest) e-map))]
                  (when install?
                    (mem/-write-bytes! root-r d/ATOM_TABLE_START (:table newest))
                    (mem/-store-i32! root-r d/ROOT_EPOCH_OFFSET (:epoch newest))
                    (mem/sync-region! root-r))
                  (when (or torn? install?)
                    (jf/journal-truncate! h)
                    (when newest
                      (jf/journal-write-at! h (jf/journal-record root-r) 0))
                    (jf/journal-force! h true)))))))))))

(defn- journal-anchor!
  "Checkpoint's tail — RECOVERY FLOOR FIX (a) (decision,
  2026-10-27): checkpoint truncation never empties the journal. The
  newest valid record — the checkpoint epoch's own — is rewritten as
  the journal's sole record (the anchor); everything older goes.
  Recovery's rule is then uniform: whenever a valid record exists,
  install from the journal, never from the landed root — a root that
  landed ahead of an emptied journal was exactly phase 2's
  undefended floor. Also advances the retention floor (fix (b)) to
  the anchor's epoch: monotone, at checkpoint, under the journal
  lock, and nowhere else. A journal with no valid record (a fresh
  domain) is left empty."
  [journal]
  (when journal
    (jf/with-journal-lock (:file journal)
      (fn [] (let [h (:file journal)
                   {:keys [newest]} (jf/journal-scan h)]
               (when newest
                 (jf/journal-truncate! h)
                 (jf/journal-write-at! h (jf/journal-record-of
                                           (:epoch newest) (:table newest)) 0)
                 (jf/journal-force! h true)
                 (when (:floor journal)
                   (reset! (:floor journal) (:epoch newest)))))))))

(defn- journal-close! [journal]
  (when journal
    (jf/journal-close! (:file journal))))

(defn- open-mmap-domain!
  "Create (create? true) or join the mmap domain at base-path. Returns the
   domain state. The process's slabs are this domain's from here on (CLJS
   slabs are module state: one mmap domain per process). A creator writes the
   slabs, then the root, its magic last; a joiner opens the slabs only once
   the magic is there."
  [base-path {:keys [capacities lustre? create?] :as opts}]
  (when create? (proc/ensure-parent-dir! base-path))
  (let [root-r (region/open-mmap-region (str base-path ".root") ROOT_BYTES
                                        {:lustre? lustre? :lockable? true})
        _      (when-not create? (await-domain-root! root-r base-path))
        sio    (alloc/mmap-domain-sio! base-path opts)
        rmap-r (region/open-mmap-region (str base-path ".rmap") READER_MAP_BYTES
                                        {:lustre? lustre?})]
    (when create? (init-domain-root! root-r))
    (let [journal  (when (:journal? opts)
                     {:file    (jf/open-journal (str base-path ".journal"))
                      :limit   (or (:journal-batch opts) 1)
                      :pending (clojure.core/atom 0)
                      ;; RETENTION FLOOR (fix (b)): the epoch the
                      ;; journal pins against reclamation, set at
                      ;; recovery (journal-recover!) and advanced
                      ;; at every flush (journal-flush!, to the
                      ;; previous flush's epoch) and at checkpoint
                      ;; (journal-anchor!, to the anchor epoch).
                      :floor   (clojure.core/atom nil)
                      ;; FLUSH-ADVANCE: the epoch of the newest
                      ;; record THIS process has durably appended —
                      ;; the next flush advances :floor to it.
                      :last-epoch (clojure.core/atom nil)})
          _        (when journal (journal-recover! root-r journal create?))
          journal  (cond-> journal
                     (:journal-window-ms opts)
                     (assoc :window-ms (:journal-window-ms opts)
                            :last-flush (clojure.core/atom (now-ms))))
          slot-idx (claim-slot-as-participant! root-r)
          _        (write-heartbeat! root-r slot-idx)
          hb       (proc/start-timer! 10000 #(write-heartbeat! root-r slot-idx))
          ds       (cond-> (assoc (make-domain-state root-r rmap-r sio base-path slot-idx hb)
                                  ;; commits force before they publish (see swap-attempt!),
                                  ;; unless the opener asked for the group-commit shape;
                                  ;; a journal domain is durable by construction
                                  :durable-commits? (or (:journal? opts)
                                                        (not (false? (:durable? opts)))))
                     journal (assoc :journal journal))]
      (proc/on-process-exit! (fn [] (when-not @(:closed? ds)
                                      (mmap-release-slot! root-r slot-idx))))
      ds)))

(defn- open-heap-domain!
  "An in-process domain: heap slabs (JVM, bb) or the module SABs (CLJS), a
   heap root and reader map. One participant, no heartbeat."
  [opts]
  (let [sio    (alloc/heap-domain-sio! opts)
        root-r (region/make-heap-region ROOT_BYTES)
        rmap-r (region/make-heap-region READER_MAP_BYTES)]
    (init-domain-root! root-r)
    (let [slot-idx (mmap-claim-slot! root-r)]
      (write-heartbeat! root-r slot-idx)
      (make-domain-state root-r rmap-r sio nil slot-idx nil))))

;; ---------------------------------------------------------------------------
;; Epoch pins — per thread, nested, one slot per domain
;; ---------------------------------------------------------------------------
;; Threads of one process share the worker slot: the slot carries the MIN of
;; their pinned epochs. A thread's pins nest (a deref inside a swap keeps the
;; swap's older epoch): depth counts them, the first wins.

(defn- slot-pin! [root-r slot-idx pins]
  (if (empty? pins)
    (mmap-unpin-epoch! root-r slot-idx)
    (mmap-pin-epoch! root-r slot-idx (reduce min (map first (vals pins))))))

(defn- pin-epoch! [{:keys [root-r slot-idx pins pin-lock]} epoch]
  (locking pin-lock
    (let [tid (proc/thread-id)
          p   (swap! pins (fn [m]
                            (if-let [[e depth] (get m tid)]
                              (assoc m tid [(min e epoch) (inc depth)])
                              (assoc m tid [epoch 1]))))]
      (slot-pin! root-r slot-idx p))))

(defn- unpin-epoch! [{:keys [root-r slot-idx pins pin-lock]}]
  (locking pin-lock
    (let [tid (proc/thread-id)
          p   (swap! pins (fn [m]
                            (if-let [[e depth] (get m tid)]
                              (if (> depth 1)
                                (assoc m tid [e (dec depth)])
                                (dissoc m tid))
                              m)))]
      (slot-pin! root-r slot-idx p))))

(defn- refresh-slabs! [{:keys [base-path sio]}]
  (when base-path (alloc/refresh! sio)))

;; ---------------------------------------------------------------------------
;; Root values
;; ---------------------------------------------------------------------------

(defn- live-ptr? [ptr]
  (and (not= ptr alloc/NIL_OFFSET) (not= ptr CLAIMED_SENTINEL)))

(defn- read-root-value
  "The slab-backed value at ptr, read through sio. The caller pins."
  [sio ptr]
  (when (live-ptr? ptr)
    (let [type-id (alloc/read-header-tag sio ptr)]
      (if (== type-id ser/SCALAR_BLOCK_TYPE_ID)
        (alloc/scalar-block->value sio ptr)
        (if-let [ctor (ser/get-header-constructor type-id)]
          (ctor sio ptr)
          (throw (ex-info "mmap-atom: unknown root value type-id"
                          {:type-id type-id :ptr ptr})))))))

(defn- write-plain-collection!
  "A plain collection as a slab value in sio: the host's direct writer when
   one is registered (the JVM codec's HAMT/trie writers), else a builder.
   Returns the header offset, or nil when v is no collection."
  [sio v]
  (let [direct (cond (map? v) (ser/get-coll-writer :map)
                     (set? v) (ser/get-coll-writer :set)
                     (vector? v) (ser/get-coll-writer :vec)
                     (or (list? v) (seq? v)) (ser/get-coll-writer :list)
                     :else nil)]
    (if direct
      (direct sio nil v)
      (some-> (ser/convert-to-eve sio v) d/-root-header-off))))

(defn- write-typed-root!
  "A host typed array (or a heap-backed eve array, its backing array) a swap!
   sets, written into sio as an eve array block; nil for any other value."
  [sio v]
  (cond
    (enc/host-array? v) (arr/write-host-array! sio v)
    (satisfies? d/IBackingArray v) (arr/write-host-array! sio (d/-backing-array v) (d/-backing-subtype v))
    :else nil))

(defn- resolve-new-ptr
  "The slab offset a new root value lives at in sio: NIL for nil, the header
   of a slab-backed value of this domain (its own pointer: shared, not copied), the result of
   writing a plain collection, or a fresh scalar block. A slab-backed value of another domain
   follows the receiving domain's foreign policy (step-2 prototype): :throw (the default, today's
   error) or :copy (materialized under the reader's pin, then written here)."
  ([sio new-val] (resolve-new-ptr sio new-val :throw))
  ([sio new-val foreign-policy]
   (cond
     (nil? new-val)
     alloc/NIL_OFFSET

     (satisfies? d/IEveRoot new-val)
     (let [vs (d/-eve-sio new-val)]
       (if (and vs (not (identical? (alloc/sio-root vs) (alloc/sio-root sio))))
         (case foreign-policy
           :copy (resolve-new-ptr sio (d/eve->clj new-val) :throw)
           (throw (ex-info "swap!: Eve value was allocated in a different slab domain; construct it with the atom's domain sio (eve.atom/atom-sio)"
                           {:value-type (str (type new-val))})))
         (d/-root-header-off new-val)))

     :else
     (or (write-typed-root! sio new-val)
         (write-plain-collection! sio new-val)
         (alloc/value->scalar-block! sio new-val)))))

;; ---------------------------------------------------------------------------
;; Reclamation — how many use a block (eve.tally)
;; ---------------------------------------------------------------------------
;; A committed swap's slot holds the new value and lets the old one go: a
;; fresh block's first holder counts its references once, a block another
;; holder already has costs one increment, and a block whose count reaches 0
;; is parked on the domain's zero queue — freed once no pinned reader can see
;; it, its references dropped in turn. Counts decide what a write frees, so a
;; value two atoms hold (or two keys of one atom) is freed only when the last
;; of them lets it go. The reach readers below serve `seal`, which digests
;; every block a value owns.

(defn- try-flush-retires!
  "The zero queue's flush, throttled: the 256-slot pin scan runs when the last one is FLUSH_INTERVAL_MS old or the
   queue is past FLUSH_QUEUE_THRESHOLD. Only ever delays a free."
  [{:keys [retire-q flush-ts] :as ds}]
  (let [now (proc/now-ms)]
    (when (or (> (- now @flush-ts) FLUSH_INTERVAL_MS)
              (> (count @retire-q) FLUSH_QUEUE_THRESHOLD))
      (reset! flush-ts now)
      (flush-zero-queue! ds))))

;; ---------------------------------------------------------------------------
;; deref / swap!
;; ---------------------------------------------------------------------------

(defn- region-deref
  "Inside a `$` (eve.read-region): the atom's value as the lazy, slab-backed value. Its domain is pinned the first
   time the region reads it and stays pinned until the region exits, so every value read in the region is readable
   until then; the region materializes its result before it lets go."
  [{:keys [root-r sio] :as ds} atom-slot-idx]
  (rr/hold! ds
            (fn []
              (refresh-slabs! ds)
              (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)))
            (fn [] (unpin-epoch! ds)))
  (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET))]
    (refresh-slabs! ds)
    (read-root-value sio ptr)))

(defn- plain-deref
  "The atom's value as plain data (d/eve->clj), read under a pinned epoch.
   Slab-backed wrappers must not escape: the epoch is unpinned on return."
  [{:keys [root-r sio] :as ds} atom-slot-idx]
  (refresh-slabs! ds)
  (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
  (try
    (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET))]
      ;; the root may name blocks another process allocated after growing a
      ;; class past the refresh above: re-check behind the load
      (refresh-slabs! ds)
      (d/eve->clj (read-root-value sio ptr)))
    (finally
      (unpin-epoch! ds))))

#?(:bb nil
   :glj nil
   :clj
   (defn- guard-deref
     "PROTOTYPE (step 2). Inside a guard extent (eve.guard): the atom's root as a lazy value — the slab-backed value
      read-root-value builds, nothing materialized. The domain is pinned the first time the extent chain reads it and
      unpinned when the extent that pinned it closes. The value comes back behind eve.guard's validity proxy; with
      eve.guard/*proxy-same-domain?* false, a read of the extent's own receiving domain comes back raw (the slab
      value itself, pointer-equal to the atom's, with no use-after-close check)."
     [{:keys [root-r sio] :as ds} atom-slot-idx]
     (guard/hold! ds
                  (fn []
                    (refresh-slabs! ds)
                    (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)))
                  (fn [] (unpin-epoch! ds)))
     (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET))
           _   (refresh-slabs! ds)
           v   (read-root-value sio ptr)]
       (if (and (not guard/*proxy-same-domain?*) (identical? ds (:domain guard/*extent*)))
         v
         (guard/wrap v (guard/current-cell))))))

(defn- mmap-deref
  "deref: the atom's value as plain data (plain-deref); inside a `$`, the lazy value (region-deref); inside a guard
   extent (step-2 prototype, JVM), the lazy value behind the guard (guard-deref)."
  [ds atom-slot-idx]
  (cond
    (rr/active?) (region-deref ds atom-slot-idx)
    #?@(:bb [] :glj [] :clj [(guard/active?) (guard-deref ds atom-slot-idx)])
    :else (plain-deref ds atom-slot-idx)))

(defn- swap-attempt!
  "One CAS attempt: read the old root, apply f, write the new value, CAS the
   root pointer. Returns [:ok new-val] or [:retry nil]. Runs under the
   epoch pin for the whole attempt — the old value is lazy, and the commit's
   hold reads the new tree (round 2, 0008). exclusive? is the call
   convention with-domain-write hands the attempt (in the count-pages
   design a plan that raised a count returned [:hold nil] and the write
   ran again under the domain's hold); a descriptor count is a CAS word on
   the descriptor row, safe to raise from any write, so no attempt ever
   needs the hold and there is no [:hold nil] return."
  [{:keys [root-r sio durable-commits? journal] :as ds} ptr-off f args exclusive?]
  (refresh-slabs! ds)
  (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
  (try
    (let [old-ptr (mem/-load-i32 root-r ptr-off)
          _       (refresh-slabs! ds)                      ;; behind the load (0004/0007)
          lsio    (alloc/logging-sio sio)                  ;; this attempt's allocations
          old-val (read-root-value lsio old-ptr)
          new-val (binding [d/*parent-atom* ds
                            d/*domain-sio* lsio]
                    (apply f old-val args))
          ;; written in the attempt's domain too: a codec that allocates a nested collection in the running domain
          ;; (ClojureScript's) logs it here, so a lost CAS frees what the attempt allocated unheld
          new-ptr (binding [d/*domain-sio* lsio]
                    (resolve-new-ptr lsio new-val (some-> (:foreign-policy ds) deref)))]
      (if (== new-ptr old-ptr)
        ;; the same value: nothing to publish, nothing to retire
        (do (alloc/stop-logging! lsio)
            [:ok new-val])
        ;; the slot's hold of new, taken before the CAS publishes it (eve.tally/hold-root!)
        (let [_ (tally/hold-root! sio new-ptr)
              ;; durability (a disk domain with :durable-commits?): everything this
              ;; commit wrote is forced BEFORE the root pointer can publish it, so a
              ;; system crash cannot leave a root pointing at value pages that never
              ;; reached disk. Spike form: the sio's whole slab, bitmap and descriptor
              ;; regions — the upper bound; the written ranges are the production
              ;; refinement. Inside the attempt, so a lost CAS wastes one force and the
              ;; retry writes and forces its own blocks; nothing is held across the retry.
              ;; a journal domain with a batch (J10) defers even this sweep
              ;; to its flush; J1 and the force-ordering form sweep here.
              _ (when (and durable-commits?
                           (or (nil? journal) (<= (:limit journal) 1)))
                  (alloc/sync-sio! sio))
              w (mem/-cas-i32! root-r ptr-off old-ptr new-ptr)]
          (if (== w old-ptr)
            (do (mem/-add-i32! root-r d/ROOT_EPOCH_OFFSET 1)
                ;; and the epoch is made durable before swap! returns: a
                ;; journal domain appends its flush record and forces the
                ;; journal (that force is the commit point; the root file
                ;; is a checkpoint and is not forced per commit) — otherwise
                ;; the root's own range, the epoch word through the slot
                ;; pointer, is forced after the CAS.
                (when durable-commits?
                  (if journal
                    (journal-note-commit! ds)
                    (mem/sync-region! root-r
                                      (min ptr-off d/ROOT_EPOCH_OFFSET)
                                      (+ 4 (- (max ptr-off d/ROOT_EPOCH_OFFSET)
                                              (min ptr-off d/ROOT_EPOCH_OFFSET))))))
                ;; the slot lets old go: a block at 0 is parked at the epoch now
                (tally/drop-root! sio old-ptr (park-fn ds))
                (alloc/stop-logging! lsio)
                [:ok new-val])
            (let [offs (alloc/logged-allocs lsio)]
              ;; never published: the hold goes back (new's blocks reach 0 and are freed by the flush, what they
              ;; share with other values keeps its count), and every other block this attempt allocated that no
              ;; holder took is freed now (a value the attempt stored into another atom is that atom's)
              ;; (read before the log stops: a stopped log reads empty)
              (alloc/stop-logging! lsio)
              (tally/drop-root! sio new-ptr (park-fn ds))
              (tally/free-unheld! sio offs)
              [:retry nil])))))
    (finally
      (unpin-epoch! ds))))

(defn- mmap-swap!
  "CAS-loop swap. Accepts any slab-backed value of this domain, a plain
   collection, a scalar or nil."
  [ds atom-slot-idx f args]
  (let [ptr-off (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET)]
    (loop [attempt 0]
      (when (>= attempt d/MAX_SWAP_RETRIES)
        (throw (ex-info "mmap-atom swap!: max retries exceeded" {:attempts attempt})))
      (let [[tag result] (with-domain-write ds false
                           (fn [exclusive?]
                             (let [r (swap-attempt! ds ptr-off f args exclusive?)]
                               (when (= :ok (first r))
                                 (try-flush-retires! ds))
                               r)))]
        (case tag
          :ok result
          :retry (do (cas-backoff! attempt)
                     (recur (inc attempt))))))))

(defn- mmap-compare-and-set!
  "Set the atom to new-val iff its value equals old-val. One CAS loop: f
   returns the current value unchanged when it does not match."
  [ds atom-slot-idx old-val new-val]
  (let [set? (volatile! false)]
    (mmap-swap! ds atom-slot-idx
                (fn [cur]
                  ;; f runs once per attempt; only the attempt whose CAS
                  ;; commits counts, so every attempt overwrites the flag (a
                  ;; matching attempt that lost its CAS used to leave it true)
                  (let [hit? (= (d/eve->clj cur) old-val)]
                    (vreset! set? hit?)
                    (if hit? new-val cur)))
                [])
    @set?))

(defprotocol IEveAtom
  "An eve atom's (or atom domain's) parts: its domain's state and its slot."
  (-domain-state [a])
  (-atom-slot-idx [a]))

(defprotocol IEveAtomDomain
  "An eve atom domain's registry: a named atom's slot by its name."
  (-registry-cache [d]))

(defn- atom-domain-state [a]
  (-domain-state a))

(defn $*
  "`$`'s region (eve.alpha/$ expands to it): (f) with every eve atom deref'd inside answering its lazy, slab-backed
   value under a pin held until the outermost region exits; f's result materialized (d/eve->clj, lazy seqs
   realized) before the pins go. A region inside another joins it."
  [f]
  (rr/run f d/eve->clj))

;; PROTOTYPE (step 2)
(defn set-foreign-policy!
  "What swap! on a's domain does with a slab-backed value of another domain: :throw (default) or :copy."
  [a policy]
  {:pre [(#{:throw :copy} policy)]}
  (reset! (:foreign-policy (atom-domain-state a)) policy))

(defn read-pinned
  "Bounded slab-backed access to one atom: (f value & args) with the atom's
   value as the lazy slab-backed Eve value, read under this thread's pinned
   epoch, which is unpinned when f returns — f's result must not keep the
   value. eve's own reads use it; a program reads with eve.alpha/$."
  [a f & args]
  (let [{:keys [root-r sio] :as ds} (atom-domain-state a)
        slot (-atom-slot-idx a)]
    (refresh-slabs! ds)
    (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
    (try
      (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset slot d/ATOM_SLOT_PTR_OFFSET))]
        (refresh-slabs! ds)
        (apply f (read-root-value sio ptr) args))
      (finally
        (unpin-epoch! ds)))))

;; ---------------------------------------------------------------------------
;; MmapAtom / MmapAtomDomain — the host's atom interfaces are the one
;; difference (cljs.core protocols vs clojure.lang interfaces); the bodies
;; are the same calls.
;; ---------------------------------------------------------------------------

;; One definition for every host, written with the JVM's names: each host's
;; types adapter realizes it (ClojureScript's ISwap/IReset, sci's own IAtom,
;; basilisp's duck-typed swap/reset, clojerl's through eve.host).
(edt/deftype MmapAtom [domain-state atom-slot-idx]
  clojure.lang.IDeref
  (deref [_] (mmap-deref domain-state atom-slot-idx))
  clojure.lang.IAtom
  (swap [_ f] (mmap-swap! domain-state atom-slot-idx f []))
  (swap [_ f a] (mmap-swap! domain-state atom-slot-idx f [a]))
  (swap [_ f a b] (mmap-swap! domain-state atom-slot-idx f [a b]))
  (swap [_ f a b xs] (mmap-swap! domain-state atom-slot-idx f (concat [a b] xs)))
  (reset [_ v] (mmap-swap! domain-state atom-slot-idx (constantly v) []))
  (compareAndSet [_ old-val new-val]
    (mmap-compare-and-set! domain-state atom-slot-idx old-val new-val))
  IEveAtom
  (-domain-state [_] domain-state)
  (-atom-slot-idx [_] atom-slot-idx)
  ;; (a printer reaches the instance's fields through it: on a JVM host it
  ;; is the type's print-method, outside the type)
  IPrintWithWriter
  (-pr-writer [this writer _opts]
    (let [i (-atom-slot-idx this)]
      (ecore/-write writer (str "#eve/shared-atom {:id " i " :idx " i "}")))))

(defn compare-and-set!*
  "compare-and-set! for an Eve atom on every host (CLJS's core
   compare-and-set! is for its own Atom type)."
  [a old-val new-val]
  (mmap-compare-and-set! (atom-domain-state a)
                         (-atom-slot-idx a)
                         old-val new-val))

;; SharedAtom compat alias (tests reference a/SharedAtom)
(def SharedAtom MmapAtom)

(edt/deftype MmapAtomDomain [domain-state registry-cache]
  clojure.lang.IDeref
  (deref [_] (mmap-deref domain-state 0))
  ;; the domain's own value (slot 0) is an atom as well
  clojure.lang.IAtom
  (swap [_ f] (mmap-swap! domain-state 0 f []))
  (swap [_ f a] (mmap-swap! domain-state 0 f [a]))
  (swap [_ f a b] (mmap-swap! domain-state 0 f [a b]))
  (swap [_ f a b xs] (mmap-swap! domain-state 0 f (concat [a b] xs)))
  (reset [_ v] (mmap-swap! domain-state 0 (constantly v) []))
  (compareAndSet [_ old-val new-val]
    (mmap-compare-and-set! domain-state 0 old-val new-val))
  ;; (:key domain): on babashka, whose deftype takes ILookup only on a map
  ;; type, it is domain-lookup
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k not-found]
    (if-let [slot-idx (get @registry-cache (str (namespace k) "/" (name k)))]
      (MmapAtom. domain-state slot-idx)
      not-found))
  IEveAtom
  (-domain-state [_] domain-state)
  (-atom-slot-idx [_] 0)
  IEveAtomDomain
  (-registry-cache [_] registry-cache))

(defn domain-lookup
  "Look up an atom in a domain by keyword."
  ([domain k] (domain-lookup domain k nil))
  ([domain k not-found]
   (let [registry-cache (-registry-cache domain)]
     (if-let [slot-idx (get @registry-cache (str (namespace k) "/" (name k)))]
       (MmapAtom. (atom-domain-state domain) slot-idx)
       not-found))))

;; ---------------------------------------------------------------------------
;; Domains
;; ---------------------------------------------------------------------------

(def ^:private domain-cache
  "Opened mmap domains by base path."
  (clojure.core/atom {}))

(defn persistent-atom-domain
  "Open or create the mmap domain at base-path (joins when its files exist).
   Cached by path: the same path returns the same domain until
   close-atom-domain!. Returns an MmapAtomDomain. The process whose exclusive
   create of the .root file succeeds creates the domain; every other joins —
   never a check of the file's existence then a create, which two processes
   opening a fresh domain together both pass (each initialised the domain
   over the other's writes)."
  [base-path & {:keys [capacities lustre? durable? journal? journal-batch journal-window-ms] :or {capacities {} lustre? false durable? true}}]
  (or (get @domain-cache base-path)
      (let [_  (proc/ensure-parent-dir! base-path)
            ds (open-mmap-domain! base-path {:capacities capacities
                                             :lustre? lustre?
                                             :durable? durable?
                                             :journal? journal?
                                             :journal-batch journal-batch
                                             :journal-window-ms journal-window-ms
                                             :create? (proc/create-exclusive! (str base-path ".root"))})
            d  (MmapAtomDomain. ds (clojure.core/atom {}))]
        (swap! domain-cache assoc base-path d)
        d)))

(defn join-atom-domain
  "Join an existing mmap domain at base-path (prefer persistent-atom-domain)."
  [base-path]
  (persistent-atom-domain base-path))

(defn- sync-domain!
  "Force a domain's whole mapped set — slabs, bitmaps, descriptor tables,
   reader map, root — to durable storage (eve.mem/sync-region!: a region
   that cannot force answers nil, so an in-process domain is a no-op)."
  [{:keys [root-r rmap-r sio]}]
  (alloc/sync-sio! sio)
  (mem/sync-region! rmap-r)
  (mem/sync-region! root-r))

(defn- journal-checkpoint!
  "Fold the journal into the root file: flush any pending batch,
   force the whole domain (the root file is now the checkpoint),
   then reduce the journal to its anchor record (never empty —
   journal-anchor!). Without a journal this is sync-domain!."
  [ds]
  (let [journal (:journal ds)]
    (when (and journal (pos? @(:pending journal)))
      (reset! (:pending journal) 0)
      (journal-flush! ds true))
    (sync-domain! ds)
    (when journal (journal-anchor! journal))))

(defn- release-domain! [ds]
  (let [{:keys [root-r slot-idx heartbeat closed? base-path]} ds]
    (when (and root-r slot-idx (not @closed?))
      (reset! closed? true)
      ;; a disk domain is forced whole before it is let go: nothing a
      ;; commit wrote is left riding the page cache at close; a journal
      ;; domain folds its journal into the root file as it closes.
      (when base-path (journal-checkpoint! ds))
      (journal-close! (:journal ds))
      (proc/stop-timer! heartbeat)
      (mmap-release-slot! root-r slot-idx)
      (tally/unregister-parker! (:sio ds))
      ;; the slabs back to what this mmap domain replaced (ClojureScript's
      ;; module slabs)
      (alloc/release-domain-sio! (:sio ds) (some? base-path)))))

(defn close-atom-domain!
  "Release the worker slot, stop the heartbeat, forget the domain. Atoms of
   the domain are invalid after this."
  [d]
  (let [{:keys [base-path] :as ds} (atom-domain-state d)]
    (release-domain! ds)
    (when base-path
      (swap! domain-cache dissoc base-path))))

(defn close!
  "Release the worker slot of the domain this atom belongs to. Idempotent."
  [a]
  (release-domain! (atom-domain-state a)))

(defn sync!
  "Force everything this atom's (or domain's) domain has written to
   durable storage: when sync! returns, every commit so far survives a
   system crash or power loss, not only a process crash. Commits on a
   disk domain are already forced one by one as they publish
   (persistent-atom-domain's :durable?, default true); sync! is the
   explicit barrier for a domain opened :durable? false — the
   group-commit shape, one force for many commits. Returns x."
  [x]
  (journal-checkpoint! (atom-domain-state x))
  x)

;; ---------------------------------------------------------------------------
;; Atom names — FNV-1a over the name's UTF-16 units & 0xFF (the registry
;; hash every host writes; FORMAT-DECISIONS.md §names)
;; ---------------------------------------------------------------------------

(defn- fnv-1a-hash
  [s]
  (reduce (fn [h u] (i32/imul (bit-xor h (bit-and u 0xFF)) 0x01000193))
          (i32/i32 0x811c9dc5)
          (enc/utf16-units s)))

(defn- keyword-str [k]
  (str (namespace k) "/" (name k)))

(defn- claim-atom-slot!
  "CAS-claim the first free atom slot (from 1; 0 is the registry) with the
   CLAIMED sentinel, write the name hash, return the index."
  [root-r name-hash]
  ;; FORMAT-DECISIONS §12 (2026-10-24): the capacity is root word 20 (grown by CAS by a participant that can extend the
  ;; file: eve-disk), the scan starts at the hint (word 36), and a slot whose ptr word is 0 (bytes a grown file gained)
  ;; is free as NIL is. This participant maps the root at ROOT_BYTES: a capacity past that is a domain grown elsewhere
  ;; — it claims what its region holds and says so past it (the remap is test/coop-atom's unit).
  (let [cap (mem/-load-i32 root-r d/ROOT_ATOM_TABLE_CAPACITY)
        cap (if (pos? cap) cap d/MAX_ATOM_SLOTS)
        reach (min cap d/MAX_ATOM_SLOTS)
        hint (mem/-load-i32 root-r d/ROOT_SLOT_HINT_OFFSET)]
    (loop [i (max 1 hint)]
      (when (>= i reach)
        (throw (ex-info (if (> cap d/MAX_ATOM_SLOTS)
                          "mmap-atom: the domain's atom slots pass this participant's root region (FORMAT-DECISIONS §12)"
                          "mmap-atom: no free atom slot")
                        {:capacity cap :region-slots d/MAX_ATOM_SLOTS})))
      (let [ptr-off (d/atom-slot-offset i d/ATOM_SLOT_PTR_OFFSET)
            cur-ptr (mem/-load-i32 root-r ptr-off)]
        (if (or (== cur-ptr alloc/NIL_OFFSET) (== cur-ptr 0))
          (let [w (mem/-cas-i32! root-r ptr-off cur-ptr CLAIMED_SENTINEL)]
            (if (== w cur-ptr)
              (do (mem/-store-i32! root-r (d/atom-slot-offset i d/ATOM_SLOT_HASH_OFFSET) name-hash)
                  (mem/-store-i32! root-r d/ROOT_SLOT_HINT_OFFSET (inc i))
                  i)
              (recur (inc i))))
          (recur (inc i)))))))

(defn- register-name!
  "kw-str -> slot-idx in the registry unless the name is there already: one
   swap of the registry, so of processes creating one name at once exactly one
   registers it (each assoc'ing its own slot over the others' gave each its
   own atom). The slot the name has, read under the swap's pin."
  [ds kw-str slot-idx]
  (let [got (volatile! nil)]
    (mmap-swap! ds 0 (fn [reg]
                       (let [reg  (or reg {})
                             have (ecore/get reg kw-str)]
                         ;; every attempt overwrites: the committed one's counts
                         (vreset! got (if (some? have) have slot-idx))
                         (if (some? have) reg (ecore/assoc reg kw-str slot-idx))))
                [])
    @got))

(defn- read-registry [ds]
  (plain-deref ds 0))

(defn- create-mmap-atom!
  "Create the atom named kw-str in a domain, or open the one another process
   created under that name first: claim a slot, write initial-val into it
   while no other process can name it, then register the name
   (register-name!). A process that lost frees its slot — the value retired as
   any swap retires it — and opens the winner's, whose value it leaves alone.
   Returns the MmapAtom."
  [domain kw-str initial-val]
  (let [ds (atom-domain-state domain)
        root-r (:root-r ds)
        mine (with-domain-write ds false
               (fn [_] (claim-atom-slot! root-r (fnv-1a-hash kw-str))))]
    (when (some? initial-val)
      (mmap-swap! ds mine (constantly initial-val) []))
    (let [slot (register-name! ds kw-str mine)]
      (when-not (== slot mine)
        ;; nil is the NIL root pointer: the slot is free again
        (mmap-swap! ds mine (constantly nil) []))
      (swap! (-registry-cache domain) assoc kw-str slot)
      (MmapAtom. ds slot))))

(defn lookup-or-create-mmap-atom!
  "The atom named kw-str in domain, created with initial-val when absent."
  [domain kw-str initial-val]
  (let [cache (-registry-cache domain)
        ds (atom-domain-state domain)]
    (if-let [cached-idx (get @cache kw-str)]
      (MmapAtom. ds cached-idx)
      (let [reg (read-registry ds)]
        (if-let [slot-idx (get reg kw-str)]
          (do (swap! cache assoc kw-str slot-idx)
              (MmapAtom. ds slot-idx))
          (create-mmap-atom! domain kw-str initial-val))))))

;; ---------------------------------------------------------------------------
;; Meta-atoms — an atom's coordination state kept beside it (operator
;; direction, 2026-10-19): a second atom of the same domain, registered in
;; the domain's registry under a reserved name made from the atom's own, read
;; and swapped as any atom is. Its traffic never touches the atom's own slot:
;; the atom's value, its root pointer (its version) and its registry entry are
;; what they were. Neither the atom's format nor its equality changes — the
;; pairing is a name.
;; ---------------------------------------------------------------------------

(def ^:const META_ATOM_PREFIX
  "The reserved prefix of a meta-atom's registry name: the meta-atom of the
   atom registered as n is registered as (str META_ATOM_PREFIX n)."
  "eve.meta/")

(defn registered-name
  "The name eve atom a is registered under in its domain's registry, or nil
   (the domain itself, slot 0, has none)."
  [a]
  (let [slot (-atom-slot-idx a)]
    (when-not (zero? slot)
      (some (fn [[k v]] (when (== v slot) k)) (read-registry (atom-domain-state a))))))

(defn meta-atom
  "The meta-atom of eve atom a: the atom of a's domain registered as
   (str META_ATOM_PREFIX (registered-name a)), made holding initial-value (nil by
   default) the first time any process asks for it — of processes asking at
   once exactly one makes it (create-mmap-atom!)."
  ([a] (meta-atom a nil))
  ([a initial-value]
   (let [nm (or (registered-name a)
                (throw (ex-info "eve.atom/meta-atom: the atom has no registered name" {:slot (-atom-slot-idx a)})))]
     (lookup-or-create-mmap-atom! (MmapAtomDomain. (atom-domain-state a) (clojure.core/atom {}))
                                  (str META_ATOM_PREFIX nm) initial-value))))

(defn version
  "[domain-epoch root-pointer] of atom a, read epoch first: equal readings name one value of a — a commit to any atom
   of the domain moves the epoch after its pointer moves, and a pointer comes back only after the commit that moved
   it away has moved the epoch (its block is freed past that epoch). Unequal readings say nothing (a commit elsewhere
   in the domain, or the same value written again)."
  [a]
  (let [{:keys [root-r]} (atom-domain-state a)
        e (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
    [e (mem/-load-i32 root-r (d/atom-slot-offset (-atom-slot-idx a) d/ATOM_SLOT_PTR_OFFSET))]))

(defn- fnv-fold [h x] (i32/imul (bit-xor h (i32/i32 x)) 0x01000193))

(defn seal
  "Atom a's current version as it is stored, read under a pin: its slot's two
   words — the root pointer (every committed swap writes a new one) and its
   name's hash — and every block its value owns, offset and bytes, folded in
   offset order into one FNV-1a digest (a fixed-size class's whole block, an
   overflow block's first 32 bytes). Equal seals are one version, byte for
   byte: what a meta-atom's traffic leaves of its atom."
  [a]
  (let [{:keys [root-r sio] :as ds} (atom-domain-state a)
        slot (-atom-slot-idx a)]
    (refresh-slabs! ds)
    (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
    (try
      (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset slot d/ATOM_SLOT_PTR_OFFSET))
            nh (mem/-load-i32 root-r (d/atom-slot-offset slot d/ATOM_SLOT_HASH_OFFSET))
            _ (refresh-slabs! ds)
            offs (sort (distinct (when (live-ptr? ptr) (retire/reach sio ptr))))
            digest (reduce (fn [h off]
                             (let [cls (alloc/decode-class-idx off)
                                   ^bytes bs (slab/read-bytes sio off 0 (if (< cls 6) (d/slab-size cls) 32))]
                               (reduce (fn [h i] (fnv-fold h (bit-and (aget bs i) 0xFF)))
                                       (fnv-fold h off)
                                       (range (alength bs)))))
                           (i32/i32 0x811c9dc5)
                           offs)]
        {:ptr ptr :name-hash nh :blocks (count offs) :digest digest})
      (finally
        (unpin-epoch! ds)))))

;; ---------------------------------------------------------------------------
;; Argument parsing
;; ---------------------------------------------------------------------------

(defn- parse-persistent-atom-args
  "(persistent-atom value) · (persistent-atom ::ns/kw value) ·
   (persistent-atom {:id ::ns/kw} value) · (persistent-atom nil value)"
  [args]
  (if (== 1 (count args))
    {:value (first args) :opts {}}
    (let [fst (first args)
          snd (second args)]
      (cond
        (and (keyword? fst) (namespace fst)) {:value snd :opts {:id fst}}
        (map? fst) {:value snd :opts fst}
        (nil? fst) {:value snd :opts {}}
        :else {:value fst :opts {}}))))

;; ---------------------------------------------------------------------------
;; Default domain
;; ---------------------------------------------------------------------------

(def ^:dynamic *global-persistent-atom-domain*
  "The default persistent atom domain; ./eve/ is opened lazily when unset."
  nil)

(defn- resolve-domain
  ":persistent as a path opens that domain; :persistent true (or any
   non-path) is the default global domain."
  [opts]
  (let [lustre? (:lustre? opts false)
        path (:persistent opts)]
    (if (string? path)
      (persistent-atom-domain path :lustre? lustre?)
      (or *global-persistent-atom-domain*
          (persistent-atom-domain "./eve/" :lustre? lustre?)))))

(def ^:private anon-counter
  (clojure.core/atom 0))

(defn- atom-name [id]
  (if id
    (str (namespace id) "/" (name id))
    (str "__anon__/" (swap! anon-counter inc))))

(defn persistent-atom
  "Create or look up a named persistent atom in an mmap domain.
     (persistent-atom ::counter 0)
     (persistent-atom {:id ::counter :persistent \"./my-db\"} 0)
     (persistent-atom 0)
   Returns an MmapAtom."
  [& args]
  (let [{:keys [value opts]} (parse-persistent-atom-args args)
        domain (resolve-domain opts)]
    (lookup-or-create-mmap-atom! domain (atom-name (:id opts)) value)))

;; ---------------------------------------------------------------------------
;; In-process atoms — heap slabs (JVM, bb) or the module SABs (CLJS)
;; ---------------------------------------------------------------------------

(def ^:private heap-domain-cache
  "In-process domains by id (nil = the default)."
  (clojure.core/atom {}))

(defn- resolve-heap-domain [id]
  (or (get @heap-domain-cache id)
      (let [d (MmapAtomDomain. (open-heap-domain! {}) (clojure.core/atom {}))]
        (swap! heap-domain-cache assoc id d)
        d)))

(defn atom
  "Create or look up an atom.
     (atom ::counter 0)                            in-process
     (atom {:id ::counter} 0)                      in-process
     (atom {:id ::counter :persistent \"./db\"} 0)  mmap-backed (on disk)
     (atom 0)                                      anonymous, in-process"
  [& args]
  (let [{:keys [value opts]} (parse-persistent-atom-args args)]
    (if (:persistent opts)
      (apply persistent-atom args)
      (lookup-or-create-mmap-atom! (resolve-heap-domain (:id opts)) (atom-name (:id opts)) value))))

(defn atom-sio
  "The ISlabIO of an Eve atom's domain: construct values that will be stored
   in the atom with it, so they allocate in the atom's own domain:
     (swap! ea (fn [_] (ds/dataset (atom-sio ea) {...})))
   Accepts an MmapAtom or MmapAtomDomain."
  [atm]
  (:sio (atom-domain-state atm)))

(defn shared-atom?
  "True for an Eve atom (MmapAtom or MmapAtomDomain)."
  [obj]
  (or (instance? MmapAtom obj) (instance? MmapAtomDomain obj)))

(def ^:dynamic *global-atom-instance*
  "The global atom instance where a caller binds it (or, on ClojureScript,
   set!s it); global-domain answers it, else the domain atom-domain made
   last."
  nil)

;; atom-domain's last domain: a var's root is set only where a host allows
;; it (ClojureScript's set!; the JVM refuses set! outside a binding), an
;; atom's value everywhere
(defonce ^:private last-atom-domain (clojure.core/atom nil))

(defn global-domain
  "The global atom domain: *global-atom-instance* where bound, else the
   domain atom-domain made last (nil before one)."
  []
  (or *global-atom-instance* @last-atom-domain))

(defn- create-atom-domain! [domain initial-value]
  (let [kw-str "__atom-domain__/root"
        _a (lookup-or-create-mmap-atom! domain kw-str initial-value)]
    (reset! last-atom-domain domain)
    domain))

(defn atom-domain
  "A fresh in-process atom domain holding initial-value. Returns an
   MmapAtomDomain."
  [initial-value & {:as _opts}]
  (let [ds (open-heap-domain! {})
        domain (MmapAtomDomain. ds (clojure.core/atom {}))]
    (alloc/init-domain-views!)
    (mmap-swap! ds 0 (constantly initial-value) [])
    (create-atom-domain! domain initial-value)
    domain))

;; ---------------------------------------------------------------------------
;; The epoch-GC surface the eve.shared-atom tests call (epoch_gc_test): worker
;; slots, heartbeats, epochs and a retire registry over a domain-state map
;; (eve.sab has ClojureScript's SAB and module-slab calls of that surface).
;; ---------------------------------------------------------------------------

(defn register-worker!
  [env _worker-id]
  (let [root-r (:root-r env)
        slot (mmap-claim-slot! root-r)]
    (write-heartbeat! root-r slot)
    slot))

(defn unregister-worker! [env slot-idx]
  (mmap-release-slot! (:root-r env) slot-idx))

(defn update-heartbeat! [env slot-idx]
  (write-heartbeat! (:root-r env) slot-idx))

(defn check-worker-liveness [env slot-idx]
  (not (heartbeat-stale? (:root-r env) slot-idx)))

(defn get-current-epoch [env]
  (mem/-load-i32 (:root-r env) d/ROOT_EPOCH_OFFSET))

(defn increment-epoch! [env]
  (inc (mem/-add-i32! (:root-r env) d/ROOT_EPOCH_OFFSET 1)))

(defn begin-read! [env slot-idx]
  (let [root-r (:root-r env)
        epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
    (mmap-pin-epoch! root-r slot-idx epoch)
    epoch))

(defn end-read-epoch! [env slot-idx]
  (mmap-unpin-epoch! (:root-r env) slot-idx))

(defn with-read-epoch [env slot-idx f]
  (let [epoch (begin-read! env slot-idx)]
    (try (f epoch)
         (finally (end-read-epoch! env slot-idx)))))

(defn get-min-active-epoch [env]
  (mmap-min-safe-epoch (:root-r env)))

(def ^:private retired-blocks-registry
  (clojure.core/atom {}))

(defn retire-block!
  [env descriptor-idx]
  (let [epoch (get-current-epoch env)]
    (swap! retired-blocks-registry assoc descriptor-idx
           {:epoch epoch :slab-offset (:offset (get @retired-blocks-registry descriptor-idx))})
    true))

(defn try-free-retired!
  [env descriptor-idx]
  (if-let [entry (get @retired-blocks-registry descriptor-idx)]
    (let [min-epoch (get-min-active-epoch env)
          retired-epoch (:epoch entry)]
      (if (or (nil? min-epoch) (> min-epoch retired-epoch))
        (do (swap! retired-blocks-registry dissoc descriptor-idx)
            :freed)
        :has-readers))
    :not-retired))

(defn sweep-retired-blocks!
  [env]
  (let [min-epoch (get-min-active-epoch env)
        entries @retired-blocks-registry
        freeable (filter (fn [[_ {:keys [epoch]}]]
                           (or (nil? min-epoch) (> min-epoch epoch)))
                         entries)]
    (doseq [[desc-idx _] freeable]
      (swap! retired-blocks-registry dissoc desc-idx))
    (count freeable)))

(defn mark-stale-workers!
  [env]
  (let [root-r (:root-r env)]
    (loop [i 0 count 0]
      (if (>= i d/MAX_WORKERS)
        count
        (let [status (mem/-load-i32 root-r (worker-slot-offset i d/OFFSET_WS_STATUS))]
          (if (and (== status d/WORKER_STATUS_ACTIVE)
                   (heartbeat-stale? root-r i))
            (recur (inc i) (inc count))
            (recur (inc i) count)))))))
