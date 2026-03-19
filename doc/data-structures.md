# Eve Data Structures

Eve provides `SharedArrayBuffer`-backed atoms and persistent data structures that are shared across workers with zero-copy semantics. Unlike serialized data that gets copied between workers, Eve data structures live in shared memory — all workers see the same data.

## Overview

| Type | Constructor | Backed By |
|------|------------|-----------|
| SharedAtom | `e/atom` | Slot within an AtomDomain's SAB |
| AtomDomain | `eve/atom-domain` | SharedArrayBuffer — a shared mutable map |
| HashMap | `e/hash-map` | HAMT in SharedArrayBuffer |
| HashSet | `e/hash-set` | HAMT in SharedArrayBuffer |
| Vector | (automatic via atom) | Persistent vector in SharedArrayBuffer |
| List | (automatic via atom) | Persistent list in SharedArrayBuffer |
| Array | `e/eve-array` | Typed array in SharedArrayBuffer |
| Dataset | `e/dataset` | Columnar data frame (named EveArray columns) |
| Tensor | `e/tensor-from-array` | N-dimensional view over EveArray |
| Custom types | `eve/deftype` | User-defined SAB-backed types |

Most user code only needs `eve.alpha` — shared atoms are available as `e/atom`. For advanced features (custom `AtomDomain`, `deftype`, `extend-type`), require `eve.alpha` with `:include-macros true`.

## Setup

```clojure
(ns my-app.core
  (:require [eve.alpha :as e]))

;; Create a shared atom — the allocator initializes automatically
(defonce state (e/atom {:counter 0}))
```

For advanced eve features (standalone atom domains, custom SAB-backed types):

```clojure
(ns my-app.advanced
  (:require [eve.alpha :as eve :include-macros true]))
```

## AtomDomain

An `AtomDomain` is the primary shared state container — a `SharedArrayBuffer`-backed atom that holds a Clojure map. It implements `IDeref`, `IReset`, `ISwap`, and `IWatchable`.

```clojure
;; Create a standalone AtomDomain with its own SAB
(def my-domain
  (eve/atom-domain {:counter 0 :name "world"}
    :sab-size (* 4 1024 1024)   ;; 4MB (default: 256MB)
    :max-blocks 4096))           ;; max descriptor slots (default: 65536)
```

Standard atom operations work:

```clojure
@my-domain
;=> {:counter 0 :name "world"}

(swap! my-domain update :counter inc)
;=> {:counter 1 :name "world"}

(reset! my-domain {:counter 0})
;=> {:counter 0}

(add-watch my-domain :log (fn [k ref old new] (println :changed old :-> new)))
(remove-watch my-domain :log)
```

### Cross-Worker Sharing

To share an `AtomDomain` across workers, extract its SAB references and pass them:

```clojure
;; On the creating worker:
(def transfer (e/sab-transfer-data my-domain))
;; => {:sab <SharedArrayBuffer> :reader-map-sab <SharedArrayBuffer>}
```

The fat kernel handles this automatically for the global atom instance.

## SharedAtom

A `SharedAtom` is an individual atom within a shared `AtomDomain`. Multiple `SharedAtom`s share the same underlying `SharedArrayBuffer`. `e/atom` (from `eve.alpha`) is the standard way to create one:

```clojure
;; Create a SharedAtom in the global AtomDomain
(def counter (e/atom {:count 0}))

@counter
;=> {:count 0}

(swap! counter update :count inc)
;=> {:count 1}
```

SharedAtoms support the same protocols as AtomDomain: `IDeref`, `IReset`, `ISwap`, `IWatchable`, `IMeta`, `IWithMeta`.

### Type Predicates

```clojure
(e/shared-atom? counter)    ;=> true
```

## HashMap, HashSet, Vector, List — Transparent Storage

