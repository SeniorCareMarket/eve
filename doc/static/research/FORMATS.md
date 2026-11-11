STATUS: PARTIAL — Part A (claims 1–10) banked. Remaining: all of Part B (mechanism cards); all of Part C (synthesis tables).

# Static Eve — format research (FORMATS.md)

Research date: **2026-11-11** (all times US Eastern). Lane: formats census for the static-Eve design.
Method: primary sources only — source files fetched from `raw.githubusercontent.com` at the named branch/tag, official
spec/doc pages, RFCs, papers. Every quote below was read in the fetched file this session; nothing is quoted from memory.

Legend — verdicts: **VERIFIED** (primary source says it), **REFUTED** (primary source says otherwise), **NUANCED**
(true with a qualification that matters to the design), **UNVERIFIABLE-HERE** (fetch blocked or no primary source found;
URL attempted is given). "from memory, unverified" marks anything not read this session.

Pins used (branch heads as shown on github.com on 2026-11-11, or the version string inside the fetched tree):

| Tree | Pin |
|---|---|
| vortex-data/vortex | branch `develop`, head `51972eb` (2026-11-11); old tags `0.7.0`…`0.36.0` probed for the magic |
| lance-format/lance | branch `main`, workspace version `15.0.0-beta.18`; paper arXiv:2504.15247v1 (2025-04-21) |
| facebookincubator/nimble | branch `main` (commits page shows `acead74`, 2026-08-07, as newest) |
| apache/parquet-java | `master`, `1.19.0-SNAPSHOT`; apache/arrow `main`; apache/arrow-rs `main`; apache/iceberg `main`; apache/parquet-format `master`; apache/parquet-site `production` |
| apache/orc | `main`, `3.0.0-SNAPSHOT` |
| huggingface/safetensors | `main`, crate `0.9.0-dev.0` |
| docs.datomic.com | pages as served 2026-11-11 |

---

## Part A — claims checked against primary sources

### A1. Vortex

Sources: `S` = <https://github.com/vortex-data/vortex/blob/develop/vortex-file/src/strategy.rs> ·
`L` = <https://github.com/vortex-data/vortex/blob/develop/vortex-file/src/lib.rs> ·
`F` = <https://github.com/vortex-data/vortex/blob/develop/vortex-file/flatbuffers/vortex-file/footer.fbs> ·
`R` = <https://github.com/vortex-data/vortex/blob/develop/vortex-layout/src/layouts/repartition.rs> ·
`D` = <https://github.com/vortex-data/vortex/blob/develop/vortex-layout/src/layouts/dict/writer.rs> ·
`Z` = <https://github.com/vortex-data/vortex/blob/develop/vortex-layout/src/layouts/zoned/writer.rs> ·
`B` = <https://github.com/vortex-data/vortex/blob/develop/vortex-btrblocks/src/lib.rs> ·
`RM` = <https://github.com/vortex-data/vortex/blob/develop/README.md> ·
docs = <https://docs.vortex.dev/specs/file-format.html> (source `docs/specs/file-format.md`, `docs/specs/versioning.md`, `docs/concepts/{layouts,dtypes,arrays}.md`).

| # | Claim | Verdict | Quote / code line | Nuance |
|---|---|---|---|---|
| a | Writer coalesces to a target of exactly 1,048,576 bytes; rationale in code | **VERIFIED, NUANCED** | `S`: `const ONE_MEG: u64 = 1 << 20;` … `data_block_target_bytes: Some(ONE_MEG),` and the comment: "Write stream partitions roughly become segments. Because Vortex never reads less than one segment, the size of segments and, therefore, partitions, must be small enough to both (1) allow fine-grained random access reads and (2) allow sufficient read concurrency for the desired throughput. One megabyte is small enough to achieve this for S3 (Durner et al., "Exploiting Cloud Object Storage for High-Performance Analytics", VLDB Vol 16, Iss 11)." | 1 MiB is the **uncompressed** size of a block *before* compression, used as both minimum and target (`R`: "The minimum uncompressed size in bytes for a block"; target is "only a best effort attempt: the partitions may be arbitrarily larger or smaller"), and blocks hold a multiple of 8,192 rows. The on-disk segment is the compressed result, so normally well under 1 MiB. A second constant buffers 2 MiB for locality: `BufferedStrategy::new(chunked, 2 * ONE_MEG); // 2MB`. The rationale is object-store request sizing, not CPU cache. |
| b | Zone maps over fixed 8,192-row blocks | **VERIFIED** | `S`: `row_block_size: 8192,` … `// Always repartition into 8K row blocks`; `Z`: `block_size: unsafe { NonZeroUsize::new_unchecked(8192) },`; docs/layouts: "`ZonedLayout` — A layout that stores a zone-map of statistics to perform filter pruning." | Configurable (`with_row_block_size`): "Larger blocks reduce footer/statistics overhead. Smaller blocks can improve pruning and random-access locality." |
| c | Per-chunk dictionary encoding | **NUANCED** | `L`: "`DictLayout`: a shared dictionary of values with a child layout holding indices."; `D`: "When the dict constraints are hit, a new dictionary is created." with `max_bytes: 1024 * 1024, max_len: u16::MAX` and "Since layout chunks are typically ~8k elements, having more than 64k unique values in a dictionary means dictionary encoding provides little compression benefit." | The dictionary is **shared across a run of chunks**, not one per chunk; it is cut when it reaches 1 MiB or 65,535 entries. Codes are `u8` or `u16` only. The use/skip decision probes the first 8,192 rows (`probe_len: 8192`). |
| d | Cascading "BtrBlocks-style" encoding selection per chunk | **VERIFIED** | `B`: "Vortex's [BtrBlocks]-inspired adaptive compression framework." … "evaluates every enabled `Scheme` and picks the one with the best compression ratio" … "(often by compressing a ~1% sample)" … "Schemes can produce arrays that are themselves further compressed (e.g. FoR then BitPacking), up to `MAX_CASCADE` (3) layers deep." | Selection is by sampled estimate per array; depth cap 3. |
| e | Extensible DTypes / extension types; pluggable encodings + layouts | **VERIFIED** | `RM`: "Pluggable encoding system, type system, compression strategy, & layout strategy"; docs/dtypes: "An `Extension` dtype is a logical dtype with an `id`, a `storage` dtype, and a `metadata` field."; docs/layouts: "users can define their own custom layouts."; `F`: an `ArraySpec`/`LayoutSpec` is "identified by a globally unique string identifier, and looked up in the Vortex registry at read-time." | The footer dictionary-encodes those string ids once per file ("Dictionary-encoded array specs, up to u16::MAX") and the data refers to them by index — a per-file tag cache. A reader without the plugin cannot decode that component. |
| f | Footer stores per-segment {offset, length, alignment} | **VERIFIED** | `F`: `struct SegmentSpec { offset: uint64; length: uint32; alignment_exponent: uint8; _compression: uint8; _encryption: uint16; }` | Alignment is a base-2 exponent; two reserved fields; segment length is `u32` (< 4 GiB). The reader validates the exponent as untrusted input (`footer/segment.rs`, test `rejects_excessive_alignment_exponent` rejects 17). |
| g | File magic changed between format versions | **NUANCED** | Tag probes of `MAGIC_BYTES`: `*b"VRX1"` (tags 0.7.0, 0.10.0, 0.12.0 — `vortex-serde/src/layouts/mod.rs`), `*b"VRTX"` (0.14.0), `*b"VTXF"` (0.16.0 … 0.36.0 and `develop`). `VERSION: u16 = 1` in every tag from 0.14.0 to `develop`. | The magic changed twice **before** the stability line while the `u16` version stayed 1. Since 0.36.0 it sits in `mod forever_constant` ("Constants that will never change (i.e., doing so would break backwards compatibility)"). Evolution now goes through *editions*, not the magic or the version field. |
| h | Random access / compute on compressed data | **VERIFIED as stated goals** | `RM`: "100x faster random access reads (vs. modern Apache Parquet)", "Optimized compute kernels for encoded data"; docs index: "Operate directly on compressed data … no decompression needed for many operations."; `S`: "Vortex never reads less than one segment". | The speed-ups are the project's own numbers (not reproduced here). Compute falls back to canonical form when no kernel exists for an encoding (docs/arrays). Random access granularity is one segment. |
| i | Format stability policy | **VERIFIED** | `RM`: "we now consider the file format _stable_. From release 0.36.0, all future releases of Vortex should maintain backwards compatibility of the file format"; versioning: "Vortex guarantees **backward compatibility** for its frozen wire formats" … "The first frozen edition is `core2025.05.0`, supported from version `0.36.0`". | Backward only. Forward compatibility = the writer selects an older *edition* (a frozen set of permitted wire ids). Embedding WASM decoders in the file is "not implemented and is not part of the edition compatibility guarantee". Library API is not stable. |

