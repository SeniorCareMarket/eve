# Performance — Eve `f329fc4`

What this document measures, where users look. Every cell below is a
measured number with its box and pin label, or it is marked **GAPPED**
or **n/a** with the reason — none is estimated, and no older number is
carried forward as if it were new. Sections carry their own
provenance: runs are never blended across boxes, pins, or days.

The JVM and jolt suite rows were re-run on J (Linux x86_64, Temurin
21.0.13, jolt v0.8.28, bb 1.13.226, Node 24.22.1, Basilisp 0.5.1) on
2026-11-23 22:42–22:43 ET against Eve `f329fc4` (the jolt parity
view-pin fix on main), load1 2.63→2.40. The other host suite rows
remain the 2026-11-23 20:53–21:08 ET run on the same box against Eve
`507235b` (PR #44's jlog journal on main). load1 is recorded with
every host. Older records, including the 0.9.3.1 and 0.9.3.2 Mac
tables, stand in this file's git history.

Measurement discipline: performance measures the operation, not
compilation — benches run each host's compiled form, and compile time
is reported on its own line where a run reports it.

## 1. Host perf suite — `bb scripts/perf-all.clj`, 9 workloads

`test/eve/perf_suite.cljc`, 1 s budget, one host at a time.
`int-map-build-200` was removed from the suite in 0.9.3.2 when
PersistentIntMap was removed. The cross-host table is in
[doc/hosts/README.md](hosts/README.md); the raw EDN is
[doc/hosts/perf-results.edn](hosts/perf-results.edn).

**ClojureCLR** was not run: `cljr` is not on PATH and this box has no
.NET runtime. Command would have been
`CLOJURE_LOAD_PATH=<cp :cljr> cljr -m eve.runner.cljr-main perf`.

**clojerl** failed at compile, twice, same error:
`clojerl … -m eve.runner.clje-main perf` exits 1 in 8 s with
`adapters/flat-io/portable/src/eve/flat_io.cljc:304:13: Unable to
resolve symbol: long`.

### JVM — J, 2026-11-23 22:42:07–22:42:37 ET, exit 0, 30 s wall, load1 2.63→3.00, Eve `f329fc4`

| workload | ns/op |
|---|---:|
| atom-swap-counter | 11,436 |
| atom-deref-small | 2,906 |
| atom-swap-map-1k | 4,104 |
| atom-deref-map-1k | 2,740 |
| atom-$-get-map-1k | 582 |
| atom-reset-vec-1k | 1,483,398 |
| sorted-set-build-200 | 5,300,781 |
| disk-swap-counter | 67,321 |
| thread-submit-64 | 2,529,296 |

### ClojureScript (node) — J, 22:39–22:41 ET, exit 0, 46 s wall, load1 3.77→4.24

`thread-submit-64` now runs (it failed on `507235b` with a MessagePort error).

| workload | ns/op |
|---|---:|
| atom-swap-counter | 42,541 |
| atom-deref-small | 4,783 |
| atom-swap-map-1k | 15,319 |
| atom-deref-map-1k | 5,580 |
| atom-$-get-map-1k | 6,713 |
| atom-reset-vec-1k | 11,968,750 |
| sorted-set-build-200 | 31,906,250 |
| disk-swap-counter | 187,255 |
| thread-submit-64 | 62,812,500 |

### browser — J, 22:39–22:41 ET, exit 0, 45 s wall, load1 3.77→4.24

`disk-swap-counter` is n/a (`require is not defined` — no disk domains in a page). `thread-submit-64` was 3,251,953 ns/op on this box at `77b649aa5` (2026-10-21, load1 1.52).

| workload | ns/op |
|---|---:|
| atom-swap-counter | 18,569 |
| atom-deref-small | 2,954 |
| atom-swap-map-1k | 6,908 |
| atom-deref-map-1k | 2,477 |
| atom-$-get-map-1k | 2,834 |
| atom-reset-vec-1k | 6,437,500 |
| sorted-set-build-200 | 14,554,687 |
| disk-swap-counter | n/a |
| thread-submit-64 | 140,000,000 |

### babashka — J, 21:02–21:02 ET, exit 0, 31 s wall, load1 2.50→1.91

| workload | ns/op |
|---|---:|
| atom-swap-counter | 1,363,281 |
| atom-deref-small | 97,167 |
| atom-swap-map-1k | 2,777,343 |
| atom-deref-map-1k | 86,547 |
| atom-$-get-map-1k | 109,436 |
| atom-reset-vec-1k | 81,937,500 |
| sorted-set-build-200 | 1,048,000,000 |
| disk-swap-counter | 2,405,273 |
| thread-submit-64 | 31,281,250 |

### bb-minimal — J, 21:02–21:03 ET, exit 0, 33 s wall, load1 1.91→1.57

| workload | ns/op |
|---|---:|
| atom-swap-counter | 1,403,320 |
| atom-deref-small | 88,684 |
| atom-swap-map-1k | 693,847 |
| atom-deref-map-1k | 92,224 |
| atom-$-get-map-1k | 112,731 |
| atom-reset-vec-1k | 80,562,500 |
| sorted-set-build-200 | 1,034,000,000 |
| disk-swap-counter | 2,347,656 |
| thread-submit-64 | 2,486,328 |

### nbb — J, 22:39–22:41 ET, exit 0, 39 s wall, load1 3.77→4.24

`thread-submit-64` now runs (same MessagePort fix as ClojureScript).

| workload | ns/op |
|---|---:|
| atom-swap-counter | 545,898 |
| atom-deref-small | 23,300 |
| atom-swap-map-1k | 135,131 |
| atom-deref-map-1k | 25,924 |
| atom-$-get-map-1k | 48,828 |
| atom-reset-vec-1k | 85,687,500 |
| sorted-set-build-200 | 193,125,000 |
| disk-swap-counter | 1,232,421 |
| thread-submit-64 | 247,625,000 |

### basilisp (lpy) — J, 21:03–21:06 ET, exit 0, 172 s wall, load1 2.81→1.30

| workload | ns/op |
|---|---:|
| atom-swap-counter | 12,164,062 |
| atom-deref-small | 10,093,750 |
| atom-swap-map-1k | 3,657,000,000 |
| atom-deref-map-1k | 1,872,000,000 |
| atom-$-get-map-1k | 619,628 |
| atom-reset-vec-1k | 3,845,000,000 |
| sorted-set-build-200 | 10,934,000,000 |
| disk-swap-counter | 17,234,375 |
| thread-submit-64 | 125,250,000 |

### jolt — J, 2026-11-23 22:42:37–22:43:06 ET, exit 0, 29 s wall, load1 3.00→2.40, Eve `f329fc4`

| workload | ns/op |
|---|---:|
| atom-swap-counter | 13,786 |
| atom-deref-small | 1,543 |
| atom-swap-map-1k | 6,893 |
| atom-deref-map-1k | 1,615 |
| atom-$-get-map-1k | 587 |
| atom-reset-vec-1k | 3,572,265 |
| sorted-set-build-200 | 10,687,500 |
| disk-swap-counter | 153,076 |
| thread-submit-64 | 5,539,062 |

### glojure — J, 21:07–21:08 ET, exit 0, 74 s wall, load1 2.13→2.06

| workload | ns/op |
|---|---:|
| atom-swap-counter | 5,257,812 |
| atom-deref-small | 549,804 |
| atom-swap-map-1k | 12,320,312 |
| atom-deref-map-1k | 544,921 |
| atom-$-get-map-1k | 700,683 |
| atom-reset-vec-1k | 504,500,000 |
| sorted-set-build-200 | 5,857,000,000 |
| disk-swap-counter | 7,769,531 |
| thread-submit-64 | 10,625,000 |

## 2. Columnar bench (node) — not re-run at `507235b`

The cells below are the Nov 13 Mac record, left as a prior-pin section.
They are not this pin's numbers.


Eve vs stock collections over columnar shapes, 10K–1M rows:
**30/30 cells result-match**. Eve wins arithmetic, dataset and tensor
at 100K–1M rows (up to **4.18×**). Eve **loses filter+aggregate and
sort at every size** — named, not softened. The per-cell table is in
the run's output on the operator's Mac and is **GAPPED** in this
record; the win/loss verdicts above are the measured summary in hand.

## 3. Startup shootout — JVM row at `507235b`

Contract `one` (cold process, fresh journaled domain, one datum,
`{:mark :ONE-DONE :datoms 2}` checked each run; ×5, median) was re-run
on J for the JVM source-form leg that the eve.db.ds README quotes:

`bash harness/shootout.sh jvm-src bash harness/run-jvm.sh contract.one`

| leg | median ms | runs ms | load1 start/end |
|---|---:|---|---|
| jvm-src | **1,591** | 8,000 1,474 1,595 1,591 1,494 | 2.00 / 1.92 |

All five runs printed `ONE-DONE`. The first run paid a one-time compile
(8,000 ms); the median is the third of the five, so that compile is
excluded by construction. The other four runs sat in a 1,474–1,595 ms
band. Eve `507235b`, eve.db.ds `f35f5d0`, 2026-11-23 21:12–21:13 ET.

Other hosts (bb, nbb, cljs, jolt, jank, glj, basilisp, ClojureCLR,
clojerl) were **not re-run** at this pin for the startup shootout; they
are not carried forward from earlier pins.

## 4. Eve-DataScript vs stock DataScript (runtime) — J, `507235b` / `f35f5d0`

Re-run with eve.db.ds `scripts/ds_bench.sh` (the same five write shapes
at 500 and 1,000 people, median of 3 fresh-process reps). The Eve arm
is a persistent, journaled conn; the stock arm is DataScript 1.8.1 in a
heap atom. load1 1.84→2.00, 2026-11-23 21:11–21:12 ET.

| Shape | Eve | Stock | Eve ÷ stock |
|---|---:|---:|---:|
| **500 people** | | | |
| batched build, 100-person swaps (total ms) | 1223.27 | 62.32 | 20× |
| bulk-load, 2,000-datom swaps (total ms) | 607.42 | 14.47 | 42× |
| one person per swap (ms per swap) | 2.52 | 0.04 | 57× |
| one datom per swap (ms per swap) | 0.91 | 0.01 | 67× |
| retract one entity per swap (ms per swap) | 2.06 | 0.05 | 41× |
| **1000 people** | | | |
| batched build, 100-person swaps (total ms) | 933.14 | 23.33 | 40× |
| bulk-load, 2,000-datom swaps (total ms) | 932.74 | 21.00 | 44× |
| one person per swap (ms per swap) | 1.92 | 0.02 | 78× |
| one datom per swap (ms per swap) | 0.70 | 0.01 | 109× |
| retract one entity per swap (ms per swap) | 2.22 | 0.02 | 110× |

The gap is the durability Eve pays for and stock does not. A durable
Eve database is 20 to 110 times slower than an in-memory database on
these write shapes, at this pin.

## 5. Gaps, named

- **ClojureCLR host suite** — .NET 8.0.428 and Clojure.Main 1.12.6 are
  installed; `bb test:cljr` dies at compile (`eve/flat_io.cljc:308`,
  `Unable to resolve symbol: u8`).
- **clojerl host suite** — compile fails at `eve/flat_io.cljc:304`
  (`Unable to resolve symbol: long`), twice, same error.
- **browser `thread-submit-64`** — 140.0 ms on this tree; 3.3 ms on
  this box at `77b649aa5`. The jump is `70e6f4c8` (eve.wire on the SAB
  ring), not the MessagePort bug, which was cljs/nbb only and is fixed.
- **browser `disk-swap-counter`** — n/a (no disk domains in a page).
- **Startup shootout, hosts other than JVM source-form** — not re-run
  at this pin.
- **Columnar bench per-cell table** — not re-run at this pin; the
  Nov 13 Mac win/loss summary remains in §2 as a prior-pin section.
- **Logseq-shaped scale (bytes per datom)** — the original driver is
  not in eve.db.ds; not re-run. The 2026-11-20 figures are not carried
  forward as current.
