# D5 — static Eve, the implementation plan

What to build, as waves of lanes that run in parallel; every lane's requirements, each with the test that fails
before it and passes after; and the scoreboard the spike is driven by. Rewritten 2026-11-12 12:45 AM ET at the operator's
instruction: "spike out all these ideas. 64K chunks. High speed zipping. High speed atom to atom transfer.
Iterative, perf-driven spike. And update the phases for high lane fan out. Ensure tests are made for all
requirements in each phase lane." The evidence is `ASSUMPTIONS.md` (A*n*, C*n*), `MEASUREMENTS.md` (M*n*),
`BLIND-SPOTS.md` (Q*n*) and `FORMAT-V1.md` (§). Sizes of work are not estimated: nothing here has been built, so
there is no measurement to cite for one.

## 0. Read first — the stop conditions

The research plan's stop conditions were A4, A8 and A9 (`ASSUMPTIONS.md`, last section). A4 holds. A9 is absent as
a present capability: neither allocator takes placement. A8 this lane first read as "a chunk's bytes sit in a page
beside persistent blocks, uncopied" — false on both trees — and reported dead. The operator has since said what he
means by it (2026-11-12 12:23 AM ET): a chunk that carries the map by which the allocator zips its objects into free blocks.
That is A9's mechanism, and a probe measured it. So one thing is missing, and it is an addition to accept or
refuse, not a capability that exists:

| What the plan stands on | Status | Lanes that need it | Lanes that do not |
|---|---|---|---|
| A **run entry** in the allocator — n blocks of one class claimed in one call where the room is known to be free — driven by the image's block and relocation tables | measured in a probe on the JVM heap slab (M5, M6); in neither product allocator | Z2, Z3, Z4, T1, H1, H2 | Z1, B1, C1–C4, L1, X1 |
| A chunk as **one opaque leaf block** in an atom, entered by one `memcpy` — for packed, columnar data that should stay packed | fork F3 | H7 | all of Waves 0 and 1 |

## 1. The shape, in one paragraph

A static value is immutable Eve bytes outside every domain, in one of two forms (`FORMAT-V1.md` §1). The **image**
is how a value leaves a domain and enters one: its blocks as the slab holds them, copied by a walk Eve already
owns, placed by a run claim the allocator does not have yet. The **packed** form is the wire and storage form:
64 KiB chunks with a symbol table, shapes and typed columns, read in place by generated types. `eve-deftype`
generates the packed sister from the one function every host's expansion passes; the image needs no sister where an
instance already carries its slab I/O. Nothing here changes what `@a` returns: static is asked for.

## 2. What the research reordered, and why

SCOPE's spike was "JVM only — StaticVector + one generated deftype sister, `eve->static`, append-conj, round-trip
back into an atom, buffer to file and back, measured against `eve->clj`". The evidence moves three things:

1. **The spike is the image, not a StaticVector.** Every acceptance item of that spike already passes in a probe
   with no new type code, for every collection but the list and for user types and a DataScript DB (M1). Building a
   hand-written StaticVector first would prove less and delay the measured win.
2. **The allocator comes second, not last.** The transformation the operator asked about is bounded by allocation
   and then by the commit's holder count, not by encoding (M5, M6). A run claim and a count written from the
   image's tables are the two changes that make "placement, not planning" true.
3. **The generated sister belongs with the columns (lane H5).** On the ISlabIO hosts a sister is needed only for the packed,
   columnar form (A2); generating it earlier would generate something nothing reads.

## 3. The work, in waves and lanes

A **wave** is a set of lanes that start together and run in parallel sessions. A **lane** owns its files, has
numbered requirements, and has one named test per requirement. Rules every lane follows:

1. **Tests first.** A lane's first commit is its tests, red, one per requirement, each registered in
   `test/eve/static/requirements.edn` (requirement id → test var). `requirements-covered` (W0.2) fails when an id in
   this plan has no test var or names a var that does not exist: a requirement without a test is a red build.
2. **Step 1 is the measurement.** A perf lane runs the scoreboard at its starting commit and logs the row before it
   changes anything; it is judged against that row, taken in its own session.
