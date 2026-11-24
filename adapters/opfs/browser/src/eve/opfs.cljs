(ns eve.opfs
  "eve's files in a browser: the origin private file system (OPFS), the OPFS disk
   tier spec's W3 (the io worker and its client).

   OPFS reads and writes a file synchronously only through a
   FileSystemSyncAccessHandle, which is exclusive and lives in the one worker that
   opened it. So the page starts one io worker (eve.opfs.mount/mount!, before any
   eve thread: the pool-boot mount) that opens every file, and every worker reaches it
   through one request block in a SharedArrayBuffer — a lock word, a doorbell, a
   done word (eve/opfs_io.js). A file's bytes live in a pool slot, a growable
   SharedArrayBuffer every worker received at its start, so the region a worker
   opens (eve.region.opfs) reads and writes the file's bytes in place, with
   Atomics, and only its syncs go to the io worker (which writes the pages whose
   bytes changed since it last wrote them).

   A request blocks its caller (Atomics.wait): a worker's call. The page's main
   thread starts the pool (eve.opfs.mount) and never opens a file."
  (:require
   [eve.region :as region]
   [eve.region.opfs :as ropfs]))

;; The request block (eve/opfs_io.js's words)
(def ^:private LOCK 0)
(def ^:private DOORBELL 1)
(def ^:private DONE 2)
(def ^:private OP 3)
(def ^:private STATUS 4)
(def ^:private R0 5)
(def ^:private R1 6)
(def ^:private R2 7)
(def ^:private NARGS 8)
(def ^:private NSTR 9)
(def ^:private ROLE 10)
(def ^:private TERM 11)
(def ^:private SEEN-TERM 12)
(def ^:private COMMITS 13)
(def ^:private SEQ 14)
(def ARGS
  "The request block's first arg word (its header words precede it): eve.opfs.mount sizes the block by it."
  16)

(def ^:private ops
  {:open 1 :persist 2 :exists 3 :size 4 :create-exclusive 5 :rename 6 :delete 7 :close-all 8 :mkdirs 9
   :persist-changed 10 :follow-call 11})

(defonce ^:private pool (atom nil))

(defn available?
  "true where this context can hold the pool: shared memory (cross-origin
   isolated) and OPFS."
  []
  (boolean (and (exists? js/SharedArrayBuffer)
                (exists? js/navigator) (.-storage js/navigator)
                (.-getDirectory (.-storage js/navigator)))))

(defn use-pool!
  "This context's pool is p (eve.opfs.mount/mount! on the page)."
  [p]
  (reset! pool p))

(defn pool-of
  "This context's pool, or nil (no mount): the page's own, or (in a worker) the one its start carried. The worker's
   is what eve.opfs.mount shared under \"eve.opfs\" (eve.thread-host/share!), read here from the init data
   eve.thread-host's bootstrap sets on the worker's global scope (self.__eve_thread.shared), as
   eve.thread-host/shared-of reads it: this namespace is below eve.thread-host (eve.process requires it), so it
   cannot require it."
  []
  (or @pool
      (when-let [p (some-> (when (exists? js/self) (.-__eve_thread js/self)) .-shared (aget "eve.opfs"))]
        (reset! pool p))))

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
  "Send op to the io worker and block for its answer: [r0 r1 r2]; throws its error.
   args: int words (a JS array or nil); strs: strings (joined by NUL), or raw bytes (a Uint8Array) for the string
   area. With a reply-bytes atom, the string area's bytes after the answer are put there."
  ([op args strs] (request! op args strs nil))
  ([op ^js args strs reply]
   (let [^js p   (the-pool)
         ^js ctl (js/Int32Array. (.-ctl p))
         bytes   (js/Uint8Array. (.-ctl p))
         nargs   (if args (.-length args) 0)
         sbytes  (if (instance? js/Uint8Array strs) strs (.encode encoder (apply str (interpose "\u0000" strs))))
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
       (let [status (aget ctl STATUS)]
         (if (zero? status)
           (do (when reply (reset! reply (.slice bytes sbase (+ sbase (aget ctl NSTR)))))
               [(aget ctl R0) (aget ctl R1) (aget ctl R2)])
           (let [n (aget ctl NSTR)]
             (throw (ex-info (str "eve.opfs: " (.decode decoder (.slice bytes sbase (+ sbase n))))
                             (cond-> {:op op}
                               (not (instance? js/Uint8Array strs)) (assoc :strs (vec strs))
                               (= 2 status) (assoc ::not-owner true)
                               (= 3 status) (assoc ::no-owner true)))))))
       (finally (unlock! ctl))))))

