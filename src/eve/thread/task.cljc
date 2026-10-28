(ns eve.thread.task
  "Code identity, conveyance, and what a descriptor's work is (EVE-THREAD plan P§5.3).

   Where a pool's threads share the submitter's heap, work is a closure and the host's binding frame, as Clojure's
   futures are. Where a descriptor crosses a heap — another isolate now, another process or machine through a domain
   cell later — only data travels: a registered task's qualified name (deftask; every runtime that loads its
   namespace knows it) with the hash of its form, which the runner checks (skew is a loud miss, never a wrong answer),
   or an evaluable form (tfn, a future's body: where the runner has eval-form and the pool allows forms, P§10 Q4); the
   arguments as values; the values of the declared conveyed vars (defconveyed). A plain closure is refused with the
   rule's message.

   The macros that write these (deftask, defconveyed, tfn) are eve.thread's, with the macro-time analysis a tfn's
   form needs (its free locals, its symbols qualified); this namespace is what they expand to."
  (:require [clojure.string :as str]
            [eve.encoding :as enc]
            [eve.int32 :as i32]
            [eve.thread-host :as h]))

;; ---------------------------------------------------------------------------
;; Registered tasks
;; ---------------------------------------------------------------------------

(defonce tasks (atom {}))
(defonce task-names (atom {}))

(defn register-task!
  "f registered as the task named sym (a qualified symbol), its form's hash h; sym."
  [sym f h]
  (swap! tasks assoc sym {:fn f :hash h})
  (swap! task-names assoc f sym)
  sym)

(defn- ordinal
  "Strings compared by their UTF-16 units (a host's own string order is not everyone's: ClojureCLR's is culture-aware,
   putting : before \" where the JVM's puts it after)."
  [a b]
  (loop [x (seq (enc/utf16-units a)) y (seq (enc/utf16-units b))]
    (cond
      (and (nil? x) (nil? y)) 0
      (nil? x) -1
      (nil? y) 1
      :else (let [c (compare (first x) (first y))] (if (zero? c) (recur (next x) (next y)) c)))))

