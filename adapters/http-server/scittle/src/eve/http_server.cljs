(ns eve.http-server
  "eve's http-server feature (adapters/README.md) in scittle — the browser adapter's PUMP shape, which is
   the only shape scittle has: a scittle program is interpreted by SCI in one heap (the page's, or the
   seat's page-worker — hosts.edn's :scittle row: 'a page's loader reads these roots over HTTP in a
   cross-origin isolated page's worker'), and THE HEAP owns the endpoint, exactly as the page does in the
   browser adapter and the host does in the vs adapter. A page has no listener socket and needs none: the
   server that served the page (the origin host) holds the member's TCP listener and delivers request
   events down the channel this heap holds open to its origin; the heap's own citizens reach the endpoint
   through eve.http-server/request! in the same heap.

   What is NOT ported from the browser adapter, and why — the two mechanisms are the compiled build's, not
   the topology's:
   - the thread mailboxes (eve.thread-host/send!, receive!, the endpoint pump draining thread 0's
     mailbox): there is one heap and no second thread to mail. The origin channel's poll chain is the
     pump; request! dispatches in-heap and takes the answer synchronously off the dispatch itself.
   - the dispatch pool (serve threads resolving the handler BY NAME in their own heaps via
     goog.getObjectByName): a worker the thread adapter spawns boots the compiled fat-kernel main.js,
     which contains no SCI interpreter and no program — the pool shape waits on an
     interpreter-in-a-worker rung (a worker that boots scittle.js plus the program source) that nobody
     has built. Named here so the cell reads as a missing rung, not a property of scittle.

   (serve! opts handler) -> {:port :stop}. handler takes eve's request map {:method :url :headers :body
   :body-b64} — :url the path and query as sent, :headers lower-case names, :body UTF-8 text and
   :body-b64 the same bytes as base64 — and answers {:status :headers :body :body-as}: :body text, or
   with :body-as :b64 the base64 of the bytes to send (eve.net.run's conventions, as the node and JDK
   adapters speak them). A handler that throws answers 500; an unrouted path is the handler's own
   business.

   The terms' scittle meanings:
   - :port names the member's endpoint ON ITS ORIGIN HOST (as vs's :port is its host's flag and the
     browser adapter's is its origin's); the returned :port echoes it, nil when no origin channel is
     configured (in-page serving only). The channel: a long poll of /__eve_http_server/<port>/poll on
     this heap's origin; answers return by POST to .../respond, EDN both ways — the heap's own contract
     with its origin host, exactly vs's event-in / respond-out with its bridge.
   - :threads is accepted and recorded, and any value serves the same way: the pump dispatches on this
     heap's event loop (the origin poll chain, one event at a time), because the pool it counts on other
     hosts is the unbuilt rung above. A handler here must not block, the constraint the page already
     lives under.
   - handler is a function, or a symbol naming a var IN THIS HEAP: the program is interpreted where it
     lives, so a name resolves here — (resolve handler) — unlike the compiled browser build, where a
     symbol means 'resolve in each serve thread's heap'. An unresolvable symbol throws, naming itself.
   - :host is accepted and ignored: the origin is the host.

   Byte work rides the platform's own codecs — TextEncoder/TextDecoder for UTF-8, atob/btoa for
   base64 — NOT eve.net.bytes, for two measured reasons (the lane's STATUS has the full trail):
   (1) eve.net.bytes cannot load in stock scittle's SCI context: its require eve.encoding references
   cljs.core/MapEntry, a type constructor stock scittle 0.8.33's built-in cljs.core mappings omit —
   and no route available to a program or a page can add it (intern and in-ns/def never reach the
   built-in mappings a new namespace refers from; sci.core exposes only error formatting;
   sci.ctx-store exposes nothing; the bundle puts no cljs.core on the window; plugins are bundles
   compiled against the host's own application object). nbb's SCI context has the binding and the
   compiled cljs build has it; stock scittle's has not — a seat gap, named, not worked around in
   Eve's sources, which stay byte-identical. The handler boundary is unchanged by the choice: :body
   is UTF-8 text and :body-b64 the exact bytes as base64, as on every host. (2) The platform decoder
   is the lossy one the browser lane's D2 asks for: TextDecoder (fatal: false, the default) never
   throws on a body ending mid-sequence, where eve.net.bytes/utf8->str does — so the prefix-decode
   guard the browser adapter carries around that wart is unnecessary on this seat. The channel's EDN
   is likewise read with the seat's own reader: cljs.core/read-string (SCI's EDN reader) — stock
   scittle registers no cljs.reader namespace for programs (the reader in its bundle is SCI's
   internal one), where the compiled build's adapter requires cljs.reader.")

;; ---------------------------------------------------------------------------
;; Small shapes
;; ---------------------------------------------------------------------------

(defonce ^:private utf8-enc (js/TextEncoder.))
(defonce ^:private utf8-dec (js/TextDecoder. "utf-8"))

(defn- b64->u8
  "base64 -> bytes (a Uint8Array): atob's binary string, one char per byte"
  [b64]
  (let [s (js/atob (str (or b64 "")))
        u (js/Uint8Array. (.-length s))]
    (dotimes [i (.-length s)] (aset u i (.charCodeAt s i)))
    u))

(defn- u8->b64
  "bytes (a Uint8Array) -> base64: btoa over a binary string, built in chunks a call stack can hold"
  [u]
  (loop [i 0 parts []]
    (if (< i (.-length u))
      (recur (+ i 8192)
             (conj parts (.apply js/String.fromCharCode nil
                                 (.subarray u i (min (.-length u) (+ i 8192))))))
      (js/btoa (apply str parts)))))

(defn- resp-bytes-b64
  "a response's bytes as base64: with :body-as :b64 the :body already is base64; else its UTF-8 bytes'"
  [{:keys [body body-as]}]
  (if (= :b64 body-as) (str (or body "")) (u8->b64 (.encode utf8-enc (str (or body ""))))))

(defn- run-handler
  "handler's answer to req, a throw a 500 (every adapter's rule)"
  [handler req]
  (try (handler req)
       (catch :default e {:status 500 :headers {} :body (str (or (ex-message e) e))})))

;; ---------------------------------------------------------------------------
;; Origin-answer instrumentation (the browser adapter's dbg!, ported): POSTs one line per hop of the
;; origin-answer segment to the origin's /__eve_debug (the harness logs it into the transcript).
;; Inert unless window.__eve_http_server_debug is set by the page. Fire-and-forget, fully guarded —
;; it can never throw into the flow it observes.
;; ---------------------------------------------------------------------------

(defonce ^:private debug-flag (atom false))

(defn- page-debug? []
  (or @debug-flag
      (and (exists? js/window) (true? (.-__eve_http_server_debug js/window)))))

(defn- err-str [e]
  (try (str (or (ex-message e) (.-message e) e))
       (catch :default _ (str e))))

(defn- dbg! [& xs]
  (when (page-debug?)
    (try
      (-> (js/fetch (str (.-origin js/location) "/__eve_debug")
                    #js {:method "POST" :body (apply str xs)})
          (.catch (fn [_] nil)))
      (catch :default _ nil))))

;; ---------------------------------------------------------------------------
;; The endpoint (this heap): the registry of servers and the pending origin
;; requests. The browser adapter's pump drained thread 0's mailbox into the
;; same two maps; here dispatch is a call, so the maps are all that remain.
;; ---------------------------------------------------------------------------

(defonce ^:private endpoint
  (atom {:servers {}   ; id -> {:id :port :handler :threads}
         :pending {}   ; origin request id -> (fn [resp])
         :seq 0}))

(defn- find-server [port]
  (let [servers (vals (:servers @endpoint))]
    (or (first (filter #(= port (:port %)) servers))
        (when (and (nil? port) (= 1 (count servers))) (first servers)))))

(defn- dispatch!
  "Route one request event to the server at port and answer through respond (an origin request's
   respond fn, registered in :pending before this call, or request!'s answer box). No server: a 502
   through the same respond, so an origin event is never stranded."
  [{:keys [id port req]} respond]
  (try
    (dbg! "dispatch!: id=" id " port=" port
          " server-ports=" (pr-str (mapv :port (vals (:servers @endpoint)))))
    (let [s (find-server port)]
      (if (nil? s)
        (do (dbg! "dispatch!: NO SERVER for id=" id " port=" port)
            (respond {:status 502 :headers {} :body "eve.http-server: no server at that port"}))
        (let [resp (run-handler (:handler s) req)]
          (dbg! "dispatch!: id=" id " answered status " (:status resp))
          (respond resp))))
    (catch :default e
      (dbg! "dispatch!: THREW for id=" id ": " (err-str e))
      (respond {:status 500 :headers {} :body (str "eve.http-server: " (err-str e))}))))

;; ---------------------------------------------------------------------------
;; The origin channel (this heap): a long poll the origin host answers with
;; the next request event it took on this member's port.
;; ---------------------------------------------------------------------------

(defonce ^:private channel (atom {:open #{}}))

(defn- channel-url [port kind] (str (.-origin js/location) "/__eve_http_server/" port "/" kind))

(defn- respond-origin!
  "Answer an origin request: POST its response (bytes as base64) back down the channel. The channel
   speaks EDN both ways — it is this heap's own contract with its origin host."
  [port id resp]
  (dbg! "respond-origin!: port=" port " id=" id " status=" (:status resp))
  (try
    (-> (js/fetch (channel-url port "respond")
                  #js {:method "POST" :headers #js {"content-type" "application/edn"}
                       :body (pr-str {:id id :status (or (:status resp) 200)
                                      :headers (into {} (map (fn [[k v]] [(name k) (str v)]) (:headers resp)))
                                      :body-b64 (resp-bytes-b64 resp)})})
        (.then (fn [r] (dbg! "respond-origin!: fetch answered " (.-status r) " for id=" id)))
        (.catch (fn [e] (dbg! "respond-origin!: fetch REJECTED for id=" id ": " (err-str e)))))
    (catch :default e
      (dbg! "respond-origin!: THREW synchronously for id=" id ": " (err-str e)))))

(defn- poll-channel!
  "Hold the channel to the origin open for the server at port: each event it delivers (an EDN request
   event) is dispatched like any request event, its answer POSTed back. Re-polls while the server
   stands. This chain is the pump: it is what serving means on a heap with an event loop and no
   threads to mail."
  [port]
  (let [step (fn step []
               (when (contains? (:open @channel) port)
                 (-> (js/fetch (channel-url port "poll"))
                     (.then (fn [^js r]
                              (if (= 200 (.-status r))
                                (.then (.text r)
                                       (fn [text]
                                         (dbg! "poll: event text received (" (count text) " chars)")
                                         ;; The channel's contract (an event in, a respond out) is held
                                         ;; the way the browser lane hardened it: the answer path is
                                         ;; registered BEFORE the request is built or dispatched, and
                                         ;; a failure there answers 500 through it instead of stranding
                                         ;; the event (a throw in this callback is otherwise
                                         ;; indistinguishable from a clean re-poll — the chain's .catch
                                         ;; retries the poll either way).
                                         (try
                                           (let [e  (read-string text)
                                                 id (:id e)]
                                             (dbg! "poll: event parsed id=" id " url=" (:url e))
                                             (swap! endpoint assoc-in [:pending id]
                                                    (fn [resp]
                                                      (swap! endpoint update :pending dissoc id)
                                                      (respond-origin! port id resp)))
                                             (dbg! "poll: pending registered for id=" id)
                                             (try
                                               (let [req {:method (:method e) :url (:url e)
                                                          :headers (:headers e)
                                                          :body (.decode utf8-dec (b64->u8 (:body-b64 e)))
                                                          :body-b64 (:body-b64 e)}]
                                                 (dbg! "poll: req built for id=" id)
                                                 (dispatch! {:id id :port port :req req}
                                                            (get (:pending @endpoint) id))
                                                 (dbg! "poll: dispatch! returned for id=" id))
                                               (catch :default err
                                                 (dbg! "poll: EVENT FAILED for id=" id ": " (err-str err))
                                                 (when-let [respond (get (:pending @endpoint) id)]
                                                   (respond {:status 500 :headers {}
                                                             :body (str "eve.http-server: " (err-str err))})))))
                                           (catch :default err
                                             (dbg! "poll: CALLBACK THREW: " (err-str err))))
                                         (step)))
                                (step))))
                     (.catch (fn [e] (dbg! "poll: chain rejected: " (err-str e))
                               (js/setTimeout step 250))))))]
    (step)))

(defn- open-channel! [port]
  (when (and (some? port) (not (contains? (:open @channel) port)))
    (swap! channel update :open conj port)
    (poll-channel! port)))

;; ---------------------------------------------------------------------------
;; The in-page channel's client half: a request event to the endpoint, in the
;; same heap — the dispatch is the round-trip.
;; ---------------------------------------------------------------------------

(defonce ^:private req-seq (atom 0))

(defn request!
  "Deliver req (a request map, as a handler takes) to the server at port (nil: this heap's only
   server) and take its response map. Synchronous — caller and endpoint share a heap, so there is
   nothing to wait on; the handler runs inside this call and must not block, as everywhere on this
   heap. Throws when no server stands at the port: in-heap, a missing server is the caller's own
   programming error, named rather than waited out."
  [port req]
  (when-not (find-server port)
    (throw (ex-info (str "eve.http-server: no server at port " port " in this heap")
                    {:port port :servers (mapv :port (vals (:servers @endpoint)))})))
  (let [id  (str "rq-" (swap! req-seq inc))
        box (atom nil)]
    (dispatch! {:id id :port port :req req} (fn [resp] (reset! box resp)))
    @box))

;; ---------------------------------------------------------------------------
;; serve!
;; ---------------------------------------------------------------------------

(defonce ^:private local-seq (atom 0))

(defn- server-id [] (str "srv-" (swap! local-seq inc)))

(defn serve!
  "Serve handler as the member's endpoint on its origin host at opts :port (nil: in-page serving
   only). handler is a function, or a symbol naming a var in this heap (resolved once, here, where
   the program lives). :threads is accepted; the pump serves at any count (see the ns docstring).
   {:port :stop}."
  [opts handler]
  (let [port    (:port opts)
        threads (get opts :threads 2)
        id      (server-id)
        f       (cond (symbol? handler) (try (resolve handler)
                                             (catch :default _ nil))
                      (fn? handler) handler
                      :else nil)]
    (when-not f
      (throw (ex-info (str "eve.http-server: handler must be a function, or a symbol naming a var "
                           "in this heap (the program is interpreted where it lives): " (pr-str handler))
                      {:handler handler})))
    (let [s {:id id :port port :handler f :threads threads}]
      (swap! endpoint assoc-in [:servers id] s)
      (dbg! "serve!: server " id " on port " port " threads(accepted) " threads)
      (open-channel! port)
      {:port port
       :stop (fn []
               (swap! endpoint update :servers dissoc id)
               (when (some? port) (swap! channel update :open disj port))
               nil)})))
