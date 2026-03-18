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
         ;; Wait again and do another swap to ensure flush ran
         (js/setTimeout
           (fn []
             (swap! a f)
             (resolve))
           60))
       60)))))

;;-----------------------------------------------------------------------------
;; Test 1: Map-to-map swaps — the 'happy path' that should work
;;         collect-retire-diff-offsets walks the HAMT tree diff.
;;-----------------------------------------------------------------------------

(deftest test-map-swap-slab-stability
  (async done
    (let [b (str test-base "-map-stable")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:count 0})]
      ;; Warm up: establish steady state
      (dotimes [_ 20]
        (swap! a update :count inc))
      ;; Wait for retires to flush
      (-> (wait-and-flush a)
          (.then (fn [_]
            (let [baseline (total-used-blocks)]
              ;; Do 200 more swaps
              (dotimes [_ 200]
                (swap! a update :count inc))
              ;; Wait for flush
              (-> (wait-and-flush a)
                  (.then (fn [_]
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
;; Test 2: Map swap with growing keys — more HAMT nodes involved
;;-----------------------------------------------------------------------------

(deftest test-map-growing-keys-leak
  (async done
    (let [b (str test-base "-map-grow")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {})]
      ;; Seed with 20 keys
      (dotimes [i 20]
        (swap! a assoc (keyword (str "k" i)) i))
      (-> (wait-and-flush a)
          (.then (fn [_]
            (let [baseline (total-used-blocks)]
              ;; 200 assoc cycles rotating through 10 keys
              (dotimes [i 200]
                (swap! a assoc (keyword (str "tmp" (mod i 10))) i))
              (-> (wait-and-flush a)
                  (.then (fn [_]
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
;;         500 swaps in a tight loop, then wait for flush.
;;         After flush, used blocks should be close to baseline.
;;-----------------------------------------------------------------------------

(deftest test-rapid-swap-flush-convergence
  (async done
    (let [b (str test-base "-rapid")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:n 0})]
      ;; Warmup
      (dotimes [_ 10]
        (swap! a update :n inc))
      (-> (wait-and-flush a)
          (.then (fn [_]
            (let [baseline (total-used-blocks)]
              ;; Burst: 500 swaps as fast as possible
              (dotimes [_ 500]
                (swap! a update :n inc))
              (let [after-burst (total-used-blocks)]
                (println (str "  Rapid: baseline=" baseline
                              " after-burst=" after-burst
                              " burst-growth=" (- after-burst baseline)))
                ;; Now wait for multiple flush cycles
                (-> (wait-and-flush a)
                    (.then (fn [_]
                      (-> (wait-and-flush a)
                          (.then (fn [_]
                            (let [after-drain (total-used-blocks)
                                  residual (- after-drain baseline)]
                              (println (str "  Rapid: after-drain=" after-drain
                                            " residual=" residual))
                              (is (< residual 30)
                                  (str "After 500 rapid swaps + flush, "
                                       residual " blocks still unreleased"))
                              (atom/close-atom-domain! d)
                              (done))))))))))))))));

;;-----------------------------------------------------------------------------
;; Test 4: Large map OOM stress
;;         With default small slab capacities, swapping a 20-key map
;;         500 times will exhaust slab space if trees aren't reclaimed.
;;-----------------------------------------------------------------------------

(deftest test-map-swap-oom-stress
  (async done
    (let [b (str test-base "-oom")
          d (atom/persistent-atom-domain b)
          init-map (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 20)))
          a (atom/persistent-atom {:id :eve/main :persistent b} init-map)]
      ;; If old HAMT trees are not freed, this throws "all slab classes full"
      (try
        (dotimes [i 500]
          (swap! a assoc (keyword (str "k" (mod i 20))) (+ i 1000)))
        (-> (wait-and-flush a)
            (.then (fn [_]
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
;;         Sample used blocks at intervals. With proper GC, usage should
;;         stabilize. With a leak, it grows without bound.
;;-----------------------------------------------------------------------------

(deftest test-monotonic-growth-detection
  (async done
    (let [b (str test-base "-mono")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:v 0})
          samples (atom [])
          run-batch (fn run-batch [batch-idx]
                      (if (>= batch-idx 8)
                        ;; All batches done — analyze
                        (-> (wait-and-flush a)
                            (.then (fn [_]
                              (swap! samples conj (total-used-blocks))
                              (let [s @samples]
                                (println (str "  Monotonic samples: " (pr-str s)))
                                (when (> (count s) 3)
                                  (let [;; Check last 4 samples for plateau
                                        tail (take-last 4 s)
                                        max-tail (apply max tail)
                                        min-tail (apply min tail)
                                        spread (- max-tail min-tail)]
                                    (is (< spread 50)
                                        (str "Slab usage still growing in last 4 samples, "
                                             "spread=" spread " samples=" (pr-str tail)
                                             ". Indicates memory leak."))))
                                (atom/close-atom-domain! d)
                                (done)))))
                        ;; Run a batch of 50 swaps
                        (do
                          (dotimes [_ 50]
                            (swap! a update :v inc))
                          (-> (wait-and-flush a)
                              (.then (fn [_]
                                (swap! samples conj (total-used-blocks))
                                (run-batch (inc batch-idx))))))))]
      ;; Warmup
      (dotimes [_ 10]
        (swap! a update :v inc))
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
      (dotimes [_ 10]
        (swap! a inc))
      (-> (wait-and-flush a inc)
          (.then (fn [_]
            (let [baseline (total-used-blocks)]
              (dotimes [_ 200]
                (swap! a inc))
              (-> (wait-and-flush a inc)
                  (.then (fn [_]
                    (let [after (total-used-blocks)
                          growth (- after baseline)]
                      (println (str "  Scalar swaps: baseline=" baseline
                                    " after=" after " growth=" growth))
                      (is (< growth 10)
                          (str "Scalar swaps leaked " growth " blocks"))
                      (atom/close-atom-domain! d)
                      (done))))))))))))

;;=============================================================================
;; REGRESSION GUARDS — Tests that catch known leak patterns.
;; These should FAIL if the bug is present, PASS after the fix.
;;=============================================================================

;;-----------------------------------------------------------------------------
;; Test 7: Vector-in-atom retirement
;;         Bug: cljs-mmap-swap! only calls collect-retire-diff-offsets for
;;         EveHashMap. For EveVector, only [old-ptr] (header) is retired.
;;         The trie nodes underneath are leaked.
;;-----------------------------------------------------------------------------

(deftest test-vector-atom-swap-leak
  (async done
    (let [b (str test-base "-vec-leak")
          d (atom/persistent-atom-domain b)
          ;; Start with a vector of 32 elements (enough to create trie nodes)
          init-vec (vec (range 32))
          a (atom/persistent-atom {:id :eve/main :persistent b} init-vec)]
      ;; Warmup
      (dotimes [i 10]
        (swap! a assoc (mod i 32) (+ i 100)))
      (-> (wait-and-flush a #(assoc % 0 (inc (nth % 0))))
          (.then (fn [_]
            (let [baseline (total-used-blocks)]
              ;; 100 swaps replacing elements in the vector
              ;; Each swap creates new trie path nodes; old ones should be freed.
              (dotimes [i 100]
                (swap! a assoc (mod i 32) (+ i 1000)))
              (-> (wait-and-flush a #(assoc % 0 (inc (nth % 0))))
                  (.then (fn [_]
                    (let [after (total-used-blocks)
                          growth (- after baseline)]
                      (println (str "  Vec swaps: baseline=" baseline
                                    " after=" after " growth=" growth
                                    " " (all-stats-str)))
                      (is (< growth 30)
                          (str "Vector-in-atom swaps leaked " growth
                               " slab blocks over 100 swaps. "
                               "Non-map retirement is broken."))
                      (atom/close-atom-domain! d)
                      (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 8: Set-in-atom retirement
;;         Same bug as Test 7 but for EveHashSet.
;;-----------------------------------------------------------------------------

(deftest test-set-atom-swap-leak
  (async done
    (let [b (str test-base "-set-leak")
          d (atom/persistent-atom-domain b)
          init-set (set (range 32))
          a (atom/persistent-atom {:id :eve/main :persistent b} init-set)]
      ;; Warmup
      (dotimes [i 10]
        (swap! a #(-> % (disj (mod i 32)) (conj (+ i 100)))))
      (-> (wait-and-flush a #(conj % -1))
          (.then (fn [_]
            (let [baseline (total-used-blocks)]
              ;; 100 swaps modifying the set
              (dotimes [i 100]
                (swap! a #(-> % (disj (mod i 32)) (conj (+ i 1000)))))
              (-> (wait-and-flush a #(conj % -2))
                  (.then (fn [_]
                    (let [after (total-used-blocks)
                          growth (- after baseline)]
                      (println (str "  Set swaps: baseline=" baseline
                                    " after=" after " growth=" growth
                                    " " (all-stats-str)))
                      (is (< growth 30)
                          (str "Set-in-atom swaps leaked " growth
                               " slab blocks over 100 swaps. "
                               "Non-map retirement is broken."))
                      (atom/close-atom-domain! d)
                      (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 9: Type transition leak
;;         Swapping from map to vec or vice versa. The entire old tree
;;         should be freed, not just the header.
;;-----------------------------------------------------------------------------

(deftest test-type-transition-leak
  (async done
    (let [b (str test-base "-type-trans")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:init true})]
      ;; Warmup
      (dotimes [_ 5]
        (swap! a update :init not))
      (-> (wait-and-flush a)
          (.then (fn [_]
            (let [baseline (total-used-blocks)]
              ;; Alternate between map and vec 50 times
              (dotimes [i 50]
                (if (even? i)
                  (reset! a (vec (range 16)))
                  (reset! a (into {} (map (fn [j] [(keyword (str "k" j)) j]) (range 16))))))
              (-> (wait-and-flush a)
                  (.then (fn [_]
                    (let [after (total-used-blocks)
                          growth (- after baseline)]
                      (println (str "  Type trans: baseline=" baseline
                                    " after=" after " growth=" growth
                                    " " (all-stats-str)))
                      (is (< growth 50)
                          (str "Type transitions leaked " growth
                               " slab blocks over 50 transitions. "
                               "Old trees not fully freed on type change."))
                      (atom/close-atom-domain! d)
                      (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 10: Nested Eve collections as map values
;;          When a map value is itself an Eve collection (via SAB pointer tag),
;;          replacing that value should free the nested collection's tree.
;;          Currently, collect-retire-diff-offsets only walks HAMT structural
;;          nodes — it does NOT follow SAB pointer tags in data entries.
;;-----------------------------------------------------------------------------

(deftest test-nested-collection-value-leak
  (async done
    (let [b (str test-base "-nested")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {})]
      ;; Store maps as values inside the outer map
      (dotimes [i 10]
        (swap! a assoc (keyword (str "k" i))
               (into {} (map (fn [j] [(keyword (str "v" j)) j]) (range 10)))))
      (-> (wait-and-flush a)
          (.then (fn [_]
            (let [baseline (total-used-blocks)]
              ;; Replace each nested map 50 times with a new one
              (dotimes [round 50]
                (swap! a assoc :k0
                       (into {} (map (fn [j] [(keyword (str "r" round "v" j)) j]) (range 10)))))
              (-> (wait-and-flush a)
                  (.then (fn [_]
                    (let [after (total-used-blocks)
                          growth (- after baseline)]
                      (println (str "  Nested: baseline=" baseline
                                    " after=" after " growth=" growth
                                    " " (all-stats-str)))
                      (is (< growth 50)
                          (str "Nested collection values leaked " growth
                               " slab blocks over 50 replacements. "
                               "Inner Eve trees not freed when outer map value replaced."))
                      (atom/close-atom-domain! d)
                      (done))))))))))))

;;-----------------------------------------------------------------------------
;; Test 11: Sustained load — many swaps over time should converge
;;          This is the ultimate "canary" test. If any leak exists,
;;          this test will eventually OOM or show unbounded growth.
;;-----------------------------------------------------------------------------

(deftest test-sustained-map-load-convergence
  (async done
    (let [b (str test-base "-sustained")
          d (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b}
                                   (into {} (map (fn [i] [(keyword (str "k" i)) 0]) (range 50))))
          run-phase (fn run-phase [phase-idx]
                      (if (>= phase-idx 10)
                        ;; Done — check final state
                        (-> (wait-and-flush a)
                            (.then (fn [_]
                              (let [final-used (total-used-blocks)]
                                (println (str "  Sustained: final-used=" final-used
                                              " " (all-stats-str)))
                                (is (< final-used 200)
                                    (str "After 1000 swaps, " final-used
                                         " blocks still in use. Expected < 200."))
                                (atom/close-atom-domain! d)
                                (done)))))
                        ;; Run 100 swaps, then flush
                        (do
                          (dotimes [i 100]
                            (swap! a assoc (keyword (str "k" (mod i 50)))
                                   (+ (* phase-idx 100) i)))
                          (-> (wait-and-flush a)
                              (.then (fn [_] (run-phase (inc phase-idx))))))))]
      (run-phase 0))))
