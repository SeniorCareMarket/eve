(ns eve.opfs.relay
  "The follower relay (the OPFS disk tier spec, W9): calls to a root's owner through eve's service worker
   (eve-sw.js, its /eve-sw/opfs/ routes), for a worker that cannot reach the owner through shared memory — a page
   that is not cross-origin isolated (W10), or any worker told to (eve.opfs.follow/use-relay!).

   A caller (a worker: call! blocks) posts its request in a synchronous XHR the service worker holds until the host's
   reply comes. The host (serve!, async, on a page or worker the service worker controls) long-polls the service
   worker for the calls of its name and posts each one's reply. A call is sent again under its own key when the
   service worker gave up waiting (504), so it is queued once and answered once; one the service worker lost (the
   browser stopped it) is queued again, and its request id keeps the owner from applying it twice
   (eve.atom/*request-id*).

   A browser may send a worker's synchronous XHR to the network instead of its service worker (eve-sw.js notes
   Firefox; Firefox 159, Chromium 156 and WebKit 2388 relay them from eve's threads, measured): relayed? says whether
   this worker's do.")

(def ^:private bytes-type "application/octet-stream")

(defn- url
  "path on this origin: an eve thread's own location is a blob: URL, which a path does not resolve against."
  [path]
  (str (.-origin js/location) path))

(defn host-for
  "The relay name of the owner of the OPFS root (eve.opfs.mount/mount!'s :root)."
  [root]
  (str "eve:" root))

(defn relayed?
  "Do this worker's synchronous XHRs reach eve's service worker?"
  []
  (try (let [x (js/XMLHttpRequest.)]
         (.open x "GET" (url "/eve-sw/opfs/ping") false)
         (.send x)
         (= "1" (.getResponseHeader x "X-Eve-Sw")))
       (catch :default _ false)))

(defonce ^:private me (str (.toString (js/Math.floor (* (js/Math.random) 1e12)) 36) "-" (js/Date.now)))
(defonce ^:private seq-no (volatile! 0))

(defn call!
  "In a worker: send request (a Uint8Array) to host and block until its reply (a Uint8Array; empty when the host
   could not serve it: it does not own the root). Throws (ex-data :eve.opfs/refused :relay-timeout) when no host
   answered within :timeout-ms (default 30000), :relay-unreachable when the service worker cannot be reached."
  ([host request] (call! host request {}))
  ([host ^js request {:keys [timeout-ms] :or {timeout-ms 30000}}]
   (let [k (str me "." (vswap! seq-no inc))
         deadline (+ (js/Date.now) timeout-ms)
         body (.slice request)]
     (loop [lost 0]
       (let [left (- deadline (js/Date.now))]
         (when (<= left 0)
           (throw (ex-info (str "eve.opfs.relay: no host " host " answered within " timeout-ms " ms")
                           {:eve.opfs/refused :relay-timeout :host host})))
         (let [x (js/XMLHttpRequest.)
               status (try (.open x "POST" (url (str "/eve-sw/opfs/call?to=" (js/encodeURIComponent host) "&k=" k
                                                     "&ms=" (min 25000 left)))
                                  false)
                           (set! (.-responseType x) "arraybuffer")
                           (.send x body)
                           (.-status x)
                           (catch :default _ 0))]
           (cond
             (= 200 status) (js/Uint8Array. (.-response x))
             (= 504 status) (recur lost)
             (< lost 4) (recur (inc lost))
             :else (throw (ex-info "eve.opfs.relay: the service worker does not relay this worker's calls"
                                   {:eve.opfs/refused :relay-unreachable :host host :status status})))))))))

(defn serve!
  "Serve host's calls (an async loop, on a page or worker eve's service worker controls): (handle request) is the
   reply — a Uint8Array, or a promise of one — for each request (a Uint8Array), one at a time. term orders hosts of
   one name: a call a host of an older term took and never answered goes to this one. Answers a fn that stops it."
  [host term handle]
  (let [stopped (volatile! false)
        q (url (str "/eve-sw/opfs/next?host=" (js/encodeURIComponent host) "&term=" term "&ms=25000"))]
    (letfn [(again [ms] (when-not @stopped (js/setTimeout poll ms)))
            (answer! [k reply]
              (js/fetch (url (str "/eve-sw/opfs/reply?k=" (js/encodeURIComponent k)))
                        #js {:method "POST" :body (or reply (js/Uint8Array. 0))
                             :headers #js {"Content-Type" bytes-type}}))
            (poll []
              (-> (js/fetch q #js {:cache "no-store"})
                  (.then (fn [^js r]
                           (if (= 200 (.-status r))
                             (let [k (.get (.-headers r) "X-Eve-Key")]
                               (-> (.arrayBuffer r)
                                   (.then (fn [b] (handle (js/Uint8Array. b))))
                                   (.catch (fn [_] (js/Uint8Array. 0)))
                                   (.then (fn [reply] (answer! k reply)))
                                   (.then (fn [_] (again 0)))))
                             (again 0))))
                  (.catch (fn [_] (again 500)))))]
      (again 0)
      (fn [] (vreset! stopped true) nil))))
