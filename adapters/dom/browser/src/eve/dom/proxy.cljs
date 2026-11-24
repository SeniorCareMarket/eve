(ns eve.dom.proxy
  "eve.dom in a worker: proxies for the page's objects, so a program whose main thread is a worker drives the page's
   DOM (and a JS library made for the page) as if it ran there.

   A page object is an integer handle; its proxy (an ES6 Proxy, one per handle while it lives) turns property reads,
   writes and calls into ops for the page's executor (eve.dom.screen). Writes, and calls the proxy knows answer
   nothing, are queued and go in one frame with the next op that needs an answer, at the end of a page event's
   callback or animation frame, or on a microtask; a call that answers waits for the page (Atomics.wait on the
   proxy SharedArrayBuffer, or a synchronous XHR the service worker holds when the page is not isolated).

   A worker's function crosses only where the page calls it later: listeners, on* properties, observers, timers,
   animation frames, promise reactions. Anywhere else the page would have to call into a worker synchronously, which
   cannot be done, and the call is refused (ex-data {:eve.dom/refused :sync-callback}).

   Some properties stay in the worker: keys starting with \"__\", namespaced keys (\"reagami.core/vnode\"), symbols,
   and values that are not page values (a worker's object or function under a key that is not an on* handler)."
  (:require [eve.dom.codec :as c]))

;; ---------------------------------------------------------------------------------------------------------------
;; state

(defonce ^:private st
  #js {:call nil :limit c/SLOT :kind nil
       :q nil :qn 0 :mark 4 :flush_scheduled false
       :handles (js/Map.) :pinned (js/Map.) :rel (js/Set.)
       :void (js/Set.) :methods (js/Map.) :known (js/Set.) :shadowed #js []
       :cbs (js/Map.) :cb_gen (js/Map.) :next_cb 0 :gen 0
       :range_next 0 :range_end 0
       :win nil :doc nil
       :raf #js [] :raf_next 0 :raf_scheduled false
       :stats #js {:sync 0 :frames 0 :ops 0 :page_errors 0}})

(defonce ^:private metas (js/WeakMap.))
(defonce ^:private cb-ids (js/WeakMap.))

(defn- meta-of
  "x's proxy record, when x is a proxy (typeof, not goog/typeOf: that reads properties, each a round trip here)."
  [x]
  (when (some? x)
    (let [t (js* "typeof ~{}" x)]
      (when (or (identical? "object" t) (identical? "function" t))
        (.get metas x)))))

(defn proxy?
  "Is x a proxy for a page object?"
  [x]
  (some? (meta-of x)))

(defn handle-of [x] (some-> (meta-of x) .-h))

(defn type-of
  "The constructor name of the page object behind proxy x (as last reported)."
  [x]
  (some-> (meta-of x) .-type))

(defn stats
  "Counts so far: :sync (round trips that waited for an answer), :frames, :ops, :page-errors (queued ops that failed
   on the page, each logged there)."
  []
  (let [s (.-stats st)]
    {:sync (.-sync s) :frames (.-frames s) :ops (.-ops s) :page-errors (.-page-errors s)}))

(defn- refused [why msg data]
  (ex-info (str "eve.dom: " msg) (merge {:eve.dom/refused why} data)))

;; ---------------------------------------------------------------------------------------------------------------
;; the queue and the transport

(defn- q []
  (or (.-q st)
      (let [w (c/writer 8192)]
        (c/u32! w 0)
        (set! (.-q st) w)
        w)))

(defn- clear-q! []
  (let [w (q)]
    (set! (.-pos w) 4)
    (set! (.-qn st) 0)
    (let [sh (.-shadowed st)]
      (dotimes [i (.-length sh)] (set! (.-shadow (aget sh i)) nil))
      (set! (.-length sh) 0))))

(declare read-value)

(defn- send-frame!
  "Send the queue as one frame (n ops in it); answers a reader over the reply, after its error count."
  []
  (let [w (q)
        call (or (.-call st) (throw (ex-info "eve.dom: not attached (eve.dom.proxy/attach! not called)" {})))]
    (c/patch-u32! w 0 (.-qn st))
    (let [s (.-stats st)]
      (set! (.-frames s) (inc (.-frames s)))
      (set! (.-ops s) (+ (.-ops s) (.-qn st))))
    (let [reply (call (c/view w))]
      (clear-q!)
      (let [r (c/reader reply)
            errors (c/r-u32 r)]
        (when (pos? errors)
          (let [s (.-stats st)] (set! (.-page-errors s) (+ (.-page-errors s) errors))))
        r))))

(defn- write-release! [w]
  (let [rel (.-rel st)]
    (when (pos? (.-size rel))
      (c/u8! w c/OP-RELEASE)
      (c/u32! w (.-size rel))
      (.forEach rel (fn [h] (c/i32! w h)))
      (.clear rel)
      (set! (.-qn st) (inc (.-qn st))))))

(defn flush!
  "Send what is queued now (nothing answers)."
  []
  (set! (.-flush-scheduled st) false)
  (when (pos? (.-qn st))
    (send-frame!))
  nil)

(defn- begin-op!
  "Start an op in the queue: the writer. Until the op is ended (queued or answered), abort-op! takes it back out."
  [op]
  (let [w (q)]
    (when (and (zero? (.-qn st)) (pos? (.-size (.-rel st))))
      (write-release! w))
    (set! (.-mark st) (.-pos w))
    (c/u8! w op)
    w))

(defn- abort-op!
  "Take the op being written back out of the queue (a value in it was refused); rethrow e."
  [e]
  (set! (.-pos (q)) (.-mark st))
  (throw e))

(defn- end-queued-op! []
  (set! (.-qn st) (inc (.-qn st)))
  (when (> (.-pos (q)) (- (.-limit st) 4096))
    (flush!))
  (when-not (.-flush-scheduled st)
    (set! (.-flush-scheduled st) true)
    (js/queueMicrotask flush!)))

(defn- answer!
  "Send the queue with the op just written (the one that answers); its value, or throw the page's error."
  []
  (set! (.-qn st) (inc (.-qn st)))
  (let [s (.-stats st)] (set! (.-sync s) (inc (.-sync s))))
  (read-value (send-frame!) true))

;; ---------------------------------------------------------------------------------------------------------------
;; callbacks

(def ^:private callback-methods
  #{"addEventListener" "removeEventListener" "requestAnimationFrame" "requestIdleCallback" "then" "catch" "finally"
    "setTimeout" "setInterval" "queueMicrotask" "requestVideoFrameCallback"})

