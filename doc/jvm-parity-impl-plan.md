# Eve JVM Parity Implementation Plan

> Generated: 2026-03-10
> Source: [jvm-cljs-gap-analysis.md](jvm-cljs-gap-analysis.md)

---

## Executive Summary

The JVM implementations of Eve's four core data structures (map, vector, set, list)
have significant interop and correctness gaps compared to their CLJS counterparts.
The most critical issues are: (1) mutating operations (`dissoc`, `conj`, `pop`,
`disjoin`) silently return standard Clojure types instead of Eve types, breaking
type identity and cross-process sharing; (2) set lookups are O(n) instead of
O(log n); and (3) collections lack `IFn`, `IHashEq`, `IReduce`, and `print-method`,
making them non-idiomatic in Clojure code. This plan organizes remediation into
6 phases across 10 work items, ordered by correctness impact first, then
ergonomics, then performance.

---

## Phases

### Phase 1 — Correctness: Set O(log n) Lookup

**Gap:** `EveHashSet.contains` and `.get` use `jvm-set-reduce` to do a full tree
scan, serializing every element. O(n) per lookup.

**Work Items:**

1. **Add `jvm-set-hamt-get`** in `src/eve/set.cljc`
   - Port the hash-directed HAMT descent logic from `jvm-hamt-get` in `map.cljc`
   - Compute `(clojure.core/hash elem)`, then walk the trie by 5-bit hash chunks
   - At leaf: compare serialized bytes of the candidate to the stored value
   - Return the matched element or `nil`

2. **Wire into `EveHashSet`** JVM deftype
   - `.contains(key)` → call `jvm-set-hamt-get`, return boolean
   - `.get(key)` → call `jvm-set-hamt-get`, return element or nil

3. **Tests** — `test/eve/jvm_set_test.clj`
   - O(log n) lookup for present and absent elements
   - Boundary: empty set, 1-element set, collision-node set
   - Verify `contains` and `get` return correct values

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
   - Allocate new nodes in the slab via `alloc-block`
   - Return the new root offset (or sentinel for empty)

2. **Wire `EveHashMap.without`** to use `jvm-hamt-dissoc` instead of `(dissoc (into {} this) k)`

3. **Fix `EveHashMap.assoc` collision-node fallback** — currently falls back to
   `(assoc (into {} this) k v)`. Implement native collision-node assoc in the slab.

4. **Tests** — `test/eve/jvm_map_test.clj`
   - `dissoc` returns `EveHashMap`, not `PersistentHashMap`
   - Round-trip: assoc N keys, dissoc some, verify remaining
   - Collision-node dissoc
   - `dissoc` on missing key returns same map
   - Empty map after dissoc-ing all keys

#### 2b. Vector: Native `conj` / `assocN` / `pop`

**Work Items:**

1. **Add `jvm-vec-conj`** in `src/eve/vec.cljc`
   - Extend the RRB-style trie: if tail has room, append to tail; otherwise push
     tail into tree and allocate new tail
   - Allocate new nodes/leaves in slab

2. **Add `jvm-vec-assoc-n`** — path-copy update at index `i`

3. **Add `jvm-vec-pop`** — remove last element; if tail becomes empty, pull from tree

4. **Wire into `SabVecRoot` JVM deftype** — replace the three `(into [] this)` fallbacks

5. **Tests** — `test/eve/jvm_vec_test.clj`
   - `conj` returns `SabVecRoot`
   - `assocN` returns `SabVecRoot`
   - `pop` returns `SabVecRoot`
   - Round-trip: build vector with conj, verify `nth` on all indices
   - Pop to empty
   - assocN boundary (last index, index = count for append)

#### 2c. Set: Native `conj` / `disjoin`

**Work Items:**

1. **Add `jvm-set-hamt-conj`** in `src/eve/set.cljc`
   - Mirror `jvm-hamt-assoc!` from map but for value-only HAMT
   - Hash the element, walk/create trie path, store serialized value at leaf

2. **Add `jvm-set-hamt-disjoin`** — mirror `jvm-hamt-dissoc` from Phase 2a

3. **Wire into `EveHashSet` JVM deftype** — replace `cons` and `disjoin` fallbacks

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

**Work Items:**

1. **`EveHashMap`** — add `clojure.lang.IFn`
   - `invoke(Object key)` → `.valAt(key)`
   - `invoke(Object key, Object not-found)` → `.valAt(key, not-found)`

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

**Files:** `src/eve/map.cljc`, `src/eve/vec.cljc`, `src/eve/set.cljc`,
`src/eve/list.cljc`, corresponding test files

---

### Phase 4 — Correctness: `IHashEq` and `print-method`

