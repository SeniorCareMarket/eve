(ns eve.runner.bb-main
  "EVE on babashka: the shared (.cljc) tests and the columnar bench, run from
   the source tree.

     bb -cp src:test -m eve.runner.bb-main test [ns ...]
     bb -cp src:test -m eve.runner.bb-main smoke [ns ...]
     bb -cp src:test -m eve.runner.bb-main bench [name n mode]

   Exits non-zero on a failure."
  (:require [clojure.test :as t]
            [eve.columnar-bench :as cb]))

(def shared-tests
  "The test namespaces every host runs as written."
  '[eve.hamt-util-test eve.coalesc-align-test eve.eve-bytes-fixture-test
    eve.array-hosts-test eve.dataset-test eve.tensor-test eve.deftype.rb-tree-test eve.numeric-tags-test eve.collection-hash-test eve.reference-tag-test eve.big-value-test eve.sorted-coll-test eve.keyring-test eve.value-buffer-test eve.vec-retire-test eve.reclaim-test eve.descriptor-table-test eve.tally-test eve.read-region-test eve.domain-open-test eve.deftype.int-map-test
    eve.deftype.type-id-test eve.rrb-vector-test eve.thread-host-test eve.thread-test eve.thread-meta-test eve.thread-domain-test eve.thread-net-test eve.thread-fold-test eve.thread-fold-group-test
    ;; aegis's clear-evaluation primitives (the crypto feature's fast-path rows)
    ;; the claims test is held back with the chat spec pending the aegis split (release/eve/exclude)
    eve.aegis.rsabssa-test eve.aegis.h2c-test eve.aegis.merkle-test eve.aegis.clsag-test
    ;; the read-regions spike: HPKE and the regions' lockboxes
    eve.aegis.hpke-test eve.aegis.regions-test eve.aegis.regions-claims-test
    eve.net.crypto-fast-path-test])

(def smoke-tests
  "The crypto invariant smoke set: the namespaces a landing runs every time
   (bb test:bb-smoke; gate 17 of VS's scripts/gate-battery). Each carries an
   invariant of the ExtCID v3 / aegis claims line: the claims evaluator's
   refusals (impersonation, non-members, another principal's signature),
   a CLSAG ring signature that verifies only for a real signer under its
   scope (and whose key image links a double-show), RSA blind-ballot and
   hash-to-curve and HPKE published vectors, Merkle openings that bind key
   and value, region lockboxes that exclude removed members, the keyring's
   epoch keys, and the net crypto rows with their refusal batteries. Seeded
   and deterministic throughout (counter-stream randomness, fixed vectors).
   The same directories' benches and spec files are not tests and are not
   here; eve.aegis.pages-v3-test (block/page IO) carries no crypto
   invariant and stays suite-only."
  '[eve.aegis.clsag-test eve.aegis.rsabssa-test
    eve.aegis.h2c-test eve.aegis.hpke-test eve.aegis.merkle-test
    eve.aegis.regions-test eve.aegis.regions-claims-test
    eve.keyring-test
    eve.net.crypto-test eve.net.crypto-fast-path-test eve.net.ed25519-test])

(defn -main [& [cmd & args]]
  (case cmd
    "test"  (let [nses (if (seq args) (map symbol args) shared-tests)
                  _ (apply require nses)
                  r (apply t/run-tests nses)]
              (println :summary (select-keys r [:test :pass :fail :error]))
              (System/exit (if (t/successful? r) 0 1)))
    "smoke" (let [nses (if (seq args) (map symbol args) smoke-tests)
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
    (do (println "usage: -m eve.runner.bb-main test [ns ...] | smoke [ns ...] | bench [name n mode]")
        (System/exit 2))))
