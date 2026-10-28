(ns eve-media.serve
  "The browser's way into the domain — the network rung (P§8.3): a gateway (eve.net.gateway) serving the domain's
   atoms on eve.net's broker routes, and the object store its blobs go to, presigned (the
   gateway relays small JSON only; blocks go to the store). Served by a member that has the http-server feature
   (eve.http-server: the JVM's), on the launcher's port; the Go member's media server proxies /gw/ and /blobs/ to it,
   so the page reaches both on its own origin.

   Threat model (rule 9): the writer is whoever holds the run's api key — the launcher mints one per run and gives it
   to the page it serves; both servers listen on 127.0.0.1 only. A network write lands by a compare of content keys
   (atomic against every disk writer); the gateway refuses an upgrade's hello over the network
   (eve.thread.net/hello-over-disk-only). The store checks each request's HMAC presignature; it holds content-addressed
   blocks in this process's memory and deletes none (a restarted server starts empty: a client re-reads, the gateway
   re-uploads what it serves)."
  (:require [clojure.string :as str]
            [eve.http-server :as hs]
            [eve.net.bytes :as b]
            [eve.net.gateway :as gw]
            [eve.process :as proc]
            [eve.thread.net :as tn]))

(defn- query-param [q k]
  (some (fn [kv] (let [[pk v] (str/split kv #"=" 2)] (when (= pk k) v))) (str/split (or q "") #"&")))

(defn store-handler
  "The object store: PUT stores a block, GET answers it, HEAD asks for it — each under a valid presignature. Takes a
   request in-process (eve.net.run's: a base64 :body) or from eve.http-server (:body-b64, the bytes as sent)."
  [blobs secret]
  (fn [{:keys [method url body body-b64]}]
    (let [i (str/index-of url "/blobs/")
          [k q] (str/split (subs url (+ i 7)) #"\?" 2)
          mode (query-param q "mode")
          exp (some-> (query-param q "exp") parse-long)
          ok? (and (= mode ({"PUT" "write" "GET" "read" "HEAD" "head"} method))
                   (gw/hmac-ok? secret k mode exp (query-param q "sig") (quot (proc/now-ms) 1000)))]
      (cond
        (not ok?) {:status 403 :headers {} :body ""}
        (= "PUT" method) (do (swap! blobs assoc k (b/from-b64 (or body-b64 body))) {:status 200 :headers {} :body ""})
        (= "HEAD" method) {:status (if (contains? @blobs k) 200 404) :headers {} :body ""}
        :else (if-let [bs (get @blobs k)]
                {:status 200 :headers {"content-type" "application/octet-stream"} :body (b/to-b64 bs) :body-as :b64}
                {:status 404 :headers {} :body ""})))))

(defn start-gateway!
  "Serve domain's atoms and the store on 127.0.0.1:port. origin: the media server's (the page's) origin, which the
   clients reach the gateway (origin/gw) and the store (origin/blobs/) through. {:port :stop :gateway}."
  [domain {:keys [port origin domain-id api-key store-secret]}]
  (let [store-url (str origin "/blobs/")
        blobs (atom {})
        store (store-handler blobs store-secret)
        g (gw/gateway domain {:endpoint (str origin "/gw") :domain-id domain-id :api-key api-key
                              :presign (gw/hmac-presigner {:url store-url :secret store-secret :now-ms proc/now-ms})
                              :platform {:request store}
                              :deny tn/hello-over-disk-only})
        srv (hs/serve! {:port port :threads 8}
                       (fn [req]
                         (cond (str/includes? (:url req) "/blobs/") (store req)
                               (str/includes? (:url req) "/v1/domains/") ((:handle g) req)
                               :else {:status 404 :headers {} :body "not found"})))]
    (assoc srv :gateway g :blobs blobs)))
