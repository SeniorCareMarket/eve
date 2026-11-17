# Lane H6 — Dataset <-> batch: REPORT

Branch `claude/static-h6-dataset`. Base: Columns' close-out tip
`55c570b`, Chunk's tree merged at `abbd260`.

## What the lane built

`src/eve/dataset/batch.cljc` — the bridge between Eve Datasets and the
columnar batch form (Columns' `eve.static.column`):

- `dataset->batch` reads each Dataset column's plain values **once**
  and encodes them via Columns' `encode-batch`, forcing the dense kind
  of the column's own dtype (float64->9, int32->6, uint8->1,
  float32->8, uint32->7). One copy per column; no row-wise transpose.
- `values->batch` is the same bridge at the value level, for the column
  types a Dataset's EveArrays cannot hold: strings (kind 19),
  keywords/symbols (kinds 17/18 over a per-batch dictionary), booleans
  (kind 16), nulls (validity bitmap; an all-null column is kind 16
  with every row absent), int64 (kind 10), instants (kind 10, epoch
  millis, inverted by `batch->values` via the descriptor's `:instant`
  dtype).
- `batch->dataset` / `batch->values` rebuild the values; a rebuilt
  Dataset is equal to the source. Unsupported column types refuse by
  name. A non-WIDE encode that hits Columns' `:offset-overflow`
  refusal is retried WIDE.
- The descriptor carries `{:bytes :node-off :ctx :names :dtypes :n
  :wide?}`; the ctx is the map `eve.static.column/open-batch` reads
  through (symbol fn/code, shape fn, WIDE flag).

Requirements: H6.1 `dataset-batch-roundtrip`, H6.2
`columnar-pipelines` (the columnar bench's five pipelines over a batch
vs today's Dataset machinery in one session, at the bench's 1e-9
tolerance), H6.3 `dataset-column-types` — all in
`eve.static.dataset-batch-test`, tests committed first, red.

## The one defect, and its provenance

First J run of the implementation (`b285b4e`): 3 tests / 26
assertions, 0 failures, **2 errors** — both the same call:
`ArityException: Wrong number of args (4) passed to clojure.core/into`
at `batch->values` (batch.cljc:227), reached by H6.1 via
`batch->dataset` and by H6.3 directly. H6.2 did not error.

Cause, re-derived at the pin before fixing: `batch->values` passed
`(range)` and `names` as two separate source collections to `into`,
whose transducer form takes exactly one. Columns' `encode-batch` and
Chunk's pack/open were not implicated — both pass standalone in their
own trees (C2's suite is J-green at 9,459 assertions; C1's likewise);
the defect was in this lane's rebuild step, the difference between the
seats. Fix `2f2e3e2`: index+name zipped via `map-indexed` over
`names`; the file's other two `into` sites verified as the correct
3-arg transducer form.

## Evidence

- Suite at the fix (`h6-suite.log` on J): **3 tests / 29 assertions,
  0 failures, 0 errors, EXIT=0** — the assertion count rose from 26
  (the erroring run) to 29 because the two formerly-erroring tests now
  run to completion. Provenance note, stated plainly: that delivered
  log carries no HEAD line (the filing's echo landed outside the
  redirected region), so it is corroborating evidence, not the
  verdict-grade run. The confirming leg below was filed with HEAD as
  the log's first line inside the redirect and re-runs both
  namespaces at the final tip; the lane's declaration rides on it.
- Confirming leg: requirements-test + dataset-batch-test at the final
  tip — verdict recorded in H6-STATUS.md on delivery.

## Standing items respected

- Columns' flagged kind-18 limitation (u16 symbol codes unchecked —
  truncates past 65,536 symbols in one chunk): **respected, not
  touched.** This lane's dictionaries are per-batch and far under the
  limit; the fix, if any, is Columns'/collation's.
- Columns' u32 `put-off!` offset fix is in this tree unchanged.
- No second copy of the column or chunk machinery: the bridge calls
  Columns' encode/open readers and nothing else.
