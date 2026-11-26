(ns eve.dom.core
  "eve.dom's guest side, written once: the proxy's op queue and frames, its ops (get / set / call / new / global /
   release / has / keys / delete / alloc), what it learns (void methods, method properties, created types), the
   callback registry, values over eve.dom.codec, and the animation-frame queue. A host's skin turns its own
   language's property reads, writes and calls on a page object into these ops: the browser's is ES6 Proxy traps
   (adapters/dom/browser, eve.dom.proxy), ValueScript's its JS object model's open-ended class
   (adapters/dom/vs, then vs.dom).

   A page object is an integer handle (1 the page's window, 2 its document); a guest value standing for one is the
   skin's proxy, whose META is {:h handle :kind 0 object / 1 function :type (volatile constructor name) :shadow
   (volatile: properties written since the last frame) :ex (volatile: the guest's own properties)}.

   Writes, and calls the core knows answer nothing, are queued and go in one frame with the next op that needs an
   answer, or when the queue is flushed (the skin's microtask, the end of a callback or an animation frame). A guest
   function crosses only where the page calls it later (listeners, on* properties, observers, timers, animation
   frames, promise reactions); anywhere else it is refused (ex-data {:eve.dom/refused :sync-callback}).

   THE HOST (attach!'s map; every key a fn):
     :send          frame bytes -> reply bytes (the round trip; blocks)
     :defer         f -> nil, f run soon after the current task (the flush's microtask)
     :proxy         [h kind type] -> the guest value for handle h (the skin keeps one per live handle)
     :meta-of       x -> x's META when x is a proxy, else nil
     :undefined     [] -> the host's undefined (nil where it has none)
     :undefined?    x -> is x the host's undefined
     :array?        x -> is x a host array          :array-items  x -> its elements, a seqable
     :make-array    v -> a host array of vector v
     :object?       x -> is x plain data with string keys (a host's plain object)
     :object-items  x -> its [key value] pairs       :make-object  v -> a host object of [k v] pairs vector v
     :error         [name msg] -> the host error to throw for a page-side error
     :describe      x -> a short description for a refusal
     :call          [f args] -> f called as the page calls it, args a vector (optional; default apply: every
                    ClojureScript fn is a JavaScript fn, which takes any argument count — a host whose fns are
                    arity-strict, ValueScript, calls with the leading arguments f takes)"
  (:require [eve.dom.codec :as c]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------------------------------------------
;; state: one guest thread's (the skin's thread)

(defonce ^:private st
  {:host (volatile! nil) :limit (volatile! c/SLOT) :kind (volatile! nil)
   :q (volatile! nil) :qn (volatile! 0) :mark (volatile! 4) :flush-scheduled (volatile! false)
   :rel (volatile! #{}) :void (volatile! #{}) :known (volatile! #{}) :shadowed (volatile! [])
   :cbs (volatile! {}) :cb-gen (volatile! {}) :cb-ids (volatile! {}) :next-cb (volatile! 0) :gen (volatile! 0)
   :range-next (volatile! 0) :range-end (volatile! 0)
   :win (volatile! nil) :doc (volatile! nil)
   :raf (volatile! []) :raf-next (volatile! 0) :raf-scheduled (volatile! false) :last-undef (volatile! false)
   :objs (volatile! {})
   :stats (volatile! {:sync 0 :frames 0 :ops 0 :page-errors 0})})

(defn- host [k] (or (get @(:host st) k) (throw (ex-info (str "eve.dom: no host " k " (eve.dom.core/attach! not called)") {}))))
(defn- h1 [k a] ((host k) a))
(defn- bump! [k n] (vswap! (:stats st) update k + n))

(defn meta-of [x] (when (some? x) (h1 :meta-of x)))
(defn proxy? "Is x a proxy for a page object?" [x] (some? (meta-of x)))
(defn handle-of [x] (some-> (meta-of x) :h))
(defn type-of "The constructor name of the page object behind proxy x (as last reported)." [x] (some-> (meta-of x) :type deref))
(defn stats "Counts so far: :sync (round trips that waited), :frames, :ops, :page-errors (queued ops failed on the page)." [] @(:stats st))

(defn- refused [why msg data]
  (ex-info (str "eve.dom: " msg) (merge {:eve.dom/refused why} data)))

(defn new-meta [h kind ty] {:h h :kind kind :type (volatile! ty) :shadow (volatile! nil) :ex (volatile! nil)})

;; ---------------------------------------------------------------------------------------------------------------
;; the queue and the transport

(defn- q []
  (or @(:q st)
      (let [w (c/writer 8192)]
        (c/u32! w 0)
        (vreset! (:q st) w)
        w)))

(defn- clear-q! []
  (c/seek! (q) 4)
  (vreset! (:qn st) 0)
  (doseq [m @(:shadowed st)] (vreset! (:shadow m) nil))
  (vreset! (:shadowed st) []))

(declare read-value)

(defn- send-frame!
  "Send the queue as one frame; answers a reader over the reply, after its error count."
  []
  (let [w (q) n @(:qn st)]
    (c/patch-u32! w 0 n)
    (bump! :frames 1)
    (bump! :ops n)
    (let [reply (h1 :send (c/bytes w))]
      (clear-q!)
      (let [r (c/reader reply)
            errors (c/r-u32 r)]
        (when (pos? errors) (bump! :page-errors errors))
        r))))

(defn- write-release! [w]
  (let [rel @(:rel st)]
    (when (seq rel)
      (c/u8! w c/OP-RELEASE)
      (c/u32! w (count rel))
      (doseq [h rel] (c/i32! w h))
      (vreset! (:rel st) #{})
      (vswap! (:qn st) inc))))

(defn flush!
  "Send what is queued now (nothing answers)."
  []
  (vreset! (:flush-scheduled st) false)
  (when (pos? @(:qn st)) (send-frame!))
  nil)

(defn- begin-op!
  "Start an op in the queue: the writer. Until the op is ended (queued or answered), abort-op! takes it back out."
  [op]
  (let [w (q)]
    (when (and (zero? @(:qn st)) (seq @(:rel st))) (write-release! w))
    (vreset! (:mark st) (c/pos w))
    (c/u8! w op)
    w))

(defn- abort-op!
  "Take the op being written back out of the queue (a value in it was refused); rethrow e."
  [e]
  (c/seek! (q) @(:mark st))
  (throw e))

(defn- end-queued-op! []
  (vswap! (:qn st) inc)
  (when (> (c/pos (q)) (- @(:limit st) 4096)) (flush!))
  (when-not @(:flush-scheduled st)
    (vreset! (:flush-scheduled st) true)
    (h1 :defer flush!)))

(defn- answer!
  "Send the queue with the op just written (the one that answers); its value, or throw the page's error."
  []
  (vswap! (:qn st) inc)
  (bump! :sync 1)
  (vreset! (:last-undef st) false)
  (read-value (send-frame!) true))

;; ---------------------------------------------------------------------------------------------------------------
;; callbacks

(def callback-methods
  #{"addEventListener" "removeEventListener" "requestAnimationFrame" "requestIdleCallback" "then" "catch" "finally"
    "setTimeout" "setInterval" "queueMicrotask" "requestVideoFrameCallback"})

(defn- callback-id [f]
  (let [id (or (get @(:cb-ids st) f)
               (let [id (vswap! (:next-cb st) inc)]
                 (vswap! (:cb-ids st) assoc f id)
                 id))
        gen (vswap! (:gen st) inc)]
    (vswap! (:cbs st) assoc id f)
    (vswap! (:cb-gen st) assoc id gen)
    [id gen]))

;; ---------------------------------------------------------------------------------------------------------------
;; values

(defn- write-value
  "Write guest value v for the page. ok? says whether a function may cross here (as a callback)."
  [w v ok? where]
  (cond
    (h1 :undefined? v) (c/u8! w c/T-UNDEF)
    (nil? v) (c/u8! w c/T-NULL)
    (true? v) (c/u8! w c/T-TRUE)
    (false? v) (c/u8! w c/T-FALSE)
    ;; a page number is a double: a host's exact ones (a VS ratio, 745/2 from Clojure's /) cross as their double.
    ;; MEASURED before (VS's inspect/dashboard.vs on eve.dom): `ClassCastException 745/2` at the frame's f64
    (number? v) (do (c/u8! w c/T-NUM) (c/f64! w (double v)))
    (string? v) (do (c/u8! w c/T-STR) (c/str! w v))
    :else
    (if-let [m (meta-of v)]
      (do (c/u8! w c/T-HANDLE) (c/i32! w (:h m)) (c/u8! w (:kind m)) (c/str! w @(:type m)))
      (cond
        (h1 :array? v) (let [xs (vec (h1 :array-items v))]
                         (c/u8! w c/T-ARRAY)
                         (c/u32! w (count xs))
                         (doseq [x xs] (write-value w x ok? where)))
        (fn? v) (if ok?
                  (let [[id gen] (callback-id v)]
                    (c/u8! w c/T-CALLBACK) (c/i32! w id) (c/f64! w gen))
                  (throw (refused :sync-callback
                                  (str "a function passed to " where
                                       " would be called synchronously by the page, which cannot call into a worker")
                                  {:where where})))
        (h1 :object? v) (let [kvs (vec (h1 :object-items v))]
                          (c/u8! w c/T-OBJECT)
                          (c/u32! w (count kvs))
                          (doseq [[k x] kvs] (c/str! w (if (keyword? k) (name k) (str k))) (write-value w x ok? where)))
        (keyword? v) (do (c/u8! w c/T-STR) (c/str! w (name v)))
        :else (throw (refused :not-data (str "a value the page cannot receive, passed to " where ": " (h1 :describe v))
                              {:where where}))))))

(defn- write-args [w args ok? where]
  (let [args (vec args)]
    (c/u32! w (count args))
    (doseq [a args] (write-value w a ok? where))))

(declare proxy-for)

(defn read-value
  "A page value from reader r. top? marks a reply's answer (a function property read answers ::method)."
  [r top?]
  (let [t (c/r-u8 r)]
    (case (int t)
      0 (do (when top? (vreset! (:last-undef st) true)) ((host :undefined)))
      1 nil
      2 false
      3 true
      4 (c/r-f64 r)
      5 (c/r-str r)
      6 (let [h (c/r-i32 r) kind (c/r-u8 r) ty (c/r-str r)] (proxy-for h kind ty))
      7 (let [n (c/r-u32 r)] (h1 :make-array (vec (repeatedly n #(read-value r false)))))
      8 (let [n (c/r-u32 r)] (h1 :make-object (vec (repeatedly n (fn [] (let [k (c/r-str r)] [k (read-value r false)]))))))
      9 (let [nm (c/r-str r) msg (c/r-str r)] (throw ((host :error) nm msg)))
      11 ::method
      (throw (ex-info (str "eve.dom: unexpected value tag " t (when top? " in a reply")) {:tag t})))))

;; ---------------------------------------------------------------------------------------------------------------
;; ops

(defn op-get [m k]
  (let [w (begin-op! c/OP-GET)]
    (c/i32! w (:h m)) (c/str! w k)
    (answer!)))

(defn op-set! [m k v]
  (let [w (begin-op! c/OP-SET)]
    (c/i32! w (:h m)) (c/str! w k)
    (try (write-value w v (str/starts-with? k "on") (str "the property " k))
         (catch #?(:clj Throwable :cljs :default :default :default) e (abort-op! e)))
    (end-queued-op!)
    (when (nil? @(:shadow m))
      (vreset! (:shadow m) {})
      (vswap! (:shadowed st) conj m))
    (vswap! (:shadow m) assoc k v)
    v))

(defn shadowed
  "[true v] when property k of m was written since the last frame (its value, without a round trip), else nil."
  [m k]
  (let [s @(:shadow m)] (when (and s (contains? s k)) [true (get s k)])))

(defn op-has? [m k]
  (let [w (begin-op! c/OP-HAS)]
    (c/i32! w (:h m)) (c/str! w k)
    (answer!)))

(defn op-delete! [m k]
  (let [w (begin-op! c/OP-DEL)]
    (c/i32! w (:h m)) (c/str! w k)
    (end-queued-op!)
    true))

(defn op-keys [m]
  (let [w (begin-op! c/OP-KEYS)]
    (c/i32! w (:h m))
    (answer!)))

(defn op-apply [m this-arg args]
  (let [w (begin-op! c/OP-APPLY)]
    (c/i32! w (:h m))
    (try (write-value w this-arg false "a page function's this")
         (write-args w args false (str "the page function " @(:type m)))
         (catch #?(:clj Throwable :cljs :default :default :default) e (abort-op! e)))
    (answer!)))

(defn op-new [m args]
  (let [w (begin-op! c/OP-NEW)]
    (c/i32! w (:h m))
    (try (write-args w args (boolean (re-find #"Observer$" @(:type m))) (str "new " @(:type m)))
         (catch #?(:clj Throwable :cljs :default :default :default) e (abort-op! e)))
    (answer!)))

(def void-learning
  #"(Element|Document|DocumentFragment|^Text|^Comment|CSSStyleDeclaration|DOMTokenList|CanvasRenderingContext2D|Event|^Window|^Storage|^History)$")

(def ^:private returns-arg {"appendChild" 0 "insertBefore" 0 "replaceChild" 1 "removeChild" 0})

(def ^:private created
  #{"createElement" "createElementNS" "createTextNode" "createComment" "createDocumentFragment" "cloneNode"})

(def ^:private tag-types
  {"canvas" "HTMLCanvasElement" "div" "HTMLDivElement" "span" "HTMLSpanElement" "input" "HTMLInputElement"
   "button" "HTMLButtonElement" "a" "HTMLAnchorElement" "p" "HTMLParagraphElement" "img" "HTMLImageElement"
   "ul" "HTMLUListElement" "li" "HTMLLIElement" "form" "HTMLFormElement" "textarea" "HTMLTextAreaElement"
   "select" "HTMLSelectElement" "option" "HTMLOptionElement" "label" "HTMLLabelElement" "style" "HTMLStyleElement"
   "script" "HTMLScriptElement" "table" "HTMLTableElement" "template" "HTMLTemplateElement"})

(defn- created-type [m k args]
  (case k
    "createElement" (get tag-types (str/lower-case (str (first args))) "HTMLElement")
    "createElementNS" (if (= "http://www.w3.org/2000/svg" (first args)) "SVGElement" "Element")
    "createTextNode" "Text"
    "createComment" "Comment"
    "createDocumentFragment" "DocumentFragment"
    "cloneNode" @(:type m)))

(defn- next-handle! []
  (when (>= @(:range-next st) @(:range-end st))
    (let [w (begin-op! c/OP-ALLOC)]
      (c/u32! w 256)
      (let [base (long (answer!))]
        (vreset! (:range-next st) base)
        (vreset! (:range-end st) (+ base 256)))))
  (let [h @(:range-next st)]
    (vswap! (:range-next st) inc)
    h))

(defn- void-key [m k] (str @(:type m) "|" k))

;; THE PIPELINED CALL: a call of a method that answered a page object (not a DOM node: those answer null too —
;; querySelector) is queued under a handle the guest allocates (OP-CALLAS, as createElement's), its proxy given the type
;; the first answer named — so a chain of such calls (d3's select … transition … duration … attr) crosses in ONE frame
;; and the page runs it in one task. Keyed by type, method and argument count (d3's attr with one argument reads,
;; with two answers the transition). MEASURED before (VS's reagami_counter, whose d3 lives on the page): transition and
;; duration were round trips 5-10 ms apart, the page's animation frame between them started the transition, and d3
;; threw `too late; already running` — the click's handler stopped before its confetti
(def ^:private node-type
  #"(Element|Document|DocumentFragment|^Text|^Comment|^CDATASection|^Window|NodeList|HTMLCollection|^Attr)$")

(defn- objs-key [m k args] (str @(:type m) "|" k "|" (count args)))

(defn known-method? "Was property k of m's type read as a method before?" [m k] (contains? @(:known st) (void-key m k)))

(defn call-method
  "Call method k of the page object behind META m, with args (a seqable). Answers the call's value."
  [m k args]
  (let [args (vec args)
        ok? (contains? callback-methods k)
        where (str @(:type m) "." k)]
    (cond
      (and (contains? created k) (or (= k "cloneNode") (= 2 (:h m)) (re-find #"Document$" @(:type m))))
      (let [rh (next-handle!)
            ty (created-type m k args)
            w (begin-op! c/OP-CALLAS)]
        (c/i32! w rh) (c/i32! w (:h m)) (c/str! w k)
        (try (write-args w args false where) (catch #?(:clj Throwable :cljs :default :default :default) e (abort-op! e)))
        (end-queued-op!)
        (proxy-for rh 0 ty))

      (contains? returns-arg k)
      (let [w (begin-op! c/OP-CALLQ)]
        (c/i32! w (:h m)) (c/str! w k)
        (try (write-args w args false where) (catch #?(:clj Throwable :cljs :default :default :default) e (abort-op! e)))
        (end-queued-op!)
        (nth args (get returns-arg k)))

      (contains? @(:objs st) (objs-key m k args))
      (let [rh (next-handle!)
            ty (get @(:objs st) (objs-key m k args))
            w (begin-op! c/OP-CALLAS)]
        (c/i32! w rh) (c/i32! w (:h m)) (c/str! w k)
        (try (write-args w args ok? where) (catch #?(:clj Throwable :cljs :default :default :default) e (abort-op! e)))
        (end-queued-op!)
        (proxy-for rh 0 ty))

      (contains? @(:void st) (void-key m k))
      (let [w (begin-op! c/OP-CALLQ)]
        (c/i32! w (:h m)) (c/str! w k)
        (try (write-args w args ok? where) (catch #?(:clj Throwable :cljs :default :default :default) e (abort-op! e)))
        (end-queued-op!)
        ((host :undefined)))

      :else
      (let [w (begin-op! c/OP-CALL)]
        (c/i32! w (:h m)) (c/str! w k)
        (try (write-args w args ok? where) (catch #?(:clj Throwable :cljs :default :default :default) e (abort-op! e)))
        (let [v (answer!)]
          ;; the reply's tag, not the host's value: a host with no undefined (VS) learns the same voids
          (when (and @(:last-undef st) (re-find void-learning @(:type m)))
            (vswap! (:void st) conj (void-key m k)))
          (when-let [vm (meta-of v)]
            (when (and (= 0 (:kind vm)) (seq @(:type vm)) (not (re-find node-type @(:type vm))))
              (vswap! (:objs st) assoc (objs-key m k args) @(:type vm))))
          v)))))

(defn get-prop
  "Property k of the page object behind META m: the value written since the last frame, else the page's (one round
   trip); a function-valued property answers ::method (the skin answers its method function, called by name on m),
   learned so the next read of k on m's type does not cross."
  [m k]
  (if-let [[_ v] (shadowed m k)]
    v
    (if (known-method? m k)
      ::method
      (let [v (op-get m k)]
        (when (= v ::method) (vswap! (:known st) conj (void-key m k)))
        v))))

;; ---------------------------------------------------------------------------------------------------------------
;; handles

(defn proxy-for
  "The guest value for page handle h (the skin's, one per live handle); its type updated when the page names it."
  [h kind ty]
  (vswap! (:rel st) disj h)
  (let [p ((host :proxy) h kind ty)
        m (meta-of p)]
    (when (and m (seq ty) (not= ty @(:type m)) (not= h 1)) (vreset! (:type m) ty))
    p))

(defn released!
  "The skin let go of handle h's proxy (collected): the page forgets h with the next frame."
  [h]
  (when (> h 2) (vswap! (:rel st) conj h))
  nil)

;; ---------------------------------------------------------------------------------------------------------------
;; animation frames: the guest's own queue, one page request however many callbacks wait

(defn- raf-tick [ts]
  (vreset! (:raf-scheduled st) false)
  (let [cbs @(:raf st)]
    (vreset! (:raf st) [])
    (doseq [e cbs]
      (when-not @(:cancelled e)
        (try ((:f e) ts)
             (catch #?(:clj Throwable :cljs :default :default :default) err
               (println "eve.dom: an animation frame callback threw:" (str err)))))))
  (flush!))

(defn request-animation-frame
  "Run f at the page's next animation frame."
  [f]
  (let [id (vswap! (:raf-next st) inc)]
    (vswap! (:raf st) conj {:id id :f f :cancelled (volatile! false)})
    (when-not @(:raf-scheduled st)
      (vreset! (:raf-scheduled st) true)
      (let [m (meta-of @(:win st))
            w (begin-op! c/OP-CALLQ)]
        (c/i32! w (:h m)) (c/str! w "requestAnimationFrame") (write-args w [raf-tick] true "requestAnimationFrame")
        (end-queued-op!)))
    id))

(defn cancel-animation-frame [id]
  (doseq [e @(:raf st)] (when (= id (:id e)) (vreset! (:cancelled e) true))))

;; ---------------------------------------------------------------------------------------------------------------
;; page messages: a callback to run (its arguments' bytes), or one the page has let go

(defn run-callback!
  "Run callback id with the arguments encoded in host bytes args (the page's stub's frame), then flush."
  [id args]
  (when-let [f (get @(:cbs st) id)]
    (let [r (c/reader args)
          n (c/r-u32 r)
          xs (vec (repeatedly n #(read-value r false)))]
      (try ((or (get @(:host st) :call) apply) f xs)
           (catch #?(:clj Throwable :cljs :default :default :default) e
             (println "eve.dom: a callback threw:" (str e))))
      (flush!)))
  nil)

(defn callback-released!
  "The page let callback id's stub go, at generation gen: forget it unless it has crossed again since."
  [id gen]
  (when (= gen (get @(:cb-gen st) id))
    (vswap! (:cbs st) dissoc id)
    (vswap! (:cb-gen st) dissoc id))
  nil)

;; ---------------------------------------------------------------------------------------------------------------
;; attach

(defn attach!
  "Use host (THE HOST above) whose request frames may be up to limit bytes; kind names its transport."
  [hst limit kind]
  (vreset! (:host st) hst)
  (vreset! (:limit st) limit)
  (vreset! (:kind st) kind)
  (when-not @(:win st)
    (vreset! (:win st) (proxy-for 1 0 "Window"))
    (vreset! (:doc st) (proxy-for 2 0 "HTMLDocument")))
  nil)

(defn transport-kind [] @(:kind st))

(defn page-global
  "The page's own global k (window's: (page-global \"Map\") is the page's Map constructor)."
  [k]
  (let [w (begin-op! c/OP-GLOBAL)]
    (c/str! w k)
    (answer!)))

(defn window [] @(:win st))
(defn document [] @(:doc st))