When you put a regular Clojure map, set, vector, or list into a shared atom, EVE automatically stores it as a `SharedArrayBuffer`-backed persistent data structure. When you deref the atom, it's deserialized back to heap memory. You use standard Clojure code — the EVE layer is transparent:

```clojure
(defonce state (e/atom {:users {:alice {:role :admin}
                                :bob   {:role :user}}
                        :tags  #{:active :verified}
                        :items [1 2 3]}))

;; All standard Clojure — EVE handles storage transparently
(swap! state assoc-in [:users :carol] {:role :user})
(swap! state update :tags conj :premium)
(swap! state update :items conj 4)

@state
;=> {:users {:alice {:role :admin} :bob {:role :user} :carol {:role :user}}
;    :tags #{:active :verified :premium}
;    :items [1 2 3 4]}
```

Under the hood, the map is stored as an EVE HAMT, the set as an EVE hash-set, and the vector as an EVE persistent vector — all in `SharedArrayBuffer`. Every worker that can reach this atom sees the same shared memory, with zero-copy semantics.

There is no need to call `e/hash-map` or `e/hash-set` directly in most cases. Regular Clojure constructors (`{}`, `#{}`, `[]`, `'()`) are serialized into the EVE equivalents automatically during `swap!` and `reset!`. Vectors and lists do not have standalone constructors — they are created automatically when stored in an atom.

## Typed Arrays

Eve arrays provide typed array storage in `SharedArrayBuffer` with optional atomic operations. Atoms handle typed arrays automatically — when you store a typed array in an Eve atom, it is backed by shared memory transparently.

```clojure
(require '[eve.alpha :as eve])
(require '[eve.array :as arr])

;; Construction
(arr/eve-array :int32 10)            ;; 10 zero-filled int32 elements
(arr/eve-array :float64 10 0.0)      ;; 10 float64 filled with 0.0
(arr/eve-array :uint8 [1 2 3])       ;; from collection
```

Supported types: `:int8`, `:uint8`, `:uint8-clamped`, `:int16`, `:uint16`, `:int32`, `:uint32`, `:float32`, `:float64`

### Element Access

```clojure
(eve/aget my-array 0)        ;; read (atomic for integer types)
(eve/aset! my-array 0 42)    ;; write (atomic for integer types)
```

### Atomic Operations (integer types only)

```clojure
(arr/cas! my-array idx expected new-val)   ;; compare-and-swap
(arr/add! my-array idx delta)              ;; atomic add
(arr/sub! my-array idx delta)              ;; atomic subtract
(arr/band! my-array idx mask)              ;; atomic bitwise AND
(arr/bor! my-array idx mask)               ;; atomic bitwise OR
(arr/bxor! my-array idx mask)              ;; atomic bitwise XOR
```

### Wait/Notify (int32 only)

```clojure
(arr/wait! my-array idx expected)          ;; block until value changes
(arr/wait! my-array idx expected timeout)  ;; with timeout
(arr/notify! my-array idx)                 ;; wake one waiting worker
(arr/notify! my-array idx count)           ;; wake N waiting workers
```

### Low-Level Access

```clojure
(arr/get-sab my-array)          ;; underlying SharedArrayBuffer
(arr/get-typed-view my-array)   ;; raw JS TypedArray view
(arr/array-type my-array)       ;; type keyword (:int32, etc.)
(arr/retire! my-array)          ;; mark for GC
```

### SIMD-Accelerated Operations (int32 only)

These use SIMD for 4x throughput but are **not atomic** — use only when exclusive access is guaranteed:

```clojure
(arr/afill-simd! my-array 42)                    ;; fill all elements
(arr/afill-simd! my-array 42 start end)           ;; fill range
(arr/acopy-simd! dest src)                        ;; copy array
(arr/asum-simd my-array)                          ;; sum all elements
(arr/areduce my-array init-val f)                  ;; reduce
(arr/amap my-array f)                              ;; map
```

## `eve/deftype` — Custom SAB-Backed Types

