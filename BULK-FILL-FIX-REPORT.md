# Eve-core bulk fill fix — completion declaration

Branch: `eve-bulk-fill-fix` on SeniorCareMarket/eve, off main
4a9212981680 (the shipped 0.9.3.1 tree). One source file changed:
`src/eve/mem.cljc`. PR: **#35** —
https://github.com/SeniorCareMarket/eve/pull/35 — opened against
main with this report's numbers in the body; state verified open
and unmerged after creation (the operator merges Eve PRs).
This file is the banked half of the lane's double declaration; the
other half is the lane's final chat message, same substance.

## The finding (re-derived at the pin, not taken on the brief's word)

- `eve.deftype-proto.coalesc/DEFAULT_MMAP_DESCRIPTORS` = 1,048,576.
  Domain creation fills two i32 columns (status, lock) of
  n = 1,048,575 slots each through `eve.mem/fill-i32!`.
- `eve.mem`'s `IFillRegion` protocol carried an extend-default that
  looped per slot over `-store-i32!` — 2,097,150 single protocol
  stores per domain creation on every region adapter without a
  native fill. Only the jank and js region adapters implement
  `-fill-i32!` natively; JVM (nio/varhandle), CLR, Dart, BEAM,
  Python, WASM and every other adapter paid the per-slot loop.
- Provenance: the F2 diagnosis on the cohost line (REBASE-REPORT.md
  addendum, atom.broker lane-m/cohost-on-eve-0931) — creation took
  minutes on an expensive-store device region; on the JVM it is
  seconds, paid silently on every domain creation. Re-derived here
  from the SCM/eve main tree and fresh measurements (below): the
  claim is CONFIRMED, not a phantom.

## The fix (src/eve/mem.cljc only — no adapter touched, no other file)

The extend-default `-fill-i32!` now patterns the value once into a
byte-array chunk (65,536 slots = 256 KiB, little-endian — every Eve
region is little-endian) and writes it with `-write-bytes!`: a
1,048,575-slot column is 16 chunk writes + a patterned tail instead
of 1,048,575 single stores. Same bytes, same ascending span order.
The per-slot atomicity of the old default is not load-bearing:
fills are contracted to fresh, unpublished ranges (domain creation,
growth, slab init), and the native jank/js fills are likewise plain
fills. Adapters with a native `-fill-i32!` are unaffected (protocol
dispatch prefers their own implementation, as before). The change
uses only vocabulary mem.cljc already referred from eve.host
(byte-array/aset/unchecked-byte/unsigned-bit-shift-right), so the
portable surface is unchanged.

## Measurements (JVM varhandle region, 32 MiB file-backed region, hatch)

Fill of one 1,048,575-slot column; bytes verified against an
independent in-process SHA-256 oracle (expected little-endian
pattern) after every fill — hash-match=true in both arms, plus
edge counts 0, 1, 65,535, 65,536, 65,537 all matching.

- BEFORE (per-slot default), steady-state reps on resident pages:
  ~135–710 ms per column fill (runs at box load 17–26).
- AFTER (bulk default), steady-state reps: 4.5–7.9 ms per column
  fill (load ~2). First-touch fills in fresh regions cost more in
  both arms — that is page faults on the new region file, not the
  fill mechanism.
- Domain creation (eve.atom/create-domain! end to end, fresh dir,
  median of 5): BEFORE 932 ms (load ~17) and 2,103 ms (load ~26);
  AFTER 370 ms (load ~26) and 373 ms (load ~2). Fresh domains from
  the fixed code deref and transact normally (probe atom = 42).

## Gates — all green

- DataScript-on-Eve smoke via the shipped arrangement
  (contract-test/lib-ds as a tools.deps lib, Eve via its
  local/root): PASS. Heap conn round: 20 datoms, query exact;
  persistent mmap domain round: 20 datoms, query exact;
  fresh-process reopen of the created domain: 20 datoms, query
  exact.
- Eve JVM suite: PASS — 659 tests, 0 failures, 0 errors across all
  91 test namespaces (the count matches the fleet's recorded
  a-jvm-suite total of 659 tests exactly). Run in chunks because
  this box is oversubscribed (load peaked at 41): 22 namespaces on
  hatch via eve.runner.bb-main (152 tests, 3,810 assertions,
  fail 0 / error 0) and the remaining 69 on J via the job agent
  (job 20261110T200012, DONE EXIT=0) using the tree's own :jvm-test
  cognitect runner (507 tests, 6,861 assertions, 0 failures,
  0 errors; 12/12 chunks exit 0). Assertion totals are as each
  runner counts them.
- Environmental deaths on the way, none of them test failures:
  two whole-suite JVMs and two chunks died on hatch (SIGBUS from
  mmap test domains on the 100%-full /tmp tmpfs — the hs_err
  memory map names /tmp/eve-keyring-* mappings; SIGKILL under box
  memory pressure; one VM replacement). All remaining runs used a
  disk-backed tmpdir. eve.keyring-test, the SIGBUS victim, passed
  in the re-run.

## Provenance line

Tree: SCM/eve main 4a9212981680; local mem.cljc verified
byte-identical to remote main by blob hash (90d01bc6…) before
editing; the branch's mem.cljc read back after push as blob
b1c093b9…, byte-identical to the measured local file. Tools:
JDK 21.0.13 / Clojure CLI 1.12.6.1673 on hatch; J's JDK 21 +
clojure CLI for the suite remainder. Fresh staging for every
measurement (new region files, new domain dirs). Reconcile: the
shape passes natively on jank/js (native fill) — the defect lived
exactly in the extend-default those seats never execute.

## What this unblocks

The cohost release freeze (operator's ruling, 2026-11-10: this fix
lands first). After the operator merges, the cohost line re-pins
to the new Eve.
