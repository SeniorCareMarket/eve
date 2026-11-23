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
  (js/Uint8Array. (#_{:clj-kondo/ignore [:unresolved-symbol]} into-array values)))

(defn js-byte-array
  "Runtime half of the byte-array routing (the macro side,
   adapters/core/clojure's eve.host/expand, sends every byte-array call
   here): signed byte storage on this seat is a js/Int8Array — a size or
   a sequence, optionally with an init (a fill byte or a sequence).
   Lives here for the same reason expand-const-bytes does: on the
   compiled CLJS seat the runtime side of eve.host is this file."
  ([x]
   (js/Int8Array. (if (number? x) x (into-array x))))
  ([size init]
   (let [a (js/Int8Array. size)]
     (if (number? init)
       (.fill a init)
       (.set a (into-array (take size init))))
     a)))
