(ns cohost-media.av
  "The audio/video facility behind cohost-media.tasks' transcode and pcm: one surface, two implementations.
   The ffmpeg CLI is this namespace's own (the core set's transcoder: :transcode-cli / :pcm-cli). Where a
   member's pool config names the native implementation (:facilities {:av :native} in the config the boot
   program hands the pool), the runtime's FFmpeg-FFI implementation answers instead — jolt's, in
   cohost-media.av-jolt, resolved lazily: only a runtime that carries it ever loads it. Which ran is
   recorded in the result (:by :transcode-native / :transcode-cli, :pcm-native), as before."
  (:require ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [clojure.string :as str]
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [cohost-media.core :as em]
   [cohost-media.state :as st]
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [cohost-media.work :as work]))

(defn- native-fn
  "The native implementation's var, when this runtime carries it."
  [sym]
  #?(:cljs nil
     :default (try (requiring-resolve sym) (catch Throwable _ nil))))

#?(:clj
   (defn- sh
     "Run a command, answer {:exit :out (bytes) :err (text)}."
     [& args]
     (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str args)) (.redirectErrorStream false)))
           out (future (with-open [in (.getInputStream p)] (.readAllBytes in)))
           err (future (slurp (.getErrorStream p)))]
       {:exit (.waitFor p) :out @out :err @err})))

#?(:clj
   (defn- ffmpeg [& args] (apply sh "ffmpeg" "-nostdin" "-v" "error" "-y" args)))

#?(:clj
   (defn transcode-args
     "The ffmpeg CLI's arguments for a web-ready MP4 (H.264/AAC; audio-only AAC for an audio file):
      bit-exact and single-threaded, so a rerun writes the same bytes."
     [in kind out]
     (concat ["-i" in "-map_metadata" "-1" "-fflags" "+bitexact" "-flags" "+bitexact"]
             (if (= :audio kind)
               ["-vn"]
               ["-map" "0:v:0" "-map" "0:a?" "-c:v" "libx264" "-preset" "medium" "-crf" "23" "-pix_fmt" "yuv420p"
                "-threads" "1" "-x264-params" "threads=1:sliced-threads=0"])
             ["-c:a" "aac" "-b:a" "128k" "-ac" "2" "-ar" "48000" "-movflags" "+faststart" "-f" "mp4" out])))

(defn- cli-transcode! [{:keys [path id kind out]}]
  #?(:clj
     (let [dest (em/output-path out :transcode id)
           tmp (str dest ".tmp-" (.pid (java.lang.ProcessHandle/current)))]
       (.mkdirs (.getParentFile (java.io.File. dest)))
       (let [{:keys [exit err]} (apply ffmpeg (transcode-args path kind tmp))]
         (when-not (zero? exit) (throw (ex-info "transcode failed" {:err err})))
         (.renameTo (java.io.File. tmp) (java.io.File. dest))
         (work/record! path :transcode {:path dest :bytes (.length (java.io.File. dest)) :by :transcode-cli})))
     :default (throw (ex-info "av: no transcoder on this runtime" {}))))

(defn transcode!
  "Transcode the job's file to its web MP4 and record the result — natively where the member's pool
   config names the native implementation, by the ffmpeg CLI otherwise."
  [job]
  ;; The :av test reads the member's runtime pool config
  ;; (st/ctx), not a constant: kondo models the in-repo default
  ;; ctx, under which it reads always-false on this pass.
  (if-let [f #_{:clj-kondo/ignore [:constant-condition]} (and (= :native (:av @st/ctx)) (native-fn 'cohost-media.av-jolt/transcode-job!))]
    (f job)
    (cli-transcode! job)))

(defn- cli-pcm! [{:keys [path id window]}]
  #?(:clj
     (let [dest (em/pcm-path window id)]
       (.mkdirs (.getParentFile (java.io.File. dest)))
       (let [{:keys [exit err]} (ffmpeg "-i" path "-map" "0:a:0" "-ac" "2" "-ar" "48000" "-f" "f32le" dest)]
         (work/record! path :pcm
                       (if (and (zero? exit) (.exists (java.io.File. dest)))
                         {:window dest :offset 0 :length (.length (java.io.File. dest)) :channels 2 :rate 48000 :format :f32le}
                         {:none true :reason (first (str/split-lines (str err)))}))))
     :default (throw (ex-info "av: no PCM decoder on this runtime" {}))))

(defn pcm!
  "Decode the job's audio to PCM in its window file and record the result — natively where the member's
   pool config names the native implementation, by the ffmpeg CLI otherwise."
  [job]
  (if-let [f #_{:clj-kondo/ignore [:constant-condition]} (and (= :native (:av @st/ctx)) (native-fn 'cohost-media.av-jolt/pcm-job!))]
    (f job)
    (cli-pcm! job)))
