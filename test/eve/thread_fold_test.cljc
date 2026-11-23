(ns eve.thread-fold-test
  "eve.thread's fold and =>> in one process (gap EVT-5; EVE-THREAD plan P§1.5, P§2.1, P§5.7): C1 fold over
   a pool — chunk, reduce, combine — equal to the sequential reduce; C2 =>> equal to ->> on every registered transducer,
   with dedupe, take-while, halt-when and random-sample out of the parallel set; C3 chunks sized from the pool's
   measured swap cost. The group form (a domain pool, several runtimes) is eve.thread-fold-group-test. Every row reads
   eve.thread, the thread contract's declarations ((driving), (ships-closures?)) or the form a pool declares (:local,
   :routed) — never a host's name. The arguments of the rows' transducers and reductions are registered tasks, so the
   same rows run where the pool's threads are isolates (they load this namespace: :requires)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-host :as h]
            [eve.thread.fold :as fold]))

;; ---------------------------------------------------------------------------
;; Tasks: what crosses a heap by name
;; ---------------------------------------------------------------------------

(t/deftask plus ([] 0) ([a b] (+ a b)))

(t/deftask cat-v ([] []) ([a b] (into a b)))

(t/deftask conj-x [acc x] (conj acc x))

(t/deftask kv-plus [acc k v] (+ acc k v))

(t/deftask merge-tally ([] {}) ([a b] (merge-with + a b)))

