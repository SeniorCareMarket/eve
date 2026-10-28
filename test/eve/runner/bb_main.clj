(ns eve.runner.bb-main
  "EVE on babashka: the shared (.cljc) tests and the columnar bench, run from
   the source tree.

     bb -cp src:test -m eve.runner.bb-main test [ns ...]
     bb -cp src:test -m eve.runner.bb-main bench [name n mode]

   Exits non-zero on a failure."
  (:require [clojure.test :as t]
            [eve.columnar-bench :as cb]))

(def shared-tests
  "The test namespaces every host runs as written."
  '[eve.hamt-util-test eve.coalesc-align-test eve.eve-bytes-fixture-test
    eve.array-hosts-test eve.dataset-test eve.tensor-test eve.deftype.rb-tree-test eve.numeric-tags-test eve.collection-hash-test eve.reference-tag-test eve.big-value-test eve.sorted-coll-test eve.keyring-test eve.value-buffer-test eve.vec-retire-test eve.reclaim-test eve.descriptor-table-test eve.tally-test eve.read-region-test eve.domain-open-test eve.deftype.int-map-test
    eve.deftype.type-id-test eve.rrb-vector-test eve.thread-host-test eve.thread-test eve.thread-meta-test eve.thread-domain-test eve.thread-net-test eve.thread-fold-test eve.thread-fold-group-test
    ;; aegis's clear-evaluation primitives (aegis/docs/simple-spike/REPORT.md; the crypto feature's fast-path rows)
    eve.aegis.rsabssa-test eve.aegis.h2c-test eve.aegis.merkle-test eve.aegis.clsag-test eve.aegis.claims-test
    ;; the read-regions spike (aegis/docs/read-regions-spike/REPORT.md): HPKE and the regions' lockboxes
    eve.aegis.hpke-test eve.aegis.regions-test eve.aegis.regions-claims-test
    ;; the eve-chat capstone (eve/capstone/eve-chat): the REPL chat client's portable core
    eve.aegis.chat-test
    eve.net.crypto-fast-path-test])

(defn -main [& [cmd & args]]
  (case cmd
    "test"  (let [nses (if (seq args) (map symbol args) shared-tests)
                  _ (apply require nses)
                  r (apply t/run-tests nses)]
              (println :summary (select-keys r [:test :pass :fail :error]))
              (System/exit (if (t/successful? r) 0 1)))
    "bench" (let [[bench n mode] args
                  fails (if bench
                          (cb/run-one! bench (parse-long n) (keyword mode))
                          (cb/run-all!))]
              (System/exit (if (pos? fails) 1 0)))
    "perf"  (do (require 'eve.perf-suite)
                ((resolve 'eve.perf-suite/run!) {:budget-ms (some-> (first args) read-string)})
                (System/exit 0))
    (do (println "usage: -m eve.runner.bb-main test [ns ...] | bench [name n mode]")
        (System/exit 2))))
