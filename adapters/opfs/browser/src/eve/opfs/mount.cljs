(ns eve.opfs.mount
  "The page's half of eve.opfs (the OPFS disk tier spec, W4: the pool-boot mount): before any eve thread starts, the
   page makes the pool — the io worker's request block and its file slots, growable SharedArrayBuffers — starts
   the io worker (eve/opfs_io.js, inlined) and shares the pool with every worker it creates after
   (eve.thread-host/share!). The io worker takes the root's Web Lock: this tab owns its files, or follows the tab
   that does and takes over when that tab goes (eve.opfs/owner?, on-role!)."
  (:require
   [eve.opfs :as opfs]
   [eve.thread-host :as h]
   [shadow.resource :as rc]))

(def ^:private io-js (rc/inline "eve/opfs_io.js"))

(def default-opts
  "mount!'s defaults: the OPFS directory eve's paths are under, the pool's files open at once (slots;
   eve.opfs/close-all! lets them go), a file's largest size (the slot's maxByteLength: address space reserved,
   not memory), and the request block's int words (a sync's runs travel in them) and string bytes."
  {:root "eve" :slots 256 :max-file-bytes (* 1024 1024 1024) :args-words (* 64 1024) :string-bytes (* 64 1024)})

(defn- make-pool [{:keys [slots max-file-bytes args-words string-bytes]}]
  #js {:ctl (js/SharedArrayBuffer. (+ (* 4 (+ opfs/ARGS args-words)) string-bytes))
       :argsWords args-words
       :slots (into-array (repeatedly slots #(js/SharedArrayBuffer. 0 #js {:maxByteLength max-file-bytes})))})

(defonce ^:private listeners (atom []))   ; the page's on-role! fns: never on the pool, which workers receive (cloned)

(defn on-role!
  "Call (f role term) on the page when this tab's role changes (\"owner\": it took over) or a follower hears of a
   new owner's term (\"follower\")."
  [^js _p f]
  (swap! listeners conj f)
  nil)

(defn- tell! [^js p role term]
  (set! (.-role p) role)
  (set! (.-term p) term)
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
                    (resolve p))
                  (when (.-eveOpfsRole d) (tell! p (.-eveOpfsRole d) (.-term d)))
                  (when (.-eveOpfsOwner d) (tell! p "follower" (.-term d)))
                  (when (false? (.-eveOpfsReady d))
                    (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-error d))))))))
        (set! (.-onerror w) (fn [e] (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-message e))))))
        (set! (.-__eve_opfs_io js/globalThis) w)
        (.postMessage w #js {:eveOpfsBoot true :ctl (.-ctl p) :argsWords (.-argsWords p)
                             :slots (.-slots p) :root (:root opts) :tab tab}))))))
