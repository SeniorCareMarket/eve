(ns eve.region
  "eve's regions (eve.mem's IMemRegion and IHostAtomics; HOST-CONTRACT §1-2)
   over java.nio without VarHandle (babashka): a table of MappedByteBuffer
   segment windows whose read-modify-write ops are plain, made exclusive
   across processes by a FileChannel byte-range lock (IHostAtomics: a
   lock-only region).

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
                     -read-bytes -write-bytes! -atomic-rmw? -with-file-lock]])
  (:import
   [java.io RandomAccessFile]
   [java.nio ByteBuffer ByteOrder MappedByteBuffer]
   [java.nio.channels FileChannel FileChannel$MapMode]
   [java.nio.file OpenOption Paths StandardOpenOption]
   [java.util Date UUID]))

;; Every region has atomic RMW unless it says otherwise.
(extend-protocol mem/IHostAtomics
  Object
  (-atomic-rmw? [_] true)
  (-with-file-lock [_ _byte-off f] (f)))

   ;; Babashka implementations — MappedByteBuffer, no atomic read-modify-write
   ;; bb takes `:bb` before `:clj` in reader conditionals. Everything above the
   ;; region is the JVM code; this is the residue. bb has no
   ;; java.lang.invoke.MethodHandles (measured 2026-10-15: the class does not
   ;; resolve; sun.misc.Unsafe has no reachable methods; java.lang.foreign's
   ;; MemorySegment/ofBuffer does not resolve), so no VarHandle and no hardware
   ;; CAS on a mapped buffer. A BbMmapRegion's loads and stores are the plain
   ;; MappedByteBuffer ones, every store followed by a full fence; its
   ;; read-modify-write ops are plain, and correct only under the exclusive hold
   ;; (ROOT_HOLD/ROOT_INFLIGHT, data.cljc) that eve.atom takes around a bb swap —
   ;; -atomic-rmw? answers false so eve.atom knows to.

;; A full memory fence: a lock-prefixed read-modify-write on a private
;; word orders every earlier store before every later load (x86: the
;; store buffer drains; aarch64: ldaddal is acquire+release).
(def ^:private fence-word (java.util.concurrent.atomic.AtomicInteger. 0))
(defn- fence! []
  (.incrementAndGet ^java.util.concurrent.atomic.AtomicInteger fence-word)
  nil)

;; BbMmapRegion — file-backed MappedByteBuffer. chan is the file's open
;; channel when the region was opened :lockable? (the .root region of a
;; domain): it carries the byte-range locks of -with-file-lock, and POSIX
;; record locks die with any close of the file by this process, so it is
;; never closed. in-process serialises this process's own threads on the
;; lock (a second lock of the same range by this process throws
;; OverlappingFileLockException — measured).
;;
;; A babashka may refuse every method of the lock itself (sun.nio.ch.FileLockImpl:
;; "Method close ... not allowed!", seen on a Mac's bb, 2026-10-22; this tree's
;; 1.13.226 closes it). There each lock is taken on a channel of its own, opened
;; for it and closed to release it (path): checked once per process, by locking
;; a scratch file and closing the lock. EVE_BB_FILE_LOCK=channel forces that
;; route (its test).
(def ^:private lock-close-ok?
  (delay
    (and (not= "channel" (System/getenv "EVE_BB_FILE_LOCK"))
         (let [f (java.io.File/createTempFile "eve-lock" ".probe")
               ^FileChannel fc (FileChannel/open (.toPath f)
                                                 (into-array OpenOption [StandardOpenOption/READ
                                                                         StandardOpenOption/WRITE]))]
           (try
             (let [fl (.lock fc 0 1 false)]
               (try (.close fl) true (catch Exception _ false)))
             (finally (.close fc) (.delete f)))))))

;; ------------------------------------------------------------------
;; Segmentation — as in the varhandle adapter (design of record:
;; docs/eve-region-segmentation/REPORT.md): a file-backed region is a
;; table of MappedByteBuffer windows of a power-of-two size
;; (:segment-bytes open-time parameter, default 256 MiB, stored
;; nowhere in the files), so no slab file is walled at 2^31 bytes by
;; FileChannel.map's single-mapping limit.  Every op decomposes its
;; absolute offset inside the region.  ByteBuffer's absolute
;; getInt/getLong tolerate unaligned offsets within one window; an
;; access that straddles a window boundary composes byte by byte.
;; ------------------------------------------------------------------

