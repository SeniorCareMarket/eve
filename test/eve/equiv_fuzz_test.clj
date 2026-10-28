(ns eve.equiv-fuzz-test
  "Merkle Phase 4 (§2b, plan §8.2) — dual-path fuzz for eve equiv.

  Each generated host value is written into the slab TWICE, independently
  (two distinct roots), and eve's `=` on the two slab values is compared
  against host Clojure `=` on the source values — the semantic eve must
  match. Modes: 0 = same value written twice (equal by construction),
  1 = one leaf mutated before the second write, 2 = independently
  generated second value, 3 = cross-partition: the same sequential
  content written as a vec on one side and a list on the other.

  Today eve equiv is walk-only, so this is the BASELINE: any disagreement
  here indicts the existing walk (or this harness). When Phase 3a's cached
  digests land and equiv consults them, this same test is the fast-path
  acceptance gate: ZERO disagreements tolerated.

  Numbers are ints only. Doubles are excluded by design: NaN / ±0.0 are
  seal step-asides (plan §8.1(3) disposition), not fuzz surface.

  PORTED from canonical eve's Merkle phase-4 equality work. One adaptation: the slab serializer is
  eve.codec/value+sio->eve-bytes here (eve.mem/... in canonical)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.codec :as codec]
            [eve.map  :as eve-map]
            [eve.vec  :as eve-vec]
            [eve.set  :as eve-set]
            [eve.list :as eve-list]))

;; ── deterministic PRNG (same LCG constants as the VS harness) ──────────
(def ^:private rng (atom 0))
(defn- rnd-next! [] (swap! rng (fn [s] (mod (+ (* s 1103515245) 12345) 2147483648))))
(defn- rnd-int! [n] (mod (rnd-next!) n))
(defn- rnd-bool! [] (zero? (rnd-int! 2)))

;; ── host value generator ───────────────────────────────────────────────
(declare gen-value)
(defn- gen-scalar []
  (case (rnd-int! 5)
    0 (rnd-int! 1000)
    1 (str "s" (rnd-int! 50))
    2 (keyword (str "k" (rnd-int! 50)))
    3 (rnd-bool!)
    4 nil))
(defn- gen-coll [depth]
  (case (rnd-int! 4)
    0 (vec (repeatedly (rnd-int! 6) #(gen-value (inc depth))))
    1 (apply list (repeatedly (rnd-int! 6) #(gen-value (inc depth))))
    2 (into {} (repeatedly (rnd-int! 5)
                           (fn [] [(keyword (str "k" (rnd-int! 20)))
                                   (gen-value (inc depth))])))
    3 (set (repeatedly (rnd-int! 5) gen-scalar))))
(defn- gen-value [depth]
  (if (or (>= depth 3) (< (rnd-int! 10) 6))
    (gen-scalar)
    (gen-coll depth)))

;; ── mutation: change one scalar leaf (sets are left whole; their
;;    unequal pairs come from mode 2) ─────────────────────────────────────
(defn- mutate [v]
  (cond
    (vector? v) (if (empty? v) v (update v (rnd-int! (count v)) mutate))
    (seq? v) (if (empty? v) v
                 (let [i (rnd-int! (count v))]
                   (apply list (map-indexed (fn [j x] (if (= j i) (mutate x) x)) v))))
    (map? v) (if (empty? v) v
                 (let [k (nth (vec (keys v)) (rnd-int! (count v)))]
                   (update v k mutate)))
    (set? v) v
    (number? v) (+ v 1000000)
    (string? v) (str v "X")
    (keyword? v) (keyword (str (name v) "X"))
    (boolean? v) (not v)
    :else v))

;; ── slab writers: host value -> eve value (top-level dispatch; nested
;;    values are written by the writers themselves) ──────────────────────
(defn- write-value [sio v]
  (let [sv (partial codec/value+sio->eve-bytes sio)]
    (cond
      (map? v)    (eve-map/jvm-eve-hash-map-from-offset sio (eve-map/jvm-write-map! sio v))
      (vector? v) (eve-vec/jvm-sabvec-from-offset sio (eve-vec/jvm-write-vec! sio sv v))
      (set? v)    (eve-set/jvm-eve-hash-set-from-offset sio (eve-set/jvm-write-set! sio sv v))
      (seq? v)    (eve-list/jvm-sab-list-from-offset sio (eve-list/jvm-write-list! sio sv v))
      :else v)))

(defn- shallow-sequential? [v]
  (and (sequential? v) (every? #(not (coll? %)) v)))

(defn- run-batch
  "Returns [pairs disagreements first-counterexamples]."
  [seed pairs]
  (reset! rng seed)
  (let [sio (alloc/make-jvm-heap-slab-ctx {})]
    (loop [i 0 dis 0 cex []]
      (if (= i pairs)
        [pairs dis cex]
        (let [a (gen-value 0)
              mode (rnd-int! 4)
              [ha hb] (case mode
                        0 [a a]
                        1 [a (mutate a)]
                        2 [a (gen-value 0)]
                        3 (if (shallow-sequential? a)
                            [(vec a) (apply list a)]
                            [a a]))
              ea (write-value sio ha)
              eb (write-value sio hb)
              fast (= ea eb)
              oracle (= ha hb)]
          (if (= fast oracle)
            (recur (inc i) dis cex)
            (recur (inc i) (inc dis)
                   (if (< (count cex) 3)
                     (conj cex {:mode mode :fast fast :oracle oracle
                                :a ha :b hb})
                     cex))))))))

(deftest vec-equals-nil-element-regression
  (testing "EveVector.equals on a nil element (canonical find, fuzz baseline run 3; vendored tree already carried the fix — this is its regression guard here)"
    (let [sio (alloc/make-jvm-heap-slab-ctx {})
          w (fn [v] (write-value sio v))]
      ;; the fuzz stack: list equiv -> map equiv (mapEquals) -> vec .equals -> NPE
      (is (= (w (list {:a [nil]})) (w (list {:a [nil]}))))
      ;; the direct shape: a map whose value is an eve vec containing nil
      (is (= (w {:a [nil]}) (w {:a [nil]})))
      (is (not= (w {:a [nil]}) (w {:a [1]})))
      ;; .equals (Java) directly, both directions
      (is (.equals (w [nil]) (w [nil])))
      (is (.equals (w [1 nil 3]) (w [1 nil 3])))
      (is (not (.equals (w [nil]) (w [0])))))))

(deftest equiv-fuzz-baseline
  (testing "eve equiv agrees with host = on independently written values"
    ;; Scale with -Dmerkle.fuzz.seeds=N -Dmerkle.fuzz.pairs=M (the Phase 4
    ;; acceptance run: 20 seeds x 5000 pairs = 100,000 pairs).
    (let [seed-pool [424243 777001 123457 900001 5150 271828 314159 161803
                     424242 8675309 1000003 777777 31337 600613 20241024
                     111111 222222 333333 444444 555555]
          seeds (take (Integer/getInteger "merkle.fuzz.seeds" 3) seed-pool)
          pairs-per-seed (Integer/getInteger "merkle.fuzz.pairs" 2000)
          results (mapv #(run-batch % pairs-per-seed) seeds)
          total (reduce + (map first results))
          dis (reduce + (map second results))
          cex (mapcat #(nth % 2) results)]
      (println "equiv-fuzz pairs=" total "disagreements=" dis)
      (when (seq cex) (println "counterexamples:" (pr-str cex)))
      (is (zero? dis)
          (str "equiv disagreed with host = " dis " times; first: "
               (pr-str (first cex)))))))
