# Eve-core bulk fill fix — eve-bulk-fill-fix

Status: IN PROGRESS — fix + measurements + DataScript smoke complete;
the JVM suite is being completed in chunks (box load; see Gates).
This file is updated as the remaining gate results land; the PR opens
when the suite is fully green. Do not merge — the operator merges Eve PRs.

## The finding (re-derived at the pin, not taken on the brief's word)

Provenance: SeniorCareMarket/eve main = 4a9212981680 (the shipped
0.9.3.1 tree). Tools: JDK 21.0.13, Clojure CLI 1.12.6.1673, hatch box.

- `eve.deftype-proto.coalesc/DEFAULT_MMAP_DESCRIPTORS` = 1,048,576.
  Domain creation fills two i32 columns (status, lock) of
  n = 1,048,575 slots each through `eve.mem/fill-i32!`.
- `eve.mem`'s `IFillRegion` protocol carried an extend-default that
  looped per slot over `-store-i32!` — 2,097,150 single protocol
  stores per domain creation on every region adapter without a
  native fill. Only the jank and js region adapters implement
  `-fill-i32!` natively; the JVM (nio/varhandle), CLR, Dart, BEAM,
  Python, WASM and every other adapter paid the per-slot loop.
- This is the F2 diagnosis from the cohost line (REBASE-REPORT.md
  addendum, lane-m/cohost-on-eve-0931): on an expensive-store device
  region, creation took minutes; on the JVM it is seconds, paid
  silently on every domain creation.

## The fix (src/eve/mem.cljc only — no adapter touched)

The extend-default `-fill-i32!` now patterns the value once into a
byte-array chunk (65,536 slots = 256 KiB, little-endian — every Eve
region is little-endian) and writes it with `-write-bytes!`: a
1,048,575-slot column is 16 chunk writes + a patterned tail instead
of 1,048,575 single stores. Same bytes, same ascending span order.
The per-slot atomicity of the old default is not load-bearing: fills
are contracted to fresh, unpublished ranges (domain creation,
growth, slab init), and the native jank/js fills are likewise plain
fills. Adapters with a native `-fill-i32!` are unaffected (protocol
dispatch prefers their own implementation, as before). The change
uses only vocabulary mem.cljc already referred from eve.host
(byte-array/aset/unchecked-byte/unsigned-bit-shift-right), so the
portable surface is unchanged.

## Measurements (JVM varhandle region, 32 MiB file-backed region, hatch)

Fill of one 1,048,575-slot column, bytes verified against an
independent in-process SHA-256 oracle (expected little-endian
pattern) after every fill — all hash-match=true, before and after.

- BEFORE (per-slot default), steady-state reps on resident pages:
  ~135–710 ms per column fill across runs (box load 17–26 during
  those runs).
- AFTER (bulk default), steady-state reps: 4.5–7.9 ms per column
  fill (load ~2). Two cold first-touch fills in the same runs:
  1,193 / 189 / 492 ms — that cost is first-touch page faults on the
  fresh region file, present in both arms.
- Domain creation (eve.atom/create-domain! end to end, fresh dir,
  median of 5): BEFORE 932 ms (load ~17) and 2,103 ms (load ~26);
  AFTER 370 ms (load ~26) and 373 ms (load ~2). Fresh domains from
  the fixed code deref and transact normally (probe atom = 42), and
  a DataScript db built in a fresh domain reads back exactly from a
  separate process (below).

## Gates

- DataScript-on-Eve smoke via the shipped arrangement
  (contract-test/lib-ds as a tools.deps lib, Eve via its local/root):
  PASS. Heap conn round: 20 datoms, query exact; persistent mmap
  domain round: 20 datoms, query exact; fresh-process reopen of the
  created domain: 20 datoms, query exact.
- Eve JVM suite (eve.runner.bb-main shared set, run in chunks of
  ≤10 namespaces on this loaded box; the canonical :jvm-test
  cognitect run covers the same set plus the JVM-only namespaces):
  IN PROGRESS — chunks green so far are listed in the PR body when
  it opens; two earlier whole-suite attempts and two chunk attempts
  died environmentally (SIGBUS from mmap test domains on the
  100%-full /tmp tmpfs — hs_err memory map shows /tmp/eve-keyring-*
  mappings; SIGKILL under box memory pressure; one VM replacement),
  never on a test failure. Test temp dirs are redirected to disk
  for all remaining runs.

## Provenance line

Tree: SCM/eve main 4a9212981680 (local mem.cljc verified
byte-identical to remote main by blob hash before editing). Tools:
JDK 21.0.13 / Clojure CLI 1.12.6.1673 on hatch, PATH as recorded in
the run scripts (~/workspace/eve-fill-fix-wt/). Fresh staging for
every measurement (new region files, new domain dirs). Reconcile:
the shape passes natively on jank/js (native fill) — the defect
lived exactly in the extend-default those seats never execute.
