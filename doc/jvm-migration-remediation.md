# JVM Migration Remediation Plan

> Generated: 2026-03-11
> Context: Post-mortem analysis of Phases 0–7 JVM parity work.
> Problem: JVM swap! latency showed no improvement over baseline despite
>          slab-backed deftypes — root cause is gratuitous materialization.

---

## Executive Summary

The JVM parity migration (Phases 0–7) successfully ported protocol
implementations (IFn, IHashEq, IReduce, transients, etc.) but missed the
fundamental design principle of the CLJS implementation: **zero-copy, lazy
slab access**. Only `EveHashMap` got a proper slab-backed JVM deftype.
Sets, vecs, and lists are eagerly materialized to plain Clojure types on
every `deref` and nested read, then fully re-serialized on every `swap!`.

This turns the O(log32 n) HAMT advantage into O(n) on both sides of the
user function, explaining why benchmarks showed no improvement.

---

## Root Cause Analysis

### The CLJS model (correct)

```
deref:
  read root pointer (i32)                    → O(1)
  construct Eve type from header-off         → O(1)
  return SabVecRoot / EveHashSet / SabList   → lazy, zero-copy

swap!:
  deref old-val                              → O(1)
  user fn: (assoc old-val :k v)              → path-copy in slab, O(log32 n)
  cljs-resolve-new-ptr:
    (satisfies? IEveRoot new-val) → true
    return (-root-header-off new-val)        → O(1)
  CAS root pointer                           → O(1)
```

Total: O(log32 n) — only the changed path is touched.

### The JVM model (broken)

```
deref:
  read root pointer (i32)                    → O(1)
  jvm-read-root-value:
    map?  → EveHashMap (lazy)                → O(1) ✓
    set?  → (into #{} ...)                   → O(n) ✗ WALKS ENTIRE SET
    vec?  → (into [] ...)                    → O(n) ✗ WALKS ENTIRE VEC
    list? → (into '() (reverse ...))         → O(n) ✗ WALKS ENTIRE LIST

swap!:
  deref old-val                              → O(n) for set/vec/list
  user fn: (assoc old-val :k v)              → operates on PLAIN types
  jvm-resolve-new-ptr:
    (instance? EveHashMap new-val) → false (it's a plain map now)
    (map? new-val) → true
    jvm-write-map! → RE-SERIALIZES ENTIRE MAP → O(n)
  CAS root pointer                           → O(1)
```

Total: O(n) on deref + O(n) on re-serialize = O(2n). Worse: if the root
is a map containing vecs/sets, the nested collections are ALSO materialized
recursively during deserialization.

---

## The Two Functions That Must Change

### 1. `jvm-coll-factory` (atom.cljc:577–587)

Current — materializes sets, vecs, lists:

```clojure
(defn- jvm-coll-factory [tag sio slab-offset]
  (case (int tag)
    0x10 (eve-map/jvm-eve-hash-map-from-offset sio slab-offset jvm-coll-factory)
    0x11 (into #{} (eve-set/jvm-eve-hash-set-from-offset sio slab-offset jvm-coll-factory))
    0x12 (into [] (eve-vec/jvm-sabvec-from-offset sio slab-offset jvm-coll-factory))
    0x13 (into '() (reverse (eve-list/jvm-sab-list-from-offset sio slab-offset)))
    (throw ...)))
```

Target — return slab-backed types directly (matches CLJS):

```clojure
(defn- jvm-coll-factory [tag sio slab-offset]
  (case (int tag)
    0x10 (eve-map/jvm-eve-hash-map-from-offset sio slab-offset jvm-coll-factory)
    0x11 (eve-set/jvm-eve-hash-set-from-offset sio slab-offset jvm-coll-factory)
    0x12 (eve-vec/jvm-sabvec-from-offset sio slab-offset jvm-coll-factory)
    0x13 (eve-list/jvm-sab-list-from-offset sio slab-offset jvm-coll-factory)
    (throw ...)))
```

### 2. `jvm-read-root-value` (atom.cljc:589–605)

Same change — remove all `(into ...)` wrappers:

```clojure
(defn- jvm-read-root-value [sio ptr]
  (when (and (not= ptr alloc/NIL_OFFSET) (not= ptr CLAIMED_SENTINEL))
    (let [type-id (alloc/jvm-read-header-type-byte sio ptr)
          cf      jvm-coll-factory]
      (case (int type-id)
        0xED (eve-map/jvm-eve-hash-map-from-offset sio ptr cf)
        0xEE (eve-set/jvm-eve-hash-set-from-offset sio ptr cf)
        0x12 (eve-vec/jvm-sabvec-from-offset sio ptr cf)
        0x13 (eve-list/jvm-sab-list-from-offset sio ptr cf)
        0x1D (eve-array/jvm-eve-array-from-offset sio ptr)
        0x1E (eve-obj/jvm-obj-from-offset sio ptr)
        0x01 (alloc/jvm-read-scalar-block sio ptr)
        (throw ...)))))
```

