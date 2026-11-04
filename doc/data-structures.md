# Eve Data Structures

Eve's atoms and persistent data structures live in slab memory — in process (heap regions; SharedArrayBuffers on
ClojureScript and nbb) or in memory-mapped files shared across processes. A value written into an atom is eve's own
collection in the atom's domain; every thread of the process, and on disk every process, reads the same bytes.

## Overview

| Type | Constructor | In |
|------|------------|-----------|
| Atom | `e/atom` | a slot of an atom domain (in-process: a heap domain per `:id`; `:persistent`: a disk domain) |
| Atom domain | `e/atom-domain`, `eve.atom/persistent-atom-domain` | the domain itself, an atom (its slot 0, the registry) |
| HashMap | `e/hash-map`, or any map stored in an atom | HAMT in slab memory |
| HashSet | `e/hash-set`, or any set stored in an atom | HAMT in slab memory |
| Vector | `eve.vec/eve-vec`, or any vector stored in an atom | persistent vector trie in slab memory |
| List | `eve.list/eve-list`, or any list stored in an atom | persistent list in slab memory |
| Array | `e/eve-array` | typed array (a heap array on the JVM family; a slab block elsewhere) |
| Integer map, sorted set, sorted map | `eve.deftype.int-map`, `eve.deftype.rb-tree`, `eve.sorted` | `doc/collections.md` |
| RRB vector | `eve.rrb-vector/vec` | below |
| Dataset | `eve.alpha.ds/dataset` | columnar data frame (named EveArray columns) |
| Tensor | `eve.alpha.tensor/from-array` | N-dimensional view over an EveArray |
| Custom types | `e/deftype` (`eve.deftype/eve-deftype`) | user-defined types in slab memory |

Most user code needs only `eve.alpha`. Atoms and domains in depth — disk domains, member atoms, meta-atoms, `$` — are
`doc/atom-domains.md`.

## Setup

```clojure
(ns my-app.core
  (:require [eve.alpha :as e]))

;; an atom — this process's slab readies itself as eve.alpha loads
(defonce state (e/atom ::state {:counter 0}))
```

On ClojureScript, `e/deftype` and `e/extend-type` are macros: require `eve.alpha` with `:include-macros true` (or
`:refer-macros`). Every other host takes them from the same `.cljc`.

## Atoms and Atom Domains

`e/atom` makes a named atom (`(e/atom ::id value)`, `(e/atom {:id ::id} value)`) or an anonymous one
(`(e/atom value)`); with `:persistent` in the config it lives in a disk domain (`doc/atom-domains.md`). It implements
`IDeref`, `IAtom` (`swap!`, `reset!`, `compare-and-set!`) on every host. There are no watches or validators:
`add-watch` on an eve atom throws.

`e/atom-domain` makes a fresh in-process **atom domain** holding a value. A domain is itself an atom — `@`, `swap!`,
`reset!` act on its slot 0 — and its member atoms are its other slots. It takes no options (any passed are ignored):

```clojure
(def my-domain (e/atom-domain {:counter 0 :name "world"}))

(swap! my-domain update :counter inc)
(:counter @my-domain)    ;=> 1
(e/close-atom-domain! my-domain)
```

Known issue (0.9): a domain's slot 0 is also its registry, so `@my-domain` carries the entry
`"__atom-domain__/root" 1` beside the value's keys.

`(e/shared-atom? x)` is true for an eve atom or atom domain.

### Sharing With Workers (ClojureScript)

An in-process domain's slabs on ClojureScript are SharedArrayBuffers. `eve.sab/sab-transfer-data` (ClojureScript and
nbb) answers them for a worker — `{:sab :reader-map-sab :slab-sabs :root-sab}` (`adapters/region/js/src/eve/sab.cljs`).
`eve.thread`'s workers on Node and in the browser are started with the program and their own module slabs
(HOST-CONTRACT §11); a disk domain is shared by every process and worker that opens its path.

## HashMap, HashSet, Vector, List — Transparent Storage

A regular Clojure map, set, vector or list put into an atom is stored as eve's persistent collection. Deref
materializes it back into the host's own collections; inside `swap!` your function sees the eve collection in place.
You use standard Clojure code:

```clojure
(defonce state (e/atom ::users {:users {:alice {:role :admin}
                                        :bob   {:role :user}}
                                :tags  #{:active :verified}
                                :items [1 2 3]}))

(swap! state assoc-in [:users :carol] {:role :user})
(swap! state update :tags conj :premium)
(swap! state update :items conj 4)

@state
;=> {:items [1 2 3 4], :tags #{:active :premium :verified},
;    :users {:bob {:role :user}, :carol {:role :user}, :alice {:role :admin}}}
```

The map is an eve HAMT, the set an eve hash set, the vector an eve vector trie, all in the atom's domain. Regular
constructors (`{}`, `#{}`, `[]`, `'()`) are written into their eve equivalents during `swap!` and `reset!`, so
`e/hash-map`, `e/hash-set`, `eve.vec/eve-vec` and `eve.list/eve-list` are rarely needed — and an eve collection built
outside `swap!` belongs to another domain and cannot be stored (`doc/collections.md`).

## Typed Arrays

