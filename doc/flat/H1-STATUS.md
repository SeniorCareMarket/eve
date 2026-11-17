# H1 (Disk) — lane status

## 2026-11-12 — lane opened, tests first

Tree: cloned from Z1 (e3320f4), branch `claude/static-h1-disk`. Meeting-point
merges in: Z3 Zip @ 835e6fc (place.cljc, check.cljc, Z2's ctx-alloc-run! in
alloc.cljc) and C1 Chunk @ abbd260 (chunk.cljc final) — both clean.

Requirements (IMPL-PLAN §3.4): H1.1 `eve.static.disk-test/run-claim-two-process`,
H1.2 `eve.static.disk-test/place-exact-disk` — registered in requirements.edn,
both `:planned`.

First commit 2da614e is the requirement tests, written before any lane code:
- H1.1: parent creates an mmap domain's files and releases them; two child
  JVMs (test/eve/static/disk_worker.clj) join the same files cold, wait on a
  go file, and claim runs (script 37 5 64 1 13 128 3 21 8 55 2 34, classes
  rotating 0–2, 10 rounds) plus single blocks through `alloc/alloc-run!` /
  `-sio-alloc!`. Assertions: each process's set internally duplicate-free,
  the two sets disjoint, per-class FREE_COUNT == TOTAL − claimed on a fresh
  open, every claimed block frees exactly once, counts restored to total.
- H1.2: place (via eve.static/place + a CheckedBytes image detached from a
  heap atom) on atoms in a `persistent-atom-domain` (journal off — the
  journal is H2's lane): won swap grows the domain by exactly the image's
  block count; a lost CAS leaves the same total as the won swap; a throw
  after placement leaves the atom untouched and grows the set by exactly
  the image's blocks (Z3.4's banked correction, asserted on disk); close +
  reopen reads the placed value back with the allocated set unchanged.

What the lane owns / expects to find: the mmap path of `ctx-alloc-run!`
(alloc.cljc) — its bitmap claim is a CAS loop over the shared mapped
bitmap file and the header counts ride atomic add/sub on the shared slab
header, so the two-process claim discipline exists in shape; unproven
until this test runs. Known boundary read from the code: `ctx-alloc-run!`
never grows a file-backed class (growth "belongs to its cross-process
discipline and is not led from here") — the tests size classes ahead
(8192 blocks of classes 0–2) and stay inside them.

**Bundle:** h1-suite1 ran before any bundle existed and died at the
clone (EXIT=128, no HEAD line — no evidence about the tests). The bundle
now exists locally: ~/workspace/static-eve/bundles/h1.bundle at lane tip
e176163 (git bundle list-heads verified). Coordinator: stage it at
~/static-eve-bundles/h1.bundle on J before the re-filed leg runs (the
leg's log prints HEAD; discard if it is not e176163 or a descendant).

Legs filed: h1-suite1 (dead, EXIT=128, bundle race) → h1-suite2
re-filed → ~/static-eve-results/h1-suite2.log (disk-test, one -n).

## 2026-11-12 — bundle race, twice; lane staged its own bundle

h1-suite2 died identically (EXIT=128, bundle still absent on J). After
two filings lost to the same race, the lane staged its own bundle:
scp bundles/h1.bundle → J ~/static-eve-bundles/h1.bundle (7,023,192 B,
verified present on J 11:47 ET). suite3 re-filed against the staged
bundle. Coordinator: future h1 bundle refreshes still welcome from the
sweep; the lane will refresh its own rather than lose a third leg.

## 2026-11-12 — finisher: suite4's two failures re-derived; test accounting fixed; suite5 filed

suite4 (J): 2 tests / 7,462 assertions, 2 failures, 0 errors, both in
place-exact-disk. H1.1 run-claim-two-process green in full.

Re-derivation at the pre-fix tip: the won arm measured 421 against the
expected 423, the lost arm 840 against 842 — both short by exactly 2,
and 2 is exactly the block count of the atoms' initial value {:other 1}
measured by the same detach (:init-blocks). A committed swap retires
its predecessor; the tests flush the zero-queue before counting, so
the freed predecessor blocks come off the in-use total. The disk path
claimed exactly the image's blocks; the test's expected arithmetic
ignored the retire. Verdict: TEST-side accounting defect, disk path
exonerated — cf. Transfer's census lesson (a count can include what a
placement doesn't net). The throw arm (no commit, nothing retired)
expected the full nblocks and passed, which pins the delta to the
commit/retire, not to placement.

Fix (a43ac0c): image-of also detaches {:other 1} and the won/lost arms
expect used0 + nblocks − init-blocks; the won arm now flushes the
zero-queue after place, like the lost arm. No product code touched.

Bundle refresh needed to a43ac0c (or descendant incl. this entry):
bundles/h1.bundle rebuilt locally and staged to J by the lane.
suite5 re-filed → ~/static-eve-results/h1-suite5.log (disk-test, one -n).

## 2026-11-12 — finisher: suite5 GREEN; lane complete

suite5 on J: 2 tests / 7,462 assertions, 0 failures, 0 errors, EXIT=0.
The run's job HEAD was a43ac0c (the accounting fix): the leg started
before the bundle refreshed to 948c16f reached J. a43ac0c..948c16f
differs only in this STATUS file — test and product code byte-identical
— so the verdict covers the declared tip's code; the discrepancy is
recorded, not hidden (see H1-REPORT.md, "Tip note").

H1.1 and H1.2 flipped to :built in test/eve/static/requirements.edn.
H1-REPORT.md written. Lane H1 (Disk) is complete: the mmap run claim
holds two-process, and place is exact on disk across won, lost, thrown
and reopened swaps, net of the retired predecessor.
