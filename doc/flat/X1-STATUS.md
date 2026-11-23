# Lane X1 — the same bytes on a second host · STATUS

Branch `claude/static-x1-node` (local only — this box has no GitHub credential
for SeniorCareMarket/eve; the coordinator collates lane branches from the
trees under `/home/hatch/workspace/static-eve/`). Cross-lane code arrives by
path remote: `w0` = ../w0 (`claude/static-w0-contracts`), `c1` = ../c1
(`claude/static-c1-chunk`). All times US Eastern.

## Pins

| what                 | pin                                                                                                            |
| -------------------- | -------------------------------------------------------------------------------------------------------------- |
| research base        | `7402ef9` (`claude/sweet-carson-6pt29d`)                                                                       |
| W0 contracts merged  | w0 `cfa86c6` (requirements.edn, `eve.static`, contract, corpora, scoreboard)                                   |
| C1 byte-identity pin | **`9ea620b`** (merged at `55a0b76`) — "C1 chunk implementation (compiles; tests not yet run) — downstream pin" |
| tools                | Node v24.22.1, OpenJDK (box), clojure/bb in `/home/hatch/.local/bin`                                           |

## What X1 must prove (IMPL-PLAN §3.3, lane X1)

- X1.1 `eve.static.chunk-node-test/golden-bytes` — C1's golden fixtures
  (`test/eve/fixtures/static-bytes.edn`) encode and decode on Node.
- X1.2 `jvm-node-same-bytes` — one value packed on the JVM and on Node is
  the same bytes (settles A14 for the format).
- X1.3 `node-numeric-edges` — i64 columns exact through `BigInt64Array`;
  −0.0 and integral doubles kept in typed (f64) columns.
- X1.4 `node-alignment` — a chunk viewed through typed arrays starts
  8-aligned.

## Design decisions (this lane)

- **Node route** follows the tree's own small-graph precedent
  (`merkle-phase5`): a dedicated shadow build `:static-x1-test`
  (`:node-script`, main `eve.runner.static-x1-main/main`, output
  `target/static-x1-test/main.js`), compiled with
  `clojure -M:node-test -m shadow.cljs.devtools.cli compile static-x1-test`
  and run with `node target/static-x1-test/main.js` from the repo root.
  The packed chunk is host-heap bytes only, so the runner needs no slab /
  native-addon init (unlike the merkle runner).
- **Scalars**: C1's JVM writer delegates scalar bytes to
  `eve.codec/value->eve-bytes` and strips the 2 magic bytes. The JS codec
  has no flat form (`value->eve-bytes` throws in
  `adapters/codec/js/src/eve/codec.cljs`), but it has the same machinery
  under `eve.codec/serialize-flat-element` (flat-collection writer:
  "the same bytes on every host"). The CLJS `scalar-body` therefore
  mirrors C1 exactly, sourced from `serialize-flat-element`, and decodes
  through `eve.codec/decode` like C1's `scalar-at`. Byte identity for
  scalars is then Eve's existing cross-host contract (the eve-bytes
  fixtures), not a new one.
- **The CLJS writer is a mirror, not a port of internals**: same scan
  (symbol counts → codes by descending use, ties by string; shapes;
  types), same column plans (kinds 6/9/10/16/17/18/19/20/22/23 and the
  run-end rule), same node order (children before parents, root last),
  over a `Uint8Array` builder with a `DataView`. Where the mirror cannot
  decide identically it must refuse, not diverge silently.
- **Column handoff on CLJS**: `eve.static.chunk/column-view` (CLJS side)
  answers the dense typed array over the chunk's own buffer — zero copy —
  `BigInt64Array` for kind 10, `Float64Array` for kind 9, etc. Kinds with
  no dense JS array (narrowed 22, run-end 23, utf-8 19, any 20, struct 21,
  bits 16) answer a typed refusal. This is the Node form of the C2.5
  zero-copy handoff, at chunk scope.
- **Alignment**: the CLJS `pack` returns chunk bytes in a fresh buffer, so
  the chunk starts at byte offset 0; columns are aligned from the chunk's
  start by the writer's `align!`, so typed views are element-aligned
  (X1.4 asserts both offsets).

## Decisions added since lane start