(defn- callback-id [f]
  (let [id (or (.get cb-ids f)
               (let [id (inc (.-next-cb st))]
                 (set! (.-next-cb st) id)
                 (.set cb-ids f id)
                 id))
        gen (inc (.-gen st))]
    (set! (.-gen st) gen)
    (.set (.-cbs st) id f)
    (.set (.-cb-gen st) id gen)
    [id gen]))

;; ---------------------------------------------------------------------------------------------------------------
;; values

(defn- write-value
  "Write worker value v for the page. ok? says whether a function may cross here (as a callback)."
  [w v ok? where]
  (cond
    (undefined? v) (c/u8! w c/T-UNDEF)
    (nil? v) (c/u8! w c/T-NULL)
    (true? v) (c/u8! w c/T-TRUE)
    (false? v) (c/u8! w c/T-FALSE)
    (number? v) (do (c/u8! w c/T-NUM) (c/f64! w v))
    (string? v) (do (c/u8! w c/T-STR) (c/str! w v))
    :else
    (if-let [m (meta-of v)]
      (do (c/u8! w c/T-HANDLE) (c/i32! w (.-h m)) (c/u8! w (.-kind m)) (c/str! w (.-type m)))
      (cond
        (fn? v) (if ok?
                  (let [[id gen] (callback-id v)]
                    (c/u8! w c/T-CALLBACK) (c/i32! w id) (c/f64! w gen))
                  (throw (refused :sync-callback
                                  (str "a function passed to " where
                                       " would be called synchronously by the page, which cannot call into a worker")
                                  {:where where})))
        (js/Array.isArray v) (do (c/u8! w c/T-ARRAY)
                                 (c/u32! w (.-length v))
                                 (dotimes [i (.-length v)] (write-value w (aget v i) ok? where)))
        (c/plain-object? v) (let [ks (js/Object.keys v)]
                              (c/u8! w c/T-OBJECT)
                              (c/u32! w (.-length ks))
                              (doseq [k ks] (c/str! w k) (write-value w (aget v k) ok? where)))
        (keyword? v) (do (c/u8! w c/T-STR) (c/str! w (name v)))
        :else (throw (refused :not-data (str "a value the page cannot receive, passed to " where ": " (goog/typeOf v)
                                             " " (c/type-name v))
                              {:where where}))))))

