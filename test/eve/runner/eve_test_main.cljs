(ns eve.runner.eve-test-main
  "Standalone eve test runner. Runs all eve test suites independently of
   cljs-thread. Suite selection via CLI args.

   Usage: node target/eve-test/all.js [suite]
   Example: node target/eve-test/all.js slab"
  (:require
   [goog]
   [cljs.test :as t]
   [clojure.string :as str]
   [eve.deftype-proto.alloc :as eve-alloc]
   [eve.process-slab :as ps]
   [eve.atom :as a]
   [eve.data :as d]

   ;; Pool reset modules (for isolated test namespace support)
   [eve.map :as eve-map]
   [eve.vec :as eve-vec]
   [eve.list :as eve-list]
   [eve.set :as eve-set]

   ;; All test namespaces (must be required so they compile)
   [eve.deftype-test :as deftype-test]
   [eve.array-test :as array-test]
   [eve.map-test :as map-test]
   [eve.vec-test :as vec-test]
   [eve.vec-retire-test :as vec-retire-test]
   [eve.reclaim-test :as reclaim-test]
   [eve.tally-test :as tally-test]
   [eve.read-region-test :as read-region-test]
   [eve.thread-host :as thread-host]
   [eve.thread-host-test :as thread-host-test]
   [eve.thread-test :as thread-test]
   [eve.thread-meta-test :as thread-meta-test]
   [eve.thread-domain-test :as thread-domain-test]
   [eve.thread-net-test :as thread-net-test]
   [eve.thread-fold-test :as thread-fold-test]
   [eve.thread-fold-group-test :as thread-fold-group-test]
   [eve.list-test :as list-test]
   [eve.set-test :as set-test]
   [eve.large-scale-test :as large-scale-test]
   [eve.epoch-gc-test :as epoch-gc-test]
   [eve.obj-test :as obj-test]
   [eve.batch2-validation-test :as batch2-test]
   [eve.batch3-validation-test :as batch3-test]
   [eve.batch4-validation-test :as batch4-test]
   [eve.deftype.int-map-test :as int-map-test]
   [eve.deftype.rb-tree-test :as rb-tree-test]
   [eve.numeric-tags-test :as numeric-tags-test]
   [eve.collection-hash-test :as collection-hash-test]
   [eve.reference-tag-test :as reference-tag-test]
   [eve.big-value-test :as big-value-test]
   [eve.sorted-coll-test :as sorted-coll-test]
   [eve.keyring-test :as keyring-test]
   [eve.value-buffer-test :as value-buffer-test]
   [eve.deftype.type-id-test :as type-id-test]
   [eve.typed-array-test :as typed-array-test]
   [eve.mem-test :as mem-test]
   [eve.region :as region]
   [eve.mem :as mem]
   [eve.mmap-test :as mmap-test]
   [eve.mmap-worker-test :as mmap-worker-test]
   [eve.mmap-slab-test :as mmap-slab-test]
   [eve.mmap-atom-test :as mmap-atom-test]
   [eve.merkle-phase5-test :as merkle-phase5-test]
   [eve.mmap-atom-e2e-test :as mmap-atom-e2e-test]
   [eve.mmap-domain-test :as mmap-domain-test]
   [eve.conformance-test :as conformance-test]
   [eve.fuzz-test :as fuzz-test]
   [eve.eve-bytes-fixture-test :as fixture-test]
   [eve.char-key-test :as char-key-test]
   [eve.hamt-util-test :as hamt-util-test]
   [eve.coalesc-align-test :as coalesc-align-test]
   [eve.lustre-test :as lustre-test]
   [eve.lustre-bench :as lustre-bench]
   [eve.dataset-test :as dataset-test]
   [eve.array-hosts-test :as array-hosts-test]
   [eve.tensor-test :as tensor-test]
   [eve.rrb-vector-test :as rrb-vector-test]
   [eve.columnar-bench :as columnar-bench]
   [eve.perf-audit :as perf-audit]
   [eve.perf-suite :as perf-suite]
   [eve.repro-test :as repro-test]
   [eve.slab-leak-test :as slab-leak-test]
   ;; the network atom's portable core (eve/doc/network-atoms/IMPL-PLAN.md §6; eve.http's Node adapter)
   [eve.net.json-test :as net-json-test]
   [eve.net.graft-test :as net-graft-test]
   [eve.net.xxh3-test :as net-xxh3-test]
   [eve.net.codec-fixture-test :as net-codec-fixture-test]
   [eve.net.poseidon2-test :as net-poseidon2-test]
   [eve.net.wire-test :as net-wire-test]
   [eve.net.broker-test :as net-broker-test]
   [eve.net.remote-atom-test :as net-remote-atom-test]
   [eve.net.crypto-test :as net-crypto-test]
   [eve.net.ed25519-test :as net-ed25519-test]
   [eve.net.pruned-swap-test :as net-pruned-swap-test]
   [eve.net.bytes-codec-test :as net-bytes-codec-test]))

