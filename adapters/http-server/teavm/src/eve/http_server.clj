(ns eve.http-server
  "eve's http-server feature (adapters/README.md) for the :teavm seat,
   over the eve.http import group (the EveHttp host stub).

   The host side is a Node http server on its own worker thread. It can
   receive and hold requests while the body executes or blocks: each
   request crosses into a shared buffer and the worker waits there for
   the body's response. In the body, `serve!` starts a thread whose
   loop blocks inside the serve_next import until a request is queued,
   reads it fully into plain Clojure values, invokes the handler,
   writes the response back through respond. Requests are therefore
   served one at a time — :threads is accepted for surface parity and
   v1 serves serially; the handler itself runs on that one body thread
   and may block there, as the jdk leaf's handlers may block on theirs.

   The shapes are the jdk leaf's: (serve! {:port :host :threads}
   handler) answers {:port :stop}; the handler's request is
   {:method :url :headers :body :body-b64} — :url the path and query
   exactly as sent, header names lower-case, :body the bytes as UTF-8
   text, :body-b64 the same bytes base64; its response
   {:status :headers :body :body-as}, :body-as :b64 sending the bytes
   the base64 :body names. A handler throw answers 500. Bodies are
   whole: no streaming or range at this layer, as on every seat.

   The wire between body and host is one JSON text per request or
   response through the import scratch (out/in, byte-at-a-time, the
   eve.region shape), bodies base64 inside it. The scratch is
   single-flight and the loop is strictly sequential with it: a request
   is fully read before the handler runs, so a handler that calls
   eve.http.request reuses the scratch only after its own request is
   body-side values, and respond writes only after any such call has
   completed."
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

(defn- handle-one!
  "serve_next has delivered a request: read it fully, run the handler,
   answer through respond. The handler throw answers 500, as the jdk
   leaf answers it."
  [handler]
  (let [r (json-parse (pull-text!))
        body-b64 (get r "bodyB64")
        ^bytes raw (b64-decode body-b64)
        req {:method (get r "method")
             :url (get r "url")
             :headers (into {} (get r "headers"))
             :body (String. raw "UTF-8")
             :body-b64 body-b64}
        resp (try (handler req)
                  (catch Throwable t {:status 500 :headers {} :body (str t)}))
        {:keys [status headers body body-as]} resp]
    (push-text! (json-str {"status" (or status 200)
                           "headers" (into {} (map (fn [[k v]] [(name k) (str v)])) headers)
                           "bodyB64" (if (= :b64 body-as)
                                       (str (or body ""))
                                       (b64-encode (.getBytes (str (or body "")) "UTF-8")))}))
    (EveHttp/respond)))

(defn serve!
  "start the host server and the body's accept thread. Answers
   {:port p :stop f}: f stops the server and ends the accept loop."
  [opts handler]
  (push-text! (str (or (:host opts) "127.0.0.1")))
  (let [port (EveHttp/serveStart (int (or (:port opts) 0)))]
    (when (neg? port)
      (throw (ex-info "eve.http-server: the host server did not start" {:opts opts})))
    (let [stopped (atom false)
          serve-loop (fn []
                       (loop []
                         (when-not @stopped
                           (when (== 1 (EveHttp/serveNext))
                             (handle-one! handler)
                             (recur)))))
          ;; reify, not the fn itself: the Thread(Runnable) call must
          ;; direct-link — a reflective constructor call is a
          ;; Class.cast the seat's images cannot yet answer
          t (Thread. (reify Runnable (run [_] (serve-loop))))]
      (.start t)
      {:port port
       :stop (fn [] (reset! stopped true) (EveHttp/serveStop))})))
