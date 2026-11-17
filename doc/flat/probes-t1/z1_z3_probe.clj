;; T1 probe 8 — the canonical composition on its own: Z1's detach of the
;; STM cell map, fed to Z3's place. Discriminates "my image assembly" from
;; "a Z1<->Z3 format gap at these pins".
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/z1_z3_probe.clj
(ns t1.z1-z3-probe
  (:require [eve.stm :as stm]
            [eve.atom :as ea]
            [eve.static :as st]
            [eve.static.contract :as contract]
            [eve.static.place :as place]))

(def rc (stm/make-ref {:donor 1}))
(def foreign (ea/read-pinned (:cell rc) (fn [v] v)))
(def img (st/detach foreign))
(def bs (:bytes img))
(def h (contract/read-header bs 0))
(println "z1 image: length" (:length h) "root" (:root h) "symtab" (:symtab h)
         "shapetab" (:shapetab h) "typetab" (:typetab h) "array" (alength bs))
(println "blocks:" (pr-str (contract/read-block-table bs (:symtab h)
                             (quot (- (:shapetab h) (:symtab h)) 8))))
(def b (ea/atom (gensym "z1z3-b")))
(println "place outcome:"
         (try (place/place b bs) :placed
              (catch Throwable t [:refused (ex-message t)])))
(println "b deref:" (pr-str @b))
(System/exit 0)
