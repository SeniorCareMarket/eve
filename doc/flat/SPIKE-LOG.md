# Flat Eve — SPIKE-LOG

The scoreboard of IMPL-PLAN §3.1, appended by every run.

## 2026-11-12 02:37 ET — W0.6 baseline — commit 5d4b4f6 - JVM 21.0.13 - min of 3 runs - load1 ?

| row | corpus           | today                       | flat arm  | gate                                                                                           |
| --- | ---------------- | --------------------------- | --------- | ---------------------------------------------------------------------------------------------- |
| B1  | map 10k int->int | —                           | not built | ≤ 50 ns/block                                                                                  |
| B2  | map 10k int->int | 9931.98 ms (net 9528.80 ms) | not built | ≤ 2x row B1                                                                                    |
| B3  | map 10k int->int | 198.28 ms                   | not built | arm at least 2x faster than @a on every corpus                                                 |
| B4  | map 10k int->int | 1683.78 ms                  | not built | ≤ 2x (row B3 + row B1)                                                                         |
| B5  | map 10k int->int | 9931.98 ms                  | not built | under the probe's 0.37-0.56x of today's swap!                                                  |
| B6  | map 10k int->int | transit not on classpath    | not built | ≤ 0.9x Transit MessagePack (messages, records)                                                 |
| B7  | map 10k int->int | transit not on classpath    | not built | decode ≥ 2.5x Transit - scan ≤ 2 ns/row - filter ≤ 4 ns/row - cell ≤ 2x host - row view ≤ 1 µs |
| B8  | map 10k int->int | transit not on classpath    | not built | ≤ 5% over the whole value compressed as one                                                    |

## 2026-11-12 03:22 ET — W0.6 baseline — commit 7f27f8e - JVM 21.0.13 - min of 3 runs - load1 5.71

| row | corpus           | today                                             | flat arm  | gate                                                                                           |
| --- | ---------------- | ------------------------------------------------- | --------- | ---------------------------------------------------------------------------------------------- |
| B1  | map 10k int->int | —                                                 | not built | ≤ 50 ns/block                                                                                  |
| B2  | map 10k int->int | 2329.38 ms (incl. domain; domain alone 193.58 ms) | not built | ≤ 2x row B1                                                                                    |
| B3  | map 10k int->int | 198.42 ms                                         | not built | arm at least 2x faster than @a on every corpus                                                 |
| B4  | map 10k int->int | 3898.34 ms                                        | not built | ≤ 2x (row B3 + row B1)                                                                         |
| B5  | map 10k int->int | 2329.38 ms                                        | not built | under the probe's 0.37-0.56x of today's swap!                                                  |
| B6  | map 10k int->int | Transit MessagePack 59235 B                       | not built | ≤ 0.9x Transit MessagePack (messages, records)                                                 |
| B7  | map 10k int->int | Transit decode 209.46 ms                          | not built | decode ≥ 2.5x Transit - scan ≤ 2 ns/row - filter ≤ 4 ns/row - cell ≤ 2x host - row view ≤ 1 µs |
| B8  | map 10k int->int | whole value as one gzip stream: 23984 B           | not built | ≤ 5% over the whole value compressed as one                                                    |

B4 (atom->atom) is timed as ONE run: the source value read under the pin and stored through the target domain's :foreign-policy :copy — never a sum of parts.

## 2026-11-12 03:25 ET — W0.6 baseline — commit da3fe92 - JVM 21.0.13 - min of 3 runs - load1 18.56

