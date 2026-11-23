# Z3 — the zip (`place`): lane report

Branch `claude/static-z3-zip`. Code tip under test: `a8009db`
(the suite-round-4 leg's tip; place code as of `84600da` plus the
checked gate, with meeting-point merges of Z1 through `e3320f4`,
C4 `339dbe3`, Z2 `ed97031`). Base: research tip `7402ef9` + W0
contracts (`cfa86c6`). No origin pushes (no credential on this box);
all commits local.

## What the lane delivers

`eve.static.place` — placing a FORMAT-V1 §9 image into a live atom
domain: validate the tables in full, claim every block through the one
public claim (`alloc/alloc-run!`), copy the bytes through the target
sio's own ops, and rewrite each pointer word to the slab offset its
block claimed. Doors: `place` (inside a `swap!`, the `eve.static`
delegate), `place-value` (the swap-fn form), `place!` (domain level:
the placed root's slab offset), `placement-plan` (the Z3.7 assignment
plan, computed before anything is claimed).

Properties, as designed and desk-verified:

- **Validation before claim (Z3.2).** Every table check runs before
  the first claim; each refusal is a typed ex-info
  `{:eve.static.place/check <name>}` and leaves the domain's allocated
  set unchanged. Check names are listed in `doc/static/Z3-STATUS.md`.
