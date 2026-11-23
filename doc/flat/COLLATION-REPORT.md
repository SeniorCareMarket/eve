# COLLATION REPORT — Flat Eve Wave 1 (+ contracts), collated tree

Branch `collate`, based on the research tip 7402ef9. Merge order per
IMPL-PLAN §4 (meeting points): W0, Z1, Z2, Z3, Z4+T1, C1, C2, C3, C4, X1,
L1, B1, H5; then the Sister chunk port; then collation items 1–4; then
the successor refresh (Z1 80a3d6f, Z3 0eb8308, T1 83b4e88) and one
collation repair (below). Wave 2 lanes collate in a second pass —
done in the final pass (below), which also carries the final Detach
refresh (ab90820) and the final Transfer refresh (14aefaa).

## Merges and conflicts

All 13 Wave 1 lanes merged. Conflicts were confined to:

- `doc/flat/SPIKE-LOG.md` — add/add in nearly every merge;
  union-resolved (every lane's section preserved; C1's B6/B7 section,
  initially dropped by a faulty line-membership check, restored
  line-for-line from C1's file and verified by section headings).
- `src/eve/flat/chunk.cljc` — X1 vs C1/H5: the port (below).
- `test/eve/flat/chunk_test.clj` — one 2-line conflict: C1's
  test.check arity fix `(gen/large-integer* {})` kept over H5's
  un-aritied form.
- `test/eve/flat/requirements.edn` — status flips merged cleanly.

Refresh merges (successor pass): Z1 80a3d6f, Z3 0eb8308, T1 83b4e88 —
SPIKE-LOG unions only; requirement flips rode along.

## Cross-lane fixes — which version won, and why

- **place.cljc — owner (Z3) won over Sister's snapshot.** Collated file
  differed from Z3's tip only by collation item 2. Z3's tip carries the
  CheckedBytes gate (normalize-image funnel) and the relocation-span pad
  fix; H5's copy predated both. Nothing of H5's was lost — its place
  needs were the gate itself.
- **transfer.cljc — owner (T1) won over Sister's snapshot.** T1's tip
  carries the same HAMT-scan extraction H5 made, plus the `(long …)`
  coercions in block-scan that H5's copy lacks.
- **image.cljc — Detach's tip, byte-identical** (hash-of overflow fix,
  HAMT inline-pointer base fix, unbounded-window bound, FlatSio state
  protocol, set-node layout). Verified by diff against Z1 80a3d6f: no
  differences.
- **chunk.cljc — a port, not a winner.** The collated file is X1's
  merged single implementation (2,774 lines; `chunk.cljs` does not
  exist) with three bodies of work ported into it form by form
  (CHUNK-PORT.md is the full record):
  - C1 post-pin: proxy-based map/set handles on :clj (APersistentMap /
    APersistentSet proxies — a deftype IPersistentMap fails
    `(= plain flat)`), inline hash/digest caches in seq handles,
    exact-greedy pack-all seal, handle meta via IAttachMeta;
  - Node's planner fix: `col-range` is the promoting subtraction
    `(-' mx mn)` on :clj, BigInt math on :cljs — verified present and
    untouched by the port;
  - Sister's integration: rep-converters, the type registry as
    `[tkey fp] → factory`, RecordSource/RowSource field sources,
    generated sisters answered by the reader when a factory is
    registered; plus the shape/type list sort fix both Sister and Node
    hit independently (`sort-by second` on `[key count]` pairs).
- **alloc-run! — one canonical answer shape** (collation item 2): the
  vector of slab-qualified offsets, nil = no run. Place's dead scalar
  branch removed; the scalar first-offset shape survives only at the
  `-sio-alloc-run!` protocol layer, converted by alloc-run! itself.
- **FORMAT-V1 prose — corrected to the producers** (item 1) in the four
  places Check found: table offsets range-checked, not 8-aligned;
  relocation owner/target are block-table ordinals; the column alignment
  pad sits inside the column; utf-8 offsets are u32 always.
