(ns eve.dom-demo.perf
  "eve.dom's perf cells (the spec's px-* rows), one cell per page load, in the program's main worker. Opt-in: run by
   scripts/dom-perf.sh through bb test:browser (EVE_BROWSER_SUITE=dom-perf); no existing workload changes. The page's
   ?eve-dom-perf=<cell> names the cell; its result goes to /result as {:op :perf :lines [...]}, times in ms.

   px-get-rtt        one property read (document.title), each read timed: p50/p95 of 1,000 after 100 warm-up
   px-call-rtt       document.querySelector('#n500') on a page of 1,000 divs: p50/p95 of 500 after 50 warm-up
   px-batch-100      100 setAttribute writes and the one answering read that sends them: p50 of 50 after 5
   px-event-latency  a click the page dispatches (worker asks el.click()) to the handler's first line, by the
                     page's clock (performance.timeOrigin + event.timeStamp) against the worker's: p50/p95 of 200
   px-frame-d3       10 d3 transitions (1 s) on 10 rects: the worker's frame intervals until all 10 end, p50/p95/max,
                     and how many exceeded 25 ms (a dropped frame at 60 Hz)
   first-render      page timeOrigin to main's start, to a rendered and answered button, and the page's FCP"
  (:require [eve.dom.app :as app]
            [eve.dom.proxy :as proxy]))

(def ^:private d3-files
  ["d3-dispatch-3.0.2/d3-dispatch.min.js" "d3-selection-3.0.1/d3-selection.min.js" "d3-color-3.1.1/d3-color.min.js"
   "d3-interpolate-3.0.2/d3-interpolate.min.js" "d3-ease-3.0.2/d3-ease.min.js" "d3-timer-3.0.2/d3-timer.min.js"
   "d3-transition-3.0.2/d3-transition.min.js"])

(defn- origin [] (.-origin js/location))

(defn- now [] (.now js/performance))

(defn- abs-now [] (+ (.-timeOrigin js/performance) (.now js/performance)))

(defn- pct [xs p]
  (let [v (vec (sort xs))]
    (when (seq v) (nth v (min (dec (count v)) (int (* p (count v))))))))

(defn- r3 [x] (when x (/ (js/Math.round (* 1000 x)) 1000)))

(defn- summary [xs]
  {:n (count xs) :p50 (r3 (pct xs 0.5)) :p95 (r3 (pct xs 0.95)) :max (r3 (apply max xs))
   :mean (r3 (/ (reduce + xs) (count xs)))})

(defn- timed [warm n f]
  (dotimes [_ warm] (f))
  (let [s0 (:sync (proxy/stats))
        xs (vec (for [_ (range n)] (let [t (now)] (f) (- (now) t))))]
    (assoc (summary xs) :sync-per-op (r3 (/ (- (:sync (proxy/stats)) s0) n)))))

(defn- app-el [] (.getElementById js/document "app"))

