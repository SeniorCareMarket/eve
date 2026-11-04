(ns eve.deftype
  "Types written once, for every host EVE runs on, with ClojureScript's
   protocol names or the JVM's interface names:

     eve-deftype — a type that lives in eve memory: its fields are in the
                   slab, so an instance can sit in an atom, shared across
                   processes and hosts.
     deftype     — a plain type on the host (the process's own memory): seqs,
                   transients, views over eve values.

   The expansion is eve.deftype.macros (each host's realization its
   eve.types); this namespace is the macros and the runtime an expansion
   calls (requiring eve.deftype is all a type's namespace needs). A
   ClojureScript compiler takes the macros from here through its adapter's
   eve/deftype.cljs (adapters/core/cljs-compiler)."
  (:refer-clojure :exclude [deftype])
  (:require [eve.deftype-proto.alloc]
            [eve.deftype-proto.data]
            [eve.deftype-proto.serialize]
            [eve.codec]
            [eve.deftype.slab-runtime]
            [eve.deftype.macros :as m]))

(defmacro eve-deftype
  "Define a slab-backed type. See eve.deftype.macros/eve-deftype*."
  [type-name fields & body]
  (m/eve-deftype* &env type-name fields body))

(defmacro deftype
  "A plain type on the host, written once for every host: ClojureScript's
   protocol names (ICounted/-count, ...) or the JVM's. See
   eve.deftype.macros/deftype*."
  [type-name fields & body]
  (m/deftype* &env type-name fields body))

(defmacro eve-extend-type
  "Extend protocols to an existing eve-deftype. See
   eve.deftype.macros/eve-extend-type*."
  [type-name & body]
  (m/eve-extend-type* &env type-name body))

(defmacro extend-host-vectors
  "Extend proto to the host's own vectors (its persistent vector and, where
   it is a type of its own, its subvec), each with the given method bodies:
   (extend-host-vectors P (-m [v] ...))."
  [proto & impls]
  (m/extend-host-vectors* &env proto impls))