- **F5 — NOT ADOPTED stands in the collated tree**: `*count-at-place*`
  flag OFF; counts stay walked (Count's clean interleaved probe: walked
  162–171 ms vs written 391–885 ms per 71k blocks).

## Collation repair (successor pass)

The first pass's J filing was partial (audit below) but its one run
exposed a genuine cross-lane break: **transfer's byte doors into place
were broken by Check's gate.** `place-image!` and `zip-root!` handed
place raw detach bytes; place's `normalize-image` refuses raw bytes
`:unchecked-bytes` — so every `eve.flat/transfer!` and every `:zip`
foreign-policy store died on the collated tree. Each lane's own suite
was blind to it: Zip's tests use the map/CheckedBytes doors, Transfer's
probes predate the gate's landing in its merge base. Repair (7f2645a):
both call sites vouch their self-produced bytes through
`eve.flat.check/open-checked`, the door the contract names.

That repair exposed one more boundary defect, in Check itself
(repair 5, 377902f): routing self-produced images through the full
checker showed its header range test refusing `typetab == length` —
the empty type table at the buffer's end, which the producers write
for every typeless value and place's parser reads as zero entries.
The collated B4 bench died on exactly that refusal for vec-100k
before the fix, and completes all four corpora after it. The range
test now refuses only offsets past the end; count-bearing tables keep
their own count checks. (Repairs 2–4 — the nil-probe class — are
recorded in the amendment judgment below.)

## Audit of the first pass's filings

`collate-tests.log` on J contains exactly one namespace's run
(sister-test: 6 tests / 37 assertions, 0 failures / 1 error — the
transfer byte-door break above) and no scoreboard log exists. The
STATUS claim "full test set + scoreboard filed" was a partial filing.
Both runs were re-filed against the repaired tip; results below.

## The contract amendment (15d3d3b) — judgment

**The amendment's intent is right; both its predicate and its subjects
were incomplete.** Four runs of requirements-test on J walked the
failure down:

1. Run 1 (7c46b04): `(detach nil)` throws an untyped
   IllegalArgumentException — `detach-image`'s 1-arity dispatches
   `-eve-sio`/`-root-header-off` on nil before the 2-arity's typed
   `{:type ::nil-root}` refusal can fire. → repair 2 (7887386): the
   nil guard now sits ahead of both dispatches.
2. Run 2: `(place nil nil)` NPEs inside `clojure.core/swap!`. Same
   class: place and transfer! dispatched on a nil atom ahead of their
   refusal vocabularies, and chunk-bytes' not-a-handle ex-info carried
   no vocabulary key at all. → repair 3 (9e1cd75): `:no-atom` refusals
   in place/transfer!, `:type ::not-a-handle` in chunk-bytes.
3. Run 3: place's now-correctly-typed refusal
   `{:eve.flat.place/check :no-atom}` was **rethrown by the amended
   probe itself** — `typed-refusal?` demanded a key whose namespace
   is exactly `"eve.flat"`, but `(namespace :eve.flat.place/check)`
   is `"eve.flat.place"`; transfer's `:eve.flat.transfer/refusal`
   has the same shape, and Check's refusals carry a bare `:check` key.
   The predicate recognized none of the three vocabularies its own
   comment names. → repair 4 (453273e): the test now accepts `:type`,
   `:check`, or any key in an `eve.flat.*` namespace.
4. Run 4 (453273e): **requirements-test green — 2 tests, 571
   assertions, 0 failures, 0 errors.** All seven verbs answer the
   nil-probe with a return, a not-built, or a typed refusal. Untyped
   host errors still fail the probe (runs 1–2 prove the probe's teeth:
   it caught real defects, it did not wave them through).

## Test set on the collated tree

