(ns eve.mem
  "IMemRegion — platform-neutral abstraction over a fixed-size region of
   shared memory that supports atomic int32 operations.

   EVE requires only three things from a shared-memory backing:

     1. Atomic int32 read / write / CAS / add / sub (for lock fields,
        epoch counters, reader-map counters, block-descriptor fields)
     2. Futex-like wait / notify on int32 slots (for sleep and coordination)
     3. Bulk non-atomic byte I/O (for reading and writing serialized values
        in the data region)

   This protocol captures exactly that contract. Higher-level EVE code
   (shared_atom, util, alloc …) works against IMemRegion and need not care
   about what backs it.

   Implementations:

     JsSabRegion     — wraps js/SharedArrayBuffer + js/Atomics
                       (browser; Node worker_threads intra-process)          [CLJS]
     NodeMmapRegion  — wraps a file-backed MAP_SHARED mmap via the
                       native addon in native/mmap_cas.cc
                       (Node cross-process IPC, e.g. Node ↔ JVM)            [CLJS]
     JvmMmapRegion   — wraps java.lang.foreign.MemorySegment (Panama FFM)
                       with Unsafe atomics (Java 21+)                        [CLJ]

   All byte-off arguments are BYTE offsets. Atomic ops require 4-byte
   alignment (enforced by the caller — util.cljs already does this via
   the (* field-idx 4) convention)."
  #?(:bb
     (:import
      [java.io RandomAccessFile]
      [java.nio ByteBuffer ByteOrder MappedByteBuffer]
      [java.nio.channels FileChannel FileChannel$MapMode]
      [java.nio.file OpenOption Paths StandardOpenOption]
      [java.util Date UUID])
     :clj
     (:import
      [java.io RandomAccessFile]
      [java.lang.foreign Arena MemorySegment]
      [java.nio ByteBuffer ByteOrder MappedByteBuffer]
      [java.nio.channels FileChannel FileChannel$MapMode]
      [java.nio.file OpenOption Paths StandardOpenOption]
      [java.util Date UUID]
      [java.util.concurrent.locks ReentrantLock]))
  (:require
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol IMemRegion
  "A fixed-size region of shared memory with atomic int32 primitives.
   All offsets are byte offsets; atomic ops require 4-byte alignment."

  ;; --- metadata ---
  (-byte-length [r]
    "Total size of the region in bytes.")

  ;; --- atomic int32 ops ---
  (-load-i32 [r byte-off]
    "Atomically load the i32 at byte-off (acquire semantics).
     Equivalent to Atomics.load on an Int32Array at (byte-off / 4).")

  (-store-i32! [r byte-off val]
    "Atomically store val at byte-off (release semantics).
     Equivalent to Atomics.store on an Int32Array at (byte-off / 4).")

  (-cas-i32! [r byte-off expected desired]
    "Atomic compare-and-exchange on the i32 at byte-off.
     Returns the *old* value — success iff (= returned expected).
     Equivalent to Atomics.compareExchange.")

  (-add-i32! [r byte-off delta]
    "Atomically add delta to the i32 at byte-off. Returns old value.")

  (-sub-i32! [r byte-off delta]
    "Atomically subtract delta from the i32 at byte-off. Returns old value.")

  (-exchange-i32! [r byte-off val]
    "Atomically store val at byte-off and return the old value.
     Equivalent to Atomics.exchange on an Int32Array at (byte-off / 4).")

  ;; --- atomic int64 ops ---
  ;; Required only for mmap regions (slab headers).  SAB regions may throw.
  ;; Values are JS Number (double), lossless up to 2^53 (9 PB).
  ;; On JVM, values are native longs (full 64-bit range).

  (-load-i64 [r byte-off]
    "Atomically load the i64 at byte-off (acquire semantics).
     Requires 8-byte alignment.")

  (-store-i64! [r byte-off val]
    "Atomically store val at byte-off (release semantics).
     Requires 8-byte alignment.")

  (-cas-i64! [r byte-off expected desired]
    "Atomic compare-and-exchange on the i64 at byte-off.
     Returns the *old* value — success iff (= returned expected).")

  (-add-i64! [r byte-off delta]
    "Atomically add delta to the i64 at byte-off. Returns old value.")

  (-sub-i64! [r byte-off delta]
    "Atomically subtract delta from the i64 at byte-off. Returns old value.")

  ;; --- futex-like wait / notify ---
  (-wait-i32! [r byte-off expected timeout-ms]
    "Block the calling thread until the i32 at byte-off differs from
     expected, or until timeout-ms milliseconds elapse.
     Returns :ok | :not-equal | :timed-out.
     May throw on the browser main thread (Atomics.wait is forbidden there).")

  (-notify-i32! [r byte-off n]
    "Wake up to n threads blocked in -wait-i32! on byte-off.
     Returns the number of threads actually woken.")

  (-supports-watch? [r]
    "Returns true if this region can back an Atomics.waitAsync watch loop.
     JsSabRegion returns true (SAB is a valid Atomics target).
     File-backed mmap regions (NodeMmapRegion, JvmMmapRegion) return false.")

  ;; --- non-atomic bulk byte I/O (data region reads / writes) ---
  (-read-bytes [r byte-off len]
    "Copy len bytes starting at byte-off into a new byte array.
     Non-atomic — callers are responsible for any needed synchronisation.")

  (-write-bytes! [r byte-off src]
    "Copy all bytes from src into the region at byte-off.
     Non-atomic — callers are responsible for any needed synchronisation."))

;; ---------------------------------------------------------------------------
;; Lustre mode flag — when true, open-mmap-region returns Lustre regions
;; that use fcntl byte-range locking for cross-node atomics.
;; ---------------------------------------------------------------------------

#?(:cljs (defonce ^:dynamic *lustre-mode* false)
   :clj  (def ^:dynamic *lustre-mode* false))

;; ---------------------------------------------------------------------------
;; CLJS implementations
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     ;; --- Helpers ---

     (defn- byte-off->i32-idx
       "Convert a 4-byte-aligned byte offset to the Int32Array element index."
       [byte-off]
       (unsigned-bit-shift-right byte-off 2))

     (defn- wait-result->kw
       "Coerce the string returned by Atomics.wait to a keyword."
       [s]
       (case s
         "ok"         :ok
         "not-equal"  :not-equal
         "timed-out"  :timed-out
         :timed-out))

     ;; --- JsSabRegion — SharedArrayBuffer + Atomics ---
     ;; This is the existing EVE backing for both browser and Node worker_threads.
     ;; worker_threads workers share SABs via postMessage / workerData; the Atomics
     ;; API provides the necessary synchronisation.

     (deftype JsSabRegion [^js sab
                           ^js -i32   ; cached Int32Array view — atomic ops
                           ^js -u8]   ; cached Uint8Array view  — byte I/O
       IMemRegion
       (-byte-length [_]
         (.-byteLength sab))

       (-load-i32 [_ byte-off]
         (js/Atomics.load -i32 (byte-off->i32-idx byte-off)))

       (-store-i32! [_ byte-off val]
         (js/Atomics.store -i32 (byte-off->i32-idx byte-off) val)
         nil)

       (-cas-i32! [_ byte-off expected desired]
         (js/Atomics.compareExchange -i32 (byte-off->i32-idx byte-off) expected desired))

       (-add-i32! [_ byte-off delta]
         (js/Atomics.add -i32 (byte-off->i32-idx byte-off) delta))

       (-sub-i32! [_ byte-off delta]
         (js/Atomics.sub -i32 (byte-off->i32-idx byte-off) delta))

       (-exchange-i32! [_ byte-off val]
         (js/Atomics.exchange -i32 (byte-off->i32-idx byte-off) val))

       (-load-i64 [_ _byte-off]
         (throw (ex-info "JsSabRegion does not support i64 ops" {})))
       (-store-i64! [_ _byte-off _val]
         (throw (ex-info "JsSabRegion does not support i64 ops" {})))
       (-cas-i64! [_ _byte-off _expected _desired]
         (throw (ex-info "JsSabRegion does not support i64 ops" {})))
       (-add-i64! [_ _byte-off _delta]
         (throw (ex-info "JsSabRegion does not support i64 ops" {})))
       (-sub-i64! [_ _byte-off _delta]
         (throw (ex-info "JsSabRegion does not support i64 ops" {})))

       (-wait-i32! [_ byte-off expected timeout-ms]
         (wait-result->kw
          (js/Atomics.wait -i32 (byte-off->i32-idx byte-off) expected timeout-ms)))

       (-notify-i32! [_ byte-off n]
         (js/Atomics.notify -i32 (byte-off->i32-idx byte-off) n))

       (-supports-watch? [_] true)

       (-read-bytes [_ byte-off len]
         (.slice -u8 byte-off (+ byte-off len)))

       (-write-bytes! [_ byte-off src]
         (.set -u8 src byte-off)
         nil))

     (defn js-sab-region
       "Wrap an existing js/SharedArrayBuffer in a JsSabRegion."
       [^js sab]
       (JsSabRegion. sab (js/Int32Array. sab) (js/Uint8Array. sab)))

     (defn make-js-sab-region
       "Allocate a fresh js/SharedArrayBuffer of byte-length bytes and wrap it."
       [byte-length]
       (js-sab-region (js/SharedArrayBuffer. byte-length)))

     ;; --- NodeMmapRegion — file-backed MAP_SHARED mmap (cross-process) ---
     ;; Used when EVE needs to share memory with a process that cannot receive a
     ;; SharedArrayBuffer via postMessage — e.g. a JVM process, or a separate
     ;; Node process not related by worker_threads.
     ;;
     ;; The backing is a native Node addon (native/mmap_cas.cc) that:
     ;;   • opens / creates a file, ftruncates to size, and calls mmap(MAP_SHARED)
     ;;   • returns the mapping as a Node.js Buffer (zero-copy, same virtual page)
     ;;   • provides atomic int32 ops via std::atomic_ref<int32_t> (C++20 stdlib)
     ;;   • provides futex wait/notify via Linux futex(2) syscall directly
     ;;
     ;; The Buffer's underlying memory IS the mmap'd page — reads and writes to the
     ;; Buffer are immediately visible to any other process (or JVM thread) that has
     ;; the same file mapped.

     ;; Lazy reference to the native addon — auto-loaded on first access
     (defonce ^:private native-addon (atom nil))

     (defn- try-auto-load-addon!
       "Attempt to auto-load the mmap_cas native addon via node-gyp-build.
        Tries two strategies:
        1. resolve 'eve-native/package.json' — works when eve is an npm dep
        2. walk up from this file's compiled location — works in local dev
        Returns the addon or nil if not available."
       []
       (try
         (let [ngb  (js/require "node-gyp-build")
               path (js/require "path")
               pkg-root (try
                          ;; Strategy 1: npm dep — resolve via node module resolution
                          (.dirname path (js/require.resolve "eve-native/package.json"))
                          (catch :default _
                            ;; Strategy 2: local dev — walk up from compiled output
                            (.resolve path (.dirname path (.-filename js/module)) ".." "..")))]
           (reset! native-addon (ngb pkg-root))
           @native-addon)
         (catch :default _
           nil)))

     (defn load-native-addon!
       "Load the mmap_cas native addon explicitly. Normally not needed —
        the addon is auto-loaded on first use via node-gyp-build."
       [addon-js-obj]
       (reset! native-addon addon-js-obj))

     (defn native-addon-loaded?
       "Returns true if the mmap_cas native addon has been loaded."
       []
       (some? @native-addon))

     (defn- native
       "Return the loaded native addon, auto-loading if needed."
       ^js []
       (or @native-addon
           (try-auto-load-addon!)
           (throw (ex-info "mmap_cas native addon not found — run npm install to build it" {}))))

     (deftype NodeMmapRegion [^js buf   ; Node.js Buffer from addon open()
                              size]     ; byte length
       IMemRegion
       (-byte-length [_]
         size)

       (-load-i32 [_ byte-off]
         (.load32 ^js (native) buf byte-off))

       (-store-i32! [_ byte-off val]
         (.store32 ^js (native) buf byte-off val)
         nil)

       (-cas-i32! [_ byte-off expected desired]
         ;; Native cas32 returns the *old* value — same contract as Atomics.compareExchange
         (.cas32 ^js (native) buf byte-off expected desired))

       (-add-i32! [_ byte-off delta]
         (.add32 ^js (native) buf byte-off delta))

       (-sub-i32! [_ byte-off delta]
         (.sub32 ^js (native) buf byte-off delta))

       (-exchange-i32! [_ byte-off val]
         ;; Simulate exchange via CAS loop (native addon may not expose exchange32).
         (loop []
           (let [old (.load32 ^js (native) buf byte-off)]
             (if (== old (.cas32 ^js (native) buf byte-off old val))
               old
               (recur)))))

       (-load-i64 [_ byte-off]
         (.load64 ^js (native) buf byte-off))

       (-store-i64! [_ byte-off val]
         (.store64 ^js (native) buf byte-off val)
         nil)

       (-cas-i64! [_ byte-off expected desired]
         (.cas64 ^js (native) buf byte-off expected desired))

       (-add-i64! [_ byte-off delta]
         (.add64 ^js (native) buf byte-off delta))

       (-sub-i64! [_ byte-off delta]
         (.sub64 ^js (native) buf byte-off delta))

       (-wait-i32! [_ byte-off expected timeout-ms]
         ;; Native wait32 returns "ok" | "not-equal" | "timed-out"
         (wait-result->kw (.wait32 ^js (native) buf byte-off expected timeout-ms)))

       (-notify-i32! [_ byte-off n]
         (.notify32 ^js (native) buf byte-off n))

       (-supports-watch? [_] false)

       (-read-bytes [_ byte-off len]
         ;; Buffer.slice returns a view sharing the same ArrayBuffer.
         ;; Preserve byteOffset so deserialize-element reads from the correct position.
         (let [sliced (.slice buf byte-off (+ byte-off len))]
           (js/Uint8Array. (.-buffer sliced) (.-byteOffset sliced) (.-byteLength sliced))))

       (-write-bytes! [_ byte-off src]
         ;; src is a Uint8Array; copy into the Buffer via Node's Buffer.from +
         ;; Buffer.copy for zero-extra-copy semantics
         (let [src-buf (js/Buffer.from (.-buffer src) (.-byteOffset src) (.-byteLength src))]
           (.copy src-buf buf byte-off))
         nil))

     (defn open-mmap-region
       "Open (or create) a file-backed shared memory region at path.
        If the file is new it is truncated to size-bytes.
        Returns a NodeMmapRegion (default) or LustreMmapRegion (when
        *lustre-mode* is true).

        Example:
          (mem/open-mmap-region \"/tmp/eve-shard-0.mem\" (* 64 1024 1024))"
       [path size-bytes]
       (if *lustre-mode*
         (let [result (.openWithFd ^js (native) path size-bytes)]
           (LustreMmapRegion. (.-buf result) (.-fd result) size-bytes))
         (let [buf (.open ^js (native) path size-bytes)]
           (NodeMmapRegion. buf size-bytes))))

     ;; --- LustreMmapRegion — fcntl byte-range locking for cross-node atomics ---
     ;; Uses mmap for data access (zero-copy reads) but fcntl byte-range locks
     ;; for all atomic operations, enabling Eve atoms to work across machines on
     ;; a Lustre filesystem mounted with -o flock.
     ;;
     ;; Pattern: lock N bytes via fcntl(F_SETLKW), read via mmap, compare,
     ;; conditionally write + msync, unlock. This is a pessimistic lock-based
     ;; CAS — slower than hardware CAS (~50-200us vs ~10ns on Lustre) but
     ;; correct across nodes via the Lustre LDLM.

     (deftype LustreMmapRegion [^js buf   ; Node.js Buffer (mmap'd page)
                                 fd        ; file descriptor (kept open for fcntl)
                                 size]     ; byte length
       IMemRegion
       (-byte-length [_] size)

       ;; --- atomic i32 via fcntl ---
       (-load-i32 [_ byte-off]
         (.fcntlLoad32 (native) fd buf byte-off))

       (-store-i32! [_ byte-off val]
         (.fcntlStore32 (native) fd buf byte-off val)
         nil)

       (-cas-i32! [_ byte-off expected desired]
         (.fcntlCas32 (native) fd buf byte-off expected desired))

       (-add-i32! [_ byte-off delta]
         (.fcntlAdd32 (native) fd buf byte-off delta))

       (-sub-i32! [_ byte-off delta]
         (.fcntlSub32 (native) fd buf byte-off delta))

       (-exchange-i32! [_ byte-off val]
         (.fcntlExchange32 (native) fd buf byte-off val))

       ;; --- atomic i64 via fcntl ---
       (-load-i64 [_ byte-off]
         (.fcntlLoad64 (native) fd buf byte-off))

       (-store-i64! [_ byte-off val]
         (.fcntlStore64 (native) fd buf byte-off val)
         nil)

       (-cas-i64! [_ byte-off expected desired]
         (.fcntlCas64 (native) fd buf byte-off expected desired))

       (-add-i64! [_ byte-off delta]
         (.fcntlAdd64 (native) fd buf byte-off delta))

       (-sub-i64! [_ byte-off delta]
         (.fcntlSub64 (native) fd buf byte-off delta))

       ;; --- wait/notify — polling fallback (no futex across Lustre nodes) ---
       (-wait-i32! [this byte-off expected timeout-ms]
         (let [deadline (+ (js/Date.now) timeout-ms)]
           (loop []
             (let [cur (-load-i32 this byte-off)]
               (cond
                 (not= cur expected) :not-equal
                 (>= (js/Date.now) deadline) :timed-out
                 :else (do (.nanosleep (native) 100000)  ; 100µs
                           (recur)))))))

       (-notify-i32! [_ _byte-off _n] 0)

       (-supports-watch? [_] false)

       ;; --- byte I/O — direct mmap access (same as NodeMmapRegion) ---
       (-read-bytes [_ byte-off len]
         (let [sliced (.slice buf byte-off (+ byte-off len))]
           (js/Uint8Array. (.-buffer sliced) (.-byteOffset sliced) (.-byteLength sliced))))

       (-write-bytes! [_ byte-off src]
         (let [src-buf (js/Buffer.from (.-buffer src) (.-byteOffset src) (.-byteLength src))]
           (.copy src-buf buf byte-off))
         nil))

     (defn open-lustre-region
       "Open (or create) a file-backed Lustre-compatible memory region at path.
        Like open-mmap-region but keeps the fd open for fcntl byte-range locking.
        Returns a LustreMmapRegion."
       [path size-bytes]
       (let [result (.openWithFd (native) path size-bytes)]
         (LustreMmapRegion. (.-buf result) (.-fd result) size-bytes)))))

;; ---------------------------------------------------------------------------
;; Babashka implementations — MappedByteBuffer (no Panama FFM, no Unsafe)
;; ---------------------------------------------------------------------------
;; bb takes `:bb` before `:clj` in reader conditionals.
;; Uses MappedByteBuffer for mmap regions and ByteBuffer.wrap for heap regions.
;; Atomic ops are simulated via locking — sufficient for single-process bb scripts.

#?(:bb
   (do
     ;; Locking object for simulated atomics. Babashka is single-threaded for
     ;; scripting, but we still do CAS-style reads; a global lock suffices.
     (def ^:private bb-lock (Object.))

     ;; BbMmapRegion — file-backed MappedByteBuffer with simulated atomics
     (deftype BbMmapRegion [^MappedByteBuffer mbb ^long size]
       IMemRegion

       (-byte-length [_] size)

       (-load-i32 [_ byte-off]
         (locking bb-lock
           (.getInt mbb (int byte-off))))

       (-store-i32! [_ byte-off val]
         (locking bb-lock
           (.putInt mbb (int byte-off) (unchecked-int val)))
         nil)

       (-cas-i32! [_ byte-off expected desired]
         (locking bb-lock
           (let [cur (.getInt mbb (int byte-off))]
             (when (== cur (int expected))
               (.putInt mbb (int byte-off) (unchecked-int desired)))
             cur)))

       (-add-i32! [_ byte-off delta]
         (locking bb-lock
           (let [old (.getInt mbb (int byte-off))]
             (.putInt mbb (int byte-off) (unchecked-int (+ old (int delta))))
             old)))

       (-sub-i32! [_ byte-off delta]
         (locking bb-lock
           (let [old (.getInt mbb (int byte-off))]
             (.putInt mbb (int byte-off) (unchecked-int (- old (int delta))))
             old)))

       (-exchange-i32! [_ byte-off val]
         (locking bb-lock
           (let [old (.getInt mbb (int byte-off))]
             (.putInt mbb (int byte-off) (unchecked-int val))
             old)))

       (-load-i64 [_ byte-off]
         (locking bb-lock
           (.getLong mbb (int byte-off))))

       (-store-i64! [_ byte-off val]
         (locking bb-lock
           (.putLong mbb (int byte-off) (long val)))
         nil)

       (-cas-i64! [_ byte-off expected desired]
         (locking bb-lock
           (let [cur (.getLong mbb (int byte-off))]
             (when (== cur (long expected))
               (.putLong mbb (int byte-off) (long desired)))
             cur)))

       (-add-i64! [_ byte-off delta]
         (locking bb-lock
           (let [old (.getLong mbb (int byte-off))]
             (.putLong mbb (int byte-off) (+ old (long delta)))
             old)))

       (-sub-i64! [_ byte-off delta]
         (locking bb-lock
           (let [old (.getLong mbb (int byte-off))]
             (.putLong mbb (int byte-off) (- old (long delta)))
             old)))

       (-wait-i32! [_ byte-off expected timeout-ms]
         (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
           (loop []
             (let [cur (locking bb-lock (.getInt mbb (int byte-off)))]
               (cond
                 (not= cur (int expected))                  :not-equal
                 (>= (System/currentTimeMillis) deadline)   :timed-out
                 :else (do (Thread/sleep 1)
                           (recur)))))))

       (-notify-i32! [_ _byte-off _n] 0)

       (-supports-watch? [_] false)

       (-read-bytes [_ byte-off len]
         (let [dst (byte-array len)]
           (locking bb-lock
             (.position mbb (int byte-off))
             (.get mbb dst))
           dst))

       (-write-bytes! [_ byte-off src]
         (locking bb-lock
           (.position mbb (int byte-off))
           (.put mbb ^bytes src))
         nil))

     (defn open-mmap-region
       "Open (or create) a file-backed shared memory region at path-str.
        Uses MappedByteBuffer (compatible with Babashka/GraalVM native-image)."
       [path-str size-bytes]
       (let [size  (long size-bytes)
             _     (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
                     (try (when (< (.length raf) size) (.setLength raf size))
                          (finally (.close raf))))
             path  (Paths/get ^String path-str (into-array String []))
             ^FileChannel fc
             (FileChannel/open path
               (into-array OpenOption
                 [StandardOpenOption/READ
                  StandardOpenOption/WRITE]))
             ^MappedByteBuffer mbb (.map fc FileChannel$MapMode/READ_WRITE 0 size)]
         (.close fc)
         (.order mbb ByteOrder/LITTLE_ENDIAN)
         (BbMmapRegion. mbb size)))

     ;; BbHeapRegion — byte[] backed by ByteBuffer.wrap with simulated atomics
     (deftype BbHeapRegion [^ByteBuffer bb-buf ^bytes backing ^long size]
       IMemRegion

       (-byte-length [_] size)

       (-load-i32 [_ byte-off]
         (locking bb-lock
           (.getInt bb-buf (int byte-off))))

       (-store-i32! [_ byte-off val]
         (locking bb-lock
           (.putInt bb-buf (int byte-off) (unchecked-int val)))
         nil)

       (-cas-i32! [_ byte-off expected desired]
         (locking bb-lock
           (let [cur (.getInt bb-buf (int byte-off))]
             (when (== cur (int expected))
               (.putInt bb-buf (int byte-off) (unchecked-int desired)))
             cur)))

       (-add-i32! [_ byte-off delta]
         (locking bb-lock
           (let [old (.getInt bb-buf (int byte-off))]
             (.putInt bb-buf (int byte-off) (unchecked-int (+ old (int delta))))
             old)))

       (-sub-i32! [_ byte-off delta]
         (locking bb-lock
           (let [old (.getInt bb-buf (int byte-off))]
             (.putInt bb-buf (int byte-off) (unchecked-int (- old (int delta))))
             old)))

       (-exchange-i32! [_ byte-off val]
         (locking bb-lock
           (let [old (.getInt bb-buf (int byte-off))]
             (.putInt bb-buf (int byte-off) (unchecked-int val))
             old)))

       (-load-i64 [_ byte-off]
         (locking bb-lock
           (.getLong bb-buf (int byte-off))))

       (-store-i64! [_ byte-off val]
         (locking bb-lock
           (.putLong bb-buf (int byte-off) (long val)))
         nil)

       (-cas-i64! [_ byte-off expected desired]
         (locking bb-lock
           (let [cur (.getLong bb-buf (int byte-off))]
             (when (== cur (long expected))
               (.putLong bb-buf (int byte-off) (long desired)))
             cur)))

       (-add-i64! [_ byte-off delta]
         (locking bb-lock
           (let [old (.getLong bb-buf (int byte-off))]
             (.putLong bb-buf (int byte-off) (+ old (long delta)))
             old)))

       (-sub-i64! [_ byte-off delta]
         (locking bb-lock
           (let [old (.getLong bb-buf (int byte-off))]
             (.putLong bb-buf (int byte-off) (- old (long delta)))
             old)))

       (-wait-i32! [_ byte-off expected timeout-ms]
         (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
           (loop []
             (let [cur (locking bb-lock (.getInt bb-buf (int byte-off)))]
               (cond
                 (not= cur (int expected))                  :not-equal
                 (>= (System/currentTimeMillis) deadline)   :timed-out
                 :else (do (Thread/sleep 1)
                           (recur)))))))

       (-notify-i32! [_ _byte-off _n] 0)

       (-supports-watch? [_] false)

       (-read-bytes [_ byte-off len]
         (java.util.Arrays/copyOfRange backing (int byte-off) (int (+ byte-off len))))

       (-write-bytes! [_ byte-off src]
         (System/arraycopy src 0 backing (int byte-off) (alength ^bytes src))
         nil))

     (defn make-heap-region
       "Create an IMemRegion backed by a zero-initialized heap byte array.
        Uses ByteBuffer.wrap for int32/int64 ops (Babashka-compatible)."
       [size-bytes]
       (let [ba  (byte-array (int size-bytes))
             buf (doto (ByteBuffer/wrap ba) (.order ByteOrder/LITTLE_ENDIAN))]
         (BbHeapRegion. buf ba (long size-bytes))))))

;; ---------------------------------------------------------------------------
;; JVM implementations
;; ---------------------------------------------------------------------------

#?(:bb nil
   :clj
   (do
     ;; sun.misc.Unsafe for atomic int32 ops over native memory.
     ;; Works on Java 21 (preview FFM) and Java 22+ (final FFM).
     ;; VarHandle.getVolatile/setVolatile are @PolymorphicSignature and cannot be
     ;; called via Clojure reflection; Unsafe provides the same atomics portably.
     (def ^:private ^sun.misc.Unsafe UNSAFE
       (let [f (.getDeclaredField sun.misc.Unsafe "theUnsafe")]
         (.setAccessible f true)
         (.get f nil)))

     ;; Forward declaration — LustreJvmMmapRegion and open-lustre-region are
     ;; defined after JvmHeapRegion but referenced in open-mmap-region.
     (declare open-lustre-region)

     ;; JvmMmapRegion stores the MemorySegment (for bulk copy) and its base native
     ;; address (for Unsafe ops, avoiding a .address() call on every atomic op).
     (deftype JvmMmapRegion [^MemorySegment seg ^long base-addr ^long size]
       IMemRegion

       (-byte-length [_] size)

       (-load-i32 [_ byte-off]
         ;; getIntVolatile provides acquire semantics
         (.getIntVolatile UNSAFE nil (+ base-addr (long byte-off))))

       (-store-i32! [_ byte-off val]
         ;; putIntVolatile provides release semantics
         (.putIntVolatile UNSAFE nil (+ base-addr (long byte-off)) (unchecked-int val))
         nil)

       (-cas-i32! [_ byte-off expected desired]
         ;; Simulate compareAndExchange (returns witness/old value).
         ;; sun.misc.Unsafe only has compareAndSwapInt (returns bool), so:
         ;; success → return expected; failure → read current value as witness.
         (let [addr (+ base-addr (long byte-off))
               exp  (unchecked-int expected)]
           (if (.compareAndSwapInt UNSAFE nil addr exp (unchecked-int desired))
             exp
             (.getIntVolatile UNSAFE nil addr))))

       (-add-i32! [_ byte-off delta]
         ;; getAndAddInt returns the value BEFORE the add
         (.getAndAddInt UNSAFE nil (+ base-addr (long byte-off)) (unchecked-int delta)))

       (-sub-i32! [_ byte-off delta]
         (.getAndAddInt UNSAFE nil (+ base-addr (long byte-off)) (unchecked-int (- delta))))

       (-exchange-i32! [_ byte-off val]
         ;; getAndSetInt provides atomic exchange semantics
         (.getAndSetInt UNSAFE nil (+ base-addr (long byte-off)) (unchecked-int val)))

       (-load-i64 [_ byte-off]
         (.getLongVolatile UNSAFE nil (+ base-addr (long byte-off))))

       (-store-i64! [_ byte-off val]
         (.putLongVolatile UNSAFE nil (+ base-addr (long byte-off)) (long val))
         nil)

       (-cas-i64! [_ byte-off expected desired]
         (let [addr (+ base-addr (long byte-off))
               exp  (long expected)]
           (if (.compareAndSwapLong UNSAFE nil addr exp (long desired))
             exp
             (.getLongVolatile UNSAFE nil addr))))

       (-add-i64! [_ byte-off delta]
         (.getAndAddLong UNSAFE nil (+ base-addr (long byte-off)) (long delta)))

       (-sub-i64! [_ byte-off delta]
         (.getAndAddLong UNSAFE nil (+ base-addr (long byte-off)) (long (- delta))))

       (-wait-i32! [_ byte-off expected timeout-ms]
         ;; Polling fallback — no JNI futex yet.
         (let [addr     (+ base-addr (long byte-off))
               deadline (+ (System/currentTimeMillis) (long timeout-ms))]
           (loop []
             (let [cur (.getIntVolatile UNSAFE nil addr)]
               (cond
                 (not= cur (int expected))                  :not-equal
                 (>= (System/currentTimeMillis) deadline)   :timed-out
                 :else (do (Thread/sleep 0 100000)           ; 100 µs park
                           (recur)))))))

       (-notify-i32! [_ _byte-off _n]
         ;; No-op on JVM polling path — threads self-wake via the loop above.
         0)

       (-supports-watch? [_]
         ;; JVM regions cannot be passed to Atomics.waitAsync.
         false)

       (-read-bytes [_ byte-off len]
         ;; Zero-copy slice of the mapped segment into a fresh byte[].
         (let [dst (byte-array len)]
           (MemorySegment/copy seg (long byte-off) (MemorySegment/ofArray dst) 0 (long len))
           dst))

       (-write-bytes! [_ byte-off src]
         ;; Write directly into the mapped page — immediately visible to other
         ;; processes sharing the same MAP_SHARED file.
         (MemorySegment/copy (MemorySegment/ofArray src) 0 seg (long byte-off) (long (alength src)))
         nil))

     (defn open-mmap-region
       "Open (or create) a file-backed shared memory region at path-str.
        If the file does not exist it is created.  If the file is smaller than
        size-bytes it is grown via RandomAccessFile.setLength.
        Returns a JvmMmapRegion backed by a MAP_SHARED mapping.

        Works on Java 21+ (Panama FFM). Uses FileChannel.map (Java 21) rather than
        MemorySegment.map (Java 22 only). Atomic ops via sun.misc.Unsafe.

        Example:
          (mem/open-mmap-region \"/tmp/eve.main\" (* 256 1024 1024))"
       [path-str size-bytes]
       (if *lustre-mode*
         (open-lustre-region path-str size-bytes)
         (let [size  (long size-bytes)
               ;; Ensure the file exists and is at least size-bytes long.
               ;; Only grow the file — never truncate an existing larger file
             ;; (e.g. a 1 MB domain file peeked at 4096 bytes must not be truncated).
             _     (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
                     (try (when (< (.length raf) size) (.setLength raf size))
                          (finally (.close raf))))
             ;; Map the file using FileChannel.map (available Java 21+).
             ;; MemorySegment.map was added in Java 22 and is NOT available in Java 21.
             path  (Paths/get ^String path-str (into-array String []))
             arena (Arena/ofShared)
             seg   (with-open [^FileChannel fc
                               (FileChannel/open path
                                 (into-array OpenOption
                                   [StandardOpenOption/READ
                                    StandardOpenOption/WRITE]))]
                     (.map fc FileChannel$MapMode/READ_WRITE 0 size arena))]
           (JvmMmapRegion. seg (.address seg) size))))

     ;; -----------------------------------------------------------------------
     ;; JvmHeapRegion — heap byte[] backed by Unsafe atomics
     ;; -----------------------------------------------------------------------
     ;; Same atomic guarantees as JvmMmapRegion but backed by a Java byte array
     ;; rather than a file-backed mmap.  Used for non-persistent (in-process)
     ;; EVE atom domains where cross-process visibility is not required.
     ;;
     ;; Unsafe.arrayBaseOffset(byte[]) gives the JVM heap base address of the
     ;; array; getIntVolatile/putIntVolatile/compareAndSwapInt then work on-heap
     ;; with full acquire/release/sequentially-consistent semantics.

     ;; Unsafe requires the array object as `Object` for heap atomics.
     ;; We store it untyped in the deftype field so Clojure does not try to
     ;; find a non-existent Unsafe.getIntVolatile(byte[], long) overload.
     (def ^:private BYTE_ARRAY_BASE_OFFSET
       ;; arrayBaseOffset returns int; widen to long for arithmetic
       (long (.arrayBaseOffset UNSAFE (Class/forName "[B"))))

     (deftype JvmHeapRegion [backing ^long size]
       ;; backing is a byte[] but untyped here — Clojure must see it as Object
       ;; so it resolves Unsafe methods against the (Object, long) signatures.
       IMemRegion

       (-byte-length [_] size)

       (-load-i32 [_ byte-off]
         (.getIntVolatile UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off))))

       (-store-i32! [_ byte-off val]
         (.putIntVolatile UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off)) (unchecked-int val))
         nil)

       (-cas-i32! [_ byte-off expected desired]
         (let [addr (+ BYTE_ARRAY_BASE_OFFSET (long byte-off))
               exp  (unchecked-int expected)]
           (if (.compareAndSwapInt UNSAFE backing addr exp (unchecked-int desired))
             exp
             (.getIntVolatile UNSAFE backing addr))))

       (-add-i32! [_ byte-off delta]
         (.getAndAddInt UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off)) (unchecked-int delta)))

       (-sub-i32! [_ byte-off delta]
         (.getAndAddInt UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off)) (unchecked-int (- delta))))

       (-exchange-i32! [_ byte-off val]
         (.getAndSetInt UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off)) (unchecked-int val)))

       (-load-i64 [_ byte-off]
         (.getLongVolatile UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off))))

       (-store-i64! [_ byte-off val]
         (.putLongVolatile UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off)) (long val))
         nil)

       (-cas-i64! [_ byte-off expected desired]
         (let [addr (+ BYTE_ARRAY_BASE_OFFSET (long byte-off))
               exp  (long expected)]
           (if (.compareAndSwapLong UNSAFE backing addr exp (long desired))
             exp
             (.getLongVolatile UNSAFE backing addr))))

       (-add-i64! [_ byte-off delta]
         (.getAndAddLong UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off)) (long delta)))

       (-sub-i64! [_ byte-off delta]
         (.getAndAddLong UNSAFE backing
           (+ BYTE_ARRAY_BASE_OFFSET (long byte-off)) (long (- delta))))

       (-wait-i32! [_ byte-off expected timeout-ms]
         (let [addr     (+ BYTE_ARRAY_BASE_OFFSET (long byte-off))
               deadline (+ (System/currentTimeMillis) (long timeout-ms))]
           (loop []
             (let [cur (.getIntVolatile UNSAFE backing addr)]
               (cond
                 (not= cur (int expected))                  :not-equal
                 (>= (System/currentTimeMillis) deadline)   :timed-out
                 :else (do (Thread/sleep 0 100000)
                           (recur)))))))

       (-notify-i32! [_ _byte-off _n] 0)

       (-supports-watch? [_] false)

       (-read-bytes [_ byte-off len]
         ;; Cast back to byte[] for Arrays.copyOfRange
         (let [ba ^bytes backing]
           (java.util.Arrays/copyOfRange ba (int byte-off) (int (+ byte-off len)))))

       (-write-bytes! [_ byte-off src]
         ;; Cast back to byte[] for System.arraycopy destination
         (let [ba ^bytes backing]
           (System/arraycopy src 0 ba (int byte-off) (alength ^bytes src)))
         nil))

     (defn make-heap-region
       "Create an IMemRegion backed by a zero-initialized heap byte array.
        Thread-safe: all i32 ops use sun.misc.Unsafe with volatile/atomic
        semantics.  Unlike JvmMmapRegion, this is not file-backed — data lives
        only in the JVM heap and is not visible to other processes."
       [size-bytes]
       (JvmHeapRegion. (byte-array (int size-bytes)) (long size-bytes)))

     ;; -----------------------------------------------------------------------
     ;; LustreJvmMmapRegion — fcntl byte-range locking for cross-node atomics
     ;; -----------------------------------------------------------------------
     ;; Uses mmap for data access (zero-copy reads via MemorySegment) but
     ;; Java FileChannel.lock() (which maps to fcntl F_SETLKW on Linux) for
     ;; all atomic operations. Enables Eve atoms to work across machines on
     ;; a Lustre filesystem mounted with -o flock.
     ;;
     ;; A ReentrantLock serializes intra-JVM threads because POSIX fcntl locks
     ;; are process-scoped — all threads in the JVM share one lock identity.
     ;; The FileChannel.lock() handles inter-process coordination.

     (deftype LustreJvmMmapRegion
       [^MemorySegment seg
        ^long base-addr
        ^long size
        ^FileChannel lock-channel
        ^MappedByteBuffer mapped-buf
        ^ReentrantLock thread-lock]

       IMemRegion

       (-byte-length [_] size)

       ;; --- atomic i32 via fcntl (FileChannel.lock) ---

       (-load-i32 [_ byte-off]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 4 false)]
             (try
               (.getIntVolatile UNSAFE nil (+ base-addr (long byte-off)))
               (finally (.release fl))))
           (finally (.unlock thread-lock))))

       (-store-i32! [_ byte-off val]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 4 false)]
             (try
               (.putIntVolatile UNSAFE nil (+ base-addr (long byte-off)) (unchecked-int val))
               (.force mapped-buf (int byte-off) 4)
               (finally (.release fl))))
           (finally (.unlock thread-lock)))
         nil)

       (-cas-i32! [_ byte-off expected desired]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 4 false)]
             (try
               (let [addr (+ base-addr (long byte-off))
                     cur  (.getIntVolatile UNSAFE nil addr)]
                 (when (= cur (unchecked-int expected))
                   (.putIntVolatile UNSAFE nil addr (unchecked-int desired))
                   (.force mapped-buf (int byte-off) 4))
                 cur)
               (finally (.release fl))))
           (finally (.unlock thread-lock))))

       (-add-i32! [_ byte-off delta]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 4 false)]
             (try
               (let [addr (+ base-addr (long byte-off))
                     old  (.getIntVolatile UNSAFE nil addr)
                     nv   (unchecked-int (+ old (unchecked-int delta)))]
                 (.putIntVolatile UNSAFE nil addr nv)
                 (.force mapped-buf (int byte-off) 4)
                 old)
               (finally (.release fl))))
           (finally (.unlock thread-lock))))

       (-sub-i32! [_ byte-off delta]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 4 false)]
             (try
               (let [addr (+ base-addr (long byte-off))
                     old  (.getIntVolatile UNSAFE nil addr)
                     nv   (unchecked-int (- old (unchecked-int delta)))]
                 (.putIntVolatile UNSAFE nil addr nv)
                 (.force mapped-buf (int byte-off) 4)
                 old)
               (finally (.release fl))))
           (finally (.unlock thread-lock))))

       (-exchange-i32! [_ byte-off val]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 4 false)]
             (try
               (let [addr (+ base-addr (long byte-off))
                     old  (.getIntVolatile UNSAFE nil addr)]
                 (.putIntVolatile UNSAFE nil addr (unchecked-int val))
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
               (.getLongVolatile UNSAFE nil (+ base-addr (long byte-off)))
               (finally (.release fl))))
           (finally (.unlock thread-lock))))

       (-store-i64! [_ byte-off val]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 8 false)]
             (try
               (.putLongVolatile UNSAFE nil (+ base-addr (long byte-off)) (long val))
               (.force mapped-buf (int byte-off) 8)
               (finally (.release fl))))
           (finally (.unlock thread-lock)))
         nil)

       (-cas-i64! [_ byte-off expected desired]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 8 false)]
             (try
               (let [addr (+ base-addr (long byte-off))
                     cur  (.getLongVolatile UNSAFE nil addr)]
                 (when (= cur (long expected))
                   (.putLongVolatile UNSAFE nil addr (long desired))
                   (.force mapped-buf (int byte-off) 8))
                 cur)
               (finally (.release fl))))
           (finally (.unlock thread-lock))))

       (-add-i64! [_ byte-off delta]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 8 false)]
             (try
               (let [addr (+ base-addr (long byte-off))
                     old  (.getLongVolatile UNSAFE nil addr)
                     nv   (+ old (long delta))]
                 (.putLongVolatile UNSAFE nil addr nv)
                 (.force mapped-buf (int byte-off) 8)
                 old)
               (finally (.release fl))))
           (finally (.unlock thread-lock))))

       (-sub-i64! [_ byte-off delta]
         (.lock thread-lock)
         (try
           (let [fl (.lock lock-channel (long byte-off) 8 false)]
             (try
               (let [addr (+ base-addr (long byte-off))
                     old  (.getLongVolatile UNSAFE nil addr)
                     nv   (- old (long delta))]
                 (.putLongVolatile UNSAFE nil addr nv)
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

       ;; --- byte I/O — direct mmap access (same as JvmMmapRegion) ---

       (-read-bytes [_ byte-off len]
         (let [dst (byte-array len)]
           (MemorySegment/copy seg (long byte-off) (MemorySegment/ofArray dst) 0 (long len))
           dst))

       (-write-bytes! [_ byte-off src]
         (MemorySegment/copy (MemorySegment/ofArray src) 0 seg (long byte-off) (long (alength src)))
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
             ;; Map as MappedByteBuffer for scoped force(index, length).
             ;; Wrap as MemorySegment for Unsafe native address access.
             ^FileChannel map-ch (FileChannel/open path
                                   (into-array OpenOption
                                     [StandardOpenOption/READ
                                      StandardOpenOption/WRITE]))
             ^MappedByteBuffer mbb (.map map-ch FileChannel$MapMode/READ_WRITE 0 size)
             seg   (MemorySegment/ofBuffer mbb)
             ;; Separate FileChannel kept open for fcntl locking —
             ;; MUST NOT be closed until the region is disposed.
             lock-ch (FileChannel/open path
                       (into-array OpenOption
                         [StandardOpenOption/READ
                          StandardOpenOption/WRITE]))]
         (LustreJvmMmapRegion. seg (.address seg) size
                               lock-ch mbb
                               (ReentrantLock.))))))

;; ---------------------------------------------------------------------------
;; Dispatch wrappers — work against IMemRegion on both platforms
;; ---------------------------------------------------------------------------

(defn load-i32       [r byte-off]           (-load-i32       r byte-off))
(defn store-i32!     [r byte-off val]       (-store-i32!     r byte-off val))
(defn cas-i32!       [r byte-off exp des]   (-cas-i32!       r byte-off exp des))
(defn add-i32!       [r byte-off delta]     (-add-i32!       r byte-off delta))
(defn sub-i32!       [r byte-off delta]     (-sub-i32!       r byte-off delta))
(defn exchange-i32!  [r byte-off val]       (-exchange-i32!  r byte-off val))
(defn load-i64       [r byte-off]           (-load-i64       r byte-off))
(defn store-i64!     [r byte-off val]       (-store-i64!     r byte-off val))
(defn cas-i64!       [r byte-off exp des]   (-cas-i64!       r byte-off exp des))
(defn add-i64!       [r byte-off delta]     (-add-i64!       r byte-off delta))
(defn sub-i64!       [r byte-off delta]     (-sub-i64!       r byte-off delta))
(defn wait-i32!      [r byte-off exp t]     (-wait-i32!      r byte-off exp t))
(defn notify-i32!    [r byte-off n]         (-notify-i32!    r byte-off n))
(defn supports-watch? [r]                   (-supports-watch? r))
(defn read-bytes     [r byte-off len]       (-read-bytes     r byte-off len))
(defn write-bytes!   [r byte-off src]       (-write-bytes!   r byte-off src))

#?(:bb
   (defn copy-region!
     "Bulk copy len bytes between two IMemRegion instances (Babashka)."
     [src-region src-byte-off dst-region dst-byte-off len]
     (let [bs (-read-bytes src-region src-byte-off len)]
       (-write-bytes! dst-region dst-byte-off bs)))
   :clj
   (defn copy-region!
     "Bulk copy len bytes between two IMemRegion instances.
      For JvmMmapRegion↔JvmMmapRegion: uses MemorySegment/copy (native memcpy).
      For any JvmHeapRegion involved: falls back to read-bytes/write-bytes."
     [src-region src-byte-off dst-region dst-byte-off len]
     (if (and (instance? JvmMmapRegion src-region)
              (instance? JvmMmapRegion dst-region))
       (let [src-seg (.-seg ^JvmMmapRegion src-region)
             dst-seg (.-seg ^JvmMmapRegion dst-region)]
         (MemorySegment/copy src-seg (long src-byte-off) dst-seg (long dst-byte-off) (long len)))
       (let [bs (-read-bytes src-region src-byte-off len)]
         (-write-bytes! dst-region dst-byte-off bs)))))

