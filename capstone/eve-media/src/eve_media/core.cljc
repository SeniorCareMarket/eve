(ns eve-media.core
  "eve-media's names, shared by every member: the atoms of its domain, its pools and the caps that serve them, the
   media kinds, and where outputs go. Portable cljc; no host is named here (a member's host code is its
   eve-media.tasks, under members/<host>/).

   The domain (a disk domain, durable) holds:
     eve-media/jobs      the rendezvous atom: its meta-atom is eve.thread's metadata — the runtime registry, the pools,
                         every job as a thread descriptor (eve.thread.domain; P§5.4's eve.thread/meta)
     eve-media/library   {:files {path entry}} — every media file, its status, its jobs' ids and results
     eve-media/index     {:complete? :files n :duplicates [[id …] …] :loudness {id lufs} :generation g}
     eve-media/control   the run's shared notes: its configuration (the coordinator named), the children the supervisor
                         starts and what became of them, restarts the coordinator asked for, the stop
   The window (tmpfs) holds bulk regions: decoded PCM, read in place by the loudness pool."
  (:require [clojure.string :as str]))

(def jobs-name "eve-media/jobs")
(def library-name "eve-media/library")
(def index-name "eve-media/index")
(def control-name "eve-media/control")

(def ttl
  "Lease horizon of every member, ms: a member killed mid-job has its job adopted once this passes."
  4000)

(def ext->kind
  {"mp4" :video "mkv" :video "mov" :video "avi" :video "webm" :video
   "wav" :audio "flac" :audio "mp3" :audio "m4a" :audio "ogg" :audio
   "png" :image "jpg" :image "jpeg" :image "webp" :image})

(defn extension [path]
  (let [i (str/last-index-of path ".")] (when i (str/lower-case (subs path (inc i))))))

(defn media-kind [path] (get ext->kind (extension path)))

(defn base-name [path] (let [i (str/last-index-of path "/")] (if i (subs path (inc i)) path)))

;; ---------------------------------------------------------------------------
;; Jobs: each a task (a registered name every member that runs it defines in its eve-media.tasks), a pool, and the
;; caps a claimer must have (:needs). One pool per job family; the caps choose between members of the same family.
;; ---------------------------------------------------------------------------

(def task-ns "eve-media.tasks")

(defn task-sym [kind] (symbol task-ns (name kind)))

(def job-pool
  {:meta :meta
   :phash :phash
   :transcode :transcode
   :pcm :pcm
   :loudness :loudness
   :thumb :npm
   :sheet :npm
   :tags :npm
   :publish :publish
   :index :index
   :waveform :browser})

(def pools
  "Every pool the application uses (the coordinator's own :group queue aside)."
  (vec (distinct (vals job-pool))))

(defn group-pool
  "The coordinator's own queue for the duplicate grouping: push as a hint, P§5.4 — the coordinator still pulls it."
  [rid]
  (keyword (str "group." rid)))

(defn needs
  "The caps a job of kind needs, given the caps the registry's runtimes offer now: the native transcoder and PCM
   decoder (jolt's) when a member offers them, the ffmpeg CLI otherwise."
  [kind offered]
  (case kind
    :transcode
    (if (contains? offered :transcode-native)
      #{:transcode-native}
      #{:transcode-cli})
    :pcm (if (contains? offered :pcm-native) #{:pcm-native} #{:pcm-cli})
    :meta #{:meta}
    :phash #{:phash}
    :loudness #{:loudness}
    (:thumb :sheet :tags) #{:npm}
    :publish #{:serve}
    :index #{:index}
    :waveform #{:browser}
    #{}))

(defn file-jobs
  "The jobs a file of media kind k gets, each [kind after-kinds], given the caps offered: a DAG — thumbnails before the
   contact sheet, PCM before loudness, the transcode before it is published; and, when a browser runtime has joined,
   the waveform it draws after loudness."
  [k offered]
  (let [waveform (when (contains? offered :browser) [[:waveform [:loudness]]])]
    (case k
      :video
      (into [[:meta []]
             [:phash []]
             [:transcode []]
             [:thumb []]
             [:sheet [:thumb]]
             [:pcm []]
             [:loudness [:pcm]]
             [:publish [:transcode]]]
        waveform)
      :audio
      (into [[:meta []]
             [:tags []]
             [:transcode []]
             [:pcm []]
             [:loudness [:pcm]]
             [:publish [:transcode]]]
        waveform)
      :image [[:meta []] [:phash []] [:thumb []]]
      [])))

;; ---------------------------------------------------------------------------
;; Outputs (under the output directory a library names)
;; ---------------------------------------------------------------------------

(defn output-path [out-dir kind id]
  (case kind
    :transcode (str out-dir "/web/" id ".mp4")
    :thumb (str out-dir "/thumbs/" id ".jpg")
    :sheet (str out-dir "/sheets/" id ".jpg")
    nil))

(def thumb-width 320)
(def sheet-cols 4)
(def sheet-rows 3)
(def sheet-tile-width 160)
(def sheet-tile-height 90)

(defn pcm-path [window id] (str window "/pcm/" id ".f32"))

(defn add-files
  "The library value v with every file of files ({:path :size :mtime}) it does not hold yet added as :new, in path
   order, each numbered by its arrival (f01, f02, ...): the launcher's scan and the Go member's folder watcher write the
   library this one way, in one swap each. found-by: the writer, kept in the entry."
  [v files found-by]
  (reduce (fn [v {:keys [path size mtime]}]
            (if (or (get-in v [:files path]) (nil? (media-kind path)))
              v
              (let [n (inc (count (:files v)))]
                (assoc-in v [:files path]
                          {:id (str "f" (when (< n 10) "0") n)
                           :path path
                           :kind (media-kind path)
                           :size size
                           :mtime mtime
                           :status :new
                           :found-by found-by})))) v (sort-by :path files)))
