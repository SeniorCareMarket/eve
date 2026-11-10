(ns eve.host
  "eve.host for a ClojureScript compiler whose macros run in its own Clojure:
   the names are the macros of eve.host's Clojure side
   (adapters/core/clojure, eve/host.cljc), which expand to clojure.core's
   calls (cljs.core's, as the compiler reads them)."
  (:require-macros [eve.host]))

(defn expand-const-bytes
  "Runtime builder behind the const-bytes macro (F3): the macro side
   (adapters/core/clojure, eve/host.cljc) expands const-bytes to a call
   of eve.host/expand-const-bytes; on the compiled CLJS seat the runtime
   side of eve.host is this file, so the fn lives here. A js/Uint8Array
   over the same values; readers go through host aget + masking
   (hamt_util's ubyte), so it is indistinguishable to every caller."
  [values]
  (js/Uint8Array. (into-array values)))
