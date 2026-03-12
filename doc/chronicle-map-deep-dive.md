# Eve vs. Chronicle Map: A Deep Comparative Analysis

_March 2026_

---

## 1. Chronicle Map in Production: What the Numbers Actually Say

Chronicle Map markets itself with sub-microsecond latency claims. Let's be precise about what that means and where it breaks down.

### Reported Production Numbers

| Metric | Number | Source |
|--------|--------|--------|
| **Peak writes** | 30M updates/s (500M keys, 16 cores, 64 MB heap) | Chronicle Software (Enterprise) |
| **Read latency** | ~600 ns average (get) | OpenHFT benchmarks |
| **Write latency** | ~1,500 ns average (put) | OpenHFT benchmarks |
| **Cross-process visibility** | ~40 ns | Chronicle Software |
| **Memory per entry** | ~30 bytes overhead | DZone benchmarks |
| **Production entry counts** | "billions" at tier-1 banks | Chronicle Software marketing |

### Where Chronicle Map Degrades

The marketing paints a rosy picture. The engineering reality is more nuanced:

**Issue #185 — 100x slowdown at 3 billion entries.** A user with 16 cores, 256 GB RAM, and NVMe SSD reported that performance at 3B entries was ~100x slower than at a few million. The response from Chronicle's Rob Austin attributed this to hash table load factor — Chronicle Map uses open-addressing linear probing, which degrades as segments fill. The recommendation was to over-provision entry counts to keep the load factor low (between 0.33 and 0.66).

**Tier chaining under pressure.** When a segment overflows its initial tier allocation, Chronicle Map chains new tiers — and explicitly warns that "a segment with chained tiers might be much slower for key search than a normal single-tier segment." This means under-provisioned maps degrade unpredictably.

**The 40ns cross-process number is misleading.** That's cache-to-cache coherency latency on a single socket — the time for another core to see a cache-line update after a store. It is *not* the time for a full get() or put() operation, which involves hashing, segment lookup, lock acquisition, linear probing, and serialization.

### Realistic Chronicle Map Performance Tiers

| Scale | In-Memory? | Realistic Get Latency | Realistic Put Latency |
|-------|-----------|----------------------|----------------------|
| 1M entries, fits in RAM | Yes | 0.3-1 μs | 1-2 μs |
| 100M entries, fits in RAM | Mostly | 0.5-2 μs | 1.5-5 μs |
| 500M entries, partially paged | Mixed | 2-50 μs (bimodal) | 5-100 μs (bimodal) |
| 3B entries, disk-dominant | No | 50-500 μs (page faults) | 100-1000+ μs |

**Key insight**: Chronicle Map's sub-microsecond promise holds only when the working set fits in RAM. Once data exceeds physical memory and the OS starts paging mmap'd regions, Chronicle Map is subject to the same page fault physics as any mmap-based system — including Eve.

---

## 2. Architectural Comparison at the Data Structure Level

### Chronicle Map: Mutable Segmented Hash Table

```
ChronicleMap File
├─ Global Header (config, lock words)
├─ Segment Headers (N segments, each with 3-level lock)
├─ Segment 0
│  ├─ Hash Lookup (open-addressing, linear probing)
│  │   └─ Slots: [hash_key_bits | entry_chunk_index]
│  └─ Entry Space (variable-size chunks)
│      └─ [key_size | key_bytes | value_size | value_bytes]
├─ Segment 1
│  └─ ... (same structure)
└─ Segment N-1
```

- **Mutation model**: In-place. A put() overwrites the value bytes at the existing entry location.
- **Concurrency**: Lock-striped by segment. One writer per segment at a time. Readers within a segment wait for writers.
- **Hash collision**: Linear probing within the flat hash table.
- **Growth**: No resize. Entry count is fixed at creation time. Over-provision or suffer.

### Eve: Immutable HAMT in Slab Memory

```
Eve Domain Files
├─ .root (8,320 B — root pointers + worker registry + atom table)
├─ .rmap (256 KB — reader epoch map)
├─ .slab0 (32B blocks)  + .slab0.bm (bitmap)
├─ .slab1 (64B blocks)  + .slab1.bm (bitmap)
├─ .slab2 (128B blocks) + .slab2.bm (bitmap)
├─ .slab3 (256B blocks) + .slab3.bm (bitmap)
├─ .slab4 (512B blocks) + .slab4.bm (bitmap)
├─ .slab5 (1024B blocks)+ .slab5.bm (bitmap)
└─ .slab6 (overflow, coalescing allocator)
```

