# JVM Ambient Slab Access — Eliminating `sio` and `coll-factory`

> Generated: 2026-03-11
> Goal: Make the JVM side mirror the CLJS pattern — global/ambient slab access,
>       no explicit `sio` threading, no `coll-factory` callback.

---

## The Problem

The CLJS implementation accesses slab memory through **module-level mutable state**
(DataView arrays in `alloc.cljc`). Collection deftypes store only structural fields
(`cnt`, `root-off`, `header-off`) and call global alloc functions directly. There is
no `sio` parameter, no `coll-factory` callback. Nested collections are reconstructed
via a **constructor registry** (`sab-type-constructors` in `serialize.cljc`).

The JVM implementation diverged: it threads an explicit `sio` (ISlabIO instance)
through every collection type as a deftype field, and passes `coll-factory` as a
callback to reconstruct nested collections during deserialization. This is ~819
occurrences of `sio` and ~142 occurrences of `coll-factory` across 6 source files
plus ~325 `sio` references in test files.

This divergence causes:
1. API asymmetry — JVM constructors need `sio` + `coll-factory`, CLJS constructors don't
2. The `coll-factory` callback exists solely to break a circular dependency that
   CLJS doesn't have (because CLJS uses a registry, not explicit callbacks)
3. Every JVM function signature is polluted with `sio` threading
4. Test code must manually wire up `sio` and `coll-factory`

---

## The CLJS Pattern (target state)

### How CLJS accesses slab memory

```
alloc.cljc (CLJS side):
  - Module-level arrays: data-views[], bitmaps[], etc.
  - resolve-u8!(slab-offset) → sets resolved-dv, resolved-base
  - read-u8(slab-offset, field-off) → global DataView read
  - alloc!(size) → global allocator
  - free!(slab-offset) → global free
```

CLJS collections call `eve-alloc/resolve-u8!`, `eve-alloc/read-u8`, etc. directly.
No `sio` parameter needed.

### How CLJS reconstructs nested collections

```
serialize.cljc:
  sab-type-constructors — Map<tag, (fn [sab header-off] → collection)>

  register-sab-type-constructor!(tag, flat-tag, ctor-fn)
    → stores ctor-fn at tag in sab-type-constructors

Each collection type registers itself:
  map.cljc:2156   → FAST_TAG_SAB_MAP  → (fn [_sab header-off] (make-eve-hash-map-from-header header-off))
  vec.cljc:996    → SabVecN-type-id   → (fn [_sab header-off] ...)
  list.cljc:1006  → 0x14              → (fn [_sab header-off] ...)
  set.cljc:??     → (similar registration)

deserialize-element (serialize.cljc:894-899):
  When encountering FAST_TAG_SAB_MAP..FAST_TAG_SAB_LIST:
    ctor = sab-type-constructors.get(tag)
    return (ctor sab offset)
```

No circular dependency. No callback threading. The registry breaks the cycle.

### How CLJS deref works

```
atom.cljc (CLJS):
  cljs-mmap-deref:
    ptr = load-i32(root-r, atom-slot-offset)
    type-id = alloc/read-header-type-byte(ptr)    ;; global alloc call, no sio
    ctor = ser/get-header-constructor(type-id)
    return (ctor nil ptr)                          ;; just offset, no sio
```

---

## The JVM Target Pattern

### Step 1: Make `*jvm-slab-ctx*` the ambient context

`*jvm-slab-ctx*` already exists as a dynamic var (`alloc.cljc:546`). It's documented
as "bind this before calling collection constructors." But currently it's only used by
a few user-facing constructors — the core read/write paths all take explicit `sio`.

**Target:** All JVM slab access goes through `*jvm-slab-ctx*`. No function takes `sio`
as a parameter. The atom binds `*jvm-slab-ctx*` before any operation.

### Step 2: Create a JVM constructor registry (mirror CLJS)

Add a JVM-side `jvm-type-constructors` registry in `serialize.cljc` (or `alloc.cljc`):

```clojure
;; :clj reader conditional
(def jvm-type-constructors (java.util.concurrent.ConcurrentHashMap.))

(defn register-jvm-type-constructor! [tag ctor-fn]
  (.put jvm-type-constructors (int tag) ctor-fn))

(defn get-jvm-type-constructor [tag]
  (.get jvm-type-constructors (int tag)))
```

Each collection type registers itself (no circular dependency):
```clojure
;; map.cljc (JVM reader conditional)
(ser/register-jvm-type-constructor! 0x10
  (fn [header-off] (jvm-eve-hash-map-from-offset header-off)))
(ser/register-jvm-type-constructor! 0xED  ;; header type-id for map
  (fn [header-off] (jvm-eve-hash-map-from-offset header-off)))

;; set.cljc
(ser/register-jvm-type-constructor! 0x11
  (fn [header-off] (jvm-eve-hash-set-from-offset header-off)))

;; vec.cljc
(ser/register-jvm-type-constructor! 0x12
  (fn [header-off] (jvm-sabvec-from-offset header-off)))

;; list.cljc
(ser/register-jvm-type-constructor! 0x13
  (fn [header-off] (jvm-sab-list-from-offset header-off)))
```

