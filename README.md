# Eve

Shared-memory persistent data structures for ClojureScript and Clojure.

Eve provides persistent maps, vectors, sets, and lists backed by
SharedArrayBuffer (in-process) or memory-mapped files (cross-process).
JVM Clojure, Node.js ClojureScript, and Babashka processes can share and
atomically mutate Clojure data structures via mmap files on disk.

## Features

- **O(log32 N) persistent collections** — 32-way branching HAMT maps, vectors, sets, and lists in shared memory. A 1-billion-key map is only 6 levels deep. Swap latency is constant regardless of atom size: JVM p50 ~1 ms, Node p50 ~0.16 ms, bb p50 ~1.4 ms — identical from a 12 MB atom (1,800 keys) through a 1 GB atom (150,000 keys).
- **Exabyte-scale durable atoms** — atoms backed by memory-mapped sparse files that grow lazily from kilobytes to terabytes. Data survives process restarts — no export/import step.
- **Cross-process, uncoordinated** — multiple JVM, Node.js, and Babashka processes mutate the same atom concurrently via lock-free CAS on a single 32-bit root pointer. No coordination server, no locks, no leader election.
- **In-browser shared memory** — `SharedArrayBuffer`-backed atoms let web workers share and atomically mutate persistent data structures without `postMessage` serialization.
- **Four platforms, one format** — browser (CLJS), Node.js (CLJS), JVM (Clojure), and Babashka all use identical on-disk/in-memory layouts, hash functions, and CAS protocols. A domain created by one platform can be joined by any other.
- **Epoch-based GC** — cooperative garbage collection ensures old HAMT nodes are freed only after every reader has moved past them. No stop-the-world pauses.
- **Zero-copy reads** — `deref` walks mmap'd/SAB memory directly. No deserialization into intermediate heap objects.

## Performance

Swap latency (p50, no contention) on persistent mmap-backed atoms.
Measured on Linux, JDK 21, Node 18, Babashka 1.x.

```
Atom Size     Keys      Depth   JVM swap p50   Node swap p50   bb swap p50
──────────────────────────────────────────────────────────────────────────
  11 MB       2,812       3      0.71 ms        0.09 ms         0.38 ms
 103 MB      17,428       4      0.75 ms        0.08 ms         0.43 ms
 1.1 GB      62,012       4      0.82 ms        0.07 ms         0.41 ms
```

Swap latency stays flat because each `swap!` only path-copies O(log32 N)
HAMT nodes — the rest of the tree is structurally shared. A 103 MB atom
with 17,428 keys is 4 levels deep; a 1-billion-key atom would be 6.

```
  Swap latency vs atom size (p50, log scale)

  ms
  10 ┤
     │
   1 ┤  ■──────────────────■───────────────■  JVM  (~0.7–0.8 ms)
     │  ▲──────────────────▲───────────────▲  bb   (~0.4 ms)
 0.1 ┤  ●──────────────────●───────────────●  Node (~0.08 ms)
     │
0.01 ┤
     └──┬───────────────────┬───────────────┬──
       11 MB             103 MB           1.1 GB
```

Cross-process contention (2 JVM threads + 2 Node processes + 2 bb processes,
shared `:counter`):

```
Atom Size     Throughput    Counter     Result
──────────────────────────────────────────────
  2.4 MB       663 ops/s    300/300     CORRECT
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
| Babashka | mmap files only (no in-process heap mode) |

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

All platforms use identical on-disk formats — JVM, Node.js, and Babashka
processes share the same atom simultaneously with full CAS semantics.

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
