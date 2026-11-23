# C1 — the 64 KiB chunk: lane status

Branch `claude/static-c1-chunk`, base = research tip 7402ef9. JVM only for the gates.

## Step 0 — readings and decisions (before any code)

- FORMAT-V1 §2–4 + §5–§11 read; prototype `probes/pack.clj` read. The spec
  supersedes the prototype where they differ: 32-byte header (prototype: 28),
  children-before-parents with **absolute** chunk offsets (prototype:
  parent-first, node-relative offset tables), one offset width per chunk
  (u16, or u32 when the WIDE flag is set; prototype: per-node wide twins),
  SET = `[n][offset of a VEC or COL of keys]` (prototype: inline offset
  table), column kinds = §5 codes (prototype: private codes 1–8).
- Writer strategy for the width circularity (node sizes depend on the offset
  width, the width on the total size): write with u16; if the finished chunk
  passes 65,535 bytes, rewrite with u32. Node sizes depend only on the width,
  never on offset values, so the second pass is exact. Deterministic either way.
- Equality (§8): `hash` is a walk kept on the handle, built from
  `clojure.lang.Murmur3/hashOrdered|hashUnordered` over element `hasheq`
  (map entries as real `MapEntry`s over scalar-or-child-handle reps), so it
  agrees with the host's fold by construction. The content digest is SHA-256
  over Eve's flat bytes (`eve.digest`'s own definition), re-emitted by a walk
  over the packed nodes without building host collections.
- C1.4's decode counter: counts **full-chunk materializations** (the
  `unpack`/decode entry point). Protocol ops (`lookup`, `nth`, `count`,
  `seq`, `reduce`, `equiv`, `hash`, `print`) read nodes in place and never
  run it; decoding one scalar leaf on demand is a read, not a decode. The
  counter is exposed as `eve.static.chunk/full-decodes`.
- C1.5: `assoc`/`conj` append new nodes and a new root past the old chunk's
  length into a **grown copy** when the shape/symbol tables already cover the
  change (a vector conj always qualifies; a map assoc qualifies when the key
  is already in the map's shape). Otherwise (a new key needs a new shape) the
  update re-packs into a fresh chunk. Either way the answer is a new static
  and the old chunk's bytes are untouched, which is what the requirement
  states; the append path is the one the test's name describes.
- C1.7's image/slab arms: Z1 has not landed, so there is no image reader in
  the tree yet. The test covers packed ↔ plain both ways, packed ↔ packed
  across chunkings, and the packed digest against Eve's own digest of the
  plain value. The slab arm is added when Z1's `detach` exists to compare
  against; recorded here so the gap is named, not hidden.
- REF nodes (0x2C, cross-chunk) are C3's: the lone-chunk reader refuses one
  with a typed ex-info rather than guessing a directory it does not have.
- Type table: the writer emits an entry for every `IRecord` it packs (type
  key from the class name, `ns/Type`; fingerprint = first 8 bytes of SHA-256
  over field names and classes in basis order). A reader that has the key +
  fingerprint in `eve.static.chunk`'s registry answers a record handle;
  otherwise a generic record (TAGGED over the shaped map) that keeps every
  field and re-packs to the same bytes (C1.6).

## Board baselines (B6/B7, Transit arms, this session, before any impl)

Run at load1 ≈ 1.2–4 with `eve.static.chunk-bench` (min of 5, one warmup):

| Row       | Corpus         |    Transit MessagePack | Eve flat codec |
| --------- | -------------- | ---------------------: | -------------: |
| B6 bytes  | 5,000 messages |              874,085 B |    2,083,799 B |
| B6 bytes  | 10,000 records |              646,605 B |    1,872,016 B |
| B7 decode | 5,000 messages | 417.6 ms (83.5 µs/row) |              — |
| B7 decode | 10,000 records | 315.3 ms (31.5 µs/row) |              — |

Transit decode absolutes are far above the research session's (M4:
7.1 µs/row messages) — this box is shared by all twelve lanes right now.
The gates are ratios measured in one session (plan §3 rule 2), so the
packed arms are judged against these rows in the same conditions at the
end of the lane, and the absolutes are reported alongside.

## Log

- 01:2x ET — lane start: tree cloned, plan/format/probes read, decisions above.

## PIN FOR DOWNSTREAM LANES (2026-11-12 ~02:30 ET)

- `src/eve/static/chunk.cljc` compiles clean (`(require 'eve.static.chunk)` => loaded)
  and is committed on branch `claude/static-c1-chunk` as the C1 pin for
  C2/C3/C4/X1. Header/contract surface (FORMAT-V1 §2: 32-byte header, "EVS1",
  kind 0 packed, u16 offsets / WIDE u32, root last, children before parents)
  is stable from this commit; later commits may revise internals and fix test
  failures but not the header layout without a STATUS note here first.