**Gap:** No `IHashEq` means Eve collections used as keys/values in standard Clojure
hash maps/sets may have inconsistent hash behavior. No `print-method` means poor
REPL experience.

#### 4a. `IHashEq` on All JVM Types

**Work Items:**

1. Add `clojure.lang.IHashEq` to all four JVM deftypes
   - `hasheq()` returns the Murmur3-based ordered/unordered collection hash
   - Memoize in a `volatile` field (same pattern as CLJS `__hash`)
   - Map: `Murmur3/hashUnordered` over entry seq
   - Vec: `Murmur3/hashOrdered` over element seq
   - Set: `Murmur3/hashUnordered` over element seq
   - List: `Murmur3/hashOrdered` over element seq

2. **Tests** — for all four types
   - `(hash eve-coll)` equals `(hash (into <clj-equiv> eve-coll))`
   - Memoization: second call returns same value without recomputation

#### 4b. `print-method` on All JVM Types

**Work Items:**

1. Add `print-method` multimethods for all four types (in each `.cljc` file,
   inside `#?(:clj ...)` reader conditional)
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

**Work Items:**

1. Add `clojure.lang.IReduce` to all four JVM deftypes
   - `reduce(IFn f)` — 1-arg reduce (no init value)
   - `reduce(IFn f, Object init)` — 2-arg reduce
   - Map: walk HAMT nodes, apply `f` to each `MapEntry`
   - Vec: iterate indices 0..count-1, apply `f` to each `nth`
   - Set: walk HAMT nodes, apply `f` to each element
   - List: walk linked list nodes, apply `f` to each element
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

### Phase 6 — Completeness: Transients and List Gaps

#### 6a. Transients for JVM Map and Set

**Gap:** CLJS has `TransientEveHashMap` and `TransientEveHashSet`. JVM has none.

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
   - `first()`, `next()`, `more()`, `cons(Object)`

2. **Add `clojure.lang.Indexed`** to `SabList`
   - `nth(int)`, `nth(int, Object)` — O(n) traversal

3. **Add metadata** — `IMeta` / `IObj` with `_meta` field

4. **Wire `coll-factory`** through `jvm-sab-list-from-offset` to support nested
   Eve collections as list values

5. **Tests**
   - `(first l)`, `(rest l)`, `(next l)` work correctly
   - `(nth l 2)` works
   - `(with-meta l {:a 1})` works
   - Nested Eve collections inside list values deserialize correctly

**Files:** `src/eve/map.cljc`, `src/eve/set.cljc`, `src/eve/list.cljc`,
corresponding test files

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

---

## Testing Strategy

Each phase adds tests to the corresponding `test/eve/jvm_*_test.clj` files.
All tests run via `clojure -M:jvm-test`. The existing CLJS test suites
(per CLAUDE.md green baseline) must continue to pass after every phase.

**Test categories per phase:**

| Phase | Test Focus |
|---|---|
| 1 | Set contains/get correctness and performance |
| 2a | Map dissoc returns Eve type, round-trip, collision nodes |
| 2b | Vec conj/assocN/pop return Eve type, round-trip |
| 2c | Set conj/disjoin return Eve type, round-trip |
| 3 | `(coll arg)` and `(coll arg default)` for all types |
| 4a | `(hash eve-coll) == (hash clj-equiv)` for all types |
| 4b | `(pr-str eve-coll)` format for all types |
| 5a | `reduce` correctness + early termination for all types |
| 5b | Lazy seq memory (optional) |
| 6a | Transient round-trips for map and set |
| 6b | List first/rest/next/nth/meta/nested-coll |

---

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Slab allocator changes break CLJS | All slab code is shared `.cljc`; run full CLJS test suite after each phase |
| JVM HAMT write ops introduce corruption | Extensive round-trip tests; compare to CLJS behavior |
| Collision-node handling is complex | Port directly from existing CLJS collision-node code |
| Transient implementation is large | Defer to Phase 6; mirror CLJS transient deftypes closely |
| `IHashEq` hash values differ from Clojure stdlib | Verify `(hash eve-coll) == (hash (into <equiv> eve-coll))` in tests |

---

## Dependencies

```
Phase 1 (Set lookup) ─── blocks ──→ Phase 2c (Set write ops)
Phase 2a (Map dissoc) ── blocks ──→ Phase 6a (Map transients)
Phase 2c (Set write) ─── blocks ──→ Phase 6a (Set transients)
Phases 1-2 ───────────── blocks ──→ Phase 3 (IFn needs correct lookups)
Phases 1-3 are independent of ────→ Phases 4-5 (can parallelize)
Phase 6b (List gaps) is fully independent
```
