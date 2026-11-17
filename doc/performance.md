# Performance — Eve 0.9.3.2

What this release measures, where users look. Every cell below is a
measured number with its box and pin label, or it is marked **GAPPED**
by name — none is estimated. Sections carry their own provenance:
runs are never blended across boxes, pins, or days.

Measurement discipline: performance measures the operation, not
compilation — benches run each host's compiled form, and compile time
is reported on its own line where a run reports it. The current-
release record was measured on the operator's Mac (macOS, arm64,
JDK 26) unless a section says otherwise. Older Linux cells from
earlier rounds are not carried into this record; the 0.9.3.1 record
stands in this file's git history.

Pins named below: the Nov 14 ClojureScript run is the perf-doc-inputs
harness run (tree at harness commit 95c855c1, Nov 14 01:57 ET); the
Nov 14 ClojureCLR and basilisp runs are at the round-40 tree
(e8c794be); the Nov 12–13 runs predate the round 38–40 fixes and are
labeled as such wherever they appear.

## 1. Host perf suite — `bb scripts/perf-all.clj`, 10 workloads

Compile verdict for the release tree: `test:compile` **OK** (Mac,
Nov 14, perf-doc-inputs run).

### ClojureScript (node) — Mac, Nov 14, exit 0, 37 s wall

| workload | ns/op |
|---|---:|
| atom-swap-counter | 13,214 |
| atom-deref-small | 2,792 |
| atom-swap-map-1k | 4,127 |
| atom-deref-map-1k | 589,843 |
| atom-$-get-map-1k | 3,168 |
| atom-reset-vec-1k | 6,085,937 |
| sorted-set-build-200 | 14,320,312 |
| int-map-build-200 | 3,599,609 |
| disk-swap-counter | 6,117,187 |
| thread-submit-64 | 2,158,203 |

### ClojureCLR — Mac, Nov 14, round-40 pin, exit 0, 44 s wall

All 10 workloads pass. Verified rows in hand: atom-swap-counter
**27,114 ns/op**, atom-deref-small **5,142 ns/op**. The remaining
eight rows are in that run's `doc/hosts/perf-results.edn` on the
operator's Mac and are **GAPPED** in this record — not estimated.

### Basilisp (lpy) — Mac, Nov 14, round-40 pin, exit 0, 121 s wall

All 10 workloads pass. Its honest weak rows, named: sorted-set-build-200
at **22,695 ms for 1 op** (≈22.7 s/op) and atom-reset-vec-1k at
**1,399 ms for 1 op** (≈1.4 s/op). The remaining rows are in the same
Mac `perf-results.edn` and are **GAPPED** here on the same terms.

### JVM / nbb / jolt — Mac, Nov 12 (earlier pin, pre-rounds 38–40)

All three hosts green, 10/10 workloads (walls: JVM 35 s, nbb 34 s,
jolt 34 s). Headline rows: JVM atom-swap-counter **4,215 ns/op**;
JVM disk-swap-counter **4,542,968 ns/op**; jolt disk-swap-counter
**262,695 ns/op** — the durable commit ≈17× cheaper on jolt than on
the JVM in this run; nbb roughly even with jolt on the swap rows.
These rows were **not re-run at the round-40 pin**; the full tables
are in that run's Mac `perf-results.edn` and are **GAPPED** here.

### Nov 13 rerun (Mac, after the round-4 pull) — spot rows

The rerun completed with cljs, bb, bb-minimal and glojure legs in the
table. Glojure is interpreted and shows it: atom-swap-map-1k
**7,175,781 ns/op** against the JVM's **1,283 ns/op** in the same run.
ClojureCLR and basilisp columns were still dashes in this run (their
pass at the round-40 pin is recorded above). Full table **GAPPED**
here on the same terms as the Nov 12 run.

Jolt and clojerl full perf-all tables from the Nov 13 runs are not in
this record's sources and are **GAPPED** by name rather than rebuilt
from summaries.

## 2. Columnar bench (node) — Mac, Nov 13

Eve vs stock collections over columnar shapes, 10K–1M rows:
**30/30 cells result-match**. Eve wins arithmetic, dataset and tensor
at 100K–1M rows (up to **4.18×**). Eve **loses filter+aggregate and
sort at every size** — named, not softened. The per-cell table is in
the run's output on the operator's Mac and is **GAPPED** in this
record; the win/loss verdicts above are the measured summary in hand.

## 3. Startup shootout — NO ROWS at the 0.9.3.2 pin (convicted)

