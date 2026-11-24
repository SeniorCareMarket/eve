# eve in a browser

ClojureScript in a browser (`hosts.edn :browser`) is ClojureScript's adapters with the browser's own threads, http and
crypto. Its constraints shape the adapters: the page must be **cross-origin isolated** (COOP `same-origin` + COEP
`require-corp`) for SharedArrayBuffer; the page's main thread **cannot block** (`Atomics.wait` throws there); a worker
can read no `MessagePort` synchronously; WebCrypto is promise-only. So a thread's mailbox is a ring in one
SharedArrayBuffer every thread holds, the page creates every worker, http is synchronous `XMLHttpRequest`, and crypto
is the portable code. A page's disk domains live in the origin private file system (OPFS; *Disk domains: the OPFS
tier*, below).

```bash
bb test:browser                       # the thread feature's conformance and eve.thread's battery, headless Chromium
EVE_BROWSER_PERF=1 bb test:browser    # the performance suite in a worker (a page of its own, :eve-perf-browser)
EVE_BROWSER_OPFS=1 bb test:browser    # the OPFS tier's suite (isolated by eve-sw.js; tabs, takeover, follower relay)
EVE_BROWSER_OPFS=1 EVE_BROWSER_COI=0 EVE_BROWSER_QUERY=eve-opfs-relay=1 bb test:browser   # the relay pool, unisolated
EVE_BROWSER_PERF=opfs bb test:browser # disk-swap-counter on OPFS (:eve-perf-opfs-browser; opt-in, not in perf-all)
EVE_BROWSER=firefox …                 # any of these in headless Firefox (webkit: see the gotchas)
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
a browser (they run on Node). The OPFS tier's suites are their own pages (`EVE_BROWSER_OPFS=1`, above).

## Performance

<!-- perf:browser (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 17.7 µs | 65536 | 1.6 |
| `atom-deref-small` | 2.9 µs | 524288 | 1.0 |
| `atom-swap-map-1k` | 7.1 µs | 262144 | 1.7 |
| `atom-deref-map-1k` | 2.3 µs | 524288 | 0.9 |
| `atom-$-get-map-1k` | 2.9 µs | 524288 | 5.0 |
| `atom-reset-vec-1k` | 6.9 ms | 256 | 4.7 |
| `sorted-set-build-200` | 13.9 ms | 128 | 2.6 |
| `disk-swap-counter` | n/a | — | — |
| `thread-submit-64` | 20.5 ms | 128 | 8.1 |

Measured 2026-11-23 11:34–11:36 PM ET on this box (J, Linux x86_64, Temurin 21.0.13) at Eve `441ae29f` plus the pack-plain wire-encode fix, by `bb scripts/perf-all.clj`, one host at a time, load1 2.49; a round of at least 1 s. `disk-swap-counter` is n/a (no disk domains in a browser page). `thread-submit-64` is 20.5 ms (it was 140.0 ms after the MessagePort fix; 3.3 ms on this box at `77b649aa5`, 2026-10-21, load1 1.52).
<!-- /perf:browser -->

Reading it: a small deref 2.9 µs, within noise of the JVM's 2.9 µs, and within ~1.6–1.7× of the JVM on swaps. No disk
domains in a page. 64 tasks to the page's workers: 20.5 ms in this run (140.0 ms after the MessagePort fix; 3.3 ms at `77b649aa5` on this box).

What sets a page's numbers: the same compiled ClojureScript as Node, on the same V8, with the module slab in each
worker; a network atom from a browser is bound by the browser's six connections per origin (a cold deref of ~1,100
blocks: 5.7 s from a worker, against 4.5 s on babashka), and a page's main thread reading synchronously serves no
worker's batch meanwhile (15.4 s against 5.7 s), so the capstone reads in a worker.

### eve.dom (a worker main driving the page's DOM)

Per-op costs of eve.dom's proxies (docs/eve-dom.md has the method), 2026-11-24 04:59–05:15 AM ET, Eve `49f5f8a6`:
SAB is a cross-origin isolated page, SW the service worker's synchronous-XHR relay on a page that is not.

| cell (target SAB / SW) | Chromium 156 SAB | Chromium SW relay | Firefox SAB | WebKit SAB | WebKit SW relay |
|---|---:|---:|---:|---:|---:|
| `px-get-rtt` p50 / p95 (≤ 50 µs / report) | 25 / 110 µs | 1.0 / 1.8 ms | 40 / 100 µs | 20 / 60 µs | 1 / 1 ms |
| `px-call-rtt` p50 / p95 (≤ 80 µs / report) | 35 / 95 µs | 1.0 / 1.6 ms ¹ | 40 / 120 µs | 40 / 80 µs | 1 / 1 ms |
| `px-batch-100` p50 / p95 (≤ 0.5 ms / ≤ 1 call) | 0.26 / 0.66 ms | 1.2 / 2.3 ms | 0.40 / 1.06 ms | 0.36 / 1.38 ms | 1 / 2 ms |
| `px-event-latency` p50 / p95 (≤ 1 ms / ≤ 2 ms) | 30 / 90 µs | 1.0 / 1.4 ms | 60 / 100 µs | 40 / 140 µs | 1 / 1 ms |
| worker `click()` to handler p50 / p95 | 60 / 195 µs | 1.1 / 1.5 ms | 80 / 180 µs | 60 / 220 µs | 1 / 1 ms |
| `px-frame-d3` interval p50 / p95 / max, frames over 25 ms (≤ 16 ms / report) | 16.7 / 19.5 / 22.7, 0 | 16.6 / 20.8 / 23.8, 0 | 16.7 / 17.9 / 19.5, 0 | 16.1 / 17.7 / 19.1, 0 | 16 / 18 / 21, 0 |
| `first-render`: to main's start / rendered / FCP (report) | 193 / 197 / 214 ms | 192 / 205 / 216 ms | 356 / 361 / 377 ms | 287 / 292 / 315 ms | 205 / 216 / 222 ms |

SW columns are ≤ (coarse clocks on an unisolated page). Firefox's SW relay is refused by design. One Chromium SW
`px-call-rtt` load of three failed with a NetworkError in the relay's synchronous XHR (see docs/eve-dom.md).

## Disk domains: the OPFS tier

A persistent atom in a page is the same `persistent-atom-domain` / `{:persistent path}` atom as on Node, its files in
the origin's private file system. OPFS writes a file synchronously only through a `FileSystemSyncAccessHandle`, which
is exclusive to the worker that opened it, so `eve.opfs.mount/mount!` starts **one io worker** (`eve/opfs_io.js`)
that opens every file, and every eve worker reaches it through a request block in a SharedArrayBuffer (a lock, a
doorbell, a done word; the caller waits with `Atomics.wait`). A file's bytes live in a pool slot every worker holds, so
a region reads and writes them in place; only a sync goes to the io worker, which writes the 4 KB pages whose 64-bit hash
changed since it last wrote them, through a fixed bounce buffer (a sync handle refuses a view over a growable buffer).

```clojure
;; on the page
(-> (eve.opfs.boot/boot! {:opfs {:root "my-app"} :start #(start-main-worker!)})
    (.then (fn [{:keys [pool relay]}] ...)))
;; in a worker
(def a (eve.atom/persistent-atom {:id :app/state :persistent "/db"} {:n 0}))
```

| Piece | What it does |
|---|---|
| `eve.opfs.boot/boot!` | isolates the page (registers `/eve-sw.js?coi=1`, which adds COOP/COEP, and reloads once; the guard is in `sessionStorage`), mounts the pool, starts main. `:isolate false` keeps the page unisolated (the relay pool) |
| tabs | one tab **owns** the files (its io worker holds the Web Lock `eve:<root>`; a term in `.eve-term`); others are **followers** (`eve.opfs.follow/follower-atom`): a deref is cached until an owner commit is heard, a `swap!` sends a compare-and-set to the owner. When the owner's tab closes a follower takes over (term + 1) and recovers from the journal |
| exactly once | a follower's request id `[client seq]` rides in the owner's jlog commit record (`eve.atom/*request-id*`); anchors keep each client's highest applied seq (`eve.atom/applied-request-seq`), so a resend of what a dead owner applied is answered, not applied again |
| the relay | eve's one service worker (`adapters/sw/browser/eve-sw.js`) routes `/eve-sw/opfs/call` (a synchronous XHR; 504 then resent under the same key, answered once, reply kept 30 s), `/next` (the serving tab's long poll, by host and term; a newer term ends older polls and takes back calls they held), `/reply`, `/ping`. A follower whose owner is in another tab reaches it this way. URLs resolve against `location.origin` (eve threads have `blob:` locations) |
| the relay pool | a page that is **not cross-origin isolated** shares no memory between workers, but `new WebAssembly.Memory({shared: true}).buffer` still makes a growable SharedArrayBuffer in Chrome, Firefox and WebKit (it cannot be posted). So the program's main worker is the domain host: it holds the request block and the slots, and talks to its io worker (the kernel URL at `?eve-opfs-io=1`; Firefox does not let the service worker control a nested `blob:` worker) over the relay — a sync's changed bytes out (diffed against a shadow copy), an open's file bytes back. eve's memory is then that one worker's |
| recovery | a fresh root (no live worker holds the file) is read from storage; a commit survives a killed browser (the relaunch test: same profile, fixed port, 7 commits then a kill, 7 read back) |

Measured (`EVE_BROWSER_PERF=opfs`, this box, 2026-11-24 ET, nice 19, one run, under gate load): `disk-swap-counter`
404 µs/op in headless Chrome 156 (4096 ops, `readwrite-unsafe` handles), 979 µs/op in headless Firefox 159 (1024
ops, `readwrite` handles), durable (journal batch 1). Not a `perf-all`
cell: the browser row's `disk-swap-counter` stays n/a there, since that page has no OPFS pool.

## How eve is built in a browser

| Feature | Adapter | How |
|---|---|---|
| thread | `thread/browser` | dedicated Web Workers by the fat-kernel route: a Blob bootstrap `importScripts` the kernel (the program itself, a `:simple` build with no output wrapper, its URL found from the build's `manifest.edn` by synchronous XHR); every thread's mailbox a 256 KB ring in one SharedArrayBuffer; workers created by the page from a spawn ring it pumps (`Atomics.waitAsync`), because a blocked worker never turns its own event loop to start a child; a worker closes itself once its entry returns and its exit notice is sent |
| http | `http/browser` | synchronous `XMLHttpRequest`; on the main thread bytes come as an `x-user-defined` binary string; a worker's batch (a level of a network atom's blocks) is posted to the page, fetched with `Promise.all`, the worker waiting on `Atomics.wait` |
| region, slab-io (disk) | `opfs/browser` | `eve.region.opfs` over `eve.opfs`'s io worker (above) |
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
- **No http-server**; headers the browser forbids are dropped; a batch's answers are capped at 8 MB.
- **Disk domains only in workers, and only after the pool mounts**: `eve.opfs.boot/boot!` mounts the OPFS pool
  before any eve thread starts; a worker spawned before it has no file pool.
- **WebKit (Playwright's) does not pass the OPFS suite on this box**: `RangeError: Out of memory` creating a
  SharedArrayBuffer in the suite's reader worker, in both the header-isolated and the service-worker runs, on the
  tree that passed earlier too (environmental; not fixed). Chrome and Firefox pass.
- **Firefox's synchronous manifest read fails on an unisolated page** (`eve.thread-host`'s kernel lookup): the relay
  page loads the kernel by `<script src>` instead; not fixed in `thread-host`.
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