Full set, run on J in three chained segments (each namespace its own
JVM invocation; a segment stops at its first red namespace). Tips:
segments 1–2 at 453273e (repairs 1–4), segment 3 at 377902f (repair 5;
the delta is check.cljc's boundary test, judged by check-test in
segment 3 and by the B4 bench).

| namespace         | tests | assertions | verdict                                                                                                                                                                                                               |
| ----------------- | ----- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| contract-test     | 3     | 33         | green                                                                                                                                                                                                                 |
| requirements-test | 2     | 571        | green (amendment + repairs 2–4)                                                                                                                                                                                       |
| image-test        | 7     | 208        | **2 failures** — image-read-speed: read ratios 152.7× and 33.1× vs the ≤1.2 gate. Detach's open Z1.7 wound; the collated image.cljc is byte-identical to Z1's tip, so this is the lane's state, not a merge artifact. |
| place-test        | 7     | 96         | green                                                                                                                                                                                                                 |
| alloc-run-test    | 6     | 32         | green                                                                                                                                                                                                                 |
| transfer-test     | 6     | 150        | **2 failures** — the Q2b writer-route control (below)                                                                                                                                                                 |
| build-test        | 4     | 354        | green                                                                                                                                                                                                                 |
| chunk-test        | 8     | 153        | green                                                                                                                                                                                                                 |
| column-test       | 5     | 9,459      | green                                                                                                                                                                                                                 |
| container-test    | 5     | 38         | green                                                                                                                                                                                                                 |
| check-test        | 5     | 73         | green                                                                                                                                                                                                                 |
| list-test         | 2     | 12         | green                                                                                                                                                                                                                 |
| sister-test       | 6     | 45         | green — boundary-conversion passes: repair 1 judged                                                                                                                                                                   |
| tally-count-test  | 5     | 83         | green                                                                                                                                                                                                                 |