3. **Done** = every requirement test green on the JVM runner, and the lane's board row logged with its commit. A
   lane that cannot reach its gate reports the gap and the reason. It does not relabel the gate.
4. **Files are owned.** A lane edits only the files listed for it. What lanes share is frozen in Wave 0; a change
   to a contract is its own commit, read by every lane that uses it.
5. Where a lane needs another's code it builds against the Wave 0 contract and the default that ships with it; the
   lane table says where the two meet. The probes in `doc/static/probes/` are seeds and stay research code.

### 3.1 The scoreboard — what "perf-driven" means here

One command, built in Wave 0 (`test/eve/static/scoreboard.clj`), runs every row on the four corpora of M6 — map
10k int → int · vector 100k ints · vector 10k records · 5k chat messages — both arms in one session, the minimum of
N runs, and appends the result to `doc/static/SPIKE-LOG.md`.

| Row | What is timed or sized | Today | Research probe | Spike gate | From |
|---|---|---|---|---|---|
| B1 zip, alone | an image laid into a domain's blocks, net of creating the domain | — | 2.2 / 3.4 / 1.3 / 2.5 ms (33–42 ns a block on the larger three) | ≤ 50 ns a block | M5 |
| B2 zip, committed | a `swap!` that places an image, net of the domain | 202 / 325 / 475 / 1,190 ms for the plain store | 4.4 / 105 / 34 / 77 ms (≈ 1 µs a block) | ≤ 2× row B1 | M6 — the gap is the count |
| B3 detach | an image copied under the pin, against `@a` | 3.3 / 19.1 / 20.7 / 43.9 ms | 0.40 / 9.63 / 3.27 / 10.54 ms | at least 2× faster than `@a` on every corpus | M6 |
| B4 atom → atom | a value of atom A stored into atom B, one process | never measured as one run; its parts (`eve->clj` + plain store) sum to 206 / 344 / 496 / 1,234 ms. W0.6 measures it | parts (detach + placed `swap!`): 10.9 / 118 / 39.9 / 90.9 ms | ≤ 2× (row B3 + row B1) | M5, M6 |
| B5 plain → atom | a `swap!` of plain data | 202 / 325 / 475 / 1,190 ms | 76 / 154 / 242 / 666 ms with today's writers into a buffer | under the probe's figure on every corpus; a direct builder has no measurement yet | M6 |
| B6 chunk bytes | packed in 64 KiB chunks, against Transit MessagePack | — | 0.86× messages, 0.55× records, 0.56× datoms | ≤ 0.9× on messages and records | M2, M3 |
| B7 chunk reads | decode · column scan · filter · one cell · row view, at 64 KiB | Transit decode 4.18 µs a row; host scan 23.3 ns a row | 0.77 µs · 1.44 ns · 2.96 ns · 106 ns · 0.64 µs | decode ≥ 2.5× Transit · ≤ 2 ns · ≤ 4 ns · ≤ 2× the host map's cell · ≤ 1 µs | M4 |
| B8 container | sixteen chunks compressed as one, against the whole value compressed as one chunk | — | a proxy only: 1 MiB chunks each compressed alone are +0–4 %; 64 KiB chunks each compressed alone +23–35 %. Sixteen chunks under one compression was not run | ≤ 5 % | M3, M8 |

**The loop**, for every perf lane: run the board → change one thing → run the board → keep the change if its row
moved and every requirement test is green, else revert → log the row either way. Two iterations in a row that move
nothing end that line of attack: log the number and the reason, take the next idea. A gate is a threshold on the
board. It is not an estimate of effort.

### 3.2 Wave 0 — contracts and the board (one lane, serial; every other lane waits on it)

Files: `src/eve/static.cljc`, `src/eve/static/contract.cljc`, `test/eve/static/requirements.edn`,
`requirements_test.cljc`, `contract_test.cljc`, `corpora.cljc`, `scoreboard.clj`.

