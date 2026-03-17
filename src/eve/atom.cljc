(ns eve.atom
  "Cross-process persistent atom backed by mmap slab files.

   B2 architecture: HAMT nodes live in slab files (.slab0–.slab5).
   The root pointer in .root is a slab-qualified i32 [class:3|block:29].
   A swap! does O(log n) slab allocations then CASes the root pointer.

   No .main file. No block descriptor table. No flat serialization.

   Phase 7: universal root types (any Eve collection, scalar, or nil).
   Phase 6 extends: epoch GC, cross-process acceptance test."
  (:refer-clojure :exclude [atom])
  #?(:bb
     (:require
      [eve.deftype-proto.coalesc :as coalesc]
      [eve.deftype-proto.data :as d]
      [eve.deftype-proto.alloc :as alloc]
      [eve.deftype-proto.serialize :as ser]
      [eve.mem :as mem]
      [eve.map :as eve-map]
      [eve.set :as eve-set]
      [eve.vec :as eve-vec]
      [eve.list :as eve-list]
      [eve.perf :as perf])
     :cljs
     (:require
      [eve.deftype-proto.coalesc :as coalesc]
      [eve.deftype-proto.data :as d]
      [eve.deftype-proto.alloc :as alloc]
      [eve.deftype-proto.serialize :as ser]
      [eve.mem :as mem]
      [eve.map :as eve-map]
      [eve.wasm-mem :as wasm-mem]
      [eve.vec]
      [eve.set]
      [eve.list])
     :clj
     (:require
      [eve.deftype-proto.coalesc :as coalesc]
      [eve.deftype-proto.data :as d]
      [eve.deftype-proto.alloc :as alloc]
      [eve.deftype-proto.serialize :as ser]
      [eve.mem :as mem]
      [eve.map :as eve-map]
      [eve.set]
      [eve.vec]
      [eve.list]
      [eve.array :as eve-array]
      [eve.obj :as eve-obj]
      [eve.perf :as perf])))

#?(:bb nil :clj (import '[eve.map EveHashMap]))

;; ---------------------------------------------------------------------------
;; B2 constants
;; Root pointer format: alloc/encode-slab-offset [class:3 | block:29]
;; NOT data/pack-slab-ptr [class:8 | block:24] — that encoding is wrong here.
;; ---------------------------------------------------------------------------

(def ^:const ROOT_BYTES d/ROOT_FILE_SIZE_V2) ;; 8320 (V2 multi-atom)
(def ^:const READER_MAP_BYTES 262144)

;; OBJ-1: Time-throttled retire flush — skip the expensive 256-slot scan
;; when the last scan was recent enough. Correctness: we only delay freeing,
;; never free too early. Retire queue grows bounded by threshold.
(def ^:const FLUSH_INTERVAL_MS 50)
(def ^:const FLUSH_QUEUE_THRESHOLD 64)

;; OBJ-4: CAS retry backoff — jittered exponential backoff on CAS failure.
;; Reduces thundering herd under contention (8+ writers).
;; Zero overhead when CAS succeeds on the first attempt.
(def ^:const BACKOFF_CAP_MS 8)

(def ^:const DEFAULT_ATOM_SLOT 1) ;; slot 0 = registry, slot 1 = default atom
(def ^:const CLAIMED_SENTINEL -2) ;; "claimed but no value" slot marker