- **Development vehicle**: until C1's `chunk.cljc` exists as a commit, the
  CLJS implementation lives in `src/eve/static/chunk.cljs` (ns
  `eve.static.chunk`). At the C1 merge it folds into `chunk.cljc` as the
  `:cljs` side (reader conditionals, C1's JVM forms untouched) and the
  `.cljs` file is deleted in the same commit.
- **Scalar tag set verified firsthand**: the JS codec's `FAST_TAG_*`
  constants (`src/eve/deftype_proto/serialize.cljc`) are exactly the tag
  table of C1's `scalar-len` (0x03 i32, 0x04 f64, 0x0F i64, 0x0B uuid,
  0x14 char, …), and `serialize-flat-element` emits `[0xEE 0xDB tag
  body…]` for scalars — so the strip-magic mirror of C1's `scalar-body`
  is byte-faithful by construction, and `eve.codec/decode` (=
  `deserialize-element`) reads the re-magicked body back. Tag `0x15` is
  unassigned in the inline set — it is the slab-reference tag; C1's
  refusal of a `0x15` scalar body is mirrored.
- **No type table on CLJS in v1**: records / `eve-deftype` values pack as
  their field maps (the generated sisters are Wave 2 lane H5). JVM typed
  chunks still _read_ on Node — an unregistered type opens as a generic
  tagged handle; `eve.static.chunk/type-registry` +
  `register-type!` are the seam H5 fills. Byte-identical re-pack of
  typed values is not claimed on CLJS.
- **i64 host rendering**: `col-at` for kind 10/22 answers a JS number
  while the value is a safe integer, a `js/BigInt` past that — Eve's
  CLJS number model. Through `column-view` (BigInt64Array) values are
  always exact. Consequence to name, not hide: a column of _only_
  integral doubles plans as an integer column on CLJS (3.0 is the
  integer 3 in this host's number model, exactly as the scalar codec
  writes it), where the JVM plans f64 for `Double` objects; X1.3's
  corpus carries 1.5 so the column is f64 on both hosts, and −0.0 is
  asserted inside that f64 column.
- **Handle equiv/hash on CLJS walk the node** (`decode-all`, counted by
  `full-decodes`); C1's digest-first short-circuit has no CLJS
  counterpart yet because the JS codec carries no flat form to fold.
  Correctness equal, speed not claimed.
