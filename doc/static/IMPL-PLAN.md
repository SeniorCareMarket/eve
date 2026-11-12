# D5 — static Eve, the implementation plan

What to build, in what order, and the probe that says each phase is done — written before the phase, failing
before it, passing after. The evidence is `ASSUMPTIONS.md` (A*n*, C*n*), `MEASUREMENTS.md` (M*n*),
`BLIND-SPOTS.md` (Q*n*) and `FORMAT-V1.md` (§). Sizes of work are not estimated here: no phase has been built, so
there is no measurement to cite for one.

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
3. **The generated sister is the columnar phase.** On the ISlabIO hosts a sister is needed only for the packed,
   columnar form (A2); generating it earlier would generate something nothing reads.

## 3. Phases

Each phase lists what is built, where, and its acceptance probes. A probe is a test in the repository's own
suites, run on the host named; the research probes in `doc/static/probes/` are their seeds and stay research code.

### Phase 0 — the spike: a value leaves a domain and comes back (JVM)

Built, in a new namespace `eve.static` (name: fork F1):

- **A static slab I/O** over one byte array: reads only; `alloc!`, `free!`, `write-*`, `cas!` throw. A builder
  variant bumps a tail and ignores frees. Recognised by the hot read path (`alloc/node-heap-array`,
  `src/eve/deftype_proto/alloc.cljc:1127-1168`) so a static reads at slab speed rather than through protocol
  dispatch (M5: 16–60 ms against 18–54 ms per 100,000 reads, both 7–27× a host collection).
- **`detach`** — the relocating copy, generic over the block kinds `eve.retire` reads
  (`src/eve/retire.cljc:145-179`), with each block's size taken from the block. It needs one addition to what a
  type registers: an eve-deftype's total size beside its pointer offsets
  (`register-deftype-layout!`, `src/eve/deftype/macros.cljc:140-146`).
- **`place`** — an image into the running swap's domain. In this phase by per-block `alloc!` (correct on every
  host as it stands).
- **Asked-for exits**: a deref that answers a static (`(static/deref a)` or an option on the read region), beside
  `@a`, which is unchanged.
- **Bytes out and in**: `bytes`, `open`, to a file and back.
- **The list's seq** made a host cursor, so reading a list allocates nothing
  (`src/eve/list.cljc:157-161,195-203`) — the one read path A4 found that needs an allocator.

Acceptance (JVM; each fails before, passes after):

| # | Probe | From |
|---|---|---|
| 0.1 | Property test: generated values × generated updates; every earlier `(length, root)` view equal, readable from a buffer truncated at its length, hash-stable — including lists | `probes/p1_bump_sio.clj` |
| 0.2 | Every type of `probes/p5_types.clj` — sorted, rb-tree, int-map, array, Dataset, a user type with all four field classes, the DataScript DB — through detach → read in place → place → read; the list included (it errors today) | P5 |
| 0.3 | A static view refuses every write (the prototype accepts one) | P5.2 |
| 0.4 | File out, new process, file in, read in place, equal | P1.3 |
| 0.5 | In one session, the four corpora of `probes/p6_end_to_end.clj`: `detach` under the pin against `@a`; a placed `swap!` against a plain one. Recorded, not thresholded — the research figures (2–8× and 3–45×) are what to compare against | P6 |
| 0.6 | `eve.reclaim-test`-style exactness: after a placed swap and after a lost one, the domain's allocated set is exactly the value's blocks | new, from `test/eve/reclaim_test.cljc` |

### Phase 1 — placement: the allocator and the count

Built:

- **A run claim** as a slab-I/O operation (`alloc-run! sio class n` → first block), default implementation a loop
  of `alloc!` so every host is correct at once; the heap and mmap slabs claim whole bitmap words by CAS (measured:
  5–12× per-block, M5). It must hold under another process allocating in the same class (word CAS, no lock) and
  under growth.
- **`place` by runs**, with the conditions `ASSUMPTIONS.md` A15 lists: bits set before the hold, clean rows, final
  offsets, **validation before the claim** (a throw inside a swap frees nothing today), touches for the force.
