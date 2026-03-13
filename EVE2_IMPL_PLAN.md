# Eve2 Implementation Plan

## Executive Summary

Build a parallel `eve2.*` namespace tree that unifies CLJ/CLJS data structures through a single `eve2/deftype` macro. Users write CLJS-style protocol names (`ICounted`, `-count`); the macro auto-generates JVM equivalents. On CLJS, field reads inline to raw DataView calls (zero dispatch overhead). On CLJ, field reads emit ISlabIO protocol calls. The result: one `.cljc` source file per data structure, no reader-conditional deftype duplication.

---

## Architecture

```
User writes (.cljc):
  (eve2/deftype EveHashMap
    [^:int32 cnt ^:int32 root-off ^:int32 header-off]
    ICounted
    (-count [_] cnt))

CLJS expansion:                         CLJ expansion:
  (deftype EveHashMap [offset__]          (deftype EveHashMap [^long offset__ sio__]
    ICounted                                clojure.lang.Counted
    (-count [_]                             (count [_]
      (let [b (resolve-dv! offset__)          (-sio-read-i32 sio__ offset__ 4)))
            dv resolved-dv]
        (.getInt32 dv (+ b 4) true))))
```

### Key Design Decisions

1. **CLJS protocol names are canonical.** The macro's mapping table translates to JVM at compile time. If a user needs a protocol not in the default mapping, they use `#?(:clj ...)` in their impl file — the macro doesn't try to be exhaustive.

2. **ISlabIO is the universal memory abstraction.** All field reads/writes go through it conceptually. On CLJS, the macro inlines past it (DataView calls). On CLJ, the protocol dispatch is the actual code path.

3. **Separate eve2 tree.** No changes to `eve.*`. The two trees coexist until eve2 is proven, then eve.alpha re-exports from eve2.

---

## Source Tree

```
src/eve2/
├── deftype.clj              # THE MACRO (detects CLJS/CLJ via &env)
├── deftype/
│   └── registry.clj         # Compile-time type registry
├── proto_map.clj            # Protocol mapping table (CLJS→JVM)
├── alloc.cljc               # Slab allocator (wraps eve.deftype-proto.alloc initially)
├── serialize.cljc           # Serializer (wraps/forks eve.deftype-proto.serialize)
├── map.cljc                 # HAMT map — single deftype, unified algorithms
├── vec.cljc                 # Persistent vector
├── set.cljc                 # Set (wraps map)
├── list.cljc                # Persistent list
├── atom.cljc                # Cross-process mmap atom
└── alpha.cljc               # Public API entry point
```

Test tree mirrors this under `test/eve2/`.

---

## Phase 1: Macro Foundation

### 1.1 Protocol Mapping Table (`eve2/proto_map.clj`)

A data-only file. The macro reads it at expansion time.

