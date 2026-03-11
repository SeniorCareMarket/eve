# Eve CLJC Unification Analysis

> Generated: 2026-03-10
> Objective: Identify what can be unified under a common `.cljc` deftype/function
> system vs. what must remain platform-specific.

---

## Executive Summary

The CLJS and JVM implementations of Eve's four data structures share identical
data formats, HAMT/trie algorithms, and slab layouts — but their code is almost
entirely duplicated. The CLJS code lives inside `#?(:cljs (do ...))` blocks and
the JVM code lives inside `#?(:clj (do ...))` blocks, with minimal shared code
between them. The duplication spans ~3,000 lines across the four files.

**What CAN be unified:**
1. Bitwise HAMT helpers (trivially — ~40 lines per file)
2. The portable hash function (already unified in `map.cljc`)
3. Constants and header layouts (already mostly unified)
4. HAMT algorithmic logic via ISlabIO (medium effort — ~500 lines, requires CLJS to use ISlabIO)
5. `print-method` / `IPrintWithWriter` format strings (trivially)

**What CANNOT be unified:**
1. The deftypes themselves (different interface names, method signatures)
2. Memory access layer (CLJS uses DataView interop, JVM uses ISlabIO protocol)
3. Platform-specific optimizations (CLJS node pools, mutable flags, SIMD)
4. Transient implementations (CLJS-only mutable semantics differ from JVM)

**Estimated unification potential:** ~20-25% of duplicated code, primarily the
bitwise helpers and (if CLJS migrates to ISlabIO) the HAMT traversal functions.
The deftypes are inherently platform-specific and represent ~40% of the code.

---

## Current Code Structure

### File sizes and platform split

| File | Total lines | Shared | CLJS-only | JVM-only |
|------|-------------|--------|-----------|----------|
| `map.cljc` | ~3005 | ~120 (constants + portable hash) | ~2310 (lines 119-2425) | ~580 (lines 2431-3005) |
| `set.cljc` | ~1638 | ~30 (constants) | ~1330 (lines 48-1360) | ~280 (lines 1363-1638) |
| `vec.cljc` | ~1307 | ~30 (constants) | ~1000 (lines 33-1028) | ~280 (lines 1030-1307) |
| `list.cljc` | ~1153 | ~30 (constants) | ~1070 (lines 33-1100) | ~55 (lines 1103-1153) |

The shared sections are tiny — just constants and (in map.cljc) the portable hash.

---

## Category 1: Trivially Unifiable — Bitwise Helpers

### Current duplication

The following pure functions are defined **separately** in CLJS and JVM blocks
in both `map.cljc` and `set.cljc`:

| Function | Map CLJS | Map JVM | Set CLJS | Set JVM |
|----------|----------|---------|----------|---------|
| `popcount32` | line 270 | line 2437 | (via `simd`) | line 1365 |
| `mask-hash` | line 276 | line 2442 | (inline) | line 1368 |
| `bitpos` | line 279 | line 2445 | (inline) | line 1369 |
| `has-bit?` | line 282 | line 2448 | (inline) | line 1370 |
| `get-index` | line 285 | line 2451 | (inline) | line 1371 |
| `hashes-start-off` | line 326 | line 2454 | N/A | N/A |
| `kv-data-start-off` | line 331 | line 2457 | N/A | N/A |
| `children-start-off` | N/A | N/A | (inline) | line 1373 |

### Why they're duplicated

- `popcount32` has different implementations per platform:
  - CLJS: bit-manipulation + `js*` for 32-bit multiply
  - JVM: `Integer/bitCount`
- All others (`mask-hash`, `bitpos`, `has-bit?`, `get-index`) are **identical**
  between platforms — pure integer arithmetic.

### Unification approach

```clojure
;; In shared section of each .cljc file:
(defn- popcount32 [n]
  #?(:cljs (let [n (- (bit-and n 0xFFFFFFFF) ...)] ...)  ;; existing CLJS impl
     :clj  (Integer/bitCount (unchecked-int n))))

(defn- mask-hash [kh shift]
  (bit-and (unsigned-bit-shift-right kh shift) MASK))

(defn- bitpos [kh shift]
  (bit-shift-left 1 (mask-hash kh shift)))

(defn- has-bit? [bitmap bit]
  (not (zero? (bit-and bitmap bit))))

(defn- get-index [bitmap bit]
  (popcount32 (bit-and bitmap (dec bit))))
```

**Savings:** ~15 lines per file, 4 files = ~60 lines. Trivial but eliminates
a class of copy-paste bugs.

**Further optimization:** Extract these into a shared namespace
`eve.hamt-util` (or `eve.deftype-proto.hamt`) that both map and set import.
Currently, map and set each define their own copies.

---

## Category 2: Potentially Unifiable — HAMT Traversal via ISlabIO

### The ISlabIO protocol

Both CLJS and JVM define memory access through `ISlabIO` (in `alloc.cljc`):

