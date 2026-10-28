;; eve-media's launcher (babashka): creates the disk domain and the window, writes the run's children into the control
;; atom and starts the supervisor (clojerl's; the JVM's without a BEAM), which starts them and keeps them running;
;; writes the library, and checks the outputs.
;;
;;   bb eve-media demo [--hosts bb,jvm,...] [--kill transcode|coordinator] [--supervisor jvm]   (from eve/)
;;   bb eve-media scan <dir> [--hosts ...]     the library of a folder of your own
;;   bb eve-media verify                        the done bar: the runs of doc README §"The done bar", compared
;;
;; A run's directory (~/.eve-media/runs/<stamp>) holds media/ (the demo's generated files), out/ (transcodes,
;; thumbnails, contact sheets), d (the disk domain's files), logs/ (each member's), report.edn. The window is
;; /dev/shm/eve-media/<stamp> (decoded PCM; the system's temporary directory where there is no /dev/shm, as on macOS). The launcher reads and writes the domain's atoms; it does not join
;; eve.thread (the members do).
(ns eve-media.launcher
  (:refer-clojure :exclude [swap!])
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.http-client.websocket :as ws]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [eve.host :refer [swap!]]
            [eve.net.atom :as na]
            [eve.net.platform :as platform]
            [eve-media.core :as em]
            [eve-media.domain :as domain]))

(def eve-dir (str (fs/absolutize ".")))
(def app-dir (str (fs/path eve-dir "capstone" "eve-media")))
(def hosts-edn (edn/read-string (slurp (str (fs/path eve-dir "hosts.edn")))))

(defn now [] (System/currentTimeMillis))
(defn log [& xs] (println (str "[eve-media " (java.time.LocalTime/now) "]") (str/join " " xs)) (flush))