;; Anti-DCE exports — namespace aliases as values force the test namespaces to load.
;; The undeclared-var warnings here are expected and harmless.
(goog/exportSymbol "eve.deftype_test" deftype-test)
(goog/exportSymbol "eve.array_test" array-test)
(goog/exportSymbol "eve.map_test" map-test)
(goog/exportSymbol "eve.vec_test" vec-test)
(goog/exportSymbol "eve.vec_retire_test" vec-retire-test)
(goog/exportSymbol "eve.reclaim_test" reclaim-test)
(goog/exportSymbol "eve.tally_test" tally-test)
(goog/exportSymbol "eve.read_region_test" read-region-test)
(goog/exportSymbol "eve.thread_host_test" thread-host-test)
(goog/exportSymbol "eve.thread_test" thread-test)
(goog/exportSymbol "eve.thread_meta_test" thread-meta-test)
(goog/exportSymbol "eve.thread_domain_test" thread-domain-test)
(goog/exportSymbol "eve.thread_fold_test" thread-fold-test)
(goog/exportSymbol "eve.thread_fold_group_test" thread-fold-group-test)
(goog/exportSymbol "eve.thread_net_test" thread-net-test)
(goog/exportSymbol "eve.list_test" list-test)
(goog/exportSymbol "eve.set_test" set-test)
(goog/exportSymbol "eve.large_scale_test" large-scale-test)
(goog/exportSymbol "eve.epoch_gc_test" epoch-gc-test)
(goog/exportSymbol "eve.obj_test" obj-test)
(goog/exportSymbol "eve.batch2_validation_test" batch2-test)
(goog/exportSymbol "eve.batch3_validation_test" batch3-test)
(goog/exportSymbol "eve.batch4_validation_test" batch4-test)
(goog/exportSymbol "eve.deftype.int_map_test" int-map-test)
(goog/exportSymbol "eve.deftype.rb_tree_test" rb-tree-test)
(goog/exportSymbol "eve.numeric_tags_test" numeric-tags-test)
(goog/exportSymbol "eve.collection_hash_test" collection-hash-test)
(goog/exportSymbol "eve.reference_tag_test" reference-tag-test)
(goog/exportSymbol "eve.big_value_test" big-value-test)
(goog/exportSymbol "eve.sorted_coll_test" sorted-coll-test)
(goog/exportSymbol "eve.keyring_test" keyring-test)
(goog/exportSymbol "eve.value_buffer_test" value-buffer-test)
(goog/exportSymbol "eve.deftype.type_id_test" type-id-test)
(goog/exportSymbol "eve.typed_array_test" typed-array-test)
(goog/exportSymbol "eve.mem_test" mem-test)
(goog/exportSymbol "eve.mmap_test" mmap-test)
(goog/exportSymbol "eve.mmap_worker_test" mmap-worker-test)
(goog/exportSymbol "eve.mmap_slab_test" mmap-slab-test)
(goog/exportSymbol "eve.mmap_atom_test" mmap-atom-test)
(goog/exportSymbol "eve.merkle_phase5_test" merkle-phase5-test)
(goog/exportSymbol "eve.mmap_atom_e2e_test" mmap-atom-e2e-test)
(goog/exportSymbol "eve.mmap_domain_test" mmap-domain-test)
(goog/exportSymbol "eve.conformance_test" conformance-test)
(goog/exportSymbol "eve.fuzz_test" fuzz-test)
(goog/exportSymbol "eve.eve_bytes_fixture_test" fixture-test)
(goog/exportSymbol "eve.char_key_test" char-key-test)
(goog/exportSymbol "eve.hamt_util_test" hamt-util-test)
(goog/exportSymbol "eve.coalesc_align_test" coalesc-align-test)
(goog/exportSymbol "eve.lustre_test" lustre-test)
(goog/exportSymbol "eve.lustre_bench" lustre-bench)
(goog/exportSymbol "eve.dataset_test" dataset-test)
(goog/exportSymbol "eve.array_hosts_test" array-hosts-test)
(goog/exportSymbol "eve.tensor_test" tensor-test)
(goog/exportSymbol "eve.rrb_vector_test" rrb-vector-test)
(goog/exportSymbol "eve.columnar_bench" columnar-bench)
(goog/exportSymbol "eve.perf_audit" perf-audit)
(goog/exportSymbol "eve.repro_test" repro-test)
(goog/exportSymbol "eve.slab_leak_test" slab-leak-test)
(goog/exportSymbol "eve.net.json_test" net-json-test)
(goog/exportSymbol "eve.net.graft_test" net-graft-test)
(goog/exportSymbol "eve.net.xxh3_test" net-xxh3-test)
(goog/exportSymbol "eve.net.codec_fixture_test" net-codec-fixture-test)
(goog/exportSymbol "eve.net.poseidon2_test" net-poseidon2-test)
(goog/exportSymbol "eve.net.wire_test" net-wire-test)
(goog/exportSymbol "eve.net.broker_test" net-broker-test)
(goog/exportSymbol "eve.net.remote_atom_test" net-remote-atom-test)
(goog/exportSymbol "eve.net.crypto_test" net-crypto-test)
(goog/exportSymbol "eve.net.ed25519_test" net-ed25519-test)
(goog/exportSymbol "eve.net.pruned_swap_test" net-pruned-swap-test)
(goog/exportSymbol "eve.net.bytes_codec_test" net-bytes-codec-test)

