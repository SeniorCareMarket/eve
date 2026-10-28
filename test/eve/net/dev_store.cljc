(ns eve.net.dev-store
  "An object store for the gateway's tests (lane ET-3): blobs behind presigned URLs, each request's signature checked
   (eve.net.gateway/hmac-ok?: the presigner and the store share the secret; the client holds only the URL) — the part
   Tigris plays in production, which the gateway never plays itself (repo CLAUDE.md rule 4).

     (def s (dev-store {:url \"http://store.test/blobs/\" :secret \"k\" :now-ms now}))
     ((:request s) {:method \"PUT\" :url <presigned> :body <b64> :body-as :b64})

   PUT stores, GET answers the bytes (as base64: eve.net.run's :as :b64), HEAD answers 200 when held; a bad or expired
   signature, or a mode that is not the method's, answers 403."
  (:require [clojure.string :as str]
            [eve.net.bytes :as b]
            [eve.net.gateway :as gw]))

(defn- param [q k]
  (some (fn [kv] (let [i (str/index-of kv "=")] (when (and i (= k (subs kv 0 i))) (subs kv (inc i)))))
        (loop [s q out []] (if-let [i (str/index-of s "&")] (recur (subs s (inc i)) (conj out (subs s 0 i))) (conj out s)))))

(defn- parse-long* [s] (when s (reduce (fn [n c] (+ (* 10 n) (str/index-of "0123456789" (str c)))) 0 s)))

(def modes {"PUT" "write" "GET" "read" "HEAD" "head" "DELETE" "delete"})

(defn handle
  "The store's answer to req: {:blobs atom :secret :now-ms :url} st."
  [st {:keys [method url body]}]
  (let [rest (subs url (+ (str/index-of url "/blobs/") 7))
        qi (str/index-of rest "?")
        k (if qi (subs rest 0 qi) rest)
        q (if qi (subs rest (inc qi)) "")
        mode (param q "mode")
        exp (some-> (param q "exp") parse-long*)
        ok? (and (= mode (get modes method))
                 (gw/hmac-ok? (:secret st) k mode exp (param q "sig") (quot ((:now-ms st)) 1000)))]
    (swap! (:log st) conj [method k ok?])
    (cond
      (not ok?) {:status 403 :headers {} :body ""}
      (= "PUT" method) (do (swap! (:blobs st) assoc k (b/from-b64 body)) {:status 200 :headers {} :body ""})
      (= "HEAD" method) {:status (if (contains? @(:blobs st) k) 200 404) :headers {} :body ""}
      (= "GET" method) (if-let [o (get @(:blobs st) k)]
                         {:status 200 :headers {} :body (b/to-b64 o)}
                         {:status 404 :headers {} :body ""})
      :else {:status 405 :headers {} :body ""})))

(defn dev-store
  "{:request f :blobs atom :log atom :url :secret}: a store at url checking signatures under secret."
  [{:keys [url secret now-ms]}]
  (let [st {:url url :secret secret :now-ms now-ms :blobs (atom {}) :log (atom [])}]
    (assoc st :request (fn [req] (handle st req)))))
