(ns cohost-media.gateway
  "The gateway: cohost-media.serve's gateway and store over the run's disk domain, on the boot
   program's ports (the control atom's :config). The boot program starts it in its own process
   (start!) — no member's death takes the network rung down, and its request threads serve the
   network members — the browser's workers — in parallel (each request materializes the value it
   serves: on one event loop they queued behind each other, and the members' leases lapsed). It
   serves atoms; it is no runtime of the job atom and runs no jobs. -main remains for running it
   standalone:

     clojure -m cohost-media.gateway <base>"
  (:require [clojure.string :as str]
            [cohost.pool :as pool]
            [cohost-media.domain :as domain]
            [cohost-media.serve :as serve]))

(defn start!
  "Start the gateway over atoms' domain and note it in the control atom. Answers serve's server map
   ({:port :stop :gateway :blobs :handler})."
  [{:keys [control domain] :as atoms}]
  (let [cfg (:config @control)
        srv (serve/start-gateway! domain (assoc (select-keys cfg [:domain-id :api-key :store-secret])
                                                :port (:gateway-port cfg)
                                                :origin (str "http://127.0.0.1:" (:gateway-port cfg))))]
    (swap! control assoc-in [:servers :gateway] {:port (:port srv) :process "boot" :t (pool/now-ms)})
    (println (pr-str {:step :gateway :port (:port srv)}))
    srv))

(defn seat-handler
  "The gateway's endpoints as a cohost browser-seat :handler, over the STARTED gateway srv —
   serve's own dispatch (srv's :handler), so the page and the members share the one gateway and
   the one store: the /gw/ wire and the /blobs/ store answered in-process, no HTTP hop, and nil
   for every other path (the seat falls through to its statics and the caller's own handlers).
   The translation is the http server's request convention on both sides: the seat's req
   (:method a keyword, :uri, :query a map, :body text, :body-b64) becomes serve's req (:method
   a string, :url, :body, :body-b64), and serve's answer crosses unchanged — blob traffic is
   already {:body <base64> :body-as :b64}, which the seat puts on the wire as bytes. Presigned
   /blobs/ URLs in a /presign answer are re-based onto the page's own origin (the request's Host
   header) — the trick the retired page-server proxy did, minus the hop."
  [srv]
  (let [handler (:handler srv)]
    (fn [req]
      (let [path (:uri req)]
        (when (or (str/starts-with? path "/gw/") (str/starts-with? path "/blobs/"))
          (let [url (if (seq (:query req))
                      (str path "?" (str/join "&" (map (fn [[k v]] (str k "=" v)) (:query req))))
                      path)
                resp (handler {:method (str/upper-case (name (:method req)))
                               :url url
                               :headers (:headers req)
                               :body (:body req)
                               :body-b64 (:body-b64 req)})]
            (if (and resp (str/includes? path "/presign") (string? (:body resp)))
              (update resp :body str/replace #"http://127\.0\.0\.1:\d+/blobs/"
                      (str "http://" (get (:headers req) "host") "/blobs/"))
              resp)))))))

(defn -main [base]
  (let [atoms (domain/open-atoms base)
        srv (start! atoms)
        control (:control atoms)]
    (loop []
      (if (:stop @control)
        (do ((:stop srv)) (println (pr-str {:step :stopped}))
            #?(:clj (do (shutdown-agents) (System/exit 0))
               :cljs (js/process.exit 0)))
        (do (pool/sleep-ms! 500) (recur))))))