;; Isolated namespace support
(def ^:private isolated-nss
  #{'eve.map-test
    'eve.vec-test
    'eve.vec-retire-test
    'eve.reclaim-test
    'eve.tally-test
    'eve.read-region-test
    'eve.list-test
    'eve.set-test
    'eve.large-scale-test
    'eve.conformance-test
    'eve.fuzz-test})

(defn- recycle-slab-env! []
  (eve-map/reset-pools!)
  (eve-vec/reset-pools!)
  (eve-list/reset-pools!)
  (eve-set/reset-pools!)
  (ps/reset-overflow-fns!)
  (set! a/*global-atom-instance*
        (a/atom-domain {})))

(defmethod t/report [::t/default :begin-test-ns] [m]
  (when (contains? isolated-nss (:ns m))
    (println "  Resetting slab pools for isolated namespace...")
    (recycle-slab-env!))
  (println (str "\nTesting " (:ns m))))

;; Platform detection
(def ^:private node?
  (and (exists? js/process) (exists? js/process.versions)))

(defn- exit! [code]
  (if node?
    (js/process.exit code)
    (do
      (set! js/window.__test_exit_code code)
      (set! js/window.__test_complete true))))

(defn- get-args []
  (if node?
    (vec (.slice js/process.argv 2))
    []))

;; Suite runners
(defn- run-core! []
  (t/run-tests 'eve.deftype-test))

(defn- run-array! []
  (t/run-tests 'eve.array-test))

(defn- run-slab! []
  (t/run-tests
    'eve.map-test
    'eve.vec-test
    'eve.vec-retire-test
    'eve.reclaim-test
    'eve.tally-test
    'eve.read-region-test
    'eve.list-test
    'eve.set-test))

(defn- run-slab-grow!
  "The slab suites and batch2's growth tests over slabs that start small and grow (ps/init! :growable? true, a
   browser's default; Node's slabs hold their capacity from the start)."
  []
  (t/run-tests
    'eve.batch2-validation-test
    'eve.map-test
    'eve.vec-test
    'eve.vec-retire-test
    'eve.reclaim-test
    'eve.tally-test
    'eve.read-region-test
    'eve.list-test
    'eve.set-test
    'eve.large-scale-test))

(defn- run-large-scale! []
  (t/run-tests 'eve.large-scale-test))

(defn- run-epoch-gc! []
  (t/run-tests 'eve.epoch-gc-test))

(defn- run-obj! []
  (t/run-tests 'eve.obj-test))

(defn- run-batch2! []
  (t/run-tests 'eve.batch2-validation-test))

(defn- run-batch3! []
  (t/run-tests 'eve.batch3-validation-test))

(defn- run-batch4! []
  (t/run-tests 'eve.batch4-validation-test))

(defn- run-int-map! []
  (t/run-tests 'eve.deftype.int-map-test))

(defn- run-rb-tree! []
  (t/run-tests 'eve.deftype.rb-tree-test))

(defn- run-deftype! []
  (t/run-tests
    'eve.deftype-test
    'eve.deftype.int-map-test
    'eve.deftype.rb-tree-test
    'eve.deftype.type-id-test))

(defn- run-validation! []
  (t/run-tests
    'eve.batch2-validation-test
    'eve.batch3-validation-test
    'eve.batch4-validation-test))

(defn- run-typed-array! []
  (t/run-tests 'eve.typed-array-test))

(defn- run-mem! []
  (t/run-tests 'eve.mem-test))

(defn- load-native-addon! []
  (let [path (.resolve (js/require "path") "build/Release/mmap_cas.node")]
    (region/load-native-addon! (js/require path))))

(defn- run-mmap! []
  (load-native-addon!)
  (t/run-tests 'eve.mmap-test
               'eve.mmap-worker-test))

(defn- run-mmap-slab! []
  (load-native-addon!)
  (ps/init-mmap-slab! 5 "/tmp/eve-p3-cls5.mem")
  (t/run-tests 'eve.mmap-slab-test))

(defn- run-mmap-atom! []
  (load-native-addon!)
  (t/run-tests 'eve.mmap-atom-test))

(defn- run-merkle-phase5! []
  (load-native-addon!)
  (t/run-tests 'eve.merkle-phase5-test))

(defn- run-mmap-atom-e2e! []
  (let [fs   (js/require "fs")
        path (js/require "path")
        worker (.resolve path "target/eve-test/mmap-worker.js")]
    (when-not (.existsSync fs worker)
      (println "ERROR: mmap-worker.js not found at" worker)
      (println "  Compile it first: npx shadow-cljs compile mmap-worker")
      (exit! 1)))
  (load-native-addon!)
  (t/run-tests 'eve.mmap-atom-e2e-test))

(defn- run-mmap-domain! []
  (load-native-addon!)
  (t/run-tests 'eve.mmap-domain-test))

(defn- run-conformance! []
  (t/run-tests 'eve.conformance-test))

(defn- run-fuzz! []
  (t/run-tests 'eve.fuzz-test))

(defn- run-em5! []
  (t/run-tests 'eve.numeric-tags-test 'eve.collection-hash-test 'eve.reference-tag-test 'eve.big-value-test 'eve.sorted-coll-test 'eve.keyring-test 'eve.value-buffer-test))

(defn- run-fixtures! []
  (t/run-tests 'eve.eve-bytes-fixture-test))

(defn- run-char-key! []
  (t/run-tests 'eve.char-key-test))

(defn- run-hamt-util! []
  (t/run-tests 'eve.hamt-util-test 'eve.coalesc-align-test))

(defn- run-lustre! []
  (load-native-addon!)
  (t/run-tests 'eve.lustre-test))

(defn- run-lustre-bench! []
  (load-native-addon!)
  (lustre-bench/run-all-benches!))

(defn- run-dataset! []
  (t/run-tests 'eve.dataset-test))

(defn- run-array-hosts! []
  (t/run-tests 'eve.array-hosts-test))

(defn- run-tensor! []
  (t/run-tests 'eve.tensor-test))

(defn- run-rrb-vector! []
  (t/run-tests 'eve.rrb-vector-test))

(defn- run-columnar-bench! []
  (when (pos? (columnar-bench/run-all!)) (exit! 1)))

(defn- run-columnar-bench-mmap! []
  (when (pos? (columnar-bench/run-mmap!)) (exit! 1)))

(defn- run-columnar-one!
  "node all.js columnar-one <arith|filter|sort|dataset|tensor> <n> <mmap|in-memory>"
  []
  (let [[_ b n mode] (get-args)]
    (when (pos? (columnar-bench/run-one! b (js/parseInt n) (keyword mode))) (exit! 1))))

(defn- run-columnar-bench-inmem! []
  (when (pos? (columnar-bench/run-inmem!)) (exit! 1)))

(defn- run-perf-audit! []
  (perf-audit/run-audit!))

;; eve's performance suite (test/eve/perf_suite.cljc: the same workloads on every host; disk domains need the addon)
(defn- run-perf-suite! []
  (load-native-addon!)
  (perf-suite/run! {})
  (exit! 0))

(defn- run-repro! []
  (load-native-addon!)
  (repro-test/run-repro!))

(defn- run-slab-leak! []
  (load-native-addon!)
  (t/run-tests 'eve.slab-leak-test))

(defn- run-net! []
  (t/run-tests 'eve.net.json-test 'eve.net.graft-test 'eve.net.xxh3-test 'eve.net.codec-fixture-test 'eve.net.poseidon2-test 'eve.net.wire-test 'eve.net.broker-test 'eve.net.remote-atom-test 'eve.net.crypto-test 'eve.net.ed25519-test 'eve.net.pruned-swap-test))

(defn- run-thread-host! []
  (t/run-tests 'eve.thread-host-test))

(defn- run-thread! []
  (t/run-tests 'eve.thread-test))

(defn- run-thread-meta! []
  (t/run-tests 'eve.thread-meta-test))

(defn- run-thread-domain! []
  (load-native-addon!)
  (t/run-tests 'eve.thread-domain-test 'eve.thread-net-test))

(defn- run-thread-fold! []
  (t/run-tests 'eve.thread-fold-test))

(defn- run-thread-fold-group! []
  (load-native-addon!)
  (t/run-tests 'eve.thread-fold-group-test))

(defn- run-all! []
  (t/run-tests
    'eve.deftype-test
    'eve.array-test
    'eve.map-test
    'eve.vec-test
    'eve.vec-retire-test
    'eve.reclaim-test
    'eve.tally-test
    'eve.read-region-test
    'eve.list-test
    'eve.set-test
    'eve.large-scale-test
    'eve.epoch-gc-test
    'eve.obj-test
    'eve.batch2-validation-test
    'eve.batch3-validation-test
    'eve.batch4-validation-test
    'eve.deftype.int-map-test
    'eve.deftype.rb-tree-test
    'eve.deftype.type-id-test
    'eve.typed-array-test
    'eve.dataset-test
    'eve.array-hosts-test
    'eve.tensor-test
    'eve.eve-bytes-fixture-test
    'eve.char-key-test
    'eve.hamt-util-test
    'eve.coalesc-align-test
    'eve.numeric-tags-test
    'eve.collection-hash-test
    'eve.reference-tag-test
    'eve.big-value-test
    'eve.sorted-coll-test
    'eve.keyring-test
    'eve.value-buffer-test
    'eve.thread-host-test
    'eve.thread-test
    'eve.thread-meta-test
    'eve.thread-fold-test))

;; Suite registry
(def ^:private suite-runners
  {"core"        run-core!
   "array"       run-array!
   "slab"        run-slab!
   "slab-grow"   run-slab-grow!
   "large-scale" run-large-scale!
   "epoch-gc"    run-epoch-gc!
   "obj"         run-obj!
   "batch2"      run-batch2!
   "batch3"      run-batch3!
   "batch4"      run-batch4!
   "int-map"     run-int-map!
   "rb-tree"     run-rb-tree!
   "deftype"     run-deftype!
   "validation"  run-validation!
   "typed-array" run-typed-array!
   "mem"         run-mem!
   "mmap"        run-mmap!
   "mmap-slab"   run-mmap-slab!
   "mmap-atom"     run-mmap-atom!
   "mmap-atom-e2e" run-mmap-atom-e2e!
   "mmap-domain"   run-mmap-domain!
   "conformance"   run-conformance!
   "fuzz"          run-fuzz!
   "fixtures"      run-fixtures!
   "char-key"      run-char-key!
   "em5"           run-em5!
   "hamt-util"     run-hamt-util!
   "lustre"        run-lustre!
   "lustre-bench"  run-lustre-bench!
   "dataset"        run-dataset!
   "array-hosts"    run-array-hosts!
   "tensor"         run-tensor!
   "rrb-vector"     run-rrb-vector!
   "columnar-bench" run-columnar-bench!
   "columnar-mmap"  run-columnar-bench-mmap!
   "columnar-inmem" run-columnar-bench-inmem!
   "columnar-one"   run-columnar-one!
   "perf-audit"     run-perf-audit!
   "perf"           run-perf-suite!
   "perf-growable"  run-perf-suite!
   "repro"          run-repro!
   "slab-leak"      run-slab-leak!
   "merkle-phase5"  run-merkle-phase5!
   "net"            run-net!
   "thread-host"    run-thread-host!
   "thread"         run-thread!
   "thread-meta"    run-thread-meta!
   "thread-domain"  run-thread-domain!
   "thread-fold"    run-thread-fold!
   "thread-fold-group" run-thread-fold-group!
   "all"            run-all!
   ;; Aliases
   "map-test"        run-slab!
   "vec-test"        run-slab!
   "list-test"       run-slab!
   "set-test"        run-slab!
   "deftype-test"    run-core!
   "array-test"      run-array!
   "epoch-gc-test"   run-epoch-gc!})

(def ^:private suite-names
  ["all" "core" "array" "slab" "slab-grow" "large-scale" "epoch-gc" "obj"
   "deftype" "int-map" "rb-tree" "batch2" "batch3" "batch4" "validation"
   "typed-array" "mem" "mmap" "mmap-slab" "mmap-atom" "mmap-atom-e2e"
   "mmap-domain" "conformance" "fuzz" "fixtures" "char-key" "em5" "hamt-util" "lustre" "lustre-bench"
   "dataset" "array-hosts" "tensor" "rrb-vector" "columnar-bench" "perf-audit" "perf" "perf-growable" "slab-leak" "net"
   "thread-host" "thread" "thread-meta" "thread-domain" "thread-fold" "thread-fold-group"])

;; Summary reporter
(defmethod t/report [::t/default :summary] [{:keys [test pass fail error]}]
  (println)
  (println (str "Ran " test " tests containing "
                (+ pass fail error) " assertions."))
  (println (str fail " failures, " error " errors."))
  ;; a worker's start, when the run started one (the thread contract's (driving) :spawn-ms: the last worker's)
  (when-let [ms (try (:spawn-ms (thread-host/driving)) (catch :default _ nil))]
    (when (pos? ms) (println (str "Worker start: " ms " ms (the last worker's, eve.thread-host/driving)"))))
  (exit! (if (pos? (+ fail error)) 1 0)))

;; Main run logic
(defn- run-suite! [suite-name]
  (if-let [run-fn (get suite-runners suite-name)]
    (do
      (println "=== Eve Test Runner ===")
      (println (str "Suite: " suite-name))
      (println)
      (println "Initializing slab allocator...")
      ;; perf-growable: the perf suite over growable slabs (a browser's default), against perf's fixed ones
      (-> (ps/init! :force true :growable? (contains? #{"slab-grow" "perf-growable"} suite-name))
          (.then (fn [_]
                   (println "Slab allocator initialized.")
                   (set! d/*worker-id* 1)
                   (set! a/*global-atom-instance*
                         (a/atom-domain {}))
                   (println)
                   (run-fn)))
          (.catch (fn [e]
                    (println (str "ERROR: " (.-message e)))
                    (when-let [st (.-stack e)] (println st))
                    (exit! 1)))))
    (do
      (println (str "Unknown suite: " suite-name))
      (println (str "Available: " (str/join ", " suite-names)))
      (exit! 1))))

(defn main
  "In a thread eve.thread-host started (a worker running this whole program: the fat kernel), that thread's entry;
   else the suite named on the command line."
  []
  (when-not (thread-host/run-thread!)
    (let [args (get-args)]
      (when (some #{":list" "--list" "--help" "-h"} args)
        (println "Available test suites:")
        (doseq [s suite-names]
          (println (str "  " s)))
        (exit! 0))
      (run-suite! (or (first args) "all")))))
