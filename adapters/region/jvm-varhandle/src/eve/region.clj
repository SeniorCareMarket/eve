(ns eve.region
  "eve's regions (eve.mem's IMemRegion and IHostAtomics; HOST-CONTRACT §1-2)
   over the JVM's java.nio: one region over ByteBuffers (a heap byte[] in
   process; a table of MappedByteBuffer segment windows across processes —
   see the segmentation note below), its atomics VarHandle view handles,
   wait/notify LockSupport park/unpark; and a Lustre mount's fcntl locks.

   make-heap-region and open-mmap-region open them; lustre-region? says
   whether a region's atomics are fcntl byte-range locks (a Lustre mount)."
  (:refer-clojure :exclude [aget aset alength byte-array int-array long-array object-array
                            into-array unchecked-byte unchecked-int unchecked-long
                            unchecked-inc-int unsigned-bit-shift-right long double class
                            locking transient persistent! conj! assoc! bytes objects
                            const-bytes const-objects])
  (:require
   [eve.fsync :as fsync]
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
;; heap regions wrap a byte[] (ByteBuffer/wrap), mmap regions use
;; MappedByteBuffers (FileChannel.map) — accessed through
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

;; The atomics' exact-typed form (REPORT.md 3.6 #4): the spread handles above box every int/long and build an Object[]
;; per call (dsrt-alloc: 20-25% of a ds run's allocation). VhOps is the same ten access modes with primitive
;; signatures; vh-ops implements it in a class generated once here with clojure.asm (no Java build step), each method
;; one invokevirtual of a static final VarHandle at its exact type -- what a Java call site compiles to, intrinsified
;; by the JIT. Where the class cannot be made, vh-ops answers through the spread handles: the same results.
(definterface VhOps
  (^int getI32 [^java.nio.ByteBuffer b ^int off])
  (^void setI32 [^java.nio.ByteBuffer b ^int off ^int v])
  (^int casI32 [^java.nio.ByteBuffer b ^int off ^int expected ^int desired])
  (^int addI32 [^java.nio.ByteBuffer b ^int off ^int delta])
  (^int xchgI32 [^java.nio.ByteBuffer b ^int off ^int v])
  (^long getI64 [^java.nio.ByteBuffer b ^int off])
  (^void setI64 [^java.nio.ByteBuffer b ^int off ^long v])
  (^long casI64 [^java.nio.ByteBuffer b ^int off ^long expected ^long desired])
  (^long addI64 [^java.nio.ByteBuffer b ^int off ^long delta]))

(defn- gen-vh-ops
  "A VhOps over static final VarHandles, its class generated with clojure.asm."
  ^VhOps []
  (let [cname "eve/region/VhOpsExact"
        vh "java/lang/invoke/VarHandle" vhd "Ljava/lang/invoke/VarHandle;"
        cw (clojure.asm.ClassWriter. clojure.asm.ClassWriter/COMPUTE_FRAMES)]
    (.visit cw clojure.asm.Opcodes/V1_8 (bit-or clojure.asm.Opcodes/ACC_PUBLIC clojure.asm.Opcodes/ACC_FINAL)
            cname nil "java/lang/Object" (into-array String [(.replace (.getName VhOps) \. \/)]))
    (doseq [f ["I32" "I64"]]
      (.visitEnd (.visitField cw (bit-or clojure.asm.Opcodes/ACC_STATIC clojure.asm.Opcodes/ACC_FINAL
                                         clojure.asm.Opcodes/ACC_PRIVATE) f vhd nil nil)))
    (let [mv (.visitMethod cw clojure.asm.Opcodes/ACC_STATIC "<clinit>" "()V" nil nil)]
      (.visitCode mv)
      (doseq [[f arr] [["I32" "[I"] ["I64" "[J"]]]
        (.visitLdcInsn mv (clojure.asm.Type/getType ^String arr))
        (.visitFieldInsn mv clojure.asm.Opcodes/GETSTATIC "java/nio/ByteOrder" "LITTLE_ENDIAN" "Ljava/nio/ByteOrder;")
        (.visitMethodInsn mv clojure.asm.Opcodes/INVOKESTATIC "java/lang/invoke/MethodHandles" "byteBufferViewVarHandle"
                          (str "(Ljava/lang/Class;Ljava/nio/ByteOrder;)" vhd) false)
        (.visitFieldInsn mv clojure.asm.Opcodes/PUTSTATIC cname f vhd))
      (.visitInsn mv clojure.asm.Opcodes/RETURN)
      (.visitMaxs mv 0 0) (.visitEnd mv))
    (let [mv (.visitMethod cw clojure.asm.Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)]
      (.visitCode mv)
      (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 0)
      (.visitMethodInsn mv clojure.asm.Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V" false)
      (.visitInsn mv clojure.asm.Opcodes/RETURN)
      (.visitMaxs mv 0 0) (.visitEnd mv))
    ;; [method field VarHandle-method value-args(I|J) return(I|J|V)]
    (doseq [[m f op args ret] [["getI32" "I32" "getVolatile" "" "I"] ["setI32" "I32" "setVolatile" "I" "V"]
                               ["casI32" "I32" "compareAndExchange" "II" "I"] ["addI32" "I32" "getAndAdd" "I" "I"]
                               ["xchgI32" "I32" "getAndSet" "I" "I"] ["getI64" "I64" "getVolatile" "" "J"]
                               ["setI64" "I64" "setVolatile" "J" "V"] ["casI64" "I64" "compareAndExchange" "JJ" "J"]
                               ["addI64" "I64" "getAndAdd" "J" "J"]]]
      (let [d (str "(Ljava/nio/ByteBuffer;I" args ")" ret)
            mv (.visitMethod cw clojure.asm.Opcodes/ACC_PUBLIC m d nil nil)]
        (.visitCode mv)
        (.visitFieldInsn mv clojure.asm.Opcodes/GETSTATIC cname f vhd)
        (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 1)
        (.visitVarInsn mv clojure.asm.Opcodes/ILOAD 2)
        (reduce (fn [slot c] (if (= c \J)
                               (do (.visitVarInsn mv clojure.asm.Opcodes/LLOAD (int slot)) (+ slot 2))
                               (do (.visitVarInsn mv clojure.asm.Opcodes/ILOAD (int slot)) (inc slot))))
                3 args)
        (.visitMethodInsn mv clojure.asm.Opcodes/INVOKEVIRTUAL vh op d false)
        (.visitInsn mv (case ret "I" clojure.asm.Opcodes/IRETURN "J" clojure.asm.Opcodes/LRETURN
                             "V" clojure.asm.Opcodes/RETURN))
        (.visitMaxs mv 0 0) (.visitEnd mv)))
    (.visitEnd cw)
    (let [^Class c (.defineClass ^clojure.lang.DynamicClassLoader (clojure.lang.RT/makeClassLoader)
                                 "eve.region.VhOpsExact" (.toByteArray cw) nil)]
      (.newInstance (.getConstructor c (make-array Class 0)) (make-array Object 0)))))

(defn- spread-vh-ops
  "VhOps through the spread handles: gen-vh-ops's fallback."
  ^VhOps []
  (reify VhOps
    (getI32 [_ b off] (int (.invoke I32-GET (args2 b off))))
    (setI32 [_ b off v] (.invoke I32-SET (args3 b off v)) nil)
    (casI32 [_ b off e d] (int (.invoke I32-CAS (args4 b off e d))))
    (addI32 [_ b off d] (int (.invoke I32-ADD (args3 b off d))))
    (xchgI32 [_ b off v] (int (.invoke I32-XCHG (args3 b off v))))
    (getI64 [_ b off] (long (.invoke I64-GET (args2 b off))))
    (setI64 [_ b off v] (.invoke I64-SET (args3 b off v)) nil)
    (casI64 [_ b off e d] (long (.invoke I64-CAS (args4 b off e d))))
    (addI64 [_ b off d] (long (.invoke I64-ADD (args3 b off d))))))

(def ^:private ^VhOps vh-ops
  (or (try (let [^VhOps o (gen-vh-ops)
                 b (ByteBuffer/allocateDirect 16)]
             (.setI64 o b 8 -2)
             (when (and (== -2 (.getI64 o b 8)) (== 0 (.casI32 o b 0 0 5)) (== 5 (.getI32 o b 0))) o))
           (catch Throwable _ nil))
      (spread-vh-ops)))

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
    (.getI32 vh-ops buf (int off))
    (i32-get-unaligned buf off)))

(defn- bb-store-i32!
  [^ByteBuffer buf off ^long v]
  (if (zero? (bit-and off 3))
    (.setI32 vh-ops buf (int off) (unchecked-int v))
    (i32-set-unaligned! buf off v)))

(defn- bb-load-i64
  [^ByteBuffer buf off]
  (if (zero? (bit-and off 7))
    (.getI64 vh-ops buf (int off))
    (i64-get-unaligned buf off)))

(defn- bb-store-i64!
  [^ByteBuffer buf off ^long v]
  (if (zero? (bit-and off 7))
    (.setI64 vh-ops buf (int off) v)
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

;; ------------------------------------------------------------------
;; Segmentation — a file-backed region is a table of mappings, each
;; covering a fixed power-of-two window of the region's byte space.
;; FileChannel.map refuses any single mapping past Integer.MAX_VALUE,
;; so one whole-file mapping walls a slab file at 2^31 bytes; a table
;; of windows has no such ceiling (design of record:
;; docs/eve-region-segmentation/REPORT.md).  The segment size is an
;; open-time parameter (:segment-bytes, default 256 MiB), stored
;; nowhere in the files — presentation, not format: two processes may
;; open the same domain with different segment sizes.  Every op
;; decomposes its absolute offset (segment = off >>> shift,
;; intra = off & mask) inside the region; the IMemRegion surface
;; above sees one flat byte space, exactly as before.  Aligned atomic
;; ops (<= 8 B; the segment size is a multiple of 4096) can never
;; straddle a boundary; the unaligned loads/stores, the plain loads
;; and the bulk paths split at the boundary and compose.
;;
;; The segment table is fixed geometry derived from the region's
;; published size — no per-segment state, nothing tracked, nothing
;; counted (the design's memory-law pre-flight).
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
  ^ByteBuffer [^objects segments ^long shift ^long off]
  (aget segments (int (unsigned-bit-shift-right off shift))))

(defn- seg-intra
  "The offset of absolute offset off within its segment."
  ^long [^long shift ^long off]
  (bit-and off (dec (bit-shift-left 1 shift))))

(defn- seg-byte
  "One byte of a segmented region at absolute offset off, unsigned."
  [^objects segments ^long shift ^long off]
  (bit-and (.get (seg-buf segments shift off) (int (seg-intra shift off))) 0xFF))

(defn- seg-put-byte!
  [^objects segments ^long shift ^long off ^long v]
  (.put (seg-buf segments shift off) (int (seg-intra shift off)) (unchecked-byte v))
  nil)

(defn- seg-compose-i32
  "An i32 at absolute offset off, composed byte by byte — the form an
   unaligned access that straddles a segment boundary takes."
  [^objects segments ^long shift ^long off]
  (unchecked-int
   (bit-or (seg-byte segments shift off)
           (bit-shift-left (seg-byte segments shift (+ off 1)) 8)
           (bit-shift-left (seg-byte segments shift (+ off 2)) 16)
           (bit-shift-left (seg-byte segments shift (+ off 3)) 24))))

(defn- seg-load-i32
  [^objects segments ^long shift ^long off]
  (let [intra (seg-intra shift off)]
    (if (and (not (zero? (bit-and off 3)))
             (> (+ intra 4) (bit-shift-left 1 shift)))
      (seg-compose-i32 segments shift off)
      (bb-load-i32 (seg-buf segments shift off) (int intra)))))

(defn- seg-store-i32!
  [^objects segments ^long shift ^long off ^long v]
  (let [intra (seg-intra shift off)]
    (if (and (not (zero? (bit-and off 3)))
             (> (+ intra 4) (bit-shift-left 1 shift)))
      (do (seg-put-byte! segments shift off v)
          (seg-put-byte! segments shift (+ off 1) (unsigned-bit-shift-right v 8))
          (seg-put-byte! segments shift (+ off 2) (unsigned-bit-shift-right v 16))
          (seg-put-byte! segments shift (+ off 3) (unsigned-bit-shift-right v 24)))
      (bb-store-i32! (seg-buf segments shift off) (int intra) v))))

(defn- seg-load-i64
  [^objects segments ^long shift ^long off]
  (let [intra (seg-intra shift off)]
    (cond
      (zero? (bit-and off 7))
      (bb-load-i64 (seg-buf segments shift off) (int intra))

      (<= (+ intra 8) (bit-shift-left 1 shift))
      (i64-get-unaligned (seg-buf segments shift off) intra)

      :else
      (let [lo (seg-load-i32 segments shift off)
            hi (seg-load-i32 segments shift (+ off 4))]
        (bit-or (bit-shift-left (long hi) 32)
                (bit-and (long lo) 0xFFFFFFFF))))))

(defn- seg-store-i64!
  [^objects segments ^long shift ^long off ^long v]
  (let [intra (seg-intra shift off)]
    (if (and (not (zero? (bit-and off 7)))
             (> (+ intra 8) (bit-shift-left 1 shift)))
      (do (seg-store-i32! segments shift off (unchecked-int v))
          (seg-store-i32! segments shift (+ off 4)
                          (unchecked-int (unsigned-bit-shift-right v 32))))
      (bb-store-i64! (seg-buf segments shift off) (int intra) v))))

(defn- seg-plain-load-i32
  "The IPlainLoad i32: byte-composed like i32-get-unaligned, carried
   across a segment boundary when the access straddles one."
  [^objects segments ^long shift ^long off]
  (let [intra (seg-intra shift off)]
    (if (<= (+ intra 4) (bit-shift-left 1 shift))
      (i32-get-unaligned (seg-buf segments shift off) intra)
      (seg-compose-i32 segments shift off))))

(defn- seg-read-bytes
  ^bytes [^objects segments ^long shift ^long off ^long len]
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

(defn- seg-force!
  "Force every mapped segment (heap buffers have nothing to force)."
  [^objects segments]
  (dotimes [k (alength segments)]
    (let [b (aget segments k)]
      (when (instance? MappedByteBuffer b)
        (.force ^MappedByteBuffer b))))
  nil)

(defn- seg-force-range!
  "Force the parts of the segments intersecting [off, off+len)."
  [^objects segments ^long shift ^long off ^long len]
  (when (pos? len)
    (loop [k (unsigned-bit-shift-right off shift)]
      (let [seg-start (bit-shift-left k shift)]
        (when (< seg-start (+ off len))
          (let [b (aget segments (int k))]
            (when (instance? MappedByteBuffer b)
              (let [i0 (max off seg-start)
                    i1 (min (+ off len)
                            (+ seg-start (long (.capacity ^ByteBuffer b))))]
                (.force ^MappedByteBuffer b
                        (int (- i0 seg-start))
                        (int (- i1 i0))))))
          (recur (inc k))))))
  nil)

(defn- map-segments
  "Map fc's file over [0, size) in windows of 2^shift bytes.  When
   old-segments (a previous region's table for the same file, opened
   at the same shift) has a window whose length is unchanged, that
   window's buffer is reused — a growth or refresh re-map maps only
   the delta (design §4.4)."
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
              (if (and old (== (long (.capacity ^ByteBuffer old)) len))
                old
                (.map fc FileChannel$MapMode/READ_WRITE start len)))))
    arr))

