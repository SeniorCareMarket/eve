# Eve: Market Positioning & Competitive Landscape Analysis

_March 2026_

---

## 1. What Eve Actually Is

Eve is a **shared-memory persistent data structure engine** for ClojureScript and JVM Clojure. It provides persistent (in the functional-programming sense) maps, vectors, sets, and lists that live entirely in shared memory — either SharedArrayBuffer for in-process web workers, or mmap'd files for cross-process coordination between JVM and Node.js processes.

### Core Architectural Properties

| Property | Implementation |
|----------|---------------|
| **Data structures** | 32-way HAMT maps/sets, trie vectors, linked lists — all in shared slabs |
| **Memory model** | 6 fixed-size slab classes (32B–1024B) + coalescing overflow, bitmap-allocated |
| **Pointer model** | 32-bit slab-qualified offsets (3-bit class + 29-bit block index) — position-independent |
| **Atomicity** | Single 32-bit CAS on root pointer; lock-free readers |
| **Persistence** | Structural sharing — O(log₃₂ N) path-copy per update |
| **GC** | Epoch-based reclamation with heartbeat-driven stale detection |
| **Cross-platform** | Same binary layout on JVM (Panama FFM + Unsafe), Node.js (native C++ addon), browser (SAB + js/Atomics) |
| **Zero-copy reads** | Lazy deref walks mmap/SAB memory directly; no deserialization to heap |

The novel synthesis: **cross-process epoch-based GC of persistent immutable HAMT nodes in mmap'd shared memory**. No existing system combines all three of these properties.

---

## 2. The Competitive Landscape

Eve sits at the intersection of three established categories. No single competitor occupies the same point in the design space, but several overlap on one or two axes.

### 2.1 Embedded mmap Key-Value Stores

These are Eve's closest relatives in spirit: embedded, memory-mapped, no separate server process.

| System | Architecture | Mutability | Cross-Process | Structural Sharing | Language |
|--------|-------------|------------|---------------|-------------------|----------|
| **Eve** | HAMT in mmap'd slabs | Immutable (persistent) | Yes (mmap + CAS) | Yes — O(log₃₂ N) | Clojure/CLJS |
| **LMDB** | Copy-on-write B+ tree | Mutable B+ tree | Yes (mmap) | No — full page copy | C |
| **libmdbx** | Enhanced LMDB | Mutable B+ tree | Yes (mmap) | No | C |
| **RocksDB** | LSM tree | Mutable (append) | No (single-process) | No | C++ |
| **SQLite** | B-tree + WAL | Mutable | Limited (file locking) | No | C |

**Eve vs. LMDB** — the most instructive comparison:

| Dimension | Eve | LMDB |
|-----------|-----|------|
| Write model | CAS on root pointer; allocate new path nodes | Copy-on-write B+ tree pages |
| Readers block writers? | No | No |
| Writers block writers? | No (CAS retry with backoff) | Yes (single-writer lock) |
| Readers block readers? | No | No |
| Structural sharing | Full — old versions share nodes | Partial — CoW pages but no trie sharing |
| Time-travel / snapshots | Natural (old roots remain valid until GC) | Supported via read transactions |
| Max concurrent writers | Unlimited (CAS contention scales) | 1 |
| GC model | Epoch-based reclamation | Reader txn tracking |
| Disk format | Slab files + root file | Single mmap'd file |
| Maturity | Pre-production | Battle-tested (20+ years) |

**Key insight**: LMDB is single-writer. Eve is multi-writer via lock-free CAS. For write-heavy concurrent workloads, this is a fundamental architectural advantage. However, LMDB's maturity, crash safety guarantees, and ecosystem integration are decades ahead.

### 2.2 Off-Heap Shared-Memory Data Structures (JVM)

This is the space Chronicle Map dominates — the fintech/trading vertical.