;; Ownership (W7): one tab owns a root's files (eve/opfs_io.js holds the Web Lock); a follower tab's requests throw
;; (ex-data ::not-owner) until the owner's tab goes and this tab's io worker takes over
(defn- ctl-word [i]
  (when-let [^js p (pool-of)] (js/Atomics.load (js/Int32Array. (.-ctl p)) i)))

(defn owner?
  "true when this tab owns the files (its io worker holds the root's Web Lock)."
  []
  (= 1 (ctl-word ROLE)))

(defn term
  "The term this tab owns the files in (it goes up at each takeover), or the latest owner's term a follower has
   heard of (0 before any); nil with no pool."
  []
  (if (owner?) (ctl-word TERM) (ctl-word SEEN-TERM)))

(defn not-owner?
  "true when e is a request a follower tab's io worker refused."
  [e]
  (true? (::not-owner (ex-data e))))

(defn await-owner!
  "Block (a worker's call) until this tab owns the files or ms pass; true when it does: a follower's workers wait here
   for the takeover, then open their domains (a domain's fresh root is recovered: eve.atom)."
  [ms]
  (let [^js ctl (js/Int32Array. (.-ctl ^js (the-pool)))
        deadline (+ (js/Date.now) ms)]
    (loop []
      (let [role (js/Atomics.load ctl ROLE)
            left (- deadline (js/Date.now))]
        (cond (= 1 role) true
              (not (pos? left)) false
              :else (do (js/Atomics.wait ctl ROLE role (min left 1000)) (recur)))))))

(defn no-owner?
  "true when e is a follower's call no owner answered (no owner tab, or one taking over)."
  [e]
  (true? (::no-owner (ex-data e))))

(defn call-owner!
  "A follower tab's call (a worker's): request bytes to the owner tab's follower server (eve.opfs.follow), its
   reply bytes back; throws (no-owner?) when no owner answers in time, (not-owner?) when this tab is the owner."
  [^js req]
  (let [reply (atom nil)]
    (request! :follow-call #js [] req reply)
    @reply))

(defn commits
  "How many owner commits this follower tab has heard of (announced on the channel); an owner's own count of file
   writes. A value a follower read before the count moved may be stale."
  []
  (ctl-word COMMITS))

(defn next-seq!
  "This tab's next request sequence number (its workers share it)."
  []
  (js/Atomics.add (js/Int32Array. (.-ctl ^js (the-pool))) SEQ 1))

(defn tab-id
  "This tab's id (eve.opfs.mount made it), or nil with no pool."
  []
  (some-> (pool-of) .-tab))

(defn root
  "The OPFS root this tab's pool is over (eve.opfs.mount/mount!'s :root), or nil with no pool."
  []
  (some-> (pool-of) .-root))

(defn svc-block
  "The owner's svc block (eve/opfs_io.js's S_ words): the follower server's (eve.opfs.follow)."
  []
  (.-svc ^js (the-pool)))

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
            (request! :persist a #js [])
            (recur j))))))
  (-persist-changed! [_ slot]
    (request! :persist-changed #js [slot] #js [])))

(def ^:private io-store (IoStore.))

(defn open-region
  "The region of size bytes over the file at path (created when absent, grown
   to size when shorter): an eve.region.opfs region over the file's pool slot,
   fresh (eve.mem/fresh-region?) when this open read the file in from OPFS."
  [path size]
  (let [[slot _len fresh] (request! :open #js [size] [path])
        ^js p (the-pool)]
    (ropfs/opfs-region (aget (.-slots p) slot) size io-store slot (== 1 fresh))))

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
  "Flush and close every file the io worker holds and let their slots go (a test's restart): a region opened before
   must not be used after — its slot is the next file's."
  []
  (request! :close-all #js [] nil)
  nil)

;; Every file region of this program opens here (eve.region/open-mmap-region): a browser maps no file
(region/register-file-opener! (fn [path size _opts] (open-region path size)))