```clojure
(def cljs->jvm
  '{;; Core collection protocols
    ICounted         {:iface clojure.lang.Counted
                      :methods {-count {:jvm-name count :arities [[_]]}}}
    ILookup          {:iface clojure.lang.ILookup
                      :methods {-lookup {:jvm-name valAt :arities [[_ k] [_ k nf]]}}}
    IAssociative     {:iface clojure.lang.Associative
                      :methods {-contains-key? {:jvm-name containsKey :arities [[_ k]]}
                                -assoc         {:jvm-name assoc :arities [[_ k v]]}}}
    IMap             {:iface clojure.lang.IPersistentMap
                      :methods {-dissoc {:jvm-name without :arities [[_ k]]}}}
    ICollection      {:iface clojure.lang.IPersistentCollection
                      :methods {-conj  {:jvm-name cons   :arities [[_ v]]}
                                -empty {:jvm-name empty  :arities [[_]]}}}
    IEquiv           {:iface clojure.lang.IPersistentCollection
                      :methods {-equiv {:jvm-name equiv :arities [[_ other]]}}}
    IHash            {:iface clojure.lang.IHashEq
                      :methods {-hash {:jvm-name hasheq :arities [[_]]}}}
    ISeqable         {:iface clojure.lang.Seqable
                      :methods {-seq {:jvm-name seq :arities [[_]]}}}
    IMeta            {:iface clojure.lang.IMeta
                      :methods {-meta {:jvm-name meta :arities [[_]]}}}
    IWithMeta        {:iface clojure.lang.IObj
                      :methods {-with-meta {:jvm-name withMeta :arities [[_ m]]}}}
    IFn              {:iface clojure.lang.IFn
                      :methods {-invoke {:jvm-name invoke :arities [[_ a] [_ a b]]}}}
    IReduce          {:iface clojure.lang.IReduceInit
                      :methods {-reduce {:jvm-name reduce :arities [[_ f init]]}}}
    IKVReduce        {:iface clojure.lang.IKVReduce
                      :methods {-kv-reduce {:jvm-name kvreduce :arities [[_ f init]]}}}
    IIndexed         {:iface clojure.lang.Indexed
                      :methods {-nth {:jvm-name nth :arities [[_ n] [_ n nf]]}}}
    IStack           {:iface clojure.lang.IPersistentStack
                      :methods {-peek {:jvm-name peek :arities [[_]]}
                                -pop  {:jvm-name pop  :arities [[_]]}}}
    ISeq             {:iface clojure.lang.ISeq
                      :methods {-first {:jvm-name first :arities [[_]]}
                                -rest  {:jvm-name more  :arities [[_]]}}}
    INext            {:iface nil  ;; CLJS-only, JVM uses ISeq.more
                      :methods {}}
    ISequential      {:iface clojure.lang.Sequential
                      :methods {}}
    IIterable        {:iface java.lang.Iterable
                      :methods {-iterator {:jvm-name iterator :arities [[_]]}}}
    IPrintWithWriter {:iface Object
                      :methods {-pr-writer {:jvm-name toString
                                            :arities [[_]]
                                            :transform :pr-to-string}}}})
```

**Unmapped protocols**: If a user writes a protocol symbol not in this table, the macro emits it verbatim on CLJS and throws a compile error on CLJ with a message: "Unknown protocol X — use #?(:clj ...) for JVM-specific protocols".

### 1.2 Type Registry (`eve2/deftype/registry.clj`)

Minimal. Fork of `eve.deftype.registry` with identical field parsing and layout computation. No behavioral changes — just a clean namespace.

### 1.3 The Macro (`eve2/deftype.clj`)

**Detection**: `(if (:ns &env) :cljs :clj)` — standard shadow-cljs pattern.

**CLJS expansion pipeline:**
1. Parse fields → compute layout (offsets, sizes, slab class)
2. For each method body: walk AST, replace field symbols with DataView reads
3. `set!` on fields → DataView writes; `cas!` → `Atomics.compareExchange`
4. Wrap method body in `(let [slab-base__ (resolve-dv! offset__) slab-dv__ resolved-dv ...] body)`
5. Emit `(deftype TypeName [offset__] ...protocols...)`
6. Emit constructor: alloc block, resolve-dv!, write initial values, return `(TypeName. offset)`

**CLJ expansion pipeline:**
1. Same field parsing and layout
2. For each method body: walk AST, replace field symbols with `(-sio-read-i32 sio__ offset__ field-off)`
3. `set!` → `(-sio-write-i32! sio__ offset__ field-off val)`; `cas!` → ISlabIO CAS (TBD)
4. Translate protocol names and method names via `proto_map`
5. Emit `(deftype TypeName [^long offset__ sio__] ...jvm-protocols...)`
6. Emit constructor: `(-sio-alloc! sio__ total-size)`, write fields via ISlabIO, return instance

**Boilerplate generation** (auto-added unless user provides):
- `IHash` / `hasheq` — hash of offset
- `IEquiv` / `equiv` — same type + same offset (+ same sio on CLJ)
- `IPrintWithWriter` / `toString` — type tag + field dump

**Constructor override:**
The auto-generated `->TypeName` is replaced with a constructor that takes field values (not raw offset/sio), allocates a slab block, writes fields, and returns the instance.

### 1.4 Deliverables for Phase 1