Extra facts read in the same files: postscript ≤ 65,527 bytes and "An initial read of a Vortex file defaults to at least
64KB (u16::MAX bytes) and therefore is guaranteed to cover at least the Postscript" (`F`); file = magic · segments ·
dtype/layout/statistics/footer flatbuffers · postscript · `u16 version, u16 postscript length, magic` (`L`).

### A2. Lance

Sources: docs `I` = <https://github.com/lance-format/lance/blob/main/docs/src/format/file/index.md> ·
`E` = <https://github.com/lance-format/lance/blob/main/docs/src/format/file/encoding.md> ·
`Ve` = <https://github.com/lance-format/lance/blob/main/docs/src/format/file/versioning.md> ·
code `enc` = `rust/lance-encoding/src/encoder.rs`, `mb` = `rust/lance-encoding/src/encodings/logical/primitive/miniblock.rs`,
`se` = `rust/lance-encoding/src/encodings/logical/primitive/structural_encoder.rs`, `w` = `rust/lance-file/src/writer.rs`
(same repo, `main`) · paper `P` = <https://arxiv.org/abs/2504.15247> (v1, 2025-04-21, "Lance: Efficient Random Access in
Columnar Storage through Adaptive Structural Encodings").

| # | Claim | Verdict | Quote / code line | Nuance |
|---|---|---|---|---|
| a | Default page size 8 MiB | **VERIFIED** | `I`: "a default of 8MB is recommended for the page size and should yield ideal performance on all storage systems."; `enc`: `cache_bytes_per_column: 8 * 1024 * 1024, max_page_bytes: 32 * 1024 * 1024,`; `w`: "The default is 8 MiB per column."; `P`: "default disk page (i.e. column chunk) size of 8 MiB". | 8 MiB is the per-column write buffer flushed as one page; the hard page cap is 32 MiB. Pages are the I/O unit ("large enough to justify a dedicated I/O operation"), not the decode unit. No row groups. |
| b | Mini-block structural encoding with 4–8 KiB chunks (1–2 disk sectors) | **VERIFIED (paper); NUANCED (format limits)** | `P` §4.2: "The goal is for each chunk to represent 1-2 disk sectors (4KiB-8KiB) of compressed data." and "miniblocks are at most 32 KiB and usually much closer to 4-8 KiB"; `mb`: `pub const MAX_MINIBLOCK_BYTES: u64 = 8 * 1024 - 6;` `const DEFAULT_MAX_MINIBLOCK_VALUES: u64 = 4096;` | The format limit is 32 KiB in 2.1 (`E`: "should be less than 32KiB of compressed data"; size stored as 12 bits of 8-byte words) and `u32` since 2.2; the *writer budget* is just under 8 KiB and ≤ 4,096 values (power of two). Mini-blocks are padded to 8 bytes. A whole mini-block is decoded to read one value. |
| c | Full-zip layout and the bytes-per-value threshold (128 vs 256) | **NUANCED — both numbers are primary** | `P` §4.1 (2025-04, describing 2.1): "We use 128 bytes per value as a threshold based on experimental measurements."; `E` (`main`, 2026-11): "We currently use 256 bytes as the cutoff for the full zip layout. At this point we would only be fitting 16 values in a 4KiB disk sector"; `se`: `const MINIBLOCK_MAX_BYTE_LENGTH_PER_VALUE: u64 = 256;` | Paper = 128; current spec text and code = 256. The test is on the page's **maximum** value length (`Stat::MaxLength`), not the mean. Which release moved 128 → 256 was not traced. |
| d | File format carries no type system; delegates to Arrow | **VERIFIED** | `I`: "The file container itself has no built-in concept of encoding, and no built-in type system. A Lance file is only aware of columns, pages, and buffers"; "This basic container format has no concept of types. These are added later by the encoding layer. … The schema is typically stored in the global buffers, but the file format is unaware of this." | Encodings are self-describing protobuf messages; an unknown encoding yields "a clear "unsupported encoding" error rather than silently misreading". Arrow types live in the encoding layer / schema global buffer. |
| e | Random-access cost (IOPS per row) | **VERIFIED** | `P` §4 goals: "At most 1 IOP for random access to a fixed-width column • At most 2 IOPS for random access to a variable-width column"; `E`: "To perform random access we require two IOPS. First we issue an IOP into the repetition index … and then a second IOP into the data buffer". | Per column, after the "search cache" (2 bytes per mini-block) is loaded. Blob layout is "only recommended when you can justify a single IOP per value. For example, when values are 1MiB or larger." |