(def DEFAULT-SEGMENT-BYTES
  "The default segment window of a file-backed region: 256 MiB."
  (bit-shift-left 1 28))

(defn- segment-shift
  "Validate a :segment-bytes value — a power of two, 4096..2^30 (a
   window is one FileChannel.map, so a window may not exceed what one
   mapping can hold) — and return its log2."
  [seg-bytes]
  (let [sb (long seg-bytes)]
    (when-not (and (<= 4096 sb)
                   (<= sb (bit-shift-left 1 30))
                   (zero? (bit-and sb (dec sb))))
      (throw (ex-info (str "eve.region: :segment-bytes must be a power of two "
                           "between 4096 and 2^30, got " sb)
                      {:segment-bytes sb})))
    (loop [shift 0 v sb]
      (if (== v 1)
        shift
        (recur (inc shift) (bit-shift-right v 1))))))

(defn- seg-buf
  "The segment buffer covering absolute offset off."
  ^MappedByteBuffer [^objects segments ^long shift ^long off]
  (aget segments (int (unsigned-bit-shift-right off shift))))

(defn- seg-intra
  "The offset of absolute offset off within its segment."
  ^long [^long shift ^long off]
  (bit-and off (dec (bit-shift-left 1 shift))))

(defn- seg-load-i32
  [^objects segments ^long shift ^long off]
  (let [intra (seg-intra shift off)]
    (if (<= (+ intra 4) (bit-shift-left 1 shift))
      (.getInt (seg-buf segments shift off) (int intra))
      (unchecked-int
       (bit-or (bit-and (.get (seg-buf segments shift off) (int (seg-intra shift off))) 0xFF)
               (bit-shift-left (bit-and (.get (seg-buf segments shift (+ off 1)) (int (seg-intra shift (+ off 1)))) 0xFF) 8)
               (bit-shift-left (bit-and (.get (seg-buf segments shift (+ off 2)) (int (seg-intra shift (+ off 2)))) 0xFF) 16)
               (bit-shift-left (bit-and (.get (seg-buf segments shift (+ off 3)) (int (seg-intra shift (+ off 3)))) 0xFF) 24))))))

(defn- seg-store-i32!
  [^objects segments ^long shift ^long off ^long v]
  (let [intra (seg-intra shift off)]
    (if (<= (+ intra 4) (bit-shift-left 1 shift))
      (.putInt (seg-buf segments shift off) (int intra) (unchecked-int v))
      (do (.put (seg-buf segments shift off) (int (seg-intra shift off)) (unchecked-byte v))
          (.put (seg-buf segments shift (+ off 1)) (int (seg-intra shift (+ off 1))) (unchecked-byte (unsigned-bit-shift-right v 8)))
          (.put (seg-buf segments shift (+ off 2)) (int (seg-intra shift (+ off 2))) (unchecked-byte (unsigned-bit-shift-right v 16)))
          (.put (seg-buf segments shift (+ off 3)) (int (seg-intra shift (+ off 3))) (unchecked-byte (unsigned-bit-shift-right v 24)))))))

(defn- seg-load-i64
  [^objects segments ^long shift ^long off]
  (let [intra (seg-intra shift off)]
    (if (<= (+ intra 8) (bit-shift-left 1 shift))
      (.getLong (seg-buf segments shift off) (int intra))
      (let [lo (seg-load-i32 segments shift off)
            hi (seg-load-i32 segments shift (+ off 4))]
        (bit-or (bit-shift-left (long hi) 32)
                (bit-and (long lo) 0xFFFFFFFF))))))

(defn- seg-store-i64!
  [^objects segments ^long shift ^long off ^long v]
  (let [intra (seg-intra shift off)]
    (if (<= (+ intra 8) (bit-shift-left 1 shift))
      (.putLong (seg-buf segments shift off) (int intra) (long v))
      (do (seg-store-i32! segments shift off (unchecked-int v))
          (seg-store-i32! segments shift (+ off 4)
                          (unchecked-int (unsigned-bit-shift-right v 32)))))))

