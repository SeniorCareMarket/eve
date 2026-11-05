;; doc/jvm-heap-grade/bench/baseline.clj — STEP 1 baseline, JVM seat, one session.
;; Every arm (Eve and its stock control) runs in this one JVM, same bench shape, interleaved:
;; each round runs every arm once, rounds repeat; per arm we print each round's min-of-3-batch ns/op.
;; Run: java --add-opens java.base/java.lang=ALL-UNNAMED -cp <deps.edn :paths>:clojure jars clojure.main doc/jvm-heap-grade/bench/baseline.clj
(ns hg.baseline
  (:require [eve.alpha :as e]
            [eve.map :as em]
            [eve.vec :as ev]
            [eve.deftype-proto.alloc :as alloc]))

(defn ns-per-op [f n]
  (dotimes [_ n] (f))
  (apply min (for [_ (range 3)]
               (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (/ (double (- (System/nanoTime) t0)) n)))))
(defn fmt [ns] (cond (> ns 1e6) (format "%.2f ms" (/ ns 1e6)) (> ns 1e3) (format "%.2f µs" (/ ns 1e3)) :else (format "%.1f ns" ns)))

(defn ctx [] (alloc/make-jvm-heap-slab-ctx {}))
;; the assoc arm allocates path copies that nothing reclaims outside an atom: give it room
(def big-sio (alloc/make-jvm-heap-slab-ctx {:capacities (zipmap (range 6) (repeat (* 64 1024 1024)))}))
(def src100 (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 100))))
(def src1k (zipmap (range 1000) (range 1000)))
(def m100 (let [s (ctx)] (em/hash-map-from-header s (em/jvm-write-map! s src100))))
(def m1k (let [s (ctx)] (em/hash-map-from-header s (em/jvm-write-map! s src1k))))
(def m1k-big (em/hash-map-from-header big-sio (em/jvm-write-map! big-sio src1k)))
(def v1k (reduce conj (ev/empty-vec (ctx)) (range 1000)))
(def sv1k (vec (range 1000)))
(def a-scalar (e/atom ::scalar 0))
(def a-m100 (e/atom ::m100 src100))
(def s-scalar (atom 0))
(def s-m100 (atom src100))
(def ks (int-array (shuffle (range 1000))))
(def ki (volatile! 0))
(defn nk ^long [] (aget ^ints ks (vswap! ki (fn [^long x] (if (== x 999) 0 (inc x))))))

(def arms
  ;; [label n f]
  [["eve  map get, 100-map, direct (:k42)"   20000 #(get m100 :k42)]
   ["stock map get, 100-map (:k42)"        2000000 #(get src100 :k42)]
   ["eve  map get, 1k-map, direct (rand int key)" 20000 #(get m1k (nk))]
   ["stock map get, 1k-map (rand int key)" 2000000 #(get src1k (nk))]
   ["eve  vec nth, 1k-vec, direct"          20000 #(nth v1k (nk))]
   ["stock vec nth, 1k-vec"               2000000 #(nth sv1k (nk))]
   ["eve  (get @atom k), 100-map root"        300 #(get @a-m100 :k42)]
   ["eve  $ (get @atom k), 100-map root"     5000 #(e/$ (get @a-m100 :k42))]
   ["stock (get @atom k), 100-map root"   2000000 #(get @s-m100 :k42)]
   ["eve  deref scalar root"                 5000 #(deref a-scalar)]
   ["stock deref scalar"                  2000000 #(deref s-scalar)]
   ["eve  deref 100-map root"                 300 #(deref a-m100)]
   ["eve  swap! inc"                          500 #(swap! a-scalar inc)]
   ["stock swap! inc"                     2000000 #(swap! s-scalar inc)]
   ["eve  swap! assoc, 100-map root"          300 #(swap! a-m100 assoc :k42 (nk))]
   ["stock swap! assoc, 100-map root"      500000 #(swap! s-m100 assoc :k42 (nk))]
   ["eve  map assoc, 1k-map, direct (rand key)" 2000 #(assoc m1k-big (nk) 7)]
   ["stock map assoc, 1k-map (rand key)"   500000 #(assoc src1k (nk) 7)]])

(def results (atom {}))
(dotimes [round 3]
  (doseq [[label n f] arms]
    (swap! results update label (fnil conj []) (ns-per-op f n))))
(println "=== baseline: per-round min ns/op, 3 interleaved rounds ===")
(doseq [[label] arms]
  (println (format "%-46s %s" label (mapv fmt (get @results label)))))

;; the capacity wall: fresh default heap ctx per shape
(defn wall [label build]
  (let [t0 (System/nanoTime)
        r (try (build (alloc/make-jvm-heap-slab-ctx {})) (catch Throwable t [:FAIL (.getMessage t)]))]
    (println (format "%-46s %s  (%.0f ms)" label (pr-str r) (/ (- (System/nanoTime) t0) 1e6)))))
(println "=== capacity (default make-jvm-heap-slab-ctx: 6×1 MB bitmap classes + 64 KB coalescing class) ===")
(doseq [n [1000 5000 10000]]
  (wall (str "map, " n " int entries")
        (fn [s] (loop [m (em/empty-hash-map s) j 0] (if (== j n) [:ok (count m)] (recur (assoc m j j) (inc j)))))))
(doseq [n [1000 10000 30000 100000]]
  (wall (str "vec, " n " int elements")
        (fn [s] (loop [v (ev/empty-vec s) j 0] (if (== j n) [:ok (count v)] (recur (conj v j) (inc j)))))))
(println "stock 10k-entry map build:" (fmt (ns-per-op #(loop [m {} j 0] (if (== j 10000) m (recur (assoc m j j) (inc j)))) 20)))
