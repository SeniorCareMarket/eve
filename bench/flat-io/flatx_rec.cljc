(ns flatx-rec
  "flatx with record values (flat lanes U4a, 2026-11-17): the 1BRC result shape — 407 string keys — with each station's
   [min sum max count] once as a vector (flatx.cljc's rows, the before) and once as a Stats eve-deftype of four fields
   (the after), in one process so a load swing lands on both. Run by bench/flat-io/run-bench <jvm|node|bb> rec <root>.

   A Stats built outside an Eve domain is its flat sister (eve-deftype's construction rule): the persistent map's values
   are sisters, each over its own one-record chunk; fc/pack writes the map with the type in its type table, and a value
   read back is the generated sister, whose field reads go to the chunk (eve.flat.source/-field through eve.flat-io).
   The field read on every row is the type's own accessor (IStats/smin — what an eve-deftype's fields are reached by,
   on every host), and (:min s) through the type's ILookup where the host's deftype carries ILookup (the JVM and
   ClojureScript; babashka's sci deftype takes ILookup on a full map type only, measured, so its row is the accessor's).
   Each row: the first run, then the best of 20."
  (:require [eve.flat.chunk :as fc]
            [eve.deftype :as edt]
            [eve.flat.sister]))

(defprotocol IStats
  (smin [s])
  (ssum [s])
  (smax [s])
  (scount [s]))

(edt/deftype Stats [mn sm mx ct]
  IStats
  (smin [_] mn)
  (ssum [_] sm)
  (smax [_] mx)
  (scount [_] ct)
  clojure.lang.ILookup
  (valAt [_ k] (case k :min mn :sum sm :max mx :count ct nil))
  (valAt [_ k nf] (case k :min mn :sum sm :max mx :count ct nf)))

(defn- now [] #?(:clj (System/nanoTime) :cljs (js/Number (js/process.hrtime.bigint))))

(def ^:private host #?(:bb "bb" :clj "jvm" :cljs "node"))

(defn- tm [label f]
  (let [a (now) r (f) first-us (quot (- (now) a) 1000)
        best (loop [i 0 b #?(:clj Long/MAX_VALUE :cljs js/Infinity)]
               (if (< i 20) (let [t (now) _ (f) d (- (now) t)] (recur (inc i) (min b d))) b))]
    (println "X" host label "first-us" first-us "best-us" (quot best 1000))
    r))

(defn- sum-over [f m] (reduce (fn [a v] (+ a (f v))) 0 (vals m)))

(defn run []
  (let [ks (vec (for [i (range 407)] (str "Station-" i)))
        rows (vec (for [i (range 407)] [(- i) (* 1000 i) (+ i 50) (+ i 7)]))
        ;; --- before: vector values (flatx.cljc's rows) ---
        mv (tm "vec/persistent-map-x407"
               (fn [] (loop [i 0 m (transient {})]
                        (if (< i 407) (recur (inc i) (assoc! m (nth ks i) (nth rows i))) (persistent! m)))))
        fv (tm "vec/fc-pack" (fn [] (fc/pack mv)))
        _ (tm "vec/get-x407" (fn [] (loop [i 0 a 0] (if (< i 407) (recur (inc i) (+ a (nth (get fv (nth ks i)) 3))) a))))
        _ (tm "vec/field-sum" (fn [] (sum-over #(nth % 0) fv)))
        _ (tm "vec/format" (fn [] (count (apply str (map (fn [[k v]] (str k "=" (nth v 0) "/" (nth v 1) "/" (nth v 2)))
                                                         (sort-by key fv))))))
        _ (tm "vec/eq-flat-persistent" (fn [] (= fv mv)))
        ;; --- after: record values (Stats sisters) ---
        mr (tm "rec/persistent-map-x407"
               (fn [] (loop [i 0 m (transient {})]
                        (if (< i 407)
                          (recur (inc i) (assoc! m (nth ks i) (let [[a b c d] (nth rows i)] (->Stats a b c d))))
                          (persistent! m)))))
        fr (tm "rec/fc-pack" (fn [] (fc/pack mr)))
        _ (tm "rec/get-x407" (fn [] (loop [i 0 a 0] (if (< i 407) (recur (inc i) (+ a (scount (get fr (nth ks i))))) a))))
        _ (tm "rec/field-sum" (fn [] (sum-over smin fr)))
        _ #?(:bb nil :default (tm "rec/field-sum-kw" (fn [] (sum-over :min fr))))
        _ (tm "rec/format" (fn [] (count (apply str (map (fn [[k s]] (str k "=" (smin s) "/" (ssum s) "/" (smax s)))
                                                         (sort-by key fr))))))
        _ (tm "rec/eq-flat-persistent" (fn [] (= fr mr)))
        ;; the same 407 Stats as a vector: a BATCH, one column a field, whose rows read as sisters over the
        ;; batch's columns — the field read at column[row] (FORMAT-V1 §6); the map above writes each value as its
        ;; own record node (a MAP of the type's shape), whose field read is the node's slot
        vr (vec (vals mr))
        br (tm "rec/batch-pack" (fn [] (fc/pack vr)))
        _ (tm "rec/batch-field-sum" (fn [] (reduce (fn [a s] (+ a (smin s))) 0 br)))
        s5 (get fr "Station-5")]
    (println "X" host "vec get" (get fv "Station-5") "equal?" (= fv mv) "field-sum" (sum-over #(nth % 0) fv)
             "format-len" (count (apply str (map (fn [[k v]] (str k "=" (nth v 0) "/" (nth v 1) "/" (nth v 2))) (sort-by key fv)))))
    (println "X" host "rec get" [(smin s5) (ssum s5) (smax s5) (scount s5)]
             "type" (pr-str (type s5)) "sister?" (satisfies? eve.flat.source/ISister s5)
             "equal?" (= fr mr) "field-sum" (sum-over smin fr)
             #?@(:bb [] :default ["field-sum-kw" (sum-over :min fr)])
             "format-len" (count (apply str (map (fn [[k s]] (str k "=" (smin s) "/" (ssum s) "/" (smax s))) (sort-by key fr))))
             "chunk-bytes vec/rec" (alength (fc/chunk-bytes fv)) (alength (fc/chunk-bytes fr)))
    nil))

(defn -main [& _] (run) #?(:clj (shutdown-agents)))
(defn main [& _] (run))
