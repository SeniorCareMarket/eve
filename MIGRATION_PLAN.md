# Eve CLJC-ification Migration Plan

## Key Finding

ISlabIO is already 90% of the way to full cross-platform unification. The data structures already use ISlabIO for 429 of their memory access calls. Only ~105 raw DataView calls in `#?(:cljs)` blocks remain, mostly in write paths and serialization. Converting those is mechanical.

The harder problem — and the focus of this plan — is the **`eve/deftype` macro**: how to give users (and the data structures themselves) a single macro that generates memory-backed deftypes working across all 6 environments.

---

## Status Quo: Three Generations of Deftype Macros

### Generation 1: `eve.deftype` (lower-level, CLJS-only)
- **File**: `src/eve/deftype.clj` + `src/eve/deftype/runtime.cljs` + `src/eve/deftype/registry.clj`
- **Internal fields**: `[eve-env eve-offset]` — carries an "env" map + byte offset
- **Allocation**: `eve.deftype.runtime/alloc-instance` → old SAB allocator
- **Field access**: emits calls to `runtime/read-int32` etc (function calls per field read)
- **Used by**: `eve.deftype.int_map.cljs`, `eve.deftype.rb_tree.cljs`
- **Platform**: CLJS only

### Generation 2: `eve.deftype-proto` (higher-level, CLJS-only)
- **File**: `src/eve/deftype_proto.clj` (macro)
- **Internal fields**: `[offset__]` — just a slab-qualified offset, no env
- **Allocation**: `eve.deftype-proto.alloc/alloc-offset` (slab allocator)
- **Field access**: `resolve-dv!` once per method, then inline `.getInt32` etc on `slab-dv__` / `slab-base__` (zero function-call overhead for field reads)
- **Used by**: Nothing — data structures use hand-rolled deftype
- **Platform**: CLJS only (emits `js/DataView`, `js/Atomics`)

### Generation 3: Hand-rolled `deftype` in data structures
- **Files**: `map.cljc`, `vec.cljc`, `set.cljc`, `list.cljc`
- **Pattern**: Separate `#?(:cljs (deftype ...))` and `#?(:clj (deftype ...))` blocks
- **CLJS fields**: `[cnt root-off header-off ^:mutable ...]` — plain JS values
- **CLJ fields**: `[^long cnt ^long root-off ^long header-off sio _meta]` — carries `sio` (ISlabIO)
- **Field access**: CLJS reads from slab DataViews. JVM reads via ISlabIO.
- **Platform**: Both CLJ and CLJS, with completely separate implementations

### The bottom layer

**IMemRegion** (`mem.cljc`): Platform-neutral atomic memory ops.
- `JsSabRegion` (CLJS) — wraps SharedArrayBuffer
- `NodeMmapRegion` (CLJS) — wraps mmap via native addon
- `JvmMmapRegion` (CLJ) — wraps Panama FFM MemorySegment
- `JvmHeapRegion` (CLJ) — wraps plain ByteBuffer

**ISlabIO** (`alloc.cljc`): Slab-aware block I/O (12 methods).
- `CljsSlabIO` (CLJS) — delegates to module-level DataView state
- `JvmSlabCtx` (CLJ) — holds `IMemRegion[]` per slab class

---

## Goal

A unified **`eve/deftype`** macro that:
1. Works across all 6 environments (jvm-mmap, jvm-heap, node-mmap, node-sab, browser-localstorage, browser-sab)
2. Is `.cljc` — one source, platform-appropriate code via reader conditionals or `&env` detection
3. Generates types whose fields are backed by shared memory (slab-allocated)
4. Rewrites `set!` and `cas!` on fields to memory writes/CAS
5. The data structures (map, vec, set, list) are rebuilt on top of this macro
6. Users can build their own data structures on top of it
7. Callable as `eve/deftype` via public API

---

## The Six Options

### Option 1: "ISlabIO Everywhere" — Thread `sio` Through Every Deftype

Every eve-deftype instance carries an `ISlabIO` reference. The macro emits ISlabIO protocol calls for all field reads/writes.

