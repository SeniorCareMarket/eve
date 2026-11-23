(ns eve.http-server
  "eve's http-server feature (adapters/README.md) in a browser, over the page's own topology — the shape the vs
   adapter takes on ValueScript, translated: there the host owns the listener and hands the program each request
   as an event; here THE PAGE owns the endpoint. A page has no listener socket, and needs none: it is the one
   thread with an event loop open to the outside (its origin) and the one thread every worker can reach, so
   request events arrive at the page and are dispatched from it, and the answers go back the way they came.

   The two channels a browser citizen is reached on, both native to it:

   - the ORIGIN channel: the server that served the page (in eve-media, the media server proxying the gateway on
     this origin) holds the member's TCP listener — opts :port names the member's endpoint ON THAT HOST — and
     delivers request events addressed to it down the channel the page holds open to its origin (a long poll of
     /__eve_http_server/<port>/poll on the page's origin; answers return by POST to .../respond). The page's
     contract with its origin host is exactly vs's with its bridge: an event in, a respond op out.
   - the IN-PAGE channel: any thread of the page delivers a request event to the endpoint (thread 0) through the
     thread mailboxes (eve.thread-host/send!) and takes the answer synchronously — a worker blocks on receive!,
     the main thread polls. The event protocol (EDN data, as every mailbox message):
       {:eve.http-server/request <id> :port <port|nil> :req <request map> :reply <thread addr>}
     and the answer comes back to :reply as
       {:eve.http-server/response <id> :resp <response map>}.

   (serve! opts handler) → {:port :stop}. handler takes eve's request map {:method :url :headers :body
   :body-b64} — :url the path and query as sent, :headers lower-case names, :body UTF-8 text and :body-b64 the
   same bytes as base64 — and answers {:status :headers :body :body-as}: :body text, or with :body-as :b64 the
   base64 of the bytes to send (eve.net.run's conventions, as the node and JDK adapters speak them). A handler
   that throws answers 500; an unrouted path is the handler's own business.

   :threads is the dispatch pool: the handler may block, and the page's main thread cannot, so the handler runs
   on serve threads — workers started by eve.thread-host, each resolving the handler BY NAME in its own heap
   (every heap runs the whole program: the fat kernel), so with a pool the handler is named by symbol:
   (serve! {:port 8700 :threads 2} 'my.app/handler). A handler given as a function serves in the calling heap:
   in a worker, serve! serves in the calling thread until a stop event arrives, then returns {:port :stop} (the
   vs shape); on the page itself, the endpoint's pump runs it — a handler there must not block, the constraint
   the page already lives under. :host is accepted and ignored: the origin is the host."
  ;; unresolved-var exclusions, this file only: this namespace is one of
  ;; several seat files sharing the name; kondo's registry keeps one file's
  ;; defs and reads the others' as unresolved (standalone lint of this file
  ;; is clean). The excluded names are this file's own defs, verified
  ;; against the file.
  {:clj-kondo/config {:linters {:unresolved-var {:exclude [eve.http-server/body->str eve.http-server/endpoint? eve.http-server/ensure-pump! eve.http-server/on-control! eve.http-server/on-response! eve.http-server/page? eve.http-server/pick eve.http-server/pump! eve.http-server/pump-installed eve.http-server/register! eve.http-server/unregister! eve.http-server/utf8->b64]}}}}
  (:require [cljs.reader :as reader]
            [eve.net.bytes :as nb]
            [eve.thread-host :as h]))

;; ---------------------------------------------------------------------------
;; Small shapes
;; ---------------------------------------------------------------------------

(defn- page? [] (exists? js/document))

(defn- endpoint?
  "This heap is the page's (thread 0, the only one with a document)."
  []
  (and (page?) (zero? (h/self))))

(defn- utf8->b64 [s] (nb/to-b64 (nb/utf8 s)))

(defn- resp-bytes-b64
  "a response's bytes as base64: with :body-as :b64 the :body already is base64; else its UTF-8 bytes'"
  [{:keys [body body-as]}]
  (if (= :b64 body-as) (str (or body "")) (utf8->b64 (str (or body "")))))

(defn- run-handler
  "handler's answer to req, a throw a 500 (every adapter's rule)"
  [handler req]
  (try (handler req)
       (catch :default e {:status 500 :headers {} :body (str (or (ex-message e) e))})))

(defn- body->str
  "An origin request's :body — the text form of its bytes. D2 (measured run 4, named by the
   instrumentation: 'poll: CALLBACK THREW: No item 256 in vector of length 256'): eve.net.bytes/
   utf8->str THROWS when a multi-byte lead in the tail promises continuation bytes past the
   vector's end — its portable decoder reads positionally (decode-cp) and cljs nth is
   bounds-checked, so any body whose decode walk reaches the end mid-sequence (the battery's
   256 binary bytes do) kills the conversion. The text form is a convention — the exact bytes
   ride :body-b64 — so decode the longest prefix that decodes clean (a throw can only originate
   in the last three positions; the drop cap is generous) instead of losing the request."
  [bs]
  (let [v       (vec bs)
        attempt (fn [k] (try (nb/utf8->str (subvec v 0 k))
                             (catch :default _ ::threw)))]
    ;; the attempt is a closure because cljs forbids recur across a try boundary
    (loop [k (count v) drops 0]
      (let [r (attempt k)]
        (if (not= ::threw r)
          r
          (if (and (pos? k) (< drops 16))
            (recur (dec k) (inc drops))
            ""))))))