- **Mutation model**: Copy-on-write. A swap!() allocates new HAMT path nodes, then CAS-swaps the root pointer.
- **Concurrency**: Lock-free. Readers never block. Writers contend only on the single 32-bit root CAS.
- **Hash collision**: Trie descent (5 bits per level), collision nodes at max depth.
- **Growth**: Dynamic. Slabs grow lazily via CAS on total_blocks. No pre-sizing required.

---

## 3. The Lookup Path: Cache Miss Analysis

This is where the "100-1000x slower" claim deserves scrutiny.

### Chronicle Map: Single-Key Get

1. **Hash the key** — ~50 cycles (compute 64-bit hash)
2. **Identify segment** — ~1 cycle (shift + mask)
3. **Acquire read lock** — ~10-50 cycles (CAS spin, amortized)
4. **Probe hash table** — 1-3 cache line loads (linear probing, ~0.33-0.66 load factor)
5. **Read entry** — 1 cache line load (key + value contiguous in entry space)
6. **Deserialize value** — varies (copy bytes into Java object)
7. **Release lock** — ~5 cycles

**Total cache misses**: ~2-4 (segment header, hash slot, entry data)
**Best case**: ~300-600 ns when hot in cache

### Eve: Single-Key Get (HAMT Lookup)

1. **Hash the key** — ~100 cycles (Murmur3 over serialized key bytes)
2. **Load root pointer** — 1 atomic load from .root file (1 cache miss)
3. **Level 0**: Load HAMT root node from slab — read 12-byte header + scan data_bitmap, follow child offset (1 cache miss)
4. **Level 1**: Load child node from slab — same pattern (1 cache miss)
5. **Level 2**: Load child node from slab — same pattern (1 cache miss)
6. **Level 3**: Find inline KV data, compare key bytes (0-1 cache miss)
7. **Return**: Zero-copy — the value *is* the bytes in mmap memory. No deserialization to a heap object.

**Total cache misses at different scales:**

| Map Size | HAMT Depth | Cache Misses (lookup) | Cache Misses (Chronicle) |
|----------|-----------|----------------------|-------------------------|
| 100 keys | 2 | 3-4 | 2-3 |
| 1,000 keys | 3 | 4-5 | 2-3 |
| 100,000 keys | 3-4 | 4-6 | 2-4 |
| 1,000,000 keys | 4 | 5-7 | 2-4 |
| 100,000,000 keys | 5-6 | 6-8 | 3-5 |
| 1,000,000,000 keys | 6-7 | 7-9 | 3-6 |

**Implication**: Eve requires roughly 2x the cache misses per lookup compared to Chronicle Map. At ~300 cycles per miss, that's ~600-900 ns additional latency. This puts Eve reads at roughly **1-3 μs** vs Chronicle Map's **0.3-1 μs** for warm-cache scenarios.

**That's 2-5x slower, not 100-1000x.**

The "100-1000x" gap in the previous analysis compared Eve's full `swap!` operation (~120-400 μs) against Chronicle's `get()` latency (~0.6 μs). That's comparing a *write* to a *read*. The fair comparisons are:

| Operation | Eve | Chronicle Map | Ratio |
|-----------|-----|---------------|-------|
| **Read (warm cache, 1M keys)** | ~1-3 μs (JVM deref) | ~0.3-1 μs (get) | 2-5x |
| **Read (warm cache, 1K keys, Node.js)** | ~44 μs (N-API overhead) | N/A (Java only) | — |
| **Read (warm cache, 1K keys, JVM)** | ~0.6 μs | ~0.3 μs | ~2x |
| **Write (no contention, 1K keys)** | ~120-400 μs (swap!) | ~1.5 μs (put) | 80-260x |
| **Write (no contention, 100 keys)** | ~120 μs (swap!) | ~1.5 μs (put) | ~80x |

### Why Eve Writes Are Genuinely Slower

Eve's write path does fundamentally more work:

1. **Read current value** — traverse the HAMT to get current state
2. **Apply user function** — `(f current-value args)`
3. **Allocate ~3-5 new slab blocks** — bitmap scan + CAS per block
4. **Serialize new path nodes** — write 12-byte headers + KV data
5. **CAS the root pointer** — single atomic operation
6. **On failure**: free all allocated blocks, backoff 1-8 ms, retry from step 1

Chronicle Map's write:
1. **Hash key** → **find segment** → **acquire write lock** → **probe hash table** → **overwrite bytes in-place** → **release lock**

