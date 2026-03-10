# Eve

Shared-memory persistent data structures for ClojureScript and Clojure.

Eve provides persistent maps, vectors, sets, and lists backed by
SharedArrayBuffer (in-process) or memory-mapped files (cross-process).
JVM Clojure and Node.js ClojureScript processes can share and atomically
mutate Clojure data structures via mmap files on disk.

## Features

- **Persistent collections** — HAMT maps, vectors, sets, lists in shared memory
- **Cross-process atoms** — mmap-backed atoms with CAS semantics
- **JVM interop** — JVM Clojure and Node.js share data structures
- **Epoch GC** — cooperative garbage collection for slab-allocated blocks
- **Zero-copy reads** — deref without serialization overhead

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

### In-process atoms (SharedArrayBuffer)

```clojure
(require '[eve.alpha :as e])

;; Create a SharedArrayBuffer-backed atom (slab allocator auto-initializes)
(def my-atom (e/atom {:key "value"}))

(swap! my-atom assoc :count 42)
@my-atom ;; => {:key "value", :count 42}

;; Named atoms — same :id returns the same atom across threads/workers/processes
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
| [persistent-atoms.md](doc/persistent-atoms.md) | Cross-process persistent atoms — quick start, CAS semantics, API |
| [data-structures.md](doc/data-structures.md) | Eve data structures — atoms, maps, sets, vectors, lists, typed arrays |
| [collections.md](doc/collections.md) | Specialized collections — IntMap, sorted set, PATRICIA trie |
| [obj.md](doc/obj.md) | `eve/obj` typed shared objects — AoS/SoA layouts, schemas, atomic ops |

## License

MIT
