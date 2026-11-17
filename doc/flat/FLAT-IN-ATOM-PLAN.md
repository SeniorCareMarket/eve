# FLAT IN ATOM — implementation plan

Status: APPROVED (John, Nov 13 2026), then MIGRATED TO THE
CORRECT TARGET the same day (John's order: "review the impl
plan and migrate the impl plan to the correct target. All
assumptions must be reassessed now."). Captures the
requirements settled in the Nov 12–13 design discussion. This
is the F3 disposition: the Leaf lane, reframed.

## TARGET MIGRATION (Nov 13 2026) — read this first

The plan was drafted, and S1 was built, against the WRONG
allocator format: the legacy CLJC domain allocator in
eve.deftype-proto.alloc (6 bitmap classes, 32–1024 B, plus a
coalescing overflow class; slab pointers [class:3 | block:29];
SLAB_MAX_BLOCK_SIZE 1024). That is the old format. The correct
target — the format Eve and the ValueScript kernel agree on
(alloc.vs: "Same as eve_constants.vs BLOCK_SIZES[14] so both
allocators agree"; carried in this tree by eve.net.pages and
eve.deftype-proto.coalesc, whose design is "the same in eve
and in ValueScript's kernel") — is:

- **1 MiB pages** (REGION_SIZE), each opened by a 64-byte PHV2
  page header: magic, 128-bit page CID, class @20, block size
  @24, page size, total blocks, bitmap/descriptor offset, data
  offset, owner, layout version, epoch.
- **16 classes.** 0–14 fixed-size, bitmap-tracked:
  4, 8, 16, 32, 64, 128, 288, 576, 832, 1088, 1600, 2112,
  3200, 4224, 10240 bytes — sized to the block shapes the
  kernel produces (root headers with a 16-byte type-CID
  prefix, 256-byte ExtCID child slots, HAMT interiors).
  **Class 15 is the coalescing overflow**: variable-size
  extents under a descriptor table, adjacent frees merged on
  free; sizes above 10240 route there; the largest requests
  take consecutive raw regions.
- **Addressing is (page, linear index)**: a block's address is
  arithmetic on its page (page_of + index), in any thread,
  with no table. The class lives in the PAGE header, never in
  the pointer. There is no [class:3 | block:29] in the target.
- **Identity is ExtCID**: a block's own 256-byte header
  carries its type CID, with the type tag and payload after
  it; content hashes (SHA-256) live in that header. This is
  flat FORMAT-V1's layout 2 — the flat format anticipated
  this target; what the campaign built is layout 1.
- **Tally is on-descriptor**: every block's descriptor row
  (32 B in the current layout) carries the holder count and
  flags (PUB/QUEUED/LEAF/…) at its TALLY word. There is no
  separate reference-walk tally of the legacy kind.

### Assumption reassessment (every load-bearing one)

- **"Slab blocks top out at 1 KiB; a chunk cannot be one
  block" — DIES.** Fixed classes top out at 10240 B, blocks
  are size-routed by byte count alone, and a 64 KiB chunk is
  ONE variable-size extent in class 15 (a small frozen chunk
  is one block in a small fixed class — flat content is not
  assigned a class by type).
  The chain (header block + 65 kind-7 segments) was a
  workaround for a format that is not the target. Deleted
  from the design; R2 restated below.
- **FLAT LADDER — DECIDED (John, Nov 13 2026).** Flats
  that outgrow the shared fixed classes go to a SEPARATE,
  FINE-GRAINED ladder of their own (not the persistent
  side's variable class 15), and rungs are allocated
  LAZILY — "just don't allocate rungs until they're
  needed": a rung commits no page until the first chunk
  of its size arrives. Measured basis (page-tax model,
  Nov 13): a fine rung pays for itself once it holds on
  the order of a hundred-plus chunks; below ~100 MiB of
  spread-out frozen payload the per-rung page tax would
  dominate a pre-allocated ladder, at ~1 GiB the internal
  -waste savings overtake it — lazy allocation removes
  the small-scale penalty, since unneeded rungs cost
  nothing. What remains is last-page slack on rungs that
  actually activate, bounded by real use.
- **Pointer form [class:3 | block:29], class decoded from
  pointers — DIES as a target assumption.** Target pointers
  are (page, index); class comes from the page header. This
  reaches beyond this plan: the whole built flat stack
  (image block tables recording a class per block,
  block-scan's class parameter, the stream's class runs, the
  run claim) encodes the legacy pointer/class model. That
  stack's own migration is a separate, larger item — named
  here, not silently absorbed into this plan.
- **Header tag bytes (0xED/0xEE/0xEF siblings, my 0x20) and
  the serialize header-constructor registry — CHANGE.** Those
  are the legacy format's identity scheme (type = first byte
  of the block). In the target, identity is the ExtCID type
  CID/tag in the block's own header. A frozen chunk's type
  identity is stated in that scheme.
- **Tally/retire via a refs walk; "an unknown kind is never
  held" — the MECHANISM changes, the LESSON carries.** Target
  retire is descriptor counts: a block is freed when its
  on-descriptor count drains. The S1 leak (root classified
  opaque → chain never held → never freed) translates to: a
  frozen extent's descriptor must be born held by its root
  reference, and a root's reference to a class-15 extent must
  be a first-class counted reference in the target tally.
- **The FrozenValue design (a root value delegating reads to
  an opened chunk handle, bytes gathered once, cached) —
  HOLDS as a shape.** Its construction/registration hooks
  move from the legacy serialize registry to the target's
  ExtCID read path.
- **R1 closed values — HOLDS**, stronger in the target: an
  extent is self-contained by construction.
- **R3 freeze/upgrade, R4 granularity/eligibility — HOLD**
  (value semantics). Their mechanisms rebind to the target's
  swap/place machinery.
- **R5 deref delivers flat, R6 protocol completeness —
  HOLD** (heap-side; storage-format independent). S0's
  findings were about chunk handles and carry unchanged.
- **R7/R8 assurances and semantics — HOLD**, owed against
  the target build.
- **R9 digest — CHANGES, gets cheaper.** No digest-words
  field in a header block of this plan's invention: the
  target block's own ExtCID header carries its content hash.
  Freezing latches identity by sealing the extent's own
  header. (The stream dedup/trust modes keep their design;
  their digest-on-collection-roots mechanism is legacy-side.)
- **R10 reference columns — HOLDS** (parked; format-free).
- **R11 small-value policy, R12 guidance — HOLD.**
- **The 64 KiB chunk — HOLDS, and aligns literally**: the
  target page is 1 MiB, so the packed container (16 chunks
  per page) is the target's own page, not an analogy. A
  chunk in an atom is one block, size-routed (class 15 at
  full chunk size).
- **The S1 chain build (eve.flat.freeze chain writer,
  FrozenValue, tally :frozen kinds, block-scan kind 7,
  checked entry with latched digest) — RECLASSIFIED**: a
  working prototype on the legacy domain allocator. Its
  VALUE-side proofs carry (value construction from stored
  bytes, protocol reads, equality, deref delivery, checked
  entry, digest latching, exact retire accounting). Its
  STORAGE mechanics (chain, segment kinds, registry tags) do
  not transfer and are not the target design.
- **One open decision the migration cannot settle itself**:
  the build seat. The target format's living seats are the
  kernel (alloc.vs / tally.vs) and Eve's net/pages + coalesc
  pieces; the CLJC domain allocator eve.atom runs on is the
  legacy one. S1' step 0 is the characterization that
  establishes which seat can host a class-15 extent as an
  atom value today; if two seats prove live with different
  costs, that choice is John's.

## The idea in one paragraph

A flat value is a *state a value can be in*, inside an atom as
well as outside it. A persistent subtree can be **frozen** into
flat form (packed chunk, one leaf block) to make it cheap to
store, ship, and scan; a write into a frozen subtree **upgrades**
it back to persistent form on the swap's write path. And an
atom's deref delivers flat (`eve->flat` in place of `eve->clj`
materialization), so what lands in the caller's heap is a flat
object: self-contained bytes plus a complete protocol mirror,
ready to ship to the wire with no further work. The advice to
users is *ship from the atom, change in the atom* — but the
objects must still be honest general-purpose heap citizens, with
published performance characteristics against the Clojure heap
objects they replace, and a documented materialization path for
anyone who wants plain Clojure data instead.

## Requirements

R1. **Flat values are closed.** A flat value is self-contained:
   no pointers out to persistent blocks, nothing to relocate on
   the read side. Persistent structures may contain flat leaves;
   flat contains nothing outside itself. (Dissolution argument,
   Nov 13: every candidate need for outbound pointers flips into
   per-subtree embedding, is semantically free under
   immutability, or narrows to R10.)
R2. **The in-atom flat form is the packed chunk.** The image form
   is NOT an atom resident; it stays the detach/place transfer
   mechanism. One leaf VALUE per frozen subtree, seen by
   equality, hashing, tally and retirement as one value. Storage
   (TARGET, as migrated): the chunk is stored as **one block,
   size-routed like any other content** — the allocator assigns
   the class by byte count alone: a fitting fixed class when
   the framed chunk is small (a 2 KiB frozen subtree lands in a
   small class), the class-15 variable-size space when it is
   large (a full 64 KiB chunk). No type is assigned a class;
   flat content is not special. One descriptor, freed by that
   descriptor's count draining. No chain, no segment kinds, no
   per-block walk. The first bytes of the block carry the
   chunk's framing (its ExtCID own-header in the target
   identity scheme).
   Reads open the chunk over the extent's bytes in place — no
   gather step; a handle may still cache a byte array where a
   host's extent access makes that cheaper, as an implementation
   choice, not a storage shape. (The legacy-format prototype
   stored the chunk as a header + segment chain because the
   legacy bitmap classes top out at 1 KiB; see the migration
   section for why that shape does not transfer.)