(def ^:private core-namespaces
  "The namespaces a reader qualifies the core vocabulary with (@x reads as (<core>/deref x)): clojure.core's, and the
   host's own as its syntax-quote names it — they differ by host."
  (set ["clojure.core" (namespace `deref)]))

(defn- rename [x m]
  (cond
    (symbol? x) (get m x x)
    (map? x) (into {} (map (fn [[k v]] [(rename k m) (rename v m)]) x))
    (set? x) (set (map (fn [y] (rename y m)) x))
    (vector? x) (mapv (fn [y] (rename y m)) x)
    (seq? x) (apply list (map (fn [y] (rename y m)) x))
    :else x))

(defn- fn-literal
  "#(...) as a reader writes it — (fn* [params] body) — with its params renamed by position: each reader names them its
   own way (p1__7820# on one host, arg-1 on another; a counter in the name on some)."
  [x]
  (rename x (into {} (keep (fn [[i p]] (when-not (= '& p) [p (symbol (str "%" i))]))
                           (map-indexed vector (second x))))))

(defn- canonical
  "form printed the same on every host: a map's entries and a set's elements sorted, by UTF-16 units (a host prints a
   map in its own order — insertion on one, hash order on another); a symbol of the core vocabulary unqualified (the
   reader qualifies @x's deref with its own host's core namespace); #(...)'s params by position."
  [x]
  (cond
    (map? x) (str "{" (str/join " " (sort ordinal (map (fn [[k v]] (str (canonical k) " " (canonical v))) x))) "}")
    (set? x) (str "#{" (str/join " " (sort ordinal (map canonical x))) "}")
    (vector? x) (str "[" (str/join " " (map canonical x)) "]")
    (seq? x) (let [x (if (and (= 'fn* (first x)) (vector? (second x))) (fn-literal x) x)]
               (str "(" (str/join " " (map canonical x)) ")"))
    (symbol? x) (if (contains? core-namespaces (namespace x)) (name x) (str x))
    :else (pr-str x)))

(defn form-hash
  "The hash a task's form is registered with (P§5.3 item 1), the same on every host: FNV-1a over its canonical
   printing's UTF-16 units (eve.atom's name hash), so runtimes of different hosts sharing a domain cell compare it (a
   host's own hash of a string, and its print order of a map, differ by host)."
  [form]
  (reduce (fn [h u] (i32/imul (bit-xor h (bit-and u 0xFF)) 0x01000193))
          (i32/i32 0x811c9dc5)
          (enc/utf16-units (canonical form))))

(defn task-of
  "The registered task's name for f — a task's symbol, or a fn deftask registered — else nil."
  [f]
  (if (symbol? f) f (get @task-names f)))

(defn task-fn
  "The fn registered as sym; when h is given it must be the form's hash this runtime registered (a runtime with
   another version of the task fails loudly)."
  ([sym] (task-fn sym nil))
  ([sym h]
   (let [t (get @tasks sym)]
     (cond
       (nil? t) (throw (ex-info (str "eve.thread: no task registered as " sym " in this runtime")
                                {:eve.thread/refused :unknown-task :task sym}))
       (and (some? h) (not= h (:hash t)))
       (throw (ex-info (str "eve.thread: task " sym " differs here (form hash " (:hash t) ", submitted " h ")")
                       {:eve.thread/refused :task-skew :task sym}))
       :else (:fn t)))))

;; ---------------------------------------------------------------------------
;; Declared conveyed vars: the bindings that cross a heap, as values
;; ---------------------------------------------------------------------------

(defonce conveyed (atom {}))

(defn register-conveyed!
  "The var named sym conveyed by value: getter answers its value here, binder runs (binder v thunk) with it bound. Its
   value now — as defconveyed defines it — is its declared initial value."
  [sym getter binder]
  (swap! conveyed assoc sym {:get getter :bind binder :init (getter)})
  nil)

(defn- changed-binding [[s {:keys [get init]}]] (let [v (get)] (when (not= v init) [s v])))

(defn capture-conveyed
  "{sym value} of each declared conveyed var whose value here is not its declared initial one: what a binding changed
   (Clojure conveys a thread's bindings, not roots; an unchanged var is its initial value wherever it runs)."
  []
  (into {} (keep changed-binding) @conveyed))

(defn- bind-one [bind v thunk] (fn [] (bind v thunk)))

(defn call-with-conveyed
  "f called with each conveyed var of m bound to its value (a var this runtime has not declared is skipped)."
  [m f]
  ((reduce (fn [thunk [s v]]
             (if-let [{:keys [bind]} (get @conveyed s)] (bind-one bind v thunk) thunk))
           f m)))

;; ---------------------------------------------------------------------------
;; tfn: a fn that runs here as a closure and travels as its form
;; ---------------------------------------------------------------------------

(defrecord Tfn [f form args])

(defn tfn? [x] (instance? Tfn x))

;; ---------------------------------------------------------------------------
;; Work: what a descriptor runs
;; ---------------------------------------------------------------------------

(defn- data?
  "May x cross a heap, by eve.thread's own check: no fn anywhere in it (the adapter refuses any other host object at
   the send)."
  [x]
  (cond (fn? x) false
        (map? x) (every? (fn [[k v]] (and (data? k) (data? v))) x)
        (coll? x) (every? data? x)
        :else true))

(defn closure-work
  "Work that stays in this heap: the call as a thunk, and the binding frame captured here."
  [f args]
  (let [g (cond (tfn? f) (:f f) (symbol? f) (task-fn f) :else f)]
    {:fn (fn [] (apply g args)) :frame (h/capture-frame)}))

(defn refuse-closure []
  (throw (ex-info (str "eve.thread: a closure cannot cross a heap boundary — register it (deftask) "
                       "or write it as a tfn")
                  {:eve.thread/refused :closure})))

(defn data-work
  "Work that may cross a heap: a task name and its form's hash, or a form (forms? true — the pool allows them — and
   the runners can eval), the arguments and the conveyed values; refused, loudly, when it cannot be written as data."
  [f args forms? eval?]
  (let [args (vec args)
        cv (capture-conveyed)]
    (when-not (and (data? args) (data? cv))
      (throw (ex-info "eve.thread: an argument that crosses a heap must be a value (data): a host object is refused"
                      {:eve.thread/refused :host-object})))
    (if-let [sym (when-not (tfn? f) (task-of f))]
      (cond-> {:task sym :hash (:hash (get @tasks sym)) :args args} (seq cv) (assoc :conveyed cv))
      (cond
        (not (tfn? f)) (refuse-closure)
        (not forms?) (throw (ex-info (str "eve.thread: this pool runs task names only — a form crosses a heap only "
                                          "where the pool allows forms ({:forms true})")
                                     {:eve.thread/refused :form}))
        (not eval?) (throw (ex-info "eve.thread: the pool's threads cannot eval a form here: register it (deftask)"
                                    {:eve.thread/refused :eval}))
        (not (data? (:args f))) (throw (ex-info (str "eve.thread: a local a form carries must be a value (data): "
                                                     "a host object is refused")
                                                {:eve.thread/refused :host-object}))
        :else (cond-> {:form (:form f) :fargs (vec (:args f)) :args args} (seq cv) (assoc :conveyed cv))))))

(defn run-work
  "Run work; inside wraps the call (eve.thread binds its own vars there, inside the conveyed frame)."
  [work inside]
  (if-let [f (:fn work)]
    (h/call-with-frame (:frame work) (fn [] (inside f)))
    (call-with-conveyed
     (:conveyed work)
     (fn []
       (inside
        (fn []
          (if-let [form (:form work)]
            (apply (apply (h/eval-form form) (:fargs work)) (:args work))
            (apply (task-fn (:task work) (:hash work)) (:args work)))))))))
