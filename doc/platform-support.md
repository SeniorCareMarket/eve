# Eve Platform Support

Detailed breakdown of platform requirements, capabilities, and differences between JVM and Node.js.

---

## Supported Platforms

| Platform | In-Process Atoms | Cross-Process Atoms | Status |
|---|---|---|---|
| Node.js 18+ (Linux) | SharedArrayBuffer | mmap + futex | Full support |
| Node.js 18+ (macOS) | SharedArrayBuffer | mmap (stub wait) | Works, degraded wait/notify |
| Node.js 18+ (Windows) | SharedArrayBuffer | Not supported | SAB-only |
| JVM 21+ (Linux) | JvmHeapRegion | mmap + Panama FFM | Full support |
| JVM 21+ (macOS) | JvmHeapRegion | mmap + Panama FFM | Works, degraded wait/notify |
| Browser (modern) | SharedArrayBuffer | Not applicable | SAB atoms only |

---

## Runtime Requirements

### Node.js (ClojureScript)

| Requirement | Version | Purpose |
|---|---|---|
| Node.js | 18+ | Runtime, SharedArrayBuffer, worker threads |
| npm | Any recent | Dependency management, native addon build |
| shadow-cljs | 3.3.6+ | ClojureScript compiler |
| C++ compiler | C++20 | Native addon compilation (mmap_cas) |
| node-gyp | Any recent | Native addon build system |
| node-addon-api | 8.6+ | N-API C++ wrapper (header-only) |
| node-gyp-build | 4.8+ | Runtime addon loading |

### JVM (Clojure)

| Requirement | Version | Purpose |
|---|---|---|
| Java | 21+ | Panama FFM for mmap, MemorySegment API |
| Clojure | 1.12.0+ | Core language |
| Clojure CLI | 1.11+ | Build tool |

JVM flags required (configured in `deps.edn`):
```
--add-opens java.base/java.lang=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

### Browser

SharedArrayBuffer requires:
- HTTPS or localhost
- Cross-Origin headers: `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`

---

## Platform Comparison

### Memory Backing

| Aspect | Node.js (CLJS) | JVM (Clojure) |
|---|---|---|
| In-process backing | `SharedArrayBuffer` | `JvmHeapRegion` (byte[] + Unsafe) |
| Cross-process backing | mmap via C++ addon | mmap via Panama FFM `MemorySegment` |
| Atomic operations | `js/Atomics` (SAB) / N-API addon (mmap) | `sun.misc.Unsafe` |
| Slab state | Module-level globals | Explicit `JvmSlabCtx` per domain |

### Concurrency Model

| Aspect | Node.js (CLJS) | JVM (Clojure) |
|---|---|---|
| Threading | Single-threaded (event loop) | Multi-threaded |
| Cross-worker sharing | `SharedArrayBuffer` + `postMessage` | Not applicable (shared heap) |
| Cross-process sharing | mmap files | mmap files |
| CAS backoff | `Atomics.wait` on scratch SAB | `LockSupport.parkNanos` |
| Epoch pinning | Single epoch per process | Per-thread via `ConcurrentHashMap` |
| Worker heartbeat | `setInterval` | `ScheduledExecutorService` |

### Deref Behavior

| Aspect | Node.js (CLJS) | JVM (Clojure) |
|---|---|---|
| SAB atom deref | Returns lazy slab-backed Eve types | N/A |
| Persistent atom deref | Returns lazy slab-backed Eve types | Returns lazy `EveHashMap` |
| Materialization | On access (transparent) | On access (transparent) |

### API Differences

| Operation | Node.js (CLJS) | JVM (Clojure) |
|---|---|---|
| Atom creation | `(e/atom ...)` via `eve.alpha` | `(atom/atom ...)` via `eve.atom` |
| Persistent atom | `(e/atom {:id ::k :persistent "path"} val)` | `(atom/atom {:id ::k :persistent "path"} val)` |
| AtomDomain | `(eve/atom-domain val :sab-size N)` | `(atom/persistent-atom-domain "path")` |
| Close domain | Not available | `atom/close-atom-domain!` (also via `e/close-atom-domain!` on CLJ) |
| deftype macro | `(eve/deftype ...)` (CLJS only) | Not available |
| Typed objects | `eve.obj` | `eve.obj` |
| Typed arrays | `eve.array` | `eve.array` |
| Integer map | `eve.deftype.int-map` (CLJS only) | Not available |
| Sorted set | `eve.deftype.rb-tree` (CLJS only) | Not available |

---

## Native Addon Details

### What It Provides

The native addon (`native/mmap_cas.cc`) provides:

1. **`open(path, size) -> Buffer`** — Memory-map a file, returning a Node.js Buffer whose backing memory IS the mmap'd page.
2. **Atomic int32 operations** — `load32`, `store32`, `cas32`, `add32`, `sub32` using `__atomic_*` GCC/Clang built-ins.
3. **Futex wait/notify** — `wait32`, `notify32` for cross-process coordination.

### Building

```bash
# Build (or rebuild) the native addon
npm run build:addon

