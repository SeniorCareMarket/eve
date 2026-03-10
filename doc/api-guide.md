# Eve API Guide

Complete reference for Eve's public API surface.

---

## Namespaces

| Namespace | Platform | Purpose |
|---|---|---|
| `eve.alpha` | CLJS + CLJ | Public API entry point |
| `eve.atom` | CLJS + CLJ | Cross-process mmap atom (lower-level) |
| `eve.shared-atom` | CLJS only | SAB-backed atom internals |
| `eve.obj` | CLJS only | Typed shared objects (AoS/SoA) |
| `eve.array` | CLJS only | Typed array API |
| `eve.deftype.int-map` | CLJS only | Integer map (PATRICIA trie) |
| `eve.deftype.rb-tree` | CLJS only | Sorted set (red-black tree) |

---

## eve.alpha (Public Entry Point)

```clojure
(ns my-app.core
  (:require [eve.alpha :as e]))
```

### Atom Creation

#### `e/atom` — Unified atom constructor

Dispatches between SAB-backed (in-memory) and mmap-backed (persistent) atoms based on the presence of `:persistent` in the config.

```clojure
;; --- In-memory (SAB-backed on CLJS, heap-backed on JVM) ---

;; Anonymous atom
(e/atom {:count 0})

;; Named atom (keyword shorthand)
(e/atom ::counter {:count 0})

;; Named atom (config map)
(e/atom {:id ::counter} {:count 0})

;; --- Persistent (mmap-backed, disk files) ---

;; Named persistent atom, explicit domain path
(e/atom {:id ::counter :persistent "./my-db"} {:count 0})

;; Named persistent atom, default domain (./eve/)
(e/atom {:id ::counter :persistent true} {:count 0})
```

**Parameters:**
- First arg: either a keyword (shorthand for `{:id kw}`), a config map, or omitted (anonymous)
- Second arg: initial value (any Clojure value)

**Config map keys:**
- `:id` — namespace-qualified keyword (e.g., `::counter`, `:my.app/state`). Required for persistent atoms.
- `:persistent` — `true` (default domain at `./eve/`) or a string path (`"./my-db"`)

**Returns:** An atom implementing `IDeref`, `IReset`, `ISwap`, `IWatchable` (SAB), or `IDeref`, `IReset`, `ISwap` (persistent).

All atoms support `@`, `swap!`, `reset!`.

#### `e/atom-domain` — Standalone AtomDomain (CLJS)

Creates a standalone `SharedArrayBuffer`-backed `AtomDomain` with its own memory region.

```clojure
(def my-domain
  (eve/atom-domain {:counter 0 :name "world"}
    :sab-size (* 4 1024 1024)   ;; 4MB (default: 256MB)
    :max-blocks 4096))           ;; max descriptor slots (default: 65536)
```

Standard atom operations work on an `AtomDomain`:

```clojure
@my-domain                                    ;; deref
(swap! my-domain update :counter inc)         ;; swap
(reset! my-domain {:counter 0})               ;; reset
(add-watch my-domain :log (fn [k r o n] ...)) ;; watch
(remove-watch my-domain :log)                 ;; unwatch
```

### Data Structure Constructors

These are rarely needed — standard Clojure literals (`{}`, `[]`, `#{}`, `'()`) are automatically converted to Eve types when stored in an atom.

```clojure
(e/hash-map :a 1 :b 2)      ;; Eve HAMT map
(e/empty-hash-map)           ;; Empty Eve HAMT map
(e/hash-set :a :b :c)        ;; Eve hash set
(e/empty-hash-set)           ;; Empty Eve hash set
```

### Typed Array Access

```clojure
(e/aget my-array 0)          ;; Read element (atomic for integer types)
(e/aset! my-array 0 42)     ;; Write element (atomic for integer types)
(e/get-typed-view my-array)  ;; Get raw JS TypedArray view
```

### Type Predicates

```clojure
(eve/atom-domain? my-domain)  ;; true if AtomDomain
(eve/shared-atom? counter)    ;; true if SharedAtom
```

### Cross-Worker Support (CLJS)

```clojure
;; Extract SAB references for cross-worker transfer
(eve/sab-transfer-data my-domain)
;; => {:sab <SharedArrayBuffer> :reader-map-sab <SharedArrayBuffer>}

;; Initialize Eve on a receiving worker
(eve/init-eve-on-worker! transfer-data)
```

