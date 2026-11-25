(ns eve.dom.proxy
  "eve.dom in a worker: proxies for the page's objects, so a program whose main thread is a worker drives the page's
   DOM (and a JS library made for the page) as if it ran there.

   The browser's SKIN over eve.dom.core (src/eve/dom/core.cljc, the guest side every host shares: the op queue and
   frames, the ops and what they learn, callbacks, values, animation frames). Here: the ES6 Proxy per handle whose
   traps turn property reads, writes and calls into the core's ops; the WeakMaps that find a proxy's META; release on
   collection (a FinalizationRegistry); the worker's window (its own globals beside the page's); and the transports —
   a call that answers waits for the page (Atomics.wait on the proxy SharedArrayBuffer, or a synchronous XHR the
   service worker holds when the page is not isolated).

   Some properties stay in the worker: keys starting with \"__\", namespaced keys (\"reagami.core/vnode\"), symbols,
   and values that are not page values (a worker's object or function under a key that is not an on* handler)."
  (:require [eve.dom.codec :as c]
            [eve.dom.core :as core]
            [eve.dom.values :as v]))

;; ---------------------------------------------------------------------------------------------------------------
;; the skin's state: proxies by handle, their META

(defonce ^:private handles (js/Map.))   ; h -> WeakRef of its proxy
(defonce ^:private pinned (js/Map.))    ; h -> proxy, held (window, document, and a proxy holding worker-local props)
(defonce ^:private metas (js/WeakMap.)) ; proxy (and its target) -> META
(defonce ^:private methods (js/Map.))

(defn- meta-of
  "x's META, when x is a proxy (typeof, not goog/typeOf: that reads properties, each a round trip here)."
  [x]
  (when (some? x)
    (let [t (js* "typeof ~{}" x)]
      (when (or (identical? "object" t) (identical? "function" t))
        (.get metas x)))))

(defn proxy? "Is x a proxy for a page object?" [x] (some? (meta-of x)))
(defn handle-of [x] (some-> (meta-of x) :h))
(defn type-of "The constructor name of the page object behind proxy x (as last reported)." [x] (some-> (meta-of x) :type deref))
(def stats core/stats)
(def flush! core/flush!)
(def page-global core/page-global)
(def transport-kind core/transport-kind)
(def request-animation-frame core/request-animation-frame)
(def cancel-animation-frame core/cancel-animation-frame)
(defn window [] (core/window))
(defn document [] (core/document))

(defn- method-for
  "The worker-side function for method k: called on a proxy (its this), or on nothing (then the page's window)."
  [k]
  (or (.get methods k)
      (let [f (fn []
                (this-as this
                         (let [m (or (meta-of this) (meta-of (core/window)))]
                           (core/call-method m k (js/Array.prototype.slice.call (js-arguments))))))]
        (.set methods k f)
        f)))

;; ---------------------------------------------------------------------------------------------------------------
;; traps

(defn- local-key? [k]
  (or (not (string? k))
      (.startsWith k "__")
      (.startsWith k "cljs$")
      (not= -1 (.indexOf k "/"))))

(defn- page-value? [x k]
  (or (nil? x) (undefined? x) (boolean? x) (number? x) (string? x)
      (proxy? x)
      (and (fn? x) (string? k) (.startsWith k "on"))))

(declare window-get window-set!)

(defn- ex-of [m] @(:ex m))

(defn- get-trap [t k _recv]
  (let [m (.get metas t)
        ex (ex-of m)]
    (cond
      (and ex (js-in k ex)) (aget ex k)
      (local-key? k) (aget t k)
      (and (= k "then") (not= "Promise" @(:type m))) js/undefined
      (core/shadowed m k) (second (core/shadowed m k))
      (= 1 (:h m)) (window-get m k)
      :else (let [x (core/get-prop m k)]
              (if (keyword-identical? x ::core/method) (method-for k) x)))))

(defn- set-local! [m k x]
  (when-not (ex-of m)
    (vreset! (:ex m) (js/Object.create nil))
    (.set pinned (:h m) (.deref (.get handles (:h m)))))
  (aset (ex-of m) k x))

(defn- set-trap [t k x _recv]
  (let [m (.get metas t)]
    (cond
      (and (= 1 (:h m)) (window-set! m k x)) nil
      (or (local-key? k) (not (page-value? x k))) (set-local! m k x)
      :else (do (when-let [ex (ex-of m)] (when (js-in k ex) (js-delete ex k)))
                (core/op-set! m k x)))
    true))

