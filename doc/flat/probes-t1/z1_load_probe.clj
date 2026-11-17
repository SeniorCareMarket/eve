;; T1 probe 9 — why does eve.static.image not load? The delegate swallows
;; the load failure; require it directly and print the cause chain.
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/z1_load_probe.clj
(ns t1.z1-load-probe)
(try (require 'eve.static.image)
     (println "image loaded; detach var:" (find-var 'eve.static.image/detach))
     (catch Throwable t
       (loop [c t]
         (when c
           (println "CAUSE:" (class c) "-" (ex-message c))
           (recur (.getCause c))))))
(System/exit 0)
