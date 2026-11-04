(ns eve.region
  "eve's regions (eve.mem's IMemRegion and IHostAtomics; HOST-CONTRACT §1-2)
   over the JVM's java.nio: one region over a ByteBuffer (heap byte[] in-process,
   a MappedByteBuffer across processes), its atomics VarHandle view handles,
   wait/notify LockSupport park/unpark; and a Lustre mount's fcntl locks.

   make-heap-region and open-mmap-region open them; lustre-region? says
   whether a region's atomics are fcntl byte-range locks (a Lustre mount)."
  (:refer-clojure :exclude [aget aset alength byte-array int-array long-array object-array
                            into-array unchecked-byte unchecked-int unchecked-long
                            unchecked-inc-int unsigned-bit-shift-right long double class
                            locking transient persistent! conj! assoc! bytes objects
                            const-bytes const-objects])
  (:require
   [eve.host :refer [aget aset alength byte-array int-array long-array object-array
                     into-array unchecked-byte unchecked-int unchecked-long
                     unchecked-inc-int unsigned-bit-shift-right long double class locking
                     transient persistent! conj! assoc! bytes objects const-bytes
                     const-objects]]
   [eve.mem :as mem :refer [-byte-length -load-i32 -store-i32! -cas-i32!
                     -add-i32! -sub-i32! -exchange-i32! -load-i64 -store-i64! -cas-i64!
                     -add-i64! -sub-i64! -wait-i32! -notify-i32! -supports-watch?
                     -read-bytes -write-bytes! -atomic-rmw? -with-file-lock
                     -sync! -sync-range!]])
  (:import
   [java.io RandomAccessFile]
   [java.lang.invoke MethodHandle MethodHandles MethodType VarHandle VarHandle$AccessMode]
   [java.nio ByteBuffer ByteOrder MappedByteBuffer]
   [java.nio.channels FileChannel FileChannel$MapMode]
   [java.nio.file OpenOption Paths StandardOpenOption]
   [java.util Date UUID]
   [java.util.concurrent ConcurrentHashMap ConcurrentLinkedQueue]
   [java.util.concurrent.locks LockSupport ReentrantLock]))

;; Every region has atomic RMW unless it says otherwise.
(extend-protocol mem/IHostAtomics
  Object
  (-atomic-rmw? [_] true)
  (-with-file-lock [_ _byte-off f] (f)))

