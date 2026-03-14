# Implementation Plan: LustreMmapRegion Spike

## Goal

Add a new `IMemRegion` implementation that uses **mmap for data access** + **fcntl byte-range locks for atomic operations**, enabling Eve atoms to work across machines on a Lustre filesystem. Zero changes to HAMT algorithms, slab allocator, serializer, epoch GC, or public API.

---

## Architecture

### How It Works

Current Eve mmap atoms use CPU hardware atomics (`__atomic_compare_exchange_n` on Node, `Unsafe.compareAndSwapInt` on JVM) for CAS on mmap'd memory. These atomics only work within a single machine's cache-coherent memory domain.

On Lustre across machines, hardware atomics don't span nodes. Instead:

```
fcntl CAS(fd, offset, expected, desired):
  1. fcntl(fd, F_SETLKW, {F_WRLCK, offset, 4})   // exclusive lock on 4 bytes
  2. pread(fd, &current, 4, offset)                 // read current value
  3. if current == expected:
       pwrite(fd, &desired, 4, offset)              // write new value
       msync(addr+offset, 4, MS_SYNC)               // flush to Lustre
  4. fcntl(fd, F_SETLK, {F_UNLCK, offset, 4})      // unlock
  5. return current                                  // old value (matches CAS contract)
```

This is a pessimistic lock-based CAS. Slower than hardware CAS (~50-200us vs ~10ns) but correct across Lustre nodes via LDLM.

### Key Design Decisions

1. **mmap for reads/writes, fcntl for atomics** — Keep zero-copy mmap data access. Only atomic operations go through fcntl locks.
2. **Keep fd open** — Current `mmap_cas.cc` closes fd after mmap (line 146). Lustre mode must keep it open for fcntl locks.
3. **msync after locked writes** — Ensures Lustre LDLM propagates changes before lock release.
4. **JVM thread safety** — Java's `FileChannel.lock()` is process-scoped (POSIX fcntl semantics). Wrap with `ReentrantLock` for intra-JVM thread serialization.
5. **Polling for wait/notify** — No futex across Lustre nodes. Use polling fallback (JVM already does this with 100us sleep).
6. **Mount requirement** — All Lustre clients must mount with `-o flock` (default since Lustre 2.13+).

---

## Steps

### Step 1: Extend native addon (`native/mmap_cas.cc`) ~100 lines

Add functions that keep fd open and use fcntl for atomics:

```cpp
// New: open file, mmap MAP_SHARED, keep fd for fcntl
Napi::Value OpenWithFd(const Napi::CallbackInfo& info);
// Returns {buf: Buffer, fd: number}

// New: fcntl-based CAS on 4 bytes
// Lock [offset, offset+4), pread, compare, pwrite, unlock
Napi::Value FcntlCas32(const Napi::CallbackInfo& info);
// Args: fd, buf, byteOffset, expected, desired → returns old value

// New: fcntl-based atomic add on 4 bytes
Napi::Value FcntlAdd32(const Napi::CallbackInfo& info);
// Args: fd, buf, byteOffset, delta → returns old value

// New: fcntl-based atomic sub on 4 bytes
Napi::Value FcntlSub32(const Napi::CallbackInfo& info);

// New: fcntl-based exchange on 4 bytes
Napi::Value FcntlExchange32(const Napi::CallbackInfo& info);

// New: fcntl-based i64 variants (same pattern, 8 bytes)
Napi::Value FcntlCas64(const Napi::CallbackInfo& info);
Napi::Value FcntlAdd64(const Napi::CallbackInfo& info);
Napi::Value FcntlSub64(const Napi::CallbackInfo& info);

// New: close fd (for cleanup)
Napi::Value CloseFd(const Napi::CallbackInfo& info);
```

**MmapDeleter extension**: New variant `MmapFdDeleter` that also calls `close(fd)` on GC.

**msync helper**: After pwrite inside lock, call `msync(buf + (offset & ~(PAGE_SIZE-1)), PAGE_SIZE, MS_SYNC)`.

**Registration**: Add to `Init()` alongside existing exports.

