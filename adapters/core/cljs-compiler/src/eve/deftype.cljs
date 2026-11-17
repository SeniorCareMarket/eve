(ns eve.deftype
  "eve.deftype for a ClojureScript compiler whose macros run in its own
   Clojure: the runtime an expansion calls, and the macros of eve.deftype's
   Clojure side (src/eve/deftype.cljc)."
  (:require [eve.deftype.registry]
            [eve.deftype-proto.alloc]
            [eve.process-slab]
            [eve.deftype-proto.data]
            [eve.deftype-proto.serialize]
            [eve.codec]
            [eve.type-readers]
            [eve.deftype.slab-runtime]
            ;; the expansion registers every type's generated static
            ;; sister here (eve.deftype.macros, lane H5) — as the JVM
            ;; realization's requires carry it (src/eve/deftype.cljc)
            [eve.flat.sister])
  (:require-macros [eve.deftype]))
