# Dead Code Removal Plan

Comprehensive plan for removing ~160 dead code items (~3,100 lines) identified
through automated analysis of every public function/var/constant in the Eve
codebase. Each item was verified to have zero external callers via exhaustive
grep of `src/` and `test/`.

**Methodology:** Five parallel research agents scanned every `.cljc`, `.cljs`,
and `.clj` file in `src/eve/`. For each public def, all references across
the entire codebase (src + test) were counted. Items with zero external
references were flagged, then categorized by risk level.

**Safety rule:** After each phase, recompile and run the full green baseline
(`node target/eve-test/all.js all` + `clojure -M:jvm-test`). Do not proceed
to the next phase until all tests pass.

---

## Phase 1: Delete Entirely Dead Modules

Two CLJS modules are never `require`d by any file in the codebase.

### 1.1 Delete `src/eve/deftype_proto/simd_hamt.cljs` (~675 lines)

- Contains: `init!`, `init-with-memory!`, `create-wasm-memory`, `ready?`,
  `hamt-find-wasm`, `hamt-find-hash-simd`, `popcount32`, `bytes-equal?`,
  `copy-key-to-scratch!`, `lookup-with-wasm`, `lookup-with-wasm-simd`,
  `fnv1a-hash`, plus constants
- Evidence: Zero `require` statements reference `eve.deftype-proto.simd-hamt`
  anywhere in src/ or test/
- Risk: **None** — entirely unreachable code

### 1.2 Delete `src/eve/deftype_proto/simd_wasm.cljs` (~481 lines)

- Contains: `init-simd!`, `simd-available?`, `find-matching-lengths`,
  `simd-bytes-equal-16`, `simd-memcmp`, `simd-bytes-equal?`,
  `batch-find-key-simd`, plus WAT/binary data
- Evidence: Zero `require` statements reference `eve.deftype-proto.simd-wasm`
  anywhere in src/ or test/
- Risk: **None** — entirely unreachable code

---

## Phase 2: Remove Dead Functions from `map.cljc`

### 2.1 Remove unused `-pub` wrappers (lines 1322–1365)

These are leftover API stubs from an earlier design phase. Zero callers.

| Function | Line |
|---|---|
| `hamt-assoc-pub` | 1322 |
| `alloc-bytes-pub` | 1327 |
| `hamt-dissoc-pub` | 1332 |
| `direct-assoc-pub` | 1337 |
| `direct-assoc-with-khs-pub` | 1351 |

### 2.2 Remove dead `hamt-graft` subsystem (lines 1257–1316)

| Function | Line |
|---|---|
| `hamt-graft` | 1257 |
| `hamt-graft-added?` | 1316 |

`hamt-graft` is only self-recursive with zero external entry points.

### 2.3 Remove `free-cas-abandoned!` (line 953)

Zero callers anywhere in the codebase.

### 2.4 Remove `preduce` and supporting functions (lines ~2090–2130)

| Function | Line |
|---|---|
| `MIN_PARALLEL_ENTRIES` | ~2090 |
| `get-top-level-subtrees` | ~2095 |
| `partition-offsets` | ~2105 |
| `preduce` | 2112 |

Unused parallel reduce implementation. Zero callers.

### 2.5 Remove `validate-eve-hash-map` (line 2319)

Zero callers. Note: `validate-hamt-tree` (which it calls) IS used by
`validate-from-header-offset` and must be kept.

### 2.6 Remove debug toggles and pool tracking (lines 125–156)

| Function | Line | Notes |
|---|---|---|
| `enable-pool-debug!` | 125 | Zero callers |
| `disable-pool-debug!` | 126 | Zero callers |
| `enable-pool!` | 127 | Zero callers |
| `disable-pool!` | 128 | Zero callers |
| `enable-pool-track!` | 131 | Deprecated, zero callers |
| `disable-pool-track!` | 134 | Deprecated, zero callers |
| `pool-report` | 137 | Zero callers |

Also remove the debug vars they toggle:

| Var | Line |
|---|---|
| `pool-debug?` | 14 |
| `pool-disabled?` | 15 |
| `alloc-debug-set` | 17 |

