# D4 — static Eve, format v1

Concrete enough to spike from. Every choice cites the evidence that made it (`ASSUMPTIONS.md` A-rows,
`MEASUREMENTS.md` M-sections, `census/`, `research/`). The prototype this was measured with is
`probes/pack.clj` (packed) and `probes/lib.clj` + `probes/java/staticprobe/ImageCopier.java` (image); where the spec
below differs from the prototype it says so.

## 1. What a static value is

A static value is an immutable Eve value held as bytes outside every domain: no slab, no epoch, no tally, no
allocator. It is a value, never state. It has **two byte forms in one container**, because the evidence did not
allow one:

| | **image** | **packed** |
|---|---|---|
| What the bytes are | every block the value reaches, byte-for-byte as the slab holds it, pointers rewritten to positions in the buffer | chunks: a symbol table, shapes, nodes, typed columns; scalars are Eve's scalar bytes |
| Job | leaving a domain and entering one — a deref snapshot, a crossing between domains or threads of one program, a build made off-allocator and then placed | the wire and storage form; columnar work; anything that crosses versions, hosts or the kernel |
| Out of a slab | a relocating block copy: 1.8–6.3× `eve->clj` (M5) | an encoder walk (not built; bounded below by the image's walk plus a dictionary probe per key) |
| Into a slab | one run claimed per size class, a `memcpy`, a pointer pass: 3–45× a plain `swap!` end to end (M6) | one block per chunk, by `memcpy` (§7) |
| Bytes | 2.6–7× EDN text (M2) | 0.55–0.86× Transit MessagePack on Eve-shaped data (M2) |
| Reader code | none new on the ISlabIO hosts: Eve's own types through a slab I/O over the buffer (M1) | new types, generated (§6) |
| Sharing inside a value | kept — a block reached twice is copied once (M7) | kept — a node is addressed by offset and may have several parents |

A value can move between the forms: `pack` (image or slab → packed) and `lay-out` (packed → image, then placed).
Both forms satisfy the same protocols and are `=` to each other, to their slab original and to plain data (§8).

## 2. The container

A **chunk** is the unit everywhere: the cache scope, the decode unit, the wire unit, the storage unit, and — in an
atom — one leaf block. A static value is one chunk, or several chunks and a directory. A stream or a file is a
sequence of chunks.

```
stream   := chunk*                       each chunk self-delimiting by its header's length
file     := chunk* directory-chunk tail
tail     := [directory offset u64 LE][magic "EVS1" u32][0 u32]        16 bytes, at the end
```

Chunk header — 32 bytes, little-endian, the chunk 8-aligned in whatever holds it:

| off | field | |
|---:|---|---|
| 0 | magic `45 56 53 31` ("EVS1") | constant for the life of the format (A1g of the format research: Vortex moved its magic twice before freezing it) |
| 4 | `version` u8 = 1 | a reader refuses a version above its own |
| 5 | `kind` u8 | 0 packed · 1 image · 2 directory · 3 raw bytes (a part of a large scalar; reserved) |
| 6 | `flags` u16 | bit 0 WIDE (offsets are u32; else u16) · bit 1 DIGEST (a 32-byte SHA-256 of the chunk, its own field zeroed, ends the chunk) · bit 2 TYPES (a type table is present) · bits 8–15 **required features**: a reader that does not know a set bit refuses the chunk |
| 8 | `length` u32 | bytes, header included; a multiple of 8 |
| 12 | `root` u32 | offset of the root node (packed) or root block (image) |
| 16 | `nsym` u16 · 18 `nshape` u16 | packed only |
| 20 | `symtab` u32 · 24 `shapetab` u32 · 28 `typetab` u32 | offsets; 0 = absent. In an image: the block table, the relocation table, the type table |

**64 KiB is a target, not a cap** (A7, Q12). A writer seals a chunk when the next row would pass 65,535 bytes; under
that, every offset in the chunk is 16 bits (M3: this is why 64 KiB is the smallest total on the datom shape). One
row or one scalar that cannot fit makes a WIDE chunk of whatever size it needs, up to 2^32 − 1 bytes. A value smaller
than a chunk is a short chunk: 32 bytes of header and its nodes. A scalar is never a static value — `static` of a
scalar is the scalar.

**Compression is not in a chunk.** Chunk by chunk it costs 23–35 % more bytes at 64 KiB than over the whole (M3).
v1 defines none; a store or a transport compresses a run of chunks (a 1 MiB run recovers all but 1–4 %).

## 3. Scalars

`[tag][payload]`: Eve's value bytes (`src/eve/deftype_proto/serialize.cljc:17-58`, `doc/internals.md`
"Serialization Format") **without the two magic bytes** — the chunk's header carries the magic once. So a scalar's
static bytes, its key bytes and its digest bytes are the same string, and converting a scalar between a slab and a
chunk is a copy (A17). Tags `0x01–0x0F`, `0x14`, `0x16–0x18` are taken as they are.