;; ---------------------------------------------------------------------------
;; Origin-answer instrumentation (Bucket C2 closer, run 4): run 3 proved origin delivery +
;; dispatch happened (the parked poll was consumed, the page re-polled) but no respond POST
;; ever reached the driver — and the poll chain's .catch-retry makes even a THROW in the poll
;; callback indistinguishable from a clean re-poll, so the wedge segment (poll callback →
;; dispatch! → serve thread → thread 0 → pump → on-response! → respond-origin!) was unobserved.
;; dbg! POSTs one line per hop to the origin's /__eve_debug (the harness logs it into the
;; transcript). Inert unless the heap's debug flag is set: the page reads
;; window.__eve_http_server_debug; serve threads take the flag from their boot (serve! passes
;; it through). Fire-and-forget, fully guarded — it can never throw into the flow it observes.
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
;; The serve thread: a pool worker. Its entry is resolved by name like every
;; thread's; its boot names the handler the same way.
;; ---------------------------------------------------------------------------

(defn serve-thread
  "A dispatch-pool worker: loop on this thread's mailbox, run the boot's handler (a symbol, resolved in this
   heap) per request event, answer to the event's :reply; a :stop control ends the thread."
  [{:keys [handler debug]}]
  (when debug (reset! debug-flag true))
  (let [sym (symbol handler)
        f (js/goog.getObjectByName (str (munge (namespace sym)) "." (munge (name sym))))]
    (dbg! "serve-thread " (h/self) ": up, handler " handler " resolved " (some? f))
    (loop []
      (let [m (h/receive! -1)]
        (cond
          ;; eve.thread-host's own stop request is a bare keyword, not one of this ns's maps
          (= :eve.thread/stop m) nil
          (and (map? m) (= :stop (:eve.http-server/control m))) nil
          (and (map? m) (contains? m :eve.http-server/request))
          (do (try
                (dbg! "serve-thread " (h/self) ": request " (:eve.http-server/request m)
                      " url " (:url (:req m)) " reply " (:reply m))
                (let [resp (run-handler f (:req m))]
                  (dbg! "serve-thread " (h/self) ": answered " (:eve.http-server/request m)
                        " status " (:status resp) " — send! to " (:reply m))
                  (h/send! (:reply m) {:eve.http-server/response (:eve.http-server/request m)
                                       :resp resp})
                  (dbg! "serve-thread " (h/self) ": answer sent for " (:eve.http-server/request m)))
                (catch :default e
                  (dbg! "serve-thread " (h/self) ": THREW on " (:eve.http-server/request m)
                        ": " (err-str e))))
              (recur))
          :else (recur))))))

