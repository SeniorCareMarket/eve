;; eve-swarm, the aegis chat room as a swarm of eve's dialects and the browser (capstone/eve-swarm/README.md): one
;; seat — a member of the room on one dialect — started on that dialect's classpath (hosts.edn) running
;; eve.aegis.chat.seat, which reads one JSON command a line on stdin and answers one `SEAT {…}` line on stdout. Run from
;; eve/:
;;
;;   bb eve-swarm seat --host bb|jvm|nbb|sci|cljs|cherry|lpy|glj|clje|cljr|jolt|lg|cljd    this process becomes the seat (its stdin and stdout;
;;                                                                  scittle is a page: swarm.py's PageSeat)
;;   bb eve-swarm hosts                                       the dialects, and which this machine runs
;;
;; The swarm itself (the room, the browser, the scenario, the checks) is atom.broker's SWARM row,
;; test/aegis/e2e_rows/swarm.py, which starts one seat per dialect through this launcher.
(ns eve-swarm.launcher
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def eve-root (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".."))))

(defn roots "eve's src and the host's adapter roots (hosts.edn), absolute" [host]
  (mapv (fn [r] (str (fs/path eve-root r))) (cons "src" (get (edn/read-string (slurp (str (fs/path eve-root "hosts.edn")))) host))))

(defn- cp [host] (str/join ":" (roots host)))

(def ^:private LOCALE
  "a UTF-8 locale where the environment names none (the JVM then reads and prints non-ASCII text as ?)"
  (if (or (System/getenv "LANG") (System/getenv "LC_ALL")) {} {"LANG" "C.UTF-8"}))

(def ^:private SEAT "eve.aegis.chat.seat")

(def hosts
  "each dialect: its binary (to check) and how it runs the seat's -main {:cmd :env :dir}"
  {:bb {:what "babashka" :bin "bb"
        :run (fn [] {:cmd ["bb" "-cp" (cp :bb) "-m" SEAT]})}
   :jvm {:what "Clojure on the JVM" :bin "clojure"
         :run (fn [] {:cmd ["clojure" "-M" "-m" SEAT] :dir eve-root})}
   :nbb {:what "nbb (ClojureScript on Node)" :bin "nbb"
         :run (fn [] {:cmd ["nbb" "--classpath" (cp :nbb) "-m" SEAT]})}
   :sci {:what "SCI on the JVM (org.babashka/sci, a library)" :bin "clojure"
         ;; capstone/eve-swarm/sci: one SCI context reads eve's src and the babashka host's roots from source
         :run (fn [] {:cmd ["clojure" "-M:sci-seat" "-m" "eve-swarm.sci-seat"] :dir eve-root})}
   :scittle {:what "Scittle (SCI in a browser page's worker)" :bin (or (System/getenv "EVE_CHROME") "/opt/pw-browsers/chromium")
             ;; a page, not a process: test/aegis/e2e_rows/swarm.py's PageSeat opens it in headless Chromium and loads
             ;; the seat from source (capstone/eve-swarm/scittle, hosts.edn :scittle); no command line runs it
             :page true}
   :cljs {:what "ClojureScript, compiled (Node)" :bin (str (fs/path eve-root "target" "eve-swarm" "seat-node.js"))
          ;; built by shadow-cljs (shadow-cljs.edn :eve-swarm-node): clojure -M:node-test -m shadow.cljs.devtools.cli
          ;; release eve-swarm-node
          :run (fn [] {:cmd ["node" (str (fs/path eve-root "target" "eve-swarm" "seat-node.js"))]})}
   :cherry {:what "cherry (ClojureScript to ES modules, Node)" :bin (str (fs/path eve-root "target" "eve-swarm" "cherry" "seat.mjs"))
            ;; built by bb eve-swarm:cherry (capstone/eve-swarm/cherry.clj)
            :run (fn [] {:cmd ["node" (str (fs/path eve-root "target" "eve-swarm" "cherry" "seat.mjs"))]})}
   :lpy {:what "basilisp (CPython)" :bin "basilisp"
         ;; unbuffered, or the answers wait in Python's pipe buffer
         ;; -c, not -n: `basilisp run -n` loads a namespace and calls nothing
         :run (fn [] {:cmd (concat ["basilisp" "run"] (mapcat (fn [r] ["-p" r]) (reverse (roots :lpy)))
                                   ["-c" (str "(require '" SEAT ") (" SEAT "/-main)")])
                      :env {"PYTHONPYCACHEPREFIX" (str (fs/path eve-root "target" "lpycache-seat")) "PYTHONUNBUFFERED" "1"}})}
   :glj {:what "glojure (Go; glj-eve, built by bb glj-eve)" :bin (str (fs/path eve-root "target" "glj-eve" "glj-eve"))
         :run (fn [] {:cmd [(str (fs/path eve-root "target" "glj-eve" "glj-eve"))
                            "-e" (str "(require '" SEAT ") (" SEAT "/-main)")]
                      :env {"GLJ_CLASSPATH" (cp :glj)}})}
   :clje {:what "clojerl (the BEAM)" :bin "clojerl"
          :run (fn [] {:cmd (concat ["clojerl"] (mapcat (fn [r] ["-pa" r]) (roots :clje)) ["-m" SEAT])})}
   :jolt {:what "jolt (Chez Scheme)" :bin (or (some-> (fs/which "jolt") str) "/opt/eve-tools/jolt/bin/jolt")
          ;; jolt on PATH, or where the tools box installs it with its Chez (JOLT_CHEZ, as bb test:jolt)
          :run (fn [] {:cmd [(or (some-> (fs/which "jolt") str) "/opt/eve-tools/jolt/bin/jolt") "-Scp" (cp :jolt) "-m" SEAT]
                       :env (if (System/getenv "JOLT_CHEZ") {}
                                (let [chez "/opt/eve-tools/chez/bin/scheme"] (if (fs/exists? chez) {"JOLT_CHEZ" chez} {})))})}
   :lg {:what "let-go (Go; lg-eve, built by bb lg-eve)"
        :bin (str (fs/path eve-root "target" "lg-eve" "lg-eve"))
        ;; its completion first: let-go's core is completed by re-rooting vars, which reaches only what compiles after
        :run (fn [] {:cmd [(str (fs/path eve-root "target" "lg-eve" "lg-eve")) "-source-paths" (cp :lg)
                           "-e" (str "(require 'eve.host-completion) (require '" SEAT ") (" SEAT "/-main)")]})}
   :cljr {:what "ClojureCLR (.NET)" :bin "cljr"
          :run (fn [] {:cmd ["cljr" "-m" SEAT]
                       :env {"CLOJURE_LOAD_PATH" (cp :cljr) "DOTNET_ROOT" (or (System/getenv "DOTNET_ROOT") "/opt/dotnet")}})}
   :cljd {:what "ClojureDart, compiled (a Dart executable)" :bin (str (fs/path eve-root "target" "eve-swarm" "seat-cljd"))
          ;; built by capstone/eve-swarm/cljd/build.sh (ClojureDart over hosts.edn :cljd, then dart compile exe)
          :run (fn [] {:cmd [(str (fs/path eve-root "target" "eve-swarm" "seat-cljd"))]})}})