### Step 3: `eve-bytes->value` uses registry instead of `coll-factory`

```clojure
;; Current (BROKEN):
0x10 (if (and sio coll-factory)
       (coll-factory 0x10 sio (read-i32-le b 3))
       (throw ...))

;; Target (MIRRORS CLJS):
0x10 (let [ctor (ser/get-jvm-type-constructor 0x10)]
       (if ctor
         (ctor (read-i32-le b 3))
         (throw ...)))
```

No `sio`. No `coll-factory`. The constructor reads slab via `*jvm-slab-ctx*`.

### Step 4: Remove `sio` and `coll-factory` from deftype fields

```clojure
;; Current:
(deftype EveHashMap [^long cnt ^long root-off ^long header-off
                     sio coll-factory _meta])

;; Target:
(deftype EveHashMap [^long cnt ^long root-off ^long header-off _meta])
```

Same for `SabVecRoot`, `EveHashSet`, `SabList`.

### Step 5: All JVM helper functions read from `*jvm-slab-ctx*`

```clojure
;; Current:
(defn jvm-hamt-get [sio root-off k not-found coll-factory]
  (let [nt (-sio-read-u8 sio off 0)] ...))

;; Target:
(defn jvm-hamt-get [root-off k not-found]
  (let [sio alloc/*jvm-slab-ctx*
        nt (-sio-read-u8 sio off 0)] ...))
```

The `sio` local is resolved once at the top of each function from the dynamic var.
This is the same pattern CLJS uses (module-level state), just with Clojure's dynamic
var mechanism.

### Step 6: Atom binds `*jvm-slab-ctx*` around operations

```clojure
;; Current:
(defn- jvm-mmap-deref [{:keys [root-r sio] :as domain-state} atom-slot-idx]
  (alloc/refresh-jvm-slab-regions! sio)
  (jvm-read-root-value sio (mem/-load-i32 root-r ...)))

;; Target:
(defn- jvm-mmap-deref [{:keys [root-r sio] :as domain-state} atom-slot-idx]
  (binding [alloc/*jvm-slab-ctx* sio]
    (alloc/refresh-jvm-slab-regions!)
    (jvm-read-root-value (mem/-load-i32 root-r ...))))
```

The domain-state still owns the `JvmSlabCtx` instance. It binds it as ambient context
for the duration of each operation. This exactly mirrors how CLJS sets up module-level
slab state before operations.

---

## Scope of Changes

### Source files

| File | `sio` refs | `coll-factory` refs | Changes |
|------|-----------|---------------------|---------|
| `map.cljc` | 333 | 37 | Remove `sio`/`coll-factory` from JVM deftype fields. Remove from all JVM helper fn signatures. Each fn resolves `sio` from `*jvm-slab-ctx*` internally. Register constructors. |
| `set.cljc` | 198 | 29 | Same pattern as map. |
| `vec.cljc` | 150 | 24 | Same pattern as vec. |
| `list.cljc` | 53 | 24 | Same pattern as list. |
| `mem.cljc` | 38 | 21 | Remove `sio`/`coll-factory` params from `eve-bytes->value`. Use registry for tag dispatch. Remove `sio` from `value+sio->eve-bytes` (rename to `value->eve-bytes*` or similar). |
| `atom.cljc` | 47 | 7 | Bind `*jvm-slab-ctx*` around deref/swap. Remove `jvm-coll-factory` entirely. Remove `sio` from `jvm-read-root-value`, `jvm-resolve-new-ptr`, etc. Domain still owns the JvmSlabCtx. |
| `alloc.cljc` | many | 0 | Add `refresh-jvm-slab-regions!` 0-arity that reads from `*jvm-slab-ctx*`. Ensure all JVM alloc fns have 0-arity versions using ambient ctx. |
| `serialize.cljc` | 0 | 0 | Add JVM constructor registry (`jvm-type-constructors`). |

### Test files

| File | `sio` refs | Changes |
|------|-----------|---------|
| `jvm_map_test.clj` | 72 | Remove `sio` setup. Use `binding [*jvm-slab-ctx* ...]`. Remove `coll-factory`. |
| `jvm_set_test.clj` | 81 | Same. |
| `jvm_vec_test.clj` | 72 | Same. |
| `jvm_list_test.clj` | 39 | Same. |
| `jvm_slab_test.clj` | 14 | Same. |
| `jvm_conformance_test.clj` | 47 | Same. |
| `jvm_fuzz_test.clj` | 0 | Likely unchanged. |
| `jvm_atom_test.clj` | 0 | Uses atom API, not direct sio. |
| `jvm_array_test.clj` | ? | May need updates for array/obj. |
| `jvm_obj_test.clj` | ? | May need updates. |

