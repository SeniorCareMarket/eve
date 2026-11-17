(ns eve.release-suite-http-echo
  "The release suite's http handler, and a standalone echo main that serves
   it out-of-process.

   On node-family hosts (nbb, ClojureScript on Node) the http leg CANNOT
   serve and request in one process: eve.http's client runs the fetch on a
   worker thread and parks the calling thread on Atomics.wait, and the
   node http server needs that same main thread's event loop to accept and
   answer — the park starves the server (measured: a bound, listening
   in-process server never answers during the park). So for the nbb-http
   leg, scripts/release-suite.sh starts THIS main as a separate process
   (the production shape: eve.net gateways are other processes) and the
   test namespace requests it via EVE_SUITE_HTTP_BASE."
  (:require [clojure.string :as str]
            [eve.http-server :as hs]))

(defn handler [req]
  (cond
    (str/starts-with? (:url req) "/hello")
    {:status 200 :headers {"content-type" "text/plain"} :body "hello from release-suite"}

    (str/starts-with? (:url req) "/boom")
    (throw (ex-info "boom" {}))

    :else {:status 404 :headers {} :body "not found"}))

(defn -main [& _]
  (let [srv (hs/serve! {:port 18173} handler)]
    (println "LISTENING" (:port srv))
    (flush)
    ;; On Node an open server keeps the event loop alive by itself; on
    ;; hosts whose main thread would return, park it.
    #?(:cljs nil
       :default @(promise))))
