# eve.dom — a browser program whose main thread is a worker

The program's main runs in a worker; window and document there are proxies for the page's, so code made for a page
(reagami, canvas-confetti, d3-selection and d3-transition) runs unchanged. The page's own thread only executes DOM operations
(`eve.dom.screen`: a fixed op set over integer handles, no eval). Ideas after cljs-thread's dom proxy and VSv2's DOM
executor; nothing of either is a dependency.

```clojure
(ns app.core (:require [eve.dom.app :as app]))
(defn main [] (set! (.-textContent (.getElementById js/document "app")) "hello from a worker"))
(defn init [] (app/run-app! {:main 'app.core/main}))   ; the build's :init-fn; :simple, :output-wrapper false
```

## Transports
| page | transport | notes |
|---|---|---|
| cross-origin isolated (COOP+COEP) | proxy SharedArrayBuffer, 128 slots x 64 KB, Atomics.wait in the worker, waitAsync (or a posted bell) on the page | main's worker is an eve.thread-host thread: it can start eve.thread pools |
| not isolated, service worker can isolate | `eve-sw.js?coi=1` adds COOP same-origin / COEP credentialless (require-corp on Safari) / CORP; one reload; then SAB | Chromium, Firefox, WebKit |
| not isolated, kept so (`?eve-dom-transport=sw` or `:transport :sw`) | worker posts the frame, blocks in a sync XHR `/eve-sw/px/wait` the SW holds until the page posts `/eve-sw/px/reply` | Chromium, WebKit. Firefox does not send a worker's sync XHR through a service worker: refused as `:eve.dom/refused :sw-not-relaying` (its isolated path works). No eve.thread pools here. |

The one service worker is `adapters/sw/browser/eve-sw.js` (served at `/eve-sw.js`; features add routes with
`self.eveSw.route`), shared with the OPFS tier. `start!` takes `:boot (fn [{:keys [start]}] ...)` to mount OPFS
pools before main's worker starts.

## Semantics
- Writes and calls known to answer nothing are queued and sent in one frame with the next call that answers, at the end
  of a page event's callback or animation frame, or on a microtask. Created nodes get preallocated handles, so
  `createElement` + `setAttribute` + `appendChild` cost no round trip.
- A worker function crosses only where the page calls it later: listeners, `on*` properties, observers
  (`new MutationObserver(f)`), timers, `requestAnimationFrame`, promise reactions. Anywhere else the call is refused
  (`ex-data` `{:eve.dom/refused :sync-callback}`). A listener runs after the page's dispatch is over: `target`
  stays, `currentTarget` is null, `preventDefault` has no effect.
- Kept in the worker: keys starting `__`, namespaced keys (`reagami.core/vnode`), symbols, and non-page values.
  `window.foo = x` writes the worker's global. `requestAnimationFrame` is local, one page frame per tick.
- `instanceof HTMLElement` etc. answer by the page object's constructor name (synthetic constructors); their
  prototypes carry no page methods, so libraries probing them (confetti's `transferControlToOffscreen`) take their
  plain path.
- A page-side throw is rethrown in the worker with its name and message; a queued op's failure is logged on the page
  and counted (`(eve.dom.proxy/stats)` `:page-errors`).
- In main's worker, `location` is a blob: URL under the isolated transport: use absolute URLs
  (`(.-origin js/location)`) for fetch/importScripts.

## Tests
`bb test:browser:dom` (conformance by each transport, Chromium), `bb test:browser:dom-demo` (reagami counter, ten clicks moving a d3-transition bar to 100%, then confetti; vendored libraries and licences in `adapters/dom/browser/demo/public/vendor`).
`EVE_BROWSER=firefox|webkit` with `NODE_PATH` (playwright) and `PLAYWRIGHT_BROWSERS_PATH` runs another engine;
`EVE_BROWSER_COI=0`, `EVE_BROWSER_QUERY=eve-dom-transport=sw`, `EVE_BROWSER_EXPECT=sab|sw` select a transport.

## Performance
Measured 2026-11-24 05:51–05:53 AM ET on box J (Linux x86_64), Eve `3a105fc3` plus the launcher fix, load1 8.9 median
(other lanes running), BENCH.lock held per cell.

| cell (target SAB / SW) | Chromium 156 SAB | Chromium SW relay | Firefox SAB | WebKit SAB | WebKit SW relay |
|---|---:|---:|---:|---:|---:|
| `px-get-rtt` p50 / p95 (≤ 50 µs / report) | 25 / 45 µs | 0.9 / 1.5 ms | 40 / 60 µs | 20 / 100 µs | 1 / 2 ms |
| `px-call-rtt` p50 / p95 (≤ 80 µs / report) | 30 / 70 µs | 0.8 / 1.4 ms | 40 / 140 µs | 40 / 100 µs | 1 / 2 ms |
| `px-batch-100` p50 / p95 (≤ 0.5 ms / ≤ 1 call) | 0.27 / 1.1 ms | 1.5 / 6 ms | 0.38 / 0.78 ms | 0.40 / 1.06 ms | 1 / 3 ms |
| `px-event-latency` p50 / p95 (≤ 1 ms / ≤ 2 ms) | 35 / 95 µs | 1.1 / 2.7 ms | 100 / 220 µs | 60 / 340 µs | 1 / 2 ms |
| worker `click()` to handler p50 / p95 | 55 / 180 µs | 1.2 / 2.8 ms | 140 / 340 µs | 100 / 500 µs | 1 / 3 ms |
| `px-frame-d3` interval p50 / p95 / max, frames over 25 ms (≤ 16 ms / report) | 16.7 / 18.1 / 19.1, 0 | 16.7 / 17.5 / 19.9, 0 | 16.7 / 19.2 / 19.7, 0 | 16.1 / 18.2 / 20.2, 0 | 16 / 19 / 21, 0 |
| `first-render`: to main's start / rendered / FCP (report) | 211 / 215 / 228 ms | 241 / 255 / 272 ms | 446 / 457 / 469 ms | 325 / 330 / 338 ms | 236 / 256 / 273 ms |

Each cell is three page loads (fresh profile each), the median of the loads' own p50/p95; the loop inside a load is
1,000 reads (get), 500 querySelector calls on a 1,000-div page (call), 50 rounds of 100 `setAttribute` and the read
that sends them (batch), 200 page-dispatched clicks (event; the page's clock, `performance.timeOrigin` +
`event.timeStamp`, against the worker's), and 10 d3 transitions of 1 s until all end (frame: the worker's
animation-frame intervals, which can't go below the display's 16.7 ms at 60 Hz; the cell counts dropped frames, it does
not time each frame's work). Firefox's SW relay is refused by design. The SW pages are not cross-origin isolated, so
their clocks are coarse (Chromium 0.1 ms, WebKit 1 ms): read those columns as ≤.

Chrome is launched with `--disable-extensions` for eve.dom's suites, as Playwright launches it. With an extension a
machine's policy force-installs (this fleet's Chrome has two), a worker's run of a few hundred back-to-back synchronous
XHRs ends in a NetworkError, the relay's and plain network ones alike; without extensions 3,000 of 3,000 went through in
every mode, and 36 of 36 Chromium relay loads of this table's runs were clean.

Run: `bb perf:browser:dom` (`scripts/dom-perf.sh`; `CELLS`, `ENGINES`, `TRANSPORTS`, `REPS`, `OUT`, `EVE_BENCH_LOCK`).
