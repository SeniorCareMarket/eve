# Eve Internals

Deep dive into the slab allocator, serialization format, HAMT implementation, native addon, and GC mechanics.

---

## Slab Allocator

### Overview

Eve uses a multi-slab architecture where each size class gets its own `SharedArrayBuffer` (CLJS) or mmap file (JVM). This eliminates coalescing for fixed-size blocks and enables bitmap-based free tracking with SIMD acceleration.

### Slab Header (64 bytes, cache-line aligned)

Each slab's data file begins with a 64-byte header:

| Offset | Field | Type | Description |
|---|---|---|---|
| 0 | `magic` | u32 | `0x534C4142` (`'SLAB'` in ASCII) |
| 4 | `block_size` | u32 | Block size in bytes (32, 64, 128, 256, 512, 1024) |
| 8 | `total_blocks` | u32 | Number of blocks currently available |
| 12 | `free_count` | u32 | Atomic counter of free blocks |
| 16 | `alloc_cursor` | u32 | Hint for next allocation scan start |
| 20 | `class_idx` | u32 | Size class index (0-5) |
| 24 | `bitmap_offset` | u32 | Byte offset where bitmap starts |
| 28 | `data_offset` | u32 | Byte offset where data region starts |
| 32-63 | reserved | — | Reserved for future use |

### Bitmap

- 1 bit per block: `0` = free, `1` = allocated.
- Aligned to 16 bytes for SIMD `v128` loads.
- Size: `ceil(total_blocks / 8)` bytes, padded to 16B.

**SIMD scanning (CLJS):** `v128.load` bitmap bytes → `v128.not` → find first non-zero byte via `i8x16.bitmask` + `ctz`. Scans 128 blocks per SIMD iteration. For a slab with 16,384 blocks, the full bitmap is 2KB = 128 `v128` loads worst case.

### Data Region

Blocks are contiguous and uniformly sized:
```
Block N starts at: data_offset + N * block_size
```

### Allocation Algorithm

1. Read `alloc_cursor` from the slab header.
2. Scan the bitmap starting at the cursor position for a free bit (0).
3. Attempt to set the bit atomically (CAS the bitmap word).
4. On success: decrement `free_count`, update `alloc_cursor`, return the slab-qualified offset.
5. On failure (another thread/process claimed it): continue scanning.
6. If the slab is full and below max capacity: grow the slab (double `total_blocks`).

### Lazy Growth

Slabs start small and grow on demand:

| Class | Block Size | Initial Capacity | Max Capacity | Max Blocks |
|---|---|---|---|---|
| 0 | 32 B | 64 KB (2K blocks) | 1 GB (32M blocks) |
| 1 | 64 B | 64 KB (1K blocks) | 1 GB (16M blocks) |
| 2 | 128 B | 32 KB (256 blocks) | 1 GB (8M blocks) |
| 3 | 256 B | 32 KB (128 blocks) | 512 MB (2M blocks) |
| 4 | 512 B | 16 KB (32 blocks) | 256 MB (512K blocks) |
| 5 | 1024 B | 16 KB (16 blocks) | 256 MB (256K blocks) |

Growth doubles via CAS on the header's `total_blocks` field. On Linux/macOS, sparse files and lazy mmap page commit mean the bitmap and data regions can be sized for max capacity at init time — untouched pages cost zero physical memory or disk.

### Split Files (mmap)

For mmap-backed slabs, bitmap and data live in separate files:
- `<domain>.slab<N>` — header + data region
- `<domain>.slab<N>.bm` — bitmap only

This allows independent growth and simpler mmap management. The `data_offset` in the header is always `SLAB_HEADER_SIZE` (64 bytes) for mmap slabs.

---

## Overflow Allocator (Class 6)

Blocks larger than 1024 bytes are handled by a coalescing allocator in `.slab6`:

### File Layout

```
┌──────────────────────────────────────────────────────┐
│ Header (64 bytes, same field offsets as slab header)  │
│   magic: 0x534C4142, block_size: 1, class_idx: 6     │
├──────────────────────────────────────────────────────┤
│ Descriptor Table (max_desc × 28 bytes)                │
│   Default: 16,384 descriptors                         │
├──────────────────────────────────────────────────────┤
│ Data Region (remaining space)                         │
└──────────────────────────────────────────────────────┘
```

### Descriptor Layout (28 bytes = 7 × i32)

| Offset | Field | Description |
|---|---|---|
| 0 | STATUS | `FREE=0`, `ALLOCATED=1`, `ZEROED_UNUSED=-1` |
| 4 | DATA_OFFSET | Byte offset relative to data region start |
| 8 | DATA_LENGTH | Used bytes (informational) |
| 12 | BLOCK_CAPACITY | Total capacity of this contiguous block |
| 16 | RESERVED | Always -1 |
| 20 | LOCK_OWNER | 0=unlocked, nonzero=locked |
| 24 | RESERVED2 | Always 0 |

