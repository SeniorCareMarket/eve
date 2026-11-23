(ns hooks.jolt-ffi-defcfn
  "clj-kondo analyze-call hook for jolt.ffi/defcfn.

   (defcfn name \"foreign_name\" [type ...] :ret & opts) defines a
   Clojure function `name` that calls the foreign function; its
   arity is the number of parameter entries in the type vector —
   the :& token is a mode marker in the signature (the by-reference
   mode argument of C's open, [:string :int :& :int]), not a
   parameter, so it is not counted. Kondo does
   not know the macro (jolt.ffi is a host library, not on the
   lint classpath), so every defined name reads as unresolved at
   its call sites. The hook models the call as a defn of the
   same name and arity. (Pattern established in the
   cohost-media-example Phase 1 lane for the same macro.)"
  (:require [clj-kondo.hooks-api :as api]))

(defn defcfn [{:keys [node]}]
  (let [[_ fname _foreign types-vec] (:children node)
        argc (count (filter (fn [t] (not= :& (api/sexpr t)))
                            (:children types-vec)))
        ;; Underscore-prefixed: the modeled defn has no body, so
        ;; plain names would read as unused bindings at every
        ;; defcfn site.
        args (map (fn [i] (api/token-node (symbol (str "_arg" i))))
                  (range argc))]
    {:node (api/list-node
            [(api/token-node 'defn)
             (api/token-node (api/sexpr fname))
             (api/vector-node (vec args))])}))
