# Research: Unifying Eve's Codebase via Protocol Abstraction

> How much of Eve's non-cljc code can be cljc-ified, and what abstractions make that possible?

---

## Executive Summary

Eve currently has **~26,400 lines** of source code across 32 files. Of those, **~16,400 lines** are in `.cljc` files (already cross-platform to some degree), but substantial logic remains locked in CLJS-only `.cljs` files or behind `#?` reader conditionals within `.cljc` files.

**The core finding:** IMemRegion already provides 90% of the abstraction needed. The remaining gap is that CLJS code **bypasses** IMemRegion for performance, using raw DataView/Uint8Array/Atomics calls. However, `ISlabIO` — a higher-level protocol sitting above IMemRegion — already abstracts slab block I/O and is used by all data structure `.cljc` files. By routing *all* CLJS memory access through ISlabIO (instead of module-level `alloc/read-*` functions that use raw DataView), most of the codebase becomes pure `.cljc`.

**Projected impact:** Code sharing could increase from ~74% to ~90-92%, eliminating ~2,000-3,000 lines of duplicated platform-specific code across data structure files.

---

## Table of Contents

1. [Current Codebase Inventory](#1-current-codebase-inventory)
2. [The Two Protocol Layers](#2-the-two-protocol-layers)
3. [The DataView Bypass Problem](#3-the-dataview-bypass-problem)
4. [Per-File Analysis: What Can Be cljc-ified](#4-per-file-analysis)
5. [Proposed Architecture](#5-proposed-architecture)
6. [Migration Strategy](#6-migration-strategy)
7. [Performance Considerations](#7-performance-considerations)
8. [Appendix: Raw DataView Call Census](#appendix-raw-dataview-call-census)

---

## 1. Current Codebase Inventory

### File Classification

| Category | Files | Lines | Notes |
|----------|-------|-------|-------|
| **Shared .cljc** | 13 | 16,445 | Cross-platform with `#?` conditionals |
| **CLJS-only .cljs** | 14 | 9,213 | Includes PROTECTED `shared_atom.cljs` |
| **CLJ-only .clj** | 5 | 1,141 | JVM entry points + deftypes |
| **Total** | **32** | **26,799** | |

### .cljc Files — Current Sharing Percentages

| File | Lines | Shared | CLJS-only | CLJ-only | Key Split Point |
|------|-------|--------|-----------|----------|-----------------|
| `coalesc.cljc` | 479 | **100%** | 0% | 0% | Pure algorithm via IMemRegion |
| `data.cljc` | 464 | **95%** | 3% | 2% | `count` vs `.-length` |
| `serialize.cljc` | 1,249 | **85%** | 10% | 5% | DataView vs IMemRegion for read/write |
| `map.cljc` | 3,514 | **85%** | 8% | 7% | Memory access + deftype wrappers |
| `alloc.cljc` | 1,699 | **55%** | 35% | 10% | CljsSlabIO vs JvmSlabCtx |
| `hamt_util.cljc` | 107 | **75%** | 15% | 10% | Bitwise op helpers |
| `set.cljc` | 2,110 | **75%** | 18% | 7% | Same as map |
| `vec.cljc` | 1,553 | **70%** | 20% | 10% | Read shared; writes CLJS-only |
| `obj.cljc` | 662 | **70%** | 20% | 10% | Schema shared; ops diverge |
| `array.cljc` | 1,039 | **65%** | 25% | 10% | Type system shared; SIMD CLJS-only |
| `mem.cljc` | 1,193 | **10%** | 45% | 45% | Protocol definition shared; impls diverge |
| `list.cljc` | 1,208 | **60%** | 30% | 10% | Pool mgmt CLJS-only |
| `atom.cljc` | 1,168 | **40%** | 35% | 25% | Heartbeat/timer/atom diverge |

**Blended average: ~74% shared**

### CLJS-Only Files

| File | Lines | Can cljc-ify? | Effort | Notes |
|------|-------|---------------|--------|-------|
| `shared_atom.cljs` | 3,695 | **No** | PROTECTED | SAB-specific; not mmap |
| `deftype_proto/simd_hamt.cljs` | 674 | **No** | N/A | WASM SIMD intrinsics |
| `deftype_proto/wasm.cljs` | 659 | **No** | N/A | WebAssembly memory mgmt |
| `deftype_proto/simd_wasm.cljs` | 480 | **No** | N/A | WASM module loader |
| `deftype_proto/simd.cljs` | 420 | **No** | N/A | SIMD operations |
| `wasm_mem.cljs` | 668 | **No** | N/A | WASM memory pages |
| `deftype/rb_tree.cljs` | 619 | **Maybe** | Hard | SAB-only; uses `eve.deftype` macros |
| `deftype/int_map.cljs` | 918 | **Maybe** | Hard | SAB-only; uses `eve.deftype` macros |
| `util.cljs` | 361 | **Partially** | Easy | buf->hex, hex->buf portable |
| `deftype_proto/xray.cljs` | 639 | **Yes** | Medium | Debug tool; needs ISlabIO |
| `deftype_proto/chunked_kv_seq.cljs` | 317 | **Partially** | Medium | Chunked seq; protocol names differ |
| `deftype/runtime.cljs` | 300 | **Yes** | Medium | Registry pattern; CLJ equiv exists |
| `data.cljs` | 248 | **Yes** | Easy | Constants; trivial `#?` |
| `deftype.cljs` | 2 | N/A | N/A | Macro self-require |
| `alpha.cljs` | 35 | N/A | N/A | Entry point |

### CLJ-Only Files

| File | Lines | Notes |
|------|-------|-------|
| `deftype_proto.clj` | 473 | JVM helpers for slab init, heap lifecycle |
| `deftype.clj` | 352 | JvmEveMap/Vec/Set/List deftypes (thin wrappers) |
| `deftype/registry.clj` | 96 | Type constructor registry |
| `alpha.clj` | 20 | JVM entry point |

---

## 2. The Two Protocol Layers

Eve has two key abstraction protocols, forming a stack:

```
┌─────────────────────────────────────────────────────┐
│  Data Structures (map, vec, set, list, array, obj)  │
│  serialize.cljc                                     │
├─────────────────────────────────────────────────────┤
│  ISlabIO  (alloc.cljc)                              │  ← slab-qualified offsets
│  -sio-read-u8, -sio-read-i32, -sio-alloc! ...      │
├─────────────────────────────────────────────────────┤
│  IMemRegion  (mem.cljc)                             │  ← raw byte offsets
│  -load-i32, -store-i32!, -cas-i32!, -read-bytes ... │
├─────────────────────────────────────────────────────┤
│  Platform Implementations                           │
│  CLJS: JsSabRegion, NodeMmapRegion                  │
│  CLJ:  JvmMmapRegion                                │
└─────────────────────────────────────────────────────┘
```

### IMemRegion (11 methods + 6 atomic + 3 futex)

Defined in `mem.cljc`, this is the low-level memory abstraction:

| Category | Methods |
|----------|---------|
| **Metadata** | `-byte-length` |
| **Atomic i32** | `-load-i32`, `-store-i32!`, `-cas-i32!`, `-add-i32!`, `-sub-i32!`, `-exchange-i32!` |
| **Atomic i64** | `-load-i64`, `-store-i64!`, `-cas-i64!`, `-add-i64!`, `-sub-i64!` |
| **Futex** | `-wait-i32!`, `-notify-i32!`, `-supports-watch?` |
| **Bulk I/O** | `-read-bytes`, `-write-bytes!` |

Three implementations exist:
- **JsSabRegion** (CLJS) — wraps `SharedArrayBuffer` + `DataView` + `Int32Array` + `Atomics`
- **NodeMmapRegion** (CLJS) — wraps mmap'd file → SharedArrayBuffer via native addon
- **JvmMmapRegion** (CLJ) — wraps `MemorySegment` (Panama FFI) + Unsafe atomics

### ISlabIO (12 methods)

Defined in `alloc.cljc`, this abstracts slab-level block I/O:

| Method | Purpose |
|--------|---------|
| `-sio-read-u8` | Read byte from slab-offset + field-off |
| `-sio-write-u8!` | Write byte |
| `-sio-read-u16` | Read u16 (little-endian) |
| `-sio-write-u16!` | Write u16 |
| `-sio-read-i32` | Read i32 |
| `-sio-write-i32!` | Write i32 |
| `-sio-read-bytes` | Read byte array |
| `-sio-write-bytes!` | Write byte array |
| `-sio-alloc!` | Allocate block of given size |
| `-sio-free!` | Free block at slab-offset |
| `-sio-copy-block!` | Bulk copy between slab blocks |

Two implementations:
- **CljsSlabIO** (CLJS) — delegates to module-level `read-u8`, `read-i32` etc. which use raw DataView
- **JvmSlabCtx** (CLJ) — wraps IMemRegion[] array, delegates to `mem/-read-bytes`, `mem/-load-i32` etc.

---

## 3. The DataView Bypass Problem

### The Pattern

The JVM path is clean: Data structures → ISlabIO → IMemRegion → MemorySegment.

The CLJS path has **two modes**:

1. **Through ISlabIO** (used by data structure `.cljc` files): map/vec/set/list call `-sio-read-i32` etc. → CljsSlabIO delegates to module-level `alloc/read-i32` → gets DataView from `wasm/slab-data-view` → raw `.getInt32`

2. **Bypassing ISlabIO** (used by CLJS-only code in `.cljc` files): Some code in `#?(:cljs ...)` blocks calls `alloc/read-i32` etc. directly, and `serialize.cljc` uses raw DataView methods directly.

### Call Census

How data structure `.cljc` files access memory (call counts):

| File | ISlabIO calls (`-sio-*`) | Module-level (`alloc/read-*`) | Raw DataView (`.getInt32` etc.) |
|------|--------------------------|-------------------------------|--------------------------------|
| `map.cljc` | **165** | 19 | 9 |
| `set.cljc` | **97** | 10 | 8 |
| `vec.cljc` | **61** | 0 | 7 |
| `list.cljc` | **16** | 1 | 18 |
| `obj.cljc` | **12** | 4 | 10 |
| `array.cljc` | **3** | 6 | 4 |
| `alloc.cljc` | **67** (internal) | N/A | 5 |
| `serialize.cljc` | 0 | 0 | **44** |

**Key observation:** The data structure files already *predominantly* use ISlabIO (429 calls). The remaining ~145 direct/module-level/DataView calls are almost entirely in `#?(:cljs ...)` blocks — platform-specific write paths and serialization.

### Where The Bypasses Happen

1. **CLJS write paths** in `.cljc` files: When a CLJS block allocates new nodes (e.g., HAMT path-copying during `assoc`), it calls `alloc/read-i32` and `alloc/write-i32!` directly instead of going through ISlabIO. This is because the CLJS write path was written before ISlabIO existed.

2. **serialize.cljc** CLJS block: All 44 raw DataView calls are in the CLJS serialization path. Functions like `serialize-element` and `deserialize-element` operate on raw DataView, not ISlabIO.

3. **list.cljc** CLJS block: The pool-based allocation system uses raw DataView for node construction.

### What IMemRegion Is Missing (For Full Unification)

**Nothing.** IMemRegion already has all needed methods. The issue is that CLJS code doesn't use it — it uses DataView directly for performance. But ISlabIO exists precisely to abstract this away, and the data structures already mostly use ISlabIO.

---

## 4. Per-File Analysis

### Tier 1: Already Pure or Nearly Pure cljc (minimal work)

#### `coalesc.cljc` (479 lines) — **100% shared, 0 #? conditionals**
- Pure algorithmic coalescing allocator
- Works entirely through IMemRegion
- **No changes needed**

#### `data.cljc` (464 lines) — **95% shared, 7 #? conditionals**
- All slab constants and layout definitions
- Only split: `#?(:cljs (.-length arr) :clj (count arr))` in 2 places
- **Fix:** Add `(defn- arr-len [a] #?(:cljs (.-length a) :clj (count a)))` helper. That's it.

#### `hamt_util.cljc` (107 lines) — **75% shared**
- Portable hash function with inline `#?` for bitwise ops
- **No changes needed** — the `#?` conditionals are unavoidable (JS vs JVM math)

### Tier 2: Data Structures — High Value, Medium Effort

These files follow the same pattern: shared HAMT/tree logic with `#?` blocks for platform-specific write paths and deftype wrappers.

#### `map.cljc` (3,514 lines) — Currently 85% → Target **95%**

**Currently platform-specific:**
- Lines 5-28: Namespace requires (different imports)
- Lines 37-53: Forward declares (CLJS has write fn declares)
- ~200 lines: CLJS write path (assoc, dissoc) using `alloc/read-*` module functions
- ~200 lines: CLJ write path (`jvm-hamt-assoc!` at line ~2916, `jvm-hamt-dissoc!` etc.) — full path-copy via ISlabIO

**Important:** Both platforms already have **full write support** through ISlabIO. The CLJ side implements `jvm-hamt-assoc!`, `jvm-hamt-dissoc!`, etc. with path-copy allocation. The CLJS side has equivalent functions but uses module-level `alloc/read-*` instead of ISlabIO. This means the write *algorithms* are already duplicated — unification would collapse two parallel implementations into one.

**What to share:**
The CLJS write path (HAMT node creation, path-copying) calls `alloc/read-i32`, `alloc/write-i32!` etc. These could call through ISlabIO instead. The write logic is pure HAMT algorithm — hash partitioning, node splitting, collision handling — with memory access as the only platform-specific part.

**Conversion plan:**
1. Replace `alloc/read-i32` calls in CLJS write path with `-sio-read-i32` calls (passing `sio` parameter)
2. Replace `alloc/write-i32!` calls similarly
3. Move write functions out of `#?(:cljs ...)` blocks
4. Add `sio` parameter to write functions that currently use module-level state
5. Keep only ns declaration and deftype constructor in `#?` blocks

**Estimated delta:** ~400 lines collapse (two parallel write paths → one shared)

#### `set.cljc` (2,110 lines) — Currently 75% → Target **92%**
Same structure as map. Both CLJ and CLJS have full write paths via ISlabIO / module-level functions.
**Estimated delta:** ~250 lines shared

#### `vec.cljc` (1,553 lines) — Currently 70% → Target **90%**
Persistent vector tree. Both platforms have write ops; CLJS uses `alloc/read-*` directly.
**Estimated delta:** ~150 lines shared

#### `list.cljc` (1,208 lines) — Currently 60% → Target **85%**
Cons-cell list with pool management. Pool is CLJS-specific (module-level mutable state), but cons/first/rest logic could be shared.
**Estimated delta:** ~100 lines shared

#### `obj.cljc` (662 lines) — Currently 70% → Target **88%**
Object/struct type with SoA (struct-of-arrays) support. Schema definitions are shared; field read/write could go through ISlabIO.
**Estimated delta:** ~50 lines shared

#### `array.cljc` (1,039 lines) — Currently 65% → Target **80%**
Typed arrays. Type metadata is shared; element access uses DataView for CLJS and IMemRegion for CLJ. SIMD operations are inherently CLJS-only.
**Estimated delta:** ~80 lines shared

### Tier 3: Allocator & Serializer — High Impact

#### `alloc.cljc` (1,699 lines) — Currently 55% → Target **75%**

This is the most impactful file. Currently:
- Lines 1-119: Shared (offset encoding + ISlabIO protocol definition)
- Lines 120-497: **CLJ-only** (JvmSlabCtx + helpers)
- Lines 498-825: Shared (higher-level alloc functions using ISlabIO)
- Lines 826-1699: **CLJS-only** (module-level state + CljsSlabIO + lifecycle)

**What cannot be shared:**
- CljsSlabIO implementation (delegates to WASM-backed DataView module state)
- JvmSlabCtx implementation (delegates to IMemRegion array)
- Slab lifecycle (init-slab!, open-mmap-slab!, etc.) — inherently platform-specific
- Root pointer ops, epoch management, worker registry (use module-level IMemRegion state)

**What could be shared (but currently isn't):**
- Bitmap allocation algorithm: The CAS-loop-over-bitmap logic in both CljsSlabIO and JvmSlabCtx is nearly identical. If both used IMemRegion, a single `bitmap-alloc!` function could serve both.
- Growth logic: `grow-mmap-slab!` (CLJS) and the growth logic in JvmSlabCtx's `-sio-alloc!` follow the same CAS-leader-election pattern.

**Estimated delta:** ~200 lines of bitmap alloc + growth logic could be shared

#### `serialize.cljc` (1,249 lines) — Currently 85% → Target **95%**

**Currently shared:** Tag constants, tag tables, fast-path dispatch tables (~1,060 lines)

**CLJS-only (~150 lines):** `serialize-element`, `deserialize-element` using DataView

**CLJ-only (~40 lines):** References to `mem/` functions

**Conversion plan:**
The serialization functions need to read/write: u8 (tags), i32 (pointers/ints), f64 (doubles), bytes (strings/keywords). ISlabIO provides all of these except f64. Options:

1. **Add `-sio-read-f64` / `-sio-write-f64!` to ISlabIO** — then serialize can go through ISlabIO
2. **Use `-sio-read-bytes` + manual conversion** — works but slower
3. **Accept the `#?`** — keep 2 tiny serializer implementations that call the right platform API

Recommendation: Option 1. Add f64 to ISlabIO (trivial: 4 lines each in CljsSlabIO and JvmSlabCtx).

**String serialization** is the other gap:
- CLJS uses `js/TextEncoder` / `js/TextDecoder`
- CLJ uses `(.getBytes s "UTF-8")` / `(String. bytes "UTF-8")`

This should stay as a small `#?` conditional — it's 4 lines per platform and not worth a protocol method.

### Tier 4: CLJS-Only Files — Partial Conversion

#### `xray.cljs` (639 lines) → **Can be .cljc** (Medium effort)
Debug/inspection tool. Currently reads slab memory via raw DataView. If converted to use ISlabIO, the tree-walking logic is 100% platform-neutral.

#### `chunked_kv_seq.cljs` (317 lines) → **Partially .cljc** (Medium effort)
Implements `IChunkedSeq` / `IChunkedNext` (CLJS protocols). The chunking *logic* is portable, but the protocol interfaces differ:
- CLJS: `ISeq`, `IChunkedSeq`, `IChunkedNext`, `IChunk`
- CLJ: `clojure.lang.ISeq`, `clojure.lang.IChunkedSeq`

Could use `#?` for protocol names with shared method bodies.

#### `runtime.cljs` (300 lines) + `registry.clj` (96 lines) → **Merge to .cljc**
Both implement the same pattern: registry atom, `register-eve-type!`, `slab->eve`. The only difference is constructor argument types (DataView vs ISlabIO). With ISlabIO as the universal parameter, these merge cleanly.

#### `data.cljs` (248 lines) → **Mostly .cljc** (Easy)
Constants and helper data. Trivial `#?` for JS array literals.

### Tier 5: Inherently Platform-Specific (Cannot Convert)

| File | Lines | Why |
|------|-------|-----|
| `shared_atom.cljs` | 3,695 | PROTECTED; SAB-specific worker thread coordination |
| `simd_hamt.cljs` | 674 | WASM SIMD instructions for HAMT acceleration |
| `wasm.cljs` | 659 | WebAssembly memory management |
| `simd_wasm.cljs` | 480 | WASM module loader for SIMD |
| `simd.cljs` | 420 | SIMD primitive operations |
| `wasm_mem.cljs` | 668 | WASM memory page management |
| `int_map.cljs` | 918 | SAB-only (uses `eve.deftype` macros + `shared-atom`) |
| `rb_tree.cljs` | 619 | SAB-only (uses `eve.deftype` macros + `shared-atom`) |
| `deftype.cljs` | 2 | Macro self-require |
| `deftype.clj` | 352 | JVM deftype wrappers (protocol names differ) |
| `deftype_proto.clj` | 473 | JVM slab lifecycle helpers |
| `alpha.cljs` / `alpha.clj` | 55 | Platform entry points |

Total inherently platform-specific: ~8,893 lines

---

## 5. Proposed Architecture

### Goal: Everything Above ISlabIO Is Pure .cljc

```
┌──────────────────────────────────────────────────────────────┐
│                     PURE .cljc LAYER                         │
│                                                              │
│  map.cljc ─── HAMT assoc/dissoc/lookup/seq                  │
│  vec.cljc ─── RRB tree conj/pop/nth/seq                     │
│  set.cljc ─── HAMT conj/disj/contains/seq                   │
│  list.cljc ── cons/first/rest/seq                            │
│  array.cljc ─ typed array read/write                         │
│  obj.cljc ─── struct field read/write                        │
│  serialize.cljc ── serialize/deserialize elements            │
│  coalesc.cljc ──── coalescing allocator (already pure!)      │
│  hamt_util.cljc ── portable hash (already pure!)             │
│  xray.cljc ──────── debug inspection (new!)                  │
│  registry.cljc ──── type registry (merged!)                  │
│                                                              │
│  All functions take `sio` (ISlabIO) as first argument        │
│  No DataView, no MemorySegment, no js/, no Java imports      │
├──────────────────────────────────────────────────────────────┤
│                     ISlabIO PROTOCOL                         │
│  -sio-read-u8, -sio-write-u8!, -sio-read-i32, ...          │
│  -sio-read-f64, -sio-write-f64!  (NEW)                      │
│  -sio-alloc!, -sio-free!, -sio-copy-block!                  │
│  -sio-encode-string, -sio-decode-string  (NEW)              │
├──────────────────────────────────────────────────────────────┤
│              PLATFORM IMPLEMENTATIONS                        │
│                                                              │
│  CLJS: CljsSlabIO                    CLJ: JvmSlabCtx        │
│    → WASM DataView + Atomics           → IMemRegion[]        │
│    → TextEncoder/TextDecoder           → String.getBytes     │
│    → Module-level slab state           → Panama MemSegment   │
│                                                              │
│  deftype.cljs (CLJS protocols)   deftype.clj (CLJ protocols)│
│  alpha.cljs (CLJS entry)         alpha.clj (CLJ entry)      │
│  shared_atom.cljs (SAB-only)     deftype_proto.clj (JVM)    │
│  SIMD/WASM files (CLJS-only)                                │
├──────────────────────────────────────────────────────────────┤
│                     IMemRegion PROTOCOL                      │
│  -load-i32, -store-i32!, -cas-i32!, -read-bytes, ...       │
├──────────────────────────────────────────────────────────────┤
│  JsSabRegion  │  NodeMmapRegion  │  JvmMmapRegion           │
└───────────────┴──────────────────┴───────────────────────────┘
```

### Required ISlabIO Extensions

Two small additions to ISlabIO:

```clojure
;; Add to ISlabIO protocol:
(-sio-read-f64 [ctx slab-offset field-off]
  "Read a little-endian f64 from slab-offset+field-off.")

(-sio-write-f64! [ctx slab-offset field-off val]
  "Write a little-endian f64 to slab-offset+field-off.")
```

**CljsSlabIO implementation:**
```clojure
(-sio-read-f64 [_ slab-offset field-off]
  (let [class-idx (decode-class-idx slab-offset)
        dv        (wasm/slab-data-view class-idx)
        base      (slab-offset->byte-offset slab-offset)]
    (.getFloat64 dv (+ base field-off) true)))
```

**JvmSlabCtx implementation:**
```clojure
(-sio-read-f64 [_ slab-offset field-off]
  (let [class-idx (decode-class-idx slab-offset)
        block-idx (decode-block-idx slab-offset)
        base      (+ (aget data-offsets class-idx)
                     (* block-idx (aget block-sizes class-idx)))
        region    (aget regions class-idx)
        b         (mem/-read-bytes region (+ base field-off) 8)]
    ;; Decode little-endian f64 from byte array
    (let [bb (java.nio.ByteBuffer/wrap b)]
      (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)
      (.getDouble bb))))
```

### String Handling

String serialization remains a small `#?` conditional in `serialize.cljc`:

```clojure
(defn- encode-utf8 [^String s]
  #?(:cljs (let [enc (js/TextEncoder.)] (.encode enc s))
     :clj  (.getBytes s "UTF-8")))

(defn- decode-utf8 [bytes]
  #?(:cljs (let [dec (js/TextDecoder.)] (.decode dec bytes))
     :clj  (String. ^bytes bytes "UTF-8")))
```

This is acceptable — 8 lines of `#?` is far better than duplicating entire serialize/deserialize functions.

---

## 6. Migration Strategy

### Phase 1: Foundation (Low Risk, High Leverage)

**Step 1.1: Add `-sio-read-f64` / `-sio-write-f64!` to ISlabIO**
- Add protocol methods to `alloc.cljc`
- Implement in CljsSlabIO and JvmSlabCtx
- ~20 lines of new code total

**Step 1.2: Add UTF-8 helpers to `serialize.cljc`**
- `encode-utf8` / `decode-utf8` as `#?` functions
- Replace scattered TextEncoder/String.getBytes calls
- ~10 lines

**Step 1.3: Convert `serialize.cljc` CLJS block to use ISlabIO**
- Replace 44 raw DataView calls with ISlabIO calls
- Functions gain `sio` parameter
- Move out of `#?(:cljs ...)` block
- **Test:** All suites pass

### Phase 2: Data Structure Write Paths (Medium Risk)

**Step 2.1: `map.cljc` write path**
- Replace `alloc/read-i32`, `alloc/write-i32!` in CLJS write functions with ISlabIO calls
- Add `sio` parameter to `eve-map-assoc`, `eve-map-dissoc`, etc.
- Move these functions out of `#?(:cljs ...)` blocks
- Remove duplicate CLJ read-only versions where they now work cross-platform
- **Test:** `obj`, `slab`, `mmap-atom`, `mmap-atom-e2e` suites

**Step 2.2: `set.cljc` write path** — Same pattern as map

**Step 2.3: `vec.cljc` write path** — Same pattern

**Step 2.4: `list.cljc` write path** — Pool management stays CLJS; cons/first/rest shared

**Step 2.5: `array.cljc` and `obj.cljc`** — Convert remaining DataView calls

### Phase 3: File Conversions (Medium Risk)

**Step 3.1: Merge `runtime.cljs` + `registry.clj` → `registry.cljc`**

**Step 3.2: Convert `xray.cljs` → `xray.cljc`**
- Replace DataView reads with ISlabIO calls
- Add `sio` parameter to xray functions

**Step 3.3: Convert `data.cljs` to `.cljc` portions**
- Move constants to `data.cljc`
- Keep JS-specific bits in minimal `#?`

### Phase 4: Shared Bitmap Allocator (High Value, Higher Risk)

**Step 4.1: Extract shared bitmap allocation logic**
- Both CljsSlabIO and JvmSlabCtx implement nearly identical CAS-loop-over-bitmap allocation
- Extract to a shared function: `(defn bitmap-alloc-loop! [mem-region bitmap-offset total-blocks] ...)`
- Uses IMemRegion's `-cas-i32!` and `-load-i32`

**Step 4.2: Extract shared slab growth logic**
- CAS-leader-election pattern is identical on both platforms
- Extract to: `(defn grow-slab-cas! [peek-region total-blocks-offset current-total] ...)`

---

## 7. Performance Considerations

### The Performance Concern

The CLJS code bypasses IMemRegion for a reason: protocol dispatch overhead on every memory access. In a HAMT lookup, a single `get` may do 5-7 memory reads (one per trie level). In a tree traversal (`reduce`, `seq`), this multiplies by the collection size.

### Why ISlabIO Is Different From IMemRegion

ISlabIO is **already used** on the hot path (429 calls across data structure files). The CLJS CljsSlabIO implementation is a zero-overhead deftype that delegates to inlined module-level functions. The protocol dispatch cost of ISlabIO is already being paid.

The remaining 145 `alloc/read-*` and raw DataView calls in `#?(:cljs ...)` blocks would add ISlabIO dispatch where there currently is none. However:

1. **These are write-path calls**, not read-path. Writes are already much heavier (allocation, CAS, bitmap scanning), so one extra dispatch per write is negligible.

2. **Serialization calls** (44 in serialize.cljc) are on the serialize/deserialize path, which is already expensive (tag parsing, string encoding, collection traversal). Extra dispatch is noise.

3. **JVM already pays this cost** — and JVM Eve performs adequately for cross-process coordination.

### Mitigation Strategies

If profiling reveals performance regression:

1. **`^:redef false` on ISlabIO methods** — enables JIT inlining in V8
2. **Direct function calls in CLJS** — CljsSlabIO could be special-cased to call module functions directly while maintaining the protocol interface for JVM
3. **Monomorphic call sites** — if only CljsSlabIO is ever used in CLJS (which is true), V8 will monomorphize the protocol dispatch to a direct call after warmup

---

## Appendix: Raw DataView Call Census

### In `.cljc` files (inside `#?(:cljs ...)` blocks)

| File | `.getInt32` | `.setInt32` | `.getUint8` | `.setUint8` | `.getFloat64` | `.setFloat64` | Total |
|------|------------|------------|------------|------------|--------------|--------------|-------|
| serialize.cljc | 12 | 10 | 8 | 8 | 3 | 3 | **44** |
| list.cljc | 6 | 5 | 4 | 3 | 0 | 0 | **18** |
| obj.cljc | 3 | 3 | 2 | 1 | 1 | 0 | **10** |
| map.cljc | 3 | 3 | 2 | 1 | 0 | 0 | **9** |
| set.cljc | 3 | 2 | 2 | 1 | 0 | 0 | **8** |
| vec.cljc | 3 | 2 | 1 | 1 | 0 | 0 | **7** |
| alloc.cljc | 2 | 1 | 1 | 1 | 0 | 0 | **5** |
| array.cljc | 1 | 1 | 1 | 1 | 0 | 0 | **4** |
| **Total** | **33** | **27** | **21** | **17** | **4** | **3** | **105** |

### In `.cljs`-only files

| File | DataView calls | ISlabIO calls | `alloc/*` calls | Notes |
|------|---------------|---------------|-----------------|-------|
| `shared_atom.cljs` | 7 | 0 | 0 | PROTECTED; SAB-only |
| `runtime.cljs` | 19 | 0 | 0 | Type constructors use DataView |
| `int_map.cljs` | 4 | 0 | 0 | Uses `eve.deftype` macros |
| `chunked_kv_seq.cljs` | 3 | 0 | 0 | Reads from DataView |
| `xray.cljs` | ~30 | 0 | 0 | Debug reads from DataView |

### Summary

- **105 raw DataView calls in `.cljc` files** → All convertible to ISlabIO
- **~63 raw DataView calls in convertible `.cljs` files** → Convertible with ISlabIO parameter
- **~7 raw DataView calls in non-convertible files** → Stay as-is (shared_atom.cljs)

**Converting 105 DataView calls in `.cljc` files to ISlabIO would eliminate ~90% of all `#?(:cljs ...)` blocks in the data structure and serializer layers.**

---

## Summary: Impact Projection

| Metric | Before | After | Delta |
|--------|--------|-------|-------|
| .cljc shared code % | ~74% | ~90-92% | +16-18% |
| `#?` conditionals in data structures | ~30 | ~6 | -24 |
| Raw DataView calls in .cljc | 105 | 0 | -105 |
| Lines in pure shared .cljc | ~12,100 | ~15,200 | +3,100 |
| Files convertible to .cljc | 0 | 3-4 | +3-4 |
| New protocol methods needed | 0 | 2 | +2 (f64 read/write) |
| New shared helper functions | 0 | 2 | +2 (UTF-8 encode/decode) |

The fundamental insight: **ISlabIO is already 90% of the way there.** The remaining 10% is converting the CLJS write paths and serializer from direct DataView access to ISlabIO calls. This is mechanical, low-risk, and testable at each step.
