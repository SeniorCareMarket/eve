(ns eve.http-server
  "eve's http-server feature (adapters/README.md) over Node's http module — ClojureScript on Node: what a member needs to
   serve its disk domain's atoms on eve.net's routes (eve.net.gateway). (serve! opts handler) answers
   {:port :stop}; handler takes {:method :url :headers :body :body-b64} — :url the request's path and query as sent (an
   atom id's %2F kept), :headers lower-case names, :body UTF-8 text and :body-b64 the same bytes as base64 (a blob) —
   and answers {:status :headers :body :body-as}: :body text, or with :body-as :b64 the base64 of the bytes to send
   (eve.net.run's conventions), as the JDK and http-kit adapters do.

   Node's server runs the handler on its event loop when a request's body has arrived; the handler is synchronous (a
   gateway's swap is an eve swap). Node binds asynchronously: :port is the port asked for (0 asks for any free one, and
   :port is then nil until it is bound — read it from (:bound srv), a JS promise of the port)."
  (:require ["http" :as http]))

(defn- headers-map [^js h]
  (into {} (map (fn [k] [(.toLowerCase k) (str (aget h k))])) (js/Object.keys h)))

(defn serve!
  "Serve handler at opts {:port (0: any free one) :host (default 127.0.0.1)}: {:port :stop :bound}."
  [opts handler]
  (let [port (or (:port opts) 0)
        srv (.createServer
             http
             (fn [^js req ^js res]
               (let [chunks #js []]
                 (.on req "data" (fn [c] (.push chunks c)))
                 (.on req "end"
                      (fn []
                        (let [raw (js/Buffer.concat chunks)
                              r (try (handler {:method (.-method req)
                                               :url (.-url req)
                                               :headers (headers-map (.-headers req))
                                               :body (.toString raw "utf8")
                                               :body-b64 (.toString raw "base64")})
                                     (catch :default e {:status 500 :headers {} :body (str e)}))
                              body (if (= :b64 (:body-as r))
                                     (js/Buffer.from (str (or (:body r) "")) "base64")
                                     (js/Buffer.from (str (or (:body r) "")) "utf8"))]
                          (doseq [[k v] (:headers r)] (.setHeader res (name k) (str v)))
                          (.setHeader res "content-length" (.-length body))
                          (.writeHead res (or (:status r) 200))
                          (.end res body)))))))
        bound (js/Promise. (fn [ok _] (.on srv "listening" (fn [] (ok (.-port (.address srv)))))))]
    ;; Nagle's algorithm off, as the JDK adapter does: a small response would wait on the client's delayed ACK
    (.on srv "connection" (fn [^js sock] (.setNoDelay sock true)))
    (.listen srv port (or (:host opts) "127.0.0.1"))
    {:port (when (pos? port) port) :bound bound :stop (fn [] (.close srv))}))
