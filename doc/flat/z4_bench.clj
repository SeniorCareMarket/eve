;; Lane Z4's board row B2 bench -- fork F5's numbers.
;;
;; For each corpus of MEASUREMENTS.md M6 (via eve.flat.corpora, the
;; scoreboard's own builders): an image of the value is read once from a
;; source domain (the tally-test harness's graph), then, both arms in one
;; session, the minimum of N runs of
;;
;;   placement alone     the graph placed into a fresh pre-sized domain
;;   placed swap!, walk  a swap! whose function places the graph; the
;;                       commit counts it by the walk (today's path)
;;   placed swap!, F5    the same swap! with *count-at-place* bound: the
;;                       function registers the placement's tables and
;;                       hold-root! writes the counts from them
;;
;; Domain and atom creation are measured separately and netted out, the
;; scoreboard's method. The gate (IMPL-PLAN SS3.1, row B2): a placed swap!
;; <= 2x placement alone. The walk arm's ratio is the probe's 2-31x figure
;; re-measured in this session; the F5 arm's ratio is what the operator
;; decides F5 on.
;;
;; Placement here is the test harness's (per-block alloc! + byte copy +
;; pointer pass), not lane Z3's run-claim zip: both counting arms share
;; it, so the ratios isolate the count -- which is F5's whole question.
;; Absolute placement is row B1's business (lanes Z2/Z3).
;;
;; Run (from the repo root, box load1 <= 8):
;;   CP=$(CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;        PATH=/home/hatch/.local/bin:$PATH clojure -Spath -X:jvm-test)
;;   java -Xmx4g -cp "$CP" clojure.main doc/flat/z4_bench.clj
(ns z4-bench
  (:require [clojure.string :as str]
            [eve.atom :as ea]
            [eve.tally :as tally]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.flat.corpora :as corpora]
            [eve.flat.tally-count-test :as zt]))

(defn- load1
  "The box's load1, read without slurp: slurp sizes its buffer by
   InputStream.available(), which throws EINVAL on /proc files on this
   box (measured 2026-11-12, the bench's first run)."
  []
  (try (first (str/split (String. (java.nio.file.Files/readAllBytes
                                   (java.nio.file.Paths/get "/proc/loadavg" (make-array String 0)))
                                  "UTF-8")
                         #" "))
       (catch Throwable _ "?")))

(def ^:private graph-of @#'zt/graph-of)
(def ^:private place-graph! @#'zt/place-graph!)
(def ^:private reopen @#'zt/reopen)
(def ^:private root-ptr @#'zt/root-ptr)
(def ^:private ds-of @#'zt/ds-of)

;; the scoreboard's capacities (test/eve/flat/scoreboard.clj)
(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(defn- bench-min [warmup runs f]
  (dotimes [_ warmup] (f))
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i runs)
      best
      (let [t0 (System/nanoTime)
            _ (f)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best ms))))))

(defn- fresh-domain [] (ea/atom-domain nil :capacities capacities))

(defn- fresh-atom [name]
  (ea/lookup-or-create-mmap-atom! (fresh-domain) name nil))

(defn- source-graph
  "The corpus value committed in a pre-sized source domain; its graph."
  [v]
  (let [a (fresh-atom "z4b/src")
        _ (reset! a v)
        sio (:sio (ds-of a))]
    [sio (graph-of sio (root-ptr a))]))

(defn -main [& args]
  (let [warmup 2 runs 5
        ;; Optional corpus filter, e.g. `messages-5k`: same arms, same
        ;; warmup/runs -- it exists because a whole-suite run died of
        ;; OutOfMemoryError in eve.region/make-heap-region at the second
        ;; corpus under this box's fleet memory pressure (measured
        ;; 2026-11-12, second successor): one JVM per corpus resets the
        ;; accumulated live set. No argument runs every corpus, as before.
        only (when (first args) (keyword (first args)))]
    (println "Z4 bench -- board row B2 (placed swap!: the count, walked vs written)")
    (println "java" (System/getProperty "java.version")
             "| min of" runs "runs after" warmup "warmup | load1" (load1)
             (when only (str "| corpus " only " only")))
    (doseq [[kind v] (corpora/corpora :canonical)
            :when (or (nil? only) (= kind only))]
      (System/gc)
      (let [[src-sio graph] (source-graph v)
            nblocks (count (:blocks graph))
            dom-ms (bench-min warmup runs #(fresh-atom "z4b/dom"))
            place-ms (bench-min warmup runs
                                (fn []
                                  (let [dom (fresh-domain)
                                        sio (:sio (ea/-domain-state
                                                   (ea/lookup-or-create-mmap-atom! dom "z4b/p" nil)))]
                                    (place-graph! src-sio graph sio))))
            walk-ms (bench-min warmup runs
                               (fn []
                                 (let [a (fresh-atom "z4b/walk")]
                                   (swap! a (fn [_]
                                              (let [sio d/*domain-sio*
                                                    placed (place-graph! src-sio graph sio)]
                                                (reopen sio (:root placed))))))))
            written-ms (bench-min warmup runs
                                  (fn []
                                    (let [a (fresh-atom "z4b/f5")]
                                      (binding [tally/*count-at-place* true]
                                        (swap! a (fn [_]
                                                   (let [sio d/*domain-sio*
                                                         placed (place-graph! src-sio graph sio)]
                                                     (tally/register-placed! sio (:root placed)
                                                                             (:kinds placed) (:edges placed))
                                                     (reopen sio (:root placed)))))))))
            net-place (- place-ms dom-ms)
            net-walk (- walk-ms dom-ms)
            net-written (- written-ms dom-ms)]
        (println (format "  %-28s %7d blocks | placement alone %8.2f ms | placed swap! walk %8.2f ms (%5.2fx) | placed swap! written %8.2f ms (%5.2fx) | gate <= 2x: %s"
                         (corpora/corpus-labels kind) nblocks
                         net-place net-walk (/ net-walk (max 0.001 net-place))
                         net-written (/ net-written (max 0.001 net-place))
                         (if (<= net-written (* 2 net-place)) "PASS" "MISS")))
        (flush)))
    (println "Z4 bench done"))
  (System/exit 0))

(apply -main *command-line-args*)
