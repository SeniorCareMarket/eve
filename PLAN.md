# Plan: Babashka Port of Eve (Fast Path)

## Goal

Port Eve's cross-process mmap atom to Babashka, replicating the **fast** version
from the prior branch (commit `516e730`: 152 μs/swap scalar, 3.2 ms/swap map).

The key insight from the prior attempt: **avoid the registry/closure indirection
for the deref and swap hot paths**. Instead, use direct `defn` calls that bb's
SCI can resolve as hoisted compiled vars.

## Current Codebase State (post-eve3 migration)

- `eve2/`, `eve3/` dirs are gone — everything consolidated into `eve.*`
- `eve/deftype` macro (in `macros.clj`) emits CLJ-style deftypes with Java
  interface impls (`clojure.lang.Counted`, `ILookup`, `IReduceInit`, etc.)
- `register-eve-type!` macro handles both CLJ and CLJS registration via
  `ser/register-jvm-type-constructor!` and `ser/register-jvm-header-constructor!`
- HAMT core functions (`hamt-get`, `hamt-assoc`, `hamt-kv-reduce`, `hamt-seq`)
  are plain `defn-` in `.cljc` — **already portable**
- Same for vec (`vec-nth`, trie ops), set, list
- No `:bb` reader conditionals exist currently
- No `bb.edn` exists currently

## Architecture

BB reads `:bb` before `:clj` in reader conditionals. We add `:bb` branches
that:

1. **mem.cljc**: `BbMmapRegion` + `BbHeapRegion` using `MappedByteBuffer` +
   `locking` (same as prior branch — this worked well)
2. **atom.cljc**: Direct inline deref/swap using `hamt-kv-reduce` etc. —
   **NOT** going through `ser/get-jvm-header-constructor` registry
3. **Collection deftypes**: `#?(:bb nil :default ...)` around the `eve/deftype`
   + constructor fns. BB gets materializing constructors instead.
4. **serialize.cljc**: `#?(:bb nil :clj ...)` around `ConcurrentHashMap` usage

## Files to Change

### 1. `src/eve/mem.cljc` — BbMmapRegion + BbHeapRegion

Add `:bb` reader conditional **before** `:clj` in the ns `:import` form and
in the implementation section.

**BB imports**: `RandomAccessFile`, `ByteBuffer`, `ByteOrder`,
`MappedByteBuffer`, `FileChannel`, `FileChannel$MapMode`, `Paths`,
`OpenOption`, `StandardOpenOption`

**BB does NOT import**: `Arena`, `MemorySegment` (Panama FFM),
`sun.misc.Unsafe`, `ReentrantLock`

**New deftypes**:
- `BbMmapRegion [^MappedByteBuffer mbb ^long size]` — IMemRegion via
  `locking bb-lock` + `.getInt`/`.putInt` on MappedByteBuffer
- `BbHeapRegion [^ByteBuffer bb-buf ^bytes backing ^long size]` — IMemRegion
  via `ByteBuffer.wrap`

**New functions**:
- `open-mmap-region` (bb version) — `RandomAccessFile` + `FileChannel.map()`
- `make-heap-region` (bb version) — `ByteBuffer.wrap`
- `copy-region!` (bb version) — read-bytes + write-bytes

All of this is essentially identical to the prior branch's commit `633d8fb`.

### 2. `src/eve/atom.cljc` — The Critical Hot Path

This is where the prior branch regressed. We must keep the **fast** pattern.

**Changes in the `#?(:clj ...)` block** — add nested `#?(:bb ... :clj ...)`
for these functions:

#### a. `cas-backoff!`
- BB: `Thread/sleep` (no `LockSupport/parkNanos`)

#### b. `jvm-open-mmap-domain!` and `jvm-join-mmap-domain!`
- BB: No `ScheduledExecutorService`, no `reify ThreadFactory`
- BB: Use `LinkedList` instead of `ConcurrentLinkedQueue` (single-threaded)
- BB: `heartbeat-sched nil`

#### c. `jvm-read-root-value` — THE KEY FUNCTION
- BB version: **direct inline dispatch**, not registry lookup
- Pattern:
  ```clojure
  #?(:bb
     (defn- jvm-read-root-value [sio ptr]
       (when (and (not= ptr alloc/NIL_OFFSET) ...)
         (let [type-id (alloc/jvm-read-header-type-byte sio ptr)]
           (case (int type-id)
             0x01 (alloc/jvm-read-scalar-block sio ptr)
             0xED (bb-materialize-map sio ptr)
             0xEE (bb-materialize-set sio ptr)
             0x12 (bb-materialize-vec sio ptr)
             0x13 (bb-materialize-list sio ptr)
             (throw ...)))))
     :clj
     (defn- jvm-read-root-value ...existing registry-based...))
  ```

#### d. `bb-materialize-*` functions (new, in atom.cljc)
Direct calls to the HAMT/trie traversal functions in each collection ns:
```clojure
(defn- bb-materialize-map [sio ptr]
  (let [[_cnt root-off] (eve-map/read-map-header sio ptr)]
    (eve-map/hamt-kv-reduce sio root-off
      (fn [m k v] (assoc m k v)) {})))
```
These call the existing `defn-` functions directly — bb resolves them as
hoisted vars, NOT as SCI-interpreted closures.

#### e. `jvm-resolve-new-ptr`
- BB: `satisfies? d/IEveRoot` instead of `instance? EveHashMap`
- BB: No `.isArray` check, no array/obj support
- BB: Call `mem/jvm-write-collection!` for maps/sets/vecs/lists (same as CLJ)

#### f. `jvm-try-flush-retires!`
- BB: No `^java.util.Queue` type hint

