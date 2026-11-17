# H1 (Disk) — lane report

Branch `claude/static-h1-disk`. Base: Z1 Detach @ e3320f4, with meeting-point
merges Z3 Zip @ 835e6fc and C1 Chunk @ abbd260. Requirements H1.1 and H1.2
both `:built`.

## Verdict

Both requirements green on J: 2 tests / 7,462 assertions, 0 failures,
0 errors (`h1-suite5.log`, job HEAD a43ac0c — see the tip note below).

- **H1.1 run-claim-two-process — PASS.** Two child JVMs joining the same
  mmap domain files cold claimed runs and single blocks through
  `alloc/alloc-run!` / `-sio-alloc!` concurrently (script of 12 run sizes,
  classes rotating 0–2, 10 rounds): each process's set internally
  duplicate-free, the two sets disjoint, per-class FREE_COUNT == TOTAL −
  claimed on a fresh open, every claimed block freed exactly once, counts
  restored to total. The mmap path of the run claim holds across processes:
  claims interleave at the shared bitmap CAS, and the header counts ride
  the shared slab header — no block was handed to both processes.
- **H1.2 place-exact-disk — PASS.** On `persistent-atom-domain` atoms
  (journal off; the journal is H2's lane): a won `place` swap grows the
  domain by exactly the image's blocks net of the retired predecessor;
  a lost CAS leaves the same total as the won swap (the lost attempt's
  blocks all returned); a throw after placement leaves the atom untouched
  and leaks exactly the placed attempt, nothing partial, nothing extra
  (Z3.4's banked correction, asserted on disk); close + reopen reads the
  placed value back with the allocated set unchanged.

## The suite4 failures — re-derived, test-side, disk path exonerated

suite4 measured the won arm at 421 blocks in use against 423 expected,
and the lost arm at 840 against 842: both short by exactly 2, and 2 is
exactly the block count of the atoms' initial value `{:other 1}` measured
by the same detach. A committed swap retires its predecessor; the tests
flush the zero-queue before counting, so the freed predecessor blocks come
off the in-use total. Placement on disk claimed exactly the image's blocks
— the test's expected arithmetic had ignored the retire. Fix (a43ac0c,
test file only): `image-of` also detaches `{:other 1}` (`:init-blocks`),
and the won/lost arms expect `used0 + nblocks − init-blocks`; the won arm
also flushes the zero-queue after place, like the lost arm. The throw arm
— no commit, nothing retired — expected the full `nblocks` throughout and
passed in suite4, which pins the delta to the commit/retire, not to
placement. No product code was touched by this lane.

## Known boundary (stated, not worked around silently)

`ctx-alloc-run!` never grows a file-backed class — growth belongs to a
cross-process discipline not led from here. The tests size classes 0–2
ahead (8,192 blocks each) and stay inside them. Growing a disk class under
concurrent claim remains unbuilt and unclaimed by this lane.

## Tip note

The green suite5 run checked out a43ac0c (the accounting-fix commit): the
leg started before the refreshed bundle carrying the STATUS-only commit
948c16f reached J. `git diff a43ac0c 948c16f` touches only
doc/static/H1-STATUS.md — test and product code are byte-identical between
the tested commit and the tip the lane declared from, so the verdict
covers the declared tip's code. The discrepancy is recorded here rather
than hidden.
