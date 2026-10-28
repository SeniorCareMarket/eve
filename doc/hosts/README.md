# eve's hosts

eve is written once, against eve's host interface ([HOST-CONTRACT](../unification/HOST-CONTRACT.md)); a host is that
shared code plus a list of adapter roots, its row in [`hosts.edn`](../../hosts.edn). One page per host covers what was
measured there — its battery and its performance — how eve is built on it, what to watch for, and where it goes next.

| Host | Page | Battery on this tree (the final pass, 2026-10-21/22 ET); before it, the final sweep (2026-10-20 ET) | Notes |
|---|---|---|---|
| JVM | [jvm.md](jvm.md) | 546 tests, 9,615 assertions, green (136 s); the final sweep 522 / 8,078 (163 s) | the reference |
| ClojureScript (Node) | [clojurescript-node.md](clojurescript-node.md) | `all`: 482 tests, 34,980 assertions, green (72 s); `slab-grow` 105 / 28,902, `thread-domain` 12 / 146, `thread-fold-group` 3 / 49, `net` 62 / 1,429, `deftype` 63 / 710, `rrb-vector` 22 / 68; the final sweep `all` 468 / 33,388, `slab-grow` 91 / 27,310, `thread-fold-group` 3 / 49 | |
| ClojureScript (browser) | [browser.md](browser.md) | 48 tests, 633 assertions, green (headless Chromium, 36 s) | |
| babashka | [babashka.md](babashka.md) | 247 tests, 4,748 assertions, green; babashka-minimal 242 / 4,623; the final sweep 233 / 3,156 and 228 / 3,031 | |
| nbb | [nbb.md](nbb.md) | 242 tests, 4,617 assertions, green (489 s, peak 4.7 GB); the final sweep 228 / 3,025 (peak 4.6 GB) | |
| ClojureCLR | [clojureclr.md](clojureclr.md) | 202 tests, 4,499 assertions, green (188 s); the final sweep 188 / 2,907 | |
| basilisp | [basilisp.md](basilisp.md) | 180 tests, 4,350 assertions, 0 failures, **7 errors** (56 min, 9.34's tree): eve.thread.domain's 120 s waits run out late in the full run (9.26), fold on a group's three with a leak of its own (9.35); `eve.thread-net-test` alone 5 / 47 and `eve.thread-fold-group-test` alone 3 / 49, green. Before 9.34: 180 / 4,360, 5 errors. Before it, on `08b94cbf4` (before 9.31): 174 / 4,396, 1 failure (a timing bar under load), 1 error | |
| jolt | [jolt.md](jolt.md) | 199 tests, 4,479 assertions, green (203 s, peak 1.7 GB); the final sweep 185 / 2,887 (peak 1.9 GB) | |
| clojerl | [clojerl.md](clojerl.md) | 198 tests, 4,400 assertions, green (16 min; peak 11.1 GB, not investigated), and green again over the NIF's macOS change; before it, on `08b94cbf4` (before 9.31), 192 / 4,383 | |
| glojure | [glojure.md](glojure.md) | 182 tests, 4,458 assertions, green; the final sweep 168 / 2,866 (peak 2.4 GB) | |
| ValueScript | [valuescript.md](valuescript.md) | 260 passed, 0 failed (the three namespaces it compiles; 2026-10-22 ET, main `f8cfdb0dd`'s builder) | not part of 0.9 |

## The performance suite

`test/eve/perf_suite.cljc` is one source, written over eve's public API, run by every host's runner (`perf` command):
the same workloads at the same sizes everywhere. Each workload runs in doubling rounds — 1, 2, 4 … operations — until
one round has taken the time budget (1 s), and that round is reported, so a fast host and a slow one both finish in
about two budgets a workload and both report the same work's cost:

| Workload | What one operation is |
|---|---|
| `atom-swap-counter` | `swap!` of `update :n inc` on an in-process atom holding `{:n i}` |
| `atom-deref-small` | `deref` of a two-key map |
| `atom-swap-map-1k` | `swap!` of an `assoc` into a 1,000-key map (one key changed: the path copy) |
| `atom-deref-map-1k` | `deref` of the 1,000-key map (materializing it as host collections) |
| `atom-$-get-map-1k` | a zero-copy `get` in the 1,000-key map under `eve.atom/read-pinned` (one pinned read: the bound `$` regions are made of) |
| `atom-reset-vec-1k` | `reset!` to `{:v (vec (range 1000))}` (encoding a 1,000-element vector) |
| `sorted-set-build-200` | building a 200-element `eve.sorted` set inside a `swap!` |
| `int-map-build-200` | building a 200-key int-map inside a `swap!` |
| `disk-swap-counter` | `swap!` on a persistent atom in a disk domain (mmap, the cross-process CAS) |
| `thread-submit-64` | 64 tasks (a `deftask`, so it crosses to an isolate by name) submitted to the default compute pool (a fixed pool of the host's parallelism), dereferenced |

```bash
bb scripts/perf-all.clj              # every host, one at a time (results: doc/hosts/perf-results.edn)
bb scripts/perf-all.clj jolt glj     # the hosts named
```

The two build workloads build a 200-element int-map or sorted set inside one `swap!` through its constructor — a
transient that begins from no tree, freeing each version a write supersedes — and the swap retires the structure it
replaces by eve.retire's one walk (with the tally rule, 9.31); they run on their time budget like the others
(2026-10-21).
Until then they stopped at 16 builds (`:capped 16`) and built by 200 persistent ops, whose intermediate versions
stayed allocated: the numbers in the tables below from runs before 2026-10-21 measure that workload.

The suite found two defects on its first run, both fixed before these numbers (`7faf6597f`): an int32 field could not
hold a slab offset past 2^31 (an int-map node threw "integer overflow" once its heap domain's small classes filled),
and a fresh map or set written into an atom left 85% of what it allocated unreachable (the writers' own path copies).

<!-- perf:all (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| per operation | JVM | CLJS (Node) | browser | babashka | bb-minimal | nbb | ClojureCLR | basilisp | jolt | clojerl | glojure |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `atom-swap-counter` | 11.8 µs | 33.2 µs | 15.3 µs | 324.7 µs | 340.3 µs | 301.0 µs | 92.1 µs | 8.9 ms | 160.8 µs | 366.9 µs | 2.1 ms |
| `atom-deref-small` | 17.1 µs | 8.9 µs | 4.5 µs | 68.9 µs | 74.2 µs | 27.9 µs | 89.7 µs | 1.3 ms | 17.2 µs | 74.8 µs | 318.4 µs |
| `atom-swap-map-1k` | 4.7 µs | 10.1 µs | 4.7 µs | 115.4 µs | 140.0 µs | 81.9 µs | 33.6 µs | 27.0 ms | 47.3 µs | 122.1 µs | 4.6 ms |
| `atom-deref-map-1k` | 377.7 µs | 1.7 ms | 1.0 ms | 12.2 ms | 12.2 ms | 5.8 ms | 4.2 ms | 636.0 ms | 4.7 ms | 7.2 ms | 72.6 ms |
| `atom-$-get-map-1k` | 2.1 µs | 6.4 µs | 3.4 µs | 88.3 µs | 97.5 µs | 33.7 µs | 25.2 µs | 2.1 ms | 25.0 µs | 75.5 µs | 383.8 µs |
| `atom-reset-vec-1k` | 1.5 ms | 11.5 ms | 6.0 ms | 39.9 ms | 42.3 ms | 78.3 ms | 9.1 ms | 1630.0 ms | 16.5 ms | 456.5 ms | 218.8 ms |
| `sorted-set-build-200` | 11.1 ms | 36.4 ms | 19.7 ms | 484.3 ms | 410.3 ms | 260.8 ms | 77.8 ms | 9197.0 ms | 182.8 ms | 570.0 ms | 2295.0 ms |
| `int-map-build-200` | 4.5 ms | 8.1 ms | 6.4 ms | 133.4 ms | 132.1 ms | 171.3 ms | 22.2 ms | 2539.0 ms | 81.4 ms | 341.5 ms | 1039.0 ms |
| `disk-swap-counter` | 17.6 µs | 43.7 µs | n/a | 415.5 µs | 383.1 µs | 308.6 µs | 98.1 µs | 9.6 ms | 177.9 µs | 524.9 µs | 2.2 ms |
| `thread-submit-64` | 985.4 µs | 7.6 ms | 5.8 ms | 1.5 ms | 1.9 ms | 11.0 ms | 2.4 ms | 280.5 ms | 2.2 ms | 15.8 ms | 7.4 ms |
<!-- /perf:all -->

Lower is faster. Every host was run on the release box, one at a time with nothing else running, in one window
(2026-10-21, 10:56–11:06 PM ET, the final tree `77b649aa5`; basilisp's column alone, 1:44–1:46 AM ET on
2026-10-22). **The box varies by more than a few percent**: two runs
of unchanged code earlier on 2026-10-21 differed by a median of 1.09–1.64× per host and up to 2.33× on one workload.
Against the run at 2:37–2:47 PM ET (`08b94cbf4`, before 9.31), on the eight workloads that are not builds, this one's
median per host is 0.90–1.14× and its rows 0.65–1.75×, 36 of 87 rows faster; the largest moves, `atom-reset-vec-1k`
1.16–1.75× slower on seven hosts (1.00× on the JVM), are not investigated. Read a difference under ~1.5× between two
hosts as noise; the orders of magnitude between groups of hosts are what this table measures. The two build rows
measure the constructor and the swap's walk of the tree it replaces (9.25); what reclaiming costs a build was measured
apart, both trees on the box at once (2026-10-21).