#### g. `jvm-mmap-swap!`
- BB: `satisfies? d/IEveRoot` instead of `instance? EveHashMap`
- BB: Skip the map→map replaced-node optimization (`instance?` checks)

#### h. `MmapAtom` deftype
- BB: `clojure.lang.IDeref` + `clojure.lang.IAtom` (both supported in bb deftype)

#### i. `MmapAtomDomain` deftype
- BB: `clojure.lang.IDeref` only (no `ILookup` in bb deftype)
- Provide a function `domain-lookup` for atom retrieval instead

#### j. `jvm-pin-thread-epoch!` / `jvm-unpin-thread-epoch!`
- BB: Simplified — single-threaded, so use a plain atom instead of
  `ConcurrentHashMap` + `Thread/currentThread`

### 3. `src/eve/map.cljc` — BB collection compatibility

#### a. Wrap `eve/deftype EveHashMap` + constructors
```clojure
#?(:bb nil
   :default
   (eve/deftype EveHashMap [...] ...))

#?(:bb nil
   :default
   (do (defn- make-hash-map ...) (defn hash-map-from-header ...) ...))
```

#### b. Keep HAMT core functions (`hamt-assoc`, `hamt-kv-reduce`, etc.) UNWRAPPED
These are plain `defn-` — they work in bb as-is via ISlabIO protocol dispatch.

#### c. Make `read-map-header` and `write-map-header!` public for bb
Currently `defn-` — need to be accessible from `atom.cljc` bb branch.
Alternative: use the `hamt-kv-reduce` + `read-map-header` directly, promoting
to `defn` or using a bb-specific namespace alias.

#### d. Registration section
```clojure
#?(:bb
   (do
     (register-jvm-collection-writer! :map ...)  ;; same writer fn
     ;; NO register-jvm-type-constructor! — bb doesn't use the registry
     )
   :clj
   (do ...existing...))
```
But actually the `register-eve-type!` macro generates the registration. We need
to wrap it:
```clojure
#?(:bb nil
   :default
   (eve/register-eve-type! {...}))
```
And add a separate bb registration for the collection writer only.

### 4. `src/eve/set.cljc`, `src/eve/vec.cljc`, `src/eve/list.cljc`

Same pattern as map.cljc:
- Wrap `eve/deftype` + constructors in `#?(:bb nil :default ...)`
- Wrap `eve/register-eve-type!` in `#?(:bb nil :default ...)`
- Keep core traversal functions unwrapped
- Add bb-specific `register-jvm-collection-writer!` if needed
- Make header-read functions accessible to atom.cljc

### 5. `src/eve/deftype_proto/serialize.cljc`

Wrap JVM registry infrastructure:
```clojure
#?(:bb
   (do
     ;; Minimal stubs — bb doesn't use the registry for deref
     (defn get-jvm-type-constructor [_] nil)
     (defn get-jvm-header-constructor [_] nil)
     (defn register-jvm-type-constructor! [& _] nil)
     (defn register-jvm-header-constructor! [& _] nil))
   :clj
   (do ...existing ConcurrentHashMap-based...))
```

### 6. `src/eve/perf.cljc` (or rename `.clj` → `.cljc`)

If still `.clj`, rename to `.cljc` and add `:bb` no-op stubs for `timed`,
`count!`, etc. The prior branch already did this.

### 7. `src/eve/deftype_proto/alloc.cljc`

The `JvmSlabCtx` deftype and `*jvm-slab-ctx*` dynamic var should work in bb —
`JvmSlabCtx` only implements `ISlabIO` (a Clojure protocol). Verify no
bb-incompatible type hints.

Check for `ConcurrentHashMap` usage — the alloc logging uses `ArrayList` which
is fine in bb.

### 8. New files

- `bb.edn` — paths + tasks config
- `examples/bb_atom_swap.clj` — example script (same as prior branch)

## Key Design Decisions

### Why NOT use the registry for bb deref?

The `register-eve-type!` macro generates anonymous closures stored in a
`ConcurrentHashMap`. When bb's SCI calls `(ctor ptr)`, it must interpret that
closure body on every invocation. The direct `case` + named-function-call
pattern lets SCI resolve the target as a hoisted var — the function body itself
is a compiled Clojure fn loaded at ns-require time, not re-interpreted.

### Why materialize to Clojure data?

BB's `deftype` can't implement Java interfaces (`clojure.lang.ILookup`,
`Counted`, `ISeq`, etc.). Without those, you can't use `(:key m)`, `(count m)`,
`(seq m)` on Eve types. So bb's `jvm-read-root-value` materializes the
slab-backed HAMT into a plain `{}` / `#{}` / `[]` / `()`.

This is fine because:
- The user's `swap!` fn receives and returns plain Clojure data
- The serialize path (jvm-resolve-new-ptr) already handles plain maps/vecs/sets
- Only the deref hot path pays the materialization cost

### What about the `eve/deftype` macro in bb?

The macro checks `(:ns &env)` — nil in bb → takes CLJ path → emits Java
interface impls → bb chokes. So we wrap `eve/deftype` calls in
`#?(:bb nil :default ...)`. BB never sees the deftypes.

## Verification

After implementation, all existing tests must still pass:

```bash
npx shadow-cljs compile eve-test
node target/eve-test/all.js all
clojure -M:jvm-test
```

Then bb-specific verification:

```bash
bb examples/bb_atom_swap.clj
```

Expected output similar to prior fast version:
- Domain open: ~60ms
- Atom create: ~4ms
- 100 scalar swaps: ~15ms (150 μs/swap)
- 50 map assocs: ~160ms (3.2 ms/swap)
- 1000 derefs: ~50ms (50 μs/deref)