```clojure
(eve/deftype EveHashMap
  [^:int32 cnt
   ^:int32 root-off]
  ICounted
  (-count [_] cnt))

;; Expands to (both platforms):
(deftype EveHashMap [sio__ ^long offset__]
  ICounted
  (-count [_]
    (let [cnt (-sio-read-i32 sio__ offset__ 4)]
      cnt)))
```

| | |
|---|---|
| **Pros** | Simplest macro; truly portable; testable (swap in heap ISlabIO); no global mutable state |
| **Cons** | Protocol dispatch per field read (~20-50ns); each instance carries sio ref; breaks CLJS optimization path |
| **Complexity** | Low |
| **Performance** | Worse on CLJS, same on JVM |

---

### Option 2: "Conditional Macro Emission" — One `.clj` Macro, Two Code Paths

Macro detects target via `(:ns &env)`. Emits inline DataView on CLJS, ISlabIO calls on JVM.

```clojure
;; CLJS expansion:
(deftype EveHashMap [offset__]
  ICounted
  (-count [_]
    (let [slab-base__ (resolve-dv! offset__)
          cnt (.getInt32 slab-dv__ (+ slab-base__ 4) true)]
      cnt)))

;; CLJ expansion:
(deftype EveHashMap [^long offset__ sio__]
  clojure.lang.Counted
  (count [_]
    (-sio-read-i32 sio__ offset__ 4)))
```

| | |
|---|---|
| **Pros** | Best performance (CLJS inline, JVM ISlabIO); single macro file |
| **Cons** | Must map CLJS protocol names to JVM interfaces (ICounted→Counted, -count→count, etc.); method signature differences; complex macro |
| **Complexity** | High (protocol mapping is the killer) |
| **Performance** | Best |

---

### Option 3: "Macro for Structure, Manual Protocol Impls"

Macro handles field layout, constructor, accessors. Protocol impls written manually in `.cljc` using generated accessors.

```clojure
(eve/deftype EveHashMap [^:int32 cnt ^:int32 root-off])
;; → generates: deftype, constructor, EveHashMap-cnt, EveHashMap-root-off accessors

;; User writes protocol impls in .cljc:
#?(:cljs (extend-type EveHashMap ICounted (-count [this] (EveHashMap-cnt this)))
   :clj  (...))
```

| | |
|---|---|
| **Pros** | Simple macro; full control; incremental migration |
| **Cons** | Verbose (every impl written twice); loses the macro's magic; accessor function-call overhead |
| **Complexity** | Low (macro), Medium (data structure code) |
| **Performance** | Slightly worse (accessor call per field read) |

---

### Option 4: "Unified Protocol Abstraction Layer"

A `eve/proto.cljc` maps CLJS protocol names to JVM equivalents. Macro uses unified names and a universal method naming convention.

```clojure
;; eve/proto.cljc:
#?(:cljs (def Counted ICounted)
   :clj  (def Counted clojure.lang.Counted))

(eve/deftype EveHashMap [^:int32 cnt ^:int32 root-off]
  eve.proto/Counted
  (count* [_] cnt))
```

| | |
|---|---|
| **Pros** | Write once; clean abstraction |
| **Cons** | Massive upfront work; method signature mismatches (valAt vs -lookup); leaky for edge-case interfaces (Iterable, Serializable); fragile maintenance |
| **Complexity** | Very High |
| **Performance** | Same as Option 2 |

---

### Option 5: "ISlabIO Unification Without Macro Change"

Accept separate deftypes. Maximize shared *algorithmic* code by routing all CLJS write paths through ISlabIO. This is what your research recommends for the data structure layer.

```clojure
;; Shared HAMT algorithm in .cljc:
(defn hamt-assoc [sio root-off kh kb vb shift] ...)

;; Platform-specific deftypes remain:
#?(:cljs (deftype EveHashMap [offset__] ...))
#?(:clj  (deftype EveHashMap [^long offset__ sio__] ...))
```

| | |
|---|---|
| **Pros** | Least disruptive; performance preserved; proven pattern; your research shows 105→0 DataView calls is mechanical |
| **Cons** | Still two deftypes per structure; doesn't give users `eve/deftype`; doesn't unify the macro |
| **Complexity** | Low |
| **Performance** | Best |