- Known state at the pin: implementation complete per FORMAT-V1 §2–6/§8;
  requirement tests written but NOT yet run green (test run follows this
  commit). Type-table byte layout is lane-defined (spec silent, §6) — see
  "Design decisions" above.
- Push: none (no GitHub credential on this box; coordinator collates locally
  from /home/hatch/workspace/static-eve/c1).

## Update 2026-11-12 ~03:40 ET — equality gate found and routed

- Re-derived firsthand why (= plain static) failed for maps/sets while
  vectors/lists passed: APersistentMap/APersistentSet.equiv dispatch on
  the other side's RUNTIME CLASS (a minimal deftype IPersistentMap fails
  (.equiv {:a 1} it) and (.equals ...) alike unless it also implements
  java.util.Map; equiv wants the abstract class itself). gen-class
  cannot fix it here — its classes do not materialize in a
  source-loaded namespace (verified in isolation). defrecord cannot
  override its field-map behavior (duplicate-interface on any core
  section). ROUTE TAKEN: the four map handles (StaticMap, StaticRecord,
  StaticTagged, StaticRow) and StaticSet are now PROXY classes over
  APersistentMap/APersistentSet closing over the node position, with a
  2-slot cache array (hash, digest). Sequential handles stay deftype
  (their equiv gates accept the interfaces). Test-var names in
  requirements.edn are unaffected.
- Golden fixture {:a 1} corrected: my hand-computed hex had nsym/nshape
  zeroed; the writer's 1/1 is right per FORMAT-V1 §2.
- pack-all rewritten exact-greedy (the estimate version was quadratic
  and could sail past the target).
