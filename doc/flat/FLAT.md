# Flat Eve — the flat aspect of Eve's persistent types

Every Eve persistent type has a flat aspect: a second form of the same
type, generated alongside it. Where the persistent form is a tree of
blocks in an atom domain — structurally shared, updated by path-copying
at log₃₂ efficiency — the flat form is the same value laid out in one
linear byte buffer: append-only, immutable, read in place.

`eve/deftype` generates both kinds together. A user type gets its flat
sister with zero additional code, and the sister satisfies the type's
own protocols over the flat bytes. Eve's built-in collections — maps,
vectors, sets, lists, sorted collections, datasets, and the rest —
have flat forms the same way.

Flat types are **not persistent**. There is no structural sharing and
there are no interior edits: a flat value grows by appending, and
changing anything inside it means producing a new flat value.
What flat buys instead is the subject of this document.

## Why flat exists

The persistent form is the right shape inside an atom domain. It is
the wrong shape almost everywhere a value has to _go_:

- **Storage** — a value as one linear buffer, not a tree of
  size-class-padded blocks scattered through slab files.
- **Wire** — one buffer to send, self-describing, identical on every
  host, with per-chunk dictionaries that make repeated shapes and
  symbols nearly free.
- **Atom-to-atom transfer** — moving a value between domains as a
  copy of its blocks laid down in one claim, instead of a
  value-at-a-time rebuild through the writer.
- **Columnar payloads** — row data stored as typed columns, so scans
  and filters run over contiguous arrays the way Arrow-shaped data
  does, for the Python dataframe world and for Eve's own Dataset
  machinery.

