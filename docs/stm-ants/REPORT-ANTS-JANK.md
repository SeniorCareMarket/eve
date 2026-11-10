# REPORT-ANTS-JANK — the jank seat of the ants STM demo

Lane: lane-m/ants-jank-seat, based on lane-m/stm-bb-storms @ 6971e96b
(the composed tree: 0931 + the STM wrap-up fixes), with docs/stm-ants
grafted as-is from lane-m/ants-demo-package @ 32a9c468 (the base tip
carried docs/stm-eve but not docs/stm-ants).

## Verdict

JANK IS A REAL ANTS MEMBER. All three stages pass on J
(j.slaa.us), small board (dim 8, 64 cells):

- (a) jank alone creates a world in one process and re-opens it
  in a second: digest and commit clock IDENTICAL across the two
  processes, audits clean in both. PROBE-VERDICT: PASS.
- (b) launcher run, members jvm+jank: jank probed READY, joined
  as tag 2, exit 0; 42 snapshots audited, bad-audits=0.
  VERDICT: PASS.
- (c) mixed run, members jvm+bb+jolt+jank: all four members
  joined and exited 0; 39 snapshots audited, bad-audits=0;
  FINAL-AUDIT ants 8/8, food 15/15, violations [].
  VERDICT: PASS.

Getting there took four walls, all fixed in the loop: a
reader-conditional arm-order defect in the demo's own sleep!
(W1), a jank runtime stub (W2), the disk-tier STM seating never
having been promoted into eve/src (W3 — the release-relevant
one), and the jank region adapter exhausting the process fd
limit at ~30 domains (W4). A fifth defect fell out of stage (a):
read-only transactions advancing the shared commit clock (W5).

## The driver

docs/stm-ants/hosts/jank/ants_jank_main.cljc (ns ants-jank-main):
mirrors hosts/jolt/ants_jolt_main.cljc — reads ants-config.edn from
its cwd, await-built! → open-world → await-ready! → place-ants! →
round-robin behave loop to the config deadline — with the jank
discipline of docs/stm-eve/hosts/jank/stm_jank_main.cljc:
[eve.host-completion] required first, deadline via eve.process
now-ms, exit via (proc/exit! 0) (jank's own teardown hangs; named
upstream defect). Prints JANK-ANT-HOST-READY / JANK-ANT-HOST-DONE
lines in the launcher's supervision shape ("ANT-HOST-READY" /
"ANT-HOST-DONE" substrings + tag= N).

run.cljc changes (demo/run.cljc):
- roster: jank's :no-driver row removed — jank is a real seat.
- probe-runtime: :jank case added (jank has no -e verb: the probe
  writes (println (+ 1 2)) to a file in the run dir and `jank run`s
  it, 180 s, wants "3").
- member-spec: :jank case added — jank --module-path <cp-for :jank
  + docs/stm-eve + docs/stm-ants + hosts/jank> run-main
  ants-jank-main, LC_ALL=en_US.UTF-8, config via write-cfg!.
- find-root: the root test also accepts eve/src/eve/stm.cljc (the
  composed trees carry eve.stm in-tree; the vendored docs/stm-eve/
  eve/stm.cljc exists only on the older ants trees).

## Walls found and fixed in the loop

### W1. ants' sleep! — :jank arm unreachable (reader-conditional order)
ants.cljc's sleep! carried its :jank arm AFTER :clj. Jank claims
:clj in reader conditionals, so the :clj arm (Thread/sleep) was
selected and Thread/sleep does not resolve under jank (no Thread
shim). Stage (a) run 1 died at analysis on exactly this. Fix: the
:jank arm (proc/sleep-ms!, eve.process) now precedes :clj — the
same arm-order discipline the STM wrap-up applied inside eve.stm.
(An earlier bank of this lane misdiagnosed this as the arm's BODY;
the body was already proc/sleep-ms!. The order was the defect.)

### W2. clojure.core/flush is a jank runtime stub
(flush) throws ex-info "TODO: port flesh" under jank. Both new
mains called it (copied from the bb/jolt mains). Removed;
stm_jank_main.cljc never calls it either.

