(ns hooks.eve-host-field
  "clj-kondo analyze-call hook for eve.host/field.

   (field ctx field-name) reads a deftype field off a slab
   context; the second argument is a field NAME — data for the
   accessor, resolved against the type's fields at macro time —
   not a var reference. Kondo reads it as one and reports every
   field name as an unresolved symbol. The hook quotes the name,
   which is the call's actual semantics."
  (:require [clj-kondo.hooks-api :as api]))

(defn field [{:keys [node]}]
  (let [children (:children node)]
    (when (= 3 (count children))
      (let [[head target fname] children]
        {:node (api/list-node
                [head target
                 (api/list-node [(api/token-node 'quote) fname])])}))))