On free, adjacent `FREE` blocks are coalesced to reduce fragmentation. Since `SLAB_SIZES[6]=1`, the block index directly equals the byte offset in the data region.

---

## Serialization Format

### Fast-Path Tags

Eve uses a two-byte magic prefix (`0xEE 0xDB`) followed by a type tag:

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
| `0x0B` | UUID | `[0xEE][0xDB][0x0B][16 bytes]` (19 bytes) |
| `0x0C` | short symbol | `[0xEE][0xDB][0x0C][len:u8][name bytes]` |
| `0x0D` | ns symbol (short) | `[0xEE][0xDB][0x0D][ns-len:u8][ns][name-len:u8][name]` |
| `0x0E` | Date | `[0xEE][0xDB][0x0E][millis:f64 LE]` (11 bytes) |
| `0x0F` | int64 | `[0xEE][0xDB][0x0F][i64 LE]` (11 bytes) |

### SAB Pointer Tags (in-process only)

Nested collections stored as SAB data structures (zero-copy references):

| Tag | Type | Format |
|---|---|---|
| `0x10` | SAB Map | `[0xEE][0xDB][0x10][offset:i32]` (7 bytes) |
| `0x11` | SAB Set | `[0xEE][0xDB][0x11][offset:i32]` (7 bytes) |
| `0x12` | SAB Vec | `[0xEE][0xDB][0x12][offset:i32]` (7 bytes) |
| `0x13` | SAB List | `[0xEE][0xDB][0x13][offset:i32]` (7 bytes) |

**Important:** Tags `0x10`-`0x13` are SAB-only and must never appear in cross-process atom serialization.

### Flat Collection Tags (cross-process safe)

Self-contained binary encoding readable by both JVM and Node.js:

| Tag | Type | Format |
|---|---|---|
| `0xED` | Flat Map | `[0xEE][0xDB][0xED][count:i32 LE]([k-len:i32][k-bytes][v-len:i32][v-bytes])*` |
| `0xEF` | Flat Vec | `[0xEE][0xDB][0xEF][count:i32 LE]([e-len:i32][e-bytes])*` |

### Special Tags

| Tag | Type | Format |
|---|---|---|
| `0x1A` | Record | `[0xEE][0xDB][0x1A][sab-map-offset:i32]` (7 bytes) |
| `0x1B` | Typed Array | `[0xEE][0xDB][0x1B][subtype:u8][byte-length:u32][raw bytes]` |
| `0x1C` | Eve Array | `[0xEE][0xDB][0x1C][block-offset:i32]` (7 bytes) |

### Scalar Block (atom root)

When a primitive value is stored at the atom root:
```
[0x01][EVE-serialized-bytes...]
```

### Keyword Cache (CLJS optimization)

Keywords are interned in CLJS, so Eve caches their serialized form to avoid repeated `TextEncoder` + allocation overhead. Two reusable scratch buffers (A/B) support serializing key+value simultaneously, eliminating per-call `ArrayBuffer` + `DataView` allocations.

---

## HAMT Implementation

### Hash Function

Murmur3_x86_32 (seed=0) over serialized key bytes. Implemented identically in CLJS and JVM to produce the same trie shape on both platforms. Input is the Eve-serialized byte representation of the key (not the Clojure hash).

### Trie Navigation

- 32-way branching: 5 bits of hash per level.
- `SHIFT_STEP = 5`, `MASK = 0x1f`.
- Maximum depth: 7 levels (32 bits / 5 bits per level, with 2 spare bits at the deepest level handled by collision nodes).

### Bitmap Node Layout

```
Offset  Size   Field
0       1      type (NODE_TYPE_BITMAP = 1)
1       1      flags
2       2      kv_total_size (u16) — total bytes of inline KV data
4       4      data_bitmap (u32) — which slots have inline key-value data
8       4      node_bitmap (u32) — which slots have child node pointers
12      var    inline KV data (serialized key-value pairs)
12+kv   var    child offsets (slab-qualified i32 per set bit in node_bitmap)
```

Header size: 12 bytes.

### Collision Node Layout

```
Offset  Size   Field
0       1      type (NODE_TYPE_COLLISION = 3)
1       1      reserved
2       2      count (u16) — number of entries
4       4      hash (i32) — the shared hash value
8       var    serialized key-value pairs
```

Header size: 8 bytes.

### EveHashMap Header

Stored as the atom root or as a nested collection reference:

```
Offset  Size   Field
0       1      type-id (0xED)
1       1      pad
2       2      pad
4       4      count (i32) — total number of entries
8       4      root-off (i32) — slab-qualified offset to root bitmap node
```

