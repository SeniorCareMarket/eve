;; T1 probe 7 — micro-behavior of the walk's byte readers and case shape.
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/u8_probe.clj
(ns t1.u8-probe
  (:require [eve.static.transfer :as st]))

(def bs (byte-array [1 0 100 0 2 0 16 65 0 0 0 0]))
(def u8 @#'st/u8)
(def i32 @#'st/i32)
(println "u8 bs 0 =" (u8 bs 0) "| u8 bs 1 =" (u8 bs 1) "| i32 bs 4 =" (i32 bs 4))
(println "case check:" (case (int 1) 0 :zero (1 2) :one-or-two 3 :three :none))
(println "== check:" (== (u8 bs 0) 1))
(System/exit 0)
