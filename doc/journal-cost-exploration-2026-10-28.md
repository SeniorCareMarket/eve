# Journal cost exploration — why floored J costs what it costs, and the fix

Lane `claude/eve-j-cost-exploration`, 2026-10-28. Base: spike tip
`f596fcb9511` (static J, checkpoint floor). All bench outputs are
banked in `eve/doc/journal-cost-evidence/` on this branch; the
running log is `journal-cost-exploration-STATUS.md`.

## The question

Two charges, kept separate:

- **(a) The constant factor.** Floored J1000 measures
  ~5,400–9,700 µs/op at 300 commits in, where no-floor J measured
  258–410 and A ~900. What in the commit path costs that?
- **(b) The residual growth.** Under the flush-advance floor the
  decay is bounded but cost still rises ~1.8× across a run. What
  accumulates?

## The answer

One mechanism explains most of both: **the retire zero-queue's
flush discipline.** Every commit calls `try-flush-retires!`
(atom.cljc:998); once the queue passes 64 entries it invokes
`tally/flush!` on nearly every commit, and `flush!` (tally.cljc:440)
drain-scans and re-copies the **whole** standing queue — at ~5 µs
per standing entry — whether or not any entry can be freed. A
retention floor's whole effect on the queue is to hold entries
standing: ~1,000 under either floor rule, growing without bound
under the checkpoint rule, 57 with no floor. Floored J therefore
pays ~(standing queue) × ~5 µs on every commit. The journal
itself — records, forces, append — was never the cost: flush
latency is 18–185 ms per flush at every revision (~20–180 µs/op
amortized at batch 1000, and flat).

The prototype in §4 removes the term: floored J1000 on the
checkpoint floor measures **759 µs/op at warm 300 and 417 µs/op
at warm 5000** (before: 13,204 / 36,778, same invocation, same
night) — flat, at phase-1's no-floor price (410), with J's
semantics unchanged.

## 1. The mechanism, measured three ways

**Micro (probe1, `eve/bench/eve/jcost_probe.clj`).** Floor pinned
by hand so every standing entry is ineligible; `flush-zero-queue!`
timed against controlled queue lengths. Pure drain-scan: 1,163 µs
at 250 entries → 18,894 µs at 4,000 — **≈4.7 µs per standing
entry**, linear, zero frees. The free path (floor lifted past all
stamps) frees at 5.6–7.3 µs/entry. Fixed costs are small: the
safe-epoch scan (256 registry slots) ≈ 10 µs; an empty-queue
flush ≈ 10–12 µs. So a floored commit's price is, to first order,
5 µs × queue length + ~10 µs.

**Trajectory (probe2).** J1000 counter on checkpoint-floor code,
1,000-commit windows, queue sampled per window: 987 µs/op at
queue 3; then every window adds exactly 1,000 standing entries
(the floor, pinned at epoch 1001 by the first flush, never moves)
and ~+6,500 µs/op — 3,548 @ 1,003 · 9,609 @ 2,003 · 16,052 @
3,003 · 22,869 @ 4,003 · 28,751 @ 5,003. In-path slope ≈ **6.3 µs
per standing entry per commit**, bracketing the micro constant
from the other side. Cost (a) and the checkpoint floor's growth
are this one line.

**Boundedness (probe3, floor-advance tree).** Same sampling on
`claude/eve-floor-advance`: the queue goes 3 → 1,003 at window 1
and stays 1,003 through window 7 (8,000 commits); the floor
advances 1001→7001. Cost plateaus at 10.1–12.9 ms/op with no
trend. So the flush-advance floor fixes the queue's **size**
(bounded at one batch) but not its **price**: the plateau is the
whole-queue scan of the bounded queue, ~11 µs/entry/commit on
that tree. Charge (b)'s residual growth under flush-advance is
the approach to this plateau, not a second accumulator.

## 2. Options, ranked

1. **Prefix-bounded `flush!`** — examine only the epoch-eligible
   prefix of the queue; when the head is ineligible, examine
   nothing. Attacks the price term directly (per-commit flush
   cost becomes ~10 µs + the eligible prefix, which is empty in
   steady state under any floor). **Built and measured — §3/§4.**
2. **Flush-advance floor** (sibling lane, built) — advances the
   floor at every flush, bounding the queue at ~one batch.
   Complementary to (1), not a substitute: it caps the scan's
   input but leaves the per-commit scan of it in place (the
   ~11 ms/op plateau above). (1) without (2) already restores
   flat cost on the checkpoint floor; (2) without (1) leaves
   cost ~25× above raw. Together they bound both factors.
