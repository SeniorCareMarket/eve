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
- ITEM 1 (lpy J10/kb64 tail, 9 points) — IN FLIGHT: banked recording
  (manifest + snap-0..11, p0 PASS) copied from the follow-up
  worktree; server-adapted copy of runs/crash_suite_lpy.py re-runs
  the suite, which skips banked points. JVM reader = the suite
  reader (eve.durability-crash-reader) on this tree.
- ITEM 3 (VS-seat writer re-check) — queued behind item 1; probe is
  the spikes reqatom compile (follow-up attempts 1-2 protocol) over
  this branchs corrected roots, against the pair installed on main
  at run time (builder sha recorded in the report).
- ITEM 4 (fast-disk phase-3 J cell) — gated on item 3s verdict and
  on the two cuts currently on this box draining; otherwise every
  number is labelled contention-tainted.