(defn- seg-read-bytes
  [^objects segments ^long shift ^long off ^long len]
  (let [dst      (byte-array (int len))
        seg-size (bit-shift-left 1 shift)]
    (loop [done 0]
      (when (< done len)
        (let [abs   (+ off done)
              intra (seg-intra shift abs)
              n     (int (min (- seg-size intra) (- len done)))]
          (.get (seg-buf segments shift abs) (int intra) dst (int done) n)
          (recur (+ done n)))))
    dst))

(defn- seg-write-bytes!
  [^objects segments ^long shift ^long off ^bytes src]
  (let [len      (alength src)
        seg-size (bit-shift-left 1 shift)]
    (loop [done 0]
      (when (< done len)
        (let [abs   (+ off done)
              intra (seg-intra shift abs)
              n     (int (min (- seg-size intra) (- len done)))]
          (.put (seg-buf segments shift abs) (int intra) src (int done) n)
          (recur (+ done n))))))
  nil)

(defn- map-segments
  "Map fc's file over [0, size) in windows of 2^shift bytes, each
   LITTLE_ENDIAN.  When old-segments (a previous region's table for
   the same file, opened at the same shift) has a window whose length
   is unchanged, that window's buffer is reused — a growth or refresh
   re-map maps only the delta."
  ^objects [^FileChannel fc size shift ^objects old-segments old-shift]
  (let [size     (long size)
        shift    (long shift)
        seg-size (bit-shift-left 1 shift)
        n        (int (max 1 (quot (+ size (dec seg-size)) seg-size)))
        arr      (object-array n)]
    (dotimes [k n]
      (let [start (long (* (long k) seg-size))
            len   (min seg-size (- size start))
            old   (when (and old-segments
                             (== shift old-shift)
                             (< k (alength old-segments)))
                    (aget old-segments k))]
        (aset arr k
              (if (and old (== (long (.capacity ^MappedByteBuffer old)) len))
                old
                (doto (.map fc FileChannel$MapMode/READ_WRITE start len)
                  (.order ByteOrder/LITTLE_ENDIAN))))))
    arr))

