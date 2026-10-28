(require '[clojure.string :as str]
         '[eve.atom :as ea]
         '[eve.net.atom :as na]
         '[eve.net.gateway :as gw]
         '[eve.process :as proc])

(def base (first *command-line-args*))   ; a fresh directory

;; The object store: blobs behind presigned URLs, each request's signature checked. In production this is
;; S3/Tigris; here it is a map in memory.
(defn mem-store [secret]
  (let [blobs (atom {})]
    (fn [{:keys [method url body]}]
      (let [[_ k q] (re-find #"/blobs/([^?]+)\?(.*)" url)
            p (into {} (map #(vec (str/split % #"=" 2))) (str/split q #"&"))
            mode ({"PUT" "write" "GET" "read" "HEAD" "head"} method)
            ok? (and mode (= mode (p "mode"))
                     (gw/hmac-ok? secret k mode (parse-long (p "exp")) (p "sig") (quot (proc/now-ms) 1000)))]
        (cond (not ok?)        {:status 403 :headers {} :body ""}
              (= method "PUT") (do (swap! blobs assoc k body) {:status 200 :headers {} :body ""})
              (= method "HEAD") {:status (if (contains? @blobs k) 200 404) :headers {} :body ""}
              :else            (if-let [b (get @blobs k)]
                                 {:status 200 :headers {} :body b}
                                 {:status 404 :headers {} :body ""}))))))

(def store (mem-store "store-secret"))

;; A gateway: this process serves its disk domain's atoms on eve.net's broker routes.
(def d (ea/persistent-atom-domain (str base "/db")))
(def counter (ea/lookup-or-create-mmap-atom! d "app/counter" {:n 0}))
(def g (gw/gateway d {:endpoint "http://gw.test" :domain-id "d1" :api-key "k"
                      :presign (gw/hmac-presigner {:url "http://store.test/blobs/" :secret "store-secret"
                                                   :now-ms proc/now-ms})
                      :platform {:request store}}))

;; A client's platform: requests to the gateway reach its handler, the rest reach the store. Over a real network
;; this is the host's http adapter (eve.net.platform/platform) and the gateway is served by eve.http-server/serve!.
(def platform {:request (fn [req] (if (str/starts-with? (:url req) "http://gw.test") ((:handle g) req) (store req)))
               :sleep proc/sleep-ms! :now-ms proc/now-ms :seed 1})

(def ra (na/remote-atom {:endpoint "http://gw.test" :domain-id "d1" :atom-id (gw/atom-id "app/counter") :api-key "k"}
                        platform))

(println (na/deref ra))                    ; {:n 0}
(println (na/swap! ra update :n inc))      ; {:n 1}
(swap! counter update :n inc)              ; a disk writer of the same atom
(println (na/deref ra))                    ; {:n 2}
(println @counter)                         ; {:n 2}
(println (gw/stats g))
(ea/close-atom-domain! d)
