(ns hooks.jolt-ffi-defcfn
  "clj-kondo analyze-call hook for jolt.ffi/defcfn.

   (defcfn name \"foreign_name\" [type ...] :ret & opts) defines a
   Clojure function `name` that calls the foreign function; its
   arity is the number of entries in the type vector. Kondo does
   not know the macro (jolt.ffi is a host library, not on the
   lint classpath), so every defined name reads as unresolved at
   its call sites. The hook models the call as a defn of the
   same name and arity. (Pattern established in the
   cohost-media-example Phase 1 lane for the same macro.)"
  (:require [clj-kondo.hooks-api :as api]))

(defn defcfn [{:keys [node]}]
  (let [[_ fname _foreign types-vec] (:children node)
        argc (count (:children types-vec))
        args (map (fn [i] (api/token-node (symbol (str "arg" i))))
                  (range argc))]
    {:node (api/list-node
            [(api/token-node 'defn)
             (api/token-node (api/sexpr fname))
             (api/vector-node (vec args))])}))
