# Journal cost exploration — STATUS (2026-10-28)

Lane: `claude/eve-j-cost-exploration` off `claude/eve-durability-spike`
tip `f596fcb9511` (static J, checkpoint floor). Mission: keep J's
semantics exactly; find why floored J costs ~5,400–9,700 µs/op at 300
commits in (no-floor J: 258–410; A ~900, phase-1) and why cost still
grows under the flush-advance floor; prototype the most promising fix
at small scale. Deliverable: `eve/doc/journal-cost-exploration-2026-10-28.md`.

## Starting facts (each traced to its bench file, not the summary doc)

- Phase-1 bench (pre-floor code, eve.durability-bench): A 898 µs/op,
  B 110,331, J1000 410 — all flat (spike report, phase-1 tables).
- Matched pair, one bench file at both revisions
  (`eve-journal-window-sweep` evidence `recon-bench-2026-10-27.txt`):
  rev 06cf2244a56 (pre-floor) 535 / 199 µs/op at warm 300 / 5000;
  rev aaf2fde5370 (floor fix ALONE) 9,658 at warm 300;
  rev f596fcb9511 (spike tip) 5,446 at warm 300 → 36,964 at warm 5000.
  The floor fix is the only commit-path change between the first two.
- Flush latency is NOT the variable: flush means 82–185 ms at every
  revision (recon file; floor-advance bench: 52–88 ms mean) → at
  J1000 that is ~50–180 µs/op amortized, an order below the effect.
- Flush-advance floor (branch claude/eve-floor-advance, runs in
  ~/workspace/floor-advance-runs): solo 8,365 / 12,794 / 14,807 µs/op
  at warm 300 / 5000 / 8000; matched trio warm 300: 379 (no floor) /
  9,311 (checkpoint) / 6,124 (flush-advance); trio warm 5000:
  258 / 46,712 / 11,198 (trio file says TRIO-DONE; no warm-8000 row).
  Crash suite 72/72 on the flush-advance floor; never-checkpoint kill
  case PASS with the flush-log bound method.

## Already isolated by the floor-advance lane (its STATUS, leg 2 — baseline, do not re-propose the floor itself)

- The retire zero-queue's flush discipline: every commit calls
  try-flush-retires!; once the queue passes 64 entries tally/flush!
  runs on nearly every commit and drain-scans the WHOLE queue,
  re-copying survivors. Prices track standing-queue length at
  ~5–12 µs per standing entry per commit; probe at 5,000 swaps:
  standing queue 1,003 entries (flush-advance) vs 57 (no floor).
- Its named follow-on (not built there): process only the
  epoch-eligible prefix of the queue.

## Code findings so far (this lane, from source at f596fcb9511)

- `eve.tally/flush!` (tally.cljc:440): drains the queue atom via CAS
  to [], samples safe AFTER the drain, reduces over EVERY entry
  (per entry: alloc/desc-of + flags + cnt loads + map destructure),
  collects survivors in a `stay` vector, then `(swap! q into @stay)`
  re-conjs every survivor — each standing entry is examined and
  copied ~twice per scan, freed or not.
- `try-flush-retires!` (atom.cljc:998): gate is
  `(or (> (- now @flush-ts) 50ms) (> (count @retire-q) 64))` — once a
  floor (or any lag) holds the queue past 64 permanently, the second
  disjunct is true on EVERY commit, so the whole-queue scan runs per
  commit. Called after every successful swap (atom.cljc:~1135).