| System | Data Structure | Cross-Process | Persistence | Immutable? | GC Pressure |
|--------|---------------|---------------|-------------|------------|-------------|
| **Eve** | HAMT, vector, set, list | Yes (mmap) | Yes (file-backed) | Yes (persistent) | Zero (off-heap) |
| **Chronicle Map** | Hash map (mutable) | Yes (mmap) | Yes (file-backed) | No (mutable in-place) | Zero (off-heap) |
| **Agrona** | Ring buffers, maps | No (single-process) | No | No | Low |
| **MapDB** | B-tree map | Limited | Yes | No | Low |

**Eve vs. Chronicle Map**:

| Dimension | Eve | Chronicle Map |
|-----------|-----|---------------|
| Mutation model | Immutable + structural sharing | Mutable in-place |
| Concurrent reads during write | Always safe (old version persists) | Safe (lock-striping) |
| Historical versions | Naturally available until epoch GC | Not available |
| Latency target | Sub-millisecond (0.12ms p50 Node, 0.39ms JVM) | Sub-microsecond (<1μs) |
| Language | Clojure/CLJS | Java |
| Ecosystem | Nascent | Mature (tier-1 banks, billions/day in production) |
| Data model | Rich Clojure types (keywords, maps, vecs, UUIDs) | Java serialized objects |

**Key insight**: Chronicle Map is 100-1000x faster for single-key reads because it's mutable and avoids trie traversal. Eve's advantage is *immutability*: safe snapshots, time-travel, and no reader-writer coordination. Different tools for different problems.

### 2.3 Persistent/Immutable Data Structure Libraries

These share Eve's data structure philosophy but lack shared-memory or persistence-to-disk.

| System | Language | Shared Memory? | Disk Persistence? | Cross-Process? |
|--------|----------|---------------|-------------------|---------------|
| **Eve** | Clojure/CLJS | Yes (SAB + mmap) | Yes (mmap files) | Yes |
| **Clojure core** | Clojure | No (heap only) | No | No |
| **Immutable.js** | JavaScript | No (heap only) | No | No |
| **immer (C++)** | C++ | No (heap only) | No | No |
| **im-rs** | Rust | No (heap only) | No | No |
| **Pyrsistent** | Python | No (heap only) | No | No |

**Key insight**: Eve is the only persistent data structure library that operates in shared memory. Every other library in this category is heap-bound and single-process. This is Eve's most distinctive and defensible architectural property.

### 2.4 Cross-Process Data Sharing Frameworks

| System | Model | Data Structures | Mutability | Latency |
|--------|-------|----------------|------------|---------|
| **Eve** | mmap'd persistent collections | Maps, vecs, sets, lists | Immutable (CAS on root) | ~0.1-0.4ms |
| **Apache Arrow** | Columnar memory format | Tables, arrays (columnar) | Immutable (write-once) | Zero-copy reads |
| **Plasma (Arrow)** | Shared object store | Opaque blobs | Immutable | ~μs |
| **Boost.Interprocess** | Raw shared memory | User-defined | User-defined | ~ns |
| **POSIX shm_open + mmap** | Raw shared memory | None (raw bytes) | User-defined | ~ns |

**Key insight**: Arrow solves columnar analytics data sharing. Eve solves *application state* sharing — rich, nested, heterogeneous data structures that applications actually use (maps of maps of vectors of keywords). These are complementary, not competitive.

### 2.5 Immutable Databases

| System | Model | Query Language | Embedded? | Shared Memory? |
|--------|-------|---------------|-----------|---------------|
| **Eve** | Persistent HAMT atoms | Clojure data manipulation | Yes | Yes |
| **Datomic** | Append-only facts + Datalog | Datalog | No (server) | No |
| **XTDB** | Bitemporal immutable SQL | SQL | No (server) | No |
| **DataScript** | In-memory Datalog | Datalog | Yes (in-process) | No |

**Key insight**: Eve is not a database in the Datomic/XTDB sense — it has no query language, no schema, no indexing strategy. It's a *primitive* — a shared-memory atom that happens to be persistent and cross-process. It sits below databases in the stack, more analogous to the storage engine layer.

---

## 3. Eve's Unique Position

Eve occupies a genuinely novel point in the design space:

```
                    Shared Memory / Cross-Process
                              ↑
                              |
        Chronicle Map    ←— [EVE] —→    LMDB / libmdbx
        (mutable, off-heap)     |        (mutable B-tree, mmap)
                              |
                              ↓
                    Persistent / Immutable
                              ↑
                              |
        Clojure atoms   ←— [EVE] —→    immer / Immutable.js
        (heap-only)           |        (heap-only)
                              |
                              ↓
                    Cross-Platform (JVM + JS)
```

**The synthesis that doesn't exist elsewhere:**
1. Persistent (immutable) data structures — from Clojure/Okasaki tradition
2. Shared memory (mmap) — from systems programming tradition
3. Lock-free cross-process atomicity — from concurrent systems tradition
4. Epoch-based GC across process boundaries — from crossbeam/RCU tradition

No other system combines all four.

---

## 4. Strengths and Weaknesses

### Strengths

1. **Multi-writer lock-free**: Unlike LMDB's single-writer constraint, Eve supports unlimited concurrent writers via CAS. Under moderate contention, this scales naturally.

2. **Zero-copy reads**: Deref walks mmap memory directly. No deserialization step. Readers never block and never copy.

3. **Natural snapshots**: Because data structures are persistent (structurally shared), any root pointer is a valid, consistent snapshot. No need for explicit snapshot transactions.

4. **Cross-runtime interop**: The same binary format works across JVM Clojure and Node.js ClojureScript. A JVM process and a Node process can share and atomically mutate the same data.

5. **Rich data model**: Not just key-value bytes — Eve natively supports Clojure's full type system: keywords, maps, vectors, sets, UUIDs, dates, nested collections.

6. **Dual-mode operation**: Same code, same data structures work in-browser (SAB between web workers) and on-disk (mmap between OS processes). This is architecturally rare.

7. **Structural sharing efficiency**: Updating 1 key in a 10,000-key map touches ~3-4 slab blocks. The rest is shared with the previous version.

### Weaknesses

1. **Maturity**: Eve is pre-production. LMDB has 20+ years. Chronicle Map handles billions of dollars daily. Eve has zero production deployments.

2. **Crash safety**: LMDB provides ACID guarantees via careful page management. Eve's mmap writes can be reordered by the OS; a crash during a multi-node allocation could leave orphaned blocks. There's no WAL or transaction log.

3. **Raw throughput**: Chronicle Map targets <1μs latency. Eve's CAS-based swap is ~100-400μs. For hot-path trading systems, this is 100-1000x too slow.

4. **Ecosystem size**: LMDB has bindings in 20+ languages. Chronicle Map has enterprise support and bank deployments. Eve has one developer and a ClojureScript-only API surface.

5. **Query capabilities**: None. Eve is a shared atom, not a database. No indexes, no range queries, no joins. Applications must build these themselves.

6. **Write amplification under contention**: CAS failures require freeing all newly allocated blocks and retrying. Under heavy write contention, this wastes allocation work.

7. **Clojure-only**: The rich data model (keywords, persistent collections) is a strength within Clojure but limits adoption outside it. A C or Rust process can't easily consume Eve's slab format.

8. **32-bit offset limit**: 3-bit class + 29-bit block index limits each slab class to ~512M blocks. For class 0 (32B), that's ~16GB. For class 5 (1024B), ~512GB. Large but not unbounded.

---

## 5. Market Verticals & Productization Analysis

### 5.1 High Viability: Clojure Application State Sharing

**The pitch**: "Share Clojure atoms across processes and runtimes with zero serialization overhead."

**Why it works**:
- Clojure developers already think in atoms, persistent data structures, and immutability
- Eve's API is `swap!` and `deref` — zero learning curve
- Cross-runtime (JVM ↔ Node.js) is a genuine pain point in Clojure shops
- No comparable solution exists in the Clojure ecosystem

**Market size**: Small (Clojure community is ~50K-100K developers) but highly technical and willing to pay for infrastructure.

**Competitive moat**: Deep. Nobody else is building mmap-backed persistent Clojure collections.

