(ns eve.opfs
  "eve's files in a browser: the origin private file system (OPFS), the OPFS disk
   tier spec's W3 (the io worker and its client).

   OPFS reads and writes a file synchronously only through a
   FileSystemSyncAccessHandle, which is exclusive and lives in the one worker that
   opened it. So the page starts one io worker (mount!, before any eve thread:
   the pool-boot mount) that opens every file, and every worker reaches it
   through one request block in a SharedArrayBuffer — a lock word, a doorbell, a
   done word (eve/opfs_io.js). A file's bytes live in a pool slot, a growable
   SharedArrayBuffer every worker received at its start, so the region a worker
   opens (eve.region.opfs) reads and writes the file's bytes in place, with
   Atomics, and only its syncs go to the io worker.

   A request blocks its caller (Atomics.wait): a worker's call. The page's main
   thread starts the pool and never opens a file."
  (:require
   [eve.region.opfs :as ropfs]
   [eve.thread-host :as h]
   [shadow.resource :as rc]))

;; The request block (eve/opfs_io.js's words)
(def ^:private LOCK 0)
(def ^:private DOORBELL 1)
(def ^:private DONE 2)
(def ^:private OP 3)
(def ^:private STATUS 4)
(def ^:private R0 5)
(def ^:private R1 6)
(def ^:private NARGS 8)
(def ^:private NSTR 9)
(def ^:private ARGS 16)

(def ^:private ops
  {:open 1 :persist 2 :exists 3 :size 4 :create-exclusive 5 :rename 6 :delete 7 :close-all 8 :mkdirs 9})

(def ^:private io-js (rc/inline "eve/opfs_io.js"))

(def default-opts
  "mount!'s defaults: the OPFS directory eve's paths are under, the pool's
   files (slots: a slot is never reused in a page's life), a file's largest
   size (the slot's maxByteLength: address space reserved, not memory), and the
   request block's int words (a sync's runs travel in them)."
  {:root "eve" :slots 128 :max-file-bytes (* 1024 1024 1024) :args-words (* 64 1024) :string-bytes (* 64 1024)})

(defonce ^:private pool (atom nil))

(defn available?
  "true where this context can hold the pool: shared memory (cross-origin
   isolated) and OPFS."
  []
  (boolean (and (exists? js/SharedArrayBuffer)
                (exists? js/navigator) (.-storage js/navigator)
                (.-getDirectory (.-storage js/navigator)))))

(defn- make-pool [{:keys [slots max-file-bytes args-words string-bytes]}]
  (let [dmax (ropfs/dirty-map-bytes max-file-bytes)]
    #js {:ctl (js/SharedArrayBuffer. (+ (* 4 (+ ARGS args-words)) string-bytes))
         :argsWords args-words
         :slots (into-array (repeatedly slots #(js/SharedArrayBuffer. 0 #js {:maxByteLength max-file-bytes})))
         :dirty (into-array (repeatedly slots #(js/SharedArrayBuffer. 4 #js {:maxByteLength dmax})))}))

(defn mount!
  "On the page, before any eve thread starts: make the pool and start its io
   worker over OPFS directory (:root opts). A promise of the pool, shared (eve.thread-host's share!) with every
   worker the page starts after it resolves; rejected when the io worker cannot open OPFS."
  ([] (mount! {}))
  ([opts]
   (let [opts (merge default-opts opts)
         p    (make-pool opts)
         url  (.createObjectURL js/URL (js/Blob. #js [io-js] #js {:type "text/javascript"}))
         w    (js/Worker. url)]
     (js/Promise.
      (fn [resolve reject]
        (set! (.-onmessage w)
              (fn [e]
                (let [d (.-data e)]
                  (when (.-eveOpfsReady d)
                    (set! (.-mode p) (.-mode d))
                    (.revokeObjectURL js/URL url)
                    (reset! pool p)
                    (h/share! "eve.opfs" p)
                    (resolve p))
                  (when (false? (.-eveOpfsReady d)) (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-error d))))))))
        (set! (.-onerror w) (fn [e] (reject (js/Error. (str "eve.opfs: the io worker failed: " (.-message e))))))
        (set! (.-__eve_opfs_io js/globalThis) w)
        (.postMessage w #js {:eveOpfsBoot true :ctl (.-ctl p) :argsWords (.-argsWords p)
                             :slots (.-slots p) :dirty (.-dirty p) :root (:root opts)}))))))

(defn pool-of
  "This context's pool, or nil (no mount): the page's own, or (in a worker) the
   one its start carried (eve.thread-host's share!)."
  []
  (or @pool (when-let [p (h/shared-of "eve.opfs")] (reset! pool p))))

(defn- the-pool []
  (or (pool-of) (throw (ex-info "eve.opfs: no OPFS pool here (the page mounts it before its threads start)" {}))))

(defn handle-mode
  "The io worker's sync access handle mode: \"readwrite-unsafe\" where the
   browser has it (Chromium 121+: handles do not lock their file), else
   \"readwrite\" (exclusive); nil with no pool."
  []
  (some-> (pool-of) .-mode))

(def ^:private encoder (js/TextEncoder.))
(def ^:private decoder (js/TextDecoder.))

(defn- lock! [ctl]
  (loop []
    (when-not (zero? (js/Atomics.compareExchange ctl LOCK 0 1))
      (js/Atomics.wait ctl LOCK 1 100)
      (recur))))

(defn- unlock! [ctl]
  (js/Atomics.store ctl LOCK 0)
  (js/Atomics.notify ctl LOCK 1))

(defn- request!
  "Send op to the io worker and block for its answer: [r0 r1]; throws its error.
   args: int words (a JS array or nil); strs: strings (joined by NUL)."
  [op ^js args strs]
  (let [^js p   (the-pool)
        ^js ctl (js/Int32Array. (.-ctl p))
        bytes   (js/Uint8Array. (.-ctl p))
        nargs   (if args (.-length args) 0)
        sbytes  (.encode encoder (apply str (interpose "\u0000" strs)))
        sbase   (* 4 (+ ARGS (.-argsWords p)))]
    (when (> nargs (.-argsWords p))
      (throw (ex-info "eve.opfs: a request's args exceed the request block" {:words nargs})))
    (when (> (.-length sbytes) (- (.-length bytes) sbase))
      (throw (ex-info "eve.opfs: a request's strings exceed the request block" {:bytes (.-length sbytes)})))
    (lock! ctl)
    (try
      (aset ctl OP (get ops op))
      (aset ctl NARGS nargs)
      (dotimes [i nargs] (aset ctl (+ ARGS i) (aget args i)))
      (aset ctl NSTR (.-length sbytes))
      (.set bytes sbytes sbase)
      (js/Atomics.store ctl DONE 0)
      (js/Atomics.add ctl DOORBELL 1)
      (js/Atomics.notify ctl DOORBELL)
      (loop []
        (when (zero? (js/Atomics.load ctl DONE))
          (js/Atomics.wait ctl DONE 0 1000)
          (recur)))
      (if (zero? (aget ctl STATUS))
        [(aget ctl R0) (aget ctl R1)]
        (let [n (aget ctl NSTR)]
          (throw (ex-info (str "eve.opfs: " (.decode decoder (.slice bytes sbase (+ sbase n))))
                          {:op op :strs (vec strs)}))))
      (finally (unlock! ctl)))))

;; The store a region syncs through: a request per batch of runs the block holds
(deftype IoStore []
  ropfs/IRegionStore
  (-persist! [_ slot runs]
    (let [cap (- (.-argsWords ^js (the-pool)) 1)
          cap (- cap (mod cap 2))
          n   (.-length runs)]
      (loop [i 0]
        (when (< i n)
          (let [j (min n (+ i cap))
                a (.slice runs i j)]
            (.unshift a slot)
            (request! :persist a nil)
            (recur j)))))))

(def ^:private io-store (IoStore.))

(defn open-region
  "The region of size bytes over the file at path (created when absent, grown
   to size when shorter): an eve.region.opfs region over the file's pool slot."
  [path size]
  (let [[slot _len] (request! :open #js [size] [path])
        ^js p (the-pool)]
    (ropfs/opfs-region (aget (.-slots p) slot) (aget (.-dirty p) slot) size io-store slot)))

(defn file-size
  "The file's byte length, or nil when there is none."
  [path]
  (let [[n] (request! :size #js [] [path])] (when-not (neg? n) n)))

(defn file-exists? [path] (= 1 (first (request! :exists #js [] [path]))))

(defn create-exclusive!
  "Create an empty file at path when none is there; true when this call made it."
  [path]
  (= 1 (first (request! :create-exclusive #js [] [path]))))

(defn rename-file! [from to] (request! :rename #js [] [from to]) nil)

(defn delete-file!
  "Delete the (closed) file at path; true when there was one."
  [path]
  (= 1 (first (request! :delete #js [] [path]))))

(defn ensure-dirs! [dir] (request! :mkdirs #js [] [dir]) nil)

(defn close-all!
  "Flush and close every file the io worker holds (a test's restart: the pool's
   slots are not reused, so regions opened before stay over stale bytes)."
  []
  (request! :close-all #js [] nil)
  nil)
