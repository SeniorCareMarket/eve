# Eve 0.9 durability spike — STATUS

Branch: `claude/eve-durability-spike` off main `d2f163f056c69a96177849409d8913e793b0bf9a`
(ls-remote verified 2026-10-27). Worktree: `~/workspace/wt/eve-durability-spike`.
All changes confined to `eve/`. SPIKE — nothing lands to main from this branch.

## Problem anchors — verified firsthand 2026-10-27

- `eve/adapters/region/jvm-varhandle/src/eve/region.clj`: `JvmByteBufferRegion`
  (line 230) does CAS via VarHandle with **no `.force` anywhere in the type**.
  Every `.force` in the file is inside `LustreJvmMmapRegion` (line 428+) —
  Lustre mode only. `open-mmap-region` (line 365) returns `JvmByteBufferRegion`.
- `eve/src/eve/atom.cljc`: the commit in `swap-attempt!` is one
  `mem/-cas-i32!` on `root-r` at `ptr-off`, preceded by `tally/hold-root!`,
  followed by the epoch `-add-i32!`. No force before or after.
  `release-domain!` / `close-atom-domain!` stop the heartbeat and release the
  slot/sio — no sync. No public `sync!`/`flush!` durability API exists
  (`eve.tally/flush!` is the retire-queue flush — reclamation, not durability).
- Consequence (unchanged from the validated read): process crash safe
  (MAP_SHARED page cache), system crash / power loss unordered — the root's
  page can reach disk before the value pages it references; reopen checks only
  a type-id tag at the root. No checksum, no previous-root fallback.

## Fix design (JVM host only)

1. `eve.mem`: new small protocol `ISyncableRegion` with `-sync!`
   (`[r]` whole region, `[r byte-off len]` scoped) + a `sync-region!` helper
   that no-ops for regions not implementing it (heap/SAB/other-host regions),
   following the existing `IHostAtomics` separate-protocol precedent.
2. `eve.region` (jvm-varhandle adapter): `JvmByteBufferRegion` implements it —
   `MappedByteBuffer.force()` / `.force(off, len)` when the buffer is mapped,
   no-op for a heap buffer. `LustreJvmMmapRegion` likewise (its `mapped-buf`).
3. `eve.deftype-proto.alloc`: `sync-sio!` — forces every region the slab ctx
   holds (`regions`, `bitmap-regions`, `desc-regions`, nils skipped).
4. `eve.atom` `swap-attempt!` commit ordering, durable disk domains:
   (a) value written as today; (b) `sync-sio!` — force the slab ranges the
   commit wrote (**spike form: whole slab/bitmap/descriptor regions — the
   stated upper bound**; written-range scoping is the production refinement);
   (c) root CAS as today; (d) scoped force of the root range covering the
   atom slot pointer and the epoch word, after the epoch bump.
   Gated by a `:durable?` option on `persistent-atom-domain` (default true) —
   the option also gives the group-commit bench variant its shape for free.
5. `eve.atom`: public `sync!` on an atom or domain — forces the whole mapped
   set (sio regions + rmap + root). `release-domain!` also syncs a disk
   domain before releasing it (close was one of the named holes).

Node port (report only): the Node addon's Lustre path already has
`msync(MS_SYNC)`; the default Node region needs the same sync op wired to it.

## Measurement plan

- JVM, eve's own classpath (`eve/deps.edn`), persistent atom in a scratch dir
  under `~/workspace`. Variants: A = pristine main (separate worktree),
  B = this fix (per-commit force), C = fix present, `:durable? false` +
  explicit `sync!` every N swaps (group commit) — only if it falls out.
- Sizes: small (counter), ~1 KB, ~64 KB values. Interleaved A/B rounds,
  µs/op + ops/sec, raw output banked under `eve/doc/durability-spike-evidence/`.
- Regression: eve's JVM atom test subset on the fixed branch.

## Scope addition (John, 2026-10-27 ~12:05 ET)

