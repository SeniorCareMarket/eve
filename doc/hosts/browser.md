# eve in a browser

ClojureScript in a browser (`hosts.edn :browser`) is ClojureScript's adapters with the browser's own threads, http and
crypto. Its constraints shape the adapters: the page must be **cross-origin isolated** (COOP `same-origin` + COEP
`require-corp`) for SharedArrayBuffer; the page's main thread **cannot block** (`Atomics.wait` throws there); a worker
can read no `MessagePort` synchronously; WebCrypto is promise-only. So a thread's mailbox is a ring in one
SharedArrayBuffer every thread holds, the page creates every worker, http is synchronous `XMLHttpRequest`, and crypto
is the portable code. There are no disk domains in a page.

```bash
bb test:browser                       # the thread feature's conformance and eve.thread's battery, headless Chromium
EVE_BROWSER_PERF=1 bb test:browser    # the performance suite in a worker (a page of its own, :eve-perf-browser)
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:browser` | **48 tests, 633 assertions, 0 failures, 0 errors**, every main-thread row true (36 s) | 2026-10-21, this tree (the final pass) |
| `bb test:browser` | **48 tests, 633 assertions, 0 failures, 0 errors**, every main-thread row true (44 s; slabs that grow) | 2026-10-20, the final sweep |
| `bb test:browser` | **48 tests, 633 assertions, 0 failures, 0 errors**, every main-thread row true (48 s) | 2026-10-20, earlier in this round |
| `bb test:browser` | **48 tests, 633 assertions, 0 failures, 0 errors**, every main-thread row true (65 s) | 2026-10-20, the 0.9 release preparation's final tree |
| the capstone, `bb eve-media verify` | passed: the page a runtime of its own in the core and extended runs, its library and re-queue checked, the screenshot 11 of 11 rows | 2026-10-22, this tree (the final pass) |
| the capstone, `bb eve-media verify` | the page as a runtime of its own (a worker pool over the network rung) | 2026-10-20 |

The page runs the thread feature's conformance (`eve.thread-host-test`), eve.thread's battery, the meta-atom's suite and
eve.thread.fold's suite in a worker, and checks three rows only the main thread can show: it declares `:block :never`,
polls, and refuses a wait. The data-structure suites, the fixtures, eve.net's suites and the domain cell are not run in
a browser (they run on Node; a page has no disk domain).

## Performance

<!-- perf:browser (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 15.3 µs | 65536 | 1.3 |
| `atom-deref-small` | 4.5 µs | 262144 | 0.26 |
| `atom-swap-map-1k` | 4.7 µs | 262144 | 1.0 |
| `atom-deref-map-1k` | 1.0 ms | 1024 | 2.7 |
| `atom-$-get-map-1k` | 3.4 µs | 524288 | 1.7 |
| `atom-reset-vec-1k` | 6.0 ms | 256 | 3.9 |
| `sorted-set-build-200` | 19.7 ms | 64 | 1.8 |
| `int-map-build-200` | 6.4 ms | 256 | 1.4 |
| `disk-swap-counter` | n/a — no disk domains in a page |  |  |
| `thread-submit-64` | 5.8 ms | 256 | 5.9 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:browser -->

Reading it: the fastest small deref of any host (4.5 µs) and within ~1.3× of the JVM's on swaps, with growable slab
buffers (an A/B on Node with one bundle measured them at a median 1.06×, 0.95–1.24×, on in-memory work in the final
pass; the browser itself was not A/B'd). No disk domains in a page. 64 tasks to the page's workers: 5.8 ms.

What sets a page's numbers: the same compiled ClojureScript as Node, on the same V8, with the module slab in each
worker; a network atom from a browser is bound by the browser's six connections per origin (a cold deref of ~1,100
blocks: 5.7 s from a worker, against 4.5 s on babashka), and a page's main thread reading synchronously serves no
worker's batch meanwhile (15.4 s against 5.7 s), so the capstone reads in a worker.

## How eve is built in a browser

| Feature | Adapter | How |
|---|---|---|
| thread | `thread/browser` | dedicated Web Workers by the fat-kernel route: a Blob bootstrap `importScripts` the kernel (the program itself, a `:simple` build with no output wrapper, its URL found from the build's `manifest.edn` by synchronous XHR); every thread's mailbox a 256 KB ring in one SharedArrayBuffer; workers created by the page from a spawn ring it pumps (`Atomics.waitAsync`), because a blocked worker never turns its own event loop to start a child; a worker closes itself once its entry returns and its exit notice is sent |
| http | `http/browser` | synchronous `XMLHttpRequest`; on the main thread bytes come as an `x-user-defined` binary string; a worker's batch (a level of a network atom's blocks) is posted to the page, fetched with `Promise.all`, the worker waiting on `Atomics.wait` |
| crypto | `crypto/portable` | `eve.net.portable-crypto` (WebCrypto is promise-only) |
| process | `process/node` | its browser-safe parts: `Date.now`, `crypto.getRandomValues`, an interval heartbeat |
| slab, region, typed-view, slab-io, coll, types, encoding, int32, codec | as ClojureScript on Node | SharedArrayBuffer slabs through DataViews, `Atomics` |

## Gotchas

- **Cross-origin isolation is required** (COOP + COEP; CORP on what the page loads), or there is no
  SharedArrayBuffer and eve does not start.
- **The main thread cannot block**: `receive!` with a wait throws `:cannot-block`; do eve's blocking work (a network
  atom's deref, a future's deref) in a worker.
