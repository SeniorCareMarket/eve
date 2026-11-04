(ns eve.http-vs-check
  "eve.http on ValueScript (adapters/http/vs) against a local eve.net.store: PUTs a byte array, GETs it back as bytes,
   as base64 and through request-all, and reads a 403 for an unsigned URL. Its arguments are a presigned PUT URL, a
   presigned GET URL and the store's base URL; run by bb test:vs-http under vs-term --bridge. Prints `N passed, M
   failed`, which the task reads."
  (:refer-clojure :exclude [aget alength byte-array])
  (:require [eve.host :refer [aget alength byte-array]]
            [eve.http :as http]))

(def BYTES [0 1 127 128 200 255 10 13 0 65])

(defn- array-of [bs]
  (let [a (byte-array (count bs))]
    (dotimes [i (count bs)] (clojure.core/aset a i (unchecked-byte (nth bs i))))
    a))

(defn- unsigned [a] (mapv (fn [i] (bit-and (aget a i) 0xFF)) (range (alength a))))

(def results (atom []))
(defn- check [label ok? got]
  (swap! results conj ok?)
  (println (if ok? "ok  " "FAIL") label (if ok? "" (pr-str got))))

(let [[put-url get-url base] *command-line-args*
      put (http/request {:method "PUT" :url put-url :body (array-of BYTES) :body-as :bytes})
      got (http/request {:method "GET" :url get-url :as :bytes})
      b64 (http/request {:method "GET" :url get-url :as :b64})
      all (http/request-all [{:method "GET" :url get-url :as :bytes} {:method "GET" :url (str base "nope")}])
      bad (http/request {:method "GET" :url (str base "k1")})]
  (check "PUT answers 200" (= 200 (:status put)) put)
  (check "GET :as :bytes answers the bytes" (and (= 200 (:status got)) (= BYTES (unsigned (:body got)))) got)
  (check "GET :as :b64 answers base64" (= "AAF/gMj/Cg0AQQ==" (:body b64)) b64)
  (check "headers are lower case" (contains? (:headers got) "access-control-allow-origin") (:headers got))
  (check "request-all answers in order" (and (= BYTES (unsigned (:body (first all)))) (= 403 (:status (second all))))
         (mapv :status all))
  (check "an unsigned GET answers 403" (= 403 (:status bad)) bad)
  (let [n (count (filter true? @results))]
    (println (str n " passed, " (- (count @results) n) " failed"))))