- Contention correctness: run eve's cross-process cooperative-atom suite
  (PR #199 line) on the FIXED branch — multiple processes on one shared disk
  atom must stay correct with force-ordering in the commit path.
- Contention perf diff A vs B: 4 processes × N swaps on ONE shared disk
  atom, interleaved rounds, aggregate ops/sec + µs/op, evidence banked.
- Report must address the fix's contention mechanics: where the force sits
  relative to the CAS retry loop, what a lost CAS re-forces, and what forcing
  changes about contention on the (lock-free) default path.

## Remainders (named, out of spike scope)

- Allocator bitmap crash-consistency as a design (the spike forces bitmaps
  with everything else; it does not redesign their recovery).
- Node host port of the sync op.
- Power-loss simulation: kill -9 does NOT simulate power loss for mmap;
  no such test is claimed.

## RECOVERY FLOOR FIXES (John's ruling 2026-10-27) — built, proof running

Both phase-2 fixes approved and implemented on this branch
(commit aaf2fde5370), in John's order:
- **(a) Anchor record** — `journal-anchor!` replaces checkpoint
  truncation: the checkpoint epoch's record is regenerated from the
  scan's newest and rewritten as the journal's sole record; the
  journal is never empty while a checkpoint exists. Recovery's rule
  is now uniform (install from the journal whenever a valid record
  exists). Evidence: journal crash tests reopen correctly (J1 15/15,
  J10 8 = the journaled prefix, torn+garbage 3/3) and the journal
  after recovery+close is **2068 B** (one anchor), was 0. Node CLJS:
  bench domains close to 2068 B journals identically.
- **(b) Retention floor** — journal state gains `:floor`: the oldest
  epoch recovery may still install. Set at recovery from the scan's
  new `:oldest` (both hosts' scans now report it; live joiners set it
  read-only), pinned at the first flush into an empty journal,
  advanced ONLY at checkpoint inside `journal-anchor!` under the
  journal lock. `flush-zero-queue!`'s safe epoch — the retire path's
  existing input — is now min(reader pins, floor): the journal pins
  the floor epoch like a slow reader. `get-min-active-epoch` (the
  reader-facing query) deliberately untouched.
- Regressions: JVM suites **44 tests / 210 assertions, 0 fail**
  (identical counts to pre-fix). 
- PROOF COMPLETE: full phase-2 crash suite re-recorded + re-run on
  the fixed code — **72/72: B 18/18, J1 34/34, J10 20/20** (pre-fix
  outputs archived in crash-suite-prefix/). All seven violations
  closed: anchor → J1 root-ahead ×2 + J10 window-c5-root-ahead ×2;
  retention floor → J10 kb64 flush-after-sweep / flush-torn-100b /
  flush-torn-1030b. One harness correction along the way: the
  suite's J10 window compositions truncated the modeled journal to
  0 bytes (the pre-fix checkpoint state); they now compose the
  anchor (man[0]) — expectations unchanged, and the two
  window-c5 points that "failed" under the stale composition pass.
  Addendum gained a "Floor fixes" section. Branch tip for the
  fixes + proof: 28629a5b9e5.
- Phase 3 RESUMED on the fixed code (no J cells had been measured
  pre-fix, so nothing needed re-running; A cells never touched the
  journal).

## Phase 3 — extreme multi-runtime contention, 5 s window (RESUMED)

RUNTIME CENSUS (each probed firsthand: open a journal domain, 2 swaps,
sync!, read back — unless noted):
- **JVM (Clojure)** ✓ journal. **Node (compiled CLJS)** ✓ journal.
  **nbb** ✓ journal (SCI-interpreted CLJS; its written domain verified
  readable from the JVM afterwards). **Basilisp / lpy** ✓ journal
  (installed into a venv — system pip is PEP-668-locked; run via the
  repo's own -p-roots recipe from eve-chat's launcher; entry is
  top-level forms — basilisp run has no -m).
  **ClojureCLR / cljr** ✓ journal (.NET SDK 8.0.404 installed by hand
  — dotnet-install's extraction fails on this box's uid mapping; tar
  --no-same-owner works. Clojure.Main 1.12.6 dotnet tool behind a
  `cljr` shim in ~/opt/bin).
- **jolt** ✓ A and B modes (open/swap/sync!/read verified); JOURNAL
  BLOCKED: its host interop cannot resolve FileChannel.lock in
  journal-with-lock ("No matching field found: lock for class
  :object"; direct and reflective spellings both). Joins J cells as a
  non-journal participant (the leaderless point-3 case).
- **bb** ✗: eve.encoding (the java adapter on the :bb path) fails SCI
  analysis — java.util.concurrent.ConcurrentHashMap not resolvable in
  babashka 1.3.190 as installed.
- **clje (clojerl)** ✗ not staged: the Erlang/OTP apt install was
  still in flight ~40 min after launch on this box's proxy (apt alive,
  no erl binary); clojerl itself also absent. Named gap.
- **cherry** ✗: no standalone disk-domain runner in the tree (its
  build makes the eve-swarm seat; thread adapter is thread/none).
- **browser / scittle** ✗ by construction: heap/SAB regions — no disk
  domain to share.
- **vs** — FULL PARTICIPANT in shared disk atoms since 2026-10-07
  via the coop route (shipped builder + vs-eve-host; see ERRATUM 2
  below — this row's first two drafts were both wrong). The spike
  journal on VS is the parity unit below (ERRATUM 2, point 4).
CENSUS ERRATUM — VS row (John's correction, 2026-10-27 ~21:40 ET;
probed after the fact, evidence in
doc/durability-spike-evidence/census-vs/):
1. The original VS census row was NOT probed. It was read off the
   hosts.edn comment ("eve/deftype's expansion waits on a builder
   carrying EM4's held half") and repeated as if measured. It was
   not. Every other census row above WAS probed with the standard
   probe (open journal domain, 2 swaps, sync!, read back); the VS
   row was the exception, and the summary that reached John
   overstated it as "can't come up at all."
2. Probed now, three routes (probe_main.vs = journal domain + 2
   swaps + sync! + read; probe2.vs = heap (atom/atom 0) only;
   probe3.vs = disk domain, no journal; hello.vs = 2-line control):
   - `vs compile` (installed vs, shipped-pair builder) over the
     hosts.edn :vs roots (13 roots via `bb classpath vs`):
     eve.atom's whole graph COMPILES, rc=0, 6.7 MB wasm.
     Compilation is not the blocker. (census-vs/compile.log)
   - Bare `wasmtime run` (the `bb test:vs` host route): every probe
     traps at the FIRST eve.atom call — RUN-TRIP form 1
     eve.atom/persistent-atom-domain (journal and plain-disk
     probes) and RUN-TRIP form 1 eve.atom/atom (heap-only probe),
     rc=1, no domain files created. (run.log, probe2-run.log,
     probe3-run.log)
   - `vs run` (launcher + shipped pair): wasm trap `unreachable`
     in interpreter/eval-all, rc=134 — for the hello control
     identically, so this route measures nothing about Eve on
     this box. (run-vsrun.log)
   - `bb test:vs` in this tree reports "0 passed, 0 failed" (its
     pass regex accepts 0); its runner vs_main.vs covers
     hamt-util / eve-bytes-fixture / slab-io tests only, and its
     own docstring states the eve.atom-on-deftype tests are
     excluded pending VS macro capability (docs/eve-on-vs/PLAN.md
     §3 E). (test-vs.log)
3. Builder: the probes used the installed vs (shipped-pair
   lineage). A held-half builder exists in the record only as
   EM4's SCRATCH builders (EM4-held-half-report-2026-10-19.md
   tags its readings CANDIDATE, "not a landed one"); no held-half
   builder artifact exists in this tree to point a probe at, so
   whether the atom stack runs under one — the work John cites —
   cannot be measured from this tree + box.
4. [SUPERSEDED by ERRATUM 2 below: the "every builder+host
   combination" claim is retracted — the working combination
   (shipped builder + vs-eve-host) was never probed, the bare-
   wasmtime route has no doorbell by design, and these probes
   were compiled with the installed vs whose pair anchors to
   another clone. What survives from this erratum is points 1–3's
   record of what was and wasn't measured.] FOLLOW-ON (John's
   order, next unit): fix the two VS-seat journal blockers — the
   vs region's stub `-with-file-lock` and the missing force arm
   — for parity; see the VS-seat section below.

CENSUS ERRATUM 2 — VS row (John's second correction, 2026-10-27
~22:20 ET; supersedes ERRATUM 1's framing):
1. RETRACTED: erratum 1's claim that "under every builder+host
   combination present in this tree, a VS process cannot execute
   eve.atom's first call." The tree contains the combination that
   works — the shipped builder + vs/hosts/eve-mmap's vs-eve-host —
   and the reports proving it; none of my three probed routes
   (bare wasmtime, bb test:vs, vs run) used it. Bare wasmtime has
   no doorbell host: vs.mmap/map-file! throws there BY DESIGN
   ("run the program under vs-eve-host"), so a first-call trip on
   that route measures the missing host, not VS.
2. The ground truth, from the tree's own record: the disk-atom
   contention suite is test/coop-atom/ at the repo root. run.sh's
   header: the JVM orchestrator drives eve's JVM, Node worker,
   nbb, bb AND "the wasm participant (wasm/coop_worker.vs,
   compiled by the shipped builder, run by vs/hosts/eve-mmap's
   vs-eve-host)" against one mmap atom. COOP-ATOM-REPORT.md
   (2026-10-07), bottom line: "A wasmtime-hosted VS program now
   joins eve's mmap disk atom (.root .rmap .slab0-6 .slabN.bm) as
   a full participant: it reads and writes the same files with
   the same CAS protocol as eve's JVM, Node and bb runtimes" —
   round trip Node → JVM → bb → wasm, wasm-only contention and
   JVM + wasm contention passing, 3 of 3 again at 5× scale.
   ROUND2 §3/§6: round-trip and JVM+wasm contention 3/3 at
   COOP_SCALE=200; the soak at 20,000 mixed ops 3/3
   (COOP_WASM_BATCH=20 — §6/W3: a compiled VS program frees no
   temporaries, so one process has an op budget, ~667–690
   uncontended ops measured; the batching is the standing
   workaround). The VS participant's stack there is vs.eve-disk,
   VS's own implementation of eve's disk format and CAS protocol.
3. A second contamination in erratum 1's probes, measured after
   the fact: they were compiled with the INSTALLED `vs`, whose
   pair anchors to another clone (~/src/atom.broker-land38);
   its programs RUN-TRIP on ANY cross-namespace call, including a
   plain .vs fn from a .vs main (banked: vs-seat feat3 runs).
   The tree's own vs/bin/vs compiles the same probes to working
   programs (rc=0, correct output). With that builder, two facts
   from the re-probes stand: (a) VS compiles .cljc with the :clj
   reader feature only — #?(:clj …) branches are taken, :cljs
   and :vs are not in the feature set (feat/feat4 probes); (b)
   eve.atom's graph compiles, rc=0.
4. What is actually open for VS + this spike — the parity unit:
   the coop suite coordinates by the CAS protocol and never
   needed the journal's append lock or a force; the journal's
   host surface on VS is exactly the two blockers John named:
   eve.region/vs's -with-file-lock stub ((f)) and no force arm
   (eve.mem/ISyncableRegion unimplemented on the VS region, so
   sync-region!/sync-sio! no-op). The journal probe for VS runs
   the coop route — shipped builder (this tree's vs/bin/vs) +
   vs-eve-host, journal domain — and its result, whatever it
   shows, is the real VS journal state. Bare-wasmtime numbers
   are not evidence about VS and are withdrawn wherever the
   census used them.

CELL MIX (8 processes, 6 runtimes): JVM ×2, Node ×2, nbb ×1,
ClojureCLR ×1, Basilisp ×1, jolt ×1.

WORKLOAD: one shared domain; atoms = 1 counter + record maps at
~1 KB (12 records) / ~100 KB (1,200) / ~1 MB (12,000) / ~100 MB
(1,200,000 records of {:v, 60-char data} — many small records, never
one giant string, per the known single-string defect). Seeded mix
40/35/15/8/2 (counter/1k/100k/1m/100m). The 100 MB class is taken
only by JVM-family writers (JVM, jolt, cljr) — Node/nbb/lpy draws
become counter increments (materializing ~100 MB into JS/Python
objects per op) — named, not smoothed over. J-5s =
:journal-window-ms 5000 (new: time trigger checked on the commit
path; count batch 1,000,000 stays as backstop) + *flush-log*
instrumentation (new dynamic var; {:at :ms} per flush). Writers log
every op with the post-op root epoch; at the deadline the runner
SIGKILLs, copies the domain, a fresh JVM recovery-opens the copy, and
a checker verifies: atoms read whole; counter in [confirmed
increments (epoch ≤ newest journaled), total]; per-record :v is a
logged value and ≥ the newest confirmed put for that record. Writers
never sync! mid-cell (harness design: a checkpoint truncates the
journal and would erase the audit trail; sync! itself is proven in
phases 1–2).

SETUP OBSERVATION (repro running): the 1.2M-record setup value
round-tripped with 1,199,977 records — 23 keys missing. If it
reproduces standalone it is a pre-existing eve defect at map scale,
unrelated to the journal (setup runs B-mode, journal off); the repro
(doc/durability-spike-evidence/map_scale_repro.clj) names the missing
keys and whether they collide under eve's portable hash.

## VS-SEAT PARITY UNIT (John's order, 2026-10-27 ~21:55 ET)

Fix both VS-seat journal blockers on this branch: (1) a real
cross-process file lock for eve/adapters/region/vs's
-with-file-lock (today the stub (f)); (2) a force arm — the VS
region answers eve.mem/ISyncableRegion through a new vs.mmap
doorbell op, host-side msync in vs-eve-host. Scope note: the
doorbell host (vs/hosts/eve-mmap/vs-eve-host.c) and vs.mmap
(vs/src/vs/mmap.vs) live OUTSIDE eve/ — John's order names them
explicitly, so this unit's changes extend there; nothing lands
to main, as everywhere in this spike.

LOCK MECHANISM — MEASURED FIRST (evidence:
~/workspace/vs-seat/lockprobe/, to be copied into the evidence
dir): a JVM process holding FileChannel.lock (the journal's
lock) on a file, contenders in a second process:
- fcntl F_SETLK (classic POSIX): BLOCKED (EAGAIN)
- fcntl F_OFD_SETLK (OFD): BLOCKED (EAGAIN)
- flock(2) LOCK_EX: ACQUIRED — does NOT exclude the JVM lock
- mkdir lock directory (Node's journal mechanism): ACQUIRED —
  does NOT exclude the JVM lock
Reverse direction: python holders of classic and OFD fcntl locks
each made the JVM's tryLock fail (BLOCKED/DENIED). CONCLUSION:
fcntl byte-range locks (either flavor) are the mechanism that
excludes across hosts; flock and mkdir live in separate lock
domains. The VS host uses F_OFD_SETLK (an OFD lock is owned by
the mapping's own descriptor, which the host holds for the
window's life; a classic lock would drop if the process closed
any descriptor on the file). NAMED GAP, pre-existing: Node's
mkdir journal lock excludes only other mkdir users — it does
not exclude the JVM today either, so the {JVM, VS} fcntl domain
vs Node mkdir domain split predates this unit and is not a
regression it introduces; closing it is a Node-side change
(the addon could export an fcntl lock), not built here.

BUILT SO FAR:
- vs-eve-host.c: OP_SYNC 10 (msync MS_SYNC of a page-aligned
  range inside a file window), OP_FLOCK 11 / OP_FUNLOCK 12
  (one non-blocking OFD attempt per request, refcounted held
  table; the guest spins so the doorbell thread never blocks),
  OP_TRUNC 13 (ftruncate, shrink included — the journal's
  checkpoint op; EXTEND only grows by design). Host rebuilt.
- vs/src/vs/mmap.vs: sync!, flock! (EAGAIN/EACCES → false, else
  true/throw), funlock!, truncate!.
- eve/adapters/region/vs region.vs: -with-file-lock on Long is
  real for file windows (spin 5 ms × 6000 ≙ 30 s, the Node
  lock's shape; heap windows run f directly — no other seat can
  contend for a heap window); ISyncableRegion on Long: -sync! /
  -sync-range! via vmm/sync! for file windows, no-op for heap.
  B's path reaches it unmodified: alloc/sync-sio! walks the ctx
  regions through mem/sync-region!.
- Probes banked under ~/workspace/vs-seat/ (feature probes:
  :clj-only reader features on VS; builder-mismatch probes).
PROVEN (evidence in doc/durability-spike-evidence/vs-seat/):
- FORCE: lockprobe `sync` under vs-eve-host → VS-SYNC-OK 4242,
  the word on disk afterwards (mem/sync-region! whole + ranged
  through the new ISyncableRegion arm). lp-sync.txt.
- LOCK EXCLUSION, BOTH DIRECTIONS, REAL CODE
  (lock-exclusion-proof.txt, lp-hold3/lp-try1/lp-try2.txt):
  VS holds via mem/-with-file-lock (doorbell OP_FLOCK → host
  F_OFD_SETLK) → JVM FileChannel.tryLock(16,4) BLOCKED/DENIED
  and a python OFD attempt BLOCKED (EAGAIN). JVM holds
  FileChannel.lock(16,4) → VS vs.mmap/flock! returns false;
  after release → true.
- OTHER SEATS UNHARMED: JVM atom suites re-run — 44 tests /
  210 assertions, 0 fail (identical to pre-parity). Journal
  crash tests re-run — J1 15/15, J10 reopened 8 (journaled
  prefix), torn+garbage 3/3, all journals 2068 B after
  recovery+close (journal-crash-tests.txt refreshed). Node J1
  bench re-run all three sizes; every journal 2068 B after
  close. (The parity diff touches no JVM/Node code path: only
  vs-eve-host.c, vs/src/vs/mmap.vs, adapters/region/vs.)
JOURNAL CENSUS PROBE VIA THE COOP ROUTE — measured, blocked
OUTSIDE this unit's scope: probe_main.vs (journal domain, 2
swaps, sync!, read) compiled with this tree's vs/bin/vs FAILS:
"X 2 0 18161 macroexpand: Unable to resolve symbol:
translate-protos in this context" (rc=75). A require-only probe
((require eve.atom), no calls) fails IDENTICALLY — same form
count 18161 — so the wall is in macroexpanding eve.atom's own
graph: eve.types/vs's deftype macro calls translate-protos,
and the shipped builder's macro evaluator cannot resolve it.
That is the EM4 held-half item the hosts.edn :vs comment names
("eve/deftype's expansion waits on a builder carrying EM4's
held half") — the shipped-builder deftype question John's
steer puts OUT of scope for this unit. The lockprobe graph
(eve.region + eve.mem + vs.mmap; no deftype macros) compiles
and runs fine under the same builder + host, which is why the
lock/force proofs above stand. BASE CONTROL LANDED: the same require-only probe against
pristine main (d2f163f056c6) eve roots fails with the SAME
error (17,825 forms vs the spike's 18,161 — the delta is the
spike's own added forms). The wall predates the spike
entirely (reqatom-base-compile.txt). Behind the wall, one
more named piece (code read, cannot be run until the wall
falls): VS compiles .cljc as :clj only, so the journal's :clj
branch is what VS would execute — and it calls
java.nio.channels.FileChannel / java.nio.ByteBuffer /
java.util.zip.CRC32, for which no shim exists anywhere in
vs/src (searched). The journal on VS needs that shim surface
(or a doorbell-backed channel) after the builder wall falls;
the lock and force this unit was ordered to fix are beneath
both and are done.
UNIT CLOSED (report section: "VS seat parity" at the end of
eve/doc/durability-spike-report-2026-10-27.md): both ordered
blockers built and proven (lock exclusion both directions,
force arm), other seats re-run green, journal probe
characterized with its two remaining, out-of-scope blockers
named. The mixed phase-3-style cell with a VS writer is NOT
run — a journal-participating VS writer waits on the builder
wall, and vs.eve-disk's coop writer is not a journal
participant (decision stated in the report section).
Phase 3 proper (smoke-2 TypeError diagnosis, then the A / J-5s
60 s cells) remains open behind this unit.

## Phase 3 — smoke findings + two harness/implementation fixes (2026-10-27 late)

- SMOKE CELLS (12 s / 15 s, J): all 8 writers produce ops (smoke-2:
  12,771 ops parsed; counter recovered EXACTLY 5,163 = logged
  increments; 2,071 records checked, 0 violations; counts whole
  except s100m at 1,199,977 + 1 anomaly — the pre-existing setup
  map-scale loss, present before any writer ran).
- RUNNER BUG (fixed): phase3_runner.py's EDN tokenizer split
  `:keyword` into two tokens (`:` + name), shifting every map
  until a vector landed in a key slot — smoke-2 died with
  TypeError: unhashable type 'list' in the checker, and smoke-1's
  op parsing silently dropped most lines. Tokenizer now matches
  keywords as single tokens (+ `#{}` set handling). Verified
  against smoke-2's own verify.edn/ops logs offline.
- IMPLEMENTATION BUG (fixed, spike code): `persistent-atom-domain`
  destructured `:journal?`/`:journal-batch` but never forwarded
  `:journal-window-ms` to open-mmap-domain! — the 5 s window
  option was silently dropped at the public API, so no J-window
  flush ever fired (probe: batch-5 flushes fine; window-100 ms
  over 1.5 s flushed ZERO times, journal 0 B). Fixed by forwarding
  the option; re-probe: 15 flushes in 1.5 s at a 100 ms window,
  journal 31,020 B mid-run, 2,068 B anchor after close.
  Regressions after the fix: journal crash tests green (J1 15/15,
  J10 reopened 8, torn 3/3, journals 2,068 B); JVM suites
  44 tests / 210 assertions, 0 fail (identical).

## Phase 3 — CELLS DONE (2026-10-28 ~00:20 UTC)

- CELL A (60 s, full 8-writer mix): 67,499 ops (counter
  28,428 / 1k 23,501 / 100k 9,989 / 1m 5,524 / 100m 57);
  counter recovered EXACTLY 28,428; 3,597 records checked, 0
  violations; only anomaly = the pre-existing map defect.
- CELL J-5s (two runs; run 2 = the cell of record,
  phase3-cell-J.json): 14,077 ops; E_dur 13,879; counter
  confirmed 5,615 ≤ recovered 5,616 ≤ total 5,710 ✓; 1,976
  records checked — every recovered :v a logged value, all
  but one exactly the last confirmed put. Run 1: confirmed
  6,862 = recovered 6,862 exactly, 2,315 records, one
  residual. Both residuals = the harness epoch-attribution
  race (epoch read after swap, inflates under contention);
  run 1's is PROVEN: lpy's seeded first put (:v 1 → r134)
  published, then its process died at the clock throw before
  logging — an unlogged commit the journal still carried.
  No torn state in any cell.
- FLUSH under contention: 30 flushes/cell, mean 654–707 ms,
  max 1,444 ms (this disk); achieved cadence 5.7–6.0 s per
  journal writer; journal at kill 62,040 B = 30 records.
- PERF: J-5s ≈ 0.20–0.22× of A uniformly across classes
  (aggregate A/4.8) — the whole-domain sweep (~0.7 s on this
  disk, incl. the 100 MB class's pages from all writers) on a
  writer's commit path is the brake; the phase-1 J1000>A
  counter result does NOT generalize to this workload.
  Mechanism measured (flush latencies), stated in the report.
- CENSUS CORRECTION (measured): cljr/lpy reader features are
  #{:cljr}/#{:lpy} only — the journal's #?(:clj …) Java
  append branch is DEAD on both; their census "✓ journal"
  probes are downgraded (in-process read-back passes a no-op
  flush too). They join J cells as Jn non-journal
  participants, like jolt. Porting the append branch per host
  is a named remainder. now-ms gained a :default arm.
- cljr in J cells: run 1 died (clock), run 2 still booting at
  the kill window — no J measurement for cljr (399 ops in A).
  lpy marginal (124 ops J / 1 A). Node flush events don't
  reach its op logs (CLJS drain gap) though its throughput
  drops as if flushing.
- MAP-SCALE DEFECT (pre-existing, both cells + setup):
  1.2M-record map loses 23 keys single-process; recovered
  100 MB maps carry a spurious :v key. Journal-independent;
  repro banked; register candidate, not this spike's scope.
- Phase-3 section written into the spike report. Evidence:
  phase3-cell-A.json / phase3-cell-J.json in the evidence dir.

## Log

- 2026-10-27 ~12:05 ET — branch + worktree created off verified main tip;
  anchors verified (above); design fixed. Next: read alloc ctx conditionals +
  `mmap-compare-and-set!`, then implement.
- ~12:10 ET — fix implemented (mem.cljc ISyncableRegion + sync-region!;
  region.clj force impls for both JVM region types; alloc.cljc sync-sio!;
  atom.cljc commit ordering + :durable? + sync! + close-time sync).
  Editing deviation: plain text edits, not `vs src!` (eve .cljc sources;
  the spike brief allows it, noted here per instructions).
  `mmap-compare-and-set!` rides mmap-swap!, so it inherits the ordering.
- ~12:15 ET — REPL probe green: durable domain, swaps incl. a 5000-char
  string, sync!, close, reopen → value intact. strace proof: 10 counter
  swaps = 261 msync with durable commits vs 21 with `:durable? false`
  (the 21 are the close-time whole-domain force) — the force is real,
  per commit, and the toggle works.
- ~12:20 ET — fix committed + pushed: `8f9d6c341db`.
- ~12:25 ET — spike tests (4 reopen tests) + existing JVM atom suites on
  the fixed branch: **44 tests, 210 assertions, 0 fail / 0 error**
  (evidence: doc/durability-spike-evidence/jvm-atom-tests-fixed.txt).
  Test + bench harness committed + pushed.
- ~12:30 ET — bench attempt 1 failed fast: `(ProcessHandle/current)` does
  not resolve unqualified ("No such namespace: ProcessHandle"); fixed to
  `java.lang.ProcessHandle`, bench restarted clean (attempt 2). Base
  worktree `~/workspace/wt/eve-durability-base` at main tip holds an
  untracked copy of the same bench file for variant A.
- Coop suite finding: the orchestrator is atom.broker's
  `test/coop-atom/clj/coop_atom_test.clj`; its contention bases are
  initialised through eve's Node worker, which is not built in this tree
  (no node_modules / target / addon; the cognitect test-runner git dep is
  also uncached). Fallback prepared: a runner driving the orchestrator's
  own `contend!` + coop.scenario invariant checks with 4 JVM threads
  (evidence dir: coop-jvm-contention-runner.clj). The Node/bb/nbb/wasm
  participants are not run by this spike; the report will say so.
- ~12:40 ET — JVM single-host benches DONE (3 interleaved rounds):
  durable +~0.85–1.1 ms/commit flat across sizes; group commit ≈ base.
  JVM contention DONE (4 procs × 400): B ≈ 58× slower than A (9.7 vs
  560 ops/s), C ≈ 2.2× off A; all runs land exactly 1600. Coop scenario
  (suite's own source, JVM threads): PASS, violations [].
- ~12:45 ET — scope addition (John): Node host too. Anchors verified
  firsthand: addon default cas32/cas64 are bare
  __atomic_compare_exchange_n (mmap_cas.cc:184/240); every ::msync sits
  in the Lustre fcntl functions (mmap_cas.cc:336+). npm install built
  the pristine addon in both trees (base addon's msync export:
  undefined — variant A is truly current code).
- Node fix (commit d90637cb802): addon gains a standalone msync(buf,
  off, len) export reusing the same MS_SYNC primitive with page-aligned
  ranges; NodeMmapRegion implements mem/ISyncableRegion via it;
  process_slab.cljs registers :sync-domain-sio! (the module slabs'
  regions per class + desc + coalesc); alloc/sync-sio! dispatches
  host-op first (mirrors refresh!), JVM falls back as before. The
  commit ordering itself is host-shared (atom.cljc) — unchanged.
- Node cost shape found by measurement (strace -T): msync on eve's
  LIVE domain files costs ~1–30 ms per call (~22 calls/commit ≈
  ~110 ms/commit; B counter ≈ 122–129 µs/op ×1000 vs base ≈ 8 µs/op),
  while the same syscall on a quiescent domain file is ~0.7 ms and on
  a plain fresh file ~0.001 ms — the cost is writeback state of the
  live files, not the syscall or the range size. Scoped forcing is
  therefore worth much more on Node than on the JVM (where the whole
  force round is ~1.1 ms/commit).
- Epoch framing (John's correction, applied to the report): epochs
  are generations of the ROOT — the root page holds one current-epoch
  word (ROOT_EPOCH_OFFSET 8) and one root pointer per atom slot; each
  commit advances the epoch and moves the pointer. Retirement READS
  epochs (retire entries are epoch-tagged; min-safe-epoch gates
  reclamation); epochs are not retirement's and there is no "retire
  epoch" (the phrase appears nowhere in this spike's text). The fix
  is a durable-epoch watermark: an epoch's writes are forced before
  the root's advance to that epoch is forced; :durable? true = the
  durable epoch never lags the live one, group commit = bounded lag.
  On-disk layout fact: previous epochs' root pointers are NOT
  retained (one pointer per slot), so open-time resolution to the
  newest fully-durable epoch would need a retained previous-root
  record — named as the open design question in the report.
- Node correctness closed: fixed tree (dev build) mmap-atom 7/7,
  mmap-domain 10/10, epoch-gc 16/16; mmap-atom-e2e fails 9 on BOTH
  the fixed tree and pristine base (worker spawn timeouts under the
  harness; the same worker modes pass standalone in <2 s) —
  pre-existing, not a regression.
- ~13:30 ET — PIVOT (John): the journal is the fix; variant J is the
  spike's new subject, B stays as the built comparison. J DESIGN
  (decisions banked before building):
  * Journal file <base>.journal beside .root; records carry COMMIT
    METADATA ONLY: {magic, len, epoch, the full atom-table bytes
    (256 slots x 8 B = 2048 B — one pointer per atom per epoch, raw),
    CRC32}. Framing lets a scan skip a torn record by its length;
    the newest valid record is the newest fully journaled epoch.
  * Commit path (durable journal domain): write blocks -> (J1)
    force slab ranges -> root CAS + epoch bump (live, as today) ->
    append the flush record -> force the journal: that force is the
    commit point. Live machinery untouched; the journal decides
    nothing about liveness. J10 batches 10 epochs: no per-commit
    force; the flush does ONE whole slab sweep + appends ONE flush
    record (epoch + table at flush time) + ONE journal force.
    Records are flush records — recovery only ever needs the newest,
    and full-table records make cross-process batch interleave
    sound (a flush's whole sweep forces every dirty block in the
    domain, whichever process wrote it).
  * BITMAPS — the named choice: bitmap consistency is DEFERRED;
    alloc/free deltas do NOT ride the records. The closing is
    rebuild-from-roots at recovery (walk the installed epoch's
    reachable blocks, rebuild the allocation bitmaps). Named, not
    built (one paragraph in the report).
  * APPEND SERIALIZATION — the named choice: a lock on the journal
    file (JVM FileLock), held across each flush (append + force)
    and across recovery/checkpoint truncation. It never touches the
    live CAS path. A crash mid-append leaves a torn tail record
    that fails framing/CRC at scan: that epoch is
    uncommitted-durably, and no survivor read it from a durable
    world — durability is defined at the journal, and in a system
    crash every local process dies together.
  * CHECKPOINTING — the picked trigger: sync! and close. Both
    flush any pending batch, force the whole domain (root file
    included), then truncate the journal to empty under the lock.
    Between checkpoints the journal grows one record per flush.
  * RECOVERY at open, before claiming a worker slot, under the
    journal lock, ONLY when the domain is not live (no ACTIVE
    worker slot with a fresh heartbeat; heartbeats tick every
    10 s, stale at 30 s — a post-crash opener inside the stale
    window defers recovery to a later open, never corrupts):
    install the newest valid record's table + epoch into the root
    region when its epoch differs from the mapped root's, force
    the root file, compact the journal to that record (or empty).
    A live domain is joined through its live root, journal
    untouched.
- J RECOVERY PROOF (journal-crash-tests.txt): J1 halted mid-run,
  root wound back to epoch 1 + table zeroed -> reopened 15/15.
  J10 halted after 15 swaps -> reopened 8 (first flush at epoch
  10 = 2 setup commits + 8 swaps; swaps 9-15 lost whole). J1 +
  100 garbage bytes appended to the journal -> torn tail
  discarded, reopened 3/3. All journals 0 bytes after
  recovery+close (checkpoint truncation works).
- J PERF, single-host, JVM — ROUND 1 of 3 (interleaved A/B/J1/J10,
  us/op; evidence j-single-r1.txt). NOTE the box was replaced
  mid-day: msync on live files now costs ~ms each, so B reads
  ~114 ms/op today vs 1.7 ms/op in the morning session — only the
  interleaved rounds compare; the morning A/B numbers stand as
  their own session.
    counter      A 711      B 113,904   J1 111,130   J10 11,847
    ~1 KB        A 3,734    B 64,202    J1 64,895    J10 14,486
    ~64 KB       A 3,705    B 67,338    J1 76,766    J10 11,572
  J1 ~= B (same force work dominates both; J1 = sweep + 1 fsync,
  B = sweep + root msync). J10 ~= B/6-B/10: one sweep + one fsync
  per 10 epochs. Rounds 2-3 + contention to follow, banked as they
  land.
- J PERF, single-host, JVM — FINAL medians, 3 interleaved rounds
  (us/op; evidence j-single-r1..r3.txt):
    counter      A 898      B 110,331   J1 106,821   J10 11,832
    ~1 KB        A 6,792    B 64,202    J1 60,349    J10 12,314
    ~64 KB       A 5,610    B 63,476    J1 65,468    J10 11,572
  J1 ~= B at every size (the same force work dominates). J10 is
  5-9x cheaper per commit than B/J1 on this box. The loss window
  priced against the curve: a crash loses up to N-1 unjournaled
  epochs — J10: 9, J100: 99, J1000: 999.
- FLUSH LATENCY instrumentation (John's steer: batch size is a
  time window): eve.atom/*flush-timing* — when bound to an atom,
  journal-flush! conjs each flush's whole-call wall time (lock +
  slab sweep + append + journal force); the bench prints a
  :flush-latency line (flushes, mean-ms, max-ms) per run. Probe
  (J10, this box): mean 96-138 ms, max 281-395 ms across sizes.
  flush-latency x unflushed swap rate = commits accumulated while
  one flush is in flight = the journal-capacity sizing number.
  J100/J1000 rounds (with latency lines) running.
- BATCH CURVE, JVM single-host — round 1 (us/op; j2-single-r1.txt):
    counter      A 474     J10 9,587    J100 1,265    J1000 467
    ~1 KB        A 1,829   J10 6,007    J100 1,668    J1000 1,854
    ~64 KB       A 1,249   J10 6,908    J100 2,656    J1000 1,311
  J1000 ~= A at every size (flush cost fully amortized); J100
  ~= A on counter/~1 KB, ~2x A on ~64 KB. Price line per batch:
  loss window = up to N-1 unjournaled epochs (J10: 9, J100: 99,
  J1000: 999). Flush latency this round: means 51-189 ms, maxes
  302-469 ms; a J1000 flush costs more than a J10 flush (189/361
  ms means) — its sweep forces ~100x the dirty pages — but is
  paid 100x less often. Rounds 2-3 running; medians banked when
  they land.
- BATCH CURVE FINAL — medians, 3 interleaved rounds (us/op;
  j2-single-r1..r3.txt):
    counter      A 438     J10 9,587    J100 1,216    J1000 410
    ~1 KB        A 1,829   J10 6,007    J100 2,391    J1000 1,832
    ~64 KB       A 1,278   J10 6,908    J100 1,873    J1000 1,311
  J1000 ~= A at every size; J100 within 1.3-2.8x of A.
  Flush latency pooled across rounds (whole call: lock + sweep +
  append + force): means 51-147 ms at every batch size (mild
  growth with batch: J1000 counter wmean 128 ms vs J10 93 ms),
  maxes 302-469 ms. Within-run max/mean ~3-9x; the across-session
  swing of the mean itself is ~60-100x (morning force round
  ~1.1 ms/commit total vs evening flush means ~100 ms).
  Sizing identity: commits accumulating during one flush =
  flush latency x unflushed rate (A counter 438 us/op ~=
  2,280 ops/s): ~210-290 at mean latency, ~1,070 at the 469 ms
  max — the journal-capacity number. (Feeds the report's
  adaptive-sizing section + recommended valid range, and the
  leaderless-coordination section — both John's named additions.)
  J contention running (A/B/J1/J10/J100/J1000, N=100, 2 rounds,
  with per-worker flush-latency capture).
- SECOND VENUE — j.slaa.us (John's build server, its own disk;
  evidence server/server-single-r1..r2.txt). Singles medians,
  2 interleaved rounds (us/op):
    counter      A 205     B 2,558    J1 3,439    J10 481     J100 186     J1000 149
    ~1 KB        A 982     B 2,506    J1 3,334    J10 1,227   J100 959     J1000 994
    ~64 KB       A 856     B 2,524    J1 3,543    J10 1,111   J100 916     J1000 792
  J100/J1000 at parity with A (within noise); J10 2.3-5.3x
  cheaper than B. Flush latency on the server: J1 flush
  (append+fdatasync only) wmean 1.34 ms; sweeping flushes
  (J10/J100/J1000) means 3.2-17.6 ms, max 31 ms — vs local
  evening means 51-147 ms, max 469 ms. The two disks' latency
  distributions differ ~30-70x: a batch count fitted to one
  disk is wrong on the other by that factor, which is what the
  adaptive (latency-driven, time-ceilinged) range is for.
- CONTENTION round 1 (local, 4 procs x 100, aggregate ops/s):
  A 341, J1 9.9, J10 81, J100 147, J1000 443 (J1000 ABOVE A —
  its workers almost never flush inside the window; A pays
  pure CAS contention). B leg + round 2 running.
- CONTENTION on the server (4 procs x 100, aggregate ops/s;
  server/server-contention-r1.txt): A 1,434, B 291, J1 225,
  J10 1,006, J100 1,302, J1000 1,490. Same shape on the fast
  disk: J10 within 1.4x of A, J100/J1000 at parity, J1/B at
  ~1/5 of A. Contention flush latency (local, per worker):
  J10 flushes mean ~420-432 ms (vs 93 ms single-host — lock
  queueing + whole-domain sweeps of 4 processes' pages), max
  854 ms; J1's append+force flush mean 37 ms (its price sits in
  the pre-CAS sweep, not the journal).
- NODE J singles — medians, 2 interleaved rounds (us/op, this
  box; node-j-single-r1..r2.txt):
    counter      A 270     B 118,755   J1 128,796   J10 12,754   J100 2,127    J1000 1,141
    ~1 KB        A 8,766   B 97,351    J1 67,761    J10 21,661   J100 19,795   J1000 17,270
    ~64 KB       A 10,467  B 87,143    J1 78,825    J10 27,704   J100 30,996   J1000 24,028
  Same shape as JVM: J1 ~= B, J10 ~3-10x cheaper, J100/J1000
  approaching A (Node's A at kb sizes is itself noisy on this
  box — GC/allocation variance; the counter row is the clean
  read: J1000 within ~4x of A). Node journal files verified on
  disk (created per domain, truncated to 0 at close-checkpoint).
- THIRD VENUE — John's Mac (Apple Silicon, OpenJDK 26, via
  run-bench.sh): counter A 75.5 / B 328.8 / J1 4,311.3 /
  J10 505.1 / J100 126.2 / J1000 61.4 us/op; ~64 KB A 625.6 /
  B 1,734.5 / J1 4,634.3 / J10 769.3 / J100 1,214.9 /
  J1000 669.9. Flush latency ~4 ms FLAT at every batch size
  (fdatasync-priced disk; sweep nearly free). J1 is ~13x WORSE
  than B there — the primitives price oppositely to the hatch
  disk — and J1000 ~= A at every size. In the report (venue
  tables + latency table + capacity note: ~53 commits per
  flush window at the Mac's counter rate).
- RUNNER BUG FIXED (John's fresh-checkout Node failure —
  target/durability-bench.js not found; his copy predated the
  main.js path fix and the shadow build's failure was swallowed
  by tail): run-bench.sh now builds, VERIFIES
  target/durability-bench/main.js exists, prints the build
  log's tail loudly on failure, and SKIPS the Node matrix with
  a printed reason instead of erroring per variant.
  Smoke-tested on this box: 'bench build OK' + Node rows run.
- PHASE 2 COMPLETE (2026-10-27). Addendum:
  eve/doc/durability-crash-suite-2026-10-27.md. Totals across
  72 crash points (fresh-process reopen per point): B 18/18
  pass (both sizes — force ordering holds at every boundary);
  J1 32/34; J10 15/20. All 7 violations are ONE finding, two
  halves — THE RECOVERY FLOOR IS NOT DEFENDED: (1) the
  checkpoint's atom table exists only in the root file; when
  the live root lands while the journal is empty past the
  checkpoint (root-ahead points, J1 + J10, both sizes),
  recovery has no record to install — J1 reopens on an
  unjournaled complete epoch, J10 fails to open (unknown root
  value type-id; the named epoch's blocks were never forced).
  (2) A journaled epoch's BLOCKS are not retained: at J10's
  flush points on the ~66 KB legs, falling back to the
  checkpoint fails (Unknown EVE type tag) because the
  checkpoint's root block was recycled by later epochs —
  retirement honors live readers' pins, and a journaled epoch
  is pinned by nobody; low churn (counter legs) never triggers
  it in these sequences. Production needs, characterized not
  built: a checkpoint anchor record surviving truncation, and
  a journal retention floor bounding reclamation. Everywhere
  the floor holds, recovery is exact (torn tails discarded at
  all 6 offsets, fallbacks install the right epoch, flush-done
  reads epoch 10). Nothing lands to main.
- PHASE 2 RESULTS SO FAR: B/counter 9/9 pass, B/kb64 9/9 pass
  (B's force ordering holds at every boundary, both sizes).
  J1/counter 16 pass / 1 violation; J1/kb64 identical 16/1.
  THE VIOLATION (a real finding): point c1-root-ahead-of-journal
  — crash after the live root CAS lands but before the first
  journal record is forced, journal EMPTY since the checkpoint:
  recovery installs nothing (there is no valid record) and the
  landed live root survives — reopen reads epoch 1, not the
  checkpoint epoch. With >= 1 valid record (c2-c4 root-ahead)
  the fallback installs the journaled epoch correctly. Root
  cause: the checkpoint's atom table exists ONLY in the root
  file; once the root's pages land with live state, no on-disk
  copy of the checkpoint table remains to roll back to. The
  exposed state is a complete epoch under J1 (blocks forced
  pre-CAS); the same window under a batch exposes an epoch
  whose blocks were never forced (J10 window-c5-root-ahead
  point added; running). Fix shape (John's call, not built):
  a checkpoint must leave an anchor record — recovery needs a
  valid record for the checkpoint epoch itself. Suite shape
  note: single strings >~1-5 KB do not round-trip through this
  eve build's mmap atom (reads back empty; verified identical
  on pristine main — pre-existing, not the fix), so the ~64 KB
  legs use a 2200-element vector of 30-char strings.
- PHASE 2 — SQLite-pattern crash suite (John's follow-on unit,
  started after the declaration below). Design: a recording run
  drives a scripted commit sequence per variant (B / J1 / J10) x
  size (counter / ~64 KB), snapshotting the whole domain dir
  after each commit plus a manifest (epoch, journal length after
  each commit). A materializer composes, for crash point k, an
  image in which ONLY the effects of force events 1..k exist:
  slab bytes come from snapshot k, unforced files keep the
  checkpoint (snap-0) bytes, the journal is truncated to the
  forced prefix (or torn at a chosen offset); worker heartbeats
  in copied root bytes are zeroed (dead processes do not
  heartbeat — the model's time abstraction, stated in the
  addendum). A fresh process reopens each image; the check:
  reopen succeeds, and the value equals exactly the expected
  epoch's value. Points: B after each commit's slab force and
  root force; J1 after slab force, after journal force,
  mid-append torn tails (4 offsets), live-root-ahead-of-journal;
  J10 inside the batch window, between sweep and journal force,
  mid-append torn. Verdicts banked per variant-size as they land;
  addendum eve/doc/durability-crash-suite-2026-10-27.md.
  Model assumption (named): a force makes its ranges durable
  atomically w.r.t. the crash point; unforced data is entirely
  absent (the powersafe-write assumption).
- SPIKE DECLARED COMPLETE (2026-10-27 evening). Final report:
  eve/doc/durability-spike-report-2026-10-27.md — hole + anchors
  (both hosts), B and J designs in epoch terms, guarantees per
  variant (A none / B durable / C force-work price only / J
  reopens at newest fully journaled epoch), perf tables for
  three disks (this box morning + evening sessions, j.slaa.us,
  Node), flush-latency tables, adaptive capacity design with
  the recommended valid range [100, 1000] epochs / ~1 s, the
  leaderless-coordination section (points 3 and 5 weaker as
  built, stated), correctness (suites, coop JVM + mixed,
  journal crash tests, cross-host check), remainders. Process
  sweep of both worktrees: clean. Nothing lands to main.
  FOLLOW-ON (John's queued direction): phase 2, the
  SQLite-pattern simulated-crash suite, runs on this branch
  next; addendum eve/doc/durability-crash-suite-2026-10-27.md.
- CROSS-HOST JOURNAL CHECK: PASS (journal-xhost-check.txt).
  JVM wrote 5 epochs to a J1 domain and halted unclosed; the
  root was wounded by hand (slot zeroed, epoch reset); a Node
  process opened the domain, scanned the JVM-written journal
  (framing + CRC32 identical), installed epoch 5's table,
  checkpointed at close; JVM cread reads :value 5. The journal
  byte format is host-independent in fact, not just in design.
- MAC RUNNER banked: eve/doc/durability-spike-evidence/
  run-bench.sh — one command, prints setup + JVM matrix (+ Node
  if present), A from an origin/main worktree with variant-C
  fallback, results tee'd to run-bench-results.txt beside it.
  Smoke-tested on this box (path bug found + fixed; the A
  worktree add is slow on this blobless clone, fast on a normal
  one, fallback covers failure). Retest after the fix ran the
  full JVM matrix green with the true origin/main baseline.
- LOCAL contention FINAL (4 procs x 100, 2 rounds, aggregate
  ops/s medians; j-contention-r1..r2.txt): A 294, B 9, J1 9,
  J10 69, J100 145, J1000 368. Every leg's final counter exactly
  400 in both rounds — all J variants correct under contention.
  Contention flush latency (per worker, pooled): J10 mean
  ~500 ms max 1,142 ms; J100 mean ~750 ms (one flush per
  worker) max 1,002 ms; J1 append+force mean 42 ms max 365 ms.
  The worst measured flush anywhere: 1.14 s (J10, contention,
  this disk) — the ceiling arithmetic in the report rests on
  it.
