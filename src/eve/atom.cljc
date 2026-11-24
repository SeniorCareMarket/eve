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
                            volatile! vswap! locking transient persistent! conj! bytes get-validator])
  (:require
   ;; jank's clojure.core has none of these. eve.host-completion interns
   ;; them, but this namespace is created (refer-clojure) before that
   ;; require loads, so the interns are invisible here — later requires
   ;; such as eve.mem see them. Refer the completion's names so a module
   ;; that requires eve.atom first (the #418 / #461 plain jank probe)
   ;; can analyze write-typed-root!, resolve-new-ptr, and IEveAtom.
   #?@(:jank [[eve.host-completion :refer [defprotocol deftype defrecord reify
                                           extend-type extend-protocol extend
                                           satisfies? extends? halt-when]]])
   [eve.host :refer [aget alength long class volatile!
                     vswap! locking transient persistent! conj! bytes token]]
   [eve.encoding :as enc]
   ;; Load order: host-deftype's expansion ends in a top-level
   ;; eve.type-readers/register! call, which needs the registry first
   ;; in the RUNTIME graph (a macro ns's require is macro-side only).
   ;; Guard: eve.deftype-guard-test.
   [eve.type-readers]
   [eve.int32 :as i32]
   [eve.process :as proc]
   [eve.jlog :as jlog]
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
   #?(:jolt [jolt.scheme :as scheme])
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
   [eve.deftype-proto.macros :as eve]))

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
;; ... counted in commits too: the queue passes FLUSH_QUEUE_THRESHOLD on nearly every commit (a swap retires a
;; path's nodes), so the node count alone never held the scan back (REPORT.md 3.6 #1). The scan runs when the last
;; is FLUSH_INTERVAL_MS old, FLUSH_COMMIT_THRESHOLD commits ago, or the queue is past FLUSH_QUEUE_CAP nodes.
(def ^:const FLUSH_COMMIT_THRESHOLD 16)
(def ^:const FLUSH_QUEUE_CAP 1024)

;; CAS retry backoff — jittered exponential backoff after the third failure.
(def ^:const BACKOFF_CAP_MS 8)

(def ^:const DEFAULT_ATOM_SLOT 1) ;; slot 0 = registry, slot 1 = default atom
(def ^:const CLAIMED_SENTINEL -2) ;; "claimed but no value" slot marker

;; The exclusive hold a joining lock-only participant takes before it has a
;; slot (its value in ROOT_HOLD); cleared by a waiter after HEARTBEAT_TIMEOUT_MS.
(def ^:const HOLD_JOINING (inc d/MAX_WORKERS))

;; The recovery hold (eve-mmap-journal-plan §4.4): a jlog domain's opener
;; recovering it, or its creator laying the log down, before it has a slot —
;; no participant writes and no opener recovers or claims a slot while it is
;; set; cleared by a waiter after HEARTBEAT_TIMEOUT_MS, as HOLD_JOINING is.
(def ^:const HOLD_RECOVERING (+ d/MAX_WORKERS 2))

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

(defn- mmap-min-safe-epoch-loop
  "mmap-min-safe-epoch's loop: two -load-i32 per slot."
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

(defn- mmap-min-safe-epoch
  "The minimum CURRENT_EPOCH among live, pinned slots; nil when none is
   pinned (every retired entry is reclaimable)."
  [root-r]
  #?(:jolt
     ;; jolt: the scan is eve.region's fused loop (one Scheme procedure: the same loads in the same order); each slot
     ;; it stops at takes the heartbeat check as the loop does. nil (no fused scan for this region): the loop.
     (let [scan (fn [i] (region/scan-pinned-slot root-r d/ROOT_WORKER_REGISTRY_START i d/MAX_WORKERS d/WORKER_SLOT_SIZE
                                                 d/OFFSET_WS_STATUS d/OFFSET_WS_CURRENT_EPOCH d/WORKER_STATUS_ACTIVE))
           hit0 (scan 0)]
       (if (nil? hit0)
         (mmap-min-safe-epoch-loop root-r)
         (loop [hit hit0 result nil]
           (if (= :none hit)
             result
             (let [[j epoch] hit
                   result (if (heartbeat-stale? root-r j) result (if (nil? result) epoch (min result epoch)))]
               (recur (scan (inc j)) result))))))
     :default (mmap-min-safe-epoch-loop root-r)))

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
   or recovering hold (no slot yet) that a waiter has watched for that long."
  [root-r hold waited-ms]
  (if (or (== hold HOLD_JOINING) (== hold HOLD_RECOVERING))
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
  [ds hold? f]
  (let [root-r (:root-r ds) lock-only? (:lock-only? ds) slot-idx (:slot-idx ds) holder (:holder ds)]
    (let [tid (proc/thread-id)
          held (fn [] (reset! holder tid) (try (f true) (finally (reset! holder nil))))]
      (cond
        (= @holder tid) (f true)
        lock-only? (with-exclusive-hold* root-r (inc slot-idx) held)
        hold? (with-held-write* root-r (inc slot-idx) held)
        :else (with-root-write* root-r false (inc slot-idx) (fn [] (f false)))))))

;; ---------------------------------------------------------------------------
;; Domain root initialization
;; ---------------------------------------------------------------------------

(defn init-domain-root!
  "Write the V2 domain root header into root-r (any IMemRegion): epoch,
   worker registry, atom table, the hold words, and the magic last — a
   process joining a domain while its creator writes it reads the root once
   the magic is there (await-domain-root!). Slab allocation is the caller's
   job. HOLD (default 0) is the hold word's first value: a jlog domain's
   creator writes HOLD_RECOVERING, so no joiner recovers the log or claims a
   slot before the creator has laid the log down and holds its own slot
   (eve-mmap-journal-plan §4.4 step 5). Returns root-r."
  ([root-r] (init-domain-root! root-r 0))
  ([root-r hold]
   (mem/-store-i32! root-r d/ROOT_ATOM_PTR_OFFSET 0) ;; now: atom slot count
   (mem/-store-i32! root-r d/ROOT_EPOCH_OFFSET 1)
   (mem/-store-i32! root-r d/ROOT_WORKER_REG_OFFSET d/ROOT_WORKER_REGISTRY_START)
   (mem/-store-i32! root-r d/ROOT_ATOM_TABLE_OFFSET d/ATOM_TABLE_START)
   (mem/-store-i32! root-r d/ROOT_ATOM_TABLE_CAPACITY d/MAX_ATOM_SLOTS)
   (mem/-store-i32! root-r d/ROOT_HOLD_OFFSET hold)
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
   root-r))

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
;;   :watches         (atom {slot {key cb}})  per-atom watch registries (trunk watch surface)
;;   :validators      (atom {slot fn})        per-atom validators (trunk watch surface)

(declare park-fn)

#?(:jolt
   (do
     ;; jolt: a mutable pin table (threads + views) and one publish of the worker-slot
     ;; CURRENT_EPOCH word. Same min-epoch, nesting, and view-count rules as the portable
     ;; PersistentMap / sorted-map path, without a new map or [epoch depth] vector on
     ;; every pin or unpin. The store is mmap-pin-epoch! (IMemRegion/-store-i32!, the
     ;; seq-cst libatomic store): the pin is visible before pin-epoch! / view-pin! returns
     ;; and the reader loads the root, and the unpin store happens in the caller's finally.
     (scheme/eval-string
      "(begin
         ;; slots 4 and 5: a binary min-heap of view epochs (vector, size), so the least view epoch after the
         ;; least one's last view goes is the heap's top once the gone epochs above it are popped, not a scan of
         ;; every live epoch (the sorted map's first key on the portable path). An epoch whose views all went
         ;; stays in the heap until it reaches the top; the heap is rebuilt from the table when those outnumber it.
         ;; slots 6-8: the table's mutex, the address of this participant's worker-slot CURRENT_EPOCH word (#f where
         ;; the region has none to store to here) and the root region that address lies in, kept live by the table.
         (define eve-pt-store4
           (and eve-region-libatomic (foreign-procedure \"__atomic_store_4\" (uptr integer-32 int) void)))
         ;; slot 9: the root region's base address where slot 7 has one (read-pinned's pin-and-read below), else #f
         (define (eve-pt-new addr keep base)
           (let ((a (and eve-pt-store4 (fixnum? addr) addr)))
             (vector (make-eq-hashtable) (make-eq-hashtable) #f #f (make-vector 16 0) 0
                     (box #f) a keep (and a (fixnum? base) base) eve-pt-collect-q 1 #f #f 0)))
         ;; slot 6: the table's lock, a box CASed #f -> #t (box-cas!, a locked cmpxchg) and spun on: held across a few
         ;; table operations and one store, never across a call that can block (a pthread mutex pair was ~28 ns of
         ;; every pin and every unpin on jolt, atom-$-get-map-1k).
         (define-syntax eve-pt-lock!
           (syntax-rules () ((_ pt) (let ((b (vector-ref pt 6))) (let spin () (unless (box-cas! b #f #t) (spin)))))))
         (define-syntax eve-pt-unlock!
           (syntax-rules () ((_ pt) (begin (memory-order-release) (set-box! (vector-ref pt 6) #f)))))
         ;; slot 0 maps a thread id to its cell, a mutable pair (least epoch . depth); a cell at depth 0 is a thread
         ;; with no pin, kept (no cons and no delete per read) until the table holds more than 64 threads. Slots 12
         ;; and 13: the last thread's id and its cell (one thread reading again skips the hashtable); slot 14: the
         ;; number of cells at depth > 0.
         (define (eve-pt-cell pt tid)
           (if (eq? (vector-ref pt 12) tid)
               (vector-ref pt 13)
               (let ((c (hashtable-ref (vector-ref pt 0) tid #f)))
                 (when c (vector-set! pt 12 tid) (vector-set! pt 13 c))
                 c)))
         (define (eve-pt-threads pt) (vector-ref pt 0))
         (define (eve-pt-views pt) (vector-ref pt 1))
         (define (eve-pt-tmin pt) (vector-ref pt 2))
         (define (eve-pt-vmin pt) (vector-ref pt 3))
         (define (eve-pt-tmin-set! pt v) (vector-set! pt 2 v))
         (define (eve-pt-vmin-set! pt v) (vector-set! pt 3 v))
         ;; an empty table (the last reader's unpin) answers #f without collecting the entries
         (define (eve-pt-recompute-tmin! pt)
           (if (fx= (vector-ref pt 14) 0)
             (begin (eve-pt-tmin-set! pt #f) #f)
           (let-values (((_ vs) (hashtable-entries (eve-pt-threads pt))))
             (let loop ((i 0) (m #f))
               (if (fx>= i (vector-length vs))
                   (begin (eve-pt-tmin-set! pt m) m)
                   (let ((c (vector-ref vs i)))
                     (loop (fx+ i 1) (if (and (fx> (cdr c) 0) (or (not m) (< (car c) m))) (car c) m))))))))
         (define (eve-pt-heap-push! pt e)
           (let* ((n (vector-ref pt 5))
                  (h (let ((h0 (vector-ref pt 4)))
                       (if (fx< n (vector-length h0))
                           h0
                           (let ((g (make-vector (fx* 2 (vector-length h0)) 0)))
                             (do ((i 0 (fx+ i 1))) ((fx= i n)) (vector-set! g i (vector-ref h0 i)))
                             (vector-set! pt 4 g)
                             g)))))
             (let up ((i n))
               (if (fx= i 0)
                   (vector-set! h 0 e)
                   (let* ((p (fxsrl (fx- i 1) 1)) (pv (vector-ref h p)))
                     (if (< e pv)
                         (begin (vector-set! h i pv) (up p))
                         (vector-set! h i e)))))
             (vector-set! pt 5 (fx+ n 1))))
         (define (eve-pt-heap-pop! pt)
           (let* ((h (vector-ref pt 4)) (n (fx- (vector-ref pt 5) 1)) (e (vector-ref h n)))
             (vector-set! pt 5 n)
             (when (fx> n 0)
               (let down ((i 0))
                 (let* ((l (fx+ (fx* 2 i) 1)) (r (fx+ l 1)))
                   (if (fx>= l n)
                       (vector-set! h i e)
                       (let* ((c (if (and (fx< r n) (< (vector-ref h r) (vector-ref h l))) r l))
                              (cv (vector-ref h c)))
                         (if (< cv e)
                             (begin (vector-set! h i cv) (down c))
                             (vector-set! h i e)))))))))
         (define (eve-pt-heap-add! pt e)
           (let ((ht (eve-pt-views pt)))
             (if (fx> (vector-ref pt 5) (fx+ 64 (fx* 2 (hashtable-size ht))))
                 (let ((ks (hashtable-keys ht)))
                   (vector-set! pt 5 0)
                   (do ((i 0 (fx+ i 1))) ((fx= i (vector-length ks))) (eve-pt-heap-push! pt (vector-ref ks i))))
                 (eve-pt-heap-push! pt e))))
         (define (eve-pt-recompute-vmin! pt)
           (let ((ht (eve-pt-views pt)))
             (let loop ()
               (if (fx= (vector-ref pt 5) 0)
                   (begin (eve-pt-vmin-set! pt #f) #f)
                   (let ((m (vector-ref (vector-ref pt 4) 0)))
                     (if (hashtable-contains? ht m)
                         (begin (eve-pt-vmin-set! pt m) m)
                         (begin (eve-pt-heap-pop! pt) (loop))))))))
         (define (eve-pt-publish-val pt)
           (let ((tm (eve-pt-tmin pt)) (vm (eve-pt-vmin pt)))
             (cond ((and tm vm) (min tm vm))
                   (tm tm)
                   (vm vm)
                   (else 0))))
         (define (eve-pt-pin! pt tid epoch)
           (let ((cur (eve-pt-cell pt tid)))
             (if (and cur (fx> (cdr cur) 0))
                 (let ((e (min (car cur) epoch)))
                   (set-car! cur e)
                   (set-cdr! cur (fx+ (cdr cur) 1))
                   (let ((tm (eve-pt-tmin pt)))
                     (when (or (not tm) (< e tm)) (eve-pt-tmin-set! pt e))))
                 (begin
                   (if cur
                       (begin (set-car! cur epoch) (set-cdr! cur 1))
                       (let ((c (cons epoch 1)))
                         (hashtable-set! (eve-pt-threads pt) tid c)
                         (vector-set! pt 12 tid)
                         (vector-set! pt 13 c)))
                   (vector-set! pt 14 (fx+ (vector-ref pt 14) 1))
                   (let ((tm (eve-pt-tmin pt)))
                     (when (or (not tm) (< epoch tm)) (eve-pt-tmin-set! pt epoch)))))
             (eve-pt-publish-val pt)))
         (define (eve-pt-unpin! pt tid)
           (let ((cur (eve-pt-cell pt tid)))
             (when (and cur (fx> (cdr cur) 0))
               (let ((e (car cur)) (d (cdr cur)))
                 (if (fx> d 1)
                     (set-cdr! cur (fx- d 1))
                     (begin
                       (set-cdr! cur 0)
                       (vector-set! pt 14 (fx- (vector-ref pt 14) 1))
                       (let ((ht (eve-pt-threads pt)))
                         (when (fx> (hashtable-size ht) 64)
                           (hashtable-delete! ht tid)
                           (when (eq? (vector-ref pt 12) tid) (vector-set! pt 12 #f) (vector-set! pt 13 #f))))
                       (let ((tm (eve-pt-tmin pt)))
                         (when (and tm (= e tm)) (eve-pt-recompute-tmin! pt)))))))
             (eve-pt-publish-val pt)))
         (define (eve-pt-view-pin! pt epoch)
           (let* ((ht (eve-pt-views pt))
                  (n (hashtable-ref ht epoch 0)))
             (hashtable-set! ht epoch (fx+ n 1))
             (when (fx= n 0)
               (eve-pt-heap-add! pt epoch)
               (let ((vm (eve-pt-vmin pt)))
                 (when (or (not vm) (< epoch vm)) (eve-pt-vmin-set! pt epoch))))
             (eve-pt-publish-val pt)))
         (define (eve-pt-view-unpin! pt epoch)
           (let* ((ht (eve-pt-views pt))
                  (n (hashtable-ref ht epoch 0)))
             (cond
               ((fx<= n 1)
                (when (fx= n 1)
                  (hashtable-delete! ht epoch)
                  (let ((vm (eve-pt-vmin pt)))
                    (when (and vm (= epoch vm)) (eve-pt-recompute-vmin! pt)))))
               (else (hashtable-set! ht epoch (fx- n 1))))
             (eve-pt-publish-val pt)))
         ;; a pin or unpin and the publish of its least epoch (the seq-cst libatomic store -store-i32! makes, at the
         ;; slot word's address) under the table's mutex, as eve.atom's locking + mmap-pin-epoch! did: #t, or #f with
         ;; nothing done where the table has no address (the caller takes that path)
         (define-syntax eve-pt-locked-pub
           (syntax-rules ()
             ((_ pt e)
              (let ((a (vector-ref pt 7)))
                (if a
                    (begin
                      (eve-pt-lock! pt)
                      (let ((v e)) (eve-pt-store4 a v 5) (keep-live (vector-ref pt 8)) (eve-pt-unlock! pt) #t))
                    #f)))))
         (define (eve-pt-pin-pub! pt epoch) (eve-pt-locked-pub pt (eve-pt-pin! pt (get-thread-id) epoch)))
         ;; read-pinned's pin in one call: the root epoch word's load, the pin and its publish (as eve-pt-pin-pub!),
         ;; then the atom slot's pointer word's load, behind the seq-cst store as pin-epoch! then -load-i32 had it;
         ;; the pointer, or #f with nothing done where the table has no root base (the caller takes the old path)
         ;; slot 10: the retire-queue length at which a commit whose flush this process's own pin blocks has the
         ;; collector look for dead views in its older generations (eve-pt-collect-views!).
         ;; Why: a view's pin ends when jolt's collector finds the view dead (eve.gc's guardian). Chez promotes every
         ;; object live at a collection into the next generation, and generation g is collected only every 4^g
         ;; collections, so a view that happened to be live at a collection or two — dead a moment later — can keep
         ;; its epoch pinned for hundreds of thousands of commits: the zero queue grows without bound and nothing is
         ;; freed (probe jseam.allocprobe: 120k -> 320k entries; one full collection releases it).
         (define eve-pt-collect-q 65536)
         ;; (eve.gc's locked pop: the guardian's queue is not safe for two threads at once)
         (define (eve-pt-sweep-views!)
           (when (top-level-bound? 'eve-gc-pop)
             (let ((g (top-level-value 'eve-gc-pop)))
               (let loop () (let ((r (g))) (when r (r) (loop)))))))
         ;; qn: the queue's length; e: this process's published pin. Collects generations 0..g (all threads paused,
         ;; collect-rendezvous under a one-off handler; each survivor moves up one generation only — min target 1 —
         ;; so the views live now are not sent to the oldest generation to pin there in turn), g from the generation
         ;; that last released one (slot 11, first
         ;; 1) up to the oldest, running the released views' unpins after each, until the published pin moves: #t
         ;; (the caller flushes, the threshold goes back to its base), or #f with the threshold doubled (a pin the
         ;; program itself still holds is not collected for).
         (define (eve-pt-collect-views! pt qn)
           (let ((a (vector-ref pt 7)))
             (if (and a (fx> qn (vector-ref pt 10)) (top-level-bound? 'eve-gc-guardian))
                 (let ((e (foreign-ref 'integer-32 a 0)) (mg (collect-maximum-generation)) (h (collect-request-handler)))
                   (let loop ((g (fxmin (vector-ref pt 11) mg)))
                     (collect-request-handler (lambda () (collect g 1 (fxmin (fx+ g 1) mg))))
                     (collect-rendezvous)
                     (collect-request-handler h)
                     (eve-pt-sweep-views!)
                     (cond ((not (= (foreign-ref 'integer-32 a 0) e))
                            (vector-set! pt 10 eve-pt-collect-q)
                            (vector-set! pt 11 g)
                            #t)
                           ((fx< g mg) (loop (fx+ g 1)))
                           (else (vector-set! pt 10 (fx* 2 qn)) #f))))
                 #f)))
         (define (eve-pt-pin-read! pt eoff poff)
           (let ((a (vector-ref pt 7)) (rb (vector-ref pt 9)))
             (if (and a rb)
                 (begin
                   (eve-pt-lock! pt)
                   (let* ((e (#3%foreign-ref 'integer-32 rb eoff))
                          (v (eve-pt-pin! pt (get-thread-id) e)))
                     (eve-pt-store4 a v 5)
                     (let ((p (#3%foreign-ref 'integer-32 rb poff)))
                       (keep-live (vector-ref pt 8))
                       (eve-pt-unlock! pt)
                       p)))
                 #f)))
         (define (eve-pt-unpin-pub! pt) (eve-pt-locked-pub pt (eve-pt-unpin! pt (get-thread-id))))
         (define (eve-pt-view-pin-pub! pt epoch) (eve-pt-locked-pub pt (eve-pt-view-pin! pt epoch)))
         (define (eve-pt-view-unpin-pub! pt epoch) (eve-pt-locked-pub pt (eve-pt-view-unpin! pt epoch)))
         (define (eve-pt-snapshot pt assoc-fn empty-map views-key sm-empty vec-fn)
           (let-values (((tks tvs) (hashtable-entries (eve-pt-threads pt))))
             (let loop ((i 0) (m empty-map))
               (if (fx>= i (vector-length tks))
                   (if (fx> (hashtable-size (eve-pt-views pt)) 0)
                       (let-values (((vks vvs) (hashtable-entries (eve-pt-views pt))))
                         (let vloop ((j 0) (sm sm-empty))
                           (if (fx>= j (vector-length vks))
                               (assoc-fn m views-key sm)
                               (vloop (fx+ j 1)
                                      (assoc-fn sm (vector-ref vks j) (vector-ref vvs j))))))
                       m)
                   (let ((p (vector-ref tvs i)))
                     (loop (fx+ i 1)
                           (if (fx> (cdr p) 0)
                               (assoc-fn m (vector-ref tks i) (vec-fn (car p) (cdr p)))
                               m)))))))
         #t)")
     (def ^:private pt-new* (scheme/proc "eve-pt-new"))
     (def ^:private pt-pin!* (scheme/proc "eve-pt-pin!"))
     (def ^:private pt-unpin!* (scheme/proc "eve-pt-unpin!"))
     (def ^:private pt-view-pin!* (scheme/proc "eve-pt-view-pin!"))
     (def ^:private pt-view-unpin!* (scheme/proc "eve-pt-view-unpin!"))
     (def ^:private pt-pin-pub!* (scheme/proc "eve-pt-pin-pub!"))
     (def ^:private pt-pin-read!* (scheme/proc "eve-pt-pin-read!"))
     (def ^:private pt-collect-views!* (scheme/proc "eve-pt-collect-views!"))
     (def ^:private pt-unpin-pub!* (scheme/proc "eve-pt-unpin-pub!"))
     (def ^:private pt-view-pin-pub!* (scheme/proc "eve-pt-view-pin-pub!"))
     (def ^:private pt-view-unpin-pub!* (scheme/proc "eve-pt-view-unpin-pub!"))
     (def ^:private pt-snapshot* (scheme/proc "eve-pt-snapshot"))
     (defn- pt-snapshot [pt]
       (pt-snapshot* pt assoc {} ::views (sorted-map) vector))
     (deftype JoltPins [pt]
       clojure.lang.IDeref
       (deref [_] (pt-snapshot pt)))
     (defn- jolt-pins
       "The domain's pin table. Where root-r is a region whose worker-slot word can be stored to from Chez (a
        JoltRegion on Linux x86-64: eve.region/word-address), the table's own mutex and that store publish each pin;
        else (no address) pin-epoch! and the rest take the pin lock and mmap-pin-epoch!, as before."
       [root-r slot-idx]
       (JoltPins. (pt-new* (or (when (some? slot-idx)
                                 (region/word-address root-r (worker-slot-offset slot-idx d/OFFSET_WS_CURRENT_EPOCH)))
                               false)
                           root-r
                           (or (region/word-address root-r 0) false))))
     (defn- pt-of [pins]
       (.-pt ^JoltPins pins))))

(defn- make-domain-state
  [root-r rmap-r sio base-path slot-idx heartbeat]
  (let [ds {:root-r root-r :rmap-r rmap-r :sio sio :base-path base-path
            :slot-idx slot-idx
            :lock-only? (not (mem/-atomic-rmw? root-r))
            :heartbeat heartbeat
            :retire-q (clojure.core/atom [])
            ;; the slab classes the zero-queue flush wrote (frees, the cascade's drops) that no force has covered
            ;; yet, {class one-offset-of-it}: the next commit's force sweeps them with its own (take-unforced!)
            :unforced (clojure.core/atom {})
            :flush-ts (clojure.core/atom 0)
            :flush-n (volatile! 0)
            :pins #?(:jolt (jolt-pins root-r slot-idx) :default (clojure.core/atom {}))
            :pin-lock (token)
            :holder (clojure.core/atom nil)
            :closed? (clojure.core/atom false)
            ;; per-atom watch registries + validators (the trunk watch
            ;; surface: add-watch*/set-validator*; notification on every
            ;; successful swap, delivery under the deref law)
            :watches (clojure.core/atom {})
            :validators (clojure.core/atom {})
            ;; PROTOTYPE (step 2): what swap! does with a slab-backed value of ANOTHER domain — :throw (today's
            ;; behaviour), :copy (materialize it under the reader's pin and write it here) or :zip (lane T1:
            ;; detach and place it block for block). set-foreign-policy!
            :foreign-policy (clojure.core/atom :throw)}]
    ;; a reference an eve-deftype writes in place into one of this domain's blocks parks what reaches 0 here
    (tally/register-parker! sio (park-fn ds))
    ds))

(defn- park-fn
  "The domain's park!: a block whose count reached 0 queued on the zero queue at the epoch now."
  [ds]
  (let [root-r (:root-r ds) retire-q (:retire-q ds)]
    (fn [off kind]
      (swap! retire-q conj {:off off :kind kind :epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)}))))

(defn- take-unforced!
  "One offset in each slab class the zero-queue flush wrote that no force has covered yet, taken (the note emptied):
   the caller's force sweeps those classes with its own (alloc/sync-touched! forces by class)."
  [{:keys [unforced]}]
  (if unforced
    (loop []
      (let [u @unforced]
        (if (compare-and-set! unforced u {}) (vals u) (recur))))
    nil))

(declare jlog?)

(defn- flush-zero-queue!
  "Free the zero queue's entries whose epoch no live reader pins
  (eve.tally/flush!): the safe epoch is the minimum pinned reader epoch.
  A journaled domain needs no further bound: a commit lets its old root go
  only once its log record is durable (jlog-commit!), so nothing queued is
  reachable from a state recovery can install.

  The flush's own writes (bitmap + descriptor writes of the frees) are
  NOT forced here. On a journaled domain they ride until the next
  checkpoint's whole-domain force; on the force-ordering form (durable,
  no journal) the classes of the blocks it may free are noted on the
  domain (:unforced) and the next commit's pre-CAS force sweeps them
  with its own. Either way a crash before that force can only find a
  freed block still allocated, or a count above its holders (a cascade
  drop never forced) — the leak direction, never a use-after-free. (Measured, fix-for-release-2 E2: the flush runs on
  every single-swap commit — its queue never falls under
  FLUSH_QUEUE_THRESHOLD — so a force here was a second whole-class
  force per commit, ~3.9 ms of an add-5 commit's 17.9 on the bench
  box.)"
  [ds]
  (let [root-r (:root-r ds)
        sio (:sio ds)
        retire-q (:retire-q ds)
        durable-commits? (:durable-commits? ds)
        base-path (:base-path ds)
        unforced (:unforced ds)
        ;; a journaled domain notes nothing: its flush frees only blocks no durable state reaches, so a free left
        ;; unforced until the next checkpoint's whole-domain force can only leak in a crash (and the note copied the
        ;; whole queue on every flush, which grows without bound while a view pins an old epoch)
        note? (and durable-commits? base-path unforced (not (jlog? ds)))]
    ;; jolt: this process's own published pin already bounds the safe epoch from above (the safe epoch is the least
    ;; live slot's, this slot among them, and the floor only lowers it): when this slot is live and pinned at or
    ;; below the queue's head epoch, tally/flush! answers 0 at its head test with no other effect, so the 256-slot
    ;; scan is skipped. A queue past FLUSH_QUEUE_CAP runs the flush on every commit while a view pins an old epoch
    ;; (views go when jolt's collector finds them, thousands of commits apart). Not on a noting (durable) domain.
    ;; When that pin has held the queue past the pin table's threshold, the collector looks for the pinning view
    ;; in its older generations first (pt-collect-views!*: a dead view promoted there pins until that generation
    ;; is collected); if that moves the pin, the flush runs. A noting domain never asks (its flush always runs).
    (when-not #?(:jolt (and (some? (:slot-idx ds))
                            (let [slot-idx (:slot-idx ds)
                                  head (first @retire-q)
                                  e (when head
                                      (mem/-load-i32 root-r (worker-slot-offset slot-idx d/OFFSET_WS_CURRENT_EPOCH)))]
                              (and head
                                   (not (zero? e))
                                   (>= (:epoch head) e)
                                   (== (mem/-load-i32 root-r (worker-slot-offset slot-idx d/OFFSET_WS_STATUS))
                                       d/WORKER_STATUS_ACTIVE)
                                   (not (heartbeat-stale? root-r slot-idx))))
                            (not note?)
                            (not (pt-collect-views!* (pt-of (:pins ds)) (count @retire-q))))
                 :default false)
      (let [queued (when note? (mapv :off @retire-q))]
        (tally/flush! sio retire-q
                      (fn [] (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
                      (fn [] (mmap-min-safe-epoch root-r)))
        (when note?
          (swap! unforced into (map (fn [o] [(alloc/decode-class-idx o) o])) queued))))))

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
;; Commit journal — the instrumentation its flushes share (the journal itself:
;; the commit log on the mmapped ring, eve.jlog, below)
;; ---------------------------------------------------------------------------

(def ^:dynamic *flush-timing*
  "When bound to an atom, each commit's wait on the log's watermark
  (jlog-commit!: the force that makes its record durable) conjs its wall
  time in ms. Bench instrumentation only."
  nil)

(defn- now-ms []
  ;; eve.process/now-ms is the portable wall clock (per-host adapter);
  ;; System/currentTimeMillis exists only on the JVM — the CLR and
  ;; Basilisp seats have no java.lang.System, and the journal's batch
  ;; window threw there once it first ran on them
  ;; (phase 3). The adapter's now-ms is the one clock on every host.
  (proc/now-ms))

(def ^:dynamic *flush-log*
  "When bound to an atom, each commit's wait on the log's watermark (jlog-commit!) conjs {:at <wall-ms> :ms
  <duration-ms>}. Bench/workload instrumentation only."
  nil)

;; ---------------------------------------------------------------------------
;; The commit log (eve.jlog, a redo log; doc/jlog-redo-spec.md)
;;
;; A commit forces no slab file. It reserves its log seq after reading the
;; old root and before its CAS (so log order extends happens-before), CASes,
;; writes one record — the delta [slot old new hash] and the redo of what it
;; wrote: its new blocks' bytes, their bitmap bits by offset, their flags and
;; pointer columns, and the rows its holds touched — then waits until the
;; prefix watermark passes its seq: the watermark's msync is the commit's one
;; device flush, shared by every record forced with it. Only then does the
;; slot let the old root go, so no block a durable state reaches is ever
;; freed. A commit whose redo would not fit a slot, that allocates in the
;; coalesced class, or that wrote in place into a block it did not allocate
;; takes the fallback: its touched classes forced before its CAS, a delta-only
;; NOREDO record. A checkpoint (half the ring past the
;; newest anchor, sync!, close) forces the whole domain and writes an ANCHOR,
;; the previous anchor folded with the deltas since. Recovery folds the
;; anchor and the prefix, replays the redo, pins every row a replayed record
;; touched (count := CPERM: a count may err high, never low), forces the
;; domain and installs the table.
;; ---------------------------------------------------------------------------

(declare jlog-checkpoint!)

(defn- jlog?
  "The domain is journaled (its commit log, <base>.jlog)."
  [ds]
  (some? (:journal ds)))

(defn- u32 [x] (bit-and (long x) 0xFFFFFFFF))

(defn- jlog-stale? [root-r] (fn [w] (heartbeat-stale? root-r w)))

(defn- jlog-force-domain!
  "The whole domain forced: every slab region (data, bitmap, the three descriptor columns, the coalesced class)."
  [ds]
  (take-unforced! ds)
  (alloc/sync-sio! (:sio ds)))

(defn- jlog-await!
  "Return once the log's prefix watermark has passed SEQ (eve.jlog/await-durable!: this process forces what is
   ready; a dead writer's hole is voided after a whole-domain force)."
  [ds seq]
  (jlog/await-durable! (:log (:journal ds)) seq (jlog-stale? (:root-r ds)) d/HEARTBEAT_TIMEOUT_MS
                       #(jlog-force-domain! ds)))

(defn- jlog-reserve!
  "This process's next CNT consecutive seqs (the first answered). A commit may fill the ring to half its slots past
   the newest anchor; at that mark it runs a checkpoint first (CHECKPOINT? true: the checkpoint's own reservation,
   which may use the other half)."
  ([ds checkpoint?] (jlog-reserve! ds checkpoint? 1))
  ([ds checkpoint? cnt]
   (let [{:keys [root-r slot-idx journal]} ds
         log (:log journal)
         room (if checkpoint? (:n log) (quot (:n log) 2))
         gate (fn [t] (< (i32/i32 (- t (jlog/anchor-seq log))) room))]
     (loop [i 0]
       (let [r (jlog/reserve-run! log slot-idx cnt (jlog-stale? root-r) d/HEARTBEAT_TIMEOUT_MS gate)]
         (cond (not= :full r) (:seq r)
               checkpoint? (do (when (> i 100) (proc/sleep-ms! 1)) (recur (inc i)))
               :else (do (jlog-checkpoint! ds) (recur (inc i)))))))))

(defn- jlog-write!
  "Write the record of the CNT seqs from SEQ this process reserved (BODY split over them, each part its own
   CRC-checked record) and release the slots. false when a resolver voided a slot of the run first (this process was
   judged dead while alive): what it won is written as ABORT."
  ([ds seq kind label body] (jlog-write! ds seq kind label body 1))
  ([ds seq kind label body cnt]
   (let [{:keys [slot-idx journal]} ds
         log (:log journal)
         seqs (mapv (fn [i] (i32/i32 (+ seq i))) (range cnt))
         won (mapv (fn [s] (jlog/claim-seal! log s slot-idx)) seqs)
         ok? (every? true? won)
         parts (if (and ok? (> cnt 1)) (jlog/split-body body cnt) [body])]
     (dotimes [i cnt]
       (let [s (nth seqs i)]
         (when (nth won i)
           (jlog/write-slot! log s (if ok?
                                     (jlog/encode-record s kind label (:nonce log) (inc slot-idx) (nth parts i)
                                                         (jlog/part-word i cnt))
                                     (jlog/encode-record s jlog/K_ABORT 0 (:nonce log) (inc slot-idx) nil)))
           (jlog/release! log s))))
     ok?)))

(defn- jlog-redo
  "A won commit's redo from its plan (jlog-plan): [blocks pins] — each of KEPT (the blocks the attempt allocated that
   a holder took) with its bytes, flags and pointer column (W1-W3); each of PINS (the other rows its holds touched,
   W4)."
  [sio kept pins]
  [(mapv (fn [off]
           (let [row (alloc/desc-row sio off)]
             {:off off
              :flags (slab/region-load-i32 (nth row 2) (nth row 3))
              :pointer (slab/region-load-i32 (nth row 4) (nth row 5))
              :bytes (slab/read-bytes sio off 0 (d/slab-size (alloc/decode-class-idx off)))}))
         kept)
   (mapv (fn [off] {:off off :flags 0}) pins)])

(defn- jlog-plan
  "How a commit is logged, decided before its CAS (after hold-root!): {:cnt :kept :pins} — KEPT the blocks the attempt
   allocated that a holder took (PUB: the new root reaches them; the rest free-unreached! frees after the CAS), PINS
   the other rows its holds touched, CNT the slots their redo takes, exactly — or :fallback, the forced fallback
   (NOREDO): an allocation in the coalesced class, a block written in place that the attempt did not allocate
   (WRITES), or a redo past a quarter of the ring."
  [sio log allocs touches writes]
  (let [mine (into #{} (map u32) allocs)]
    (if (or (some (fn [off] (== alloc/OVERFLOW_CLASS_IDX (alloc/decode-class-idx off))) allocs)
            (some (fn [off] (not (contains? mine (u32 off)))) writes))
      :fallback
      (let [kept (filterv (fn [off]
                            (when-let [row (alloc/desc-row sio off)]
                              (not (zero? (bit-and (slab/region-load-i32 (nth row 2) (nth row 3)) tally/F_PUB)))))
                          allocs)
            pins (filterv (fn [off] (and (not (contains? mine off)) (alloc/desc-row sio off)))
                          (distinct (map u32 touches)))
            body (+ 24 (reduce + 0 (map (fn [off] (+ 16 (d/slab-size (alloc/decode-class-idx off)))) kept))
                    (* 8 (count pins)))
            cnt (max 1 (quot (+ body (dec jlog/REC_MAX_BODY)) jlog/REC_MAX_BODY))]
        (if (> cnt (quot (:n log) 4)) :fallback {:cnt cnt :kept kept :pins pins})))))

(defn- jlog-drop-pending!
  "Let go the old roots a batch's commits deferred (their records are durable now)."
  [ds]
  (when-let [pd (:pending-drops (:journal ds))]
    (let [ptrs (loop [] (let [v @pd] (if (compare-and-set! pd v []) v (recur))))]
      (doseq [p ptrs] (tally/drop-root! (:sio ds) p (park-fn ds))))))

(defn- jlog-commit!
  "A won journaled commit, after its CAS and epoch bump (LABEL): write its record over the CNT slots from SEQ — COMMIT with
   the redo, or NOREDO for a FALLBACK? commit (forced before its CAS) — wait until the watermark passes it (a batch
   commit only at its boundary), then let OLD-PTR go. A record a resolver voided (a zombie) is replaced by a
   checkpoint, whose forced domain and ANCHOR cover the commit."
  [ds seq cnt label slot old-ptr new-ptr plan fallback?]
  (let [{:keys [root-r sio journal]} ds
        delta {:slot slot :old old-ptr :new new-ptr
               :hash (mem/-load-i32 root-r (d/atom-slot-offset slot d/ATOM_SLOT_HASH_OFFSET))}
        body (if fallback?
               (jlog/encode-commit-body delta nil nil)
               (let [[blocks pins] (jlog-redo sio (:kept plan) (:pins plan))]
                 (jlog/encode-commit-body delta blocks pins)))
        written? (jlog-write! ds seq (if fallback? jlog/K_NOREDO jlog/K_COMMIT) label body cnt)
        last-seq (i32/i32 (+ seq (dec cnt)))
        limit (:limit journal)
        boundary? (or (<= limit 1)
                      (let [n (swap! (:pending journal) inc)
                            {:keys [window-ms last-flush]} journal]
                        (when (or (>= n limit) (and window-ms (>= (- (now-ms) @last-flush) window-ms)))
                          (reset! (:pending journal) 0)
                          (when last-flush (reset! last-flush (now-ms)))
                          true)))]
    (if (not written?)
      (do (jlog-checkpoint! ds)
          (tally/drop-root! sio old-ptr (park-fn ds)))
      (if boundary?
        (let [t0 (when *flush-timing* (now-ms))]
          (jlog-await! ds last-seq)
          (when t0 (swap! *flush-timing* conj (- (now-ms) t0)))
          (when *flush-log* (swap! *flush-log* conj {:at (now-ms) :ms (when t0 (- (now-ms) t0))}))
          (jlog-drop-pending! ds)
          (tally/drop-root! sio old-ptr (park-fn ds)))
        (swap! (:pending-drops journal) conj old-ptr)))
    nil))

(defn- jlog-abort!
  "A lost CAS's reservation of CNT slots: an ABORT record in each (the watermark forces them with their neighbours)."
  [ds seq cnt]
  (dotimes [i cnt]
    (jlog-write! ds (i32/i32 (+ seq i)) jlog/K_ABORT 0 nil))
  nil)

(defn- jlog-note-claim!
  "An atom slot claimed (its pointer CLAIMED, its name hash written): a delta-only record, durable before the claim
   is used, so recovery keeps the slot claimed — the name the registry will give it is never a free slot's."
  [ds slot]
  (let [root-r (:root-r ds)
        seq (jlog-reserve! ds false)
        delta {:slot slot :old alloc/NIL_OFFSET :new CLAIMED_SENTINEL
               :hash (mem/-load-i32 root-r (d/atom-slot-offset slot d/ATOM_SLOT_HASH_OFFSET))}]
    (jlog-write! ds seq jlog/K_NOREDO (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)
                 (jlog/encode-commit-body delta nil nil))
    (jlog-await! ds seq)))

(defn- jlog-checkpoint!
  "A checkpoint (half the ring past the newest anchor, sync!, close; doc/jlog-redo-spec.md §5): reserve a seq, wait
   until every seq before it is durable, force the whole domain, then write an ANCHOR there — the newest anchor's
   table folded with every delta between — wait until it is durable and advance the anchor. A batch's deferred drops
   go after."
  [ds]
  (let [log (:log (:journal ds))
        seq (jlog-reserve! ds true)]
    (jlog-await! ds (i32/i32 (dec seq)))
    (jlog-force-domain! ds)
    (let [a (jlog/anchor-seq log)
          anchor (jlog/read-record log a)
          recs (loop [s (i32/i32 (inc a)) acc []]
                 (if (== s seq) acc (recur (i32/i32 (inc s)) (conj acc (jlog/read-record log s)))))
          [table label] (jlog/fold anchor (jlog/logical (remove nil? recs)))]
      (when (jlog-write! ds seq jlog/K_ANCHOR label (jlog/encode-anchor-body table))
        (jlog-await! ds seq)
        (jlog/advance-anchor! log seq)))
    (jlog-drop-pending! ds)))

(defn- jlog-anchor-fresh!
  "Lay down a fresh ANCHOR of the root's table at the log's tail (a new log, or recovery's end): reserve, write,
   force, advance. Run with no live writer (create, or under the recovery hold)."
  [root-r log]
  (let [{:keys [seq]} (jlog/reserve! log 0 0 (constantly false) d/HEARTBEAT_TIMEOUT_MS)
        table (mem/-read-bytes root-r d/ATOM_TABLE_START jlog/TABLE_BYTES)]
    (jlog/claim-seal! log seq 0)
    (jlog/write-slot! log seq (jlog/encode-record seq jlog/K_ANCHOR (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)
                                                  (:nonce log) 0 (jlog/encode-anchor-body table)))
    (jlog/release! log seq)
    (jlog/force-through! log)
    (jlog/advance-anchor! log seq)
    seq))

(defn- jlog-replay!
  "Recovery of a log (doc/jlog-redo-spec.md §6), under the recovery hold, no live writer: the newest anchor and
   the prefix after it; the table folded; the redo of each COMMIT replayed in log order — block bytes, bitmap bits,
   flags and pointer columns — and every row a replayed record names pinned (count := CPERM); the whole domain
   forced; the table and epoch installed into the root and forced; valid records past the prefix (never
   acknowledged) voided; the reservation state rebuilt; a fresh ANCHOR. Answers the installed epoch."
  [root-r sio log]
  (let [[anchor & recs] (jlog/recovery-prefix log)
        e-root (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
    (when anchor
      (alloc/refresh! sio)
      (doseq [rec (jlog/logical recs)
              :when (== jlog/K_COMMIT (:kind rec))
              :let [{:keys [blocks pins]} (jlog/decode-commit-body (:body rec))]]
        (doseq [{:keys [off flags pointer bytes]} blocks]
          (slab/write-bytes! sio off 0 bytes)
          (alloc/mark-allocated! sio off)
          (when-let [row (alloc/desc-row sio off)]
            (slab/region-store-i32! (nth row 2) (nth row 3) flags)
            (slab/region-store-i32! (nth row 4) (nth row 5) pointer)
            (slab/region-store-i32! (nth row 0) (nth row 1) tally/CPERM)))
        (doseq [{:keys [off]} pins]
          (when-let [row (alloc/desc-row sio off)]
            (slab/region-store-i32! (nth row 0) (nth row 1) tally/CPERM))))
      (alloc/sync-sio! sio)
      (let [[table label] (jlog/fold anchor (jlog/logical recs))]
        (mem/-write-bytes! root-r d/ATOM_TABLE_START table)
        (mem/-store-i32! root-r d/ROOT_EPOCH_OFFSET
                         (if (pos? (i32/i32 (- label e-root))) label e-root))
        (mem/sync-region! root-r)))
    (let [next (if anchor
                 (i32/i32 (+ (:seq anchor) 1 (count recs)))
                 (i32/i32 (inc (or (jlog/max-seq (keep (fn [i] (jlog/read-slot log i)) (range (:n log)))) -1))))]
      ;; records at or past NEXT were never acknowledged: void them before anything new is written at NEXT, or a
      ;; later recovery would read them as the prefix's continuation
      (jlog/void-from! log next)
      (jlog/rebuild-header! log next)
      (jlog-anchor-fresh! root-r log)
      (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))))

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

;; ---------------------------------------------------------------------------
;; Opening a jlog domain: the recovery hold and recovery (eve-mmap-journal-plan §4.4)
;; ---------------------------------------------------------------------------

(def ^:private RECOVERY_DRAIN_MS
  "How long a recoverer waits, holding the domain, for writes already in flight to finish before it judges the
   domain's liveness: a write in flight finishes in microseconds; a count still set after this is a live writer
   (its slot is live) or one a killed process leaked (H5), which recovery repairs."
  1000)

(defn- with-recovery-hold*
  "Run (f) holding the domain's recovery hold, ROOT_HOLD = HOLD_RECOVERING (§4.4 step 2): writers and slot claims
   back off while it is set. A lock-free host takes it by CAS once the hold is clear (a dead holder's is cleared by
   wait-hold-clear!), then waits up to RECOVERY_DRAIN_MS for the writes already in flight; a lock-only host takes it
   under its file lock on the hold word, as with-exclusive-hold* does. No OS lock is involved on a lock-free host."
  [root-r f]
  (if (not (mem/-atomic-rmw? root-r))
    (mem/-with-file-lock root-r d/ROOT_HOLD_OFFSET
                         (fn []
                           (wait-hold-clear! root-r)
                           (mem/-store-i32! root-r d/ROOT_HOLD_OFFSET HOLD_RECOVERING)
                           (try (f) (finally (mem/-store-i32! root-r d/ROOT_HOLD_OFFSET 0)))))
    (loop []
      (wait-hold-clear! root-r)
      (if (zero? (mem/-cas-i32! root-r d/ROOT_HOLD_OFFSET 0 HOLD_RECOVERING))
        (try
          (let [t0 (proc/now-ms)]
            (loop [i 0]
              (when (and (pos? (mem/-load-i32 root-r d/ROOT_INFLIGHT_OFFSET))
                         (< (- (proc/now-ms) t0) RECOVERY_DRAIN_MS))
                (when (> i 100) (proc/sleep-ms! 1))
                (recur (inc i)))))
          (f)
          (finally (mem/-cas-i32! root-r d/ROOT_HOLD_OFFSET HOLD_RECOVERING 0)))
        (recur)))))

(defn- claim-live-slot!
  "Claim a worker slot and write its heartbeat in one step, so no recoverer ever sees the slot ACTIVE with a stale
   heartbeat and judges a live opener dead. Inside a hold this process holds, a direct claim; otherwise a write to
   the root (claim-slot-as-participant!'s bracket)."
  ([root-r] (claim-live-slot! root-r false))
  ([root-r held?]
   (let [claim (fn [] (let [i (mmap-claim-slot! root-r)] (write-heartbeat! root-r i) i))]
     (if held?
       (claim)
       (with-root-write* root-r (not (mem/-atomic-rmw? root-r)) HOLD_JOINING claim)))))

(defn- jlog-path [base-path] (str base-path ".jlog"))

(defn- jlog-region-opts
  "The log's region options: the domain's own (a Lustre domain's log is a Lustre region, its atomics byte-range
   locks across nodes, as its root's are)."
  [opts]
  {:lustre? (:lustre? opts)})

(defn- jlog-create-opts
  "A new log's options: its slot count (:jlog-slots) and the domain's region options."
  [opts]
  {:slots (or (:jlog-slots opts) jlog/DEFAULT_SLOTS)
   :region (jlog-region-opts opts)})

(defn- legacy-install!
  "Migration's install (§4.7 steps 1-3): the legacy <base>.journal's records read once (eve.jlog/legacy-records:
   a framing failure resyncs on the magic rather than ending the read), the one with the highest epoch picked —
   never the last in file order — and installed into the root when its epoch is at or after the root's and its
   epoch or table differs; a legacy record OLDER than the root is never installed (that was the shootout's loss).
   The file is read through a mapping of exactly its size: no host's open grows or truncates it."
  [root-r legacy]
  (let [size (or (proc/file-size legacy) 0)
        recs (if (pos? size)
               (jlog/legacy-records (mem/-read-bytes (region/open-mmap-region legacy size) 0 size))
               [])
        e-root (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)
        rel (fn [r] (i32/i32 (- (:epoch r) e-root)))
        best (when (seq recs) (reduce (fn [a r] (if (> (rel r) (rel a)) r a)) recs))]
    (when (and best
               (>= (rel best) 0)
               (or (not (== (:epoch best) e-root))
                   (not (enc/bytes= (mem/-read-bytes root-r d/ATOM_TABLE_START jlog/TABLE_BYTES) (:table best)))))
      (mem/-write-bytes! root-r d/ATOM_TABLE_START (:table best))
      (mem/-store-i32! root-r d/ROOT_EPOCH_OFFSET (:epoch best))
      (mem/sync-region! root-r))
    {:legacy-records (count recs) :installed? (some? best)}))

(defn- jlog-for-recovery
  "The log a non-live open recovers from: the existing one; else a fresh one — after migrating a legacy
   <base>.journal into the root (legacy-install!) when there is one, or when the log's creator died before
   writing its magic (magic 0). A wrong magic or version throws (eve.jlog/open): a log is never overwritten."
  [root-r base-path opts]
  (let [path (jlog-path base-path)
        legacy (str base-path ".journal")]
    (if (and (proc/file-exists? path) (not (zero? (jlog/magic path))))
      (jlog/open path d/HEARTBEAT_TIMEOUT_MS (jlog-region-opts opts))
      (do (when (proc/file-exists? legacy) (legacy-install! root-r legacy))
          (jlog/create! path (jlog-create-opts opts))))))

(defn- jlog-recover!
  "Recovery of a non-live journaled domain, under the recovery hold: repair a leaked in-flight count (H5); open the log
   (or lay one down, after migrating a legacy <base>.journal); replay its prefix (jlog-replay!); rename a migrated
   legacy journal aside; claim this process's slot inside the hold. Answers {:log :slot-idx}."
  [root-r sio base-path opts]
  (mem/-store-i32! root-r d/ROOT_INFLIGHT_OFFSET 0)
  (let [log (jlog-for-recovery root-r base-path opts)]
    (jlog-replay! root-r sio log)
    ;; once the fresh ANCHOR is durable a legacy journal is renamed aside, never deleted
    (let [legacy (str base-path ".journal")]
      (when (proc/file-exists? legacy) (proc/rename-file! legacy (str legacy ".migrated"))))
    {:log log :slot-idx (claim-live-slot! root-r true)}))

(defn- jlog-open!
  "The jlog side of opening a domain. A creator — whose root went down under HOLD_RECOVERING (init-domain-root!) —
   lays the log down with its first ANCHOR, claims its slot, then lets the hold go. A joiner takes the recovery hold: a
   live domain is joined as it stands; a domain no process holds is recovered (jlog-recover!). Answers {:log
   :slot-idx}."
  [root-r sio base-path create? opts]
  (if create?
    (let [log (jlog/create! (jlog-path base-path) (jlog-create-opts opts))
          _ (jlog-anchor-fresh! root-r log)
          slot-idx (claim-live-slot! root-r true)]
      (if (mem/-atomic-rmw? root-r)
        (mem/-cas-i32! root-r d/ROOT_HOLD_OFFSET HOLD_RECOVERING 0)
        (mem/-store-i32! root-r d/ROOT_HOLD_OFFSET 0))
      {:log log :slot-idx slot-idx})
    (let [res (with-recovery-hold* root-r
                (fn [] (if (domain-live? root-r) ::live (jlog-recover! root-r sio base-path opts))))]
      (if (= ::live res)
        (let [path (jlog-path base-path)
              _ (when-not (proc/file-exists? path)
                  (throw (ex-info (str "eve: " base-path " is live without a jlog (a legacy journal domain in use);"
                                       " it can move to the jlog only on an open no other process holds")
                                  {:base-path base-path})))
              log (jlog/open path d/HEARTBEAT_TIMEOUT_MS (jlog-region-opts opts))]
          {:log log
           :slot-idx (claim-live-slot! root-r)})
        res))))

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
                                        {:lustre? lustre?})
        jlog? (:journal? opts)
        _ (when create? (init-domain-root! root-r (if jlog? HOLD_RECOVERING 0)))
        jl (when jlog? (jlog-open! root-r sio base-path create? opts))
        journal  (when jl
                   {:log     (:log jl)
                    :limit   (or (:journal-batch opts) 1)
                    :pending (clojure.core/atom 0)
                    ;; the old roots a batch's commits let go once their records are durable (jlog-commit!)
                    :pending-drops (clojure.core/atom [])})
        journal  (cond-> journal
                   (:journal-window-ms opts)
                   (assoc :window-ms (:journal-window-ms opts)
                          :last-flush (clojure.core/atom (now-ms))))
        slot-idx (if jl (:slot-idx jl) (claim-slot-as-participant! root-r))
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
    ds))

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

(defn- slot-pin!
  "Publish this process's pin: the least epoch any reader holds — the
   threads' pins (one entry per pinning thread) and the views' (::views, a
   sorted map epoch -> count, so its least is its first key however many
   views are alive)."
  [root-r slot-idx pins]
  ;; one pass over the entries (no dissoc, no lazy seqs: on jolt they and the sorted map's seqs were a fifth of a
  ;; small deref), and the views' first key read only where there are views
  (let [views (get pins ::views)
        tmin (reduce-kv (fn [m k v] (if (= k ::views) m (let [e (first v)] (if (or (nil? m) (< e m)) e m))))
                        nil pins)
        vmin (when (pos? (count views)) (key (first views)))]
    (if (and (nil? tmin) (nil? vmin))
      (mmap-unpin-epoch! root-r slot-idx)
      (mmap-pin-epoch! root-r slot-idx (min (or tmin vmin) (or vmin tmin))))))

(defn- pin-epoch! [ds epoch]
  ;; jolt: the table's own mutex and publish (one Chez call, no domain-state lookups) where it has the slot word's
  ;; address; else the pin lock, as on every host
  (when-not #?(:jolt (pt-pin-pub!* (pt-of (:pins ds)) epoch) :default false)
    (let [{:keys [root-r slot-idx pins pin-lock]} ds]
      (locking pin-lock
        #?(:jolt (mmap-pin-epoch! root-r slot-idx (pt-pin!* (pt-of pins) (proc/thread-id) epoch))
           :default
           (let [tid (proc/thread-id)
                 p   (swap! pins (fn [m]
                                   (if-let [[e depth] (get m tid)]
                                     (assoc m tid [(min e epoch) (inc depth)])
                                     (assoc m tid [epoch 1]))))]
             (slot-pin! root-r slot-idx p)))))))

(defn- unpin-epoch! [ds]
  (when-not #?(:jolt (pt-unpin-pub!* (pt-of (:pins ds))) :default false)
    (let [{:keys [root-r slot-idx pins pin-lock]} ds]
      (locking pin-lock
        #?(:jolt (mmap-pin-epoch! root-r slot-idx (pt-unpin!* (pt-of pins) (proc/thread-id)))
           :default
           (let [tid (proc/thread-id)
                 p   (swap! pins (fn [m]
                                   (if-let [[e depth] (get m tid)]
                                     (if (> depth 1)
                                       (assoc m tid [e (dec depth)])
                                       (dissoc m tid))
                                     m)))]
             (slot-pin! root-r slot-idx p)))))))

(defn- view-pin!
  "A view's pin: counted under its epoch in ::views, not under a thread, so
   any thread (the GC's, when it collects the read's last view) releases it."
  [ds epoch]
  (when-not #?(:jolt (pt-view-pin-pub!* (pt-of (:pins ds)) epoch) :default false)
    (let [{:keys [root-r slot-idx pins pin-lock]} ds]
      (locking pin-lock
        #?(:jolt (mmap-pin-epoch! root-r slot-idx (pt-view-pin!* (pt-of pins) epoch))
           :default
           ;; (update m ::views (fnil update (sorted-map)) epoch (fnil inc 0)), spelled out: no fnil closures, no
           ;; update's apply (a count is never nil)
           (slot-pin! root-r slot-idx
                      (swap! pins (fn [m]
                                    (let [vs (or (get m ::views) (sorted-map))]
                                      (assoc m ::views (assoc vs epoch (inc (get vs epoch 0)))))))))))))

(defn- view-unpin! [ds epoch]
  (when-not #?(:jolt (pt-view-unpin-pub!* (pt-of (:pins ds)) epoch) :default false)
    (let [{:keys [root-r slot-idx pins pin-lock]} ds]
      (locking pin-lock
        #?(:jolt (mmap-pin-epoch! root-r slot-idx (pt-view-unpin!* (pt-of pins) epoch))
           :default
           (slot-pin! root-r slot-idx
                      (swap! pins (fn [m]
                                    (let [vs (get m ::views)
                                          n (dec (get vs epoch 0))
                                          vs (if (pos? n) (assoc vs epoch n) (dissoc vs epoch))]
                                      (if (pos? (count vs)) (assoc m ::views vs) (dissoc m ::views)))))))))))

(defonce ^:private view-read-hook
  ;; eve.view's registration (it requires this namespace): (fn [raw ctx]
  ;; -> the read's answer), loaded on first need (view-read-fn); ::none where
  ;; no view layer loads (basilisp), whose reads outside `$` deliver flat.
  (clojure.core/atom nil))

(defn register-view-read!
  "eve.view's registration of the view a read outside `$` answers."
  [f]
  (reset! view-read-hook f))

(declare deliver-value deref-deliver)

(defn- view-read-fn
  "The registered view read, loading eve.view (which registers it) the first
   time a read needs it — a program that reads an atom need not have required
   eve.view (or eve.alpha) first. ::none caches its absence (a host with no
   flat layer yet: basilisp)."
  []
  (let [h @view-read-hook]
    (cond
      (fn? h) h
      (= ::none h) nil
      :else #?(:cljs nil
               :clje nil
               :lpy (do (reset! view-read-hook ::none) nil)
               :default (do (try (requiring-resolve 'eve.view/wrap)
                                 (catch #?(:cljd dynamic :cljr Exception :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))
                            (let [h @view-read-hook]
                              (if (fn? h) h (do (reset! view-read-hook ::none) nil))))))))

(defn- read-answer
  "The answer a read gives for raw, read now under the caller's pin: a view
   holding a pin of its own (from the current epoch) when views are built
   here, else raw delivered flat."
  [ds atom-slot-idx raw]
  (let [root-r (:root-r ds)]
    (if-let [h (view-read-fn)]
      (let [epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
      ;; the read api eve.view chooses from: a pin of the read's own (:hold,
      ;; answering its release), or per-access pins (:enter / :exit) checked
      ;; against the epoch the read was taken at
        (h raw {:epoch epoch
                :hold (fn [] (view-pin! ds epoch) (fn [] (view-unpin! ds epoch)))
                :enter (fn [] (let [e (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
                                (pin-epoch! ds e)
                                e))
                :exit (fn [] (unpin-epoch! ds))}))
    ;; no views here: the new value delivered flat; on a host with no flat
    ;; layer yet (basilisp), the value as before — eve->clj would refuse
    ;; an eve array on every swap!
      (if-let [deliver (deref-deliver)]
        (deliver raw)
        raw))))

(defn- refresh-slabs! [ds]
  (let [base-path (:base-path ds) sio (:sio ds)]
    (when base-path (alloc/refresh! sio))))

;; ---------------------------------------------------------------------------
;; Root values
;; ---------------------------------------------------------------------------

(defn- live-ptr? [ptr]
  ;; jolt: a pointer word is an integer; == is its compiled compare, not= the variadic generic
  #?(:jolt (not (or (== ptr alloc/NIL_OFFSET) (== ptr CLAIMED_SENTINEL)))
     :default (and (not= ptr alloc/NIL_OFFSET) (not= ptr CLAIMED_SENTINEL))))

;; jolt: the root block's type-id byte (read in place, as alloc/read-header-tag) and its header constructor
;; (eve.deftype-proto.serialize's header-ctor-array, the registry's mirror) in one Chez call: the constructor, or #f
;; for a scalar block, an unregistered type-id or a sio that is not a slab context (read-root-value's own path then).
;; The two were ~40 ns of every root read on jolt (atom-$-get-map-1k, 2026-11-23 ET).
#?(:jolt
   (def ^:private root-ctor*
     (let [tags (alloc/jolt-ctx-tags)]
       (scheme/eval-string
        "(define (eve-atom-root-ctor ctag ltag u8op arr scalar)
           (let ((cd #f) (ld #f) (vec (jolt-array-vec arr)))
             (lambda (sio ptr)
               (eve-alloc-unwrap sio s ctag ltag cd ld
                 (let* ((v (jrec*-vals s))
                        (t (eve-sio-at (vector-ref v 0) (vector-ref v 2) (vector-ref v 5) ptr 0 r a
                             (let ((b (and (fixnum? a) (eve-region-base r))))
                               (if b (let ((x (#3%foreign-ref 'unsigned-8 b a))) (keep-live r) x) (u8op r a))))))
                   (if (or (not (fixnum? t)) (fx= t scalar) (fx< t 0) (fx>= t (vector-length vec)))
                       #f
                       (let ((c (vector-ref vec t))) (if (or (not c) (eq? c jolt-nil)) #f c))))
                 #f))))")
       ((scheme/proc "eve-atom-root-ctor") (nth tags 0) (nth tags 1) region/plain-load-u8-op
                                           ser/header-ctor-array ser/SCALAR_BLOCK_TYPE_ID))))

(defn- read-root-value
  "The slab-backed value at ptr, read through sio. The caller pins."
  [sio ptr]
  ;; jolt: the type byte read and its constructor found in one Chez call (root-ctor*), the header path below for
  ;; what it answers #f for; every other host reads the header path alone, as it did (no dead branch in its body)
  (when (live-ptr? ptr)
    #?(:jolt (if-let [ctor (root-ctor* sio ptr)]
               (ctor sio ptr)
               (let [type-id (alloc/read-header-tag sio ptr)]
                 (if (== type-id ser/SCALAR_BLOCK_TYPE_ID)
                   (alloc/scalar-block->value sio ptr)
                   (if-let [ctor (ser/get-header-constructor type-id)]
                     (ctor sio ptr)
                     (throw (ex-info "mmap-atom: unknown root value type-id"
                                     {:type-id type-id :ptr ptr}))))))
       :default (let [type-id (alloc/read-header-tag sio ptr)]
                  (if (== type-id ser/SCALAR_BLOCK_TYPE_ID)
                    (alloc/scalar-block->value sio ptr)
                    (if-let [ctor (ser/get-header-constructor type-id)]
                      (ctor sio ptr)
                      (throw (ex-info "mmap-atom: unknown root value type-id"
                                      {:type-id type-id :ptr ptr}))))))))

(defn- scalar-leaf?
  "True when v is a host scalar leaf — a number, string, keyword, symbol,
   boolean, char, date or UUID: a value no IEveRoot / IBackingArray
   implementor and no serialize builder can claim (convert-to-eve answers
   nil for scalars by construction). The swap! dispatch screens its
   protocol checks with this: for a scalar, each NEGATIVE satisfies?
   re-walks the type hierarchy (find-protocol-impl: supers set + lazy
   impl scan), which priced most of a scalar swap. The screen is by what
   a value cannot be claimed AS, never by class-name prefix: screening
   by namespace was the DS11 JVM defect — an eve-deftype in a user
   namespace (datascript.db/DB) is an IEveRoot in its own class, and a
   name screen silently published its atom roots through a scalar block,
   so the tally never counted (never held, never freed) the tree beneath
   and seal/reach could not see it. Non-scalars keep the full dispatch;
   non-JVM seats keep it unconditionally (their satisfies? does not
   walk). The :jank arm below is written first on purpose: jank's
   feature set includes :clj, but its analyzer cannot resolve the
   bare java.lang names the :clj arm names (Number, String, Boolean,
   Character), so that arm must never be read on the jank seat —
   jank takes the :default behavior explicitly."
  [v]
  #?(:jank false
     :clj (boolean (or (instance? Number v)
                       (instance? String v)
                       (instance? Boolean v)
                       (instance? Character v)
                       (instance? clojure.lang.Keyword v)
                       (instance? clojure.lang.Symbol v)
                       (instance? java.util.Date v)
                       (instance? java.util.UUID v)))
     :default false))

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
      ;; convert-to-eve's builders match Eve values and plain
      ;; collections only; anything else is a scalar — skip the builder
      ;; loop (and its satisfies? preds) for it.
      (when (not (scalar-leaf? v))
        (some-> (ser/convert-to-eve sio v) d/-root-header-off)))))

(defn- write-typed-root!
  "A host typed array (or an IBackingArray value, its backing array) a swap!
   sets, written into sio as an eve array block; nil for any other value."
  [sio v]
  (cond
    (enc/host-array? v) (arr/write-host-array! sio v)
    (and (not (scalar-leaf? v)) (satisfies? d/IBackingArray v)) (arr/write-host-array! sio (d/-backing-array v) (d/-backing-subtype v))
    :else nil))

(defn- transfer-fn
  "eve.flat.transfer's entry points, resolved lazily: that namespace
   requires this one, so the foreign-policy branch reaches it the way
   eve.flat's own surface does — by requiring-resolve, never a static
   require (a cycle). A missing namespace is a named error, not a silent
   fall back to :throw."
  [sym]
  #?(:clj (let [v (try (requiring-resolve sym)
                       (catch Throwable _ nil))]
            (if v
              @v
              (throw (ex-info (str "swap!: the :zip foreign policy needs " sym ", which is not on the classpath")
                              {:eve.flat.transfer/not-built sym}))))
     :cljs (throw (ex-info (str "swap!: the :zip foreign policy needs " sym ", which is not built for this host")
                           {:eve.flat.transfer/not-built sym}))))

(defn- resolve-new-ptr
  "The slab offset a new root value lives at in sio: NIL for nil, the header
   of a slab-backed value of this domain (its own pointer: shared, not copied), the result of
   writing a plain collection, or a fresh scalar block. A slab-backed value of another domain
   follows the receiving domain's foreign policy: :throw (the default, today's
   error), :copy (materialized under the reader's pin, then written here) or
   :zip (lane T1: detached and placed block for block by eve.flat.transfer,
   no host collection built; under :zip a plain value holding foreign slab
   values nested inside it has each one placed first, so the plain writers
   never meet a foreign pointer)."
  ([sio new-val] (resolve-new-ptr sio new-val :throw))
  ([sio new-val foreign-policy]
   (cond
     (nil? new-val)
     alloc/NIL_OFFSET

     (and (not (scalar-leaf? new-val)) (satisfies? d/IEveRoot new-val))
     (let [vs (d/-eve-sio new-val)]
       (if (and vs (not (identical? (alloc/sio-root vs) (alloc/sio-root sio))))
         (case foreign-policy
           :copy (resolve-new-ptr sio (d/eve->clj new-val) :throw)
           :zip ((transfer-fn 'eve.flat.transfer/zip-root!) sio new-val)
           (throw (ex-info "swap!: Eve value was allocated in a different slab domain; construct it with the atom's domain sio (eve.atom/atom-sio)"
                           {:value-type (str (type new-val))})))
         (d/-root-header-off new-val)))

     :else
     (or (write-typed-root! sio new-val)
         (write-plain-collection! sio (if (= :zip foreign-policy)
                                        ((transfer-fn 'eve.flat.transfer/zip-nested) sio new-val)
                                        new-val))
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
  "The zero queue's flush, throttled: the 256-slot pin scan runs when the last one is FLUSH_INTERVAL_MS old, or
   FLUSH_COMMIT_THRESHOLD commits ago with the queue past FLUSH_QUEUE_THRESHOLD, or the queue is past
   FLUSH_QUEUE_CAP. Only ever delays a free."
  [ds]
  (let [retire-q (:retire-q ds) flush-ts (:flush-ts ds) flush-n (:flush-n ds)]
    (let [now (proc/now-ms)
          n (vswap! flush-n inc)]
      (when (or (> (- now @flush-ts) FLUSH_INTERVAL_MS)
                (let [q (count @retire-q)]
                  (or (and (>= n FLUSH_COMMIT_THRESHOLD) (> q FLUSH_QUEUE_THRESHOLD))
                      (> q FLUSH_QUEUE_CAP))))
        (reset! flush-ts now)
        (vreset! flush-n 0)
        (flush-zero-queue! ds)))))

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

(defonce ^:private deref-deliver-hook
  ;; The flat delivery deref uses (eve.flat.freeze's deliver-flat):
  ;; freeze registers it at load, and deref-deliver below also resolves
  ;; it lazily — eve.atom cannot require freeze (freeze requires atom),
  ;; and delivery must not depend on whether the caller happened to
  ;; require freeze first. ::none caches "not on the classpath".
  (clojure.core/atom nil))

(defn register-deref-deliver!
  "eve.flat.freeze's registration of the flat delivery deref uses:
   a fn of one argument, the raw root value read under the pin,
   answering the delivered flat object (which owns its bytes)."
  [f]
  (reset! deref-deliver-hook f))

(defn- deref-deliver
  "The delivery fn for flat-deref, or nil when eve.flat.freeze is not
   on the classpath (deref then falls back to plain-deref — Eve
   without flat behaves exactly as before). Reached the way
   transfer-fn reaches eve.flat.transfer: requiring-resolve, never a
   static require."
  []
  (let [h @deref-deliver-hook]
    (cond
      (fn? h) h
      (= ::none h) nil
      :else #?(:cljs nil
               :clje nil
               :default (let [v (try (requiring-resolve 'eve.flat.freeze/deliver-flat)
                                      (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))]
                          (if v
                            (do (reset! deref-deliver-hook @v) @v)
                            (do (reset! deref-deliver-hook ::none) nil)))))))

(defn- plain-deref
  "The atom's value as plain data (d/eve->clj), read under a pinned epoch.
   Slab-backed wrappers must not escape: the epoch is unpinned on return.
   The freeze load is ensured BEFORE the pin, in flat-deref's order:
   a value embedding frozen subtrees decodes through the serialize
   registry, whose 0x20 constructor eve.flat.freeze registers at
   load, and inside the pin that load cannot run on every host —
   measured on bb-source, where SCI's namespace load fails under
   the pinned epoch / lock-only region context and the codec's own
   lazy resolver answers nil there, so a freeze-less process's
   plain deref threw \"no constructor registered\" even though the
   identical resolve succeeds at top level and in a bare decode
   (tag-32 verification narrowing, 2026-11-16). deref-deliver is
   this file's existing ensure; its value is not used here — plain
   data is this path's contract — and with freeze absent it caches
   ::none and this path behaves exactly as before."
  [{:keys [root-r sio] :as ds} atom-slot-idx]
  (deref-deliver)
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

(declare read-root-value)

(defn- view-deref
  "deref outside `$` (and outside a swap!), where views are built: the root
   read under a pin and answered as a view that holds a pin of its own until
   the GC collects it (eve.view)."
  [{:keys [root-r sio] :as ds} atom-slot-idx]
  (refresh-slabs! ds)
  (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
  (try
    (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET))]
      (refresh-slabs! ds)
      (read-answer ds atom-slot-idx (read-root-value sio ptr)))
    (finally
      (unpin-epoch! ds))))

(defn- flat-deref
  "The atom's value as a flat object (FLAT-IN-ATOM R5): read under a
   pinned epoch, the root converted by the registered delivery INSIDE
   the pin — the snapshot discipline plain-deref's materialization
   obeys, so a concurrent swap retiring the old root cannot tear the
   delivery. What crosses the pin boundary owns its bytes: no pin, no
   epoch, no coupling to the domain's lifetime. With no delivery
   registered (eve.flat.freeze not loaded), plain-deref answers."
  [{:keys [root-r sio] :as ds} atom-slot-idx]
  (if-let [deliver (deref-deliver)]
    (do (refresh-slabs! ds)
        (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
        (try
          (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET))]
            ;; the root may name blocks another process allocated after
            ;; growing a class past the refresh above: re-check behind
            ;; the load, exactly as plain-deref does
            (refresh-slabs! ds)
            (deliver (read-root-value sio ptr)))
          (finally
            (unpin-epoch! ds))))
    (plain-deref ds atom-slot-idx)))

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
  "deref: the atom's value as a flat object (flat-deref, FLAT-IN-ATOM R5);
   inside a `$`, the lazy value (region-deref); inside a guard extent
   (step-2 prototype, JVM), the lazy value behind the guard (guard-deref).
   Deref delivers the flat form on every host — there is no plain
   opt-out: materialization is the caller's explicit eve->clj, never a
   deref mode (operator ruling, 2026-11-16)."
  [ds atom-slot-idx]
  (cond
    (rr/active?) (region-deref ds atom-slot-idx)
    #?@(:bb [] :glj [] :clj [(guard/active?) (guard-deref ds atom-slot-idx)])
    (and (some? (view-read-fn)) (nil? d/*parent-atom*)) (view-deref ds atom-slot-idx)
    :else (flat-deref ds atom-slot-idx)))

;; ---------------------------------------------------------------------------
;; The trunk watch surface — registries, delivery, the notify hook.
;; Spec: the atoms consolidation lane's watch-surface spec (clauses 1–6).
;; ---------------------------------------------------------------------------

(defonce ^:private watch-notify-listeners
  ;; Notify listeners installed by adapters (spec clause 5): a listener is
  ;; (fn [ds atom-slot-idx old new]), called with the DELIVERED old/new on
  ;; every successful swap in the domain, after the atom's own watchers.
  ;; Registered the way deref-deliver is — eve.atom cannot require the
  ;; adapter (eve.sab), so the adapter installs at its load. The wait/notify
  ;; machinery itself lives on the adapter's side of the seam.
  (clojure.core/atom []))

(defn register-notify-listener!
  "Install a notify listener (spec clause 5): (f ds atom-slot-idx old new),
   called with the delivered old/new values on every successful swap in
   the domain. For region/adapter cross-worker signaling (eve.sab).
   Returns f."
  [f]
  (swap! watch-notify-listeners conj f)
  f)

(defn- watches-for
  [ds atom-slot-idx]
  (when-let [w (:watches ds)]
    (get @w atom-slot-idx)))

(defn- validator-for
  [ds atom-slot-idx]
  (when-let [v (:validators ds)]
    (get @v atom-slot-idx)))

(defn- watched?
  "True when a successful swap of this atom must compute delivered old/new
   values: the atom has watchers, or a notify listener is installed."
  [ds atom-slot-idx]
  (or (boolean (seq (watches-for ds atom-slot-idx)))
      (boolean (seq @watch-notify-listeners))))

(defn- deliver-value
  "Notify delivery of a raw root value read under the pin: the deref law's
   funnel, minus deref's read contexts (region/guard) — the flat form via
   the registered deliver-flat; the eve->clj materialization only when no
   delivery is registered (Eve without flat). The caller holds the pin;
   what this returns owns its bytes."
  [ds atom-slot-idx raw]
  (if-let [deliver (deref-deliver)]
    (deliver raw)
    (d/eve->clj raw)))

(defn- illegal-state
  [msg]
  #?(:lpy (IllegalStateException. msg)
     :jank (ex-info msg {:eve.atom/illegal-state true})
     :clj (IllegalStateException. msg)
     :cljr (System.InvalidOperationException. msg)
     :cljs (js/Error. msg)
     :default (ex-info msg {:eve.atom/illegal-state true})))

(declare notify-watchers!)

;; ---------------------------------------------------------------------------
;; The trunk watch core — THE one watch/validator implementation.
;; The registry ops, the validator check and the firing loop live here
;; once. eve.atom's surface below and eve.stm's ref surface both run on
;; these fns; what differs per surface is residence state (where the
;; registry lives), delivery (each surface delivers what its deref
;; delivers) and scheduling (the atom fires per successful swap, after
;; its write hold releases; STM folds a transaction's writes and fires
;; once per written ref at commit). Callback shape everywhere:
;; (cb key target old new); callback exceptions propagate to the
;; committer, and the committed change stands.
;; ---------------------------------------------------------------------------

(defn watch-add
  "Registry op: the watches map with cb installed under key k."
  [watches k f]
  (assoc watches k f))

(defn watch-remove
  "Registry op: the watches map with key k removed."
  [watches k]
  (dissoc watches k))

(defn validator-check!
  "Run validator vf against the value about to be committed: a false
   result throws the illegal-state abort (\"Invalid reference state\")
   and nothing is installed or notified; vf's own exception propagates
   the same way. A nil vf passes. Returns true when the value stands."
  [vf v]
  (when (and (some? vf) (not (vf v)))
    (throw (illegal-state "Invalid reference state")))
  true)

(defn fire-watches!
  "Fire a watches map ({key cb}) at target with old/new:
   (cb key target old new), in map order, exceptions propagating
   to the caller."
  [watches target old new]
  (doseq [[k cb] watches]
    (cb k target old new)))

(declare swap-attempt-vals!)

(defn- swap-attempt!
  "One CAS attempt: read the old root, apply f, write the new value, CAS the
   root pointer. Returns [:ok new-val delivery] or [:retry nil ::none] —
   delivery is [old-delivered new-delivered], the deref-law deliveries of
   the old and new roots computed under this attempt's pin — the new one
   always (swap!'s return), the old one when the atom is watched or
   want-vals? asks (spec clause 6: the :ok tuple threads the old root's
   delivered value through), else ::none. Runs under the
   epoch pin for the whole attempt — the old value is lazy, and the commit's
   hold reads the new tree (round 2, 0008). exclusive? is the call
   convention with-domain-write hands the attempt (in the count-pages
   design a plan that raised a count returned [:hold nil] and the write
   ran again under the domain's hold); a descriptor count is a CAS word on
   the descriptor row, safe to raise from any write, so no attempt ever
   needs the hold and there is no [:hold nil] return."
  [ds
   ptr-off
   atom-slot-idx
   f
   args
   exclusive?]
  (swap-attempt-vals! ds ptr-off atom-slot-idx f args exclusive? false))

(defn- swap-attempt-vals!
  [ds
   ptr-off
   atom-slot-idx
   f
   args
   exclusive?
   want-vals?]
  (let [root-r (:root-r ds) sio (:sio ds) durable-commits? (:durable-commits? ds) journal (:journal ds)]
    (refresh-slabs! ds)
    (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
  ;; the touched set feeds the durable sweeps alone (every read of it below is inside a durable-commits? arm):
  ;; an in-memory domain binds none, and tally/note-touch! is one nil check (it was a set conj per mutation)
    ;; jolt: an in-memory domain under no outer touched set skips the binding (its value inside is nil either way;
    ;; a binding was ~1.5% of atom-swap-counter there, its frame map built on every attempt); a redo-log commit also
    ;; notes the blocks eve-deftype fields are written into in place (alloc/*inplace*, jlog-plan)
    (let [attempt (fn []
                    (try
                      (let [old-ptr (mem/-load-i32 root-r ptr-off)
                            _ (refresh-slabs! ds)  ;; behind the load (0004/0007)
                            lsio (alloc/logging-sio sio)  ;; this attempt's allocations
                            old-val (read-root-value lsio old-ptr)
                            new-val (binding [d/*parent-atom* ds
                                              d/*domain-sio* lsio]
                                      (apply f old-val args))
                              ;; the validator (spec clause 6 — new mechanism, Clojure-shaped):
                              ;; runs on the new value pre-commit; false or an exception
                              ;; aborts the swap, unwinding through the pin's finally exactly
                              ;; as a throwing f does — nothing is written, nothing notifies
                            _ (validator-check! (validator-for ds atom-slot-idx) new-val)
                              ;; written in the attempt's domain too: a codec that allocates a nested collection in the running domain
                              ;; (ClojureScript's) logs it here, so a lost CAS frees what the attempt allocated unheld
                            new-ptr (binding [d/*domain-sio* lsio]
                                      (resolve-new-ptr lsio new-val
                                                       (some-> (:foreign-policy ds) deref)))]
                        (if (== new-ptr old-ptr)
                            ;; the same value: nothing to publish, nothing to retire — but
                            ;; the swap SUCCEEDED, so a watched atom still notifies (spec
                            ;; clause 2: notification is not gated by the change decision)
                            ;; swap!'s return is the new value delivered flat (the value
                            ;; model: what leaves an atom is flat), under this attempt's pin
                          (let [delivery (let [w? (or want-vals? (watched? ds atom-slot-idx))
                                               dv (when w? (deliver-value ds atom-slot-idx old-val))]
                                           [dv dv (read-answer ds atom-slot-idx old-val)])]
                              ;; nothing published: whatever f allocated on the way to the old value is held by nothing
                            (tally/free-unreached! sio (alloc/logged-allocs lsio))
                            (alloc/stop-logging! lsio)
                            [:ok new-val delivery])
                            ;; the slot's hold of new, taken before the CAS publishes it (eve.tally/hold-root!)
                          (let [_ (tally/hold-root! sio new-ptr)
                                  ;; a journaled commit: its allocations (read before the log stops), how it is logged (jlog-plan:
                                  ;; its redo's run of slots, or the forced fallback) and — after any fallback force, before its
                                  ;; CAS — its log seq (jlog-commit!)
                                journaled? (jlog? ds)
                                allocs (when journaled? (alloc/logged-allocs lsio))
                                plan (when journaled? (jlog-plan sio (:log journal) allocs @tally/*touches* @alloc/*inplace*))
                                fallback? (= :fallback plan)
                                jcnt (if (map? plan) (:cnt plan) 1)
                                _ (when fallback?
                                    (alloc/sync-touched! sio (-> @tally/*touches* (into (take-unforced! ds)) (into allocs)
                                                                 (into @alloc/*inplace*))))
                                  ;; durability (a disk domain with :durable-commits?): everything this
                                  ;; commit wrote is forced BEFORE the root pointer can publish it, so a
                                  ;; system crash cannot leave a root pointing at value pages that never
                                  ;; reached disk. The force covers the slab classes this attempt
                                  ;; touched — its logged allocations and the descriptor rows its
                                  ;; tally holds mutated (alloc/sync-touched!) — not the whole slab.
                                  ;; Inside the attempt, so a lost CAS wastes one force and the
                                  ;; retry writes and forces its own blocks; nothing is held across the retry.
                                  ;; The force-ordering form only (durable, no journal): a journaled commit
                                  ;; forces nothing here unless it takes the fallback (above) — its record
                                  ;; carries its redo.
                                _ (when (and durable-commits? (nil? journal))
                                    (alloc/sync-touched! sio
                                                         (into (into @tally/*touches* (take-unforced! ds))
                                                               (alloc/logged-allocs lsio))))
                                jseq (when journaled? (jlog-reserve! ds false jcnt))
                                w (mem/-cas-i32! root-r ptr-off old-ptr new-ptr)]
                            (if (== w old-ptr)
                              (let [label (i32/i32 (inc (mem/-add-i32! root-r d/ROOT_EPOCH_OFFSET 1)))]
                                  ;; what the attempt allocated that the new root does not reach (the swap function's intermediates)
                                  ;; is held by nothing: freed now, before the drop and the durable sweeps below, which cover these
                                  ;; classes through the logged allocations they already name
                                (tally/free-unreached! sio (alloc/logged-allocs lsio))
                                (cond
                                  journaled?
                                  (jlog-commit! ds jseq jcnt label atom-slot-idx old-ptr new-ptr plan fallback?)

                                  :else
                                  (do  ;; the force-ordering form (durable, no journal): the epoch is made durable before
                                      ;; swap! returns — the root's own range, the epoch word through the slot pointer,
                                      ;; is forced after the CAS
                                    (when durable-commits?
                                      (mem/sync-region! root-r
                                                        (min ptr-off d/ROOT_EPOCH_OFFSET)
                                                        (+ 4
                                                           (- (max ptr-off d/ROOT_EPOCH_OFFSET)
                                                              (min ptr-off d/ROOT_EPOCH_OFFSET)))))
                                      ;; the slot lets old go: a block at 0 is parked at the epoch now
                                    (tally/drop-root! sio old-ptr (park-fn ds))
                                      ;; the drop's descriptor writes are forced too (the root
                                      ;; force above covered the root's own range only)
                                    (when (and durable-commits? (nil? journal))
                                      (alloc/sync-touched! sio (into @tally/*touches* (alloc/logged-allocs lsio))))))
                                  ;; delivery computed HERE, under the attempt's pin: the old
                                  ;; root is retired by this commit, so converting it after
                                  ;; the pin drops would read retired blocks (the discipline
                                  ;; flat-deref's in-pin conversion obeys)
                                  ;; the new value delivered flat always (swap!'s return); the
                                  ;; old one only when a watcher or swap-vals! reads it
                                  ;; [old new] flat when a watcher or swap-vals! reads them;
                                  ;; swap!'s answer (a view, or flat) for the new root always
                                (let [w? (or want-vals? (watched? ds atom-slot-idx))
                                      new-raw (read-root-value sio new-ptr)
                                      delivery [(when w? (deliver-value ds atom-slot-idx old-val))
                                                (when w? (deliver-value ds atom-slot-idx new-raw))
                                                (read-answer ds atom-slot-idx new-raw)]]
                                  (alloc/stop-logging! lsio)
                                  [:ok new-val delivery]))
                              (let [offs (alloc/logged-allocs lsio)]
                                  ;; a redo-log reservation: its slot is closed with an ABORT record
                                (when journaled? (jlog-abort! ds jseq jcnt))
                                  ;; never published: the hold goes back (new's blocks reach 0 and are freed by the flush, what they
                                  ;; share with other values keeps its count), and every other block this attempt allocated that no
                                  ;; holder took is freed now (a value the attempt stored into another atom is that atom's)
                                  ;; (read before the log stops: a stopped log reads empty)
                                (alloc/stop-logging! lsio)
                                (tally/drop-root! sio new-ptr (park-fn ds))
                                (tally/free-unheld! sio offs)
                                  ;; the frees' and drop's writes are forced even on a lost
                                  ;; attempt (the pre-CAS force covered the allocations, not
                                  ;; their taking-back). Journal domains skip this force:
                                  ;; nothing was published, and the lagging allocator writes
                                  ;; are forced by the flush sweep of whichever commit's
                                  ;; record first references a reused block (J1: that
                                  ;; commit's touched sweep; J10/checkpoint: the whole-domain
                                  ;; sweep) and by the retire flush that recycles the blocks
                                  ;; — a crash before then reads the blocks as still
                                  ;; allocated, the leak direction only.
                                (when (and durable-commits? (nil? journal))
                                  (alloc/sync-touched! sio (into @tally/*touches* offs)))
                                [:retry nil ::none])))))
                      (finally (unpin-epoch! ds))))
          inplace (when (jlog? ds) (clojure.core/atom #{}))]
      #?(:jolt (if (or durable-commits? (some? tally/*touches*))
                 (binding [tally/*touches* (when durable-commits? (clojure.core/atom #{}))
                           alloc/*inplace* inplace]
                   (attempt))
                 (attempt))
         :default (binding [tally/*touches* (when durable-commits? (clojure.core/atom #{}))
                            alloc/*inplace* inplace]
                    (attempt))))))

(defn- mmap-swap!
  "CAS-loop swap. Accepts any slab-backed value of this domain, a plain
   collection, a scalar or nil. A successful attempt notifies the atom's
   watchers with the delivered old/new its :ok tuple threaded through
   (spec clauses 2–4) — here, outside the domain write hold, so a
   callback that swaps cannot deadlock the domain. It answers the new value
   delivered flat under the attempt's pin (the value model: what leaves an
   atom is flat); with want-vals?, [old new], both delivered flat
   (swap-vals!, reset-vals!)."
  ([ds atom-slot-idx f args] (mmap-swap! ds atom-slot-idx f args false))
  ([ds atom-slot-idx f args want-vals?]
  (let [ptr-off (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET)]
    (loop [attempt 0]
      (when (>= attempt d/MAX_SWAP_RETRIES)
        (throw (ex-info "mmap-atom swap!: max retries exceeded" {:attempts attempt})))
      (let [[tag result delivery] (with-domain-write ds false
                                    (fn [exclusive?]
                                      (let [r (if want-vals?
                                                (swap-attempt-vals! ds ptr-off atom-slot-idx f args exclusive? true)
                                                (swap-attempt! ds ptr-off atom-slot-idx f args exclusive?))]
                                        (when (= :ok (first r))
                                          (try-flush-retires! ds))
                                        r)))]
        (case tag
          :ok (do (when (and (not= ::none delivery) (watched? ds atom-slot-idx))
                    (notify-watchers! ds atom-slot-idx
                                      (nth delivery 0) (nth delivery 1)))
                  (cond want-vals? [(nth delivery 0) (nth delivery 1)]
                        (= ::none delivery) result
                        :else (nth delivery 2)))
          :retry (do (cas-backoff! attempt)
                     (recur (inc attempt)))))))))

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
                  (let [hit? (= (d/eve->clj cur) (d/eve->clj old-val))]
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

(defn persistent?
  "True for a value inside an atom: slab-backed, rooted in a domain (built in
   a swap!, or read inside a swap! or a $ region). False for flat data — what
   every exit from an atom delivers, and what eve constructors give outside
   every atom (the value model, docs/ds-in-eve/EVE-FIX-PLAN.md §0)."
  [x]
  (boolean
   (and (some? x)
        (not (or (string? x) (number? x) (keyword? x) (symbol? x) (boolean? x)))
        (satisfies? d/IEveRoot x)
        (satisfies? d/IEveSio x)
        (some? (d/-eve-sio x))
        (not (== -1 (d/-root-header-off x))))))

(defn eve->clj
  "Eve data as this host's Clojure data (eve->cljs's rule, on every host):
   eve maps, vectors, sets, lists → host collections, recursively; an
   eve-deftype → its flat instance. Inside an atom, storing the result back
   makes it eve data again; outside, it runs on flat data."
  [v]
  (d/eve->clj v))

(defn same?
  "Inside an atom (a swap! fn or a $ region): true when x and y are the same
   block of the same domain. Outside every atom values have no identity, and
   same? throws (:eve/no-identity)."
  [x y]
  (when-not (or (rr/active?) (some? d/*parent-atom*))
    (throw (ex-info "eve/same?: values have no identity outside an atom (use it inside swap! or $)"
                    {:type :eve/no-identity})))
  (boolean
   (and (persistent? x) (persistent? y)
        (identical? (alloc/sio-root (d/-eve-sio x)) (alloc/sio-root (d/-eve-sio y)))
        (== (d/-root-header-off x) (d/-root-header-off y)))))

(defn- env-setting
  "A setting from the environment (NAME) or a JVM system property (prop),
   else nil."
  [name prop]
  #?(:clj (or (System/getenv name) (System/getProperty prop))
     :cljs (when (and (exists? js/process) (.-env js/process))
             (aget (.-env js/process) name))
     :default nil))

(defonce ^:private region-off
  ;; EVE_REGION=off / eve.region=off: `$` runs its body as a plain call —
  ;; the measurement of how much code needs `$` (its reads answer views,
  ;; ended by EVE_READ_END's rule)
  (clojure.core/atom (= "off" (env-setting "EVE_REGION" "eve.region"))))

(defn- region-off? [] @region-off)

(defn set-region-off!
  "true: `$` runs its body as a plain call (measurement only)."
  [off?]
  (reset! region-off off?))

(defn $*
  "`$`'s region (eve.alpha/$ expands to it): (f) with every eve atom deref'd inside answering its lazy, slab-backed
   value under a pin held until the outermost region exits; f's result materialized (d/eve->clj, lazy seqs
   realized) before the pins go. A region inside another joins it."
  [f]
  (if (region-off?)
    (d/eve->clj (f))
    (rr/run f d/eve->clj)))

;; PROTOTYPE (step 2)
(defn set-foreign-policy!
  "What swap! on a's domain does with a slab-backed value of another domain:
   :throw (default), :copy (materialize it through the host and write it
   here) or :zip (lane T1: detach and place it block for block, no host
   collection built — eve.flat.transfer)."
  [a policy]
  {:pre [(#{:throw :copy :zip} policy)]}
  (reset! (:foreign-policy (atom-domain-state a)) policy))

;; jolt: the refresh's test inline (refresh-slabs! is a call and two map lookups on every read); the epoch load, the
;; pin and the pointer load in one call where the pin table has the root's base (pt-pin-read!*), else pin-epoch! and
;; -load-i32 as on every host; (f v) where there are no args (apply builds the arg list)
#?(:jolt
   (defn- read-pinned-jolt [a f args]
     (let [ds (atom-domain-state a)
           sio (:sio ds)
           base-path (:base-path ds)
           slot (-atom-slot-idx a)
           pt (pt-of (:pins ds))]
       (when base-path (alloc/refresh! sio))
       (if-let [ptr (pt-pin-read!* pt d/ROOT_EPOCH_OFFSET (d/atom-slot-offset slot d/ATOM_SLOT_PTR_OFFSET))]
         (try
           (when base-path (alloc/refresh! sio))
           (if (nil? args) (f (read-root-value sio ptr)) (apply f (read-root-value sio ptr) args))
           (finally
             (pt-unpin-pub!* pt)))
         (let [root-r (:root-r ds)]
           (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
           (try
             (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset slot d/ATOM_SLOT_PTR_OFFSET))]
               (when base-path (alloc/refresh! sio))
               (if (nil? args) (f (read-root-value sio ptr)) (apply f (read-root-value sio ptr) args)))
             (finally
               (unpin-epoch! ds))))))))

(defn read-pinned
  "Bounded slab-backed access to one atom: (f value & args) with the atom's
   value as the lazy slab-backed Eve value, read under this thread's pinned
   epoch, which is unpinned when f returns — f's result must not keep the
   value. eve's own reads use it; a program reads with eve.alpha/$."
  #?@(:jolt
      ;; jolt: a fixed arity for (read-pinned a f) (the rest arity's argument list was counted on every read)
      [([a f] (read-pinned-jolt a f nil))
       ([a f & args] (read-pinned-jolt a f args))]
      :default
      [[a f & args]
       (let [{:keys [root-r sio] :as ds} (atom-domain-state a)
             slot (-atom-slot-idx a)]
         (refresh-slabs! ds)
         (pin-epoch! ds (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET))
         (try
           (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset slot d/ATOM_SLOT_PTR_OFFSET))]
             (refresh-slabs! ds)
             (apply f (read-root-value sio ptr) args))
           (finally
             (unpin-epoch! ds))))]))

;; ---------------------------------------------------------------------------
;; Watches and validators — the public trunk surface (spec clauses 1–3, 6).
;; The host's own add-watch / set-validator! reach the same impls through
;; MmapAtom's IRef section below; these fns are the every-host path
;; (eve.stm's ref surface delegates to the watch core above — one
;; implementation; STM adds only its storage and commit scheduling).
;; ---------------------------------------------------------------------------

(defn add-watch*
  "Add a watcher to an Eve atom: (f key atom old new) fires on every
   successful swap!/reset! (spec clauses 2–3), old/new delivered under
   the deref law — the flat form (clause 4).
   Returns the atom."
  [a k f]
  (let [slot (-atom-slot-idx a)]
    (swap! (:watches (atom-domain-state a))
           (fn [m] (assoc m slot (watch-add (get m slot) k f)))))
  a)

(defn remove-watch*
  "Remove the watcher under key k from an Eve atom. Returns the atom."
  [a k]
  (let [slot (-atom-slot-idx a)]
    (swap! (:watches (atom-domain-state a))
           (fn [m]
             (let [ws (watch-remove (get m slot) k)]
               (if (empty? ws) (dissoc m slot) (assoc m slot ws))))))
  a)

(defn get-watches
  "An Eve atom's watchers, {key cb}."
  [a]
  (get @(:watches (atom-domain-state a)) (-atom-slot-idx a) {}))

(defn set-validator*
  "Set an Eve atom's validator (spec clause 6): a fn of the new value,
   run pre-commit on every swap!/reset!/compare-and-set!* — false or an
   exception aborts the swap and nothing notifies. The validator sees
   the new value in the form swap functions traffic in (the Eve value f
   returned, or the value reset! was given). Setting one validates the
   atom's current value first (read raw under a pin, STM's shape): a
   current value it rejects throws and nothing is installed. nil clears
   the validator. Returns nil (clojure.core/set-validator!'s shape)."
  [a f]
  (when f
    (read-pinned a (fn [v] (validator-check! f v))))
  (swap! (:validators (atom-domain-state a)) assoc (-atom-slot-idx a) f)
  nil)

(defn get-validator
  "An Eve atom's validator fn, or nil."
  [a]
  (get @(:validators (atom-domain-state a)) (-atom-slot-idx a)))

;; ---------------------------------------------------------------------------
;; MmapAtom / MmapAtomDomain — the host's atom interfaces are the one
;; difference (cljs.core protocols vs clojure.lang interfaces); the bodies
;; are the same calls.
;; ---------------------------------------------------------------------------

;; One definition for every host, written with the JVM's names: each host's
;; types adapter realizes it (ClojureScript's ISwap/IReset, sci's own IAtom,
;; basilisp's duck-typed swap/reset, clojerl's through eve.host).
(eve/host-deftype MmapAtom [domain-state atom-slot-idx]
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
  clojure.lang.IAtom2
  (swapVals [_ f] (mmap-swap! domain-state atom-slot-idx f [] true))
  (swapVals [_ f a] (mmap-swap! domain-state atom-slot-idx f [a] true))
  (swapVals [_ f a b] (mmap-swap! domain-state atom-slot-idx f [a b] true))
  (swapVals [_ f a b xs] (mmap-swap! domain-state atom-slot-idx f (concat [a b] xs) true))
  (resetVals [_ v] (mmap-swap! domain-state atom-slot-idx (constantly v) [] true))
  ;; the watch/validator surface (spec clause 1): the host's add-watch /
  ;; set-validator! land here; the bodies are the trunk fns above, and
  ;; each host's types adapter realizes IRef the way it realizes
  ;; IDeref/IAtom (basilisp's and glojure's completions map
  ;; clojure.lang.IRef; a seat with no IRef home uses the trunk fns)
  clojure.lang.IRef
  (addWatch [this k f] (add-watch* this k f))
  (removeWatch [this k] (remove-watch* this k))
  (setValidator [this f] (set-validator* this f))
  (getValidator [this] (get-validator this))
  IEveAtom
  (-domain-state [_] domain-state)
  (-atom-slot-idx [_] atom-slot-idx)
  ;; (a printer reaches the instance's fields through it: on a JVM host it
  ;; is the type's print-method, outside the type)
  IPrintWithWriter
  (-pr-writer [this writer _opts]
    (let [i (-atom-slot-idx this)]
      (ecore/-write writer (str "#eve/shared-atom {:id " i " :idx " i "}"))))
  ;; str is the print form, stated in the CLJ/JVM API directly (the fake
  ;; deftype derived toString from the printer; host-deftype does not)
  java.lang.Object
  (toString [this]
    (let [i (-atom-slot-idx this)]
      (str "#eve/shared-atom {:id " i " :idx " i "}"))))

(defn- notify-watchers!
  "Fire a slot's watchers with the delivered old/new (spec clauses 2–4:
   (cb key atom old new)), then the registered notify listeners
   (clause 5). The atom argument is the canonical handle for the slot —
   (MmapAtom. ds slot), the same object domain-lookup returns. Callback
   exceptions propagate to the swapper, as in Clojure."
  [ds atom-slot-idx old-delivered new-delivered]
  (let [a (MmapAtom. ds atom-slot-idx)]
    (fire-watches! (watches-for ds atom-slot-idx) a old-delivered new-delivered)
    (doseq [listener @watch-notify-listeners]
      (listener ds atom-slot-idx old-delivered new-delivered))))

(defn compare-and-set!*
  "compare-and-set! for an Eve atom on every host (CLJS's core
   compare-and-set! is for its own Atom type)."
  [a old-val new-val]
  (mmap-compare-and-set! (atom-domain-state a)
                         (-atom-slot-idx a)
                         old-val new-val))

;; SharedAtom compat alias (tests reference a/SharedAtom)
(def SharedAtom MmapAtom)

(eve/host-deftype MmapAtomDomain [domain-state registry-cache]
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
  clojure.lang.IAtom2
  (swapVals [_ f] (mmap-swap! domain-state 0 f [] true))
  (swapVals [_ f a] (mmap-swap! domain-state 0 f [a] true))
  (swapVals [_ f a b] (mmap-swap! domain-state 0 f [a b] true))
  (swapVals [_ f a b xs] (mmap-swap! domain-state 0 f (concat [a b] xs) true))
  (resetVals [_ v] (mmap-swap! domain-state 0 (constantly v) [] true))
  ;; the domain is slot 0's atom: the same watch/validator surface
  clojure.lang.IRef
  (addWatch [this k f] (add-watch* this k f))
  (removeWatch [this k] (remove-watch* this k))
  (setValidator [this f] (set-validator* this f))
  (getValidator [this] (get-validator this))
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

(def MEM-BUDGET-DESKTOP
  "The desktop per-domain in-mem budget default: 2 GB. A developer-settable
   DEFAULT for a domain's :mem-budget — pass it (or your own byte count) to
   persistent-atom-domain / atom-domain; nothing applies it for you, and it
   is a parameter, not a ceiling compiled into the allocator: growth past
   it is refused with a named error, never the host's hard OOM."
  (* 2 1024 1024 1024))

(def MEM-BUDGET-BROWSER
  "The browser per-domain in-mem budget default: 1 GB. See MEM-BUDGET-DESKTOP."
  (* 1024 1024 1024))

(def MEM-BUDGET-WORKERD
  "The Cloudflare workerd per-domain in-mem budget default: 48 MB (the
   platform's ~64 MB envelope leaves the runtime its share). See
   MEM-BUDGET-DESKTOP."
  (* 48 1024 1024))

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
   over the other's writes).

   Durability is the shape of a disk domain: the domain is
   JOURNALED — commits append a record to
   <base>.journal and the journal force is the commit point; the .root
   file is a checkpoint (cleanup-sweep journal verdict, 2026-11-07: the
   disk tier is journaled by design). :mem-budget (bytes) caps the domain's total
   region growth — refused growth names the budget, never the host's
   out-of-memory; the MEM-BUDGET-* constants are the per-target defaults.
   :segment-bytes (a power of two) sets the region mapping window —
   presentation, not format: it is stored nowhere in the domain's
   files, and processes may join the same domain with different
   windows.

   The journal is <base>.jlog, the mmapped commit log (eve.jlog;
   eve-mmap-journal-plan §4): a fixed-size ring of 4 KiB record slots claimed
   by CAS — one implementation on every host, no file lock. It is a redo log
   (doc/jlog-redo-spec.md): a commit's record carries its delta and the bytes
   it wrote, and one device flush makes it durable. :jlog-slots (a power of
   two, default 1024) sizes it at creation. A legacy domain's <base>.journal
   is migrated on the first open no other process holds (and renamed
   <base>.journal.migrated)."
  [base-path & {:keys [capacities lustre? durable? journal-batch journal-window-ms coalesc-descriptors mem-budget segment-bytes
                       jlog-slots] :or {capacities {} lustre? false durable? true}}]
  (or (get @domain-cache base-path)
      (let [_  (proc/ensure-parent-dir! base-path)
            ds (open-mmap-domain! base-path (cond-> {:capacities capacities
                                                     :lustre? lustre?
                                                     :durable? durable?
                                                     :journal? (not (false? durable?))
                                                     :journal-batch journal-batch
                                                     :journal-window-ms journal-window-ms
                                                     :create? (proc/create-exclusive! (str base-path ".root"))}
                                              coalesc-descriptors
                                              (assoc :coalesc-descriptors coalesc-descriptors)
                                              mem-budget
                                              (assoc :mem-budget mem-budget)
                                              segment-bytes
                                              (assoc :segment-bytes segment-bytes)
                                              jlog-slots (assoc :jlog-slots jlog-slots)))
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
  [{:keys [root-r rmap-r sio] :as ds}]
  (take-unforced! ds)
  (alloc/sync-sio! sio)
  (mem/sync-region! rmap-r)
  (mem/sync-region! root-r))

(defn- journal-checkpoint!
  "A checkpoint (sync!, close): on a journaled domain jlog-checkpoint! (every commit before it durable, the whole
   domain forced, an ANCHOR folded from the ring — no truncation, no rewrite), then the reader map and the root
   forced. Without a journal this is sync-domain!."
  [ds]
  (let [journal (:journal ds)]
    (if journal
      (do (reset! (:pending journal) 0)
          (jlog-checkpoint! ds)
          (mem/sync-region! (:rmap-r ds))
          (mem/sync-region! (:root-r ds)))
      (sync-domain! ds))))

(defn- release-domain! [ds]
  (let [{:keys [root-r slot-idx heartbeat closed? base-path]} ds]
    (when (and root-r slot-idx (not @closed?))
      (reset! closed? true)
      ;; a disk domain is forced whole before it is let go: nothing a
      ;; commit wrote is left riding the page cache at close; a journal
      ;; domain anchors its log as it closes.
      (when base-path (journal-checkpoint! ds))
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
  "Close the domain this atom belongs to: release the worker slot, stop
   the heartbeat, and — for a disk domain — forget the domain, so
   reopening its path opens it fresh from disk (close-atom-domain!'s
   semantics, addressed by atom). Idempotent: a second close is a
   no-op. Atoms of the domain are invalid after this."
  [a]
  (let [{:keys [base-path] :as ds} (atom-domain-state a)]
    (release-domain! ds)
    (when base-path
      (swap! domain-cache dissoc base-path))))

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
               (fn [_] (claim-atom-slot! root-r (fnv-1a-hash kw-str))))
        ;; a redo log keeps the claim (the slot's CLAIMED pointer and name hash) durable before the slot is used
        _ (when (jlog? ds) (jlog-note-claim! ds mine))]
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
                                   bs (slab/read-bytes sio off 0 (if (< cls 6) (d/slab-size cls) 32))]
                               (reduce (fn [h i] (fnv-fold h (bit-and (aget ^bytes bs i) 0xFF)))
                                       (fnv-fold h off)
                                       (range (alength ^bytes bs)))))
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
        domain (resolve-domain opts)
        a (lookup-or-create-mmap-atom! domain (atom-name (:id opts)) value)]
    a))

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
   MmapAtomDomain. Domain options (:capacities, :coalesc-descriptors,
   :mem-budget) pass through to the domain's slab I/O."
  [initial-value & {:as opts}]
  (let [ds (open-heap-domain! opts)
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