And strip the dead `when pool-debug?` branches in `pool-ensure!`,
`pool-get!`, `pool-return!`, and `alloc-node-buffer` (these compile away
since `pool-debug?` is always false, but removing them reduces code noise).

---

## Phase 3: Remove Dead Functions from `shared_atom.cljs`

### 3.1 Remove dead compaction subsystem

| Function | Line | Notes |
|---|---|---|
| `set-compaction-temperature!` | 2108 | Unfinished feature |
| `set-on-block-moved-fn!` | 2117 | Unfinished feature |
| `compact-one-block!` | 2227 | Unfinished feature |
| `maybe-compact!` | 2339 | Unfinished feature |

### 3.2 Remove dead cross-worker stubs

| Function | Line | Notes |
|---|---|---|
| `set-broadcast-swap-fn!` | 2545 | Never wired up |
| `check-remote-watches!` | 2578 | Never wired up |
| `eve->cljs` | 2436 | Zero callers |
| `conveyable->` | 3583 | Zero callers |
| `<-conveyable` | 3599 | Zero callers |

### 3.3 Remove deprecated aliases

| Function | Line |
|---|---|
| `default-serializer` | 2390 |
| `default-deserializer` | 2391 |

### 3.4 Remove other dead functions

| Function | Line | Notes |
|---|---|---|
| `batch-alloc` | 301 | Zero callers (distinct from `batch-alloc-js` which is a WASM fallback — keep that) |
| `xray-replay!` | 1028 | Zero callers |
| `register-slab-xray-validator!` | 1082 | Zero callers |
| `get-eve-map-header-offset` | 1129 | Zero callers |
| `reset-root-pool!` | 1310 | Zero callers |
| `mem-window` | 3624 | Zero callers |
| `_xray-find-active-region` | 755 | Underscore-prefixed, intentionally unused |

### 3.5 Remove commented-out code

| Location | Line | Notes |
|---|---|---|
| `#_end-read!` (old version) | 1410 | Replaced by current version at line 1359 |

---

## Phase 4: Remove Dead Functions from `wasm_mem.cljs`

~25 dead functions. These are speculative WASM acceleration paths that were
never integrated into the main code paths.

### 4.1 Remove dead WASM HAMT functions

| Function | Line |
|---|---|
| `hamt-find` | 358 |
| `hamt-collect-kv` | 387 |
| `hamt-reduce-sum` | 402 |
| `vec-collect-values` | 412 |
| `deser-tag-info` | 421 |
| `calc-kv-total-size` | 590 |
| `build-node-replace-child!` | 598 |
| `build-node-replace-kv!` | 609 |
| `build-node-add-kv!` | 621 |
| `build-node-remove-kv-add-child!` | 633 |

### 4.2 Remove dead WASM SIMD utilities

| Function | Line |
|---|---|
| `v128-bytes-eq-16?` | 335 |
| `v128-find-u32` | 341 |
| `v128-i32x4-eq-mask` | 348 |
| `gather-u32!` | 434 |
| `scatter-u32!` | 440 |
| `prefix-sum-u32!` | 446 |

### 4.3 Remove dead memory/view helpers

| Function | Line |
|---|---|
| `create-memory` | 110 |
| `get-memory` | 126 |
| `get-buffer` | 132 |
| `i32-view` | 148 |
| `f64-view` | 153 |
| `init-views-from-sab!` | 175 |
| `popcount32` | 265 |
| `bytes-equal?` | 279 |
| `memset!` | 301 |
| `fnv1a-hash` | 309 |
| `bitmap-index` | 324 |

### 4.4 Remove dead batch/scratch functions

| Function | Line |
|---|---|
| `find-free-descriptors-batch` | 541 |
| `find-free-descriptors-batch-simd` | 565 |
| `scratch-offset` | 650 |
| `copy-to-scratch!` | 659 |

### 4.5 Remove dead constants

| Constant | Line |
|---|---|
| `MAX_COLLECT_ENTRIES` | 385 |
| `SCRATCH_SIZE` | 83 |
| `MAX_WORKERS` | 84 |
| `NODE_TYPE_BITMAP_HASHED` | 89 |

---

## Phase 5: Remove Dead Functions from `simd.cljs`