(defn- installed? [{:keys [bin]}] (boolean (or (fs/which bin) (and (fs/absolute? bin) (fs/exists? bin)))))

(defn seat! [args]
  (let [host (keyword (or (second (drop-while (fn [a] (not= "--host" a)) args)) "bb"))
        h (get hosts host)]
    (when-not h
      (binding [*out* *err*] (println "eve-swarm: no dialect" (name host) "— one of" (str/join " " (map name (keys hosts)))))
      (System/exit 2))
    (when-not (installed? h)
      (binding [*out* *err*] (println "eve-swarm:" (:what h) "is not installed here (" (:bin h) "; doc/hosts/)"))
      (System/exit 2))
    (when (:page h)
      (binding [*out* *err*] (println "eve-swarm:" (:what h) "is a page, not a process: test/aegis/e2e_rows/swarm.py's"
                                      "PageSeat runs it"))
      (System/exit 2))
    (let [{:keys [cmd env dir]} ((:run h))
          prof (System/getenv "EVE_SEAT_PROFILE")
          ;; EVE_SEAT_PROFILE=<file>: the seat's CPU profile — glojure's Go profile (glj-eve's GLJ_CPUPROFILE; go tool
          ;; pprof -top), basilisp's cProfile (python3 -m pstats)
          [cmd env] (cond (and prof (= host :glj)) [cmd (assoc env "GLJ_CPUPROFILE" prof)]
                          (and prof (= host :lpy))
                          (let [bin (str (fs/which "basilisp"))
                                ;; the interpreter basilisp is installed under (its script's #! line), not python3's
                                py (str/replace (first (str/split-lines (slurp bin))) #"^#!\s*" "")]
                            [(concat (str/split py #"\s+") ["-m" "cProfile" "-o" prof bin] (rest cmd)) env])
                          :else [cmd env])]
      (p/exec {:cmd (vec cmd) :extra-env (merge LOCALE env) :dir (or dir eve-root)}))))

(defn hosts! []
  (doseq [[k h] (sort-by key hosts)]
    (println (format "%-7s %-46s %s" (name k) (:what h) (if (installed? h) "installed" "not installed"))))
  (println (format "%-7s %-46s %s" "vs" "ValueScript" "the browser's own client (client.wasm), the swarm's page")))

(let [[cmd & args] *command-line-args*]
  (case cmd
    "seat" (seat! args)
    "hosts" (hosts!)
    (do (println "bb eve-swarm seat --host bb|jvm|nbb|sci|cljs|cherry|lpy|glj|clje|cljr|jolt|lg|cljd  |  bb eve-swarm hosts")
        (System/exit (if cmd 2 0)))))
