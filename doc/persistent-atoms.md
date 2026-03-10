# Persistent Atoms

> Cross-process, disk-backed atoms for Clojure and ClojureScript.

Persistent atoms let multiple OS processes — JVM Clojure and Node.js
ClojureScript — share and atomically mutate a Clojure data structure via
memory-mapped files on disk. Values survive process restarts. Concurrent
`swap!` calls from different processes converge to a correct result via
compare-and-swap (CAS).

---

## Quick Start

### Node.js (ClojureScript)

```clojure
(require '[eve :as e])

;; Create a persistent atom at an explicit path
(def counter (e/atom {:id ::counter :persistent "./my-db"} {:count 0}))

(swap! counter update :count inc)
@counter  ;=> {:count 1}

;; Another atom in the same domain
(def users (e/atom {:id ::users :persistent "./my-db"} {}))
(swap! users assoc :alice {:role :admin})
```

### JVM (Clojure)

```clojure
(require '[eve.atom :as atom])

;; --- In-memory (heap-backed) atoms ---
;; Analogous to CLJS SharedArrayBuffer atoms: fast, in-process, no files.
(def counter (atom/atom ::counter {:count 0}))
(swap! counter update :count inc)
@counter  ;=> {:count 1}

;; Anonymous in-memory atom
(def temp (atom/atom {:count 0}))

;; --- Persistent (mmap-backed) atoms ---
;; Cross-process, disk-backed. Survives restarts.
(def domain (atom/persistent-atom-domain "./my-db"))
(def pcounter (atom/atom {:id ::counter :persistent "./my-db"} {:count 0}))
;; or equivalently:
(def pcounter2 (atom/persistent-atom {:id ::counter :persistent "./my-db"} {:count 0}))

;; Clean up persistent domains when done
(atom/close-atom-domain! domain)
```

### Cross-Process Sharing

```clojure
;; Process A (JVM): create and write
(def d (atom/persistent-atom-domain "/tmp/shared"))
(def a (atom/persistent-atom {:id ::state :persistent "/tmp/shared"} {:count 0}))
(swap! a update :count inc)
;; @a => {:count 1}

;; Process B (Node.js): join and read
(def b (e/atom {:id ::state :persistent "/tmp/shared"} nil))
@b  ;=> {:count 1}
(swap! b update :count inc)
@b  ;=> {:count 2}

;; Back in Process A:
@a  ;=> {:count 2}
```

Both processes see the same value. No coordination server is needed — the
mmap'd files on disk are the shared medium.

---

## API Reference

### `atom/atom` (JVM) / `e/atom` (ClojureScript)

The unified entry point. Dispatches between heap-backed (in-memory) and
mmap-backed (persistent) atoms based on the `:persistent` key:

```clojure
;; --- Heap-backed (in-memory, no files) ---
(atom/atom ::counter {:count 0})             ;; named, keyword shorthand
(atom/atom {:id ::counter} {:count 0})       ;; named, config map
(atom/atom {:count 0})                       ;; anonymous

;; --- Persistent (mmap-backed, disk files) ---
(atom/atom {:id ::counter :persistent "./my-db"} {:count 0})
```

**Without `:persistent`:** Creates an in-memory heap-backed atom. On CLJS this
uses SharedArrayBuffer; on JVM this uses `JvmHeapRegion` (off-heap byte arrays
with `sun.misc.Unsafe` atomics). Fast, single-process, no disk I/O.

**With `:persistent`:** Creates an mmap-backed atom stored in files on disk.
Supports cross-process sharing between JVM and Node.js. Delegates to
`persistent-atom` internally.

All atoms support `@`, `swap!`, and `reset!`.

**Requirements:**
- `:id` must be a namespace-qualified keyword (e.g., `::counter` or `:my.app/state`)
- `:persistent true` uses the default domain at `./eve/`
- `:persistent "./path"` targets a specific domain directory

### `persistent-atom` (JVM + ClojureScript)

The lower-level entry point for persistent (mmap-backed) atoms. Available on
both platforms:

```clojure
;; Named atom, explicit domain
(atom/persistent-atom {:id ::counter :persistent "./my-db"} {:count 0})

;; Named atom, default domain
(atom/persistent-atom ::counter {:count 0})

;; Anonymous atom (auto-generated name)
(atom/persistent-atom {:count 0})
```

### `persistent-atom-domain`

Opens or creates a domain. Caches by path — repeated calls return the same
domain object.

```clojure
(def d (atom/persistent-atom-domain "./my-db"))
```

If the domain files already exist on disk, it joins them. Otherwise it
creates new files. This is the recommended way to manage domain lifecycle
explicitly.

