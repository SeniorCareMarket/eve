# Lane C2 — columns and batches: STATUS

Branch: `claude/static-c2-columns` (tree `/home/hatch/workspace/static-eve/c2`),
base `claude/sweet-carson-6pt29d` @ 7402ef9.

## 2026-11-12 ~01:50 ET — lane opened, reading done, design fixed

Read: IMPL-PLAN §3/§3.1/§3.3-C2, FORMAT-V1 §5 (+§2/§4/§6 as they bear on columns),
MEASUREMENTS M4 (+M2/M7 column rows), the prototype `probes/pack.clj` end to end,
W0's `eve.static.contract`, its scoreboard, corpora, and requirements manifest
(W0 is local-only so far; its manifest names my tests:
`eve.static.column-test/{column-roundtrip, column-ops-equal-host,
column-encodings-point-read, rows-and-columns-same-bytes,
column-zero-copy-handoff}`).

Coordination state at lane start: neither `origin/claude/static-c1-chunk` nor
`origin/claude/static-w0-contracts` exists yet. Per the lane brief, C2 builds
against FORMAT-V1 §5 with its own minimal scaffolding, marked as such, to be
replaced by C1's `chunk.cljc` on merge.

### Design decisions — aligned to C1's writer/reader (read firsthand from
### C1's working tree 2026-11-12 ~01:57 ET; C1 pin: branch
### claude/static-c1-chunk, commits ff30e92 + a58f383, chunk.cljc 1,866 lines
### uncommitted in its tree at read time)

Where §5 was silent, C1's `write-column!`/`parse-column`/`col-at` are the
ground truth and column.cljc matches them byte for byte:

1. Column segment = `[kind u8][validity bitmap iff kind & 0x80][data]`,
   validity LSB-first, one bit a row (present = 1), written for every kind
   (23 included) when any row is nil. Nil rows of dense/symbol/narrowed
   columns keep their slot, zero/code-0 filled.
2. Dense kinds: data aligned to the element size **from the chunk's start**,
   little-endian. C1 emits only 6 (i32), 8 (f32), 9 (f64), 10 (i64) of the
   §5 dense kinds; kinds 1–5, 7, 11 are implemented here per §5 (aligned,
   LE) and round-trip, but **C1's parser refuses them today** — a §5-vs-C1
   divergence flagged for collation (an Eve u8 array in a COL node is where
   it would bite).
3. Kind 17: u8 codes, unaligned. Kind 18: u16 codes, **unaligned** (C1
   writes `.u16` with no align). Kind 19: offsets area aligned 4, `n + 1`
   u32 offsets relative to the string-bytes area, then the bytes.
4. Kind 20 (any): `n` node offsets of the chunk's one width (u16/u32 by
   WIDE), chunk-absolute, resolved through a ctx `:node` function.
5. Kind 21 (struct) is `[21][one offset of the chunk's width]` naming a
   BATCH node elsewhere in the chunk — not an inline body. A **BATCH node**
   is `[0x26][shape varint][n varint][column offsets × fields]`, offsets
   chunk-absolute of the chunk's width, and the columns are written
   **before** the node (children first). C1 never writes validity on a
   struct column (nils in a map field fall back to kind 20); the reader
   here tolerates the flag, the encoder refuses nil struct values.
6. Kind 22 (narrowed): `[base i64][width u8 ∈ {1,2,4}][data]`, data
   **aligned to the width** from the chunk's start; read = base + unsigned
   load. Kind 23 (run-end): `[runs varint][values column segment]
   [ends u32 × runs]` — ends **unaligned**, immediately at the values
   column's end, cumulative exclusive; nil is a run value (the values
   column carries its own validity for a nil run, and the row bitmap
   marks nil rows too — C1 double-marks; matched here).
7. Kind inference in the encoder mirrors C1's `column-plan`, including
   its narrowed thresholds (n ≥ 16, range fits width) and its run-end
   wrap (base kind ∈ {6, 10, 17, 18}, n ≥ 32, runs ≤ max(2, n/16)) — so
   C2's bytes for a value are the bytes C1's writer would emit.
