(ns eve.thread-host
  "eve's thread feature (HOST-CONTRACT §11) over a browser's Web Workers (compiled ClojureScript).

   A thread is a dedicated worker. Heaps are separate, so a message is data (EDN): closures never travel
   (ships-closures? false), and a host object in a message is refused at the sender. A browser has no synchronous
   receive on a MessagePort (Node's receiveMessageOnPort), so a worker blocked in Atomics.wait could never read one:
   every thread's mailbox is a ring in one SharedArrayBuffer every thread holds (the page must be cross-origin
   isolated, COOP + COEP) — a lock word, a head and a tail per ring, the tail being the word its owner waits on. A
   thread is reachable by every thread from the moment it has an address: there is no mesh to wire.

   The page's main thread cannot block (Atomics.wait throws there): (driving) declares :block :never on it, and its
   receive! polls (0) and refuses a wait. Workers block.

   How a worker gets the program's code — the later cljs-thread lineage's fat-kernel route: a tiny Blob bootstrap
   receives its init data, sets it on its global scope and importScripts the kernel, whose URL the page finds from
   the build's manifest.edn (probed beside each <script src>, by synchronous XHR) and hands down, so a worker's own
   threads start the same way. The kernel is the program itself (a build whose names stay global: :simple, no output
   wrapper); its entry point calls run-thread! first: in a thread this adapter started, it runs that thread's entry.

   Workers are created by the page. A dedicated worker's own worker starts through its parent's event loop, which a
   blocked worker never turns (measured: the conformance suite, run in a worker, stalled at its first start); so a
   worker's start-threads! takes the addresses at once and hands the creation to the page through a spawn ring the
   page pumps from its event loop (Atomics.waitAsync where the browser has it) — the page must not hog its event
   loop for ever. (The later cljs-thread lineage had the screen spawn every worker for the same reason.)

   A worker's end is never silent: the worker itself sends its starter {:eve.thread/exit addr :reason r} when its
   entry returns or throws; the Worker's error event (on the page's event loop) is the backstop."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]))

;; The mailboxes: a ring of RING bytes for each of MAX threads, and the page's spawn ring (SPAWN), after a control
;; area of Int32 words. A thread's id is never reused, so MAX - 1 is the threads a page starts in its life: 63 were
;; spent by the conformance suite and eve.thread's battery before fold's suite started its pools ("thread limit",
;), so 127 (a 33 MB buffer). Recycling ids — a mailbox freed once its thread has ended and drained — is the
;; real fix, and the browser surface's .
(def ^:private MAX 128)
(def ^:private SPAWN MAX)
(def ^:private RING (* 256 1024))
(def ^:private NEXT-ID 0)
(def ^:private SPAWN-MS 1)
(def ^:private CTL-WORDS (+ 8 (* 4 (inc MAX))))
(defn- lock-word [id] (+ 8 (* 4 id)))
(defn- head-word [id] (+ 9 (* 4 id)))
(defn- tail-word [id] (+ 10 (* 4 id)))
(defn- reported-word [id] (+ 11 (* 4 id)))
(defn- ring-base [id] (+ (* 4 CTL-WORDS) (* RING id)))

(defn- init-data [] (when (exists? js/self) (.-__eve_thread js/self)))

(defn- ours? [] (some? (init-data)))

(defn- can-block? [] (not (exists? js/document)))

(defn- shared [sab] {:sab sab :ctl (js/Int32Array. sab 0 CTL-WORDS) :bytes (js/Uint8Array. sab)})