Contract `one` (cold process, fresh journaled domain, one datum,
`{:mark :ONE-DONE :datoms 2}` checked each run; ×5, median) produced
**no row** at the round-40 pin: the full ×5 run went **0/5 ONE-DONE**,
so there is no median to quote. Two causes, measured and separated:

- **(A) Product wall.** Every run that reached the transact (2/2)
  died with a ClassCastException at `datascript.db` compare
  (db.cljc:1053): `Datom__Flat` cannot be cast to `Datom`. The
  construction-law relaxation makes a no-domain Datom construction
  yield its flat sister, and DataScript's `^Datom`-hinted index
  comparators hard-cast. The fix belongs to the release/flat line
  (bind the domain through db-with's datom construction, or thaw at
  the comparator boundary). Post-fix re-runs belong on the Mac or J.
- **(B) Venue wall.** Runs 2–5 also died at
  `UnixFileDispatcherImpl.map0` EPERM — the hatch sandbox's
  intermittent mmap ban (run 1 mapped fine). Hatch-only; it does not
  explain (A), which reproduces from the transact path itself.

For contrast, the previous release's record (0.9.3.1 pin, pre-
relaxation — **not** 0.9.3.2 rows): JVM passed at 747/695 ms; cljs
216 ms (J) / 83 ms (Mac); ClojureCLR 6,303 ms; nbb 6,501 ms; basilisp
7,767 ms; bb 3,099 ms calm (15,014 ms under load); jolt 969 ms with
the journal **off** — not a product row, a journal-engaged row is
still owed. Clojerl was walled at that pin; glojure, jank,
ClojureDart, cherry and VS had no rows.

## 4. Eve-DataScript vs stock DataScript (runtime) — GAPPED at the 0.9.3.2 pin

No runtime rows are quoted for 0.9.3.2: the comparison was not re-run
at this pin, and the 0.9.3.1 tables are not carried forward — the port
and the tree have both moved since (construction law, the collation).

Exact state of the missing measurement:

- The canonical bench driver is located and byte-verified:
  branch `lane-m/ds-perf-bench` @ d309f1fc, path
  `docs/datascript-perf/bench/contract/ds_bench.cljc`,
  sha256 `7ef8a26485767732d3e20cad0555920f042df6e133bacaafb522154ebfaa9b45`
  (re-verified by this lane, Nov 15). **Warning:** eve.db.ds's own
  `test/drivers/contract/ds_bench.cljc` hashes 759a6f68… — a
  different driver; its numbers do not compare and it is never a
  substitute.
- Rows were filed for production on Nov 15: heron DISPATCH #46,
  kind-hypatia DISPATCH #37, and job-agent task
  `perf-doc-s04-ds-runtime-jvm` (JVM arm first, both arms, driver
  hash checked by the runner before executing). Until a venue
  returns, this section stays GAPPED.
- Port coverage is not runtime rows, and is labeled as such: at the
  DataScript lane's Nov 14 census the port was proven on 7 hosts
  (JVM, bb, jolt, nbb, glojure, ClojureCLR, jank), partial on cljs
  and basilisp, not proven on clojerl, ClojureDart or cherry.
- One priced read cell from the same census, named as an open cost:
  find-datom at **170.53 ms/op** (≈389× stock); a write regression in
  the same record was convicted to the DS11 tally fix.

## 5. Gaps, named

- **DataScript runtime tables at the 0.9.3.2 pin** — driver located
  and hash-verified, rows filed to three venues Nov 15, none returned
  at this writing.
- **Startup shootout, all hosts, at the 0.9.3.2 pin** — blocked on
  the Datom__Flat comparator fix (§3A); the venue wall (§3B) routes
  the re-run to the Mac or J.
- **ClojureCLR / basilisp full perf-all tables** — headline and weak
  rows in §1; the full tables sit in the operator's Mac
  `perf-results.edn`.
- **JVM / nbb / jolt perf-all at the round-40 pin** — Nov 12 rows
  (§1) predate rounds 38–40 and were not re-run.
- **Jolt / clojerl full perf-all tables (Nov 13 runs)** — not in this
  record's sources.
- **Columnar bench per-cell table** — win/loss verdicts in §2; the
  cells themselves are in the Mac run output.
- **Single-swap write cost and tier warm reads** (the 0.9.3.1
  record's E2/E3 items) — not re-measured for 0.9.3.2; their
  0.9.3.1 verdicts are not carried forward as current.
