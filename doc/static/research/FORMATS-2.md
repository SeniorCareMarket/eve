STATUS: PARTIAL — cards B3 (rkyv), B4 (FlexBuffers) banked. Remaining: B5–B12, C1 additions, C2, C3.

# Static Eve — format research, part 2 (FORMATS-2.md)

Continues `doc/static/research/FORMATS.md` (Part A complete; cards B1 Arrow IPC, B2 Parquet VARIANT; C1 chunk rows).
Research date: **2026-11-12** (US Eastern). Method: primary sources only. Source files and spec pages were downloaded
this session (`curl` through the session proxy) and quoted from the downloaded bytes — mark **ran** = a `grep`/`sed`
over the fetched file here; **doc read** = a spec/doc page read here; nothing is quoted from memory. A number not seen
in a primary source this session is marked **UNVERIFIED-HERE** with the URL tried.

Pins: branch heads as served on 2026-11-12 — `rkyv/rkyv` `main` (`Cargo.toml` `version = "0.8.18"`),
`google/flatbuffers` `master`.

---

## Part B — mechanism cards (continued)

### B3. rkyv — generated sister types, self-relative pointers, validate-then-cast

Sources (ran): book <https://rkyv.org/format.html>, <https://rkyv.org/validation.html>, <https://rkyv.org/architecture/relative-pointers.html> (`book_src/*.md`) ·
`rkyv/src/{vec.rs,rel_ptr.rs,seal.rs,lib.rs,api/mod.rs}`, `rkyv/src/collections/swiss_table/table.rs`, `rkyv/src/collections/btree/map/mod.rs` at <https://github.com/rkyv/rkyv/tree/main/rkyv/src>.

