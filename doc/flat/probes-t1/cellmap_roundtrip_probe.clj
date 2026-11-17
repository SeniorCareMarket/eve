;; T1 probe 3 — detach + place round-trip of an STM cell map, no STM in
;; the loop after minting the value. Prints the image tables and the
;; read-back, so a missed relocation shows its face.
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/cellmap_roundtrip_probe.clj
(ns t1.cellmap-roundtrip-probe
  (:require [eve.stm :as stm]
            [eve.atom :as ea]
            [eve.alpha :as e]
            [eve.static.contract :as contract]
            [eve.static.transfer :as st]))

(def rc (stm/make-ref {:donor 1}))
(def foreign (ea/read-pinned (:cell rc) (fn [v] v)))
(println "value:" (pr-str foreign))

(def bs (st/detach foreign))
(def h (contract/read-header bs 0))
(def nb (quot (- (:shapetab h) (:symtab h)) contract/block-entry-size)
      )
(def blocks (contract/read-block-table bs (:symtab h) nb))
(def nr (quot (- (:length h) (:shapetab h)) contract/relocation-entry-size))
(def relocs (contract/read-relocation-table bs (:shapetab h) nr))
(println "image: root" (:root h) "blocks" (pr-str blocks))
(println "relocs" (pr-str relocs))

(def b (e/atom nil))
(st/place-image! b bs)
(println "placed read-back:" (pr-str @b))
(println "round-trip equal:" (= @b (pr-str foreign) ) (= (pr-str @b) (pr-str foreign)))
(System/exit 0)