**Product form**: Open-source library with commercial support / consulting.

### 5.2 Moderate Viability: Browser-Based Collaborative / Real-Time Applications

**The pitch**: "Shared persistent state across web workers with zero-copy reads and structural sharing."

**Why it works**:
- Web workers need shared state; postMessage serialization is a bottleneck
- Games, collaborative editors, and real-time simulations need low-latency shared state
- Eve's SAB mode provides exactly this
- CRDT-like properties emerge naturally from persistent data structures (old versions always valid)

**Challenges**:
- SharedArrayBuffer requires cross-origin isolation headers (COOP/COEP) — deployment friction
- Most web developers don't use ClojureScript
- Competing with simpler solutions (SharedArrayBuffer + Atomics directly, or transferable objects)

**Product form**: Could extract the SAB engine as a standalone JS library (not ClojureScript-specific) to broaden market. Would require a JS-native API layer.

### 5.3 Moderate Viability: Edge/Embedded State Management

**The pitch**: "Persistent, crash-recoverable application state for edge devices with zero external dependencies."

**Why it works**:
- Edge devices need local state that survives restarts
- mmap-backed atoms provide this with no database server
- Small disk footprint (~1MB empty, ~2MB for 1000 keys)
- Epoch GC handles multi-process coordination automatically

**Challenges**:
- Crash safety story needs hardening (no WAL)
- ARM support for native addon needs validation
- Competing with SQLite, which is universal

**Product form**: Embedded runtime for IoT/edge, sold as part of a larger platform.

### 5.4 Lower Viability: Fintech / Trading Infrastructure

**The pitch**: "Lock-free multi-writer shared state for trading systems."

**Why it fails**:
- Chronicle Map already dominates this space with sub-microsecond latency
- Eve's ~100-400μs swap latency is 100-1000x too slow for tick-to-trade
- Trading firms want C++/Java, not ClojureScript
- Regulatory requirements demand battle-tested software

**What would be needed**: Rewrite hot path in C/Rust, achieve <1μs, build Java API, undergo years of production hardening. Not realistic as a near-term path.

### 5.5 Lower Viability: General-Purpose Embedded Database

**The pitch**: "LMDB but with persistent data structures and multi-writer."

**Why it's premature**:
- No query language
- No crash safety guarantees (no WAL/journal)
- No range queries or secondary indexes
- LMDB/RocksDB/SQLite ecosystem is massive and mature
- Would need 5-10 years of hardening to compete

**What would be needed**: Add ACID transactions, WAL, query layer, secondary indexes. At that point it's a different product.

---

## 6. Strategic Recommendations

### Near-term (0-12 months): Own the Clojure niche

1. **Stabilize the core**: Crash safety (WAL or write barriers), fuzz testing, property-based tests
2. **Ship 1.0 for Clojure**: `eve.alpha` API with stable guarantees
3. **Publish benchmarks**: Head-to-head with `clojure.core` atoms, Redis, LMDB via lmdbjava
4. **Tell the story**: "The only persistent data structure library that works across JVM and JS processes" — this is genuinely novel and the Clojure community will appreciate the architecture
5. **Integration**: Build bridges to Datomic (Eve as storage), Ring (session store), re-frame (shared state)

### Medium-term (1-3 years): Extract the engine

1. **Language-neutral slab format**: Document the binary format as a spec. Enable C/Rust/Python readers
2. **Standalone JS library**: Extract SAB engine from ClojureScript dependency for browser market
3. **Crash safety**: Add WAL or double-buffered root commit for ACID-like guarantees
4. **Query layer**: Basic predicate filtering, watch/notify on sub-paths

### Long-term (3+ years): Platform play

1. **Eve as embedded storage engine**: Other databases/frameworks use Eve's slab allocator + persistent HAMT as their underlying storage
2. **Edge runtime**: Bundled state management for IoT/embedded platforms
3. **Multi-language SDK**: Rust, Python, Go clients that can read/write Eve slab files

---

## 7. Summary