(defn roots [host & more]
  (str/join ":" (concat [(str (fs/path eve-dir "src"))]
                        (map #(str (fs/path eve-dir %)) (get hosts-edn host))
                        [(str (fs/path app-dir "src"))]
                        (map #(str (fs/path app-dir %)) more))))

(def tools-home
  "Where bb eve-media setup builds what no package manager has (clojerl)."
  (str (fs/path (fs/home) ".eve-media" "tools")))

(def clojerl-rev
  "The clojerl setup builds: the one eve's tool boxes run (/opt/eve-tools/clojerl, 2026-10)."
  "4ad14e5")

(defn clojerl-built?
  "Whether the clojerl at script (its bin/clojerl) is built: its clojure.core compiled (a clone whose make stopped is not)."
  [script]
  (boolean (and script (fs/executable? script)
                (fs/exists? (fs/path (fs/parent (fs/parent (fs/canonicalize script)))
                                     "_build" "default" "lib" "clojerl" "ebin" "clojure.core.beam")))))

(defn otp-release
  "The OTP release of the erl in dir (on PATH when nil), or nil."
  [dir]
  (let [erl (if dir (str (fs/path dir "erl")) (some-> (fs/which "erl") str))]
    (when (and erl (fs/executable? erl))
      (some-> (:out (p/shell {:continue true :out :string :err :string} erl "-noshell" "-eval"
                             "io:format(\"~s\",[erlang:system_info(otp_release)]),halt()."))
              str/trim parse-long))))

(def clojerl-otp
  "The OTP releases clojerl builds and runs on (its CI: 24-27). OTP 28's regexes (PCRE2) are references, not literals,
   and clojerl compiles a def's value as a literal: clojure.instant's #\"...\" stops the build."
  #{24 25 26 27})

(defn erlang-bin
  "A bin directory whose erl clojerl runs on: nil when the erl on PATH does; else Homebrew's erlang@27 when installed."
  []
  (when-not (clojerl-otp (otp-release nil))
    (let [b (some-> (fs/which "brew") str
                    (#(:out (p/shell {:continue true :out :string :err :string} % "--prefix" "erlang@27")))
                    str/trim (fs/path "bin") str)]
      (when (and b (clojerl-otp (otp-release b))) b))))

(def clojerl-runs?
  "Does the clojerl at script run (clojerl -e, the erl in erl-bin first on PATH when there is one)? Built is not enough:
   a clojerl built, or half built, under another OTP than the one it runs on cannot load its own modules (the
   operator's Mac, 2026-10-22: \"Failed to load module 'clojerl_cli' because it requires a more recent Erlang/OTP
   version\" on OTP 27). Once per pair."
  (memoize
   (fn [script erl-bin]
     (boolean
      (when (clojerl-built? script)
        (let [{:keys [exit out]} (p/shell {:continue true :out :string :err :string
                                           :extra-env {"PATH" (str (when erl-bin (str erl-bin ":")) (System/getenv "PATH"))}}
                                          script "-e" "(+ 1 2)")]
          (and (zero? exit) (= "3" (str/trim (str out))))))))))

(defn find-tools
  "The runtimes no package manager installs whole — clojerl (the supervisor), jolt and its Chez Scheme — and the page's
   browser: on PATH, else where setup builds them (~/.eve-media/tools), else where eve's tool boxes put them
   (/opt/eve-tools); clojerl the first that runs, with an erl it runs on (else the first that is built)."
  []
  (let [which (fn [n & fallbacks] (or (some-> (fs/which n) str) (some #(when (fs/executable? %) %) fallbacks) (last fallbacks)))
        clojerls (remove nil? [(some-> (fs/which "clojerl") str) (str (fs/path tools-home "clojerl" "bin" "clojerl"))
                               "/opt/eve-tools/clojerl/bin/clojerl"])
        erl-bin (erlang-bin)]
    {:clojerl (or (some #(when (clojerl-runs? % erl-bin) %) clojerls) (some #(when (clojerl-built? %) %) clojerls)
                  (last clojerls))
     :erl-bin erl-bin
     :jolt (which "jolt" "/opt/eve-tools/jolt/bin/jolt")
     ;; a jolt run from its source tree needs a Chez Scheme (JOLT_CHEZ); a release build (Homebrew's) carries its own
     :jolt-chez (or (System/getenv "JOLT_CHEZ")
                    (when (fs/executable? "/opt/eve-tools/chez/bin/scheme") "/opt/eve-tools/chez/bin/scheme"))
     ;; EVE_CHROME, else the first Chromium or Chrome found: Playwright's on this tree's Linux boxes, macOS's apps, PATH
     :chrome (or (System/getenv "EVE_CHROME")
                 (some (fn [c] (when (fs/executable? c) c))
                       ["/opt/pw-browsers/chromium"
                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                        "/Applications/Chromium.app/Contents/MacOS/Chromium"])
                 (some #(some-> (fs/which %) str) ["chromium" "chromium-browser" "google-chrome" "google-chrome-stable"])
                 "chromium")
     :path (str (when erl-bin (str erl-bin ":"))
                (fs/path tools-home "clojerl" "bin") ":/opt/eve-tools/jolt/bin:/opt/eve-tools/chez/bin:/opt/eve-tools/clojerl/bin:"
                (System/getenv "PATH"))}))

(def tools (find-tools))

(def maven-cp
  (delay (str/trim (:out (p/shell {:dir app-dir :out :string :err :string} "clojure" "-Spath")))))

(defn net-atoms
  "The control and library atoms as network atoms at the run's gateway (the Node member serves it): what the launcher
   writes through once members run — babashka writes no shared disk domain while a lock-free member may die
   mid-write (eve-media.domain's namespace doc). Making them sends nothing."
  [cfg]
  (let [p (platform/platform)
        mk (fn [nm]
             (na/remote-atom {:endpoint (str "http://127.0.0.1:"
                                             (:gateway-port cfg) "/gw")
                              :domain-id (:domain-id cfg)
                              :atom-id (str/replace nm "/" "%2F")
                              :api-key (:api-key cfg)} p))]
    {:control (mk em/control-name) :library (mk em/library-name)}))

(defn write!
  "Atom k (:control or :library) of the run swapped by (apply f value args): through the gateway once the network
   phase is on (retried while the gateway starts, up to 60 s), on the disk before any member runs."
  [atoms k f & args]
  (if-let [ra (when @(:net-on atoms) (get-in atoms [:net k]))]
    (let [t0 (now)]
      (loop []
        (let [r (try
                  (apply na/swap! ra f args)
                  ::ok
                  (catch Exception e e))]
          (cond
            (= r ::ok) nil
            (> (- (now) t0) 60000) (throw r)
            :else
            (do
              (Thread/sleep 500)
              (recur))))))
    (do
      (swap! (get atoms k) (fn [v] (apply f v args)))
      nil)))

(defn member-cmd
  "The command line starting member rid of host h on domain base (out, window); cfg the run's (control :config): a
   babashka member is given the gateway it joins through, the browser the page's URL."
  [h rid base out window cfg]
  (let [args [base out window rid]]
    (case h
      :jvm
      {:cmd (concat ["java"
                     "--add-opens"
                     "java.base/java.lang=ALL-UNNAMED"
                     "--enable-native-access=ALL-UNNAMED"
                     "-cp"
                     (str @maven-cp ":" (roots :jvm "members/jvm/src"))
                     "clojure.main"
                     "-m"
                     "eve-media.member"]
                    args)}
      :bb
      {:cmd (concat ["bb"
                     "-cp"
                     (roots :bb "members/bb/src")
                     "-m"
                     "eve-media.member"]
                    args
                    [(str "http://127.0.0.1:" (:gateway-port cfg) "/gw")
                     (:api-key cfg)])}
      :node
      {:cmd (concat ["node" (str (fs/path app-dir "target" "node-member.js"))]
                    args)
       :dir app-dir}
      :glj
      {:cmd (concat [(str (fs/path app-dir "target" "glj-eve-media" "glj-eve"))
                     "-e"
                     (str "(require 'eve.host-completion) (require 'eve-media.member) "
                          "(apply eve-media.member/-main *command-line-args*)")]
                    args)
       :env {"GLJ_CLASSPATH" (roots :glj "members/glj/src")}}
      :jolt
      {:cmd (concat [(:jolt tools)
                     "-Scp"
                     (roots :jolt "members/jolt/src")
                     "-m"
                     "eve-media.member"]
                    args)
       :env (cond-> {"PATH" (:path tools)} (:jolt-chez tools) (assoc "JOLT_CHEZ" (:jolt-chez tools)))}
      ;; the browser: Chromium on the page (its runtime joins over the network rung; ?check= the done bar's re-queue row),
      ;; a fresh profile each start — a window of its own where the run shows the page (:show-page), else headless
      :browser
      (let [url (str "http://127.0.0.1:" (:media-port cfg) "/index.html?check=f01:thumb")
            profile (str "--user-data-dir=" (fs/create-temp-dir {:prefix "eve-media-chrome-"}))]
        {:cmd (if (:show-page cfg)
                (concat [(:chrome tools) "--no-first-run" "--no-default-browser-check" "--no-proxy-server"
                         "--enable-logging=stderr" "--v=0" "--window-size=1400,1000" profile]
                        ;; Chromium refuses to run as root with its sandbox
                        (when (= "root" (System/getProperty "user.name")) ["--no-sandbox"])
                        [(str "--app=" url)])
                [(:chrome tools) "--headless=new" "--no-sandbox" "--disable-gpu" "--no-proxy-server" "--no-first-run"
                 "--enable-logging=stderr" "--v=0" profile url])}))))

(def default-hosts
  "A run's runtimes of the job atom: babashka (the index), the JVM (metadata, hashes, loudness — and the coordinator),
   Node (thumbnails, contact sheets, tags), glojure (the media server, the folder watcher), jolt (the transcoder and the
   PCM decoder, FFmpeg's libraries through its FFI) and the browser (waveforms; the page). clojerl supervises them —
   no runtime of the job atom. Without jolt the JVM transcodes by the ffmpeg CLI; without clojerl the JVM supervises."
  [:bb :jvm :node :glj :jolt :browser])

(defn supervisor-cmd
  "The command line of the run's supervisor of kind :clje (OTP, members/clje) or :jvm (members/jvm), on domain base."
  [kind base]
  (case kind
    :clje {:cmd (concat [(:clojerl tools)]
                        (mapcat (fn [r] ["-pa" r]) (str/split (roots :clje "members/clje/src") #":"))
                        ["-m" "eve-media.supervisor" base])
           :env {"PATH" (:path tools)}}
    :jvm {:cmd ["java" "--add-opens" "java.base/java.lang=ALL-UNNAMED" "--enable-native-access=ALL-UNNAMED"
                "-cp" (str @maven-cp ":" (roots :jvm "members/jvm/src"))
                "clojure.main" "-m" "eve-media.supervisor" base]}))

(defn child-specs
  "The run's children, as its supervisor takes them (eve-media.supervise): the gateway; a member per host of hosts —
   babashka once the gateway is up, the browser once the media server and the gateway are."
  [run cfg hosts]
  (let [logs (str (fs/path (:dir run) "logs"))]
    (into {"gateway" {:host :gateway :member? false :wait [] :logs logs :dir eve-dir :env {}
                      :cmd ["java" "--add-opens" "java.base/java.lang=ALL-UNNAMED" "-cp"
                            (str @maven-cp ":" (roots :jvm "members/jvm/src"))
                            "clojure.main" "-m" "eve-media.gateway" (:base run)]}}
          (map (fn [h]
                 (let [rid (name h)
                       {:keys [cmd env dir]} (member-cmd h rid (:base run) (:out run) (:window run) cfg)]
                   [rid {:host h :member? true :cmd (mapv str cmd) :env (or env {}) :dir (str (or dir eve-dir))
                         :logs logs :wait (case h :bb [:gateway] :browser [:media :gateway] [])}])))
          hosts)))

;; ---------------------------------------------------------------------------
;; The demo's media: ten files from ffmpeg's lavfi sources, two seeded duplicate pairs, no download
;; ---------------------------------------------------------------------------

(defn ffmpeg! [& args]
  (let [{:keys [exit err]} (apply p/shell {:continue true :out :string :err :string} "ffmpeg" "-nostdin" "-v" "error" "-y" args)]
    (when-not (zero? exit) (throw (ex-info (str "ffmpeg failed: " err) {:args args})))))

(def encoders
  "The encoders the ffmpeg on PATH has (ffmpeg -encoders): builds differ — Homebrew's has no libvorbis."
  (delay (->> (str/split-lines (:out (p/shell {:out :string :err :string} "ffmpeg" "-hide_banner" "-encoders")))
              (keep #(second (re-find #"^ [VAS][.A-Z]{5} (\S+)" %)))
              set)))

(defn generate-demo! [dir]
  (fs/create-dirs dir)
  (when-not (contains? @encoders "libx264")
    (throw (ex-info (str "eve-media: the ffmpeg on PATH has no libx264 encoder; the demo and every transcode are H.264 "
                         "(an ffmpeg built with libx264: Homebrew's, a distribution's ffmpeg package)") {})))
  (let [f (fn [n] (str (fs/path dir n)))
        ;; Vorbis by libvorbis where the build has it, else ffmpeg's own encoder (experimental, stereo only)
        vorbis (if (contains? @encoders "libvorbis") ["-c:a" "libvorbis"] ["-c:a" "vorbis" "-strict" "-2" "-ac" "2"])]
    (ffmpeg! "-f" "lavfi" "-i" "testsrc=size=1280x720:rate=25:duration=10" "-f" "lavfi" "-i" "sine=frequency=440:duration=10"
             "-c:v" "libx264" "-preset" "ultrafast" "-pix_fmt" "yuv420p" "-c:a" "aac" "-shortest" (f "f01-testsrc.mp4"))
    ;; a seeded duplicate: f01 resized and re-encoded into another container
    (apply ffmpeg! (concat ["-i" (f "f01-testsrc.mp4") "-vf" "scale=640:360" "-c:v" "mpeg4" "-q:v" "4"] vorbis
                           [(f "f02-testsrc-copy.mkv")]))
    (ffmpeg! "-f" "lavfi" "-i" "smptebars=size=1280x720:rate=25:duration=10" "-f" "lavfi" "-i" "sine=frequency=1000:duration=10"
             "-c:v" "mpeg4" "-q:v" "4" "-c:a" "aac" "-shortest" (f "f03-bars.mov"))
    (ffmpeg! "-f" "lavfi" "-i" "mandelbrot=size=1280x720:rate=25" "-t" "10" "-c:v" "mpeg4" "-q:v" "4" (f "f04-mandelbrot.mkv"))
    (ffmpeg! "-f" "lavfi" "-i" "testsrc2=size=1280x720:rate=25:duration=10" "-f" "lavfi"
             "-i" "anoisesrc=color=pink:duration=10:amplitude=0.25:seed=3" "-c:v" "mpeg4" "-q:v" "5" "-c:a" "pcm_s16le"
             "-shortest" (f "f05-noise.avi"))
    (ffmpeg! "-f" "lavfi" "-i" "sine=frequency=440:duration=10:sample_rate=48000" "-ac" "2" (f "f06-tone.wav"))
    (ffmpeg! "-f" "lavfi" "-i" "anoisesrc=color=pink:duration=10:amplitude=0.4:seed=5" "-ac" "2" "-c:a" "flac" (f "f07-pink.flac"))
    ;; an MP3 by libmp3lame; a build without it (ffmpeg has no MP3 encoder of its own) writes the tone as Ogg Vorbis
    (if (contains? @encoders "libmp3lame")
      (ffmpeg! "-f" "lavfi" "-i" "sine=frequency=220:duration=10,volume=0.5" "-ac" "2" "-c:a" "libmp3lame" "-b:a" "128k"
               (f "f08-low.mp3"))
      (apply ffmpeg! (concat ["-f" "lavfi" "-i" "sine=frequency=220:duration=10,volume=0.5" "-ac" "2"] vorbis
                             [(f "f08-low.ogg")])))
    (ffmpeg! "-f" "lavfi" "-i" "gradients=size=1280x720:seed=7:nb_colors=4" "-frames:v" "1" (f "f09-gradient.png"))
    ;; the second seeded duplicate: f09 resized, as a JPEG
    (ffmpeg! "-i" (f "f09-gradient.png") "-vf" "scale=640:360" "-q:v" "3" (f "f10-gradient-small.jpg"))
    (sort (map str (fs/list-dir dir)))))

;; ---------------------------------------------------------------------------
;; The library: every media file of a folder, written as :new (the coordinator plans each)
;; ---------------------------------------------------------------------------

(defn scan!
  "Every media file of dir written into the library as :new (eve-media.core/add-files, one swap): the launcher's scan,
   when no Go member watches the folder."
  [atoms dir]
  (let [files (->> (fs/list-dir dir)
                   (map str)
                   (filter em/media-kind)
                   (map (fn [p]
                          {:path p
                           :size (fs/size p)
                           :mtime (str (fs/last-modified-time p))})))]
    (write! atoms :library em/add-files files "launcher")
    (count files)))

;; ---------------------------------------------------------------------------
;; Members
;; ---------------------------------------------------------------------------

(defn clojerl-ready?
  "Can clojerl supervise here: it runs (on an OTP 24-27 erl), and a C compiler for eve's NIF?"
  []
  (boolean (and (clojerl-runs? (:clojerl tools) (:erl-bin tools))
                (or (clojerl-otp (otp-release nil)) (:erl-bin tools))
                (or (fs/which "gcc") (fs/which "cc")))))

(defn start-supervisor!
  "The run's supervisor: clojerl's (unless opts says :supervisor :jvm, or clojerl cannot run here), the JVM's when it
   does not come up (its note in the control atom, within 180 s) or ends first. {:kind :proc}."
  [run atoms opts]
  (let [start (fn [kind]
                (let [{:keys [cmd env]} (supervisor-cmd kind (:base run))
                      logf (str (fs/path (:dir run) "logs" (str "supervisor." (name kind) ".log")))
                      pr (apply p/process {:out (fs/file logf) :err (fs/file (str logf ".err"))
                                           :extra-env (or env {}) :dir eve-dir}
                                cmd)]
                  (log "started the supervisor:" (name kind) "pid" (.pid ^Process (:proc pr)))
                  pr))
        up (fn [pr]
             (let [t0 (now)]
               (loop []
                 (cond (:supervisor @(:control atoms)) :up
                       (not (p/alive? pr)) :ended
                       (> (- (now) t0) 180000) :timeout
                       :else (do (Thread/sleep 300) (recur))))))
        kind (if (and (not= :jvm (:supervisor opts)) (clojerl-ready?)) :clje :jvm)
        _ (when (and (= :jvm kind) (not= :jvm (:supervisor opts)))
            (log "clojerl cannot run here (bb eve-media setup builds it): the JVM supervises"))
        pr (start kind)
        r (up pr)]
    (cond
      (= :up r) {:kind kind :proc pr}
      (= :clje kind)
      (do (log "the clojerl supervisor" (name r) "before it was up (logs/supervisor.clje.log.err): the JVM supervises")
          (p/destroy-tree pr)
          (let [pr (start :jvm) r (up pr)]
            (if (= :up r)
              {:kind :jvm :proc pr}
              (throw (ex-info (str "eve-media: the JVM supervisor " (name r) " before it was up") {})))))
      :else (throw (ex-info (str "eve-media: the JVM supervisor " (name r) " before it was up") {})))))

(defn alive? [pid] (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" (str pid)))))

(defn kill-9! [pid] (p/shell {:continue true} "kill" "-9" (str pid)))

;; ---------------------------------------------------------------------------
;; A run
;; ---------------------------------------------------------------------------

(defn new-run [opts]
  (let [stamp (str (java.time.LocalDateTime/now))
        stamp (str/replace (subs stamp 0 (min 23 (count stamp))) #"[:.]" "-")
        dir (str (fs/path (fs/home) ".eve-media" "runs" stamp))
        window (str (fs/path (if (fs/directory? "/dev/shm") "/dev/shm" (fs/temp-dir)) "eve-media" stamp))]
    (fs/create-dirs (fs/path dir "logs"))
    (fs/create-dirs (fs/path dir "out"))
    (fs/create-dirs window)
    {:dir dir :base (str (fs/path dir "d")) :out (str (fs/path dir "out")) :window window :opts opts}))

(defn descs [atoms] (:descs (domain/meta-value atoms)))

(defn complete? [{:keys [library index]}]
  (let [files (:files @library) ix @index]
    (and (seq files)
         (every? (fn [[_ e]] (contains? #{:done :error} (:status e))) files)
         (:complete? ix)
         (= (count files) (get-in ix [:job :generation])))))

(defn kill-step!
  "The kill tests: kill -9 of a member — :kill \"transcode\": the one running a transcode, once it has run a second;
   :kill \"coordinator\": the coordinator, once a file is done and another still planned. Its supervisor starts it
   again."
  [run atoms state]
  (when (and (= "coordinator" (get-in run [:opts :kill])) (not (:killed @state)))
    (let [statuses (map :status (vals (:files @(:library atoms))))
          ctl @(:control atoms)
          rid (get-in ctl [:config :coordinator])
          pid (get-in ctl [:members rid :pid])]
      (when (and pid (some #{:done} statuses) (some #{:planned} statuses))
        (log "kill -9 the coordinator" rid "pid" pid)
        (kill-9! pid)
        (clojure.core/swap! state assoc :killed {:rid rid :pid pid :coordinator true :at (now)}))))
  (when (and (= "transcode" (get-in run [:opts :kill])) (not (:killed @state)))
    (let [running (filter (fn [[_ d]] (and (= :running (:state d))
                                           (= (em/task-sym :transcode) (get-in d [:work :task]))
                                           (> (- (now) (:claimed d 0)) 1000)))
                          (descs atoms))]
      (when-let [[id d] (first running)]
        (let [rid (first (str/split (:owner d) #"#"))
              pid (get-in @(:control atoms) [:members rid :pid])]
          (when pid
            (log "kill -9" rid "pid" pid "mid-transcode of" id (get-in d [:work :args 0 :id]))
            (kill-9! pid)
            (clojure.core/swap! state assoc :killed {:rid rid :pid pid :descriptor id :file (get-in d [:work :args 0 :id])
                                                     :at (now)})))))))

(defn wait-complete! [run atoms procs state timeout-ms]
  (let [t0 (now)]
    (loop [last-print 0]
      (cond
        (complete? atoms) true
        (> (- (now) t0) timeout-ms) false
        (not (p/alive? (get @procs "supervisor"))) (do (log "the supervisor ended before the run did") false)
        :else
        (do (kill-step! run atoms state)
            (let [lp (if (> (- (now) last-print) 5000)
                       (let [files (:files @(:library atoms))
                             ctl @(:control atoms)]
                         (log "progress" (frequencies (map (comp :status val) files))
                              "coordinator" (get-in ctl [:config :coordinator])
                              "supervisor" (some-> (get-in ctl [:supervisor :host]) name))
                         (now))
                       last-print)]
              (Thread/sleep 300)
              (recur lp)))))))

(defn stop-members!
  "The run's stop written; the supervisor lets its children end (and ends what does not: the browser), then ends
   itself — or, after 40 s, it and everything under it are killed."
  [atoms procs]
  (try
    (write! atoms :control assoc :stop true)
    (catch Exception e
      (log "the stop could not be written (" (ex-message e) "); the supervisor and its children are killed")))
  (when-let [pr (get @procs "supervisor")]
    (when (= ::timeout (deref pr 40000 ::timeout))
      (log "the supervisor did not end in time: it and its children killed"))
    (p/destroy-tree pr)))

;; ---------------------------------------------------------------------------
;; The checks (P§8.6 as amended)
;; ---------------------------------------------------------------------------

(defn sh-out [& args] (:out (apply p/shell {:continue true :out :string :err :string} args)))

(defn probe-codecs [path]
  (set (str/split-lines (str/trim (sh-out "ffprobe" "-v" "error" "-show_entries" "stream=codec_name" "-of" "csv=p=0" path)))))

(defn image-dims [path]
  (let [[w h] (str/split (str/trim (sh-out "ffprobe" "-v" "error" "-select_streams" "v:0" "-show_entries"
                                           "stream=width,height" "-of" "csv=p=0:s=x" path)) #"x")]
    (when (and w h) [(parse-long w) (parse-long h)])))

(defn ffmpeg-lufs [path]
  (let [err (:err (p/shell {:continue true :out :string :err :string} "ffmpeg" "-nostdin" "-nostats" "-i" path
                           "-map" "0:a:0" "-af" "ebur128" "-f" "null" "-"))
        i (last (re-seq #"I:\s+(-?[0-9.]+) LUFS" err))]
    (some-> i second parse-double)))

(defn sha256
  "A file's SHA-256, hex (in process: macOS has no sha256sum)."
  [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (java.io.FileInputStream. (str path))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn rid-of [owner] (when owner (first (str/split owner #"#"))))

(defn check [run atoms state]
  (let [files (:files @(:library atoms))
        ix @(:index atoms)
        meta (domain/meta-value atoms)
        by-id (into {} (map (fn [[_ e]] [(:id e) e]) files))
        transcodes (keep (fn [[_ e]] (when-let [t (get-in e [:results :transcode :path])] [(:id e) (:kind e) t])) files)
        thumbs (keep (fn [[_ e]] (when-let [t (get-in e [:results :thumb :path])] [(:id e) t])) files)
        sheets (keep (fn [[_ e]] (when-let [t (get-in e [:results :sheet :path])] [(:id e) t])) files)
        loud (keep (fn [[p e]] (when-let [l (get-in e [:results :loudness :lufs])] [(:id e) l (ffmpeg-lufs p)])) files)
        ran-on (set (concat (mapcat (fn [[_ e]] (map rid-of (vals (:ran-on e)))) files)
                            (map rid-of (vals (:ran-on ix)))))
        ctl @(:control atoms)
        started (set (keep (fn [[rid m]] (when (:member? m) rid)) (:members ctl)))
        children (set (keys (:children ctl)))
        registry (set (keys (:runtimes meta)))
        mp4 (mapv (fn [[id k t]] (let [c (probe-codecs t)]
                                   {:id id :codecs c :ok (if (= :audio k) (= #{"aac"} c) (and (contains? c "h264") (or (= c #{"h264"}) (contains? c "aac"))))}))
                  transcodes)
        dims (mapv (fn [[id t]] (let [[w _ :as d] (image-dims t)] {:id id :dims d :ok (= em/thumb-width w)})) thumbs)
        sheet-dims [(* em/sheet-cols em/sheet-tile-width) (* em/sheet-rows em/sheet-tile-height)]
        sdims (mapv (fn [[id t]] (let [d (image-dims t)] {:id id :dims d :ok (= sheet-dims d)})) sheets)
        louds (mapv (fn [[id ours theirs]] {:id id :eve-media ours :ffmpeg theirs
                                            :ok (and theirs (<= (Math/abs (- ours theirs)) 0.5))}) loud)
        killed (:killed @state)
        starts (get-in ctl [:members (:rid killed) :incarnation] 0)
        kill-row (when killed
                   (if (:coordinator killed)
                     {:killed (:rid killed) :coordinator true :starts starts :ok (>= starts 2)}
                     (let [e (get by-id (:file killed))]
                       {:file (:file killed) :killed (:rid killed) :attempts (get-in e [:attempts :transcode])
                        :starts starts :sha256 (some-> (get-in e [:results :transcode :path]) sha256)
                        :ok (and (= 2 (get-in e [:attempts :transcode])) (>= starts 2))})))
        expected-groups [["f01" "f02"] ["f09" "f10"]]
        checks {:index-complete (boolean (:complete? ix))
                :every-file-done (every? (fn [[_ e]] (= :done (:status e))) files)
                :every-runtime-in-meta (every? registry started)
                :every-runtime-in-ran-on (every? ran-on started)
                :mp4s-h264-aac (and (seq mp4) (every? :ok mp4))
                :thumbnails (and (seq dims) (every? :ok dims))
                :contact-sheets (and (seq sheets) (every? :ok sdims))
                :loudness-within-0.5-lu (and (seq louds) (every? :ok louds))
                :two-duplicate-groups (= expected-groups (:duplicates ix))}
        checks (assoc checks
                      ;; every child the launcher wrote was started by the supervisor, and none was given up on
                      :supervised (and (seq children)
                                       (every? (fn [rid] (pos? (get-in ctl [:members rid :incarnation] 0))) children)
                                       (not-any? (fn [[_ m]] (= :gave-up (:state m))) (:members ctl))))
        checks (cond-> checks
                 (and killed (:coordinator killed)) (assoc :coordinator-restarted (:ok kill-row))
                 (and killed (not (:coordinator killed))) (assoc :kill-attempts-2-restarted (:ok kill-row)))]
    {:ok (every? true? (vals checks))
     :checks checks
     :coordinator (:coordinator ix)
     :supervisor (some-> (get-in ctl [:supervisor :host]) name)
     :members started :registry registry :ran-on ran-on
     :mp4 mp4 :thumbs dims :sheets sdims :loudness louds :duplicates (:duplicates ix)
     :kill kill-row
     :checksums (into (sorted-map) (map (fn [[id _ t]] [id (sha256 t)]) transcodes))
     :thumb-checksums (into (sorted-map) (map (fn [[id t]] [id (sha256 t)]) thumbs))
     :sheet-checksums (into (sorted-map) (map (fn [[id t]] [id (sha256 t)]) sheets))}))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn parse-opts [args]
  (loop [[a & more] args
         opts {}]
    (cond
      (nil? a) opts
      (= a "--supervisor") (recur (rest more) (assoc opts :supervisor (keyword (first more))))
      (= a "--hosts")
      (recur (rest more)
             (assoc opts :hosts (mapv keyword (str/split (first more) #","))))
      (= a "--kill") (recur (rest more) (assoc opts :kill (first more)))
      (= a "--only") (recur (rest more) (assoc opts :only (first more)))
      (= a "--keep") (recur more (assoc opts :keep true))
      (= a "--no-browser") (recur more (assoc opts :no-browser true))
      (= a "--headless") (recur more (assoc opts :headless true))
      (= a "--install") (recur more (assoc opts :install true))
      (= a "--timeout")
      (recur (rest more) (assoc opts :timeout (parse-long (first more)))) :else
      (recur more (update opts :args (fnil conj []) a)))))

(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn random-hex [n]
  (apply str (repeatedly n #(rand-nth "0123456789abcdef"))))

(defn display?
  "Whether this machine shows windows: macOS, or a Linux session with an X or Wayland display."
  []
  (boolean (or (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
               (some #(seq (System/getenv %)) ["DISPLAY" "WAYLAND_DISPLAY"]))))

(defn configure!
  "The run's servers configured in the control atom before any member starts: the media server's and the gateway's
   ports, the watched folder (the Go member's watcher scans it when a Go member runs), the page's directory, and the
   gateway's api key and store secret, minted for this run."
  [run atoms hosts media-dir]
  (let [cfg {:media-port (free-port)
             :gateway-port (free-port)
             :page (str (fs/path (:dir run) "page"))
             :watch (when (some #{:glj} hosts) media-dir)
             :domain-id "eve-media"
             :api-key (random-hex 32)
             :store-secret (random-hex 32)
             ;; the coordinator: the JVM member (babashka's in a run without a JVM) — named, no election
             :coordinator (if (some #{:jvm} hosts) "jvm" "bb")
             ;; the page in a window of its own where the machine shows windows (--headless: never)
             :show-page (and (not (:headless (:opts run))) (display?))}]
    (swap! (:control atoms) assoc :config cfg)
    cfg))

(defn prepare-page!
  "The page's directory for this run: index.html, the compiled page (target/page/js: main.js, and the manifest.edn its
   worker threads find it by), and its config.json (the domain id and api key it signs in with, the run's output and
   window directories). Is the compiled page there?"
  [run cfg]
  (let [dir (:page cfg)
        built (fs/path app-dir "target" "page" "js")
        js (fs/path built "main.js")]
    (fs/create-dirs (fs/path dir "js"))
    (fs/copy (fs/path app-dir "page" "index.html") (fs/path dir "index.html")
             {:replace-existing true})
    (doseq [f ["main.js" "manifest.edn"]
            :when (fs/exists? (fs/path built f))]
      (fs/copy (fs/path built f) (fs/path dir "js" f) {:replace-existing true}))
    (spit (str (fs/path dir "config.json"))
          (str "{\"domain_id\":\"" (:domain-id cfg) "\",\"api_key\":\""
               (:api-key cfg) "\",\"out\":\"" (:out run) "\",\"window\":\""
               (:window run) "\"}"))
    (fs/exists? js)))

(defn- cdp-screenshot!
  "A screenshot of url into shot, taken once the page's table holds n rows (or after timeout-ms): headless Chromium
   driven over the DevTools protocol. (A fixed --virtual-time-budget raced the page: its workers read the library over
   the network rung while virtual time ran on, and the shot caught an empty table.) Answers the rows it saw."
  [url shot n timeout-ms]
  (let [dir (str (fs/create-temp-dir {:prefix "eve-media-shot-"}))
        proc (p/process {:out :string :err :string} (:chrome tools)
                        "--headless=new" "--no-sandbox" "--disable-gpu"
                        "--no-proxy-server" "--hide-scrollbars"
                        (str "--user-data-dir=" dir) "--window-size=1400,1100"
                        "--remote-debugging-port=0" "about:blank")
        port-file (str (fs/path dir "DevToolsActivePort"))]
    (try
      (let [port (loop [i 0]
                   (cond
                     (and (fs/exists? port-file)
                          (seq (str/trim (slurp port-file))))
                     (first (str/split-lines (slurp port-file))) (< i 200)
                     (do
                       (Thread/sleep 100)
                       (recur (inc i)))
                     :else
                     (throw (ex-info "Chromium opened no DevTools port" {}))))
            targets (json/parse-string (:body (http/get (str "http://127.0.0.1:"
                                                             port "/json/list")))
                                       true)
            ws-url (:webSocketDebuggerUrl (first (filter (fn [t]
                                                           (= "page" (:type t)))
                                                         targets)))
            pending (atom {})
            ids (atom 0)
            ;; (a large reply — the screenshot — arrives in parts: a message is whole at its last part)
            part (StringBuilder.)
            sock (ws/websocket {:uri ws-url
                                :on-message (fn [_ data last?]
                                              (.append part (str data))
                                              (when last?
                                                (let [m (json/parse-string (str part)
                                                                           true)]
                                                  (.setLength part 0)
                                                  (when-let [pr (get @pending
                                                                     (:id m))]
                                                    (deliver pr m)))))})
            call (fn [method params]
                   (let [id (clojure.core/swap! ids inc)
                         pr (promise)]
                     (clojure.core/swap! pending assoc id pr)
                     (ws/send! sock
                               (json/generate-string {:id id
                                                      :method method
                                                      :params params}))
                     (let [r (deref pr 30000 nil)]
                       (clojure.core/swap! pending dissoc id)
                       (:result r))))
            rows (fn []
                   (or (get-in (call "Runtime.evaluate"
                                     {:expression "document.querySelectorAll('tbody tr').length"
                                      :returnByValue true}) [:result :value]) 0))
            _ (call "Page.navigate" {:url url})
            t0 (now)
            seen (loop []
                   (let [r (rows)]
                     (if (or (>= r n) (> (- (now) t0) timeout-ms))
                       r
                       (do
                         (Thread/sleep 500)
                         (recur)))))]
        ;; (the thumbnails load after their rows)
        (Thread/sleep 1500)
        (when-let [png (:data (call "Page.captureScreenshot" {:format "png"}))]
          (fs/write-bytes shot
                          (.decode (java.util.Base64/getDecoder) ^String png)))
        (ws/close! sock)
        seen)
      (finally (p/destroy-tree proc)))))

(defn browser-check!
  "Wait (up to timeout-ms) for the page's check (the browser member, started with ?check=f01:thumb, writes it into
   control :page-check); then a screenshot of the page (?runtime=0: a view only, no second browser runtime), once its
   table shows every file of the library."
  [run atoms cfg timeout-ms]
  (let [t0 (now)
        got (loop []
              (or (:page-check @(:control atoms))
                  (when (< (- (now) t0) timeout-ms) (Thread/sleep 500) (recur))))
        shot (str (fs/path (:dir run) "page.png"))
        n (count (:files @(:library atoms)))
        [ok? seen] (try
                     [true
                      (cdp-screenshot! (str "http://127.0.0.1:"
                                            (:media-port cfg)
                                            "/index.html?runtime=0") shot n
                                       60000)]
                     (catch Exception e [false (ex-message e)]))]
    (log "browser check" (if got "received" "NOT received") "screenshot" shot
         (if ok? (str seen " of " n " rows") (str "failed: " seen)))
    (assoc (or got {:ok false}) :screenshot (when (fs/exists? shot) shot)
           :screenshot-rows (when ok? seen))))

(defn- newest
  "The newest modification time (ms) of the Clojure sources under dirs."
  [dirs]
  (reduce max 0
          (for [d dirs
                :when (fs/exists? d)
                f (fs/glob d "**.{clj,cljs,cljc,glj,clje,go}")]
            (.toMillis (fs/last-modified-time f)))))

(defn- stale? [target dirs]
  (or (not (fs/exists? target))
      (< (.toMillis (fs/last-modified-time target)) (newest dirs))))

;; ---------------------------------------------------------------------------
;; What a run needs of this machine, checked before anything starts
;; ---------------------------------------------------------------------------

(def jolt-runs?
  "Does the jolt at jolt run here (jolt -e, with the Chez Scheme chez where it is a source tree's)? Once per pair."
  (memoize
   (fn [jolt chez]
     (boolean
      (when (and jolt (fs/executable? jolt))
        (let [{:keys [exit out]} (p/shell {:continue true :out :string :err :string
                                           :extra-env (cond-> {"PATH" (:path tools)} chez (assoc "JOLT_CHEZ" chez))}
                                          jolt "-e" "(+ 1 2)")]
          (and (zero? exit) (= "3" (str/trim (str out))))))))))

(defn ffmpeg-libs
  "The majors of FFmpeg's libavutil this machine's loader finds — 58, 59, 60: FFmpeg 6.1, 7.x, 8.x, what jolt's
   transcoder reads (members/jolt/src/eve_media/av.clj) — in EVE_FFMPEG_LIB, Homebrew's and MacPorts' lib directories
   on macOS, the loader's cache (ldconfig -p) on Linux."
  []
  (let [mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
        dirs (concat (keep not-empty [(System/getenv "EVE_FFMPEG_LIB")])
                     (when mac? ["/opt/homebrew/lib" "/usr/local/lib" "/opt/local/lib"]))
        cache (when-not mac?
                (str (:out (p/shell {:continue true :out :string :err :string} "sh" "-c"
                                    "ldconfig -p 2>/dev/null || /sbin/ldconfig -p"))))]
    (set (filter (fn [m]
                   (let [file (if mac? (str "libavutil." m ".dylib") (str "libavutil.so." m))]
                     (or (some (fn [d] (fs/exists? (fs/path d file))) dirs)
                         (and cache (str/includes? cache file)))))
                 [58 59 60]))))

(defn checks
  "What hosts need of this machine, one check each: {:ok? :msg :brew [formulae] :cask name :build kw}. Linux and macOS
   run every member."
  [hosts]
  (let [hosts (set hosts)
        os (str/lower-case (System/getProperty "os.name"))
        linux? (str/includes? os "linux")
        on-path? (fn [c] (some? (fs/which c)))
        ffmpeg? (on-path? "ffmpeg")
        ck (fn [ok? msg & {:as how}] (merge {:ok? (boolean ok?) :msg msg} how))]
    (vec (remove nil?
                 [(ck ffmpeg? "ffmpeg on PATH (the demo's media, every transcode, the loudness check)" :brew ["ffmpeg"])
                  (ck (on-path? "ffprobe") "ffprobe on PATH (the checks read every output's streams)" :brew ["ffmpeg"])
                  (ck (or (not ffmpeg?) (contains? @encoders "libx264"))
                      "an ffmpeg built with libx264 (every transcode is H.264): Homebrew's, a distribution's package")
                  (when (:jvm hosts) (ck (on-path? "java") "java (the JVM member)" :brew ["openjdk"]))
                  (when (:jvm hosts) (ck (on-path? "clojure") "the clojure CLI (the JVM member's classpath)" :brew ["clojure"]))
                  (when (:node hosts) (ck (and (on-path? "node") (on-path? "npm"))
                                          "node and npm (the Node member; eve's native addon builds on npm install)" :brew ["node"]))
                  (when (:glj hosts) (ck (on-path? "go") "go (the Go member's glj-eve-media is built from source)" :brew ["go"]))
                  (when (:browser hosts)
                    (ck (fs/executable? (:chrome tools))
                        "a Chromium or Chrome for the page (none found: EVE_CHROME=/path/to/chrome, or install one)"
                        :cask "google-chrome"))
                  (when (:clje hosts) (ck (or (clojerl-otp (otp-release nil)) (:erl-bin tools))
                                          (str "an Erlang/OTP 24-27 for clojerl, the run's supervisor (the erl on PATH is OTP " (or (otp-release nil) "none")
                                               "; OTP 28's regexes are not literals clojerl can compile)")
                                          :brew ["erlang@27"]))
                  (when (:clje hosts) (ck (clojerl-runs? (:clojerl tools) (:erl-bin tools))
                                          (str "clojerl (the run's supervisor; the JVM supervises without it), built on the OTP it runs "
                                               "on and running (clojerl -e): on PATH, ~/.eve-media/tools or /opt/eve-tools")
                                          :build :clojerl))
                  (when (:clje hosts) (ck (or (on-path? "gcc") (on-path? "cc"))
                                          "a C compiler (clojerl builds eve's NIF on first load): xcode-select --install on macOS"))
                  (when (:jolt hosts)
                    (ck (jolt-runs? (:jolt tools) (:jolt-chez tools))
                        (str "jolt, running (jolt -e): Homebrew's (macOS arm64 14+), jolt's install script "
                             "(curl -sL https://raw.githubusercontent.com/jolt-lang/jolt/main/install | bash), or a source "
                             "tree with its Chez Scheme (JOLT_CHEZ)")
                        :brew ["jolt-lang/jolt/jolt"]))
                  (when (:jolt hosts)
                    (ck (seq (ffmpeg-libs))
                        (str "FFmpeg's shared libraries, 6.1, 7.x or 8.x (libavutil 58-60), for jolt's transcoder: "
                             "Homebrew's ffmpeg, a distribution's libavcodec/libavformat packages, or EVE_FFMPEG_LIB=<dir>")
                        :brew ["ffmpeg"]))
                  (when (and (:jolt hosts) linux?)
                    (ck (str/includes? (str (:out (p/shell {:continue true :out :string :err :string} "sh" "-c"
                                                           "ldconfig -p 2>/dev/null || /sbin/ldconfig -p")))
                                       "libatomic.so.1")
                        "libatomic.so.1 (jolt's eve regions call its atomics): a distribution's libatomic1 package"))]))))

(defn- how
  "What bb eve-media setup does for a failing check (Homebrew's where there is one, or on macOS), or nil."
  [{:keys [brew cask build]}]
  (let [brew? (or (some? (fs/which "brew")) (str/includes? (str/lower-case (System/getProperty "os.name")) "mac"))]
    (cond build "bb eve-media setup builds it"
          (and brew? brew) (str "brew install " (str/join " " brew))
          (and brew? cask) (str "brew install --cask " cask))))

(defn preflight
  "What hosts need of this machine that it lacks: one line each, with what installs it (empty: ready)."
  [hosts]
  (vec (keep (fn [c] (when-not (:ok? c) (str (:msg c) (some->> (how c) (str " — "))))) (checks hosts))))

(defn install-clojerl!
  "clojerl at clojerl-rev, built into ~/.eve-media/tools/clojerl with an OTP 24-27 erl first on PATH (Homebrew's
   erlang@27 where the erl on PATH is newer), rebar3, git and make from Homebrew when absent."
  []
  (let [dir (str (fs/path tools-home "clojerl"))
        brew? (some? (fs/which "brew"))
        brew! (fn [formula]
                (if brew?
                  (do (log "setup: brew install" formula) (p/shell "brew" "install" formula))
                  (throw (ex-info (str "eve-media setup: clojerl's build needs " formula ", and there is no Homebrew")
                                  {:missing [formula]}))))]
    (doseq [[cmd formula] [["rebar3" "rebar3"] ["git" "git"] ["make" "make"]]]
      (when-not (fs/which cmd) (brew! formula)))
    (when-not (or (clojerl-otp (otp-release nil)) (erlang-bin)) (brew! "erlang@27"))
    (let [erl-bin (erlang-bin)
          env {:extra-env {"PATH" (str (when erl-bin (str erl-bin ":")) (System/getenv "PATH"))}}]
      (fs/create-dirs tools-home)
      (when-not (fs/exists? (fs/path dir ".git"))
        (log "setup: git clone https://github.com/clojerl/clojerl" dir)
        (p/shell "git" "clone" "-q" "https://github.com/clojerl/clojerl" dir))
      (p/shell {:dir dir} "git" "checkout" "-q" clojerl-rev)
      (fs/delete-tree (fs/path dir "_build"))   ; a make that stopped (or ran on another OTP) leaves its beams
      (log "setup: make (clojerl" clojerl-rev "on OTP" (otp-release erl-bin) ")")
      (let [r (p/shell (merge env {:dir dir :continue true}) "make")]
        (when-not (zero? (:exit r))
          (throw (ex-info (str "eve-media setup: clojerl's make failed (above), on OTP " (otp-release erl-bin))
                          {:missing ["clojerl"]})))))))

(defn setup!
  "Install what hosts need and this machine lacks, where it can: Homebrew's formulae and casks, clojerl built from
   source. Then the checks again; answers what is still missing."
  [hosts]
  (let [hosts (conj (set hosts) :clje)
        failing (remove :ok? (checks hosts))
        brew? (some? (fs/which "brew"))
        formulae (distinct (mapcat :brew failing))
        casks (distinct (keep :cask failing))]
    (when (and (seq (concat formulae casks)) (not brew?))
      (log "setup: no Homebrew (https://brew.sh) — install these yourself:" (str/join ", " (concat formulae casks))))
    (when (and brew? (seq formulae))
      (log "setup: brew install" (str/join " " formulae))
      (apply p/shell "brew" "install" formulae))
    (when (and brew? (seq casks))
      (log "setup: brew install --cask" (str/join " " casks))
      (apply p/shell "brew" "install" "--cask" casks))
    (when (some #(= :clojerl (:build %)) failing) (install-clojerl!))
    (alter-var-root #'tools (fn [_] (find-tools)))
    (let [left (preflight hosts)]
      (if (seq left)
        (log "setup: still missing:\n  " (str/join "\n  " left))
        (log "setup: ready for" (str/join " " (map name (sort (set hosts))))))
      left)))

(defn ensure-built!
  "What the members and the page run, built when absent or older than their sources (force?: always): the npm
   libraries, the Node member and the page (shadow-cljs through deps.edn's :eve-media alias), glj-eve-media (Go)."
  [hosts force?]
  (let [hosts (set hosts)
        sh! (fn [what dir & cmd]
              (log "build:" what)
              (let [r (apply p/shell
                             {:dir dir :continue true :out :string :err :string}
                             cmd)]
                (when-not (zero? (:exit r))
                  (println (:out r) (:err r))
                  (throw (ex-info (str "build failed: " what) {:cmd cmd})))))
        eve-src (str (fs/path eve-dir "src"))
        ;; (the adapters a ClojureScript build composes: the browser's http and thread adapters, Node's http server)
        eve-adapters (str (fs/path eve-dir "adapters"))
        app-src (str (fs/path app-dir "src"))]
    ;; eve's own npm packages (shadow-cljs) and its native addon (built by npm install: node-gyp-build), which the Node
    ;; member's disk domains load
    (when (and (:node hosts)
               (or force?
                   (not (fs/exists? (fs/path eve-dir "build" "Release" "mmap_cas.node")))
                   (not (fs/exists? (fs/path eve-dir "node_modules" "shadow-cljs")))))
      (sh! "npm install in eve/ (shadow-cljs; the native addon)" eve-dir "npm" "install"
           "--no-audit" "--no-fund"))
    (when (and (:node hosts)
               (or force?
                   (not (fs/exists? (fs/path app-dir "node_modules" "sharp")))))
      (sh! "npm install (sharp, music-metadata)" app-dir "npm" "install"
           "--no-audit" "--no-fund"))
    (when (and (:node hosts)
               (or force?
                   (stale? (fs/path app-dir "target" "node-member.js")
                           [eve-src
                            eve-adapters
                            app-src
                            (str (fs/path app-dir "members" "node" "src"))])))
      (sh! "the Node member (shadow-cljs release eve-media-node)" eve-dir
           "clojure" "-M:node-test:eve-media-node" "-m"
           "shadow.cljs.devtools.cli" "release" "eve-media-node"))
    (when (and (:glj hosts)
               (or force?
                   (stale? (fs/path app-dir "target" "page" "js" "main.js")
                           [eve-src
                            eve-adapters
                            app-src
                            (str (fs/path app-dir "page" "src"))])))
      (sh! "the page (shadow-cljs release eve-media-page)" eve-dir "clojure"
           "-M:browser-test:eve-media-page" "-m" "shadow.cljs.devtools.cli"
           "release" "eve-media-page"))
    (when (:glj hosts)
      (sh! "glj-eve-media (glj/build.sh; cached by its source stamp)" app-dir
           "bash" "glj/build.sh"))
    nil))

(defn drop-file!
  "Live pick-up: once the index is complete, a new file dropped into the watched folder (an eleventh, generated here:
   three seconds of tone, written under a name the watcher skips and renamed into place); then the watcher's entry for
   it and the index are waited for again. {:ok :file :id :found-by :status :picked-up-ms :ms}."
  [run atoms procs state media-dir timeout-ms]
  (let [dest (str (fs/path media-dir "f11-dropped.wav"))
        part (str dest ".part")]
    (ffmpeg! "-f" "lavfi" "-i" "sine=frequency=330:duration=3:sample_rate=48000"
             "-ac" "2" "-f" "wav" part)
    (let [t0 (now)]
      (fs/move part dest {:replace-existing true})
      (log "dropped" dest "into the watched folder")
      (let [seen (loop []
                   (cond
                     (get-in @(:library atoms) [:files dest]) (- (now) t0)
                     (> (- (now) t0) 30000) nil
                     :else
                     (do
                       (Thread/sleep 200)
                       (recur))))
            ok? (and seen (wait-complete! run atoms procs state timeout-ms))
            e (get-in @(:library atoms) [:files dest])]
        (log "the dropped file"
             (if seen (str "picked up in " seen " ms") "NOT picked up")
             "; index" (if ok? "complete" "not complete"))
        {:ok (boolean (and ok? e (= :done (:status e))
                           (= 11 (count (:files @(:library atoms))))))
         :file dest
         :id (:id e)
         :found-by (:found-by e)
         :status (:status e)
         :picked-up-ms seen
         :ms (- (now) t0)}))))

(defn run-library!
  "A run on a fresh domain: its children written into the control atom (the gateway, a member per runtime, the
   browser) and the supervisor started, which starts them and keeps them running; dir's media written into the library
   (by the Go member's watcher, or the launcher's scan when no Go member runs); the index waited for; the page's check;
   the report. With :drop, the media are copied into the run and an eleventh file is dropped into the watched folder
   once the index is complete."
  [opts media-dir]
  (let [hosts (let [hs (or (:hosts opts) default-hosts)
                    jolt? (or (not (some #{:jolt} hs)) (:strict opts) (empty? (preflight [:jolt])))]
                (when-not jolt?
                  (log "jolt cannot run here (" (str/join "; " (preflight [:jolt])) "): the JVM's ffmpeg CLI transcodes"))
                (vec (cond->> hs
                       (not jolt?) (remove #{:jolt})
                       (or (:no-browser opts) (not (some #{:glj} hs)))
                       (remove #{:browser}))))
        browser? (boolean (some #{:browser} hosts))
        _ (when (:install opts) (setup! hosts))
        _ (when-let [missing (seq (preflight hosts))]
            (throw (ex-info (str "eve-media: this machine lacks what " (str/join " " (map name hosts)) " need:\n  "
                                 (str/join "\n  " missing) "\n--install installs what it can (Homebrew; clojerl from source)")
                            {:missing missing})))
        _ (ensure-built! hosts false)
        run (new-run opts)
        _ (log "run" (:dir run) "window" (:window run))
        media-dir (if (:drop opts)
                    (let [d (str (fs/path (:dir run) "media"))]
                      (fs/copy-tree media-dir d)
                      d)
                    media-dir)
        atoms (domain/open-atoms (:base run))
        cfg (configure! run atoms hosts media-dir)
        atoms (assoc atoms :net (net-atoms cfg) :net-on (atom false))
        page? (prepare-page! run cfg)
        procs (atom {})
        state (atom {})]
    (try
      ;; the children, written on the disk before any of them runs; then the supervisor, which starts them
      (swap! (:control atoms) assoc :children
             (child-specs run cfg (cond->> hosts (not page?) (remove #{:browser}))))
      (let [{:keys [proc]} (start-supervisor! run atoms opts)]
        (clojure.core/swap! procs assoc "supervisor" proc))
      ;; from here on the launcher writes through the gateway (a child of its own), reads from the disk
      (reset! (:net-on atoms) true)
      (if (:watch cfg)
        (log "the Go member's watcher scans" media-dir)
        (log "scan" media-dir (scan! atoms media-dir) "files"))
      (let [t0 (now)
            timeout (or (:timeout opts) 600000)
            ok? (wait-complete! run atoms procs state timeout)
            wall (- (now) t0)
            dropped (when (and ok? (:drop opts) (:watch cfg))
                      (drop-file! run atoms procs state media-dir timeout))
            page-check (when (and browser? page?)
                         (browser-check! run atoms cfg 180000))
            nfiles (count (:files @(:library atoms)))
            base (if ok?
                   (check run atoms state)
                   {:ok false :checks {:index-complete false}})
            base (cond-> base
                   browser?
                   (assoc-in [:checks :browser-library-and-requeue]
                             (boolean (and (:ok page-check)
                                           (= nfiles (:rows page-check))
                                           (= :done (last (:seen page-check))))))
                   (:drop opts)
                   (assoc-in [:checks :watcher-new-file]
                             (boolean (:ok dropped))))
            base (assoc base :ok (every? true? (vals (:checks base))))
            report (assoc base :wall-ms wall :hosts hosts :run (:dir run) :set
                          (:set opts) :restarts (:restarts @(:control atoms))
                          :page page-check :drop dropped :servers
                          (:servers @(:control atoms)) :found-by
                          (frequencies (map (comp :found-by val)
                                            (:files @(:library atoms)))))]
        (log (if ok? "complete in" "TIMED OUT after") wall "ms")
        (spit (str (fs/path (:dir run) "report.edn")) (pr-str report))
        (log "report" (str (fs/path (:dir run) "report.edn")))
        (println (pr-str (select-keys report
                                      [:ok
                                       :checks
                                       :coordinator
                                       :supervisor
                                       :members
                                       :ran-on
                                       :kill
                                       :drop
                                       :wall-ms])))
        (when (and (:stay opts) browser? page? (:show-page cfg))
          (log "the page stays up:" (str "http://127.0.0.1:" (:media-port cfg) "/index.html") "— Enter ends the run")
          (read-line))
        report)
      (finally (stop-members! atoms procs)))))

(defn demo-media!
  "The demo's ten files (generated once into ~/.eve-media/demo-media): their directory."
  []
  (let [media (str (fs/path (fs/home) ".eve-media" "demo-media"))]
    (when-not (and (fs/exists? media) (= 10 (count (fs/list-dir media))))
      (fs/delete-tree media)
      (log "generating ten demo files (lavfi) in" media)
      (generate-demo! media))
    media))

(def verify-runs
  "The done bar's runs: the whole set undisturbed (the page, and an eleventh file dropped into the watched folder);
   jolt killed mid-transcode, and the coordinator (the JVM) killed mid-run — each started again by the supervisor; the
   JVM supervising (no BEAM); the ffmpeg CLI transcoding (no jolt)."
  [{:name "full" :hosts default-hosts :browser true :drop true}
   {:name "full-kill" :hosts (vec (remove #{:browser} default-hosts)) :kill "transcode"}
   {:name "coordinator-kill" :hosts (vec (remove #{:browser} default-hosts)) :kill "coordinator"}
   {:name "jvm-supervises" :hosts (vec (remove #{:browser} default-hosts)) :supervisor :jvm}
   {:name "cli-transcode" :hosts (vec (remove #{:browser :jolt} default-hosts))}])

(defn verify!
  "Every run of verify-runs, then the comparisons the done bar asks for: each run's checks; the killed transcode at
   :attempts 2 with the undisturbed run's checksum; the killed coordinator started again and the same outputs; the JVM
   supervising with the same outputs. Writes ~/.eve-media/verify-<stamp>.edn; answers it."
  [opts]
  (let [only (some-> (:only opts) (str/split #",") set)
        runs (filterv (fn [r] (or (nil? only) (contains? only (:name r))))
                      verify-runs)
        _ (when (:install opts) (setup! (distinct (mapcat :hosts runs))))
        unready (keep (fn [r] (when-let [m (seq (preflight (:hosts r)))] [(:name r) m])) runs)
        _ (when (seq unready)
            (let [ready (remove (set (map first unready)) (map :name runs))]
              (throw (ex-info (str "eve-media verify: run(s) this machine cannot start:\n"
                                   (str/join "\n" (map (fn [[n m]] (str "  " n ": " (str/join "; " m))) unready))
                                   "\nbb eve-media verify --install installs what it can"
                                   (when (seq ready) (str "\nthe ones it can run now: bb eve-media verify --only " (str/join "," ready))))
                              {:unready (into {} unready)}))))
        media (demo-media!)
        reports (reduce (fn [acc {:keys [name hosts kill browser drop supervisor]}]
                          (log "=== verify run" name hosts (if kill (str "kill " kill) "")
                               (if supervisor (str "supervisor " (clojure.core/name supervisor)) ""))
                          (assoc acc name
                                 (run-library! (cond-> (assoc opts :hosts hosts :set name :strict true)
                                                 kill (assoc :kill kill)
                                                 drop (assoc :drop true)
                                                 supervisor (assoc :supervisor supervisor)
                                                 (not browser) (assoc :no-browser true))
                                               media)))
                        {} runs)
        ten (map (fn [i] (str "f" (when (< i 10) "0") i)) (range 1 11))
        outputs (fn [r]
                  (-> (select-keys r [:checksums :thumb-checksums :sheet-checksums :duplicates])
                      (update :checksums select-keys ten)
                      (update :thumb-checksums select-keys ten)
                      (update :sheet-checksums select-keys ten)))
        same? (fn [a b]
                (and (get reports a) (get reports b)
                     (= (outputs (get reports a)) (outputs (get reports b)))))
        kill-ok? (fn [k base]
                   (let [row (:kill (get reports k))]
                     (and row (:ok row) (get reports base)
                          (= (:sha256 row) (get-in (get reports base) [:checksums (:file row)])))))
        rows (cond-> {}
               (get reports "full")
               (assoc :full-checks (:ok (get reports "full"))
                      :full-supervised-by-clojerl (= "clje" (:supervisor (get reports "full"))))
               (get reports "full-kill")
               (assoc :full-kill-attempts-2-same-checksum (boolean (kill-ok? "full-kill" "full")))
               (get reports "coordinator-kill")
               (assoc :coordinator-kill-restarted (boolean (get-in reports ["coordinator-kill" :kill :ok]))
                      :coordinator-kill-same-outputs (boolean (same? "full" "coordinator-kill")))
               (get reports "jvm-supervises")
               (assoc :jvm-supervises (= "jvm" (:supervisor (get reports "jvm-supervises")))
                      :jvm-supervises-same-outputs (boolean (same? "full" "jvm-supervises")))
               (get reports "cli-transcode")
               (assoc :cli-transcode-checks (:ok (get reports "cli-transcode"))))
        all-checks (into {} (map (fn [[n r]] [n (:ok r)]) reports))
        out {:ok (and (every? true? (vals rows)) (every? true? (vals all-checks)))
             :rows rows
             :run-checks all-checks
             :runs (into {} (map (fn [[n r]]
                                   [n (select-keys r [:run :hosts :coordinator :supervisor :wall-ms :kill :checks
                                                      :ran-on :checksums :page :drop])])
                                 reports))}
        f (str (fs/path (fs/home) ".eve-media"
                        (str "verify-" (str/replace (str (java.time.LocalDateTime/now)) #"[:.]" "-") ".edn")))]
    (spit f (pr-str out))
    (log "verify" (if (:ok out) "PASSED" "FAILED") f)
    (println (pr-str (select-keys out [:ok :rows :run-checks])))
    out))

(defn -main [& [cmd & args]]
  (let [opts (parse-opts args)]
    (try
    (case cmd
      "demo"
      (let [r (run-library! (assoc opts :stay true) (demo-media!))]
        (System/exit (if (:ok r) 0 1)))
      "scan"
      (let [r (run-library! (assoc opts :stay true) (str (fs/absolutize (first (:args opts)))))]
        (System/exit (if (:ok r) 0 1)))
      "verify"
      (let [r (verify! opts)]
        (System/exit (if (:ok r) 0 1)))
      "setup"
      ;; what the runs named (--only, verify's) or the hosts need, and clojerl to supervise: installed where it can be
      (let [hosts (if-let [only (some-> (:only opts) (str/split #",") set)]
                    (distinct (mapcat :hosts (filter #(only (:name %)) verify-runs)))
                    (or (:hosts opts) default-hosts))]
        (System/exit (if (empty? (setup! hosts)) 0 1)))
      "build"
      (do
        (ensure-built! default-hosts true)
        (System/exit 0))
      (do
        (println "usage: bb eve-media demo|scan <dir>|verify|setup|build [--hosts h,...]"
                 "[--kill transcode|coordinator] [--supervisor jvm] [--no-browser] [--headless] [--only run,...]"
                 "[--install]")
        (System/exit 2)))
    ;; what this machine lacks: the message alone, no stack trace
    (catch clojure.lang.ExceptionInfo e
      (if (let [d (ex-data e)] (and (map? d) (or (:unready d) (:missing d))))
        (do (binding [*out* *err*] (println (ex-message e))) (System/exit 1))
        (throw e))))))

(apply -main *command-line-args*)
