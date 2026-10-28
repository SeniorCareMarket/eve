(ns eve.runner.perf-browser-main
  "eve's performance suite (test/eve/perf_suite.cljc) in a browser: bb test:browser with EVE_BROWSER_PERF=1 builds
   this page (shadow-cljs's :eve-perf-browser) and serves it as it serves the conformance page. The suite runs in a
   worker — where a browser program runs eve's blocking work; the page's main thread cannot block — and its lines are
   POSTed to /result. A build of its own: the suite loads eve.alpha, which readies a module slab in every worker that
   loads the kernel, and the conformance page's many workers need none."
  (:require [eve.thread-host :as h]
            [eve.perf-suite :as perf]))

(defn run-perf
  "A worker's entry: every workload, the lines sent to the page."
  [boot]
  (h/send! (:reply boot) {:op :perf :lines (perf/run! {})}))

(defn- post! [result]
  (js/fetch "/result" #js {:method "POST" :body (pr-str result)}))

(defn init []
  (when-not (h/run-thread!)
    (let [[w] (h/start-threads! 1 'eve.runner.perf-browser-main/run-perf {:reply (h/self)} {})
          timer (atom nil)]
      (reset! timer
              (js/setInterval
               (fn []
                 (let [m (h/receive! 0)]
                   (cond
                     (and (map? m) (= :perf (:op m)))
                     (do (js/clearInterval @timer) (post! m))
                     (and (map? m) (= w (:eve.thread/exit m)))
                     (do (js/clearInterval @timer) (post! {:op :died :notice m})))))
               20)))))
