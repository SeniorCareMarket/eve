(ns eve.coll
  "cljs.core's helpers for collection types (several are private in
   cljs.core, and nbb has none of them), written once in eve.core's
   vocabulary, so on every host they reach EVE values: equiv-sequential,
   ci-reduce, compare-indexed, seq-reduce, pr-sequential-writer,
   vector-index-out-of-bounds."
  (:refer-clojure :exclude [count nth seq first next sequential? counted?
                            pr-sequential-writer])
  (:require [eve.core :refer [count nth seq first next sequential? counted? -write]]
            [eve.encoding :as enc]))

(defn equiv-sequential
  "Assumes x is sequential. Returns true if x equals y, otherwise false."
  [x y]
  (boolean
   (when (sequential? y)
     (if (and (counted? x) (counted? y)
              (not (== (count x) (count y))))
       false
       (loop [xs (seq x) ys (seq y)]
         (cond (nil? xs) (nil? ys)
               (nil? ys) false
               (= (first xs) (first ys)) (recur (next xs) (next ys))
               :else false))))))

(defn ci-reduce
  "Reduces a collection with count and nth, without a seq."
  ([cicoll f]
   (let [cnt (count cicoll)]
     (if (zero? cnt)
       (f)
       (loop [val (nth cicoll 0), n 1]
         (if (< n cnt)
           (let [nval (f val (nth cicoll n))]
             (if (reduced? nval)
               @nval
               (recur nval (inc n))))
           val)))))
  ([cicoll f val]
   (let [cnt (count cicoll)]
     (loop [val val, n 0]
       (if (< n cnt)
         (let [nval (f val (nth cicoll n))]
           (if (reduced? nval)
             @nval
             (recur nval (inc n))))
         val)))))

(defn compare-indexed
  "Compares indexed collections: by length, then element by element."
  ([xs ys]
   (let [xl (count xs)
         yl (count ys)]
     (cond
       (< xl yl) -1
       (> xl yl) 1
       (== xl 0) 0
       :else (compare-indexed xs ys xl 0))))
  ([xs ys len n]
   (let [d (compare (nth xs n) (nth ys n))]
     (if (and (zero? d) (< (+ n 1) len))
       (recur xs ys len (inc n))
       d))))

(defn vector-index-out-of-bounds [i cnt]
  (throw (enc/index-out-of-bounds (str "No item " i " in vector of length " cnt))))

(defn pr-sequential-writer
  "Writes coll's elements between begin and end, sep between them, each by
   (print-one x writer opts); *print-length* elides the rest."
  [writer print-one begin sep end opts coll]
  (-write writer begin)
  (loop [xs (seq coll) n *print-length* first? true]
    (when xs
      (if (and n (zero? n))
        (do (when-not first? (-write writer sep)) (-write writer "..."))
        (do (when-not first? (-write writer sep))
            (print-one (first xs) writer opts)
            (recur (next xs) (when n (dec n)) false)))))
  (-write writer end))

(defn seq-reduce
  "Reduces a seq element by element (a seq type's IReduce, without going back
   through reduce)."
  ([f coll]
   (if-let [s (seq coll)]
     (seq-reduce f (first s) (next s))
     (f)))
  ([f val coll]
   (loop [val val, coll (seq coll)]
     (if coll
       (let [nval (f val (first coll))]
         (if (reduced? nval)
           @nval
           (recur nval (next coll))))
       val))))