(defn- has-trap [t k]
  (let [m (.get metas t) ex (ex-of m)]
    (cond
      (and ex (js-in k ex)) true
      (local-key? k) (js-in k t)
      :else (core/op-has? m k))))

(defn- delete-trap [t k]
  (let [m (.get metas t) ex (ex-of m)]
    (cond
      (and ex (js-in k ex)) (js-delete ex k)
      (local-key? k) (js-delete t k)
      :else (core/op-delete! m k))
    true))

(defn- own-keys-trap [t]
  (let [m (.get metas t)
        ks (core/op-keys m)]
    (when-let [ex (ex-of m)] (doseq [k (js/Object.keys ex)] (.push ks k)))
    (doseq [k (js/Reflect.ownKeys t)]
      (when (= -1 (.indexOf ks k)) (.push ks k)))
    ks))

(defn- descriptor-trap [t k]
  (if-let [d (js/Reflect.getOwnPropertyDescriptor t k)]
    d
    #js {:value (get-trap t k nil) :writable true :enumerable true :configurable true}))

(defn- apply-trap [t this-arg args] (core/op-apply (.get metas t) this-arg args))

(defn- construct-trap [t args _new-target] (core/op-new (.get metas t) args))

(def ^:private handler
  #js {:get get-trap :set set-trap :has has-trap :deleteProperty delete-trap :ownKeys own-keys-trap
       :getOwnPropertyDescriptor descriptor-trap :apply apply-trap :construct construct-trap})

(defonce ^:private finalizer
  (js/FinalizationRegistry.
   (fn [h]
     (let [ref (.get handles h)]
       (when-not (and ref (.deref ref))
         (.delete handles h)
         (core/released! h))))))

(defn- make-proxy [h kind ty]
  (let [t (if (= kind 1) (.bind (fn []) nil) #js {})
        p (js/Proxy. t handler)
        m (core/new-meta h kind ty)]
    (.set metas t m)
    (.set metas p m)
    (.set handles h (js/WeakRef. p))
    (if (<= h 2)
      (.set pinned h p)
      (.register finalizer p h))
    p))

(defn- proxy-of
  "The proxy for page handle h (one per handle while it lives): the core's :proxy."
  [h kind ty]
  (let [ref (.get handles h)
        p (when ref (.deref ref))]
    (or p (make-proxy h kind ty))))

;; ---------------------------------------------------------------------------------------------------------------
;; the window: constructors for instanceof, the worker's own globals

(defn- dom-type? [ty]
  (boolean (re-find #"(Element|Document|DocumentFragment|^Text|^Comment|^CDATASection|^ProcessingInstruction|^DocumentType)$" ty)))

(defn- instance-rule [nm]
  (case nm
    "Node" dom-type?
    "EventTarget" #(or (dom-type? %) (= "Window" %))
    "Element" #(.endsWith % "Element")
    "HTMLElement" #(and (.startsWith % "HTML") (.endsWith % "Element"))
    "SVGElement" #(and (.startsWith % "SVG") (.endsWith % "Element"))
    "Document" #(.endsWith % "Document")
    "CharacterData" #(contains? #{"Text" "Comment" "CDATASection" "ProcessingInstruction"} %)
    "Text" #(contains? #{"Text" "CDATASection"} %)
    "Event" #(.endsWith % "Event")
    "UIEvent" #(contains? #{"UIEvent" "MouseEvent" "PointerEvent" "KeyboardEvent" "WheelEvent" "FocusEvent"
                            "InputEvent" "TouchEvent" "CompositionEvent"} %)
    "MouseEvent" #(contains? #{"MouseEvent" "PointerEvent" "WheelEvent" "DragEvent"} %)
    #(= nm %)))

(defonce ^:private synthetic (js/Map.))

(defn- synthetic-ctor
  "A stand-in constructor named nm: instanceof answers by the page object's constructor name; its prototype has
   none of the page's methods (a library probing a prototype, as canvas-confetti probes transferControlToOffscreen,
   takes its plain path)."
  [nm]
  (or (.get synthetic nm)
      (let [rule (instance-rule nm)
            f (fn [] (throw (js/TypeError. (str "eve.dom: " nm " is the page's: create one through document"))))]
        (js/Object.defineProperty f "name" #js {:value nm})
        (js/Object.defineProperty f js/Symbol.hasInstance
                                  #js {:value (fn [x] (let [m (meta-of x)] (boolean (and m (rule @(:type m))))))})
        (.set synthetic nm f)
        f)))

(def ^:private synthetic-names
  #"^(Node|EventTarget|Element|Document|HTMLDocument|DocumentFragment|CharacterData|Text|Comment|ShadowRoot|Event|UIEvent|MouseEvent|PointerEvent|KeyboardEvent|WheelEvent|FocusEvent|InputEvent|TouchEvent|CustomEvent|(HTML|SVG)\w*Element)$")

(def ^:private page-window-keys
  #{"location" "history" "localStorage" "sessionStorage" "matchMedia" "getComputedStyle" "innerWidth" "innerHeight"
    "outerWidth" "outerHeight" "devicePixelRatio" "scrollX" "scrollY" "pageXOffset" "pageYOffset" "scrollTo"
    "scroll" "scrollBy" "screen" "addEventListener" "removeEventListener" "dispatchEvent" "alert" "confirm"
    "prompt" "open" "getSelection" "customElements" "visualViewport" "frames" "parent" "top" "opener" "name"
    "focus" "blur" "print" "MutationObserver" "ResizeObserver" "IntersectionObserver" "Image" "Option" "Audio"
    "CSS" "getEventListeners"})

(defn- page-get [m k]
  (let [x (core/get-prop m k)] (if (keyword-identical? x ::core/method) (method-for k) x)))

(defn- window-get [m k]
  (cond
    (or (= k "window") (= k "self") (= k "globalThis") (= k "frames")) (core/window)
    (= k "document") (core/document)
    (= k "requestAnimationFrame") core/request-animation-frame
    (= k "cancelAnimationFrame") core/cancel-animation-frame
    (re-find synthetic-names k) (synthetic-ctor k)
    (contains? page-window-keys k) (page-get m k)
    (js-in k js/self) (aget js/self k)
    :else (page-get m k)))

(defn- window-set!
  "A write to window that is the worker's own: true when done here. The window stands over the worker's globalThis,
   as a page's window is its globalThis: a library's global (window.confetti = ...) is a worker global; the page's
   own keys and on* handlers go to the page."
  [_m k x]
  (if (and (string? k) (not (contains? page-window-keys k)) (not (.startsWith k "on")))
    (do (aset js/self k x) true)
    false))

;; ---------------------------------------------------------------------------------------------------------------
;; page messages: callbacks and their release

(defn on-page-message
  "A message from the page tagged \"eve.dom\" (a callback to run, or one the page has let go)."
  [^js d]
  (case (.-op d)
    "cb" (core/run-callback! (.-cb d) (js/Uint8Array. (.-args d)))
    "rel" (core/callback-released! (.-cb d) (.-gen d))
    nil))

;; ---------------------------------------------------------------------------------------------------------------
;; transports

(defn sab-transport
  "The round trip through slot i of the proxy SharedArrayBuffer sab (the page cross-origin isolated). bell? when
   the page has no Atomics.waitAsync: then each request also posts a bell to the page."
  [sab i bell? timeout-ms]
  (let [ctl (js/Int32Array. sab 0 (+ c/HDR-WORDS (* 4 c/NSLOT)))
        u8 (js/Uint8Array. sab)
        sw (c/state-word i)
        ring! (fn []
                (js/Atomics.add ctl c/DOORBELL 1)
                (js/Atomics.notify ctl c/DOORBELL)
                (when bell? (.postMessage js/self #js {:eveTag "eve.dom" :op "bell"})))
        await! (fn []
                 (loop []
                   (when (= c/S-REQUEST (js/Atomics.load ctl sw))
                     (when (and (= "timed-out" (js/Atomics.wait ctl sw c/S-REQUEST timeout-ms))
                                (= c/S-REQUEST (js/Atomics.load ctl sw)))
                       (throw (ex-info "eve.dom: the page did not answer (its thread busy, or gone)"
                                       {:eve.dom/refused :ui-timeout :ms timeout-ms})))
                     (recur))))
        put! (fn [^js bs total]
               (.set u8 bs (c/data-at i))
               (js/Atomics.store ctl (c/len-word i) (.-length bs))
               (js/Atomics.store ctl (c/total-word i) total)
               (js/Atomics.store ctl sw c/S-REQUEST)
               (ring!)
               (await!))]
    (fn [^js frame]
      (let [total (.-length frame)]
        (loop [off 0]
          (let [n (min c/SLOT (- total off))]
            (put! (.subarray frame off (+ off n)) total)
            (when (< (+ off n) total) (recur (+ off n)))))
        (let [rtotal (js/Atomics.load ctl (c/total-word i))
              out (js/Uint8Array. rtotal)]
          (loop [got 0]
            (let [n (js/Atomics.load ctl (c/len-word i))]
              (.set out (.subarray u8 (c/data-at i) (+ (c/data-at i) n)) got)
              (when (< (+ got n) rtotal)
                (put! (js/Uint8Array.of c/OP-MORE) 1)
                (recur (+ got n)))))
          (js/Atomics.store ctl sw c/S-IDLE)
          out)))))

(defn sw-relayed?
  "Do this worker's synchronous XHRs reach the service worker? (Firefox sends them to the network.)"
  []
  (let [x (js/XMLHttpRequest.)]
    (try
      (.open x "GET" "/eve-sw/px/sleep?ms=0" false)
      (.send x)
      (= "1" (.getResponseHeader x "X-Eve-Sw"))
      (catch :default _ false))))

(defn sw-transport
  "The round trip through the service worker (the page not isolated): post the frame to the page, then wait in a
   synchronous XHR the service worker holds until the page posts the reply under the same key."
  [worker-id]
  (let [seq-no (volatile! 0)]
    (fn [^js frame]
      (let [k (str worker-id "." (vswap! seq-no inc))
            b (.slice (.-buffer frame) (.-byteOffset frame) (+ (.-byteOffset frame) (.-length frame)))
            x (js/XMLHttpRequest.)]
        (.postMessage js/self #js {:eveTag "eve.dom" :op "call" :key k :frame b} #js [b])
        (.open x "POST" (str "/eve-sw/px/wait?k=" k) false)
        (set! (.-responseType x) "arraybuffer")
        (.send x)
        (when-not (= 200 (.-status x))
          (throw (ex-info "eve.dom: the service worker did not relay the page's answer"
                          {:eve.dom/refused :ui-timeout :status (.-status x) :key k})))
        (js/Uint8Array. (.-response x))))))

;; ---------------------------------------------------------------------------------------------------------------
;; attach and install

(defn- host
  "eve.dom.core's host in a browser worker (core.cljc's THE HOST), its round trip call."
  [call]
  {:send call
   :defer (fn [f] (js/queueMicrotask f))
   :proxy proxy-of
   :meta-of meta-of
   :undefined (fn [] js/undefined)
   :undefined? undefined?
   :array? (fn [x] (js/Array.isArray x))
   :array-items (fn [x] (array-seq x))
   :make-array (fn [xs] (into-array xs))
   :object? v/plain-object?
   :object-items (fn [o] (map (fn [k] [k (aget o k)]) (js/Object.keys o)))
   :make-object (fn [kvs] (let [o #js {}] (doseq [[k x] kvs] (aset o k x)) o))
   :error (fn [nm msg] (let [e (js/Error. msg)] (set! (.-name e) nm) e))
   :describe (fn [x] (str (goog/typeOf x) " " (v/type-name x)))})

(defn attach!
  "Use transport call (a fn: request frame bytes -> reply bytes) whose request frames may be up to limit bytes."
  [call limit kind]
  (core/attach! (host call) limit kind))

(def ^:private self-keys
  ["getComputedStyle" "matchMedia" "scrollTo" "scrollBy" "alert" "confirm" "prompt" "getSelection" "innerWidth"
   "innerHeight" "devicePixelRatio" "localStorage" "sessionStorage" "history" "MutationObserver" "ResizeObserver"
   "IntersectionObserver" "Image" "customElements"])

(defn install!
  "Make this worker's globals look like the page's: window and document, the page-only functions and constructors
   (getComputedStyle, MutationObserver, HTMLElement, ...), and animation frames timed by the page."
  []
  (let [win (core/window)
        def! (fn [k getter]
               (js/Object.defineProperty js/self k #js {:get getter :configurable true}))]
    (def! "window" (fn [] win))
    (def! "document" (fn [] (core/document)))
    (doseq [k self-keys]
      (when-not (and (js-in k js/self) (not (contains? page-window-keys k)))
        (def! k (fn [] (aget win k)))))
    (doseq [k ["Node" "EventTarget" "Element" "HTMLElement" "SVGElement" "Document" "HTMLDocument"
               "DocumentFragment" "Text" "Comment" "CharacterData" "HTMLCanvasElement" "HTMLInputElement"
               "MouseEvent" "KeyboardEvent" "PointerEvent" "UIEvent"]]
      (when-not (js-in k js/self)
        (def! k (fn [] (synthetic-ctor k)))))
    (set! (.-requestAnimationFrame js/self) core/request-animation-frame)
    (set! (.-cancelAnimationFrame js/self) core/cancel-animation-frame)
    nil))