### Macros (CLJS — requires `:include-macros true`)

```clojure
(ns my-app.advanced
  (:require [eve.alpha :as eve :include-macros true]))

;; Define a SAB-backed type
(eve/deftype Counter [^:mutable ^:int32 count label]
  ICounted
  (-count [this] count))

;; Extend protocols on an existing eve/deftype
(eve/extend-type Counter
  IMyProtocol
  (-my-method [this] ...))
```

---

## eve.atom (Persistent Atoms — JVM + CLJS)

Lower-level API for persistent (mmap-backed) atoms. Most users should use `e/atom` instead.

```clojure
(require '[eve.atom :as atom])
```

### `atom/atom` (JVM)

The JVM version of `atom` dispatches between heap-backed and mmap-backed:

```clojure
;; Heap-backed (in-memory, no files)
(atom/atom ::counter {:count 0})     ;; named
(atom/atom {:id ::counter} {:count 0}) ;; config map
(atom/atom {:count 0})                 ;; anonymous

;; Persistent (mmap-backed)
(atom/atom {:id ::counter :persistent "./my-db"} {:count 0})
```

### `atom/persistent-atom`

Explicit persistent atom constructor:

```clojure
(atom/persistent-atom {:id ::counter :persistent "./my-db"} {:count 0})
(atom/persistent-atom ::counter {:count 0})  ;; default domain
(atom/persistent-atom {:count 0})            ;; anonymous
```

### `atom/persistent-atom-domain`

Opens or creates a domain. Caches by path — repeated calls return the same domain.

```clojure
(def d (atom/persistent-atom-domain "./my-db"))
```

### `atom/close-atom-domain!`

Releases the worker slot and cancels the heartbeat timer.

```clojure
(atom/close-atom-domain! domain)
```

Also available as `e/close-atom-domain!` via `eve.alpha`.

---

## eve.obj — Typed Shared Objects (CLJS)

Schema-based typed objects backed by `SharedArrayBuffer`. Two layouts:

- **`eve-obj`** (AoS — Array of Structures): Single object with named typed fields.
- **`eve-obj-array`** (SoA — Structure of Arrays): Collection of objects stored column-wise.

```clojure
(require '[eve.obj :as obj])
```

### Schemas

```clojure
(def node-schema
  (obj/create-schema {:key   :int32
                      :left  :int32
                      :right :int32
                      :value :float64}))
```

**Supported field types:** `:int8`, `:uint8`, `:int16`, `:uint16`, `:int32`, `:uint32`, `:float32`, `:float64`, `:obj` (offset reference), `:array` (offset reference).

Integer types (`:int32`, `:uint32`) support atomic operations. Fields are sorted by alignment (descending) for optimal memory packing.

### Single Objects

```clojure
;; Create inside atom transaction
(swap! state assoc :tree
  (obj/obj node-schema {:key 42 :left -1 :right -1 :value 3.14}))

;; Field access (ILookup, IFn, ISeqable)
(:key node)           ;; => 42
(node :key)           ;; => 42
(seq node)            ;; => ([:key 42] [:value 3.14] ...)

;; Mutation
(obj/assoc! node :key 100)

;; Atomic operations (int32/uint32 fields only)
(obj/cas! node :key 100 200)      ;; compare-and-swap, returns true/false
(obj/add! node :key 10)           ;; atomic add, returns old value
(obj/sub! node :key 5)            ;; atomic subtract, returns old value
(obj/exchange! node :key 999)     ;; atomic exchange, returns old value
```

### Object Arrays (SoA)

```clojure
;; Create array of N objects
(swap! state assoc :particles
  (obj/obj-array 1000 {:x :int32 :y :int32 :velocity :int32}))

;; Element access
(obj/assoc-in! data [0 :x] 42)
(obj/get-in data [0 :x])          ;; => 42
(nth data 0)                       ;; row view

;; Column access (SIMD/batch)
(obj/column data :x)               ;; raw Int32Array
(obj/column-reduce data :x 0 (fn [acc idx val] (+ acc val)))
(obj/column-map! data :x (fn [idx val] (* val 2)))
```

---

## eve.array — Typed Arrays (CLJS)

Typed array storage in `SharedArrayBuffer` with optional atomic operations.

