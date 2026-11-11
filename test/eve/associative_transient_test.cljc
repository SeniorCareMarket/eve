(ns eve.associative-transient-test
  (:refer-clojure :exclude [deftype])
  (:require #?(:cljs [cljs.test :refer-macros [deftest is]] :default [clojure.test :refer [deftest is]])
            [eve.deftype #?@(:cljs [:refer-macros [deftype]] :default [:refer [deftype]])]
            [eve.core :as ec]))
(declare ->DualCollection)

;; DataScript DB analogue: one type has both persistent and transient behavior.
(deftype DualCollection [m]
  clojure.lang.Counted
  clojure.lang.Seqable
  (seq [_] (seq m))
  clojure.lang.ILookup
  (valAt [_ k] (get m k)) (valAt [_ k nf] (get m k nf))
  clojure.lang.Associative
  (assoc [_ k v] (->DualCollection (assoc m k v)))
  (containsKey [_ k] (contains? m k)) (entryAt [_ k] (find m k))
  clojure.lang.IPersistentCollection
  (count [_] (count m)) (cons [_ entry] (->DualCollection (conj m entry)))
  (empty [_] (->DualCollection {})) (equiv [_ other] (= m (.-m other)))
  clojure.lang.IEditableCollection
  (asTransient [this] this)
  clojure.lang.ITransientCollection
  (conj [_ entry] (->DualCollection (conj m entry))) (persistent [this] this))
(deftest mixed-persistent-transient
  (let [d (->DualCollection {:a 1}) d2 (ec/assoc d :b 2)]
    (is (= 1 (ec/get d :a))) (is (= :missing (ec/get d :absent :missing)))
    (is (ec/contains? d :a)) (is (not (ec/contains? d :b)))
    (is (= 2 (ec/get d2 :b))) (is (= 1 (ec/count d)))
    (is (= 0 (ec/count (ec/empty d))))
    (is (= 3 (ec/get (ec/conj d [:c 3]) :c)))
    (is (= 4 (ec/get (ec/persistent! (ec/conj! (ec/transient d) [:d 4])) :d)))
    #?(:clje (do (is (= [:a 1] (clojerl.IAssociative/entry_at d :a)))
                 (is (nil? (clojerl.IAssociative/entry_at d :absent)))
                 (is (not (satisfies? eve.core/ITransientAssociative d)))))
    (is (= 1 (ec/get d :a)))))
