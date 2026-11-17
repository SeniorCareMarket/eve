(ns eve.deftype
  "Types written once, for every host EVE runs on, with ClojureScript's
   protocol names or the JVM's interface names:

     deftype — a type that lives in eve memory: its fields are in the
               slab, so an instance can sit in an atom, shared across
               processes and hosts. This is the one type macro users
               define types with.

   Eve's own process-local types (seqs, transients, handles, views over
   eve values) are defined with the internal host-deftype in
   eve.deftype-proto.macros — never by users.

   THE ONE-TREE RULE: every type in Eve is defined by exactly one of
   three sanctioned macros — eve.deftype/deftype (slab-backed user
   types, this namespace), eve.deftype-proto.macros/deftype (the proto
   layer's slab types, Eve's core collections), and
   eve.deftype-proto.macros/host-deftype (process-local host types).
   There is no fourth way to define a type: no other type-defining
   macro may be added to the public layer, and no namespace may route
   around these three. A structural test (eve.deftype-guard-test)
   enforces this permanently.

   The expansion is eve.deftype.macros (each host's realization its
   eve.types); this namespace is the macros and the runtime an expansion
   calls (requiring eve.deftype is all a type's namespace needs). A
   ClojureScript compiler takes the macros from here through its adapter's
   eve/deftype.cljs (adapters/core/cljs-compiler)."
  (:refer-clojure :exclude [deftype])
  (:require [eve.deftype.registry]
            [eve.deftype-proto.alloc]
            #?(:cljs [eve.process-slab])
            [eve.deftype-proto.data]
            [eve.deftype-proto.serialize]
            [eve.codec]
            [eve.deftype.slab-runtime]
            ;; the expansion registers every type's generated flat
            ;; sister here (eve.deftype.macros, lane H5)
            [eve.flat.sister]
            [eve.deftype.macros :as m]
            [eve.type-readers]))

(defmacro deftype
  "Define a slab-backed type. See eve.deftype.macros/eve-deftype*.

   Also generates a predicate named <TypeName>? (Dataset?, ...)
   that answers true for the type's persistent form and its flat
   sister alike — both carry the type's key (eve.deftype-proto.data/
   type-key-of). Library authors wrap the generated predicate under
   their own name as they choose, as eve.dataset/dataset? wraps
   Dataset?."
  [type-name fields & body]
  (m/eve-deftype* &env type-name fields body))

(defmacro eve-extend-type
  "Extend protocols to an existing eve type. See
   eve.deftype.macros/eve-extend-type*."
  [type-name & body]
  (m/eve-extend-type* &env type-name body))

(defmacro extend-host-vectors
  "Extend proto to the host's own vectors (its persistent vector and, where
   it is a type of its own, its subvec), each with the given method bodies:
   (extend-host-vectors P (-m [v] ...))."
  [proto & impls]
  (m/extend-host-vectors* &env proto impls))
