(ns eve.net.store
  "The store role: blobs behind presigned URLs — the part an S3-compatible object store plays for a broker. A network
   atom's blocks go here direct, by URLs its broker signs (eve.net.presign/hmac-presigner, a broker's :presign); the broker
   never carries a blob. Any eve host can be a store: the handler is plain shared code, served by eve.http-server/serve!
   where the host has one, or called in process.

     (def s (store {:url \"http://127.0.0.1:9100/blobs/\" :secret \"k\" :now-ms now}))
     ((:handle s) {:method \"PUT\" :url <presigned> :body-b64 <bytes as base64>})
     (eve.http-server/serve! {:port 9100} (:handle s))

   Each request's signature is checked (eve.net.presign/hmac-ok?: the presigner and the store share :secret; a client
   holds only the URL): PUT stores, GET answers the bytes, HEAD answers 200 when held, DELETE forgets. A bad or expired
   signature, or a mode that is not the method's, answers 403. A body arrives as :body-b64 (a server adapter's) or as
   base64 :body (eve.net.run's :body-as :b64, a client in the same process); a GET answers base64 with :body-as :b64,
   which a server adapter sends as the bytes. Every answer carries Access-Control-Allow-Origin: * and an OPTIONS
   preflight is answered, so a page's network atom reaches the store direct.

   opts: :url (the store's own base URL, ending /blobs/ — where its presigner points), :secret, :now-ms (the clock),
   :blobs (an atom of key -> the blob as base64 text, kept as it arrived and answered as it is; default a fresh one:
   the blobs live as long as the process), :log? (true keeps [method key signed?] per request in :log)."
  (:require [clojure.string :as str]
            [eve.net.presign :as presign]))

(defn- param [q k]
  (some (fn [kv] (let [i (str/index-of kv "=")] (when (and i (= k (subs kv 0 i))) (subs kv (inc i)))))
        (loop [s q out []] (if-let [i (str/index-of s "&")] (recur (subs s (inc i)) (conj out (subs s 0 i))) (conj out s)))))

(defn- parse-long* [s] (when s (reduce (fn [n c] (+ (* 10 n) (str/index-of "0123456789" (str c)))) 0 s)))

(def modes {"PUT" "write" "GET" "read" "HEAD" "head" "DELETE" "delete"})

(def ^:private cors
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "GET, PUT, HEAD, DELETE, OPTIONS"
   "access-control-allow-headers" "content-type"
   "access-control-max-age" "600"})

(defn- answer [status m] (merge {:status status :headers cors :body ""} m))

(defn handle
  "The store's answer to req: st is (store opts)."
  [st {:keys [method url body body-b64]}]
  (let [i (str/index-of url "/blobs/")
        rest (if i (subs url (+ i 7)) "")
        qi (str/index-of rest "?")
        k (if qi (subs rest 0 qi) rest)
        q (if qi (subs rest (inc qi)) "")
        mode (param q "mode")
        exp (some-> (param q "exp") parse-long*)
        ok? (and i (not= k "") (= mode (get modes method))
                 (presign/hmac-ok? (:secret st) k mode exp (param q "sig") (quot ((:now-ms st)) 1000)))]
    (when (and (:log st) (not= "OPTIONS" method)) (swap! (:log st) conj [method k (boolean ok?)]))
    (cond
      (= "OPTIONS" method) (answer 204 {})
      (not ok?) (answer 403 {})
      (= "PUT" method) (do (swap! (:blobs st) assoc k (or body-b64 body "")) (answer 200 {}))
      (= "HEAD" method) (answer (if (contains? @(:blobs st) k) 200 404) {})
      (= "DELETE" method) (if (contains? @(:blobs st) k)
                            (do (swap! (:blobs st) dissoc k) (answer 204 {}))
                            (answer 404 {}))
      (= "GET" method) (if-let [o (get @(:blobs st) k)]
                         (answer 200 {:headers (assoc cors "content-type" "application/octet-stream")
                                      :body o :body-as :b64})
                         (answer 404 {}))
      :else (answer 405 {}))))

(defn store
  "A store at :url checking signatures under :secret: {:handle f :blobs atom :log atom-or-nil :url :secret}."
  [{:keys [url secret now-ms blobs log?]}]
  (let [st {:url url :secret secret :now-ms now-ms :blobs (or blobs (atom {})) :log (when log? (atom []))}]
    (assoc st :handle (fn [req] (handle st req)))))

(defn- b64-bytes "the byte count base64 text s names (padded, as eve.net.bytes/to-b64 writes it)" [s]
  (let [n (count s)]
    (- (* 3 (quot n 4)) (cond (str/ends-with? s "==") 2 (str/ends-with? s "=") 1 :else 0))))

(defn stats "what the store holds: {:objects n :bytes n}" [st]
  (let [m @(:blobs st)]
    {:objects (count m) :bytes (reduce + 0 (map b64-bytes (vals m)))}))
