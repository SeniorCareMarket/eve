(ns eve.thread
  "eve.thread: an executor service written once, over the thread feature (eve.thread-host, HOST-CONTRACT §11) — the
   EVE-THREAD plan (docs/port-gap-closure/coordination/reports/eve-thread-plan-2026-10-18.md), lane ET-1.

   Clojure's threading surface on every eve host: future, future-call, promise, deliver, pmap (unchunked, *pool*'s
   size + 2 ahead, *pool* read at the call), pcalls, pvalues, with-pool, a deref that helps (a waiting thread runs the
   queued work it waits for), errors out of band; the pools — fixed, single, cached, per-task, scheduled — and the
   executor protocol (submit, invoke-all, invoke-any, shutdown!, shutdown-now!, await-termination, shutdown?,
   terminated?); the future an eve type (deref, (deref f ms v), realized?, future-cancel); =>> over the pools (lane
   ET-4; its fold is eve.thread.fold/fold).

   Work is a thread descriptor — data, never a stack — in a cell its pool's threads pull from (eve.thread.cell,
   eve.thread.descriptor); where the threads share the caller's heap it runs as a closure with the binding frame,
   where they do not only a registered task's name (deftask) or a form (tfn, a future's body, where the pool allows
   forms), values and the declared conveyed vars (defconveyed) travel — a plain closure is refused.

   A ClojureScript compiler takes this namespace through adapters/core/cljs-compiler's eve/thread.cljs: the same
   names, by api-defs."
  (:refer-clojure :exclude [future future-call promise deliver pmap pcalls pvalues realized? future-cancel
                            future-cancelled? future-done? future? deref])
  ;; the aliases the api table names its values by (adapters/core/cljs-compiler's eve/thread.cljs requires the same)
  (:require [clojure.walk :as walk]
            [eve.thread-host]
            [eve.thread.cell :as cell]
            [eve.thread.descriptor]
            [eve.thread.fold :as fold]
            [eve.thread.future :as fut]
            [eve.thread.pool :as pool]
            [eve.thread.task :as task]))

;; ---------------------------------------------------------------------------
;; The API: each name and what it is
;; ---------------------------------------------------------------------------

(def ^:private api
  '[;; futures and promises
    [future-call pool/future-call*]
    [promise fut/promise]
    [deliver fut/deliver]
    [realized? fut/realized?]
    [future-done? fut/future-done?]
    [future-cancel fut/future-cancel]
    [future-cancelled? fut/future-cancelled?]
    [future? fut/future?]
    [deref-within fut/deref-within]
    ;; (not `descriptor`: compiled ClojureScript would write it over the namespace object eve.thread.descriptor)
    [descriptor-of fut/descriptor-of]
    ;; parallel map
    [pmap pool/pmap-default]
    [pcalls pool/pcalls-default]
    ;; (fold is eve.thread.fold/fold, as reducers' is r/fold: a var eve.thread/fold would compile, in ClojureScript,
    ;; over the namespace object eve.thread.fold — ET-1's descriptor-of, again. =>>, a macro, is below.)
    ;; pools
    [fixed-pool pool/fixed-pool]
    [single-pool pool/single-pool]
    [cached-pool pool/cached-pool]
    [per-task-pool pool/per-task-pool]
    [scheduled-pool pool/scheduled-pool]
    [schedule! pool/schedule!]
    [schedule-at-fixed-rate! pool/schedule-at-fixed-rate!]
    [schedule-with-fixed-delay! pool/schedule-with-fixed-delay!]
    [default-pool pool/default-pool]
    [shutdown-default-pools! pool/shutdown-default-pools!]
    [current-pool cell/current-pool]
    [pool-name pool/pool-name-of]
    [pool-descriptor pool/pool-descriptor]
    [registry pool/registry]
    ;; the executor protocol
    [submit pool/submit]
    [invoke-all pool/invoke-all]
    [invoke-any pool/invoke-any]
    [shutdown! pool/shutdown!]
    [shutdown-now! pool/shutdown-now!]
    [await-termination pool/await-termination]
    [shutdown? pool/shutdown?]
    [terminated? pool/terminated?]
    ;; code identity
    [register-task! task/register-task!]
    [task-fn task/task-fn]
    [attempt pool/attempt]])