### `close-atom-domain!`

Releases the worker slot and cancels the heartbeat timer. Atoms from the
domain become invalid after close.

```clojure
(atom/close-atom-domain! domain)
```

---

## How It Works

### File Layout

A domain at path `./my-db` creates these files:

| File | Purpose |
|------|---------|
| `./my-db.slab0` | 32-byte blocks (HAMT bitmap nodes) |
| `./my-db.slab1` | 64-byte blocks (interior nodes, small KVs) |
| `./my-db.slab2` | 128-byte blocks (vec trie nodes) |
| `./my-db.slab3` | 256-byte blocks (collision nodes, values) |
| `./my-db.slab4` | 512-byte blocks (large collision nodes) |
| `./my-db.slab5` | 1024-byte blocks (large payloads) |
| `./my-db.slab6` | Variable-size overflow (coalescing allocator) |
| `./my-db.root`  | Root pointers, worker registry, atom table |
| `./my-db.rmap`  | Reader epoch map (256 KB) |

All files are memory-mapped. Both JVM and Node.js processes map the same
physical pages, so writes from one process are immediately visible to another.

### The HAMT

Values are stored as a Hash Array Mapped Trie (HAMT) with 32-way branching.
HAMT nodes live in the slab files. The root file stores a 32-bit pointer to
the root HAMT node, encoded as `[class:3 bits | block:29 bits]`.

**Structural sharing:** When you `swap!` to update one key in a 1,000-key map,
only the O(log32 N) nodes along the path from root to leaf are reallocated.
The rest of the tree is shared with the previous version. This makes writes
efficient regardless of map size.

### The CAS Loop

Every `swap!` follows this protocol:

1. **Pin epoch** — announce that this process is reading at the current epoch
2. **Read root pointer** — load the atom's root pointer from the atom table
3. **Deref** — follow the pointer to reconstruct the current value (lazy on CLJS and JVM)
4. **Apply function** — call `(f current-value args...)` to compute the new value
5. **Allocate new nodes** — write new HAMT nodes into slabs
6. **CAS** — atomically compare-and-swap the root pointer (old → new)
7. **On success:** bump epoch, retire old nodes to the GC queue
8. **On failure:** free the newly allocated nodes, back off, retry from step 1

The CAS is a single atomic 32-bit compare-and-swap on the root file, provided
by the native addon (Linux futex) on Node.js and Panama FFM on JVM.

### Epoch GC

Old HAMT nodes can't be freed immediately — another process might still be
reading them. Persistent atoms use epoch-based garbage collection:

- Each process claims a **worker slot** (256 available) and writes a heartbeat
- Before reading, a process **pins** its current epoch
- After a successful `swap!`, old nodes are placed in a **retire queue** tagged with the epoch
- Periodically, the retire queue is flushed: nodes whose epoch is older than
  all currently-pinned epochs are freed
- Stale processes (no heartbeat for 60s) are ignored during the epoch scan

This guarantees that no node is freed while any process could still be reading it.

### Multi-Atom Domains

A single domain can hold up to **256 named atoms**. The root file contains an
atom table with 256 slots, each holding a root pointer and a name hash. Slot 0
is reserved for a **registry atom** — an `EveHashMap` that maps keyword strings
to slot indices.

When you create `(e/atom {:id ::counter :persistent "./db"} 0)`, the system:
1. Opens/joins the domain at `./db`
2. Looks up `"my.ns/counter"` in the registry
3. If found, returns an atom pointing to that slot
4. If not found, claims a free slot, updates the registry, sets the initial value

This means multiple named atoms share the same set of slab files, root file,
and worker registry.

---

## Platform Differences

| Aspect | Node.js (CLJS) | JVM (Clojure) |
|--------|----------------|---------------|
| Memory mapping | Native C++ addon (`mmap_cas.cc`) | Panama FFM (Java 21+) |
| Atomic CAS | Linux futex via addon | `sun.misc.Unsafe` via Panama |
| Deref | Returns lazy slab-backed Eve types | Returns lazy `EveHashMap` (post-OBJ-8) |
| CAS backoff | `Atomics.wait` on a scratch SAB | `LockSupport.parkNanos` |
| Worker slots | Single slot per process, heartbeat via `setInterval` | Single slot per process, heartbeat via `ScheduledExecutorService` |
| Thread safety | Single-threaded (Node event loop) | Multi-threaded; per-thread epoch pinning with `ConcurrentHashMap` |
| `atom` API | `e/atom` dispatches SAB vs mmap | `atom/atom` dispatches heap vs mmap |
| Non-persistent backing | SharedArrayBuffer (cross-worker) | JvmHeapRegion (byte[] + Unsafe atomics) |
| Slab state | Module-level globals | Explicit `JvmSlabCtx` per domain |