- Queue stamps: every park site stamps the CURRENT root epoch at
  park time (atom.cljc:362 park-fn; tally.cljc:456 flush!'s park!).
  Root epoch is monotone, so fresh appends are stamp-ordered; BUT
  flush!'s own restore breaks the order: parks (stamp = now) are
  conj'd onto q DURING the scan, survivors (`stay`, older stamps)
  are appended AFTER → after any scan that parks, small stamps sit
  behind large ones. A prefix split must fix the restore order
  (stays ++ suffix ++ parks) to maintain the invariant it relies on.
  (Disorder is safe — per-entry freeing test unchanged, frees only
  delay — but it defeats prefix processing if left as-is.)
- The per-entry freeing test (flags/count claim via ccas, epoch <
  safe) is the semantics; any fix may change WHEN entries are
  examined, never the test. try-flush-retires!'s standing contract
  is "only ever delays a free."
- `mmap-min-safe-epoch` (atom.cljc:163): 256-slot scan (status +
  epoch load + heartbeat-stale? per slot), run once per flush!
  via flush-zero-queue!'s safe-fn. Size to measure; expected minor
  next to a ~1–2k-entry queue scan.

## Probe plan (REPL-first, small scale)

- P1 (micro): controlled standing queue — pin the journal floor by
  hand, build queues of known length, time tally/flush! directly →
  clean µs-per-standing-entry slope + safe-fn cost. Seconds per point.
- P2 (trajectory): J1000 counter, sample (queue length, µs/op) at
  cumulative 0/1k/2k/4k commits on spike code → the (a) shape.
- P3 (boundedness): same sampling on floor-advance code → is the
  standing queue bounded (~2 batches) or still creeping? Settles
  what (b) is made of.
- Prototype: prefix-bounded flush! (peek head → split at first
  stamp ≥ safe → process prefix only → restore stays ++ suffix ++
  parks) on this branch; bench-size J1000 warm 300 / 5000 before vs
  after (recon invocation); JVM atom suites + journal crash driver.

## Log

- 01:4x ET — read: architecture doc §§6–7; spike report (full);
  sweep reconciliation commit 7ef8fbfe045 + recon bench file;
  floor-advance STATUS legs 1–3 + both bench files. Branch +
  worktree created. Source read: tally/flush!, try-flush-retires!,
  mmap-min-safe-epoch, park sites, floor fix diff aaf2fde5370.
- 01:5x ET — branch banked and verified: local and origin tip both
  c4cac6880ca (STATUS commit) on spike tip f596fcb9511. Probes
  written (eve/bench/eve/jcost_probe.clj): probe1 = P1 micro
  (pure tally/flush! scan cost vs standing-queue length, floor
  pinned by hand; safe-scan cost; free-path price), probe2 = P2
  trajectory (J1000 counter, per-window µs/op next to queue length
  and floor). Next: run probe1, then probe2; P3 on the
  floor-advance worktree after.
- 01:5x ET — SIDE TASK (John's order, via coordinator): completed
  the measurements table in ~/workspace/eve-journal-architecture.html
  §9.3 with the raw numbers for the full commit-mode lines. §9.3 now
  has three labeled parts: Table 1 "Phase series (pre-floor code)",
  Table 2 "Durable-fix series (A / B / C)", then the pre-existing
  "Floor windows" table, unchanged. Nothing outside §9.3 was touched.
  Every figure is the spike report's own median of interleaved
  rounds, read from eve/doc/durability-spike-report-2026-10-27.md
  and spot-checked against raw evidence (j-single-r1.txt round
  values A 710.6 / B 113,904.2 / J1 111,129.6 / J10 11,847.1 µs/op,
  consistent with the reported medians):
  - Table 1, JVM this box (evening session; raw j-single-r1..r3.txt,
    j2-single-r1..r3.txt): counter A 898, B 110,331, J1 106,821,
    J10 11,832, J100 1,216, J1000 410; ~1 KB 6,792 / 64,202 /
    60,349 / 12,314 / 2,391 / 1,832; ~64 KB 5,610 / 63,476 /
    65,468 / 11,572 / 1,873 / 1,311. (J100/J1000 = batch-curve
    medians, anchors A 438 / J10 9,587 — report's note, carried.)
  - Table 1, JVM server j.slaa.us (raw server/server-single-r1..r2):
    counter 205 / 2,558 / 3,439 / 481 / 186 / 149; ~1 KB 982 /
    2,506 / 3,334 / 1,227 / 959 / 994; ~64 KB 856 / 2,524 / 3,543 /
    1,111 / 916 / 792.
  - Table 1, Node this box (raw node-j-single-r1..r2): counter
    270 / 118,755 / 128,796 / 12,754 / 2,127 / 1,141; ~1 KB 8,766 /
    97,351 / 67,761 / 21,661 / 19,795 / 17,270; ~64 KB 10,467 /
    87,143 / 78,825 / 27,704 / 30,996 / 24,028.
  - Table 1, JVM Mac (one round, run-bench.sh): counter (2000)
    75.5 / 328.8 / 4,311.3 / 505.1 / 126.2 / 61.4; ~1 KB (500)
    418.7 / 1,391.2 / 5,161.9 / 876.9 / 462.2 / 376.6; ~64 KB (200)
    625.6 / 1,734.5 / 4,634.3 / 769.3 / 1,214.9 / 669.9.
  - Table 2, JVM this box, morning session (raw single-r1..r3):
    A / B / C — counter (3000) 594 / 1,685 / 580; ~1 KB (1000)
    3,073 / 3,981 / 3,025; ~64 KB (300) 3,601 / 4,444 / 3,624.
    Table 2, Node (raw node-single-r1..r3): counter (A/C 1000,
    B 300) 68.7 / 85,161 / 9,089; ~1 KB (200) 2,098 / 50,338 /
    8,448; ~64 KB (50) 607 / 49,256 / 12,870.
  The caption of Table 2 states explicitly that B's 1,685 (morning
  disk) and 110,331 (evening disk, Table 1) are the same
  implementation on two disks and must not be averaged or
  interchanged. No cell was interpolated; no "not measured" cells
  arose (the report's phase tables are complete). Rendered with
  headless Chrome and the §9.3 region read from the screenshot:
  all six tables render complete and legible.
- 02:1x ET — CONTINUATION lane started (probes + prototype; the two
  earlier segments left both unrun/unbuilt). Source re-read firsthand:
  tally/flush! (tally.cljc:440), flush-zero-queue! (atom.cljc:364),
  try-flush-retires! (atom.cljc:998), park-fn (atom.cljc:362) — all as
  the STATUS code findings describe. tally/flush!'s ONLY caller is
  flush-zero-queue!. Prototype design settled from the source:
  sample safe once at entry; PEEK the queue head first — head stamp
  >= safe (safe non-nil) answers 0 without draining (the O(1) fast
  path); else CAS-drain, split at the first stamp >= safe, run the
  EXISTING reduce over the prefix only, restore in one swap! as
  stays ++ suffix ++ concurrent-parks (concurrent conjs carry the
  current epoch, the largest stamp, so they belong last — order
  preserved). Safe sampled before the drain is never less
  conservative: a pin registered after the sample carries an epoch
  >= the root epoch at sample time >= any sampled pin, and the floor
  only moves forward. The nil-safe multi-round path is unchanged
  (nil safe => whole queue is the prefix). Probe1 running
  (~/workspace/jcost-runs/probe1-before.txt).
