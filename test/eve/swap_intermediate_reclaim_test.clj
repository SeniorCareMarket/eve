(ns eve.swap-intermediate-reclaim-test
  "What a WON swap attempt allocated that its new root does not reach is freed at the commit (2026-11-20 ET).

   The leak this pins: an Eve atom holding a 200-entry map, swap!'d with two-key assocs, kept one class-0 header and
   one HAMT path per swap for ever — the first assoc's map, built on the way to the second, was allocated through the
   attempt's log, never reached by the new root's hold, and so never counted, parked or freed (only a LOST attempt
   freed its unheld allocations: tally/free-unheld!). Measured on the in-process domain at eve 9addb4f: class 0 at
   10,341 blocks after 10,000 swaps, +0.46 KB/swap; with the won path freeing them (tally/free-unreached!) used stays
   within a few dozen blocks of its start. A swap whose f allocates and then answers the old value (the same-value
   path) frees what it allocated too.

   Read in read-end :commit (C): a view swap! answers in read-end :gc (B) holds its pin until the JVM's GC collects
   it, which defers reclamation by GC cadence (a bounded sawtooth) and would make the count here depend on when the
   GC runs."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as ea]
            [eve.view :as view]
            [eve.slab-io :as slab]
            [eve.mem :as mem]
            [eve.deftype-proto.alloc :as alloc]))

(defn- used-blocks
  "Blocks allocated in each bitmap class (0-5) of atom a's domain: a class's total less its free count."
  [a]
  (let [rs (.-regions (slab/domain-ctx (alloc/domain-root (ea/atom-sio a))))]
    (vec (for [i (range 6)]
           (if-let [r (aget ^objects rs i)]
             (- (mem/-load-i32 r 8) (mem/-load-i32 r 12))
             0)))))

(defn- in-read-end-c [f]
  (let [mode (view/read-end-mode)]
    (view/set-read-end! :commit)
    (try (f) (finally (view/set-read-end! mode)))))

(def ^:private kws (mapv #(keyword (str "k" %)) (range 200)))

(deftest two-key-assocs-plateau
  (in-read-end-c
   (fn []
     (let [a (ea/atom (keyword "eve.swap-intermediate-reclaim-test" (str "assoc2-" (System/nanoTime)))
                      (zipmap kws (range 200)))
           _ (dotimes [i 200] (swap! a assoc (kws i) i))   ;; settle
           start (used-blocks a)]
       (dotimes [i 3000]
         (swap! a assoc (kws (mod i 200)) i (kws (mod (+ i 101) 200)) (- i)))
       (let [end (used-blocks a)
             grown (mapv - end start)]
         (testing "the value is right"
           (is (= 200 (count @a)))
           (is (= 2999 (get @a (kws (mod 2999 200))))))
         (testing "no class keeps a block per swap (the leak kept ~3,000 in class 0 alone)"
           (is (every? #(< % 300) grown) (str "start " start " end " end))))))))

(deftest same-value-swap-frees-what-f-allocated
  (in-read-end-c
   (fn []
     (let [a (ea/atom (keyword "eve.swap-intermediate-reclaim-test" (str "same-" (System/nanoTime)))
                      (zipmap kws (range 200)))
           _ (dotimes [i 50] (swap! a identity))
           start (used-blocks a)]
       ;; f builds a new map on the way and answers the old one: nothing is published
       (dotimes [i 2000]
         (swap! a (fn [m] (let [_ (assoc m :scratch i (kws (mod i 200)) -1)] m))))
       (let [grown (mapv - (used-blocks a) start)]
         (is (= 200 (count @a)))
         (is (not (contains? @a :scratch)))
         (is (every? #(< % 100) grown) (str "grown " grown)))))))