;; Forward declaration — open-lustre-region is defined below but
;; referenced in open-mmap-region.
(declare open-lustre-region)

;; -----------------------------------------------------------------------
;; JvmByteBufferRegion — unified heap/mmap region (no Unsafe)
;; -----------------------------------------------------------------------
;; One IMemRegion implementation for both backings.  The backing is a
;; segment table of ByteBuffers (see the segmentation note above):
;;   heap: one segment, the ByteBuffer/wrap of a byte[] (make-heap-region)
;;   mmap: one MappedByteBuffer per :segment-bytes window of the file
;;         (open-mmap-region)
;; All atomics go through the VarHandle view method handles above
;; (volatile get/set, compareAndExchange, getAndAdd, getAndSet) with
;; explicit little-endian order.  Wait/notify is real blocking via
;; LockSupport.park/unpark, so -supports-watch? is true.
(deftype JvmByteBufferRegion [^objects segments ^long size ^int shift]
  mem/IMemRegion
  (-byte-length [_] size)

  ;; i32/i64 loads and stores tolerate unaligned offsets (DataView-style
  ;; fallback) because HAMT node interiors pack [len:i32][bytes] tightly.
  ;; Atomic RMW ops require alignment and throw otherwise.

  (-load-i32 [_ byte-off]
    ;; Common path inlined to pristine call depth (method -> bb-load-i32
    ;; -> spread handle): the segment decomposition is a shift, a mask
    ;; and an aget, done here rather than through the seg-load-i32
    ;; helper — the extra IFn layer measurably broke JIT inlining of
    ;; the leaf access in deep descents (3-7x on the DS bench read
    ;; rows, A/B vs pristine, Nov 8).  The helper remains for the
    ;; straddle case and cold paths.
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 4) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))]
          (bb-load-i32 buf (int intra)))
        (seg-load-i32 segments shift off))))

  (-store-i32! [_ byte-off val]
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 4) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))]
          (bb-store-i32! buf (int intra) (long val)))
        (seg-store-i32! segments shift off (long val))))
    nil)

  ;; compareAndExchange returns the witness (previous) value — exactly
  ;; the protocol's "returns the old value" contract.
  (-cas-i32! [_ byte-off expected desired]
    (let [off (long byte-off)]
      (when-not (zero? (bit-and off 3))
        (unaligned-atomic-ex :cas-i32 byte-off 4))
      (int (.casI32 vh-ops (seg-buf segments shift off)
                           (int (seg-intra shift off))
                           (unchecked-int expected)
                           (unchecked-int desired)))))

  ;; getAndAdd returns the value BEFORE the add.
  (-add-i32! [_ byte-off delta]
    (let [off (long byte-off)]
      (when-not (zero? (bit-and off 3))
        (unaligned-atomic-ex :add-i32 byte-off 4))
      (int (.addI32 vh-ops (seg-buf segments shift off)
                           (int (seg-intra shift off))
                           (unchecked-int delta)))))

  (-sub-i32! [_ byte-off delta]
    (let [off (long byte-off)]
      (when-not (zero? (bit-and off 3))
        (unaligned-atomic-ex :sub-i32 byte-off 4))
      (int (.addI32 vh-ops (seg-buf segments shift off)
                           (int (seg-intra shift off))
                           (unchecked-int (- delta))))))

  (-exchange-i32! [_ byte-off val]
    (let [off (long byte-off)]
      (when-not (zero? (bit-and off 3))
        (unaligned-atomic-ex :exchange-i32 byte-off 4))
      (int (.xchgI32 vh-ops (seg-buf segments shift off)
                            (int (seg-intra shift off))
                            (unchecked-int val)))))

  (-load-i64 [_ byte-off]
    ;; Inlined common path — see -load-i32.
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 8) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))]
          (bb-load-i64 buf (int intra)))
        (seg-load-i64 segments shift off))))

  (-store-i64! [_ byte-off val]
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 8) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))]
          (bb-store-i64! buf (int intra) (long val)))
        (seg-store-i64! segments shift off (long val))))
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (let [off (long byte-off)]
      (when-not (zero? (bit-and off 7))
        (unaligned-atomic-ex :cas-i64 byte-off 8))
      (long (.casI64 vh-ops (seg-buf segments shift off)
                            (int (seg-intra shift off))
                            (long expected)
                            (long desired)))))

  (-add-i64! [_ byte-off delta]
    (let [off (long byte-off)]
      (when-not (zero? (bit-and off 7))
        (unaligned-atomic-ex :add-i64 byte-off 8))
      (long (.addI64 vh-ops (seg-buf segments shift off)
                            (int (seg-intra shift off))
                            (long delta)))))

  (-sub-i64! [_ byte-off delta]
    (let [off (long byte-off)]
      (when-not (zero? (bit-and off 7))
        (unaligned-atomic-ex :sub-i64 byte-off 8))
      (long (.addI64 vh-ops (seg-buf segments shift off)
                            (int (seg-intra shift off))
                            (long (- delta))))))

  (-wait-i32! [this byte-off expected timeout-ms]
    (let [exp      (int expected)
          off      (long byte-off)
          load-now (fn [] (seg-load-i32 segments shift off))
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
    ;; Absolute bulk get — does not touch any buffer's position.
    ;; Common path inlined to pristine shape (one bulk get on the
    ;; covering segment); the seg-read-bytes loop serves straddles.
    (let [off   (long byte-off)
          len   (long len)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra len) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))
              dst (byte-array (int len))]
          (.get buf (int intra) dst 0 (int len))
          dst)
        (seg-read-bytes segments shift off len))))

  (-write-bytes! [_ byte-off src]
    ;; Absolute bulk put — immediately visible to other processes
    ;; sharing the same MAP_SHARED mappings.  Inlined common path as
    ;; in -read-bytes.
    (let [off   (long byte-off)
          len   (alength ^bytes src)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra len) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))]
          (.put buf (int intra) ^bytes src 0 (int len)))
        (seg-write-bytes! segments shift off src)))
    nil)

  ;; --- the hot read path's plain loads (eve.mem/IHeapBytes): a heap
  ;; region's own byte[] (make-heap-region wraps one at offset 0, its
  ;; single segment); a mapped region has none and answers nil ---
  mem/IHeapBytes
  (-heap-bytes [_]
    (when (== 1 (alength segments))
      (let [^ByteBuffer buf (aget segments 0)]
        (when (.hasArray buf)
          (when (zero? (.arrayOffset buf))
            (.array buf))))))

  ;; --- plain loads of published-node bytes (eve.mem/IPlainLoad):
  ;; absolute ByteBuffer gets — plain memory loads on heap and mapped
  ;; buffers alike, no VarHandle, no fence, no allocation.  Published
  ;; nodes only. ---
  mem/IPlainLoad
  (-plain-load-i32 [_ byte-off]
    ;; Inlined common path — pristine depth is method -> i32-get-unaligned.
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 4) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))]
          (i32-get-unaligned buf (int intra)))
        (seg-plain-load-i32 segments shift off))))
  (-plain-load-u8 [_ byte-off]
    (let [off (long byte-off)
          ^ByteBuffer buf (aget segments
                                (int (unsigned-bit-shift-right off shift)))]
      (bit-and (.get buf (int (bit-and off (dec (bit-shift-left 1 shift)))))
               0xFF)))

  ;; --- plain stores into an unpublished block (eve.mem/IPlainStore):
  ;; absolute ByteBuffer puts, no allocation; a straddling i32 byte by
  ;; byte, as seg-store-i32! does ---
  mem/IPlainStore
  (-plain-store-i32! [_ byte-off v]
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 4) (bit-shift-left 1 shift))
        (i32-set-unaligned! (aget segments (int (unsigned-bit-shift-right off shift))) (int intra) (long v))
        (do (seg-put-byte! segments shift off v)
            (seg-put-byte! segments shift (+ off 1) (unsigned-bit-shift-right (long v) 8))
            (seg-put-byte! segments shift (+ off 2) (unsigned-bit-shift-right (long v) 16))
            (seg-put-byte! segments shift (+ off 3) (unsigned-bit-shift-right (long v) 24))))
      nil))
  (-plain-store-u8! [_ byte-off v]
    (let [off (long byte-off)
          ^ByteBuffer buf (aget segments (int (unsigned-bit-shift-right off shift)))]
      (.put buf (int (bit-and off (dec (bit-shift-left 1 shift)))) (unchecked-byte (long v)))
      nil))

  ;; --- durability (eve.mem/ISyncableRegion): mapped segments force;
  ;; a heap region (make-heap-region) has nothing to force ---
  mem/ISyncableRegion
  (-sync! [_]
    (seg-force! segments)
    nil)

  (-sync-range! [_ byte-off len]
    (seg-force-range! segments shift (long byte-off) (long len))
    nil))

