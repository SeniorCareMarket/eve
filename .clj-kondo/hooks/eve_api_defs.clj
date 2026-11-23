(ns hooks.eve-api-defs
  "clj-kondo analyze-call hooks for Eve's api-defs macros
   (eve.alpha/api-defs, eve.thread/api-defs).

   Each of those namespaces defines its public API as data — a
   private `api` table of [name target] pairs — and generates the
   defs with a zero-arg macro: (api-defs) expands to
   (do (def name target) ...). Kondo cannot evaluate the table,
   so without a model every generated name is an unresolved var
   in every consumer (e/atom, t/attempt, ...). The hook replaces
   the call with a declare of the table's names, registering
   them in the defining namespace (and in the cljs-compiler
   adapter's twin files, which call the same macros).

   The name lists below are transcribed from the `api` vars in
   src/eve/alpha.cljc and src/eve/thread.cljc — keep them in
   sync with those tables. Drift is self-announcing: a name
   added to a table without a matching entry here reads as an
   unresolved var at its uses."
  (:require [clj-kondo.hooks-api :as api]))

(def alpha-names
  '[atom atom-domain persistent-atom-domain close-atom-domain!
    shared-atom? persistent? same? eve->clj
    hash-map empty-hash-map hash-set empty-hash-set
    value-array? make-array aget aset alength aclone array-copy
    array-seq array-chunk aset! eve-array
    count nth get seq conj empty contains? disj meta with-meta
    invoke reduce])

(def thread-names
  '[future-call promise deliver realized? future-done?
    future-cancel future-cancelled? future? deref deref-within
    descriptor-of pmap pcalls fixed-pool single-pool cached-pool
    per-task-pool scheduled-pool schedule! schedule-at-fixed-rate!
    schedule-with-fixed-delay! default-pool shutdown-default-pools!
    current-pool pool-name pool-descriptor registry submit
    invoke-all invoke-any shutdown! shutdown-now! await-termination
    shutdown? terminated? register-task! task-fn attempt])

(defn- declares [names]
  {:node (api/list-node
          (into [(api/token-node 'declare)]
                (map api/token-node names)))})

(defn alpha [_ctx]
  (declares alpha-names))

(defn thread [_ctx]
  (declares thread-names))

(defn defconveyed
  "eve.thread/defconveyed (name init) defines a conveyed dynamic
   var: the expansion is a def of a ^:dynamic var. Modeled exactly,
   so the earmuffed-name linter sees the dynamic declaration."
  [{:keys [node]}]
  (let [[_ vname init] (:children node)]
    {:node (api/list-node
            [(api/token-node 'def)
             (with-meta vname {:dynamic true})
             init])}))