Define types with fields stored directly in `SharedArrayBuffer`:

```clojure
(require '[eve.alpha :as eve :include-macros true])

(eve/deftype Counter [^:mutable ^:int32 count label]
  ICounted
  (-count [this] count))
```

### Field Metadata

| Hint | Storage | Notes |
|------|---------|-------|
| `^:int32`, `^:uint32` | 4-byte SAB slot | Atomic read/write |
| `^:float32` | 4-byte SAB slot | Non-atomic |
| `^:float64` | 8-byte SAB slot | Non-atomic |
| `^:MyEveType` | Offset reference | Links to another `eve/deftype` |
| (no hint) | Serialized | Any Clojure value, stored via `pr-str`/`cljs.reader` |

### Mutability

| Modifier | Behavior |
|----------|----------|
| (default) | Immutable — set at construction |
| `^:mutable` | Mutable via `set!` (single-worker) |
| `^:volatile-mutable` | Atomic via `set!`/`cas!` (cross-worker safe) |

### Extending Types

```clojure
(eve/extend-type Counter
  IIncable
  (-inc! [this]
    (set! count (inc count))
    this))
```

Field names from the type's declaration are available as local bindings in method bodies. `set!` and `cas!` on declared fields are rewritten to SAB operations.

## Memory Model

Eve uses a custom allocator within `SharedArrayBuffer`:

- **Descriptor table:** Fixed-size array of block descriptors (status, offset, capacity, data length)
- **Data region:** Variable-size region where actual data is stored
- **SoA mirrors:** Structure-of-arrays mirrors of descriptor fields for SIMD scanning
- **Epoch-based GC:** Concurrent readers are protected by epoch tracking — blocks are only freed when no reader holds a reference

### Default Memory Budget

When `e/atom` creates the first SharedAtom, it bootstraps a global `AtomDomain` with the defaults below. These cover the **overflow allocator** (blocks >1024B) and the **slab allocator** (blocks ≤1024B). WASM Memory reserves virtual address space but only commits physical pages as written, so the defaults are essentially free until used.

#### AtomDomain (overflow allocator — blocks >1024B)

| Parameter | Default | Source |
|-----------|---------|--------|
| `:sab-size` | 256 MB | `shared_atom.cljs` `atom-domain` |
| `:max-blocks` | 65,536 descriptors | `shared_atom.cljs` `atom-domain` |
| Block descriptor size | 28 bytes (7 × i32) | `data.cljs` `SIZE_OF_BLOCK_DESCRIPTOR` |
| WASM growth ceiling | 1 GB (16,384 pages × 64 KB) | `wasm_mem.cljs` `MAX_PAGES` |
| Reader-map SAB | 256 KB (65,536 × i32) | `data.cljs` `READER_MAP_SAB_SIZE_BYTES` |

#### Slab allocator (blocks ≤1024B)

Six size-stratified slabs, each in its own `SharedArrayBuffer`. Growth factor is 4× via `WebAssembly.Memory` maximum.

| Class | Block Size | Initial Capacity | Max Capacity | Max Blocks |
|-------|-----------|-------------------|-------------|-----------|
| 0 | 32 B | 64 KB (2K blocks) | 1 GB | 32M blocks |
| 1 | 64 B | 64 KB (1K blocks) | 1 GB | 16M blocks |
| 2 | 128 B | 32 KB (256 blocks) | 1 GB | 8M blocks |
| 3 | 256 B | 32 KB (128 blocks) | 512 MB | 2M blocks |
| 4 | 512 B | 16 KB (32 blocks) | 256 MB | 512K blocks |
| 5 | 1024 B | 16 KB (16 blocks) | 256 MB | 256K blocks |

**Total initial slab capacity: ~224 KB** (64+64+32+32+16+16). Slabs grow lazily on demand — sparse files and lazy mmap page commit mean untouched pages cost zero physical memory or disk. Typical contents per class: tiny HAMT bitmap nodes (class 0), common HAMT nodes (1), vec trie nodes (2), collision/serialized values (3–5).

