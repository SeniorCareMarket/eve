(ns eve.view-test
  "eve.view on the JVM seats: a read's pin is released when the GC collects
   the read's last view (the value model's V9, EVE-FIX-PLAN X10) — by the
   JVM's Cleaner, or on jolt (no Cleaner) at eve's weak-reference sweep."
  (:require [clojure.test :refer [deftest is]]
            [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.view :as ev]))

(defn- view-pins
  "The domain's outstanding view pins (the counts under :eve.atom/views)."
  [a]
  (reduce + 0 (vals (get @(:pins (ea/-domain-state a)) :eve.atom/views))))

(defn- gc-until [pred]
  (loop [i 0]
    (System/gc)
    (Thread/sleep 50)
    ;; a host with no Cleaner (jolt) releases at eve's own sweep
    (ev/sweep!)
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