| Req | Requirement | Test |
|---|---|---|
| W0.1 | `eve.static` exposes `detach`, `place`, `transfer!`, `pack`, `open`, `open-checked`, `bytes`; each delegates to its lane's namespace and answers "not built" until that lane lands | `api-surface` |
| W0.2 | Every requirement id in this plan maps to an existing test var | `requirements-covered` |
| W0.3 | The image's block table and relocation table, and the chunk header, read and write exactly as `FORMAT-V1.md` §2 and §9 state | `contract-roundtrip` |
| W0.4 | `alloc-run!` exists on the slab I/O with a default that loops `alloc!`, so every host is correct before any is fast | `alloc-run-default` |
| W0.5 | The scoreboard runs every row, both arms in one session, and appends to the log | `scoreboard-smoke` (a tiny corpus) |
| W0.6 | Baselines logged — including atom → atom through today's `:foreign-policy :copy` as one run | first row of `SPIKE-LOG.md` |

### 3.3 Wave 1 — the spike (JVM; twelve lanes)

Meeting points: Z1 + Z2 → Z3 → Z4 and T1. C1 → C2, C3, C4, X1. L1 and B1 meet nothing. Until a meeting point lands,
the downstream lane runs on the Wave 0 default (per-block `alloc-run!`, a hand-built image, the contract's header).

**Z1 — the image: a static slab I/O and `detach`.** Files: `src/eve/static/sio.cljc`, `src/eve/static/image.cljc`,
`register-deftype-layout!` in `src/eve/deftype/macros.cljc` (adds a type's total size). Board: B3.

| Req | Requirement | Test |
|---|---|---|
| Z1.1 | A read-only slab I/O over one byte array: every `alloc!`, `free!`, `write-*`, `cas!` throws (the prototype accepted writes) | `view-refuses-every-mutator` |
| Z1.2 | Every type of `probes/p5_types.clj` reads in place from an image — sorted, rb-tree, int-map, arrays, Dataset, a user type with all four field classes, a DataScript DB, the list (with L1) | `image-reads-every-type` (table-driven) |
| Z1.3 | `detach` copies exactly the reachable blocks, root first, generic over the kinds `eve.retire` reads, and emits both tables: the block table names each block once, the relocation table names every pointer `eve.retire/children` finds | `detach-tables-complete` (generated values) |
| Z1.4 | A detach under the pin is one committed value while another thread swaps | `detach-under-concurrent-writes` |
| Z1.5 | An earlier `(length, root)` view stays equal, hash-stable and readable from a buffer cut at its length while the builder appends | `views-survive-appends` (property, from P1) |
| Z1.6 | Detaching a build that left garbage yields no dead byte | `detach-compacts` (live ratio 1.0) |
| Z1.7 | Hot path: a static reads at slab speed, not through protocol dispatch | `image-read-speed` (within 1.2× of a slab read; through protocol dispatch the probe was 0.9–1.65×, M5) |

**Z2 — the run claim.** Files: `src/eve/deftype_proto/alloc.cljc` (heap and mmap slabs). Board: B1.

| Req | Requirement | Test |
|---|---|---|
| Z2.1 | `alloc-run! sio class n` answers the first of n consecutive blocks, all marked allocated | `run-is-consecutive-and-allocated` |
| Z2.2 | Whole bitmap words by one CAS, partial words masked at both ends: a run of any n at any cursor wastes no block (the probe wasted up to 31 a claim) | `run-wastes-nothing` (free count exact; no bit set outside the run) |
| Z2.3 | No free run → answers none and claims nothing | `no-run-claims-nothing` (a fragmented class) |
| Z2.4 | N threads claiming runs and single blocks in one domain: no block handed out twice, free counts exact | `run-claim-contention` |
| Z2.5 | Correct across growth of a heap class | `run-claim-across-growth` |
| Z2.6 | Works in an encrypted domain | `run-claim-encrypted` |
| Gate | claim and copy at least 3× per-block allocation, net of the domain (probe: 3–9×) | board row B1 |

**Z3 — the zip (`place`).** Files: `src/eve/static/place.cljc`. Board: B1.

| Req | Requirement | Test |
|---|---|---|
| Z3.1 | An image placed inside a `swap!` reads back equal, and a further write on top of it is sound | `place-reads-back-and-updates` |
| Z3.2 | Tables are checked before anything is claimed — an offset out of range, overlapping blocks, a pointer to a non-block, a size the class cannot hold, an unknown kind — each a typed refusal that leaves the domain's allocated set unchanged | `place-refuses-bad-tables` (one case per check) |
| Z3.3 | When a class has no free run the zip falls back to shorter runs, then single blocks, with the same result | `place-fallback-equal` |
| Z3.4 | After a lost CAS, and after a throw in the swap's function once placement is done, the allocated set is what it was | `place-exact-on-loss-and-throw` (the shape of `test/eve/reclaim_test.cljc`) |
| Z3.5 | Blocks of the large class are placed 8-aligned | `place-large-blocks` |
| Z3.6 | In an encrypted domain placed bytes pass through the domain's keystream | `place-encrypted` |
| Z3.7 | An image in 1 MiB units places unit by unit, pointers that leave a unit resolved from the plan in the first unit | `place-by-units` (M8) |
| Gate | ≤ 50 ns a block | board row B1 |

**Z4 — the count taken at the zip (this is fork F5, spiked behind a flag).** Files: `src/eve/tally.cljc`. Board: B2.

| Req | Requirement | Test |
|---|---|---|
| Z4.1 | Counts and flags written from the relocation table equal, row for row, what `hold-root!` writes for the same value | `written-counts-equal-walked` (generated values, two domains) |
| Z4.2 | Exactness on a won swap, a lost CAS and a throw | `written-counts-exact` |
| Z4.3 | Dropping the root afterwards frees the domain to empty | `written-counts-release-clean` |
| Z4.4 | A block with several holders is counted once per holder | `written-counts-shared` (the k = 100 corpus of M7) |
| Z4.5 | Flag off: behaviour identical to Z3 with the walk | `flag-off-is-the-walk` |
| Gate | a placed `swap!` ≤ 2× placement alone (today 2–31×) | board row B2 |

If Z4.1 or Z4.2 cannot be made exact the lane ends there and reports; placement stays at row B2's probe figure.

**T1 — atom to atom.** Files: `src/eve/static/transfer.cljc`, the foreign-value branch of `resolve-new-ptr` in
`src/eve/atom.cljc`. Board: B4.

| Req | Requirement | Test |
|---|---|---|
| T1.1 | A slab value of atom A stored into atom B, at the root or nested, is detached and zipped — no host collection is built — and reads back equal; A is unchanged; B's allocated set is exactly the value's blocks | `transfer-in-process` |
| T1.2 | The source is pinned for the copy: concurrent writes to A never tear the value B receives | `transfer-under-writes` |
| T1.3 | A two-ref STM transaction that stores a foreign value commits both refs or neither (it commits one today, A16) | `stm-foreign-value-atomic` |
| T1.4 | Process to process: A's value → bytes → a file → B in a second JVM, equal | `transfer-cross-process` |
| T1.5 | A value holding a process-local reference is refused by name | `transfer-refuses-local-reference` |
| T1.6 | If a direct domain-to-domain copy (no buffer between) is tried, it gives the same blocks | `transfer-direct-equal` |
| Gate | ≤ 2× (detach + zip alone): ≤ 5 / 26 / 9 / 26 ms on the four corpora | board row B4 |

**B1 — a builder with no garbage.** Files: `src/eve/static/build.cljc`. Board: B5.

| Req | Requirement | Test |
|---|---|---|
| B1.1 | Plain data → blocks in final layout in one pass; the value is `=` today's writer's, has the same root digest, and takes a further `assoc` | `direct-build-equal-and-updatable` |
| B1.2 | Every byte written is reachable — no compaction pass | `direct-build-no-garbage` |
| B1.3 | Its output is an image with both tables, so Z3 places it | `direct-build-is-placeable` |
| B1.4 | A type it does not cover falls back to today's writer and says so | `direct-build-fallback` |
| Gate | plain → atom under the probe's 0.37–0.56× on every corpus | board row B5 |

**C1 — the 64 KiB chunk.** Files: `src/eve/static/chunk.cljc`, `test/eve/fixtures/static-bytes.edn`. Board: B6, B7.

| Req | Requirement | Test |
|---|---|---|
| C1.1 | Header, scalars, symbol table, shapes and nodes as `FORMAT-V1.md` §2–4; every fixture row encodes to its bytes and decodes to its value | `golden-bytes` |
| C1.2 | Round trip: every corpus and generated values — nil, bytes, big numbers, uuids, instants, chars, sets, lists, metadata | `chunk-roundtrip` (property) |
| C1.3 | Fill: a chunk seals when the next row would pass 65,535 bytes; an oversize value makes one wide chunk; small values share a chunk | `chunk-fill-policy` |
| C1.4 | Lookup, nth, count, seq, reduce, equiv, hash, print over the bytes, with no whole-chunk decode | `chunk-protocols-in-place` (a decode counter stays at zero) |
| C1.5 | `assoc` / `conj` answer a new static; the sealed bytes of the old one are untouched | `chunk-update-appends` |
| C1.6 | A type the reader lacks round-trips as a generic record, bytes unchanged | `chunk-unknown-type` |
| C1.7 | `=` and `hash` agree across packed, image, slab and plain, both ways; the float edges behave as the digest does (A10) | `chunk-equality-domain` |
| Gate | ≤ 0.9× Transit MessagePack; decode ≥ 2.5× Transit | board rows B6, B7 |

**C2 — columns and batches.** Files: `src/eve/static/column.cljc`. Board: B7.

| Req | Requirement | Test |
|---|---|---|
| C2.1 | BATCH and every column kind of §5 round-trip | `column-roundtrip` |
| C2.2 | Scan, filter, one cell and a row view computed in place equal the host's answers | `column-ops-equal-host` |
| C2.3 | Narrowed and run-end columns read correctly at random indexes | `column-encodings-point-read` |
| C2.4 | A vector of maps and a map of vectors of the same data pack to the same bytes (the prototype: 0.55× against 1.10×) | `rows-and-columns-same-bytes` |
| C2.5 | A dense column is handed to the host as its typed array without a copy, read-only | `column-zero-copy-handoff` |
| Gate | ≤ 2 ns and ≤ 4 ns a row; one cell ≤ 2× the host map's; a row view ≤ 1 µs | board row B7 |

**C3 — the container: sixteen chunks, 1 MiB.** Files: `src/eve/static/container.cljc`. Board: B8.

| Req | Requirement | Test |
|---|---|---|
| C3.1 | A value past one chunk is a directory and chunks; a reader finds its chunk by binary search and opens no other | `directory-opens-one-chunk` (an open counter) |
| C3.2 | A container holds up to sixteen chunks under one directory and is the unit of compression | `container-compression` |
| C3.3 | A reader can enter a stream at any chunk | `enter-mid-stream` |
| C3.4 | Appending makes a new chunk and a new root; the old `(chunks, root)` still reads the old value | `append-keeps-old-value` |
| C3.5 | A container is the unit of placement for an image, with its cross-unit plan in the first unit | `container-is-placement-unit` (with Z3.7) |
| Gate | ≤ 5 % over the whole value compressed as one | board row B8 |

**C4 — bytes nobody vouches for.** Files: `src/eve/static/check.cljc`, `test/eve/fixtures/static-malformed.edn`.

| Req | Requirement | Test |
|---|---|---|
| C4.1 | `open-checked` refuses every malformed fixture with its named check and never a host `Error` | `malformed-refused-by-name` |
| C4.2 | Limits: nesting 64; a count times its smallest element never exceeds the bytes left | `limits-enforced` (10,000 levels; a lying count) |
| C4.3 | Random mutations of valid chunks end in a typed refusal or a value — no crash, no hang | `fuzz-never-crashes` |
| C4.4 | `place` and `transfer!` accept only checked bytes | `place-requires-checked` |

**L1 — the list reads without allocating.** Files: `src/eve/list.cljc`.

| Req | Requirement | Test |
|---|---|---|
| L1.1 | Walking a list allocates nothing (a deref of 50 elements takes 50 blocks today) | `list-seq-allocates-nothing` |
| L1.2 | A list reads from a frozen view (P5's one failing row) | `list-reads-from-view` |
| L1.3 | The list's existing tests stay green | the existing list suite |

**X1 — the same bytes on a second host.** Files: the ClojureScript side of `chunk`, a Node runner entry.

| Req | Requirement | Test |
|---|---|---|
| X1.1 | The golden fixtures encode and decode on Node | `golden-bytes` on Node |
| X1.2 | One value packed on the JVM and on Node is the same bytes (settles A14 for the format) | `jvm-node-same-bytes` |
| X1.3 | i64 columns exact through `BigInt64Array`; −0.0 and integral doubles kept in typed columns | `node-numeric-edges` |
| X1.4 | A chunk viewed through typed arrays starts 8-aligned | `node-alignment` |

### 3.4 Wave 2 — hardening and hosts (eight lanes; opens when Wave 1's gates are met or reported)

| Lane | Owns | Requirements, each with its test |
|---|---|---|
| H1 disk domains | mmap slab path of `alloc-run!` | two processes claiming runs and blocks in one disk domain: no block twice, free counts exact (`run-claim-two-process`) · `place` exact on won, lost and thrown swaps in a disk domain (`place-exact-disk`) |
| H2 journal | — (tests and whatever they force) | place, kill before the next commit, reopen: the value reads and the allocated set is exact (`place-survives-crash`, the shape of `test/eve/journal_recovery_test.clj`) |
| H3 other hosts | runner lists, adapters | Wave 1's Z, T and C tests green on babashka, then the remaining slab-I/O hosts (`<host>-static-suite`) |
| H4 ClojureScript image | `adapters/types/js`, the module slab | Eve's types read an image on Node (`image-reads-every-type` on Node) · the module slab's uncalled `batch-alloc` becomes the run claim (`run-wastes-nothing` on Node) |
| H5 the generated sister | `src/eve/deftype/macros.cljc:130-153` | a vector of instances → batch → instances, equal by fields (`sister-roundtrip`) · the sister satisfies the type's own protocols (`sister-protocols`) · a changed layout reads as a generic record (`sister-fingerprint-mismatch`) · no hand-written code per type (`sister-is-generated`) |
| H6 Dataset | `src/eve/dataset*`, `eve.alpha.col` | Dataset ↔ batch is a copy per column (`dataset-batch-roundtrip`) · `test/eve/columnar_bench.cljc`'s five pipelines over a batch against today's Dataset, one session (`columnar-pipelines`) · strings, keywords, booleans, nulls, int64 and instants as columns (`dataset-column-types`) |
| H7 a chunk inside an atom (needs F3) | `eve.tally`, `eve.retire`, the pointer tag | `(swap! a assoc :t batch)` costs one block and a copy of the bytes (`chunk-leaf-store`) · a chunk that names a block of the domain is refused (`chunk-leaf-closed`) · exactness (`chunk-leaf-exact`) · datoms ≤ 25 B each in sorted segments, against 296 B today (`datom-segments-size`) · a range scan over segments in place (`datom-segment-scan`) |
| H8 writer and reader | `src/eve/static/io.cljc` | a stream of values with handlers keyed by type key (`writer-reader-handlers`) · the default handler answers the generic record (`reader-default`) · a file written in one process reads in another (`file-roundtrip`) |

### 3.5 Wave 3 — the network and the kernel

Not this lane's to build; stated so the format does not close doors. A chunk with a digest is one blob on the live
blob wire, put and fetched by presigned URL (`doc/network-atoms/WIRE.md` §7); the gateway moves slab ↔ wire without
materializing (it materializes both ways today, `src/eve/net/gateway.cljc:164,203`); the kernel gets a type CID for
a chunk, a reader in `.vs`, an 8-aligned large-block allocation, a run entry in its allocator — the one fast feed
that touches no standing rule — and the block copier "a crossing copies" lacks (`BLIND-SPOTS.md`, the aside;
`census/VS-KERNEL-2.md` O10 options 2 and 3). Canonical Eve on ValueScript today runs its own slab inside foreign
runs of the kernel's memory, not kernel blocks (`census/VS-KERNEL-2.md` §4), so the two sides meet only as bytes.

## 4. Fan-out, hosts and merge order

- Wave 0 is one session. Wave 1 is up to twelve; Wave 2 up to eight. No lane of a wave waits for another to start.
- Merge order inside Wave 1 follows the meeting points: Z1 and Z2, then Z3, then Z4 and T1; C1, then C2, C3, C4
  and X1; L1 and B1 whenever green. A lane rebases on what merged before it and re-runs its tests and its board row.
- Hosts: Wave 1 is the JVM, where everything in `MEASUREMENTS.md` was taken, plus Node for the chunk's bytes (X1).
  The other slab-I/O hosts, ClojureScript's image and disk domains are Wave 2. ValueScript does not expand
  `eve-deftype` at this pin and is Wave 3.
- What Wave 1 leaves untouched: what `@a` returns, the journal, the network layer, every public API outside
  `eve.static`. Z4 is behind a flag and T1 behind the foreign-value branch that refuses today.

## 5. Risks that remain

| # | Risk | What bounds it |
|---|---|---|
| R1 | Writing counts at placement is a change inside the tally, where the standing rule is "the walk, exactly" | It adds no mechanism beside the count — it writes the same words the walk would. Lane Z4's tests (Z4.1–Z4.3) are equality with the walk and exactness on won, lost and thrown swaps. If it cannot be made exact it is dropped and placement stays at M6's 3–45× |
| R2 | Two forms are two reader surfaces | The image's reader is Eve's existing types; only the packed form is new code. If the chunk readers (lanes C1, C2) reach slab read speed, the image stays an internal transport and is not a public form |
| R3 | ClojureScript cannot read an image as its types stand | Lane H4; until then ClojureScript has `pack` and the packed readers only. A14 is open until X1.2 |
| R4 | Reading Eve's block layouts in place is 7–31× a host collection (M5) | Not made worse by this plan; the packed readers are the fast path (one cell 68–156 ns against 59) |
| R5 | A static wire is a generation ValueScript's client cannot read (A17) | Wave 3; until then statics travel between Eve hosts and to stores, and the live network atom is untouched |
| R6 | Equality at the float edges differs from Clojure's (A10) | Inherited from the digest, pinned by a fixture row rather than left implicit |
| R7 | Everything was measured on one JVM | Wave 2's H3 and H4 run Wave 1's tests on a second host before anything is called done |
| R9 | Shared map shapes have no shipped precedent: FlexBuffers' key-vector sharing is unimplemented in its C++ and Rust writers (`research/FORMATS-2.md` B4) | The evidence is this lane's own measurement (M2: shapes take a further 22–33 %); lane C1's gate (board row B6) re-measures it in product code |
| R10 | Append-only forms accumulate garbage: LMDB's author measured 1,027 pages for 10 live and abandoned the design; CouchDB compacts by copy at about twice the storage (`research/FORMATS-2.md` B6); here a 10,000-entry build is 6.2 % live (M7) | The rule in §3: a build is compacted before it is kept, and nothing long-lived is an append log. Appending is for builders and for short chains of versions |
| R11 | The review of these documents was cut short: of six adversarial reviewers, two ran for about six minutes before the lane was told to stop spending (2026-11-11 11:33 PM ET). They found five errors, all fixed (`review/`). The kernel-and-rulings, format-soundness, completeness and plan lenses did not run | The untested claims are the ones those lenses would have checked: `FORMAT-V1.md` §4 and §10 as an implementer and an attacker would read them; every kernel citation; the probes' thresholds. Treat them as drafted, not reviewed |
| R8 | Findings outside this lane that it will trip over: a root scalar past 1,016 bytes reads `nil`; a two-ref STM transaction can half-commit; `eve.sorted`'s `-plain` and the list's seq allocate on read; an adopted kernel page can be evicted under an atom | Recorded with provenance in `CENSUS.md` §"Defects found on the way"; each is re-derived at its own pin before anyone fixes it |

## 6. Decided here — not forks

| Question | Decision | Why it is not the operator's to re-decide |
|---|---|---|
| Vector first or columnar first? | Neither first: after Wave 0 the image, the run claim, the zip, atom-to-atom, the chunk and its columns are parallel lanes of one spike, each judged on its board row | M1, M5, M6, M8 (§2, §3.1) |
| Is 64 KiB right? | Yes, as a target | A7 |
| Is the page 1 MiB with 16 chunks? | The container is "a run of chunks"; in a kernel region 15 fit; nothing in the format depends on 16 | A8, C2 |
| Does `@a` change? | No. Static is asked for | A13 — a silent change would break programs that mutate through a deref |
| One seam at `IPlain`? | No — the exits of a domain, and the block walk | A3 |
| Compression in v1? | None; later, per run of chunks | M3 |
| Reuse the scalar tags? | Verbatim, plus bytes and nil | A1, A17 |
| Does the wire header carry the registry id? | The type key string and a layout fingerprint | A11, C3 |
| Which SOTA design to follow? | Three, each for one mechanism: an in-chunk key table with binary-searchable objects (Parquet's VARIANT; FlexBuffers), column batches with dictionaries (Arrow IPC), a per-segment spec the consumer places by (Vortex's footer). Not Transit's cache, which cannot be entered mid-stream. The second research pass reaches the same composition on its own: VARIANT-style values with FlexBuffers' root-at-end and shape vectors, in a PAX / Vortex-style chunk, framed by an Arrow / Ion-style explicit table — "no single format is the answer" | C10; `research/FORMATS.md`; `research/FORMATS-2.md` C2 |
| The ValueScript aside: a thread-local region for values built off the allocator? | No. What the kernel needs is a run entry in its allocator and a block copier; a build buffer is a call temporary and nothing more | Two standing rulings. 2026-10-18, on the nursery ("a thread-local pool of short-lived eve values, promoted only when published"): "Let's fix the transients and go with that for local state." (atom.broker `docs/port-gap-closure/lanes/L22-eve-kernel.md:612-631`). 2026-10-29, rule 12's scratch conditions (a)–(d). `census/VS-KERNEL-2.md` O10 prices six options; only the run entry touches no rule. The request that opened this lane asks for "off-allocator, ephemeral constructions of objects that are thread local and safe"; a call temporary plus a run entry is the reading of it that those rulings allow, and this plan assumes no other |

## 7. Forks for the operator

Genuine preferences; each with a recommendation.

| # | Fork | Options | Recommendation |
|---|---|---|---|
| F1 | **The name** | `eve.static` with `detach` / `pack` / `place` / `open`; or `eve->static` as one verb with a `:form` option; or other words for the two forms | The namespace and four verbs: the two forms do different jobs and one verb would hide which one a caller is paying for |
| F2 | **Equality of a user type that defines none** | (a) in a static, by type key and fields — so a static copy is `=` its twin, and a slab instance stays identity-compared as today; (b) change the slab default to by-fields as well, a behaviour change for existing types; (c) keep identity everywhere, so no static of such a type is ever `=` anything but itself | (a) now; (b) is the consistent end state and is its own decision |
| F3 | **A chunk as a leaf block inside an atom** (lane H7) | (a) yes — a typed, counted, digested leaf Eve reads in place, like an eve array block; (b) no — a packed value stored into an atom is always exploded into ordinary collection blocks, and chunks live only outside domains | (a). It is all that survives of "static types living in pages with persistent types" (A8 is dead as worded), and it is what makes storing bulk data a copy instead of a build. It is flagged because rule 26 ("no out-of-band stand-ins") is the operator's to read |
| F4 | *Withdrawn.* It asked whether to amend the scratch ruling for a per-thread build region. Standing rulings already answer it (§6, last row) | — | — |
| F5 | **How far the zip may go inside the tally** | (a) counts written at placement (R1); (b) placement only, the hold left as it is | Lane Z4 spikes (a) behind a flag and reports board row B2 with its exactness tests; decide on those numbers. (a) if Z4.1–Z4.3 are green |
