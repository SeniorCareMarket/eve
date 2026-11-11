(ns eve.bb-adapter-test
  "bb adapter overlays for #56: SciMap keeps IPersistentMap methods plus
   Iterable, and lookup-ns? carves out eve.db.ds.* so library keyword
   lookup is rewritten to eve.core/get."
  (:require [clojure.test :refer [deftest is]]
            [eve.deftype :as edt]
            [eve.host-completion]))

(edt/deftype LocalRec [m]
  clojure.lang.ILookup
  (valAt [_ k] (get m k))
  (valAt [_ k nf] (get m k nf))
  clojure.lang.Associative
  (containsKey [_ k] (contains? m k))
  (entryAt [_ k] (when (contains? m k) (clojure.lang.MapEntry/create k (get m k))))
  (assoc [_ k v] (LocalRec. (assoc m k v)))
  clojure.lang.Counted
  (count [_] (count m))
  clojure.lang.IPersistentCollection
  (cons [this o]
    (if (map-entry? o)
      (assoc this (key o) (val o))
      (reduce (fn [acc e] (assoc acc (key e) (val e))) this o)))
  (empty [_] (LocalRec. {}))
  (equiv [_ o] (= (seq m) (seq o)))
  clojure.lang.IPersistentMap
  (without [_ k] (LocalRec. (dissoc m k)))
  clojure.lang.Seqable
  (seq [_] (seq m))
  java.lang.Iterable
  (iterator [_]
    (let [s (atom (seq m))]
      (reify java.util.Iterator
        (hasNext [_] (boolean @s))
        (next [_] (let [e (first @s)] (swap! s next) e))))))

(deftest sci-map-keeps-ipersistentmap-methods-and-iterable
  (let [r (LocalRec. {:a 1 :b 2})
        r2 (assoc r :a 3)
        r3 (dissoc r :b)]
    (is (= 1 (get r :a)))
    (is (= 3 (get r2 :a)))
    (is (= 1 (get r3 :a)))
    (is (nil? (get r3 :b)))
    (is (= 2 (count r)))
    (is (integer? (hash r)))
    (is (= #{r} (hash-set r)))))

(deftest lookup-ns-carves-out-eve-db-ds
  ;; lookup-ns? lives only on the bb adapter (defn- in
  ;; adapters/completion/bb). The JVM suite loads
  ;; adapters/completion/clojure, which does not intern it:
  ;; requiring-resolve returns nil, and deref of nil was the NPE
  ;; that failed #460 (664 tests, 1 error). Deref the var when
  ;; present (bb); skip the predicate rows on any other host.
  (let [v (requiring-resolve 'eve.host-completion/lookup-ns?)]
    (if (nil? v)
      (is (nil? v)
          "bb-only predicate; this host does not intern lookup-ns?")
      (let [lookup (if (var? v) @v v)]
        (is (fn? lookup))
        (is (false? (binding [*ns* (create-ns 'eve.mem)] (lookup)))
            "eve.* implementation namespaces stay skipped")
        (is (false? (binding [*ns* (the-ns 'clojure.core)] (lookup)))
            "clojure.* stays skipped")
        (is (true? (binding [*ns* (create-ns 'eve.db.ds.db)] (lookup)))
            "eve.db.ds.db is carved out so (:e datom) can be rewritten")
        (is (true? (binding [*ns* (create-ns 'eve.db.ds.core)] (lookup)))
            "eve.db.ds.* children are carved out")
        (is (true? (binding [*ns* (create-ns 'user)] (lookup)))
            "plain user/library namespaces still present")))))

(edt/deftype LocalSeq [xs]
  clojure.lang.Seqable
  (seq [_] (seq xs)))

(deftest vec-consumes-eve-seqables
  (is (= [1 2 3] (vec (LocalSeq. [1 2 3]))))
  (is (= [] (vec (LocalSeq. []))))
  (let [v (with-meta [1 2 3] {:native true})]
    (if-let [original (ns-resolve 'eve.host-completion 'sci-vec)]
      (do (is (= (identical? v (@original v)) (identical? v (vec v))))
          (is (= (meta (@original v)) (meta (vec v)))))
      (is (= [1 2 3] (vec v))))))

;; Map-shaped JVM declarations do not have to spell out Iterable. In SCI
;; map mode the adapter must supply it for native hash/set consumers.
(edt/deftype MapWithoutIterator [m]
  clojure.lang.ILookup
  (valAt [_ k] (get m k))
  (valAt [_ k nf] (get m k nf))
  clojure.lang.Associative
  (containsKey [_ k] (contains? m k))
  (entryAt [_ k] (find m k))
  (assoc [_ k v] (MapWithoutIterator. (assoc m k v)))
  clojure.lang.Counted
  (count [_] (count m))
  clojure.lang.Seqable
  (seq [_] (seq m)))

(deftest map-without-explicit-iterator-can-be-hashed
  (when (System/getProperty "babashka.version")
    (let [x (MapWithoutIterator. {:a 1 :b 2})]
      (is (= (hash {:a 1 :b 2}) (hash x)))
      (is (= #{x} (hash-set x)))
      (is (= #{[:a 1] [:b 2]} (set x))))))

(edt/deftype LookupOnly [m]
  clojure.lang.ILookup
  (valAt [_ k] (get m k))
  (valAt [_ k nf] (get m k nf)))

(deftest juxt-normalizes-keyword-constituents
  (let [x (LookupOnly. {:a 1 :b false})]
    (is (= [1 false nil] ((juxt :a :b :missing) x)))
    (is (= [[1 false]] (mapv (juxt :a :b) [x])))))
