(ns eve.runner.squint-main
  "EVE on squint (ClojureScript-syntax compiled to ES modules, Node): the
   shared (.cljc) tests, run from the compiled tree with the CLJS runner's
   setup (slab allocator, worker id, global atom domain).

     node out/eve/runner/squint_main.mjs test [ns ...]

   The namespaces are required statically (squint compiles ahead; there
   is no runtime require of an uncompiled namespace). Exits non-zero on
   a failure. Pattern: test/eve/runner/nbb_main.cljs, minus nbb's
   per-var yielding (squint takes thread/none: the thread suites answer
   the contract rows only, and no worker outlives its starter here)."
  (:require [clojure.test :as t]
            [eve.process-slab :as ps]
            [eve.deftype-proto.data :as d]
            [eve.atom :as a]
            [eve.hamt-util-test]
            [eve.coalesc-align-test]
            [eve.eve-bytes-fixture-test]
            [eve.array-hosts-test]
            [eve.dataset-test]
            [eve.tensor-test]
            [eve.sorted-migration-test]
            [eve.vec-retire-test]
            [eve.reclaim-test]
            [eve.tally-test]
            [eve.read-region-test]
            [eve.domain-open-test]
            [eve.deftype.type-id-test]
            [eve.rrb-vector-test]
            [eve.thread-host-test]
            [eve.thread-test]
            [eve.thread-meta-test]
            [eve.thread-fold-test]
            [eve.numeric-tags-test]
            [eve.collection-hash-test]
            [eve.reference-tag-test]
            [eve.big-value-test]
            [eve.sorted-coll-test]
            [eve.keyring-test]
            [eve.int-key-nested-value-test]
            [eve.value-buffer-test]
            [eve.net.crypto-fast-path-test]
            [eve.slab-io-test]))

(def shared-tests
  "The test namespaces every host runs as written (nbb_main's list, plus
   eve.slab-io-test, whose root is adapters/slab-io/test), minus
   threadless-excluded below."
  '[eve.hamt-util-test eve.coalesc-align-test eve.eve-bytes-fixture-test
    eve.array-hosts-test eve.dataset-test eve.tensor-test eve.sorted-migration-test eve.numeric-tags-test eve.collection-hash-test eve.reference-tag-test eve.big-value-test eve.sorted-coll-test eve.keyring-test eve.int-key-nested-value-test eve.value-buffer-test eve.vec-retire-test eve.reclaim-test eve.tally-test eve.domain-open-test
    eve.deftype.type-id-test eve.rrb-vector-test eve.thread-host-test eve.thread-test eve.thread-meta-test eve.thread-fold-test
    eve.net.crypto-fast-path-test])

(def threadless-excluded
  "Namespaces the squint seat does not run: their rows coordinate
   through domain pools — a starter derefs a submit to a domain pool
   from inside start!, and only a pool thread (or a second runtime
   concurrently driving) can complete it. Squint's thread host is
   thread/none (max-threads 0), so the deref parks on the cell
   doorbell forever (measured 2026-11-13: staged bring-up passes
   runtimes and pools, parks at the svc starter's deref). These rows
   need a thread host; the seat's is none, the same honest limit the
   cherry seat declares. Local-pool thread tests (eve.thread-test,
   eve.thread-fold-test) run and pass their rows here."
  '[eve.thread-domain-test eve.thread-net-test eve.thread-fold-group-test])

(defn -main [& _]
  (let [argv (vec (drop 2 (.-argv js/process)))
        cmd (first argv)
        args (rest argv)]
    (if (not= cmd "test")
      (do (println "usage: squint-main test [ns ...]")
          (js/process.exit 2))
      (-> (ps/init! :force true)
          (.then (fn [_]
                   (set! d/*worker-id* 1)
                   (set! a/*global-atom-instance* (a/atom-domain {}))
                   (let [nses (if (seq args) (map symbol args) shared-tests)
                         res (apply t/run-tests nses)]
                     (println :summary (select-keys res [:test :pass :fail :error]))
                     (js/process.exit (if (zero? (+ (:fail res) (:error res))) 0 1)))))
          (.catch (fn [e]
                    (println "ERROR:" (.-message e))
                    (println (.-stack e))
                    (js/process.exit 2)))))))

(-main)
