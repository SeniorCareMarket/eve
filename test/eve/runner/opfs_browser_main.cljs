(ns eve.runner.opfs-browser-main
  "eve's OPFS disk tier in a browser (EVE_BROWSER_OPFS=1 bb test:browser builds this into a page): the page mounts
   the OPFS pool (eve.opfs/mount!) before its threads start, then runs the suite in a worker, as the thread
   conformance page does; the result is POSTed to /result (a mount that fails is the result)."
  (:require [cljs.test :as t]
            [eve.opfs :as opfs]
            [eve.thread-host :as h]
            [eve.opfs-browser-test]
            [eve.region-opfs-test]))

(def ^:private report-to (atom nil))
(def ^:private failures (atom []))

(defn- note! [m]
  (swap! failures conj (pr-str (select-keys m [:type :message :expected :actual :testing-contexts]))))

(defmethod t/report [::t/default :fail] [m]
  (t/inc-report-counter! :fail)
  (note! (assoc m :testing-contexts (t/testing-contexts-str)))
  (println "FAIL" (pr-str (select-keys m [:message :expected :actual]))))

(defmethod t/report [::t/default :error] [m]
  (t/inc-report-counter! :error)
  (note! (assoc m :actual (str (:actual m)) :testing-contexts (t/testing-contexts-str)))
  (println "ERROR" (str (:actual m))))

(defmethod t/report [::t/default :end-run-tests] [m]
  (when-let [r @report-to]
    (h/send! r {:op :suite :summary (select-keys m [:test :pass :fail :error]) :failures @failures})))

(defn run-suite
  "A worker's entry: the OPFS suites, the summary sent to the page."
  [boot]
  (reset! report-to (:reply boot))
  (t/run-tests 'eve.region-opfs-test 'eve.opfs-browser-test))

(defn- main-rows []
  (let [[ok? e] (h/attempt (fn [] (h/receive! 10)))]
    {:block-never (= :never (:block (h/driving)))
     :wait-refused (and (false? ok?) (= :cannot-block (:eve.thread/refused (:data (h/error-data e)))))
     :poll-answers (= :eve.thread/timeout (h/receive! 0))}))

(defn- post! [result]
  (js/fetch "/result" #js {:method "POST" :body (pr-str result)}))

(defn- start! [rows t0]
  (let [[w] (h/start-threads! 1 'eve.runner.opfs-browser-main/run-suite {:reply (h/self)} {})
        timer (atom nil)]
    (reset! timer
            (js/setInterval
             (fn []
               (let [m (h/receive! 0)]
                 (cond
                   (and (map? m) (= :suite (:op m)))
                   (do (js/clearInterval @timer)
                       (post! (assoc m :main rows :ms (- (js/Date.now) t0) :driving (h/driving))))
                   (and (map? m) (= w (:eve.thread/exit m)))
                   (do (js/clearInterval @timer)
                       (post! {:op :died :notice m :main rows})))))
             20))))

(defn init []
  (when-not (h/run-thread!)
    (let [rows (main-rows)
          t0 (js/Date.now)]
      (-> (opfs/mount! {:root "eve-opfs-test"})
          (.then (fn [_] (start! (assoc rows :mounted true) t0))
                 (fn [e] (post! {:op :mount-failed :message (str e) :main (assoc rows :mounted false)})))))))