R3. **Freeze and upgrade are the two transitions.** Freeze:
   persistent subtree -> chunk leaf, installed by a swap.
   Upgrade: a write whose path enters a frozen subtree places
   that subtree back into persistent blocks, applies the edit,
   publishes the persistent root. The pre-swap flat value stays
   valid for anyone holding it until it retires — upgrade is
   value-level COW, the kind every swap already does; there is
   no plain-Clojure materialization anywhere in the path, and no
   copy beyond the unpack the write itself needs.
R4. **Freeze granularity is the subtree.** A persistent root with
   flat leaves at its cold parts is the normal shape; freeze
   chooses where the cold is. Freeze eligibility: the subtree
   holds no by-reference content (fns, host objects — the
   digest-less, process-local kind); such subtrees are refused,
   and the freeze boundary stops above them.
R5. **Deref delivers flat.** Atom deref, swap return values, and
   watcher notifications deliver the flat form instead of
   materializing. Because flat bytes are owned by the value, a
   deref'd flat object carries NO epoch pin and NO coupling to
   the domain's lifetime — unlike today's pinned slab reads,
   which are epoch-bound by design. A per-atom (and dynamic)
   opt-out delivers materialized plain values instead, as the
   migration/compat path.
R6. **Flat heap objects are protocol-complete.** They already
   are, by construction: eve.deftype generates the flat sister
   with the persistent kind's full protocol mirror. Every
   protocol-based consumer (get, nth, count, seq, assoc, into,
   reduce, equality, hashing, printing) works on a flat object
   without materializing.
