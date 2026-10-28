(ns eve.deftype
  "eve.deftype for a ClojureScript compiler whose macros run in its own
   Clojure: the runtime an expansion calls, and the macros of eve.deftype's
   Clojure side (src/eve/deftype.cljc)."
  (:require [eve.deftype-proto.alloc]
            [eve.process-slab]
            [eve.deftype-proto.data]
            [eve.deftype-proto.serialize]
            [eve.codec]
            [eve.deftype.slab-runtime])
  (:require-macros [eve.deftype]))