- **What**: "total zero-copy deserialization … structuring its encoded representation so that it is the same as the in-memory representation of the source type"; access is "always just a pointer offset and a cast".
- **Sister type**: `derive(Archive)` emits `ArchivedT` where "Member types are replaced with their archived counterparts · Structs are `#[repr(C)]` · Enums have `#[repr(N)]` … choosing the smallest possible type · All primitives are replaced with versions which have stable, well-defined layouts and byte orders" (`a: u32_le, b: ArchivedString`). Build is two-step: serialize dependencies, keep a `Resolver` (their positions), then *resolve* the parent in place.
- **Format knobs are compile-time features, not bytes**: endianness, `aligned`/`unaligned`, `pointer_width_16/32/64`; defaults "Little-endian · Aligned · 32-bit relative pointers". Nothing in the buffer says which were used (no header found in the pages read).
- **Relative pointer**: "An untyped pointer which resolves relative to its position in memory" (offset from the pointer's own address); "Relative pointers don't require write access to memory either, so we can memory map entire files".
- **Root at the end**: "rkyv lays out subobjects in depth-first order from the leaves to the root. This means that the root object is stored at the end of the buffer" → "you don't need to store the position of the root object"; root = buffer length − size of the root type.
- **`ArchivedVec<T>`** = `#[repr(C)] { ptr: RelPtr<T>, len: ArchivedUsize }` (8 bytes at defaults) over a contiguous `[Archived<T>]`.
- **`ArchivedHashMap`** = SwissTable: `ArchivedHashTable<T> { ptr: RawRelPtr, len, cap }`, one control byte per bucket (`h2 = (hash >> 57) as u8`, EMPTY `0xFF`); "The number of control bytes is rounded up to a maximum group width (16) instead of the next power of two … Since this collection is immutable after writing, we'll never benefit from having more buckets than we need." Hash is "a target-independent version of FxHash to ensure that all targets compute the same hashes".
- **`ArchivedBTreeMap<K, V, const E = 5>`** = `{ root: RelPtr<Node>, len }`; `Node { kind, keys: [K; E], values: [V; E] }`, `InnerNode { node, lesser_nodes: [RelPtr; E], greater_node: RelPtr }`, `LeafNode { node, len }`; `DEFAULT_ENTRIES_PER_NODE: usize = 5` (branching 6).
- **Validation (`bytecheck`, a default feature)**: "All pointers are checked to make sure that they: Point inside the archive · Are properly aligned · And have enough space afterward to hold the desired object", plus *subtree ranges* so "the archive follows its strict ownership model" (against "recursion attacks and memory sharing violations"); shared pointers "will fail to validate if they are different types". Cost model: one whole-archive pass before the first access (`access::<ArchivedExample, Failure>(buffer)`), "can generally be done without allocations"; the alternative is `unsafe access_unchecked` plus "checksums and cryptographic signatures".
- **Access vs deserialize**: `access` → `&ArchivedT` inside the buffer; `deserialize` / `from_bytes` → builds the native `T` (allocates, copies).
- **In-place mutation**: `access_mut` returns `Seal<'_, ArchivedT>` — "A mutable reference which may not be moved or assigned … unless it is `Unpin` and `NoUndef`", destructured with `munge` (`*value = 12345.into();`). Fixed-size fields can be overwritten; nothing can grow or be re-pointed ("rkyv supports limited in-place data mutation").
- **For static Eve**: the working proof of "derived sister type + self-relative offsets = O(1) open over mmap"; copy the resolver two-step and root-at-end. Do not copy: layout defined by the host language's type + build flags (no self-description, no evolution), and collections that are frozen at write.

### B4. FlexBuffers (schema-less FlatBuffers) and the FlatBuffers Verifier

Sources (ran): <https://flatbuffers.dev/internals/> (`docs/source/internals.md`, section "FlexBuffers"), <https://flatbuffers.dev/flexbuffers/> (`docs/source/flexbuffers.md`) ·
`include/flatbuffers/{flexbuffers.h,verifier.h,base.h}`, `rust/flexbuffers/src/builder/mod.rs`, `java/…/FlexBuffersBuilder.java` at <https://github.com/google/flatbuffers/tree/master>.

- **What**: "can also be accessed without parsing / copying / object allocation … allows unique use cases such as mmap-ing large amounts of free-form data"; "any value can be the root, including a lonely int value".
- **One offset kind**: "an unsigned integer indicating the number of bytes in a negative direction from the address of itself". Built front to back: "children are stored before parents, and the root of the data starts at the last byte" — last byte = root byte width, the byte before = root type, before that the root value (`uint8_t 13, 4, 1`). A writer can therefore stream children out and never patch.
- **Per-vector width**: "scalar data is stored with a variable number of bits (8/16/32/64). The current width is always determined by the *parent*" — one width for all elements *and* the size field (which sits "effectively at index -1"). One double widens the whole vector, hence `IndirectDouble`.
- **Type bytes**: an untyped vector is size · elements · one `uint8_t` type per element; "2 lower bits representing the bit-width of the child … 6 bits representing the actual type". Typed vectors "omit the type bytes" — only for int / uint / float / key; fixed-length 2/3/4 variants store no size.
- **Map** = values vector with three prefixes: index −3 "An offset to the keys vector (may be shared between tables)", −2 "Byte width of the keys vector", −1 size. "Both the keys and corresponding values *have* to be stored in sorted order (as determined by `strcmp`), such that lookups can be made using binary search" (`std::bsearch` in `flexbuffers.h:997`) → O(log n) get, no decode. A key is a NUL-terminated string with no size field: **string keys only**, no byte 0.
- **Sharing flags** (`flexbuffers.h:1031`): `BUILDER_FLAG_NONE = 0, BUILDER_FLAG_SHARE_KEYS = 1, BUILDER_FLAG_SHARE_STRINGS = 2, BUILDER_FLAG_SHARE_KEYS_AND_STRINGS = 3, BUILDER_FLAG_SHARE_KEY_VECTORS = 4, BUILDER_FLAG_SHARE_ALL = 7`; default `BUILDER_FLAG_SHARE_KEYS` ("By default this is on for keys, but off for strings."). Any value can be shared by hand: `builder.ReuseValue(id); // Refers to same double by offset.`
- **Doc vs code — key-vector sharing is not implemented.** C++ `EndMap` (`flexbuffers.h:1301`): "TODO(wvo): if kBuilderFlagShareKeyVectors is true, see if we can share the first vector." Rust builder: "## Not Yet Implemented - `SHARE_STRINGS` - `SHARE_KEY_VECTORS`". Java defines `BUILDER_FLAG_SHARE_KEY_VECTORS = 4` and never reads it (1 grep hit). The format permits a shared keys vector; no shipped builder emits one.
- **Limits**: widths 8/16/32/64 only; closed 6-bit type enum (no user tags); verifier size bound `FLATBUFFERS_MAX_BUFFER_SIZE` = max `soffset_t` (`base.h:342`).
- **FlexBuffers `Verifier`** (`flexbuffers.h:1844`): `reuse_tracker = nullptr, _check_alignment = true, size_t max_depth = 64`; `max_vectors_(buf_len)`; checks ranges, byte widths, `type < FBT_MAX_TYPE`, offsets, alignment, key terminators; the reuse tracker "comes at the cost of using additional memory the same size of the buffer being verified, so it is by default off".
- **FlatBuffers `Verifier::Options`** (`verifier.h:29`): `max_depth = 64` ("maximum nesting of tables and vectors"), `max_tables = 1000000`, `check_alignment = true`, `check_nested_flatbuffers = true`, `max_size = FLATBUFFERS_MAX_BUFFER_SIZE`. One full pass (vtable/field bounds, vectors, strings) before trusting accessors.
- **For static Eve**: the nearest shipped "EDN-ish tree, operable in place, minimal width per container, pooled keys". Take: root-at-end, the keys-vector / values-vector split, per-container width. Fix: string-only `strcmp` keys, no tags, key-vector sharing left as a TODO, typed vectors for four scalar kinds only.

