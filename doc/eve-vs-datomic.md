# Eve as a Database: Comparison with Datomic

## What Eve Is

Eve is a shared-memory persistent data structure library. When used as a
database, it provides:

- **Persistent atoms** backed by memory-mapped files on disk
- **HAMT-based maps/vectors/sets/lists** with structural sharing
- **Lock-free CAS concurrency** — no coordination server, no locks
- **Cross-process sharing** — JVM and Node.js processes share the same atom via mmap
- **Zero-copy reads** — deref walks mmap'd memory directly

## Comparison Matrix

| Aspect | Eve | Datomic |
|--------|-----|---------|
| **Data model** | Clojure persistent collections (maps, vectors, sets, lists) | Entity-attribute-value (EAV) triples with time |
| **Storage** | Memory-mapped slab files on local disk | DynamoDB/PostgreSQL/Cassandra backend |
| **Concurrency** | Lock-free CAS on 32-bit root pointer | Single transactor (serialized writes) |
| **Write model** | Multi-writer CAS (any process can write) | Single-writer (one transactor) |
| **Read model** | Zero-copy mmap traversal | Peer cache + storage reads |
| **Schema** | Schema-free (application-level) | Schema-enforced with types and cardinality |
| **Query** | Clojure data operations (get, assoc, filter, etc.) | Datalog query engine |
| **Time travel** | Not built yet (structural sharing makes it possible) | Built-in — every fact is timestamped |
| **Transactions** | Single-atom CAS (all-or-nothing per atom) | Multi-entity ACID transactions |
| **Deployment** | Embedded library — no server process | Transactor + peers + storage backend |
| **Cross-machine** | Not yet (feasible via Lustre — see below) | Built-in via storage backend |
| **Latency (write)** | p50 ~0.16ms (Node), ~1.5ms (JVM) | p50 ~2-10ms (network + transactor) |
| **Latency (read)** | p50 <1μs (mmap, cached) | p50 ~1-5ms (peer cache hit) |
| **Disk footprint** | ~1MB empty, ~2.1MB for 1K keys | Grows with full history retention |
| **Max atom size** | Exabyte-capable (slab6 overflow, sparse files) | Limited by storage backend |

## Eve's Advantages

### 1. Raw Performance
Eve's write latency is 10-60x faster than Datomic under no contention.
Reads are effectively free — mmap walks cached pages with no network hop.

### 2. Multi-Writer Concurrency
Any process can write simultaneously via CAS. No single-writer bottleneck.
Under contention, throughput degrades gracefully (924 ops/s with 8 concurrent writers).

### 3. Zero Operational Overhead
No transactor process, no storage backend, no coordination server. Eve is a
library you require — the files on disk are the database.

### 4. Structural Sharing
Updates allocate only O(log32 N) new nodes. A 1-billion-key map is 6 levels deep.
Swap latency is constant regardless of atom size.

### 5. Transparent Clojure API
Standard `swap!`/`reset!`/`deref`. No query language to learn. Regular Clojure
data operations work directly on the stored data.

### 6. Cross-Platform Identity
JVM Clojure and Node.js ClojureScript share identical on-disk formats, byte
layouts, hash functions, and CAS protocols. One domain, two runtimes.

## Datomic's Advantages

### 1. Time Travel (Built-In)
Every fact in Datomic is timestamped. You can query the database as-of any point
in time. Eve's structural sharing makes this architecturally feasible but it is
not built yet.

### 2. Datalog Query Engine
Datomic provides a declarative query language with joins, aggregation, and rules.
Eve relies on Clojure's standard library for data manipulation.

### 3. Schema Enforcement
Datomic enforces attribute types, cardinality, and uniqueness at the storage
level. Eve is schema-free — validation happens at the application layer.

### 4. Cross-Machine Distribution
Datomic works across machines out of the box via its storage backend (DynamoDB,
PostgreSQL, etc.). Eve currently requires a shared filesystem for cross-machine
use (see Lustre section below).

### 5. Mature Ecosystem
Datomic has years of production use, documentation, tooling (console, analytics),
and community patterns for schema design, migration, and backup.

## "Not Built Yet" vs "Not Possible"

Many apparent limitations of Eve are architectural choices that can be addressed,
not fundamental constraints:

| Capability | Status | Difficulty |
|-----------|--------|------------|
| Time travel / history | Not built yet | Medium — structural sharing already preserves old trees |
| Schema enforcement | Not built yet | Easy — application-layer validation |
| Automatic indexing | Not built yet | Medium — specialized collections (int-map, sorted-set) exist |
| Reactive subscriptions | Not built yet | Easy — filesystem polling is faster than network push |
| Cross-machine distribution | Not built yet | See Lustre plan below (~670 lines of code) |
| Replication / backup | Not built yet | Easy — slab files can be copied/rsync'd |
| Multi-atom transactions | Not built yet | Medium — combine related data into one atom |
| >256 atoms per domain | Not built yet | Easy — arbitrary limit, multiple domains work today |

---

## Eve-Lustre: Cross-Machine Distribution Plan

### The Problem
Eve currently works across processes on a **single machine** via mmap. To work
across machines, it needs a shared filesystem that supports:

1. **Coherent mmap** — writes from one node are visible to readers on other nodes
2. **Atomic CAS on mapped memory** — or an equivalent mutual exclusion primitive
3. **fcntl byte-range locking** — for CAS fallback when hardware atomics don't span nodes

### Why NFS Doesn't Work
AWS EFS, GCP Filestore, and Azure Files are all NFS-based. NFS uses
"close-to-open" consistency — mmap bypasses this entirely, so stale cached pages
persist across nodes. These filesystems **cannot** support Eve's concurrency model.

### Why Lustre Works
Lustre's LDLM (Distributed Lock Manager) provides coherent mmap across nodes
via lock callbacks and cache invalidation. fcntl byte-range locking is fully
supported and works across nodes. All three major clouds offer managed Lustre:

| Cloud | Service | Multi-AZ | Latency |
|-------|---------|----------|---------|
| AWS | FSx for Lustre | Single-AZ (cross-AZ via VPC peering) | ~5-15ms CAS |
| GCP | Managed Lustre | Single-zone | ~5-15ms CAS |
| Azure | Managed Lustre | Single-AZ | ~5-15ms CAS |

### Code Changes Required (~670 lines, zero algorithm changes)

Eve's entire I/O abstraction is the `IMemRegion` protocol in `src/eve/mem.cljc`.
All atomic operations (CAS, load, store, add, sub) flow through this single
interface. The Lustre adaptation requires:

**1. New IMemRegion implementations (~250 lines)**
- `LustreMmapRegion` (CLJS) — ~150 lines
- `LustreJvmMmapRegion` (JVM) — ~100 lines
- Both use fcntl byte-range locking as CAS fallback instead of hardware atomics

**2. Native addon extension (~200 lines C++)**
- Keep fd open after mmap (currently closed at line 146 of `mmap_cas.cc`)
- Add `fcntl_cas32()`, `fcntl_add32()` functions
- Extend `MmapDeleter` to also `close(fd)` in Lustre mode

**3. Atom wiring (~20 lines in `atom.cljc`)**
- Detect Lustre mode from config (`:lustre true` or `:backend :lustre`)
- Select `LustreMmapRegion` instead of `NodeMmapRegion`/`JvmMmapRegion`
- No changes to CAS loop, epoch GC, slab allocator, or any data structure code

**4. Portability layer (~200 lines)**
- Optional `PosixFsBackend` using pread/pwrite instead of mmap for maximum
  filesystem compatibility
- Pluggable backend selection: `MmapBackend` (current), `LustreBackend`,
  `PosixFsBackend`

### What Does NOT Change
- HAMT algorithms (`eve.map`, `eve.set`)
- Vector/list implementations (`eve.vec`, `eve.list`)
- Slab allocator (`eve.deftype-proto.alloc`)
- Coalescing overflow allocator (`eve.deftype-proto.coalesc`)
- Serializer (`eve.deftype-proto.serialize`)
- Epoch-based GC logic
- CAS loop protocol
- `shared_atom.cljs` (SAB atoms, in-process only)
- Public API (`eve.alpha`)

The entire change is a **thin layer at the bottom** of the stack.

---

## Monetization

### How Datomic Monetized (and Stopped)
- 2012-2023: Commercial license ($3,000-$16,000/year per transactor)
- Datomic Pro Starter was free for small use
- 2023: Datomic went fully free after Nubank acquisition
- Revenue model shifted to supporting Nubank's internal infrastructure

### Recommended Model: DuckDB/MotherDuck Pattern

The closest analogy to Eve-as-database is DuckDB — a free embedded library with
a separate company (MotherDuck) selling the managed cloud service.

**Phase 1: Foundation**
- Eve library: MIT-licensed, free forever
- Dual license for enterprise (BSL for managed service providers)
- Community building, documentation, ecosystem

**Phase 2: Support & Consulting**
- Enterprise support contracts
- Deployment consulting for Lustre-backed production use
- Training and architecture review

**Phase 3: Managed Service (Eve Cloud)**
- Managed Lustre-backed Eve domains as a service
- Multi-tenant isolation, automated backup, monitoring
- Available on all three clouds via marketplace

**Phase 4: Cloud Marketplace Distribution**
- AWS Marketplace, GCP Marketplace, Azure Marketplace
- All charge ~3% for SaaS listings
- Unified billing through customer's existing cloud account
- Reduces sales friction — no separate procurement process

### Pricing Analogy

| Tier | Target | Price Point |
|------|--------|-------------|
| Free | Individual developers, open source | $0 (MIT library) |
| Team | Small teams, <10 atoms, <100GB | $49-99/mo |
| Business | Production workloads, multi-node Lustre | $299-999/mo |
| Enterprise | Custom Lustre clusters, SLA, support | Custom pricing |

### Key Differentiators for Sales
1. **No server process** — unlike Datomic's transactor, Eve is embedded
2. **Multi-writer** — unlike Datomic's single-writer bottleneck
3. **Sub-millisecond writes** — 10-60x faster than Datomic
4. **Cross-platform** — JVM + Node.js + browser from one codebase
5. **Incremental adoption** — start with in-process atoms, graduate to persistent, then to Lustre
