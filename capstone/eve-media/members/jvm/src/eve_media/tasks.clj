(ns eve-media.tasks
  "The JVM member's jobs: metadata (Apache Tika's audio/video and image parsers, metadata-extractor through Tika's
   image module), perceptual hashes (a 32×32 gray frame, ffmpeg's scaler, eve-media.phash), the transcode by the
   ffmpeg CLI (:transcode-cli: the core set's transcoder), PCM decoded by the CLI into a window region (:pcm-cli), and
   EBU R128 loudness read in place from that region (eve.region over the window; eve-media.r128) — the loudness pool
   re-homed here from VS (operator override, 2026-10-20), its descriptor and data shape unchanged. The coordinator
   (eve-media.coordinator) runs on this member, and the duplicate grouping on its own queue."
  (:refer-clojure :exclude [meta])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [eve.mem :as mem]
            [eve.region :as region]
            [eve.thread :as t]
            [eve-media.core :as em]
            [eve-media.phash :as ph]
            [eve-media.r128 :as r128]
            [eve-media.state :as st]
            [eve-media.work :as work])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.io File]
           [org.apache.tika Tika]
           [org.apache.tika.metadata Metadata]
           [org.apache.tika.parser AutoDetectParser ParseContext]
           [org.xml.sax.helpers DefaultHandler]))

(def caps #{:meta :phash :transcode-cli :pcm-cli :loudness})
(def pools {:meta 2 :phash 1 :transcode 1 :pcm 1 :loudness 1})
(def options {})

(defn attempt [f] (try [true (f)] (catch Throwable e [false e])))
(defn exit! [code] (shutdown-agents) (System/exit code))
(defn init! [_ctx]
  nil)

(defn- sh
  "Run a command, answer {:exit :out (bytes) :err (text)}."
  [& args]
  (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str args)) (.redirectErrorStream false)))
        out (future (with-open [in (.getInputStream p)] (.readAllBytes in)))
        err (future (slurp (.getErrorStream p)))]
    {:exit (.waitFor p) :out @out :err @err}))

(defn- ffmpeg [& args] (apply sh "ffmpeg" "-nostdin" "-v" "error" "-y" args))

(defn- ffprobe-streams [path]
  (let [{:keys [out]} (sh "ffprobe" "-v" "error" "-show_entries"
                          "stream=index,codec_type,codec_name,width,height,sample_rate,channels:format=duration,format_name"
                          "-of" "compact=p=0" path)]
    (str/split-lines (String. ^bytes out "UTF-8"))))

(t/deftask meta [{:keys [path] :as job}]
  (let [md (Metadata.)
        mime (.detect (Tika.) (io/file path))]
    (with-open [in (io/input-stream path)]
      (.parse (AutoDetectParser.) in (DefaultHandler.) md (ParseContext.)))
    (work/record! path :meta
                  {:mime mime
                   :tika (into (sorted-map) (keep (fn [n] (when-not (str/starts-with? n "X-TIKA") [n (.get md n)]))
                                                  (.names md)))
                   :streams (ffprobe-streams path)
                   :size (.length (io/file path))})))

(t/deftask phash [{:keys [path kind]}]
  (let [{:keys [exit out err]} (apply ffmpeg (concat (when (= :video kind) ["-ss" "1"])
                                                     ["-i" path "-frames:v" "1" "-vf" "scale=32:32:flags=area,format=gray"
                                                      "-f" "rawvideo" "-"]))
        px (mapv (fn [b] (bit-and b 0xFF)) out)]
    (when-not (and (zero? exit) (= 1024 (count px)))
      (throw (ex-info "phash: no 32x32 frame" {:exit exit :err err})))
    (work/record! path :phash {:phash (ph/phash px)})))

(defn transcode-args
  "The ffmpeg CLI's arguments for a web-ready MP4 (H.264/AAC; audio-only AAC for an audio file): bit-exact and
   single-threaded, so a rerun writes the same bytes."
  [in kind out]
  (concat ["-i" in "-map_metadata" "-1" "-fflags" "+bitexact" "-flags" "+bitexact"]
          (if (= :audio kind)
            ["-vn"]
            ["-map" "0:v:0" "-map" "0:a?" "-c:v" "libx264" "-preset" "medium" "-crf" "23" "-pix_fmt" "yuv420p"
             "-threads" "1" "-x264-params" "threads=1:sliced-threads=0"])
          ["-c:a" "aac" "-b:a" "128k" "-ac" "2" "-ar" "48000" "-movflags" "+faststart" "-f" "mp4" out]))

(t/deftask transcode [{:keys [path id kind out]}]
  (let [dest (em/output-path out :transcode id)
        tmp (str dest ".tmp-" (.pid (java.lang.ProcessHandle/current)))]
    (.mkdirs (.getParentFile (io/file dest)))
    (let [{:keys [exit err]} (apply ffmpeg (transcode-args path kind tmp))]
      (when-not (zero? exit) (throw (ex-info "transcode failed" {:err err})))
      (.renameTo (io/file tmp) (io/file dest))
      (work/record! path :transcode {:path dest :bytes (.length (io/file dest)) :by :transcode-cli}))))

(t/deftask pcm [{:keys [path id window]}]
  (let [dest (em/pcm-path window id)]
    (.mkdirs (.getParentFile (io/file dest)))
    (let [{:keys [exit err]} (ffmpeg "-i" path "-map" "0:a:0" "-ac" "2" "-ar" "48000" "-f" "f32le" dest)]
      (work/record! path :pcm
                    (if (and (zero? exit) (.exists (io/file dest)))
                      {:window dest :offset 0 :length (.length (io/file dest)) :channels 2 :rate 48000 :format :f32le}
                      {:none true :reason (first (str/split-lines (str err)))})))))

(defn- region-floats
  "The f32le samples of a window region, read in place through eve's region interface."
  [{:keys [window offset length]}]
  (let [r (region/open-mmap-region window (+ offset length))
        bs ^bytes (mem/-read-bytes r offset length)
        fb (.asFloatBuffer (.order (ByteBuffer/wrap bs) ByteOrder/LITTLE_ENDIAN))
        xs (double-array (.remaining fb))]
    (dotimes [i (alength xs)] (aset xs i (double (.get fb i))))
    xs))

(t/deftask loudness [{:keys [path]}]
  (let [pcm (get-in @(st/library) [:files path :results :pcm])]
    (work/record! path :loudness
                  (if (or (nil? pcm) (:none pcm))
                    {:lufs nil :no-audio true}
                    (let [xs (region-floats pcm)]
                      {:lufs (some-> (r128/integrated xs (:channels pcm) (:rate pcm)) (* 100.0) Math/round (/ 100.0))
                       :waveform (r128/peaks xs (:channels pcm) 100)
                       :read-from (:window pcm)})))))

(t/deftask group [job] (work/group! job))