### Additional files

| File | Changes |
|------|---------|
| `array.cljc` | JVM array helpers take `sio` — update to ambient. |
| `obj.cljc` | JVM obj helpers take `sio` — update to ambient. |
| `bench/eve/data_bench.clj` | Update bench code if it uses `sio` directly. |
| `bench/eve/stress_atom.clj` | Likely unchanged (uses atom API). |

---

## Implementation Phases

### Phase A — Registry Infrastructure

**Files:** `serialize.cljc`, `alloc.cljc`

1. Add `jvm-type-constructors` ConcurrentHashMap to `serialize.cljc` (JVM only)
2. Add `register-jvm-type-constructor!` and `get-jvm-type-constructor`
3. Add 0-arity ambient versions of key alloc functions that read from `*jvm-slab-ctx*`:
   - `refresh-jvm-slab-regions!` (0-arity)
   - `jvm-read-header-type-byte` (1-arity: just slab-off)
   - `jvm-alloc-scalar-block!` (1-arity: just value)
   - `jvm-read-scalar-block` (1-arity: just slab-off)
4. Keep existing arities for backward compat during migration

**Test:** Registry can store and retrieve constructors. Ambient alloc fns work when
`*jvm-slab-ctx*` is bound.

**Risk:** Low. Additive only.

---

### Phase B — `eve-bytes->value` uses registry

**Files:** `mem.cljc`

1. Change `eve-bytes->value` to use `get-jvm-type-constructor` for tags 0x10–0x13
   instead of `coll-factory` callback
2. The constructor receives only `slab-offset` and reads sio from `*jvm-slab-ctx*`
3. Keep the 3-arity `[b sio coll-factory]` as deprecated shim temporarily
4. Add 1-arity `[b]` that works when `*jvm-slab-ctx*` is bound

**Also:**
5. `value+sio->eve-bytes` → add 1-arity `[v]` that reads sio from ambient ctx
6. The `IEveRoot` fast path already works (no sio needed for pointer passthrough)

**Test:** `eve-bytes->value` roundtrips work with registry. Nested collections
deserialize correctly.

**Risk:** Medium. This is the central deserialization function. Must ensure all
callers are updated or the deprecated shim works.

---

### Phase C — Map (`map.cljc`) migration

**Files:** `map.cljc`

The largest file. ~333 sio refs, ~37 coll-factory refs. All in `:clj` reader conditionals.

1. Remove `sio` and `coll-factory` from JVM `EveHashMap` deftype fields
2. Every JVM method that uses `sio` → add `(let [sio alloc/*jvm-slab-ctx*] ...)` at top
3. Update all JVM helper functions:
   - `jvm-hamt-get` — remove `sio` and `coll-factory` params
   - `jvm-hamt-kv-reduce` — remove `sio` and `coll-factory` params
   - `jvm-hamt-lazy-seq` — remove `sio` and `coll-factory` params
   - `jvm-hamt-build-entries!` — remove `sio` param
   - `jvm-hamt-assoc!` — remove `sio` param
   - `jvm-hamt-dissoc` — remove `sio` param
   - `jvm-eve-hash-map-from-offset` — remove `sio` and `coll-factory` params
   - `jvm-write-map!` — remove `sio` param
   - All other `jvm-*` helpers
4. Register JVM map constructor:
   ```clojure
   (ser/register-jvm-type-constructor! 0x10
     (fn [header-off] (jvm-eve-hash-map-from-offset header-off)))
   (ser/register-jvm-type-constructor! 0xED
     (fn [header-off] (jvm-eve-hash-map-from-offset header-off)))
   ```
5. Update `jvm_map_test.clj`:
   - Replace `sio` setup with `(binding [alloc/*jvm-slab-ctx* (alloc/make-jvm-heap-slab-ctx)] ...)`
   - Remove `coll-factory` definition
   - Remove all `sio` and `coll-factory` args from function calls

**Test:** All JVM map tests pass. `clojure -M:jvm-test` green.

**Risk:** High — largest change, most callsites. Do this first among the types to
establish the pattern, then the others follow mechanically.

---

### Phase D — Set (`set.cljc`) migration

**Files:** `set.cljc`

Same pattern as Phase C. ~198 sio refs, ~29 coll-factory refs.

1. Remove `sio`/`coll-factory` from JVM `EveHashSet` deftype fields
2. Update all JVM helper functions (same pattern: resolve sio from ambient)
3. Register JVM set constructors (0x11, 0xEE)
4. Update `jvm_set_test.clj`

**Test:** All JVM set tests pass.

---

### Phase E — Vec (`vec.cljc`) migration

