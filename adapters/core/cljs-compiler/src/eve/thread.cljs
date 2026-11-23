(ns eve.thread
  "eve.thread for a ClojureScript compiler whose macros run in its own Clojure: the API src/eve/thread.cljc defines
   (its api-defs, the same names), and its macros (with-pool, deftask, defconveyed, tfn, future, pvalues, =>>)."
  (:refer-clojure :exclude [future future-call promise deliver pmap pcalls pvalues realized? future-cancel
                            future-cancelled? future-done? future? deref])
  ;; the aliases src/eve/thread.cljc's api table names its values by
  (:require [eve.thread-host]
            ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.thread.cell :as cell]
            [eve.thread.descriptor]
            ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.thread.fold :as fold]
            ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.thread.future :as fut]
            ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.thread.pool :as pool]
            ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.thread.task :as task])
  (:require-macros [eve.thread]))

(eve.thread/api-defs)
