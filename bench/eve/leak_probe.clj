;; leak_probe.clj — blocks in use in a disk domain (slab classes 0-5: header total - free, read from the files) after
;; 20/40/80/160 swaps, per workload; the first three one source for every eve tree since the import (eve.atom/
;; persistent-atom and the slab header only), so a tree can be measured against another (RELEASE-CHECKLIST 9.25,
;; 2026-10-21); the tree workloads after them need this tree's transients and retire walks (TREES=0 skips them):
;;   java -cp "$(clojure -Spath -M:jvm-test)" clojure.main bench/eve/leak_probe.clj
;; A count swings by what the epoch-gated flush has not yet freed; what a leak looks like is a count that only grows.
(require '[eve.atom :as ea])
(import '[java.io RandomAccessFile] '[java.nio ByteBuffer ByteOrder])
(defn hdr-used [path]
  (let [f (java.io.File. path)]
    (if-not (.exists f) 0
      (with-open [r (RandomAccessFile. f "r")]
        (let [b (byte-array 16) _ (.readFully r b)
              bb (doto (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN))]
          (- (.getInt bb 8) (.getInt bb 12)))))))
(defn used [base] (reduce + (for [c (range 6)] (hdr-used (str base ".slab" c)))))
;; init is a value, or a fn the first swap calls: an eve value built outside the atom's swap belongs to another
;; domain, which the atom refuses (build it inside swap!)
(defn run [label init step]
  (let [base (str "/tmp/eve-leak-" label "-" (System/nanoTime) "/db")
        _ (.mkdirs (.getParentFile (java.io.File. base)))
        a (ea/persistent-atom {:id (keyword "leak" label) :persistent base} {})]
    (swap! a (fn [_] (if (fn? init) (init) init)))
    (let [marks (loop [i 0 out []]
                  (if (> i 160) out
                      (let [out (if (#{20 40 80 160} i) (conj out [i (used base)]) out)]
                        (swap! a step)
                        (recur (inc i) out))))]
      (println label "blocks in use after N swaps:" marks)
      (flush))))
(def init (into {} (map (fn [k] [k 0]) (range 200))))
;; 50 assocs on the stored eve map in one swap: 49 intermediate versions a swap
(run "path-copies" init (fn [m] (reduce (fn [m k] (assoc m k (inc (get m k 0)))) m (range 50))))
;; one assoc a swap: no intermediates (the control)
(run "one-assoc" init (fn [m] (assoc m (rand-int 200) (rand-int 1000))))
;; host data rebuilt each swap: intermediates are host values
(run "host-data" init (fn [_] (into {} (map (fn [k] [k (rand-int 1000)]) (range 200)))))
(when-not (= "0" (System/getenv "TREES"))
  (require '[eve.core :as e] '[eve.deftype.int-map :as im] '[eve.sorted :as so])
  (let [kv (interleave (range 200) (range 200))
        e-assoc (resolve 'eve.core/assoc) e-get (resolve 'eve.core/get) e-conj (resolve 'eve.core/conj)
        e-disj (resolve 'eve.core/disj)
        e-transient (resolve 'eve.core/transient) e-assoc! (resolve 'eve.core/assoc!) e-persistent! (resolve 'eve.core/persistent!)
        int-map (resolve 'eve.deftype.int-map/int-map) sorted-set (resolve 'eve.sorted/sorted-set)]
    ;; the perf suite's build workloads: a 200-key int-map, a 200-key sorted set, built in a swap through the constructor
    (run "int-map-build" {} (fn [_] {:m (apply int-map kv)}))
    (run "sorted-set-build" {} (fn [_] {:s (apply sorted-set (range 200))}))
    ;; a transient begun from an empty map the swap made: that empty map's header, one block a build, stays
    (run "int-map-transient" {} (fn [_] {:m (e-persistent! (reduce (fn [t k] (e-assoc! t k k)) (e-transient (int-map)) (range 200)))}))
    ;; one assoc on a stored int-map, and one conj on a stored sorted set
    (run "int-map-assoc" (fn [] {:m (apply int-map kv)}) (fn [m] (e-assoc m :m (e-assoc (e-get m :m) (rand-int 200) (rand-int 9)))))
    (run "sorted-set-disj-conj" (fn [] {:s (apply sorted-set (range 200))})
         (fn [m] (let [k (rand-int 200)] (e-assoc m :s (e-conj (e-disj (e-get m :s) k) k)))))))
(shutdown-agents)
(System/exit 0)
