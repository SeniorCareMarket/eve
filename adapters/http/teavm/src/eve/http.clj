(ns eve.http
  "eve's http feature (adapters/README.md) for the :teavm seat, over the
   eve.http import group (the EveHttp host stub): the Node host runs the
   fetch on a worker thread and the calling body thread blocks inside
   the import until the answer is in — the ClojureScript/Node adapter's
   worker + Atomics.wait shape, moved across the import boundary, so
   `request` stays SYNCHRONOUS as eve.net.run requires (deref and swap!
   answer the value, never a promise).

   The conventions are eve.net.run's, as the java adapter states them:
   `platform` answers {:request f :request-all f}; a request
   {:method :url :headers :body :as :body-as} answers
   {:status :headers :body}; :body-as :b64 sends the bytes its base64
   :body names, :body-as :bytes the byte array :body is; :as :b64
   answers the response body as base64, :as :bytes as a byte array,
   otherwise bodies are UTF-8 text. A transport failure answers
   {:error message}, as eve.net.wire reads it.

   The wire between body and host is one JSON text per call through the
   import scratch (out/in, byte-at-a-time, the eve.region shape), with
   every body inside it base64 — so any byte string crosses exactly.
   The scratch is single-flight: one call at a time per body.

   Two named differences from the java leaf: request-all runs its
   requests in order on the calling thread (the java leaf sends the
   batch at once); response header values are the host fetch's combined
   values where the java leaf takes the first. The restricted header
   set the java leaf refuses (host, content-length, connection, expect,
   upgrade) is dropped here before the call, as there."
  (:require [clojure.string :as str])
  (:import [EveHttp]
           [RegionBytes]))

;; ---------------------------------------------------------------------
;; the import scratch: one JSON text out, one JSON text in

(defn- push-text! [^String s]
  (EveHttp/outReset)
  (let [^bytes bs (.getBytes s "UTF-8")]
    (dotimes [i (alength bs)]
      (EveHttp/outByte (RegionBytes/get bs i)))))

(defn- pull-text! []
  (let [n (EveHttp/inLen)
        ^bytes bs (byte-array n)]
    (dotimes [i n]
      (RegionBytes/put bs i (EveHttp/inByte i)))
    (String. bs "UTF-8")))

;; ---------------------------------------------------------------------
;; base64 (hand-rolled, as the glojure http-server adapter does: no
;; classlib surface is assumed beyond the seat's own stubs)

(def ^:private b64-alpha
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn- b64-encode [^bytes bs]
  (let [n (alength bs)
        sb (StringBuilder.)]
    (loop [i 0]
      (if (< i n)
        (let [b0 (RegionBytes/get bs i)
              b1 (if (< (+ i 1) n) (RegionBytes/get bs (+ i 1)) -1)
              b2 (if (< (+ i 2) n) (RegionBytes/get bs (+ i 2)) -1)]
          (.append sb (.charAt b64-alpha (bit-shift-right b0 2)))
          (.append sb (.charAt b64-alpha
                               (bit-or (bit-shift-left (bit-and b0 3) 4)
                                       (if (neg? b1) 0 (bit-shift-right b1 4)))))
          (.append sb (if (neg? b1)
                        \=
                        (.charAt b64-alpha
                                 (bit-or (bit-shift-left (bit-and b1 15) 2)
                                         (if (neg? b2) 0 (bit-shift-right b2 6))))))
          (.append sb (if (neg? b2) \= (.charAt b64-alpha (bit-and b2 63))))
          (recur (+ i 3)))
        (str sb)))))

(defn- b64-val [c]
  (.indexOf b64-alpha (int c)))

(defn- b64-decode ^bytes [^String s]
  (let [n (.length s)
        pads (cond (zero? n) 0
                   (not= (.charAt s (dec n)) \=) 0
                   (= (.charAt s (- n 2)) \=) 2
                   :else 1)
        ^bytes out (byte-array (- (quot (* n 3) 4) pads))]
    (loop [i 0 o 0]
      (when (< i n)
        (let [v0 (b64-val (.charAt s i))
              v1 (b64-val (.charAt s (+ i 1)))
              p2 (= (.charAt s (+ i 2)) \=)
              p3 (= (.charAt s (+ i 3)) \=)
              v2 (if p2 0 (b64-val (.charAt s (+ i 2))))
              v3 (if p3 0 (b64-val (.charAt s (+ i 3))))]
          (RegionBytes/put out o (bit-or (bit-shift-left v0 2)
                                         (bit-shift-right v1 4)))
          (when-not p2
            (RegionBytes/put out (+ o 1) (bit-or (bit-shift-left (bit-and v1 15) 4)
                                                 (bit-shift-right v2 2))))
          (when-not p3
            (RegionBytes/put out (+ o 2) (bit-or (bit-shift-left (bit-and v2 3) 6) v3)))
          (recur (+ i 4) (+ o (cond p2 1 p3 2 :else 3))))))
    out))

;; ---------------------------------------------------------------------
;; a small JSON codec for the flat shapes that cross: maps with string
;; keys; string / number / boolean / nil / vector values. Bodies never
;; cross as JSON text — they are base64 strings inside it.

(defn- hex4 [x]
  (let [h (Integer/toHexString (int x))
        sb (StringBuilder.)]
    (dotimes [_ (- 4 (.length h))] (.append sb \0))
    (.append sb h)
    (str sb)))

(defn- json-escape [^String s]
  (let [sb (StringBuilder.)]
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (cond
          (= c \") (.append sb "\\\"")
          (= c \\) (.append sb "\\\\")
          (= c \newline) (.append sb "\\n")
          (= c \return) (.append sb "\\r")
          (= c \tab) (.append sb "\\t")
          (< (int c) 32) (.append sb (str "\\u" (hex4 (int c))))
          :else (.append sb c))))
    (str sb)))