- **The count written, not walked.** The image's relocation table is every block's holders; placement writes each
  new block's count and flags as it writes the block, and the commit's hold of the root does not descend into
  blocks it is told are freshly and fully counted. This is the measured bottleneck (M6: ≈ 97 % of a placed swap)
  and the one change here that touches `eve.tally`; it is specified against the tally's own words for "counted"
  (`src/eve/tally.cljc:10-17,314-358`) and proven by the exactness probe, not argued.
- **Building off-allocator.** `swap!`'s store of a plain value builds in a thread-private buffer, compacts, and
  places (M6: 0.37–0.56× today's time with the writers untouched). The buffer is scratch: never published, thrown
  away.
- **A crossing copies.** A slab value of another domain stored into an atom is detached and placed, root or
  nested — the model's rule, which Eve refuses today and `:foreign-policy :copy` approximates by materializing
  (`doc/memory-model.md` D5; `src/eve/atom.cljc:852-876`).

Acceptance:

| # | Probe | Host |
|---|---|---|
| 1.1 | Run claim under contention: N threads and two processes allocating runs and single blocks in one disk domain; no block handed out twice; free counts exact | JVM, then bb |
| 1.2 | Exactness (0.6) with run claims and written counts, on heap and disk domains, including a lost CAS and a throw in the swap's function after placement | JVM |
| 1.3 | A journal domain: place, kill the process before the next commit, reopen, the value reads and the allocated set is exact | JVM (`test/eve/journal_recovery_test.clj`'s shape) |
| 1.4 | A placed `swap!` within 2× of placement alone on the four corpora (today 4–30×) | JVM |
| 1.5 | The plain `swap!` on the four corpora no slower than today on any, and the off-allocator arm at or under 0.6× | JVM |
| 1.6 | `(swap! a assoc :k v)` with `v` from another domain stores and reads back, root and nested; two-ref STM transaction storing a foreign value commits both or neither (it commits one today: A16) | JVM |

### Phase 2 — the packed form: chunks, the wire role

Built:

- **The chunk writer and reader** of `FORMAT-V1.md` §2–4, 8, 10–11 — scalars, symbol table, shapes, MAP / VEC /
  SET / LIST / KMAP / META / TAGGED, standalone columns — on the byte-array hosts and the ClojureScript family from
  the first commit, because the byte contract is only real when two codecs agree.
- **Static collection types** that satisfy the protocols over a chunk: lookup, nth, count, seq, reduce, equiv,
  hash, print; `assoc` / `conj` answering a new static.
- **`pack`** from plain data and from a slab value (keeping one node per shared block, Q2); **`open-checked`**.
- **A writer / reader pair** with handlers keyed by type key and the generic record as the default (Q7).
- **The two scalars Eve lacks** (bytes, an addressable nil) in the static form; whether bytes also becomes a slab
  scalar is a separate change and not required here.
- **Fixtures**: `test/eve/fixtures/static-bytes.edn` and a malformed-chunk file, in every host's runner list.

Acceptance:

| # | Probe | Host |
|---|---|---|
| 2.1 | Golden bytes: every fixture row encodes to its bytes and decodes to its value | every host with a runner |
| 2.2 | The same value packed on the JVM and on Node is the same bytes (settles A14 for the format) | JVM + Node |
| 2.3 | Malformed chunks: each refused with its named check; no `Error`; nesting 10,000 deep refused | JVM, Node |
| 2.4 | Bytes: at or under 0.9× Transit MessagePack on the message and record corpora without batches; hit rate and table share reported per chunk size | JVM (`probes/p2_sizes.clj`) |
| 2.5 | `(= packed plain)`, `(= packed slab)`, `(= packed image)` both ways; hashes equal; the float edges behave as the digest already does (A10) | JVM |
| 2.6 | A type the reader does not have round-trips as the generic record, bytes unchanged | JVM ↔ Node |

### Phase 3 — columns and the generated sister

Built:

- **BATCH and the column kinds** (§5), with the narrowed and run-end encodings behind a writer choice.
- **`eve-deftype` emits the sister** at `src/eve/deftype/macros.cljc:130-153`: a typed shape (key, fingerprint,
  field classes), a row type whose field reads go to `column[row]`, the user's protocol bodies expanded against it.
  On the ClojureScript family the same hook emits the image's reader too.
- **Dataset ↔ batch**: a Dataset's columns are already dense blocks; packing one is a copy per column. Row views
  on a batch; the column functions of `eve.alpha.col` over a batch in place (they copy a column out and back
  today, `census/DEFTYPE-AND-COLUMNAR.md` §2.5).
- **Column types Eve's Dataset lacks**: strings, keywords (dictionary), booleans, nulls, int64, instants.
- **Hand-offs without a copy**: a dense column as the host's typed array / buffer; an Arrow C-data or DLPack export
  where the host has one — a consumer, not a dependency.

Acceptance:

| # | Probe | Host |
|---|---|---|
| 3.1 | A vector of instances of a user type → batch → instances: equal by fields; the sister satisfies the type's own protocols | JVM, Node |
| 3.2 | Column scan and filter over a 64 KiB-chunked batch at or under 2 ns and 4 ns a row; one cell at or under 2× the host map's; a row view built in under 1 µs (research: 1.4, 2.7, 88 ns against 59, 0.68 µs — M4) | JVM |
| 3.3 | `test/eve/columnar_bench.cljc`'s five pipelines over a static batch against today's Dataset, same session | JVM, Node |
| 3.4 | A map of vectors and a vector of maps of the same data pack to the same bytes | JVM |
| 3.5 | A typed column handed to a `double[]` / `Float64Array` shares memory (a write through the host array is forbidden; identity of the backing store is the check) | JVM, Node |

### Phase 4 — chunks in atoms; segments

Gated on fork F3. Built: the chunk block kind (a leaf to the tally and to `eve.retire`, a pointer tag), store and
read-back by `memcpy`; a persistent spine over chunk leaves (a directory by index or by key range, itself an Eve
collection); for the DataScript port, a sorted datom batch with a first / last key per chunk as an index leaf.

Acceptance: `(swap! a assoc :t batch)` costs a copy of the batch's bytes and one block; exactness (0.6) holds;
datoms at or under 25 B each in segments against 296 B today (M2, M1); a range scan over segments in place.

### Phase 5 — the network and the kernel

Not this lane's to build; stated so the format does not close doors. A chunk with a digest is one blob on the live
blob wire, put and fetched by presigned URL (`doc/network-atoms/WIRE.md` §7); the gateway moves slab ↔ wire without
materializing (it materializes both ways today, `src/eve/net/gateway.cljc:164,203`); the kernel gets a type CID for
a chunk, a reader in `.vs`, an 8-aligned large-block allocation, and — if F4 is ruled in — a build region and the
block copier it lacks (`BLIND-SPOTS.md`, the aside).

## 4. Host order

JVM first for every phase (it is where everything here was measured). Phase 2's bytes land on Node in the same
change (2.2). The other ISlabIO hosts follow the JVM with their existing adapters: the image and placement are
shared `.cljc` over `eve.slab-io`. ClojureScript's image reader comes with Phase 3's emission hook. ValueScript does
not expand `eve-deftype` at this pin and is Phase 5.

## 5. Risks that remain

| # | Risk | What bounds it |
|---|---|---|
| R1 | Writing counts at placement is a change inside the tally, where the standing rule is "the walk, exactly" | It adds no mechanism beside the count — it writes the same words the walk would. Probe 1.2 is exactness against the allocation log, on won, lost and thrown swaps. If it cannot be made exact it is dropped and placement stays at M6's 3–45× |
| R2 | Two forms are two reader surfaces | The image's reader is Eve's existing types; only the packed form is new code. If Phase 2's readers reach slab read speed, the image stays an internal transport and is not a public form |
| R3 | ClojureScript cannot read an image as its types stand | Phase 3's hook; until then ClojureScript has `pack` and the packed readers only. A14 is open until probe 2.2 |
| R4 | Reading Eve's block layouts in place is 7–27× a host collection (M5) | Not made worse by this plan; the packed readers are the fast path (one cell 49–152 ns against 59) |
| R5 | A static wire is a generation ValueScript's client cannot read (A17) | Phase 5; until then statics travel between Eve hosts and to stores, and the live network atom is untouched |
| R6 | Equality at the float edges differs from Clojure's (A10) | Inherited from the digest, pinned by a fixture row rather than left implicit |
| R7 | Everything was measured on one JVM | Each phase's probes run on a second host before it is called done |
| R8 | Findings outside this lane that it will trip over: a root scalar past 1,016 bytes reads `nil`; a two-ref STM transaction can half-commit; `eve.sorted`'s `-plain` and the list's seq allocate on read; an adopted kernel page can be evicted under an atom | Recorded with provenance in `CENSUS.md` §"Defects found on the way"; each is re-derived at its own pin before anyone fixes it |

## 6. Decided here — not forks

| Question | Decision | Why it is not the operator's to re-decide |
|---|---|---|
| Vector first or columnar first? | Neither: the image first, the allocator second, chunks third, columns fourth | M1, M5, M6 (§2 above) |
| Is 64 KiB right? | Yes, as a target | A7 |
| Is the page 1 MiB with 16 chunks? | The container is "a run of chunks"; in a kernel region 15 fit; nothing in the format depends on 16 | A8, C2 |
| Does `@a` change? | No. Static is asked for | A13 — a silent change would break programs that mutate through a deref |
| One seam at `IPlain`? | No — the exits of a domain, and the block walk | A3 |
| Compression in v1? | None; later, per run of chunks | M3 |
| Reuse the scalar tags? | Verbatim, plus bytes and nil | A1, A17 |
| Does the wire header carry the registry id? | The type key string and a layout fingerprint | A11, C3 |
| Which SOTA design to follow? | Three, each for one mechanism: an in-chunk key table with binary-searchable objects (Parquet's VARIANT; FlexBuffers), column batches with dictionaries (Arrow IPC), a per-segment spec the consumer places by (Vortex's footer). Not Transit's cache, which cannot be entered mid-stream | C10; `research/FORMATS.md` |

## 7. Forks for the operator

Genuine preferences; each with a recommendation.

| # | Fork | Options | Recommendation |
|---|---|---|---|
| F1 | **The name** | `eve.static` with `detach` / `pack` / `place` / `open`; or `eve->static` as one verb with a `:form` option; or other words for the two forms | The namespace and four verbs: the two forms do different jobs and one verb would hide which one a caller is paying for |
| F2 | **Equality of a user type that defines none** | (a) in a static, by type key and fields — so a static copy is `=` its twin, and a slab instance stays identity-compared as today; (b) change the slab default to by-fields as well, a behaviour change for existing types; (c) keep identity everywhere, so no static of such a type is ever `=` anything but itself | (a) now; (b) is the consistent end state and is its own decision |
| F3 | **A chunk as a leaf block inside an atom** (Phase 4) | (a) yes — a typed, counted, digested leaf Eve reads in place, like an eve array block; (b) no — a packed value stored into an atom is always exploded into ordinary collection blocks, and chunks live only outside domains | (a). It is the "static types living in pages with persistent types" of the original ask, and it is what makes storing bulk data a copy instead of a build. It is flagged because rule 26 ("no out-of-band stand-ins") is the operator's to read |
| F4 | **The ValueScript aside** | (a) amend the per-thread scratch ruling's condition (b) so a thread's build region may hold values under construction, never published, copied out by placement; (b) leave it, and keep VS temporaries as allocator blocks | (a), scoped to exactly that. The kernel's binding stack and boxing memos already hold values under that ruling (`census/VS-KERNEL-2.md` §3.6); the copy-out is the copier "a crossing copies" has always needed |
| F5 | **How far Phase 1 may go inside the tally** | (a) counts written at placement (R1); (b) placement only, the hold left as it is | (a), behind probe 1.2 |