R7. **Published performance assurances.** The plan owes a
   measured table, flat heap object vs the Clojure heap object
   for the same value, same session: get / nth / count / seq
   traversal / hash / equality / assoc / footprint in bytes.
   Known anchors going in (measured, Nov 12): column scans beat
   host structures (21 vs 30 ns/row); point cell reads cost ~2x
   a host get; a row materialized as a host map is expensive
   (5.35 us) — flat pays when read in place, and the table must
   say so where it does not.
R8. **Semantics differences are documented, not discovered.**
   The plan owes a written account, in FLAT.md, of how flat heap
   objects differ from Clojure heap objects:
   - identity: flat objects are pure values; `identical?` across
     two derefs or a round trip is not promised, equality and
     hashing are by content (one equality domain, as today);
   - type: protocol-polymorphic code works unchanged;
     class-checking code (`instance? PersistentVector`, Java
     collection interop, reflection over concrete classes) does
     not — materialize for it;
   - mutation cost shape: assoc into a flat object is an upgrade
     (R3/S6), not a path-copy; mutation-heavy workloads belong
     in the atom or on materialized data;
   - hashing: a flat object's content digest can be latched and
     carried, so repeated hashing/equality against known content
     can be O(1)-ish where a Clojure heap object re-walks;
   - footprint: bytes, not an object graph — no per-node object
     headers; a deref'd flat value is dramatically smaller in the
     heap than its materialized form.
