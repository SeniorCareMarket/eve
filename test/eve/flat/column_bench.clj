(ns eve.flat.column-bench
  "Lane C2's board row B7 (IMPL-PLAN §3.1): scan, filter, one cell and a
   row view over a batch of 50,000 records, in place, against the host's
   own vector of maps — both arms in one session, the minimum of N runs,
   appended to doc/flat/SPIKE-LOG.md.

   The corpus is the probe corpora's records (seed 7, 7 fields), the
   M4 corpus; M4's packed arms ran it in 64 KiB chunks with ~1,700 rows
   a batch, this bench runs one batch of all 50,000. That batch spans
   ~600 KB — past the 64 KiB a non-WIDE chunk's u16 offsets can name —
   so it is encoded as a WIDE batch (u32 offsets, the format's flag
   for spans past one chunk); in-chunk batches never widen, and the
   column segment code this bench measures (scan/filter/cell/row) is
   offset-width independent. Chunking itself is C1/C3's layer. Gates
   (§3.3 lane C2): scan <= 2 ns/row, filter <= 4 ns/row, one cell
   <= 2x the host map's cell in the same session, a row view <= 1 µs.

   Run (from the repo root):
     CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \\
       clojure -M -i test/eve/flat/column_bench.clj \\
       -e \"(eve.flat.column-bench/-main)\""
  (:require [clojure.string :as str]
            [eve.flat.column :as column]
            [eve.flat.column-scaffold :as scaffold])
  (:import [java.io File]
           [java.util Random]
           [java.time ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

(defn- bench-min
  "The minimum of `runs` measured runs of f, after `warmup` unmeasured
   ones. f's return value is returned with the time, so a caller can
   check the arm did the work: [value ms]."
  [warmup runs f]
  (dotimes [_ warmup] (f))
  (loop [i 0 best Double/MAX_VALUE v nil]
    (if (== i runs)
      [v best]
      (let [t0 (System/nanoTime)
            v (f)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best ms) v)))))

(def ^:private record-ks [:id :name :active :score :dept :city :ts])

(defn- records
  "The probe corpora's records (doc/flat/probes/corpora.clj, seed 7)."
  [n]
  (let [r (Random. 7)
        cities (mapv #(str "city-" %) (range 50))
        depts [:eng :ops :sales :legal :finance :design :support :research]]
    (mapv (fn [i]
            {:id i
             :name (str "user-" i)
             :active (even? (.nextInt r 10))
             :score (* 0.5 (.nextInt r 2000))
             :dept (nth depts (.nextInt r (count depts)))
             :city (nth cities (.nextInt r (count cities)))
             :ts (+ 1700000000000 (* 1000 (long i)))})
          (range n))))

(defn- git-short []
  (try
    (let [head (str/trim (slurp ".git/HEAD"))]
      (if (str/starts-with? head "ref:")
        (subs (str/trim (slurp (str ".git/" (str/trim (subs head 4))))) 0 7)
        (subs head 0 7)))
    (catch Throwable _ "unknown")))

(defn- load1 []
  (try (first (str/split (str/trim (slurp "/proc/loadavg")) #" "))
       (catch Throwable _ "?")))

(defn run!
  [{:keys [rows runs warmup log]
    :or {rows 50000 runs 5 warmup 2 log "doc/flat/SPIKE-LOG.md"}}]
  (let [rs (records rows)
        n (count rs)
        rnd (Random. 5)
        idx (int-array (repeatedly 20000 #(.nextInt rnd n)))
        idx2 (int-array (repeatedly 200000 #(.nextInt rnd n)))
        ;; host arms
        [h-sum h-scan] (bench-min warmup runs #(transduce (map :score) + 0.0 rs))
        [h-cnt h-filt] (bench-min warmup runs #(count (filter (fn [r] (= :eng (:dept r))) rs)))
        [_ h-cell] (bench-min warmup runs
                              #(loop [j 0 acc 0.0]
                                 (if (== j 200000)
                                   acc
                                   (recur (inc j) (+ acc (double (or (:ts (nth rs (aget idx2 j))) 0)))))))
        [_ h-row] (bench-min warmup runs
                             #(loop [j 0 acc 0]
                                (if (== j 20000)
                                  acc
                                  (recur (inc j) (+ acc (count (nth rs (aget idx j))))))))
        ;; flat arm: one batch of all rows
        symtab (scaffold/build-symtab rs)
        enc (column/encode-batch-rows rs {:ks record-ks :shape 0
                                          :sym-code (:code symtab)
                                          :nsym (:nsym symtab)
                                          :wide? true})
        ctx (scaffold/ctx (:bytes enc) {:symtab symtab :shapes {0 record-ks}
                                        :wide? true})
        bt (column/open-batch (:bytes enc) (:node-off enc) ctx)
        score-col (nth (:cols bt) 3)
        dept-col (nth (:cols bt) 4)
        [s-sum s-scan] (bench-min warmup runs #(column/scan-sum score-col))
        [s-cnt s-filt] (bench-min warmup runs #(column/count-eq dept-col :eng))
        [_ s-cell] (bench-min warmup runs
                              #(loop [j 0 acc 0.0]
                                 (if (== j 200000)
                                   acc
                                   (recur (inc j) (+ acc (double (or (column/batch-cell bt 6 (aget idx2 j)) 0)))))))
        [_ s-row] (bench-min warmup runs
                             #(loop [j 0 acc 0]
                                (if (== j 20000)
                                  acc
                                  (recur (inc j) (+ acc (count (column/batch-row bt (aget idx j))))))))
        per-row (fn [ms k] (/ (* 1e6 ms) k))
        ts (.format (ZonedDateTime/now (ZoneId/of "America/New_York"))
                    (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))
        lines [(str "## " ts " ET — C2 board row B7 (one batch of " n " records)"
                    " — commit " (git-short)
                    " - JVM " (System/getProperty "java.version")
                    " - min of " runs " runs - load1 " (load1))
               ""
               "| row | corpus | today (host) | flat arm | gate |"
               "|---|---|---|---|---|"
               (str "| B7 scan (sum :score) | records " n " | "
                    (format "%.2f ns/row" (per-row h-scan n)) " | "
                    (format "%.2f ns/row" (per-row s-scan n)) " | <= 2 ns/row |")
               (str "| B7 filter (count :dept = :eng) | records " n " | "
                    (format "%.2f ns/row" (per-row h-filt n)) " | "
                    (format "%.2f ns/row" (per-row s-filt n)) " | <= 4 ns/row |")
               (str "| B7 one cell (:ts, 200k random) | records " n " | "
                    (format "%.1f ns" (per-row h-cell 200000)) " | "
                    (format "%.1f ns" (per-row s-cell 200000)) " | <= 2x host |")
               (str "| B7 row view (20k random) | records " n " | "
                    (format "%.3f µs" (/ (per-row h-row 20000) 1000.0)) " | "
                    (format "%.3f µs" (/ (per-row s-row 20000) 1000.0)) " | <= 1 µs |")
               ""]
        section (str (str/join "\n" lines) "\n")]
    ;; the arms must agree before any number is quoted
    (assert (= h-sum s-sum) "scan sums differ")
    (assert (= h-cnt s-cnt) "filter counts differ")
    (assert (= (nth rs 12345) (column/batch-row bt 12345)) "a row differs")
    (println section)
    (let [f (File. log)]
      (when-not (.exists f)
        (spit f "# Flat Eve — SPIKE-LOG\n\nThe scoreboard of IMPL-PLAN §3.1, appended by every run.\n\n"))
      (spit f section :append true))
    {:host {:scan (per-row h-scan n) :filter (per-row h-filt n)
            :cell (per-row h-cell 200000) :row (/ (per-row h-row 20000) 1000.0)}
     :flat {:scan (per-row s-scan n) :filter (per-row s-filt n)
              :cell (per-row s-cell 200000) :row (/ (per-row s-row 20000) 1000.0)}}))

(defn -main [& args]
  (let [opts (loop [args args opts {}]
               (if (empty? args)
                 opts
                 (let [[a & more] args]
                   (case a
                     "--rows" (recur (rest more) (assoc opts :rows (parse-long (first more))))
                     "--runs" (recur (rest more) (assoc opts :runs (parse-long (first more))))
                     "--warmup" (recur (rest more) (assoc opts :warmup (parse-long (first more))))
                     (recur more opts)))))]
    (run! opts)
    (System/exit 0)))
