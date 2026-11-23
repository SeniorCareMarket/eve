(ns eve.http-server
  "eve's http-server feature (adapters/README.md) over java.net.ServerSocket — jolt, whose jolt.socket mirrors
   java.net.Socket and ServerSocket and their streams over the host's C sockets (as eve.http's socket client
   relies on). (serve! opts handler) answers {:port :stop}; handler takes {:method :url :headers :body :body-b64}
   — :url the request's path and query as sent (an atom id's %2F kept), :headers lower-case names, :body UTF-8 text
   and :body-b64 the same bytes as base64 (a blob) — and answers {:status :headers :body :body-as}: :body text, or
   with :body-as :b64 the base64 of the bytes to send (eve.net.run's conventions), as the JDK adapter does.
   HTTP/1.1 is read and written here, as in the socket client; one future a connection (a handler may block), as
   the JDK adapter's pool. One request a connection (Connection: close), as the socket client speaks."
  (:require [clojure.string :as str])
  (:import [java.net ServerSocket Socket InetSocketAddress]
           [java.io ByteArrayOutputStream]
           [java.util Base64]))

(def ^:private reasons {200 "OK" 201 "Created" 204 "No Content" 400 "Bad Request"
                        403 "Forbidden" 404 "Not Found" 500 "Internal Server Error"})

(defn- byte-at [a i] (bit-and (aget a i) 0xFF))

(defn- head-end
  "the index just past the CRLF CRLF that ends the head of the bytes read so far, or nil"
  [^ByteArrayOutputStream acc]
  (let [a (.toByteArray acc) n (alength a)]
    (loop [i 0]
      (cond (> (+ i 4) n) nil
            (and (= 13 (byte-at a i)) (= 10 (byte-at a (inc i))) (= 13 (byte-at a (+ i 2))) (= 10 (byte-at a (+ i 3))))
            (+ i 4)
            :else (recur (inc i))))))

(defn- read-request
  "eve's request map of one connection, or nil if the peer sent nothing"
  [^Socket s]
  (let [is (.getInputStream s)
        acc (ByteArrayOutputStream.)]
    (loop []
      (if-let [he (head-end acc)]
        (let [all (.toByteArray acc)
              head (String. all 0 (- he 4) "ISO-8859-1")
              lines (str/split head #"\r\n")
              rl (str/split (first lines) #" ")
              headers (into
                       {}
                       (keep
                        (fn
                          [l]
                          (when-let [i (str/index-of l ":")]
                            [(str/lower-case (str/trim (subs l 0 i))) (str/trim (subs l (inc i)))])))
                       (rest lines))
              need (if-let [cl (get headers "content-length")]
                     (try (Long/parseLong cl) (catch Exception _ 0))
                     0)
              body-acc (ByteArrayOutputStream.)]
          (.write body-acc all (int he) (int (- (alength all) he)))
          (loop [have (- (alength all) he)]
            (when (< have need)
              (let [buf (byte-array (min 65536 (- need have))), n (.read is buf)]
                (when (pos? n) (.write body-acc buf 0 n) (recur (+ have n))))))
          (let [bs (.toByteArray body-acc)
                bs (if (> (alength bs) need) (java.util.Arrays/copyOf bs (int need)) bs)]
            {:method (first rl),
             :url (second rl),
             :headers headers,
             :body (String. bs "UTF-8"),
             :body-b64 (.encodeToString (Base64/getEncoder) bs)}))
        (let [b (.read is)] (when (<= 0 b) (.write acc b) (recur)))))))

(defn- respond [^Socket s {:keys [status headers body body-as]}]
  (let [status (or status 200)
        ^bytes bs (if (= :b64 body-as)
                    (.decode (Base64/getDecoder) ^String (str (or body "")))
                    (.getBytes (str (or body "")) "UTF-8"))
        head (str "HTTP/1.1 " status " " (get reasons status "") "\r\n"
                  (clojure.string/join
                   (keep
                    (fn
                      [[k v]]
                      (let [k (name k)]
                        (when-not (= "content-length" (str/lower-case k)) (str k ": " v "\r\n"))))
                    headers))
                  "Content-Length: " (alength bs) "\r\n"
                  "Connection: close\r\n"
                  "\r\n")
        os (.getOutputStream s)]
    (.write os (.getBytes head "ISO-8859-1"))
    (when (pos? (alength bs)) (.write os bs))
    (.flush os)))

(defn- serve-conn [handler ^Socket s]
  (try
    (when-let [req (read-request s)]
      (respond s (try (handler req)
                      #_{:splint/disable [lint/catch-throwable]}   (catch Throwable t {:status 500 :headers {} :body (str t)}))))
    #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ nil)
    (finally (try (.close s) #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ nil)))))

(defn serve!
  "Serve handler at opts {:port (0: any free one) :host (default 127.0.0.1)}: {:port :stop}. :stop ends the
   listener; a connection already being handled finishes its answer."
  [opts handler]
  (let [ss (ServerSocket.)]
    (.bind ss (InetSocketAddress. ^String (or (:host opts) "127.0.0.1") (int (or (:port opts) 0))))
    (let [stopped (atom false)]
      (future
        (loop []
          (when-not @stopped
            (try
              (let [s (.accept ss)]
                (future (serve-conn handler s)))
              #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ nil))
            (recur))))
      {:port (.getLocalPort ss)
       :stop (fn [] (reset! stopped true) (try (.close ss) #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ nil)))})))
