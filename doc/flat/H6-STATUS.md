# Lane H6 — Dataset <-> batch: STATUS

Branch: `claude/static-h6-dataset` (tree `/home/hatch/workspace/static-eve/h6`).
Base: Columns' close-out tip `55c570b`, with Chunk's tree merged at
`abbd260` (merge `5ee1e20`; the only conflict was SPIKE-LOG.md, resolved
as a union of the two lanes' appended rows).

## Log

- 2026-11-12 — lane opened. Requirements (IMPL-PLAN §3.4): H6.1
  `dataset-batch-roundtrip`, H6.2 `columnar-pipelines`, H6.3
  `dataset-column-types`, all in `eve.static.dataset-batch-test`,
  registered `:planned` in requirements.edn (Wave 0's rows).
- Tests first: `test/eve/static/dataset_batch_test.cljc` committed red —
  it requires `eve.dataset.batch`, which does not exist yet. The bridge
  will live in `src/eve/dataset/batch.cljc` (lane-owned `src/eve/dataset*`),
  built on Columns' `eve.static.column` encode/open readers.
- Design, in one paragraph: `dataset->batch` extracts each Dataset
  column's plain values once and encodes them with Columns'
  `encode-batch`, forcing the dense kind of the column's own dtype
  (float64->9, int32->6, uint8->1, float32->8, uint32->7, ...) — one copy
  per column, no row-wise transpose. `values->batch` is the same bridge
  at the value level for the column types a Dataset's EveArrays cannot
  hold: strings (kind 19), keywords (symbol kinds 17/18 over a
  batch-level dictionary), booleans (kind 16), nulls (validity bitmap;
  an all-null column is kind 16 with every row absent), int64 (kind 10
  or the planner's narrowed/integer kinds, value-exact), instants
  (kind 10, epoch millis, dtype `:instant` recorded in the descriptor
  and inverted by `batch->values`). The descriptor carries
  {:bytes :node-off :ctx :names :dtypes :n :wide?}; the ctx is the map
  `eve.static.column/open-batch` reads through (symbol fn/code, shape
  fn, WIDE flag). A non-WIDE encode that hits Columns'
  `:offset-overflow` refusal is retried WIDE.
- Banked state respected: Columns' u32 `put-off!` fix is in this tree;
  kind-18 symbol codes past 65,536 symbols in one chunk are Columns'
  flagged limitation — not touched here (this lane's dictionaries are
  per-batch and far under it).
- Bundle: creation needed — `bundles/h6.bundle` for this branch, staged
  to J by the coordinator before the suite leg runs.
- 2026-11-12 — implementation committed (`b285b4e`):
  `src/eve/dataset/batch.cljc` — `dataset->batch`, `values->batch`,
  `batch->values`, `batch->dataset`, `open-batch`, per the design above.
  A local namespace-load check was attempted under load1 10.1 and hit
  the 55 s light-check ceiling with no output — no verdict taken from
  it; the lane's own run is the J leg.
- Bundle created at `b285b4e` (`~/workspace/static-eve/bundles/h6.bundle`,
  list-heads verified). Suite leg filed:
  `~/workspace/job-agent/inbox/20261112-static-eve-h6-suite.json` —
  deliverable `~/static-eve-results/h6-suite.log` on J (HEAD line,
  `eve.static.dataset-batch-test` summary, EXIT). Awaiting bundle
  staging + the log via the coordinator.
- On a green suite: flip H6.1–H6.3 to `:built` in requirements.edn,
  re-run requirements-test + dataset-batch-test on J as the confirming
  leg, write H6-REPORT.md, declare.
- 2026-11-12 — successor session. The first suite leg died EXIT=128
  (cloned before the bundle was staged — no test ran; not a verdict).
  Bundle on J verified staged at `b285b4e` (list-heads). Suite leg
  re-filed in the proven form:
  `~/workspace/job-agent/inbox/20261112-static-eve-h6-suite2.json` —
  same deliverable `~/static-eve-results/h6-suite.log`. Awaiting the
  log via the coordinator; the J run is this lane's first real
  measurement.

- 2026-11-12 — finisher #2. Delivered h6-suite.log (b285b4e): 3 tests /
  26 assertions, 0 failures, 2 ERRORS — both the same call:
  `ArityException: Wrong number of args (4) passed to clojure.core/into`
  at `batch->values` (batch.cljc:227), reached from H6.1's
  dataset-batch-roundtrip (via batch->dataset) and H6.3's
  dataset-column-types (directly). Re-derived at the pin: batch->values
  passed `(range)` and `names` as two separate collections to `into`,
  whose transducer arity takes one source. H6.2 columnar-pipelines did
  not error. Columns'/Chunk's pieces are not implicated — the defect is
  in this lane's rebuild, the difference between the seats.
  Fix `2f2e3e2`: index+name zipped by `map-indexed` over `names`
  (paren balance verified; the file's other two `into` sites are the
  correct 3-arg transducer form). Bundle recreated, staged to J,
  list-heads verified at 2f2e3e2. Suite re-filed:
  `20261112-static-eve-h6-suite3.json` → h6-suite.log. On green: flip
  H6.1–H6.3, confirming leg (requirements-test + dataset-batch-test),
  H6-REPORT.md, declare.
- 2026-11-12 — close-out session. Delivered h6-suite.log at the fix:
  3 tests / 29 assertions, 0 failures, 0 errors, EXIT=0 (assertions
  26 -> 29: the two formerly-erroring tests now complete). Provenance
  caveat recorded in H6-REPORT.md: that log carries no HEAD line (the
  filing's echo landed outside the redirect), so it corroborates; the
  confirming leg is the verdict-grade run. H6.1–H6.3 flipped to
  `:built`. H6-REPORT.md written. Confirming leg filed (requirements-
  test + dataset-batch-test, HEAD first line inside the redirect,
  bundle recreated + staged + list-heads verified at the final tip
  before filing) -> `h6-confirm.log`. On its delivery: record the
  verdict here and declare the lane complete. If red: re-derive
  before declaring anything.
- 2026-11-12 — declaration session. Confirming leg DELIVERED and
  verified firsthand (`h6-confirm.log` on J): first line
  `HEAD=fdf07d33901d7014acc22d10c7c2ec0cca86b285` (the declared tip);
  requirements-test + dataset-batch-test together: **535 assertions,
  0 failures, 1 error**. The lane's own dataset-batch suite is green
  at the declared tip. The 1 error, read from the log: `ERROR in
  (api-surface) (chunk.cljc:748)` — W0's api-surface nil-probe, where
  `chunk/open`'s TYPED refusal `{:type :eve.static.chunk/short}`
  ("static chunk: shorter than a header") surfaces uncaught. This
  tree carries the pre-amendment probe; on the collated tree the same
  probe is green — collation's amendment accepts typed refusals and
  its repairs landed there. The error is the attributed cross-lane
  item, NOT an H6 defect: the dataset bridge neither raises nor
  mishandles it, and its own suite is 0/0.
  **Lane H6 COMPLETE** at `fdf07d3`: H6.1–H6.3 `:built`,
  H6-REPORT.md banked, confirming verdict recorded here.
