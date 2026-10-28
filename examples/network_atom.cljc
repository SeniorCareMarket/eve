;; examples/network_atom.cljc — a network atom, in one process and with no sockets: a gateway serves a disk domain's
;; atom on eve.net's broker routes, a client opens that atom as a network atom, and a disk writer and the network
;; writer update it together. Blobs go to an object store through presigned URLs, never through the gateway.
;;
;;   bb -cp "$(bb classpath bb)" examples/network_atom.cljc
;;   clojure -M examples/network_atom.cljc
;;
;; In production the client's :request is the host's http adapter (eve.http) and the gateway runs behind
;; eve.http-server/serve!, or the client talks to a broker; the object store is S3-compatible. Here the client's
;; requests are routed by URL to the gateway's handler and to a small store defined below.
(ns network-atom
  (:require [clojure.string :as str]
            [eve.atom :as ea]
            [eve.net.atom :as na]
            [eve.net.bytes :as b]
            [eve.net.gateway :as gw]
            [eve.process :as proc]))

(defn check [label expected actual]
  (println label "=>" (pr-str actual))
  (when-not (= expected actual)
    (throw (ex-info (str label ": expected " (pr-str expected)) {:actual actual}))))

;; --- an object store behind presigned URLs (the part S3 or Tigris plays): PUT stores, GET answers, HEAD asks ---------
(def store-url "http://store.example/blobs/")
(def store-secret "store-secret")
(def blobs (atom {}))

(defn- query-param [q k]
  (some (fn [kv] (let [[pk v] (str/split kv #"=" 2)] (when (= pk k) v))) (str/split q #"&")))

(defn store-request [{:keys [method url body]}]
  (let [[k q] (str/split (subs url (count store-url)) #"\?" 2)
        mode (query-param q "mode")
        exp (some-> (query-param q "exp") parse-long)
        ok? (and (= mode ({"PUT" "write" "GET" "read" "HEAD" "head"} method))
                 (gw/hmac-ok? store-secret k mode exp (query-param q "sig") (quot (proc/now-ms) 1000)))]
    (cond
      (not ok?) {:status 403 :headers {} :body ""}
      (= "PUT" method) (do (swap! blobs assoc k (b/from-b64 body)) {:status 200 :headers {} :body ""})
      (= "HEAD" method) {:status (if (contains? @blobs k) 200 404) :headers {} :body ""}
      :else (if-let [bs (get @blobs k)]
              {:status 200 :headers {} :body (b/to-b64 bs)}
              {:status 404 :headers {} :body ""}))))

;; --- a disk domain, and a gateway serving it ---------------------------------------------------------------------
(def base (str "/tmp/eve-network-atom-example-" (proc/now-ms) "/db"))
(def domain (ea/persistent-atom-domain base))
(def counter (ea/lookup-or-create-mmap-atom! domain "app/counter" {:n 0}))

(def gateway
  (gw/gateway domain {:endpoint "http://gateway.example" :domain-id "d1" :api-key "key-1"
                      :presign (gw/hmac-presigner {:url store-url :secret store-secret :now-ms proc/now-ms})
                      :platform {:request store-request}}))

;; --- the client: a network atom over the gateway ----------------------------------------------------------------
(def platform
  {:request (fn [req] (if (str/starts-with? (:url req) "http://gateway.example")
                        ((:handle gateway) req)
                        (store-request req)))
   :sleep proc/sleep-ms! :now-ms proc/now-ms :seed 1})

(def ra (na/remote-atom {:endpoint "http://gateway.example" :domain-id "d1"
                         :atom-id (gw/atom-id "app/counter") :api-key "key-1"}
                        platform))

(check "network deref" {:n 0} (na/deref ra))
(check "network swap!" {:n 1} (na/swap! ra update :n inc))   ; synchronous: answers the new value
(swap! counter update :n inc)                                   ; a disk writer of the same atom
(check "network deref after a disk write" {:n 2} (na/deref ra))
(check "disk deref" {:n 2} @counter)
(check "blobs in the store" true (pos? (count @blobs)))
(na/release! ra)
(ea/close-atom-domain! domain)
(println "network_atom: ok")
