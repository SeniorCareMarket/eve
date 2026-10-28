(ns eve.http
  "eve's http feature (adapters/README.md) in a browser: XMLHttpRequest, synchronous, as eve.net's contract is on every
   host (deref and swap! answer the value). `platform` answers {:request f :request-all g}: f takes {:method :url
   :headers :body :as :body-as} and answers {:status :headers :body} — a request with :body-as :b64 sends the bytes its
   base64 :body names, :body-as :bytes the byte array (a Uint8Array) :body is; :as :b64 answers the response body as
   base64, :as :bytes as a Uint8Array (eve.encoding's, which eve.codec reads); otherwise bodies are UTF-8 text — or
   {:error message}; g takes a batch and answers its responses in order.

   A synchronous request on a page's main thread cannot set a responseType, so a byte body is read as a binary string
   (the x-user-defined charset maps each byte to one char, 0xF700 + b above 0x7F) and base64-encoded here; in a worker
   the same code runs.

   A batch (a network atom's level of blocks: hundreds of small GETs) in a worker runs at once: a worker cannot await
   (it blocks), but the page's main thread never does, so the worker posts the batch to the page (self.postMessage:
   eve's thread adapter hands a worker's message to the page's hook, globalThis.__eve_worker_message, which this
   namespace sets on the page) with a SharedArrayBuffer of its own, and waits on it (Atomics.wait); the page fetches
   the batch concurrently (fetch, Promise.all) and writes the answers there. A page that never answers (no hook) is
   found by a first short wait, and the worker then runs batches in turn, as the page's main thread always does.")

(defn- b64->bytes [s]
  (let [bin (js/atob s)
        a (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset a i (.charCodeAt bin i)))
    a))

(defn- binary-string->b64
  "x-user-defined text (one char a byte) as base64."
  [s]
  (let [n (.-length s)
        chunk 8192]
    (js/btoa
     (loop [i 0 acc ""]
       (if (< i n)
         (let [end (min n (+ i chunk))
               part (.apply (.-fromCharCode js/String) nil
                            (js/Array.from (js/Array. (- end i))
                                           (fn [_ j] (bit-and (.charCodeAt s (+ i j)) 0xFF))))]
           (recur end (str acc part)))
         acc)))))

(defn- binary-string->bytes
  "x-user-defined text (one char a byte) as a Uint8Array."
  [s]
  (let [n (.-length s) a (js/Uint8Array. n)]
    (dotimes [i n] (aset a i (bit-and (.charCodeAt s i) 0xFF)))
    a))

(defn- bytes->b64 [^js u8]
  (let [n (.-length u8)
        chunk 8192]
    (js/btoa (loop [i 0 acc ""]
               (if (< i n)
                 (let [end (min n (+ i chunk))]
                   (recur end (str acc (.apply (.-fromCharCode js/String) nil (.subarray u8 i end)))))
                 acc)))))

(defn- response-headers [xhr]
  (into {}
        (keep (fn [line]
                (let [i (.indexOf line ":")]
                  (when (pos? i) [(.toLowerCase (.trim (subs line 0 i))) (.trim (subs line (inc i)))])))
              (.split (or (.getAllResponseHeaders xhr) "") "\r\n"))))

(def ^:private forbidden
  "headers a browser sets itself and refuses from a script"
  #{"host" "content-length" "connection" "accept-encoding" "origin" "referer" "user-agent"})

(defn request [{:keys [method url headers body as body-as]}]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr (or method "GET") url false)
      (when (#{:b64 :bytes} as) (.overrideMimeType xhr "text/plain; charset=x-user-defined"))
      (doseq [[k v] headers :let [k (name k)] :when (not (forbidden (.toLowerCase k)))]
        (.setRequestHeader xhr k (str v)))
      (.send xhr (cond (nil? body) nil
                       (= :b64 body-as) (b64->bytes body)
                       :else body))
      {:status (.-status xhr)
       :headers (response-headers xhr)
       :body (case as
               :b64 (binary-string->b64 (.-responseText xhr))
               :bytes (binary-string->bytes (.-responseText xhr))
               (.-responseText xhr))})
    (catch :default e {:error (or (.-message e) (str e))})))

;; ---------------------------------------------------------------------------
;; Batches: a worker's, fetched by the page
;; ---------------------------------------------------------------------------

(def ^:private BATCH-BYTES
  "The answers a batch may carry (JSON, UTF-8): a batch past it runs in turn (eve.net sends 32 GETs at a time)"
  (* 8 1024 1024))

(def ^:private WAIT-MS 60000)
(def ^:private PROBE-MS 3000)

(defn- page? [] (exists? js/document))

(defn- fetch-one
  "A request fetched on the page: a promise of its answer as plain JS ({status headers body} or {error})."
  [^js r]
  (let [hs (js-obj)]
    (doseq [k (js/Object.keys (or (.-headers r) #js {})) :when (not (forbidden (.toLowerCase k)))]
      (aset hs k (aget (.-headers r) k)))
    (-> (js/fetch (.-url r) #js {:method (or (.-method r) "GET") :headers hs
                                 :body (cond (nil? (.-body r)) js/undefined
                                             (.-b64 r) (b64->bytes (.-body r))
                                             :else (.-body r))})
        (.then (fn [^js resp]
                 (.then (.arrayBuffer resp)
                        (fn [ab]
                          (let [u8 (js/Uint8Array. ab)
                                h (js-obj)]
                            (.forEach (.-headers resp) (fn [v k] (aset h (.toLowerCase k) v)))
                            #js {:status (.-status resp) :headers h
                                 :body (if (.-as64 r) (bytes->b64 u8) (.decode (js/TextDecoder.) u8))})))))
        (.catch (fn [e] #js {:error (str (or (.-message e) e))})))))

(defn- answer!
  "On the page: write state (1 answers, 2 too large or failed, 9 the probe's) and the bytes into the worker's buffer,
   and wake it."
  [sab state ^js bytes]
  (let [i32 (js/Int32Array. sab 0 2)]
    (when bytes
      (.set (js/Uint8Array. sab 8 (.-length bytes)) bytes)
      (aset i32 1 (.-length bytes)))
    (js/Atomics.store i32 0 state)
    (js/Atomics.notify i32 0)))

(defn- serve-batch! [^js e]
  (let [d (.-data e)]
    (when (and d (.-eveHttp d))
      (let [sab (.-sab d)]
        (if (.-probe d)
          (answer! sab 9 nil)
          (-> (js/Promise.all (.map (js/JSON.parse (.-reqs d)) fetch-one))
              (.then (fn [answers]
                       (let [bytes (.encode (js/TextEncoder.) (js/JSON.stringify answers))]
                         (if (> (.-length bytes) (- (.-byteLength sab) 8))
                           (answer! sab 2 nil)
                           (answer! sab 1 bytes)))))
              (.catch (fn [_] (answer! sab 2 nil)))))))))

;; the page serves its workers' batches (eve's browser thread adapter hands their messages to this hook)
(when (page?) (set! (.-__eve_worker_message js/globalThis) serve-batch!))

(defonce ^:private batch-state (atom nil))

(defn- batch-buffer
  "This worker's buffer for batches, and whether the page answers them (asked once, a short wait): nil when not."
  []
  (let [s @batch-state]
    (if (some? s)
      (when (:ok s) (:sab s))
      (let [sab (js/SharedArrayBuffer. (+ 8 BATCH-BYTES))
            i32 (js/Int32Array. sab 0 2)]
        (js/Atomics.store i32 0 0)
        (.postMessage js/self #js {:eveHttp true :probe true :sab sab})
        (let [ok (and (not= "timed-out" (js/Atomics.wait i32 0 0 PROBE-MS)) (= 9 (js/Atomics.load i32 0)))]
          (reset! batch-state {:ok ok :sab sab})
          (when ok sab))))))

(defn- in-turn [reqs] (mapv request reqs))

(defn- plain
  "a request as the page fetches it (JSON: a byte body and a byte answer cross as base64)"
  [{:keys [method url headers body as body-as]}]
  #js {:method (or method "GET") :url url :headers (clj->js (into {} (map (fn [[k v]] [(name k) (str v)]) headers)))
       :body (if (= :bytes body-as) (bytes->b64 body) body) :b64 (contains? #{:b64 :bytes} body-as)
       :as64 (contains? #{:b64 :bytes} as)})

(defn request-all
  "Every request of reqs answered, in order: at once through the page (a worker), or in turn."
  [reqs]
  (let [reqs (vec reqs)
        sab (when (and (> (count reqs) 1) (not (page?)) (exists? js/SharedArrayBuffer) (exists? js/self.postMessage))
              (batch-buffer))]
    (if-not sab
      (in-turn reqs)
      (let [i32 (js/Int32Array. sab 0 2)]
        (js/Atomics.store i32 0 0)
        (.postMessage js/self #js {:eveHttp true :sab sab :reqs (js/JSON.stringify (into-array (map plain reqs)))})
        (js/Atomics.wait i32 0 0 WAIT-MS)
        ;; (unanswered in time: the page may still write into this buffer, so the next batch takes a fresh one)
        (when (zero? (js/Atomics.load i32 0))
          (reset! batch-state {:ok true :sab (js/SharedArrayBuffer. (+ 8 BATCH-BYTES))}))
        (if (= 1 (js/Atomics.load i32 0))
          (let [n (aget i32 1)
                bytes (.slice (js/Uint8Array. sab 8 n))
                answers (js/JSON.parse (.decode (js/TextDecoder.) bytes))]
            (mapv (fn [req ^js a]
                    (if (.-error a)
                      {:error (.-error a)}
                      {:status (.-status a)
                       :headers (js->clj (.-headers a))
                       :body (if (= :bytes (:as req)) (b64->bytes (.-body a)) (.-body a))}))
                  reqs answers))
          (in-turn reqs))))))

(defn platform [] {:request request :request-all request-all})