```clojure
(defprotocol ISlabIO
  (-sio-read-u8 [sio off field-off])
  (-sio-read-i32 [sio off field-off])
  (-sio-read-bytes [sio off field-off len])
  (-sio-write-u8! [sio off field-off val])
  (-sio-write-i32! [sio off field-off val])
  (-sio-write-bytes! [sio off field-off bytes])
  (-sio-alloc! [sio size])
  (-sio-free! [sio off])
  ...)
```

The JVM HAMT code uses this protocol exclusively. If the CLJS code also used
ISlabIO (instead of direct DataView interop), the traversal functions could be
shared.

### Current CLJS memory access pattern

CLJS uses a **resolve-then-read** pattern for performance:

```clojure
;; CLJS (map.cljc, set.cljc):
(let [base (eve-alloc/resolve-dv! slab-off)]
  (.getUint32 eve-alloc/resolved-dv (+ base 4) true)  ;; direct DataView
  (.getUint32 eve-alloc/resolved-dv (+ base 8) true))
```

JVM uses ISlabIO protocol:

```clojure
;; JVM:
(-sio-read-i32 sio off 4)
(-sio-read-i32 sio off 8)
```

### Unification feasibility

**Option A: CLJS adopts ISlabIO for HAMT traversal**

- Pros: Enables shared HAMT functions (~200-300 lines of traversal code per type)
- Cons: Performance regression — CLJS currently uses resolved DataView with
  zero-overhead JS interop. ISlabIO protocol dispatch adds overhead per read.
  For HAMT traversal (many reads per lookup), this could be significant.
- **Verdict:** Not recommended unless ISlabIO can be made zero-cost on CLJS
  (e.g., via macros that inline the DataView calls).

**Option B: Macro-based shared algorithm**

Define HAMT algorithms as macros that emit platform-specific memory access:

```clojure
(defmacro read-node-type [off]
  `#?(:cljs (let [base# (eve-alloc/resolve-dv! ~off)]
              (.getUint8 eve-alloc/resolved-dv base#))
      :clj  (-sio-read-u8 ~'sio ~off 0)))
```

- Pros: Zero-cost abstraction; shared algorithm, platform-specific access
- Cons: Macro complexity; harder to debug; reader conditionals in macros
  are tricky (must be in `.cljc` macro file, expanded at compile time)
- **Verdict:** Possible but high complexity. Only worthwhile for the core
  traversal functions that are unlikely to change.

**Option C: Higher-order functions with protocol**

Define traversal functions that take a "reader" protocol:

```clojure
(defn hamt-get [reader root-off k not-found]
  (let [kb (serialize k)
        kh (portable-hash-bytes kb)]
    (loop [off root-off, shift 0]
      ...
      (let [nt (read-u8 reader off 0)]
        ...))))
```

- Pros: Clean separation; testable; works today
- Cons: Protocol dispatch overhead on every read (same as Option A)
- **Verdict:** Same performance concern as Option A.

### Recommendation

Keep HAMT traversal platform-specific for now. The performance cost of
abstracting memory access is too high for the hot path. Instead, focus on:
1. Unifying the bitwise helpers (Category 1)
2. Ensuring algorithmic parity through shared test specifications
3. Using the portable hash function consistently (already done for map)

---

## Category 3: Cannot Unify — Deftypes

### Why deftypes must be platform-specific

The CLJS and JVM deftypes implement different interfaces with different method
names:

| Concept | CLJS Protocol | JVM Interface |
|---------|---------------|---------------|
| Count | `ICounted` / `-count` | `clojure.lang.Counted` / `count` |
| Lookup | `ILookup` / `-lookup` | `clojure.lang.ILookup` / `valAt` |
| Assoc | `IAssociative` / `-assoc` | `clojure.lang.Associative` / `assoc` |
| Seq | `ISeqable` / `-seq` | `clojure.lang.Seqable` / `seq` |
| Reduce | `IReduce` / `-reduce` | `clojure.lang.IReduce` / `reduce` |
| Hash | `IHash` / `-hash` | `clojure.lang.IHashEq` / `hasheq` |
| Print | `IPrintWithWriter` / `-pr-writer` | `print-method` multimethod |
| Fn call | `IFn` / `-invoke` | `clojure.lang.IFn` / `invoke` |
| Transient | `IEditableCollection` | `clojure.lang.IEditableCollection` |
| Metadata | `IMeta`/`IWithMeta` | `clojure.lang.IMeta`/`IObj` |
| Java interop | N/A | `java.util.Map`/`List`/`Set`, `Iterable` |

Clojure's `deftype` in `.cljc` files supports reader conditionals for interface
lists, but the method bodies also differ (different method names, different
calling conventions). A single deftype cannot implement both CLJS and JVM
interfaces.

### Partial unification: shared method bodies

Some method *bodies* are identical across platforms (the logic, not the protocol
method name). These could be extracted into shared helper functions:

```clojure
;; Shared:
(defn- eve-map-equiv [this other count-fn get-fn seq-fn]
  ...)

;; CLJS deftype:
IEquiv
(-equiv [this other] (eve-map-equiv this other -count -lookup -seq))

;; JVM deftype:
IPersistentCollection
(equiv [this other] (eve-map-equiv this other .count .valAt .seq))
```

But the gains are marginal — most method bodies are 1-5 lines and the overhead
of the indirection hurts readability more than the duplication.

---

## Category 4: Structural Differences That Prevent Unification

### 4a. CLJS-only features with no JVM equivalent

| Feature | File | Lines | Purpose |
|---------|------|-------|---------|
| Node pools (alloc recycling) | `set.cljc` | 60-78 | Amortize alloc cost |
| SIMD integration | `set.cljc` | import | Vectorized node ops |
| Mutable flags (`hamt-conj-added?`) | `set.cljc` | 659-660 | Avoid alloc for booleans |
| `_modified_khs` tracking | `set.cljc` | 1053-1058 | Retirement optimization |
| `d/IDirectSerialize` | all types | various | Skip generic serialize path |
| `d/ISabStorable` | all types | various | Slab storage marker |
| `d/ISabRetirable` | all types | various | Epoch GC integration |

These are deeply tied to the CLJS runtime model (single-threaded, no GC pressure
from protocol dispatch, mutable-in-place optimization) and have no JVM equivalent.

### 4b. JVM-only features with no CLJS equivalent

| Feature | File | Lines | Purpose |
|---------|------|-------|---------|
| `jvm-hashed?` flag | `map.cljc` | 2859 | Track hash compatibility |
| `coll-factory` parameter | all types | various | Nested collection deser |
| `java.util.Map`/`Set`/`List` | all types | various | Java interop |
| `MapEquivalence` marker | `map.cljc` | 2873 | Clojure equiv dispatch |
| Metadata (`_meta` field) | map/vec/set | various | JVM metadata support |

### 4c. Hash function divergence (set-specific)

The set uses `clojure.core/hash` (platform-specific) while the map uses
`portable-hash-bytes` (shared Murmur3). This is the deepest structural
divergence — it means the same logical set has **different HAMT shapes** on
CLJS vs JVM. Until this is resolved (see impl plan Phase 0), the set's HAMT
code cannot be shared even in principle.

---

## Unification Roadmap

### Quick wins (do now)

1. **Move bitwise helpers to shared section** in `map.cljc` and `set.cljc`
   - `mask-hash`, `bitpos`, `has-bit?`, `get-index` are platform-identical
   - `popcount32` needs `#?` for the one platform-specific line
   - ~60 lines of dedup across files

2. **Extract `portable-hash-bytes` to shared namespace**
   - Currently in `map.cljc` shared section (lines 54-113)
   - Set needs it for Phase 0 of the impl plan
   - Create `eve.hamt-util` or add to `eve.deftype-proto.data`
   - Both map and set import from there

3. **Shared header read/write helpers**
   - Header layouts are identical across platforms
   - Define `read-map-header`, `read-set-header`, `read-vec-header`,
     `read-list-header` in shared sections using ISlabIO
   - ~20 lines per type

### Medium-term (after JVM parity work)

4. **Shared HAMT traversal via ISlabIO** (if perf acceptable)
   - Benchmark CLJS ISlabIO overhead vs. direct DataView
   - If <5% regression, migrate CLJS HAMT reads to ISlabIO
   - Share `hamt-get`, `hamt-reduce`, `hamt-build-entries` across platforms
   - ~500 lines of dedup in map, ~300 in set

5. **Shared `equiv` / `hash` logic**
   - Extract collection equality and hash computation into shared functions
   - Platform-specific deftypes call the shared logic

### Not recommended

6. **Unified deftype** — The interface names, method signatures, and
   platform-specific features make this impractical. The cost of the abstraction
   layer would exceed the maintenance cost of two separate deftypes.

7. **Macro-based HAMT** — Too complex, too hard to debug, and the CLJS code
   is likely to continue evolving independently (SIMD, pools, etc.).

---

## Impact on JVM Parity Implementation Plan

The cljc unification objective is **complementary** to the JVM parity plan,
not blocking. Recommended sequencing:

1. **Phase 0 of parity plan** (set hash migration) is a prerequisite for set
   unification. Do this first.
2. **Quick wins (items 1-3)** can be done during or after parity Phase 0.
3. **Parity Phases 1-6** should proceed with platform-specific code. Trying to
   unify while adding features would double the complexity.
4. **Medium-term unification (items 4-5)** should happen after JVM parity is
   achieved and both platforms are stable. This is a refactoring pass, not a
   feature pass.

---

## Summary Table

| Code Category | Lines (est.) | Unifiable? | Effort | Priority |
|---------------|-------------|------------|--------|----------|
| Constants & headers | ~120 | Already done | — | — |
| Portable hash | ~60 | Done (map); needed for set | Low | High |
| Bitwise helpers | ~60 | Yes — trivially | Low | High |
| HAMT traversal | ~800 | Maybe — perf dependent | Medium | Medium |
| HAMT write ops | ~600 | Maybe — perf dependent | Medium | Medium |
| Deftype interfaces | ~1200 | No | — | — |
| Platform optimizations | ~400 | No | — | — |
| Eve-internal protocols | ~200 | No (CLJS-only) | — | — |
| Transients | ~300 | No (different semantics) | — | — |
| **Total duplicated** | **~3740** | **~1040 (28%)** | | |