- `eve2/proto_map.clj` — the mapping table
- `eve2/deftype/registry.clj` — field parsing + layout
- `eve2/deftype.clj` — the macro, both CLJS and CLJ paths
- `test/eve2/deftype_test.cljc` — macro expansion tests:
  - Simple Counter type (one int32 field)
  - Multi-field type with mixed types
  - Mutable + volatile-mutable fields
  - set! and cas! rewriting
  - Protocol mapping verification (CLJ path)
  - Eve-type references (one deftype referencing another)
  - Constructor allocation + field initialization

---

## Phase 2: Allocator + Serializer Bridge

### 2.1 `eve2/alloc.cljc`

Initially a thin wrapper over `eve.deftype-proto.alloc`:
- Re-exports `ISlabIO`, `CljsSlabIO`, `JvmSlabCtx`
- Re-exports offset encoding/decoding
- On CLJS: exposes `resolve-dv!`, `alloc-offset`, `free!` (the module-level DataView functions)
- On CLJ: exposes `*jvm-slab-ctx*` dynamic var

**Why wrap instead of fork?** The allocator is battle-tested and shared with eve1 during transition. Forking would mean maintaining two allocators.

### 2.2 `eve2/serialize.cljc`

Initially wraps `eve.deftype-proto.serialize`. As ISlabIO unification progresses, the serialize functions get rewritten to use ISlabIO exclusively (eliminating the ~44 remaining DataView calls on CLJS).

---

## Phase 3: Data Structure Port

Port one at a time. Each follows this pattern:

1. **Extract shared algorithms** — HAMT traversal, trie operations, etc. These are pure functions that take `sio` (on CLJ) or use module-level state (on CLJS). Already ~85% shared in the current codebase.

2. **Write the deftype** using `eve2/deftype`. One block of code. Protocol impls use CLJS names. The macro handles the rest.

3. **Platform-specific helpers** — Pooling (CLJS module-level arrays), caching (CLJS mutable fields on the deftype), `binding` for sio (CLJ). These stay in `#?()` blocks, which is fine — they're inherently platform-specific mutable state.

### 3.1 Map (`eve2/map.cljc`)

The current `eve.map` has:
- **CLJS deftype**: lines 1730-1945 (~215 lines of protocol impls)
- **CLJ deftype**: lines 3300-3480 (~180 lines, largely identical logic)
- **CLJS HAMT algorithms**: lines 300-1700 (~1400 lines)
- **CLJ HAMT algorithms**: lines 2600-3280 (~680 lines, same logic via ISlabIO)

In eve2: **One deftype block** (~220 lines) + **one set of HAMT algorithms** (~1400 lines shared, with `#?` for DataView-vs-ISlabIO in the hot paths or a macro-driven abstraction).

The HAMT algorithms (hamt-assoc, hamt-dissoc, hamt-find, etc.) are the trickiest part. They currently exist in two copies:
- CLJS: uses module-level `resolve-dv!` + DataView reads
- CLJ: uses `(-sio-read-i32 sio ...)` calls

**Strategy**: Write HAMT algorithms using ISlabIO method calls. On CLJS, the `CljsSlabIO` singleton delegates to the same module-level DataView functions — so the overhead is one extra function call per slab read. If profiling shows this matters, we can later add macro-based inlining for hot-path functions.

### 3.2 Vec (`eve2/vec.cljc`)

Same pattern. Trie algorithms become shared. One deftype.

### 3.3 Set (`eve2/set.cljc`)

Thin wrapper over eve2 map. Already simple.

### 3.4 List (`eve2/list.cljc`)

Cons-cell linked list. Small, straightforward port.

### 3.5 Atom (`eve2/atom.cljc`)

Cross-process mmap atom. Mostly independent of the deftype macro — it orchestrates serialization, CAS on root pointer, and epoch-based GC. Port after data structures are stable.

---

## Phase 4: Public API + Migration

### 4.1 `eve2/alpha.cljc`

Re-exports:
- `eve2/deftype` macro
- `eve2-map`, `eve2-vec`, `eve2-set`, `eve2-list` constructors
- `eve2-atom` (cross-process atom)

### 4.2 Backward Compatibility

