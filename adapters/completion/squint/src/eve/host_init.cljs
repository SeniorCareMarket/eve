(ns eve.host-init
  "Load-time registration, replayed as one call — the squint seat's
   counterpart of the ClojureDart seat's eve.host-init.

   Squint-compiled modules DO execute their top-level forms on
   import (unlike ClojureDart, which emits definitions only), so
   Eve's registrations — eve.deftype-proto.alloc's register-* into
   serialize's registry, each collection type's register-eve-type! —
   already run when the namespaces below load. This namespace
   exists so a compiled-seat program can make the same (init!)
   call the other seats make: requiring it pulls the registering
   namespaces into the compiled graph, and init! itself is a no-op.
   Calling it again is likewise a no-op."
  (:require [eve.deftype-proto.serialize]
            [eve.deftype-proto.alloc]
            [eve.deftype-proto.data]
            [eve.slab-io]
            [eve.vec]
            [eve.map]
            [eve.set]
            [eve.list]
            [eve.array]))

(defn init!
  "Registrations happen at namespace load on this host; see the
   ns docstring. Call once, first, as on every compiled seat."
  []
  nil)
