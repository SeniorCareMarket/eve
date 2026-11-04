# Journals remainders — completion report (2026-10-28)

Lane `claude/journals-remainders`, base origin/main @
33add31123d9bf84b8602779b1f7622821d8e7df. All work ran on the
build server j.slaa.us (its writes are healthy; the hatch box
still refuses writes with EPERM — the fault that stalled these
items in the follow-up lane). Every verdict below is read from a
log file named beside it; evidence copies are committed under
`eve/doc/journals-remainders-evidence/`.

## Item 1 — Basilisp J10/kb64 tail: COMPLETE (10 pass, 0 violations)

The cell's last 9 crash points re-ran on the server against the
follow-up lane's banked recording (manifest + snap-0..snap-11,
copied over intact; a server-adapted copy of
`runs/crash_suite_lpy.py` drove, the suite's JVM reader —
`eve.durability-crash-reader` from this tree — read every image):

- window-c1-nothing-forced, window-c5-nothing-forced,
  window-c9-nothing-forced, flush-after-sweep, flush-torn-100b,
  flush-torn-1030b, flush-done, post-flush-window-c11,
  window-c5-root-ahead — **all PASS**
- Totals line: `# totals: 10 pass, 0 violations` (with the
  previously banked p0-checkpoint;
  `journals-remainders-evidence/crash-lpy-j10-kb64.out`)

Lpy crash cells now stand **54/54, 0 violations**, cell-for-cell
equal to CLR. Basilisp itself never had to run: the recording was
already complete — only the reader passes were missing. Answer to
the operator's question, from the measurement: basilisp is not
uniquely broken on the hatch box, and nothing in this cell was a
basilisp bug at all — the points had simply never run, because
the harness's state writes died on the box's EPERM write fault.
The same harness, unchanged, runs the cell clean on the server.

## Item 2 — `:vs` hosts.edn thread root: COMPLETE at source

Commit `d7bb0afcc5` on this branch: `eve/hosts.edn`'s `:vs` row now
names `"adapters/thread/none/src"`.

Decision, from the tree: there is **no** `adapters/thread/vs`
(the adapters are beam, browser, clr, glojure, jvm, node, none,
python), so the row takes thread/none — the root every working
spike compile added by hand, and the one the `:lg` row already
ships. It provides `eve.thread-host` (`thread_host.cljc`), the
namespace the follow-up lane's item 3, attempt 1 died without
("Could not locate eve/thread_host … required by digest.cljc",
rc 75 at form 0). `eve/deps.edn` carries no `:vs` alias —
hosts.edn is the seat's only root list, so this one line is the
whole fix. The file re-parses as EDN after the edit; `:vs` reads
16 roots with the thread root present.

## Item 3 — VS-seat journal writer re-check: verdict NO — the wall MOVED

Protocol: the spike's require-only probe
(`reqatom_main.vs` = `(ns reqatom-main (:require [eve.atom :as
atom]))`, byte-identical to the follow-up lane's copy), compiled
with this tree's `vs/bin/vs` over this branch's corrected
hosts.edn roots, against the pair installed on main at run time —
**builder `02c86f56a24efd4e` / core `8f09c2cfcaae599a`**, shas
recorded in the run log's header (the residence landing had not
changed the pair). Ran 2026-10-29T00:19:04Z → 00:37:56Z
(18.9 min, under load), died rc 75:

```
X 2 0 24209 macroexpand: Unable to resolve symbol: double? in this context
```

(`journals-remainders-evidence/vs-reqatom-compile3.txt`, full
350-line log.)

Three blockers, distinguished:

1. **thread-host (follow-up attempt 1, form 0)** — closed by
   item 2. This run never names it.
2. **translate-protos (the spike's wall, form 18161, rc 75)** —
   NOT reproduced. The symbol appears 0 times in this log; the
   compile passed 24,209 forms — 6,048 beyond the spike's death
   point (the graph also grew with the journals landing). On the
   current pair, the EM4 held-half symptom the spike measured is
   gone.
3. **double? (new, form 24209, rc 75)** — the same class of
   failure: a symbol the shipped builder's macro evaluator cannot
   resolve while macroexpanding eve's graph. `double?` is written
   nowhere in eve's or libs' sources (one hit, test-only:
   `eve/test/coop/orchestrator.clj`), so it is produced by a macro
   expansion, not by an eve call site; the trace carries no
   namespace for the failing form. Naming the exact macro is the
   follow-on's first step (bisect the require graph).

**Verdict:** the VS seat does **not** write + recover a journal
correctly on the current pair — eve.atom's graph still does not
compile on the shipped builder, so no VS-seat writer can be built
or run. Per the spike's standing design the writer stays unbuilt
while the graph doesn't compile; no workaround was attempted.
This is a verdict, not a pass: the item closes with the wall
located at a new, precisely named site, 6,048 forms further than
the last measurement.

(Disclosure: the first launch script for this compile was mangled
by an unquoted heredoc — `$LOG` expanded at write time — and
never ran; it was rewritten and relaunched. A launcher defect,
not a compile attempt, and no evidence was produced or lost by
it.)

## Item 4 — fast-disk phase-3 J cell: DEFERRED (coordinator steer)

Not run. The operator has set the residence pair landing as the
priority, and this cell was gated on item 3 in any case; item 3's
verdict (the wall above) keeps the VS side of the question closed
for now. Prep banked on the server for the pickup:

- Server-adapted runner:
  `~/journals-remainders-runs/phase3_runner_j_server.py` (system
  JDK 21 / clojure paths; the cljr writer dropped — no cljr or
  dotnet exists on the server, and the follow-up lane's own
  inventory note records that JVM writers suit the disk
  comparison; the cell as adapted runs its two JVM writers).
- The setup phase was launched and then killed on the steer,
  mid-rec-100m (`setup rc 143`; classes rec-1k / rec-100k /
  rec-1m complete in `~/journals-remainders-runs/phase3/
  setup.log`, 297 MB written). The partial setup-domain was
  deleted: the runner's `setup()` skips when `setup-domain.root`
  exists, and a partial domain must never be mistaken for a
  ready one. The pickup re-runs setup from scratch, then the J
  cell. No phase-3 numbers exist; there is nothing
  contention-tainted to label.

## Regression bars

No eve code changed in this lane — the only source change is the
`:vs` root list in hosts.edn (the `:jvm` row untouched) — so the
follow-up lane's bars stand as last measured (JVM suites 47/419/0;
check-host-leak 0/0/1/0) and were not re-run.

## What remains open

1. The `double?` macro wall at form 24209 of the reqatom graph on
   pair 02c86f56 — the VS-seat journal writer's standing blocker,
   one named follow-on (bisect the graph, name the macro).
2. Item 4 (above), deferred behind the residence landing and
   that wall.