Also read: buffers aligned to 64 bytes ("we always align buffers to 64 byte boundaries", `I`); page dictionary cap 8 MiB
(`DEFAULT_DICT_MAX_SIZE_BYTES: usize = 8 * 1024 * 1024`); versions `Ve`: 2.0 and 2.1 stable, 2.2 listed stable, "2.3
(unstable)"; "Unstable formats have no compatibility guarantee".

### A3. Nimble

Sources: <https://github.com/facebookincubator/nimble/blob/main/README.md> (`RM`) ·
`dwio/nimble/writer/VeloxWriterOptions.h` (`O`) · `dwio/nimble/velox/NimbleConfig.cpp` (`C`) — same repo, `main`.

| Claim | Verdict | Quote / code line | Nuance |
|---|---|---|---|
| Status / stability | **VERIFIED: unstable** | `RM`: "Nimble is a work in progress, and many of these features above are still under design and/or active development. As such, Nimble does not provide stability or versioning guarantees (yet)." | Also: "We strongly discourage developers to (re-)implement Nimble's spec" — it is a library, not a spec. |
| Default stripe / chunk sizes | **VERIFIED (code only; no published spec numbers)** | `O`: `return std::make_unique<StripeRawSizeFlushPolicy>(256 << 20);` ("Buffering 256MB data before encoding stripes."); `uint64_t minStreamChunkRawSize{512 << 10};` `uint64_t maxStreamChunkRawSize{20 << 20};` `uint64_t wideSchemaMaxStreamChunkRawSize{2 << 20};`; `C`: `"alpha.raw.stripe.size", 384 * 1024L * 1024L` | Stripe 256 MiB raw (384 MiB via the Hive-style config key); stream chunks 512 KiB – 20 MiB raw (2 MiB cap when the schema has > 500 nodes). No 64 KiB or 1 MiB unit. |
| Cluster index / point lookup | **NUANCED: exists, experimental** | `O`: "the cluster index … is the primary index over key-ordered data and stores per-chunk boundary keys per stripe-group partition for binary-search pruning. EXPERIMENTAL: Cluster index is not production-ready."; `enableChunkIndex{false}`: "enabling O(1) chunk-level seeking within stripes … EXPERIMENTAL: Not production-ready." | Off by default. |
| "Extensible" as a design principle | **VERIFIED** | `RM`: "**Extensible:** Since the state-of-the-art in data encoding evolves faster than the file layout itself, Nimble decouples stream encoding from the underlying physical layout. Nimble allows encodings to be extended by library users and recursively applied (cascading)." | |
| Encoding selection | **VERIFIED** | `RM`: "Supports pluggable encoding selection policies."; `O`: "Heuristics based, ML based or specialized policies can be specified." | Also "Use block encoding instead of stream encoding to provide predictable memory usage"; FlatBuffers metadata. |

### A4. Parquet

| # | Claim | Verdict | Primary source | Quote / code line | Nuance |
|---|---|---|---|---|---|
| a1 | parquet-java data page default 1 MiB | **VERIFIED** | <https://github.com/apache/parquet-java/blob/master/parquet-column/src/main/java/org/apache/parquet/column/ParquetProperties.java> (1.19.0-SNAPSHOT) | `public static final int DEFAULT_PAGE_SIZE = 1024 * 1024;` | Also a row cap: `DEFAULT_PAGE_ROW_COUNT_LIMIT = 20_000`. README: "The page is the smallest unit that must be read fully to access a single record." |
| a2 | parquet-cpp / pyarrow default 1 MiB | **VERIFIED** | <https://github.com/apache/arrow/blob/main/cpp/src/parquet/properties.h> | `static constexpr int64_t kDefaultDataPageSize = 1024 * 1024;` `static constexpr int64_t kDefaultMaxRowsPerPage = 20'000;` | arrow-rs agrees: `pub const DEFAULT_PAGE_SIZE: usize = 1024 * 1024;` (<https://github.com/apache/arrow-rs/blob/main/parquet/src/file/properties.rs>). |
| a3 | Iceberg default 1 MiB | **VERIFIED** | <https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/TableProperties.java> | `PARQUET_PAGE_SIZE_BYTES_DEFAULT = 1024 * 1024; // 1 MB`; `PARQUET_PAGE_ROW_LIMIT_DEFAULT = 20_000;` | |
| b | Format docs recommend 8 KiB pages | **VERIFIED** | <https://parquet.apache.org/docs/file-format/configurations/> (source `apache/parquet-site`, `content/en/docs/File Format/configurations.md`) | "Data pages should be considered indivisible so smaller data pages allow for more fine grained reading (e.g. single row lookup). … We recommend 8KB for page sizes." | Every implementation above defaults to 128× that. The doc recommendation and the shipped defaults disagree. |
| c | Default row-group sizes | **VERIFIED** | parquet-java `ParquetWriter.java`; Arrow `properties.h`; arrow-rs; Iceberg | `DEFAULT_BLOCK_SIZE = 128 * 1024 * 1024;` (bytes, parquet-java) · `DEFAULT_MAX_ROW_GROUP_LENGTH = 1024 * 1024;` (rows, Arrow C++) · `DEFAULT_MAX_ROW_GROUP_ROW_COUNT: usize = 1024 * 1024;` (rows, arrow-rs) · `PARQUET_ROW_GROUP_SIZE_BYTES_DEFAULT = 128 * 1024 * 1024; // 128 MB` (Iceberg) | Units differ: Java/Iceberg count bytes, C++/Rust count rows. The format docs say "We recommend large row groups (512MB - 1GB)". |
| d | One dictionary page per column chunk; size limit; fallback | **VERIFIED** | <https://github.com/apache/parquet-java/blob/master/parquet-hadoop/README.md>; <https://github.com/apache/parquet-format/blob/master/Encodings.md> | README: "There is one dictionary page per column per row group when dictionary encoding is used. **Default value:** `1048576` (1 MB)"; `DEFAULT_DICTIONARY_PAGE_SIZE = DEFAULT_PAGE_SIZE;`; Arrow: `DEFAULT_DICTIONARY_PAGE_SIZE_LIMIT = kDefaultDataPageSize;`; Encodings.md: "If the dictionary grows too big, whether in size or number of distinct values, the encoding will fall back to the plain encoding." | Iceberg overrides to 2 MiB (`PARQUET_DICT_SIZE_BYTES_DEFAULT = 2 * 1024 * 1024`). Dictionary scope = column chunk (one row group), fallback is one-way within the chunk. |

