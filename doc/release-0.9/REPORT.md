# eve 0.9 — lane report, 2026-10-21

Session `session_01VA9egFFp7vNEsCkSj2zFKA`, 6:52–7:20 PM ET. The final pass that followed was finished on 2026-10-22 by session
`session_01KMUtvFR1sxQD4sMtJGPP7U` on a second box, 2:09–5:17 AM ET (§8). Times are US Eastern. Everything below is in the private
repository johnmn3/atom.broker; nothing of this session's work went to a public repository except the push probe in §5.

On 2026-10-22 checklist 9.34 was landed and every battery re-run on it (9:24 AM – 2:10 PM ET, the same session and box): §0 is
that declaration, basilisp's full run first.

## 0. Declaration — 9.34 landed, every battery re-run on it (2026-10-22)

**basilisp's full battery on the fixed tree: 180 tests, 4,350 assertions, 0 failures, 7 errors — red.**
`bb test:lpy`, alone on the box, 9:28–10:25 AM ET (56 min, peak 1.2 GB; `logs-2026-10-22/f3-lpy.log`). On the tree
before the patch it was 180 / 4,360 with 5 errors on this box (`f-lpy.log`, 2:19–3:16 AM ET), and 180 / 4,413 with 1
failure and 1 error on the first box, 24–59% faster (`f-lpy-box1.log`: fold's chunk-size timing bar, and 9.26).

- **9.34's patch does what it says** [ran]. Operator, 2026-10-22: "Land checklist 9.34's patch … exactly as written in
  the checklist — attach! cleans up after itself when it fails and the thread tests detach in a finally". Landed as
  `fe9dc0c1`. basilisp's threads were sampled every 30 s through the run (`f3-lpy-threads.log`): the domain pool test's
  second `attach!` started its ticker at 10:01:47, burned 69 s of CPU waiting for a first beat, and was gone by 10:04:34
  — the failed `attach!` stopped it; the test's other handle's ticker (started 9:58:48) was gone by 10:05:04 — the
  `finally` detached it. Before the patch such tickers beat on for the life of the process, 10–20% of a core each.
- **What is left is 9.26, and a second leak (9.35).** All seven errors are eve.thread.domain's 120 s waits running out
  late in the run: the domain pool over a network atom (9.26, `:ticker-up`), the gateway (`:pumps-started`), doorbells
  and the drivers ("pool :g's threads did not start" — both green in the run before the patch), and fold on a group's
  three (the same). Doorbells and the drivers failed with nothing of an earlier test running beside them (the sampler:
  only threads at 0 s of CPU besides theirs), so the late-run slowness alone takes basilisp past the 120 s budgets: the
  r2 ticker above spent 69 s of CPU on one network swap. That is 9.26's "a process that has run the whole battery is
  slower still" — not profiled. Fold on a group's fixture leaks the way the network tests did (9.35, new): it has no
  `finally`, and a failed `pool` leaves its threads running — its first test's threads (10:17) were still running at the
  run's end (10:25).
- **The two namespaces alone on the fixed tree, green**: `bb test:lpy eve.thread-net-test` 5 tests, 47 assertions, 0
  failures, 0 errors (1,214 s; `f3-lpy-net`); `bb test:lpy eve.thread-fold-group-test` 3 / 49, green (1,016 s;
  `f3-lpy-fold-group`) — as in the final pass (1,233 s, 953 s). The tests are sound; the run they fail in is long.

**Every other battery on the fixed tree** (one leg at a time, `logs-2026-10-22/f3-*.log`, `runs.tsv`):

| leg | result | wall |
|---|---|---|
| Node: `npm run test:compile`, then the final pass's 28 suites (f3-node-*) | every suite green, each count the final pass's (`all` 482 / 34,980; `net` 62 / 1,429; `slab-grow` 105 / 28,902; `thread-domain` 12 / 146; `thread-fold-group` 3 / 49) | 49 s + 1–329 s each |
| `clojure -M:jvm-test` (f3-jvm-2) | 546 tests, 9,826 assertions, 0 failures, 0 errors | 191 s |
| `bb test:bb` (f3-bb) | 247 / 4,748, green | 417 s |
| `bb test:bb-minimal` (f3-bb-minimal) | 242 / 4,623, green | 402 s |
| `bb test:nbb` (f3-nbb) | 242 / 4,617, green | 684 s |
| `bb test:cljr` (f3-cljr) | 202 / 4,499, green | 230 s |
| `bb test:jolt` (f3-jolt) | 199 / 4,479, green | 261 s |
| `bb test:clje` (f3-clje) | 198 / 4,400, green; process-tree peak 11.0 GB | 1,221 s |
| `bb test:glj` (f3-glj) | 182 / 4,458, green | 684 s |
| `bb test:browser` (f3-browser) | 48 / 633, green | 62 s |
| `bb test:vs` (f3-vs) | 260 passed, 0 failed | 216 s |
| `bb scripts/thread-rendezvous.clj jvm jolt` (f3-rv-jvm-jolt) | `:ok true` | 65 s |
| `bb scripts/thread-rendezvous.clj` — the other eleven pairs (jvm–jvm, jvm–nbb, nbb–jvm, jvm–bb, bb–jvm, jvm–clje, jvm–cljr, jvm–lpy, jvm–glj, glj–jvm, glj–glj; f3-rv-*) | each `:ok true` | 19–203 s |
| `bb scripts/thread-rotation.clj jvm glj bb nbb clje` (f3-rotation) | `:ok true` | 91 s |
| `bb scripts/thread-fold-rendezvous.clj bb bb` (f3-fold-rendezvous) | `:ok true` | 6 s |

The JVM's first leg (f3-jvm) stopped at classpath resolution, no test run: Maven Central answered 429 after the
session's start rewrote `~/.m2/settings.xml` without the mirror (10:25 AM ET); the mirror given again, f3-jvm-2 ran.
Every count but basilisp's equals the final pass's on the tree before the patch (the JVM's tests too; its
assertions move run to run — jvm-fuzz-test asserts per generated case: 9,615 in the final pass).

**What this declares.** 9.34 is fixed (`fe9dc0c1`) and every battery passes on its tree as it did before — every
host's, every rendezvous, the rotation, the fold rendezvous — except basilisp's full run, which stays red: by 9.26 (a
basilisp process that has run most of the battery takes eve.thread.domain past its 120 s waits — unprofiled) and by
9.35 (fold on a group's fixture and a failed `pool` leave threads running, the leak 9.34 closed in the network tests).
Next, in the checklist's own sizes: 9.35's patch (**S**), then 9.26's profile of a late-run swap (**M**).

## 1. What was done

The brief received at 6:52 PM ET ("EVE 0.9 — SHIP THE TREE THAT PASSES THE CORPUS") was changed by the operator at about
7:02 PM ET: copy the 0.9 lane's code onto the new codebase and keep its fixes and optimizations, without a porting
campaign; and by 7:15 PM ET: nothing goes public yet.

The 0.9 lane (`claude/eve-0.9-release`, retired 6:55 PM ET) had worked on a base, `112f6ced2`, that `origin/main` has
since moved 176 commits past (first counted as 151 on a shallow clone; 176 once the history was deepened). None of
those commits touched `eve/`. So the lane's `eve/` went onto main whole, with
nothing to merge:

| commit (branch `claude/keen-feynman-60t1jk`, cut from `origin/main` `f65b23153`, rebased onto `f8cfdb0dd` — below) | what |
|---|---|
| `6f1d2f5d` | the lane's `eve/` at its tip `36cc025ad`, byte-identical when copied (eve tree `5a846123741c`, 185 files changed against main's `eve/`) |
| `3b804f01` | the lane's declaration report, `docs/port-gap-closure/coordination/reports/eve-0.9-release-2026-10-20.md` (split from the copy at the rebase: one sub-repo a commit) |
| `a56a00a7` | the lane's change list, `docs/port-gap-closure/coordination/reports/eve-0.9-lane-changes.md` — cherry-picked from `748470496` (branch `claude/amazing-allen-baau4o`), the retiring session's last work |
| `b1df4d3e` | the change list's head: where every item now lives, what the codec re-run found |
| `574471d8`, `fa1d02c7`, `f47495bd`, this commit | STATUS entries, this report, its logs (`logs-2026-10-21/`) |

**Rebased (2026-10-22, 12:30 AM ET, operator: "Pull main and rebase on that").** `origin/main` had moved 80 commits
to `f8cfdb0dd`, the repo-split preparation: the private material moved under `atom-broker/`; in `eve/` it changed six
path references (`services/atom-broker` → `atom-broker/services/atom-broker` in four docs, `eve.net.auth`'s docstring
and its test's), no code; it rebuilt VS's kernel (`tally.vs`, `retire.vs`, `program_runtime.vs`, `valuescript.wasm`).
The rebase applied cleanly; the three commits that spanned two of the three sub-repos (`eve/`, `vs/`, the rest:
atom.broker's) were split into one commit each. The branch's tree after it is `origin/main` plus this lane's work, and
its `eve/` differs from the tree measured before it only in those six lines. The logs name the commit each run was on
before the rebase; `logs-2026-10-22/rebase-map.tsv` maps each to its rebased commit.

The carried code is every item of the change list: 18 shared-code fixes, 13 host fixes, 6 performance changes, the new
surfaces (browser http adapter, the browser as an eve.thread runtime, Node's http-server, the npm package files, the perf
suite, the eve-media capstone), the tests, and the docs. The branch's `eve/` differs from the lane's tip only in
`doc/release-0.9/` (STATUS, this report, its logs).

Not carried: the lane's notes in `vs/docs/memory-model.md` (+8 lines) and `vs/docs/contention-domains.md` (+17). main
rewrote both and has its own "THE TALLY" sections; the lane's notes describe VSv2's count as the `retire.vs` hold ledger,
which main has replaced with `kernel/wasm/tally.vs`. Carrying them would put wrong statements back.

`src/vs`: 0 JavaScript files, 0 bridges — unchanged (this lane touched `eve/` and `docs/` only; main's pre-commit gates
passed on every commit).

## 2. What was measured

| check | result | where |
|---|---|---|
| `bb test:vs` — eve's shared tests compiled by **main's** builder, run under wasmtime | **260 passed, 0 failed**, 97 s (7:16:45–7:18:22 PM ET) | `logs-2026-10-21/test-vs.branch.log` |
| `eve/` reads anything outside `eve/`? (`hosts.edn`, `deps.edn`, `bb.edn`, `shadow-cljs.edn`, `package.json`, and every `.clj*`/`.edn`/`.sh`/`.vs` source, for `../vs`, `../src`, `../../`) | nothing in those — **but** two test fixtures were symlinks into `vs/` (`test/eve/fixtures/vsv2-blocks.edn`, `vsv2-blocks-256.edn`: `git ls-files -s` mode 120000), which that search could not see; on `eve/` alone they dangle and the ClojureScript test bundle fails at load. Now copies (2026-10-22, 1:05 AM ET), the only two symlinks `eve/` had | this session, 7:15 PM ET; the symlinks found 12:58 AM ET |
| main's pre-commit gates (dangling imports, wasm ratchet, intern table, the JavaScript rule — 205 admitted, host leak) | OK on all six commits | commit hooks |
| `vs/toolchain/eve-kernel --check --set codec` | **red on main's own `eve/` and on this branch** — §3 | `logs-2026-10-21/eve-kernel-*` |

`bb test:vs` is the one eve battery that runs main's code. Every other host battery runs `eve/` alone, which reads nothing
outside itself. At 7:20 PM ET they stood as the lane's measurements on `38b96e21b` / `fcb91485e` (the same `eve/`
source), not re-run here. **The final pass re-ran them all on this branch** (2026-10-21 10:56 PM ET onward, one run at a
time on one 4-core, 15 GB box; logs `logs-2026-10-22/`, list `runs.tsv`), 0 failures and 0 errors each: Node all
482 / 34,980; browser 48 / 633; slab-grow 105 / 28,902; deftype 63 / 710; net 62 / 1,429; JVM 546 / 9,615; bb
247 / 4,748; bb-minimal 242 / 4,623; nbb 242 / 4,617; ClojureCLR 202 / 4,499; jolt 199 / 4,479; clojerl 198 / 4,400
(its first full green with 9.31; process-tree peak 11.05 GB, not investigated); glojure 182 / 4,458 and its
core.rrb-vector suite (21 min); VS 260 / 0 again on main `f8cfdb0dd`. Also the twelve two-process rendezvous pairs
(`:ok true` each), the rotation, the fold rendezvous and perf-all on all eleven hosts. basilisp's battery, run on a
fresh box after the first was lost (§8): 180 / 4,360, 0 failures, **5 errors** — 9.26's and four its failed tests'
tickers caused (9.34); its network and fold-group namespaces alone green (5 / 47, 3 / 49). Over the fixes made after
midnight (bb's file lock, glj-eve's region, clojerl's NIF) bb, bb-minimal, glojure and clojerl ran again: the same
counts, green. Each change-list item's proof, run there: §7.

## 3. The one red: VS's codec test set (main's own, first)

The change list's integration notes 1.1 and 1.10 say to regenerate VS's codec test set after carrying `eve.net.codec`
and `eve.net.bytes` (the only namespaces this lane changed that any VS kernel set reads; the shipped set,
`eve.hamt-util`, and the xxh3 set read none). Run one at a time, 7:07–7:13 PM ET:

- **main's own `eve/`** (worktree at `f65b23153`): 7 refusals — `eve.net.bytes/HEX-VAL`, `hex-digit`, `unhex` call
  `vs.core/rt-kvmap-own/1`; `eve.net.u64/mul`, `mul128`, `eve.net.xxh3/len17to128`, `len129to240` call
  `vs.core/rt-drop-own/1`: "no kernel function (eve-core's callee-table)" — then `RUN-TRIP form 293 … wat->vs: a
  statement between an instruction's operands and the instruction`. Exit 1, 94 s.
- **this branch**: the same refusals as far as it got (the three `eve.net.bytes`, the two `u64`), then a wasm
  `unreachable` trap in a `forms` batch. Exit 1, 144 s.

Cause of the refusals: main's compiler emits `rt-kvmap-own` and `rt-drop-own` since `35eaba70` (2026-10-20, the EM7
last-use pass), and eve-core's callee table (`vs/toolchain/eve-kernel.vs:114`) maps neither to a kernel function. The
fix is in VS's shipped kernel — eve-core functions in `src/vs/kernel/wasm/eve_core.vs`, their callee-table rows, a kernel
landing — then the two failures behind them (main's wat->vs trip, this branch's trap). That is VS work on a red main
already had, outside the copy this lane was scoped to; **not fixed here**. While it stands, `run-codec --build` and
`run-xxh3` cannot build their test kernels on main, with or without this `eve/`, and whether the lane's
`eve.net.codec` change adds a failure of its own stays hidden behind main's.

Isolated at 10:54 PM ET (`runs.tsv`'s `codec-isolation-B` row; its log was never committed — `eve/.gitignore`
ignores `*.log` — and was lost with the first box, 2026-10-22): this branch's `eve/` with main's
`eve.net.codec` and `eve.net.bytes` put back fails exactly as main's own `eve/` does — the same seven refusals, then
`RUN-TRIP form 293` (exit 1, 82 s). So nothing else the lane changed reaches the codec set; its two namespaces turn
main's failure into the earlier wasm trap. They make more of the set run at load — `eve.net.bytes`'s `HEX2` table is
computed when the namespace loads, and main's `coll-rec` still holds four of `eve.net.codec`'s top-level forms in its
body (item 1.1), which the lane's file defines at top level — so the trap may be the same missing callee rows reached
sooner; which form traps is not isolated while the refusals stand in front of it.

## 4. Rulings

Given by the operator this session, verbatim:

- "I'm not interested in a massive porting process. Mostly just copy the new code over. And import the optimizations
  and fixes found by the old 0.9 lane." · "Pull those notes. Make sure you salvage their work. We just want that work on
  the right codebase from the beginning, which is the new stuff."
- "I don't want you pushing anything publicly yet." · "Stop pushing there... We're not going public yet.."
- "Just finish this lane"

None asked.

## 5. Hashes

| repository | ref | hash | note |
|---|---|---|---|
| johnmn3/atom.broker (private) | `claude/adoring-hypatia-1zww0c` | the commit carrying this report | this lane's work, continued (session_01KMUtvFR1sxQD4sMtJGPP7U, 2026-10-22): fast-forwarded from `claude/keen-feynman-60t1jk` |
| | `claude/keen-feynman-60t1jk` | `8af95be9` | this lane's work to the previous session's last push (2026-10-22, ~2:05 AM ET) |
| | `claude/eve-0.9-release` | `837c6815` | the 0.9 lane; its STATUS points here |
| | `main` | `f8cfdb0dd` | rebased onto (2026-10-22, 12:30 AM ET); not merged into |
| SeniorCareMarket/eve (**public**) | `main` | `10760f1` | untouched (2026-03-25); no tag |
| | `claude/eve-0.9-release` | `bb1655e` | pushed 6:34 PM ET by the 0.9 lane's session: the whole `eve/` at `fcb91485e`, internal docs included; no credentials (scanned this session, before 7:15 PM ET). **Deleted by the operator** (reported 2026-10-22, ~12:15 AM ET) |
| | `claude/push-probe-60t1jk` | `7a9a5cf` | pushed 6:53 PM ET by this session: one file, `.push-probe`, one line. **Deleted by the operator** (the same) |

The session could not delete either (the git proxy refuses branch deletion, HTTP 403); the operator removed both. The operator
has saved them to the private repository, where they belonged; the lane's own history is there too
(`claude/eve-0.9-release`, `837c6815`, holds `fcb91485e`, the tree `bb1655e` was made from).
`git ls-remote` of SeniorCareMarket/eve at 12:17 AM ET, 2026-10-22: 26 branches, neither among them; `main` still
`10760f1`. The local clone of SeniorCareMarket/eve has its push URL set to `DISABLED-not-public-yet`.

## 6. Not done, and why

- **The corpus** (every `eve/test` leg and the VS-side checks, main against main + this `eve/`): set up with every host
  runtime installed, stopped by the operator at about 7:02 PM ET before any leg finished.
- **The release** (the standalone branch, hygiene, version 0.9.0, clean-clone verify, merge, tag): not going public
  (operator). The brief's five operator lines (`doc/release-0.9/`, `doc/unification/`, `doc/network-atoms/`,
  `doc/hosts/valuescript.md`: ship or drop; `eve.net.auth`'s issuer: keep or configurable) were blank as received.
- **The codec test set**: §3.
- **Open from the 0.9 lane, carried with its code** (change list 1.15): 9.31's tally keeps its counts in count pages
  found from the `.root` header's word 32 and takes the domain's exclusive hold on a write that raises one; VSv2's
  kernel tally (`kernel/wasm/tally.vs`) keeps an out-of-band count table; VS's disk participant (`eve_disk.vs`) reads
  neither. The operator's 2026-10-21 ask to move eve's count onto the block's descriptor was not started by that lane.
- **9.26 and 9.34** (RELEASE-CHECKLIST): basilisp's network-rung ticker answers late in a full run, and a failed
  `attach!` leaves its ticker running, which on basilisp turns that one error into five. 9.34's patch is written out
  in the checklist; it is a shared-code change, not made in the final pass (it would re-open every battery measured
  on this tree).
- **clojerl's 11 GB**: its battery's process tree peaks at 11.1–11.6 GB on a 15 GB box; not investigated.
- **Merging into `main`**: not instructed.

## 7. Every item of the change list, checked

Each item of `docs/port-gap-closure/coordination/reports/eve-0.9-lane-changes.md`, two ways. **Code**: of the lines the
item's commits added, how many are on this branch (the rest were replaced later in the lane, by the commits named; a
lane commit's line that later lane work rewrote is not lost). **Proof**: the item's proving check as the change list
names it, run in the final pass on the final tree (2026-10-21 10:56 PM ET onward, one run at a time; each leg's log is
`logs-2026-10-22/<leg>.log`). ✓ = ran green. "PENDING" = the leg had not finished when this table was written.

| item | code on the branch | proof in the final pass |
|---|---|---|
| 1.1 codec forms out of `coll-rec` | 12 / 12 | ✓ `bb test:bb eve.thread-net-test` alone 5 / 45 (f-bb-thread-net); bb-minimal + http 5 / 45. VS's codec set: red, main's own (§3) |
| 1.2 glojure's records | 17 / 17 | ✓ `bb test:glj` 182 / 4,458 (f-glj) |
| 1.3, 1.4 long past int32; a swap answering its value | 48 / 48 | ✓ `remote_atom_test` in Node's `net` 62 / 1,429 (f-node-net) and the JVM's 546 / 9,615 (f-jvm) |
| 1.5 gateway docstring | 5 / 5 | — (a docstring) |
| 1.6 clojerl's merkle constants | 28 / 28 | ✓ `bb test:clje` 198 / 4,400 (f-clje) |
| 1.7 a writer frees its path copies | 17 / 18 (1 by `2397b2d6`) | ✓ `vec_retire_test` in every battery |
| 1.8 int32 field past 2^31 | 1 / 1 | ✓ the same |
| 1.9 a cancelled schedule | 5 / 5 | ✓ `bb test:lpy eve.domain-open-test eve.thread-test` 16 / 159 (f-lpy-open-thread); eve.thread-test green in basilisp's full battery (f-lpy) |
| 1.10 `hex` by a table | 2 / 2 | ✓ every host's net suites; basilisp's 32-byte `hex` 1.04 ms (f-probe-lpy-costs); `bb test:lpy eve.thread-net-test` alone 5 / 47 (f-lpy-net) |
| 1.11 9.25, transients reclaim | 577 / 716 (109 by `2397b2d6`, 2 by `a3693674`, 10 by `f6bb7f1a`, 18 by `08b94cbf`) | ✓ `reclaim_test` in every battery; `jvm_atom_retire_test` (f-jvm); leak probe int-map `assoc` 560 / 700 / 531 / 636 blocks over 20–160 swaps, flat (f-leak-probe) |
| 1.12 a list at an atom root | 22 / 22 | ✓ `jvm_atom_retire_test` (f-jvm) |
| 1.13 slab-io conformance on VS | 1 / 1 | ✓ `bb test:vs` 260 passed, 0 failed, main `f8cfdb0dd`'s builder (f-vs) |
| 1.14 9.30, a user's eve-deftype | 9 / 102 (88 by `2397b2d6`, 5 by `a3693674`: 9.31's one walk) | ✓ `jvm_atom_retire_test`: a 5,000-node tree, one insert, walk 0.163 ms against a whole-value reach 24.997 ms (f-jvm) |
| 1.15 9.31, the tally | 599 / 639 (40 by `2397b2d6`) | ✓ `tally_test` in every battery; `jvm_atom_retire_test` (f-jvm). VS's disk participant reads no counts: open (§6) |
| 1.16 module slab's default sio | a removal: gone | ✓ `tally_test` on Node, slab 63 / 2,863 (f-node-slab) |
| 1.17 the walk and a NIL slot | 85 / 85 | ✓ `thread-domain` 12 / 146 (f-node-thread-domain) |
| 1.18 the codec's stored-piece report | 35 / 35 | ✓ `tally_test` on Node (f-node-slab) |
| 2.1 glojure's root; bb's paths | 25 / 25 | ✓ f-glj; `bb run atom-swap` (f-bb-atom-swap) |
| 2.2 the JDK server's `:stop` | 12 / 12 | ✓ the JVM exits 1 s after `:stop`; the lane's base still running at 60 s (f-http-stop) |
| 2.3 glj-eve's GOGC | 3 / 3 | ✓ `bb test:glj` peak 2.8 GB, the process tree (182 tests; the 7.1 → 1.2 GB was a 149-test battery) |
| 2.4 jolt's heap regions | 40 / 41 (1 by `80fa591f`) | ✓ `bb test:jolt` peak 1.7 GB (f-jolt) |
| 2.5 Node's heartbeat unref'd; a browser worker closes | 9 / 9 | ✓ `bb test:nbb` 242 / 4,617, peak 4.7 GB (f-nbb); `bb test:browser` 48 / 633 (f-browser) |
| 2.6 glojure's `sequence` / `dedupe` / `partition-all` | 40 / 40 | ✓ eve.thread-fold-test in f-glj |
| 2.7 basilisp's arrays, sorted maps | 79 / 79 | ✓ `eve.reference-tag-test` and `eve.thread-net-test` load and run in basilisp's full battery (f-lpy, 180 tests; the latter's two errors are 9.26's and 9.34's, past loading) and alone (f-lpy-net) |
| 2.8 basilisp's double evaluation | 2 / 2 | ✓ eve.domain-open-test in f-lpy-open-thread (16 / 159) and in f-lpy |
| 2.9 basilisp's `str` | 7 / 7 | ✓ `(str "a" "b")` 6.08 µs (f-probe-lpy-costs; 77 µs before); the gateway test 239 s alone (f-lpy-net; the item says 229 s) |
| 2.10 clojerl's type hints | 2 / 2 | ✓ f-clje loads and runs |
| 2.11 jolt's relative domain path | 1 / 1 | ✓ f-jolt |
| 2.12 bb's transient hash map | 11 / 11 | ✓ `reclaim_test` on bb, 247 / 4,748 (f-bb) |
| 2.13 the emulated 64-bit store | 1 / 1 | ✓ `slab-grow` 105 / 28,902 (f-node-slab-grow) |
| 3.1 the JVM without reflection | 92 / 94 (2 by `2397b2d6`) | ✓ a counter `swap!` 12.0 µs alone (perf-jvm-2: its log lost with the first box, uncommitted; its row in `runs.tsv`), 11.8 µs in perf-all; a volatile get 19.1 ns, a CAS 48.9 ns, by `invokeWithArguments` 139.8 ns (f-probe-varhandle); JVM battery 136 s; reflection left: 155 warnings, all core.rrb-vector's (f-reflect; the item says 146 node-field reads) |
| 3.2 growable module slabs | 147 / 158 (11 by `2397b2d6`) | ✓ Chromium 141: 15 fixed 1 GB buffers, then refused; 400 growable 1 GB-maximum beside them; a worker 3 × 256 MB more — 15.75 GB, one cap per renderer (f-probe-sab-cap); growable on Node 0.95–1.24× of fixed (median 1.06×; f-node-perf-*); `slab-grow` ✓ |
| 3.3 a transient pays for what it frees | 128 / 128 | ✓ perf-all, every host's transient rows |
| 3.4 9.31's cost | 89 / 89 | ✓ JVM `atom-reset-vec-1k` 1.54 ms (perf-all; the item says 1.67 ms after, 1.54 ms before 9.31) |
| 3.5 `hex`, basilisp's `str` | (1.10, 2.9) | ✓ 1.04 ms, 6.08 µs (f-probe-lpy-costs) |
| 3.6 memory | (2.3–2.5) | ✓ glj 2.8 GB, jolt 1.7 GB, nbb 4.7 GB (process trees) |
| 4.1 the browser http adapter | 153 / 158 (5 by `b721eb5a`) | ✓ f-browser; the capstone's core and extended runs, the browser's library and re-queue rows true (f-capstone-verify) |
| 4.2 the browser as an eve.thread runtime | 25 / 25 | ✓ `bb test:browser` 48 / 633, `:main` all true (f-browser) |
| 4.3 Node's http-server | 42 / 42 | ✓ a POST answered as text, a GET as bytes, the URL's `%2F` kept, `:stop` (f-probe-node-http-server — no committed test runs it: the ladder and the capstone serve from the JVM or bb) |
| 4.4 the npm package's files | 15 / 15 | ✓ `npm pack --dry-run`: 197 files, 667.9 kB packed — `adapters/` (no `target/`), `src/`, `native/`, the README, CHANGELOG, licenses (f-npm-pack; the version still 1.0.0, checklist 1.1) |
| 4.5 the perf suite | 206 / 223 (4 by `70f9a1b2`, 13 by `acf4c26f`) | ✓ `bb scripts/perf-all.clj`, all 11 hosts (perf-all; basilisp's alone, f-perf-lpy) |
| 4.6 the eve-media capstone | 3,210 / 3,647 (130 by `a9ab3a62`, 34 by `cf298704`, 26 by `ba99b61c`, 47 by `24c53bc8`, 7 by `febb1646`, 11 by `8d16e8c7`, 182 by `2ce25bcb`) | ✓ `bb eve-media verify` PASSED: six runs, every check and comparison row true (f-capstone-verify, 2026-10-22 4:43–5:00 AM ET, over the post-midnight macOS changes); the macOS port's parts run here (f-mac-*: glojure's and clojerl's futex-free waits 20 / 153 each, glj-eve cross-built for darwin) |
| 5.1 named namespaces on nbb, cljr, jolt | 9 / 9 | ✓ `bb test:nbb eve.thread-net-test` 5 / 48 (f-probe-nbb-rss); cljr and jolt on one namespace (f-start-cljr, f-start-jolt) |
| 5.2 runners skip nothing unexplained | 63 / 67 (4 by `2397b2d6`) | ✓ every battery |
| 5.3 the new suites | 606 / 650 (23 by `a3693674`, 9 by `ca70b7c3`, 7 by `2397b2d6`, 5 by `38b96e21`) | ✓ every battery |
| 5.4 the leak probe | 41 / 41 | ✓ f-leak-probe |

## 8. The final pass's end (2026-10-22, 2:09–5:17 AM ET, a second box)

The first box was lost at about 2:05 AM ET with the previous session, mid-way through basilisp's battery; nothing
pushed was lost. Lost with it: that battery, its queued re-runs, a results sheet and two doc agents' unsaved edits —
and four earlier runs' logs that had never been committed (`eve/.gitignore` ignores `*.log`; `codec-isolation-B` and
`perf-jvm-2` are cited above, their rows stand in `runs.tsv`). The branch, fast-forwarded to `claude/keen-feynman-60t1jk`
`8af95be9`, continues as `claude/adoring-hypatia-1zww0c`. Every host runtime reinstalled in 7 min; Maven Central
answered 429 again, and `~/.m2/settings.xml` took Google's Central mirror as on 2026-10-20.

**What ran, one leg at a time** (`logs-2026-10-22/`, `runs.tsv`; eve's code unchanged this session):

| leg | result | wall |
|---|---|---|
| `bb test:lpy` (f-lpy) | 180 tests, 4,360 assertions, 0 failures, **5 errors** — 9.26, then four 9.34 caused | 57 min |
| `bb test:lpy eve.thread-net-test` (f-lpy-net) | 5 / 47, green (the domain pool 501 s, the gateway 239 s) | 21 min |
| `bb test:lpy eve.thread-fold-group-test` (f-lpy-fold-group) | 3 / 49, green | 16 min |
| `bb test:lpy eve.domain-open-test eve.thread-test` (f-lpy-open-thread) | 16 / 159, green | 114 s |
| `bb test:bb`, over `28e2c611` (f2-bb) | 247 / 4,748, green | 417 s |
| `bb test:bb-minimal`, the same (f2-bb-minimal) | 242 / 4,623, green | 392 s |
| `bb test:glj`, over `4eac2122e` (f2-glj) | 182 / 4,458, green | 753 s (glj-eve built) |
| `bb test:clje`, the same (f2-clje) | 198 / 4,400, green; process-tree peak 11.6 GB | 1,297 s |
| `bb eve-media verify` (f-capstone-verify) | **PASSED**: six runs, every check and comparison row true | 1,046 s |
| `bb test:bb` on the tree before `28e2c611` / HEAD again (f-ab-bb-*) | 247 / 4,748 each | 407 s / 411 s |
| `npm pack --dry-run` (f-npm-pack) | 197 files, 667.9 kB packed, 2.5 MB unpacked | 3 s |
| probes: clojerl's costs, glojure's costs, the macOS ffmpeg check | measured (below); the ffmpeg check green | seconds |

The second box is slower: every re-run took 24–59% longer than on the first. The A/B puts that on the box, not on
`28e2c611`: bb's battery on this box took 407 s before the change and 417 s and 411 s after it.

**9.34, found here.** basilisp's full battery errs where 9.26 always has (the network rung's ticker answers past
`attach!`'s 120 s late in the run), and then four more times. Read in `src/eve/thread/domain.cljc:476-490`: a failed
`attach!` never stops the ticker it started, and the network tests detach their handles only at their ends, so the
failed tests' tickers and pumps beat on — six of their threads alive at 3:10 AM ET, one at 40% of a core under the GIL
— and the gateway test and fold on a group's three tests could not start their threads. Each namespace alone is green.
The patch is in RELEASE-CHECKLIST 9.34; it is a shared-code change and was not made in the final pass.

**Docs held to the final pass's own logs**, where they read otherwise (each commit names its log): babashka's `swap!`
on an eve atom fails at seven extra arguments, not eight; clojerl's NIF array calls take 0.035 / 0.040 µs from compiled
code (the docs had ~1.3 µs) and the binding frame's round trip 2.4 µs (6.5 µs); basilisp's `dotimes` iteration 1.8 µs
(~35 µs), an RRB vector of 40,000 built in 46 s and an assoc 10.9 ms (100 s, 55 ms), four threads 1.17× serial,
`interleave` past Python's recursion limit at 1,000 elements, `require eve.alpha` 51 s cold; growable slab buffers a
median 1.06× of fixed on Node (0.95–1.24×; the browser page had 1.05–1.32×); glojure's core.rrb-vector suite 21 min;
the JVM's VarHandle costs; CHANGELOG's commit count (314 / 374 did not reproduce: it counted this branch's history,
which holds the lane's `eve/` as one commit — on the lane's own branch 315 to 2026-10-20 ET, 340 through `36cc025ad`).
The two cost probes' first runs had measured nothing (clojerl opened a REPL; glojure has no `deftype`); both fixed and
run. Every host page's "this tree" rows now cite the final pass's log.

**Rulings asked and given this session**: none. One decision is the operator's: whether to land 9.34's patch now
(re-running every battery on it) or carry it with 9.26. Decided at 9:24 AM ET: landed, every battery re-run on it (§0).