| row | corpus           | today                                            | flat arm  | gate                                                                                           |
| --- | ---------------- | ------------------------------------------------ | --------- | ---------------------------------------------------------------------------------------------- |
| B1  | vector 100k ints | —                                                | not built | ≤ 50 ns/block                                                                                  |
| B2  | vector 100k ints | 2487.32 ms (incl. domain; domain alone 96.44 ms) | not built | ≤ 2x row B1                                                                                    |
| B3  | vector 100k ints | 102.02 ms                                        | not built | arm at least 2x faster than @a on every corpus                                                 |
| B4  | vector 100k ints | 3907.36 ms                                       | not built | ≤ 2x (row B3 + row B1)                                                                         |
| B5  | vector 100k ints | 2487.32 ms                                       | not built | under the probe's 0.37-0.56x of today's swap!                                                  |
| B6  | vector 100k ints | Transit MessagePack 368549 B                     | not built | ≤ 0.9x Transit MessagePack (messages, records)                                                 |
| B7  | vector 100k ints | Transit decode 193.79 ms                         | not built | decode ≥ 2.5x Transit - scan ≤ 2 ns/row - filter ≤ 4 ns/row - cell ≤ 2x host - row view ≤ 1 µs |
| B8  | vector 100k ints | whole value as one gzip stream: 212000 B         | not built | ≤ 5% over the whole value compressed as one                                                    |

B4 (atom->atom) is timed as ONE run: the source value read under the pin and stored through the target domain's :foreign-policy :copy — never a sum of parts.

## 2026-11-12 03:31 ET — W0.6 baseline — commit 92db4d6 - JVM 21.0.13 - min of 3 runs - load1 23.73

| row | corpus             | today                                            | flat arm  | gate                                                                                           |
| --- | ------------------ | ------------------------------------------------ | --------- | ---------------------------------------------------------------------------------------------- |
| B1  | vector 10k records | —                                                | not built | ≤ 50 ns/block                                                                                  |
| B2  | vector 10k records | 4102.48 ms (incl. domain; domain alone 21.15 ms) | not built | ≤ 2x row B1                                                                                    |
| B3  | vector 10k records | 95.71 ms                                         | not built | arm at least 2x faster than @a on every corpus                                                 |
| B4  | vector 10k records | 9345.98 ms                                       | not built | ≤ 2x (row B3 + row B1)                                                                         |
| B5  | vector 10k records | 4102.48 ms                                       | not built | under the probe's 0.37-0.56x of today's swap!                                                  |
| B6  | vector 10k records | Transit MessagePack 358526 B                     | not built | ≤ 0.9x Transit MessagePack (messages, records)                                                 |
| B7  | vector 10k records | Transit decode 1036.75 ms                        | not built | decode ≥ 2.5x Transit - scan ≤ 2 ns/row - filter ≤ 4 ns/row - cell ≤ 2x host - row view ≤ 1 µs |
| B8  | vector 10k records | whole value as one gzip stream: 65556 B          | not built | ≤ 5% over the whole value compressed as one                                                    |

B4 (atom->atom) is timed as ONE run: the source value read under the pin and stored through the target domain's :foreign-policy :copy — never a sum of parts.

## 2026-11-12 04:00 ET — W0.6 baseline — commit 7054091 - JVM 21.0.13 - min of 3 runs - load1 8.22

| row | corpus           | today                                             | flat arm  | gate                                                                                           |
| --- | ---------------- | ------------------------------------------------- | --------- | ---------------------------------------------------------------------------------------------- |
| B1  | 5k chat messages | —                                                 | not built | ≤ 50 ns/block                                                                                  |
| B2  | 5k chat messages | 6305.34 ms (incl. domain; domain alone 195.58 ms) | not built | ≤ 2x row B1                                                                                    |
| B3  | 5k chat messages | 282.73 ms                                         | not built | arm at least 2x faster than @a on every corpus                                                 |
| B4  | 5k chat messages | 4868.24 ms                                        | not built | ≤ 2x (row B3 + row B1)                                                                         |
| B5  | 5k chat messages | 6305.34 ms                                        | not built | under the probe's 0.37-0.56x of today's swap!                                                  |
| B6  | 5k chat messages | Transit MessagePack 874085 B                      | not built | ≤ 0.9x Transit MessagePack (messages, records)                                                 |
| B7  | 5k chat messages | Transit decode 285.61 ms                          | not built | decode ≥ 2.5x Transit - scan ≤ 2 ns/row - filter ≤ 4 ns/row - cell ≤ 2x host - row view ≤ 1 µs |
| B8  | 5k chat messages | whole value as one gzip stream: 174004 B          | not built | ≤ 5% over the whole value compressed as one                                                    |