R9. **Content identity closes.** Because flat values are closed
   (R1), a flat object's digest is computable from its own bytes
   alone. In the target format the frozen extent's own ExtCID
   header carries its content hash — freeze seals that header;
   a frozen value shipped to a peer is one extent plus its
   header identity, and the stream machinery (dedup,
   trusted/verify receive modes) applies unchanged. (The
   legacy prototype latched eve.digest words in its header
   block — same requirement, legacy mechanism.)
R10. **Reference columns are OUT of v1, named for later.** The
    one surviving outbound-pointer candidate — rows referencing
    big shared blobs held elsewhere in the domain — is a bounded
    format addition (a pointer-typed column) to be decided on
    its own merits after v1. It does not block anything here.
R11. **Small values stay cheap.** Deref delivering flat must not
    tax small values: the deref policy is size/shape-aware
    (threshold below which delivery is the sister form or
    materialized, set by the S4 measurements, not by taste).
R12. **Guidance ships with the feature.** FLAT.md gains the user
    contract: ship directly from the atom; change in the atom
    first; if you want Clojure heap data, materialize with the
    existing `eve->clj` path — with the R7 table as the basis
    for choosing.

## Where we start (inventory)

Exists today: the packed chunk format + pack/open/open-checked;
chunk reads in place (row views, column surface); flat sisters
with the full protocol mirror (auto-generated, landed); equality
across forms in one domain; detach/place for the image form;
the stream sender/receiver with digest dedup and trusted/verify
modes; eve.digest latching (`root-digest`, `latch-digest-words!`,
`digest-words-of`); the perf suite as the measurement driver.

Missing: a chunk as atom content (no leaf-block kind for it;
storing a value into an atom explodes it into collection blocks
today); the freeze/upgrade transitions; deref delivering
anything but materialized plain values (plain-deref is the only
deref path; lazy slab values exist only inside pinned reads);
the heap-object performance table; the semantics document.

## Steps

S0. **Characterize before building — DONE (Nov 13,
    lane-tools/probe_s0.clj).** Findings on opened chunk handles
    (the deref deliverable under R2): reads are complete today —
    count, nth, row-field get, seq, hash, and equality against
    the plain value all work, on row batches and on nested maps
    (get-in included). Map handles assoc in place-flat: assoc-in
    returns a new flat handle, no materialization. TWO gaps:
    (1) assoc on a vector/batch handle throws
    AbstractMethodError — FlatBatch.assoc does not route to the
    existing assocN-flat; closing this is an S3 acceptance item
    (R6's protocol-completeness claim is false for vectors until
    it lands). (2) Not measured here, by design: precise op
    costs — that is S4's battery, same-session against Clojure
    heap objects. (The image form's heap story was probed and
    set aside: image-view is an SIO view, not a value accessor;
    moot under R2.)