;; ------------------------------------------------------------------
;; Atomic memory access via VarHandle (no sun.misc.Unsafe)
;; ------------------------------------------------------------------
;; VarHandle access-mode methods (getVolatile, compareAndExchange, ...)
;; are @PolymorphicSignature and cannot be called through Clojure
;; reflection.  Instead each VarHandle is converted once to a
;; concretely-typed MethodHandle via VarHandle.toMethodHandle, spread
;; once to (Object[])Object (spread-handle), and invoked with .invoke on an
;; Object[]: the call site's type is then the handle's own, so no
;; conversion is made per call. (invokeWithArguments built a spreader
;; every call: 183 ns a volatile get against 18.6 ns so, measured
;; 2026-10-21 ET.)  The handles below are stateless and shared by every
;; region.
;;
;; Heap and mmap regions are unified: both are java.nio.ByteBuffers —
;; heap regions wrap a byte[] (ByteBuffer/wrap), mmap regions use a
;; MappedByteBuffer (FileChannel.map) — accessed through
;; MethodHandles.byteBufferViewVarHandle with explicit LITTLE_ENDIAN
;; order (EVE's format is little-endian).  Java 9+, no preview flags.

(def ^:private ^VarHandle I32-VH
  (MethodHandles/byteBufferViewVarHandle (class (int-array 0))
                                         ByteOrder/LITTLE_ENDIAN))
(def ^:private ^VarHandle I64-VH
  (MethodHandles/byteBufferViewVarHandle (class (long-array 0))
                                         ByteOrder/LITTLE_ENDIAN))

(defn- spread-handle
  "mh taking n arguments as one taking an Object[] of them and answering an Object."
  ^MethodHandle [^MethodHandle mh n]
  (-> mh
      (.asSpreader (class (object-array 0)) (int n))
      (.asType (MethodType/methodType Object (class (object-array 0))))))

(def ^:private ^MethodHandle I32-GET
  (spread-handle (.toMethodHandle I32-VH VarHandle$AccessMode/GET_VOLATILE) 2))
(def ^:private ^MethodHandle I32-SET
  (spread-handle (.toMethodHandle I32-VH VarHandle$AccessMode/SET_VOLATILE) 3))
(def ^:private ^MethodHandle I32-CAS
  (spread-handle (.toMethodHandle I32-VH VarHandle$AccessMode/COMPARE_AND_EXCHANGE) 4))
(def ^:private ^MethodHandle I32-ADD
  (spread-handle (.toMethodHandle I32-VH VarHandle$AccessMode/GET_AND_ADD) 3))
(def ^:private ^MethodHandle I32-XCHG
  (spread-handle (.toMethodHandle I32-VH VarHandle$AccessMode/GET_AND_SET) 3))
(def ^:private ^MethodHandle I64-GET
  (spread-handle (.toMethodHandle I64-VH VarHandle$AccessMode/GET_VOLATILE) 2))
(def ^:private ^MethodHandle I64-SET
  (spread-handle (.toMethodHandle I64-VH VarHandle$AccessMode/SET_VOLATILE) 3))
(def ^:private ^MethodHandle I64-CAS
  (spread-handle (.toMethodHandle I64-VH VarHandle$AccessMode/COMPARE_AND_EXCHANGE) 4))
(def ^:private ^MethodHandle I64-ADD
  (spread-handle (.toMethodHandle I64-VH VarHandle$AccessMode/GET_AND_ADD) 3))
(def ^:private ^MethodHandle I64-XCHG
  (spread-handle (.toMethodHandle I64-VH VarHandle$AccessMode/GET_AND_SET) 3))

;; A spread handle's argument array, typed so the .invoke call resolves when compiled. (Given a vector, the call was
;; resolved by reflection at every call — Class.getMethods, most of a JVM swap's time: JFR, 2026-10-21 ET.)
(defn- args2 ^objects [a b] (let [arr (object-array 2)] (aset arr 0 a) (aset arr 1 b) arr))
(defn- args3 ^objects [a b c] (let [arr (object-array 3)] (aset arr 0 a) (aset arr 1 b) (aset arr 2 c) arr))
(defn- args4 ^objects [a b c d]
  (let [arr (object-array 4)] (aset arr 0 a) (aset arr 1 b) (aset arr 2 c) (aset arr 3 d) arr))

;; ------------------------------------------------------------------
;; Unaligned i32/i64 access — DataView-style fallback
;; ------------------------------------------------------------------
;; HAMT nodes pack [len:i32][bytes] tightly, so slab-interior i32
;; offsets are routinely NOT 4-aligned.  The CLJS slab IO is
;; DataView-backed and tolerates this; the VarHandle int/long views
;; throw IllegalStateException on misaligned access.  These helpers
;; compose little-endian values from absolute byte ops.
;;
;; Memory-safety note: unaligned locations are node interiors, always
;; written during node construction (before the node pointer is
;; published via an aligned volatile store/CAS) and read after the
;; pointer is acquired.  Plain non-atomic semantics therefore match
;; the CLJS DataView behavior.  Atomic RMW ops (CAS/add/exchange)
;; require alignment — they throw on unaligned offsets instead of
;; silently tearing.

(defn- i32-get-unaligned
  [^ByteBuffer buf off]
  (let [off (int off)
        b0 (bit-and (.get buf off) 0xFF)
        b1 (bit-and (.get buf (unchecked-add-int off 1)) 0xFF)
        b2 (bit-and (.get buf (unchecked-add-int off 2)) 0xFF)
        b3 (bit-and (.get buf (unchecked-add-int off 3)) 0xFF)]
    (unchecked-int (bit-or b0
                           (bit-shift-left b1 8)
                           (bit-shift-left b2 16)
                           (bit-shift-left b3 24)))))

(defn- i32-set-unaligned!
  [^ByteBuffer buf off ^long v]
  (let [off (int off)]
    (.put buf off (unchecked-byte v))
    (.put buf (unchecked-add-int off 1) (unchecked-byte (unsigned-bit-shift-right v 8)))
    (.put buf (unchecked-add-int off 2) (unchecked-byte (unsigned-bit-shift-right v 16)))
    (.put buf (unchecked-add-int off 3) (unchecked-byte (unsigned-bit-shift-right v 24)))))

(defn- i64-get-unaligned
  [^ByteBuffer buf off]
  (let [lo (i32-get-unaligned buf off)
        hi (i32-get-unaligned buf (+ off 4))]
    (bit-or (bit-shift-left (long hi) 32)
            (bit-and (long lo) 0xFFFFFFFF))))

(defn- i64-set-unaligned!
  [^ByteBuffer buf off ^long v]
  (i32-set-unaligned! buf off (unchecked-int v))
  (i32-set-unaligned! buf (+ off 4) (unchecked-int (unsigned-bit-shift-right v 32))))

(defn- unaligned-atomic-ex
  [op ^long byte-off align]
  (throw (ex-info (str "Unaligned atomic " (name op)
                       " at byte offset " byte-off
                       " — requires " align "-byte alignment.")
                  {:op op :byte-off byte-off})))

;; Aligned-or-unaligned i32/i64 load/store used by both region deftypes.
;; buf is any ByteBuffer (heap ByteBuffer or MappedByteBuffer).

(defn- bb-load-i32
  [^ByteBuffer buf off]
  (if (zero? (bit-and off 3))
    (int (.invoke I32-GET (args2 buf off)))
    (i32-get-unaligned buf off)))

(defn- bb-store-i32!
  [^ByteBuffer buf off ^long v]
  (if (zero? (bit-and off 3))
    (.invoke I32-SET (args3 buf off (unchecked-int v)))
    (i32-set-unaligned! buf off v)))

(defn- bb-load-i64
  [^ByteBuffer buf off]
  (if (zero? (bit-and off 7))
    (long (.invoke I64-GET (args2 buf off)))
    (i64-get-unaligned buf off)))

(defn- bb-store-i64!
  [^ByteBuffer buf off ^long v]
  (if (zero? (bit-and off 7))
    (.invoke I64-SET (args3 buf off v))
    (i64-set-unaligned! buf off v)))

;; ------------------------------------------------------------------
;; Futex-style wait/notify via LockSupport.park/unpark
;; ------------------------------------------------------------------
;; waiters maps [region byte-off] -> ConcurrentLinkedQueue of Thread.
;; Region keys use identity equality (deftype default).  Queues are
;; dropped when observed empty, so dead regions don't accumulate.

(def ^:private ^ConcurrentHashMap waiters (ConcurrentHashMap.))

(defn- ^ConcurrentLinkedQueue waiter-queue
  [region byte-off]
  (let [k [region (long byte-off)]
        q (.get waiters k)]
    (if q
      q
      (let [nq (ConcurrentLinkedQueue.)]
        (let [prev (.putIfAbsent waiters k nq)]
          (if prev prev nq))))))

(defn- waiter-dequeue
  "Remove thread t from region/offset's waiter queue; drop the queue key
   when it is observed empty."
  [region byte-off ^Thread t]
  (let [k [region (long byte-off)]
        ^ConcurrentLinkedQueue q (.get waiters k)]
    (when q
      (.remove q t)
      (when (.isEmpty q)
        (.remove waiters k q)))))

;; Forward declaration — open-lustre-region is defined below but
;; referenced in open-mmap-region.
(declare open-lustre-region)

;; -----------------------------------------------------------------------
;; JvmByteBufferRegion — unified heap/mmap region (no Unsafe)
;; -----------------------------------------------------------------------
;; One IMemRegion implementation for both backings.  The only difference
;; is how the ByteBuffer is obtained:
;;   heap: ByteBuffer/wrap over a zeroed byte[]      (make-heap-region)
;;   mmap: MappedByteBuffer from FileChannel.map     (open-mmap-region)
;; All atomics go through the VarHandle view method handles above
;; (volatile get/set, compareAndExchange, getAndAdd, getAndSet) with
;; explicit little-endian order.  Wait/notify is real blocking via
;; LockSupport.park/unpark, so -supports-watch? is true.
(deftype JvmByteBufferRegion [^ByteBuffer buf ^long size]
  mem/IMemRegion
  (-byte-length [_] size)

  ;; i32/i64 loads and stores tolerate unaligned offsets (DataView-style
  ;; fallback) because HAMT node interiors pack [len:i32][bytes] tightly.
  ;; Atomic RMW ops require alignment and throw otherwise.

  (-load-i32 [_ byte-off]
    (bb-load-i32 buf (int byte-off)))

  (-store-i32! [_ byte-off val]
    (bb-store-i32! buf (int byte-off) (long val))
    nil)

  ;; compareAndExchange returns the witness (previous) value — exactly
  ;; the protocol's "returns the old value" contract.
  (-cas-i32! [_ byte-off expected desired]
    (let [off (int byte-off)]
      (when-not (zero? (bit-and off 3))
        (unaligned-atomic-ex :cas-i32 byte-off 4))
      (int (.invoke I32-CAS (args4 buf off
                                         (unchecked-int expected)
                                         (unchecked-int desired))))))

  ;; getAndAdd returns the value BEFORE the add.
  (-add-i32! [_ byte-off delta]
    (let [off (int byte-off)]
      (when-not (zero? (bit-and off 3))
        (unaligned-atomic-ex :add-i32 byte-off 4))
      (int (.invoke I32-ADD (args3 buf off
                                         (unchecked-int delta))))))

  (-sub-i32! [_ byte-off delta]
    (let [off (int byte-off)]
      (when-not (zero? (bit-and off 3))
        (unaligned-atomic-ex :sub-i32 byte-off 4))
      (int (.invoke I32-ADD (args3 buf off
                                         (unchecked-int (- delta)))))))

  (-exchange-i32! [_ byte-off val]
    (let [off (int byte-off)]
      (when-not (zero? (bit-and off 3))
        (unaligned-atomic-ex :exchange-i32 byte-off 4))
      (int (.invoke I32-XCHG (args3 buf off
                                          (unchecked-int val))))))

  (-load-i64 [_ byte-off]
    (bb-load-i64 buf (int byte-off)))

  (-store-i64! [_ byte-off val]
    (bb-store-i64! buf (int byte-off) (long val))
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (let [off (int byte-off)]
      (when-not (zero? (bit-and off 7))
        (unaligned-atomic-ex :cas-i64 byte-off 8))
      (long (.invoke I64-CAS (args4 buf off
                                          (long expected)
                                          (long desired))))))

  (-add-i64! [_ byte-off delta]
    (let [off (int byte-off)]
      (when-not (zero? (bit-and off 7))
        (unaligned-atomic-ex :add-i64 byte-off 8))
      (long (.invoke I64-ADD (args3 buf off
                                          (long delta))))))

  (-sub-i64! [_ byte-off delta]
    (let [off (int byte-off)]
      (when-not (zero? (bit-and off 7))
        (unaligned-atomic-ex :sub-i64 byte-off 8))
      (long (.invoke I64-ADD (args3 buf off
                                          (long (- delta)))))))

  (-wait-i32! [this byte-off expected timeout-ms]
    (let [exp      (int expected)
          off      (int byte-off)
          load-now (fn [] (bb-load-i32 buf off))
          deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
      (loop []
        (let [cur (load-now)]
          (cond
            (not= cur exp)
            :not-equal

            (>= (System/nanoTime) deadline)
            :timed-out

            :else
            (let [q (waiter-queue this byte-off)
                  t (Thread/currentThread)]
              (.add q t)
              ;; Re-check after registering: a notify that fired between
              ;; the first check and registration must not be missed.
              (if (not= (load-now) exp)
                (do (waiter-dequeue this byte-off t)
                    :not-equal)
                (do (LockSupport/parkNanos (- deadline (System/nanoTime)))
                    (waiter-dequeue this byte-off t)
                    (recur)))))))))

  (-notify-i32! [this byte-off n]
    (let [k [this (long byte-off)]
          ^ConcurrentLinkedQueue q (.get waiters k)]
      (if (nil? q)
        0
        (let [woken (loop [w 0]
                      (if (>= w (long n))
                        w
                        (if-let [^Thread t (.poll q)]
                          (do (LockSupport/unpark t)
                              (recur (inc w)))
                          w)))]
          (when (.isEmpty q)
            (.remove waiters k q))
          woken))))

  (-supports-watch? [_]
    ;; Real blocking wait is available via LockSupport.
    true)

  (-read-bytes [_ byte-off len]
    ;; Absolute bulk get — does not touch the buffer's position.
    (let [dst (byte-array (int len))]
      (.get buf (int byte-off) dst 0 (int len))
      dst))

  (-write-bytes! [_ byte-off src]
    ;; Absolute bulk put — immediately visible to other processes
    ;; sharing the same MAP_SHARED mapping.
    (.put buf (int byte-off) ^bytes src 0 (alength ^bytes src))
    nil)

  ;; --- durability (eve.mem/ISyncableRegion): a mapped buffer forces; a
  ;; heap buffer (make-heap-region) has nothing to force ---
  mem/ISyncableRegion
  (-sync! [_]
    (when (instance? MappedByteBuffer buf)
      (.force ^MappedByteBuffer buf))
    nil)

  (-sync-range! [_ byte-off len]
    (when (instance? MappedByteBuffer buf)
      (.force ^MappedByteBuffer buf (int byte-off) (int len)))
    nil))

(defn open-mmap-region
  "Open (or create) a file-backed shared memory region at path-str.
   If the file does not exist it is created.  If the file is smaller than
   size-bytes it is grown via RandomAccessFile.setLength.
   Returns a JvmByteBufferRegion backed by a MAP_SHARED mapping.

   Atomics go through VarHandle view handles (no sun.misc.Unsafe);
   bulk I/O through absolute ByteBuffer bulk ops.

   Example:
     (mem/open-mmap-region \"/tmp/eve.main\" (* 256 1024 1024))"
  ([path-str size-bytes] (open-mmap-region path-str size-bytes nil))
  ([path-str size-bytes opts]
  (if (:lustre? opts)
    (open-lustre-region path-str size-bytes)
    (let [size  (long size-bytes)
          ;; Ensure the file exists and is at least size-bytes long.
          ;; Only grow the file — never truncate an existing larger file
        ;; (e.g. a 1 MB domain file peeked at 4096 bytes must not be truncated).
        _     (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
                (try (when (< (.length raf) size) (.setLength raf size))
                     (finally (.close raf))))
        ;; Map the file.  The mapping survives channel close, so the
        ;; channel is closed immediately; the MappedByteBuffer stays
        ;; valid for the region's lifetime.
        path  (Paths/get ^String path-str (into-array String []))]
      (with-open [^FileChannel fc
                  (FileChannel/open path
                    (into-array OpenOption
                      [StandardOpenOption/READ
                       StandardOpenOption/WRITE]))]
        (JvmByteBufferRegion. (.map fc FileChannel$MapMode/READ_WRITE 0 size)
                              size))))))

;; -----------------------------------------------------------------------
;; Heap regions — same JvmByteBufferRegion over a heap byte[]
;; -----------------------------------------------------------------------

(defn make-heap-region
  "Create an IMemRegion backed by a zero-initialized heap byte array.
   Thread-safe: all i32/i64 ops use VarHandle volatile/atomic access
   with full cross-thread visibility (the same guarantee a
   SharedArrayBuffer gives JS workers).  Unlike open-mmap-region, this
   is not file-backed — data lives only in the JVM heap and is not
   visible to other processes."
  [size-bytes]
  (JvmByteBufferRegion. (ByteBuffer/wrap (byte-array (int size-bytes)))
                        (long size-bytes)))

;; -----------------------------------------------------------------------
;; LustreJvmMmapRegion — fcntl byte-range locking for cross-node atomics
;; -----------------------------------------------------------------------
;; Uses mmap for data access but Java FileChannel.lock() (which maps to
;; fcntl F_SETLKW on Linux) for all atomic operations. Enables Eve atoms
;; to work across machines on a Lustre filesystem mounted with -o flock.
;;
;; A ReentrantLock serializes intra-JVM threads because POSIX fcntl locks
;; are process-scoped — all threads in the JVM share one lock identity.
;; The FileChannel.lock() handles inter-process coordination.
;;
;; Memory access itself is via the shared VarHandle view handles (no
;; sun.misc.Unsafe); the MappedByteBuffer is shared with open-mmap-region.

(deftype LustreJvmMmapRegion
  [^MappedByteBuffer mapped-buf
   ^long size
   ^FileChannel lock-channel
   ^ReentrantLock thread-lock]

  mem/IMemRegion
  (-byte-length [_] size)

  ;; --- atomic i32 via fcntl (FileChannel.lock) ---

  (-load-i32 [_ byte-off]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (bb-load-i32 mapped-buf (int byte-off))
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-store-i32! [_ byte-off val]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (bb-store-i32! mapped-buf (int byte-off) (long val))
          (.force mapped-buf (int byte-off) 4)
          (finally (.release fl))))
      (finally (.unlock thread-lock)))
    nil)

  (-cas-i32! [_ byte-off expected desired]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [cur (bb-load-i32 mapped-buf (int byte-off))]
            (when (= cur (unchecked-int expected))
              (bb-store-i32! mapped-buf (int byte-off) (long desired))
              (.force mapped-buf (int byte-off) 4))
            cur)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-add-i32! [_ byte-off delta]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [old (bb-load-i32 mapped-buf (int byte-off))
                nv  (unchecked-int (+ old (unchecked-int delta)))]
            (bb-store-i32! mapped-buf (int byte-off) (long nv))
            (.force mapped-buf (int byte-off) 4)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-sub-i32! [_ byte-off delta]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [old (bb-load-i32 mapped-buf (int byte-off))
                nv  (unchecked-int (- old (unchecked-int delta)))]
            (bb-store-i32! mapped-buf (int byte-off) (long nv))
            (.force mapped-buf (int byte-off) 4)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-exchange-i32! [_ byte-off val]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [old (bb-load-i32 mapped-buf (int byte-off))]
            (bb-store-i32! mapped-buf (int byte-off) (long val))
            (.force mapped-buf (int byte-off) 4)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  ;; --- atomic i64 via fcntl ---

  (-load-i64 [_ byte-off]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (bb-load-i64 mapped-buf (int byte-off))
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-store-i64! [_ byte-off val]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (bb-store-i64! mapped-buf (int byte-off) (long val))
          (.force mapped-buf (int byte-off) 8)
          (finally (.release fl))))
      (finally (.unlock thread-lock)))
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (let [cur (bb-load-i64 mapped-buf (int byte-off))]
            (when (= cur (long expected))
              (bb-store-i64! mapped-buf (int byte-off) (long desired))
              (.force mapped-buf (int byte-off) 8))
            cur)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-add-i64! [_ byte-off delta]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (let [old (bb-load-i64 mapped-buf (int byte-off))
                nv  (+ old (long delta))]
            (bb-store-i64! mapped-buf (int byte-off) (long nv))
            (.force mapped-buf (int byte-off) 8)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-sub-i64! [_ byte-off delta]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (let [old (bb-load-i64 mapped-buf (int byte-off))
                nv  (- old (long delta))]
            (bb-store-i64! mapped-buf (int byte-off) (long nv))
            (.force mapped-buf (int byte-off) 8)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  ;; --- wait/notify — polling fallback (same as JvmMmapRegion) ---

  (-wait-i32! [this byte-off expected timeout-ms]
    (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
      (loop []
        (let [cur (-load-i32 this byte-off)]
          (cond
            (not= cur (int expected))                  :not-equal
            (>= (System/currentTimeMillis) deadline)   :timed-out
            :else (do (Thread/sleep 0 100000)
                      (recur)))))))

  (-notify-i32! [_ _byte-off _n] 0)

  (-supports-watch? [_] false)

  ;; --- byte I/O — absolute bulk ops on the mapped buffer ---

  (-read-bytes [_ byte-off len]
    (let [dst (byte-array (int len))]
      (.get ^MappedByteBuffer mapped-buf (int byte-off) dst 0 (int len))
      dst))

  (-write-bytes! [_ byte-off src]
    (.put ^MappedByteBuffer mapped-buf (int byte-off) ^bytes src 0 (alength ^bytes src))
    nil)

  ;; --- durability (eve.mem/ISyncableRegion): the mapped buffer forces ---
  mem/ISyncableRegion
  (-sync! [_]
    (.force ^MappedByteBuffer mapped-buf)
    nil)

  (-sync-range! [_ byte-off len]
    (.force ^MappedByteBuffer mapped-buf (int byte-off) (int len))
    nil))

(defn open-lustre-region
  "Open (or create) a file-backed Lustre-compatible memory region at path-str.
   Like open-mmap-region but keeps a FileChannel open for fcntl byte-range
   locking. Uses a ReentrantLock for intra-JVM thread serialization.
   Returns a LustreJvmMmapRegion."
  [path-str size-bytes]
  (let [size  (long size-bytes)
        _     (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
                (try (when (< (.length raf) size) (.setLength raf size))
                     (finally (.close raf))))
        path  (Paths/get ^String path-str (into-array String []))
        ;; Map as MappedByteBuffer for atomic access and scoped force(index, length).
        ^FileChannel map-ch (FileChannel/open path
                              (into-array OpenOption
                                [StandardOpenOption/READ
                                 StandardOpenOption/WRITE]))
        ^MappedByteBuffer mbb (.map map-ch FileChannel$MapMode/READ_WRITE 0 size)
        ;; Separate FileChannel kept open for fcntl locking —
        ;; MUST NOT be closed until the region is disposed.
        lock-ch (FileChannel/open path
                  (into-array OpenOption
                    [StandardOpenOption/READ
                     StandardOpenOption/WRITE]))]
    (LustreJvmMmapRegion. mbb size lock-ch (ReentrantLock.))))

(defn lustre-region?
  "true for a region whose atomics are fcntl byte-range locks (a Lustre mount)."
  [r]
  (instance? LustreJvmMmapRegion r))