**Files:** `vec.cljc`

~150 sio refs, ~24 coll-factory refs.

1. Remove `sio`/`coll-factory` from JVM `SabVecRoot` deftype fields
2. Update all JVM helper functions
3. Register JVM vec constructors (0x12)
4. Update `jvm_vec_test.clj`

**Test:** All JVM vec tests pass.

---

### Phase F — List (`list.cljc`) migration

**Files:** `list.cljc`

~53 sio refs, ~24 coll-factory refs.

1. Remove `sio`/`coll-factory` from JVM `SabList` deftype fields
2. Update all JVM helper functions
3. Register JVM list constructors (0x13)
4. Update `jvm_list_test.clj`

**Test:** All JVM list tests pass.

---

### Phase G — Atom (`atom.cljc`) cleanup

**Files:** `atom.cljc`

1. Remove `jvm-coll-factory` entirely (replaced by registry)
2. `jvm-read-root-value` — use `get-jvm-type-constructor` for dispatch, no sio param
3. `jvm-resolve-new-ptr` — remove sio param (IEveRoot path doesn't need it;
   fallback write paths use ambient)
4. `jvm-mmap-deref` — bind `*jvm-slab-ctx*` from domain-state, then call
5. `jvm-mmap-swap!` — bind `*jvm-slab-ctx*` from domain-state
6. `jvm-try-flush-retires!` — use ambient for `-sio-free!`
7. `jvm-collect-replaced-nodes` — use ambient for `-sio-read-*`
8. Domain-state still stores the `JvmSlabCtx` instance (owns the mmap regions)

**Test:** All JVM atom tests and mmap-atom-e2e tests pass.

**Risk:** Medium. Atom is the correctness-critical coordination layer.

---

### Phase H — Array/Obj and remaining files

**Files:** `array.cljc`, `obj.cljc`

1. Update JVM array/obj helpers to use ambient sio
2. Update `jvm_array_test.clj`, `jvm_obj_test.clj`
3. Update `data_bench.clj` if it references sio directly

---

### Phase I — Cleanup

1. Remove deprecated `eve-bytes->value` 3-arity shim
2. Remove deprecated `value+sio->eve-bytes` 2-arity (rename to `value->eve-bytes*` or keep name)
3. Remove all `sio` parameter references from docstrings
4. Ensure `alloc.cljc` JVM alloc/free/read functions all have 0-arity ambient versions
5. Run full test suite (both CLJS and JVM)
6. Update doc files (`jvm-cljs-gap-analysis.md`, `jvm-migration-remediation.md`)

---

## Phase Dependencies

```
A (registry + ambient alloc) ── must be first
├── B (eve-bytes->value) ── after A
│   ├── C (map) ── after B (largest, establishes pattern)
│   │   ├── D (set) ── after C (follows pattern)
│   │   ├── E (vec) ── after C (follows pattern)
│   │   └── F (list) ── after C (follows pattern)
│   │       └── G (atom) ── after C,D,E,F (all types migrated)
│   │           └── H (array/obj) ── after G
│   │               └── I (cleanup) ── last
```

D, E, F can run in parallel after C establishes the pattern.

---

## Key Design Decisions

### Why dynamic var, not a global atom?

A dynamic var (`*jvm-slab-ctx*`) gives us:
- Thread-local binding (each thread can have its own slab context if needed)
- Same semantics as CLJS module-level state (set once, available everywhere)
- Already exists and is documented

A global `defonce` atom would work too but loses thread-local flexibility.
Since `*jvm-slab-ctx*` already exists and the atom code already creates JvmSlabCtx
instances, binding is the natural approach.

### Why not make ISlabIO methods take 0 args?

ISlabIO protocol methods take `[ctx slab-offset field-off]`. We keep this — the
protocol is fine. The change is that callers resolve `ctx` from the dynamic var
instead of receiving it as a parameter. The protocol itself stays unchanged.

### What about performance?

Dynamic var access in Clojure is a thread-local lookup — effectively free compared
to slab I/O. The CLJS pattern (module-level mutable state) is similarly zero-cost.
No performance regression expected.

### What about concurrent slab contexts?

The atom's domain-state owns the JvmSlabCtx. Multiple atoms (different directories)
each have their own JvmSlabCtx. The dynamic var binding scopes each operation to the
right context. This is safe — each thread binds the var for the duration of its
operation, and Clojure's binding conveyance handles thread pools correctly.

---

## Verification Plan

After each phase:
1. `clojure -M:jvm-test` — all JVM tests green
2. `npx shadow-cljs compile eve-test && node target/eve-test/all.js all` — all CLJS tests green (no regressions)
3. After Phase G: `node target/eve-test/all.js mmap-atom-e2e` — cross-process tests green

Final:
4. Full green baseline per CLAUDE.md
5. All 14 test suites pass