### W3. THE COMPOSITION GAP — disk-tier STM seating never reached eve/src
With W1 fixed, analysis failed on stm/seat-clock!: unresolved.
Verified across every STM branch: the disk-tier seating API —
seat-clock!, seat-process-tag!, make-ref's :seat opt, the commit
lock in the cell's Eve meta-atom — exists ONLY in the vendored
copy docs/stm-eve/eve/stm.cljc (884 lines) carried by
lane-m/stm-ants and lane-m/ants-demo-package. Every proven ants
run loaded THAT copy (those trees have no eve/src/eve/stm.cljc at
all). The in-tree lineage (stm-in-eve 788 → stm-collate-0931 822 →
0931-composed 827) never received it: its clock is a private
per-process host atom, its cells mint in fresh per-process
domains, and its make-ref silently ignores :seat.

The STM wrap-up's "0931's eve.stm is a semantic superset of the
capability lane's copy" verdict is true of the in-tree lineage it
diffed (105 lines, all accounted) and does not cover the seating:
the seating lived outside that diff, in the vendored copy.
CONSEQUENCE: as composed, the release tree's STM cannot run the
ants demo on ANY seat — ants.cljc calls seat-clock!,
seat-process-tag! and :seat unconditionally. This lane's jank
member was simply the first attempt to run the demo against the
composed tree.

