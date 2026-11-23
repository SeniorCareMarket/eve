(ns eve.region
  "eve's regions (eve.mem's IMemRegion and IHostAtomics; HOST-CONTRACT §1-2)
   over java.nio without VarHandle (babashka): a table of MappedByteBuffer
   segment windows whose read-modify-write ops are plain, made exclusive
   across processes by a FileChannel byte-range lock (IHostAtomics: a
   lock-only region).

   make-heap-region and open-mmap-region open them; lustre-region? says
   whether a region's atomics are fcntl byte-range locks (a Lustre mount).

   With a working babashka.ffi the openers return BbNativeRegions
   instead (libatomic hardware CAS — the Native CAS section below);
   the lock-only regions above are the fallback, announced on *err*
   when taken."

  ;; this namespace's per-seat defs are in the central config's
  ;; :unresolved-var exclusions (.clj-kondo/config.edn): one namespace
  ;; lives in several seat files and kondo's registry keeps one file's
  ;; defs, reading the others' as unresolved.

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
                            -read-bytes -write-bytes! -atomic-rmw? -with-file-lock]] [clojure.string])
  (:import
   [java.io RandomAccessFile]
   [java.nio ByteBuffer ByteOrder MappedByteBuffer]
   [java.nio.channels FileChannel FileChannel$MapMode]
   [java.nio.file OpenOption Paths StandardOpenOption]))

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
               (bit-shift-left (bit-and (.get (seg-buf segments shift (inc off)) (int (seg-intra shift (inc off)))) 0xFF) 8)
               (bit-shift-left (bit-and (.get (seg-buf segments shift (+ off 2)) (int (seg-intra shift (+ off 2)))) 0xFF) 16)
               (bit-shift-left (bit-and (.get (seg-buf segments shift (+ off 3)) (int (seg-intra shift (+ off 3)))) 0xFF) 24))))))