;; Where a pool's threads share this heap its waiting caller helps — runs the queued work it waits for — so a row that
;; shows the pool's threads running chunks makes each element meet a second runner first (bounded): the caller runs one
;; chunk at a time, so a pool thread must enter. (A routed pool's caller cannot run its work: no meeting there.)
(defonce runners (atom #{}))

(defn- meet! [meet?]
  (when meet?
    (swap! runners conj (h/self))
    (let [deadline (+ (proc/now-ms) 10000)]
      (loop [] (when (and (< (count @runners) 2) (< (proc/now-ms) deadline)) (proc/sleep-ms! 1) (recur))))))

(t/deftask tally [acc [_ meet?]] (meet! meet?) (update acc (h/self) (fnil inc 0)))

(t/deftask sum-to-100 [acc x] (let [a (+ acc x)] (if (>= a 100) (reduced a) a)))

(t/deftask boom-at-77 [acc x] (if (= x 77) (throw (ex-info "boom" {:x x})) (+ acc x)))

(t/defconveyed *spins* 1)

;; an element of a weight the row chooses: *spins* steps of arithmetic (conveyed wherever the chunk runs)
(t/deftask spin [x] (loop [i *spins* a x] (if (zero? i) a (recur (dec i) (mod (+ (* a 31) i) 1000003)))))

(t/deftask spin-plus [acc x] (+ acc (spin x)))

(t/deftask inc-x [x] (inc x))
(t/deftask odd-x? [x] (odd? x))
(t/deftask even-x? [x] (even? x))
(t/deftask twice [x] [x x])
(t/deftask tens [x] (when (odd? x) (* 10 x)))
(t/deftask third [x] (quot x 3))
(t/deftask idx-pair [i x] [i x])
(t/deftask odd-idx [i x] (when (odd? i) x))
(t/deftask stop-at [r x] [:stop x])
(t/deftask on-thread [[x meet?]] (meet! meet?) [x (h/self)])

(def wait-ms 30000)

(def iso
  "The options every row's pool takes: isolates load this namespace; forms may cross (P§10 Q4's opt-in)."
  {:requires '[eve.thread-fold-test] :forms true})

(defn- threads? [] (not= 0 (:max-threads (h/driving))))

(defn- form-of [p] (:form (t/pool-descriptor p)))

(defn- meeting
  "Elements 0 … n-1, each with whether it meets a second runner first (a pool of this heap's threads), the meeting reset."
  [p n]
  (reset! runners #{})
  (let [meet? (and (= :local (form-of p)) (threads?))]
    (mapv (fn [i] [i meet?]) (range n))))

(defn- pool-threads [p] (let [d (t/pool-descriptor p)] (set (concat (:threads d) (:members d)))))

(defn- close! [& ps]
  (doseq [p ps]
    (t/shutdown! p)
    (is (t/await-termination p wait-ms) (str "pool " (t/pool-name p) " terminated"))))

(defn- refused [r] (when (false? (first r)) (get-in (second r) [:data :eve.thread/refused])))

(defn- forms-cross?
  "Can a form reach the pool's threads: they share this heap, or they can eval."
  []
  (or (h/ships-closures?) (:eval (h/driving)) (not (threads?))))

;; ---------------------------------------------------------------------------
;; C2 — =>> equal to ->> on every registered transducer
;; ---------------------------------------------------------------------------

(def seam-input
  "Runs of repeats (dedupe's seams), a long odd prefix, an even (take-while's and halt-when's end), then odds again."
  (vec (concat (mapcat (fn [i] (repeat (inc (mod i 3)) (+ 1 (* 2 i)))) (range 30)) [40] (range 41 140 2))))

(defn- rows
  "[row =>>-thread ->>-thread]: each registered transducer, alone and in mixed threads. Where ->> gives a transducer no
   meaning (cat, halt-when) the reference is its lazy-seq equivalent."
  []
  [[:map (fn [xs] (t/=>> xs (map inc-x))) (fn [xs] (->> xs (map inc-x)))]
   [:filter (fn [xs] (t/=>> xs (filter odd-x?))) (fn [xs] (->> xs (filter odd-x?)))]
   [:remove (fn [xs] (t/=>> xs (remove odd-x?))) (fn [xs] (->> xs (remove odd-x?)))]
   [:keep (fn [xs] (t/=>> xs (keep tens))) (fn [xs] (->> xs (keep tens)))]
   [:mapcat (fn [xs] (t/=>> xs (mapcat twice))) (fn [xs] (->> xs (mapcat twice)))]
   [:cat (fn [xs] (t/=>> xs (map twice) cat)) (fn [xs] (->> xs (map twice) (apply concat)))]
   [:replace (fn [xs] (t/=>> xs (replace {1 :one 41 :forty-one}))) (fn [xs] (->> xs (replace {1 :one 41 :forty-one})))]
   [:dedupe (fn [xs] (t/=>> xs (dedupe))) (fn [xs] (->> xs (dedupe)))]
   [:take-while (fn [xs] (t/=>> xs (take-while odd-x?))) (fn [xs] (->> xs (take-while odd-x?)))]
   [:halt-when (fn [xs] (t/=>> xs (halt-when even-x?))) (fn [xs] (->> xs (take-while (complement even-x?))))]
   [:halt-when-retf (fn [xs] (t/=>> xs (halt-when even-x? stop-at))) (fn [xs] (->> xs (take-while (complement even-x?))))]
   [:random-sample-1 (fn [xs] (t/=>> xs (random-sample 1.0))) (fn [xs] (->> xs (random-sample 1.0)))]
   [:random-sample-0 (fn [xs] (t/=>> xs (random-sample 0.0))) (fn [xs] (->> xs (random-sample 0.0)))]
   [:drop-while (fn [xs] (t/=>> xs (drop-while odd-x?))) (fn [xs] (->> xs (drop-while odd-x?)))]
   [:take (fn [xs] (t/=>> xs (take 17))) (fn [xs] (->> xs (take 17)))]
   [:drop (fn [xs] (t/=>> xs (drop 17))) (fn [xs] (->> xs (drop 17)))]
   [:take-nth (fn [xs] (t/=>> xs (take-nth 3))) (fn [xs] (->> xs (take-nth 3)))]
   [:partition-all (fn [xs] (t/=>> xs (partition-all 4))) (fn [xs] (->> xs (partition-all 4)))]
   [:partition-by (fn [xs] (t/=>> xs (partition-by third))) (fn [xs] (->> xs (partition-by third)))]
   [:distinct (fn [xs] (t/=>> xs (distinct))) (fn [xs] (->> xs (distinct)))]
   [:interpose (fn [xs] (t/=>> xs (interpose 0))) (fn [xs] (->> xs (interpose 0)))]
   [:map-indexed (fn [xs] (t/=>> xs (map-indexed idx-pair))) (fn [xs] (->> xs (map-indexed idx-pair)))]
   [:keep-indexed (fn [xs] (t/=>> xs (keep-indexed odd-idx))) (fn [xs] (->> xs (keep-indexed odd-idx)))]
   ;; a separable run around the four, and steps
   [:mixed (fn [xs] (t/=>> xs (map inc-x) (dedupe) (mapcat twice) (take-while even-x?) (filter even-x?) (partition-all 3)))
    (fn [xs] (->> xs (map inc-x) (dedupe) (mapcat twice) (take-while even-x?) (filter even-x?) (partition-all 3)))]
   [:mixed-steps (fn [xs] (t/=>> xs (map inc-x) (remove odd-x?) vec (keep tens) (concat [1 2 3]) (filter odd-x?)))
    (fn [xs] (->> xs (map inc-x) (remove odd-x?) vec (keep tens) (concat [1 2 3]) (filter odd-x?)))]])

(def chunk-sizes
  "*chunk* for the C2 rows: chunks of one element, small odd ones (seams everywhere), larger than the input, and
   measured (nil: the probe takes this small input whole)."
  [1 3 7 64 1000 nil])

(deftest =>>-equals-->>-on-every-registered-transducer
  (let [p (t/fixed-pool 3 iso)
        xs seam-input]
    (doseq [[row par ref] (rows) n chunk-sizes]
      (is (= (vec (ref xs)) (t/with-pool p (binding [fold/*chunk* n] (vec (par xs)))))
          (str row " with *chunk* " n)))
    (testing "the input as a lazy seq: the same"
      (doseq [[row par ref] (rows)]
        (is (= (vec (ref (seq xs))) (t/with-pool p (binding [fold/*chunk* 5] (vec (par (map identity xs))))))
            (str row " over a seq"))))
    (testing "a thread with no transducer is ->>"
      (is (= 14 (t/=>> 3 (+ 4) (* 2))))
      (is (= (vec (->> xs (concat [1]) (map + xs))) (vec (t/=>> xs (concat [1]) (map + xs))))
          "map with a collection is a step")
      (is (= 7 (t/=>> 7))))
    (testing "lazy: an infinite input, a bounded consumer"
      (is (= [2 4 6 8 10] (t/with-pool p (binding [fold/*chunk* 4] (vec (t/=>> (range) (map inc-x) (filter even-x?) (take 5))))))))
    (close! p)))

(defn- subsequence?
  "Is a an in-order subsequence of b."
  [a b]
  (loop [a (seq a) b (seq b)]
    (cond (nil? a) true
          (nil? b) false
          (= (first a) (first b)) (recur (next a) (next b))
          :else (recur a (next b)))))

(defn- per-chunk
  "xf applied to each chunk of n and the outputs concatenated: what injest's =>> did with its parallel set."
  [xf n xs]
  (mapcat (fn [c] (fold/xf-seq xf c)) (partition-all n xs)))

(deftest the-parallel-set-is-corrected
  (testing "the chunk-separable set: map filter remove keep mapcat cat replace"
    (is (= #{:map :filter :remove :keep :mapcat :cat :replace}
           (set (filter fold/parallel? (keys fold/transducers))))))
  (testing "injest's parallel set had dedupe, take-while, halt-when and random-sample: out of it"
    (doseq [k [:dedupe :take-while :halt-when :random-sample]]
      (is (contains? fold/transducers k) (str k " registered"))
      (is (not (fold/parallel? k)) (str k " stepped here"))))
  (testing "disj! and dissoc!, in both of injest's registries, are no transducers: not registered"
    (is (not (contains? fold/transducers :disj!)))
    (is (not (contains? fold/transducers :dissoc!))))
  (testing "why: applied per chunk the four differ from the whole (a duplicate kept at a seam, what follows the first
            failure kept in later chunks)"
    (let [xs seam-input]
      (is (not= (vec (dedupe xs)) (vec (per-chunk (dedupe) 4 xs))))
      (is (not= (vec (take-while odd? xs)) (vec (per-chunk (take-while odd?) 4 xs))))
      (is (not= (vec (take-while odd? xs)) (vec (per-chunk (halt-when even?) 4 xs))))
      (is (= (vec (map inc xs)) (vec (per-chunk (map inc) 4 xs))) "a separable one: per chunk equals the whole")))
  (testing "random-sample keeps an in-order subsequence of its input, its draws made here"
    (let [p (t/fixed-pool 2 iso)
          r (t/with-pool p (binding [fold/*chunk* 3] (vec (t/=>> seam-input (map inc-x) (random-sample 0.5)))))]
      (is (subsequence? r (map inc seam-input)))
      (close! p)))
  (testing "=>>'s plan of a thread: runs of separable and non-separable transducers, every other form a step"
    (is (= [:par :seq :par :seq :step]
           (map :kind (fold/segments [['(map f) 'clojure.core/map] ['(dedupe) 'clojure.core/dedupe]
                                      ['(filter g) 'clojure.core/filter] ['cat 'clojure.core/cat]
                                      ['(take 3) 'clojure.core/take] ['(reduce +) 'clojure.core/reduce]]))))
    (is (= [[:map] [:dedupe] [:filter :cat] [:take] [nil]]
           (map :keys (fold/segments [['(map f) 'clojure.core/map] ['(dedupe) 'clojure.core/dedupe]
                                      ['(filter g) 'clojure.core/filter] ['cat 'clojure.core/cat]
                                      ['(take 3) 'clojure.core/take] ['(reduce +) 'clojure.core/reduce]]))))
    (is (= [:step] (map :kind (fold/segments [['(map f ys) 'clojure.core/map]])))
        "map with a collection is no transducer: a ->> step")
    (is (= [:step] (map :kind (fold/segments [['(map f) nil]]))) "a head that names no var here (a local): a step")
    (is (= [:step] (map :kind (fold/segments [['(map f) 'my.ns/map]]))) "another namespace's map: a step")))

(defn- registered
  "[k transducer] of every registered transducer, written with registered tasks (cat after a map to vectors)."
  []
  [[:map (map inc-x)] [:filter (filter odd-x?)] [:remove (remove odd-x?)] [:keep (keep tens)] [:mapcat (mapcat twice)]
   [:cat (comp (map twice) cat)] [:replace (replace {1 :one 41 :forty-one})] [:dedupe (dedupe)]
   [:take-while (take-while odd-x?)] [:halt-when (halt-when even-x?)] [:halt-when-retf (halt-when even-x? stop-at)]
   [:random-sample (random-sample 1.0)] [:drop-while (drop-while odd-x?)] [:take (take 17)] [:drop (drop 17)]
   [:take-nth (take-nth 3)] [:partition-all (partition-all 4)] [:partition-by (partition-by third)] [:distinct (distinct)]
   [:interpose (interpose 0)] [:map-indexed (map-indexed idx-pair)] [:keep-indexed (keep-indexed odd-idx)]])

(deftest every-registered-transducer-means-what-clojure-means-here
  (testing "this host's own sequence and into equal eve's stepper (fold/xf-seq) on every registered transducer — its
            completion adapter makes them Clojure's where the host's differ (basilisp's sequence, into, halt-when;
            jolt's replace)"
    (let [xs seam-input]
      (doseq [[k xf] (registered)]
        (is (= (vec (fold/xf-seq xf xs)) (vec (sequence xf xs))) (str k ": sequence"))
        (case k
          :halt-when (is (= 40 (into [] xf xs)) "into: the halting input, as Clojure's")
          :halt-when-retf (is (= [:stop 40] (into [] xf xs)) "into: retf of the completed result and the input")
          (is (= (vec (fold/xf-seq xf xs)) (into [] xf xs)) (str k ": into")))))))

(deftest =>>-runs-on-the-pool-with-the-bindings-of-the-call
  (let [p (t/fixed-pool 3 iso)
        me (h/self)
        r (t/with-pool p (binding [fold/*chunk* 5] (vec (t/=>> (meeting p 40) (map on-thread)))))
        on (set (map second r))]
    (is (= (vec (range 40)) (mapv first r)))
    (is (every? (fn [a] (or (= a me) (contains? (pool-threads p) a))) on) "each chunk ran on the pool (or here, helping)")
    (when (threads?)
      (is (some (fn [a] (contains? (pool-threads p) a)) on) "chunks ran on the pool's threads"))
    (testing "a plan for each separable run, read at the call"
      (let [plans (atom [])]
        (t/with-pool p (binding [fold/*chunk* 5 fold/*on-plan* (fn [x] (swap! plans conj x))]
                         (vec (t/=>> (vec (range 40)) (map inc-x) (dedupe) (filter odd-x?)))))
        (is (= [[:map] [:filter]] (map :run @plans)) "both separable runs were folded — none degraded to ->>")
        (is (every? (fn [x] (= 5 (:n x))) @plans))
        (is (every? (fn [x] (= (t/pool-name p) (:pool x))) @plans))))
    (testing "closures and forms: a core fn reaches the pool where its threads share this heap or eval forms"
      (let [r (t/attempt (fn [] (t/with-pool p (binding [fold/*chunk* 5] (vec (t/=>> (vec (range 20)) (map inc)))))))]
        (if (forms-cross?)
          (is (= [true (vec (range 1 21))] r))
          (is (= :eval (refused r)) "isolates that cannot eval refuse the form (a declared capability)"))))
    (close! p)))

;; ---------------------------------------------------------------------------
;; C1 — fold over a pool in a process: chunk, reduce, combine
;; ---------------------------------------------------------------------------

(deftest fold-over-a-pool-in-a-process
  (let [p (t/fixed-pool 3 iso)
        xs (vec (range 1000))]
    (testing "(fold n combinef reducef coll) equals the sequential reduce at every chunk size, in order"
      (doseq [n [1 7 100 999 1000 5000]]
        (is (= (reduce + xs) (t/with-pool p (fold/fold n plus plus xs))) (str "n " n))
        (is (= xs (t/with-pool p (fold/fold n cat-v conj-x xs))) (str "order preserved, n " n))))
    (testing "measured chunks (n nil), (fold reducef coll) and (fold combinef reducef coll)"
      (is (= (reduce + xs) (t/with-pool p (fold/fold plus xs))))
      (is (= xs (t/with-pool p (fold/fold cat-v conj-x xs)))))
    (testing "a lazy seq, a map (reduce-kv), an empty coll"
      (is (= (reduce + (range 500)) (t/with-pool p (fold/fold 16 plus plus (map identity (range 500))))))
      (let [m (zipmap (range 300) (range 300 600))]
        (is (= (reduce-kv kv-plus 0 m) (t/with-pool p (fold/fold 16 plus kv-plus m))))
        (is (= (reduce-kv kv-plus 0 m) (t/with-pool p (fold/fold plus kv-plus m)))))
      (is (= 0 (t/with-pool p (fold/fold plus []))))
      (is (= 0 (t/with-pool p (fold/fold 8 plus plus [])))))
    (testing "reduced ends its chunk, never the fold (clojure.core.reducers/fold's)"
      (is (= (reduce + (map (fn [c] (reduce sum-to-100 0 c)) (partition-all 10 xs)))
             (t/with-pool p (fold/fold 10 plus sum-to-100 xs)))))
    (testing "the chunks are descriptors on the pool: its threads ran them"
      (let [by (t/with-pool p (fold/fold 25 merge-tally tally (meeting p 200)))]
        (is (= 200 (reduce + (vals by))))
        (is (every? (fn [a] (or (= a (h/self)) (contains? (pool-threads p) a))) (keys by)))
        (when (threads?) (is (some (fn [a] (contains? (pool-threads p) a)) (keys by))))))
    (testing "a failed chunk fails the fold, its error out of band"
      (let [[ok? e] (t/attempt (fn [] (t/with-pool p (fold/fold 10 plus boom-at-77 xs))))]
        (is (false? ok?))
        (is (= {:x 77} (get-in e [:data :data])) "the task's own ex-data")))
    (testing "a closure: run where the threads share this heap; refused with the rule's message where they do not"
      (let [r (t/attempt (fn [] (t/with-pool p (fold/fold 10 + + xs))))]
        (if (= :local (form-of p))
          (is (= [true (reduce + xs)] r))
          (is (= :closure (refused r))))))
    (testing "the default :compute pool"
      (is (= (reduce + xs) (fold/fold 50 plus plus xs))))
    (close! p)))

(deftest fold-on-a-threadless-or-single-pool
  (let [p (t/single-pool iso)
        xs (vec (range 300))]
    (is (= (reduce + xs) (t/with-pool p (fold/fold 7 plus plus xs))))
    (is (= xs (t/with-pool p (fold/fold 7 cat-v conj-x xs))))
    (is (= (vec (->> xs (map inc-x) (filter odd-x?))) (t/with-pool p (binding [fold/*chunk* 7] (vec (t/=>> xs (map inc-x) (filter odd-x?)))))))
    (close! p)))

;; ---------------------------------------------------------------------------
;; C3 — chunks sized from the pool's measured swap cost, not a constant
;; ---------------------------------------------------------------------------

(defn- worth?
  "Does n satisfy chunk-size's rule for plan x: n elements' work at least work-to-swaps times their swaps' cost."
  [x n]
  (let [{:keys [swap-us swaps carry-ns carries elements us]} x]
    (>= (* n 1000 us) (* fold/work-to-swaps (+ (* 1000 swaps swap-us elements) (* (or carries 1) carry-ns n elements))))))

(defn- ns-per-spin
  "One spin step's cost here, ns: the fastest of three measurements (a JIT runs the loop faster once warm)."
  []
  (binding [*spins* 1000]
    (reduce min (repeatedly 3 (fn []
                                (loop [k 2]
                                  (let [t0 (proc/now-ms)
                                        _ (reduce spin-plus 0 (range k))
                                        dt (- (proc/now-ms) t0)]
                                    (if (or (>= dt 20) (>= k 1000000)) (max 1 (quot (* dt 1000) k)) (recur (* 2 k))))))))))

(defn- heavy-enough
  "[spins plan value]: (run spins on-plan) — a fold or an =>> of elements of spins steps, giving its plan to on-plan —
   from k spins, four times heavier each time, until its plan distributes it (n) by the plan's own measurements; at most
   six times (a calibration misjudges under a cold JIT or a loaded box)."
  [k run]
  (loop [k k i 0]
    (let [plans (atom [])
          v (run k (fn [x] (swap! plans conj x)))
          x (first @plans)]
      (if (or (:n x) (>= i 6)) [k x v] (recur (* 4 k) (inc i))))))

(defn- spins-worth-carrying
  "Spins that make an element worth its carrying on a cell whose descriptors carry an element for carry-ns (=>>'s
   twice: in and out): sixty times that, and at least 2 µs."
  [carry-ns]
  (max 1 (quot (max 2000 (* 60 carry-ns)) (ns-per-spin))))

(defn- elements-of-ms
  "How many elements of spins steps take about ms here (at least 16)."
  [spins ms]
  (min 200000 (max 16 (quot (* ms 1000000) (max 1 (* spins (ns-per-spin)))))))

(deftest chunks-are-sized-from-the-measured-swap-cost
  (testing "chunk-size: the fewest elements whose work is at least ten times the descriptor's swaps and carrying"
    (doseq [x [{:swap-us 25 :swaps 1 :carry-ns 0 :elements 1000 :us 1000}
               {:swap-us 300 :swaps 3 :carry-ns 0 :elements 200000 :us 5000}
               {:swap-us 300 :swaps 3 :carry-ns 300 :carries 1 :elements 20 :us 5000}
               {:swap-us 5500 :swaps 1 :carry-ns 2000 :carries 2 :elements 3 :us 15000}
               {:swap-us 1 :swaps 1 :carry-ns 0 :elements 1 :us 100000}
               {:swap-us 77 :swaps 1 :carry-ns 0 :elements 32767 :us 8000}]]
      (let [n (fold/chunk-size x)]
        (is (some? n) (str x))
        (is (worth? x n) (str "work >= 10 x swaps " x))
        (is (or (= 1 n) (not (worth? x (dec n)))) (str "and no larger " x)))))
  (testing "an element that costs no more than ten times its carrying: no chunk is worth its swaps"
    (is (nil? (fold/chunk-size {:swap-us 300 :swaps 3 :carry-ns 40000 :carries 1 :elements 4095 :us 8000})))
    (is (nil? (fold/chunk-size {:swap-us 300 :swaps 3 :carry-ns 100 :carries 2 :elements 5000 :us 5000}))))
  (let [p (t/fixed-pool 3 iso)]
    (testing "the pool's swap cost: measured on the pool, once"
      (let [c1 (fold/swap-cost p)
            c2 (fold/swap-cost p)]
        (is (pos? (:swap-us c1)))
        (is (= 1 (:swaps c1)) "a rung-0 cell's descriptor round trip, counted whole")
        (is (>= (:carry-ns c1) 0))
        (is (= c1 c2) "kept with the pool")))
    (testing "a measured fold's and =>>'s chunks are chunk-size of the measurements; heavier elements, smaller chunks"
      (let [k0 (spins-worth-carrying (* 2 (:carry-ns (fold/swap-cost p))))
            fold-of (fn [k on] (let [xs (vec (range (elements-of-ms k 60)))]
                                 [xs (t/with-pool p (binding [*spins* k fold/*on-plan* on] (fold/fold plus spin-plus xs)))]))
            [k pl [xs r1]] (heavy-enough k0 fold-of)
            plans (atom [])
            r2 (t/with-pool p (binding [*spins* (* 8 k) fold/*on-plan* (fn [x] (swap! plans conj x))]
                                (fold/fold plus spin-plus xs)))
            ph (first @plans)
            [kx px [ys r3]] (heavy-enough k (fn [k on] (let [ys (vec (range (elements-of-ms k 60)))]
                                                         [ys (t/with-pool p (binding [*spins* k fold/*on-plan* on]
                                                                              (vec (t/=>> ys (map spin) (filter odd-x?)))))])))]
        (is (= (binding [*spins* k] (reduce spin-plus 0 xs)) r1))
        (is (= (binding [*spins* (* 8 k)] (reduce spin-plus 0 xs)) r2) "the conveyed binding reached every chunk")
        (is (= (binding [*spins* kx] (vec (->> ys (map spin) (filter odd-x?)))) r3))
        (doseq [x [pl ph px]]
          (is (pos? (:elements (:probe x))) "the caller's probe measured the element cost")
          (is (= (:swap-us (fold/swap-cost p)) (:swap-us x)) "the pool's measured swap cost")
          (is (= (fold/chunk-size (merge x (:probe x))) (:n x)) (str "n is chunk-size of the measurements: " x))
          (is (some? (:n x)) (str "worth distributing: the element made heavier than its carrying " x))
          (is (false? (:given x))))
        (is (= [1 1 2] (map :carries [pl ph px])) "fold's elements travel once, =>>'s in and out")
        (when (and (:n pl) (:n ph))
          (if (> (:n pl) 1)
            (is (< (:n ph) (:n pl)) (str "heavier elements, smaller chunks: " (:n ph) " < " (:n pl)))
            (is (= 1 (:n ph)) "one element a chunk already")))))
    (testing "*chunk* and fold's n set the size instead"
      (let [plans (atom [])]
        (t/with-pool p (binding [fold/*on-plan* (fn [x] (swap! plans conj x))] (fold/fold 33 plus plus (vec (range 100)))))
        (is (= [33 true 4] ((juxt :n :given :chunks) (first @plans))))))
    (close! p)))
