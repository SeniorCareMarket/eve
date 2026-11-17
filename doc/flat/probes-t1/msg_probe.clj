;; T1 finisher #4 — the messages-5k B4 corpus dies SILENTLY on J (header
;; only in the log, both the 4-corpus run and the messages-only run at
;; ae9366e): no row, no error. This probe walks the bench's own phases at
;; a reduced size with prints between them, so whichever phase dies is
;; named by the last line printed.
;;
;; Run: cd <tree> && clojure -M doc/static/probes-t1/msg_probe.clj [n]
(ns t1.msg-probe
  (:require [eve.atom :as ea]
            [eve.static :as st]
            [eve.static.corpora :as corpora]
            [eve.static.transfer :as tf]))

(def ^:private detach-value @#'tf/detach-value)
(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(defn- fresh-atom [name]
  (let [dom (ea/atom-domain nil :capacities capacities)]
    (ea/lookup-or-create-mmap-atom! dom name nil)))

(defn- phase [label f]
  (print (str label " ... ")) (flush)
  (let [t0 (System/nanoTime)
        r (f)]
    (println (format "ok %.1f ms" (/ (- (System/nanoTime) t0) 1e6)))
    (flush)
    r))

(let [n (if (seq *command-line-args*) (Long/parseLong (first *command-line-args*)) 500)
      rt (Runtime/getRuntime)]
  (println "msg-probe n=" n "max-heap-mb=" (quot (.maxMemory rt) 1048576))
  (let [v (phase "build corpus" #(corpora/build :messages-5k n))
        holder (phase "fresh holder" #(fresh-atom "msg/hold"))
        _ (phase "store into holder (writers)" #(swap! holder (fn [_] v)))
        bytes (phase "detach-value" #(ea/read-pinned holder (fn [lv] (detach-value lv))))
        _ (println "image bytes:" (alength bytes))
        t (phase "fresh target" #(fresh-atom "msg/zip"))
        _ (phase "place-image!" #(tf/place-image! t bytes))
        _ (phase "read back ==" #(= v @t))
        t2 (phase "fresh target 2" #(fresh-atom "msg/tf"))
        _ (phase "transfer!" #(st/transfer! t2 holder))
        _ (phase "read back 2 ==" #(= v @t2))]
    (println "MSG PROBE DONE n=" n)))
(System/exit 0)
