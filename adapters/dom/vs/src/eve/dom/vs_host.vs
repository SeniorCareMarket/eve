(ns eve.dom.vs-host
  "eve.dom on ValueScript (adapters/dom/vs): the host of eve.dom.core (its ns doc, THE HOST) — VS below eve.

   THE TRANSPORT is the vs.host bridge (VS's src/vs/host.vs): a frame leaves on the program's stdout as one request of
   op family :evedom, [:evedom :frame <base64 of the request frame>], and its answer is the base64 of the reply frame.
   The page's executor is eve.dom.screen's (exec-frame), the one every eve guest uses; VS's browser host (its
   worker.js) hands it the frame and answers with the reply. A page-side callback arrives as an event of the family,
   {\"t\" \"evedom\" \"op\" \"cb\" \"cb\" <id> \"args\" <base64>} (its stub's frame), or \"rel\" when the page lets a stub
   go; pump! runs them.

   THE PROXY of a page object is a map {:vs-type \"js.EveProxy\" :eve.dom/meta <core META>} of vs.cljs's object model:
   vs.cljs reads and writes a property of a map through its class's \"*\" getter and setter, and calls a method through
   its class's \"*\" method, so js.EveProxy's three turn them into eve.dom.core's ops. A function handle carries
   :vs-call (calling it is OP-APPLY), every handle :vs-ctor (new is OP-NEW). One proxy per handle (no collection on
   VS: a handle lives as long as the program).

   VALUES: VS's JS arrays are vectors (and its Java arrays); a plain object is a js.Object box or a map with no
   :vs-type (its keys by name); VS has no undefined (nil); a page error is thrown as a js.<name> error box."
  (:require [eve.dom.core :as core]
            [eve.flat-io :as fio]
            [eve.net.bytes :as nb]
            [vs.cljs :as j]
            [vs.host :as host]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------------------------------------------
;; bytes <-> base64 (the bridge carries text)

(defn- bytes->b64 [b]
  (let [c (fio/chunk b)]
    (nb/to-b64 (mapv (fn [i] (fio/u8 c i)) (range (fio/chunk-len c))))))

(defn- b64->bytes [s]
  (let [bs (nb/from-b64 s) n (count bs) out (byte-array n)]
    (loop [i 0] (when (< i n) (aset out i (unchecked-byte (nth bs i))) (recur (inc i))))
    out))

;; ---------------------------------------------------------------------------------------------------------------
;; proxies

(def ^:private proxies (atom {}))

(defn meta-of [x] (when (and (map? x) (= "js.EveProxy" (get x :vs-type))) (get x :eve.dom/meta)))

(defn- local-key?
  "A property the guest keeps for itself: \"__…\", a namespaced key (reagami.core/vnode), a compiler's (cljs$…)."
  [k]
  (or (not (string? k)) (str/starts-with? k "__") (str/starts-with? k "cljs$") (str/includes? k "/")))

(defn- page-value? [v k]
  (or (nil? v) (boolean? v) (number? v) (string? v) (some? (meta-of v))
      (and (fn? v) (string? k) (str/starts-with? k "on"))))

(defn- method-fn [m k] (fn [& args] (core/call-method m k args)))

(defn- prop-get [o k]
  (let [m (meta-of o) ex @(:ex m)]
    (cond
      (and ex (contains? ex k)) (get ex k)
      (local-key? k) nil
      (and (= k "then") (not= "Promise" @(:type m))) nil
      :else (let [v (core/get-prop m k)]
              (if (= v ::core/method) (method-fn m k) v)))))

(defn- prop-set! [o k v]
  (let [m (meta-of o)]
    (if (or (local-key? k) (not (page-value? v k)))
      (vswap! (:ex m) (fn [ex] (assoc (or ex {}) k v)))
      (do (when-let [ex @(:ex m)] (when (contains? ex k) (vswap! (:ex m) dissoc k)))
          (core/op-set! m k v)))
    v))

(defn- make-proxy [h kind ty]
  (let [m (core/new-meta h kind ty)
        p (cond-> {:vs-type "js.EveProxy" :eve.dom/meta m
                   :vs-ctor (fn [& args] (core/op-new m args))}
            (= kind 1) (assoc :vs-call (fn [& args] (core/op-apply m nil args))))]
    (swap! proxies assoc h p)
    p))

(defn proxy-for [h kind ty] (or (get @proxies h) (make-proxy h kind ty)))

(defn register-class!
  "js.EveProxy in vs.cljs's object model: every property read, write and method call of a page object is an op."
  []
  (j/register-class! "js.EveProxy"
                     {:super "js.Object"
                      :getters {"*" prop-get}
                      :setters {"*" prop-set!}
                      :methods {"*" (fn [o k & args] (core/call-method (meta-of o) k args))}}))

;; ---------------------------------------------------------------------------------------------------------------
;; values

(defn- plain-object? [x]
  (or (j/js-object? x)
      (and (map? x) (not (contains? x :vs-type)) (not (record? x)))))

(defn- object-items [x]
  (if (j/box? x)
    (mapv (fn [k] [k (j/get-prop x k)]) (j/own-keys x))
    (mapv (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) x)))

(def host
  {:send (fn [frame] (b64->bytes (host/value! [:evedom :frame (bytes->b64 frame)])))
   :defer (fn [f] (j/queue-microtask! f))
   :proxy proxy-for
   :meta-of meta-of
   :undefined (fn [] nil)
   :undefined? (fn [_] false)
   :array? (fn [x] (and (not (map? x)) (j/js-array? x)))
   :array-items (fn [x] (vec x))
   :make-array (fn [v] v)
   :object? plain-object?
   :object-items object-items
   :make-object (fn [kvs] (j/make-object kvs))
   :error (fn [nm msg] (j/js-error (str "js." nm) msg))
   :describe (fn [x] (j/-describe x))})

;; ---------------------------------------------------------------------------------------------------------------
;; page events: callbacks and their release

(defn deliver!
  "Run one :evedom event message (a callback's call, or its release)."
  [msg]
  (case (get msg "op")
    "cb" (core/run-callback! (long (get msg "cb")) (b64->bytes (get msg "args")))
    "rel" (core/callback-released! (long (get msg "cb")) (get msg "gen"))
    nil))

(defn pump!
  "Run the page events that have arrived (reading fd 0 for up to ms, nil: until one arrives, when none is filed)."
  [ms]
  (let [filed (host/take-events! :evedom)]
    (doseq [m (if (seq filed) filed (do (host/pump! ms) (host/take-events! :evedom)))]
      (deliver! m))))

(defn attach!
  "eve.dom.core on this program's page (the host serves op family :evedom)."
  []
  (register-class!)
  (core/attach! host (* 64 1024) :vs-bridge))
