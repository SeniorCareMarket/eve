(ns eve.thread-fold-group-test
  "eve.thread's fold and =>> on a group (gap EVT-5): a domain pool (eve.thread.domain) whose members are two
   runtimes attached to one rendezvous atom's meta-atom on disk — the moves runtimes of several processes and hosts
   make (scripts/thread-fold-rendezvous.clj runs them across processes). C1 fold over the group equal to the sequential
   reduce, its chunks claimed by both runtimes; C2 =>> equal to ->> there, its separable runs written as data; C3 the
   group's chunks sized from the members' measured meta-atom swaps, published as each runtime's :measured :swap-us
   (P§5.4). Every row ends with the rendezvous atom's seal as it was before any coordination. Rows read only the thread
   contract's declarations, never a host's name; a host with no threads (thread/none) runs the rows with its waits."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as ea]
            [eve.host]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-host :as h]
            [eve.thread.domain :as dom]
            [eve.thread.fold :as fold]))

;; ---------------------------------------------------------------------------
;; Tasks: what crosses to any member by name
;; ---------------------------------------------------------------------------

(t/deftask plus ([] 0) ([a b] (+ a b)))

(t/deftask by-runtime ([] {}) ([a b] (merge-with + a b)))

;; a chunk's elements counted under the runtime that ran it; with meet? its first element waits (bounded) at a gate in the
;; domain until both runtimes have entered — the folder's runtime holds at most three of the four chunks in flight (its
;; two pumps and its helping caller), so the other's threads must claim one
(defn- gate [] (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain (:base (:dh dom/*running*))) "test/gate" {}))

(t/deftask gate-tally [acc [_ meet?]]
  (let [rid (or (:rid (:dh dom/*running*)) "here")]
    (when (and meet? (empty? acc))
      (let [g (gate)
            deadline (+ (proc/now-ms) 60000)]
        (eve.host/swap! g update :rids (fnil conj #{}) rid)
        (loop [] (when (and (< (count (:rids @g)) 2) (< (proc/now-ms) deadline)) (proc/sleep-ms! 5) (recur)))))
    (update acc rid (fnil inc 0))))

(t/defconveyed *spins* 1)

(t/deftask spin [x] (loop [i *spins* a x] (if (zero? i) a (recur (dec i) (mod (+ (* a 31) i) 1000003)))))

(t/deftask spin-plus [acc x] (+ acc (spin x)))

(t/deftask sq [x] (* x x))
(t/deftask big? [x] (> x 50))
(t/deftask twice [x] [x x])
(t/deftask odd-x? [x] (odd? x))
(t/deftask tens-of [x] (quot x 10))

(defn- threads? [] (not= 0 (:max-threads (h/driving))))

(def ttl 600)

(def opts {:ttl ttl :requires '[eve.thread-fold-group-test]})

(defn- fresh-base []
  ;; (the same directory on every host this suite runs on)
  (str "/tmp/eve-thread-fold-group-" (rand-int 1000000000) "/d"))

(def app-value {:app "eve" :xs [1 2 3] :nested {:k #{:a :b} :n 42}})

(defn- group
  "A fresh domain's rendezvous atom holding app-value, its seal before any coordination, and two runtimes of this
   process attached to it, each serving pool :g with two threads (none where the host has none): [a seal0 r1 r2 g1 g2]."
  []
  (let [dom (ea/persistent-atom-domain (fresh-base))
        a (ea/lookup-or-create-mmap-atom! dom "app/state" app-value)
        seal0 (ea/seal a)
        r1 (dom/attach! a (assoc opts :rid "r1"))
        r2 (dom/attach! a (assoc opts :rid "r2"))
        n (if (threads?) 2 0)
        g1 (dom/pool r1 :g {:threads n})
        g2 (dom/pool r2 :g {:threads n})]
    [a seal0 r1 r2 g1 g2]))

(defn- leave! [a seal0 r1 r2]
  (dom/detach! r2)
  (dom/detach! r1)
  (is (= seal0 (ea/seal a)) "the rendezvous atom's seal: as it was before any coordination")
  (is (= app-value @a))
  (ea/close-atom-domain! (ea/persistent-atom-domain (:base-path (ea/-domain-state a)))))

(defn- refused [r] (when (false? (first r)) (get-in (second r) [:data :eve.thread/refused])))

;; ---------------------------------------------------------------------------
;; C1 — fold on a group
;; ---------------------------------------------------------------------------

(deftest fold-on-a-group
  (let [[a seal0 r1 r2 g1 g2] (group)
        xs (vec (range 600))]
    ;; (a chunk is three swaps of the meta-atom — on basilisp, two runtimes' tickers and pumps contending in one process,
    ;; seconds each [ran: 1,146 s for ~100 descriptors] — so these rows make about a dozen; seams at every chunk size are
    ;; eve.thread-fold-test's, on every host)
    (testing "equal to the sequential reduce at every chunk size, from either runtime"
      (is (= (reduce + (range 5)) (fold/fold-in g1 1 plus plus (vec (range 5)))) "n 1")
      (doseq [n [150 5000]]
        (is (= (reduce + xs) (fold/fold-in g1 n plus plus xs)) (str "r1, n " n))
        (is (= (reduce + xs) (fold/fold-in g2 n plus plus xs)) (str "r2, n " n))))
    (testing "measured chunks; with-pool and eve.thread's fold"
      (is (= (reduce + xs) (t/with-pool g2 (fold/fold plus xs)))))
    (testing "the chunks are the group's descriptors: both runtimes ran them"
      (let [ys (mapv (fn [i] [i (threads?)]) (range 80))
            by (fold/fold-in g1 10 by-runtime gate-tally ys)]
        (is (= (count ys) (reduce + (vals by))))
        (is (every? #{"r1" "r2"} (keys by)) "every chunk ran on a member (the waiter's runtime helps under its own rid)")
        (when (threads?) (is (= #{"r1" "r2"} (set (keys by))) (str "both runtimes ran chunks: " by)))))
    (testing "a closure cannot cross: refused with the rule's message"
      (is (= :closure (refused (t/attempt (fn [] (fold/fold-in g1 10 + + xs)))))))
    (leave! a seal0 r1 r2)))

;; ---------------------------------------------------------------------------
;; C2 — =>> on a group
;; ---------------------------------------------------------------------------

(deftest =>>-on-a-group
  (let [[a seal0 r1 r2 g1 g2] (group)
        xs (vec (concat (mapcat (fn [i] (repeat (inc (mod i 3)) (+ 1 (* 2 i)))) (range 20)) [60] (range 61 100 2)))]
    (doseq [n [16 nil]]
      (testing (str "*chunk* " n)
        (is (= (vec (->> xs (map sq) (filter big?) (mapcat twice) (dedupe) (take-while odd-x?) (partition-by tens-of)))
               (t/with-pool g1 (binding [fold/*chunk* n]
                                 (vec (t/=>> xs (map sq) (filter big?) (mapcat twice) (dedupe) (take-while odd-x?)
                                             (partition-by tens-of)))))))
        (is (= (vec (->> xs (map twice) (apply concat) (replace {1 1000 61 1060}) (remove odd-x?)))
               (t/with-pool g2 (binding [fold/*chunk* n]
                                 (vec (t/=>> xs (map twice) cat (replace {1 1000 61 1060}) (remove odd-x?)))))))
        (is (= (vec (->> xs (keep big?) (distinct)))
               (t/with-pool g1 (binding [fold/*chunk* n] (vec (t/=>> xs (keep big?) (distinct)))))))))
    (testing "a separable run written as data travels as eve.thread.fold's xf-chunk task"
      (let [plans (atom [])]
        (t/with-pool g1 (binding [fold/*chunk* 31 fold/*on-plan* (fn [x] (swap! plans conj x))]
                          (vec (t/=>> xs (map sq) (dedupe) (filter big?)))))
        (is (= [[:map] [:filter]] (map :run @plans)))
        (is (every? (fn [x] (= :g (:pool x))) @plans))))
    (testing "a run whose argument is no registered task: its forms, which this pool (no :forms) refuses"
      (is (= :form (refused (t/attempt (fn [] (t/with-pool g1 (binding [fold/*chunk* 5] (vec (t/=>> xs (map inc)))))))))))
    (leave! a seal0 r1 r2)))

;; ---------------------------------------------------------------------------
;; C3 — the group's chunks sized from its members' measured swaps
;; ---------------------------------------------------------------------------

(defn- ns-per-spin
  "One spin step's cost here, ns: the fastest of three measurements (see eve.thread-fold-test)."
  []
  (binding [*spins* 1000]
    (reduce min (repeatedly 3 (fn []
                                (loop [k 2]
                                  (let [t0 (proc/now-ms)
                                        _ (reduce spin-plus 0 (range k))
                                        dt (- (proc/now-ms) t0)]
                                    (if (or (>= dt 20) (>= k 1000000)) (max 1 (quot (* dt 1000) k)) (recur (* 2 k))))))))))

(defn- element-ns
  "One element of spin-plus at k spins, its cost here, ns: the fastest of three measurements."
  [k]
  (binding [*spins* k]
    (reduce min (repeatedly 3 (fn []
                                (loop [m 64]
                                  (let [t0 (proc/now-ms)
                                        _ (reduce spin-plus 0 (range m))
                                        dt (- (proc/now-ms) t0)]
                                    (if (or (>= dt 20) (>= m 4000000)) (max 1 (quot (* dt 1000000) m)) (recur (* 2 m))))))))))

(defn- heavy-enough
  "[spins plan value]: (run spins on-plan) from k spins, four times heavier each time, until its plan distributes it —
   at most six times (see eve.thread-fold-test)."
  [k run]
  (loop [k k i 0]
    (let [plans (atom [])
          v (run k (fn [x] (swap! plans conj x)))
          x (first @plans)]
      (if (or (:n x) (>= i 6)) [k x v] (recur (* 4 k) (inc i))))))

(defn- elements-of-ms [spins ms] (min 200000 (max 16 (quot (* ms 1000000) (max 1 (* spins (ns-per-spin)))))))

(deftest the-chunks-of-a-group-are-sized-from-its-members-measured-swaps
  (let [[a seal0 r1 r2 g1 g2] (group)]
    (testing "each runtime measures its own meta-atom swaps and publishes them in its descriptor (:measured)"
      (let [c1 (fold/swap-cost g1)
            m1 (get-in (dom/registry r1) [:runtimes "r1" :measured])]
        (is (pos? (:swap-us m1)) "r1's swap cost, in the registry")
        (is (some? (:carry-ns m1)) "r1's carrying cost, in the registry")
        (is (= 3 (:swaps c1)) "a descriptor's swaps here: its submission, its claim, its completion")
        (is (nil? (get-in (dom/registry r1) [:runtimes "r2" :measured :swap-us])) "r2 has not folded: nothing measured")
        (let [c2 (fold/swap-cost g2)
              m2 (get-in (dom/registry r2) [:runtimes "r2" :measured])]
          (is (pos? (:swap-us m2)) "r2's, in the registry")
          (if (threads?)
            (do (is (= (max (:swap-us m1) (:swap-us m2)) (:swap-us c2)) "the group's cost is its slowest member's")
                (is (= (max (:carry-ns m1) (:carry-ns m2)) (:carry-ns c2)))
                (is (= c2 (fold/swap-cost g1)) "whichever member folds"))
            ;; (no runtime serves the pool — a threadless one joins none — so its folder runs the work in its waits)
            (is (= (:swap-us m2) (:swap-us c2)) "with no member threads, the folder's own")))))
    (testing "an element cheaper than ten times its carrying: no chunk is worth its swaps — reduced where it was called"
      (let [plans (atom [])
            c (fold/swap-cost g1)
            ;; the cheapest element (one spin), its cost measured here, warm; enough of them to outlast the probe
            e1 (element-ns 1)
            ys (vec (range (min 400000 (max 64 (quot 40000000 (max 1 e1))))))
            r (t/with-pool g1 (binding [*spins* 1 fold/*on-plan* (fn [x] (swap! plans conj x))] (fold/fold plus spin-plus ys)))
            x (first @plans)]
        (is (= (binding [*spins* 1] (reduce spin-plus 0 ys)) r))
        (is (= (fold/chunk-size (merge x (:probe x))) (:n x)) "the plan's choice is chunk-size's")
        ;; (where even the cheapest element costs more than half its carrying, the consistency above is the row)
        (when (< (* 2 e1) (:carry-ns c))
          (is (true? (:here x)) (str "reduced here: " x " " c " element " e1 " ns"))
          (is (nil? (:n x))))))
    (testing "an element worth its carrying: the group's chunks are chunk-size of the measurements"
      (let [c (fold/swap-cost g1)
            k0 (max 1 (quot (max 2000 (* 120 (:carry-ns c))) (ns-per-spin)))
            ;; (each with the group's cost as read just before and just after it: a member whose lease lapsed under the
            ;; load rejoins with a fresh descriptor, and measures again at its next fold)
            cost-now (fn [g] (:swap-us (fold/swap-cost g)))
            [k pf [ys rf cf]] (heavy-enough k0 (fn [k on] (let [ys (vec (range (elements-of-ms k 60)))
                                                                b (cost-now g1)
                                                                v (t/with-pool g1 (binding [*spins* k fold/*on-plan* on]
                                                                                    (fold/fold plus spin-plus ys)))]
                                                            [ys v (hash-set b (cost-now g1))])))
            [kx px [zs rx cx]] (heavy-enough k (fn [k on] (let [zs (vec (range (elements-of-ms k 60)))
                                                                b (cost-now g2)
                                                                v (t/with-pool g2 (binding [*spins* k fold/*on-plan* on]
                                                                                    (vec (t/=>> zs (map spin) (filter odd-x?)))))]
                                                            [zs v (hash-set b (cost-now g2))])))]
        (is (= (binding [*spins* k] (reduce spin-plus 0 ys)) rf))
        (is (= (binding [*spins* kx] (vec (->> zs (map spin) (filter odd-x?)))) rx))
        (doseq [[x costs] [[pf cf] [px cx]]]
          (is (pos? (:elements (:probe x))))
          (is (contains? costs (:swap-us x)) (str "the group's measured swap cost: " (:swap-us x) " of " costs))
          (is (= 3 (:swaps x)))
          (is (some? (:n x)) (str "worth distributing: " x))
          (is (= (fold/chunk-size (merge x (:probe x))) (:n x)) "n is chunk-size of the measurements"))))
    (leave! a seal0 r1 r2)))
