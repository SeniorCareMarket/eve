# Eve

Shared-memory persistent data structures for ClojureScript and Clojure.

Eve provides persistent maps, vectors, sets, and lists backed by
SharedArrayBuffer (in-process) or memory-mapped files (cross-process).
JVM Clojure and Node.js ClojureScript processes can share and atomically
mutate Clojure data structures via mmap files on disk.

## Features

- **O(log32 N) persistent collections** — 32-way branching HAMT maps, vectors, sets, and lists in shared memory. A 1-billion-key map is only 6 levels deep. Swap latency is constant regardless of atom size: JVM p50 ~1.5–3.5 ms, Node p50 ~0.2 ms — from an 11 MB atom (1,800 keys) through a 1.1 GB atom (25,000 keys).
- **Exabyte-scale durable atoms** — atoms backed by memory-mapped sparse files that grow lazily from kilobytes to terabytes. Data survives process restarts — no export/import step.
- **Cross-process, uncoordinated** — multiple JVM and Node.js processes mutate the same atom concurrently via lock-free CAS on a single 32-bit root pointer. No coordination server, no locks, no leader election.
- **In-browser shared memory** — `SharedArrayBuffer`-backed atoms let web workers share and atomically mutate persistent data structures without `postMessage` serialization.
- **Three platforms, one format** — browser (CLJS), Node.js (CLJS), and JVM (Clojure) all use identical on-disk/in-memory layouts, hash functions, and CAS protocols. A domain created by one platform can be joined by any other.
- **Epoch-based GC** — cooperative garbage collection ensures old HAMT nodes are freed only after every reader has moved past them. No stop-the-world pauses.
- **Zero-copy reads** — `deref` walks mmap'd/SAB memory directly. No deserialization into intermediate heap objects.

## Performance

Swap latency (p50, no contention) on persistent mmap-backed atoms.
Measured on Linux, JDK 21, Node 18.

```
Atom Size     Keys      Depth   JVM swap p50   Node swap p50
─────────────────────────────────────────────────────────────
  11 MB       1,801       3      1.53 ms        0.16 ms
 114 MB      18,801       4      2.59 ms        0.34 ms
 1.1 GB      25,001       4      3.55 ms        0.22 ms
```

Swap latency stays flat because each `swap!` only path-copies O(log32 N)
HAMT nodes — the rest of the tree is structurally shared. A 114 MB atom
with 18,801 keys is 4 levels deep; a 1-billion-key atom would be 6.

```
  Swap latency vs atom size (p50, log scale)

  ms
  10 ┤
     │
   1 ┤  ■──────────────────■───────────────■  JVM  (~1.5–3.5 ms)
     │
 0.1 ┤  ●──────────────────●───────────────●  Node (~0.2 ms)
     │
0.01 ┤
     └──┬───────────────────┬───────────────┬──
       11 MB             114 MB           1.1 GB
```

Cross-process contention (4 JVM threads + 4 Node processes, shared `:counter`):

```
Atom Size     Throughput    Counter     Result
──────────────────────────────────────────────
  11 MB        924 ops/s    400/400     CORRECT
 114 MB        462 ops/s    400/400     CORRECT
 1.1 GB        546 ops/s    400/400     CORRECT
```

## Usage

### As a dependency

Add eve to both `deps.edn` (for ClojureScript source) and `package.json`
(for the native mmap addon):

```clojure
;; deps.edn
eve/eve {:git/url "https://github.com/SeniorCareMarket/eve"
         :git/sha "0e084fff"}
```

```json
// package.json — native addon (builds automatically on npm install)
{
  "dependencies": {
    "eve-native": "github:SeniorCareMarket/eve"
  }
}
```

The native addon is only needed for cross-process persistent atoms (mmap).
In-process SharedArrayBuffer atoms work without it.

### In-process atoms

Every platform has a heap-backed in-process mode — no files on disk, no native addon required.

| Platform | Backing |
|---|---|
| Browser (CLJS) | `SharedArrayBuffer` — shared across web workers via `Atomics` CAS |
| Node.js (CLJS) | `SharedArrayBuffer` — same as browser |
| JVM (Clojure) | `byte[]` + `sun.misc.Unsafe` atomics — heap-allocated, single-process |

```clojure
(require '[eve.alpha :as e])

;; Create an in-process atom (slab allocator auto-initializes)
(def my-atom (e/atom {:key "value"}))

(swap! my-atom assoc :count 42)
@my-atom ;; => {:key "value", :count 42}

;; Named atoms — same :id returns the same atom across threads/workers
(def state (e/atom ::app-state {:counter 0}))
(swap! state update :counter inc)
```

### Cross-process persistent atoms (Node.js + JVM)

The same `e/atom` function supports mmap-backed persistence. Pass `:persistent`
in the config map to store data on disk, shareable across processes.
The native addon is auto-loaded via `node-gyp-build`.

**Process A** (Node.js or JVM — same API on both):

```clojure
(require '[eve.alpha :as e])

;; Create a persistent atom backed by mmap files at ./my-db/
(def counter (e/atom {:id ::counter :persistent "./my-db"} 0))

(swap! counter inc)
@counter ;; => 1
```

**Process B** (Node.js or JVM — joins the same atom):

```clojure
(require '[eve.alpha :as e])

;; Same :id + path — detects the existing atom and loads its current value
(def counter (e/atom {:id ::counter :persistent "./my-db"} 0))

@counter ;; => 1  (sees Process A's write)
(swap! counter inc)
@counter ;; => 2
```

Both platforms use identical on-disk formats — JVM and Node.js processes
share the same atom simultaneously with full CAS semantics.

## Building

```bash
# Install dependencies (also builds native addon via postinstall)
npm install

# Compile tests
npx shadow-cljs compile eve-test

# Run tests
node target/eve-test/all.js all
```

## Documentation

| Document | Description |
|---|---|
| [getting-started.md](doc/getting-started.md) | Installation, first atom, basic usage |
| [api-guide.md](doc/api-guide.md) | Complete public API reference |
| [persistent-atoms.md](doc/persistent-atoms.md) | Cross-process persistent atoms — quick start, CAS semantics, API |
| [data-structures.md](doc/data-structures.md) | Eve data structures — atoms, maps, sets, vectors, lists, typed arrays |
| [collections.md](doc/collections.md) | Specialized collections — IntMap, sorted set, PATRICIA trie |
| [obj.md](doc/obj.md) | `eve/obj` typed shared objects — AoS/SoA layouts, schemas, atomic ops |
| [architecture.md](doc/architecture.md) | System design, memory model, slab allocator, CAS loop, epoch GC |
| [internals.md](doc/internals.md) | Deep dive — slab allocator, serialization format, HAMT, native addon |
| [platform-support.md](doc/platform-support.md) | Platform requirements and JVM vs Node.js differences |
| [testing.md](doc/testing.md) | Test suites, how to run them, what they cover |

## License

MIT
