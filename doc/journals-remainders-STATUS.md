# Journals remainders — STATUS (lane claude/journals-remainders)

Base: origin/main @ 33add31123d9bf84b8602779b1f7622821d8e7df (ls-remote
verified at dispatch). All work on the build server j.slaa.us (writes
healthy there; the hatch box still refuses writes with EPERM).
Background: eve/doc/journals-follow-up-2026-10-28.md (items 1-4 state),
its runs/ evidence in the follow-up worktree on the hatch box.

- ITEM 2 (hosts.edn :vs thread root) — DONE at source, this commit.
  The tree has NO adapters/thread/vs (adapters are beam, browser,
  clr, glojure, jvm, node, none, python), so the row takes
  adapters/thread/none/src — the same root the durability spikes
  working compiles added and the :lg row already uses. It provides
  eve.thread-host (thread_host.cljc), the namespace attempt 1 of the
  follow-up item 3 died without. eve/deps.edn carries no :vs alias;
  hosts.edn is the only root list for the seat. EDN re-parsed after
  the edit: :vs = 16 roots, thread root present.
- ITEM 1 (lpy J10/kb64 tail, 9 points) — COMPLETE: totals line
  "10 pass, 0 violations" (server runs dir
  crash-lpy-j10-kb64.out, copy committed under
  eve/doc/journals-remainders-evidence/). The 9 re-run points —
  window-c1/c5/c9-nothing-forced, flush-after-sweep, flush-torn-
  100b, flush-torn-1030b, flush-done, post-flush-window-c11,
  window-c5-root-ahead — all PASS, read by the JVM reader from this
  tree. Basilisp was not needed: the recording was already banked;
  only the reader runs were missing. Lpy crash cells now stand
  54/54, 0 violations, matching CLR cell for cell.
  (John's question answered by measurement: nothing basilisp-
  specific was broken — the points had simply never run; the
  harness state writes died on the hatch box EPERM fault.)
- ITEM 3 (VS-seat writer re-check) — COMPLETE, verdict NO, wall
  MOVED. Reqatom compile on this tree (pair in tree
  02c86f56a24efd4e / 8f09c2cfcaae599a — the pair installed on main
  at run time; residence landing had not changed it), roots from
  the item-2-fixed hosts.edn: ran 18.9 min under load and died
  rc=75 at form 24209:
  "X 2 0 24209 macroexpand: Unable to resolve symbol: double? in
  this context" (log: eve/doc/journals-remainders-evidence/
  vs-reqatom-compile3.txt). Three blockers, distinguished:
  (1) thread-host (follow-up attempt 1, form 0) — closed by item 2;
  this run never names it. (2) translate-protos (spike, form
  18161) — NOT reproduced: the symbol appears 0 times in the log;
  the compile passed 24,209 forms, 6,048 past the spikes death
  point (the graph also grew with the journals landing).
  (3) NEW: double? — same class (a symbol the shipped builders
  macro evaluator cannot resolve during an eve macroexpand), new
  site. double? is written nowhere in eve/libs sources (one
  test-only hit, test/coop/orchestrator.clj), so it is produced
  by a macro expansion; naming the exact macro is the follow-ons
  first step (bisect the graph). Consequence per the spikes
  design: eve.atom's graph still does not compile on the shipped
  pair, so the VS seat cannot write + recover a journal on it —
  the writer stays unbuilt, no workaround attempted.
- ITEM 4 (fast-disk phase-3 J cell) — gated on item 3s verdict and
  on the two cuts currently on this box draining; otherwise every
  number is labelled contention-tainted.
