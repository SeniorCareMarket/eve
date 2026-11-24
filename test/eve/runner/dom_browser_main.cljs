(ns eve.runner.dom-browser-main
  "eve.dom's conformance in a browser (bb test:browser:dom builds this into a page and drives a headless browser):
   the program's main is a worker (eve.dom.app), the suite runs there against the page's DOM, and the worker POSTs
   the result to /result."
  (:require [cljs.test :as t]
            [eve.dom.app :as app]
            [eve.dom.proxy :as proxy]
            [eve.dom-test]))

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

(defn- result-url
  "/result on the page's origin (main's worker runs from a blob: URL, against which a relative URL does not resolve)."
  []
  (str (.-origin js/location) "/result"))

(defmethod t/report [::t/default :end-run-tests] [m]
  (js/fetch (result-url)
            #js {:method "POST"
                 :body (pr-str {:op :suite :summary (select-keys m [:test :pass :fail :error]) :failures @failures
                                :main {:transport (some? (proxy/transport-kind))}
                                :transport (proxy/transport-kind) :stats (proxy/stats)})}))

(defn suite
  "main, in the program's main worker."
  []
  (t/run-tests 'eve.dom-test))

(defn init []
  (let [r (app/run-app! {:main 'eve.runner.dom-browser-main/suite})]
    (when (instance? js/Promise r)
      (.catch r (fn [e]
                  (js/fetch "/result" #js {:method "POST"
                                           :body (pr-str {:op :died :error (str e) :data (pr-str (ex-data e))})}))))))
