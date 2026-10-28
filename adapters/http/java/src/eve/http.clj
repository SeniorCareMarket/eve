(ns eve.http
  "eve's http feature (adapters/README.md) over java.net.http — the JVM and babashka. `platform` answers what this host
   lends eve.net (eve/src/eve/net/run.cljc, the sans-IO driver): {:request f}, f taking {:method :url :headers :body
   :as :body-as} and answering {:status :headers :body} SYNCHRONOUSLY. The body conventions are eve.net.run's: a
   request with :body-as :b64 sends the bytes its base64 :body names, :body-as :bytes the byte array :body is; :as :b64
   answers the response body as base64 (a blob's bytes), :as :bytes as a byte array (eve.encoding's, which eve.codec
   reads); otherwise bodies are UTF-8 text. A transport failure answers {:error message}, as eve.net.wire
   reads it. `request-all` sends a batch at once (sendAsync) and blocks until every answer is in."
  (:require [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$Builder HttpRequest$BodyPublishers HttpResponse
            HttpResponse$BodyHandlers]
           [java.util Base64]))

(def ^:private client (delay (HttpClient/newHttpClient)))

;; headers java.net.http sets itself and refuses from a caller
(def ^:private RESTRICTED #{"host" "content-length" "connection" "expect" "upgrade"})

(defn- build [{:keys [method url headers body body-as]}]
  (let [bytes (cond (nil? body) nil
                    (= :b64 body-as) (.decode (Base64/getDecoder) ^String body)
                    (= :bytes body-as) body
                    :else (.getBytes ^String body "UTF-8"))
        pub (if bytes (HttpRequest$BodyPublishers/ofByteArray bytes) (HttpRequest$BodyPublishers/noBody))]
    (.build ^HttpRequest$Builder
            (reduce (fn [^HttpRequest$Builder b [k v]] (if (RESTRICTED (str/lower-case (name k))) b (.header b (name k) (str v))))
                    (.method (HttpRequest/newBuilder (URI/create url)) (or method "GET") pub)
                    headers))))

(defn- answer [^HttpResponse resp as]
  (let [raw ^bytes (.body resp)]
    {:status (.statusCode resp)
     :headers (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)]) (.map (.headers resp))))
     :body (case as :b64 (.encodeToString (Base64/getEncoder) raw) :bytes raw (String. raw "UTF-8"))}))

(defn- failure [e] {:error (or (ex-message (or (ex-cause e) e)) (str e))})

(defn request
  [req]
  (try
    (answer (.send ^HttpClient @client (build req) (HttpResponse$BodyHandlers/ofByteArray)) (:as req))
    (catch Exception e (failure e))))

(defn request-all
  "every request sent at once (sendAsync), the answers joined in their order — the caller blocks until all are in"
  [reqs]
  (let [futures (mapv (fn [req]
                        (try (.sendAsync ^HttpClient @client (build req) (HttpResponse$BodyHandlers/ofByteArray))
                             (catch Exception e e)))
                      reqs)]
    (mapv (fn [req f]
            (if (instance? Throwable f)
              (failure f)
              (try (answer (.join ^java.util.concurrent.CompletableFuture f) (:as req))
                   (catch Exception e (failure e)))))
          reqs futures)))

(defn platform [] {:request request :request-all request-all})
