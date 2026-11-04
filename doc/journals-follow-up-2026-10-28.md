# Journals follow-up — completion report (2026-10-28)

Lane `claude/journals-follow-up`, base origin/main @ 13c7e845e58.
All verdicts below are read from log files under the lane worktree's
`runs/` directory; a number in no log file is not claimed.

## The shape of the fix (applies to items 1 and 2)

The journals composition landed the journal logic in shared
`eve/src/eve/atom.cljc` with the file work inline as `#?(:clj …)`
arms — dead code on every other host, and (measured) a host-leak
regression: `python3 eve/scripts/check-host-leak` on main read
conditionals 16 against a baseline of 0. The port follows
eve/CLAUDE.md's own rule (shared code names no host; hosts plug in
through same-namespace adapter files):

- New adapter feature **eve.journal-file** —
  `eve/adapters/journal-file/{java,node,clr,python,unsupported}/src/eve/journal_file.*`
  with the surface open-journal, with-journal-lock,
  journal-append!, journal-force!, journal-scan,
  journal-truncate!, journal-write-at!, journal-close!,
  journal-record, journal-record-of. Registered in `eve/hosts.edn`
  and `eve/deps.edn` for every host; jolt/clje/glj/vs get the
  **unsupported** stub, whose `:journal?` open throws honestly
  instead of the old silent no-op.
- `atom.cljc`'s journal section is host-free over eve.journal-file
  (flush / recover / anchor / retention-floor logic unchanged;
  `now-ms` is unconditionally `(proc/now-ms)`).
- Record bytes are identical on every host:
  `[magic i32 BE][len][epoch][table-len][table][crc32]`,
  magic "EVJ1", 2068 B for the 2048 B table. Scan/recovery/anchor
  semantics mirror the landed JVM behavior exactly (one deliberate
  deviation: a table length that cannot fit its own record is a
  torn-stop here; the original JVM code threw BufferUnderflow).

Post-change gate: `check-host-leak OK: conditionals 0 predicates 0
host-files 1 net-interop 0` (runs/host-leak.log) — better than the
base it branched from.

Two force-chain gaps on the brief's "force slab" path, fixed:

- `alloc/sync-sio!`'s fallback was `#?(:clj …)`-gated while its
  sibling `refresh!` calls the identical fallback unconditionally;
  ungated.
