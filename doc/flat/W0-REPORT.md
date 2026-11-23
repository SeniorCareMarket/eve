# Wave 0 — completion declaration (contracts and the board)

Lane: Wave 0 of `IMPL-PLAN.md` §3.2, requirements W0.1–W0.6. Branch
`claude/static-w0-contracts` in this tree, based on the research tip `7402ef9`.
The lane was finished by a successor after the first agent paused on the box's
load gate; every verdict below was re-verified in the successor's own runs.
All times US Eastern, 2026-11-12. No origin push exists (no GitHub credential
on this box); the coordinator collates by path.

## Per-requirement verdicts

| Req                       | Verdict                      | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ------------------------- | ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| W0.1 api-surface          | **PASS**                     | Scoped JVM suite green in three consecutive successor runs (final: 5 tests / 559 assertions / 0 failures / 0 errors, load1 6.17 at start). `eve.static` exposes detach/place/transfer!/pack/open/open-checked/bytes at the frozen arities, each delegating by requiring-resolve to its lane namespace and otherwise throwing a typed `{:eve.static/not-built …}` ex-info.                                                    |
| W0.2 requirements-covered | **PASS**                     | The `b31eaf2` hyphen-regex fix was re-run by the successor: requirements-covered is clean (the first successor run's only 2 failures were both in scoreboard-smoke; the second and third runs were fully green). The manifest (`test/eve/static/requirements.edn`) covers every requirement id in IMPL-PLAN.md — Waves 0–1 as numbered there, Wave 2's rows numbered H*.n in plan order — and every `:built` entry resolves. |
| W0.3 contract-roundtrip   | **PASS**                     | Same runs. Header (incl. flag bits, required-features byte, image layout-id in nsym, block/relocation table offsets), block table and relocation table round-trip exactly per FORMAT-V1 §2/§9; bad magic refused by name.                                                                                                                                                                                                    |
| W0.4 alloc-run-default    | **PASS**                     | Same runs, against a real heap domain: n blocks of the class answered by looping `-sio-alloc!`, each allocated (free! true, then false), overflow class throws. `alloc-run!` + `ISlabRunAlloc` live in `src/eve/deftype_proto/alloc.cljc`.                                                                                                                                                                                   |
| W0.5 scoreboard-smoke     | **PASS, after a correction** | See "Correction" below. The smoke runs all rows B1–B8 on the tiny corpora and appends the section; B4 is measured and stated as one run. Green in the successor's second and third scoped runs.                                                                                                                                                                                                                              |
| W0.6 baselines            | **PASS**                     | `doc/static/SPIKE-LOG.md` now carries corrected-scoreboard sections for all four corpora (map-10k re-run, vec-100k, records-10k, messages-5k), Transit arms populated. Figures and conditions below.                                                                                                                                                                                                                         |

## Correction — the earlier W0.5 PASS was a phantom

The partial report claimed W0.5 PASS from the first agent's run. That claim
does not reproduce at any committed state: the tests-first smoke test
(unchanged since `963e9bf`) asserted a flat row list from `run!` and grepped
the rendered section for the string `atom->atom`, but `run!` has returned
per-corpus results since the implementation commit `5eff26e`, and the renderer
never emitted that string. The successor's first re-run failed exactly those
2 assertions (559 assertions, 2 failures, both in scoreboard-smoke). The claim
is superseded by this declaration. Fixes (commit `6343c50`), neither weakening
the requirement's substance:

1. The smoke test flattens `:rows` across `run!`'s documented per-corpus
   results — the B1–B8 completeness assertion is unchanged in strength.
2. The scoreboard's rendered section now states in words that B4
   (atom->atom) is timed as one run. The measurement already was one run
   (`read-pinned` + `swap!` under `:foreign-policy :copy`, timed whole);
   the log now says so, which is what the test greps for.

## W0.6 baselines — the board's "today" column

Corrected scoreboard (warm-up sweep `915383d`, census-sized capacities
`cfa86c6`, one corpus per JVM, Transit via `-Sdeps` before `-Spath`), min of
3 runs, `--runs 3 --warmup 1`. Static arms are "not built" — these rows are
the incumbents Wave 1 lanes are judged against, within-session.

| corpus (section)              | B2 store today | B3 @a     | B4 atom→atom, ONE run | B6 Transit msgpack | B7 Transit decode | B8 whole-value gzip |
| ----------------------------- | -------------- | --------- | --------------------- | ------------------ | ----------------- | ------------------- |
| map 10k int->int (03:22 ET)   | 2329.38 ms     | 198.42 ms | **3898.34 ms**        | 59235 B            | 209.46 ms         | 23984 B             |
| vector 100k ints (03:25 ET)   | 2487.32 ms     | 102.02 ms | **3907.36 ms**        | 368549 B           | 193.79 ms         | 212000 B            |
| vector 10k records (03:31 ET) | 4102.48 ms     | 95.71 ms  | **9345.98 ms**        | 358526 B           | 1036.75 ms        | 65556 B             |
| 5k chat messages (04:00 ET)   | 6305.34 ms     | 282.73 ms | **4868.24 ms**        | 874085 B           | 285.61 ms         | 174004 B            |

Conditions, stated plainly: every run **started** under the load gate
(load1 ≤ 8: 7.41 / 7.82 / 6.04 / 7.60 at start), but this box is shared with
~13 sibling lanes and load rose during two measurements — the section
headers record render-time load1 of 5.71 (map), **18.56 (vec)**,
**23.73 (records)**, 8.22 (messages). The vec and records rows are therefore
contention-affected; they are labeled by their own headers in the log and are
not silently mixed with the cleaner map/messages rows. Min-of-3 dampens but
does not erase contention.

Two comparisons a reader must not make: the plan's "today" column (B4 ≈
206 ms) was a sum of parts from a warm, dedicated box — B4 here is one run,
as the plan requires, on a contended shared box. And the predecessor's cold
02:37 ET section (which stands in the log, labeled, with its Transit arms
missing) banked B4 map-10k = 1683.78 ms; the corrected warm re-run measures
3898.34 ms. The difference is box conditions, not a regression claim — no
code on the measured path changed between the two sections except the
scoreboard itself. One messages-5k attempt was killed mid-run by a runtime
restart and left no section; the 04:00 ET section is its clean re-run.