(defn open-mmap-region
  "Open (or create) a file-backed shared memory region at path-str.
   If the file does not exist it is created.  If the file is smaller than
   size-bytes it is grown via RandomAccessFile.setLength.
   Returns a JvmByteBufferRegion backed by MAP_SHARED segment mappings.

   opts:
     :lustre?       — an fcntl-locked region (open-lustre-region)
     :segment-bytes — the mapping window: a power of two, 4096..2^30,
                      default 256 MiB.  Presentation, not format: it
                      is stored nowhere in the file, and two processes
                      may open the same file with different windows.
     :reuse-from    — a region previously opened on this file at the
                      same segment size: its unchanged windows are
                      reused, so a growth or refresh re-map maps only
                      the delta.

   Atomics go through VarHandle view handles (no sun.misc.Unsafe);
   bulk I/O through absolute ByteBuffer bulk ops.

   Example:
     (mem/open-mmap-region \"/tmp/eve.main\" (* 256 1024 1024))"
  ([path-str size-bytes] (open-mmap-region path-str size-bytes nil))
  ([path-str size-bytes opts]
   (if (:lustre? opts)
     (open-lustre-region path-str size-bytes opts)
     (let [size  (long size-bytes)
           shift (segment-shift (or (:segment-bytes opts) DEFAULT-SEGMENT-BYTES))
           ;; Ensure the file exists and is at least size-bytes long.
           ;; Only grow the file — never truncate an existing larger file
           ;; (e.g. a 1 MB domain file peeked at 4096 bytes must not be truncated).
           _     (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
                   (try (when (< (.length raf) size) (.setLength raf size))
                        (finally (.close raf))))
           ;; Map the file in segment windows.  The mappings survive
           ;; channel close, so the channel is closed immediately; the
           ;; MappedByteBuffers stay valid for the region's lifetime.
           path  (Paths/get ^String path-str (into-array String []))
           reuse (:reuse-from opts)
           old-segments (when (instance? JvmByteBufferRegion reuse)
                          (.segments ^JvmByteBufferRegion reuse))
           old-shift    (if (instance? JvmByteBufferRegion reuse)
                          (long (.shift ^JvmByteBufferRegion reuse))
                          -1)]
       (with-open [^FileChannel fc
                   (FileChannel/open path
                     (into-array OpenOption
                       [StandardOpenOption/READ
                        StandardOpenOption/WRITE]))]
         (JvmByteBufferRegion. (map-segments fc size shift old-segments old-shift)
                               size
                               (int shift)))))))

;; -----------------------------------------------------------------------
;; Heap regions — same JvmByteBufferRegion over a heap byte[]
;; -----------------------------------------------------------------------

;; Which buffer a heap region wraps is the JDK's answer, read once: a
;; byteBufferViewVarHandle takes atomic access modes (getVolatile,
;; compareAndExchange, getAndAdd, ...) on a heap ByteBuffer through JDK 21,
;; and refuses every one of them on JDK 25 with IllegalStateException
;; "Atomic access not supported for heap buffer" (measured this box
;; 2026-11-09: OpenJDK 21.0.12.1 accepts all five modes Eve uses on a
;; wrapped byte[]; GraalVM JDK 25.0.4 refuses all five; a direct buffer
;; takes all five on both). Every region op here is one of those modes, so
;; on such a JDK the heap region's segment is a direct buffer —
;; zero-filled native memory, the JDK's supported atomic target — and on
;; a JDK that still takes heap atomics it stays the wrapped byte[] (the
;; IHeapBytes fast path's array).
(def ^:private heap-buffer-atomics?
  (try (.invoke I32-GET (args2 (ByteBuffer/wrap (byte-array 8)) (int 0)))
       true
       (catch IllegalStateException _ false)))

(defn make-heap-region
  "Create an IMemRegion backed by zero-initialized process memory: a heap
   byte array where this JDK takes VarHandle atomics on a heap buffer, a
   direct (native) buffer where it refuses them (heap-buffer-atomics?).
   Thread-safe: all i32/i64 ops use VarHandle volatile/atomic access
   with full cross-thread visibility (the same guarantee a
   SharedArrayBuffer gives JS workers).  Unlike open-mmap-region, this
   is not file-backed — data lives only in this process and is not
   visible to other processes."
  [size-bytes]
  (JvmByteBufferRegion. (object-array [(if heap-buffer-atomics?
                                         (ByteBuffer/wrap (byte-array (int size-bytes)))
                                         (ByteBuffer/allocateDirect (int size-bytes)))])
                        (long size-bytes)
                        (int 31)))

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
;; sun.misc.Unsafe); the segment mappings are shared with open-mmap-region.

(deftype LustreJvmMmapRegion
  [^objects segments
   ^long size
   ^int shift
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
          (seg-load-i32 segments shift (long byte-off))
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-store-i32! [_ byte-off val]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [off (long byte-off)]
            (seg-store-i32! segments shift off (long val))
            (seg-force-range! segments shift off 4))
          (finally (.release fl))))
      (finally (.unlock thread-lock)))
    nil)

  (-cas-i32! [_ byte-off expected desired]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [cur (seg-load-i32 segments shift (long byte-off))]
            (when (= cur (unchecked-int expected))
              (let [off (long byte-off)]
                (seg-store-i32! segments shift off (long desired))
                (seg-force-range! segments shift off 4)))
            cur)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-add-i32! [_ byte-off delta]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [old (seg-load-i32 segments shift (long byte-off))
                nv  (unchecked-int (+ old (unchecked-int delta)))]
            (seg-store-i32! segments shift (long byte-off) (long nv))
            (seg-force-range! segments shift (long byte-off) 4)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-sub-i32! [_ byte-off delta]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [old (seg-load-i32 segments shift (long byte-off))
                nv  (unchecked-int (- old (unchecked-int delta)))]
            (seg-store-i32! segments shift (long byte-off) (long nv))
            (seg-force-range! segments shift (long byte-off) 4)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-exchange-i32! [_ byte-off val]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [old (seg-load-i32 segments shift (long byte-off))]
            (seg-store-i32! segments shift (long byte-off) (long val))
            (seg-force-range! segments shift (long byte-off) 4)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  ;; --- atomic i64 via fcntl ---

  (-load-i64 [_ byte-off]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (seg-load-i64 segments shift (long byte-off))
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-store-i64! [_ byte-off val]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (let [off (long byte-off)]
            (seg-store-i64! segments shift off (long val))
            (seg-force-range! segments shift off 8))
          (finally (.release fl))))
      (finally (.unlock thread-lock)))
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (let [cur (seg-load-i64 segments shift (long byte-off))]
            (when (= cur (long expected))
              (let [off (long byte-off)]
                (seg-store-i64! segments shift off (long desired))
                (seg-force-range! segments shift off 8)))
            cur)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-add-i64! [_ byte-off delta]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (let [old (seg-load-i64 segments shift (long byte-off))
                nv  (+ old (long delta))]
            (seg-store-i64! segments shift (long byte-off) (long nv))
            (seg-force-range! segments shift (long byte-off) 8)
            old)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  (-sub-i64! [_ byte-off delta]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 8 false)]
        (try
          (let [old (seg-load-i64 segments shift (long byte-off))
                nv  (- old (long delta))]
            (seg-store-i64! segments shift (long byte-off) (long nv))
            (seg-force-range! segments shift (long byte-off) 8)
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

  ;; --- byte I/O — absolute bulk ops across segments ---

  (-read-bytes [_ byte-off len]
    ;; Inlined common path — see JvmByteBufferRegion -read-bytes.
    (let [off   (long byte-off)
          len   (long len)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra len) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
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
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))]
          (.put buf (int intra) ^bytes src 0 (int len)))
        (seg-write-bytes! segments shift off src)))
    nil)

  ;; --- plain loads of published-node bytes (eve.mem/IPlainLoad):
  ;; absolute gets across segments, WITHOUT the fcntl lock — a
  ;; published node's bytes never change, so there is nothing to
  ;; coordinate. ---
  mem/IPlainLoad
  (-plain-load-i32 [_ byte-off]
    ;; Inlined common path — see JvmByteBufferRegion -load-i32.
    (let [off   (long byte-off)
          intra (bit-and off (dec (bit-shift-left 1 shift)))]
      (if (<= (+ intra 4) (bit-shift-left 1 shift))
        (let [^ByteBuffer buf (aget segments
                                    (int (unsigned-bit-shift-right off shift)))]
          (i32-get-unaligned buf (int intra)))
        (seg-plain-load-i32 segments shift off))))
  (-plain-load-u8 [_ byte-off]
    (let [off (long byte-off)
          ^ByteBuffer buf (aget segments
                                (int (unsigned-bit-shift-right off shift)))]
      (bit-and (.get buf (int (bit-and off (dec (bit-shift-left 1 shift)))))
               0xFF)))

  ;; --- durability (eve.mem/ISyncableRegion): the mapped segments
  ;; force ---
  mem/ISyncableRegion
  (-sync! [_]
    (seg-force! segments)
    nil)

  (-sync-range! [_ byte-off len]
    (seg-force-range! segments shift (long byte-off) (long len))
    nil))