- Neither ClrRegion nor LpyRegion implemented
  `eve.mem/ISyncableRegion`, so `mem/sync-region!` (the root force
  in recovery, the flush's slab sweep) silently no-opped on both
  hosts. Both now do: CLR via msync through region.cljr's existing
  syscall emitter (file-backed regions only), lpy via `mmap.flush`
  with page-aligned ranges.

## Item 1 — ClojureCLR journal append port: COMPLETE, verified

Recorder `eve/test/eve/durability_crash_recorder_cljr.clj`, driver
`runs/crash_suite_clr.py` (the spike suite with the recorder seat
swapped; every image recovered by the **JVM** reader, so the byte
format is proven cross-host). Cells, totals from the logs:

- J1/counter — 17 pass, 0 violations (runs/crash-clr-j1-counter.log)
- J1/kb64 — 17 pass, 0 violations (runs/crash-clr-j1-kb64.log)
- J10/counter — 10 pass, 0 violations (runs/crash-clr-j10-counter.log)
- J10/kb64 — 10 unique points, all PASS, 0 violations
  (runs/crash-clr-j10-kb64.log). Caveat: the
  log's totals line reads "11 pass" because the interrupted first
  run's PASS line for `window-c5-root-ahead` was written twice
  across the resume; unique points are 10.

54 unique points, 0 violations. Plus a CLR journal smoke
(runs/clr-smoke*): journal domain, counter=3 across an unclean
exit, journal 8272 B = 4 records, all independently parsed
(epochs 3–6, CRCs valid), CLR recovery read back 3. Lock exclusion
was proven in both directions at lane start: the CLR FileStream
lock and the JVM FileChannel lock exclude each other (same OFD
space on this kernel).

One real bug found and fixed on the way: msync through the
region's syscall emitter threw EINVAL — **msync is syscall 26 on
x86-64; 227 is clock_settime** (227 is msync only on aarch64). A
futex control through the same emitter and a Python raw-syscall
control isolated it to the number; both call sites fixed.

In-tree wiring: the phase-3 runner's cljr seat now runs "J"
(journal) instead of "Jn" (eve/doc/durability-spike-evidence's
runner lineage / in-tree phase3_runner copy on this branch).

## Item 2 — Basilisp (lpy) journal append port: COMPLETE at source; cells 45/54, remainder blocked by an environment fault

The python journal-file adapter ships with item 1's seam. Two
pre-existing host-load defects blocked all eve code on the pinned
basilisp 0.5.1; per the operator's 2:52 PM directive both were
fixed at source in this lane:

1. `adapters/completion/basilisp/src/eve/host_completion.lpy` —
   the `/and_`-style symbols from 226ac4cfa3c that basilisp
   0.5.1's reader rejects ("'/' character disallowed in names")
   reverted to `operator/` spellings (8 hunks, operator call
   sites only; the file now differs from main only there and in
   fix 2).
2. The same file now patches core `aset` for Python-bytearray
   targets (mask to the low 8 bits on store — Clojure's
   aset-byte semantics; every other target delegates to core
   aset unchanged). eve.encoding's `make-bytes` answers a
   bytearray on this host, so `(aset ba i (unchecked-byte v))`
   threw for every v past 127 — eve.hamt-util's xxh3 secret
   build was the first casualty; eve.keyring's xor loop has the
   same shape. The completion is eve's designated layer for
   "basilisp.core does otherwise than Clojure" (it already
   patches the whole unchecked-* family there).
   Also fixed in the lane's own journal_file.lpy: `(.-length x)`
   is not a Python bytes property — `(python/len x)` throughout.

Result (runs/lpy-smoke-write3.log + read-back): **eve loads
end-to-end on basilisp 0.5.1, real tree, no shadow copies**; a J1
journal domain writes counter=3, exits unclean, journal 8272 B =
4 records independently parsed (epochs 3–6, CRCs valid); lpy
recovery reads counter=3 — byte-identical shape to the CLR run.

Crash cells (lpy recorder `eve/test/eve/durability_crash_recorder.lpy`,
driver `runs/crash_suite_lpy.py`, JVM reader):

- J1/counter — 17 pass, 0 violations (runs/crash-lpy-j1-counter.log)
- J1/kb64 — 17 pass, 0 violations (runs/crash-lpy-j1-kb64-resume.log;
  the first run was SIGTERM'd by a box restart at 5 banked points
  and resumed clean)
- J10/counter — 10 pass, 0 violations (runs/crash-lpy-j10-counter.log)
- J10/kb64 — **recording complete, cell incomplete**: the
  manifest shows correct J10 batching (journal-len 2068 through
  commit 9, 4136 at commits 10–11) and `p0-checkpoint` PASS is
  banked (runs/crash-lpy-j10-kb64.out); the remaining 9 points
  were blocked by the environment fault below.

Lpy subtotal: **45 points banked, 0 violations** — the same
per-cell counts as CLR everywhere both ran.

### The environment fault (blocks J10/kb64's tail, item 3 attempt 3, item 4)

From ~20:25 on 2026-10-28, file writes beyond a shrinking
per-file threshold fail with EPERM for all user processes on
this box (exec children and detached setsid processes alike;
the runtime's own file tools are unaffected). Measured live:
100 B–2 KB writes pass, 256 KB writes fail; `os.copy_file_range`
from the recorders' snapshot files fails outright. The box also
restarted mid-evening (uptime 42 min at 20:36, load ~10.6,
MemFree ~144 MB of 8 GB under heavy foreign load). The fault
killed the first J10/kb64 run mid-recovery and once truncated
the lpy driver file (rebuilt via the file tools; the driver now
copies read/write — `_safe_copy`/`_safe_copytree` — and no
longer needs copy_file_range). Resume when the box write path
recovers: re-run `python3 runs/crash_suite_lpy.py J10 kb64
<work> <out>` with the same paths — the suite skips banked
points. Nothing in the remaining work is blocked by eve code.

## Item 3 — VS-seat journal writer: NO VERDICT on the current pair (wall neither confirmed nor cleared)

The repro is the spike's require-only probe
(`runs/vs-reqatom/reqatom_main.vs` = `(require [eve.atom])`),
against the current pair (builder 02c86f56a24efd4e / core
8f09c2cfcaae599a, installed by 755132c991a; pair provenance in
the worktree confirmed).

- Attempt 1 (runs/vs-reqatom-compile.log, rc 75): failed
  EARLIER than the wall on a config gap — main's `:vs` hosts.edn
  roots have **no thread root**, so the graph dies at
  "Could not locate eve/thread_host … (required by
  digest.cljc)". The spike's compiles included
  `adapters/thread/none/src`. That gap in main's `:vs` row is
  real and recorded here for the coordinator.
- Attempt 2 (runs/vs-reqatom-compile2.log): with the thread
  root added, the batch compile hit the 2400 s timeout with an
  empty log under a heavily loaded box — the compile writes its
  trace only at process end, so a timeout banks zero evidence.
- Attempt 3 was scheduled for the quiet window after the lpy
  cells; the environment fault above forecloses it for now
  (the compile writes MBs).

The spike's own traces therefore stand as the last measurement:
`doc/durability-spike-evidence/vs-seat/reqatom-compile.txt` dies
at form 18161, "macroexpand: Unable to resolve symbol:
translate-protos in this context" (rc 75; base control on
pristine pre-journals main: form 17825, same error). No fake
writer was built; the writer remains unbuilt until the wall is
re-measured on the current pair.

## Item 4 — fast-disk phase-3 cell: NOT RUN

Gated by the brief on items 1–3 landing; item 3 has no verdict,
and the local J cell's image copying is blocked by the same
write fault. Prep is banked: `runs/phase3_runner_j.py` (JVM +
CLR writers seated "J"), server inventoried 2026-10-28 (JDK 21 +
clojure-tools present, no cljr — JVM writers suit the disk
comparison; 27 G free; load was 8.17 on 4 cores at inventory).

## Regression bars

- JVM suites (eve.tally-test, eve.jvm-atom-test,
  eve.jvm-atom-cas-test, eve.jvm-atom-lost-attempt-test,
  eve.jvm-atom-retire-test): **47 tests / 419 assertions /
  0 failures** — measured on the refactored tree
  (runs/jvm-suites.log) and re-measured after the basilisp
  completion fix (runs/jvm-suites2.log).
- check-host-leak: green at 0/0/1/0 (baseline on main was 16
  conditionals).
- CLR cells: untouched by the lpy work (54 points stand).

## What remains open

1. Lpy J10/kb64's last 9 points — environment-blocked; resume
   command above; recording + manifest already correct.
2. Item 3 attempt 3 (VS wall verdict on the current pair) —
   environment-blocked; if the wall stands, the VS-seat writer
   stays unbuilt by design (no workaround per the brief).
3. Item 4 — gated on 1–3; prep banked.
4. For the coordinator: main's `:vs` hosts.edn row lacks a
   thread root (item 3, attempt 1 evidence).
