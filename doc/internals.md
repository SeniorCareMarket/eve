# Eve Internals

The slab allocator, the serialization format, the HAMT, the native addon and the worker registry, byte by byte.
Reclamation — what a write frees and when — is `doc/memory-model.md`; the decisions behind the bytes are
`doc/unification/FORMAT-DECISIONS.md`.

---

## Slab Allocator

### Overview

A domain has six fixed-size classes (32–1024 B) and an overflow class. On disk each class is its own data file and
bitmap file; in process each class is a heap region (the JVM family) or a module SharedArrayBuffer (ClojureScript,
nbb). Fixed-size blocks need no coalescing, and free tracking is one bit per block.

### Slab Header (64 bytes, cache-line aligned)

Each slab's data begins with a 64-byte header (`eve.deftype-proto.data`, `SLAB_HDR_*`):

| Offset | Field | Type | Description |
|---|---|---|---|
| 0 | `magic` | u32 | `0x534C4142` (`'SLAB'` in ASCII) |
| 4 | `block_size` | u32 | Block size in bytes (32, 64, 128, 256, 512, 1024) |
| 8 | `total_blocks` | u32 | Number of blocks currently available |
| 12 | `free_count` | u32 | Atomic counter of free blocks |
| 16 | `alloc_cursor` | u32 | Hint for the next allocation scan's start |
| 20 | `class_idx` | u32 | Size class index (0–5) |
| 24 | `bitmap_offset` | u32 | Byte offset where the bitmap starts |
| 28 | `data_offset` | u32 | Byte offset where the data region starts |
| 32–63 | reserved | — | Reserved |

### Bitmap

- 1 bit per block: `0` free, `1` allocated.
- Size `ceil(total_blocks / 8)` bytes, padded to 16 B.
- Shared code reaches it through `eve.slab-io`'s fused primitives (`bitmap-find-free`, `bitmap-alloc-cas!`,
  `bitmap-free!`). On ClojureScript and nbb the module slab scans with WASM SIMD (`v128` loads, 128 blocks an
  iteration; `adapters/slab/module/src/eve/deftype_proto/wasm.cljs`); elsewhere the scan is `eve.mem`'s word loop.

### Data Region

Blocks are contiguous and uniformly sized:
```
Block N starts at: data_offset + N * block_size
```

### Allocation

1. Read `alloc_cursor` from the slab header.
2. Scan the bitmap from the cursor for a free bit.
3. Set the bit by CAS on its bitmap word.
4. On success: decrement `free_count`, advance `alloc_cursor`, return the slab-qualified offset.
5. On a lost CAS (another thread or process took it): continue scanning.
6. If the slab is full and below its maximum capacity: grow it (double `total_blocks`, one leader by CAS).

### Lazy Growth

A disk domain's slabs start small and double on demand (`SLAB_CLASS_INITIAL_CAPACITIES` in
`src/eve/deftype_proto/data.cljc`; `:capacities` sets a disk domain's initial sizes, in bytes). The maxima
(`SLAB_CLASS_CAPACITIES`) bound ClojureScript's module slabs, which grow when growable (a browser's default,
`:growable? true` on Node); a JvmSlabCtx disk class doubles with no cap (`max-blocks` is not read by its growth), and
a JVM-family heap domain's classes are fixed (1 MB each unless `:capacities` says otherwise; a full class spills to
the next):

| Class | Block Size | Initial Capacity | Max Capacity |
|---|---|---|---|
| 0 | 32 B | 64 KB (2K blocks) | 1 GB (32M blocks) |
| 1 | 64 B | 64 KB (1K blocks) | 1 GB (16M blocks) |
| 2 | 128 B | 32 KB (256 blocks) | 1 GB (8M blocks) |
| 3 | 256 B | 32 KB (128 blocks) | 512 MB (2M blocks) |
| 4 | 512 B | 16 KB (32 blocks) | 256 MB (512K blocks) |
| 5 | 1024 B | 16 KB (16 blocks) | 256 MB (256K blocks) |

Growth doubles by CAS on the header's `total_blocks`. Bitmap and data offsets are computed for the maximum capacity
at init, so the data region never moves; on Linux sparse files and lazy page commit mean untouched pages cost neither
memory nor disk.

### Split Files (disk domains)

On disk, bitmap and data live in separate files:
- `<domain>.slab<N>` — header + data region
- `<domain>.slab<N>.bm` — bitmap only

The `data_offset` in the header is always `SLAB_HEADER_SIZE` (64 bytes) for a disk slab.

