(ns eve.net-serve-vs
  "eve.net's passive broker (eve.net.broker) and its store (eve.net.store) served by a ValueScript program over
   eve.http-server (adapters/http-server/vs): one listener, /blobs/… to the store, every other route to the broker,
   which presigns into that store (eve.net.presign). Run by bb test:vs-serve under Node's vs-serve-node.mjs, which
   passes EVE_BASE (the listener's base URL, ending /), EVE_SECRET (the store's) and EVE_API_KEY (the broker's)."
  (:require [clojure.string :as str]
            [eve.http-server :as hs]
            [eve.net.broker :as broker]
            [eve.net.presign :as presign]
            [eve.net.store :as store]
            [eve.process :as process]))

(let [base (System/getenv "EVE_BASE")
      secret (System/getenv "EVE_SECRET")
      blobs (str base "blobs/")
      st (store/store {:url blobs :secret secret :now-ms process/now-ms})
      bk (broker/broker {:api-key (System/getenv "EVE_API_KEY")
                         :presign (presign/hmac-presigner {:url blobs :secret secret :now-ms process/now-ms})})]
  (println "eve.net-serve-vs: broker and store at" base)
  (hs/serve! {} (fn [req] (if (str/includes? (:url req) "/blobs/") ((:handle st) req) ((:handle bk) req)))))