### 3. `jvm-resolve-new-ptr` (atom.cljc:643–663)

Current — only short-circuits for `EveHashMap`:

```clojure
(cond
  (instance? EveHashMap new-val) (.-header-off ^EveHashMap new-val)
  (map? new-val) (jvm-write-map! sio encode new-val)  ;; re-serialize
  ...)
```

Target — short-circuit for ALL Eve types:

```clojure
(cond
  (nil? new-val)                  alloc/NIL_OFFSET
  (instance? EveHashMap new-val)  (.-header-off ^EveHashMap new-val)
  (instance? EveHashSet new-val)  (.-header-off ^EveHashSet new-val)
  (instance? SabVecRoot new-val)  (.-header-off ^SabVecRoot new-val)
  (instance? SabList new-val)     (.-header-off ^SabList new-val)
  ;; Fallback for plain Clojure types (user constructed a new collection)
  (map? new-val) (jvm-write-map! sio encode new-val)
  ...)
```

This mirrors CLJS `cljs-resolve-new-ptr` which uses `(satisfies? IEveRoot new-val)`
and calls `(-root-header-off new-val)`.

---

## Detailed Gap Inventory

### Gap 1: Vec `conj`/`assocN`/`pop` return plain types

**File:** `vec.cljc` JVM `SabVecRoot` deftype (line 1315+)

**Problem:** `conj`, `assocN`, and `pop` allocate new slab blocks correctly
but the result `SabVecRoot` loses its Eve identity when it flows through
`jvm-resolve-new-ptr` — the pass-through check only covers `EveHashMap`.

**Fix:** Add `(instance? SabVecRoot new-val)` check to `jvm-resolve-new-ptr`.
The JVM `SabVecRoot` deftype already stores `header-off` and implements
slab-backed `nth`/`seq`/`reduce`. This is already correct — just needs the
wire-up.

**Verify:** `SabVecRoot` already has a `header-off` field (line 1316).

---

### Gap 2: Set `conj`/`disjoin` fallback to `(into #{} this)`

**File:** `set.cljc` JVM `EveHashSet` deftype (line 1928+)

**Problem:** Lines 1952 and 1990 — when `jvm-hashed?` is false, `disj` and
`conj` materialize the entire set via `(into #{} this)` and return a plain
Clojure set.

**Fix in two parts:**

1. **Remove `jvm-hashed?` flag.** Portable hashing is now universal (Phase 0
   migrated sets to `portable-hash-bytes`). The flag is vestigial. The
   previous Claude already removed it from `EveHashMap` in commit `7b2cf6e`.
   Apply the same treatment to `EveHashSet`.

2. **Add `(instance? EveHashSet new-val)` to `jvm-resolve-new-ptr`.**

**Verify:** `EveHashSet` already has `header-off` field (line 1928).

---

### Gap 3: List has no `coll-factory` pass-through for nested reads

**File:** `list.cljc` JVM `SabList` deftype (line 1108+)

**Problem:** `jvm-sab-list-from-offset` is called WITHOUT `jvm-coll-factory`
in `jvm-coll-factory` (atom.cljc:586) and `jvm-read-root-value` (atom.cljc:600).
Nested collections inside list elements won't reconstruct as Eve types.

**Fix:** Pass `jvm-coll-factory` through to `jvm-sab-list-from-offset` like
the other types already do.

**Verify:** Check `jvm-sab-list-from-offset` signature — does it accept
`coll-factory`?

---

### Gap 4: `jvm-collect-replaced-nodes` only handles maps

**File:** `atom.cljc` lines 665–697

**Problem:** The tree-diff for epoch GC retirement (`jvm-collect-replaced-nodes`)
only walks HAMT bitmap nodes (type 1). When the root is a vec/set/list,
the retirement path falls through to the `tree-logs` ConcurrentHashMap
(lines 731–733), which stores the alloc log from `jvm-resolve-new-ptr`.

But if `jvm-resolve-new-ptr` returns a pass-through (header-off directly),
no alloc log is generated. The old tree's blocks are never queued for GC.