---

## Overflow Allocator (Class 6)

Blocks larger than 1024 bytes go to a coalescing allocator in `.slab6` (`src/eve/deftype_proto/coalesc.cljc`):

### File Layout

```
┌──────────────────────────────────────────────────────┐
│ Header (64 bytes, the slab header's field offsets)    │
│   [0..3]   magic: 0x534C4142 ('SLAB')                │
│   [4..7]   block_size: 1 (class 6 convention)        │
│   [8..11]  max_descriptors: u32                       │
│   [12..15] free_count: u32                            │
│   [16..19] alloc_cursor: u32 (descriptor scan hint)   │
│   [20..23] class_idx: 6                               │
│   [24..27] desc_table_offset: u32                     │
│   [28..31] data_offset: u32                           │
│   [32..39] data-region size: i64 (growth)             │
├──────────────────────────────────────────────────────┤
│ Descriptor Table (max_desc × 28 bytes)                │
│   Default: 16,384 descriptors                         │
├──────────────────────────────────────────────────────┤
│ Data Region (remaining space; up to 4 PiB by default) │
└──────────────────────────────────────────────────────┘
```

### Descriptor Layout (28 bytes = 7 × i32)

| Offset | Field | Description |
|---|---|---|
| 0 | STATUS | `FREE=0`, `ALLOCATED=1`, `ZEROED_UNUSED=-1` |
| 4 | DATA_OFFSET | Byte offset relative to the data region's start |
| 8 | DATA_LENGTH | Used bytes (informational, set by the caller) |
| 12 | BLOCK_CAPACITY | Total capacity of this contiguous block |
| 16 | RESERVED | `-1` |
| 20 | LOCK_OWNER | 0 unlocked, nonzero locked |
| 24 | RESERVED2 | `0` |

On free, adjacent `FREE` blocks are coalesced. Since `SLAB_SIZES[6] = 1`, a class-6 block index is the byte offset in
the data region.

---

## Serialization Format

Every encoded value starts with the two-byte magic `0xEE 0xDB`, then a type tag (`src/eve/deftype_proto/serialize.cljc`).
Lengths are byte lengths; multi-byte numbers are little-endian.

### Scalars

| Tag | Type | Format |
|---|---|---|
| `0x01` | `false` | `[0xEE][0xDB][0x01]` (3 bytes) |
| `0x02` | `true` | `[0xEE][0xDB][0x02]` (3 bytes) |
| `0x03` | int32 | `[0xEE][0xDB][0x03][i32 LE]` (7 bytes) |
| `0x04` | float64 | `[0xEE][0xDB][0x04][f64 LE]` (11 bytes) |
| `0x05` | short string | `[0xEE][0xDB][0x05][len:u8][UTF-8 bytes]` |
| `0x06` | long string | `[0xEE][0xDB][0x06][len:u32 LE][UTF-8 bytes]` |
| `0x07` | short keyword | `[0xEE][0xDB][0x07][len:u8][name bytes]` |
| `0x08` | long keyword | `[0xEE][0xDB][0x08][len:u32 LE][name bytes]` |
| `0x09` | ns keyword (short) | `[0xEE][0xDB][0x09][ns-len:u8][ns][name-len:u8][name]` |
| `0x0A` | ns keyword (long) | `[0xEE][0xDB][0x0A][ns-len:u32][ns][name-len:u32][name]` |
| `0x0B` | UUID | `[0xEE][0xDB][0x0B][16 bytes]` (19 bytes; msb then lsb, big-endian) |
| `0x0C` | short symbol | `[0xEE][0xDB][0x0C][len:u8][name bytes]` |
| `0x0D` | ns symbol (short) | `[0xEE][0xDB][0x0D][ns-len:u8][ns][name-len:u8][name]` |
| `0x0E` | Date | `[0xEE][0xDB][0x0E][millis:f64 LE]` (11 bytes) |
| `0x0F` | int64 | `[0xEE][0xDB][0x0F][i64 LE]` (11 bytes) |
| `0x14` | character | `[0xEE][0xDB][0x14][utf16:u16 LE]` (a one-character string on ClojureScript) |
| `0x16` | big integer | `[0xEE][0xDB][0x16][len:u32 LE][two's complement, big-endian, minimal]` |
| `0x17` | ratio | `[0xEE][0xDB][0x17][len:u32 LE][numerator][len:u32 LE][denominator]` (lowest terms) |
| `0x18` | big decimal | `[0xEE][0xDB][0x18][scale:i32 LE][len:u32 LE][unscaled]` (trailing zeros stripped) |

