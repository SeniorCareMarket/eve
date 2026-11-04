(ns eve-media.tasks
  "The jolt member's jobs: the native transcoder (:transcode-native) and the native PCM decoder (:pcm-native) —
   FFmpeg's libraries called through jolt's FFI (eve-media.av). With this member in the set the coordinator asks for
   these caps instead of the ffmpeg CLI's (eve-media.core/needs)."
  (:require [eve.thread :as t]
            [eve.thread-host :as th]
            [eve-media.av :as av]
            [eve-media.core :as em]
            [eve-media.work :as work]))

(def caps #{:transcode-native :pcm-native})
(def pools {:transcode 1 :pcm 1})
(def options {})

(defn attempt [f] (th/attempt f))
(defn exit! [code] (System/exit code))
(defn init! [_ctx] nil)

(t/deftask transcode [{:keys [path id kind out]}]
  (let [dest (em/output-path out :transcode id)]
    (av/mkdirs! (av/parent dest))
    (let [counts (av/transcode! path dest (= :audio kind))]
      (work/record! path :transcode (merge {:path dest :bytes (av/file-size dest) :by :transcode-native} counts)))))

(t/deftask pcm [{:keys [path id window]}]
  (let [dest (em/pcm-path window id)]
    (av/mkdirs! (av/parent dest))
    (work/record! path :pcm
                  (if-let [{:keys [bytes]} (av/pcm! path dest)]
                    {:window dest :offset 0 :length bytes :channels 2 :rate 48000 :format :f32le :by :pcm-native}
                    {:none true :reason "no audio stream"}))))