### A5. ORC

Sources: <https://github.com/apache/orc/blob/main/java/core/src/java/org/apache/orc/OrcConf.java> (3.0.0-SNAPSHOT) ·
<https://github.com/apache/orc/blob/main/c++/src/Writer.cc> · spec <https://orc.apache.org/specification/ORCv1/> (source
`site/specification/ORCv1.md`).

| Claim | Verdict | Quote / code line | Nuance |
|---|---|---|---|
| Compression chunk default — Java | **VERIFIED: 262,144** | `BUFFER_SIZE("orc.compress.size", "hive.exec.orc.default.buffer.size", 256 * 1024, "Define the default ORC buffer size, in bytes."),` | Spec agrees: "The default compression chunk size is 256K, but writers can choose their own value." |
| Compression chunk default — C++ | **VERIFIED: 65,536** | `compressionBlockSize = 64 * 1024; // 64K` (in `WriterOptionsPrivate()`) | So **both** numbers in the proto-research are right, for different writers. The C++ writer also uses `memoryBlockSize = 64 * 1024; // 64K`. |
| Stripe size | **VERIFIED: 64 MiB both** | Java `STRIPE_SIZE("orc.stripe.size", …, 64L * 1024 * 1024,`; C++ `stripeSize = 64 * 1024 * 1024; // 64M` | Spec prose still says stripes are "typically ~200MB". |
| Row index stride | **VERIFIED: 10,000 rows both** | Java `ROW_INDEX_STRIDE("orc.row.index.stride", …, 10000,`; C++ `rowIndexStride = 10000;` | |
| Dictionary key-size threshold | **VERIFIED: 0.8 (Java); 0.0 (C++)** | Java `DICTIONARY_KEY_SIZE_THRESHOLD("orc.dictionary.key.threshold", …, 0.8, "If the number of distinct keys in a dictionary is greater than this fraction of the total number of non-null rows, turn off dictionary encoding…`; C++ `dictionaryKeySizeThreshold = 0.0;` | C++ default 0.0 = dictionary encoding off unless enabled. |

Chunk framing (spec): "Each header is 3 bytes long with (compressedLength * 2 + isOriginal) stored as a little endian
value"; "Each compression chunk is compressed independently"; the chunk size is recorded in the Postscript.

### A6. Datomic segment size

| Claim (proto-research) | Verdict | Primary source | Quote | Nuance |
|---|---|---|---|---|
| "4 KB is a misconception" | **VERIFIED** (no primary source gives 4 KB) | <https://docs.datomic.com/operation/capacity.html> | "Datomic uses all storages similarly – to store log and index segments. These segments range up to about 50kb each." | |
| "docs say thousands of datoms per segment" | **VERIFIED** | same page; <https://docs.datomic.com/indexes/background-indexing.html>; <https://docs.datomic.com/query/indexes.html> | "In practice, for non-large values, we see anywhere from 1,000 to 20,000 datoms/segment."; "trees of segments with a wide (~1000) branching factor and with leaf segments typically containing a few thousand datoms each"; "each segment typically contains thousands of datoms" | |
| "~50 KB is unofficial" | **REFUTED** | <https://docs.datomic.com/operation/capacity.html> | "These segments range up to about 50kb each." | The ~50 KB figure **is** in the official docs. It is an upper bound on the stored (Fressian-encoded, then compressed) segment: "the Fressian format has packed encodings … Finally, all of the segments are compressed (with, e.g. zip)". |

### A7. Safetensors and DLPack

Safetensors — <https://github.com/huggingface/safetensors/blob/main/README.md>, `safetensors/src/tensor.rs` (`main`, crate 0.9.0-dev.0):

| Claim | Verdict | Quote / code line | Nuance |
|---|---|---|---|
| Header = 8-byte length + JSON | **VERIFIED** | "8 bytes: `N`, an unsigned little-endian 64-bit integer, containing the size of the header" · "N bytes: a JSON UTF-8 string representing the header." | "`data_offsets` point to the tensor data relative to the beginning of the byte buffer (i.e. not an absolute position in the file)". |
| No holes / contiguity | **VERIFIED** | "The byte buffer needs to be entirely indexed, and cannot contain holes." | Enforced: `MetadataIncompleteBuffer` = "incomplete metadata, file not fully covered". |
| Alignment | **NUANCED** | "The header data MAY be trailing padded with whitespace (0x20)."; `tensor.rs`: `// Force alignment to 8 bytes.` `let aligned_metadata_len = metadata_buf.len().next_multiple_of(N_LEN);` | The format does not *promise* per-tensor alignment; the reference writer pads the JSON with spaces so the data region starts 8-byte aligned and sorts tensors by descending dtype alignment. |
| Header size limit | **VERIFIED: 100 MB** | README: "on the size of the header of 100MB to prevent parsing extremely large JSON."; `const MAX_HEADER_SIZE: usize = 100_000_000;` | Decimal 100,000,000, not 100 MiB. Little-endian only. |

DLPack — <https://github.com/dmlc/dlpack/blob/main/include/dlpack/dlpack.h> (`main`; `DLPACK_MAJOR_VERSION 1`, `DLPACK_MINOR_VERSION 4`):

| Claim | Verdict | Quote / code line | Nuance |
|---|---|---|---|
| `DLTensor` fields | **VERIFIED** | `void* data; DLDevice device; int32_t ndim; DLDataType dtype; int64_t* shape; int64_t* strides; uint64_t byte_offset;` | `strides` are in elements, not bytes. "Before DLPack v1.2, strides can be NULL to indicate contiguous data." — since 1.2 they must be present when `ndim != 0`. |
| `DLManagedTensorVersioned` fields | **VERIFIED** | `DLPackVersion version; void *manager_ctx; void (*deleter)(struct DLManagedTensorVersioned *self); uint64_t flags; DLTensor dl_tensor;` | Ownership = producer keeps the memory alive until the consumer calls `deleter`. |
| `byte_offset` | **VERIFIED** | "The `byte_offset` field should be used to point to the beginning of the data." | Lets `data` stay the (aligned) allocation base while the tensor starts inside it — the same shape as "container base + segment offset". |
| `READ_ONLY`, `IS_COPIED` flags | **VERIFIED** | `#define DLPACK_FLAG_BITMASK_READ_ONLY (1UL << 0UL)` · `#define DLPACK_FLAG_BITMASK_IS_COPIED (1UL << 1UL)` — "If set, the tensor is considered solely owned throughout its lifetime by the consumer, until the producer-provided deleter is invoked." | A third flag exists: `DLPACK_FLAG_BITMASK_IS_SUBBYTE_TYPE_PADDED (1UL << 2UL)`. A static-Eve column exported in place would set `READ_ONLY` and leave `IS_COPIED` clear. |
| 256-byte alignment recommendation | **NUANCED** | "This pointer is always aligned to 256 bytes as in CUDA." followed by: "Note that as of Nov 2021, multiple libraries (CuPy, PyTorch, TensorFlow, TVM, perhaps others) do not adhere to this 256 byte alignment requirement on CPU/CUDA/ROCm, and always use `byte_offset=0`. … at the moment it is recommended to not rely on the data pointer being correctly aligned." | The header states the rule and, in the same comment, says not to rely on it. Consumers cannot assume 256; producers that want to be safe align the base and use `byte_offset`. |