**Fix:** Either:
- (a) Implement `jvm-collect-replaced-nodes` for vec/set/list trie structures, or
- (b) Use the CLJS approach — `collect-retire-diff-offsets` walks both old and
  new trees to find structurally-unshared nodes. Each collection type would
  implement a JVM equivalent of `ISabRetirable.-sab-retire-diff!`.

Option (b) is cleaner since each type knows its own structure.

---

### Gap 5: `eve-passthru?` check only covers `EveHashMap`

**File:** `atom.cljc` line 718

```clojure
eve-passthru? (instance? EveHashMap new-val)
```

**Problem:** This flag determines whether to use structural diff (efficient)
or alloc-log (bulk) for retirement. When vec/set/list get pass-through
treatment, this needs to cover them too.

**Fix:** Check all Eve types:

```clojure
eve-passthru? (or (instance? EveHashMap new-val)
                  (instance? EveHashSet new-val)
                  (instance? SabVecRoot new-val)
                  (instance? SabList new-val))
```

Or better, define a JVM protocol/marker that all Eve types implement.

---

### Gap 6: `print-method` materializes entire collection

**File:** `map.cljc` line 3300

```clojure
(defmethod print-method EveHashMap [^EveHashMap m ^java.io.Writer w]
  (print-method (into {} m) w))
```

**Problem:** Printing an `EveHashMap` materializes the entire map. Same
pattern likely exists or will be needed for set/vec/list.

**Fix:** Implement print-method using lazy seq iteration:

```clojure
(defmethod print-method EveHashMap [^EveHashMap m ^java.io.Writer w]
  (.write w "{")
  (let [s (seq m)]
    (when s
      (let [e (first s)]
        (print-method (key e) w) (.write w " ") (print-method (val e) w))
      (doseq [e (rest s)]
        (.write w ", ")
        (print-method (key e) w) (.write w " ") (print-method (val e) w))))
  (.write w "}"))
```

Same for `toString` (line 3216).

---

### Gap 7: `value+sio->eve-bytes` creates `partial` on every call

**File:** `mem.cljc` line 1124+

```clojure
(defn value+sio->eve-bytes [sio v]
  (let [writers @jvm-coll-writers]
    (cond
      (map? v)
      (if-let [write-map! (get writers :map)]
        (jvm-sab-pointer-bytes 0x10 (write-map! sio (partial value+sio->eve-bytes sio) v))
        ...)
```

**Problem:** `(partial value+sio->eve-bytes sio)` creates a new closure
object on every nested call. Minor but adds GC pressure in hot paths.

**Fix:** Pre-bind or use `fn` with closed-over `sio`:

```clojure
(let [encode (fn [v] (value+sio->eve-bytes sio v))]
  ...)
```

---

### Gap 8: `jvm-write-map!` re-serializes nested Eve collections

**File:** `map.cljc` JVM `jvm-write-map!` (line 3058+)

**Problem:** When the user function returns an `EveHashMap` that contains
nested `EveHashSet`s or `SabVecRoot`s, and those were untouched by the user
function, `jvm-write-map!` still serializes them via `value+sio->eve-bytes`
which allocates new slab blocks for them.

**Fix:** `value+sio->eve-bytes` should check if the value is already an
Eve type in the slab. If so, emit a SAB pointer to its existing `header-off`
instead of re-serializing:

```clojure
(cond
  (instance? EveHashMap v) (jvm-sab-pointer-bytes 0x10 (.-header-off ^EveHashMap v))
  (instance? EveHashSet v) (jvm-sab-pointer-bytes 0x11 (.-header-off ^EveHashSet v))
  (instance? SabVecRoot v) (jvm-sab-pointer-bytes 0x12 (.-header-off ^SabVecRoot v))
  (instance? SabList v)    (jvm-sab-pointer-bytes 0x13 (.-header-off ^SabList v))
  ;; Then existing primitive/collection logic
  ...)
```

This is the JVM equivalent of the CLJS path where Eve types already have
SAB pointer bytes via `ISabStorable.-sab-encode`.

---

## Implementation Phases

### Phase R0 — Remove `into` materialization (atom.cljc)

**Scope:** 3 functions, ~20 lines changed.

1. `jvm-coll-factory` — remove `(into ...)` wrappers for tags 0x11, 0x12, 0x13
2. `jvm-read-root-value` — same removal for type-ids 0xEE, 0x12, 0x13
3. Pass `jvm-coll-factory` to `jvm-sab-list-from-offset` (currently missing)

**Test:** `deref` now returns `EveHashSet`, `SabVecRoot`, `SabList` instead
of plain Clojure types. Existing JVM tests that assert plain types will need
updating.

