# Z2 — the run claim: completion report

Lane Z2 of the static-Eve spike (IMPL-PLAN §3.3). Branch
`claude/static-z2-runclaim` in this tree (SeniorCareMarket/eve; the box
has no GitHub credential — verified by the coordinator — so the local
branch is the record, collated locally). Base: research tip 7402ef9,
with Wave 0 merged (38c5f7e). File owned and edited:
`src/eve/deftype_proto/alloc.cljc`. Tests:
`test/eve/static/alloc_run_test.clj` (the manifest's namespace).
Manifest: Z2.1–Z2.6 flipped `:planned` → `:built`.

## Status of this report (final, 2026-11-12)

This report was written by the lane's first session but never
committed; the successor session committed it with corrections, and
this finalizer lifts the provisional banner on the delivered J
evidence:

- Verify leg on J (z2-verify.log, HEAD d802f93): 22 tests / 195
  assertions, 2 failures, 0 errors. Both failures are W0's
  scoreboard-smoke contract deftest — its assertions demand
  measured scoreboard arms, which read "not built" at this pin;
  that is the known pre-existing W0-state failure every lane meets
  at these pins, not a Claim defect. The alloc-run requirement
  tests, the contract roundtrips, and the regression trio
  (alloc-growth-race, descriptor-table, keyring) are GREEN on J.
  The lane's local figures agree (gate-run.log: 19 tests / 158
  assertions / 0 failures; final-run.log: 24 tests / 721
  assertions, its failures likewise W0's own at this pin).
- Bench re-take on J (z2-bench.log, HEAD 29187a3, box load ~6.4):
  the table under Board row B1. The class-3 claim+copy gate
  (≥ 3×) is MISSED at 2.64×, confirming the contended first run's
  2.40× — the miss was not contention.

## What was built

`ctx-alloc-run!` in alloc.cljc, behind Wave 0's frozen contract
(`alloc/alloc-run!` + `ISlabRunAlloc`, W0.4):

- `find-run` — a cursor-led scan (wrapping once) for n consecutive
  clear bits in a class bitmap; bits at or past the class total read
  as set, and a run never wraps the class's end.
- `claim-run-at!` — the claim itself: **one CAS per bitmap word**,
  whole interior words by mask −1, head and tail words masked at both
  ends, so a run of any n at any start wastes no block (the research
  probe's claim started at the cursor's next word, threw when the run
  was not free, and lost up to 31 blocks a claim). A word lost mid-claim
  releases exactly the words already claimed and the search retries;
  no partial claim survives.
- On success: FREE_COUNT −= n atomically, cursor := start + n.
- The (bitmap-region, total) snapshot is certified the way
  `ctx-alloc!`'s try-alloc certifies its own (total read on both sides
  of the region read, torn pair re-read under the regions lock), so a
  concurrent remap can never pair an old bitmap with a new total.
- Growth and refusal: a fragmented class (free ≥ n, no run) answers
  nil and claims nothing; free < n on a heap class grows by
  replacement (`grow-heap-class!`, under the regions lock, 20 steps
  as ctx-alloc!) and re-searches. No spill into a larger class —
  placement fallback is the zip's own decision (Z3.3). A file-backed
  class's growth is not led from here (its cross-process discipline
  is Wave 2's H1); a run that fits its current geometry claims fine.
