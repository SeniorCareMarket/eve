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
- ITEM 3 (VS-seat writer re-check) — queued behind item 1; probe is
  the spikes reqatom compile (follow-up attempts 1-2 protocol) over
  this branchs corrected roots, against the pair installed on main
  at run time (builder sha recorded in the report).
- ITEM 4 (fast-disk phase-3 J cell) — gated on item 3s verdict and
  on the two cuts currently on this box draining; otherwise every
  number is labelled contention-tainted.