**Risk:** Low — these JVM deftypes already exist and implement all necessary
Clojure protocols.

---

### Phase R1 — Pass-through in `jvm-resolve-new-ptr` (atom.cljc)

**Scope:** ~10 lines added to one function.

1. Add `instance?` checks for `EveHashSet`, `SabVecRoot`, `SabList`
2. Return `(.-header-off ...)` directly for each
3. Widen `eve-passthru?` check to cover all Eve types

**Test:** `swap!` with identity function `(swap! a identity)` should be
near-zero cost — no slab allocation, no serialization.

**Risk:** Low.

---

### Phase R2 — Remove `jvm-hashed?` from `EveHashSet` (set.cljc)

**Scope:** ~30 lines changed in set.cljc.

1. Remove `jvm-hashed?` field from JVM `EveHashSet` deftype
2. Remove `(if jvm-hashed? ... (into #{} this))` fallback branches
3. Hardcode portable-hash path (always true)
4. Remove flag from `jvm-set-write-header!`

**Test:** All JVM set tests pass without fallback paths.

**Risk:** Low — portable hashing has been universal since Phase 0.

---

### Phase R3 — Eve type pass-through in `value+sio->eve-bytes` (mem.cljc)

**Scope:** ~15 lines added to `value+sio->eve-bytes`.

1. Before checking `map?`/`set?`/`vector?`, check for Eve type instances
2. If already in slab, emit SAB pointer bytes using existing `header-off`
3. Skip re-serialization entirely

**Test:** `swap!` that returns an `EveHashMap` containing nested unchanged
`SabVecRoot`s should NOT allocate new blocks for those vecs.

**Risk:** Medium — must ensure epoch GC still tracks all live blocks correctly.
Blocks from the old tree that are structurally shared into the new tree must
NOT be freed.

---

### Phase R4 — Epoch GC for non-map types (atom.cljc)

**Scope:** Medium — structural diff for vec/set/list trie structures.

1. Implement `jvm-collect-replaced-nodes` (or equivalent) for vec trie structure
2. Implement same for set HAMT structure (can reuse map's walker)
3. Implement same for list linked structure (simpler — linear walk)
4. Or: define a JVM protocol analogous to `ISabRetirable` that each type implements

**Test:** After 100+ swaps, slab free counts should stay bounded — old blocks
are reclaimed.

**Risk:** Medium — must match exact slab block layout for each type.

---

### Phase R5 — Fix `print-method` and `toString` (map.cljc + others)

**Scope:** ~20 lines per type.

1. `EveHashMap.toString` — use lazy seq, don't `(into {} this)`
2. `print-method EveHashMap` — same
3. Add `print-method` for `EveHashSet`, `SabVecRoot`, `SabList` using lazy seq

**Test:** `(pr-str eve-map)` produces correct output without materialization.

**Risk:** Low.

---

## Phase Dependencies

```
R0 (remove into) ──────────────────── must be first
  ├── R1 (pass-through) ───────────── immediately after R0
  │     └── R3 (nested pass-through)  after R1
  │           └── R4 (epoch GC)────── after R3 (blocks stay shared)
  ├── R2 (remove jvm-hashed?) ─────── independent, any time after R0
  └── R5 (print-method) ──────────── independent, any time
```

R0 is the critical fix. R1 makes swap! fast. R3 prevents re-serialization
of unchanged nested collections. R4 ensures GC correctness when blocks are
shared across old and new trees.

---

## Expected Performance Impact

After R0 + R1:
- `deref` becomes O(1) for all types (construct wrapper, no tree walk)
- `swap!` with EveHashMap assoc stays O(log32 n) (already working)
- `swap!` with unchanged nested vecs/sets/lists becomes O(1) instead of O(n)

After R3:
- `swap!` that modifies one key in a map containing 100 nested vecs/sets
  only re-serializes the ONE modified path, not the 99 unchanged collections

After R4:
- Long-running processes won't leak slab blocks

---

## Design Principle

The CLJS implementation follows one rule: **the deftype IS the slab view**.
There is no factory, no materialization, no round-trip. `deref` returns the
type. The type reads from slab on demand. Mutations path-copy in slab and
return a new type pointing at the new root.

The JVM implementation should follow the exact same rule. The JVM deftypes
(`EveHashSet`, `SabVecRoot`, `SabList`) already exist and already implement
the necessary Clojure protocols. The only thing preventing zero-copy behavior
is the `(into ...)` calls in `atom.cljc` and the missing pass-through checks
in `jvm-resolve-new-ptr`.
