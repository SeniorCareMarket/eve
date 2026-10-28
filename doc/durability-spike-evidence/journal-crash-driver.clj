;; Journal recovery driver (durability spike): modes
;;   crash <base> <variant> <n>   open the journal domain, do n counter
;;                                swaps, print the value, then HALT the
;;                                JVM (no shutdown hooks, no close —
;;                                the domain is left exactly as a crash
;;                                leaves it)
;;   read  <base>                 open (no journal opts — recovery is a
;;                                property of the domain's files) and
;;                                print the value a reopener sees
;; The shell driver between the two: wind the .root file back by hand
;; (zero the atom table, reset the epoch word) to put the checkpoint
;; behind the journal, and wait out the 30 s heartbeat staleness so
;; the opener's recovery engages (a live domain is never recovered).
(require '[eve.atom :as atom])

(let [[mode base variant n] *command-line-args*]
  (case mode
    "crash"
    (let [opts (case variant
                 "J1" {:journal? true :journal-batch 1}
                 "J10" {:journal? true :journal-batch 10})
          dom (atom/persistent-atom-domain base opts)
          a (atom/persistent-atom {:id :eve/main :persistent base} 0)]
      (dotimes [_ (parse-long n)] (swap! a inc))
      (println :before-halt :value @a)
      (flush)
      (.halt (Runtime/getRuntime) 0))
    "read"
    (let [dom (atom/persistent-atom-domain base {:journal? true})
          a (atom/persistent-atom {:id :eve/main :persistent base} nil)]
      (println :reopened :value @a)
      (atom/close-atom-domain! dom))))

(System/exit 0)
