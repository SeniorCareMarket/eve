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

**ClojureCLR** — .NET 8.0.428 and Clojure.Main 1.12.6 are on this box
and the portable flat-io `:cljr` arm plus its conformance test pass
(11 / 264 / 0 / 0, 2026-11-24 12:04 AM ET). Official `bb test:cljr`
now loads and runs (16 of 22 started namespaces clean on 2026-11-24
12:38–12:46 AM ET; `eve.numeric-test` 6/84/0/0, `eve.type-io-test` 8/47/0/0,
and record / type-id / sorted-migration in a 50/392/0/0 batch with those two
on this tree; remaining rrb ISeq/slab-domain in
[clojureclr.md](hosts/clojureclr.md)). `cljr -m eve.runner.cljr-main
perf` has not been re-run at this pin.

**clojerl** — the portable flat-io `:clje` arm plus its conformance
test pass (11 / 260 / 0 / 0, 2026-11-24 12:07 AM ET). This batch
refers `long`/`alength` from `eve.host` on `:clje`; official
`bb test:clje` was not re-run and the Clojerl handle macros are still
unported, so the official suite is not expected to compile yet.

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

### ClojureScript (node) — J, 23:34–23:36 ET, exit 0, 43 s wall, load1 2.49→5.71

`thread-submit-64` is 20,140,625 ns/op (20.1 ms; it was 62,812,500 after the MessagePort fix).

| workload | ns/op |
|---|---:|
| atom-swap-counter | 44,036 |
| atom-deref-small | 4,318 |
| atom-swap-map-1k | 15,701 |
| atom-deref-map-1k | 5,313 |
| atom-$-get-map-1k | 6,408 |
| atom-reset-vec-1k | 11,421,875 |
| sorted-set-build-200 | 27,718,750 |
| disk-swap-counter | 197,265 |
| thread-submit-64 | 20,140,625 |

### browser — J, 23:34–23:36 ET, exit 0, 48 s wall, load1 2.49→5.71

`disk-swap-counter` is n/a (`require is not defined` — no disk domains in a page). `thread-submit-64` is 20,460,937 ns/op (20.5 ms; it was 140,000,000 after the MessagePort fix, and 3,251,953 on this box at `77b649aa5`, 2026-10-21, load1 1.52).

| workload | ns/op |
|---|---:|
| atom-swap-counter | 17,730 |
| atom-deref-small | 2,853 |
| atom-swap-map-1k | 7,095 |
| atom-deref-map-1k | 2,340 |
| atom-$-get-map-1k | 2,882 |
| atom-reset-vec-1k | 6,898,437 |
| sorted-set-build-200 | 13,945,312 |
| disk-swap-counter | n/a |
| thread-submit-64 | 20,460,937 |

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

### nbb — J, 23:34–23:36 ET, exit 0, 40 s wall, load1 2.49→5.71

`thread-submit-64` is 80,562,500 ns/op (80.6 ms; it was 247,625,000 after the MessagePort fix).

| workload | ns/op |
|---|---:|
| atom-swap-counter | 565,429 |
| atom-deref-small | 23,422 |
| atom-swap-map-1k | 141,357 |
| atom-deref-map-1k | 23,101 |
| atom-$-get-map-1k | 48,217 |
| atom-reset-vec-1k | 88,312,500 |
| sorted-set-build-200 | 197,000,000 |
| disk-swap-counter | 1,492,187 |
| thread-submit-64 | 80,562,500 |

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
  installed; flat-io conformance is 11 / 264 / 0 / 0. Official
  `bb test:cljr` loads and runs (16 of 22 started namespaces clean;
  `eve.numeric-test` 6/84/0/0, `eve.type-io-test` 8/47/0/0, and record /
  type-id / sorted-migration in a 50/392/0/0 batch with those two on this
  tree; remaining rrb ISeq/slab-domain in [clojureclr.md](hosts/clojureclr.md)).
- **clojerl host suite** — flat-io conformance is 11 / 260 / 0 / 0.
  The `:clje` `long`/`alength` refer is in this batch; official
  `bb test:clje` was not re-run and handle macros are still unported.
- **browser `thread-submit-64`** — 20.5 ms on this tree (was 140.0 ms
  after the MessagePort fix); 3.3 ms on this box at `77b649aa5`. The
  remaining gap vs October is `70e6f4c8` (eve.wire FORMAT-V1 on the
  SAB ring) after a native JS packer recovered most of the pack-rep
  cost.
- **browser `disk-swap-counter`** — n/a in `perf-all` (its page has no OPFS pool). The opt-in OPFS cell,
  `EVE_BROWSER_PERF=opfs bb test:browser`, measured 404 µs/op in headless Chrome on 2026-11-24 ET (one run, under
  gate load; not a perf-all cell, so not in §1). See [hosts/browser.md](hosts/browser.md).
- **Startup shootout, hosts other than JVM source-form** — not re-run
  at this pin.
- **Columnar bench per-cell table** — not re-run at this pin; the
  Nov 13 Mac win/loss summary remains in §2 as a prior-pin section.
- **Logseq-shaped scale (bytes per datom)** — the original driver is
  not in eve.db.ds; not re-run. The 2026-11-20 figures are not carried
  forward as current.