Eve is architecturally novel — the fusion of persistent immutable data structures, shared-memory mmap, and cross-process epoch-based GC genuinely does not exist elsewhere. The question is not whether the architecture is interesting (it is), but whether the market values the specific combination of properties Eve provides.

**The strongest productization path** is as a **Clojure-ecosystem infrastructure library** — the shared-memory atom that works across JVM and Node.js. The community is small but technical, the pain point is real, and the competitive moat is deep. From that base, the engine can be extracted and generalized.

**The weakest path** is competing head-on with LMDB, Chronicle Map, or RocksDB in their established verticals. Eve lacks the maturity, performance, and ecosystem to compete there today.

**The honest assessment**: Eve is a research-grade library with a genuinely novel architecture. Productization is viable but requires choosing a narrow beachhead (Clojure state sharing), proving reliability, and expanding from there. The technology is ahead of the market — the challenge is finding the market that values exactly what Eve provides.

---

## Sources

### Competitors & Related Systems
- [LMDB (Wikipedia)](https://en.wikipedia.org/wiki/Lightning_Memory-Mapped_Database)
- [libmdbx (GitHub)](https://github.com/erthink/libmdbx)
- [Chronicle Map Enterprise](https://chronicle.software/map-enterprise/)
- [Chronicle Map Tutorial (GitHub)](https://github.com/OpenHFT/Chronicle-Map/blob/ea/docs/CM_Tutorial.adoc)
- [Scaling Caches with ChronicleMap — DoubleVerify](https://medium.com/doubleverify-engineering/scaling-high-performance-in-memory-caches-with-chroniclemap-38ab9850d91d)
- [immer — Persistent Data Structures for C++ (GitHub)](https://github.com/arximboldi/immer)
- [Immutable.js (GitHub)](https://github.com/immutable-js/immutable-js)
- [Apache Arrow Use Cases](https://arrow.apache.org/use_cases/)
- [Arrow Zero-Copy Shared Memory (arXiv)](https://arxiv.org/html/2404.03030v1)
- [Plasma In-Memory Object Store (Arrow)](https://arrow.apache.org/blog/2017/08/08/plasma-in-memory-object-store/)
- [Metall — mmap Persistent Data Structures (Virginia Tech)](https://vtechworks.lib.vt.edu/server/api/core/bitstreams/b87d1696-381a-48fa-8168-e19b7e033ea0/content)
- [Persistent Memory Programming (ACM Queue)](https://queue.acm.org/detail.cfm?id=3358957)

### Immutable Databases
- [Datomic Alternatives (G2)](https://www.g2.com/products/datomic/competitors/alternatives)
- [XTDB — Immutable SQL Database](https://slashdot.org/software/p/Datomic/alternatives)

### Embedded Databases
- [Top Embedded Databases 2025 (Slashdot)](https://slashdot.org/software/embedded-database/)
- [RaimaDB — Edge Computing](https://raima.com/edge-computing-raima-rdm/)
- [Edge Databases (Navicat)](https://www.navicat.com/en/company/aboutus/blog/3331-edge-databases-empowering-distributed-computing-environments.html)

### SharedArrayBuffer & WebAssembly
- [SharedArrayBuffer (MDN)](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer)
- [Lockless Allocator with SharedArrayBuffers](https://greenvitriol.com/posts/lockless-allocator)
- [shared-memory-datastructures (npm)](https://www.npmjs.com/package/shared-memory-datastructures)
- [Wasm 3.0 Completed](https://webassembly.org/news/2025-09-17-wasm-3.0/)
- [Concurrency in WebAssembly (ACM Queue)](https://queue.acm.org/detail.cfm?id=3746173)

### ClojureScript Ecosystem
- [cljs-thread (GitHub)](https://github.com/johnmn3/cljs-thread)
- [In.mesh (ClojureVerse)](https://clojureverse.org/t/in-mesh-one-step-closer-to-threads-on-the-web/9069)

### Prior Art Analysis
- [Eve Architecture & Prior Art (Gist)](https://gist.github.com/johnmn3/d2b60b3c6ed08d9972b83c7138e60a6e)