---

### Option 6: "eve2 Parallel Tree" — Fresh Start With ISlabIO-First Design

New `eve2.*` namespace tree alongside `eve.*`. Designed from day one for CLJC with ISlabIO as the universal memory abstraction.

The key insight: use **macro-time inlining** on CLJS to eliminate protocol dispatch overhead while keeping source-level ISlabIO abstraction.

```clojure
;; eve2/deftype generates:
;;   CLJS: (deftype Foo [offset__]) with inline DataView reads
;;   CLJ:  (deftype Foo [^long offset__ sio__]) with ISlabIO reads

;; User writes (in .cljc):
(eve2/deftype EveHashMap
  [^:int32 cnt ^:int32 root-off]
  ICounted
  (-count [_] cnt))    ;; macro rewrites `cnt` to the right memory read per platform
```

On CLJS, field reads expand to `(let [b (resolve-dv! offset__) dv resolved-dv] (.getInt32 dv (+ b 4) true))` — zero protocol dispatch, same as Gen 2.

On CLJ, field reads expand to `(-sio-read-i32 sio__ offset__ 4)` — ISlabIO dispatch.

Protocol name mapping is handled by the macro: users write CLJS-style protocol names (ICounted, ILookup). On CLJ, the macro translates to JVM equivalents. The mapping table is finite:

```
ICounted      → clojure.lang.Counted      | -count → count
ILookup       → clojure.lang.ILookup      | -lookup → valAt
IAssociative  → clojure.lang.Associative   | -assoc → assoc
IMap          → clojure.lang.IPersistentMap | -dissoc → without
ICollection   → clojure.lang.IPersistentCollection | -conj → cons
ISeqable      → clojure.lang.Seqable       | -seq → seq
IEquiv        → Object.equals + MapEquivalence
IHash         → clojure.lang.IHashEq       | -hash → hasheq
IMeta/IWithMeta → clojure.lang.IMeta/IObj
IFn           → clojure.lang.IFn           | -invoke → invoke
IReduce       → clojure.lang.IReduceInit   | -reduce → reduce
IKVReduce     → clojure.lang.IKVReduce     | -kv-reduce → kvreduce
IPrintWithWriter → Object.toString
```

| | |
|---|---|
| **Pros** | Clean slate; best performance via macro inlining; parallel development; proper testing; user-facing `eve2/deftype` |
| **Cons** | Most work; duplication during transition; macro-in-macro staging complexity |
| **Complexity** | High (but phased) |
| **Performance** | Best |

---

### Option 7: Hybrid — Option 5 First, Then Option 6

**Phase A**: Do the ISlabIO unification from your research (Option 5). Convert the 105 DataView calls. Get to 90-92% shared `.cljc`. This is mechanical, low-risk, and immediately valuable. No macro work needed.

**Phase B**: Build `eve2/deftype` macro in the new `eve2.*` tree (Option 6). This can take its time because Phase A already delivered the value.

**Phase C**: Port data structures from `eve.map` → `eve2.map` etc, using `eve2/deftype` and the now-unified ISlabIO algorithms.

| | |
|---|---|
| **Pros** | Immediate value from Phase A; low risk; Phase B can iterate on macro design; no big-bang migration |
| **Cons** | Three-phase project; temporary code churn in Phase A before Phase C replaces it |
| **Complexity** | Medium per phase |
| **Performance** | Best |

---

## Recommendation Matrix

| Option | CLJC? | Performance | Complexity | Incremental? | User `eve/deftype`? | Preserves current perf? |
|--------|-------|-------------|------------|--------------|---------------------|------------------------|
| 1. ISlabIO Everywhere | Yes | Worse CLJS | Low | Yes | Yes | No |
| 2. Conditional Emission | Yes | Best | High | Partial | Yes | Yes |
| 3. Structure-Only Macro | Partial | Good | Low | Yes | Partial | No |
| 4. Unified Protocol Layer | Yes | Best | Very High | No | Yes | Yes |
| 5. ISlabIO Unification | Partial | Best | Low | Yes | No | Yes |
| 6. eve2 Parallel Tree | Yes | Best | High | Yes | Yes | Yes |
| **7. Hybrid (5→6)** | **Yes** | **Best** | **Medium/phase** | **Yes** | **Yes** | **Yes** |