B4 (atom->atom) is timed as ONE run: the source value read under the pin and stored through the target domain's :foreign-policy :copy — never a sum of parts.

## 2026-11-12 04:41 ET — Z4 B2 (doc/flat/z4_bench.clj, messages slice) — commit 15c9cc8 - JVM 21.0.13 - min of 5 runs after 2 warmup - load1 3.86 at start, rising to ~24 mid-run

| row | corpus           | today                                                                       | flat arm                                                                         | gate               |
| --- | ---------------- | --------------------------------------------------------------------------- | -------------------------------------------------------------------------------- | ------------------ |
| B2  | 5k chat messages | placed swap!, counts walked: 8484.55 ms (2.87x placement alone, 2957.20 ms) | placed swap!, counts written at the zip (F5): 9977.79 ms (3.37x placement alone) | ≤ 2x row B1 — MISS |

B2 here is lane Z4's bench (doc/flat/z4_bench.clj), not the W0 scoreboard's
B2: the placement is the tally-test harness's per-block placement (alloc! +
byte copy + pointer pass — both counting arms share it, so the arms isolate
the count; absolute placement is row B1's business, lanes Z2/Z3). The graph
is 70,997 blocks. Arms run sequentially in one session — placement alone,
then walked, then written — and the box's load1 rose from 3.86 to ~24 across
the session, against the later arms; the row is banked as measured, and the
load history is part of the row. Whole-suite runs died first (silent exit at
-Xmx4g under fleet memory pressure; OutOfMemoryError in make-heap-region at
-Xmx2g on the second corpus), so the bench takes an optional corpus filter
and each corpus runs in its own JVM; the other three corpora's B2 rows are
not yet run. An earlier map-10k row from a load1-26.81 session (arms
inverted: placement alone slower than placement+walk) was judged a
load-storm artifact and is NOT banked — see Z4-STATUS.md.

> Note (T1, 2026-11-12): this file is created by lane T1 to carry its B4
> row. W0.6's baseline row — atom → atom through today's
> `:foreign-policy :copy` as one run — is still owed by W0 and is NOT
> present below; nothing here stands in for it.

## 2026-11-12 — B4 atom → atom (lane T1, pin a1eaab6, J, JVM 21.0.9)

Method: `doc/flat/probes-t1/b4_bench.clj`, min of 5 runs after 2
warmups, one session, canonical corpora. Arms: detach = T1's walk;
zip = Z3 `place-image!`; transfer = `eve.flat/transfer!`. Correctness
first, untimed: every measured corpus read back equal under both arms
(zip-ok = tf-ok = true). Log: `t1-b4-4.log` on J.

| corpus                    | detach       | zip          | detach+zip   | transfer!    | ratio (gate ≤ 2×) | cap   | verdict                  |
| ------------------------- | ------------ | ------------ | ------------ | ------------ | ----------------- | ----- | ------------------------ |
| map-10k (346,952 B)       | 844.68 ms    | 807.32 ms    | 1,652.00 ms  | 1,539.74 ms  | 0.93 PASS         | 5 ms  | cap MISSED               |
| vec-100k (3,677,808 B)    | 15,699.44 ms | 12,347.38 ms | 28,046.81 ms | 28,645.78 ms | 1.02 PASS         | 26 ms | cap MISSED               |
| records-10k (2,167,976 B) | 4,230.98 ms  | 3,395.83 ms  | 7,626.80 ms  | 8,371.81 ms  | 1.10 PASS         | 9 ms  | cap MISSED               |
| messages-5k               | —            | —            | —            | —            | —                 | 26 ms | NO ROW — see disposition |

