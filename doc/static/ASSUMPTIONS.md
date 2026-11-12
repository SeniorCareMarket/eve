# D1 — the assumption register

Every assumption under the static-Eve design, re-derived at the pin: verdict, the evidence, the kill check the
research plan named, and a provenance line. Measurements are in `MEASUREMENTS.md` (M1–M7); file:line detail is in
`census/*.md` and `research/*.md`, cited here by file and section.

**Pins.** `SeniorCareMarket/eve` `4a92129` (main tip, release 0.9.3.1, 2026-11-09). `johnmn3/atom.broker` `163cb656c`
(main tip, 2026-11-11 1:58 AM ET). `johnmn3/valuescript`: no commits. **Tools:** OpenJDK 21.0.12, Clojure 1.12.0,
Node 22.23.4 (unused: no ClojureScript build was made); no `clojure` CLI, no `bb`. **Kinds of evidence:** *read* (the
code at the pin, file:line), *ran* (a probe or a one-liner executed here, its output committed or quoted in the
census), *measured* (a timed or sized probe, both arms in one session). Nothing ran on any host but the JVM.

## Corrections first — premises that died under re-derivation

Each is a claim of the research plan or of the proto-research it builds on, the discriminator that killed it, and
what stands in its place.

| # | The premise | What the pin says | Discriminator |
|---|---|---|---|
| C1 | "The integration census additionally covers atom.broker main's `eve/` subtree, because the net/codec surface lives there" | atom.broker has no `eve/` subtree: `163cb656c` "Land the Eve extraction: eve/ subtree removed; Eve lives in SeniorCareMarket/eve and atom.broker consumes it as a dep pin (0.9.3.1)". The net layer is 29 files of canonical Eve, `src/eve/net/` | TREE — `git log -1`, `ls` |
| C2 | "Container = the 1 MiB Eve page"; "only a page's owner allocates / frees in it; foreign frees go through return queues" | That is the **kernel's** allocator (atom.broker `vs/src/vs/kernel/wasm/alloc.vs`: `REGION_SIZE` 1,048,576, sub-pages of 64 KiB / 256 KiB / 1 MiB, 16 classes, owner-only allocation, return queues). Canonical Eve has **no pages**: six bitmap classes of 32–1024 B and a coalescing class 6, any thread or process allocating by CAS on a bitmap (`src/eve/deftype_proto/alloc.cljc:533-606`). In canonical Eve, 1 MiB appears as `eve.net.pages`' `REGION` (a writer of kernel pages with no caller in `src/`) and as a heap class's starting capacity | TREE — `census/VS-KERNEL.md` §1.1, §1.4; `census/BYTE-PATHS.md` §4.4 |
| C3 | "Registry ids are expansion-order and cannot travel" | Ids are derived from the type's name: `type-id = 0x40 + (h mod 173)`, `name-hash = h & 0xFFFFFF`, `h` = the int32 fold of XXH3-128 of the name's key bytes — "the same in every process" (`src/eve/deftype/registry.cljc:12-67`; declared break 2026-10-25; pinned on every host by `test/eve/deftype/type_id_test.cljc`) | TREE |
| C4 | "Under the construction law (Eve data constructed outside a swap throws; the heap-slab fallback is abolished)" | Not in the tree. `current-sio` is `(or d/*domain-sio* (default-sio))` (`alloc.cljc:1726-1731`): a constructor outside a swap allocates in the process's heap domain; **nothing throws at construction**; the refusal is at store time. "The deftype constructor guard" (`data.cljc:557-563`) is a stale comment; `eve.guard` is a read guard. On ClojureScript a value built outside a swap lands in the module slab every in-process atom uses — and in an open disk domain's files, unlogged | TREE + ran — `census/TYPES-AND-DEREF.md` §0 C1–C3, §2.8 (probes A1–A11) |
| C5 | "The flat codec … is exactly your packed/wire format … cross-process" | At the pin flat bytes are **key bytes and digest input only**: map keys, set elements, sorted-collection keys, the type-id hash, the root digest. No thread descriptor, journal record or network payload uses them; the JS codec refuses `value->eve-bytes` outright. They decode to plain data only, and nothing reads inside them without decoding the whole | TREE — `census/BYTE-PATHS.md` §2.2, §2.7 |
| C6 | "`eve->clj` has produced plain host data … atom deref was explicitly contracted not to return Eve wrappers" | A deref returns a **live slab-backed value** for every type with no `IPlain` — every user `eve-deftype` (a DataScript `DB`), `ValueArray` — through `eve->clj`'s `:else v` arm; `swap!` returns whatever its function returned, unpinned; `eve.sorted`'s `-plain` answers another slab collection built in the process domain | TREE + ran — `census/TYPES-AND-DEREF.md` §2.2, §3.1, §3.3 |
| C7 | "One byte format lineage" | Six byte encodings of a scalar exist at the pin: Eve tags; ExtCID v3 bands and value blocks (16-byte type CIDs); `eve.net.codec` key images; the VSNP serial form (Eve's magic, but `0x07` is an int64 there and a keyword in Eve); aegis fields; JSON on the envelope | TREE — `census/BYTE-PATHS-2.md` §6 A17 |
| C8 | Watches' value delivery is a path to census | `MmapAtom` has no watches and no validator (`add-watch` → `ClassCastException`); they existed in the legacy ClojureScript atom, which nothing requires | ran — `census/TYPES-AND-DEREF.md` §3.1 (I2/I3); `-2` §3 |
| C9 | "Datomic: ~50 KB is unofficial"; "ORC's compression block is precisely 65,536 bytes"; "Vortex's per-chunk dictionary" | Datomic's own docs: "These segments range up to about 50kb each", 1,000–20,000 datoms a segment. ORC: 262,144 in the Java writer, 65,536 in the C++ writer. Vortex's dictionary is shared across a run of chunks, cut at 1 MiB or 65,535 entries; its 1 MiB is the **uncompressed** block target and its stated reason is object-store request sizing | primary sources — `research/FORMATS.md` A1c, A5, A6 |
| C10 | "Transit's cache" as the model for a per-chunk cache | Transit never caches string *values* (keywords, symbols, tags, and strings in map-key position only), scopes the cache to one top-level write, and defines it by traversal order — a reader cannot enter in the middle. "The property a seekable chunk format must not copy" | primary sources — `research/FORMATS.md` A9 |

## The register

### A1 — the flat codec is a sufficient scalar foundation · **REFUTED as stated**

The tags are a workable alphabet; they are not a sufficient foundation for a wire and storage form.

- No bytes / blob scalar: a JVM `byte[]` is written as an `:int8` eve array and derefs as a `PersistentVector` of
  longs; as a key it is a process-local reference, refused by a disk domain (ran, `census/BYTE-PATHS.md` §1.5, §1.6).
- `nil` is zero bytes with no tag. No float32. A non-BMP char loses its high bits with no refusal. A symbol segment
  past 255 bytes is refused. An instant is milliseconds in an f64 (whole seconds on clojerl).
- Not canonical across hosts: the JS codec writes an integral double and `-0.0` as int32 and an integer past 2^53
  as float64; NaN payloads are kept; a lone surrogate encodes differently per host.
- Records lose their type (on ClojureScript an unregistered record is written as zero bytes); metadata is never
  written; a sorted collection as a key loses its comparator.
- No reader validates: wrong magic is accepted on the byte-array codec, trailing bytes are ignored, a negative count
  is an empty collection, a length of 2^31−1 is an `OutOfMemoryError`, 1,000 levels of nesting a
  `StackOverflowError` (ran, P-2…P-17).
- Measured: 1.8–3.7× Transit MessagePack in bytes (M2); 2.2–5.9 µs a row to decode (M4).

Kill check — "any Eve scalar or leaf that cannot be represented, or that round-trips changed": hit several times.
What stands: the design takes tags `0x01–0x0F`, `0x14`, `0x16–0x18` and their payloads **verbatim**, adds a bytes
scalar and an explicit nil, and carries f32 / f64 / i64 exactly in typed columns.
*Prov: eve `4a92129`; read + ran (`census/BYTE-PATHS.md` Part 1–2, `-2` §6); measured (P2, P4).*

### A2 — the `eve-deftype` registry carries enough to generate a sister · **VALIDATED**

- `eve-deftype*` is one portable function for every host that expands the macro
  (`src/eve/deftype/macros.cljc:54-153`). At that point the parsed fields with offsets, sizes, hints and classes,
  the total size, the type key, the type id and name hash, and the user's protocol bodies are all in scope.
- They are in local bindings, not in the registry: the compile-time `registry` is keyed by the type's simple name
  and read only by `eve-extend-type`. At runtime a process knows, per type, a constructor and *which offsets hold
  references* — no field names, no primitive types, no total size (`census/DEFTYPE-AND-COLUMNAR.md` §1.4–1.5).
- One edit reaches every host: the `(list 'do …)` at `macros.cljc:130-153` (hook H1, same file §1.6).
- Two emission families. On the ISlabIO hosts (JVM, babashka, CLR, basilisp, jolt, clojerl, glojure, jank) an
  instance is `[… slab-off__ sio__]` — parametric in its slab I/O — and **the same class reads an image** (ran,
  P5). On the ClojureScript family an instance is `[… offset__]` and its fields are read through the process's
  module slab: there a sister emission is required.

Kill check — "layout or identity information that exists only at runtime, or only on one host's macro path": none
for layout or identity. One-family only: `IDirectSerialize`, `ISabStorable`, the pointer-tag registration
(ClojureScript). ValueScript does not run the expansion at this pin (`hosts.edn` `:vs` comment).
*Prov: eve `4a92129`; read (`census/DEFTYPE-AND-COLUMNAR.md` Part 1); ran (P5.2).*

### A3 — `IPlain` is the materialization seam · **REFUTED**

- `IPlain` is implemented by 11 types; not by the RRB vector, the value array, any user `eve-deftype`, records,
  `HeapEveArray`, `ObjArray`. Where it is implemented it does not always answer plain data.
- Fifteen paths hand a value out without it (`census/TYPES-AND-DEREF.md` §3.3): `swap!`'s return value;
  `read-pinned`; the `:host-value` readers of babashka, glojure, jank and ValueScript; the codecs' flat decode;
  `eve->clj`'s own predicate and as-is arms; `seal`; `IBulkAccess`; printing; reference decode; and more.
- The right seam is where a value leaves a domain — `plain-deref`, a read region's exit, `swap!`'s return — and the
  right mechanism is the block walk every type already registers for (`eve.retire`, the tally's `refs`), not a
  second per-type protocol method that would inherit the same gaps.

Kill check — "materialization paths that bypass `IPlain` in numbers that make the seam the wrong place": hit.
*Prov: eve `4a92129`; read + ran (`census/TYPES-AND-DEREF.md` §2.2, §3; `src/eve/deftype_proto/data.cljc:515-543`
read first-hand).*

### A4 — length-view persistence is sound · **VALIDATED, one type excepted**

- P1 (19/19) and P5 (19 of 20): Eve's own map, vector, set, sorted collections, int-map, arrays, Dataset, a user
  type and a DataScript DB read correctly from a `(buffer, length, root)` view; an update appends and rewrites no
  earlier byte; every earlier view stays equal, reads from a buffer truncated at its own length, and keeps its hash
  (property: 80 maps × 15 updates).
- **`EveList` fails**: `seq` / `next` / `pop` allocate a 12-byte header per step
  (`src/eve/list.cljc:157-161,195-203`) — a read that needs bytes past the view. A plain deref of a 50-element list
  takes 50 blocks of the atom's domain today and nothing frees them (ran, `census/TYPES-AND-DEREF.md` §2.9).
- Two other reads write: the first `hash` or `=` on a map / set / vector / list root allocates a 48-byte digest side
  block where the block has a descriptor row (`src/eve/digest.cljc:103-126`) — a buffer has no rows, so it does not
  there; and `eve.sorted`'s `-plain` builds a copy in the process slab on every deref.

Kill check — "any protocol whose correct behavior needs bytes past the view's length, or mutation of written
bytes": one — the list's seq. It is a defect of the producer, fixed there (a host cursor), not designed around.
*Prov: eve `4a92129`; ran (P1, P5 — JVM); read.*

### A5 — retention is acceptable · **VALIDATED, with a required copy-out**

- A view pins its buffer. Measured (M7): one message of a 5,000-message image pins 3,746,412 B to hold 556 B; a
  10,000-entry map built by the direct writer into an append-only buffer is 6.2 % live; each later `assoc` adds
  527 B.
- The fix is the mechanism itself: the same walk copies a view out on its own, and a deref snapshot is exactly the
  reachable blocks (ratio 1.0 at creation).
- Against the slab: a pinned reader holds an epoch, and every block retired after it, anywhere in the domain, waits
  (`src/eve/atom.cljc:413-424`). A static view holds one buffer.

Kill check — "pin ratios that make statics a memory hazard in the flagship uses with no copy-out policy": the hazard
is real and the policy exists; the plan makes it a rule (a build is compacted before it is kept; a view kept past
its parent is copied out).
*Prov: eve `4a92129`; measured (P6 §6.3).*

### A6 — columnar dictionaries pay on Eve-shaped data · **VALIDATED**

Measured (M2, M3): on messages / records / datoms the symbol table takes 24 / 31 / 21 % off, shapes a further 22 /
32 / 33 %, batches 6 / 7 / 9 %, typed vectors 9 / 36 / 35 %; the result is 0.55–0.86× Transit MessagePack (0.83× on
the repository's own EDN files); hit rates at 64 KiB are 99.1–99.6 %. Against the flat codec: 0.36–0.19×.
Not covered by the prototype: a map with non-symbol keys (3.04× MessagePack on int → int).
*Prov: eve `4a92129`; measured (P2).*

### A7 — 64 KiB is the right chunk · **VALIDATED as a target, not a cap**

- Bytes (M3): 64 KiB is within 1.2 % of one chunk on rows, under it on messages, and the smallest total on datoms
  (16-bit offsets inside a chunk). 4–16 KiB cost +3 to +46 %. 256 KiB – 1 MiB gain at most 1 % on rows.
- Reading (M4): warm scans and point reads are flat from 32 KiB up; the decode unit is 1.4 ms at 64 KiB against
  20 ms at 1 MiB; a cold scan pays the chunk's open cost once per chunk.
- The counter-design, Lance's 4–8 KiB mini-blocks, is refuted for a self-contained chunk that carries its own
  tables; mini-blocks share their page's metadata.
- Precedents read in primary sources (`research/FORMATS.md` C1): ClickHouse `min_compress_block_size` 65,536 and
  `max_compress_block_size` 1,048,576; Bigtable and HBase blocks 64 KiB; ORC's C++ writer 64 KiB; Snappy framing
  65,536; Datomic segments up to about 50 KB; the WebAssembly page; the kernel's own smallest sub-page
  (`alloc.vs:1939-1948`).
- One thing loses at 64 KiB: opaque compression per chunk (+23–35 % against one chunk). Compress per container.

Kill check — "a size that dominates 64 KiB on the heterogeneous mix, with numbers": none found.
*Prov: eve `4a92129`; measured (P2 §2, P4); primary sources for the precedents.*

### A8 — a static chunk can live in an Eve page · **VALIDATED on the kernel, with two corrections; not the question on canonical Eve**

- Kernel: no fixed class holds 64 KiB (the largest is 10,240 B); one class-15 block does. A 1 MiB region's class-15
  data region is 989,600 B, so **15** such blocks fit, not 16; a class-15 block is 4-byte aligned, so 8-byte columns
  need the chunk to pad from its own start (`census/VS-KERNEL.md` §1.3, §1.6).
- Kernel, without a copy: a page image is adopted *beside* the allocator — decoded or mapped straight into a region,
  registered under its page CID, every pointer into it rebased at read time by one table probe; with
  `vs.mmap/map-value-page!` the adoption is zero-copy. An adopted page is never allocated into and is evicted whole
  (§2.2).
- Canonical Eve: there is no page. A chunk is one class-6 block (8-aligned, any size). The tally holds it as one
  leaf only if its header byte is a known leaf kind; any unknown byte is `:opaque` and never freed
  (`census/TYPES-AND-DEREF-2.md` §4.4).

Kill check — "an ownership or class rule that makes co-residence a copy anyway": placing a chunk into an allocator
block is one `memcpy` of the chunk — no re-layout, no per-value work; the kernel's adoption path is no copy at all.
*Prov: atom.broker `163cb656c`, eve `4a92129`; read (`census/VS-KERNEL.md`); nothing was run in the kernel.*

### A9 — the allocator can consume pre-mapped spacing · **REFUTED as a present capability; VALIDATED as a mechanism**

- Neither allocator has a bulk, reserve, placement or adoption entry. Eve: `-sio-alloc!` takes one size and answers
  one block (`alloc.cljc:95-139`, `:533-606`). Kernel: `alloc_blocks` is a loop over `alloc_block`
  (`alloc.vs:4188-4201`); the nearest things are whole-page adoption and the bump-filled BLOCK PAGES for blocks
  rebuilt from wire images (`census/VS-KERNEL.md` §1.7, §2.5).
- Measured on Eve's JVM heap domain (M5, M6): claiming one run of blocks per size class by CAS on whole bitmap words
  places an image 5–12× faster than per-block allocation; through a real `swap!` the placed path is 3–45× the plain
  one, and the tally's hold of the new blocks is then ≈ 97 % of it.
- What per-value allocation costs today (the plan's "say what it costs instead"): ≈ 250 ns a block to allocate, and
  ≈ 1 µs a block to count at commit, on the JVM.

*Prov: eve `4a92129`; read; measured (P3, P3c, P6 — JVM heap domains only; no disk domain, no other host).*

### A10 — hash and equality from the binary · **split**

| What | Today | From the packed form? |
|---|---|---|
| equality of two eve roots | the content digest when both carry one — SHA-256 of the root's flat bytes (`src/eve/digest.cljc:163-199`) — else an element walk | **yes**: canonical bytes are already Eve's equality on byte-array hosts; and they already disagree with Clojure's `=` at the float edges (`{:a 0.0}` ≠ `{:a -0.0}`, `{:a ##NaN}` = `{:a ##NaN}`; ran, `census/BYTE-PATHS-2.md` §1.3) |
| `hash` of a collection | the host's fold over the host's `hash` of each decoded element; host-specific by ruling (`test/eve/collection_hash_test.cljc:18-26`) | **no** without decoding every element; it cannot ride in a cross-host form. It is computed once by a walk and kept on the static object, as the slab keeps it in the digest side block |
| a user `eve-deftype` with no `IEquiv` / `IHash` of its own | `hash` = the block's offset; `=` = same type and same offset (`src/eve/deftype_proto/sio.cljc:215-231`) | **no** — a copy has another offset. Ran (P5.2): the image of an instance is not `=` its slab original |

Kill check — "a type whose Eve hash cannot be produced from the packed form without a full walk that
materialization would beat": the collection hash needs the walk either way (it does not need the host collections);
identity-compared user types cannot be reproduced at all. That last row is a semantics choice — fork F2.
*Prov: eve `4a92129`; read + ran (`census/BYTE-PATHS-2.md` §1; P1; P5.2).*

### A11 — type-key identity is a sufficient wire contract · **VALIDATED, two additions required**

- Identity in memory is 32 bits derived from the name (C3); the full `"ns/Type"` string exists only on the host type
  and is never written. A process that has not loaded the type throws on a root of it, throws on a nested pointer,
  and leaks the block in the tally (`census/DEFTYPE-AND-COLUMNAR.md` §1.5).
- So a self-contained form must carry, once per container: the **type key string** and a **layout fingerprint** (a
  hash over field names, classes and hints in declaration order).
- Receiving cases: the type is not loaded → a generic tagged record that keeps the fields and re-emits unchanged
  (Transit's `TaggedValue`, Fressian's struct with its field count — `research/FORMATS.md` A9, A10); two versions →
  the fingerprints differ → a registered reader for that fingerprint, else the generic record; a renamed type is
  another type, as it is today (`doc/data-structures.md:385-392`).

Kill check — "an identity case keys cannot disambiguate": two unrelated types with one key string and one layout in
two programs. Nothing disambiguates that in Transit either; accepted.
*Prov: eve `4a92129`; read.*

### A12 — `:serialized` fields pack · **VALIDATED, with the reference rule**

A serialized field is a 4-byte slot holding the offset of a `[len][EVE bytes]` block
(`src/eve/deftype/slab_runtime.cljc:22-55`); the bytes are a scalar, a pointer to a collection, or a process-local
reference (`0x15`). The image copies the first two as they are (ran, P5.2). A reference cannot leave the process:
the rule a disk domain already applies — "a reference never reaches a shared domain"
(`src/eve/deftype_proto/serialize.cljc:220-226`) — is the static form's rule too.

Kill check — "a field class whose static form needs information the slab form doesn't have": `^:host` fields are
not in memory at all (nil when read back, today as well). The packed, columnar form of a type needs its field names
and hints, which are not in the slab — they come from the expansion (A2), so a type unknown to the process can be
carried as an image or as a generic record, never columnized.
*Prov: eve `4a92129`; read + ran (P5.2).*

### A13 — mutable and volatile fields freeze coherently · **VALIDATED as declared snapshot semantics, opt-in only**

- Live mutability through a deref is a behaviour programs can observe today: a `^:mutable` field written through
  the instance a plain deref returned changes the atom with no swap; `EveArray` atomics, `wait!` / `notify!`, `Obj`
  `assoc!`, tensor `mset!` (ran, `census/TYPES-AND-DEREF.md` §2.9).
- An image is a snapshot: a later in-place write to the slab original does not show in it (ran, P5.2).
- The prototype view refuses allocation and **not** writes; a product view has no write path.

Kill check — "a contract that a frozen copy violates silently": avoided only while static is opt-in at the boundary
(the deref contract unchanged), views cannot write, and the docs say a static of a mutable type is its value at the
copy.
*Prov: eve `4a92129`; read + ran.*

### A14 — ClojureScript parity is reachable · **OPEN (not runnable here); no kill found by reading**

- The ClojureScript family's types hold `[… offset__]` and read through the module slab's `DataView`
  (`adapters/types/js/src/eve/types_js.cljc:264-277,291-294,425-439`): they cannot read an image as they stand. A
  generated sister over `(view, base)`, or carrying a view on the instance, closes it.
- A typed-array view needs its byte offset to be a multiple of the element size; eve arrays in the overflow class
  are 8-aligned for that reason (`test/eve/coalesc_align_test.cljc:16-25`). The packed prototype aligns 4- and
  8-byte columns from the chunk's start, so a chunk must itself start 8-aligned in its buffer.
- 64-bit integers are exact only to 2^53 as JS numbers (`doc/unification/HOST-CONTRACT.md:97-112`); a typed i64
  column is exact through `BigInt64Array`.
- The JS number model already makes a generic double's bytes host-dependent (`-0.0` and integral doubles go out as
  int32, `doc/unification/FORMAT-DECISIONS.md:127-131`). Typed columns do not inherit that; generic scalars do.

*Prov: eve `4a92129`; read only. The plan names the probe that settles it.*

### A15 — ingest respects the construction law and the journal · **VALIDATED by reading and on a heap domain; the journal case by reading only**

A placed image stored from inside `swap!` reads back equal and takes a further write (ran, P6). What an ingest by
run claim must do, from the tally and the swap as they are (`census/TYPES-AND-DEREF-2.md` §4, §6b):

1. every block individually allocated before the hold — its bitmap bit set, or an allocated class-6 descriptor at
   its offset — else the hold stops there and the CAS publishes blocks the allocator may hand out again;
2. every block's descriptor row at zero, inside the class's `total-blocks`, free count and cursor kept;
3. a layout the tally's `refs` reads, with final slab offsets as its pointers;
4. validation **before** the claim: a throw inside the swap frees nothing (`atom.cljc:982,1085`);
5. nothing more for a lost CAS (blocks reachable from the attempt's root are freed by the count) or for a journal
   domain (the force is by class, and the hold's touches name every class the run landed in). The journal records
   root tables only — "COMMIT METADATA ONLY — never values" (`atom.cljc:452-475`) — so it does not see how blocks
   were produced.

*Prov: eve `4a92129`; read (`src/eve/tally.cljc` whole, `atom.cljc:964-1085`); ran on JVM heap domains (P6). No disk
domain was exercised.*

### A16 — STM behaves · **VALIDATED for release; three findings about `eve.stm` itself**

- Release is by a count on each block's own descriptor row, never by the host's GC: a won CAS drops the old root, a
  lost one drops the new root and frees the unheld rest. It does not depend on how the new blocks were produced.
- A ref's value is nested under `:value` in a cell map stored in **its own heap domain**; a deref materializes the
  whole cell, history included; every commit re-encodes the new value and up to ten old ones
  (`src/eve/stm.cljc:300-330,764-778`).
- What is GC-dependent: the ref's domain (never closed), attempt records, materialized copies.
- Ran: a two-ref transaction whose second value is a slab value of another domain throws at store time **and leaves
  the first ref installed** (`census/TYPES-AND-DEREF-2.md` §1, R5). Provenance: JVM, `4a92129`, fresh heap domains.

*Prov: eve `4a92129`; read + ran.*

### A17 — one byte format, not two · **VALIDATED for the design; its premise corrected (C7)**

The prototype takes each scalar's bytes from `eve.codec/value->eve-bytes` and drops the two magic bytes, so a
scalar's static bytes, key bytes and digest bytes are one string by construction. New container tags come from
`0x20–0x3F`, unassigned and reserved for Eve (`census/BYTE-PATHS.md` §1.4). Two scalar tags are added (bytes, nil).
The kernel's universe has no uuid, instant, big number or bytes at all; the mapping to it is defined per kind in
`FORMAT-V1.md`.

Kill check — "a design requirement that forces re-tagging scalars": none.
Standing fact to carry: FORMAT-DECISIONS §8 says of the live wire "neither is this format" — a static wire is a new
generation that ValueScript's own client cannot read until the kernel reads chunks.
*Prov: eve `4a92129`; read; measured (P2 round-trips every corpus through the verbatim scalar bytes).*

### A18 — encodings keep random access · **VALIDATED for dictionary codes and narrowing; run-end admitted with its cost stated**

Measured (M4, M7): a dictionary-coded cell 49–152 ns against the host's 59; a narrowed (frame-of-reference) column
1.4–3.1 ns a read at half or a quarter of the bytes; a run-end column 42–102 ns a read (a binary search over the
runs) at 3–300× fewer bytes. Nothing opaque is admitted inside a chunk; delta coding is not admitted (a read would
need a prefix sum).
*Prov: eve `4a92129`; measured (P4, P6b).*

## The three load-bearing rows

The research plan names A4, A8 and A9 as stop conditions. None died: A4 holds with one type's read path to fix; A8
holds with "15, not 16" and the page tier where it actually lives; A9's capability does not exist and its mechanism
measures well. The lane ran to D5.
