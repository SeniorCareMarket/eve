# Eve JVM Parity Implementation Plan

> Generated: 2026-03-10
> Amended: 2026-03-10 (post-review — see Appendix A for amendment rationale)
> Amended: 2026-03-10 (cljc unification integration — see Appendix B)
> Amended: 2026-03-11 (array/obj cljc migration — see Phase 7 and Appendix C)
> Source: [jvm-cljs-gap-analysis.md](jvm-cljs-gap-analysis.md),
>         [cljc-unification-analysis.md](cljc-unification-analysis.md)

---

## Executive Summary

The JVM implementations of Eve's four core data structures (map, vector, set, list)
have significant interop and correctness gaps compared to their CLJS counterparts.
The most critical issues are: (1) mutating operations (`dissoc`, `conj`, `pop`,
`disjoin`) silently return standard Clojure types instead of Eve types, breaking
type identity and cross-process sharing; (2) set lookups are O(n) instead of
O(log n); (3) collections lack `IFn`, `IHashEq`, `IReduce`, and `print-method`,
making them non-idiomatic in Clojure code; and (4) `EveArray` and `Obj`/`ObjArray`
are CLJS-only (`array.cljs`, `obj.cljs`) with no JVM deftypes — JVM reads return
plain vectors/maps instead of typed instances. This plan organizes remediation into
8 phases across 30 tracked gaps, ordered by correctness impact first, then
ergonomics, then performance.

---

## Phases

### Phase 0 — Pre-requisite: Set Hash Function Migration

**Gap (NEW — missed in original plan):** The set's HAMT is built with
`clojure.core/hash` on both CLJS and JVM, but these produce **different values
per platform**. The map solved this with `portable-hash-bytes` (a shared Murmur3
over serialized value bytes, defined in `map.cljc:54-113`). The set has no such
portable hash. This means:

- JVM-built sets use JVM `hash` → JVM can navigate them.
- CLJS-built sets use CLJS `hash` → JVM **cannot** do hash-directed lookup.
- Cross-process sets (Node writes, JVM reads) are stuck at O(n) scan.

Without fixing this, Phase 1 (set O(log n) lookup) only works for JVM-built sets,
and Phase 2c (set native write ops) produces sets incompatible with CLJS readers.

**Work Items:**

1. **Extract `portable-hash-bytes` to shared namespace** *(cljc unification)*
   - Create `src/eve/hamt_util.cljc` (or add to `eve.deftype-proto.data`)
   - Move `portable-hash-bytes` and its helpers (`ubyte`, `imul32`, `rotl32`,
     `ushr32`) from `map.cljc:54-113` to the new shared namespace
   - Update `map.cljc` to import from the new namespace instead of defining inline
   - `set.cljc` imports from the same namespace
   - This eliminates a `set → map` cross-dependency and establishes a clean shared
     foundation for both HAMT implementations

2. **Move bitwise helpers to shared `.cljc` sections** *(cljc unification)*
   - In both `map.cljc` and `set.cljc`, move `mask-hash`, `bitpos`, `has-bit?`,
     `get-index` out of the `#?(:cljs ...)` and `#?(:clj ...)` blocks into the
     shared section — these are platform-identical pure integer arithmetic
   - `popcount32` stays in each file but uses a `#?` reader conditional for the
     one platform-specific line (CLJS: bit-manipulation + `js*`; JVM: `Integer/bitCount`)
   - This eliminates 4 duplicate definitions per file and prevents copy-paste
     divergence as new HAMT functions are added in later phases

3. **Migrate set HAMT to `portable-hash-bytes`**
   - CLJS set: change `(hash v)` → `(portable-hash-bytes (ser/serialize-key v))` in
     `hamt-find`, `hamt-conj`, `hamt-disj`, and all HAMT navigation
   - JVM set: change `(hash elem)` → `(portable-hash-bytes (value->eve-bytes elem))` in
     `jvm-write-set!` and future `jvm-set-hamt-get`

