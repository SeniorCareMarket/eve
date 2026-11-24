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
| `disk-swap-counter` | `swap!` on a persistent atom in a disk domain (mmap, the cross-process CAS) |
| `thread-submit-64` | 64 tasks (a `deftask`, so it crosses to an isolate by name) submitted to the default compute pool (a fixed pool of the host's parallelism), dereferenced |

```bash
bb scripts/perf-all.clj              # every host, one at a time (results: doc/hosts/perf-results.edn)
bb scripts/perf-all.clj jolt glj     # the hosts named
```

`sorted-set-build-200` builds a 200-element `eve.sorted` set inside one `swap!` through its constructor — a transient
that begins from no tree, freeing each version a write supersedes — and the swap retires the structure it replaces by
eve.retire's one walk (with the tally rule, 9.31). `int-map-build-200` was removed from the suite in 0.9.3.2 when
PersistentIntMap was removed; those cells are not carried forward.

<!-- perf:all (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| per operation | JVM | CLJS (Node) | browser | babashka | bb-minimal | nbb | ClojureCLR | basilisp | jolt | clojerl | glojure |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `atom-swap-counter` | 12.8 µs | 41.6 µs | 17.9 µs | 1.4 ms | 1.4 ms | 535.6 µs | — | 12.2 ms | 15.9 µs | — | 5.3 ms |
| `atom-deref-small` | 2.7 µs | 4.2 µs | 2.8 µs | 97.2 µs | 88.7 µs | 21.4 µs | — | 10.1 ms | 1.7 µs | — | 549.8 µs |
| `atom-swap-map-1k` | 4.2 µs | 13.9 µs | 7.0 µs | 2.8 ms | 693.8 µs | 135.4 µs | — | 3657.0 ms | 6.7 µs | — | 12.3 ms |
| `atom-deref-map-1k` | 2.3 µs | 4.4 µs | 2.5 µs | 86.5 µs | 92.2 µs | 24.6 µs | — | 1872.0 ms | 1.6 µs | — | 544.9 µs |
| `atom-$-get-map-1k` | 0.6 µs | 6.3 µs | 2.9 µs | 109.4 µs | 112.7 µs | 55.2 µs | — | 619.6 µs | 0.8 µs | — | 700.7 µs |
| `atom-reset-vec-1k` | 1.2 ms | 12.2 ms | 6.6 ms | 81.9 ms | 80.6 ms | 85.4 ms | — | 3845.0 ms | 3.7 ms | — | 504.5 ms |
| `sorted-set-build-200` | 5.0 ms | 29.2 ms | 14.6 ms | 1048.0 ms | 1034.0 ms | 193.5 ms | — | 10934.0 ms | 10.4 ms | — | 5857.0 ms |
| `disk-swap-counter` | 61.8 µs | 182.1 µs | n/a | 2.4 ms | 2.3 ms | 1.3 ms | — | 17.2 ms | 139.0 µs | — | 7.8 ms |
| `thread-submit-64` | 3.1 ms | n/a | 125.9 ms | 31.3 ms | 2.5 ms | n/a | — | 125.3 ms | 4.5 ms | — | 10.6 ms |
<!-- /perf:all -->

Lower is faster. Every host that could run was measured on this box (J, Linux x86_64, Temurin 21.0.13, jolt v0.8.28, bb 1.13.226, Node 24.22.1, Basilisp 0.5.1), one at a time, 2026-11-23 8:53–9:08 PM ET, Eve `507235b`. ClojureCLR has no .NET/`cljr` on this box. clojerl dies at compile (`eve/flat_io.cljc:304` cannot resolve `long`). cljs and nbb's `thread-submit-64` fail with `The "port" argument must be a MessagePort instance` (n/a). The browser's `disk-swap-counter` is n/a (no disk domains in a page). **The box varies**: read a difference under ~1.5× between two hosts as possible noise; the orders of magnitude between groups of hosts are what this table measures. Older tables, including the 2026-10-21 release-box run, remain in git history.

