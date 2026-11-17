# Final verification — the assembled flat tree

Tree: branch `claude/flat-rename` at **6124bb3** (collation 4fb369f +
sister load-fix d2a5cff + the `eve.flat` rename rebased on top).
All evidence from J, `~/static-eve-results/final-*.log`; every log's
first line is `HEAD=6124bb3e52514d3c498990a5583847f79fa19417`,
read firsthand. 2026-11-12.

## Verdict: the assembled tree matches the collated baselines exactly

### JVM spot set — `final-jvm2.log`

- `eve.flat.sister-test`: 6 tests / **45 assertions, 0 failures,
  0 errors** — S1–S6 intact under the new namespace.
- `eve.flat.dataset-batch-test`: 3 tests / **29 assertions,
  0 failures, 0 errors**.
- EXIT=0.

### Requirements — `final-req.log`

- **594 assertions, 0 failures, 1 error** — identical to the collated
  baseline. The 1 error is `requirements-covered` failing to resolve
  `eve.flat.image-node-test/run-wastes-nothing` from the JVM: the
  manifest host-blindness residual, its citation renamed with
  everything else. Not a regression; carried as a named residual.

### Node image suite — `final-node.log` (build `:flat-h4-test`,
runner `eve.runner.flat-h4-main`)

- The suite **loads and runs** on the assembled tree — the load death
  the sister load-fix closed stays closed after the rename.
- **4 tests / 31 assertions, 3 failures, 0 errors.** All three
  failures are `image-reads-every-type` at
  `eve/flat/image_node_test.cljs:79, 80, 113` — H4.1's Dataset row,
  the named Eve-core residual (CLJS deftype field reads hardcode the
  module slab sio; the image bytes are proven correct field-by-field
  in H4's report). Byte-identity arm green; run-wastes-nothing
  (H4.2) green.
- **No undeclared-var warning for `eve.flat.sister/register-sister!`**
  — the string "sister" appears nowhere in the log. The 10
  undeclared-var warnings that do appear are all in `eve.flat.chunk`
  (decode-plain ×5, IllegalStateException ×2, clojure, byte-array,
  IndexOutOfBoundsException) and are **byte-identical in substance
  and count to the sisterfix baseline** (`sisterfix-node.log`, where
  they read `eve.static.chunk/…`): pre-existing CLJS analyzer noise
  from chunk.cljc's JVM-side references, not a rename regression.

## Process findings (filing defects, not tree defects)

1. **The two pre-filed legs never ran — the router held both.**
   `held-20261112-static-eve-final-{jvm,node}.json`: "held —
   missing/empty string field: needs". `needs` must be a non-empty
   string, not `[]`. Both were re-filed with `needs` as a string;
   the harness-inspection leg was superseded by filing the real
   Node suite directly.
2. **The held JVM run string was itself broken.** Re-filed verbatim,
   it died before any test: `Error while parsing option
   "--config-data {:aliases": EOF while reading` — its
   `-Sdeps {:aliases …}` argument reaches clojure split at the
   spaces inside the bash -c string. The judged run above used the
   campaign's proven form instead (`clojure -M:jvm-test -n <ns>`,
   chained, HEAD first line in-log) — the form every sisterfix JVM
   leg used. The tree was never implicated: HEAD printed correctly
   in the dead log before the parse error.

## Residuals standing on the assembled tree (unchanged from collation)

H4.1 CLJS deftype field reads · manifest host-blindness · Z1.7 image
read speed · A16 STM half-commit · Z3.4 thrown-swap leak · B8
container format question · kind-18 u16 symbol codes · forks F2/F3
awaiting the operator (F1 DECIDED: `eve.flat`).
