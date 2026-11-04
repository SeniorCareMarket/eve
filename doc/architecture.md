# Eve Architecture

High-level system design. The byte-level detail is `doc/internals.md`; reclamation (epochs, the retire walk, what is
freed when) is `doc/memory-model.md`; the host contract is `doc/unification/HOST-CONTRACT.md`.

---

## Design Goals

1. **Shared-memory persistence** — Clojure persistent data structures in slab memory: in-process (heap regions,
   SharedArrayBuffers on ClojureScript) or memory-mapped files (cross-process).
2. **Transparent Clojure API** — standard `swap!` / `reset!` / `deref` on atoms. Regular Clojure maps, vectors, sets
   and lists are written into eve's own collections when stored; deref reads them back as the host's own.
3. **One format on every host** — eleven host rows (`eve/hosts.edn`, beside `:bb-minimal`: the JVM,
   ClojureScript on Node and in a browser, babashka, nbb, ClojureCLR, basilisp, jolt, clojerl, ValueScript, glojure)
   share identical on-disk bytes, hash functions and CAS protocol. A disk domain one host creates, any host that maps
   files can join.
4. **One root word per atom** — every write is a compare-and-swap of the atom's 32-bit root pointer. On hosts with
   atomic read-modify-write (every host but babashka; a Lustre mount's atomics are per-operation fcntl locks) the swap
   is lock-free; a lock-only host takes part through the exclusive-hold protocol (below), and a lock-free host takes
   the same hold by CAS for a write that raises a tally count (`with-held-write*`).
5. **Portable by construction** — the shared code (`eve/src`) names no host but one file, `src/eve/shared_atom.cljs`
   (`.host-leak`: conditionals 0, predicates 0, net-interop 0, host-files 1; `scripts/check-host-leak` lets each
   count only go down). What a host must supply is the adapters under `eve/adapters/`, one row of `hosts.edn` per host
   (`adapters/README.md`, "eve is portable").

---

## System Layers

```
┌──────────────────────────────────────────────────────────────────────────┐
│  User code            (swap! my-atom update :counter inc)                 │
├──────────────────────────────────────────────────────────────────────────┤
│  eve.alpha            e/atom, e/atom-domain, e/hash-map, e/deftype, ...    │
│  eve.atom             atoms and atom domains, in-process or on disk        │
│  eve.net.atom         network atoms (synchronous, blobs direct to a store) │
│  eve.thread           the executor service (pools, future, pmap, fold)     │
├──────────────────────────────────────────────────────────────────────────┤
│  Data structures      eve.map (HAMT) · eve.vec · eve.set · eve.list        │
│                       eve.deftype.int-map · eve.deftype.rb-tree ·           │
│                       eve.sorted · eve.rrb-vector · eve.obj · eve.array     │
├──────────────────────────────────────────────────────────────────────────┤
│  eve.codec            values ⇄ EVE bytes      (adapter: codec/byte-array,  │
│                                                codec/js)                    │
│  eve.deftype-proto.alloc   the slab allocator: 6 fixed classes + overflow  │
│  eve.slab-io          the memory seam every slab access goes through       │
│                       (adapter: slab-io/protocol, slab-io/vs)              │
├──────────────────────────────────────────────────────────────────────────┤
│  eve.mem              IMemRegion, IHostAtomics (the region contract)       │
│  eve.region           a host's regions (adapter: region/<impl>)            │
├──────────────────────────────────────────────────────────────────────────┤
│  the host's adapters  eve.host · eve.encoding · eve.process · eve.types ·  │
│                       eve.core · eve.int32 · eve.crypto · eve.http ·       │
│                       eve.thread-host · eve.process-slab                    │
└──────────────────────────────────────────────────────────────────────────┘
```

Every layer above the adapters is one `.cljc` source for every host. `doc/adopter-tiers.md` lists the
sixteen adapter features, which host takes which implementation, and which a new host must write.

---

## Atom Domains

An **atom domain** is a set of regions holding named atoms; it is itself an atom (its slot 0, the registry). Atoms
are slots in one domain's root region. Two kinds, one implementation (`eve.atom`'s `MmapAtom` and `MmapAtomDomain`
on every host):

### In-process

Created by `e/atom` without `:persistent`. Each `:id` gets its own heap domain, cached in this process. The regions
are the host's heap regions — on the JVM family a heap `ByteBuffer` with VarHandle atomics
(`adapters/region/jvm-varhandle`); on ClojureScript and nbb the module slabs' SharedArrayBuffers
(`adapters/slab/module`), which `eve.sab/sab-transfer-data` hands to a worker. One participant, no heartbeat.

