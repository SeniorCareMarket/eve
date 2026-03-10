# Eve Architecture

High-level system design and memory model.

---

## Design Goals

1. **Shared-memory persistence** — Clojure persistent data structures living in `SharedArrayBuffer` (in-process) or memory-mapped files (cross-process), accessible with zero-copy semantics.
2. **Transparent Clojure API** — Standard `swap!`/`reset!`/`deref` on atoms. Regular Clojure maps, vectors, sets, and lists are automatically serialized into Eve's shared-memory equivalents.
3. **Cross-platform identity** — JVM Clojure and Node.js ClojureScript share identical on-disk formats, byte layouts, hash functions, and CAS protocols. A domain created by one platform can be joined by the other.
4. **Lock-free concurrency** — All mutations use compare-and-swap (CAS) on a single 32-bit root pointer. No locks, no coordination server.

---

## System Layers

```
┌──────────────────────────────────────────────────────────┐
│  User Code                                                │
│    (swap! my-atom update :counter inc)                    │
├──────────────────────────────────────────────────────────┤
│  eve.alpha                                                │
│    Public API: e/atom, e/hash-map, e/deftype              │
├────────────────────┬─────────────────────────────────────┤
│  eve.shared-atom   │  eve.atom                            │
│  (SAB atoms, CLJS) │  (mmap atoms, CLJ + CLJS)           │
├────────────────────┴─────────────────────────────────────┤
│  Data Structures                                          │
│    eve.map (HAMT) | eve.vec | eve.set | eve.list          │
│    eve.deftype.int-map | eve.deftype.rb-tree              │
│    eve.obj (typed objects)                                │
├──────────────────────────────────────────────────────────┤
│  Slab Allocator (eve.deftype-proto.alloc)                 │
│    6 fixed-size classes + overflow coalescing allocator    │
├──────────────────────────────────────────────────────────┤
│  Serializer (eve.deftype-proto.serialize)                 │
│    Binary encoding for primitives and collections         │
├──────────────────────────────────────────────────────────┤
│  IMemRegion (eve.mem)                                     │
│    Platform-neutral shared memory abstraction             │
├────────────────────┬─────────────────────────────────────┤
│  JsSabRegion       │  NodeMmapRegion  │  JvmMmapRegion   │
│  (SharedArrayBuf)  │  (N-API addon)   │  (Panama FFM)    │
└────────────────────┴──────────────────┴──────────────────┘
```

---

## Two Atom Modes

### In-Process: SharedArrayBuffer (CLJS)

- **Backing:** `SharedArrayBuffer` — shared memory visible to all workers/threads in the same process.
- **Created by:** `e/atom` without `:persistent`.
- **Atom container:** `AtomDomain` — a single SAB holding many named atoms.
- **Concurrency:** Cross-worker CAS via `Atomics.compareExchange`.
- **GC:** Epoch-based — blocks are freed only when no reader holds a reference.

### Cross-Process: Memory-Mapped Files (CLJ + CLJS)

- **Backing:** `mmap`'d files on disk — shared memory visible across OS processes.
- **Created by:** `e/atom` with `:persistent`.
- **Files:** `.slab0`–`.slab5` (fixed-size), `.slab6` (overflow), `.root` (atom table + worker registry), `.rmap` (reader epoch map).
- **Concurrency:** Cross-process CAS via Linux `futex(2)` (Node.js) or Panama FFM / `sun.misc.Unsafe` (JVM).
- **GC:** Epoch-based with heartbeat-driven stale-process detection (30s timeout).

---

## Memory Model

### The Slab Allocator

Eve uses a multi-slab allocator. Six size-stratified slabs hold fixed-size blocks; a seventh handles overflow (>1024B):

| Class | Block Size | Typical Contents |
|---|---|---|
| 0 | 32 B | Tiny HAMT bitmap nodes (0-1 children) |
| 1 | 64 B | Common HAMT nodes (2-3 children, 1-2 KVs) |
| 2 | 128 B | Medium nodes (4-8 children, vec trie nodes) |
| 3 | 256 B | Dense nodes, collision nodes, serialized values |
| 4 | 512 B | Very dense or large collision nodes |
| 5 | 1024 B | Large collision nodes, big payloads |
| 6 | Variable | Coalescing allocator (blocks >1024B) |

Each slab (classes 0-5) has:
- A **64-byte header** with magic, block size, total blocks, free count, alloc cursor, class index, bitmap offset, data offset.
- A **bitmap region** (1 bit per block, 0=free, 1=allocated, 16-byte aligned for SIMD).
- A **data region** (contiguous, uniformly-sized blocks).