4. **Add `jvm-hashed?` flag to `EveHashSet`** (mirror map's approach)
   - JVM-built sets: `jvm-hashed? = true`
   - Sets read from CLJS-built slab data: `jvm-hashed? = false`
   - When `jvm-hashed?` is false, fall back to O(n) `jvm-set-reduce` for lookup
   - Store flag in set header byte 1 (currently padding)

5. **CLJS migration path**: Existing CLJS-built sets in slab data will use old
   `cljs.core/hash`. New sets will use `portable-hash-bytes`. Detect via header flag.

6. **Tests**
   - Cross-process: JVM writes set, Node reads; Node writes set, JVM reads
   - Verify hash-directed lookup works for JVM-built sets
   - Verify fallback works for legacy CLJS-built sets
   - Verify `map.cljc` still passes all tests after import change

**Files:** `src/eve/hamt_util.cljc` (new), `src/eve/map.cljc`, `src/eve/set.cljc`,
`test/eve/jvm_set_test.clj`

**Risk:** This is a **breaking change** for CLJS set HAMT layout. Existing slab
files with CLJS-built sets will have incompatible hash structure. Mitigate with
header flag for backward compatibility.

---

### Phase 1 — Correctness: Set O(log n) Lookup

**Gap:** `EveHashSet.contains` and `.get` use `jvm-set-reduce` to do a full tree
scan, serializing every element. O(n) per lookup.

**Depends on:** Phase 0 (portable hash migration).

**Work Items:**

1. **Add `jvm-set-hamt-get`** in `src/eve/set.cljc`
   - Port the hash-directed HAMT descent logic from `jvm-hamt-get` in `map.cljc`
   - Use `portable-hash-bytes` on serialized value bytes for trie navigation
   - **Key difference from map:** Set bitmap nodes have **no hash array** (unlike
     map which stores per-entry hashes). Navigation uses bitmap bits only; at leaf,
     compare serialized bytes directly.
   - **Key difference from map:** Set collision node type is **2** (map uses **3**).
   - At leaf: compare serialized bytes of the candidate to the stored value
   - Return the matched element or `nil`

2. **Wire into `EveHashSet`** JVM deftype
   - `.contains(key)` → if `jvm-hashed?`, call `jvm-set-hamt-get`; else `jvm-set-reduce` scan
   - `.get(key)` → same dispatch

3. **Tests** — `test/eve/jvm_set_test.clj`
   - O(log n) lookup for present and absent elements
   - Boundary: empty set, 1-element set, collision-node set
   - Verify `contains` and `get` return correct values
   - Cross-process: lookup in CLJS-built set (fallback path)

**Files:** `src/eve/set.cljc`, `test/eve/jvm_set_test.clj`

---

### Phase 2 — Correctness: Native Slab-Based Write Operations

**Gap:** On JVM, `dissoc` (map), `conj`/`assocN`/`pop` (vec), and `conj`/`disjoin`
(set) all materialize to standard Clojure types via `into`. This breaks type
identity, slab allocation, and cross-process sharing.

#### 2a. Map: Native `dissoc` (path-copy HAMT delete)

**Work Items:**

1. **Add `jvm-hamt-dissoc`** in `src/eve/map.cljc`
   - Walk trie by hash bits to find the target key
   - On removal from bitmap-indexed node: clear the bit, compact children
   - On removal from collision node: shrink or promote to BIN
   - Allocate new nodes in the slab via `-sio-alloc!`
   - Return the new root offset (or sentinel for empty)
   - **Must respect `jvm-hashed?` flag** — only path-copy when true, else fall back

2. **Wire `EveHashMap.without`** to use `jvm-hamt-dissoc` instead of `(dissoc (into {} this) k)`
   - Guard with `jvm-hashed?` check (same pattern as `assoc`)

3. **Fix `EveHashMap.assoc` collision-node fallback** — currently falls back to
   `(assoc (into {} this) k v)` at `map.cljc:2790-2791`. Implement native
   collision-node assoc in the slab. The existing `jvm-hamt-assoc!` already handles
   most cases but returns `nil` for collision nodes and hash exhaustion (shift >= 30).

4. **Tests** — `test/eve/jvm_map_test.clj`
   - `dissoc` returns `EveHashMap`, not `PersistentHashMap`
   - Round-trip: assoc N keys, dissoc some, verify remaining
   - Collision-node dissoc
   - `dissoc` on missing key returns same map
   - Empty map after dissoc-ing all keys

#### 2b. Vector: Native `conj` / `assocN` / `pop`

**Existing infrastructure:** `jvm-build-trie!` (line 1102), `jvm-vec-alloc-node!`
(line 1084), and `jvm-vec-alloc-value-block!` (line 1092) already exist for
bulk construction. These are bottom-up (build entire trie at once). The new ops
need incremental trie modification (top-down path-copy).

**Work Items:**

1. **Add `jvm-vec-conj`** in `src/eve/vec.cljc`
   - If tail has room (tail-len < NODE_SIZE), append value to tail, return new SabVecRoot
   - If tail full, push tail into tree as new leaf, allocate new single-element tail
   - May need to grow tree depth (new root) when tree is full at current shift
   - Allocate new nodes/leaves in slab via `-sio-alloc!`

2. **Add `jvm-vec-assoc-n`** — path-copy update at index `i`
   - Walk trie from root to leaf at index `i`, copy each node on the path
   - At leaf, write new serialized value

3. **Add `jvm-vec-pop`** — remove last element
   - If tail has >1 element, shrink tail
   - If tail has 1 element, pull rightmost leaf from tree as new tail
   - May need to shrink tree depth

4. **Wire into `SabVecRoot` JVM deftype** — replace the three `(into [] this)` fallbacks

5. **Tests** — `test/eve/jvm_vec_test.clj`
   - `conj` returns `SabVecRoot`
   - `assocN` returns `SabVecRoot`
   - `pop` returns `SabVecRoot`
   - Round-trip: build vector with conj, verify `nth` on all indices
   - Pop to empty
   - assocN boundary (last index, index = count for append)
   - Grow past NODE_SIZE (trigger tail push + tree growth)

#### 2c. Set: Native `conj` / `disjoin`

**Depends on:** Phase 0 (portable hash) + Phase 1 (hash-directed lookup).

**Existing infrastructure:** `jvm-set-hamt-build-entries!` (line 1431) builds
complete set HAMTs from `[kh vb]` pairs. The new ops need incremental path-copy.

**Work Items:**

1. **Add `jvm-set-hamt-conj`** in `src/eve/set.cljc`
   - Mirror `jvm-hamt-assoc!` from map but for value-only HAMT
   - **Key difference:** No hash array in bitmap nodes — simpler layout
   - Hash the element via `portable-hash-bytes`, walk/create trie path, store serialized value at leaf

2. **Add `jvm-set-hamt-disjoin`** — mirror `jvm-hamt-dissoc` from Phase 2a
   - Same HAMT delete algorithm but adapted for set node layout (no hash array)

3. **Wire into `EveHashSet` JVM deftype** — replace `cons` and `disjoin` fallbacks
   - Guard with `jvm-hashed?` check

4. **Tests** — `test/eve/jvm_set_test.clj`
   - `conj` returns `EveHashSet`
   - `disjoin` returns `EveHashSet`
   - Round-trip: add elements, remove some, verify membership
   - Disjoin on missing element returns same set

**Files:** `src/eve/map.cljc`, `src/eve/vec.cljc`, `src/eve/set.cljc`,
`test/eve/jvm_map_test.clj`, `test/eve/jvm_vec_test.clj`, `test/eve/jvm_set_test.clj`

---

### Phase 3 — Ergonomics: `IFn` on All JVM Types

**Gap:** CLJS types implement `IFn` so you can write `(my-map :key)`,
`(my-vec 0)`, `(my-set :val)`, `(my-list 0)`. None of the JVM types do.

**Implementation note (NEW):** `clojure.lang.IFn` defines `invoke` methods for
0-20+ args plus `applyTo`. Clojure's built-in collections extend `AFn` (abstract
class) which provides `ArityException` for unsupported arities. Since `deftype`
can only extend `Object`, we must implement all `invoke` arities explicitly —
supported ones delegate to lookup, unsupported ones throw
`clojure.lang.ArityException`. Alternatively, implement only the needed arities
and accept `AbstractMethodError` for wrong arity (less polished but functional).

**Work Items:**

1. **`EveHashMap`** — add `clojure.lang.IFn`
   - `invoke(Object key)` → `.valAt(key)`
   - `invoke(Object key, Object not-found)` → `.valAt(key, not-found)`
   - All other arities → throw `ArityException`

2. **`SabVecRoot`** — add `clojure.lang.IFn`
   - `invoke(Object idx)` → `.nth(idx)` (cast to int)
   - `invoke(Object idx, Object not-found)` → `.nth(idx, not-found)`

3. **`EveHashSet`** — add `clojure.lang.IFn`
   - `invoke(Object key)` → `.get(key)`
   - `invoke(Object key, Object not-found)` → return element or `not-found`

4. **`SabList`** — add `clojure.lang.IFn`
   - `invoke(Object idx)` → nth traversal
   - `invoke(Object idx, Object not-found)` → nth or not-found

5. **Tests** — for all four types
   - `(my-coll arg)` and `(my-coll arg default)` syntax
   - Wrong arity throws (not AbstractMethodError)

**Files:** `src/eve/map.cljc`, `src/eve/vec.cljc`, `src/eve/set.cljc`,
`src/eve/list.cljc`, corresponding test files

---

### Phase 4 — Correctness: `IHashEq` and `print-method`