- **The checked gate (C4's contract, landed this session).** Byte-form
  images are accepted only as the `eve.static.check` CheckedBytes
  handle `open-checked` answers. Raw byte arrays — however valid they
  look — are refused `{:check :unchecked-bytes}` in `normalize-image`
  (the funnel all three doors pass through), via
  `check/require-checked`, before any parsing. The map form is built
  in process; its tables are `validate!`'s job.
- **Claims (Z3.3).** Through `alloc-run!` only — never a private
  bitmap path — so inside a swap attempt the claim is logged by the
  attempt's logging sio and a lost CAS takes the whole placement back
  through the atom's own hold/drop + free-unheld. This tree carries
  Z2's verified run claim after the merge (first-offset scalar);
  `place` normalizes both answer shapes (vector of offsets, W0's
  default arm; scalar first offset, Z2's arm). A nil run answer splits
  the class's blocks into halves recursively down to singles; a single
  a run will not answer goes through `-sio-alloc!` at its own size;
  class-6 (overflow) blocks are placed one by one through
  `-sio-alloc!` (the coalescing allocator's own alignment discipline,
  Z3.5).
- **Writes only through the sio's ops** (`-sio-write-bytes!`,
  `-sio-write-i32!`), so an encrypting sio's keystream applies to
  placed bytes exactly as to written ones (Z3.6), and a mapped domain
  needs no heap array.
- **Rollback.** Any failure after the first claim frees every offset
  claimed so far, through the same sio, before the exception
  propagates — a failed `place!` leaves the allocated set unchanged.
- **Units (Z3.7).** With `:unit-size`, blocks are grouped in buffer
  order into units (charged size + 8 + 12 per owned pointer, the P7
  accounting); the full plan is computed before the first unit is
  placed; the pointer pass resolves every relocation from the
  completed plan.

## Test state

Seven requirement deftests (`eve.static.place-test`), tests first
(3989f7b), plus this session's gate arms and the end-to-end arm
(9688f83 — written before the gate landed in 4ef4dc8):

- Z3.1 `place-reads-back-and-updates` — six shapes through the checked
  chunk form via the frozen `eve.static` door, the map form, and a
  **real detach → open-checked → place** arm (Z1's producer, three
  shapes, each with a further sound write on top).
- Z3.2 `place-refuses-bad-tables` — one case per check, each asserting
  the allocated set unchanged; plus the gate arms: raw chunk bytes
  (valid, and bad-magic) refused `:unchecked-bytes` at both the
  domain door and the atom door, the atom's value untouched, and
  `open-checked` itself naming the bad magic.
- Z3.3 `place-fallback-equal` — `alloc-run!` stubbed to nil (singles
  carry the placement) and a fragmented class 0; the placed value and
  the exact claimed count are asserted both ways.
- Z3.4 `place-exact-on-loss-and-throw` — lost CAS exact after flush;
  mid-placement failure (sixth claim throws) rolls back every claim;
  the throw-after arm asserts the corrected property below.
- Z3.5 `place-large-blocks` — the 5,004-byte string block lands in
  class 6, 8-aligned.
- Z3.6 `place-encrypted` — the needle reads back through the keystream
  and appears in no slab file.
- Z3.7 `place-by-units` — a 2,000-record image at 64 KiB units:
  several units, cross-unit pointers positive, value whole at 64 KiB
  and at 1 MiB.

## Results — suite round 4 (`z3-suite5.log` on J, at a8009db)

- **`eve.static.place-test`: 7 tests, 96 assertions, 0 failures,
  0 errors — GREEN.** All seven requirement deftests (Z3.1–Z3.7 as
  itemized above) pass on the venue at the shipped tip; Z3.1–Z3.7 are
  flipped to `:built` in `test/eve/static/requirements.edn`.
- **`eve.static.requirements-test`: 2 tests, 514 assertions,
  0 failures, 1 error.** The one error is in W0's `api-surface`
  deftest: its `(detach nil)` probe throws a protocol
  `IllegalArgumentException` (`IEveRoot` has no implementation for
  nil, at `core_deftype.clj:585`) instead of answering what the
  frozen contract expects — the same attributed item every lane
  meets at these pins; the collation tree carries the contract
  amendment as a flagged commit. It is **not** claimed here that the
  api-surface probe passed. No Z3 requirement's evidence rests on
  that probe: each Z3 verdict rests on its own place-test deftest
  above, and `requirements-covered` — the manifest check over the
  flipped entries — is among the passing assertions in the same run.

Getting here took four rounds, each red re-derived before any fix
(full record in `Z3-STATUS.md`): round 1 never compiled (a wrong
namespace alias and a missing closer in the lane's own test files);
round 2's 21 failures / 7 errors resolved to one product defect
(`chunk->image` demanding the relocation span divide by 12 against
FORMAT-V1 §2's 8-alignment — fixed), two test-writer defects, and a
sabotage seam that could not intercept compiled call sites; round 3
isolated the end-to-end arm's wrong call shape (`detach` answers
bytes, not an Image record) and, behind it, a stale meeting point —
Detach's pre-fix walk — consumed by merging Z1 at its fixed tip.

## Board row B1 — the zip, alone

Gate: **≤ 50 ns/block** (research probe: 33–42 ns/block). The bench
(`eve.static.place-bench`) builds each M6 corpus's hand image once,
then times `place!` into a fresh pre-sized heap domain, net of domain
creation, min of 5 after 2 warmups, and prints the claim mode its
tree's `alloc-run!` answers in — this tree's mode, post-merge, is
Z2's run claim (first-offset scalar).

**Measured** (banked in `doc/static/SPIKE-LOG.md` from
`z3-bench3.log` at 835e6fc, claim mode run): map 38,029.9 · vector
15,998.1 · records 29,860.1 · **messages 15,275.1 ns/block — the
gate is MISSED by ~300×**, reported as measured. The note in the log
stands: the research probe timed the raw zip loop; the board row is
the shipped path — full table validation, the checked-bytes gate,
and per-block sio ops included.

## Corrections banked

1. **Z3.4's plan wording is not a property `place` can keep.**
   `eve.atom/swap-attempt!` has no catch around the swap fn. Measured
   on this pin (probe in `doc/static/Z3-STATUS.md`): an ordinary
   in-domain build of ~7,520 blocks followed by a throw left the
   domain at 7,524 blocks in use, still 7,524 after the retire flush;
   the same build committed lands at 15,042. A thrown attempt's
   allocations are never reclaimed — for every writer, not just
   placement. What Z3.4 asserts is the part placement owns: lost CAS
   exact; a failure inside placement rolls back every claim exactly;
   a user-fn throw after placement grows the set by exactly the
   image's block count — no partial claims, no extras. The fix, if
   wanted, is atom-level and out of this lane's files.
2. **The bench's documented run line named a `:test` alias that does
   not exist** in deps.edn; corrected to the `-Spath -X:jvm-test`
   classpath form (the invocation the filed bench leg uses).
3. **Brief paraphrases vs the frozen manifest.** The successor brief
   paraphrased the requirements as "Z3.2 run claimed once per run",
   "Z3.5 concurrency", "Z3.7 the root cases incl. the past-1016-bytes
   correction". The manifest (W0, frozen) and IMPL-PLAN §3.3 name
   them as tested above: the run-claim discipline is Z3.3's fallback
   - the claim path itself, the concurrency content is Z3.4's
     lost-CAS arm, Z3.7 is units. The "root scalar past 1,016 bytes
     reads nil" item is a research-census defect on a read path, not a
     Z3 requirement; no Z3 test names it and this lane does not own it.

## Flags for collation

- **`alloc-run!`'s answer shape.** W0's default arm answers a vector
  of offsets; Z2's arm (this tree, post-merge) answers the run's
  first offset as a scalar. `place` accepts both; the collation must
  still pick one canonical shape and retire the other.
- **Z4/T1 consume `place` from this branch.** Z4's counts-at-place
  work and T1's transfer build on `place!`/`place-value`; both must
  take the merged tree (Z1+Z2+C4 included) or re-merge it — in
  particular, any T1 caller passing raw chunk bytes to `place` now
  meets the checked gate and must route through `open-checked`
  first, exactly as C4.4's contract states.
