;; jlog_crash_check.clj — the kill -9 crash tests' verdict (eve-mmap-journal-plan §6.3)
;; over scripts/jlog-crash.sh's output: bb scripts/jlog_crash_check.clj <out>
;; Per iteration: every runtime's check ran to CHECKED and they agree; for each
;; writer, its own vector is exactly 0..n-1 with every acknowledged item in it
;; and nothing beyond what it tried; its shared count is between the swaps it
;; saw acknowledged and the ones it tried.
(require '[clojure.string :as str]
         '[babashka.fs :as fs])

(defn- lines [f] (str/split-lines (slurp (str f))))

(defn- writer-facts
  "{tag {:tried n :own-acked i :shared-acked k}} from the writer logs (a killed
   writer's last line may be cut: only whole lines count)."
  [d]
  (into {}
        (for [f (fs/glob d "w.*.log")
              :let [tag (second (re-find #"w\.(.+)\.log$" (str f)))
                    ls (lines f)
                    nums (fn [re] (keep #(some-> (re-matches re %) second parse-long) ls))]]
          [tag {:tried (count (nums (re-pattern (str "TRY " tag " (\\d+)"))))
                :own-acked (count (nums (re-pattern (str "ACK own " tag " (\\d+)"))))
                :shared-acked (count (nums (re-pattern (str "ACK shared " tag " (\\d+)"))))}])))

(defn- check-lines [f] (filter #(re-find #"^(OWN|SHARED|CHECKED)" %) (lines f)))

(defn- judge [d]
  (let [facts (writer-facts d)
        checks (into (sorted-map) (for [f (fs/glob d "check.*.txt")] [(str (fs/file-name f)) (check-lines f)]))
        c (first (vals checks))
        own (into {} (for [l c :let [[_ t n s] (re-matches #"OWN (\S+) (\d+) (\S+)" l)] :when t] [t [(parse-long n) s]]))
        shared (into {} (for [l c :let [[_ t n] (re-matches #"SHARED (\S+) (\d+)" l)] :when t] [t (parse-long n)]))
        problems (concat
                  (for [[f ls] checks :when (not (some #{"CHECKED"} ls))] (str f " did not finish (recovery threw?)"))
                  (when-not (apply = (vals checks)) ["the runtimes' checks disagree"])
                  (for [[tag {:keys [tried own-acked shared-acked]}] facts
                        :let [[n shape] (get own tag) s (get shared tag 0)]
                        p [(when (and (pos? own-acked) (nil? n)) (str tag ": acknowledged items but no vector"))
                           (when (and n (not= shape "exact")) (str tag ": vector not 0..n-1"))
                           (when (and n (< n own-acked)) (str tag ": " own-acked " acked, " n " present (LOST)"))
                           (when (and n (> n tried)) (str tag ": " n " present, " tried " tried"))
                           (when (< s shared-acked) (str tag ": shared " s " < " shared-acked " acked (LOST)"))
                           (when (> s tried) (str tag ": shared " s " > " tried " tried"))]
                        :when p]
                    p))]
    {:dir (str d) :kill (str/trim (slurp (str (fs/file d "kill.txt"))))
     :acked (reduce + (map :own-acked (vals facts))) :problems (vec problems)}))

(let [[out] *command-line-args*
      iters (sort-by str (filter #(fs/exists? (fs/file % "kill.txt")) (fs/glob out "*/*")))
      results (map judge iters)
      by-cfg (group-by #(second (re-find #"/([^/]+)/\d+$" (:dir %))) results)]
  (doseq [[cfg rs] (sort by-cfg)]
    (println (format "%-16s %3d iterations  %3d PASS  acked swaps %d" cfg (count rs)
                     (count (filter (comp empty? :problems) rs)) (reduce + (map :acked rs))))
    (doseq [r rs :when (seq (:problems r))] (println "   FAIL" (:dir r) (:kill r) (:problems r))))
  (println (if (every? (comp empty? :problems) results) "CRASH SUITE PASS" "CRASH SUITE FAIL")))