The name leans into the zero-copyness of flat-type worlds, with one
precision the operator insists on: for Eve it is not literally zero
copy, it is a **zip copy** — a bunch of little zero copies in a row,
without having to chase pointers between the zipper links. A flat
value's blocks sit in order in one buffer; the relocation table
linearizes what pointers would have scattered. Reads are served in
place from the buffer. Materializing a flat value back into host
collections (`eve->clj`'s job on the persistent side) is the escape
hatch, not the norm — the performance section prices it, and it is
not cheap.

## The two forms

A flat value has two byte forms. They share a container and a header
(`doc/flat/FORMAT-V1.md` is the format of record; container magic
`EVS1`, and the format embeds no name string — type identity travels
as the type's key, below).

**The image** is the persistent value's own blocks, copied by the
detach walk into one buffer: a block table, a relocation table, a
type table, then the blocks in walk order. An image is placed back
into a domain by a **zip**: the allocator claims one consecutive run
per size class, the blocks are copied in order, and the relocation
table rewrites each pointer word to its placed address. An image is
the form for storage and for atom-to-atom movement — it round-trips
the exact block graph, sharing included.

**The packed form** is a stream of **64 KiB chunks**. Each chunk is
self-contained: a symbol table, a shapes table (each distinct map
shape stored once), and typed columns. A **container** is a run of
chunks — sixteen chunks fill one 1 MiB Eve page. The packed form is
the wire and columnar form: chunks are independently decodable
(their tables are chunk-local), a reader can scan or filter typed
columns without materializing rows, and row-shaped data packs
smaller than Transit MessagePack on the corpora measured (below).
64 KiB is a target, not a cap: a writer seals a chunk when the next
row would pass 65,535 bytes, the largest size at which every offset
inside the chunk stays 16 bits.

Which form when: images move _Eve values_ (exact graphs, placed back
as persistent values); chunks move _data_ (rows and values encoded,
read through the packed machinery, decoded only on demand).

## Domain boundaries

Flat is the sanctioned form **outside** atom domains — what a value
becomes when it leaves, and the only thing that may enter from
outside:

- **Construction.** Inside a `swap!` (or an explicit domain
  context), Eve types construct in their persistent form, in that
  domain. With **no domain bound, construction takes the flat
  path**: instantiating an `eve-deftype` type — a user type,
  `eve.dataset/dataset`, `eve.array/eve-array` — produces the
  type's flat variant instead of throwing (the construction law's
  no-domain throw, Nov 11 2026, was relaxed on Nov 14 2026; it
  survives only for internal builders that have no flat form).
  Eve data outside an atom is flat data, by construction.
- **Exits.** `detach` produces an image of an atom's value;
  `pack` produces chunks; dereferencing an atom delivers its
  value's flat parts whole (below). All are pure reads of the
  source domain.
- **Entries.** `place` installs an image into a target atom inside
  a swap; `transfer!` composes detach + zip + commit for an
  atom-to-atom move in one call; and a flat value swapped into an
  atom as part of a larger value **embeds by reference** at its
  position (below) — it is not walked and rebuilt.
- **Untrusted bytes are never placed raw.** Bytes from outside go
  through the checked gate — `eve.flat.check/open-checked` — which
  validates the full form (tables in range, relocations sane,
  nesting bounded) and answers a checked handle; placement accepts
  only that handle. The checker refuses with named, typed errors,
  and a 10,000-deep nesting is refused at the exact boundary (64)
  without overflowing (Check's report: 46-row malformed corpus, 250
  fuzz mutants, all refused cleanly). The gate vouches _form_, not
  integrity: a flipped value byte yields a different well-formed
  value. Integrity is the digest's job (below).

## Flat inside atoms

Flat is not only a travel form. Inside an atom, a subtree can live
in its flat form — flat is a _value state_, at subtree granularity:

- **Freeze** (`eve.flat.freeze/freeze-value`, and the generated
  `freeze` method every `eve-deftype` type carries) converts a
  value to flat form _in place in the domain_: the packed chunk is
  striped into a chain of fixed-size-class blocks (never the
  variable-size class), and the value's slot points at the chain.
  A frozen value is closed — it holds no pointers into the
  surrounding persistent tree.
- **Reads are pinned and whole.** Dereferencing walks from the
  read point and collates the persistent and flat parts into one
  flat object; a frozen subtree reads through its chain exactly
  like the value it froze. What leaves the atom is always a
  regular flat object — persistent Eve data never leaks outside
  atoms, and a composite of flat bases with persistent overlays
  (next point) is an atom-internal state, never a delivered one.
- **Upgrade is O(edit).** Editing a frozen subtree — an `assoc`
  through its persistent parent, say — does not thaw the value.
  The edit path upgrades the subtree to a persistent overlay that
  **embeds the flat original by reference**: the composite reads
  as base-plus-edits, and the cost is the edit, never the size of
  the frozen value. Nothing refreezes automatically.
- **Refreeze is a verb** (`freeze/refreeze`, `freeze/refreeze!`):
  collapse a composite back to a pure flat value, equal — and
  digest-equal — to its materialization.
- **Ingestion embeds.** Swapping host data that _contains_ flat
  values (a delivered batch nested in a map, say) persistent-izes
  the host parts and embeds the flat parts by reference at their
  positions — no materialization on the way in, and reading back
  returns the logical flat value whole.
- **Retirement is exact.** A frozen chain is held and dropped by
  the same tally walks as any other value, at root or nested: when
  the last reference goes, the chain's blocks return to their
  classes. (The retire census at canonical scale — 10,000 rows
  frozen, installed nested, replaced, churned — is the acceptance
  measurement; it caught a real defect in the tally's tag table,
  since fixed: a nested chain was invisible to the walks and never
  retired.)
- **Freeze what's scanned or shipped, never what's navigated or
  picked at.** That rule is measured, not aesthetic — the
  performance section prices both directions.

## Flat on the heap

Outside atoms, flat values are ordinary host values with unusual
storage:

- **Protocols, not classes.** A flat value satisfies the Clojure
  collection protocols over its bytes — `get`, `nth`, `count`,
  `seq`, `assoc`, keyword lookup on maps and on user-type sisters —
  so ordinary code navigates it without knowing it's flat.
  Code that checks concrete classes, or calls host interop on the
  value, must materialize first (`eve.flat.freeze/plain-of`);
  materialization is the escape hatch, priced in the performance
  section.
- **No identity.** Flat values have value semantics in the
  Valhalla sense: two flat values with equal content are the same
  value, full stop. Persistent Eve values carry identity; flat
  values never do. A flat value also owns its bytes, so it carries
  no epoch pin and stays readable forever — unlike a pinned slab
  read, nothing can retire underneath it.
- **Equality is one domain** (also under How it works): flat =
  slab = plain when logically equal. A chunk can latch its root's
  hash and content digest at pack time (a flagged slot in the
  header other readers skip), so a delivered value's first `hash`
  or equality check is a field read, not a walk.
- **Nesting in host structures.** Flat values nest inside ordinary
  host maps and vectors and navigate there. Two handler sets in
  `eve.flat.transit` ship them through Transit: the _plain_ flavor
  writes a flat value as its content (the far side gets ordinary
  data); the _flat_ flavor writes it as its chunk bytes (the far
  side gets the flat value back, still flat). Bare Transit refuses
  chunk classes — it dispatches on class, not protocols — so one
  of the two handler sets is required.

## How it works

**The verbs** (`eve.flat`): `detach` (value → image bytes), `pack`
(value → chunk bytes), `open` (bytes → a flat value, read in place),
`place` (image → into an atom, inside a swap), `transfer!`
(atom → atom). On top of those:

- **The builder** (`eve.flat.build`) ingests a host value directly
  into an image without materializing Eve values first — the
  no-garbage path for loading data into an atom.
- **The IO surface** (`eve.flat.io`) is Transit-shaped streaming: a
  writer emits each value as one packed chunk framed by a 4-byte
  big-endian length; the reader's only door is the checked gate —
  there is no unchecked read path. Records dispatch by wire type
  key to handlers, as Transit's do.
- **Stream transfer and digests** (`eve.flat.stream`). An atom's
  whole block graph streams to another atom or process as typed
  frames — the receiver lays the blocks down directly, runs the
  journal tail, and replays to the exact value. Every value has a
  content digest (Merkle over its flat bytes, computed lazily and
  latched on the handle); a stream can carry per-value digests
  under three trust modes: `:verify` (the default — recompute on
  receipt and refuse a mismatch), `:trusted` (stamp as-is), and
  `:off`. Form is the checker's job; integrity is the digest's.
- **Generated freeze/thaw.** Every `eve-deftype` type carries a
  generated method pair beside its sister: `freeze` on the
  persistent kind (value → its flat form, in a domain) and `thaw`
  on the sister (flat form → the plain value), dispatched through
  `eve.flat.sister/freeze` and `sister/thaw`. User code writes
  neither the sister nor the pair — one `eve-deftype` definition
  produces the persistent type, its flat mirror, and the
  conversions between them.
- **Run claim.** Placement used to allocate block by block. The
  allocator now answers a _run claim_: one consecutive run of blocks
  per size class, claimed in a single CAS-guarded operation, safe
  across processes on a shared disk domain (Disk's lane: two
  processes claiming concurrently get disjoint runs, every block
  freed exactly once). The zip then copies into the claimed run.
- **Counts are walked, not written.** Counts-at-placement was spiked
  (fork F5) and **not adopted**: written counts measured _slower_
  than walking — in the decider probe, walk 161.57 ms (min) against
  written 391.26 ms — so placement recomputes counts by walking,
  and the spike stays in the tree behind an off-by-default flag
  (Count's report).
- **Equality is one domain.** A flat value, its slab original, and
  its plain host form are `=` to each other and hash alike; the
  packed handles implement Eve's equality over the bytes. For user
  types that define no equality of their own, the sister compares
  by type key and fields (fork F2, disposition (a), implemented;
  the operator's final disposition is pending).
- **Wire identity is the type key.** A flat header carries the
  type's namespace/name key and a layout fingerprint — never a
  registry type-id, which is a per-process accident. A fingerprint
  mismatch reads as a generic record rather than as the type (the
  same answer the persistent side gives a shape it doesn't know).
- **Appends.** A flat value is a view (buffer, length, root).
  Appending writes past the current length; older views over the
  same buffer are undisturbed and stay equal to what they were.
  There is no append _inside_ a placed persistent value — append is
  a property of the flat form in hand.
- **Persistence of placed values.** A placed value is an ordinary
  persistent value afterwards: it survives on disk (Disk's lane:
  placement exact across won, lost, and thrown swaps and across
  close/reopen) and through the journal (Journal's lane: a placement
  committed in a journaled mmap domain survives a kill before the
  next commit, value equal, allocated set exact).
- **Datasets.** `eve.dataset.batch` bridges Eve's Dataset and the
  packed batch form: each column is encoded once through the column
  machinery (no row transpose), and a batch rebuilds an equal
  Dataset. Strings, keywords, symbols, booleans, nulls (validity
  bitmap), int64, and instants are covered; anything else refuses
  by name (Dataset's report).

## Hosts

The flat namespaces are `.cljc` — one implementation, host branches
only where a host's byte machinery genuinely differs. Measured state
(Hosts' board, all on the J venue):

- **JVM** — the reference host; every suite green.
- **babashka (fleet 1.13.226) and bb-minimal** — the flat suites
  run; the only failures are in the contract/scoreboard test
  machinery itself, not in flat code. One gap: `chunk` cannot load
  on bb — it names `clojure.lang.Murmur3` with no `:bb` branch, and
  any substitute must be hash-identical, since chunk equality is
  byte-level (a named collation item, unfixed).
- **Node / ClojureScript** — images built on Node are **byte-for-byte
  identical** to the JVM's on the reference values (CLJS image
  report); the chunk implementation is the same `chunk.cljc`. One
  gap: `chunk` does not load under nbb (a `js*` resolution failure
  in the chunk's CLJS side; shadow-compiled seats are unaffected —
  named collation item, unfixed).
- **jolt** — the suite runs end-to-end; byte layout is identical
  (the golden-bytes arm passes). Its few reds trace to test-machinery
  pins, not to jolt.
- **jank, basilisp (lpy)** — no flat namespace loads yet; each
  block
  ...[truncated 11083 chars]er is measured and named in Hosts' lists (missing
  `clojure.java.io` / `java.io.File` host modules, macroexpansion
  breaks) — host work, not flat work.
- **ClojureCLR, clojerl, let-go (glj)** — not measured: their
  toolchains do not exist on the venue. Suite definitions stand
  ready in Hosts' lists.
- **Browser-class hosts (scittle, cherry)** — not attempted; no
  browser-worker harness on the venue.

## Performance characteristics

Everything below is measured, on the JVM on the campaign's J venue
unless a line says otherwise, min-of-5 discipline per the
scoreboard. Gates are the plan's; **misses are stated as misses.**
The governing shape: flat costs are proportional to blocks or rows
moved — the zip-copy law — and the shipped paths currently pay
large per-unit constants on top of it. Where the constant is the
story, the line says so.

### The current numbers (Nov 2026, canonical corpora)

Sizes first — the packed form against the alternatives, same
values (records: 10,000 uniform maps; messages: 5,000 nested
payloads):

| form                  | records-10k   | messages-5k   |
| --------------------- | ------------- | ------------- |
| image                 | 2,167,976 B   | 5,162,744 B   |
| stream                | 1,817,243 B   | 4,402,009 B   |
| **packed**            | **230,600 B** | **696,152 B** |
| Transit (MessagePack) | 358,526 B     | 870,399 B     |

Reads, on delivered flat values versus the same data as heap
objects — the read taxonomy the whole design steers by:

| operation                         | flat                        | heap     | verdict                                         |
| --------------------------------- | --------------------------- | -------- | ----------------------------------------------- |
| hash of a delivered value         | 46–100 ms (whole corpora)   | —        | latched at pack: a field read with root recipes |
| point access (one cell)           | 1,274–1,878 ns              | 37–43 ns | flat loses ~35×                                 |
| full scan                         | 7.5–12.2 ms (whole corpora) | slower   | flat wins                                       |
| row materialization / full decode | ~11× Transit's cost         | —        | flat loses; don't                               |

The rule those numbers write: **freeze what is scanned or
shipped, never what is navigated or picked at.** Flat wins full
scans, aggregations over uniform rows, filters and projections,
and whole-unit movement (storage, wire, atom-to-atom). It loses
point reads, deep navigation, row materialization, and full
decode — those belong to the persistent form in an atom, or to
materialized heap data.

In atoms, the swap-cost shape (frozen values are frozen _inside_
the swap function, per repetition — the only legal pattern, since
a frozen value belongs to its domain): replacing a root with its
frozen form costs the freeze itself (a pack striped into the
domain); editing a frozen value costs the edit — materialize,
edit, refreeze, all inside the swap — never the value's size in
machinery, though the current refreeze is a full repack (the
overlay machinery makes the _stored_ form O(edit); the verb
itself is O(value) and priced as such). The sections below are
the campaign's detailed record behind these summaries.

### Placement (the zip)

The raw run-claim primitive costs **32.8–50.7 ns/block** across size
classes (Claim's report) — the mechanism is cheap. The shipped
`place`, which validates tables, passes the checked gate, and
operates per block, costs far more: **15,275 ns/block** on the
messages corpus, 15,998–38,030 ns/block across the four corpora,
against the B1 gate of **≤50 ns/block — missed by ~300×** (Zip's
report). Claim+copy against per-block allocation: 2.93× / 8.85× /
2.64× by class — the class-3 arm **misses** its ≥3× gate at 2.64×;
claim-only (no copy) passes ≥3× on every class (Claim's report).

### Detach

Detach is correct on every corpus (values verified equal) and slow:
against `@a` it measures 0.01×/0.00×/0.01×/0.01× — **the B3 gate
(detach ≥2× faster than `@a` on every corpus) is missed by
~100–250×** on the collated tree (Collation report scoreboard).
The walk pays per-block slab reads; at lane pins the same wound
measured 114–733× (Detach's report). Detach as shipped is a
correctness mechanism, not yet a fast path.

### Transfer

`transfer!` costs what its parts cost and no more: ratios against
detach+zip are **0.92 / 0.96 / 1.00 / 1.08 across the four corpora —
the ≤2× composition gate passes** (Collation report). The absolute
caps (≤5/26/9/26 ms) are **missed by ~250–1,900×**, inherited from
the walk and placement costs above (Transfer's report).

### Ingest (the builder)

Whole-value ingest of a plain host value, against today's `swap!`
of the same value (Builder's report):

| corpus           | ratio vs swap! | reading                                  |
| ---------------- | -------------- | ---------------------------------------- |
| map 10k int→int  | **0.11×**      | beats the 0.37–0.56× probe band outright |
| records 10k      | 0.60×          | near-miss over the band                  |
| messages 5k      | 0.68×          | miss — build cost dominates              |
| vector 100k ints | 1.61×          | miss — placement cost dominates          |

The B5 gate as written (band met on every corpus) is **missed**;
the map row is the campaign's one unambiguous speed win. One
composition caveat was measured after collation: a builder image
did not place through `place`'s in-memory door — the two halves
disagreed on one key name (`:relocations` vs `:relocs`), so a
build image placed with zero relocations applied (F3 measurement,
defect 1). **Fixed** in the F3 wave: builder images place with
their relocations applied.

### Packed bytes and full decode

Size is the packed form's clean win: **B6 passes** — chunk bytes
are **0.801× (messages) and 0.455× (records)** of Transit
MessagePack against a ≤0.9× gate (Chunk's report).

Full decode is the opposite: unpacking a whole value runs at
**0.08× / 0.09× of Transit's speed — roughly 11–12× slower —
against a ≥2.5× gate, missed** (Chunk's report). The F3 profile
(at the collated pin) attributes it: per-chunk setup is 0.4–2.2%
of decode — negligible; row assembly is ~82–86% of materialization;
and a shape-free vector of longs still decodes at 6.65 µs/element,
so the floor is the per-node materialize machinery itself. Decode
cost does **not** scale with chunk count at fixed rows (the curve
is non-monotone; one wide chunk is the slowest packing) — chunk
size is not the lever. In flat terms this is the expected shape:
decode is leaving the flat world, and the world charges for it.
It is priced here because interop paths do pay it.

### Column reads

Through the column door (Columns' batch machinery), the packed
form beats host maps at their own game — and still misses the
Arrow-class absolute gates. Lane-pin numbers, with the collated
re-take beside them (F3 measurement; both sessions agree on the
relative verdicts, absolutes move with box weather):

| row      | lane pin                    | collated re-take          | gate                                                      |
| -------- | --------------------------- | ------------------------- | --------------------------------------------------------- |
| scan     | 129.52 ns/row (host 203.93) | **21.08** (host 29.90)    | ≤2 ns/row — missed                                        |
| filter   | 100.54 (host 364.38)        | **20.13** (host 31.25)    | ≤4 ns/row — missed                                        |
| one cell | 637.1 ns (host 123.8)       | 100.4 (host 48.2)         | ≤2× host — missed (5.1×, then 2.08×)                      |
| row view | 5.346 µs (host 0.301)       | **0.817 µs** (host 0.076) | ≤1 µs — missed at the lane pin, **passed** at the re-take |

Through row handles instead of the column door, a chunk reads
40–400× slower than heap on scans and ~100–140× on single cells
(F3 measurement) — which door a reader uses is the whole read
story. The same measurement's three-way table (chunk vs persistent
in an atom vs plain heap), records corpus: full scan 2,299 / 5,676
/ 57 ns/row; one cell 5,238 / 36 / 51 ns; one row 19.3 µs / 0.07 µs
/ 0.07 µs. The persistent form's point reads sit at heap parity;
its whole-value traversals run 100–500× heap. Neither form is the
heap; each is fast at what it is for.

### Container overhead

**B8 is missed**: a container of 64 KiB chunks costs **+24.36%
(messages) / +25.24% (records)** over the same value compressed as
one stream, against a ≤5% gate (Container's report). The mechanism
is named: per-chunk symbol/shape tables reset every 64 KiB, and a
compressor's window cannot reach the repeats across chunk
boundaries. The fix is format-level (a container-level shared
table or a wider-window compressor) and undecided.

### Reading images in place

Values read from an image are slower than from the slab: map get
**70×**, vector nth **28×** at Detach's pin (27–152× across the
collated re-judgments), against a ≤1.2× gate — **missed** (Detach's
report, Z1.7). The mechanism is Eve core's, not the image's:
`hamt-get` takes its resolved-node descent only behind a concrete
`instance? JvmSlabCtx` gate, so any foreign slab I/O — the image's
included — walks per word, at ~22 µs a descent against ~0.3 µs.
No single image operation is slow (raw and node reads measure at
parity); the fix is a node-resolution hook for foreign slab I/Os,
and it is Eve-core work, named here as an open item.

### Writers

One asymmetry to know before writing chunks at scale: the single
wide `pack` is fine (records 1,599 ms, messages 1,580 ms at the
collated pin — 1.25–1.9× the corresponding `swap!`). The ≤64 KiB
chunk writer, `pack-all`, was **superlinear as first shipped**
— records 662,564 ms, messages 148,270 ms (414× / 94× the wide
pack), because its seal test re-encoded the accumulating chunk
per row (F3 measurement, defect 3). **Fixed** in the F3 wave:
the seal test is incremental now — records-10k packs in
8,720 ms, in line with the wide pack.

## Status and open items

Flat Eve is landed as a working system on the collated tree: every
suite green except image read speed, across two waves of lanes and
a full collation. What is open is named, not buried:

**Forks.**

- **F1, the name — DECIDED**: flat types, `eve.flat` (operator,
  Nov 12 2026). Verbs unchanged: detach / pack / place / open.
- **F2, equality default** — implemented as (a): sisters compare by
  type key + fields. The operator's final disposition is pending.
- **F3, a chunk as one opaque leaf block inside an atom** — open,
  the operator's. The Leaf lane is unbuilt; the F3 measurement
  (chunk vs persistent vs heap, decode profile) exists to price
  the call.
- **F5, counts written at placement** — NOT ADOPTED (see Counts,
  above).

**Defects measured at the assembled pin (F3 measurement) — all
three fixed in the F3 wave and re-measured.**

- Builder image → `place` did not compose (the `:relocations` /
  `:relocs` key disagreement) — see Ingest, above.
- `chunk/open-checked` refused 100% of the chunk writer's own
  output ("a table offset out of range"), while Check's separate
  door vouched the same bytes — the checker's table walk now
  agrees with the writer.
- `pack-all`'s superlinear writer cost — see Writers, above.

**Residuals carried by collation (COLLATION-REPORT.md).**

- **A16** — a two-ref `dosync` can half-commit when the second
  ref's foreign value throws at store time. Eve STM commit-protocol
  territory; the `:zip` policy removes the refusal that triggers
  it here.
- **Thrown-swap block leak** — a swap attempt whose function throws
  leaks its claimed blocks (583 blocks measured in a heap domain,
  identical after kill + reopen in a persistent one). The cause is
  atom-level (`swap-attempt!` has no catch around the swap fn).
- **B8 container format question** — see Container overhead.
- **Kind-18 symbol codes** — u16 per chunk; a chunk past 65,536
  distinct symbols truncates. Respected by the Dataset bridge, not
  fixed.
- **H4.1** — Eve's CLJS deftype field reads hardcode the module
  slab, so a Dataset's fields can't be read back through its
  deftype on ClojureScript, though the image bytes are proven
  correct field-by-field. Eve-core, same family as Z1.7.
- **Requirements manifest host-blindness** — the contract manifest
  has no host dimension, so a CLJS-judged requirement errors the
  JVM requirements run (the collated run's one error).
- **Image read speed (Z1.7)** — see Reading images, above.
- **Chunk host gaps** — the Murmur3 `:bb` branch and the nbb `js*`
  load failure (see Hosts).

**Flagged for the operator.** The contract amendment (collation
commit 15d3d3b): the contract's nil-probe accepts a verb's _typed
refusal_ as a valid answer. It is how four lanes' worth of nil
handling got repaired; it stands in the tree as a flagged commit
awaiting the operator's disposition.

## Status — Nov 14 2026 (claude/flat-on-release, post three-seat board)

The construction law is relaxed: instantiating Eve data
outside an atom/transaction produces the flat variant (the
eve-deftype constructor funnel, plus dataset/array
no-domain arities); the no-domain throw survives only for
direct alloc callers with no flat form.

The three-seat verification board (hatch / J / Mac, 37
tests) named four flat defects; all four are disposed:

- **Frozen-child delivery** — packing a root map holding a
  frozen child threw in the chunk reader: the chunk rep
  converter sister registers lives behind a delay that only
  descriptor registration forces, so a plain data pipeline
  saw an empty registry. eve.flat.freeze now registers its
  own converter at load.
- **Sister protocols** — twin sisters compared unequal and
  printing/materializing one recursed forever: the vocab's
  synthesized Object group satisfied flat-type's
  protocol-level check, suppressing the sister's whole
  Object segment (field-map equals, hasheq hashCode,
  field-printing toString) in favor of deftype identity
  defaults plus a `(pr-str this)` toString with no
  print-method behind it. flat-type now drops the
  synthesized self-print and emits the boilerplate per
  method. Suites: sister-test clean (was 1F/1E on all
  three seats).
- **Nested-frozen retire "leak" (227 blocks)** — a
  phantom: the census probe's nested cases never removed
  the value from the atom, so the chain was live, not
  leaked. Discriminator: the value reads back equal while
  held and retires to baseline exact once removed; the
  corrected census (R/N/N2/V) returns to baseline in all
  four variants. The earlier real defect in this family
  (tally's tag-kind missing the 0x20 frozen case) stands
  fixed as recorded in UPGRADE-PLAN.
- **Sister thaw recursion** — plain-of, the thaw
  materializer, returned a sister (or a FrozenValue view)
  as itself. It now materializes a FrozenValue through its
  own -plain and a sister through its field structure,
  recursively: `(sister/thaw s)` yields the plain field
  map.

Verification on the fixed tree (hatch): the five flat
suites (chunk, dataset-batch, tally, stream, sister)
31 tests, 473 assertions, 0 failures, 0 errors; the probe
battery (subtree, transit both handler flavors, packnest2,
embed, funnel, genmethods, census, thaw) green. The
transfer suite's two remaining items are Eve-core, not
flat: an exact residual-census expectation that drifts
per seat, and a heap-region OutOfMemoryError creating
domains at a 2 GiB test heap (clean on the Mac).

One behavior worth naming: a Dataset derefs out of an atom
as its declared plain form — a map of column name to plain
column — because eve.dataset defines IPlain for the type
and deref materializes it. That is uniform across the
persistent and flat kinds (the persistent Dataset derefs
the same way); a type with no IPlain, like a user
eve-deftype, delivers its flat sister at deref.

Still open: CLJS parity for the construction funnel and
the sister Object methods; refreeze / stream-of-composite
verification; the F2 equality disposition (implemented:
type key + fields); the heap-region OOM above.

## Sources

Every figure above comes from the campaign's own records in this
directory: the lane reports (Z1–Z4, T1, B1, C1–C4, L1, X1,
H1–H6, H8), SPIKE-LOG.md, COLLATION-REPORT.md, FORMAT-V1.md, and
the F3 measurement report (collated pin; its tables supersede
lane-pin rows where both exist). Research-era expectations are in
MEASUREMENTS.md and are labeled as such wherever the two differ —
the shipped system, not the probes, is what this document prices.
