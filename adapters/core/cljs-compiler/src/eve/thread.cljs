(ns eve.thread
  "eve.thread for a ClojureScript compiler whose macros run in its own Clojure: the API src/eve/thread.cljc defines
   (its api-defs, the same names), and its macros (with-pool, deftask, defconveyed, tfn, future, pvalues, =>>)."
  (:refer-clojure :exclude [future future-call promise deliver pmap pcalls pvalues realized? future-cancel
                            future-cancelled? future-done? future? deref])
  ;; the aliases src/eve/thread.cljc's api table names its values by
  (:require [eve.thread-host]
            [eve.thread.cell :as cell]
            [eve.thread.descriptor]
            [eve.thread.fold :as fold]
            [eve.thread.future :as fut]
            [eve.thread.pool :as pool]
            [eve.thread.task :as task])
  (:require-macros [eve.thread]))

(eve.thread/api-defs)