- **A page starts at most 127 threads in its life**: thread ids are never reused (the mailbox buffer has 128 rings).
  Recycling ids (a mailbox freed once its thread has ended and drained) is the fix .
- **A message is at most a mailbox ring's 256 KB**; a full mailbox throws.
- **A renderer caps its SharedArrayBuffers at ~16 GB** (Chromium, measured: fifteen 1 GB buffers in a page, then
  three of 256 MB in a worker), and **a worker loads the whole program**, so `eve.alpha` readies a module slab in
  every worker of a page that requires it. At full capacity a kernel's slabs were ~5 GB, and a page held three
  kernels: the perf suite's second pool thread died at start ("Array buffer allocation failed"), and the conformance
  page with the perf suite linked into it timed out. Since 2026-10-21 a browser's module slabs are growable
  SharedArrayBuffers that start at each class's initial capacity (~8 MB a kernel, the bitmaps sized for the full
  capacity) and double when full; a growable buffer counts against the cap by its length, not its maximum. What a
  page's kernels hold, not what they could hold, is now bounded by the cap.
- **Growable buffers cost in-memory work up to ~25%**: V8's views over a growable SharedArrayBuffer track its length.
  Measured on Node with one bundle, growable against fixed, the median of three runs each (the final pass, 2026-10-21
  ET): a median 1.06× across the suite's in-memory workloads,
  0.95–1.24×, the two builds the most (1.05–1.32× in the first measurement, one run each), so Node keeps fixed buffers
  (it has no cap). `ps/init!` takes `:growable? false` for a page that starts few workers
  and wants the speed.
- **No disk domains, no http-server**; headers the browser forbids are dropped; a batch's answers are capped at 8 MB.
- A page reading a network atom synchronously on its main thread stalls its workers' batches (above).

## Future directions

Optimization:

- A module slab readied on first use rather than when `eve.alpha` loads, so a worker that never touches an eve atom
  reserves nothing.
- Fixed-length views over the growable buffer, rebuilt when it grows — if V8 optimizes a fixed-length view of a
  growable SharedArrayBuffer as it does an ordinary one (not measured here), that takes back the growable buffers'
  cost (up to ~25% on Node); a reader holding the old views must then treat a block past their end as a cue to rebuild them, not as
  zeros.
- One module slab for a page's kernels (the page's buffers passed to each worker at spawn), rather than one each.
- A gateway CAS that compares content keys without materializing the value, for a page's swaps.

Coverage:

- Recycled thread ids (ET-7), lifting the 127-thread limit.
- The data-structure suites and the fixtures in the page (they are compiled ClojureScript; only the runner is missing).
- A public `with-turn` for a writer that is not a runtime, such as the page.
