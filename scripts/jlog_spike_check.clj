;; jlog_spike_check.clj — the jlog spike's exit criteria (plan §5) over a run
;; directory of scripts/jlog-spike.sh: bb scripts/jlog_spike_check.clj <out> <slots> [baseline-ns]
(require '[clojure.string :as str]
         '[babashka.fs :as fs])

(let [[out slots-s baseline-s] *command-line-args*
      slots (parse-long slots-s)
      baseline (some-> baseline-s not-empty parse-long)
      lines (fn [f] (str/split-lines (slurp (str f))))
      wlogs (sort (fs/glob out "w*.log"))
      acks (for [f wlogs, l (lines f), :when (str/starts-with? l "ACK ")]
             (let [[w c sq lb] (map parse-long (rest (str/split l #" ")))]
               {:w w :c c :seq sq :label lb}))
      stats (for [f wlogs, l (lines f), :when (str/starts-with? l "STATS ")]
              (let [p (str/split l #" ")]
                {:w (parse-long (nth p 1)) :rt (second (re-find #"w\d+\.(\w+)\.log" (str f)))
                 :appends (parse-long (nth p 3)) :waits (parse-long (nth p 5))
                 :p50 (parse-long (nth p 7)) :p90 (parse-long (nth p 9))}))
      solo (into (sorted-map)
                 (for [f (fs/glob out "solo.*.log"), l (lines f), :when (str/starts-with? l "STATS ")]
                   [(second (re-find #"solo\.(\w+)\.log" (str f))) (parse-long (nth (str/split l #" ") 7))]))
      jvm-p50 (get solo "jvm")
      ;; the scan's own lines only (a JVM's stderr carries JAVA_TOOL_OPTIONS notices)
      scans (into (sorted-map) (for [f (fs/glob out "scan.*.txt")]
                                 [(str (fs/file-name f))
                                  (str/join "\n" (filter #(re-find #"^(REC|VALID|RSTAR|RTABLE) " %) (lines f)))]))
      goldens (into (sorted-map) (for [f (fs/glob out "golden.*.txt")]
                                   [(str (fs/file-name f)) (str/join "\n" (filter #(str/starts-with? % "GOLDEN") (lines f)))]))
      scan (first (vals scans))
      rtable (some->> (str/split-lines scan) (filter #(str/starts-with? % "RTABLE ")) first
                      (#(str/split % #" ")) rest (mapv parse-long))
      max-ack (reduce (fn [m {:keys [w c]}] (update m w (fnil max 0) c)) {} acks)
      seqs (map :seq acks)
      killed (when (fs/exists? (fs/file out "killed.txt")) (str/trim (slurp (str (fs/file out "killed.txt")))))
      c1 (and (> (count scans) 1) (apply = (vals scans)) (str/includes? scan "RSTAR"))
      c2-misses (for [[w c] max-ack :let [r (get rtable w)] :when (or (nil? r) (< r c) (> r (inc c)))] [w c r])
      c2 (and (seq acks) (empty? c2-misses))
      c3 (= (count seqs) (count (distinct seqs)))
      total (count acks)
      waits (reduce + (map :waits stats))
      c4 (and (> total (* 10 slots)) (pos? waits))
      c5 (if (and baseline jvm-p50) (<= jvm-p50 (* 1.1 baseline)) :no-baseline)
      c6 (and (> (count goldens) 1) (apply = (vals goldens)))
      verdict (fn [b] (cond (true? b) "PASS" (= b :no-baseline) "n/a (no baseline)" :else "FAIL"))]
  (println "jlog spike" out)
  (println " writers" (count wlogs) "acks" total "killed" (or killed "none") "slots" slots)
  (doseq [s stats] (println "  " s))
  (println " 1 same valid set and R* from every runtime:" (verdict c1) (keys scans))
  (println " 2 every acked counter covered by R*:" (verdict c2) "max-ack" max-ack "R* table" rtable
           (if (seq c2-misses) (str "misses " (vec c2-misses)) ""))
  (println " 3 no seq handed out twice:" (verdict c3) (count seqs) "acks," (count (distinct seqs)) "distinct")
  (println " 4 laps and back-pressure:" (verdict c4) total "appends >" (* 10 slots) "; waits" waits)
  (println " 5 JVM solo p50 append <= 1.1 x pwrite+fdatasync:" (verdict c5) "solo p50 ns" solo "baseline" baseline)
  (println " 6 record bytes identical across hosts:" (verdict c6) (keys goldens))
  (println (if (and c1 c2 c3 c4 c6 (not (false? c5))) "SPIKE PASS" "SPIKE FAIL")))
