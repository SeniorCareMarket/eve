# Flat lanes — roster, waves, rules (race-nk Phase 0, 2026-11-17)

Operator, 2026-11-17 (verbatim): "lets fan this impl out. Make it a quick phase 0, where you stub out all the
files/phase lanes for impl plans. The a fanout across a 2 or 3 agents in wave 1 for 1 and 2, and a wave 2 for 3 and 4.
And then we'll let you collate the landing." The plan these lanes implement: ../PLAN.md.

| lane | wave | unit (PLAN.md) | repo · worktree · local branch | brief | ledger |
|---|---|---|---|---|---|
| U1 | 1 | 1 — the VS flat map, full API | valuescript · /home/user/w/wt/u1 · flat-u1 | U1-vs-full-api.md | status/U1.md |
| U2a | 1 | 2 — eve.flat-io: the seam, portable, chunk.cljc over it, conformance, the lookup | atom.broker (eve) · /home/user/w/eve-u2a · flat-u2a | U2a-flat-io-seam.md | status/U2a.md |
| U2b | 1 | 2 — eve.flat-io: the JVM and JS fast implementations, the three-host bench | atom.broker (eve) · /home/user/w/eve-u2b · flat-u2b | U2b-flat-io-fast.md | status/U2b.md |
| U3 | 2 | 3 — flat-io/vs, eve's flat layer on VS | both | U3-eve-flat-on-vs.md | status/U3.md |
| U4 | 2 | 4 — deftype / defrecord flat sisters, field reads as loads | both | U4-deftype-sisters.md | status/U4.md |

The seam's contract, which U2a, U2b and U3 implement and U1's core forms already follow: CONTRACT-flat-io.md.

## Rules every lane keeps (the parent CLAUDE.md is the conduct authority; these are the lane's own)
1. **Your worktree, your local branch, commit often, never push.** The coordinator (race-nk, the session that spawned
   you) merges and pushes. Do not touch /home/user/valuescript, /home/user/w/eve-ab or another lane's worktree.
2. **Write your ledger as you go**, append-only, newest at the tail: status/<lane>.md in YOUR worktree's copy of this
   directory (U2a / U2b: the same path under specs/ is not in eve's repo — keep your ledger at
   <worktree>/doc/flat/lanes/status/<lane>.md). Every entry: ET time (`TZ=America/New_York date`), what was measured,
   the command, the provenance line (repo + commit, tool versions, fresh-repro result).
3. **No builds that link or land, no whole suites, no universe mint.** VS lanes test through program-level probes on
   the installed pair (the R7 pair, /home/user/valuescript/bin/vs) and through the session (docs/procedure.md §1);
   the coordinator mints, links and lands. Eve lanes run the targeted tests their brief names (the JVM's
   :jvm-test alias with a namespace filter, bb's runner, Node's one target), never the whole battery.
4. **One heavy process at a time per lane** (a VS compile, a JVM test run, a Node build): four cores are shared by
   three lanes and the coordinator. Run long commands with a timeout; never launch anything detached.
5. **Defects are measurements.** A red you did not watch fail at your base is not yours to fix until re-derived
   (parent CLAUDE.md "Phantom defects"). A real defect in your files is your work; one outside them goes in your
   ledger with its repro for the coordinator.
6. **No JavaScript in VS product code** (parent rule 4a/16): VS code is .vs; tooling is bash or Python. Eve's JS
   adapter (flat-io/js) is ClojureScript, which eve's ClojureScript host compiles — that is eve's host code, as
   every other eve js adapter.
7. **Report when done**: the commits (hashes), the done bar's measurement with its numbers, what is not done and why.
