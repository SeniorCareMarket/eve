;; The command line that runs namespace m on a host (eve's runners' classpaths: hosts.edn): thread-ladder.clj's and
;; thread-rungs.clj's.
(defn roots [host]
  (map (fn [r] (str (fs/absolutize r))) (concat ["src"] (get (edn/read-string (slurp "hosts.edn")) host) ["test"])))

(def lpy-cache (str (fs/create-temp-dir {:prefix "eve-ladder-lpycache-"})))

(defn cmd [host m & args]
  (case host
    "jvm" (concat ["clojure" "-Sdeps" "{:aliases {:rv {:extra-paths [\"test\"]}}}" "-M:rv" "-m" m] args)
    "bb" (concat ["bb" "-cp" (str/join ":" (roots :bb)) "-m" m] args)
    "nbb" (concat ["nbb" "--classpath" (str/join ":" (roots :nbb)) "-m" m] args)
    "clje" (concat ["clojerl"] (mapcat (fn [r] ["-pa" r]) (roots :clje)) ["-m" m] args)
    "cljr" (concat ["env" (str "CLOJURE_LOAD_PATH=" (str/join ":" (roots :cljr))) "cljr" "-m" m] args)
    "lpy" (concat ["env" (str "PYTHONPYCACHEPREFIX=" lpy-cache) "basilisp" "run"]
                  (mapcat (fn [r] ["-p" r]) (reverse (roots :lpy))) ["-n" m] args)
    "jolt" (concat ["jolt" "-Scp" (str/join ":" (roots :jolt)) "-m" m] args)))