**Gap:** No `IHashEq` means Eve collections used as keys/values in standard Clojure
hash maps/sets may have inconsistent hash behavior. No `print-method` means poor
REPL experience.

#### 4a. `IHashEq` on All JVM Types

**Implementation note (NEW):** Map already uses `APersistentMap/mapHash` for
`hashCode` and set uses `Murmur3/hashUnordered`. These produce correct values
but are not memoized and don't implement the `IHashEq` interface that Clojure's
hash dispatch checks first.

**Work Items:**

1. Add `clojure.lang.IHashEq` to all four JVM deftypes
   - `hasheq()` returns the Murmur3-based ordered/unordered collection hash
   - Memoize in a `^:volatile-mutable __hash` field (same pattern as CLJS)
   - Map: `Murmur3/hashUnordered` over entry seq (same as current `APersistentMap/mapHash`)
   - Vec: `Murmur3/hashOrdered` over element seq
   - Set: `Murmur3/hashUnordered` over element seq
   - List: `Murmur3/hashOrdered` over element seq
   - Also update `hashCode` to delegate to `hasheq` for consistency

2. **Tests** — for all four types
   - `(hash eve-coll)` equals `(hash (into <clj-equiv> eve-coll))`
   - Memoization: second call returns same value without recomputation

#### 4b. `print-method` on All JVM Types

**Work Items:**

1. Add `print-method` multimethods for all four types (in each `.cljc` file,
   inside `#?(:clj ...)` reader conditional, **after** the deftype definition)
   - `EveHashMap`: print as `{k1 v1, k2 v2}`
   - `SabVecRoot`: print as `[v1 v2 v3]`
   - `EveHashSet`: print as `#{v1 v2 v3}`
   - `SabList`: print as `(v1 v2 v3)`

2. **Tests** — `(pr-str eve-coll)` matches expected format

**Files:** `src/eve/map.cljc`, `src/eve/vec.cljc`, `src/eve/set.cljc`,
`src/eve/list.cljc`, corresponding test files

---

### Phase 5 — Performance: `IReduce` and Lazy Seq

**Gap:** `reduce` over JVM Eve collections goes through `seq`, which eagerly
materializes an `ArrayList`. CLJS versions walk the slab in-place.

#### 5a. `IReduce` on All JVM Types

**Implementation note (NEW):** Map already has `IKVReduce` via `jvm-hamt-kv-reduce`.
The new `IReduce` for map should build on this (wrap entries as `MapEntry`).

**Work Items:**

1. Add `clojure.lang.IReduce` to all four JVM deftypes
   - `reduce(IFn f)` — 1-arg reduce (no init value)
   - `reduce(IFn f, Object init)` — 2-arg reduce
   - Map: delegate to existing `jvm-hamt-kv-reduce`, wrapping each pair as `MapEntry`
   - Vec: iterate indices 0..count-1, apply `f` to each `nth`
   - Set: walk HAMT nodes via `jvm-set-reduce`, apply `f` to each element
   - List: walk linked list nodes via `jvm-list-seq`, apply `f` to each element
   - Support early termination via `Reduced`

2. **Tests** — for all four types
   - `reduce` produces correct result
   - Early termination with `reduced` works
   - 1-arg reduce on empty collection throws / returns identity correctly

#### 5b. Lazy Seq for Map/Vec/Set (Optional Improvement)

**Work Items:**

1. Replace `ArrayList` materialization in `seq` with lazy-seq for map, vec, set
   - Map: lazy HAMT walk (mirror CLJS `seq-from-hamt-walk`)
   - Vec: `(lazy-seq (cons (nth this i) (seq-from i+1)))` pattern
   - Set: lazy HAMT walk
   - List: already lazy (no change needed)

**Files:** `src/eve/map.cljc`, `src/eve/vec.cljc`, `src/eve/set.cljc`,
`src/eve/list.cljc`, corresponding test files

---

### Phase 6 — Completeness: Transients, List Gaps, and Vec `java.util.List`

#### 6a. Transients for JVM Map and Set

**Gap:** CLJS has `TransientEveHashMap` and `TransientEveHashSet`. JVM has none.

**Depends on:** Phase 2a (map native dissoc) and Phase 2c (set native write ops).

**Work Items:**

1. **`TransientEveHashMap`** JVM deftype
   - Implements `clojure.lang.ITransientMap`
   - Mutable root pointer; batch `assoc!` / `dissoc!` without intermediate persistent snapshots
   - `persistent!` freezes and returns new `EveHashMap`

2. **`TransientEveHashSet`** JVM deftype
   - Implements `clojure.lang.ITransientSet`
   - `conj!` / `disjoin!` / `persistent!`

3. **Wire `IEditableCollection`** on `EveHashMap` and `EveHashSet`
   - `.asTransient()` returns the corresponding transient

4. **Tests**
   - `(persistent! (reduce conj! (transient eve-map) entries))` round-trip
   - `(persistent! (reduce conj! (transient eve-set) elems))` round-trip

#### 6b. List Gaps

**Gap:** List is missing `ISeq`, `Indexed`, metadata, and `coll-factory` for nested
collection deserialization.

**Work Items:**

1. **Add `clojure.lang.ISeq`** to `SabList`
   - `first()` → delegates to existing `peek`
   - `next()` → if count > 1, return new SabList from next node; else nil
   - `more()` → if count > 1, return new SabList from next node; else empty list
   - Note: `ISeq` inherits `IPersistentCollection` which `SabList` already implements
     via `IPersistentList`. Ensure no method signature conflicts.

2. **Add `clojure.lang.Indexed`** to `SabList`
   - `nth(int)`, `nth(int, Object)` — O(n) traversal via `jvm-list-seq`

3. **Add metadata** — `IMeta` / `IObj` with `_meta` field
   - List currently has no `_meta` field; add it to the deftype fields

4. **Wire `coll-factory`** through `jvm-sab-list-from-offset` to support nested
   Eve collections as list values
   - Add `coll-factory` field to `SabList` deftype
   - Pass it through `jvm-list-node-read` → `eve-bytes->value`

5. **Tests**
   - `(first l)`, `(rest l)`, `(next l)` work correctly
   - `(nth l 2)` works
   - `(with-meta l {:a 1})` works
   - Nested Eve collections inside list values deserialize correctly

#### 6c. Vector `java.util.List` (NEW)

**Gap (missed in original plan):** Clojure's `PersistentVector` implements
`java.util.List`. Eve's `SabVecRoot` does not. This breaks Java interop when
passing Eve vectors to Java APIs expecting `List`.

**Work Items:**

1. Add `java.util.List` to `SabVecRoot` JVM deftype
   - Read methods: `size`, `get`, `indexOf`, `lastIndexOf`, `listIterator`,
     `subList`, `contains`, `containsAll`, `toArray`
   - Write stubs: throw `UnsupportedOperationException`