`eve.alpha` gets updated to re-export from `eve2.*` with deprecation warnings on old paths. Existing tests continue to work against `eve.*` namespaces.

### 4.3 Cleanup

Once all tests pass through `eve2.*`:
- Remove duplicated CLJ/CLJS deftype blocks from `eve.map`, `eve.vec`, etc.
- Point `eve.*` namespaces at `eve2.*` as thin wrappers
- Eventually collapse `eve.*` → `eve2.*` entirely

---

## Protocol Mapping: Edge Cases

### IEquiv on CLJ
CLJ has no direct `IEquiv`. Clojure uses `equiv` on `IPersistentCollection` + `MapEquivalence` marker interface. The macro handles this by:
- Mapping `IEquiv/-equiv` to `IPersistentCollection/equiv`
- Auto-adding `MapEquivalence` marker for map types (when `IMap` is present)

### IPrintWithWriter on CLJ
CLJS: `(-pr-writer [this writer opts] ...)` writes to an `IWriter`.
CLJ: `toString [_]` returns a String.
The macro transforms: on CLJ, captures the body's writes into a `StringWriter`, returns `.toString`.

### INext on CLJ
CLJS-only protocol. The macro silently drops it on CLJ (since JVM's `ISeq.more` covers the same ground).

### Unmapped Protocols
Users can freely add protocols the macro doesn't know about:
```clojure
(eve2/deftype MyType [^:int32 x]
  ICounted (-count [_] x)          ;; mapped automatically
  #?@(:cljs [IMyProtocol           ;; CLJS-only, passed through verbatim
              (-my-method [_] x)]
      :clj  [my.java.Interface      ;; CLJ-only, passed through verbatim
              (myMethod [_] x)]))
```

The macro doesn't interfere with `#?` blocks in the body — it only maps protocols it recognizes.

---

## Open Decisions (Resolved)

| Question | Decision |
|---|---|
| Protocol mapping scope | Curated ~18 protocols. Unmapped = verbatim CLJS / error CLJ. Users escape-hatch with `#?`. |
| Serialized fields | Yes, include. The `(no hint) → serialized` field class from Gen 1 enables rich user-level types. |
| Transients | Hand-rolled per data structure. Transient semantics are too type-specific for the macro. |
| Pooling | Stays in data structure code (per-class module-level arrays on CLJS). Not macro responsibility. |
| Browser LocalStorage | Separate IMemRegion impl that plugs into ISlabIO. Not macro's concern. |
| eve2 vs in-place | eve2 parallel tree. Safer, allows side-by-side testing. |

---

## Build Order

```
Phase 1 (Macro):
  1. eve2/proto_map.clj
  2. eve2/deftype/registry.clj
  3. eve2/deftype.clj (CLJS path first, then CLJ path)
  4. test/eve2/deftype_test.cljc

Phase 2 (Bridge):
  5. eve2/alloc.cljc (thin wrapper)
  6. eve2/serialize.cljc (thin wrapper, then ISlabIO-ify)

Phase 3 (Data Structures):
  7. eve2/map.cljc (biggest, do first to validate the macro)
  8. eve2/vec.cljc
  9. eve2/set.cljc
  10. eve2/list.cljc

Phase 4 (Integration):
  11. eve2/atom.cljc
  12. eve2/alpha.cljc
  13. Update eve.alpha to re-export
```

Each phase runs the full green baseline before proceeding.

---

## Risk Mitigation

1. **Macro complexity**: The CLJS→JVM protocol mapping is finite (~18 entries) and well-tested in the existing `eve.deftype.clj` (Gen 1 already has a mapping table at lines 8-47). We're expanding a proven pattern, not inventing one.

2. **Performance regression on CLJS**: The macro inlines DataView calls identically to the current Gen 2 pattern. No protocol dispatch on the hot path. Verifiable by comparing `macroexpand` output.

3. **CLJ performance**: ISlabIO dispatch is the same pattern already used by `eve.map`'s CLJ side. No change.

4. **Correctness**: Eve1 tests remain as a regression baseline. Eve2 tests run the same assertion suite against the new types.
