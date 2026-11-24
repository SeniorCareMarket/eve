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
Measured 2026-11-24 04:59–05:15 AM ET on box J (Linux x86_64), Eve `49f5f8a6` plus this runner, load1 4.2–9.3 (other
lanes running), BENCH.lock held per cell.

| cell (target SAB / SW) | Chromium 156 SAB | Chromium SW relay | Firefox SAB | WebKit SAB | WebKit SW relay |
|---|---:|---:|---:|---:|---:|
| `px-get-rtt` p50 / p95 (≤ 50 µs / report) | 25 / 110 µs | 1.0 / 1.8 ms | 40 / 100 µs | 20 / 60 µs | 1 / 1 ms |
| `px-call-rtt` p50 / p95 (≤ 80 µs / report) | 35 / 95 µs | 1.0 / 1.6 ms ¹ | 40 / 120 µs | 40 / 80 µs | 1 / 1 ms |
| `px-batch-100` p50 / p95 (≤ 0.5 ms / ≤ 1 call) | 0.26 / 0.66 ms | 1.2 / 2.3 ms | 0.40 / 1.06 ms | 0.36 / 1.38 ms | 1 / 2 ms |
| `px-event-latency` p50 / p95 (≤ 1 ms / ≤ 2 ms) | 30 / 90 µs | 1.0 / 1.4 ms | 60 / 100 µs | 40 / 140 µs | 1 / 1 ms |
| worker `click()` to handler p50 / p95 | 60 / 195 µs | 1.1 / 1.5 ms | 80 / 180 µs | 60 / 220 µs | 1 / 1 ms |
| `px-frame-d3` interval p50 / p95 / max, frames over 25 ms (≤ 16 ms / report) | 16.7 / 19.5 / 22.7, 0 | 16.6 / 20.8 / 23.8, 0 | 16.7 / 17.9 / 19.5, 0 | 16.1 / 17.7 / 19.1, 0 | 16 / 18 / 21, 0 |
| `first-render`: to main's start / rendered / FCP (report) | 193 / 197 / 214 ms | 192 / 205 / 216 ms | 356 / 361 / 377 ms | 287 / 292 / 315 ms | 205 / 216 / 222 ms |

Each cell is three page loads (fresh profile each), the median of the loads' own p50/p95; the loop inside a load is
1,000 reads (get), 500 querySelector calls on a 1,000-div page (call), 50 rounds of 100 `setAttribute` and the read
that sends them (batch), 200 page-dispatched clicks (event; the page's clock, `performance.timeOrigin` +
`event.timeStamp`, against the worker's), and 10 d3 transitions of 1 s until all end (frame: the worker's
animation-frame intervals; 60 Hz is 16.7 ms). Firefox's SW relay is refused by design. The SW pages are not
cross-origin isolated, so their clocks are coarse (Chromium 0.1 ms, WebKit 1 ms): read those columns as ≤.
¹ One of the three Chromium SW `px-call-rtt` loads failed: the relay's synchronous XHR `/eve-sw/px/wait` ended in a
NetworkError after ~460 calls (the median is of the other two). Without `--disable-background-timer-throttling
--disable-renderer-backgrounding` (Playwright's defaults; `dom-perf` passes them to Chrome) it fails after ~500 calls
every time. WebKit's relay ran every load. In Chromium the first POST after a long synchronous-XHR run can also fail
once (`:post-tries 2` in the EDN), so the cell page retries it.

Run: `bb perf:browser:dom` (`scripts/dom-perf.sh`; `CELLS`, `ENGINES`, `TRANSPORTS`, `REPS`, `OUT`, `EVE_BENCH_LOCK`).