---

## Detailed Plan for Option 7 (Recommended)

### Phase A: ISlabIO Unification (your research, 1-2 weeks)

All work in existing `eve.*` tree. No macro changes.

1. **Add `-sio-read-f64` / `-sio-write-f64!` to ISlabIO** (~20 lines)
2. **Add UTF-8 encode/decode helpers** to `serialize.cljc` (~10 lines)
3. **Convert `serialize.cljc`** CLJS block: replace 44 DataView calls with ISlabIO
4. **Convert `map.cljc`** write path: replace 9 DataView + 19 `alloc/read-*` calls
5. **Convert `set.cljc`** write path: replace 8+10 calls
6. **Convert `vec.cljc`** write path: replace 7 calls
7. **Convert `list.cljc`**: replace 18 DataView calls
8. **Convert `obj.cljc`**: replace 10 calls
9. **Convert `array.cljc`**: replace 4 calls
10. **Merge `runtime.cljs` + `registry.clj` → `registry.cljc`**
11. **Convert `xray.cljs` → `xray.cljc`**

**Result**: 74% → 90-92% shared. Zero new APIs. All existing tests pass.

### Phase B: `eve2/deftype` Macro (2-3 weeks)

New `src/eve2/` directory. Old code untouched.

1. **`eve2/registry.cljc`** — compile-time type registry (from current `registry.clj`)
2. **`eve2/deftype.clj`** — the macro
   - Detects CLJS vs CLJ via `(:ns &env)`
   - CLJS: emits `[offset__]` + inline `resolve-dv!` + DataView reads (Gen 2 pattern)
   - CLJ: emits `[^long offset__ sio__]` + ISlabIO protocol calls
   - Protocol name mapping table (finite, ~15 entries)
   - `set!` / `cas!` rewriting
   - Constructor generation (CLJS: slab alloc, CLJ: ISlabIO alloc)
3. **`eve2/deftype_test.cljc`** — comprehensive macro expansion tests
4. **`eve2/alpha.cljc`** — public API entry point (`eve2/deftype` macro re-export)

### Phase C: Data Structure Port (3-4 weeks)

Port one at a time, keeping old code working.

1. **`eve2/map.cljc`** — Port from `eve.map`, using `eve2/deftype` for EveHashMap
   - HAMT algorithm is shared `.cljc` (already 85% shared)
   - Protocol impls written once in the macro body
   - Pools, caching stay as platform-specific `#?` blocks (they're inherently mutable state)
2. **`eve2/vec.cljc`**
3. **`eve2/set.cljc`**
4. **`eve2/list.cljc`**
5. **`eve2/atom.cljc`** — Cross-process atom using new data structures

Each port runs the full test suite before moving to the next.

### Phase D: Migration & Cleanup

1. Update `eve.alpha` to re-export from `eve2.*`
2. Deprecation warnings on old `eve.*` types
3. Remove old code when confident

---

## Open Questions

1. **Protocol mapping scope**: Should the macro support arbitrary user protocols, or only the curated set of ~15 collection protocols? Curated is simpler and covers 99% of use cases.

2. **Serialized fields**: The old `eve-deftype` (Gen 1) supported `(no hint) → serialized, any Clojure value`. Worth including? Adds complexity but enables richer user-level types.

3. **Transient support**: Should transients be macro-supported (the macro generates ITransientCollection impls) or hand-rolled per data structure?

4. **Pooling**: Currently each data structure has its own pool. Should pooling move into the alloc layer so `eve2/deftype` types get it for free?

5. **Browser LocalStorage**: Not yet built. Should the deftype handle it, or is it a separate IMemRegion implementation that "just works" under ISlabIO?

6. **`eve2` vs in-place**: Should we use `eve2.*` namespaces (parallel tree) or modify `eve.*` in-place? Parallel tree is safer but more work. In-place risks breaking existing tests during transition.