- **Finding (C1's, to report)**: the scan's symtab tie-break — descending
  total uses, ties by `(str sym)` — is not total: a symbol `a` and the
  string `"a"` share `(str …)`; with equal use counts their symtab
  order falls back to hash-map iteration order, which is not
  host-determined. Corpus avoids the pathological tie; the fix belongs
  to C1 (tie-break by scalar/key bytes). Similarly, shape-registration
  order follows rep-map traversal, which can differ between a JVM
  persistent map and a CLJS one for sibling subtrees with distinct
  shapes — X1.2's reference corpus is the probe; a divergence there is
  a FORMAT-V1 scan-canonicalization finding, not something this lane
  works around silently.
- **`eve.static/bytes` note**: W0's `src/eve/static.cljc` delegates
  `bytes` to `eve.static.chunk/bytes`; the CLJS side defines `bytes`
  (alias of `chunk-bytes`) so the delegation resolves on Node once
  W0's own `:cljs` delegate stub is lifted. X1's tests call
  `eve.static.chunk` directly.

## Log

- 01:5x ET — lane start: tree cloned from the research tip, plan/format/
  assumptions read. C1 and W0 not yet on any remote; both found as local
  trees. W0 contracts merged (above). C1's writer read firsthand in its
  working tree (uncommitted): API is `pack`/`pack-all`/`open`/
  `open-checked`/`chunk-bytes`/`bytes-of`; C1.1's fixtures are committed
  there (`ff30e92`) but the implementation is not — the cljs side waits
  for the C1 commit to merge against.
- Tests-first commit `986895f`: the four X1 tests (red — `eve.static.chunk`
  does not exist in this tree yet), the runner main, the shadow build.
- Implementation commit `709b389`: `src/eve/static/chunk.cljs` complete —
  writer mirror (scan, column plans, node writer, symtab/shapetab),
  reader (header door, column parser, node walks), handles answering
  the host collection protocols, `column-view`. ~1,410 lines.
- GATES, both open at this writing: (1) box load — load1 has read
  10–35 all lane; the shadow compile is a heavy run and has not started
  (rule: never above 8). First compile + probes are the next act when
  load allows. (2) C1's commit — still uncommitted in ../c1; the merge,
  the transplant into `chunk.cljc`, the JVM reference corpus
  (`batch-numerics` for X1.2/X1.3) and the byte-identity pin all wait
  on it.

## Successor (2026-11-12, after the C1 pin landed)

- C1 merged at `55a0b76`; the byte-identity pin is `9ea620b`.
- **Directive now in force (operator, 2026-11-12): cljc-first.** The
  chunk is ONE implementation in `chunk.cljc`; `chunk.cljs` is a
  development vehicle and is deleted by the transplant. Host
  differences (typed arrays, BigInt, DataView) live in `:cljs`
  branches; where the two texts express the same algorithm C1's
  structure wins; a genuine logic divergence is recorded below as a
  finding for C1, not papered over.
- **Finding F-D (C1 test defect, fixed here):** at the pin,
  `eve.static.chunk-test` cannot load — `gen/large-integer*` is a
  _function_ in test.check 1.1.1 and is passed bare into `gen/one-of`
  (chunk_test.clj:99), so the suite throws during namespace load.
  C1's "tests not yet run" is literal. Fixed minimally in `680c9f1`
  (`(gen/large-integer* {})`); C1's implementation untouched.
- **Finding F-A (logic divergence, sort fallback):**
  `sort-by-key-bytes` — C1 sorts keyed and unkeyed reps in one byte
  space (an unkeyed rep's fallback is the UTF-8 of its printed form,
  compared directly against key bytes). The CLJS vehicle prefixed a
  flag byte (0 = keyed, 1 = fallback), sorting every keyed rep before
  every unkeyed one. The orders differ whenever an unkeyed rep (a
  byte array inside a collection key) sorts among keyed ones, so the
  packed bytes differ. The desk-review note in `db38bf6` says the
  flag scheme was taken from C1's then-uncommitted working tree; the
  committed pin does not have it. The merged file follows the pin;
  which scheme FORMAT-V1 intends is C1's call.
- **Finding F-B (logic divergence, column narrowing):**
  `column-plan` — the vehicle narrows to kind 22 width 4 whenever
  n ≥ 16 and the value range fits u32. C1 reaches width 4 only inside
  the `(<= Integer/MIN_VALUE mn)` branch: with mn below INT32_MIN,
  n ≥ 16 and 65535 < range ≤ 2³²−1, C1 plans kind 10 where the
  vehicle planned kind 22/4 — different bytes for such a column.
  The merged file follows C1's decision tree exactly.
- **Suspect F-C (being re-derived):** `column-plan`'s range,
  `(- mx mn)` over Clojure longs, overflows for a full-range i64
  column — X1.3's own corpus has mn = Long/MIN_VALUE and
  mx = 2⁵³+1, so C1's writer may throw on the very batch the JVM
  reference must be generated from. Probe result lands here.
- **Finding F-E (C1 implementation defect, repaired here):**
  re-derived at the pin — `(pack {:a 1})` throws
  `ClassCastException` in `scan` (chunk.cljc:302): the shapes/types
  assembly maps HashMap entries to plain `[k v]` vectors and then
  calls `key`/`val` on them (those require `Map$Entry`), and the
  type-list also read the ordinal as the fingerprint. Any value with
  a shaped map or a record crashed the writer; fixture rows 1–2 (no
  shapes) pass. The CLJS vehicle's scan destructures entries
  directly — the working reference for the intended semantics.
  Repaired minimally in the merge (sort the entries themselves;
  `:key`/`:fp` from the entry's key), C1's structure kept.
- **Finding F-C (confirmed):** the probe at the pin threw
  `ArithmeticException: long overflow` packing X1.3's batch —
  `column-plan`'s `(- mx mn)` overflows when an i64 column spans
  more than a long (mn = Long/MIN_VALUE, mx = 2⁵³+1). C1's writer
  cannot pack a full-range i64 column at all. In the merged
  `column-plan` the range is computed with promotion
  (`-'` on the JVM; BigInt subtraction on CLJS never overflows) —
  the only behavioral change, and it turns a throw into the plan
  C1's own decision tree prescribes.
- **Fixture analysis (F-F, confirmed and corrected):**
  fixture row 3 (`{:a 1}`) parses byte-perfectly under FORMAT-V1
  §2–4 and C1's layout — symtab offsets [0, 3], entry `07 01 61`,
  shape entry `01 00 00`, i32 node at 46, MAP root at 51 → 46 —
  except header bytes 16–19 were `00 00 00 00`: nsym/nshape read 0
  for tables that demonstrably hold one entry each. The probe
  diffed the JVM writer's bytes against the fixture: they differed
  at exactly hex positions 33 and 37 (= header bytes 16 and 18);
  rows 1–2 encode-matched the writer in the same probe. Fixture
  row corrected to the writer's bytes (nsym 1, nshape 1) and
  committed. In the merged tree the JVM writer again packs
  `{:a 1}` to 56 bytes — the corrected fixture's length.

## Successor, round 2 (2026-11-12): the transplant

- **Transplant committed (`1263ee5`):** `chunk.cljs` is DELETED;
  `src/eve/static/chunk.cljc` (2,656 lines) is the only chunk
  implementation. C1's file layout and structure win throughout;
  the vehicle's logic survives only as `:cljs` branches —
  DataView/typed-array byte primitives, BigInt i64 helpers, the JS
  codec's flat-element scalar body, the volatile-map scan, a CLJS
  `Ctx` carrying a DataView, decode-all equiv/hash (counted on the
  context's decode counter exactly as C1 counts full decodes), the
  nine protocol handles in C1's field shapes
  (`[ctx off mm _h _d]`, rows `[ctx off row mm _h _d]`, tagged/record
  `[ctx off mm _h _d tkey fp]`) routing edits to C1's shared update
  path (`cons-static`/`assocN-static`/`pop-static`/`assoc-static`/
  `dissoc-static`/`disjoin-static`), and `column-view` (CLJS-only).
  Handle constructors in C1's shared `read-value`/`row-handle` are
  called verbatim on both hosts. `IRecordInfo` is defined per host
  so `record-tag`/`generic-record?` stay shared; `content-digest`,
  `materialize`/`unpack`, the Murmur3/flat-emit digest machinery and
  the JVM handle macros stay `:clj`-only. A shared `bytes` alias of
  `chunk-bytes` was added — W0's `eve.static/bytes` delegates to
  `eve.static.chunk/bytes`, which the pin did not define (this was
  the predecessor's stub finding; the delegate now has a target).
  Its load-time warning (`bytes` replaces the `clojure.core/bytes`
  refer on the JVM) is the accepted cost of the API name W0 chose.
- **Reader check:** bb read pass over the merged file — 112 forms
  under `:clj`, 119 under `:cljs`, no imbalance.
- **JVM check:** the merged namespace LOADS on the JVM
  (Temurin 21, direct classpath, `-Xmx640m`) and
  `(pack {:a 1})` answers 56 bytes, matching the corrected fixture.
  The full `eve.static.chunk-test` run has twice reached
  "Testing eve.static.chunk-test" and then been OOM-killed by the
  box (memory exhausted by concurrent lanes; not a test failure —
  no FAIL/ERROR was ever printed). Suite-green is still owed when
  the box frees up; the Node run and the JVM reference hex wait on
  the same gate (load1 read 42 at last check; the rule is ≤ 8).

## Successor, round 3 (2026-11-12): reference + first Node run

- **JVM reference banked (`bc8b2a2`):** `batch-numerics` packed by the
  merged writer at the C1 pin `9ea620b` → 120 reference bytes inserted
  as `jvm-reference` in `chunk_node_test.cljs`.
- **First Node run fixes (`9a9a6cb`):** `bigint?` now goes through
  `js*` typeof (`js/typeof` is not a var; it threw); the CLJS `-seq`
  impls (seq-wrap/seq-of-node, node-seq-entries, StaticSet/Batch/Row)
  answer nil for empty nodes — an empty LazySeq had walked one
  phantom nil through map/reduce.

## Successor, round 4 (2026-11-12): Node green; JVM run in a waiter

- **Node suite GREEN in this session's own run:** built artifact
  `target/static-x1-test/main.js` (compiled 10:07 UTC, after
  `9a9a6cb` at 09:23 UTC; tree clean at that tip) run with
  `node target/static-x1-test/main.js` at load1 ≈ 28 (the node run
  is a light process; the heavy shadow compile was the predecessor's):
  **4 tests, 33 assertions, 0 failures, 0 errors.** X1.1
  golden-bytes, X1.2 jvm-node-same-bytes (against the `bc8b2a2`
  JVM reference), X1.3 node-numeric-edges, X1.4 node-alignment all
  pass.
- **JVM `eve.static.chunk-test`** still owed a load window (load1
  27.85 at this session's start). A detached waiter
  (`/tmp/x1-jvm-wait.sh`, log `/tmp/x1-jvm-test.log`) polls loadavg
  every 30 s and, after two consecutive readings ≤ 8, runs the suite
  scoped via a direct classpath (`-Xmx1536m`, result + load logged).
  Verdict lands here and in X1-REPORT.md when it fires.

## Successor, round 4 continued (2026-11-12): the local JVM run, then the venue shift

- **The waiter fired** (window at load1 5.76, run 10:17–10:30 UTC):
  the JVM suite ran to completion — NOT OOM-killed this time.
  **8 tests, 68 pass, 18 fail, 18 error.** The suite is RED in the
  merged tree at the pin. Failure classes, re-derived from the log
  (`/tmp/x1-jvm-test.log` on hatch) and the code:
  - **Hash ERRORs (C1 pin defect):** `cached-hash`/`cached-digest`
    `set!` the deftype fields `_h`/`_d`, which are final —
    `IllegalAccessException: Can not set final field
    StaticRow._h` on every handle `hash`. Verbatim at the pin
    (C1's `chunk.cljc:1212`); the transplant is innocent.
  - **Equiv FAILs (root not yet pinned):** plain = static prints
    identical but returns false (`chunk-protocols-in-place`,
    `chunk-update-appends`, `chunk-equality-domain` lines
    224–230); `{:a 0.0}` and `{:a -0.0}` packs compare equal and
    `##NaN` packs compare unequal — the digest/equiv path
    (`node-equiv`, digest-first) is where the investigation sits.
  - **Test defect (F-D family):** the `chunk-roundtrip` defspec
    errors at namespace use — `gen/list` called with 3 args
    (chunk_test.clj:109) against test.check 1.1.1's arities.
  - **`chunk-roundtrip-values` ERROR:** `unpack (pack nil)` —
    `pack` returns a scalar as itself (nil → nil, FORMAT-V1 §2),
    and `unpack`/`open` on it throws `:eve.static.chunk/short`.
    Whether scalars-as-themselves exempts them from the round-trip
    test or `unpack` must answer a scalar as itself is open.
  - **`chunk-fill-policy` FAIL:** `pack-all` of 2,000 rows ×
    ~210-byte payloads returned ONE chunk of ≤ 65,535 bytes — the
    columnar form collapses the repeated payload, defeating the
    test's size premise. A probe owes the distinction: writer
    dedup working as designed vs a seal-logic defect.
  - Cross-lane corroboration: C1's own lane, advanced past the
    pin, reports its suite green in its tree (705/706, plus a
    nil-header guard) — the pin's reds are real, and X1 stays at
    pin `9ea620b` per its brief.
- **VENUE SHIFT (operator directive, 2026-11-12 05:19 ET):** no
  heavy runs on hatch at any load, effective now. The local waiter
  is stood down. Both owed legs are filed with the job agent
  (lane `static-eve/node`, venue_pref `j`, class `heavy`), each
  cloning `~/static-eve-bundles/x1.bundle` ON J and checking out
  `claude/static-x1-node` at tip `77986c6`:
  - inbox `static-eve-node-node-suite-1.json` → jobs/
    `20261112T103324-jobagent-node-x1-node-suite-x1-1-x1-4-at-c1.json`
    — shadow compile + Node suite; log
    `~/static-eve-results/x1-node-suite.log` on J (Node version
    printed into the log as part of the result).
  - inbox `static-eve-node-jvm-chunk-test-1.json` → jobs/
    `20261112T103316-jobagent-node-x1-jvm-eve-static-chunk-test.json`
    — scoped JVM `eve.static.chunk-test`; log
    `~/static-eve-results/x1-jvm-chunk-test.log` on J, with the
    local contended-box numbers (68/18/18) named in the filing as
    the comparison basis.
  - **Bundle refresh needed** if the staged bundle on J predates
    tip `77986c6` (the code state both jobs pin).
- X1-REPORT.md is drafted (findings F-A–F-F, symtab tie-break,
  host-model edges all carried) and is committed when the J
  verdicts land — the report's JVM line waits on the J run, per
  the rule that verdicts come only from runs actually made.