- chunk-bytes now also accepts raw chunk bytes (pack-all's return).

## Update 2026-11-12 ~04:20 ET — equality domain green; meta routed via protocol; pin 921fb7a

- Suite progression (same scoped JVM runner): 148/157 → 152/153 →
  pending final run at 921fb7a. Remaining clusters fixed in 921fb7a:
  - node-equiv rewritten: digest-first on BOTH sides, leaf compare by
    Eve bytes (equiv-any), so the A10 float edges follow the digest in
    every static-driven direction. C1.7 green except the meta item.
  - typed-rep recorded a record's fields twice (keywords + symbols);
    now one enumeration, keyword form, basis order. C1.6 green:
    generic record (IGenericRecord marker), fields read, re-pack
    byte-identical.
  - test.check gen/list and gen/set 3-arity forms do not exist in
    1.1.1; generators rewritten over gen/vector + fmap.
- FLOAT EDGES, grounded (A10): vanilla Clojure measured in this tree's
  JVM — (= {:a ##NaN} {:a ##NaN}) is FALSE, (= {:a 0.0} {:a -0.0}) is
  TRUE. Plain-driven = is computed by clojure.lang over decoded
  Doubles; no static-side code can move it. The test asserts the edges
  in static-driven directions and by content digest directly, with
  the reason recorded in the test. This is A10's own recorded
  disagreement between Clojure's = and Eve's digest equality.
- META on proxy handles, measured: under clojure.main source loading,
  (instance? clojure.lang.IObj static-map) is false, with-meta throws
  ClassCastException, (meta static-map) reads APersistentMap's own
  meta field (always nil — the generated proxy class carries no
  meta/withMeta method), and the chunk protocol interfaces land in
  distinct DynamicClassLoaders. ROUTE: chunk never touches IObj for
  handle meta — read-value attaches via IAttachMeta/-attach-meta and
  unpack reads via -hmeta (both live on the proxy classes, verified
  in .getMethods). unpack also re-applies handle meta to the plain
  materialized value. User-side (with-meta static-map m) remains
  subject to the loader world; (meta h) on a map/set handle under
  source loading returns nil — recorded as a divergence note for the
  report; pack/unpack preserve metadata through the chunk itself.
- Wave 0 is COMPLETE on its branch (36fc651). Merge into
  claude/static-c1-chunk + requirements registration is the next step
  after the green run at 921fb7a; bench (B6/B7 packed arms) after that.
- Pin for downstream lanes: 921fb7a (chunk.cljc coherent; one suite
  item — map metadata — fixed in this commit, verification running).

## Correction 2026-11-12 ~04:35 ET

- The verification run at 921fb7a FAILED TO COMPILE: my -hmeta edit
  in defseqhandle carried an extra close paren that ended the deftype
  early ("Unable to resolve symbol: this#", chunk.cljc:1312). Fixed in
  the commit after 921fb7a. The suite verdict at the current pin is
  STILL OWED — re-run waits on the load gate (load1 ~30 at this
  writing). No green claim stands until that run lands.

## Successor session 2026-11-12 ~05:20 ET — merged suite run; one NPE fixed; one cross-lane item

- W0 (complete, 36fc651) is merged (961f67e); all seven C1 requirements
  are registered in requirements.edn. Scoped suite = chunk-test +
  contract-test + requirements-test, run via the c1-cp.sh classpath.
- RUN 1 at 961f67e+bench-commit (load window, load1 6.17 at start):
  13 tests, 706 assertions — 705 pass, 0 failures, 1 error. All seven
  C1 requirement tests green; contract green. The one error:
  requirements-test/api-surface (W0.1's probe) — `(eve.static/open nil)`
  reached the landed chunk/open and NPE'd at `(alength bs)` in
  header-ctx, before the typed ::short refusal could fire.
- FIX (f243c1d, this lane's file): header-ctx reads the length of
  `(or bs (byte-array 0))`, so nil bytes get the same typed
  {:type ::short} refusal as short bytes. No untyped crash remains on
  that path.
- CROSS-LANE ITEM for the coordinator (not fixable inside C1's files,
  named here so it is not hidden): api-surface's nil-probe accepts only
  two outcomes from a verb — return normally, or throw the
  :eve.static/not-built ex-info. A LANDED verb that refuses nil with a
  typed ex-info (open's ::short, per FORMAT-V1 §10's refuse-by-name
  style) is rethrown by the probe and errors. Every landing lane hits
  this (place/transfer!/detach will too, the day they land): the
  probe's outcome set predates any landing. Proposed amendment (W0's
  file, coordinator's call): accept an ExceptionInfo whose ex-data
  carries :type as a third outcome, ::refused. Until then the merged
  suite carries exactly this one attributed error. Related: W0's
  surface doc names eve.static.chunk/bytes as C1's delegate target
  for `bytes`; chunk.cljc provides chunk-bytes/bytes-of instead.
  Adding a `bytes` alias interacts with the same probe (a landed
  `bytes` called on nil must refuse typed, which the probe also
  rejects today) — reconcile the two together, not separately.
- Bench extended (48c6401): chunk_bench now also measures the B7 read
  arms on records (scan/filter/cell/row view, M4's definitions, host
  arms in the same session). B6/B7 run follows the green suite run.
- RUN 2 at f243c1d (load window, load1 6.48 at start): identical totals
  — 13 tests, 706 assertions, 705 pass, 0 failures, 1 error — and the
  error is now exactly the predicted one: api-surface rethrowing
  chunk/open's typed {:type :eve.static.chunk/short} refusal of nil.
  The untyped crash is gone; the residual is the cross-lane item above,
  not a C1 defect. C1.1–C1.7 flipped to :built (1f0e049) on these runs.

## Venue shift 2026-11-12 ~05:34 ET (operator directive 05:19 ET)

- NO heavy runs on hatch from 05:19 ET, at any load. The local B6/B7
  bench JVM that was mid-run when the order arrived was KILLED;
  no local bench numbers exist and none will be used.
- Both owed legs are filed with the job agent (lane static-eve/chunk,
  venue_pref j, class heavy), one JSON per leg in
  ~/workspace/job-agent/inbox/:
  - static-eve-c1-suite.json (leash 1800 s) → ~/static-eve-results/c1-suite.log on J
  - static-eve-c1-bench.json (leash 2400 s) → ~/static-eve-results/c1-bench.log on J
    Each run clones ~/static-eve-bundles/c1.bundle on J, checks out
    claude/static-c1-chunk, echoes EXPECTED/ACTUAL tip, builds the
    classpath from the tree + ~/m2repo jars, and runs the leg.
- **BUNDLE REFRESH NEEDED on J**: J's staged c1.bundle is STALE at
  a58f383 (verified firsthand by ssh 05:40 ET — it predates the whole
  921fb7a patch batch). The current bundle is refreshed locally at
  hatch:~/workspace/static-eve/bundles/c1.bundle, tip 1f0e049 —
  re-stage that file to J's ~/static-eve-bundles/c1.bundle before the
  filed legs run, or they will test the wrong tree (both job files
  say so in needs/bindings and print both tips in their logs).
- J environment verified firsthand by ssh (light probe, 05:40 ET):
  JDK 21.0.9, clojure CLI present, ~/static-eve-results exists, and
  all nine jars the legs' classpath names are present in ~/m2repo.
- Filing correction (~05:50 ET): the router HELD both filings —
  "binding not drawn from (1,2,3,4)" — because the bindings array
  carried a prose string; the router wants the definition numbers.
  Both files were re-filed in inbox/ with "bindings": [1] (the prose
  explanation stays in needs). Held originals remain in
  ~/workspace/job-agent/jobs/held-static-eve-c1-{suite,bench}.json as
  the audit trail; the live filings are the re-filed ones.
- C1-REPORT.md is drafted in this tree around the filed legs: the
  C1.1–C1.7 verdicts rest on the local scoped runs above; the SPIKE-LOG
  B6/B7 section and the report's final form wait on the J bench log,
  which the coordinator reads back from J.

## Close-out session 2026-11-12 ~06:13 ET — earlier J records void; legs re-filed

- The re-filed suite/bench jobs from ~05:50 ET are WORTHLESS as
  evidence and are treated as never run: the router records them
  DONE EXIT=0, but the coordinator's firsthand search found their
  j-queue logs are 0 bytes and no c1-suite.log / c1-bench.log exists
  anywhere on J. A DONE record with no log is not a result. They also
  predated the bundle refresh; J's staged c1.bundle is now at
  acee946 (code 1f0e049), refreshed by the coordinator.
- Both legs re-filed in the proven bash -c form (lane static-eve/chunk,
  bindings [1] with the bundle fact in needs, class heavy, leash
  3600 s, venue_pref j):
  - inbox/20261112-static-eve-c1-suite-confirm.json → c1-suite.log on J
    (chunk-test, contract-test, requirements-test; one -n per
    invocation, &&-chained; the requirements run's single api-surface
    error is expected and attributed — see the run-2 note above).
  - inbox/20261112-static-eve-c1-bench-confirm.json → c1-bench.log on J
    (eve.static.chunk-bench; test tree appended via -Sdeps extra-paths,
    transit-clj 1.0.333 supplied via -Sdeps :deps — never a replacing
    :paths).
    Each log's first line is TIP=<full hash>; the close-out verdict
    requires it to read acee946b0f75f20c2b1f8a77f878ef14c2bdd341.
- Owed on delivery of the two logs: B6/B7 rows to SPIKE-LOG.md with
  gates reported exactly as measured, C1-REPORT.md finalized (the
  owed B6/B7 section replaced), and the completion declaration. If a
  leg again produces no log, that is stated here plainly and the leg
  is re-filed once, simplified.

## Close-out 2026-11-12 ~06:2x ET — bench landed; suite confirmation outstanding

- c1-bench.log DELIVERED, TIP line verified = acee946 (the code tip's
  tree). B6/B7 rows are in SPIKE-LOG and C1-REPORT is finalized with
  them: B6 GATE PASS both corpora (messages packed 699,824 B =
  0.801× Transit MessagePack; records 294,016 B = 0.455×); B7 decode
  GATE MISS both corpora (packed full decode 0.08× / 0.09× Transit —
  ~11–12× slower; reported as missed). The bench's decode arm is
  full materialization; the scan/filter/cell/row-view sub-gates are
  the Columns lane's rows, not C1's.
- OUTSTANDING, exactly one item: c1-suite.log (the scoped suite
  confirmation on J) has not landed yet. The requirement verdicts
  rest on the lane's two local scoped runs (13 tests, 706
  assertions, 705 pass, 1 attributed W0 api-surface error). On its
  delivery the verdict is appended here; the lane is otherwise
  complete.

## Finalizer 2026-11-12 — suite confirmation landed; lane fully complete

- c1-suite.log DELIVERED, TIP line verified = acee946 (the code
  tip's tree). Scoped suite on J, exactly as the close-out
  predicted: chunk-test 8 tests / 153 assertions, 0 failures,
  0 errors; contract-test 3 tests / 33 assertions, 0 failures,
  0 errors; requirements-test 2 tests / 527 assertions, 0 failures,
  1 error — the single error is the attributed W0 api-surface
  nil-probe item (chunk/open's typed `{:type :eve.static.chunk/short}`
  refusal where the W0 probe accepts only return or
  `:eve.static/not-built`; the same wall Sister and Detach document,
  collation's contract amendment). SUITE-EXIT=1 follows from that
  one attributed error and nothing else.
- The J run confirms the local scoped verdicts on the venue. The
  outstanding item is CLOSED: lane C1 is fully complete, no
  outstanding items. Totals across the three namespaces: 13 tests,
  713 assertions, 0 failures, 1 attributed error.
