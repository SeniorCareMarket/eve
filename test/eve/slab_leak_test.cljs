(ns eve.slab-leak-test
  "Memory leak reproduction tests for mmap atom slab retirement.
   These tests demonstrate that old slab blocks are not properly freed
   after swap! operations, causing monotonic slab usage growth.

   Run via: node target/eve-test/all.js slab-leak"
  (:require [cljs.test :refer [deftest testing is async]]
            [eve.atom :as atom]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.map :as eve-map]
            [eve.vec :as eve-vec]
            [eve.set :as eve-set]))

(def ^:private test-base (str "/tmp/eve-leak-test-" (js/Date.now)))

(defn- total-used-blocks
  "Sum of used-count across all 6 bitmap slab classes."
  []
  (loop [i 0 total 0]
    (if (>= i d/NUM_SLAB_CLASSES)
      total
      (let [stats (alloc/slab-stats i)]
        (recur (inc i) (+ total (:used-count stats)))))))

(defn- all-stats-str []
  (let [stats (alloc/all-slab-stats)]
    (str "{"
         (apply str
                (interpose ", "
                           (for [[cls {:keys [block-size total-blocks free-count used-count]}] (sort stats)]
                             (str cls ":" used-count "/" total-blocks))))
         "}")))

(defn- wait-and-flush
  "Wait 60ms (past FLUSH_INTERVAL_MS=50), then do a swap to trigger flush.
   Returns a Promise that resolves after flush. f is the swap fn to apply."
  ([a] (wait-and-flush a #(if (map? %) (update % :_flush (fnil inc 0)) (identity %))))
  ([a f]
   (js/Promise. (fn [resolve _]
                  (js/setTimeout
                   (fn []
                     (swap! a f)
                     (js/setTimeout
                      (fn []
                        (swap! a f)
                        (resolve))
                      60))
                   60)))))

;;-----------------------------------------------------------------------------
;; Test 1: Map-to-map swaps — the 'happy path' that should work
;;-----------------------------------------------------------------------------

(deftest test-map-swap-slab-stability
  (async done
         (let [b (str test-base "-map-stable")
               d (atom/persistent-atom-domain b)
               a (atom/persistent-atom {:id :eve/main :persistent b} {:count 0})]
           (dotimes [_ 20] (swap! a update :count inc))
           (-> (wait-and-flush a)
               (.then
                (fn [_]
                  (let [baseline (total-used-blocks)]
                    (dotimes [_ 200] (swap! a update :count inc))
                    (-> (wait-and-flush a)
                        (.then
                         (fn [_]
                           (let [after (total-used-blocks)
                                 growth (- after baseline)]
                             (println (str "  Map swaps: baseline=" baseline
                                           " after=" after " growth=" growth
                                           " " (all-stats-str)))
                             (is (< growth 30)
                                 (str "Map-to-map swaps leaked " growth
                                      " slab blocks over 200 swaps"))
                             (atom/close-atom-domain! d)
                             (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 2: Map swap with growing keys
;;-----------------------------------------------------------------------------

(deftest test-map-growing-keys-leak
  (async done
         (let [b (str test-base "-map-grow")
               d (atom/persistent-atom-domain b)
               a (atom/persistent-atom {:id :eve/main :persistent b} {})]
           (dotimes [i 20] (swap! a assoc (keyword (str "k" i)) i))
           (-> (wait-and-flush a)
               (.then
                (fn [_]
                  (let [baseline (total-used-blocks)]
                    (dotimes [i 200]
                      (swap! a assoc (keyword (str "tmp" (mod i 10))) i))
                    (-> (wait-and-flush a)
                        (.then
                         (fn [_]
                           (let [after (total-used-blocks)
                                 growth (- after baseline)]
                             (println (str "  Map grow: baseline=" baseline
                                           " after=" after " growth=" growth
                                           " " (all-stats-str)))
                             (is (< growth 40)
                                 (str "Map growing-keys leaked " growth
                                      " slab blocks over 200 swaps"))
                             (atom/close-atom-domain! d)
                             (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 3: Rapid swaps + flush convergence
;;-----------------------------------------------------------------------------

(deftest test-rapid-swap-flush-convergence
  (async done
         (let [b (str test-base "-rapid")
               d (atom/persistent-atom-domain b)
               a (atom/persistent-atom {:id :eve/main :persistent b} {:n 0})]
           (dotimes [_ 10] (swap! a update :n inc))
           (-> (wait-and-flush a)
               (.then
                (fn [_]
                  (let [baseline (total-used-blocks)]
                    (dotimes [_ 500] (swap! a update :n inc))
                    (let [after-burst (total-used-blocks)]
                      (println (str "  Rapid: baseline=" baseline
                                    " after-burst=" after-burst
                                    " burst-growth=" (- after-burst baseline)))
                      (-> (wait-and-flush a)
                          (.then
                           (fn [_]
                             (-> (wait-and-flush a)
                                 (.then
                                  (fn [_]
                                    (let [after-drain (total-used-blocks)
                                          residual (- after-drain baseline)]
                                      (println (str "  Rapid: after-drain=" after-drain
                                                    " residual=" residual))
                                      (is (< residual 30)
                                          (str "After 500 rapid swaps + flush, "
                                               residual " blocks still unreleased"))
                                      (atom/close-atom-domain! d)
                                      (done))))))))))))))))

;;-----------------------------------------------------------------------------
;; Test 4: Large map OOM stress
;;-----------------------------------------------------------------------------

(deftest test-map-swap-oom-stress
  (async done
         (let [b (str test-base "-oom")
               d (atom/persistent-atom-domain b)
               init-map (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 20)))
               a (atom/persistent-atom {:id :eve/main :persistent b} init-map)]
           (try
             (dotimes [i 500]
               (swap! a assoc (keyword (str "k" (mod i 20))) (+ i 1000)))
             (-> (wait-and-flush a)
                 (.then
                  (fn [_]
                    (is (= (+ 499 1000) ((keyword "k19") @a))
                        "Should complete 500 swaps without OOM")
                    (println (str "  OOM stress: " (all-stats-str)))
                    (atom/close-atom-domain! d)
                    (done))))
             (catch :default e
               (println (str "  OOM stress FAILED: " (.-message e)))
               (is false (str "OOM after map swaps: " (.-message e)))
               (atom/close-atom-domain! d)
               (done))))))

;;-----------------------------------------------------------------------------
;; Test 5: Monotonic growth detection
;;-----------------------------------------------------------------------------

(deftest test-monotonic-growth-detection
  (async done
         (let [b (str test-base "-mono")
               d (atom/persistent-atom-domain b)
               a (atom/persistent-atom {:id :eve/main :persistent b} {:v 0})
               samples (atom [])
               run-batch
               (fn run-batch [batch-idx]
                 (if (>= batch-idx 8)
                   (-> (wait-and-flush a)
                       (.then
                        (fn [_]
                          (swap! samples conj (total-used-blocks))
                          (let [s @samples]
                            (println (str "  Monotonic samples: " (pr-str s)))
                            (when (> (count s) 3)
                              (let [tail (take-last 4 s)
                                    spread (- (apply max tail) (apply min tail))]
                                (is (< spread 50)
                                    (str "Slab usage still growing, spread=" spread
                                         " samples=" (pr-str tail)))))
                            (atom/close-atom-domain! d)
                            (done)))))
                   (do
                     (dotimes [_ 50] (swap! a update :v inc))
                     (-> (wait-and-flush a)
                         (.then
                          (fn [_]
                            (swap! samples conj (total-used-blocks))
                            (run-batch (inc batch-idx))))))))]
           (dotimes [_ 10] (swap! a update :v inc))
           (-> (wait-and-flush a)
               (.then (fn [_] (run-batch 0)))))))

;;-----------------------------------------------------------------------------
;; Test 6: Scalar swaps — minimal allocation, should not leak
;;-----------------------------------------------------------------------------

(deftest test-scalar-swap-no-leak
  (async done
         (let [b (str test-base "-scalar")
               d (atom/persistent-atom-domain b)
               a (atom/persistent-atom {:id :eve/main :persistent b} 0)]
           (dotimes [_ 10] (swap! a inc))
           (-> (wait-and-flush a inc)
               (.then
                (fn [_]
                  (let [baseline (total-used-blocks)]
                    (dotimes [_ 200] (swap! a inc))
                    (-> (wait-and-flush a inc)
                        (.then
                         (fn [_]
                           (let [after (total-used-blocks)
                                 growth (- after baseline)]
                             (println (str "  Scalar swaps: baseline=" baseline
                                           " after=" after " growth=" growth))
                             (is (< growth 10)
                                 (str "Scalar swaps leaked " growth " blocks"))
                             (atom/close-atom-domain! d)
                             (done))))))))))))

;;=============================================================================
;; REGRESSION GUARDS
;;=============================================================================

;;-----------------------------------------------------------------------------
;; Test 7: Vector-in-atom retirement
;;-----------------------------------------------------------------------------

(deftest test-vector-atom-swap-leak
  (async done
         (let [b (str test-base "-vec-leak")
               d (atom/persistent-atom-domain b)
               init-vec (vec (range 32))
               a (atom/persistent-atom {:id :eve/main :persistent b} init-vec)]
           (dotimes [i 10] (swap! a assoc (mod i 32) (+ i 100)))
           (-> (wait-and-flush a #(assoc % 0 (inc (nth % 0))))
               (.then
                (fn [_]
                  (let [baseline (total-used-blocks)]
                    (dotimes [i 100] (swap! a assoc (mod i 32) (+ i 1000)))
                    (-> (wait-and-flush a #(assoc % 0 (inc (nth % 0))))
                        (.then
                         (fn [_]
                           (let [after (total-used-blocks)
                                 growth (- after baseline)]
                             (println (str "  Vec swaps: baseline=" baseline
                                           " after=" after " growth=" growth
                                           " " (all-stats-str)))
                             (is (< growth 30)
                                 (str "Vector-in-atom swaps leaked " growth
                                      " slab blocks over 100 swaps"))
                             (atom/close-atom-domain! d)
                             (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 8: Set-in-atom retirement
;;-----------------------------------------------------------------------------

(deftest test-set-atom-swap-leak
  (async done
         (let [b (str test-base "-set-leak")
               d (atom/persistent-atom-domain b)
               init-set (set (range 32))
               a (atom/persistent-atom {:id :eve/main :persistent b} init-set)]
           (dotimes [i 10]
             (if (even? i) (swap! a conj 9999) (swap! a disj 9999)))
           (-> (wait-and-flush a #(conj % -1))
               (.then
                (fn [_]
                  (let [baseline (total-used-blocks)]
                    (dotimes [i 100]
                      (if (even? i) (swap! a conj 8888) (swap! a disj 8888)))
                    (-> (wait-and-flush a #(disj % 8888))
                        (.then
                         (fn [_]
                           (let [after (total-used-blocks)
                                 growth (- after baseline)]
                             (println (str "  Set swaps: baseline=" baseline
                                           " after=" after " growth=" growth
                                           " " (all-stats-str)))
                             (is (< growth 30)
                                 (str "Set-in-atom swaps leaked " growth
                                      " slab blocks over 100 swaps"))
                             (atom/close-atom-domain! d)
                             (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 9: Type transition retirement
;;         Verify collection->scalar frees old tree, scalar->collection works.
;;         Uses scalars as targets to avoid builder-intermediate overhead.
;;-----------------------------------------------------------------------------

(deftest test-type-transition-leak
  (async done
         (let [b (str test-base "-type-trans")
               d (atom/persistent-atom-domain b)
               a (atom/persistent-atom {:id :eve/main :persistent b} {:init true})]
      ;; Build a 16-key map as steady-state via incremental assoc
           (dotimes [i 16] (swap! a assoc (keyword (str "k" i)) i))
           (-> (wait-and-flush a)
               (.then
                (fn [_]
                  (let [map-baseline (total-used-blocks)]
                ;; Transition to scalar — should free entire old map tree
                    (reset! a 42)
                    (-> (wait-and-flush a inc)
                        (.then
                         (fn [_]
                           (let [after-scalar (total-used-blocks)
                                 freed (- map-baseline after-scalar)]
                             (println (str "  Type trans map->scalar: map-baseline="
                                           map-baseline " after-scalar=" after-scalar
                                           " freed=" freed))
                             (is (> freed 0)
                                 "Map->scalar freed 0 blocks, old tree not retired")
                          ;; Build a vec incrementally (avoid builder intermediates)
                             (reset! a [])
                             (dotimes [i 32]
                               (swap! a conj i))
                             (-> (wait-and-flush a #(conj % -1))
                                 (.then
                                  (fn [_]
                                    (let [vec-baseline (total-used-blocks)]
                                    ;; Transition vec -> scalar
                                      (reset! a 99)
                                      (-> (wait-and-flush a inc)
                                          (.then
                                           (fn [_]
                                             (let [after2 (total-used-blocks)
                                                   vec-freed (- vec-baseline after2)]
                                               (println (str "  Type trans vec->scalar: vec-baseline="
                                                             vec-baseline " after=" after2
                                                             " freed=" vec-freed))
                                               (is (> vec-freed 0)
                                                   "Vec->scalar freed 0 blocks, old tree not retired")
                                               (atom/close-atom-domain! d)
                                               (done))))))))))))))))))))

;;-----------------------------------------------------------------------------
;; Test 10: Nested Eve collections as map values
;;          (Expected to fail until recursive freeing is implemented)
;;-----------------------------------------------------------------------------

(deftest test-nested-collection-value-leak
  (async done
         (let [b (str test-base "-nested")
               d (atom/persistent-atom-domain b)
               a (atom/persistent-atom {:id :eve/main :persistent b} {})]
        ;; Build map with nested Eve maps as values via incremental assoc
        ;; Each assoc with a CLJS map value triggers direct-map-encoder → EveHashMap
           (dotimes [i 10]
             (swap! a assoc (keyword (str "k" i)) {:val i}))
           (-> (wait-and-flush a)
               (.then
                (fn [_]
                  (let [with-nested (total-used-blocks)]
                 ;; Transition to scalar — should free parent tree AND all nested maps
                    (reset! a 0)
                    (-> (wait-and-flush a inc)
                        (.then
                         (fn [_]
                           (let [after-scalar (total-used-blocks)
                                 freed (- with-nested after-scalar)]
                             (println (str "  Nested: with-nested=" with-nested
                                           " after-scalar=" after-scalar
                                           " freed=" freed
                                           " " (all-stats-str)))
                          ;; Should free both parent map nodes AND all 10 nested EveHashMap trees
                          ;; Each nested 1-key map = ~2 blocks (header + node), parent = ~3 nodes + header
                          ;; Expect at least 15 blocks freed
                             (is (> freed 15)
                                 (str "Nested collection values: only freed " freed
                                      " blocks (expected >15 for parent + 10 nested maps)"))
                             (atom/close-atom-domain! d)
                             (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 11: Sustained load — many swaps over time should converge
;;-----------------------------------------------------------------------------

(deftest test-sustained-map-load-convergence
  (async done
         (let [b (str test-base "-sustained")
               d (atom/persistent-atom-domain b)
               a (atom/persistent-atom {:id :eve/main :persistent b}
                                       (into {} (map (fn [i] [(keyword (str "k" i)) 0]) (range 50))))
               run-phase
               (fn run-phase [phase-idx]
                 (if (>= phase-idx 10)
                   (-> (wait-and-flush a)
                       (.then
                        (fn [_]
                          (let [final-used (total-used-blocks)]
                            (println (str "  Sustained: final-used=" final-used
                                          " " (all-stats-str)))
                            (is (< final-used 200)
                                (str "After 1000 swaps, " final-used
                                     " blocks still in use. Expected < 200."))
                            (atom/close-atom-domain! d)
                            (done)))))
                   (do
                     (dotimes [i 100]
                       (swap! a assoc (keyword (str "k" (mod i 50)))
                              (+ (* phase-idx 100) i)))
                     (-> (wait-and-flush a)
                         (.then (fn [_] (run-phase (inc phase-idx))))))))]
           (run-phase 0))))
