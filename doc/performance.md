# Performance — Eve 0.9.3.1

What this release measures, where users look. Every cell below is a
measured number with its box and load label, or it is marked **GAPPED**
by name — none is estimated. The full record, with run logs and
provenance per cell, is the lane report this page is folded from:
`docs/fix-for-release-2/RELEASE-PERF-REPORT.md` in the development
repository.

Measurement discipline: "this box" is the fix box (Linux x86-64,
4 cores, 15 GB RAM); "calm" means load1 < 5 at every banked reading;
write cells are medians of 3 fresh processes unless a table says
otherwise; ratios are computed only between two measured cells.

## 1. Eve-DataScript vs stock DataScript

Both arms run the same driver (`contract.ds-bench`, the driver every
Eve-DS number in the lineage used), the same five shapes, generators,
batch sizes and counts. Eve-DS is the flagship shape: `swap!` on a
journaled persistent atom domain, the release default. Stock is
upstream DataScript 1.8.1 with the DB in a `clojure.core/atom` (stock
has no persistent domain) — its heap-atom twin of the same shapes.

**@500 people**

| shape | Eve-DS, JVM | stock 1.8.1 heap atom, JVM | **Eve ÷ stock JVM** | stock 1.8.1 heap atom, bb (native) |
|---|---:|---:|---:|---:|
| batched build, 100-person swaps (total ms) | **3,267** | 102.69 | **32×** | 17.98 |
| bulk-load, 2,000-datom swaps (total ms) | **2,721** | 30.91 | **88×** | 9.15 |
| one person per swap (ms per swap; first 500) | **9.13** | 0.129 | **71×** | 0.049 |
| one datom per swap (ms per swap; ×100) | **4.53** | 0.019 | **244×** | 0.0029 |
| retract one entity per swap (ms per swap; first 500) | **10.82** | 0.079 | **138×** | 0.014 |

**@1,000 people**

| shape | Eve-DS, JVM | stock 1.8.1 heap atom, JVM | **Eve ÷ stock JVM** | stock 1.8.1 heap atom, bb (native) |
|---|---:|---:|---:|---:|
| batched build, 100-person swaps (total ms) | **5,488** | 167.62 | **33×** | 39.56 |
| bulk-load, 2,000-datom swaps (total ms) | **4,858** | 43.68 | **111×** | 23.11 |
| one person per swap (ms per swap; first 500) | **9.58** | 0.102 | **94×** | 0.028 |
| one datom per swap (ms per swap; ×100) | **4.82** | 0.018 | **275×** | 0.0033 |
| retract one entity per swap (ms per swap; first 500) | **12.67** | 0.060 | **210×** | 0.016 |

Read the ratios for what they are: the Eve column pays a **durable
journaled commit per swap** (fsync-class costs); stock pays none. The
ratio is the price of persistence plus Eve's encoding, not a
like-for-like in-memory comparison. (@1,000 the single-swap rows use
the first 500 people/entities, per the driver, on both arms.
Eve-DS load1 0.81–1.79, calm; stock JVM 1.22–4.39, bb 1.05–1.77, calm.)

The same writes in upstream-harness form (`suite-n 500`, ms per op;
add-all / init / add-5 / add-1 / retract-5): stock JVM **21.07 / 1.31 /
22.25 / 12.71 / 29.22**; stock bb **12.97 / 2.74 / 13.52 / 7.24 /
21.21**. (Stock DataScript does not exist on a standard bb — its
optional feature pins 1.3.10; the bb arm is bb v1.13.226 rebuilt
natively from its tag with datascript 1.8.1.) The Eve port cannot run
these in-process at bench scale, which is why the comparison above
goes through the flagship shape.

Stock reads (`suite-n 1000`, ms per op, JVM): q1 0.024 · q2 0.080 ·
q3 0.110 · q4 0.158 · q5-shortcircuit 0.021 · qpred1 0.175 ·
qpred2 0.281 · find-datoms 0.129 · find-datom 0.137 · pull-one 0.514 ·
pull-many 0.978 · pull-wildcard 2.627 · pull-one-entities 0.847 ·
pull-many-entities 2.283. **Eve-DS read cells on the final tree are
GAPPED** — not re-measured this round.

