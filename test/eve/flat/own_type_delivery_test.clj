(ns eve.flat.own-type-delivery-test
  "Own-type identity across the atom boundary (design call, Nov 15 2026,
   option (b) on the 96ee re-pin): an Eve deftype owns its type identity
   through the type-id registry (eve.deftype.registry). A user deftype
   SET (set-shaped: the plain set branch of the chunk writer used to
   drop its type) and a user deftype ELEMENT held in its field, stored
   in a persistent atom, deref back as their own classes — fields and
   class hints intact — not as a plain flat set proxy or a flat sister.
   The eve.db.ds case behind it: DB -> BTSet (an IPersistentSet with a
   comparator field) -> Datom."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [swap!]]
            [eve.atom :as a]
            [eve.deftype :as edt]
            [eve.deftype.registry :as reg]
            [eve.flat.chunk :as chunk]
            [eve.flat.source :as source]
            [eve.flat.sister :as sister]))

(edt/deftype Elem [^:int32 k tag])

(edt/deftype OneSet [^Elem elem label]
  clojure.lang.IPersistentSet
  (disjoin [this _] this)
  (contains [_ x] (= x elem))
  (get [_ x] (when (= x elem) elem))
  clojure.lang.IPersistentCollection
  (count [_] 1)
  (cons [this _] this)
  (empty [this] this)
  (equiv [this o] (identical? this o))
  clojure.lang.Seqable
  (seq [_] (list elem)))

(edt/deftype Holder [s note])

(def ^:private set-key "eve.flat.own-type-delivery-test/OneSet")

(defn- fresh-atom []
  (let [base (str (System/getProperty "java.io.tmpdir")
                  "/eve-own-type-" (System/nanoTime))
        d (a/persistent-atom-domain base :journal? false)]
    [d (a/lookup-or-create-mmap-atom! d "t/own" nil)]))

(deftest registry-names-the-types
  (is (= OneSet (:host-type (reg/lookup-type set-key))))
  (is (some? (sister/descriptor set-key))))

(deftest deftype-set-and-element-deref-as-themselves
  ;; the eve.db.ds shape: a root deftype (DB) holding a deftype set
  ;; (BTSet) holding a deftype element (Datom)
  (let [[d x] (fresh-atom)]
    (try
      (swap! x (fn [_] (->Holder (->OneSet (->Elem 7 "seven") "the-set") "h")))
      (let [^Holder h @x
            ^OneSet s (.-s h)]
        (is (instance? Holder h) "the deftype root did not deref as itself")
        (is (instance? OneSet s) "the deftype set did not deref as itself")
        (is (set? s))
        (is (= "the-set" (.-label s)) "a field read through the class hint failed")
        (let [^Elem e (.-elem s)]
          (is (instance? Elem e) "the deftype element did not deref as itself")
          (is (= 7 (.-k e)))
          (is (= "seven" (.-tag e)))))
      (testing "the delivery owns its memory: it reads after the domain closes"
        (let [^Holder h @x]
          (a/close-atom-domain! d)
          (is (= "seven" (.-tag ^Elem (.-elem ^OneSet (.-s h)))))))
      (finally
        (try (a/close-atom-domain! d) (catch Throwable _ nil))))))

(deftest nested-deftype-set-keeps-its-type-key-when-packed
  (let [[d x] (fresh-atom)]
    (try
      (swap! x (fn [_] (->OneSet (->Elem 1 "one") "nested")))
      ;; inside the pin: pack the slab instance nested in a plain map
      (let [opened (a/read-pinned x (fn [v] (chunk/open (chunk/chunk-bytes (chunk/pack {:s v})))))
            s (:s opened)]
        (is (satisfies? source/ISister s)
            "a set-shaped deftype packed as a plain set (its type dropped)")
        (is (= set-key (source/-sister-type-key s))))
      (finally
        (a/close-atom-domain! d)))))

(deftest nested-deftypes-in-plain-collections-deref-as-themselves
  ;; {:db DB}: own types nested in a plain map / vector / set come back
  ;; as their own classes too, not only a root. (Not in a set: a set
  ;; element is keyed by its eve key bytes, and a deftype instance has
  ;; none — the store refuses it on the base too.)
  (let [[d x] (fresh-atom)]
    (try
      (swap! x (fn [_] {:db (->Holder (->OneSet (->Elem 3 "three") "s3") "h3")
                        :n 1
                        :more [(->Elem 4 "four") {:deep (list (->Elem 5 "five"))}]}))
      (let [m @x
            ^Holder h (:db m)]
        (is (map? m))
        (is (= 1 (:n m)))
        (is (instance? Holder h) "a deftype nested in a map did not deref as itself")
        (is (instance? OneSet (.-s h)))
        (is (= "three" (.-tag ^Elem (.-elem ^OneSet (.-s h)))))
        (let [[e4 {:keys [deep]}] (:more m)]
          (is (instance? Elem e4) "a deftype nested in a vector did not deref as itself")
          (is (= 4 (.-k ^Elem e4)))
          (is (every? #(instance? Elem %) deep) "a deftype nested in a list did not deref as itself")
          (is (= [5] (map #(.-k ^Elem %) deep)))))
      (testing "the nested delivery owns its memory: it reads after the domain closes"
        (let [m @x]
          (a/close-atom-domain! d)
          (is (= "h3" (.-note ^Holder (:db m))))))
      (finally
        (try (a/close-atom-domain! d) (catch Throwable _ nil))))))

(deftest plain-collection-without-own-types-still-delivers-flat
  (let [[d x] (fresh-atom)]
    (try
      (swap! x (fn [_] {:a 1 :b [1 2 3]}))
      (let [m @x]
        (is (satisfies? chunk/IFlatHandle m) "a plain map stopped delivering as a flat object")
        (is (= {:a 1 :b [1 2 3]} m)))
      (finally
        (a/close-atom-domain! d)))))
