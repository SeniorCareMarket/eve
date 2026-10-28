(ns eve.http-server
  "eve's http-server feature (adapters/README.md) over http-kit — babashka's: what a member needs to serve its disk
   domain's atoms on eve.net's routes (eve.net.gateway). (serve! opts handler) answers {:port :stop}; handler
   takes {:method :url :headers :body} — :url the request's path and query as sent (an atom id's %2F kept), :headers
   lower-case names, :body UTF-8 text — and answers {:status :headers :body} (text). http-kit runs a handler on its
   worker pool (:threads, default 8): a handler may block. Bodies as the JDK adapter's: the request's also as :body-b64,
   a response's :body-as :b64 sends the bytes its base64 :body names."
  (:require [clojure.string :as str]
            [org.httpkit.server :as hk])
  (:import [java.util Base64]))

(defn serve!
  "Serve handler at opts {:port (0: any free one) :host (default 127.0.0.1) :threads}: {:port :stop}."
  [opts handler]
  (let [srv (hk/run-server
             (fn [req]
               (let [^bytes raw (if-let [b (:body req)] (.readAllBytes ^java.io.InputStream b) (byte-array 0))
                     r (try (handler {:method (str/upper-case (name (:request-method req)))
                                      :url (str (:uri req) (when-let [q (:query-string req)] (str "?" q)))
                                      :headers (:headers req)
                                      :body (String. raw "UTF-8")
                                      :body-b64 (.encodeToString (Base64/getEncoder) raw)})
                            (catch Throwable t {:status 500 :headers {} :body (str t)}))]
                 {:status (or (:status r) 200) :headers (or (:headers r) {})
                  :body (if (= :b64 (:body-as r))
                          (java.io.ByteArrayInputStream. (.decode (Base64/getDecoder) ^String (str (or (:body r) ""))))
                          (str (or (:body r) "")))}))
             {:port (or (:port opts) 0) :ip (or (:host opts) "127.0.0.1") :thread (or (:threads opts) 8)
              :legacy-return-value? false})]
    {:port (hk/server-port srv) :stop (fn [] (hk/server-stop! srv))}))
