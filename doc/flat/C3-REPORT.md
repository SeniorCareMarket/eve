# Lane C3 — the container: sixteen chunks, 1 MiB — REPORT

Branch `claude/static-c3-container` (local only; this box has no GitHub
credential, the coordinator collates lane branches locally). Code tip measured:
`22bc1d0`. Lane state in full: doc/static/C3-STATUS.md. Board row: B8.

## What the lane built

`src/eve/static/container.cljc`, on the frozen W0 contract (header and
block/relocation table readers from `eve.static.contract`, no private copy):
a directory over a run of 64 KiB chunks — directory opens any single chunk,
binary-search open, enter-mid-stream, append that keeps the old value readable,
one-stream compression over the run, and the container as the placement unit
(C3.5: units in order + the cross-unit plan read from the first unit only,
FORMAT-V1 §9 — the container side of what Z3's placement consumes).

Tests first, registered in W0's manifest (C3.1–C3.5). Requirement suite green
on hatch on the W0-merged base: **5 tests, 38 assertions, 0 failures**
(`clojure -M:jvm-test -n eve.static.container-test`). Known scaffolding, stated
not hidden: C1's in-chunk reader had not landed when this lane ran, so the
container tests use a clearly-marked toy chunk payload; the container reads
only the FORMAT-V1 §2 header fields (magic/version/kind/length), which are
frozen.

## Board row B8 — measured, gate NOT MET

The real arm, run by doc/static/C3-B8-MEASURE.clj (same rows, one session,
zlib level 6; row committed in doc/static/SPIKE-LOG.md):

| corpus | rows in 16 chunks | container (zlib) | one chunk (zlib) | overhead | gate |
|---|---:|---:|---:|---:|---|
| messages (10k chat) | 5,568 | 218,441 | 175,654 | **+24.36 %** | ≤ +5 % — NOT MET |
| records (50k rows) | 27,680 | 392,383 | 313,306 | **+25.24 %** | ≤ +5 % — NOT MET |

The research figure behind this row was a proxy (chunk sizes compressed
alone); sixteen chunks under one compression had never been run. It has now.

Iterations, one change each, both logged in SPIKE-LOG either way:

- one stream over the run vs per-chunk streams summed: +27.97 % / +21.10 %
  per-chunk context — the single stream buys almost nothing.
- preset dictionary = chunk 0's bytes: 217,933 (+24.07 %) / 392,404
  (+25.25 %) — no movement.

Two no-movement iterations: the compression line of attack is closed in this
lane, per the perf-loop rule. The mechanism: per-chunk symbol/shape tables
reset every 64 KiB and codes are reassigned per chunk; the body patterns
deflate exploits in the one-chunk arm do not survive the reset, and a preset
dictionary does not restore them because the tables are not byte-identical
across chunks. Closing the gap is above this lane: a container-level shared
table (a format change to FORMAT-V1 §2's assumptions) or a wider-window
compressor (§6 already defers compression past v1). FORMAT-V1 §2's "a 1 MiB
run recovers all but 1–4 %" is refuted for zlib by this run. The gate is
reported as measured — not met, not relabeled.

## Venue confirmation — filed, results pending coordinator delivery

Under the operator's venue order (no heavy runs on hatch at any load), the
successor session filed both confirmations with the job agent for J
(lane `static-eve/container`, venue_pref `j`, class heavy):

- `~/workspace/job-agent/inbox/static-eve-c3-suite.json` — requirement suite
  from the staged bundle at the code tip; deliverable
  `~/static-eve-results/c3-suite.log` on J.
- `~/workspace/job-agent/inbox/static-eve-c3-b8.json` — the B8 measure script
  (all arms incl. the dictionary arm); deliverable
  `~/static-eve-results/c3-b8.log` on J.

Both run strings verify `HEAD == 22bc1d0` and fail fast with a STALE-BUNDLE
line otherwise. The bundle staged on J was cut at `4b81c10`; a refreshed
bundle at the tip sits at `~/workspace/static-eve/bundles/c3.bundle` on hatch
for the coordinator to re-stage. Venue numbers append to SPIKE-LOG as a
confirmation row when delivered; nothing in this report depends on them —
they reproduce (or refute) measurements already banked, they do not create
new claims.

## Declaration

Lane C3 is complete on its substance: the container is implemented on the
frozen contract, its requirement tests are green, and its board row is
measured honestly — **B8 fails its gate at +24–25 %**, with the mechanism
named and the fix located at the format level, above this lane. Unreached
gates are reported, never relabeled: that is this lane's landing.