;; ---------------------------------------------------------------------------
;; BB materializer registration — nested collections in slab memory.
;; SAB pointer tags 0x10-0x13 in eve-bytes->value need constructors that
;; materialize to plain Clojure data (bb can't implement Java interfaces).
;; ---------------------------------------------------------------------------

#?(:bb
   (do
     (ser/register-jvm-type-constructor!
      0x10 0xED ;; SAB_MAP pointer → HAMT map header
      (fn [header-off]
        (let [sio alloc/*jvm-slab-ctx*
              [_cnt root-off] (eve-map/read-map-header sio header-off)]
          (eve-map/hamt-kv-reduce sio root-off
                                  (fn [m k v] (assoc m k v)) {}))))
     (ser/register-jvm-type-constructor!
      0x11 0xEE ;; SAB_SET pointer → HAMT set header
      (fn [header-off]
        (let [sio alloc/*jvm-slab-ctx*
              [_cnt root-off] (eve-set/read-set-header sio header-off)]
          (eve-set/hamt-val-reduce sio root-off
                                   (fn [s v] (conj s v)) #{}))))
     (ser/register-jvm-type-constructor!
      0x12 0x12 ;; SAB_VEC pointer → vec trie header
      (fn [header-off]
        (let [sio alloc/*jvm-slab-ctx*
              [cnt shift root tail _tail-len] (eve-vec/read-vec-header sio header-off)]
          (loop [i 0 acc (transient [])]
            (if (>= i cnt)
              (persistent! acc)
              (recur (inc i) (conj! acc (eve-vec/nth-impl sio cnt shift root tail i))))))))
     (ser/register-jvm-type-constructor!
      0x13 0x13 ;; SAB_LIST pointer → linked list header
      (fn [header-off]
        (let [sio alloc/*jvm-slab-ctx*
              [cnt head-off] (eve-list/read-list-header sio header-off)]
          (loop [off head-off i 0 acc (transient [])]
            (if (or (>= i cnt) (== off alloc/NIL_OFFSET))
              (apply list (persistent! acc))
              (recur (eve-list/read-node-next sio off)
                     (inc i)
                     (conj! acc (eve-list/read-node-value sio off))))))))))

;; ---------------------------------------------------------------------------
;; Worker slot helpers — operate on mmap root-r, NOT the SAB @root-region
;; ---------------------------------------------------------------------------

(defn- worker-slot-offset [slot-idx field-offset]
  (+ d/ROOT_WORKER_REGISTRY_START (* slot-idx d/WORKER_SLOT_SIZE) field-offset))

(defn- write-heartbeat!
  "Write the current time as a 64-bit timestamp split into two i32s."
  [root-r slot-idx]
  (let [now #?(:cljs (js/Date.now) :default (System/currentTimeMillis))]
    (mem/-store-i32! root-r
                     (worker-slot-offset slot-idx d/OFFSET_WS_HEARTBEAT_LO)
                     #?(:cljs (bit-and now 0xFFFFFFFF)
                        :default (unchecked-int (bit-and now 0xFFFFFFFF))))
    (mem/-store-i32! root-r
                     (worker-slot-offset slot-idx d/OFFSET_WS_HEARTBEAT_HI)
                     #?(:cljs (unsigned-bit-shift-right now 32)
                        :default (unchecked-int (unsigned-bit-shift-right now 32))))))

(defn- heartbeat-stale?
  "True if the heartbeat timestamp for slot-idx is older than HEARTBEAT_TIMEOUT_MS."
  [root-r slot-idx]
  (let [lo (mem/-load-i32 root-r (worker-slot-offset slot-idx d/OFFSET_WS_HEARTBEAT_LO))
        hi (mem/-load-i32 root-r (worker-slot-offset slot-idx d/OFFSET_WS_HEARTBEAT_HI))
        ts #?(:cljs (+ (* (unsigned-bit-shift-right hi 0) 0x100000000)
                       (unsigned-bit-shift-right lo 0))
              :default (bit-or (bit-shift-left (bit-and (long hi) 0xFFFFFFFF) 32)
                               (bit-and (long lo) 0xFFFFFFFF)))
        now #?(:cljs (js/Date.now) :default (System/currentTimeMillis))]
    (> (- now ts) d/HEARTBEAT_TIMEOUT_MS)))

(defn- mmap-claim-slot!
  "CAS-scan the worker registry to claim the first INACTIVE slot.
   If all slots are occupied, does a second pass reclaiming stale ACTIVE slots.
   Stores ACTIVE and zeroes CURRENT_EPOCH. Returns slot-idx.
   Throws if all 256 slots are occupied and none are stale."
  [root-r]
  (or
   ;; First pass: look for INACTIVE slots
   (loop [i 0]
     (when (< i d/MAX_WORKERS)
       (let [status-off (worker-slot-offset i d/OFFSET_WS_STATUS)
             witness (mem/-cas-i32! root-r status-off
                                    d/WORKER_STATUS_INACTIVE
                                    d/WORKER_STATUS_ACTIVE)]
         (if (== witness d/WORKER_STATUS_INACTIVE)
           (do (mem/-store-i32! root-r
                                (worker-slot-offset i d/OFFSET_WS_CURRENT_EPOCH) 0)
               i)
           (recur (inc i))))))
   ;; Second pass: reclaim stale ACTIVE slots
   (loop [i 0]
     (when (< i d/MAX_WORKERS)
       (let [status (mem/-load-i32 root-r (worker-slot-offset i d/OFFSET_WS_STATUS))]
         (if (and (== status d/WORKER_STATUS_ACTIVE)
                  (heartbeat-stale? root-r i))
           (let [witness (mem/-cas-i32! root-r (worker-slot-offset i d/OFFSET_WS_STATUS)
                                        d/WORKER_STATUS_ACTIVE d/WORKER_STATUS_ACTIVE)]
             (if (== witness d/WORKER_STATUS_ACTIVE)
               (do (mem/-store-i32! root-r
                                    (worker-slot-offset i d/OFFSET_WS_CURRENT_EPOCH) 0)
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
  "Announce this process is reading at epoch. Call BEFORE reading root ptr."
  [root-r slot-idx epoch]
  (mem/-store-i32! root-r (worker-slot-offset slot-idx d/OFFSET_WS_CURRENT_EPOCH) epoch))

(defn- mmap-unpin-epoch!
  "Clear the read-epoch announcement. Call AFTER deref completes (in finally)."
  [root-r slot-idx]
  (mem/-store-i32! root-r (worker-slot-offset slot-idx d/OFFSET_WS_CURRENT_EPOCH) 0))

(defn- mmap-min-safe-epoch
  "Scan the worker registry. Return the minimum CURRENT_EPOCH among all
   ACTIVE slots that are currently pinned (CURRENT_EPOCH != 0).
   Returns nil if no slot is pinned — meaning all retired entries are safe."
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
;; CLJS domain open/join
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     ;; OBJ-4: Tiny SAB used by Atomics.wait for sub-ms CAS backoff sleep.
     (def ^:private backoff-i32
       (js/Int32Array. (js/SharedArrayBuffer. 4)))

     (defn- cas-backoff!
       "Jittered exponential backoff after CAS failure.
        First 3 failures retry immediately (handles low contention without penalty).
        Failures 4+ use 1..min(2^(n-3),8) ms Atomics.wait to break thundering herd."
       [attempt]
       (when (> attempt 3)
         (let [max-ms (min (bit-shift-left 1 (min (- attempt 3) 3)) BACKOFF_CAP_MS)
               ms (inc (rand-int max-ms))]
           (js/Atomics.wait backoff-i32 0 0 ms))))

     (defn- cljs-open-mmap-domain!
       "Create and initialise mmap-backed atom domain files.
        Opens/creates: {base}.slab0–.slab5, {base}.root, {base}.rmap.
        Writes .root header. Returns domain-state map.
        NOTE: Replaces the module-level slab instances.
              A process using the mmap atom cannot simultaneously use
              the intra-process SAB atom."
       [base-path & {:keys [capacities] :or {capacities {}}}]
       ;; Disable alloc-debug-set for mmap atoms (cross-process bitmap CAS is the guard)
       (set! eve-map/mmap-mode? true)
       ;; 1. Create/init 6 bitmap slab files (data + bitmap) + 1 coalescing slab
       ;;    Uses initial (small) capacities by default for lazy growth.
       (dotimes [i d/NUM_SLAB_CLASSES]
         (let [cap (get capacities i (d/initial-capacity-for-class i))]
           (alloc/init-mmap-slab! i (str base-path ".slab" i)
                                  :capacity cap)))
       (alloc/init-mmap-coalesc! (str base-path ".slab6"))
       ;; 2. Open .root and .rmap
       (let [root-r (mem/open-mmap-region (str base-path ".root") ROOT_BYTES)
             rmap-r (mem/open-mmap-region (str base-path ".rmap") READER_MAP_BYTES)]
         ;; 3. Write .root header (V2)
         (mem/store-i32! root-r d/ROOT_MAGIC_OFFSET d/ROOT_MAGIC_V2)
         (mem/store-i32! root-r d/ROOT_ATOM_PTR_OFFSET 0) ;; now: atom slot count
         (mem/store-i32! root-r d/ROOT_EPOCH_OFFSET 1)
         (mem/store-i32! root-r d/ROOT_WORKER_REG_OFFSET d/ROOT_WORKER_REGISTRY_START)
         (mem/store-i32! root-r d/ROOT_ATOM_TABLE_OFFSET d/ATOM_TABLE_START)
         (mem/store-i32! root-r d/ROOT_ATOM_TABLE_CAPACITY d/MAX_ATOM_SLOTS)
         ;; 4. Init worker slots
         (dotimes [slot d/MAX_WORKERS]
           (mem/store-i32! root-r
                           (+ d/ROOT_WORKER_REGISTRY_START (* slot d/WORKER_SLOT_SIZE))
                           d/WORKER_STATUS_INACTIVE))
         ;; 4b. Write atom table header and init all slots
         (mem/store-i32! root-r d/ATOM_TABLE_HEADER_START d/ATOM_TABLE_MAGIC)
         (mem/store-i32! root-r (+ d/ATOM_TABLE_HEADER_START 4) 0) ;; slot count
         (dotimes [s d/MAX_ATOM_SLOTS]
           (mem/store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_PTR_OFFSET)
                           alloc/NIL_OFFSET)
           (mem/store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_HASH_OFFSET) 0))
         ;; Init slot 0 (registry atom) — starts with NIL_OFFSET (empty registry)
         (mem/store-i32! root-r (d/atom-slot-offset 0 d/ATOM_SLOT_PTR_OFFSET)
                         alloc/NIL_OFFSET)
         ;; 5. Claim a slot for this process
         (let [slot-idx (mmap-claim-slot! root-r)]
           (write-heartbeat! root-r slot-idx)
           (let [timer-id (js/setInterval #(write-heartbeat! root-r slot-idx) 10000)]
             (.on js/process "exit"
                  (fn [_] (mmap-release-slot! root-r slot-idx)))
             {:root-r root-r :rmap-r rmap-r :base-path base-path
              :slot-idx slot-idx :timer-id timer-id
              :retire-q (cljs.core/atom [])
              :flush-ts (doto (make-array 1) (aset 0 0))}))))

     (defn- cljs-join-mmap-domain!
       "Open existing mmap-backed atom domain files.
        Opens: {base}.slab0–.slab5, {base}.root, {base}.rmap.
        NOTE: Replaces the module-level slab instances."
       [base-path]
       ;; Disable alloc-debug-set for mmap atoms (cross-process bitmap CAS is the guard)
       (set! eve-map/mmap-mode? true)
       (dotimes [i d/NUM_SLAB_CLASSES]
         (alloc/open-mmap-slab! i (str base-path ".slab" i)))
       (alloc/open-mmap-coalesc! (str base-path ".slab6"))
       (let [root-r (mem/open-mmap-region (str base-path ".root") ROOT_BYTES)
             _ (let [magic (mem/-load-i32 root-r d/ROOT_MAGIC_OFFSET)]
                 (when (not= magic d/ROOT_MAGIC_V2)
                   (throw (ex-info "mmap-atom: root file has wrong magic (old format?)"
                                   {:expected d/ROOT_MAGIC_V2 :actual magic}))))
             rmap-r (mem/open-mmap-region (str base-path ".rmap") READER_MAP_BYTES)
             slot-idx (mmap-claim-slot! root-r)]
         (write-heartbeat! root-r slot-idx)
         (let [timer-id (js/setInterval #(write-heartbeat! root-r slot-idx) 10000)]
           (.on js/process "exit"
                (fn [_] (mmap-release-slot! root-r slot-idx)))
           {:root-r root-r :rmap-r rmap-r :base-path base-path
            :slot-idx slot-idx :timer-id timer-id
            :retire-q (cljs.core/atom [])
            :flush-ts (doto (make-array 1) (aset 0 0))})))

     (defn- cljs-open-sab-domain!
       "Create an in-memory SAB-backed atom domain on CLJS.
        Uses SharedArrayBuffers for slab backing (same as the existing WASM slab
        allocator) plus a SAB-backed root region and rmap region.
        Returns domain-state map compatible with cljs-mmap-deref/cljs-mmap-swap!.
        NOTE: Replaces the module-level slab instances unless :skip-init true."
       [& {:keys [capacities skip-init] :or {capacities {} skip-init false}}]
       ;; 1. Initialize 6 slab classes backed by SABs (via existing init!)
       ;;    init! calls init-slab! for each class which creates WASM-backed SABs.
       ;;    If skip-init is true, assume slabs were already initialized by caller.
       (let [slab-promises (when-not skip-init
                             (alloc/init! :capacities capacities :force true))]
         ;; 2. Create root region as SAB-backed JsSabRegion
         (let [root-sab (js/SharedArrayBuffer. ROOT_BYTES)
               root-r (mem/js-sab-region root-sab)
               rmap-sab (js/SharedArrayBuffer. READER_MAP_BYTES)
               rmap-r (mem/js-sab-region rmap-sab)]
           ;; 3. Write .root header (V2 format — multi-atom with atom table)
           (mem/store-i32! root-r d/ROOT_MAGIC_OFFSET d/ROOT_MAGIC_V2)
           (mem/store-i32! root-r d/ROOT_ATOM_PTR_OFFSET 0)
           (mem/store-i32! root-r d/ROOT_EPOCH_OFFSET 1)
           (mem/store-i32! root-r d/ROOT_WORKER_REG_OFFSET d/ROOT_WORKER_REGISTRY_START)
           (mem/store-i32! root-r d/ROOT_ATOM_TABLE_OFFSET d/ATOM_TABLE_START)
           (mem/store-i32! root-r d/ROOT_ATOM_TABLE_CAPACITY d/MAX_ATOM_SLOTS)
           ;; 4. Init worker slots
           (dotimes [slot d/MAX_WORKERS]
             (mem/store-i32! root-r
                             (+ d/ROOT_WORKER_REGISTRY_START (* slot d/WORKER_SLOT_SIZE))
                             d/WORKER_STATUS_INACTIVE))
           ;; 4b. Write atom table header and init all slots
           (mem/store-i32! root-r d/ATOM_TABLE_HEADER_START d/ATOM_TABLE_MAGIC)
           (mem/store-i32! root-r (+ d/ATOM_TABLE_HEADER_START 4) 0) ;; slot count
           (dotimes [s d/MAX_ATOM_SLOTS]
             (mem/store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_PTR_OFFSET)
                             alloc/NIL_OFFSET)
             (mem/store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_HASH_OFFSET) 0))
           ;; Init slot 0 (registry atom)
           (mem/store-i32! root-r (d/atom-slot-offset 0 d/ATOM_SLOT_PTR_OFFSET)
                           alloc/NIL_OFFSET)
           ;; 5. Claim a worker slot (no heartbeat needed — single process in-memory)
           (let [slot-idx (mmap-claim-slot! root-r)]
             (write-heartbeat! root-r slot-idx)
             {:root-r root-r :rmap-r rmap-r :base-path nil
              :slot-idx slot-idx :timer-id nil
              :retire-q (cljs.core/atom [])
              :flush-ts (doto (make-array 1) (aset 0 0))
              ;; Keep SABs for transfer to workers
              :root-sab root-sab :rmap-sab rmap-sab
              :slab-promises slab-promises}))))

     (defn- cljs-mmap-deref
       "Read the current atom value from the .root file.
        Pins epoch before reading root ptr to protect against epoch GC.
        Returns an Eve type instance (or nil) based on the header type-id byte."
       [{:keys [root-r slot-idx base-path]} atom-slot-idx]
       ;; Refresh slab regions in case another process grew them (mmap only)
       (when base-path (alloc/refresh-mmap-slabs!))
       (let [epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
         (mmap-pin-epoch! root-r slot-idx epoch)
         (try
           (let [ptr (mem/-load-i32 root-r (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET))]
             (when (and (not= ptr alloc/NIL_OFFSET)
                        (not= ptr CLAIMED_SENTINEL))
               (let [type-id (alloc/read-header-type-byte ptr)]
                 (if (== type-id ser/SCALAR_BLOCK_TYPE_ID)
                   (alloc/read-scalar-block ptr)
                   (let [ctor (ser/get-header-constructor type-id)]
                     (if ctor
                       (ctor nil ptr)
                       (throw (ex-info "mmap-atom: unknown root value type-id"
                                       {:type-id type-id :ptr ptr}))))))))
           (finally
             (mmap-unpin-epoch! root-r slot-idx)))))

     (defn- cljs-try-flush-retires!
       "Flush retired slab offsets whose epoch is safe to reclaim.
        OBJ-1: Skip the 256-slot scan when the last scan was recent (< FLUSH_INTERVAL_MS)
        and the queue is small (< FLUSH_QUEUE_THRESHOLD). This eliminates up to 768
        N-API crossings per swap in the common case."
       [root-r retire-q flush-ts]
       (let [now (js/Date.now)
             last-t (aget flush-ts 0)
             q @retire-q
             q-len (count q)]
         (when (or (> (- now last-t) FLUSH_INTERVAL_MS)
                   (> q-len FLUSH_QUEUE_THRESHOLD))
           (aset flush-ts 0 now)
           (let [safe-epoch (mmap-min-safe-epoch root-r)
                 grouped (group-by (fn [{:keys [epoch]}]
                                     (or (nil? safe-epoch) (< epoch safe-epoch)))
                                   q)
                 to-free (get grouped true)
                 still-live (get grouped false)]
             (doseq [{:keys [offsets]} to-free]
               (doseq [off offsets]
                 (when (not= off alloc/NIL_OFFSET)
                   (eve-map/untrack-debug-offset! off)
                   (alloc/free! off))))
             (reset! retire-q (or still-live []))))))

     (defn- cljs-resolve-new-ptr
       "Resolve the slab-qualified offset for a new atom root value.
        Returns alloc/NIL_OFFSET for nil, header-off for IEveRoot types,
        converts CLJS native types via builders, or allocates a scalar block."
       [new-val]
       (cond
         (nil? new-val)
         alloc/NIL_OFFSET

         ;; Already an Eve type in the slab — use its header-off directly
         (satisfies? d/IEveRoot new-val)
         (d/-root-header-off new-val)

         ;; CLJS native type (PersistentHashMap, PersistentVector, etc.)
         ;; — convert via the existing cljs-to-sab-builders registry
         :else
         (let [eve-val (ser/convert-to-sab new-val)]
           (if eve-val
             (d/-root-header-off eve-val)
             ;; Not a collection — treat as scalar primitive
             (alloc/alloc-scalar-block! new-val)))))

     (defn- cljs-mmap-swap!
       "B2 CAS-loop swap. Accepts any Eve type, CLJS native type, scalar, or nil.
        On CAS failure, frees the newly-allocated value (safe: not yet published).
        On CAS success, retires old ptr via epoch GC retire queue.
        Binds *parent-atom* so nested collection allocation (vec/set/list alloc-node!)
        passes the atomic-context guard.

        IMPORTANT: epoch is pinned for the ENTIRE swap iteration (read + apply f +
        CAS) to prevent the JVM from freeing slab blocks that the lazy Eve value
        still references.  cljs-mmap-deref is NOT used here because it unpins in
        its finally block, which is too early — applying f to the lazy old-val would
        access slab data with the epoch unpinned."
       [{:keys [root-r slot-idx retire-q flush-ts base-path] :as domain-state} atom-slot-idx f args]
       (let [ptr-off (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET)]
         (loop [attempt 0]
           (when (>= attempt d/MAX_SWAP_RETRIES)
             (throw (ex-info "mmap-atom swap!: max retries exceeded" {:attempts attempt})))
         ;; Refresh slab regions in case another process grew them (mmap only)
           (when base-path (alloc/refresh-mmap-slabs!))
         ;; Pin epoch for the entire iteration — protects lazy old-val reads
           (let [epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
             (mmap-pin-epoch! root-r slot-idx epoch)
             (let [old-ptr (mem/-load-i32 root-r ptr-off)
                   old-val (when (and (not= old-ptr alloc/NIL_OFFSET)
                                      (not= old-ptr CLAIMED_SENTINEL))
                             (let [type-id (alloc/read-header-type-byte old-ptr)]
                               (if (== type-id ser/SCALAR_BLOCK_TYPE_ID)
                                 (alloc/read-scalar-block old-ptr)
                                 (let [ctor (ser/get-header-constructor type-id)]
                                   (if ctor
                                     (ctor nil old-ptr)
                                     (throw (ex-info "mmap-atom swap!: unknown root type-id"
                                                     {:type-id type-id :ptr old-ptr})))))))
                 ;; Bind *parent-atom* so nested collection builders (vec/set/list)
                 ;; can allocate HAMT nodes in slabs during the user function.
                   [new-val new-ptr]
                   (binding [d/*parent-atom* domain-state]
                     (let [nv (apply f old-val args)]
                       [nv (cljs-resolve-new-ptr nv)]))
                   w (mem/-cas-i32! root-r ptr-off old-ptr new-ptr)]
             ;; Unpin epoch — CAS is done, old tree traversal is complete
               (mmap-unpin-epoch! root-r slot-idx)
               (if (== w old-ptr)
                 (let [new-epoch (mem/-add-i32! root-r d/ROOT_EPOCH_OFFSET 1)]
                   (when (and (not= old-ptr alloc/NIL_OFFSET)
                              (not= old-ptr CLAIMED_SENTINEL))
                   ;; Collect offsets to free NOW (both trees are live),
                   ;; but defer the actual freeing until the epoch is safe.
                     (let [offsets (if (instance? eve-map/EveHashMap old-val)
                                     (eve-map/collect-retire-diff-offsets old-val new-val)
                                     [old-ptr])]
                     ;; Untrack from debug-set immediately so subsequent allocs
                     ;; don't false-positive when these offsets are re-used
                       (doseq [off offsets]
                         (eve-map/untrack-debug-offset! off))
                       (swap! retire-q conj {:offsets offsets
                                             :epoch (inc new-epoch)})))
                   (cljs-try-flush-retires! root-r retire-q flush-ts)
                   new-val)
                 (do (when (and (not= new-ptr alloc/NIL_OFFSET)
                                (not= new-ptr old-ptr))
                     ;; CAS failed — free the abandoned new value immediately.
                     ;; The new nodes were never published, so no reader can see them.
                       (if (instance? eve-map/EveHashMap new-val)
                         (d/-sab-retire-diff! new-val
                                              (when (instance? eve-map/EveHashMap old-val) old-val)
                                              nil :free)
                       ;; Non-Eve type was converted to fresh tree — just free header
                         (alloc/free! new-ptr)))
                     (cas-backoff! attempt)
                     (recur (inc attempt)))))))))))

;; ---------------------------------------------------------------------------
;; JVM domain open/join
;; ---------------------------------------------------------------------------

#?(:cljs nil
   :default
   (do
     (defn- cas-backoff!
       "Jittered exponential backoff after CAS failure (JVM).
        First 3 failures retry immediately. Failures 4+ use LockSupport/parkNanos
        with 100μs..min(2^(n-3),8)ms jitter to break thundering herd."
       [attempt]
       (when (> attempt 3)
         (let [max-ms (min (bit-shift-left 1 (min (- attempt 3) 3)) BACKOFF_CAP_MS)]
           #?(:bb (Thread/sleep (inc (rand-int max-ms)))
              :clj (let [nanos (* (inc (rand-int (* max-ms 1000))) 1000)]
                     (java.util.concurrent.locks.LockSupport/parkNanos nanos))))))

     (defn- jvm-open-mmap-domain!
       "Create and initialise mmap-backed atom domain on JVM.
        Opens/creates .slab0–.slab5 as JvmMmapRegion, wraps in JvmSlabCtx.
        Opens .root and .rmap. Writes .root header. Returns domain-state map.
        Uses initial (small) capacities by default for lazy growth."
       [base-path & {:keys [capacities] :or {capacities {}}}]
       (let [slab-paths (mapv #(str base-path ".slab" %) (range d/NUM_SLAB_CLASSES))
             bm-paths (mapv #(str base-path ".slab" % ".bm") (range d/NUM_SLAB_CLASSES))
             init-result
             (mapv (fn [i]
                     (let [block-size (nth d/SLAB_SIZES i)
                           init-cap (get capacities i (d/initial-capacity-for-class i))
                           layout (d/mmap-slab-layout block-size init-cap)
                           init-blocks (:total-blocks layout)
                           data-bytes (:data-bytes layout)
                           bm-bytes (:bitmap-bytes layout)
                           region (mem/open-mmap-region (nth slab-paths i) data-bytes)
                           bm-region (mem/open-mmap-region (nth bm-paths i) bm-bytes)]
                       (mem/-store-i32! region d/SLAB_HDR_MAGIC d/SLAB_MAGIC)
                       (mem/-store-i32! region d/SLAB_HDR_BLOCK_SIZE block-size)
                       (mem/-store-i32! region d/SLAB_HDR_TOTAL_BLOCKS init-blocks)
                       (mem/-store-i32! region d/SLAB_HDR_FREE_COUNT init-blocks)
                       (mem/-store-i32! region d/SLAB_HDR_ALLOC_CURSOR 0)
                       (mem/-store-i32! region d/SLAB_HDR_CLASS_IDX i)
                       (mem/-store-i32! region d/SLAB_HDR_BITMAP_OFFSET 0)
                       (mem/-store-i32! region d/SLAB_HDR_DATA_OFFSET d/SLAB_HEADER_SIZE)
                       {:region region :bm-region bm-region}))
                   (range d/NUM_SLAB_CLASSES))
             regions (mapv :region init-result)
             bm-regions (mapv :bm-region init-result)
             ;; Class 6: coalescing overflow allocator — lazy growth
             coalesc-init-sz coalesc/INITIAL_DATA_SIZE
             coalesc-layout (coalesc/coalesc-layout coalesc-init-sz
                                                    coalesc/MAX_DESCRIPTORS)
             coalesc-r (mem/open-mmap-region
                        (str base-path ".slab6") (:total-bytes coalesc-layout))
             _ (coalesc/init-coalesc-region! coalesc-r
                                             coalesc-init-sz
                                             coalesc/MAX_DESCRIPTORS)
             regions-7 (conj regions coalesc-r)
             bm-regions-7 (conj bm-regions nil)
             paths-7 (conj slab-paths (str base-path ".slab6"))
             bm-paths-7 (conj bm-paths nil)
             sio (alloc/make-jvm-slab-ctx regions-7 bm-regions-7
                                          paths-7 bm-paths-7 nil)
             root-r (mem/open-mmap-region (str base-path ".root") ROOT_BYTES)
             rmap-r (mem/open-mmap-region (str base-path ".rmap") READER_MAP_BYTES)]
         (mem/-store-i32! root-r d/ROOT_MAGIC_OFFSET d/ROOT_MAGIC_V2)
         (mem/-store-i32! root-r d/ROOT_ATOM_PTR_OFFSET 0) ;; now: atom slot count
         (mem/-store-i32! root-r d/ROOT_EPOCH_OFFSET 1)
         (mem/-store-i32! root-r d/ROOT_ATOM_TABLE_OFFSET d/ATOM_TABLE_START)
         (mem/-store-i32! root-r d/ROOT_ATOM_TABLE_CAPACITY d/MAX_ATOM_SLOTS)
         ;; Init all worker slots to INACTIVE
         (dotimes [slot d/MAX_WORKERS]
           (mem/-store-i32! root-r
                            (+ d/ROOT_WORKER_REGISTRY_START (* slot d/WORKER_SLOT_SIZE))
                            d/WORKER_STATUS_INACTIVE))
         ;; Init atom table header and slots
         (mem/-store-i32! root-r d/ATOM_TABLE_HEADER_START d/ATOM_TABLE_MAGIC)
         (mem/-store-i32! root-r (+ d/ATOM_TABLE_HEADER_START 4) 0)
         (dotimes [s d/MAX_ATOM_SLOTS]
           (mem/-store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_PTR_OFFSET)
                            alloc/NIL_OFFSET)
           (mem/-store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_HASH_OFFSET) 0))
         (mem/-store-i32! root-r (d/atom-slot-offset 0 d/ATOM_SLOT_PTR_OFFSET)
                          alloc/NIL_OFFSET)
         (let [slot-idx (mmap-claim-slot! root-r)]
           (write-heartbeat! root-r slot-idx)
           #?(:bb
              {:root-r root-r :rmap-r rmap-r :sio sio :base-path base-path
               :slot-idx slot-idx :heartbeat-sched nil
               :retire-q (java.util.LinkedList.)
               :tree-logs (java.util.HashMap.)
               :flush-ts (volatile! 0)
               :thread-epochs (clojure.core/atom {})
               :pin-lock (Object.)}
              :clj
              (let [sched (doto (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
                                 (reify java.util.concurrent.ThreadFactory
                                   (newThread [_ r]
                                     (doto (Thread. r)
                                       (.setDaemon true)
                                       (.setName "eve-heartbeat")))))
                            (.scheduleAtFixedRate
                             #(write-heartbeat! root-r slot-idx)
                             10 10 java.util.concurrent.TimeUnit/SECONDS))]
                {:root-r root-r :rmap-r rmap-r :sio sio :base-path base-path
                 :slot-idx slot-idx :heartbeat-sched sched
                 :retire-q (java.util.concurrent.ConcurrentLinkedQueue.)
                 :tree-logs (java.util.concurrent.ConcurrentHashMap.)
                 :flush-ts (volatile! 0)
                 :thread-epochs (java.util.concurrent.ConcurrentHashMap.)
                 :pin-lock (Object.)})))))

     (defn- jvm-join-mmap-domain!
       "Open existing mmap-backed atom domain on JVM."
       [base-path]
       (let [slab-paths (mapv #(str base-path ".slab" %) (range d/NUM_SLAB_CLASSES))
             bm-paths (mapv #(str base-path ".slab" % ".bm") (range d/NUM_SLAB_CLASSES))
             open-result
             (mapv (fn [i]
                     (let [peek-r (mem/open-mmap-region (nth slab-paths i) 64)
                           total (mem/-load-i32 peek-r d/SLAB_HDR_TOTAL_BLOCKS)
                           bs (nth d/SLAB_SIZES i)
                           data-bytes (+ d/SLAB_HEADER_SIZE (* total bs))
                           bm-bytes (d/bitmap-byte-size total)]
                       {:region (mem/open-mmap-region (nth slab-paths i) data-bytes)
                        :bm-region (mem/open-mmap-region (nth bm-paths i) bm-bytes)}))
                   (range d/NUM_SLAB_CLASSES))
             regions (mapv :region open-result)
             bm-regions (mapv :bm-region open-result)
             ;; Class 6: coalescing overflow — peek header, open at current size
             coalesc-peek (mem/open-mmap-region (str base-path ".slab6") 64)
             coalesc-data-off (mem/-load-i32 coalesc-peek d/SLAB_HDR_DATA_OFFSET)
             coalesc-cur-sz (mem/-load-i64 coalesc-peek coalesc/COALESC_HDR_DATA_SIZE)
             coalesc-r (mem/open-mmap-region (str base-path ".slab6")
                                             (+ coalesc-data-off coalesc-cur-sz))
             regions-7 (conj regions coalesc-r)
             bm-regions-7 (conj bm-regions nil)
             paths-7 (conj slab-paths (str base-path ".slab6"))
             bm-paths-7 (conj bm-paths nil)
             sio (alloc/make-jvm-slab-ctx regions-7 bm-regions-7
                                          paths-7 bm-paths-7 nil)
             root-r (mem/open-mmap-region (str base-path ".root") ROOT_BYTES)
             _ (let [magic (mem/-load-i32 root-r d/ROOT_MAGIC_OFFSET)]
                 (when (not= magic d/ROOT_MAGIC_V2)
                   (throw (ex-info "mmap-atom: root file has wrong magic (old format?)"
                                   {:expected d/ROOT_MAGIC_V2 :actual magic}))))
             rmap-r (mem/open-mmap-region (str base-path ".rmap") READER_MAP_BYTES)
             slot-idx (mmap-claim-slot! root-r)]
         (write-heartbeat! root-r slot-idx)
         #?(:bb
            {:root-r root-r :rmap-r rmap-r :sio sio :base-path base-path
             :slot-idx slot-idx :heartbeat-sched nil
             :retire-q (java.util.LinkedList.)
             :tree-logs (java.util.HashMap.)
             :flush-ts (volatile! 0)
             :thread-epochs (clojure.core/atom {})
             :pin-lock (Object.)}
            :clj
            (let [sched (doto (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
                               (reify java.util.concurrent.ThreadFactory
                                 (newThread [_ r]
                                   (doto (Thread. r)
                                     (.setDaemon true)
                                     (.setName "eve-heartbeat")))))
                          (.scheduleAtFixedRate
                           #(write-heartbeat! root-r slot-idx)
                           10 10 java.util.concurrent.TimeUnit/SECONDS))]
              {:root-r root-r :rmap-r rmap-r :sio sio :base-path base-path
               :slot-idx slot-idx :heartbeat-sched sched
               :retire-q (java.util.concurrent.ConcurrentLinkedQueue.)
               :tree-logs (java.util.concurrent.ConcurrentHashMap.)
               :flush-ts (volatile! 0)
               :thread-epochs (java.util.concurrent.ConcurrentHashMap.)
               :pin-lock (Object.)}))))

     (defn- jvm-pin-thread-epoch!
       "Pin epoch for the current JVM thread. Multiple threads share one worker
        slot, so we track per-thread epochs and write the MIN to the shared slot.
        This prevents a later thread's pin from masking an earlier thread's epoch."
       [{:keys [root-r slot-idx thread-epochs
                ^Object pin-lock]} epoch]
       #?(:bb
          (do (swap! thread-epochs assoc :main epoch)
              (mmap-pin-epoch! root-r slot-idx (int epoch)))
          :clj
          (locking pin-lock
            (.put ^java.util.concurrent.ConcurrentHashMap thread-epochs
                  (.getId (Thread/currentThread)) (Long/valueOf (long epoch)))
            (let [min-e (reduce min epoch (.values ^java.util.concurrent.ConcurrentHashMap thread-epochs))]
              (mmap-pin-epoch! root-r slot-idx (int min-e))))))

     (defn- jvm-unpin-thread-epoch!
       "Unpin epoch for the current JVM thread. Updates the shared slot to the
        MIN of remaining pinned threads, or clears it if no threads are pinned."
       [{:keys [root-r slot-idx thread-epochs
                ^Object pin-lock]}]
       #?(:bb
          (do (swap! thread-epochs dissoc :main)
              (mmap-unpin-epoch! root-r slot-idx))
          :clj
          (locking pin-lock
            (.remove ^java.util.concurrent.ConcurrentHashMap thread-epochs
                     (.getId (Thread/currentThread)))
            (if (.isEmpty ^java.util.concurrent.ConcurrentHashMap thread-epochs)
              (mmap-unpin-epoch! root-r slot-idx)
              (let [min-e (reduce min (.values ^java.util.concurrent.ConcurrentHashMap thread-epochs))]
                (mmap-pin-epoch! root-r slot-idx (int min-e)))))))

     (defn- jvm-read-root-value
       "Read the atom value from a root pointer. Caller must ensure epoch is pinned.
        CLJ: Returns slab-backed Eve types directly — no materialization.
        BB: Returns slab-backed types (BbEveHashMap etc.) — no materialization.
            Vec/List/Set fall back to plain Clojure (no bb deftype yet)."
       [sio ptr]
       (when (and (not= ptr alloc/NIL_OFFSET)
                  (not= ptr CLAIMED_SENTINEL))
         (let [type-id (alloc/jvm-read-header-type-byte sio ptr)]
           #?(:bb
              (case (int type-id)
                0x01 (alloc/jvm-read-scalar-block sio ptr)
                ;; Map — slab-backed BbEveHashMap (no materialization)
                0xED (eve-map/hash-map-from-header sio ptr)
                ;; Set — materialize to plain Clojure set (no bb deftype yet)
                0xEE (let [[_cnt root-off] (eve-set/read-set-header sio ptr)]
                       (eve-set/hamt-val-reduce sio root-off
                                                (fn [s v] (conj s v)) #{}))
                ;; Vec — materialize trie to plain Clojure vector
                0x12 (let [[cnt shift root tail _tail-len] (eve-vec/read-vec-header sio ptr)]
                       (loop [i 0 acc (transient [])]
                         (if (>= i cnt)
                           (persistent! acc)
                           (recur (inc i) (conj! acc (eve-vec/nth-impl sio cnt shift root tail i))))))
                ;; List — materialize linked list to plain Clojure list
                0x13 (let [[cnt head-off] (eve-list/read-list-header sio ptr)]
                       (loop [off head-off i 0 acc (transient [])]
                         (if (or (>= i cnt) (== off alloc/NIL_OFFSET))
                           (apply list (persistent! acc))
                           (recur (eve-list/read-node-next sio off)
                                  (inc i)
                                  (conj! acc (eve-list/read-node-value sio off))))))
                (throw (ex-info "jvm-mmap-deref: unknown root type-id"
                                {:type-id type-id :ptr ptr})))
              :clj
              (case (int type-id)
                0x01 (alloc/jvm-read-scalar-block sio ptr)
                ;; Look up by header type-id byte directly
                (if-let [ctor (ser/get-jvm-header-constructor type-id)]
                  (ctor ptr)
                  ;; Try array/obj constructors
                  (case (int type-id)
                    0x1D (eve-array/eve-array-from-offset sio ptr)
                    0x1E (eve-obj/jvm-obj-from-offset sio ptr)
                    (throw (ex-info "jvm-mmap-deref: unknown root type-id"
                                    {:type-id type-id :ptr ptr})))))))))

     (defn- jvm-mmap-deref
       [{:keys [root-r sio] :as domain-state} atom-slot-idx]
       ;; Refresh slab regions in case another process grew them
       (alloc/refresh-jvm-slab-regions! sio)
       (binding [alloc/*jvm-slab-ctx* sio]
         (let [epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
           (jvm-pin-thread-epoch! domain-state epoch)
           (try
             (jvm-read-root-value sio
                                  (mem/-load-i32 root-r (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET)))
             (finally
               (jvm-unpin-thread-epoch! domain-state))))))

     (defn- jvm-try-flush-retires!
       "Free retired HAMT trees whose epoch is safe to reclaim.
        OBJ-1: Skip the 256-slot scan when the last scan was recent enough."
       [root-r retire-q sio flush-ts]
       (let [now (System/currentTimeMillis)
             last-t @flush-ts
             q-len #?(:bb (count retire-q) :clj (.size ^java.util.Queue retire-q))]
         (when (or (> (- now last-t) FLUSH_INTERVAL_MS)
                   (> q-len FLUSH_QUEUE_THRESHOLD))
           (vreset! flush-ts now)
           (let [safe-epoch (mmap-min-safe-epoch root-r)
                 entries (java.util.ArrayList.)]
             (loop []
               (when-let [e #?(:bb (when (seq retire-q) (.removeFirst ^java.util.LinkedList retire-q))
                               :clj (.poll ^java.util.Queue retire-q))]
                 (.add entries e)
                 (recur)))
             (doseq [entry entries]
               (let [{:keys [offsets epoch]} entry]
                 (if (or (nil? safe-epoch) (< epoch safe-epoch))
                   (doseq [off offsets]
                     (when (not= off alloc/NIL_OFFSET)
                       (alloc/-sio-free! sio off)))
                   #?(:bb (.add ^java.util.List retire-q entry)
                      :clj (.add ^java.util.Queue retire-q entry)))))))))

     (defn- jvm-resolve-new-ptr
       "Resolve the slab-qualified offset for a new atom root value (JVM/bb).
        If new-val is already a slab-backed Eve type, returns its header-off
        directly (no re-serialization). Otherwise serializes to slab via the
        collection writer registry."
       [sio new-val]
       (cond
         (nil? new-val)
         alloc/NIL_OFFSET

         ;; Already an Eve type — use its header-off directly (via IEveRoot protocol)
         (satisfies? d/IEveRoot new-val)
         (d/-root-header-off new-val)

         ;; Clojure native types — serialize via collection writer registry
         (map? new-val)
         #?(:bb (mem/jvm-write-collection! :map sio new-val)
            :clj (if (and (contains? new-val :schema) (contains? new-val :values))
                   (alloc/jvm-write-obj! sio (:schema new-val) (:values new-val))
                   (mem/jvm-write-collection! :map sio new-val)))
         (set? new-val) (mem/jvm-write-collection! :set sio new-val)
         (vector? new-val) (mem/jvm-write-collection! :vec sio new-val)
         (or (list? new-val) (seq? new-val))
         (mem/jvm-write-collection! :list sio new-val)
         #?@(:clj [(.isArray (class new-val))
                   (alloc/jvm-write-eve-array! sio new-val)])
         :else
         (alloc/jvm-alloc-scalar-block! sio new-val)))

     (defn- jvm-collect-replaced-nodes
       "Walk old and new HAMT trees, collecting old node offsets that differ.
        Skips shared subtrees (same offset). For structural sharing after a
        single assoc, only O(log32 n) nodes differ."
       [sio old-off new-off]
       (let [nil-off (long alloc/NIL_OFFSET)
             result (java.util.ArrayList.)]
         (letfn [(walk [^long o ^long n]
                   (when (and (not= o nil-off) (not= o n))
                     (.add result o)
                     (when (== (long (alloc/-sio-read-u8 sio o 0)) 1)
                       (let [o-nbm (unchecked-int (alloc/-sio-read-i32 sio o 8))
                             n-tp (when (not= n nil-off)
                                    (long (alloc/-sio-read-u8 sio n 0)))
                             n-nbm (when (and n-tp (== (long n-tp) 1))
                                     (unchecked-int (alloc/-sio-read-i32 sio n 8)))]
                         (loop [rem o-nbm oi 0]
                           (when-not (zero? rem)
                             (let [bit (bit-and rem (- rem))
                                   oc (long (alloc/-sio-read-i32 sio o (+ 12 (* oi 4))))
                                   nc (if (and n-nbm
                                               (not (zero? (bit-and n-nbm bit))))
                                        (let [ni (Integer/bitCount
                                                  (unchecked-int
                                                   (bit-and (unchecked-int n-nbm)
                                                            (unchecked-int (dec bit)))))]
                                          (long (alloc/-sio-read-i32 sio n (+ 12 (* ni 4)))))
                                        nil-off)]
                               (walk oc nc)
                               (recur (bit-and rem (unchecked-int (dec rem)))
                                      (inc oi)))))))))]
           (walk old-off new-off))
         (vec result)))

     (defn- jvm-mmap-swap!
       "B2 CAS-loop swap (JVM/bb). Epoch pinned for the ENTIRE iteration to
        protect lazy EveHashMap reads during path-copy."
       [{:keys [root-r sio retire-q tree-logs flush-ts] :as domain-state} atom-slot-idx f args]
       (binding [alloc/*jvm-slab-ctx* sio]
       (let [ptr-off (d/atom-slot-offset atom-slot-idx d/ATOM_SLOT_PTR_OFFSET)]
       (loop [attempt 0]
         (when (>= attempt d/MAX_SWAP_RETRIES)
           (throw (ex-info "mmap-atom swap!: max retries exceeded" {:attempts attempt})))
         ;; Refresh slab regions in case another process grew them
         (perf/timed :refresh-regions (alloc/refresh-jvm-slab-regions! sio))
         (perf/timed :pin-epoch
                     (jvm-pin-thread-epoch! domain-state (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)))
         (let [[tag result]
               (try
                 (let [old-ptr (mem/-load-i32 root-r ptr-off)
                       old-val (perf/timed :read-root (jvm-read-root-value sio old-ptr))
                       _ (alloc/start-jvm-alloc-log!)
                       _ (alloc/start-jvm-replaced-log!)
                       new-val (perf/timed :apply-f (apply f old-val args))
                       new-ptr (perf/timed :resolve-ptr (jvm-resolve-new-ptr sio new-val))
                       cur-log (alloc/drain-jvm-alloc-log!)
                       replaced-log (alloc/drain-jvm-replaced-log!)
                       eve-passthru? (satisfies? d/IEveRoot new-val)
                       w (perf/timed :cas (mem/-cas-i32! root-r ptr-off old-ptr new-ptr))]
                   (if (== w old-ptr)
                     (let [new-epoch (mem/-add-i32! root-r d/ROOT_EPOCH_OFFSET 1)]
                       (perf/timed :retire-enqueue
                                   (when (and (not= old-ptr alloc/NIL_OFFSET)
                                              (not= old-ptr CLAIMED_SENTINEL))
                                     #?(:bb
                              ;; bb: use replaced-log for Eve→Eve (structural sharing),
                              ;; fall back to tree-logs for type changes
                                        (if (and (satisfies? d/IEveRoot old-val)
                                                 (satisfies? d/IEveRoot new-val))
                                          (let [offs (conj (or replaced-log []) old-ptr)]
                                            (.add ^java.util.List retire-q
                                                  {:offsets offs :epoch (inc new-epoch)}))
                                          (let [old-key (Integer/valueOf (int old-ptr))
                                                old-log (.remove ^java.util.HashMap tree-logs old-key)]
                                            (.add ^java.util.List retire-q
                                                  {:offsets (or old-log [old-ptr])
                                                   :epoch (inc new-epoch)})))
                                        :clj
                                        (if (and (instance? EveHashMap old-val) (instance? EveHashMap new-val))
                                ;; Map->Map: replaced nodes were collected during path-copy
                                          (let [offs (conj (or replaced-log []) old-ptr)]
                                            (.add ^java.util.Queue retire-q {:offsets offs :epoch (inc new-epoch)}))
                                ;; Other types: use alloc-log if available, else just header
                                          (let [old-log (.remove ^java.util.concurrent.ConcurrentHashMap tree-logs
                                                                 (Integer/valueOf (int old-ptr)))]
                                            (.add ^java.util.Queue retire-q {:offsets (or old-log [old-ptr])
                                                                             :epoch (inc new-epoch)}))))))
                       ;; Store new tree's alloc-log for future retire (non-Eve types only)
                       #?(:bb
                          (when (and (not eve-passthru?) cur-log (not= new-ptr alloc/NIL_OFFSET))
                            (.put ^java.util.HashMap tree-logs
                                  (Integer/valueOf (int new-ptr)) cur-log))
                          :clj
                          (when (and (not eve-passthru?) cur-log (not= new-ptr alloc/NIL_OFFSET))
                            (.put ^java.util.concurrent.ConcurrentHashMap tree-logs
                                  (Integer/valueOf (int new-ptr)) cur-log)))
                       [:ok new-val])
                     ;; CAS failed — free ALL blocks allocated for the new tree
                     (do (perf/count! :cas-retry)
                         (when cur-log
                           (doseq [off cur-log]
                             (alloc/-sio-free! sio off)))
                         [:retry nil])))
                 (finally
                   (perf/timed :unpin-epoch
                               (jvm-unpin-thread-epoch! domain-state))))]
           (case tag
             :ok (do (perf/timed :flush-retires
                                 (jvm-try-flush-retires! root-r retire-q sio flush-ts))
                     result)
             :retry (do (cas-backoff! attempt) (recur (inc attempt)))))))))))

;; ---------------------------------------------------------------------------
;; MmapAtomDomain — CLJS
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftype MmapAtom [domain-state atom-slot-idx]
     IDeref
     (-deref [_] (cljs-mmap-deref domain-state atom-slot-idx))
     IAtom
     ISwap
     (-swap! [_ f] (cljs-mmap-swap! domain-state atom-slot-idx f []))
     (-swap! [_ f a] (cljs-mmap-swap! domain-state atom-slot-idx f [a]))
     (-swap! [_ f a b] (cljs-mmap-swap! domain-state atom-slot-idx f [a b]))
     (-swap! [_ f a b xs] (cljs-mmap-swap! domain-state atom-slot-idx f (concat [a b] xs)))
     IReset
     (-reset! [this v] (-swap! this (constantly v)))
     IPrintWithWriter
     (-pr-writer [_ writer _opts]
       (-write writer (str "#eve/shared-atom {:id " atom-slot-idx
                           " :idx " atom-slot-idx "}")))))


;; ---------------------------------------------------------------------------
;; MmapAtom — JVM / bb
;; ---------------------------------------------------------------------------

#?(:bb
   (deftype MmapAtom [domain-state atom-slot-idx]
     clojure.lang.IDeref
     (deref [_] (jvm-mmap-deref domain-state atom-slot-idx))
     clojure.lang.IAtom
     (swap [_ f] (jvm-mmap-swap! domain-state atom-slot-idx f []))
     (swap [_ f a] (jvm-mmap-swap! domain-state atom-slot-idx f [a]))
     (swap [_ f a b] (jvm-mmap-swap! domain-state atom-slot-idx f [a b]))
     (swap [_ f a b xs] (jvm-mmap-swap! domain-state atom-slot-idx f (concat [a b] xs)))
     (reset [_ v] (jvm-mmap-swap! domain-state atom-slot-idx (constantly v) []))
     (compareAndSet [_ old-val new-val]
       (let [current (jvm-mmap-deref domain-state atom-slot-idx)]
         (if (= current old-val)
           (do (jvm-mmap-swap! domain-state atom-slot-idx (constantly new-val) []) true)
           false))))
   :cljs nil
   :clj
   (deftype MmapAtom [domain-state atom-slot-idx]
     clojure.lang.IDeref
     (deref [_] (jvm-mmap-deref domain-state atom-slot-idx))
     clojure.lang.IAtom
     (swap [_ f] (jvm-mmap-swap! domain-state atom-slot-idx f []))
     (swap [_ f a] (jvm-mmap-swap! domain-state atom-slot-idx f [a]))
     (swap [_ f a b] (jvm-mmap-swap! domain-state atom-slot-idx f [a b]))
     (swap [_ f a b xs] (jvm-mmap-swap! domain-state atom-slot-idx f (concat [a b] xs)))
     (reset [_ v] (jvm-mmap-swap! domain-state atom-slot-idx (constantly v) []))))

;; SharedAtom compat alias (for tests referencing a/SharedAtom)
#?(:cljs (def SharedAtom MmapAtom))

;; ---------------------------------------------------------------------------
;; MmapAtomDomain — CLJS
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftype MmapAtomDomain [domain-state registry-cache]
     IDeref
     (-deref [_] (cljs-mmap-deref domain-state 0))
     ISwap
     (-swap! [_ f] (cljs-mmap-swap! domain-state 0 f []))
     (-swap! [_ f a] (cljs-mmap-swap! domain-state 0 f [a]))
     (-swap! [_ f a b] (cljs-mmap-swap! domain-state 0 f [a b]))
     (-swap! [_ f a b xs] (cljs-mmap-swap! domain-state 0 f (concat [a b] xs)))
     IReset
     (-reset! [this v] (-swap! this (constantly v)))
     ILookup
     (-lookup [this k] (-lookup this k nil))
     (-lookup [_ k not-found]
       (if-let [slot-idx (get @registry-cache (str (namespace k) "/" (name k)))]
         (MmapAtom. domain-state slot-idx)
         not-found))))

;; ---------------------------------------------------------------------------
;; MmapAtomDomain — JVM / bb
;; ---------------------------------------------------------------------------

#?(:bb
   (deftype MmapAtomDomain [domain-state registry-cache]
     clojure.lang.IDeref
     (deref [_] (jvm-mmap-deref domain-state 0)))
   :cljs nil
   :clj
   (deftype MmapAtomDomain [domain-state registry-cache]
     clojure.lang.IDeref
     (deref [_] (jvm-mmap-deref domain-state 0))
     clojure.lang.ILookup
     (valAt [this k] (.valAt this k nil))
     (valAt [_ k not-found]
       (if-let [slot-idx (get @registry-cache (str (namespace k) "/" (name k)))]
         (MmapAtom. domain-state slot-idx)
         not-found))))

(defn domain-lookup
  "Look up an atom in a MmapAtomDomain by keyword (bb-compatible).
   Use this instead of (:key domain) in Babashka."
  ([domain k] (domain-lookup domain k nil))
  ([domain k not-found]
   (let [registry-cache (.-registry-cache domain)]
     (if-let [slot-idx (get @registry-cache (str (namespace k) "/" (name k)))]
       (MmapAtom. (.-domain-state domain) slot-idx)
       not-found))))

;; ---------------------------------------------------------------------------
;; Domain cache and file detection (must precede persistent-atom-domain)
;; ---------------------------------------------------------------------------

(def ^:private domain-cache
  "Cache of opened domains by base-path string."
  #?(:cljs (cljs.core/atom {})
     :default (clojure.core/atom {})))

(defn- domain-files-exist?
  "Check if the domain root file exists at the given base-path."
  [base-path]
  (let [root-path (str base-path ".root")]
    #?(:cljs (let [fs (js/require "fs")]
               (.existsSync fs root-path))
       :default (.exists (java.io.File. root-path)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn persistent-atom-domain
  "Open or create an mmap-backed atom domain at base-path.
   If domain files already exist, joins them. Otherwise creates new ones.
   Caches domains by path — subsequent calls with the same path return the
   same domain. Returns an MmapAtomDomain. Use close-atom-domain! to release."
  [base-path & {:keys [capacities lustre?] :or {capacities {} lustre? false}}]
  (or (get @domain-cache base-path)
      (binding [mem/*lustre-mode* lustre?]
        (let [exists? (domain-files-exist? base-path)
              d #?(:cljs (MmapAtomDomain.
                          (if exists?
                            (cljs-join-mmap-domain! base-path)
                            (cljs-open-mmap-domain! base-path :capacities capacities))
                          (cljs.core/atom {}))
                   :default (MmapAtomDomain.
                             (if exists?
                               (jvm-join-mmap-domain! base-path)
                               (jvm-open-mmap-domain! base-path :capacities capacities))
                             (clojure.core/atom {})))]
          (swap! domain-cache assoc base-path d)
          d))))

(defn join-atom-domain
  "Join an existing mmap-backed atom domain at base-path.
   Files must already exist. Returns an MmapAtomDomain.
   Prefer persistent-atom-domain which auto-detects create vs join."
  [base-path]
  (persistent-atom-domain base-path))

(defn close-atom-domain!
  "Release the worker slot and cancel heartbeat for a domain.
   Atoms created from this domain become invalid after close.
   Removes the domain from the internal cache."
  [d]
  (let [{:keys [root-r slot-idx base-path] :as ds} (.-domain-state #?(:cljs ^js d :default d))]
    (when (and root-r slot-idx)
      #?(:cljs (when-let [tid (:timer-id ds)] (js/clearInterval tid))
         :default (when-let [sched (:heartbeat-sched ds)] (.shutdown sched)))
      (mmap-release-slot! root-r slot-idx))
    (when base-path
      (swap! domain-cache dissoc base-path))))

(defn close!
  "Release the worker slot acquired when this atom was opened or joined.
   Cancels the heartbeat timer. Call when the process is done using the atom.
   Safe to call multiple times (idempotent via slot INACTIVE check)."
  [a]
  (let [{:keys [root-r slot-idx] :as ds} (.-domain-state #?(:cljs ^js a :default a))]
    (when (and root-r slot-idx)
      #?(:cljs (when-let [tid (:timer-id ds)] (js/clearInterval tid))
         :default (when-let [sched (:heartbeat-sched ds)] (.shutdown sched)))
      (mmap-release-slot! root-r slot-idx))))

;; ---------------------------------------------------------------------------
;; FNV-1a hash for atom name strings
;; ---------------------------------------------------------------------------

(defn- fnv-1a-hash
  "FNV-1a 32-bit hash of a string. Used for atom slot name_hash field."
  [^String s]
  (let [len #?(:cljs (.-length s) :default (.length s))]
    (loop [i 0 h (unchecked-int 0x811c9dc5)]
      (if (>= i len)
        h
        (let [b #?(:cljs (.charCodeAt s i) :default (int (.charAt s i)))]
          (recur (inc i)
                 (unchecked-int
                  (unchecked-multiply
                   (bit-xor h (bit-and b 0xFF))
                   (unchecked-int 0x01000193)))))))))

(defn- keyword-str
  "Convert a namespace-qualified keyword to its string form: \"ns/name\"."
  [k]
  (str (namespace k) "/" (name k)))

;; ---------------------------------------------------------------------------
;; Atom slot allocation and registry
;; ---------------------------------------------------------------------------

(defn- claim-atom-slot!
  "Scan the atom slot table for a free slot (atom_ptr == NIL_OFFSET).
   CAS-claim it by writing a sentinel. Returns the slot index.
   Starts scanning from slot 1 (slot 0 = registry)."
  [root-r name-hash]
  (loop [i 1]
    (when (>= i d/MAX_ATOM_SLOTS)
      (throw (ex-info "mmap-atom: no free atom slot" {:max d/MAX_ATOM_SLOTS})))
    (let [ptr-off (d/atom-slot-offset i d/ATOM_SLOT_PTR_OFFSET)
          cur-ptr (mem/-load-i32 root-r ptr-off)]
      (if (== cur-ptr alloc/NIL_OFFSET)
        ;; Free slot — try to claim with CAS (NIL → CLAIMED sentinel)
        (let [w (mem/-cas-i32! root-r ptr-off alloc/NIL_OFFSET CLAIMED_SENTINEL)]
          (if (== w alloc/NIL_OFFSET)
            (do (mem/-store-i32! root-r (d/atom-slot-offset i d/ATOM_SLOT_HASH_OFFSET)
                                 name-hash)
                i)
            (recur (inc i))))
        (recur (inc i))))))

(defn- update-registry!
  "CAS-update the registry atom (slot 0) to associate kw-str → slot-idx.
   The registry is an EveHashMap stored as a regular atom value."
  [domain-state kw-str slot-idx]
  #?(:cljs
     (cljs-mmap-swap! domain-state 0
                      (fn [reg] (assoc (or reg {}) kw-str slot-idx)) [])
     :default
     (jvm-mmap-swap! domain-state 0
                     (fn [reg] (assoc (or reg {}) kw-str slot-idx)) [])))

(defn- read-registry
  "Read the current registry map from slot 0."
  [domain-state]
  #?(:cljs (cljs-mmap-deref domain-state 0)
     :default (jvm-mmap-deref domain-state 0)))

(defn- create-mmap-atom!
  "Create a new named atom in a domain. Allocates a slot, updates registry,
   optionally sets initial value. Returns [MmapAtom slot-idx]."
  [^MmapAtomDomain domain kw-str initial-val]
  (let [ds (.-domain-state domain)
        root-r (:root-r ds)
        name-hash (fnv-1a-hash kw-str)
        slot-idx (claim-atom-slot! root-r name-hash)]
    ;; Update registry: assoc kw-str → slot-idx
    (update-registry! ds kw-str slot-idx)
    ;; Update in-memory cache
    (swap! (.-registry-cache domain) assoc kw-str slot-idx)
    ;; Create atom and set initial value if provided
    (let [a (MmapAtom. ds slot-idx)]
      (when (some? initial-val)
        #?(:cljs (-swap! a (constantly initial-val))
           :default (swap! a (constantly initial-val))))
      a)))

(defn lookup-or-create-mmap-atom!
  "Look up an atom by keyword string, or create it if not found."
  [^MmapAtomDomain domain kw-str initial-val]
  ;; Check in-memory cache first
  (if-let [cached-idx (get @(.-registry-cache domain) kw-str)]
    (MmapAtom. (.-domain-state domain) cached-idx)
    ;; Read registry from slab
    (let [reg (read-registry (.-domain-state domain))]
      (if-let [slot-idx (get reg kw-str)]
        (do (swap! (.-registry-cache domain) assoc kw-str slot-idx)
            (MmapAtom. (.-domain-state domain) slot-idx))
        ;; Not found — create new atom
        (create-mmap-atom! domain kw-str initial-val)))))

;; ---------------------------------------------------------------------------
;; Argument parsing (mirrors shared_atom.cljs:parse-atom-args)
;; ---------------------------------------------------------------------------

(defn- parse-persistent-atom-args
  "Parse persistent-atom arguments:
   (persistent-atom value)                  - anonymous
   (persistent-atom ::ns/kw value)          - named (qualified kw shorthand)
   (persistent-atom {:id ::ns/kw} value)    - named (config map)
   (persistent-atom nil value)              - anonymous (escape hatch)"
  [args]
  (if (== 1 (count args))
    {:value (first args) :opts {}}
    (let [fst (first args)
          snd (second args)]
      (cond
        (and (keyword? fst) (namespace fst))
        {:value snd :opts {:id fst}}

        (map? fst)
        {:value snd :opts fst}

        (nil? fst)
        {:value snd :opts {}}

        :else
        {:value fst :opts {}}))))

;; ---------------------------------------------------------------------------
;; Default domain
;; ---------------------------------------------------------------------------

(def ^:dynamic *global-persistent-atom-domain*
  "Dynamic var for the default persistent atom domain.
   Lazy-initialized on first persistent-atom call without :persistent key."
  nil)

(defn- resolve-domain
  "Resolve which domain to use for a persistent-atom call.
   :persistent in opts → open/join that path.
   :lustre? in opts → use fcntl byte-range locking for cross-node atomics.
   Otherwise → *global-persistent-atom-domain* or lazy default at ./eve/."
  [opts]
  (let [lustre? (:lustre? opts false)]
    (if-let [path (:persistent opts)]
      (persistent-atom-domain path :lustre? lustre?)
      (or *global-persistent-atom-domain*
          (persistent-atom-domain "./eve/" :lustre? lustre?)))))

(def ^:private anon-counter
  "Auto-incrementing counter for anonymous persistent atoms."
  #?(:cljs (cljs.core/atom 0)
     :default (clojure.core/atom 0)))

(defn persistent-atom
  "Create or look up a named persistent atom in an mmap-backed domain.

   Forms:
     (persistent-atom ::counter 0)
       Named atom in the default global domain (*global-persistent-atom-domain*
       or lazy ./eve/). Name is the keyword string \"ns/counter\".

     (persistent-atom {:id ::counter :persistent \"./my-db\"} 0)
       Named atom in an explicit domain at ./my-db.

     (persistent-atom 0)
       Anonymous atom (auto-generated unique name) in the default domain.

     (persistent-atom {:id ::counter} 0)
       Named atom, default domain (same as keyword shorthand).

   Returns an MmapAtom that implements IDeref, ISwap, IReset."
  [& args]
  (let [{:keys [value opts]} (parse-persistent-atom-args args)
        domain (resolve-domain opts)
        id (:id opts)
        kw-str (if id
                 (str (namespace id) "/" (name id))
                 (str "__anon__/" (swap! anon-counter inc)))]
    (lookup-or-create-mmap-atom! domain kw-str value)))

;; ---------------------------------------------------------------------------
;; Heap-backed (non-persistent) atom — JVM only (not bb)
;; ---------------------------------------------------------------------------

#?(:bb nil
   :clj
   (do
     (defn- jvm-open-heap-domain!
       "Create an in-memory (non-persistent) atom domain on JVM.
        Uses JvmHeapRegion for slabs and root — no files on disk.
        Returns domain-state map compatible with jvm-mmap-deref/jvm-mmap-swap!."
       [& {:keys [capacities] :or {capacities {}}}]
       (let [;; 6 bitmap slab classes (heap-backed)
             slab-regions
             (mapv (fn [i]
                     (let [cap (get capacities i (* 1 1024 1024))]
                       (alloc/init-jvm-heap-slab! i :capacity cap)))
                   (range d/NUM_SLAB_CLASSES))
             ;; Class 6: coalescing overflow (heap-backed)
             coalesc-init-sz (* 64 1024) ;; 64 KB initial — grows on demand
             coalesc-layout (coalesc/coalesc-layout coalesc-init-sz
                                                    coalesc/MAX_DESCRIPTORS)
             coalesc-r (mem/make-heap-region (:total-bytes coalesc-layout))
             _ (coalesc/init-coalesc-region! coalesc-r
                                             coalesc-init-sz
                                             coalesc/MAX_DESCRIPTORS)
             regions-7 (conj slab-regions coalesc-r)
             ;; Heap slabs: bitmap is embedded in same region (no separate file)
             bm-regions-7 (conj slab-regions nil) ;; classes 0-5 share region, 6 = nil
             sio (alloc/make-jvm-slab-ctx regions-7 bm-regions-7)
             ;; Root region (heap-backed)
             root-r (mem/make-heap-region ROOT_BYTES)
             rmap-r (mem/make-heap-region READER_MAP_BYTES)]
         ;; Write root header (V2)
         (mem/-store-i32! root-r d/ROOT_MAGIC_OFFSET d/ROOT_MAGIC_V2)
         (mem/-store-i32! root-r d/ROOT_ATOM_PTR_OFFSET 0)
         (mem/-store-i32! root-r d/ROOT_EPOCH_OFFSET 1)
         (mem/-store-i32! root-r d/ROOT_ATOM_TABLE_OFFSET d/ATOM_TABLE_START)
         (mem/-store-i32! root-r d/ROOT_ATOM_TABLE_CAPACITY d/MAX_ATOM_SLOTS)
         ;; Init worker slots
         (dotimes [slot d/MAX_WORKERS]
           (mem/-store-i32! root-r
                            (+ d/ROOT_WORKER_REGISTRY_START (* slot d/WORKER_SLOT_SIZE))
                            d/WORKER_STATUS_INACTIVE))
         ;; Init atom table
         (mem/-store-i32! root-r d/ATOM_TABLE_HEADER_START d/ATOM_TABLE_MAGIC)
         (mem/-store-i32! root-r (+ d/ATOM_TABLE_HEADER_START 4) 0)
         (dotimes [s d/MAX_ATOM_SLOTS]
           (mem/-store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_PTR_OFFSET)
                            alloc/NIL_OFFSET)
           (mem/-store-i32! root-r (d/atom-slot-offset s d/ATOM_SLOT_HASH_OFFSET) 0))
         (mem/-store-i32! root-r (d/atom-slot-offset 0 d/ATOM_SLOT_PTR_OFFSET)
                          alloc/NIL_OFFSET)
         ;; Claim a worker slot (no heartbeat needed — single process)
         (let [slot-idx (mmap-claim-slot! root-r)]
           (write-heartbeat! root-r slot-idx)
           {:root-r root-r :rmap-r rmap-r :sio sio :base-path nil
            :slot-idx slot-idx :heartbeat-sched nil
            :retire-q (java.util.concurrent.ConcurrentLinkedQueue.)
            :tree-logs (java.util.concurrent.ConcurrentHashMap.)
            :flush-ts (volatile! 0)
            :thread-epochs (java.util.concurrent.ConcurrentHashMap.)
            :pin-lock (Object.)})))

     (def ^:private heap-domain-cache
       "Cache of heap domains by id (keyword or nil for default)."
       (clojure.core/atom {}))

     (defn- resolve-heap-domain
       "Get or create a heap-backed MmapAtomDomain for the given id (nil = default)."
       [id]
       (or (get @heap-domain-cache id)
           (let [ds (jvm-open-heap-domain!)
                 d (MmapAtomDomain. ds (clojure.core/atom {}))]
             (swap! heap-domain-cache assoc id d)
             d)))

     (defn atom
       "Create or look up an atom (JVM).

        Mirrors the cljs-thread t/atom API:

          (atom ::counter 0)                     - heap-backed (in-memory)
          (atom {:id ::counter} 0)               - heap-backed (in-memory)
          (atom {:id ::counter :persistent \"./db\"} 0)  - mmap-backed (on disk)
          (atom 0)                               - anonymous heap-backed

        Without :persistent, creates an in-memory heap-backed atom (analogous
        to CLJS SharedArrayBuffer atoms). With :persistent, delegates to
        persistent-atom for mmap-backed cross-process atoms."
       [& args]
       (let [{:keys [value opts]} (parse-persistent-atom-args args)]
         (if (:persistent opts)
           (apply persistent-atom args)
           (let [domain (resolve-heap-domain (:id opts))
                 id (:id opts)
                 kw-str (if id
                          (str (namespace id) "/" (name id))
                          (str "__anon__/" (swap! anon-counter inc)))]
             (lookup-or-create-mmap-atom! domain kw-str value)))))))

;; ---------------------------------------------------------------------------
;; SAB-backed (non-persistent) atom — CLJS only
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (def ^:private sab-domain-cache
       "Cache of SAB domains by id (keyword or nil for default)."
       (cljs.core/atom {}))

     (defn- resolve-sab-domain
       "Get or create an SAB-backed MmapAtomDomain for the given id (nil = default)."
       [id]
       (or (get @sab-domain-cache id)
           (let [ds (cljs-open-sab-domain! :skip-init true)
                 d (MmapAtomDomain. ds (cljs.core/atom {}))]
             (swap! sab-domain-cache assoc id d)
             d)))

     (defn atom
       "Create or look up an atom (CLJS).

        Mirrors the JVM eve.atom/atom API:

          (atom ::counter 0)                     - SAB-backed (in-memory)
          (atom {:id ::counter} 0)               - SAB-backed (in-memory)
          (atom {:id ::counter :persistent \"./db\"} 0)  - mmap-backed (on disk)
          (atom 0)                               - anonymous SAB-backed

        Without :persistent, creates an in-memory SAB-backed atom (replaces
        the old eve.shared-atom). With :persistent, delegates to
        persistent-atom for mmap-backed cross-process atoms."
       [& args]
       (let [{:keys [value opts]} (parse-persistent-atom-args args)]
         (if (:persistent opts)
           (apply persistent-atom args)
           (let [domain (resolve-sab-domain (:id opts))
                 id (:id opts)
                 kw-str (if id
                          (str (namespace id) "/" (name id))
                          (str "__anon__/" (swap! anon-counter inc)))]
             (lookup-or-create-mmap-atom! domain kw-str value)))))))

;; ---------------------------------------------------------------------------
;; Backward-compat API — bridges callers that used eve.shared-atom's API
;; These shims let array.cljc and obj.cljc compile during the transition.
;; ---------------------------------------------------------------------------

(def ^:dynamic *global-atom-instance*
  "Dynamic var for the global atom instance.
   Bound during swap!/reset! and by atom-domain initialization.
   Used by array.cljc and obj.cljc to locate the slab allocation context."
  nil)

(defn- create-atom-domain!
  "Internal: create a domain, store initial value, set global."
  [domain initial-value]
  (let [kw-str "__atom-domain__/root"
        _a (lookup-or-create-mmap-atom! domain kw-str initial-value)]
    ;; Set the global so array.cljc / obj.cljc can find the slab context
    (set! *global-atom-instance* domain)
    domain))

#?(:cljs
   (do
     (defn get-env
       "Return the slab allocation environment map.
        For backward compatibility with callers that used eve.shared-atom/get-env.
        In the slab-backed system, the 'env' is the domain-state map
        augmented with :sab pointing to the class 0 slab's SAB."
       [obj]
       (let [ds (cond
                  (instance? MmapAtom obj) (.-domain-state ^js obj)
                  (instance? MmapAtomDomain obj) (.-domain-state ^js obj)
                  (map? obj) obj
                  :else (.-domain-state ^js obj))
             ;; Add :sab compat key from slab class 0
             sabs (try (alloc/get-all-slab-sabs) (catch :default _ nil))
             sab (when (and sabs (pos? (alength sabs))) (aget sabs 0))]
         (if sab
           (assoc ds :sab sab)
           ds)))

     (defn alloc
       "Allocate a block of size-bytes via the slab allocator.
        For backward compatibility with eve.shared-atom/alloc callers.
        Returns {:offset slab-qualified-offset :descriptor-idx slab-offset}.
        descriptor-idx is set to the slab offset for unique identification."
       [_env size-bytes]
       (let [result (alloc/alloc size-bytes)]
         (if (:error result)
           result
           (let [off (:offset result)]
             {:offset off
              :descriptor-idx off}))))

     (defn free
       "Free a slab-allocated block.
        For backward compatibility with eve.shared-atom/free callers."
       [_env slab-offset]
       (alloc/free! slab-offset))

     (defn shared-atom?
       "Returns true if obj is an Eve atom (MmapAtom or MmapAtomDomain)."
       [obj]
       (or (instance? MmapAtom obj) (instance? MmapAtomDomain obj)))

     (defn atom-serialize
       "Serialize a value for atom storage (compat shim).
        In the slab-backed system, this converts a CLJS value to an Eve type."
       [v]
       (ser/convert-to-sab v))

     (defn atom-deserialize
       "Deserialize a value from atom storage (compat shim).
        In the slab-backed system, Eve types are already lazy — just return as-is."
       [v]
       v)

     (defn eve->cljs
       "Convert an Eve type to a plain CLJS value (compat shim).
        Recursively materializes lazy slab-backed types into plain CLJS data."
       [v]
       (cond
         (nil? v) v
         (or (string? v) (number? v) (boolean? v) (keyword? v) (symbol? v)) v
         (satisfies? IMap v) (into {} (map (fn [[k v]] [(eve->cljs k) (eve->cljs v)])) v)
         (satisfies? IVector v) (into [] (map eve->cljs) v)
         (satisfies? ISet v) (into #{} (map eve->cljs) v)
         (satisfies? ISequential v) (into [] (map eve->cljs) v)
         :else v))

     (defn init-worker-cache!
       "Initialize module-level cached views for a worker thread.
        For backward compatibility with eve.shared-atom/init-worker-cache!.
        In the slab system, initializes wasm-mem views from slab class 0 SAB."
       [_env]
       (let [sabs (alloc/get-all-slab-sabs)]
         (when (and sabs (pos? (alength sabs)))
           (wasm-mem/init-views-from-sab! (aget sabs 0)))))

     (defn reset-alloc-cursor!
       "Reset the allocation cursor (compat shim — no-op in slab system)."
       ([] nil)
       ([_pos] nil))

     (defn sab-transfer-data
       "Return the SABs for transferring to a worker.
        Returns map with :sab (class 0 slab SAB), :reader-map-sab (rmap SAB),
        :slab-sabs (all 6 slab SABs), :root-sab."
       ([] (sab-transfer-data nil))
       ([obj]
        (let [sabs (alloc/get-all-slab-sabs)
              sab (when (and sabs (pos? (alength sabs))) (aget sabs 0))
              ds (when obj
                   (cond
                     (instance? MmapAtomDomain obj) (.-domain-state ^js obj)
                     (instance? MmapAtom obj) (.-domain-state ^js obj)
                     :else nil))
              rmap-sab (when-let [rmap-r (when ds (:rmap-r ds))]
                         (.-sab ^js rmap-r))]
          {:sab sab
           :reader-map-sab rmap-sab
           :slab-sabs sabs
           :root-sab (alloc/get-root-sab)})))

     (defn atom-domain
       "Create an SAB-backed atom domain (CLJS).
        For backward compatibility with eve.shared-atom/atom-domain.
        Creates a fresh SAB-backed domain with the given initial value.
        Returns an MmapAtomDomain."
       [initial-value & {:as _opts}]
       (let [ds (cljs-open-sab-domain! :skip-init true)
             domain (MmapAtomDomain. ds (cljs.core/atom {}))]
         ;; Initialize wasm-mem cached views from slab class 0 SAB
         (let [sabs (alloc/get-all-slab-sabs)]
           (when (and sabs (pos? (alength sabs)))
             (wasm-mem/init-views-from-sab! (aget sabs 0))))
         (create-atom-domain! domain initial-value)
         domain))

     ;; -----------------------------------------------------------------------
     ;; Epoch-GC public API — shims for epoch_gc_test.cljs
     ;; These delegate to the private worker-slot helpers on the root IMemRegion.
     ;; The `env` parameter is a domain-state map (from get-env).
     ;; -----------------------------------------------------------------------

     (defn register-worker!
       "Register a worker in the root worker registry. Returns slot index."
       [env _worker-id]
       (let [root-r (:root-r env)
             slot (mmap-claim-slot! root-r)]
         (write-heartbeat! root-r slot)
         slot))

     (defn unregister-worker!
       "Unregister a worker slot."
       [env slot-idx]
       (mmap-release-slot! (:root-r env) slot-idx))

     (defn update-heartbeat!
       "Update worker heartbeat timestamp."
       [env slot-idx]
       (write-heartbeat! (:root-r env) slot-idx))

     (defn check-worker-liveness
       "Check if a worker's heartbeat is fresh (not stale)."
       [env slot-idx]
       (not (heartbeat-stale? (:root-r env) slot-idx)))

     (defn get-current-epoch
       "Read the current global epoch."
       [env]
       (mem/-load-i32 (:root-r env) d/ROOT_EPOCH_OFFSET))

     (defn increment-epoch!
       "Atomically increment the global epoch. Returns new epoch."
       [env]
       (inc (mem/-add-i32! (:root-r env) d/ROOT_EPOCH_OFFSET 1)))

     (defn begin-read!
       "Pin the current epoch for reading. Returns the epoch."
       [env slot-idx]
       (let [root-r (:root-r env)
             epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)]
         (mmap-pin-epoch! root-r slot-idx epoch)
         epoch))

     (defn end-read-epoch!
       "Unpin the read epoch for a worker slot."
       [env slot-idx]
       (mmap-unpin-epoch! (:root-r env) slot-idx))

     (defn with-read-epoch
       "Execute f within a pinned read epoch. Returns result of f."
       [env slot-idx f]
       (let [epoch (begin-read! env slot-idx)]
         (try
           (f epoch)
           (finally
             (end-read-epoch! env slot-idx)))))

     (defn get-min-active-epoch
       "Find the minimum epoch being read by any active worker."
       [env]
       (mmap-min-safe-epoch (:root-r env)))

     (def ^:private retired-blocks-registry
       "Track retired blocks for epoch-based GC compat.
        Maps descriptor-idx to {:epoch N :slab-offset M}."
       (cljs.core/atom {}))

     (defn retire-block!
       "Retire a block at the current epoch.
        Returns true on success."
       ([env descriptor-idx]
        (let [epoch (get-current-epoch env)]
          (swap! retired-blocks-registry assoc descriptor-idx
                 {:epoch epoch :slab-offset (:offset (get @retired-blocks-registry descriptor-idx))})
          true))
       ([slab-offset]
        (when (not= slab-offset alloc/NIL_OFFSET)
          (alloc/free! slab-offset))))

     (defn try-free-retired!
       "Try to free a retired block. Checks if min-active-epoch > retired-epoch.
        Returns :freed, :has-readers, or :not-retired."
       [env descriptor-idx]
       (if-let [entry (get @retired-blocks-registry descriptor-idx)]
         (let [min-epoch (get-min-active-epoch env)
               retired-epoch (:epoch entry)]
           (if (or (nil? min-epoch) (> min-epoch retired-epoch))
             (do
               (swap! retired-blocks-registry dissoc descriptor-idx)
               :freed)
             :has-readers))
         :not-retired))

     (defn sweep-retired-blocks!
       "Sweep retired blocks. Frees all blocks whose epoch is safe."
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
       "Mark stale workers. Returns count of stale workers found."
       [env]
       (let [root-r (:root-r env)]
         (loop [i 0 count 0]
           (if (>= i d/MAX_WORKERS)
             count
             (let [status (mem/-load-i32 root-r (worker-slot-offset i d/OFFSET_WS_STATUS))]
               (if (and (== status d/WORKER_STATUS_ACTIVE)
                        (heartbeat-stale? root-r i))
                 (recur (inc i) (inc count))
                 (recur (inc i) count)))))))))

