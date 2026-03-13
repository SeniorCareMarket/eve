# Eve Codebase Analysis Report

> Generated: 2026-03-13

---

## 1. Where We've Been — Origin and Evolution

### Genesis (2026-03-10, commit `0e084ff`)

Eve started as a ClojureScript-only library providing SharedArrayBuffer-backed
persistent data structures for web workers. The initial commit delivered:

- **HAMT maps, vectors, sets, and lists** backed by SharedArrayBuffer
- **Slab allocator** with six size classes (32B–1024B) plus coalescing overflow
- **WASM-accelerated bitmap allocator** with SIMD optimizations
- **Epoch-based GC** for safe cross-worker memory reclamation
- **Typed shared objects** (`eve/obj`) with AoS/SoA layouts
- **Integer map** (PATRICIA trie) and **red-black tree** sorted set
- **X-RAY diagnostics** for slab allocator debugging
- A native C++ addon (`mmap_cas.cc`, 357 lines) providing file-backed
  `MAP_SHARED` mmap with atomic CAS/load/store/wait/notify via Linux futex

The CLJS implementation was already mature — full Clojure protocol coverage
(`ILookup`, `IAssociative`, `ISeqable`, `IReduce`, `IKVReduce`, `IFn`,
`IEditableCollection` transients, `IPrintWithWriter`).

### Documentation Sprint (2026-03-10, PRs #1–#4)

Over the first day, the project gained:
- Comprehensive docs (10 files in `doc/`): getting-started, API guide,
  architecture, internals, persistent atoms, collections, obj, testing,
  platform support
- README rewrite with real benchmark data (not hypothetical claims)
- Performance section with measured swap latency numbers
- Cross-process README example test (`test-e2e/readme_example_test.clj`)

### JVM Parity Push (2026-03-11, PRs #5–#9)

This was the largest engineering effort. The gap analysis (`doc/jvm-cljs-gap-analysis.md`)
identified 10 major gaps between CLJS and JVM implementations. A seven-phase
remediation was executed in a single day:

| Phase | Commit | What It Did |
|-------|--------|-------------|
| 0 | `ea907a6` | Extract shared HAMT utils (`hamt_util.cljc`), portable Murmur3 hash |
| 0.6 | `7ebf475` | Fix collision layout, `imul32` overflow, add cross-process tests |
| 1 | `cd7113c` | Hash-directed `O(log n)` set lookup on JVM |
| 2a | `f123957` | Native map dissoc + collision-aware assoc on JVM |
| 2b | `81df0b7` | Native vector conj/assocN/pop on JVM |
| 2c | `5a22a64` | Native set conj/disjoin on JVM |
| 3 | `91d636b` | Add `IFn` to all JVM collection types |
| 4 | `75365cb` | Add `IHashEq` + `print-method` to all JVM types |
| 5a | `682b92a` | Add `IReduceInit`/`IReduce` to all four JVM collections |
| 5b | `2d6dade` | Lazy seq for map, vec, set (eliminate ArrayList materialization) |
| 6 | `d899100` | Transients, List `ISeq`, Vec `java.util.List` |
| 7 | `8a73286` | Array/Obj CLJS→CLJC migration + JVM deftypes |

Post-parity optimizations:
- Eliminated gratuitous materialization in JVM Eve types (commit `7451153`)
- Bulk-copy-and-patch HAMT path-copy, lockless refresh, inline retire (`06d9597`)
- Eliminated ByteBuffer alloc from JVM `value->eve-bytes` hot path (`cecc140`)

### Benchmarking and Profiling (2026-03-11)

- Added `eve.perf` profiling tools with instrumented swap bench
- Created comprehensive data-transformation benchmarks (CLJ atoms vs Eve atoms)
- Per-op timing instrumentation, target-mb CLI arg for scalable dataset sizing
- Updated README with fresh 10MB/100MB/1GB stress test numbers:
  - JVM swap p50: ~1.5–3.5 ms (flat across 11 MB → 1.1 GB)
  - Node swap p50: ~0.16–0.34 ms
  - Cross-process contention: 924 ops/s at 11 MB, 546 ops/s at 1.1 GB, 100% correct

### Tooling (2026-03-12, PR #14)