### Step 2: LustreMmapRegion deftype (CLJS) in `src/eve/mem.cljc` ~80 lines

```clojure
(deftype LustreMmapRegion [^js buf fd size]
  IMemRegion
  (-byte-length [_] size)

  ;; Atomic i32 — all via fcntl lock-read-compare-write-unlock
  (-load-i32 [_ byte-off]
    (.fcntlLoad32 (native) fd buf byte-off))
  (-store-i32! [_ byte-off val]
    (.fcntlStore32 (native) fd buf byte-off val) nil)
  (-cas-i32! [_ byte-off expected desired]
    (.fcntlCas32 (native) fd buf byte-off expected desired))
  (-add-i32! [_ byte-off delta]
    (.fcntlAdd32 (native) fd buf byte-off delta))
  (-sub-i32! [_ byte-off delta]
    (.fcntlSub32 (native) fd buf byte-off delta))
  (-exchange-i32! [_ byte-off val]
    (.fcntlExchange32 (native) fd buf byte-off val))

  ;; Atomic i64 — same pattern
  (-load-i64 [_ byte-off]
    (.fcntlLoad64 (native) fd buf byte-off))
  (-store-i64! [_ byte-off val]
    (.fcntlStore64 (native) fd buf byte-off val) nil)
  (-cas-i64! [_ byte-off expected desired]
    (.fcntlCas64 (native) fd buf byte-off expected desired))
  (-add-i64! [_ byte-off delta]
    (.fcntlAdd64 (native) fd buf byte-off delta))
  (-sub-i64! [_ byte-off delta]
    (.fcntlSub64 (native) fd buf byte-off delta))

  ;; Wait/Notify — polling fallback (no futex across Lustre nodes)
  (-wait-i32! [this byte-off expected timeout-ms]
    (let [deadline (+ (js/Date.now) timeout-ms)]
      (loop []
        (let [cur (-load-i32 this byte-off)]
          (cond
            (not= cur expected) :not-equal
            (>= (js/Date.now) deadline) :timed-out
            :else (do (.waitSync (native) 100000) ;; 100us nanosleep
                      (recur)))))))
  (-notify-i32! [_ _byte-off _n] 0) ;; no-op, polling handles it
  (-supports-watch? [_] false)

  ;; Byte I/O — same as NodeMmapRegion (direct mmap access)
  (-read-bytes [_ byte-off len]
    (.slice buf byte-off (+ byte-off len)))
  (-write-bytes! [_ byte-off src]
    (.set buf (js/Buffer.from src) byte-off) nil))
```

**Constructor function**:
```clojure
(defn open-lustre-region [path size-bytes]
  (let [result (.openWithFd (native) path size-bytes)
        buf    (.-buf result)
        fd     (.-fd result)]
    (LustreMmapRegion. buf fd size-bytes)))
```

### Step 3: LustreJvmMmapRegion deftype (JVM) in `src/eve/mem.cljc` ~100 lines

```clojure
(deftype LustreJvmMmapRegion
  [^MemorySegment seg ^long base-addr ^long size
   ^FileChannel lock-channel
   ^java.util.concurrent.locks.ReentrantLock thread-lock]

  IMemRegion
  (-byte-length [_] size)

  ;; Atomic i32 — fcntl via FileChannel.lock + pread/pwrite
  (-cas-i32! [_ byte-off expected desired]
    (.lock thread-lock)
    (try
      (let [fl (.lock lock-channel (long byte-off) 4 false)]
        (try
          (let [bb (java.nio.ByteBuffer/allocate 4)
                _ (.position lock-channel (long byte-off))
                _ (.read lock-channel bb)
                _ (.flip bb)
                current (.getInt bb)]
            (when (= current (unchecked-int expected))
              (.clear bb)
              (.putInt bb (unchecked-int desired))
              (.flip bb)
              (.position lock-channel (long byte-off))
              (.write lock-channel bb)
              (.force lock-channel false))
            current)
          (finally (.release fl))))
      (finally (.unlock thread-lock))))

  ;; load/store/add/sub/exchange — same lock-read-write-unlock pattern
  ;; ...

  ;; Wait/Notify — polling (same as existing JvmMmapRegion)
  (-wait-i32! [this byte-off expected timeout-ms]
    (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
      (loop []
        (let [cur (-load-i32 this byte-off)]
          (cond
            (not= cur (int expected)) :not-equal
            (>= (System/currentTimeMillis) deadline) :timed-out
            :else (do (Thread/sleep 0 100000)
                      (recur)))))))
  (-notify-i32! [_ _ _] 0)
  (-supports-watch? [_] false)

  ;; Byte I/O — direct mmap access via MemorySegment (same as JvmMmapRegion)
  (-read-bytes [_ byte-off len]
    (let [dst (byte-array len)]
      (MemorySegment/copy seg (long byte-off)
                          (MemorySegment/ofArray dst) 0 (long len))
      dst))
  (-write-bytes! [_ byte-off src]
    (MemorySegment/copy (MemorySegment/ofArray src) 0
                        seg (long byte-off) (long (alength ^bytes src)))
    nil))
```

