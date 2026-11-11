(ns eve.runner.cljr-main
  "EVE on ClojureCLR: the shared (.cljc) tests, run from the source tree
   (ClojureCLR's classpath is CLOJURE_LOAD_PATH).

     CLOJURE_LOAD_PATH=src:test cljr -m eve.runner.cljr-main test [ns ...]

   Exits non-zero on a failure. The shared tests not listed here need
   eve.array's typed arrays (coalesc-align, array-hosts, dataset, tensor),
   which are the JVM's, bb's and ClojureScript's so far."
  (:require [clojure.test :as t]))

(def shared-tests
  "The test namespaces ClojureCLR runs as written."
  '[eve.clock-uuid-test eve.associative-transient-test eve.hamt-util-test eve.eve-bytes-fixture-test eve.deftype.type-id-test
    eve.deftype.rb-tree-test eve.numeric-tags-test eve.overflow-test eve.collection-hash-test eve.reference-tag-test eve.big-value-test eve.sorted-coll-test eve.keyring-test eve.value-buffer-test eve.vec-retire-test eve.reclaim-test eve.tally-test eve.domain-open-test eve.deftype.int-map-test eve.rrb-vector-test eve.thread-host-test eve.thread-test eve.thread-meta-test eve.thread-domain-test eve.thread-net-test eve.thread-fold-test eve.thread-fold-group-test
    ;; the crypto feature's fast-path rows (AesGcm, Ed25519 over BigInteger)
    eve.net.crypto-fast-path-test])

(defn -main [& [cmd & args]]
  (case cmd
    "test" (let [nses (if (seq args) (map symbol args) shared-tests)
                 _ (apply require nses)
                 r (apply t/run-tests nses)]
             (println :summary (select-keys r [:test :pass :fail :error]))
             (Environment/Exit (if (t/successful? r) 0 1)))
    "perf" (do (require 'eve.perf-suite)
               ((resolve 'eve.perf-suite/run!) {:budget-ms (some-> (first args) read-string)})
               (Environment/Exit 0))
    (do (println "usage: -m eve.runner.cljr-main test [ns ...]")
        (Environment/Exit 2))))
