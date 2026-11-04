(ns coop.mix
  "The capstone scenario: eve data structures built on eve data structures, mixed in one shared atom and updated
   under contention by every eve host at once — the JVM (threads in the orchestrator), Node (eve's compiled
   ClojureScript worker), nbb and bb (processes). One source for all four: orchestrator.clj, bb_worker.clj,
   nbb_worker.cljs and eve's Node worker (eve/test/eve/runner/mmap_worker_main.cljs, modes coop-mix-*); run.sh
   copies it to eve/test/coop/mix.cljc for the Node build.

   The value:
     :counter  ops so far
     :logs     {writer an RRB vector (eve.rrb-vector, an eve-deftype over eve arrays) of {:i i :kind k} eve maps,
                in that writer's order}
     :shared   an RRB vector of 0..63 that :rotate ops turn by k places — a catvec of two subvecs, so its tree is
                rebuilt by concatenation, not by assoc
     :members  a sorted set (eve.deftype.rb-tree, an eve-deftype red-black tree) of \"writer:i\" strings, added and
                removed
     :ledger   nested maps: ten accounts of 1000, :transfer ops moving up to 3 between two of them
     :tally    ops by kind

   Every op is one swap! that changes several of these at once. The invariants hold for any interleaving, and each
   host computes the snapshot from its own read, so the orchestrator checks the invariants and that every host
   read the same final value. The wasm participant implements eve's format for maps, sets, vectors and lists in
   ValueScript, not eve's eve-deftypes, so it is not a writer here."
  (:require [eve.core :as e]
            [eve.rrb-vector :as fv]
            [eve.deftype.rb-tree :as rb]))

(def kinds "An op's kind, by i mod 4." [:rotate :member+ :member- :transfer])

(defn- rotation "How far :rotate op i turns :shared: 1-63." [i] (inc (mod (* 7 i) 63)))

(defn- rotate [s k] (fv/catvec (fv/subvec s k) (fv/subvec s 0 k)))

(defn- member [w i] (str w ":" i))

(defn initial
  "The start (called inside swap!, so every structure is built in the atom's domain)."
  []
  {:counter 0
   :logs {}
   :shared (fv/vec (range 64))
   :members (rb/sorted-set)
   :ledger (into {} (map (fn [i] [(str "a" i) {:balance 1000}])) (range 10))
   :tally (zipmap kinds (repeat 0))})

(defn mix-op
  "Op i of writer w: :counter +1, {:i i :kind k} onto w's log, the kind's tally +1, then by kind — turn :shared
   (rotation i) places, add (member w i) to :members, remove (member w (- i 5)) (added by this writer's op i-5), or
   transfer up to 3 between two accounts."
  [m w i]
  (let [kind (nth kinds (mod i 4))
        m (-> m
              (update :counter inc)
              (update-in [:logs w] (fn [log] (e/conj (or log (fv/vector)) {:i i :kind kind})))
              (update-in [:tally kind] inc))]
    (case kind
      :rotate (update m :shared rotate (rotation i))
      :member+ (update m :members e/conj (member w i))
      :member- (if (>= i 5) (update m :members e/disj (member w (- i 5))) m)
      :transfer (let [fa (str "a" (mod (* 7 i) 10))
                      ta (str "a" (mod (inc (* 7 i)) 10))
                      amt (min 3 (get-in m [:ledger fa :balance]))]
                  (-> m
                      (update-in [:ledger fa :balance] - amt)
                      (update-in [:ledger ta :balance] + amt))))))

(defn snapshot
  "The invariants' inputs from a value, as plain data: what this host reads."
  [v]
  (let [shared (:shared v)
        at0 (e/nth shared 0)
        elems (vec (e/seq (:members v)))
        balances (map (fn [[_ a]] (:balance a)) (:ledger v))]
    {:counter (:counter v)
     :tally (into {} (:tally v))
     :logs (into {} (map (fn [[w log]]
                           (let [entries (vec (e/seq log))]
                             [w {:count (e/count log)
                                 :order (mapv :i entries)
                                 :kinds-ok? (every? (fn [x] (= (:kind x) (nth kinds (mod (:i x) 4)))) entries)}])))
                 (:logs v))
     :shared {:count (e/count shared)
              :at0 at0
              :rotation? (every? (fn [j] (= (e/nth shared j) (mod (+ j at0) 64))) (range (e/count shared)))}
     :members {:elems elems
               :sorted? (= elems (sort elems))}
     :ledger {:accounts (count (:ledger v))
              :sum (reduce + 0 balances)
              :min (reduce min 1000 balances)}}))

(defn- per-kind [n k] (quot (+ n (- 3 k)) 4))

(defn expected
  "The snapshot every interleaving of the writers' ops ends in, given the op counts {writer n}."
  [per-writer]
  (let [total (reduce + 0 (vals per-writer))]
    {:counter total
     :tally (into {} (map-indexed (fn [k kind] [kind (reduce + 0 (map #(per-kind % k) (vals per-writer)))]) kinds))
     :logs (into {} (map (fn [[w n]] [w {:count n :order (vec (range n)) :kinds-ok? true}])) per-writer)
     :shared {:count 64
              :at0 (mod (reduce + 0 (for [[_ n] per-writer i (range n) :when (zero? (mod i 4))] (rotation i))) 64)
              :rotation? true}
     :members {:elems (vec (sort (for [[w n] per-writer j (range n) :when (and (= 1 (mod j 4)) (>= j (- n 5)))]
                                   (member w j))))
               :sorted? true}
     :ledger {:accounts 10 :sum 10000}}))

(defn violations
  "Where snapshot s differs from (expected per-writer): [] when every invariant holds. The ledger's smallest
   balance is not fixed by the counts, only bounded below by 0."
  [s per-writer]
  (let [x (expected per-writer)]
    (cond-> (vec (for [k [:counter :tally :logs :shared :members]
                       :when (not= (get x k) (get s k))]
                   [k (get s k) :expected (get x k)]))
      (not= (:ledger x) (dissoc (:ledger s) :min)) (conj [:ledger (:ledger s) :expected (:ledger x)])
      (neg? (get-in s [:ledger :min] 0)) (conj [:ledger-negative (:ledger s)]))))
