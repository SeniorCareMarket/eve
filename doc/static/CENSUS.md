# D2 — the integration census

The census is file:line-level and lives in six files; this page is the map from the research plan's Part 3 to
them, the allocator surface read first-hand, the tree differences, and the defects found on the way.

| File | Lines | What it holds | Status |
|---|---:|---|---|
| `census/TYPES-AND-DEREF.md` | 563 | every persistent type (ids, layouts, `IPlain`, storability, hash and equality, seq, metadata, printing, constructors); every deref path of `eve.atom`; the store path; size limits. 60-odd rows probed on the JVM | Part 1 and the `eve.atom` half complete; continued in `-2` |
| `census/TYPES-AND-DEREF-2.md` | 262 | STM; how values travel between threads on every adapter; the legacy ClojureScript atom; what the tally needs of an ingest; the journal adapter | complete |
| `census/BYTE-PATHS.md` | 546 | every tag with its encoder and decoder on each codec; coverage of every value kind; 28 JVM probes of the decoder; the flat codec; `eve.encoding`; `eve.net.codec` / `wire` / `atom` / `pages` | Parts 1–2 complete, 3–4 partial; continued in `-2` |
| `census/BYTE-PATHS-2.md` | 250 | every digest and hash; the rest of the network layer and which wire generation is live; defenses against untrusted bytes; FORMAT-DECISIONS and HOST-CONTRACT clause by clause; the fixture discipline | complete |
| `census/DEFTYPE-AND-COLUMNAR.md` | 646 | the seven macros and the path per host; layout rules; type identity; what the macro emits on each emission family and where a sister hooks in; field classes; every `eve-deftype` in the tree; EveArray, Dataset, tensor, `eve.obj`, `IBulkAccess`; recorded performance | complete but for a few leftovers named in its first line |
| `census/VS-KERNEL.md`, `-2` | 360, + | the kernel allocator (granularities, 16 classes, ownership, every public entry); page adoption and the wire page; per-thread structures; whether a privately built region can be published without a copy | §0–§3 complete; host boundary, type identity leftovers and Eve's remains in atom.broker in `-2` as banked |
| `research/FORMATS.md`, `-2` | 289, + | the proto-research's claims against primary sources (Vortex, Lance, Nimble, Parquet, ORC, Datomic, Safetensors, DLPack, WebAssembly, Transit, Fressian); mechanism cards; the chunk-size precedent table | Part A complete; cards and synthesis in `-2` as banked |

Every row in those files is tagged *read*, *ran* or *doc*, with the path and line at eve `4a92129` or atom.broker
`163cb656c`.

## Part 3, item by item

| Research-plan item | Where | The finding that matters most |
|---|---|---|
| 1. Every persistent type — `IPlain`, storable / serialize, hash, constructors | `census/TYPES-AND-DEREF.md` §2.1–2.9 | One `.cljc` definition per type for every host; 11 types implement `IPlain`; no constructor throws outside a swap (it allocates in the process's own domain); four reads allocate or write |
| 2. Every materialization / deref path | same §3; `-2` §1–3 | Fifteen paths bypass `IPlain`; `@a` hands out live slab values for types without it; `swap!` returns its function's result unpinned; STM materializes a whole cell, history included, on every read; Node and browser workers exchange EDN text |
| 3. Every existing byte path, and what each guarantees | `census/BYTE-PATHS.md`; `-2` | Flat bytes are key and digest bytes, not a wire form; the live network wire is the kernel's block images under a snapshot, pinned byte-exact by fixtures cut by ValueScript; `eve.net.pages` has no caller; no decoder validates; six scalar encodings coexist |
| 4. The allocator's public and semi-public surface | below; `census/TYPES-AND-DEREF-2.md` §4; `census/VS-KERNEL.md` §1 | One block per call, on both allocators; no run, reserve, placement or adoption entry |
| 5. The deftype generation pipeline on both macro paths | `census/DEFTYPE-AND-COLUMNAR.md` Part 1 | One expansion function for every host; two emission families (an instance carries its slab I/O, or does not); the registry is not where the information lives; a sister hooks in at one place |
| 6. The dataset / columnar stack | same, Part 2 | Columns are dense typed-array blocks, numeric only; no row view on a Dataset; the bulk path copies a column out and back; no column-wise form of a collection of deftype instances exists |

## The allocator surface of canonical Eve (read first-hand)

`src/eve/deftype_proto/alloc.cljc` (1,733 lines), `src/eve/mem.cljc`, `adapters/slab-io/protocol/src/eve/slab_io.cljc`.

| Surface | Where | What it is |
|---|---|---|
| `ISlabIO` — 12 methods | `alloc.cljc:95-139` | `read-u8/u16/i32`, `write-u8!/u16!/i32!`, `cas-i32!`, `read-bytes`, `write-bytes!`, `alloc!` (one size → one block), `free!`, `copy-block!`. Every type reads through it, by the `eve.slab-io` macros |
| slab offsets | `:60-85` | `[class:3 | block:29]` in a signed i32; −1 nil; class 6 = the overflow class, its block index a byte offset |
| `ctx-alloc!` | `:533-700` | class by size; scan the bitmap from the class's cursor; CAS the bit; store the cursor; subtract from the free count. Full → grow (a heap class by replacement `:467-531`, a file class by CAS on its block total and a re-map `:635-685`), then spill to the next class, then the overflow class |
| `ctx-free!` | `:725-752` | reset the block's descriptor row (count, flags, digest pointer), clear the bit, add to the free count |
| descriptor rows | `:702-771` | per block, out of band: count, flags, digest-side-block pointer |
| allocation logs | `:1077-1099`, `:1423-1485` | `LoggingSlabIO` over a domain (a swap attempt's log), `scope-sio` over that (a transient's); `owns?`, `keep-owned!`, `free-owned!`, `logged-allocs`, `stop-logging!` |
| the hot read path | `:1127-1223` | `node-heap-array`, `node-base`, `node-i32`, `node-u8`, `node-bytes`: plain array loads when the domain is this process's heap; any other slab I/O keeps the protocol call |
| domain openers | `:1601-1731` | `mmap-domain-sio!` (`.slabN`, `.bm`, three descriptor files per class, `.slab6`), `heap-domain-sio!` (1 MB a class to start), `default-sio`, `current-sio` |
| bitmap primitives | `src/eve/mem.cljc:289-387` | `imr-bitmap-find-free`, `-find-free-bulk`, `-alloc-cas!` (one bit), `-free!` |
| **absent** | grep `alloc-run`, `claim-run`, `bulk`, `reserve`, `adopt` over `src adapters` | any entry that takes more than one block, a placement list, or bytes already laid out |

## Where the trees differ

| | canonical Eve (`eve`) | the kernel (`atom.broker`, `vs/`) |
|---|---|---|
| A block | starts with a type id; children by 7-byte tagged pointers and 4-byte offsets | a 256-byte own header (type CID, own offset, SHA-256) and 64-byte slots |
| Allocator | 6 bitmap classes of 32–1024 B + a coalescing class; any participant allocates by CAS | 16 classes of 4–10,240 B + a coalescing class in 1 MiB regions with 64 KiB / 256 KiB / 1 MiB sub-pages; a page's owner allocates, others free through a return queue |
| Scalars | tags after `EE DB` | 16-byte type CIDs; no uuid, instant, big number or bytes |
| A foreign value stored into an atom | refused (the model says copied) | refused (the model says copied); no copier in either |
| Bringing bytes in | nothing | whole-page adoption beside the allocator; per-block adoption with two hashes a block |
| What `eve.net` in canonical Eve speaks | — | the kernel's formats, re-implemented portably over vectors of bytes (`eve.net.codec`, `eve.net.pages`) |
| The `eve/` subtree in atom.broker | — | removed at `163cb656c`; Eve is a dependency pin |

One more difference, read in two census files and **not reconciled by execution**: the kernel's comment derives a
type's 32-bit word "through Clojure's `hash` of a string" (`program_runtime.vs:85453-85458`,
`census/VS-KERNEL-2.md` §5), while canonical Eve at the pin derives it from XXH3-128 of the name's key bytes
(`src/eve/deftype/registry.cljc:47-67`, a declared break of 2026-10-25). If both are as read, the two sides do not
agree on a type's word.

