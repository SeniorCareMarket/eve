(ns eve.view-test
  "eve.view on the JVM: a read's pin is released when the GC collects the
   read's last view (the value model's V9, EVE-FIX-PLAN X10)."
  (:require [clojure.test :refer [deftest is]]
            [eve.alpha :as e]
            [eve.atom :as ea]))

(defn- view-pins
  "The domain's outstanding view pins (pin keys that are not thread ids)."
  [a]
  (count (remove number? (keys @(:pins (ea/-domain-state a))))))

(defn- gc-until [pred]
  (loop [i 0]
    (System/gc)
    (Thread/sleep 50)
    (cond (pred) true
          (< i 100) (recur (inc i))
          :else false)))

(deftest a-read-ends-when-its-views-are-collected
  (let [a (e/atom {})]
    (swap! a (fn [_] {:m (into {} (map (fn [i] [i i])) (range 100))}))
    (dotimes [_ 200] (get-in @a [:m 7]))
    (is (gc-until #(zero? (view-pins a))) "a collected read still holds its pin")
    (let [held @a]
      (is (pos? (view-pins a)) "a reachable view holds its read's pin")
      (is (= 7 (get-in held [:m 7]))))))
