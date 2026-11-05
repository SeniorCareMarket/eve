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

   Implementations are the host's, in eve.region (eve/adapters/region/,
   adapters/README.md): a SharedArrayBuffer and an mmap'd file on JavaScript,
   a ByteBuffer with VarHandle atomics on the JVM, and each other host's own.

   All byte-off arguments are BYTE offsets. Atomic ops require 4-byte
   alignment (enforced by the caller — util.cljs already does this via
   the (* field-idx 4) convention)."
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
                     const-objects extend-default]]
   [eve.deftype-proto.data :as d]
   [eve.encoding :as enc]
   [eve.platform :as p]))

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
     JvmByteBufferRegion returns true (LockSupport.park/unpark).
     File-backed NodeMmapRegion returns false.")

  ;; --- non-atomic bulk byte I/O (data region reads / writes) ---
  (-read-bytes [r byte-off len]
    "Copy len bytes starting at byte-off into a new byte array.
     Non-atomic — callers are responsible for any needed synchronisation.")

  (-write-bytes! [r byte-off src]
    "Copy all bytes from src into the region at byte-off.
     Non-atomic — callers are responsible for any needed synchronisation."))

(defprotocol ISyncableRegion
  "A region that can force its written bytes to durable storage — a
   file-backed mapping whose host offers force/msync. Regions that cannot
   (heap, SAB, another host's) simply do not implement it; sync-region!
   no-ops for them."
  (-sync! [r]
    "Force the region's whole mapped range to durable storage.")
  (-sync-range! [r byte-off len]
    "Force the range [byte-off, byte-off+len) of the region to durable
     storage."))

(defn sync-region!
  "Force r (or a range of it) to durable storage when r's host supports it
   (ISyncableRegion); a no-op otherwise — in-process regions, and hosts
   whose sync port has not landed, answer nil."
  ([r]
   (when (and r (satisfies? ISyncableRegion r))
     (-sync! r)))
  ([r byte-off len]
   (when (and r (satisfies? ISyncableRegion r))
     (-sync-range! r byte-off len))))

(defprotocol IHostAtomics
  "What a host's region can promise about its read-modify-write ops."
  (-atomic-rmw? [r]
    "true when -cas-i32!/-add-i32!/-sub-i32!/-exchange-i32! are atomic against
     every other process mapping the same bytes. A region that answers false
     is a lock-only region: its RMW ops are plain, and correct only while its
     process holds the exclusive hold (eve.atom, ROOT_HOLD_OFFSET).")
  (-with-file-lock [r byte-off f]
    "Run f while this process holds an exclusive cross-process lock on the
     4 bytes at byte-off; returns f's result. A region with atomic RMW runs
     f directly — the lock exists for lock-only regions."))

;; A region's bytes as this process's own array — the hot read path's plain loads (jvm-heap-grade step 3,
;; 2026-11-05). A published node's bytes never change (they are written before its pointer is published and
;; read after the reader acquired that pointer), so a lookup may read them with plain array loads instead of
;; the region's atomic ops: no dispatch per read, no allocation per byte. Only a region whose bytes live in
;; this process's heap, at array offset 0, answers one (the JVM's heap ByteBuffer); every other region —
;; a mapped file another process writes, a SharedArrayBuffer, another host's — answers nil, and its
;; readers keep the region's own ops.
(defprotocol IHeapBytes
  (-heap-bytes [r]
    "r's backing byte array (byte i of the array is r's byte i), or nil."))

(extend-default IHeapBytes
  (-heap-bytes [_] nil))

;; ---------------------------------------------------------------------------
;; The regions themselves are the host's: eve.region, an adapter
;; (eve/adapters/region/<implementation>, adapters/README.md), opens them —
;; make-heap-region, open-mmap-region — and says lustre-region?.
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Region introspection — portable
;; ---------------------------------------------------------------------------

(defn copy-region!
  "Bulk copy len bytes between two IMemRegion instances through a
   temporary array (a native-memcpy fast path can be added later)."
  [src-region src-byte-off dst-region dst-byte-off len]
  (let [bs (-read-bytes src-region (long src-byte-off) (long len))]
    (-write-bytes! dst-region (long dst-byte-off) bs)))

;; ---------------------------------------------------------------------------
;; Portable IMemRegion Bitmap Operations — shared CLJ + CLJS
;; ---------------------------------------------------------------------------
;; These work with any IMemRegion (SAB-backed, mmap, JVM mmap).
;; They use only atomic int32 load/CAS which every IMemRegion must implement.
;; CLJS uses these as the JS fallback when WASM is not available.
;; JVM uses these for all slab bitmap management.

(defn- scan-free
  "The first 0 bit at or after start-bit among total-bits, word i of the
   bitmap being (word i); -1 when every bit is set."
  [word total-bits start-bit]
  (let [word-count (unsigned-bit-shift-right (+ total-bits 31) 5)]
    (loop [word-idx    (unsigned-bit-shift-right start-bit 5)
           bit-in-word (bit-and start-bit 31)]
      (if (>= word-idx word-count)
        -1
        (let [inverted (bit-xor (word word-idx) -1)
              masked   (if (pos? bit-in-word)
                         (bit-and inverted (bit-shift-left -1 bit-in-word))
                         inverted)]
          (if (not (zero? masked))
            ;; masked has no bit below bit-in-word, so the search starts
            ;; there: one step for a cursor-led allocation, not up to 32
            (let [bit-pos (loop [b bit-in-word]
                            (if (>= b 32) 32
                              (if (not (zero? (bit-and masked (bit-shift-left 1 b))))
                                b
                                (recur (inc b)))))
                  abs-bit (+ (bit-shift-left word-idx 5) bit-pos)]
              (if (< abs-bit total-bits)
                abs-bit
                -1))
            (recur (inc word-idx) 0)))))))

(defn imr-bitmap-find-free
  "Scan bitmap for the first free bit (0-bit) starting from start-bit.
   bm-byte-offset is the byte offset of the bitmap within the IMemRegion.
   Returns the block index (absolute bit position), or -1 if bitmap is full."
  [region bm-byte-offset total-bits start-bit]
  (scan-free (fn [i] (-load-i32 region (+ bm-byte-offset (* i 4)))) total-bits start-bit))

(defn imr-bitmap-find-free-bulk
  "Like imr-bitmap-find-free but reads the bitmap in one bulk -read-bytes call,
   then scans the local byte array. This avoids per-word fcntl lock overhead in
   lustre mode where -load-i32 acquires a byte-range lock per call.
   The snapshot may be slightly stale, but that's safe: the caller retries via
   imr-bitmap-alloc-cas! which uses a proper CAS."
  [region bm-byte-offset total-bits start-bit]
  (let [^bytes raw (-read-bytes region bm-byte-offset (* 4 (unsigned-bit-shift-right (+ total-bits 31) 5)))
        u8 (fn [o] (bit-and (aget raw o) 0xFF))]
    (scan-free (fn [i] (let [o (* i 4)]
                         (unchecked-int (bit-or (u8 o) (bit-shift-left (u8 (+ o 1)) 8)
                                                (bit-shift-left (u8 (+ o 2)) 16)
                                                (bit-shift-left (u8 (+ o 3)) 24)))))
               total-bits start-bit)))

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