S1'. **Chunk as atom content (TARGET design, as migrated).**
    The chunk is stored as ONE block, size-routed: allocated
    by byte count (fixed class when it fits, class 15 when it
    doesn't), identity = the block's ExtCID own-header (type
    tag for a frozen chunk; content hash sealed at freeze,
    per R9). One descriptor, one tally count, retire = the
    count draining and the block returning to free space
    (coalescing, in class 15).
    Entering an atom from outside goes through open-checked
    (bytes are vouched before they become content).
    Equality/hashing against the same content in persistent
    form must hold (one domain).
    Step 0 — CHARACTERIZE THE SEAT (owed first, per the
    migration): establish where a class-15 extent can live as
    an atom value today — the kernel seat (alloc.vs/tally.vs),
    Eve's net/pages + coalesc pieces, or the legacy domain
    allocator's own coalescing overflow class as the class-15
    analogue — and what each seat's tally does with a root
    reference to a variable-size extent. The build follows the
    characterization; two live seats with different costs is
    the one fork reserved for John.
    Step 1 — extent store/read: write a chunk's bytes as one
    extent in the chosen seat; construct the value from the
    extent (the FrozenValue shape: reads delegate to the
    opened chunk); prove count/nth/get/seq/hash/equality
    against the plain value, as S0/S1-prototype did.
    Step 2 — retire proof: install, replace, churn; the
    descriptor count drains and the extent's space returns
    (coalescing visible), matching a plain-value control —
    the target-form of the prototype's used-block trial.
    Step 3 — checked entry: outside bytes vouched by
    open-checked before the extent is written; the extent's
    own-header hash sealed from the vouched value.
    S1' FINDINGS (Nov 13, built + proven locally on the
    legacy seat): step 0's answer — the seat that can host
    the extent TODAY is this tree's own coalescing overflow
    class, the class-15 analogue inside the allocator
    eve.atom already runs on (eve.deftype-proto.coalesc is
    the design the kernel shares). Raw extents verified:
    1,025 B – 200,000 B allocate, write and read back in that
    class, indices advancing by extent size. The extent form
    is built in eve.flat.freeze as a second storage mode:
    tag 0x21, 44-byte header (length, digest words, flags),
    chunk bytes after it, ONE block at any size (the framed
    records chunk is 2,164 B → one class-6 block); tally kind
    :frozen-extent, no refs (closed); block-scan case added.
    Proven end-to-end (probe_s1extent): checked install,
    constructor read-back, count/nth/equality, header digest
    == the value's eve.digest words, plain deref delivering
    the frozen value, truncated bytes refused, and retire —
    after reset + churn, a fresh allocation of the extent's
    size lands on the extent's own freed address (coalesced
    and reused). The kernel seat remains the format's home;
    the mechanism here (one extent, one descriptor, count
    drains → free) is the shape that carries to class 15
    when the domain allocator unifies. Whether the CLJC
    build should instead wait for / target the kernel seat
    directly is the fork step 0 reserved — the analogue
    build does not foreclose it.
    PROTOTYPE RECORD (Nov 13, legacy format — superseded as
    the design, kept as evidence): the chain build
    (eve.flat.freeze: header tag 0x20 after two corrections —
    0xF0 violated FORMAT-DECISIONS §9's process-private
    reservation; the tally classified the unknown header
    :opaque and leaked the chain until eve.tally learned
    :frozen/:frozen-seg kinds) proved, on the legacy domain
    allocator: value construction from stored bytes, protocol
    reads, equality/hash against the plain value, deref
    delivering the flat value, exact retire accounting
    against a plain-value control, the heap-exact disposer,
    and checked entry with the header digest matching the
    value's eve.digest words. Two legacy lessons banked:
    a registered header-disposer is write-only in that tree
    (the tally graph is the retire mechanism there), and
    legacy -sio-alloc! returns the [class:3 | block:29]
    pointer word, which exceeds Integer/MAX_VALUE unsigned —
    stored bit-exact in i32 fields, never range-checked.
