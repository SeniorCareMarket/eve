(ns eve.user-ns-root-test
  "DS11 regression: an eve-deftype root from a USER namespace (its class
   name carries no eve. prefix — the DataScript port's datascript.db/DB
   is the field instance) must publish DIRECTLY in a persistent atom.
   The tier-repair classification screen published such roots through a
   scalar block instead: the tally never counted the tree beneath the
   root (nothing held, nothing freed, seal/reach blind at one block),
   and any counted co-holder could recycle shared blocks under the live
   tree — the accounting void behind the DS11 fs11b corruption."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is]]
            [eve.host :refer [swap!]]
            [eve.atom :as a]
            [eve.slab-io :as slab]
            [eve.tally :as tally]
            [ds11-fixture.box :as box]))

(deftest user-namespace-root-publishes-directly
  (let [base (str (System/getProperty "java.io.tmpdir")
                  "/eve-user-ns-root-" (System/nanoTime))
        d (a/persistent-atom-domain base :journal? false)
        x (a/lookup-or-create-mmap-atom! d "t/box" nil)]
    (swap! x (fn [_] (box/->Box 1 {:a 1 :b 2})))
    (let [sio (a/atom-sio x)
          ptr (:ptr (a/seal x))]
      (is (= box/Box-type-id (slab/read-u8 sio ptr 0))
          "the root block is the Box block itself, not a scalar wrapper")
      (is (= 1 (tally/count-of sio ptr))
          "the slot's hold counts the root block")
      (is (< 1 (:blocks (a/seal x)))
          "seal reaches the tree beneath the root"))
    (dotimes [i 50]
      (swap! x (fn [b] (box/->Box (inc (box/box-version b))
                                  (assoc (box/box-items b)
                                         (keyword (str "k" i)) i)))))
    (is (= 51 (box/box-version @x)))
    (is (= 52 (count (box/box-items @x))))
    (a/close-atom-domain! d)
    (let [d2 (a/persistent-atom-domain base :journal? false)
          x2 (a/lookup-or-create-mmap-atom! d2 "t/box" nil)]
      (is (= 51 (box/box-version @x2)))
      (is (= 52 (count (box/box-items @x2))))
      (a/close-atom-domain! d2))))