;; ---------------------------------------------------------------------------
;; The endpoint (the page's heap only): the registry of servers, the pending
;; origin requests, and the pump that drains this thread's mailbox.
;; ---------------------------------------------------------------------------

(defonce ^:private endpoint
  (atom {:servers {}   ; id -> {:port :pool [addr..] :owner addr|nil :fn handler|nil :rr int}
         :pending {}   ; origin request id -> (fn [resp])
         :seq 0}))

(defn- pick
  "the next dispatch target of server s (round-robin over its pool), or its owner, or its pump-run :fn"
  [s]
  (cond (seq (:pool s)) (let [a (nth (:pool s) (mod (:rr s) (count (:pool s))))]
                          [a (update s :rr inc)])
        (:owner s) [(:owner s) s]
        :else [nil s]))

(defn- dispatch!
  "Route one request event that arrived at the endpoint: to a serve thread or the owning thread, which answers
   to the event's :reply; a :fn server (a function handler registered by the page itself) is run by the pump."
  [{:keys [id port req reply]}]
  (try
    (dbg! "dispatch!: id=" id " port=" port " reply=" reply
          " server-ports=" (pr-str (mapv :port (vals (:servers @endpoint)))))
    (let [servers (:servers @endpoint)
          s (or (first (filter #(= port (:port %)) (vals servers)))
                (when (and (nil? port) (= 1 (count servers))) (first (vals servers))))]
      (cond
        (nil? s) (do (dbg! "dispatch!: NO SERVER for id=" id " port=" port)
                     (when (= 0 reply)
                       (when-let [respond (get (:pending @endpoint) id)]
                         (respond {:status 502 :headers {} :body "eve.http-server: no server at that port"}))))
        (:fn s) (let [resp (run-handler (:fn s) req)]
                  (if (= 0 reply)
                    (when-let [respond (get (:pending @endpoint) id)] (respond resp))
                    (h/send! reply {:eve.http-server/response id :resp resp})))
        :else (let [[target s'] (pick s)]
                (swap! endpoint assoc-in [:servers (:id s)] s')
                (dbg! "dispatch!: id=" id " -> serve thread " target)
                (h/send! target {:eve.http-server/request id :req req :reply reply})
                (dbg! "dispatch!: id=" id " sent to " target))))
    (catch :default e
      (dbg! "dispatch!: THREW for id=" id ": " (err-str e))
      (throw e))))

(defn- on-response!
  "An answer reached the endpoint (its :reply was 0): complete the origin request that is waiting on it."
  [{:keys [eve.http-server/response resp]}]
  (let [respond (get (:pending @endpoint) response)]
    (dbg! "on-response!: id=" response " pending " (if respond "HIT" "MISS")
          " pending-keys=" (pr-str (keys (:pending @endpoint))))
    (when respond
      (swap! endpoint update :pending dissoc response)
      (respond resp))))

(defn- on-control!
  "A serve! in another heap registering or unregistering its server at the endpoint."
  [m]
  (case (:eve.http-server/control m)
    :register (swap! endpoint assoc-in [:servers (:server m)]
                     {:id (:server m) :port (:port m) :pool (:pool m) :owner (:owner m) :rr 0})
    :unregister (swap! endpoint update :servers dissoc (:server m))
    nil))

(defn- pump!
  "The endpoint's pump, on the page's event loop (the page cannot block): drain this thread's mailbox —
   controls, request events addressed here, and answers whose :reply was the endpoint. A poll that finds
   the mailbox empty returns eve.thread-host's timeout keyword; the pass ends there (the next tick resumes)."
  []
  (loop []
    (let [m (h/receive! 0)]
      (when (map? m)
        (cond
          (contains? m :eve.http-server/control) (on-control! m)
          (contains? m :eve.http-server/request)
          #_{:clj-kondo/ignore [:invalid-arity]} ; eve.http-server is defined per seat: this seat's dispatch! takes the one event map; the scittle seat's takes [event respond] - kondo merges the seats' namespaces and checks this seat's calls against the other seat's def
          (dispatch! {:id (:eve.http-server/request m) :port (:port m) :req (:req m) :reply (:reply m)})
          (contains? m :eve.http-server/response)
          (do (dbg! "pump: response map arrived, id=" (:eve.http-server/response m))
              (on-response! m))
          :else nil)
        (recur)))))

;; ---------------------------------------------------------------------------
;; The origin channel (the page's heap only): a long poll the origin host
;; answers with the next request event it took on this member's port.
;; ---------------------------------------------------------------------------

(defonce ^:private channel (atom {:open #{} :stopped #{}}))

(defn- channel-url [port kind] (str (.-origin js/location) "/__eve_http_server/" port "/" kind))

(defn- respond-origin!
  "Answer an origin request: POST its response (bytes as base64) back down the channel. The channel speaks
   EDN both ways — it is the page's own contract with its origin host, as the thread mailboxes are EDN."
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
  "Hold the channel to the origin open for the server at port: each event it delivers (an EDN request event)
   is dispatched like any request event, its answer POSTed back. Re-polls while the server stands."
  [port]
  (let [step (fn step []
               (when (contains? (:open @channel) port)
                 (-> (js/fetch (channel-url port "poll"))
                     (.then (fn [^js r]
                              (if (= 200 (.-status r))
                                (.then (.text r)
                                       (fn [text]
                                         (dbg! "poll: event text received (" (count text) " chars)")
                                         ;; A throw anywhere in here used to be indistinguishable from a
                                         ;; clean re-poll: the chain's .catch retries the poll either way,
                                         ;; so a wedged event vanished silently (run 3's D2). Name it —
                                         ;; and hold the channel's contract (an event in, a respond
                                         ;; out): the answer path is registered BEFORE the request is
                                         ;; built or dispatched, and a failure there answers 500
                                         ;; through it instead of stranding the event.
                                         (try
                                           (let [e   (reader/read-string text)
                                                 id  (:id e)]
                                             (dbg! "poll: event parsed id=" id " url=" (:url e))
                                             (swap! endpoint assoc-in [:pending id]
                                                    (fn [resp] (respond-origin! port id resp)))
                                             (dbg! "poll: pending registered for id=" id)
                                             (try
                                               (let [req {:method (:method e) :url (:url e)
                                                          :headers (:headers e)
                                                          :body (body->str (nb/from-b64 (:body-b64 e)))
                                                          :body-b64 (:body-b64 e)}]
                                                 (dbg! "poll: req built for id=" id)
                                                 #_{:clj-kondo/ignore [:invalid-arity]} ; eve.http-server is defined per seat: this seat's dispatch! takes the one event map; the scittle seat's takes [event respond] - kondo merges the seats' namespaces and checks this seat's calls against the other seat's def
                                                 (dispatch! {:id id :port port :req req :reply 0})
                                                 (dbg! "poll: dispatch! returned for id=" id))
                                               (catch :default err
                                                 (dbg! "poll: EVENT FAILED for id=" id ": " (err-str err))
                                                 (when-let [respond (get (:pending @endpoint) id)]
                                                   (swap! endpoint update :pending dissoc id)
                                                   (respond {:status 500 :headers {}
                                                             :body (str "eve.http-server: " (err-str err))})))))
                                           (catch :default err
                                             (dbg! "poll: CALLBACK THREW: " (err-str err))))
                                         (step)))
                                (step))))
                     (.catch (fn [e] (dbg! "poll: chain rejected: " (err-str e))
                               (js/setTimeout step 250))))))]
    (step)))

;; The endpoint lives on the page, as the browser http adapter's page hook does (it installs at load under
;; (page?)). Here the page is thread 0 from load — eve.thread-host's state defaults :self to 0 in the heap
;; that has no __eve_thread boot data, i.e. the page — so the pump installs at load on the page, and lazily
;; again (atom-guarded) from any endpoint-side call. (At this pin eve.thread-host has no init-hooks API; the
;; hooks in eve-media's capstone code belong to a newer Eve.)
(defonce ^:private pump-installed (atom false))

(defn- ensure-pump! []
  (when (and (endpoint?) (not @pump-installed))
    (reset! pump-installed true)
    (js/setInterval pump! 10)))

(ensure-pump!)

;; ---------------------------------------------------------------------------
;; The in-page channel's client half: a request event to the endpoint, the
;; answer taken from this thread's own mailbox.
;; ---------------------------------------------------------------------------

(defonce ^:private req-seq (atom 0))

(defn request!
  "Deliver req (a request map, as a handler takes) to the server at port (nil: the page's only server) through
   the endpoint and take its response map. A worker blocks until the answer lands. On the page itself this
   throws, as every wait there does — the main thread cannot wait; the page's own outbound requests ride
   eve.http like any other client's."
  [port req]
  (let [id (str "rq-" (h/self) "-" (swap! req-seq inc))]
    (h/send! 0 {:eve.http-server/request id :port port :req req :reply (h/self)})
    (loop []
      (let [m (h/receive! -1)]
        (if (and (map? m) (= id (:eve.http-server/response m)))
          (:resp m)
          (do (when (map? m) (h/send! (h/self) m)) ; not ours: put it back at the tail of our own mailbox
              (recur)))))))

;; ---------------------------------------------------------------------------
;; serve!
;; ---------------------------------------------------------------------------

(defonce ^:private local-seq (atom 0))

(defn- server-id [] (str "srv-" (h/self) "-" (swap! local-seq inc)))

(defn- register!
  "Register server s at the endpoint: directly in the page's heap, else by control message to thread 0."
  [s]
  (ensure-pump!)
  (if (endpoint?)
    (swap! endpoint assoc-in [:servers (:id s)] (assoc s :rr 0))
    (h/send! 0 {:eve.http-server/control :register :server (:id s) :port (:port s)
                :pool (:pool s) :owner (:owner s)})))

(defn- unregister! [s]
  (if (endpoint?)
    (swap! endpoint update :servers dissoc (:id s))
    (h/send! 0 {:eve.http-server/control :unregister :server (:id s)})))

(defn- open-channel! [port]
  (when (and (some? port) (endpoint?) (not (contains? (:open @channel) port)))
    (swap! channel update :open conj port)
    (poll-channel! port)))

(defn serve!
  "Serve handler as the member's endpoint on its origin host at opts :port (nil: in-page serving only), with
   :threads serve threads in the dispatch pool (default 2; a named handler — a symbol — is required with a
   pool, and on the page). A function handler with no pool serves in the calling heap. {:port :stop}."
  [opts handler]
  (let [port (:port opts)
        threads (get opts :threads 2)
        id (server-id)]
    (cond
      ;; the pool shape (the canonical one): serve threads resolve the handler by name in their own heaps
      (pos? threads)
      (do (when-not (symbol? handler)
            (throw (ex-info "eve.http-server: a dispatch pool runs the handler on serve threads, which resolve it by name: pass the handler as a symbol (or :threads 0 with a function, served in the calling heap)"
                            {:handler handler})))
          (let [addrs (h/start-threads! threads 'eve.http-server/serve-thread {:handler (str handler) :debug (page-debug?)} {})
                s {:id id :port port :pool addrs}]
            (dbg! "serve!: pool " (pr-str addrs) " on port " port)
            (register! s)
            (open-channel! port)
            {:port port
             :stop (fn []
                     (unregister! s)
                     (when (some? port) (swap! channel update :open disj port))
                     (doseq [a addrs] (h/send! a {:eve.http-server/control :stop}))
                     nil)}))

      ;; a function, in a worker: serve in the calling thread until a stop event arrives (the vs shape)
      (and (fn? handler) (not (page?)))
      (let [s {:id id :port port :owner (h/self)}]
        (register! s)
        (loop []
          (let [m (h/receive! -1)]
            (cond
              (= :eve.thread/stop m) nil
              (and (map? m) (= :stop (:eve.http-server/control m))) nil
              (and (map? m) (contains? m :eve.http-server/request))
              (do (h/send! (:reply m) {:eve.http-server/response (:eve.http-server/request m)
                                       :resp (run-handler handler (:req m))})
                  (recur))
              :else (recur))))
        (unregister! s)
        {:port port :stop (fn [] nil)})

      ;; a function, on the page: the endpoint's pump runs it (it must not block — the page's own constraint)
      (fn? handler)
      (let [s {:id id :port port :fn handler}]
        (register! s)
        (open-channel! port)
        {:port port :stop (fn [] (unregister! s)
                            (when (some? port) (swap! channel update :open disj port))
                            nil)})

      :else
      (throw (ex-info "eve.http-server: with :threads 0 the handler must be a function, served in the calling heap; a symbol names a handler for a dispatch pool (:threads above 0)"
                      {:handler handler :threads threads})))))