An integer is written by its magnitude (INT32, then INT64, then BIGINT), so `(= 5 5N)` is one key. Symbols longer than
255 bytes are refused (there is no long symbol form). FORMAT-DECISIONS §4, §8.

### References and Out-of-line Values

| Tag | Type | Format |
|---|---|---|
| `0x15` | reference | `[0xEE][0xDB][0x15][realm:u32 LE][handle:u32 LE]` — a value eve has no bytes for, named in this process's reference table; **never written to a shared domain** (FORMAT-DECISIONS §9) |
| `0x19` | value block | `[0xEE][0xDB][0x19][block:i32 LE][len:u32 LE]` — a map entry's value longer than 64 bytes (`VALUE_INLINE_MAX`), out of line in a scalar block (FORMAT-DECISIONS §10) |

### Slab Pointer Tags

A stored eve value is a reference to it in the slab, `[0xEE][0xDB][tag][offset:i32]` (7 bytes), its tag the target
header's type id (a map's and a set's excepted) — what `eve.retire` follows (`pointer-tag?`):

| Tag | Type | Format |
|---|---|---|
| `0x10` | map | `[0xEE][0xDB][0x10][offset:i32]` (7 bytes) |
| `0x11` | set | `[0xEE][0xDB][0x11][offset:i32]` (7 bytes) |
| `0x12` | vector | `[0xEE][0xDB][0x12][offset:i32]` (7 bytes) |
| `0x13` | list | `[0xEE][0xDB][0x13][offset:i32]` (7 bytes) |
| `0x1D` | eve array | `[0xEE][0xDB][0x1D][offset:i32]` (7 bytes; `0x1C` below is read too) |
| `0x1E` | eve object (`eve.obj`) | `[0xEE][0xDB][0x1E][offset:i32]` (7 bytes) |
| `0x1F` | eve value array | `[0xEE][0xDB][0x1F][offset:i32]` (7 bytes) |
| `0x40`–`0xEC` | an eve-deftype instance | `[0xEE][0xDB][type-id][offset:i32]` (7 bytes) |

The tag format is shared by every domain; only the backing store differs. In a disk domain the offsets point into the
mmap'd slabs, in an in-process domain into heap or SharedArrayBuffer slabs (`eve/CLAUDE.md`, Forbidden Patterns §3).
An offset means something only in its own domain, which is why an eve value built in one domain is refused by
another.

### Flat Collections

Self-contained encodings, the same bytes on every host (FORMAT-DECISIONS §2):

| Tag | Type | Format |
|---|---|---|
| `0xED` | flat map | `[0xEE][0xDB][0xED][count:i32 LE]([k-len:i32][k-bytes][v-len:i32][v-bytes])*` — entries in ascending key-byte order |
| `0xEE` | flat set | `[0xEE][0xDB][0xEE][count:i32 LE]([e-len:i32][e-bytes])*` — ascending element-byte order |
| `0xEF` | flat vector | `[0xEE][0xDB][0xEF][count:i32 LE]([e-len:i32][e-bytes])*` |

### Records and Arrays

| Tag | Type | Format |
|---|---|---|
| `0x1A` | record | `[0xEE][0xDB][0x1A][map-offset:i32]` (7 bytes) — the record's fields as an eve map with its record tag |
| `0x1B` | typed array | `[0xEE][0xDB][0x1B][subtype:u8][byte-length:u32][raw bytes]` |
| `0x1C` | eve array | `[0xEE][0xDB][0x1C][block-offset:i32]` (7 bytes) |

### Slab Block Type Ids

A value's header block (what a root pointer or a pointer tag names) begins with its type id; interior blocks (HAMT
nodes, trie nodes, list cells, the tally's count pages) are read by their parent's layout:

| Id | Block |
|---|---|
| `0x01` | scalar block — a primitive at an atom root, or a value block: `[0x01][EVE bytes...]` |
| `0x12` | vector header |
| `0x13` | list header |
| `0x1D` | eve array: `[0x1D][subtype:u8][pad:u16][count:u32][element bytes...]` |
| `0x1E` | eve object (`eve.obj`): `[0x1E][pad:u8][schema-len:u16][schema bytes...][fields, 4-aligned]` |
| `0x1F` | eve value array (`eve.deftype.array`) |
| `0x40`–`0xEC` | an eve-deftype instance (int-map and red-black nodes, user types), by its registered layout |
| `0xED` / `0xEE` | EveHashMap / EveHashSet root header (below) |
| `0xF0`–`0xFF` | a runtime's own process-private block kinds; never written to a shared domain |

