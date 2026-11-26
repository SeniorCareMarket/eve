(ns eve.http
  "eve's http feature (adapters/README.md) on ValueScript, over the vs.host bridge's fetch (VS's src/vs/host.vs: a
   frame on the program's stdout, the answer on its stdin; served by vs-term --bridge, Node, Workers and the browser).
   `platform` answers what this host lends eve.net (eve/src/eve/net/run.cljc): {:request f :request-all g}, f taking
   {:method :url :headers :body :as :body-as} and answering {:status :headers :body} SYNCHRONOUSLY — vs.host/fetch
   blocks the calling thread until its answer arrives. The body conventions are eve.net.run's, and :b64 is vs.host's
   own: :body-as :b64 sends the bytes its base64 :body names, :body-as :bytes the byte array :body is (sent as base64);
   :as :b64 answers the response body as base64, :as :bytes as a byte array (eve.host's, which eve.codec reads);
   otherwise bodies are UTF-8 text. Header names are answered lower case. A transport failure, or a host that serves
   no fetch, answers {:error message}. request-all keeps up to vs.host/FETCH-ALL-LIMIT requests in flight at once."
  (:require [clojure.string :as str]
            [eve.encoding :as enc]
            [vs.host :as host]))

;; eve.encoding's codec slots are the kernel's base64 rows (one call over the run's bytes; ValueScript's layering U1b —
;; these went through a vector a byte at a time)
(defn- array->b64 "base64 text of a byte array's bytes" [a] (enc/bytes->b64 a))
(defn- b64->array "the byte array of base64 text" [s] (enc/b64->bytes s))

(defn- init
  "vs.host/fetch's init of an eve.net request: a byte-array body goes as base64, a :bytes answer is asked as base64"
  [{:keys [method headers body as body-as]}]
  (cond-> {:method (or method "GET") :headers (or headers {})}
    (some? body) (assoc :body (if (= :bytes body-as) (array->b64 body) body))
    (contains? #{:b64 :bytes} body-as) (assoc :body-as :b64)
    (contains? #{:b64 :bytes} as) (assoc :as :b64)))

(defn- answer
  "eve.net's answer of vs.host's response to req"
  [req resp]
  (if (contains? resp :error)
    {:error (str (:error resp))}
    {:status (:status resp)
     :headers (into {} (map (fn [[k v]] [(str/lower-case (name k)) v]) (:headers resp)))
     :body (if (= :bytes (:as req)) (b64->array (or (:body resp) "")) (:body resp))}))

(defn- failure [e] {:error (or (ex-message e) (str e))})

(defn request
  [req]
  (try (answer req (host/fetch (str (:url req)) (init req)))
       (catch :default e (failure e))))

(defn request-all
  "the requests' answers in their order, up to vs.host/FETCH-ALL-LIMIT in flight at once (vs.host/fetch-all)"
  [reqs]
  (let [reqs (vec reqs)]
    (try (mapv answer reqs (host/fetch-all (mapv (fn [r] [(str (:url r)) (init r)]) reqs)))
         (catch :default e (let [f (failure e)] (mapv (fn [_] f) reqs))))))

(defn platform [] {:request request :request-all request-all})
