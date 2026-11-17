# H2 — Journal: lane report

Branch `claude/static-h2-journal`. Base: Z1 tip `e3320f4` (Detach's
fixed walk) + clean merge of Z3 tip `835e6fc` (place + the checked
gate + Z2's run claim). T1 not merged — `eve.static` delegates
lazily, and H2.1's seam is placement inside a swap!, the same path
`transfer!` rides; its commits journal identically by construction.

## The requirement

H2.1 `place-survives-crash` (IMPL-PLAN §3.4): place, kill before the
next commit, reopen — the value reads and the allocated set is
exact. Test: `test/eve/static/journal_test.clj`, one deftest, three
arms, against a persistent journaled mmap domain, the halt simulated
without the close-time checkpoint.

## Verdict: H2.1 BUILT — placement into a persistent domain is journaled, crash-safe, and exact

Evidence: `h2-suite2.log` on J, at this branch's tip —

- `eve.static.journal-test`: **1 test, 10 assertions, 0 failures,
  0 errors.**
- `eve.static.requirements-test`: 514 assertions, 0 failures,
  1 error — W0's api-surface nil-probe (`(detach nil)` throws a
  protocol error before lane code runs), the attributed contract
  item every lane meets at these pins; the collation tree carries
  the amendment as a flagged commit. Not an H2 defect.

## The three arms

1. **Placed commit survives a kill.** Placement inside a swap! on
   the persistent journaled domain engages the journal; the process
   is killed without the close-time checkpoint; on reopen the value
   reads equal and the allocated set is exact.
2. **Placed value under a later commit survives a second kill.** A
   second commit lands on top of the placed value; killed again,
   reopened — value and allocated set still exact.
3. **Thrown placement journals nothing, leaves no phantom commit.**
   Per the banked Z3.4 correction (a thrown swap attempt leaks its
   placed blocks for every writer — `swap-attempt!` has no catch
   around the swap fn; atom-level Eve, out of this lane), this arm
   asserts only the journal-side facts: no commit ⇒ no journal
   record, and recovery serves the pre-attempt value. The attempt's
   block residue is printed, not asserted away — see below.

## The failed placement's residue, exactly as printed

From `h2-suite2.log`, verbatim:

```
H2 failed placement — free-count delta, live domain (Z3.4 leak class): [509 22 31 20 1 0] blocks held by class, of the image placed then thrown over
H2 failed placement — free-count delta, after kill + reopen: [509 22 31 20 1 0] blocks held by class
```

583 blocks (509 + 22 + 31 + 20 + 1) claimed by the placed-then-thrown
image remain held in the domain, **identically after kill + reopen**:
the journal carries no record of the attempt and recovery serves the
pre-attempt value, but the thrown attempt's blocks are not reclaimed
by the reopen either. That is Z3.4's leak, quantified in a persistent
domain and named here with evidence; the fix is atom-level and stays
out of this lane.

## The round-1 correction

Suite leg 1 (`h2-suite.log`) was red in the test's own
`checked-image` helper, before placement was attempted: the helper
called `image/image-bytes` on detach's return value, but **detach
answers the image byte array itself** — `ClassCastException [B` →
`Image` at image.cljc:639 via journal_test.clj:86. The same
call-shape class Zip hit (its tests pass detach's bytes straight to
`open-checked`, which is what the fixed helper does, `13071f3`).
Placement into a persistent domain was never actually blocked; the
first red measured the test, not the seam.