### The ClojureScript Codec

ClojureScript and nbb take `adapters/codec/js` (typed arrays, DataViews); every other host takes
`adapters/codec/byte-array` (byte arrays through `eve.host` and `eve.encoding`). Both write the bytes above, pinned by
the fixtures every host runs (`test/eve/fixtures/eve-bytes.edn`, `test/eve/eve_bytes_fixture_test.cljc`). The JS codec
caches keywords' encoded form and reuses three scratch buffers (A and B for a key and a value; a 7-byte pointer
buffer); `doc/memory-model.md` lists that
scratch reuse among eve's divergences from the memory model.

---

## HAMT Implementation

### Hash Function

Murmur3_x86_32 (seed 0) over the key's encoded bytes (`eve.hamt-util/portable-hash-bytes`), its 32-bit arithmetic
through `eve.int32` — identical on every host, so every host builds the same trie. The input is the key's EVE bytes,
not the host's Clojure hash (which `hasheq` of an eve collection still answers, HOST-CONTRACT §8.11).

### Trie Navigation

- 32-way branching: 5 bits of hash per level (`SHIFT_STEP = 5`, `MASK = 0x1f`).
- Maximum depth: 7 levels, the deepest handled by collision nodes.

### Bitmap Node Layout

```
Offset            Size                      Field
0                 1                         type (NODE_TYPE_BITMAP = 1)
1                 1                         flags
2                 2                         kv_total_size (u16) — total bytes of inline KV data
4                 4                         data_bitmap (u32) — slots holding inline key-value entries
8                 4                         node_bitmap (u32) — slots holding child pointers
12                4 × popcount(node_bm)     child offsets (slab-qualified i32 each)
12 + 4·nc         4 × popcount(data_bm)     hashes of the inline entries
12 + 4·nc + 4·nd  var                       inline KV data (encoded key-value pairs)
```

Header 12 bytes (`src/eve/map.cljc`: `NODE_HEADER_SIZE`, `hashes-start-off`, `kv-data-start-off`). Fields inside a
node are at arbitrary alignment, which is why HOST-CONTRACT §2.1 requires a region's i32 load and store at any offset.

### Collision Node Layout

```
Offset  Size   Field
0       1      type (NODE_TYPE_COLLISION = 3)
1       1      count (u8) — number of entries
2       2      reserved (u16, 0)
4       4      hash (i32) — the shared hash value
8       var    encoded key-value pairs
```

Header 8 bytes.

### EveHashMap Header

The atom root, or a nested collection's target:

```
Offset  Size   Field
0       1      type-id (0xED)
1       3      pad
4       4      count (i32) — total number of entries
8       4      root-off (i32) — slab-qualified offset to the root bitmap node
```

Header 12 bytes.

### Structural Sharing

When `swap!` updates one key, only the O(log32 N) nodes on the path from root to leaf are reallocated; every other
node is shared with the previous version.

---

## Native Addon (`native/mmap_cas.cc`)

ClojureScript on Node and nbb reach file regions through eve's N-API addon (loaded by `node-gyp-build`; used by
`adapters/region/js`): file-backed `MAP_SHARED` mappings and atomic operations on them.

### Exported Functions

| Group | Functions |
|---|---|
| hardware atomics | `open` (map or create a file; a Buffer over the mapping), `load32`, `store32`, `cas32` (answers the old value), `add32`, `sub32` (old value), `load64`, `store64`, `cas64`, `add64`, `sub64`, `wait32` (`"ok"` / `"not-equal"` / `"timed-out"`), `notify32` (the count woken) |
| Lustre (fcntl byte-range locks) | `openWithFd`, `fcntlLoad32`, `fcntlStore32`, `fcntlCas32`, `fcntlAdd32`, `fcntlSub32`, `fcntlExchange32`, `fcntlLoad64`, `fcntlStore64`, `fcntlCas64`, `fcntlAdd64`, `fcntlSub64`, `closeFd` |
| misc | `nanosleep` |

### Implementation Details

- `__atomic_*` GCC/Clang built-ins (not `std::atomic_ref`) for Apple Clang compatibility.
- Linux: `futex(2)` with `FUTEX_WAIT_BITSET` on `CLOCK_MONOTONIC` (absolute deadline) for `wait32`/`notify32`; no
  `FUTEX_PRIVATE_FLAG`, so a `MAP_SHARED` futex keys by inode and works across processes.
