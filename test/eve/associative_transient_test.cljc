(ns eve.associative-transient-test
  (:refer-clojure :exclude [deftype])
  (:require #?(:cljs [cljs.test :refer-macros [deftest is testing use-fixtures]] :default [clojure.test :refer [deftest is testing use-fixtures]])
            [eve.deftype #?@(:cljs [:refer-macros [deftype eve-deftype]] :default [:refer [deftype eve-deftype]])]
            [eve.core :as ec]
            [eve.map :as emap]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))
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
    (testing "eve.core/assoc is variadic, as on the JVM/cljs surfaces"
      (is (= 2 (ec/get (ec/assoc d :b 2 :c 3) :b)))
      (is (= 3 (ec/get (ec/assoc d :b 2 :c 3) :c))))
    (testing "eve.core/dissoc is variadic, as on the JVM/cljs surfaces"
      (is (= {:b 2} (ec/dissoc {:a 1 :b 2 :c 3} :a :c)))
      (is (= {:a 1} (ec/dissoc {:a 1})))
      (is (= {:b 2} (ec/dissoc (emap/hash-map :a 1 :b 2 :c 3) :a :c))))
    (testing "eve.core/conj is variadic, as on the JVM/cljs surfaces"
      (is (= [1 2 3] (ec/conj [] 1 2 3)))
      (is (= 3 (ec/get (ec/conj d [:b 2] [:c 3]) :c))))
    (is (= 0 (ec/count (ec/empty d))))
    (is (= 3 (ec/get (ec/conj d [:c 3]) :c)))
    (is (= 4 (ec/get (ec/persistent! (ec/conj! (ec/transient d) [:d 4])) :d)))
    #?(:clje (do (is (= [:a 1] (clojerl.IAssociative/entry_at d :a)))
                 (is (nil? (clojerl.IAssociative/entry_at d :absent)))
                 (is (not (satisfies? eve.core/ITransientAssociative d)))))
    (is (= 1 (ec/get d :a)))))

;; The slab-backed twin of the same shape (eve.db.ds's DB is an
;; eve-deftype): the persistent associative surface must survive the
;; transient groups on the slab path too — on clojerl that means the
;; expansion emits clojerl.IAssociative, with no extend-type patch.
(declare ->SlabDual)
(eve-deftype SlabDual [m]
  clojure.lang.Seqable
  (seq [_] (ec/seq m))
  clojure.lang.ILookup
  (valAt [_ k] (ec/get m k)) (valAt [_ k nf] (ec/get m k nf))
  clojure.lang.Associative
  (assoc [_ k v] (->SlabDual (ec/assoc m k v)))
  (containsKey [_ k] (ec/contains? m k))
  (entryAt [_ k] (let [v (ec/get m k ::absent)]
                   (when (not (identical? ::absent v)) [k v])))
  clojure.lang.IPersistentCollection
  (count [_] (ec/count m)) (cons [_ entry] (->SlabDual (ec/conj m entry)))
  (empty [_] (->SlabDual (ec/empty m))) (equiv [_ other] (= m (.-m other)))
  clojure.lang.IEditableCollection
  (asTransient [this] this)
  clojure.lang.ITransientCollection
  (conj [_ entry] (->SlabDual (ec/conj m entry))) (persistent [this] this))

(defn- in-a-domain-of-its-own [f]
  ;; Collation fix (eve-final): the binding form is unconditional, as in the
  ;; law lane's e54e47c fixture fix — the old #?(:cljs (f)) no-op arm relied
  ;; on the process-heap fallback the construction law abolished, so on
  ;; ClojureScript the test body would throw :no-domain-context.
  (binding [d/*domain-sio* (alloc/heap-domain-sio! {:capacities {0 (* 32 1024 1024) 1 (* 16 1024 1024)
                                                                        2 (* 8 1024 1024) 3 (* 8 1024 1024)
                                                                        4 (* 8 1024 1024) 5 (* 8 1024 1024)}})]
    (f)))

(use-fixtures :each in-a-domain-of-its-own)

(deftest slab-mixed-persistent-transient
  (testing "an eve-deftype with DB's group set keeps its persistent associative surface"
    (let [d (->SlabDual (emap/hash-map :a 1))
          d2 (ec/assoc d :b 2)]
      (is (= 1 (ec/get d :a)))
      (is (= 2 (ec/get d2 :b)))
      (is (ec/contains? d :a))
      (is (= 1 (ec/count d)))
      #?(:clje (do (is (satisfies? clojerl.IAssociative d))
                   (is (= [:a 1] (clojerl.IAssociative/entry_at d :a)))
                   (is (= 2 (ec/get (assoc d :b 2) :b))))))))