(defn- write-args [w ^js args ok? where]
  (c/u32! w (.-length args))
  (dotimes [i (.-length args)] (write-value w (aget args i) ok? where)))

(declare proxy-for method-for)

(defn read-value
  "A page value from reader r. top? marks a reply's answer (a function property read answers a method marker)."
  [r top?]
  (let [t (c/r-u8 r)]
    (case t
      0 js/undefined
      1 nil
      2 false
      3 true
      4 (c/r-f64 r)
      5 (c/r-str r)
      6 (let [h (c/r-i32 r) kind (c/r-u8 r) ty (c/r-str r)] (proxy-for h kind ty))
      7 (let [n (c/r-u32 r) a (make-array n)]
          (dotimes [i n] (aset a i (read-value r false)))
          a)
      8 (let [n (c/r-u32 r) o #js {}]
          (dotimes [_ n] (let [k (c/r-str r)] (aset o k (read-value r false))))
          o)
      9 (let [nm (c/r-str r) msg (c/r-str r)
              e (js/Error. msg)]
          (set! (.-name e) nm)
          (throw e))
      11 ::method
      (throw (js/Error. (str "eve.dom: unexpected value tag " t (when top? " in a reply")))))))

;; ---------------------------------------------------------------------------------------------------------------
;; ops

(defn- op-get [m k]
  (let [w (begin-op! c/OP-GET)]
    (c/i32! w (.-h m)) (c/str! w k)
    (answer!)))

(defn- op-set! [m k v]
  (let [w (begin-op! c/OP-SET)]
    (c/i32! w (.-h m)) (c/str! w k)
    (try (write-value w v (.startsWith k "on") (str "the property " k))
         (catch :default e (abort-op! e)))
    (end-queued-op!)
    (when (nil? (.-shadow m))
      (set! (.-shadow m) #js {})
      (.push (.-shadowed st) m))
    (aset (.-shadow m) k v)))

(def ^:private void-learning
  #"(Element|Document|DocumentFragment|^Text|^Comment|CSSStyleDeclaration|DOMTokenList|CanvasRenderingContext2D|Event|^Window|^Storage|^History)$")

(def ^:private returns-arg {"appendChild" 0 "insertBefore" 0 "replaceChild" 1 "removeChild" 0})

(def ^:private created
  {"createElement" true "createElementNS" true "createTextNode" true "createComment" true
   "createDocumentFragment" true "cloneNode" true})

(def ^:private tag-types
  {"canvas" "HTMLCanvasElement" "div" "HTMLDivElement" "span" "HTMLSpanElement" "input" "HTMLInputElement"
   "button" "HTMLButtonElement" "a" "HTMLAnchorElement" "p" "HTMLParagraphElement" "img" "HTMLImageElement"
   "ul" "HTMLUListElement" "li" "HTMLLIElement" "form" "HTMLFormElement" "textarea" "HTMLTextAreaElement"
   "select" "HTMLSelectElement" "option" "HTMLOptionElement" "label" "HTMLLabelElement" "style" "HTMLStyleElement"
   "script" "HTMLScriptElement" "table" "HTMLTableElement" "template" "HTMLTemplateElement"})

(defn- created-type [m k ^js args]
  (case k
    "createElement" (get tag-types (.toLowerCase (str (aget args 0))) "HTMLElement")
    "createElementNS" (if (= "http://www.w3.org/2000/svg" (aget args 0)) "SVGElement" "Element")
    "createTextNode" "Text"
    "createComment" "Comment"
    "createDocumentFragment" "DocumentFragment"
    "cloneNode" (.-type m)))

(defn- next-handle! []
  (when (>= (.-range-next st) (.-range-end st))
    (let [w (begin-op! c/OP-ALLOC)]
      (c/u32! w 256)
      (let [base (answer!)]
        (set! (.-range-next st) base)
        (set! (.-range-end st) (+ base 256)))))
  (let [h (.-range-next st)]
    (set! (.-range-next st) (inc h))
    h))

(defn- void-key [m k] (str (.-type m) "|" k))

(defn call-method
  "Call method k of the page object behind meta m, with args (an array-like)."
  [m k ^js args]
  (let [ok? (contains? callback-methods k)
        where (str (.-type m) "." k)]
    (cond
      (and (contains? created k) (or (= k "cloneNode") (= 2 (.-h m)) (re-find #"Document$" (.-type m))))
      (let [rh (next-handle!)
            ty (created-type m k args)
            w (begin-op! c/OP-CALLAS)]
        (c/i32! w rh) (c/i32! w (.-h m)) (c/str! w k)
        (try (write-args w args false where) (catch :default e (abort-op! e)))
        (end-queued-op!)
        (proxy-for rh 0 ty))

      (contains? returns-arg k)
      (let [w (begin-op! c/OP-CALLQ)]
        (c/i32! w (.-h m)) (c/str! w k)
        (try (write-args w args false where) (catch :default e (abort-op! e)))
        (end-queued-op!)
        (aget args (get returns-arg k)))

      (.has (.-void st) (void-key m k))
      (let [w (begin-op! c/OP-CALLQ)]
        (c/i32! w (.-h m)) (c/str! w k)
        (try (write-args w args ok? where) (catch :default e (abort-op! e)))
        (end-queued-op!)
        js/undefined)

      :else
      (let [w (begin-op! c/OP-CALL)]
        (c/i32! w (.-h m)) (c/str! w k)
        (try (write-args w args ok? where) (catch :default e (abort-op! e)))
        (let [v (answer!)]
          (when (and (undefined? v) (re-find void-learning (.-type m)))
            (.add (.-void st) (void-key m k)))
          v)))))

(defn- method-for
  "The worker-side function for method k: called on a proxy (its this), or on nothing (then the page's window)."
  [k]
  (or (.get (.-methods st) k)
      (let [f (fn []
                (this-as this
                         (let [m (or (meta-of this) (meta-of (.-win st)))]
                           (call-method m k (js-arguments)))))]
        (.set (.-methods st) k f)
        f)))

;; ---------------------------------------------------------------------------------------------------------------
;; proxies

(defn- local-key? [k]
  (or (not (string? k))
      (.startsWith k "__")
      (.startsWith k "cljs$")
      (not= -1 (.indexOf k "/"))))

(defn- page-value? [v k]
  (or (nil? v) (undefined? v) (boolean? v) (number? v) (string? v)
      (proxy? v)
      (and (fn? v) (string? k) (.startsWith k "on"))))

(declare window-get window-set!)

(defn- get-trap [t k _recv]
  (let [m (.get metas t)
        ex (.-ex m)]
    (cond
      (and ex (js-in k ex)) (aget ex k)
      (local-key? k) (aget t k)
      (and (= k "then") (not= "Promise" (.-type m))) js/undefined
      (and (.-shadow m) (js-in k (.-shadow m))) (aget (.-shadow m) k)
      (= 1 (.-h m)) (window-get m k)
      (.has (.-known st) (void-key m k)) (method-for k)
      :else (let [v (op-get m k)]
              (if (keyword-identical? v ::method)
                (do (.add (.-known st) (void-key m k)) (method-for k))
                v)))))

(defn- set-local! [m k v]
  (when-not (.-ex m)
    (set! (.-ex m) (js/Object.create nil))
    (.set (.-pinned st) (.-h m) (.-proxy m)))
  (aset (.-ex m) k v))

(defn- set-trap [t k v _recv]
  (let [m (.get metas t)]
    (cond
      (and (= 1 (.-h m)) (window-set! m k v)) nil
      (or (local-key? k) (not (page-value? v k))) (set-local! m k v)
      :else (do (when (and (.-ex m) (js-in k (.-ex m))) (js-delete (.-ex m) k))
                (op-set! m k v)))
    true))

(defn- has-trap [t k]
  (let [m (.get metas t)]
    (cond
      (and (.-ex m) (js-in k (.-ex m))) true
      (local-key? k) (js-in k t)
      :else (let [w (begin-op! c/OP-HAS)]
              (c/i32! w (.-h m)) (c/str! w k)
              (answer!)))))

(defn- delete-trap [t k]
  (let [m (.get metas t)]
    (cond
      (and (.-ex m) (js-in k (.-ex m))) (js-delete (.-ex m) k)
      (local-key? k) (js-delete t k)
      :else (let [w (begin-op! c/OP-DEL)]
              (c/i32! w (.-h m)) (c/str! w k)
              (end-queued-op!)))
    true))

(defn- own-keys-trap [t]
  (let [m (.get metas t)
        w (begin-op! c/OP-KEYS)]
    (c/i32! w (.-h m))
    (let [ks (answer!)]
      (when-let [ex (.-ex m)] (doseq [k (js/Object.keys ex)] (.push ks k)))
      (doseq [k (js/Reflect.ownKeys t)]
        (when (= -1 (.indexOf ks k)) (.push ks k)))
      ks)))

(defn- descriptor-trap [t k]
  (if-let [d (js/Reflect.getOwnPropertyDescriptor t k)]
    d
    #js {:value (get-trap t k nil) :writable true :enumerable true :configurable true}))

(defn- apply-trap [t this-arg args]
  (let [m (.get metas t)
        w (begin-op! c/OP-APPLY)]
    (c/i32! w (.-h m))
    (try (write-value w this-arg false "a page function's this")
         (write-args w args false (str "the page function " (.-type m)))
         (catch :default e (abort-op! e)))
    (answer!)))

(defn- construct-trap [t args _new-target]
  (let [m (.get metas t)
        w (begin-op! c/OP-NEW)]
    (c/i32! w (.-h m))
    (try (write-args w args (boolean (re-find #"Observer$" (.-type m))) (str "new " (.-type m)))
         (catch :default e (abort-op! e)))
    (answer!)))

(def ^:private handler
  #js {:get get-trap :set set-trap :has has-trap :deleteProperty delete-trap :ownKeys own-keys-trap
       :getOwnPropertyDescriptor descriptor-trap :apply apply-trap :construct construct-trap})

(defonce ^:private finalizer
  (js/FinalizationRegistry.
   (fn [h]
     (let [ref (.get (.-handles st) h)]
       (when-not (and ref (.deref ref))
         (.delete (.-handles st) h)
         (.add (.-rel st) h))))))

(defn- make-proxy [h kind ty]
  (let [t (if (= kind 1) (.bind (fn []) nil) #js {})
        p (js/Proxy. t handler)
        m #js {:h h :kind kind :type ty :ex nil :shadow nil :proxy p}]
    (.set metas t m)
    (.set metas p m)
    (.set (.-handles st) h (js/WeakRef. p))
    (if (<= h 2)
      (.set (.-pinned st) h p)
      (.register finalizer p h))
    p))

(defn proxy-for
  "The proxy for page handle h (one per handle while it lives)."
  [h kind ty]
  (let [ref (.get (.-handles st) h)
        p (when ref (.deref ref))]
    (.delete (.-rel st) h)
    (if p
      (let [m (.get metas p)]
        (when (and (seq ty) (not= ty (.-type m)) (not= h 1)) (set! (.-type m) ty))
        p)
      (make-proxy h kind ty))))

;; ---------------------------------------------------------------------------------------------------------------
;; the window: local animation frames, constructors for instanceof, the worker's own globals

(defn- raf-tick [ts]
  (set! (.-raf-scheduled st) false)
  (let [cbs (.-raf st)]
    (set! (.-raf st) #js [])
    (dotimes [i (.-length cbs)]
      (let [e (aget cbs i)]
        (when-not (.-cancelled e)
          (try ((.-f e) ts)
               (catch :default err (js/console.error "eve.dom: an animation frame callback threw:" (str err) err)))))))
  (flush!))

(defn request-animation-frame
  "Run f at the page's next animation frame (one page frame request however many callbacks are waiting)."
  [f]
  (let [id (inc (.-raf-next st))]
    (set! (.-raf-next st) id)
    (.push (.-raf st) #js {:id id :f f :cancelled false})
    (when-not (.-raf-scheduled st)
      (set! (.-raf-scheduled st) true)
      (let [m (meta-of (.-win st))
            w (begin-op! c/OP-CALLQ)]
        (c/i32! w (.-h m)) (c/str! w "requestAnimationFrame") (write-args w #js [raf-tick] true "requestAnimationFrame")
        (end-queued-op!)))
    id))

(defn cancel-animation-frame [id]
  (doseq [e (.-raf st)] (when (= id (.-id e)) (set! (.-cancelled e) true))))

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
                                  #js {:value (fn [x] (let [m (meta-of x)] (boolean (and m (rule (.-type m))))))})
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

(defn- window-get [m k]
  (cond
    (or (= k "window") (= k "self") (= k "globalThis") (= k "frames")) (.-win st)
    (= k "document") (.-doc st)
    (= k "requestAnimationFrame") request-animation-frame
    (= k "cancelAnimationFrame") cancel-animation-frame
    (re-find synthetic-names k) (synthetic-ctor k)
    (contains? page-window-keys k) (let [v (op-get m k)] (if (keyword-identical? v ::method) (method-for k) v))
    (js-in k js/self) (aget js/self k)
    :else (let [v (op-get m k)] (if (keyword-identical? v ::method) (method-for k) v))))

(defn- window-set!
  "A write to window that is the worker's own: true when done here. The window stands over the worker's globalThis,
   as a page's window is its globalThis: a library's global (window.confetti = ...) is a worker global; the page's
   own keys and on* handlers go to the page."
  [_m k v]
  (if (and (string? k) (not (contains? page-window-keys k)) (not (.startsWith k "on")))
    (do (aset js/self k v) true)
    false))

;; ---------------------------------------------------------------------------------------------------------------
;; page messages: callbacks and their release

(defn on-page-message
  "A message from the page tagged \"eve.dom\" (a callback to run, or one the page has let go)."
  [^js d]
  (case (.-op d)
    "cb" (when-let [f (.get (.-cbs st) (.-cb d))]
           (let [r (c/reader (js/Uint8Array. (.-args d)))
                 n (c/r-u32 r)
                 args (make-array n)]
             (try
               (dotimes [i n] (aset args i (read-value r false)))
               (.apply f nil args)
               (catch :default e (js/console.error "eve.dom: a callback threw:" (str e) e)))
             (flush!)))
    "rel" (when (= (.-gen d) (.get (.-cb-gen st) (.-cb d)))
            (.delete (.-cbs st) (.-cb d))
            (.delete (.-cb-gen st) (.-cb d)))
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

(def ^:private self-keys
  ["getComputedStyle" "matchMedia" "scrollTo" "scrollBy" "alert" "confirm" "prompt" "getSelection" "innerWidth"
   "innerHeight" "devicePixelRatio" "localStorage" "sessionStorage" "history" "MutationObserver" "ResizeObserver"
   "IntersectionObserver" "Image" "customElements"])

(defn attach!
  "Use transport call (a fn: request frame bytes -> reply bytes) whose request frames may be up to limit bytes."
  [call limit kind]
  (set! (.-call st) call)
  (set! (.-limit st) limit)
  (set! (.-kind st) kind)
  (when-not (.-win st)
    (set! (.-win st) (proxy-for 1 0 "Window"))
    (set! (.-doc st) (proxy-for 2 0 "HTMLDocument")))
  nil)

(defn transport-kind [] (.-kind st))

(defn page-global
  "The page's own global k (window's, not the worker's: (page-global \"Map\") is the page's Map constructor)."
  [k]
  (let [w (begin-op! c/OP-GLOBAL)]
    (c/str! w k)
    (answer!)))

(defn window [] (.-win st))

(defn document [] (.-doc st))

(defn install!
  "Make this worker's globals look like the page's: window and document, the page-only functions and constructors
   (getComputedStyle, MutationObserver, HTMLElement, ...), and animation frames timed by the page."
  []
  (let [win (.-win st)
        def! (fn [k getter]
               (js/Object.defineProperty js/self k #js {:get getter :configurable true}))]
    (def! "window" (fn [] win))
    (def! "document" (fn [] (.-doc st)))
    (doseq [k self-keys]
      (when-not (and (js-in k js/self) (not (contains? page-window-keys k)))
        (def! k (fn [] (aget win k)))))
    (doseq [k ["Node" "EventTarget" "Element" "HTMLElement" "SVGElement" "Document" "HTMLDocument"
               "DocumentFragment" "Text" "Comment" "CharacterData" "HTMLCanvasElement" "HTMLInputElement"
               "MouseEvent" "KeyboardEvent" "PointerEvent" "UIEvent"]]
      (when-not (js-in k js/self)
        (def! k (fn [] (synthetic-ctor k)))))
    (set! (.-requestAnimationFrame js/self) request-animation-frame)
    (set! (.-cancelAnimationFrame js/self) cancel-animation-frame)
    nil))
