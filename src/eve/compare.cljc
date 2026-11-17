(ns eve.compare
  "Total ordering for portable data. Mixed types never use host Comparable.
   NaN sorts last among numbers. Opaque types may supply an order key."
  (:refer-clojure :exclude [compare])
  (:require [eve.type :as t]
            [eve.encoding :as enc]))

(defprotocol IOrderKey
  (-order-key [value] "A portable data key for an otherwise opaque value."))

(defn- rank [x]
  (cond (nil? x) 0 (boolean? x) 1 (number? x) 2
        (or (string? x) (char? x)) 3 (keyword? x) 4 (symbol? x) 5
        (uuid? x) 6 (inst? x) 7
        (sequential? x) 8 (map? x) 9 (set? x) 10
        (satisfies? IOrderKey x) 11 :else 12))

(declare compare)

(defn- sequence-compare [a b]
  (let [n (clojure.core/compare (count a) (count b))]
    (if (zero? n)
      (loop [a (seq a) b (seq b)]
        (if (nil? a) 0
          (let [n (compare (first a) (first b))]
            (if (zero? n) (recur (next a) (next b)) n))))
      n)))

(defn- named-compare [a b]
  (let [n (clojure.core/compare (namespace a) (namespace b))]
    (if (zero? n) (clojure.core/compare (name a) (name b)) n)))

(defn- uuid-key [x]
  ;; UUID's two 64-bit halves are SIGNED in java.util.UUID.compareTo.
  (let [s (str x)]
    [(if (neg? (clojure.core/compare (subs s 0 1) "8")) 1 0) (subs s 0 18)
     (if (neg? (clojure.core/compare (subs s 19 20) "8")) 1 0) (subs s 19)]))

(defn compare
  "Total data comparison, Clojure scalar/vector semantics where defined.
   Unknown host objects fall back to type/printed representation (a total
   preorder, NOT portable identity). Implement IOrderKey for a stable key."
  [a b]
  (let [ra (rank a) rb (rank b)]
    (if (not= ra rb) (clojure.core/compare ra rb)
      (case ra
        0 0
        1 (clojure.core/compare (if a 1 0) (if b 1 0))
        2 (let [an (not (= a a)) bn (not (= b b))]
            (cond (and an bn) 0 an 1 bn -1 :else (clojure.core/compare a b)))
        3 (clojure.core/compare (str a) (str b))
        4 (named-compare a b)
        5 (named-compare a b)
        6 (sequence-compare (uuid-key a) (uuid-key b))
        7 (clojure.core/compare (enc/date-ms a) (enc/date-ms b))
        8 (sequence-compare a b)
        9 (sequence-compare (sort compare (map vec a)) (sort compare (map vec b)))
        10 (sequence-compare (sort compare a) (sort compare b))
        11 (compare (-order-key a) (-order-key b))
        12 (sequence-compare [(str (or (t/type-token a) (type a))) (pr-str a)]
                               [(str (or (t/type-token b) (type b))) (pr-str b)])))))
