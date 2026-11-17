(ns flatx
  "flatx — eve flat chunks on the 1BRC result shape (407 string keys -> [min sum max count]), one probe for every
   host: race-nk's eve/flatx.clj (specs/fold-fix/race-nk/eve/flatx.clj) as a .cljc, run by bench/flat-io/run-bench
   on the JVM, Node and bb over whichever eve.flat-io root is on the classpath. Each row: the first run, then the best
   of 20. The rows and labels are flatx.clj's; count and = are timed rows here (flatx.clj printed them)."
  (:require [eve.flat.chunk :as fc]))

(defn- now [] #?(:clj (System/nanoTime) :cljs (js/Number (js/process.hrtime.bigint))))

(def ^:private host #?(:bb "bb" :clj "jvm" :cljs "node"))

(defn- tm [label f]
  (let [a (now) r (f) first-us (quot (- (now) a) 1000)
        best (loop [i 0 b #?(:clj Long/MAX_VALUE :cljs js/Infinity)]
               (if (< i 20) (let [t (now) _ (f) d (- (now) t)] (recur (inc i) (min b d))) b))]
    (println "X" host label "first-us" first-us "best-us" (quot best 1000))
    r))

(defn run []
  (let [ks (vec (for [i (range 407)] (str "Station-" i)))
        vs* (vec (for [i (range 407)] [(- i) (* 1000 i) (+ i 50) (+ i 7)]))
        m (tm "persistent-map-x407"
              (fn [] (loop [i 0 m (transient {})]
                       (if (< i 407) (recur (inc i) (assoc! m (nth ks i) (nth vs* i))) (persistent! m)))))
        f (tm "fc-pack" (fn [] (fc/pack m)))]
    (println "X" host "count" (count f) "get" (get f "Station-5") (get m "Station-5"))
    (tm "count-flat" (fn [] (count f)))
    (tm "seq-walk-flat" (fn [] (loop [s (seq f) c 0] (if s (recur (next s) (inc c)) c))))
    (tm "seq-walk-persistent" (fn [] (loop [s (seq m) c 0] (if s (recur (next s) (inc c)) c))))
    (tm "get-x407-flat" (fn [] (loop [i 0 a 0] (if (< i 407) (recur (inc i) (+ a (nth (get f (nth ks i)) 3))) a))))
    (tm "get-x407-persistent" (fn [] (loop [i 0 a 0] (if (< i 407) (recur (inc i) (+ a (nth (get m (nth ks i)) 3))) a))))
    (tm "format-flat" (fn [] (count (apply str (map (fn [[k v]] (str k "=" (nth v 0) "/" (nth v 1) "/" (nth v 2))) (sort-by key f))))))
    (tm "format-persistent" (fn [] (count (apply str (map (fn [[k v]] (str k "=" (nth v 0) "/" (nth v 1) "/" (nth v 2))) (sort-by key m))))))
    (tm "eq-flat-persistent" (fn [] (= f m)))
    (println "X" host "equal?" (= f m))
    nil))

(defn -main [& _] (run) #?(:clj (shutdown-agents)))
(defn main [& _] (run))