## Defects found on the way

Not this lane's to fix. Each is a measurement at the pin with its provenance; each is re-derived at its own pin
before anyone works it.

| # | What | Evidence | Provenance |
|---|---|---|---|
| 1 | An atom whose whole value is a string past 1,016 bytes reads back `nil` | `(deref (eve.atom/atom ::s (apply str (repeat 1100 "x"))))` → `nil`; 1,000 chars fine; the scalar block carries no length and the reader takes `(dec block-size)` bytes, and the overflow class's size is 1 (`alloc.cljc:1514-1522`, `data.cljc:87-91`) | ran — JVM 21.0.12, eve `4a92129`, fresh heap domain and a fresh disk domain (`census/BYTE-PATHS.md` §1.6 P-1; `census/TYPES-AND-DEREF.md` §4.6 G1–G4) |
| 2 | A two-ref STM transaction can commit one ref and not the other | `(dosync (ref-set* ra 1) (ref-set* rb foreign))` with `foreign` a slab value of another ref's domain: throws at the second install, `ra` is 1 (`src/eve/stm.cljc:836-841`) | ran — JVM, `4a92129`, fresh heap domains (`census/TYPES-AND-DEREF-2.md` §1 R5) |
| 3 | Every plain deref of an N-element list allocates N blocks in the atom's domain; nothing frees them | free blocks 32,716 → 32,666 after one deref of a 50-element list (`src/eve/list.cljc:157-161,195-203`) | ran — JVM, `4a92129` (`census/TYPES-AND-DEREF.md` §2.9 F1–F3); and P5 here |
| 4 | Every deref of an `eve.sorted` collection builds a copy in the process's slab; nothing frees it | ten derefs of a 3-entry sorted map took 130 blocks (`src/eve/sorted.cljc:377-387`) | ran — same (§2.9 F8–F9) |
| 5 | A throw inside a swap's function, or in the store, frees none of what the attempt allocated | the attempt's only handler is `(finally (unpin-epoch! ds))` (`src/eve/atom.cljc:982,1085`) | read (`census/TYPES-AND-DEREF-2.md` §4.2) |
| 6 | A non-BMP character loses its high bits with no refusal on hosts whose char is a code point; on ClojureScript an unregistered record is written as zero bytes and reads back `nil` | `codec.cljc:524-525`; `codec.cljs:590-592` | read (`census/BYTE-PATHS.md` §1.5) |
| 7 | On the kernel, a registered adopted or block page can be evicted and its region reused while an atom's slot names a block in it | the region hold for stored values is described in comments (`eve.vs:2273-2275`) and its code is deleted (`retire.vs:15-22`); `eve_region_evict` checks the owner and the class's current page only | read, not run — atom.broker `163cb656c` (`census/VS-KERNEL-2.md` §3.7 e) |
| 8 | Documents behind the code | `doc/unification/FORMAT-DECISIONS.md` §7 (the type-id hash), `doc/network-atoms/WIRE.md` §7 (the v2 seal image), `src/eve/deftype_proto/data.cljc:557-563` ("the deftype constructor guard"), `doc/architecture.md:118-121` (heap classes "fixed"), `src/eve/stm.cljc:14` (the cell's shape) | read (the census files' "mismatch" tables) |