(defn open-lustre-region
  "Open (or create) a file-backed Lustre-compatible memory region at path-str.
   Like open-mmap-region but keeps a FileChannel open for fcntl byte-range
   locking. Uses a ReentrantLock for intra-JVM thread serialization.
   Accepts open-mmap-region's :segment-bytes and :reuse-from opts.
   Returns a LustreJvmMmapRegion."
  [path-str size-bytes opts]
  (let [size  (long size-bytes)
        shift (segment-shift (or (:segment-bytes opts) DEFAULT-SEGMENT-BYTES))
        _     (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
                (try (when (< (.length raf) size) (.setLength raf size))
                     (finally (.close raf))))
        path  (Paths/get ^String path-str (into-array String []))
        ;; Map in segment windows for atomic access and scoped
        ;; force(index, length).
        ^FileChannel map-ch (FileChannel/open path
                              (into-array OpenOption
                                [StandardOpenOption/READ
                                 StandardOpenOption/WRITE]))
        reuse (:reuse-from opts)
        old-segments (when (instance? LustreJvmMmapRegion reuse)
                       (.segments ^LustreJvmMmapRegion reuse))
        old-shift    (if (instance? LustreJvmMmapRegion reuse)
                       (long (.shift ^LustreJvmMmapRegion reuse))
                       -1)
        segments (map-segments map-ch size shift old-segments old-shift)
        ;; Separate FileChannel kept open for fcntl locking —
        ;; MUST NOT be closed until the region is disposed.
        lock-ch (FileChannel/open path
                  (into-array OpenOption
                    [StandardOpenOption/READ
                     StandardOpenOption/WRITE]))]
    (LustreJvmMmapRegion. segments size (int shift) lock-ch (ReentrantLock.))))