The transfer failures, precisely: T1's Q2b control asserts that a
plain writer-route store (`swap!` of the same foreign value under the
default policy) censuses exactly as the placed image does. Measured:
the writer route holds **3,330 blocks** (free-count delta
[3214 3 109 1]) where the image carries **1,262** ([1214 4 43 1]) —
the conversion route allocates ~2.6× the blocks for this shape (the
path-copying garbage problem in miniature). Transfer's own arms all
pass (source untouched, census == reach, refusal paths, cross-process);
the assertion that fails is the _control's_ exactness, an open T1
residual (its finisher #6 is still working the lane), not a collation
break — collated transfer.cljc/image.cljc are the owner tips' bytes
plus repair 1's check-wrapping, which touches neither census.

**Resolved by the final Transfer refresh (below):** T1's declared
test judges the writer route against its _own_ census, and both
routes show the identical residual [2 1 0 0 0 0] — the committing
swap freeing the fresh atom's initial root, a bookkeeping fact about
the holder, not a route difference. The ~2.6× block-count difference
between the routes stands as measured; the exactness claim now holds
per-route. transfer-test on the collated tree: **6 tests, 150
assertions, 0 failures, 0 errors.**

## Scoreboard on the collated tree (B1–B8)

A fact about the instrument first: W0's `scoreboard.clj` measures only
the _today_ arms — its flat arm is hard-coded `"not built"` (the
lanes' own benches were always the flat-arm instruments). The board
was run on the collated tree anyway (tip 7887386, min of 5) to re-take
the baselines the gates compare against:

| corpus      | B2/B5 today swap! | B3 today @a (deref) | B4 today atom→atom (:copy, one run) |
| ----------- | ----------------- | ------------------- | ----------------------------------- |
| map 10k     | 542.89 ms         | 18.94 ms            | 637.60 ms                           |
| vector 100k | 401.35 ms         | 17.56 ms            | 484.07 ms                           |
| records 10k | 475.02 ms         | 22.19 ms            | 529.76 ms                           |
| messages 5k | 1,175.38 ms       | 48.09 ms            | 1,255.25 ms                         |

(B6 baselines: Transit MessagePack 59,235 / 368,549 / 358,526 /
874,085 B. B8 baselines: whole-value gzip 23,984 / 212,000 / 65,556 /
174,004 B.)

The flat arms, measured on the collated tree with the lanes'
instruments:

**B3 — detach vs @a (Detach's z1_board, tip 453273e, min of 5). Gate:
≥2× faster than @a on every corpus. MISSED on every corpus, by
~100–250×:**

| corpus      | @a       | detach       | ratio |
| ----------- | -------- | ------------ | ----- |
| map 10k     | 13.63 ms | 1,400.47 ms  | 0.01× |
| vector 100k | 92.47 ms | 23,425.94 ms | 0.00× |
| records 10k | 40.68 ms | 6,732.04 ms  | 0.01× |
| messages 5k | 89.64 ms | 16,212.26 ms | 0.01× |

Values verified equal on every row (no BOARD MISMATCH) — detach is
correct and catastrophically slow. This is Detach's Z1.7 wound
(image-test's image-read-speed failures are the same wound from the
read side). The final Detach refresh (see the final pass, below)
carried no walk changes, so this row stands as measured here; Z1.7
itself was re-judged there and stands open as Eve-core work.

**B4 — atom→atom (Transfer's b4_bench, tip 377902f, min of 5 after 2
warmups; zip-ok and tf-ok true on every corpus). Gates: ratio
transfer!/(detach+zip) ≤2× — PASSED on all four (0.92 / 0.96 / 1.00 /
1.08; the composition is free, as at T1's own pin). Absolute caps
≤5/26/9/26 ms — MISSED on all four, by ~250–1,900×:**

| corpus      | detach       | zip          | transfer!    | cap   |
| ----------- | ------------ | ------------ | ------------ | ----- |
| map 10k     | 481.74 ms    | 924.96 ms    | 1,287.41 ms  | 5 ms  |
| vector 100k | 21,907.34 ms | 29,110.73 ms | 49,114.47 ms | 26 ms |
| records 10k | 5,667.95 ms  | 8,452.57 ms  | 14,106.30 ms | 9 ms  |
| messages 5k | 13,652.31 ms | 20,435.57 ms | 36,957.08 ms | 26 ms |

The caps were set from the research probes' zip numbers (~2.5 ms for
a laid-out value); the landed walk and placement are nowhere near
them. The dominant cost sits in the same place as B3's: the walk.

**Remaining rows — lane instruments at lane pins (not re-run on the
collated tree; the collated suites above judge their correctness):**

- **B1 (zip alone, ≤50 ns/block):** MISSED — Zip's close-out measured
  15,275.1 ns/block on messages (~300×).
- **B2 (zip committed ≤2× B1):** the gate's deciding comparison is
  F5's, and F5 is NOT ADOPTED (walked counts stand): placement with
  walked counts measured 2.87× placement-alone on Count's full bench,
  3.37× with counts written. MISSED as a system; the mechanism that
  was to close it is off.
- **B5 (plain→atom via Builder):** map 0.11× (inside the 0.37–0.56×
  band's better side), records 0.60× (near-miss), messages 0.68×
  (miss), vector 1.61× (miss) — gate as written (band on every
  corpus) MISSED; the map win is real.
- **B6 (chunk bytes ≤0.9× Transit):** PASSED — messages 0.801×,
  records 0.455× (Chunk's bench, collated-form numbers in C1's
  close-out).
- **B7 (chunk reads):** decode MISSED (0.08×/0.09× of Transit vs
  ≥2.5× gate); Columns' scan 129.52 ns/row (gate ≤2) MISSED, filter
  100.54 (≤4) MISSED, cell 637.1 ns vs host 123.8 (≤2×) MISSED, row
  view 5.346 µs (≤1 µs) MISSED — while beating host maps at scan
  (1.6×) and filter (3.6×).
- **B8 (container ≤+5%):** MISSED — +24.36% messages, +25.24%
  records, confirmed on J; format-level (residuals, below).

## Final pass — Detach refresh, Wave 2 second pass, Transfer refresh

**Final Detach refresh (ab90820, declared).** Merged by path remote.
The declaration's delta over the already-merged 80a3d6f is the report,
the requirement flips, and a diag touch — no walk changes. The
collated `image.cljc` differs from the owner's tip by exactly one
hunk: collation repair 2 (the nil guard ahead of dispatch in
`detach-image`'s 1-arity), collation's own judged change; every other
byte is the owner's. image-test re-judged on the collated tree
(spot-check leg, tip 7d47715): 7 tests, 208 assertions, **2 failures
— both image-read-speed** (ratios 118.25× and 27.62× vs the ≤1.2
gate). Z1.7 stands exactly as Detach left it: mechanism named
(`hamt-get`'s resolved-node descent sits behind a concrete
`instance? JvmSlabCtx` gate; a foreign sio walks per-word), the fix
an Eve-core hook, not a merge artifact and not patched around here.
The B3 row was not re-benched in this pass — the refresh carried no
walk changes, so the row stands as measured at 453273e.

**Wave 2 second pass — Disk (16eaffd), Journal (9d87ae2), IO
(9d2695b), Hosts (fd3e09e) merged by path remote.** Conflicts and
winners:

- `requirements.edn` (Journal and Hosts merges): each side carried
  its own lane's flips against a stale base for the others — resolved
  as a union by owner (H1.1/H1.2, H2.1, H3.1, H8.1–H8.3 all `:built`;
  T1.1–T1.6 `:built` rode the Transfer merge; H4/H6/H7 stay
  `:planned`).
- `src/eve/flat/check.cljc` (IO merge, add/add): IO's copy is
  Check's tip arriving by a second route (its lane merged C4
  directly); the collated file carries collation repair 5 (the
  empty-table boundary, `(> off end)`) on top of the same bytes.
  **Collated file won** — the diff between the two sides is exactly
  repair 5, nothing of IO's was in its copy.
- Hosts' SPIKE-LOG/status files and the three lanes' code merged
  clean; no other conflicts.

**The Hosts list flip, applied as part of the Hosts merge**
(collation commit on the merged lists, per H3-STATUS's rebase call):
the eleven Wave 1 suites (image, alloc-run, place, tally-count,
transfer, build, column, container, check, list, sister) move from
`:excluded` to `:namespaces` for the JVM-class hosts (`jvm`, `cljr`,
`jolt`, `clje`, `glj`, and `bb` via its derived suite, which keeps
its own chunk-test exclusion). Hosts that still exclude them now
carry their measured host reasons instead of the landed one:
basilisp and jank get per-namespace "no namespace loads on this host
at this pin (measured)" entries; the five ClojureScript-class hosts
get "a JVM/bb suite; this host runs the Node-compiled chunk suite"
entries. The disk/journal/io exclusion reasons were corrected from
"has not landed in any tree yet" (false the moment this pass merged
them) to landed-in-the-collated-tree, suite-membership-unjudged.
`not-yet-landed` is renamed `not-in-any-suite` — it now holds only
disk, journal, io (landed, unjudged), dataset-batch (H6, in flight),
and chunk-leaf (H7, gated on fork F3). host-suite-test judges the
flipped lists green (below).

**Final Transfer refresh (14aefaa, declared).** Merged clean — no
conflict in `transfer.cljc`. Verified by diff against T1's tip: the
merged file is the owner's bytes **plus exactly collation's
repairs** (the `eve.flat.check` require, both byte doors vouching
through `check/open-checked` — repair 1, which the standing
instruction says wins — and repair 3's `:no-atom` nil guard in
`transfer!`). The Q2b rewrite rode the merge in `transfer_test.clj`.
Re-judged on the collated tree: **transfer-test fully green** (see
the resolution note in the test-set section).

**Suites on the collated tree after this pass** (J, tip 7d47715 =
Detach refresh + Wave 2 merges + the list flip + Transfer refresh;
logs collate-w2-suites.log and collate-w2-spotcheck.log, HEAD lines
verified):

| namespace         | tests | assertions | verdict                                               |
| ----------------- | ----- | ---------- | ----------------------------------------------------- |
| disk-test         | 2     | 7,462      | green                                                 |
| journal-test      | 1     | 10         | green                                                 |
| io-test           | 3     | 39         | green                                                 |
| host-suite-test   | 1     | 329        | green — the flipped lists judge                       |
| requirements-test | 2     | 590        | green (assertion count grew with the merged manifest) |
| contract-test     | 3     | 33         | green                                                 |
| image-test        | 7     | 208        | **2 failures** — image-read-speed only (Z1.7, above)  |
| sister-test       | 6     | 45         | green                                                 |
| transfer-test     | 6     | 150        | green                                                 |

With the successor pass's table, the collated test set now stands:
**every namespace green except image-test's Z1.7 pair.** No
scoreboard rows were re-run in this pass; the B1–B8 verdicts stand
as recorded above.

**A filing correction, banked:** this pass's first two J filings
produced no evidence and were discarded unread — both legs shared
one clone dir (`/tmp/colw2`) and raced (index-pack died in one,
leaving a corrupt clone whose missing deps.edn made the `:jvm-test`
alias "undeclared" and every invocation fail identically), and the
run strings' single trailing redirect captured only the last
namespace's output. Both defects were in the filings, not the tree:
the re-filed legs use distinct clone dirs and per-invocation
appending redirects, and their HEAD lines verify at 7d47715. (The
shared-clone-dir half of this is the same lesson Hosts banked from
its EXIT=128 races.)

**Pending inputs to the collated tree, final list:**

- **CLJS image (H4)** — lane in flight (successor #2 working; its
  Node probe + same-bytes reference are the current legs). Owner:
  the H4 lane. Lands as a third Wave 2 pass when it declares.
- **Dataset ↔ batch (H6)** — lane in flight (finisher #2's arity
  fix re-filed; suite verdict riding on its J leg). Owner: the H6
  lane. Same third pass.
- Nothing else is pending: Transfer's refresh is in (above), and
  the Leaf lane (H7) remains unstaffed on the operator's fork F3,
  not a collation input.

## Third pass — H4 (CLJS image) + H6 (Dataset), the last two Wave 2 lanes

**H4 merged at ccc6730** (its declared close-out). One conflict,
`requirements.edn`, resolved as a union by owner: H4.2 → `:built`
(its own flip), H4.1 stays `:planned` (both sides agreed), the
collated tree's H5 `:built` flips kept over H4's stale `:planned`
copies. The merge's `image.cljc` delta is H4's `:cljs` branches only
(Uint8Array allocation in detach, `grow-ints` doubled-copy, canonical
zero pads at emission); collation repair 2's nil guard verified
present in the merged file. H4's other code (process_slab.cljs run
claim, sio.cljc, flat.cljc delegates, the Node runner + suite)
arrived as the owner's bytes.

**H6 merged at 917a01c — its declared tip** (the declaration landed
during this pass, past the briefed minimum fdf07d3; the confirming
leg's verdict is recorded in H6's own declaration commit). Two
conflicts: `requirements.edn`, union by owner (H6.1–H6.3 `:built`;
the collated H4.2/H5 flips kept over H6's stale copies); and
`SPIKE-LOG.md`, resolved to the collated file on evidence — a
section-heading comparison shows H6's file is a strict subset (every
heading present in the collated file, its C1 bench section
byte-identical; the collated file adds five sections H6's base
predates: Z4 B2, Z3 bench, Z1 B3, B1 B5, C3 B8). Nothing of H6's was
dropped. As part of the pass, `host_suite.cljc`'s dataset-batch
reason was corrected from "has not landed in any tree yet" to
landed-at-this-pass (suite membership unjudged), the same correction
Disk/Journal/IO received in the final pass (commit ca967b8).

**Suites on the collated tree after this pass** (J, tip ca967b8;
logs collate-w3-jvm.log and collate-w3-node.log, HEAD lines
verified):

| namespace              | tests | assertions | verdict                                   |
| ---------------------- | ----- | ---------- | ----------------------------------------- |
| dataset-batch-test     | 3     | 29         | green                                     |
| requirements-test      | 2     | 594        | **1 error** — attributed below            |
| contract-test          | 3     | 33         | green                                     |
| host-suite-test        | 1     | 331        | green (count grew with the landed files)  |
| image-node-test (Node) | —     | —          | **dies at load** — cross-lane break below |

**The requirements error is the manifest's host blindness, not the
api-surface probe.** `requirements-covered` errors on exactly one
entry: ":H4.2: :built but eve.flat.image-node-test/run-wastes-nothing
does not resolve" — the JVM-side coverage probe resolving a
CLJS-only test namespace, which no JVM run can do. H4.2 is `:built`
on Node-judged evidence (below); the manifest and its probe have no
host dimension, so a CLJS-judged requirement errors the JVM probe.
This is stated, not smoothed: the collated requirements run stands
at 594 assertions, 0 failures, 1 error, with the error's cause
named. (For the record the brief asked about: the api-surface probe
itself is NOT this error — it passes on this tree under the amended
contract, as judged in the successor pass; the 1 error on H6's own
confirming leg, 535 assertions at its pre-amendment pin, was that
probe, and it does not recur here.) Dataset's own suite is green
outright, so H6 is judged landed on the collated tree.

**A genuine cross-lane break, found by this merge: the Node suite
dies at load on the collated tree.** At H4's own tip the suite runs
(4 tests, 31 assertions, 3 failures — all the H4.1 Dataset row — 0
errors; byte-identity arm green; run-wastes-nothing green). On the
collated tree, shadow compiles and Node dies during SHADOW_IMPORT of
`eve.dataset.js`: "Cannot read properties of undefined (reading
'sister')" at eve.dataset.js:408 — zero tests run. Mechanism,
re-derived from the trees: the collated `eve.deftype` macros emit,
in EVERY deftype expansion, a top-level
`(eve.flat.sister/register-sister! …)` call naming the fully
qualified var, with no accompanying require (macros.cljc, the
expansion's registration block). H4's tree predates the Sister
generation landing in the macros, so its Dataset expansion carries
no such call. On the JVM the emission is harmless — Clojure resolves
the var lazily and the suites' own requires load `eve.flat.sister`
first (every JVM suite above is green, Dataset construction
included). On CLJS's flat load graph, `eve.dataset` neither
requires nor can layering-wise require the flat layer, nothing in
the suite's load graph declares `eve.flat.sister`, and the
namespace object is undefined when Dataset's top level executes.
The difference between the trees is exactly the discriminator: same
`eve.dataset` source, same `eve.flat` API file, byte-identical —
only the deftype macros changed. No lane's version wins this: H4's
tree avoids it by lacking Sister generation entirely. It is banked
below as a named residual (Eve-core deftype macro × CLJS load graph;
the fix — a guarded registration, a declared load path, or moving
registration behind the flat layer's own load — is the Eve line's
to choose, not collation's). Consequence for this pass's verdicts:
H4.2's `:built` and the byte-identity arm stand on H4's judged
owner-tip evidence with their code merged as the owner's bytes;
re-judging them on the collated tree waits on the load fix. H4.1
stays `:planned` on its own residual (below), independent of this
break.

**Pending inputs to the collated tree: none.** Every Wave 1 and
Wave 2 lane is merged. What remains open is the residual list below
and fork F3 (Leaf — unstaffed, the operator's).

## Named residuals (not fixed here)

- **A16 / STM half-commit** — a two-ref dosync can half-commit when a
  ref's store throws mid-commit (Transfer re-derived: ra=1 installed,
  rb=0). Eve-level, in `eve.stm`'s commit protocol; the `:zip` policy
  path sidesteps the refusal that triggers it.
- **Z3.4 thrown-swap leak** — a swap attempt whose function throws leaks
  its claimed blocks for every writer (`swap-attempt!` has no catch
  around the swap fn). Atom-level Eve.
- **Kind-18 symbol-code limit** — Columns writes u16 symbol codes
  unchecked; truncates past 65,536 symbols in one chunk.
- **B8 container overhead** — +24.36% (messages) / +25.24% (records)
  against the ≤5% gate, confirmed on J. Mechanism: per-chunk
  symbol/shape tables reset every 64 KiB and deflate's window cannot
  reach the repeats. The fix is format-level (container-level shared
  table or a wider-window compressor) — above every lane, undecided.
- **Forks F2/F3 remain the operator's** (equality default —
  implemented as (a) on the flat side by Sister — chunk as leaf
  block). **F1 DECIDED (operator, Nov 12 2026): the family is named
  flat types, `eve.flat`** — renamed on branch `claude/flat-rename`,
  suite verdicts matching this tree namespace for namespace. The
  contract amendment 15d3d3b likewise awaits the operator's
  disposition.
- **Z1.7 image read speed** — image reads run 27–152× slower than
  slab reads against the ≤1.2 gate: `hamt-get`'s resolved-node
  descent sits behind a concrete `instance? JvmSlabCtx` gate, so a
  foreign sio walks per-word. Eve-core hook owed; named in Detach's
  report and the final pass above.
- **H4.1 CLJS deftype field reads (H4's residual, carried verbatim
  in substance)** — eve-deftype's CLJS instance binding hardcodes
  the module slab sio for field reads, so a Dataset's fields can't
  be read back through the deftype on CLJS even though the image
  bytes are proven correct field-by-field. Provenance (H4-REPORT
  §3.9, probe arms P-D/P-E): the detached image of a 2-column
  dataset carries 11 blocks / 10 relocations, and manual
  `slab-read-serialized` over the opened view decodes col-order
  `[:price :qty]` and the col-map with both EveArrays intact — the
  image is correct. But the constructed Dataset instance reads its
  col-map as {} and its columns as []: the compiled eve.dataset.js
  (lines 319–320) shows the generated serialized-field reads calling
  `slab_read_serialized(eve.process_slab.cljs_sio(), offset, …)` —
  the module slab, hardcoded — in place of the sio the instance was
  constructed over, per the documented assumption at
  serialize.cljc:122 ("On CLJS the sio is the module-slab token"),
  true until the flat view made a second sio on CLJS. The fix
  belongs to the Eve line: eve-deftype's CLJS constructors and field
  accessors must honor the passed sio, as the JVM's do. Same family
  as Z1.7 (core read machinery assuming the home slab). H4.1 stays
  `:planned` on this row alone.
- **Deftype sister registration breaks CLJS load for core deftypes**
  (third pass, above) — the macros emit an unguarded top-level
  `(eve.flat.sister/register-sister! …)` into every expansion;
  namespaces that don't (and layering-wise can't) require the flat
  layer — `eve.dataset` is the measured case — die at load on CLJS
  ("Cannot read properties of undefined (reading 'sister')",
  eve.dataset.js:408) while every JVM suite stays green. Eve-core
  deftype macro / Sister-generation integration; until it lands, the
  collated tree's Node image suite cannot run at all.
- **Requirements manifest has no host dimension** (third pass,
  above) — `requirements-covered` judges `:built` entries by JVM
  resolvability, so the CLJS-judged H4.2 errors the JVM run (594
  assertions, 0F/1E at ca967b8, the error fully attributed). A
  manifest/probe scoping question for whoever next touches the W0
  contract machinery; recorded, not repaired here.
