(ns eve.opfs.mount
  "The page's half of eve.opfs (the OPFS disk tier spec, W4: the pool-boot mount): before any eve thread starts, the
   page makes the pool — the io worker's request block and its file slots, growable SharedArrayBuffers — starts
   the io worker (eve/opfs_io.js, inlined) and shares the pool with every worker it creates after
   (eve.thread-host/share!). The io worker takes the root's Web Lock: this tab owns its files, or follows the tab
   that does and takes over when that tab goes (eve.opfs/owner?, on-role!)."
  (:require
   [eve.opfs :as opfs]
   [eve.opfs.follow]
   [eve.thread-host :as h]
   [shadow.resource :as rc]))

(def ^:private io-js (rc/inline "eve/opfs_io.js"))

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
  "Where the engine caps growable buffers (WebKit, ~62 GiB a process), the pool reserves at most this: each eve
   worker's own slabs reserve ~5 GB there (eve.deftype-proto.wasm/create-slab-memory)."
  (* 4 1024 1024 1024))

(defn- make-pool
  "The pool: the request block, the svc block and the slots. A file's largest size is :max-file-bytes where the
   engine reserves every slot so; where it does not (WebKit: one budget for every frame and worker of the process),
   a quarter of the largest size it does reserve, and at most constrained-budget in all, so the pool leaves the rest
   to the page's workers (.maxFileBytes says what it got; fewer :slots, bigger files)."
  [{:keys [slots max-file-bytes args-words string-bytes]}]
  (let [[max bufs] (loop [m max-file-bytes]
                     (if-let [b (slot-buffers slots m)]
                       (if (or (== m max-file-bytes) (<= (quot m 4) min-file-bytes))
                         [m b]
                         (let [q (max min-file-bytes (min (quot m 4) (quot constrained-budget slots)))]
                           [q (or (slot-buffers slots q) b)]))
                       (if (> (quot m 2) min-file-bytes)
                         (recur (quot m 2))
                         (throw (ex-info "eve.opfs.mount: the engine reserves no pool of these slots"
                                         {:slots slots :max-file-bytes m})))))]
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

(defn- tell! [^js p role term]
  (set! (.-role p) role)
  (set! (.-term p) term)
  (when (and (= "owner" role) (.-mounted p)) (serve-followers!))   ; before the mount, ready starts it
  (doseq [f @listeners] (f role term)))

(defn mount!
  "On the page, before any eve thread starts: make the pool and start its io worker over the OPFS directory
   (:root opts). A promise of the pool, shared (eve.thread-host/share!) with every worker the page starts after it
   resolves — its .role \"owner\" or \"follower\", its .term — rejected when the io worker cannot open OPFS."
  ([] (mount! {}))
  ([opts]
   (let [opts (merge default-opts opts)
         p    (make-pool opts)
         url  (.createObjectURL js/URL (js/Blob. #js [io-js] #js {:type "text/javascript"}))
         w    (js/Worker. url)
         tab  (str (js/Date.now) "-" (.toString (rand-int 0x7fffffff) 36))]
     (set! (.-tab p) tab)
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
                    (when (= "owner" (.-role p)) (serve-followers!))
                    (resolve p))
                  (when (.-eveOpfsRole d) (tell! p (.-eveOpfsRole d) (.-term d)))
                  (when (.-eveOpfsOwner d) (tell! p "follower" (.-term d)))
                  (when (false? (.-eveOpfsReady d))
                    (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-error d))))))))
        (set! (.-onerror w) (fn [e] (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-message e))))))
        (set! (.-__eve_opfs_io js/globalThis) w)
        (.postMessage w #js {:eveOpfsBoot true :ctl (.-ctl p) :argsWords (.-argsWords p)
                             :slots (.-slots p) :svc (.-svc p) :root (:root opts) :tab tab}))))))
