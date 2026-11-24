(ns eve.dom.app
  "A browser program whose main thread is a worker: the page's own thread only executes DOM operations for it.

   The program's entry point calls (run! {:main 'app.core/main}) first. On the page, run! starts the transport and
   one worker running main, with window and document there as proxies for the page's (eve.dom.proxy); in that
   worker, it runs main. main's worker stays open (page events still reach it) until the page goes.

   Transports: the page cross-origin isolated, the proxy SharedArrayBuffer (and main's worker is an eve.thread-host
   thread, so main can start eve.thread pools). Not isolated, the service worker eve-sw.js (one for the site, shared
   with the OPFS tier) is registered and asked to add the isolation headers, and the page reloads once; where the
   browser still will not isolate it, main's worker blocks in synchronous XHRs the service worker holds until the
   page answers (no eve.thread pools there: they need shared memory)."
  (:require [eve.dom.codec :as c]
            [eve.dom.proxy :as proxy]
            [eve.dom.screen :as screen]
            [eve.thread-host :as h]))

(def ^:private timeout-ms 30000)

(defn- resolve-main [main]
  (let [sym (symbol main)
        f (js/goog.getObjectByName (str (munge (namespace sym)) "." (munge (name sym))))]
    (or f (throw (ex-info (str "eve.dom.app: no function " main) {:main main})))))

(defn- run-main! [main]
  (proxy/install!)
  (let [r ((resolve-main main))]
    (proxy/flush!)
    r))

(defn main-entry
  "The entry of main's worker (an eve.thread-host thread, the page isolated): attach to the proxy
   SharedArrayBuffer, run main, and keep the worker."
  [{:keys [main]}]
  (let [^js init (h/shared-of "eve.dom")]
    (proxy/attach! (proxy/sab-transport (.-sab init) (h/self) (.-bell init) timeout-ms) c/SLOT :sab)
    (h/on-page-message! "eve.dom" proxy/on-page-message)
    (run-main! main)
    (js/Promise. (fn [_ _]))))

(defn- sw-worker-params []
  (when (and (exists? js/WorkerGlobalScope) (exists? js/location))
    (let [p (js/URLSearchParams. (.-search js/location))]
      (when (= "sw" (.get p "eve-dom"))
        {:id (.get p "id") :main (.get p "main")}))))

(defn- run-sw-worker! [{:keys [id main]}]
  (when-not (proxy/sw-relayed?)
    (.postMessage js/self #js {:eveTag "eve.dom" :op "refused" :why "sw-not-relaying"})
    (throw (ex-info "eve.dom.app: this browser does not route a worker's synchronous XHR through the service worker"
                    {:eve.dom/refused :sw-not-relaying})))
  (proxy/attach! (proxy/sw-transport id) (* 4 1024 1024) :sw)
  (.postMessage js/self #js {:eveTag "eve.dom" :op "ready"})
  (.addEventListener js/self "message"
                     (fn [^js e]
                       (let [d (.-data e)]
                         (when (and d (= "eve.dom" (.-eveTag d))) (proxy/on-page-message d)))))
  (run-main! main))

(defn- isolated? [] (and (exists? js/crossOriginIsolated) (true? js/crossOriginIsolated)))

(def ^:private reload-key "eve.dom/coi-reload")

(defn- start-sw-worker!
  "Not isolated, the page controlled by eve-sw.js: main's worker, created from the kernel's own URL (so the service
   worker controls it), the transport in its query string."
  [main]
  (screen/start! {:transport :sw})
  (let [id 1
        url (str (h/kernel) (if (re-find #"\?" (h/kernel)) "&" "?") "eve-dom=sw&id=" id "&main="
                 (js/encodeURIComponent (str main)))
        w (js/Worker. url)
        settle (atom nil)]
    (screen/set-sender! id (fn [msg transfer] (if transfer (.postMessage w msg transfer) (.postMessage w msg))))
    (.addEventListener w "message"
                       (fn [^js e]
                         (let [d (.-data e)]
                           (when (and d (= "eve.dom" (.-eveTag d)))
                             (case (.-op d)
                               "call" (screen/serve-sw-call! id d)
                               "ready" (when-let [f @settle] (f nil))
                               "refused" (when-let [f @settle] (f (keyword (.-why d))))
                               nil)))))
    (js/Promise.
     (fn [resolve reject]
       (reset! settle (fn [why]
                        (if why
                          (reject (ex-info (str "eve.dom.app: no transport: " (name why)) {:eve.dom/refused why}))
                          (resolve {:transport :sw :worker w}))))))))

(defn- start-isolated! [main]
  (screen/start! {:transport :sab})
  (let [[w] (h/start-threads! 1 'eve.dom.app/main-entry {:main (str main)} {})]
    {:transport :sab :thread w}))

(defn- controlled
  "A Promise, resolved once a service worker controls this page."
  []
  (let [sw js/navigator.serviceWorker]
    (if (.-controller sw)
      (js/Promise.resolve true)
      (js/Promise. (fn [resolve _]
                     (.addEventListener sw "controllerchange" (fn [_] (resolve true)) #js {:once true}))))))

(defn- page-option [k]
  (.get (js/URLSearchParams. (.-search js/location)) k))

(defn start!
  "On the page: start main's worker by the best transport the page has; a Promise of {:transport ..}. Options:
   :main (the symbol of main's fn), :sw (the service worker's URL, default /eve-sw.js), :transport (:sw to use the
   service worker's relay even where the page could be isolated; also the page's ?eve-dom-transport=sw), :coi (false:
   do not ask the service worker for isolation headers), :boot (the OPFS tier's boot fn: called with these options and
   :start, a fn that starts main's worker, so the pools and the mount come first)."
  [{:keys [main sw boot transport coi] :or {sw "/eve-sw.js" coi true} :as opts}]
  (let [forced (or transport (some-> (page-option "eve-dom-transport") keyword))
        coi (and coi (not= forced :sw))
        ;; the kernel's URL now, before a service worker controls the page: Firefox fails the synchronous probe of
        ;; manifest.edn from a page a service worker has just claimed
        _ (when-not boot (h/kernel))]
    (cond
      boot (js/Promise.resolve (boot (assoc opts :start (fn [] (start-isolated! main)))))
      (and (isolated?) (not= forced :sw)) (js/Promise.resolve (start-isolated! main))
      (not (exists? js/navigator.serviceWorker))
      (js/Promise.reject (ex-info "eve.dom.app: not cross-origin isolated, and no service worker here"
                                  {:eve.dom/refused :no-transport}))
      :else
      (-> (.register js/navigator.serviceWorker (str sw (when coi "?coi=1")) #js {:scope "/"})
          (.then (fn [_] (.-ready js/navigator.serviceWorker)))
          (.then (fn [_] (controlled)))
          (.then (fn [_]
                   (if (and coi (not (.getItem js/sessionStorage reload-key)))
                     ;; once: the service worker adds the isolation headers to the next load
                     (do (.setItem js/sessionStorage reload-key "1")
                         (.reload js/location)
                         (js/Promise. (fn [_ _])))
                     (start-sw-worker! main))))))))

(defn run-app!
  "The program's entry point calls this first. In main's worker: run main (true). In another eve.thread-host thread:
   run its entry (true). On the page: start! (false is never answered there: a Promise of how it started)."
  [opts]
  (cond
    (h/run-thread!) true
    (sw-worker-params) (do (run-sw-worker! (sw-worker-params)) true)
    (exists? js/WorkerGlobalScope) false
    :else (start! opts)))
