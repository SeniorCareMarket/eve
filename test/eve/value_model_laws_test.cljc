(ns eve.value-model-laws-test
  "The value model's laws (docs/ds-in-eve/EVE-FIX-PLAN.md §0, §1, §5):
   outside an atom, data is flat, value-only, without identity; inside an
   atom (a swap! fn, a $ region) it is persistent; the atom confers
   identity. One deftest per row of the plan's §1, against eve.alpha only.
   V9: a read outside $ answers a view of the version it pinned (X10);
   its GC end is pinned by eve.view-test on the JVM."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.alpha :as e]))

(defprotocol IV (v [this]))

(e/deftype Leaf [^:int32 k note]
  IV (v [_] [:leaf k note]))

(e/deftype Node [slots]
  IV (v [_] (mapv (fn [x] (if (satisfies? IV x) (v x) x)) (e/array-seq slots))))

(e/deftype DB [schema eavt max-tx]
  IV (v [_] {:schema schema :eavt (v eavt) :max-tx max-tx}))

(defn- arr [& xs]
  (let [a (e/make-array (count xs))]
    (dotimes [i (count xs)] (e/aset a i (nth xs i)))
    a))

(defn- mk-db [tx]
  (->DB {:a {:db/unique :db.unique/identity}}
        (->Node (arr (->Leaf 1 "x") (->Leaf 2 "y") 3))
        tx))

(defn- db-val [tx]
  {:schema {:a {:db/unique :db.unique/identity}}
   :eavt [[:leaf 1 "x"] [:leaf 2 "y"] 3]
   :max-tx tx})

(defn- churn! [a n]
  (dotimes [i n] (swap! a (fn [m] (assoc m :db (mk-db (+ 100 i)))))))

(defn- err-type [f]
  (try (f) nil
       (catch #?(:cljs :default :lpy python/Exception :default Exception) ex
         (or (:type (ex-data ex)) :thrown))))

(defn- db-atom []
  (let [a (e/atom {})]
    (swap! a (fn [m] (assoc m :db (mk-db 1) :arr (arr 1 2 3))))
    a))

;; ---------------------------------------------------------------------------
;; V1 — every exit delivers flat data
;; ---------------------------------------------------------------------------

(deftest v1-deref-deftype
  (let [a (db-atom)
        d (:db @a)]
    (is (not (e/persistent? d)) "a deref'd deftype is flat")
    (is (= (db-val 1) (v d)))
    (churn! a 1000)
    (is (= (db-val 1) (v d)) "V8: still reads after 1,000 swaps")))

(deftest v1-deref-value-array
  (let [a (db-atom)]
    (is (nil? (err-type #(deref a))) "deref of a value holding an eve array")
    (is (= [1 2 3] (vec (e/array-seq (:arr @a)))))
    (is (not (e/persistent? (:arr @a))))))

(deftest v1-region-exit
  (let [a (db-atom)
        d (e/$ (:db @a))
        r (e/$ (:arr @a))]
    (is (not (e/persistent? d)) "$ hands out no live deftype")
    (is (not (e/persistent? r)) "$ hands out no live eve array")
    (churn! a 1000)
    (is (= (db-val 1) (v d)))
    (is (= [1 2 3] (vec (e/array-seq r))))))

(deftest v1-swap-return
  (let [a (db-atom)
        r (swap! a (fn [m] (assoc m :db (mk-db 2))))]
    (is (not (e/persistent? r)) "swap! returns flat data")
    (is (= (db-val 2) (v (:db r))))
    (is (= {:x 1} (reset! a {:x 1})))))

(deftest v1-swap-vals
  (let [a (db-atom)
        [o n] (swap-vals! a (fn [m] (assoc m :db (mk-db 3))))]
    (is (not (e/persistent? o)))
    (is (not (e/persistent? n)))
    (is (= (db-val 1) (v (:db o))))
    (is (= (db-val 3) (v (:db n))))))

(deftest v1-watch
  (let [a (db-atom)
        seen (atom nil)]
    (add-watch a ::w (fn [_ _ o n] (reset! seen [o n])))
    (swap! a (fn [m] (assoc m :db (mk-db 2))))
    (remove-watch a ::w)
    (let [[o n] @seen]
      (is (not (e/persistent? o)))
      (is (not (e/persistent? n)))
      (is (= (db-val 1) (v (:db o))))
      (is (= (db-val 2) (v (:db n)))))))

;; ---------------------------------------------------------------------------
;; V2 — one complete packed value, no in-atom wrapper surfaces
;; ---------------------------------------------------------------------------

(deftest v2-restored-deftype
  (let [a (db-atom)]
    (swap! a (fn [m] (assoc m :old (:db m) :db (mk-db 2))))
    (is (= (db-val 1) (v (:old @a))) "a deftype re-stored in swap! derefs as its type")
    (is (= (db-val 2) (v (:db @a))))))

;; ---------------------------------------------------------------------------
;; V3 — construction: flat outside, persistent inside
;; ---------------------------------------------------------------------------

(deftest v3-construction
  (let [l (->Leaf 1 "out")]
    (is (not (e/persistent? l)))
    (is (= [:leaf 1 "out"] (v l))))
  (let [a (e/atom nil)
        seen (volatile! nil)]
    (swap! a (fn [_] (let [l (->Leaf 2 "in")]
                       (vreset! seen [(e/persistent? l) (v l)])
                       l)))
    (is (= [true [:leaf 2 "in"]] @seen)))
  (testing "e/make-array outside every atom"
    (let [r (err-type #(arr 1 2))]
      (is (nil? r) "make-array outside an atom gives a flat array"))
    (when-not (err-type #(arr 1 2))
      (let [x (arr 1 2)]
        (is (not (e/persistent? x)))
        (is (= [1 2] (vec (e/array-seq x))))))))

;; ---------------------------------------------------------------------------
;; V4 — flat values stored in an atom answer as their type inside it
;; ---------------------------------------------------------------------------

(deftest v4-flat-in-atom
  (let [a (e/atom {})
        b (->Leaf 7 "flat")
        seen (volatile! nil)]
    (reset! a {:x {:y b}})
    (swap! a (fn [m] (vreset! seen [(e/persistent? m) (v (get-in m [:x :y]))]) m))
    (is (= [true [:leaf 7 "flat"]] @seen))
    (is (= [:leaf 7 "flat"] (v (get-in @a [:x :y]))))
    (is (= [:leaf 7 "flat"] (e/$ (v (get-in @a [:x :y])))))))

;; ---------------------------------------------------------------------------
;; V5 — eve->clj: eve->cljs's rule on every host, no per-type protocol
;; ---------------------------------------------------------------------------

(deftest v5-eve->clj
  (let [a (e/atom nil)
        seen (volatile! nil)]
    (swap! a (fn [_] {:m {:k [1 2 #{:s}]} :l (->Leaf 3 "c") :arr (arr 4 5)}))
    (swap! a (fn [m]
               (let [c (e/eve->clj m)]
                 (vreset! seen {:plain (:m c)
                                :map? (map? c)
                                :flat? (not (e/persistent? c))
                                :leaf (v (:l c))
                                :leaf-flat? (not (e/persistent? (:l c)))
                                :arr (vec (e/array-seq (:arr c)))}))
               m))
    (is (= {:plain {:k [1 2 #{:s}]} :map? true :flat? true
            :leaf [:leaf 3 "c"] :leaf-flat? true :arr [4 5]}
           @seen))
    (testing "outside, on flat data"
      (let [c (e/eve->clj @a)]
        (is (= {:k [1 2 #{:s}]} (:m c)))
        (is (= [:leaf 3 "c"] (v (:l c))))))))

;; ---------------------------------------------------------------------------
;; V6, V7 — no identity outside; CAS by value
;; ---------------------------------------------------------------------------

(deftest v6-no-identity
  (let [a (db-atom)]
    (is (= @a @a))
    (is (= (v (:db @a)) (v (:db @a))))))

(deftest v7-cas-by-value
  (let [a (db-atom)
        old @a]
    (is (true? (compare-and-set! a old {:z 1})) "CAS with the deref'd value succeeds")
    (is (false? (compare-and-set! a old {:z 2})) "a stale value fails")
    (is (= {:z 1} @a))))

;; ---------------------------------------------------------------------------
;; V10 — $ works with the whole system; X8 — same? inside it
;; ---------------------------------------------------------------------------

(deftest v10-region
  (let [a (db-atom)]
    (is (= (db-val 1) (e/$ (v (:db @a)))))
    (is (= (db-val 1) (e/$ (e/$ (v (:db @a))))) "nested $")
    (is (= [1 2 3] (e/$ (vec (e/array-seq (:arr @a))))))
    (is (true? (e/$ (e/same? (:db @a) (:db @a)))))
    (is (false? (e/$ (e/same? (:db @a) (:arr @a)))))
    (is (some? (err-type #(e/same? (:db @a) (:db @a)))) "same? outside an atom throws")))

;; ---------------------------------------------------------------------------
;; V9 — a read outside $ answers a view: lookups in place, its own version
;; ---------------------------------------------------------------------------

(deftest v9-read-answers-a-view
  (let [a (db-atom)
        root @a
        db (:db root)]
    (is (not (e/persistent? root)) "a view is not a persistent value")
    (is (= (db-val 1) (v db)) "a deftype's view answers its protocols")
    (churn! a 1000)
    (is (= (db-val 1) (v db)) "a view reads the version it pinned, after 1,000 swaps")
    (is (= (db-val 1) (v (:db (e/eve->clj root))))
        "eve->clj of a view is its version as host data")))

(deftest v9-view-flows-back-into-an-atom
  (let [a (db-atom)
        b (e/atom {})
        r @a]
    (churn! a 10)
    (swap! b assoc :copy (:arr r) :m (:db r))
    (churn! a 1000)
    (is (= [1 2 3] (vec (e/array-seq (:copy @b)))) "a stored view is its subtree's value")
    (is (= (db-val 1) (v (:m @b))))
    (swap! a (fn [m] (assoc m :back (:arr r))))
    (is (= [1 2 3] (vec (e/array-seq (:back @a)))) "a view stored back into its own atom")))

(deftest v10-a-view-ends-with-its-region
  (let [a (db-atom)
        leak (volatile! nil)]
    (e/$ (vreset! leak (swap! a (fn [m] (assoc m :x 1)))) nil)
    (is (= :eve/read-ended (err-type #(get @leak :db)))
        "a view escaped from $ is refused after $ exits, never read freed")))
