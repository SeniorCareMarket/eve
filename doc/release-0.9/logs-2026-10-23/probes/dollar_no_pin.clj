;; dollar_no_pin.clj — negative control for eve.read-region-test: the same tests with the region's hold made a no-op
;; (no pin taken), so a test that passes here does not test the pin.
;;
;;   bb -cp "$(bb classpath bb):test" doc/release-0.9/logs-2026-10-23/probes/dollar_no_pin.clj      (from eve/)
(require '[clojure.test :as t] '[eve.read-region :as rr] 'eve.read-region-test)
(with-redefs [rr/hold! (fn [_k _acquire _release] true)]
  (prn (t/run-tests 'eve.read-region-test)))
