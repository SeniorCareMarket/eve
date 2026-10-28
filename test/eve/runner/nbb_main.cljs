(ns eve.runner.nbb-main
  "EVE on nbb (ClojureScript on SCI, Node): the shared (.cljc) tests and the
   columnar bench, run from the source tree with the CLJS runner's setup (slab
   allocator, worker id, global atom domain).

     nbb --classpath src:test -m eve.runner.nbb-main test [ns ...]
     nbb --classpath src:test -m eve.runner.nbb-main bench [name n mode]

   Run from eve/: the mmap atoms load EVE's addon (build/Release) from the
   working directory. Exits non-zero on a failure."
  (:require [cljs.test :as t]
            [eve.deftype-proto.alloc :as alloc]
            [eve.process-slab :as ps]
            [eve.deftype-proto.data :as d]
            [eve.atom :as a]
            [eve.columnar-bench :as cb]
            [eve.hamt-util-test]
            [eve.coalesc-align-test]
            [eve.eve-bytes-fixture-test]
            [eve.array-hosts-test]
            [eve.dataset-test]
            [eve.tensor-test]
            [eve.deftype.rb-tree-test]
            [eve.deftype.int-map-test]
            [eve.vec-retire-test]
            [eve.reclaim-test]
            [eve.tally-test]
            [eve.read-region-test]
            [eve.domain-open-test]
            [eve.deftype.type-id-test]
            [eve.rrb-vector-test]
            [eve.thread-host :as thread-host]
            [eve.thread-host-test]
            [eve.thread-test]
            [eve.thread-meta-test]
            [eve.thread-domain-test]
            [eve.thread-fold-test]
            [eve.thread-fold-group-test]
            [eve.thread-net-test]))

(def shared-tests
  "The test namespaces every host runs as written."
  '[eve.hamt-util-test eve.coalesc-align-test eve.eve-bytes-fixture-test
    eve.array-hosts-test eve.dataset-test eve.tensor-test eve.deftype.rb-tree-test eve.numeric-tags-test eve.collection-hash-test eve.reference-tag-test eve.big-value-test eve.sorted-coll-test eve.keyring-test eve.value-buffer-test eve.vec-retire-test eve.reclaim-test eve.tally-test eve.domain-open-test eve.deftype.int-map-test
    eve.deftype.type-id-test eve.rrb-vector-test eve.thread-host-test eve.thread-test eve.thread-meta-test eve.thread-domain-test eve.thread-net-test eve.thread-fold-test eve.thread-fold-group-test
    ;; the crypto feature's fast-path rows
    eve.net.crypto-fast-path-test])

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println :summary (select-keys m [:test :pass :fail :error]))
  ;; a worker's start, when the run started one (the thread contract's (driving) :spawn-ms: the last worker's)
  (when-let [ms (try (:spawn-ms (thread-host/driving)) (catch :default _ nil))]
    (when (pos? ms) (println :worker-start-ms ms)))
  (js/process.exit (if (t/successful? m) 0 1)))

(defn- run [f]
  (-> (ps/init! :force true)
      (.then (fn [_]
               (set! d/*worker-id* 1)
               (set! a/*global-atom-instance* (a/atom-domain {}))
               (f)))
      (.catch (fn [e]
                (println "ERROR:" (.-message e))
                (println (.-stack e))
                (js/process.exit 2)))))

(defn- next-macrotask [] (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(defn- test-vars-of [n]
  (sort-by (fn [v] (:line (meta v))) (filter (fn [v] (:test (meta v))) (vals (ns-interns n)))))

(defn- run-tests-yielding
  "cljs.test's run-tests, one test var at a time with the event loop run between them. Node joins a worker that has
   ended — and releases its isolate — only on its starter's event loop (measured 2026-10-19: four ended workers stayed
   resident, ~75 MB each, ~210 MB with eve loaded, until the main thread yielded), so a suite that starts many threads
   (eve.thread's battery starts ~47) must yield between tests or hold them all. (The shared tests' fixtures are all
   :each: run per var, as before.)"
  [nses]
  (t/set-env! (t/empty-env))
  (-> (reduce (fn [p n]
                (.then p (fn [_]
                           (t/do-report {:type :begin-test-ns :ns n})
                           (reduce (fn [p v] (.then p (fn [_] (t/test-vars [v]) (next-macrotask))))
                                   (js/Promise.resolve nil) (test-vars-of n)))))
              (js/Promise.resolve nil) nses)
      (.then (fn [_] (t/report (assoc (:report-counters (t/get-current-env)) :type :end-run-tests))))))

(defn -main [& [cmd & args]]
  (case cmd
    "test"  (run (fn []
                   (let [nses (if (seq args) (map symbol args) shared-tests)]
                     ;; load each (nbb's require is asynchronous), one at a time
                     (-> (reduce (fn [p n] (.then p (fn [_] (require n))))
                                 (js/Promise.resolve nil) nses)
                         (.then (fn [_] (run-tests-yielding nses)))))))
    "bench" (run #(let [[bench n mode] args
                        fails (if bench
                                (cb/run-one! bench (js/parseInt n) (keyword mode))
                                (cb/run-all!))]
                    (js/process.exit (if (pos? fails) 1 0))))
    "perf"  (run (fn [] (.then (require 'eve.perf-suite)
                               (fn [_] ((resolve 'eve.perf-suite/run!) {:budget-ms (some-> (first args) read-string)})
                                 (js/process.exit 0)))))
    (do (println "usage: -m eve.runner.nbb-main test [ns ...] | bench [name n mode]")
        (js/process.exit 2))))
