# Lane Z4 STATUS — the count taken at the zip (fork F5)

Lane: Wave 1 Z4 of `IMPL-PLAN.md` §3.3 (requirements Z4.1–Z4.5, board row B2).
Branch `claude/static-z4-counts`, base research tip `7402ef9` + W0 contracts
merged (`cfa86c6`, via path remote `w0`). Tree `/home/hatch/workspace/static-eve/z4`.
All times US Eastern. No GitHub push (no credential on this box — coordinator
collates locally); commits are local.

## Pins and tools (2026-11-12)

| thing          | value                                                                                                                                                                                                                           |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| tree           | SeniorCareMarket/eve @ `7402ef9`, + W0 `cfa86c6` merged                                                                                                                                                                         |
| java / clojure | OpenJDK 21.0.13 · Clojure CLI 1.12.6.1673 (`/home/hatch/.local/bin/clojure`)                                                                                                                                                    |
| deps           | `CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig` → `:mvn/local-repo "/home/hatch/m2repo"`                                                                                                                                   |
| test runner    | `clojure -X:jvm-test`, scoped `-n eve.static.tally-count-test` while iterating                                                                                                                                                  |
| test names     | fixed by W0's `test/eve/static/requirements.edn`: ns `eve.static.tally-count-test`, vars `written-counts-equal-walked`, `written-counts-exact`, `written-counts-release-clean`, `written-counts-shared`, `flag-off-is-the-walk` |

## Load gate

Box load1 11–17 through the first hours (gate: no heavy run starts above 8).
Reading/design/tests-first writing proceeds; test runs and the B2 bench wait
for dips.

## The design (from tally.cljc + atom.cljc at this pin)

- The commit seam is `eve.tally/hold-root!` (atom.cljc's swap attempt calls
  it once, before the CAS). Z4 changes only `src/eve/tally.cljc`.
- New: `*count-at-place*` (the F5 flag, dynamic, default false),
  `register-placed!` (called by place inside the swap fn; no-op with the
  flag off), `forget-placed!`, and a `placed-write-count` instrument so
  tests can prove the written path — not the walk — ran.
- Registration: `{off kind}` blocks + `[owner target]` edges (the image's
  relocation table, resolved to target-domain offsets), keyed by
  `(alloc/domain-root sio)` identity + root offset (the parkers pattern;
  `domain-root` unwraps the attempt's logging sio to the same root object
  `hold-root!` sees). Consumed by the first `hold-root!` of that root;
  `drop-root!` forgets any residue.
- `hold-root!` with the flag on and a registration present: validate
  (root in the table; every edge inside the table; every named block
  allocated, rowed, uncounted — no PUB, count word 0), then write. Any
  doubt → the registration is dropped and the walk decides. A pre-existing
  counted block fails validation by construction, so written counts can
  never stomp a live count.
- The write itself mirrors the walk exactly: propagate from the root
  through readable blocks only (a block held as a leaf is counted, not
  descended — `readable?` decides from the placed bytes, as `hold!`
  does); count = arrivals (one per edge, duplicates kept) + 1 for the
  root (the slot); PUB on every counted block, LEAF beside it where the
  walk would set it; `note-touch!` per block so durable commits force
  the same descriptor rows the walk's writes would. Blocks the
  propagation never reaches are left untouched, as the walk leaves them.
- Lost CAS / won CAS need nothing new: written state is indistinguishable
  from walked state, so `drop-root!` + the flush cascade unwind it by the
  existing machinery. A throw in the swap fn lands before `hold-root!`:
  no counts written, placed blocks uncounted — the walk arm's exact state
  at the same point.

## Log

- 01:55 ET — tree cloned, branch made; plan §3/§3.3-Z4/§5-R1/§7-F5,
  MEASUREMENTS M5–M7, FORMAT-V1 §2/§9, tally.cljc and the atom commit
  path read firsthand. W0 contracts merged. Tests-first commit next.
- ~03:5x ET — second successor: inherited tip 66d1931 (implementation +
  three re-derived harness fixes banked; no test results reported).
- ~04:0x ET — requirement tests run at tip 66d1931, own run, load window:
  `clojure -M:jvm-test -n eve.static.tally-count-test` → **5 tests,
  83 assertions, 0 failures, 0 errors.** Z4.1–Z4.5 all green as written.
- B2 bench attempts: (1) -Xmx4g run died silently after the header —
  no rows, no error file; box memory was ~184 MB free at the time
  (presumed kernel OOM under fleet pressure, unproven). (2) -Xmx2g run
  under load1 26.81: map-10k row printed (placement 1283.30 ms, walk
  0.21×, written 0.64×) then OutOfMemoryError in make-heap-region on
  vec-100k. The map-10k ratios invert the arms' expected order
  (placement alone slower than placement+walk) — a load-storm artifact,
  NOT a measurement; the row is not banked.
- Bench fix (15c9cc8): optional corpus filter, one JVM per corpus —
  whole-suite runs cannot complete under this box's memory pressure.
- 04:41 ET — B2 messages slice COMPLETED (commit 15c9cc8, -Xmx2g,
  min of 5 after 2 warmup, load1 3.86 at start rising to ~24 mid-run):
  70,997 blocks; placement alone 2957.20 ms; placed swap! walk
  8484.55 ms (2.87×); placed swap! written 9977.79 ms (3.37×) —
  **gate ≤2×: MISS**; written/walk = 1.18×. Row banked in
  doc/static/SPIKE-LOG.md (c799720) with its load history.
- Confirmation attempts: a second messages-slice run and the
  interleaved hold-root!-only probe (doc/static/z4_probe.clj, 9e051cf)
  were each killed mid-run by runtime restarts (three restarts in the
  hour) before printing any number. Not banked at the time; the other
  three corpora's B2 rows remain owed as board completeness only.
- 2026-11-12 (close-out) — the interleaved probe RAN CLEAN on J
  (log f5-probe4.log, read firsthand by the coordinator): ABAB at
  stable load1 ~8.5–9.0, hold-root!-only, 70,997 blocks, rows equal
  (written == walked). Walk min 161.57 ms / median 170.79 ms;
  written min 391.26 ms / median 885.12 ms — written 2.4× slower at
  best, ~5× at median. The confound is gone and the margins got
  worse. **F5 verdict FINAL: NOT ADOPTED** — counts stay walked,
  `*count-at-place*` stays behind its flag, OFF. Z4-REPORT.md
  updated ("The decider" section); the owed probe item is closed.
- Lane complete: verdict in Z4-REPORT.md.