**Composition verdict:** transfer! ≈ detach+zip composed (ratios
0.93 / 1.02 / 1.10) — the composition itself carries no measurable
tax. **Caps missed by ~300–1000× on every measured corpus.**

**Stale-components caveat (binding on how this row may be read):** the
components measured here are NOT the current ones. This pin's detach
walk is T1's pre-collation walk; Detach's four walk defects (HAMT
pointer-base, unbounded copy window, FlatSio state access, set-node
layout) were fixed in Z1's own tree (tip e3320f4) and are not merged
here, and Zip's place has likewise advanced past this tree
(beb3ce9+). B4 at this pin therefore measures _composition
correctness_, not the caps. The caps' real verdict belongs to the
collated tree and must be re-measured there.

**messages-5k disposition:** the delivered log ends after the
records-10k row — no messages row, no `B4 BENCH DONE` line, and no
error or refusal text (verified against the complete file on J,
6 lines). A bench-level refusal or exception would have printed (the
detach refusal arm is captured and reported inline by the bench), so
the absence is a truncated run — the process died mid-corpus without
a message (SIGKILL-class: leash or OOM), not a measured refusal.
No messages verdict exists at this pin; a messages-only re-run
(`--only messages-5k`, added by finisher #3) is filed to close it.

## 2026-11-12 — C1 bench on J — commit acee946 (code 1f0e049) - JVM 21.0.9 - min of 5 runs, both arms in one session

| row | corpus             | today                                 | flat arm                                                           | gate                                           |
| --- | ------------------ | ------------------------------------- | ------------------------------------------------------------------ | ---------------------------------------------- |
| B6  | 5k chat messages   | Transit MessagePack 874,085 B         | packed 699,824 B in 11 chunks = 0.801x Transit — GATE PASS         | ≤ 0.9x Transit MessagePack (messages, records) |
| B7  | 5k chat messages   | Transit decode 68.4 ms (13.69 µs/row) | packed decode 879.0 ms (175.81 µs/row) = 0.08x Transit — GATE MISS | decode ≥ 2.5x Transit                          |
| B6  | vector 10k records | Transit MessagePack 646,605 B         | packed 294,016 B in 5 chunks = 0.455x Transit — GATE PASS          | ≤ 0.9x Transit MessagePack (messages, records) |
| B7  | vector 10k records | Transit decode 44.7 ms (4.47 µs/row)  | packed decode 499.4 ms (49.94 µs/row) = 0.09x Transit — GATE MISS  | decode ≥ 2.5x Transit                          |

B7 decode here is the FULL-MATERIALIZATION arm (chunk/unpack of the
whole value). The remaining B7 sub-gates (scan ≤ 2 ns/row, filter
≤ 4 ns/row, cell ≤ 2x host, row view ≤ 1 µs) are measured by the
Columns lane's bench over column batches, not by this bench; they are
not C1 rows. Verdict in one line: the packed form is smaller than
Transit MessagePack on both corpora (0.801x / 0.455x, gates passed)
and its full decode is ~11–12x slower than Transit's (0.08x / 0.09x,
gates missed — reported as missed, not relabeled).

## 2026-11-12 06:03 ET — C2 board row B7 — commit f4a8b14 - JVM 21.0.9 - min of 5 runs - one batch of 50000 records (WIDE)

