(ns eve.http
  "eve's http feature (adapters/README.md) over java.net.Socket: HTTP/1.1 written and read here — jolt, whose
   jolt.socket mirrors java.net.Socket and its streams over the host's C sockets (and whose Chez Scheme has no HTTP
   client of its own). `platform` answers what this host lends eve.net (eve/src/eve/net/run.cljc): {:request f
   :request-all g}, f taking {:method :url :headers :body :as :body-as} and answering {:status :headers :body}
   SYNCHRONOUSLY, as on every host. The body conventions are eve.net.run's: :body-as :b64 sends the bytes its base64
   :body names, :body-as :bytes the byte array :body is; :as :b64 answers the response body as base64, :as :bytes as
   a byte array (eve.encoding's, which eve.codec reads); otherwise bodies are UTF-8 text. One connection a request
   (Connection: close); a chunked answer is joined. http:// only — an https:// URL answers {:error …} (jolt's own TLS,
   jolt.mvn-http, reads files for its dependency download, not requests). A transport failure answers {:error
   message}. request-all runs a batch in turn."
  (:require [clojure.string :as str])
  (:import [java.net Socket]
           [java.io ByteArrayOutputStream]
           [java.util Base64]))

(defn- digits->long [s] (reduce (fn [a c] (+ (* a 10) (- (int c) 48))) 0 s))

(defn- parse-url
  "{:host :port :path} of an http:// URL, or nil"
  [url]
  (when-let [[_ host port path] (re-matches #"(?i)http://([^/:?#]+)(?::(\d+))?([/?].*)?" (str url))]
    {:host host :port (if port (digits->long port) 80) :path (cond (nil? path) "/" (str/starts-with? path "?") (str "/" path) :else path)}))

(defn- utf8 [^String s] (.getBytes s "UTF-8"))

(defn- slice
  "the bytes [from, to) of byte array a, a new array"
  [a from to]
  (let [o (ByteArrayOutputStream.)] (.write o a (int from) (int (- to from))) (.toByteArray o)))

(defn- byte-at [a i] (bit-and (aget a i) 0xFF))

(defn- head-end
  "the index just past the CRLF CRLF that ends the head, or nil"
  [a]
  (let [n (alength a)]
    (loop [i 0]
      (cond (> (+ i 4) n) nil
            (and (= 13 (byte-at a i)) (= 10 (byte-at a (+ i 1))) (= 13 (byte-at a (+ i 2))) (= 10 (byte-at a (+ i 3))))
            (+ i 4)
            :else (recur (inc i))))))

(defn- line-end
  "the index of the CRLF at or after i, or nil"
  [a i]
  (let [n (alength a)]
    (loop [i i] (cond (>= (inc i) n) nil (and (= 13 (byte-at a i)) (= 10 (byte-at a (inc i)))) i :else (recur (inc i))))))

(defn- hex->long [s]
  (reduce (fn [acc c] (let [d (Character/digit (char c) 16)] (if (neg? d) (reduced acc) (+ (* acc 16) d)))) 0 (str/trim s)))

(defn- dechunk
  "the body of a chunked transfer: each chunk's bytes joined, its size line and trailers dropped"
  [a start]
  (let [o (ByteArrayOutputStream.)]
    (loop [i start]
      (if-let [le (line-end a i)]
        (let [size (hex->long (String. ^bytes (slice a i le) "ISO-8859-1"))]
          (if (pos? size)
            (do (.write o a (int (+ le 2)) (int size)) (recur (+ le 2 size 2)))
            (.toByteArray o)))
        (.toByteArray o)))))

(defn- read-all
  "every byte the input stream answers until it ends"
  [is]
  (let [buf (byte-array 65536) o (ByteArrayOutputStream.)]
    (loop []
      (let [n (.read is buf)]
        (when (pos? n) (.write o buf 0 n) (recur))))
    (.toByteArray o)))

(defn- answer [raw as]
  (let [he (or (head-end raw) (throw (ex-info "http: no end to the response's head" {})))
        lines (str/split (String. ^bytes (slice raw 0 (- he 4)) "ISO-8859-1") #"\r\n")
        status (digits->long (second (str/split (first lines) #" ")))
        headers (into {} (keep (fn [l] (let [i (str/index-of l ":")]
                                         (when i [(str/lower-case (str/trim (subs l 0 i))) (str/trim (subs l (inc i)))])))
                               (rest lines)))
        body (if (= "chunked" (some-> (get headers "transfer-encoding") str/lower-case))
               (dechunk raw he)
               (let [cl (get headers "content-length")
                     end (if cl (min (alength raw) (+ he (digits->long cl))) (alength raw))]
                 (slice raw he end)))]
    {:status status
     :headers headers
     :body (case as
             :b64 (.encodeToString (Base64/getEncoder) body)
             :bytes body
             (String. ^bytes body "UTF-8"))}))

(defn request
  [{:keys [method url headers body as body-as]}]
  (if-let [{:keys [host port path]} (parse-url url)]
    (try
      (let [payload (cond (nil? body) nil
                          (= :bytes body-as) body
                          (= :b64 body-as) (.decode (Base64/getDecoder) ^String body)
                          :else (utf8 body))
            head (str (or method "GET") " " path " HTTP/1.1\r\n"
                      "Host: " host (when (not= 80 port) (str ":" port)) "\r\n"
                      "Connection: close\r\n"
                      (when (or payload (#{"POST" "PUT" "PATCH"} (or method "GET")))
                        (str "Content-Length: " (if payload (alength payload) 0) "\r\n"))
                      (apply str (keep (fn [[k v]]
                                         (let [k (name k)]
                                           (when-not (#{"host" "connection" "content-length"} (str/lower-case k))
                                             (str k ": " v "\r\n"))))
                                       headers))
                      "\r\n")
            s (Socket. ^String host (int port))]
        (try
          (let [os (.getOutputStream s)]
            (.write os ^bytes (utf8 head))
            (when payload (.write os ^bytes payload))
            (.flush os)
            (answer (read-all (.getInputStream s)) as))
          (finally (.close s))))
      (catch Exception e {:error (or (ex-message e) (str e))}))
    {:error (str "eve.http over java.net.Socket: http:// only, not " url)}))

(defn request-all
  "every request in turn"
  [reqs]
  (mapv request reqs))

(defn platform [] {:request request :request-all request-all})
