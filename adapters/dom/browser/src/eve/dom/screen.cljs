(ns eve.dom.screen
  "eve.dom on the page's own thread (the UI, or screen, thread) for eve's guests — workers eve.thread-host started:
   eve.dom.exec's executor (re-exported here), its stubs posting through eve.thread-host, and the transports. Cross-origin
   isolated, each worker's frame sits in its slot of the proxy SharedArrayBuffer and the page wakes on the doorbell word
   (Atomics.waitAsync, or a posted bell where that is missing); not isolated, a worker posts its frame and blocks in a
   synchronous XHR that the service worker holds until the page posts the reply."
  (:require [eve.dom.codec :as c]
            [eve.dom.exec :as x]
            [eve.dom.values :as v]
            [eve.thread-host :as h]))

(x/set-default-sender! (fn [w msg transfer] (h/post-to-worker! w msg transfer)))

(def register! x/register!)
(def lookup x/lookup)
(def registered-count x/registered-count)
(def set-sender! x/set-sender!)
(def stub-count x/stub-count)
(def write-value x/write-value)
(def exec-frame x/exec-frame)

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
