;; Cross-host journal check: this JVM process builds a J1 domain, does
;; 5 swaps, then HALTS without closing — the journal keeps its records,
;; nothing is checkpointed. The root file is then wounded by hand
;; (atom-table slot zeroed, epoch word reset), the heartbeat is left
;; to go stale, and a NODE process opens the domain: only a correct
;; Node-side journal scan (framing + CRC32 identical to the JVM's)
;; can install epoch 5's table. A JVM cread afterwards must read 5.
(require '[eve.atom :as atom])
(let [dir (first *command-line-args*)
      dom (atom/persistent-atom-domain dir {:journal? true :journal-batch 1})
      a (atom/persistent-atom {:id :eve/main :persistent dir} 0)]
  (dotimes [_ 5] (swap! a inc))
  (println :jvm-wrote @a :journal-bytes
           (.length (java.io.File. (str dir ".journal"))))
  (flush)
  (.halt (Runtime/getRuntime) 0))
