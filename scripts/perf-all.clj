;; eve's performance suite on every host (test/eve/perf_suite.cljc), one host at a time so the numbers do not contend:
;; each host's runner prints one EDN line per workload; this collects them into doc/hosts/perf-results.edn and prints
;; a table of ns/op, workload by host.
;;
;;   bb scripts/perf-all.clj                 # every host
;;   bb scripts/perf-all.clj bb jolt glj     # the hosts named
;;
;; The extended runtimes are found on PATH or where eve's tool setup puts them (/opt/eve-tools; EVE_TOOLS overrides).
;; A host whose runtime is missing is reported as such, never skipped silently.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str])

(def tools (or (System/getenv "EVE_TOOLS") "/opt/eve-tools"))
(def path (str/join ":" [(str tools "/jolt/bin") (str tools "/chez/bin") (str tools "/clojerl/bin") (System/getenv "PATH")]))
(def hosts-edn (edn/read-string (slurp "hosts.edn")))

(defn roots [host & more]
  (map (fn [r] (str (fs/absolutize r))) (concat ["src"] (get hosts-edn host) more)))

(defn cp [host] (str/join ":" (roots host "test")))

(def hosts
  "host → [env command...] (commands run in order; the last one's EDN lines are the results)"
  {:jvm        [{} ["clojure" "-M:perf"]]
   :cljs       [{} ["npm" "run" "test:compile"] ["node" "target/eve-test/all.js" "perf"]]
   :browser    [{"EVE_BROWSER_PERF" "1"} ["bb" "test:browser"]]
   :bb         [{} ["bb" "-cp" (cp :bb) "-m" "eve.runner.bb-main" "perf"]]
   :bb-minimal [{} ["bb" "-cp" (cp :bb-minimal) "-m" "eve.runner.bb-main" "perf"]]
   :nbb        [{} ["nbb" "--classpath" (cp :nbb) "-m" "eve.runner.nbb-main" "perf"]]
   :cljr       [{"CLOJURE_LOAD_PATH" (cp :cljr)} ["cljr" "-m" "eve.runner.cljr-main" "perf"]]
   :lpy        [{"PYTHONPYCACHEPREFIX" (str (fs/absolutize "target/lpycache-perf"))}
                (concat ["basilisp" "run"] (mapcat (fn [r] ["-p" r]) (reverse (roots :lpy "test")))
                        ["-n" "eve.runner.lpy-main" "perf"])]
   :jolt       [{"JOLT_CHEZ" (str tools "/chez/bin/scheme")} ["jolt" "-Scp" (cp :jolt) "-m" "eve.runner.jolt-main" "perf"]]
   :clje       [{} (concat ["clojerl"] (mapcat (fn [r] ["-pa" r]) (roots :clje "test")) ["-m" "eve.runner.clje-main" "perf"])]
   :glj        [{"GLJ_CLASSPATH" (cp :glj)}
                ["bb" "glj-eve"]
                ["target/glj-eve/glj-eve" "-e" "(require 'eve.runner.glj-main) (eve.runner.glj-main/-main \"perf\")"]]})

(def order [:jvm :cljs :browser :bb :bb-minimal :nbb :cljr :lpy :jolt :clje :glj])

(defn- program
  "cmd with its program found on the extended path (babashka.process looks a program up on this process's PATH, not
   the :extra-env one)"
  [cmd]
  (let [[prog & args] cmd
        found (some (fn [d] (let [f (fs/file d prog)] (when (fs/executable? f) (str f)))) (str/split path #":"))]
    (cons (or found prog) args)))

(defn- perf-lines
  "The suite's lines: an EDN map with :perf (a host prints a map's keys in its own order: basilisp's begins :ops)."
  [out]
  (keep (fn [l] (when (str/starts-with? l "{")
                  (let [m (try (edn/read-string l) (catch Exception _ nil))]
                    (when (and (map? m) (:perf m)) m))))
        (str/split-lines out)))

(defn run-host [h]
  (let [[env & cmds] (get hosts h)
        t0 (System/currentTimeMillis)
        res (reduce (fn [_ cmd]
                      (let [r (try @(apply p/process {:out :string :err :string :dir "." :extra-env (merge {"PATH" path} env)}
                                                      (program cmd))
                                   (catch Exception e {:exit -1 :out "" :err (ex-message e)}))]
                        (if (zero? (:exit r)) r (reduced r))))
                    nil cmds)
        lines (vec (perf-lines (:out res)))]
    (println (str "== " (name h) ": " (count lines) " workloads, exit " (:exit res) ", "
                  (quot (- (System/currentTimeMillis) t0) 1000) " s"))
    (doseq [l lines] (println "  " (pr-str l)))
    (when (empty? lines)
      (println "   (no results)" (subs (str (:err res)) 0 (min 400 (count (str (:err res)))))))
    {:host h :exit (:exit res) :wall-s (quot (- (System/currentTimeMillis) t0) 1000) :lines lines}))

(defn table [results]
  (let [ws (distinct (mapcat (fn [r] (map :perf (:lines r))) results))
        hs (map :host results)
        cell (fn [r w] (let [l (first (filter #(= w (:perf %)) (:lines r)))]
                         (cond (nil? l) "—" (:error l) "n/a" :else (str (:ns-per-op l)))))]
    (str/join "\n"
              (concat [(str "| ns/op | " (str/join " | " (map name hs)) " |")
                       (str "|---|" (str/join "|" (repeat (count hs) "---:")) "|")]
                      (for [w ws] (str "| `" (name w) "` | " (str/join " | " (map #(cell % w) results)) " |"))))))

(let [named (map keyword *command-line-args*)
      hs (if (seq named) named order)
      results (mapv run-host hs)
      out "doc/hosts/perf-results.edn"
      prior (when (fs/exists? out) (edn/read-string (slurp out)))
      merged (merge (:hosts prior) (into {} (map (fn [r] [(:host r) (assoc r :at (str (java.time.Instant/now)))]) results)))]
  (fs/create-dirs "doc/hosts")
  (spit out (with-out-str (clojure.pprint/pprint {:suite "test/eve/perf_suite.cljc" :hosts merged})))
  (println)
  (println (table (map (fn [h] (get merged h)) (filter merged order))))
  (println)
  (println "written" out))