;; ---------------------------------------------------------------------------
;; Portable IMemRegion Bitmap Operations — shared CLJ + CLJS
;; ---------------------------------------------------------------------------
;; These work with any IMemRegion (SAB-backed, mmap, JVM mmap).
;; They use only atomic int32 load/CAS which every IMemRegion must implement.
;; CLJS uses these as the JS fallback when WASM is not available.
;; JVM uses these for all slab bitmap management.

(defn imr-bitmap-find-free
  "Scan bitmap for the first free bit (0-bit) starting from start-bit.
   bm-byte-offset is the byte offset of the bitmap within the IMemRegion.
   Returns the block index (absolute bit position), or -1 if bitmap is full."
  [region bm-byte-offset total-bits start-bit]
  (let [word-count (unsigned-bit-shift-right (+ total-bits 31) 5)]
    (loop [word-idx    (unsigned-bit-shift-right start-bit 5)
           bit-in-word (bit-and start-bit 31)]
      (if (>= word-idx word-count)
        -1
        (let [word     (-load-i32 region (+ bm-byte-offset (* word-idx 4)))
              inverted (bit-xor word -1)
              masked   (if (pos? bit-in-word)
                         (bit-and inverted (bit-shift-left -1 bit-in-word))
                         inverted)]
          (if (not (zero? masked))
            (let [bit-pos (loop [b 0]
                            (if (>= b 32) 32
                              (if (not (zero? (bit-and masked (bit-shift-left 1 b))))
                                b
                                (recur (inc b)))))
                  abs-bit (+ (bit-shift-left word-idx 5) bit-pos)]
              (if (< abs-bit total-bits)
                abs-bit
                -1))
            (recur (inc word-idx) 0)))))))