S2. **Freeze + upgrade.** A `freeze` operation usable inside a
    swap function (and a convenience swap! wrapper): packs the
    target subtree to a chunk leaf, eligibility per R4.
    Upgrade: the swap write path detects a write entering a
    frozen subtree and places it first (whole-subtree in v1).
    Acceptance: frozen value reads equal its pre-freeze value on
    every protocol op the S0 probe covers; a write into it
    produces the correctly edited persistent value; the old flat
    value, held across the swap, still reads as it did.
    S2 FINDINGS (Nov 13, built + proven locally, root case):
    eve.flat.freeze/freeze-value packs a heap value and stores
    it as one extent block with its digest latched; a swap fn
    returning it installs a FrozenValue equal to the original.
    UPGRADE is built at the value level: FrozenValue's
    assoc/cons/pop materialize the value (plain-of), apply the
    edit to the plain data, and answer the plain value — a
    swap! storing it publishes the persistent form. Proven:
    swap! assoc-in [0 :name] "zed" through a frozen records
    root lands, the root reads back a plain vector, the rest
    of the value intact and equal to the plain edited value.
    ELIGIBILITY proven: a value containing a fn is refused by
    the packer (ExceptionInfo). Multi-chunk values (>64 KiB
    packed) are refused by freeze-value for now — the
    container form is a named follow-up. GAP (named): SUBTREE
    freeze fails — the serializer writing the parent meets
    the FrozenValue as a nested value and refuses it
    ("Unknown EVE type tag"): swap! special-cases an IEveRoot
    result at the root, but the write path does not compose a
    nested IEveRoot as a block reference. Closing it is an
    Eve-core write-path seam (compose-by-reference for
    same-domain roots), not flat code.
S2b. **The chain form: freezing past one rung (John, Nov 13
    2026).** "Freezing objects larger than 16 KiB — those
    should chain into multiple chunks that link in atom and
    get read out linearly; might need a special leaf type."
    Design, against the machinery already in the tree:
    (1) The special leaf EXISTS: the 0x20 chain header is a
    registered root kind whose content is a byte chain, not
    a value tree (tally kinds :frozen/:frozen-seg, transfer
    scan kind 7) — what was missing is scale and the
    multi-chunk reader, not a new type. (2) Segments are cut
    at the ladder top: 16 KiB less the 8-byte [next][len]
    link header, each segment ONE size-routed allocation
    (target: a top flat-ladder block; this seat: a
    coalescing extent). The old 1016-byte cut was the legacy
    1 KiB block cap, not a design choice. Tally, disposer
    and transfer scan are all size-agnostic (sizes and links
    are read from the blocks themselves) — no changes there.
    (3) The chain carries a byte STREAM: one chunk = the raw
    chunk bytes (today's form, unchanged); several chunks =
    a framed stream (header flag bit 1) with a chunk-length
    directory up front. (4) Read-out is linear: the reader
    walks the links once, in order, gathering the stream —
    no seeking, no per-block random access — and opens it.
    A value spanning chunks opens as a spanning batch
    handle (per-chunk handles + cumulative counts; nth/seq/
    count/equiv delegate). (5) freeze-value routes by size:
    framed total ≤ 16 KiB → the extent form (one block);
    larger → the chain. The multi-chunk refusal dies.
    deliver-flat gets the same spanning open (it silently
    took the first chunk for >64 KiB values — a live
    truncation defect this step closes).

S3. **Deref delivers flat.** Deref, swap returns, and watchers
    take the flat delivery path; size/shape policy per R11,
    opt-out per R5. Acceptance: a deref'd value needs no domain
    access afterwards (prove by retiring/closing the domain and
    still reading it); watchers observe flat values; the opt-out
    restores today's behavior exactly.
    S3 FINDINGS (Nov 13, delivery built + proven; core wiring
    characterized): eve.flat.freeze/deliver-flat packs a value
    and opens the chunk over the byte array — the delivered
    object OWNS its bytes. Proven (probe_s3): from a slab-
    backed EveVector root, the delivery is a FlatBatch equal
    to the value; it still reads after the source root is
    retired and churned, and after the domain is CLOSED
    (close-atom-domain!) — no pin, no epoch, no domain. Swap
    returns already deliver flat for frozen roots (the swap's
    return IS the FrozenValue). WATCHERS (corrected Nov 13 —
    an earlier draft of this section claimed no watch API
    exists; wrong, the search had covered one file): watches
    ARE implemented, in eve.shared_atom — notify-watches
    firing on swap with old/new values (currently delivered
    materialized, via the eve->cljs conversion at the notify
    site), remote watch signaling between workers, async
    watch loops; STM refs have add-watch*/remove-watch*. The
    plain domain atoms (ea/atom) carry no watch firing in
    atom.cljc — the ClassCastException in the S3 probe was
    clojure.core/add-watch used on the wrong surface. Flat
    delivery for watchers = replacing the materializing
    conversion at shared_atom's notify site, same seam shape
    as deref's. SNAPSHOT DISCIPLINE (added Nov 13, John's
    challenge, validated by probe_torn): the delivery
    CONVERSION must run inside the read pin — deliver-atom
    packs within read-pinned, exactly as plain-deref
    materializes within its pin; packing a slab-backed value
    after the pin drops races with retire and can deliver a
    torn value. The delivered HANDLE holds no pin — it owns
    its bytes; the pin is transient, held only for the
    conversion. The remaining piece
    is the core seam, characterized exactly: plain-deref is
    pin -> read-root-value -> eve->clj -> unpin; flat delivery
    replaces the eve->clj step with deliver-flat, dispatched
    in mmap-deref beside region-deref/guard-deref, with the
    per-atom opt-out keeping plain-deref. Flipping that
    default is the plan's open question 1 (assumed default-on)
    and is a core-wide change — it lands as its own step,
    not folded into the flat build.