### A8. WebAssembly

| Claim | Verdict | Primary source | Quote | Nuance |
|---|---|---|---|---|
| Linear-memory page size is 64 KiB | **VERIFIED** | <https://webassembly.github.io/spec/core/exec/runtime.html#memory-instances> (source `WebAssembly/spec` `main`, `document/core/exec/runtime.rst`) | "The length of the sequence always is a multiple of the WebAssembly *page size*, which is defined to be the constant 65536 -- abbreviated 64 Ki." | Memory grows only in whole pages; limits are in pages. |
| Custom-page-sizes proposal status | **VERIFIED: Phase 3** | <https://github.com/WebAssembly/proposals/blob/main/README.md> (2026-11-11); <https://github.com/WebAssembly/custom-page-sizes/blob/main/proposals/custom-page-sizes/Overview.md> | Listed under "Phase 3 - Implementation Phase (CG + WG)"; Overview: "There are currently exactly two valid page sizes: 1 byte and 64 KiB. The encoding and spec are factored such that we can relax this to allow any power of two between `1` and `65536` (inclusive) in the future" | Not standardized (Phase 3 of 5). Even with it, 64 KiB stays the maximum page size, so a 64 KiB chunk = exactly one default wasm page. |

### A9. Transit

Sources: spec <https://github.com/cognitect/transit-format/blob/master/README.md> (`master`) · reference implementation
`cognitect/transit-java` `master`: `src/main/java/com/cognitect/transit/impl/{WriteCache,ReadCache,WriterFactory,ReaderFactory}.java`.

| Claim | Verdict | Quote / code line | Nuance |
|---|---|---|---|
| What is cacheable | **VERIFIED** | Spec: "all ~#tag, keyword and symbol values are cached when they are more than 3 characters long (including the tag). Strings more than 3 characters long are also cached when they are used as keys in maps whose keys are all "stringable"." Code: `return (s.length() >= MIN_SIZE_CACHEABLE) && (asMapKey \|\| (s.charAt(0) == Constants.ESC && (s.charAt(1) == ':' \|\| s.charAt(1) == '$' \|\| s.charAt(1) == '#')));` with `MIN_SIZE_CACHEABLE = 4` | The length is of the *encoded* string, prefix included (`"~:ab"` is cacheable). String **values** are never cached — only keywords, symbols, tags anywhere, and strings in map-key position. |
| Cache code = `^` + base-44 digits | **VERIFIED** | "The number is converted to a one or two digit string expressed ASCII 48-91, inclusive, for numerals with a "^" prefix, i.e., "^c" or "^cc"."; `CACHE_CODE_DIGITS = 44; BASE_CHAR_INDEX = 48;` | One digit for the first 44 entries, two after. The code is itself a string, so it works in JSON and MessagePack alike. |
| Max cache size 44×44 = 1,936; behaviour when full | **VERIFIED** | "Since there are 44 numerals and up to 2 digits, the possible range of cache codes is from 0 to 44^2."; `MAX_CACHE_ENTRIES = CACHE_CODE_DIGITS * CACHE_CODE_DIGITS;`; "When the counter reaches its maximum it wraps to 0, the map is discarded, and the process starts again." (`if(index == MAX_CACHE_ENTRIES) init();`) | Full = **discard everything and restart**, not LRU. Both sides reset at the same count. |
| Cache scope | **NUANCED — per top-level value; the spec text is silent** | `WriterFactory`: `emitter.emit(o, false, writeCache.init());` · `ReaderFactory`: `return (T) p.parse(cache.init());` | The spec never states the scope; the reference implementation re-initializes the cache on every top-level `write`/`read`. So the scope is one written value, not the writer or the connection. |
| Read-side symmetry | **VERIFIED** | "Because the writer and the reader encounter cacheable values in the same order, cache code generation stays in sync." (reader = "an incrementing counter and an array") | The cache is implicit — defined by traversal order, never transmitted. Consequence: no random access into a cached value; a reader must decode from the start. This is the property a seekable chunk format must **not** copy. |
| Ground vs extension types | **VERIFIED** | "instances of ground types are represented directly in MessagePack or JSON … Instances of extended types are never represented directly in MessagePack or JSON, they are always encoded." | Ground: null, string, boolean, integer, float, bytes, array, map. Built-in extensions (keyword, symbol, bigdec, bigint, time, uuid, uri, char, set, list, cmap, link, …) use the same tag mechanism as user types. Extensions may be defined in terms of other extensions ("Recursive Extensions"). |
| Unknown tag → `TaggedValue` | **VERIFIED** | "the encoded value cannot be decoded and is returned as an instance of a special TaggedValue type with two properties, a tag and a value … If a TaggedValue instance is written with Transit, the tag and value are used for the encoded form. ensuring that TaggedValues roundtrip correctly." | Unknown types survive a read-modify-write hop unchanged. |
| JSON / JSON-Verbose / MessagePack | **VERIFIED** | "In normal JSON mode, caching is enabled (explained below) and maps are represented as arrays with a special marker element. … In JSON-Verbose mode, caching is disabled and maps are represented as JSON objects." Table headers: "MessagePack \| JSON \| JSON-Verbose (no caching)". | MessagePack mode caches too (`new WriteCache(true)`). Map-as-array marker is `"^ "`. |
| Composite-key maps (`cmap`) | **VERIFIED** | Table row: "map w/ composite keys \| cmap \| array \| [k1, v1, ...] \| `["~#cmap", [k1, v1, ...]]`" | A map with any non-stringable key is written as a tagged flat array of alternating keys and values. |