Both platforms use identical file formats, byte layouts, and CAS protocols.
A domain created by JVM can be joined by Node.js and vice versa.

---

## Performance Characteristics

### Read Throughput

Deref is fast because it returns a **lazy slab-backed type** — the HAMT nodes
are not eagerly deserialized. Values are only materialized when accessed.

| Map Size | Node.js Ops/s | JVM Ops/s (post-OBJ-8) |
|----------|---------------|-------------------------|
| 1 key    | ~590,000      | ~5,100,000              |
| 100 keys | ~22,500       | ~2,400,000              |
| 500 keys | ~4,100        | ~1,700,000              |

JVM reads are faster because `EveHashMap` lazily traverses mmap'd memory
without N-API crossing overhead.

### Write Throughput

Write throughput depends on map size (O(log32 N) path copies) and value
complexity:

| Map Size | Node.js Ops/s | JVM Ops/s (post-OBJ-8) |
|----------|---------------|-------------------------|
| 100 keys | ~3,000        | ~1,000                  |
| 500 keys | ~2,100        | ~720                    |
| 1,000 keys | ~2,000     | ~700                    |

Node.js writes are currently faster than JVM due to the N-API addon's
optimized mmap path.

### Swap Latency

| Scenario | p50 | p95 | max |
|----------|-----|-----|-----|
| Node, no contention | 0.12 ms | 0.36 ms | 2.6 ms |
| Node, 4 writers | 0.19 ms | 2.0 ms | 18.9 ms |
| JVM, no contention (post-OBJ-8) | 0.39 ms | 0.59 ms | 6.5 ms |

Under contention, p50 stays sub-millisecond. Tail latency (p95+) increases
due to CAS retries with jittered exponential backoff.

### Contention Scaling

CAS semantics hold perfectly under all tested contention levels (1–16 writers).
Throughput degrades gracefully:

| Writers | Throughput (swaps/s) |
|---------|---------------------|
| 1       | ~1,900              |
| 4       | ~1,700              |
| 8       | ~920                |
| 16      | ~470                |

All results verified for CAS correctness — no lost updates.

### Disk Footprint

Slab files grow lazily (proportional to data stored):

| Map Size | Disk Usage |
|----------|-----------|
| Empty    | ~1 MB     |
| 100 keys | ~1 MB     |
| 500 keys | ~1.4 MB   |
| 1,000 keys | ~2.1 MB |

### Cold Start

| Operation | Time |
|-----------|------|
| Create new domain (JVM) | ~54 ms |
| Join existing domain (JVM) | ~2 ms |
| Join existing domain (Node) | ~3 ms |

### Cross-Process Visibility

Writes are visible to other processes immediately via mmap. The measured
round-trip latency (~195 ms in benchmarks) is dominated by Node.js process
startup, not mmap propagation.

---

## Supported Value Types

Persistent atoms support any standard Clojure/ClojureScript value:

| Type | Supported | Notes |
|------|-----------|-------|
| Maps (hash-map) | Yes | Stored as HAMT with structural sharing |
| Vectors | Yes | Stored as trie in slabs |
| Sets | Yes | Stored as HAMT in slabs |
| Lists | Yes | Stored as linked nodes in slabs |
| Strings | Yes | Serialized as scalar blocks |
| Numbers (int, float) | Yes | Serialized as scalar blocks |
| Keywords | Yes | Serialized as scalar blocks |
| `nil` | Yes | Represented as `NIL_OFFSET` (-1) |
| Nested collections | Yes | Arbitrary nesting depth |

---

## Limitations

- **256 named atoms per domain.** Each domain supports up to 256 atom slots.
  Create multiple domains if you need more.

- **256 concurrent processes per domain.** The worker registry has 256 slots.
  Stale processes (no heartbeat for 60s) are automatically reclaimed.

- **No watches or validators.** Persistent atoms do not support `add-watch` or
  `set-validator!`. Use polling or application-level change detection.

- **`atom/atom` on JVM.** The JVM `atom/atom` function supports both heap-backed
  (no `:persistent`) and mmap-backed (with `:persistent`) atoms, matching the
  CLJS `e/atom` API.

- **Linux-optimized.** The native addon uses Linux futex for atomic waits.
  macOS and Windows work but may have different performance characteristics.

- **No transactions.** Each atom is independent. There is no STM-style
  multi-atom transaction. If you need to update two atoms atomically, combine
  them into one atom.
