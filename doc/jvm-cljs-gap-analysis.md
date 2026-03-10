# Eve JVM ↔ CLJS Feature Parity Gap Analysis

> Generated: 2026-03-10

This document catalogs every gap between the ClojureScript and JVM implementations
of Eve's four core data structures: **EveHashMap**, **SabVecRoot**, **EveHashSet**,
and **SabList**.

---

## Executive Summary

The CLJS implementations are mature and feature-complete. The JVM implementations
were built for cross-process atom read/write but are missing significant
Clojure interop surface area. The biggest gaps:

1. **No transients** on any JVM type
2. **No `IFn`** (can't call collections as functions)
3. **No `IHashEq`** (Clojure's value-equality hashing)
4. **No `print-method`** (REPL printing falls back to `Object.toString`)
5. **No `IReduce`** on any JVM type (forces seq materialization for `reduce`)
6. **`dissoc` on map materializes to `clojure.core/hash-map` first**
7. **`conj`/`assocN`/`pop` on vector materialize to `clojure.core/vector` first**
8. **`conj`/`disjoin` on set materialize to `clojure.core/hash-set` first**
9. **No `ILookup` on list** (no `nth` via `get`)
10. **No metadata on list**

---

## Per-Type Gap Tables

### 1. EveHashMap (`src/eve/map.cljc`)

| Feature / Interface | CLJS | JVM | Gap |
|---|---|---|---|
| **Count** | `ICounted` | `clojure.lang.Counted` | Parity |
| **Lookup** | `ILookup` (with LRU cache) | `clojure.lang.ILookup` | Parity (no LRU cache on JVM, but functional) |
| **Associative** | `IAssociative` | `clojure.lang.IPersistentMap.assoc` | Parity — JVM has path-copy `assoc` |
| **Dissoc** | `IMap` (native HAMT dissoc) | `without` → `(dissoc (into {} this) k)` | **GAP: materializes entire map to Clojure map, then dissocs** |
| **Collection** | `ICollection` | `IPersistentCollection` | Parity |
| **Emptyable** | `IEmptyableCollection` | `IPersistentCollection.empty` | Parity |
| **Seqable** | `ISeqable` (lazy HAMT walk) | `clojure.lang.Seqable` (ArrayList materialization) | Functional parity, but JVM eagerly materializes |
| **Reduce** | `IReduce` (1-arity + 2-arity) | — | **GAP: no `clojure.lang.IReduce`; `reduce` goes through `seq`** |
| **KVReduce** | `IKVReduce` | `clojure.lang.IKVReduce` | Parity |
| **Equiv** | `IEquiv` | `IPersistentCollection.equiv` + `MapEquivalence` | Parity |
| **Hash** | `IHash` (memoized `__hash`) | `Object.hashCode` (not memoized, no `IHashEq`) | **GAP: no `IHashEq`, no `hasheq` memoization** |
| **IFn** | `-invoke` (1-arity, 2-arity) | — | **GAP: can't call map as function `(m :key)`** |
| **Transient** | `IEditableCollection` → `TransientEveHashMap` | — | **GAP: no transient support** |
| **Metadata** | `IMeta`/`IWithMeta` (returns nil / no-op) | `IMeta`/`IObj` (functional) | JVM is *better* — has real `IObj.withMeta` |
| **Print** | `IPrintWithWriter` (`{k v}` format) | `Object.toString` (`(str (into {} this))`) | **GAP: no `print-method`; REPL shows class name or materialized map** |
| **java.util.Map** | N/A | `java.util.Map` (partial — `containsValue` throws) | Parity (adequate) |
| **Iterable** | N/A | `java.lang.Iterable` | Parity |

**JVM-specific issues:**
- `without` (dissoc) at line 2908 does `(dissoc (into {} this) k)` — O(n) materialization, returns a *Clojure* PersistentHashMap, not an EveHashMap
- `assoc` falls back to `(assoc (into {} this) k v)` when encountering collision nodes, also returning a Clojure map
- No hash memoization (`__hash` field)
- `seq` eagerly builds an `ArrayList` then calls `seq` on it

---

### 2. SabVecRoot (`src/eve/vec.cljc`)

| Feature / Interface | CLJS | JVM | Gap |
|---|---|---|---|
| **Count** | `ICounted` | `clojure.lang.Counted` | Parity |
| **Indexed** | `IIndexed` | `clojure.lang.Indexed` | Parity |
| **Lookup** | `ILookup` | `IPersistentVector.valAt` | Parity |
| **Sequential** | `ISequential` | `clojure.lang.Sequential` | Parity |
| **Seqable** | `ISeqable` (lazy-seq) | `clojure.lang.Seqable` (ArrayList materialization) | Functional parity but JVM is eager |
| **Conj** | `ICollection` (native trie append) | `IPersistentVector.cons` → `(conj (into [] this) v)` | **GAP: materializes to Clojure vector** |
| **AssocN** | `IVector` (native path-copy) | `IPersistentVector.assocN` → `(assoc (into [] this) i v)` | **GAP: materializes to Clojure vector** |
| **Pop** | `IStack.-pop` (native trie pop) | `IPersistentVector.pop` → `(pop (into [] this))` | **GAP: materializes to Clojure vector** |
| **Peek** | `IStack.-peek` | `IPersistentVector.peek` | Parity |
| **Emptyable** | `IEmptyableCollection` | `IPersistentVector.empty` | Parity |
| **Reduce** | `IReduce` (1-arity + 2-arity) | — | **GAP: no `clojure.lang.IReduce`; `reduce` goes through `seq`** |
| **Equiv** | `IEquiv` | `IPersistentVector.equiv` | Parity |
| **Hash** | `IHash` | `Object.hashCode` (Murmur3, not memoized) | **GAP: no `IHashEq`, no memoization** |
| **IFn** | `-invoke` (1-arity, 2-arity) | — | **GAP: can't call vector as function `(v 0)`** |
| **Transient** | — (also missing in CLJS) | — | Neither platform has transients |
| **Metadata** | — (no metadata in CLJS) | `IMeta`/`IObj` | JVM is *better* |
| **Print** | `IPrintWithWriter` (`#sab/vec [...]`) | `Object.toString` (`(str (vec (seq this)))`) | **GAP: no `print-method`** |
| **Associative** | `IAssociative` | `clojure.lang.Associative` (delegates to `assocN`) | Parity (but assocN itself materializes) |
| **RandomAccess** | N/A | `java.util.RandomAccess` (marker only) | Parity |
| **Iterable** | N/A | `java.lang.Iterable` | Parity |

**JVM-specific issues:**
- `cons`, `assocN`, and `pop` all do `(into [] this)` first — O(n), and they return Clojure vectors, not Eve vectors
- No `java.util.List` implementation (Clojure's `PersistentVector` implements this)

---

### 3. EveHashSet (`src/eve/set.cljc`)

| Feature / Interface | CLJS | JVM | Gap |
|---|---|---|---|
| **Count** | `ICounted` | `clojure.lang.Counted` | Parity |
| **Lookup** | `ILookup` | `IPersistentSet.get` | Parity |
| **Set ops** | `ISet` (native HAMT disjoin) | `IPersistentSet.disjoin` → `(disj (into #{} this) elem)` | **GAP: materializes to Clojure set** |
| **Contains** | via `ILookup` | `IPersistentSet.contains` (linear scan via `jvm-set-reduce`) | **GAP: O(n) scan instead of O(log n) hash-directed lookup** |
| **Get** | via `ILookup` | `IPersistentSet.get` (linear scan via `jvm-set-reduce`) | **GAP: O(n) scan instead of O(log n) hash-directed lookup** |
| **Conj** | `ICollection` (native HAMT conj) | `IPersistentCollection.cons` → `(conj (into #{} this) elem)` | **GAP: materializes to Clojure set** |
| **Emptyable** | `IEmptyableCollection` | `IPersistentCollection.empty` | Parity |
| **Seqable** | `ISeqable` (HAMT walk) | `clojure.lang.Seqable` (ArrayList materialization) | Functional parity but JVM is eager |
| **Reduce** | `IReduce` (1-arity + 2-arity) | — | **GAP: no `clojure.lang.IReduce`** |
| **Equiv** | `IEquiv` | `IPersistentCollection.equiv` | Parity |
| **Hash** | `IHash` (memoized `__hash`) | `Object.hashCode` (Murmur3, not memoized) | **GAP: no `IHashEq`, no memoization** |
| **IFn** | `-invoke` (1-arity, 2-arity) | — | **GAP: can't call set as function `(s :val)`** |
| **Transient** | `IEditableCollection` → `TransientEveHashSet` | — | **GAP: no transient support** |
| **Metadata** | `IMeta`/`IWithMeta` (nil / no-op) | `IMeta`/`IObj` | JVM is *better* |
| **Print** | `IPrintWithWriter` (`#{...}`) | `Object.toString` (`(str (set (.seq this)))`) | **GAP: no `print-method`** |
| **java.util.Set** | N/A | `java.util.Set` | Parity |

**JVM-specific issues:**
- `contains` and `get` do a **full tree scan** (`jvm-set-reduce`) serializing every element to compare bytes — O(n) instead of O(log n)
- No hash-directed lookup function exists for the JVM set (unlike the JVM map which has `jvm-hamt-get`)
- `cons` and `disjoin` materialize to Clojure sets
- Uses `clojure.core/hash` for HAMT construction but has no hash-directed lookup on read

---

### 4. SabList (`src/eve/list.cljc`)

| Feature / Interface | CLJS | JVM | Gap |
|---|---|---|---|
| **Count** | `ICounted` | `clojure.lang.Counted` | Parity |
| **Sequential** | `ISequential` | `clojure.lang.Sequential` | Parity |
| **Seqable** | `ISeqable` (returns self) | `clojure.lang.Seqable` (lazy-seq) | Parity |
| **Seq** | `ISeq` (`-first`, `-rest`) | — | **GAP: no `clojure.lang.ISeq`; `first`/`rest` work via `Seqable.seq`** |
| **Next** | `INext` | — | **GAP: no `clojure.lang.ISeq` means no direct `next`** |
| **Stack** | `IStack` (`-peek`, `-pop`) | `IPersistentList` (`peek`, `pop`, `cons`) | Parity |
| **Collection** | `ICollection` (native conj) | `IPersistentList.cons` | Parity |
| **Emptyable** | `IEmptyableCollection` | `IPersistentCollection.empty` | Parity |
| **Reduce** | `IReduce` (1-arity + 2-arity) | — | **GAP: no `clojure.lang.IReduce`** |
| **Equiv** | `IEquiv` | `IPersistentCollection.equiv` | Parity |
| **Hash** | `IHash` | `Object.hashCode` (via `Util.hasheq(seq)`) | **GAP: no `IHashEq`** |
| **IFn** | `-invoke` (1-arity, 2-arity) | — | **GAP: can't call list as function** |
| **Indexed** | `IIndexed` (`-nth`) | — | **GAP: no `clojure.lang.Indexed`; `nth` only works via seq traversal** |
| **Metadata** | — (no metadata in CLJS) | — | **GAP: neither platform has metadata** |
| **Print** | `IPrintWithWriter` (`(...)` format) | `Object.toString` | **GAP: no `print-method`** |
| **Iterable** | N/A | `java.lang.Iterable` | Parity |

**JVM-specific issues:**
- No `coll-factory` parameter in `jvm-sab-list-from-offset` — nested collections in list values won't deserialize correctly
- `cons` works natively (good!) — but `pop` also works natively (good!)
- Missing `ISeq` interface means the list doesn't behave like a Clojure list under `first`/`rest`

---

## Cross-Cutting Gaps

### 1. No `clojure.lang.IFn` on any JVM type

All four CLJS types implement `IFn` so you can write `(my-map :key)`, `(my-vec 0)`,
`(my-set :val)`. None of the JVM types do. This is a major interop gap — idiomatic
Clojure code expects to call collections as functions.

**Fix:** Add `clojure.lang.IFn` with `invoke(Object)` and `invoke(Object, Object)`
to all four JVM deftypes.

### 2. No `clojure.lang.IHashEq` on any JVM type

Clojure's value equality system uses `IHashEq.hasheq()` for hash-based collection
membership. Without it, Eve collections in standard Clojure maps/sets may have
incorrect hash behavior.

**Fix:** Add `clojure.lang.IHashEq` with `hasheq()` returning memoized hash values.

### 3. No `print-method` on any JVM type

REPL output for Eve types currently shows `Object.toString()` which does
`(str (into {} this))` — it works but materializes the entire collection and doesn't
use Eve-specific formatting.

**Fix:** Add `(defmethod print-method EveHashMap ...)` etc. for all four types.

### 4. No `clojure.lang.IReduce` on any JVM type

When you `reduce` over a JVM Eve collection, Clojure falls back to iterating
over `seq`, which eagerly materializes the entire collection into an ArrayList.
The CLJS versions have direct `IReduce` that walks the slab in-place.

**Fix:** Add `clojure.lang.IReduce` with `reduce(IFn)` and `reduce(IFn, Object)`
that directly walk the slab, as the CLJS versions do.

### 5. No transients on JVM map or set

CLJS has `TransientEveHashMap` and `TransientEveHashSet` for efficient batch
construction. The JVM has nothing — `into` with an Eve map/set will be O(n²)
as each `conj` creates a new persistent structure (or worse, materializes).

**Fix:** Add `IEditableCollection` and corresponding `TransientEveHashMap` /
`TransientEveHashSet` JVM deftypes.

### 6. Mutating operations materialize to Clojure types

The most critical correctness/usability gap: on JVM, calling `dissoc`, `conj` (vec),
`assocN`, `pop` (vec), `conj` (set), or `disjoin` on Eve collections returns
**standard Clojure types**, not Eve types. This means:

- Type identity is lost: `(type (dissoc eve-map :k))` → `PersistentHashMap`
- Slab allocation is abandoned: results live on the Java heap, not in the slab
- Cross-process sharing breaks: materialized Clojure types can't be stored in mmap atoms

**Fix:** Implement native slab-based path-copy `dissoc` for map, native `conj`/`assocN`/`pop`
for vector, and native `conj`/`disjoin` for set.

### 7. Set lookup is O(n) on JVM

`EveHashSet.contains` and `EveHashSet.get` scan the entire HAMT tree via
`jvm-set-reduce`, serializing every element to compare. The map has `jvm-hamt-get`
for O(log n) lookup but the set has no equivalent.

**Fix:** Add `jvm-set-hamt-get` analogous to `jvm-hamt-get` in the map.

---

## Priority Matrix

| Priority | Gap | Impact | Difficulty |
|---|---|---|---|
| **P0** | Mutating ops return Clojure types (not Eve) | Correctness — breaks type contract | High |
| **P0** | Set lookup O(n) | Performance — quadratic for any set operation | Medium |
| **P1** | No `IFn` | Idiomatic Clojure broken | Low |
| **P1** | No `IHashEq` | Incorrect behavior in hash maps/sets | Low |
| **P1** | No `IReduce` | Perf — unnecessary materialization | Medium |
| **P1** | No `print-method` | Poor REPL experience | Low |
| **P2** | No transients | Perf — batch ops are slow | High |
| **P2** | Eager seq materialization | Perf — memory pressure on large collections | Medium |
| **P3** | List missing `ISeq`, `Indexed`, metadata | Minor interop gaps | Low |
| **P3** | List `coll-factory` not wired through | Nested collections in list values broken | Low |

---

## Test Coverage Gaps

The JVM test suites (`test/eve/jvm_*_test.clj`) cover basic roundtrip, lookup,
seq, and reduce, but do **not** test:

- `dissoc` / `without` on map
- `conj` / `assocN` / `pop` on vector
- `conj` / `disjoin` on set
- Transients (don't exist)
- `IFn` invocation
- `print-method` output
- Hash equality (`hasheq`)
- `reduce` performance (no `IReduce` benchmarks)
- Nested collections in lists

---

## Recommended Implementation Order

1. **Set O(log n) lookup** — add `jvm-set-hamt-get` (blocks everything else set-related)
2. **Map native `dissoc`** — path-copy HAMT delete (the map already has `jvm-hamt-assoc!`)
3. **Vector native `conj` / `assocN` / `pop`** — extend the trie write support
4. **Set native `conj` / `disjoin`** — extend HAMT write support for sets
5. **`IFn` on all types** — small, high-impact
6. **`IHashEq` on all types** — small, correctness-critical
7. **`print-method` on all types** — small, UX-critical
8. **`IReduce` on all types** — medium, uses existing traversal functions
9. **Transients for map and set** — larger effort, mirrors CLJS implementation
10. **List gaps** — `ISeq`, `Indexed`, metadata, `coll-factory`
