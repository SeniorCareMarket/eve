(ns eve.alpha
  "eve.alpha for a ClojureScript compiler whose macros run in its own
   Clojure: the API src/eve/alpha.cljc defines (its api-defs, the same
   names), and its macros (deftype, extend-type)."
  (:refer-clojure :exclude [deftype extend-type atom aget aset alength
                            aclone make-array hash-map hash-set
                            count nth get seq conj empty contains? disj meta
                            with-meta reduce])
  ;; the aliases src/eve/alpha.cljc's api table names its values by
  (:require [eve.deftype]
            ; eve.alpha is the one-namespace consumer surface: this require loads the API cone and alpha's defs delegate to the namespace's vars by their full names 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.core :as ec]
            ; eve.alpha is the one-namespace consumer surface: this require loads the API cone and alpha's defs delegate to the namespace's vars by their full names 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.atom :as atm]
            ; eve.alpha is the one-namespace consumer surface: this require loads the API cone and alpha's defs delegate to the namespace's vars by their full names 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.array :as arr]
            ; eve.alpha is the one-namespace consumer surface: this require loads the API cone and alpha's defs delegate to the namespace's vars by their full names 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.deftype.array :as darr]
            ; eve.alpha is the one-namespace consumer surface: this require loads the API cone and alpha's defs delegate to the namespace's vars by their full names 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.map :as emap]
            ; eve.alpha is the one-namespace consumer surface: this require loads the API cone and alpha's defs delegate to the namespace's vars by their full names 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.set :as eset]
            [eve.vec]
            [eve.list]
            [eve.process-slab :as ps]
            ;; the flat delivery (deref, eve->clj's kind arm): part of the
            ;; value model on every host (EVE-FIX-PLAN §0), loaded with alpha
            [eve.flat.freeze]
            [eve.view])
  (:require-macros [eve.alpha]))

(eve.alpha/api-defs)

;; this process's module slab readied as eve.alpha loads (a promise of its
;; WASM upgrades)
#_{:clj-kondo/ignore [:unused-private-var]} ; defonce for its load-time side effect: (ps/init!) starts the runtime; the promise itself is observed through eve.promise machinery, never read by name here
(defonce ^:private init-promise (ps/init!))