Header size: 12 bytes.

### Structural Sharing

When `swap!` updates one key in a map, only the O(log32 N) nodes along the path from root to leaf are reallocated. All other nodes are shared with the previous version. For a 1,000-key map, this means ~2-3 new nodes per update.

---

## Native Addon (mmap_cas.cc)

The native addon provides file-backed `MAP_SHARED` mmap + atomic int32/int64 operations for Node.js.

### Exported Functions

| Function | Signature | Description |
|---|---|---|
| `open` | `(path, size) -> Buffer` | Map or create a file. Returns a Buffer backed by mmap'd memory. |
| `load32` | `(buf, off) -> number` | Atomic load (acquire) |
| `store32` | `(buf, off, val)` | Atomic store (release) |
| `cas32` | `(buf, off, expected, desired) -> number` | Compare-and-swap. Returns old value. |
| `add32` | `(buf, off, delta) -> number` | Atomic add. Returns old value. |
| `sub32` | `(buf, off, delta) -> number` | Atomic sub. Returns old value. |
| `wait32` | `(buf, off, expected, timeoutMs) -> string` | Futex wait. Returns `"ok"`, `"not-equal"`, or `"timed-out"`. |
| `notify32` | `(buf, off, count) -> number` | Futex wake. Returns threads woken. |

### Implementation Details

- Uses `__atomic_*` GCC/Clang built-ins (not `std::atomic_ref`) for Apple Clang compatibility.
- Linux: `futex(2)` with `FUTEX_WAIT_BITSET` on `CLOCK_MONOTONIC` (absolute deadline, race-free).
- No `FUTEX_PRIVATE_FLAG`: cross-process futex uses inode-based keying.
- macOS/Windows: stub fallback (spin with short sleep).
- mmap cleanup via GC-triggered Deleter via `Napi::Buffer::New` with custom deleter.
- Compiled with C++20, via `node-gyp` (`binding.gyp`).

---

## Worker Registry

Each domain maintains a worker registry with 256 slots:

### Worker Slot Layout (24 bytes)

| Offset | Field | Type | Description |
|---|---|---|---|
| 0 | STATUS | u32 | `INACTIVE=0`, `ACTIVE=1`, `STALE=2` |
| 4 | CURRENT_EPOCH | u32 | Epoch this worker is currently reading |
| 8 | HEARTBEAT_LO | u32 | Low 32 bits of heartbeat timestamp |
| 12 | HEARTBEAT_HI | u32 | High 32 bits of heartbeat timestamp |
| 16 | WORKER_ID | u32 | Unique worker identifier |
| 20 | RESERVED | u32 | Reserved |

Workers write heartbeats periodically:
- Node.js: via `setInterval`
- JVM: via `ScheduledExecutorService`

Stale workers (no heartbeat for 30s) are ignored during epoch scans and their slots can be reclaimed.

---

## Epoch GC Details

### Retire Queue

After a successful `swap!`, old nodes that are no longer reachable from the new root are placed in a retire queue, tagged with the epoch at the time of retirement.

### Flush Protocol

Flushing is time-throttled (default: 50ms interval) to avoid expensive 256-slot scans on every swap:

1. Scan all 256 worker slots.
2. For each `ACTIVE` worker, read its `CURRENT_EPOCH`.
3. Compute the minimum epoch across all active workers.
4. For each entry in the retire queue with epoch < minimum: free the slab block.

### Thread-Local Alloc Log (JVM)

On the JVM, a `ThreadLocal<ArrayList>` tracks all blocks allocated during a `swap!`. If CAS fails, every block in the log is freed. On success, the log is drained and old blocks are retired instead.

---

## Root File Format (V2)

```
Offset    Size      Region
0         64 B      Header (magic, atom ptr, epoch, worker reg offset)
64        6,144 B   Worker Registry (256 × 24 B)
6,208     64 B      Atom Table Header (magic: 0x41544F4D)
6,272     2,048 B   Atom Table (256 × 8 B slots)
                    Total: 8,320 bytes
```

### Header Fields

| Offset | Field | Description |
|---|---|---|
| 0 | MAGIC | `0x524F4F55` (V2) |
| 4 | ATOM_PTR | Legacy root pointer (V1 compat) |
| 8 | EPOCH | Global epoch counter |
| 12 | WORKER_REG_OFFSET | Byte offset to worker registry |
| 16 | ATOM_TABLE_OFFSET | Byte offset to atom table |
| 20 | ATOM_TABLE_CAPACITY | Max atom slots |

### Atom Slot (8 bytes)

| Offset | Field | Description |
|---|---|---|
| 0 | PTR | Slab-qualified root pointer (CAS target) |
| 4 | HASH | FNV-1a hash of the keyword name |