Eve arrays are typed arrays in eve memory with atomic operations on the integer types. On the JVM, babashka and jolt a
fresh `eve-array` is a heap array over Java's primitive arrays (`HeapEveArray`); elsewhere it is a slab block. Stored
in an atom, an array is written into the atom's domain.

```clojure
(require '[eve.alpha :as e])
(require '[eve.array :as arr])

;; Construction
(arr/eve-array :int32 10)            ;; 10 zero-filled int32 elements
(arr/eve-array :float64 10 0.0)      ;; 10 float64 filled with 0.0
(arr/eve-array :uint8 [1 2 3])       ;; from a collection
```

Supported types: `:int8`, `:uint8`, `:uint8-clamped`, `:int16`, `:uint16`, `:int32`, `:uint32`, `:float32`,
`:float64`. Also: `eve-array-uninit`, `int32-array`, `int32-array-from`, `from-double-array`, `from-int-array`,
`from-byte-array`.

### Element Access

```clojure
(e/aget my-array 0)        ;; read
(e/aset! my-array 0 42)    ;; write
```

A slab-backed array's elements are read and written through its domain's slab I/O (`eve.slab-io`); a `HeapEveArray`'s
through its host array.

### Atomic Operations (integer types only, every host)

Each is a CAS loop on the 32-bit word holding the element (a sub-word element is masked in place); a JVM heap array
locks itself instead.

```clojure
(arr/cas! my-array idx expected new-val)   ;; compare-and-swap
(arr/exchange! my-array idx new-val)       ;; atomic exchange
(arr/add! my-array idx delta)              ;; atomic add
(arr/sub! my-array idx delta)              ;; atomic subtract
(arr/band! my-array idx mask)              ;; atomic bitwise AND
(arr/bor! my-array idx mask)               ;; atomic bitwise OR
(arr/bxor! my-array idx mask)              ;; atomic bitwise XOR
```

### Wait/Notify (int32 only; a slab-backed array: the region's futex / Atomics.wait)

```clojure
(arr/wait! my-array idx expected)          ;; block until the value changes
(arr/wait! my-array idx expected timeout)  ;; with a timeout (ms)
(arr/notify! my-array idx)                 ;; wake every waiter (count defaults to ##Inf)
(arr/notify! my-array idx count)           ;; wake up to count waiters
```

### Bulk and Low-Level Access

```clojure
(arr/areduce my-array init (fn [acc idx v] ...))   ;; reduce with index (every host)
(arr/amap my-array (fn [idx v] ...))               ;; a new array (every host)
(arr/amap! my-array (fn [idx v] ...))              ;; in place
(arr/afill! my-array v)
(arr/acopy! dest src)
(arr/array-type my-array)       ;; the type keyword (:int32, ...)
(arr/retire! my-array)          ;; free its block, when nothing reads it any more
;; ClojureScript (and nbb): eve.typed-view, [eve.typed-view :as tv]
(tv/get-sab my-array)           ;; the underlying buffer
(tv/get-typed-view my-array)    ;; a raw JS TypedArray view
(tv/from-typed-array js-array)  ;; an EveArray of a JS typed array's elements
```

### SIMD Operations (int32 only, ClojureScript: eve.typed-view)

WASM SIMD loops over an int32 array. They are **not atomic** — use them only with exclusive access:

```clojure
(tv/afill-simd! my-array 42)                     ;; fill all elements
(tv/afill-simd! my-array 42 start end)            ;; fill a range
(tv/acopy-simd! dest src)                         ;; copy
(tv/asum-simd my-array)                           ;; sum
(tv/amin-simd my-array) (tv/amax-simd my-array) (tv/aequal-simd? a b)
```

## `eve/deftype` — Custom Types in Eve Memory

Define types whose fields are stored in slab memory:

```clojure
(ns user.counter
  (:require [eve.alpha :as eve]       ; ClojureScript: [eve.alpha :as eve :include-macros true]
            [eve.atom :as ea]))

(eve/deftype Counter [^:mutable ^:int32 count label]
  clojure.lang.Counted
  (count [this] count))

(def a (eve/atom ::counters {}))
(swap! a (fn [v] (assoc v :c (->Counter 3 "c"))))
(eve/$ (eve/count (:c @a)))            ;=> 3
```

(Run on babashka and the JVM, 2026-10-20.) A type of your own has no host collection to materialize to, so deref
answers the instance itself — a reference into the domain, meaningful while the domain holds it; read it inside
`swap!` or inside `eve.alpha/$`.

Write the body with the JVM's interface names or with ClojureScript's
protocol names — either way one `.cljc` definition serves every host. JVM
names are translated on ClojureScript (`clojure.lang.Counted`/`count` →
`ICounted`/`-count`, `eve.deftype-proto.proto-map/jvm->cljs`); ClojureScript
names are translated on the JVM (`ICounted`/`-count` → `clojure.lang.Counted`/
`count`, `cljs->jvm`), with the methods the JVM's interfaces also require
derived from the type's own (an `IPersistentVector`'s `length` from its count,
an `Associative`'s `entryAt` from its lookup, `IFn`'s `applyTo`, `Object`'s
`equals` and `hashCode` from `-equiv` and `-hash`) and an `IPrintWithWriter`
body as the type's `print-method`. A name neither table knows (your own
protocol) passes through. The constructor is the same on every host —
`(->Counter 0 "c")` — and allocates in the running `swap!`'s domain, else the
process's own: nobody passes a slab. (`Counter.` builds an instance on the
host, not in eve memory: an eve type is always built with `->Counter`.)

