# Getting Started with Eve

Eve is a shared-memory persistent data structure library for ClojureScript and Clojure. It provides persistent maps, vectors, sets, and lists backed by `SharedArrayBuffer` (in-process) or memory-mapped files (cross-process). JVM Clojure and Node.js ClojureScript processes can share and atomically mutate Clojure data structures via mmap files on disk.

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Node.js | 18+ | Required for ClojureScript runtime |
| Java | 21+ | Required for JVM persistent atoms (Panama FFM) |
| Clojure CLI | 1.11+ | `clojure` / `clj` commands |
| npm | Any recent | Comes with Node.js |
| C++ compiler | C++20 capable | GCC 5+, Clang 3.4+, Apple Clang 6+ |

The C++ compiler is only needed for the native addon (cross-process mmap atoms). In-process `SharedArrayBuffer` atoms work without it.

---

## Installation

### As a dependency

Add Eve to both `deps.edn` (for ClojureScript source) and `package.json` (for the native mmap addon):

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

The native addon is only needed for cross-process persistent atoms. In-process `SharedArrayBuffer` atoms work without it.

### From source

```bash
git clone https://github.com/SeniorCareMarket/eve.git
cd eve

# Install dependencies (also builds native addon via postinstall)
npm install

# Compile tests to verify the build
npx shadow-cljs compile eve-test

# Run all tests
node target/eve-test/all.js all
```

### shadow-cljs configuration

Eve uses shadow-cljs for ClojureScript compilation. Your `shadow-cljs.edn` should include the Eve source path and the `:node-test` alias from `deps.edn`:

```clojure
{:deps {:aliases [:node-test]}
 :builds
 {:my-app
  {:target :node-script
   :main my-app.core/main
   :output-to "target/my-app.js"}}}
```

---

## Your First Eve Atom

### In-Process (SharedArrayBuffer)

The simplest way to use Eve is with in-process `SharedArrayBuffer`-backed atoms. These are fast, zero-copy, and work across web workers or Node.js worker threads.

```clojure
(ns my-app.core
  (:require [eve.alpha :as e]))

;; Create a SharedArrayBuffer-backed atom
;; The slab allocator auto-initializes on first use
(def my-atom (e/atom {:key "value"}))

;; Standard Clojure atom operations — Eve handles storage transparently
(swap! my-atom assoc :count 42)
@my-atom ;; => {:key "value", :count 42}

;; Named atoms — same :id returns the same atom
(def state (e/atom ::app-state {:counter 0}))
(swap! state update :counter inc)
```

All standard Clojure data structures — maps, vectors, sets, lists — are automatically serialized into Eve's `SharedArrayBuffer`-backed equivalents during `swap!` and `reset!`, and deserialized back on `deref`. You use normal Clojure code; the Eve layer is transparent.

```clojure
(def app (e/atom {:users {:alice {:role :admin}}
                  :tags  #{:active :verified}
                  :items [1 2 3]}))

(swap! app assoc-in [:users :bob] {:role :user})
(swap! app update :tags conj :premium)
(swap! app update :items conj 4)

@app
;; => {:users {:alice {:role :admin}, :bob {:role :user}}
;;     :tags #{:active :verified :premium}
;;     :items [1 2 3 4]}
```

### Cross-Process (Persistent Atoms)

Persistent atoms store data on disk via memory-mapped files. Multiple OS processes — JVM Clojure and Node.js ClojureScript — can share and atomically mutate the same data with full CAS semantics.

**Process A** (Node.js or JVM):

```clojure
(require '[eve.alpha :as e])

;; Create a persistent atom backed by mmap files at ./my-db/
(def counter (e/atom {:id ::counter :persistent "./my-db"} {:count 0}))

(swap! counter update :count inc)
@counter ;; => {:count 1}
```

**Process B** (Node.js or JVM — joins the same atom):

```clojure
(require '[eve.alpha :as e])

;; Same :id + path — detects the existing atom and loads its current value
(def counter (e/atom {:id ::counter :persistent "./my-db"} {:count 0}))

@counter ;; => {:count 1}  (sees Process A's write)
(swap! counter update :count inc)
@counter ;; => {:count 2}
```

Both platforms use identical on-disk formats. No coordination server is needed — the mmap'd files on disk are the shared medium.

### Cross-Worker Sharing (SharedArrayBuffer)

To share an `AtomDomain` across web workers or Node.js worker threads:

```clojure
;; On the creating worker:
(def my-domain (eve/atom-domain {:counter 0}))
(def transfer (eve/sab-transfer-data my-domain))
;; => {:sab <SharedArrayBuffer> :reader-map-sab <SharedArrayBuffer>}

;; Send `transfer` to the worker via postMessage

;; On the receiving worker:
(eve/init-eve-on-worker! transfer)
```

---

## Supported Value Types

Eve atoms accept any standard Clojure/ClojureScript value:

| Type | Notes |
|---|---|
| Maps (`{}`) | Stored as HAMT with structural sharing |
| Vectors (`[]`) | Stored as persistent trie |
| Sets (`#{}`) | Stored as HAMT |
| Lists (`'()`) | Stored as linked nodes |
| Strings | Serialized as scalar blocks |
| Numbers (int, float) | Serialized as scalar blocks |
| Keywords | Serialized as scalar blocks |
| Symbols | Serialized as scalar blocks |
| UUIDs | Serialized as scalar blocks |
| Dates | Serialized as scalar blocks |
| `nil` | Represented as `NIL_OFFSET` (-1) |
| Booleans | Serialized as scalar blocks |
| Nested collections | Arbitrary nesting depth |

---

## Specialized Collections

For most use cases, standard Clojure data structures are sufficient — Eve converts them transparently. When you need specific capabilities beyond standard maps and sets, Eve provides:

- **Integer Map** (`eve.deftype.int-map`): O(log32 n) lookups by integer key, efficient `merge-with`, range queries. A PATRICIA trie implementation.
- **Sorted Set** (`eve.deftype.rb-tree`): O(log n) sorted traversal, membership testing, ordered iteration. A red-black tree implementation.

```clojure
(require '[eve.deftype.int-map :as im])
(require '[eve.deftype.rb-tree :as rb])

(def state (e/atom {:index (im/int-map)
                    :tags  (rb/sorted-set)}))

(swap! state update :index assoc 1 :a)
(swap! state update :tags conj "clojure")
```

---

## Next Steps

- [API Guide](api-guide.md) — complete API reference
- [Architecture](architecture.md) — system design and memory model
- [Internals](internals.md) — slab allocator, serialization, HAMT details
- [Testing](testing.md) — test suites and how to run them
- [Platform Support](platform-support.md) — JVM, Node.js, browser compatibility
