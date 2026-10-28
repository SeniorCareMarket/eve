(ns eve.runner.thread-browser-main
  "The thread feature's conformance in a browser (bb test:browser builds this into a page and drives a headless
   Chromium), and eve.thread's battery after it. The page's main thread cannot block, so the suite runs in a worker this page starts — eve.thread-host's
   own, by the fat-kernel route — and the page polls for its summary; the rows only the main thread can show (it
   declares :block :never, polls, and refuses a wait) are checked here. The result is POSTed to /result."
  (:require [cljs.test :as t]
            [eve.thread-host :as h]
            [eve.thread-host-test]
            [eve.thread-test]
            [eve.thread-meta-test]
            [eve.thread-fold-test]))

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
  "A worker's entry: the conformance suite, eve.thread's battery and the meta-atom's state (eve.thread.meta: pure; the
   domain cell itself needs a disk domain, which a browser has not), the summary sent to the page."
  [boot]
  (reset! report-to (:reply boot))
  (t/run-tests 'eve.thread-host-test 'eve.thread-test 'eve.thread-meta-test 'eve.thread-fold-test))

(defn- main-rows []
  (let [[ok? e] (h/attempt (fn [] (h/receive! 10)))]
    {:block-never (= :never (:block (h/driving)))
     :wait-refused (and (false? ok?) (= :cannot-block (:eve.thread/refused (:data (h/error-data e)))))
     :poll-answers (= :eve.thread/timeout (h/receive! 0))}))

(defn- post! [result]
  (js/fetch "/result" #js {:method "POST" :body (pr-str result)}))

(defn init []
  (when-not (h/run-thread!)
    (let [rows (main-rows)
          t0 (js/Date.now)
          [w] (h/start-threads! 1 'eve.runner.thread-browser-main/run-suite {:reply (h/self)} {})
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
               20)))))
