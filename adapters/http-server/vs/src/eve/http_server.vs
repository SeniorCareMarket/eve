(ns eve.http-server
  "eve's http-server feature (adapters/README.md) on ValueScript, over the vs.host bridge's HTTP in (VS's
   src/vs/host.vs next-request / respond!, looped by vs.http.server/serve): the host owns the listener — Node's
   vs-serve-node.mjs (--port N), a Cloudflare Worker — and hands the program each request it took
   as an `http` event; the answer goes back as the host's `respond` op.

   (serve! opts handler) serves until the host closes its end of the bridge, then answers {:port nil :stop f}: a VS
   program is one thread on the bridge, so it serves in the calling thread rather than beside it, and the port is the
   host's (opts :port and :host are the host's flags, not this call's). handler takes eve's request map {:method :url
   :headers :body :body-b64} — :url the path and query as sent, :headers lower-case names, :body UTF-8 text (\"\" when
   the bytes are not UTF-8 text, as the host delivers them) and :body-b64 the bytes as base64 — and answers {:status
   :headers :body :body-as}: :body text, or with :body-as :b64 the base64 of the bytes to send (eve.net.run's
   conventions). A handler that throws answers 500."
  (:require [clojure.string :as str]
            [eve.net.bytes :as nb]
            [vs.http.server :as server]))

(defn- request
  "eve's request map of vs.host's (a Ring map; :body-b64 only when the bytes are not UTF-8 text, :body then \"\")"
  [req]
  (let [q (:query-string req)
        b64 (:body-b64 req)]
    {:method (str/upper-case (name (:request-method req)))
     :url (if q (str (:uri req) "?" q) (:uri req))
     :headers (into {} (map (fn [[k v]] [(str/lower-case (name k)) v]) (:headers req)))
     :body (if b64 "" (or (:body req) ""))
     :body-b64 (or b64 (nb/to-b64 (nb/utf8 (or (:body req) ""))))}))

(defn- response
  "vs.host's response map of eve's"
  [{:keys [status headers body body-as]}]
  (let [hs (into {} (map (fn [[k v]] [(name k) (str v)]) headers))]
    (if (= :b64 body-as)
      {:status (or status 200) :headers hs :body "" :body-b64 (str (or body ""))}
      {:status (or status 200) :headers hs :body (str (or body ""))})))

(defn serve!
  "Serve handler on the host's listener until the host closes: {:port nil :stop (fn [] nil)}."
  [opts handler]
  (server/serve (fn [req] (response (handler (request req)))))
  {:port nil :stop (fn [] nil)})