(def ^:private state
  (atom (if-let [d (init-data)]
          (merge (shared (.-sab d)) {:self (.-self d) :starter (.-starter d) :kernel (.-kernel d) :local #queue []})
          {:self 0 :starter nil :local #queue []})))

(declare pump!)

(defn- mem
  "The mailboxes (made on first use, by the page, which then pumps its spawn ring)."
  []
  (or (:ctl @state)
      (do (swap! state merge (shared (js/SharedArrayBuffer. (+ (* 4 CTL-WORDS) (* RING (inc MAX))))))
          (js/setTimeout pump! 0)
          (:ctl @state)))
  @state)

(def ^:private encoder (js/TextEncoder.))
(def ^:private decoder (js/TextDecoder.))

(defn parallelism [] (or (.-hardwareConcurrency js/navigator) 1))

(defn ships-closures? [] false)

(defn driving
  "The declared driving properties: a blocked worker costs a worker, the page's main thread cannot block; workers run
   in parallel; a domain region's wait is Atomics.wait on shared memory; a new worker's start is measured (the last
   one's, ms from new Worker to its entry)."
  []
  {:block (if (can-block?) :thread :never) :parallel true :closures false :eval false :wake :futex
   :spawn-ms (js/Atomics.load (:ctl (mem)) SPAWN-MS) :max-threads (dec MAX) :runtime :browser})

(defn self [] (:self @state))

(defn error-data [e]
  {:class (cond (instance? ExceptionInfo e) "cljs.core/ExceptionInfo"
                (instance? js/Error e) (str "js/" (.-name e))
                :else (str "js/" (if (some? e) (.. e -constructor -name) "null")))
   :message (or (ex-message e) (str e))
   :data (ex-data e)})

(defn- data?
  "May x travel to another heap: EDN that reads back equal?"
  [x]
  (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x) (uuid? x) (inst? x)
      (and (coll? x) (not (record? x)) (every? data? (if (map? x) (mapcat identity x) x)))))

(defn- lock! [ctl id]
  (loop []
    (when-not (zero? (js/Atomics.compareExchange ctl (lock-word id) 0 1))
      (recur))))

(defn- put-bytes!
  "src's bytes into ring id from position pos, wrapping."
  [^js bytes id pos ^js src]
  (let [base (ring-base id)
        at (bit-and pos (dec RING))
        first-n (min (.-length src) (- RING at))]
    (.set bytes (.subarray src 0 first-n) (+ base at))
    (when (< first-n (.-length src))
      (.set bytes (.subarray src first-n) base))))

(defn- get-bytes
  "n bytes of ring id from position pos, wrapping, into a fresh unshared array (TextDecoder refuses shared ones)."
  [^js bytes id pos n]
  (let [base (ring-base id)
        at (bit-and pos (dec RING))
        out (js/Uint8Array. n)
        first-n (min n (- RING at))]
    (.set out (.subarray bytes (+ base at) (+ base at first-n)) 0)
    (when (< first-n n)
      (.set out (.subarray bytes base (+ base (- n first-n))) first-n))
    out))

(defn- put! [id s]
  (let [{:keys [ctl bytes]} (mem)
        payload (.encode encoder s)
        n (.-length payload)
        need (* 4 (js/Math.ceil (/ (+ 4 n) 4)))
        len (js/Uint8Array. 4)]
    (when (> need RING)
      (throw (ex-info (str "eve.thread-host: a message of " n " bytes exceeds a mailbox (" RING " bytes)") {:bytes n})))
    (aset len 0 (bit-and n 255)) (aset len 1 (bit-and (unsigned-bit-shift-right n 8) 255))
    (aset len 2 (bit-and (unsigned-bit-shift-right n 16) 255)) (aset len 3 (unsigned-bit-shift-right n 24))
    (lock! ctl id)
    (let [head (js/Atomics.load ctl (head-word id))
          tail (js/Atomics.load ctl (tail-word id))]
      (if (> (+ (unsigned-bit-shift-right (- tail head) 0) need) RING)
        (do (js/Atomics.store ctl (lock-word id) 0)
            (throw (ex-info "eve.thread-host: the receiver's mailbox is full" {:to id :bytes n})))
        (do (put-bytes! bytes id tail len)
            (put-bytes! bytes id (+ tail 4) payload)
            (js/Atomics.store ctl (tail-word id) (bit-or (+ tail need) 0))
            (js/Atomics.store ctl (lock-word id) 0)
            (js/Atomics.notify ctl (tail-word id)))))))

(defn- take!
  "The next message in this thread's ring, or nil."
  [id]
  (let [{:keys [ctl bytes]} (mem)
        head (js/Atomics.load ctl (head-word id))
        tail (js/Atomics.load ctl (tail-word id))]
    (when-not (= head tail)
      (let [l (get-bytes bytes id head 4)
            n (+ (aget l 0) (* 256 (aget l 1)) (* 65536 (aget l 2)) (* 16777216 (aget l 3)))
            s (.decode decoder (get-bytes bytes id (+ head 4) n))]
        (js/Atomics.store ctl (head-word id) (bit-or (+ head (* 4 (js/Math.ceil (/ (+ 4 n) 4)))) 0))
        (try (reader/read-string s)
             (catch :default _ (throw (ex-info (str "eve.thread-host: unreadable message: " (subs s 0 (min 400 (count s))))
                                               {}))))))))

(defn send!
  "Write msg into the mailbox of the thread at addr (to oneself: onto this thread's own queue); never blocks. A message
   must be data: a host object (a fn, an atom, a record) is refused here; so is one larger than a mailbox, or one a
   full mailbox cannot hold."
  [addr msg]
  (cond
    (= addr (self)) (swap! state update :local conj msg)
    (not (data? msg))
    (throw (ex-info "eve.thread-host: a message to another heap must be data (EDN): a host object is refused"
                    {:eve.thread/refused :host-object}))
    (and (integer? addr) (< -1 addr MAX)) (put! addr (pr-str msg)))
  nil)

(defn receive!
  "The calling thread's next message; :eve.thread/timeout after ms milliseconds (ms < 0: wait for ever; 0: poll). On
   a thread that cannot block (the page's main thread), only a poll: a wait is refused."
  [ms]
  (when (and (not (zero? ms)) (not (can-block?)))
    (throw (ex-info "eve.thread-host: this thread cannot block (the page's main thread): poll with (receive! 0)"
                    {:eve.thread/refused :cannot-block})))
  (let [me (self)
        deadline (when (pos? ms) (+ (js/Date.now) ms))]
    (loop []
      (if-let [m (peek (:local @state))]
        (do (swap! state update :local pop) m)
        (let [ctl (:ctl (mem))
              seen (js/Atomics.load ctl (tail-word me))]
          (if-let [m (take! me)]
            m
            (cond
              (zero? ms) :eve.thread/timeout
              :else (let [left (if deadline (- deadline (js/Date.now)) js/Infinity)]
                      (if (<= left 0)
                        :eve.thread/timeout
                        (do (js/Atomics.wait ctl (tail-word me) seen left) (recur)))))))))))

(defn- fetch-text-sync [url]
  (try (let [x (js/XMLHttpRequest.)]
         (.open x "GET" url false)
         (.send x)
         (when (= 200 (.-status x)) (.-responseText x)))
       (catch :default _ nil)))

(defn- kernel-url
  "The kernel a new worker loads: handed down, or (on the page) the module manifest.edn names beside a <script src> —
   its one module, or :main."
  []
  (or (:kernel @state)
      (let [srcs (keep #(.-src %) (array-seq (.querySelectorAll js/document "script[src]")))
            found (some (fn [src]
                          (let [base (subs src 0 (inc (str/last-index-of src "/")))]
                            (when-let [t (fetch-text-sync (str base "manifest.edn"))]
                              (let [mods (reader/read-string t)
                                    m (or (when (= 1 (count mods)) (first mods))
                                          (some #(when (= :main (:module-id %)) %) mods))]
                                (when m (str base (:output-name m)))))))
                        srcs)]
        (when-not found
          (throw (ex-info "eve.thread-host: no kernel: no manifest.edn beside any <script src> names a module" {})))
        (:kernel (swap! state assoc :kernel found)))))

(def ^:private bootstrap
  (str "self.addEventListener('message', function boot(e) {"
       "  var d = e.data; if (!d || !d.eveThreadBoot) return;"
       "  self.removeEventListener('message', boot);"
       "  self.__eve_thread = d.init;"
       "  importScripts(d.init.kernel);"
       "});"))

(defn- report-exit!
  "This worker's end, to its starter, once."
  [reason]
  (let [{:keys [self starter ctl]} (mem)]
    (when (zero? (js/Atomics.compareExchange ctl (reported-word self) 0 1))
      (send! starter {:eve.thread/exit self :reason reason}))))

(defn- spawn!
  "On the page: create the worker a start asked for ({:id :starter :entry :boot :started}). A message the worker posts
   to its parent (self.postMessage: eve's own threads talk through the mailboxes, never so) goes to the page's hook
   globalThis.__eve_worker_message, when one is set: the way another feature serves its workers from the page (the
   http feature's batched requests)."
  [{:keys [id starter entry boot started]}]
  (let [{:keys [ctl sab]} (mem)
        url (.createObjectURL js/URL
                              (js/Blob. #js [bootstrap]
                                        #js {:type "text/javascript"}))
        w (js/Worker. url)]
    (js/setTimeout #(.revokeObjectURL js/URL url) 60000)
    (set! (.-onerror w)
          (fn [e]
            ;; the backstop: an error the worker did not report, to its starter
            (when
              (zero? (js/Atomics.compareExchange ctl (reported-word id) 0 1))
              (send! starter
                     {:eve.thread/exit id
                      :reason {:class "error"
                               :message (str (.-message e))
                               :data nil}}))))
    (.addEventListener w "message"
                       (fn [e]
                         (when-let [h (.-__eve_worker_message js/globalThis)]
                           (h e))))
    (.postMessage w
                  #js {:eveThreadBoot true
                       :init #js {:sab sab
                                  :self id
                                  :starter starter
                                  :kernel (kernel-url)
                                  :entry entry
                                  :boot boot
                                  :started started}})))

(defn- pump!
  "On the page: create every worker the spawn ring asks for, then wait (without blocking) for the next request."
  []
  (let [ctl (:ctl (mem))]
    (loop [] (when-let [req (take! SPAWN)] (spawn! req) (recur)))
    (let [seen (js/Atomics.load ctl (tail-word SPAWN))]
      (if (exists? js/Atomics.waitAsync)
        (let [r (js/Atomics.waitAsync ctl (tail-word SPAWN) seen)]
          (if (.-async r) (.then (.-value r) pump!) (js/setTimeout pump! 0)))
        (js/setTimeout pump! 5)))))

(defn start-threads!
  "Start n workers, each running ((resolve entry) (assoc boot :self addr)) once the kernel has loaded; their
   addresses, at once. The page creates each worker: here, or (from a worker) when its event loop takes the request
   from the spawn ring."
  [n entry boot _opts]
  (let [{:keys [ctl]} (mem)
        me (self)]
    (vec (for [_ (range n)]
           (let [id (inc (js/Atomics.add ctl NEXT-ID 1))
                 req {:id id :starter me :entry (str entry) :boot (pr-str (assoc boot :self id)) :started (js/Date.now)}]
             (when (>= id MAX)
               (throw (ex-info "eve.thread-host: thread limit" {:max (dec MAX)})))
             (if (ours?) (put! SPAWN (pr-str req)) (spawn! req))
             id)))))

(defn run-thread!
  "In a worker this adapter started: run its entry, tell its starter how it ended, and close (the page created every
   worker, so no other thread is this one's child; a dedicated worker left open stays resident until the page goes);
   true. Elsewhere: false. The program's entry point calls it first (its workers run the whole program)."
  []
  (if-let [d (init-data)]
    (let [sym (symbol (.-entry d))
          f (js/goog.getObjectByName (str (munge (namespace sym)) "." (munge (name sym))))]
      (js/Atomics.store (:ctl (mem)) SPAWN-MS (- (js/Date.now) (.-started d)))
      (report-exit! (try (f (reader/read-string (.-boot d))) :normal
                         (catch :default e (error-data e))))
      (.close js/self)
      true)
    false))

(defn stop-threads!
  "Ask each thread to end: the message :eve.thread/stop (a request its loop honours, never a kill)."
  [addrs]
  (doseq [a addrs] (send! a :eve.thread/stop)))

(defn attempt [f] (try [true (f)] (catch :default e [false e])))

(defn capture-frame [] nil)
(defn call-with-frame [_frame f] (f))

(defn var-symbol [v] (symbol v))

(defn eval-form
  "Refused, loudly: compiled ClojureScript has no compiler at run time."
  [_form]
  (throw (ex-info "eve.thread-host: this runtime cannot eval a form (compiled ClojureScript)"
                  {:eve.thread/refused :eval})))
