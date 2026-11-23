(ns hooks.register-eve-type
  "clj-kondo analyze-call hook for eve/register-eve-type!
   (eve.deftype-proto.macros/register-eve-type!).

   A call (register-eve-type! {:type-id EveVector-type-id ...})
   defines, in the calling namespace, named fns the serialize
   registry holds: <type-id>--from-header (always),
   <type-id>--dispose (when :dispose is given) and
   <type-id>--build (when :builder-pred and :builder-ctor are
   given), each of two args. Consumers name them directly (the
   Dart host-init's registration table, the JVM codec's writer
   lookup), so the hook models the call as those defns. The opts
   map is a literal at every call site; when it is not, the call
   is left unmodeled."
  (:require [clj-kondo.hooks-api :as api]))

(defn register-eve-type [{:keys [node]}]
  (let [[_ opts] (:children node)]
    (when (api/map-node? opts)
      (let [kvs (partition 2 (:children opts))
            m (into {} (map (fn [[k v]] [(api/sexpr k) v]) kvs))
            type-id (some-> (:type-id m) api/sexpr)
            gen (fn [suffix]
                  (api/list-node
                   [(api/token-node 'defn)
                    (api/token-node (symbol (str (name type-id) "--" suffix)))
                    (api/vector-node [(api/token-node 'sio)
                                      (api/token-node 'x)])]))]
        (when type-id
          {:node (api/list-node
                  (cond-> [(api/token-node 'do) (gen "from-header")]
                    (:dispose m) (conj (gen "dispose"))
                    (and (:builder-pred m) (:builder-ctor m))
                    (conj (gen "build"))))})))))