Slab files grow lazily — initial capacities are small (16-64KB), with max capacities up to 1GB per class. On Linux/macOS, sparse files and lazy mmap page commit mean untouched pages cost zero physical memory or disk.

### Slab-Qualified Offsets

All node references in Eve are 32-bit **slab-qualified offsets**:

```
[class_idx:3 bits | block_idx:29 bits]
```

- `class_idx` 0-5: slab classes (32B-1024B)
- `class_idx` 6: overflow allocator
- `class_idx` 7: reserved/sentinel
- `block_idx`: up to 536M blocks per slab

The NIL sentinel is `-1` (all bits set = class 7, block 0x1FFFFFFF).

### The Root Pointer

Each atom's state is captured by a single 32-bit root pointer stored in the `.root` file (mmap) or root SAB (SharedArrayBuffer). The root pointer is a slab-qualified offset pointing to the root HAMT node (or scalar block) of the atom's value.

A `swap!` mutates the atom by CAS-ing this single i32 — the entire tree of HAMT nodes is immutable and structurally shared.

---

## HAMT (Hash Array Mapped Trie)

Eve maps and sets use a 32-way branching HAMT with bitmap-indexed nodes:

- **Hash function:** Murmur3_x86_32 over serialized key bytes (identical output on JVM and CLJS).
- **Node types:** Bitmap nodes (sparse 32-way branch) and collision nodes (same hash, different keys).
- **Structural sharing:** Updating one key in a 1,000-key map only allocates O(log32 N) new nodes along the root-to-leaf path. The rest of the tree is shared with the previous version.

### Node Layout (Bitmap Node)

```
[type:u8][flags:u8][kv_total_size:u16][data_bitmap:u32][node_bitmap:u32]
[inline KV data...][child offsets...]
```

- `data_bitmap`: 32-bit mask indicating which hash prefix slots have inline key-value data.
- `node_bitmap`: 32-bit mask indicating which slots have child node pointers.
- Inline KVs are serialized key-value pairs packed after the header.
- Child offsets are slab-qualified 32-bit pointers.

---

## The CAS Loop

Every `swap!` follows this protocol:

1. **Pin epoch** — announce that this process is reading at the current epoch.
2. **Read root pointer** — atomic load of the atom's root pointer.
3. **Deref** — follow the pointer to reconstruct the current value (lazy on both platforms).
4. **Apply function** — call `(f current-value args...)` to compute the new value.
5. **Allocate new nodes** — write new HAMT nodes into slabs.
6. **CAS** — atomically compare-and-swap the root pointer (old -> new).
7. **On success:** bump epoch, retire old nodes to the GC queue.
8. **On failure:** free the newly allocated nodes, back off (jittered exponential), retry from step 1.

Maximum retries: 1000 (configurable via `MAX_SWAP_RETRIES`).

---

## Epoch-Based Garbage Collection

Old HAMT nodes can't be freed immediately — another process/worker might still be reading them. Eve uses epoch-based GC:

- Each process/worker claims a **worker slot** (256 available per domain) and writes a periodic heartbeat.
- Before reading, a process **pins** its current epoch via an atomic write to the reader map.
- After a successful `swap!`, old nodes are placed in a **retire queue** tagged with the current epoch.
- Periodically (every 50ms by default), the retire queue is flushed: nodes whose epoch is older than all currently-pinned epochs are freed.
- Stale processes (no heartbeat for 30s) are ignored during the epoch scan.

This guarantees that no node is freed while any process could still be reading it.

---

## Multi-Atom Domains

A single domain can hold up to **256 named atoms**. The `.root` file contains:

| Region | Size | Purpose |
|---|---|---|
| Header | 64 B | Magic (`ROOT`/`ROOT` V2), root pointer, epoch counter |
| Worker Registry | 6,144 B | 256 slots × 24 B (status, epoch, heartbeat, worker ID) |
| Atom Table Header | 64 B | Magic (`ATOM`), table metadata |
| Atom Table | 2,048 B | 256 slots × 8 B (root pointer + name hash) |

**Total root file: 8,320 bytes.**

Slot 0 is reserved for a **registry atom** — an `EveHashMap` that maps keyword name strings to slot indices. When you create a named persistent atom:

1. The domain is opened/joined at the given path.
2. The keyword name is looked up in the registry via FNV-1a hash.
3. If found, the existing slot is returned.
4. If not found, a free slot is claimed, the registry is updated, and the initial value is written.

