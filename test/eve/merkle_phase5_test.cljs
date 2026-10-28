(ns eve.merkle-phase5-test
  "merkle-phase5 acceptance probes (plan §8.2, CAS/watch rows).

   The watch/CAS change decision in eve.shared-atom is now
   identity -> root digest -> structural `=` (the digest step is a
   Phase 3a seam; today it falls through to `=`), and watch
   notification is delivered on EVERY successful swap/reset
   (the decision on fork Q5 — Clojure semantics), no longer
   gated by the change decision.

   Observable bars:
   - equal-but-rebuilt swap/reset: root descriptor UNCHANGED (the
     decision answered without installing a new root), watch count
     still increments by exactly 1 per call;
   - changed swap/reset: root descriptor changes, watch increments;
   - 200 equal-but-rebuilt swaps: slab census unchanged (no leak).

   Run via: node target/eve-test/all.js merkle-phase5"
  (:require [cljs.test :refer [deftest testing is]]
            [eve.atom :as atom]
            [eve.data :as d]
            [eve.deftype-proto.alloc :as alloc]
            [eve.util :as u]))

(def ^:private test-base (str "target/merkle-phase5-probe-" (js/Date.now)))

(defn- shared-root-desc-idx
  "The descriptor index of a SharedAtom's installed root value block,
   read from its header descriptor in the parent domain's index view."
  [a]
  (let [env (.-s-atom-env (.-parent-atom-domain a))
        index-view (:index-view env)
        field (u/get-block-descriptor-field-int32-offset index-view (.-header-descriptor-idx a) d/BD_COL_VALUE_DATA_DESC_IDX)]
    (js/Atomics.load index-view field)))

(defn- domain-root-desc-idx
  "The descriptor index of an AtomDomain's installed root block."
  [dom]
  (let [env (.-s-atom-env dom)
        index-view (:index-view env)]
    (js/Atomics.load index-view (/ d/OFFSET_ATOM_ROOT_DATA_DESC_IDX d/SIZE_OF_INT32))))

(defn- total-used-blocks []
  (loop [i 0 total 0]
    (if (>= i d/NUM_SLAB_CLASSES)
      total
      (recur (inc i) (+ total (:used-count (alloc/slab-stats i)))))))

(deftest shared-atom-equal-rebuilt-swap-notifies-without-new-root
  (testing "SharedAtom: equal-but-rebuilt swap answered by the decision; watch still fires"
    (let [b (str test-base "-sa-eq")
          dom (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b} {:count 1 :name "x"})
          fires (cljs.core/atom [])
          _ (add-watch a :w (fn [_k _ref old new] (swap! fires conj [old new])))
          root0 (shared-root-desc-idx a)]
      ;; equal-but-rebuilt swap: rebuild the map, change nothing
      (swap! a (fn [m] (into {} m)))
      (is (= {:count 1 :name "x"} @a) "value intact")
      (is (= root0 (shared-root-desc-idx a)) "root desc unchanged — no new root installed")
      (is (= 1 (count @fires)) "watch fired on equal-but-rebuilt swap (Q5)")
      (is (= {:count 1 :name "x"} (first (first @fires))) "watch old value")
      (is (= {:count 1 :name "x"} (second (first @fires))) "watch new value")
      ;; changed swap: root moves, watch fires
      (swap! a assoc :count 2)
      (is (= 2 (:count @a)))
      (is (not= root0 (shared-root-desc-idx a)) "changed swap installs a new root")
      (is (= 2 (count @fires)) "watch fired on changed swap")
      ;; equal-but-rebuilt reset: root stays, watch fires
      (let [root1 (shared-root-desc-idx a)]
        (reset! a (into {} @a))
        (is (= root1 (shared-root-desc-idx a)) "reset to equal value installs no new root")
        (is (= 3 (count @fires)) "watch fired on equal reset (Q5)"))
      ;; changed reset
      (reset! a {:count 9})
      (is (= 9 (:count @a)))
      (is (= 4 (count @fires)) "watch fired on changed reset")
      (atom/close-atom-domain! dom))))

(deftest shared-atom-equal-swaps-do-not-grow-slab
  (testing "SharedAtom: 200 equal-but-rebuilt swaps allocate no slab blocks"
    (let [b (str test-base "-sa-leak")
          dom (atom/persistent-atom-domain b)
          a (atom/persistent-atom {:id :eve/main :persistent b}
                                  (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 50))))
          root0 (shared-root-desc-idx a)
          _ (swap! a (fn [m] (into {} m))) ;; warm-up: settle pools/caches
          used0 (total-used-blocks)]
      (dotimes [_ 200] (swap! a (fn [m] (into {} m))))
      (is (= 0 (:k0 @a)) "value still correct after 200 equal swaps")
      (is (= root0 (shared-root-desc-idx a)) "root desc stable across 200 equal swaps")
      (is (= used0 (total-used-blocks)) "slab census unchanged — no leak")
      (atom/close-atom-domain! dom))))

(deftest atom-domain-equal-rebuilt-swap-notifies-without-new-root
  (testing "AtomDomain: equal-but-rebuilt swap answered by the decision; watch still fires"
    (let [dom (atom/atom-domain {})
          _ (swap! dom assoc :count 1)
          fires (cljs.core/atom [])
          _ (add-watch dom :w (fn [_k _ref old new] (swap! fires conj [old new])))
          root0 (domain-root-desc-idx dom)]
      (swap! dom (fn [m] (into {} m)))
      (is (= {:count 1} @dom) "value intact")
      (is (= root0 (domain-root-desc-idx dom)) "root desc unchanged — no new root installed")
      (is (= 1 (count @fires)) "watch fired on equal-but-rebuilt swap (Q5)")
      (swap! dom assoc :count 2)
      (is (= 2 (:count @dom)))
      (is (not= root0 (domain-root-desc-idx dom)) "changed swap installs a new root")
      (is (= 2 (count @fires)) "watch fired on changed swap")
      (let [root1 (domain-root-desc-idx dom)]
        (reset! dom (into {} @dom))
        (is (= root1 (domain-root-desc-idx dom)) "reset to equal value installs no new root")
        (is (= 3 (count @fires)) "watch fired on equal reset (Q5)"))
      (reset! dom {:count 3})
      (is (= 3 (:count @dom)))
      (is (= 4 (count @fires)) "watch fired on changed reset"))))