### On disk (cross-process)

Created by `e/atom` with `:persistent`, or `eve.atom/persistent-atom-domain`.

- **Backing:** `mmap`'d files (`MAP_SHARED`) — every mapping of a file sees every write, in and across processes.
- **Files:** `.slab0`–`.slab5` and their bitmaps `.slab0.bm`–`.slab5.bm`, `.slab6` (overflow), `.root` (header, worker
  registry, atom table), `.rmap` (262,144 bytes); `.keyring` for an encrypted domain (`eve.keyring`).
- **Concurrency:** the root pointer's CAS is the host region's atomic: VarHandle on the JVM, `__atomic_*` built-ins in
  eve's N-API addon on Node (`native/mmap_cas.cc`, which uses `futex(2)` for wait/notify), the platform's atomics
  through each host's region adapter elsewhere.
- **Participants:** each process claims one of 256 worker slots in `.root` and writes a heartbeat every 10 s
  (`eve.process/start-timer!`); a slot whose heartbeat is 30 s old is stale.
- **Create or join:** the creator is the one process whose exclusive create of `<path>.root` succeeds
  (`eve.process/create-exclusive!`); every other joins and waits for the root's magic, written last.

### Lock-only hosts

A host whose regions have no atomic read-modify-write (babashka's `region/jvm-nio-lock`) answers
false to `IHostAtomics/-atomic-rmw?`. It writes under the **exclusive hold**: a byte-range file lock on the root's hold
word, the hold set, the other participants' in-flight writes drained (lock-free hosts count each write into
`ROOT_INFLIGHT` and back off while the hold is set), the write done with plain loads and stores, the hold cleared
(`eve.atom` `with-exclusive-hold*`). Both kinds of host share one domain.

---

## The Slab Allocator

Six size-stratified classes hold fixed-size blocks; a seventh handles overflow (> 1024 B):

| Class | Block Size | Typical Contents |
|---|---|---|
| 0 | 32 B | small HAMT bitmap nodes |
| 1 | 64 B | common HAMT nodes |
| 2 | 128 B | medium nodes, vector trie nodes |
| 3 | 256 B | dense nodes, collision nodes, encoded values |
| 4 | 512 B | very dense or large collision nodes |
| 5 | 1024 B | large nodes, big payloads |
| 6 | variable | coalescing overflow allocator (blocks > 1024 B) |