Scope note in the spec: "Transit defines the rules for encoding and decoding semantically typed values. It does not define how
encoded data is stored, transmitted, or otherwise used."

### A10. Fressian

Sources: wiki <https://github.com/Datomic/fressian/wiki> (pages Rationale, Caching, Extensibility; raw markdown read) ·
code `Datomic/fressian` `master`: `src/org/fressian/impl/Codes.java`, `impl/Ranges.java`, `FressianWriter.java`,
`FressianReader.java`, `StreamingWriter.java`.

| Claim | Verdict | Quote / code line | Nuance |
|---|---|---|---|
| Priority cache ("cached" objects) | **VERIFIED** | Wiki/Caching: "Fressian can cache values that it encounters, associating those values with a short (often single byte!) bytecode sequence. … Caching is off by default, but can be activated by passing true to `writeObject`". Codes: `PRIORITY_CACHE_PACKED_START = 0x80; PRIORITY_CACHE_PACKED_END = 0xA0; GET_PRIORITY_CACHE = 0xCC; PUT_PRIORITY_CACHE = 0xCD;` | Opt-in **per value by the writer** (domain knowledge), for any value, not only strings. First 32 entries get a 1-byte code; later ones `GET_PRIORITY_CACHE` + integer index. No size cap. Explicit `PUT` marks the defining occurrence in-band. |
| Struct cache | **VERIFIED** | Wiki/Extensibility: "The first sixteen structs encountered get single byte codes starting at `STRUCT_CACHE_PACKED_START`. Subsequent structs are encoded as the bytecode `STRUCT`, plus the ordinal number of the struct's first appearance."; wiki/Caching: "Tags are automatically cached". | A type's (tag, field count) is written once per stream (`STRUCTTYPE = 0xEF`), then referred to by ordinal. This is the Transit `~#tag` cache done in binary. |
| Tag + handler extensibility | **VERIFIED** | "A Struct definition consists of the `STRUCT_TYPE` byte code, followed by the struct tag (a string uniquely naming the struct), followed by the number of fields in the struct (an integer), followed by the fields."; "Because the structs encode a fieldcount, naive readers can read structs they have never seen before."; reader: `return new TaggedObject(tag, readObjects(fields));` | The field count in the definition is what makes an unknown type **skippable and re-emittable** without its handler. |
| Open / closed chunked collections | **VERIFIED** | `BEGIN_CLOSED_LIST = 0xED; BEGIN_OPEN_LIST = 0xEE; END_COLLECTION = 0xFD;` `StreamingWriter`: "Begin a variable-length closed list. This allows you (the writer) to write very large things, or things of unknown size, without having to place them in a collection first." / "An open list can be terminated either by a call to endList, *or* by an end of stream." | Also chunked bytes and strings: `BYTES_CHUNK = 0xD8; STRING_CHUNK = 0xE2;` with `BYTE_CHUNK_SIZE = 65535` (`Ranges.java`) — a 64 KiB − 1 chunk unit. |
| Cache reset / mid-stream entry | **VERIFIED** | "The use of caching makes reading a Fressian stream stateful -- readers have to start reading from where caching began in order to understand the cache codes. … you can create such a point by calling resetCaches" (`RESET_CACHES = 0xFE`) | Same sequential-state limit as Transit, but with an explicit, writer-chosen resynchronization point — i.e. a chunk boundary. |
| Datomic uses it for storage / wire | **VERIFIED (storage); UNVERIFIED-HERE (wire)** | <https://docs.datomic.com/operation/capacity.html>: "the Fressian format has packed encodings of, e.g. small numbers etc, as well as options for semantic compression that we leverage to help mitigate the datom encoding (E, A, and T parts)" | Storage segments: confirmed by the official docs. Peer/transactor wire use: no primary statement was found in the pages read. |

---

## Part B — mechanism cards

Each card: what it is, the mechanism that matters to static Eve, primary source. Quotes were read this session.

### B1. Apache Arrow IPC, columnar layouts, C Data Interface

Sources (`apache/arrow` `main`): `docs/source/format/Columnar.rst` = <https://arrow.apache.org/docs/format/Columnar.html> ·
`docs/source/format/CDataInterface.rst` = <https://arrow.apache.org/docs/format/CDataInterface.html> · `cpp/src/arrow/dataset/scanner.h` · `cpp/src/parquet/properties.h`.

- **What**: an in-memory columnar layout that is also its own wire format — "Relocatable without "pointer swizzling", allowing for true zero-copy access in shared memory"; "O(1) (constant-time) random access".
- **Record batch + dictionary batch = explicit per-stream cache.** Stream order: "`DictionaryBatch` and `RecordBatch` messages may be interleaved, but before any dictionary key is used in a `RecordBatch` it should be defined in a `DictionaryBatch`." `table DictionaryBatch { id: long; data: RecordBatch; isDelta: boolean = false; }`.
- **Delta vs replacement**: "A dictionary batch with `isDelta` set indicates that its vector should be concatenated with those of any previous batches with the same `id`." / "if `isDelta` is set to false, then the dictionary replaces the existing dictionary for the same ID." One dictionary `id` "can be referenced one or more times in the schema, so that dictionaries can even be used for multiple fields."
- **Seekable file vs stream differ**: "The IPC File format does not support dictionary replacement, i.e. only one non-delta dictionary batch can be emitted for a given dictionary ID." — random access forces an append-only dictionary. (Direct precedent for an append-only per-container key table.)
- **Alignment**: "allocate memory on aligned addresses (multiple of 8- or 64-bytes) and pad (overallocate) to a length that is a multiple of 8 or 64 bytes. When serializing Arrow data for interprocess communication, these alignment and padding requirements are enforced. If possible, we suggest that you prefer using 64-byte alignment". IPC message body "must be a multiple of 8 bytes". So: 8 required, 64 recommended.
- **Extension types** are field metadata over a storage type: `'ARROW:extension:name'` ("use a "namespace"-style prefix") + `'ARROW:extension:metadata'`. Unknown extension degrades to storage: "an implementation that does not support this UUID type will simply interpret it as a `FixedSizeBinary(16)` and pass along the `custom_metadata`".
- **StringView/BinaryView** (format 1.4, "adapted from TU Munich's UmbraDB"): 16-byte views; "Short strings, length <= 12" are inline; long = `length | prefix | buf. index | offset`, "A copy of the first four bytes of the string is stored inline in the prefix".
- **Run-End Encoded**: "each run is represented as a value and an integer giving the index in the array where the run ends" → "relatively efficient random access from a logical index using binary search".
- **Validity**: LSB-numbered bitmap, `is_valid[j] -> bitmap[j / 8] & (1 << (j % 8))`; may be absent when null count is 0.
- **C Data Interface**: two C structs; ownership by callback — `void (*release)(struct ArrowArray*); void* private_data;`; "Consumers MUST call a base structure's release callback when they won't be using it anymore"; alignment only "recommended, but not required … Consumers MAY decide not to support unaligned memory."
- **Batch sizing in practice** (rows, not bytes): Arrow C++ dataset scanner `kDefaultBatchSize = 1 << 17; // 128Ki rows`; Parquet→Arrow reader `kArrowDefaultBatchSize = 64 * 1024` rows; DuckDB vector 2,048 (C1). No byte-size rule in the format.
- **For static Eve**: the closest *wire* match for "chunk + its dictionary"; but it is strictly typed-columnar (heterogeneous EDN needs unions, see B-variant card) and dictionaries are per column id, not a shared key/tag cache.

