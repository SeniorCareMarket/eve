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
   [eve.shared-atom :as a]
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
   [eve.typed-array-test :as typed-array-test]
   [eve.mem-test :as mem-test]
   [eve.mem :as mem]
   [eve.mmap-test :as mmap-test]
   [eve.mmap-worker-test :as mmap-worker-test]
   [eve.mmap-slab-test :as mmap-slab-test]
   [eve.mmap-atom-test :as mmap-atom-test]
   [eve.mmap-atom-e2e-test :as mmap-atom-e2e-test]
   [eve.mmap-domain-test :as mmap-domain-test]
   [eve.lustre-test :as lustre-test]))

;; Anti-DCE exports
(goog/exportSymbol "eve.deftype_test" deftype-test)
(goog/exportSymbol "eve.array_test" array-test)
(goog/exportSymbol "eve.map_test" map-test)
(goog/exportSymbol "eve.vec_test" vec-test)
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
(goog/exportSymbol "eve.typed_array_test" typed-array-test)
(goog/exportSymbol "eve.mem_test" mem-test)
(goog/exportSymbol "eve.mmap_test" mmap-test)
(goog/exportSymbol "eve.mmap_worker_test" mmap-worker-test)
(goog/exportSymbol "eve.mmap_slab_test" mmap-slab-test)
(goog/exportSymbol "eve.mmap_atom_test" mmap-atom-test)
(goog/exportSymbol "eve.mmap_atom_e2e_test" mmap-atom-e2e-test)
(goog/exportSymbol "eve.mmap_domain_test" mmap-domain-test)
(goog/exportSymbol "eve.lustre_test" lustre-test)

;; Isolated namespace support
(def ^:private isolated-nss
  #{'eve.map-test
    'eve.vec-test
    'eve.list-test
    'eve.set-test
    'eve.large-scale-test})

(defn- recycle-slab-env! []
  (eve-map/reset-pools!)
  (eve-vec/reset-pools!)
  (eve-list/reset-pools!)
  (eve-set/reset-pools!)
  (eve-alloc/reset-overflow-fns!)
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
    'eve.list-test
    'eve.set-test))

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
    'eve.deftype.rb-tree-test))

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
    (mem/load-native-addon! (js/require path))))

(defn- run-mmap! []
  (load-native-addon!)
  (t/run-tests 'eve.mmap-test
               'eve.mmap-worker-test))

(defn- run-mmap-slab! []
  (load-native-addon!)
  (eve-alloc/init-mmap-slab! 5 "/tmp/eve-p3-cls5.mem")
  (t/run-tests 'eve.mmap-slab-test))

(defn- run-mmap-atom! []
  (load-native-addon!)
  (t/run-tests 'eve.mmap-atom-test))

(defn- run-mmap-atom-e2e! []
  (load-native-addon!)
  (t/run-tests 'eve.mmap-atom-e2e-test))

(defn- run-mmap-domain! []
  (load-native-addon!)
  (t/run-tests 'eve.mmap-domain-test))

(defn- run-lustre! []
  (load-native-addon!)
  (t/run-tests 'eve.lustre-test))

(defn- run-all! []
  (t/run-tests
    'eve.deftype-test
    'eve.array-test
    'eve.map-test
    'eve.vec-test
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
    'eve.typed-array-test))

;; Suite registry
(def ^:private suite-runners
  {"core"        run-core!
   "array"       run-array!
   "slab"        run-slab!
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
   "lustre"        run-lustre!
   "all"           run-all!
   ;; Aliases
   "map-test"        run-slab!
   "vec-test"        run-slab!
   "list-test"       run-slab!
   "set-test"        run-slab!
   "deftype-test"    run-core!
   "array-test"      run-array!
   "epoch-gc-test"   run-epoch-gc!})

(def ^:private suite-names
  ["all" "core" "array" "slab" "large-scale" "epoch-gc" "obj"
   "deftype" "int-map" "rb-tree" "batch2" "batch3" "batch4" "validation"
   "typed-array" "mem" "mmap" "mmap-slab" "mmap-atom" "mmap-atom-e2e"
   "mmap-domain" "lustre"])

;; Summary reporter
(defmethod t/report [::t/default :summary] [{:keys [test pass fail error]}]
  (println)
  (println (str "Ran " test " tests containing "
                (+ pass fail error) " assertions."))
  (println (str fail " failures, " error " errors."))
  (exit! (if (pos? (+ fail error)) 1 0)))

;; Main run logic
(defn- run-suite! [suite-name]
  (if-let [run-fn (get suite-runners suite-name)]
    (do
      (println "=== Eve Test Runner ===")
      (println (str "Suite: " suite-name))
      (println)
      (println "Initializing slab allocator...")
      (-> (eve-alloc/init! :force true)
          (.then (fn [_]
                   (println "Slab allocator initialized.")
                   (set! d/*worker-id* 1)
                   (set! a/*global-atom-instance*
                         (a/atom-domain {}))
                   (println)
                   (run-fn)))
          (.catch (fn [e]
                    (println (str "ERROR: " (.-message e)))
                    (exit! 1)))))
    (do
      (println (str "Unknown suite: " suite-name))
      (println (str "Available: " (str/join ", " suite-names)))
      (exit! 1))))

(defn main []
  (let [args (get-args)]
    (when (some #{":list" "--list" "--help" "-h"} args)
      (println "Available test suites:")
      (doseq [s suite-names]
        (println (str "  " s)))
      (exit! 0))
    (run-suite! (or (first args) "all"))))