(deftype BbMmapRegion [^objects segments ^long size ^int shift
                       ^FileChannel chan
                       path
                       ^Object in-process]
  mem/IMemRegion
  (-byte-length [_] size)

  (-load-i32 [_ byte-off]
    ;; Inlined common path (see the varhandle adapter's -load-i32):
    ;; method -> .getInt directly, helper only for a straddle.
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 4) (bit-shift-left 1 shift))
        (let [^MappedByteBuffer buf (aget segments
                                          (int (unsigned-bit-shift-right off shift)))]
          (.getInt buf (int intra)))
        (seg-load-i32 segments shift off))))

  (-store-i32! [_ byte-off val]
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 4) (bit-shift-left 1 shift))
        (let [^MappedByteBuffer buf (aget segments
                                          (int (unsigned-bit-shift-right off shift)))]
          (.putInt buf (int intra) (unchecked-int val)))
        (seg-store-i32! segments shift off (long val))))
    (fence!)
    nil)

  (-cas-i32! [_ byte-off expected desired]
    (let [cur (seg-load-i32 segments shift (long byte-off))]
      (when (== cur (int expected))
        (seg-store-i32! segments shift (long byte-off) (unchecked-int desired))
        (fence!))
      cur))

  (-add-i32! [_ byte-off delta]
    (let [old (seg-load-i32 segments shift (long byte-off))]
      (seg-store-i32! segments shift (long byte-off) (unchecked-int (+ old (int delta))))
      (fence!)
      old))

  (-sub-i32! [_ byte-off delta]
    (let [old (seg-load-i32 segments shift (long byte-off))]
      (seg-store-i32! segments shift (long byte-off) (unchecked-int (- old (int delta))))
      (fence!)
      old))

  (-exchange-i32! [_ byte-off val]
    (let [old (seg-load-i32 segments shift (long byte-off))]
      (seg-store-i32! segments shift (long byte-off) (unchecked-int val))
      (fence!)
      old))

  (-load-i64 [_ byte-off]
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 8) (bit-shift-left 1 shift))
        (let [^MappedByteBuffer buf (aget segments
                                          (int (unsigned-bit-shift-right off shift)))]
          (.getLong buf (int intra)))
        (seg-load-i64 segments shift off))))

  (-store-i64! [_ byte-off val]
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 8) (bit-shift-left 1 shift))
        (let [^MappedByteBuffer buf (aget segments
                                          (int (unsigned-bit-shift-right off shift)))]
          (.putLong buf (int intra) (long val)))
        (seg-store-i64! segments shift off (long val))))
    (fence!)
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (let [cur (seg-load-i64 segments shift (long byte-off))]
      (when (== cur (long expected))
        (seg-store-i64! segments shift (long byte-off) (long desired))
        (fence!))
      cur))

  (-add-i64! [_ byte-off delta]
    (let [old (seg-load-i64 segments shift (long byte-off))]
      (seg-store-i64! segments shift (long byte-off) (+ old (long delta)))
      (fence!)
      old))

  (-sub-i64! [_ byte-off delta]
    (let [old (seg-load-i64 segments shift (long byte-off))]
      (seg-store-i64! segments shift (long byte-off) (- old (long delta)))
      (fence!)
      old))

  ;; No futex from bb: poll.
  (-wait-i32! [_ byte-off expected timeout-ms]
    (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
      (loop []
        (let [cur (seg-load-i32 segments shift (long byte-off))]
          (cond
            (not= cur (int expected))                  :not-equal
            (>= (System/currentTimeMillis) deadline)   :timed-out
            :else (do (Thread/sleep 1)
                      (recur)))))))

  (-notify-i32! [_ _byte-off _n] 0)

  (-supports-watch? [_] false)

  ;; Absolute bulk get/put across segments: no position state, so no lock.
  (-read-bytes [_ byte-off len]
    ;; Inlined common path (see the varhandle adapter's -read-bytes).
    (let [off   (long byte-off)
          len   (long len)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra len) (bit-shift-left 1 shift))
        (let [^MappedByteBuffer buf (aget segments
                                          (int (unsigned-bit-shift-right off shift)))
              dst (byte-array (int len))]
          (.get buf (int intra) dst 0 (int len))
          dst)
        (seg-read-bytes segments shift off len))))

  (-write-bytes! [_ byte-off src]
    (let [off   (long byte-off)
          len   (alength ^bytes src)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra len) (bit-shift-left 1 shift))
        (let [^MappedByteBuffer buf (aget segments
                                          (int (unsigned-bit-shift-right off shift)))]
          (.put buf (int intra) ^bytes src 0 (int len)))
        (seg-write-bytes! segments shift off src)))
    (fence!)
    nil)

  mem/IHostAtomics
  (-atomic-rmw? [_] false)
  (-with-file-lock [_ byte-off f]
    (when-not chan
      (throw (ex-info "BbMmapRegion: not opened :lockable? — no channel for the file lock"
                      {:byte-off byte-off})))
    ;; An Object monitor (locking), as BbHeapRegion below locks: bb
    ;; 1.3.190's analyzer cannot resolve java.util.concurrent.locks.
    ;; ReentrantLock. -with-file-lock has exactly one call site (eve.atom's
    ;; exclusive hold) and is never re-entered on the same region, so the
    ;; monitor's non-reentrancy cannot bite.
    (locking in-process
      (if @lock-close-ok?
        (let [fl (.lock chan (long byte-off) 4 false)]
          ;; .release is not reachable from bb; close releases (measured).
          (try (f) (finally (.close fl))))
        (let [^FileChannel c (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ
                                                                             StandardOpenOption/WRITE]))]
          (try (.lock c (long byte-off) 4 false) (f) (finally (.close c))))))))

(defn open-mmap-region
  "Open (or create) a file-backed shared memory region at path-str,
   grown to size-bytes when smaller, never truncated. The file is
   mapped in segment windows (see the segmentation note above). opts:
     :lockable?     — keep the file's channel open for -with-file-lock
                      (the .root region of a domain; see BbMmapRegion).
     :segment-bytes — the mapping window: a power of two, 4096..2^30,
                      default 256 MiB.  Presentation, not format: it
                      is stored nowhere in the file.
     :reuse-from    — a BbMmapRegion previously opened on this file at
                      the same segment size: its unchanged windows are
                      reused (growth/refresh map only the delta)."
  ([path-str size-bytes] (open-mmap-region path-str size-bytes nil))
  ([path-str size-bytes opts]
   (let [size  (long size-bytes)
         shift (segment-shift (or (:segment-bytes opts) DEFAULT-SEGMENT-BYTES))
         _     (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
                 (try (when (< (.length raf) size) (.setLength raf size))
                      (finally (.close raf))))
         path  (Paths/get ^String path-str (into-array String []))
         ^FileChannel fc
         (FileChannel/open path
                           (into-array OpenOption
                                       [StandardOpenOption/READ
                                        StandardOpenOption/WRITE]))
         reuse (:reuse-from opts)
         old-segments (when (instance? BbMmapRegion reuse)
                        (.segments ^BbMmapRegion reuse))
         old-shift    (if (instance? BbMmapRegion reuse)
                        (long (.shift ^BbMmapRegion reuse))
                        -1)
         segments (map-segments fc size shift old-segments old-shift)]
     (if (:lockable? opts)
       (BbMmapRegion. segments size (int shift) fc path (Object.))
       (do (.close fc)
           (BbMmapRegion. segments size (int shift) nil nil nil))))))

