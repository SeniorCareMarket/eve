(ns eve.region
  "eve's regions (eve.mem's IMemRegion and IHostAtomics; HOST-CONTRACT §1-2)
   over java.nio without VarHandle (babashka): a MappedByteBuffer whose
   read-modify-write ops are plain, made exclusive across processes by a
   FileChannel byte-range lock (IHostAtomics: a lock-only region).

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

(deftype BbMmapRegion [^MappedByteBuffer mbb ^long size
                       ^FileChannel chan
                       path
                       ^java.util.concurrent.locks.ReentrantLock in-process]
  mem/IMemRegion
  (-byte-length [_] size)

  (-load-i32 [_ byte-off]
    (.getInt mbb (int byte-off)))

  (-store-i32! [_ byte-off val]
    (.putInt mbb (int byte-off) (unchecked-int val))
    (fence!)
    nil)

  (-cas-i32! [_ byte-off expected desired]
    (let [cur (.getInt mbb (int byte-off))]
      (when (== cur (int expected))
        (.putInt mbb (int byte-off) (unchecked-int desired))
        (fence!))
      cur))

  (-add-i32! [_ byte-off delta]
    (let [old (.getInt mbb (int byte-off))]
      (.putInt mbb (int byte-off) (unchecked-int (+ old (int delta))))
      (fence!)
      old))

  (-sub-i32! [_ byte-off delta]
    (let [old (.getInt mbb (int byte-off))]
      (.putInt mbb (int byte-off) (unchecked-int (- old (int delta))))
      (fence!)
      old))

  (-exchange-i32! [_ byte-off val]
    (let [old (.getInt mbb (int byte-off))]
      (.putInt mbb (int byte-off) (unchecked-int val))
      (fence!)
      old))

  (-load-i64 [_ byte-off]
    (.getLong mbb (int byte-off)))

  (-store-i64! [_ byte-off val]
    (.putLong mbb (int byte-off) (long val))
    (fence!)
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (let [cur (.getLong mbb (int byte-off))]
      (when (== cur (long expected))
        (.putLong mbb (int byte-off) (long desired))
        (fence!))
      cur))

  (-add-i64! [_ byte-off delta]
    (let [old (.getLong mbb (int byte-off))]
      (.putLong mbb (int byte-off) (+ old (long delta)))
      (fence!)
      old))

  (-sub-i64! [_ byte-off delta]
    (let [old (.getLong mbb (int byte-off))]
      (.putLong mbb (int byte-off) (- old (long delta)))
      (fence!)
      old))

  ;; No futex from bb: poll.
  (-wait-i32! [_ byte-off expected timeout-ms]
    (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
      (loop []
        (let [cur (.getInt mbb (int byte-off))]
          (cond
            (not= cur (int expected))                  :not-equal
            (>= (System/currentTimeMillis) deadline)   :timed-out
            :else (do (Thread/sleep 1)
                      (recur)))))))

  (-notify-i32! [_ _byte-off _n] 0)

  (-supports-watch? [_] false)

  ;; Absolute bulk get/put: no position state, so no lock.
  (-read-bytes [_ byte-off len]
    (let [dst (byte-array len)]
      (.get mbb (int byte-off) dst)
      dst))

  (-write-bytes! [_ byte-off src]
    (.put mbb (int byte-off) ^bytes src)
    (fence!)
    nil)

  mem/IHostAtomics
  (-atomic-rmw? [_] false)
  (-with-file-lock [_ byte-off f]
    (when-not chan
      (throw (ex-info "BbMmapRegion: not opened :lockable? — no channel for the file lock"
                      {:byte-off byte-off})))
    (.lock in-process)
    (try
      (if @lock-close-ok?
        (let [fl (.lock chan (long byte-off) 4 false)]
          ;; .release is not reachable from bb; close releases (measured).
          (try (f) (finally (.close fl))))
        (let [^FileChannel c (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ
                                                                             StandardOpenOption/WRITE]))]
          (try (.lock c (long byte-off) 4 false) (f) (finally (.close c)))))
      (finally (.unlock in-process)))))

(defn open-mmap-region
  "Open (or create) a file-backed shared memory region at path-str,
   grown to size-bytes when smaller, never truncated. opts:
     :lockable? — keep the file's channel open for -with-file-lock
                  (the .root region of a domain; see BbMmapRegion)."
  ([path-str size-bytes] (open-mmap-region path-str size-bytes nil))
  ([path-str size-bytes opts]
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
     (.order mbb ByteOrder/LITTLE_ENDIAN)
     (if (:lockable? opts)
       (BbMmapRegion. mbb size fc path (java.util.concurrent.locks.ReentrantLock.))
       (do (.close fc)
           (BbMmapRegion. mbb size nil nil nil))))))

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
