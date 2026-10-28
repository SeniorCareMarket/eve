(ns eve.runner.jolt-main
  "EVE on jolt: the shared (.cljc) tests, run from the source tree (jolt's
   source roots are the :paths of the deps.edn in its working directory).

     jolt -Scp src:test -m eve.runner.jolt-main test [ns ...]

   Exits non-zero on a failure. The shared tests not listed here need
   eve.array's typed arrays (coalesc-align, array-hosts, dataset, tensor),
   which are the JVM's, bb's and ClojureScript's so far."
  (:require [clojure.test :as t]))

;; one line per test as it ends, with its time: a suite that stalls names
;; the test it stalled in
(def ^:private test-start (atom 0))
(defmethod t/report :begin-test-var [_] (reset! test-start (System/currentTimeMillis)))
(defmethod t/report :end-test-var [m]
  (println "done" (:var m) (str (- (System/currentTimeMillis) @test-start) "ms")))

(def shared-tests
  "The test namespaces jolt runs as written."
  '[eve.hamt-util-test eve.eve-bytes-fixture-test eve.deftype.type-id-test
    eve.deftype.rb-tree-test eve.numeric-tags-test eve.collection-hash-test eve.reference-tag-test eve.big-value-test eve.sorted-coll-test eve.keyring-test eve.value-buffer-test eve.vec-retire-test eve.reclaim-test eve.tally-test eve.domain-open-test eve.deftype.int-map-test eve.rrb-vector-test eve.thread-host-test eve.thread-test eve.thread-meta-test eve.thread-domain-test eve.thread-fold-test eve.thread-fold-group-test
    ;; the network rung and the chat client's core: the http feature, http/socket
    eve.thread-net-test eve.aegis.chat-test
    ;; the crypto feature's fast-path rows
    eve.net.crypto-fast-path-test])

(defn -main [& [cmd & args]]
  (case cmd
    "test" (let [syms (if (seq args) (map symbol args) shared-tests)
                 ;; ns/name: that one test (with its namespace's fixtures)
                 tests (filter namespace syms)
                 nses (remove namespace syms)
                 _ (apply require (distinct (concat nses (map (comp symbol namespace) tests))))
                 r (merge-with + (when (seq nses) (apply t/run-tests nses))
                               (when (seq tests)
                                 (binding [t/*report-counters* (ref t/*initial-report-counters*)]
                                   (t/test-vars (map resolve tests))
                                   @t/*report-counters*)))]
             (println :summary (select-keys r [:test :pass :fail :error]))
             (System/exit (if (zero? (+ (:fail r 0) (:error r 0))) 0 1)))
    "perf" (do (require 'eve.perf-suite)
               ((resolve 'eve.perf-suite/run!) {:budget-ms (some-> (first args) read-string)})
               (System/exit 0))
    (do (println "usage: -m eve.runner.jolt-main test [ns ...]")
        (System/exit 2))))