# Or manually:
cd native && node-gyp configure build
```

The addon is built automatically on `npm install` via the `postinstall` script.

### Platform-Specific Behavior

**Linux:**
- Full futex support via `syscall(SYS_futex, ...)`.
- Uses `FUTEX_WAIT_BITSET` with `CLOCK_MONOTONIC` absolute deadline (race-free).
- No `FUTEX_PRIVATE_FLAG` — cross-process futex uses inode-based keying.

**macOS:**
- Futex is stubbed: `futex_wait` returns -1, `futex_wake` returns 0.
- CAS and atomic operations work (same `__atomic_*` built-ins).
- Wait/notify falls back to spin with short sleep.
- A proper port would use `os_sync_wait_on_address` (macOS 14+) or `__ulock_wait`/`__ulock_wake`.

**Windows:**
- Not currently supported for mmap atoms (no mmap or futex).
- SAB-backed in-process atoms work fine.

### Compiler Requirements

| Platform | Compiler | Notes |
|---|---|---|
| Linux | GCC 5+ or Clang 3.4+ | `__atomic_*` built-ins required |
| macOS | Apple Clang 6+ | Uses `__atomic_*` instead of `std::atomic_ref` for compatibility |
| Windows | MSVC with `/std:c++20` | Compilation supported, mmap not available |

---

## JVM Panama FFM

The JVM uses Java's Foreign Function & Memory API (Panama FFM, Java 21+) for mmap:

- `MemorySegment` maps files into process address space.
- `sun.misc.Unsafe` provides atomic CAS operations on the mapped memory.
- `LockSupport.parkNanos` for CAS backoff sleep.
- `FileChannel.map` with `MapMode.READ_WRITE` for shared mappings.

The `JvmMmapRegion` implementation wraps `MemorySegment` and provides the `IMemRegion` protocol.

The `JvmHeapRegion` implementation (for non-persistent atoms) uses `byte[]` arrays with `Unsafe` atomics — no files, no mmap.

---

## Cross-Platform File Format

Both JVM and Node.js use **identical file formats**, byte layouts, hash functions (Murmur3_x86_32), and CAS protocols. A domain created by one platform can be joined by the other:

```
Process A (JVM):
  (def a (atom/atom {:id ::state :persistent "/tmp/shared"} {:count 0}))
  (swap! a update :count inc)

Process B (Node.js):
  (def b (e/atom {:id ::state :persistent "/tmp/shared"} nil))
  @b  ;=> {:count 1}
```

### File Format Identity

| Aspect | Guarantee |
|---|---|
| Byte order | Little-endian everywhere |
| Hash function | Murmur3_x86_32 (seed=0) over serialized key bytes |
| Slab header | Identical 64-byte layout |
| Root file | Identical V2 format (8,320 bytes) |
| Serialization tags | Same tag bytes, same encoding |
| Slab-qualified offsets | Same `[class:3 \| block:29]` encoding |
| CAS semantics | Same protocol (read-apply-CAS-retry) |

---

## Limitations by Platform

### All Platforms

- 256 named atoms per domain
- 256 concurrent processes/workers per domain
- No STM-style multi-atom transactions
- Maximum slab block size: 1024 bytes (larger goes to overflow)

### Node.js Specific

- Single-threaded per process (event loop)
- `SharedArrayBuffer` requires cross-origin isolation in browsers
- Native addon required for mmap (cross-process) atoms

### JVM Specific

- Requires Java 21+ for Panama FFM
- Requires `--add-opens` and `--enable-native-access` JVM flags
- `eve/deftype`, integer maps, and sorted sets are CLJS-only
- No `add-watch` / `set-validator!` on persistent atoms

### macOS Specific

- Futex wait/notify is stubbed (spin fallback)
- Higher tail latency under contention compared to Linux
- Full CAS correctness is maintained

### Browser Specific

- SAB atoms only (no mmap, no cross-process)
- Requires HTTPS + COOP/COEP headers
- No native addon, no file I/O

---

## Version Matrix

| Component | Minimum | Tested |
|---|---|---|
| Node.js | 18 | 18, 20, 22 |
| Java | 21 | 21 |
| Clojure | 1.12.0 | 1.12.0 |
| ClojureScript | 1.12.134 | 1.12.134 |
| shadow-cljs | 3.3.6 | 3.3.6 |
| node-addon-api | 8.6.0 | 8.6.0 |
| Linux kernel | 2.6.28+ | 6.x |