S4. **The assurances table.** Extend the perf suite (the one
    driver) with the heap battery of R7: flat heap object vs
    Clojure heap object vs in-atom slab read, same session, per
    corpus, plus footprint. This table is a release artifact —
    it goes in FLAT.md verbatim.
    S4 STATUS (Nov 13): the battery is built into the perf
    suite driver (--heap mode: per corpus, counts, random-
    access probes, full scan, hash, equality, one assoc,
    footprint bytes — heap vs delivered-flat vs slab, same
    session) and miniature-proven at tiny scale (every section
    executes; the records row prints all fields). The battery
    itself reconfirmed the S0 gap as a table entry: assoc on
    a delivered batch handle threw AbstractMethodError.
    FIXED (Nov 13): root cause was in the defseqhandle
    macro's JVM section — its clojure.lang.Associative
    implemented containsKey/entryAt but never assoc, so the
    method stayed abstract on every macro-generated vector
    handle (FlatVec, FlatColVec, FlatBatch). One line —
    (assoc [this k v] (assocN-flat this (long k) v)) — and
    delivered-handle assoc now works and stays flat: batch
    assoc lands the edit, the answer is still a FlatBatch,
    the original is untouched; vector handles likewise. The
    CANONICAL battery
    run is owed with the canonical perf rows; tiny-scale
    timings are cold-dominated and are not results.
S5. **Semantics + guidance docs.** FLAT.md gains the R8 account
    and the R12 user contract, written against the S4 numbers.
    S5 STATUS (Nov 13): the account is written as
    doc/flat/FLAT-IN-ATOM.md in this tree (FLAT.md itself
    lives in the landed tree; the section merges into it at
    landing). It states only facts with measurements or
    proofs behind them and defers the assurances table to
    the canonical heap-battery run, named as such.
S6. **EXPLORATION (post-v1, explicitly not blocking): heap-
    friendlier mutation.** Subtree-granular upgrade (thaw only
    the edited path's subtree), chunk-append assoc for the log
    shape, transients over flat. Driven by what S0/S4 measure;
    implementers of S1–S3 must keep the seams these need (the
    upgrade boundary is a single, findable place).

## Non-goals

- The image form as an atom resident (R2).
- General outbound pointers from flat values (R1, R10).
- Automatic compaction policy in v1 — freeze is a verb first;
  age/mutation-signal-driven automation is a later decision,
  taken with S4's numbers in hand.
- Making flat point reads beat the HAMT. The freeze rule stands:
  freeze what's scanned or shipped, never what's navigated or
  picked at.

## Open questions for John

1. Deref-delivers-flat: default-on for all atoms, or an opt-in
   per atom until the S4 table exists? (Plan assumes default-on
   with the R5 opt-out; flag it if you'd rather invert it.)
2. Freeze automation: verb-only in v1 (plan's assumption), or is
   even a crude age-based policy wanted in the first build?
3. Reference columns (R10): park, or spec alongside v1?
