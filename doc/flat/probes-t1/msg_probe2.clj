;; T1 finisher #4, probe 2 — is detach's messages cost per-call steady
;; state or JIT warmup, and does it scale linearly in n? Detach the same
;; holder 3 times, at n from argv. Prints each call.
(ns t1.msg-probe2
  (:require [eve.atom :as ea]
            [eve.static.corpora :as corpora]
            [eve.static.transfer :as tf]))

(def ^:private detach-value @#'tf/detach-value)
(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(let [n (Long/parseLong (first *command-line-args*))
      v (corpora/build :messages-5k n)
      dom (ea/atom-domain nil :capacities capacities)
      holder (ea/lookup-or-create-mmap-atom! dom "msg2/hold" nil)
      t0 (System/nanoTime)]
  (swap! holder (fn [_] v))
  (println "n=" n "store-ms=" (long (/ (- (System/nanoTime) t0) 1e6)))
  (dotimes [i 3]
    (let [t (System/nanoTime)
          bs (ea/read-pinned holder (fn [lv] (detach-value lv)))]
      (println "detach" i "ms=" (long (/ (- (System/nanoTime) t) 1e6))
               "bytes=" (alength bs))
      (flush))))
(System/exit 0)