- Non-Linux (Darwin): `wait32` polls the word with a 100 µs sleep until it changes or the deadline passes, and
  `notify32` wakes nothing (answers 0) — legal under HOST-CONTRACT §2.3. The addon is POSIX (`sys/mman.h`): there is
  no Windows build.
- The mapping is released by the Buffer's deleter when the Buffer is collected.
- Compiled with `-std=c++20` through `node-gyp` (`binding.gyp`, at `eve/`'s root).

clojerl's equivalent is its NIF, `adapters/core/beam/native/eve_clje_nif.c` (built on first load into
`adapters/core/beam/target/clje/`); glojure's is eve's Go region package compiled into glj-eve (`impls/glojure/region`).

---

## Worker Registry

Each domain's `.root` holds 256 worker slots:

### Worker Slot Layout (24 bytes)

| Offset | Field | Type | Description |
|---|---|---|---|
| 0 | STATUS | u32 | `INACTIVE=0`, `ACTIVE=1`, `STALE=2` |
| 4 | CURRENT_EPOCH | u32 | The epoch this participant is pinned at (0: none) |
| 8 | HEARTBEAT_LO | u32 | Low 32 bits of the heartbeat timestamp (ms) |
| 12 | HEARTBEAT_HI | u32 | High 32 bits |
| 16 | WORKER_ID | u32 | Participant identifier |
| 20 | RESERVED | u32 | Reserved |

A participant of a disk domain writes its heartbeat every 10 s through the host's `eve.process/start-timer!` (a
daemon thread on the JVM family, `setInterval` on Node, each host's timer). A slot whose heartbeat is 30 s old
(`HEARTBEAT_TIMEOUT_MS`) is stale: skipped when the minimum pinned epoch is computed, and reclaimable by a new
participant. Threads of one process share its slot, which carries the minimum of their pinned epochs. An in-process
domain has one participant and no heartbeat.

---

## Reclamation (summary)

After a committed `swap!`, `eve.retire`'s walk of the old value against the new (planned under the attempt's pin before
the CAS, applied after it) releases the blocks the old value held and the new one does not; a piece held at more than
one place carries a count (`eve.tally`) and is released only when its last holder drops it. The released blocks are
queued with the epoch the write published. A lost CAS resets its new blocks' counts and frees every block its attempt
allocated, which a per-attempt `LoggingSlabIO` records (`alloc/logging-sio`, on every host). The queue is flushed when
its last flush is 50 ms old (`FLUSH_INTERVAL_MS`) or it holds more than 64 entries (`FLUSH_QUEUE_THRESHOLD`): an entry
whose epoch is below the minimum live pinned epoch is freed. The model this follows, and where eve's code diverges from
it, is `doc/memory-model.md`.

---

## Root File Format (V2)

```
Offset    Size      Region
0         64 B      Header (magic, atom ptr, epoch, worker reg offset, atom table, hold and in-flight words, tally)
64        6,144 B   Worker Registry (256 × 24 B)
6,208     64 B      Atom Table Header (magic: 0x41544F4D 'ATOM')
6,272     2,048 B   Atom Table (256 × 8 B slots)
                    Total: 8,320 bytes
```

### Header Fields

| Offset | Field | Description |
|---|---|---|
| 0 | MAGIC | `0x524F4F55` (V2) |
| 4 | ATOM_PTR | legacy root pointer (V1) |
| 8 | EPOCH | the domain's epoch counter |
| 12 | WORKER_REG_OFFSET | byte offset to the worker registry |
| 16 | ATOM_TABLE_OFFSET | byte offset to the atom table |
| 20 | ATOM_TABLE_CAPACITY | atom slots |
| 24 | HOLD | the exclusive hold of a lock-only participant (`ROOT_HOLD_OFFSET`) |
| 28 | INFLIGHT | lock-free writes in flight (`ROOT_INFLIGHT_OFFSET`) |
| 32 | TALLY | 0, or 1 + the slab offset of the tally's directory block (`ROOT_TALLY_OFFSET`, `eve.tally`) |

### Atom Slot (8 bytes)

| Offset | Field | Description |
|---|---|---|
| 0 | PTR | slab-qualified root pointer (the CAS target); `-1` empty, `-2` claimed |
| 4 | HASH | FNV-1a hash of the atom's name (over its UTF-16 units & 0xFF) |

Slot 0 is the registry (atom name → slot index), so a domain holds 255 named atoms.
