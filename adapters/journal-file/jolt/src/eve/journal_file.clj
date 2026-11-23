(ns eve.journal-file
  "The commit journal's file (eve.atom's durability mode): an append-only
   per-domain file beside the domain root, holding commit records only.
   This host: jolt — libc through jolt.ffi, on the python adapter's
   shape: an {:fd :path} handle, and the journal's lock a POSIX lockf
   lock on the fd (glibc lockf is an fcntl lock — the family the java
   adapter records as conflicting with the JVM seat's FileLock on the
   same path), under this process's monitor on the handle, as the java
   seat locks on its channel.

   The java adapter cannot serve this host: under jolt a FileChannel
   is an opaque object — zero-argument calls dispatch, every
   argument-taking channel call (read/write/position/force/truncate/
   lock) fails to resolve (measured 2026-11-08). Bytes therefore move
   through libc pread/pwrite on a raw fd.

   Record format (identical bytes on every host):
     [magic i32][len i32][epoch i32][table-len i32][table bytes][crc32 i32]
   magic = \"EVJ1\" (0x45564A31); all integers big-endian; len counts the
   bytes after itself (epoch + table-len + table + crc); the CRC-32
   covers the epoch..table-end bytes. The commit point is lock, write
   blocks, force slab, append record, force journal — eve.atom runs
   that sequence over this file."
  (:refer-clojure :exclude [aget aset alength byte-array unchecked-byte
                            unsigned-bit-shift-right])
  (:require
   [eve.host :refer [aget aset alength byte-array unchecked-byte
                     unsigned-bit-shift-right]]
   [eve.mem :as mem]
   [eve.deftype-proto.data :as d]
   [clojure.string :as str]
   [jolt.ffi :as ffi]
   [jolt.scheme :as scheme]))

(def ^:private journal-magic 0x45564A31)   ;; "EVJ1"
(def ^:private journal-table-bytes (* d/MAX_ATOM_SLOTS d/ATOM_SLOT_SIZE))

(def ^:private os
  (let [n (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond (str/includes? n "mac") :macos
          (str/includes? n "linux") :linux
          :else (throw (UnsupportedOperationException.
                        (str "eve.journal-file on jolt: no journal for " n))))))

(def ^:private O-RDWR 2)
(def ^:private O-CREAT (if (= :macos os) 0x200 0x40))
(def ^:private F-LOCK 1)                   ;; lockf(2): exclusive, blocking
(def ^:private F-ULOCK 0)                  ;; lockf(2): release
(def ^:private SEEK-SET 0)
(def ^:private SEEK-END 2)

;; libc through jolt.ffi, region/jolt-ffi's idioms: the variadic open
;; carries the :& mode marker and captures errno; positioned I/O
;; (pread/pwrite) keeps no seek state on the shared fd.
(ffi/defcfn jf-open "open" [:string :int :& :int] :int
  {:capture-native-error true})
(ffi/defcfn jf-close "close" [:int] :int)
(ffi/defcfn jf-lseek "lseek" [:int :int64 :int] :int64
  {:capture-native-error true})
(ffi/defcfn jf-pread "pread" [:int :pointer :size_t :int64] :int64
  {:capture-native-error true})
(ffi/defcfn jf-pwrite "pwrite" [:int :pointer :size_t :int64] :int64
  {:capture-native-error true})
(ffi/defcfn jf-fsync "fsync" [:int] :int)
(ffi/defcfn jf-ftruncate "ftruncate" [:int :int64] :int)
(ffi/defcfn jf-lockf "lockf" [:int :int :int64] :int
  {:capture-native-error true})

;; ---- big-endian i32 over a byte array ----

(defn- put-i32! [b off v]
  (let [v (bit-and v 0xFFFFFFFF)]
    (aset b       off  (unchecked-byte (unsigned-bit-shift-right v 24)))
    (aset b (+ off 1) (unchecked-byte (unsigned-bit-shift-right v 16)))
    (aset b (+ off 2) (unchecked-byte (unsigned-bit-shift-right v 8)))
    (aset b (+ off 3) (unchecked-byte v)))
  nil)

(defn- get-i32
  "The signed big-endian i32 at OFF in B (the stored form every host
   reads: java's getInt, node's getInt32)."
  [b off]
  (unchecked-int
   (bit-or (bit-shift-left (bit-and (aget b       off)  0xFF) 24)
           (bit-shift-left (bit-and (aget b (+ off 1)) 0xFF) 16)
           (bit-shift-left (bit-and (aget b (+ off 2)) 0xFF) 8)
           (bit-and (aget b (+ off 3)) 0xFF))))

