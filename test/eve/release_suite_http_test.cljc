(ns eve.release-suite-http-test
  "THE RELEASE SUITE, http area — kept in its own namespace so a host whose
   roots carry no http-server adapter fails only this leg, never the core
   suite (eve.release-suite-test). The serve contract is the uniform one the
   adapter campaign proved per target: handler takes {:url :body-b64 ...}
   and answers {:status :headers :body}; a throw answers 500. The client is
   eve.http/request, synchronous on every host's adapter.

   Node-family hosts are the one exception to in-process serving: the
   client's Atomics.wait park starves the in-process server's event loop,
   so for the nbb-http leg the driver serves eve.release-suite-http-echo
   from a separate process and names it via EVE_SUITE_HTTP_BASE (see that
   namespace). Every other host serves in-process here."
  (:require [clojure.test :refer [deftest is]]
            [eve.http :as http]
            [eve.http-server :as hs]
            [eve.release-suite-http-echo :as echo]))

(deftest http-serve-properties
  ;; node-family hosts name a port: their serve! binds asynchronously, so
  ;; with port 0 the returned :port is nil until bound (the adapter's
  ;; :bound promise carries it then) and there would be no URL to call.
  ;; Every adapter answers (:port srv) with the port it was asked for.
  (let [ext #?(:cljs (aget (.-env js/process) "EVE_SUITE_HTTP_BASE") :default nil)
        srv (when-not ext (hs/serve! {:port #?(:cljs 18173 :default 0)} echo/handler))
        base (or ext (str "http://127.0.0.1:" (:port srv)))]
    (try
      (let [r (http/request {:method "GET" :url (str base "/hello")})]
        (is (= 200 (:status r)))
        (is (= "hello from release-suite" (:body r))))
      (let [r (http/request {:method "GET" :url (str base "/boom")})]
        (is (= 500 (:status r)) "a handler throw answers 500"))
      (let [r (http/request {:method "GET" :url (str base "/nope")})]
        (is (= 404 (:status r))))
      (finally
        (when srv ((:stop srv)))
        (println "AREA http OK")
        (flush)))))
