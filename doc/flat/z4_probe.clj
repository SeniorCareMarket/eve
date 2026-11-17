;; Z4 diagnostic probe (not the bench): isolate the count itself.
;; The B2 bench's arms run sequentially while box load rose ~4 -> ~24, so
;; its walk-vs-written ordering is confounded by drift. This probe places
;; the messages graph fresh for every arm and times ONLY tally/hold-root!
;; -- walked vs written -- interleaved ABAB over 5 rounds, and checks the
;; written rows equal the walked rows after every arm.
(ns z4-probe
  (:require [eve.atom :as ea]
            [eve.tally :as tally]
            [eve.flat.corpora :as corpora]
            [eve.flat.tally-count-test :as zt]))

(def ^:private graph-of @#'zt/graph-of)
(def ^:private place-graph! @#'zt/place-graph!)
(def ^:private root-ptr @#'zt/root-ptr)
(def ^:private ds-of @#'zt/ds-of)
(def ^:private rows-by-source @#'zt/rows-by-source)

(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(defn- fresh-sio []
  (:sio (ea/-domain-state
         (ea/lookup-or-create-mmap-atom! (ea/atom-domain nil :capacities capacities)
                                         "z4p/x" nil))))

(defn- load1 []
  (try (first (clojure.string/split
               (String. (java.nio.file.Files/readAllBytes
                         (java.nio.file.Paths/get "/proc/loadavg" (make-array String 0)))
                        "UTF-8")
               #" "))
       (catch Throwable _ "?")))

(defn- time-hold [sio placed written?]
  (let [t0 (System/nanoTime)]
    (if written?
      (binding [tally/*count-at-place* true]
        (tally/register-placed! sio (:root placed) (:kinds placed) (:edges placed))
        (tally/hold-root! sio (:root placed)))
      (tally/hold-root! sio (:root placed)))
    (/ (- (System/nanoTime) t0) 1e6)))

(defn -main []
  (let [v (second (first (filter (fn [[k _]] (= k :messages-5k))
                                 (corpora/corpora :canonical))))
        src (ea/lookup-or-create-mmap-atom! (ea/atom-domain nil :capacities capacities)
                                            "z4p/src" nil)
        _ (reset! src v)
        src-sio (:sio (ds-of src))
        graph (graph-of src-sio (root-ptr src))
        nblocks (count (:blocks graph))
        results (atom {:walk [] :written []})
        first-rows (atom {})]
    (println "Z4 probe -- hold-root! only, walked vs written, interleaved | blocks" nblocks
             "| load1" (load1))
    ;; one warmup placement + hold per arm, untimed
    (doseq [w? [false true]]
      (let [sio (fresh-sio) placed (place-graph! src-sio graph sio)]
        (time-hold sio placed w?)))
    (System/gc)
    (dotimes [r 5]
      (doseq [w? (if (even? r) [false true] [true false])]
        (let [sio (fresh-sio)
              placed (place-graph! src-sio graph sio)
              before (tally/placed-write-count)
              ms (time-hold sio placed w?)
              arm (if w? :written :walk)
              rows (rows-by-source sio placed)]
          (when w?
            (assert (= (inc before) (tally/placed-write-count))
                    "the written path did not run"))
          (if-let [fr (get @first-rows arm)]
            (assert (= fr rows) (str "rows differ between rounds, arm " arm))
            (swap! first-rows assoc arm rows))
          (swap! results update arm conj ms)
          (println (format "  round %d %-7s %9.2f ms | load1 %s" r (name arm) ms (load1)))
          (flush)))
      (System/gc))
    (let [{:keys [walk written]} @results
          med (fn [xs] (nth (sort xs) 2))]
      (println "rows equal (written == walked):"
               (= (get @first-rows :written) (get @first-rows :walk)))
      (println (format "walk    min %.2f ms | median %.2f ms" (apply min walk) (med walk)))
      (println (format "written min %.2f ms | median %.2f ms" (apply min written) (med written))))
    (println "Z4 probe done"))
  (System/exit 0))

(-main)