---

## File Layout (Persistent Atoms)

A domain at path `./my-db` creates:

```
./my-db.slab0      # 32-byte blocks (HAMT bitmap nodes)
./my-db.slab0.bm   # Bitmap for slab0
./my-db.slab1      # 64-byte blocks
./my-db.slab1.bm   # Bitmap for slab1
./my-db.slab2      # 128-byte blocks
./my-db.slab2.bm   # Bitmap for slab2
./my-db.slab3      # 256-byte blocks
./my-db.slab3.bm   # Bitmap for slab3
./my-db.slab4      # 512-byte blocks
./my-db.slab4.bm   # Bitmap for slab4
./my-db.slab5      # 1024-byte blocks
./my-db.slab5.bm   # Bitmap for slab5
./my-db.slab6      # Variable-size overflow (coalescing allocator)
./my-db.root        # Root pointers, worker registry, atom table
./my-db.rmap        # Reader epoch map (256 KB)
```

Bitmap and data are in separate files for mmap. The slab header is at the start of each data file; the bitmap is its own file (`.bm`).

---

## IMemRegion Protocol

`IMemRegion` is the platform-neutral abstraction over a fixed-size region of shared memory. Eve requires only three things:

1. **Atomic int32 read/write/CAS/add/sub** — for lock fields, epoch counters, block descriptors.
2. **Futex-like wait/notify on int32 slots** — for sleep and coordination.
3. **Bulk non-atomic byte I/O** — for reading and writing serialized values.

Implementations:

| Implementation | Platform | Backing |
|---|---|---|
| `JsSabRegion` | CLJS (browser/Node) | `SharedArrayBuffer` + `Atomics` |
| `NodeMmapRegion` | CLJS (Node.js) | mmap via native C++ addon |
| `JvmMmapRegion` | CLJ (JVM) | Panama FFM `MemorySegment` + `Unsafe` atomics |
| `JvmHeapRegion` | CLJ (JVM) | `byte[]` + `sun.misc.Unsafe` (non-persistent) |

---

## ISlabIO Protocol

Higher-level abstraction over slab block I/O, consumed by HAMT algorithms:

```clojure
(defprotocol ISlabIO
  (-sio-read-u8   [ctx slab-offset field-off])
  (-sio-write-u8!  [ctx slab-offset field-off val])
  (-sio-read-u16  [ctx slab-offset field-off])
  (-sio-write-u16! [ctx slab-offset field-off val])
  (-sio-read-i32  [ctx slab-offset field-off])
  (-sio-write-i32! [ctx slab-offset field-off val])
  (-sio-read-bytes [ctx slab-offset field-off len])
  (-sio-write-bytes! [ctx slab-offset field-off src])
  (-sio-alloc!    [ctx size-bytes])
  (-sio-free!     [ctx slab-offset]))
```

- `slab-offset`: always a slab-qualified offset `[class:3 | block:29]`.
- `field-off`: byte offset within the block (relative to block start).
- CLJS: `CljsSlabIO` delegates to DataView-based functions.
- JVM: `JvmSlabCtx` holds one `IMemRegion` per slab file.

---

## Performance Characteristics

### Read Throughput (persistent atoms)

| Map Size | Node.js Ops/s | JVM Ops/s |
|---|---|---|
| 1 key | ~590,000 | ~5,100,000 |
| 100 keys | ~22,500 | ~2,400,000 |
| 500 keys | ~4,100 | ~1,700,000 |

JVM reads are faster because `EveHashMap` lazily traverses mmap'd memory without N-API crossing overhead.

### Write Throughput (persistent atoms)

| Map Size | Node.js Ops/s | JVM Ops/s |
|---|---|---|
| 100 keys | ~3,000 | ~1,000 |
| 500 keys | ~2,100 | ~720 |
| 1,000 keys | ~2,000 | ~700 |

### Swap Latency

| Scenario | p50 | p95 | max |
|---|---|---|---|
| Node, no contention | 0.12 ms | 0.36 ms | 2.6 ms |
| Node, 4 writers | 0.19 ms | 2.0 ms | 18.9 ms |
| JVM, no contention | 0.39 ms | 0.59 ms | 6.5 ms |

### Disk Footprint

| Map Size | Disk Usage |
|---|---|
| Empty | ~1 MB |
| 100 keys | ~1 MB |
| 500 keys | ~1.4 MB |
| 1,000 keys | ~2.1 MB |
