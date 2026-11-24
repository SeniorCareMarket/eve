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
| ClojureCLR | [clojureclr.md](clojureclr.md) | 2026-10-21: 202 tests, 4,499 assertions, green (188 s); the final sweep 188 / 2,907. 2026-11-24: official suite loads; `numeric-test` 6/84/0/0, `type-io-test` 8/47/0/0, record/type-id/sorted-migration in a 50/392/0/0 batch with those two; remaining rrb ISeq/slab-domain in clojureclr.md | |
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
| `atom-swap-counter` | 11.4 µs | 44.0 µs | 17.7 µs | 1.4 ms | 1.4 ms | 565.4 µs | — | 12.2 ms | 13.8 µs | — | 5.3 ms |
| `atom-deref-small` | 2.9 µs | 4.3 µs | 2.9 µs | 97.2 µs | 88.7 µs | 23.4 µs | — | 1.9 ms | 1.5 µs | — | 549.8 µs |
| `atom-swap-map-1k` | 4.1 µs | 15.7 µs | 7.1 µs | 2.8 ms | 693.8 µs | 141.4 µs | — | 116.2 ms | 6.9 µs | — | 7.6 ms |
| `atom-deref-map-1k` | 2.7 µs | 5.3 µs | 2.3 µs | 86.5 µs | 92.2 µs | 23.1 µs | — | 94.1 ms | 1.6 µs | — | 544.9 µs |
| `atom-$-get-map-1k` | 0.6 µs | 6.4 µs | 2.9 µs | 109.4 µs | 112.7 µs | 48.2 µs | — | 619.6 µs | 0.6 µs | — | 700.7 µs |
| `atom-reset-vec-1k` | 1.5 ms | 11.4 ms | 6.9 ms | 81.9 ms | 80.6 ms | 88.3 ms | — | 3845.0 ms | 3.6 ms | — | 504.5 ms |
| `sorted-set-build-200` | 5.3 ms | 27.7 ms | 13.9 ms | 1048.0 ms | 1034.0 ms | 197.0 ms | — | 10934.0 ms | 10.7 ms | — | 5857.0 ms |
| `disk-swap-counter` | 67.3 µs | 197.3 µs | n/a | 2.4 ms | 2.3 ms | 1.5 ms | — | 17.2 ms | 153.1 µs | — | 7.8 ms |
| `thread-submit-64` | 2.5 ms | 20.1 ms | 20.5 ms | 31.3 ms | 2.5 ms | 80.6 ms | — | 125.3 ms | 5.5 ms | — | 10.6 ms |
<!-- /perf:all -->

Lower is faster. Measured on this box (J, Linux x86_64, Temurin 21.0.13, jolt v0.8.28, bb 1.13.226, Node 24.22.1, Basilisp 0.5.1), one host at a time. The JVM and jolt columns were re-run 2026-11-23 10:42–10:43 PM ET at Eve `f329fc4` (load1 2.63→2.40). ClojureScript (Node), nbb, and the browser were re-measured 11:34–11:36 PM ET the same day (load1 2.49) on Eve `441ae29f` plus the pack-plain wire-encode fix, by `bb scripts/perf-all.clj`. The basilisp `atom-deref-small`, `atom-swap-map-1k`, and `atom-deref-map-1k` cells were re-measured 2026-11-24 8:47–9:45 AM ET on this box (J) at Eve `80f97a68` (median of five official cells, load1 1.74–6.14). The glojure `atom-swap-map-1k` cell was re-measured 2026-11-24 11:04–11:11 AM ET on this box (J) at Eve `2b9ee135` (median of five official cells, load1 2.63–4.76). The other basilisp rows, and the remaining host columns, remain the 2026-11-23 8:53–9:08 PM ET run at Eve `507235b`. `thread-submit-64` is 20.1 ms (cljs), 20.5 ms (browser) and 80.6 ms (nbb); those were 62.8 ms, 140.0 ms and 247.6 ms after the MessagePort fix, and 7.6 ms / 3.3 ms (this box at `77b649aa5`, 2026-10-21, load1 1.52) / 11.0 ms in the October published table. The remaining gap vs October is `70e6f4c8` (eve.wire FORMAT-V1 on the thread path) after a native JS packer recovered most of the pack-rep cost. The browser's `disk-swap-counter` is n/a (no disk domains in a page). ClojureCLR: .NET 8.0.428 and Clojure.Main 1.12.6 are on this box; the portable flat-io `:cljr` arm and its conformance test pass (11 tests / 264 assertions, 2026-11-24 12:04 AM ET). Official `bb test:cljr` now loads and runs (16 of 22 started namespaces clean on 2026-11-24 12:38–12:46 AM ET; `eve.numeric-test` 6/84/0/0, `eve.type-io-test` 8/47/0/0, and record / type-id / sorted-migration in a 50/392/0/0 batch with those two on this tree; remaining rrb ISeq/slab-domain in [clojureclr.md](clojureclr.md)). `cljr -m eve.runner.cljr-main perf` has not been re-run. clojerl: the portable flat-io `:clje` arm and its conformance test pass (11 tests / 260 assertions at the same time; Inf/NaN rows skipped — the BEAM has neither). This batch refers `long`/`alength` from `eve.host` on `:clje`; official `bb test:clje` was not re-run and the Clojerl handle macros are still unported, so the official suite is not expected to compile yet. No new ClojureCLR or clojerl perf-all numbers at this pin. **The box varies**: read a difference under ~1.5× between two hosts as possible noise; the orders of magnitude between groups of hosts are what this table measures. Older tables, including the 2026-10-21 release-box run, remain in git history.