| row                            | corpus        | today (host)  | flat arm      | gate       | verdict                               |
| ------------------------------ | ------------- | ------------- | ------------- | ---------- | ------------------------------------- |
| B7 scan (sum :score)           | records 50000 | 203.93 ns/row | 129.52 ns/row | ≤ 2 ns/row | MISS — flat arm 1.6x faster than host |
| B7 filter (count :dept = :eng) | records 50000 | 364.38 ns/row | 100.54 ns/row | ≤ 4 ns/row | MISS — flat arm 3.6x faster than host |
| B7 one cell (:ts, 200k random) | records 50000 | 123.8 ns      | 637.1 ns      | ≤ 2x host  | MISS — flat arm 5.1x host             |
| B7 row view (20k random)       | records 50000 | 0.301 µs      | 5.346 µs      | ≤ 1 µs     | MISS                                  |

All four B7 gates missed as written. The flat arm beats the host arm on
scan (1.6x) and filter (3.6x); it loses on single-cell point reads (5.1x
host) and row views (5.346 µs vs the 1 µs gate). Gates are reported
unreached, not relabeled. Source: c2-b7-wide2.log on J (the batch is
encoded WIDE — see C2-REPORT.md on the u16 offset defect that forced it).

## Row B8 — container (lane C3, branch claude/flat-c3-container)

Measured by doc/flat/C3-B8-MEASURE.clj: the first 16 probe-packed 64 KiB chunks
of the corpus + the container's directory bytes under one zlib stream (level 6),
against the same rows packed as one chunk under one zlib stream. Same session,
same rows, both arms. The research figure for this row was a proxy (per-chunk-size
compressions) — this is the arm itself.

| corpus              | rows in 16 chunks | container (zlib) | one chunk (zlib) | overhead | gate             |
| ------------------- | ----------------: | ---------------: | ---------------: | -------: | ---------------- |
| messages (10k chat) |             5,568 |          218,441 |          175,654 | +24.36 % | ≤ +5 % — NOT MET |
| records (50k rows)  |            27,680 |          392,383 |          313,306 | +25.24 % | ≤ +5 % — NOT MET |

Iterations (one change, re-run, log either way):

- one stream over the run: +24.36 % / +25.24 % (above). Per-chunk streams summed, for
  context: +27.97 % / +21.10 % — the single stream buys almost nothing.
- preset dictionary = chunk 0's bytes: 217,933 (+24.07 %) / 392,404 (+25.25 %) — no
  movement. Two no-movement iterations: this line of attack ends here.

