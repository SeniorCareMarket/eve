(ns eve.opfs.mount
  "The page's half of eve.opfs (the OPFS disk tier spec, W4: the pool-boot mount): before any eve thread starts, the
   page makes the pool — the io worker's request block and its file slots, growable SharedArrayBuffers — starts
   the io worker (eve/opfs_io.js, inlined) and shares the pool with every worker it creates after
   (eve.thread-host/share!). The io worker takes the root's Web Lock: this tab owns its files, or follows the tab
   that does and takes over when that tab goes (eve.opfs/owner?, on-role!)."
  (:require
   [eve.deftype-proto.wasm :as wasm]
   [eve.opfs :as opfs]
   [eve.opfs.follow :as follow]
   [eve.opfs.relay :as relay]
   [eve.thread-host :as h]
   [shadow.resource :as rc]))

(def ^:private io-js (rc/inline "eve/opfs_io.js"))

;; The relay pool's io worker (W10) is the program's own kernel, loaded with ?eve-opfs-io=1 (a worker from a URL of
;; the site, so eve's service worker controls it — Firefox does not give a worker's Blob-URL worker to it): this
;; namespace then runs the io worker's script in it, before the kernel's own start
(when (and (exists? js/WorkerGlobalScope) (exists? js/location)
           (re-find #"[?&]eve-opfs-io=1(&|$)" (str (.-search js/location))))
  (js/importScripts (.createObjectURL js/URL (js/Blob. #js [io-js] #js {:type "text/javascript"}))))

(defn- relay-io-worker
  "The relay pool's io worker: the kernel this worker was started from, with ?eve-opfs-io=1; from a Blob of the io
   script where this worker's own URL is one (no kernel URL to start)."
  []
  (let [loc (.-location js/self)]
    (if (= "blob:" (.-protocol loc))
      (js/Worker. (.createObjectURL js/URL (js/Blob. #js [io-js] #js {:type "text/javascript"})))
      (js/Worker. (str (.-origin loc) (.-pathname loc) "?eve-opfs-io=1")))))

(def default-opts
  "mount!'s defaults: the OPFS directory eve's paths are under, the pool's files open at once (slots;
   eve.opfs/close-all! lets them go), a file's largest size (the slot's maxByteLength: address space reserved,
   not memory), and the request block's int words (a sync's runs travel in them) and string bytes."
  {:root "eve" :slots 256 :max-file-bytes (* 1024 1024 1024) :args-words (* 64 1024) :string-bytes (* 64 1024)})

(defn- slot-buffers
  "n growable SharedArrayBuffers of max bytes each; nil when the engine will not reserve them all (WebKit reserves
   about 62 GiB of growable buffers a page, measured: 62 x 1 GiB, 248 x 256 MiB)."
  [n max]
  (try (into-array (repeatedly n #(js/SharedArrayBuffer. 0 #js {:maxByteLength max})))
       (catch js/RangeError _ nil)))

(def ^:private min-file-bytes (* 16 1024 1024))

(def ^:private constrained-budget
  "Where the engine caps growable buffers (WebKit, 60 GiB a process: eve.deftype-proto.wasm/webkit?), the pool
   reserves at most this: each eve worker's own slabs reserve ~363 MiB there (wasm/slab-capacity)."
  (* 4 1024 1024 1024))

(defn- make-pool
  "The pool: the request block, the svc block and the slots. A file's largest size is :max-file-bytes where the
   engine reserves every slot so. On WebKit (one budget for every frame and worker of the process) it is
   constrained-budget's share of a slot, reserved at once. Elsewhere, where the engine will not reserve them all, it
   is a quarter of the largest size it does reserve, and at most constrained-budget in all. Either way the pool leaves
   the rest to the page's workers (.maxFileBytes says what it got; fewer :slots, bigger files)."
  [{:keys [slots max-file-bytes args-words string-bytes]}]
  (let [[max bufs] (or
                    ;; WebKit: its share at once. Probing for it there reserves the process's whole budget in buffers
                    ;; thrown away (each size tried until one fails), which count until collected: a worker starting
                    ;; meanwhile could not reserve its slabs (RangeError: Out of memory)
                    (when (wasm/webkit?)
                      (let [m (max min-file-bytes (min max-file-bytes (quot constrained-budget slots)))]
                        (some->> (slot-buffers slots m) (vector m))))
                    (loop [m max-file-bytes]
                      (if-let [b (slot-buffers slots m)]
                        (if (or (== m max-file-bytes) (<= (quot m 4) min-file-bytes))
                          [m b]
                          (let [q (max min-file-bytes (min (quot m 4) (quot constrained-budget slots)))]
                            [q (or (slot-buffers slots q) b)]))
                        (if (> (quot m 2) min-file-bytes)
                          (recur (quot m 2))
                          (throw (ex-info "eve.opfs.mount: the engine reserves no pool of these slots"
                                          {:slots slots :max-file-bytes m}))))))]
    #js {:ctl (js/SharedArrayBuffer. (+ (* 4 (+ opfs/ARGS args-words)) string-bytes))
         :argsWords args-words
         :svc (js/SharedArrayBuffer. (+ 64 (* 4 args-words)))
         :maxFileBytes max
         :slots bufs}))

(defonce ^:private listeners (atom []))   ; the page's on-role! fns: never on the pool, which workers receive (cloned)

(defn on-role!
  "Call (f role term) on the page when this tab's role changes (\"owner\": it took over) or a follower hears of a
   new owner's term (\"follower\")."
  [^js _p f]
  (swap! listeners conj f)
  nil)

(defonce ^:private server (atom nil))

(defn- serve-followers!
  "The owner tab's follower server (eve.opfs.follow/serve!), once."
  []
  (when (compare-and-set! server nil :starting)
    (reset! server (first (h/start-threads! 1 'eve.opfs.follow/serve! {} {})))))

(defonce ^:private relay-host (atom nil))

(defn serve-relay!
  "The owner tab's host on eve's service worker relay (W9: eve.opfs.relay), once, when the service worker controls
   the page: its follower calls go to the io worker, as a channel's do (the tab's follower server answers them).
   mount! starts it; a page whose service worker took control later calls it again. true when it serves."
  []
  (let [^js p (opfs/pool-of)
        ^js io (.-__eve_opfs_io js/globalThis)]
    (when (and p io (= "owner" (.-role p)) (.-mounted p) (nil? @relay-host)
               (some-> (.-serviceWorker js/navigator) .-controller))
      (let [pending (atom {})
            n (atom 0)]
        (.addEventListener io "message"
                           (fn [^js e]
                             (when-let [^js r (some-> e .-data .-eveRelayReply)]
                               (when-let [f (get @pending (.-id r))]
                                 (swap! pending dissoc (.-id r))
                                 (f (or (.-bytes r) (js/Uint8Array. 0)))))))
        (reset! relay-host
                (relay/serve! (relay/host-for (.-root p)) (.-term p)
                              (fn [req]
                                (js/Promise.
                                 (fn [resolve _]
                                   (let [id (swap! n inc)]
                                     (swap! pending assoc id resolve)
                                     (.postMessage io #js {:eveRelayCall #js {:id id :bytes req}})))))))))
    (some? @relay-host)))

(defn- tell! [^js p role term]
  (set! (.-role p) role)
  (set! (.-term p) term)
  (when (and (= "owner" role) (.-mounted p)) (serve-followers!) (serve-relay!))   ; before the mount, ready starts them
  (doseq [f @listeners] (f role term)))

(defn- mount-relay!
  "The relay pool (W10), in the one worker of a page that is not cross-origin isolated (the domain host): its own
   request block and slots, and its own io worker, reached over eve's service worker relay. SharedArrayBuffer
   becomes the global (eve.opfs/sab-constructor) so eve's memory works in this worker. When this tab owns the files
   it serves follower calls (eve.opfs.follow/serve-relay!)."
  [{:keys [root slots max-file-bytes args-words string-bytes]}]
  (let [C (opfs/sab-constructor)
        _ (when-not (exists? js/SharedArrayBuffer) (set! (.-SharedArrayBuffer js/globalThis) C))
        tab (str (js/Date.now) "-" (.toString (rand-int 0x7fffffff) 36))
        p #js {:relay (str "eve-io:" tab) :ctl (C. (+ (* 4 (+ opfs/ARGS args-words)) string-bytes))
               :argsWords args-words :slots #js [] :shadows #js [] :lens #js [] :maxFileBytes max-file-bytes
               :tab tab :root root}
        w (relay-io-worker)
        serving (atom nil)
        serve! (fn [] (when (and (= "owner" (.-role p)) (nil? @serving))
                        (reset! serving (follow/serve-relay! (relay/host-for root) (.-term p)))))]
    (set! (.-__eve_opfs_io js/globalThis) w)
    (js/Promise.
     (fn [resolve reject]
       (set! (.-onmessage w)
             (fn [e]
               (let [d (.-data e)]
                 (when (.-eveOpfsReady d)
                   (set! (.-mode p) (.-mode d))
                   (set! (.-role p) (.-role d))
                   (set! (.-term p) (.-term d))
                   (opfs/use-pool! p)
                   (opfs/note-role! (= "owner" (.-role d)) (.-term d))
                   (set! (.-mounted p) true)
                   (serve!)
                   (resolve p))
                 (when (.-eveOpfsRole d)
                   (set! (.-role p) (.-eveOpfsRole d))
                   (set! (.-term p) (.-term d))
                   (when (.-mounted p) (opfs/note-role! true (.-term d)) (serve!))
                   (doseq [f @listeners] (f (.-role p) (.-term p))))
                 (when (false? (.-eveOpfsReady d))
                   (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-error d))))))))
       (set! (.-onerror w) (fn [e] (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-message e))))))
       (.postMessage w #js {:eveOpfsBoot true :relay (.-relay p) :ownerHost (relay/host-for root)
                            :argsWords args-words :stringBytes string-bytes :slotCount slots
                            :maxFileBytes max-file-bytes :root root :tab tab})))))

(defn- relay-context?
  "A worker of a page that is not cross-origin isolated: the relay pool's place."
  []
  (and (exists? js/WorkerGlobalScope) (not (true? js/crossOriginIsolated))))

(declare mount-page!)

(defn mount!
  "On the page, before any eve thread starts: make the pool and start its io worker over the OPFS directory
   (:root opts). A promise of the pool, shared (eve.thread-host/share!) with every worker the page starts after it
   resolves — its .role \"owner\" or \"follower\", its .term — rejected when the io worker cannot open OPFS.
   In a worker of a page that is not cross-origin isolated (W10), the relay pool instead: that worker alone holds
   eve's memory and its files (mount-relay!)."
  ([] (mount! {}))
  ([opts]
   (if (relay-context?)
     (mount-relay! (merge default-opts opts))
     (mount-page! opts))))

(defn- mount-page!
  ([opts]
   (let [opts (merge default-opts opts)
         p    (make-pool opts)
         url  (.createObjectURL js/URL (js/Blob. #js [io-js] #js {:type "text/javascript"}))
         w    (js/Worker. url)
         tab  (str (js/Date.now) "-" (.toString (rand-int 0x7fffffff) 36))]
     (set! (.-tab p) tab)
     (set! (.-root p) (:root opts))
     (js/Promise.
      (fn [resolve reject]
        (set! (.-onmessage w)
              (fn [e]
                (let [d (.-data e)]
                  (when (.-eveOpfsReady d)
                    (set! (.-mode p) (.-mode d))
                    (set! (.-role p) (.-role d))
                    (set! (.-term p) (.-term d))
                    (.revokeObjectURL js/URL url)
                    (opfs/use-pool! p)
                    (h/share! "eve.opfs" p)
                    (set! (.-mounted p) true)
                    (when (= "owner" (.-role p)) (serve-followers!) (serve-relay!))
                    (when-let [sw (.-serviceWorker js/navigator)]
                      (.addEventListener sw "controllerchange" (fn [_] (serve-relay!))))
                    (resolve p))
                  (when (.-eveOpfsRole d) (tell! p (.-eveOpfsRole d) (.-term d)))
                  (when (.-eveOpfsOwner d) (tell! p "follower" (.-term d)))
                  (when (false? (.-eveOpfsReady d))
                    (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-error d))))))))
        (set! (.-onerror w) (fn [e] (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-message e))))))
        (set! (.-__eve_opfs_io js/globalThis) w)
        (.postMessage w #js {:eveOpfsBoot true :ctl (.-ctl p) :argsWords (.-argsWords p)
                             :slots (.-slots p) :svc (.-svc p) :root (:root opts) :tab tab}))))))