#?(:cljs nil
   :default
   (defn imr-bitmap-find-free-bulk
     "Like imr-bitmap-find-free but reads the bitmap in one bulk -read-bytes call,
      then scans the local byte array. This avoids per-word fcntl lock overhead in
      lustre mode where -load-i32 acquires a byte-range lock per call.
      The snapshot may be slightly stale, but that's safe: the caller retries via
      imr-bitmap-alloc-cas! which uses a proper CAS."
     [region bm-byte-offset total-bits start-bit]
     (let [word-count (int (unsigned-bit-shift-right (+ total-bits 31) 5))
           byte-len   (* word-count 4)
           ^bytes raw (-read-bytes region bm-byte-offset byte-len)
           buf        (-> (java.nio.ByteBuffer/wrap raw)
                          (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
       (loop [word-idx    (int (unsigned-bit-shift-right start-bit 5))
              bit-in-word (int (bit-and start-bit 31))]
         (if (>= word-idx word-count)
           -1
           (let [word     (.getInt buf (* word-idx 4))
                 inverted (bit-xor word -1)
                 masked   (if (pos? bit-in-word)
                            (bit-and inverted (bit-shift-left -1 bit-in-word))
                            inverted)]
             (if (not (zero? masked))
               (let [bit-pos (Integer/numberOfTrailingZeros masked)
                     abs-bit (+ (bit-shift-left word-idx 5) bit-pos)]
                 (if (< abs-bit total-bits)
                   abs-bit
                   -1))
               (recur (inc word-idx) 0))))))))

(defn imr-bitmap-alloc-cas!
  "Atomically set bit-idx in the bitmap from 0→1 (mark block allocated).
   Returns true on success, false if bit was already set (lost CAS race)."
  [region bm-byte-offset bit-idx]
  (let [word-byte-off (+ bm-byte-offset (* (unsigned-bit-shift-right bit-idx 5) 4))
        bit-mask      (bit-shift-left 1 (bit-and bit-idx 31))]
    (loop []
      (let [old-word (-load-i32 region word-byte-off)]
        (if (not (zero? (bit-and old-word bit-mask)))
          false
          (let [new-word (bit-or old-word bit-mask)]
            (if (== old-word (-cas-i32! region word-byte-off old-word new-word))
              true
              (recur))))))))

(defn imr-bitmap-free!
  "Atomically clear bit-idx in the bitmap from 1→0 (mark block free).
   Returns true if bit was set (valid free), false if already clear (double-free)."
  [region bm-byte-offset bit-idx]
  (let [word-byte-off (+ bm-byte-offset (* (unsigned-bit-shift-right bit-idx 5) 4))
        bit-pos       (bit-and bit-idx 31)
        clear-mask    (bit-xor (bit-shift-left 1 bit-pos) -1)
        old-word      (loop []
                        (let [cur     (-load-i32 region word-byte-off)
                              new-val (bit-and cur clear-mask)]
                          (if (== cur (-cas-i32! region word-byte-off cur new-val))
                            cur
                            (recur))))]
    (not (zero? (bit-and (unsigned-bit-shift-right old-word bit-pos) 1)))))

;; ---------------------------------------------------------------------------
;; JVM domain API (CLJ only) — see also Step 11 for slab extension
;; ---------------------------------------------------------------------------

#?(:cljs nil :default
   (do

     ;; EVE binary format — magic prefix bytes written into serialized byte arrays.
     (def ^:private ^:const MAGIC-0 (unchecked-byte 0xEE))
     (def ^:private ^:const MAGIC-1 (unchecked-byte 0xDB))
     ;; Byte-typed write-side constants for value->eve-bytes
     (def ^:private ^:const TAG-FALSE          (unchecked-byte 0x01))
     (def ^:private ^:const TAG-TRUE           (unchecked-byte 0x02))
     (def ^:private ^:const TAG-INT32          (unchecked-byte 0x03))
     (def ^:private ^:const TAG-FLOAT64        (unchecked-byte 0x04))
     (def ^:private ^:const TAG-STRING-SHORT   (unchecked-byte 0x05))
     (def ^:private ^:const TAG-STRING-LONG    (unchecked-byte 0x06))
     (def ^:private ^:const TAG-KEYWORD-SHORT  (unchecked-byte 0x07))
     (def ^:private ^:const TAG-KEYWORD-LONG   (unchecked-byte 0x08))
     (def ^:private ^:const TAG-KW-NS-SHORT    (unchecked-byte 0x09))
     (def ^:private ^:const TAG-KW-NS-LONG     (unchecked-byte 0x0A))
     (def ^:private ^:const TAG-UUID           (unchecked-byte 0x0B))
     (def ^:private ^:const TAG-SYMBOL-SHORT   (unchecked-byte 0x0C))
     (def ^:private ^:const TAG-SYM-NS-SHORT   (unchecked-byte 0x0D))
     (def ^:private ^:const TAG-DATE           (unchecked-byte 0x0E))
     (def ^:private ^:const TAG-INT64          (unchecked-byte 0x0F))
     ;; Flat collection tags — cross-process binary encoding (no SAB pointers)
     (def ^:private ^:const TAG-FLAT-MAP       (unchecked-byte 0xED))
     (def ^:private ^:const TAG-FLAT-SET       (unchecked-byte 0xEE))
     (def ^:private ^:const TAG-FLAT-VEC       (unchecked-byte 0xEF))


     ;; --- OBJ-7: Keyword serialization caches ---
     ;; ConcurrentHashMap caches for keyword↔bytes, mirroring CLJS kw-ser-cache.
     ;; bb: use plain HashMap (single-threaded).
     ;; Evicts when size exceeds 4096 to bound memory.

     (def ^:private kw-encode-cache
       #?(:bb  (java.util.HashMap. 256)
          :clj (java.util.concurrent.ConcurrentHashMap. 256)))

     (def ^:private kw-decode-cache
       #?(:bb  (java.util.HashMap. 256)
          :clj (java.util.concurrent.ConcurrentHashMap. 256)))

     (def ^:private ^:const KW_CACHE_MAX 4096)

     ;; --- EVE binary format — manual LE byte helpers (no ByteBuffer alloc) ---

     (defn- le-bb
       "Wrap a byte[] in a little-endian ByteBuffer.
        Only used for variable-length flat collection encoding where streaming
        writes justify the ByteBuffer overhead."
       ^ByteBuffer [^bytes b]
       (-> (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN)))

     (defn- read-u8 ^long [^bytes b ^long i]
       (bit-and (aget b i) 0xFF))

     (defn- read-u32-le ^long [^bytes b ^long i]
       (bit-or (bit-and (long (aget b i)) 0xFF)
               (bit-shift-left (bit-and (long (aget b (+ i 1))) 0xFF) 8)
               (bit-shift-left (bit-and (long (aget b (+ i 2))) 0xFF) 16)
               (bit-shift-left (bit-and (long (aget b (+ i 3))) 0xFF) 24)))

     (defn- read-i32-le ^long [^bytes b ^long i]
       (let [u (read-u32-le b i)]
         (if (>= u 0x80000000)
           (- u 0x100000000)
           u)))

     (defn- read-i64-le ^long [^bytes b ^long i]
       (bit-or (bit-and (long (aget b i)) 0xFF)
               (bit-shift-left (bit-and (long (aget b (+ i 1))) 0xFF) 8)
               (bit-shift-left (bit-and (long (aget b (+ i 2))) 0xFF) 16)
               (bit-shift-left (bit-and (long (aget b (+ i 3))) 0xFF) 24)
               (bit-shift-left (bit-and (long (aget b (+ i 4))) 0xFF) 32)
               (bit-shift-left (bit-and (long (aget b (+ i 5))) 0xFF) 40)
               (bit-shift-left (bit-and (long (aget b (+ i 6))) 0xFF) 48)
               (bit-shift-left (long (aget b (+ i 7))) 56)))

     (defn- read-f64-le ^double [^bytes b ^long i]
       (Double/longBitsToDouble (read-i64-le b i)))

     ;; --- LE write helpers (eliminates ByteBuffer alloc for fixed-size types) ---

     (defn- put-i32-le! [^bytes b ^long off ^long v]
       (aset b off       (unchecked-byte v))
       (aset b (+ off 1) (unchecked-byte (unsigned-bit-shift-right v 8)))
       (aset b (+ off 2) (unchecked-byte (unsigned-bit-shift-right v 16)))
       (aset b (+ off 3) (unchecked-byte (unsigned-bit-shift-right v 24))))

     (defn- put-i64-le! [^bytes b ^long off ^long v]
       (aset b off       (unchecked-byte v))
       (aset b (+ off 1) (unchecked-byte (unsigned-bit-shift-right v 8)))
       (aset b (+ off 2) (unchecked-byte (unsigned-bit-shift-right v 16)))
       (aset b (+ off 3) (unchecked-byte (unsigned-bit-shift-right v 24)))
       (aset b (+ off 4) (unchecked-byte (unsigned-bit-shift-right v 32)))
       (aset b (+ off 5) (unchecked-byte (unsigned-bit-shift-right v 40)))
       (aset b (+ off 6) (unchecked-byte (unsigned-bit-shift-right v 48)))
       (aset b (+ off 7) (unchecked-byte (unsigned-bit-shift-right v 56))))

     (defn- put-f64-le! [^bytes b ^long off ^double v]
       (put-i64-le! b off (Double/doubleToRawLongBits v)))

     ;; --- Cached constant byte arrays for zero-alloc serialization ---

     (def ^:private ^bytes BYTES-NIL     (byte-array 0))
     (def ^:private ^bytes BYTES-FALSE   (doto (byte-array 3) (aset 0 MAGIC-0) (aset 1 MAGIC-1) (aset 2 TAG-FALSE)))
     (def ^:private ^bytes BYTES-TRUE    (doto (byte-array 3) (aset 0 MAGIC-0) (aset 1 MAGIC-1) (aset 2 TAG-TRUE)))

     ;; Small integer cache [-128, 127] — mirrors java.lang.Integer cache range.
     ;; Each entry is a pre-built 7-byte INT32 array.
     (def ^:private small-int-cache
       (let [^objects arr (object-array 256)]
         (dotimes [i 256]
           (let [n (- i 128)
                 b (byte-array 7)]
             (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-INT32)
             (put-i32-le! b 3 n)
             (aset arr i b)))
         arr))

     (defn- read-utf8 ^String [^bytes b ^long off ^long len]
       (String. b (int off) (int len) "UTF-8"))

     ;; --- EVE binary format — deserializer ---

     (defn eve-bytes->value
       "Decode an EVE-format byte array into a Clojure value.

        1-arity [b]: primitives only.
          Handles: nil (0-byte array), boolean, long (int32/int64), double (float64),
          String, Keyword, Symbol, java.util.Date, java.util.UUID.
          Throws UnsupportedOperationException for SAB pointer tags (0x10–0x13).

        3-arity [b sio coll-factory]: also handles collection types.
          sio          — ISlabIO context (e.g. JvmSlabCtx from alloc/make-jvm-slab-ctx)
          coll-factory — (fn [tag sio slab-offset] → collection)
                          tag 0x10 → EveHashMap, 0x11 → EveHashSet,
                          0x12 → SabVec,        0x13 → SabList"
       ([^bytes b] (eve-bytes->value b nil nil))
       ([^bytes b sio coll-factory]
        (let [len (alength b)]
          (cond
            (zero? len)
            nil

            (< len 3)
            (throw (ex-info "EVE bytes truncated" {:len len}))

            :else
            (let [tag (bit-and (aget b 2) 0xFF)]
              (condp = tag
               0x01 false   ; TAG-FALSE
               0x02 true    ; TAG-TRUE
               0x03 (read-i32-le b 3)   ; TAG-INT32
               0x0F (read-i64-le b 3)   ; TAG-INT64
               0x04 (read-f64-le b 3)   ; TAG-FLOAT64
               0x0E (Date. (long (read-f64-le b 3)))  ; TAG-DATE

               0x05  ; TAG-STRING-SHORT
               (read-utf8 b 4 (read-u8 b 3))

               0x06  ; TAG-STRING-LONG
               (read-utf8 b 7 (read-u32-le b 3))

               0x07  ; TAG-KEYWORD-SHORT
               (keyword (read-utf8 b 4 (read-u8 b 3)))

               0x08  ; TAG-KEYWORD-LONG
               (keyword (read-utf8 b 7 (read-u32-le b 3)))

               0x09  ; TAG-KW-NS-SHORT
               (let [ns-len  (read-u8 b 3)
                     ns-str  (read-utf8 b 4 ns-len)
                     name-off (+ 4 ns-len)
                     name-len (read-u8 b name-off)
                     name-str (read-utf8 b (+ name-off 1) name-len)]
                 (keyword ns-str name-str))

               0x0A  ; TAG-KW-NS-LONG
               (let [ns-len  (read-u32-le b 3)
                     ns-str  (read-utf8 b 7 ns-len)
                     name-off (+ 7 ns-len)
                     name-len (read-u32-le b name-off)
                     name-str (read-utf8 b (+ name-off 4) name-len)]
                 (keyword ns-str name-str))

               0x0C  ; TAG-SYMBOL-SHORT
               (symbol (read-utf8 b 4 (read-u8 b 3)))

               0x0D  ; TAG-SYM-NS-SHORT
               (let [ns-len  (read-u8 b 3)
                     ns-str  (read-utf8 b 4 ns-len)
                     name-off (+ 4 ns-len)
                     name-len (read-u8 b name-off)
                     name-str (read-utf8 b (+ name-off 1) name-len)]
                 (symbol ns-str name-str))

               0x0B  ; TAG-UUID — 16 raw bytes in big-endian order
               (let [msb (Long/reverseBytes (read-i64-le b 3))
                     lsb (Long/reverseBytes (read-i64-le b 11))]
                 (UUID. msb lsb))

               ;; SAB pointer types — collection values in slab memory.
               ;; Prefer registry lookup (mirrors CLJS pattern); fall back to
               ;; legacy coll-factory callback for backward compat.
               0x10 (let [ctor (or (ser/get-jvm-type-constructor 0x10)
                                   (when coll-factory (fn [off] (coll-factory 0x10 sio off))))]
                      (if ctor (ctor (read-i32-le b 3))
                        (throw (UnsupportedOperationException. "EVE SAB_MAP: no constructor registered."))))
               0x11 (let [ctor (or (ser/get-jvm-type-constructor 0x11)
                                   (when coll-factory (fn [off] (coll-factory 0x11 sio off))))]
                      (if ctor (ctor (read-i32-le b 3))
                        (throw (UnsupportedOperationException. "EVE SAB_SET: no constructor registered."))))
               0x12 (let [ctor (or (ser/get-jvm-type-constructor 0x12)
                                   (when coll-factory (fn [off] (coll-factory 0x12 sio off))))]
                      (if ctor (ctor (read-i32-le b 3))
                        (throw (UnsupportedOperationException. "EVE SAB_VEC: no constructor registered."))))
               0x13 (let [ctor (or (ser/get-jvm-type-constructor 0x13)
                                   (when coll-factory (fn [off] (coll-factory 0x13 sio off))))]
                      (if ctor (ctor (read-i32-le b 3))
                        (throw (UnsupportedOperationException. "EVE SAB_LIST: no constructor registered."))))
               0x1D (let [ctor (or (ser/get-jvm-type-constructor 0x1D)
                                   (when coll-factory (fn [off] (coll-factory 0x1D sio off))))]
                      (if ctor (ctor (read-i32-le b 3))
                        (throw (UnsupportedOperationException. "EVE ARRAY: no constructor registered."))))
               0x1E (let [ctor (or (ser/get-jvm-type-constructor 0x1E)
                                   (when coll-factory (fn [off] (coll-factory 0x1E sio off))))]
                      (if ctor (ctor (read-i32-le b 3))
                        (throw (UnsupportedOperationException. "EVE OBJ: no constructor registered."))))

               ;; Flat map — cross-process binary map encoding
               0xED
               (let [cnt (read-i32-le b 3)]
                 (loop [pos 7 i 0 m (transient {})]
                   (if (>= i cnt)
                     (persistent! m)
                     (let [klen (read-i32-le b pos)
                           k    (eve-bytes->value (java.util.Arrays/copyOfRange b (int (+ pos 4)) (int (+ pos 4 klen))))
                           voff (+ pos 4 klen)
                           vlen (read-i32-le b voff)
                           v    (eve-bytes->value (java.util.Arrays/copyOfRange b (int (+ voff 4)) (int (+ voff 4 vlen))))]
                       (recur (+ voff 4 vlen) (inc i) (assoc! m k v))))))

               ;; Flat set — cross-process binary set encoding
               0xEE
               (let [cnt (read-i32-le b 3)]
                 (loop [pos 7 i 0 s (transient #{})]
                   (if (>= i cnt)
                     (persistent! s)
                     (let [elen (read-i32-le b pos)
                           elem (eve-bytes->value (java.util.Arrays/copyOfRange b (int (+ pos 4)) (int (+ pos 4 elen))))]
                       (recur (+ pos 4 elen) (inc i) (conj! s elem))))))

               ;; Flat vec — cross-process binary vector encoding
               0xEF
               (let [cnt (read-i32-le b 3)]
                 (loop [pos 7 i 0 v (transient [])]
                   (if (>= i cnt)
                     (persistent! v)
                     (let [elen (read-i32-le b pos)
                           elem (eve-bytes->value (java.util.Arrays/copyOfRange b (int (+ pos 4)) (int (+ pos 4 elen))))]
                       (recur (+ pos 4 elen) (inc i) (conj! v elem))))))

               (throw (ex-info "Unknown EVE type tag" {:tag tag}))))))))

     ;; --- EVE binary format — serializer (primitive types only) ---

     (defn- bytes-header-tag
       "Create a 3-byte prefix [0xEE 0xDB tag]. Only used for variable-size types
        that need a fresh array. Fixed-size types use cached constants or put-* helpers."
       ^bytes [tag]
       (doto (byte-array 3) (aset 0 MAGIC-0) (aset 1 MAGIC-1) (aset 2 (unchecked-byte tag))))

     (declare value->eve-bytes)

     (defn- flat-set->eve-bytes
       "Encode a Clojure set as a FLAT_SET byte[].
        Format: [0xEE][0xDB][0xEE][count:i32LE]([e-len:i32LE][e-bytes])*"
       ^bytes [s]
       (let [items   (seq s)
             cnt     (count s)
             encoded (mapv value->eve-bytes items)
             body    (reduce + (map #(+ 4 (alength ^bytes %)) encoded))
             b       (byte-array (+ 7 body))
             bb      (doto (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN))]
         (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-FLAT-SET)
         (.position bb 3)
         (.putInt bb (int cnt))
         (doseq [^bytes eb encoded]
           (.putInt bb (int (alength eb)))
           (.put bb eb))
         b))

     (defn- flat-vec->eve-bytes
       "Encode a Clojure sequential as a FLAT_VEC byte[].
        Format: [0xEE][0xDB][0xEF][count:i32LE]([e-len:i32LE][e-bytes])*"
       ^bytes [coll]
       (let [items   (seq coll)
             count   (count coll)
             encoded (mapv value->eve-bytes items)
             body    (reduce + (map #(+ 4 (alength ^bytes %)) encoded))
             b       (byte-array (+ 7 body))
             bb      (doto (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN))]
         (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-FLAT-VEC)
         (.position bb 3)
         (.putInt bb (int count))
         (doseq [^bytes eb encoded]
           (.putInt bb (int (alength eb)))
           (.put bb eb))
         b))

     (defn- flat-map->eve-bytes
       "Encode a Clojure map as a FLAT_MAP byte[].
        Format: [0xEE][0xDB][0xED][count:i32LE]([k-len:i32LE][k-bytes][v-len:i32LE][v-bytes])*"
       ^bytes [m]
       (let [count   (count m)
             encoded (mapv (fn [[k v]] [(value->eve-bytes k) (value->eve-bytes v)]) m)
             body    (reduce (fn [acc [kb vb]] (+ acc 4 (alength ^bytes kb) 4 (alength ^bytes vb)))
                             0 encoded)
             b       (byte-array (+ 7 body))
             bb      (doto (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN))]
         (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-FLAT-MAP)
         (.position bb 3)
         (.putInt bb (int count))
         (doseq [[^bytes kb ^bytes vb] encoded]
           (.putInt bb (int (alength kb)))
           (.put bb kb)
           (.putInt bb (int (alength vb)))
           (.put bb vb))
         b))

     (defn value->eve-bytes
       "Encode a Clojure value to EVE binary format byte[].

        Supports: nil (→ 0 bytes), Boolean, Long/Integer/Short/Byte (→ INT32 or INT64),
        Double/Float (→ FLOAT64), String, Keyword, Symbol, java.util.Date, java.util.UUID.

        Maps are flat-encoded as FLAT_MAP (tag 0xED); sequential collections and sets
        are flat-encoded as FLAT_VEC (tag 0xEF).  Use value+sio->eve-bytes when HAMT
        allocation in a slab context is required.

        Fixed-size types use manual LE byte writes (no ByteBuffer allocation).
        nil, booleans, and small integers [-128,127] return cached arrays (zero alloc)."
       ^bytes [v]
       (cond
         (nil? v)
         BYTES-NIL

         (instance? Boolean v)
         (if v BYTES-TRUE BYTES-FALSE)

         (or (instance? Long v)
             (instance? Integer v)
             (instance? Short v)
             (instance? Byte v))
         (let [n (long v)]
           (if (and (>= n Integer/MIN_VALUE) (<= n Integer/MAX_VALUE))
             ;; INT32: check small-int cache first
             (if (and (>= n -128) (<= n 127))
               (aget ^objects small-int-cache (+ (int n) 128))
               (let [b (byte-array 7)]
                 (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-INT32)
                 (put-i32-le! b 3 n)
                 b))
             ;; INT64: [0xEE][0xDB][0x0F][i64LE:8]
             (let [b (byte-array 11)]
               (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-INT64)
               (put-i64-le! b 3 n)
               b)))

         (or (instance? Double v) (instance? Float v))
         ;; FLOAT64: [0xEE][0xDB][0x04][f64LE:8]
         (let [b (byte-array 11)]
           (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-FLOAT64)
           (put-f64-le! b 3 (double v))
           b)

         (instance? Date v)
         ;; DATE: [0xEE][0xDB][0x0E][f64LE:8]  (milliseconds since epoch)
         (let [b (byte-array 11)]
           (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-DATE)
           (put-f64-le! b 3 (double (.getTime ^Date v)))
           b)

         (instance? String v)
         (let [^bytes utf8 (.getBytes ^String v "UTF-8")
               slen (alength utf8)]
           (if (<= slen 255)
             ;; STRING_SHORT: [0xEE][0xDB][0x05][len:u8][utf8]
             (let [b (byte-array (+ 4 slen))]
               (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-STRING-SHORT)
               (aset b 3 (unchecked-byte slen))
               (System/arraycopy utf8 0 b 4 slen) b)
             ;; STRING_LONG: [0xEE][0xDB][0x06][len:u32LE][utf8]
             (let [b (byte-array (+ 7 slen))]
               (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-STRING-LONG)
               (put-i32-le! b 3 (long slen))
               (System/arraycopy utf8 0 b 7 slen) b)))

         (keyword? v)
         (or (.get kw-encode-cache v)
             (let [ns-str  (namespace v)
                   nm-str  (name v)
                   result
                   (if (nil? ns-str)
                     (let [^bytes utf8 (.getBytes ^String nm-str "UTF-8")
                           slen (alength utf8)]
                       (if (<= slen 255)
                         (let [b (byte-array (+ 4 slen))]
                           (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-KEYWORD-SHORT)
                           (aset b 3 (unchecked-byte slen))
                           (System/arraycopy utf8 0 b 4 slen) b)
                         (let [b (byte-array (+ 7 slen))]
                           (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-KEYWORD-LONG)
                           (put-i32-le! b 3 (long slen))
                           (System/arraycopy utf8 0 b 7 slen) b)))
                     (let [^bytes ns-utf8 (.getBytes ^String ns-str "UTF-8")
                           ^bytes nm-utf8 (.getBytes ^String nm-str "UTF-8")
                           ns-len (alength ns-utf8)
                           nm-len (alength nm-utf8)]
                       (if (and (<= ns-len 255) (<= nm-len 255))
                         (let [b (byte-array (+ 5 ns-len nm-len))]
                           (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-KW-NS-SHORT)
                           (aset b 3 (unchecked-byte ns-len))
                           (System/arraycopy ns-utf8 0 b 4 ns-len)
                           (aset b (+ 4 ns-len) (unchecked-byte nm-len))
                           (System/arraycopy nm-utf8 0 b (+ 5 ns-len) nm-len) b)
                         (let [b (byte-array (+ 11 ns-len nm-len))]
                           (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-KW-NS-LONG)
                           (put-i32-le! b 3 (long ns-len))
                           (System/arraycopy ns-utf8 0 b 7 ns-len)
                           (put-i32-le! b (+ 7 ns-len) (long nm-len))
                           (System/arraycopy nm-utf8 0 b (+ 11 ns-len) nm-len) b))))]
               (when (> (.size kw-encode-cache) KW_CACHE_MAX) (.clear kw-encode-cache))
               (.put kw-encode-cache v result)
               result))

         (symbol? v)
         (let [ns-str (namespace v)
               nm-str (name v)]
           (if (nil? ns-str)
             ;; Simple symbol
             (let [^bytes utf8 (.getBytes ^String nm-str "UTF-8")
                   slen (alength utf8)
                   b    (byte-array (+ 4 slen))]
               (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-SYMBOL-SHORT)
               (aset b 3 (unchecked-byte slen))
               (System/arraycopy utf8 0 b 4 slen) b)
             ;; Namespaced symbol
             (let [^bytes ns-utf8 (.getBytes ^String ns-str "UTF-8")
                   ^bytes nm-utf8 (.getBytes ^String nm-str "UTF-8")
                   ns-len (alength ns-utf8)
                   nm-len (alength nm-utf8)
                   b (byte-array (+ 5 ns-len nm-len))]
               (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-SYM-NS-SHORT)
               (aset b 3 (unchecked-byte ns-len))
               (System/arraycopy ns-utf8 0 b 4 ns-len)
               (aset b (+ 4 ns-len) (unchecked-byte nm-len))
               (System/arraycopy nm-utf8 0 b (+ 5 ns-len) nm-len) b)))

         (instance? UUID v)
         ;; UUID: [0xEE][0xDB][0x0B][msb:8 bytes BE][lsb:8 bytes BE]
         (let [^UUID u v
               b (byte-array 19)
               msb (.getMostSignificantBits u)
               lsb (.getLeastSignificantBits u)]
           (aset b 0 MAGIC-0) (aset b 1 MAGIC-1) (aset b 2 TAG-UUID)
           ;; msb and lsb are big-endian (network order)
           (put-i64-le! b 3 (Long/reverseBytes msb))
           (put-i64-le! b 11 (Long/reverseBytes lsb))
           b)

         (map? v)
         (flat-map->eve-bytes v)

         (set? v)
         (flat-set->eve-bytes v)

         (or (vector? v) (sequential? v) (seq? v))
         (flat-vec->eve-bytes v)

         :else
         (throw (ex-info "value->eve-bytes: unsupported type" {:type (type v) :value v}))))

     ;; --- JVM slab collection serialization ---

     (defn jvm-sab-pointer-bytes
       "Build the 7-byte EVE SAB pointer: [0xEE 0xDB tag off0 off1 off2 off3]."
       ^bytes [tag slab-off]
       (let [b (byte-array 7)]
         (aset b 0 (unchecked-byte 0xEE))
         (aset b 1 (unchecked-byte 0xDB))
         (aset b 2 (unchecked-byte tag))
         (aset b 3 (unchecked-byte (bit-and slab-off 0xFF)))
         (aset b 4 (unchecked-byte (bit-and (unsigned-bit-shift-right slab-off 8) 0xFF)))
         (aset b 5 (unchecked-byte (bit-and (unsigned-bit-shift-right slab-off 16) 0xFF)))
         (aset b 6 (unchecked-byte (bit-and (unsigned-bit-shift-right slab-off 24) 0xFF)))
         b))

     ;; Late-bound collection writers registered by map/vec/set/list namespaces at
     ;; load time. Using a defonce atom avoids circular compile-time dependencies.
     (declare value+sio->eve-bytes)
     (defonce ^:private jvm-coll-writers (clojure.core/atom {}))

     (defn register-jvm-collection-writer!
       "Register a JVM slab writer for a collection type.
        tag    — one of :map, :set, :vec, :list
        writer — (fn [sio serialize-elem coll] → slab-off)
        Called from collection namespaces after their jvm-write-*! fns are defined."
       [tag writer]
       (swap! jvm-coll-writers assoc tag writer))

     (defn jvm-write-collection!
       "Serialize a Clojure collection to slab via the registered writer.
        Returns the slab-qualified header offset.
        tag — :map, :set, :vec, or :list."
       [tag sio coll]
       (let [writer (get @jvm-coll-writers tag)]
         (if writer
           (writer sio (partial value+sio->eve-bytes sio) coll)
           (throw (ex-info (str "jvm-write-collection!: no writer for " tag) {:tag tag})))))

     (defn value+sio->eve-bytes
       "Serialize v to EVE bytes, allocating collection structures into sio.
        Maps → SAB_MAP pointer (0x10), sets → SAB_SET (0x11),
        vectors → SAB_VEC (0x12), lists → SAB_LIST (0x13).
        Primitives are encoded inline via value->eve-bytes.
        Collection writers must be registered via register-jvm-collection-writer!
        before calling this function with collection values.
        1-arity: uses *jvm-slab-ctx*.  2-arity: explicit sio."
       (^bytes [v]
        (value+sio->eve-bytes @(resolve 'eve.deftype-proto.alloc/*jvm-slab-ctx*) v))
       (^bytes [sio v]
       (let [writers @jvm-coll-writers]
         (cond
           (or (nil? v) (boolean? v) (integer? v) (float? v) (string? v)
               (keyword? v) (symbol? v)
               (instance? java.util.UUID v) (instance? java.util.Date v))
           (value->eve-bytes v)

           ;; Already slab-backed Eve type — return pointer to existing header
           (satisfies? d/IEveRoot v)
           (let [off (d/-root-header-off v)
                 tag (cond
                       (map? v)    0x10
                       (set? v)    0x11
                       (vector? v) 0x12
                       :else       0x13)]
             (jvm-sab-pointer-bytes tag off))

           (map? v)
           (if-let [write-map! (get writers :map)]
             (jvm-sab-pointer-bytes 0x10 (write-map! sio (partial value+sio->eve-bytes sio) v))
             (throw (ex-info "value+sio->eve-bytes: :map writer not registered" {:value v})))

           (set? v)
           (if-let [write-set! (get writers :set)]
             (jvm-sab-pointer-bytes 0x11 (write-set! sio (partial value+sio->eve-bytes sio) v))
             (throw (ex-info "value+sio->eve-bytes: :set writer not registered" {:value v})))

           (list? v)
           (if-let [write-list! (get writers :list)]
             (jvm-sab-pointer-bytes 0x13 (write-list! sio (partial value+sio->eve-bytes sio) v))
             (throw (ex-info "value+sio->eve-bytes: :list writer not registered" {:value v})))

           (or (vector? v) (sequential? v))
           (if-let [write-vec! (get writers :vec)]
             (jvm-sab-pointer-bytes 0x12 (write-vec! sio (partial value+sio->eve-bytes sio) v))
             (throw (ex-info "value+sio->eve-bytes: :vec writer not registered" {:value v})))

           (satisfies? d/IBackingArray v)
           (if-let [write-arr! (get writers :array)]
             (jvm-sab-pointer-bytes 0x1D (write-arr! sio nil (d/-backing-array v)))
             (throw (ex-info "value+sio->eve-bytes: :array writer not registered" {:value v})))

           (.isArray (class v))
           (if-let [write-arr! (get writers :array)]
             (jvm-sab-pointer-bytes 0x1D (write-arr! sio nil v))
             (throw (ex-info "value+sio->eve-bytes: :array writer not registered" {:value v})))

           :else
           (throw (ex-info "value+sio->eve-bytes: unsupported type"
                           {:type (type v) :value v}))))))))