2. **Tests**
   - `(instance? java.util.List eve-vec)` is true
   - `.get(i)` works
   - `.size()` equals `count`

**Files:** `src/eve/map.cljc`, `src/eve/set.cljc`, `src/eve/list.cljc`,
`src/eve/vec.cljc`, corresponding test files

---

### Phase 7 — Array and Obj: CLJS → CLJC Migration + JVM Deftypes

**Gap:** `eve.array` (696 lines) and `eve.obj` (696 lines) are pure `.cljs` files.
The JVM can serialize/deserialize their slab blocks (type-id `0x1D` and `0x1E`)
via `jvm-write-eve-array!`, `jvm-read-eve-array`, `jvm-write-obj!`, and
`jvm-read-obj` in `alloc.cljc`, but these return plain Clojure vectors and maps —
not typed instances. There are no `EveArray` or `Obj`/`ObjArray` deftypes on JVM.

#### Current State

| Aspect | CLJS (`array.cljs`) | JVM (`alloc.cljc`) |
|--------|---------------------|---------------------|
| Deftype | `EveArray` with 11 fields | None — returns `vector` |
| Read | Live view over SAB (atomic/non-atomic) | `jvm-read-eve-array` → `mapv` to vector |
| Write | Allocates SAB region, writes header | `jvm-write-eve-array!` → slab block |
| Protocols | ICounted, IIndexed, ILookup, IFn, ISeqable, IReduce, IHash, IEquiv, IPrintWithWriter, ISabStorable, IsEve | None |
| Atomics | cas!, exchange!, add!, sub!, band!, bor!, bxor! | N/A (no shared memory) |
| Wait/Notify | wait!, wait-async, notify! (int32 only) | N/A |
| SIMD | afill-simd!, acopy-simd!, asum-simd, amin-simd, amax-simd, aequal-simd? | N/A |
| Slab integration | SAB pointer (0x1D), header constructor, disposer | Slab read/write only |

| Aspect | CLJS (`obj.cljs`) | JVM (`alloc.cljc`) |
|--------|-------------------|---------------------|
| Deftypes | `Obj`, `ObjArray`, `ObjArrayRow` | None — returns `{:schema ... :values ...}` |
| Read | Live field access via DataView + Atomics | `jvm-read-obj` → map of field values |
| Write | Allocates SAB region per object | `jvm-write-obj!` → slab block |
| Schema | `create-schema`, `compute-layout` (alignment-aware) | `jvm-obj-layout`, `jvm-encode/decode-obj-schema` |
| Protocols | ICounted, ILookup, IFn, ISeqable, IHash, IEquiv, IPrintWithWriter (on Obj) | None |
| Atomics | cas!, add!, sub!, exchange! on int32/uint32 fields | N/A |
| ObjArray | SoA columns backed by `EveArray`; column-reduce, column-map! | N/A |

#### What CAN Be Shared (→ `.cljc` shared section)

1. **Constants** — `HEADER_SIZE`, subtype codes, type-size/alignment maps
   - `array.cljs:34` — `HEADER_SIZE = 8`
   - `array.cljs:40-47` — `subtype->elem-shift` (pure arithmetic)
   - `array.cljs:49-52` — `subtype->atomic?` (pure logic)
   - `array.cljs:54-68` — `type-kw->subtype` (lookup table, minus `js/Error`)
   - `array.cljs:70-82` — `subtype->type-kw` (lookup table)
   - `obj.cljs:29-53` — `type-sizes`, `type-alignments`, `align-offset`
   - `obj.cljs:63-83` — `compute-layout` (pure data transform)
   - `obj.cljs:85-95` — `create-schema` (pure data transform)
   - `obj.cljs:612-619` — `obj-type-kw->code`, `obj-code->type-kw` (lookup tables)
   - **Overlap with `alloc.cljc`:** `obj-code->type-kw`, `obj-type-kw->code`,
     `obj-type-sizes`, `obj-type-alignments`, `jvm-obj-layout` (lines 624-656)
     are **duplicated** between `obj.cljs` and `alloc.cljc`. These must be
     consolidated into the shared section.

2. **Schema encode/decode** — `encode-obj-schema` / `decode-obj-schema`
   - Nearly identical logic in `obj.cljs:621-656` and `alloc.cljc:658-686`
   - CLJS uses `TextEncoder`/`TextDecoder`; JVM uses `String.getBytes("UTF-8")`
   - Can be unified with `#?` reader conditionals for the encoding calls only

#### What CANNOT Be Shared (→ platform-specific `#?` blocks)

1. **Deftypes** — `EveArray`, `Obj`, `ObjArray`, `ObjArrayRow` implement platform-
   specific protocols (CLJS: `ICounted`/`-count`, JVM: `Counted`/`count`, etc.)
2. **Memory access** — CLJS uses SAB + DataView + Atomics; JVM uses ISlabIO or
   `ByteBuffer`. Fundamentally different memory models.
3. **Atomics** — CLJS: `js/Atomics.load/store/compareExchange/add/sub/and/or/xor`;
   JVM: `java.util.concurrent.atomic` or `VarHandle` (Java 9+). Different APIs,
   and JVM doesn't have SharedArrayBuffer.
4. **Wait/Notify** — CLJS: `js/Atomics.wait/waitAsync/notify`; JVM: `Object.wait/notify`
   or `LockSupport`. Completely different threading models.