;; BbHeapRegion — byte[] under ByteBuffer.wrap, one process, its RMW ops
;; serialised by the region's own lock (bb threads are futures).
(deftype BbHeapRegion [^ByteBuffer bb-buf ^bytes backing ^long size ^Object lock]
  mem/IMemRegion
  (-byte-length [_] size)

  (-load-i32 [_ byte-off]
    (locking lock (.getInt bb-buf (int byte-off))))

  (-store-i32! [_ byte-off val]
    (locking lock (.putInt bb-buf (int byte-off) (unchecked-int val)))
    nil)

  (-cas-i32! [_ byte-off expected desired]
    (locking lock
      (let [cur (.getInt bb-buf (int byte-off))]
        (when (== cur (int expected))
          (.putInt bb-buf (int byte-off) (unchecked-int desired)))
        cur)))

  (-add-i32! [_ byte-off delta]
    (locking lock
      (let [old (.getInt bb-buf (int byte-off))]
        (.putInt bb-buf (int byte-off) (unchecked-int (+ old (int delta))))
        old)))

  (-sub-i32! [_ byte-off delta]
    (locking lock
      (let [old (.getInt bb-buf (int byte-off))]
        (.putInt bb-buf (int byte-off) (unchecked-int (- old (int delta))))
        old)))

  (-exchange-i32! [_ byte-off val]
    (locking lock
      (let [old (.getInt bb-buf (int byte-off))]
        (.putInt bb-buf (int byte-off) (unchecked-int val))
        old)))

  (-load-i64 [_ byte-off]
    (locking lock (.getLong bb-buf (int byte-off))))

  (-store-i64! [_ byte-off val]
    (locking lock (.putLong bb-buf (int byte-off) (long val)))
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (locking lock
      (let [cur (.getLong bb-buf (int byte-off))]
        (when (== cur (long expected))
          (.putLong bb-buf (int byte-off) (long desired)))
        cur)))

  (-add-i64! [_ byte-off delta]
    (locking lock
      (let [old (.getLong bb-buf (int byte-off))]
        (.putLong bb-buf (int byte-off) (+ old (long delta)))
        old)))

  (-sub-i64! [_ byte-off delta]
    (locking lock
      (let [old (.getLong bb-buf (int byte-off))]
        (.putLong bb-buf (int byte-off) (- old (long delta)))
        old)))

  (-wait-i32! [this byte-off expected timeout-ms]
    (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
      (loop []
        (let [cur (-load-i32 this byte-off)]
          (cond
            (not= cur (int expected))                  :not-equal
            (>= (System/currentTimeMillis) deadline)   :timed-out
            :else (do (Thread/sleep 1)
                      (recur)))))))

  (-notify-i32! [_ _byte-off _n] 0)

  (-supports-watch? [_] false)

  (-read-bytes [_ byte-off len]
    (let [dst (byte-array len)]
      (System/arraycopy backing (int byte-off) dst 0 (int len))
      dst))

  (-write-bytes! [_ byte-off src]
    (System/arraycopy ^bytes src 0 backing (int byte-off) (alength ^bytes src))
    nil))

(defn make-heap-region
  "Create an IMemRegion backed by a zero-initialized heap byte array."
  [size-bytes]
  (let [ba  (byte-array (int size-bytes))
        buf (doto (ByteBuffer/wrap ba) (.order ByteOrder/LITTLE_ENDIAN))]
    (BbHeapRegion. buf ba (long size-bytes) (Object.))))

(defn lustre-region?
  "true for a region whose atomics are fcntl byte-range locks (a Lustre mount)."
  [_r]
  false)