Each slab (classes 0–5) has a 64-byte header (magic, block size, total blocks, free count, alloc cursor, class index,
bitmap offset, data offset), a bitmap (1 bit per block, 0 free, 1 allocated) and a data region of uniform blocks. A
disk domain's slabs start small and double on demand (`:capacities` sets the initial sizes, in bytes); ClojureScript's
module slabs grow when growable (a browser's default, `:growable? true` on Node), up to `SLAB_CLASS_CAPACITIES`; a
JVM-family heap domain's classes are fixed (1 MB each unless `:capacities` says otherwise) and a full class spills to
the next. Sparse files and lazy page commit mean untouched pages cost neither memory nor disk on Linux.

### Slab-Qualified Offsets

Every node reference is a 32-bit **slab-qualified offset**:

```
[class_idx:3 bits | block_idx:29 bits]
```

- `class_idx` 0–5: the fixed classes; 6: the overflow allocator; 7: reserved.
- The NIL sentinel is `-1`; a claimed-but-empty atom slot holds `-2`.

### The Root Pointer

Each atom's state is one 32-bit root pointer in its slot of the root region's atom table. It is a slab-qualified
offset to the root node (or scalar block) of the atom's value. A `swap!` publishes a new value by CAS-ing this word;
the trees behind it are immutable and structurally shared.

---

## HAMT (Hash Array Mapped Trie)

Eve maps and sets are 32-way branching HAMTs with bitmap-indexed nodes:

- **Hash:** Murmur3_x86_32 over the key's encoded bytes (`eve.hamt-util/portable-hash-bytes`, through `eve.int32`) —
  the same 32-bit result on every host, so every host builds the same tree shape.
- **Node types:** bitmap nodes (sparse 32-way branch) and collision nodes (one hash, several keys).
- **Structural sharing:** updating one key allocates O(log32 N) new nodes along the root-to-leaf path; the rest of
  the tree is shared with the previous version.

### Bitmap Node Layout

```
[type:u8][flags:u8][kv_total_size:u16][data_bitmap:u32][node_bitmap:u32]   ; 12-byte header
[child offsets: 4 × popcount(node_bitmap)]
[hashes: 4 × popcount(data_bitmap)]
[inline KV data ...]
```

(`src/eve/map.cljc`: `NODE_HEADER_SIZE` 12, `hashes-start-off`, `kv-data-start-off`.) `data_bitmap` marks the slots
holding inline key-value entries, `node_bitmap` those holding children; child offsets are slab-qualified pointers.

---

## Reads and the CAS Loop

**Deref** reads the atom's root pointer under a pinned epoch and **materializes** the value — `d/eve->clj` builds the
host's own collections — then unpins (`plain-deref`). Slab-backed values do not escape a deref. To work on the value in
place, use the function passed to `swap!`, or the read region `eve.alpha/$`: inside it a deref answers the slab-backed
value (`region-deref`), the domain pinned on its first read and every pin held until the outermost `$` exits
(`eve.read-region`), and the region's result is materialized before they go.

**`swap!`** (`mmap-swap!` / `swap-attempt!` in `src/eve/atom.cljc`):

1. Pin the current epoch for this thread (the worker slot carries the minimum of its threads' pins).
2. Load the atom's root pointer.
3. Read the old value lazily — slab-backed, over a `LoggingSlabIO` that records this attempt's allocations.
4. Apply `(f old-value args...)`; collections built inside `f` allocate in this domain.
5. Write the new value (the codec), giving a new root pointer. An identical pointer ends the swap: nothing to publish.
6. Plan the commit under the pin (`eve.retire/plan`): the walk of the old value against the new gives the blocks to
   release and the tally counts (`eve.tally`) to raise and lower. A plan that raises a count outside the domain's
   exclusive hold is undone (its allocations freed) and the write runs again under the hold (`with-held-write*`: a
   lock-free host takes `ROOT_HOLD` by CAS). The new blocks' counts are written.
7. CAS the root pointer. **On success:** bump the domain epoch, apply the plan's raises and lowers (`retire/apply!`; a
   lower that takes a piece's last holder releases it), and queue the released blocks with the new epoch for the
   flush.
8. **On failure:** reset the new blocks' counts, free every block this attempt allocated (never published), back off —
   the first three retries at once, then 1..min(2^(n−3), 8) ms jittered — and retry.

At most 1,000 attempts (`MAX_SWAP_RETRIES`); a lock-only host runs steps 1–8 under the exclusive hold.

---

## Reclamation

What a committed write superseded is queued at the write with the epoch it was published at; the queue is flushed
when its last flush is 50 ms old or it holds more than 64 entries, freeing the entries whose epoch is below every
live pinned epoch (stale slots are skipped). A freed block is forgotten, never zeroed: its next owner writes every
field it uses. The model eve follows — and the places eve's code diverges from it today — are
**`doc/memory-model.md`**.

---

## Multi-Atom Domains

A domain holds up to **256 atom slots**: slot 0 is the registry (a map from atom name to slot index), so **255 named
atoms**; each meta-atom (`eve.atom/meta-atom`) is a named atom too. The `.root` file:

| Region | Size | Purpose |
|---|---|---|
| Header | 64 B | magic (`ROOT` V2 = `0x524F4F55`), root pointer, epoch counter, hold and in-flight words, tally word (byte 32) |
| Worker registry | 6,144 B | 256 slots × 24 B (status, current epoch, heartbeat lo/hi, worker id) |
| Atom table header | 64 B | magic (`ATOM`), table metadata |
| Atom table | 2,048 B | 256 slots × 8 B (root pointer, FNV-1a hash of the name) |

**Total root file: 8,320 bytes.**

Creating a named atom: claim a free slot by CAS (the claimed sentinel `-2`), write the initial value into it, then
register the name with one swap of the registry. Of two processes creating one name at once exactly one registers
it; the loser frees its slot and opens the winner's (`create-mmap-atom!`).

---

## File Layout (Disk Domains)

A domain at path `./my-db` creates:

```
./my-db.slab0      # 32-byte blocks          ./my-db.slab0.bm   # its bitmap
./my-db.slab1      # 64-byte blocks          ./my-db.slab1.bm
./my-db.slab2      # 128-byte blocks         ./my-db.slab2.bm
./my-db.slab3      # 256-byte blocks         ./my-db.slab3.bm
./my-db.slab4      # 512-byte blocks         ./my-db.slab4.bm
./my-db.slab5      # 1024-byte blocks        ./my-db.slab5.bm
./my-db.slab6      # variable-size overflow (coalescing allocator)
./my-db.root       # root pointers, worker registry, atom table (8,320 B)
./my-db.rmap       # reader map (262,144 B)
./my-db.keyring    # only for an encrypted domain (eve.keyring)
```

The slab header is at the start of each data file; each bitmap is its own file (`.bm`).

---

## IMemRegion

`eve.mem/IMemRegion` is the platform-neutral contract over a fixed-size region (HOST-CONTRACT §1–2): signed i32
load/store at any alignment, i32 CAS returning the witness, add/sub, i64 load/store/CAS (for the overflow header),
wait/notify on an i32 (a bounded poll and a 0-returning notify are legal), and bulk byte read/write.
`IHostAtomics` says whether the region's read-modify-writes are atomic (`-atomic-rmw?`) and gives a file lock
(`-with-file-lock`) where they are not.

Implementations (`eve.region`, one adapter per host capability):

| Adapter | Host(s) | Type(s) |
|---|---|---|
| `region/jvm-varhandle` | JVM | `JvmByteBufferRegion` (heap `ByteBuffer` or `MappedByteBuffer`, VarHandle atomics), `LustreJvmMmapRegion` |
| `region/jvm-nio-lock` | babashka | `BbMmapRegion`, `BbHeapRegion` (lock-only: a `FileChannel` byte-range lock) |
| `region/js` | ClojureScript, nbb | `JsSabRegion` (SharedArrayBuffer + Atomics), `NodeMmapRegion` (eve's N-API addon), `LustreMmapRegion` |
| `region/clr` | ClojureCLR | `ClrRegion` (`MemoryMappedFile`, `Interlocked`, futex) |
| `region/ctypes` | basilisp | `LpyRegion` (Python `mmap`, `ctypes` + libatomic, futex) |
| `region/jolt-ffi` | jolt | `JoltRegion` (`jolt.ffi` over mmap, libatomic, futex) |
| `region/beam` | clojerl | `CljeRegion` (eve's NIF, `adapters/core/beam/native/eve_clje_nif.c`) |
| `region/glojure` | glojure | `GljRegion`, `GljLockedRegion` (eve's Go region package in glj-eve) |
| `region/vs` | ValueScript | an address in the program's linear memory |

---

## ISlabIO and the Memory Seam

`eve.deftype-proto.alloc/ISlabIO` is the slab-block I/O the data structures are written against:

```clojure
(defprotocol ISlabIO
  (-sio-read-u8     [ctx slab-offset field-off])
  (-sio-write-u8!   [ctx slab-offset field-off val])
  (-sio-read-u16    [ctx slab-offset field-off])
  (-sio-write-u16!  [ctx slab-offset field-off val])
  (-sio-read-i32    [ctx slab-offset field-off])
  (-sio-write-i32!  [ctx slab-offset field-off val])
  (-sio-cas-i32!    [ctx slab-offset field-off expected desired])
  (-sio-read-bytes  [ctx slab-offset field-off len])
  (-sio-write-bytes! [ctx slab-offset field-off src])
  (-sio-alloc!      [ctx size-bytes])
  (-sio-free!       [ctx slab-offset])
  (-sio-copy-block! [ctx dst-slab-offset dst-field-off src-slab-offset src-field-off len]))
```

- `slab-offset` is always slab-qualified; `field-off` a byte offset within the block.
- `JvmSlabCtx` (in alloc, every host) holds one `IMemRegion` per slab class; ClojureScript's module slab registers its
  own (`CljsSlabIO`, `adapters/slab/module`) with `alloc/register-host-slab!`. `LoggingSlabIO` wraps one to record a
  swap attempt's allocations.
- **Shared code never calls these protocol methods directly.** Every slab access goes through `eve.slab-io`
  (HOST-CONTRACT §12): `adapters/slab-io/protocol` expands each name to the protocol call on every host but
  ValueScript; `adapters/slab-io/vs` is plain functions over linear memory. `adapters/slab-io/test` is the conformance
  test both answer.

---

## Performance

This document carries no performance figures: none of those it used to carry had a date, a commit or a command
behind them. Benchmarks that can measure eve on this tree are the `deps.edn` aliases `:native-build-atom`,
`:native-profiled-stress`, `:native-x-stress-atom`, `:native-data-bench`, `:contention-bench` and `:columnar-bench`
(`bench/eve/`, `test/eve/columnar_bench.cljc`), and `bb bench:bb` / `bb bench:nbb`; a figure published from one names
its date, commit, host and command. The dated tables in the docs are `doc/hosts/`'s per-host performance tables and
`doc/data-structures.md`'s columnar comparison (both from the final pass, 2026-10-21 ET); `doc/adopting-eve-fast.md`
dates its seam bench runs.