### B2. Semi-structured values inside columnar files — Parquet VARIANT + shredding, Arrow unions / `arrow.parquet.variant`, Vortex, Lance

Sources: `apache/parquet-format` `master` (pom `2.15.0-SNAPSHOT`): <https://github.com/apache/parquet-format/blob/master/VariantEncoding.md>,
<https://github.com/apache/parquet-format/blob/master/VariantShredding.md>, `LogicalTypes.md` · Arrow `Columnar.rst` (Union Layout), `CanonicalExtensions.rst`
= <https://arrow.apache.org/docs/format/CanonicalExtensions.html#parquet-variant> · Iceberg `format/spec.md` · Spark 4.0.0 release notes
<https://spark.apache.org/releases/spark-release-4-0-0.html> · Vortex `develop`: `vortex-array/src/dtype/mod.rs`, `encodings/parquet-variant/src/lib.rs` · Lance `docs/src/guide/json.md`.

- **What**: "A Variant is encoded with 2 binary values, the value and the metadata." A value is a Primitive, Short string, Object ("An unordered collection of string/Variant pairs") or Array. Goal: "efficiently queried by path … even in the presence of very wide or deep structures."
- **Metadata dictionary (the key cache)**: header byte (`version` = 1 in 4 bits, `sorted_strings` bit, `offset_size_minus_one` 2 bits) · `dictionary_size` · `dictionary_size + 1` offsets · UTF-8 `bytes`. Offsets are 1–4 bytes wide, chosen per dictionary. "All field names of a Variant, whether shredded or not, must be present in the metadata."
- **Value header byte**: low 2 bits `basic_type` (0 primitive, 1 short string = "A string with a length less than 64 bytes", 2 object, 3 array) + 6-bit `value_header` (primitive type id, or string length, or the width fields below).
- **Object layout**: `num_elements` (1 or 4 bytes, `is_large`) · `field_id` × n · `field_offset` × (n+1) · values. "A `field_id` is an index into the dictionary in the metadata." Widths 1–4 bytes each (`field_id_size_minus_one`, `field_offset_size_minus_one`). "The field ids and field offsets must be in lexicographical order of the corresponding field names … However, the actual `value` entries do not need to be in any particular order." → "a binary search on field IDs (combined with metadata lookups) may be used to find a field with a given name."
- **Array layout**: `num_elements` · `num_elements + 1` offsets · values → O(1) `nth`.
- **Random access inside one value, no decode**: yes — O(log n) field lookup, O(1) index, and "each nested Variant value is contiguous and self-contained … the representation of an inner Variant value, when paired with the metadata of the full variant, is itself a valid Variant." Reaching *the row* still costs a Parquet page decode (a container property, not the encoding's).
- **How keys are shared**: one dictionary per top-level value, shared by all its nested objects. Across rows nothing is shared by the encoding — each row has its own `metadata` binary; cross-row sharing is left to the column encoder (inference: Parquet dictionary-encodes identical `metadata` byte strings). There is no per-chunk key dictionary in the spec.
- **Shredding**: a `typed_value` column beside `value`; (`value`, `typed_value`) = (null, null) missing · (non-null, null) any type · (null, non-null) the shredded type · (non-null, non-null) "a partially shredded object". Object fields shred into `required group` children each with its own `value`/`typed_value`. Gains: "column statistics for data skipping, and partial projections"; "readers do not need to read the `value` column if `typed_value` fields satisfy the required fields". Cost: "Different files may contain conflicting shredding schemas."
- **Evolution**: "new types may be added to the specification without incrementing the version ID. In such a situation, an implementation should be able to read the rest of the Variant value if desired." The primitive set is closed (no user tags).
- **Status**: Parquet `VARIANT(1)` logical type is in `LogicalTypes.md` (GitHub release notes list "Add Variant logical type annotation" under 2.11.0 and "Clarify variant shredding spec" under 2.12.0 — release page read through a summarizer). Spark: "[SPARK-45827] Add VARIANT data type" in 4.0.0. Iceberg: "Support for Variant is added in Iceberg v3."
- **Arrow**: heterogeneous values = unions. "Dense union represents a mixed-type array with 5 bytes of overhead for each value" (int8 type id + int32 offset); sparse union = "the child arrays are each equal in length to the length of the union". Type ids are 8-bit ("more than 128 possible types can be modeled as a union of unions"). Arrow **has** adopted variant as a canonical extension: "Extension name: `arrow.parquet.variant`", storage = struct of non-nullable `metadata` + `value` and/or `typed_value`.
- **Vortex**: yes, in `develop` code — `DType::Variant(Nullability)` ("may also carry a typed, row-aligned shredded representation for selected paths"), crate `encodings/parquet-variant` ("Vortex support for Arrow's canonical `arrow.parquet.variant` extension type … also supports shredded variant values"), and `vortex-json` (`json_to_variant`, `ShreddingSpec`). The docs page `docs/concepts/dtypes.md` in the same tree still says variants are "not yet" supported; whether Variant is in a frozen edition was not checked.
- **Lance**: JSON only — "Lance stores JSON data internally as JSONB (binary JSON) using the `lance.json` extension type." No variant or shredding encoding appears in its encoding spec.
- **For static Eve**: the nearest production match for "EDN-shaped value + key dictionary + in-place get + shred the homogeneous part". Gaps to close: keys are strings only (EDN needs keyword/symbol/composite keys); dictionary scope is the value, not the chunk; no extension tags.

_Part B continues — cards pending._

---

## Part C — synthesis

### C1. Chunk / unit-size precedents

Marks: **◆64K** = default is exactly 65,536 bytes · **◆1M** = default is exactly 1,048,576 bytes · (≈) = close but not
exact. Every row was read in the cited primary source this session.

| System | Unit | Default | Rows or bytes | Mark | Primary source |
|---|---|---|---|---|---|
| Vortex | data block coalescing target (pre-compression) | `1 << 20` | bytes, uncompressed | **◆1M** | `vortex-file/src/strategy.rs` (A1a) |
| Vortex | zone-map / row block | 8,192 | rows | | same (A1b) |
| Vortex | dictionary cap | `1024 * 1024` bytes or 65,535 entries | bytes / entries | **◆1M** | `vortex-layout/src/layouts/dict/writer.rs` |
| Vortex | first tail read | "at least 64KB (u16::MAX bytes)" | bytes | (≈) | `footer.fbs` |
| Lance | disk page (per-column write buffer) | `8 * 1024 * 1024` | bytes | | `lance-encoding/src/encoder.rs` (A2a) |
| Lance | mini-block | ≤ `8 * 1024 - 6` bytes and ≤ 4,096 values (format max 32 KiB in 2.1) | bytes + values | | `…/primitive/miniblock.rs` (A2b) |
| Nimble | stripe / stream chunk | 256 MiB raw / 512 KiB–20 MiB raw | bytes | | `VeloxWriterOptions.h` (A3) |
| Parquet (Java, C++, Rust, Iceberg) | data page | `1024 * 1024`, and ≤ 20,000 rows | bytes | **◆1M** | A4 |
| Parquet (Java, C++, Rust) | dictionary page limit | `1024 * 1024` (Iceberg: 2 MiB) | bytes | **◆1M** | A4d |
| Parquet | row group | 128 MiB (Java, Iceberg) · 1,048,576 rows (C++, Rust) | bytes / rows | | A4c |
| Parquet format docs | recommended page | "8KB" | bytes | | parquet.apache.org configurations |
| ORC — Java | compression chunk | `256 * 1024` | bytes | | `OrcConf.java` (A5) |
| ORC — C++ | compression chunk | `64 * 1024` | bytes | **◆64K** | `c++/src/Writer.cc` (A5) |
| ORC | stripe / row-index stride | 64 MiB / 10,000 rows | bytes / rows | | A5 |
| Datomic | index/log segment | "up to about 50kb"; 1,000–20,000 datoms | bytes / datoms | | docs.datomic.com capacity (A6) |
| WebAssembly | linear-memory page | 65,536 | bytes | **◆64K** | core spec (A8) |
| Fressian | bytes/string chunk | 65,535 | bytes | (≈) | `impl/Ranges.java` (A10) |
| Transit | cache | 1,936 entries, reset per top-level value | entries | | A9 |
| ClickHouse | `min_compress_block_size` | `65536` | bytes, uncompressed | **◆64K** | <https://github.com/ClickHouse/ClickHouse/blob/master/src/Core/Settings.cpp> — "By default, 65,536." |
| ClickHouse | `max_compress_block_size` | `1048576` | bytes, uncompressed | **◆1M** | same — "By default, 1,048,576 (1 MiB)." |
| ClickHouse | `index_granularity` (mark) | 8,192 (and `index_granularity_bytes` = `10 * 1024 * 1024`) | rows | | `src/Storages/MergeTree/MergeTreeSettings.cpp` |
| ClickHouse | `DEFAULT_BLOCK_SIZE` (rows per processing block) / I/O buffer | 65,409 rows ("65536 - PADDING_FOR_SIMD - (PADDING_FOR_SIMD - 1)") / `DBMS_DEFAULT_BUFFER_SIZE = 1048576ULL` | rows / bytes | (≈) / **◆1M** | `src/Core/Defines.h` |
| Bigtable | SSTable block | "typically each block is 64KB in size, but this is configurable" | bytes | **◆64K** (as "64KB") | Chang et al., OSDI 2006, §4 <https://research.google/pubs/bigtable-a-distributed-storage-system-for-structured-data/> |
| HBase | HFile block | `64 * 1024` | bytes | **◆64K** | `hbase-common/…/HConstants.java` `DEFAULT_BLOCKSIZE` |
| Kudu | CFile block | `256*1024` | bytes | | `src/kudu/cfile/cfile_writer.cc` `cfile_default_block_size` |
| RocksDB / LevelDB | data block | `4 * 1024` | bytes, uncompressed | | `include/rocksdb/table.h`; `include/leveldb/options.h` |
| Cassandra | compression chunk | `1024 * 16` (was 64 KiB before 4.0: "CASSANDRA-13241 lowered the default chunk_lengh_in_kb … from 64kb to 16kb") | bytes | (was ◆64K) | `CompressionParams.java`; `NEWS.txt` (trunk) |
| DuckDB | vector / row group / block | 2,048 rows / 122,880 rows / 262,144 bytes | rows / bytes | | `common/vector_size.hpp`; `storage/storage_info.hpp` |
| HyPer Data Blocks | block | "Typically, we store up to 2^16 records in a Data Block." | rows | 65,536 **rows**, not bytes | Lang et al., SIGMOD 2016 <https://db.in.tum.de/downloads/publications/datablocks.pdf> |
| Snappy framing | chunk (uncompressed) | "must be no longer than 65536 bytes. This allows consumers to easily use small fixed-size buffers." | bytes | **◆64K** | <https://github.com/google/snappy/blob/main/framing_format.txt> |
| LZ4 | match window / frame block max | 64 KB window ("The maximum `offset` value is 65535") / one of "64 KB \| 256 KB \| 1 MB \| 4 MB" | bytes | options include ◆64K and ◆1M | `doc/lz4_Block_format.md`, `doc/lz4_Frame_format.md` |
| Zstandard | block max / recommended window | "128 KiB (131.072 bytes)" / up to 8 MB | bytes | | `doc/zstd_compression_format.md` |
| Kafka | `batch.size` / `max.request.size` / `message.max.bytes` | 16,384 / `1024 * 1024` / `1024 * 1024 + Records.LOG_OVERHEAD` | bytes | **◆1M** (request) | `ProducerConfig.java`; `ServerLogConfigs.java` (trunk) |
| gRPC | max receive message | "Defaults to 4MB(4*1024*1024 bytes)" | bytes | | `include/grpc/impl/channel_arg_names.h` |

_More rows pending: TLS / HTTP/2 / deflate / QUIC / WebSocket / WebRTC / Cloudflare / S3, Arrow batch sizing, Avro sync
interval, HDF5 and Zarr, CPU caches._

### C2. Ranking — _pending_
### C3. Mechanisms list — _pending_
