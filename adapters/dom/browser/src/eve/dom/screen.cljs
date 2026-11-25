(ns eve.dom.screen
  "eve.dom on the page's own thread (the UI, or screen, thread): the executor of proxy frames.

   Every page-side object a worker can name is in one registry under an integer handle (1 the page's globalThis,
   2 its document); a WeakMap keeps each object's handle stable, so a worker sees one proxy per object. The executor
   runs a fixed op set — get, set, call, apply, new, global, has, keys, delete, alloc, release — and never evaluates
   code a worker sends. A page-side throw is answered as an error value, so a worker never waits on a failure.

   A worker's function crosses only as a callback id, in the slots where the page runs it later (listeners, on*
   properties, observers, promise reactions, timers): the page holds a stub that posts the call back to the worker.

   Transports: cross-origin isolated, each worker's frame sits in its slot of the proxy SharedArrayBuffer and the page
   wakes on the doorbell word (Atomics.waitAsync, or a posted bell where that is missing); not isolated, a worker posts
   its frame and blocks in a synchronous XHR that the service worker holds until the page posts the reply."
  (:require [eve.dom.codec :as c]
            [eve.dom.values :as v]
            [eve.thread-host :as h]))

;; ---------------------------------------------------------------------------------------------------------------
;; the registry

(defonce ^:private objs (js/Map.))
(defonce ^:private ids (js/WeakMap.))
(defonce ^:private counter #js {:next 16})

(defn- alloc! [n]
  (let [b (.-next counter)]
    (set! (.-next counter) (+ b n))
    b))

(defn- put-at! [h o]
  (.set objs h o)
  (when (or (identical? "object" (goog/typeOf o)) (identical? "function" (goog/typeOf o)))
    (.set ids o h))
  h)

(defn register!
  "The handle of page-side object o (registered on first sight)."
  [o]
  (if-let [h (.get ids o)]
    (do (when-not (.has objs h) (.set objs h o)) h)
    (put-at! (alloc! 1) o)))

(defn lookup [h]
  (if (.has objs h)
    (.get objs h)
    (throw (js/Error. (str "eve.dom: no page object under handle " h " (released)")))))

(defn registered-count [] (.-size objs))

(defonce ^:private _boot (do (put-at! 1 js/globalThis) (when (exists? js/document) (put-at! 2 js/document)) true))

;; ---------------------------------------------------------------------------------------------------------------
;; callbacks: page-side stubs for worker functions, by worker and callback id

;; A stub lives while the page holds it (a listener, a handler property); when the page lets it go, the worker hears
;; of it with the generation of the stub's last crossing, and forgets the function only if it has not crossed since.

(defonce ^:private senders (atom {}))
(defonce ^:private stubs (js/Map.))

(defn set-sender!
  "How the page posts to worker id: (send msg transfer). The default is eve.thread-host's post-to-worker!."
  [id send]
  (swap! senders assoc id send))

(defn- sender [w]
  (or (get @senders w) (fn [msg transfer] (h/post-to-worker! w msg transfer))))

(defonce ^:private released
  (js/FinalizationRegistry.
   (fn [k]
     (when-let [e (.get stubs k)]
       (when-not (.deref (.-ref e))
         (.delete stubs k)
         (let [[w id] (.split k ":")]
           ((sender (js/parseInt w)) #js {:eveTag "eve.dom" :op "rel" :cb (js/parseInt id) :gen (.-gen e)} nil)))))))

(declare write-value)

(defn- stub [w id gen]
  (let [k (str w ":" id)
        e (.get stubs k)
        f (when e (.deref (.-ref e)))]
    (if f
      (do (set! (.-gen e) gen) f)
      (let [send (sender w)
            f (fn [& args]
                (let [wr (c/writer 256)]
                  (c/u32! wr (count args))
                  (doseq [a args] (write-value wr a false 0))
                  (let [b (.-buffer (c/bytes wr))]
                    (send #js {:eveTag "eve.dom" :op "cb" :cb id :args b} #js [b])))
                js/undefined)]
        (.set stubs k #js {:ref (js/WeakRef. f) :gen gen})
        (.register released f k)
        f))))

(defn stub-count [] (.-size stubs))

;; ---------------------------------------------------------------------------------------------------------------
;; values

(defn- write-handle [w o kind]
  (c/u8! w c/T-HANDLE)
  (c/i32! w (register! o))
  (c/u8! w kind)
  (c/str! w (if (= kind 1) (or (.-name o) "") (v/type-name o))))

(defn write-value
  "Write page value v. A function-valued property read by GET (method? true) crosses as a method marker, not a
   handle: the worker calls it by name on its owner."
  [w v method? depth]
  (cond
    (undefined? v) (c/u8! w c/T-UNDEF)
    (nil? v) (c/u8! w c/T-NULL)
    (true? v) (c/u8! w c/T-TRUE)
    (false? v) (c/u8! w c/T-FALSE)
    (number? v) (do (c/u8! w c/T-NUM) (c/f64! w v))
    (string? v) (do (c/u8! w c/T-STR) (c/str! w v))
    (fn? v) (if method? (c/u8! w c/T-METHOD) (write-handle w v 1))
    (or (js/Array.isArray v)
        (and (exists? js/NodeList) (instance? js/NodeList v))
        (and (exists? js/HTMLCollection) (instance? js/HTMLCollection v)))
    (if (< depth 8)
      (do (c/u8! w c/T-ARRAY)
          (c/u32! w (.-length v))
          (dotimes [i (.-length v)] (write-value w (aget v i) false (inc depth))))
      (write-handle w v 0))
    (and (v/plain-object? v) (v/data? v depth))
    (let [ks (js/Object.keys v)]
      (c/u8! w c/T-OBJECT)
      (c/u32! w (.-length ks))
      (doseq [k ks] (c/str! w k) (write-value w (aget v k) false (inc depth))))
    :else (write-handle w v 0)))

(defn- read-value [r w]
  (let [t (c/r-u8 r)]
    (case t
      0 js/undefined
      1 nil
      2 false
      3 true
      4 (c/r-f64 r)
      5 (c/r-str r)
      6 (let [hd (c/r-i32 r)] (c/r-u8 r) (c/r-str r) (lookup hd))
      7 (let [n (c/r-u32 r) a (make-array n)]
          (dotimes [i n] (aset a i (read-value r w)))
          a)
      8 (let [n (c/r-u32 r) o #js {}]
          (dotimes [_ n] (let [k (c/r-str r)] (aset o k (read-value r w))))
          o)
      10 (let [id (c/r-i32 r)] (stub w id (c/r-f64 r)))
      (throw (js/Error. (str "eve.dom: unexpected value tag " t))))))

(defn- read-args [r w]
  (let [n (c/r-u32 r) a (make-array n)]
    (dotimes [i n] (aset a i (read-value r w)))
    a))

;; ---------------------------------------------------------------------------------------------------------------
;; the executor

(defn- write-error [out e]
  (c/u8! out c/T-ERROR)
  (c/str! out (or (some-> e .-name) "Error"))
  (c/str! out (or (some-> e .-message) (str e))))

(defn exec-frame
  "Run request frame bytes (an unshared Uint8Array) from worker w; the reply frame's bytes (a fresh Uint8Array)."
  [^js bs w]
  (let [r (c/reader bs)
        out (c/writer 1024)
        n (c/r-u32 r)
        errors (volatile! 0)]
    (c/u32! out 0)
    (dotimes [_ n]
      (let [op (c/r-u8 r)]
        (if (or (= op c/OP-SET) (= op c/OP-CALLQ) (= op c/OP-RELEASE) (= op c/OP-DEL) (= op c/OP-CALLAS))
          ;; queued ops: no answer; a failure is counted and logged on the page
          (try
            (condp = op
              c/OP-SET (let [o (lookup (c/r-i32 r)) k (c/r-str r) v (read-value r w)] (aset o k v))
              c/OP-CALLQ (let [o (lookup (c/r-i32 r)) k (c/r-str r) args (read-args r w)]
                           (.apply (aget o k) o args))
              c/OP-CALLAS (let [rh (c/r-i32 r) o (lookup (c/r-i32 r)) k (c/r-str r) args (read-args r w)]
                            (put-at! rh (.apply (aget o k) o args)))
              c/OP-DEL (let [o (lookup (c/r-i32 r)) k (c/r-str r)] (js-delete o k))
              c/OP-RELEASE (dotimes [_ (c/r-u32 r)] (let [hd (c/r-i32 r)] (when (> hd 2) (.delete objs hd)))))
            (catch :default e
              (vswap! errors inc)
              (js/console.error (str "eve.dom: a queued op (" op ") failed on the page: " (.-name e) ": " (.-message e)))))
          ;; answering ops: the value, or the error as a value
          (let [at (c/pos out)]
            (try
              (condp = op
                ;; a function property is a method (called by name on its owner), unless its name is a
                ;; constructor's (MutationObserver, Image): then it crosses as a handle, for new
                c/OP-GET (let [o (lookup (c/r-i32 r)) k (c/r-str r)]
                           (write-value out (aget o k) (not (re-find #"^[A-Z]" k)) 0))
                c/OP-CALL (let [o (lookup (c/r-i32 r)) k (c/r-str r) args (read-args r w)
                                f (aget o k)]
                            (when-not (fn? f)
                              (throw (js/TypeError. (str k " is not a function on " (v/type-name o)))))
                            (write-value out (.apply f o args) false 0))
                c/OP-APPLY (let [f (lookup (c/r-i32 r)) this (read-value r w) args (read-args r w)]
                             (write-value out (.apply f this args) false 0))
                c/OP-NEW (let [f (lookup (c/r-i32 r)) args (read-args r w)]
                           (write-value out (js/Reflect.construct f args) false 0))
                c/OP-GLOBAL (write-value out (aget js/globalThis (c/r-str r)) false 0)
                c/OP-HAS (let [o (lookup (c/r-i32 r)) k (c/r-str r)] (write-value out (js-in k o) false 0))
                c/OP-KEYS (let [o (lookup (c/r-i32 r)) ks #js []]
                            (js* "for (var k in ~{}) { ~{}.push(k); }" o ks)
                            (write-value out ks false 0))
                c/OP-ALLOC (write-value out (alloc! (c/r-u32 r)) false 0)
                (throw (js/Error. (str "eve.dom: unknown op " op))))
              (catch :default e
                (c/seek! out at)
                (write-error out e)))))))
    (c/patch-u32! out 0 @errors)
    (c/bytes out)))

;; ---------------------------------------------------------------------------------------------------------------
;; the SAB transport

(defonce ^:private sab-state (atom nil))
(defonce ^:private pending (js/Map.))

(defn- answer-slot! [^js ctl ^js u8 i ^js reply]
  (let [cap c/SLOT
        total (.-length reply)
        n (min cap total)]
    (.set u8 (.subarray reply 0 n) (c/data-at i))
    (if (> total n) (.set pending i #js {:bytes reply :off n}) (.delete pending i))
    (js/Atomics.store ctl (c/len-word i) n)
    (js/Atomics.store ctl (c/total-word i) total)
    (js/Atomics.store ctl (c/state-word i) c/S-REPLY)
    (js/Atomics.notify ctl (c/state-word i))))

(defn- more-slot! [^js ctl ^js u8 i]
  (let [p (.get pending i)
        reply (.-bytes p)
        off (.-off p)
        n (min c/SLOT (- (.-length reply) off))]
    (.set u8 (.subarray reply off (+ off n)) (c/data-at i))
    (if (< (+ off n) (.-length reply)) (set! (.-off p) (+ off n)) (.delete pending i))
    (js/Atomics.store ctl (c/len-word i) n)
    (js/Atomics.store ctl (c/state-word i) c/S-REPLY)
    (js/Atomics.notify ctl (c/state-word i))))

(defonce ^:private partial-requests (js/Map.))

(defn- serve-slot! [^js ctl ^js u8 i]
  (let [n (js/Atomics.load ctl (c/len-word i))
        total (js/Atomics.load ctl (c/total-word i))]
    (cond
      (and (= n 1) (= total 1) (= (aget u8 (c/data-at i)) c/OP-MORE) (.has pending i))
      (more-slot! ctl u8 i)

      (or (> total n) (.has partial-requests i))
      ;; a request larger than the slot comes in parts: take this one, ask for the next
      (let [p (or (.get partial-requests i) #js {:bytes (js/Uint8Array. total) :got 0})
            got (.-got p)]
        (.set (.-bytes p) (.subarray u8 (c/data-at i) (+ (c/data-at i) n)) got)
        (set! (.-got p) (+ got n))
        (if (< (.-got p) total)
          (do (.set partial-requests i p)
              (js/Atomics.store ctl (c/state-word i) c/S-CHUNK)
              (js/Atomics.notify ctl (c/state-word i)))
          (do (.delete partial-requests i)
              (answer-slot! ctl u8 i (exec-frame (.-bytes p) i)))))

      :else
      (answer-slot! ctl u8 i (exec-frame (v/copy-out u8 (c/data-at i) n) i)))))

(defn pump!
  "Serve every slot holding a request, then wait (without blocking the page) for the doorbell."
  []
  (let [{:keys [ctl u8]} @sab-state
        seen (js/Atomics.load ctl c/DOORBELL)]
    (dotimes [i c/NSLOT]
      (when (= c/S-REQUEST (js/Atomics.load ctl (c/state-word i)))
        (serve-slot! ctl u8 i)))
    (when (exists? js/Atomics.waitAsync)
      (let [r (js/Atomics.waitAsync ctl c/DOORBELL seen)]
        (if (.-async r) (.then (.-value r) (fn [_] (pump!))) (js/setTimeout pump! 0))))))

;; ---------------------------------------------------------------------------------------------------------------
;; the service-worker transport (no isolation)

(defn serve-sw-call!
  "A worker's frame posted to the page (not isolated): run it and post the reply to the service worker under key."
  [w ^js d]
  (let [reply (exec-frame (js/Uint8Array. (.-frame d)) w)]
    (js/fetch (str "/eve-sw/px/reply?k=" (.-key d))
              #js {:method "POST" :body reply :cache "no-store"})))

(defn- on-message [w ^js d]
  (case (.-op d)
    "bell" (pump!)
    "call" (serve-sw-call! w d)
    nil))

;; ---------------------------------------------------------------------------------------------------------------
;; start

(defn start!
  "On the page, once: the registry and the transport. Answers the init value workers get under \"eve.dom\"
   (eve.thread-host's share!, done here when the page is cross-origin isolated)."
  [{:keys [transport] :or {transport (if (and (exists? js/crossOriginIsolated) js/crossOriginIsolated) :sab :sw)}}]
  (h/on-worker-message! "eve.dom" on-message)
  (if (= transport :sab)
    (let [sab (or (:sab @sab-state) (js/SharedArrayBuffer. (c/sab-bytes)))
          init #js {:transport "sab" :sab sab :bell (not (exists? js/Atomics.waitAsync))}]
      (when-not @sab-state
        (reset! sab-state {:sab sab :ctl (js/Int32Array. sab 0 (+ c/HDR-WORDS (* 4 c/NSLOT))) :u8 (js/Uint8Array. sab)})
        (pump!))
      (h/share! "eve.dom" init)
      init)
    #js {:transport "sw"}))

(defn transport [] (if @sab-state :sab :sw))
