(ns eve.atom-repro-scanner-test
  "MAP-FIX regression (lane-m/atom-repro-scanner @ cf091b69e4,
   docs/stm-eve/probe_atom_repro.clj), ported to the release tree.

   The original probe drove a plain Eve atom through eve.stm install!'s
   steady-state transition — a cell map {:value :point :version :history}
   whose :history is a fresh host vector of <=10 entry maps — swapped in
   by cell-cas!'s own fn, 6,000 times. On the stm-in-eve-era atom.cljc it
   threw IndexOutOfBoundsException at install 3,007: the retire else-arm's
   closure walk fed a vector TAIL block (an offset array) to
   eve.map/scan-node-value-sab-ptrs, whose only guard is byte 0 ==
   NODE_TYPE_BITMAP; once the tail's slot-0 offset had low byte 1 the
   offset array parsed as a HAMT node.

   Port: the original's census instrumentation read internals that the
   release tree no longer has (closure-payload-ptr, the heap regions'
   free counts, the retire-q's .size); the WORKLOAD — the part that threw
   — is carried verbatim, on both domain kinds the release offers (the
   original's ea/atom-domain, and a persistent mmap domain), past the
   original's 6,000-install bar, with the exact final value checked."
  (:require [clojure.test :refer [deftest is]]
            [eve.atom :as ea]))

(defn- install-loop!
  "The probe's install loop, STM-free: returns [:reached-cap value] or
   [:threw class install]."
  [cell n]
  (let [counter (volatile! 0)]
    (try
      (loop [i 1]
        (let [cur @cell
              new-cell {:value (inc (:value cur))
                        :point i
                        :version (inc (:version cur))
                        :history (vec (take 10 (into [{:point (:point cur)
                                                       :value (:value cur)}]
                                                     (:history cur))))}]
          (swap! cell (fn [c] (if (= c cur) new-cell c)))
          (vreset! counter i))
        (if (>= i n) [:reached-cap @cell] (recur (inc i))))
      (catch Throwable t
        [:threw (str (type t)) @counter (.getMessage t)]))))

(def ^:private N 6000)

(defn- expected-cell [n]
  {:value n :point n :version n
   :history (vec (for [p (range (dec n) (- n 11) -1)] {:point p :value p}))})

(deftest map-fix-history-vector-installs-heap-domain
  (let [domain (ea/atom-domain {})
        cell (ea/lookup-or-create-mmap-atom!
              domain "atom-repro/cell" {:value 0 :point 0 :version 0 :history []})
        [outcome v :as r] (install-loop! cell N)]
    (is (= :reached-cap outcome) (str "the install loop must not throw: " (pr-str r)))
    (is (= (expected-cell N) v) "the cell holds exactly the 6,000th install")))

(deftest map-fix-history-vector-installs-persistent-domain
  (let [base (str (System/getProperty "java.io.tmpdir")
                  "/eve-atom-repro-" (rand-int 1000000000) "/d")
        domain (ea/persistent-atom-domain base)
        cell (ea/lookup-or-create-mmap-atom!
              domain "atom-repro/cell" {:value 0 :point 0 :version 0 :history []})
        [outcome v :as r] (install-loop! cell N)]
    (is (= :reached-cap outcome) (str "the install loop must not throw: " (pr-str r)))
    (is (= (expected-cell N) v) "the cell holds exactly the 6,000th install")
    (ea/close-atom-domain! domain)))
