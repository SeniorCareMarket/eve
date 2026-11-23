(ns eve.flat.z1-board
  "Lane Z1's board run for row B3 (IMPL-PLAN §3.1): detach vs @a on the
   four M6 corpora (eve.flat.corpora), both arms in one session, the
   minimum of N runs, appended to doc/flat/SPIKE-LOG.md in the
   scoreboard's format. The incumbent arm is @a — (deref holder), the
   scoreboard's own B3 baseline arm; the flat arm is the public verb
   (eve.flat.image/detach of the holder atom: the committed value
   copied under the pin into one image byte array). Gate: the arm at
   least 2x faster than @a on every corpus.

   MEASUREMENTS M6 recorded the pre-change @a baseline (3.3 / 19.1 /
   20.7 / 43.9 ms); this run re-measures @a in-session on the lane's
   tree — the deref path is untouched by the lane's diff.

   Run (load-gated, like every heavy run on this box):
     CP=$(CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig clojure -Spath -X:jvm-test)
     java -Xmx4g -cp \"$CP\" clojure.main -m eve.flat.z1-board -- --runs 5
   Options: --runs N --warmup N --tiny --label TEXT --log PATH."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [eve.atom :as ea]
            [eve.deftype-proto.data :as d]
            [eve.flat.corpora :as corpora]
            [eve.flat.image :as img])
  (:import [java.io File]
           [java.time ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

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

(defn- fresh-atom [name]
  (let [dom (ea/atom-domain nil :capacities capacities)]
    (ea/lookup-or-create-mmap-atom! dom name nil)))

(defn- git-short []
  (try
    (let [pb (ProcessBuilder. ["git" "rev-parse" "--short" "HEAD"])
          p (.start pb)
          out (slurp (.getInputStream p))]
      (.waitFor p)
      (str/trim out))
    #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ "unknown")))

(defn- load1 []
  (try (first (str/split (str/trim (slurp "/proc/loadavg")) #" "))
       #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ "?")))

(defn- measure-corpus [kind v {:keys [warmup runs]}]
  (let [holder (fresh-atom "z1b/hold")
        _ (swap! holder (fn [_] v))
        value-ok (= v @holder)
        deref-ms (bench-min warmup runs #(deref holder))
        ;; the flat arm is tried separately: an arm error must not
        ;; discard the today number measured above (the first venue
        ;; round's rows printed ERROR in the today column for exactly
        ;; that reason — @a itself had not errored).
        detach (try {:detach-ms (bench-min warmup runs #(img/detach holder))}
                    #_{:splint/disable [lint/catch-throwable]}   (catch Throwable e {:error (str e)}))
        image-ok (when-not (:error detach)
                   (= v (d/eve->clj (img/image-value (img/open-image (img/detach holder))))))]
    (merge {:kind kind :value-ok value-ok :deref-ms deref-ms :image-ok image-ok}
           detach
           (when (:detach-ms detach)
             {:ratio (/ deref-ms (max 0.01 (:detach-ms detach)))}))))

(defn run!
  [{:keys [corpora runs warmup log label only]
    :or {corpora :canonical runs 5 warmup 2 log "doc/flat/SPIKE-LOG.md"}}]
  (let [results (mapv (fn [[kind v]]
                        ;; progress + the finished row go to stdout as each
                        ;; corpus completes: a process death mid-run (the
                        ;; board3 leg left a 134-byte log) then still names
                        ;; the corpus it died on and keeps the rows done.
                        (binding [*out* *err*] (println "BOARD measuring" kind) (flush))
                        (System/gc)
                        (let [r (try (measure-corpus kind v {:warmup warmup :runs runs})
                                     #_{:splint/disable [lint/catch-throwable]}   (catch Throwable e {:kind kind :error (str e)}))]
                          (println "BOARD ROW" kind
                                   (if (:error r)
                                     (str "ERROR " (:error r))
                                     (format "deref %.2f ms, detach %.2f ms (%.2fx)"
                                             (:deref-ms r) (:detach-ms r) (:ratio r))))
                          (flush)
                          r))
                      (cond->> (corpora/corpora corpora)
                        only (filter (fn [[kind _]] (= kind only)))))
        ts (.format (ZonedDateTime/now (ZoneId/of "America/New_York"))
                    (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))
        lines (into [(str "## " ts " ET — " (or label "Z1 board: B3 detach vs @a")
                          " — commit " (git-short)
                          " - JVM " (System/getProperty "java.version")
                          " - min of " runs " runs - load1 " (load1))
                     ""
                     "| row | corpus | today | flat arm | gate |"
                     "|---|---|---|---|---|"]
                    (for [{:keys [kind deref-ms detach-ms ratio error]} results]
                      (if error
                        (str "| B3 | " (corpora/corpus-labels kind)
                             " | " (if deref-ms (format "%.2f ms" deref-ms) "ERROR")
                             " | ERROR: " error
                             " | arm at least 2x faster than @a on every corpus |")
                        (str "| B3 | " (corpora/corpus-labels kind)
                             " | " (format "%.2f ms" deref-ms)
                             " | " (format "%.2f ms (%.2fx)" detach-ms ratio)
                             " | arm at least 2x faster than @a on every corpus |"))))
        section (str (str/join "\n" lines) "\n\n")
        f (File. log)]
    (when-not (.exists f)
      (spit f "# Flat Eve — SPIKE-LOG\n\nThe scoreboard of IMPL-PLAN §3.1, appended by every run.\n\n"))
    (spit f section :append true)
    (println section)
    (doseq [{:keys [kind value-ok image-ok error]} results]
      (when error (println "BOARD ERROR" kind error))
      (when (false? value-ok) (println "BOARD MISMATCH: stored value differs for" kind))
      (when (false? image-ok) (println "BOARD MISMATCH: image value differs for" kind)))
    results))

(defn -main [& args]
  (let [opts (loop [args args opts {}]
               (if (empty? args)
                 opts
                 (let [[a & more] args]
                   (case a
                     "--tiny" (recur more (assoc opts :corpora :tiny))
                     "--runs" (recur (rest more) (assoc opts :runs (parse-long (first more))))
                     "--warmup" (recur (rest more) (assoc opts :warmup (parse-long (first more))))
                     "--label" (recur (rest more) (assoc opts :label (first more)))
                     "--only" (recur (rest more) (assoc opts :only (keyword (first more))))
                     "--log" (recur (rest more) (assoc opts :log (first more)))
                     (recur more opts)))))]
    (run! opts)
    (System/exit 0)))
