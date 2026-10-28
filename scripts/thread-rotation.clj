;; The multi-runtime rotation (the EVE-THREAD plan's S3 on lane ET-2's domain cell):
;;   bb scripts/thread-rotation.clj <host> <host> ... [--n N] [--c C]
;;
;; Run from eve/. A fresh disk domain; one member per host named (jvm bb nbb clje cljr lpy jolt glj; a host may be named
;; twice — its rids are host, host-2, ...), each its host's own runner, all started at the same instant: they attach to
;; one rendezvous atom's meta-atom and serve one domain pool, and the coordinator role passes along the turn order (the
;; order named here): each coordinator submits collatz lengths of 1..N in descriptors of C to the pool and waits on
;; them. The members' EDN lines are the receipts; this script checks them and prints one summary line.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn roots [host]
  (map (fn [r] (str (fs/absolutize r))) (concat ["src"] (get (edn/read-string (slurp "hosts.edn")) host) ["test"])))

(def lpy-cache (str (fs/create-temp-dir {:prefix "eve-rotation-lpycache-"})))

(defn cmd [host & args]
  (let [m "eve.thread-rotation"]
    (case host
      "jvm" (concat ["clojure" "-Sdeps" "{:aliases {:rv {:extra-paths [\"test\"]}}}" "-M:rv" "-m" m] args)
      "bb" (concat ["bb" "-cp" (str/join ":" (roots :bb)) "-m" m] args)
      "nbb" (concat ["nbb" "--classpath" (str/join ":" (roots :nbb)) "-m" m] args)
      "clje" (concat ["clojerl"] (mapcat (fn [r] ["-pa" r]) (roots :clje)) ["-m" m] args)
      "cljr" (concat ["env" (str "CLOJURE_LOAD_PATH=" (str/join ":" (roots :cljr))) "cljr" "-m" m] args)
      "lpy" (concat ["env" (str "PYTHONPYCACHEPREFIX=" lpy-cache) "basilisp" "run"]
                    (mapcat (fn [r] ["-p" r]) (reverse (roots :lpy))) ["-n" m] args)
      "jolt" (concat ["jolt" "-Scp" (str/join ":" (roots :jolt)) "-m" m] args)
      "glj" (concat ["env" (str "GLJ_CLASSPATH=" (str/join ":" (roots :glj))) (str (fs/absolutize "target/glj-eve/glj-eve"))
                     "-e" (str "(require 'eve.host-completion) (require '" m ") (apply " m "/-main *command-line-args*)")]
                    args))))

(defn lines [f]
  (keep (fn [l] (when (and (str/starts-with? l "{") (str/includes? l ":step "))
                  (let [m (edn/read-string {:default (fn [_ v] v)} l)] (when (:step m) m))))
        (str/split-lines (if (fs/exists? f) (slurp f) ""))))

(defn now [] (System/currentTimeMillis))

(let [{hosts false opts true} (group-by #(str/starts-with? % "--") *command-line-args*)
      args (vec *command-line-args*)
      opt (fn [k d] (if-let [i (first (keep-indexed (fn [i a] (when (= a k) i)) args))] (parse-long (nth args (inc i))) d))
      hosts (vec (remove (set (map str (keep (fn [k] (when-let [v (opt k nil)] v)) ["--n" "--c"]))) hosts))
      n (opt "--n" 2000) c (opt "--c" 50)
      _ (when (some #{"glj"} hosts) (p/shell "bash" "impls/glojure/build.sh" (str (fs/absolutize "target/glj-eve"))))
      rids (loop [seen {} [h & more] hosts out []]
             (if h
               (let [k (inc (get seen h 0))]
                 (recur (assoc seen h k) more (conj out (if (= 1 k) h (str h "-" k)))))
               out))
      order (str/join "," rids)
      dir (str (fs/create-temp-dir {:prefix "eve-rotation-"}))
      base (str dir "/d")
      t0 (now)
      procs (mapv (fn [h rid]
                    (let [log (str dir "/" rid ".log")]
                      [rid log (apply p/process {:out log :err (str log ".err")}
                                      (cmd h "member" base rid order (str n) (str c)))]))
                  hosts rids)
      _ (doseq [[_ _ pr] procs] (deref pr 1800000 nil))
      by (into {} (map (fn [[rid log _]] [rid (lines log)]) procs))
      step (fn [rid s] (first (filter #(= s (:step %)) (get by rid))))
      turns (sort-by :at (mapcat (fn [rid] (filter #(= :turn (:step %)) (get by rid))) rids))
      done (map #(step % :member-done) rids)
      ran (apply merge-with + (map :ran-by turns))
      checks {:every-member-done (every? some? done)
              :every-turn-ok (and (= (count rids) (count turns)) (every? :ok turns))
              :turn-order (= rids (mapv :coordinator turns))
              :one-registry (every? (fn [rid] (= (sort rids) (:runtimes (step rid :joined)))) rids)
              :every-member-ran-work (every? (fn [rid] (pos? (get ran rid 0))) rids)
              :value-same (every? :value-same done)}
      ok (every? true? (vals checks))]
  (println (pr-str {:hosts hosts :rids rids :dir dir :ok ok :checks checks :n n :c c
                    :errors (vec (keep (fn [rid] (step rid :error)) rids))
                    :turns (mapv (fn [t] (select-keys t [:coordinator :driving :wall-ms :ok :ran-by :attempts])) turns)
                    :ran-by ran :wall-ms (- (now) t0)}))
  (System/exit (if ok 0 1)))
