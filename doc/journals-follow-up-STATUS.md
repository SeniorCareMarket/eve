# journals-follow-up — STATUS

Lane opened 2026-10-28 (John's order, after the journals composition
merged to main). Base: origin/main @ 13c7e845e58 (verified by
ls-remote). Branch: claude/journals-follow-up. Worktree:
~/workspace/wt/journals-follow-up.

## The gap (measured, spike report §phase-3 census)

ClojureCLR reads .cljc with features #{:cljr}, Basilisp with #{:lpy}
— re-verified this lane with the census featprobe on both hosts
(:took-cljr / :took-lpy; a bare :clj arm falls to :default). Every
journal host op in eve/src/eve/atom.cljc is spelled #?(:clj … :cljs …)
with no :cljr/:lpy arm, so on those seats the defns compile empty:
journal-open! returns nil, journal-with-lock never calls f,
journal-flush!/recover!/anchor! are no-ops. A CLR seat opened with
:journal? true creates no .journal file at all (census-cljr domain
listing: root+slabs+rmap, no journal). The phase-3 runner therefore
seats cljr/lpy in J cells as "Jn" non-journal participants.

## Items

1. **CLR journal append port** — :cljr arms for journal-record-bytes,
   journal-scan, journal-with-lock, journal-flush!, journal-recover!,
   journal-open!, journal-record-bytes-of, journal-anchor!,
   journal-close! in eve/src/eve/atom.cljc; byte format identical
   (EVJ1, big-endian, CRC-32/ISO-HDLC). Then seat cljr as "J" (not
   "Jn") in crash cells.
2. **Basilisp journal append port** — same, :lpy arms (os/fcntl/zlib
   via Python interop, mirroring the :cljs arms' inline js/require).
3. **VS-seat journal writer** — re-check the translate-protos
   deftype wall on the current shipped pair (builder 02c86f56a24efd4e,
   installed on main by 755132c991a). If it stands: bank the minimal
   repro on current main and report. If it moved: build the writer
   via the coop route and prove it in a crash cell.
4. (If 1–3 land) fast-disk phase-3 cell on j.slaa.us.

## Verification bar

- Crash-suite cells per host touched: per-host recorder (J1/J10,
  counter + kb64) → JVM crash reader on materialized images
  (cross-host byte-format proof), totals from the suite's own
  "# totals:" line.
- JVM suites stay 47 tests / 419 assertions / 0 failures.
- Lock exclusion measured cross-process (JVM FileChannel.lock is
  OFD fcntl on this box): the CLR/lpy journal lock must exclude a
  JVM holder and vice versa, or the port does not count.

## Design (settled 2026-10-28, after probes + eve/CLAUDE.md)

eve/CLAUDE.md's first rule: shared code names no host; a host plugs
in through an adapter. The spike's inline #? arms are why
scripts/check-host-leak is RED on current main (conditionals 16 >
baseline 0; 15 in atom.cljc + 1 in alloc.cljc). So the port does NOT
grow inline arms. Instead:

- New adapter feature eve.journal-file (same-namespace per-host
  files, the eve.process / eve.process-slab pattern):
  adapters/journal-file/{java,node,clr,python,unsupported}.
  Surface: open-journal, with-journal-lock, journal-append!,
  journal-force! (full? — JVM .force false for appends, true at
  truncate/checkpoint), journal-scan, journal-truncate!,
  journal-write-at!, journal-close!, journal-record (codec, from a
  root region), journal-record-of (codec, explicit epoch+table).
  atom.cljc's journal section becomes host-free over it (flush /
  recover / anchor / floor logic unchanged); hosts.edn gains one
  root per host (jolt/clje/glj/vs get the unsupported stub — a
  :journal? open there fails honestly instead of silently no-oping).
- The force chain has two more dead links on cljr/lpy, both in the
  brief's "force slab" scope: (a) ClrRegion/LpyRegion do not
  implement eve.mem/ISyncableRegion, so mem/sync-region! (the root
  force in journal-recover!, the slab sweep) no-ops there — add
  -sync!/-sync-range!: CLR via msync(227) through region.cljr's
  existing syscall6 emitter (file-backed regions only), lpy via
  mmap.flush. (b) alloc/sync-sio!'s fallback is #?(:clj …)-gated
  while its sibling refresh! calls the identical fallback
  unconditionally — ungate it (mem/sync-region! already no-ops on
  regions that cannot force, so other hosts are unaffected).
  Expected end state: check-host-leak back at baseline (0/0/1/0).

## Probe results (2026-10-28, runs/ in this worktree)