8. Zero-copy handoff (C2.5) on the JVM is a read-only NIO buffer view
   (`ByteBuffer/asXBuffer` over the column's own byte array): no copy, the
   backing array is shared, writes throw `ReadOnlyBufferException`.

### Next

Tests first (red): `test/eve/static/column_test.cljc` with the five named
tests + scaffolding chunk writer in `test/eve/static/column_scaffold.clj`
(marked SCAFFOLDING). Then a B7 baseline bench (host arms) before the
implementation. Box load is over the heavy-run gate (load1 ~10–19), so test
runs wait for a window; writing proceeds.

## Log

- 01:5x ET — tests-first commit `07ff5b2` (red: eve.static.column did not
  exist), scaffolding included.
- 01:57 ET — coordination correction received: no GitHub credential on this
  box; pushes are off, commits stay local, cross-lane code moves by path
  remotes (w0, c1 added). C1's chunk writer/reader read firsthand from its
  working tree and the layout decisions above replaced my first reading
  (relative batch offsets, inline struct body, aligned run-end ends —
  all three wrong against C1). Tests re-aligned: commit `635478d`.
  C1 pin recorded: branch claude/static-c1-chunk @ a58f383 + uncommitted
  chunk.cljc (1,866 lines) in its tree.
- 02:0x ET — implementation commit `0a4ba5b`: eve.static.column complete
  (encode + parse + in-place ops + typed-array-view), column_bench.clj
  written (B7 vs host arms). Desk-reviewed; runs wait on load1 < 8
  (load1 20+ at last check — the box is carrying all twelve lanes).

## 2026-11-12 ~05:40 ET — successor session (prior sessions died in restarts)

State re-derived firsthand; the log above was stale in one respect: the
suite DID run once before the restarts — 9,453 assertions, exactly one
error (the checked `(byte 240)` cast in column-zero-copy-handoff) —
fixed at `fd77ce3` (preceded by `bdb6a49`, green-run fixes). The
`fd77ce3` fix itself was never re-run. That is the open verification.

- Merged C1 (`claude/static-c1-chunk` @ `1f0e049`, incl. chunk pin
  `9ea620b`) by path remote — clean merge at `72d13ee`. The tree now
  carries `eve.static.chunk` + `eve.static.contract` beside
  `eve.static.column`; the column code is unchanged and its tests still
  drive it through the scaffold ctx, as designed.
- VENUE ORDER (operator, binding): NO heavy runs on hatch at any load.
  Two legs filed in `~/workspace/job-agent/inbox/`, lane
  `static-eve/columns`, venue_pref `j`, class heavy:
  (1) `static-eve-c2-suite` — `eve.static.column-test` (C2.1–C2.5
  correctness), log `~/static-eve-results/c2-suite.log` on J;
  (2) `static-eve-c2-b7` — `column_bench.clj` B7 arms (scan ≤2 ns/row,
  filter ≤4 ns/row, cell ≤2× host map, row view ≤1 µs), log
  `~/static-eve-results/c2-b7.log` on J.
- **BUNDLE REFRESH REQUIRED before either leg runs:**
  `~/static-eve-bundles/c2.bundle` on J must be refreshed to branch tip
  `72d13ee` (the C1 merge). The staged bundle predates it.
- Next: on the suite result — if red, re-derive from the J log, fix
  locally, commit, re-file. On the B7 result — log the row, report
  unreached gates as unreached. Then C2-REPORT.md.

## 2026-11-12 ~05:47 ET — finisher session: suite green on J; B7 failure re-derived, fixed

- **Suite on J: GREEN** (coordinator-delivered c2-suite.log): 5 tests,
  9,459 assertions, 0 failures, 0 errors, at the pre-fix tip. C2.1–C2.5
  correctness is venue-verified.
- **B7 leg on J: ERRORED** — `open-column` threw "unknown column kind"
  at column.cljc:562 on the bench's own corpus. Re-derived locally
  (light repro, 2-column batch: kind-19 strings + kind-22 ints,
  n = 10,000): the first column's segment is 138,898 bytes, so the
  second column's true chunk offset is 138,898 — but `encode-batch`
  wrote it through `put-u16!`, truncating to 7,826 (= 138,898 mod
  65,536). `open-column` landed inside column 1's offsets area and
  read byte 0 as kind 0; the ex-data {:kind 0 :offset 7826} matches
  the wrapped offset and the byte there exactly. The wide control arm
  (u32 offsets) opened cleanly with kinds [19 22] and exact reads.
  **Verdict: C2's writer, not the reader and not Chunk** — the bench
  never reaches C1's code (it encodes through the scaffold ctx), and
  every kind the bench plans (22/19/16/9/17) is a kind the reader
  accepts. Root cause: the three chunk-offset writes in the encoder
  (batch-node column offsets, kind 20 node offsets, kind 21
  batch-off) truncated silently past 65,535. In a real 64 KiB chunk
  offsets always fit u16; a larger span is what the format's WIDE
  flag (u32 offsets) exists for — and the bench's one 50,000-row
  batch spans ~600 KB without setting it.
- **Fixes (this commit):**
  1. `column.cljc`: new `put-off!` — writes an offset at the chunk's
     width and, when non-wide and past 65,535, throws a named refusal
     (`:eve.static.column/check :offset-overflow`, carrying the true
     offset) instead of truncating. All three offset sites route
     through it. Repro after fix: non-wide arm refuses at encode time
     with :offset 138898; wide arm unchanged and exact.
  2. `column_bench.clj`: the 50,000-row batch is now encoded WIDE
     (`:wide? true` in the encode opts and the scaffold ctx), and the
     docstring's "the segment code is the same either way" claim is
     corrected — the measured ops are offset-width independent, but
     the batch itself is a WIDE batch. Verified end-to-end locally at
     20,000 rows (old form overflows at that size): encode → open →
     scan/filter/cell/row arms all run, host/static agreement
     assertions pass.
  Noted, not fixed (unproven, out of the measured defect's scope):
  kind 18 writes symbol codes as u16 data with no range check — a
  symtab past 65,536 symbols in one chunk would truncate the same
  way; no corpus in the campaign approaches it.
- **Re-filed on J** (lane `static-eve/columns`, venue_pref `j`):
  (1) suite re-run after the `put-off!` change; (2) B7 bench, 50,000
  rows, min of 5. **BUNDLE REFRESH REQUIRED** to this commit before
  either leg runs.
- Next: on the delivered B7 numbers — append the row to SPIKE-LOG.md,
  write C2-REPORT.md, flip C2.1–C2.5 in requirements.edn, declare.

## 2026-11-12 ~06:03 ET — finisher #2: re-run legs re-filed in proven forms

- Finisher #1's re-run filings died instantly on J: "Could not find or
  load main class clojure.main" — the run strings captured an empty
  classpath into -cp. Form defect in the filings only; the f4a8b14 fix
  itself stands (verified locally by finisher #1).
- Re-filed (lane static-eve/columns, venue j, bindings [], class heavy):
  - `20261112-static-eve-c2-suite3.json` — suite via plain
    `clojure -M:jvm-test -n eve.static.column-test` inside the standard
    bash -c clone/checkout wrapper → `~/static-eve-results/c2-suite3.log`.
  - `20261112-static-eve-c2-b7-wide2.json` — bench in the exact run-string
    shape of the round-1 B7 filing (which ran): -Sdeps :bench alias adding
    the test path, -M:bench -i column_bench.clj → deliverable log renamed
    `~/static-eve-results/c2-b7-wide2.log`.
- Bundle on J is already at tip f4a8b14 (coordinator refreshed) — no
  refresh owed for these legs.
- Next (on coordinator delivery of the two logs): append the B7 row to
  SPIKE-LOG.md with unreached gates named as unreached, write
  C2-REPORT.md, flip C2.1–C2.5 in requirements.edn, declare.

## 2026-11-12 — close-out session: LANE COMPLETE

- Delivered evidence (coordinator read both logs firsthand):
  c2-suite3.log — 5 tests, 9,459 assertions, 0 failures, 0 errors,
  EXIT=0 at code tip f4a8b14. c2-b7-wide2.log — B7 arms measured
  (50,000 records, WIDE, min of 5, JVM 21.0.9).
- B7: all four gates MISSED as written — scan 129.52 ns/row (gate ≤2),
  filter 100.54 ns/row (gate ≤4), cell 637.1 ns = 5.1x host (gate
  ≤2x host), row view 5.346 µs (gate ≤1 µs). Static arm beats host on
  scan (1.6x) and filter (3.6x). Row set appended to SPIKE-LOG.md with
  verdicts as measured.
- C2-REPORT.md written: requirements evidence, the u16→put-off!
  defect + fix (f4a8b14) with provenance, B7 table, and the kind-18
  u16 symbol-code item flagged for collation (unfixed here).
- C2.1–C2.5 flipped to :built in requirements.edn.
- Lane declared complete.