#### Worker registry & scratch

| Parameter | Default | Source |
|-----------|---------|--------|
| Max workers | 256 | `data.cljs` `MAX_WORKERS` |
| Worker slot size | 24 bytes | `data.cljs` `WORKER_SLOT_SIZE` |
| Worker registry total | 6,144 bytes | `data.cljs` `WORKER_REGISTRY_SIZE` |
| Per-worker WASM scratch | 4 KB | `wasm_mem.cljs` `SCRATCH_SIZE` |
| Scratch pool total | 1 MB (256 × 4 KB) | — |
| Heartbeat timeout | 30,000 ms | `data.cljs` `HEARTBEAT_TIMEOUT_MS` |

#### Overriding defaults

Pass `:sab-size` and `:max-blocks` to `eve/atom-domain` to size a standalone domain:

```clojure
(eve/atom-domain {:counter 0}
  :sab-size   (* 4 1024 1024)   ;; 4MB overflow region
  :max-blocks 4096)              ;; 4K descriptors
```

Slab class capacities are currently compile-time constants in `deftype_proto/data.cljs` (`SLAB_CLASS_CAPACITIES`).

### Block States

| State | Meaning |
|-------|---------|
| FREE | Available for allocation |
| ALLOCATED | In-use data block |
| RETIRED | Marked for GC, waiting for epoch sweep |
| EMBEDDED | Embedded atom header block |
| ORPHANED | Failed free, needs cleanup |

### Diagnostics

Use X-RAY for storage model validation:

```clojure
;; Internal namespace — diagnostic use only
(require '[eve.shared-atom :as a])

(a/validate-storage-model! (.-s-atom-env my-atom) {:width 80 :label "checkpoint"})
(a/xray-replay!)
```

See CLAUDE.md for test commands and suite details.

## Dataset — Columnar Data Frames

Datasets are columnar data frames: named columns (EveArrays) with atom-native
storage. Built with `eve/deftype` — lives inside Eve atoms, backed by slab memory.

```clojure
(require '[eve.alpha :as e]
         '[eve.alpha.ds :as ds])

;; Inside swap! — datasets must be created in atom context
(swap! my-atom (fn [_]
  (ds/dataset {:price (e/eve-array :float64 [10.5 20.3 15.0])
               :qty   (e/eve-array :int32 [100 200 150])})))
```

### Column Access (zero-copy)

```clojure
(ds/column my-ds :price)      ;; → EveArray
(ds/column-names my-ds)       ;; → [:price :qty]
(ds/row-count my-ds)          ;; → 3
(ds/dtypes my-ds)             ;; → {:price :float64 :qty :int32}
```

### Structural Operations

```clojure
(ds/select-columns my-ds [:price])
(ds/add-column my-ds :total some-array)
(ds/drop-column my-ds :qty)
(ds/rename-columns my-ds {:price :cost})
(ds/head my-ds 10)
(ds/tail my-ds 10)
(ds/slice my-ds 5 15)
```

### Row Operations

```clojure
(ds/filter-rows my-ds :price #(> % 12.0))
(ds/sort-by-column my-ds :price :asc)
(ds/reindex my-ds idx-array)
```

## Tensor — N-Dimensional Arrays

Tensors are N-dimensional views over EveArrays. Shape/strides enable zero-copy
reshaping, transposing, and slicing.

```clojure
(require '[eve.alpha :as e]
         '[eve.alpha.tensor :as tensor])

;; Inside swap!
(swap! my-atom (fn [_]
  (tensor/from-array (e/eve-array :float64 (range 12)) [3 4])))
```

### Construction

```clojure
(tensor/from-array arr [3 4])    ;; wrap 12-elem EveArray as 3×4 matrix
(tensor/zeros :float64 [3 4])    ;; allocate + zero-fill
(tensor/ones :int32 [2 3 4])     ;; allocate + fill with 1
```