### The single-swap write cost (E2) — improved, not closed

A/B on fresh domains, write-domain @500, interleaved final / notally /
pin, load1 0.81–1.79 (calm), ms per commit, medians of 3:

| shape | final | notally (measurement arm) | pin | final ÷ notally |
|---|---:|---:|---:|---:|
| add-1 | **4.53** | 3.00 | 7.73 | **1.51×** |
| add-5 | **9.13** | 7.39 | 15.13 | **1.24×** |
| retract-5 | **10.82** | 6.94 | 17.95 | **1.56×** |

65–78% of the pin's excess over the no-tally arm was removed this
round; the verdict is PARTIAL. The remainder is the journal forcing
the descriptor pages each commit's holds touched — holds must be on
disk before the record: the price of durable counts. "notally" is a
measurement arm only (it never frees — the DS11 leak), never a
candidate configuration.

### Tier warm reads (E3) — improved, bar not met

Warm get ≈ **12× a plain map get** on this box's method (pin
≈ 13.4×); the two landed causes took get 281.7 → 252.0 ns/op and
allocation 122.4 → 69.6 B/op. **The heap-speed bar is NOT met** —
PARTIAL; the remaining causes (a per-word region dispatch, an in-place
codec entry, one unexplained type check) are priced in the lane
record, not yet built.

## 2. Startup shootout — one-datum cold, per host

Contract `one`: cold process, fresh journaled domain, one datum,
`{:mark :ONE-DONE :datoms 2}` checked each run; ×5, shell wall clock,
median. Rows are not comparable across boxes, trees and routes (source
vs AOT) — the labels say which.

| host | box | cold, median | label / source |
|---|---|---:|---|
| jank (AOT, `-O2 --no-debug`, 16.3 MB binary) | finisher's box (4-core Ubuntu VM) | **0.231 s** | take 2 (was 2.940 s) |
| compiled ClojureScript (shadow :simple, node) | finisher's box | **0.235 s** | banked record; another tree carries 0.253 s |
| jank (take-2 AOT, built at the 0.9.3.1 pin, 16.4 MB) | this box | **0.317 s** | ONE-DONE 5/5; load1 1.39 |
| jolt 0.8.25 | this box, final tree | **0.665 s** | pin 0.704 s |
| bb 1.13.226 (eve.host-completion first) | this box, final tree | **2.221 s** | pin 2.234 s |
| nbb (node 22, mmap_cas addon) | this box, final tree | **2.284 s** | pin not run |
| JVM (java 21) | this box, final tree | **4.308 s** | pin 4.323 s |
| bb 1.12.218 | macOS (JDK 26 box) | 3,561 ms | pre take 2 |
| bb 1.13.226 | Grok box | 3,891 ms | **LOAD-POISONED (load1 100+)** |
| bb 1.13.226 | hatch | 18,086 ms | interim source route, load ~2–4 |
| basilisp 0.5.1 | hatch | 15,603 ms | source route |
| glojure, ClojureCLR, clojerl, basilisp | this box | **GAPPED** | not in this round's set |

## 3. Gaps, named

- **Eve-DS on bb (and every non-JVM host) for the write shapes** — not
  measured this round; the bb stock column has no Eve partner.
- **Eve-DS read cells on the final tree** (`suite-domain` @1,000) —
  not re-measured this round.
- **Startup on glojure, ClojureCLR, clojerl, basilisp, this box** —
  not in this round's set. nbb has no pin-tree row.
- **Stock DataScript on hosts other than the JVM and bb** (jolt, nbb,
  glojure, CLR, basilisp, clojerl, jank): GAPPED — not asked of this
  round.
- **E2** remains PARTIAL (final ÷ notally 1.24–1.56×) and **E3**
  remains PARTIAL (≈12× a plain map get; the heap-speed bar is not
  met) — measured, not gaps; their remaining causes are priced in the
  lane record.
