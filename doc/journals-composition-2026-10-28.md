# Journals composition — final report (2026-10-28)

Branch `claude/journals-composition`, off the exploration tip
`e1b0f6c2e0b`. The two measured halves of the predicted journals
end state, composed on one tree and measured. Adoption is the
operator's decision; nothing here lands to main.

## Composition

- Prefix-bounded `flush!` — `efeab102bb9` (tally.cljc; the
  per-entry freeing test byte-for-byte unchanged).
- Flush-advance floor — `dad4e4b0f90` (atom.cljc journal state;
  floor advances at every flush to the previous flush's epoch).
- Different files; the composition was clean. The lane's first
  commit also re-banked the complete J10-counter crash evidence
  (the exploration branch's committed copy was truncated at
  6 points with no totals line; the complete file, 10 points +
  totals, came from ~/workspace/jcost-runs).

## Correctness (evidence in eve/doc/journals-composition-evidence/)

- Crash suite, all six cells: **72 pass, 0 violations**
  (B 9+9, J1 17+17, J10 10+10) — both parent lanes' bar, met
  cell-for-cell on the composed tree.
- JVM suites: **47 tests, 419 assertions, 0 failures.**

## Bench — J1000 counter, n 3000, this machine

Run by the coordinator on 2026-10-28 after the lane stalled
twice (see STATUS); evidence bench-composed.txt in the evidence
dir, raw output also at ~/workspace/journals-composition-runs/.

| Window | Composed | Prototype alone (checkpoint floor) | Flush-advance alone |
|---|---|---|---|
| warm 300 | **530 µs/op** (1,888 ops/s) | 759 µs/op | — |
| warm 5000 | **187 µs/op** (5,339 ops/s) | 417 µs/op | 11,198 µs/op |

Phase-1 reference: J1000 with no floor at all measured 410 µs/op.
The composition holds the flat band — the deep window costs no
more than the shallow one — and sits below both single-fix
numbers at both windows. Single run on an idle box; flush-latency
rows in the established band (mean 187 ms at warm 300, 112 ms at
warm 5000), not the variable in any of these results.

## What remains

- Adoption of the journal as Eve's durability mode, and in what
  form: the operator's open decision. This lane measured the
  composition of the two floor/scan fixes; it did not land
  anything.
- CLR/Basilisp per-host journal append ports: separate named
  remainder (the append code there is dead — `#?(:clj …)` never
  fires on those hosts).