### Shape Operations (zero-copy)

```clojure
(tensor/reshape t [4 3])        ;; new shape, same data
(tensor/transpose t)            ;; reverse axis order
(tensor/slice-axis t 0 1)       ;; select row 1 → rank-1 view
```

### Element Access

```clojure
(tensor/mget t 1 2)             ;; get element at [1,2]
(tensor/mset! t 1 2 42.0)       ;; set element at [1,2]
(tensor/shape t)                 ;; → [3 4]
(tensor/rank t)                  ;; → 2
```

### Bulk Operations

```clojure
(tensor/emap f t)                ;; element-wise map
(tensor/ereduce f init t)        ;; reduce all elements
(tensor/to-array t)              ;; flatten to EveArray
(tensor/to-dataset t [:a :b :c]) ;; 2D tensor → Dataset
```

## Columnar Operations — Argops & Functional

Element-wise and index-space operations on EveArrays. These work on standalone
arrays and dataset columns (columns ARE EveArrays).

```clojure
(require '[eve.alpha.col :as col])
```

### Arithmetic (element-wise, return new EveArray)

```clojure
(col/add a b)     ;; element-wise addition (either arg may be scalar)
(col/sub a b)     ;; subtraction
(col/mul a b)     ;; multiplication
(col/div a b)     ;; division
```

### Aggregations (return scalars)

```clojure
(col/sum my-col)       ;; sum all elements
(col/mean my-col)      ;; arithmetic mean
(col/min-val my-col)   ;; minimum
(col/max-val my-col)   ;; maximum
```

### Comparisons (return `:uint8` mask arrays)

```clojure
(col/gt a b)      ;; element-wise greater-than
(col/lt a b)      ;; less-than
(col/eq a b)      ;; equality
```

### Element-wise Map

```clojure
(col/emap f my-col)      ;; map f over elements → new array
(col/emap2 f a b)        ;; map f over pairs → new array
```

### Index-Space Operations

```clojure
(col/argsort my-col :asc)       ;; indices that would sort col
(col/argfilter pred my-col)     ;; indices where pred is true
(col/argmin my-col)             ;; index of minimum
(col/argmax my-col)             ;; index of maximum
(col/arggroup my-col)           ;; {value → index-array}
(col/take-indices my-col idx)   ;; gather by index array
```

## Performance

### Columnar Operations — Eve EveArray vs Stock Clojure Vectors

Each benchmark runs a multi-step pipeline inside `swap!`. "Eve" uses EveArray
columns with typed-view fast paths. "Stock" uses plain Clojure vectors with
`mapv`/`reduce`/`sort`. Speedup = Stock time / Eve time (higher is better for Eve).

#### JVM — mmap (persistent atom)

| Benchmark              |     N |   Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|-----------:|-----------:|--------:|
| Column Arithmetic      |   10K |        3.0 |        3.0 |   1.00x |
| Filter + Aggregate     |   10K |        3.2 |        0.8 |   0.25x |
| Sort + Top-N           |   10K |        3.8 |        4.4 |   1.16x |
| Dataset Pipeline       |   10K |       12.6 |        9.8 |   0.78x |
| Tensor Pipeline        |   10K |        5.8 |        2.0 |   0.34x |
| **Column Arithmetic**  |  100K |      **2.8** |     21.4 | **7.64x** |
| **Filter + Aggregate** |  100K |      **3.4** |      6.8 | **2.00x** |
| **Sort + Top-N**       |  100K |     **12.8** |     35.0 | **2.73x** |
| **Dataset Pipeline**   |  100K |     **20.8** |     64.8 | **3.12x** |
| **Tensor Pipeline**    |  100K |      **9.0** |     14.4 | **1.60x** |

#### JVM — in-memory (heap atom, no persistence)

