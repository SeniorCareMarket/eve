(ns eve.batch4-validation-test
  "Eve validation tests for batch 4 functionality.
   Tests SAB transfer, SharedAtom API, slab infrastructure."
  (:require
   [cljs.test :refer-macros [deftest testing is]]
   [eve.shared-atom :as a]
   [eve.map :as sm]
   [eve.set :as ss]))

;; Platform detection — filesystem tests are node-only
(def ^:private node?
  (and (exists? js/process) (exists? js/process.versions)))

;; Eve runner dir — resolve once; nil when not running from eve/
(def ^:private eve-runner-dir
  (when node?
    (let [fs (js/require "fs")
          path (js/require "path")
          d (.join path (js/process.cwd) "test/cljs_thread/runner")
          marker (.join path d "eve_xray_main.cljs")]
      (when (.existsSync fs marker) d))))

;;-----------------------------------------------------------------------------
;; Helper: create a fresh atom env
;;-----------------------------------------------------------------------------

(defn fresh-env
  "Create a fresh atom env, resetting pools first."
  []
  (sm/reset-pools!)
  (ss/reset-pools!)
  (set! a/*global-atom-instance*
        (a/atom-domain {}))
  (a/get-env a/*global-atom-instance*))

;;=============================================================================
;; sab-transfer-data — extract SAB refs for cross-thread transfer
;;=============================================================================

(deftest sab-transfer-data-exists-test
  (testing "sab-transfer-data is defined in atom namespace"
    (is (fn? a/sab-transfer-data)
        "sab-transfer-data should be a function")))

(deftest sab-transfer-data-returns-sab-refs-test
  (testing "sab-transfer-data returns map with :sab and :reader-map-sab"
    (let [env (fresh-env)
          transfer (a/sab-transfer-data a/*global-atom-instance*)]
      (is (map? transfer)
          "should return a map")
      (is (contains? transfer :sab)
          "should contain :sab key")
      (is (contains? transfer :reader-map-sab)
          "should contain :reader-map-sab key"))))

(deftest sab-transfer-data-sab-is-shared-array-buffer-test
  (testing "sab-transfer-data :sab is a SharedArrayBuffer"
    (let [env (fresh-env)
          transfer (a/sab-transfer-data a/*global-atom-instance*)
          sab (:sab transfer)]
      (is (some? sab) ":sab should be non-nil")
      (is (instance? js/SharedArrayBuffer sab)
          ":sab should be a SharedArrayBuffer instance")
      (is (= sab (:sab env))
          ":sab should be the same buffer as the atom env's SAB"))))

(deftest sab-transfer-data-round-trip-test
  (testing "SAB refs from sab-transfer-data can reconstruct atom env"
    (let [env (fresh-env)
          transfer (a/sab-transfer-data a/*global-atom-instance*)
          sab (:sab transfer)
          index-view (js/Int32Array. sab)
          data-view (js/Uint8Array. sab)]
      (swap! a/*global-atom-instance* assoc :hello "world")
      (is (some? (aget index-view 0))
          "Reconstructed index-view should be able to read from SAB")
      (is (> (.-byteLength sab) 0)
          "SAB should have non-zero byte length")
      (is (= "world" (get @a/*global-atom-instance* :hello))
          "Original atom should still work after sab-transfer-data"))))

;;=============================================================================
;; Project structure
;;=============================================================================

(deftest project-directory-structure-test
  (when node?
    (testing "Project root has expected structure"
      (let [fs (js/require "fs")
            path (js/require "path")
            project-dir (js/process.cwd)]
        (is (.existsSync fs (.join path project-dir "src"))
            "src/ should exist")
        (is (.existsSync fs (.join path project-dir "test"))
            "test/ should exist")
        (is (.existsSync fs (.join path project-dir "shadow-cljs.edn"))
            "shadow-cljs.edn should exist")
        (is (.existsSync fs (.join path project-dir "deps.edn"))
            "deps.edn should exist")))))

(deftest xray-files-exist-test
  (when eve-runner-dir
    (testing "X-RAY storage model checker files exist"
      (let [fs (js/require "fs")
            path (js/require "path")]
        (is (.existsSync fs (.join path eve-runner-dir "eve_xray_main.cljs"))
            "eve_xray_main.cljs should exist (X-RAY entry point)")
        (let [content (.readFileSync fs (.join path eve-runner-dir "eve_xray_main.cljs") "utf8")]
          (is (re-find #"validate-storage-model!" content)
              "X-RAY should call validate-storage-model! (slab validation)")
          (is (re-find #"atom-domain" content)
              "X-RAY should use a/atom-domain (EVE)"))))))

(deftest bench-framework-exists-test
  (when eve-runner-dir
    (testing "Benchmark framework code exists"
      (let [fs (js/require "fs")
            path (js/require "path")]
        (is (.existsSync fs (.join path eve-runner-dir "eve_bench.cljs"))
            "eve_bench.cljs should exist (bench framework)")
        (is (.existsSync fs (.join path eve-runner-dir "eve_bench_main.cljs"))
            "eve_bench_main.cljs should exist (bench entry point)")
        (let [content (.readFileSync fs (.join path eve-runner-dir "eve_bench.cljs") "utf8")]
          (is (re-find #"cljs\.test" content)
              "Bench framework should use cljs.test reporting")
          (is (re-find #"record!" content)
              "Bench framework should define record! for timing"))))))


;;=============================================================================
;; SharedAtom API tests
;;=============================================================================

(deftest embedded-shared-atom-api-test
  (testing "a/atom creates SharedAtom in slab"
    (fresh-env)
    (let [embedded (a/atom {:x 42})]
      (is (some? embedded)
          "a/atom should return non-nil")
      (is (instance? a/SharedAtom embedded)
          "a/atom should return an SharedAtom instance")
      (is (= 42 (get @embedded :x))
          "SharedAtom should deref to the correct value"))))

(deftest shared-atom-swap-test
  (testing "SharedAtom supports swap! on slab"
    (fresh-env)
    (let [embedded (a/atom {:counter 0})]
      (swap! embedded update :counter inc)
      (is (= 1 (get @embedded :counter))
          "swap! should update SharedAtom value")
      (swap! embedded update :counter inc)
      (is (= 2 (get @embedded :counter))
          "Multiple swap! calls should accumulate"))))

(deftest shared-atom-reset-test
  (testing "SharedAtom supports reset! on slab"
    (fresh-env)
    (let [embedded (a/atom {:old true})]
      (reset! embedded {:new true})
      (is (= true (get @embedded :new))
          "reset! should replace the value")
      (is (nil? (get @embedded :old))
          "Old key should be gone after reset!"))))

;;=============================================================================
;; SharedAtom print/read
;;=============================================================================

(deftest shared-atom-print-read-roundtrip-test
  (testing "SharedAtom prints as #eve/shared-atom tagged literal"
    (fresh-env)
    (let [sa (a/atom {:x 1})
          printed (pr-str sa)]
      (is (re-find #"#eve/shared-atom" printed)
          "pr-str should emit #eve/shared-atom tag")
      (is (re-find #":id" printed)
          "pr-str should include :id field")
      (is (re-find #":idx" printed)
          "pr-str should include :idx field"))))

;;=============================================================================
;; Multiple SharedAtoms coexistence
;;=============================================================================

(deftest multiple-shared-atoms-in-slab-test
  (testing "Multiple SharedAtoms can coexist in slab"
    (fresh-env)
    (let [a1 (a/atom {:name "alice" :age 30})
          a2 (a/atom {:name "bob" :age 25})
          a3 (a/atom {:kind "config" :level 5})]
      (is (= "alice" (get @a1 :name)))
      (is (= "bob" (get @a2 :name)))
      (is (= 5 (get @a3 :level)))
      (swap! a2 assoc :age 26)
      (is (= 30 (get @a1 :age)) "a1 should be unchanged")
      (is (= 26 (get @a2 :age)) "a2 should be updated")
      (is (= 5 (get @a3 :level)) "a3 should be unchanged"))))
