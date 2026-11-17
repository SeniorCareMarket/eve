# Lane C2 — columns and batches: REPORT

Branch: `claude/static-c2-columns` (tree `/home/hatch/workspace/static-eve/c2`).
Code tip under test: `f4a8b14`. Lane complete.

## Verdict

C2.1–C2.5 are met: the column implementation (`eve.static.column`) is
green on the venue at the final code tip — suite **c2-suite3.log on J:
5 tests, 9,459 assertions, 0 failures, 0 errors, EXIT=0**. The B7 board
gates are **all missed as written** (table below); the static arm still
beats the host arm on scan and filter. One real defect was found by the
bench, re-derived, and fixed en route (u16 chunk-offset truncation).

## Requirements

| req | test | evidence |
|---|---|---|
| C2.1 | `eve.static.column-test/column-roundtrip` | suite green on J (c2-suite3.log), 9,459 assertions total |
| C2.2 | `eve.static.column-test/column-ops-equal-host` | same run |
| C2.3 | `eve.static.column-test/column-encodings-point-read` | same run |
| C2.4 | `eve.static.column-test/rows-and-columns-same-bytes` | same run |
| C2.5 | `eve.static.column-test/column-zero-copy-handoff` | same run |

All five flipped to `:built` in `test/eve/static/requirements.edn`.
Column bytes are byte-for-byte the bytes C1's writer emits (the lane
aligned to C1's `write-column!`/`parse-column`/`col-at` as ground truth
where FORMAT-V1 §5 was silent; see C2-STATUS design decisions). The
zero-copy handoff on the JVM is a read-only NIO buffer view over the
column's own byte array — no copy, writes throw
`ReadOnlyBufferException`.

## Defect: u16 chunk offsets truncated silently — fixed at f4a8b14

**Provenance.** The round-1 B7 leg on J errored: `open-column` threw
"unknown column kind" (column.cljc:562) on the bench's own corpus.
Finisher #1 re-derived it locally with a minimal 2-column batch
(kind-19 strings + kind-22 ints, n = 10,000): the first column's
segment is 138,898 bytes, so the second column's true chunk offset is
138,898 — but `encode-batch` wrote chunk offsets through `put-u16!`,
truncating to 7,826 (= 138,898 mod 65,536). `open-column` landed inside
column 1's offsets area and read byte 0 as a column kind; the
exception's `{:kind 0 :offset 7826}` matches the wrapped offset and the
byte there exactly. A wide (u32) control arm opened cleanly. Verdict:
C2's writer, not the reader and not Chunk — the bench encodes through
C2's own scaffold ctx and never reaches C1's code on this path.

**Why it only bites past one chunk.** In a real 64 KiB chunk, u16
offsets always fit. The bench's single 50,000-row batch spans ~600 KB,
so the truncation is a >64 KiB-span defect, invisible to every
chunk-sized corpus — which is why the 9,459-assertion suite was green
while the bench's first act failed.

**Fix (f4a8b14).** A single `put-off!` now routes all three chunk-offset
writes — batch-node column offsets, kind-20 node offsets, and the
kind-21 batch offset. Non-wide past 65,535 it throws the named refusal
`:eve.static.column/check :offset-overflow` carrying the true offset,
instead of truncating. The B7 bench encodes its 50,000-row batch WIDE,
which is what the format's WIDE flag exists for. Verified end-to-end
locally at 20,000 rows (the old form overflows at that size): encode →
open → all four B7 arms run, host/static agreement assertions pass.
The fixed code is what c2-suite3.log and c2-b7-wide2.log ran on J.

## B7 board row — all four gates missed as written

One batch of 50,000 records (WIDE), min of 5, JVM 21.0.9, commit
f4a8b14. Source: c2-b7-wide2.log on J; appended to SPIKE-LOG.md.

| arm | host | static | gate | verdict |
|---|---|---|---|---|
| scan (sum :score) | 203.93 ns/row | 129.52 ns/row | ≤ 2 ns/row | MISS — static 1.6x faster than host |
| filter (count :dept = :eng) | 364.38 ns/row | 100.54 ns/row | ≤ 4 ns/row | MISS — static 3.6x faster than host |
| one cell (:ts, 200k random) | 123.8 ns | 637.1 ns | ≤ 2x host | MISS — static 5.1x host |
| row view (20k random) | 0.301 µs | 5.346 µs | ≤ 1 µs | MISS |

The columnar bet pays where it was aimed — sequential scan and filter
beat host maps per row — and loses on random point access: a single
cell costs 5.1x a host-map lookup and a materialized row view costs
5.346 µs against a 1 µs gate. The absolute gates (2 ns/row scan,
4 ns/row filter) are unreached by an order of magnitude on both arms;
they are reported unreached, not relabeled.

## Flagged for collation — not fixed here

- **Kind 18 symbol codes are written as u16 with no range check.** A
  chunk whose symbol table passes 65,536 symbols would truncate codes
  the same way the offset defect truncated offsets. Unproven — no
  corpus in the campaign approaches it — and outside the measured
  defect's scope, so it is flagged, not fixed.
- **§5-vs-C1 divergence (from C2-STATUS):** dense kinds 1–5, 7, 11 are
  implemented here per FORMAT-V1 §5 and round-trip, but C1's parser
  refuses them today. An Eve u8 array in a COL node is where it would
  bite.