| Function | Line | Notes |
|---|---|---|
| `wasm-available?` | 280 | Zero callers |
| `popcount32` | 284 | Zero callers |
| `memcmp` | 291 | Zero callers |
| `sab-bytes-equal?` | 307 | Zero callers |
| `sab-memcmp` | 330 | Zero callers |
| `batch-popcount32` | 357 | Zero callers |
| `batch-hash-lookup` | 389 | Zero callers |
| `SIMD_ALIGN` | 410 | Zero callers |
| `align-up` | 412 | Zero callers |
| `is-aligned?` | 418 | Zero callers |

Also remove private helpers only called by the above dead functions:
`_wasm-popcount32`, `_wasm-bytes-equal?`, `wasm-memcmp`.

---

## Phase 6: Remove Dead Functions from `util.cljs`

### 6.1 Remove dead bigint atomics (lines 97–101)

| Function | Line |
|---|---|
| `atomic-load-bigint` | 97 |
| `atomic-store-bigint` | 98 |
| `atomic-add-bigint` | 99 |
| `atomic-sub-bigint` | 100 |
| `atomic-compare-exchange-bigint` | 101 |

### 6.2 Remove dead utility functions

| Function | Line | Notes |
|---|---|---|
| `atomic-compare-and-swap` | 64 | Zero callers |
| `cas` | 79 | Zero callers |
| `typed-array?` | 42 | Zero callers |
| `log?` / `log` | 31/34 | Debug logging, never enabled |
| `raw-worker-data` | ~8 | Never read |
| `raw-parent-port` | ~10 | Never read |
| `get-index-region-size` | 158 | Zero callers |
| `find-descriptor-for-data-offset` | 205 | Zero callers |
| `-equiv-sequential` | 299 | Zero callers |
| `is-node?` | 327 | Zero callers |
| `TE` / `TD` | 354/355 | Zero callers |
| `string->uint8array` | 357 | Zero callers |
| `uint8array->string` | 360 | Zero callers |

### 6.3 Remove commented-out code

| Location | Line | Notes |
|---|---|---|
| `#_get-reader-map-idx` (old version) | 343 | Replaced by current version at line 329 |

---

## Phase 7: Remove Dead Functions from `array.cljc`

### 7.1 Remove deprecated aliases

| Function | Line |
|---|---|
| `int32-array` | ~361 |
| `int32-array-from` | ~369 |

### 7.2 Remove dead SIMD wrappers

| Function | Line |
|---|---|
| `afill-simd!` | 693 |
| `acopy-simd!` | 707 |
| `asum-simd` | 721 |
| `amin-simd` | 735 |
| `amax-simd` | 750 |
| `aequal-simd?` | 765 |

### 7.3 Remove dead utility/accessor functions

| Function | Line | Notes |
|---|---|---|
| `get-int32-view` | 651 | Zero callers |
| `get-sab` | 633 | Zero callers |
| `get-offset` | 638 | Zero callers |
| `get-descriptor-idx` | 657 | Zero callers |
| `array-type` | 662 | Zero callers |
| `retire!` | 671 | Zero callers |
| `from-typed-array` | 783 | Zero callers |
| `afill!` | 600 | Zero callers |
| `acopy!` | 616 | Zero callers |

### 7.4 Remove dead wait/notify/bitwise ops

**Policy decision needed:** These are documented public API (`wait!`, `notify!`,
`band!`, `bor!`, `bxor!`) with zero internal callers. They exist for external
consumers. Options:

- **Option A:** Keep as public API (they compile to trivial wrappers)
- **Option B:** Remove now, re-add if/when a consumer needs them

| Function | Line |
|---|---|
| `wait!` | 495 |
| `wait-async` | 515 |
| `notify!` | 544 |
| `band!` | 455 |
| `bor!` | 467 |
| `bxor!` | 479 |

---

## Phase 8: Remove Dead Code from `alloc.cljc`

### 8.1 Remove dead worker-side SAB subsystem (~80 lines)

Connected dead subgraph — pre-mmap SAB worker-sharing path, superseded by
the mmap cross-process architecture.

| Function | Line |
|---|---|
| `init-worker-slabs!` | 1032 |
| `get-root-sab` | 1004 |
| `get-all-slab-sabs` | 1013 |

