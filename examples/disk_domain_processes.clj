;; examples/disk_domain_processes.clj — several processes, of any hosts, writing one atom of one disk domain at once.
;;
;;   bb examples/disk_domain_processes.clj                 # two babashka processes and one JVM process
;;   bb examples/disk_domain_processes.clj bb bb glj jvm   # any list of bb, jvm, glj (glj: run `bb glj-eve` first)
;;
;; Each process runs examples/disk_domain.cljc's run! (200 swaps of {:n inc}) concurrently against a fresh domain in a
;; temporary directory; then this process (babashka, on bb.edn's :paths) reads the counter: it must be 200 × processes.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def swaps 200)
(def hosts (or (seq *command-line-args*) ["bb" "bb" "jvm"]))
(def tmp-dir (str (fs/create-temp-dir {:prefix "eve-disk-domain-"})))
(def base (str tmp-dir "/db"))

(defn classpath [host]
  (str/join ":" (map #(str (fs/absolutize %)) (cons "src" (get (edn/read-string (slurp "hosts.edn")) (keyword host))))))

(def form (format "(load-file \"examples/disk_domain.cljc\") (disk-domain/run! %s %d)" (pr-str base) swaps))

(defn command [host]
  (case host
    "bb"  ["bb" "-cp" (classpath "bb") "-e" form]
    "jvm" ["clojure" "-M" "-e" form]
    "glj" {:cmd ["target/glj-eve/glj-eve" "-e" (str "(require 'eve.host-completion) " form)]
           :env {"GLJ_CLASSPATH" (classpath "glj")}}))

(println "domain:" base "| processes:" (str/join " " hosts) "|" swaps "swaps each")
(let [t0 (System/currentTimeMillis)
      procs (doall (for [h hosts]
                     (let [c (command h)
                           opts {:out :string :err :string :extra-env (or (:env c) {})}]
                       [h (apply p/process opts (if (map? c) (:cmd c) c))])))]
  (doseq [[h pr] procs]
    (let [{:keys [exit out err]} @pr]
      (println (format "  %-4s exit %d  %s" h exit (str/trim (or (some #(when (str/starts-with? % "process done") %) (str/split-lines out)) ""))))
      (when-not (zero? exit)
        (println err)
        (throw (ex-info (str h " failed") {:exit exit})))))
  (println (format "  all processes ended in %.1f s" (/ (- (System/currentTimeMillis) t0) 1000.0))))

(load-file "examples/disk_domain.cljc")
(let [v (disk-domain/total base)
      want {:n (* swaps (count hosts))}]
  (println "final value:" (pr-str v) "expected:" (pr-str want))
  (fs/delete-tree tmp-dir)
  (if (= want v)
    (println "disk_domain_processes: ok")
    (do (println "disk_domain_processes: MISMATCH") (System/exit 1))))