Fix (this branch, eve/src/eve/stm.cljc 827 → 954 lines): the
seating machinery ported from the vendored copy INTO the in-tree
file, keeping the current file as the base — the vendored copy is
behind it on retry semantics (single retry signal, no
:retry-causes stats, pre-Q-STM-WAIT await-unlocked!, bounded
lock!) and was not taken wholesale. Ported: seat-clock! /
clock-cell / clock-now / claim-point! (beyond-clock?, read-point!
and commit!'s claim routed through them); new-cell's :seat arity
(persistent-atom-domain + lookup-or-create); lock-cell-for;
make-ref's :lock-cell; seat-process-tag! + tagged attempt ids
([tag n]); lock-holder dispatch; eve-value-cas!; try-lock! /
unlock! dispatch on :lock-cell; lock!'s seated 1 ms backoff.
Regression proof: the portable STM ticket under jank on the
merged stm — PROBE-STM-PORTABLE: ALL 15 CHECKS PASS, EXIT 0,
stats {:attempts 3606 :retries 0 :commits 3603 :retry-causes {}}
identical to the wrap-up's pre-merge run; and the load bisect t2
([eve.host-completion] [eve.process] [ants] [eve.stm] on the full
module path) exits 0 with ants resolved against the merged stm.

### W5. Read-only transactions advanced the commit-point clock
Stage (a) run 4 (after W4's fix) reopened the world exactly —
audit clean in both processes — but the clock read 9 in create
and 11 in reopen, so the values-exact digest (which hashes the
seated clock atom) could never match. Mechanism: commit! claimed
a commit point UNCONDITIONALLY at its step (4), so every
transaction, including pure reads (snapshot, audit), advanced
the shared clock. The claim site predates the seating port in
both STM generations; stock's lastPoint moves on committing
writers only. Beyond the probe failure, readers claiming points
spam the seated clock atom with a swap per snapshot — the demo's
driver snapshots constantly while members transact — and widen
every ref's history-retention window. Fix (eve/src/eve/stm.cljc):
commit! claims a point only when the attempt has installs,
(when (seq installs) (claim-point!)); install! is the claim's
only consumer. With the fix, stage (a)'s create clock is exactly
8 — the 8 writing commits (2 place-ants + 6 behave steps) — and
reopen reproduces 8.

### W4. jank region adapter holds every mmap fd — EMFILE at ~30 domains
Stage (a) run 2: create died "eve.region: open .../cell-0029...
Too many open files" (then segfaulted in jank teardown on the
error path). Measured: one persistent domain maps ~37 files (7
slabs × 5 files + rmap + root); eve/adapters/region/jank's
open-mmap-region kept EVERY file's descriptor open — its
docstring stated the policy ("The descriptor stays open (the
fcntl locks of a Lustre mount need it; msync does not)") — so a
64-cell world needs ~2,400 fds against the default 1024 limit.
Only a Lustre region needs its fd (fcntl byte-range locks); a
plain region's fd is read nowhere except -sync!'s (when fd ...)
guard, and msync acts on the mapping, not the descriptor.
Fix (eve/adapters/region/jank/src/eve/region.jank): a plain
region's descriptor is closed right after mmap succeeds and its
fd field holds true (the -sync! guard still reads it as
syncable); JankLockedRegion keeps its descriptor. No other code
in the tree reads a region's fd field (grepped).

## Staged proofs

### (a) jank alone: create + reopen, values exact
docs/stm-ants/hosts/jank/run_jank_probe.sh (probe_jank_world.cljc,
dim 8 world, 2 ants, 3 behave steps per ant, digest = hash of
[snapshot, registry, clock]):

    PROBE-CREATE tag= 1 digest= 3272455857 clock= 8 ants-ok= true food-ok= true registered-ok= true ant-count= 2 violations= []
    PROBE-REOPEN tag= 2 digest= 3272455857 clock= 8 ants-ok= true food-ok= true registered-ok= true ant-count= 2 violations= []
    PROBE-VERDICT: PASS (digest + clock equal across processes)

Both processes EXIT 0. Timings on J (box load 6-40 during the
runs, mostly other lanes): create ~11 min for 65 domains (mint
~10 s/domain); reopen JOINED all 65 domains in ~1 min
(~1 s/domain). Members only ever join, so member startup is not
mint-bound.

### (b) jank as a launcher member (jvm,jank), small board
bb docs/stm-ants/demo/run.cljc --members jvm,jank --root <this
checkout> on J; run dir ants-demo-20261108-171657:

    jank                   READY (/usr/local/bin/jank)
    JANK-ANT-HOST-READY tag= 2 ants= [[2 0] [2 1]]
    JANK-ANT-HOST-DONE tag= 2 behave-calls= 328 stats= {:attempts 345, :retries 15, :commits 330, :retry-causes {:validation 15}} errors= []
    FINAL-AUDIT {:ants-ok? true, :registered-ok? true, :food-ok? true, :food-sum 15, :food-total 15, :registered 4, :ant-count 4, :violations []}
    members joined (2): jvm (driver), jank tag 2
    audits: ants-ok=true registered-ok=true food-ok=true bad-audits=0
    member jank-0 exit=0
    driver exit: 0
    VERDICT: PASS — the world hydrated fully and every audit held

(42 snapshots audited. The 15 retries are ordinary cross-process
:validation contention against the driver's ants.)

### (c) mixed run (jvm,bb,jolt,jank), small board
Same launcher, --members jvm,bb,jolt,jank; run dir
ants-demo-20261108-172245. All four seats probed READY; tags:
driver 1, jolt 2, bb 3, jank 4.

    JANK-ANT-HOST-READY tag= 4 ants= [[4 0] [4 1]]
    JANK-ANT-HOST-DONE tag= 4 behave-calls= 318 stats= {:attempts 350, :retries 30, :commits 320, :retry-causes {:validation 30}} errors= []
    FINAL-AUDIT {:ants-ok? true, :registered-ok? true, :food-ok? true, :food-sum 15, :food-total 15, :registered 8, :ant-count 8, :violations []}
    DRIVER-STATS {:attempts 16973, :retries 75, :commits 16898, :retry-causes {:validation 73, :history-trim 2}} bad-audits= 0 errors= []
    ants: 8 expected, all registered (DRIVER-ALL-IN); snapshots audited: 39
    member bb-0 exit=0
    member jolt-0 exit=0
    member jank-0 exit=0
    driver exit: 0
    VERDICT: PASS — the world hydrated fully and every audit held

## Remaining gaps

- Per-domain MINT cost under jank (~10 s/domain on a loaded box,
  ~37 files per domain) vs JOIN cost (~1 s/domain). Where the
  mint time goes (alloc/domain initialization over the C++
  interop boundary is the suspect) is unmeasured; it makes a
  jank process the wrong choice to BUILD a large world, but
  joining — the member shape — is fine. At dim 60 (3,600 cells) a
  jank member's join would run ~1 s × 3,600 ≈ an hour at the
  measured rate: the 6x60 ship shape with a jank member is NOT
  proven by this lane and would need the join path measured and
  probably optimized first.
- The seating promotion (W3) is on THIS branch only: the release
  branch lane-m/eve-release-0931 still carries the unseated stm.
  Collating this branch's eve/src/eve/stm.cljc (or re-porting the
  seating there) is required before the shipped STM can run the
  demo the release cites as its acceptance vehicle.
- The mid-commit death wedge (a process dying while holding a
  seated commit lock leaves the lock in the meta-atom with no
  dead-holder recovery) is a property of the seating design as
  ported, unchanged from the vendored copy; it is named in the
  wrap-up lineage already and was not addressed here.
