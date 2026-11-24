# eve.dom — a browser program whose main thread is a worker

The program's main runs in a worker; window and document there are proxies for the page's, so code made for a page
(reagami, canvas-confetti, d3-selection) runs unchanged. The page's own thread only executes DOM operations
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
`bb test:browser:dom` (conformance by each transport, Chromium), `bb test:browser:dom-demo` (reagami counter + confetti).
`EVE_BROWSER=firefox|webkit` with `NODE_PATH` (playwright) and `PLAYWRIGHT_BROWSERS_PATH` runs another engine;
`EVE_BROWSER_COI=0`, `EVE_BROWSER_QUERY=eve-dom-transport=sw`, `EVE_BROWSER_EXPECT=sab|sw` select a transport.