**Constructor**:
```clojure
(defn open-lustre-region [path-str size-bytes]
  (let [size (long size-bytes)
        path (Paths/get path-str (into-array String []))
        ;; Ensure file exists at size
        _ (let [raf (RandomAccessFile. path-str "rw")]
            (try (when (< (.length raf) size) (.setLength raf size))
                 (finally (.close raf))))
        ;; mmap for data access
        arena (Arena/ofShared)
        seg (with-open [fc (FileChannel/open path
                             (into-array [StandardOpenOption/READ
                                         StandardOpenOption/WRITE]))]
              (.map fc FileChannel$MapMode/READ_WRITE 0 size arena))
        ;; Separate FileChannel kept open for fcntl locking
        lock-ch (FileChannel/open path
                  (into-array [StandardOpenOption/READ
                              StandardOpenOption/WRITE]))]
    (LustreJvmMmapRegion. seg (.address seg) size
                          lock-ch
                          (java.util.concurrent.locks.ReentrantLock.))))
```

### Step 4: Wire Lustre mode into domain init (`src/eve/atom.cljc`) ~30 lines

Add a `*lustre-mode*` dynamic var and plumb it through domain creation:

```clojure
(def ^:dynamic *lustre-mode* false)

;; In open-mmap-region dispatch (mem.cljc):
(defn open-mmap-region [path size-bytes]
  (if *lustre-mode*
    (open-lustre-region path size-bytes)
    ;; existing: NodeMmapRegion or JvmMmapRegion
    ...))
```

Alternatively, pass `:lustre? true` through the domain config:

```clojure
;; atom.cljc — persistent-atom-domain
(defn persistent-atom-domain
  [base-path & {:keys [capacities lustre?] :or {capacities {} lustre? false}}]
  (binding [mem/*lustre-mode* lustre?]
    (or (get @domain-cache base-path)
        ...)))

;; Public API in eve.alpha:
(e/atom {:id ::counter :persistent "./db" :lustre? true} {:count 0})
```

### Step 5: Handle fd lifecycle ~20 lines

**CLJS**: The `LustreMmapRegion` holds `fd`. On domain close or process exit, call `(.closeFd (native) fd)`.

**JVM**: The `LustreJvmMmapRegion` holds `lock-channel`. On domain close, call `(.close lock-channel)`.

Wire into existing `close-atom-domain!` and process exit handlers in `atom.cljc`.

### Step 6: Optimization — load/store without lock for non-contended fields ~20 lines

Not all IMemRegion operations need fcntl locks. Reads of non-contended fields (e.g., slab header magic, block_size) can use direct mmap access without locking:

For the spike, keep it simple — all atomic ops go through fcntl. Optimize later by distinguishing "hot" fields (root pointer, epoch, free count, bitmap words) from "cold" fields (slab magic, block_size, class_idx).

### Step 7: Tests ~100 lines

Create `test/eve/lustre_test.cljs` with tests that exercise the Lustre region:

1. **Unit**: LustreMmapRegion satisfies IMemRegion — load/store/CAS/add/sub round-trip
2. **CAS correctness**: Multiple sequential CAS operations, verify only expected-match succeeds
3. **Bitmap alloc**: `imr-bitmap-alloc-cas!` and `imr-bitmap-free!` work through LustreMmapRegion
4. **Integration**: Create a persistent atom with `:lustre? true`, do swap!/deref
5. **Concurrent**: Fork child processes, verify CAS correctness under contention

Tests can run on local ext4 (fcntl works locally too, just faster). Lustre is only needed for cross-machine testing.

---

## File Changes Summary

| File | Change | Lines |
|------|--------|-------|
| `native/mmap_cas.cc` | Add `OpenWithFd`, `FcntlCas32/64`, `FcntlAdd32/64`, `FcntlSub32/64`, `FcntlExchange32/64`, `FcntlLoad32/64`, `FcntlStore32/64`, `CloseFd`, `MmapFdDeleter` | ~150 |
| `src/eve/mem.cljc` | Add `LustreMmapRegion` (CLJS), `LustreJvmMmapRegion` (JVM), `open-lustre-region`, `*lustre-mode*` | ~200 |
| `src/eve/atom.cljc` | Pass `:lustre?` through domain init, bind `*lustre-mode*`, fd cleanup on close | ~30 |
| `test/eve/lustre_test.cljs` | Unit + integration + concurrent tests | ~100 |
| **Total** | | **~480 lines** |

## What Does NOT Change

- `src/eve/map.cljc` — HAMT algorithms
- `src/eve/vec.cljc` — persistent vector
- `src/eve/set.cljc` — persistent set
- `src/eve/list.cljc` — persistent list
- `src/eve/deftype_proto/alloc.cljc` — slab allocator (uses IMemRegion)
- `src/eve/deftype_proto/coalesc.cljc` — overflow allocator (uses IMemRegion)
- `src/eve/deftype_proto/serialize.cljc` — serializer
- `src/eve/shared_atom.cljs` — SAB atoms (in-process only, PROTECTED)
- `src/eve/alpha.cljs` / `.clj` — public API (passes config through)
- `src/eve/obj.cljc` — typed objects
- `src/eve/deftype.cljs` / `.clj` — deftype macro

---

## Performance Expectations

| Operation | Current (local mmap) | Lustre (cross-node) |
|-----------|---------------------|---------------------|
| load-i32 | ~10ns | ~50-200us (fcntl round-trip) |
| CAS-i32 | ~10ns | ~50-200us (fcntl round-trip) |
| swap! (no contention) | 0.12-0.39ms | ~1-5ms |
| swap! (contended, 4 writers) | 0.19ms p50 | ~5-20ms |
| deref (read) | <1us | <1us (mmap cached pages) |
| Bulk byte read | <1us | <1us (mmap cached pages) |

Reads remain fast (mmap pages cached locally by Lustre). Writes slow down proportional to LDLM lock round-trip latency, but stay well within usable range.

---

## Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| fcntl close-any-fd-releases-all | Keep exactly one fd per file. Never close it except on domain shutdown. |
| JVM thread-blindness of POSIX locks | ReentrantLock wraps all FileChannel.lock() calls |
| Bitmap CAS storms (many allocs) | Batch: lock entire bitmap word range, scan+alloc, unlock. Future optimization. |
| No Lustre in CI | Tests work on local ext4 (fcntl is local-compatible). Lustre-specific CI later. |
| Lock pingpong on root pointer | Acceptable for infrequent root CAS. High-frequency bitmap ops are the concern — optimize later. |

---

## Sequencing

1. **Step 1** (native addon) — can be built and tested independently
2. **Step 2** (CLJS deftype) — depends on Step 1
3. **Step 3** (JVM deftype) — independent of Steps 1-2
4. **Step 4** (wiring) — depends on Steps 2+3
5. **Step 5** (fd lifecycle) — depends on Step 4
6. **Step 6** (optimization) — defer to post-spike
7. **Step 7** (tests) — alongside each step

Steps 1+3 can be done in parallel. Steps 2+3 can be done in parallel after Step 1.