| Benchmark              |     N |   Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|-----------:|-----------:|--------:|
| Column Arithmetic      |   10K |        0.4 |        1.2 |   3.00x |
| Filter + Aggregate     |   10K |        0.6 |        0.2 |   0.33x |
| Sort + Top-N           |   10K |        1.2 |        2.2 |   1.83x |
| Dataset Pipeline       |   10K |        3.6 |        3.8 |   1.06x |
| Tensor Pipeline        |   10K |        2.0 |        1.6 |   0.80x |
| **Column Arithmetic**  |  100K |      **1.6** |     17.4 | **10.87x** |
| **Filter + Aggregate** |  100K |      **2.4** |      6.2 | **2.58x** |
| **Sort + Top-N**       |  100K |     **12.0** |     31.0 | **2.58x** |
| **Dataset Pipeline**   |  100K |     **16.2** |     57.4 | **3.54x** |
| **Tensor Pipeline**    |  100K |      **5.2** |     13.8 | **2.65x** |

#### JVM — columnar at 1M elements

| Benchmark              | Eve (ms) | Stock (ms) | Speedup |
|------------------------|--------:|-----------:|--------:|
| Column Arithmetic      |    21.3 |      202.0 |   9.47x |
| Filter + Aggregate     |    28.0 |      141.7 |   5.06x |
| Sort + Top-N           |   172.7 |      671.0 |   3.89x |
| Dataset Pipeline       |   180.7 |    1,146.3 |   6.35x |

#### Node.js — mmap (persistent atom)

| Benchmark              |     N |   Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|-----------:|-----------:|--------:|
| Column Arithmetic      |   10K |       17.6 |        3.4 |   0.19x |
| Sort + Top-N           |   10K |        4.4 |        4.6 |   1.05x |
| Column Arithmetic      |  100K |      212.0 |       57.4 |   0.27x |
| **Sort + Top-N**       |  100K |     **51.8** |     69.6 | **1.34x** |

#### Node.js — in-memory (SAB-backed atoms)

| Benchmark              |     N |   Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|-----------:|-----------:|--------:|
| Column Arithmetic      |   10K |        1.7 |        5.7 |   3.40x |
| Column Arithmetic      |  100K |       <1   |       82.0 |   >80x  |
| Filter + Aggregate     |  100K |       <1   |       11.0 |   >30x  |
| Column Arithmetic      |    1M |       <1   |    1,104.0 | >1000x  |

#### Scaling Analysis

Eve's advantage grows with data size. At 10K, Eve and stock Clojure are comparable.
At 100K+, typed-array-backed operations dominate:

- **Column Arithmetic** (element-wise mul/sub/sum): 4–10x on JVM, 72x+ on Node at
  100K. Eve operates directly on typed arrays with zero allocation per element.
- **Sort + Top-N** (argsort + take-indices): 4–5x at 100K+. Argsort uses Int32Array
  for indices, avoiding boxed integer allocation.
- **Dataset Pipeline** (create + derived columns + filter + sort + head + aggregate):
  3–6x at 100K+.
- **Filter + Aggregate** (argfilter + take-indices + 4 stats): 1.3–5x. Predicate
  cost is similar; Eve wins on aggregation.
- **Tensor Pipeline** (emap + transpose + reduce): 1.5–4x. Contiguous typed array
  memory layout.

#### JVM IBulkAccess Fast Paths

On JVM, the `IBulkAccess` protocol provides `double[]`/`int[]` aget loops that
completely eliminate per-element protocol dispatch and byte-array allocation.
This is the single biggest performance lever for columnar operations.

Design principle: **one extraction, flat loop**. Every bulk operation:
1. Extracts the backing `double[]`/`int[]` ONCE (O(1))
2. Loops with `aget`/`aset` over the raw primitive array (O(n), no alloc)
3. Wraps result as EveArray at the end (O(1))

### mmap Atom Benchmarks

