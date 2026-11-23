(ns eve.region
  "eve's regions (eve.mem's IMemRegion and IHostAtomics; HOST-CONTRACT §1-2)
   over jolt's FFI (Chez Scheme's foreign procedures): a file mapped MAP_SHARED;
   on Linux its atomics the system libatomic's and wait/notify the futex syscall,
   on macOS its atomics libSystem's and wait a poll (the JVM's mmap region's).

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
                     -read-bytes -write-bytes! -atomic-rmw? -with-file-lock]]
   [clojure.string :as str]
   [jolt.ffi :as ffi]
   [jolt.scheme :as scheme]))

;; Every region has atomic RMW unless it says otherwise.
(extend-protocol mem/IHostAtomics
  Object
  (-atomic-rmw? [_] true)
  (-with-file-lock [_ _byte-off f] (f)))

   ;; jolt (Clojure on Chez Scheme): a region is memory at a fixed address — the file
   ;; mapped MAP_SHARED, or an immobile bytevector in Chez's heap — and a jolt pointer
   ;; is its integer address. All of it is jolt.ffi over what the system ships; nothing is built.
   ;; Linux: the atomics are libatomic's, wait/notify futex(2) through libc's syscall (202 on
   ;; x86-64, 98 on arm64; another architecture polls). macOS: the atomics are libSystem's
   ;; OSAtomic barrier functions (a compare-and-swap that answers a boolean, an add that answers
   ;; the new value — the rest built over them below), and a wait polls the word every 100 µs, as
   ;; the JVM's mmap region does: a notify wakes no one, the waiter sees the change itself.
   ;; (jolt.ffi resolves a binding on its first call, so declaring every platform's is safe.)

(def ^:private os
  (let [n (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond (str/includes? n "mac") :macos
          (str/includes? n "linux") :linux
          :else (throw (UnsupportedOperationException. (str "eve on jolt: no region for " n))))))

(def ^:private SYS-futex
  (when (= :linux os)
    (case (str/lower-case (or (System/getProperty "os.arch") ""))
      ("amd64" "x86_64") 202
      ("aarch64" "arm64") 98
      nil)))

(def ^:private O-CREAT (if (= :macos os) 0x200 0x40))

;; arm64's atomics fault at an address that is not a multiple of the word's width, where x86-64 performs the access.
;; eve's codec reads and writes words at any byte of a block (a value's fields packed), and an eve-media domain's open
;; alone makes 57 such 4-byte loads and stores (probes/jolt_region_alignment.clj) — a Mac, 2026-10-22:
;; "invalid memory reference". So where alignment is strict a load or store at an unaligned address is a plain one,
;; which arm64 performs at any address; eve's read-modify-writes are on aligned words. EVE_JOLT_STRICT_ALIGNMENT=1
;; takes that path on x86-64 too (to test it).
(def ^:private strict-alignment?
  (boolean (or (= :macos os)
               (#{"aarch64" "arm64"} (str/lower-case (or (System/getProperty "os.arch") "")))
               (= "1" (System/getenv "EVE_JOLT_STRICT_ALIGNMENT")))))

(defn- load-or-plain
  "A load: f at an address that is a multiple of width, else plain (f itself where alignment is not strict)."
  [width f plain]
  (if strict-alignment?
    (let [m (dec width)] (fn [a] (if (zero? (bit-and a m)) (f a) (plain a))))
    f))

(defn- store-or-plain
  "A store: f at an address that is a multiple of width, else plain (f itself where alignment is not strict)."
  [width f plain]
  (if strict-alignment?
    (let [m (dec width)] (fn [a v] (if (zero? (bit-and a m)) (f a v) (plain a v))))
    f))

(def ^:private SEQ-CST 5)
(when (= :linux os) (ffi/load-library "libatomic.so.1"))
(ffi/defcfn jolt-load4 "__atomic_load_4" [:pointer :int] :int32)
(ffi/defcfn jolt-store4 "__atomic_store_4" [:pointer :int32 :int] :void)
(ffi/defcfn jolt-add4 "__atomic_fetch_add_4" [:pointer :int32 :int] :int32)
(ffi/defcfn jolt-xchg4 "__atomic_exchange_4" [:pointer :int32 :int] :int32)
;; (ptr, expected ptr, desired, success order, failure order); the value
;; found is written into expected
(ffi/defcfn jolt-cas4 "__atomic_compare_exchange_4" [:pointer :pointer :int32 :int :int] :bool)
(ffi/defcfn jolt-load8 "__atomic_load_8" [:pointer :int] :int64)
(ffi/defcfn jolt-store8 "__atomic_store_8" [:pointer :int64 :int] :void)
(ffi/defcfn jolt-add8 "__atomic_fetch_add_8" [:pointer :int64 :int] :int64)
(ffi/defcfn jolt-cas8 "__atomic_compare_exchange_8" [:pointer :pointer :int64 :int :int] :bool)
(ffi/defcfn mac-cas4 "OSAtomicCompareAndSwap32Barrier" [:int32 :int32 :pointer] :bool)
(ffi/defcfn mac-add4 "OSAtomicAdd32Barrier" [:int32 :pointer] :int32)
(ffi/defcfn mac-cas8 "OSAtomicCompareAndSwap64Barrier" [:int64 :int64 :pointer] :bool)
(ffi/defcfn mac-add8 "OSAtomicAdd64Barrier" [:int64 :pointer] :int64)
;; open(2) is variadic: the :& marker is what Apple arm64's calling convention needs for its mode
(ffi/defcfn jolt-open "open" [:string :int :& :int] :int {:capture-native-error true})
(ffi/defcfn jolt-close "close" [:int] :int)
(ffi/defcfn jolt-ftruncate "ftruncate" [:int :int64] :int)
(ffi/defcfn jolt-mmap "mmap" [:pointer :size_t :int :int :int :int64] :pointer
  {:capture-native-error true})
;; syscall(SYS-futex, uaddr, op, val, timeout*, uaddr2, val3) — Linux's only (its variadic convention is the fixed one)
(ffi/defcfn jolt-futex "syscall" [:long :pointer :int :int :pointer :pointer :int] :long
  {:blocking true :capture-native-error true})

;; The platform's atomics, each word width's: load, store, fetch-add (the value before), exchange (the value before),
;; compare-and-swap (the value found: expected when it swapped).
(defn- mac-cas
  "A compare-and-swap that answers the value found, over one that answers whether it swapped: a failed swap reads the
   word, and a word read equal to expected (changed and changed back) is tried again."
  [cas add addr expected desired]
  (loop []
    (if (cas expected desired addr)
      expected
      (let [v (add 0 addr)]
        (if (= v expected) (recur) v)))))

(defn- mac-xchg [cas add addr v]
  (loop []
    (let [old (add 0 addr)]
      (if (cas old v addr) old (recur)))))

(def ^:private load4 (load-or-plain 4 (if (= :macos os) (fn [a] (mac-add4 0 a)) (fn [a] (jolt-load4 a SEQ-CST)))
                                      (fn [a] (ffi/read a :int32 0))))
(def ^:private store4 (store-or-plain 4 (if (= :macos os) (fn [a v] (mac-xchg mac-cas4 mac-add4 a v) nil) (fn [a v] (jolt-store4 a v SEQ-CST)))
                                        (fn [a v] (ffi/write a :int32 v 0) nil)))
(def ^:private add4 (if (= :macos os)
                      (fn [a d] (unchecked-int (- (mac-add4 d a) d)))
                      (fn [a d] (jolt-add4 a d SEQ-CST))))
(def ^:private xchg4 (if (= :macos os) (fn [a v] (mac-xchg mac-cas4 mac-add4 a v)) (fn [a v] (jolt-xchg4 a v SEQ-CST))))
(def ^:private load8 (load-or-plain 8 (if (= :macos os) (fn [a] (mac-add8 0 a)) (fn [a] (jolt-load8 a SEQ-CST)))
                                      (fn [a] (ffi/read a :int64 0))))
(def ^:private store8 (store-or-plain 8 (if (= :macos os) (fn [a v] (mac-xchg mac-cas8 mac-add8 a v) nil) (fn [a v] (jolt-store8 a v SEQ-CST)))
                                        (fn [a v] (ffi/write a :int64 v 0) nil)))
(def ^:private add8 (if (= :macos os)
                      (fn [a d] (unchecked-long (- (mac-add8 d a) d)))
                      (fn [a d] (jolt-add8 a d SEQ-CST))))

(defn- jolt-cas! [cas width addr expected desired]
  ;; the witness: libatomic writes the value it saw into expected
  (let [e (ffi/alloc 8)]
    (try
      (ffi/write e width expected)
      (cas addr e desired SEQ-CST SEQ-CST)
      (ffi/read e width)
      (finally (ffi/free e)))))

(def ^:private cas4 (if (= :macos os)
                      (fn [a e d] (mac-cas mac-cas4 mac-add4 a e d))
                      (fn [a e d] (jolt-cas! jolt-cas4 :int32 a e d))))
(def ^:private cas8 (if (= :macos os)
                      (fn [a e d] (mac-cas mac-cas8 mac-add8 a e d))
                      (fn [a e d] (jolt-cas! jolt-cas8 :int64 a e d))))

(defn- futex-wait [addr expected timeout-ms]
  (let [ts (ffi/alloc 16)]
    (try
      (ffi/write ts :int64 (quot timeout-ms 1000) 0)
      (ffi/write ts :int64 (* 1000000 (mod timeout-ms 1000)) 8)
      (let [[r errno] (jolt-futex SYS-futex addr 0 expected ts ffi/null 0)]
        (if (zero? r)
          :ok
          (case (int errno) 11 :not-equal 110 :timed-out :ok)))
      (finally (ffi/free ts)))))

(defn- poll-wait
  "The word at addr watched every 100 µs until it differs from expected (:ok — a wait may end on any change, as a
   futex's may wake spuriously) or timeout-ms pass."
  [addr expected timeout-ms]
  (if (not= expected (load4 addr))
    :not-equal
    (let [deadline (+ (System/nanoTime) (* 1000000 (long timeout-ms)))]
      (loop []
        (cond (not= expected (load4 addr)) :ok
              (>= (System/nanoTime) deadline) :timed-out
              :else (do (Thread/sleep 0 100000) (recur)))))))

(declare ^:private pl-i32 ^:private pl-u8 ^:private pl-i64 ^:private ps-i32! ^:private ps-u8!
         ^:private x86-64-linux? ^:private fast-cas4 ^:private fast-cas8)

(deftype JoltRegion [base size backing]
  mem/IMemRegion
  (-byte-length [_] size)
  (-load-i32 [this off] (if x86-64-linux? (pl-i32 this base off) (load4 (+ base off))))
  (-store-i32! [_ off v] (store4 (+ base off) (unchecked-int v)) nil)
  (-cas-i32! [_ off expected desired]
    (if x86-64-linux?
      (fast-cas4 (+ base off) (unchecked-int expected) (unchecked-int desired))
      (cas4 (+ base off) (unchecked-int expected) (unchecked-int desired))))
  (-add-i32! [_ off delta] (add4 (+ base off) (unchecked-int delta)))
  (-sub-i32! [_ off delta] (add4 (+ base off) (unchecked-int (- delta))))
  (-exchange-i32! [_ off v] (xchg4 (+ base off) (unchecked-int v)))
  (-load-i64 [this off] (if x86-64-linux? (pl-i64 this base off) (load8 (+ base off))))
  (-store-i64! [_ off v] (store8 (+ base off) (long v)) nil)
  (-cas-i64! [_ off expected desired]
    (if x86-64-linux?
      (fast-cas8 (+ base off) (long expected) (long desired))
      (cas8 (+ base off) (long expected) (long desired))))
  (-add-i64! [_ off delta] (add8 (+ base off) (long delta)))
  (-sub-i64! [_ off delta] (add8 (+ base off) (long (- delta))))
  (-wait-i32! [_ off expected timeout-ms]
    (if SYS-futex
      (futex-wait (+ base off) (unchecked-int expected) timeout-ms)
      (poll-wait (+ base off) (unchecked-int expected) timeout-ms)))
  (-notify-i32! [_ off n]
    (if SYS-futex (first (jolt-futex SYS-futex (+ base off) 1 (int n) ffi/null ffi/null 0)) 0))
  (-supports-watch? [_] true)
  (-read-bytes [_ off len] (ffi/read-array (+ base off) len))
  (-write-bytes! [_ off src] (ffi/write-array (+ base off) src) nil)
  mem/IPlainLoad
  (-plain-load-i32 [this off] (pl-i32 this base off))
  (-plain-load-u8 [this off] (pl-u8 this base off))
  mem/IPlainStore
  (-plain-store-i32! [this off v] (ps-i32! this base off v) nil)
  (-plain-store-u8! [this off v] (ps-u8! this base off v) nil))

;; A region's memory goes back when the region does, as a JVM byte array or a Go slice does. A heap region IS Chez heap
;; memory: an immobile bytevector (the collector marks it where it is and never moves it, so its address holds for the
;; libatomic and futex calls), held by the region, counted by jolt's collector as every other byte it holds and
;; reclaimed with the region — so jolt's heap policy (its ceiling: JOLT_MAX_HEAP, else 25% of RAM, the JVM's
;; MaxRAMPercentage) paces and bounds eve's heap domains as the JVM's does. A mapped region is the file's pages outside
;; the heap: Chez's collector hands one it found unreachable to a guardian, and the next region opened unmaps what the
;; guardian holds. Nothing is kept aside or reused. (Before: both were foreign memory, never released — every heap
;; domain a process dropped stayed resident, and bb test:jolt peaked at 5.8 GB; with the guardian over ffi/alloc'd
;; memory, at 4.7 GB, a dropped region waiting for its generation's collection, which the collector schedules without
;; seeing foreign bytes.)
(def ^:private chez
  (scheme/eval-string "(begin (define eve-region-guardian (make-guardian))
           (define eve-region-mutex (make-mutex))
           (define (eve-region-guard! r rep) (with-mutex eve-region-mutex (eve-region-guardian r rep)) r)
           (define (eve-region-unreachable) (with-mutex eve-region-mutex (eve-region-guardian)))
           (define (eve-region-bytes n) (let ((bv (make-immobile-bytevector n))) (bytevector-fill! bv 0) (lock-object bv) bv))
           (define (eve-region-unlock! bv) (unlock-object bv))
           (define eve-region-memset (foreign-procedure \"memset\" (u8* int size_t) uptr))
           (define (eve-region-address bv) (eve-region-memset bv 0 0))
;; foreign-ref / foreign-set! at optimize-level 3 (#3%) where the address and offset are fixnums: the
           ;; open-coded load or store, ~5 ns, where the checked procedure (check-args, the type resolved per call) is
           ;; ~40 ns: 36% of sorted-set-build-200 on jolt (jolt.profile, 2026-11-22 ET). An address or offset that is
           ;; not a fixnum takes the checked procedure, as before.
           (define-syntax eve-region-fx2
             (syntax-rules () ((_ b o fast slow) (if (and (fixnum? b) (fixnum? o)) fast slow))))
           (define (eve-region-pl-i32 r b o)
             (let ((v (eve-region-fx2 b o (#3%foreign-ref 'integer-32 b o) (foreign-ref 'integer-32 b o)))) (keep-live r) v))
           (define (eve-region-pl-i64 r b o)
             (let ((v (eve-region-fx2 b o (#3%foreign-ref 'integer-64 b o) (foreign-ref 'integer-64 b o)))) (keep-live r) v))
           (define (eve-region-pl-u8 r b o)
             (let ((v (eve-region-fx2 b o (#3%foreign-ref 'unsigned-8 b o) (foreign-ref 'unsigned-8 b o)))) (keep-live r) v))
           (define (eve-region-ps-i32! r b o v)
             (let ((w (bitwise-and v #xFFFFFFFF)))
               (eve-region-fx2 b o (#3%foreign-set! 'unsigned-32 b o w) (foreign-set! 'unsigned-32 b o w)))
             (keep-live r))
           (define (eve-region-ps-u8! r b o v)
             (let ((w (bitwise-and v #xFF)))
               (eve-region-fx2 b o (#3%foreign-set! 'unsigned-8 b o w) (foreign-set! 'unsigned-8 b o w)))
             (keep-live r))
           ;; the worker-registry scan (eve.atom/mmap-min-safe-epoch): from slot i, the first slot whose status word is
           ;; active and whose epoch word is not 0, as (slot * 2^32) + (epoch + 2^31); -1 when none. The same two
           ;; aligned seq-cst loads per slot, in the same order, as the loop over -load-i32 (a MOV each on x86-64)
           ;; (#3%foreign-ref when the address is a fixnum, as the plain loads above: every offset here is fixnum
           ;; arithmetic already.)
           (define-syntax eve-region-scan-loop
             (syntax-rules ()
               ((_ ref r b start i0 n stride soff eoff active)
                (let loop ((i i0))
                  (if (fx>= i n)
                      (begin (keep-live r) -1)
                      (let* ((o (fx+ start (fx* i stride)))
                             (st (ref 'integer-32 b (fx+ o soff)))
                             (ep (ref 'integer-32 b (fx+ o eoff))))
                        (if (and (fx= st active) (not (fx= ep 0)))
                            (begin (keep-live r) (+ (* i 4294967296) (+ ep 2147483648)))
                            (loop (fx+ i 1)))))))))
           (define (eve-region-scan-pinned r b start i n stride soff eoff active)
             (if (fixnum? b)
                 (eve-region-scan-loop #3%foreign-ref r b start i n stride soff eoff active)
                 (eve-region-scan-loop foreign-ref r b start i n stride soff eoff active)))
           (define eve-region-libatomic
             (guard (c (#t #f)) (load-shared-object \"libatomic.so.1\") #t))
           (define eve-region-cas4-raw
             (and eve-region-libatomic
                  (foreign-procedure \"__atomic_compare_exchange_4\" (uptr u8* integer-32 int int) boolean)))
           (define eve-region-cas8-raw
             (and eve-region-libatomic
                  (foreign-procedure \"__atomic_compare_exchange_8\" (uptr u8* integer-64 int int) boolean)))
           (define (eve-region-cas4 a e d)
             (let ((w (make-bytevector 4)))
               (bytevector-s32-native-set! w 0 e)
               (eve-region-cas4-raw a w d 5 5)
               (bytevector-s32-native-ref w 0)))
           (define (eve-region-cas8 a e d)
             (let ((w (make-bytevector 8)))
               (bytevector-s64-native-set! w 0 e)
               (eve-region-cas8-raw a w d 5 5)
               (bytevector-s64-native-ref w 0)))
           #t)"))

;; Plain loads and stores of a published (or not yet published) node's words: Chez's foreign-ref / foreign-set!, inline
;; primitives on an address plus offset — no foreign call, no byte-array per word (the IPlainLoad default composes
;; each word from a 4-byte -read-bytes copy). Any alignment: these are plain accesses, which x86-64 and arm64 perform
;; at any address; only the atomics need aligned words. Same bytes, little-endian, as the default's composition.
(def ^:private pl-i32 (scheme/proc "eve-region-pl-i32"))
(def ^:private pl-u8 (scheme/proc "eve-region-pl-u8"))
(def ^:private pl-i64 (scheme/proc "eve-region-pl-i64"))
(def ^:private ps-i32! (scheme/proc "eve-region-ps-i32!"))
(def ^:private ps-u8! (scheme/proc "eve-region-ps-u8!"))

;; Linux x86-64 atomics without a foreign call where none is needed, and without a witness malloc per CAS:
;; - a sequentially consistent LOAD of an aligned word on x86-64 is a plain MOV (the C11 mapping; libatomic's
;;   __atomic_load_4/8 is that MOV behind a call), so -load-i32/-load-i64 read through foreign-ref. Stores, add and
;;   exchange stay libatomic's (a seq-cst store needs the locked/fenced form).
;; - CAS is libatomic's __atomic_compare_exchange_4/8 declared in Scheme with the expected-value witness a fresh
;;   4/8-byte bytevector (Chez allocation; the u8* argument is its data, which the collector cannot move during the
;;   call) instead of jolt.ffi alloc + free around each swap. Same call, same orders (SEQ-CST), same answer: the value
;;   found. Other platforms (macOS, arm64, EVE_JOLT_STRICT_ALIGNMENT) keep the paths above.
(def ^:private x86-64-linux?
  (and (= :linux os) (not strict-alignment?) (scheme/eval-string "eve-region-libatomic")
       (#{"amd64" "x86_64"} (str/lower-case (or (System/getProperty "os.arch") "")))))
(def ^:private fast-cas4 (scheme/proc "eve-region-cas4"))
(def ^:private fast-cas8 (scheme/proc "eve-region-cas8"))

(def ^:private scan-pinned* (scheme/proc "eve-region-scan-pinned"))

(defn scan-pinned-slot
  "jolt's worker-registry scan for eve.atom/mmap-min-safe-epoch: from slot i of n slots (stride bytes apart from byte
   start), the first slot whose i32 at soff is active and whose i32 at eoff is not 0, as [slot epoch]; :none when
   there is none; nil where this region has no fused scan (not a JoltRegion, or not Linux x86-64), for the caller's
   own loop. Same loads, same order, as that loop's -load-i32 pair per slot."
  [r start i n stride soff eoff active]
  (when (and x86-64-linux? (instance? JoltRegion r))
    (let [x (scan-pinned* r (.-base ^JoltRegion r) start i n stride soff eoff active)]
      (if (neg? x)
        :none
        [(quot x 4294967296) (- (rem x 4294967296) 2147483648)]))))

(def ^:private guard! (scheme/proc "eve-region-guard!"))

(def ^:private unreachable (scheme/proc "eve-region-unreachable"))

;; n zeroed bytes in Chez's heap, immobile and locked: Chez 10.4.1 as jolt v0.8.28 ships it moves an immobile
;; bytevector of a few MB in some collections ((collect 0 1 1), which jolt's collector runs near its heap ceiling,
;; among them: probe/immobile in the eve-perf workspace), and a region's base address must hold for the region's
;; life. A locked object is never moved; the region's guardian entry unlocks it once the region is unreachable.
(def ^:private heap-bytes (scheme/proc "eve-region-bytes"))

(def ^:private unlock-bytes! (scheme/proc "eve-region-unlock!"))

;; a bytevector's data address (a u8* argument is passed as it; memset of 0 bytes answers it)
(def ^:private bytes-address (scheme/proc "eve-region-address"))

(ffi/defcfn jolt-munmap "munmap" [:pointer :size_t] :int)

(defn- unmap-unreachable!
  "Every region the collector has found unreachable since the last call, released: a mapped one unmapped, a heap
   one's bytevector unlocked (the collector reclaims it then)."
  []
  (loop []
    (when-let [rep (unreachable)]
      (if (vector? rep)
        (let [[p n] rep] (jolt-munmap p n))
        (unlock-bytes! rep))
      (recur))))

(defn open-mmap-region
  "Open (or create) a file-backed MAP_SHARED region of size-bytes at
   path-str. The file grows to size-bytes when it is shorter and is never
   truncated."
  ([path-str size-bytes] (open-mmap-region path-str size-bytes nil))
  ([path-str size-bytes opts]
    (when (:lustre? opts)
      (throw (UnsupportedOperationException. "eve on jolt: no lustre regions")))
    ;; open(2) resolves a relative path against the process's cwd, which jolt's launcher moved to jolt's own root;
    ;; java.io.File resolves it against the user's (JOLT_PWD)
    (let [[fd e1] (jolt-open (.getAbsolutePath (java.io.File. ^String path-str)) (bit-or 2 O-CREAT) 420)  ; O_RDWR | O_CREAT
          _ (when (neg? fd)
              (throw (ex-info (str "open " path-str ": errno " e1)
                              {:path path-str})))
          _ (when (< (.length (java.io.File. ^String path-str)) size-bytes)
              (jolt-ftruncate fd size-bytes))
          [p e2] (jolt-mmap ffi/null size-bytes 3 1 fd 0)]  ; PROT_READ|WRITE, MAP_SHARED
      (jolt-close fd)
      (when (or (nil? p) (= p 18446744073709551615))  ; MAP_FAILED
        (throw (ex-info (str "mmap " path-str ": errno " e2) {:path path-str})))
      (unmap-unreachable!)
      (guard! (JoltRegion. p size-bytes nil) [p size-bytes]))))

(defn make-heap-region
  "An IMemRegion over size-bytes of zeroed memory, shared by the process's threads: a locked immobile bytevector in
   Chez's heap, reclaimed with the region."
  [size-bytes]
  (unmap-unreachable!)
  (let [bv (heap-bytes size-bytes)]
    (guard! (JoltRegion. (bytes-address bv) size-bytes bv) bv)))

(defn lustre-region?
  "true for a region whose atomics are fcntl byte-range locks (a Lustre mount)."
  [_r]
  false)