Two worked examples, one source each, their tests run on the JVM, Node, nbb
and babashka, their values live in atoms like any Eve value:
[the RRB vector](#rrb-vector--corerrb-vector-in-eve-memory) — core.rrb-vector's
ClojureScript implementation, written the ClojureScript way — and
`src/eve/deftype/rb_tree.cljc`, a red-black tree and a `SortedSet` over it,
written with the JVM's names.

### Hosts

One definition, written with the JVM's interface names or ClojureScript's
protocol names, on every host. The expansion is `eve.deftype.macros`
(portable `.cljc`); the core collections and `EveArray` use the layer under it
(`eve.deftype-proto.macros/deftype`), which follows the same rules.

Each host realizes a type through its `eve.types` adapter (`hosts.edn`'s `types/<impl>` root):

| Host | `eve.types` adapter | What a type is |
|---|---|---|
| JVM | `types/jvm` | a `deftype` with the interfaces as written |
| ClojureScript (compiled) | `types/jvm` → `types/js` (the compiler runs in the JVM) | a `deftype` with the CLJS protocols they translate to |
| nbb | `types/js` + `types/nbb` | the same ClojureScript `deftype`, run by SCI |
| babashka | `types/bb` | a `deftype` with those interfaces on `eve.core`'s protocols; a map type keeps the map interfaces |
| ClojureCLR | `types/clr` | a `deftype` with the JVM's interfaces under the CLR's names (below) |
| basilisp | `types/basilisp` | a basilisp `deftype` (a Python class) with the JVM's interfaces translated onto basilisp's (below) |
| jolt | `types/jvm` | the JVM's `deftype`, as written (below) |
| clojerl | `types/beam` | a clojerl `deftype` with the JVM's interfaces translated onto clojerl's protocols (below) |
| glojure (glj-eve) | `types/glojure` | the completion's `deftype` — a record extended to its protocols (glojure v0.7.22 has none) — with every interface translated onto `eve.core`'s protocols, as on babashka; `IDeref`/`IAtom`/`IPending` onto the completion's (below) |
| ValueScript | `types/vs` | VS's `deftype`, whose instances VS's core reads as its own typed maps: every interface translated onto `eve.core`'s protocols. eve's own `eve/deftype` expansion does not yet run on VS (`hosts.edn`'s `:vs` comment: it waits on VS's analyzer and macro evaluator); the codec, regions, the allocator and the HAMT functions do |

**The vocabulary.** `eve.core` (re-exported by `eve.alpha`) is the surface
every EVE value answers on every host: `count`, `nth`, `get`, `seq`, `conj`,
`empty`, `contains?`, `disj`, `meta`, `with-meta`, `invoke`, `reduce`. On the
JVM, ClojureScript and nbb these *are* `clojure.core`'s functions (with its
inlining on the JVM), because a type there carries the host's own collection
interfaces — `(count x)` and `(e/count x)` are the same call. On babashka,
whose `deftype` takes protocols, `Object` and `IPersistentMap` only (the
babashka book, "Differences with Clojure"), the macro maps
`clojure.lang.Counted/count` onto `eve.core`'s `ICounted/-count` exactly as it
maps it onto ClojureScript's `ICounted`, and these functions dispatch through
those protocols, falling back to `clojure.core` for ordinary data. Code meant
for every host — EVE's own library code, its shared tests — says it through
this vocabulary. `eve.core` is an adapter (`adapters/coll/`, one
implementation per capability: clojure.core's with RT inlining on the JVM,
ClojureCLR and jolt; cljs.core's; nbb's; bb's protocols; basilisp's;
clojerl's); what is written once over it is `eve.coll` (cljs.core's
collection helpers) and `eve.rseq` (RSeq).

On babashka a type is otherwise an ordinary bb `deftype`: real fields, `T.`,
`instance?`, mutable fields, and `=`, `hash` and `str` through its `Object`
methods (`equals` and `hashCode` its `equiv`/`hasheq`). What bb cannot
do is make `clojure.core`'s own `count`, `nth`, `seq` — or a keyword lookup,
`(:k x)` — see a type that is not a map; there it is `(e/count x)`,
`(e/get x :k)`. EVE's vector, set and list are defined on bb like any type
(`eve.vec/eve-vec` builds one there, answering `eve.core`), and what bb reads
from eve memory is the host's own vector, set or list: the collection
registers both readers and bb's types adapter picks the host value
(`eve.types/reader-of`), so `clojure.core` takes a value read from an atom.
Deref gives plain Clojure data on every host for eve's map, set, vector and list
and for the collections that register a host reader (the int-map reads as a
map, the red-black sorted set as a sorted set); a type with no host reader — an
RRB vector, your own `eve/deftype` — reads back as the eve type itself (on
babashka, reach it through `eve.core`: `(e/count (:v @a))`).

Each collection registers how it prints (`:print {:open "[" :close "]"}`, or
`:map`) and the types adapter makes the host's printer of it: a
`print-method` through `clojure.core`'s own printers on the JVM and the CLR,
`IPrintWithWriter` on ClojureScript and nbb (`eve.types/print-form`).

On ClojureCLR the `clojure.lang` interfaces carry the JVM's names, so a type
is written as for the JVM and the types adapter translates what differs, against
the CLR's own types (`adapters/types/clr`, `cljr-translate-protos`):
`Object`'s methods are `Equals`, `GetHashCode` and `ToString`; a seqable type
is `IEnumerable` (and `IEnumerable<IMapEntry>` where `IPersistentMap` brings
it), since ClojureCLR's `vec` and `into` take one; `java.util.Map` is a
read-only `IDictionary` + `ICollection`, the only maps ClojureCLR's own maps
compare equal to; a method two interfaces declare with different return types
(`assoc`, `cons`) is defined once per return type, hinted with it; a method
no CLR interface declares (`Map$Entry`'s `getKey`) is dropped; a type with
`IReduceInit` gets `IReduce`'s two-argument `reduce`; and the exceptions a body
throws are the CLR's. `eve.core`'s vocabulary is `clojure.core`'s there, as
on the JVM (`coll/jvm`). The host's own primitives are its adapters (`hosts.edn
:cljr`): regions over `MemoryMappedFile` with `Interlocked` and futex
(`region/clr`), bytes, numbers and dates (`encoding/clr`), the clock, threads and
files (`process/clr`, `thread/clr`).

On basilisp (0.5.1 on CPython) a type is a basilisp `deftype`, a Python class,
and the types adapter translates the JVM's names onto basilisp's interfaces
(`adapters/types/basilisp`, `lpy-translate-protos`): `count` is
`__len__`, `equiv` and `hasheq` are `__eq__` and `__hash__`, a collection
prints through `__repr__` as basilisp's own does; `meta`, `first`, `rest`,
`key` and `value` are properties; a method the JVM overloads by arity (`nth`,
`valAt`, `reduce`, `cons`) is one variadic method dispatching on the count;
what basilisp's interfaces require and the JVM derives (`__iter__`,
`__getitem__`, `is_empty`, `contains`, `entry`) is generated; and a body's
`(.valAt m k)` is `(get m k)`. A Python class has one namespace for fields and
methods, so a field named as a property (`meta`) is renamed inside the
methods; and the type's own protocols come before its interfaces, since
basilisp dispatches a protocol function by the type's MRO. Where basilisp.core
lacks a name eve uses or differs from Clojure — `unchecked-int` does not
narrow, `ns` keeps one clause of each kind, `swap!` is a deref and a
`compare-and-set!` rather than the atom's own swap — its core completion
(`adapters/completion/basilisp/src/eve/host_completion.lpy`, which `eve.host`
loads first) makes it Clojure's. The host's own primitives are its adapters
(`hosts.edn :lpy`): regions over `mmap` with atomics through `ctypes` and the
system's `libatomic` and futex through libc's `syscall` (`region/ctypes`), bytes
and dates (`encoding/python`), the clock, threads and files (`process/python`,
`thread/python`).

jolt (Clojure compiled to Chez Scheme) runs eve's shared `.cljc` and realizes
eve's types through the JVM's `eve.types` adapter (`adapters/types/jvm`). A type
is the JVM's `deftype` as written: jolt registers a type's methods by name across
the `clojure.lang` interfaces it knows. What differs from the JVM is jolt's
adapters (`hosts.edn :jolt`): its regions — a file mapped `MAP_SHARED` or an immobile
bytevector in Chez's heap, at a fixed address; atomics from the system's
`libatomic`, futex through libc's `syscall`, all by `jolt.ffi` (`region/jolt-ffi`) — and its core completion (`completion/jolt`), which makes
`swap!`, `reset!` and `compare-and-set!` call another atom's `IAtom` methods
(jolt's own cast their atom to jolt's), and makes jolt's `into` and chunked-seq
fns reach a type's own (`chunk-first`, `chunk-rest` and `chunk-next` call a
type's `IChunkedSeq` methods; `into` goes through the target's transient whenever
it is an `IEditableCollection`). `seq` is a native op the completion cannot
re-root: `eve.core`'s `seq` (`coll/jvm`) is Clojure's.

clojerl (Clojure on the BEAM) runs eve's shared `.cljc` with its own core
adapter (`adapters/core/beam`). Its `deftype` implements protocols
(`clojerl.ICounted`, `clojerl.ISeq`, ...), not interfaces, and has no mutable
fields: the types adapter (`adapters/types/beam`, `clje-deftype`) translates the
JVM's interfaces and methods onto clojerl's protocols, rewrites the JVM interop a
body holds, and gives a mutable field a cell (`eve.host/volatile!`) that the
type's `->T` factory makes; a hash cache is not kept. Its core has no arrays,
cells, locks or transients and its integers are unbounded: those are `eve.host`'s
([HOST-CONTRACT §10](unification/HOST-CONTRACT.md#10-the-host-seam-evehost-2026-10-16)),
over eve's NIF (`adapters/core/beam/native/eve_clje_nif.c`, built on first load
into `adapters/core/beam/target/clje/`), which also holds the regions — an
mmap'd file or heap memory, seq-cst atomics, futex wait and wake, `fcntl` locks
(`region/beam`). What clojerl cannot hold is named where the shared tests meet
it: a char (it reads `\a` as a one-character string), a date finer than a second
(`erlang.util.Date` is a `calendar:datetime()`), and a keyword past 255
characters (a keyword is an Erlang atom). One clojerl bug the RRB suite meets is
clojerl's own: a vector's seq hashes the whole vector wherever it stands
(`clojerl.Vector.ChunkedSeq:hash/1`, clojerl `4ad14e5`).

glojure (Go) runs eve's shared `.cljc` as glj-eve — glojure v0.7.22 with eve's
Go packages linked in and its core completion's patches applied
(`impls/glojure/build.sh`; `bb glj-eve` builds it into `target/glj-eve/`). glojure
has no `deftype` or `reify`: its completion (`adapters/completion/glojure`)
supplies them as records extended to their protocols, mutable fields as cells,
and completes what else v0.7.22 lacks or answers otherwise (`satisfies?`,
`=`/`hash` reaching a type's own, `deref`/`swap!`/`reset!` reaching a type's
`IDeref`/`IAtom`, a wrapping `unchecked-int`, `clojure.edn`), with three Go
patches for the build (`(.-field x)` reads, a record type's factory for `new`, a
goroutine's id). Its types adapter (`types/glojure`) translates a type's
interfaces onto `eve.core`'s protocols, as on babashka (`coll/protocols`), and a
registered collection — eve's map too — reads as glojure's own. Regions are eve's
Go region package (`region/glojure`, `impls/glojure/region`: Go's heap or a
`MAP_SHARED` file, `sync/atomic`, futex).

ValueScript (`hosts.edn :vs`, `bb test:vs`: compiled by `vs compile`, run under
wasmtime) runs eve's slab I/O, regions, allocator, codec and HAMT functions over
its own linear memory (`slab-io/vs`, `region/vs`); `eve.core` there is babashka's
protocols adapter and `types/vs` translates a type onto it. eve's `eve/deftype`
expansion, eve's threads and the network atom's crypto natives are not yet on VS
(`hosts.edn`'s `:vs` comment; `crypto/portable` is its crypto).

A plain (not slab-backed) type for the JVM family is
`eve.deftype-proto.macros/host-deftype` — `deftype` on the JVM and jolt, the
same translated `deftype` on bb, ClojureCLR and basilisp. `HeapEveArray`, the heap array a
fresh `eve-array` is where `eve.encoding` has a host typed array to hold it
(`typed-array`: Java's primitive arrays, on the JVM, bb and jolt; elsewhere a
fresh `eve-array` is a slab block), is an `eve.deftype/deftype` on every host.

**A type's id.** The byte that names a type in eve memory comes from the
type's namespace-qualified name, so every process derives the same id
whatever it loaded and in whatever order, and a type defined later is read
by every host that loads its definition. Two types may share that byte; a
hash of the name in each block tells them apart
([FORMAT-DECISIONS §7](unification/FORMAT-DECISIONS.md#7-eve-deftype-type-ids-from-the-types-name-2026-10-16)).
The name is the identity: rename a type or its namespace and the values
written under the old name no longer read as it.

### Field Metadata

| Hint | Storage | Notes |
|------|---------|-------|
| `^:int32`, `^:uint32` | 4-byte slot in the instance's block | Atomic read/write |
| `^:float32` | 4-byte slot | Non-atomic |
| `^:float64` | 8-byte slot | Non-atomic |
| `^:MyEveType` | Offset reference | Links to another `eve/deftype`; read when a method reads it |
| (no hint) | An eve value | Any value in eve's encoding (its own block): an eve map, vector, array, another eve type's instance |
| `^:host` | On the instance | Not in eve memory — a cache, a transient's edit token; `nil` in an instance read back from eve memory |

An immutable field (all but a `^:MyEveType` reference) is read when an instance
is reached and kept on it, so `(.-field x)` reads it on every host, as a
`deftype`'s field — `(.-root v)`, `(.-arr node)`. A `^:MyEveType` reference is
read when a method reads it: a node linked to nodes of its own type would
otherwise read the whole structure the moment one is reached.

### Mutability

| Modifier | Behavior |
|----------|----------|
| (default) | Immutable — set at construction |
| `^:mutable` with a slab type | Mutable via `set!` (single-worker), in eve memory |
| `^:mutable` with none | On the instance only (as `^:host`), mutable — ClojureScript's `^:mutable __hash` cache |
| `^:volatile-mutable` | Atomic via `set!`/`cas!` (cross-worker safe) |

A field the JVM reserves for types (`__hash`, ...) is renamed there, in the
field and in the bodies that read it.

### Types on the host: `eve.deftype/deftype`

A type whose instances need not live in eve memory — a seq over an eve
collection, a transient, a view — is `eve.deftype/deftype`: written the same
way (ClojureScript's protocol names or the JVM's), translated the same way,
its instances ordinary objects of the running process. `cljs.core/deftype`
on ClojureScript and nbb (nbb's missing `IChunkedSeq`, `IChunkedNext` and
`ASeq` are `eve.core`'s), the JVM's interfaces on the JVM, `eve.core`'s
protocols on babashka; `^:mutable` is the JVM's `^:unsynchronized-mutable`.

### Arrays in eve memory: `eve.deftype.array`

What an eve type holds where a host type holds an array (`Object[]`, a JS
array): `(make-array n)` allocates `n` slots in the current eve domain, each
holding one eve value — inline when its encoding is an int32, a pointer to
another eve value, `false` or `true`, else in a block of its own. A 33-slot
node fits a 256-byte block. `aget`, `aset`, `alength`, `aclone`,
`array-copy`, `array-seq` and `array-chunk` take an eve array or a host
array, so code written against ClojureScript's array functions reads the
same. An eve array is one `eve.deftype/deftype` on every host, seqable by
`eve.core` everywhere and by the host's own `seq` wherever the host's types
reach it (on bb `clojure.core` does not see a type's protocols, as for every
eve type there; `array-seq` reads one on every host). An
eve array is written while it is new, by the code that made it, and read
after that; `aclone` and `array-copy` share the values a slot points at.

### Extending Types

```clojure
(eve/extend-type Counter
  IIncable
  (-inc! [this]
    (set! count (inc count))
    this))
```

Field names from the type's declaration are available as local bindings in method bodies. `set!` and `cas!` on declared fields are rewritten to slab operations (`eve.slab-io`).

## RRB vector — core.rrb-vector in eve memory

`eve.rrb-vector` is [core.rrb-vector](https://github.com/clojure/core.rrb-vector)'s
ClojureScript implementation — relaxed radix balanced trees: vectors with
logarithmic `catvec` and `subvec` — as one `.cljc` source for every EVE host,
with the vector in eve memory. It is the worked example of writing an eve
type: the same algorithm, the same functions in the same files, line for
line, so reading the two side by side shows what EVE asks of a data
structure and nothing else.

```clojure
(require '[eve.rrb-vector :as fv] '[eve.atom :as atom] '[eve.core :as e])

(def a (atom/atom {:id ::playlist} nil))
(swap! a (fn [_] {:v (fv/vec (range 1000))}))          ; built in the atom's domain
(swap! a update :v #(fv/catvec % (fv/vec (range 1000 1500))))
(swap! a update :v #(fv/subvec % 10))
(e/count (:v @a))                                        ; => 1490, in any process sharing the atom
```

**Reading it against upstream.** The first commit of the example imports
upstream's sources byte for byte (core.rrb-vector `d1cc27f1`,
`src/main/cljs`, EPL 1.0 notices kept); the next one ports them. `git diff`
between the two, over `src/eve/rrb_vector*`, is exactly what EVE changes,
and every change is marked `;; eve:` in the source:

| core.rrb-vector (ClojureScript) | `eve.rrb-vector` | Why |
|---|---|---|
| `(deftype Vector [cnt shift root tail meta ^:mutable __hash] …)` | `(eve-deftype Vector [^:int32 cnt ^:int32 shift root tail meta ^:mutable __hash] …)` — the body is upstream's | the vector lives in eve memory; `cnt`/`shift` are int32s there; `__hash` (`^:mutable`, no slab type) stays a cache on the instance |
| cljs.core's `VectorNode [edit arr]` | `(eve-deftype VectorNode [^:host edit arr])` | a node lives in eve memory; its edit token never enters it — a token in shared memory could match another process's transient, which would then edit a node it does not own |
| JS arrays: `make-array`, `aget`, `aset`, `aclone`, `array-copy` | eve arrays (`eve.deftype.array`), the same calls | a node's children, a leaf's elements, a range table, in eve memory |
| `(Vector. …)`, `(VectorNode. …)` | `(->Vector …)`, `(->VectorNode …)` | the constructor allocates in the current eve domain |
| `cljs.core.PersistentVector.EMPTY_NODE`, one node every vector shares | `(empty-node)`, one per vector | an eve value cannot point into another domain (another atom, another process's own memory) |
| `-empty`, and `pop` of the last element: a cljs.core vector | an empty eve vector | the result stays an eve value |
| `-as-rrbt` of a cljs.core vector: its nodes, shared | its elements, copied into eve memory | a host vector's nodes are in the host's memory |
| transient `persistent!`: `(set! (.-edit root) nil)` | `(set! root (->VectorNode nil (.-arr root)))` | the token is on the root instance; the JVM and bb cannot `set!` another object's field |

What remains is portability, not EVE: `Box` (a field another type sets) is a
`volatile!`, `js/Error.` is `ex-info`, `RSeq.` is `->RSeq` (nbb names
another namespace's type only through its factory), `-lookup` of a key that
is not a number answers not-found (as cljs.core's own vector does — `(<= 0
:k)` throws on the JVM), and `remove-leftmost-child` stops adjusting the
range table at the entries it copied (upstream also adjusts the slot it
overwrites next, unset — `NaN` on a JS array, `nil` here). The namespace
forms differ, and that is all.

**On each host.** On the JVM, ClojureScript and nbb an RRB vector is a vector
to `clojure.core` (`vector?`, `=`, `nth`, `conj`, `reduce`, transients). On
babashka its `clojure.core` cannot see the type: say it through `eve.core`
(`e/count`, `e/conj`, `e/equiv`, `e/transient`, ...), which is what the shared
test does on every host.

**Memory.** Every persistent step allocates eve memory (an `assoc` copies its
path), and what a computation drops is reclaimed only when an atom's
`swap!` replaces it: build inside `swap!`, in the atom's domain. Outside an
atom a value lands in the process's own domain — on the JVM family a heap domain of 1 MB per slab
class. The ported test suite (`test/eve/rrb_vector_test.cljc`: upstream's
`test_common.cljs`, deftest by deftest, the same data and checks) builds each
test in a domain of its own for that reason.

**Across processes and hosts.** An RRB vector is an eve value like a map:
one atom can hold them on every host at once. The cross-host contention
scenario (its value written in eve's
`test/coop/mix.cljc`) keeps RRB vectors of eve maps, a shared RRB vector turned
by `catvec` of two `subvec`s, an `eve.deftype.rb-tree` sorted set and nested maps
in one mmap atom, and has the JVM, Node, nbb, bb, ClojureCLR, basilisp, jolt and clojerl
`swap!` it at once; each host then reads the final value back and must find what the op counts
predict.

**Status.** The ported suite (`test/eve/rrb_vector_test.cljc`, 22 tests) runs on
the JVM (inside `clojure -M:jvm-test`), on ClojureScript (`node
target/eve-test/all.js rrb-vector`), and in the default runs of nbb, babashka,
ClojureCLR, jolt and clojerl; on basilisp and glojure it runs when named (`bb test:lpy
eve.rrb-vector-test`, `bb test:glj eve.rrb-vector-test`), where it takes a long
time: upstream's data reaches 300,000 elements. nbb and babashka skip the
host-specific checks in `test-seq` (nbb has no chunked seqs; bb's `e/seq` is a
Clojure seq walking the vector). The final pass's `bb test:bb` run (2026-10-21 ET,
247 tests, 0 failures) includes it.

## Memory

A value's blocks come from the six fixed slab classes and the coalescing overflow
class of its domain (`doc/internals.md`). The slab classes start small and grow on
demand (`eve.deftype-proto.data`, `SLAB_CLASS_INITIAL_CAPACITIES` /
`SLAB_CLASS_CAPACITIES`; a disk domain overrides the maxima with `:capacities`):

| Class | Block Size | Initial Capacity | Max Capacity | Max Blocks |
|-------|-----------|-------------------|-------------|-----------|
| 0 | 32 B | 64 KB (2K blocks) | 1 GB | 32M blocks |
| 1 | 64 B | 64 KB (1K blocks) | 1 GB | 16M blocks |
| 2 | 128 B | 32 KB (256 blocks) | 1 GB | 8M blocks |
| 3 | 256 B | 32 KB (128 blocks) | 512 MB | 2M blocks |
| 4 | 512 B | 16 KB (32 blocks) | 256 MB | 512K blocks |
| 5 | 1024 B | 16 KB (16 blocks) | 256 MB | 256K blocks |

Total initial slab capacity: about 224 KB (64+64+32+32+16+16). Sparse files and
lazy page commit mean untouched pages cost neither memory nor disk. The overflow
class holds blocks over 1024 bytes in a data region of up to 4 PiB, tracked by
16,384 descriptors by default (`eve.deftype-proto.coalesc`). A domain's root
region holds 256 worker slots (24 bytes each; a slot whose heartbeat is 30 s old
is stale) and 256 atom slots, one of them the registry.

What a write frees, and when: a committed `swap!` plans, under its pin, what its
old value drops against its new one (`eve.retire`). A block whose last holder the
write drops is queued with the epoch it published and freed by a later flush once
no live reader's pinned epoch can reach it; a piece still held at another place
carries a count (`eve.tally`) that the write lowers instead, and a write that
stores an existing piece again raises it, under the domain's exclusive hold. A
lost CAS frees the blocks its attempt allocated. The model this follows — and where eve's code
diverges from it — is `doc/memory-model.md`.

### Diagnostics

`eve.atom/seal` (a byte-exact digest of an atom's stored version) and
`eve.atom/version` (`[epoch root-pointer]`) tell two readers whether they see the
same version. The X-RAY storage validator of 0.1.0 (`validate-storage-model!`,
`xray-replay!`) belongs to the legacy `eve.shared-atom`, which nothing in the tree
loads; it does not apply to `e/atom`'s atoms.

## Dataset — Columnar Data Frames

Datasets are columnar data frames: named columns (EveArrays) in eve memory, built with eve's types. Like every eve
value a dataset allocates in the running `swap!`'s domain, else this process's own slab: build one inside `swap!` to
keep it in an atom.

```clojure
(require '[eve.alpha :as e]
         '[eve.atom :as ea]
         '[eve.alpha.ds :as ds])

(def my-atom (e/atom ::data {}))
(swap! my-atom (fn [v] (assoc v :ds (ds/dataset {:price (e/eve-array :float64 [10.5 20.3 15.0])
                                                 :qty   (e/eve-array :int32 [100 200 150])}))))

(e/$ (ds/column-names (:ds @my-atom)))              ;=> [:price :qty]
(e/$ (ds/row-count (:ds @my-atom)))                 ;=> 3
(e/$ (ds/dtypes (:ds @my-atom)))                    ;=> {:price :float64, :qty :int32}
(e/$ (ds/row-count (ds/filter-rows (:ds @my-atom) :price #(> % 12.0))))   ;=> 2
(e/$ (vec (e/seq (ds/column (ds/sort-by-column (:ds @my-atom) :price :asc) :price))))
;=> [10.5 15.0 20.3]
```

(These samples were run on babashka 1.13.226 and the JVM on 2026-10-20 with identical output, as were these calls
of the sections below, on a 3×4 tensor and the dataset's columns: `tensor/from-array`, `tensor/shape`,
`tensor/mget`, `tensor/transpose`, `tensor/ereduce`, `col/sum`, `col/mean`, `col/argsort`.)

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

This page publishes one measurement: a dated, sourced run of the columnar bench on every host it then ran on. The
tables earlier versions carried (columnar speedups by size, mmap swap and deref throughput, contention, footprint,
profiles) had no date, commit or command behind them and are gone; re-measure with the commands under "Running
Benchmarks" and publish a figure with its date, commit, host and command.

### Columnar Operations — Every host at 1M elements

Each benchmark runs a multi-step pipeline inside `swap!`. "Eve" uses EveArray columns with typed-view fast paths;
"Stock" uses plain Clojure vectors with `mapv`/`reduce`/`sort`. Every result is checked against the stock pipeline's.

One run of each host's bench on one 4-core box in the final pass (2026-10-21 ET, the final tree), trimmed mean of the
middle three of five timed runs; every host's 30 results matched. nbb and babashka interpret both pipelines; on nbb
Eve's typed-array loops still win most rows, on babashka the stock pipeline (compiled `clojure.core`) wins most. Against
this table's previous run (2026-10-16, `c06b7688d`) Eve is slower on most Node rows (column arithmetic mmap 8 → 115 ms)
and in the JVM's tensor pipeline (48 → 243 ms mmap); an A/B by tree finds that slowdown already in main's `f65b23153`,
before the release branch's own commits (not bisected). On 2026-10-16 the bench re-run on the `eve.core` tree
(`1d06c539`) matched 30 of 30 on every host; the rows it ran slow, A/B'd against the tree before (`2389e54e`) in three
alternating rounds, did not move (JVM dataset mmap 230–245 vs 215–234 ms, Node column arithmetic mmap 7–11 vs 8–13 ms)
— an intermittent slow mode (a ~1 s tensor pipeline) showed on either tree.

| Pipeline (1M) | Mode | JVM Eve / Stock ms | Node Eve / Stock ms | nbb Eve / Stock ms | bb Eve / Stock ms |
|---|---|---|---|---|---|
| Column Arithmetic | mmap | 18 / 147 | 115 / 710 | 206 / 625 | 339 / 485 |
| Dataset Pipeline | mmap | 128 / 998 | 484 / 3434 | 1494 / 3496 | 3533 / 3388 |
| Filter + Aggregate | mmap | 37 / 35 | 109 / 90 | 141 / 66 | 462 / 86 |
| Sort + Top-N | mmap | 154 / 552 | 354 / 1269 | 2364 / 1273 | 6402 / 1190 |
| Tensor Pipeline | mmap | 243 / 167 | 315 / 938 | 261 / 547 | 429 / 384 |
| Column Arithmetic | in-memory | 22 / 127 | 186 / 799 | 202 / 647 | 360 / 433 |
| Dataset Pipeline | in-memory | 164 / 1077 | 518 / 3195 | 1409 / 3466 | 3463 / 3455 |
| Filter + Aggregate | in-memory | 27 / 92 | 102 / 103 | 149 / 69 | 507 / 117 |
| Sort + Top-N | in-memory | 164 / 519 | 383 / 1466 | 2395 / 1312 | 6869 / 1075 |
| Tensor Pipeline | in-memory | 270 / 168 | 169 / 851 | 174 / 637 | 489 / 365 |

### JVM IBulkAccess Fast Paths

On the JVM, the `IBulkAccess` protocol (`eve.deftype-proto.data`) gives `double[]`/`int[]` `aget` loops that avoid
per-element protocol dispatch and byte-array allocation. Design principle: **one extraction, flat loop**. Every bulk
operation:
1. extracts the backing `double[]`/`int[]` once (O(1));
2. loops with `aget`/`aset` over the raw primitive array (O(n), no allocation);
3. wraps the result as an EveArray at the end (O(1)).
### Running Benchmarks

```bash
# JVM columnar benchmarks (all modes, all scales)
clojure -M:columnar-bench

# Node.js columnar benchmarks
npm run test:compile
node target/eve-test/all.js columnar-bench    # all modes
node target/eve-test/all.js columnar-mmap     # mmap only
node target/eve-test/all.js columnar-inmem    # in-memory only

# babashka and nbb, from the source tree (run from eve/)
bb bench:bb                                   # bb -cp src:<hosts.edn :bb roots>:test -m eve.runner.bb-main bench
bb bench:nbb                                  # nbb --classpath src:<hosts.edn :nbb roots>:test -m eve.runner.nbb-main bench
bb -cp src:<hosts.edn :bb roots>:test -m eve.runner.bb-main bench dataset 10000 mmap   # one pipeline

# the shared (.cljc) tests on babashka and nbb
bb test:bb
bb test:nbb
```
