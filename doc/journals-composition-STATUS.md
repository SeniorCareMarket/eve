# Journals composition — STATUS (2026-10-28)

Lane: `claude/journals-composition` off exploration tip
`e1b0f6c2e0b` (branch `claude/eve-j-cost-exploration`). Mission:
compose the two measured halves of the predicted journals end
state on one tree and measure the composition. Adoption remains
the operator's decision; nothing lands to main.

The two halves:
1. Prefix-bounded `flush!` prototype — commit `efeab102bb9`
   (tally.cljc only; per-entry freeing test untouched). Measured
   alone on the CHECKPOINT floor: warm 300 = 759 µs/op,
   warm 5000 = 417 µs/op (J1000 counter); crash 72/72;
   JVM suites 47 tests / 419 assertions / 0 failures.
2. Flush-advance floor — commit `dad4e4b0f90` (atom.cljc journal
   state gains `:last-epoch`; floor advances at every flush to
   the previous flush's epoch). Measured alone: crash 72/72;
   matched-trio warm 5000 = 11,198 µs/op.

Legs: (0) housekeeping — re-bank the complete J10-counter crash
evidence (the exploration branch's copy is truncated at 6 points,
no totals line; the complete file in ~/workspace/jcost-runs has
10 points + totals). (1) Compose dad4e4b0f90 onto this tree —
different files (atom.cljc vs tally.cljc), clean expected.
(2) Crash suite, all six cells, same driver/invocation as the
exploration lane. (3) JVM suites, bar 47/419/0. (4) Bench trio
route (durability-bench bench-size, J1000 counter, warm 300 /
5000, n 3000) — ONLY when the re-mint lane's byform-cut is not
running on this box; timing under contention is worthless.
(5) Report + double declaration.

## Log

- 03:0x ET — read firsthand: exploration report + STATUS (full),
  floor-advance log + dad4e4b0f90 stat (atom.cljc +49/−9 region,
  STATUS file), crash_suite_jcost.py invocation (EVE path
  hardcoded to the exploration worktree — my run uses a copy
  pointed here), durability_bench bench-size signature.
  Worktree created at ~/workspace/wt/journals-composition
  (first attempt's checkout was interrupted by a session
  restart; repaired, clean at e1b0f6c2e0b). Box: byform-cut
  RUNNING (re-mint lane), load ~5 — crash cells may proceed,
  bench waits.
- 03:1x ET — housekeeping banked @ 5074757fb87: banked
  crash-proto-J10-counter.txt replaced with the complete file
  (10 points + "# totals: 10 pass, 0 violations"); replacement
  noted in the commit message.
- 03:2x ET — COMPOSITION banked @ 65c5e13dddd (cherry-pick of
  dad4e4b0f90). The CODE composed clean: atom.cljc applied with
  no conflict and is byte-identical to the floor-advance
  branch's version (0 diff lines); tally.cljc is byte-identical
  to the prototype tip (0 diff lines). The one collision was
  documentary: eve/doc/durability-spike-STATUS.md — HEAD's copy
  (831 lines) ends where the floor-advance lineage appends the
  sweep + floor-advance sections (231 lines); resolved by
  composition (shared prefix untouched, append taken whole;
  result 1,062 lines = the incoming file exactly). Committed-tree
  marker sweep: 10 files at HEAD = 10 at base — no new markers.
  Next: crash suite, six cells, sequential.
- 03:3x ET — crash suite running (driver copy
  ~/workspace/journals-composition-runs/crash_suite_comp.py,
  EVE pointed at this worktree; cells sequential, evidence
  crash-comp-*.txt in the runs dir). B counter 9/9, B kb64 9/9 —
  18 points, 0 violations so far. J1 cells in flight.
  byform-cut still running; bench still waiting on it.