3. **Gate tuning in `try-flush-retires!`** (raise the 64-entry
   threshold, lengthen the 50 ms interval) — scans less often,
   but each scan still costs the whole queue, frees are delayed
   (the standing contract permits delay, not incorrectness), and
   the queue's memory footprint grows between scans. A palliative;
   does not change the shape.
4. **Cheaper per-entry scan** (avoid the survivor re-copy, leaner
   per-entry work) — attacks the ~5 µs constant, not the
   O(queue) shape. Made moot by (1) for the floored cases that
   matter; the no-floor path already self-cleans at queue ~2–57.

## 3. The prototype (option 1), as built

One function changed: `eve.tally/flush!` on this branch
(commit `efeab102bb9`). `flush!`'s only caller is
`flush-zero-queue!`.

- **Safe is sampled once at entry** (was: after the drain). A pin
  registered after the sample carries an epoch at or above the
  sampled safe epoch — a pin is the root epoch its reader
  observed, and the root epoch never goes back — and the floor
  only moves forward, so sampling early is never less
  conservative.
- **Head peek.** If the queue's head entry is stamped at or above
  safe (safe non-nil), no entry can pass the epoch test: the
  flush returns 0 without draining. This is the steady state
  under any floor — per-commit cost ~11 µs (safe-fn + a deref).
- **Prefix split.** Otherwise the queue is drained as before and
  split at the first stamp ≥ safe; the **existing reduce runs
  over the prefix only**. The per-entry freeing test — QUEUED /
  TAKEN / count claim via `ccas`, epoch < safe — is byte-for-byte
  the shipped test. Only *when* entries are examined changes.
- **Restore repairs the order.** The shipped restore left the
  queue disordered (parks conj'd during the scan, survivors
  appended after them — small stamps behind large ones, which by
  itself would defeat prefix processing). The prototype restores
  in one `swap!` as **stays ++ suffix ++ parks**: stays carry the
  smallest stamps, the unexamined suffix keeps its order, and
  every park — the scan's or a concurrent conj's — carries the
  current epoch, the largest stamp there is. Order in ⇒ order
  out, inductively.
- The nil-safe path (no reader pinned, no floor) is unchanged in
  behavior: the whole queue is the prefix, including the
  multi-round re-run over parked blocks that frees a dropped tree
  whole.

## 4. Prototype results

Bench: `eve.durability-bench` `bench-size`, J1000 counter, the
reconciliation invocation, this machine, same night, before vs
after (`bench-before.txt` / `bench-after.txt`):

| window | before | after | factor |
|---|---|---|---|
| warm 300, n 3000 | 13,204 µs/op | **759 µs/op** | 17× |
| warm 5000, n 3000 | 36,778 µs/op | **417 µs/op** | 88× |

Flat: the deep window costs what the shallow one does — phase-1's
no-floor J1000 price (410 µs/op) on the checkpoint floor.
Probe1-after confirms the mechanism directly: an all-ineligible
queue of 500–4,000 entries now flushes in 9–27 µs at every length
(before: 2,369 → 22,158 µs, linear).

**Correctness.** JVM suites on the prototype — `eve.tally-test`,
`eve.jvm-atom-test`, `eve.jvm-atom-cas-test`,
`eve.jvm-atom-lost-attempt-test`, `eve.jvm-atom-retire-test`:
47 tests, 419 assertions, **0 failures, 0 errors**
(`jvm-suites-after.txt`). Journal crash-suite cells against this
tree (spike's `crash_suite.py`, path-patched copy): see STATUS
log tail / `crash-proto-*.txt` evidence — the floor-advance
branch's 72/72 is the bar.

## 5. Caveats

- This box prices commits very differently across VM instances
  (the floor lane's note); tonight's absolute µs values are
  tonight's. Every before/after pair above is same-night,
  same-invocation, and the *shapes* (linear vs flat) are the
  claim, not the decimals.
- probe1's scratch "directory" is a file prefix (domain files are
  `probe1.root`, `probe1.slab0`, …), so its delete never fired and
  runs after the first resumed the previous domain; phases 1–2
  are per-process and unaffected, and the first (fresh) run holds
  the no-floor sanity reading. Details in the STATUS.
- The restore still copies the suffix on any flush that drains.
  In steady state under a floor the head peek means drains (and
  copies) happen only when the floor has advanced past the head —
  per floor-advance, not per commit. Under no floor, behavior and
  cost are as shipped.
- The prototype is on the spike base (checkpoint floor). The
  end-state composition — prefix flush + flush-advance floor —
  is predicted to hold J at raw prices with a bounded queue, but
  that exact combination has not been measured; it is a
  follow-on, as is any landing decision (adoption remains the
  operator's call; nothing here lands to main).
