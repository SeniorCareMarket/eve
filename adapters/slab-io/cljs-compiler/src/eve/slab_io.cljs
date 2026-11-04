(ns eve.slab-io
  "eve.slab-io for a ClojureScript compiler whose macros run in its own
   Clojure: the names are the macros of slab-io/protocol's eve/slab_io.cljc,
   which expand to the ISlabIO and IMemRegion calls (cljs.core's protocol
   fns, as the compiler reads them)."
  (:require-macros [eve.slab-io]))