;; copy-bytes! and crc32-of on the byte array's own bytevector through
;; Chez (bytevector-copy!; a slicing-by-8 IEEE CRC-32, the same
;; polynomial and reflected form as java.util.zip.CRC32 / zlib). The
;; previous per-byte aset/aget loop and Clojure table walk were 21.4%
;; and 10.3% of jolt ds tx-single (dsphase r8, 2037223). Defined once
;; at load through jolt.scheme; output bytes and CRC values are
;; unchanged.
(scheme/eval-string
 "(begin
    (define eve-jf-crc-t
      (let ((t (make-vector 8)))
        (let loop ((k 0))
          (unless (fx>= k 8)
            (vector-set! t k (make-fxvector 256))
            (loop (fx+ k 1))))
        (let ((t0 (vector-ref t 0)))
          (let loop ((n 0))
            (unless (fx>= n 256)
              (let inner ((c n) (i 0))
                (if (fx< i 8)
                    (inner (fxand (if (fxeven? c)
                                      (fxsrl c 1)
                                      (fxxor #xEDB88320 (fxsrl c 1)))
                                  #xFFFFFFFF)
                           (fx+ i 1))
                    (fxvector-set! t0 n c)))
              (loop (fx+ n 1))))
          (let loopk ((k 1))
            (unless (fx>= k 8)
              (let ((tk (vector-ref t k))
                    (tp (vector-ref t (fx- k 1))))
                (let loopn ((n 0))
                  (unless (fx>= n 256)
                    (let ((c (fxvector-ref tp n)))
                      (fxvector-set! tk n (fxand (fxxor (fxsrl c 8)
                                                        (fxvector-ref t0 (fxand c 255)))
                                                 #xFFFFFFFF)))
                    (loopn (fx+ n 1)))))
              (loopk (fx+ k 1)))))
        t))
    (define (eve-jf-crc32 a off len)
      (let* ((bv (jolt-array-vec a))
             (t eve-jf-crc-t)
             (t0 (vector-ref t 0)) (t1 (vector-ref t 1))
             (t2 (vector-ref t 2)) (t3 (vector-ref t 3))
             (t4 (vector-ref t 4)) (t5 (vector-ref t 5))
             (t6 (vector-ref t 6)) (t7 (vector-ref t 7)))
        (let loop ((c #xFFFFFFFF) (i 0))
          (cond
            ((fx<= (fx+ i 8) len)
             (let* ((x (fxand (fxxor c (bytevector-u32-ref bv (fx+ off i) (endianness little)))
                              #xFFFFFFFF))
                    (y (bytevector-u32-ref bv (fx+ off i 4) (endianness little)))
                    (c2 (fxand (fxxor (fxvector-ref t7 (fxand x 255))
                                      (fxvector-ref t6 (fxand (fxsrl x 8) 255))
                                      (fxvector-ref t5 (fxand (fxsrl x 16) 255))
                                      (fxvector-ref t4 (fxsrl x 24))
                                      (fxvector-ref t3 (fxand y 255))
                                      (fxvector-ref t2 (fxand (fxsrl y 8) 255))
                                      (fxvector-ref t1 (fxand (fxsrl y 16) 255))
                                      (fxvector-ref t0 (fxsrl y 24)))
                               #xFFFFFFFF)))
               (loop c2 (fx+ i 8))))
            ((fx< i len)
             (loop (fxand (fxxor (fxsrl c 8)
                                 (fxvector-ref t0 (fxand (fxxor c (bytevector-u8-ref bv (fx+ off i))) 255)))
                          #xFFFFFFFF)
                   (fx+ i 1)))
            (else (fxxor c #xFFFFFFFF))))))
    (define (eve-jf-copy-bytes! src soff dst doff len)
      (when (fx> len 0)
        (bytevector-copy! (jolt-array-vec src) soff (jolt-array-vec dst) doff len))
      #f)
    #t)")

(def ^:private copy-bytes!* (scheme/proc "eve-jf-copy-bytes!"))
(def ^:private crc32-of* (scheme/proc "eve-jf-crc32"))
(defn- copy-bytes! [src soff dst doff len]
  (copy-bytes!* src soff dst doff len)
  nil)
(defn- crc32-of
  "CRC-32 of byte-array B from OFF for LEN bytes, as a signed i32
   (the stored form)."
  [b off len]
  (unchecked-int (crc32-of* b off len)))

;; ---- fd I/O: byte arrays across the FFI through a scratch buffer ----

(defn- write-all-at!
  "Write all of byte-array B to FD at absolute offset POS (pwrite
   loop; a partial write advances, 0 or an error throws)."
  [fd b pos]
  (let [total (alength b)]
    (when (pos? total)
      (let [buf (ffi/alloc total)]
        (try
          (ffi/write-array buf b)
          (loop [off 0]
            (when (< off total)
              (let [[n errno] (jf-pwrite fd (+ buf off) (- total off)
                                          (+ pos off))]
                (cond (neg? n)
                      (throw (ex-info (str "eve.journal-file: pwrite errno "
                                           errno) {:errno errno}))
                      (zero? n)
                      (throw (ex-info "eve.journal-file: pwrite wrote 0"
                                      {}))
                      :else (recur (+ off n))))))
          (finally (ffi/free buf)))))))

(defn- file-size [{:keys [fd]}]
  (let [[size errno] (jf-lseek fd 0 SEEK-END)]
    (when (neg? size)
      (throw (ex-info (str "eve.journal-file: lseek errno " errno)
                      {:errno errno})))
    size))

(defn- read-all-bytes
  "The journal file's whole content as a byte array (pread loop)."
  [{:keys [fd] :as h}]
  (let [size (file-size h)]
    (if (zero? size)
      (byte-array 0)
      (let [buf (ffi/alloc size)]
        (try
          (let [got (loop [off 0]
                      (if (< off size)
                        (let [[n errno] (jf-pread fd (+ buf off)
                                                  (- size off) off)]
                          (cond (neg? n)
                                (throw (ex-info
                                        (str "eve.journal-file: pread errno "
                                             errno) {:errno errno}))
                                (zero? n) off   ;; shrank under us: scan what exists
                                :else (recur (+ off n))))
                        off))]
            (ffi/read-array buf got))
          (finally (ffi/free buf)))))))

;; ---- the contract (eve.journal-file, 10 fns) ----

(defn open-journal
  "Open (creating if needed) the journal file at PATH for read+write.
   Returns the file handle {:fd :path}. The path is made absolute
   first: open(2) resolves a relative path against the process's
   cwd, which jolt's launcher moved to jolt's own root (region's
   precedent)."
  [path]
  (let [abs (.getAbsolutePath (java.io.File. ^String path))
        [fd errno] (jf-open abs (bit-or O-RDWR O-CREAT) 420)]   ;; 0644
    (when (neg? fd)
      (throw (ex-info (str "eve.journal-file: open " path ": errno " errno)
                      {:path path :errno errno})))
    {:fd fd :path path}))

(defn with-journal-lock
  "Run F holding the journal's lock: this process's monitor on the
   handle (the java seat's shape, on its channel) around a POSIX
   lockf lock on the journal fd (the python seat's shape, through
   fcntl.lockf — the same lock family, so the seats exclude each
   other on one journal file). lockf's region runs from the fd's
   offset: seek to 0 first so the region is the whole file, with
   length 0 (to EOF)."
  [{:keys [fd] :as h} f]
  (locking h
    (let [[off errno] (jf-lseek fd 0 SEEK-SET)]
      (when (neg? off)
        (throw (ex-info (str "eve.journal-file: lseek(0) errno " errno)
                        {:errno errno}))))
    (let [[r errno] (jf-lockf fd F-LOCK 0)]
      (when (neg? r)
        (throw (ex-info (str "eve.journal-file: lockf errno " errno)
                        {:errno errno})))
      (try (f)
           (finally
             ;; re-seek before unlocking: f's appends moved the fd offset
             ;; to EOF, and lockf's region runs from the offset — an
             ;; unlock issued there releases only [EOF, EOF) and leaks
             ;; the [0, EOF) prefix for the life of the process.
             (jf-lseek fd 0 SEEK-SET)
             (jf-lockf fd F-ULOCK 0))))))

(defn journal-record-of
  "The commit record for an explicit EPOCH and TABLE (a byte array)."
  [epoch tbl]
  (let [tlen (alength tbl)
        len  (+ 4 4 tlen 4)
        out  (byte-array (+ 8 len))]
    (put-i32! out 0 journal-magic)
    (put-i32! out 4 len)
    (put-i32! out 8 (int epoch))
    (put-i32! out 12 tlen)
    (copy-bytes! tbl 0 out 16 tlen)
    (put-i32! out (+ 16 tlen) (crc32-of out 8 (+ 4 4 tlen)))
    out))

(defn journal-record
  "The commit record for ROOT-R's CURRENT epoch and atom table, as a
   byte array."
  [root-r]
  (journal-record-of (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)
                     (mem/-read-bytes root-r d/ATOM_TABLE_START
                                      journal-table-bytes)))

(defn journal-scan
  "Scan the journal's records in order. Returns {:newest {:epoch :table}
   :oldest epoch :torn? bool} — a record is accepted iff its framing
   survives (magic + length in range) and its CRC matches; a record whose
   framing survives but whose CRC fails is SKIPPED (its length still
   advances the scan); a framing failure ends the scan."
  [h]
  (let [data  (read-all-bytes h)
        total (alength data)]
    (loop [pos 0 newest nil oldest nil torn? false]
      (let [remaining (- total pos)]
        (cond
          (< remaining 8)
          {:newest newest :oldest oldest
           :torn? (or torn? (pos? remaining))}

          :else
          (let [magic (get-i32 data pos)
                len   (get-i32 data (+ pos 4))]
            (if (or (not= magic journal-magic)
                    (not (pos? len))
                    (> len (- remaining 8)))
              {:newest newest :oldest oldest :torn? true}
              (let [epoch (get-i32 data (+ pos 8))
                    tlen  (get-i32 data (+ pos 12))]
                (if (> (+ 4 4 tlen 4) len)
                  {:newest newest :oldest oldest :torn? true}
                  (let [stored (get-i32 data (+ pos 16 tlen))]
                    (if (= stored (crc32-of data (+ pos 8) (+ 4 4 tlen)))
                      (let [tbl (byte-array tlen)]
                        (copy-bytes! data (+ pos 16) tbl 0 tlen)
                        (recur (+ pos 8 len)
                               {:epoch epoch :table tbl}
                               (or oldest epoch)
                               torn?))
                      (recur (+ pos 8 len) newest oldest true))))))))))))

(defn journal-append!
  "Write BYTES (a byte array) at the end of the journal file. Does
   not force — the caller forces at the commit point. Callers hold
   the journal lock (eve.atom's flush does), so the size read and
   the write are one step."
  [{:keys [fd] :as h} bytes]
  (write-all-at! fd bytes (file-size h)))

(defn journal-write-at!
  "Write BYTES (a byte array) at absolute offset POS."
  [{:keys [fd]} bytes pos]
  (write-all-at! fd bytes pos))

(defn journal-truncate!
  "Truncate the journal file to zero length (no force; the caller
   forces as part of its checkpoint/recovery sequence)."
  [{:keys [fd]}]
  (jf-ftruncate fd 0)
  nil)

(defn journal-force!
  "Force the journal file to storage. fsync forces data and
   metadata alike — FULL? is ignored (the stronger force is taken
   on both paths, as the node and python seats do)."
  [{:keys [fd]} full?]
  (jf-fsync fd)
  nil)

(defn journal-close!
  "Close the journal file; never throws."
  [{:keys [fd]}]
  (try (jf-close fd) (catch Throwable _ nil))
  nil)