Reading: the cost is not the compression unit. Per-chunk symbol/shape tables reset
every 64 KiB, codes are reassigned per chunk, and the body patterns deflate exploits
in the one-chunk arm (short-distance repetition of coded forms) do not survive the
reset — a preset dictionary does not restore them because the tables are not
byte-identical across chunks. Closing the gap needs what is above this lane: a
container-level shared table (a format change to §2's assumptions) or a
wider-window compressor (§6 already defers compression past v1). FORMAT-V1 §2's
"a 1 MiB run recovers all but 1–4 %" is refuted for zlib by this run.

## 2026-11-12 ~06:00 ET — B1 lane, board row B5 (venue) — commit 1e03b9b - JVM on J (clean box) - min of 9 runs after 3 warm, arms interleaved - per-arm domains

Bench `test/eve/flat/b1_bench.clj`: today = `swap!` of the plain value;
direct = `build-image` + place (Wave 0 default per-block `alloc-run!`) +
`swap!`. Read-backs verified ok on every corpus.

| row | corpus             | today      | flat arm                                              | gate                                                                                    |
| --- | ------------------ | ---------- | ----------------------------------------------------- | --------------------------------------------------------------------------------------- |
| B5  | map 10k int->int   | 484.46 ms  | 52.56 ms = **0.11×** (build 35.68 + place/swap 16.88) | under the probe's 0.37–0.56x — PASS, beats the band                                     |
| B5  | vector 10k records | 747.39 ms  | 449.92 ms = 0.60× (build 348.97 + place/swap 100.96)  | under the probe's 0.37–0.56x — near-miss, just over the band's 0.56 top                 |
| B5  | 5k chat messages   | 1195.92 ms | 811.59 ms = 0.68× (build 600.56 + place/swap 211.03)  | under the probe's 0.37–0.56x — MISS; build is the larger half                           |
| B5  | vector 100k ints   | 387.35 ms  | 623.21 ms = 1.61× (build 278.62 + place/swap 344.60)  | under the probe's 0.37–0.56x — MISS; place/swap is the larger half (Z2/Z3/Z4 territory) |

B5 gate as written (under the probe's figure on every corpus): NOT MET —
met on map-10k only; records a near-miss; messages and vec miss, splits
above. Post-merge verification suite on the same venue run (build +
requirements + contract): 9 tests, 917 assertions, 0 failures, 0 errors —
B1.1–B1.4 confirmed on J. Hatch-local readings of the same rows (min of
9, interleaved, contention-inflated absolutes; banked during the perf
loop): map 0.16×, records 0.52×, messages 0.95×, vec 1.60×.

## 2026-11-12 06:53 ET — Z1 board: B3 detach vs @a — commit 017ddad - JVM 21.0.9 - min of 5 runs - per-corpus legs

| row | corpus             | today    | flat arm            | gate                                           |
| --- | ------------------ | -------- | ------------------- | ---------------------------------------------- |
| B3  | map 10k int->int   | 16.06 ms | 1830.73 ms (0.01x)  | arm at least 2x faster than @a on every corpus |
| B3  | vector 100k ints   | 51.02 ms | 37375.29 ms (0.00x) | arm at least 2x faster than @a on every corpus |
| B3  | vector 10k records | 71.97 ms | 19770.32 ms (0.00x) | arm at least 2x faster than @a on every corpus |
| B3  | 5k chat messages   | 95.64 ms | 45070.88 ms (0.00x) | arm at least 2x faster than @a on every corpus |

Gate: MISSED on every corpus — the flat arm is 114–733x SLOWER than @a, not 2x
faster. Detach as built at this pin is a correctness mechanism: the walk pays
per-block slab reads (each reachable block fetched and scanned through the
source sio's per-block read path), and that cost, not the copy, is the row.

## 2026-11-12 06:38 ET — Z3 bench — commit 835e6fc - JVM on J - min of 5 runs after 2 warmups

Board row B1 (the zip, alone): `eve.flat.place-bench` — each corpus's
hand-built image placed once via `place!` into a fresh pre-sized heap
domain, net of domain creation. Claim mode: **run** (this tree's
`alloc/alloc-run!` is Z2's run claim, first-offset scalar).

| row | corpus             | flat arm (ns/block) | gate                                |
| --- | ------------------ | ------------------- | ----------------------------------- |
| B1  | map 10k int->int   | 38,029.9            | ≤ 50 ns/block (messages)            |
| B1  | vector 100k ints   | 15,998.1            | ≤ 50 ns/block (messages)            |
| B1  | vector 10k records | 29,860.1            | ≤ 50 ns/block (messages)            |
| B1  | 5k chat messages   | 15,275.1            | ≤ 50 ns/block — **MISSED by ~300x** |

The gate stays missed. The research probe's 33–42 ns/block measured
the raw zip loop in isolation; this row is the shipped `place` path —
full table validation, the checked-bytes gate, and per-block sio ops
for claims, byte copies and pointer rewrites — and its number is what
is reported. (Log: z3-bench3.log on J, HEAD 835e6fc.)

**messages-5k row LANDED** (t1-b4-messages2.log, reduced config —
0 warmup / 1 run, `--only messages-5k`; the canonical config's leash
death is dispositioned in T1-STATUS, finisher #4): detach 14,078.28 ms,
zip 12,063.03 ms, detach+zip 26,141.31 ms, transfer! 45,284.57 ms —
ratio 1.73 (≤2× PASS), cap 26 ms MISSED. B4 is now complete: all four
corpora ratio-pass, all four caps missed, at pre-fix component pins;
the caps' verdict belongs to collation.