5. **SIMD** — CLJS-only via WASM. No JVM equivalent in this codebase (JVM has
   `jdk.incubator.vector` but that's a different API entirely).
6. **SAB allocation** — `alloc-eve-region`, `alloc-obj-region` use `atom/*global-atom-instance*`
   and SAB-specific allocation. JVM uses slab files with `ISlabIO`.
7. **Slab registration** — `register-cljs-to-sab-builder!`, `register-header-constructor!`,
   `register-sab-type-constructor!` are CLJS-specific registration hooks.

#### 7a. File Restructuring

**Work Items:**

1. **Rename `src/eve/array.cljs` → `src/eve/array.cljc`**
   - Move shared constants and helper functions to the top (outside `#?`)
   - Wrap existing CLJS code in `#?(:cljs (do ...))`
   - Add `#?(:clj (do ...))` block for JVM deftype and functions
   - Update `ns` require: replace `(:require [eve.shared-atom :as atom] [eve.wasm-mem :as wasm] ...)`
     with `#?` conditionals — `eve.shared-atom` and `eve.wasm-mem` are CLJS-only

2. **Rename `src/eve/obj.cljs` → `src/eve/obj.cljc`**
   - Same restructuring as array
   - Consolidate schema system: `compute-layout`, `create-schema`, type maps into
     shared section (these are pure Clojure, no platform deps)
   - Move duplicated code from `alloc.cljc` lines 624-656 (`obj-code->type-kw`,
     `obj-type-kw->code`, `obj-type-sizes`, `obj-type-alignments`, `jvm-obj-layout`)
     into `obj.cljc` shared section, and import from there in `alloc.cljc`
   - Schema encode/decode: unify `encode-obj-schema`/`decode-obj-schema` with `#?`
     for the `TextEncoder`/`String.getBytes` calls

3. **Update `alloc.cljc`** to import shared schema/layout functions from `obj.cljc`
   instead of defining them locally

4. **Update `shadow-cljs.edn` and `deps.edn`** if the file renames require build
   config changes (unlikely for `.cljs` → `.cljc` but verify)

#### 7b. JVM `EveArray` Deftype

**Work Items:**

1. **Define `EveArray` JVM deftype** in `src/eve/array.cljc` `#?(:clj ...)` block
   - Fields: `sio` (ISlabIO), `slab-off` (slab-qualified offset), `subtype-code`,
     `length`, `elem-shift`, `_meta`, `__hash`
   - Reads go through ISlabIO: `-sio-read-u8`, `-sio-read-i32`, `-sio-read-bytes`
   - No live mutable view (unlike CLJS SAB) — each read is a slab IO call

2. **Implement Clojure interfaces on JVM `EveArray`:**
   - `clojure.lang.Counted` — `count` returns `length`
   - `clojure.lang.Indexed` — `nth(i)` reads element at data offset + i*elem_size
   - `clojure.lang.ILookup` — `valAt(idx)` delegates to `nth`
   - `clojure.lang.IFn` — `invoke(idx)`, `invoke(idx, not-found)`
   - `clojure.lang.Seqable` — lazy seq over indices
   - `clojure.lang.IReduce` — direct iteration without seq allocation
   - `clojure.lang.IHashEq` — same hash algorithm as CLJS (subtype-seeded, element-folded)
   - `clojure.lang.IPersistentCollection` — `count`, `cons` (→ throw UnsupportedOp),
     `empty`, `equiv`
   - `clojure.lang.IMeta` / `clojure.lang.IObj` — metadata support
   - `java.lang.Iterable` — element iterator
   - `print-method` multimethod — `#eve/array :int32 [1 2 3]`

3. **Wire `jvm-read-eve-array`** to return `EveArray` deftype instead of vector
   - Existing code in `alloc.cljc:589-616` reads and materializes to vector
   - New code returns a live `EveArray` backed by ISlabIO (lazy, reads on demand)
   - Keep the materializing reader as `jvm-read-eve-array-vec` for backward compat

4. **Constructor** — `eve-array` function on JVM
   - `(eve-array :int32 10)` → allocate slab block via ISlabIO, write header, return `EveArray`
   - `(eve-array :int32 [1 2 3])` → allocate, write header + elements, return `EveArray`
   - Uses `jvm-write-eve-array!` internally

5. **Element mutation on JVM** (subset of CLJS API)
   - `aget` / `aset!` — basic read/write via ISlabIO
   - Atomic ops: **not supported** on JVM slab files (mmap doesn't guarantee atomicity
     across processes the way SAB does). Document this limitation.
   - Wait/notify: **not supported** on JVM.
   - SIMD: **not supported** on JVM.
   - Functional ops (`areduce`, `amap`, `afill!`, `acopy!`): implement via ISlabIO reads

6. **Tests** — `test/eve/jvm_array_test.clj` (expand existing 7 tests)
   - `jvm-read-eve-array` returns `EveArray` (not vector)
   - `(count arr)`, `(nth arr 0)`, `(arr 0)` work
   - `(seq arr)` returns lazy seq
   - `(reduce + arr)` works
   - `(pr-str arr)` produces expected format
   - `(= arr1 arr2)` and `(hash arr1)` work
   - Round-trip: write from JVM, read back as `EveArray`
   - Cross-process: CLJS writes EveArray, JVM reads as `EveArray` (not vector)
   - All 9 subtypes (int8 through float64) work

#### 7c. JVM `Obj` and `ObjArray` Deftypes

**Work Items:**

1. **Define `Obj` JVM deftype** in `src/eve/obj.cljc` `#?(:clj ...)` block
   - Fields: `schema`, `sio` (ISlabIO), `slab-off`, `data-offset`, `_meta`, `__hash`
   - Schema is the shared `create-schema` result (same structure as CLJS)
   - Field reads: compute field byte offset from schema layout, read via ISlabIO
   - Field writes: `assoc!` writes via ISlabIO

2. **Implement Clojure interfaces on JVM `Obj`:**
   - `clojure.lang.ILookup` — `valAt(field-key)` reads field from slab
   - `clojure.lang.IFn` — `invoke(field-key)`, `invoke(field-key, not-found)`
   - `clojure.lang.Counted` — count of schema fields
   - `clojure.lang.Seqable` — seq of `MapEntry`s (field-key → value)
   - `clojure.lang.IHashEq` — same hash algorithm as CLJS
   - `clojure.lang.IPersistentCollection` — `count`, `equiv`
   - `clojure.lang.Associative` — `assoc` (creates new Obj with one field changed)
   - `clojure.lang.IMeta` / `clojure.lang.IObj` — metadata
   - `java.lang.Iterable` — entry iterator
   - `print-method` — `#eve/obj {:key 42, :left -1}`

3. **Define `ObjArray` JVM deftype** in `src/eve/obj.cljc` `#?(:clj ...)` block
   - Fields: `schema`, `length`, `columns` (map of field-key → `EveArray`)
   - Depends on JVM `EveArray` from Phase 7b
   - Each column is a JVM `EveArray` of type `:int32` (matching CLJS limitation —
     all columns currently Int32Array)

4. **Implement Clojure interfaces on JVM `ObjArray`:**
   - `clojure.lang.Counted` — `count` returns `length`
   - `clojure.lang.Indexed` — `nth(idx)` returns `ObjArrayRow` view
   - `clojure.lang.IFn` — `invoke(idx)`
   - `clojure.lang.Seqable` — lazy seq of `ObjArrayRow`s
   - `print-method` — `#eve/obj-array [schema={...} length=N]`

5. **Define `ObjArrayRow` JVM deftype** — read-only view into ObjArray at index
   - `clojure.lang.ILookup` — reads from parent ObjArray column
   - `clojure.lang.IFn` — `invoke(field-key)`
   - `clojure.lang.Seqable` — seq of entries
   - `print-method` — `#eve/obj-array-row {:key 42, :val 7}`

6. **Wire `jvm-read-obj`** to return `Obj` deftype instead of plain map
   - Keep `jvm-read-obj-map` for backward compat

7. **Constructor** — `obj` function on JVM
   - `(obj {:key :int32 :val :int32} {:key 42 :val 7})` → allocate slab, write schema
     + field data, return `Obj`
   - Uses `jvm-write-obj!` internally

8. **Constructor** — `obj-array` function on JVM
   - `(obj-array 1000 {:key :int32 :val :int32})` → creates N `EveArray` columns
   - Returns `ObjArray`

9. **Mutation on JVM** (subset of CLJS API)
   - `get` / `assoc!` — basic field read/write via ISlabIO
   - Atomic ops: not supported (same mmap limitation as array)
   - `get-in` / `assoc-in!` for ObjArray

10. **Tests** — `test/eve/jvm_obj_test.clj` (expand existing 7 tests)
    - `jvm-read-obj` returns `Obj` (not map)
    - `(:key obj)` works (ILookup + IFn)
    - `(seq obj)` returns MapEntry seq
    - `(pr-str obj)` produces expected format
    - `ObjArray` round-trip: create, write fields, read back
    - `ObjArrayRow` provides correct field access
    - All 10 field types work correctly
    - Cross-process: CLJS writes Obj, JVM reads as `Obj`
    - Schema round-trip: encode → decode → same layout

#### 7d. Consolidate Duplicated Code

**Work Items:**

1. **Schema system consolidation**
   - Move `obj-code->type-kw`, `obj-type-kw->code`, `obj-type-sizes`,
     `obj-type-alignments`, `align-offset`, `compute-layout`, `create-schema`
     into the shared section of `obj.cljc`
   - Remove duplicates from `alloc.cljc` lines 624-656 (`obj-code->type-kw`,
     `obj-type-kw->code`, `obj-type-sizes`, `obj-type-alignments`, `jvm-obj-layout`,
     `jvm-align`)
   - `alloc.cljc` imports the layout function from `obj.cljc`

2. **Schema encode/decode unification**
   - Merge `encode-obj-schema` (CLJS `obj.cljs:621-639`) and `jvm-encode-obj-schema`
     (`alloc.cljc:658-675`) into one function with `#?` for string encoding
   - Merge `decode-obj-schema` (CLJS `obj.cljs:642-656`) and `jvm-decode-obj-schema`
     (`alloc.cljc:677-686`) similarly

3. **Array subtype helpers consolidation**
   - `subtype->elem-shift`, `subtype->atomic?`, `type-kw->subtype`, `subtype->type-kw`
     move to shared section — they're pure lookup tables with no platform deps
   - `jvm-subtype-elem-size` in `alloc.cljc` is equivalent to `subtype->elem-shift`
     (just returns bytes instead of shift) — consolidate or derive one from the other

**Files:** `src/eve/array.cljc` (renamed), `src/eve/obj.cljc` (renamed),
`src/eve/deftype_proto/alloc.cljc`, `test/eve/jvm_array_test.clj`,
`test/eve/jvm_obj_test.clj`

---

## Complete Gap → Phase Mapping

| # | Gap | Phase |
|---|---|---|
| 1 | No transients on JVM map/set | 6a |
| 2 | No `IFn` on any JVM type | 3 |
| 3 | No `IHashEq` on any JVM type | 4a |
| 4 | No `print-method` on any JVM type | 4b |
| 5 | No `IReduce` on any JVM type | 5a |
| 6 | Map `dissoc` materializes to Clojure type | 2a |
| 7 | Vec `conj`/`assocN`/`pop` materialize to Clojure type | 2b |
| 8 | Set `conj`/`disjoin` materialize to Clojure type | 2c |
| 9 | Set lookup is O(n) | 1 |
| 10 | No `ILookup`/metadata on list | 6b |
| 11 | Map `assoc` collision-node fallback materializes | 2a |
| 12 | Eager seq materialization (map/vec/set) | 5b |
| 13 | List missing `ISeq`, `Indexed` | 6b |
| 14 | List `coll-factory` not wired through | 6b |
| 15 | Map hash not memoized | 4a |
| 16 | Set hash not memoized | 4a |
| 17 | Vec hash not memoized | 4a |
| 18 | **Set uses platform-specific hash (not portable)** | **0** |
| 19 | **Vec missing `java.util.List`** | **6c** |
| 20 | **`IFn` arity handling (all arities must throw properly)** | **3** |
| 21 | **Bitwise helpers duplicated across CLJS/JVM blocks** | **0** |
| 22 | **`portable-hash-bytes` trapped in `map.cljc`, not shared** | **0** |
| 23 | **`EveArray` is CLJS-only — no JVM deftype** | **7b** |
| 24 | **`jvm-read-eve-array` returns vector, not `EveArray`** | **7b** |
| 25 | **`Obj` is CLJS-only — no JVM deftype** | **7c** |
| 26 | **`ObjArray`/`ObjArrayRow` are CLJS-only — no JVM deftypes** | **7c** |
| 27 | **`jvm-read-obj` returns map, not `Obj`** | **7c** |
| 28 | **Schema system duplicated between `obj.cljs` and `alloc.cljc`** | **7d** |
| 29 | **Array subtype helpers duplicated between `array.cljs` and `alloc.cljc`** | **7d** |
| 30 | **Array/obj `.cljs` files not `.cljc` — can't compile on JVM** | **7a** |

---

## Testing Strategy

Each phase adds tests to the corresponding `test/eve/jvm_*_test.clj` files.
All tests run via `clojure -M:jvm-test`. The existing CLJS test suites
(per CLAUDE.md green baseline) must continue to pass after every phase.

**Test categories per phase:**

| Phase | Test Focus |
|---|---|
| 0 | Cross-process set hash compatibility; CLJS migration path |
| 1 | Set contains/get correctness and performance; fallback for legacy sets |
| 2a | Map dissoc returns Eve type, round-trip, collision nodes |
| 2b | Vec conj/assocN/pop return Eve type, round-trip, tree growth |
| 2c | Set conj/disjoin return Eve type, round-trip |
| 3 | `(coll arg)` and `(coll arg default)` for all types; arity errors |
| 4a | `(hash eve-coll) == (hash clj-equiv)` for all types |
| 4b | `(pr-str eve-coll)` format for all types |
| 5a | `reduce` correctness + early termination for all types |
| 5b | Lazy seq memory (optional) |
| 6a | Transient round-trips for map and set |
| 6b | List first/rest/next/nth/meta/nested-coll |
| 6c | Vec java.util.List interop |
| 7a | File renames compile on both platforms; CLJS tests still pass |
| 7b | JVM EveArray: read/write, nth, seq, reduce, hash, equiv, print, cross-process |
| 7c | JVM Obj/ObjArray: read/write, field access, schema, print, cross-process |
| 7d | Schema/subtype dedup: alloc.cljc imports from obj.cljc/array.cljc |

---

## Risk Assessment

| Risk | Mitigation |
|---|---|
| **Set portable-hash migration is breaking** | Header flag differentiates old/new sets; fallback scan for legacy |
| Slab allocator changes break CLJS | All slab code is shared `.cljc`; run full CLJS test suite after each phase |
| JVM HAMT write ops introduce corruption | Extensive round-trip tests; compare to CLJS behavior |
| Collision-node handling is complex | Port directly from existing CLJS collision-node code |
| Transient implementation is large | Defer to Phase 6; mirror CLJS transient deftypes closely |
| `IHashEq` hash values differ from Clojure stdlib | Verify `(hash eve-coll) == (hash (into <equiv> eve-coll))` in tests |
| `IFn` arity explosion | Implement full arity set with throws; or accept AbstractMethodError |
| **Epoch GC / slab retirement for new write ops** | See Appendix A, item 6 |
| `.cljs` → `.cljc` rename breaks CLJS build | Verify shadow-cljs resolves `.cljc`; run full test suite |
| JVM EveArray lacks atomics/SIMD | Document limitation clearly; mmap doesn't support SAB atomics |
| `alloc.cljc` circular dependency with `obj.cljc` | `alloc.cljc` imports schema helpers from `obj.cljc`; `obj.cljc` imports alloc fns from `alloc.cljc` — may need a third namespace or careful ordering |

---

## Dependencies

```
Phase 0 items 1-2 (shared ns + bitwise) ── first ──→ Phase 0 items 3-5 (set hash migration)
Phase 0 (Set hash migration) ────────────── blocks ──→ Phase 1 (Set lookup)
Phase 1 (Set lookup) ────────────────────── blocks ──→ Phase 2c (Set write ops)
Phase 2a (Map dissoc) ───────────────────── blocks ──→ Phase 6a (Map transients)
Phase 2c (Set write) ────────────────────── blocks ──→ Phase 6a (Set transients)
Phases 0-2 ──────────────────────────────── blocks ──→ Phase 3 (IFn needs correct lookups)
Phases 0-3 are independent of ───────────────────────→ Phases 4-5 (can parallelize)
Phase 6b (List gaps) is fully independent
Phase 6c (Vec java.util.List) is fully independent
Phase 7a (File renames) ──────────────────── blocks ──→ Phase 7b-7d
Phase 7b (JVM EveArray) ─────────────────── blocks ──→ Phase 7c (ObjArray uses EveArray columns)
Phase 7d (Dedup) depends on 7a-7c completing
Phase 7 is fully independent of Phases 0-6
```

---

## Appendix A: Amendment Log

Gaps discovered during post-plan code review, with line-level evidence.

### 1. Set Hash Function Mismatch (NEW Phase 0) — CRITICAL

**Evidence:**
- `set.cljc:1513`: `jvm-write-set!` uses `(hash elem)` — JVM's `clojure.core/hash`
- `set.cljc:1041,1049,1067`: CLJS set uses `(hash v)` — CLJS's `cljs.core/hash`
- `map.cljc:2536,2843`: Map uses `(portable-hash-bytes kb)` — shared Murmur3
- `map.cljc:54-113`: `portable-hash-bytes` is defined once in shared `.cljc` section
- `set.cljc` has **zero** references to `portable-hash-bytes`
- `map.cljc:2889`: Map has `jvm-hashed?` guard; set has no equivalent

**Impact:** Phase 1 as originally written would produce a `jvm-set-hamt-get` that
uses JVM `hash` for navigation but encounters CLJS-built HAMT nodes organized by
CLJS `hash`. Lookups would silently return wrong results or miss elements.

### 2. Set Collision Node Type Differs from Map

**Evidence:**
- `set.cljc:36`: `NODE_TYPE_COLLISION = 2`
- `map.cljc:37`: `NODE_TYPE_COLLISION = 3`

**Impact:** Phase 1 originally said "port from `jvm-hamt-get`" without noting
this constant difference. Direct copy-paste would check for node type 3 but
encounter node type 2, causing incorrect traversal.

### 3. Set Bitmap Nodes Have No Hash Array

**Evidence:**
- `set.cljc:1479-1496`: Set bitmap node layout is `header + child pointers + inline values`
- `map.cljc:2554-2556`: Map's `jvm-hamt-get` reads per-entry hashes via `hashes-start-off`
- Set has `children-start-off` (line 1373) but no `hashes-start-off`

**Impact:** Phase 1 must account for this structural difference. The set's
hash-directed lookup can still work (navigate by bitmap bits, compare bytes at
leaf) but the intermediate hash-check optimization in the map is not available.

### 4. `IFn` Arity Handling

**Evidence:** `clojure.lang.IFn` defines `invoke()` through `invoke(Object...20 args)`
plus `applyTo(ISeq)`. Clojure's built-in collections extend `AFn` which provides
default `ArityException` throws. `deftype` extends `Object`, not `AFn`.

**Impact:** Implementing only 1- and 2-arity `invoke` would cause
`AbstractMethodError` on wrong arity instead of the expected `ArityException`.

### 5. Vec Missing `java.util.List` (NEW Phase 6c)

**Evidence:**
- `vec.cljc:1256`: `SabVecRoot` implements `java.util.RandomAccess` but NOT `java.util.List`
- Clojure's `PersistentVector` implements both

**Impact:** Java interop breakage when passing Eve vectors to APIs expecting `List`.

### 6. Epoch GC / Slab Retirement for New Write Ops (Open Question)

**Evidence:**
- CLJS types implement `d/ISabRetirable` for memory cleanup on atom swap
- JVM types do not implement this protocol
- When native write ops create new slab nodes (Phase 2), old nodes are orphaned

**Impact:** New write ops will allocate slab blocks for new nodes but never free
old ones. For single-swap operations this is manageable (same as current
`jvm-hamt-assoc!` behavior). For long-lived processes with many mutations, this
could cause slab exhaustion. The existing epoch GC mechanism handles this at the
atom level (whole-root retirement), so individual node retirement may not be
needed — but this should be validated.

**Recommendation:** Defer to post-Phase 2 investigation. Validate that atom-level
epoch GC is sufficient for the new write ops.

### 7. Vec `empty` Requires Slab Context

**Evidence:**
- `vec.cljc:1228-1230`: `(empty [_] (let [hdr-off (jvm-write-vec! sio ...)] ...))`
- Requires `*jvm-slab-ctx*` to be bound

**Impact:** Calling `(empty eve-vec)` outside a slab context (e.g., in generic
Clojure code that doesn't know about Eve) will fail. Same issue exists for map
and set `empty`. This is an inherent constraint of the slab architecture, not a
bug per se, but should be documented.

### 8. Existing Infrastructure to Leverage

The original plan didn't call out existing JVM code that can be reused:

- **Map:** `jvm-hamt-assoc!` (line 2744) — full path-copy HAMT assoc already works
  for bitmap nodes. Only collision nodes need new code.
- **Map:** `jvm-write-bitmap-node!` (line 2713), `jvm-read-node-kvs` (2698),
  `jvm-read-children` (2737), `jvm-read-hashes` (2740) — reusable for dissoc.
- **Vec:** `jvm-build-trie!` (1102), `jvm-vec-alloc-node!` (1084),
  `jvm-vec-alloc-value-block!` (1092) — bulk construction exists; incremental
  ops need new code but can reuse the allocation helpers.
- **Set:** `jvm-set-hamt-build-entries!` (1431) — bulk construction exists.
  Bitwise helpers (`popcount32`, `mask-hash`, `bitpos`, `has-bit?`, `get-index`,
  `children-start-off`) already defined at lines 1365-1374.
- **Set:** `jvm-set-reduce` (1376) — full tree walk exists; can be reused for
  `IReduce` and as fallback for non-portable-hashed sets.

---

## Appendix B: CLJC Unification Integration

Changes to incorporate recommendations from [cljc-unification-analysis.md](cljc-unification-analysis.md).

### What changed in this plan

Two work items were added to Phase 0 (items 1 and 2), and two gaps were added
to the tracking table (items 21 and 22). No other phases changed.

| Unification Recommendation | Plan Impact | Where |
|---|---|---|
| Extract `portable-hash-bytes` to shared ns | Added as Phase 0, item 1 | Replaces inline import from `map.cljc` |
| Move bitwise helpers to shared sections | Added as Phase 0, item 2 | ~30 min; no logic changes |
| Shared header read/write helpers | No change | Blocked on CLJS ISlabIO migration |
| Shared HAMT traversal via ISlabIO | No change | Deferred to post-parity refactoring pass |
| Shared equiv/hash logic | No change | Method bodies too short to justify |
| Unified deftype | No change | Not recommended — interfaces differ |
| Macro-based HAMT | No change | Not recommended — too complex |

### Why most recommendations don't affect the plan

The unification analysis is sequenced **after** the parity plan:

1. **Quick wins (items 1-2 above)** slot naturally into Phase 0, which already
   touches the same files. No schedule impact.
2. **Medium-term unification** (shared HAMT traversal, shared equiv/hash) is
   explicitly recommended for after JVM parity is achieved. The new JVM code
   written in Phases 1-2 already uses ISlabIO exclusively, making it ready for
   future unification without extra work now.
3. **Not-recommended items** (unified deftype, macro HAMT) require no action.

### Future unification pass (post-parity)

After all parity phases are complete, a separate refactoring pass should:

1. Benchmark CLJS ISlabIO overhead vs. direct DataView access
2. If < 5% regression, migrate CLJS HAMT reads to ISlabIO
3. Share `hamt-get`, `hamt-reduce`, `hamt-build-entries` across platforms
   (~500 lines map, ~300 lines set)
4. Consider shared `equiv`/`hash` helpers if implementations have grown complex

This is tracked in the unification analysis, not in this plan.

---

## Appendix C: Array/Obj CLJC Migration Analysis

### Motivation

`eve.array` and `eve.obj` are pure CLJS files (`.cljs`) that cannot be compiled
on the JVM. The JVM can read/write their slab block formats (type-ids `0x1D` and
`0x1E`) via helper functions in `alloc.cljc`, but returns plain Clojure vectors
and maps instead of typed instances. This creates a parity gap:

- CLJS: `(nth my-array 0)` → atomic read from SharedArrayBuffer
- JVM: `(nth (jvm-read-eve-array sio off) 0)` → materialized Clojure vector lookup

The migration to `.cljc` enables: (a) JVM deftypes with the same API surface as
CLJS (minus atomics/SIMD), (b) lazy reads from slab instead of eager materialization,
(c) cross-process type identity (JVM code sees `EveArray` not `PersistentVector`),
and (d) elimination of ~100 lines of duplicated code between the `.cljs` files and
`alloc.cljc`.

### Code Duplication Inventory

| Code | `array.cljs` / `obj.cljs` | `alloc.cljc` (JVM) | Shareable? |
|------|--------------------------|---------------------|------------|
| Subtype codes (0x01-0x09) | `type-kw->subtype` (line 54) | Implicit in case branches | Yes |
| Elem shift / size | `subtype->elem-shift` (line 40) | `jvm-subtype-elem-size` (line 529) | Yes (derive) |
| Obj type codes | `obj-type-kw->code` (line 612) | `obj-type-kw->code` (line 628) | Yes (identical) |
| Obj type sizes | `type-sizes` (line 29) | `obj-type-sizes` (line 631) | Yes (identical) |
| Obj type alignments | `type-alignments` (line 42) | `obj-type-alignments` (line 635) | Yes (identical) |
| Align helper | `align-offset` (line 55) | `jvm-align` (line 639) | Yes (identical) |
| Layout computation | `compute-layout` (line 63) | `jvm-obj-layout` (line 643) | Yes (identical) |
| Schema creation | `create-schema` (line 85) | N/A (inline) | Yes |
| Schema encode | `encode-obj-schema` (line 621) | `jvm-encode-obj-schema` (line 658) | Yes (with `#?`) |
| Schema decode | `decode-obj-schema` (line 642) | `jvm-decode-obj-schema` (line 677) | Yes (with `#?`) |

**Total dedup potential:** ~120 lines from schema/layout consolidation, ~20 lines
from subtype helper consolidation.

### JVM Limitations vs. CLJS

The following CLJS features have **no JVM equivalent** and will be CLJS-only:

| Feature | Reason |
|---------|--------|
| SharedArrayBuffer backing | JVM uses slab files (mmap), not SAB |
| `js/Atomics.*` operations | No equivalent for mmap files; `VarHandle` works for heap only |
| `wait!` / `wait-async` / `notify!` | SAB-specific threading primitives |
| WASM SIMD ops | CLJS-only WASM module |
| `ISabStorable` / `ISabRetirable` | CLJS SAB lifecycle protocols |
| `register-sab-type-constructor!` | CLJS deserialization hook |
| `register-cljs-to-sab-builder!` | CLJS serialization hook |
| `make-typed-view` (JS TypedArray) | JS-specific memory view |
| `from-typed-array` | JS TypedArray conversion |

The JVM `EveArray` will support: `aget`, `aset!`, `areduce`, `amap`, `afill!`,
`acopy!`, and all Clojure collection protocols. Mutation goes through ISlabIO.

### Implementation Order

1. **Phase 7a first** — file renames must happen before any JVM code is added.
   Run full CLJS test suite after renames to ensure no breakage.
2. **Phase 7b before 7c** — `ObjArray` columns are `EveArray` instances, so the
   JVM `EveArray` deftype must exist first.
3. **Phase 7d last** — deduplication is a refactoring pass that depends on 7a-7c
   being stable.

Phase 7 is fully independent of Phases 0-6 and can be worked on in parallel.