(defn- get-rtt [] (js/Promise.resolve (timed 100 1000 #(.-title js/document))))

(defn- call-rtt []
  (let [root (app-el)]
    (dotimes [i 1000]
      (let [d (.createElement js/document "div")]
        (set! (.-id d) (str "n" i))
        (.appendChild root d)))
    (js/Promise.resolve (timed 50 500 #(.querySelector js/document "#n500")))))

(defn- batch-100 []
  (let [el (.createElement js/document "div")]
    (.appendChild (app-el) el)
    (js/Promise.resolve
     (timed 5 50 (fn []
                   (dotimes [i 100] (.setAttribute el "data-x" i))
                   (.-id el))))))

(defn- event-latency []
  (let [btn (.createElement js/document "button")
        page0 (.-timeOrigin (proxy/page-global "performance"))
        n 200
        lat (atom [])
        rtt (atom [])
        pending (atom nil)]
    (.appendChild (app-el) btn)
    (.addEventListener btn "click" (fn [e]
                                     (let [t1 (abs-now)
                                           ts (.-timeStamp e)]
                                       (swap! lat conj (- t1 (+ page0 ts)))
                                       (when-let [f @pending] (reset! pending nil) (f t1)))))
    (letfn [(one [i]
              (if (= i n)
                (js/Promise.resolve
                 {:latency (summary (drop 20 @lat)) :worker-click-to-handler (summary (drop 20 @rtt))})
                (-> (js/Promise. (fn [resolve _]
                                   (let [t0 (abs-now)]
                                     (reset! pending (fn [t1] (swap! rtt conj (- t1 t0)) (resolve nil)))
                                     (.click btn))))
                    (.then (fn [_] (one (inc i)))))))]
      (one 0))))

(defn- frame-d3 []
  (doseq [f d3-files] (js/importScripts (str (origin) "/vendor/" f)))
  (let [ns-svg "http://www.w3.org/2000/svg"
        svg (.createElementNS js/document ns-svg "svg")]
    (.setAttribute svg "width" "300") (.setAttribute svg "height" "200")
    (dotimes [i 10]
      (let [r (.createElementNS js/document ns-svg "rect")]
        (.setAttribute r "class" "b") (.setAttribute r "y" (* i 20)) (.setAttribute r "height" 18)
        (.setAttribute r "width" "0")
        (.appendChild svg r)))
    (.appendChild (app-el) svg)
    (.-id svg)
    (js/Promise.
     (fn [resolve _]
       (let [ts (atom [])
             f0 (:frames (proxy/stats))
             ended (atom 0)
             t-give-up (+ (now) 3000)]
         (-> (.selectAll js/d3 "rect.b") (.transition) (.duration 1000) (.attr "width" "300")
             (.on "end" (fn [] (swap! ended inc))))
         (letfn [(tick [_]
                   (swap! ts conj (now))
                   (if (and (< @ended 10) (< (now) t-give-up))
                     (js/requestAnimationFrame tick)
                     (let [xs (map - (rest @ts) @ts)
                           done (every? #(= "300" (.getAttribute % "width"))
                                        (array-seq (.querySelectorAll js/document "rect.b")))]
                       (resolve (assoc (summary (vec xs)) :frames-sent (- (:frames (proxy/stats)) f0)
                                       :over-25ms (count (filter #(> % 25) xs))
                                       :transitions-finished done)))))]
           (js/requestAnimationFrame tick)))))))

(defn- first-render [start]
  (let [perf (proxy/page-global "performance")
        page0 (.-timeOrigin perf)
        btn (.createElement js/document "button")]
    (set! (.-textContent btn) "rendered from a worker")
    (.appendChild (app-el) btn)
    (.-id btn)
    (let [rendered (abs-now)]
      (js/Promise.
       (fn [resolve _]
         (js/setTimeout
          (fn []
            (let [fcp (some-> (.getEntriesByName perf "first-contentful-paint") (aget 0) .-startTime)]
              (resolve {:to-main-start (r3 (- start page0)) :to-rendered (r3 (- rendered page0))
                        :fcp (r3 fcp)})))
          300))))))

(def ^:private cells
  {"px-get-rtt" get-rtt "px-call-rtt" call-rtt "px-batch-100" batch-100 "px-event-latency" event-latency
   "px-frame-d3" frame-d3})

(defn- post!
  "The result to /result. Under the service-worker relay in Chromium the first POST after a long run of synchronous
   XHRs can fail (TypeError: Failed to fetch) while a second one goes through, so up to 5 tries, 200 ms apart; the
   tries it took ride along as :post-tries."
  ([m] (post! m 1))
  ([m tries]
   (let [m' (cond-> m (> tries 1) (update :lines (fn [ls] (mapv #(assoc % :post-tries tries) ls))))]
     (-> (js/fetch (str (origin) "/result") #js {:method "POST" :body (pr-str m')})
         (.catch (fn [e]
                   (if (< tries 5)
                     (js/Promise. (fn [resolve _] (js/setTimeout #(resolve (post! m (inc tries))) 200)))
                     (js/Promise.reject e))))))))

(defn main
  "main, in the program's main worker."
  []
  (let [start (abs-now)
        cell (.get (js/URLSearchParams. (.. js/document -location -search)) "eve-dom-perf")
        run (if (= cell "first-render") #(first-render start) (get cells cell))]
    (if-not run
      (post! {:op :perf :lines [{:cell cell :error "unknown cell"}]})
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (run)))
          (.then (fn [r] (post! {:op :perf :lines [(merge {:cell cell :transport (proxy/transport-kind)
                                                           :page-errors (:page-errors (proxy/stats))}
                                                          r)]})))
          (.catch (fn [e] (post! {:op :perf :lines [{:cell cell :error (str e)}]})))))))

(defn init []
  (app/run-app! {:main 'eve.dom-demo.perf/main}))
