# eve on ClojureScript (Node)

ClojureScript on Node (`hosts.edn :cljs`) is eve's second reference host and the one its in-process memory was first
built for: the **module slab** — six SharedArrayBuffer slabs and an overflow class, read through DataViews, allocated
by bitmap with wasm-SIMD scans — shared by a process's `worker_threads`. Disk domains go through eve's N-API addon
(`native/mmap_cas.cc`: `MAP_SHARED`, the `__atomic_*` built-ins, futex). Compiled ClojureScript cannot eval, so a worker
receives the compiled program itself (the fat-kernel route).

```bash
npm install                    # builds eve's addon (C++20; POSIX only)
npm run test:compile           # shadow-cljs: target/eve-test/all.js (+ the mmap worker)
node target/eve-test/all.js all          # the default battery; a suite name runs one suite
node target/eve-test/all.js perf         # the performance suite
```

The ClojureScript rows of `hosts.edn` and `deps.edn` also carry the JVM's adapter roots: the compiler's own Clojure is
a JVM, and eve's macros run there (the byte-array codec's `.cljc` yields to the `.cljs` one; ClojureScript never reads
the `.clj` files).

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `node target/eve-test/all.js all` | **482 tests, 34,980 assertions, 0 failures, 0 errors** (72 s, peak 2.7 GB, the process tree) | 2026-10-21, this tree (the final pass, `doc/release-0.9/logs-2026-10-22/f-node-*.log`) |
| `node target/eve-test/all.js slab-grow` | **105 tests, 28,902 assertions, green** (20 s): the slab suites over slabs that start small and grow (a browser's default) | 2026-10-21, this tree (the final pass) |
| `node target/eve-test/all.js thread-domain` | **12 tests, 146 assertions, green** (36 s, peak 1.6 GB) | 2026-10-21, this tree (the final pass) |
| `node target/eve-test/all.js thread-fold-group` | **3 tests, 49 assertions, green** (16 s, peak 1.6 GB) | 2026-10-21, this tree (the final pass) |
| `node target/eve-test/all.js net` | **62 tests, 1,429 assertions, green** (274 s, peak 1.9 GB) | 2026-10-21, this tree (the final pass) |
| `node target/eve-test/all.js deftype` | **63 tests, 710 assertions, green** (3 s) | 2026-10-21, this tree (the final pass) |
| `node target/eve-test/all.js rrb-vector` | **22 tests, 68 assertions, green** (24 s) | 2026-10-21, this tree (the final pass) |
| `node target/eve-test/all.js all` | **468 tests, 33,388 assertions, 0 failures, 0 errors** (84 s, peak 2.2 GB) | 2026-10-20, the final sweep |
| `node target/eve-test/all.js slab-grow` | **91 tests, 27,310 assertions, green** (18 s) | 2026-10-20, the final sweep |
| `node target/eve-test/all.js thread-domain` | **12 tests, 146 assertions, green** (43 s, peak 1.5 GB) | 2026-10-20, the final sweep |
| `node target/eve-test/all.js thread-fold-group` | **3 tests, 49 assertions, green** (18 s, peak 1.5 GB) | 2026-10-20, the final sweep |
| `node target/eve-test/all.js net` | **62 tests, 1,429 assertions, green** (313 s, peak 4.2 GB) | 2026-10-20, the final sweep |
| `node target/eve-test/all.js all` | **462 tests, 33,353 assertions, 0 failures, 0 errors** (78 s, peak 2.1 GB) | 2026-10-20, earlier in this round |
| `node target/eve-test/all.js thread-domain` | **12 tests, 146 assertions, green**; peak 1.55 GB (the baseline note: ~3.8 GB, before the heartbeat fix) | 2026-10-20 |
| `node target/eve-test/all.js thread-fold-group` | 3 tests, 49 assertions, green; peak 1.5 GB | 2026-10-20 |
| `node target/eve-test/all.js net` | 62 tests, 1,429 assertions, green (302 s) | 2026-10-20, the release-prep lane's final tree |

`all` runs the core collections (map, vector, set, list, the slab), epoch GC, typed objects, typed arrays, datasets,
tensors, int-map, the red-black set, deftype, validation, the fixtures (**52 of 71 rows, 19 skipped**: ClojureScript
has no char, and no big integers, ratios or big decimals in its numbers), the em5 suites, and the thread feature's
in-process suites. Not in `all`, run by name: the memory and mmap suites (`mem`, `mmap`, `mmap-slab`, `mmap-atom`,
`mmap-atom-e2e`, `mmap-domain`), `net`, `rrb-vector` (24 s in the final pass), `thread-domain` and `thread-fold-group` (disk domains,
many workers), `fuzz`, `conformance`, `lustre`, `repro`, `slab-leak`, and the benches.

## Performance

<!-- perf:cljs (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 33.2 µs | 32768 | 2.8 |
| `atom-deref-small` | 8.9 µs | 131072 | 0.52 |
| `atom-swap-map-1k` | 10.1 µs | 131072 | 2.2 |
| `atom-deref-map-1k` | 1.7 ms | 1024 | 4.6 |
| `atom-$-get-map-1k` | 6.4 µs | 262144 | 3.1 |
| `atom-reset-vec-1k` | 11.5 ms | 128 | 7.5 |
| `sorted-set-build-200` | 36.4 ms | 32 | 3.3 |
| `int-map-build-200` | 8.1 ms | 128 | 1.8 |
| `disk-swap-counter` | 43.7 µs | 32768 | 2.5 |
| `thread-submit-64` | 7.6 ms | 256 | 7.7 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:cljs -->

Reading it: 2–5× the JVM's on swaps and its reads (a 1,000-key map's deref 1.7 ms against 0.38 ms), an int-map
build 1.8× the JVM's (8.1 ms), a small deref twice as fast as the JVM's. The thread workload crosses to worker
isolates (7.6 ms for 64 tasks). The browser runs the same compiled code faster on every in-memory workload
(1.3–2.2×), its slabs growable — not investigated (Chromium's V8 against Node 24's, the perf page's smaller bundle).

What sets ClojureScript's numbers: V8 compiles the slab I/O to typed-array access and the allocator's bitmap scans are
wasm-SIMD, so in-process work is within 2–3× of the JVM's (faster than the JVM's until the JVM's reflection fix); numbers are doubles (an INT64 past 2^53 is lossy on
ClojureScript only); a worker starts in ~80 ms (the compiled kernel, `eve.thread.pool`'s measurement).

## How eve is built on ClojureScript (Node)

| Feature | Adapter | How |
|---|---|---|
| slab | `slab/module` | the module slabs: six SharedArrayBuffer slabs (or one disk domain's files) through DataViews, bitmap allocation with SIMD where wasm is ready, a class-6 coalescing region, the atom root and the worker registry; a wasm instance per slab |
| region | `region/js` | `JsSabRegion` (SharedArrayBuffer + `Atomics`) in process; `NodeMmapRegion` through the N-API addon across processes; `LustreMmapRegion` by fcntl locks |
| slab-io | `slab-io/protocol` + `slab-io/cljs-compiler` | macros over `ISlabIO` / `IMemRegion`, taken through `:require-macros` |
| typed-view | `typed-view/js` | JS TypedArrays over an EveArray; wasm-SIMD bulk ops on `:int32` |
| coll | `coll/cljs` | cljs.core's own functions: eve values answer `count`, `get`, `seq`, `=` |
| types | `types/jvm` (its ClojureScript arm) + `types/js` | ClojureScript `deftype`s with cljs.core's protocols |
| encoding, int32, codec | `encoding/js`, `int32/js`, `codec/js` | `TextEncoder` / `TextDecoder`, `Math.imul`, DataView with a keyword cache |
| process | `process/node` | `Date.now`, `Atomics.wait` sleep, an unref'd heartbeat interval, `fs` `"wx"` for exclusive creation, `crypto.getRandomValues` |
| thread | `thread/node` | `worker_threads` by the fat-kernel route (`new Worker(kernel-source, {eval: true})`, the kernel the program's own compiled file); one `MessagePort` per pair of threads; a SharedArrayBuffer doorbell |
| crypto | `crypto/node` | `node:crypto`; XXH3 portable |
| http, http-server | `http/node`, `http-server/node` | fetch on a worker thread, the caller parked on `Atomics.wait`; Node's `http` module (ClojureScript on Node serves a gateway) |

## Gotchas

- **One disk domain at a time per process**: the slabs are module state, and opening a disk domain swaps them (the
  replaced state comes back on release).
- **No eval**: routed forms are refused (`eval-form` throws) — a task is a `deftask`, never a form.
- **Node frees an ended worker only on its starter's event loop**: a main thread that blocks holds every worker that
  ended meanwhile (~75 MB each, ~210 MB with eve loaded).
- **`clojure.core/compare-and-set!` is not safe on eve atoms** (deref then reset; across two processes it lost 136 of
  1,000 updates): use `eve.atom/compare-and-set!*`.
- A SharedArrayBuffer region's 64-bit operations are two 32-bit halves: not atomic, and 64-bit add / sub throw.
- An mmap region's `-read-bytes` answers a view aliasing the mapping (copy before keeping it).
- The class-6 SharedArrayBuffer reserves 1 GB of virtual memory per process (and per worker).
- A ClojureScript consumer gets no ClojureScript adapters from `deps.edn`'s default paths: add the `:cljs` roots
  (`hosts.edn`, or the `:node-test` alias's list).
- The addon needs a C++20 toolchain at `npm install`, and is POSIX-only (no Windows build).

## Future directions

Optimization:

- 64-bit atomics on SharedArrayBuffer regions through `BigInt64Array` and `Atomics` (supported by V8), retiring the
  two-halves emulation.
- Prebuilt addon binaries (and an `engines` field) so `npm install` needs no compiler.

Coverage:

- The disk-domain suites in `all` now that their peak is 1.5 GB (`thread-domain`, `thread-fold-group`).
- Node 18 / 20 / 22 (only Node 24 was run).
- Recycled thread ids and more than one disk domain per process (the module slab as a value rather than module state).
