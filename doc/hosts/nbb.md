# eve on nbb

nbb (`hosts.edn :nbb`) is ClojureScript interpreted by sci on Node. It shares ClojureScript-on-Node's memory, codec
and threads (the module slab, SharedArrayBuffer regions, eve's N-API addon for disk domains, `worker_threads`) and
differs where sci does: it evaluates, so a worker loads eve from source on the same classpath rather than receiving a
compiled kernel; and sci lacks a few of cljs.core's protocols and chunked seqs, which `coll/nbb` and `types/nbb`
supply.

```bash
npm install -g nbb@1.6.214     # nbb on PATH; npm install in eve/ builds the addon (disk domains, the key ring)
bb test:nbb                    # the battery, run from eve/ (the addon is found from the working directory)
nbb --classpath "<src:hosts.edn :nbb roots:test>" -m eve.runner.nbb-main perf   # the performance suite
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:nbb` | **242 tests, 4,617 assertions, 0 failures, 0 errors** (489 s, peak 4.7 GB, the process tree) | 2026-10-21, this tree (the final pass) |
| `bb scripts/thread-rendezvous.clj jvm nbb` (both ways) | 18 of 18 checks (22 s each) | 2026-10-22, this tree (the final pass) |
| `bb test:nbb` | **228 tests, 3,025 assertions, 0 failures, 0 errors** (606 s, peak 4.6 GB) | 2026-10-20, the final sweep |
| `bb test:nbb` | **226 tests, 3,004 assertions, 0 failures, 0 errors** (741 s); peak 4.4 GB resident, in `eve.thread-fold-group-test` (a group of runtimes and their pools within one test, which nothing can release before it ends) | 2026-10-20, nbb 1.6.214 on Node 24 |
| `bb test:nbb`, before the heartbeat timer fix | killed at 12.2 GB resident in `eve.thread-fold-test` (no failures to that point): every worker that opened a disk domain stayed alive | 2026-10-20 |
| `bb test:nbb eve.thread-net-test` | 5 tests, 48 assertions, green (35 s); peak 2.0 GB and 18 threads, the process tree sampled each second, 71 MB and 6 threads at its end | 2026-10-22, this tree (the final pass) |
| `bb test:nbb eve.thread-net-test` | 5 tests, 48 assertions, green; peak 1.3 GB (3.5 GB and rising before the fix) | 2026-10-20 |
| `bb scripts/thread-rendezvous.clj nbb bb` (both ways) | 18 of 18 checks | 2026-10-19 |

The runner (`test/eve/runner/nbb_main.cljs`) runs the same 28 shared namespaces as babashka's — the typed arrays,
datasets and tensors among them (nbb has ClojureScript's typed arrays) — and yields to Node's event loop between test
vars (below).

### The memory fix (2026-10-20)

A Node worker ends when its event loop has nothing left to run. eve's disk domains keep a heartbeat timer, and the
Node process adapter started it with a plain `setInterval`, which keeps the loop alive: a worker that opened a disk
domain — every domain cell's ticker and pump — never ended, and each held its isolate (~320 MB with eve and the test
namespaces loaded under sci). On every other host the heartbeat is a daemon (the JVM's and ClojureCLR's background
threads, Python's daemon thread, a BEAM process, a goroutine). It is now an unref'd interval: it never keeps a process
or a worker alive. Measured on `eve.thread-net-test`, a pause after each test: before, resident memory and the thread
count only rose (to 3.5 GB, 21 threads); after, they return between tests (750 MB, 11 threads). The same change took
compiled ClojureScript's `thread-domain` suite from the ~3.8 GB its baseline note records to a 1.55 GB peak.

## Performance

<!-- perf:nbb (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 301.0 µs | 4096 | 25 |
| `atom-deref-small` | 27.9 µs | 65536 | 1.6 |
| `atom-swap-map-1k` | 81.9 µs | 16384 | 18 |
| `atom-deref-map-1k` | 5.8 ms | 256 | 15 |
| `atom-$-get-map-1k` | 33.7 µs | 32768 | 16 |
| `atom-reset-vec-1k` | 78.3 ms | 16 | 51 |
| `sorted-set-build-200` | 260.8 ms | 4 | 23 |
| `int-map-build-200` | 171.3 ms | 8 | 38 |
| `disk-swap-counter` | 308.6 µs | 4096 | 18 |
| `thread-submit-64` | 11.0 ms | 128 | 11 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:nbb -->

Reading it: babashka's interpretation cost on V8 — swaps near babashka's (a counter swap 301 µs against 325 µs, a
1,000-key map's 81.9 µs against 115 µs), reads faster (V8's DataViews under sci: a small deref 27.9 µs against 68.9
µs). 64 tasks: 11.0 ms, its workers each loading eve from source.

What sets nbb's numbers: sci interprets every call, and eve's types are sci records answering eve's protocols, so
nbb pays babashka's interpretation cost on V8 rather than SubstrateVM, with the module slab's DataView reads beneath.
A worker starts by loading eve from source (seconds, not ClojureScript's ~80 ms for a compiled kernel), so a pool's
first task is slow and a task-sized message is most of a round trip.

## How eve is built on nbb

| Feature | Adapter | How |
|---|---|---|
| coll | `coll/nbb` | cljs.core plus what nbb lacks: the chunked-seq protocols (nbb has no chunked seqs: `array-chunk` returns a vector), `ASeq`, `pr-writer`, `caching-hash` |
| types | `types/js` + `types/nbb` | ClojureScript's realization, with the protocols sci lacks mapped to eve.core's; sci resolves a form's symbols when it reads it, so factories are declared first |
| thread | `thread/node` | `worker_threads`: a worker runs nbb's CLI on the same `--classpath` and requires the program's namespaces (`:requires`), then runs its entry; one `MessagePort` per pair of threads, a SharedArrayBuffer doorbell |
| slab, region, typed-view | `slab/module`, `region/js`, `typed-view/js` | ClojureScript's: six SharedArrayBuffer slabs (or one disk domain's files) through DataViews; disk domains through eve's N-API addon (`native/mmap_cas.cc`) |
| encoding, int32, codec, process | `encoding/js`, `int32/js`, `codec/js`, `process/node` | ClojureScript's |
| crypto, http | `crypto/node`, `http/node` | `node:crypto`; Node's fetch on a worker, the caller parked on `Atomics.wait` |
| core, completion, slab-io | `core/clojure`, `completion/clojure`, `slab-io/protocol` | loaded as `.cljc` directly (no compiler roots) |

## Gotchas

- **Node frees an ended worker only on its starter's event loop.** A program that blocks its main thread holds every
  worker that ended meanwhile; the battery yields between test vars for this. A long synchronous section that starts
  many short threads should yield too.
- **One disk domain at a time per process** (the module slab is module state, as on ClojureScript).
- **Run from `eve/`** (or wherever `build/Release` holds the addon): disk domains and the key ring load it from the
  working directory.
- **No http-server**: nbb cannot serve a gateway (ClojureScript on Node can).
- The runner's docstring shows `nbb --classpath src:test …`, which cannot load `eve.host`; use `bb test:nbb` or the full
  root list.

## Future directions

Optimization:

- Workers that load a prebuilt bundle instead of eve's sources (nbb can load compiled JS): a pool's start in
  milliseconds.
- The interpretation cost: eve's hot paths (codec, HAMT walk) precompiled as ClojureScript and loaded by nbb as JS.

Coverage:

- eve.net's own suites (they run on Node's compiled runner; not yet on nbb's).
- The http-server feature (Node's `http`, as ClojureScript's adapter) so nbb can serve a gateway.
