;; T1 probe 6 — call the walk's internals directly on the cell map's node:
;; fetch-block's bytes and block-scan's verdict, separated.
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/block_scan_probe.clj
(ns t1.block-scan-probe
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
(def fetch @#'st/fetch-block)
(def scan @#'st/block-scan)
(def bs (fetch sio node 1 (alloc/decode-class-idx node)))
(println "fetched" (alength bs) "bytes; first 16:" (pr-str (vec (take 16 bs))))
(println "scan verdict:" (pr-str (scan sio sio bs 1 0 0 0 (alloc/decode-class-idx node) node)))
(println "scan of header:" (pr-str (scan sio sio (fetch sio root 0 0) 0 0 0 0 0 root)))
(System/exit 0)