- Lock exclusion, measured cross-process both directions:
  CLR FileStream.Lock blocks behind a JVM FileChannel.lock and a
  JVM tryLock fails against a CLR-held Lock (same OFD lock space);
  lpy fcntl.lockf LOCK_EX likewise excludes/is excluded by the JVM
  lock (classic POSIX and OFD locks conflict on this kernel).
  CLR Lock accepts a 0-length file and a 2^62 length — lock [0, 2^62).
- CLR bytes: byte[] aget is unsigned; CRC-32 must mask to 32 bits
  each step (ClojureCLR bit ops widen to long; the naive JS port
  reads -232181308 for "123456789", the masked port reads the
  correct -873187034). FileStream SetLength/Write/Read/Position/
  Flush(true) all verified, round-trip CRC intact.
- lpy bytes: (:import os fcntl zlib struct) all resolve;
  (python/__import__ "os") works from shared-code position (the
  :cljs arms' inline js/require pattern); struct BE pack/unpack_from,
  zlib/crc32 (= 3421780262 for the check vector, convert to signed),
  bytearray extend over array("b") via python/bytes, os
  write/fsync/ftruncate/fstat/lseek all verified.

## Log

- 2026-10-28 lane open: base verified, worktree + branch created,
  journal surface mapped (all host ops live in eve/src/eve/atom.cljc,
  lines ~413–870), reader features re-measured on both hosts.
- 2026-10-28 probes complete (above); design settled on the
  adapter route per eve/CLAUDE.md; sync-sio!/ISyncableRegion gaps
  found and folded into scope.
- 2026-10-28 IMPLEMENTED (commit 51fcc328c51): eve.journal-file
  adapters (java/node/clr/python/unsupported), hosts.edn + deps.edn
  roots, atom.cljc journal section host-free, sync-sio! ungated,
  ISyncableRegion for ClrRegion (msync — x86-64 syscall 26; an
  initial 227 was clock_settime, EINVAL, diagnosed via futex
  control + /proc/self/maps) and LpyRegion (mmap.flush).
  check-host-leak GREEN (0/0/1/0, was 16 conditionals).
  JVM suites 47 tests / 419 assertions / 0 failures (runs/jvm-suites.log).
- 2026-10-28 CLR smoke GREEN (runs/clr-smoke*): J1 domain, sync! +
  3 inc swaps exit-unclean; journal 8272 B = 4 records x 2068;
  independent Python parse: epochs 3-6, all CRCs valid; CLR recovery
  read-back counter=3.
- 2026-10-28 LPY BLOCKED (pre-existing, on pristine main): basilisp  0.5.1 (PyPI latest; eve's pin) cannot load eve at all on current
  main — (1) adapters/completion/basilisp host_completion.lpy uses
  /and_-style symbols (commit 226ac4cfa3c) its reader rejects
  ("'/' character disallowed in names", line 45); eve.host requires
  the completion, so every eve namespace is unreachable. (2) With a
  spelling-shadowed completion copy (runs/lpy-shadow, verification
  scaffolding only), eve.hamt-util then throws in core aset
  (unchecked-byte negative into bytearray). Both reproduce with no
  journal code involved. journal_file.lpy itself parses cleanly
  under the 0.5.1 reader (17 forms) and its primitives were probed
  green at lane start (fcntl lock exclusion both directions,
  struct/zlib/bytes). End-to-end lpy cells wait on the host load
  defect, which is outside this lane's files.
- 2026-10-28 CLR crash cell J1/counter GREEN: 17 pass, 0 violations
  (runs/crash-clr-j1-counter.log) — CLR recorder
  (test/eve/durability_crash_recorder_cljr.clj, new) writes the
  journal, JVM reader recovers every materialized crash point.
  Manifest journal-lens 2068/4136/6204/8272/10340 (one record per
  J1 commit).
- 2026-10-28 CLR crash cell J1/kb64 GREEN: 17 pass, 0 violations
  (runs/crash-clr-j1-kb64.log). CLR crash cell J10/counter GREEN:
  10 pass, 0 violations (runs/crash-clr-j10-counter.log).
- 2026-10-28 2:28 PM: J10/kb64 RUNNING (7 points in, all PASS so
  far; driver + JVM reader alive). Item 3 (VS-seat re-check on pair
  02c86f56a24efd4e) starts when it lands; item 4 after.
- 2026-10-28 ITEM 1 VERIFIED COMPLETE: J10/kb64 finished — 10
  unique points, all PASS, 0 violations (the log's final totals
  line reads 11: the interrupted first run's PASS line for
  window-c5-root-ahead was written twice across the resume; unique
  points are 10). CLR crash cells total: J1/counter 17, J1/kb64 17,
  J10/counter 10, J10/kb64 10 — 54 unique points, 0 violations,
  every image recovered by the JVM reader. Plus the CLR smoke
  (counter=3, records independently parsed), JVM suites 47/419/0,
  host-leak gate green.
