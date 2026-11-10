;; eve.stm check suite — PORTABLE adaptation for seats without the
;; JVM thread machinery (nbb, Jolt, glojure, Basilisp, clojerl,
;; CLJS family). Same expected answers as probe_stm.clj wherever the
;; algebra is the same; every adaptation is named in its row label:
;;   S = sequential form of the numbered JVM check (identical
;;       expectations; contention absent by construction);
;;   A = algebra form (commute/transfer totals preserved, thread
;;       storm replaced by the same operations run sequentially).
;; Rows that exist only as concurrency facts (7-10 snapshot/skew
;; interleavings, 11/13 storms as storms, 20 forced retry, 21-23
;; interruption) are NOT simulated here: run-in-transaction joins a
;; nested call (stock semantics, stm.cljc), so no single-threaded
;; interleave exists through the public API. Those rows are reported
;; per host as NOT-RUN (needs the seat's thread/process machinery),
;; never silently dropped.
;; The probe uses NO host seam of its own: no threads, no latches,
;; no clock, no System/exit. It prints PASS/FAIL lines and a final
;; PROBE-STM-PORTABLE summary; the runner reads the log.
;; (State lives in top-level atoms + one let inside run: Basilisp's
;; analyzer does not resolve defs nested in a defn body.)

(ns stm-portable
  "The portable check suite as a namespace: dialect hosts load-file
   this file and call stm-portable/run (or -main); the CLJS seats go
   through a seat main that performs the slab init ritual first
   (hosts/nbb/stm_nbb_main.cljc)."
  (:require [eve.stm :as stm]))

(def checks (atom 0))
(def failures (atom []))
(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))
(defn check-true [label actual] (check label true (boolean actual)))

(defn mk [v & [opts]] (if opts (stm/make-ref v opts) (stm/make-ref v)))

(defn run []
  (let [r1 (mk 41)
        r2 (mk 100)
        counter (mk 0)
        from (mk 10000)
        to (mk 10000)
        vr (mk 5 {:validator (fn [v] (>= v 0))})
        wr (mk 1)
        watch-log (atom [])
        delivered (atom [])]
    ;; -- S1-S2: construction, deref outside a transaction -------------
    (check-true "S1 (=JVM 1) ref-value?" (stm/ref-value? r1))
    (check "S2 (=JVM 2) deref outside tx" 41 (stm/deref-ref r1))

    ;; -- S3-S4: a transaction commits ----------------------------------
    (check "S3 (=JVM 3) dosync alter returns new value" 42
           (stm/run-in-transaction (fn [] (stm/alter* r1 inc []))))
    (check "S4 (=JVM 4) committed" 42 (stm/deref-ref r1))

    ;; -- S5: writes outside a transaction throw -------------------------
    (check "S5 (=JVM 5) alter outside tx throws" :threw
           (try (stm/alter* r1 inc []) :no-throw
                (catch #?(:cljs :default :lpy python/RuntimeError :cljr System.InvalidOperationException :jank jank.runtime.object_ref :default IllegalStateException) e :threw)))

    ;; -- S6: an aborting transaction leaves no partial writes -----------
    (check "S6 (=JVM 6) abort rolls back both refs" [42 100]
           (do (try (stm/run-in-transaction
                     (fn [] (stm/ref-set* r1 0) (stm/ref-set* r2 0)
                       (throw (ex-info "boom" {}))))
                    (catch #?(:cljs :default :lpy python/Exception :cljr System.Exception :default Throwable) e :caught))
               [(stm/deref-ref r1) (stm/deref-ref r2)]))

    ;; -- A11: commute algebra (JVM 11's totals, sequential) -------------
    (try (dotimes [_ 8]
           (dotimes [_ 200]
             (stm/run-in-transaction (fn [] (stm/commute* counter inc [])))))
         (catch #?(:cljs :default :lpy python/Exception :cljr System.Exception :default Throwable) e
           (println "INFO A11 storm threw (ceiling?):" (ex-message e))))
    (check "A11 (algebra of JVM 11) 8x200 commutes, none lost" 1600 (stm/deref-ref counter))

    ;; -- A12/A13: transfer algebra (JVM 12/13's totals, sequential) ------
    (try (dotimes [_ 4]
           (dotimes [_ 500]
             (stm/run-in-transaction
              (fn [] (stm/alter* from - [1]) (stm/alter* to + [1])))))
         (catch #?(:cljs :default :lpy python/Exception :cljr System.Exception :default Throwable) e
           (println "INFO A12/A13 storm threw (ceiling?):" (ex-message e))))
    (check "A12 (algebra of JVM 12) transfer invariant holds" 20000
           (+ (stm/deref-ref from) (stm/deref-ref to)))
    (check "A13 (algebra of JVM 13) all transfers landed" [8000 12000]
           [(stm/deref-ref from) (stm/deref-ref to)])

    ;; -- S14-S15: validators run at commit --------------------------------
    (check "S14 (=JVM 14) validator rejects the commit" :threw
           (try (stm/run-in-transaction (fn [] (stm/ref-set* vr -1))) :no-throw
                (catch #?(:cljs :default :lpy python/RuntimeError :cljr System.InvalidOperationException :jank jank.runtime.object_ref :default IllegalStateException) e :threw)))
    (check "S15 (=JVM 15) value unchanged after rejected commit" 5 (stm/deref-ref vr))

    ;; -- S16: watches fire once per written ref, at commit -----------------
    (stm/add-watch* wr :w (fn [k r old new] (swap! watch-log conj [k old new])))
    (stm/run-in-transaction (fn [] (stm/ref-set* wr 2) (stm/ref-set* wr 3)))
    (check "S16 (=JVM 16) watch fired once, pre-commit and final values" [[:w 1 3]] @watch-log)

    ;; -- S17-S18: sends: held, exactly once, on commit ----------------------
    (stm/set-send-dispatcher! (fn [msg] (swap! delivered conj msg)))
    (stm/run-in-transaction (fn [] (stm/dispatch! [:committed])))
    (check "S17 (=JVM 17) send from a committing tx fired once" [[:committed]] @delivered)
    (try (stm/run-in-transaction
          (fn [] (stm/dispatch! [:aborted]) (throw (ex-info "boom" {}))))
         (catch #?(:cljs :default :lpy python/Exception :cljr System.Exception :default Throwable) e nil))
    (check "S18 (=JVM 18) send from an aborting tx never fired" [[:committed]] @delivered)

    ;; -- S19: history count ---------------------------------------------------
    (check "S19 (=JVM 19) history count" 1 (stm/ref-history-count wr))

    (println "INFO stm stats:" (stm/stats))
    (println (if (empty? @failures)
               (str "PROBE-STM-PORTABLE: ALL " @checks " CHECKS PASS")
               (str "PROBE-STM-PORTABLE: " (count @failures) " FAILURES: " @failures))))
  nil)

(defn -main [& _args] (run))
