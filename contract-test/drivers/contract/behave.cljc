(ns contract.behave
  "Per-host behave probe for the external-library contract test.
   Loads the ported library (me.tonsky.persistent-sorted-set), builds a set,
   and prints one EDN row per aspect. The JVM run is the semantic reference;
   every value printed here is compared against it host by host."
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [eve.deftype-proto.data :as d]
   [me.tonsky.persistent-sorted-set :as pss]))

(defmacro attempt
  "Run body; on any throw, answer the string ':throws <err>'. The catch class
   is a reader conditional: basilisp rejects (catch Throwable ..) — there
   Throwable is a Var, not a class type."
  [& body]
  `(try ~@body
     (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t#
       (str :throws " " t#))))

(defn- row [aspect value]
  (println (pr-str {:aspect aspect :value value})))

(defn- scramble
  "Deterministic non-sorted insertion order (exercises splits/merges)."
  [xs]
  (sort-by (fn [x] (mod (* x 7919) 104729)) xs))

(defn read-fields-untyped
  "Field reads through an untyped function parameter — the shape the brief
   calls out (and the shape that breaks on the jank seat today, cf. S-RRB)."
  [x]
  [(.-shift x) (.-cnt x) (some? (.-root x))])

(defn -main [& _]
  (try
    (row :load :ok)
    (let [s0 (pss/sorted-set)
          s1 (reduce (fn [s x] (e/conj s x)) (pss/sorted-set) (scramble (range 0 200)))
          s2 (reduce (fn [s x] (e/conj s x)) (pss/sorted-set) (scramble (range 0 200)))]
      (row :construct-count (e/count s1))
      (row :empty-count (e/count s0))
      (row :seq-eq (= (vec (e/seq s1)) (vec (range 200))))
      (row :seq-first-last [(first (e/seq s1)) (last (e/seq s1))])
      (row :seq-sum (reduce + (e/seq s1)))
      (row :field-access-untyped (read-fields-untyped s1))
      (row :lookup [(e/get s1 42) (e/get s1 9999) (e/contains? s1 42) (e/contains? s1 9999)])
      (row :conj-count (e/count (e/conj s1 1000)))
      (row :disj [(e/count (e/disj s1 42)) (e/contains? (e/disj s1 42) 42)])
      (row :host-core [(attempt (clojure.core/count (clojure.core/conj s1 1000)))
                       (attempt (clojure.core/count s1))
                       (attempt (clojure.core/get s1 42))
                       (attempt (first (clojure.core/seq s1)))])
      (row :equiv-self (= s1 s1))
      (row :equiv-indep (= s1 s2))
      (row :equiv-host-set (= s1 (into #{} (range 200))))
      (row :equiv-host-set-rev (= (into #{} (range 200)) s1))
      (row :hash (attempt (hash s1)))
      (row :print-small (pr-str (pss/sorted-set 1 2 3)))
      (row :print-empty (pr-str s0))
      (row :plain [(d/eve->clj (pss/sorted-set 1 2 3)) (= (d/eve->clj s1) (into (sorted-set) (range 200)))])
      (row :slice (vec (e/seq (pss/slice s1 50 59))))
      (row :rseq-first-last [(first (e/rseq s1)) (last (e/rseq s1))])
      (row :seek (attempt (vec (e/seq (pss/seek (e/seq s1) 195)))))
      (row :vec-of-set (attempt (vec (pss/sorted-set 1 2 3))))
      (row :sorted-set-by (vec (e/seq (pss/sorted-set-by (fn [a b] (compare b a)) 3 1 2))))
      ;; single-process residence: the structure inside an in-memory Eve atom
      ;; (constructed inside the swap, per Eve's domain-construction contract)
      (let [a (atom/atom nil)]
        (swap! a (fn [_] (pss/sorted-set)))
        (swap! a (fn [s] (reduce (fn [s x] (e/conj s x)) s (range 0 50))))
        (row :heap-atom [(attempt (e/count @a)) (= @a (apply pss/sorted-set (range 50))) (= (apply pss/sorted-set (range 50)) @a) (= (vec (e/seq @a)) (vec (range 50))) (type @a)]))
      (row :done :ok))
    (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t
      (println (pr-str {:aspect :fatal :value (str t)}))
      (row :done :fatal))))