Added, from the unassigned range Eve reserves for itself (`0x00`, `0x20–0x3F`; `census/BYTE-PATHS.md` §1.4):

| tag | | bytes |
|---|---|---|
| `0x00` | nil | 1 byte (Eve's nil is zero bytes, which a node cannot address) |
| `0x28` | bytes | `[len varint][bytes]` — the scalar Eve lacks (A1) |

Not carried, by the rule a disk domain already applies: a reference (`0x15`) — a fn, a regex, a host object. The
writer refuses it by name (A12). A slab pointer tag (`0x10–0x13`, `0x19`, `0x1C–0x1F`, `0x40–0xEC`) never occurs in
a packed chunk.

Numbers: a generic scalar keeps Eve's rule (an integer by magnitude: int32, int64, bigint). A typed column carries
its element type exactly, so f32, i64 and a double that happens to be integral survive every host (A14).

## 4. Packed nodes

A node is addressed by its offset in the chunk. **Children are written before their parents and the root last**:
every reference inside a chunk points to a lower offset. That makes a chunk acyclic by construction (a reader
checks `child < parent`), puts the newest root at the end, and makes appending natural — a builder with room left
appends new nodes and a new root, and the old root still names the old value (the append-only B-tree file's shape).
The prototype wrote parents first with node-relative offsets; the spike writes this order.

Offsets are u16, or u32 in a WIDE chunk — one width per chunk.

| tag | node | layout |
|---|---|---|
| `0x20` | SYM | `[code varint]` — a symbol-table entry |
| `0x21` | MAP | `[shape varint][value offsets × n]` — n and the keys come from the shape |
| `0x22` | VEC | `[n varint][element offsets × n]` |
| `0x23` | COL | a column standing alone — a typed vector; `[n varint][column]` (§5) |
| `0x24` | SET | `[n varint][keys: offset of a VEC or COL of n]` — keys in key order |
| `0x25` | LIST | as VEC |
| `0x26` | BATCH | `[shape varint][n rows varint][column offsets × fields]` (§5) |
| `0x27` | KMAP | a map whose keys are not all symbols: `[n varint][keys offset][values offset]`, each a VEC or COL of n, keys in key order |
| `0x29` | META | `[value offset][meta offset]` |
| `0x2A` | TAGGED | `[tag: SYM][value offset]` — a value whose type the reader does not have (§6) |
| `0x2C` | REF | `[chunk varint][offset u32]` — a node of another chunk of the same value |
| `0x2D–0x3F` | reserved | a reader refuses them |

**Symbol table** — the chunk's cache. Every keyword and symbol, and every string that is a map key or occurs twice
in the chunk, is stored once as its scalar bytes; a use is a SYM, or a code in a column. Codes are assigned by
descending use, so the common ones are one byte. Unlike Transit's cache it is a table in the chunk, not an order of
traversal: any node can be read without reading what came before it (C10). Layout: `nsym + 1` offsets, then the
entries.

**Shape table** — a key tuple stored once: `[n varint][type index + 1, or 0][symbol code × n]`, keys in Eve's
canonical order (ascending key bytes, `doc/unification/FORMAT-DECISIONS.md` §2). A map of that shape is its values
alone. Measured: shapes take a further 22–33 % off after the symbol table (M2).

**Key order.** A KMAP's or SET's generic keys are in ascending key-byte order, the order Eve's flat form already
uses; a typed key column is in its numeric order; a reader finds a key by binary search. A MAP's key is found by
its symbol code in its shape.

**Sharing.** A node may have several parents. A writer packing a slab value keeps one node per source block it
meets twice; packing plain data it may do the same by identity. Measured on the image, the mechanism is the same:
k = 100 holders of one vector cost 19 KB where every logical form costs 390 KB – 1.1 MB (M7).

## 5. Columns

A column is `[kind u8][validity bitmap, when kind has 0x80][data]`. A COL node is a column of its own (a typed
vector); a BATCH is rows of one shape, one column per field, index = row. So a vector of maps and a map of vectors
arrive at the same bytes, and that is also what an Eve `Dataset` is (a map of name → typed array,
`src/eve/dataset.cljc:50-52`).

| kind | column | data |
|---|---|---|
| 1–11 | dense numbers: Eve's typed-array subtype codes as they are — 1 u8, 2 i8, 3 u8-clamped, 4 i16, 5 u16, 6 i32, 7 u32, 8 f32, 9 f64, 10 i64, 11 u64 (`serialize.cljc:106-116`) | little-endian, contiguous, aligned to the element size from the chunk's start |
| 16 | bits | one bit a row, least-significant first |
| 17, 18 | symbol codes, 1 or 2 bytes a row | a code into the chunk's symbol table |
| 19 | utf-8 | `n + 1` offsets, then the bytes |
| 20 | any | `n` node offsets — the column for mixed values |
| 21 | struct | a BATCH of the same row count — a nested map or typed field |
| 22 | narrowed integers | `[base i64][width u8 = 1, 2, 4][data]`; a read is a load and an add (M7: 1.4–3.1 ns) |
| 23 | run-end | `[runs varint][values column][ends u32 × runs]`; a read is a binary search (M7: 42–102 ns); for sorted and near-constant columns only |

Validity is one bit a row, least-significant first — the convention Arrow uses, so a dense column and its bitmap
can be handed to an Arrow consumer, a typed array, a `double[]` or DLPack as they lie (8-byte alignment is what
Arrow requires and 64 what it recommends; a JS typed array needs its offset to be a multiple of the element size —
`research/FORMATS.md` B1, A7). Nothing opaque and no delta coding is admitted inside a chunk (A18).

Measured on these columns: a sum over an f64 column at 1.3–1.4 ns a row, a keyword count at 2.4–2.7 — 17× and 6×
the host's vector of maps; one cell at 49–152 ns against the host's 59; a row assembled into a plain map in
0.6–1.0 µs (M4).

## 6. Types

A type is a shape with a name. The **type table** has, per type the chunk uses: the type key (the `"ns/Type"`
string Eve already derives its ids from, `src/eve/deftype/registry.cljc`), a 64-bit **layout fingerprint** (a hash
over the field names, classes and hints in declaration order), and per field its class — a primitive with its
subtype, a reference to another type, or a serialized value (A11). An instance is a MAP of a typed shape; a
collection of instances is a BATCH of it: primitive fields become dense columns, serialized fields `any` or typed
columns as their values allow, references `struct` or `any`.

A reader that has the type builds its static sister. One that does not, or whose fingerprint differs and has no
reader registered for that fingerprint, answers a generic record — TAGGED over the shaped map — which keeps every
field and writes back unchanged (Transit's and Fressian's rule; `research/FORMATS.md` A9, A10). `^:host` fields are
not written (they are not in memory today either).

`eve-deftype` generates the sister from the one place every host's expansion passes
(`src/eve/deftype/macros.cljc:130-153`; A2): a row type whose field reads go to `column[row]` instead of
`block + offset`, with the user's protocol bodies expanded against it. On the ISlabIO hosts the image needs no
sister — the type itself reads it.

## 7. Where a chunk lives

- **In host memory**: a byte array, a typed array, a mapped file — 8-aligned.
- **In a file or object store**: the stream of §2. A chunk with DIGEST is addressable by its SHA-256, which is how
  the live network wire already names every block it stores (`doc/network-atoms/WIRE.md` §5, §7): a chunk is one
  blob, put and fetched by presigned URL like any other.
- **In an Eve atom** (canonical Eve): one leaf block of the overflow class, 8-aligned, with a block type id from
  the unassigned `0x20–0x3F` (proposed `0x20`), the chunk after its 8-byte block header. Stored and read back by
  `memcpy`; counted and freed by the tally as one leaf; pointed at from a map or vector by an ordinary 7-byte
  pointer. This needs the type id registered as a leaf kind in `eve.tally` and `eve.retire` and as a pointer tag
  (`src/eve/retire.cljc:23-27`) — without that the tally holds an unknown block and never frees it
  (`census/TYPES-AND-DEREF-2.md` §4.4). Whether a chunk in an atom is in-band is fork F3.
- **In the kernel**: a flat value block (ExtCID v3 invariant J: a 16-byte type CID, then the payload, identity = the
  SHA-256 of its bytes) under one new type CID, of class 15 — fifteen 64 KiB chunks to a 1 MiB region, 4-byte
  aligned (A8); or, without a copy, inside an adopted page. The kernel's page writer refuses class-15 blocks today
  (`vs.tier`), so the blob wire is the route until it does not.

## 8. Equality, hash, digest

- Two statics, a static and a slab value, a static and plain data: `=` by Eve's existing rule — the content digest
  when both sides have one, else an element walk. The digest is SHA-256 over the value's flat bytes
  (`src/eve/digest.cljc:163-199`); for a packed value it is computed by a walk that re-emits those bytes without
  building host collections, once, and kept on the handle. Packed bytes are **not** canonical (codes follow use
  counts, chunk boundaries follow the writer), so two packed values are never compared byte for byte unless they
  are the same bytes.
- `hash` is the host's fold over the host's hash of each element, as it is for a slab value: computed by a walk,
  kept on the handle. It cannot be stored in the bytes — it is host-specific by ruling (A10).
- Inherited, not introduced: by digest `{:a 0.0}` ≠ `{:a -0.0}` and `{:a ##NaN}` = `{:a ##NaN}` (A10).
- A user type with no equality of its own compares by block identity in a slab. In a static it has no block; v1
  proposes type key + fields (fork F2).

## 9. The image, exactly

`kind` 1. After the header: the blocks, each at a 4-aligned offset, byte-identical to the slab's but for pointers,
which hold buffer offsets where the slab holds `[class:3 | block:29]`; the root block's offset in `root`. Then:

- the **block table** — `{offset u32, size u32}` per block, in buffer order. It is the consumer's allocation plan:
  the count and size class of every block, before any byte is read.
- the **relocation table** — `{field position u32, owner block u32, target block u32}` per pointer. It is the
  pointer pass, and it is who-holds-whom: the in-degree of every block, which is what the tally counts (M6).
- the **type table** of §6 for every eve-deftype in it — an image's blocks carry 32 bits of name, not the name.

Either table may be left out (a flag): a consumer then finds blocks and pointers by reading each block by its kind,
as `eve.retire` does. With them the image is ~1.5–2.3× its blocks (8 bytes a block, 12 a pointer; M5), so a
same-process hand-off keeps them and a stored image drops them.

Reading: Eve's types through a slab I/O over the buffer — `read-*` at `offset + field`, no `alloc!`, no `write-*`,
no `cas!`. Appending (a builder only): `alloc!` bumps the tail; `free!` does nothing. An append-only build is
mostly dead bytes when it ends — 6.2 % live for a 10,000-entry map written by today's map writer (M7) — so a build
is compacted by the same walk before it is kept.

## 10. What a reader checks

Eve's decoders check almost nothing today (A1; `census/BYTE-PATHS.md` Part 6, `-2` §3). A static reader has two
doors:

- `open` — bytes this process made or a store it trusts as it trusts a domain's files: the header only.
- `open-checked` — bytes from anywhere: one pass before any protocol operation sees them. Header: magic, version,
  required-feature bits, `length` = the bytes given, every table offset inside the chunk and 8-aligned. Tables:
  offsets ascending and in range; every entry a well-formed scalar; every shape's codes below `nsym`. Nodes, from
  the root: tag known; every child offset below its parent's; counts × the smallest element ≤ the bytes left; a
  column's length = the batch's rows; codes below `nsym`; nesting ≤ 512. An image: every block inside the buffer,
  every relocation's position inside its owner, its target a block start. With DIGEST: the hash.

A refusal is a typed `ex-info` naming the check — never a host `Error`, never a partial value. The fixture suite
gains a file of malformed chunks (none exists today for any Eve byte form).

## 11. Evolution

- The magic never changes. `version` moves only when a v1 reader could misread; a reader refuses a higher version.
- Additions that an old reader may skip ride optional flag bits; additions it must not skip set a required bit,
  and it refuses. Reserved tags are refused.
- Types evolve by fingerprint (§6), with the generic record as the floor.
- Golden bytes go in `test/eve/fixtures/` under the existing discipline — one row a line, both directions, named in
  every host's runner list (`census/BYTE-PATHS-2.md` §5) — including the rows no fixture pins today (NaN, `-0.0`,
  ±Infinity, a non-BMP character, a lone surrogate, bytes).

## 12. What v1 leaves out, on purpose

Opaque compression; parts for scalars past one chunk (WIDE chunks carry them; `kind` 3 is reserved); a persistent
spine over chunk leaves and the directory's zone maps (the plan's fourth phase); the kernel's readers; the columnar
form of a map whose keys are not symbols beyond KMAP's two columns (the prototype's weak case, 3.04× MessagePack on
int → int — KMAP with two dense columns is 80 KB against MessagePack's 59 KB on that corpus, by arithmetic, not
measured).