(defn- json-str [v]
  (cond
    (nil? v) "null"
    (string? v) (str "\"" (json-escape v) "\"")
    (map? v) (str "{"
                  (str/join "," (map (fn [[k x]] (str (json-str (str k)) ":" (json-str x))) v))
                  "}")
    (sequential? v) (str "[" (str/join "," (map json-str v)) "]")
    (number? v) (str v)
    (true? v) "true"
    (false? v) "false"
    :else (json-str (str v))))

(defn- json-parse [^String s]
  (let [n (.length s)
        i (atom 0)]
    (letfn [(ws []
              (loop []
                (when (and (< @i n) (<= (int (.charAt s @i)) 32))
                  (swap! i inc)
                  (recur))))
            (pstr []
              ;; @i is just past the opening quote
              (let [sb (StringBuilder.)]
                (loop []
                  (let [c (.charAt s @i)]
                    (swap! i inc)
                    (cond
                      (= c \") (str sb)
                      (= c \\)
                      (let [e (.charAt s @i)]
                        (swap! i inc)
                        (case e
                          \" (.append sb \")
                          \\ (.append sb \\)
                          \/ (.append sb \/)
                          \b (.append sb (char 8))
                          \f (.append sb (char 12))
                          \n (.append sb \newline)
                          \r (.append sb \return)
                          \t (.append sb \tab)
                          \u (do (.append sb (char (Integer/parseInt (subs s @i (+ @i 4)) 16)))
                                 (swap! i + 4))
                          (.append sb e))
                        (recur))
                      :else (do (.append sb c) (recur)))))))
            (pnum []
              (let [start @i]
                (loop []
                  (when (and (< @i n)
                             (let [c (.charAt s @i)]
                               (or (Character/isDigit c)
                                   (= c \-) (= c \+) (= c \.) (= c \e) (= c \E))))
                    (swap! i inc)
                    (recur)))
                (let [txt (subs s start @i)]
                  (if (or (.contains txt ".") (.contains txt "e") (.contains txt "E"))
                    (Double/parseDouble txt)
                    (Long/parseLong txt)))))
            (pval []
              (ws)
              (let [c (.charAt s @i)]
                (cond
                  (= c \{) (do (swap! i inc) (pobj))
                  (= c \[) (do (swap! i inc) (parr))
                  (= c \") (do (swap! i inc) (pstr))
                  (= c \t) (do (swap! i + 4) true)
                  (= c \f) (do (swap! i + 5) false)
                  (= c \n) (do (swap! i + 4) nil)
                  :else (pnum))))
            (pobj []
              (ws)
              (if (= (.charAt s @i) \})
                (do (swap! i inc) {})
                (loop [m {}]
                  (ws)
                  (swap! i inc) ;; opening quote of the key
                  (let [k (pstr)]
                    (ws)
                    (swap! i inc) ;; colon
                    (let [v (pval)]
                      (ws)
                      (let [m2 (assoc m k v)]
                        (if (= (.charAt s @i) \,)
                          (do (swap! i inc) (recur m2))
                          (do (swap! i inc) m2))))))))
            (parr []
              (ws)
              (if (= (.charAt s @i) \])
                (do (swap! i inc) [])
                (loop [v []]
                  (let [x (pval)]
                    (ws)
                    (let [v2 (conj v x)]
                      (if (= (.charAt s @i) \,)
                        (do (swap! i inc) (recur v2))
                        (do (swap! i inc) v2)))))))]
      (pval))))

;; ---------------------------------------------------------------------
;; the leaf

;; headers the host's fetch sets itself; the java leaf refuses them
;; from a caller, so they are dropped here before the call as well
(def ^:private RESTRICTED #{"host" "content-length" "connection" "expect" "upgrade"})

(defn request
  [req]
  (let [{:keys [method url headers body as body-as]} req
        body-b64 (cond (nil? body) nil
                       (= :b64 body-as) (str body)
                       (= :bytes body-as) (b64-encode body)
                       :else (b64-encode (.getBytes (str body) "UTF-8")))]
    (push-text! (json-str {"method" (or method "GET")
                           "url" url
                           "headers" (into {}
                                           (comp (map (fn [[k v]] [(str/lower-case (name k)) (str v)]))
                                                 (remove (fn [[k _]] (RESTRICTED k))))
                                           headers)
                           "bodyB64" body-b64}))
    (if (zero? (EveHttp/fetch))
      {:error "eve.http: the host fetch did not run"}
      (let [r (json-parse (pull-text!))]
        (if (get r "error")
          {:error (get r "error")}
          {:status (get r "status")
           :headers (into {} (map (fn [[k v]] [(str/lower-case k) v])) (get r "headers"))
           :body (let [b64 (get r "bodyB64")]
                   (case as
                     :b64 b64
                     :bytes (b64-decode b64)
                     (String. ^bytes (b64-decode b64) "UTF-8")))})))))

(defn request-all
  "every request run in order on the calling thread; the caller blocks
   until all are in"
  [reqs]
  (mapv request reqs))

(defn platform [] {:request request :request-all request-all})