Eve allocates memory and writes multiple nodes. Chronicle Map overwrites bytes in place. This is an inherent architectural difference — the cost of immutability.

---

## 4. At Scale: Where Eve Gains Ground

### 4.1 Chronicle Map's Fixed-Size Problem

Chronicle Map requires you to declare `entries(N)` at creation time. The internal segment count and hash table size are computed once and never change. This creates real operational pain:

- **Over-provision → waste memory.** A 10B-entry map with 30B overhead = 300 GB minimum, even if only 1M entries are populated.
- **Under-provision → catastrophic degradation.** Segments fill → tier chaining → search goes from O(1) to O(chain_length).
- **No resize.** You cannot grow a Chronicle Map. You must create a new one and copy.

**Eve doesn't have this problem.** Slabs grow lazily. An Eve domain starts at ~1 MB and grows proportionally to actual data. There is no pre-sizing decision. This is a genuine operational advantage.

### 4.2 The Multi-Writer Advantage

Chronicle Map's lock-striped segments allow concurrent writes to *different* segments. But two writers to keys in the *same segment* serialize — one waits for the other. Under high write contention to popular keys, this creates queueing.

Eve has a single CAS on the root pointer. Under contention, writers retry. The question is: which scales better?

**Low contention (most production workloads):**
- Chronicle Map: multiple writers hit different segments → near-linear scaling
- Eve: CAS succeeds on first try → single-writer speed

**High contention (hot keys):**
- Chronicle Map: writers to same segment queue on the segment lock
- Eve: CAS retries with jittered backoff. Throughput degrades (see below) but never deadlocks.

Eve's measured contention scaling:

| Writers | Throughput (swaps/s) | Avg Latency |
|---------|---------------------|-------------|
| 1 | ~1,900 | ~0.5 ms |
| 4 | ~1,700 | ~2.4 ms |
| 8 | ~920 | ~8.7 ms |
| 16 | ~470 | ~34 ms |

At 16 writers, Eve's throughput is still 470 atomic swaps/sec — each of which is a *full HAMT path-copy*. Chronicle Map would fare better here in absolute ops/sec, but the scaling *curve* is similar for hot-key contention.

### 4.3 The Snapshot / Time-Travel Advantage

This is where Eve provides something Chronicle Map fundamentally cannot.

With Eve, any root pointer you've read is a valid, consistent snapshot of the entire data structure. You can hold onto it, pass it to another thread, compare it with the current version, or serialize it — all without holding any locks or coordinating with writers.

Chronicle Map has no equivalent. Reading a consistent multi-key view requires locking multiple segments in order. There are no snapshots, no history, no diffing. If you need "what was the state 5 seconds ago?", Chronicle Map cannot help.

**Use cases where this matters:**
- Audit logs and compliance (fintech regulatory requirements)
- Undo/redo in collaborative applications
- Debugging production state ("what did the map look like when the bug occurred?")
- Consistent backups without stopping writes
- Time-series analysis of state evolution

### 4.4 Memory Efficiency at Scale

| Map with N entries | Eve Disk | Chronicle Map Disk | Notes |
|-------------------|---------|-------------------|-------|
| Empty | ~1 MB | Pre-allocated | CM pre-allocates based on entries() |
| 1,000 | ~2.1 MB | ~30 KB + overhead | CM wins for small maps |
| 100,000 | ~20 MB (est.) | ~3 MB + segments | CM more compact per-entry |
| 1,000,000 | ~150-200 MB (est.) | ~30 MB + segments | CM more compact per-entry |
| 100,000,000 | ~15-20 GB (est.) | ~3 GB + segments | CM ~5x more compact |

Eve's HAMT nodes carry structural overhead — 12-byte headers, child pointer arrays, bitmap metadata. Chronicle Map's entries are more compact because they're just key+value bytes with minimal per-entry overhead (~30 bytes).

However, Eve's structural sharing means that *versions* are nearly free. Updating 1 key in a 100M-key map allocates ~5-6 new slab blocks (~200-400 bytes total). In Chronicle Map, there's no concept of "the previous version" — it's gone.

---

## 5. Where Chronicle Map Genuinely Wins

### 5.1 Raw Hot-Path Latency

For the use case "look up one key as fast as possible, thousands of times per second, all in RAM," Chronicle Map is 2-5x faster on reads and 80-260x faster on writes. This is not a benchmarking artifact — it's an architectural fact. Flat hash table + in-place mutation + segment lock will always be faster than HAMT traversal + path-copy + CAS for single-key operations.

