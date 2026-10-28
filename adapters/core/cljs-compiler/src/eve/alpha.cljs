(ns eve.alpha
  "eve.alpha for a ClojureScript compiler whose macros run in its own
   Clojure: the API src/eve/alpha.cljc defines (its api-defs, the same
   names), and its macros (deftype, extend-type)."
  (:refer-clojure :exclude [deftype extend-type atom aget hash-map hash-set
                            count nth get seq conj empty contains? disj meta
                            with-meta reduce])
  ;; the aliases src/eve/alpha.cljc's api table names its values by
  (:require [eve.deftype]
            [eve.core :as ec]
            [eve.atom :as atm]
            [eve.array :as arr]
            [eve.map :as emap]
            [eve.set :as eset]
            [eve.vec]
            [eve.list]
            [eve.process-slab :as ps])
  (:require-macros [eve.alpha]))

(eve.alpha/api-defs)

;; this process's module slab readied as eve.alpha loads (a promise of its
;; WASM upgrades)
(defonce ^:private init-promise (ps/init!))
