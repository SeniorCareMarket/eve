;; T1 probe 5 — dump the detached image bytes for the STM cell map, next
;; to the source bytes, in one JVM.
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/image_dump_probe.clj
(ns t1.image-dump-probe
  (:require [eve.stm :as stm]
            [eve.atom :as ea]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]
            [eve.static.transfer :as st]))

(def rc (stm/make-ref {:donor 1}))
(def foreign (ea/read-pinned (:cell rc) (fn [v] v)))
(def sio (ea/atom-sio (:cell rc)))
(def root (d/-root-header-off foreign))
(def node (alloc/-sio-read-i32 sio root 8))
(println "source header @5:" (pr-str (vec (alloc/-sio-read-bytes sio root 0 12))))
(println "source node @1073741827 first 16:" (pr-str (vec (alloc/-sio-read-bytes sio node 0 16))))

(def bs (st/detach foreign))
(println "image length:" (alength bs))
(println "image bytes:" (pr-str (vec bs)))
(System/exit 0)