```clojure
(require '[eve.array :as arr])
```

### Construction

```clojure
(arr/eve-array :int32 10)            ;; 10 zero-filled int32 elements
(arr/eve-array :float64 10 0.0)      ;; 10 float64 filled with 0.0
(arr/eve-array :uint8 [1 2 3])       ;; from collection
```

**Supported types:** `:int8`, `:uint8`, `:uint8-clamped`, `:int16`, `:uint16`, `:int32`, `:uint32`, `:float32`, `:float64`

### Element Access

```clojure
(arr/aget my-array 0)        ;; read (atomic for integer types)
(arr/aset! my-array 0 42)   ;; write (atomic for integer types)
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
(arr/wait! my-array idx expected timeout)  ;; with timeout (ms)
(arr/notify! my-array idx)                 ;; wake one waiting worker
(arr/notify! my-array idx count)           ;; wake N waiting workers
```

### Utility

```clojure
(arr/get-sab my-array)          ;; underlying SharedArrayBuffer
(arr/get-typed-view my-array)   ;; raw JS TypedArray view
(arr/array-type my-array)       ;; type keyword (:int32, etc.)
(arr/retire! my-array)          ;; mark for GC
```

### SIMD-Accelerated Operations (int32 only)

Not atomic — use only with exclusive access:

```clojure
(arr/afill-simd! my-array 42)                    ;; fill all elements
(arr/afill-simd! my-array 42 start end)           ;; fill range
(arr/acopy-simd! dest src)                        ;; copy array
(arr/asum-simd my-array)                          ;; sum all elements
(arr/areduce-simd my-array init-val f)            ;; SIMD reduce
(arr/amap-simd my-array f)                        ;; SIMD map
```

---

## eve.deftype.int-map — Integer Map (CLJS)

16-way branching PATRICIA trie with 32-bit integer keys.

```clojure
(require '[eve.deftype.int-map :as im])
```

### Construction and Use

```clojure
(def state (e/atom {:data (im/int-map)}))

;; Standard map operations inside swap!
(swap! state update :data assoc 1 :a)
(swap! state update :data assoc 2 :b)

;; Full 32-bit key range (including negative keys)
(swap! state update :data assoc -10 "cold")
```

### Merge

```clojure
;; Right-wins merge
(im/merge map-a map-b)

;; Custom conflict resolution
(im/merge-with + counts-a counts-b)
```

### Range Query

```clojure
;; Returns a sub-map with keys in [lo, hi]
(im/range my-int-map 3 7)
```

---

## eve.deftype.rb-tree — Sorted Set (CLJS)

Okasaki-style persistent red-black tree.

```clojure
(require '[eve.deftype.rb-tree :as rb])
```

### Construction and Use

```clojure
(def state (e/atom {:tags (rb/sorted-set)}))

(swap! state update :tags conj "clojure")
(swap! state update :tags conj "python")

;; With initial elements
(rb/sorted-set 5 3 8 1 4)

;; Membership testing
(rb/member? my-set "clojure")  ;; => true

;; Supports any comparable type (strings, keywords, numbers)
(rb/sorted-set :z :a :m :f)   ;; iterates as (:a :f :m :z)
```

---

## eve/deftype — Custom SAB-Backed Types (CLJS)

Define types with fields stored directly in `SharedArrayBuffer`:

```clojure
(require '[eve.alpha :as eve :include-macros true])

(eve/deftype Counter [^:mutable ^:int32 count label]
  ICounted
  (-count [this] count))
```

### Field Metadata

| Hint | Storage | Notes |
|---|---|---|
| `^:int32`, `^:uint32` | 4-byte SAB slot | Atomic read/write |
| `^:float32` | 4-byte SAB slot | Non-atomic |
| `^:float64` | 8-byte SAB slot | Non-atomic |
| `^:MyEveType` | Offset reference | Links to another `eve/deftype` |
| (no hint) | Serialized | Any Clojure value, stored via `pr-str`/`cljs.reader` |

### Mutability Modifiers

| Modifier | Behavior |
|---|---|
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

---

## Diagnostics

```clojure
;; Internal namespace — diagnostic use only
(require '[eve.shared-atom :as a])

(a/validate-storage-model! (.-s-atom-env my-atom) {:width 80 :label "checkpoint"})
(a/xray-replay!)
```
