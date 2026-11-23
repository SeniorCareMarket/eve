# Lane Z4 REPORT — the count taken at the zip (fork F5)

Branch `claude/static-z4-counts`, final tip at writing: see git log
(this report is committed on it). Implementation: `src/eve/tally.cljc`
(commit 02da409, untouched since), counts written at placement in
`hold-root!` from a registered placement's block/edge tables, behind
`eve.tally/*count-at-place*` (default false). Harness and bench:
`test/eve/static/tally_count_test.cljc`, `doc/static/z4_bench.clj`.

## F5 verdict — NOT ADOPTED (FINAL)

Exactness is proven; speed is not. All five requirement tests pass
(below), so written counts are _correct_ — indistinguishable from
walked counts, row for row, including rollback. But the deciding
bench row missed its gate and the written count did not beat the
walk in the only completed same-session run:

| 5k chat messages, 70,997 blocks      | ms (min of 5, 2 warmup) | × placement |
| ------------------------------------ | ----------------------- | ----------- |
| placement alone (harness, per-block) | 2,957.20                | 1.00×       |
| placed swap!, counts walked          | 8,484.55                | 2.87×       |
| placed swap!, counts written (F5)    | 9,977.79                | 3.37×       |

Gate (row B2): ≤ 2× placement — **MISS** (written arm 3.37×).
Written vs walked: **1.18× slower**, i.e. the count written from the
tables cost ≈7,021 ms over placement against the walk's ≈5,527 ms
on this run.

The confound, stated plainly: the bench's arms run sequentially and
this box's load1 rose from 3.86 at session start to ~24 mid-run,
against the later (written) arm; absolutes on this box (1.5 shared
CPUs, fleet load) run ~40–80× the research session's (count ≈78
µs/block walked here vs the probe's ~1 µs/block), so ratios are the
only content and even they carry the drift. That made the verdict
above provisional when first written: F5 was to be decided by the
interleaved hold-root!-only probe (`doc/static/z4_probe.clj`, which
times just the count, ABAB, and re-checks row equality), whose early
attempts were killed mid-run by runtime restarts before printing a
single number.

## The decider — interleaved probe, run clean on J (2026-11-12)

The probe has now run to completion on J (log `f5-probe4.log`,
read firsthand by the coordinator): interleaved ABAB rounds at
stable load1 ~8.5–9.0, hold-root!-only arm (the count alone, no
placement), 70,997 blocks, 5k chat messages. Rows re-checked equal:
written == walked, true.

| count only, 70,997 blocks | min       | median    |
| ------------------------- | --------- | --------- |
| walked                    | 161.57 ms | 170.79 ms |
| written (F5)              | 391.26 ms | 885.12 ms |

Written counts are **2.4× slower than the walk at best, ~5× slower
at median** — with the load confound removed, the written count
loses by _worse_ margins than the confounded bench suggested. The
provisional verdict is confirmed and is now **FINAL: F5 is NOT
ADOPTED.** Counts stay walked. The `*count-at-place*` mechanism
stays in the tree behind its flag, **off** (its shipped default):
it is exact (Z4.1–Z4.5, and the probe's row-equality check), so the
flag remains available as an instrument, but nothing turns it on.

Note the gate's own framing: it ratios against the harness's
per-block placement (~41.7 µs/block); against Z3's real zip (~2.5 ms
for this corpus in the research session) a placed commit is ~all
count, so the written-vs-walked comparison is the number that
matters — and on the clean probe it is decisive against F5.

## Requirement verdicts (own run at 66d1931: 5 tests, 83 assertions, 0 failures, 0 errors)

Manifest names per `test/eve/static/requirements.edn`:

- **Z4.1 `written-counts-equal-walked` — GREEN.** Seven shapes
  (nested plain value, long string at root, eve-deftype with a
  reference + serialized field, int-map of 300, rrb vector of 2000,
  two generated trees): written rows equal walked rows, count word
  and flags word, block for block; the source's committed counts
  also match the independent in-degree model (edges + 1 for root).
- **Z4.2 `written-counts-exact` — GREEN.** Won swap: counts are the
  walk's and the value reads back. Lost CAS: both attempts' holds
  written; after flush, no loser block remains allocated, every
  winner block is. Throw after placement: no count written, every
  placed block uncounted (count −1), atom untouched, stale
  registration forgettable exactly once, later plain swap walks.
- **Z4.3 `written-counts-release-clean` — GREEN.** Drop the root and
  flush: every placed block freed, both arms (written and walked).
- **Z4.4 `written-counts-shared` — GREEN.** One vector held at 100
  places: header counted exactly 100 by the written path; every
  other row equals the walk's.
- **Z4.5 `flag-off-is-the-walk` — GREEN.** Flag off: register-placed!
  records nothing (forget answers false, write counter unmoved),
  rows are the walk's. Flag on with nothing registered: an ordinary
  swap! walks, counts match the in-degree model.

## Bench record

- Banked row: `doc/static/SPIKE-LOG.md`, 2026-11-12 04:41 ET entry
  (commit c799720), with the session's load history in the row.
- Not banked: the load1-26.81 map-10k row whose arms inverted
  (placement alone slower than placement+walk) — a load-storm
  artifact, recorded in Z4-STATUS.md; two whole-suite attempts that
  died (silent exit at −Xmx4g under fleet memory pressure;
  OutOfMemoryError in `make-heap-region` at −Xmx2g, corpus 2).
- Bench change (15c9cc8): optional corpus filter, same arms/warmup/
  runs, so each corpus runs in its own JVM — the only way the bench
  completes on this box at all.

## Still owed (environment, not code)

1. ~~The interleaved probe run (`doc/static/z4_probe.clj`) or a
   quiet-box bench re-run, to decide F5's speed question cleanly.~~
   **Done** — probe ran clean on J 2026-11-12 (see "The decider"
   above); F5 verdict is final.
2. B2 rows for map-10k, vec-100k, records-10k (messages was the
   F5-deciding corpus and ran first by design). These no longer
   gate F5; they remain owed as board completeness only.
