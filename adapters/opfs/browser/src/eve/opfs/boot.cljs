(ns eve.opfs.boot
  "A browser program's boot with the OPFS tier (the spec's W9a; /workspace/eve-perf/specs/eve-sw-and-boot-contract.md):
   the page made cross-origin isolated, then the pool mounted, then the program's main started — pools, mount, main.

   Isolation: a page served without COOP/COEP registers eve's one service worker (eve-sw.js at the site root, ?coi=1:
   it adds the isolation headers to what it serves) and reloads once; the reload's guard lives in sessionStorage, so
   a browser that still will not isolate the page is never reloaded again. eve.dom.app/start! calls boot! (its :boot
   option) when the OPFS tier is in the program."
  (:require [eve.opfs.mount :as mount]))

(def ^:private reload-key "eve.opfs/coi-reload")

(defn isolated?
  "true when this page is cross-origin isolated (SharedArrayBuffer between its workers)."
  []
  (and (exists? js/crossOriginIsolated) (true? js/crossOriginIsolated)))

(defn- controlled []
  (let [sw (.-serviceWorker js/navigator)]
    (if (.-controller sw)
      (js/Promise.resolve true)
      (js/Promise. (fn [resolve _]
                     (.addEventListener sw "controllerchange" (fn [_] (resolve true)) #js {:once true}))))))

(defn isolate!
  "On the page: a promise of true when the page is isolated, false when it cannot be (no service worker, or the
   one reload spent). Not isolated, it registers the service worker (:sw, default /eve-sw.js) with ?coi=1 and, the
   first time, reloads the page (the promise then never settles: the page goes)."
  ([] (isolate! {}))
  ([{:keys [sw] :or {sw "/eve-sw.js"}}]
   (cond
     (isolated?) (do (.removeItem js/sessionStorage reload-key) (js/Promise.resolve true))
     (not (exists? (.-serviceWorker js/navigator))) (js/Promise.resolve false)
     :else
     (-> (.register (.-serviceWorker js/navigator) (str sw "?coi=1") #js {:scope "/"})
         (.then (fn [_] (.-ready (.-serviceWorker js/navigator))))
         (.then (fn [_] (controlled)))
         (.then (fn [_]
                  (if (.getItem js/sessionStorage reload-key)
                    false
                    (do (.setItem js/sessionStorage reload-key "1")
                        (.reload js/location)
                        (js/Promise. (fn [_ _]))))))
         (.catch (fn [_] false))))))

(defn boot!
  "On the page: isolate it (isolate!), mount the OPFS pool (:opfs, eve.opfs.mount/mount!'s options), then call
   (:start opts) — the program's main started, its worker sharing the pool. A promise of {:pool p :started x};
   rejected (ex-data :eve.opfs/refused :not-isolated) where the page cannot be isolated (the service worker's
   synchronous transport is the OPFS spec's W9/W10)."
  [{:keys [opfs start] :as opts}]
  (-> (isolate! opts)
      (.then (fn [iso?]
               (if-not iso?
                 (js/Promise.reject (ex-info "eve.opfs.boot: the page is not cross-origin isolated"
                                             {:eve.opfs/refused :not-isolated}))
                 (-> (mount/mount! (or opfs {}))
                     (.then (fn [p] {:pool p :started (when start (start))}))))))))