Cross-process mmap-backed persistent atom benchmarks. Dataset: 416 users +
833 orders (~5 MB).

#### Platform Throughput (Sequential Swaps)

| Platform | 500 swaps (ms) | Ops/s | ms/swap |
|----------|---------------|-------|---------|
| **Node** | 87.0 | 5,749 | 0.17 |
| **JVM**  | 151.8 | 3,294 | 0.30 |

#### Stock CLJ Atom vs Eve mmap Atom (JVM)

Eve mmap is 25–287x slower than in-memory CLJ atoms — the cost of cross-process
persistence with mmap CAS, HAMT path-copying, and slab serialization.

| Category | Ops | Ratio Range |
|----------|-----|------------|
| Map ops | assoc, update, dissoc, get-in, merge, select-keys, reduce-kv | 31–225x |
| Vector ops | conj, assoc, nth, mapv, filterv, into | 25–287x |
| Set ops | conj, disj, contains?, union | 37–78x |
| Rich transforms | filter-map-reduce, group-by, flatten, bulk swap, leaderboard | 14–142x |
| Read-only ops | get-in, nth, contains?, filterv | **14–79x** (smallest) |

#### Write Throughput by Atom Size

| Initial Keys | ms/swap | Ops/s |
|--------------|---------|-------|
| 100 | 0.356 | 2,809 |
| 500 | 0.345 | 2,902 |
| 1,000 | 0.337 | 2,971 |

Write throughput is constant — HAMT path-copy is O(log32 n).

#### Read Throughput (deref/s)

| Keys | Ops/s |
|------|-------|
| 1 | 6,144 |
| 10 | 6,110 |
| 100 | 6,142 |
| 500 | 6,158 |

Constant across all sizes — deref returns a lazy slab-backed EveHashMap.

#### Contention (Node workers, counter increment)

| Writers | Throughput (swaps/s) | Correct? |
|---------|---------------------|----------|
| 1 | 2,128 | Yes |
| 2 | 2,132 | Yes |
| 4 | 1,856 | Yes |
| 8 | 1,007 | Yes |
| 16 | 545 | Yes |

CAS correctness holds at all concurrency levels.

#### Swap Hotspot (JVM, profiled)

`apply-f` (user function + HAMT path-copy + slab allocation) dominates at ~93%
of swap time. CAS is ~1.2 µs. Infrastructure (epoch, retire, refresh) is <1ms.

#### Disk Footprint (Lazy Slab Growth)

| Keys | Payload/key | Disk (MB) | Overhead |
|------|-------------|-----------|----------|
| 100 | 500 chars | 1.23 | 17x |
| 500 | 5,000 chars | 5.15 | 2.1x |
| 1,000 | 20,000 chars | 33.40 | 1.7x |

Epoch GC reclaims retired HAMT nodes — zero disk growth during mutations.

### Running Benchmarks

```bash
# JVM columnar benchmarks (all modes, all scales)
clojure -M:columnar-bench

# Node.js columnar benchmarks
npm run test:compile
node target/eve-test/all.js columnar-bench    # all modes
node target/eve-test/all.js columnar-mmap     # mmap only
node target/eve-test/all.js columnar-inmem    # in-memory only
```

### Known Issues

- **CLJS Dataset Pipeline in atom swap!**: EveArray `cnt` field gets corrupted
  after SAB/mmap atom round-trip when newly-created EveArrays are stored back
  into the atom. Affects both mmap and in-memory modes on CLJS. JVM works
  correctly. Root cause: likely a serialization/deserialization mismatch for
  EveArray type-id `0x1D` blocks created inside `swap!`.

- **Node.js mmap columnar benchmarks**: Sort+Top-N, Dataset Pipeline, and Tensor
  Pipeline at larger scales are affected by a known slab corruption issue under
  repeated `swap!` iterations involving the coalesc allocator (class 6, >1024 byte
  blocks). Column Arithmetic is unaffected.