### 5.2 Garbage-Free Steady State

Chronicle Map's `getUsing()` API reuses caller-provided value objects, avoiding all allocation. Eve's JVM path must allocate thread-local tracking structures during swap. Eve's epoch GC is clever but adds overhead that Chronicle Map simply doesn't have.

### 5.3 Java Ecosystem Integration

Chronicle Map implements `java.util.concurrent.ConcurrentMap`. It drops into any Java codebase that uses `Map<K,V>`. Eve requires adopting Clojure, learning `swap!`/`deref`, and accepting a fundamentally different programming model.

### 5.4 Maturity and Trust

Chronicle Map is in production at tier-1 banks handling billions of dollars daily. It has had years of production hardening, chaos monkey testing, and commercial support. Eve has zero production deployments. For regulated industries, this gap is measured in years, not features.

---

## 6. Where Eve Genuinely Wins

### 6.1 No Pre-Sizing

Chronicle Map's `entries(N)` is a hard requirement. Get it wrong and you either waste memory or trigger catastrophic degradation. Eve grows dynamically, which is operationally simpler and eliminates an entire class of capacity planning failures.

### 6.2 Multi-Writer Without Lock Coordination

Chronicle Map's segment locks can deadlock in theory (the design avoids it via lock ordering, but it's complexity). Eve's single CAS is deadlock-free by construction. Under moderate contention, Eve's retry-with-backoff is simpler to reason about than lock-striped segments.

### 6.3 Natural Snapshots and Version History

Eve's persistent data structures provide zero-cost consistent snapshots. Chronicle Map provides none. For any use case that needs "compare current state with previous state" or "roll back to a known-good state," Eve is architecturally superior.

### 6.4 Cross-Runtime Interop

Eve works across JVM and Node.js on the same mmap'd files. Chronicle Map is Java-only. If your architecture includes JavaScript/TypeScript services alongside JVM services, Eve provides cross-runtime shared state that Chronicle Map cannot.

### 6.5 Rich Nested Data Model

Chronicle Map stores flat key-value pairs. Nesting requires serializing inner structures to bytes and deserializing on read. Eve natively supports maps-of-maps-of-vectors-of-keywords with structural sharing at every level. For applications with rich, deeply nested state, Eve eliminates serialization layers.

---

## 7. Revised Assessment: Is Fintech Actually Off The Table?

The previous analysis said "100-1000x too slow." The corrected analysis:

- **Reads**: 2-5x slower (not 100x)
- **Writes**: 80-260x slower (but provides immutability guarantee Chronicle Map doesn't)

### Fintech Sub-Verticals

Not all fintech is tick-to-trade:

| Sub-Vertical | Latency Req | Eve Viable? | Why |
|--------------|------------|-------------|-----|
| **HFT / tick-to-trade** | <10 μs | No | Need sub-μs reads, can't afford HAMT traversal |
| **Order management** | <1 ms | Marginal | Eve reads fit; writes at edge of budget |
| **Risk aggregation** | <10 ms | Yes | Snapshot capability is uniquely valuable |
| **Position tracking** | <100 ms | Yes | Rich nested data model fits well |
| **Regulatory reporting** | Seconds | Yes | Time-travel / audit trail is killer feature |
| **Reference data** | <1 ms reads | Yes | Read-heavy, infrequent updates — HAMT reads are fine |
| **EOD batch processing** | Minutes | Yes | Structural sharing makes version comparison cheap |

**Key insight**: The fintech vertical that cares about sub-microsecond latency (HFT) is one sub-segment. Much of fintech — risk, compliance, position management, reference data — operates at latencies where Eve is competitive, and where Eve's unique properties (snapshots, audit trails, cross-runtime) are genuinely valuable.

### The Compliance Angle

Post-2008 financial regulation (MiFID II, Dodd-Frank, Basel III) requires firms to maintain auditable records of state changes. Chronicle Map has no native answer for this — firms build audit logs as a separate system. Eve's persistent data structures provide audit trails *by architecture*. Every version of every atom is a consistent, inspectable snapshot until GC'd. Extending the GC epoch retention policy could provide configurable history depth with zero additional engineering.

---

## 8. Scaling Projections

### Theoretical Limits

| Dimension | Eve | Chronicle Map |
|-----------|-----|---------------|
| Max entries | ~1B per slab class (29-bit block index) | Fixed at creation, practical limit ~3-5B |
| Max total data | ~4 GB per slab class (6 classes) = ~24 GB data | Disk-limited (tested at 8 TB virtual) |
| Max concurrent writers | Unlimited (CAS contention) | Limited by segment count |
| Max concurrent readers | Unlimited (lock-free) | Unlimited (read lock is shared) |

### What Eve Would Need for Large-Scale Fintech

1. **CHAMP optimization**: Replace the current HAMT bitmap node with CHAMP layout (separate data/node bitmaps stored compactly) for better cache locality — reduces cache misses per level from ~1.5 to ~1.
2. **Segment the root pointer**: Instead of one global CAS target, partition atoms into segments (like Chronicle) to reduce contention for high-write workloads. Keeps immutability but adds write parallelism.
3. **JVM write performance**: The current 700-1000 ops/s on JVM needs investigation — Node.js at 2000-3000 ops/s suggests the JVM path has optimization headroom (likely in alloc log tracking and slab I/O).
4. **Configurable GC retention**: Add a policy for "keep N epochs of history" to serve the compliance/audit use case explicitly.
5. **Crash safety (WAL)**: Write-ahead log or double-buffered root commit for ACID guarantees — non-negotiable for regulated industries.

---

## 9. Summary

The "100-1000x too slow" characterization was comparing apples to oranges (Eve swap! vs Chronicle get). The corrected comparison:

| | Eve Read | Eve Write | Chronicle Read | Chronicle Write |
|---|---------|-----------|---------------|----------------|
| **Latency (warm, 1M keys)** | 1-3 μs | 120-400 μs | 0.3-1 μs | 1.5 μs |
| **Ratio** | — | — | 2-5x faster | 80-260x faster |

Chronicle Map is faster. That's an architectural fact — flat hash tables with in-place mutation will always beat persistent HAMT traversal with path-copy allocation.

But "faster" isn't the whole picture:

- Chronicle Map degrades catastrophically with under-provisioned segments at scale
- Chronicle Map has no snapshots, no version history, no diffing
- Chronicle Map requires precise up-front capacity planning
- Chronicle Map is Java-only
- Chronicle Map's 100x slowdown at 3B entries suggests its scaling is not as smooth as marketed

**Eve's actual competitive position in fintech**: Not HFT, but risk/compliance/audit — where the latency budget is milliseconds (not microseconds), the data model is rich and nested, and the ability to inspect historical state is a regulatory requirement rather than a nice-to-have.

---

## Sources

- [Chronicle Map Enterprise](https://chronicle.software/map-enterprise/)
- [Chronicle Map Open Source (GitHub)](https://github.com/OpenHFT/Chronicle-Map)
- [Chronicle Map Design Overview (spec)](https://github.com/OpenHFT/Chronicle-Map/blob/ea/spec/2-design-overview.md)
- [Chronicle Map Memory Layout (spec)](https://github.com/OpenHFT/Chronicle-Map/blob/ea/spec/3-memory-layout.md)
- [Issue #185: Performance slowdown at 3B entries](https://github.com/OpenHFT/Chronicle-Map/issues/185)
- [Issue #205: Garbage during iteration](https://github.com/OpenHFT/Chronicle-Map/issues/205)
- [DoubleVerify: Scaling Caches with ChronicleMap](https://medium.com/doubleverify-engineering/scaling-high-performance-in-memory-caches-with-chroniclemap-38ab9850d91d)
- [ChronicleMap Part 1: Go Off-Heap (DZone)](https://dzone.com/articles/java-chroniclemap-part-1-go-off-heap)
- [ChronicleMap Part 2: Super RAM Maps (DZone)](https://dzone.com/articles/java-chroniclemap-part-2-super-ram-maps)
- [ChronicleMap vs CHM (Mechanical Sympathy)](https://groups.google.com/g/mechanical-sympathy/c/W31SWkqyv_w)
- [SharedHashMap vs Redis (Vanilla Java)](http://blog.vanillajava.blog/2014/05/sharedhashmap-vs-redis.html)
- [Optimizing HAMTs for Fast and Lean (OOPSLA 2015)](https://michael.steindorfer.name/publications/oopsla15.pdf)
- [HAMT Wikipedia](https://en.wikipedia.org/wiki/Hash_array_mapped_trie)
- [Ideal Hash Trees (Bagwell)](https://lampwww.epfl.ch/papers/idealhashtrees.pdf)
- [Eve Persistent Atoms Documentation](doc/persistent-atoms.md)
- [Eve Internals Documentation](doc/internals.md)
