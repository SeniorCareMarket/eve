# H2 — Journal: lane status

Branch `claude/static-h2-journal`, worktree `~/workspace/static-eve/h2`.
Base: Z1 tip `e3320f4` (Detach's fixed walk) + clean merge of Z3 tip
`835e6fc` (place + the checked gate + Z2's run claim, via Z3's tree).
T1 not merged: `eve.static` delegates lazily (`requiring-resolve`), so
detach/place resolve without it, and H2.1's seam is placement inside a
swap! — T1's `transfer!` rides the same place-in-swap path and its
commits journal identically by construction. (T1-REPORT.md does not
exist yet in T1's tree; T1-STATUS.md read in its place — nothing in it
touches the journal.)

## The requirement (IMPL-PLAN §3.4)

H2.1 `place-survives-crash`: place, kill before the next commit,
reopen — the value reads and the allocated set is exact. Shape:
`test/eve/journal_recovery_test.clj` (persistent journaled domain,
halt simulated without the close-time checkpoint).

## Respecting the banked Z3.4 correction

A thrown swap attempt leaks its placed blocks for every writer
(`swap-attempt!` has no catch around the swap fn) — atom-level Eve,
out of this lane. H2's arms therefore assert journal facts for
SUCCESSFUL placements, and for the thrown placement assert only the
journal-side facts (no commit ⇒ no journal record; recovery serves
the pre-attempt value) while PRINTING the attempt's block residue in
the live and reopened domains — naming it with evidence, not fixing
it here.

## Work

- `8cf81c5` — requirement tests first (red): `test/eve/static/journal_test.clj`,
  one deftest `place-survives-crash`, three arms (placed commit
  survives a kill with allocated set exact; placed value under a
  later commit survives a second kill; thrown placement journals
  nothing and leaves no phantom commit).

## Legs

- Suite leg 1 (tip `a9b7908`) → `h2-suite.log`: ERRORED in the test's
  own `checked-image` helper, before placement was attempted —
  `image/image-bytes` called on detach's return value, but detach
  answers the image byte array itself (Z1's/Z3's tests pass it
  straight to `open-checked`): ClassCastException `[B` → `Image` at
  image.cljc:639 via journal_test.clj:86. Test defect, not a place
  or mmap-domain wall; placement into a persistent journaled domain
  is still unmeasured. Fixed in `13071f3` (helper passes detach's
  bytes to `open-checked` directly).
- Suite leg 2 filed → `h2-suite2.log` (same two namespaces).
  Bundle refreshed: `~/workspace/static-eve/bundles/h2.bundle`
  staged at this branch's tip — the leg clones from J's
  `~/static-eve-bundles/`; bundle refresh needed to the tip named
  in the filing's `needs` before it runs; the log prints HEAD —
  discard any result whose HEAD is not that tip.
- **LANE COMPLETE.** Leg 2 delivered: journal-test 1 test /
  10 assertions, 0 failures, 0 errors — H2.1 PASSES (placement in a
  swap! on a persistent journaled domain engages the journal,
  survives a kill without the close-time checkpoint, reopens with
  the value equal and the allocated set exact; second-commit arm
  green; thrown-placement arm journals nothing, recovery serves the
  pre-attempt value, residue printed: `[509 22 31 20 1 0]` blocks
  held by class, identical live and after kill + reopen — Z3.4's
  leak quantified in a persistent domain). requirements-test 514
  assertions, 0 failures, 1 error = W0's api-surface nil-probe,
  attributed. H2.1 flipped `:built`; full record in H2-REPORT.md.