(defn- seg-store-i32!
  [^objects segments ^long shift ^long off ^long v]
  (let [intra (seg-intra shift off)]
    (if (<= (+ intra 4) (bit-shift-left 1 shift))
      (.putInt (seg-buf segments shift off) (int intra) (unchecked-int v))
      (do (.put (seg-buf segments shift off) (int (seg-intra shift off)) (unchecked-byte v))
          (.put (seg-buf segments shift (inc off)) (int (seg-intra shift (inc off))) (unchecked-byte (unsigned-bit-shift-right v 8)))
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

;; ------------------------------------------------------------------
;; Native CAS — babashka.ffi over libatomic (the substrate of the
;; jolt-ffi and ctypes regions: __atomic_* on the mapped address).
;;
;; babashka.ffi is built into bb 1.13.220+ and needs the dynamically
;; linked build (a musl-static or BABASHKA_LIBFFI=none bb cannot
;; dlopen). The probe below is the only door to it: it resolves the
;; namespace through ns-resolve, so this file loads identically on a
;; bb without the namespace (1.3.190-era), and every failure — no
;; namespace, no libatomic, a binding the binary cannot make, a CAS
;; round-trip that does not round-trip — leaves native CAS
;; unavailable WITH ITS REASON. open-mmap-region and make-heap-region
;; then fall back to the lock-only regions and announce the fallback
;; and the reason on *err* (once per process): a downgrade is never
;; silent. EVE_BB_NATIVE_CAS=0 forces the fallback (the
;; EVE_BB_FILE_LOCK precedent above).
;; ------------------------------------------------------------------

(def ^:private SEQ-CST 5)

(def ^:private mac-os?
  (clojure.string/includes? (clojure.string/lower-case (str (System/getProperty "os.name"))) "mac"))

(def ^:private strict-alignment?
  ;; arm64's atomics fault at an address that is not a multiple of the
  ;; word's width, where x86-64 performs the access (the jolt region's
  ;; finding). Where alignment is strict, a load or store at an
  ;; unaligned address is a plain one; eve's read-modify-writes are on
  ;; aligned words, so the RMW ops need no such guard.
  (boolean (or mac-os?
               (#{"aarch64" "arm64"} (clojure.string/lower-case (str (System/getProperty "os.arch"))))
               (= "1" (System/getenv "EVE_BB_STRICT_ALIGNMENT")))))

(def ^:private SYS-futex
  (case (clojure.string/lower-case (str (System/getProperty "os.arch")))
    ("amd64" "x86_64") 202
    ("aarch64" "arm64") 98
    nil))

(def ^:private O-CREAT (if mac-os? 0x200 0x40))

;; One 16-byte scratch per thread, from the global arena: the CAS
;; witness word (libatomic writes the value it found into it) and the
;; futex timespec. A thread uses it for one call at a time.
(def ^:private native-scratch-tl (ThreadLocal.))

(defn- native-scratch [api]
  (let [s (.get native-scratch-tl)]
    (if s
      s
      (let [s ((:alloc api) ((:global-arena api)) 16)]
        (.set native-scratch-tl s)
        s))))

(defn- native-load [api seg off width]
  (let [addr (+ ((:address api) seg) (long off))
        w (if (= width :int32) 4 8)
        p ((:segment api) addr w)]
    (if (and strict-alignment? (not (zero? (bit-and addr (dec w)))))
      ((:read api) p width)
      ((if (= width :int32) (:load4 api) (:load8 api)) p SEQ-CST))))

(defn- native-store [api seg off width v]
  (let [addr (+ ((:address api) seg) (long off))
        w (if (= width :int32) 4 8)
        p ((:segment api) addr w)]
    (if (and strict-alignment? (not (zero? (bit-and addr (dec w)))))
      ((:write api) p width v)
      ((if (= width :int32) (:store4 api) (:store8 api)) p v SEQ-CST)))
  nil)

(defn- native-cas [api seg off width expected desired]
  (let [wit (native-scratch api)
        w (if (= width :int32) 4 8)
        p ((:segment api) (+ ((:address api) seg) (long off)) w)]
    ((:write api) wit width expected)
    ((if (= width :int32) (:cas4 api) (:cas8 api)) p wit desired false SEQ-CST SEQ-CST)
    ((:read api) wit width)))

(defn- native-add [api seg off width delta]
  ((if (= width :int32) (:add4 api) (:add8 api))
   ((:segment api) (+ ((:address api) seg) (long off)) (if (= width :int32) 4 8))
   delta SEQ-CST))

(defn- native-xchg [api seg off width v]
  ((if (= width :int32) (:xchg4 api) (:xchg8 api))
   ((:segment api) (+ ((:address api) seg) (long off)) (if (= width :int32) 4 8))
   v SEQ-CST))

(defn- native-errno [api]
  ;; __errno_location returns a bare :pointer — a size-0 segment —
  ;; and ffi's read rejects those outright, so an errno read would
  ;; throw the ffi error and mask the real errno in every failure
  ;; path that reports one (the probe's open/mmap checks, region
  ;; open). Reinterpret to the int's width first, the same sizing
  ;; the probe gives the scratch mapping.
  ((:read api) ((:reinterpret api) ((:errno-loc api)) 4) :int))

(defn- native-wait [api seg off expected timeout-ms]
  (let [expected (int expected)]
    (if-not SYS-futex
      (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
        (loop []
          (cond (not= (native-load api seg off :int32) expected) :not-equal
                (>= (System/currentTimeMillis) deadline) :timed-out
                :else (do (Thread/sleep 1) (recur)))))
      (if (not= (native-load api seg off :int32) expected)
        :not-equal
        (let [ts (native-scratch api)
              p ((:segment api) (+ ((:address api) seg) (long off)) 4)]
          ((:write api) ts :int64 (quot (long timeout-ms) 1000))
          ((:write api) ts :int64 (* 1000000 (mod (long timeout-ms) 1000)) 8)
          (let [r ((:syscall api) SYS-futex p 0 expected ts (:null api) 0)]
            (if (zero? r)
              :ok
              (case (native-errno api)
                11 :not-equal
                110 :timed-out
                :ok))))))))

(defn- native-notify [api seg off n]
  (if SYS-futex
    (int ((:syscall api) SYS-futex
                         ((:segment api) (+ ((:address api) seg) (long off)) 4)
                         1 (int n) (:null api) (:null api) 0))
    0))

(defn- native-sync [api seg off len]
  (let [addr (+ ((:address api) seg) (long off))
        start (bit-and addr -4096)
        mlen (- (+ addr (long len)) start)]
    ((:msync api) ((:segment api) start mlen) mlen 4))
  nil)

(def ^:private native-probe
  (delay
    (try
      (if (= "0" (System/getenv "EVE_BB_NATIVE_CAS"))
        {:available? false :reason "EVE_BB_NATIVE_CAS=0"}
        (do
          (require 'babashka.ffi)
          (let [ffi-ns (the-ns 'babashka.ffi)
                rv (fn [sym]
                     (let [v (ns-resolve ffi-ns sym)]
                       (when-not v
                         (throw (ex-info (str "babashka.ffi has no " sym) {})))
                       (var-get v)))
                cfn (rv 'cfn)
                load-library (rv 'load-library)
                load-system-library (rv 'load-system-library)
                find-symbol (rv 'find-symbol)
                libatomic (load-library (if mac-os?
                                          ["libatomic.dylib" "libatomic.so.1" "libatomic.so"]
                                          ["libatomic.so.1" "libatomic.so"]))
                _ (when-not (find-symbol libatomic "__atomic_load_4")
                    (throw (ex-info "the libatomic candidate exports no __atomic_load_4" {})))
                libc (load-system-library "c")
                bind (fn [lib sym sig ret] (cfn lib sym sig ret))
                api {:read (rv 'read) :write (rv 'write)
                     :segment (rv 'segment) :address (rv 'address)
                     :reinterpret (rv 'reinterpret)
                     :alloc (rv 'alloc) :global-arena (rv 'global-arena)
                     :arena-ctor (if-let [v (ns-resolve ffi-ns 'auto-arena)]
                                   (var-get v)
                                   (rv 'global-arena))
                     :arena-keep? (boolean (ns-resolve ffi-ns 'auto-arena))
                     :read-array (rv 'read-array) :write-array (rv 'write-array)
                     :null (rv 'null)
                     :load4 (bind libatomic "__atomic_load_4" [:pointer :int] :int32)
                     :store4 (bind libatomic "__atomic_store_4" [:pointer :int32 :int] :void)
                     :cas4 (bind libatomic "__atomic_compare_exchange_4" [:pointer :pointer :int32 :bool :int :int] :bool)
                     :add4 (bind libatomic "__atomic_fetch_add_4" [:pointer :int32 :int] :int32)
                     :xchg4 (bind libatomic "__atomic_exchange_4" [:pointer :int32 :int] :int32)
                     :load8 (bind libatomic "__atomic_load_8" [:pointer :int] :int64)
                     :store8 (bind libatomic "__atomic_store_8" [:pointer :int64 :int] :void)
                     :cas8 (bind libatomic "__atomic_compare_exchange_8" [:pointer :pointer :int64 :bool :int :int] :bool)
                     :add8 (bind libatomic "__atomic_fetch_add_8" [:pointer :int64 :int] :int64)
                     :xchg8 (bind libatomic "__atomic_exchange_8" [:pointer :int64 :int] :int64)
                     :open (bind libc "open" [:string :int :& :int] :int)
                     :close (bind libc "close" [:int] :int)
                     :ftruncate (bind libc "ftruncate" [:int :int64] :int)
                     :mmap (bind libc "mmap" [:pointer :size_t :int :int :int :int64] :pointer)
                     :munmap (bind libc "munmap" [:pointer :size_t] :int)
                     :msync (bind libc "msync" [:pointer :size_t :int] :int)
                     :memset (bind libc "memset" [:pointer :int :size_t] :pointer)
                     :syscall (bind libc "syscall" [:long :pointer :int :int :pointer :pointer :int] :long)
                     :errno-loc (bind libc "__errno_location" [] :pointer)
                     :libatomic-path (:path libatomic)}]
            ;; The smoke: a CAS round-trip on a scratch mapping, through
            ;; the same helpers the region uses (it also exercises the
            ;; ThreadLocal witness scratch). Anything that does not
            ;; round-trip is not a native region.
            (let [f (java.io.File/createTempFile "eve-native" ".probe")
                  path (.getAbsolutePath f)
                  fd ((:open api) path (bit-or 2 O-CREAT) 420)]
              (when (neg? fd)
                (throw (ex-info (str "probe open: errno " (native-errno api)) {})))
              (try
                ((:ftruncate api) fd 4096)
                (let [p ((:mmap api) (:null api) 4096 3 1 fd 0)
                      addr ((:address api) p)]
                  (when (== addr -1)
                    (throw (ex-info (str "probe mmap: errno " (native-errno api)) {})))
                  (try
                    (let [seg ((:reinterpret api) p 4096)]
                      (when-not (zero? (native-load api seg 0 :int32))
                        (throw (ex-info "probe: a fresh word is not 0" {})))
                      (native-store api seg 0 :int32 41)
                      (when-not (== 41 (native-cas api seg 0 :int32 41 42))
                        (throw (ex-info "probe: the CAS witness is wrong" {})))
                      (when-not (== 42 (native-add api seg 0 :int32 8))
                        (throw (ex-info "probe: fetch-add is wrong" {})))
                      (when-not (== 50 (native-load api seg 0 :int32))
                        (throw (ex-info "probe: the load after add is wrong" {})))
                      (when-not (== 50 (native-cas api seg 0 :int64 50 7))
                        (throw (ex-info "probe: the i64 CAS witness is wrong" {}))))
                    (finally ((:munmap api) p 4096))))
                (finally ((:close api) fd) (.delete f))))
            (assoc api :available? true))))
      (catch Throwable e
        {:available? false :reason (str e)}))))

;; BbNativeRegion — a file mapped MAP_SHARED by libc mmap (or, for a
;; heap region, an arena allocation), every op libatomic's on the
;; mapped address. -atomic-rmw? is the file-wide Object default:
;; true. arena is the heap allocation's arena, held so the garbage
;; collector cannot release it under a live region (nil for a mapped
;; region, and for a global-arena heap allocation).
(deftype BbNativeRegion [seg size path api arena]
  mem/IMemRegion
  (-byte-length [_] size)

  (-load-i32 [_ byte-off] (native-load api seg byte-off :int32))
  (-store-i32! [_ byte-off val] (native-store api seg byte-off :int32 (unchecked-int val)))
  (-cas-i32! [_ byte-off expected desired]
    (native-cas api seg byte-off :int32 (unchecked-int expected) (unchecked-int desired)))
  (-add-i32! [_ byte-off delta] (native-add api seg byte-off :int32 (unchecked-int delta)))
  (-sub-i32! [_ byte-off delta] (native-add api seg byte-off :int32 (unchecked-int (- delta))))
  (-exchange-i32! [_ byte-off val] (native-xchg api seg byte-off :int32 (unchecked-int val)))

  (-load-i64 [_ byte-off] (native-load api seg byte-off :int64))
  (-store-i64! [_ byte-off val] (native-store api seg byte-off :int64 (long val)))
  (-cas-i64! [_ byte-off expected desired]
    (native-cas api seg byte-off :int64 (long expected) (long desired)))
  (-add-i64! [_ byte-off delta] (native-add api seg byte-off :int64 (long delta)))
  (-sub-i64! [_ byte-off delta] (native-add api seg byte-off :int64 (long (- delta))))

  (-wait-i32! [_ byte-off expected timeout-ms] (native-wait api seg byte-off expected timeout-ms))
  (-notify-i32! [_ byte-off n] (native-notify api seg byte-off n))
  (-supports-watch? [_] false)

  (-read-bytes [_ byte-off len]
    ;; The segment handed to read-array is built from the transfer
    ;; length, so the region's extent must be enforced here, against
    ;; size: an empty transfer would hand ffi a size-0 segment, which
    ;; read-array rejects outright, and an out-of-extent length would
    ;; declare the segment at that length — read-array's own bound
    ;; then passes vacuously and the copy runs off the mapping (a
    ;; segfault, not an error). The sibling regions get both
    ;; behaviors from their backing stores (ByteBuffer / arraycopy
    ;; bounds), where an empty transfer is a no-op; it is one here
    ;; too, and an out-of-extent transfer is an ex-info, not a fault.
    (let [byte-off (long byte-off)
          len      (long len)]
      (if (zero? len)
        (byte-array 0)
        (do (when (or (neg? byte-off) (neg? len) (> (+ byte-off len) size))
              (throw (ex-info (str "eve.region: read of " len " bytes at offset "
                                   byte-off " is outside the region's "
                                   size " bytes")
                              {:byte-off byte-off :len len :size size})))
            ((:read-array api) ((:segment api) (+ ((:address api) seg) byte-off) len)
                               :byte (int len))))))

  (-write-bytes! [_ byte-off src]
    (let [byte-off (long byte-off)
          n        (alength src)]
      (when (pos? n)
        (when (or (neg? byte-off) (> (+ byte-off n) size))
          (throw (ex-info (str "eve.region: write of " n " bytes at offset "
                               byte-off " is outside the region's "
                               size " bytes")
                          {:byte-off byte-off :len n :size size})))
        ((:write-array api) ((:segment api) (+ ((:address api) seg) byte-off) n)
                            :byte src)))
    nil)

  mem/ISyncableRegion
  (-sync! [_] (native-sync api seg 0 size))
  (-sync-range! [_ byte-off len] (native-sync api seg byte-off len)))

(defn- native-open-mmap-region [api path-str size-bytes]
  (let [size (long size-bytes)
        _ (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
            (try (when (< (.length raf) size) (.setLength raf size))
                 (finally (.close raf))))
        abs (.getAbsolutePath (java.io.File. ^String path-str))
        fd ((:open api) abs (bit-or 2 O-CREAT) 420)]
    (when (neg? fd)
      (throw (ex-info (str "eve.region: open " path-str ": errno " (native-errno api))
                      {:path path-str})))
    (try
      (let [p ((:mmap api) (:null api) size 3 1 fd 0)
            addr ((:address api) p)]
        (when (== addr -1)
          (throw (ex-info (str "eve.region: mmap " path-str ": errno " (native-errno api))
                          {:path path-str})))
        (BbNativeRegion. ((:reinterpret api) p size) size path-str api nil))
      (finally ((:close api) fd)))))

(def ^:private fallback-announced (atom false))

(defn- announce-fallback! [probe]
  (when (compare-and-set! fallback-announced false true)
    (binding [*out* *err*]
      (println (str "eve.region: bb native CAS unavailable (" (:reason probe)
                    ") — regions are lock-only (BbMmapRegion, file-lock hold protocol)")))))

(defn- open-mmap-region-niolock
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
  ([path-str size-bytes] (open-mmap-region-niolock path-str size-bytes nil))
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

(defn open-mmap-region
  "Open (or create) a file-backed shared memory region at path-str,
   grown to size-bytes when smaller, never truncated: a
   BbNativeRegion (hardware CAS) when the native probe passes, else
   the lock-only BbMmapRegion of open-mmap-region-niolock, announced
   on *err*. opts:
     :lockable?     — a native region needs no channel (its
                      -with-file-lock is the atomic default); the
                      lock-only region keeps its channel as before.
     :segment-bytes — the lock-only region's mapping window; a native
                      region maps the file in one mapping.
     :reuse-from    — a region previously opened on this file: a
                      native region of the same path and size is
                      returned as-is; a grown one is unmapped and
                      remapped."
  ([path-str size-bytes] (open-mmap-region path-str size-bytes nil))
  ([path-str size-bytes opts]
   (let [probe @native-probe]
     (if (:available? probe)
       (let [reuse (:reuse-from opts)]
         (if (and (instance? BbNativeRegion reuse)
                  (= (.path ^BbNativeRegion reuse) path-str)
                  (== (.size ^BbNativeRegion reuse) (long size-bytes)))
           reuse
           (do (when (instance? BbNativeRegion reuse)
                 ((:munmap probe) (.seg ^BbNativeRegion reuse) (.size ^BbNativeRegion reuse)))
               (native-open-mmap-region probe path-str size-bytes))))
       (do (announce-fallback! probe)
           (open-mmap-region-niolock path-str size-bytes opts))))))

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

(defn- make-heap-region-niolock
  "Create an IMemRegion backed by a zero-initialized heap byte array."
  [size-bytes]
  (let [ba  (byte-array (int size-bytes))
        buf (doto (ByteBuffer/wrap ba) (.order ByteOrder/LITTLE_ENDIAN))]
    (BbHeapRegion. buf ba (long size-bytes) (Object.))))

(defn make-heap-region
  "Create an IMemRegion backed by zero-initialized memory: a
   BbNativeRegion over an arena allocation when the native probe
   passes (the region holds its arena, so the memory lives exactly
   as long as the region), else the BbHeapRegion of
   make-heap-region-niolock, announced on *err*."
  [size-bytes]
  (let [probe @native-probe]
    (if (:available? probe)
      (let [arena ((:arena-ctor probe))
            seg ((:alloc probe) arena (long size-bytes))]
        ((:memset probe) seg 0 (long size-bytes))
        (BbNativeRegion. seg (long size-bytes) nil probe
                         (when (:arena-keep? probe) arena)))
      (do (announce-fallback! probe)
          (make-heap-region-niolock size-bytes)))))

(defn lustre-region?
  "true for a region whose atomics are fcntl byte-range locks (a Lustre mount)."
  [_r]
  false)
