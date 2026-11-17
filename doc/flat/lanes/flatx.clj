;; flatx — eve flat chunks on the 1BRC result shape (407 string keys -> [min sum max count]), one probe for every host
;; (JVM: eve's deps.edn classpath; bb: hosts.edn :bb roots; VS: the same forms in flat1.vs). A measuring aid.
(ns flatx (:require [eve.flat.chunk :as fc]))
(defn now [] (System/nanoTime))
(defn tm [label f]
  (let [a (now) r (f) first-us (quot (- (now) a) 1000)
        best (loop [i 0 b Long/MAX_VALUE] (if (< i 20) (let [t (now) _ (f) d (- (now) t)] (recur (inc i) (min b d))) b))]
    (println "X" label "first-us" first-us "best-us" (quot best 1000)) r))
(def ks (vec (for [i (range 407)] (str "Station-" i))))
(def vs* (vec (for [i (range 407)] [(- i) (* 1000 i) (+ i 50) (+ i 7)])))
(def m (tm "persistent-map-x407" (fn [] (loop [i 0 m (transient {})] (if (< i 407) (recur (inc i) (assoc! m (nth ks i) (nth vs* i))) (persistent! m))))))
(def f (tm "fc-pack" (fn [] (fc/pack m))))
(println "X count" (count f) "get" (get f "Station-5") (get m "Station-5"))
(tm "seq-walk-flat" (fn [] (loop [s (seq f) c 0] (if s (recur (next s) (inc c)) c))))
(tm "seq-walk-persistent" (fn [] (loop [s (seq m) c 0] (if s (recur (next s) (inc c)) c))))
(tm "get-x407-flat" (fn [] (loop [i 0 a 0] (if (< i 407) (recur (inc i) (+ a (nth (get f (nth ks i)) 3))) a))))
(tm "get-x407-persistent" (fn [] (loop [i 0 a 0] (if (< i 407) (recur (inc i) (+ a (nth (get m (nth ks i)) 3))) a))))
(tm "format-flat" (fn [] (count (apply str (map (fn [[k v]] (str k "=" (nth v 0) "/" (nth v 1) "/" (nth v 2))) (sort-by key f))))))
(tm "format-persistent" (fn [] (count (apply str (map (fn [[k v]] (str k "=" (nth v 0) "/" (nth v 1) "/" (nth v 2))) (sort-by key m))))))
(println "X equal?" (= f m))