Note: `init-root-sab-from-existing!` (994) and
`populate-slab-caches-from-header!` (1021) are only called by
`init-worker-slabs!`, so they die with it.

### 8.2 Remove dead allocator helpers

| Function | Line | Notes |
|---|---|---|
| `read-root-ptr` | 1406 | Superseded by inline reads in atom.cljc |
| `cas-root-ptr!` | 1411 | Superseded by inline CAS in atom.cljc |
| `get-slab-data-offset` | 853 | Debug helper, zero callers |
| `reset-all-slabs!` | 954 | "Nuclear reset" never called |
| `reset-mmap-coalesc!` | 1692 | Cleanup function never called |

### 8.3 Remove dead debug functions

| Function | Line |
|---|---|
| `slab-stats` | ~450 |
| `dump-slab-header` | ~470 |

---

## Phase 9: Remove Dead Code from `serialize.cljc`

### 9.1 Remove flat serialization functions (forbidden by CLAUDE.md)

| Function | Line |
|---|---|
| `serialize-flat-collection` | 737 |
| `serialize-flat-element` | 789 |

Note: Keep `FAST_TAG_FLAT_MAP` and `FAST_TAG_FLAT_VEC` constants — they're
needed by the deserialization path to read existing data.

### 9.2 Remove dead utility functions

| Function | Line | Notes |
|---|---|---|
| `get-header-disposer` | 145 | Disposers registered but getter never called |
| `dispose-sab-value!` | 1242 | Zero callers |
| `clear-deser-caches!` | 269 | Zero callers |
| `register-record-type!` | 203 | Zero callers (investigate: may be intended for external use) |

---

## Phase 10: Remove Dead Code from `coalesc.cljc`

| Function | Line |
|---|---|
| `coalesc-stats` | ~280 |
| `dump-descriptors` | ~310 |

---

## Phase 11: Remove Dead Code from Remaining Files

### 11.1 `atom.cljc`

| Function | Line | Notes |
|---|---|---|
| `DEFAULT_ATOM_SLOT` | 55 | Constant never referenced |
| `join-atom-domain` | 870 | Redundant alias for `persistent-atom-domain` |
| `close!` | 890 | Public cleanup with zero callers |

### 11.2 `data.cljc` — dead constants and duplicate vars

| Item | Line | Notes |
|---|---|---|
| `SLAB_PTR_NIL` | 260 | `alloc/NIL_OFFSET` used instead |
| `SLAB_SIZE_0` through `SLAB_SIZE_5` | 74-79 | `SLAB_SIZES` lookup table used instead |
| `SLAB_MAX_BLOCK_SIZE` | 89 | Never referenced |
| `DEFAULT_SLAB_CAPACITY` | 95 | Unreachable fallback |
| `BITMAP_ALIGNMENT` | 190 | Only used internally in `bitmap-byte-size` |
| `slab-bitmap-path` | 226 | Callers inline `(str path ".bm")` |
| `slab-data-path` | 231 | Callers inline the path |
| `*persistent?*` | 400 | Duplicate of `eve.data/*persistent?*` |
| `*worker-slot-idx*` | 403 | Duplicate of `eve.data/*worker-slot-idx*` |
| `*read-epoch*` | 404 | Duplicate of `eve.data/*read-epoch*` |

### 11.3 `data.cljs` — dead constants

| Item | Line | Notes |
|---|---|---|
| `WASM_SCRATCH_SIZE` | 89 | Zero refs |
| `OFFSET_ATOM_ROOT_POINTER` | 119 | Superseded by `OFFSET_ATOM_ROOT_DATA_DESC_IDX` |
| `OFFSET_ALLOCATOR_GLOBAL_LOCK` | 123 | Zero refs |
| `TAG_JS_ARRAY` through `TAG_BIGUINT64_ARRAY` | 146-157 | 12 constants, zero refs |
| `TAG_REGEX`, `TAG_URI`, `TAG_CHAR`, `TAG_BIGINT`, `TAG_RECORD` | 159-163 | 5 constants, zero refs |
| `TAG_SABP_LINKED_LIST_STATE` through `TAG_SABP_SET_STATE` | 165-169 | 5 constants, zero refs |
| `OFFSET_LL_NODE_VALUE` | 214 | Zero refs |
| `LL_NODE_NEXT_OFFSET_SIZE_BYTES` | 215 | Zero refs |
| `register-sabp-cleanup!` / `get-sabp-cleanup-fn` | 222-225 | Zero refs |
| `*worker-slot-idx*` | 234 | Zero refs (the `data.cljs` one IS used, but check: may be same var) |
| `*read-epoch*` | 237 | Zero refs |
| `*use-flat-hashtable*` | 240 | Feature flag never activated |
| `*parallel-reduce*` | 241 | Feature flag never activated |
| `READER_MAP_TOTAL_SIZE_BYTES` | 244 | Duplicate of `READER_MAP_SAB_SIZE_BYTES` |
| `OFFSET_READER_MAP_START` | 246 | Zero refs |