(defn lustre-region?
  "true for a region whose atomics are fcntl byte-range locks (a Lustre mount)."
  [r]
  (instance? LustreJvmMmapRegion r))

;; ---------------------------------------------------------------------------
;; eve.fsync's file syscalls (option 1, doc/jlog-redo-spec.md §9): open, fstat, lseek, fstatfs, sync_file_range,
;; fdatasync, close through the JVM's foreign function API (java.lang.foreign; final in JDK 22, and callable from
;; Clojure on 21 without --enable-preview — measured 2026-11-24 on OpenJDK 21.0.13), reached by reflection so this
;; namespace still loads on a JDK without it. Linux only (struct stat's st_ino at 8 and st_size at 48, statfs's f_type
;; at 0: x86-64 and aarch64 alike); elsewhere, or when the API is missing, nil — eve.fsync's callers then force
;; region by region.
;; ---------------------------------------------------------------------------

(def ^:private ffm-syscalls
  (delay
    (when (.startsWith (str (System/getProperty "os.name")) "Linux")
      (try
        (let [cls (fn [n] (Class/forName (str "java.lang.foreign." n)))
              sfield (fn [c f] (clojure.lang.Reflector/getStaticField ^Class (cls c) ^String f))
              smeth (fn [c m args] (clojure.lang.Reflector/invokeStaticMethod ^Class (cls c) ^String m
                                                                              ^"[Ljava.lang.Object;" (clojure.core/object-array args)))
              linker (smeth "Linker" "nativeLinker" [])
              lookup (clojure.lang.Reflector/invokeInstanceMethod linker "defaultLookup" (clojure.core/object-array 0))
              I (sfield "ValueLayout" "JAVA_INT")
              L (sfield "ValueLayout" "JAVA_LONG")
              A (sfield "ValueLayout" "ADDRESS")
              dc (fn [nm ret args]
                   (let [sym (clojure.lang.Reflector/invokeInstanceMethod
                              (clojure.lang.Reflector/invokeInstanceMethod lookup "find" (clojure.core/object-array [nm]))
                              "orElseThrow" (clojure.core/object-array 0))
                         fd (smeth "FunctionDescriptor" "of" [ret (clojure.core/into-array (cls "MemoryLayout") args)])]
                     (clojure.lang.Reflector/invokeInstanceMethod
                      linker "downcallHandle" (clojure.core/object-array [sym fd (clojure.core/into-array (cls "Linker$Option") [])]))))
              call (fn [^java.lang.invoke.MethodHandle h & args] (.invokeWithArguments h ^"[Ljava.lang.Object;" (clojure.core/object-array args)))
              h-open (dc "open" I [A I])
              h-fstat (dc "fstat" I [I A])
              h-lseek (dc "lseek" L [I L I])
              h-fstatfs (dc "fstatfs" I [I A])
              h-sfr (dc "sync_file_range" I [I L L I])
              h-fdatasync (dc "fdatasync" I [I])
              h-close (dc "close" I [I])
              arena (fn [] (smeth "Arena" "ofConfined" []))
              alloc (fn [a n] (clojure.lang.Reflector/invokeInstanceMethod a "allocate" (clojure.core/object-array [(clojure.core/long n)])))
              cstr (fn [a s] (try (clojure.lang.Reflector/invokeInstanceMethod a "allocateUtf8String" (clojure.core/object-array [s]))
                                  (catch IllegalArgumentException _
                                    (clojure.lang.Reflector/invokeInstanceMethod a "allocateFrom" (clojure.core/object-array [s])))))
              get-long (fn [seg off] (clojure.lang.Reflector/invokeInstanceMethod seg "get" (clojure.core/object-array [L (clojure.core/long off)])))
              close-arena (fn [a] (clojure.lang.Reflector/invokeInstanceMethod a "close" (clojure.core/object-array 0)))
              chk (fn [what r] (when (neg? (clojure.core/long r)) (throw (ex-info (str "eve.region: " what " failed") {:result r}))) r)]
          ;; the handles resolve now: a libc without one of them is a host without the syscalls
          {:open-ro (fn [path] (let [a (arena)] (try (clojure.core/long (call h-open (cstr a path) (int 0))) (finally (close-arena a)))))
           :fstat (fn [fd] (let [a (arena)]
                             (try (let [st (alloc a 144)]
                                    (chk "fstat" (call h-fstat (int fd) st))
                                    [(clojure.core/long (get-long st 8)) (clojure.core/long (get-long st 48))])
                                  (finally (close-arena a)))))
           :seek-hole (fn [fd] (clojure.core/long (chk "lseek SEEK_HOLE" (call h-lseek (int fd) (clojure.core/long 0) (int 4)))))
           :fs-type (fn [fd] (let [a (arena)]
                               (try (let [b (alloc a 128)]
                                      (chk "fstatfs" (call h-fstatfs (int fd) b))
                                      (clojure.core/long (get-long b 0)))
                                    (finally (close-arena a)))))
           :sync-range (fn [fd] (chk "sync_file_range" (call h-sfr (int fd) (clojure.core/long 0) (clojure.core/long 0) (int 7))) nil)
           :fdatasync (fn [fd] (chk "fdatasync" (call h-fdatasync (int fd))) nil)
           :close (fn [fd] (call h-close (int fd)) nil)})
        (catch Throwable _ nil)))))

(fsync/register! (fn [] @ffm-syscalls))