## What Wave 1 lanes must know about the frozen contracts

- **API** (`src/eve/static.cljc`): the seven verbs and their arities are frozen —
  detach [x], place [a image], transfer! [a x], pack [x], open [b],
  open-checked [b], bytes [s]. To land a verb, create its namespace
  (`eve.static.image`, `.place`, `.transfer`, `.chunk`, `.check`) with the var of
  the same name; the stub picks it up by requiring-resolve, no edit here needed.
- **Contract** (`src/eve/static/contract.cljc`): header read/write, flag
  accessors (wide?/digest?/types?/required-features-of), layout-id (an image's
  nsym field), block/relocation table read/write, and the constants (header-size
  32, kinds, chunk-target 65536, seal threshold 65535, layouts 1/2).
- **alloc-run!** (`eve.deftype-proto.alloc`): `(alloc-run! sio class-idx n)` →
  vector of n slab offsets, ascending, or nil from a run-claiming sio with no
  free run; the default loops `-sio-alloc!` (allocated, not consecutive). Z2
  implements `ISlabRunAlloc/-sio-alloc-run!` for JvmSlabCtx; wrappers that don't
  implement it keep the default. Class 6 (overflow) throws.
- **Manifest** (`test/eve/static/requirements.edn`): your lane's test vars are
  pre-registered as `:planned`; when your tests land, flip your entries to
  `:built` in your own commit — requirements-covered then resolves them.
- **Scoreboard** (`test/eve/static/scoreboard.clj`): run one corpus per JVM
  (`--corpus`), Transit via `-Sdeps` BEFORE `-Spath`; rows append to
  doc/static/SPIKE-LOG.md. B4's incumbent arm is `eve.atom/read-pinned` +
  swap! under `:foreign-policy :copy`, timed as one run.

## Not run

- The full Eve JVM suite (`clojure -M:jvm-test`, all namespaces): the banked
  resume instructions made it conditional on load permitting. Sustained load
  through the lane's final hours (5-minute averages 21–25) never permitted a
  run whose results would be attributable. The lane's own scoped suite —
  which loads the full deftype/atom stack and exercises `alloc-run!` against
  a real domain — is green three consecutive runs at the final tree.
