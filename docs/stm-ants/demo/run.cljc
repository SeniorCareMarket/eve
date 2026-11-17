;; run.cljc — the ants demo's front door (Lane M packaging of the
;; stm-ants lane's demo; the port is docs/stm-ants/ants.cljc AS-IS,
;; the members are the existing docs/stm-ants/hosts/ drivers AS-IS).
;;
;;   bb docs/stm-ants/demo/run.cljc [--board small|144] [--seconds N]
;;                                  [--ants N] [--world DIR] [--run DIR]
;;                                  [--members a,b,c] [--exclude a,b,c]
;;                                  [--gate SECONDS] [--dry-run]
;;
;; One command from a fresh clone (from the repo root; bb is the
;; launcher seat). The launcher probes the ORDER's twelve hosts,
;; deals the board across the members that can join, spawns them
;; against ONE shared world dir, streams the driver's renders and
;; audits, and prints a verdict block. Members that cannot join
;; FALL BACK: at 144 their column groups are dealt to extra
;; processes of the joined runtimes; on the small board the
;; expect-total is recomputed from the joined members. Nothing is
;; ever silently partial: a member that dies mid-join fails the
;; run loudly, with the member named.
;;
;; WHY BABASHKA: Eve has no spawn call in eve.process — every
;; seat's adapter carries the same HOST-CONTRACT §5 surface
;; (clock, sleep, timer, exit hook, file existence/create). Eve's
;; shell layer in practice is bb: eve/examples/
;; disk_domain_processes.clj spawns bb/jvm/glj processes against
;; one shared disk domain exactly this way, and eve/capstone/
;; eve-media/launcher.clj is a full bb launcher (runtime probing
;; by which + per-runtime "(+ 1 2)" run-checks, per-host command
;; tables, per-member log files, supervision). This launcher
;; follows those two precedents, and builds every member's
;; classpath from eve/hosts.edn — the tree's own per-host
;; adapter-roots table — so no absolute machine path ships.
;;
;; Launcher state lives in atoms (the demo's memory law); the
;; launcher never opens the world itself — membership is proven
;; by the members' READY lines and the driver's audits, and the
;; driver (the JVM seat) is the audit authority, as in the lane's
;; M1/M2 runs.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

;; ---------------------------------------------------------------------------
;; roots — everything derived from this script's own location in the
;; checkout (or --root / $ANTS_DEMO_ROOT); no absolute machine paths.

(def script-dir
  (let [f (fs/absolutize (or *file* "docs/stm-ants/demo/run.cljc"))]
    (str (fs/parent f))))

(defn find-root
  "The checkout root: the ancestor of the demo dir that holds
   eve/hosts.edn + an eve.stm (docs/stm-eve/eve/stm.cljc on the
   vendored trees, eve/src/eve/stm.cljc on the composed ones) +
   docs/stm-ants/ants.cljc. Falls back to $ANTS_DEMO_ROOT, then
   the cwd."
  []
  (let [ok? (fn [r]
              (and (fs/exists? (fs/path r "eve" "hosts.edn"))
                   (or (fs/exists? (fs/path r "docs" "stm-eve" "eve" "stm.cljc"))
                       (fs/exists? (fs/path r "eve" "src" "eve" "stm.cljc")))
                   (fs/exists? (fs/path r "docs" "stm-ants" "ants.cljc"))))
        up (loop [d (fs/absolutize script-dir) n 0]
             (cond (ok? d) (str d)
                   (> n 8) nil
                   :else (recur (fs/parent d) (inc n))))]
    (or up
        (let [e (System/getenv "ANTS_DEMO_ROOT")] (when (and e (ok? e)) e))
        (let [c (str (fs/absolutize "."))] (when (ok? c) c)))))

;; ---------------------------------------------------------------------------
;; args

(def usage
  (str "usage: bb docs/stm-ants/demo/run.cljc [options]\n"
       "  --board small|144   board size (default small = 8x8; 144 = the ORDER's 144x144)\n"
       "  --dim N             explicit board dimension (overrides --board)\n"
       "  --seconds N         audit window seconds (default 90 small, 120 at 144)\n"
       "  --ants N            ants per member process (default 2 small, 4 at 144)\n"
       "  --world DIR         world root (default: a fresh dir inside --run)\n"
       "  --run DIR           run dir for logs/configs (default ./ants-demo-<stamp>)\n"
       "  --keep-run          keep the world after the run (default: it is deleted at the end; ~10 GB at dim 60)\n"
       "  --min-free-gb N     refuse to start with less free space (default 15 at dim>=60, else 5)\n"
       "  --disk-floor-gb N   abort, kill the seats and delete the world below this free space (default 3)\n"
       "  --log-cap-mb N      trim any log file over N MB to its last 1 MB at the end (default 50)\n"
       "  --members a,b,c     only these seats may join (subset of the roster)\n"
       "  --exclude a,b,c     these seats are treated as unavailable (fallback proof)\n"
       "  --gate SECONDS      144 only: the driver's expect-gate (default 7200)\n"
       "  --dry-run           probe + deal + print the plan; spawn nothing\n"
       "  --root DIR          checkout root (default: detected from the script path)\n"
       "seats: jvm bb jolt nbb cljs browser cljr lpy glj jank clje vs\n"))

(defn parse-args [args]
  (loop [[a & more] args opts {}]
    (cond
      (nil? a) opts
      (= a "--help") (do (print usage) (flush) (System/exit 0))
      (= a "--board") (recur (rest more) (assoc opts :board (first more)))
      (= a "--dim") (recur (rest more) (assoc opts :dim (parse-long (first more))))
      (= a "--seconds") (recur (rest more) (assoc opts :seconds (parse-long (first more))))
      (= a "--ants") (recur (rest more) (assoc opts :ants (parse-long (first more))))
      (= a "--world") (recur (rest more) (assoc opts :world (first more)))
      (= a "--run") (recur (rest more) (assoc opts :run (first more)))
      (= a "--keep-run") (recur more (assoc opts :keep-run true))
      (= a "--min-free-gb") (recur (rest more) (assoc opts :min-free (parse-double (first more))))
      (= a "--disk-floor-gb") (recur (rest more) (assoc opts :floor (parse-double (first more))))
      (= a "--log-cap-mb") (recur (rest more) (assoc opts :cap-mb (parse-long (first more))))
      (= a "--members") (recur (rest more) (assoc opts :members (first more)))
      (= a "--exclude") (recur (rest more) (assoc opts :exclude (first more)))
      (= a "--gate") (recur (rest more) (assoc opts :gate (parse-long (first more))))
      (= a "--root") (recur (rest more) (assoc opts :root (first more)))
      (= a "--dry-run") (recur more (assoc opts :dry-run true))
      :else (do (println "unknown option:" a) (print usage) (flush) (System/exit 2)))))

(defn seat-set [s]
  (when s (set (map (fn [x] (keyword (str/trim x))) (str/split s #",")))))

;; ---------------------------------------------------------------------------
;; the member table — the ORDER's twelve hosts, in the ORDER's order.
;; :no-driver members have no ant-host driver program in the tree;
;; they are named in every verdict and always fall back.

(def roster
  [{:seat :jvm    :name "JVM (Clojure)"}
   {:seat :bb     :name "Babashka"}
   {:seat :jolt   :name "Jolt"}
   {:seat :nbb    :name "nbb"}
   {:seat :cljs   :name "ClojureScript/Node"
    :no-driver "no ant-host driver under docs/stm-ants/hosts/ (the CLJS family's driver in the tree is nbb's)"}
   {:seat :browser :name "browser"
    :no-driver "a browser cannot join a shell-spawned disk world; no ant-host driver in the tree"}
   {:seat :cljr   :name "ClojureCLR"}
   {:seat :lpy    :name "Basilisp"}
   {:seat :glj    :name "glojure"}
   {:seat :jank   :name "jank"}
   {:seat :clje   :name "clojerl"
    :no-driver "no ant-host driver in the tree (seat blocked: Eve's BEAM adapter defect, per the ORDER)"}
   {:seat :vs     :name "ValueScript seat"
    :no-driver "no ant-host driver in the tree (seat blocked: domain minting in the VS seat, per the ORDER)"}])

;; ---------------------------------------------------------------------------
;; classpaths from eve/hosts.edn (the tree's own table)

(defn load-hosts-edn [root]
  (edn/read-string (slurp (str (fs/path root "eve" "hosts.edn")))))

(defn cp-for
  "Absolute classpath: eve/src, then the seat's hosts.edn adapter
   roots, then more (stm-eve, stm-ants, the seat's host dir)."
  [root hosts-edn seat & more]
  (str/join ":"
            (map str
                 (concat [(fs/path root "eve" "src")]
                         (map (fn [r] (fs/path root "eve" r)) (get hosts-edn seat))
                         more))))

(defn paths-for
  "The same roots as a vector of absolute path strings (deps.edn)."
  [root hosts-edn seat & more]
  (mapv str
        (concat [(fs/path root "eve" "src")]
                (map (fn [r] (fs/path root "eve" r)) (get hosts-edn seat))
                more)))

;; ---------------------------------------------------------------------------
;; probing — Eve-media's pattern: the runtime must be found, and it
;; must run (+ 1 2) -> 3. Probes are sequential, each with a hard
;; timeout; a hung runtime is destroyed, never waited on forever.

(defn run-check
  "Run cmd for a trivial form check; true iff it exits 0 within
   timeout-ms and its stdout, trimmed, contains want. A runtime
   that cannot even be started (broken symlink, missing
   interpreter) is a failed check, never a launcher crash.
   NOTE the call shape — babashka.process takes (opts & args)
   with the command SPREAD as varargs, exactly as eve-media's
   launcher and the disk-domain example call it; handing it the
   command as one vector stringifies the vector into the program
   name and nothing runs."
  [cmd timeout-ms want & [dir]]
  (try
    (let [pr (apply p/process (cond-> {:out :string :err :string}
                                dir (assoc :dir (str dir)))
                    cmd)
          r (deref pr timeout-ms ::timeout)]
      (if (= ::timeout r)
        (do (p/destroy-tree pr) false)
        (and (zero? (:exit r)) (str/includes? (str (:out r)) want))))
    (catch Throwable _ false)))

(defn which-str [cmd] (some-> (fs/which cmd) str))

(defn glj-binary
  "glojure runs as the glj-eve binary Eve builds (bb glj-eve in
   eve/ -> target/glj-eve/glj-eve); $ANTS_DEMO_GLJ_EVE overrides."
  [root]
  (or (System/getenv "ANTS_DEMO_GLJ_EVE")
      (let [built (str (fs/path root "eve" "target" "glj-eve" "glj-eve"))]
        (when (fs/executable? built) built))
      (which-str "glj-eve")))

(defn probe-runtime
  "Stage 1 + 2 for a seat: {:runtime <path-or-name> :runs? bool} or
   {:reason ...} when the runtime cannot be found."
  [seat root rundir]
  (case seat
    :jvm (if-let [c (which-str "clojure")]
           {:runtime c :runs? (run-check [c "-M" "-e" "(println (+ 1 2))"] 180000 "3" rundir)}
           {:reason "the clojure CLI is not on PATH"})
    :bb (if-let [b (which-str "bb")]
          {:runtime b :runs? (run-check [b "-e" "(println (+ 1 2))"] 60000 "3")}
          {:reason "bb is not on PATH"})
    :jolt (if-let [j (which-str "jolt")]
            ;; run in the run dir (like :jvm): from a checkout root,
            ;; jolt's -e builds the classpath of the ROOT deps.edn and
            ;; a deps-resolution miss reads as a broken runtime.
            {:runtime j :runs? (run-check [j "-e" "(+ 1 2)"] 120000 "3" rundir)}
            {:reason "jolt is not on PATH"})
    :nbb (if-let [n (which-str "nbb")]
           {:runtime n :runs? (run-check [n "-e" "(println (+ 1 2))"] 120000 "3")}
           {:reason "nbb is not on PATH"})
    :cljr (if-let [c (which-str "Clojure.Main")]
            {:runtime c :runs? (run-check [c "-e" "(println (+ 1 2))"] 180000 "3")}
            {:reason "Clojure.Main (ClojureCLR) is not on PATH"})
    :lpy (if-let [b (which-str "basilisp")]
           {:runtime b :runs? (run-check [b "run" "-c" "(print (+ 1 2))"] 120000 "3")}
           {:reason "basilisp is not on PATH"})
    :jank (if-let [j (which-str "jank")]
            (let [f (fs/path rundir "probe-jank.clj")]
              (fs/create-dirs rundir)
              (spit (str f) "(println (+ 1 2))")
              {:runtime j :runs? (run-check [j "run" (str f)] 180000 "3" rundir)})
            {:reason "jank is not on PATH"})
    :glj (if-let [g (glj-binary root)]
           {:runtime g :runs? (run-check [g "-e" "(println (+ 1 2))"] 120000 "3")}
           {:reason "no glj-eve binary (build it: bb glj-eve in eve/, or set ANTS_DEMO_GLJ_EVE)"})
    {:reason "no runtime probe for this seat"}))

(defn nbb-domain-probe
  "Stage 3 for the CLJS seats' known defect (F-ANTS-2: persistent
   domains alias to the last-opened). Runs the stm-ants lane's own
   discriminator, hosts/nbb/probe_nbb_domains.cljc, in a scratch
   dir: three domains written 0/100/200 must read back distinct,
   through the originals and through fresh lookups. {:ok? bool}."
  [root hosts-edn rundir]
  (let [dir (fs/path rundir "probe-nbb")
        cp (cp-for root hosts-edn :nbb
                   (fs/path root "docs" "stm-eve")
                   (fs/path root "docs" "stm-ants")
                   (fs/path root "docs" "stm-ants" "hosts" "nbb"))
        spawned (try (apply p/process {:out :string :err :string :dir (str rundir)}
                            ["nbb" "--classpath" cp "-m" "probe-nbb-domains" (str dir)])
                     (catch Throwable t t))]
    (if (instance? Throwable spawned)
      {:ok? false :reason (str "nbb could not be started for the F-ANTS-2 domain probe: " (ex-message spawned))}
      (let [pr spawned
            r (deref pr 900000 ::timeout)]
    (if (= ::timeout r)
      (do (p/destroy-tree pr) {:ok? false :reason "the F-ANTS-2 domain probe did not finish in 900 s"})
      (let [line (last (filter (fn [l] (str/starts-with? l "E d2 opened"))
                               (str/split-lines (str (:out r)))))
            good (and line
                      (str/includes? line "a2= 200")
                      (str/includes? line "a1= 100")
                      (str/includes? line "a0= 0")
                      (str/includes? line "a0-fresh= 0")
                      (str/includes? line "a1-fresh= 100"))]
        {:ok? (boolean good)
         :reason (when-not good
                   (str "persistent domains alias on this seat (the F-ANTS-2 shape): "
                        (or line (str/trim (str (:err r) (:out r))))))}))))))

;; ---------------------------------------------------------------------------
;; dealing

(defn deal
  "Given the joined seats (roster order, :jvm first — the driver),
   produce the process list. Small board: one process per joined
   seat. 144 board: exactly 12 group processes (one per 12-column
   group) dealt across the joined seats as evenly as possible in
   roster order. Returns {:procs [{:seat :idx}] :expect ants}."
  [joined dim ants-per]
  (if (< dim 144)
    {:procs (mapv (fn [s] {:seat s :idx 0}) joined)
     :expect (* (count joined) ants-per)}
    (let [k (count joined)
          base (quot 12 k)
          extra (rem 12 k)
          counts (map-indexed (fn [i _] (+ base (if (< i extra) 1 0))) joined)
          procs (vec (mapcat (fn [s n] (map (fn [i] {:seat s :idx i}) (range n)))
                             joined counts))]
      {:procs procs :expect (* 12 ants-per)})))

;; ---------------------------------------------------------------------------
;; member commands — every member is one of the existing hosts/
;; drivers, spawned per its own proven launch shape (run_m1.sh,
;; run_m2.sh, run_spike_144b.sh, run_smoke_cljr4.sh), with configs
;; and deps generated into the run dir from the detected root.
;; The JVM seat's extra processes (144 deal) use the demo's own
;; jvm_host_main.clj — the tree ships no JVM ant-host main (in
;; M1/M2 the JVM seat was always the driver); see that file.

(defn utf8-locale
  "The jank member needs a UTF-8 LC_ALL: jank's C++ runtime throws
   (locale::facet::_S_create_c_locale) on a locale name the box
   does not have installed. Prefer en_US.UTF-8 (the jank seat's
   proven setting), fall back to C.UTF-8."
  []
  (let [avail (try (:out (p/sh "locale" "-a")) (catch Throwable _ ""))]
    (if (str/includes? (str avail) "en_US") "en_US.UTF-8" "C.UTF-8")))

(defn write-cfg! [dir cfg]
  (fs/create-dirs dir)
  (spit (str (fs/path dir "ants-config.edn")) (pr-str cfg))
  (str (fs/path dir "ants-config.edn")))

(defn member-spec
  "The spawn spec for one process of seat (idx = which process of
   that seat). ctx carries the run's roots/dirs/numbers."
  [ctx {:keys [seat idx]}]
  (let [{:keys [root hosts-edn stm-ants stm-eve world dim ants host-seconds rundir demo]} ctx
        dir (fs/path rundir "members" (str (name seat) "-" idx))
        cfg {:root (str world) :dim dim :ants ants :seconds host-seconds}]
    (fs/create-dirs dir)
    (case seat
      :jvm (let [cfgp (write-cfg! dir cfg)]
             (spit (str (fs/path dir "deps.edn"))
                   (pr-str {:paths (paths-for root hosts-edn :jvm stm-eve stm-ants)}))
             {:cmd ["clojure" "-M" (str (fs/path demo "jvm_host_main.clj")) cfgp]
              :dir (str dir)})
      :bb (let [cfgp (write-cfg! dir cfg)]
            {:cmd ["bb" "-cp"
                   (cp-for root hosts-edn :bb stm-eve stm-ants (fs/path stm-ants "hosts" "bb"))
                   "-f" (str (fs/path stm-ants "hosts" "bb" "ants_bb_main.clj")) cfgp]
             :dir (str dir)})
      :jolt (do (write-cfg! dir cfg)
                (spit (str (fs/path dir "deps.edn"))
                      (pr-str {:paths (paths-for root hosts-edn :jolt stm-eve stm-ants
                                                  (fs/path stm-ants "hosts" "jolt"))}))
                {:cmd ["jolt" "run" "-m" "ants-jolt-main"]
                 :dir (str dir)})
      :nbb (do (write-cfg! dir cfg)
               {:cmd ["nbb" "--classpath"
                      (cp-for root hosts-edn :nbb stm-eve stm-ants (fs/path stm-ants "hosts" "nbb"))
                      "-m" "ants-nbb-main"]
                :dir (str dir)})
      :cljr (do (write-cfg! dir cfg)
                {:cmd ["Clojure.Main" "-m" "ants-cljr-main"]
                 :dir (str dir)
                 :env {"CLOJURE_LOAD_PATH"
                       (cp-for root hosts-edn :cljr stm-eve stm-ants (fs/path stm-ants "hosts" "cljr"))}})
      :lpy (let [cfgp (write-cfg! dir cfg)
                 priority (concat [(str (fs/path root "eve" "src"))]
                                  (map (fn [r] (str (fs/path root "eve" r))) (:lpy hosts-edn))
                                  [(str stm-eve) (str stm-ants) (str (fs/path stm-ants "hosts" "lpy"))])]
             {:cmd (vec (concat ["basilisp" "run"]
                                (mapcat (fn [r] ["-p" r]) (reverse priority))
                                ["-c" (str "(require 'ants-lpy-main) (ants-lpy-main/run \"" cfgp "\")")]))
              :dir (str dir)
              :env {"PYTHONPYCACHEPREFIX" (str (fs/path rundir "lpycache"))}})
      :jank (do (write-cfg! dir cfg)
                {:cmd ["jank" "--module-path"
                       (cp-for root hosts-edn :jank stm-eve stm-ants (fs/path stm-ants "hosts" "jank"))
                       "run-main" "ants-jank-main"]
                 :dir (str dir)
                 :env {"LC_ALL" (utf8-locale)}})
      :glj (let [cfgp (write-cfg! dir cfg)]
             ;; glojure needs a deps.edn in its cwd: without one its
             ;; analyzer dies on Eve's CollReduce extend in eve.map
             ;; (EvalASTMaybeHostForm: clojure.core.protocols/
             ;; CollReduce — the protocol only becomes extendable
             ;; in glojure's project mode). Mirrors the :jolt spec.
             (spit (str (fs/path dir "deps.edn"))
                   (pr-str {:paths (paths-for root hosts-edn :glj stm-eve stm-ants
                                               (fs/path stm-ants "hosts" "glj"))}))
             {:cmd [(glj-binary root) "-e"
                    (str "(require 'eve.host-completion) (require 'ants-glj-main) (ants-glj-main/run \"" cfgp "\")")]
              :dir (str dir)
              :env {"GLJ_CLASSPATH"
                    (cp-for root hosts-edn :glj stm-eve stm-ants (fs/path stm-ants "hosts" "glj"))}})
      (throw (ex-info (str "no member spec for seat " seat) {:seat seat})))))

(defn driver-spec
  "The JVM driver process: creates the world, evaporates, renders,
   audits. Its deps.edn is generated into its member dir."
  [ctx expect gate]
  (let [{:keys [root hosts-edn stm-ants stm-eve world dim ants window rundir]} ctx
        dir (fs/path rundir "members" "driver")]
    (fs/create-dirs dir)
    (spit (str (fs/path dir "deps.edn"))
          (pr-str {:paths (paths-for root hosts-edn :jvm stm-eve stm-ants)}))
    {:cmd (vec (concat ["clojure" "-J-Dstdout.encoding=UTF-8" "-J-Dfile.encoding=UTF-8" "-M"
                        (str (fs/path stm-ants "hosts" "jvm"
                                      (if (>= dim 144) "ants_driver_144.clj" "ants_driver.clj")))
                        (str world) (str dim) (str ants) (str window) (str expect)]
                       (when (>= dim 144) [(str gate)])))
     :dir (str dir)}))

(defn precreate-spec
  "The world pre-creation process (F-ANTS-4): the JVM driver run
   in precreate mode — it creates every domain, marks the world
   :ready, syncs, and exits BEFORE any host is spawned. Hosts
   then only ever reopen completed domains, which is the
   in-tree design (the driver is the sole creator)."
  [ctx expect]
  (let [{:keys [root hosts-edn stm-ants stm-eve world dim ants window rundir]} ctx
        dir (fs/path rundir "members" "precreate")]
    (fs/create-dirs dir)
    (spit (str (fs/path dir "deps.edn"))
          (pr-str {:paths (paths-for root hosts-edn :jvm stm-eve stm-ants)}))
    {:cmd ["clojure" "-J-Dstdout.encoding=UTF-8" "-J-Dfile.encoding=UTF-8" "-M"
           (str (fs/path stm-ants "hosts" "jvm" "ants_driver.clj"))
           (str world) (str dim) (str ants) (str window) (str expect) "precreate"]
     :dir (str dir)}))

;; ---------------------------------------------------------------------------
;; the run

(def state
  "The launcher's own state — atoms, per the demo's memory law."
  (atom {:children []        ; [{:label :proc :log :errlog :off :erroff :spawned-ms}]
         :world-ready? false
         :world-ready-ms nil ; when DRIVER-READY first appeared
         :all-in? false
         :stopped? false}))  ; set by the shutdown hook (operator stop)

(defn log-lines-since
  "New complete lines of a log file since byte offset n, read via a
   seek (a 144 driver log grows for hours; never re-read it whole).
   Returns [lines new-offset]."
  [path n]
  (if (fs/exists? path)
    (let [raf (java.io.RandomAccessFile. (str path) "r")
          total (.length raf)]
      (try
        (if (> total n)
          (do (.seek raf (long n))
              (let [buf (byte-array (- total n))]
                (.readFully raf buf)
                (let [chunk (String. buf "UTF-8")
                      cut (inc (str/last-index-of chunk "\n"))]
                  (if (pos? cut)
                    (let [head (subs chunk 0 cut)]
                      [(str/split-lines head)
                       (+ n (count (.getBytes head "UTF-8")))])
                    [[] n]))))
          [[] n])
        (finally (.close raf))))
    [[] n]))

(def board-legend
  "The board legend — printed once, with the first frame of a
   run, and carried in the demo README."
  ["LEGEND ant by heading ↑↗→↘↓↙←↖ · ant carrying food ⇑⇗⇒⇘⇓⇙⇐⇖ (double arrows)"
   "LEGEND home zone ⌂ · food · 1, : 2, + 3, # 4+ · pheromone ░ faint, ▒ fresh, ▓ busy trail, █ highway"])

(def presentation
  "Console presentation state for the drivers board frames —
   atoms per the demos memory law. :tty? selects the in-place
   redraw (pipes and log files keep the scrolling stream);
   :dim is learned from the first completed frame; :buf holds
   candidate board lines between AUDIT lines (the drivers loop
   emits exactly dim board lines, then one AUDIT line); :live?
   means the in-place region is on screen with the cursor
   resting on the line below its status line."
  (atom {:tty? (some? (System/console))
         :dim nil
         :buf []
         :live? false
         :legend? false}))

(defn- status-line!
  "Rewrite the status line (the line directly under the board)
   in place; the cursor ends below it again, as the redraw
   invariant requires."
  [s]
  (print (str "\033[1A\r" (subs s 0 (min (count s) 118)) "\033[K\n"))
  (flush))

(defn- frame-redraw!
  "Draw a completed board frame plus its AUDIT status line, in
   place: cursor-up over the region and overwrite — the board
   is a stable picture that updates, Hickey-GUI repaint
   semantics in ASCII. First frame: the legend scrolls above
   the region, the board prints, the cursor hides. Frame rate
   is the drivers own audit cycle, never faster."
  [frame status]
  (let [dim (count frame)]
    (if (:live? @presentation)
      (do
        (print (str "\033[" (+ dim 1) "A"))
        (doseq [l frame]
          (println (str l "\033[K")))
        (println (str (subs status 0 (min (count status) 118)) "\033[K"))
        (flush))
      (do
        (when-not (:legend? @presentation)
          (doseq [l board-legend]
            (println l))
          (swap! presentation assoc :legend? true))
        (doseq [l frame]
          (println l))
        (println status)
        (print "\033[?25l")
        (flush)
        (swap! presentation assoc :live? true)))))

(defn- frame-end!
  "End the in-place region (the drivers FINAL lines are
   arriving): show the cursor again; what follows scrolls."
  []
  (when (:live? @presentation)
    (print "\033[?25h")
    (flush)
    (swap! presentation assoc :live? false)))

(defn echo-line!
  "Console echo for one child-output line. Driver stdout board
   lines are buffered; an AUDIT line completes a frame, which
   is presented whole — in place on a TTY (frame-redraw!), or
   scrolled into pipes and log files (the legend first, once).
   Everything else echoes as it arrives; a labeled line that
   arrives while the board is live rewrites the status line
   instead of tearing the picture. A buffer that never meets
   its AUDIT (400+ lines) flushes as scroll rather than growing
   forever, and control lines flush any partial frame first —
   degraded is printed, never silently dropped."
  [label line]
  (let [p @presentation]
    (cond
      ;; labeled: member lines and driver stderr
      label
      (if (:live? p)
        (status-line! (str "[" label "] " line))
        (do
          (println (str "[" label "] " line))
          (flush)))

      ;; driver stdout control lines
      (or (str/starts-with? line "DRIVER-")
          (str/starts-with? line "FINAL-AUDIT"))
      (do
        (when (seq (:buf p))
          (doseq [l (:buf p)]
            (println l))
          (swap! presentation assoc :buf []))
        (when (str/starts-with? line "FINAL-AUDIT") (frame-end!))
        (println line)
        (flush))

      ;; an AUDIT line completes a frame
      (str/starts-with? line "AUDIT ")
      (let [buf (:buf p)
            dim (:dim p)
            frame? (and (seq buf) (or (nil? dim) (= (count buf) dim))
                        (apply = (map count buf)))]
        (swap! presentation assoc :buf [])
        (cond
          (not frame?)
          (do
            (doseq [l buf]
              (println l))
            (println line)
            (flush))
          (:tty? p)
          (do
            (when (nil? dim) (swap! presentation assoc :dim (count buf)))
            (frame-redraw! buf line))
          :else
          (do
            (when-not (:legend? p)
              (doseq [l board-legend]
                (println l))
              (swap! presentation assoc :legend? true))
            (doseq [l buf]
              (println l))
            (println line)
            (flush))))

      ;; a board candidate: buffer it
      :else
      (do
        (swap! presentation update :buf conj line)
        (when (> (count (:buf @presentation)) 400)
          (doseq [l (:buf @presentation)]
            (println l))
          (swap! presentation assoc :buf [])
          (flush))))))

(defn fail! [code & lines]
  (doseq [l lines] (println l))
  (flush)
  (System/exit code))

;; ---------------------------------------------------------------------------
;; disk safety — the demo writes a world to disk (one Eve domain per board
;; cell: ~10 GB at dim 60, measured on the Mac in the perf-venues ants
;; ladder that filled an operator's disk). Safe by default: refuse to start
;; short of space, abort while running if space runs out, and delete the
;; world when the run ends unless --keep-run.

(def disk (atom {:world nil :rundir nil :keep? false :cleaned? false}))

(defn usable-gb
  "Usable GB on the volume holding path (nearest existing ancestor)."
  [path]
  (let [f (loop [f (java.io.File. (str path))]
            (cond (.exists f) f
                  (.getParentFile f) (recur (.getParentFile f))
                  :else (java.io.File. ".")))]
    (/ (.getUsableSpace f) 1073741824.0)))

(defn du-human [path]
  (try (str/trim (:out (p/sh "du" "-sh" (str path)))) (catch Throwable _ "?")))

(defn- self-pid [] (str/trim (:out (p/sh "sh" "-c" "echo $PPID"))))

(defn- descendants-of [pid]
  (let [kids (try (remove str/blank? (str/split-lines (:out (p/sh "pgrep" "-P" (str pid))))) (catch Throwable _ []))]
    (vec (concat (mapcat descendants-of kids) kids))))

(defn kill-children!
  "Kill every process this launcher started (all seats, whole trees), deepest first."
  []
  (try
    (let [pids (descendants-of (or (:pid @disk) (self-pid)))]
      (doseq [k pids] (try (p/sh "kill" "-9" (str k)) (catch Throwable _))))
    (catch Throwable _)))

(defn trim-logs!
  "Cap per-file log size: a file over cap-mb keeps only its last 1 MB."
  [rundir cap-mb]
  (try
    (doseq [^java.io.File f (file-seq (java.io.File. (str rundir)))
            :when (and (.isFile f) (> (.length f) (* cap-mb 1048576)))]
      (try
        (with-open [raf (java.io.RandomAccessFile. f "rw")]
          (let [keep 1048576 n (.length raf) buf (byte-array keep)]
            (.seek raf (- n keep))
            (.readFully raf buf)
            (.setLength raf 0)
            (.write raf (.getBytes (str "[log trimmed to its last 1 MB; cap " cap-mb " MB]\n")))
            (.write raf buf)))
        (catch Throwable _)))
    (catch Throwable _)))

(defn cleanup!
  "Idempotent. Kills the seats, trims logs, prints the world's size, then
   deletes the world unless --keep-run."
  []
  (let [{:keys [world rundir keep? cleaned? cap-mb]} @disk]
    (when (and world (not cleaned?))
      (swap! disk assoc :cleaned? true)
      (kill-children!)
      (Thread/sleep 300)
      (trim-logs! rundir (or cap-mb 50))
      (when (fs/exists? world)
        (println (str "world size: " (du-human world) "  (" world ")"))
        (if keep?
          (println "world kept (--keep-run); delete it when done:" world)
          (do (try (fs/delete-tree world) (catch Throwable _))
              (println (if (fs/exists? world) "world delete FAILED — remove it by hand" "world deleted")))))
      (flush))))

(defn shutdown!
  "Kill the seats, clean the world, kill again (the supervising thread may spawn once more while this runs)."
  []
  (kill-children!)
  (cleanup!)
  (Thread/sleep 200)
  (kill-children!))

(defn est-min-free-gb [dim] (if (>= dim 60) 15.0 5.0))

(defn install-disk-guard!
  "Preflight (refuse to start short of --min-free-gb), shutdown hook
   (cleanup on success, failure, Ctrl-C, timeout), and a 5 s watchdog
   (abort below --disk-floor-gb)."
  [{:keys [world rundir dim keep? min-free floor cap-mb]}]
  (let [need (or min-free (est-min-free-gb dim))
        floor (or floor 3.0)
        free (usable-gb rundir)]
    (println (format "disk: estimate %.0f GB needed (%s; use --min-free-gb to override), %.1f GB free, abort floor %.1f GB, world %s"
                     (double need) (if min-free "--min-free-gb" "default for this dim, not measured per member")
                     free (double floor) (if keep? "kept (--keep-run)" "deleted at the end")))
    (flush)
    (when (< free need)
      (fail! 2 (format "DISK-PREFLIGHT refuse: %.1f GB free < %.0f GB needed on the volume of %s." free (double need) rundir)
             "Free space, pass --run/--world on a bigger volume, or lower --min-free-gb at your own risk."))
    (reset! disk {:pid (self-pid) :world world :rundir rundir :keep? keep? :cleaned? false :cap-mb (or cap-mb 50)})
    (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (shutdown!))))
    (doto (Thread.
           (fn []
             (loop []
               (Thread/sleep 5000)
               (let [f (usable-gb rundir)]
                 (if (< f floor)
                   (do (println (format "DISK-FLOOR abort %.2f GB (floor %.1f GB)" f (double floor)))
                       (flush)
                       (shutdown!)
                       (System/exit 4))
                   (recur))))))
      (.setDaemon true)
      (.start))))

(defn main [args]
  (let [opts (parse-args args)
        root (or (:root opts) (find-root))
        _ (when-not root
            (fail! 2 "ants demo: cannot find the checkout root (eve/hosts.edn + docs/stm-eve + docs/stm-ants)."
                   "Run from inside the atom.broker checkout, or pass --root DIR."))
        root (str (fs/absolutize root))
        hosts-edn (load-hosts-edn root)
        stm-ants (str (fs/path root "docs" "stm-ants"))
        stm-eve (str (fs/path root "docs" "stm-eve"))
        demo (str (fs/path root "docs" "stm-ants" "demo"))
        board (or (:board opts) "small")
        dim (or (:dim opts) (if (= board "144") 144 8))
        window (or (:seconds opts) (if (>= dim 144) 120 90))
        ants-per (or (:ants opts) (if (>= dim 144) 4 2))
        gate (or (:gate opts) 7200)
        big? (>= dim 144)
        stamp (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                       (java.time.LocalDateTime/now))
        rundir (str (fs/absolutize (or (:run opts) (str "ants-demo-" stamp))))
        world (str (fs/absolutize (or (:world opts) (str (fs/path rundir "world")))))
        only (seat-set (:members opts))
        excluded (seat-set (:exclude opts))
        _ (fs/create-dirs rundir)
        _ (fs/create-dirs (fs/path rundir "logs"))
        _ (install-disk-guard! {:world world :rundir rundir :dim dim :keep? (:keep-run opts)
                                :min-free (:min-free opts) :floor (:floor opts) :cap-mb (:cap-mb opts)})
        _ (when (and (fs/exists? world) (seq (fs/list-dir world)))
            (fail! 2 (str "ants demo: the world dir " world " already has content.")
                   "Worlds are single-run: pass a fresh --world (or let the launcher create one)."))]
    (println "=== ants demo ===")
    (println "checkout:" root)
    (println "board:" (str dim "x" dim) (str "(" (* dim dim) " cells)") "| run dir:" rundir)
    (println "world:" world)
    (flush)
    ;; ---- probe the roster
    (println "--- probing the twelve hosts")
    (let [results
          (mapv (fn [{:keys [seat name no-driver] :as m}]
                  (let [base {:seat seat :name name}]
                    (cond
                      (and only (not (contains? only seat)))
                      (assoc base :status :not-selected :reason "not in --members")
                      (contains? excluded seat)
                      (assoc base :status :excluded :reason "made unavailable by --exclude (deliberate)")
                      no-driver
                      (assoc base :status :no-driver :reason no-driver)
                      :else
                      (let [pr (probe-runtime seat root rundir)]
                        (if-not (:runtime pr)
                          (assoc base :status :absent :reason (:reason pr))
                          (if-not (:runs? pr)
                            (assoc base :status :broken :reason (str "the runtime was found (" (:runtime pr) ") but did not run (+ 1 2)"))
                            (if (= seat :nbb)
                              (let [dp (nbb-domain-probe root hosts-edn rundir)]
                                (if (:ok? dp)
                                  (assoc base :status :ready :runtime (:runtime pr))
                                  (assoc base :status :broken :reason (:reason dp))))
                              (assoc base :status :ready :runtime (:runtime pr)))))))))
                roster)
          ready (filter (fn [r] (= :ready (:status r))) results)
          joined (mapv :seat ready)]
      (doseq [r results]
        (println (format "  %-22s %s" (:name r)
                         (case (:status r)
                           :ready (str "READY (" (:runtime r) ")")
                           :not-selected "not selected"
                           :excluded (str "EXCLUDED — " (:reason r))
                           :no-driver (str "NO DRIVER — " (:reason r))
                           :absent (str "ABSENT — " (:reason r))
                           :broken (str "BROKEN — " (:reason r))))))
      (flush)
      ;; ---- the driver gate: the JVM seat creates the world and audits
      (when-not (some (fn [s] (= :jvm s)) joined)
        (doseq [l [""
                   "CANNOT RUN: the JVM seat did not join, and it is the only driver"
                   "program in the tree — it creates the world and runs the audit."
                   "Every other seat falls back; the driver cannot. Install the"
                   "Clojure CLI (and a JDK) and re-run."]]
          (println l))
        (flush)
        (System/exit 2))
      ;; ---- deal
      (let [{procs :procs expect :expect} (deal joined dim ants-per)
            host-seconds (+ window 60)
            ctx {:root root :hosts-edn hosts-edn :stm-ants stm-ants :stm-eve stm-eve
                 :demo demo :world world :dim dim :ants ants-per :window window
                 :host-seconds host-seconds :rundir rundir}
            by-seat (group-by :seat procs)
            absent (remove (fn [r] (= :ready (:status r))) results)]
        (println "--- dealing")
        (if big?
          (do (println (str "  12 column groups across " (count joined) " joined members:"))
              (doseq [s joined]
                (println (str "  " (name s) ": " (count (get by-seat s)) " group process(es)"))))
          (println (str "  one process per joined member (" (count joined) "); "
                        ants-per " ants each")))
        (println (str "  expect-total (from who joined): " expect " ants"))
        (doseq [r absent]
          (println (str "  falls back: " (:name r) " — " (:reason r))))
        (flush)
        (when (:dry-run opts)
          (println "--- dry run: the spawn plan")
          (let [ds (driver-spec ctx expect gate)]
            (println "  driver:" (str/join " " (:cmd ds)) " (cwd" (str (:dir ds) ")")))
          (doseq [pr procs :when (not= pr {:seat :jvm :idx 0})]
            (let [ms (member-spec ctx pr)]
              (println (str "  " (name (:seat pr)) "-" (:idx pr) ":")
                       (str/join " " (:cmd ms)) " (cwd" (str (:dir ms) ")"))))
          (println "dry run only — nothing spawned, no world created.")
          (flush)
          (System/exit 0))
        ;; ---- pre-create the world (F-ANTS-4): the driver alone
        ;; creates every domain and marks the world :ready; only
        ;; then does anyone spawn. Two runs of the spawn-at-once
        ;; shape died at join on exactly this race (dim-8 smoke D,
        ;; run60-a). Small-board path; the 144 driver keeps its
        ;; own flow (its build is the gate's business there).
        (when-not big?
          (println "--- pre-creating the world (driver sole creator, to :ready)")
          (flush)
          (let [spec (precreate-spec ctx expect)
                logf (str (fs/path rundir "logs" "precreate.log"))
                errf (str (fs/path rundir "logs" "precreate.err.log"))
                pr (apply p/process {:out (fs/file logf) :err (fs/file errf)
                                     :dir (:dir spec)}
                          (:cmd spec))
                r (deref pr 3600000 ::timeout)]
            (when (= ::timeout r)
              (p/destroy-tree pr)
              (fail! 3 "RUN FAILED: world pre-creation did not finish in 3600 s (see logs/precreate.log)."))
            (when-not (and (zero? (:exit r))
                           (str/includes? (slurp logf) "WORLD-PRECREATED"))
              (fail! 3 "RUN FAILED: world pre-creation failed (see logs/precreate.log and logs/precreate.err.log).")))
          (println "  world :ready — hosts spawn against completed domains")
          (flush))
        ;; ---- spawn
        (println "--- spawning")
        (let [spawn! (fn [label spec]
                       (let [logf (str (fs/path rundir "logs" (str label ".log")))
                             errf (str (fs/path rundir "logs" (str label ".err.log")))
                             pr (try (apply p/process
                                            (cond-> {:out (fs/file logf) :err (fs/file errf)
                                                     :dir (:dir spec)}
                                              (:env spec) (assoc :extra-env (:env spec)))
                                            (:cmd spec))
                                     (catch Throwable t
                                       (fail! 3 (str "RUN FAILED: member " label " could not be started: "
                                                     (ex-message t)))))]
                         (swap! state update :children conj
                                {:label label :proc pr :log logf :errlog errf
                                 :off (atom 0) :erroff (atom 0)
                                 :spawned-ms (System/currentTimeMillis)})
                         pr))
              _ (spawn! "driver" (driver-spec ctx expect gate))
              _ (println "  driver (jvm) spawned; it creates the world")
              ;; the driver IS the jvm seat's process 0; every other
              ;; dealt process is a member (incl. jvm processes 1..n)
              spawn-list (vec (remove (fn [pr] (= pr {:seat :jvm :idx 0})) procs))
              ;; nbb's main does NOT call await-built! — it opens the
              ;; world directly, so spawning it before the world
              ;; exists would race the driver's cell creation
              ;; (F-ANTS-4). nbb processes are held and spawned by
              ;; the supervision loop the moment DRIVER-READY lands.
              held (atom [])]
          (doseq [[i pr] (map-indexed (fn [i pr] [i pr]) spawn-list)]
            (Thread/sleep 2000)
            (let [label (str (name (:seat pr)) "-" (:idx pr))]
              (if (= :nbb (:seat pr))
                (do (swap! held conj {:label label :spec (member-spec ctx pr)})
                    (println (str "  " label " held — nbb spawns after DRIVER-READY (its main does not await-built!)")))
                (do (spawn! label (member-spec ctx pr))
                    (println (str "  " label " spawned (member " (inc i) " of " (count spawn-list) ")"))))
              (flush)))
          ;; shutdown hook: an operator stop takes the children down
          (.addShutdownHook
           (Runtime/getRuntime)
           (Thread. (fn []
                      (swap! state assoc :stopped? true)
                      (doseq [c (:children @state)]
                        (try (p/destroy-tree (:proc c)) (catch Throwable _))))))
          ;; ---- supervise
          (let [driver-child (first (:children @state))
                start-ms (:spawned-ms driver-child)
                joined-seen (atom {})
                dead-joined (atom [])
                pump! (fn [c member?]
                        (doseq [[path off err?] [[(:log c) (:off c) false]
                                                 [(:errlog c) (:erroff c) true]]]
                          (let [[lines noff] (log-lines-since path @off)]
                            (when (seq lines)
                              (reset! off noff)
                              (doseq [l lines]
                                (if member?
                                  (do (when (or (str/includes? l "ANT-HOST-READY")
                                                (str/includes? l "ANT-HOST-DONE")
                                                (str/includes? l "HOST-DIED")
                                                (str/includes? l "FIRST-ERROR"))
                                        (echo-line! (str (:label c) (when err? " (err)")) l))
                                      (when (str/includes? l "ANT-HOST-READY")
                                        (let [tag (some-> (re-find #"tag=\s*(\d+)" l) second parse-long)]
                                          (swap! joined-seen assoc (:label c) tag))))
                                  (do (echo-line! (when err? "driver (err)") l)
                                      (when (str/includes? l "DRIVER-READY")
                                        (when-not (:world-ready? @state)
                                          (swap! state assoc :world-ready? true
                                                 :world-ready-ms (System/currentTimeMillis))))
                                      (when (str/includes? l "DRIVER-ALL-IN")
                                        (let [[_ reg exp] (re-find #"registered=\s*(\d+)\s+expect=\s*(\d+)" l)]
                                          (when (and reg exp (= reg exp))
                                            (swap! state assoc :all-in? true)))))))))))]
            (loop []
              ;; release held members (nbb) the moment the world exists
              (when (and (seq @held) (:world-ready? @state))
                (doseq [h @held]
                  (spawn! (:label h) (:spec h))
                  (println (str "  " (:label h) " spawned (DRIVER-READY seen; the world exists)"))
                  (flush))
                (reset! held []))
              (let [member-children (vec (rest (:children @state)))
                    now (System/currentTimeMillis)
                    ready-ms (:world-ready-ms @state)
                    ;; deadlines anchor to DRIVER-READY: before it,
                    ;; members cannot be "late" (the world does not
                    ;; exist yet — at 144 the build alone takes
                    ;; ~1-2 h); the pre-ready cap is a 6 h build bound.
                    join-base (or ready-ms start-ms)
                    join-deadline-ms (* 1000 (+ (if big? gate 600) window 600))
                    abs-limit-s (if ready-ms
                                  (+ (if big? gate 600) window host-seconds 3600)
                                  (* 6 3600))]
                (doseq [c (:children @state)]
                  (pump! c (not= "driver" (:label c))))
                (doseq [c member-children]
                  (when (and (not (contains? @joined-seen (:label c)))
                             (not (p/alive? (:proc c)))
                             (not (some (fn [x] (= x (:label c))) @dead-joined)))
                    (swap! dead-joined conj (:label c))))
                (cond
                  (seq @dead-joined)
                  (do (doseq [c (:children @state)]
                        (try (p/destroy-tree (:proc c)) (catch Throwable _)))
                      (doseq [l [""
                                 (str "RUN FAILED: member(s) " (str/join ", " @dead-joined)
                                      " exited before joining (no ANT-HOST-READY line).")
                                 "A member that dies mid-join fails the run loudly: its"
                                 "process tag may already be claimed, so its slice cannot"
                                 "be honestly re-dealt after the fact. Its log is in the"
                                 (str "run dir: " rundir "/logs")]]
                        (println l))
                      (flush)
                      (System/exit 3))
                  (not (p/alive? (:proc driver-child)))
                  nil ; driver done -> endgame below
                  (> now (+ join-base (* 1000 abs-limit-s)))
                  (do (doseq [c (:children @state)]
                        (try (p/destroy-tree (:proc c)) (catch Throwable _)))
                      (println "RUN FAILED: the run exceeded its absolute deadline; children destroyed.")
                      (flush)
                      (System/exit 3))
                  (and ready-ms
                       (seq member-children)
                       (< (count @joined-seen) (count member-children))
                       (> (- now join-base) join-deadline-ms))
                  (do (doseq [c (:children @state)]
                        (try (p/destroy-tree (:proc c)) (catch Throwable _)))
                      (println (str "RUN FAILED: not all members joined within "
                                    (quot join-deadline-ms 1000) " s of DRIVER-READY: joined so far "
                                    (pr-str (keys @joined-seen))))
                      (flush)
                      (System/exit 3))
                  :else
                  (do (Thread/sleep 1000) (recur)))))
            ;; ---- endgame: driver has exited
            (let [member-children (vec (rest (:children @state)))
                  driver-exit (:exit @(:proc driver-child))
                  grace-end (+ (System/currentTimeMillis) 300000)
                  _ (loop []
                      (when (and (some (fn [c] (p/alive? (:proc c))) member-children)
                                 (< (System/currentTimeMillis) grace-end))
                        (doseq [c member-children] (pump! c true))
                        (Thread/sleep 1000)
                        (recur)))
                  stragglers (filter (fn [c] (p/alive? (:proc c))) member-children)
                  _ (doseq [c stragglers] (try (p/destroy-tree (:proc c)) (catch Throwable _)))
                  _ (doseq [c (:children @state)] (pump! c (not= "driver" (:label c))))
                  member-exits (mapv (fn [c] {:label (:label c)
                                              :exit (try (:exit @(:proc c)) (catch Throwable _ -1))
                                              :log (str (slurp (:log c)) "\n" (slurp (:errlog c)))})
                                     member-children)
                  driver-log (slurp (:log driver-child))
                  final-line (last (filter (fn [l] (str/starts-with? l "FINAL-AUDIT"))
                                           (str/split-lines driver-log)))
                  stats-line (last (filter (fn [l] (str/starts-with? l "DRIVER-STATS"))
                                           (str/split-lines driver-log)))
                  audits (count (filter (fn [l] (str/starts-with? l "AUDIT "))
                                        (str/split-lines driver-log)))
                  getf (fn [k] (some-> (re-find (re-pattern (str k " (true|false)")) (str final-line)) second))
                  ants-ok (getf ":ants-ok\\?") reg-ok (getf ":registered-ok\\?")
                  food-ok (getf ":food-ok\\?")
                  bad-audits (some-> (re-find #"bad-audits=\s*(\d+)" (str stats-line)) second)
                  member-warnings (filter (fn [m]
                                            (and (str/includes? (:log m) "errors=")
                                                 (not (str/includes? (:log m) "errors= []"))))
                                          member-exits)
                  member-died (filter (fn [m] (str/includes? (:log m) "HOST-DIED")) member-exits)
                  clean? (and (zero? driver-exit) (:all-in? @state)
                              (= "true" ants-ok) (= "true" reg-ok)
                              (= "true" food-ok) (= "0" (str bad-audits))
                              (empty? member-died) (empty? stragglers) (empty? @held)
                              (= (count @joined-seen) (count member-children)))]
              (println)
              (println "================ ANTS DEMO VERDICT ================")
              (when (:stopped? @state)
                (println "STOPPED BY THE OPERATOR before completion — the state below")
                (println "is partial and is NOT a verdict."))
              (println (str "board: " dim "x" dim " (" (* dim dim) " cells)   world: " world))
              (println (str "members joined (" (count joined) "): "
                            (str/join ", " (map (fn [s]
                                                  (let [tags (keep (fn [[label tag]]
                                                                     (when (str/starts-with? label (str (name s) "-")) tag))
                                                                   @joined-seen)]
                                                    (str (name s)
                                                         (when (= s :jvm) " (driver)")
                                                         (when (seq tags) (str " tag" (when (> (count tags) 1) "s") " " (str/join "," tags))))))
                                                joined))))
              (when big?
                (println (str "column groups: 12 dealt — "
                              (str/join ", " (map (fn [s] (str (name s) " x" (count (get by-seat s)) " process(es)")) joined)))))
              (println (str "members fallen back (" (count absent) "):"))
              (doseq [r absent]
                (println (str "  " (:name r) " — " (:reason r))))
              (if big?
                (println "  -> their column groups were dealt to extra processes of the joined members; all 12 groups seeded.")
                (println "  -> expect-total was computed from the joined members only; the world still fully hydrated (the driver creates every cell)."))
              (println (str "ants: " expect " expected, "
                            (if (:all-in? @state) "all registered (DRIVER-ALL-IN)" "see the driver log")
                            "; snapshots audited: " audits))
              (println (str "audits: ants-ok=" ants-ok " registered-ok=" reg-ok " food-ok=" food-ok
                            " bad-audits=" bad-audits))
              (doseq [m member-exits]
                (println (str "member " (:label m) " exit=" (:exit m))))
              (when (seq @held)
                (println (str "held member(s) never spawned (the driver exited before DRIVER-READY): "
                              (str/join ", " (map (fn [h] (:label h)) @held)))))
              (doseq [m member-warnings]
                (println (str "member warning: " (:label m) " logged behave errors (see its log)")))
              (doseq [c stragglers]
                (println (str "member " (:label c) " did not exit within the grace period and was destroyed")))
              (println (str "driver exit: " driver-exit))
              (println (str "VERDICT: " (cond (:stopped? @state) "STOPPED (not a verdict)"
                                              clean? "PASS — the world hydrated fully and every audit held"
                                              :else "FAIL — see the driver log and FINAL-AUDIT above")))
              (println (str "logs: " rundir "/logs"))
              (println "===================================================")
              (flush)
              (System/exit (cond (:stopped? @state) 130
                                 clean? 0
                                 :else 1)))))))))

(main *command-line-args*)
