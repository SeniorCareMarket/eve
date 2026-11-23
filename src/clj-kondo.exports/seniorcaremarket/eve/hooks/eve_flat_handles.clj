(ns hooks.eve-flat-handles
  "clj-kondo analyze-call hooks for eve.flat.chunk's handle macros
   (defseqhandle, defmaphandle).

   The macros are defined per host inside a #?@ splice in
   src/eve/flat/chunk.cljc (a stub on :bb/:glj, the real generator
   on :clj) and called in the :default branch as
   (defseqhandle TypeName [field ...] fn-name ... :flag true).
   Kondo does not pick the macro up from the splice, so it reads
   the calls as function calls and reports the type name and the
   field symbols as unresolved. The expansion defines a deftype
   named TypeName plus per-type functions; the field vector and
   the trailing fn-name/flag arguments are macro data. The hook
   models the call as a declare of the type name — the same
   modeling the deftype hook uses for Eve's generated
   constructors — and leaves the macro data unanalyzed."
  (:require [clj-kondo.hooks-api :as api]))

(defn handle [{:keys [node]}]
  (let [[_ tname] (:children node)]
    {:node (api/list-node
            [(api/token-node 'declare)
             (api/token-node (api/sexpr tname))])}))
