(ns eve.host
  "eve.host for a ClojureScript compiler whose macros run in its own Clojure:
   the names are the macros of eve.host's Clojure side
   (adapters/core/clojure, eve/host.cljc), which expand to clojure.core's
   calls (cljs.core's, as the compiler reads them)."
  (:require-macros [eve.host]))
