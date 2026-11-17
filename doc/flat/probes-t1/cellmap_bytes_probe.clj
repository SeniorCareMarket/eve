;; T1 probe 4 — ground truth bytes for the cell map's first blocks, and
;; eve.retire's census of the same value.
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/cellmap_bytes_probe.clj
(ns t1.cellmap-bytes-probe
  (:require [eve.stm :as stm]
            [eve.atom :as ea]
            [eve.retire :as retire]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]))

(def rc (stm/make-ref {:donor 1}))
(def foreign (ea/read-pinned (:cell rc) (fn [v] v)))
(def sio (ea/atom-sio (:cell rc)))
(def root (d/-root-header-off foreign))
(println "value:" (pr-str foreign))
(println "root off:" root "class:" (alloc/decode-class-idx root))
(println "header bytes:" (pr-str (vec (alloc/-sio-read-bytes sio root 0 12))))
(def node (alloc/-sio-read-i32 sio root 8))
(println "root->node off:" node "class:" (alloc/decode-class-idx node))
(println "node bytes (48):" (pr-str (vec (alloc/-sio-read-bytes sio node 0 48))))
(println "retire reach count:" (count (retire/reach sio root)))
(println "retire reach:" (pr-str (retire/reach sio root)))
(System/exit 0)
