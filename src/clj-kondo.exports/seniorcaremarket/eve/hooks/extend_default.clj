(ns hooks.extend-default
  "clj-kondo analyze-call hook for eve.host/extend-default.

   (extend-default proto impls...) is Eve's host-face macro for
   extending a protocol to every value: it expands (the Clojure
   host's own definition, adapters/core/clojure) to
   (extend-protocol proto nil impls... Object impls...), with
   `default` as the root on ClojureScript. Without a model kondo
   reads each method spec as a call to the protocol fn and reports
   arity errors against the protocol's declared arities. The hook
   rewrites the call to the expansion's shape (Object serves both
   passes for analysis: only the method-spec shapes matter)."
  (:require [clj-kondo.hooks-api :as api]))

(defn extend-default [{:keys [node]}]
  (let [[_ proto & impls] (:children node)]
    {:node (api/list-node
            (concat [(api/token-node 'extend-protocol)
                     proto
                     (api/token-node 'nil)]
                    impls
                    [(api/token-node 'Object)]
                    impls))}))

(defn extend-host-vectors
  "eve.deftype/extend-host-vectors (proto impls...) extends proto to
   the host's own vector types with the given method bodies; for
   analysis it is an extend-protocol onto one representative type."
  [{:keys [node]}]
  (let [[_ proto & impls] (:children node)]
    {:node (api/list-node
            (concat [(api/token-node 'extend-protocol)
                     proto
                     (api/token-node 'Object)]
                    impls))}))