(defmacro api-defs
  "The API's defs, and *pool* — the dynamic var with-pool binds, which eve.thread's engine reads and binds through
   the two fns installed here."
  []
  (concat
   ['do]
   (map (fn [[n v]] (list 'def n v)) api)
   ['(def ^:dynamic *pool* nil)
    '(defn deref
       "@f, or (deref f ms timeout-val): the value, or timeout-val when ms pass first (on every host: ClojureScript
        has no three-argument deref)."
       ([f] (clojure.core/deref f))
       ([f ms timeout-val] (eve.thread.future/deref-within f ms timeout-val)))
    '(defn bind-pool* [p f] (binding [*pool* p] (f)))
    '(defn current-pool* [] *pool*)
    '(eve.thread.cell/install-pool-var! current-pool* bind-pool*)]))

(api-defs)

;; ---------------------------------------------------------------------------
;; The macros (and the analysis a tfn's form needs, run where macros expand: a ClojureScript compiler's own Clojure
;; reads this file, never its ClojureScript, whose resolve takes only a quoted symbol)
;; ---------------------------------------------------------------------------

(defn- body-symbols [body] (set (filter symbol? (tree-seq coll? seq body))))

(defn- free-locals
  "The locals of a macro's env the body names (cljs-thread's leaf-symbol analysis): the values a travelling form must
   carry. An env whose keys are not the locals (a ClojureScript compiler's) answers none — that runtime has no
   evaluator, and its forms are refused before they travel."
  [env body]
  (let [syms (body-symbols body)]
    (vec (filter (fn [k] (and (symbol? k) (contains? syms k))) (keys env)))))

(defn- qualify
  "body with every symbol that names a var here written fully qualified (eve.thread-host/var-symbol), so the runtime that evaluates
   it resolves it whatever its own aliases; a local, and a symbol a vector in the body binds, is left alone."
  [env body]
  (let [bound (set (filter symbol? (mapcat (fn [x] (when (vector? x) (tree-seq coll? seq x)))
                                           (tree-seq coll? seq body))))
        locals (set (filter symbol? (keys env)))]
    (walk/postwalk
     (fn [x]
       (if (and (symbol? x) (not (bound x)) (not (locals x)))
         (let [v (resolve x)]
           (if (var? v) (eve.thread-host/var-symbol v) x))
         x))
     body)))

(defmacro with-pool
  "body with *pool* bound to p: futures, pmap, pcalls and pvalues inside it run on p."
  [p & body]
  `(binding [eve.thread/*pool* ~p] ~@body))

(defmacro deftask
  "defn, and the fn registered under its qualified name with its form's hash: a task that may cross a heap by name."
  [name & fdecl]
  `(do (defn ~name ~@fdecl)
       (eve.thread.task/register-task! (eve.thread-host/var-symbol (var ~name)) ~name ~(task/form-hash fdecl))))

(defmacro defconveyed
  "A dynamic var whose binding is conveyed to a task wherever it runs, as a value."
  [name init]
  `(do (def ~(vary-meta name assoc :dynamic true) ~init)
       (eve.thread.task/register-conveyed! (eve.thread-host/var-symbol (var ~name))
                                           (fn [] ~name)
                                           (fn [v# t#] (binding [~name v#] (t#))))))

(defmacro tfn
  "(tfn [x] body): a fn that runs here as a closure and travels to another heap as its form with the values of the
   locals it closes over."
  [params & body]
  (let [ls (free-locals &env body)]
    `(eve.thread.task/->Tfn (fn ~params ~@body)
                            '(fn [~@ls] (fn ~params ~@(qualify &env body)))
                            [~@ls])))

(defmacro future
  "body on *pool* (else the :io pool): a closure where the pool's threads share this heap, a form with the locals it
   uses where they do not."
  [& body]
  `(eve.thread/future-call (eve.thread/tfn [] ~@body)))

(defmacro pvalues
  "Each expression on *pool* (else the :compute pool), their values semi-lazily: pcalls of thunks."
  [& exprs]
  `(eve.thread/pcalls ~@(map (fn [e] `(eve.thread/tfn [] ~e)) exprs)))

(defn- head-of
  "The qualified symbol a thread form's head names here, or nil (a local, a non-var, no head). Where the macro's own
   namespace refers nothing (a ClojureScript compiler expands in a fresh one) an unqualified head that resolves to
   nothing is read as core's: =>>'s registry is core's transducers."
  [env form]
  (let [hd (cond (symbol? form) form (and (seq? form) (symbol? (first form))) (first form))]
    (when (and hd (not (contains? env hd)) (not (and (map? (:locals env)) (contains? (:locals env) hd))))
      (let [v (resolve hd)]
        (cond (var? v) (eve.thread-host/var-symbol v)
              (and (nil? v) (nil? (namespace hd))) (symbol "clojure.core" (name hd)))))))

(defn- =>>-step
  "One segment of =>>'s plan (eve.thread.fold/segments) as a ->> step."
  [{:keys [kind forms keys]}]
  (case kind
    :step (first forms)
    :seq `(eve.thread.fold/seq-run (comp ~@forms))
    :par (let [in (gensym "in")
               chunk (gensym "chunk")
               ;; (realized once: a host's lazy seq need not memoize — clojerl's walked again would gensym anew)
               argsyms (mapv (fn [f] (if (symbol? f) [] (mapv (fn [_] (gensym "a")) (rest f)))) forms)
               binds (mapcat (fn [f syms] (interleave syms (if (symbol? f) [] (rest f)))) forms argsyms)
               xfs (map (fn [f syms] (if (symbol? f) f (cons (first f) syms))) forms argsyms)
               spec (mapv (fn [k syms] (into [k] syms)) keys argsyms)]
           ;; the arguments evaluated once, here; the run three ways — composed here (threads of this heap), as data
           ;; (registered tasks and values), as its forms (where the pool allows forms) — eve.thread.fold/par-run picks
           `((fn [~in]
               (let [~@binds]
                 (eve.thread.fold/par-run
                  {:xf (fn [] (comp ~@xfs))
                   :spec ~spec
                   :tfn (eve.thread/tfn [~chunk] (eve.thread.fold/into-chunk (comp ~@forms) ~chunk))}
                  ~in)))))))

(defmacro =>>
  "->> whose runs of registered transducers are composed (injest's x>>): each run of chunk-separable ones (map filter
   remove keep mapcat cat replace) folded over *pool* (else the :compute pool), read at the call, in chunks sized from
   measurement, lazily; the others — dedupe, take-while, halt-when and random-sample (injest's parallel set had them:
   they are not chunk-separable, P§1.5), drop-while, take, drop, take-nth, partition-all, partition-by, distinct,
   interpose, map-indexed, keep-indexed — stepped here, in order; every other form a ->> step. Equal to ->> (as
   eve.thread.fold/xf-seq where ->> has no meaning: cat, halt-when). The call's bindings reach every chunk."
  [x & forms]
  (let [segs (fold/segments (map (fn [f] [f (head-of &env f)]) forms))]
    `(->> ~x ~@(map =>>-step segs))))