- Added clojure-lsp (`cclsp`) and clojure-mcp MCP server configs
- Auto-allow permission prompt bypass for clojure-mcp tools

---

## 2. Where We Are — Current State of the Codebase

### Scale

| Metric | Value |
|--------|-------|
| Source files | 35 (`.cljc`, `.cljs`, `.clj`, `.cc`) |
| Source lines | ~28,000 |
| Test files | 38 (CLJS + JVM + E2E) |
| Test lines | ~8,700 |
| Test suites | 15+ named suites, 218+ assertions across 200+ tests |
| Documentation | 14 files in `doc/` |
| Native code | 357 lines of C++14 (mmap + futex CAS) |

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        eve.alpha                            │
│                   (Public API entry point)                  │
├────────────────────┬────────────────────────────────────────┤
│    CLJS Path       │            JVM Path                    │
│  eve.shared-atom   │          eve.atom                      │
│  (SAB-backed)      │      (mmap-backed)                     │
├────────────────────┴────────────────────────────────────────┤
│              Persistent Data Structures                     │
│  eve.map    eve.vec    eve.set    eve.list                  │
│  (HAMT)     (RRB)      (HAMT)    (linked)                  │
│                    eve.hamt-util                             │
│             (portable Murmur3, bitwise helpers)              │
├─────────────────────────────────────────────────────────────┤
│                   Slab Allocator Layer                       │
│  eve.deftype-proto.alloc   (6 size classes, bitmap alloc)   │
│  eve.deftype-proto.coalesc (overflow coalescing allocator)  │
│  eve.deftype-proto.data    (constants, offsets, headers)    │
│  eve.deftype-proto.serialize (encode/decode values)         │
├─────────────────────────────────────────────────────────────┤
│                   Memory Backends                           │
│  SharedArrayBuffer (CLJS) │ mmap via mmap_cas.cc (Node/JVM)│
│  eve.mem (IMemRegion)     │ eve.wasm-mem (WASM SIMD)       │
├─────────────────────────────────────────────────────────────┤
│              Specialized Collections                        │
│  eve.deftype.int-map (PATRICIA trie)                        │
│  eve.deftype.rb-tree (red-black sorted set)                 │
│  eve.obj (typed shared objects, AoS/SoA)                    │
│  eve.array (typed array with Atomics API)                   │
└─────────────────────────────────────────────────────────────┘
```

### Key Files by Size

| File | Lines | Role |
|------|-------|------|
| `shared_atom.cljs` | 3,695 | SAB atom — the workhorse for in-browser CLJS |
| `map.cljc` | 3,514 | HAMT map — most complex data structure |
| `set.cljc` | 2,113 | HAMT set (mirrors map structure) |
| `alloc.cljc` | 1,699 | Slab allocator core |
| `vec.cljc` | 1,556 | Persistent vector |
| `mem.cljc` | 1,254 | IMemRegion protocol + JVM slab I/O |
| `serialize.cljc` | 1,249 | Value serialization (Eve ↔ bytes) |
| `atom.cljc` | 1,175 | Cross-process mmap atom |
| `int_map.cljs` | 918 | Integer map (CLJS-only) |
| `list.cljc` | 793 | Persistent linked list |
| `simd_hamt.cljs` | 674 | SIMD-accelerated HAMT operations |
| `obj.cljc` | 662 | Typed shared objects |

### What Works Today

**Fully functional across all platforms:**
- Persistent hash maps, vectors, sets, and lists in shared memory
- Cross-process atoms: JVM and Node.js processes CAS on the same mmap files
- In-browser atoms: web workers share SAB-backed atoms via `Atomics`
- All 15 test suites pass (the "green baseline" from CLAUDE.md)
- Epoch-based GC prevents use-after-free in concurrent scenarios
- Full Clojure protocol surface on both JVM and CLJS
- Lazy seqs on JVM (no more eager ArrayList materialization)
- Transients on all JVM types
- `IFn`, `IHashEq`, `print-method` on all JVM types

**Performance characteristics:**
- Swap latency is O(log32 N) — constant-time regardless of atom size
- Zero-copy reads via `deref` walking mmap/SAB memory directly
- No serialization/deserialization overhead for reads

### Known Limitations

1. **Coalescing slab OOM** — The `bench-results.md` notes that B6 tier 3
   (1000 keys × 20KB values) OOM'd on the coalescing slab allocator.
   Large values stress the overflow path.

2. **Integer map and red-black tree are CLJS-only** — `int_map.cljs` and
   `rb_tree.cljs` have no JVM counterparts. Not yet migrated to `.cljc`.

3. **SIMD/WASM subsystem is CLJS-only** — `simd.cljs`, `simd_hamt.cljs`,
   `simd_wasm.cljs`, `wasm.cljs`, `wasm_mem.cljs` are browser/Node.js specific.
   The JVM path uses Panama FFM instead.

4. **No release artifacts** — No published Maven/Clojars JAR, no npm package.
   Installation is via git deps only.

5. **shared_atom.cljs complexity** — At 3,695 lines, this is the largest and
   most complex file. It handles SAB layout, descriptor tables, SoA mirrors,
   WASM scratch regions, and epoch GC — all in one namespace. It's marked as
   a protected file in CLAUDE.md.

---

## 3. Where We're Going — Open Frontiers

### Near-Term (the natural next steps)

1. **IntMap/RBTree CLJC migration** — Following the Phase 7 pattern that
   migrated array and obj to `.cljc`, the integer map and red-black tree are
   candidates for JVM parity. The PATRICIA trie (`int_map.cljs`, 918 lines)
   and sorted set (`rb_tree.cljs`, 619 lines) would benefit from cross-platform
   availability.

2. **Coalescing allocator robustness** — The OOM at 1000 keys × 20KB values
   suggests the overflow coalescing path (`coalesc.cljc`, 479 lines) needs
   either larger backing regions or a smarter compaction strategy for
   production-scale workloads.

3. **Release packaging** — The project has no Clojars/Maven artifact or npm
   registry entry. A `v0.1.0` tag exists in the CHANGELOG but no actual release
   workflow. Consumers currently depend on git SHA.

### Medium-Term (architectural opportunities)

4. **shared_atom.cljs decomposition** — The 3,695-line monolith could be split
   into focused modules (descriptor management, epoch GC, SAB layout, atom
   protocol) without changing the public API. This would improve maintainability
   and make the SAB path more testable in isolation.

5. **Browser-side mmap equivalent** — OPFS (Origin Private File System) with
   `createSyncAccessHandle` could provide browser-side persistence analogous
   to mmap on Node/JVM, extending durable atoms to pure web apps.

6. **Benchmarking CI** — The `bench/` directory has stress tests and profiling
   tools, but no automated regression detection. A CI job that tracks swap
   latency percentiles across commits would catch performance regressions.

### Long-Term (the vision)

7. **Multi-atom domains** — The root file format is already V2 "multi-atom"
   (`ROOT_FILE_SIZE_V2 = 8320`) with slot-based worker registries. This
   infrastructure supports multiple named atoms in a single domain, enabling
   richer shared-state topologies (e.g., a shared queue + config + state in one
   mmap domain).

8. **Reactive subscriptions** — The futex wait/notify infrastructure in
   `mmap_cas.cc` already supports cross-process blocking. Building a watch/notify
   layer on top would enable reactive cross-process event streams without polling.

9. **Distributed atoms** — The CAS-on-root-pointer model is fundamentally
   single-machine (mmap requires shared filesystem). Extending to network CAS
   (via consensus protocol or CRDT merge) would unlock multi-machine shared state.

---

## Summary

Eve is a focused, ambitious library that has reached a **functional plateau**:
the core data structures work across three platforms, the JVM parity gap has
been closed, and the benchmarks prove constant-time swap latency up to 1 GB
atoms. The codebase is ~28K lines of source with ~8.7K lines of tests and
extensive documentation.

The project went from initial commit to full JVM parity in **two days**
(March 10–11, 2026) across 14 pull requests and 61 commits. The architecture
is sound — slab allocators, HAMT path-copying, epoch GC, and lock-free CAS
compose cleanly. The main risks are the complexity concentration in
`shared_atom.cljs` and the lack of release infrastructure.

The most impactful next work is likely **release packaging** (making Eve
consumable as a library) and **coalescing allocator hardening** (removing the
large-value OOM ceiling).