- Entry points: `JvmSlabCtx` implements `-sio-alloc-run!`;
  `LoggingSlabIO` implements it too — delegating to its inner sio and
  logging **every block of the run**, as `-sio-alloc!` logs its one
  block, so a claim inside a swap attempt (the zip's seat) is freed
  whole when the CAS loses. Any other sio keeps W0's per-block
  default.

## Per-requirement verdicts

Tests: `clojure -M:jvm-test -n eve.static.alloc-run-test` — in the
final merged session (with W0's namespaces and the regression trio):
the namespace ran with **0 failures** (full run: 24 tests, 721
assertions, 3 failures — all three in Wave 0's own tests, proven
pre-existing by running W0's tree standalone: same 3 failures there;
they are W0.6's not-yet-logged SPIKE-LOG and scoreboard-smoke state,
named as expected in W0-STATUS).

| Req                                   | Verdict                      | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------------------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Z2.1 run-is-consecutive-and-allocated | **PASS**                     | claim of 50 on a fresh class 2: offsets are exactly the consecutive blocks ascending, all `allocated?`, FREE_COUNT −50, 50 subsequent singles disjoint                                                                                                                                                                                                                                                                                                                               |
| Z2.2 run-wastes-nothing               | **PASS**                     | crafted pattern (one run of 40 at unaligned start 37, isolated free singles at 5/90/100): claim starts at 37 exactly, FREE_COUNT −40 exactly, the allocated set afterwards is the before-set ∪ the run — no bit outside the run changed                                                                                                                                                                                                                                              |
| Z2.3 no-run-claims-nothing            | **PASS**                     | fragmented class (50 free singles, none adjacent): claim of 4 answers nil, FREE_COUNT and allocated set untouched                                                                                                                                                                                                                                                                                                                                                                    |
| Z2.4 run-claim-contention             | **PASS**                     | 8 threads × 25 mixed run claims (n 1–8) and singles on one domain: no offset handed out twice, FREE_COUNT exact (total − free = handed out), every handed block allocated                                                                                                                                                                                                                                                                                                            |
| Z2.5 run-claim-across-growth          | **PASS**                     | claim of 4× the class's total on a tiny heap class: class grew, run consecutive and allocated, FREE_COUNT exact across the growth, class still claims and allocates singly after                                                                                                                                                                                                                                                                                                     |
| Z2.6 run-claim-encrypted              | **PASS, on the default arm** | through eve.keyring's EncryptedSlabIO the claim answers n blocks of the domain, all allocated; a claimed block writes/reads 123456789 through the keystream while the raw inner read differs. **Caveat:** EncryptedSlabIO implements no run claim and keyring.cljc is not this lane's file, so the encrypted claim is served by W0's per-block default, not the masked-CAS fast path. The fast path through a wrapper is a one-method `ISlabRunAlloc` delegation owed in eve.keyring |

Regression, same discipline: eve.alloc-growth-race-test,
eve.descriptor-table-test, eve.keyring-test green alongside (19
tests / 158 assertions in the gate-run session; alloc.cljc's existing
code paths are untouched by this lane — the change is additive plus
the two protocol implementations).

## Board row B1 — the gate

Z2's gate (IMPL-PLAN §3.3): claim+copy ≥ 3× per-block allocation, net
of the domain; the probe measured 3–9×. Bench:
`doc/static/z2/bench.clj` (probe-faithful: pre-sized heap domain,
copy by arraycopy into the heap array as probes/lib.clj ingest! does;
both arms in one session, min of 7 trials, each trial on a fresh
domain whose pages a whole-class alloc/free warmup committed — two
earlier bench forms are recorded as failures in Z2-STATUS: separate
arm loops, then subtraction netting, which lazy page commitment
makes unsound). Deciding run: the bench re-take on J
(z2-bench.log, HEAD 29187a3, box load ~6.4), superseding the
contended first run on this box (bench4.log; load 4.56 → 26.85;
class rows 4.18× / 5.42× / 2.40×):

| class             | per-block alloc+copy | alloc-only   | run claim+copy | ratio     | claim-only  | claim-only ratio | raw `-sio-alloc-run!` |
| ----------------- | -------------------- | ------------ | -------------- | --------- | ----------- | ---------------- | --------------------- |
| 0 (32B) × 20,000  | 516.1 ns/blk         | 458.9 ns/blk | 176.0 ns/blk   | **2.93×** | 88.6 ns/blk | **5.18×**        | 48.1 ns/blk           |
| 2 (128B) × 20,000 | 606.6 ns/blk         | 551.4 ns/blk | 68.5 ns/blk    | **8.85×** | 51.1 ns/blk | **10.78×**       | 32.8 ns/blk           |
| 3 (256B) × 5,000  | 319.0 ns/blk         | 413.4 ns/blk | 121.0 ns/blk   | **2.64×** | 85.9 ns/blk | **4.81×**        | 50.7 ns/blk           |

**Gate: the run claim beats per-block allocation on every class,
and claim-only passes ≥ 3× on all three classes — but the
class-3 claim+copy gate (≥ 3×) is MISSED at 2.64×, reported as
missed, not relabeled.** The re-take confirms the contended first
run's 2.40×: the miss was not contention. The raw claim primitive
underneath the contract surface (`-sio-alloc-run!` alone — the
row the first session's bench5/bench6 never produced; bench5 died
on a bench.clj paren error, since repaired) runs 32.8–50.7
ns/block: the gap between the raw row and claim+copy is the
offsets vector Wave 0's `alloc-run!` builds for its answer plus
the copy, spread over only 5,000 blocks on class 3. Lane Z3 does
not have to pay the vector cost — it can call `-sio-alloc-run!`
for the run's first offset.
B1's zip arm itself (≤ 50 ns/block) is Z3's row to fill.

## For the lanes that consume this

- Z3 (place): call `alloc/alloc-run!` for the contract answer, or
  `-sio-alloc-run!` on the sio for the first offset without the
  vector. Nil means: no run in the class as it stands — fall back to
  shorter runs, then singles (Z3.3); the domain's allocated set is
  unchanged either way. Inside a swap, claim through the attempt's
  logging sio: the run is logged whole.
- Z4/T1: nothing needed from Z2.
- F5 (counts at placement): untouched by this lane — the mechanism
  stays behind its flag, OFF; that verdict belongs to Count (Z4).
- H1 (disk domains): the claim runs on mmap geometry today (same
  code path; bitmap region is the class's .bm file); growth of a
  file-backed class from inside a claim is deliberately not led
  here — that is H1's to add under the two-process tests.
- eve.keyring: add `ISlabRunAlloc` to EncryptedSlabIO delegating to
  its inner sio (one method) and encrypted domains get the fast
  claim; until then they are correct via the default.

## Provenance

Tree: this branch, tip in git (report committed on it). Tool
versions: OpenJDK 21.0.13, Clojure CLI 1.12.6.1673, deps from the
seeded /home/hatch/m2repo (CLJ_CONFIG cljconfig). All figures from
this box on 2026-11-12 ET sessions named in Z2-STATUS.md and the
logs under doc/static/z2/ (gate-run.log, bench3.log, bench4.log,
bench5.log, final-run.log).
