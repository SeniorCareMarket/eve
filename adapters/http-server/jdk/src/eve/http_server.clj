(ns eve.http-server
  "eve's http-server feature (adapters/README.md) over the JDK's com.sun.net.httpserver — the JVM: what a member needs to
   serve its disk domain's atoms on eve.net's routes (eve.net.gateway). (serve! opts handler) answers
   {:port :stop}; handler takes {:method :url :headers :body :body-b64} — :url the request's path and query as sent (an
   atom id's %2F kept), :headers lower-case names, :body UTF-8 text and :body-b64 the same bytes as base64 (a blob) —
   and answers {:status :headers :body :body-as}: :body text, or with :body-as :b64 the base64 of the bytes to send
   (eve.net.run's conventions). Requests are handled on a pool of :threads threads (default 8): a handler may block."
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util Base64]
           [java.util.concurrent Executors]))

(defn- handle [handler ^HttpExchange ex]
  (try
    (let [raw (.readAllBytes (.getRequestBody ex))
          req {:method (.getRequestMethod ex)
               :url (str (.getRequestURI ex))
               :headers (into {} (map (fn [[k vs]] [(.toLowerCase ^String k) (first vs)])) (.getRequestHeaders ex))
               :body (String. raw "UTF-8")
               :body-b64 (.encodeToString (Base64/getEncoder) raw)}
          {:keys [status headers body body-as]} (try (handler req)
                                                     (catch Throwable t {:status 500 :headers {} :body (str t)}))
          ^bytes bs (if (= :b64 body-as)
                      (.decode (Base64/getDecoder) ^String (str (or body "")))
                      (.getBytes (str (or body "")) "UTF-8"))]
      (doseq [[k v] headers] (.add (.getResponseHeaders ex) (str k) (str v)))
      (.sendResponseHeaders ex (int (or status 200)) (if (zero? (alength bs)) -1 (alength bs)))
      (when (pos? (alength bs)) (with-open [o (.getResponseBody ex)] (.write o bs))))
    (finally (.close ex))))

(defn serve!
  "Serve handler at opts {:port (0: any free one) :host (default 127.0.0.1) :threads}: {:port :stop}. :stop ends the
   server and its request threads."
  [opts handler]
  ;; Nagle's algorithm off (TCP_NODELAY), unless the deployer set it: the JDK's server leaves it on, and a small
  ;; response then waits on the client's delayed ACK — a network swap through a gateway, 264 ms with it on and 73 ms
  ;; off (measured, ET-3). The server's configuration reads the property once, when its first server is made.
  (when-not (System/getProperty "sun.net.httpserver.nodelay")
    (System/setProperty "sun.net.httpserver.nodelay" "true"))
  (let [srv (HttpServer/create (InetSocketAddress. ^String (or (:host opts)
                                                               "127.0.0.1")
                                                   (int (or (:port opts) 0))) 0)
        pool (Executors/newFixedThreadPool (int (or (:threads opts) 8)))]
    (.createContext srv "/"
                    (reify HttpHandler
                      (handle [_ ex] (handle handler ex))))
    (.setExecutor srv pool)
    (.start srv)
    ;; (HttpServer.stop leaves an executor it was given running: its threads are not daemons, so a process that
    ;; stopped its server would not exit)
    {:port (.getPort (.getAddress srv))
     :stop (fn [] (.stop srv 0) (.shutdown pool))}))
