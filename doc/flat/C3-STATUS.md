# Lane C3 — the container: sixteen chunks, 1 MiB — STATUS

Branch: `claude/static-c3-container`, base: research tip `7402ef9` (claude/sweet-carson-6pt29d).
Board row: B8. Gate: container compression ≤ 5 % over the whole value compressed as one.

## Step 1 — the B8 baseline, honestly (before any C3 code)

The research figure for B8 is a **proxy** (IMPL-PLAN §3.1, MEASUREMENTS M3/M8): 1 MiB chunks each
compressed alone came out +0–4 % over one-chunk, and 64 KiB chunks each compressed alone +23–35 %.
**Sixteen 64 KiB chunks under one compression was never run.** What exists at this pin, re-derived
from MEASUREMENTS.md M3 (probe `p2_sizes.clj`, committed output):

- messages (10k), zlib per single chunk packed as one: 298,139 B; per 64 KiB chunk, summed: 402,606 B (+35 %).
- records (50k), one chunk zlib: 556,463 B; per 64 KiB chunk, summed: 684,762 B (+23 %).
- datoms (30k), one chunk zlib: 100,306 B; per 64 KiB chunk, summed: 127,242 B (+27 %).

My lane's B8 run (the real arm: the first 16 chunks of the value, concatenated, one zlib stream over
the run, against the same rows packed as one chunk under one zlib stream) is owed below once the
harness runs; the container's own directory bytes are included in the container arm.

## Coordination state at lane start

- `origin/claude/static-w0-contracts`: not on origin yet (checked by ls-remote at lane start).
- `origin/claude/static-c1-chunk`: not on origin yet. C3 builds against FORMAT-V1 §2 (chunk header:
  magic/version/kind/length at bytes 0/4/5/8 — the only header fields the container reads) with the
  in-chunk reader deferred to C1; tests use a clearly-marked toy chunk payload as scaffolding.
- Z3 (placement) has not landed; C3.5 exposes the container side (units in order + the cross-unit
  plan read from the first unit only, FORMAT-V1 §9) for Z3.7 to consume.

## Progress

- [x] Lane start: plan §3/§3.1/§3.3-C3, FORMAT-V1, M3, M8 read firsthand.
- [x] Tests first (red): directory-opens-one-chunk, container-compression, enter-mid-stream,
      append-keeps-old-value, container-is-placement-unit. Registered in W0's manifest
      (test/eve/static/requirements.edn, merged from w0/claude/static-w0-contracts).
- [x] src/eve/static/container.cljc — built on the frozen W0 contract
      (eve.static.contract read-header/write-header!, block/relocation table readers).
- [x] Requirement tests GREEN: 5 tests, 38 assertions, 0 failures
      (clojure -M:jvm-test -n eve.static.container-test, on the W0-merged base).
- [x] B8 real arm RUN (below). Gate FAILS as defined; one iteration (preset dictionary) priced next.
- [x] Final: C3-REPORT.md committed on the local branch (this box has no GitHub credential;
      the coordinator collates lane branches locally — no push is possible or required).

## Successor session — venue confirmation (2026-11-12)

Prior sessions died in platform restarts; all state above was banked and is unchanged.
Under the operator's venue order (no heavy runs on hatch at any load), the two
confirmations of this lane's banked results are filed with the job agent for J
(`~/workspace/job-agent/inbox/`, lane `static-eve/container`, venue_pref `j`):

- `static-eve-c3-suite.json` — clone the staged bundle on J, run
  `clojure -M:jvm-test -n eve.static.container-test`; deliverable
  `~/static-eve-results/c3-suite.log` on J. Reproduces (or refutes) the banked
  green: 5 tests, 38 assertions.
- `static-eve-c3-b8.json` — clone the staged bundle on J, run
  `clojure -M -i doc/static/C3-B8-MEASURE.clj -e "(c3-b8/-main)"`; deliverable
  `~/static-eve-results/c3-b8.log` on J. Reproduces the B8 row (all arms,
  incl. the chunk-0 dictionary arm) off-box.

BUNDLE REFRESH NEEDED: the bundle staged on J (`~/static-eve-bundles/c3.bundle`)
was cut at `4b81c10`, two commits behind the tip. A fresh bundle at the tip
`22bc1d0` now sits at `~/workspace/static-eve/bundles/c3.bundle` on hatch —
coordinator to re-stage it to J before the legs run. Both job run strings verify
`HEAD == 22bc1d0` and fail fast with a STALE-BUNDLE line if the staged copy is old.

SPIKE-LOG: doc/static/SPIKE-LOG.md (row B8 with both iterations) is committed by
this session; it was left untracked by the predecessor. Venue numbers, when the
coordinator delivers them, append as a confirmation row under the hatch row —
the gate verdict does not change unless the venue numbers move it.

State of the lane's substance: implementation complete; requirement tests green
on hatch (banked, venue confirmation filed); B8 gate NOT MET as measured
(+24.36 % / +25.24 %), both priced iterations (one stream, preset dictionary)
showed no movement, so the compression line of attack is closed in this lane;
the gap is a format-level question (container-level shared table or a
wider-window compressor), named in the report for the collation.

## B8 — the real arm (2026-11-12, this lane, doc/static/C3-B8-MEASURE.clj)

Same rows both arms, one session, zlib level 6 (the M3 convention). Container arm = the
first 16 probe-packed 64 KiB chunks + this lane's directory bytes, one zlib stream over
the run. One-chunk arm = the same rows packed as one chunk, one zlib stream.

| corpus | rows in 16 chunks | zlib container | zlib one chunk | overhead | per-chunk summed (context) |
|---|---:|---:|---:|---:|---:|
| messages (10k) | 5,568 | 218,441 | 175,654 | **+24.36 %** | 224,784 (+27.97 %) |
| records (50k) | 27,680 | 392,383 | 313,306 | **+25.24 %** | 379,404 (+21.10 %) |

Gate ≤ +5 %: **not met.** The mechanism is visible in the numbers: the overhead is the
per-chunk symbol/shape tables repeating every 64 KiB, and deflate's 32 KiB window cannot
reach the previous chunk's copy of them, so one stream over the run recovers almost
nothing over per-chunk compression. The research proxy (M3/M8) compared chunk sizes whose
tables did not repeat — a 1 MiB chunk carries one table set for 16× the rows — so it
never priced table repetition. FORMAT-V1 §2's "a 1 MiB run recovers all but 1–4 %" is,
on this evidence, wrong for zlib; it would need a compressor whose window spans the
run (or tables that do not repeat per chunk — a format question above this lane).

Iteration in flight: price a preset dictionary (chunk 0's bytes, whose tables are what
chunks 1–15 repeat) on the same arms. The gate is reported as measured either way.

## Coordination notes

- C1 has tests + baselines only so far; the B8 bytes are the research prototype's packed
  bytes (same packing the plan's B6/B8 rows cite), and the container tests still use the
  marked toy-chunk scaffolding for C1's reader. What was measured against is stated here
  and in the report.
- Z3 landed place.cljc (z3/claude/static-z3-zip): its Z3.7 computes the placement plan
  (incl. crossings) from the image's §9 tables itself, before placing unit by unit.
  C3.5's cross-unit-plan is the container-side equivalent for an image whose tables
  live in its first unit (§9); both read the same tables through the W0 contract.
