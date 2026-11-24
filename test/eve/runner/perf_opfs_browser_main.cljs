(ns eve.runner.perf-opfs-browser-main
  "The OPFS disk tier's opt-in perf cells (the spec's W12): EVE_BROWSER_PERF=opfs bb test:browser builds this page
   (:eve-perf-opfs-browser). The page boots as a program does (eve.opfs.boot/boot!: isolated, the pool mounted),
   then a worker runs eve's performance suite's disk cell (eve.perf-suite, :disk-swap-counter — the cell as the
   suite defines it), its persistent atom's files on OPFS; each line is marked :store :opfs and the io worker's
   handle mode. The suite's own page (EVE_BROWSER_PERF=1) is unchanged: it loads no OPFS code."
  (:require [eve.opfs :as opfs]
            [eve.opfs.boot :as boot]
            [eve.perf-suite :as perf]
            [eve.thread-host :as h]))

(def cells
  "The cells this page runs."
  #{:disk-swap-counter})

(defn run-perf
  "A worker's entry: the OPFS cells, the lines sent to the page."
  [boot]
  (h/send! (:reply boot) {:op :perf
                          :lines (mapv #(assoc % :store :opfs :opfs-mode (opfs/handle-mode))
                                       (perf/run! {:only cells :dir "/perf-suite"}))}))

(defn- post! [result]
  (js/fetch "/result" #js {:method "POST" :body (pr-str result)}))

(defn init []
  (when-not (h/run-thread!)
    (-> (boot/boot! {:opfs {:root "eve-perf-opfs"}})
        (.then (fn [_]
                 (let [[w] (h/start-threads! 1 'eve.runner.perf-opfs-browser-main/run-perf {:reply (h/self)} {})
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
        (.catch (fn [e] (post! {:op :died :notice (str e)}))))))