### 11.4 `vec.cljc`

| Item | Line | Notes |
|---|---|---|
| `*chunk-size*` | 54 | Dynamic var never referenced |
| `into-eve-list-n` | 985 | Zero callers (in list.cljc, listed here for completeness) |

### 11.5 `list.cljc`

| Item | Line | Notes |
|---|---|---|
| `into-eve-list-n` | 985 | Zero callers |

### 11.6 `obj.cljc`

| Item | Line | Notes |
|---|---|---|
| `dump-schema` | ~95 | Debug function, zero callers |

---

## Phase 12: Privatize Unnecessarily-Exported Internals

These have internal callers but are exported without need. Make `^:private`:

| Item | File | Notes |
|---|---|---|
| `jvm-replaced-log-tl` | `alloc.cljc:144` | ThreadLocal, internal only |
| `jvm-sab-pointer-bytes` | `mem.cljc:1176` | Internal helper |
| `DESC_SIZE` through `DESC_RESERVED2` | `coalesc.cljc:46-56` | Internal constants |
| `STATUS_FREE`, `STATUS_ALLOCATED`, `STATUS_ZEROED_UNUSED` | `coalesc.cljc:59-61` | Internal constants |
| `LOCK_UNLOCKED`, `LOCK_SENTINEL` | `coalesc.cljc:64-65` | Internal constants |

---

## Phase 13: Verify and Update Documentation

After all removals:

1. Remove references to deleted functions from `doc/api-guide.md` (if any of
   the removed public API functions like `wait!`, `band!`, etc. are documented)
2. Remove references from `doc/data-structures.md`
3. Update `CLAUDE.md` namespace map if any files were deleted
4. Update `CONTRIBUTING.md` project structure if needed

---

## Fallback Paths — DO NOT REMOVE

These are intentional fallback/degradation paths:

| Item | File | Reason to keep |
|---|---|---|
| `batch-alloc-js` / `alloc-js` | `shared_atom.cljs` | WASM graceful degradation |
| JS fallback bitmap scanner | `shared_atom.cljs` | WASM SIMD fallback |
| `safe-max-descriptors` | `shared_atom.cljs` | Safety valve during init |
| `wait-i32` polling fallback | `mem.cljc` | Last-resort futex fallback |
| `validate-storage-model!` | `shared_atom.cljs` | Documented diagnostic tool |

---

## Execution Checklist

- [ ] Phase 1: Delete dead modules (simd_hamt.cljs, simd_wasm.cljs)
- [ ] Phase 2: Clean up map.cljc (~20 items)
- [ ] Phase 3: Clean up shared_atom.cljs (~15 items)
- [ ] Phase 4: Clean up wasm_mem.cljs (~25 items)
- [ ] Phase 5: Clean up simd.cljs (~10 items)
- [ ] Phase 6: Clean up util.cljs (~15 items)
- [ ] Phase 7: Clean up array.cljc (~21 items)
- [ ] Phase 8: Clean up alloc.cljc (~8 items)
- [ ] Phase 9: Clean up serialize.cljc (~6 items)
- [ ] Phase 10: Clean up coalesc.cljc (~2 items)
- [ ] Phase 11: Clean up remaining files (~20 items)
- [ ] Phase 12: Privatize internals (~10 items)
- [ ] Phase 13: Update documentation
- [ ] Final: Full green baseline verification
